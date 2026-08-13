import { describe, expect, it, beforeAll, afterAll } from "vitest";
import { createServer, type Server } from "node:http";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { resolve, dirname } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { TOOL_NAME } from "./mcp.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "../../..");
const distMcpJs = resolve(repoRoot, "apps/codex-adapter/dist/mcp.js");
const tsconfig = resolve(repoRoot, "apps/codex-adapter/tsconfig.json");
const tscBin = resolve(repoRoot, "node_modules/typescript/bin/tsc");

const TEST_TOKEN = "synthetic-token-" + "0123456789abcdef".repeat(4);
const TEST_CAPABILITY = "synthetic-capability-" + "0123456789abcdef".repeat(4);
const BODY_CANARY = "正文canary-ABC123";
const EVIDENCE_CANARY = "证据canary-XYZ789";
const PROBLEM_CANARY = "PROBLEM_CANARY_LEAK";

interface ObservedRequest {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
  body: string;
}

interface ToolCallResult {
  content?: Array<{ type?: string; text?: string }>;
  isError?: boolean;
}

function resultText(result: unknown): string {
  const r = result as ToolCallResult;
  const first = r.content?.[0];
  if (first?.type === "text" && typeof first.text === "string") {
    return first.text;
  }
  return "";
}

function resultIsError(result: unknown): boolean {
  return (result as ToolCallResult).isError === true;
}

function validArgs() {
  return {
    closeoutKey: "closeout-key-001",
    threadKey: "thread-key-001",
    userConfirmed: true,
    candidate: {
      perspectiveSpeakerKey: "xiaolin",
      memoryType: "EVENT",
      bodyText: `${BODY_CANARY}确认了合成记忆候选`,
    },
    evidenceMessages: [
      {
        speakerKey: "xiaolin",
        ordinal: 0,
        occurredAt: "2026-08-13T12:00:00+08:00",
        bodyText: `${EVIDENCE_CANARY}一`,
      },
      {
        speakerKey: "hide",
        ordinal: 1,
        occurredAt: "2026-08-13T12:01:00+08:00",
        bodyText: "证据消息二",
      },
    ],
  };
}

