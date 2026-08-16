import { describe, expect, it } from "vitest";
import { deriveThreadId } from "./closeout-canonicalizer.js";
import {
  CONTEXT_PACK_PURPOSE,
  buildContextPackRequest,
  deriveTurnId,
  parseContextPackResponse,
  type ContextPackMemory,
  type ContextPackRequest,
} from "./context-pack-canonicalizer.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const REQUEST_ID = "00000000-0000-4000-8000-000000000001";
const DELIVERY_ID = "00000000-0000-4000-8000-000000000002";
const MEMORY_ID = "00000000-0000-4000-8000-000000000003";
const MEMORY_REVISION_ID = "00000000-0000-4000-8000-000000000004";
const MEMORY_ID_2 = "00000000-0000-4000-8000-000000000005";
const MEMORY_REVISION_ID_2 = "00000000-0000-4000-8000-000000000006";

function makeRequest(overrides?: Partial<Record<"retrievalKey" | "threadKey" | "turnKey" | "query", string>>): ContextPackRequest {
  return buildContextPackRequest({
    retrievalKey: overrides?.retrievalKey ?? "retrieval-key-001",
    threadKey: overrides?.threadKey ?? "thread-key-001",
    turnKey: overrides?.turnKey ?? "turn-key-001",
    query: overrides?.query ?? "小林最近确认了哪些合成记忆？",
  });
}

function memory(overrides?: Partial<ContextPackMemory>): ContextPackMemory {
  return {
    memoryId: MEMORY_ID,
    memoryRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
    policyRevisionNo: 1,
    memoryType: "INTERPRETATION",
    bodyText: "记忆正文",
    score: 0.48,
    ...overrides,
  };
}

function policySet(memories: ContextPackMemory[]): string[] {
  return memories.map((m) => `MEMORY:${m.memoryId}:${m.policyRevisionNo}`).sort();
}

function validResponse(memories: ContextPackMemory[], request: ContextPackRequest): Record<string, unknown> {
  return {
    requestId: REQUEST_ID,
    resultCategory: memories.length === 0 ? "NO_RELEVANT_RESULT" : "SUCCEEDED",
    deliveryId: DELIVERY_ID,
    threadId: request.threadId,
    turnId: request.turnId,
    purpose: request.purpose,
    policyRevisionSet: policySet(memories),
    issuedAt: "2026-08-16T00:00:00.000Z",
    expiresAt: "2026-08-16T00:10:00.000Z",
    budgetLimited: false,
    memories,
  };
}

describe("deterministic identity", () => {
  it("reuses the closeout deriveThreadId for the same threadKey", () => {
    const request = makeRequest({ threadKey: "thread-key-001" });
    expect(request.threadId).toBe(deriveThreadId("thread-key-001"));
    expect(request.threadId).toMatch(UUID_RE);
  });

  it("derives a stable turnId for the same thread + turn, and a new one for a new turnKey", () => {
    const a = makeRequest({ turnKey: "turn-key-001" });
    const b = makeRequest({ turnKey: "turn-key-001" });
    const c = makeRequest({ turnKey: "turn-key-002" });
    expect(a.turnId).toBe(b.turnId);
    expect(a.turnId).not.toBe(c.turnId);
    expect(a.turnId).toMatch(UUID_RE);
  });

  it("proves length-prefixed encoding prevents ab+c vs a+bc collisions", () => {
    expect(deriveTurnId("a", "bc")).not.toBe(deriveTurnId("ab", "c"));
    expect(deriveTurnId("thread", "key")).not.toBe(deriveTurnId("threa", "dkey"));
  });

  it("writes a fixed purpose and preserves the query verbatim", () => {
    const request = makeRequest({ query: "  记忆  " });
    expect(request.purpose).toBe(CONTEXT_PACK_PURPOSE);
    expect(request.purpose).toBe("ANSWER_CURRENT_TURN");
    expect(request.query).toBe("  记忆  ");
  });
});

