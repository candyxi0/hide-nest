# Task33B1｜Local V1 永久删除 Console 安全接线底座 — 执行报告

## R1 完成回复（Task33B1-R1 安全边界封口）

```text
返工状态：LOCAL_V1_PERMANENT_DELETION_CONSOLE_HOST_R1_READY_FOR_REACT_ORDER
POST missing-null-evil-origin／同源：全部REJECTED／PASS
confirm capability到达条件：ONLY_EXACT_SAME_ORIGIN_POST
index-SPA symlink逃逸：REJECTED
problem+json请求／响应：REJECTED／PASS
upstream timeout／残留：PASS／0
readiness行／秘密泄漏：PASS／0
Codex Adapter／Node四门：PASS/PASS
Maven／OpenAPI：NOT_RUN_UNCHANGED_FROM_B1/NOT_RUN_UNCHANGED_FROM_B1
Java-契约-generated-migration-React-prototype修改：0-0-0-0-0-0
真实Git index：未变
工作区：tracked=11、untracked task files=2、QA临时文件=1、staged=0、越界=0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionConsoleHost-执行报告.md
```

## R1｜5 个精确缺口封口

1. **R1-01 POST 精确同源 Origin**：`originAllowed(origin, method)` 改为 POST 必须携带逐字等于 console origin 的 `Origin`；missing/`null`/evil/大小写/端口不一致均本地 403 且 upstream 到达数 0；GET/HEAD 无 Origin 放行、携带则仍须精确同源；不接受 Referer 替代。新增真实 HTTP 反证（preview/confirm）。
2. **R1-02 首页/SPA symlink 边界**：页面路由改用 `safeStaticFile(staticDir, "/index.html")`，与普通资产共用同一 realpath 根边界；`index.html` 指向根外的 symlink 时请求 404。symlink 测试用 `it.skipIf(!SYMLINK_SUPPORTED)` 显式 skip，可创建环境真实攻击（本机无 symlink 权限 → 1 skipped）。
3. **R1-03 请求/响应 JSON 分离**：POST 请求仅接受 `application/json`（允许 charset 参数）；`application/problem+json` 作为请求 415 且不达 upstream；upstream 响应继续允许两者。
4. **R1-04 上游有界超时**：fetch 增加 `AbortSignal.timeout(timeoutMs)`，正式默认 10000ms，可注入更短正整数且不来自浏览器请求；超时净化为稳定 504，正文无 token/capability/canary；新增 hung-upstream 真实测试。
5. **R1-05 端口 0 可发现**：`formatReadiness(bindHost, port)` 纯函数 + `runConsole(env, writer)` 可注入 writer；成功绑定输出单行 `HIDE_NEST_CONSOLE_READY http://127.0.0.1:<port>`（IPv6 用方括号）；不输出 API origin/bearer/capability/静态绝对路径/环境变量。

## R1 验证

- Codex Adapter typecheck/lint/test/build 全部 PASS（71 passed / 1 skipped symlink）。
- 全仓 Node typecheck/lint/test/build 全部 PASS。
- Maven / OpenAPI 本轮未重跑：`NOT_RUN_UNCHANGED_FROM_B1`。
- B1 的 11 tracked + 2 task untracked 路径集合未扩张（另有既有 QA 临时目录）。
- `git diff --check` 仅剩官方生成 `MemoryDetail.ts:62` trailing whitespace；R1 文件（console.ts / console.test.ts）无 trailing whitespace。
- staged=0、越界=0、Git index 起止一致；无联网、无遗留进程。

---

## B1 完成回复

```text
实施状态：LOCAL_V1_PERMANENT_DELETION_CONSOLE_HOST_READY_FOR_HIDE_REVIEW
MemoryDetail policy revision／真实数据库来源：PASS/PASS
静态宿主／SPA／安全响应头：PASS/PASS/PASS
API白名单／浏览器凭据剥离／host凭据注入：PASS/PASS/PASS
confirm独占capability／其他路径capability：PASS/0
非法origin-host-path-method-size-redirect：全部REJECTED
OpenAPI check／Maven clean verify／Node四门：PASS/PASS/PASS
V001—V016／database generated／React-prototype：MATCH/MATCH/MATCH
正文-token-capability-secret泄漏：0-0-0-0
真实Git index：未变
工作区：tracked=11、untracked=3、staged=0、越界=0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionConsoleHost-执行报告.md
```

