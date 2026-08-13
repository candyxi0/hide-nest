# LocalV1-CodexMCP-执行报告

## 实施状态

**LOCAL_V1_CODEX_MCP_R1_READY_FOR_REAL_API_SMOKE**

> 本报告在 Task32A 未提交成果之上，按 `Task32B-R1` 返工工单精确关闭 4 项局部问题（anchor 半开区间、
> POST/GET 回执绑定、statusUrl 同源-同路径门、动态身份派生碰撞）。Task32A 全部验证结果仍有效，见下文。

## R1 返工摘要（4 项关闭）

| 项 | 变更 | 结果 |
|---|---|---|
| R1-01 | anchor `toOffset` 由 `codePointCount-1` 改为 `codePointCount`（正式半开区间 `[0, n)`）；`"a😀b"` 覆盖 `0..3` | PASS |
| R1-02 | POST=202 后校验 `resultCategory==SUCCEEDED && runId==submissionId && phase==CANONICAL_COMMITTED && statusUrl 非空`；GET=200 后校验 `resultCategory==SUCCEEDED && runId==submissionId==POST.runId && phase==CANONICAL_COMMITTED`；任一不符 fail-closed；`memoryId` 由 `request.submissionId` 派生 | PASS |
| R1-03 | `statusUrl` 解析后必须 `origin===baseUrl.origin`、无 userinfo/query/fragment、`pathname==/v1/runs/{submissionId}`；否则不发 GET | PASS |
| R1-04 | 动态身份派生由 `parts.join(":")` 改为 UTF-8 字节长度前缀编码；`("a:b","c")` 与 `("a","b:c")` 得不同 actorId；`deriveMemoryId` 公式不变 | REJECTED（碰撞攻击被拒） |

## 目标与边界

在既有 `apps/codex-adapter` 脚手架内实现唯一真实 stdio MCP 工具
`hide_nest_closeout_synthetic_confirmed`，把小林在 Codex 对话中明确确认（`userConfirmed=true`）的
**一个合成候选记忆 + 1—100 条被选最小证据** 映射为刚入库的 Local V1 关窗 API 的 `CloseoutSubmissionRequest`
并 POST 提交、GET 事实确认。

本轮只实现与测试 MCP 工具本身：未修改小林 Codex 配置、未注册 MCP、未重启 Codex、未启动正式后端、
未写真实记忆、未提交 Git。真实聊天仍受 HDM-007/008/010/011 阶段门约束。

## 交付内容

### 唯一 MCP 工具

- 工具名：`hide_nest_closeout_synthetic_confirmed`
- 中文描述明确“仅在小林已经在当前对话中明确确认后调用；只提交合成候选与被选择的最小证据，不读取或上传完整房间内容；不适用于真实资料或生产记忆”。
- 输入 schema：顶层与所有嵌套对象 `additionalProperties:false`；`userConfirmed: const true`；
  `closeoutKey`/`threadKey`/`speakerKey` 长度与控制字符门；`memoryType` 六值枚举；
  `bodyText` 1..16000 code point；`evidenceMessages` 1..100 且 ordinal 严格升序、无重复、连续；
  `perspectiveSpeakerKey` 必须出现在至少一条证据中。

### 确定性映射（单一冻结映射器，生产与测试共用）

`src/closeout-canonicalizer.ts` 用 Node 标准库 `crypto` 实现唯一冻结映射器。所有 adapter 自生成的
submission/thread/actor/source/anchor/confirmation ID 走同一冻结函数：先把各组件用 **UTF-8 字节长度前缀**
（`field()` 同款 `byteLength+':'+value`）无歧义编码，再 `UUID.nameUUIDFromBytes`（MD5, v3）。动态字符串跨分隔符
不再可碰撞（R1-04）。

