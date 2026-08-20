import type { MemoryEvidenceInput } from "./memory-evidence-input.js";

/**
 * Strict closed-response validator + segmenter for the synthetic memory-evidence tool.
 *
 * The formal API response is never passed through: every top-level and item field is re-checked,
 * identity is bound verbatim to the requesting memory (memoryId / currentRevisionId / revisionNo),
 * and the evidence items are segmented by anchor with strict ordering invariants. Internal actor
 * fields (actorId / actorKind / actorStableRef) are validated for legality but never returned in
 * the projection. Any deviation fails closed; nothing partial is ever produced.
 */

/** The only accepted formal success resultCategory. */
export const EVIDENCE_RESULT_CATEGORY = "SUCCEEDED" as const;
export const MAX_EVIDENCE_ITEMS = 100;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// Capture every calendar component so the date can be validated without relying on Date.parse
// normalization (which would silently accept 2026-02-30 as a real instant).
const TIMESTAMP_RE =
  /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:([Zz])|([+-])(\d{2}):(\d{2}))$/;

export interface MemoryEvidenceRequest {
  memoryId: string;
  memoryRevisionId: string;
  revisionNo: number;
}

export interface MemoryEvidenceItem {
  anchorId: string;
  sourceUnitId: string;
  ordinal: number;
  actorId: string;
  actorKind: string;
  actorStableRef: string;
  displayLabel: string;
  occurredAt: string;
  bodyText: string;
}

/** A validated message projected to hide: internal actor fields are already dropped. */
export interface MemoryEvidenceMessage {
  sourceUnitId: string;
  ordinal: number;
  displayLabel: string;
  occurredAt: string;
  bodyText: string;
}

export interface MemoryEvidenceSegment {
  segmentNo: number;
  anchorId: string;
  messages: MemoryEvidenceMessage[];
}

export interface MemoryEvidenceSuccess {
  requestId: string;
  resultCategory: typeof EVIDENCE_RESULT_CATEGORY;
  memoryId: string;
  currentRevisionId: string;
  revisionNo: number;
  evidenceItems: MemoryEvidenceItem[];
  segments: MemoryEvidenceSegment[];
}

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

/**
 * Strictly validates an RFC3339/OffsetDateTime instant: every calendar component must be a real
 * date/time (month range, real day-of-month including leap years, hour/minute/second range, and
 * offset range). Never rewrites the value and never depends on Date.parse normalization.
 */
function validateTimestamp(value: string): void {
  const m = TIMESTAMP_RE.exec(value);
  if (m === null) fail();

  const year = Number(m[1]);
  const month = Number(m[2]);
  const day = Number(m[3]);
  const hour = Number(m[4]);
  const minute = Number(m[5]);
  const second = Number(m[6]);
  const offsetHour = Number(m[10] ?? 0);
  const offsetMinute = Number(m[11] ?? 0);

  if (month < 1 || month > 12) fail();
  if (day < 1 || day > daysInMonth(year, month)) fail();
  if (hour > 23 || minute > 59 || second > 59) fail();
  if (offsetHour > 23 || offsetMinute > 59) fail();
}

function assertPositiveSafeInt(value: unknown): asserts value is number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) fail();
}

function parseItem(value: unknown): MemoryEvidenceItem {
  if (!isPlainObject(value)) fail();
  assertExactKeys(value, [
    "anchorId",
    "sourceUnitId",
    "ordinal",
    "actorId",
    "actorKind",
    "actorStableRef",
    "displayLabel",
    "occurredAt",
    "bodyText",
  ]);

  if (!isValidUuid(value.anchorId)) fail();
  if (!isValidUuid(value.sourceUnitId)) fail();
  if (!isValidUuid(value.actorId)) fail();

  const ordinal = value.ordinal;
  if (typeof ordinal !== "number" || !Number.isSafeInteger(ordinal) || ordinal < 0) fail();

  if (typeof value.actorKind !== "string" || value.actorKind.length === 0) fail();
  if (typeof value.actorStableRef !== "string" || value.actorStableRef.length === 0) fail();

  if (typeof value.displayLabel !== "string" || value.displayLabel.length === 0) fail();
  if (typeof value.occurredAt !== "string") fail();
  validateTimestamp(value.occurredAt);
  if (typeof value.bodyText !== "string") fail();

  return {
    anchorId: value.anchorId,
    sourceUnitId: value.sourceUnitId,
    ordinal,
    actorId: value.actorId,
    actorKind: value.actorKind,
    actorStableRef: value.actorStableRef,
    displayLabel: value.displayLabel,
    occurredAt: value.occurredAt,
    bodyText: value.bodyText,
  };
}

