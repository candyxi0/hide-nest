import {
  bodyHash,
  codePointCount,
  deriveActorId,
  deriveThreadId,
  deriveUuid,
  externalUnitRef,
  field,
  nameUuidFromBytes,
  normalizeOccurredAt,
  numberField,
  sha256Hex,
  type EvidenceSegmentInput,
  type MemoryType,
} from "./closeout-canonicalizer.js";

export const CANDIDATE_DISPOSITIONS = ["ACCEPTED", "REJECTED"] as const;
export type CandidateDisposition = (typeof CANDIDATE_DISPOSITIONS)[number];

export const CANDIDATE_ACTIONS = ["CREATE", "REVISE", "SUPERSEDE"] as const;
export type CandidateAction = (typeof CANDIDATE_ACTIONS)[number];

export const CANDIDATE_ORIGIN_KINDS = ["HIDE_PROPOSED", "USER_EDITED", "USER_ADDED"] as const;
export type CandidateOriginKind = (typeof CANDIDATE_ORIGIN_KINDS)[number];

export const FINAL_AUTHOR_KINDS = ["HIDE", "USER"] as const;
export type FinalAuthorKind = (typeof FINAL_AUTHOR_KINDS)[number];

export type CandidateSetCanonicalMemoryType =
  | "Event"
  | "Claim"
  | "Quote"
  | "Interpretation"
  | "Calibration"
  | "Principle";

const CANONICAL_MEMORY_TYPE: Record<MemoryType, CandidateSetCanonicalMemoryType> = {
  EVENT: "Event",
  CLAIM: "Claim",
  QUOTE: "Quote",
  INTERPRETATION: "Interpretation",
  CALIBRATION: "Calibration",
  PRINCIPLE: "Principle",
};

export interface CandidateSetCandidateInput {
  candidateKey: string;
  disposition: CandidateDisposition;
  action: CandidateAction;
  originKind: CandidateOriginKind;
  finalAuthorKind: FinalAuthorKind;
  perspectiveSpeakerKey: string;
  memoryText: string | null;
  memoryType: MemoryType | null;
  evidenceSegmentIndexes: number[];
  targetMemoryId: string | null;
  expectedMemoryRevisionId: string | null;
  expectedRevisionNo: number | null;
  expectedPolicyRevisionNo: number | null;
  hideReason: string | null;
}

export interface CandidateSetCloseoutInput {
  candidateSetKey: string;
  threadKey: string;
  scopeRef: string;
  setVersion: number;
  userConfirmed: true;
  evidenceSegments: EvidenceSegmentInput[];
  candidates: CandidateSetCandidateInput[];
}

export interface CandidateSetEvidenceMessage {
  sourceUnitId: string;
  actorId: string;
  ordinal: number;
  externalUnitRef: string;
  occurredAt: string;
  bodyText: string;
  bodyHash: string;
}

export interface CandidateSetAnchor {
  anchorId: string;
  units: Array<{
    sourceUnitId: string;
    fromOffset: number;
    toOffset: number;
    ordinal: number;
  }>;
}

export interface CandidateSetWireCandidate {
  candidateId: string;
  ordinal: number;
  disposition: CandidateDisposition;
  action: CandidateAction;
  originKind: CandidateOriginKind;
  finalAuthorKind: FinalAuthorKind;
  memoryText: string | null;
  memoryType: CandidateSetCanonicalMemoryType | null;
  perspectiveActorId: string;
  evidenceAnchorIds: string[];
  targetMemoryId: string | null;
  expectedMemoryRevisionId: string | null;
  expectedRevisionNo: number | null;
  expectedPolicyRevisionNo: number | null;
  hideReason: string | null;
}

/** Closed JSON body accepted by POST /v1/review-sessions/{reviewSessionId}/final-submissions. */
export interface CandidateSetCloseoutRequest {
  candidateSetId: string;
  requestHash: string;
  threadId: string;
  scopeRef: string;
  setVersion: number;
  finalConfirmation: {
    decision: "CONFIRM_SET";
    confirmedSetVersion: number;
    confirmationHash: string;
  };
  evidencePool: {
    messages: CandidateSetEvidenceMessage[];
    anchors: CandidateSetAnchor[];
  };
  candidates: CandidateSetWireCandidate[];
}

