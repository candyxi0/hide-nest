import {
  CANDIDATE_ACTIONS,
  CANDIDATE_DISPOSITIONS,
  CANDIDATE_ORIGIN_KINDS,
  FINAL_AUTHOR_KINDS,
  SPEAKER_ROLES,
  type CandidateAction,
  type CandidateDisposition,
  type CandidateOriginKind,
  type CandidateSetCandidateInput,
  type CandidateSetCloseoutInput,
  type CandidateSetEvidenceMessageInput,
  type CandidateSetEvidenceSegmentInput,
  type FinalAuthorKind,
  type SpeakerRole,
} from "./candidate-set-closeout-canonicalizer.js";
import {
  MEMORY_TYPES,
  normalizeOccurredAt,
  type MemoryType,
} from "./closeout-canonicalizer.js";

const TOP_LEVEL_FIELDS = [
  "candidateSetKey",
  "threadKey",
  "scopeRef",
  "setVersion",
  "userConfirmed",
  "evidenceSegments",
  "candidates",
] as const;
const SEGMENT_FIELDS = ["messages"] as const;
const MESSAGE_FIELDS = ["speakerKey", "speakerRole", "ordinal", "occurredAt", "bodyText"] as const;
const CANDIDATE_FIELDS = [
  "candidateKey",
  "disposition",
  "action",
  "originKind",
  "finalAuthorKind",
  "perspectiveSpeakerKey",
  "memoryText",
  "memoryType",
  "evidenceSegmentIndexes",
  "targetMemoryId",
  "expectedMemoryRevisionId",
  "expectedRevisionNo",
  "expectedPolicyRevisionNo",
  "hideReason",
] as const;

export const MAX_EVIDENCE_BYTES = 1024 * 1024;
export const MAX_MEMORY_TEXT_LENGTH = 16_000;

export class CandidateSetInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CandidateSetInputError";
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function assertExactKeys(value: Record<string, unknown>, fields: readonly string[]): void {
  const keys = Object.keys(value);
  const allowed = new Set(fields);
  if (keys.length !== fields.length || keys.some((key) => !allowed.has(key))) {
    throw new CandidateSetInputError("input fields must match the closed schema");
  }
  for (const fieldName of fields) {
    if (!Object.prototype.hasOwnProperty.call(value, fieldName)) {
      throw new CandidateSetInputError("input fields must match the closed schema");
    }
  }
}

function hasControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if ((code <= 0x1f) || code === 0x7f || (code >= 0x80 && code <= 0x9f)) return true;
  }
  return false;
}

/** Text evidence may preserve normal chat formatting, but never transport/database-unsafe controls. */
function hasUnsafeTextControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (
      ((code <= 0x1f && code !== 0x09 && code !== 0x0a && code !== 0x0d) ||
        code === 0x7f ||
        (code >= 0x80 && code <= 0x9f))
    ) {
      return true;
    }
  }
  return false;
}

function hasUnpairedSurrogate(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true;
      index++;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      return true;
    }
  }
  return false;
}

function stringWithin(
  value: unknown,
  min: number,
  max: number,
  label: string,
  rejectControl = true,
): string {
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  if (hasUnpairedSurrogate(value) || (rejectControl && hasControlCharacter(value))) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  return value;
}

function textWithin(value: unknown, min: number, max: number, label: string): string {
  if (typeof value !== "string" || value.length < min || value.length > max) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  if (hasUnpairedSurrogate(value) || hasUnsafeTextControlCharacter(value)) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  return value;
}