/**
 * Groups the validated items into contiguous anchor segments, normalises block order by semantic
 * ordinal, and enforces the ordinal/anchor invariants.
 *
 * The formal API may return blocks in relation.createdAt order (e.g. `[20,21]` before `[1,2]`),
 * which is not a semantic order. This function therefore:
 * 1. Groups the raw items into contiguous anchor blocks (anchor reflow is still rejected).
 * 2. Validates each block internally (sourceUnitId uniqueness, strictly consecutive ordinals).
 * 3. Sorts blocks by firstOrdinal ascending (anchorId as deterministic tie-break only; if two
 *    blocks share the same firstOrdinal they will overlap — caught in step 4).
 * 4. Checks cross-block invariants on the sorted blocks: no overlap, no reverse, no adjacent
 *    fake segmentation.
 * 5. Assigns segmentNo from the normalised semantic order.
 *
 * Only complete anchor blocks are reordered; individual messages are never sorted by ordinal,
 * text, speaker or time.
 */
function buildSegments(items: MemoryEvidenceItem[]): MemoryEvidenceSegment[] {
  const sourceUnitIds = new Set<string>();
  const blocks: Array<{ anchorId: string; items: MemoryEvidenceItem[] }> = [];

  for (const item of items) {
    if (sourceUnitIds.has(item.sourceUnitId)) fail();
    sourceUnitIds.add(item.sourceUnitId);

    const last = blocks[blocks.length - 1];
    if (last !== undefined && last.anchorId === item.anchorId) {
      last.items.push(item);
    } else {
      blocks.push({ anchorId: item.anchorId, items: [item] });
    }
  }

  // Each anchor must appear in exactly one contiguous block.
  const seenAnchors = new Set<string>();
  for (const block of blocks) {
    if (seenAnchors.has(block.anchorId)) fail();
    seenAnchors.add(block.anchorId);
  }

  // Intra-block: ordinals strictly consecutive increasing.
  for (const block of blocks) {
    const ordinals = block.items.map((item) => item.ordinal);
    for (let i = 1; i < ordinals.length; i++) {
      if (ordinals[i] !== ordinals[i - 1] + 1) fail();
    }
  }

  // Sort blocks by semantic firstOrdinal; anchorId as deterministic tie-break.
  const sorted = [...blocks].sort((a, b) => {
    const aFirst = a.items[0].ordinal;
    const bFirst = b.items[0].ordinal;
    if (aFirst !== bFirst) return aFirst - bFirst;
    return a.anchorId.localeCompare(b.anchorId);
  });

  // Cross-block: ascending, disjoint, and separated by a real gap.
  for (let i = 1; i < sorted.length; i++) {
    const prev = sorted[i - 1];
    const curr = sorted[i];
    const prevLast = prev.items[prev.items.length - 1].ordinal;
    const currFirst = curr.items[0].ordinal;
    if (currFirst <= prevLast) fail(); // overlap or reverse order
    if (currFirst === prevLast + 1) fail(); // adjacent fake segmentation
  }

  return sorted.map((block, index) => ({
    segmentNo: index + 1,
    anchorId: block.anchorId,
    messages: block.items.map((item) => ({
      sourceUnitId: item.sourceUnitId,
      ordinal: item.ordinal,
      displayLabel: item.displayLabel,
      occurredAt: item.occurredAt,
      bodyText: item.bodyText,
    })),
  }));
}

/**
 * Parses and strictly re-validates a raw `GET /v1/memories/{memoryId}/evidence` response against the
 * requesting input. Throws (sanitized) on any deviation; never returns a partial MemoryEvidence.
 */
export function parseMemoryEvidenceResponse(
  input: MemoryEvidenceInput,
  raw: unknown,
): MemoryEvidenceSuccess {
  if (!isPlainObject(raw)) fail();
  assertExactKeys(raw, [
    "requestId",
    "resultCategory",
    "memoryId",
    "currentRevisionId",
    "revisionNo",
    "evidenceItems",
  ]);

  const resultCategory = raw.resultCategory;
  if (resultCategory !== EVIDENCE_RESULT_CATEGORY) fail();

  const requestId = raw.requestId;
  const memoryId = raw.memoryId;
  const currentRevisionId = raw.currentRevisionId;
  if (!isValidUuid(requestId)) fail();
  if (!isValidUuid(memoryId)) fail();
  if (!isValidUuid(currentRevisionId)) fail();

  // Bind the response verbatim to the requesting memory.
  if (memoryId !== input.memoryId) fail();
  if (currentRevisionId !== input.memoryRevisionId) fail();

  const revisionNo = raw.revisionNo;
  assertPositiveSafeInt(revisionNo);
  if (revisionNo !== input.revisionNo) fail();

  if (!Array.isArray(raw.evidenceItems)) fail();
  if (raw.evidenceItems.length < 1) fail();
  if (raw.evidenceItems.length > MAX_EVIDENCE_ITEMS) fail();

  const evidenceItems = raw.evidenceItems.map(parseItem);
  const segments = buildSegments(evidenceItems);

  return {
    requestId,
    resultCategory,
    memoryId,
    currentRevisionId,
    revisionNo,
    evidenceItems,
    segments,
  };
}

/** Maps a validated input to the closed request the client will issue. */
export function buildMemoryEvidenceRequest(input: MemoryEvidenceInput): MemoryEvidenceRequest {
  return {
    memoryId: input.memoryId,
    memoryRevisionId: input.memoryRevisionId,
    revisionNo: input.revisionNo,
  };
}
