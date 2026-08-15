# LocalV1 关窗证据段边界与统一气泡语义纠偏 — R4 执行报告

`状态：LOCAL_V1_EVIDENCE_SEGMENT_BOUNDARY_R4_READY_FOR_XIAOLIN_QA`

`冻结基线：HEAD=f5f0051cad6317c1505f69dbf3dd34c1530d258f`

`执行时间：2026-08-15`

## 一、返工目标与结论

小林浏览器 QA 裁定 R3 失败：MCP 关窗输入仍是扁平 `evidenceMessages[]`，`buildCloseoutRequest()` 却按每条消息各建一个 `sourceAnchor`，导致 R3 前端按 `anchorId` 分段后把两条连续消息误切成两段；删除预览缺少 `anchorId`，无法对多段证据真实分组；单条证据仍走表格 + blockquote 特例。本轮按已锁死的产品语义跨入口纠偏：一段一 anchor 多 unit、多段多 anchor、单条段仍是气泡，详情与删除预览复用同一分组／气泡组件，段数由真实 `anchorId` 动态派生。

未新增操作码／失败码／事件类型，未修改 V001—V017、database generated、prototype，未新增依赖，未联网，未 stage/commit/push。

## 二、变更

### 2.1 MCP 关窗输入改为显式证据段（`apps/codex-adapter`）

- `closeout-canonicalizer.ts`：`CloseoutInput.evidenceMessages` 改为 `evidenceSegments: { messages: EvidenceMessageInput[] }[]`；`buildCloseoutRequest()` 将每个 segment 映射为恰好一个 `sourceAnchor`（`units[]` 含该段全部消息，按 ordinal 升序），`threadReaderManifest.continuous` 由 `true` 改为 `boolean`（单段 `true`、多段 `false`），`fromOrdinal`/`toOrdinal` 取全范围首尾，manifest/review/proof 以真实 grouped anchors 与真实 continuous 重算，`anchorId` 由 `closeoutKey + 本段首 ordinal` 确定性派生且不等于 `sourceUnitId`。
- `closeout-input.ts`：`evidenceSegments` 1～100 段、合计 1～100 条；每段 ≥1 条；段内 ordinal 严格升序且逐一连续；段间严格升序、不重叠；前段尾与后段首相邻必须合并（拒绝伪分段）；跨段必须存在 ordinal 缺口；perspective speaker 在全部消息集合中至少出现一次；旧顶层 `evidenceMessages` 作为未知字段 fail closed；错误不回显正文／speakerKey／threadKey／秘密。
- `mcp.ts`：input schema 改为 `evidenceSegments`（strictObject，段内 `messages` 1～100）。
- 直接测试 `closeout-input.test.ts`、`closeout-canonicalizer.test.ts`、`closeout-client.test.ts`、`mcp.integration.test.ts` 同步重写。

### 2.2 启动器 synthetic closeout 输入

- `启动-hide-nest-永久删除浏览器验收.ps1`：seed 脚本两处 `evidenceMessages` 改为 `evidenceSegments`（单段）。

### 2.3 Java 正式入口允许 `continuous=false`

- `LocalV1CloseoutWriteCoordinator.validate()`：移除 `!manifest.continuous()` 的拒绝条件，多段（连续 false）成为合法 wire 形状；manifest/review/proof 已按真实 grouped anchors 与 continuous 值重算。
- `LocalV1CloseoutWriteHttpIntegrationTest`：新增反证 `multiSegmentContinuousFalseIsAcceptedAndGrouped`（两段 `[1,2]+[5,6]`，`continuous=false`，断言 202 + 2 个 anchor 分组 + 4 条消息）。

### 2.4 删除预览补齐正式段标识 `anchorId`

- OpenAPI `DeletionEvidenceItem` 新增 required `anchorId: uuid`；同步 `baseline.yaml`、`ContractInventory-HDM-003-v0.1.json`（description），并重生成官方 Java／TS 生成物（`DeletionEvidenceItem.java`／`.ts` 等）。
- Java 链：`DeletionPreviewGraph.EvidenceUnit` 新增 `anchorId`；`JooqDeletionPreviewAdapter` 由 `source_anchor_unit` 反查并输出真实 anchorId；`LocalV1DeletionEvidenceMessage`、`LocalV1S3ADeletionPreviewCoordinator.readEvidence`、`LocalV1DeletionWriteController.toEvidenceItem` 逐层透传。
- `LocalV1DeletionHttpIntegrationTest`：断言删除预览与 read/evidence 的 anchorId 与 ordinal 完全一致。

### 2.5 统一展示规则（`apps/nest-console`）

- 删除 `SingleEvidence`／`EvidenceMeta` 的表格 + blockquote 特例，及 `.single-evidence`／`.evidence-meta` 样式。
- 新增共享 `EvidenceConversation`（按 `anchorId` 分组、段内按 ordinal 升序、单段多消息一个连续对话区不显示“第 N 段”、多段每段独立对话区带紧凑段标题、单条段仍是气泡）。
- 详情 `EvidenceResult` 与删除预览 `DeleteDrawer` 复用 `EvidenceConversation`；删除预览段数由真实 `anchorId` 动态派生（不再写死 `1 段`）。
- hide 左侧暖灰／小林右侧 rose／未知中立、正文仅原话不拼姓名前缀、R3 轻量摘要保留。

