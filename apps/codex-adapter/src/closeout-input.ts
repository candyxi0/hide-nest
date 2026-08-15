import {
  MEMORY_TYPES,
  codePointCount,
  normalizeOccurredAt,
  type CloseoutInput,
  type EvidenceMessageInput,
  type EvidenceSegmentInput,
  type MemoryType,
} from "./closeout-canonicalizer.js";

/**
 * Frozen structural + business validation for the single synthetic closeout tool input.
 *
 * Every rejection happens before any HTTP request is made. Error messages are sanitized: they name
 * the violated rule but never echo bodyText, speakerKey, threadKey, closeoutKey or any secret.
 */

export const MAX_CLOSEOUT_KEY = 128;
export const MAX_THREAD_KEY = 128;
export const MAX_SPEAKER_KEY = 64;
export const MAX_BODY_CODE_POINTS = 16000;
export const MAX_EVIDENCE_MESSAGES = 100;
export const MAX_EVIDENCE_SEGMENTS = 100;
export const MAX_EVIDENCE_BYTES = 1024 * 1024; // 1 MiB UTF-8

export class CloseoutInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CloseoutInputError";
  }
}

function hasControlChar(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if ((c >= 0x00 && c <= 0x1f) || c === 0x7f || (c >= 0x80 && c <= 0x9f)) {
      return true;
    }
  }
  return false;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function assertOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): void {
  const allowedSet = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      throw new CloseoutInputError("input contains an unknown field");
    }
  }
}

function assertKeyString(value: unknown, maxLength: number, label: string): asserts value is string {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw new CloseoutInputError(`invalid ${label} length`);
  }
  if (hasControlChar(value)) {
    throw new CloseoutInputError(`${label} contains a control character`);
  }
}

function assertNonNegativeInteger(value: unknown): asserts value is number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    throw new CloseoutInputError("ordinal must be a non-negative integer");
  }
}

function assertMemoryType(value: unknown): asserts value is MemoryType {
  if (typeof value !== "string" || !(MEMORY_TYPES as readonly string[]).includes(value)) {
    throw new CloseoutInputError("invalid memoryType");
  }
}

function validateCandidate(candidate: unknown): CloseoutInput["candidate"] {
  if (!isPlainObject(candidate)) {
    throw new CloseoutInputError("candidate must be an object");
  }
  assertOnlyKeys(candidate, ["perspectiveSpeakerKey", "memoryType", "bodyText"]);

  assertKeyString(candidate.perspectiveSpeakerKey, MAX_SPEAKER_KEY, "perspectiveSpeakerKey");
  assertMemoryType(candidate.memoryType);
  if (
    typeof candidate.bodyText !== "string" ||
    candidate.bodyText.length === 0 ||
    codePointCount(candidate.bodyText) > MAX_BODY_CODE_POINTS
  ) {
    throw new CloseoutInputError("invalid candidate bodyText");
  }

  return {
    perspectiveSpeakerKey: candidate.perspectiveSpeakerKey,
    memoryType: candidate.memoryType,
    bodyText: candidate.bodyText,
  };
}

function validateEvidenceMessage(message: unknown): EvidenceMessageInput {
  if (!isPlainObject(message)) {
    throw new CloseoutInputError("evidence message must be an object");
  }
  assertOnlyKeys(message, ["speakerKey", "ordinal", "occurredAt", "bodyText"]);

  assertKeyString(message.speakerKey, MAX_SPEAKER_KEY, "speakerKey");
  assertNonNegativeInteger(message.ordinal);
  if (typeof message.occurredAt !== "string") {
    throw new CloseoutInputError("occurredAt must be a string");
  }
  try {
    normalizeOccurredAt(message.occurredAt);
  } catch {
    throw new CloseoutInputError("occurredAt must be an RFC3339 date-time with offset");
  }
  if (
    typeof message.bodyText !== "string" ||
    message.bodyText.length === 0 ||
    Buffer.byteLength(message.bodyText, "utf8") > MAX_EVIDENCE_BYTES
  ) {
    throw new CloseoutInputError("invalid evidence bodyText");
  }

  return {
    speakerKey: message.speakerKey,
    ordinal: message.ordinal,
    occurredAt: message.occurredAt,
    bodyText: message.bodyText,
  };
}

