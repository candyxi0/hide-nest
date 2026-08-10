# HDM-006 Slice C2A Evidence/Memory 适配器实施 执行报告

`状态：HDM006_SLICE_C2A_EVIDENCE_MEMORY_ADAPTERS_READY_FOR_HIDE_REVIEW` `基线 HEAD：b140968` `时间：2026-08-10`

## 1. 执行摘要

完成 HDM-006 Slice C2A Evidence 与 Memory 普通持久化 adapter 实施及真实数据库契约测试。Evidence 11 个 port 抽象方法全部由 JooqEvidenceReferenceAdapter 实现；Memory 17 个 port 抽象方法全部由 JooqMemoryGovernanceAdapter 实现（含 13 个 HDM-005 已有 + 4 个 HDM-006 新增）。所有数据库测试通过 Testcontainers PostgreSQL 18 + pgvector 固定 digest、Flyway V001—V009 迁移。

## 2. 环境

| 项目 | 实际 |
|------|------|
| JDK | 25.0.4-hotspot (Temurin) |
| Maven | 3.9.16 |
| Java version 属性 | 25 |

## 3. C1 累积改动（SC1 已验收，未变更）

### 新增领域对象（15 个）

| 模块 | 文件 | 对应表 |
|------|------|--------|
| evidence | Source.java | evidence.source |
| evidence | SourceUnit.java | evidence.source_unit |
| evidence | SourcePayload.java | evidence.source_payload |
| evidence | SourceAnchor.java | evidence.source_anchor |
| evidence | SourceAnchorUnit.java | evidence.source_anchor_unit |
| memory | ActorRef.java | memory.actor_ref |
| runtime | CaptureScope.java 等 9 个 | runtime.* |

### 端口新增方法签名

- EvidenceReferencePort：10 个新增（C1 为 fail-closed default）
- MemoryGovernancePort：4 个新增（C1 为 fail-closed default）
- RuntimeTransactionPort：13 个新增 + RuntimeQueryPort 新建（C1 为 fail-closed default；留给 C2B）

## 4. C2A 本任务新增改动

### 4.1 端口 default 清理

| 文件 | 变更 | 残留 default-UOE |
|------|------|-----------------|
| `EvidenceReferencePort.java` | 10 个 `default` + UOE → `abstract` | 0 |
| `MemoryGovernancePort.java` | 4 个 `default` + UOE → `abstract` | 0 |

- `verifyAnchorsExist` 在 C1 已为 abstract，本任务将其从假实现改为真实数据库查询。
- Runtime port 的过渡 default 未修改（留给 C2B）。

### 4.2 JooqEvidenceReferenceAdapter（11 个 @Override 方法）

| 方法 | 操作 |
|------|------|
| `insertSource` | INSERT → evidence.source，映射全部 9 字段 |
| `findSourceById` | SELECT by PK → Source record；not-found → null |
| `insertSourceUnit` | INSERT → evidence.source_unit，映射全部 8 字段 |
| `findSourceUnitById` | SELECT by PK → SourceUnit record；not-found → null |
| `insertSourcePayload` | INSERT → evidence.source_payload，仅元数据列（14 字段），禁止正文 |
| `findSourcePayloadById` | SELECT by PK → SourcePayload record；not-found → null |
| `insertSourceAnchor` | INSERT → evidence.source_anchor，映射全部 4 字段 |
| `findSourceAnchorById` | SELECT by PK → SourceAnchor record；not-found → null |
| `insertSourceAnchorUnits` | 批量 INSERT → evidence.source_anchor_unit；空集合 no-op |
| `findSourceAnchorUnitsByAnchorId` | SELECT by anchor_id ORDER BY ordinal ASC → List |
| `verifyAnchorsExist` | SELECT COUNT WHERE anchor_id IN (set)；count ≠ size → RuntimeException |

约束：
- `SourcePayload` 仅映射元数据列，无正文/prompt/answer/raw payload 列
- AnchorUnit 查询按 `ordinal ASC` 排序
- 批量插入空集合确定性 no-op
- `verifyAnchorsExist` fail-closed：缺任一 anchor 即拒绝
- not-found 统一返回 `null`（非 Optional）

