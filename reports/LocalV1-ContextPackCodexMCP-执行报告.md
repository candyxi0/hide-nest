# Task35A3-R2｜关窗阶段兼容与双工具真实 MCP 烟测执行报告

> 前序：Task35A3（双工具 + ContextPack MCP 适配层）与 Task35A3-R1（响应判定与证据一致性返工）均已通过 hide 复核，本报告在二者之上继续。

## 一、R2 结论与状态

- R2-01 关窗 phase 精确兼容（`CANONICAL_COMMITTED → INDEX_READY` 两阶段、单调绑定）已正确实现并通过全部离线质量门。
- 隧道前置门 5/5 探测通过（HTTP 200、`model=bge-small-zh-v1.5-f16`、`dimension=512`）。
- 同一真实 stdio MCP 进程完成完整真实链烟测：关窗 A/B 均达 `INDEX_READY`、真实语义检索 `A_FIRST` 且 `A_GT_B`、同键重放 EXACT 且审计增量 0、新键新回合形成新检索与新审计事实、禁用 Embedding 后旧键 EXACT 回放且新键 fail closed。

**最终状态：`LOCAL_V1_CONTEXT_PACK_CODEX_MCP_REAL_API_SMOKE_PASS`**

## 二、冻结基线与工作区

| 项 | 值 |
| --- | --- |
| 仓库 | `D:\myproject\hide-nest` |
| 冻结 HEAD | `6cd741eec36b443a08651d9f5302d72759270243`（未变） |
| staged | 0 |
| 起止工作区 | 4 tracked modified + 9 task untracked + 既有 QA 临时目录 |
| 联网 | 仅既有 Tailscale 隧道后的 loopback 端点（embedding） |
| Git 写操作 | 否 |

## 三、R2-01｜关窗 phase 精确兼容（完成）

Task35A1 向量投影后正式 closeout 合法 phase 已扩展为 `INDEX_READY`，既有 `closeout-client.ts` 原先只认 `CANONICAL_COMMITTED`，会在 Embedding 可用时误报 `POST_RECEIPT_MISMATCH`。本轮闭合该缺口：

- 定义闭合 phase 集：`CANONICAL_COMMITTED`、`INDEX_READY`；任何其他字符串、缺失、null、错误类型 fail closed。
- 保持原有 `request.submissionId == POST.runId == GET.runId`、`resultCategory`、`statusUrl` 同源同路径等全部门。
- 单调绑定 phase 矩阵：

| POST phase | GET phase | 结果 |
| --- | --- | --- |
| CANONICAL_COMMITTED | CANONICAL_COMMITTED | PASS |
| CANONICAL_COMMITTED | INDEX_READY | PASS（查询期间向量收敛） |
| INDEX_READY | INDEX_READY | PASS |
| INDEX_READY | CANONICAL_COMMITTED | REJECTED（禁止倒退） |
| 任一未知 phase | 任意 | REJECTED |

- `CloseoutSuccess.phase` 类型精确为 `"CANONICAL_COMMITTED" | "INDEX_READY"`，返回 GET 已复核的真实 phase，绝不把 `INDEX_READY` 降级伪装成 `CANONICAL_COMMITTED`。

改动文件：`closeout-client.ts`（phase 联合类型 + 单调绑定）、`closeout-client.test.ts`（phase 矩阵 + 未知值反证）、`mcp.integration.test.ts`（仅新增 INDEX_READY 成功投影回归，未降低原断言）。

## 四、路径集合（13 项，与 Evidence 一致）

4 个 tracked modified：

- `apps/codex-adapter/src/closeout-client.ts`
- `apps/codex-adapter/src/closeout-client.test.ts`
- `apps/codex-adapter/src/mcp.integration.test.ts`
- `apps/codex-adapter/src/mcp.ts`

7 个新增源码/测试：

- `apps/codex-adapter/src/context-pack-canonicalizer.ts`
- `apps/codex-adapter/src/context-pack-canonicalizer.test.ts`
- `apps/codex-adapter/src/context-pack-input.ts`
- `apps/codex-adapter/src/context-pack-input.test.ts`
- `apps/codex-adapter/src/context-pack-client.ts`
- `apps/codex-adapter/src/context-pack-client.test.ts`
- `apps/codex-adapter/src/context-pack-tool.ts`

2 份报告：

- `reports/LocalV1-ContextPackCodexMCP-执行报告.md`
- `reports/LocalV1-ContextPackCodexMCP-Evidence.json`

## 五、真实烟测（完整 PASS）

### 5.1 隧道前置门（5/5）

连续 5 次探测隧道 `/health`（每次间隔 2s），全部 HTTP 200、`model=bge-small-zh-v1.5-f16`、`dimension=512`。

### 5.2 一次性环境（已建立并已清理）

