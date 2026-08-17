# LocalV1 CandidateSet HTTP 执行报告

执行日期：2026-08-17
基线 HEAD：`9eba5aff69a7c56a0ba34ad33131186317834b38`
执行结论：`LOCAL_V1_CANDIDATE_SET_HTTP_READY_FOR_HIDE_REVIEW`

## 实施结果

- 复用既有 `POST /v1/review-sessions/{reviewSessionId}/final-submissions`，新增 operation 为 0。
- CandidateSet 请求与响应均使用命名 schema，匿名 Object 残留为 0。
- path `reviewSessionId` 与请求规范哈希使用同一确定性派生；写入前错绑请求 fail closed。
- 三 CREATE、共享证据、mixed、all-rejected、empty 五类正式 HTTP 场景全部通过。
- accepted REVISE/SUPERSEDE 及 mixed CREATE/REVISE 均停在 `DECISIONS_COMMITTED`，不越界发布。
- bearer 门生效；CandidateSet 路径不要求、不读取、不泄漏 capability。
- B2 测试夹具 `seedMemory()` 通过既有 S1 `prepare -> confirm` 建立正式治理事实，不再直接 SQL 绕过治理触发器。
- accepted REVISE 与 mixed 响应中的 `memoryId`、`memoryRevisionId`、`revisionNo` 按契约严格省略。

## 契约与生成

- OpenAPI official generate、`-Check`、compatibility：PASS/PASS/PASS。
- formal spec 与 baseline：字节一致。
- inventory：API schema 53、shared enum 15、event schema 4、总计 72。
- `CandidateSetProjectionPhase` 冻结值集合：`REJECTED`、`DECISIONS_COMMITTED`、`CANONICAL_COMMITTED`、`INDEX_READY`。
- operation/failure code/event type：30/50/12。
- 合同 targeted：13/13 PASS；`EnumCompletenessTest`：2/2 PASS。

## 测试与质量门

- CandidateSet HTTP targeted：16/16/0/0/0。
- Node 四门：typecheck/lint/test/build 全部 PASS。
- hide 接管后的 R1 全仓 `mvnw.cmd -o clean verify`（JDK 25）：BUILD SUCCESS，13 个模块全部 SUCCESS。
- R1 全仓当前 Surefire：50 files，590 tests，0 failures，0 errors，1 skipped（真实 Embedding smoke 按环境门跳过）。
- database-adapter：371/0/0/1；contracts：46/0/0/0；API：63/0/0/0；worker：5/0/0/0；architecture：29/0/0/0。
- V001—V020、database generated、React/prototype、MCP：MATCH/MATCH/MATCH/MATCH。
- Testcontainers 残留：0；staged=0；HEAD 未变；未联网、未执行 Git 写操作。

## 首轮最终门偏差及裁定

首轮全仓门仅在既有 `LocalV1DeletionHttpIntegrationTest.sharedEvidenceFixtureDeleteFlow` 出现一次偶发 500；B2 自身 HTTP 16/16、contracts 46/46、database-adapter 371/371 均已通过。随后：

1. 独立复跑删除套件：12/12 PASS；
2. 不修改生产代码与删除测试；
3. 作为 hide 接管后的独立 R1 封口重跑一次全仓 clean verify：BUILD SUCCESS。

因此判定为既有测试环境偶发抖动，不构成 CandidateSet HTTP 回归，也未以隔离测试冒充全仓 PASS。

## 工作区与例外

- 工作区：tracked modified=12、untracked files=23、staged=0；任务路径无越界。
- 既有 QA 临时目录 `reports/local-v1-read-browser-qa/.playwright-artifacts/` 保留未动。
- `git diff --cached --check` 的命中精确限定于官方 OpenAPI Generator 输出的 15 个 TS model 文件，共 123 项：
  `CandidateAction.ts`、`CandidateAuthorKind.ts`、`CandidateDisposition.ts`、`CandidateOriginKind.ts`、
  `CandidateSetAnchorUnit.ts`、`CandidateSetCandidate.ts`、`CandidateSetEvidenceAnchor.ts`、
  `CandidateSetEvidenceMessage.ts`、`CandidateSetEvidencePool.ts`、`CandidateSetFinalConfirmation.ts`、
  `CandidateSetProjectionItem.ts`、`CandidateSetProjectionPhase.ts`、`CandidateSetSubmissionPhase.ts`、
  `CandidateSetSubmissionResponse.ts`、`ReviewFinalSubmissionRequest.ts`。
- 上述内容均由官方生成与 `-Check` 门验证，未手改生成物；排除这 15 个精确文件后，其余 staged 路径 whitespace PASS。

## 完成回复

```text
实施状态：LOCAL_V1_CANDIDATE_SET_HTTP_READY_FOR_HIDE_REVIEW
复用submitReviewFinal／新增operation：PASS/0
命名schema／匿名Object残留：PASS/0
path reviewSession确定性绑定／写前错绑拒绝：PASS/PASS
三CREATE／共享证据／mixed／all-rejected／empty：PASS/PASS/PASS/PASS/PASS
accepted REVISE-SUPERSEDE／mixed CREATE-REVISE：DECISIONS_COMMITTED/DECISIONS_COMMITTED
同值重放／异值冲突／并发：EXACT/REJECTED/ONE_FACT_EACH
单条向量失败／恢复补投影：ISOLATED/PASS
bearer门／capability要求-读取-泄漏：PASS/0-0-0
CandidateSet HTTP targeted／OpenAPI／Maven offline／Node四门：PASS/PASS/PASS/PASS
operation／failure code／event type：30/50/12
V001—V020／database generated／React-prototype／MCP：MATCH/MATCH/MATCH/MATCH
正文-hideReason-query-完整向量-path-token-capability-secret-主机地址泄漏：0-0-0-0-0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
Docker新增containers-volumes-networks／payload／遗留进程：0-0-0/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-CandidateSetHTTP-执行报告.md
```

完成即停：未开始 Task36B3，未 stage/commit/push。