export function deriveCandidateSetId(candidateSetKey: string): string {
  return deriveUuid(["candidate-set", candidateSetKey]);
}

export function deriveCandidateId(
  candidateSetId: string,
  candidateKey: string,
  ordinal: number,
): string {
  return deriveUuid(["candidate", candidateSetId, candidateKey, ordinal]);
}

export function deriveCandidateSetSourceUnitId(candidateSetId: string, ordinal: number): string {
  return deriveUuid(["candidate-set-source", candidateSetId, ordinal]);
}

export function deriveCandidateSetAnchorId(candidateSetId: string, firstOrdinal: number): string {
  return deriveUuid(["candidate-set-anchor", candidateSetId, firstOrdinal]);
}

/** Mirrors UUID.nameUUIDFromBytes(UTF8("candidate-set:review:" + candidateSetId)) exactly. */
export function deriveCandidateSetReviewSessionId(candidateSetId: string): string {
  return nameUuidFromBytes(Buffer.from(`candidate-set:review:${candidateSetId}`, "utf8"));
}

function nullableField(value: string | null): string {
  return value === null ? "-1:" : field(value);
}

function nullableNumberField(value: number | null): string {
  return value === null ? "-1:" : numberField(value);
}

/** Exact TypeScript mirror of LocalV1CandidateSetCanonicalizer.confirmationHash. */
export function candidateSetConfirmationHash(request: CandidateSetCloseoutRequest): string {
  let canonical = numberField(request.setVersion);
  const candidates = [...request.candidates].sort((a, b) => a.ordinal - b.ordinal);
  canonical += numberField(candidates.length);
  for (const candidate of candidates) {
    canonical += field(candidate.candidateId);
    canonical += numberField(candidate.ordinal);
    canonical += field(candidate.disposition);
    canonical += field(candidate.action);
    canonical += field(candidate.originKind);
    canonical += field(candidate.finalAuthorKind);
    canonical += nullableField(candidate.memoryText);
    canonical += nullableField(candidate.memoryType);
    canonical += field(candidate.perspectiveActorId);
    canonical += numberField(candidate.evidenceAnchorIds.length);
    for (const anchorId of candidate.evidenceAnchorIds) canonical += field(anchorId);
    canonical += nullableField(candidate.targetMemoryId);
    canonical += nullableField(candidate.expectedMemoryRevisionId);
    canonical += nullableNumberField(candidate.expectedRevisionNo);
    canonical += nullableNumberField(candidate.expectedPolicyRevisionNo);
  }
  return sha256Hex(canonical);
}

/** Exact TypeScript mirror of LocalV1CandidateSetCanonicalizer.requestHash. */
export function candidateSetRequestHash(request: CandidateSetCloseoutRequest): string {
  let canonical = field(request.candidateSetId);
  canonical += field(request.candidateSetId); // Java idempotencyKey is candidateSetId.toString().
  canonical += field(request.threadId);
  canonical += field(request.scopeRef);
  canonical += numberField(request.setVersion);
  canonical += field(request.finalConfirmation.decision);
  canonical += numberField(request.finalConfirmation.confirmedSetVersion);

  canonical += numberField(request.evidencePool.messages.length);
  for (const message of request.evidencePool.messages) {
    canonical += field(message.sourceUnitId);
    canonical += field(message.actorId);
    canonical += numberField(message.ordinal);
    canonical += field(message.externalUnitRef);
    canonical += field(message.occurredAt);
    canonical += field(message.bodyText);
    canonical += field(message.bodyHash);
  }
  canonical += numberField(request.evidencePool.anchors.length);
  for (const anchor of request.evidencePool.anchors) {
    canonical += field(anchor.anchorId);
    canonical += numberField(anchor.units.length);
    for (const unit of anchor.units) {
      canonical += field(unit.sourceUnitId);
      canonical += nullableNumberField(unit.fromOffset);
      canonical += nullableNumberField(unit.toOffset);
      canonical += numberField(unit.ordinal);
    }
  }

  canonical += numberField(request.candidates.length);
  for (const candidate of request.candidates) {
    canonical += field(candidate.candidateId);
    canonical += numberField(candidate.ordinal);
    canonical += field(candidate.disposition);
    canonical += field(candidate.action);
    canonical += field(candidate.originKind);
    canonical += field(candidate.finalAuthorKind);
    canonical += nullableField(candidate.memoryText);
    canonical += nullableField(candidate.memoryType);
    canonical += field(candidate.perspectiveActorId);
    canonical += numberField(candidate.evidenceAnchorIds.length);
    for (const anchorId of candidate.evidenceAnchorIds) canonical += field(anchorId);
    canonical += nullableField(candidate.targetMemoryId);
    canonical += nullableField(candidate.expectedMemoryRevisionId);
    canonical += nullableNumberField(candidate.expectedRevisionNo);
    canonical += nullableNumberField(candidate.expectedPolicyRevisionNo);
    canonical += nullableField(candidate.hideReason);
  }
  return sha256Hex(canonical);
}