## 三、验证

- Codex Adapter typecheck／lint／test／build：PASS/PASS/PASS/PASS（87 passed, 1 skipped）。
- 契约生成 check（`generate.ps1 -Target openapi -Check`）：GENERATION_CHECK_PASS。
- 契约兼容（`contract-compatibility.ps1`）：COMPATIBILITY_PASS（baseline-vs-current=COMPATIBLE）。
- 前端 nest-console typecheck／lint／test／build：PASS/PASS/PASS/PASS（42 passed，含新增“删除预览段数动态派生多段不串段”）。
- Java targeted tests（真实 Postgres + Spring，非 mock）：
  - `LocalV1S3ADeletionPreviewTest`：8/8 PASS；
  - `LocalV1CloseoutWriteHttpIntegrationTest`：17/17 PASS（含 `continuous=false` 反证）；
  - `LocalV1DeletionHttpIntegrationTest`：12/12 PASS（含删除预览 anchorId 一致性断言）。
- 输入与规范化矩阵：单段 `[0,1,2]`→1 anchor/3 units/continuous=true；两段 `[0,1]+[4,5]`→2 anchors/扁平 `[0,1,4,5]`/continuous=false；单条 `[7]`→1 anchor/1 unit；段内缺口、重复、倒序、跨段重叠、倒序、相邻伪分段、旧 `evidenceMessages`、空段、合计 101、未知字段全部 REJECTED；同值重放 wire/hash/proof 一致。

## 四、机械验收矩阵结论

- 单段多消息／多段／单条段：PASS/PASS/PASS。
- 一段一 anchor 多 unit／多段多 anchor：PASS/PASS。
- manifest continuous true-false／hash-proof：PASS/PASS。
- 段内缺口／跨段重叠-相邻伪分段：REJECTED/REJECTED。
- 真实 closeout read-evidence／deletion preview 分组：PASS/PASS。
- 详情／删除预览动态段数：PASS/PASS。
- 单条旧表格-blockquote 样式命中：0。
- hide 左／小林右／未知中立：PASS/PASS/PASS。
- operation／failure code／event type：30/50/12。
- V001—V017／database generated／prototype：MATCH/MATCH/MATCH。
- 真实 Codex 房间读取器：NOT_IMPLEMENTED_SYNTHETIC_ONLY。
- 正文-path-token-capability-secret 泄漏：0-0-0-0-0。
- 真实 Git index／staged／越界：MATCH/0/0。
- 联网／Git 写操作：否／否。

## 五、完成回复

```text
纠偏状态：LOCAL_V1_EVIDENCE_SEGMENT_BOUNDARY_R4_READY_FOR_XIAOLIN_QA
单段多消息／多段／单条段：PASS/PASS/PASS
一段一anchor多unit／多段多anchor：PASS/PASS
manifest continuous true-false／hash-proof：PASS/PASS
段内缺口／跨段重叠-相邻伪分段：REJECTED/REJECTED
真实closeout read-evidence／deletion preview分组：PASS/PASS
详情／删除预览动态段数：PASS/PASS
单条旧表格-blockquote样式命中：0
hide左／小林右／未知中立：PASS/PASS/PASS
Codex Adapter四门／契约生成／API targeted／前端四门：PASS/PASS/PASS/PASS
operation／failure code／event type：30/50/12
V001—V017／database generated／prototype：MATCH/MATCH/MATCH
真实Codex房间读取器：NOT_IMPLEMENTED_SYNTHETIC_ONLY
正文-path-token-capability-secret泄漏：0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
小林真实浏览器QA：WAITING_FOR_XIAOLIN_RERUN
报告路径：D:\myproject\hide-nest\reports\LocalV1-EvidenceSegmentBoundary-R4-执行报告.md
```

## 六、执行者不代签

真实浏览器 QA（1440/1024/390 静态响应式门、单段多消息只一个对话区、两个分散段不串段、单条段仍是气泡、删除预览段数动态派生、缓存清除与删除成功清场不回退）由小林重新启动验收环境后签字，执行者不代签 PASS。JS 生成的单段与多段 wire 请求已由 Java 侧字节级镜像的 canonicalizer 与 `continuous=false` 反证覆盖；真实 JS→Java 端到端由小林浏览器验收环境最终确认。

---

# R4-R1 封口：Java 正式入口证据段分区

`封口状态：LOCAL_V1_EVIDENCE_SEGMENT_BOUNDARY_R4_R1_READY_FOR_XIAOLIN_QA`

`基线：HEAD=f5f0051，仅在工作树 R4 成果上封口`

## 一、封口目标与结论

