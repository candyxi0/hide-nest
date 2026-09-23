# Nest V2 G5-S04-A0 执行报告

状态：`G5_S04_A0_IMPLEMENTATION_ACCEPTED_AFTER_R1`。基线 `main` / `8b47471f98474227b928c0d2e52e3c988ff97678`；开工时 staged=0，index tree=`48e58a9a21455cfc4551a7ab71ec85eb77c545fb`。施工回执时未 stage、commit、push、部署或进入 S04-A1。

## 实施

- 同一 `nest.worker.v1` INDEX_PROJECTION task 绑定一条正式 Outbox `eventRef`、目标 `projectionGeneration`、材料 SHA-256 摘要、目标 schema/embedding manifest 和 Core 提供的受控规范材料。attempt `generation` 独立。投影能力仅 `PROJECT_INDEX`。
- 材料包含 CREATE／REVISE／SUPERSEDE 动作、本次正式 Record/revision、正文和必要 context、显式关系及接替 refs；可选最多 8 条 Core 提供的旧 revision 材料。Schema 拒绝 Evidence `exact_text`、未知字段及越界正文。
- APPLIED／RETRY_WAIT／FAILED 均回显事件身份、代次、摘要、manifest。APPLIED 才有 `appliedRevisionRef`；失败码组合闭合。移除旧结果的 `watermark`、`idempotencyKey` 和批量 `revisionRefs`。README 写明摘要算法、单事件持久成功语义和 Core 运行复验责任。
- `WorkerContractTest` 对正反 fixtures 运行真实 JSON Schema，并对各条 shape-valid 跨消息错绑和材料摘要变更做配对反证。

## 验证与首次失败

| 检查 | 首次退出码 | 修正后退出码 |
| --- | ---: | ---: |
| Fixture 生成入口（本机无 `python`，改用 `py -3`） | 1 | 0 |
| Worker 定向测试（14 项） | 1 | 0 |
| 完整 contracts reactor 测试（71 项） | — | 0 |
| Spotless 定向格式检查：首次 glob 参数被当正则 | 1 | 0（正则、定向 apply 后） |
| Spotless 正则定向检查：首次发现新增 Java 格式问题 | 1 | 0 |
| Node JSON.parse / PowerShell ConvertFrom-Json / 严格重复 key（36 文件） | — | 0 / 0 / 0 |
| manifest 与真实 fixture pathSet 双向一致（78 总数、32 INDEX） | — | 0 |
| git diff --check / Git taskPathSet 与 Evidence 双向一致 | — | 0 / 0 |

首次 Worker 测试失败是 manifest 残留已删除的旧负例 `index-negative-generation.json`；清除残留后重跑。随后发现新负例沿用旧文件名并被清理步骤误删，改名为 `index-bad-projection-generation.json` 补回，再次运行定向与完整套件均通过。Spotless 首次参数使用 glob 导致正则解析错误，改为正则后发现测试文件格式差异，定向格式化并复验。均未放宽 Schema 或删除有效负例来过门。

## Fixture pathSet

- `contracts/worker/fixtures/invalid/index-applied-failure-code.json`
- `contracts/worker/fixtures/invalid/index-applied-no-revision.json`
- `contracts/worker/fixtures/invalid/index-bad-digest.json`
- `contracts/worker/fixtures/invalid/index-bad-manifest.json`
- `contracts/worker/fixtures/invalid/index-bad-projection-generation.json`
- `contracts/worker/fixtures/invalid/index-extra-body.json`
- `contracts/worker/fixtures/invalid/index-failed-applied-revision.json`
- `contracts/worker/fixtures/invalid/index-failed-no-code.json`
- `contracts/worker/fixtures/invalid/index-missing-event.json`
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
- `contracts/worker/fixtures/valid/index-create-applied.json`
- `contracts/worker/fixtures/valid/index-create-task.json`
- `contracts/worker/fixtures/valid/index-failed.json`
- `contracts/worker/fixtures/valid/index-retry.json`
- `contracts/worker/fixtures/valid/index-revise-applied.json`
- `contracts/worker/fixtures/valid/index-revise-task.json`
- `contracts/worker/fixtures/valid/index-supersede-applied.json`
- `contracts/worker/fixtures/valid/index-supersede-task.json`

## Git 任务路径

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
- `reports/Nest-V2-G5-S04-A0-执行报告.md`

Git taskPathSet 与 [Evidence.json](Nest-V2-G5-S04-A0-Evidence.json) 相同；既有用户的 V1 数据库测试改动、`docs/planning/**` 和 `reports/local-v1-read-browser-qa/**` 未触碰。staged=0。

## 未运行

Ajv 2020 本机不可用：`NOT_RUN`，未联网安装。真实 Worker JSON、模型、PostgreSQL 投递、Embedding、候选质量、Core 最终 resolve、家庭部署和用户体验均 `NOT_RUN`。合同通过不表示 S04 运行功能通过。

## 指挥官独立复核

- 首轮发现合法 Core 100 条关系与投影材料 64 条上限不匹配，状态曾为 `NEEDS_R1`，没有提前入库。R1 将主／关联材料四处上限对齐，并以 65／100／101 真实 Schema 反证关闭缺口。
- 2026-09-24 指挥官在 JDK 25 下独立重跑完整 contracts 72/72（Worker 15/15），结合 R1 回执判本合同纵切 `IMPLEMENTATION_ACCEPTED`。真实投递、Worker、模型与用户体验仍未运行。
