import { createHash } from "node:crypto";

/**
 * Single frozen deterministic mapper + canonical hasher for the Local V1 synthetic closeout tool.
 *
 * This module is the only place that derives identity (UUIDs) and canonical hashes
 * (bodyHash, thread manifest hash, review manifest hash, confirmation proof). Production code and
 * tests must both call these functions — no second hand-written algorithm is allowed.
 *
 * The hash encoding mirrors the Java `LocalV1CloseoutCanonicalizer` byte-for-byte:
 * every field is encoded as `UTF8(byteLength) + ':' + UTF8(value)`; null is `-1:`; booleans are
 * `true`/`false`; UUIDs are lowercase-hyphenated text; times are UTC ISO-8601
 * (`OffsetDateTime.toInstant().toString()`); integers are decimal text; arrays write their element
 * count first, then each element in frozen request order.
 */

// ── public input/output types ────────────────────────────────────────────

export const MEMORY_TYPES = [
  "EVENT",
  "CLAIM",
  "QUOTE",
  "INTERPRETATION",
  "CALIBRATION",
  "PRINCIPLE",
] as const;
export type MemoryType = (typeof MEMORY_TYPES)[number];

export const SCHEMA_VERSION = "local-v1-synthetic-v1";
export const CONFIRM_DECISION = "CONFIRM";

/** The fixed confirmation tag used to derive confirmationSourceUnitId. Never mixed into evidence. */
export const CONFIRMATION_LABEL = "confirmation";

export interface EvidenceMessageInput {
  speakerKey: string;
  ordinal: number;
  occurredAt: string; // RFC3339 date-time with offset
  bodyText: string;
}

/** One explicit evidence segment: a contiguous run of messages that maps to a single source anchor. */
export interface EvidenceSegmentInput {
  messages: EvidenceMessageInput[];
}

export interface CloseoutInput {
  closeoutKey: string;
  threadKey: string;
  userConfirmed: true;
  candidate: {
    perspectiveSpeakerKey: string;
    memoryType: MemoryType;
    bodyText: string;
  };
  evidenceSegments: EvidenceSegmentInput[];
}

/** The wire request shape for `POST /v1/closeout-submissions` (CloseoutSubmissionRequest). */
export interface CloseoutRequest {
  submissionId: string;
  threadId: string;
  hideSelection: {
    perspectiveActorId: string;
    memoryType: MemoryType;
    bodyText: string;
    bodyHash: string;
  };
  userConfirmation: {
    decision: typeof CONFIRM_DECISION;
    reviewManifestHash: string;
    confirmationSourceUnitId: string;
  };
  sourceAnchors: Array<{
    anchorId: string;
    units: Array<{
      sourceUnitId: string;
      fromOffset: number;
      toOffset: number;
      ordinal: number;
    }>;
  }>;
  threadReaderManifest: {
    schemaVersion: typeof SCHEMA_VERSION;
    fromOrdinal: number;
    toOrdinal: number;
    continuous: boolean;
    manifestHash: string;
    selectedEvidenceMessages: Array<{
      sourceUnitId: string;
      actorId: string;
      ordinal: number;
      externalUnitRef: string;
      occurredAt: string;
      bodyText: string;
      bodyHash: string;
    }>;
  };
  confirmationProof: string;
}

// ── sha256 / encoding primitives ─────────────────────────────────────────

