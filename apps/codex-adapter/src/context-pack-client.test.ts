import { describe, expect, it } from "vitest";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import {
  loadContextPackConfig,
  retrieveContextPack,
  ContextPackClientError,
  type ContextPackConfig,
} from "./context-pack-client.js";
import { buildContextPackRequest, type ContextPackRequest } from "./context-pack-canonicalizer.js";

const VALID_TOKEN = "synthetic-token-" + "0123456789abcdef".repeat(4);
const PROBLEM_CANARY = "PROBLEM_CANARY_LEAK";
const MEMORY_BODY = "记忆正文canary-CONTEXT";

const REQUEST_ID = "00000000-0000-4000-8000-000000000001";
const DELIVERY_ID = "00000000-0000-4000-8000-000000000002";
const MEMORY_ID = "00000000-0000-4000-8000-000000000003";
const MEMORY_REVISION_ID = "00000000-0000-4000-8000-000000000004";

function makeRequest(): ContextPackRequest {
  return buildContextPackRequest({
    retrievalKey: "retrieval-key-001",
    threadKey: "thread-key-001",
    turnKey: "turn-key-001",
    query: "小林最近确认了哪些合成记忆？",
  });
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

function configFor(handle: ServerHandle, overrides?: Partial<ContextPackConfig>): ContextPackConfig {
  return { baseUrl: handle.baseUrl, token: VALID_TOKEN, ...overrides };
}

async function expectCode(
  promise: Promise<unknown>,
  code: string,
): Promise<void> {
  let caught: unknown;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(ContextPackClientError);
  expect((caught as ContextPackClientError).code).toBe(code);
}

describe("loadContextPackConfig (rule 2)", () => {
  it("fails closed LOCAL_CONFIGURATION_MISSING when token is missing or low entropy", () => {
    expect(() => loadContextPackConfig({})).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
    expect(() => loadContextPackConfig({ HIDE_NEST_SYNTHETIC_TOKEN: "short" })).toThrowError(
      /LOCAL_CONFIGURATION_MISSING/,
    );
  });

  it("succeeds without a capability configured", () => {
    const config = loadContextPackConfig({ HIDE_NEST_SYNTHETIC_TOKEN: VALID_TOKEN });
    expect(config.token).toBe(VALID_TOKEN);
    expect(config.baseUrl).toBe("http://127.0.0.1:8080");
  });

  it("rejects a non-loopback base URL", () => {
    expect(() =>
      loadContextPackConfig({
        HIDE_NEST_SYNTHETIC_TOKEN: VALID_TOKEN,
        HIDE_NEST_API_BASE_URL: "http://example.com",
      }),
    ).toThrowError(/LOCAL_CONFIGURATION_MISSING/);
  });
});

describe("retrieveContextPack success + request shape", () => {
  it("POSTs the closed JSON, idempotency key and bearer with no capability/cookie", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res, body) => {
      const parsed = JSON.parse(body) as Record<string, unknown>;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: REQUEST_ID,
          resultCategory: "SUCCEEDED",
          deliveryId: DELIVERY_ID,
          threadId: parsed.threadId,
          turnId: parsed.turnId,
          purpose: parsed.purpose,
          policyRevisionSet: [`MEMORY:${MEMORY_ID}:1`],
          issuedAt: "2026-08-16T00:00:00.000Z",
          expiresAt: "2026-08-16T00:10:00.000Z",
          budgetLimited: false,
          memories: [
            {
              memoryId: MEMORY_ID,
              memoryRevisionId: MEMORY_REVISION_ID,
              revisionNo: 1,
              policyRevisionNo: 1,
              memoryType: "INTERPRETATION",
              bodyText: MEMORY_BODY,
              score: 0.48,
            },
          ],
        }),
      );
    });

    const result = await retrieveContextPack(request, configFor(handle));
    expect(result.resultCategory).toBe("SUCCEEDED");
    expect(result.memories[0].bodyText).toBe(MEMORY_BODY);

    const observed = handle.requests[0];
    expect(observed.method).toBe("POST");
    expect(observed.url).toBe("/v1/context-packs");
    expect(observed.headers.authorization).toBe(`Bearer ${VALID_TOKEN}`);
    expect(observed.headers["idempotency-key"]).toBe(request.retrievalKey);
    expect(observed.headers["content-type"]).toBe("application/json");
    expect(observed.headers["x-action-capability"]).toBeUndefined();
    expect(observed.headers.cookie).toBeUndefined();

    const sent = JSON.parse(observed.body) as Record<string, unknown>;
    expect(Object.keys(sent).sort()).toEqual(["purpose", "query", "threadId", "turnId"]);
    expect(sent.threadId).toBe(request.threadId);
    expect(sent.turnId).toBe(request.turnId);
    expect(sent.purpose).toBe("ANSWER_CURRENT_TURN");
    expect(sent.query).toBe(request.query);
    await handle.close();
  });

  it("does not send any capability even when configured in the environment", async () => {
    const request = makeRequest();
    const handle = await startServer((req, res, body) => {
      const parsed = JSON.parse(body) as Record<string, unknown>;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          requestId: REQUEST_ID,
          resultCategory: "NO_RELEVANT_RESULT",
          deliveryId: DELIVERY_ID,
          threadId: parsed.threadId,
          turnId: parsed.turnId,
          purpose: parsed.purpose,
          policyRevisionSet: [],
          issuedAt: "2026-08-16T00:00:00.000Z",
          expiresAt: "2026-08-16T00:10:00.000Z",
          budgetLimited: false,
          memories: [],
        }),
      );
    });
    const result = await retrieveContextPack(request, configFor(handle));
    expect(result.resultCategory).toBe("NO_RELEVANT_RESULT");
    expect(handle.requests[0].headers["x-action-capability"]).toBeUndefined();
    await handle.close();
  });
});

