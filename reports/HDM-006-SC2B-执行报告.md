# HDM-006 Slice C2B Runtime 适配器实施 执行报告

`状态：HDM006_SLICE_C2B_RUNTIME_ADAPTERS_READY_FOR_HIDE_REVIEW` `基线 HEAD：b140968` `时间：2026-08-10`

## 1. 执行摘要

完成 HDM-006 Slice C2B Runtime 普通持久化 command/query adapter 实施及真实数据库契约测试。RuntimeTransactionPort 17 个 abstract 方法全部由 JooqRuntimeTransactionAdapter 实现；新建 JooqRuntimeQueryAdapter 实现 RuntimeQueryPort 全部 13 个方法。所有数据库测试通过 Testcontainers PostgreSQL 18 + pgvector 固定 digest、Flyway V001—V009 迁移。

## 2. 环境

| 项目 | 实际 |
|------|------|
| JDK | 25.0.4-hotspot (Temurin) |
| Maven | 3.9.16 |
| Java version 属性 | 25 |

## 3. C1/C2A 累积改动（未变更）

### C1 新增领域对象（15 个）

| 模块 | 文件 | 对应表 |
|------|------|--------|
| evidence | Source.java, SourceUnit.java, SourcePayload.java, SourceAnchor.java, SourceAnchorUnit.java | evidence.* |
| memory | ActorRef.java | memory.actor_ref |
| runtime | CaptureScope.java 等 9 个 | runtime.* |

### C1 端口新增方法签名

- EvidenceReferencePort：10 个新增（C2A 已实现）
- MemoryGovernancePort：4 个新增（C2A 已实现）
- RuntimeTransactionPort：13 个新增 + RuntimeQueryPort 新建（本任务实现）

### C2A 已验收

- JooqEvidenceReferenceAdapter：11 个 @Override 方法
- JooqMemoryGovernanceAdapter：17 个 @Override 方法（含 13 个 HDM-005 + 4 个 HDM-006）
- DatabaseSliceC2AEvidenceMemoryAdapterTest：14 个测试
- Evidence/Memory port default-UOE 残留：0

## 4. C2B 本任务新增改动

### 4.1 端口 default 清理

| 文件 | 变更 | 残留 default-UOE |
|------|------|-----------------|
| `RuntimeTransactionPort.java` | 13 个 `default` + UOE → `abstract` | 0 |
| `RuntimeQueryPort.java` | 新建，13 个 abstract 方法 | 0（无 default） |

### 4.2 JooqRuntimeTransactionAdapter（17 个 @Override 方法）

**HDM-005 已有 4 个（语义未变更）：**

| 方法 | 操作 |
|------|------|
| `lockIdempotencyKey` | pg_advisory_xact_lock |
| `findReceiptByKey` | SELECT FOR UPDATE → IdempotencyReceipt |
| `commitReceipt` | INSERT COMMITTED receipt |
| `insertGovernedOutbox` | INSERT outbox_event |

**C2B 新增 13 个：**

| 方法 | 操作 |
|------|------|
| `insertCaptureScope` | INSERT → runtime.capture_scope，映射全部 9 字段 |
| `insertCaptureScopeUnits` | 批量 INSERT → runtime.capture_scope_unit；空集合 no-op |
| `freezeCaptureScope` | UPDATE frozen_at WHERE scope_id=? AND frozen_at IS NULL，返回 affectedRows==1 |
| `insertCloseoutRun` | INSERT → runtime.closeout_run，映射全部 9 字段 |
| `transitionCloseoutRun` | UPDATE state/startedAt/terminalAt/failureCode WHERE run_id=? AND state=expectedState，返回 affectedRows==1 |
| `insertCheckpoint` | INSERT → runtime.checkpoint，映射全部 7 字段 |
| `insertWorkArtifact` | INSERT → runtime.work_artifact，映射全部 7 字段 |
| `insertModelRun` | INSERT → runtime.model_run，映射全部 10 字段 |
| `transitionModelRun` | UPDATE state/outputManifestHash/terminalAt/failureCode WHERE model_run_id=? AND state=expectedState，返回 affectedRows==1 |
| `insertRetrievalTrace` | INSERT → runtime.retrieval_trace，UUID list → PostgreSQL uuid[] 转换 |
| `insertContextDelivery` | INSERT → runtime.context_delivery，映射全部 11 字段 |
| `invalidateContextDelivery` | UPDATE invalidatedAt/invalidationReason WHERE delivery_id=? AND invalidated_at IS NULL AND invalidation_reason IS NULL，返回 affectedRows==1 |
| `insertConsumerEffect` | INSERT → runtime.consumer_effect，映射全部 4 字段 |

