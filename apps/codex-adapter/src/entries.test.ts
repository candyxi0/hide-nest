import { describe, expect, it } from "vitest";
import { runHook } from "../src/hook.js";
import { runMcp } from "../src/mcp.js";
import { runDoctor } from "../src/doctor.js";
import { runConsole } from "../src/console.js";

describe("CodexAdapter scaffold entries", () => {
  it("hook/doctor/console still return the NOT_IMPLEMENTED sentinel", () => {
    expect(runHook()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
    expect(runDoctor()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
    expect(runConsole()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
  });

  it("mcp is a real stdio server entry, not a sentinel", () => {
    expect(runMcp).toBeTypeOf("function");
    expect(runMcp.name).toBe("runMcp");
  });
});
