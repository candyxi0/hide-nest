import { describe, expect, it } from "vitest";
import {
  buildCandidateSetCloseoutRequest,
  candidateSetConfirmationHash,
  candidateSetRequestHash,
  deriveCandidateId,
  deriveCandidateSetId,
  deriveCandidateSetReviewSessionId,
} from "./candidate-set-closeout-canonicalizer.js";
import { validateCandidateSetCloseoutInput } from "./candidate-set-closeout-input.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA_RE = /^[0-9a-f]{64}$/;

function baseCandidate(overrides: Record<string, unknown> = {}) {
  return {
    candidateKey: "pink",
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

export function canonicalVectorRaw(kind: "three-create" | "mixed" | "revise") {
  const candidates =
    kind === "three-create"
      ? [
          baseCandidate({ candidateKey: "pink", memoryText: "小林喜欢粉色", evidenceSegmentIndexes: [1] }),
          baseCandidate({ candidateKey: "server", memoryText: "下周准备购买家庭服务器", evidenceSegmentIndexes: [1, 2] }),
          baseCandidate({ candidateKey: "budget", memoryText: "购买预算不超过 3000 元", evidenceSegmentIndexes: [3] }),
        ]
      : kind === "mixed"
        ? [
            baseCandidate({ candidateKey: "accepted", memoryText: "已接受候选", hideReason: "仅审阅" }),
            baseCandidate({
              candidateKey: "rejected",
              disposition: "REJECTED",
              evidenceSegmentIndexes: [],
              memoryText: null,
              memoryType: null,
              hideReason: null,
            }),
          ]
        : [
            baseCandidate({
              candidateKey: "revise",
              action: "REVISE",
              originKind: "USER_EDITED",
              finalAuthorKind: "USER",
              memoryText: "用户修订后的内容",
              memoryType: "PRINCIPLE",
              evidenceSegmentIndexes: [2],
              targetMemoryId: "00000000-0000-0000-0000-000000000001",
              expectedMemoryRevisionId: "00000000-0000-0000-0000-000000000002",
              expectedRevisionNo: 7,
              expectedPolicyRevisionNo: 3,
              hideReason: null,
            }),
          ];
  return {
    candidateSetKey: `vector-${kind}`,
    threadKey: "vector-thread",
    scopeRef: "synthetic/vector",
    setVersion: kind === "revise" ? 2 : 1,
    userConfirmed: true,
    evidenceSegments: [
      {
        messages: [
          { speakerKey: "xiaolin", speakerRole: "XIAOLIN", ordinal: 10, occurredAt: "2026-08-17T12:00:00+08:00", bodyText: "证据甲" },
          { speakerKey: "hide", speakerRole: "HIDE", ordinal: 11, occurredAt: "2026-08-17T12:00:01.120000000+08:00", bodyText: "证据乙😀" },
          { speakerKey: "xiaolin", speakerRole: "XIAOLIN", ordinal: 12, occurredAt: "2026-08-17T12:00:02+08:00", bodyText: "证据丙" },
        ],
      },
      {
        messages: [
          { speakerKey: "xiaolin", speakerRole: "XIAOLIN", ordinal: 20, occurredAt: "2026-08-17T04:00:20Z", bodyText: "证据丁" },
          { speakerKey: "hide", speakerRole: "HIDE", ordinal: 21, occurredAt: "2026-08-17T04:00:21Z", bodyText: "证据戊" },
        ],
      },
      {
        messages: [
          { speakerKey: "xiaolin", speakerRole: "XIAOLIN", ordinal: 30, occurredAt: "2026-08-17T04:00:30Z", bodyText: "证据己" },
        ],
      },
    ],
    candidates,
  };
}

function request(kind: "three-create" | "mixed" | "revise" = "three-create") {
  return buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(canonicalVectorRaw(kind)));
}

describe("CandidateSet deterministic identity and evidence mapping", () => {
  it("keeps multiline evidence and memoryText byte-for-byte in the canonical request", () => {
    const evidence = "第一行\n第二行\t缩进\r\n第三行😀";
    const memoryText = "结论第一行\r\n\t第二行😀";
    const raw = canonicalVectorRaw("three-create");
    raw.evidenceSegments[0].messages[0].bodyText = evidence;
    raw.candidates[0].memoryText = memoryText;
    const value = buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(raw));
    expect(value.evidencePool.messages[0].bodyText).toBe(evidence);
    expect(value.candidates[0].memoryText).toBe(memoryText);
    const reparsed = JSON.parse(JSON.stringify(value)) as typeof value;
    expect(reparsed.evidencePool.messages[0].bodyText).toBe(evidence);
    expect(reparsed.candidates[0].memoryText).toBe(memoryText);
  });

  it("keeps three meanings as ordinals 1..3 with distinct deterministic candidate IDs", () => {
    const value = request();
    expect(value.candidates.map((candidate) => candidate.ordinal)).toEqual([1, 2, 3]);
    expect(new Set(value.candidates.map((candidate) => candidate.candidateId)).size).toBe(3);
    expect(value.candidates.map((candidate) => candidate.memoryText)).toEqual([
      "小林喜欢粉色",
      "下周准备购买家庭服务器",
      "购买预算不超过 3000 元",
    ]);
    expect(value.candidates.map((candidate) => candidate.memoryType)).toEqual(["Claim", "Claim", "Claim"]);
  });

  it("maps continuous, separated and singleton segments to 3/2/1-unit anchors", () => {
    const value = request();
    expect(value.evidencePool.anchors.map((anchor) => anchor.units.length)).toEqual([3, 2, 1]);
    expect(value.evidencePool.anchors.map((anchor) => anchor.units.map((unit) => unit.ordinal))).toEqual([
      [1, 2, 3],
      [1, 2],
      [1],
    ]);
    expect(value.evidencePool.messages.map((message) => message.ordinal)).toEqual([10, 11, 12, 20, 21, 30]);
    expect(value.evidencePool.anchors[0].units[1].toOffset).toBe(4);
  });

  it("shares the evidence pool while mapping each candidate to selected anchors only", () => {
    const value = request();
    expect(value.candidates[0].evidenceAnchorIds).toEqual([value.evidencePool.anchors[0].anchorId]);
    expect(value.candidates[1].evidenceAnchorIds).toEqual([
      value.evidencePool.anchors[0].anchorId,
      value.evidencePool.anchors[1].anchorId,
    ]);
    expect(value.candidates[2].evidenceAnchorIds).toEqual([value.evidencePool.anchors[2].anchorId]);
  });

  it("uses UUID v3 for all deterministic identities and the frozen review formula", () => {
    const value = request();
    expect(value.candidateSetId).toMatch(UUID_RE);
    expect(deriveCandidateSetReviewSessionId(value.candidateSetId)).toMatch(UUID_RE);
    for (const candidate of value.candidates) expect(candidate.candidateId).toMatch(UUID_RE);
    for (const message of value.evidencePool.messages) expect(message.sourceUnitId).toMatch(UUID_RE);
    for (const anchor of value.evidencePool.anchors) expect(anchor.anchorId).toMatch(UUID_RE);
  });

  it("length-prefixes every dynamic identity component (ab+c != a+bc)", () => {
    const set = deriveCandidateSetId("same-set");
    expect(deriveCandidateId(set, "ab", 12)).not.toBe(deriveCandidateId(set, "ab1", 2));
    expect(deriveCandidateId(set, "ab", 1)).not.toBe(deriveCandidateId(set, "a", 1));
  });

  it("normalizes occurrence time exactly to Java Instant.toString form", () => {
    const value = request();
    expect(value.evidencePool.messages[0].occurredAt).toBe("2026-08-17T04:00:00Z");
    expect(value.evidencePool.messages[1].occurredAt).toBe("2026-08-17T04:00:01.120Z");
  });
});

