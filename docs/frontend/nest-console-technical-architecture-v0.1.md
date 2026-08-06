# nest console｜前端技术架构决定 v0.1

`状态：IMPLEMENTATION_READY_DRAFT`

`形成时间：2026-08-06`

## 1. 决定

V1 治理控制台采用：

- React＋TypeScript 的客户端单页应用；
- Vite 构建静态资源；
- React Router Declarative Mode；
- TanStack Query 管理远端 server state、轮询和 mutation 失效；
- Radix Primitives 只提供 Dialog／AlertDialog／Popover 等无样式可访问行为；
- CSS Modules＋原生 CSS custom properties／layers，建立 nest 自己的设计系统；
- OpenAPI 3.1.2 生成的 TypeScript 类型和 Fetch 客户端；
- Vitest＋Testing Library＋MSW；Playwright＋axe 做 E2E、视觉与可访问性；
- npm workspaces 与现有 Node.js 24 LTS 基线共用锁文件；精确版本必须由 CandidateManifest 验证后锁定。

不采用 Next.js／SSR、Tailwind、Redux、JWT、Cookie Session、WebSocket、GraphQL 或独立公网前端服务。

## 2. 运行位置

### 2.1 选择：本地控制台

console 静态资源打包进 CodexAdapter，由新的 `console` 入口仅绑定 loopback 地址提供。浏览器请求同源 `/v1/*`，由 Adapter 代理到远端 Java 核心。

```text
Browser
  │  http://127.0.0.1:<ephemeral-port>
  ▼
CodexAdapter console host
  ├─ static nest console assets
  ├─ loopback origin / CSRF / launch nonce checks
  ├─ DPAPI-protected remote credential access
  └─ no-body-log HTTPS proxy
        │
        ▼
     Caddy :443 → Java API → PostgreSQL / PayloadStore
```

结果：

- 浏览器不持有 DeviceCredential、IdentitySession 或 ActionCapability 原值；
- 不需要登录页、Cookie、JWT、Redis 或第三个远端容器；
- 管理面随小林的设备存在，远端核心仍是规范事实唯一来源；
- 小林设备离线时可以看明确标时的最后安全摘要，但不能执行治理写操作；
- 手机远程管理不进入 V1。

### 2.2 本地入口安全

- 仅绑定 `127.0.0.1`／`::1`，启动失败不得回退到 `0.0.0.0`；
- 默认使用临时高位端口；端口冲突重新选择，不静默公开；
- 每次启动生成短期 launch nonce，放入首个 HTML 响应内存上下文，不写 URL、日志或磁盘；
- mutation 请求必须同时通过 same-origin、允许的 `Origin`、launch nonce 和本地 session 检查；
- Adapter 代理层注入远端凭据，剥离浏览器提供的 `Authorization`／`X-Action-Capability`；
- HTML／API 返回 `Cache-Control:no-store`；Service Worker 禁用；
- CSP 默认 `default-src 'self'`，禁止任意远端脚本、frame 和对象；
- 退出 console 进程即失效本地 session，并清空内存查询缓存。

具体 user governance credential／capability 签发仍由 HDM-007／008 决定；console 不创造新的身份权力。

## 3. 为什么选择 React SPA

治理界面包含列表－详情联动、URL 状态、多个异步 Run、stale／denied／degraded 分支、删除预览和焦点管理。组件与显式状态比服务端模板更容易形成可测的状态矩阵。

React 官方文档把组件作为组合 UI 的基本单位，并正式支持 TypeScript；Vite 提供 React／TypeScript 模板与独立类型检查路径。React Router 的 Declarative Mode 适合已有独立数据层的 SPA；TanStack Query 专门处理异步 server state 的获取、缓存、同步与失效。Radix Primitives 是无样式、可定制的可访问行为层，不会把 nest 做成组件库默认皮肤。

官方依据：

