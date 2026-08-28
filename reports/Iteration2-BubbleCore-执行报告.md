# Iteration2 Bubble Core｜正式 HTTP 纵切实施报告

状态：`BUBBLE_CORE_R1_READY_FOR_HIDE_REVIEW`  
工单：`Task50A-Nest-Bubble-Core正式HTTP纵切实施工单.md`  
冻结设计：`Bubble-V1设计冻结.md`（已核验 `BUBBLE_V1_DESIGN_FROZEN_R1`）  
续作依据：`Task50A-R1-额度中断断点续施工交接单.md`（两次额度中断后断点续施工完成）  
返工依据：`Task50A-R1-Bubble-Core闭包与前置门返工单.md`（R1 四项窄修复完成，见 §10）

## 1. 基线与范围

- 目标仓库：`D:\myproject\hide-nest`
- 开工/完工基线核验：`HEAD == origin/main == 926e1a5213fa98b5043777a37b8e37fb90c58d47`；staged=0；无 stage/commit/push/reset/stash/clean。
- 未开始：Chat Gateway 接线、部署；`apps/codex-adapter/**` 与 `apps/nest-console/**` 生产实现改动 0。
- 既有保护目录 `reports/local-v1-read-browser-qa/**`（16 路径）开工记录 SHA-256，完工复核 16/16 MATCH，未删除/移动/暂存/覆盖。

## 2. 已实现内容

- 新增独立 Bubble HTTP：`POST /v1/bubbles/resolve`、`POST /v1/bubbles/rooms/purge`。
- 请求闭合为 `spaceKey + roomKey + turnKey + queryText`；拒绝额外模型策略字段。
- 默认家庭 space 与 `minScore=0.70` 为服务端 fail-closed 配置；普通 Bubble 单条上限 1，无 bootstrap/首轮语义。
- `queryText` 仅进入 embedding；数据库保存 request hash、UTF-8 字节数和无正文事实，不保存 query 正文。
- V022 建立 receipt、delivery item、room/revision ledger；延迟闭包、不可变门、最小权限 purge 函数、无 memory revision FK。
- 向量检索在 store 侧排除已在同 Room 浮现的 revision，再取候选；ContextPack 逻辑保持原接口与 cooldown 语义。
- 重放、异值冲突、stale 复核、固定 evidenceAgeDays、Room purge、删除后不返回旧正文均已有实现与测试。
- 首轮 bootstrap：按冻结设计 NOT_IMPLEMENTED（普通 Bubble 语义）。

## 3. 质量门结果（全部 PASS）

所有 Maven 命令均使用 JDK 25（`C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot`）、`-o`、临时离线 settings；Node 均使用 `npm_config_offline=true`。

| 门 | 结果 |
|---|---|
| Bubble application targeted | PASS `LocalV1BubbleCoordinatorTest` 11/11（R1 +1：错误 space 前置拒绝单测） |
| Bubble database targeted（V022 三门） | PASS `LocalV1BubbleCoreDatabaseTest` 6/6（R1 +1：跨事务投毒反证）；空库/升级/重复 = **22/1/0**（V022 原位编辑，版本数不变） |
| Bubble HTTP + ContextPack HTTP targeted | PASS `LocalV1ContextPackHttpIntegrationTest` 18/18（R1 +1：错误 space 前置拒绝 HTTP 反证） |
| 契约 targeted | PASS contracts 模块 57/57（BubbleContract/OperationMatrix/ReadApiContract/SchemaStructure） |
| ContextPack/CandidateSet/Evidence/Deletion 回归 | PASS 含 `LocalV1V021CandidateEvidenceMappingErasureTest` 6/6（jOOQ A/B/tracked 逐字节一致，本轮生成物零变更）、`LocalV1S3C1ADatabaseErasureTest` 18/18、`LocalV1DeletionHttpIntegrationTest` 12/12 |
| OpenAPI 生成 / check / compatibility | PASS `GENERATION_PASS` / `GENERATION_CHECK_PASS` / `COMPATIBILITY_PASS`（verdict=COMPATIBLE exit=0） |
| jOOQ 正式生成 / GenerateCheck | PASS `HDM005_DB_ACTION_PASS`（Generate 后树与生成前逐字节一致：129 文件，combined hash `ac76c6b68a895bdd`；A-B-tracked PASS） |
| 根离线 `clean verify` | PASS BUILD SUCCESS 13/13 模块（含 architecture-tests，29 tests）；R1 后重跑同样 BUILD SUCCESS |
| Node 四门 | PASS typecheck/lint/test/build exit=0（offline） |
| MCP 工具数 | PASS = 3（`mcp.integration.test.ts` `toHaveLength(3)` ×2，随 npm test 执行） |
| `git diff --check` | PASS exit=0（仅 CRLF autocrlf 提示） |
| 格式噪声收口 | PASS `git diff --stat` == `git diff -w --stat`（46 tracked 文件，1075+/65-）；与前任备份双向语义校验 forward=0 / reverse TOTAL_CUR_ONLY=0 |
| Docker | PASS 新增 containers/volumes/networks = 0/0/0（前后均 7/49/3）；遗留 testcontainers=0；测试进程=0；临时 payload=0 |
| 泄漏扫描 | PASS 任务文件无私钥/token/口令/用户目录绝对路径/query 正文/记忆正文；OpenAPI 仅 `type: apiKey` 方案声明 |
| QA 保护目录 | PASS 16/16 SHA-256 MATCH |