describe("parseContextPackResponse success projection", () => {
  it("accepts a valid SUCCEEDED response and drops identity/policy fields", () => {
    const request = makeRequest();
    const result = parseContextPackResponse(request, validResponse([memory()], request));
    expect(result.resultCategory).toBe("SUCCEEDED");
    expect(result.requestId).toBe(REQUEST_ID);
    expect(result.deliveryId).toBe(DELIVERY_ID);
    expect(result.budgetLimited).toBe(false);
    expect(result.memories).toHaveLength(1);
    expect(result.memories[0].memoryId).toBe(MEMORY_ID);
    expect(Object.keys(result)).toEqual([
      "requestId",
      "resultCategory",
      "deliveryId",
      "issuedAt",
      "expiresAt",
      "budgetLimited",
      "memories",
    ]);
  });

  it("accepts a valid NO_RELEVANT_RESULT response with empty memories", () => {
    const request = makeRequest();
    const result = parseContextPackResponse(request, validResponse([], request));
    expect(result.resultCategory).toBe("NO_RELEVANT_RESULT");
    expect(result.memories).toEqual([]);
  });

  it("accepts a two-memory response with non-increasing scores", () => {
    const request = makeRequest();
    const memories = [
      memory({ memoryId: MEMORY_ID, memoryRevisionId: MEMORY_REVISION_ID, policyRevisionNo: 2, score: 0.9 }),
      memory({ memoryId: MEMORY_ID_2, memoryRevisionId: MEMORY_REVISION_ID_2, policyRevisionNo: 1, score: 0.4 }),
    ];
    const result = parseContextPackResponse(request, validResponse(memories, request));
    expect(result.memories.map((m) => m.score)).toEqual([0.9, 0.4]);
  });
});