约束：
- 所有 4 个 CAS 方法使用单语句 UPDATE WHERE + affectedRows==1，无 SELECT-before-UPDATE
- 不生成业务时间，所有时间使用调用方参数
- 不把正文、prompt、answer、思维链写入任何列或日志
- 数据库约束异常原样传播
- RetrievalTrace UUID list ↔ PostgreSQL uuid[] 双向转换，null 保持 null

### 4.3 JooqRuntimeQueryAdapter（13 个 @Override 方法）

| 方法 | 操作 |
|------|------|
| `findCaptureScopeById` | SELECT by PK → CaptureScope；not-found → null |
| `findCaptureScopeUnitsByScopeId` | SELECT by scope_id ORDER BY ordinal ASC → List |
| `findCloseoutRunById` | SELECT by PK → CloseoutRun；not-found → null |
| `findCloseoutRunsByScopeId` | SELECT by scope_id ORDER BY created_at ASC, run_id ASC → List |
| `findCheckpointById` | SELECT by PK → Checkpoint；not-found → null |
| `findCheckpointsByRunKindAndRunId` | SELECT by (run_kind, run_id) ORDER BY sequence_no DESC, checkpoint_id ASC → List |
| `findWorkArtifactById` | SELECT by PK → WorkArtifact；not-found → null |
| `findWorkArtifactsByRunId` | SELECT by run_id ORDER BY created_at ASC, artifact_id ASC → List |
| `findModelRunById` | SELECT by PK → ModelRun；not-found → null |
| `findRetrievalTraceById` | SELECT by PK → RetrievalTrace；UUID[] → List<UUID> 转换 |
| `findContextDeliveryById` | SELECT by PK → ContextDelivery；not-found → null |
| `existsConsumerEffect` | SELECT COUNT by 三列完整键 → boolean |
| `findConsumerEffectByKey` | SELECT by 三列完整键 → ConsumerEffect；not-found → null |

约束：
- 单对象 not-found 返回 `null`；集合 not-found 返回空列表
- 排序严格按工单规定
- RetrievalTrace UUID[] null 保持 null，非 null 通过 Arrays.asList + compact constructor List.copyOf 防御性复制
- 禁止查询未授权正文或通过 JSONB/raw SQL 隐藏状态

### 4.4 数据库测试

新增 `DatabaseSliceC2BRuntimeAdapterTest`（14 个测试）：

