# Task30B｜Local V1 记忆只读纵切 P0—P3 执行与续接报告

## 结论

目标状态：`LOCAL_V1_READ_VERTICAL_PARTIAL_INTEGRATION_READY_FOR_HIDE_REVIEW`

按 2026-08-12 用户最新范围指令，本轮保留既有成果，只完成并验证已经开始的 P0—P3；P4 未启动。契约、S2B 只读投影、三条 Java GET API、生成 TypeScript client 与 React 记忆档案页已经形成可编译、可测试的闭环。Java API targeted 通过真实 PostgreSQL 18、V001—V015、临时 LocalPayloadStore 与随机 loopback HTTP 端口验证；React 页面使用生成 client，并通过 workspace 静态、单元和构建门禁。

本状态不是浏览器端到端 PASS。全仓离线 `clean verify` 仍被 database-adapter 中 8 个既有迁移数量硬编码断言阻断；P3 的 prototype QA、生产页面真实浏览器 390px 与 axe 门未启动；P4 loopback console host 与 Playwright E2E 按用户指令完全未开始。

## 冻结输入

- 基线 HEAD：`bedba3279cc9d8a9edf8931b767614d8cb3b3ec8`
- 工单 SHA-256：`9e4affd4db91b092a2b3624f2552cfbfcc633babed56876587b6b1745d7836a6`
- JDK：`C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot`（所有最终 Maven 命令均显式设置 `JAVA_HOME`）
- 产品 UX：`7938ce4875eced868aee2c4929aa93f2d35878f5fde2ff380fdd22a2d94b0850`
- 技术架构：`f52944a1bf8b89a0a60f229368a7acd1ae7aedf211a7445a997c169d16146d7d`
- 验收矩阵：`0b72c5c5e3c1b3e4da9db560a07924ea0dfe1a4bbcdf93ce91ff5e76ade7249b`
- 交付清单：`8ddaf55d00b308fe56d9043914772722b0c77aa2c419147519402c1e92747fe4`
- API addendum：`70ba29e3bdce62bc64b9d78675a4b506b8633569aef9dc22cd8cb3d374302a1f`
- prototype `index.html`：`3940e0b43331540a795ce96fb4644bd849a01fb5b31559adcb4fd6d60b53b260`
- prototype `app.js`：`4574d4cf15b374ce9261151f6599b9ee60abb4660ca301c08aec952f5a361296`
- prototype `styles.css`：`8d9fa48c252393b7cd98466fa41c27ed61e7b03758ddcc7d5bbbea0d6f7d90fe`

上述 Markdown 均按 UTF-8 完整读取。prototype 只作为设计输入；其源码、权威文档和截图未改动。

## 阶段结果

### P0｜Task30A 契约：PASS

- `MemoryDetail` 精确 10 字段、9 required；`MemoryEvidenceItem` 精确 8 字段且全 required。
- detail/evidence wrapper 使用命名 `$ref`；生成 Java/TypeScript 不再以模糊 Object 表达这两个模型。
- operation/failure/event 数量：30/50/12。
- spec 与 compatibility baseline 字节一致；当前共同 SHA-256 为 `efdde78a198c8f356aaa771f7036fbc7ef406837afed2a83f752d610b0f04ce7`。
- Task30A 既有允许成果全部保留；生成 client 类型检查 PASS。
- `mvnw.cmd -o -pl modules/contracts -am test`：exit 0，46/46。
- `scripts/generate.ps1 -Target openapi -Check`：exit 0，`GENERATION_CHECK_PASS`；生成器自己的 mutation canary 输出 DIFF 后仍正确判定最终 PASS。
- `npm.cmd run typecheck`（`packages/api-client-ts`）：exit 0。

### P1｜S2B 投影与三条只读 API：PASS

实现且只公开：

1. `GET /v1/memories`
2. `GET /v1/memories/{memoryId}`
3. `GET /v1/memories/{memoryId}/evidence?revisionId=`

主要收口项：

- S2B 列表/详情补齐 `currentRevisionId`、`uncertaintyCode`、evidence count 与正式完整读取得出的 source availability。
- list 的 `query/state/cursor/limit` 真实生效；不支持的 `memoryType/perspectiveActorId/sourceAvailability` 明确返回 422，不静默忽略。
- 当前 revision 绑定、deletion fence、actor、anchor/source unit、payload metadata/hash/UTF-8 全部 fail closed。
- 证据稳定顺序修复为：relation `createdAt`，同时间戳先按 anchor unit ordinal，再以 relation/source-unit ID 作最终稳定键。该修复消除了固定时钟下随机 relation UUID 导致的偶发乱序，未改写协调器、migration 或数据库语义。
- `isolated=false` 仅为 `LOCAL_V1_DERIVED_FALSE`；未冒充隔离功能已经接通。
- `local-v1-synthetic` 只允许 loopback、高熵临时 bearer、常量时间比较与三条 GET；默认 profile fail closed，非 loopback 启动失败。
- 成功与 Problem 均 `Cache-Control: no-store` 且带同请求链 requestId。

