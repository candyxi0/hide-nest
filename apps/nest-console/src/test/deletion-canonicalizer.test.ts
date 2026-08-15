import { describe, expect, it } from "vitest";
import {
  canonicalDeletionRequestString,
  deletionRequestHash,
  isCanonicalDeletionUuid,
} from "../deletion-canonicalizer";

/**
 * Cross-language fixed vectors produced by the formal Java
 * `io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionCanonicalizer`
 * (`requestHashHex(UUID.fromString(...), rev, policy)`). The TypeScript production implementation
 * must reproduce these exactly; the test must not re-implement the hash.
 */
const JAVA_VECTORS: Array<[string, number, number, string]> = [
  ["11111111-1111-4111-8111-111111111111", 3, 1, "4ebc4f5ff5b42f12eb02b0350d37f948e1c8fcdb5255cd12cfa9c3fded1543ed"],
  ["aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", 1, 7, "950dc4e33e84465b143fcb59a614880267fdb87563b5fce2f6e903cd95ea82f9"],
  ["00000000-0000-4000-8000-000000000000", 42, 5, "4b8e2b646fc430c6b5cfd0310fad86200aa12902e61edfcec57d05881a9d041e"],
];

describe("deletion request canonicalizer", () => {
  it.each(JAVA_VECTORS)("matches Java fixed vector for %s rev=%d pol=%d", async (memoryId, revisionNo, policyRevisionNo, expected) => {
    expect(await deletionRequestHash(memoryId, revisionNo, policyRevisionNo)).toBe(expected);
  });

  it("emits a 64-char lowercase hex hash and a stable frozen string", async () => {
    const hash = await deletionRequestHash("11111111-1111-4111-8111-111111111111", 3, 1);
    expect(hash).toMatch(/^[0-9a-f]{64}$/);
    expect(canonicalDeletionRequestString("11111111-1111-4111-8111-111111111111", 3, 1)).toBe(
      "26:LOCAL_V1_DELETE_PREVIEW_V136:11111111-1111-4111-8111-1111111111111:31:1",
    );
  });

  it("rejects non-canonical UUIDs and out-of-range revisions before hashing", () => {
    expect(isCanonicalDeletionUuid("11111111-1111-4111-8111-111111111111")).toBe(true);
    expect(isCanonicalDeletionUuid("11111111-1111-4111-8111-11111111111G")).toBe(false);
    expect(isCanonicalDeletionUuid("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE")).toBe(false);
    expect(isCanonicalDeletionUuid("not-a-uuid")).toBe(false);

    expect(() => canonicalDeletionRequestString("not-a-uuid", 3, 1)).toThrow();
    expect(() => canonicalDeletionRequestString("11111111-1111-4111-8111-111111111111", 0, 1)).toThrow();
    expect(() => canonicalDeletionRequestString("11111111-1111-4111-8111-111111111111", 1.5, 1)).toThrow();
    expect(() => canonicalDeletionRequestString("11111111-1111-4111-8111-111111111111", 3, 0)).toThrow();
  });
});