| # | 测试 | 覆盖要求 |
|---|------|---------|
| 1 | CaptureScope + units 在同一事务中 assemble→freeze→commit，并按 ordinal round-trip | 工单 7.1 |
| 2 | 未 freeze 提交真实拒绝；跨 source unit 真实拒绝 | 工单 7.2 |
| 3 | freeze 首次 CAS true、重复／不存在 false | 工单 7.3 |
| 4 | CloseoutRun insert/find/list 与合法状态转换；wrong expected state 返回 false；非法状态由 DB 抛出 | 工单 7.4 |
| 5 | Checkpoint insert/find/按 sequence DESC | 工单 7.5 |
| 6 | WorkArtifact insert/find/list | 工单 7.6 |
| 7 | ModelRun insert/find、合法 CAS、wrong expected false、非法转换抛出 | 工单 7.7 |
| 8 | RetrievalTrace uuid[]：非空、空、null 三种 round-trip；返回列表不可修改 | 工单 7.8 |
| 9 | ContextDelivery insert/find、首次 invalidation true、重复 false | 工单 7.9 |
| 10 | ConsumerEffect insert／exists／find；同三元组重复拒绝 | 工单 7.10 |
| 11 | 所有单对象 not-found=null、集合 not-found=empty | 工单 7.11 |
| 12 | 数据库异常不被吞 | 工单 7.12 |
| 13 | 正文／secret canary 扫描 0 命中 | 工单 7.13 |
| 14 | 正式 adapters 的 override 集合与 ports 精确相等 | 工单 7.14 |

测试调用正式 adapter 方法；CaptureScope 事务组装使用 dsl.transaction() 确保同事务语义。直接 SQL 仅用于 FK 前置数据创建和跨源 trigger 拒绝验证。

### 4.5 架构测试

| 新增测试 | 说明 |
|---------|------|
| `runtimeTransactionAdapterOverridesMustExactlyMatchPortAbstractMethods` | Transaction port 17 个 abstract = adapter 17 个 public（精确相等） |
| `runtimeQueryAdapterOverridesMustExactlyMatchPortAbstractMethods` | Query port 13 个 abstract = adapter 13 个 public（精确相等） |
| `negativeRuntimePortFixtureMustViolateGeneratedTypeRule` | Runtime 非法 fixture 导入 jOOQ `CaptureScope` 表类被同规则拒绝 |
| `RuntimePortForbiddenGeneratedTypeFixture.java` | 新增 runtime 侧同规则非法 fixture |

C1/C2A 已有测试全部保持 PASS。

## 5. 验收结果

| 检查项 | 结果 |
|--------|------|
| RuntimeTransactionAdapter @Override 方法 | 17/17（精确匹配 port abstract） |
| RuntimeQueryAdapter @Override 方法 | 13/13（精确匹配 port abstract） |
| C1 default-UOE 残留 | 0 |
| C2B 数据库测试 | 14/14/0/0/0 |
| CaptureScope 事务与 CAS | PASS |
| Closeout-Model 状态 CAS | PASS |
| Checkpoint-Artifact | PASS |
| Trace-Delivery | PASS |
| ConsumerEffect | PASS |
| not-found 返回 null/empty | PASS |
| 排序（ordinal/sequence DESC/created_at） | PASS |
| 异常传播（不被吞） | PASS |
| 正文/secret 泄漏 | 0/0 |
| C2A 测试 | 14/14 |
| Slice B 测试 | 95/95 |
| Slice C 测试 | 15/15 |
| 架构测试（ArchitectureTest + DatabaseBoundaryTest + PortBoundaryTest） | 7+6+11 = 24/24 |
| 同规则非法 fixture（evidence + memory + runtime） | PASS / PASS / PASS |
| 全仓 Maven offline clean verify | PASS（12 模块） |
| git diff --check | PASS（仅 CRLF 警告，无空白错误） |
| V001—V009 migration | MATCH（未修改） |
| jOOQ generated tree | MATCH（未修改） |
| Maven | 3.9.16 |
| Java | 25.0.4 |
| Node/API/Worker | NOT_APPLICABLE |

## 6. Fail-closed 边界

| 对象 | 状态 |
|------|------|
| Outbox SKIP LOCKED / lease / retry / FINAL_FAILED | ROUTED_LATER → HDM-006 Slice D |
| OPERATIONAL producer | 保持 PRODUCTION_DISABLED |
| WorkArtifact 定时清理 | ROUTED_TO_HDM006_SLICE_D_V010_DECISION — 普通写入与读取已通过；当前应用数据库角色缺少物理删除授权，定时清理尚未实现；在 Slice D 明确裁定 V010 权限／清理机制前，清理能力保持 fail-closed |
| DerivationBlock / DeletionFence / DeletionRun | ROUTED_LATER — fail-closed |
| C1 default-UOE 残留 | 0 — 全部 13 个 default 已转 abstract |
| 生产 adapter 中 UnsupportedOperationException | 0 |
| 正文承载字段泄漏 | 0（canary 除外） |

