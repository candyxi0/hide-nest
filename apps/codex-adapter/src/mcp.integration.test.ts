import { describe, expect, it, beforeAll, afterAll } from "vitest";
import { createServer, type Server } from "node:http";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { resolve, dirname } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { CONTEXT_PACK_TOOL_NAME, TOOL_NAME } from "./mcp.js";

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

const CONTEXT_QUERY_CANARY = "查询canary-QRY789";
const CONTEXT_RETRIEVAL_CANARY = "retrieval-canary-RET456";
const CONTEXT_THREAD_CANARY = "thread-canary-THR123";
const CONTEXT_TURN_CANARY = "turn-canary-TRN456";
const CONTEXT_MEMORY_BODY = "记忆正文canary-CTX-BODY";
const CONTEXT_PROBLEM_CANARY = "CTX_PROBLEM_CANARY_LEAK";

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
    evidenceSegments: [
      {
        messages: [
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
      },
    ],
  };
}

function contextPackArgs(overrides?: Partial<{ retrievalKey: string; turnKey: string; query: string }>) {
  return {
    retrievalKey: overrides?.retrievalKey ?? CONTEXT_RETRIEVAL_CANARY,
    threadKey: CONTEXT_THREAD_CANARY,
    turnKey: overrides?.turnKey ?? CONTEXT_TURN_CANARY,
    query: overrides?.query ?? `${CONTEXT_QUERY_CANARY} 小林最近确认了哪些合成记忆？`,
  };
}