function deepFreeze<T>(value: T): T {
  if (typeof value !== "object" || value === null || Object.isFrozen(value)) return value;
  for (const child of Object.values(value as Record<string, unknown>)) deepFreeze(child);
  return Object.freeze(value);
}

/** Validated input -> deterministic, defensively detached and closed CandidateSet wire request. */
export function buildCandidateSetCloseoutRequest(
  input: CandidateSetCloseoutInput,
): CandidateSetCloseoutRequest {
  const candidateSetId = deriveCandidateSetId(input.candidateSetKey);
  const threadId = deriveThreadId(input.threadKey);

  const messages = input.evidenceSegments.flatMap((segment) =>
    segment.messages.map((message) => ({
      sourceUnitId: deriveCandidateSetSourceUnitId(candidateSetId, message.ordinal),
      actorId: deriveActorId(input.threadKey, message.speakerKey),
      ordinal: message.ordinal,
      externalUnitRef: externalUnitRef(input.threadKey, message.ordinal),
      occurredAt: normalizeOccurredAt(message.occurredAt),
      bodyText: message.bodyText,
      bodyHash: bodyHash(message.bodyText),
    })),
  );

  const anchors = input.evidenceSegments.map((segment) => ({
    anchorId: deriveCandidateSetAnchorId(candidateSetId, segment.messages[0].ordinal),
    units: segment.messages.map((message, index) => ({
      sourceUnitId: deriveCandidateSetSourceUnitId(candidateSetId, message.ordinal),
      fromOffset: 0,
      toOffset: codePointCount(message.bodyText),
      ordinal: index + 1,
    })),
  }));

  const candidates = input.candidates.map((candidate, index) => ({
    candidateId: deriveCandidateId(candidateSetId, candidate.candidateKey, index + 1),
    ordinal: index + 1,
    disposition: candidate.disposition,
    action: candidate.action,
    originKind: candidate.originKind,
    finalAuthorKind: candidate.finalAuthorKind,
    memoryText: candidate.memoryText,
    memoryType: candidate.memoryType === null ? null : CANONICAL_MEMORY_TYPE[candidate.memoryType],
    perspectiveActorId: deriveActorId(input.threadKey, candidate.perspectiveSpeakerKey),
    evidenceAnchorIds: candidate.evidenceSegmentIndexes.map((segmentIndex) =>
      anchors[segmentIndex - 1].anchorId,
    ),
    targetMemoryId: candidate.targetMemoryId,
    expectedMemoryRevisionId: candidate.expectedMemoryRevisionId,
    expectedRevisionNo: candidate.expectedRevisionNo,
    expectedPolicyRevisionNo: candidate.expectedPolicyRevisionNo,
    hideReason: candidate.hideReason,
  }));

  const request: CandidateSetCloseoutRequest = {
    candidateSetId,
    requestHash: "",
    threadId,
    scopeRef: input.scopeRef,
    setVersion: input.setVersion,
    finalConfirmation: {
      decision: "CONFIRM_SET",
      confirmedSetVersion: input.setVersion,
      confirmationHash: "",
    },
    evidencePool: { messages, anchors },
    candidates,
  };
  request.finalConfirmation.confirmationHash = candidateSetConfirmationHash(request);
  request.requestHash = candidateSetRequestHash(request);
  return deepFreeze(request);
}
