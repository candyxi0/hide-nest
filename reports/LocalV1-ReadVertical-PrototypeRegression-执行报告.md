# Local V1 详情页已验收原型视觉回归执行报告

## 1. 结论

视觉回归状态：`LOCAL_V1_READ_DETAIL_PROTOTYPE_REGRESSION_READY_FOR_HIDE_REVIEW`

当前生产 React 记忆详情页已恢复到已验收原型的主要信息结构、比例、视觉层级与交互表达。四个治理动作仅提供可访问的“当前本地 V1 尚未接线”反馈；最终真实浏览器审计中写请求为 0，未伪造任何治理成功结果。

本工单未修改已验收原型、OpenAPI、生成客户端、Java、数据库、迁移或其他后端文件；未清理、覆盖或回退工作区既有 Task30 成果；未执行 stage、commit、push；未联网或安装依赖。

## 2. 权威原型完整性

开工与结束时分别计算三份权威文件 SHA-256，结果均为 3/3 MATCH：

| 文件 | 期望及实际 SHA-256 | 结果 |
|---|---|---|
| `docs/frontend/prototype/index.html` | `3940e0b43331540a795ce96fb4644bd849a01fb5b31559adcb4fd6d60b53b260` | MATCH |
| `docs/frontend/prototype/assets/app.js` | `4574d4cf15b374ce9261151f6599b9ee60abb4660ca301c08aec952f5a361296` | MATCH |
| `docs/frontend/prototype/assets/styles.css` | `8d9fa48c252393b7cd98466fa41c27ed61e7b03758ddcc7d5bbbea0d6f7d90fe` | MATCH |

`git diff --name-only -- docs/frontend/prototype` 为空，确认 `docs/frontend/prototype/**` 未被修改。

## 3. 实施范围

本工单实际触及路径均在允许范围内：

- `apps/nest-console/src/App.tsx`
  - 恢复 `detail-header` 左侧正文／右侧动作面板双区结构；
  - 按“第一条非空行为标题，剩余内容为正文”的稳定规则拆分 `bodyText`；
  - 单行 `bodyText` 只渲染一次，不生成重复正文；
  - 恢复修正、归档、隔离、永久删除四个入口及原型排布；
  - 点击入口只更新 `role=status` 可访问反馈，不调用写 API；
  - 恢复五项定义条：规范状态、类型、视角、证据、当前版本；
  - 恢复证据区域粉色书脊、按需读取入口和真实边界说明；
  - 恢复右侧治理边界窄栏，未接入数据稳定显示 `尚未接入本地 V1`；
  - 保留证据抽屉按需读取、关闭清除查询内存以及 hide 左／小林右气泡语义。
- `apps/nest-console/src/app.css`
  - 按已验收原型恢复暖纸张、粉色书脊、衬线详情标题、细线分隔、动作面板、定义条、证据区、治理窄栏；
  - 1440 保持导航＋列表＋详情并列；
  - 1024 压缩导航与列表，同时保留动作区、五项定义和治理栏；
  - 390 列表／详情分屏，详情内动作区、定义条、证据区、治理边界纵向折叠；
  - 长正文使用自然换行与 `overflow-wrap`，不截断、不横向溢出。
- `apps/nest-console/src/test/App.test.tsx`
  - 新增四入口存在且唯一、五项标签精确、治理边界存在、单行去重、多行拆分、未接线动作零写请求和可访问反馈断言；
  - 保留并通过证据按需读取、关闭 cache 清除、对话气泡、失败分支、URL 与持久存储安全断言。
- `apps/nest-console/e2e/read-vertical.spec.ts`
  - 新增标题／正文拆分、五项定义条、治理边界、四动作和零写请求机械断言；
  - 单行详情改为严格验证标题唯一且无重复 `.detail-body`；
  - 保留三视口 list／detail／drawer 截图，并增加详情下半区与移动动作区截图，覆盖首屏以下的真实视觉状态。
- `apps/nest-console/e2e/fixtures.ts`
  - 请求审计新增 `writeRequests`，最终汇总对非 GET 请求精确计数。
- `reports/local-v1-read-browser-qa/**`
  - 由现成 QA 脚本生成最终截图、axe、Playwright 和机器汇总产物。
- `reports/LocalV1-ReadVertical-PrototypeRegression-执行报告.md`
  - 本报告。

`apps/nest-console/scripts/run-browser-qa.ps1` 未修改。

## 4. 静态门

在最终代码上执行：

```powershell
npm.cmd run typecheck --workspace @hide-nest/nest-console
npm.cmd run lint --workspace @hide-nest/nest-console
npm.cmd run test --workspace @hide-nest/nest-console
npm.cmd run build --workspace @hide-nest/nest-console
```

结果：

| 门 | 结果 |
|---|---|
| typecheck | PASS |
| lint | PASS |
| test | PASS，1 个测试文件，12/12 用例通过 |
| build | PASS，Vite 238 modules transformed |

## 5. 真实浏览器门

