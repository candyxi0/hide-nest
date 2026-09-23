# G5-S03-B2-C1 执行报告

状态：`G5_S03_B2_C1_IMPLEMENTATION_ACCEPTED`
冻结基线：`main` / `eabf7777ce3280412648ad9a170848471341d509`；开工时 staged=0，index tree=`5eb97bd57b18763d2c264024d0166aef481be14d`。
执行日期：2026-09-23。

## 交付

- 规范关系目标用 `RevisionRef` 的两种互斥值表达：已提交 revision ID 或本 WriteSet 的 `itemRef`。旧 revision 构造器保持可用。含本地边的批次必须全为 CREATE，每项有唯一非空 `itemRef`；准备阶段拒绝重复、未知、自指、同目标重复或 SUPPORT／COUNTER 冲突。
- 新批次先分配全部正式 Record／revision ID，再写各项显式 Anchor，统一解析并写关系。最终图逐个检查 SUPPORT 环、每条 SUPPORT 目标及 Understanding 到直接 Anchor 的路径。只接受同一 Core 已结算 `COMMITTED_WRITE` 的旧 revision；本批目标以预分配 ID 连接，与数组顺序无关。
- 回执和每项 Outbox 在图验证后写入，继续加入原 S02-B 结算事务；任一失败整体回滚，processed 不推进。回执只存 item index 与正式 ID，不持久化本地 `itemRef`。旧 `revision:` 请求哈希编码原样保留，固定哈希回归值已加测试；本地目标编码带 `item:` 前缀。
- CREATE 与 REVISE／SUPERSEDE 混合仍被拒绝；旧单项替换、普通 CREATE、NO_LONG_TERM_CHANGE 沿用原路径。未修改 V001—V005 或数据库权限。

## 真实反证

- JDK 25、PostgreSQL 18.4 Testcontainers，最终 `NestV2*Test`：47 项通过，退出码 0（规范发布 26、Formation 控制 12、来源 intake 9）。验证正反数组顺序、本地与已提交目标的 SUPPORT／COUNTER、两个不连续直接 Anchor、回执正式 ID、同 key 重放与异值拒绝、重复／未知／自指／双关系／COUNTER-only／SUPPORT 环拒绝、第二项关系故障后 Evidence／Memory／回执／Outbox／A／processed 全回滚。
- 原 CREATE／REVISE／SUPERSEDE、来源／world 围栏、单赢家、NO_LONG_TERM_CHANGE 回归在同一套件通过。全仓 `-DskipTests package` 退出 0；四个修改的 Java 文件分别以 `spotlessFiles` 范围检查，退出 0。
- `git diff --check` 退出 0；Evidence 通过 Node `JSON.parse`、PowerShell `ConvertFrom-Json` 与严格重复 key 检查；Git task pathSet 与本报告／Evidence 双向一致（6/6），暂存区仍为空且 index tree 未变。Testcontainers 结束后无带其标签的容器或卷。
- 测试仅使用本机 Docker／loopback 已有镜像，无主动 pull；未接真实来源、Worker 或模型。

## 失败与自修

| 命令／操作 | 退出码 | 原因与重跑 |
| --- | ---: | --- |
| 首次 `.\mvnw.cmd -pl modules/database-adapter-v2 -am -Dtest=NestV2CanonicalCreateTest -Dsurefire.failIfNoSpecifiedTests=false test` | 1 | PowerShell 拆分未引用的 `-D` 参数；引用后定向测试 22/22，退出 0。 |
| 首次 `.\mvnw.cmd -pl modules/database-adapter-v2 -am '-Dtest=NestV2*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test` | 1 | 新测试把冻结终点 120 错断言为 processed=1；修正后 46/46，最终补哈希反证后 47/47，退出 0。 |
| 三项带 `-DspotlessFiles=**/...` 的范围格式检查 | 1 | 插件参数按正则而非 glob 解析；改用 `.*文件名[.]java`，四文件检查退出 0。 |
| 含 `\|` 的双文件 `spotlessFiles` 参数 | 1 | Windows 命令层解释了管道；改为两个独立检查，退出 0。 |
| 新测试首次单文件 `spotless:check` | 1 | 补断言的行尾格式不符；范围内 `spotless:apply spotless:check` 后退出 0。 |
| `python -c` | 1 | 主机没有 `python` 命令；改用 `py`。 |

一次不带文件过滤的三模块 `spotless:apply` 触及了范围外旧 Java 文件。依据开工时这些文件均干净的 Git 状态，逐文件从冻结 HEAD 恢复原检出字节与时间戳；复核后 Git 仅标出四个任务代码文件和开工前已有的 V1 用户文件。后续仅对任务文件应用范围过滤。没有改动既有用户内容或暂存区。

## 开放范围

未实现混合 CREATE／REVISE／SUPERSEDE WriteSet、真实 Worker JSON Adapter、模型形成质量、Retrieval／索引／UI。`G5-FW-REL-01` 保持开放。本次不开始 B2-C2／D，不 stage、commit、push 或部署。

## Git task pathSet

`modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/v2/CreateWriteSet.java`
`modules/application/src/main/java/io/github/candyxi0/hidenest/application/v2/CreatePublication.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcCreatePublicationWriter.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
`reports/Nest-V2-G5-S03-B2-C1-执行报告.md`
`reports/Nest-V2-G5-S03-B2-C1-Evidence.json`
