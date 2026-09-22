# Nest-V2-G5-S01-R1 执行报告

实施状态：`G5_S01_R1_READY_FOR_COMMANDER_REVIEW`

冻结基线：`7423c715d1de7ab81b26219e3cc56eb71be62e0f`

原 R1 返工将 `contracts/worker/fixture-manifest.json` 中三个根级非法 fixture 的 `expectedInstancePath` 从 `/` 改为空字符串 `""`，与 NetworkNT `Error#getInstanceLocation()` 的根路径语义一致。原报告错误地在运行质量门前预写 PASS；现已按真实失败与最终结果更正。指挥官补充授权后，仅在 S01-A 原允许的 `WorkerContractTest.java` 中对 Worker schema 验证启用 NetworkNT 的 `formatAssertionsEnabled(true)`，保留非法 deadline fixture 与 `format` 拒绝断言；未改 schema 或共享测试辅助类。

## 质量门

- Java 25 前置门：`java -version` 与 `mvnw.cmd -version` 均为 `25.0.4`，PASS。
- 首次 JDK25 重跑：`mvnw.cmd -pl modules/contracts -am test -o` 退出码 `1`；63 tests、1 failure、0 errors；`task.unknown-top-level.invalid` 的根路径期望 `/` 不符。
- R1 三处根路径修正后的重跑：同一命令退出码 `1`；63 tests、1 failure、0 errors；`task.bad-deadline.invalid unexpectedly passed schema validation`。原因是 Draft 2020-12 的 `format` 默认未作为断言执行。
- 启用 Worker 专属格式断言后，静默诊断命令 `mvnw.cmd -q -pl modules/contracts -am test -o` 退出码 `0`；工单原命令 `mvnw.cmd -pl modules/contracts -am test -o` 最终退出码 `0`，63 tests、0 failures、0 errors、0 skipped。
- 定向命令 `mvnw.cmd -q -pl modules/contracts -am -Dtest=WorkerContractTest -Dsurefire.failIfNoSpecifiedTests=false test -o` 退出码 `0`；Surefire 报告显示 WorkerContractTest 6 tests、0 failures、0 errors。
- 三个根级 fixture 均在完整通过的 manifest validator 中验证；manifest/pathSet 双向 MATCH，28/28。
- `git diff --check` 退出码 `0`；HEAD 为冻结基线；staged=0；允许路径越界=0。

无真实模型、网络、Docker 或部署；未开始 S02；未 stage、commit、push。
