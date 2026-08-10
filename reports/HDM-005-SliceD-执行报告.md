# HDM-005 Slice D 执行报告（负向架构与事务门封口）— R1纠偏

## 结论

**HDM005_SLICE_D_ROUTED_GAPS_SEALED_READY_FOR_LOCAL_COMMIT**

现有测试（架构 13、Slice B 35、Slice C 15）覆盖工单要求的架构负向门、GOVERNED CE/绑定门、合法合成 CLOSEOUT_RUN 和数据库 EXACT_OBJECT/wildcard/JSON 门。两项超出当前 schema 能力的缺口已路由至后续 HDM。无新增测试代码。

| 验证项 | 结果 |
| --- | --- |
| 架构正式／同规则非法 fixture | PASS／PASS |
| application-domain-adapter 反向污染 | 全部拒绝 |
| GOVERNED 缺 CE／错绑定 | REJECTED／REJECTED |
| 合法合成 CLOSEOUT_RUN（无 CE） | PASS |
| 数据库 EXACT_OBJECT／wildcard／JSON scope 拒绝 | PASS／全部拒绝 |
| OPERATIONAL identity run/effect/fact 值约束 | ROUTED_TO_HDM006_PRODUCTION_DISABLED |
| actor-role-purpose-effect／actor-owned scope 判定 | ROUTED_TO_HDM007_008_FAIL_CLOSED |
| Slice B／Slice C | 35/35／15/15 |
| Maven offline／Node／API-Worker | PASS／PASS／PASS |
| 正文／secret 泄漏 | 0／0 |
| 遗留进程／Docker 新增残留 | 0／0 |
| 真实 Git index | 未变 |

---

## 1. 基线确认

```text
repo   = D:\myproject\hide-nest
branch = main
HEAD   = c3c429ddfda892763a91de3449eff33d5351a671
parent = 7d174be08cbc1cf5fbc23572f8f8754e779216e8
status = clean（0 staged, 0 modified）
JDK    = OpenJDK 25.0.3 (IntelliJ JBR)
```

## 2. 架构负向门——已通过

### 2.1 正式门与非法 fixture 映射

| 正式规则 | 正式测试 | 非法 fixture | Fixture 违规导入 | 判定 |
| --- | --- | --- | --- | --- |
| `applicationBoundaryRule` | `formalBoundariesPassForMainClasses` | `ApplicationForbiddenDependencyFixture` | `DatabaseAdapterModule`, `java.sql.Connection` | PASS (PASS/PASS) |
| `domainBoundaryRule` | `formalBoundariesPassForMainClasses` | `EvidenceForbiddenDatabaseFixture` | `DatabaseAdapterModule` | PASS (PASS/PASS) |
| `databaseBoundaryRule` | `formalBoundariesPassForMainClasses` | `DatabaseForbiddenApplicationFixture` | `ApplicationModule` | PASS (PASS/PASS) |
| `generatedTypeBoundaryRule` | `formalBoundariesPassForMainClasses` | `ApplicationForbiddenGeneratedTypeFixture` | `database.generated.memory.tables.ActorRef` | PASS (PASS/PASS) |
| `projectClassesMustNotUseReactiveDatabaseApisRule` | `formalBoundariesPassForMainClasses` | `ApplicationForbiddenReactiveFixture` | `io.r2dbc.spi.ConnectionFactory` | PASS (PASS/PASS) |
| `forbiddenFrameworkDependencyRule` | `domainMustNotDependOnForbiddenFrameworks` | `EvidenceForbiddenFrameworkFixture` | `java.sql.Connection`, `java.sql.DriverManager` | PASS (PASS/PASS) |
| `apiAndWorkerMustNotDependOnEachOther` | `apiAndWorkerMustNotDependOnEachOther` | —（双向检查，无需 fixture） | — | PASS |

### 2.2 关键判定

- **全部 fixture 使用与正式门相同的规则工厂方法**，非仅 grep 文本
- 所有架构负向门均已通过正式测试与同规则非法 fixture 验证

### 2.3 补充正式测试

| 测试 | 覆盖 |
| --- | --- |
| `domainModulesMustNotDependOnApps` | domain 不依赖 api/worker |
| `domainModulesMustNotDependOnEachOther` | evidence/memory/runtime/security 相互隔离 |
| `domainModulesMustNotDependOnContracts` | domain 不依赖 contracts |
| `allDomainMarkersMustBeImportable` | 六模块标记类可导入 |

---

## 3. B09 Outbox 事务门——已通过 vs 路由

### 3.1 已验证通过

| 要求 | 测试 | SQLSTATE | 机制 | 判定 |
| --- | --- | --- | --- | --- |
| GOVERNED 缺 ChangeEvent → COMMIT 失败 | `aggregateOutboxGuardIT` | 23503 | FK `outbox_event_change_event_id_fkey` | REJECTED |
| GOVERNED CE NULL decision_id → 失败 | `aggregateOutboxGuardIT` | 23514 | trigger `enforce_outbox_change_event_match` | REJECTED |
| GOVERNED 合法路径 → 成功 | `aggregateOutboxGuardIT` | — | — | PASS |
| GOVERNED detached CE（无 decision）→ 失败 | `governedFactBindingIT` | 23514 | trigger `enforce_outbox_change_event_match` | REJECTED |
| GOVERNED 错误 event type → 失败 | `governedFactBindingIT` | 23514 | trigger: event_type ≠ change.event_type | REJECTED |
| GOVERNED 重复 outbox（同一 CK）→ 失败 | `governedFactBindingIT` | 23505 | unique index `outbox_event_change_unique` | REJECTED |
| 合法合成 OPERATIONAL 无 CE → 成功（CLOSEOUT_RUN） | `aggregateOutboxGuardIT` | — | — | PASS |
| 全部测试无业务正文 | 所有值均为 SYNTHETIC/DATABASE_TEST | — | — | PASS |

