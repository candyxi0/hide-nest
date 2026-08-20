import { describe, expect, it } from "vitest";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import {
  loadMemoryEvidenceConfig,
  retrieveMemoryEvidence,
  MemoryEvidenceClientError,
  type MemoryEvidenceConfig,
} from "./memory-evidence-client.js";
import type { MemoryEvidenceRequest } from "./memory-evidence-canonicalizer.js";

const VALID_TOKEN = "synthetic-token-" + "0123456789abcdef".repeat(4);
const PROBLEM_CANARY = "PROBLEM_CANARY_LEAK";
const BODY_CANARY = "证据正文canary-EVIDENCE";

const REQUEST_ID = "00000000-0000-4000-8000-000000000001";
const MEMORY_ID = "00000000-0000-4000-8000-000000000002";
const MEMORY_REVISION_ID = "00000000-0000-4000-8000-000000000003";
const ANCHOR_A = "10000000-0000-4000-8000-000000000001";
const ACTOR_HIDE = "20000000-0000-4000-8000-000000000001";

function makeRequest(overrides?: Partial<MemoryEvidenceRequest>): MemoryEvidenceRequest {
  return {
    memoryId: MEMORY_ID,
    memoryRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
    ...overrides,
  };
}

function successBody(req: MemoryEvidenceRequest, bodyText = BODY_CANARY): Record<string, unknown> {
  return {
    requestId: REQUEST_ID,
    resultCategory: "SUCCEEDED",
    memoryId: req.memoryId,
    currentRevisionId: req.memoryRevisionId,
    revisionNo: req.revisionNo,
    evidenceItems: [
      {
        anchorId: ANCHOR_A,
        sourceUnitId: "30000000-0000-4000-8000-000000000001",
        ordinal: 10,
        actorId: ACTOR_HIDE,
        actorKind: "USER",
        actorStableRef: "user:xiaolin",
        displayLabel: "hide",
        occurredAt: "2026-08-17T12:00:00+08:00",
        bodyText,
      },
    ],
  };
}

interface Observed {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
  body: string;
}

interface ServerHandle {
  baseUrl: string;
  requests: Observed[];
  close: () => Promise<void>;
}

async function startServer(
  handler: (req: IncomingMessage, res: ServerResponse, body: string) => void,
): Promise<ServerHandle> {
  const requests: Observed[] = [];
  const server: Server = createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      requests.push({ method: req.method ?? "", url: req.url ?? "", headers: req.headers, body });
      handler(req, res, body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
  const address = server.address();
  const port = typeof address === "object" && address !== null ? address.port : 0;
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

function configFor(handle: ServerHandle, overrides?: Partial<MemoryEvidenceConfig>): MemoryEvidenceConfig {
  return { baseUrl: handle.baseUrl, token: VALID_TOKEN, ...overrides };
}

async function expectCode(promise: Promise<unknown>, code: string): Promise<void> {
  let caught: unknown;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(MemoryEvidenceClientError);
  expect((caught as MemoryEvidenceClientError).code).toBe(code);
}

describe("loadMemoryEvidenceConfig", () => {
  it("fails closed LOCAL_CONFIGURATION_MISSING when token is missing or low entropy", () => {
    expect(() => loadMemoryEvidenceConfig({})).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
    expect(() => loadMemoryEvidenceConfig({ HIDE_NEST_SYNTHETIC_TOKEN: "short" })).toThrowError(
      /LOCAL_CONFIGURATION_MISSING/,
    );
  });

  it("succeeds without a capability configured", () => {
    const config = loadMemoryEvidenceConfig({ HIDE_NEST_SYNTHETIC_TOKEN: VALID_TOKEN });
    expect(config.token).toBe(VALID_TOKEN);
    expect(config.baseUrl).toBe("http://127.0.0.1:8080");
  });

  it("rejects a non-loopback base URL", () => {
    expect(() =>
      loadMemoryEvidenceConfig({
        HIDE_NEST_SYNTHETIC_TOKEN: VALID_TOKEN,
        HIDE_NEST_API_BASE_URL: "http://example.com",
      }),
    ).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
  });
});

describe("retrieveMemoryEvidence success + request shape", () => {
  it("GETs the closed URL with bearer-only headers and re-validates the response", async () => {
    const request = makeRequest();
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(successBody(request)));
    });

    const result = await retrieveMemoryEvidence(request, configFor(handle));
    expect(result.resultCategory).toBe("SUCCEEDED");
    expect(result.segments[0].messages[0].bodyText).toBe(BODY_CANARY);
    expect(result.segments[0].messages[0].displayLabel).toBe("hide");

    const observed = handle.requests[0];
    expect(observed.method).toBe("GET");
    expect(observed.url).toBe(
      `/v1/memories/${MEMORY_ID}/evidence?revisionId=${encodeURIComponent(MEMORY_REVISION_ID)}`,
    );
    expect(observed.headers.authorization).toBe(`Bearer ${VALID_TOKEN}`);
    expect(observed.headers["x-action-capability"]).toBeUndefined();
    expect(observed.headers.cookie).toBeUndefined();
    expect(observed.headers["idempotency-key"]).toBeUndefined();
    expect(observed.body).toBe("");
    await handle.close();
  });

  it("encodeURIComponent's the revisionId query value (observed on a non-200 path)", async () => {
    const request = makeRequest({ memoryRevisionId: "00000000-0000-4000-8000-0000000000ab+cd" });
    const handle = await startServer((_req, res) => {
      res.writeHead(422, { "Content-Type": "application/problem+json" });
      res.end(JSON.stringify({ status: 422, requestId: "req-x", resultCategory: "DENIED" }));
    });
    await expectCode(retrieveMemoryEvidence(request, configFor(handle)), "HTTP_FAILURE");
    expect(handle.requests[0].url).toContain("revisionId=00000000-0000-4000-8000-0000000000ab%2Bcd");
    await handle.close();
  });
});

