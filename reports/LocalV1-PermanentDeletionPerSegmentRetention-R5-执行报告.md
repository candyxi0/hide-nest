# LocalV1 永久删除逐段去留与共享记忆精确映射 — R5 执行报告

`状态：LOCAL_V1_PERMANENT_DELETION_PER_SEGMENT_RETENTION_R5_READY_FOR_XIAOLIN_QA`

`冻结基线：HEAD=f5f0051（在 R4/R4-R1 工作树成果上纠偏）`

`执行时间：2026-08-15`

## 一、QA 裁定与目标

小林真实浏览器 QA 唯一失败项：删除预览把 `sharedMemories` 作为全局提示，无法说明具体哪一段原文保留、哪一段清除。本轮把「共享记忆 ↔ 证据消息 ↔ anchor 段」建立正式、可验证的精确映射，前端逐段展示，禁止按数组位置／正文／段序号／“只有一个共享记忆”猜测。

## 二、正式契约

`DeletionEvidenceItem` 新增 required `sharedByMemoryIds: array of uuid`，语义：这条证据消息的原文在删除当前记忆后仍因这些其他规范记忆而保留；空数组表示没有其他记忆保留。保留 `DeletionPreviewResponse.sharedMemories` 作为授权后的记忆标题字典。同步 OpenAPI、baseline、inventory 与官方 Java/TS 生成物，生成链双次稳定。

> 工程注：OpenAPI 生成器 7.24.0 + 本仓 Spring Boot 4/Jackson 3.x（`tools.jackson`）下，`uniqueItems: true` 会让 Spring 生成器输出 `com.fasterxml.jackson.databind.annotation.JsonDeserialize`（2.x databind 注解，不在本仓 classpath）。为保持生成链稳定，`sharedByMemoryIds` 采用 `type: array`（生成 `List<UUID>`），唯一性、稳定排序与去重在应用边界 fail-closed 强校验（见第四节），语义等价于 `uniqueItems: true`。

## 三、后端精确映射

- `DeletionPreviewGraph.EvidenceUnit` 新增 `sharedByMemoryIds: List<UUID>`。
- `JooqDeletionPreviewAdapter` 由真实 EVIDENCED_BY / anchor-unit / payload 关系反推 `payloadId → 其他 memoryId 集合`，覆盖直接复用同一 anchor 与通过其他 anchor 复用同一 SourceUnit/Payload 两种共享，逐条填到 evidence，稳定排序、无重复。
- `LocalV1DeletionEvidenceMessage`、`LocalV1S3ADeletionPreviewCoordinator.readEvidence`、`LocalV1DeletionWriteController.toEvidenceItem` 逐层透传。
- `LocalV1S3ADeletionPreviewCoordinator.validateGraph` 新增 fail-closed：`sharedByMemoryIds` 有序且无重复、不得含 root memoryId、必须都在 `sharedMemories` 字典内、字典每条至少被一条 evidence 引用、且 `sharedPayloadIds`（RETAIN_SHARED）与 `sharedByMemoryIds` 非空性严格一致。违反即 `GRAPH_INVALID`，零半提交。
- 删除闭包、manifestHash、确认与执行算法不变；未新增 operation／failure code／event type。

## 四、前端展示

- `EvidenceConversation` 增加可选 `deletion` 删除影响投影，仅删除预览传入；普通详情抽屉保持纯只读气泡。
- 段级：共享段 `删除后会保留 · 另有 N 条记忆使用`（下方列该段 union 去重标题）；独占段 `删除后会清除 · 仅当前记忆使用`；混合段 `删除后部分保留`。
- 消息级：仅混合段在每条气泡旁标 `会保留`／`会清除`，保留消息旁列对应标题；全保留/全清除段不重复贴标签。
- 顶部汇总：`完整证据：X 段，共 Y 条消息 · 其中 A 段保留，B 段清除（，C 段部分保留）`，删除全局 `.delete-shared`／`.delete-aftermath` 含糊提示块。
- 不把 anchorId／memoryId／RETAIN_SHARED／DELETE_CANDIDATE／SOURCE_* 等内部值渲染进用户 DOM。

## 五、验证门

- OpenAPI generate check／compatibility：PASS／PASS（GENERATION_CHECK_PASS、baseline-vs-current=COMPATIBLE）。
- Java targeted（真实 PostgreSQL + Spring）：
  - `LocalV1S3ADeletionPreviewTest`：10 executed / 0 failures / 0 errors / 0 skipped（含直接 anchor 共享、间接 unit/payload 共享、sharedByMemoryIds 字典缺失/孤儿/root/重复攻击）。
  - `LocalV1DeletionHttpIntegrationTest`：12 / 0 / 0 / 0（含删除预览 `sharedByMemoryIds` 精确断言、删除 A 共享保留-独占清除、再删 B 最终清除）。
  - `LocalV1CloseoutWriteHttpIntegrationTest`：18 / 0 / 0 / 0（回归，未改动）。
- nest-console typecheck／lint／test／build：PASS/PASS/PASS/PASS（44 tests）。
- V001—V017／database generated／prototype／MCP 段语义：MATCH/MATCH/MATCH/MATCH（未改）。
- operation／failure code／event type：30/50/12（未改）。
- 正文-path-token-capability-secret 泄漏：0-0-0-0-0（既有泄漏扫描测试覆盖）。
- `git diff --check`：除已记录官方生成文件例外（`packages/api-client-ts/src/generated/models/MemoryDetail.ts`、`MemoryEvidenceItem.ts` 的生成器 JSDoc 尾部空格，属此前未提交生成物）外无 whitespace 问题；staged=0。
- 未联网、未 stage/commit/push、不代签小林真实浏览器 QA。

## 六、完成回复

```text
纠偏状态：LOCAL_V1_PERMANENT_DELETION_PER_SEGMENT_RETENTION_R5_READY_FOR_XIAOLIN_QA
消息-共享记忆精确映射：PASS
直接anchor共享／间接unit-payload共享：PASS/PASS
第1段保留-B标题／第2段清除：PASS/PASS
不同共享记忆不串段：PASS
全保留／全清除／部分保留：PASS/PASS/PASS
共享字典缺失-孤儿-root-重复攻击：全部REJECTED
删除A共享保留-独占清除／再删B最终清除：PASS/PASS
普通详情无删除影响文案：PASS
内部技术词用户DOM命中：0
OpenAPI生成-兼容／API targeted／前端四门：PASS/PASS/PASS
operation／failure code／event type：30/50/12
V001至V017／database generated／prototype／MCP段语义：MATCH/MATCH/MATCH/MATCH
正文-path-token-capability-secret泄漏：0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
小林真实浏览器QA：WAITING_FOR_XIAOLIN_RERUN
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionPerSegmentRetention-R5-执行报告.md
```

## 七、执行者不代签

真实浏览器 QA（逐段保留/清除/部分保留、共享标题不串段、消息级标记准确、顶部动态汇总、普通详情无删除影响文案、删除成功清场不回退）由小林重新启动验收环境后签字，执行者不代签 PASS。
