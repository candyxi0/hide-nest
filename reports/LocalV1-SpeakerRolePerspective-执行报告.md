# Local V1 说话者角色与记忆视角解耦执行报告

状态：`LOCAL_V1_SPEAKER_ROLE_PERSPECTIVE_DECOUPLED_READY_FOR_HIDE_REVIEW`

## 1. 结论

本轮已把 CandidateSet 证据中的“谁说的”与“记忆采用谁的视角”解耦：

- `speakerKey` 只用于稳定派生 actor identity；
- `speakerRole` 为 required 闭合枚举，仅允许 `XIAOLIN | HIDE`，唯一决定 `displayLabel`；
- `perspectiveSpeakerKey` 只决定候选记忆的叙述视角/治理主体，不再参与“小林 / hide”显示推导。

正式映射为 `XIAOLIN -> 小林`、`HIDE -> hide`。同一请求内相同 `speakerKey` 或相同 `actorId` 的角色冲突均 fail closed；缺失、null、未知 role 均拒绝。`speakerRole` 同时进入 Java/TypeScript 两端 request hash 与 confirmation hash，单独篡改角色无法重用旧幂等事实。

未实现多 hide 身份系统，未修改 UI 业务逻辑、数据库迁移、jOOQ 数据库生成树、ContextPack、向量、删除或家庭小主机部署。

## 2. 关键判定

| 判定 | 结果 | 证据 |
|---|---:|---|
| speakerRole 闭合 | PASS | MCP strict schema、HTTP mapper、application 三层仅接受 `XIAOLIN/HIDE` |
| 缺失/null/未知 role | REJECTED | MCP 输入反证与 HTTP 422 反证 |
| perspective=HIDE 时身份不反转 | PASS | 真实 PostgreSQL CandidateSet 测试复核 `小林/hide` ActorRef |
| perspective=XIAOLIN 时身份不反转 | PASS | 同一角色映射切换 perspective 后保持 |
| 任意稳定 speakerKey | PASS | 非字面 `xiaolin/hide` 的键由 role 决定标签 |
| 同 key / actor 角色冲突 | REJECTED | MCP 发 HTTP 前拒绝；application 写库前拒绝 |
| perspective 未出现在请求证据池 | REJECTED | accepted 还须出现在本候选引用证据中；rejected 无 anchor 但必须有显式角色事实 |
| role 进入双 hash | PASS | 只改 role 时 request/confirmation hash 均变化 |
| 同值 replay | EXACT | 既有 CandidateSet 幂等、事实复核与投影测试通过 |
| 小林右 / hide 左 | PASS / PASS | API displayLabel 复核 + 既有共享对话组件 45 项前端回归 |
| 内部 speakerKey/role 泄漏到 DOM | 0 | React 生产代码未改，既有 DOM 回归通过 |

## 3. 实现范围

共 17 个 tracked 路径发生本工单变更：

- CandidateSet MCP input、canonicalizer、工具说明与测试夹具；
- formal OpenAPI、byte-identical baseline、官方 TypeScript 生成物；
- CandidateSet HTTP mapper 与真实 HTTP 集成测试；
- application request model、canonicalizer、batch coordinator；
- CandidateSet 真实 PostgreSQL 核心/投影测试。

正式 OpenAPI 与 baseline SHA-256 均为：

```text
7EE5CB5DF4CC13F8800AE35C870EB9079170030FBE9EE6CF41E3742988EF81D0
```

OpenAPI 双次生成、tracked TypeScript 生成物比对及语义兼容门均 PASS。operation / failure code / event type 仍为 `30 / 50 / 12`，schema 数量未因本轮新增命名 schema。

## 4. 测试与质量门

### 4.1 定向门

| 套件 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| CandidateSet 决策核心 | 33 | 0 | 0 | 0 |
| CandidateSet CREATE 投影 | 26 | 0 | 0 | 0 |
| CandidateSet 正式 HTTP | 16 | 0 | 0 | 0 |
| 合计 | 75 | 0 | 0 | 0 |

Codex Adapter：13 files，`200 passed / 1 skipped`。

### 4.2 Node 与契约

- 根 Node typecheck / lint / test / build：PASS / PASS / PASS / PASS；
- nest-console：45/45；UI contract fixtures：10/10；
- OpenAPI generate check：PASS；
- OpenAPI compatibility：PASS。

### 4.3 Maven 事实披露

执行了一次真正的 JDK 25 离线 `mvnw.cmd -o clean verify`。该单次 reactor 在 API 套件的既有 `LocalV1DeletionHttpIntegrationTest.sharedEvidenceFixtureDeleteFlow` 偶发返回 500，因此该命令最终为 FAILURE；在此之前 database-adapter 与 contracts 全部通过，Worker/architecture 因 reactor 停止被跳过。

未循环重跑全仓。随后按单因子隔离：

- `LocalV1DeletionHttpIntegrationTest`：12/12 PASS；
- Worker：5/5 PASS；
- architecture：29/29 PASS。

当前 51 份唯一 Surefire XML 合计为：

```text
602 tests / 0 failures / 0 errors / 1 skipped
```

因此本报告将 Maven 门记为 `COMPOSITE_PASS_WITH_ISOLATED_EXISTING_FLAKE`，不冒充单次 full clean verify 为纯 PASS。该偶发项与本轮 speaker role 路径无代码交集；Task36 阶段已有同类隔离通过记录。

## 5. 机械边界

- HEAD：`6cf8c69ffe50508014df76eb7eef740a933c2786`，与冻结基线一致；
- 初始/结束 index tree：`e2a04c16bcc559b988aae4b68a275f99221f7ac4`，staged=0；
- V001—V020、database generated、React prototype、ContextPack：MATCH；
- out-of-scope tracked 修改：0；
- Testcontainers containers / volumes / networks 残留：0 / 0 / 0；
- Maven/Surefire/Testcontainers 任务进程残留：0；
- 联网：否；Git 写操作：否。

`git diff --check` 唯一命中为官方 OpenAPI Generator 产物 `CandidateSetEvidenceMessage.ts:41` 的 JSDoc 尾随空白；生成检查证明 tracked 文件与正式生成器逐字一致，未手改生成物。其余路径 whitespace PASS。

正文、token、capability、secret、家庭主机地址泄漏均为 0；报告与 Evidence 不含证据正文或秘密。

## 6. 既有错误合成记忆

状态：`POST_DEPLOY_DELETE_AND_RECREATE_NOT_EXECUTED`。

本工单没有修改或清空现有数据。代码部署后，应由小林通过正式永久删除流程删除那条说话者反转的合成记忆，再以新的 `candidateSetKey`、稳定 `speakerKey` 与显式 `speakerRole` 重存；不得复用旧错误 actor 事实。