export function sha256Hex(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

/** Frozen length-prefixed field: `UTF8(byteLength) + ':' + UTF8(value)`. */
export function field(value: string): string {
  return Buffer.byteLength(value, "utf8") + ":" + value;
}

/** Decimal integer as a length-prefixed field. */
export function numberField(value: number): string {
  return field(String(value));
}

/** Nullable decimal integer: null -> `-1:`. */
export function nullableNumberField(value: number | null): string {
  return value === null ? "-1:" : numberField(value);
}

// ── deterministic identity (UUID v3 / nameUUIDFromBytes) ────────────────

/**
 * Mirrors Java `UUID.nameUUIDFromBytes` exactly: MD5 digest, version bits set to 3, variant bits
 * set to IETF (10), rendered as lowercase hyphenated hex.
 */
export function nameUuidFromBytes(bytes: Buffer): string {
  const md5 = createHash("md5").update(bytes).digest();
  md5[6] = (md5[6] & 0x0f) | 0x30; // version 3
  md5[8] = (md5[8] & 0x3f) | 0x80; // IETF variant
  const hex = md5.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join("-");
}

function deriveUuid(parts: readonly (string | number)[]): string {
  // Length-prefixed (UTF-8 byte length) encoding makes every component unambiguous, so dynamic
  // strings cannot collide across a separator boundary (e.g. ("a:b","c") vs ("a","b:c")).
  const name = parts.map((p) => field(String(p))).join("");
  return nameUuidFromBytes(Buffer.from(name, "utf8"));
}

export function deriveSubmissionId(closeoutKey: string): string {
  return deriveUuid(["submission", closeoutKey]);
}

export function deriveThreadId(threadKey: string): string {
  return deriveUuid(["thread", threadKey]);
}

export function deriveActorId(threadKey: string, speakerKey: string): string {
  return deriveUuid(["actor", threadKey, speakerKey]);
}

export function deriveSourceUnitId(closeoutKey: string, ordinal: number): string {
  return deriveUuid(["source", closeoutKey, ordinal]);
}

export function deriveAnchorId(closeoutKey: string, ordinal: number): string {
  return deriveUuid(["anchor", closeoutKey, ordinal]);
}

export function deriveConfirmationSourceUnitId(closeoutKey: string): string {
  return deriveUuid([CONFIRMATION_LABEL, closeoutKey]);
}

/** Matches the backend facade: `memoryId = UUID.nameUUIDFromBytes("memory:" + submissionId)`. */
export function deriveMemoryId(submissionId: string): string {
  return nameUuidFromBytes(Buffer.from("memory:" + submissionId, "utf8"));
}

// ── external unit ref (short thread-key hash + ordinal, no plaintext key) ─

export function externalUnitRef(threadKey: string, ordinal: number): string {
  const short = sha256Hex(threadKey).slice(0, 12);
  return `${short}-${ordinal}`;
}

// ── RFC3339 with offset → UTC instant (Java Instant.toString() format) ────

const RFC3339_OFFSET_RE =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?([Zz]|([+-])(\d{2}):(\d{2}))$/;

function daysFromCivil(year: number, month: number, day: number): number {
  let y = year;
  if (month <= 2) y -= 1;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (month + (month > 2 ? -3 : 9)) + 2) / 5) + day - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/**
 * Normalizes an RFC3339 date-time with offset into the exact UTC instant string that Java's
 * `OffsetDateTime.toInstant().toString()` would produce (e.g. `2026-08-13T04:34:56.123Z`).
 * Throws on malformed input. Fractional seconds are preserved up to nanosecond precision.
 */
export function normalizeOccurredAt(value: string): string {
  const m = RFC3339_OFFSET_RE.exec(value);
  if (m === null) {
    throw new Error("occurredAt is not an RFC3339 date-time with offset");
  }
  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  const hour = Number(m[4]);
  const minute = Number(m[5]);
  const second = Number(m[6]);
  const fracDigits = m[7] ?? "";
  const offsetSign = m[9];
  const offsetHour = Number(m[10] ?? 0);
  const offsetMinute = Number(m[11] ?? 0);

  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > daysInMonth(year, month) ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    offsetHour > 23 ||
    offsetMinute > 59
  ) {
    throw new Error("occurredAt has an out-of-range component");
  }

  const days = daysFromCivil(year, month, day);
  const localEpochSeconds =
    days * 86400 + hour * 3600 + minute * 60 + second;
  const offsetSeconds =
    (offsetSign === "-" ? -1 : 1) * (offsetHour * 3600 + offsetMinute * 60);
  const utcEpochSeconds = localEpochSeconds - offsetSeconds;

  const nanos = fracDigits === "" ? 0 : Number(fracDigits.padEnd(9, "0"));

  const d = new Date(utcEpochSeconds * 1000);
  const yyyy = String(d.getUTCFullYear()).padStart(4, "0");
  const MM = String(d.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(d.getUTCDate()).padStart(2, "0");
  const HH = String(d.getUTCHours()).padStart(2, "0");
  const mm = String(d.getUTCMinutes()).padStart(2, "0");
  const ss = String(d.getUTCSeconds()).padStart(2, "0");
  let fraction = "";
  if (nanos > 0) {
    fraction = "." + String(nanos).padStart(9, "0").replace(/0+$/, "");
  }
  return `${yyyy}-${MM}-${dd}T${HH}:${mm}:${ss}${fraction}Z`;
}

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function daysInMonth(year: number, month: number): number {
  switch (month) {
    case 1:
    case 3:
    case 5:
    case 7:
    case 8:
    case 10:
    case 12:
      return 31;
    case 4:
    case 6:
    case 9:
    case 11:
      return 30;
    case 2:
      return isLeapYear(year) ? 29 : 28;
    default:
      return 0;
  }
}

