# HDM-006 Slice D 边界与 V010 决策只读预检报告（R1 纠偏）

`状态：HDM006_SLICE_D_PREFLIGHT_R1_READY_FOR_D1_ORDER` `基线 HEAD：2425883` `时间：2026-08-10` `纠偏依据：Task22A-R1` `性质：只读分析，不实施`

## 0. 执行摘要

基于 HDM-006 Slice A/B/C 已验收实现（HEAD `2425883`，工作区仅两份预检文件），对 Slice D 进行了只读预检。R1 纠偏已应用 hide 的 R1-01—R1-06 最终技术裁定。

**关键发现（R1 纠偏后）：**

- 原子领取方案：PostgreSQL 单 CTE + FOR UPDATE SKIP LOCKED + 调用方注入 now/leaseUntil + 返回按 sequenceNo ASC 排序的完整复核字段 DTO
- 结算语义：三态结果 SETTLED/ALREADY_SETTLED/LEASE_LOST（成功）；单原子入口自动决定 READY 1-7 或 FINAL_FAILED 8（失败）；STALE/DENIED 一次终止不重试
- 现有 outbox schema 足够：V003 列/V005 CHECK/V006 索引 全部就绪，无需新 migration（除 V010 DELETE 权限外）
- WorkArtifact：**V010_REQUIRED_IN_SLICE_D** — 原子批量清理 `purgeExpiredWorkArtifacts`，CTE + SKIP LOCKED + DELETE RETURNING；generated tree 完全不变
- 完成前复核：仅定义 port 与合成测试替身；不得创建查询真实数据库的 SyntheticCompletionGuard；生产继续禁用
- 退避参数：hide 正式冻结 HDMD006_D04（base=1s / max=10m / jitter=±25%）
- failure code 新增：0
- 实施切片：2（D1 数据库机械核心 / D2 application/worker 封口）
- 阻塞未决：0
- 权威冲突：0

---

## 1. 必读输入确认

全部 UTF-8 完整读取，逐项确认：

| # | 输入 | 路径 | 状态 |
|---|------|------|------|
| 1 | HDM-006 预检报告 R1 | `reports/HDM-006-预检报告.md` | 已读 |
| 2 | HDM-006 预检 Manifest R1 | `reports/HDM-006-PreflightManifest.json` | 已读，JSON 可解析 |
| 3 | Slice C2B 执行报告 | `reports/HDM-006-SC2B-执行报告.md` | 已读 |
| 4 | Slice C FinalEvidence | `reports/HDM-006-SC-FinalEvidence.json` | 已读，JSON 可解析 |
| 5 | V003 核心表 | `V003__core_tables.sql` | 已读 |
| 6 | V005 事务门 | `V005__transaction_gates_and_outbox.sql` | 已读 |
| 7 | V006 索引与权限 | `V006__indexes_and_privileges.sql` | 已读 |
| 8 | V009 runtime 表 | `V009__runtime_tables_operational_identity.sql` | 已读 |
| 9 | Runtime domain records | `modules/runtime/**/domain/*.java` | 已读 |
| 10 | RuntimeTransactionPort | `RuntimeTransactionPort.java` | 已读（17 abstract） |
| 11 | RuntimeQueryPort | `RuntimeQueryPort.java` | 已读（13 abstract） |
| 12 | JooqRuntimeTransactionAdapter | `JooqRuntimeTransactionAdapter.java` | 已读 |
| 13 | JooqRuntimeQueryAdapter | `JooqRuntimeQueryAdapter.java` | 已读 |
| 14 | apps/worker 源码 | `HideNestWorkerApplication.java` + `pom.xml` | 已读 |
| 15 | 正式详细契约 §2.5 | `hide记忆系统_正式开发详细契约_v0.1.md` | 已读 |
| 16 | failure code registry | `V002__roles_code_tables_and_roots.sql` | 已读（50 个 code） |
| 17 | Task22A-R1 纠偏工单 | `Task22A-R1-...纠偏工单.md` | 已读（R1-01—R1-06 全部） |

---

## 2. 现有 Outbox Schema 精确清单

### 2.1 列清单（runtime.outbox_event，V003）

