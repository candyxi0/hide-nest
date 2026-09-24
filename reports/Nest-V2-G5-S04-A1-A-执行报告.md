# Nest V2｜G5-S04-A1-A 投递账本与领取围栏执行报告

## 指挥官独立复核（2026-09-24）

**结论：**`IMPLEMENTATION_ACCEPTED_WITH_INHERITED_FORMAT_EXCEPTION`。以下施工房间的 `BLOCKED_QUALITY_GATE` 原始回执保留，不改写为施工时全绿。指挥官只读审查 V007、投递状态机、四处旧测试库存数字与 11 条任务路径后，在本机独立重跑 `mvnw.cmd -pl modules/database-adapter-v2 -am test -o`：V2 PostgreSQL 71／71、application 61／61，退出码 0；`mvnw.cmd '-DskipTests' package -o` 全仓 14 模块退出码 0；完整 adapter-v2 Spotless 退出码 0；新增两个 runtime 文件的定向 Spotless 退出码 0。

指挥官也独立复现 `mvnw.cmd -pl modules/runtime spotless:check -o` 退出码 1：23 个违规文件均未在本单 Git taskPathSet 中，且早期仓库报告已经记录 runtime 等模块的既有格式差异。这是本任务之前就存在的模块格式债务，本次只对**未改旧文件造成的完整模块 Spotless 失败**做验收例外；并未宣称该门通过，也未修改或放宽任一格式规则。新文件仍需通过定向格式检查。本例外不自动适用于以后的任务，未来若决定清理旧格式，须独立限界。

本结论仅覆盖 A1-A 逐事件投递账本的代码与数据库机械反证。A0 JSON 运行时校验、正式材料、真实 Worker、索引与连续性体验仍为 `NOT_RUN`。指挥官据此进入精确本地入库门；提交结果另行记录。

## 状态

`BLOCKED_QUALITY_GATE`。A1-A 的代码与 PostgreSQL 反证已完成，**不回执** `G5_S04_A1_A_READY_FOR_COMMANDER_REVIEW`：`modules/runtime` 的完整 `spotless:check` 因 23 个既有、未改动的生产文件格式差异退出 1。新加的 runtime 文件定向 Spotless 通过。修复完整模块格式门需要修改原工单明确禁止的既有 runtime 生产文件；本轮停止在此，不扩大路径、不开始 A1-B。

## 冻结基线与范围

- 仓库 `D:\myproject\hide-nest`，分支 `main`，冻结 HEAD `879aff008e7aa87cdd7608ef114e6f068709d072`，index tree 始终为 `6b8af43662076de1b66812dba627608a9fe072fa`，staged=0。
- 原有用户工作树：`modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1BubbleCoreDatabaseTest.java`、`docs/planning/**`、`reports/local-v1-read-browser-qa/**` 均原样保留。
- 补充授权中的四个旧 V2 测试仅机械改了 Flyway／runtime 表库存精确数值：全量 6→7、V001→最新 5→6、V005→最新 1→2、runtime 表 6→9。未修改旧测试业务断言。
- 本轮真实 taskPathSet（相对仓库，含报告）：
  1. `modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JooqProjectionLedgerAdapter.java`
  2. `modules/database-adapter-v2/src/main/resources/db/v2/migration/V007__projection_delivery_ledger.sql`
  3. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
  4. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2DependencyReadTest.java`
  5. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationControlTest.java`
  6. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationIntakeTest.java`
  7. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2ProjectionLedgerTest.java`
  8. `modules/runtime/src/main/java/io/github/candyxi0/hidenest/runtime/domain/ProjectionLedger.java`
  9. `modules/runtime/src/main/java/io/github/candyxi0/hidenest/runtime/port/ProjectionLedgerPort.java`
  10. `reports/Nest-V2-G5-S04-A1-A-Evidence.json`
  11. `reports/Nest-V2-G5-S04-A1-A-执行报告.md`

## 实施事实

- V007 仅新增 `runtime.projection_target`、`runtime.projection_delivery`、`runtime.projection_attempt` 三张表及约束、索引和精确 grants；没有 seed target、active generation、真实模型或 embedding。V001—V006 未改。
- 目标绑定 world、projection generation 与两种 manifest；投递身份为 `(world_ref, projection_generation, event_id)`，每次领取保留独立 attempt generation、owner、租约、task binding 和历史。
- `discover` 每次针对已提交 Outbox 做 anti-join；`created_at,event_id` 仅调度排序，没有 watermark。未到期的 `RETRY_WAIT` 和有效租约不会被反复列为可领事件。
- 领取、续租、过期接管、结算、失败和围栏恢复均为短事务；同事件行锁和唯一约束收敛。事务内没有假接收者、Worker 或网络调用。APPLIED 只记录机械回执，不改变 Memory、Evidence、Outbox、Formation 或来源 processed。
- 测试使用明确的 synthetic manifest、material digest 与 result digest；A0 JSON runtime validation 和正式材料摘要不在本轮运行。