// ── body + manifest hashing ──────────────────────────────────────────────

export function bodyHash(bodyText: string): string {
  return sha256Hex(bodyText);
}

export interface ManifestMessage {
  sourceUnitId: string;
  actorId: string;
  ordinal: number;
  externalUnitRef: string;
  occurredAt: string; // already normalized to UTC instant string
  bodyHash: string;
}

export interface ThreadManifest {
  schemaVersion: string;
  fromOrdinal: number;
  toOrdinal: number;
  continuous: boolean;
  selectedEvidenceMessages: ManifestMessage[];
}

/** Thread-reader manifest hash. Must match Java `threadManifestHash` byte-for-byte. */
export function threadManifestHash(manifest: ThreadManifest): string {
  let sb = "";
  sb += field(manifest.schemaVersion);
  sb += numberField(manifest.fromOrdinal);
  sb += numberField(manifest.toOrdinal);
  sb += field(manifest.continuous ? "true" : "false");
  sb += numberField(manifest.selectedEvidenceMessages.length);
  for (const message of manifest.selectedEvidenceMessages) {
    sb += field(message.sourceUnitId);
    sb += field(message.actorId);
    sb += numberField(message.ordinal);
    sb += field(message.externalUnitRef);
    sb += field(message.occurredAt);
    sb += field(message.bodyHash);
  }
  return sha256Hex(sb);
}

export interface SourceAnchorInput {
  anchorId: string;
  units: Array<{
    sourceUnitId: string;
    fromOffset: number | null;
    toOffset: number | null;
    ordinal: number;
  }>;
}

export interface ReviewManifestRequest {
  submissionId: string;
  threadId: string;
  hideSelection: {
    perspectiveActorId: string;
    memoryType: string;
    bodyHash: string;
  };
  threadManifestHash: string;
  sourceAnchors: SourceAnchorInput[];
  userConfirmation: {
    decision: string;
  };
}

/** Review manifest hash. Must match Java `reviewManifestHash` byte-for-byte. */
export function reviewManifestHash(request: ReviewManifestRequest): string {
  let sb = "";
  sb += field(request.submissionId);
  sb += field(request.threadId);
  sb += field(request.hideSelection.perspectiveActorId);
  sb += field(request.hideSelection.memoryType);
  sb += field(request.hideSelection.bodyHash);
  sb += field(request.threadManifestHash);
  sb += numberField(request.sourceAnchors.length);
  for (const anchor of request.sourceAnchors) {
    sb += field(anchor.anchorId);
    sb += numberField(anchor.units.length);
    for (const unit of anchor.units) {
      sb += field(unit.sourceUnitId);
      sb += nullableNumberField(unit.fromOffset);
      sb += nullableNumberField(unit.toOffset);
      sb += numberField(unit.ordinal);
    }
  }
  sb += field(request.userConfirmation.decision);
  return sha256Hex(sb);
}