describe("CandidateSet canonical hashes and closed wire shape", () => {
  it("matches all 3 temporary Java harness identity/hash vectors byte-for-byte", () => {
    const vectors = [
      {
        kind: "three-create" as const,
        candidateSetId: "d2d78c18-33d4-3354-b29b-6c22176f485e",
        reviewSessionId: "a6a8095d-2025-37d2-a814-a20578667243",
        confirmationHash: "cf346eb57b3efbc5f898cece4160e12b4ab1c15aa19c2b27957c65ab9a59cce0",
        requestHash: "03c6f6f38f8792b8862ace7bb9e6fd3234408bbc1de7eb7dba29ed2fa56309c2",
      },
      {
        kind: "mixed" as const,
        candidateSetId: "46fb397b-37a3-32f8-b9bd-18f668be3e60",
        reviewSessionId: "f98a5d00-19fe-3bd6-8765-212a73ed2452",
        confirmationHash: "d43b98a0d16782a4fe5946bf9171682d8f6dc838c4b1f180b667634693be24d4",
        requestHash: "b52a4911d3475298291f8efd5e1789b312a8bf3716dd6e17b15d9200aecd093a",
      },
      {
        kind: "revise" as const,
        candidateSetId: "43129a95-1443-3279-bf10-7d747c65c013",
        reviewSessionId: "1e89669c-d552-361f-a896-c2d4874a9c44",
        confirmationHash: "b8a36a6ccad55e9a48d318b1d9af4fbf00ebc41ec4a5cb6ad52aed23946f4083",
        requestHash: "cabad28d366a88d9648fb27346c6f815f8139e8cafaceac3f68d6d99d4b122f1",
      },
    ];
    for (const vector of vectors) {
      const value = request(vector.kind);
      expect(value.candidateSetId).toBe(vector.candidateSetId);
      expect(deriveCandidateSetReviewSessionId(value.candidateSetId)).toBe(vector.reviewSessionId);
      expect(value.finalConfirmation.confirmationHash).toBe(vector.confirmationHash);
      expect(value.requestHash).toBe(vector.requestHash);
    }
  });

  it("embeds self-consistent lowercase SHA-256 confirmation/request/body hashes", () => {
    const value = request("mixed");
    expect(value.requestHash).toMatch(SHA_RE);
    expect(value.finalConfirmation.confirmationHash).toMatch(SHA_RE);
    expect(candidateSetConfirmationHash(value)).toBe(value.finalConfirmation.confirmationHash);
    expect(candidateSetRequestHash(value)).toBe(value.requestHash);
    for (const message of value.evidencePool.messages) expect(message.bodyHash).toMatch(SHA_RE);
  });

  it("excludes hideReason from confirmationHash but includes it in requestHash", () => {
    const firstRaw = canonicalVectorRaw("mixed");
    const secondRaw = structuredClone(firstRaw);
    (secondRaw.candidates[0] as Record<string, unknown>).hideReason = "不同的审阅理由";
    const first = buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(firstRaw));
    const second = buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(secondRaw));
    expect(first.finalConfirmation.confirmationHash).toBe(second.finalConfirmation.confirmationHash);
    expect(first.requestHash).not.toBe(second.requestHash);
  });

  it("binds speakerRole into both confirmationHash and requestHash", () => {
    const firstRaw = canonicalVectorRaw("mixed");
    const secondRaw = structuredClone(firstRaw);
    for (const segment of secondRaw.evidenceSegments) {
      for (const message of segment.messages) {
        if (message.speakerKey === "xiaolin") message.speakerRole = "HIDE";
      }
    }
    const first = buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(firstRaw));
    const second = buildCandidateSetCloseoutRequest(validateCandidateSetCloseoutInput(secondRaw));
    expect(first.finalConfirmation.confirmationHash).not.toBe(second.finalConfirmation.confirmationHash);
    expect(first.requestHash).not.toBe(second.requestHash);
  });

  it("includes every nullable candidate field explicitly instead of relying on undefined omission", () => {
    const value = request("mixed");
    expect(Object.keys(value.candidates[1]).sort()).toEqual([
      "action",
      "candidateId",
      "disposition",
      "evidenceAnchorIds",
      "expectedMemoryRevisionId",
      "expectedPolicyRevisionNo",
      "expectedRevisionNo",
      "finalAuthorKind",
      "hideReason",
      "memoryText",
      "memoryType",
      "ordinal",
      "originKind",
      "perspectiveActorId",
      "targetMemoryId",
    ]);
    expect(value.candidates[1].memoryText).toBeNull();
    expect(value.candidates[1].expectedRevisionNo).toBeNull();
  });

  it("emits exactly the frozen top-level HTTP request fields", () => {
    expect(Object.keys(request()).sort()).toEqual([
      "candidateSetId",
      "candidates",
      "evidencePool",
      "finalConfirmation",
      "requestHash",
      "scopeRef",
      "setVersion",
      "threadId",
    ]);
  });

  it("detaches and freezes the canonical facts against source and accessor mutation", () => {
    const raw = canonicalVectorRaw("three-create");
    const input = validateCandidateSetCloseoutInput(raw);
    const value = request();
    raw.evidenceSegments[0].messages[0].bodyText = "mutated source";
    input.evidenceSegments[0].messages[0].bodyText = "mutated validated input";
    expect(value.evidencePool.messages[0].bodyText).toBe("证据甲");
    expect(Object.isFrozen(value)).toBe(true);
    expect(Object.isFrozen(value.candidates)).toBe(true);
    expect(Object.isFrozen(value.candidates[0])).toBe(true);
    expect(() => {
      value.candidates[0].memoryText = "mutated accessor";
    }).toThrow();
  });
});
