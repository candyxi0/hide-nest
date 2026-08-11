# LocalV1-S1-执行报告（返工 R1）

## 返工状态

**LOCAL_V1_S1_R1_READY_FOR_HIDE_REVIEW**

## 基线

- HEAD: `caf7d327e57960d2eb8b9d0eb818c95b9591aca5`（未变）
- 分支: `main`
- 返工方式: 在未提交 S1 候选上定向修正，无 reset/restore/checkout/clean

## 缺陷修正清单

| ID | 缺陷 | 修正 | 状态 |
|----|------|------|------|
| R1-01 | 未选闲聊写入 source_unit | 只接收 selectedEvidenceMessages；只为其创建 SourceUnit；canary 留在请求外 | PASS |
| R1-02 | confirm 接收可篡改的正文/类型/视角/anchor | ConfirmRequest 删除 bodyText/memoryType/perspectiveActorId/evidenceAnchorIds/decisionIds；从 ProposalRevision 派生 | PASS |
| R1-03 | Review-Source 无确定性绑定 | Source.externalRef="review:"+reviewSessionId；Evidence port 新增 findSourceByExternalRef + findSourceAnchorsBySourceId；confirm 从绑定派生 anchor | PASS |
| R1-04 | 随机新建"小林"破坏身份连续性；ReviewMember anyMatch | members.size()==1 精确匹配；USER_CONFIRM actor=ProposalRevision.perspectiveActorId；HIDE_SELECT 用独立 hide 系统 actor | PASS |
| R1-05 | replay 返回 null/空集合/伪造成功 | prepare replay 恢复原 HIDE_SELECT ID；confirm replay 重新核对 Decision、Review、Source 与完整 relation 集合，制品缺失时 fail closed | PASS |
| R1-06 | EVIDENCED_BY 只检查字符串类型 | 增加 toAnchorId null 拒绝、toRevisionId non-null 拒绝、perspective actor 匹配、重复 anchor 拒绝 | PASS |
| R1-07 | 测试标题假覆盖 | 修正测试语义并补充缺失证据关系的重放攻击，共 19 个针对性测试 | PASS |

## 关键验证结果

### 最小证据输入／SourceUnit

**PASS** — prepare 仅接收 2 条 evidence messages；SourceUnit 精确为 2 条（无无关消息）

### ReviewMember 精确集合／Review-Source 绑定

**PASS／PASS** — members.size()==1 且唯一成员 proposalRevisionId 精确匹配；Source.externalRef="review:"+reviewSessionId 确定性绑定

### confirm 权威字段派生／随机小林残留

**PASS／0** — bodyText/memoryType/perspectiveActorId/evidenceAnchorIds 全部从 ProposalRevision 或 Source 绑定派生；USER_CONFIRM Decision.actorId == ProposalRevision.perspectiveActorId（身份连续）

### EVIDENCED_BY 集合／跨来源／重复目标

**PASS／PREVENTED_BY_DERIVATION／REJECTED** — confirm 不接收 anchor；只从 Review 绑定 Source 派生集合。4 类无效 RelationSpec（SUPPORTS 类型、null anchor、revision target、错误 perspective）全部 CANONICAL_COMMIT_FAILED；重复 anchor 拒绝

### prepare／confirm replay

**EXACT／EXACT** — prepare replay 还原并核对 HIDE_SELECT decision ID；confirm replay 返回相同 memoryId/currentRevisionId/revisionNo/evidenceCount，并在任一证据关系缺失时 fail closed

### 未选闲聊 canary（含 source_unit）

**0** — canary 不在 prepare request 中；全业务 schema 所有文本/JSON 列扫描 0 命中（含 evidence.source_unit、source_anchor_unit、memory_revision、outbox_event.payload_manifest、idempotency_receipt.response_manifest、change_event.detail_manifest）

## 针对性测试

```
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
```

