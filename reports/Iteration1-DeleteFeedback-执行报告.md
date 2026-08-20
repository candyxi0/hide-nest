# Task41A｜Nest 第一次迭代：永久删除反馈真实修复 · 执行报告

实施状态：`ITERATION1_DELETE_FEEDBACK_UI_READY_BACKEND_DELETION_BLOCKED`

> 现场证据纠偏（家庭小主机真实数据库）：本报告撤回此前“根因仅在 React、不涉及后端 run 收敛”的判断，并撤回 `READY_FOR_HIDE_REVIEW` 状态。当前状态为：前端 UI 反馈修复就绪，后端物理删除链被阻断。

## 1. 基线核对

- 仓库：`D:\myproject\hide-nest`
- HEAD：`1b2e753ebee1b222311a80cfc0fa8ea5ecb19dba`（`feat: make context pack recall proactive`）
- `origin/main`：`1b2e753ebee1b222311a80cfc0fa8ea5ecb19dba` —— 与 HEAD 一致
- 初始 staged：0
- 允许保留且禁止触碰：`reports/local-v1-read-browser-qa/.playwright-artifacts/`（未触碰）
- 基线相符

## 2. 真实根因（现场证据修正：双成分）

原 `DetailContent.handleDeletionSucceeded()`（`apps/nest-console/src/App.tsx`）只有两步：

```ts
void queryClient.invalidateQueries({ queryKey: [LIST_KEY] }); // 异步后台重取
onBack();                                                     // 返回列表
```

**成分 A —— React 层成功反馈缺口（已修，PARTIAL_PASS）**：成功回调只返回列表，列表页没有任何 `role=status` / `aria-live` 成功反馈；且被删 `memoryId` 不会在成功瞬间移出 list query cache，旧行残留到后台重取收敛为止（延迟/失败时迫使刷新）。这是“真正成功之后的反馈与缓存窗口”问题，属于允许路径，已由本轮前端补丁解决。

**成分 B —— 后端删除链系统性断裂（未修，BLOCKED，超出 React 允许路径）**：家庭小主机真实数据库显示，`1b01b375…`、`7a6af2ec…`、`1d93c9bc…` 等对象均为 `deletion_closure=CONFIRMED`、fence 已建立，但 `runtime.deletion_run=0`，`memory_record` / `memory_revision` 仍存在；此外还有多个相同 NO_RUN 对象，属系统性后端删除链断裂。即：删除声明已确认、围栏已建，但物理删除从未执行。

**两成分对症状的关系**：前端补丁仅保证“真正成功（run 到达 `CANONICAL_COMMITTED` / `INDEX_READY`）后的反馈与缓存窗口”。由于成功回调仍只由 `CANONICAL_COMMITTED` / `INDEX_READY` 触发，前端不会在 NO_RUN / 500 路径伪造成功；在成分 B 未修复前，受影响对象永远无法让 run 收敛到成功终态，前端也就不会对这些对象展示成功反馈。物理删除本身被阻断，需后端介入，属本工单禁止修改范围，标记 BLOCKED。

## 3. 旧测试为什么没有捕获

既有“成功后自动返回列表并清除 detail/evidence 缓存与 DOM 正文”（`App.test.tsx`）的 mock 让列表接口在 `deleted=true` 的同一时刻直接返回 `items: []`，从而：
- 掩盖了“无反馈”与“缓存窗口”两个前端缺陷（假阳性）；
- 隐含假定后端总是能收敛到成功终态，完全未覆盖后端 NO_RUN / 不收敛导致前端永远停在 polling / 未成功的情况。

因此测试只能证明“前端最终能展示由服务端重取到的空列表”，既不能证明前端反馈正确，也不能揭示后端删除链断裂。

## 4. 本轮生产修改（仅允许路径，保留）

`apps/nest-console/src/App.tsx`：

1. 把成功回调上移到 `MemoryArchive.handleDeletionSucceeded(deletedMemoryId)`，保证跨一次返回导航存活：
   - `queryClient.removeQueries` 清除该 `memoryId` 的 detail / evidence cache；
   - `queryClient.setQueriesData({ queryKey: [LIST_KEY] }, …)` 立即从**所有** list query 变体缓存中过滤掉该 `memoryId`，不等待网络重取；
   - `setDeleteFeedback({ text: "已永久删除 1 条记忆。" })` 设置一次性反馈；
   - `void queryClient.invalidateQueries({ queryKey: [LIST_KEY] })` 以服务端事实重新收敛；
   - `backToList()` 只导航一次。
2. `MemoryArchive` 新增 `deleteFeedback` 状态，列表页渲染：
   `<p className="delete-feedback-banner" role="status" aria-live="polite">已永久删除 1 条记忆。</p>`
   - 不含标题、正文、完整 UUID、requestId、路径或秘密；不写任何持久存储（`localStorage`/`sessionStorage`/IndexedDB/Cookie/URL query），仅当前 React 生命周期内的内存状态。
