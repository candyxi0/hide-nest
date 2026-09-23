# G5-S03-B2-B 执行报告

状态：`G5_S03_B2_B_IMPLEMENTATION_ACCEPTED`
冻结基线：`main` / `ebdafc97ad1cb85c3cd3e9e5a949d40f05e492d1`
执行日期：2026-09-23

## 交付

- 单项 `SUPERSEDE` 沿用 B2-A 请求准备、来源 Anchor 核验和 S02-B 单赢家结算事务。请求必须携带旧 Record ID、具体 expected-current revision、显式证据和 `itemRef`；混合动作与多项替换在准备阶段拒绝。
- Core 锁定仍为 ACTIVE 的旧 Record 并核对 current，分配全新的后继 Record／首版 revision。后继可以同类型或不同类型。仅写本次明确提供的 Anchor 和关系；旧 Record 的 current、正文与关联不改，参与状态改为 SUPERSEDED。
- V005 在 V004 上新增一对一 `record_succession`，记录前驱 Record／最后 current、后继 Record／首版、任务及时间。回执与 `MEMORY_SUPERSEDED` Outbox 新增前驱 Record 列，复合外键分别约束前驱旧 revision 和后继新 revision。旧 CREATE／REVISE 行在迁移中保留，REVISE 前驱列回填为其自身 Record。
- 内部只读 `RecordSuccession.Reader`／JDBC 适配器按旧 ID 返回直接后继，不自动跨代追溯。回执只留 item index 与正式 ID，Outbox 无正文，`itemRef` 和完整 WriteSet 不持久化。重放在旧 Record 状态检查前返回原结算结果；同 key 异哈希拒绝。
- 旧 Record 已接替或不存在返回无效目标；stale current 返回 `CURRENT_CONFLICT`。中途 SQL 失败走既有有限重试，事务回滚且 processed 不推进。

## 真实验证

- JDK 25、PostgreSQL 18.4 Testcontainers：`NestV2CanonicalCreateTest` 22 项、`NestV2FormationControlTest` 12 项、`NestV2FormationIntakeTest` 9 项，共 43 项通过，退出码 0。
- 空库 V001→V005、V004 存量 CREATE／REVISE→V005、重复迁移与 Flyway validate 通过。迁移反证核对既有正文、回执动作、Outbox 和前驱列回填。
- SUPERSEDE 反证覆盖跨类型纠错、同类型接替、连续两次接替只读直接后继、旧 current／正文／Anchor 保留、后继新 ID／首版／显式 Anchor、不复制旧 Evidence、同 key 同值重放与异值拒绝、缺失／已接替／stale 目标拒绝、两次 SUPERSEDE 与 REVISE 对同一 current 的并发单胜、关系写入失败后的事务回滚、权限与单项限制。CREATE、REVISE、NO_LONG_TERM_CHANGE 和 S02-B 单赢家回归通过。
- 全仓 `-DskipTests package` 构建退出 0；受影响 V2 Java 路径的 `spotless:check` 退出 0；`git diff --check` 退出 0。未修改 V001—V004、V1、根 POM、Worker 合同或正式 OpenAPI。

## 失败与修复

- 首次测试命令的 PowerShell 参数未正确引用，退出 1；引用 Maven `-D` 参数后修复。
- 第二次真实数据库测试在 V003 预迁移夹具中提前 TRUNCATE V005 表，退出 1；保留 V003 可运行的 TRUNCATE 并由 CASCADE 清理新表。
- 第三次数据库测试仍断言只执行一项迁移，退出 1；改为显式先迁至 V004、构造 CREATE／REVISE 存量后再迁 V005。
- 首次完整 V2 套件的两个旧测试仍断言四项迁移，退出 1；改为五项后重跑 43/43 通过。
- 无范围约束的 Spotless 检查因其他既有旧文件格式退出 1；仅对本工单允许的 V2 Java 路径应用并检查格式，退出 0。未重排范围外文件。
- 首次重复 JSON key 检查调用了主机不存在的 Python，退出 1；改用 Node 重复 key 扫描器后，与 Node `JSON.parse`、PowerShell `ConvertFrom-Json` 一并通过。Git task pathSet 与报告双向核对为 11/11。

## 限制与复核点

- 本单不实现混合 WriteSet、本批 `item:` 图、`SUPPORT_CHANGED`、split／merge、自动级联 Understanding、真实 Worker／Retrieval／UI。旧条目在真实产品会话中的过滤尚未验证；`G5-FW-REL-01` 仍开放。
- Ajv、真实 Worker、模型调用、真实来源、索引和用户体验均为 `NOT_RUN`。测试只使用本机 Docker／loopback 的既有镜像，没有生产部署或家庭主机写入。
- 施工回执时没有 stage、commit、push 或部署，也没有开始 B2-C／B2-D。工单前已有的 V1 测试文件、`docs/planning/**` 和 `reports/local-v1-read-browser-qa/**` 均原样保留。

## 指挥官独立复核

- 2026-09-23 在 JDK 25 下重跑三组真实 PostgreSQL 定向测试：43 项通过，退出码 0。
- 核对 V005 前驱／后继复合外键、同一结算事务、旧 current 不变、直接后继只读入口、竞争单胜、回滚及 11/11 任务路径；`git diff --check` 通过，暂存区为空。
- 结论仅为本纵切 `IMPLEMENTATION_ACCEPTED`；真实 Worker／Retrieval／索引／用户体验未验收，`G5-FW-REL-01` 仍开放。

## Git task pathSet

`modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/v2/RecordSuccession.java`
`modules/application/src/main/java/io/github/candyxi0/hidenest/application/v2/CreatePublication.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcCreatePublicationWriter.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcRecordSuccessionReader.java`
`modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JooqFormationControlAdapter.java`
`modules/database-adapter-v2/src/main/resources/db/v2/migration/V005__canonical_supersede.sql`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2CanonicalCreateTest.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationControlTest.java`
`modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2FormationIntakeTest.java`
`reports/Nest-V2-G5-S03-B2-B-执行报告.md`
`reports/Nest-V2-G5-S03-B2-B-Evidence.json`
