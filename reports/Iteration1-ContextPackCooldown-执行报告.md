# Iteration1｜ContextPack 同房间 24 小时冷却 执行报告

## 实施状态（R1 封口后）

返工状态：ITERATION1_CONTEXT_PACK_24H_COOLDOWN_R1_READY_FOR_LOCAL_COMMIT
（原 ITERATION1_CONTEXT_PACK_24H_COOLDOWN_READY_FOR_HIDE_REVIEW 经 R1 并发时钟封口后更新为上述返工状态。）

## 1. 目标与范围

为正式 ContextPack 增加**仅一条**冷却规则：同一 `threadKey`（后端 `threadId`）内，同一 `memoryId` 若在本次检索时刻之前不足 24 小时曾成功交付，本次必须过滤；其他 thread 不受影响；恰好满 24 小时恢复资格。冷却只影响新检索，不修改、删除或降权任何记忆，不新增调用参数。

- 不做混合检索、Gateway、回合冷却、自然日算法、历史导入、下一需求。
- 基线：HEAD / origin/main = `9dbfdeb290375b7cb0e90de493b23eb88e4d8a47`，branch=main，staged=0。
- 仅允许改动范围见 §8；V001–V021、OpenAPI/baseline/generated、React/prototype、Console、Embedding、CandidateSet 业务、MemoryEvidence、删除业务、部署文件均冻结未改。

## 2. 实现

### 2.1 冷却集合只读查询
在 runtime 只读端口新增窄查询 `findDeliveredMemoryIdsSince(threadId, cutoff, now)`，jOOQ 实现为对
`runtime.context_delivery ⋈ runtime.context_pack_delivery_item ⋈ memory.memory_revision` 的只读 JOIN，
`SELECT DISTINCT memory_id`，仅 `purpose=CONTEXT_PACK`，过滤 `cutoff < delivered_at <= now`，稳定排序。
被物理删除、无法 JOIN 到 revision 的旧审计项自然忽略、不报错；null 参数在发 SQL 前拒绝。
**未新增任何表、列、索引、函数、权限或迁移（无 V022）。**

### 2.2 同 thread 并发门
在事务端口新增独立命名空间的事务级 advisory lock `lockContextPackThread(threadId)`：
`pg_advisory_xact_lock(73951, hashtext(threadId))`，与现有单 key idempotency lock 分属不同哈希空间。

### 2.3 协调器重构
`POLICY_VERSION` `v2`→`v3`，新增 `COOLDOWN_HOURS=24`。`createNew` 分为两阶段：
- **Phase A（事务外）**：读取初始冷却集合以扩大候选窗口 `candidateLimit = min(100, max(5, maxResults + cooled.size))`，随后 Embedding + 向量检索。
- **Phase B（单事务原子提交）**：idempotency lock → receipt 复核 → thread lock → 二次读取冷却集合 → 顺序过滤（score 门槛 → 冷却 → S2B 可见性 → cap maxResults）→ 审计（trace/delivery/item/receipt）原子提交。
- request hash 与 delivery manifest 均绑定 `v3` 与精确 `cooldownHours=24`；动态 now/cutoff/cooledIds 不入 hash；旧 v2 receipt 因 hash 不匹配自然 REJECTED。
- Embedding 始终在事务外执行，不跨 HTTP Embedding 持有数据库事务。

### 2.4 MCP
仅修改 ContextPack 工具 description 与对应测试；input schema、工具数（仍恰好 3）、其他工具不变。冷却说明已并入 description：同 threadKey 24h 冷却、其他 threadKey 不受影响、必须稳定复用 threadKey 禁止换 key 绕过、冷却后可少于 maxResults 或为空、不得降 minScore 凑数、网络重试复用 retrievalKey/turnKey 得 EXACT replay。Task43 默认/一次放宽/query 说明逐字保留。

### 2.5 关于候选窗口上限的说明（偏离记录）
工单 §4.2 公式上界为 100，但向量协调器 `LocalV1VectorCoordinator`（冻结、越界不可改）`searchSimilar` 上限为 20；因此在调用前将 candidateLimit 夹紧到 20，避免超上限抛 `INVALID_ARGUMENT`。冷却窗口实际受向量层 20 上限约束，与工单“candidateLimit 达上限后允许少于 maxResults”相容。

## 3. 必测矩阵结果

### 3.1 时间与 thread
- 同 thread、同 memory，首次返回：PASS
- `23:59:59.999999` 后新 key 查询 → 过滤：FILTERED
- 恰好 `24:00:00.000000` → 恢复：PASS
- 不同 thread 立即查询 → 正常返回：PASS
- 同 thread 另一 memory 未冷却 → 可返回：PASS
- 其他 purpose 的 delivery → 不产生冷却：PASS
- 空 delivery → 不产生 memory 冷却：PASS
- 被物理删除/不存在的旧 delivery item → 查询不报错、不影响其他候选：PASS

