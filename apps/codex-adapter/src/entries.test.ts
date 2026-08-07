import { describe, expect, it } from "vitest";
import { runHook } from "../src/hook.js";
import { runMcp } from "../src/mcp.js";
import { runDoctor } from "../src/doctor.js";
import { runConsole } from "../src/console.js";

describe("CodexAdapter scaffold entries", () => {
  it("all entries return NOT_IMPLEMENTED sentinel", () => {
    expect(runHook()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
    expect(runMcp()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
    expect(runDoctor()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
    expect(runConsole()).toBe("NOT_IMPLEMENTED_HDM002_SCAFFOLD");
  });
});