## 验证结果

| 门 | 实际结果 |
| --- | --- |
| JDK | Temurin 25.0.4 |
| V001—V007 空库；V006→V007；repeat | PostgreSQL 18.4 Testcontainers，7／1／0；旧 migration checksum 不变 |
| A1-A 定向测试 | `NestV2ProjectionLedgerTest`：10 项通过，exit 0 |
| 完整 V2 PostgreSQL 套件 | 71 项通过（既有 61＋新增 10），0 failures/errors/skipped，exit 0 |
| 关键反证 | 未提交事件不可见；A 早开始未提交、B 晚开始先提交并成功，A 后提交仍成功；同事件并发单租约、异事件并行；过期旧 attempt 与错 owner／task／world／event／generation／manifest／digest／revision 均拒绝；同值回执重放 `xmin` 不变；单项失败、有限重试、attention、恢复及权限均通过 |
| 故障注入 | claim、renew、settle、fail 的触发器异常均回滚；规范 Record／revision、Outbox、Formation 与来源 processed 未受影响 |
| 数据库权限 | Worker 与仅有 PUBLIC 权限的测试角色对三张表的 SELECT／INSERT／UPDATE／DELETE 均被真实拒绝；API 无 DELETE、TRUNCATE、DDL |
| adapter-v2 Spotless | 完整模块 `spotless:check` exit 0 |
| runtime Spotless | 新增两个文件定向 `spotless:check` exit 0；完整模块 exit 1，23 个未改动旧文件格式差异，越界停止门 |
| 全仓构建 | `.\mvnw.cmd '-DskipTests' package -o` exit 0 |
| Git | `git diff --check` exit 0；staged=0；本轮路径越界=0 |
| Evidence | Node `JSON.parse`、PowerShell `ConvertFrom-Json`、Python 严格重复 key 检查均 exit 0；真实 Git taskPathSet 与 Evidence 双向一致，共 11 路径 |
| Docker | 仅用本机已有 pgvector/PostgreSQL 18 镜像，未 pull；测试后无新增容器／volume／network；本轮启动的 Docker Desktop 已停止，服务回到 Stopped，无 Docker 进程遗留 |

## 首次失败与自修

| 首次失败命令／exit | 原因与处理 | 最终结果 |
| --- | --- | --- |
| 未给 PowerShell 中的 `-Dsurefire.failIfNoSpecifiedTests=false` 加引号的定向 Maven 命令／1 | shell 将参数拆开；改为引号包裹 Maven `-D` 参数 | 定向测试 exit 0 |
| 首次定向测试／1 | 新测试把 Flyway 版本 `007` 误写为 `7`，旧 checksum 查询多取了 V007；改为 `007` | 定向测试 exit 0 |
| 第二次定向测试／1 | 新测试把 PUBLIC 当作可传入 `has_table_privilege` 的角色；改用 ACL 展开并追加仅继承 PUBLIC 的真实角色 SQL 拒绝测试 | 定向测试 exit 0 |
| 首次 adapter-v2 完整 Spotless／1 | 补充授权四个旧测试的 9 个被修改行出现局部 LF，旧文件其余行为 CRLF；仅把这 9 行恢复为旧文件行尾 | adapter-v2 完整 Spotless exit 0；Git diff 仅数值 |
| 首次 runtime 完整 Spotless／1 | 既有 23 个未改动生产文件格式差异（输出显示 4 个文件及另 19 个）；修复需越过允许路径 | **未修复；保持 BLOCKED** |
| 首次 `spotlessFiles` glob／1、带括号正则／255 | 插件期望正则，Windows cmd 又解析括号；改用无括号正则精确选中新文件 | 新文件定向 Spotless exit 0 |
| 首次严格重复 key 检查／1 | 本机没有 `python` 命令；改用现有 Windows `py -3` 启动器，同一检查代码未变 | 严格检查 exit 0 |

## 未运行边界

`NOT_RUN`：A0 JSON runtime validation、正式 `projectionMaterial` 组装、真实 Worker／Python、模型、索引写入、Embedding、Retrieval、家庭部署、连续性体验。假回执的通过不表示 S04-A1 或 S04 完成。没有 stage、commit、push 或部署。

## 停止门

完整 runtime Spotless 要通过，需改变原工单禁止修改的既有 runtime 生产类，或由指挥官明确调整质量门判定。当前保留代码与测试供复核，未自行扩大范围。
