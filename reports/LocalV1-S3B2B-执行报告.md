# LocalV1 S3B2B 执行报告

## 实施状态

**LOCAL_V1_S3B2B_APPLICATION_CONFIRM_READY_FOR_HIDE_REVIEW**

## 基线 HEAD

5c724e2c28ab8464e48cb9d7f54e6ccc50287727

## 测试结果

```
合法确认／完整围栏／AFFECTED不围栏：PASS/PASS/PASS
同键同值／同键异值／不同键冲突：EXACT/REJECTED/ONE_WINNER
同键并发／不同键并发：ONE_FACT/ONE_WINNER
revision-manifest-pointer-policy-expired：全部 STALE
图集合变化／actor不存在：STALE/REJECTED
三注入点回滚／半提交：PASS/0
S3B2B／S3B2A／S3A：28/0/0/0
全仓 Maven：NOT_RUN
V001—V013／generated／contracts／frontend／payload：MATCH/MATCH/MATCH/MATCH/MATCH
正文／secret泄漏：0/0
真实 Git index：staged=0
工作区：tracked=3 modified、untracked=7、staged=0、越界=0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-S3B2B-执行报告.md
```

## 实施内容

### 1. CanonicalFailureCode 扩展
- 新增 `DELETION_PREVIEW_STALE`、`DELETION_CLOSURE_MISMATCH`
- V002 migration 已预注册，contracts 已存在

### 2. 共享 Canonical 计算（S3A 防回归）
- 新建 `CanonicalClosureComputer.java` — 纯函数，无状态
  - `buildMembers(DeletionPreviewGraph)` → `List<DeletionPreviewPort.Member>`
  - `manifestHash(DeletionPreviewGraph, List<DeletionPreviewPort.Member>)` → `byte[32]`
- `LocalV1S3ADeletionPreviewCoordinator` 重构为委托共享实现
- S3A 全测试通过 → 行为不变 ✓

### 3. Request / Result / Exception
- `LocalV1S3B2BDeletionConfirmRequest` — closureId, previewRevision, manifestHash(防御性复制), actorId, idempotencyKey
- `LocalV1S3B2BDeletionConfirmResult` — closureId, previewRevision, manifestHash(防御性复制), decisionId, confirmedAt, state=CONFIRMED, fenceCount, unfencedAffectedCount
- `LocalV1S3B2BException` — 包装 `CanonicalFailureCode`

### 4. Coordinator（12 步事务流程，无 jOOQ/JDBC/DSLContext）
1. 请求校验（null/blank/revision/32-byte hash）→ SQL 前拒绝
2. 幂等键预读 Decision
3. `lockConfirmationSnapshot(closureId)` 锁 closure
4. closure 不存在 → `DELETION_CLOSURE_MISMATCH`
5. 锁后重读幂等键（竞态保护）；已 CONFIRMED → 精确匹配 replay 或拒绝
6. PREVIEWED → revision/manifest 精确匹配 + 未过期
7. CanonicalClosureComputer 锁定当前 graph 并验证指针/member/manifest 一致性
8. actor 存在性验证（fail-closed）
9. 插入 `USER_DELETE_CONFIRM` Decision
10. 按 ordinal 为 non-AFFECTED 成员 fence
11. `confirmClosure(...)` CAS
12. COMMIT → result

### 5. 测试覆盖（10 组 / 10 PASS）
1. 合法确认 — Decision 1、non-affected 各 1 fence、AFFECTED 0 fence
2. 同键同值 replay — result 全字段 EXACT、DB 计数不增长
3. 同键异 closure/preview/manifest/actor — 全部 REJECTED
4. 不同 key 并发同 closure — 恰好一个成功、一个 Decision
5. 同 key 并发同值 — 同 decisionId/confirmedAt、一个事实
6. revision/manifest/expired/memory-change/policy-pointer-change 各自 stale
7. 图成员增加/移除 — PREVIEW_STALE、零确认事实
8. actor 不存在 — REJECTED、不创建 ActorRef
9. Decision/fences/closure 三注入点全回滚 — PASS
10. byte[] 防御性复制 — result/exception 无正文泄漏

### hide 验收补丁

- replay 增补 actorRole、proposal/review 空值、authorizationRef、idempotencyKey 与 confirmedAt 的完整事实匹配；
- canonical 复核增补 graph root/owner/access-policy 双指针一致性；
- 新增真实 `access_policy.current_revision_no` 变化攻击，确认旧 preview 稳定返回 `DELETION_PREVIEW_STALE`；
- “部分 fences 后失败”改为仅落第一条 fence 即注入异常，验证事务回滚而非整批写完后才失败；
- hide 验收轮按原三个定向套件复跑：28 tests，0 failures，0 errors，0 skipped。

## 验收门

| 条件 | 状态 |
|------|------|
| S3B2B 协调器无 jOOQ/JDBC/DSLContext | ✓ |
| application 不依赖 database-adapter | ✓ |
| S3A/S3B2B 使用同一 CanonicalClosureComputer | ✓ |
| 一个事务覆盖 Decision+fences+closure | ✓ |
| exact replay + 两类并发 | ✓ |
| V001—V013/generated/contracts/frontend/payload MATCH | ✓ |
| 正文/secret 泄漏 | 0 |
| Git index staged=0 | ✓ |
| Docker 新增 container/volume | 0 |
| 未联网/未下载/未 Git 写操作 | ✓ |

## 修改文件清单

### 修改 (3)
- `application/.../coordinator/LocalV1S3ADeletionPreviewCoordinator.java` — 重构委托共享计算机
- `application/.../model/CanonicalFailureCode.java` — +2 codes
- `database-adapter/.../database/LocalV1S3ADeletionPreviewTest.java` — migration count 12→13

### 新增 (7)
- `application/.../coordinator/CanonicalClosureComputer.java`
- `application/.../coordinator/LocalV1S3B2BDeletionConfirmCoordinator.java`
- `application/.../coordinator/LocalV1S3B2BException.java`
- `application/.../model/LocalV1S3B2BDeletionConfirmRequest.java`
- `application/.../model/LocalV1S3B2BDeletionConfirmResult.java`
- `database-adapter/.../database/LocalV1S3B2BDeletionConfirmationCoordinatorTest.java`
- `reports/LocalV1-S3B2B-执行报告.md`