| 派生 | 组件（经长度前缀编码后 `nameUUID`） |
|---|---|
| `submissionId` | `["submission", closeoutKey]` |
| `threadId` | `["thread", threadKey]` |
| `actorId` | `["actor", threadKey, speakerKey]` |
| `sourceUnitId` | `["source", closeoutKey, ordinal]` |
| `anchorId` | `["anchor", closeoutKey, ordinal]` |
| `confirmationSourceUnitId` | `["confirmation", closeoutKey]`（固定标签，绝不混入证据） |
| `memoryId` | `nameUUID("memory:" + submissionId)`（后端 facade 公式，逐字节不变） |
| `externalUnitRef` | `sha256(threadKey).slice(0,12) + "-" + ordinal`（不写 threadKey 明文） |

每条已选消息生成一个 `SourceUnit` 与一个覆盖该消息全部 Unicode code points 的 anchor
（正式半开区间 `[fromOffset, toOffset)`：`fromOffset=0`、`toOffset=codePointCount`）。`fromOrdinal`/`toOrdinal`
取证据首尾 ordinal，`continuous=true`，`schemaVersion=local-v1-synthetic-v1`。

规范哈希逐字节对齐 Java `LocalV1CloseoutCanonicalizer`：
长度前缀 `UTF8(byteLength)+':'+UTF8(value)`；`null=-1:`；布尔 `true/false`；UUID 小写连字符；
时间 `OffsetDateTime.toInstant().toString()`（UTC，纳秒小数去尾零）；整数十进制；数组先写数量。

- `bodyHash` = `sha256(UTF8(bodyText))`
- `threadManifestHash`：schemaVersion/fromOrdinal/toOrdinal/continuous/selectedEvidenceMessages(sourceUnitId/actorId/ordinal/externalUnitRef/UTC occurredAt/bodyHash)，不含正文
- `reviewManifestHash`：submissionId/threadId/hideSelection(perspectiveActorId/memoryType/bodyHash)/threadManifestHash/sourceAnchors(anchorId/unit 数/每 unit sourceUnitId/fromOffset/toOffset/ordinal)/decision，不含 reviewManifestHash 自身、confirmationSourceUnitId、confirmationProof
- `confirmationProof` = `sha256(threadId + "\n" + confirmationSourceUnitId + "\n" + reviewManifestHash + "\n" + submissionId)`

哈希比较使用 Node `crypto.timingSafeEqual`（测试断言相等性）；规范哈希跨语言最终一致性由下一张
“注册＋真实合成关窗烟测”由真实 Task31 API 直接证明。

### 进程配置与安全

- 只从进程环境读 `HIDE_NEST_API_BASE_URL`（默认 `http://127.0.0.1:8080`）、
  `HIDE_NEST_SYNTHETIC_TOKEN`、`HIDE_NEST_SYNTHETIC_CAPABILITY`。
- base URL 只允许 `http` + loopback（`127.0.0.1`/`localhost`/`::1`），禁止 userinfo/query/fragment/非根 path。
- token/capability 高熵门（`length>=43 && distinct>=16`）；缺失/低熵时 MCP initialize 与 tools/list 正常，
  但 tools/call fail closed 为 `LOCAL_CONFIGURATION_MISSING`。
- token/capability 不出现在 schema、stdout、stderr、异常、返回值、报告、fixture、diff、Codex 配置。
- 不落盘请求正文，不写临时 JSON，不缓存聊天全文。
- stdout 只承载 MCP 协议帧；诊断净化后写 stderr；成功路径零诊断。
- HTTP 用 Node 24 原生 `fetch` + 有限超时（10s），不新增第二个 HTTP 客户端。
- POST 带 `Authorization`、`X-Action-Capability`、`Idempotency-Key=submissionId`、JSON content type；
  随后对返回的 `statusUrl` 做一次 GET 事实确认。
- POST=202 后、GET 前必须满足：`resultCategory==SUCCEEDED && runId==submissionId && phase==CANONICAL_COMMITTED && statusUrl 非空`。
- `statusUrl` 解析后必须 `origin===baseUrl.origin`、无 userinfo/query/fragment、`pathname==/v1/runs/{submissionId}`；
  否则不发 GET，避免 Authorization 转交给本机其他进程。