### 3.2 排序、门槛与副作用
- 第一名冷却，第二/第三名均达门槛 → 按原 score 顺序返回：PASS
- 第一名冷却，后续低于门槛 → 不补位：PASS
- 所有达标项冷却 → HTTP 200 / `NO_RELEVANT_RESULT` / memories=[] / policyRevisionSet=[]：PASS
- 空结果审计 trace/delivery/item/receipt = `1/1/0/1`：PASS
- 冷却项的 S2B detail/evidence 调用均为 0：PASS（0-0）
- 默认 `3/0.6` 与显式 `5/0.4` 既有行为不回退：PASS

### 3.3 幂等、并发与攻击
- 同 key 同值 replay：EXACT，Embedding 与审计增量 0（即使该 memory 现已冷却）：PASS
- 同 key 参数异值：REJECTED，事实增量 0：PASS
- 两线程、独立连接、不同 key、同 thread 同时起跑：同一 memory 最多出现在一个新 delivery（0 次重复交付）：PASS
- 两线程、不同 thread：均可交付同一 memory：PASS
- v2 receipt 冒充 v3 replay：REJECTED：PASS
- cutoff 边界、未来 deliveredAt、重复 item、跨 thread 混入不得静默扩大或绕过冷却：PASS
- 并发失败不得产生半套 trace/delivery/item/receipt：PASS

### 3.4 回归
- Task43 ContextPack application/API/contracts targeted：全 PASS
- CandidateSet、MemoryEvidence MCP 回归：PASS（codex adapter 284 passed / 1 skipped）
- 永久删除、V001–V021、React/prototype 不变：MATCH

## 4. 质量门

| 门 | 结果 |
|---|---|
| 新冷却 targeted（真实 PostgreSQL/pgvector） | PASS（53 例） |
| ContextPack 既有 application + API targeted | PASS（api 18、contracts 8） |
| Codex Adapter 四门（typecheck/lint/test/build） | PASS / PASS / PASS / PASS |
| 根 Node 四门（typecheck/lint/test/build） | PASS / PASS / PASS / PASS |
| JDK25 `mvnw -o clean verify` 最终门 | PASS |
| architecture-tests | PASS（29 例） |
| `git diff --check` | PASS |
| 路径/泄漏/Docker 与 Git index 机械门 | PASS |

OpenAPI generate/check、契约兼容、jOOQ generate：`NOT_RUN_UNCHANGED`（对应路径修改为 0，`clean verify` 再生后 generated 与 HEAD 一致）。

## 5. 完成回执（R1 封口后更新）

```
返工状态：ITERATION1_CONTEXT_PACK_24H_COOLDOWN_R1_READY_FOR_LOCAL_COMMIT
PhaseA旧上界并发反证／提交时钟复核：PASS/PASS
同thread交错并发重复交付／半提交：0/0
长Embedding issuedAt-deliveredAt-expiresAt：PASS
检索期间跨24h边界／恢复资格：PASS/PASS
既有时间-thread-排序-门槛-replay回归：PASS
Task44A targeted／必要离线编译／diff-whitespace：PASS/PASS/PASS
本轮Node-Maven-架构门：NOT_RUN_R1_REUSE_TASK44A_PASS
生产业务修改／测试修改／报告修改：1/1/2
V001—V021／OpenAPI-generated／React-prototype／MCP：MATCH/MATCH/MATCH/MATCH
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\Iteration1-ContextPackCooldown-执行报告.md
```

原始 Task44A 回执（已被 R1 封口更新取代，保留存档）：

```
实施状态：ITERATION1_CONTEXT_PACK_24H_COOLDOWN_READY_FOR_HIDE_REVIEW
同thread首次／23h59m59.999999／24h：PASS/FILTERED/PASS
跨thread／其他purpose／空delivery：PASS/PASS/PASS
冷却后替补排序／低分不补／全冷却空集：PASS/PASS/PASS
冷却项S2B detail-evidence调用：0-0
同值replay／Embedding-审计增量：EXACT/0-0
同thread并发重复交付／跨thread并发：0/PASS
v2 receipt冒充v3／攻击矩阵：REJECTED/PASS
ContextPack targeted／Maven offline／架构门：PASS/PASS/PASS
Codex Adapter四门／Node四门：PASS/PASS
V001—V021／OpenAPI-generated／React-prototype／CandidateSet-MemoryEvidence-Deletion：MATCH/MATCH/MATCH/MATCH
正文-query-完整向量-path-token-capability-secret泄漏：0-0-0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
Docker新增containers-volumes-networks／遗留进程：0-0-0/0
联网／Git写操作：否／否
家庭部署：NOT_STARTED
报告路径：D:\myproject\hide-nest\reports\Iteration1-ContextPackCooldown-执行报告.md
```

