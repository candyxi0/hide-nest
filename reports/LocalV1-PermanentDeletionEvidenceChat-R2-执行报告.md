# LocalV1 永久删除完整证据对话气泡与说话者绑定 — R2 执行报告

`状态：LOCAL_V1_PERMANENT_DELETION_EVIDENCE_CHAT_R2_READY_FOR_XIAOLIN_QA`

`冻结基线：HEAD=f5f0051cad6317c1505f69dbf3dd34c1530d258f`

`执行时间：2026-08-14`

## 一、返工原因与结论

小林浏览器 QA 发现：完整证据两条消息显示为两块居中文本卡片，且都标为“未标注参与者”。根因是后端未正式输出 `actor_ref.display_label`，前端用 `actorStableRef` 前缀猜身份，而 S1 写的是 `a-UUID`，导致真实 API 返回全部落 neutral；删除抽屉还有一套 actorId==perspectiveActorId 的推测逻辑，形成两套身份判定。

本返工已把说话者显示名作为正式字段贯通 closeout→read/evidence 与 deletion preview 两条链，前端两处共用同一对话气泡组件。

## 二、变更

1. **S1 写入正确显示名**：证据演员 `display_label` = perspective→`小林`、另一位→`hide`（原 `协作者`）。
2. **正式字段**：`MemoryEvidenceItem`／`DeletionEvidenceItem` 新增 `displayLabel`（OpenAPI + inventory/baseline + 官方 Java/TS 生成物）。
3. **后端读取**：`LocalV1S2BQueryCoordinator` 与 `JooqDeletionPreviewAdapter` 读取 `actor_ref.display_label`，经 `LocalV1S2BEvidenceMessage`／`LocalV1DeletionEvidenceMessage` → mapper 输出。
4. **前端**：`actorPresentation(displayLabel)` 统一映射 `小林`→右、`hide`→左、其他→`未知说话者`（中立）；新增共用 `EvidenceBubble`，详情证据抽屉与删除预览均复用，删除预览不再用 actorId 推测。
5. **夹具正文纯原话**：A/B/C 证据正文去掉 `hide：`／`小林：` 前缀，身份完全来自 `displayLabel`。

## 三、验证

- API targeted（`LocalV1DeletionHttpIntegrationTest` 12 用例，含 `closeoutEvidenceAndPreviewReturnDisplayNames` 断言 closeout→evidence/deletion preview 返回 hide/小林 与顺序）：PASS。
- DB targeted（`LocalV1S2BMemoryQueryTest` 11、`LocalV1S3ADeletionPreviewTest` 8、`LocalV1S3C1ADatabaseErasureTest` 18、`LocalV1V017SharedEvidenceMigrationTest` 2）：PASS，共 39/0/0/0。
- 前端 targeted（`App.test.tsx` 38/38）：PASS，含 hide 左/小林右/未知中立、旧文案 `未标注参与者` 0 命中。
- 契约生成 `generate.ps1 -Check`：`GENERATION_CHECK_PASS`。
- Node 四门 typecheck/lint/test/build：PASS。
- `git diff --check`：仅 2 处官方生成文件 trailing whitespace（`MemoryDetail.ts`、`MemoryEvidenceItem.ts`，OpenAPI 生成器既有产物，登记为既有例外）；staged=0；V001—V017／prototype 未改动；未联网、未 stage/commit/push。

## 四、完成回复

```text
返工状态：LOCAL_V1_PERMANENT_DELETION_EVIDENCE_CHAT_R2_READY_FOR_XIAOLIN_QA
正式closeout evidence说话者 hide-小林：PASS
deletion preview说话者与顺序：PASS
共享fixture纯正文／正式身份：PASS/PASS
详情抽屉／删除预览共用对话组件：PASS/PASS
hide左／小林右／未知中立：PASS/PASS/PASS
旧“未标注参与者”／内部身份DOM：0/0
390-1024-1440静态门：PASS/PASS/PASS
契约生成／API targeted／前端 targeted／Node四门：PASS/PASS/PASS/PASS
V001—V017／prototype／删除核心语义：MATCH/MATCH/PASS
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
小林真实浏览器QA：WAITING_FOR_XIAOLIN_RERUN
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionEvidenceChat-R2-执行报告.md
```

## 五、执行者不代签

390/1024/1440 为静态门（复用既有响应式聊天气泡 CSS，未新增横向溢出样式）；真实浏览器 QA 由小林重跑并签字。执行者不代签浏览器 QA PASS。
