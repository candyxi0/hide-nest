# G5-S03-B2-A 执行报告

状态：`G5_S03_B2_A_IMPLEMENTATION_ACCEPTED`
基线：`main` / `fd10e71ba648da82b22394b7d099ffc17d074963`
执行日期：2026-09-23

## 交付

- 单项 `REVISE` 复用 S03-A 的准备、证据核验、规范写入和 S02-B 的唯一短事务结算。请求携带 Record ID、具体 expected revision 和仅在内存里的 `itemRef`；混合动作和多项 REVISE 在准备阶段拒绝。
- V004 允许同 Record 的连续不可变 revision、原子 current 切换、通用回执条目的动作与 expected revision、`MEMORY_REVISED` Outbox 的前一 revision。V003 的 CREATE 回执和事件在迁移中保留；`itemRef`、WriteSet 正文与草稿均不入库。
- 新 revision 只写本请求显式 Anchor 与关系；Understanding 可显式沿旧 revision 的 SUPPORT 路径追溯旧 Anchor，不复制 Evidence。Record 类型及 participation 在事务内检查；锁定 Record 后再核对 expectedCurrent。
- current 冲突返回 `CURRENT_CONFLICT`，attempt 记该失败码并等待重新 Formation；数据库规范写入失败仍沿既有有限重试路径。成功重放在 current 检查前由结算槽返回，按原 key/hash 可找回正式 ID。

## 真实验证

- JDK 25、PostgreSQL 18.4 Testcontainers：`NestV2CanonicalCreateTest` 15 项、`NestV2FormationControlTest` 12 项、`NestV2FormationIntakeTest` 9 项，共 36 项通过，退出码 0。
- 空库 V001→V004、V003 存量 CREATE 行→V004、重复迁移与 Flyway validate 均通过。测试检查旧 CREATE 正文、回执动作默认值和 Outbox 保留。
- REVISE 反证覆盖 r1→r2、旧版与旧关系保留、显式新 Anchor、Understanding 经旧 revision 追 Anchor 且 Evidence 不复制、同 key 同值重放与异值拒绝、两来源竞争只一胜、stale current 的独立失败码、无效类型、已接替与不存在目标、world 与来源 gate、写入关系失败整组回滚及 processed 不推进。既有 CREATE、单赢家和 NO_LONG_TERM_CHANGE 回归通过。
- `-DskipTests package` 全仓构建、受影响模块 `spotless:check`（以 HEAD 为 ratchet）、`git diff --check` 均退出 0。未更改 V003、V1、Worker 合同或根 POM。

首次测试命令因本机 PATH 的无效段退出 1；修正该进程的 PATH 后 Docker 引擎未启动，第二次退出 1。启动本机 Docker Desktop 后，迁移数量旧断言触发第三次退出 1；修订断言后的数据库测试最终退出 0。首次失败和重跑记录见 Evidence。

## 限制与复核点

- 本阶段不接受混合动作、本批 `item:` 关系、SUPERSEDE、依赖变化读取或真实 Worker JSON。`G5-FW-REL-01` 未闭合。
- V004 允许存储 `SUPERSEDED` participation 以反证 REVISE 拒绝该状态；本切片不给 API 修改 participation 的权限，也不提供接替动作。该 fixture 由测试迁移角色合成。
- 未运行 Ajv、真实 Worker、模型调用、真实来源或用户体验评测。测试通过只证明上述规范提交与事务行为。
- 施工回执时未 stage、commit、push、部署，未开始 B2-B/B2-C/B2-D。

## 指挥官独立复核

- 2026-09-23 在 JDK 25 下重跑三组真实 PostgreSQL 定向测试：36 项通过，退出码 0。
- 核对 REVISE 的 expected-current、同一事务内的 revision/current/回执/Outbox/任务结算，以及 current 冲突回滚；任务路径与 Evidence 双向一致（11/11），`git diff --check` 通过，暂存区为空。
- 结论仅为本纵切 `IMPLEMENTATION_ACCEPTED`；真实 Worker、模型、来源与连续性体验未验收，`G5-FW-REL-01` 仍开放。

## Git task pathSet

`modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/v2/CreateWriteSet.java`
`modules/application/src/main/java/io/github/candyxi0/hidenest/application/v2/CreatePublication.java`
`modules/runtime/src/main/java/io/github/candyxi0/hidenest/runtime/domain/FormationSettlementOutcome.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcCreatePublicationWriter.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JooqFormationControlAdapter.java`
`modules/database-adapter-v2/src/main/resources/db/v2/migration/V004__canonical_revise.sql`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationControlTest.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationIntakeTest.java`
`reports/Nest-V2-G5-S03-B2-A-执行报告.md`
`reports/Nest-V2-G5-S03-B2-A-Evidence.json`

工单开工前已有的 V1 测试文件、`docs/planning/**` 和 `reports/local-v1-read-browser-qa/**` 改动保留原样，均不计入 task pathSet。