## 冻结基线核验

- 仓库：`D:\myproject\hide-nest`，HEAD = `f5f0051`（未变）。
- 起始工作区仅含既有 `reports/local-v1-read-browser-qa/.playwright-artifacts/`，该目录全程保留未动。
- 全程离线（Maven `-o`、无依赖安装）；console host 仅用 Node 24 内建模块（`node:http`/`node:fs`/`node:path`/`node:url`/`node:net` + 全局 `fetch`）。
- Markdown 按 UTF-8 读取。

## A｜删除预览只读事实（currentPolicyRevisionNo）

1. **正式 OpenAPI / baseline / inventory**：`MemoryDetail` 增加必填字段 `currentPolicyRevisionNo`（`type: integer, format: int64, minimum: 1`）。
   - `contracts/openapi/hide-nest-api.yaml` 与 `contracts/compatibility-fixtures/baseline.yaml` 保持字节一致。
   - `contracts/inventory/ContractInventory-HDM-003-v0.1.json` 的 MemoryDetail 条目补充字段说明。
2. **生成同步**：`scripts/generate.ps1`（正式生成脚本，离线 Maven `generate-sources`）同步 Java（`modules/contracts/target`，不入库）与 TS（`packages/api-client-ts/src/generated`）。仅 `MemoryDetail.ts` 变化（新增 `currentPolicyRevisionNo: number`），未手改生成文件。
3. **S2B 真实读取**：
   - `LocalV1S2BMemoryDetail` 增加 `Long currentPolicyRevisionNo`。
   - `LocalV1S2BQueryCoordinator.getMemoryDetail` 从 `current.record().currentPolicyRevisionNo()`（即 `memory_record.current_policy_revision_no` 经 `JooqMemoryReadAdapter` 读取的真实值）返回；未写死、未用 `revisionNo` 冒充。
   - `LocalV1MemoryResponseMapper.detail` 透传该字段到生成的 `MemoryDetail`。
4. **契约与真实 PostgreSQL 测试**：
   - `ReadApiContractTest` 更新 MemoryDetail 属性集/必填集/`int64`/`minimum:1` 断言及 mutation 必填块。
   - `LocalV1S2BMemoryQueryTest.currentPolicyRevisionComesFromDatabaseNotRevisionNo`：经受控治理流程把策略版本升到 7，断言 `detail.currentPolicyRevisionNo()==7` 且 `detail.revisionNo()==1`；`damagedCurrentPointerAndOwnerBindingFailClosed` 继续 fail closed。
5. 未改 V001—V016、未改数据库 generated tree；operation/failure code/event type/RunPhase 仍为 30/50/12/5。

## B｜最小 console host（apps/codex-adapter/src/console.ts）

