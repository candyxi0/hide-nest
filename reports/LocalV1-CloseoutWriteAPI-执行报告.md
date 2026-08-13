# LocalV1-CloseoutWriteAPI-执行报告

## 实施状态

**LOCAL_V1_CLOSEOUT_WRITE_API_R1_READY_FOR_HIDE_REVIEW**

> 本报告在 Task31A 未提交成果之上，按 `Task31A-R1` 返工工单精确关闭 3 项阻塞（内容绑定、最终重放事实复核、真实并发）。Task31A 全仓 `clean verify` 成功证据保留。

## R1 返工摘要（3 项阻塞关闭）

### R1-01 冻结唯一规范哈希器

新增 `LocalV1CloseoutCanonicalizer`（application 内部，生产与测试共同调用，测试不再手写第二份算法）。全部字段用长度前缀 `UTF8(byteLength)+':'+UTF8(value)` 编码；null=`-1:`；布尔=`true/false`；UUID 小写连字符；时间 `OffsetDateTime.toInstant()` UTC ISO-8601；整数十进制；数组先写数量。

- `threadReaderManifest.manifestHash`：复算并常量时间比较，覆盖 schemaVersion/fromOrdinal/toOrdinal/continuous/selectedEvidenceMessages（sourceUnitId/actorId/ordinal/externalUnitRef/UTC occurredAt/bodyHash），不含消息正文。
- `reviewManifestHash`：复算并常量时间比较，覆盖 submissionId/threadId/hideSelection(perspectiveActorId/memoryType/bodyHash)/threadManifestHash/sourceAnchors(anchorId/unit 数/每 unit sourceUnitId/fromOffset/toOffset/ordinal)/decision；不含 reviewManifestHash 自身、confirmationSourceUnitId、confirmationProof。
- `confirmationProof`：保留 Task31A 冻结公式。校验顺序固定为：消息 bodyHash → thread manifest hash → review manifest hash → confirmationProof → 任何业务写入。

传输模型 `memoryType` 改为承载 wire 值（大写），facade 映射为 S1 PascalCase。

### R1-02 最终重放必须重新验证真实事实

删除 `replayReceipt` 的确定性拼接成功响应。新增 `replayVerify`：校验 facade receipt（operationCode/requestHash/resourceKind/resourceId 精确一致）→ 复用 `s1.prepare` replay（Review/Proposal/Source/anchors/hide Decision）→ 复用 `s1.confirm` replay（Memory/current Revision/USER_CONFIRM/EVIDENCED_BY）→ 校验 closeout_run(runId/submissionId/scopeId/state=COMPLETED) → CaptureScope(sourceId/range/ruleVersion/coverage/manifestHash/frozenAt 非空) → CaptureScopeUnit 集合与 selectedEvidenceMessages `(sourceUnitId, ordinal)` 双向相等。任一缺失/篡改 fail closed（非 2xx），不偷偷重建。

### R1-03 真实并发与阶段锁

每个“检查后写”阶段（CaptureScope/CloseoutRun create-if-absent、run 收敛、facade receipt 创建/复核）各自事务内先获取同一 `submissionId` facade advisory lock，再重新读取事实。真实并发测试：2 线程 + 2 独立 HTTP + CountDownLatch 起跑。

## R1 动态证据

| 门 | 结果 |
|---|---|
| 哈希攻击矩阵（改消息字段/改正文+bodyHash/改视角与anchor图/改manifest+局部字段/伪proof/全正确） | 6/6 通过 |
| 损坏事实重放（删 closeout_run/篡改 capture_scope.manifestHash/删 unit/改 receipt.resourceId/删 relation） | 5/5 非 2xx 且无第二份事实 |
| 同值并发 | TWO_202_ONE_FACT |
| 异值并发 | ONE_202_ONE_409 |
| 唯一键/500 泄漏、线程/连接残留 | 0/0 |
| targeted 测试（closeout 16 + S1 26） | 42/0/0/0 |



## 基线

- HEAD: `bad3d0793c2549f2c51fca7f1ccc415e2a4d4bfd`（冻结 HEAD，未变）
- 分支: `main`
- 执行方式: 新增文件 + 定向修改；未 stage/commit/push/amend/reset；未覆盖用户文件
- 既有未跟踪目录 `reports/local-v1-read-browser-qa/.playwright-artifacts/` 原样保留，未读取正文、未删除、未移动

## 业务目标达成

交付一条真实、合成、本机专用的关窗主链：

```
POST /v1/closeout-submissions
  → 复用 LocalV1S1WindowCloseCoordinator.prepare + confirm
  → PostgreSQL 出现正式 Memory、当前 Revision、EVIDENCED_BY
  → 真实 CaptureScope / CloseoutRun（runId == submissionId）
  → 返回有真实数据库事实支撑的 CloseoutReceipt（phase=CANONICAL_COMMITTED）
  → GET /v1/runs/{runId} 可查 CANONICAL_COMMITTED
```

本轮只支持**一个候选记忆**（`local-v1-synthetic` profile 暂时限制），未实现后续页面、暂缓确认、取消、归档、隔离或删除。

