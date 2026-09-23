# G5-S03-B2-C2 执行报告

状态：`G5_S03_B2_C2_IMPLEMENTATION_ACCEPTED`
基线：`main` / `59ba6f324b4c6e58e0900ba6f22be1e84ad34842`；开工时 staged=0，index tree=`a46f5c0ef63fdbc67516af9718aed22dbad9e940`。
日期：2026-09-23。

## 实施结果

- 准备阶段允许 CREATE、REVISE、SUPERSEDE 同批出现；混合批次每项须有唯一非空 `itemRef`，同一旧 Record 的第二个变化动作在来源回读前拒绝。纯旧式 CREATE 的无本地引用路径和规范哈希编码未改。
- 首次结算将所有变化目标按 Record ID 稳定排序并加行锁，复核 ACTIVE、REVISE type 和 expected-current；先预分配结果 ID，再写 Evidence、Record／revision 和显式关系，验证完整 SUPPORT 图后才切换 current、登记接替及 participation。回执／Outbox 仍与 A 结算和 processed 在 S02-B 同一事务提交。
- V006 仅移除 `memory.record_succession.task_id` 的单行唯一约束。前驱主键、后继唯一、两个复合 revision 外键、task 外键与原权限保留，同一任务可接替两个不同旧 Record。
- 旧 `itemRef` 仅用于本批内存映射，回执继续按 item index 保存正式 ID；没有新增持久草稿或更改 V001—V005、Worker 合同、V1 运行代码。

## 真实验证

- JDK 25.0.4、PostgreSQL 18.4 Testcontainers：完整 V2 数据库套件 54/54 通过（规范发布 33、Formation 控制 12、来源 intake 9），上游应用测试 61/61 通过。空库 V001→V006、V005 含 CREATE／REVISE／SUPERSEDE 存量升级 V006、旧成功重放、重复 migrate 和 Flyway validate 均通过。
- 混合批次证明 CREATE Event、REVISE Understanding、两项不同旧 Record 的 SUPERSEDE 同批成功；正反向本批 `item:` SUPPORT／COUNTER、已提交 `revision:`、正式回执与 Outbox 正确。第二目标 stale、后项 Anchor／关系／SQL 故障均证明前项及 Evidence、Memory、后继、回执、Outbox、A、processed 零半提交。同目标双动作在来源回读前拒绝；相反数组顺序的双任务并发有 20 秒硬超时，只有一批胜出。
- 旧 CREATE／REVISE／SUPERSEDE、同 key 重放与异值拒绝、来源和 world 围栏、单赢家、NO_LONG_TERM_CHANGE、64 项有界无环图和 SUPPORT 环负例继续通过。
- 5 个任务 Java 文件逐文件 `spotlessFiles` 范围检查退出 0；全仓 `mvn '-DskipTests' package` 14 模块退出 0；`git diff --check` 退出 0。仅用本机已有镜像和 loopback；测试后无 `org.testcontainers` 标记的残留容器或卷。

## 失败、自修与重跑

| 操作 | 退出码 | 处理 |
| --- | ---: | --- |
| 首次定向 Maven，未引用 PowerShell 的 `-D` 参数 | 1 | 引用参数后 30/30 通过，退出 0。 |
| 新增失败反证首次定向运行 | 1 | 测试 Anchor 元数据在事务外先被拒绝；修正测试输入后 33/33 通过，退出 0。 |
| 首次完整 V2 套件 | 1 | Control／Intake 仍断言 V005 迁移次数；更新到 V006 后重跑。 |
| 第二次完整 V2 套件 | 1 | 两处历史表行数仍断言 5；改为 6 后最终 54/54，退出 0。 |
| 新增测试的首次范围 Spotless 检查 | 1 | 对三项测试逐文件格式化并复检，连同两个生产文件均退出 0。 |
| `python` 命令 | 1 | 主机只有 `py`；用 `py -3` 恢复格式化误触的旧 application 文件。 |
| 首次 Evidence／Git pathSet 脚本 | 1 | Git 默认引用非 ASCII 路径；改用 `core.quotePath=false` 后继续核对。 |
| 第二次 Evidence／Git pathSet 脚本 | 1 | 把 Markdown 有意的双空格换行误当成尾随空白；仅对 Java／SQL／JSON 执行该附加检查后，严格重复 key 与双向 8/8 pathSet 退出 0。 |

一次模块级 `spotless:apply` 触及了开工前干净、但不在范围内的旧 application 文件。已逐文件从冻结 HEAD 恢复原内容与检出换行；Git 差异复核仅剩本任务路径及开工前已有的 V1 用户改动。未清理用户文件，暂存区仍为空，index tree 未变。

## 未运行与边界

真实 Worker JSON Adapter、真实来源、模型语义质量、索引消费、Retrieval、UI 和连续性体验均为 `NOT_RUN`。`G5-FW-REL-01` 至多关闭 Core 混合写入部分，真实 Worker Adapter 前置门仍待后续验收。未进入 B2-D；施工回执时未 stage、commit、push 或部署。

## 指挥官独立复核

- 2026-09-23 核对固定目标锁序、同批多接替、V006 保留一对一约束、图验证后切换 current／participation、失败整批回滚与精确重放；JDK 25 下独立重跑真实 PostgreSQL V2 套件 54/54，通过。
- 八条任务路径与 Evidence 双向一致，`git diff --check` 通过，暂存区为空。结论仅为本纵切 `IMPLEMENTATION_ACCEPTED`；真实 Worker／模型／来源及产品连续性未验收。

## Git task pathSet

`modules/application/src/main/java/io/github/candyxi0/hidenest/application/v2/CreatePublication.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcCreatePublicationWriter.java`
`modules/database-adapter-v2/src/main/resources/db/v2/migration/V006__multiple_successions_per_task.sql`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationControlTest.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationIntakeTest.java`
`reports/Nest-V2-G5-S03-B2-C2-执行报告.md`
`reports/Nest-V2-G5-S03-B2-C2-Evidence.json`