## 7. Git 状态

- 真实 Git index：未变（起止一致）
- staged：0
- 越界：0（所有修改均在允许范围）
- C2B 新增修改文件（tracked）：2（RuntimeTransactionPort.java, JooqRuntimeTransactionAdapter.java）
- C2B 新增文件（untracked）：4（JooqRuntimeQueryAdapter.java, DatabaseSliceC2BRuntimeAdapterTest.java, PortBoundaryTest.java 修改, RuntimePortForbiddenGeneratedTypeFixture.java）
- C1/C2A 遗留修改文件（tracked）：4（EvidenceReferencePort.java, MemoryGovernancePort.java, JooqEvidenceReferenceAdapter.java, JooqMemoryGovernanceAdapter.java）
- C1/C2A 遗留新增文件（untracked）：23（14 domain + 2 port + 2 test + 1 canary + 2 report + 2 fixture）
- C2B 封口新增（untracked）：5（JooqRuntimeQueryAdapter.java, DatabaseSliceC2BRuntimeAdapterTest.java, PortBoundaryTest.java 修改, RuntimePortForbiddenGeneratedTypeFixture.java, HDM-006-SC-FinalEvidence.json）
- 精确 untracked files 总数：28
- 禁止修改范围（migration、jOOQ、domain、application、security、apps、Node）：0 修改
- 联网：否
- Git 写操作：否

## 8. Slice C 整体封口（2026-08-10 22:03）

Maven 3.9.16 + Java 25.0.4 全仓离线 `clean verify` 复跑通过。本次 Surefire 总计 199／失败 0／错误 0／跳过 0。三报告 Maven 版本、测试计数、路由状态、工作区计数已一致。

| 确认项 | 状态 |
|--------|------|
| C2A 数据库测试 | 14/14 |
| C2B 数据库测试 | 14/14 |
| Slice B | 95/95 |
| Slice C 协调器 | 15/15 |
| 架构正式规则（Arch/DB/Port） | 24/24 |
| 同规则非法 fixture（evidence/memory/runtime） | 3/3 |
| V001—V009 / generated tree | MATCH / MATCH |
| default-UOE / 正文 / secret 泄漏 | 0 / 0 / 0 |
| WorkArtifact 清理 | ROUTED_TO_HDM006_SLICE_D_V010_DECISION |
| 报告一致性 | PASS |
| Git index | 未变；staged=0；越界=0 |

## 9. 最终回复

```text
封口状态：HDM006_SLICE_C_READY_FOR_LOCAL_COMMIT
Runtime command／query adapter：17/17／13/13
Runtime C1 default-UOE 残留：0
C2B 数据库测试：14/14/0/0/0
CaptureScope事务与CAS／Closeout-Model状态CAS：PASS／PASS
Checkpoint-Artifact／Trace-Delivery／ConsumerEffect：PASS／PASS／PASS
not-found／排序／异常传播：PASS／PASS／PASS
C2A／Slice B／Slice C：14/14／95/95／15/15
架构正式／同规则非法 fixture：PASS／PASS
全仓 Maven offline：PASS
Maven／Java：3.9.16／25.0.4
V001—V009／generated：MATCH／MATCH
正文／secret 泄漏：0／0
Node／API／Worker：NOT_APPLICABLE
真实 Git index：未变
工作区：tracked=6、untracked files=28、staged=0、越界=0
联网／Git 写操作：否／否
报告路径：D:\myproject\hide-nest\reports\HDM-006-SC2B-执行报告.md
```