### 3.2 路由缺口：OPERATIONAL identity（run/effect/fact）值约束

- **现状**：`aggregate_kind` 列仅具 NOT NULL + 单列约束，无 CHECK 限制 OPERATIONAL 事件的 identity 值必须属于 run/effect/fact
- **已知局限**：当前 schema 无法在数据库层证明 `aggregate_kind` 值属于合法 identity 枚举
- **路由**：`ROUTED_TO_HDM006` — OPERATIONAL identity 的稳定物理模型与生产者扩展归 HDM-006
- **围栏**：在 HDM-006 完成前，**禁止启用生产 OPERATIONAL producer**；当前 `CLOSEOUT_RUN` 仅为合成参考路径
- **不追加 V008**，不猜 run/effect/fact 字段或字符串枚举；不改变 HDM-005 已提交代码

---

## 4. B04 Exact Scope 门——已通过 vs 路由

### 4.1 已验证通过（数据库层）

| 要求 | 测试 | SQLSTATE | 机制 | 判定 |
| --- | --- | --- | --- | --- |
| 精确 EXACT_OBJECT scope → 成功 | `accessPolicyConcurrencyIT` | — | — | PASS |
| SELF scope → 拒绝 | `accessPolicyConcurrencyIT` | 23514 | CHECK `access_policy_grant_scope_check` | REJECTED |
| `*` wildcard scope → 拒绝 | `accessPolicyConcurrencyIT` | 23514 | CHECK `access_policy_grant_scope_check` | REJECTED |
| JSON scope `{"scope":"owner"}` → 拒绝 | `accessPolicyConcurrencyIT` | 23514 | CHECK `access_policy_grant_scope_check` | REJECTED |

- 所有拒绝均命中 CHECK violation (23514)，非"不存在行"错误
- `object_scope = 'EXACT_OBJECT'` 是当前唯一的数据库级 scope gate

### 4.2 路由缺口：actor/role/purpose/effect 与 actor-owned scope 判定

- **现状**：数据库仅保证 `object_scope='EXACT_OBJECT'`，未实现 actor/role/purpose/effect 与未确认 actor-owned scope 的运行时授权判定
- **路由**：`ROUTED_TO_HDM007_008` — actor/role/purpose/effect、actor-owned scope 的威胁分析归 HDM-007，正式四道门与 capability 消费判定归 HDM-008
- **围栏**：当前 capability seam 保持 **fail-closed**，不存在生产 allow 实现

---

## 5. 全量构建与验证（保留原始结果，未重跑）

| 阶段 | 结果 | 详情 |
| --- | --- | --- |
| Maven offline clean verify | BUILD SUCCESS | 11 modules, 63 Java tests |
| Architecture tests | 13/13 | ArchitectureTest 7 + DatabaseBoundaryTest 6 |
| Slice B (database-adapter) | 35/35 | DatabaseSliceBContractTest |
| Slice C (coordinator) | 15/15 | SliceCCoordinatorTest |
| Node typecheck | PASS | 4 workspaces |
| Node lint | PASS | 0 errors, 2 warnings (generated) |
| Node test | PASS | 13 tests (codex-adapter 2, nest-console 1, ui-contract-fixtures 10) |
| API smoke | PASS | Started in 0.64s, JDK 25.0.3 |
| Worker smoke | PASS | Started in 0.66s, JDK 25.0.3 |

---

## 6. 安全扫描（保留原始结果）

| 扫描项 | 结果 |
| --- | --- |
| diff whitespace | 0 errors (1 LF/CRLF warning on jOOQ generated file) |
| 正文泄漏 | 0（所有测试使用 SYNTHETIC/DATABASE_TEST/CANARY 标记） |
| 硬编码 secret/token/credential | 0（仅 env 变量引用 `${env.HDM005_DB_PASSWORD}`） |
| 越界路径 | 0 |

---

## 7. 残留检查（保留原始结果）

| 检查项 | 结果 |
| --- | --- |
| Java 进程残留 | 0 |
| Node 进程残留 | 0 |
| Docker 容器残留 | 0 |
| Docker volumes/networks | 0 |

---

## 8. 最终状态

```text
纠偏状态：HDM005_SLICE_D_ROUTED_GAPS_SEALED_READY_FOR_LOCAL_COMMIT
已通过：架构负向／GOVERNED绑定／合成CLOSEOUT_RUN／数据库EXACT_OBJECT
OPERATIONAL identity：ROUTED_TO_HDM006_PRODUCTION_DISABLED
actor-role-purpose-effect：ROUTED_TO_HDM007_008_FAIL_CLOSED
虚假全通过残留：0
工作区：trackedModified=0、untrackedNew=2、staged=0
trackedTotal：297（仅仓库总数）
Markdown／Evidence一致性：PASS
源码修改／测试运行／Git写操作：否／否／否
报告路径：D:\myproject\hide-nest\reports\HDM-005-SliceD-执行报告.md
```
