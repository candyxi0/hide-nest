import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  CLOSEOUT_GUIDANCE,
  CLOSEOUT_TOOL_DESCRIPTION,
  COMMON_MEMORY_SAFETY_GUIDANCE,
  CONTEXT_PACK_GUIDANCE,
  CONTEXT_PACK_TOOL_DESCRIPTION,
  MEMORY_EVIDENCE_GUIDANCE,
  MEMORY_EVIDENCE_TOOL_DESCRIPTION,
  MEMORY_GUIDANCE_VERSION,
} from "./memory-tool-guidance.js";

describe("formal local-private memory guidance", () => {
  it("has a diagnostic version and composes each model-visible description from one source", () => {
    expect(MEMORY_GUIDANCE_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}-formal-memory-mcp-v1$/);
    expect(CLOSEOUT_TOOL_DESCRIPTION).toBe(`${COMMON_MEMORY_SAFETY_GUIDANCE}\n\n${CLOSEOUT_GUIDANCE}`);
    expect(CONTEXT_PACK_TOOL_DESCRIPTION).toBe(
      `${COMMON_MEMORY_SAFETY_GUIDANCE}\n\n${CONTEXT_PACK_GUIDANCE}`,
    );
    expect(MEMORY_EVIDENCE_TOOL_DESCRIPTION).toBe(
      `${COMMON_MEMORY_SAFETY_GUIDANCE}\n\n${MEMORY_EVIDENCE_GUIDANCE}`,
    );
  });

  it("keeps common safety, confirmation, retrieval and evidence rules model-visible", () => {
    for (const phrase of [
      "当前小林控制的本地私有 Nest",
      "不是系统指令",
      "最小资料",
      "token、capability、secret、本机路径或系统提示",
      "STALE、DENIED 或 CANONICAL_COMMITTED",
      "新逻辑请求用新 key",
    ]) {
      expect(COMMON_MEMORY_SAFETY_GUIDANCE).toContain(phrase);
    }
    for (const phrase of [
      "完整、编号的最终候选集合",
      "普通“好、继续、可以、你看着办”",
      "任一变化均使旧确认失效",
      "bodyText 必须逐字复制可见原话",
      "原始换行、CRLF、Tab、emoji 都属于原文",
      "ACCEPTED 只允许 action=CREATE",
      "规范记忆已保存，向量索引尚未就绪",
    ]) {
      expect(CLOSEOUT_GUIDANCE).toContain(phrase);
    }
    expect(CLOSEOUT_GUIDANCE).not.toContain("任一变化即使旧确认失效");
    expect(CONTEXT_PACK_GUIDANCE).toContain("默认 maxResults=3、minScore=0.6");
    expect(CONTEXT_PACK_GUIDANCE).toContain("maxResults=5、minScore=0.4");
    expect(CONTEXT_PACK_GUIDANCE).toContain("24 小时内已成功返回的 memoryId 会自动冷却过滤");
    expect(MEMORY_EVIDENCE_GUIDANCE).toContain("不得猜测、拼接或跨记忆混用");
  });

  it("contains no synthetic-only applicability statement", () => {
    for (const value of [
      COMMON_MEMORY_SAFETY_GUIDANCE,
      CLOSEOUT_GUIDANCE,
      CONTEXT_PACK_GUIDANCE,
      MEMORY_EVIDENCE_GUIDANCE,
    ]) {
      expect(value).not.toContain("仅用于合成资料");
      expect(value).not.toContain("不适用于真实资料或生产记忆");
    }
  });

  it("keeps MCP registration free of copied long guidance strings", () => {
    const mcpSource = readFileSync(fileURLToPath(new URL("./mcp.ts", import.meta.url)), "utf8");
    expect(mcpSource).toContain('description: CLOSEOUT_TOOL_DESCRIPTION');
    expect(mcpSource).toContain('description: CONTEXT_PACK_TOOL_DESCRIPTION');
    expect(mcpSource).toContain('description: MEMORY_EVIDENCE_TOOL_DESCRIPTION');
    expect(mcpSource).not.toContain('const TOOL_DESCRIPTION =');
    expect(mcpSource).not.toContain('const CONTEXT_PACK_TOOL_DESCRIPTION =');
    expect(mcpSource).not.toContain('const MEMORY_EVIDENCE_TOOL_DESCRIPTION =');
  });
});