## 契约改动（第 4、5 节）

### 4.1—4.5 命名 schema

`CloseoutSubmissionRequest` 四个封闭空对象改为命名 schema 精确 `$ref`，并新增 6 个命名 schema：

| 命名 schema | 字段 | 闭合 |
|---|---|---|
| `HideSelectionSubmission` | perspectiveActorId(uuid), memoryType(MemoryType), bodyText(1..16000), bodyHash(64hex) | `additionalProperties:false` |
| `UserConfirmationSubmission` | decision(const:CONFIRM), reviewManifestHash(64hex), confirmationSourceUnitId(uuid) | `additionalProperties:false` |
| `CloseoutSourceAnchorSubmission` | anchorId(uuid), units(1..100) | `additionalProperties:false` |
| `CloseoutAnchorUnitSubmission` | sourceUnitId, fromOffset(nullable,>=0), toOffset(nullable,>=0), ordinal(>=0) | `additionalProperties:false` |
| `SyntheticThreadReaderManifest` | schemaVersion(const), fromOrdinal, toOrdinal, continuous(bool), manifestHash(64hex), selectedEvidenceMessages(1..100) | `additionalProperties:false` |
| `SyntheticEvidenceMessage` | sourceUnitId, actorId, ordinal, externalUnitRef(1..256), occurredAt, bodyText, bodyHash(64hex) | `additionalProperties:false` |

顶层 `confirmationProof` 增加 `pattern: ^[0-9a-f]{64}$`；`sourceAnchors` `minItems:1`。

### 4.6 结构校验（在任何业务写入前拒绝）

服务端在 facade `validate` 阶段、任何写入前拒绝：Idempotency-Key 缺失/≠submissionId、bodyHash/消息 bodyHash/confirmationProof 格式或内容不匹配、sourceUnit/actor/anchor/anchor-unit 重复、anchor 引用未选择 SourceUnit、消息 ordinal 重复/越界/不在 from-to、confirmationSourceUnitId 混入 selected evidence、perspectiveActorId 不在 selected evidence actors、body/消息/units 超限、未知字段、未知 enum、非 CONFIRM、`continuous != true`。全部映射到既有 50 项 FailureCode，不新增正式码。

### 契约门结果

| 门 | 结果 |
|---|---|
| 30 operation / 50 failure code / 12 event type 不变 | PASS（OperationMatrixTest + EnumCompletenessTest） |
| 命名 schema 精确 + 全闭合（ClosureJudge） | PASS |
| spec ↔ baseline.yaml 逐字节一致 | PASS（ReadApiContractTest.formalSpecAndBaselineMustBeByteIdentical） |
| inventory 计数动态一致（apiSchemas 35→41，totalSchemaCount 48→54） | PASS |
| Java/TS 真实生成、双次稳定、无 Object 残留 | PASS（generate.ps1 -Target openapi；6 个新 TS model + Java model；closeout DTO 无 `object` 残留） |
| contracts Maven 离线测试 | PASS（46 tests） |
| TS typecheck | PASS（4 workspace） |

## 后端实现范围（第 6 节）

- 新增 application facade `LocalV1CloseoutWriteCoordinator`：只做传输映射、确定性 ID、阶段编排与恢复，**复用** `LocalV1S1WindowCloseCoordinator.prepare/confirm`，**0 复制**业务写入逻辑。
- 新增传输映射 model `LocalV1CloseoutSubmission`/`LocalV1CloseoutReceipt`/`LocalV1RunStatus`；异常 `LocalV1CloseoutException`（内部 `Code` 枚举，全部映射既有 50 项，不新增正式码）。
- Runtime 补 `RuntimeQueryPort.findCloseoutRunBySubmissionId` + `JooqRuntimeQueryAdapter` 实现（按 submissionId 精确查询能力）。
- apps/api：`LocalV1CloseoutWriteController`（POST closeout-submissions）、`LocalV1RunStatusController`（GET runs/{runId}）、`LocalV1CloseoutWriteGate`（bearer+capability）、`LocalV1CloseoutWriteConfiguration`（装配）、`LocalV1CloseoutRequestMapper`（严格传输映射+未知字段拒绝）、`LocalV1ExceptionHandler` 扩展。
- `LocalV1SyntheticReadGate` 扩展：允许 `GET /v1/runs/{runId}`；对 `POST /v1/closeout-submissions` 放行（由 write gate 前置校验，`@Order(1)`）。
- `scripts/local-v1-synthetic-closeout.ps1`：只从环境取 token/capability，不把值写盘。

未新增 migration；未改 V001—V015；未改 database generated tree；未复制 S1 业务 SQL；证据正文不进 JSONB/receipt/outbox/run/日志/Problem。

### 合成证明边界（3.4）

- 两个 controller 只在 `local-v1-synthetic` profile 注册。
- `submitCloseout` 同时要求高熵 bearer 与高熵 `X-Action-Capability`，均从进程配置注入（`hidenest.local-v1.synthetic-token` / `hidenest.local-v1.synthetic-capability`），不进 HTML/URL/磁盘/响应/日志/报告。
- capability 未配置时 write gate fail closed（403 CAPABILITY_REQUIRED）。
- 合成证明命名为 `LOCAL_V1_SYNTHETIC_PROOF` 语义（confirmationProof 为请求内部一致性证明，不冒充设备证明/生产安全）。
- 默认 profile 仍 fail closed（`DefaultProfileFailClosedConfiguration` 不变）。

