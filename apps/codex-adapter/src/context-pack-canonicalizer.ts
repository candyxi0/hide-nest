import {
  MEMORY_TYPES,
  deriveThreadId,
  field,
  nameUuidFromBytes,
  type MemoryType,
} from "./closeout-canonicalizer.js";

/**
 * Deterministic identity + strict closed-response validator for the synthetic context-pack tool.
 *
 * Thread identity reuses the accepted closeout `deriveThreadId` so the same room key always maps to
 * the same thread UUID. Turn identity is a new length-prefixed UTF-8 derivation over (threadKey,
 * turnKey) so `ab+c` and `a+bc` can never collide. The response validator never passes API JSON
 * through: it re-checks every field and returns only the validated projection the tool is allowed
 * to surface to hide.
 */

/** Fixed purpose written by the adapter; never exposed to the caller. */
export const CONTEXT_PACK_PURPOSE = "ANSWER_CURRENT_TURN" as const;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// Capture every calendar component so the date can be validated without relying on Date.parse
// normalization (which would silently accept 2026-02-30 as a real instant).
const TIMESTAMP_RE =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:([Zz])|([+-])(\d{2}):(\d{2}))$/;

export interface ContextPackInput {
  retrievalKey: string;
  threadKey: string;
  turnKey: string;
  query: string;
}

/** The closed wire request: identity derived + fixed purpose + exact query, plus the raw idempotency key. */
export interface ContextPackRequest {
  retrievalKey: string;
  threadId: string;
  turnId: string;
  purpose: typeof CONTEXT_PACK_PURPOSE;
  query: string;
}

export type ContextPackResultCategory = "SUCCEEDED" | "NO_RELEVANT_RESULT";

export interface ContextPackMemory {
  memoryId: string;
  memoryRevisionId: string;
  revisionNo: number;
  policyRevisionNo: number;
  memoryType: MemoryType;
  bodyText: string;
  score: number;
  evidenceOccurredAt: string;
  evidenceAgeDays: number;
}

/** The validated, closed success projection (never includes threadId/turnId/purpose/policyRevisionSet). */
export interface ContextPackSuccess {
  requestId: string;
  resultCategory: ContextPackResultCategory;
  deliveryId: string;
  issuedAt: string;
  expiresAt: string;
  budgetLimited: boolean;
  memories: ContextPackMemory[];
}

/**
 * Deterministic turn UUID. Every component is length-prefixed UTF-8 (via the accepted `field`
 * primitive), so `deriveTurnId("a", "bc")` and `deriveTurnId("ab", "c")` provably cannot collide.
 */
export function deriveTurnId(threadKey: string, turnKey: string): string {
  const name = [field("turn"), field(threadKey), field(turnKey)].join("");
  return nameUuidFromBytes(Buffer.from(name, "utf8"));
}

/** Maps a validated tool input to the closed wire request. */
export function buildContextPackRequest(input: ContextPackInput): ContextPackRequest {
  return {
    retrievalKey: input.retrievalKey,
    threadId: deriveThreadId(input.threadKey),
    turnId: deriveTurnId(input.threadKey, input.turnKey),
    purpose: CONTEXT_PACK_PURPOSE,
    query: input.query,
  };
}

// ── strict closed-response validation ────────────────────────────────────

function fail(): never {
  throw new Error("INVALID_RESPONSE");
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isValidUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_RE.test(value);
}

function assertExactKeys(record: Record<string, unknown>, expected: readonly string[]): void {
  const actual = Object.keys(record).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length) fail();
  for (let i = 0; i < actual.length; i++) {
    if (actual[i] !== wanted[i]) fail();
  }
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

/** Days since 1970-01-01 for a civil date (Howard Hinnant's algorithm); integer-only, no Date dependency. */
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
 * Strictly validates an RFC3339/OffsetDateTime instant: every calendar component must be a real
 * date/time (month range, real day-of-month including leap years, hour/minute/second range, and
 * offset range) before it is converted to a comparable absolute epoch in nanoseconds. Never
 * rewrites the value and never depends on Date.parse normalization.
 */
function parseTimestamp(value: string): bigint {
  const m = TIMESTAMP_RE.exec(value);
  if (m === null) fail();

  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  const hour = Number(m[4]);
  const minute = Number(m[5]);
  const second = Number(m[6]);
  const fraction = m[7] ?? "";
  const offsetSign = m[9];
  const offsetHour = Number(m[10] ?? 0);
  const offsetMinute = Number(m[11] ?? 0);

  if (month < 1 || month > 12) fail();
  if (day < 1 || day > daysInMonth(year, month)) fail();
  if (hour > 23 || minute > 59 || second > 59) fail();
  if (offsetHour > 23 || offsetMinute > 59) fail();

  const days = daysFromCivil(year, month, day);
  const localSeconds = BigInt(days) * 86400n
    + BigInt(hour * 3600 + minute * 60 + second);
  const offsetSeconds = BigInt(
    (offsetSign === "-" ? -1 : 1) * (offsetHour * 3600 + offsetMinute * 60),
  );
  const epochSeconds = localSeconds - offsetSeconds;
  const nanos = BigInt(fraction.padEnd(9, "0"));
  return epochSeconds * 1_000_000_000n + nanos;
}