3. `selectMemory` 在选择其他记忆时 `setDeleteFeedback(null)`，保证反馈“一次性”。
4. `MemoryDetailView` / `DetailContent` 增加 `onDeleted` 传递；`DetailContent.handleDeletionSucceeded` 改为委托给上移后的处理器。成功判定仍只由 `DeleteDrawer.pollRun()` 在 `CANONICAL_COMMITTED` / `INDEX_READY` 触发，NO_RUN / 500 / 失败不会伪造成功。

`apps/nest-console/src/app.css`：
- 新增 `.delete-feedback-banner` 样式（唯一必要 CSS 改动）。

未修改：Java、OpenAPI、生成客户端、数据库、migration、MCP、Console Host、prototype、其他报告。以上 3 个前端文件（`App.tsx`、`app.css`、`App.test.tsx`）按现场证据保留，不回退、不扩改。

## 5. 新增反证（`apps/nest-console/src/test/App.test.tsx`，+6 用例）

1. **延迟 list refetch 反证**：run 成功后将新 list 响应人为挂起；断言返回列表后、重取仍未 resolve 时，被删 `memoryId` 已不在 list cache 与 DOM，且成功反馈可见（`role=status` + `aria-live=polite`）。
2. **refetch 失败反证**：后台 list refetch 返回 503；断言旧行不复活、成功反馈仍说明删除完成、列表错误走既有安全路径。
3. **失败终态不清场**（`it.each` FINAL_FAILED / STALE）：无成功反馈、list cache 不移除、不导航、detail cache 仍在。
4. **轮询超时不清场**：同上，超时后无成功反馈、无缓存移除、无导航。
5. **一次性反馈**：成功后反馈恰 1 次；普通 refetch 收敛不重复；重新选择其他记忆后反馈被清除；`Storage.setItem` 调用为 0。

既有回归（自动返回、正文清除、缓存清除、双击单请求、迟到 preview/poll、Authorization/Capability/storage=0 等）继续 PASS。这些反证证明的是**前端不伪造成功**（失败/超时/NO_RUN 场景无成功反馈），而非后端删除已物理完成。

## 6. 质量门

| 门 | 结果 |
|---|---|
| `apps/nest-console` targeted Vitest（`App.test.tsx`） | PASS（46/46） |
| `apps/nest-console` typecheck | PASS |
| `apps/nest-console` lint | PASS |
| `apps/nest-console` test（全量 51/51） | PASS |
| `apps/nest-console` build | PASS |
| 根 Node 四门 | NOT_RUN_UNCHANGED（改动仅限 nest-console，未涉及共享包） |
| Maven / OpenAPI / 数据库 | NOT_RUN_UNCHANGED / NOT_RUN_UNCHANGED / NOT_RUN_UNCHANGED |
| `git diff --check` | PASS（无空白错误，仅 LF→CRLF 提示） |
| 路径扫描（改动仅限允许路径） | PASS |
| 泄漏扫描（新增行无 uuid/requestId/token/secret/capability/cookie/key 模式） | PASS |
| Git index 起止一致 | MATCH（index tree = HEAD tree = `170d1d2d…`） |
| staged | 0 |

## 7. 工作区精确计数

- 修改文件：3（`apps/nest-console/src/App.tsx`、`apps/nest-console/src/app.css`、`apps/nest-console/src/test/App.test.tsx`）
- 新增测试用例：6（App.test.tsx 40 → 46；nest-console 全量 51）
- 未跟踪（允许保留）：`reports/local-v1-read-browser-qa/.playwright-artifacts/`
- 越界修改：0
- 联网：否；Git 写操作（stage/commit/push）：否；Git 配置：未修改

## 8. 完成回执

```text
实施状态：ITERATION1_DELETE_FEEDBACK_UI_READY_BACKEND_DELETION_BLOCKED
真实根因／旧测试假阳性：双成分——A.React成功反馈缺口与缓存窗口（已修）+B.后端删除链断裂NO_RUN（deletion_closure=CONFIRMED/fence已建但deletion_run=0、memory_record/revision仍存）／mock让list在成功瞬间返回空列表掩盖前端窗口且假定后端总收敛
React成功反馈修复（真正成功后的反馈+缓存窗口）：PARTIAL_PASS
后端物理删除链（NO_RUN/500 不收敛到成功终态）：BLOCKED
成功立即移除旧行／后台refetch：PASS/PASS（前端层面）
成功可见反馈／aria-live：PASS/PASS（前端层面，仅在真正成功时）
FINAL_FAILED-timeout-STALE不伪成功：PASS（前端不伪造，NO_RUN同样无成功反馈）
双击／迟到preview-poll：PASS/PASS
浏览器持久存储／正文-token-capability-secret泄漏：0/0-0-0-0
targeted／nest-console四门／diff-whitespace：PASS/PASS/PASS
Maven／OpenAPI／数据库：NOT_RUN_UNCHANGED/NOT_RUN_UNCHANGED/NOT_RUN_UNCHANGED
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\Iteration1-DeleteFeedback-执行报告.md
```