### P2｜真实 PostgreSQL + HTTP：PASS

- `mvnw.cmd -o -pl apps/api -am -Dtest=LocalV1ReadContractTest,LocalV1ReadHttpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：最终 exit 0，10/10。
- 其中 API 机械契约 4/4，真实 HTTP-DB 6/6。
- 使用 PostgreSQL 18 Testcontainers、V001—V015、S1/S2B 合成写入路径、临时 payload root 与随机 loopback HTTP 端口；没有用 controller mock 代替传输层。
- 覆盖无 token/错 token、三端点 200、query/state/paging/cursor、422、当前 revision、Unicode title/summary、hide/小林/hide 三消息顺序、错 memory/revision、deletion fence、payload 缺失/篡改、no-store/content-type/requestId、默认 profile 与非 loopback fail closed。
- 缺文件和 hash 篡改 Problem 新增逐正文零泄漏断言。
- 最终 Surefire 报告扫描：正文样本、objectRef、contentHash、绝对临时 payload root 前缀、synthetic token、secret、未选闲聊 canary、JDBC URL 均 0 命中。测试期 `logback-test.xml` 将第三方 Testcontainers/Flyway INFO 降到 WARN；不改变生产日志语义。
- Testcontainers/Ryuk 已退出；没有本轮遗留容器。

### P3｜React 记忆档案页：PASS_STATIC；浏览器验收 DEFERRED

- 页面使用 React 19、TypeScript、Vite、React Router、TanStack Query、Radix 与 `@hide-nest/api-client-ts`；没有新依赖、没有手写第二套响应类型。
- 同源 base path `/v1`；浏览器代码不设置 Authorization。
- 列表、搜索、全部/有效/归档筛选、详情、证据预览、按需完整证据抽屉，以及 loading/empty/offline/denied/not-found/integrity failure 分支已实现。
- evidence 只在打开抽屉后请求，`gcTime: 0`；关闭抽屉/离开详情时删除 Query cache。
- hide 左、小林右、未知 actor 中立单列；单消息使用原文形态；完整证据文案明确限定为本条记忆已保存的全部最小必要证据。
- 未使用 localStorage/sessionStorage/IndexedDB/Service Worker 保存正文、查询或 token；不打印 API body。
- `apps/nest-console`：typecheck/lint/test/build 全部 exit 0；Vitest 9/9；生产 build 238 modules，JS gzip 102.26 kB、CSS gzip 3.95 kB。
- 根 workspace：typecheck/lint/test/build 全部 exit 0；Vitest 合计 21/21；lint 0 error、2 个 codex-adapter 既有生成文件 unused-disable warning。
- `git diff --check`：exit 0。根因是 `MemoryEvidenceResponse` 四个字段缺少说明，OpenAPI Generator 7.24.0 因而生成空的 `* ` JSDoc 行；已在 spec 与字节同步 baseline 中补齐语义不变的最小说明，再通过既有脚本重建完整客户端。最终 generation check、contracts 46/46、client typecheck 与 diff-check 同时 PASS。

## Maven 门禁

### 已通过

- 全 reactor 离线编译/打包：`mvnw.cmd -o -DskipTests package`，exit 0，12/12 modules。
- P0 contracts：46/46。
- P2 targeted API：10/10。
- 精确架构边界：`ArchitectureTest,DatabaseBoundaryTest,PortBoundaryTest`，exit 0，24/24；body-text scan 39 files，hash `281bbd554fb32f67b68962f98ea70cf53f041e204931216ece9f27870ffdcbcb`。

### 全仓 clean verify：FAIL（精确未完成项）

`mvnw.cmd -o clean verify` 进入 database-adapter 后 exit 1：Tests run 170，Failures 2，Errors 6。失败均为既有测试对当前 V001—V015 的迁移数量硬编码，不是本纵切编译、API 或契约失败：

1. `LocalV1S3ADeletionPreviewTest.migrationUpgradeAndRepeatAreExact`：expected 3，actual 5。
2. `LocalV1S3B2ADeletionConfirmationTest.migrationCounts`：expected 0，actual 2。
3. `DatabaseSliceC2AEvidenceMemoryAdapterTest.setUp`：expected 11，actual 15。
4. `DatabaseSliceC2BRuntimeAdapterTest.setUp`：expected 11，actual 15。
5. `DatabaseSliceD1OutboxMechanicsTest.setUp`：expected 11，actual 15。
6. `LocalV1S1WindowCloseTest.setUp`：expected 11，actual 15。
7. `LocalV1S2BMemoryQueryTest.setUp`：expected 12，actual 15。
8. `SliceCCoordinatorTest.setUp`：expected 11，actual 15。

按“停止扩展范围”指令，本轮没有修改这些旧测试、migration 或删除语义。全量 reactor 因 database-adapter fail-fast，contracts/api/worker/architecture-tests 在该命令内未运行；它们所需的 P0—P3 精确门禁已分别独立通过。

## 保护面与资源

- V001—V015：15 个文件，工作区相对 HEAD 零差异，MATCH。
- database generated tree：零差异，MATCH。
- 正式写链 `LocalV1S1WindowCloseCoordinator` / `CanonicalPublishCoordinator`：零差异，MATCH。
- 正式 failure/event 枚举：未修改。
- `package-lock.json`：零差异；未运行 `npm ci`，未下载依赖或浏览器。
- Docker 收口：仍为开工时 4 个既有容器（3 个长期运行 pgvector、1 个旧 exited tduck）、41 个既有 volumes、3 个默认 networks；本轮新增差值 0/0/0。
- 工作区 Java/Node/Maven/npm 遗留进程：0。
- 未执行 stage/commit/push/amend/reset/checkout；staged=0，`git diff --cached --exit-code=0`。
- 原始 `.git/index` SHA-256：起始 `f1f77d2978c60a156c56b527ae7b3cf6ef2fc07bc6cd4edec8c38e15859c297b`，结束 `d27b57433604825c828bedf8e7d4ef92366f5ec4f43d322dac9fbb062d5985c8`，因此 raw hash 门是 MISMATCH。只读 Git 状态检查可刷新 index stat cache；语义证据仍为 cached diff 0、staged 0。一次尝试 `git write-tree` 计算语义树时被权限拒绝，未生成 index.lock、未改变 staged 内容。不得把 raw hash 伪报 MATCH。
- 允许路径外源文件变化：0。

## 未完成项与精确续接顺序

以下均未在本轮开始或完成：

1. 修复上述 8 个 database-adapter 旧迁移计数断言，并重跑全仓离线 `clean verify`；修复不得修改 V001—V015。
2. P3 prototype QA：既有脚本会写权威 prototype 下的截图和时间戳结果，本轮为避免越界写入未执行。续接时应在临时副本/临时输出目录运行，保持 `docs/frontend/prototype/**` 零差异。
3. P3 生产页真实浏览器可访问性与视觉门：1440/1024/390，完整证据不裁断，axe serious/critical=0。当前只有 jsdom/MSW 行为测试与 CSS/构建门，不能冒充浏览器结果。
4. P4 全部：`apps/codex-adapter` loopback console host、代理注入/剥离与安全测试、临时 DB/API/host 编排、Playwright list-detail-evidence、390×844、HAR/console/storage/DOM 泄漏扫描、关闭抽屉后浏览器 Query cache/DOM 证明、合成截图证据。用户明确要求本轮不要开始 P4。
5. 正式身份 HDM-007/008 仍未完成；现有门只能称为 synthetic/local read gate，不是正式 authorization 或生产可用身份。
6. list 的 `memoryType`、`perspectiveActorId`、`sourceAvailability` 授权前精确过滤尚未实现，当前按契约返回 422；这是下一纵切增强，不是静默支持。
7. Git raw index hash 门需在续接任务开始前重新冻结，并对所有 Git 只读命令设置不刷新 index 的方式；当前只能证明 staged 语义为 0。

## 最终摘要

```text
目标状态：LOCAL_V1_READ_VERTICAL_PARTIAL_INTEGRATION_READY_FOR_HIDE_REVIEW
P0 契约／P1 API／P2 真实HTTP-DB／P3 React／P4 loopback-E2E：PASS/PASS/PASS/PASS_STATIC（浏览器门DEFERRED）/NOT_STARTED_BY_USER_SCOPE
三条端点：list/detail/evidence
API targeted／全仓 Maven：10/10 PASS／clean verify FAIL（旧迁移计数断言 2 failures + 6 errors）
前端 workspace typecheck-lint-test-build：PASS/PASS/PASS/PASS（21/21 tests，lint 0 error）
浏览器 list-detail-evidence／390px／axe：NOT_RUN/NOT_RUN/NOT_RUN
浏览器 Authorization／storage正文／完整证据关闭后残留：NOT_MEASURED/NOT_MEASURED/NOT_MEASURED
V001—V015／generated DB／正式写链：MATCH/MATCH/MATCH
Task30A spec-baseline／生成客户端：MATCH/PASS
非预期正文-objectRef-绝对路径-token-secret-闲聊canary 泄漏（API响应/Problem/Surefire）：0/0/0/0/0/0
Git index 起止／staged／越界：RAW_HASH_MISMATCH（semantic cached diff 0）/0/0
Docker新增containers-volumes-networks／遗留进程：0-0-0/0
联网／依赖下载／Git写操作：否/否/否
工作区：tracked=16、untracked files=25、staged=0、越界=0
报告路径：D:\myproject\hide-nest\reports\LocalV1-ReadVertical-执行报告.md
```
