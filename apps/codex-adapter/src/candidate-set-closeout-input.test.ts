import { describe, expect, it } from "vitest";
import {
  CandidateSetInputError,
  validateCandidateSetCloseoutInput,
} from "./candidate-set-closeout-input.js";

const TARGET_ID = "00000000-0000-0000-0000-000000000001";
const REVISION_ID = "00000000-0000-0000-0000-000000000002";

function candidate(overrides: Record<string, unknown> = {}) {
  return {
    candidateKey: "candidate-1",
    disposition: "ACCEPTED",
    action: "CREATE",
    originKind: "HIDE_PROPOSED",
    finalAuthorKind: "HIDE",
    perspectiveSpeakerKey: "xiaolin",
    memoryText: "小林喜欢粉色",
    memoryType: "CLAIM",
    evidenceSegmentIndexes: [1],
    targetMemoryId: null,
    expectedMemoryRevisionId: null,
    expectedRevisionNo: null,
    expectedPolicyRevisionNo: null,
    hideReason: null,
    ...overrides,
  };
}

function message(ordinal: number, speakerKey = "xiaolin", bodyText = `证据-${ordinal}`) {
  return {
    speakerKey,
    ordinal,
    occurredAt: "2026-08-17T12:00:00+08:00",
    bodyText,
  };
}

function validRaw() {
  return {
    candidateSetKey: "candidate-set-retry-1",
    threadKey: "thread-1",
    scopeRef: "synthetic/local-v1",
    setVersion: 1,
    userConfirmed: true,
    evidenceSegments: [
      { messages: [message(10), message(11, "hide"), message(12)] },
      { messages: [message(20), message(21, "hide")] },
      { messages: [message(30)] },
    ],
    candidates: [candidate()],
  };
}

function reject(raw: unknown): void {
  expect(() => validateCandidateSetCloseoutInput(raw)).toThrow(CandidateSetInputError);
}

