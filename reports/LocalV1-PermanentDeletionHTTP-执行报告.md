# Local V1 永久删除真实 HTTP 后端纵切 — 执行报告（含 Task33A-R1 返工）

## 结论

```text
返工状态：LOCAL_V1_PERMANENT_DELETION_HTTP_R1_READY_FOR_FINAL_CLEAN_GATE
V016 空库／V015升级／重复：16/1/0
closeout→preview→confirm→run：PASS
正式 Memory／Evidence／payload 擦除：PASS/PASS/PASS
capture scope／closeout run 保留：PASS/PASS
capture scope unit 本闭包清除／他组不变：PASS/MATCH
无marker／跨闭包／错误disposition：REJECTED/REJECTED/REJECTED
注入失败全事务回滚：PASS
Task33A HTTP 原回归／新增真实链：9/9／PASS
Maven offline verify／最终 clean-repackage：PASS/PENDING_USER_STOP
V001—V015／database generated／contracts／React-prototype：MATCH/MATCH/MATCH/MATCH
operation／failure code／event type／RunPhase：30/50/12/5
正文-path-token-capability-secret泄漏：0-0-0-0-0
真实 Git index：未变
工作区：tracked=22、untracked files=17、staged=0、越界=0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionHTTP-执行报告.md
```

## 一、返工背景与唯一阻塞

Task33A 首轮把永久删除核心接成真实 HTTP 闭环，但其 9 个删除 HTTP 测试均使用 `LocalV1S1WindowCloseCoordinator` 直接构造 Memory（S1-built fixtures）。它们证明了删除 HTTP 对 S1 事实有效，却没有证明对正式 `POST /v1/closeout-submissions` 产生的 Memory 有效。

真实 closeout 会额外留下：

```text
runtime.capture_scope
runtime.capture_scope_unit → evidence.source_unit（ON DELETE NO ACTION）
runtime.closeout_run
```

V014 的数据库擦除函数直接删除闭包内 `evidence.source_unit`，却未先处理 `runtime.capture_scope_unit`，因此正式入口保存的记忆会在数据库擦除阶段被 FK 阻断。

本轮只关闭这一处阻塞，不回滚、不重做已通过的 HTTP/契约实现，不扩大到前端。

## 二、冻结修法：V016

新增且只新增一个版本化迁移：

`modules/database-adapter/src/main/resources/db/migration/V016__closeout_capture_scope_deletion_bridge.sql`

禁止修改 V001—V015。V016 只 `CREATE OR REPLACE FUNCTION`，不改变任何表结构：

1. `runtime.enforce_capture_scope_unit_frozen()`：
   - 普通 INSERT/UPDATE/DELETE 的既有冻结规则不变；
   - 仅当存在正式 `runtime.deletion_erasure_marker`，且 `OLD.source_unit_id` 是该 marker 对应闭包的 `SOURCE_UNIT + DELETE_CANDIDATE` 精确成员时，允许 DELETE；
   - UPDATE 永不放开；跨闭包、仅同 source、仅同 scope、无 marker 均无法绕过；
   - `deletion_erasure_marker` 仍不向 api/worker 授权（沿用 V014）。
2. `runtime.execute_confirmed_deletion_database_phase(...)`：
   - 保留 V014 全部检查、锁、共享引用拒绝、擦除顺序、返回值与内部错误标识；
   - 在删除 `evidence.source_unit` 之前，精确删除引用本闭包 `SOURCE_UNIT + DELETE_CANDIDATE` 的 `runtime.capture_scope_unit` 行；
   - 不删除 `runtime.capture_scope`、不删除 `runtime.closeout_run`（作为无正文审计摘要保留）；
   - 不使用 `ON DELETE CASCADE`，不放宽 `capture_scope_unit_source_unit_fk`；
   - 任一步失败整事务回滚，不留下「scope unit 已删但 Memory 未删」的半提交。

## 三、新增测试

### 1. closeout→delete 真实链（apps/api，真实 HTTP）

在 `LocalV1DeletionHttpIntegrationTest` 新增 `closeoutBuiltMemoryDeletionEndToEnd`，走真实 HTTP 且不调用 S1 helper 冒充 closeout：

```text
POST /v1/closeout-submissions（202 + CANONICAL_COMMITTED）
→ 读取真实 memoryId 与规范 facts
→ POST /v1/deletion-previews
→ POST /v1/deletion-previews/{id}/confirm
→ GET /v1/deletion-runs/{runId}
```