- GET=200 后必须满足：`resultCategory==SUCCEEDED && runId==submissionId==POST.runId && phase==CANONICAL_COMMITTED`。
- 任一不符返回净化后的 fail-closed 错误（`POST_RECEIPT_MISMATCH`/`GET_STATUS_MISMATCH`/`STATUS_URL_INVALID`），不返回成功。
- POST 成功但 GET 未确认（非 200/网络）返回可安全重放的净化失败（`CONFIRMATION_PENDING`）；确定性 submissionId 保证重放收敛。
- 成功输出仅六字段：`status=SAVED`、`runId`、`memoryId`、`phase`、`statusUrl`、`selectedEvidenceCount`；`memoryId` 由已验证的
  `request.submissionId` 按 facade 公式派生。
- 已知 Problem 只投影安全字段 `status/failureCode/resultCategory/retryable/requestId`；未知响应不原样透传 body。

## 最小必要验证

### 6.1 最小单元门（PASS）

- `userConfirmed=true` 最小合法输入生成闭合请求；`false`/缺失在 HTTP 前拒绝。
- 同一输入两次生成完全相同的 submissionId/threadId/actor/source/anchor ID 与请求 JSON（`JSON.stringify` 全等）。
- 中文＋emoji 的 bodyHash（金值 `e973a1c1…953fa`）、UTF-8 限制（证据 >1MiB 拒绝）与 code point anchor 边界
  （`"a😀b"` = 3 code point，半开区间覆盖 `0..3`）正确。
- 未知字段、ordinal 重复/断档/降序、perspectiveSpeakerKey 不在证据中、控制字符、非法 memoryType、
  空证据、超长正文等代表性非法输入全部拒绝。
- token/capability/正文 canary 不出现在成功输出、净化错误或 stderr（单元 + 集成双路验证）。

### 6.2 一次真实 MCP＋loopback 门（PASS）

`src/mcp.integration.test.ts` 构建真实 `dist/mcp.js`，用 SDK stdio client 完成一次：

- initialize（`Client.connect` 成功）；
- tools/list 精确一个工具（`hide_nest_closeout_synthetic_confirmed`）；
- tools/call 合法输入成功；
- 临时 loopback HTTP server 观测到一次 `POST /v1/closeout-submissions`（路径、`Idempotency-Key=body.submissionId`、
  `Authorization`、`X-Action-Capability`、闭合 JSON 顶层/嵌套键精确）与一次 `GET /v1/runs/{runId}`；
- 假 API 返回 canonical 后 MCP 只返回安全六字段；
- stdout 无协议外文字（SDK 客户端全量帧解析成功 + stderr 为空）；进程退出无残留（`client.close()` 正常收敛）。

另加错误路径：假 API 返回 409 Problem（含 `secretCanary` 非安全字段），MCP 仅投影
`status/failureCode/resultCategory/retryable/requestId`，不回显 canary 或正文。

### 6.3 仅 Codex Adapter 四门（PASS）

```text
npm run typecheck --workspace @hide-nest/codex-adapter   PASS
npm run lint --workspace @hide-nest/codex-adapter        PASS（0 error；2 条既有 generated 文件 warning）
npm run test --workspace @hide-nest/codex-adapter        PASS（5 files / 56 tests）
npm run build --workspace @hide-nest/codex-adapter       PASS
```

未运行 Node 全工作区四门、浏览器、Maven、PostgreSQL/API、Playwright。

## 动态证据

| 门 | 结果 |
|---|---|
| MCP initialize / tools-list / 真实 tools-call | PASS / PASS / PASS |
| 唯一工具 / 输入闭合 | 1 / PASS |
| 确定性身份 / 规范哈希 / 确认门 | PASS / PASS / PASS |
| loopback POST-GET / 幂等重放 | PASS / PASS |
| 代表性非法输入 / 错误净化 | PASS / PASS |
| stdout 协议纯净 / 正文-secret 泄漏 | PASS / 0-0 |
| Codex Adapter 四门 | PASS |
| SDK 版本 / 许可证 | 1.30.0 / MIT（node>=18，兼容 Node 24） |

### R1 返工动态证据

