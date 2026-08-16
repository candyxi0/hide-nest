import type { ContextPackInput } from "./context-pack-canonicalizer.js";

/**
 * Strict closed validation for the synthetic context-pack tool input.
 *
 * Only four fields are accepted; unknown fields, missing fields, null and wrong types all fail
 * closed before any HTTP request. Key and query limits use UTF-8 byte counts (not UTF-16 code
 * units) so Chinese and emoji are measured correctly. The query is never trimmed or rewritten.
 */

export const MAX_KEY_BYTES = 128;
export const MAX_QUERY_BYTES = 480;

export class ContextPackInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ContextPackInputError";
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasControlChar(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if ((code >= 0x00 && code <= 0x1f) || code === 0x7f || (code >= 0x80 && code <= 0x9f)) {
      return true;
    }
  }
  return false;
}

function assertOnlyKeys(value: Record<string, unknown>, allowed: readonly string[]): void {
  const allowedSet = new Set(allowed);
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      throw new ContextPackInputError("input contains an unknown field");
    }
  }
}

function assertKey(value: unknown, label: string): asserts value is string {
  if (typeof value !== "string" || value.length === 0) {
    throw new ContextPackInputError(`${label} must be a non-empty string`);
  }
  if (Buffer.byteLength(value, "utf8") > MAX_KEY_BYTES) {
    throw new ContextPackInputError(`${label} exceeds the UTF-8 byte limit`);
  }
  if (hasControlChar(value)) {
    throw new ContextPackInputError(`${label} contains a control character`);
  }
}

function assertQuery(value: unknown): asserts value is string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new ContextPackInputError("query must be a non-blank string");
  }
  if (Buffer.byteLength(value, "utf8") > MAX_QUERY_BYTES) {
    throw new ContextPackInputError("query exceeds the UTF-8 byte limit");
  }
  if (hasControlChar(value)) {
    throw new ContextPackInputError("query contains a control character");
  }
}

/**
 * Validates raw tool arguments and returns a closed, typed input. The query is returned exactly as
 * supplied (no trim, no rewrite). Throws {@link ContextPackInputError} with a sanitized message.
 */
export function validateContextPackInput(raw: unknown): ContextPackInput {
  if (!isPlainObject(raw)) {
    throw new ContextPackInputError("input must be an object");
  }
  assertOnlyKeys(raw, ["retrievalKey", "threadKey", "turnKey", "query"]);

  assertKey(raw.retrievalKey, "retrievalKey");
  assertKey(raw.threadKey, "threadKey");
  assertKey(raw.turnKey, "turnKey");
  assertQuery(raw.query);

  return {
    retrievalKey: raw.retrievalKey,
    threadKey: raw.threadKey,
    turnKey: raw.turnKey,
    query: raw.query,
  };
}
