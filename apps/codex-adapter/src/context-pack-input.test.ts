import { describe, expect, it } from "vitest";
import {
  MAX_KEY_BYTES,
  MAX_QUERY_BYTES,
  ContextPackInputError,
  validateContextPackInput,
} from "./context-pack-input.js";

function validArgs() {
  return {
    retrievalKey: "retrieval-key-001",
    threadKey: "thread-key-001",
    turnKey: "turn-key-001",
    query: "小林最近确认了哪些合成记忆？",
  };
}

function reject(raw: unknown): void {
  expect(() => validateContextPackInput(raw)).toThrow(ContextPackInputError);
}

describe("validateContextPackInput closed structure", () => {
  it("accepts a valid four-field input and preserves the query verbatim", () => {
    const input = validateContextPackInput(validArgs());
    expect(input.retrievalKey).toBe("retrieval-key-001");
    expect(input.threadKey).toBe("thread-key-001");
    expect(input.turnKey).toBe("turn-key-001");
    expect(input.query).toBe("小林最近确认了哪些合成记忆？");
  });

  it("keeps the original query, including surrounding whitespace (no trim/rewrite)", () => {
    const input = validateContextPackInput({ ...validArgs(), query: "  记忆 内容  " });
    expect(input.query).toBe("  记忆 内容  ");
  });

  it("rejects missing fields", () => {
    reject({ threadKey: "t", turnKey: "t", query: "q" });
    reject({ retrievalKey: "r", turnKey: "t", query: "q" });
    reject({ retrievalKey: "r", threadKey: "t", query: "q" });
    reject({ retrievalKey: "r", threadKey: "t", turnKey: "t" });
  });

  it("rejects unknown fields", () => {
    reject({ ...validArgs(), extra: "nope" });
    reject({ ...validArgs(), purpose: "ANSWER_CURRENT_TURN" });
  });

  it("rejects null and wrong types", () => {
    reject(null);
    reject([]);
    reject("string");
    reject({ ...validArgs(), retrievalKey: null });
    reject({ ...validArgs(), retrievalKey: 123 });
    reject({ ...validArgs(), threadKey: null });
    reject({ ...validArgs(), turnKey: undefined });
    reject({ ...validArgs(), query: null });
    reject({ ...validArgs(), query: 42 });
    reject({ ...validArgs(), query: {} });
  });

  it("rejects empty and blank queries", () => {
    reject({ ...validArgs(), query: "" });
    reject({ ...validArgs(), query: "   " });
    reject({ ...validArgs(), query: "\t\n " });
  });
});

describe("validateContextPackInput UTF-8 byte boundaries", () => {
  it("accepts keys at exactly 128 UTF-8 bytes and rejects 129", () => {
    expect(validateContextPackInput({ ...validArgs(), retrievalKey: "k".repeat(MAX_KEY_BYTES) }))
      .toBeDefined();
    reject({ ...validArgs(), retrievalKey: "k".repeat(MAX_KEY_BYTES + 1) });
    reject({ ...validArgs(), threadKey: "k".repeat(MAX_KEY_BYTES + 1) });
    reject({ ...validArgs(), turnKey: "k".repeat(MAX_KEY_BYTES + 1) });
  });

  it("measures Chinese keys by UTF-8 bytes, not code units", () => {
    // 42 × 3 bytes = 126 bytes + "kk" = 128 bytes → valid; 43 × 3 = 129 → invalid.
    expect(
      validateContextPackInput({ ...validArgs(), retrievalKey: "汉".repeat(42) + "kk" }),
    ).toBeDefined();
    reject({ ...validArgs(), retrievalKey: "汉".repeat(43) });
  });

  it("accepts a query at exactly 480 UTF-8 bytes and rejects 481", () => {
    expect(validateContextPackInput({ ...validArgs(), query: "q".repeat(MAX_QUERY_BYTES) }))
      .toBeDefined();
    reject({ ...validArgs(), query: "q".repeat(MAX_QUERY_BYTES + 1) });
  });

  it("measures emoji queries by UTF-8 bytes, not UTF-16 units", () => {
    // 120 × 4 bytes = 480 → valid; 121 × 4 = 484 → invalid.
    expect(validateContextPackInput({ ...validArgs(), query: "😀".repeat(120) })).toBeDefined();
    reject({ ...validArgs(), query: "😀".repeat(121) });
  });
});

describe("validateContextPackInput control-character rejection", () => {
  it("rejects NUL, CR/LF and other control chars in keys", () => {
    reject({ ...validArgs(), retrievalKey: "bad\0key" });
    reject({ ...validArgs(), retrievalKey: "bad\nkey" });
    reject({ ...validArgs(), threadKey: "bad\rkey" });
    reject({ ...validArgs(), turnKey: "bad\tkey" });
    reject({ ...validArgs(), threadKey: "bad\u007fkey" });
  });

  it("rejects NUL and control chars in query", () => {
    reject({ ...validArgs(), query: "bad\0query" });
    reject({ ...validArgs(), query: "bad\nquery" });
    reject({ ...validArgs(), query: "bad\u0085query" });
  });
});