function safeInteger(value: unknown, minimum: number, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < minimum) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], label: string): T {
  if (typeof value !== "string" || !allowed.includes(value as T)) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  return value as T;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function nullableUuid(value: unknown, label: string): string | null {
  if (value === null) return null;
  if (typeof value !== "string" || !UUID_RE.test(value)) {
    throw new CandidateSetInputError(`invalid ${label}`);
  }
  return value.toLowerCase();
}

function nullablePositiveInteger(value: unknown, label: string): number | null {
  return value === null ? null : safeInteger(value, 1, label);
}

function validateMessage(value: unknown): CandidateSetEvidenceMessageInput {
  if (!isPlainObject(value)) throw new CandidateSetInputError("evidence message must be an object");
  assertExactKeys(value, MESSAGE_FIELDS);
  const speakerKey = stringWithin(value.speakerKey, 1, 64, "speakerKey");
  const speakerRole = oneOf<SpeakerRole>(value.speakerRole, SPEAKER_ROLES, "speakerRole");
  const ordinal = safeInteger(value.ordinal, 0, "ordinal");
  if (typeof value.occurredAt !== "string") {
    throw new CandidateSetInputError("invalid occurredAt");
  }
  try {
    normalizeOccurredAt(value.occurredAt);
  } catch {
    throw new CandidateSetInputError("invalid occurredAt");
  }
  if (
    typeof value.bodyText !== "string" ||
    value.bodyText.trim().length === 0 ||
    hasUnsafeTextControlCharacter(value.bodyText) ||
    hasUnpairedSurrogate(value.bodyText) ||
    Buffer.byteLength(value.bodyText, "utf8") > MAX_EVIDENCE_BYTES
  ) {
    throw new CandidateSetInputError("invalid evidence bodyText");
  }
  return { speakerKey, speakerRole, ordinal, occurredAt: value.occurredAt, bodyText: value.bodyText };
}

function validateSegment(value: unknown): CandidateSetEvidenceSegmentInput {
  if (!isPlainObject(value)) throw new CandidateSetInputError("evidence segment must be an object");
  assertExactKeys(value, SEGMENT_FIELDS);
  if (!Array.isArray(value.messages) || value.messages.length < 1 || value.messages.length > 100) {
    throw new CandidateSetInputError("invalid evidence segment messages");
  }
  const messages = value.messages.map(validateMessage);
  for (let index = 1; index < messages.length; index++) {
    if (messages[index].ordinal !== messages[index - 1].ordinal + 1) {
      throw new CandidateSetInputError("segment ordinals must be ascending, unique and continuous");
    }
  }
  return { messages };
}

function validateCandidate(
  value: unknown,
  evidenceSegments: CandidateSetEvidenceSegmentInput[],
): CandidateSetCandidateInput {
  if (!isPlainObject(value)) throw new CandidateSetInputError("candidate must be an object");
  assertExactKeys(value, CANDIDATE_FIELDS);

  const candidateKey = stringWithin(value.candidateKey, 1, 128, "candidateKey");
  const disposition = oneOf<CandidateDisposition>(
    value.disposition,
    CANDIDATE_DISPOSITIONS,
    "disposition",
  );
  const action = oneOf<CandidateAction>(value.action, CANDIDATE_ACTIONS, "action");
  const originKind = oneOf<CandidateOriginKind>(
    value.originKind,
    CANDIDATE_ORIGIN_KINDS,
    "originKind",
  );
  const finalAuthorKind = oneOf<FinalAuthorKind>(
    value.finalAuthorKind,
    FINAL_AUTHOR_KINDS,
    "finalAuthorKind",
  );
  const perspectiveSpeakerKey = stringWithin(
    value.perspectiveSpeakerKey,
    1,
    64,
    "perspectiveSpeakerKey",
  );

  let memoryText: string | null;
  if (value.memoryText === null) {
    memoryText = null;
  } else {
    memoryText = textWithin(value.memoryText, 0, MAX_MEMORY_TEXT_LENGTH, "memoryText");
  }
  const memoryType =
    value.memoryType === null
      ? null
      : oneOf<MemoryType>(value.memoryType, MEMORY_TYPES, "memoryType");

  if (!Array.isArray(value.evidenceSegmentIndexes) || value.evidenceSegmentIndexes.length > 100) {
    throw new CandidateSetInputError("invalid evidenceSegmentIndexes");
  }
  const evidenceSegmentIndexes = value.evidenceSegmentIndexes.map((index) =>
    safeInteger(index, 1, "evidenceSegmentIndex"),
  );
  for (let index = 0; index < evidenceSegmentIndexes.length; index++) {
    const segmentIndex = evidenceSegmentIndexes[index];
    if (
      segmentIndex > evidenceSegments.length ||
      (index > 0 && segmentIndex <= evidenceSegmentIndexes[index - 1])
    ) {
      throw new CandidateSetInputError("evidenceSegmentIndexes must be ascending, unique and in range");
    }
  }

  const targetMemoryId = nullableUuid(value.targetMemoryId, "targetMemoryId");
  const expectedMemoryRevisionId = nullableUuid(
    value.expectedMemoryRevisionId,
    "expectedMemoryRevisionId",
  );
  const expectedRevisionNo = nullablePositiveInteger(value.expectedRevisionNo, "expectedRevisionNo");
  const expectedPolicyRevisionNo = nullablePositiveInteger(
    value.expectedPolicyRevisionNo,
    "expectedPolicyRevisionNo",
  );
  const hideReason =
    value.hideReason === null
      ? null
      : stringWithin(value.hideReason, 0, 1000, "hideReason", false);

  if ((originKind === "USER_EDITED" || originKind === "USER_ADDED") && finalAuthorKind !== "USER") {
    throw new CandidateSetInputError("user-origin candidate must have USER final author");
  }
  if (disposition === "REJECTED" && evidenceSegmentIndexes.length !== 0) {
    throw new CandidateSetInputError("rejected candidate must not reference evidence");
  }
  const perspectiveInPool = evidenceSegments.some((segment) =>
    segment.messages.some((message) => message.speakerKey === perspectiveSpeakerKey),
  );
  if (!perspectiveInPool) {
    throw new CandidateSetInputError("perspective speaker must occur in the evidence pool");
  }
  if (disposition === "ACCEPTED") {
    if (memoryText === null || memoryText.trim().length === 0 || memoryType === null) {
      throw new CandidateSetInputError("accepted candidate requires memoryText and memoryType");
    }
    if (evidenceSegmentIndexes.length === 0) {
      throw new CandidateSetInputError("accepted candidate requires evidence");
    }
    const perspectivePresent = evidenceSegmentIndexes.some((segmentIndex) =>
      evidenceSegments[segmentIndex - 1].messages.some(
        (message) => message.speakerKey === perspectiveSpeakerKey,
      ),
    );
    if (!perspectivePresent) {
      throw new CandidateSetInputError("perspective speaker must occur in the candidate evidence");
    }
  }

  const hasAnyTarget =
    targetMemoryId !== null ||
    expectedMemoryRevisionId !== null ||
    expectedRevisionNo !== null ||
    expectedPolicyRevisionNo !== null;
  const hasEveryTarget =
    targetMemoryId !== null &&
    expectedMemoryRevisionId !== null &&
    expectedRevisionNo !== null &&
    expectedPolicyRevisionNo !== null;
  if ((action === "CREATE" && hasAnyTarget) || (action !== "CREATE" && !hasEveryTarget)) {
    throw new CandidateSetInputError("candidate action target matrix is invalid");
  }

  return {
    candidateKey,
    disposition,
    action,
    originKind,
    finalAuthorKind,
    perspectiveSpeakerKey,
    memoryText,
    memoryType,
    evidenceSegmentIndexes,
    targetMemoryId,
    expectedMemoryRevisionId,
    expectedRevisionNo,
    expectedPolicyRevisionNo,
    hideReason,
  };
}

/** Closed structural and business validation. Every failure occurs before configuration/HTTP. */
export function validateCandidateSetCloseoutInput(raw: unknown): CandidateSetCloseoutInput {
  if (!isPlainObject(raw)) throw new CandidateSetInputError("input must be an object");
  assertExactKeys(raw, TOP_LEVEL_FIELDS);

  const candidateSetKey = stringWithin(raw.candidateSetKey, 1, 128, "candidateSetKey");
  const threadKey = stringWithin(raw.threadKey, 1, 128, "threadKey");
  const scopeRef = stringWithin(raw.scopeRef, 1, 256, "scopeRef");
  const setVersion = safeInteger(raw.setVersion, 1, "setVersion");
  if (raw.userConfirmed !== true) {
    throw new CandidateSetInputError("userConfirmed must be true");
  }
  if (!Array.isArray(raw.evidenceSegments) || raw.evidenceSegments.length < 1 || raw.evidenceSegments.length > 100) {
    throw new CandidateSetInputError("invalid evidenceSegments");
  }
  const evidenceSegments = raw.evidenceSegments.map(validateSegment);

  // A single speakerKey must map to a single speakerRole across the whole request.
  const speakerRoleByKey = new Map<string, SpeakerRole>();
  for (const segment of evidenceSegments) {
    for (const message of segment.messages) {
      const prior = speakerRoleByKey.get(message.speakerKey);
      if (prior !== undefined && prior !== message.speakerRole) {
        throw new CandidateSetInputError("speakerKey must map to a single speakerRole");
      }
      speakerRoleByKey.set(message.speakerKey, message.speakerRole);
    }
  }

  let messageCount = 0;
  let byteCount = 0;
  for (const segment of evidenceSegments) {
    messageCount += segment.messages.length;
    for (const message of segment.messages) byteCount += Buffer.byteLength(message.bodyText, "utf8");
  }
  if (messageCount < 1 || messageCount > 100 || byteCount > MAX_EVIDENCE_BYTES) {
    throw new CandidateSetInputError("evidence pool limit exceeded");
  }
  for (let index = 1; index < evidenceSegments.length; index++) {
    const previous = evidenceSegments[index - 1].messages;
    const current = evidenceSegments[index].messages;
    const previousLast = previous[previous.length - 1].ordinal;
    const currentFirst = current[0].ordinal;
    if (currentFirst <= previousLast || currentFirst === previousLast + 1) {
      throw new CandidateSetInputError("segments must ascend without overlap or adjacent fake splits");
    }
  }

  if (!Array.isArray(raw.candidates) || raw.candidates.length > 8) {
    throw new CandidateSetInputError("invalid candidates");
  }
  const candidates = raw.candidates.map((candidate) => validateCandidate(candidate, evidenceSegments));
  const candidateKeys = new Set<string>();
  for (const candidate of candidates) {
    if (candidateKeys.has(candidate.candidateKey)) {
      throw new CandidateSetInputError("candidateKey must be unique within the set");
    }
    candidateKeys.add(candidate.candidateKey);
  }

  return {
    candidateSetKey,
    threadKey,
    scopeRef,
    setVersion,
    userConfirmed: true,
    evidenceSegments,
    candidates,
  };
}
