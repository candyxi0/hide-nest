# Task46A-R1｜真实多行证据与 Guidance 文字封口执行报告

## 实施状态

`ITERATION1_FORMAL_MEMORY_MCP_GUIDANCE_R1_READY_FOR_LOCAL_COMMIT`

## 完成内容

- MCP `tools/list` 保持恰好 3 项，公开名改为 `hide_nest_closeout_confirmed`、`hide_nest_retrieve_context_pack`、`hide_nest_get_memory_evidence`；未注册旧 `_synthetic` 名称或 alias。
- 新增 `memory-tool-guidance.ts`，集中导出版本、公共安全规则和三份工具规则；`mcp.ts` 只注册工具、schema、annotation，并组合引用该 guidance。
- Closeout 在正式 MCP handler 的 HTTP 前执行 Local V1 门：任意 `ACCEPTED + REVISE/SUPERSEDE` 稳定拒绝，HTTP 到达为 0；未改变既有 CandidateSet API、投影或后端能力。
- 新增 guidance 单测、正式名 tools/list 断言、真实 stdio + loopback 回归断言，以及 accepted 非 CREATE 前置拒绝测试。

## R1｜多行原文与文字纠偏

- evidence `bodyText` 与 `memoryText` 现在允许并原样保留 Tab、LF、CR、CRLF 和 emoji；不 trim、不规范化、不改写原文。
- NUL、vertical tab、form feed、DEL、C1 控制字符及孤立 surrogate 仍在 input gate 拒绝，攻击请求 HTTP 到达为 0；标识字段继续使用更严格的任意控制字符拒绝规则。
- input、canonical request、`JSON.stringify`、loopback HTTP 解析四层测试均锁定多行原文逐字一致；1 MiB UTF-8 精确边界继续验证。
- closeout guidance 修正为“任一变化均使旧确认失效”，并明确原始换行、CRLF、Tab、emoji 必须保留且不得为通过工具改成单行；`mcp.ts` 顶部说明改为正式 local-private 三工具表述。

## 真实资料与确认边界

该工具没有、也不声称拥有密码学或外部授权系统。`userConfirmed:true` 仅是 hide 对“已展示完整最终集合且小林随后明确确认、参数未变”的正式断言；描述规则、闭合 schema、HTTP 前置门及集成测试共同 fail closed。

正式 closeout 已允许 local-private 真实资料形态（单/多 candidate、共享或多段 evidence），但仍要求原文证据、真实可确认时间和实际 ordinal。`INDEX_READY` 才表示“已保存且可检索”；`CANONICAL_COMMITTED` 只表示规范记忆已保存、向量索引未就绪。

## 兼容性与遗留命名

wire schema、annotation、CandidateSet API 路径及应用/数据库投影链未修改。部分内部文件、测试 fixture 和既有环境配置键继续沿用 `synthetic` 这一历史命名；它们不属于公开 MCP 工具名，也不表示正式工具禁止 local-private 真实资料。

## 验证

| 检查 | 结果 |
| --- | --- |
| Codex Adapter typecheck | PASS |
| Codex Adapter lint | PASS（仅 2 条既有 generated unused-disable warning） |
| Codex Adapter test | PASS：18 文件，294 passed，1 skipped |
| Codex Adapter build | PASS |
| 本轮根 Node typecheck / lint / test / build | NOT_RUN_R1_REUSE_TASK46A_PASS |
| MCP initialize / tools-list / real call loopback | PASS / PASS / PASS |
| `git diff --check` | PASS |
| Guidance 与 tools/list 双层断言 | PASS |
| 真实家庭 API / 部署 | NOT_STARTED |

## 范围与安全

- Java、Maven、OpenAPI、database、generated、React、prototype：修改 0。
- 未访问真实家庭 API，未部署，未 stage、commit 或 push，未联网。
- 成功路径 stdout 仅 MCP 协议；测试覆盖凭据、capability、正文、query 等泄漏防护。
- 既有 QA untracked 临时目录保持不触碰；本工单新增/修改路径均在允许范围内。

## R1A｜Git Scope 机械收口

本轮未修改源码、测试、构建产物或 QA 临时目录；仅补全本报告与 Evidence 的范围清单。排除既有 `reports/local-v1-read-browser-qa/` 后，任务路径为 **8 modified + 4 added + 0 deleted = 12**：

1. `apps/codex-adapter/src/candidate-set-closeout-canonicalizer.test.ts`
2. `apps/codex-adapter/src/candidate-set-closeout-client.test.ts`
3. `apps/codex-adapter/src/candidate-set-closeout-input.test.ts`
4. `apps/codex-adapter/src/candidate-set-closeout-input.ts`
5. `apps/codex-adapter/src/candidate-set-closeout-tool.test.ts`
6. `apps/codex-adapter/src/candidate-set-closeout-tool.ts`
7. `apps/codex-adapter/src/mcp.integration.test.ts`
8. `apps/codex-adapter/src/mcp.ts`
9. `apps/codex-adapter/src/memory-tool-guidance.test.ts`
10. `apps/codex-adapter/src/memory-tool-guidance.ts`
11. `reports/Iteration1-FormalMemoryMcpGuidance-Evidence.json`
12. `reports/Iteration1-FormalMemoryMcpGuidance-执行报告.md`

范围双向核对、三种 JSON 解析、`git diff --check` 与 staged 检查均见 Evidence；本轮测试与构建为 `NOT_RUN_REUSE_TASK46A_R1_PASS`。

## 回执

```text
末端收口状态：ITERATION1_FORMAL_MEMORY_MCP_GUIDANCE_R1A_READY_FOR_LOCAL_COMMIT
Git modified-added-deleted／taskPaths：8-4-0/12
报告-Evidence状态／路径／计数：MATCH/MATCH/MATCH
Node-PowerShell-strict JSON：PASS/PASS/PASS
源码／测试修改：0/0
本轮测试构建：NOT_RUN_REUSE_TASK46A_R1_PASS
diff-whitespace／staged／越界：PASS/0/0
联网／Git写操作：否／否
报告路径：reports/Iteration1-FormalMemoryMcpGuidance-执行报告.md
```
