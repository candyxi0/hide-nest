import { describe, expect, it } from "vitest";
import {
  bodyHash,
  buildCloseoutRequest,
  codePointCount,
  confirmationProof,
  deriveActorId,
  deriveMemoryId,
  externalUnitRef,
  nameUuidFromBytes,
  normalizeOccurredAt,
  reviewManifestHash,
  threadManifestHash,
  type CloseoutInput,
} from "./closeout-canonicalizer.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA_RE = /^[0-9a-f]{64}$/;

function validInput(): CloseoutInput {
  return {
    closeoutKey: "closeout-key-001",
    threadKey: "thread-key-001",
    userConfirmed: true,
    candidate: {
      perspectiveSpeakerKey: "xiaolin",
      memoryType: "EVENT",
      bodyText: "小林确认了合成记忆候选",
    },
    evidenceSegments: [
      {
        messages: [
          {
            speakerKey: "xiaolin",
            ordinal: 0,
            occurredAt: "2026-08-13T12:00:00+08:00",
            bodyText: "这是证据消息一",
          },
          {
            speakerKey: "hide",
            ordinal: 1,
            occurredAt: "2026-08-13T12:01:00+08:00",
            bodyText: "这是证据消息二",
          },
        ],
      },
    ],
  };
}

describe("buildCloseoutRequest determinism (6.1.2)", () => {
  it("produces identical JSON for the same input twice", () => {
    const a = JSON.stringify(buildCloseoutRequest(validInput()));
    const b = JSON.stringify(buildCloseoutRequest(validInput()));
    expect(a).toBe(b);
  });

  it("derives valid, deterministic UUID identity", () => {
    const req = buildCloseoutRequest(validInput());
    expect(req.submissionId).toMatch(UUID_RE);
    expect(req.threadId).toMatch(UUID_RE);
    expect(req.hideSelection.perspectiveActorId).toMatch(UUID_RE);
    expect(req.userConfirmation.confirmationSourceUnitId).toMatch(UUID_RE);
    for (const m of req.threadReaderManifest.selectedEvidenceMessages) {
      expect(m.sourceUnitId).toMatch(UUID_RE);
      expect(m.actorId).toMatch(UUID_RE);
    }
    for (const a of req.sourceAnchors) {
      expect(a.anchorId).toMatch(UUID_RE);
    }
  });

  it("keeps sourceUnitId and anchorId distinct per ordinal", () => {
    const req = buildCloseoutRequest(validInput());
    const msg0 = req.threadReaderManifest.selectedEvidenceMessages[0];
    const anchor0 = req.sourceAnchors[0];
    expect(anchor0.units[0].sourceUnitId).toBe(msg0.sourceUnitId);
    expect(anchor0.anchorId).not.toBe(msg0.sourceUnitId);
  });
});