| 列 | 类型 | 约束 |
|---|---|---|
| `event_id` | uuid | PK |
| `idempotency_key` | text COLLATE "C" | NOT NULL, UNIQUE |
| `sequence_no` | bigint | GENERATED ALWAYS AS IDENTITY, UNIQUE |
| `event_category` | text | NOT NULL, CHECK IN ('GOVERNED','OPERATIONAL') |
| `event_type` | text | NOT NULL |
| `aggregate_kind` | text | NOT NULL |
| `aggregate_id` | uuid | NOT NULL |
| `aggregate_revision` | bigint | NULLABLE, CHECK >=1 |
| `contract_version` | text | NOT NULL, CHECK = 'pink.event.v1' |
| `purpose` | text | NOT NULL, CHECK char_length 1-64 |
| `policy_revision` | bigint | NOT NULL, CHECK >=0 |
| `manifest_hash` | bytea | NOT NULL, CHECK octet_length=32 |
| `payload_manifest` | jsonb | NOT NULL, CHECK object |
| `change_event_id` | uuid | NULLABLE |
| `state` | text | NOT NULL DEFAULT 'READY', CHECK IN ('READY','LEASED','SUCCEEDED','FINAL_FAILED') |
| `available_at` | timestamptz | NOT NULL |
| `lease_owner` | text | NULLABLE |
| `lease_until` | timestamptz | NULLABLE |
| `attempt_count` | smallint | NOT NULL DEFAULT 0 |
| `max_attempts` | smallint | NOT NULL DEFAULT 8, CHECK =8 |
| `last_failure_code` | text | NULLABLE |
| `created_at` | timestamptz | NOT NULL |
| `completed_at` | timestamptz | NULLABLE |

### 2.2 关键 CHECK 约束（V003 + V005 + V009）

| 约束名 | 内容 |
|---|---|
| `outbox_event_state_check` | state IN ('READY','LEASED','SUCCEEDED','FINAL_FAILED') |
| `outbox_event_attempt_check` | max_attempts = 8 AND attempt_count BETWEEN 0 AND max_attempts |
| `outbox_event_lease_check` | state='LEASED' → lease_owner NOT NULL AND lease_until NOT NULL AND completed_at IS NULL; state<>'LEASED' → lease_owner IS NULL AND lease_until IS NULL |
| `outbox_event_completion_check` | state IN ('SUCCEEDED','FINAL_FAILED') → completed_at NOT NULL; state IN ('READY','LEASED') → completed_at IS NULL |
| `outbox_event_operational_kind_check` (V009) | event_category='OPERATIONAL' → aggregate_kind IN ('RUN','EFFECT','FACT') |

### 2.3 部分索引（V006）

| 索引名 | 定义 | 用途 |
|---|---|---|
| `outbox_event_ready_lookup` | ON (available_at, sequence_no) WHERE state='READY' | Worker 领取到期 READY |
| `outbox_event_lease_lookup` | ON (lease_until, sequence_no) WHERE state='LEASED' | Worker 回收过期租约 |

### 2.4 Worker 角色权限（V006 + V009）

- `GRANT SELECT ON ALL TABLES IN SCHEMA runtime TO hide_nest_worker`
- `GRANT INSERT ON runtime.outbox_event TO hide_nest_worker`
- `GRANT UPDATE (state, available_at, lease_owner, lease_until, attempt_count, last_failure_code, completed_at) ON runtime.outbox_event TO hide_nest_worker`
- `GRANT INSERT ON runtime.consumer_effect TO hide_nest_worker`
- `GRANT INSERT ON runtime.work_artifact TO hide_nest_worker`（**无 DELETE** — V010 gap）
- `GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA runtime TO hide_nest_worker`

### 2.5 consumer_effect 表（V009）

| 列 | 类型 | 约束 |
|---|---|---|
| `consumer_code` | text | NOT NULL, CHECK format |
| `event_id` | uuid | NOT NULL, FK→outbox_event |
| `effect_key` | text | NOT NULL |
| `recorded_at` | timestamptz | NOT NULL |
| **PK** | | (consumer_code, event_id, effect_key) |
| **触发器** | | BEFORE UPDATE OR DELETE → reject_immutable_mutation |

### 2.6 评估：现有 schema 是否足够

**是，现有 outbox schema 完全足够。**

唯一缺口：Worker 缺少 `DELETE ON runtime.work_artifact` 权限（V010 解决）。

