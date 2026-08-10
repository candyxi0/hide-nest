# HDM-006 Slice C1 领域对象与端口契约 执行报告（R1 纠偏）

`状态：HDM006_SLICE_C1_R1_READY_FOR_ADAPTER_IMPLEMENTATION` `基线 HEAD：b140968` `时间：2026-08-10` `纠偏依据：Task21A-R1`

## 1. 执行摘要

完成 HDM-006 Slice C1 所有目标 + R1 纠偏。为 V008/V009 表建立领域对象和端口契约。
- RetrievalTrace 防御性复制已修复（compact constructor + `List.copyOf`）
- Runtime 四个状态变更端口已改为 CAS 签名（`boolean` 返回 + 精确 `expectedState` WHERE）
- 正文扫描已修正为确定性判定器（repo-root 解析 + 精确文件集 + 合法 MemoryRevision.bodyText 免检 + 同规则 canary 拒绝）
- Maven 3.9.16 + JDK 25.0.4 全仓离线验证通过

## 2. 环境

| 项目 | 实际 |
|------|------|
| JDK | 25.0.4-hotspot (Temurin) |
| Maven | 3.9.16 (wrapper) |
| Java version 属性 | 25 |

## 3. 新增领域对象

### Evidence（5 个）

| 文件 | 对应表 | 字段数 |
|------|--------|--------|
| `Source.java` | evidence.source | 9 |
| `SourceUnit.java` | evidence.source_unit | 8 |
| `SourcePayload.java` | evidence.source_payload | 14 |
| `SourceAnchor.java` | evidence.source_anchor | 4 |
| `SourceAnchorUnit.java` | evidence.source_anchor_unit | 5 |

保留现有 `AnchorRef`，未改变其语义。

### Memory（1 个）

| 文件 | 对应表 | 字段数 |
|------|--------|--------|
| `ActorRef.java` | memory.actor_ref | 5 |

保留现有 `MemoryRelation`、`DerivationBlock`、`DeletionFence`，未改变其字段或语义。

### Runtime（9 个，含 CaptureScopeUnit）

| 文件 | 对应表 | 字段数 |
|------|--------|--------|
| `CaptureScope.java` | runtime.capture_scope | 9 |
| `CaptureScopeUnit.java` | runtime.capture_scope_unit | 4 |
| `CloseoutRun.java` | runtime.closeout_run | 9 |
| `Checkpoint.java` | runtime.checkpoint | 7 |
| `WorkArtifact.java` | runtime.work_artifact | 7 |
| `ModelRun.java` | runtime.model_run | 10 |
| `RetrievalTrace.java` | runtime.retrieval_trace | 11 |
| `ContextDelivery.java` | runtime.context_delivery | 11 |
| `ConsumerEffect.java` | runtime.consumer_effect | 4 |

- 状态集合精确沿用 V009，无伪状态
- `RetrievalTrace` compact constructor 对 `consideredIds`/`deliveredIds` 执行 `List.copyOf` 防御性复制
- `byte[]` 字段遵循现有项目风格

### RetrievalTrace 不可变性证明

```java
public RetrievalTrace {
    consideredIds = consideredIds == null ? null : List.copyOf(consideredIds);
    deliveredIds = deliveredIds == null ? null : List.copyOf(deliveredIds);
}
```
- 构造后修改原始列表不会改变 record
- 从 accessor 取得的非空列表不可修改（`List.copyOf` 返回不可变视图）

## 4. 端口新增方法签名

### EvidenceReferencePort（10 个新增）

```java
void insertSource(Source source)
Source findSourceById(UUID sourceId)
void insertSourceUnit(SourceUnit unit)
SourceUnit findSourceUnitById(UUID sourceUnitId)
void insertSourcePayload(SourcePayload payload)
SourcePayload findSourcePayloadById(UUID payloadId)
void insertSourceAnchor(SourceAnchor anchor)
SourceAnchor findSourceAnchorById(UUID anchorId)
void insertSourceAnchorUnits(List<SourceAnchorUnit> units)
List<SourceAnchorUnit> findSourceAnchorUnitsByAnchorId(UUID anchorId)
```

保留 `verifyAnchorsExist(Set<UUID>)`。SourcePayload 仅返回元数据，无正文读取接口。

### MemoryGovernancePort（4 个新增）

```java
List<MemoryRelation> findMemoryRelationsByFromRevisionId(UUID fromRevisionId)
void insertActorRef(ActorRef actorRef)
ActorRef findActorRefById(UUID actorId)
ActorRef findActorRefByKindAndStableRef(String actorKind, String stableRef)
```

HDM-005 现有 11 个方法签名和语义均未改变。

### RuntimeTransactionPort（13 个新增，含 4 个 R1 CAS 修正）

