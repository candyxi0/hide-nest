# Nest-V2-G5-S01-A 执行报告

实施状态：`G5_S01_A_READY_FOR_COMMANDER_REVIEW`（JDK25 恢复与 R1／补充授权修复后）

冻结基线：`7423c715d1de7ab81b26219e3cc56eb71be62e0f`

本回合只新增 Core↔Cognitive Worker v1 内部合同、离线夹具、单一 contracts 测试与本报告/Evidence。没有修改正式 OpenAPI、生成客户端、数据库 migration、生产模块、Worker runtime、部署配置或用户既有未跟踪文件。

## 修改路径

- `contracts/worker/worker-task-envelope-v1.schema.json`
- `contracts/worker/retrieval-result-v1.schema.json`
- `contracts/worker/formation-result-v1.schema.json`
- `contracts/worker/index-projection-result-v1.schema.json`
- `contracts/worker/fixture-manifest.json`
- `contracts/worker/fixtures/valid/**`
- `contracts/worker/fixtures/invalid/**`
- `modules/contracts/src/test/java/io/github/candyxi0/hidenest/contracts/WorkerContractTest.java`
- `reports/Nest-V2-G5-S01-A-执行报告.md`
- `reports/Nest-V2-G5-S01-A-Evidence.json`

## 合同与反证

四份 schema 均为闭合对象；任务封套固定 `nest.worker.v1`，包含 task/world/kind、attempt/generation/deadline、预算、能力白名单和 output contract。Retrieval 结果把 semantic status 与 execution status 分开，候选只有 revision/world/generation/score/navigation，正文需经 Core resolve 后才进入 selected。Formation 只允许 CREATE/REVISE/SUPERSEDE，`NO_LONG_TERM_CHANGE`、`SOURCE_INCOMPLETE`、`FAILED`、`BUDGET_EXHAUSTED` 有互斥出口。Index Projection 只表达 refs、generation、manifest、幂等键和 watermark。

Node+Ajv 离线复核真实解析了 manifest 引用的 28 个 JSON fixture：28/28 与 expectedValid 一致；PowerShell `ConvertFrom-Json` 和 Node `JSON.parse` 解析 worker 目录下 33 个 JSON 文件均通过。覆盖了合法 NO_QUERY、SUFFICIENT、NO_SUPPORT、FAILED、三类 WRITE_SET、NO_LONG_TERM_CHANGE、SOURCE_INCOMPLETE、BUDGET_EXHAUSTED、投影 APPLIED/RETRY_WAIT，以及未知顶层/嵌套字段、缺 required、非法 kind/capability、generation/deadline/budget 边界、非法结果组合和 no-support 携带 selected 的反证。跨 world candidate 由 `WorkerContractTest` 的 fixture rule 检出；不能由 schema 单独证明的“候选相关性”标记为 `NOT_APPLICABLE`。

## 质量门

- 首次原命令 `mvnw.cmd -pl modules/contracts -am test -o` 退出码 `1`：JDK `17.0.1` 不支持 parent 固定的 `release 25`。临时 release=17 诊断也命中既有 `BubbleContractTest` 的 Java 21 `List.getFirst()`；未以此冒充通过。
- JDK25 恢复后原命令先因根路径期望错误退出码 `1`，63 tests 中 1 failure；R1 修正根路径后又因非法 deadline fixture 意外通过而退出码 `1`，63 tests 中 1 failure。
- 指挥官补充授权下，`WorkerContractTest` 对 Worker schema 启用 `formatAssertionsEnabled(true)`，不修改 schema、非法 fixture 或拒绝断言。最终原命令退出码 `0`：63 tests、0 failures、0 errors、0 skipped。
- 定向 `WorkerContractTest` 复验退出码 `0`：6 tests、0 failures、0 errors、0 skipped。
- `git diff --check`：通过，真实退出码 `0`。
- fixture manifest/pathSet 双向核对：MATCH，28 条 manifest fixture path 与 `fixtures/valid|invalid/**/*.json` 集合一致。
- staged=0；未执行任何 stage/commit/push。
- 允许路径越界=0；既有用户未跟踪文件保持不动。
- 正文/query/token/capability/秘密/完整向量/绝对路径泄漏：合同与 fixture 未写入真实用户材料、query、token、秘密、完整向量或宿主绝对路径；未覆盖语义均为 `NOT_APPLICABLE`。

## 停止事实

没有模型、Embedding、reranker、真实网络、Docker、部署或 Git 写操作；没有开始 S02。历史 JDK 阻塞及两次测试失败均已在上述质量门中记录；最终门已真实全绿，停在指挥官复核门。