最终代码上执行现成脚本：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\myproject\hide-nest\apps\nest-console\scripts\run-browser-qa.ps1"
```

最终机器结果：`reports/local-v1-read-browser-qa/results.json`，执行时间 `2026-08-13T03:46:07.869Z`。

| 项目 | 1440 | 1024 | 390 |
|---|---:|---:|---:|
| 项目状态 | PASS（8/8） | PASS（8/8） | PASS（8/8） |
| 核心流程 | PASS | PASS | PASS |
| list/detail/drawer axe | 3 次全 0 | 3 次全 0 | 3 次全 0 |

汇总：

- axe 扫描：9 次；serious/critical/moderate/minor = `0/0/0/0`；
- consoleError/pageError = `0/0`；
- externalRequests = `0`；Authorization header = `0`；
- writeRequests = `0`；
- localStorage/sessionStorage/IndexedDB/Cache/Service Worker = `0/0/0/0/0`；
- evidenceRequests = `9`，对应每个视口的 ACTIVE 首次打开、关闭后重开、ARCHIVED 单证据打开共 3 次；详情初次打开前断言为 0；
- 关闭抽屉后证据 DOM 消失，查询 cache 清除；重开精确新增 1 次证据请求；
- failedCriteria 为空。

最终截图保存在 `reports/local-v1-read-browser-qa/screenshots/`：

- 1440：`01-list.png`、`02-detail.png`、`03-drawer.png`、`04-detail-lower.png`；
- 1024：`01-list.png`、`02-detail.png`、`03-drawer.png`、`04-detail-lower.png`；
- 390：`01-list.png`、`02-detail.png`、`03-drawer.png`、`04-detail-lower.png`、`05-detail-actions.png`。

## 6. 肉眼视觉对比

人工打开并逐项比较了最终 1440／1024／390 detail、detail-lower、drawer 截图与以下已验收原型截图：

- `docs/frontend/prototype/screenshots/desktop-memory-archive.png`
- `docs/frontend/prototype/screenshots/desktop-complete-evidence.png`
- `docs/frontend/prototype/screenshots/mobile-memory-detail.png`
- `docs/frontend/prototype/screenshots/mobile-complete-evidence.png`

逐项结论：

| 对比项 | 结论 | 人工检查说明 |
|---|---|---|
| 布局 | PASS | 1440 恢复左导航、档案列表、详情正文三段并列；1024 导航压缩但详情层级完整；390 列表／详情分屏并可返回。 |
| 层级 | PASS | 页面大标题保持斜体；详情标题为非斜体衬线大标题；摘要／正文、分隔线和留白与原型层级一致。 |
| 动作区 | PASS | 右上／移动纵向折叠均为修正通栏、归档与隔离并排、永久删除独立；390 由 `05-detail-actions.png` 单独确认。 |
| 定义条 | PASS | 精确为规范状态、类型、视角、证据、当前版本；1440 五列、1024 三列折行、390 两列折行，分隔正常。 |
| 证据区 | PASS | 粉色时间线／书脊、标题、副说明和“查看完整证据”位置恢复；未点击只显示真实消息边界，不显示假正文。 |
| 治理边界 | PASS | 1440／1024 为右侧窄栏，390 为纵向折叠；未接入字段诚实显示，不再由整块“只读纵切”提示替代。 |
| 响应式 | PASS | 三视口无遮挡、无横向溢出；长正文自然换行；390 动作、定义、证据、治理均有独立滚动视觉证据。 |
| 完整证据抽屉 | PASS | 1440／1024 为右侧抽屉，390 全宽可滚动；hide 左、小林右，长消息无截断，关闭入口可用。 |

## 7. 范围与 Git 审计

- 原型三文件结束 hash：3/3 MATCH；
- `docs/frontend/prototype/**` diff：0；
- 本工单越界修改：0；
- staged：0；
- Git 写操作：无；
- 网络访问／依赖安装：无；
- 工作区既有 Task30 改动：全部保留，未清理、覆盖或回退。

## 8. 验收摘要

```text
视觉回归状态：LOCAL_V1_READ_DETAIL_PROTOTYPE_REGRESSION_READY_FOR_HIDE_REVIEW
原型三文件 hash：3/3 MATCH
桌面结构／动作区／定义条／证据区／治理边界：PASS/PASS/PASS/PASS/PASS
单行去重／多行拆分：PASS/PASS
未接线动作零写请求／可访问反馈：PASS/PASS
前端 typecheck-lint-test-build：PASS/PASS/PASS/PASS
真实浏览器 1440-1024-390：PASS/PASS/PASS
axe 9次 serious-critical-moderate-minor：0-0-0-0
console-pageerror-external：0-0-0
浏览器持久存储：0
证据按需读取与关闭清除：PASS
越界／staged：0/0
联网／Git写操作：否/否
报告路径：D:\myproject\hide-nest\reports\LocalV1-ReadVertical-PrototypeRegression-执行报告.md
```
