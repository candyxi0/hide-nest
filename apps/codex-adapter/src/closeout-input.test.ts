import { describe, expect, it } from "vitest";
import { validateCloseoutInput, CloseoutInputError } from "./closeout-input.js";

function validInput() {
  return {
    closeoutKey: "closeout-key-001",
    threadKey: "thread-key-001",
    userConfirmed: true,
    candidate: {
      perspectiveSpeakerKey: "xiaolin",
      memoryType: "EVENT",
      bodyText: "小林确认了合成记忆候选",
    },
    evidenceMessages: [
      {
        speakerKey: "xiaolin",
        ordinal: 0,
        occurredAt: "2026-08-13T12:00:00+08:00",
        bodyText: "这是证据消息一",
      },
      {
        speakerKey: "hide",
        ordinal: 1,
        occurredAt: "2026-08-13T12:01:00+08:00",
        bodyText: "这是证据消息二",
      },
    ],
  };
}

function expectRejected(input: unknown) {
  expect(() => validateCloseoutInput(input)).toThrow(CloseoutInputError);
}

describe("validateCloseoutInput", () => {
  it("accepts a minimal valid input with userConfirmed=true", () => {
    const result = validateCloseoutInput(validInput());
    expect(result.userConfirmed).toBe(true);
    expect(result.evidenceMessages).toHaveLength(2);
  });

  it("rejects userConfirmed=false before HTTP", () => {
    expectRejected({ ...validInput(), userConfirmed: false });
  });

  it("rejects missing userConfirmed before HTTP", () => {
    const input = validInput() as Record<string, unknown>;
    delete input.userConfirmed;
    expectRejected(input);
  });

  it("rejects unknown top-level field", () => {
    expectRejected({ ...validInput(), extraField: "nope" });
  });

  it("rejects unknown candidate field", () => {
    const input = validInput();
    input.candidate = { ...input.candidate, extra: 1 } as never;
    expectRejected(input);
  });

  it("rejects unknown evidence message field", () => {
    const input = validInput();
    input.evidenceMessages[0] = { ...input.evidenceMessages[0], extra: 1 } as never;
    expectRejected(input);
  });

  it("rejects duplicate ordinals", () => {
    const input = validInput();
    input.evidenceMessages[1].ordinal = 0;
    expectRejected(input);
  });

  it("rejects ordinal gap", () => {
    const input = validInput();
    input.evidenceMessages[1].ordinal = 2;
    expectRejected(input);
  });

  it("rejects descending ordinals", () => {
    const input = validInput();
    input.evidenceMessages[1].ordinal = -1;
    expectRejected(input);
  });

  it("rejects perspectiveSpeakerKey absent from evidence", () => {
    const input = validInput();
    input.candidate.perspectiveSpeakerKey = "absent";
    expectRejected(input);
  });

  it("rejects control character in closeoutKey", () => {
    expectRejected({ ...validInput(), closeoutKey: "bad\u0000key" });
  });

  it("rejects control character in speakerKey", () => {
    const input = validInput();
    input.evidenceMessages[0].speakerKey = "bad\u001fkey";
    expectRejected(input);
  });

  it("rejects invalid memoryType", () => {
    const input = validInput();
    input.candidate.memoryType = "NOT_A_TYPE" as never;
    expectRejected(input);
  });

  it("rejects empty evidenceMessages", () => {
    expectRejected({ ...validInput(), evidenceMessages: [] });
  });

  it("rejects candidate bodyText over 16000 code points", () => {
    const input = validInput();
    input.candidate.bodyText = "a".repeat(16001);
    expectRejected(input);
  });

  it("rejects evidence bodyText over 1 MiB UTF-8", () => {
    const input = validInput();
    input.evidenceMessages[0].bodyText = "中".repeat(1024 * 1024);
    expectRejected(input);
  });

  it("never echoes input values in sanitized rejection messages", () => {
    const canary = "CANARY_SECRET_VALUE";
    expect(() =>
      validateCloseoutInput({ ...validInput(), closeoutKey: canary, extra: canary }),
    ).toThrowError(/input contains an unknown field/);
    try {
      validateCloseoutInput({ ...validInput(), userConfirmed: false, candidate: { ...validInput().candidate, bodyText: canary } });
      throw new Error("should have rejected");
    } catch (error) {
      expect(String((error as Error).message)).not.toContain(canary);
    }
  });
});
