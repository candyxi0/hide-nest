# Nest V2 G5-S04-A0-R1 执行报告

状态：`G5_S04_A0_R1_IMPLEMENTATION_ACCEPTED`。施工回执时 A0 仍是 `NEEDS_R1`、未入库；本报告的返工事实由指挥官独立复核后验收。基线 `main` / `8b47471f98474227b928c0d2e52e3c988ff97678`，施工回执时 staged=0，index tree=`48e58a9a21455cfc4551a7ab71ec85eb77c545fb`。

## 修正

- Core `CreatePublication` 对一项 revision 的关系列表允许最多 100 条。将投影 task 的主材料和 `relatedRevisionMaterials` 中 SUPPORT、COUNTER 四个数组的各自 `maxItems` 从 64 调至 100；不裁剪正式关系，也未改 Core 上限。
- README 明确每个数组的静态上限与每条 revision 的 SUPPORT＋COUNTER 合计不超过 100 是两种约束；合计及关系确属正式 revision 须由 Core 生成任务时核验。
- 新增真实 NetworkNT Schema 边界测试：主材料与关联旧 revision，分别对 SUPPORT／COUNTER 输入 65、100、101 条唯一正式 ref 形状，并更新正确材料摘要。8 个 65／100 样例通过，4 个 101 样例按 `maxItems` 拒绝。另以 60＋41 证明静态 Schema 不能证明合计限制。旧 64 实现会被 65 条正例杀死。
- A0 其他合同、fixtures、manifest、报告与 Evidence 保持原样；R1 新增仅两份回执。

## 真实检查与退出码

| 检查 | 首次退出码 | 修复后退出码 |
| --- | ---: | ---: |
| 65／100／101 定向反证（1 项，12 个边界组合） | — | 0 |
| Worker 合同测试（15 项） | — | 0 |
| `mvnw.cmd -pl modules/contracts -am test -o`（72 项） | — | 0 |
| WorkerContractTest 定向 Spotless | 1（新增测试排版） | 0（定向 apply 后） |
| Node JSON.parse / PowerShell ConvertFrom-Json / 严格重复 key（84 文件） | — | 0 / 0 / 0 |
| fixture/manifest pathSet（78 条，INDEX 32 条） | — | 0 |
| `git diff --check` / Git taskPathSet 与 Evidence 双向一致 | 0 / 1（Git 中文路径转义） | 0 / 0 |

首次格式检查指出新增测试的排版；定向格式化后重跑通过。首次 Git 路径核对脚本将 Git 默认转义的中文报告名当成原始路径，退出码 1；改用 `-z` 原始 UTF-8 路径输出重跑，退出码 0，44 条双向一致。A0 施工首次失败及自修记录完整保留在原 [A0 执行报告](Nest-V2-G5-S04-A0-执行报告.md)，本轮未改写。JDK 25.0.4，Ajv 2020 本机不可用，未联网安装。

## 路径与边界

R1 新增修改路径：

- `contracts/worker/worker-task-envelope-v1.schema.json`
- `contracts/worker/README.md`
- `modules/contracts/src/test/java/io/github/candyxi0/hidenest/contracts/WorkerContractTest.java`
- `reports/Nest-V2-G5-S04-A0-R1-执行报告.md`
- `reports/Nest-V2-G5-S04-A0-R1-Evidence.json`

合并 A0＋R1 的 Git taskPathSet 共 44 条，与 [R1 Evidence](Nest-V2-G5-S04-A0-R1-Evidence.json) 双向一致：

- `contracts/worker/README.md`
- `contracts/worker/fixture-manifest.json`
- `contracts/worker/fixtures/invalid/index-applied-failure-code.json`
- `contracts/worker/fixtures/invalid/index-applied-no-revision.json`
- `contracts/worker/fixtures/invalid/index-bad-digest.json`
- `contracts/worker/fixtures/invalid/index-bad-manifest.json`
- `contracts/worker/fixtures/invalid/index-bad-projection-generation.json`
- `contracts/worker/fixtures/invalid/index-extra-body.json`
- `contracts/worker/fixtures/invalid/index-failed-applied-revision.json`
- `contracts/worker/fixtures/invalid/index-failed-no-code.json`
- `contracts/worker/fixtures/invalid/index-missing-event.json`
- `contracts/worker/fixtures/invalid/index-negative-generation.json`
- `contracts/worker/fixtures/invalid/index-old-watermark.json`
- `contracts/worker/fixtures/invalid/index-retry-applied-revision.json`
- `contracts/worker/fixtures/invalid/index-retry-wrong-code.json`
- `contracts/worker/fixtures/invalid/index-task-bad-digest.json`
- `contracts/worker/fixtures/invalid/index-task-bad-event.json`
- `contracts/worker/fixtures/invalid/index-task-content-overflow.json`
- `contracts/worker/fixtures/invalid/index-task-create-predecessor.json`
- `contracts/worker/fixtures/invalid/index-task-extra-context.json`
- `contracts/worker/fixtures/invalid/index-task-material-unknown.json`
- `contracts/worker/fixtures/invalid/index-task-missing-event.json`
- `contracts/worker/fixtures/invalid/index-task-missing-material.json`
- `contracts/worker/fixtures/invalid/index-task-related-content-overflow.json`
- `contracts/worker/fixtures/invalid/index-task-related-evidence-leak.json`
- `contracts/worker/fixtures/invalid/index-task-revise-no-predecessor.json`
- `contracts/worker/fixtures/invalid/index-unknown-field.json`
- `contracts/worker/fixtures/valid/index-applied.json`
- `contracts/worker/fixtures/valid/index-create-applied.json`
- `contracts/worker/fixtures/valid/index-create-task.json`
- `contracts/worker/fixtures/valid/index-failed.json`
- `contracts/worker/fixtures/valid/index-retry.json`
- `contracts/worker/fixtures/valid/index-revise-applied.json`
- `contracts/worker/fixtures/valid/index-revise-task.json`
- `contracts/worker/fixtures/valid/index-supersede-applied.json`
- `contracts/worker/fixtures/valid/index-supersede-task.json`
- `contracts/worker/fixtures/valid/index-task.json`
- `contracts/worker/index-projection-result-v1.schema.json`
- `contracts/worker/worker-task-envelope-v1.schema.json`
- `modules/contracts/src/test/java/io/github/candyxi0/hidenest/contracts/WorkerContractTest.java`
- `reports/Nest-V2-G5-S04-A0-Evidence.json`
- `reports/Nest-V2-G5-S04-A0-R1-Evidence.json`
- `reports/Nest-V2-G5-S04-A0-R1-执行报告.md`
- `reports/Nest-V2-G5-S04-A0-执行报告.md`

已有用户的 V1 数据库测试、`docs/planning/**`、`reports/local-v1-read-browser-qa/**` 原样保留；无 stage、commit、push、部署，未进入 S04-A1。真实 Worker、投递、Embedding、模型和用户体验均 `NOT_RUN`。

## 指挥官独立复核

- 2026-09-24 核对主投影材料和关联旧 revision 四处关系数组上限、65／100／101 边界及 60＋41 合计仅运行时校验的说明；独立重跑 JDK 25 完整 contracts 72/72，通过。
- 合并 A0＋R1 的 44 条任务路径与 Evidence 双向一致，`git diff --check` 通过，暂存区为空。只接受离线合同实现；真实投递／Worker／Embedding／连续性体验仍为 `NOT_RUN`。