---

## 3. A 组：Outbox 原子领取（R1-01 纠偏）

### 3.1 唯一实现（hide 已冻结裁定 R1-01）

**PostgreSQL 单 CTE + FOR UPDATE SKIP LOCKED + 调用方注入时钟。**

```sql
WITH next_batch AS (
    SELECT event_id
    FROM runtime.outbox_event
    WHERE (state = 'READY' AND available_at <= :now)
       OR (state = 'LEASED' AND lease_until <= :now)
    ORDER BY available_at ASC, sequence_no ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE runtime.outbox_event
SET state = 'LEASED',
    lease_owner = :leaseOwner,
    lease_until = :leaseUntil
FROM next_batch
WHERE runtime.outbox_event.event_id = next_batch.event_id
RETURNING
    runtime.outbox_event.event_id,
    runtime.outbox_event.sequence_no,
    runtime.outbox_event.event_category,
    runtime.outbox_event.event_type,
    runtime.outbox_event.aggregate_kind,
    runtime.outbox_event.aggregate_id,
    runtime.outbox_event.aggregate_revision,
    runtime.outbox_event.purpose,
    runtime.outbox_event.policy_revision,
    runtime.outbox_event.manifest_hash,
    runtime.outbox_event.payload_manifest,
    runtime.outbox_event.change_event_id,
    runtime.outbox_event.attempt_count,
    runtime.outbox_event.max_attempts;
```

**关键设计（R1-01 冻结）：**

1. **调用方注入时钟：** eligibility 使用参数 `:now`，不混用数据库时钟（`now()`/`transaction_timestamp()`）与应用时钟。方法签名为 `claimAndLeaseOutboxEvents(leaseOwner, now, leaseUntil, batchSize)`
2. **参数校验：** leaseOwner 非空、leaseUntil > now、batchSize 1—100
3. **UPDATE...RETURNING 不保证顺序：** adapter 返回前按 `sequenceNo ASC` 显式排序
4. **DTO 包含完成前复核所需全部字段：** `eventId, sequenceNo, eventCategory, eventType, aggregateKind, aggregateId, aggregateRevision, purpose, policyRevision, manifestHash, payloadManifest, changeEventId, attemptCount, maxAttempts`
5. **`manifestHash` 为 byte[]，防御性复制**
6. **`payloadManifest` 只能是 V005 allowlist 后的 manifest（5 键），不承载正文**

### 3.2 并发判定方式

| 场景 | 数据库判定 |
|---|---|
| **两 Worker 同时领取同一行** | SKIP LOCKED：先到者锁定，后来者跳过 |
| **过期租约再领取** | `WHERE state='LEASED' AND lease_until <= :now` + SKIP LOCKED |
| **非 owner 结算** | `WHERE event_id=? AND state='LEASED' AND lease_owner=?` → LEASE_LOST |

### 3.3 Domain Record（R1-01）

```java
// modules/runtime/src/main/java/.../domain/ClaimedOutboxEvent.java
public record ClaimedOutboxEvent(
        UUID eventId,
        long sequenceNo,
        String eventCategory,
        String eventType,
        String aggregateKind,
        UUID aggregateId,
        Long aggregateRevision,
        String purpose,
        long policyRevision,
        byte[] manifestHash,       // 防御性复制
        String payloadManifest,    // V005 allowlist 5 键，无正文
        UUID changeEventId,
        short attemptCount,
        short maxAttempts
) {
    public ClaimedOutboxEvent {
        manifestHash = manifestHash != null ? manifestHash.clone() : null;
    }
}
```

### 3.4 Port 方法签名（R1-01）

```java
/**
 * Atomically claim and lease the next batch of due outbox events.
 * Uses caller-provided clock (:now) for eligibility, not database clock.
 * Returns DTOs sorted by sequenceNo ASC (UPDATE...RETURNING order not guaranteed).
 * leaseOwner non-null, leaseUntil > now, batchSize 1–100.
 */
List<ClaimedOutboxEvent> claimAndLeaseOutboxEvents(
        String leaseOwner,
        OffsetDateTime now,
        OffsetDateTime leaseUntil,
        int batchSize);
```

---

## 4. B 组：成功、重试与第八次终败（R1-02/R1-03/R1-04 纠偏）

