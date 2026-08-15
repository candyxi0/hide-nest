# Task33B2｜Local V1 永久删除 React 原型接线 — 执行报告

## R1 完成回复（Task33B2-R1 成功清场与关闭失效返工）

```text
返工状态：LOCAL_V1_PERMANENT_DELETION_REACT_R1_READY_FOR_XIAOLIN_BROWSER_QA
成功自动返回／正文DOM清除／detail-evidence缓存清除：PASS/PASS/PASS
失败路径不伪清场：PASS
关闭迟到preview／关闭迟到poll：REJECTED/REJECTED
重开新preview-confirm key：PASS
其他三个动作写请求：0
浏览器Authorization-Cookie-capability／storage：0-0-0/0
nest-console／Node四门／Codex Adapter：PASS/PASS/PASS
prototype／V001—V016／database generated／契约-generated：MATCH/MATCH/MATCH/MATCH
真实浏览器跨进程QA：WAITING_FOR_XIAOLIN
真实Git index：未变
工作区：tracked=14、untracked task files=5、QA临时文件=1、staged=0、越界=0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionReact-执行报告.md
```

## R1｜两个生命周期缺口封口

1. **R1-01 成功立即清场并自动返回列表**：`pollRun` 收到 `CANONICAL_COMMITTED/INDEX_READY` 后直接调用 `onDeleted`（= 失效列表 + `onBack` 自动返回列表）；detail/evidence cache 由 `MemoryDetailView` 卸载清理 effect 即时 `removeQueries`；不再有“返回档案”二次操作与“已完成”成功弹窗。失败/超时/离线/STALE/DENIED 不清详情、不伪成功。
2. **R1-02 关闭立即失效异步请求**：`openDeleteDrawer` 在 open 与 close 都递增 `deleteSession`，抽屉以 `key={deleteSession}` 重挂载——关闭即卸载旧实例、递增其 `pollTokenRef`（迟到 preview/poll 结果经 token 校验丢弃）并清空 preview/闭包/problem/run 状态；再次打开创建全新 preview/confirm 幂等键并重新请求。

## R1 新增判定测试（apps/nest-console/src/test/App.test.tsx）

- 成功终态后不再点击任何按钮即自动回列表、正文不在 DOM、detail/evidence cache 清空、列表重取。
- 关闭后迟到 preview 响应不重新显示闭包；polling 期间关闭后迟到终态不触发导航/缓存清理。
- 关闭再打开 confirm key 换新；FINAL_FAILED/超时/STALE 仍留在详情未被清场。
- 其他三个动作写请求 0；浏览器 Authorization/Cookie/capability/storage 写入 0。

---

## B2 完成回复

```text
实施状态：LOCAL_V1_PERMANENT_DELETION_REACT_READY_FOR_XIAOLIN_BROWSER_QA
Java-TS canonical hash vectors：3/3
preview／唯一confirm／run收敛：PASS/PASS/PASS
动态闭包／pending-未知-异常图阻断：PASS/PASS
成功缓存正文清除／失败不伪成功：PASS/PASS
其他三个动作写请求：0
浏览器Authorization-Cookie-capability／storage／正文secret泄漏：0-0-0/0/0
nest-console／Node四门／Codex Adapter回归：PASS/PASS/PASS
Maven／OpenAPI：NOT_RUN_UNCHANGED/NOT_RUN_UNCHANGED
prototype／V001—V016／database generated／契约-generated：MATCH/MATCH/MATCH/MATCH
真实浏览器跨进程QA：WAITING_FOR_XIAOLIN
真实Git index：未变
工作区：tracked=14、untracked task files=5、QA临时文件=1、staged=0、越界=0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionReact-执行报告.md
```

## 1｜请求绑定规范化器（apps/nest-console/src/deletion-canonicalizer.ts）

- Web Crypto `crypto.subtle.digest("SHA-256", …)` 实现，逐字匹配 Java `LocalV1DeletionCanonicalizer` 冻结算法：`LOCAL_V1_DELETE_PREVIEW_V1` + memoryId（小写连字符 UUID）+ revisionNo（十进制）+ currentPolicyRevisionNo（十进制），每字段 `UTF8字节长 + ':' + 原值`，最终 64 位小写 hex。
- 3 组交叉语言固定向量由正式 Java `LocalV1DeletionCanonicalizer.requestHashHex` 实际运行产出（`4ebc…43ed` / `950d…82f9` / `4b8e…041e`），TS 生产实现逐字一致；测试只做固定向量比对，不复刻第二套实现。
- 非规范 UUID、revision < 1、policyRevision < 1 均在发请求前拒绝。hash 仅作请求绑定，不作授权或秘密。

