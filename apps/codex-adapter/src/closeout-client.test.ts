import { describe, expect, it } from "vitest";
import { createServer, type Server, type IncomingMessage, type ServerResponse } from "node:http";
import {
  loadCloseoutConfig,
  validateLoopbackBaseUrl,
  submitCloseout,
  CloseoutClientError,
  DEFAULT_BASE_URL,
  type CloseoutConfig,
} from "./closeout-client.js";
import { buildCloseoutRequest, deriveMemoryId, type CloseoutRequest } from "./closeout-canonicalizer.js";

const VALID_TOKEN = "synthetic-token-" + "0123456789abcdef".repeat(4);
const VALID_CAPABILITY = "synthetic-capability-" + "0123456789abcdef".repeat(4);
const BODY_CANARY = "正文canary-ABC123";
const EVIDENCE_CANARY = "证据canary-XYZ789";

function makeRequest(): CloseoutRequest {
  return buildCloseoutRequest({
    closeoutKey: "closeout-key-001",
    threadKey: "thread-key-001",
    userConfirmed: true,
    candidate: {
      perspectiveSpeakerKey: "xiaolin",
      memoryType: "EVENT",
      bodyText: `${BODY_CANARY}确认了合成记忆候选`,
    },
    evidenceSegments: [
      {
        messages: [
          {
            speakerKey: "xiaolin",
            ordinal: 0,
            occurredAt: "2026-08-13T12:00:00+08:00",
            bodyText: `${EVIDENCE_CANARY}一`,
          },
        ],
      },
    ],
  });
}

interface Observed {
  method: string;
  url: string;
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
      requests.push({ method: req.method ?? "", url: req.url ?? "", body });
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

function successHandler(req: IncomingMessage, res: ServerResponse, body: string): void {
  if (req.method === "POST" && req.url === "/v1/closeout-submissions") {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    const submissionId = String(parsed.submissionId ?? "");
    res.writeHead(202, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        requestId: "req-" + submissionId,
        resultCategory: "SUCCEEDED",
        runId: submissionId,
        statusUrl: "/v1/runs/" + submissionId,
        phase: "CANONICAL_COMMITTED",
      }),
    );
    return;
  }
  if (req.method === "GET" && (req.url ?? "").startsWith("/v1/runs/")) {
    const runId = (req.url ?? "").split("/").pop() ?? "";
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        requestId: "req-" + runId,
        resultCategory: "SUCCEEDED",
        runId,
        phase: "CANONICAL_COMMITTED",
        retryable: false,
      }),
    );
    return;
  }
  res.writeHead(404);
  res.end("{}");
}

async function expectCode(promise: Promise<unknown>, code: string): Promise<void> {
  let caught: unknown;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(CloseoutClientError);
  expect((caught as CloseoutClientError).code).toBe(code);
}

function configFor(handle: ServerHandle): CloseoutConfig {
  return { baseUrl: handle.baseUrl, token: VALID_TOKEN, capability: VALID_CAPABILITY };
}

describe("validateLoopbackBaseUrl (rule 1)", () => {
  it("accepts loopback hosts with a root path", () => {
    expect(validateLoopbackBaseUrl("http://127.0.0.1:8080")).toBe("http://127.0.0.1:8080");
    expect(validateLoopbackBaseUrl("http://localhost:8080")).toBe("http://localhost:8080");
    expect(validateLoopbackBaseUrl("http://[::1]:8080")).toBe("http://[::1]:8080");
  });

  it("rejects non-loopback, https, userinfo, query, fragment and non-root path", () => {
    expect(() => validateLoopbackBaseUrl("http://192.168.1.1:8080")).toThrow(CloseoutClientError);
    expect(() => validateLoopbackBaseUrl("https://127.0.0.1:8080")).toThrow(CloseoutClientError);
    expect(() => validateLoopbackBaseUrl("http://user@127.0.0.1:8080")).toThrow(CloseoutClientError);
    expect(() => validateLoopbackBaseUrl("http://127.0.0.1:8080/foo")).toThrow(CloseoutClientError);
    expect(() => validateLoopbackBaseUrl("http://127.0.0.1:8080?x=1")).toThrow(CloseoutClientError);
    expect(() => validateLoopbackBaseUrl("http://127.0.0.1:8080#frag")).toThrow(CloseoutClientError);
  });
});

