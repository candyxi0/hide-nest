# Local V1 S3B2A 执行报告

实施范围：仅数据库确认半片。未实现确认协调器、capability、ChangeEvent/outbox/receipt、DeletionRun 或物理删除。

## 结果

- V013 空库／V012→V013 升级／重复迁移：13／1／0。
- snapshot 行锁／字段完整／ordinal 稳定排序：PASS／PASS／PASS。
- 合法 Decision＋完整 fences＋确认同事务提交：PASS。
- missing、extra、wrong Decision、AFFECTED fence、无根 MEMORY：全部拒绝或保持 UNFENCED；V012 已有 fence insert 门的场景保留其稳定拒绝。
- stale preview revision、manifest hash、root revision、policy revision、expired：全部拒绝或 CAS 返回 0；无新增确认事实。
- CONFIRMED 再确认、回退、同状态改写、确认后 member/fence 扩张、closure DELETE：全部拒绝。
- Decision 后、部分 fence 后、closure 更新后的失败注入：事务回滚，无半提交。
- snapshot byte[] 与 members：防御性复制、不可修改；closure row 使用 `FOR UPDATE`。
- hide 验收补强：逐项执行 Decision kind／target kind／target id／revision 错绑、六类 stale CAS、三处事务失败注入，以及 AFFECTED 保留但不围栏；新增 3 项真实 PostgreSQL 测试后全部通过。

## Maven 定向证据

- `LocalV1S3B2ADeletionConfirmationTest`：最新验收轮 10 tests，0 failures，0 errors，0 skipped。
- `DatabaseSliceBContractTest`：95 tests，0 failures，0 errors，0 skipped。
- `LocalV1S3B1DeletionFenceTest`：5 tests，0 failures，0 errors，0 skipped。
- 三个定向套件累计：110 tests，0 failures，0 errors，0 skipped；其中后两套沿用实施轮结果，hide 验收轮未重复运行。
- 全仓 Maven：NOT_RUN。
- 所有 Maven 命令显式使用 `-o`；未执行联网或依赖下载命令。

## 变更边界

- V001—V012：未修改；仅新增 V013。
- generated：仅同步 deletion_closure 的 V013 字段、FK、索引与 checks。
- contracts/frontend/PayloadStore/payload 文件：未修改。
- 未新增 failure code、event type、依赖、runner 或 Evidence JSON。
- Git index：起始 clean，结束 staged=0；未执行 stage、commit、push、reset、restore、checkout、clean。
- 工作区 tracked modified=6、untracked=5、staged=0；越界路径=0。
- Docker：未新增遗留 container、volume、network；已有容器与既有资源未删除。
- 报告不包含数据库正文、payload、SQL 异常堆栈、JDBC URL 或 secret。

## 交付文件

- `modules/database-adapter/src/main/resources/db/migration/V013__deletion_closure_confirmation.sql`
- `modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/port/DeletionConfirmationPort.java`
- `modules/database-adapter/src/main/java/io/github/candyxi0/hidenest/database/adapter/JooqDeletionConfirmationAdapter.java`
- `modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1S3B2ADeletionConfirmationTest.java`

本轮 Maven 本地仓库使用离线模式；未观察到下载动作或新增缓存写入。由于开始前未将 metadata 集合快照落盘，本报告不宣称该项有可复核的起止 hash=0 证据。