### 4.1 成功结算（R1-02）—— 三态结果

`boolean` 不足以表达幂等与租约丢失。使用受约束枚举：

```java
public enum OutboxSuccessSettlement { SETTLED, ALREADY_SETTLED, LEASE_LOST }
```

**同一数据库事务内操作：**

1. `SELECT ... FOR UPDATE` 锁定 `event_id + state=LEASED + lease_owner`；若行不存在 → 进入步骤 4
2. `INSERT INTO runtime.consumer_effect ... ON CONFLICT (consumer_code, event_id, effect_key) DO NOTHING`
3. `UPDATE runtime.outbox_event SET state='SUCCEEDED', lease_owner=NULL, lease_until=NULL, completed_at=? WHERE event_id=? AND state='LEASED' AND lease_owner=?`
4. 若步骤 1 租约未命中：检查是否 `state=SUCCEEDED AND 同一 ConsumerEffect 已存在` → ALREADY_SETTLED；否则 → LEASE_LOST

**禁止路径：**
- 禁止通过捕获 23505 后继续使用已 aborted 的 PostgreSQL 事务（使用 `ON CONFLICT DO NOTHING` 代替）
- 禁止出现 "ConsumerEffect 已存在但事件仍 LEASED，却直接返回幂等成功并遗留租约"

```java
/**
 * Settle a successfully processed outbox event.
 * Uses INSERT ... ON CONFLICT DO NOTHING (no aborted-tx 23505 catch).
 * Returns SETTLED (first time), ALREADY_SETTLED (idempotent replay),
 * or LEASE_LOST (lease expired or wrong owner).
 */
OutboxSuccessSettlement settleOutboxSuccess(
        UUID eventId,
        String leaseOwner,
        String consumerCode,
        String effectKey,
        OffsetDateTime completedAt);
```

### 4.2 失败结算（R1-03）—— 数据库原子决定第八次

删除原 `settleOutboxRetry` 与 `settleOutboxFinalFailed` 两个可被调用方误选的分裂接口。

**统一为单一原子入口：**

```java
public record OutboxFailureSettlement(
        String outcome,        // "RETRIED" | "FINAL_FAILED" | "LEASE_LOST" | "TERMINAL"
        String resultingState, // "READY" | "FINAL_FAILED"
        short attemptCount     // 新 attempt_count（1-8）
) {}
```

```java
/**
 * Record a failure. Database atomically decides:
 * - new attempt 1-7 → READY + clear lease + nextAvailableAt
 * - new attempt 8  → FINAL_FAILED + clear lease + completedAt
 * - not LEASED / owner mismatch → outcome=LEASE_LOST
 * - already terminal → outcome=TERMINAL
 *
 * Caller MUST NOT hardcode attempt=8; the DB increments and decides.
 */
OutboxFailureSettlement settleOutboxFailure(
        UUID eventId,
        String leaseOwner,
        OffsetDateTime nextAvailableAt,
        OffsetDateTime completedAt,
        String failureCode);
```

**关键语义：**

- 数据库单语句基于 `attempt_count + 1` 自动裁决 1-7 回 READY 还是 8→FINAL_FAILED
- 绝不允许 `READY + attempt_count=8`
- 当前非 LEASED／owner 不匹配 → outcome=LEASE_LOST
- 已终态 → outcome=TERMINAL
- 不写 ConsumerEffect

### 4.3 STALE/DENIED 终止结算（R1-04）—— 一次终止，不重试

正式契约："过期任务以 STALE／DENIED 结束"。不走退避，不重试八次。

```java
public record OutboxTerminalSettlement(
        String outcome,  // "TERMINATED" | "LEASE_LOST" | "ALREADY_TERMINAL"
        String state     // "FINAL_FAILED"
) {}
```

```java
/**
 * Terminate an event due to STALE or DENIED guard failure.
 * Directly enters FINAL_FAILED (existing state, no new states).
 * Clears lease; attempt_count increments by 1 (kept within 0-8);
 * does NOT artificially set to 8.
 * No ConsumerEffect, no fence lift, no re-confirmation.
 * No backoff — this is a one-shot termination.
 */
OutboxTerminalSettlement settleOutboxRejected(
        UUID eventId,
        String leaseOwner,
        OffsetDateTime completedAt,
        String failureCode);
```