function parseMemory(value: unknown, issuedAtNanos: bigint): ContextPackMemory {
  if (!isPlainObject(value)) fail();
  assertExactKeys(value, [
    "memoryId",
    "memoryRevisionId",
    "revisionNo",
    "policyRevisionNo",
    "memoryType",
    "bodyText",
    "score",
    "evidenceOccurredAt",
    "evidenceAgeDays",
  ]);

  if (!isValidUuid(value.memoryId)) fail();
  if (!isValidUuid(value.memoryRevisionId)) fail();

  const revisionNo = value.revisionNo;
  const policyRevisionNo = value.policyRevisionNo;
  if (
    typeof revisionNo !== "number" ||
    !Number.isSafeInteger(revisionNo) ||
    revisionNo < 1
  ) {
    fail();
  }
  if (
    typeof policyRevisionNo !== "number" ||
    !Number.isSafeInteger(policyRevisionNo) ||
    policyRevisionNo < 1
  ) {
    fail();
  }

  if (
    typeof value.memoryType !== "string" ||
    !(MEMORY_TYPES as readonly string[]).includes(value.memoryType)
  ) {
    fail();
  }
  if (typeof value.bodyText !== "string") fail();

  const score = value.score;
  if (typeof score !== "number" || !Number.isFinite(score)) fail();

  if (typeof value.evidenceOccurredAt !== "string") fail();
  const evidenceOccurredAtNanos = parseTimestamp(value.evidenceOccurredAt);
  if (evidenceOccurredAtNanos > issuedAtNanos) fail();

  const evidenceAgeDays = value.evidenceAgeDays;
  if (
    typeof evidenceAgeDays !== "number" ||
    !Number.isSafeInteger(evidenceAgeDays) ||
    evidenceAgeDays < 0
  ) {
    fail();
  }
  const expectedAgeDays = Number(
    (issuedAtNanos - evidenceOccurredAtNanos) / 86_400_000_000_000n,
  );
  if (evidenceAgeDays !== expectedAgeDays) fail();

  return {
    memoryId: value.memoryId,
    memoryRevisionId: value.memoryRevisionId,
    revisionNo,
    policyRevisionNo,
    memoryType: value.memoryType as MemoryType,
    bodyText: value.bodyText,
    score,
    evidenceOccurredAt: value.evidenceOccurredAt,
    evidenceAgeDays,
  };
}

/**
 * Parses and strictly re-validates a raw `POST /v1/context-packs` response against this request.
 * Throws (sanitized) on any deviation; never returns a half ContextPack.
 */
export function parseContextPackResponse(
  request: ContextPackRequest,
  raw: unknown,
): ContextPackSuccess {
  if (!isPlainObject(raw)) fail();
  assertExactKeys(raw, [
    "requestId",
    "resultCategory",
    "deliveryId",
    "threadId",
    "turnId",
    "purpose",
    "policyRevisionSet",
    "issuedAt",
    "expiresAt",
    "budgetLimited",
    "memories",
  ]);

  const resultCategory = raw.resultCategory;
  if (resultCategory !== "SUCCEEDED" && resultCategory !== "NO_RELEVANT_RESULT") fail();

  const requestId = raw.requestId;
  const deliveryId = raw.deliveryId;
  if (!isValidUuid(requestId)) fail();
  if (!isValidUuid(deliveryId)) fail();

  // Bind the response to this request's identity, verbatim.
  const threadId = raw.threadId;
  const turnId = raw.turnId;
  const purpose = raw.purpose;
  if (threadId !== request.threadId) fail();
  if (turnId !== request.turnId) fail();
  if (purpose !== request.purpose) fail();

  const budgetLimited = raw.budgetLimited;
  if (typeof budgetLimited !== "boolean") fail();

  const issuedAt = raw.issuedAt;
  const expiresAt = raw.expiresAt;
  if (typeof issuedAt !== "string") fail();
  if (typeof expiresAt !== "string") fail();
  const issuedAtNanos = parseTimestamp(issuedAt);
  const expiresAtNanos = parseTimestamp(expiresAt);
  if (expiresAtNanos <= issuedAtNanos) fail();

  if (!Array.isArray(raw.memories)) fail();
  const memories = raw.memories.map((m) => parseMemory(m, issuedAtNanos));
  if (memories.length > 5) fail();
  if (resultCategory === "SUCCEEDED" && memories.length < 1) fail();
  if (resultCategory === "NO_RELEVANT_RESULT" && memories.length !== 0) fail();

  // No duplicate memory/revision ids, and scores are non-increasing.
  const memoryIds = new Set<string>();
  const memoryRevisionIds = new Set<string>();
  for (const memory of memories) {
    if (memoryIds.has(memory.memoryId)) fail();
    if (memoryRevisionIds.has(memory.memoryRevisionId)) fail();
    memoryIds.add(memory.memoryId);
    memoryRevisionIds.add(memory.memoryRevisionId);
  }
  for (let i = 1; i < memories.length; i++) {
    if (memories[i - 1].score < memories[i].score) fail();
  }

  // policyRevisionSet must be the exact sorted `MEMORY:<memoryId>:<policyRevisionNo>` set.
  if (!Array.isArray(raw.policyRevisionSet)) fail();
  const actualSet = raw.policyRevisionSet.map((entry) => {
    if (typeof entry !== "string") fail();
    return entry;
  });
  const expectedSet = memories
    .map((memory) => `MEMORY:${memory.memoryId}:${memory.policyRevisionNo}`)
    .sort();
  const actualSorted = [...actualSet].sort();
  if (actualSorted.length !== expectedSet.length) fail();
  for (let i = 0; i < actualSorted.length; i++) {
    if (actualSorted[i] !== expectedSet[i]) fail();
  }

  return {
    requestId,
    resultCategory,
    deliveryId,
    issuedAt,
    expiresAt,
    budgetLimited,
    memories,
  };
}