## 4. 断点续施工增补说明

- 续作审计补齐前任备份中遗漏的机械迁移计数更新共 14 处（9 处备份已含而工作树缺失 + 5 处双方均停留在 HEAD 旧值 21：`LocalV1CandidateSetCreateProjectionTest`、`LocalV1CloseoutVectorProjectionTest`、`LocalV1ContextPackRelevanceGateTest`、`LocalV1ContextPackRetrievalTest`、`LocalV1EmbeddingVectorTest`，各 1 行 `assertEquals(21→22)`）。`LocalV1BubbleCoreDatabaseTest` 升级路径断言 `flyway(upgrade,"21")→21` 为目标版本语义，未触碰。
- 5 个曾被列为“幻影噪声”的测试文件中 3 个因上述计数修正获得真实 1 行语义 diff，归属最终 pathSet。
- 生成的 jOOQ/OpenAPI/TS 树全部由正式脚本离线闭环，未手改。

## 5. 禁止联网异常（hide 已接受，1 次已中断并披露）

- 触发：`scripts/db.ps1 -Action Generate` 首次尝试，Maven SNAPSHOT metadata 解析阶段。
- 目标域名：`packages.aliyun.com`、`maven.aliyun.com`、`maven.youzanyun.com`。
- attempted：yes；succeeded：unknown（未观察到制品下载成功确认）。
- 已中断：是；遗留 Testcontainers 容器当时已删除。
- 收紧后联网：0（Maven `-o` + 离线 settings；Node `npm_config_offline=true`）。未遇到缓存不足，未触发 `BLOCKED_OFFLINE_CACHE_INCOMPLETE`。

## 6. 既有非门发现（预存于 HEAD，不在本任务范围）

- `mvn spotless:check`：27 文件违规，跨 evidence/memory/runtime/security/contracts 五模块，其中大量为 HEAD 状态文件（如 `AnchorRef.java`）→ 属插件版本/环境偏差的既有状态；`spotless:check` 未绑定 verify，根 `clean verify` 不经过该门；工单禁止整仓格式化，故不处理。任务触碰文件中有 2 个同名在列（`MemoryVectorStorePort.java`、`OperationMatrixTest.java`），同类违规在 HEAD 状态文件同样存在，非本任务引入。
- Prettier `--check`：42 文件全部位于 `apps/codex-adapter/**`、`apps/nest-console/**`（本任务零改动）→ 既有状态。`scripts/format-check.ps1` 在本机 PowerShell 5.1 因无 BOM UTF-8（含 em-dash）解析失败，同为既有问题。
- `mvn -pl apps/api`（无 `-am`）会从 `~/.m2` 解析旧版 `hide-nest-database-adapter`（20 个迁移）导致计数断言失败；含 `-am` 的 reactor 内构建为正确姿势，最终根 `clean verify` 已 PASS。

## 7. 临时产物处置

已删除非交付物：`t50a-mvn.cmd`（前任临时 helper）、`MemoryGovernancePort.java.crlf-test`（CRLF 核查临时文件）、`.task50a-maven-offline-settings.xml`（临时离线 settings，全部 Maven 门完成后移除）。

## 8. 最终 Git pathSet（Git 机械生成，88 路径）

`git status --porcelain -uall`（排除 QA 保护目录），完工时刻真实状态；staged=0。

- tracked 修改 46：apps/api 10（2 生产 + 1 配置 + 7 测试）、contracts 3、application 1、contracts 测试 1、database-adapter 28（4 generated + 1 adapter + 23 测试）、memory port 1、api-client-ts generated index 2
- 新增（untracked）42：apps/api 6（Bubble 4 + 配置 1 + 测试 1）、application 9（Bubble 8 + 测试 1）、contracts 1（BubbleContractTest）、database-adapter 13（jOOQ generated 10 + adapter 1 + V022 迁移 1 + 数据库测试 1）、runtime 5、api-client-ts generated 6、报告/Evidence 2

完整逐路径清单见 `reports/Iteration2-BubbleCore-Evidence.json` 的 `pathSet.paths`（88 条，Git 机械生成，含 Evidence 自身）。R1-03 已用 `git -c core.quotepath=false status --porcelain=v1 -uall` 重生成（消除非 ASCII 路径八进制转义），与真实 Git 双向零差集（88==88），机械重数 46 tracked（1075+/65-）+ 42 untracked = 88。

