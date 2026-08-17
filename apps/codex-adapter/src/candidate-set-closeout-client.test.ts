import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { beforeEach, describe, expect, it } from "vitest";
import {
  buildCandidateSetCloseoutRequest,
  deriveCandidateSetReviewSessionId,
  type CandidateSetCloseoutRequest,
} from "./candidate-set-closeout-canonicalizer.js";
import {
  CandidateSetClientError,
  loadCandidateSetConfig,
  parseCandidateSetResponse,
  resetCandidateSetReplayStateForTests,
  submitCandidateSet,
  type CandidateSetConfig,
  type CandidateSetSubmissionPhase,
} from "./candidate-set-closeout-client.js";
import { validateCandidateSetCloseoutInput } from "./candidate-set-closeout-input.js";
import { candidateSetTestRaw as canonicalVectorRaw } from "./candidate-set-closeout-test-fixture.js";

const TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGH-IJKL";
const CAPABILITY = "capability-canary-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const REQUEST_ID = "10000000-0000-0000-0000-000000000001";

function request(kind: "three-create" | "mixed" | "revise" = "three-create") {
  return buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(canonicalVectorRaw(kind)));
}

function responseFor(
  value: CandidateSetCloseoutRequest,
  phase: CandidateSetSubmissionPhase = "INDEX_READY",
) {
  const empty = value.candidates.length === 0;
  const allRejected = !empty && value.candidates.every((candidate) => candidate.disposition === "REJECTED");
  const response: Record<string, unknown> = {
    requestId: REQUEST_ID,
    resultCategory: allRejected ? "NO_RELEVANT_RESULT" : "SUCCEEDED",
    candidateSetId: value.candidateSetId,
    phase: empty ? "NO_CANDIDATES" : allRejected ? "DECISIONS_COMMITTED" : phase,
    candidates: value.candidates.map((candidate, index) => {
      if (candidate.disposition === "REJECTED") {
        return {
          candidateId: candidate.candidateId,
          ordinal: candidate.ordinal,
          disposition: candidate.disposition,
          action: candidate.action,
          phase: "REJECTED",
        };
      }
      if (phase === "DECISIONS_COMMITTED") {
        return {
          candidateId: candidate.candidateId,
          ordinal: candidate.ordinal,
          disposition: candidate.disposition,
          action: candidate.action,
          phase,
        };
      }
      return {
        candidateId: candidate.candidateId,
        ordinal: candidate.ordinal,
        disposition: candidate.disposition,
        action: candidate.action,
        phase,
        memoryId: `20000000-0000-0000-0000-${String(index + 1).padStart(12, "0")}`,
        memoryRevisionId: `30000000-0000-0000-0000-${String(index + 1).padStart(12, "0")}`,
        revisionNo: 1,
      };
    }),
  };
  if (!empty) response.reviewSessionId = deriveCandidateSetReviewSessionId(value.candidateSetId);
  return response;
}

function emptyRequest(): CandidateSetCloseoutRequest {
  const raw = canonicalVectorRaw("three-create");
  raw.candidates = [];
  return buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(raw));
}

function rejectedRequest(): CandidateSetCloseoutRequest {
  const raw = canonicalVectorRaw("mixed");
  raw.candidates = raw.candidates.map((candidate, index) => ({
    ...candidate,
    candidateKey: `rejected-${index}`,
    disposition: "REJECTED",
    evidenceSegmentIndexes: [],
  }));
  return buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(raw));
}

interface CapturedRequest {
  method: string;
  url: string;
  headers: IncomingMessage["headers"];
  body: string;
}