/** Frozen confirmation proof formula (Task31A). */
export function confirmationProof(
  threadId: string,
  confirmationSourceUnitId: string,
  reviewManifestHashValue: string,
  submissionId: string,
): string {
  return sha256Hex(
    threadId + "\n" + confirmationSourceUnitId + "\n" + reviewManifestHashValue + "\n" + submissionId,
  );
}

// ── frozen mapper: validated input → closed wire request ─────────────────

/** Number of Unicode code points in a JS string (not UTF-16 code units). */
export function codePointCount(value: string): number {
  return [...value].length;
}

export function buildCloseoutRequest(input: CloseoutInput): CloseoutRequest {
  const submissionId = deriveSubmissionId(input.closeoutKey);
  const threadId = deriveThreadId(input.threadKey);

  // Flatten every segment in input order. Validation guarantees segments are ordinal-ascending and
  // internally contiguous, so messages[0] is the global minimum and messages[last] the global maximum.
  const messages = input.evidenceSegments.flatMap((segment) =>
    segment.messages.map((msg) => {
      const occurredAt = normalizeOccurredAt(msg.occurredAt);
      return {
        sourceUnitId: deriveSourceUnitId(input.closeoutKey, msg.ordinal),
        actorId: deriveActorId(input.threadKey, msg.speakerKey),
        ordinal: msg.ordinal,
        externalUnitRef: externalUnitRef(input.threadKey, msg.ordinal),
        occurredAt,
        bodyText: msg.bodyText,
        bodyHash: bodyHash(msg.bodyText),
      };
    }),
  );

  const fromOrdinal = messages[0].ordinal;
  const toOrdinal = messages[messages.length - 1].ordinal;
  const continuous = input.evidenceSegments.length === 1;

  const threadManifest = {
    schemaVersion: SCHEMA_VERSION,
    fromOrdinal,
    toOrdinal,
    continuous,
    selectedEvidenceMessages: messages,
  };
  const manifestHash = threadManifestHash(threadManifest);

  // Each evidence segment maps to exactly one source anchor whose units carry every message in
  // ordinal order. The anchor id is derived from the segment's first ordinal, so it is deterministic
  // and replay-stable without ever colliding with a sourceUnitId (distinct "anchor" name label).
  const sourceAnchors = input.evidenceSegments.map((segment) => {
    const anchorId = deriveAnchorId(input.closeoutKey, segment.messages[0].ordinal);
    const units = segment.messages.map((msg) => ({
      sourceUnitId: deriveSourceUnitId(input.closeoutKey, msg.ordinal),
      fromOffset: 0,
      toOffset: codePointCount(msg.bodyText),
      ordinal: msg.ordinal,
    }));
    return { anchorId, units };
  });

  const confirmationSourceUnitId = deriveConfirmationSourceUnitId(input.closeoutKey);
  const candidateBodyHash = bodyHash(input.candidate.bodyText);

  const reviewHash = reviewManifestHash({
    submissionId,
    threadId,
    hideSelection: {
      perspectiveActorId: deriveActorId(input.threadKey, input.candidate.perspectiveSpeakerKey),
      memoryType: input.candidate.memoryType,
      bodyHash: candidateBodyHash,
    },
    threadManifestHash: manifestHash,
    sourceAnchors,
    userConfirmation: { decision: CONFIRM_DECISION },
  });

  const proof = confirmationProof(threadId, confirmationSourceUnitId, reviewHash, submissionId);

  return {
    submissionId,
    threadId,
    hideSelection: {
      perspectiveActorId: deriveActorId(input.threadKey, input.candidate.perspectiveSpeakerKey),
      memoryType: input.candidate.memoryType,
      bodyText: input.candidate.bodyText,
      bodyHash: candidateBodyHash,
    },
    userConfirmation: {
      decision: CONFIRM_DECISION,
      reviewManifestHash: reviewHash,
      confirmationSourceUnitId,
    },
    sourceAnchors,
    threadReaderManifest: {
      schemaVersion: SCHEMA_VERSION,
      fromOrdinal,
      toOrdinal,
      continuous,
      manifestHash,
      selectedEvidenceMessages: messages,
    },
    confirmationProof: proof,
  };
}
