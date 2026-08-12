# Local V1 S3B1 R1 执行报告

返工状态：LOCAL_V1_S3B1_R1_READY_FOR_HIDE_REVIEW

## 结论

- V012 删除围栏在真实 PostgreSQL 中有效；六类共 12 个防复活攻击全部被正式表触发器拒绝。
- 上一轮 `memory.memory_record` “未被拒绝”不是生产围栏缺陷，而是测试判定器假阴性：`AttackCase` 是持有 `Runnable` 的 record，原断言使用 `attack::run`，只调用了 record accessor、返回 `Runnable`，没有执行其中的攻击。
- 判定器已改为 `() -> attack.run().run()`；并使用独立 JDBC 连接执行真实 SQL，断言异常链中的 SQLSTATE 与错误标识。
- 未修改 V001—V011、contracts、frontend 或 payload；未运行全仓 Maven。

## Fail-Closed 与创建门

- 静默旁路／生产 none-stub 残留：`0／0`。
- `LocalV1S2BQueryCoordinator`、`LocalV1S3ADeletionPreviewCoordinator` 均显式要求非空 `DeletionFencePort`。
- `JooqMemoryReadAdapter` 仅保留 `DSLContext` 构造器；列表过滤继续使用数据库 `NOT EXISTS`。
- `JooqDeletionFenceAdapter` 在发 SQL 前拒绝 null／blank 字段、null／空 drafts；空批次不再静默成功。
- 创建约束攻击：`8/8`：closure member、AFFECTED disposition、Decision kind、target kind、target id、target revision、重复 fence、批量中途失败回滚全部通过。

## 六类防复活矩阵

| 攻击面 | executed | passed | 结果 |
|---|---:|---:|---|
| MemoryRecord 同 ID UPDATE／重建 | 2 | 2 | REJECTED／REJECTED |
| MemoryRevision 所属 Memory／精确 revision | 2 | 2 | REJECTED／REJECTED |
| MemoryRelation from-memory／from-revision／to-revision／to-anchor | 4 | 4 | REJECTED／REJECTED／REJECTED／REJECTED |
| SourceAnchor 同 ID | 1 | 1 | REJECTED |
| SourceUnit 旧 ID／新 ID | 2 | 2 | REJECTED／PASS |
| SourcePayload 同 ID／指向 fenced SourceUnit | 2 | 2 | REJECTED／REJECTED |
| 精确性负例：kind/revision 不同不误伤 | 1 | 1 | PASS |
| 合计攻击（不含合法新 UUID 与精确性负例） | 12 | 12 | 0 failed |

工单口径按真实攻击计数为 `12/12/0`；SourceUnit 新 UUID 合法分支另计 `1/1`。所有拒绝均稳定断言 SQLSTATE `23514` 且消息包含 `HDM012_DELETION_FENCED`。攻击前后业务行集合、current pointer 与 payload 文件 hash 集合均一致。

为排除测试事务或连接可见性假象，测试还验证：围栏在独立 JDBC 连接中可见、V012 trigger 为 enabled、会话 `replication_role=origin`、正式 trigger/function 定义包含 UPDATE 与 OLD 身份检查。

## 离线 targeted 复跑

四个集合均使用本机 JDK 25 与 Maven `-o` 执行：

| 集合 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| LocalV1S3B1DeletionFenceTest | 5 | 0 | 0 | 0 |
| LocalV1S2BMemoryQueryTest | 10 | 0 | 0 | 0 |
| LocalV1S3ADeletionPreviewTest | 8 | 0 | 0 | 0 |
| DatabaseSliceBContractTest | 95 | 0 | 0 | 0 |
| 合计 | 118 | 0 | 0 | 0 |

Surefire XML 四套计数与上表一致。全仓 Maven：`NOT_RUN`。

## 完整性与执行边界

- 首轮 metadata 请求：`RECORDED_NETWORK_GUARD_DEVIATION`（历史事实保留）。
- R1 联网／下载／缓存变化：`否／否／0`；本轮所有 Maven 命令均显式 `-o`。
- V001—V011／generated／contracts／frontend：`MATCH/MATCH/MATCH/MATCH`。generated 的 S3B1 变更由 DatabaseSliceB 三向生成检查覆盖。
- 业务行／指针／payload 文件：`MATCH/MATCH/MATCH`。
- 真实 Git index：`5493a9441c5948c05416a887dfa196ae1ec22666`，测试前后未变。
- 工作区：modified tracked=`15`、untracked files=`8`、staged=`0`、越界=`0`。
- `git diff --check`：PASS（仅 core.autocrlf 的工作副本提示）。
- Git 写操作：否。

报告路径：`D:\myproject\hide-nest\reports\LocalV1-S3B1-执行报告.md`