## 验收结果（第 7 节）

### 7.1 契约门

全部 PASS（见上表）。

### 7.2 真实 HTTP 主链（随机 loopback 端口 + Testcontainers PostgreSQL 18 + 临时 payload root）

| # | 断言 | 结果 |
|---|---|---|
| 1 | 无/错 bearer、无/错 capability 全拒绝且零业务写入 | PASS |
| 2 | 默认 profile 无写入口；非 loopback 配置启动失败 | PASS |
| 3 | 合法单候选请求 202；runId 真实 closeout_run；statusUrl 可 GET；phase CANONICAL_COMMITTED | PASS |
| 4 | 正式 Memory/Revision/当前指针/USER_CONFIRM/证据关系/ChangeEvent/outbox/receipt 精确存在 | PASS |
| 5 | CaptureScope 只含已选 SourceUnit；未选闲聊 canary 全表+payload+manifest+响应+日志+报告 0 | PASS |
| 6 | bodyHash/消息 hash/proof/anchor 引用任一错误均写入前拒绝 | PASS |
| 7 | 同 submissionId+同请求重放收敛；异请求 `IDEMPOTENCY_KEY_REUSED` | PASS |
| 8 | 人工注入 prepare 后失败/run 建立后失败/confirm 后 run 状态更新失败，重试收敛且无第二份事实 | PASS |
| 9 | payload 缺失/篡改 fail closed 不泄露绝对路径/正文（复用 S2B 读链已证；本次写链未回读正文） | PASS |
| 10 | `Cache-Control: no-store`、requestId、content type 正确 | PASS |
| 11 | 三条 read API（list/detail/evidence）立即读到新记忆 | PASS |
| 12 | API 进程停止并用同数据库/payload 重启后 run 与记忆仍可查 | PASS |

测试：`LocalV1CloseoutWriteHttpIntegrationTest` **Tests run: 9, Failures: 0, Errors: 0**。

### 7.3 最小质量门

| 门 | 结果 |
|---|---|
| contracts targeted（离线） | PASS |
| closeout API targeted 真实 HTTP/DB | PASS（9 tests） |
| 既有 S1 targeted | PASS（LocalV1S1WindowCloseTest 26 tests） |
| 全仓 Maven 一次离线 `clean verify` | **PASS**（BUILD SUCCESS，Total 03:21 min） |
| API 启动 smoke | PASS（集成测试内 Spring 启动） |
| 生成客户端 typecheck | PASS |
| `git diff --check` | PASS（仅生成文件 LF→CRLF 提示，无空白错误；按 generated whitespace 例外判定） |
| body/path/token/capability/secret/canary 扫描 | PASS（0 命中；测试 assertNoLeak + assertProblem 校验） |

本轮不启动浏览器、不跑 Playwright、不修改 React。

## 停止条件检查（第 9 节）

未触发任何停止条件：
- runId/statusUrl 对应真实 `runtime.closeout_run` 行（runId==submissionId，submission_id UNIQUE，有测试支撑）；
- 未用 reviewSessionId/memoryId 冒充 runId；
- 未新增 migration/failure code/event type/第二套公开 API；
- 复用 S1 prepare/confirm，未复制业务逻辑；
- 合成 proof 未宣称为生产设备证明；
- 未联网下载、未改 lockfile、未越界修改。

## 权威语义一致性

- 状态投影：READY/RUNNING→RECEIVED、COMPLETED→CANONICAL_COMMITTED、FAILED/CANCELLED→FINAL_FAILED；不返回 INDEX_READY。
- 确定性 ID：runId=submissionId；memoryId/policyId/scopeId 由 submissionId 确定性派生；prepare/confirm 幂等键确定性派生。
- 两阶段可恢复：prepare→scope+run→confirm→run 收敛→facade receipt，各阶段独立提交，重放从已有事实继续。

## 工作区与 Git

- 真实 Git index：未变（staged=0）
- 工作区：tracked=10、untracked files=18、staged=0、越界=0
- 联网：否
- Git 写操作：否（无 stage/commit/push）

## 已知生产缺口

HDM-007/008/010/011（生产身份/能力、ThreadReader、四道门）未完成；**真实数据禁止**，生产模式继续 fail closed。

## 变更文件清单

修改（10）：`hide-nest-api.yaml`、`ContractInventory-HDM-003-v0.1.json`、`baseline.yaml`、`SchemaStructureTest.java`、`RuntimeQueryPort.java`、`JooqRuntimeQueryAdapter.java`、`LocalV1ExceptionHandler.java`、`LocalV1SyntheticReadGate.java`、`CloseoutSubmissionRequest.ts`、`index.ts`。

新增（18）：application model/coordinator/exception 5 项、api 5 项、测试 1 项、生成 TS model 6 项、脚本 1 项。
