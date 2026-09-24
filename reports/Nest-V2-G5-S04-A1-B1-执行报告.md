# Nest V2 G5-S04-A1-B1 执行报告

## 指挥官独立复核（2026-09-24）

**结论：**`IMPLEMENTATION_ACCEPTED`。指挥官复核了 B1 类型化结果、单一只读快照、Outbox 指定 revision／predecessor、完整关系与 INCOMPLETE 无正文出口；独立运行 `mvnw.cmd -pl modules/database-adapter-v2 -am test -o`，V2 PostgreSQL 77／77，退出码 0；三个本单 Java 文件各自定向 Spotless、全仓离线跳测构建、Evidence 三种解析、Git 五条任务路径双向核对与 `git diff --check` 均通过。

memory 模块完整 Spotless 对未改旧文件仍退出 1；本工单在施工前明确规定强制格式门为每个改动文件逐一通过，因此没有把完整模块失败记为 PASS，也没有新增事后验收例外。本结论只覆盖 Core 正式材料只读能力，B2 JSON／digest／回执围栏与真实 Worker／索引／连续性体验仍是 `NOT_RUN`。入库结果另行核验。

**状态：**`G5_S04_A1_B1_READY_FOR_COMMANDER_REVIEW`
**基线：**`main` / `1ae4a3b3c1ef8c71372a789ea46c89d98ffa624c`
**证据：**`reports/Nest-V2-G5-S04-A1-B1-Evidence.json`

## 本单结果

Core 新增内部 `ProjectionMaterialRead.Reader`，按本实例 world 与已提交 Outbox `event_id` 返回完整类型化材料，或只带原因的 `INCOMPLETE`。JDBC 适配器在 PostgreSQL `REPEATABLE READ`、`READ ONLY` 单一事务中读取 world 绑定、Outbox、事件指定的 revision、正式 predecessor revision 与两者的显式 SUPPORT／COUNTER 关系。它不读 `current_revision_id`、Evidence 正文或派生索引。CREATE 无 predecessor；REVISE／SUPERSEDE 只附一个正式旧 revision；主材料 `successorRefs` 始终为空。关系合计超过 100、重复／矛盾、owner 或动作错绑、字段越界、预算耗尽、数据库超时及读取失败均不返回正文半包。

没有新增 HTTP、MCP、Worker JSON、digest、投递状态转换或索引写入。B2、真实 Worker／索引／Embedding／Retrieval／连续性体验均为 `NOT_RUN`。

## 真实验证

| 门 | 结果 |
| --- | --- |
| B1 定向 PostgreSQL 18 Testcontainers 测试 | `PASS`，6 项，退出码 0 |
| 完整 V2 PostgreSQL 测试 | `PASS`，77 项，原有 71 项加 B1 6 项，失败／错误均 0，退出码 0 |
| 三个本单 Java 文件逐文件 Spotless | `PASS`，各退出码 0 |
| 完整 memory 模块 Spotless | `KNOWN_BASELINE_DEBT`，退出码 1；仅未改旧文件报错，本单文件逐文件通过 |
| 全仓 `mvn -o -q '-DskipTests' package` | `PASS`，退出码 0；JDK 25.0.4 |
| `git diff --check`、暂存区 | `PASS`；退出码 0、staged=0 |
| Evidence 解析 | Node `JSON.parse`、PowerShell `ConvertFrom-Json`、Python 严格重复 key 检查均通过 |
| Testcontainers 后台资源 | 测试结束 `docker ps` 无运行容器；卷最近创建时间为 2026-08-16，本单无新增遗留卷；本单启动的 Docker Desktop 已关闭，无遗留 Docker／Java 测试进程；未主动拉取镜像 |

反证覆盖三类已提交事件的历史正文与正式 predecessor，后续接替不改变旧事件材料，旧 revision 显式关系、100／101 条关系边界、跨 kind 矛盾关系、未提交事件不可见、并发 REVISE／SUPERSEDE 的提交可见性、world／事件／owner 错绑、缺 predecessor、正文越界、查询预算、真实 PostgreSQL statement timeout、受控连接故障、Evidence `exact_text` 排除与规范／投递／来源表零写入。测试只使用合成数据。

## 已修复的施工失败

- 首次 Maven 命令的 PowerShell `-D` 引号解析失败，退出码 1；改用单引号参数后继续。
- 首次测试编译时把 `DataSource` 当函数接口，退出码 1；改用可覆写的 `DriverManagerDataSource`。
- 本机 PATH 尾段格式异常触发 Testcontainers 路径解析失败，退出码 1；仅在测试进程内裁掉异常尾段。随后 Docker Engine 未启动，真实库测试退出码 1；启动本机 Docker Desktop 后使用已存在的 PostgreSQL 18 镜像。
- 扩充错绑夹具时违反 Outbox `(revision_id,event_kind)` 唯一约束，退出码 1；改为独立合成 successor，再次运行完整 V2 测试通过。
- Spotless 文件过滤最初误用 glob（插件要求正则），退出码 1；改成单文件正则并逐文件通过。
- 首次严格重复 key 检查使用环境中不可解析的 `python` 命令，退出码 1；改为明确的本机 Python 3.14 路径后通过。
- 首次报告／Evidence 双向路径核对的内联正则被 PowerShell 转义，退出码 1；改为无反引号语法的路径提取后，严格重复 key 与五条 `taskPathSet` 双向核对通过。

完整 memory 模块 Spotless 单独运行，退出码 1；违规仅来自未改旧文件，例如 `AccessPolicy.java`、`ActorRef.java`、`Proposal.java`、`ReviewMember.java`、`DeletionConfirmationPort.java` 等。未越界格式化或将整模块门记为通过。

## 路径与边界

本单 `taskPathSet`（与 Evidence.json 完全一致）：

1. `modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/v2/ProjectionMaterialRead.java`
2. `modules/database-adapter-v2/src/main/java/io/github/candyxi0/hidenest/database/v2/adapter/JdbcProjectionMaterialReader.java`
3. `modules/database-adapter-v2/src/test/java/io/github/candyxi0/hidenest/database/v2/NestV2ProjectionMaterialReadTest.java`
4. `reports/Nest-V2-G5-S04-A1-B1-执行报告.md`
5. `reports/Nest-V2-G5-S04-A1-B1-Evidence.json`

开工时已有的 `modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1BubbleCoreDatabaseTest.java` 工作树改动及 `docs/planning/**`、`reports/local-v1-read-browser-qa/**` 文件未触碰，也未暂存。未修改 migration、A0 合同、A1-A、S03、V1、根 POM。未 stage、commit、push 或部署。

**验收范围：**Core 规范材料读取已验证。JSON runtime validation、真实 Worker／索引／Embedding／Retrieval／连续性体验：`NOT_RUN`。