## 7. R1｜提交时钟与并发冷却封口

### 7.1 缺口
hide 复核发现真实并发缺口：`createNew()` 在 Embedding 前读取一次 `now/cutoff`，Phase B 获得 thread lock 后仍用该旧上界复核冷却，并把旧 `now` 写成 `issuedAt`。反例：A Phase A 时间 T0、B Phase A 时间 T1（T1>T0），B 先取锁提交 `deliveredAt=T1`，A 后取锁仍查询 `deliveredAt<=T0`，看不见 B 刚提交的 delivery，导致同 thread 同 memory 重复交付。原并发测试用同一个 fixed Clock，两请求时间完全相同，无法证明该反例。

### 7.2 修法
Phase A 的时间只用于候选窗口预估（`initialNow`/`initialCutoff`）。Phase B 在锁序之后重新取权威时间：
`idempotency lock → receipt 复核 → thread lock → commitNow = clock（微秒截断）→ commitCutoff = commitNow - 24h → 查询 (commitCutoff, commitNow] 冷却集合 → 最终过滤与原子提交`。
`RetrievalTrace.createdAt`、`ContextDelivery.deliveredAt`、响应 `issuedAt` 一律使用 `commitNow`，`expiresAt = commitNow + 10min`；不再使用 Phase A 的 initialNow 作为交付时间。Embedding 仍在事务外；request hash/manifest 的 v3+24 绑定不变；replay 不重算冷却仍 EXACT。

### 7.3 反证测试（能杀死旧实现）
新增可控 `ManualClock`（记录每次读取以证明各阶段时间顺序）与三项反证测试，并已临时改回旧实现验证其全部失败、修复后通过：

1. `concurrentSameThreadInterleavedClocksDeliverMemoryExactlyOnce`：两线程、不同 key、同 thread、同 memory；A Phase A=T0 阻塞于 Embedding，B 于 T1 先提交，A Phase B 重读 T1 后必见 B 的 delivery 为冷却；断言该 memory 在 delivery item 中精确 1 次（旧实现为 2，证明重复交付）。并断言 `clockA.reads()=[T0,T1]`、`clockB.reads()=[T1,T1]` 证明各阶段时间顺序。
2. `longEmbeddingAdvancesCommitClockForIssuedAndExpiry`：Phase A=T0、Phase B=T0+5min，断言 `issuedAt/deliveredAt=T0+5min`、`expiresAt=T0+15min`（旧实现 issuedAt 为 T0，损失 5 分钟有效期）。
3. `boundaryCrossedDuringRetrievalRecoversByPhaseBTime`：Phase A 尚不足 24h、Phase B 获锁时恰满 24h，以 Phase B 为准恢复资格（旧实现以旧上界视为冷却而不恢复）。
4. 并发两调用均正常返回、后提交者得空集合、无半提交：由测试 1 同时覆盖（A 返回空集、计数精确）。

### 7.4 范围与验证
R1 仅修改：`LocalV1ContextPackCoordinator.java`（生产 1）、`LocalV1ContextPackRetrievalTest.java`（测试 1）、报告与 Evidence（报告 2）。runtime ports/adapters、MCP、OpenAPI、migration、generated、API、React、CandidateSet、MemoryEvidence、删除、Embedding、部署文件均未在本轮修改。既有 fixed-clock 23:59:59.999999/24h、跨 thread、replay EXACT、v2→v3、低分不补与空审计回归继续 PASS。Task44A targeted 全 PASS（Retrieval 38、RelevanceGate 16、Empty 2，共 56）；必要模块离线编译 PASS；`git diff --check` PASS。本轮不重复 Node 四门、全仓 Maven、架构门，沿用 Task44A 已完成证据（`NOT_RUN_R1_REUSE_TASK44A_PASS`）。

## 6. 说明

- 本任务为保持编译，在 `OutboxWorkerCoordinatorTest.FakeTxPort` 中为新增的 `lockContextPackThread` 补了空实现桩（机械性、无行为变更），这是接口新增方法所必需的编译修复，不计入业务改动。
- 遗留的 `hide-nest-*` Docker 容器均为数日前既有任务产物（已 Exited），本任务未新增任何 container/volume/network；Testcontainers 测试容器已自动清理。
- 不 stage/commit/push，不部署，不开始混合检索或下一需求。