## 9. 停止门

完成即停，等待 hide 复核。未 stage/commit/push；Chat Gateway 接线 NOT_STARTED；部署 NOT_STARTED。

## 10. R1 返工四项修复（Task50A-R1）

R0 交付主体保留，仅按返工单做窄修复；未重新生成 OpenAPI/jOOQ/TS（生成物本轮零变更，由 `jooqABTrackedMatch` 逐字节门实证）；无仓库级格式化。

### R1-01 事实集闭包跨事务加固

- 修复：V022 原为「receipt AFTER INSERT 复核 + item/ledger 仅外键」，攻击者可在 receipt 提交后的后续事务单独插 item/ledger 污染已闭合 NO_MATCH 事实集。现将闭包校验收敛进**单个 trigger 返回函数** `runtime.enforce_bubble_receipt_closure()`（trigger 返回函数对 jOOQ codegen 不可见，保证生成物零变更），并挂到三张表的 `DEFERRABLE INITIALLY DEFERRED` 约束触发器（`bubble_turn_receipt_closure_guard` / `bubble_delivery_item_closure_guard` / `bubble_room_revision_ledger_closure_guard`）。每次延迟触发都重查父 receipt 再判：NO_MATCH 必须 0 item/0 ledger、BUBBLE_READY 必须 1 item/1 ledger、绑定三元组（memory_revision_id / delivered_at==issued_at / score>=min_score）违例 → `RAISE EXCEPTION`，ERRCODE `23514`，稳定标识 `HDM022_BUBBLE_RESULT_ITEM_CLOSURE_INVALID` / `HDM022_BUBBLE_BINDING_CLOSURE_INVALID`。
- 反证（`LocalV1BubbleCoreDatabaseTest.crossTransactionChildInsertsCannotPoisonCommittedNoMatch`）：已提交 NO_MATCH receipt 后，后开事务分别尝试 ledger-only / item-only / item+ledger 三种投毒，均 COMMIT REJECTED（沿 cause 链断言 SQLException SQLState=23514 且携带 HDM022_BUBBLE_RESULT_ITEM_CLOSURE_INVALID）；receipts=3 / items=0 / ledger=0；同事务合法 READY 通过；purge 后同 turn 重新写入通过。

### R1-02 space 门前置

- 修复：`LocalV1BubbleCoordinator.resolve` 顺序由 shape→hash/lock→space 调为 **shape→space→hash/lock/receipt**，错误 space 在任何事实探针（turn 锁、receipt 查询、embedding）之前拒绝。
- 反证：单测 `wrongSpaceRejectsBeforeAnyFactProbeEvenWithExistingReceipt`（SPACE_KEY_MISMATCH；FakeBubbleStore `turnLockCalls/receiptQueryCalls` 计数 = 0；embedding 0；零事实增量）+ HTTP `bubbleWrongSpaceRejectsBeforeAnyFactProbe`（403 / `ACCESS_DENIED`；embedding 计数不变；receipt/item/ledger 计数均不变）。replay 路径与 purge 前置 space 门不变。

### R1-03 pathSet 机械重建

- 修复：Evidence pathSet 改用 `git -c core.quotepath=false` 重生成，消除中文报告路径的八进制转义；与真实 Git porcelain 双向零差集（88==88）；Node/PowerShell/严格重复键三门解析全过。

### R1-04 stale 文案去 bootstrap 暗示

- 修复：`LocalV1ExceptionHandler` BUBBLE_STALE 消息改为「记忆泡泡既有交付事实已因治理变化失效」。全仓扫描：生产代码/OpenAPI/报告/Evidence 无 implemented-bootstrap / latest-N / bootstrapCount 主张（仅保留否定语义与无关 hook-bootstrap）；测试内 NOT_IMPLEMENTED 语义说明不受影响。

### R1 新增质量门（全部 PASS）

| 门 | 结果 |
|---|---|
| R1-01 跨事务闭包 | PASS 3 类攻击全部 23514 + 合法路径回归（见上） |
| R1-02 space 前置 | PASS 单测 + HTTP 双反证，零事实探针到达 |
| R1-03 pathSet 重建 | PASS 双向零差集 88==88，三解析门过 |
| R1-04 文案扫描 | PASS 零 bootstrap 实现暗示残留 |
| 删除回归 | PASS `LocalV1S3C1ADatabaseErasureTest` 18/18 + `LocalV1DeletionHttpIntegrationTest` 12/12 |
| V021 jOOQ 一致性 | PASS 6/6，生成物零变更 |
| 根离线 `clean verify` | PASS BUILD SUCCESS 13/13（JDK 25，`-o`） |
