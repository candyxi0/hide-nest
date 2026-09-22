# Nest V2 G5-S02-B 执行报告

实施状态：`IMPLEMENTED_AWAITING_COMMANDER_REVIEW`

基线：`main` / `1d73a86361459146879dbdd15808926db1bd003a`

执行日期：2026-09-22

## 实现

- V002 在 V2 空库链上增加前序任务、任务状态、尝试租约、唯一赢家标记和去重告知。Worker 对三张运行表无直接读写权限；无正文或 WRITE_SET 草稿列。
- 来源更新沿用来源级串行锁。A 领取后冻结；B 从 A 的 `to` 开始并记录 `predecessor_task_id`。B 可继续合并范围，只有 A 正式完成后才能领取。
- `claim` 使用 `FOR UPDATE SKIP LOCKED`；续租保持冻结范围；租约到期可重领。过期的旧尝试只要结算槽仍空，仍可提交完整有效结果。
- 结算事务先核对任务、范围、来源、读取绑定和协议，再调用注入的硬校验边界。WRITE_SET 的注入提交使用同一 JDBC 连接；规范提交、赢家、任务状态和 processed 原子闭合。processed 另有起点围栏。胜出后标记兄弟尝试并尽力请求停止。
- 失败作用于同一任务，按有限次数重试；上限后只读查询返回一条通用告知。没有自动恢复或治理命令。

## 验证

| 门 | 结果 | 证据 |
| --- | --- | --- |
| JDK | PASS | Temurin 25.0.4 |
| V001→V002、重复迁移、schema 与权限 | PASS | PostgreSQL 18.4 / Flyway 实测 |
| V2 合同与真实数据库状态机 | PASS | 17 测试，0 失败：S02-B 8 项、S02-A 9 项 |
| 前序关系、租约、结算槽、迟到结果 | PASS | 并发 claim、并发结算、迟到旧尝试、B 阻塞与合并测试 |
| processed 与规范写入原子性 | PASS | NO_CHANGE、WRITE_SET 成功及注入失败回滚测试 |
| 有限重试告知及去重 | PASS | RETRY_WAIT、ATTENTION_REQUIRED 与单条 notice 测试 |
| V2 格式 | PASS | `mvnw.cmd -pl modules/database-adapter-v2 spotless:check -o` |
| V1 回归 | PASS | `mvnw.cmd -q -pl modules/database-adapter -am test -o`；441 测试、0 失败、1 跳过 |
| `git diff --check`、暂存区 | PASS | 无空白错误；staged=0 |

测试失败历史（未删改）：

1. 首轮 V2 测试在 Testcontainers 启动前失败：本机 PATH 的既有格式错误且 Docker 引擎未启动。仅在测试进程清理 PATH，并启动本机已安装的 Docker Desktop；未拉取镜像。
2. 首轮真实数据库测试发现新增赢家外键使测试夹具原清理顺序无效。测试夹具改为一次性清理相关运行表，重跑通过。
3. Runtime 模块整体 Spotless 检查会触及本工单之外已有格式差异，因此只执行工单要求的 V2 模块格式门；未修改这些既有文件。

## 范围与边界

- 本工单产物均在允许路径；禁止范围新改动为 0。开始前已有的 V1 测试工作树修改及 `docs/planning/**`、`reports/local-v1-read-browser-qa/**` 未跟踪内容保持原样。
- `CanonicalCommitPort` 与 `FormationHardCheckPort` 是供 S03 接入的进程内边界。本工单只用测试实现验证成功、拒绝和回滚，不宣称真实 Evidence／Memory 发布已经完成。
- 合同／实现测试：`PASS`；真实数据库状态机：`PASS`；语义质量：`NOT_RUN`；真实 Worker／模型：`NOT_RUN`；UI：`NOT_RUN`；用户体验验收：`NOT_RUN`。
- 无 stage、commit、push、部署、真实模型调用、联网安装或新镜像拉取；测试容器退出后无遗留运行容器。