describe("explicit evidence segment → anchor mapping (R4)", () => {
  function segmented(ordinals: number[]) {
    return {
      messages: ordinals.map((ordinal) => ({
        speakerKey: "xiaolin",
        ordinal,
        occurredAt: "2026-08-13T12:00:00+08:00",
        bodyText: `消息-${ordinal}`,
      })),
    };
  }

  it("maps a single contiguous segment to one anchor with every message as a unit", () => {
    const input = validInput();
    input.evidenceSegments = [segmented([0, 1, 2])];
    const req = buildCloseoutRequest(input);
    expect(req.sourceAnchors).toHaveLength(1);
    expect(req.sourceAnchors[0].units.map((u) => u.ordinal)).toEqual([0, 1, 2]);
    expect(req.threadReaderManifest.continuous).toBe(true);
    expect(req.threadReaderManifest.fromOrdinal).toBe(0);
    expect(req.threadReaderManifest.toOrdinal).toBe(2);
    expect(req.threadReaderManifest.selectedEvidenceMessages.map((m) => m.ordinal)).toEqual([0, 1, 2]);
  });

  it("maps two separated segments to two anchors and continuous=false", () => {
    const input = validInput();
    input.evidenceSegments = [segmented([0, 1]), segmented([4, 5])];
    const req = buildCloseoutRequest(input);
    expect(req.sourceAnchors).toHaveLength(2);
    expect(req.sourceAnchors[0].units.map((u) => u.ordinal)).toEqual([0, 1]);
    expect(req.sourceAnchors[1].units.map((u) => u.ordinal)).toEqual([4, 5]);
    expect(req.threadReaderManifest.continuous).toBe(false);
    expect(req.threadReaderManifest.fromOrdinal).toBe(0);
    expect(req.threadReaderManifest.toOrdinal).toBe(5);
    expect(req.threadReaderManifest.selectedEvidenceMessages.map((m) => m.ordinal)).toEqual([0, 1, 4, 5]);
  });

  it("maps a single-message segment to one anchor with one unit", () => {
    const input = validInput();
    input.evidenceSegments = [segmented([7])];
    const req = buildCloseoutRequest(input);
    expect(req.sourceAnchors).toHaveLength(1);
    expect(req.sourceAnchors[0].units).toHaveLength(1);
    expect(req.sourceAnchors[0].units[0].ordinal).toBe(7);
    expect(req.threadReaderManifest.continuous).toBe(true);
  });

  it("derives deterministic, distinct anchor ids from the segment's first ordinal", () => {
    const input = validInput();
    input.evidenceSegments = [segmented([0, 1]), segmented([4, 5])];
    const a = buildCloseoutRequest(input);
    const b = buildCloseoutRequest(input);
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
    expect(a.sourceAnchors[0].anchorId).not.toBe(a.sourceAnchors[1].anchorId);
    expect(a.sourceAnchors[0].anchorId).not.toBe(a.sourceAnchors[0].units[0].sourceUnitId);
  });

  it("recomputes manifest/review/proof from real grouped anchors and continuous flag", () => {
    const input = validInput();
    input.evidenceSegments = [segmented([0, 1]), segmented([4, 5])];
    const req = buildCloseoutRequest(input);

    const manifestHash = threadManifestHash({
      schemaVersion: req.threadReaderManifest.schemaVersion,
      fromOrdinal: req.threadReaderManifest.fromOrdinal,
      toOrdinal: req.threadReaderManifest.toOrdinal,
      continuous: req.threadReaderManifest.continuous,
      selectedEvidenceMessages: req.threadReaderManifest.selectedEvidenceMessages,
    });
    expect(manifestHash).toBe(req.threadReaderManifest.manifestHash);

    const reviewHash = reviewManifestHash({
      submissionId: req.submissionId,
      threadId: req.threadId,
      hideSelection: {
        perspectiveActorId: req.hideSelection.perspectiveActorId,
        memoryType: req.hideSelection.memoryType,
        bodyHash: req.hideSelection.bodyHash,
      },
      threadManifestHash: req.threadReaderManifest.manifestHash,
      sourceAnchors: req.sourceAnchors,
      userConfirmation: { decision: req.userConfirmation.decision },
    });
    expect(reviewHash).toBe(req.userConfirmation.reviewManifestHash);

    const proof = confirmationProof(
      req.threadId,
      req.userConfirmation.confirmationSourceUnitId,
      req.userConfirmation.reviewManifestHash,
      req.submissionId,
    );
    expect(proof).toBe(req.confirmationProof);
  });
});

describe("canonical hashes (6.1.3)", () => {
  it("computes a golden UTF-8 sha256 bodyHash for a Chinese+emoji string", () => {
    expect(bodyHash("中文😀")).toBe("e973a1c1b41c5c9f4fbac31fcc311536dfffd003bfb914580455170a599953fa");
  });

  it("emits 64-char lowercase hex for every hash", () => {
    const req = buildCloseoutRequest(validInput());
    expect(req.hideSelection.bodyHash).toMatch(SHA_RE);
    expect(req.threadReaderManifest.manifestHash).toMatch(SHA_RE);
    expect(req.userConfirmation.reviewManifestHash).toMatch(SHA_RE);
    expect(req.confirmationProof).toMatch(SHA_RE);
    for (const m of req.threadReaderManifest.selectedEvidenceMessages) {
      expect(m.bodyHash).toMatch(SHA_RE);
    }
  });

  it("is self-consistent: manifest/review/proof recompute to the embedded values", () => {
    const req = buildCloseoutRequest(validInput());
    const manifestHash = threadManifestHash({
      schemaVersion: req.threadReaderManifest.schemaVersion,
      fromOrdinal: req.threadReaderManifest.fromOrdinal,
      toOrdinal: req.threadReaderManifest.toOrdinal,
      continuous: req.threadReaderManifest.continuous,
      selectedEvidenceMessages: req.threadReaderManifest.selectedEvidenceMessages,
    });
    expect(manifestHash).toBe(req.threadReaderManifest.manifestHash);

    const reviewHash = reviewManifestHash({
      submissionId: req.submissionId,
      threadId: req.threadId,
      hideSelection: {
        perspectiveActorId: req.hideSelection.perspectiveActorId,
        memoryType: req.hideSelection.memoryType,
        bodyHash: req.hideSelection.bodyHash,
      },
      threadManifestHash: req.threadReaderManifest.manifestHash,
      sourceAnchors: req.sourceAnchors,
      userConfirmation: { decision: req.userConfirmation.decision },
    });
    expect(reviewHash).toBe(req.userConfirmation.reviewManifestHash);

    const proof = confirmationProof(
      req.threadId,
      req.userConfirmation.confirmationSourceUnitId,
      req.userConfirmation.reviewManifestHash,
      req.submissionId,
    );
    expect(proof).toBe(req.confirmationProof);
  });
});

