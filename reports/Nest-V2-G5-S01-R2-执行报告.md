# Nest-V2-G5-S01-R2 执行报告

实施状态：`G5_S01_R2_READY_FOR_COMMANDER_REVIEW`

冻结基线：`7423c715d1de7ab81b26219e3cc56eb71be62e0f`；JDK 与 Maven Java 均为 `25.0.4`；最终 staged=0。

## 修改范围

只修改 `contracts/worker/**` 中四份内部 schema、fixture manifest 与合成 fixtures，新增 `contracts/worker/README.md`；只修改单一 `WorkerContractTest.java`；新增本 R2 报告与 Evidence。精确 32 条路径列于 Evidence `changedPaths`。S01-A／R1 报告及既有失败历史未改；正式 OpenAPI、generated、共享测试辅助类、pom、migration、生产代码与冻结实验工作树未改。原有用户未跟踪文件保持原状。

## R2 语义对账

- R2-01 `PASS`：Retrieval `selected.type` 与 Formation `writeSet[*].type` 只接受 `EVENT`／`CLAIM`／`QUOTE`／`UNDERSTANDING`。四种类型均有合法 fixture；测试逐一将两类正例变异为 `FACT`／`DECISION`／`PREFERENCE`／`RELATION`／`EPISODE`，共十次真实 schema 拒绝。
- R2-02 `PASS`：一个 `UNDERSTANDING` 的 `WRITE_SET` item 在合法 fixture 中带两个不连续 locator 的 `sourceAnchors`，每个含独立 `sourceRef`、`sourceVersion`、`locator`。旧单数、空数组、缺版本、伪装为 Memory revision 的版本、缺 locator 均被 schema 拒绝。Core 对 world／版本／Anchor frame、说话身份、范围的实际核验留给 S03，Worker 自报不构成授权。
- R2-03 `PASS`：Retrieval 必带 `targetTurnRef`、非空 `inputVersion` 和至少一条有界真实 `CURRENT_USER_TURN.text`；可选附近消息／上层维护 Anchor。合成正例含当前材料，缺当前材料 fixture 与缺文本变异被拒绝。Formation 必带有界 `sourceWorkRef` 和 `READ_SOURCE` 能力，不再必带 chat `sessionRef`；缺工作引用 fixture 和缺读取能力变异被拒绝。来源绑定／解引用运行实现不在本轮。
- R2-04 `PASS`：`sourceExclusion` 为闭合来源身份、来源版本、不透明起止 cursor 对象；`deliveredRevisionRefs` 仅是已交付 Memory revision refs，旧 revision 字符串来源排除与重复已交付列表被拒绝。三类结果均必带 lease `generation`，Retrieval 额外回显目标回合与输入版本。测试对三类 task/result 正例配对，并证明错 generation、错目标回合或输入版本虽各自 schema 合法仍被配对校验拒绝；缺 generation 本身被 schema 拒绝。未实现正式 lease／交付围栏。
- R2-05 `PASS`：Formation 各出口的 `writeSet`、`checkedInputs`、`reason`、`failureCode` 和 executionStatus 必需／禁止关系闭合；失败出口不携半成品，`SOURCE_INCOMPLETE` 保持 `COMPLETED` 且无游标推进动作。Retrieval 失败／超时／取消／不可用不携 `selected` 或候选；完成出口无失败码。`selected.current` 与 `historical` 均必填且相反，正反例验证。`selected` 只代表 Worker→Core 选择，非主模型完整上下文贡献。

## 测试与失败账

历史 S01-A／R1 失败保留在各自报告：JDK17 无法编译 Java25（退出码 `1`）；JDK25 首轮根实例路径期望错误（退出码 `1`，63 tests 中 1 failure）；R1 修正后非法 deadline 未被格式断言拒绝（退出码 `1`，63 tests 中 1 failure）。R1 最终曾达到 63/63，本轮未改写那些历史。

本轮真实执行：

1. Worker 定向命令 `mvnw.cmd -q -pl modules/contracts -am -Dtest=WorkerContractTest -Dsurefire.failIfNoSpecifiedTests=false test -o` 首轮退出码 `1`：11 tests、1 failure。`task.missing-current-user.invalid` 已被 schema 拒绝，但 manifest 期望 `contains`；NetworkNT Draft 2020-12 的实际 keyword 是 `minContains`，路径 `/contextMaterials`。仅修正 keyword 期望，保留负例与拒绝断言。
2. 同一定向命令重跑退出码 `0`；加强多 Anchor／读取能力正反证后再次重跑退出码 `0`，WorkerContractTest `11 tests, 0 failures, 0 errors, 0 skipped`。
3. 原始全量命令 `mvnw.cmd -pl modules/contracts -am test -o` 在加强前后分别退出码 `0`；最终汇总 `68 tests, 0 failures, 0 errors, 0 skipped`。
4. 本地 Ajv `6.15.0` 离线交叉核对：最终 manifest 的 31 个正反 fixture 与 Java 预期结果差异 `0`。Ajv 6 并非 Draft 2020-12 的权威执行器；NetworkNT Java 测试是质量门，Ajv 仅独立交叉核对正反结果。fixture manifest 与实际 `valid|invalid` JSON pathSet 双向 `MATCH`，31／31。
5. `git diff --check` 退出码 `0`；对 38 个 Worker／测试未跟踪文本文件另查尾随空白，违规 `0`；Worker 目录 36 个 JSON 严格 UTF-8 解析且重复 key `0`。
6. HEAD 与冻结 SHA 一致，staged=0，允许路径越界=0。没有真实模型、网络下载、Docker、部署、stage、commit、push 或 S02。

不可由本离线合同证明的候选相关性、真实来源内容／版本、Core 批量 resolve、runtime lease／游标推进／正式写入、主模型最终贡献，均为 `NOT_APPLICABLE`，不伪报 PASS。

完成即停，等待指挥官复核。
