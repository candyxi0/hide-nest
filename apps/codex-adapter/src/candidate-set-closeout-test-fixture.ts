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

export function candidateSetTestRaw(kind: "three-create" | "mixed" | "revise") {
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