describe("code point anchor boundary (6.1.3)", () => {
  it("covers full code points with a half-open interval [0, n)", () => {
    const input = validInput();
    input.evidenceSegments = [
      { messages: [{ speakerKey: "xiaolin", ordinal: 0, occurredAt: "2026-08-13T12:00:00Z", bodyText: "a😀b" }] },
    ];
    const req = buildCloseoutRequest(input);
    expect(codePointCount("a😀b")).toBe(3);
    expect(req.sourceAnchors[0].units[0].fromOffset).toBe(0);
    expect(req.sourceAnchors[0].units[0].toOffset).toBe(3);
  });
});

describe("time normalization", () => {
  it("normalizes RFC3339 with offset to a UTC instant string", () => {
    expect(normalizeOccurredAt("2026-08-13T12:34:56.123+08:00")).toBe("2026-08-13T04:34:56.123Z");
    expect(normalizeOccurredAt("2026-08-13T04:34:56Z")).toBe("2026-08-13T04:34:56Z");
    expect(normalizeOccurredAt("2026-08-13T04:34:56.500000000Z")).toBe("2026-08-13T04:34:56.500Z");
  });

  it("throws on malformed occurredAt", () => {
    expect(() => normalizeOccurredAt("not-a-date")).toThrow();
    expect(() => normalizeOccurredAt("2026-13-01T00:00:00Z")).toThrow();
  });
});

describe("identity derivation", () => {
  it("matches Java UUID.nameUUIDFromBytes (version 3, MD5)", () => {
    // Java UUID.nameUUIDFromBytes("memory:test".getBytes(UTF_8))
    const uuid = nameUuidFromBytes(Buffer.from("memory:test", "utf8"));
    expect(uuid).toMatch(UUID_RE);
    expect(uuid.charAt(14)).toBe("3"); // version nibble
  });

  it("derives memoryId from submissionId like the backend facade", () => {
    const req = buildCloseoutRequest(validInput());
    expect(deriveMemoryId(req.submissionId)).toMatch(UUID_RE);
  });

  it("rejects separator-collision inputs with distinct actorIds (R1-04)", () => {
    expect(deriveActorId("a:b", "c")).not.toBe(deriveActorId("a", "b:c"));
  });

  it("externalUnitRef never contains the threadKey plaintext", () => {
    const ref = externalUnitRef("secret-thread-key", 7);
    expect(ref).not.toContain("secret-thread-key");
    expect(ref).toContain("7");
  });
});

describe("closed request shape", () => {
  it("emits exactly the CloseoutSubmissionRequest fields with closed nested objects", () => {
    const req = buildCloseoutRequest(validInput());
    expect(Object.keys(req).sort()).toEqual([
      "confirmationProof",
      "hideSelection",
      "sourceAnchors",
      "submissionId",
      "threadId",
      "threadReaderManifest",
      "userConfirmation",
    ]);
    expect(Object.keys(req.hideSelection).sort()).toEqual([
      "bodyHash",
      "bodyText",
      "memoryType",
      "perspectiveActorId",
    ]);
    expect(Object.keys(req.userConfirmation).sort()).toEqual([
      "confirmationSourceUnitId",
      "decision",
      "reviewManifestHash",
    ]);
    expect(Object.keys(req.threadReaderManifest).sort()).toEqual([
      "continuous",
      "fromOrdinal",
      "manifestHash",
      "schemaVersion",
      "selectedEvidenceMessages",
      "toOrdinal",
    ]);
    for (const m of req.threadReaderManifest.selectedEvidenceMessages) {
      expect(Object.keys(m).sort()).toEqual([
        "actorId",
        "bodyHash",
        "bodyText",
        "externalUnitRef",
        "occurredAt",
        "ordinal",
        "sourceUnitId",
      ]);
    }
    for (const a of req.sourceAnchors) {
      expect(Object.keys(a).sort()).toEqual(["anchorId", "units"]);
      expect(Object.keys(a.units[0]).sort()).toEqual([
        "fromOffset",
        "ordinal",
        "sourceUnitId",
        "toOffset",
      ]);
    }
  });
});