R4 已完成 MCP 显式 `evidenceSegments`、删除预览 `anchorId` 与统一气泡。R4-R1 只封口一个阻塞：正式 Java HTTP 入口独立验证“已选消息集合”与“锚点分段集合”是精确的一一分区，不依赖 MCP 生成正确请求。

## 二、生产代码修改（1）

`LocalV1CloseoutWriteCoordinator.java`（唯一生产代码）在写入前 fail closed：

1. `selectedEvidenceMessages` 按请求顺序 ordinal 严格递增；`fromOrdinal` 等于最小、`toOrdinal` 等于最大 ordinal。
2. 每个 `sourceUnitId` 在全部 `sourceAnchors[].units[]` 中恰好出现一次（跨 anchor 重复、未归段、anchor 引用未选消息全部拒绝）。
3. 每个 anchor 内 unit ordinal 严格递增且逐一连续、必须等于对应 selected message 的 ordinal、不允许重复。
4. anchor 之间按首 ordinal 严格递增、互不重叠；相邻两段 `currentFirst == previousLast + 1` 拒绝为伪分段。
5. `threadReaderManifest.continuous` 与实际分段严格一致：1 个 anchor 必须 `true`、2+ 必须 `false`。
6. 错误复用既有冻结失败语义（`SOURCE_ORDER_INVALID`/`SOURCE_RANGE_GAP`/`REQUEST_SCHEMA_INVALID`），未新增 operation／failure code／event type，不回显正文或内部标识。

## 三、HTTP 反证测试（攻击矩阵 9/9）

`LocalV1CloseoutWriteHttpIntegrationTest.partitionAttackMatrixRejectsAllWithZeroWrites` 覆盖并证明非 2xx、零规范事实写入（memory_record／evidence.source 计数不变）：

1. 同一 sourceUnit 放入两个 anchor → SOURCE_ORDER_INVALID；
2. selected message 漏出所有 anchor → SOURCE_ORDER_INVALID；
3. anchor unit ordinal 与对应 selected message 不一致 → SOURCE_ORDER_INVALID；
4. anchor 内 ordinal 缺口 → SOURCE_ORDER_INVALID；
5. 两个 anchor ordinal 相邻伪装两段 → SOURCE_ORDER_INVALID；
6. 两个 anchor 声明 `continuous=true` → REQUEST_SCHEMA_INVALID；
7. 一个 anchor 声明 `continuous=false` → REQUEST_SCHEMA_INVALID；
8. `fromOrdinal`/`toOrdinal` 不等于真实首尾 → SOURCE_RANGE_GAP；
9. selected messages 请求顺序倒序（manifest/review/proof 全部按攻击请求重算）→ SOURCE_ORDER_INVALID。

所有攻击均重算 manifest/review/proof 哈希，使请求真正命中新分区门，而非旧哈希不匹配假阳性。

## 四、合法路径回归

- 合法单段多消息（1 anchor／2 units，`continuous=true`）仍 202，落库 1 个 anchor、2 个 unit/message。
- 合法两段 `[1,2]+[5,6]`（2 anchors／4 units，`continuous=false`）仍 202，落库 2 个 anchor，段内连续且段间有缺口。

## 五、验证门（窄门）

- `LocalV1CloseoutWriteHttpIntegrationTest`：18 executed / 0 failures / 0 errors / 0 skipped。
- `LocalV1DeletionHttpIntegrationTest`：12 executed / 0 failures / 0 errors / 0 skipped（回归通过；其 `buildCloseout` 测试构造器按“mirrors closeout test”注释同步为单段 1 anchor／2 units，避免新分区门误伤既有删除纵切）。
- `git diff --check`：本轮文件无 whitespace 问题；staged=0。
- 未改 MCP、OpenAPI、生成物、前端、V001—V017、database generated、prototype、启动器及其他生产代码（唯一生产代码修改为 `LocalV1CloseoutWriteCoordinator.java`；`LocalV1DeletionHttpIntegrationTest.java` 为测试构造器镜像修正）。
- 未联网、未 stage/commit/push。

## 六、完成回复

```text
封口状态：LOCAL_V1_EVIDENCE_SEGMENT_BOUNDARY_R4_R1_READY_FOR_XIAOLIN_QA
已选消息-锚点精确分区：PASS
跨anchor重复／未归段／非法引用：REJECTED/REJECTED/REJECTED
anchor内连续与ordinal绑定：PASS
跨anchor排序-不重叠-非相邻：PASS
continuous true-false精确绑定：PASS
首尾ordinal／selected顺序：PASS/PASS
攻击矩阵：9/9
合法单段／合法多段：PASS/PASS
targeted tests：18/0/0/0
生产代码修改：1
MCP-契约-generated-前端-V001至V017：MATCH/MATCH/MATCH/MATCH/MATCH
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
小林真实浏览器QA：WAITING_FOR_XIAOLIN_RERUN
报告路径：D:\myproject\hide-nest\reports\LocalV1-EvidenceSegmentBoundary-R4-执行报告.md
```