## 2｜真实 API 接线（apps/nest-console/src/App.tsx）

- `DeletionApi(new Configuration({ basePath: "/v1" }))`，未设置 accessToken/apiKey。
- **Preview**：点“永久删除”→ 生成一次 `Idempotency-Key=crypto.randomUUID()`（抽屉生命周期内稳定，`key={deleteSession}` 重开换新 key）→ 用 `memoryId/revisionNo/currentPolicyRevisionNo` 计算 `requestManifestHash` → `createDeletionPreview` → 成功后展示真实闭包；loading 保留骨架。
- **Confirm**：唯一按钮 `永久删除这 1 条记忆`；confirm key 生命周期内固定；请求逐项带回 `previewId/previewRevision/manifestHash` + 同一组 memory binding；不设 Authorization/Cookie/X-Action-Capability；二次/并发点击仅一个在途 confirm（确认中禁用）。
- **Run 收敛**：confirm 202 后只用同源 `statusUrl/runId`，校验 path 精确 `/v1/deletion-runs/{runId}` 否则 fail closed；有界轮询（默认 10 次 × 500ms，可注入）；`CANONICAL_COMMITTED/INDEX_READY` → “永久删除已完成” + 清 cache + 返回列表重取；`FINAL_FAILED` → 稳定中文失败（requestId/failureCode/retryable，不泄露 SQL/堆栈/正文/路径）；超时 → “执行状态尚未收敛”保留 runId 短标识。

## 3｜严格复刻已验收原型

- eyebrow `永久删除 · 不可撤销`、title `永久删除影响预览`、description/warning 逐字沿用 prototype。
- 影响列表由 `closureMembers` 动态聚合：规范记忆/记忆版本/来源锚点/来源消息/原文载荷（中文标签 + 计数），不显示 memberKind/disposition 英文、完整 UUID、contentHash、objectRef。
- `DELETE_CANDIDATE` 与 `AFFECTED_PENDING_CHOICE` 区分；空闭包/未知 memberKind/disposition/重复 ordinal/不连续 ordinal/存在 pending choice 时最终确认按钮禁用并显示稳定说明。
- 只有一个最终确认按钮，无复选框/短语/二次弹窗；关闭/Escape 只关闭不 confirm，焦点回归“永久删除”；修正/归档/隔离继续原有未接线反馈（写请求 0）。

## 4｜安全与状态边界

- token/capability 不进 React/bundle/HTML/URL/storage/console/测试截图；preview/confirm/run 响应不写 localStorage/sessionStorage/IndexedDB；抽屉关闭或卸载清空状态。
- stale/denied/offline/not-found/integrity 中文稳定分类，不渲染后端原始异常；证据抽屉行为与关闭清除语义不回退。

## 5｜自动测试（apps/nest-console/src/test）

- `deletion-canonicalizer.test.ts`：3 组 Java 固定向量 + 冻结字符串 + 非法输入拒绝（5 项）。
- `App.test.tsx` 新增 17 项覆盖：点击前 0 请求/点击后 preview 恰 1、preview key 重开换新、动态中文聚合无英文枚举/UUID/hash/objectRef、空/重复/不连续/未知/pending 阻断确认、关闭/Escape 不 confirm 焦点回归、confirm 双击仅 1 请求、statusUrl 非同源/路径错误拒绝、中间态→成功（清缓存+DOM 正文清空）、FINAL_FAILED 稳定失败不伪成功、轮询超时、stale/denied 分类 0 原始异常泄漏、浏览器 Authorization/Cookie/capability 为 0、storage 写入 0。
- 既有 12 项 React 测试回归继续 PASS（其中“四个未接线”精确调整为“三个未接线 + 永久删除打开抽屉”）。

## 6｜质量门

| 门 | 结果 |
|---|---|
| nest-console typecheck/lint/test/build | PASS（34 项全绿） |
| 全仓 Node typecheck/lint/test/build | PASS |
| Codex Adapter 回归 | 71 passed / 1 symlink skip（未回退） |
| Maven / OpenAPI | NOT_RUN_UNCHANGED |
| prototype 三文件 hash | MATCH（`3940…b260` / `4574…1296` / `8d9f…d90fe`） |
| V001—V016 / database generated / 契约 / generated | MATCH（未在 git diff 中） |
| git diff --check | 仅官方生成 MemoryDetail.ts:62 trailing whitespace；nest-console 文件无 |
| staged / 越界 / index | 0 / 0 / 未变 |

完成即停，未 stage/commit/push，未实施 B3。