### 4.3 JooqMemoryGovernanceAdapter（17 个 @Override 方法）

**C2A 新增 4 个：**

| 方法 | 操作 |
|------|------|
| `insertActorRef` | INSERT → memory.actor_ref，映射全部 5 字段 |
| `findActorRefById` | SELECT by PK → ActorRef record；not-found → null |
| `findActorRefByKindAndStableRef` | SELECT by (actor_kind, stable_ref) → ActorRef；not-found → null |
| `findMemoryRelationsByFromRevisionId` | SELECT by from_revision_id ORDER BY created_at ASC, relation_id ASC → List |

**HDM-005 已有方法修正：**

| 方法 | 变更 |
|------|------|
| `insertMemoryRelations` | 从 fail-closed `RuntimeException` → V008 正式写入；空集合 no-op；约束异常不吞 |

- 关系查询按 `created_at ASC, relation_id ASC` 稳定排序
- `EVIDENCED_BY` 仅 anchor target，其他 8 类仅 revision target
- 数据库约束（`memory_relation_target_check`）真实拒绝非法组合，adapter 不吞异常
- HDM-005 其他 12 个方法签名和语义均未改变

### 4.4 架构测试

| 新增测试 | 说明 |
|---------|------|
| `evidenceAdapterOverridesMustExactlyMatchPortAbstractMethods` | Evidence port 11 个 abstract 方法 = adapter 11 个 public 方法（精确相等） |
| `memoryAdapterOverridesMustExactlyMatchPortAbstractMethods` | Memory port 17 个 abstract 方法 = adapter 17 个 public 方法（精确相等） |
| `negativeMemoryPortFixtureMustViolateGeneratedTypeRule` | Memory 非法 fixture 导入 jOOQ `ActorRef` 表类被同规则拒绝 |
| `MemoryPortForbiddenGeneratedTypeFixture.java` | 新增 memory 侧同规则非法 fixture |

C1 已有 5 个 PortBoundaryTest 全部保持 PASS。

### 4.5 数据库测试

新增 `DatabaseSliceC2AEvidenceMemoryAdapterTest`（14 个测试）：

| # | 测试 | 覆盖要求 |
|---|------|---------|
| 1 | Source round-trip + `(platform, external_ref)` 重复拒绝 | 工单 5.1 |
| 2 | SourceUnit round-trip + 版本唯一约束 | 工单 5.2 |
| 3 | SourcePayload metadata round-trip + 32-byte hash + policy revision FK | 工单 5.3 |
| 4 | SourceAnchor + 多 AnchorUnit 顺序 round-trip（ordinal ASC） | 工单 5.4 |
| 5 | 跨 source AnchorUnit 被 V008 同源 trigger 拒绝 | 工单 5.5 |
| 6 | `verifyAnchorsExist` 全存在通过、缺一个拒绝 | 工单 5.6 |
| 7 | ActorRef id/kind+stableRef 两条查询 round-trip，重复稳定引用拒绝 | 工单 5.7 |
| 8 | MemoryRelation 两种合法目标（EVIDENCED_BY→anchor, INTERPRETS→revision）各通过 | 工单 5.8 |
| 9 | EVIDENCED_BY→revision + SUPPORTS→anchor 两类攻击均拒绝 | 工单 5.9 |
| 10 | find-not-found 精确返回 null，不产生伪对象 | 工单 5.10 |
| 11 | adapter 查询/返回对象中正文 canary 命中 0（bodyText/prompt/answer/rawPayload） | 工单 5.11 |
| 12 | 数据库异常不被吞掉或转成空集合（FK 违反仍抛出） | 工单 5.12 |
| 13 | 空集合批量插入确定性 no-op | 追加 |
| 14 | MemoryRelation 查询按 created_at/relation_id 稳定排序 | 追加 |