插入方法（9 个）：
```java
void insertCaptureScope(CaptureScope scope)
void insertCaptureScopeUnits(List<CaptureScopeUnit> units)
void insertCloseoutRun(CloseoutRun run)
void insertCheckpoint(Checkpoint checkpoint)
void insertWorkArtifact(WorkArtifact artifact)
void insertModelRun(ModelRun run)
void insertRetrievalTrace(RetrievalTrace trace)
void insertContextDelivery(ContextDelivery delivery)
void insertConsumerEffect(ConsumerEffect effect)
```

时序/CAS 方法（4 个，R1 修正）：
```java
boolean freezeCaptureScope(UUID scopeId, OffsetDateTime frozenAt)

boolean transitionCloseoutRun(
    UUID runId, String expectedState, String newState,
    OffsetDateTime startedAt, OffsetDateTime terminalAt, String failureCode)

boolean transitionModelRun(
    UUID modelRunId, String expectedState, String newState,
    byte[] outputManifestHash, OffsetDateTime terminalAt, String failureCode)

boolean invalidateContextDelivery(
    UUID deliveryId, OffsetDateTime invalidatedAt, String invalidationReason)
```

- 返回值语义统一为"精确一行发生预期变更"
- C2 adapter 必须以精确 WHERE／affected-row=1 实现
- 旧不安全签名（无 `expectedState`、无返回值、无业务时间参数）残留：**0**

### RuntimeQueryPort（新建，13 个方法）

```java
CaptureScope findCaptureScopeById(UUID scopeId)
List<CaptureScopeUnit> findCaptureScopeUnitsByScopeId(UUID scopeId)
CloseoutRun findCloseoutRunById(UUID runId)
List<CloseoutRun> findCloseoutRunsByScopeId(UUID scopeId)
Checkpoint findCheckpointById(UUID checkpointId)
List<Checkpoint> findCheckpointsByRunKindAndRunId(String runKind, UUID runId)
WorkArtifact findWorkArtifactById(UUID artifactId)
List<WorkArtifact> findWorkArtifactsByRunId(UUID runId)
ModelRun findModelRunById(UUID modelRunId)
RetrievalTrace findRetrievalTraceById(UUID traceId)
ContextDelivery findContextDeliveryById(UUID deliveryId)
boolean existsConsumerEffect(String consumerCode, UUID eventId, String effectKey)
ConsumerEffect findConsumerEffectByKey(String consumerCode, UUID eventId, String effectKey)
```

## 5. 架构测试

| 测试类 | 测试数 | 结果 |
|--------|--------|------|
| `ArchitectureTest` | 7 | PASS |
| `DatabaseBoundaryTest` | 6 | PASS |
| `PortBoundaryTest`（R1 修正） | 5 | PASS |
| **合计** | **18** | **全部 PASS** |

### PortBoundaryTest（R1 修正后 5 个测试）

1. `portSignaturesMustNotExposeGeneratedTypes` — 端口不依赖 jOOQ/JDBC/Spring/生成类型
2. `portSignaturesMustNotExposeInfrastructureTypes` — 逐方法检查参数和返回类型
3. `negativePortFixtureMustViolateGeneratedTypeRule` — 非法 fixture 被同规则工厂拒绝
4. `domainAndPortSourcesMustNotContainBodyTextFieldNames` — 正文承载字段泄漏扫描
5. `bodyTextScanCanaryMustBeRejected` — **同规则 canary 证明禁止字段确实导致测试失败**

### 正文扫描真实性（R1-01 修正）

- 使用 `resolveRepoRoot()` 从工作目录向上查找 `.git` + `pom.xml`，找不到则 FAIL（不 skip）
- 扫描根（5 个目录）：evidence/port、memory/port、runtime/port、evidence/domain、runtime/domain
- Memory domain：**仅扫描 `ActorRef.java`**（`MemoryRevision.bodyText` 为合法字段，不纳入扫描）
- 每个扫描根断言目录存在且 `.java` 文件数 > 0
- 同规则 canary `BodyTextFieldCanary.java`（含 `bodyText` 字段）被同一判定器拒绝
- **扫描文件数：23**，**集合 SHA-256：`17c59af598a5391b48bdd289b2a9ea409f075855c9f20ba2260000a15e78b508`**

### 非法 fixture 清单

| Fixture | 所在包 | 违反规则 | 验证测试 |
|---------|--------|---------|---------|
| `EvidenceForbiddenFrameworkFixture` | evidence | 依赖 `java.sql.Connection` | `ArchitectureTest.negativeFixtureMustViolateForbiddenFrameworkRule` |
| `EvidenceForbiddenDatabaseFixture` | evidence | 依赖 `DatabaseAdapterModule` | `DatabaseBoundaryTest.evidenceFixtureIsRejectedByTheFormalDomainRule` |
| `EvidencePortForbiddenGeneratedTypeFixture` | evidence | 导入 jOOQ 生成 `Source` 表类 | `PortBoundaryTest.negativePortFixtureMustViolateGeneratedTypeRule` |
| `BodyTextFieldCanary` | arch.canary | 含 `bodyText` 字段名 | `PortBoundaryTest.bodyTextScanCanaryMustBeRejected` |

