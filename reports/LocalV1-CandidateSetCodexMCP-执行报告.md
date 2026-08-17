# Local V1 CandidateSet Codex MCP 执行报告

状态：`LOCAL_V1_CANDIDATE_SET_CODEX_MCP_REAL_API_SMOKE_PASS_READY_FOR_HIDE_REVIEW`

生成时间：2026-08-17T17:25:27.1010185+08:00

## 1. 结论

CandidateSet 批量关窗已通过真实 `stdio MCP → HTTP API → PostgreSQL V001—V020 → loopback Embedding → context-pack` 纵向烟测。MCP initialize、tools/list、批量关窗工具、既有 context-pack 工具、同值重放、异值冲突、并发、向量失败隔离与恢复均通过；一次性环境已完整清理。

真实烟测同时发现并关闭了一处产品缺陷：同一房间内连续提交两个 CandidateSet 时，确定性小林 actor 身份会被第二次裸 INSERT，触发 `actor_ref` 主键冲突并被错误投影为 `INTERNAL_FAILURE`。修复后，既有相同身份原子复用；相同 actorId 但身份字段不一致时 fail closed 为既有 `REQUEST_SCHEMA_INVALID`，不新增正式故障码。

## 2. 质量门

| 门 | 结果 |
|---|---|
| CandidateSet 真库 targeted | 31/31 PASS |
| Maven JDK 25 offline `clean verify` | BUILD SUCCESS；13/13 modules |
| 全仓 Surefire XML | 50 files；592 tests；0 failures；0 errors；1 skipped |
| 架构门 | 29/29 PASS（PortBoundary 13/13） |
| Codex Adapter 四门 | PASS/PASS/PASS/PASS；196 passed，1 skipped |
| 根 Node 四门 | PASS/PASS/PASS/PASS；Adapter 196/1、Console 45、UI 10 |
| `git diff --check` | PASS（仅 Git 的 LF→CRLF 提示，不是 whitespace error） |
| Flyway | V001—V020，history=20 |

## 3. 真实 MCP 烟测事实

- initialize／tools-list／工具数：PASS/PASS/2；旧单记忆 MCP 输入拒绝；第三工具为 0。
- 首次三 CREATE：`INDEX_READY`；1 CandidateSet、1 ReviewSession、3 members、4 Decisions、3 Memory、3 Revision、3 Vector。4 Decisions 的精确构成为 3 条 `USER_CONFIRM` 与 1 条治理规范发布的 `HIDE_SELECT`。
- 共享证据 mapping profile：`1:1,2:2,3:1`；SourceUnit/Payload 未按候选复制。
- 同键重放：业务响应 EXACT，Decision/Memory/Vector/Embedding/receipt 增量 0。
- mixed、all-rejected、empty：PASS。空集合只写 1 条幂等 receipt，不写 CandidateSet/Review/Decision/Memory/Vector；这与正式 HTTP 契约及既有集成测试一致。
- accepted REVISE、accepted SUPERSEDE、mixed action：均停在 `DECISIONS_COMMITTED`，Memory/Vector 增量 0。
- 同键异值：REJECTED，事实增量 0；同值并发：ONE_FACT_EACH。
- 向量单点失败：规范事实保留、phase=`CANONICAL_COMMITTED`；同键恢复仅补 1 条缺失向量并收敛 `INDEX_READY`。
- 同一 MCP 进程中的 context-pack 回归：PASS，读取首次三条规范记忆。
- CandidateSet capability 发送 0；浏览器调用 0；成功路径 MCP stderr 0 bytes。

## 4. 窄产品修复

生产修改 3 个文件：

1. `MemoryGovernancePort` 新增 `insertActorRefIfAbsent` 原子端口；
2. `JooqMemoryGovernanceAdapter` 使用 `ON CONFLICT DO NOTHING`，随后按 actorId 或 `(actorKind, stableRef)` 读取真实已持久化身份；
3. `LocalV1CandidateSetBatchCoordinator` 精确比较 actorId、actorKind、stableRef、displayLabel，完全一致才复用，否则 fail closed。

测试修改 2 个文件：端口测试替身同步新方法；真实 PostgreSQL 新增“同一 thread/actor 跨两个 CandidateSet 精确复用”与“actorId 身份碰撞零批次事实”两条回归测试。`createdAt` 不作为身份相等条件，不改写既有 actor 事实。

未修改 OpenAPI、V001—V020、jOOQ generated、React、正式故障码集合；未开始 REVISE/SUPERSEDE 生产投影治理。

## 5. 烟测判定器收口

外部脚本按真实正式语义修正了以下机械误判：Flyway 版本字符串 `020` 按整数 20 判定；首次三 CREATE Decision 总数为 4；REJECTED 候选的可选发布字段为 null；empty 允许且仅允许 1 条幂等 receipt；单 accepted CREATE 并发事实含 2 条 Decision（USER_CONFIRM + HIDE_SELECT）。这些调整未降低产品门，反而把断言改为精确事实构成。

- 脚本 SHA-256：`83816a3c4cde53e99e4a177089e58202b20da81358a4e7a44ac568683b0ab809`
- 外部机器证据 SHA-256：`6bf843e03b2c4a7110494edeca398cc3fa250c6ad9fea064b10fc2093ad93d9f`
- 仓内机器证据：`reports/LocalV1-CandidateSetCodexMCP-Evidence.json`

## 6. 安全、清理与 Git

- 真实烟测中秘密持久化／命令行／Evidence 命中：0/0/0；capability header literal：0；network pulls：0。
- Docker 新增 containers-volumes-networks：0-0-0；Task36B3 label 容器：0；payload／临时 harness／遗留 Java-Node session 进程：0/0/0。
- HEAD：`0c5b23aa84ac881ff21b2ea0d921cd267e557550`；Git index tree：`f13cdefb0cc3585c8f4e39b2f3d535f062d43eb1`，起止 MATCH。
- staged=0；越界 tracked 修改=0；未执行 stage/commit/push，未改 Git 配置。
- 工作区任务路径：9 modified + 11 task untracked（含本报告与 Evidence）=20；既有 QA 临时目录单列保留，不入任务路径。

完成即停，等待 hide 复核。
