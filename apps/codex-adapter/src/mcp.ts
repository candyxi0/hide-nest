import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { handleCloseoutTool } from "./closeout-tool.js";

/**
 * Real stdio MCP server exposing the single synthetic closeout tool.
 *
 * stdout carries only MCP protocol frames. Diagnostics are sanitized and written to stderr, and the
 * success path emits none. The server completes initialize/tools-list without any token/capability;
 * the tool call itself fails closed (LOCAL_CONFIGURATION_MISSING) when configuration is absent.
 */

export const TOOL_NAME = "hide_nest_closeout_synthetic_confirmed";

const TOOL_DESCRIPTION =
  "仅在小林已经在当前对话中明确确认后调用；只提交合成候选与被选择的最小证据，不读取或上传完整房间内容；不适用于真实资料或生产记忆。";

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
  ordinal: z.number().int().nonnegative(),
  occurredAt: z.string(),
  bodyText: z.string().min(1),
});

const candidateSchema = z.strictObject({
  perspectiveSpeakerKey: z.string().min(1).max(64),
  memoryType: memoryTypeSchema,
  bodyText: z.string().min(1),
});

const toolInputSchema = z.strictObject({
  closeoutKey: z.string().min(1).max(128),
  threadKey: z.string().min(1).max(128),
  userConfirmed: z.literal(true),
  candidate: candidateSchema,
  evidenceMessages: z.array(evidenceMessageSchema).min(1).max(100),
});

export function createCloseoutServer(): McpServer {
  const server = new McpServer({ name: "hide-nest-codex-adapter", version: "0.0.1" });

  server.registerTool(
    TOOL_NAME,
    {
      title: TOOL_NAME,
      description: TOOL_DESCRIPTION,
      inputSchema: toolInputSchema,
      annotations: {
        readOnlyHint: false,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false,
      },
    },
    async (args) => {
      const outcome = await handleCloseoutTool(args);
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