## 6. 验收结果

| 检查项 | 结果 |
|--------|------|
| RetrievalTrace 防御性复制 | PASS |
| RetrievalTrace 非空列表不可修改 | PASS（`List.copyOf` 返回不可变列表） |
| 扫描根存在 | PASS（5/5） |
| 扫描文件数 | 23 |
| 扫描集合 SHA-256 | `17c59af5...` |
| 正文合法集合 | PASS（`MemoryRevision.bodyText` 免检，ActorRef 无命中） |
| 同规则非法 canary | REJECTED（`bodyText` 被检测到） |
| Runtime 时序-CAS 新签名 | 4（`freezeCaptureScope`/`transitionCloseoutRun`/`transitionModelRun`/`invalidateContextDelivery`） |
| Runtime 旧不安全签名残留 | 0 |
| evidence/memory/runtime domain 不依赖 jOOQ/JDBC/Spring/database-adapter | PASS |
| port 签名不暴露生成类型或基础设施类型 | PASS |
| database-adapter 不反向污染 application/domain | PASS |
| 非法 fixture 被正式规则工厂拒绝 | PASS（4/4） |
| 正文承载字段泄漏命中 | 0（canary 除外） |
| 受影响模块编译 | PASS |
| architecture-tests | PASS（18/18） |
| 全仓 Maven offline clean verify | PASS |
| git diff --check | PASS（仅 CRLF 警告，无空白错误） |
| Maven | 3.9.16 |
| Java | 25.0.4 |

## 7. Fail-closed 边界

| 对象 | 处理 |
|------|------|
| DerivationBlock | ROUTED_LATER — 未新增 CRUD |
| DeletionFence | ROUTED_LATER — 未新增 CRUD |
| DeletionRun | ROUTED_LATER — 未建表，无 port/adapter |
| 所有新增 port 方法 | fail-closed `UnsupportedOperationException("...deferred to HDM-006 Slice C2")` |
| OPERATIONAL producer | 保持 PRODUCTION_DISABLED，无新增 producer 方法 |
| SKIP LOCKED/lease/retry/backoff | 属于 Slice D，本任务未加入 |

## 8. C2 强制门记录

当前 default fail-closed 仅为未实施期过渡。C2 最终封口前必须：

1. 三个既有 port（`EvidenceReferencePort`、`MemoryGovernancePort`、`RuntimeTransactionPort`）的新增 default 方法全部改为抽象方法
2. 正式 adapters 覆盖全部新增方法（`JooqEvidenceReferenceAdapter`、`JooqMemoryGovernanceAdapter`、`JooqRuntimeTransactionAdapter`、新建 `JooqRuntimeQueryAdapter`）
3. 编译测试证明无遗漏（所有端口方法有对应 adapter 实现）
4. 生产路径中 `UnsupportedOperationException("...deferred to HDM-006 Slice C2")` 残留为 **0**

## 9. Git 状态

- 真实 Git index：未变（起止一致）
- staged：0
- 越界：0（所有修改均在允许范围内）
- 修改文件：3（port 接口）
- 新增文件：21（14 domain + 2 port + 3 test + 1 canary + 1 report）
- 禁止修改范围（migration、jOOQ、adapter、application、security、apps、Node）：0 修改
- 联网：否
- Git 写操作：否

## 10. 最终回复

```text
封口状态：HDM006_SLICE_C_READY_FOR_LOCAL_COMMIT（Slice C 整体封口通过，Maven 3.9.16）
纠偏状态：HDM006_SLICE_C1_R1_READY_FOR_ADAPTER_IMPLEMENTATION
RetrievalTrace 防御性复制／不可修改：PASS／PASS
扫描根存在／扫描文件数／集合hash：PASS／23／17c59af598a5391b48bdd289b2a9ea409f075855c9f20ba2260000a15e78b508
正文合法集合／同规则非法 canary：PASS／REJECTED
Runtime 时序-CAS 新签名／旧签名残留：4／0
C2 default 清零强制门：RECORDED
Maven／Java：3.9.16／25.0.4
架构测试／全仓 Maven offline：PASS／PASS
真实 Git index：未变
工作区：tracked=3、untracked=21、staged=0、越界=0
联网／Git 写操作：否／否
报告路径：D:\myproject\hide-nest\reports\HDM-006-SC1-执行报告.md
```
