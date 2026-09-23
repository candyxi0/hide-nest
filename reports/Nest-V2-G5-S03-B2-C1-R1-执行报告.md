# G5-S03-B2-C1-R1 执行报告

状态：`G5_S03_B2_C1_R1_IMPLEMENTATION_ACCEPTED`
冻结基线：`main` / `eabf7777ce3280412648ad9a170848471341d509`；开工 staged=0，index tree=`5eb97bd57b18763d2c264024d0166aef481be14d`。
执行日期：2026-09-23。

## 修复

仅将 `JdbcCreatePublicationWriter.hasSupportCycle` 的路径枚举递归改为去重递归。第一层从新 revision 求出所有可达节点；第二层从每个可达节点的 SUPPORT 出边求 `(start_id, id)` 可达节点对，发现 `start_id=id` 即为环。两层均使用 SQL `UNION` 去重，最多处理可达节点数 `V` 与节点对数 `V²`，再按 SUPPORT 边扩展；不同路径到达同一节点对只保留一次。没有任意深度截断，且能发现新 revision 可达、但不经过该 revision 的下游环。关系方向、SUPPORT-only 语义、来源与事务边界均未改动。

## 真实反证

- 新增真实 PostgreSQL 用例：32 层、每层两个带直接 Anchor 的 CREATE Event，共 64 项、124 条 SUPPORT 边，构成大量不同路径但无环的合法 WriteSet。结算事务内设置 `SET LOCAL statement_timeout = '1500ms'`。旧实现两次受控失败，退出码 1；第二次捕获 SQLSTATE `57014`，结算结果 `RETRY_WAIT`。替换算法后同一用例在同一硬边界内 `COMMITTED_WRITE`，64 个 Record、64 个回执项与 Outbox、124 条关系及 processed 一同落库，退出码 0。判定依据同时包括去重算法的 `V`／`V²` 上界，而非仅凭墙钟时间。
- C1 既有真实 SUPPORT 环负例原样保留：即使两端有直接 Anchor，仍拒绝成环；失败后 Evidence、Memory、回执、Outbox、A 和 processed 无半提交。其余同批正反顺序、relation-only Understanding、非法目标与关系、旧哈希／重放、CREATE／REVISE／SUPERSEDE、NO_LONG_TERM_CHANGE 等用例均未删减或放宽。
- JDK 25、PostgreSQL 18.4 Testcontainers：新用例定向 1/1、C1 定向 27/27、完整 V2 套件 48/48，均退出码 0。全仓 `-DskipTests package` 14 模块成功，退出码 0；仅对本 R1 两个 Java 文件执行范围内 `spotless:apply spotless:check`，退出码 0。
- `git diff --check` 退出码 0。R1 Evidence 经 Node `JSON.parse`、PowerShell `ConvertFrom-Json` 和严格重复 key 检查；真实 Git／报告／Evidence 的 C1＋R1 taskPathSet 双向一致（8/8）。暂存区为空，index tree 未变；Testcontainers 结束后无带其标签的容器或卷。

## 失败与自修重跑

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `.\mvnw.cmd -pl modules/database-adapter-v2 -am '-Dtest=NestV2CanonicalCreateTest#denseAcyclicSupportGraphHasBoundedCycleCheck' '-Dsurefire.failIfNoSpecifiedTests=false' test`，旧算法首次 | 1 | 预期失败：`COMMITTED_WRITE` 断言收到 `RETRY_WAIT`；statement timeout 保证无挂测。 |
| 同一命令，旧算法增加 SQLSTATE 捕获后 | 1 | 预期失败：`SQLSTATE=57014`，证实 PostgreSQL statement timeout。 |
| 同一命令，去重算法后 | 0 | 1/1 成功，64 项整批发布。 |
| `.\mvnw.cmd -pl modules/database-adapter-v2 -am '-Dtest=NestV2CanonicalCreateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 0 | 27/27。 |
| `.\mvnw.cmd -pl modules/database-adapter-v2 -am '-Dtest=NestV2*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 0 | 48/48。 |
| `.\mvnw.cmd '-DskipTests' package` | 0 | 全仓 14 模块构建。 |

## 范围与开放项

R1 只增改发布写入器、真实 PostgreSQL 测试和本报告／Evidence；C1 原有其余任务文件及全部用户文件保留。未改 migration、权限、哈希、合同或其他生产模块。真实 Worker／模型／来源／索引／用户体验均为 `NOT_RUN`；混合动作与 C2／D 未实施，`G5-FW-REL-01` 仍开放。施工回执时未 stage、commit、push 或部署。

## 指挥官独立复核

- 2026-09-23 核对去重递归的节点／节点对边界、下游环检测与 64 项受控反证，并在 JDK 25 下重跑完整真实 PostgreSQL V2 套件：48/48 通过，退出码 0。
- C1＋R1 的八条任务路径与 Evidence 双向一致，`git diff --check` 通过，暂存区为空。结论仅为本纵切 `IMPLEMENTATION_ACCEPTED`；真实 Worker／模型／来源及产品连续性未验收。

## Git taskPathSet（C1 与 R1）

`modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/v2/CreateWriteSet.java`
`modules/application/src/main/java/io/github/candyxi0/hidenest/application/v2/CreatePublication.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcCreatePublicationWriter.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
`reports/Nest-V2-G5-S03-B2-C1-执行报告.md`
`reports/Nest-V2-G5-S03-B2-C1-Evidence.json`
`reports/Nest-V2-G5-S03-B2-C1-R1-执行报告.md`
`reports/Nest-V2-G5-S03-B2-C1-R1-Evidence.json`