**关键语义：**

- 直接进入现有终态 FINAL_FAILED（不新增状态）
- 清租约、保留精确 failureCode
- attempt_count 加 1 但不人为写成 8（保持 0—8 范围内）
- 不写 ConsumerEffect、不解除围栏、不重新确认
- 不走退避

**Guard Result 类型（R1-04）：** 不得用可构造矛盾组合的 `boolean + nullable strings`。使用封闭类型：

```java
public sealed interface CompletionGuardResult
        permits CompletionGuardResult.Pass,
                CompletionGuardResult.Stale,
                CompletionGuardResult.Denied {

    record Pass() implements CompletionGuardResult {}
    record Stale(String failureCode) implements CompletionGuardResult {
        public Stale { Objects.requireNonNull(failureCode); }
    }
    record Denied(String failureCode) implements CompletionGuardResult {
        public Denied { Objects.requireNonNull(failureCode); }
    }
}
```

合法值集合仅：PASS / STALE + 对应现有 stale code / DENIED + 对应现有 denied/unavailable code。

### 4.4 退避参数（R1-05）—— hide 正式冻结

**状态：`TECHNICAL_DECISION_ACCEPTED_HDM006_D04`**

```
newAttempt = currentAttempt + 1
delay = min(baseDelay × 2^(newAttempt - 1), maxDelay)
jitter = random.nextLong(-jitterRange, jitterRange + 1)
nextAvailableAt = now + delay + jitter
```

**冻结参数：**

| 参数 | 值 | 备注 |
|---|---|---|
| `baseDelay` | `PT1S`（1 秒） | hide 裁定 |
| `maxDelay` | `PT10M`（10 分钟） | hide 裁定 |
| `jitterRange` | `delay / 4`（±25% 均匀） | hide 裁定 |
| 时钟源 | `java.time.Clock`（可注入） | 必须注入 |
| 随机源 | `java.util.random.RandomGenerator`（可注入） | 必须注入 |
| 精度 | 整数毫秒 | delay ≥ 0 |
| 适用范围 | 仅 newAttempt 1—7 | newAttempt=8 不进退避（FINAL_FAILED） |

---

## 5. C 组：完成前复核与生产禁用（R1-04 纠偏）

### 5.1 Guard 设计（fail-closed seam）

**Port 定义：**

```java
// modules/runtime/src/main/java/.../port/CompletionGuardPort.java
public interface CompletionGuardPort {
    CompletionGuardResult check(
            UUID eventId,
            String aggregateKind,
            UUID aggregateId,
            Long aggregateRevision,
            String purpose);
}
```

**Result：** 封闭 `sealed interface`（见 §4.3），仅 PASS / Stale(failureCode) / Denied(failureCode)。

### 5.2 Guard 检查项与现有 failure code 映射

