import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { handleCandidateSetCloseoutTool } from "./candidate-set-closeout-tool.js";
import { handleContextPackTool } from "./context-pack-tool.js";
import { handleMemoryEvidenceTool } from "./memory-evidence-tool.js";
import {
  CLOSEOUT_TOOL_DESCRIPTION,
  CONTEXT_PACK_TOOL_DESCRIPTION,
  MEMORY_EVIDENCE_TOOL_DESCRIPTION,
} from "./memory-tool-guidance.js";

/**
 * Real stdio MCP server exposing three formal local-private memory tools: closeout, context-pack
 * retrieval and evidence.
 *
 * stdout carries only MCP protocol frames. Diagnostics are sanitized and written to stderr, and the
 * success path emits none. The server completes initialize/tools-list without any token;
 * a tool call itself fails closed (LOCAL_CONFIGURATION_MISSING) when configuration is absent.
 */

export const TOOL_NAME = "hide_nest_closeout_confirmed";
export const CONTEXT_PACK_TOOL_NAME = "hide_nest_retrieve_context_pack";
export const MEMORY_EVIDENCE_TOOL_NAME = "hide_nest_get_memory_evidence";


const memoryTypeSchema = z.enum([
  "EVENT",
  "CLAIM",
  "QUOTE",
  "INTERPRETATION",
  "CALIBRATION",
  "PRINCIPLE",
]);

const evidenceMessageSchema = z.strictObject({
  speakerKey: z.string().min(1).max(64),
  speakerRole: z.enum(["XIAOLIN", "HIDE"]),
  ordinal: z.number().int().nonnegative(),
  occurredAt: z.string(),
  bodyText: z.string().min(1),
});

const evidenceSegmentSchema = z.strictObject({
  messages: z.array(evidenceMessageSchema).min(1).max(100),
});

const candidateSchema = z.strictObject({
  candidateKey: z.string().min(1).max(128),
  disposition: z.enum(["ACCEPTED", "REJECTED"]),
  action: z.enum(["CREATE", "REVISE", "SUPERSEDE"]),
  originKind: z.enum(["HIDE_PROPOSED", "USER_EDITED", "USER_ADDED"]),
  finalAuthorKind: z.enum(["HIDE", "USER"]),
  perspectiveSpeakerKey: z.string().min(1).max(64),
  memoryText: z.string().max(16000).nullable(),
  memoryType: memoryTypeSchema.nullable(),
  evidenceSegmentIndexes: z.array(z.number().int().min(1).max(Number.MAX_SAFE_INTEGER)).max(100),
  targetMemoryId: z.string().uuid().nullable(),
  expectedMemoryRevisionId: z.string().uuid().nullable(),
  expectedRevisionNo: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).nullable(),
  expectedPolicyRevisionNo: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER).nullable(),
  hideReason: z.string().max(1000).nullable(),
});

const toolInputSchema = z.strictObject({
  candidateSetKey: z.string().min(1).max(128),
  threadKey: z.string().min(1).max(128),
  scopeRef: z.string().min(1).max(256),
  setVersion: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
  userConfirmed: z.literal(true),
  evidenceSegments: z.array(evidenceSegmentSchema).min(1).max(100),
  candidates: z.array(candidateSchema).max(8),
});

const contextPackToolInputSchema = z.strictObject({
  retrievalKey: z.string().min(1),
  threadKey: z.string().min(1),
  turnKey: z.string().min(1),
  query: z.string().min(1),
  maxResults: z.number().int().min(1).max(5).optional(),
  minScore: z.number().min(0.4).max(1.0).optional(),
});


const memoryEvidenceToolInputSchema = z.strictObject({
  memoryId: z.string().uuid(),
  memoryRevisionId: z.string().uuid(),
  revisionNo: z.number().int().min(1).max(Number.MAX_SAFE_INTEGER),
});

export function createCloseoutServer(): McpServer {
  const server = new McpServer({ name: "hide-nest-codex-adapter", version: "0.0.1" });

  server.registerTool(
    TOOL_NAME,
    {
      title: TOOL_NAME,
      description: CLOSEOUT_TOOL_DESCRIPTION,
      inputSchema: toolInputSchema,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async (args) => {
      const outcome = await handleCandidateSetCloseoutTool(args);
      if (outcome.ok) {
        return {
          content: [{ type: "text", text: JSON.stringify(outcome.result) }],
        };
      }
      return {
        content: [{ type: "text", text: outcome.text }],
        isError: true,
      };
    },
  );

  server.registerTool(
    CONTEXT_PACK_TOOL_NAME,
    {
      title: CONTEXT_PACK_TOOL_NAME,
      description: CONTEXT_PACK_TOOL_DESCRIPTION,
      inputSchema: contextPackToolInputSchema,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async (args) => {
      const outcome = await handleContextPackTool(args);
      if (outcome.ok) {
        return {
          content: [{ type: "text", text: JSON.stringify(outcome.result) }],
        };
      }
      return {
        content: [{ type: "text", text: outcome.text }],
        isError: true,
      };
    },
  );

  server.registerTool(
    MEMORY_EVIDENCE_TOOL_NAME,
    {
      title: MEMORY_EVIDENCE_TOOL_NAME,
      description: MEMORY_EVIDENCE_TOOL_DESCRIPTION,
      inputSchema: memoryEvidenceToolInputSchema,
      annotations: {
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async (args) => {
      const outcome = await handleMemoryEvidenceTool(args);
      if (outcome.ok) {
        return {
          content: [{ type: "text", text: JSON.stringify(outcome.result) }],
        };
      }
      return {
        content: [{ type: "text", text: outcome.text }],
        isError: true,
      };
    },
  );

  return server;
}

/** Starts the stdio MCP server. Resolves once the transport is connected. */
export async function runMcp(): Promise<void> {
  const server = createCloseoutServer();
  const transport = new StdioServerTransport();
  await server.connect(transport);
}

function isMainModule(): boolean {
  const entry = process.argv[1];
  if (!entry) return false;
  return resolve(entry) === fileURLToPath(import.meta.url);
}

if (isMainModule()) {
  runMcp().catch(() => {
    process.stderr.write("hide-nest-codex-adapter: MCP server failed to start\n");
    process.exit(1);
  });
  process.on("SIGINT", () => {
    process.exit(0);
  });
}