- React TypeScript：https://react.dev/learn/typescript
- React versions：https://react.dev/versions
- Vite guide：https://vite.dev/guide/
- Vite TypeScript：https://vite.dev/guide/features.html#typescript
- React Router modes：https://reactrouter.com/start/modes
- TanStack Query overview：https://tanstack.com/query/latest/docs/framework/react/overview
- Radix accessibility：https://www.radix-ui.com/primitives/docs/overview/accessibility
- Playwright accessibility：https://playwright.dev/docs/accessibility-testing

## 4. 被拒方案

### 4.1 Thymeleaf＋HTMX

优点是 Java 单栈和部署简单；拒绝原因是本系统的复杂状态、轮询、并排档案体验和组件级故障矩阵会逐渐形成大量局部模板协议，且不能与 TypeScript Adapter／OpenAPI 客户端共享类型。

### 4.2 远端公开 SPA

优点是任意设备访问；拒绝原因是会立即引入用户登录、浏览器凭据保存、Cookie／CSRF 或额外 OAuth 设计，扩大 V1 公网攻击面，并与已确认“无登录页、无 Cookie Session”冲突。

### 4.3 Next.js／React Router Framework Mode

V1 不需要 SSR、SEO、server actions 或 Node 远端运行时。引入全栈框架会产生第二个远端应用层和重复鉴权边界。

### 4.4 Tailwind／成品后台组件库

拒绝原因不是工具能力，而是本项目已有明确的编辑式视觉语言；大量 utility 与预设 Dashboard 组件会让实现轻易退回通用 AI 后台皮肤。V1 使用 CSS Modules 与设计 token，Radix 只提供行为。

### 4.5 Redux／Zustand

远端事实由 TanStack Query 管理；局部 UI 状态由 React 和 URL 管理。没有证据需要新的全局 client-state store。

## 5. 源码布局

由 HDM-001 最终锁定绝对路径，建议逻辑布局：

```text
apps/
  api/                         Java API entry
  worker/                      Java Worker entry
  codex-adapter/               Node/TS hook, mcp, doctor, console host
  nest-console/                React/TS/Vite source
packages/
  api-client-ts/               OpenAPI generated; do not hand edit
  ui-contract-fixtures/        synthetic API fixtures shared by tests
modules/                       Java domain/application/adapters
docs/frontend/                 this spec + prototype
```

`nest-console` 只依赖 `api-client-ts` 和自身组件；不得导入 Codex transcript 类型、DPAPI 实现或 Java 生成源码。Adapter 只把静态资源作为构建产物提供，不把 UI 组件变成安全边界。

## 6. 前端分层

```text
app-shell
  ├─ routes
  ├─ feature/memories
  ├─ feature/reviews
  ├─ feature/runs
  ├─ feature/status
  ├─ shared/api          generated client adapter
  ├─ shared/security     no-secret request facade
  ├─ shared/ui           nest primitives and tokens
  └─ shared/testing      MSW scenarios
```

规则：

- route 组装页面，不写治理规则；
- feature 调用生成客户端，不拼 URL；
- 组件不得根据按钮文字猜 failureCode；
- UI 的乐观更新只用于无风险筛选等本地状态；归档、隔离、恢复和删除以服务端响应为准；
- `DENIED`、`STALE`、`FAILED` 是互斥显式分支，不折叠成通用 toast；
- 原文／证据响应只在内存呈现，离开详情即移除 query cache；
- 不使用 localStorage／IndexedDB 保存正文、搜索词、证据或 token。

## 7. 数据获取策略

### 7.1 Query

- 列表 key 包含规范化 filter 和 cursor；
- 详情 key 包含 memoryId＋currentRevision；
- evidence query 默认禁用，用户展开时按需请求，`gcTime:0`；
- Run 只在非终态轮询；后台标签页降低频率，终态立即停止；
- 只对 `retryable=true` 的网络／依赖故障重试；DENIED 和 STALE 不自动重试；
- mutation 成功后依据响应中的 revision 精确失效，不做全站 cache 清空；
- 离线不自动排队治理写操作，防止过期 revision 恢复联网后误执行。

