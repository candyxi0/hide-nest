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
    evidenceSegments: [
      {
        messages: [
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
      },
    ],
  };
}

function segmentWith(ordinals: number[]) {
  return {
    messages: ordinals.map((ordinal) => ({
      speakerKey: "xiaolin",
      ordinal,
      occurredAt: "2026-08-13T12:00:00+08:00",
      bodyText: `消息-${ordinal}`,
    })),
  };
}

function expectRejected(input: unknown) {
  expect(() => validateCloseoutInput(input)).toThrow(CloseoutInputError);
}

describe("validateCloseoutInput", () => {
  it("accepts a minimal valid single-segment input with userConfirmed=true", () => {
    const result = validateCloseoutInput(validInput());
    expect(result.userConfirmed).toBe(true);
    expect(result.evidenceSegments).toHaveLength(1);
    expect(result.evidenceSegments[0].messages).toHaveLength(2);
  });

  it("accepts a single contiguous segment with three messages", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([0, 1, 2])];
    const result = validateCloseoutInput(input);
    expect(result.evidenceSegments).toHaveLength(1);
    expect(result.evidenceSegments[0].messages.map((m) => m.ordinal)).toEqual([0, 1, 2]);
  });

  it("accepts two separated segments with an ordinal gap", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([0, 1]), segmentWith([4, 5])];
    const result = validateCloseoutInput(input);
    expect(result.evidenceSegments).toHaveLength(2);
    expect(result.evidenceSegments[0].messages.map((m) => m.ordinal)).toEqual([0, 1]);
    expect(result.evidenceSegments[1].messages.map((m) => m.ordinal)).toEqual([4, 5]);
  });

  it("accepts a single-message segment", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([7])];
    const result = validateCloseoutInput(input);
    expect(result.evidenceSegments).toHaveLength(1);
    expect(result.evidenceSegments[0].messages).toHaveLength(1);
    expect(result.evidenceSegments[0].messages[0].ordinal).toBe(7);
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

  it("rejects old top-level evidenceMessages as an unknown field", () => {
    const input = validInput() as Record<string, unknown>;
    delete input.evidenceSegments;
    (input as Record<string, unknown>).evidenceMessages = [
      { speakerKey: "xiaolin", ordinal: 0, occurredAt: "2026-08-13T12:00:00+08:00", bodyText: "旧格式" },
    ];
    expectRejected(input);
  });

  it("rejects unknown candidate field", () => {
    const input = validInput();
    input.candidate = { ...input.candidate, extra: 1 } as never;
    expectRejected(input);
  });

  it("rejects unknown evidence message field", () => {
    const input = validInput();
    input.evidenceSegments[0].messages[0] = {
      ...input.evidenceSegments[0].messages[0],
      extra: 1,
    } as never;
    expectRejected(input);
  });

  it("rejects unknown evidence segment field", () => {
    const input = validInput();
    input.evidenceSegments[0] = { ...input.evidenceSegments[0], extra: 1 } as never;
    expectRejected(input);
  });

  it("rejects segment-internal duplicate ordinals", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([0, 0])];
    expectRejected(input);
  });

  it("rejects segment-internal ordinal gap", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([0, 2])];
    expectRejected(input);
  });

  it("rejects segment-internal descending ordinals", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([1, 0])];
    expectRejected(input);
  });

  it("rejects cross-segment overlap", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([0, 1]), segmentWith([1, 2])];
    expectRejected(input);
  });

  it("rejects cross-segment descending order", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([4, 5]), segmentWith([0, 1])];
    expectRejected(input);
  });

  it("rejects adjacent pseudo-split segments", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith([0, 1]), segmentWith([2, 3])];
    expectRejected(input);
  });

  it("rejects an empty segment", () => {
    const input = validInput();
    input.evidenceSegments = [{ messages: [] }] as never;
    expectRejected(input);
  });

  it("rejects more than 100 total messages", () => {
    const input = validInput();
    input.evidenceSegments = [segmentWith(Array.from({ length: 101 }, (_, i) => i))];
    expectRejected(input);
  });

  it("rejects more than 100 segments", () => {
    const input = validInput();
    input.evidenceSegments = Array.from({ length: 101 }, (_, i) => segmentWith([i * 2]));
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
    input.evidenceSegments[0].messages[0].speakerKey = "bad\u001fkey";
    expectRejected(input);
  });

  it("rejects invalid memoryType", () => {
    const input = validInput();
    input.candidate.memoryType = "NOT_A_TYPE" as never;
    expectRejected(input);
  });

  it("rejects empty evidenceSegments", () => {
    expectRejected({ ...validInput(), evidenceSegments: [] });
  });

  it("rejects candidate bodyText over 16000 code points", () => {
    const input = validInput();
    input.candidate.bodyText = "a".repeat(16001);
    expectRejected(input);
  });

  it("rejects evidence bodyText over 1 MiB UTF-8", () => {
    const input = validInput();
    input.evidenceSegments[0].messages[0].bodyText = "中".repeat(1024 * 1024);
    expectRejected(input);
  });

  it("never echoes input values in sanitized rejection messages", () => {
    const canary = "CANARY_SECRET_VALUE";
    expect(() =>
      validateCloseoutInput({ ...validInput(), closeoutKey: canary, extra: canary }),
    ).toThrowError(/input contains an unknown field/);
    try {
      validateCloseoutInput({
        ...validInput(),
        userConfirmed: false,
        candidate: { ...validInput().candidate, bodyText: canary },
      });
      throw new Error("should have rejected");
    } catch (error) {
      expect(String((error as Error).message)).not.toContain(canary);
    }
  });
});
