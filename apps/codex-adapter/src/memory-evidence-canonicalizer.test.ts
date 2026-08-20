import { describe, expect, it } from "vitest";
import {
  buildMemoryEvidenceRequest,
  parseMemoryEvidenceResponse,
  type MemoryEvidenceItem,
  type MemoryEvidenceSuccess,
} from "./memory-evidence-canonicalizer.js";
import type { MemoryEvidenceInput } from "./memory-evidence-input.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const REQUEST_ID = "00000000-0000-4000-8000-000000000001";
const MEMORY_ID = "00000000-0000-4000-8000-000000000002";
const MEMORY_REVISION_ID = "00000000-0000-4000-8000-000000000003";
const ANCHOR_A = "10000000-0000-4000-8000-000000000001";
const ANCHOR_B = "10000000-0000-4000-8000-000000000002";
const ANCHOR_C = "10000000-0000-4000-8000-000000000003";
const ACTOR_HIDE = "20000000-0000-4000-8000-000000000001";

function input(overrides?: Partial<MemoryEvidenceInput>): MemoryEvidenceInput {
  return {
    memoryId: MEMORY_ID,
    memoryRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
    ...overrides,
  };
}

function item(overrides?: Partial<MemoryEvidenceItem>): MemoryEvidenceItem {
  return {
    anchorId: ANCHOR_A,
    sourceUnitId: "30000000-0000-4000-8000-000000000001",
    ordinal: 10,
    actorId: ACTOR_HIDE,
    actorKind: "USER",
    actorStableRef: "user:xiaolin",
    displayLabel: "hide",
    occurredAt: "2026-08-17T12:00:00+08:00",
    bodyText: "完整原文一",
    ...overrides,
  };
}

function response(
  items: MemoryEvidenceItem[],
  overrides?: Partial<Record<string, unknown>>,
): Record<string, unknown> {
  return {
    requestId: REQUEST_ID,
    resultCategory: "SUCCEEDED",
    memoryId: MEMORY_ID,
    currentRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
    evidenceItems: items,
    ...overrides,
  };
}

function parse(raw: unknown, inp: MemoryEvidenceInput = input()): MemoryEvidenceSuccess {
  return parseMemoryEvidenceResponse(inp, raw);
}

function reject(raw: unknown, inp: MemoryEvidenceInput = input()): void {
  expect(() => parse(raw, inp)).toThrow(/INVALID_RESPONSE/);
}

describe("parseMemoryEvidenceResponse success projection + segmentation", () => {
  it("single segment with a single message", () => {
    const result = parse(response([item()]));
    expect(result.requestId).toBe(REQUEST_ID);
    expect(result.resultCategory).toBe("SUCCEEDED");
    expect(result.memoryId).toBe(MEMORY_ID);
    expect(result.currentRevisionId).toBe(MEMORY_REVISION_ID);
    expect(result.revisionNo).toBe(1);
    expect(result.segments).toHaveLength(1);
    expect(result.segments[0]).toEqual({
      segmentNo: 1,
      anchorId: ANCHOR_A,
      messages: [
        {
          sourceUnitId: "30000000-0000-4000-8000-000000000001",
          ordinal: 10,
          displayLabel: "hide",
          occurredAt: "2026-08-17T12:00:00+08:00",
          bodyText: "完整原文一",
        },
      ],
    });
    // Internal actor fields are validated but never projected.
    const text = JSON.stringify(result.segments);
    expect(text).not.toContain("actorId");
    expect(text).not.toContain("actorKind");
    expect(text).not.toContain("actorStableRef");
  });

  it("single segment with consecutive multi-message ordinal run", () => {
    const result = parse(
      response([item({ ordinal: 10 }), item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 11 })]),
    );
    expect(result.segments).toHaveLength(1);
    expect(result.segments[0].messages.map((m) => m.ordinal)).toEqual([10, 11]);
    expect(result.segments[0].messages).toHaveLength(2);
  });

  it("multiple dispersed segments across distinct anchors", () => {
    const result = parse(
      response([
        item({ ordinal: 10 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 11 }),
        item({
          anchorId: ANCHOR_B,
          sourceUnitId: "30000000-0000-4000-8000-000000000003",
          ordinal: 20,
        }),
        item({
          anchorId: ANCHOR_B,
          sourceUnitId: "30000000-0000-4000-8000-000000000004",
          ordinal: 21,
        }),
      ]),
    );
    expect(result.segments).toHaveLength(2);
    expect(result.segments.map((s) => s.anchorId)).toEqual([ANCHOR_A, ANCHOR_B]);
    expect(result.segments[0].messages.map((m) => m.ordinal)).toEqual([10, 11]);
    expect(result.segments[1].messages.map((m) => m.ordinal)).toEqual([20, 21]);
  });

  it("builds a request that mirrors the input verbatim", () => {
    const request = buildMemoryEvidenceRequest(input());
    expect(request).toEqual({
      memoryId: MEMORY_ID,
      memoryRevisionId: MEMORY_REVISION_ID,
      revisionNo: 1,
    });
  });
});

