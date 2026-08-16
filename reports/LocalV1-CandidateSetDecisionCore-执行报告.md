# Local V1 CandidateSet 批次治理与共享证据核心 — 执行报告（R2A 末端收口）

状态：`LOCAL_V1_CANDIDATE_SET_DECISION_CORE_R2A_READY_FOR_LOCAL_COMMIT`

冻结父提交：`2ebfc406223da06fdaca3234b44d83540c0ee31d`

本报告为 Task36A 成果的 R2A 末端收口：在 R2 已通过的证据闭合与映射序号封口之上，仅关闭 hide 末端复核发现的两个测试/数据模型收口问题（AnchorSpec accessor 可变内部列表、unit ordinal 反证非单因子）。不重做任何业务逻辑、V020、事务、生成物或全仓门。

## 完成回复格式

```text
末端收口状态：LOCAL_V1_CANDIDATE_SET_DECISION_CORE_R2A_READY_FOR_LOCAL_COMMIT
AnchorSpec 原列表修改／accessor修改：IMMUNE/REJECTED
null unit稳定分类：REQUEST_SCHEMA_INVALID
ordinal duplicate-gap-descending单因子反证：PASS/PASS/PASS
CandidateSet targeted／diff-whitespace：PASS/PASS
本轮全仓Maven：NOT_RUN_REUSE_R2_TWO_PASS
生产业务逻辑／V020／generated修改：0/0/0
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
```

## 两个收口问题关闭

- **问题一（AnchorSpec accessor 暴露可变内部列表）** `AnchorSpec.units` 防御性复制由 `new ArrayList<>(units)` 改为 `Collections.unmodifiableList(new ArrayList<>(units))`：保留 null 元素（null unit 仍稳定由 `validateStructure` 映射为 `REQUEST_SCHEMA_INVALID`，非构造期 NPE），同时 `units()` 返回不可修改列表，原始输入列表构造后被修改不影响 AnchorSpec，不新增自定义 accessor、不改变合法请求 hash。
- **问题二（unit ordinal 反证非单因子）** ordinal duplicate/gap/descending 三个攻击各改用两个不同 `sourceUnitId`，evidence pool 同时含两条对应合法 EvidenceMessage；除被测 ordinal 外，actor/hash/offset/消息引用全部合法；duplicate-source-unit 保留为独立攻击用例。

## 新增 mutation judge

新增 `anchorSpecDefensiveCopyMutationJudge`：构造 AnchorSpec 后修改原始 mutable units list 时 request hash 不变；对 `anchor.units()` 执行 `clear/add` 明确抛 `UnsupportedOperationException`；null unit 仍得到 `REQUEST_SCHEMA_INVALID` 而非构造期 NPE。

## 验证结果

- CandidateSet targeted **29/29 PASS**（R2 28 项 + 新增 mutation judge）。
- `git diff --check` PASS。
- 本轮按工单要求**不重复运行全仓 Maven clean verify**，沿用刚刚两次 exit 0 的 R2 全仓证据（`mvnw.cmd -o clean verify` 全模块 BUILD SUCCESS；其中 `LocalV1DeletionHttpIntegrationTest` 首轮偶发 500 已证为既有无关于本改动的 flaky，隔离 12/12 PASS、重跑全绿）。
- 生产业务逻辑 / V020 / jOOQ generated 本轮改动：0/0/0（仅改 `LocalV1CandidateSetRequest.java` 与测试/报告）。
- staged=0；HEAD=冻结父提交 `2ebfc406…`；无联网、无 git 写操作。

## 边界说明（延续 R2 披露）

`LocalV1CandidateSetRequest.java` 的 `AnchorSpec.units` 防御性复制属于 R1-05「request 及嵌套 record 防御性复制」的延续，是满足 null-unit → `REQUEST_SCHEMA_INVALID` 与 accessor 不可变的最小必要改动。

## 停止条件核对

- 未削弱任何既有治理门；未伪造 PASS。
- 不开始 Task36B，不 stage/commit/push。
