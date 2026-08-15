/**
 * Local V1 permanent-deletion request binding canonicalizer.
 *
 * Freezes the exact byte layout used by the Java `LocalV1DeletionCanonicalizer`. Each field is
 * encoded as `UTF8(byteLength) + ':' + UTF8(value)` for, in order:
 *   LOCAL_V1_DELETE_PREVIEW_V1, memoryId (lowercase hyphenated UUID),
 *   revisionNo (decimal), currentPolicyRevisionNo (decimal)
 * then SHA-256 (Web Crypto) yields the 64-char lowercase hex request hash.
 *
 * This hash is a request binding, never an authorization secret. Non-canonical inputs are rejected
 * before any request is issued.
 */

const VERSION_LABEL = "LOCAL_V1_DELETE_PREVIEW_V1";
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function field(value: string): string {
  return `${new TextEncoder().encode(value).length}:${value}`;
}

export function isCanonicalDeletionUuid(value: string): boolean {
  return UUID_RE.test(value);
}

/** Builds the frozen canonical string, rejecting non-canonical bindings before hashing. */
export function canonicalDeletionRequestString(
  memoryId: string,
  revisionNo: number,
  currentPolicyRevisionNo: number,
): string {
  if (!UUID_RE.test(memoryId)) {
    throw new Error("LOCAL_V1_DELETE_BINDING_INVALID: memoryId must be a lowercase hyphenated UUID");
  }
  if (!Number.isInteger(revisionNo) || revisionNo < 1) {
    throw new Error("LOCAL_V1_DELETE_BINDING_INVALID: revisionNo must be >= 1");
  }
  if (!Number.isInteger(currentPolicyRevisionNo) || currentPolicyRevisionNo < 1) {
    throw new Error("LOCAL_V1_DELETE_BINDING_INVALID: currentPolicyRevisionNo must be >= 1");
  }
  return (
    field(VERSION_LABEL) +
    field(memoryId) +
    field(String(revisionNo)) +
    field(String(currentPolicyRevisionNo))
  );
}

/** 64-char lowercase hex SHA-256 of the frozen canonical string (Web Crypto). */
export async function deletionRequestHash(
  memoryId: string,
  revisionNo: number,
  currentPolicyRevisionNo: number,
): Promise<string> {
  const canonical = canonicalDeletionRequestString(memoryId, revisionNo, currentPolicyRevisionNo);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonical));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}