### 7.2 URL 与本地状态

- 搜索和筛选保存在 URL search params，搜索正文在提交前只保存在内存；URL 使用短 query，不放敏感全文；
- 列表滚动位置放 React memory state，不写持久化存储；
- Dialog、选中 tab、展开行是局部状态；
- 主题 V1 只有亮色，不提供仓促反色的 dark mode。

## 8. 设计系统实现

- CSS token 使用 `oklch()`，同时提供 sRGB fallback；
- spacing 以 4px 基础、8px 主节奏；
- border radius 只用 0／4／8／12 四档；
- shadow 只用于浮层和抽屉；内容层级优先靠分隔线与排印；
- 组件最小集：Button、TextField、Select、StatusTag、ArchiveRow、DefinitionList、Timeline、Drawer、Dialog、AlertDialog、Toast、Skeleton、ProblemPanel；
- dangerous action 由一个统一 `ImpactPreview` 模式承载，不能每个页面自己发明确认交互；
- 不把 prototype 的合成数据或 demo state switch 编进生产包。

## 9. 构建与发布

### 9.1 开发

- `npm ci` 校验唯一 lock；
- Vite dev server 只用于本地开发，并代理到合成 MSW 或本地 Adapter；
- `tsc --noEmit` 与 Vite build 分开运行，因为 Vite 只转译、不承担完整类型检查；
- generated client 由 OpenAPI 单一来源产生，两次生成必须无 diff。

### 9.2 生产

- console 生成带内容 hash 的静态 assets；
- Adapter 包含静态清单 hash，启动时校验；
- 不从 CDN 动态加载 React、字体、图标或脚本；
- 字体使用经过子集化的开源文件随包发布；
- sourcemap 不进入默认生产包，内部调试包单独控制；
- Caddy 不直接提供 console；远端仍只提供 API。

## 10. 测试策略

### 10.1 单元／组件

- resultCategory／failureCode→界面分支；
- status tag 与危险文案；
- stale 不重放旧 mutation；
- denied 不渲染对象标题；
- Run phase 不倒退；
- evidence cache 离页清除；
- keyboard／focus return／reduced motion。

### 10.2 合约

- OpenAPI generated client 与 MSW fixtures 同源；
- unknown enum、unknown field、缺 requestId 必须测试失败；
- Problem Details 不得包含正文 canary；
- Adapter proxy 必须剥离浏览器伪造的 credential headers。

### 10.3 E2E

- 列表→详情→证据；
- 归档与恢复；
- 隔离→预览→恢复；
- 删除预览→一次确认→Run FAILED／COMPLETED；
- ReviewSession 返回对话／取消；
- offline、denied、stale、degraded；
- 1440×900、1024×768、390×844；
- Playwright＋axe 自动扫描后，再做键盘与屏幕阅读器人工检查。

## 11. 性能预算

这些是工程预算，不是产品 SLA：

- 首次加载压缩 JS ≤250KB、CSS ≤80KB、字体总传输 ≤500KB；
- 首屏不下载 evidence payload；
- 2,000 条合成列表仍只渲染视窗附近内容；是否引入虚拟列表由实际测量决定；
- 页面切换不触发全量 refetch；
- 低性能设备上禁用非必要 paper texture 和位移动效。

超过预算先记录 bundle analyzer 证据，不为了数字删除可访问性或安全组件。

## 12. 精确版本门

本文只确定技术族，不把 2026-08-06 的最新 patch 直接锁为生产版本。HDM-001／002 必须在 CandidateManifest 中记录候选，并完成：

- Node 24 LTS 兼容；
- React／Vite／Router／Query／Radix peer dependency；
- license 与供应链扫描；
- build、typecheck、component、E2E；
- generated client 和 Adapter 打包；
- 锁文件无未解释变化。

验证通过后才能称为精确依赖基线。