逐项断言：closeout 202 且 Memory 可被 S2B detail 读取；preview 为同一 Memory 且 confirm 返回同一真实 run；删除后 memory_record/memory_revision/闭包内 evidence unit/payload/anchor 消失、payload 文件 NOT_FOUND、S2B list 不含目标且 detail/evidence 精确不可读；原 closeout 的 capture_scope 与 closeout_run 字段未被改写；本 scope 的 capture_scope_unit 删除前精确 > 0、删除后精确 = 0，其他 scope 集合前后 MATCH；deletion closure/fence/decision/run 保留；响应/日志无正文、objectRef、绝对路径、token、capability、secret、SQL、堆栈。

### 2. V016 数据库反证（modules/database-adapter）

在 `LocalV1V016CloseoutBridgeTest` 新增 5 条：

- `migrationCounts`：V016 空库 16、V015→V016 升级 1、重复 0。
- `noMarkerDeleteRejected`：无 marker，DELETE frozen capture_scope_unit → SQLSTATE 23514 `HDM006_CAPTURE_SCOPE_UNIT_FROZEN`。
- `crossClosureDeleteRejected`：A 闭包 marker 删 B scope unit → REJECTED。
- `wrongDispositionDeleteRejected`：marker + member target_id 匹配但 member_kind 非 SOURCE_UNIT（SOURCE_PAYLOAD）→ REJECTED。
- `injectedFailureRollsBackEverything`：scope-unit 删除后注入 source_unit DELETE 失败，证明 capture_scope_unit/Memory/Evidence/payload task/run 全部回滚。

## 四、机械后果

- 迁移数 15 → 16；空库 16、V015→V016 升级 1、重复 0。
- 由正式测试直接断言的迁移计数全部机械更新为 16（及相应升级计数），未批量改历史报告。
- V016 只替换函数/触发器逻辑，不改变表结构；database jOOQ generated tree 为 **MATCH**，未重新生成。
- OpenAPI、inventory、生成 Java/TS、Task33A HTTP 生产实现、React/原型均未改动。
- operation/failure code/event type/RunPhase 保持 30/50/12/5。

## 五、验证

依次执行并全部通过：

1. V016 空库 16／V015 升级 1／重复 0；
2. 新增 closeout→delete HTTP 测试（真实 HTTP）与 4 个数据库反证；
3. Task33A 原 9 个 HTTP 测试回归（9/9，S1-built fixtures）；
4. `mvnw -o verify -Dspring-boot.repackage.skip=true` 一次：**BUILD SUCCESS**。

**关于全仓门的诚实标注**：当前 PID 49968 正占用旧 `apps/api/target/*-exec.jar`（`java -jar hide-nest-api-exec.jar`），本轮不杀该进程。`mvnw -o clean verify` 的 `clean` 阶段会因文件锁（`Device or resource busy`）中断，故全仓门记录为：

```text
VERIFY_PASS
FINAL_CLEAN_REPACKAGE_PENDING_USER_STOP
```

即 `mvnw -o verify -Dspring-boot.repackage.skip=true` 已通过（VERIFY_PASS）；真正的 `clean verify`（含 repackage）待 hide 验收后由小林关闭旧服务器再执行末端 clean gate，本轮**不**将其写成 `clean verify PASS`。

## 六、边界与不变

- V001—V015：MATCH（V016 为新增，未改既有）。
- database jOOQ generated tree：MATCH（未重新生成）。
- contracts（OpenAPI/inventory/生成 Java+TS）：MATCH（R1 未改动）。
- React／原型／docs/frontend／nest-console：MATCH（未触碰）。
- 冻结 `CanonicalFailureCode` 集合：MATCH（未新增）。
- 现有 closeout 协议与只读路径：回归测试全绿。
- 未 stage、未 commit、未 push；未修改用户 Git 配置。

## 七、工作区精确统计

用 `git status --porcelain` 精确统计：

- tracked（modified ` M`）= 22
- untracked files（`??`）= 17，其中：
  - 本次新增/修改文件 = 16
  - 既有 QA 临时目录（**非本次产生，保持不变**）= 1：`reports/local-v1-read-browser-qa/.playwright-artifacts/`
- staged = 0
- 越界（允许路径之外）= 0