- `createConsoleHost(config)`（可测试、可确定性关闭）+ `runConsole()`（组装配置与生命周期）已实现。
- **B1 启动输入**：apiOrigin 仅接受 `http://127.0.0.1:<port>` 或 `http://[::1]:<port>`（拒绝域名/非 loopback/userinfo/路径/query/fragment/缺端口）；bind 仅 loopback（默认 127.0.0.1，端口允许 0）；staticDir 启动前校验存在；bearer/capability 高熵校验，缺失即启动失败。所有值仅来自进程环境变量，不写入 bundle/HTML/URL/磁盘/日志。
- **B2 静态宿主**：生产 `dist`；资产缺失 404；SPA fallback 仅无扩展名页面路由；防路径/编码/目录/symlink 逃逸；HTML/API 均 `Cache-Control: no-store`；CSP `default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'`；`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`；Host/Origin 精确匹配 console 自身 loopback origin（拒绝 open proxy/DNS rebinding）。
- **B3 /v1 精确白名单**：仅 `GET /v1/memories`、`GET /v1/memories/{uuid}`、`GET /v1/memories/{uuid}/evidence`、`POST /v1/deletion-previews`、`POST /v1/deletion-previews/{uuid}/confirm`、`GET /v1/deletion-runs/{uuid}`；其余 `/v1/**`、其他 method、非 UUID 动态段本地拒绝不转发。丢弃 `Authorization/Cookie/Proxy-*/Forwarded/X-Forwarded-*/X-Action-Capability`；注入 host bearer；仅 confirm 精确路径注入 `X-Action-Capability`；`Idempotency-Key` 仅 UUID；POST 必须 `application/json`；请求体 ≤64 KiB；GET 不转发体；响应 ≤5 MiB；不跟随 redirect；上游 redirect/非 JSON/连接错误净化为稳定无正文泄漏的 502；不记录任何请求体/响应体/token/capability/正文/数据库 URL/payload 路径；拒绝 CONNECT/WebSocket。浏览器始终看不到 bearer/capability，未在 `vite.config.ts` 注入秘密。
- `entries.test.ts` 精确调整：hook/doctor 仍 sentinel，console 变为真实 loopback host 入口。

## C｜测试门（apps/codex-adapter/src/console.test.ts，Node 内建临时目录 + 假 loopback upstream）

1. 静态首页/资产/SPA fallback/404 正确（不触达 upstream）。
2. 路径穿越/编码穿越/symlink 逃逸拒绝（无越界文件泄漏）。
3. 六条白名单逐条可达；非白名单/错误 method/非 UUID 段不到达 upstream。
4. 浏览器伪造 Authorization/Cookie/capability/Proxy/Forwarded 全剥离。
5. upstream 仅收到 host bearer；仅 confirm 收到 capability；preview/read 收不到。
6. Idempotency-Key/JSON/body 64KiB/response 5MiB/redirect 门 fail closed。
7. 非 loopback api origin 与恶意 Host/Origin 拒绝。
8. token/capability/正文 canary 在净化响应与静态内容中为 0（宿主不写 stdout/stderr）。
9. host 确定性关闭（幂等、端口可复用、句柄无残留）。
10. `currentPolicyRevisionNo` 契约（ReadApiContractTest）+ 数据库来源（LocalV1S2BMemoryQueryTest）+ 生成客户端（MemoryDetail.ts）一致。

## D｜质量门（依次执行，全部通过）

| 门 | 命令 | 结果 |
|---|---|---|
| D1 | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/generate.ps1 -Check` | `GENERATION_CHECK_PASS` |
| D2 | `mvnw.cmd -o clean verify`（JAVA_HOME=Temurin 25） | `BUILD SUCCESS`（271+ 全绿，含真实 PostgreSQL） |
| D3 | `npm run typecheck` | PASS（4 workspace） |
| D4 | `npm run lint` | PASS（0 error；2 个既有 generated events 警告） |
| D5 | `npm run test` | PASS（codex-adapter 67 / nest-console 12 / fixtures 10） |
| D6 | `npm run build` | PASS |

核验：
- V001—V016、database generated、prototype 三处 hash MATCH（未在 git diff 中）。
- React `App.tsx/app.css` 改动 0。
- 浏览器持有 Authorization/capability/storage secret 实现残留 0（`App.test.tsx` 断言浏览器不发送 Authorization）。
- Git index 起止一致：staged=0，越界=0。
- 无新增 Docker 残留（Testcontainers `stop()` 清理）与进程残留（host/upstream `close()` 清理）。

## 允许路径核验

改动仅落在允许路径内：正式 OpenAPI/inventory/baseline 及正式生成结果、S2B 领域模型/coordinator/mapper 与对应测试、`apps/codex-adapter/src/console.ts` 与 `console.test.ts`、`entries.test.ts`、本报告。未改 migration、database generated、React、prototype、MCP closeout、冻结故障码集合。未 stage/commit/push。

完成即停，未实施 React Task33B2。