| 检查项 | 不通过条件 | 类型 | failureCode（现有） |
|---|---|---|---|
| **current revision** | aggregate_revision 非当前 revision | Stale | `EXPECTED_REVISION_STALE` (#29) |
| **policy revision** | 处理期间 policy 已更新 | Stale | `POLICY_REVISION_STALE` (#18) |
| **access** | 当前访问策略拒绝 | Denied | `ACCESS_DENIED` (#17) |
| **derivation block** | 目标被围栏 | Denied | `DERIVATION_BLOCKED` (#19) |
| **deletion fence** | 目标在删除围栏内 | Denied | `DELETION_FENCED` (#20) |
| **block/fence 表不存在** | ROUTED_LATER，无法检查 | Denied | `DATABASE_UNAVAILABLE` (#44) |

**不新增 failure code。**

### 5.3 Guard 结算流程（R1-04）

```
claim → business process → guard.check()
  ├─ Pass         → settleOutboxSuccess (SETTLED/ALREADY_SETTLED/LEASE_LOST)
  ├─ Stale(code)  → settleOutboxRejected (一次终止，不重试)
  └─ Denied(code) → settleOutboxRejected (一次终止，不重试)
```

**STALE/DENIED 不再走退避**。直接进入 FINAL_FAILED。

### 5.4 合成 Guard 实现约束（R1-04）

**本阶段只定义 port 与合成测试替身。不得创建名为 `SyntheticCompletionGuard` 却查询真实数据库的生产实现。** 真实 block/fence adapter 后置（HDM-017/018），Worker 生产保持禁用。

D2 中的 "合成 guard" 是一个 **测试替身（test double）**，用于验证协调器对 Pass/Stale/Denied 三种结果的正确响应。它不连接数据库、不查询 memory_record、不访问任何真实表。

### 5.5 特别判断

**问：在真实 block/fence port 尚未落地时，本 Slice 能否只完成"机械 Worker 核心 + 合成 guard 契约"，而不宣称业务可生产运行？**

**答：可以。** 机械 Worker 核心（claim/lease/settle/reject）完全独立于 block/fence 表。合成 guard 作为测试替身验证接口契约。待 HDM-017/018 落地真实表后替换。所有 OPERATIONAL producer 继续 PRODUCTION_DISABLED。apps/worker 只提供可禁用调度壳与依赖装配。

---

## 6. D 组：WorkArtifact 与 V010（R1-06 纠偏）

### 6.1 裁定

**`V010_REQUIRED_IN_SLICE_D`**

### 6.2 原子批量清理（R1-06）

不使用"先查询再逐条删除"的两个 port（避免 TOCTOU 且多一次往返）。使用单个事务原子方法：

```java
/**
 * Atomically purge expired work artifacts.
 * Single CTE + FOR UPDATE SKIP LOCKED + DELETE ... RETURNING artifact_id.
 * Returns purged artifact IDs in stable order.
 * cutoff: delete WHERE expires_at < cutoff; batchSize 1–100.
 */
List<UUID> purgeExpiredWorkArtifacts(OffsetDateTime cutoff, int batchSize);
```

**数据库实现：**

```sql
WITH expired AS (
    SELECT artifact_id
    FROM runtime.work_artifact
    WHERE expires_at < :cutoff
    ORDER BY expires_at ASC, artifact_id ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
DELETE FROM runtime.work_artifact
USING expired
WHERE runtime.work_artifact.artifact_id = expired.artifact_id
RETURNING runtime.work_artifact.artifact_id;
```

### 6.3 V010 迁移

```sql
-- V010__work_artifact_delete_privilege.sql
GRANT DELETE ON runtime.work_artifact TO hide_nest_worker;
```

**仅 1 条语句。不改变表结构。**

### 6.4 影响评估（R1-06 修正）

| 影响项 | 评估 |
|---|---|
| 迁移计数 | +1（V010，仅 GRANT DELETE） |
| **jOOQ generated tree** | **必须完全不变**（表结构未变） |
| Flyway history | 9→10（history 表更新，不影响 jOOQ 生成） |
| 现有测试 | 0 影响 |
| 新增测试 | 1 个：原子批量清理 + 两 Worker 不重复删除 + 未过期不删 |
| 两 Worker 并发清理 | SKIP LOCKED 保证不重复删除同一行 |
| 架构边界 | port 方法在 runtime 模块，adapter 在 database-adapter 模块 |

---

## 7. 实施切片（R1 同步纠正）

### 7.1 D1：数据库机械核心（25-40 分钟，硬停止 50 分钟）

**目标：** 实现全部 R1 纠偏后的 port 方法 + jOOQ adapter + 原子领取/结算/并发/重试/攻击测试。含 V010 原子批量清理。

**允许路径：**
- `modules/runtime/src/main/java/.../domain/ClaimedOutboxEvent.java`（新增，含全部复核字段）
- `modules/runtime/src/main/java/.../domain/OutboxSuccessSettlement.java`（新增 enum）
- `modules/runtime/src/main/java/.../domain/OutboxFailureSettlement.java`（新增 record）
- `modules/runtime/src/main/java/.../domain/OutboxTerminalSettlement.java`（新增 record）
- `modules/runtime/src/main/java/.../domain/CompletionGuardResult.java`（新增 sealed interface）
- `modules/runtime/src/main/java/.../port/RuntimeTransactionPort.java`（扩展 4 个方法）
- `modules/runtime/src/main/java/.../port/RuntimeQueryPort.java`（不变）
- `modules/runtime/src/main/java/.../port/CompletionGuardPort.java`（新增接口）
- `modules/database-adapter/src/main/java/.../adapter/JooqRuntimeTransactionAdapter.java`（扩展实现）
- `modules/database-adapter/src/main/java/.../adapter/JooqRuntimeQueryAdapter.java`（不变）
- `modules/database-adapter/src/main/resources/db/migration/V010__work_artifact_delete_privilege.sql`（新增，1 句 GRANT）
- `modules/database-adapter/src/test/java/.../database/DatabaseSliceD1OutboxMechanicsTest.java`（新增）
- `modules/architecture-tests/**`（架构边界验证）

**禁止路径：**
- V001–V009 migration（只读）
- apps/worker/**
- modules/application/**
- modules/security/**
- 任何业务 coordinator/调度循环
- 任何名为 SyntheticCompletionGuard 查询真实数据库的实现

**前置：** Slice A+B+C 完成（HEAD 2425883）、PG18+pgvector、Maven 3.9.16+Java 25.0.4

**精确测试（R1 纠偏）：**

| # | 测试 | 覆盖 |
|---|---|---|
| 1 | 单 Worker 领取到期 READY，验证返回 DTO 按 sequenceNo ASC | R1-01 排序 |
| 2 | 两 Worker SKIP LOCKED 并发，验证集合不相交 | 并发竞争 |
| 3 | 租约过期再领取，旧 owner 结算 → LEASE_LOST | 租约回收 |
| 4 | 非 owner 结算 → LEASE_LOST | 权限边界 |
| 5 | 成功结算 → SETTLED，ConsumerEffect 写入 + SUCCEEDED | R1-02 主路径 |
| 6 | 同事件同 ConsumerEffect 重复 + 已 SUCCEEDED → ALREADY_SETTLED | R1-02 幂等 |
| 7 | 同事件同 ConsumerEffect 重复 + 仍 LEASED 异常组合：不遗留租约、不伪成功 | R1-02 攻击测试 |
| 8 | attempt=6 失败 → READY/attempt=7；attempt=7 失败 → FINAL_FAILED/attempt=8 | R1-03 边界 |
| 9 | 绝无 READY + attempt_count=8 | R1-03 不变量 |
| 10 | 已终态结算 → TERMINAL | R1-03 终态 |
| 11 | STALE/DENIED → settleOutboxRejected，一次终止 FINAL_FAILED，不重试 | R1-04 |
| 12 | 已终态 reject → ALREADY_TERMINAL | R1-04 |
| 13 | 退避计算：Clock.fixed + 确定种子 RandomGenerator，精确验证每 attempt 的 available_at | R1-05 |
| 14 | V010：purgeExpiredWorkArtifacts 原子批量清理 + 两 Worker 不重复删除 + 未过期不删 | R1-06 |
| 15 | 正文/secret canary 零命中（ClaimedOutboxEvent 无正文字段） | 泄漏扫描 |
| 16 | 架构边界：domain 不依赖 jOOQ，adapter 不泄漏生成类型 | 架构 |
| 17 | V010 后 jOOQ generated tree MATCH（完全不变） | R1-06 |

**停止条件：** 所有 17 个数据库测试 PASS + 架构边界 PASS + 正文泄漏零命中 + jOOQ generated tree MATCH + V010 仅 GRANT DELETE 无表结构变化

**预计：** 35-40 分钟

---

### 7.2 D2：Application/Worker 封口（25-40 分钟，硬停止 50 分钟）

**目标：** 协调器、合成 guard 测试替身（不查真实数据库）、禁用式 worker 装配、机械 runner、全仓门。

**允许路径：**
- `modules/runtime/src/main/java/.../port/CompletionGuardPort.java`（如 D1 已创建则引用）
- `modules/application/src/main/java/.../coordinator/OutboxWorkerCoordinator.java`（新增）
- `modules/application/src/main/java/.../coordinator/StubCompletionGuard.java`（测试替身，不查 DB）
- `modules/application/src/main/java/.../config/BackoffCalculator.java`（新增，HDM006_D04 参数）
- `apps/worker/src/main/java/.../worker/OutboxWorkerRunner.java`（新增，可禁用调度壳）
- `apps/worker/src/main/java/.../worker/config/WorkerConfiguration.java`（新增，依赖装配）
- `modules/architecture-tests/**`（Worker 不依赖 API）

**禁止路径：**
- V001–V010 migration（只读）
- memory schema 表/触发器/函数
- GOVERNED outbox 生产路径
- modules/security/**
- apps/api/**
- 真实业务 guard（查询数据库的 SyntheticCompletionGuard）
- 虚假业务 consumer
- 真实 block/fence adapter

**前置：** Slice D1 完成

**精确测试（R1 纠偏）：**

| # | 测试 | 覆盖 |
|---|---|---|
| 1 | 协调器完整循环：claim → process(no-op) → guard=Pass → settleSuccess=SETTLED | 全路径 |
| 2 | ConsumerEffect 幂等重放 → ALREADY_SETTLED | R1-02 |
| 3 | guard=Stale → settleOutboxRejected（一次终止，不走退避） | R1-04 |
| 4 | guard=Denied → settleOutboxRejected（一次终止） | R1-04 |
| 5 | 业务处理失败 → settleOutboxFailure → attempt=1 READY（退避） | R1-03 |
| 6 | 第 7 次 attempt → settleOutboxFailure → FINAL_FAILED | R1-03 |
| 7 | Stub guard：Pass 场景 | 测试替身 Pass |
| 8 | Stub guard：Stale + EXPECTED_REVISION_STALE | 测试替身 Stale |
| 9 | Stub guard：Denied + DERIVATION_BLOCKED | 测试替身 Denied |
| 10 | 退避计算器：HDM006_D04 参数，整数毫秒，delay≥0 | R1-05 |
| 11 | Worker 调度壳：可通过配置 disabled | 禁用验证 |
| 12 | Worker 不依赖 API 模块 | 架构 |
| 13 | 所有 OPERATIONAL producer 仍 PRODUCTION_DISABLED | D03 |
| 14 | 正文 canary 零命中 | 泄漏 |
| 15 | FINAL_FAILED 不解除围栏 | 不变量 |
| 16 | 全仓 Maven offline verify | 全仓 |

**停止条件：** 所有 outbox 机械门 PASS（6 GAP→PASS）+ effect 幂等通过 + 正文泄漏零命中 + FINAL_FAILED 不解除围栏 + 全仓 Maven PASS + OPERATIONAL producer 全部禁用

**预计：** 30-40 分钟

---

### 7.3 两片不可合并

D1 定义和实现 port 方法（纯数据库层，含所有原子语义与攻击测试）；D2 消费 port 方法（application/worker 层，协调器 + 测试替身 guard + 调度壳）。各片独立停止条件，合并超 50 分钟硬停止。

---

## 8. 自检结果

| 检查项 | 结果 |
|---|---|
| HEAD | `2425883`（匹配基线） |
| 分支 | `main` |
| `git status --short` | 仅两份预检文件 `??` |
| 暂存区 | 0 文件 |
| 实现/测试/联网/Git 写 | 0/0/否/否 |
| V001–V009 migration | MATCH（未修改） |
| jOOQ generated tree | MATCH（未修改） |
| failure code 新增 | 0 |
| 旧签名/旧语义残留 | 0（boolean 结算→三态 enum；分裂 failure→单入口；boolean+nullable guard→sealed interface） |
| blockingUnknowns | 0 |
| authorityConflicts | 0 |
| plannedSlices | 恰好 2（D1/D2） |
| hideDecisionRecords | D01–D04（D04 新增：退避参数冻结） |
| V010 裁定 | REQUIRED，原子批量清理，generated MATCH |
| 报告与 JSON 计数一致 | 已确认 |

---

```text
纠偏状态：HDM006_SLICE_D_PREFLIGHT_R1_READY_FOR_D1_ORDER
领取 DTO／顺序：完整复核字段／sequenceNo ASC
成功结算结果：SETTLED／ALREADY_SETTLED／LEASE_LOST
失败结算：单原子入口，READY 1-7／FINAL_FAILED 8
STALE／DENIED：一次终止，不重试
退避裁定：HDM006_D04（1s／10m／±25%）
WorkArtifact：V010 + 原子批量清理；generated MATCH
实施切片／阻塞未决／权威冲突：2/0/0
实现／测试／联网／Git 写操作：0／0／否／否
工作区：仅两份允许文件，staged=0
报告路径：D:\myproject\hide-nest\reports\HDM-006-SD-预检报告.md
清单路径：D:\myproject\hide-nest\reports\HDM-006-SD-PreflightManifest.json
```