describe("retrieveMemoryEvidence transport failures", () => {
  it("rejects a redirect", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(302, { Location: "http://127.0.0.1:1/v1/memories/1/evidence" });
      res.end();
    });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "REDIRECT_NOT_ALLOWED");
    await handle.close();
  });

  it("rejects a wrong Content-Type", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end("<html>");
    });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "UNSUPPORTED_MEDIA_TYPE");
    await handle.close();
  });

  it("rejects a Content-Length exceeding the 5 MiB cap", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, {
        "Content-Type": "application/json",
        "Content-Length": String(5 * 1024 * 1024 + 1),
      });
      res.end("{}");
    });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "RESPONSE_TOO_LARGE");
    await handle.close();
  });

  it("enforces the real streaming cap when Content-Length is absent", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(Buffer.alloc(5 * 1024 * 1024 + 1, 0x20));
    });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "RESPONSE_TOO_LARGE");
    await handle.close();
  });

  it("rejects malformed JSON", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("not-json{{");
    });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "MALFORMED_JSON");
    await handle.close();
  });

  it("times out with a stable code (headers never arrive)", async () => {
    const handle = await startServer(() => {
      /* never respond */
    });
    await expectCode(
      retrieveMemoryEvidence(makeRequest(), configFor(handle, { timeoutMs: 50 })),
      "TIMEOUT",
    );
    await handle.close();
  });

  it("classifies a body-phase timeout as TIMEOUT, not NETWORK_FAILURE", async () => {
    const handle = await startServer((_req, res) => {
      res.on("error", () => {});
      res.writeHead(200, { "Content-Type": "application/json" });
      res.write("{");
    });
    await expectCode(
      retrieveMemoryEvidence(makeRequest(), configFor(handle, { timeoutMs: 50 })),
      "TIMEOUT",
    );
    await handle.close();
  });

  it("rejects a dropped connection with NETWORK_FAILURE", async () => {
    const handle = await startServer((req) => {
      req.socket.destroy();
    });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "NETWORK_FAILURE");
    await handle.close();
  });
});

describe("retrieveMemoryEvidence response re-validation", () => {
  async function serveJson(body: unknown): Promise<ServerHandle> {
    return startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    });
  }

  it("rejects an identity mismatch (memoryId/currentRevisionId/revisionNo)", async () => {
    const request = makeRequest();
    const wrong = { ...successBody(request), currentRevisionId: MEMORY_REVISION_ID + "1" };
    const handle = await serveJson(wrong);
    await expectCode(retrieveMemoryEvidence(request, configFor(handle)), "INVALID_RESPONSE");
    await handle.close();
  });

  it("rejects extra top-level fields", async () => {
    const handle = await serveJson({ ...successBody(makeRequest()), extra: "boom" });
    await expectCode(retrieveMemoryEvidence(makeRequest(), configFor(handle)), "INVALID_RESPONSE");
    await handle.close();
  });
});

describe("retrieveMemoryEvidence RFC 9457 projection across statuses", () => {
  it.each([401, 403, 404, 500])(
    "projects only safe fields for status %s and never leaks the canary",
    async (status) => {
      const handle = await startServer((_req, res) => {
        res.writeHead(status, { "Content-Type": "application/problem+json" });
        res.end(
          JSON.stringify({
            type: "about:blank",
            title: "Error",
            status,
            requestId: "req-123",
            resultCategory: "DENIED",
            failureCode: "SOME_FAILURE",
            retryable: false,
            secretCanary: PROBLEM_CANARY,
          }),
        );
      });
      let caught: unknown;
      try {
        await retrieveMemoryEvidence(makeRequest(), configFor(handle));
      } catch (error) {
        caught = error;
      }
      expect(caught).toBeInstanceOf(MemoryEvidenceClientError);
      const err = caught as MemoryEvidenceClientError;
      expect(err.code).toBe("HTTP_FAILURE");
      const text = JSON.stringify(err.detail);
      expect(text).not.toContain(PROBLEM_CANARY);
      expect(text).not.toContain("about:blank");
      expect(err.detail).toEqual({
        status,
        requestId: "req-123",
        resultCategory: "DENIED",
        failureCode: "SOME_FAILURE",
        retryable: false,
      });
      await handle.close();
    },
  );
});
