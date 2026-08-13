# Local V1 浏览器 QA 脚本与无障碍返工（Task30D-R1）— 执行报告

## 状态

`LOCAL_V1_READ_VERTICAL_BROWSER_QA_R1_READY_FOR_XIAOLIN_RERUN`

本轮未启动浏览器、未做视觉结论、无 Git 写操作、未联网/下载。真实浏览器与 axe 结果标记为 `WAITING_FOR_XIAOLIN_RERUN`。

## R1 修复清单

### R1-01 筛选选择器（假失败）
`e2e/read-vertical.spec.ts`：将“全部/有效/归档”三个筛选定位改为 `page.getByRole("group", { name: "记忆状态筛选" }).getByRole("button", { name, exact: true })`，从语义容器边界 + 精确 accessible name 双维度消除与记忆卡片（含“有效/归档”状态标签）的歧义。未使用 `.first()`。

### R1-02 预期 HTTP 错误诊断分类（假失败）
`e2e/fixtures.ts`：
- 新增 `ApiAudit.expectedHttpDiagnostics` 独立计数。
- `isExpectedHttpDiagnostic` 精确判定：仅当 console 消息为 error、文本命中 `Failed to load resource`、携带与当前 mode 预期状态码精确一致的 `status of NNN`、且 `msg.location().url` 为被拦截的同源 `/v1/memories*` 端点时，才计入 `expectedHttpDiagnostics`；否则一律计入应用 `consoleErrors`。
- 未忽略所有 console error、未按字符串全局放行、未关闭 console/pageerror 监听。
`e2e/read-vertical.spec.ts`：每个 negative fixture 断言 `consoleErrors=0`、`pageErrors=0`、`expectedHttpDiagnostics` 精确等于 `expectedHttpDiagnosticCount(mode)`（4xx/5xx 为 1，ok/empty 为 0）；核心 `ok` 流程额外断言 `expectedHttpDiagnostics=0`。机器结果分别记录两类计数。

### R1-03 颜色对比度（无障碍，仅 token 级最小修复）
`src/app.css`（保留暖纸张/粉色书脊视觉方向）：
- `--ink-faint`: `#8c817b` → `#6b5f58`（`oklch(58% .025 40)` → `oklch(49.5% .019 52)`），在 paper-deep 上对比度 3.00 → 4.89（AA ≥ 4.5）。
- `--sage`: `#5f7868` → `#4a6354`（`oklch(52% .055 150)` → `oklch(47.5% .038 158)`），sage-wash 上 3.83 → 5.23。
- `--amber`: `#936f38` → `#7a5c2b`（`oklch(53% .075 75)` → `oklch(49.5% .077 78)`），amber-wash 上 3.61 → 4.87。
- `.nav-item.disabled`: 移除 `opacity: .62`，改用实色 `color: var(--ink-faint)`（以明暗表达“后续开放”，不以透明度降低对比度）。

### R1-04 链接名称（无障碍）
`src/App.tsx`：导航“记忆档案”`<Link>` 增加 `aria-label="记忆档案"`，使 1024 下 icon-only 链接具备稳定中文 accessible name（wordmark 已有 `aria-label="nest 记忆档案"`）。

### R1-05 标题层级（无障碍）
`src/App.tsx` 卡片标题 `h3` → `h2`；`src/app.css` `.memory-row h3` → `.memory-row h2`；`e2e/read-vertical.spec.ts` 同步选择器；`src/test/App.test.tsx` 同步断言。未在 axe 中排除 `heading-order`。

### R1-06 回归测试
`src/test/App.test.tsx` 新增断言：首页链接（wordmark）与档案链接（导航）accessible name 存在；卡片标题为 level 2；三个筛选按钮在语义容器内可唯一定位（`getAllByRole("button")` 长度 3 + 各自 name 唯一）。

### 必要的门禁基础设施修复（超出 R1 显式路径清单，1 文件）
`apps/nest-console/vite.config.ts`：给 vitest `test` 配置增加 `include: ["src/**/*.{test,spec}.{ts,tsx}"]`。原因：Task30D 新增的 `e2e/read-vertical.spec.ts` 会被 vitest 默认 `include` 误扫（`import { test } from "@playwright/test"` 在非 Playwright 运行器下抛 “did not expect test() to be called here”），导致 R1 门禁 `npm run test` 失败。本修改将 vitest 限定在 `src/`，排除 Playwright e2e 目录，是使门禁通过的唯一最小修改。

## 门禁结果（均未启动浏览器）

| 检查 | 结果 |
| --- | --- |
| `npm run typecheck --workspace @hide-nest/nest-console` | PASS |
| `npm run lint --workspace @hide-nest/nest-console` | PASS |
| `npm run test --workspace @hide-nest/nest-console` | PASS（10 tests passed） |
| `npm run build --workspace @hide-nest/nest-console` | PASS |
| e2e 文件 TypeScript（`tsc --ignoreConfig --strict`） | PASS（0 错误） |
| Playwright `test --list` | PASS（24 用例 = 3 project × 8，未启动浏览器） |
| PowerShell Parser | PASS（PS-SYNTAX-OK） |
| `git diff --check` / staged | 通过 / staged=0 |

## 最终回复

```text
返工状态：LOCAL_V1_READ_VERTICAL_BROWSER_QA_R1_READY_FOR_XIAOLIN_RERUN
筛选唯一定位／预期HTTP诊断分类：PASS/PASS
颜色对比／链接名称／标题层级修复：READY_FOR_REAL_AXE/READY/READY
前端 typecheck-lint-test-build：PASS/PASS/PASS/PASS
Vitest：10/0/0/0
Playwright list／PowerShell parser：PASS/PASS
真实浏览器与axe：WAITING_FOR_XIAOLIN_RERUN
生产代码修改：App.tsx/app.css（仅无障碍最小修复）
越界／staged：1/0（vite.config.ts 增加 vitest include 限定 src，排除 e2e/，为通过 npm run test 门禁的必要修改）
联网／Git写操作：否/否
报告路径：D:\myproject\hide-nest\reports\LocalV1-ReadVertical-BrowserQA-Script-执行报告.md
```
