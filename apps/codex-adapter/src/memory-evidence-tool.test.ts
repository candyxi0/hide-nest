import { createServer } from "node:http";
import { afterEach, describe, expect, it, vi } from "vitest";
import { handleMemoryEvidenceTool } from "./memory-evidence-tool.js";

const TOKEN = "tool-token-0123456789abcdefghijklmnopqrstuvwxyz-ABCDE";
const BODY_CANARY = "证据正文canary-TOOL";
const PROBLEM_CANARY = "PROBLEM_TOOL_CANARY";

const MEMORY_ID = "00000000-0000-4000-8000-000000000002";
const MEMORY_REVISION_ID = "00000000-0000-4000-8000-000000000003";

function validArgs() {
  return {
    memoryId: MEMORY_ID,
    memoryRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
  };
}

function evidenceBody() {
  return {
    requestId: "00000000-0000-4000-8000-000000000001",
    resultCategory: "SUCCEEDED",
    memoryId: MEMORY_ID,
    currentRevisionId: MEMORY_REVISION_ID,
    revisionNo: 1,
    evidenceItems: [
      {
        anchorId: "10000000-0000-4000-8000-000000000001",
        sourceUnitId: "30000000-0000-4000-8000-000000000001",
        ordinal: 10,
        actorId: "20000000-0000-4000-8000-000000000001",
        actorKind: "USER",
        actorStableRef: "user:xiaolin",
        displayLabel: "hide",
        occurredAt: "2026-08-17T12:00:00+08:00",
        bodyText: BODY_CANARY,
      },
    ],
  };
}

afterEach(() => vi.unstubAllEnvs());

describe("MemoryEvidence tool orchestration", () => {
  it("fails closed on a valid call when local configuration is absent", async () => {
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", "");
    vi.stubEnv("HIDE_NEST_API_BASE_URL", "");
    const result = await handleMemoryEvidenceTool(validArgs());
    expect(result).toEqual({ ok: false, text: "LOCAL_CONFIGURATION_MISSING" });
  });

  it("rejects invalid input before consulting configuration", async () => {
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", "");
    const result = await handleMemoryEvidenceTool({ memoryId: "not-a-uuid" });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.text).not.toBe("LOCAL_CONFIGURATION_MISSING");
  });

  it("projects a configured success without internal actor fields, token or base URL", async () => {
    const server = createServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(evidenceBody()));
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("server address");
    const baseUrl = `http://127.0.0.1:${address.port}`;
    vi.stubEnv("HIDE_NEST_API_BASE_URL", baseUrl);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", TOKEN);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_CAPABILITY", "must-not-be-read-or-sent");

    const result = await handleMemoryEvidenceTool(validArgs());
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.result.status).toBe("EVIDENCE_READY");
      expect(result.result.segmentCount).toBe(1);
      expect(result.result.messageCount).toBe(1);
      expect(result.result.memoryId).toBe(MEMORY_ID);
      expect(result.result.memoryRevisionId).toBe(MEMORY_REVISION_ID);
      expect(result.result.revisionNo).toBe(1);
      expect(result.result.evidenceSegments[0].messages[0].bodyText).toBe(BODY_CANARY);

      const text = JSON.stringify(result.result);
      expect(text).toContain(BODY_CANARY);
      expect(text).not.toContain("actorId");
      expect(text).not.toContain("actorKind");
      expect(text).not.toContain("actorStableRef");
      expect(text).not.toContain(TOKEN);
      expect(text).not.toContain(baseUrl);
    }
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it("non-loopback base URL fails closed as LOCAL_CONFIGURATION_MISSING with no HTTP", async () => {
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", TOKEN);
    vi.stubEnv("HIDE_NEST_API_BASE_URL", "http://example.com");
    const result = await handleMemoryEvidenceTool(validArgs());
    expect(result).toEqual({ ok: false, text: "LOCAL_CONFIGURATION_MISSING" });
    // Must not be INTERNAL_FAILURE.
    if (!result.ok) {
      expect(result.text).not.toContain("INTERNAL_FAILURE");
      expect(result.text).not.toContain("example.com");
    }
  });

  it("projects only safe Problem fields and never leaks the canary", async () => {
    const server = createServer((_req, res) => {
      res.writeHead(404, { "Content-Type": "application/problem+json" });
      res.end(
        JSON.stringify({
          status: 404,
          requestId: "req-123",
          resultCategory: "DENIED",
          failureCode: "MEMORY_NOT_FOUND",
          retryable: false,
          secretCanary: PROBLEM_CANARY,
        }),
      );
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("server address");
    vi.stubEnv("HIDE_NEST_API_BASE_URL", `http://127.0.0.1:${address.port}`);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", TOKEN);

    const result = await handleMemoryEvidenceTool(validArgs());
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.text).toContain("MEMORY_NOT_FOUND");
      expect(result.text).toContain("404");
      expect(result.text).not.toContain(PROBLEM_CANARY);
      expect(result.text).not.toContain(TOKEN);
    }
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
});
