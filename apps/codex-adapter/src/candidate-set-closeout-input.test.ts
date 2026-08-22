import { describe, expect, it } from "vitest";
import {
  CandidateSetInputError,
  MAX_EVIDENCE_BYTES,
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
    speakerRole: speakerKey === "hide" ? "HIDE" : "XIAOLIN",
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

  it("preserves evidence and memoryText with LF, CRLF, Tab and emoji byte-for-byte", () => {
    const evidence = "第一行\n第二行\t缩进\r\n第三行😀";
    const memoryText = "结论第一行\r\n\t第二行😀";
    const raw = validRaw();
    raw.evidenceSegments[0].messages[0].bodyText = evidence;
    raw.candidates = [candidate({ memoryText })];
    const input = validateCandidateSetCloseoutInput(raw);
    expect(input.evidenceSegments[0].messages[0].bodyText).toBe(evidence);
    expect(input.candidates[0].memoryText).toBe(memoryText);
  });

  it("rejects blank, unsafe controls and unpaired surrogates in evidence and memoryText", () => {
    for (const bodyText of ["   \n\t\r", "bad\u0000text", "bad\u000btext", "bad\u000ctext", "bad\u007ftext", "bad\u0080text", "bad\ud800text"]) {
      const raw = validRaw();
      raw.evidenceSegments[0].messages[0].bodyText = bodyText;
      reject(raw);

      const memory = validRaw();
      memory.candidates = [candidate({ memoryText: bodyText })];
      reject(memory);
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

  it("accepts the exact UTF-8 boundary and rejects one byte beyond it", () => {
    const exact = validRaw();
    exact.evidenceSegments = [{ messages: [message(1, "xiaolin", "a".repeat(MAX_EVIDENCE_BYTES))] }];
    expect(validateCandidateSetCloseoutInput(exact).evidenceSegments[0].messages[0].bodyText).toHaveLength(
      MAX_EVIDENCE_BYTES,
    );

    const tooLarge = validRaw();
    tooLarge.evidenceSegments = [
      { messages: [message(1, "xiaolin", "a".repeat(MAX_EVIDENCE_BYTES + 1))] },
    ];
    reject(tooLarge);
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

  it("requires speakerRole and rejects missing/null/unknown values", () => {
    const missing = validRaw();
    delete (missing.evidenceSegments[0].messages[0] as Record<string, unknown>).speakerRole;
    reject(missing);

    const nullRole = validRaw();
    (nullRole.evidenceSegments[0].messages[0] as Record<string, unknown>).speakerRole = null;
    reject(nullRole);

    const unknownRole = validRaw();
    (unknownRole.evidenceSegments[0].messages[0] as Record<string, unknown>).speakerRole = "ALIEN";
    reject(unknownRole);
  });

  it("rejects the same speakerKey mapped to conflicting speakerRole", () => {
    const raw = validRaw();
    // message 10 and message 12 both use speakerKey "xiaolin"; flip message 12 to HIDE.
    (raw.evidenceSegments[0].messages[2] as Record<string, unknown>).speakerRole = "HIDE";
    reject(raw);
  });

  it("accepts a HIDE-perspective candidate while evidence speakers keep their own roles", () => {
    const raw = validRaw();
    raw.candidates = [candidate({ perspectiveSpeakerKey: "hide", memoryText: "hide 的视角记忆" })];
    const input = validateCandidateSetCloseoutInput(raw);
    expect(input.candidates[0].perspectiveSpeakerKey).toBe("hide");
    expect(input.evidenceSegments[0].messages[0].speakerRole).toBe("XIAOLIN");
    expect(input.evidenceSegments[0].messages[1].speakerRole).toBe("HIDE");
  });
});