| 门 | 结果 |
|---|---|
| anchor 半开区间 `[0,n)` / `"a😀b"` 覆盖 `0..3` | PASS / PASS |
| POST 回执绑定（错 runId/phase/resultCategory 均不发 GET） | PASS |
| GET 回执绑定（错 runId/phase/resultCategory 均不成功） | PASS |
| statusUrl 同源-同路径门（跨端口/跨主机/query/fragment/错 path 均 GET 前拒绝） | PASS |
| 正常相对同源 statusUrl 仍成功 | PASS |
| 动态身份碰撞（`("a:b","c")` vs `("a","b:c")` 得不同 actorId） | REJECTED |
| 正文-token-capability-secret 泄漏 | 0-0-0-0 |

## 最终回复

```text
返工状态：LOCAL_V1_CODEX_MCP_R1_READY_FOR_REAL_API_SMOKE
anchor 半开区间／emoji：PASS/PASS
POST request绑定／GET request绑定：PASS/PASS
statusUrl 同源-同路径门：PASS
动态身份碰撞攻击：REJECTED
Codex Adapter 四门：PASS
正文-token-capability-secret泄漏：0-0-0-0
Java-契约-数据库-React改动：0-0-0-0
真实数据／Codex配置／Git写操作：0/0/0
工作区：tracked=4、untracked=8、staged=0、越界=0
联网：否
报告路径：D:\myproject\hide-nest\reports\LocalV1-CodexMCP-执行报告.md
```

## 变更文件清单

修改（4）：`apps/codex-adapter/package.json`（SDK exact 依赖 + test 脚本限定 src）、
`apps/codex-adapter/src/mcp.ts`、`apps/codex-adapter/src/entries.test.ts`、根 `package-lock.json`（仅 SDK 依赖树机械变化）。

新增（8）：`closeout-canonicalizer.ts`、`closeout-input.ts`、`closeout-client.ts`、`closeout-tool.ts`
及对应测试 `closeout-canonicalizer.test.ts`、`closeout-input.test.ts`、`closeout-client.test.ts`、
`mcp.integration.test.ts`。

R1 返工仅按工单第四节触及：`closeout-canonicalizer.ts`（anchor 半开区间、长度前缀派生）、
`closeout-client.ts`（POST/GET 回执绑定、statusUrl 同源-同路径门、memoryId 派生源）、
`closeout-canonicalizer.test.ts`、`closeout-client.test.ts`；其余文件未在 R1 改动。

未修改：Java、OpenAPI、inventory、baseline、生成客户端、migration、database generated、React、
原型、正式枚举、S1/Task31 代码；`.codex/config.toml`、`.claude/settings.local.json`、Codex 插件目录、
用户环境变量；既有 `reports/local-v1-read-browser-qa/.playwright-artifacts/**`（原样保留，未读取/删除/移动）。

## 停止条件检查

未触发任何停止条件：官方 SDK 1.30.0 为 MIT 且兼容 Node 24；全部来自 registry.npmjs.org；无需修改
后端契约或 Java；stdout 协议纯净；token/capability 不落盘；MCP 真实子进程测试成立；输入不需完整房间；
无越界修改、无真实资料、无密钥、无未声明下载。

## 工作区与 Git

- 真实 Git index 未变（staged=0）
- 工作区：tracked=4、untracked=8（新增）、staged=0、越界=0
- 既有未跟踪目录 `reports/local-v1-read-browser-qa/.playwright-artifacts/` 原样保留（未触碰）
- 联网域名：registry.npmjs.org
- Git 写操作：否（无 stage/commit/push/amend/reset/clean）
- 真实数据写入：0；Codex 配置修改：0

## 已知生产缺口

HDM-007/008/010/011（生产身份/能力、ThreadReader、四道门）未完成；**真实数据禁止**，生产模式继续 fail closed。

---

# Task32C｜真实 API 合成烟测（追加）

## 烟测状态

**LOCAL_V1_CODEX_MCP_REAL_API_SMOKE_PASS**

一次临时、隔离、可销毁本机环境，真实 PostgreSQL + 真实 V001—V015 + 真实 Task31 API + 真实 stdio MCP 子进程，
纵切逐字节接通，全部判定同时成立。

## 环境与隔离