describe("loadCloseoutConfig (rule 2)", () => {
  it("fails closed LOCAL_CONFIGURATION_MISSING when token/capability are missing", () => {
    expect(() => loadCloseoutConfig({})).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
  });

  it("fails closed when token is too low entropy", () => {
    expect(() =>
      loadCloseoutConfig({
        HIDE_NEST_SYNTHETIC_TOKEN: "short",
        HIDE_NEST_SYNTHETIC_CAPABILITY: VALID_CAPABILITY,
      }),
    ).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
  });

  it("defaults to loopback base URL and returns config when configured", () => {
    const config = loadCloseoutConfig({
      HIDE_NEST_SYNTHETIC_TOKEN: VALID_TOKEN,
      HIDE_NEST_SYNTHETIC_CAPABILITY: VALID_CAPABILITY,
    });
    expect(config.baseUrl).toBe(DEFAULT_BASE_URL);
  });

  it("fails closed on a non-loopback configured base URL", () => {
    expect(() =>
      loadCloseoutConfig({
        HIDE_NEST_SYNTHETIC_TOKEN: VALID_TOKEN,
        HIDE_NEST_SYNTHETIC_CAPABILITY: VALID_CAPABILITY,
        HIDE_NEST_API_BASE_URL: "http://example.com",
      }),
    ).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
  });
});

describe("submitCloseout POST receipt binding (R1-02)", () => {
  it("succeeds and does not send GET when POST receipt matches request facts", async () => {
    const handle = await startServer(successHandler);
    const request = makeRequest();
    const result = await submitCloseout(request, configFor(handle));
    expect(result.status).toBe("SAVED");
    expect(result.runId).toBe(request.submissionId);
    expect(result.memoryId).toBe(deriveMemoryId(request.submissionId));
    expect(result.statusUrl).toBe(`${handle.baseUrl}/v1/runs/${request.submissionId}`);
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(1);
    await handle.close();
  });

  it("rejects and skips GET when POST runId != submissionId", async () => {
    const handle = await startServer((req, res, body) => {
      if (req.method === "POST") {
        const parsed = JSON.parse(body) as Record<string, unknown>;
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            requestId: "x",
            resultCategory: "SUCCEEDED",
            runId: "00000000-0000-4000-8000-000000000000",
            statusUrl: "/v1/runs/" + String(parsed.submissionId),
            phase: "CANONICAL_COMMITTED",
          }),
        );
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("{}");
    });
    await expectCode(submitCloseout(makeRequest(), configFor(handle)), "POST_RECEIPT_MISMATCH");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });

  it("rejects and skips GET when POST phase != CANONICAL_COMMITTED", async () => {
    const handle = await startServer((req, res, body) => {
      if (req.method === "POST") {
        const parsed = JSON.parse(body) as Record<string, unknown>;
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            requestId: "x",
            resultCategory: "SUCCEEDED",
            runId: String(parsed.submissionId),
            statusUrl: "/v1/runs/" + String(parsed.submissionId),
            phase: "RECEIVED",
          }),
        );
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("{}");
    });
    await expectCode(submitCloseout(makeRequest(), configFor(handle)), "POST_RECEIPT_MISMATCH");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });

  it("rejects and skips GET when POST resultCategory != SUCCEEDED", async () => {
    const handle = await startServer((req, res, body) => {
      if (req.method === "POST") {
        const parsed = JSON.parse(body) as Record<string, unknown>;
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            requestId: "x",
            resultCategory: "FAILED",
            runId: String(parsed.submissionId),
            statusUrl: "/v1/runs/" + String(parsed.submissionId),
            phase: "CANONICAL_COMMITTED",
          }),
        );
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("{}");
    });
    await expectCode(submitCloseout(makeRequest(), configFor(handle)), "POST_RECEIPT_MISMATCH");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });
});

describe("submitCloseout GET status binding (R1-02)", () => {
  it("rejects when GET runId != submissionId", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res) => {
      if (req.method === "POST") {
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            requestId: "x",
            resultCategory: "SUCCEEDED",
            runId: request.submissionId,
            statusUrl: "/v1/runs/" + request.submissionId,
            phase: "CANONICAL_COMMITTED",
          }),
        );
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: "00000000-0000-4000-8000-000000000000",
          phase: "CANONICAL_COMMITTED",
        }),
      );
    });
    await expectCode(submitCloseout(request, configFor(handle)), "GET_STATUS_MISMATCH");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(1);
    await handle.close();
  });

  it("rejects when GET phase != CANONICAL_COMMITTED", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res) => {
      if (req.method === "POST") {
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            requestId: "x",
            resultCategory: "SUCCEEDED",
            runId: request.submissionId,
            statusUrl: "/v1/runs/" + request.submissionId,
            phase: "CANONICAL_COMMITTED",
          }),
        );
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: request.submissionId,
          phase: "RECEIVED",
        }),
      );
    });
    await expectCode(submitCloseout(request, configFor(handle)), "GET_STATUS_MISMATCH");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(1);
    await handle.close();
  });

  it("rejects when GET resultCategory != SUCCEEDED", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res) => {
      if (req.method === "POST") {
        res.writeHead(202, { "Content-Type": "application/json" });
        res.end(
          JSON.stringify({
            requestId: "x",
            resultCategory: "SUCCEEDED",
            runId: request.submissionId,
            statusUrl: "/v1/runs/" + request.submissionId,
            phase: "CANONICAL_COMMITTED",
          }),
        );
        return;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "FAILED",
          runId: request.submissionId,
          phase: "CANONICAL_COMMITTED",
        }),
      );
    });
    await expectCode(submitCloseout(request, configFor(handle)), "GET_STATUS_MISMATCH");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(1);
    await handle.close();
  });
});