- pinned pgvector PostgreSQL（`pgvector/pgvector@sha256:2ac2c62a…5c73c`，`--pull` 未触发），随机 loopback 端口、一次性密码、`--tmpfs` 数据目录。
- Flyway 精确应用 V001—V019：`flyway_schema_history` 成功迁移数 = 19。
- 以预构建 exec jar + JDK 25 启动当前工作树真实 API，profile `local-v1-synthetic`，`server.address=127.0.0.1`，随机端口，`HIDE_NEST_EMBEDDING_BASE_URL` 指向既有 Tailscale 隧道 loopback 端点。
- 通过官方 MCP SDK 启动一个真实 stdio MCP 进程（`apps/codex-adapter/dist/mcp.js`）。
- token/capability/数据库密码仅经子进程环境注入，未进入命令行/文件/stdout/stderr。

### 5.3 真实链事实（仅合成标题/ID 截断值/score/计数/耗时，未记 query 或正文）

| 环节 | 结果 |
| --- | --- |
| MCP initialize / tools-list | PASS / PASS（精确两个工具） |
| MCP stderr（成功路径） | 空（0 字节） |
| 关窗 A（粉色偏好） | `status=SAVED`、`phase=INDEX_READY`、memoryId 前缀 `14096623…` |
| 关窗 B（家庭服务器预算） | `status=SAVED`、`phase=INDEX_READY`、memoryId 前缀 `f929f82c…` |
| 数据库事实（关窗后） | 活跃记忆 2、向量事实 2、每 revision 向量事实 1-1、memoryId 与 DB 精确一致 |
| ContextPack 语义检索 | `status=CONTEXT_READY`、`resultCategory=SUCCEEDED`、A_FIRST、A_GT_B |
| 检索分数 | score(A)=0.6345、score(B)=0.2149（A > B） |
| 检索审计事实 | trace-delivery-item-receipt = 1-1-2-1 |
| 同键重放 | EXACT（requestId/deliveryId/顺序/分数逐字一致），审计增量 0-0-0-0 |
| 新 retrievalKey + 新 turnKey | FRESH（新 requestId/deliveryId），新审计事实 1 组（trace/delivery/item/receipt → 2/2/4/2） |
| 禁用 Embedding 旧键回放 | EXACT，审计增量 0 |
| 禁用 Embedding 新键检索 | REJECTED（`MODEL_PROVIDER_UNAVAILABLE`，fail closed，审计增量 0） |

## 六、测试与质量门

动态测试计数：codex-adapter 9 文件 **149 passed / 1 skipped / 0 failed**。

| 门 | 结果 |
| --- | --- |
| Codex Adapter typecheck / lint / test / build | PASS / PASS / PASS / PASS |
| 根 Node 四门（typecheck / lint / test / build） | PASS / PASS / PASS / PASS |
| `git diff --check` | PASS（exit 0，仅 LF→CRLF 换行提示） |
| 关窗 phase 矩阵 / 倒退-未知攻击 | PASS / REJECTED |
| INDEX_READY MCP 成功投影回归 | PASS |

Maven 仅用于一次性 API 构建/启动，未把全仓 `clean verify` 冒充本轮新验收；Java/数据库正式源码 0 改动。

## 七、安全、泄漏与清理

- ContextPack MCP 请求不发送 capability/Cookie；closeout MCP 仅在正式写入口发送 capability。
- 成功路径 stderr 为空；非测试构建产物无 token/capability/query/key/正文 canary 泄漏。
- 一次性环境已全部清理：`docker rm -f` 一次性容器；停 API；关 MCP 子进程；删除一次性 payload 目录与烟测 harness。
- 清理后：新增 Docker containers/volumes/networks = 0/0/0；遗留 Java/Node 子进程 = 0（未停止小林手工 SSH 隧道）。
- Git index 起止 MATCH；除 Task35A3 允许路径与既有 QA 临时目录外无变化；staged=0、越界=0。

## 八、完成状态

```text
烟测状态：LOCAL_V1_CONTEXT_PACK_CODEX_MCP_REAL_API_SMOKE_PASS
MCP initialize／tools-list：PASS/PASS
closeout A-B／phase／向量事实：PASS/INDEX_READY/1-1
context-pack／A-B排序／分数：PASS/A_FIRST/A_GT_B
同请求重试／embedding-审计增量：EXACT/0-0
同query新回合／新delivery-新审计：FRESH/1-1
禁用Embedding旧键回放／新键检索：EXACT/REJECTED
关窗phase矩阵／倒退-未知攻击：PASS/REJECTED
trace-delivery-item-receipt：1-1-2-1
浏览器凭据／context capability／秘密泄漏：0/0/0
Codex Adapter四门／Node四门：PASS/PASS
Java-数据库-契约-React改动：0-0-0-0
报告-Evidence状态／路径／计数：MATCH/MATCH/MATCH
真实Git index／staged／越界：MATCH/0/0
Docker新增containers-volumes-networks／payload／遗留进程：0-0-0/0/0
联网：仅既有Tailscale隧道后的127.0.0.1
Git写操作：否
报告路径：D:\myproject\hide-nest\reports\LocalV1-ContextPackCodexMCP-执行报告.md
```

完成即停，等待 hide 复核。未 stage、commit、push，未开始后续任务。