describe("real MCP stdio + loopback gate (6.2)", () => {
  let httpServer: Server;
  let port: number;
  const requests: ObservedRequest[] = [];
  let transport: StdioClientTransport;
  let client: Client;
  const stderrChunks: Buffer[] = [];
  const contextPackReceipts = new Map<
    string,
    { requestId: string; deliveryId: string; memoryId: string; memoryRevisionId: string }
  >();
  const indexReadyRuns = new Set<string>();

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
          if (hideSelection?.bodyText === "TRIGGER_INDEX_READY") {
            indexReadyRuns.add(submissionId);
          }
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
              phase: indexReadyRuns.has(runId) ? "INDEX_READY" : "CANONICAL_COMMITTED",
              retryable: false,
            }),
          );
          return;
        }

        if (req.method === "POST" && req.url === "/v1/context-packs") {
          const idempotencyKey = String(req.headers["idempotency-key"] ?? "");
          const parsed = JSON.parse(body) as Record<string, unknown>;
          const query = String(parsed.query ?? "");

          if (query.includes("TRIGGER_PROBLEM")) {
            res.writeHead(422, { "Content-Type": "application/problem+json" });
            res.end(
              JSON.stringify({
                type: "about:blank",
                title: "Unprocessable",
                status: 422,
                requestId: "req-ctx-123",
                resultCategory: "DENIED",
                failureCode: "REQUEST_SCHEMA_INVALID",
                retryable: false,
                secretCanary: CONTEXT_PROBLEM_CANARY,
              }),
            );
            return;
          }

          let receipt = contextPackReceipts.get(idempotencyKey);
          if (!receipt) {
            receipt = {
              requestId: randomUUID(),
              deliveryId: randomUUID(),
              memoryId: randomUUID(),
              memoryRevisionId: randomUUID(),
            };
            contextPackReceipts.set(idempotencyKey, receipt);
          }

          const noRelevant = query.includes("NO_RELEVANT");
          const resultCategory = noRelevant ? "NO_RELEVANT_RESULT" : "SUCCEEDED";
          const memories = noRelevant
            ? []
            : [
                {
                  memoryId: receipt.memoryId,
                  memoryRevisionId: receipt.memoryRevisionId,
                  revisionNo: 1,
                  policyRevisionNo: 1,
                  memoryType: "INTERPRETATION",
                  bodyText: CONTEXT_MEMORY_BODY,
                  score: 0.48,
                },
              ];
          const policyRevisionSet = memories
            .map((m) => `MEMORY:${m.memoryId}:${m.policyRevisionNo}`)
            .sort();

          res.writeHead(200, { "Content-Type": "application/json" });
          res.end(
            JSON.stringify({
              requestId: receipt.requestId,
              resultCategory,
              deliveryId: receipt.deliveryId,
              threadId: parsed.threadId,
              turnId: parsed.turnId,
              purpose: parsed.purpose,
              policyRevisionSet,
              issuedAt: "2026-08-16T00:00:00.000Z",
              expiresAt: "2026-08-16T00:10:00.000Z",
              budgetLimited: false,
              memories,
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

  it("initialize + tools/list exposes exactly two tools", async () => {
    const list = await client.listTools();
    expect(list.tools).toHaveLength(2);
    const names = list.tools.map((tool) => tool.name).sort();
    expect(names).toEqual([CONTEXT_PACK_TOOL_NAME, TOOL_NAME].sort());
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

  it("closeout tools/call projects INDEX_READY when the vector converges (R2-01)", async () => {
    const args = validArgs();
    args.candidate.bodyText = "TRIGGER_INDEX_READY";
    const result = await client.callTool({ name: TOOL_NAME, arguments: args });
    expect(resultIsError(result)).toBe(false);
    const parsed = JSON.parse(resultText(result)) as Record<string, unknown>;
    expect(parsed.status).toBe("SAVED");
    expect(parsed.phase).toBe("INDEX_READY");
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

  it("context-pack tools/call drives one loopback POST with exact projection", async () => {
    const result = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    expect(resultIsError(result)).toBe(false);

    const text = resultText(result);
    const parsed = JSON.parse(text) as Record<string, unknown>;
    expect(Object.keys(parsed).sort()).toEqual([
      "budgetLimited",
      "deliveryId",
      "expiresAt",
      "issuedAt",
      "memories",
      "requestId",
      "resultCategory",
      "status",
    ]);
    expect(parsed.status).toBe("CONTEXT_READY");
    expect(parsed.resultCategory).toBe("SUCCEEDED");
    expect(parsed.budgetLimited).toBe(false);
    expect(parsed).not.toHaveProperty("threadId");
    expect(parsed).not.toHaveProperty("turnId");
    expect(parsed).not.toHaveProperty("purpose");
    expect(parsed).not.toHaveProperty("policyRevisionSet");

    const memories = parsed.memories as Array<Record<string, unknown>>;
    expect(memories).toHaveLength(1);
    expect(Object.keys(memories[0]).sort()).toEqual([
      "bodyText",
      "memoryId",
      "memoryRevisionId",
      "memoryType",
      "policyRevisionNo",
      "revisionNo",
      "score",
    ]);
    expect(memories[0].bodyText).toBe(CONTEXT_MEMORY_BODY);

    const post = requests.find((r) => r.method === "POST" && r.url === "/v1/context-packs");
    expect(post).toBeDefined();
    expect(post!.headers.authorization).toBe(`Bearer ${TEST_TOKEN}`);
    expect(post!.headers["idempotency-key"]).toBe(CONTEXT_RETRIEVAL_CANARY);
    expect(post!.headers["x-action-capability"]).toBeUndefined();
    expect(post!.headers.cookie).toBeUndefined();

    const sent = JSON.parse(post!.body) as Record<string, unknown>;
    expect(Object.keys(sent).sort()).toEqual(["purpose", "query", "threadId", "turnId"]);
    expect(sent.purpose).toBe("ANSWER_CURRENT_TURN");
    expect(sent.query).toBe(contextPackArgs().query);
  });

  it("same logical request retries identically; new keys send a new POST", async () => {
    const countPosts = () =>
      requests.filter((r) => r.method === "POST" && r.url === "/v1/context-packs").length;

    const first = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    const firstId = JSON.parse(resultText(first)).requestId as string;
    const before = countPosts();

    const retry = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    const retryId = JSON.parse(resultText(retry)).requestId as string;
    expect(retryId).toBe(firstId);
    expect(countPosts()).toBe(before + 1);

    const fresh = await client.callTool({
      name: CONTEXT_PACK_TOOL_NAME,
      arguments: contextPackArgs({
        retrievalKey: CONTEXT_RETRIEVAL_CANARY + "-r2",
        turnKey: CONTEXT_TURN_CANARY + "-t2",
      }),
    });
    const freshId = JSON.parse(resultText(fresh)).requestId as string;
    expect(freshId).not.toBe(firstId);
    expect(countPosts()).toBe(before + 2);

    const posts = requests.filter((r) => r.method === "POST" && r.url === "/v1/context-packs");
    expect(posts[posts.length - 1].headers["idempotency-key"]).toBe(CONTEXT_RETRIEVAL_CANARY + "-r2");
  });

  it("NO_RELEVANT_RESULT is a successful empty result, not an error", async () => {
    const result = await client.callTool({
      name: CONTEXT_PACK_TOOL_NAME,
      arguments: contextPackArgs({ query: "NO_RELEVANT 这段查询没有任何相关记忆" }),
    });
    expect(resultIsError(result)).toBe(false);
    const parsed = JSON.parse(resultText(result)) as Record<string, unknown>;
    expect(parsed.status).toBe("NO_RELEVANT_MEMORY");
    expect(parsed.resultCategory).toBe("NO_RELEVANT_RESULT");
    expect(parsed.memories).toEqual([]);
  });

  it("context-pack problem projection never leaks the canary or query", async () => {
    const result = await client.callTool({
      name: CONTEXT_PACK_TOOL_NAME,
      arguments: contextPackArgs({ query: "TRIGGER_PROBLEM" }),
    });
    expect(resultIsError(result)).toBe(true);
    const text = resultText(result);
    const parsed = JSON.parse(text) as Record<string, unknown>;
    expect(parsed.status).toBe(422);
    expect(parsed.failureCode).toBe("REQUEST_SCHEMA_INVALID");
    expect(parsed.resultCategory).toBe("DENIED");
    expect(parsed.retryable).toBe(false);
    expect(parsed.requestId).toBe("req-ctx-123");
    expect(Object.keys(parsed).sort()).toEqual([
      "failureCode",
      "requestId",
      "resultCategory",
      "retryable",
      "status",
    ]);
    expect(text).not.toContain(CONTEXT_PROBLEM_CANARY);
    expect(text).not.toContain("TRIGGER_PROBLEM");
  });

  it("context-pack success keeps body in result but never leaks query/keys/token to result or stderr", async () => {
    const result = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    const text = resultText(result);
    const stderr = Buffer.concat(stderrChunks).toString("utf8");

    expect(text).toContain(CONTEXT_MEMORY_BODY);
    expect(text).not.toContain(CONTEXT_QUERY_CANARY);
    expect(text).not.toContain(CONTEXT_RETRIEVAL_CANARY);
    expect(text).not.toContain(CONTEXT_THREAD_CANARY);
    expect(text).not.toContain(CONTEXT_TURN_CANARY);
    expect(text).not.toContain(TEST_TOKEN);
    expect(text).not.toContain(TEST_CAPABILITY);

    expect(stderr).not.toContain(CONTEXT_MEMORY_BODY);
    expect(stderr).not.toContain(CONTEXT_QUERY_CANARY);
    expect(stderr).not.toContain(CONTEXT_RETRIEVAL_CANARY);
    expect(stderr).not.toContain(TEST_TOKEN);
    expect(stderr).toBe("");
  });
});