| # | 测试 | 覆盖 |
|---|------|------|
| 1 | prepareCreatesReviewButNoMemory | 正向：prepare 后 Memory=0，SourceUnit=2 |
| 2 | canaryZeroHitsAllTables | R1-01：全业务表扫描（含 source_unit） |
| 3 | confirmCreatesExactlyOneMemory | 正向：confirm 后 Memory=1，revisionNo=1 |
| 4 | rejectCreatesNoMemory | 正向：reject 无 Memory 创建 |
| 5 | prepareReplayExactMatch | R1-05：prepare replay 字段逐项相等 |
| 6 | confirmReplayExactMatch | R1-05：confirm replay 精确相等 |
| 7 | confirmReplayFailsClosedWhenEvidenceRelationIsMissing | R1-05：证据关系缺失时重放失败关闭 |
| 8 | idempotencyKeyReusedRejected | 正向：同键异值拒绝 |
| 9 | staleCompletedReviewRejected | R1-07.3：COMPLETED review 再次确认拒绝 |
| 10 | nonexistentReviewSessionRejected | R1-07.3：不存在 review 拒绝 |
| 11 | doubleRejectRejected | R1-07.3：CANCELLED review 再次拒绝 |
| 12 | wrongProposalRevisionRejected | R1-04：错误成员（非 ReviewMember 的 proposal）拒绝 |
| 13 | userConfirmActorMatchesPerspectiveActor | R1-04：USER_CONFIRM actor = 既有 actor |
| 14 | confirmDerivesContentFromProposalRevision | R1-02：confirm 从 ProposalRevision 派生，不可篡改 |
| 15 | evidencedByRejectsInvalidTargets | R1-06：4 类无效 RelationSpec 全部拒绝 |
| 16 | evidencedByDuplicateAnchorRejected | R1-06：重复 anchor 拒绝 |
| 17 | endToEndRollbackPreservesNothing | R1-07.9：末端故障全回滚 |
| 18 | v007CreateWithNullTargetSucceeds | V007：CREATE+null target 通过 |
| 19 | concurrentConfirmSerializes | 并发：幂等序列化 |

## 全仓 Maven offline

**PASS** — 所有模块 0 失败、0 回归

| 模块 | 测试数 | 结果 |
|------|--------|------|
| BackoffCalculatorTest | 24 | PASS |
| OutboxWorkerCoordinatorTest | 16 | PASS |
| DatabaseSliceBContractTest | 95 | PASS |
| DatabaseSliceC2AEvidenceMemoryAdapterTest | 14 | PASS |
| DatabaseSliceC2BRuntimeAdapterTest | 14 | PASS |
| DatabaseSliceD1OutboxMechanicsTest | 22 | PASS |
| LocalV1S1WindowCloseTest | 19 | PASS |
| SliceCCoordinatorTest | 15 | PASS |
| contracts (9 classes) | 37 | PASS |
| OutboxWorkerAssemblyTest | 5 | PASS |
| architecture-tests (3 classes) | 24 | PASS |
| **合计** | **285** | **ALL PASS** |

## V001—V010／generated／正式契约

- **MATCH**: V001—V010 migration 无修改
- **MATCH**: jOOQ generated tree 无修改
- **MATCH**: 正式 OpenAPI／事件 schema 无修改

## 真实 Git index

- modified: 6（CanonicalPublishCoordinator.java、JooqEvidenceReferenceAdapter.java、JooqMemoryGovernanceAdapter.java、SliceCCoordinatorTest.java、EvidenceReferencePort.java、MemoryGovernancePort.java）
- untracked: 9（LocalV1S1Exception.java、LocalV1S1WindowCloseCoordinator.java、LocalV1S1ConfirmRequest.java、LocalV1S1ConfirmResult.java、LocalV1S1PrepareRequest.java、LocalV1S1PrepareResult.java、LocalV1S1WindowCloseTest.java、ReviewMember.java、本报告）
- staged: 0
- 越界: 0（所有修改均在 modules/application、modules/database-adapter、modules/evidence、modules/memory 允许范围内）

## 工作区

```
tracked=6、untracked=9、staged=0、越界=0
联网／Git 写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-S1-执行报告.md
```