describe("parseContextPackResponse rejections", () => {
  it("rejects extra or missing top-level fields", () => {
    const request = makeRequest();
    const base = validResponse([memory()], request);
    expect(() => parseContextPackResponse(request, { ...base, extra: 1 })).toThrow();
    const missing = { ...base };
    delete missing["budgetLimited"];
    expect(() => parseContextPackResponse(request, missing)).toThrow();
  });

  it("rejects request identity mismatch (threadId/turnId/purpose)", () => {
    const request = makeRequest();
    const otherThread = makeRequest({ threadKey: "thread-key-999" });
    const otherTurn = makeRequest({ turnKey: "turn-key-999" });
    expect(() =>
      parseContextPackResponse(request, {
        ...validResponse([memory()], request),
        threadId: otherThread.threadId,
      }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), turnId: otherTurn.turnId }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), purpose: "OTHER" }),
    ).toThrow();
  });

  it("rejects a wrong resultCategory", () => {
    const request = makeRequest();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), resultCategory: "DENIED" }),
    ).toThrow();
  });

  it("rejects non-UUID requestId/deliveryId/memory ids", () => {
    const request = makeRequest();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), requestId: "not-a-uuid" }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), deliveryId: "x" }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ memoryId: "nope" })], request)),
    ).toThrow();
  });

  it("rejects invalid or out-of-order timestamps", () => {
    const request = makeRequest();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), issuedAt: "yesterday" }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), issuedAt: "2026-08-16T00:00:00" }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, {
        ...validResponse([memory()], request),
        issuedAt: "2026-08-16T00:10:00.000Z",
        expiresAt: "2026-08-16T00:00:00.000Z",
      }),
    ).toThrow();
  });

  it("rejects impossible calendar dates instead of normalizing them (R1-02)", () => {
    const request = makeRequest();
    const withIssuedAt = (issuedAt: string) => ({
      ...validResponse([memory()], request),
      issuedAt,
    });
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-02-30T00:00:00Z"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2025-02-29T00:00:00Z"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-04-31T00:00:00Z"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-08-16T24:00:00Z"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-08-16T00:60:00Z"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-08-16T00:00:60Z"))).toThrow();
  });

  it("rejects illegal timezone offsets (R1-02)", () => {
    const request = makeRequest();
    const withIssuedAt = (issuedAt: string) => ({
      ...validResponse([memory()], request),
      issuedAt,
    });
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-08-16T00:00:00+24:00"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-08-16T00:00:00+00:60"))).toThrow();
    expect(() => parseContextPackResponse(request, withIssuedAt("2026-08-16T00:00:00-23:60"))).toThrow();
  });

  it("accepts a legal leap-day timestamp with a 9-digit fraction (R1-02)", () => {
    const request = makeRequest();
    const result = parseContextPackResponse(
      request,
      {
        ...validResponse([memory()], request),
        issuedAt: "2024-02-29T23:59:59.123456789+08:00",
        expiresAt: "2024-03-01T00:00:00.000Z",
      },
    );
    expect(result.issuedAt).toBe("2024-02-29T23:59:59.123456789+08:00");
    expect(result.expiresAt).toBe("2024-03-01T00:00:00.000Z");
  });

  it("rejects non-boolean budgetLimited", () => {
    const request = makeRequest();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), budgetLimited: "false" }),
    ).toThrow();
  });

  it("rejects SUCCEEDED with zero memories and NO_RELEVANT_RESULT with memories", () => {
    const request = makeRequest();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([memory()], request), resultCategory: "NO_RELEVANT_RESULT" }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, { ...validResponse([], request), resultCategory: "SUCCEEDED" }),
    ).toThrow();
  });

  it("rejects more than five memories", () => {
    const request = makeRequest();
    const memories = Array.from({ length: 6 }, (_, i) =>
      memory({
        memoryId: `00000000-0000-4000-8000-0000000000${String(i + 1)}`,
        memoryRevisionId: `00000000-0000-4000-8000-0000000001${String(i + 1)}`,
        score: 1 - i * 0.1,
      }),
    );
    expect(() => parseContextPackResponse(request, validResponse(memories, request))).toThrow();
  });

  it("rejects duplicate memory ids and revision ids", () => {
    const request = makeRequest();
    const dupId = memory({ memoryId: MEMORY_ID, memoryRevisionId: MEMORY_REVISION_ID, score: 0.9 });
    const dupId2 = memory({ memoryId: MEMORY_ID, memoryRevisionId: MEMORY_REVISION_ID_2, score: 0.4 });
    expect(() => parseContextPackResponse(request, validResponse([dupId, dupId2], request))).toThrow();

    const dupRev = memory({ memoryId: MEMORY_ID, memoryRevisionId: MEMORY_REVISION_ID, score: 0.9 });
    const dupRev2 = memory({ memoryId: MEMORY_ID_2, memoryRevisionId: MEMORY_REVISION_ID, score: 0.4 });
    expect(() => parseContextPackResponse(request, validResponse([dupRev, dupRev2], request))).toThrow();
  });

  it("rejects non-decreasing scores", () => {
    const request = makeRequest();
    const memories = [
      memory({ memoryId: MEMORY_ID, memoryRevisionId: MEMORY_REVISION_ID, score: 0.4 }),
      memory({ memoryId: MEMORY_ID_2, memoryRevisionId: MEMORY_REVISION_ID_2, score: 0.9 }),
    ];
    expect(() => parseContextPackResponse(request, validResponse(memories, request))).toThrow();
  });

  it("rejects missing, extra or duplicate policy revision set entries", () => {
    const request = makeRequest();
    const memories = [memory()];
    const base = validResponse(memories, request);

    expect(() =>
      parseContextPackResponse(request, { ...base, policyRevisionSet: [] }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, {
        ...base,
        policyRevisionSet: [`MEMORY:${MEMORY_ID}:1`, `MEMORY:${MEMORY_ID}:1`],
      }),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, {
        ...base,
        policyRevisionSet: [`MEMORY:${MEMORY_ID}:1`, "MEMORY:extra:2"],
      }),
    ).toThrow();
  });

  it("rejects a memory with an illegal field or invalid type", () => {
    const request = makeRequest();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ memoryType: "EVENT" })], request)),
    ).not.toThrow();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ memoryType: "BOGUS" as never })], request)),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ revisionNo: 0 })], request)),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ policyRevisionNo: -1 })], request)),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ score: Number.NaN })], request)),
    ).toThrow();
    expect(() =>
      parseContextPackResponse(request, validResponse([memory({ bodyText: 123 as never })], request)),
    ).toThrow();
  });
});
