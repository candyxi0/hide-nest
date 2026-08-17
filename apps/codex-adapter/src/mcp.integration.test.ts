import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { createServer, type Server } from "node:http";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
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
  const first = (result as ToolCallResult).content?.[0];
  return first?.type === "text" && typeof first.text === "string" ? first.text : "";
}

function resultIsError(result: unknown): boolean {
  return (result as ToolCallResult).isError === true;
}

function createCandidate(candidateKey: string, memoryText: string, segmentIndexes: number[]) {
  return {
    candidateKey,
    disposition: "ACCEPTED",
    action: "CREATE",
    originKind: "HIDE_PROPOSED",
    finalAuthorKind: "HIDE",
    perspectiveSpeakerKey: "xiaolin",
    memoryText,
    memoryType: "CLAIM",
    evidenceSegmentIndexes: segmentIndexes,
    targetMemoryId: null,
    expectedMemoryRevisionId: null,
    expectedRevisionNo: null,
    expectedPolicyRevisionNo: null,
    hideReason: null,
  };
}

function validArgs(overrides: Record<string, unknown> = {}) {
  return {
    candidateSetKey: "candidate-set-key-001",
    threadKey: "thread-key-001",
    scopeRef: "synthetic/mcp-integration",
    setVersion: 1,
    userConfirmed: true,
    evidenceSegments: [
      {
        messages: [
          {
            speakerKey: "xiaolin",
            ordinal: 10,
            occurredAt: "2026-08-17T12:00:00+08:00",
            bodyText: `${EVIDENCE_CANARY}一`,
          },
          {
            speakerKey: "hide",
            ordinal: 11,
            occurredAt: "2026-08-17T12:00:01+08:00",
            bodyText: "合成证据二",
          },
          {
            speakerKey: "xiaolin",
            ordinal: 12,
            occurredAt: "2026-08-17T12:00:02+08:00",
            bodyText: "合成证据三",
          },
        ],
      },
      {
        messages: [
          {
            speakerKey: "xiaolin",
            ordinal: 20,
            occurredAt: "2026-08-17T12:00:20+08:00",
            bodyText: "合成证据四",
          },
        ],
      },
    ],
    candidates: [
      createCandidate("pink", `${BODY_CANARY}小林喜欢粉色`, [1]),
      createCandidate("server", "下周准备购买家庭服务器", [1, 2]),
      createCandidate("budget", "购买预算不超过 3000 元", [2]),
    ],
    ...overrides,
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

describe("real MCP stdio + loopback CandidateSet gate", () => {
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
  const candidateResources = new Map<
    string,
    Map<string, { memoryId: string; memoryRevisionId: string }>
  >();

  beforeAll(async () => {
    const build = spawnSync(process.execPath, [tscBin, "-p", tsconfig], { cwd: repoRoot });
    if (build.status !== 0) {
      throw new Error(`dist build failed:\n${build.stdout.toString()}\n${build.stderr.toString()}`);
    }

    httpServer = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
      request.on("end", () => {
        const body = Buffer.concat(chunks).toString("utf8");
        requests.push({
          method: request.method ?? "",
          url: request.url ?? "",
          headers: request.headers,
          body,
        });

        if (
          request.method === "POST" &&
          /^\/v1\/review-sessions\/[0-9a-f-]+\/final-submissions$/.test(request.url ?? "")
        ) {
          const parsed = JSON.parse(body) as Record<string, unknown>;
          if (parsed.scopeRef === "TRIGGER_409") {
            response.writeHead(409, { "Content-Type": "application/problem+json" });
            response.end(
              JSON.stringify({
                status: 409,
                requestId: randomUUID(),
                resultCategory: "DENIED",
                failureCode: "IDEMPOTENCY_KEY_REUSED",
                retryable: false,
                secretCanary: PROBLEM_CANARY,
              }),
            );
            return;
          }
          const candidateSetId = String(parsed.candidateSetId);
          const candidates = parsed.candidates as Array<Record<string, unknown>>;
          const reviewSessionId = (request.url ?? "").split("/")[3];
          const accepted = candidates.filter((candidate) => candidate.disposition === "ACCEPTED");
          const hasNonCreate = accepted.some((candidate) => candidate.action !== "CREATE");
          const allRejected = candidates.length > 0 && accepted.length === 0;
          const empty = candidates.length === 0;
          const phase = empty
            ? "NO_CANDIDATES"
            : allRejected || hasNonCreate
              ? "DECISIONS_COMMITTED"
              : "INDEX_READY";
          let resources = candidateResources.get(candidateSetId);
          if (!resources) {
            resources = new Map();
            candidateResources.set(candidateSetId, resources);
          }
          const items = candidates.map((candidate) => {
            const base = {
              candidateId: candidate.candidateId,
              ordinal: candidate.ordinal,
              disposition: candidate.disposition,
              action: candidate.action,
            };
            if (candidate.disposition === "REJECTED") return { ...base, phase: "REJECTED" };
            if (phase === "DECISIONS_COMMITTED") return { ...base, phase };
            const candidateId = String(candidate.candidateId);
            let resource = resources?.get(candidateId);
            if (!resource) {
              resource = { memoryId: randomUUID(), memoryRevisionId: randomUUID() };
              resources?.set(candidateId, resource);
            }
            return { ...base, phase, ...resource, revisionNo: 1 };
          });
          const receipt: Record<string, unknown> = {
            requestId: randomUUID(),
            resultCategory: allRejected ? "NO_RELEVANT_RESULT" : "SUCCEEDED",
            candidateSetId,
            phase,
            candidates: items,
          };
          if (!empty) receipt.reviewSessionId = reviewSessionId;
          response.writeHead(202, { "Content-Type": "application/json" });
          response.end(JSON.stringify(receipt));
          return;
        }

        if (request.method === "POST" && request.url === "/v1/context-packs") {
          const idempotencyKey = String(request.headers["idempotency-key"] ?? "");
          const parsed = JSON.parse(body) as Record<string, unknown>;
          const query = String(parsed.query ?? "");
          if (query.includes("TRIGGER_PROBLEM")) {
            response.writeHead(422, { "Content-Type": "application/problem+json" });
            response.end(
              JSON.stringify({
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
          response.writeHead(200, { "Content-Type": "application/json" });
          response.end(
            JSON.stringify({
              requestId: receipt.requestId,
              resultCategory: noRelevant ? "NO_RELEVANT_RESULT" : "SUCCEEDED",
              deliveryId: receipt.deliveryId,
              threadId: parsed.threadId,
              turnId: parsed.turnId,
              purpose: parsed.purpose,
              policyRevisionSet: memories.map(
                (memory) => `MEMORY:${memory.memoryId}:${memory.policyRevisionNo}`,
              ),
              issuedAt: "2026-08-16T00:00:00.000Z",
              expiresAt: "2026-08-16T00:10:00.000Z",
              budgetLimited: false,
              memories,
            }),
          );
          return;
        }

        response.writeHead(404, { "Content-Type": "application/json" });
        response.end("{}");
      });
    });
    await new Promise<void>((resolveListen) => httpServer.listen(0, "127.0.0.1", resolveListen));
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
    await client?.close().catch(() => undefined);
    httpServer?.closeAllConnections();
    await new Promise<void>((resolveClose) => httpServer?.close(() => resolveClose()));
  });

  it("initialize + tools/list exposes exactly two closed tools with frozen annotations", async () => {
    const list = await client.listTools();
    expect(list.tools.map((tool) => tool.name).sort()).toEqual(
      [CONTEXT_PACK_TOOL_NAME, TOOL_NAME].sort(),
    );
    const candidateTool = list.tools.find((tool) => tool.name === TOOL_NAME);
    expect(candidateTool?.inputSchema.additionalProperties).toBe(false);
    const properties = candidateTool?.inputSchema.properties as Record<string, unknown>;
    expect(Object.keys(properties).sort()).toEqual([
      "candidateSetKey",
      "candidates",
      "evidenceSegments",
      "scopeRef",
      "setVersion",
      "threadKey",
      "userConfirmed",
    ]);
    expect(candidateTool?.annotations).toEqual({
      readOnlyHint: false,
      destructiveHint: false,
      idempotentHint: true,
      openWorldHint: false,
    });
    expect(candidateTool?.description).toContain("Embedding 不负责切分");
    expect(candidateTool?.description).toContain("最小必要证据段");
  });

  it("CandidateSet tools/call returns only safe fields and drives one real loopback POST", async () => {
    const before = requests.length;
    const result = await client.callTool({ name: TOOL_NAME, arguments: validArgs() });
    expect(resultIsError(result)).toBe(false);
    const parsed = JSON.parse(resultText(result)) as Record<string, unknown>;
    expect(Object.keys(parsed).sort()).toEqual([
      "acceptedCount",
      "candidateSetId",
      "candidates",
      "phase",
      "rejectedCount",
      "resultCategory",
      "reviewSessionId",
      "status",
    ]);
    expect(parsed.status).toBe("SET_SAVED");
    expect(parsed.phase).toBe("INDEX_READY");
    expect(parsed.acceptedCount).toBe(3);
    expect(parsed.rejectedCount).toBe(0);
    const candidateItems = parsed.candidates as Array<Record<string, unknown>>;
    expect(candidateItems).toHaveLength(3);
    expect(candidateItems.map((item) => item.ordinal)).toEqual([1, 2, 3]);

    const added = requests.slice(before);
    expect(added).toHaveLength(1);
    const post = added[0];
    expect(post.url).toMatch(/^\/v1\/review-sessions\/[0-9a-f-]+\/final-submissions$/);
    expect(post.headers.authorization).toBe(`Bearer ${TEST_TOKEN}`);
    expect(post.headers["idempotency-key"]).toBe(parsed.candidateSetId);
    expect(post.headers["x-action-capability"]).toBeUndefined();
    expect(post.headers.cookie).toBeUndefined();
    expect(Object.keys(JSON.parse(post.body) as Record<string, unknown>).sort()).toEqual([
      "candidateSetId",
      "candidates",
      "evidencePool",
      "finalConfirmation",
      "requestHash",
      "scopeRef",
      "setVersion",
      "threadId",
    ]);
  });

  it("old single-memory arguments fail before HTTP and no third tool exists", async () => {
    const before = requests.length;
    const result = await client.callTool({
      name: TOOL_NAME,
      arguments: {
        closeoutKey: "old-key",
        threadKey: "old-thread",
        userConfirmed: true,
        candidate: {},
        evidenceSegments: [],
      },
    });
    expect(resultIsError(result)).toBe(true);
    expect(requests).toHaveLength(before);
    expect((await client.listTools()).tools).toHaveLength(2);
  });

  it("CandidateSet safe success and stderr never leak body/key/token/capability/hash", async () => {
    const result = await client.callTool({
      name: TOOL_NAME,
      arguments: validArgs({ candidateSetKey: "candidate-set-key-leak-check" }),
    });
    const text = resultText(result);
    const stderr = Buffer.concat(stderrChunks).toString("utf8");
    for (const canary of [
      BODY_CANARY,
      EVIDENCE_CANARY,
      "candidate-set-key-leak-check",
      TEST_TOKEN,
      TEST_CAPABILITY,
    ]) {
      expect(text).not.toContain(canary);
      expect(stderr).not.toContain(canary);
    }
    expect(stderr).toBe("");
  });

  it("projects only safe Problem fields", async () => {
    const result = await client.callTool({
      name: TOOL_NAME,
      arguments: validArgs({ candidateSetKey: "candidate-set-key-conflict", scopeRef: "TRIGGER_409" }),
    });
    expect(resultIsError(result)).toBe(true);
    const parsed = JSON.parse(resultText(result)) as Record<string, unknown>;
    expect(Object.keys(parsed).sort()).toEqual([
      "failureCode",
      "requestId",
      "resultCategory",
      "retryable",
      "status",
    ]);
    expect(parsed.status).toBe(409);
    expect(parsed.failureCode).toBe("IDEMPOTENCY_KEY_REUSED");
    expect(resultText(result)).not.toContain(PROBLEM_CANARY);
  });

  it("context-pack tools/call remains a real loopback POST with exact safe projection", async () => {
    const result = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    expect(resultIsError(result)).toBe(false);
    const parsed = JSON.parse(resultText(result)) as Record<string, unknown>;
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
    expect((parsed.memories as Array<Record<string, unknown>>)[0].bodyText).toBe(CONTEXT_MEMORY_BODY);
    const post = requests.find(
      (request) => request.method === "POST" && request.url === "/v1/context-packs",
    );
    expect(post?.headers.authorization).toBe(`Bearer ${TEST_TOKEN}`);
    expect(post?.headers["x-action-capability"]).toBeUndefined();
    expect(post?.headers.cookie).toBeUndefined();
  });

  it("context-pack replay/new-key and NO_RELEVANT_RESULT semantics are unchanged", async () => {
    const first = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    const retry = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    expect(JSON.parse(resultText(retry)).requestId).toBe(JSON.parse(resultText(first)).requestId);
    const fresh = await client.callTool({
      name: CONTEXT_PACK_TOOL_NAME,
      arguments: contextPackArgs({
        retrievalKey: `${CONTEXT_RETRIEVAL_CANARY}-new`,
        turnKey: `${CONTEXT_TURN_CANARY}-new`,
      }),
    });
    expect(JSON.parse(resultText(fresh)).requestId).not.toBe(JSON.parse(resultText(first)).requestId);
    const empty = await client.callTool({
      name: CONTEXT_PACK_TOOL_NAME,
      arguments: contextPackArgs({ query: "NO_RELEVANT 合成空结果" }),
    });
    expect(resultIsError(empty)).toBe(false);
    expect(JSON.parse(resultText(empty)).memories).toEqual([]);
  });

  it("context-pack error/result leak hygiene remains unchanged", async () => {
    const problem = await client.callTool({
      name: CONTEXT_PACK_TOOL_NAME,
      arguments: contextPackArgs({ query: "TRIGGER_PROBLEM" }),
    });
    expect(resultIsError(problem)).toBe(true);
    expect(resultText(problem)).not.toContain(CONTEXT_PROBLEM_CANARY);

    const success = await client.callTool({ name: CONTEXT_PACK_TOOL_NAME, arguments: contextPackArgs() });
    const text = resultText(success);
    expect(text).toContain(CONTEXT_MEMORY_BODY);
    for (const canary of [
      CONTEXT_QUERY_CANARY,
      CONTEXT_RETRIEVAL_CANARY,
      CONTEXT_THREAD_CANARY,
      CONTEXT_TURN_CANARY,
      TEST_TOKEN,
      TEST_CAPABILITY,
    ]) {
      expect(text).not.toContain(canary);
    }
    expect(Buffer.concat(stderrChunks).toString("utf8")).toBe("");
  });

  it("initialize/tools-list succeeds without token; tools/call then fails closed", async () => {
    const cleanEnv = Object.fromEntries(
      Object.entries(process.env).filter(
        ([key, value]) =>
          value !== undefined &&
          key !== "HIDE_NEST_SYNTHETIC_TOKEN" &&
          key !== "HIDE_NEST_SYNTHETIC_CAPABILITY" &&
          key !== "HIDE_NEST_API_BASE_URL",
      ),
    ) as Record<string, string>;
    const noConfigTransport = new StdioClientTransport({
      command: process.execPath,
      args: [distMcpJs],
      env: cleanEnv,
      stderr: "pipe",
      cwd: repoRoot,
    });
    const noConfigStderr: Buffer[] = [];
    noConfigTransport.stderr?.on("data", (chunk) => noConfigStderr.push(Buffer.from(chunk)));
    const noConfigClient = new Client({ name: "no-config", version: "1.0.0" }, { capabilities: {} });
    await noConfigClient.connect(noConfigTransport);
    expect((await noConfigClient.listTools()).tools).toHaveLength(2);
    const result = await noConfigClient.callTool({ name: TOOL_NAME, arguments: validArgs() });
    expect(resultIsError(result)).toBe(true);
    expect(resultText(result)).toBe("LOCAL_CONFIGURATION_MISSING");
    expect(Buffer.concat(noConfigStderr).toString("utf8")).toBe("");
    await noConfigClient.close();
  });
});