describe("submitCloseout statusUrl same-origin gate (R1-03)", () => {
  it("rejects a cross-port statusUrl before GET", async () => {
    const handle = await startServer((req, res, body) => {
      const parsed = JSON.parse(body) as Record<string, unknown>;
      res.writeHead(202, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: String(parsed.submissionId),
          statusUrl: "http://127.0.0.1:1/v1/runs/" + String(parsed.submissionId),
          phase: "CANONICAL_COMMITTED",
        }),
      );
    });
    await expectCode(submitCloseout(makeRequest(), configFor(handle)), "STATUS_URL_INVALID");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });

  it("rejects a cross-host statusUrl before GET", async () => {
    const handle = await startServer((req, res, body) => {
      const parsed = JSON.parse(body) as Record<string, unknown>;
      res.writeHead(202, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: String(parsed.submissionId),
          statusUrl: handle.baseUrl.replace("127.0.0.1", "localhost") + "/v1/runs/" + String(parsed.submissionId),
          phase: "CANONICAL_COMMITTED",
        }),
      );
    });
    await expectCode(submitCloseout(makeRequest(), configFor(handle)), "STATUS_URL_INVALID");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });

  it("rejects a statusUrl with query or fragment before GET", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res) => {
      res.writeHead(202, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: request.submissionId,
          statusUrl: "/v1/runs/" + request.submissionId + "?x=1",
          phase: "CANONICAL_COMMITTED",
        }),
      );
    });
    await expectCode(submitCloseout(request, configFor(handle)), "STATUS_URL_INVALID");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });

  it("rejects a statusUrl with a wrong path before GET", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res) => {
      res.writeHead(202, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: request.submissionId,
          statusUrl: "/v1/runs/00000000-0000-4000-8000-000000000000",
          phase: "CANONICAL_COMMITTED",
        }),
      );
    });
    await expectCode(submitCloseout(request, configFor(handle)), "STATUS_URL_INVALID");
    expect(handle.requests.filter((r) => r.method === "GET")).toHaveLength(0);
    await handle.close();
  });

  it("accepts a normal relative same-origin statusUrl", async () => {
    const handle = await startServer(successHandler);
    const request = makeRequest();
    const result = await submitCloseout(request, configFor(handle));
    expect(result.runId).toBe(request.submissionId);
    expect(result.statusUrl).toBe(`${handle.baseUrl}/v1/runs/${request.submissionId}`);
    await handle.close();
  });
});

describe("submitCloseout leak safety", () => {
  it("never leaks token/capability/body canary in success or failure", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res, body) => {
      const parsed = JSON.parse(body) as Record<string, unknown>;
      res.writeHead(202, { "Content-Type": "application/json" });
      // Wrong phase forces a fail-closed error while the request still carries the canaries.
      res.end(
        JSON.stringify({
          requestId: "x",
          resultCategory: "SUCCEEDED",
          runId: String(parsed.submissionId),
          statusUrl: "/v1/runs/" + String(parsed.submissionId),
          phase: "RECEIVED",
        }),
      );
    });
    const successHandle = await startServer(successHandler);
    const success = await submitCloseout(makeRequest(), configFor(successHandle));
    const successText = JSON.stringify(success);
    expect(successText).not.toContain(BODY_CANARY);
    expect(successText).not.toContain(EVIDENCE_CANARY);
    expect(successText).not.toContain(VALID_TOKEN);
    expect(successText).not.toContain(VALID_CAPABILITY);
    await successHandle.close();

    let errorText = "";
    try {
      await submitCloseout(request, configFor(handle));
    } catch (error) {
      errorText = String(error instanceof Error ? error.message : error);
    }
    expect(errorText).not.toContain(BODY_CANARY);
    expect(errorText).not.toContain(EVIDENCE_CANARY);
    expect(errorText).not.toContain(VALID_TOKEN);
    expect(errorText).not.toContain(VALID_CAPABILITY);
    await handle.close();
  });
});