describe("retrieveContextPack transport failures", () => {
  it("rejects a redirect", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(302, { Location: "http://127.0.0.1:1/v1/context-packs" });
      res.end();
    });
    await expectCode(retrieveContextPack(makeRequest(), configFor(handle)), "REDIRECT_NOT_ALLOWED");
    await handle.close();
  });

  it("rejects a wrong Content-Type", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end("<html>");
    });
    await expectCode(retrieveContextPack(makeRequest(), configFor(handle)), "UNSUPPORTED_MEDIA_TYPE");
    await handle.close();
  });

  it("rejects a response whose Content-Length exceeds the cap", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json", "Content-Length": String(1024 * 1024 + 1) });
      res.end("{}");
    });
    await expectCode(retrieveContextPack(makeRequest(), configFor(handle)), "RESPONSE_TOO_LARGE");
    await handle.close();
  });

  it("enforces the real streaming cap when Content-Length is absent", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(Buffer.alloc(1024 * 1024 + 1, 0x20));
    });
    await expectCode(retrieveContextPack(makeRequest(), configFor(handle)), "RESPONSE_TOO_LARGE");
    await handle.close();
  });

  it("rejects malformed JSON", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end("not-json{{");
    });
    await expectCode(retrieveContextPack(makeRequest(), configFor(handle)), "MALFORMED_JSON");
    await handle.close();
  });

  it("times out with a stable code", async () => {
    const handle = await startServer(() => {
      /* never respond */
    });
    await expectCode(
      retrieveContextPack(makeRequest(), configFor(handle, { timeoutMs: 50 })),
      "TIMEOUT",
    );
    await handle.close();
  });

  it("classifies a body-phase timeout as TIMEOUT, not NETWORK_FAILURE (R1-01)", async () => {
    const handle = await startServer((_req, res) => {
      res.on("error", () => {});
      // Headers arrive, then a partial JSON body is written and the connection is held open so the
      // client times out while reading the body (not before headers).
      res.writeHead(200, { "Content-Type": "application/json" });
      res.write("{");
    });
    await expectCode(
      retrieveContextPack(makeRequest(), configFor(handle, { timeoutMs: 50 })),
      "TIMEOUT",
    );
    await handle.close();
  });

  it("rejects a dropped connection with NETWORK_FAILURE", async () => {
    const handle = await startServer((req) => {
      req.socket.destroy();
    });
    await expectCode(retrieveContextPack(makeRequest(), configFor(handle)), "NETWORK_FAILURE");
    await handle.close();
  });
});