function validateEvidenceSegment(segment: unknown): EvidenceSegmentInput {
  if (!isPlainObject(segment)) {
    throw new CloseoutInputError("evidence segment must be an object");
  }
  assertOnlyKeys(segment, ["messages"]);
  if (!Array.isArray(segment.messages)) {
    throw new CloseoutInputError("evidence segment messages must be an array");
  }
  if (segment.messages.length < 1) {
    throw new CloseoutInputError("evidence segment must contain at least one message");
  }
  const messages = segment.messages.map(validateEvidenceMessage);

  // Within a segment ordinals are strictly ascending, unique and contiguous.
  for (let i = 1; i < messages.length; i++) {
    if (messages[i].ordinal !== messages[i - 1].ordinal + 1) {
      throw new CloseoutInputError("evidence segment ordinals must be strictly ascending and continuous");
    }
  }
  return { messages };
}

/**
 * Validates raw tool arguments and returns a typed, closed input.
 * Throws {@link CloseoutInputError} with a sanitized message on any violation.
 */
export function validateCloseoutInput(raw: unknown): CloseoutInput {
  if (!isPlainObject(raw)) {
    throw new CloseoutInputError("input must be an object");
  }
  assertOnlyKeys(raw, ["closeoutKey", "threadKey", "userConfirmed", "candidate", "evidenceSegments"]);

  assertKeyString(raw.closeoutKey, MAX_CLOSEOUT_KEY, "closeoutKey");
  assertKeyString(raw.threadKey, MAX_THREAD_KEY, "threadKey");

  if (raw.userConfirmed !== true) {
    throw new CloseoutInputError("userConfirmed must be true");
  }

  const candidate = validateCandidate(raw.candidate);

  if (!Array.isArray(raw.evidenceSegments)) {
    throw new CloseoutInputError("evidenceSegments must be an array");
  }
  if (raw.evidenceSegments.length < 1 || raw.evidenceSegments.length > MAX_EVIDENCE_SEGMENTS) {
    throw new CloseoutInputError("invalid evidenceSegments length");
  }

  const evidenceSegments = raw.evidenceSegments.map(validateEvidenceSegment);

  // Total message count across all segments is bounded the same way the old flat list was.
  let totalMessages = 0;
  for (const segment of evidenceSegments) {
    totalMessages += segment.messages.length;
  }
  if (totalMessages < 1 || totalMessages > MAX_EVIDENCE_MESSAGES) {
    throw new CloseoutInputError("invalid total evidence message count");
  }

  // Segments must be strictly ascending and non-overlapping, and a cross-segment boundary must
  // contain at least one ordinal gap: two runs that are exactly adjacent are one contiguous segment
  // and must be merged rather than split.
  for (let i = 1; i < evidenceSegments.length; i++) {
    const previous = evidenceSegments[i - 1];
    const current = evidenceSegments[i];
    const previousLast = previous.messages[previous.messages.length - 1].ordinal;
    const currentFirst = current.messages[0].ordinal;
    if (currentFirst <= previousLast) {
      throw new CloseoutInputError("evidenceSegments must be strictly ascending and non-overlapping");
    }
    if (currentFirst === previousLast + 1) {
      throw new CloseoutInputError("adjacent evidenceSegments must be merged into one contiguous segment");
    }
  }

  // The perspective speaker must appear in at least one evidence message across all segments.
  const perspectivePresent = evidenceSegments.some((segment) =>
    segment.messages.some((msg) => msg.speakerKey === candidate.perspectiveSpeakerKey),
  );
  if (!perspectivePresent) {
    throw new CloseoutInputError("perspectiveSpeakerKey must appear in at least one evidence message");
  }

  return {
    closeoutKey: raw.closeoutKey,
    threadKey: raw.threadKey,
    userConfirmed: true,
    candidate,
    evidenceSegments,
  };
}