async function startServer(
  handler: (request: IncomingMessage, response: ServerResponse, body: string) => void,
) {
  const captured: CapturedRequest[] = [];
  const server = createServer((incoming, response) => {
    const chunks: Buffer[] = [];
    incoming.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
    incoming.on("end", () => {
      const body = Buffer.concat(chunks).toString("utf8");
      captured.push({
        method: incoming.method ?? "",
        url: incoming.url ?? "",
        headers: incoming.headers,
        body,
      });
      handler(incoming, response, body);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (address === null || typeof address === "string") throw new Error("server address");
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    captured,
    close: async () => {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    },
  };
}

function json(response: ServerResponse, status: number, value: unknown): void {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(value));
}

async function expectCode(promise: Promise<unknown>, code: string): Promise<CandidateSetClientError> {
  let caught: unknown;
  try {
    await promise;
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(CandidateSetClientError);
  expect((caught as CandidateSetClientError).code).toBe(code);
  return caught as CandidateSetClientError;
}

beforeEach(() => resetCandidateSetReplayStateForTests());

describe("CandidateSet strict response phase matrix", () => {
  it("passes three CREATE, shared-evidence mixed, all-rejected and empty responses", () => {
    for (const value of [request("three-create"), request("mixed")]) {
      const result = parseCandidateSetResponse(value, responseFor(value));
      expect(result.phase).toBe("INDEX_READY");
      expect(result.acceptedCount + result.rejectedCount).toBe(value.candidates.length);
    }
    const rejected = rejectedRequest();
    const rejectedResult = parseCandidateSetResponse(rejected, responseFor(rejected));
    expect(rejectedResult.phase).toBe("DECISIONS_COMMITTED");
    expect(rejectedResult.resultCategory).toBe("NO_RELEVANT_RESULT");
    const empty = emptyRequest();
    const emptyResult = parseCandidateSetResponse(empty, responseFor(empty));
    expect(emptyResult.phase).toBe("NO_CANDIDATES");
    expect(emptyResult).not.toHaveProperty("reviewSessionId");
  });

  it("accepts REVISE, SUPERSEDE and mixed CREATE+REVISE only at DECISIONS_COMMITTED", () => {
    const revise = request("revise");
    expect(parseCandidateSetResponse(revise, responseFor(revise, "DECISIONS_COMMITTED")).phase).toBe(
      "DECISIONS_COMMITTED",
    );
    const supersedeRaw = canonicalVectorRaw("revise");
    supersedeRaw.candidates[0].action = "SUPERSEDE";
    const supersede = buildCandidateSetCloseoutRequest(
      validateCandidateSetCloseoutInput(supersedeRaw),
    );
    expect(
      parseCandidateSetResponse(supersede, responseFor(supersede, "DECISIONS_COMMITTED")).phase,
    ).toBe("DECISIONS_COMMITTED");

    const mixedRaw = canonicalVectorRaw("three-create");
    mixedRaw.candidates[1] = {
      ...canonicalVectorRaw("revise").candidates[0],
      candidateKey: "mixed-revise",
    };
    const mixed = buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(mixedRaw));
    const mixedResult = parseCandidateSetResponse(mixed, responseFor(mixed, "DECISIONS_COMMITTED"));
    expect(mixedResult.phase).toBe("DECISIONS_COMMITTED");
    expect(mixedResult.candidates.every((item) => !("memoryId" in item))).toBe(true);
  });

  it("allows mixed per-item INDEX_READY/CANONICAL_COMMITTED under canonical top phase", () => {
    const value = request();
    const response = responseFor(value, "CANONICAL_COMMITTED");
    (response.candidates as Array<Record<string, unknown>>)[0].phase = "INDEX_READY";
    const result = parseCandidateSetResponse(value, response);
    expect(result.phase).toBe("CANONICAL_COMMITTED");
    expect(result.candidates.map((item) => item.phase)).toEqual([
      "INDEX_READY",
      "CANONICAL_COMMITTED",
      "CANONICAL_COMMITTED",
    ]);
  });

  it("rejects missing/extra/reordered/duplicate response candidates", () => {
    const value = request();
    const missing = responseFor(value);
    (missing.candidates as unknown[]).pop();
    expect(() => parseCandidateSetResponse(value, missing)).toThrow();

    const extra = responseFor(value);
    (extra.candidates as unknown[]).push((extra.candidates as unknown[])[0]);
    expect(() => parseCandidateSetResponse(value, extra)).toThrow();

    const reordered = responseFor(value);
    (reordered.candidates as unknown[]).reverse();
    expect(() => parseCandidateSetResponse(value, reordered)).toThrow();

    const duplicate = responseFor(value);
    (duplicate.candidates as Array<Record<string, unknown>>)[1] = {
      ...(duplicate.candidates as Array<Record<string, unknown>>)[0],
      ordinal: 2,
    };
    expect(() => parseCandidateSetResponse(value, duplicate)).toThrow();
  });

  it("rejects unknown keys, wrong identities and unknown enums", () => {
    const value = request();
    for (const mutate of [
      (response: Record<string, unknown>) => (response.unknown = true),
      (response: Record<string, unknown>) => (response.requestId = "not-a-uuid"),
      (response: Record<string, unknown>) => (response.candidateSetId = REQUEST_ID),
      (response: Record<string, unknown>) => (response.reviewSessionId = REQUEST_ID),
      (response: Record<string, unknown>) => (response.phase = "UNKNOWN"),
      (response: Record<string, unknown>) =>
        ((response.candidates as Array<Record<string, unknown>>)[0].phase = "UNKNOWN"),
    ]) {
      const response = responseFor(value);
      mutate(response);
      expect(() => parseCandidateSetResponse(value, response)).toThrow();
    }
  });

  it("rejects fake, missing, nullable or decision-phase resource fields", () => {
    const value = request();
    const missing = responseFor(value);
    delete (missing.candidates as Array<Record<string, unknown>>)[0].memoryId;
    expect(() => parseCandidateSetResponse(value, missing)).toThrow();

    const fake = responseFor(value);
    (fake.candidates as Array<Record<string, unknown>>)[0].revisionNo = 2;
    expect(() => parseCandidateSetResponse(value, fake)).toThrow();

    const nullable = responseFor(value);
    (nullable.candidates as Array<Record<string, unknown>>)[0].memoryId = null;
    expect(() => parseCandidateSetResponse(value, nullable)).toThrow();

    const revise = request("revise");
    const decision = responseFor(revise, "DECISIONS_COMMITTED");
    (decision.candidates as Array<Record<string, unknown>>)[0].memoryId = REQUEST_ID;
    expect(() => parseCandidateSetResponse(revise, decision)).toThrow();
  });

  it("returns only the frozen safe success projection", () => {
    const value = request("mixed");
    const result = parseCandidateSetResponse(value, responseFor(value));
    expect(Object.keys(result).sort()).toEqual([
      "acceptedCount",
      "candidateSetId",
      "candidates",
      "phase",
      "rejectedCount",
      "resultCategory",
      "reviewSessionId",
      "status",
    ]);
    const text = JSON.stringify(result);
    expect(text).not.toContain(value.requestHash);
    expect(text).not.toContain("小林喜欢粉色");
    expect(text).not.toContain("vector-thread");
    expect(text).not.toContain("pink");
  });
});

describe("CandidateSet loopback HTTP client", () => {
  it("reads no capability and sends one exact POST without capability/cookie", async () => {
    const value = request();
    const server = await startServer((_incoming, response) => json(response, 202, responseFor(value)));
    const config = loadCandidateSetConfig({
      HIDE_NEST_API_BASE_URL: server.baseUrl,
      HIDE_NEST_SYNTHETIC_TOKEN: TOKEN,
      HIDE_NEST_SYNTHETIC_CAPABILITY: CAPABILITY,
    });
    const result = await submitCandidateSet(value, config);
    expect(result.phase).toBe("INDEX_READY");
    expect(server.captured).toHaveLength(1);
    const sent = server.captured[0];
    expect(sent.method).toBe("POST");
    expect(sent.url).toBe(
      `/v1/review-sessions/${deriveCandidateSetReviewSessionId(value.candidateSetId)}/final-submissions`,
    );
    expect(sent.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(sent.headers["idempotency-key"]).toBe(value.candidateSetId);
    expect(sent.headers["x-action-capability"]).toBeUndefined();
    expect(sent.headers.cookie).toBeUndefined();
    expect(JSON.parse(sent.body)).toEqual(value);
    await server.close();
  });

  it("does not access the capability environment property", () => {
    const env = {
      HIDE_NEST_SYNTHETIC_TOKEN: TOKEN,
      get HIDE_NEST_SYNTHETIC_CAPABILITY(): string {
        throw new Error("capability must not be read");
      },
    };
    expect(loadCandidateSetConfig(env).token).toBe(TOKEN);
  });

  it("rejects missing/weak token and non-loopback or decorated base URLs", () => {
    expect(() => loadCandidateSetConfig({})).toThrow(CandidateSetClientError);
    expect(() => loadCandidateSetConfig({ HIDE_NEST_SYNTHETIC_TOKEN: "weak" })).toThrow(
      CandidateSetClientError,
    );
    for (const baseUrl of [
      "https://127.0.0.1:8080",
      "http://example.com",
      "http://user@127.0.0.1:8080",
      "http://127.0.0.1:8080/path",
      "http://127.0.0.1:8080?query=1",
      "http://127.0.0.1:8080#fragment",
    ]) {
      expect(() =>
        loadCandidateSetConfig({
          HIDE_NEST_SYNTHETIC_TOKEN: TOKEN,
          HIDE_NEST_API_BASE_URL: baseUrl,
        }),
      ).toThrow(CandidateSetClientError);
    }
  });

  it("manually rejects redirect without forwarding bearer", async () => {
    const value = request();
    const server = await startServer((_incoming, response) => {
      response.writeHead(302, { Location: "http://127.0.0.1:1/steal" });
      response.end();
    });
    await expectCode(
      submitCandidateSet(value, { baseUrl: server.baseUrl, token: TOKEN }),
      "INVALID_RESPONSE",
    );
    expect(server.captured).toHaveLength(1);
    await server.close();
  });

  it("maps connection failure to NETWORK_FAILURE", async () => {
    await expectCode(
      submitCandidateSet(request(), { baseUrl: "http://127.0.0.1:1", token: TOKEN, timeoutMs: 100 }),
      "NETWORK_FAILURE",
    );
  });

  it("maps header and response-body timeouts to TIMEOUT", async () => {
    const value = request();
    const headerServer = await startServer(() => undefined);
    await expectCode(
      submitCandidateSet(value, { baseUrl: headerServer.baseUrl, token: TOKEN, timeoutMs: 30 }),
      "TIMEOUT",
    );
    await headerServer.close();

    const bodyServer = await startServer((_incoming, response) => {
      response.writeHead(202, { "Content-Type": "application/json" });
      response.write("{");
    });
    await expectCode(
      submitCandidateSet(value, { baseUrl: bodyServer.baseUrl, token: TOKEN, timeoutMs: 30 }),
      "TIMEOUT",
    );
    await bodyServer.close();
  });

  it("rejects oversized, non-JSON and malformed JSON responses", async () => {
    const value = request();
    const handlers = [
      (_incoming: IncomingMessage, response: ServerResponse) => {
        response.writeHead(202, {
          "Content-Type": "application/json",
          "Content-Length": String(1024 * 1024 + 1),
        });
        response.end("{}");
      },
      (_incoming: IncomingMessage, response: ServerResponse) => {
        response.writeHead(202, { "Content-Type": "text/plain" });
        response.end("{}");
      },
      (_incoming: IncomingMessage, response: ServerResponse) => {
        response.writeHead(202, { "Content-Type": "application/json" });
        response.end("{");
      },
    ];
    for (const handler of handlers) {
      const server = await startServer(handler);
      await expectCode(
        submitCandidateSet(value, { baseUrl: server.baseUrl, token: TOKEN }),
        "INVALID_RESPONSE",
      );
      await server.close();
    }
  });

  it("projects only five safe RFC 9457 fields", async () => {
    const value = request();
    const server = await startServer((_incoming, response) => {
      response.writeHead(409, { "Content-Type": "application/problem+json" });
      response.end(
        JSON.stringify({
          status: 999,
          failureCode: "IDEMPOTENCY_KEY_REUSED",
          resultCategory: "DENIED",
          retryable: false,
          requestId: REQUEST_ID,
          detail: "secret body/hash/path/database canary",
        }),
      );
    });
    const error = await expectCode(
      submitCandidateSet(value, { baseUrl: server.baseUrl, token: TOKEN }),
      "HTTP_FAILURE",
    );
    expect(error.detail).toEqual({
      status: 409,
      failureCode: "IDEMPOTENCY_KEY_REUSED",
      resultCategory: "DENIED",
      retryable: false,
      requestId: REQUEST_ID,
    });
    expect(JSON.stringify(error.detail)).not.toContain("canary");
    await server.close();
  });

  it("allows canonical -> index replay convergence and rejects regression", async () => {
    const value = request();
    let phase: CandidateSetSubmissionPhase = "CANONICAL_COMMITTED";
    const server = await startServer((_incoming, response) => json(response, 202, responseFor(value, phase)));
    const config: CandidateSetConfig = { baseUrl: server.baseUrl, token: TOKEN };
    expect((await submitCandidateSet(value, config)).phase).toBe("CANONICAL_COMMITTED");
    phase = "INDEX_READY";
    expect((await submitCandidateSet(value, config)).phase).toBe("INDEX_READY");
    phase = "CANONICAL_COMMITTED";
    await expectCode(submitCandidateSet(value, config), "INVALID_RESPONSE");
    await server.close();
  });
});