describe("retrieveContextPack response re-validation", () => {
  async function serveJson(body: unknown): Promise<ServerHandle> {
    return startServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    });
  }

  it("rejects an identity mismatch", async () => {
    const request = makeRequest();
    const other = buildContextPackRequest({
      retrievalKey: "r",
      threadKey: "thread-key-001",
      turnKey: "turn-key-999",
      query: "q",
    });
    const handle = await serveJson({
      requestId: REQUEST_ID,
      resultCategory: "SUCCEEDED",
      deliveryId: DELIVERY_ID,
      threadId: request.threadId,
      turnId: other.turnId,
      purpose: request.purpose,
      policyRevisionSet: [`MEMORY:${MEMORY_ID}:1`],
      issuedAt: "2026-08-16T00:00:00.000Z",
      expiresAt: "2026-08-16T00:10:00.000Z",
      budgetLimited: false,
      memories: [
        {
          memoryId: MEMORY_ID,
          memoryRevisionId: MEMORY_REVISION_ID,
          revisionNo: 1,
          policyRevisionNo: 1,
          memoryType: "INTERPRETATION",
          bodyText: MEMORY_BODY,
          score: 0.48,
        },
      ],
    });
    await expectCode(retrieveContextPack(request, configFor(handle)), "INVALID_RESPONSE");
    await handle.close();
  });

  it("rejects extra top-level fields and wrong resultCategory", async () => {
    const request = makeRequest();
    const handle = await serveJson({
      requestId: REQUEST_ID,
      resultCategory: "DENIED",
      deliveryId: DELIVERY_ID,
      threadId: request.threadId,
      turnId: request.turnId,
      purpose: request.purpose,
      policyRevisionSet: [],
      issuedAt: "2026-08-16T00:00:00.000Z",
      expiresAt: "2026-08-16T00:10:00.000Z",
      budgetLimited: false,
      memories: [],
      extra: "boom",
    });
    await expectCode(retrieveContextPack(request, configFor(handle)), "INVALID_RESPONSE");
    await handle.close();
  });
});

describe("retrieveContextPack RFC 9457 projection", () => {
  it("projects only the five safe fields and never leaks the canary", async () => {
    const handle = await startServer((_req, res) => {
      res.writeHead(422, { "Content-Type": "application/problem+json" });
      res.end(
        JSON.stringify({
          type: "about:blank",
          title: "Unprocessable",
          status: 422,
          requestId: "req-123",
          resultCategory: "DENIED",
          failureCode: "REQUEST_SCHEMA_INVALID",
          retryable: false,
          secretCanary: PROBLEM_CANARY,
        }),
      );
    });
    let caught: unknown;
    try {
      await retrieveContextPack(makeRequest(), configFor(handle));
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(ContextPackClientError);
    const err = caught as ContextPackClientError;
    expect(err.code).toBe("HTTP_FAILURE");
    const text = JSON.stringify(err.detail);
    expect(text).not.toContain(PROBLEM_CANARY);
    expect(text).not.toContain("about:blank");
    expect(Object.keys(err.detail ?? {}).sort()).toEqual([
      "failureCode",
      "requestId",
      "resultCategory",
      "retryable",
      "status",
    ]);
    expect(err.detail).toEqual({
      status: 422,
      failureCode: "REQUEST_SCHEMA_INVALID",
      resultCategory: "DENIED",
      retryable: false,
      requestId: "req-123",
    });
    await handle.close();
  });
});