describe("CandidateSet closed input and atomic candidates", () => {
  it("accepts 0 candidates and preserves the three evidence segment shapes", () => {
    const raw = validRaw();
    raw.candidates = [];
    const input = validateCandidateSetCloseoutInput(raw);
    expect(input.candidates).toEqual([]);
    expect(input.evidenceSegments.map((segment) => segment.messages.length)).toEqual([3, 2, 1]);
  });

  it("keeps three independent meanings as three candidates in caller order", () => {
    const raw = validRaw();
    raw.candidates = [
      candidate({ candidateKey: "pink", memoryText: "小林喜欢粉色" }),
      candidate({ candidateKey: "server", memoryText: "下周准备购买家庭服务器" }),
      candidate({ candidateKey: "budget", memoryText: "购买预算不超过 3000 元" }),
    ];
    const input = validateCandidateSetCloseoutInput(raw);
    expect(input.candidates.map((item) => item.memoryText)).toEqual([
      "小林喜欢粉色",
      "下周准备购买家庭服务器",
      "购买预算不超过 3000 元",
    ]);
  });

  it("accepts all-rejected with empty evidence references", () => {
    const raw = validRaw();
    raw.candidates = [
      candidate({
        disposition: "REJECTED",
        evidenceSegmentIndexes: [],
        memoryText: "被小林拒绝的候选",
      }),
    ];
    expect(validateCandidateSetCloseoutInput(raw).candidates[0].disposition).toBe("REJECTED");
  });

  it("accepts complete REVISE and SUPERSEDE target matrices", () => {
    for (const action of ["REVISE", "SUPERSEDE"]) {
      const raw = validRaw();
      raw.candidates = [
        candidate({
          action,
          targetMemoryId: TARGET_ID,
          expectedMemoryRevisionId: REVISION_ID,
          expectedRevisionNo: 1,
          expectedPolicyRevisionNo: 1,
        }),
      ];
      expect(validateCandidateSetCloseoutInput(raw).candidates[0].action).toBe(action);
    }
  });

  it("rejects the old single-candidate shape and any unknown or omitted field", () => {
    const old = {
      closeoutKey: "old",
      threadKey: "thread",
      userConfirmed: true,
      candidate: {},
      evidenceSegments: [],
    };
    reject(old);
    reject({ ...validRaw(), unknown: true });
    const missing = validRaw() as Record<string, unknown>;
    delete missing.scopeRef;
    reject(missing);
    const missingNullField = validRaw();
    delete (missingNullField.candidates[0] as Record<string, unknown>).hideReason;
    reject(missingNullField);
  });

  it("rejects false confirmation, unsafe version and more than eight candidates", () => {
    reject({ ...validRaw(), userConfirmed: false });
    reject({ ...validRaw(), setVersion: Number.MAX_SAFE_INTEGER + 1 });
    const raw = validRaw();
    raw.candidates = Array.from({ length: 9 }, (_, index) =>
      candidate({ candidateKey: `candidate-${index}` }),
    );
    reject(raw);
  });

  it.each([
    [[10, 10], "duplicate"],
    [[10, 12], "gap"],
    [[11, 10], "descending"],
  ])("rejects segment-internal %s ordinals", (ordinals) => {
    const raw = validRaw();
    raw.evidenceSegments = [{ messages: (ordinals as number[]).map((ordinal) => message(ordinal)) }];
    reject(raw);
  });

  it("rejects cross-segment overlap, descending order and adjacent fake split", () => {
    for (const segments of [
      [{ messages: [message(10), message(11)] }, { messages: [message(11)] }],
      [{ messages: [message(20)] }, { messages: [message(10)] }],
      [{ messages: [message(10), message(11)] }, { messages: [message(12)] }],
    ]) {
      const raw = validRaw();
      raw.evidenceSegments = segments;
      reject(raw);
    }
  });

  it("rejects blank/control/unpaired-surrogate evidence and invalid offset dates", () => {
    for (const bodyText of ["   ", "bad\u0000text", "bad\ud800text"]) {
      const raw = validRaw();
      raw.evidenceSegments[0].messages[0].bodyText = bodyText;
      reject(raw);
    }
    const raw = validRaw();
    raw.evidenceSegments[0].messages[0].occurredAt = "2026-02-30T00:00:00Z";
    reject(raw);
    raw.evidenceSegments[0].messages[0].occurredAt = "2026-08-17T00:00:00+18:01";
    reject(raw);
  });

  it("enforces both per-message and whole-pool 1 MiB UTF-8 budgets", () => {
    const tooLarge = validRaw();
    tooLarge.evidenceSegments = [{ messages: [message(1, "xiaolin", "中".repeat(400_000))] }];
    reject(tooLarge);

    const totalTooLarge = validRaw();
    totalTooLarge.evidenceSegments = [
      { messages: [message(1, "xiaolin", "a".repeat(600_000)), message(2, "xiaolin", "b".repeat(600_000))] },
    ];
    reject(totalTooLarge);
  });

  it("rejects duplicate candidateKey and invalid segment indexes", () => {
    const duplicate = validRaw();
    duplicate.candidates = [candidate(), candidate()];
    reject(duplicate);
    for (const indexes of [[0], [4], [2, 1], [1, 1]]) {
      const raw = validRaw();
      raw.candidates = [candidate({ evidenceSegmentIndexes: indexes })];
      reject(raw);
    }
  });

  it("requires an accepted candidate's perspective speaker in its own referenced evidence", () => {
    const raw = validRaw();
    raw.candidates = [candidate({ perspectiveSpeakerKey: "hide", evidenceSegmentIndexes: [3] })];
    reject(raw);
  });

  it("rejects evidence on REJECTED, targets on CREATE and incomplete non-CREATE targets", () => {
    const rejected = validRaw();
    rejected.candidates = [candidate({ disposition: "REJECTED" })];
    reject(rejected);

    const createTarget = validRaw();
    createTarget.candidates = [candidate({ targetMemoryId: TARGET_ID })];
    reject(createTarget);

    const reviseMissing = validRaw();
    reviseMissing.candidates = [candidate({ action: "REVISE", targetMemoryId: TARGET_ID })];
    reject(reviseMissing);
  });

  it("requires USER attribution for user-edited and user-added candidates", () => {
    for (const originKind of ["USER_EDITED", "USER_ADDED"]) {
      const raw = validRaw();
      raw.candidates = [candidate({ originKind, finalAuthorKind: "HIDE" })];
      reject(raw);
    }
  });

  it("returns detached arrays and objects", () => {
    const raw = validRaw();
    const input = validateCandidateSetCloseoutInput(raw);
    raw.candidates[0].memoryText = "mutated";
    raw.evidenceSegments[0].messages[0].bodyText = "mutated";
    expect(input.candidates[0].memoryText).toBe("小林喜欢粉色");
    expect(input.evidenceSegments[0].messages[0].bodyText).toBe("证据-10");
  });
});
