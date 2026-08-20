import { describe, expect, it } from "vitest";
import {
  MemoryEvidenceInputError,
  validateMemoryEvidenceInput,
} from "./memory-evidence-input.js";

const MEMORY_ID = "00000000-0000-4000-8000-000000000001";
const MEMORY_REVISION_ID = "00000000-0000-4000-8000-000000000002";

function validArgs() {
  return {
    memoryId: MEMORY_ID,
    memoryRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
  };
}

function reject(raw: unknown): void {
  expect(() => validateMemoryEvidenceInput(raw)).toThrow(MemoryEvidenceInputError);
}

describe("validateMemoryEvidenceInput closed three-field structure", () => {
  it("accepts a valid three-field input verbatim", () => {
    const input = validateMemoryEvidenceInput(validArgs());
    expect(input).toEqual({
      memoryId: MEMORY_ID,
      memoryRevisionId: MEMORY_REVISION_ID,
      revisionNo: 1,
    });
  });

  it("rejects missing fields (all three required)", () => {
    reject({ memoryRevisionId: MEMORY_REVISION_ID, revisionNo: 1 });
    reject({ memoryId: MEMORY_ID, revisionNo: 1 });
    reject({ memoryId: MEMORY_ID, memoryRevisionId: MEMORY_REVISION_ID });
    reject({});
  });

  it("rejects unknown extra fields (zero extra fields)", () => {
    reject({ ...validArgs(), extra: "nope" });
    reject({ ...validArgs(), requestId: MEMORY_ID });
    reject({ ...validArgs(), revisionNo: 1, currentRevisionId: MEMORY_REVISION_ID });
  });

  it("rejects null, arrays and non-object input", () => {
    reject(null);
    reject([]);
    reject("string");
    reject(42);
    reject(undefined);
  });

  it("rejects null and wrong types for each field", () => {
    reject({ ...validArgs(), memoryId: null });
    reject({ ...validArgs(), memoryId: 123 });
    reject({ ...validArgs(), memoryRevisionId: null });
    reject({ ...validArgs(), memoryRevisionId: {} });
    reject({ ...validArgs(), revisionNo: null });
    reject({ ...validArgs(), revisionNo: "1" });
    reject({ ...validArgs(), revisionNo: true });
  });
});

describe("validateMemoryEvidenceInput canonical UUID and positive revision", () => {
  it("rejects non-canonical or malformed UUIDs", () => {
    reject({ ...validArgs(), memoryId: "not-a-uuid" });
    reject({ ...validArgs(), memoryId: "" });
    reject({ ...validArgs(), memoryId: "00000000-0000-4000-8000-00000000000" });
    reject({ ...validArgs(), memoryRevisionId: "not-a-uuid" });
    reject({ ...validArgs(), memoryRevisionId: "zzzzzzzz-0000-4000-8000-000000000002" });
  });

  it("accepts uppercase UUIDs (canonical regex is case-insensitive)", () => {
    const upper = validArgs();
    upper.memoryId = MEMORY_ID.toUpperCase();
    upper.memoryRevisionId = MEMORY_REVISION_ID.toUpperCase();
    expect(validateMemoryEvidenceInput(upper).memoryId).toBe(MEMORY_ID.toUpperCase());
  });

  it("rejects non-positive, non-integer or unsafe revisionNo", () => {
    reject({ ...validArgs(), revisionNo: 0 });
    reject({ ...validArgs(), revisionNo: -1 });
    reject({ ...validArgs(), revisionNo: 1.5 });
    reject({ ...validArgs(), revisionNo: Number.MAX_SAFE_INTEGER + 1 });
    reject({ ...validArgs(), revisionNo: Number.NaN });
    reject({ ...validArgs(), revisionNo: Number.POSITIVE_INFINITY });
  });

  it("accepts a large but safe revisionNo", () => {
    expect(validateMemoryEvidenceInput({ ...validArgs(), revisionNo: Number.MAX_SAFE_INTEGER }))
      .toBeDefined();
  });
});