describe("parseMemoryEvidenceResponse identity binding", () => {
  it("rejects a memoryId mismatch", () => {
    reject(
      response([item()], {
        memoryId: "00000000-0000-4000-8000-000000000099",
      }),
    );
  });

  it("rejects a currentRevisionId mismatch", () => {
    reject(
      response([item()], {
        currentRevisionId: "00000000-0000-4000-8000-000000000099",
      }),
    );
  });

  it("rejects a revisionNo mismatch", () => {
    reject(response([item()], { revisionNo: 2 }));
  });

  it("rejects a wrong resultCategory", () => {
    reject(response([item()], { resultCategory: "DENIED" }));
  });

  it("rejects empty evidenceItems", () => {
    reject(response([]));
  });

  it("rejects more than 100 evidence items", () => {
    const items = Array.from({ length: 101 }, (_, i) =>
      item({
        sourceUnitId: `30000000-0000-4000-8000-${String(i + 1).padStart(12, "0")}`,
        ordinal: i * 2,
      }),
    );
    reject(response(items));
  });
});

describe("parseMemoryEvidenceResponse top-level/item schema", () => {
  it("rejects extra or missing top-level fields", () => {
    reject(response([item()], { extra: 1 }));
    const missing = response([item()]) as Record<string, unknown>;
    delete missing["revisionNo"];
    reject(missing);
  });

  it("rejects missing, extra or wrong-typed item fields", () => {
    const base = item();
    const missingOrdinal = { ...base } as Record<string, unknown>;
    delete missingOrdinal["ordinal"];
    reject(response([missingOrdinal as never]));

    const extra = { ...base, extra: 1 } as Record<string, unknown>;
    reject(response([extra as never]));

    reject(response([item({ ordinal: "10" as never })]));
    reject(response([item({ bodyText: 123 as never })]));
    reject(response([item({ displayLabel: "" })]));
    reject(response([item({ occurredAt: 42 as never })]));
  });

  it("rejects non-UUID requestId/memoryId/currentRevisionId/anchor/sourceUnit/actor ids", () => {
    reject(response([item()], { requestId: "not-a-uuid" }));
    reject(response([item({ anchorId: "nope" })]));
    reject(response([item({ sourceUnitId: "x" })]));
    reject(response([item({ actorId: "bad" })]));
    reject(response([item({ actorKind: 5 as never })]));
    reject(response([item({ actorStableRef: null as never })]));
  });

  it("rejects empty actorKind and actorStableRef", () => {
    reject(response([item({ actorKind: "" })]));
    reject(response([item({ actorStableRef: "" })]));
  });
});

