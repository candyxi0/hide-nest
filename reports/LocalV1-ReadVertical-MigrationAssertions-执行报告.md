# Task30C｜Local V1 全仓迁移断言机械收口执行报告

## 结论

目标状态：`LOCAL_V1_READ_VERTICAL_MIGRATION_ASSERTIONS_READY_FOR_BROWSER_QA`

本轮为短、机械、受限返工。只修复 8 个既有数据库测试中过时的 Flyway 迁移数量断言，并用当前 V001—V015 真实执行结果完成全仓离线 Maven 门禁。Task30A/Task30B 既有 dirty 工作区原样保留，未还原、未覆盖、未格式化、未暂存。

8 个目标测试类全部通过（0 failure / 0 error / 0 skipped）；全仓离线 `mvnw -o clean verify` exit 0（403 tests，0/0/0）；V001—V015 15/15 哈希 MATCH；`git diff --check` PASS；越界新增差异 0；staged=0；无联网、无 Git 写操作。

## 冻结输入

- 基线 HEAD：`bedba3279cc9d8a9edf8931b767614d8cb3b3ec8`
- 工单 SHA-256：`81a742b56498c2823c964da3674e13d49337a2559d076540625811a299d97b23`
- JDK：`C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot`（Temurin 25.0.4+7）
- Maven：3.9.16（wrapper，离线 `-o`）
- 正式迁移集合：V001—V015，共 15 个文件
- 数据库：PostgreSQL 18.4（pgvector Testcontainers 固定镜像）

## 修复的 8 个断言（仅允许路径内）

| # | 测试文件 | 位置 | 修复前 → 修复后 |
|---|---------|------|-----------------|
| 1 | LocalV1S3ADeletionPreviewTest | migrationUpgradeAndRepeatAreExact | V010→V015 升级 `3` → `5`（局部变量 v11→v15），重复 `0` 不变 |
| 2 | LocalV1S3B2ADeletionConfirmationTest | migrationCounts | 起点 V012=12 不变；升级到 V015 `1`→`3`；history `13`→`15`；DisplayName 同步为 "V012 start, upgrade to V015, and repeat migration are 12/3/0" |
| 3 | DatabaseSliceC2AEvidenceMemoryAdapterTest | setUp | 空库 `11` → `15` |
| 4 | DatabaseSliceC2BRuntimeAdapterTest | setUp | 空库 `11` → `15` |
| 5 | DatabaseSliceD1OutboxMechanicsTest | setUp + v010ThreeMigrationPaths | setUp 空库 `11`→`15`；迁移路径测试空库 `11`→`15`、V010→V015 `1`→`5`、history `11`→`15`（两处）；DisplayName 同步为 "V015 three migration paths — empty 15, V10 upgrade 5, repeat 0" |
| 6 | LocalV1S1WindowCloseTest | setUp | 空库 `11` → `15` |
| 7 | LocalV1S2BMemoryQueryTest | setUp | 空库 `12` → `15` |
| 8 | SliceCCoordinatorTest | setUp | 空库 `11` → `15` |

未修改 V001—V015、生产 Java、API、契约、生成树、React 或 Node 文件；未删除/弱化任何迁移断言；未使用 `@Disabled`/exclude/吞异常。

## 针对性数据库测试

```
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot
mvnw -o -pl modules/database-adapter -am
  -Dtest=LocalV1S3ADeletionPreviewTest,LocalV1S3B2ADeletionConfirmationTest,DatabaseSliceC2AEvidenceMemoryAdapterTest,DatabaseSliceC2BRuntimeAdapterTest,DatabaseSliceD1OutboxMechanicsTest,LocalV1S1WindowCloseTest,LocalV1S2BMemoryQueryTest,SliceCCoordinatorTest
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**119 tests，0 failures，0 errors，0 skipped，BUILD SUCCESS（01:19）**。

## 全仓离线门禁

```
JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot
mvnw -o clean verify
```

结果：**BUILD SUCCESS，exit 0（02:58）**。模块全部 SUCCESS：evidence / payload-adapter / memory / runtime / security / application / database-adapter / contracts / api / worker / architecture-tests。

最新 Surefire XML 统计（33 个 TEST-*.xml）：

- tests：403
- failures：0
- errors：0
- skipped：0

## 迁移路径验证

- 空库当前执行：15（V001—V015）
- V010→V015 真实增量：5
- V012→V015 真实增量：3
- 重复执行：0
- 升级路径断言：PASS

V001—V015 15 个迁移文件哈希与起始记录逐条一致：**15/15 MATCH**。

## 机械收口检查

- 8 个目标测试类：8/0/0/0
- 全仓 Surefire：403/0/0/0
- `git diff --check`：PASS（exit 0，无 trailing whitespace / space-before-tab；仅含 core.autocrlf 的 LF→CRLF 提示性 warning）
- 越界新增差异：0（本次仅新增 8 个允许测试文件的修改；无生成源、生产代码、契约、前端差异）
- staged：0
- 联网：否；Git 写操作：否
- 遗留新增资源：无 Java/Maven/Docker 新增资源（`target/` 为 gitignore 构建目录）

## 工作区状态

- tracked 变更总数：24（Task30A/30B 既有 16 + 本任务 8 个测试文件）
- untracked 总数：24（Task30A/30B 既有 23 + 本报告 1）
- staged：0
