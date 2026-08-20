/**
 * Strict closed validation for the synthetic memory-evidence tool input.
 *
 * The input is a closed object with exactly three fields — memoryId, memoryRevisionId, revisionNo —
 * all of which are required and must be taken verbatim from a single ContextPack memory. Unknown
 * fields, missing fields, null, wrong types, non-canonical UUIDs and non-positive revision numbers
 * all fail closed before any HTTP request. No field is guessed, derived or rewritten.
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class MemoryEvidenceInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MemoryEvidenceInputError";
  }
}

export interface MemoryEvidenceInput {
  memoryId: string;
  memoryRevisionId: string;
  revisionNo: number;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function assertOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): void {
  const allowedSet = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      throw new MemoryEvidenceInputError("input contains an unknown field");
    }
  }
}

function assertUuid(value: unknown, label: string): asserts value is string {
  if (typeof value !== "string" || !UUID_RE.test(value)) {
    throw new MemoryEvidenceInputError(`${label} must be a canonical UUID`);
  }
}

/**
 * Validates raw tool arguments and returns a closed, typed input. Throws
 * {@link MemoryEvidenceInputError} with a sanitized message.
 */
export function validateMemoryEvidenceInput(raw: unknown): MemoryEvidenceInput {
  if (!isPlainObject(raw)) {
    throw new MemoryEvidenceInputError("input must be an object");
  }
  assertOnlyKeys(raw, ["memoryId", "memoryRevisionId", "revisionNo"]);

  assertUuid(raw.memoryId, "memoryId");
  assertUuid(raw.memoryRevisionId, "memoryRevisionId");

  const revisionNo = raw.revisionNo;
  if (
    typeof revisionNo !== "number" ||
    !Number.isSafeInteger(revisionNo) ||
    revisionNo < 1
  ) {
    throw new MemoryEvidenceInputError("revisionNo must be a safe positive integer");
  }

  return {
    memoryId: raw.memoryId,
    memoryRevisionId: raw.memoryRevisionId,
    revisionNo,
  };
}
