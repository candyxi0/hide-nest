# Local V1 CandidateSet CREATE 投影 — 执行报告（R1A 末端收口）

状态：`LOCAL_V1_CANDIDATE_SET_CREATE_PROJECTION_R1A_READY_FOR_LOCAL_COMMIT`

冻结基线：`56e8a86dcce99777427f53682e8f5871a6fc4a37`

本工单为 Task36B1-R1A 末端收口：在 R1 全部成果（bodyHash 绑定、Memory/Revision/policy/receipt 字段复核、8 项 replay 攻击）之上，仅关闭唯一未关闭点——把 `verifyPublishReceipt` 中 `manifest.contains(...)` 的宽松判定替换为严格、闭合的 flat JSON object 判定。不回退、不重做、不扩大路径，不接 HTTP/MCP/React，不创建提交。

## 完成回复格式

```text
末端收口状态：LOCAL_V1_CANDIDATE_SET_CREATE_PROJECTION_R1A_READY_FOR_LOCAL_COMMIT
receipt manifest闭合键集合／严格类型／零额外键：PASS/PASS/PASS
correct UUID藏于无关字段攻击／wrong status-result-retryable：REJECTED/REJECTED
合法JSONB键序归一化：PASS
CandidateSet targeted／必要离线编译／diff-whitespace：PASS/PASS/PASS
本轮全仓Maven：NOT_RUN_REUSE_R1_PASS
生产业务修改／测试修改／报告修改：1/1/2
V001—V020／generated／contracts／React-prototype／MCP：MATCH/MATCH/MATCH/MATCH/MATCH
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-CandidateSetCreateProjection-执行报告.md
```

## R1A｜严格闭合 flat JSON receipt manifest 判定

唯一生产修改 `LocalV1CandidateSetCreateProjectionCoordinator.java`：

- 删除 `manifest.contains(revisionId.toString())` 与 `manifest.contains("urn:pink:response:canonical-publish")` 两个宽松判定。
- 新增 fail-closed 的专用 flat-manifest parser（`ManifestParser`，无 Jackson/JSON 依赖），并做严格闭合判定：
  - 键集合恰好 `{type,status,requestId,resultCategory,retryable}`（`object.size()==5`），零额外键、零重复键；
  - `type` == 字符串 `urn:pink:response:canonical-publish`；
  - `status` == JSON number 200（拒绝字符串 `"200"`、`200.0`、`2e2`）；
  - `requestId` == 实际 memoryRevisionId 的规范 UUID 字符串（不因正确 UUID 出现在无关字段而通过）；
  - `resultCategory` == 字符串 `SUCCEEDED`；
  - `retryable` == JSON boolean `false`（拒绝字符串 `"false"`）。
  - 拒绝嵌套对象/数组、错误 JSON 类型、未闭合结构、尾部多余内容；JSONB 的键顺序与无意义空白变化均被容忍（parser 逐 token 解析，不做整串比较）。
- 任一不满足返回 `PUBLISHED_MEMORY_VERIFICATION_FAILED`。

## R1A 单因子反证（新增）

在 `LocalV1CandidateSetCreateProjectionTest` 中保留 wrong revisionId 攻击，并新增（每项先合法投影→损坏→replay，断言稳定 `PUBLISHED_MEMORY_VERIFICATION_FAILED`、Memory/Revision 不增加、Embedding 调用零增量）：

1. `replayReceiptManifestWrongRequestIdWithNoteRejected`：requestId 改错，且新增 `note=<正确 revisionId>`，type 保持正确 → REJECTED。
2. `replayReceiptManifestExtraFieldRejected`：五字段正确但新增 `extra=true` → REJECTED。
3. `replayReceiptManifestWrongTypesRejected`：status 改字符串 `"200"`、retryable 改字符串 `"false"`、resultCategory 改错 → 全部 REJECTED。
4. `legalReceiptManifestKeyOrderNormalizedPasses`：合法 JSONB（PostgreSQL 归一化键序）→ PASS。

说明：`runtime.idempotency_receipt` 存在宽松 DB CHECK `idempotency_receipt_manifest_body_check`，会拒绝部分畸形 manifest（额外键 `note/extra`、字符串 `"200"`/`"false"` 等）注入。测试在 `tamperManifest` 内按 drop/restore 模式临时解除该 CHECK 与 `idempotency_receipt_immutable` 触发器后再注入，使判定确实由投影 parser（而非 DB CHECK）执行；恢复时以 `NOT VALID` 重加 CHECK，不改任何生产 migration。

## R1 成果（保留，摘要）

- R1-01：bodyHash 绑定进 requestHash/manifestHash（正文不入 hash），accepted CREATE 前置门收紧。
- R1-02：发布后精确复核 MemoryRecord policy/currentPolicyRevisionNo、MemoryRevision createdByDecisionId、canonical publish receipt 全字段。
- R1-03：8 项单因子 replay 攻击（pointer/policy/owner/receipt op-resource-hash-manifest/body/relation）+ 向量 model/bodyHash 双反证。
- REVISE/SUPERSEDE 边界：`ROUTED_TO_POST_LOCAL_V1_GOVERNANCE`。

## 验证结果（Surefire XML 动态计数）

- CandidateSet 投影 targeted：**26/26 PASS**（`LocalV1CandidateSetCreateProjectionTest`）。
- 必要离线编译：application/database-adapter 编译 PASS；targeted 离线 test PASS。
- 架构门（沿用 R1）：**29/29 PASS**。
- `git diff --check`：PASS。
- 本轮全仓 `clean verify`：`NOT_RUN_REUSE_R1_PASS`（R1 已 BUILD SUCCESS，本收口只改 1 生产 + 1 测试 + 2 报告，不重复消耗时间）。

## 允许修改范围核对

- 生产修改 1（`LocalV1CandidateSetCreateProjectionCoordinator.java`）、测试修改 1（`LocalV1CandidateSetCreateProjectionTest.java`）、报告 2。
- 未改动 V001—V020、jOOQ generated、Task36A 核心、`CanonicalPublishCoordinator`、`CanonicalRevisionCoordinator`、ports、OpenAPI/contracts、apps/api、MCP、React/prototype、pom、冻结 failure/event/operation 集合。

## 泄漏与残留门

- 正文/query/完整向量/path/token/capability/secret/家庭服务器地址泄漏：0。
- 真实 Git index：MATCH；staged=0；越界=0。
- Docker 新增 containers/volumes/networks：0（Testcontainers Ryuk 自清理）；payload 文件：0；遗留进程：0。
- 全程离线，无 git 写操作（未 stage/commit/push）。

## 停止条件核对

- 完成即停：不开始后续任务，不 stage/commit/push。