describe("parseMemoryEvidenceResponse ordinal/anchor attack matrix", () => {
  it("rejects a duplicate sourceUnitId", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000001", ordinal: 11 }),
      ]),
    );
  });

  it("rejects anchor reflow (same anchor reappearing non-contiguously)", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 20 }),
        item({ anchorId: ANCHOR_A, sourceUnitId: "30000000-0000-4000-8000-000000000003", ordinal: 30 }),
      ]),
    );
  });

  it("rejects an ordinal duplicate within an anchor", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 10 }),
      ]),
    );
  });

  it("rejects an ordinal gap within an anchor", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 12 }),
      ]),
    );
  });

  it("rejects reverse ordinals within an anchor", () => {
    reject(
      response([
        item({ ordinal: 11 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 10 }),
      ]),
    );
  });

  it("rejects overlapping ordinal ranges across segments", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 11 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000003", ordinal: 11 }),
      ]),
    );
  });

  it("accepts and normalises formal reversed block order (B=[20,21] before A=[1,2])", () => {
    const result = parse(
      response([
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000001", ordinal: 20 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 21 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000003", ordinal: 1 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000004", ordinal: 2 }),
      ]),
    );
    expect(result.segments).toHaveLength(2);
    // Output order must be semantic: A=[1,2] then B=[20,21].
    expect(result.segments[0].anchorId).toBe(ANCHOR_A);
    expect(result.segments[0].segmentNo).toBe(1);
    expect(result.segments[0].messages.map((m) => m.ordinal)).toEqual([1, 2]);
    expect(result.segments[1].anchorId).toBe(ANCHOR_B);
    expect(result.segments[1].segmentNo).toBe(2);
    expect(result.segments[1].messages.map((m) => m.ordinal)).toEqual([20, 21]);
  });

  it("normalises three-segment random relation order to semantic order", () => {
    // Relation order: C=[30], A=[1,2], B=[20] — normalised to A→B→C.
    const result = parse(
      response([
        item({ anchorId: ANCHOR_C, sourceUnitId: "30000000-0000-4000-8000-000000000001", ordinal: 30 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 1 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000003", ordinal: 2 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000004", ordinal: 20 }),
      ]),
    );
    expect(result.segments).toHaveLength(3);
    expect(result.segments.map((s) => s.anchorId)).toEqual([ANCHOR_A, ANCHOR_B, ANCHOR_C]);
    expect(result.segments[0].messages.map((m) => m.ordinal)).toEqual([1, 2]);
    expect(result.segments[1].messages.map((m) => m.ordinal)).toEqual([20]);
    expect(result.segments[2].messages.map((m) => m.ordinal)).toEqual([30]);
  });

  it("still rejects anchor reflow — not masked by global ordinal sort", () => {
    // Appears sorted by ordinal globally but anchor A reappears non-contiguously.
    reject(
      response([
        item({ ordinal: 1 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 10 }),
        item({ sourceUnitId: "30000000-0000-4000-8000-000000000003", ordinal: 20 }),
      ]),
    );
  });

  it("still rejects overlap when two blocks share the same firstOrdinal (tie-break surfaces overlap)", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 10 }),
      ]),
    );
  });

  it("rejects adjacent fake segmentation (no real gap between anchors)", () => {
    reject(
      response([
        item({ ordinal: 10 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 11 }),
      ]),
    );
  });

  it("accepts a real gap between anchors (disjoint, separated)", () => {
    const result = parse(
      response([
        item({ ordinal: 10 }),
        item({ anchorId: ANCHOR_B, sourceUnitId: "30000000-0000-4000-8000-000000000002", ordinal: 20 }),
      ]),
    );
    expect(result.segments).toHaveLength(2);
  });
});

describe("parseMemoryEvidenceResponse calendar validation", () => {
  it("rejects impossible calendar dates instead of normalizing them", () => {
    reject(response([item({ occurredAt: "2026-02-30T00:00:00Z" })]));
    reject(response([item({ occurredAt: "2025-02-29T00:00:00Z" })]));
    reject(response([item({ occurredAt: "2026-04-31T00:00:00Z" })]));
    reject(response([item({ occurredAt: "2026-08-16T24:00:00Z" })]));
    reject(response([item({ occurredAt: "2026-08-16T00:60:00Z" })]));
    reject(response([item({ occurredAt: "not-a-date" })]));
  });

  it("rejects illegal timezone offsets", () => {
    reject(response([item({ occurredAt: "2026-08-16T00:00:00+24:00" })]));
    reject(response([item({ occurredAt: "2026-08-16T00:00:00+00:60" })]));
  });

  it("accepts a legal leap-day and offset timestamps", () => {
    const result = parse(response([item({ occurredAt: "2024-02-29T23:59:59.123456789+08:00" })]));
    expect(result.segments[0].messages[0].occurredAt).toBe("2024-02-29T23:59:59.123456789+08:00");
    expect(() => parse(response([item({ occurredAt: "2026-08-17T12:00:00-07:00" })]))).not.toThrow();
    expect(() => parse(response([item({ occurredAt: "2026-08-17T12:00:00Z" })]))).not.toThrow();
  });
});

describe("parseMemoryEvidenceResponse UUID shape sanity", () => {
  it("emits only canonical UUID-shaped ids in the projection", () => {
    const result = parse(response([item()]));
    expect(result.requestId).toMatch(UUID_RE);
    expect(result.segments[0].messages[0].sourceUnitId).toMatch(UUID_RE);
  });
});
