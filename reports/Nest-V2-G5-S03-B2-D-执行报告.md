# Nest V2 G5-S03-B2-D 执行报告

状态：`G5_S03_B2_D_IMPLEMENTATION_ACCEPTED`
基线：`main` / `62e6cd7ef15019623c87a2d0070d14b0089a5879`；开工时 staged=0，index tree=`1da86bbfc29a5cff8d4cdc8f6f99834908076974`。
实施范围：同一 Core 实例的内部规范只读接口。施工回执时没有 stage、commit、push、部署。

## 实现

- `DependencyRead` 定义显式结果状态、root／具体 revision、SUPPORT／COUNTER 关系、路径见证和历史 Evidence 正式入口。`NO_VERSION_CHANGE_OBSERVED` 仅是版本观察，不是真假裁决。
- `JdbcDependencyReader` 在一个 PostgreSQL `REPEATABLE READ`、`READ ONLY` 事务内读取 world、root、revision 关系和 Anchor。按 revision／边去重，以稳定顺序广度优先遍历 SUPPORT；COUNTER 只返回直接关系信息，不进入 SUPPORT 见证。
- 默认预算：256 个 revision、1024 条关系、64 条变化见证、5000 ms。预算、查询故障、异常不一致或 SUPPORT 环均返回无 root／关系半包的 `INCOMPLETE`。预算可由内部调用方调整。
- 旧 Record ID 保持旧身份、最后 current 和直接后继。Evidence 只为实际 SUPPORT 路径上的 Event／Quote 返回 Anchor 与来源坐标，不返回 `exact_text` 或完整聊天。Claim 的旧正文不被包装成历史经历。

## 真实数据库反证

在本机已有 PostgreSQL 18.4 镜像上运行 Testcontainers：完整无变化路径；直接 REVISE；A→B→旧 Claim 的隔层变化与另一条独立未变支持；旧 Record SUPERSEDE 与直接后继；COUNTER 变化不传播；真实 Event／Anchor 与无 Event 反例；world 不匹配、Record 缺失、节点／见证预算、连接失败、意外 SUPPORT 环均不报完整新鲜；并发 REVISE 插入一次读取过程时，快照前后结果自洽；读取前后 canonical 表和运行游标的整表摘要一致。新增 7 项通过。

原有 V2 54 项真实 PostgreSQL 回归通过；总计 61 项，0 failures、0 errors。真实粉机／Retrieval 体验：`NOT_RUN`，后续 Retrieval 交付前 current／治理围栏：`NOT_RUN`。

## 命令与退出码

| 检查 | 退出码 | 结果 |
| --- | ---: | --- |
| 首次定向 Maven 命令，PowerShell 未正确引用 `-D` 参数 | 1 | CLI 参数解析失败；无测试执行，随后修正引号 |
| 定向 `NestV2DependencyReadTest` | 0 | 当时 6 项通过，后续增加异常环反证并在完整套件重跑 |
| 完整 `NestV2*`（最终） | 0 | 33+12+9+7=61 项通过 |
| `mvn -q -DskipTests package` | 0 | 全仓跳测构建通过 |
| `spotless:check`：`database-adapter-v2` | 0 | 受影响适配器／测试格式通过 |
| `spotless:check`：仅 `DependencyRead.java` | 0 | 受影响 Memory 文件格式通过 |
| `git diff --check` | 0 | 无本单空白错误 |

Evidence 经 Node `JSON.parse`、PowerShell `ConvertFrom-Json`、Python 严格重复 key 检查，退出码均为 0；`taskPathSet` 与 Git 中本单五个未跟踪路径双向一致。

## 指挥官独立复核

- 2026-09-23 核对同一只读快照、路径级 SUPPORT 变化见证、历史 Event／Quote Anchor 入口、旧 ID 直接后继和预算失败时清空半包；JDK 25 下独立重跑真实 PostgreSQL V2 套件 61/61，通过。
- 五条任务路径与 Evidence 双向一致，`git diff --check` 通过，暂存区为空。仅判定本单 `IMPLEMENTATION_ACCEPTED`；真实粉机／Retrieval／最终交付围栏和连续性体验仍为 `NOT_RUN`。

格式化时曾扩大到 `memory` 模块并改动了开工时干净的旧文件；只恢复这些由本次格式化产生的改动，本单新增文件和用户原有内容均保留。首次 Memory 定向格式检查使用 glob 导致正则解析失败，改为正则后退出码 0。

## 范围与工作树

本单 `taskPathSet` 与 Evidence 相同，仅两份生产代码、一份真实数据库测试和两份报告；V001—V006、V1、Worker、OpenAPI、写入器及 S02-B 均未改。开工前已有的 V1 测试改动、`docs/planning/**` 和 `reports/local-v1-read-browser-qa/**` 原样保留。最终 Git staged=0；Docker 运行中容器数为 0，按卷 `CreatedAt` 检查，本单开始后创建的留存卷数为 0。