describe("real MCP stdio + loopback gate (6.2)", () => {
  let httpServer: Server;
  let port: number;
  const requests: ObservedRequest[] = [];
  let transport: StdioClientTransport;
  let client: Client;
  const stderrChunks: Buffer[] = [];

  beforeAll(async () => {
    const build = spawnSync(process.execPath, [tscBin, "-p", tsconfig], { cwd: repoRoot });
    if (build.status !== 0) {
      throw new Error("dist build failed:\n" + build.stderr.toString());
    }

    httpServer = createServer((req, res) => {
      let body = "";
      req.on("data", (chunk) => (body += chunk));
      req.on("end", () => {
        requests.push({ method: req.method ?? "", url: req.url ?? "", headers: req.headers, body });

        if (req.method === "POST" && req.url === "/v1/closeout-submissions") {
          let parsed: Record<string, unknown> = {};
          try {
            parsed = JSON.parse(body) as Record<string, unknown>;
          } catch {
            /* not JSON */
          }
          const submissionId = String(parsed.submissionId ?? req.headers["idempotency-key"] ?? "");
          const hideSelection = parsed.hideSelection as Record<string, unknown> | undefined;
          if (hideSelection?.bodyText === "TRIGGER_409") {
            res.writeHead(409, { "Content-Type": "application/problem+json" });
            res.end(
              JSON.stringify({
                type: "about:blank",
                title: "Conflict",
                status: 409,
                requestId: "req-123",
                resultCategory: "DENIED",
                failureCode: "IDEMPOTENCY_KEY_REUSED",
                retryable: false,
                secretCanary: PROBLEM_CANARY,
              }),
            );
            return;
          }
          res.writeHead(202, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              requestId: "req-" + submissionId,
              resultCategory: "SUCCEEDED",
              runId: submissionId,
              statusUrl: "/v1/runs/" + submissionId,
              phase: "CANONICAL_COMMITTED",
            }),
          );
          return;
        }

        if (req.method === "GET" && (req.url ?? "").startsWith("/v1/runs/")) {
          const runId = (req.url ?? "").split("/").pop() ?? "";
          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              requestId: "req-" + runId,
              resultCategory: "SUCCEEDED",
              runId,
              phase: "CANONICAL_COMMITTED",
              retryable: false,
            }),
          );
          return;
        }

        res.writeHead(404);
        res.end("{}");
      });
    });
    await new Promise<void>((r) => httpServer.listen(0, "127.0.0.1", () => r()));
    const address = httpServer.address();
    if (address === null || typeof address === "string") throw new Error("no port");
    port = address.port;

    transport = new StdioClientTransport({
      command: process.execPath,
      args: [distMcpJs],
      env: {
        ...process.env,
        HIDE_NEST_API_BASE_URL: `http://127.0.0.1:${port}`,
        HIDE_NEST_SYNTHETIC_TOKEN: TEST_TOKEN,
        HIDE_NEST_SYNTHETIC_CAPABILITY: TEST_CAPABILITY,
      },
      stderr: "pipe",
      cwd: repoRoot,
    });
    transport.stderr?.on("data", (chunk) => stderrChunks.push(Buffer.from(chunk)));

    client = new Client({ name: "test-client", version: "1.0.0" }, { capabilities: {} });
    await client.connect(transport);
  });

  afterAll(async () => {
    await client?.close().catch(() => {});
    await new Promise<void>((r) => httpServer?.close(() => r()));
  });

  it("initialize + tools/list exposes exactly one tool", async () => {
    const list = await client.listTools();
    expect(list.tools).toHaveLength(1);
    expect(list.tools[0].name).toBe(TOOL_NAME);
  });

  it("tools/call returns six safe fields and drives one loopback POST + GET", async () => {
    const result = await client.callTool({ name: TOOL_NAME, arguments: validArgs() });
    expect(resultIsError(result)).toBe(false);

    const text = resultText(result);
    const parsed = JSON.parse(text) as Record<string, unknown>;
    expect(Object.keys(parsed).sort()).toEqual([
      "memoryId",
      "phase",
      "runId",
      "selectedEvidenceCount",
      "status",
      "statusUrl",
    ]);
    expect(parsed.status).toBe("SAVED");
    expect(parsed.phase).toBe("CANONICAL_COMMITTED");
    expect(parsed.selectedEvidenceCount).toBe(2);

    const post = requests.find((r) => r.method === "POST");
    expect(post).toBeDefined();
    const postBody = JSON.parse(post!.body) as Record<string, unknown>;
    const submissionId = postBody.submissionId as string;
    expect(parsed.runId).toBe(submissionId);
    expect(parsed.statusUrl).toBe(`http://127.0.0.1:${port}/v1/runs/${submissionId}`);

    const get = requests.find((r) => r.method === "GET");
    expect(get).toBeDefined();
    expect(get!.url).toBe(`/v1/runs/${submissionId}`);

    // POST headers + closed JSON
    expect(post!.url).toBe("/v1/closeout-submissions");
    expect(post!.headers["idempotency-key"]).toBe(submissionId);
    expect(post!.headers.authorization).toBe(`Bearer ${TEST_TOKEN}`);
    expect(post!.headers["x-action-capability"]).toBe(TEST_CAPABILITY);
    expect(Object.keys(postBody).sort()).toEqual([
      "confirmationProof",
      "hideSelection",
      "sourceAnchors",
      "submissionId",
      "threadId",
      "threadReaderManifest",
      "userConfirmation",
    ]);
  });

  it("success output and stderr never leak token/capability/body canary", async () => {
    const result = await client.callTool({ name: TOOL_NAME, arguments: validArgs() });
    const text = resultText(result);
    const stderr = Buffer.concat(stderrChunks).toString("utf8");

    expect(text).not.toContain(TEST_TOKEN);
    expect(text).not.toContain(TEST_CAPABILITY);
    expect(text).not.toContain(BODY_CANARY);
    expect(text).not.toContain(EVIDENCE_CANARY);
    expect(stderr).not.toContain(TEST_TOKEN);
    expect(stderr).not.toContain(TEST_CAPABILITY);
    expect(stderr).toBe("");
  });

  it("projects only safe Problem fields on a non-2xx response", async () => {
    const args = validArgs();
    args.candidate.bodyText = "TRIGGER_409";
    const result = await client.callTool({ name: TOOL_NAME, arguments: args });
    expect(resultIsError(result)).toBe(true);
    const text = resultText(result);
    const parsed = JSON.parse(text) as Record<string, unknown>;

    expect(parsed.status).toBe(409);
    expect(parsed.failureCode).toBe("IDEMPOTENCY_KEY_REUSED");
    expect(parsed.resultCategory).toBe("DENIED");
    expect(parsed.retryable).toBe(false);
    expect(parsed.requestId).toBe("req-123");
    expect(Object.keys(parsed).sort()).toEqual([
      "failureCode",
      "requestId",
      "resultCategory",
      "retryable",
      "status",
    ]);
    expect(text).not.toContain(PROBLEM_CANARY);
    expect(text).not.toContain("TRIGGER_409");
  });
});