测试调用正式 adapter 方法，仅用直接 SQL 创建 FK 必备前置数据（遵循 `insertMemory()` 全路径模式，含 governed outbox）。

## 5. 验收结果

| 检查项 | 结果 |
|--------|------|
| Evidence adapter @Override 方法 | 11/11（精确匹配 port abstract） |
| Memory adapter @Override 方法 | 17/17（精确匹配 port abstract） |
| Evidence C1 default-UOE 残留 | 0 |
| Memory C1 default-UOE 残留 | 0 |
| C2A 数据库测试 | 14/14/0/0/0 |
| Source-Unit-Payload-Anchor round-trip | PASS |
| Actor-Ref / Memory-Relation round-trip | PASS |
| 跨源 AnchorUnit 攻击 | REJECTED |
| 关系目标攻击（EVIDENCED_BY→revision / SUPPORTS→anchor） | REJECTED / REJECTED |
| not-found 返回 null | PASS |
| 排序（ordinal / created_at+relation_id） | PASS / PASS |
| 异常传播（不被吞） | PASS |
| Slice B 测试 | 95/95 |
| Slice C 测试 | 15/15 |
| 架构测试（ArchitectureTest + DatabaseBoundaryTest + PortBoundaryTest） | 7+6+8 = 21/21 |
| 同规则非法 fixture（evidence + memory） | PASS / PASS |
| 全仓 Maven offline clean verify | PASS（12 模块） |
| git diff --check | PASS（仅 CRLF 警告，无空白错误） |
| V001—V009 migration | MATCH（未修改） |
| jOOQ generated tree | MATCH（未修改） |
| 正文/secret 泄漏 | 0/0 |
| Node/API/Worker | NOT_APPLICABLE |

## 6. Fail-closed 边界

| 对象 | 状态 |
|------|------|
| SourcePayload body text | 禁止 — adapter 仅映射元数据列 |
| verifyAnchorsExist | fail-closed — count ≠ size 即拒绝 |
| insertMemoryRelations | 约束异常传播 — adapter 不吞 |
| EVIDENCED_BY / 非 EVIDENCED_BY 目标 | 数据库 CHECK 约束拒绝 |
| Runtime port/adapter | ROUTED_LATER → HDM-006 Slice C2B |
| Worker mechanics | ROUTED_LATER → HDM-006 Slice D |

## 7. Git 状态

- 真实 Git index：未变（起止一致）
- staged：0
- 越界：0（所有修改均在允许范围）
- 修改文件（tracked）：5（3 port + 2 adapter；RuntimeTransactionPort 为 C1 遗留）
- 新增文件（untracked）：22（含 C1 遗留 19 + C2A 新增 3）
- C2A 新增文件：`PortBoundaryTest.java`（修改）、`MemoryPortForbiddenGeneratedTypeFixture.java`、`DatabaseSliceC2AEvidenceMemoryAdapterTest.java`
- 禁止修改范围（migration、jOOQ、domain、Runtime adapter、application、security、apps、Node）：0 修改
- 联网：否
- Git 写操作：否

## 8. Slice C 整体封口追记（2026-08-10 22:03）

Maven 3.9.16 + Java 25.0.4 全仓离线 `clean verify` 复跑通过。本报告 Maven 版本已从 3.9.5 纠正为 3.9.16，历史测试结论与计数不变。Slice C 已通过整体封口。

```text
封口状态：HDM006_SLICE_C_READY_FOR_LOCAL_COMMIT
Maven／Java：3.9.16／25.0.4
C2A／C2B／Slice B／Slice C：14/14／14/14／95/95／15/15
本次 Surefire 总计／失败／错误／跳过：199/0/0/0
架构正式／同规则非法 fixture：PASS／PASS
全仓 Maven offline：PASS
V001—V009／generated：MATCH／MATCH
default-UOE／正文／secret 泄漏：0／0／0
WorkArtifact 清理：ROUTED_TO_HDM006_SLICE_D_V010_DECISION
报告一致性／Evidence JSON：PASS／NOT_CREATED
真实 Git index：未变
联网／Git 写操作：否／否
```