- 冻结 HEAD `f6b4079` 未变；Git index 未变（staged=0）。
- 一次性 PostgreSQL 容器：`pgvector/pgvector@sha256:2ac2c62a…5c73c`（与 `scripts/db.ps1` 锁定 digest 一致，`--pull=never`，未下载 layer）。
- Flyway V001—V015：精确 15，重复迁移 0。
- `apps/api` 以 JDK 25（`C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot`）+ 预构建 exec jar + 离线 Maven 缓存启动，
  profile `local-v1-synthetic`、`server.address=127.0.0.1`、临时端口、一次性 datasource + payload root、高熵临时 token/capability。
- 临时 bearer/capability/数据库密码只存在于父/子进程环境与内存，未写入文件/命令报告/stdout/stderr/Git diff/Codex 配置。
- 合成样例（虚构，非小林真实资料）：候选“合成角色纸船喜欢在雨天收集蓝色玻璃珠。🔵”；证据 1（纸船）、证据 2（灯塔）中文＋emoji，2 条连续、2 个说话者、`userConfirmed=true`；closeoutKey/threadKey 随机高熵，同值重放复用原值。

## 结果

| 门 | 结果 |
|---|---|
| MCP initialize / tools-list / tools-call | PASS / PASS / PASS |
| 真实 closeout-run-detail-evidence | PASS / PASS / PASS / PASS |
| 首次 / 同值重放 / 规范事实数 | SAVED / SAME_IDS / ONE |
| JS-Java 规范哈希 / anchor 半开区间 | PASS / PASS |
| 证据条数 / 顺序-说话者-正文 | 2 / PASS |
| stdout 协议 / 正文-secret 泄漏 | PASS / 0-0 |
| Git index / 生产源码改动 | 未变 / 0 |
| 新增 Docker 容器-卷-网络 / payload 目录 / 遗留进程 | 0-0-0 / 0 / 0 |
| 联网 / Codex 配置 / Git 写操作 | 否 / 0 / 0 |

## 关键证据

- 首次 tools/call 返回六字段：`status=SAVED`、`phase=CANONICAL_COMMITTED`、`selectedEvidenceCount=2`；
  `runId=782763a1-19c2-3ece-95fd-d88787d03403`、`memoryId=0e2208a4-5e6d-3b29-8bfa-e895ed19f821`。
- 同值重放返回同一 runId / memoryId / statusUrl。
- `GET /v1/runs/{runId}` → `phase=CANONICAL_COMMITTED`；`GET /v1/memories/{memoryId}` → 正文/`memoryType=CLAIM`/`revisionNo=1` 精确；
  `GET /v1/memories/{memoryId}/evidence` → 恰 2 条、2 个不同 actorId、ordinal 0/1、正文完整。
- JS 生成的 thread/review/proof 被真实 Java API 接受（202 + CANONICAL_COMMITTED，未绕过任何校验，无 hash 不一致拒绝）。
- 数据库规范事实唯一：`memory_record`=1（ACTIVE）、`memory_revision` revisionNo=1、`EVIDENCED_BY`=2、
  `capture_scope_unit`=2（未多写第三条证据或 confirmation 单元）、`evidence.source`=1。
- anchor 半开区间（DB `source_anchor_unit`，按 ordinal）：`(0, from_offset=0, to_offset=16)`、`(1, from_offset=0, to_offset=17)`——
  与“今天下雨，我捡到一颗蓝色玻璃珠。”（16 code point）与“我看见纸船把它放进了合成收藏盒。🌧”（17 code point，emoji 计 1）精确一致。
- stdout 只有 MCP 协议（SDK client 全量帧解析成功）；MCP 返回值、stderr、API 日志（322 字节，`logging.level.root=WARN`）均不含 token/capability/数据库密码。

## 清理核验

- 已停 API、已关 MCP、已 `docker rm -f` 本次一次性容器、已删除一次性 payload 目录与临时烟测 harness。
- 清理后 Docker 基线：containers=1、volumes=41、networks=3，与烟测前逐项 diff 一致；无 task32c 残留容器。
- 未新增可保留的 `real-api-smoke.test.ts`（harness 置于系统临时目录并于结束删除）；未改任何生产源码。
