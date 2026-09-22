# Nest V2 G5-S03-A 执行报告

状态：`G5_S03_A_R1_READY_FOR_COMMANDER_REVIEW`
基线：`main` / `126555d5845835763e1351d9ad2d4e9ac38c6711`
日期：2026-09-22

## 实施

- V003 在独立 V2 迁移链中新增单世界绑定、来源写入资格围栏、被引用的 Evidence SourceUnit／Anchor、Memory Record／首版 Revision／具体 revision 关系、最小幂等回执和投影 Outbox。Worker 对规范表和围栏无直接读写权限。
- `CreatePublication.prepare` 严格验证内存 CREATE 命令，对每个 locator 经受信 `SourceResolver` 事务外回读并核对精确原文、版本、任务 source identity、read binding、可比较位置和 frame／actor／speaking-as；冻结终点之外、未知位置和倒置范围 fail-closed。规范哈希由 Core 对完整命令按字段顺序和字节长度计算。`Prepared` 只能由核验过程构造，内容不可变，并绑定 task／attempt／from／to／read binding，结算时不能换候选。
- `JdbcCreatePublicationWriter` 通过 S02-B 既有 `CanonicalCommitPort` 回调加入同一 JDBC 事务；事务内再查实例世界、注册来源、当前来源写入资格、版本、幂等键和已提交 revision 的 Anchor 路径／支持环。多项 CREATE、Evidence、回执、Outbox、赢家结算和 processed 一起提交或回滚。没有新增规范事务、文件载荷、候选草稿表或真实来源接入。
- 丢失成功响应后可按原 key 和规范哈希查询最小回执；同 key 同内容重放不新增 revision。正文不进入运行表、回执或 Outbox。
- 阶段性关系范围只含直接 Anchor 与已提交 revision。`G5-FW-REL-01` 仍是真实 Formation Worker 接入前的硬前置门：同一 WRITE_SET 新建 item 之间的稳定显式关系合同、解析和反证尚未实现。

## 验证

| 门 | 结果 |
| --- | --- |
| JDK / PostgreSQL | Temurin 25.0.4 / Testcontainers PostgreSQL 18.4 |
| V001→V003 空库、V002→V003、重复迁移、无 V1 表 | PASS |
| 规范化 CREATE 硬校验与来源回读 | PASS：动作、字段、精确原文、版本、locator、不完整来源、哈希 |
| 真实数据库事务反证 | PASS：单项／双项 CREATE、第二项失败全回滚、processed 不推进、重复回执、异值冲突、已提交 revision 支持路径、环拒绝、游戏 frame、世界／来源围栏 |
| Worker SQL 权限 | PASS：实际 `SET ROLE hide_nest_worker` 后规范表 `SELECT` 被拒绝 |
| S02-B 回归 | PASS：单赢家、迟到结果、A/B 顺序、失败不推进、NO_LONG_TERM_CHANGE 零规范写入 |
| 定向测试 | 同一真实 PostgreSQL 命令退出码 0；32 项（R1 CREATE 11 + 原三组 29），0 失败、0 错误、0 跳过 |
| V2 格式 | `mvn -q -pl modules/database-adapter-v2 spotless:check`：退出码 0；新增 evidence／memory／application V2 文件的定向 Spotless 检查均退出码 0 |
| 全仓跳过测试构建 | `mvn -q -DskipTests package`：退出码 0 |
| Git | `git diff --check`：退出码 0；staged=0；未 stage、commit、push 或部署 |

失败记录：PowerShell 未引用逗号分隔的 `-Dtest` 参数，命令解析退出码 1；首轮真实数据库定向测试退出码 1，暴露 V003 外键与 S02-B 测试夹具清理不兼容，以及两处测试预期和一处反证构造错误。哈希不符改为结算失败语义后，一次重跑退出码 1，原因是测试复用了已标记失败的 attempt；改用新 attempt 后原任务重跑退出码 0。R1 首轮编译退出码 1，原因是 evidence 模块不能依赖 runtime；改为在 SourceResolver 中定义无依赖的边界／binding 值对象后退出码 0。R1 首轮 CREATE 测试退出码 1，原因是旧测试准备结果仍绑定失败 attempt；改用重试 attempt 后退出码 0。R1 一次候选 identity 加严试跑退出码 1，原因是既有幂等冲突夹具使用了不同 key 的 Prepared 快照；回到本单要求的 task／attempt／冻结边界／read binding 绑定后重跑退出码 0。全模块 Spotless 初查退出码 1，原因是 evidence 等模块已有与本工单无关的格式差异；随后只格式化、检查本次新增 V2 文件，database-adapter-v2 整模块检查退出码 0。首次失败没有计为通过。

V1 database-adapter 本轮没有新改动；S02-B 回执中的 441 项 V1 回归是此前结果，本轮未重跑。

## 未运行的门

- 真实 Worker JSON 适配：`NOT_RUN`。
- 真实模型：`NOT_RUN`。
- 真实 Source Adapter：`NOT_RUN`。
- Formation 语义质量：`NOT_RUN`。
- 用户体验验收：`NOT_RUN`。

## 路径核对与边界

本任务新增／修改路径只有：

1. `modules/evidence/src/main/java/io/github/candyxi0/hidenest/evidence/v2/SourceResolver.java`
2. `modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/v2/CreateWriteSet.java`
3. `modules/application/src/main/java/io/github/candyxi0/hidenest/application/v2/CreatePublication.java`
4. `modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcCreatePublicationWriter.java`
5. `modules/database-adapter-v2/src/main/resources/db/v2/migration/V003__canonical_create.sql`
6. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
7. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationControlTest.java`
8. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationIntakeTest.java`
9. `modules/database-adapter-v2/pom.xml`
10. `reports/Nest-V2-G5-S03-A-执行报告.md`
11. `reports/Nest-V2-G5-S03-A-Evidence.json`

开工前已有的 V1 测试换行标记、`docs/planning/**` 和 `reports/local-v1-read-browser-qa/**` 用户文件保留原样，不属于本任务。完成后停在指挥官复核门；没有开始 S03-B。

## R1 冻结范围反证

- `(100,120]` 任务中，位置 50 的可信历史 Anchor 与位置 120 的边界 Anchor 均可成功发布，证明 `fromExclusive` 不是证据下界。
- 位置 121、覆盖到 121 的范围、未知位置和倒置范围均在事务外拒绝；Evidence、Memory、Outbox、回执不增加，processed 保持 100。
- 返回错误 source identity、版本、read binding、locator 或精确文本均 fail-closed。
- Prepared 结果换成另一个 task／attempt、另一冻结终点或另一 read binding 时返回 `INVALID_RESULT`，不调用数据库结算，不修改另一个候选。
- SourceResolver 只在准备阶段调用；结算阶段通过不可变 Prepared 结果，不再次回读来源。

R1 本轮只修改 `SourceResolver.java`、`CreatePublication.java`、`NestV2CanonicalCreateTest.java` 及本报告／Evidence；没有修改 V003、S01 合同、S02-B 状态机、Jdbc writer、其他原任务文件或用户资料。`G5-FW-REL-01`、真实 Worker／Source Adapter／模型和 S03-B 边界保持原状态。
