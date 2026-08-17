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
          { speakerKey: "xiaolin", ordinal: 10, occurredAt: "2026-08-17T12:00:00+08:00", bodyText: "证据甲" },
          { speakerKey: "hide", ordinal: 11, occurredAt: "2026-08-17T12:00:01.120000000+08:00", bodyText: "证据乙😀" },
          { speakerKey: "xiaolin", ordinal: 12, occurredAt: "2026-08-17T12:00:02+08:00", bodyText: "证据丙" },
        ],
      },
      {
        messages: [
          { speakerKey: "xiaolin", ordinal: 20, occurredAt: "2026-08-17T04:00:20Z", bodyText: "证据丁" },
          { speakerKey: "hide", ordinal: 21, occurredAt: "2026-08-17T04:00:21Z", bodyText: "证据戊" },
        ],
      },
      {
        messages: [
          { speakerKey: "xiaolin", ordinal: 30, occurredAt: "2026-08-17T04:00:30Z", bodyText: "证据己" },
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
        confirmationHash: "a50d987133e955d8ab3e4cdb4881f3d03293944ac8d774a80ab60de6ffece2a7",
        requestHash: "6828d65f9a581ec9ca3625c5ae69e339a50f0f6c7df6dc90c41f01b4fd9dde03",
      },
      {
        kind: "mixed" as const,
        candidateSetId: "46fb397b-37a3-32f8-b9bd-18f668be3e60",
        reviewSessionId: "f98a5d00-19fe-3bd6-8765-212a73ed2452",
        confirmationHash: "8e74889ba1115b6a3c8e4eb75bf700338285d5aeb23bf4cb5b727fc77b52c956",
        requestHash: "56245df55b71fbdf7e1c6178e18a064d03344b70949968a1ca42d4b712aab728",
      },
      {
        kind: "revise" as const,
        candidateSetId: "43129a95-1443-3279-bf10-7d747c65c013",
        reviewSessionId: "1e89669c-d552-361f-a896-c2d4874a9c44",
        confirmationHash: "904476d12c276b57bac8fe9ad5f0aa8bf6f600bdd29467e9774b7492c061678d",
        requestHash: "0b46a51d75daaf0c598bf5fbe80768e47c4ae8f14ae6c39e7fceea43d1a4bf7d",
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
