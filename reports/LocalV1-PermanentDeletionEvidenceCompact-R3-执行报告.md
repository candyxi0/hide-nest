# LocalV1 永久删除完整证据轻量信息结构 — R3 执行报告

`状态：LOCAL_V1_PERMANENT_DELETION_EVIDENCE_COMPACT_R3_READY_FOR_XIAOLIN_QA`

`冻结基线：HEAD=f5f0051cad6317c1505f69dbf3dd34c1530d258f`

`执行时间：2026-08-15`

## 一、返工目标与结论

小林浏览器 QA 后裁定：详情页“查看完整证据”抽屉 R2 版过于简略，缺少“这是谁的证据、保存范围多大”的上下文，但 V1 不恢复整块审计式表格。本轮为纯前端派生与展示返工，在说明文字之后、聊天之前增加轻量摘要区，并支持多段证据按 anchor 分组。

未修改契约、后端、V001—V017、prototype、database generated，未新增依赖。

## 二、变更

1. `EvidenceResult` 增加轻量摘要：`对应记忆：{MemoryDetail.title 派生}`（复用已加载 detail，不发新请求）与 `完整证据：{段数} 段，共 {消息数} 条消息 · {时间或时间范围}`。
2. 段数 = `anchorId` 去重数；消息数 = `evidenceItems.length`；时间 = `occurredAt` 最早/最晚，同分钟单时间、跨分钟紧凑范围，全部动态派生。
3. 多段证据按首次出现顺序稳定分组，每段仅一行紧凑小标题 `第 N 段 · X 条消息 · 时间或时间范围`，段内按 `ordinal` 升序，不串段。
4. 继续复用 R2 的 `EvidenceBubble`／统一 actor presentation（hide 左、小林右、未知中立），正文仅原话。
5. 新增 `.evidence-summary`／`.evidence-segment`／`.evidence-segment-heading` 样式。

## 三、验证

- 前端 `App.test.tsx` 41/41 PASS，新增覆盖：单段摘要（对应记忆 + `1 段，共 2 条消息`、无 `第 N 段` 编号）、多段分组（`2 段，共 3 条消息`、两个段落不串段）、时间动态派生（同分钟无范围分隔符、跨分钟含分隔符、不写死日期）、冗余字段 DOM 0 命中（来源/参与者/截取边界/展示完整性/保留理由）、重复姓名前缀 0（`hide 说`/`小林说`）。
- 详情缓存清除与删除预览回归测试保持 PASS（既有用例不回退）。
- Node 四门 typecheck/lint/test/build：PASS/PASS/PASS/PASS。
- `git diff --check`（本轮三个文件 App.tsx/app.css/App.test.tsx）：无 whitespace 错误；staged=0；V001—V017／prototype／契约／database generated 均未改动（MATCH）；未联网、未 stage/commit/push。

## 四、完成回复

```text
返工状态：LOCAL_V1_PERMANENT_DELETION_EVIDENCE_COMPACT_R3_READY_FOR_XIAOLIN_QA
对应记忆／证据摘要：PASS/PASS
单段／多段分组：PASS/PASS
段数-消息数／时间范围动态派生：PASS/PASS
hide左／小林右／未知中立：PASS/PASS/PASS
冗余原型字段DOM命中：0
重复姓名前缀：0
详情缓存清除／删除预览回归：PASS/PASS
前端 typecheck-lint-test-build：PASS/PASS/PASS/PASS
prototype／V001—V017／契约／database generated：MATCH/MATCH/MATCH/MATCH
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
小林真实浏览器QA：WAITING_FOR_XIAOLIN_RERUN
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionEvidenceCompact-R3-执行报告.md
```

## 五、执行者不代签

真实浏览器 QA（1440/390 摘要紧凑、无横向溢出、无重复姓名前缀、删除预览未被破坏）由小林重新启动验收环境后签字，执行者不代签 PASS。
