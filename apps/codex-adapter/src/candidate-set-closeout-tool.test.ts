import { createServer } from "node:http";
import { afterEach, describe, expect, it, vi } from "vitest";
import { handleCandidateSetCloseoutTool } from "./candidate-set-closeout-tool.js";
import { candidateSetTestRaw } from "./candidate-set-closeout-test-fixture.js";

const TOKEN = "tool-token-0123456789abcdefghijklmnopqrstuvwxyz-ABCDE";

afterEach(() => vi.unstubAllEnvs());

describe("CandidateSet closeout tool orchestration", () => {
  it("rejects accepted REVISE and SUPERSEDE before HTTP", async () => {
    let httpReached = 0;
    const server = createServer(() => {
      httpReached++;
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("server address");
    vi.stubEnv("HIDE_NEST_API_BASE_URL", `http://127.0.0.1:${address.port}`);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", TOKEN);
    for (const action of ["REVISE", "SUPERSEDE"]) {
      const raw = candidateSetTestRaw("revise");
      raw.candidates[0].action = action;
      const result = await handleCandidateSetCloseoutTool(raw);
      expect(result).toEqual({
        ok: false,
        text: "accepted candidate action must be CREATE for local v1",
      });
    }
    expect(httpReached).toBe(0);
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it("rejects dangerous evidence and memoryText controls before HTTP", async () => {
    let httpReached = 0;
    const server = createServer(() => {
      httpReached++;
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("server address");
    vi.stubEnv("HIDE_NEST_API_BASE_URL", `http://127.0.0.1:${address.port}`);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", TOKEN);

    for (const dangerous of ["\u0000", "\u000b", "\u000c", "\u007f", "\u0080", "\ud800"]) {
      const evidence = candidateSetTestRaw("three-create");
      evidence.evidenceSegments[0].messages[0].bodyText = `before${dangerous}after`;
      expect((await handleCandidateSetCloseoutTool(evidence)).ok).toBe(false);

      const memory = candidateSetTestRaw("three-create");
      memory.candidates[0].memoryText = `before${dangerous}after`;
      expect((await handleCandidateSetCloseoutTool(memory)).ok).toBe(false);
    }
    expect(httpReached).toBe(0);
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  it("rejects old input before consulting missing configuration", async () => {
    const result = await handleCandidateSetCloseoutTool({
      closeoutKey: "old",
      threadKey: "old-thread",
      userConfirmed: true,
      candidate: {},
      evidenceSegments: [],
    });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.text).not.toBe("LOCAL_CONFIGURATION_MISSING");
  });

  it("fails closed on a valid call when local configuration is absent", async () => {
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", "");
    vi.stubEnv("HIDE_NEST_API_BASE_URL", "");
    const result = await handleCandidateSetCloseoutTool(candidateSetTestRaw("three-create"));
    expect(result).toEqual({ ok: false, text: "LOCAL_CONFIGURATION_MISSING" });
  });

  it("projects a configured CandidateSet success without body, keys or hashes", async () => {
    const server = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
      request.on("end", () => {
        const body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
        const candidates = body.candidates as Array<Record<string, unknown>>;
        response.writeHead(202, { "Content-Type": "application/json" });
        response.end(
          JSON.stringify({
            requestId: "10000000-0000-0000-0000-000000000001",
            resultCategory: "SUCCEEDED",
            candidateSetId: body.candidateSetId,
            reviewSessionId: (request.url ?? "").split("/")[3],
            phase: "INDEX_READY",
            candidates: candidates.map((candidate, index) => ({
              candidateId: candidate.candidateId,
              ordinal: candidate.ordinal,
              disposition: candidate.disposition,
              action: candidate.action,
              phase: "INDEX_READY",
              memoryId: `20000000-0000-0000-0000-${String(index + 1).padStart(12, "0")}`,
              memoryRevisionId: `30000000-0000-0000-0000-${String(index + 1).padStart(12, "0")}`,
              revisionNo: 1,
            })),
          }),
        );
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("server address");
    vi.stubEnv("HIDE_NEST_API_BASE_URL", `http://127.0.0.1:${address.port}`);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_TOKEN", TOKEN);
    vi.stubEnv("HIDE_NEST_SYNTHETIC_CAPABILITY", "must-not-be-read-or-sent");

    const result = await handleCandidateSetCloseoutTool(candidateSetTestRaw("three-create"));
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.result.status).toBe("SET_SAVED");
      expect(result.result.candidates).toHaveLength(3);
      const text = JSON.stringify(result.result);
      expect(text).not.toContain("小林喜欢粉色");
      expect(text).not.toContain("vector-three-create");
      expect(text).not.toContain(TOKEN);
    }
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });
});
