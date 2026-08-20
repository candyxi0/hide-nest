# Task43A-R1｜生成门与报告证据收口执行报告

## 实施状态

ITERATION1_CONTEXT_PACK_RELEVANCE_GATE_R1_READY_FOR_LOCAL_COMMIT

## R1 收口裁定

Task43A 主代码与门控语义通过 hide 代码复核，不改业务实现、不重跑数据库/API/MCP/Maven 全量测试。仅关闭两个机械阻塞：

1. **R1-01**：正式 `generate.ps1 -Check` 必须真实 exit 0，禁止自定义“授权例外”冒充 PASS；
2. **R1-02**：Markdown/Evidence 如实记录 Git 路径与 Maven 尝试次数。

## 断点接管（Task43A-H1 前置）

按 `Task43A-H1-ContextPack门槛Kimi断点接管工单.md` 冻结并接管现有工作树继续 Task43A，禁止 `git reset/checkout/restore`、禁止回退、禁止从头重做。

- HEAD：`5dde3bf34a4ec34489b1c6cd024f6d0aceac0c70` @ `main`
- staged = 0
- 允许既有 QA 临时目录 `reports/local-v1-read-browser-qa/.playwright-artifacts/`，未处理

先修已审查的四个已知缺口（H1-01 ~ H1-04），再补 Phase 2 测试，随后执行 Phase 3 API、Phase 4 Codex Adapter、Phase 5 质量门；本 R1 仅做生成门与报告收口。

## R1-01｜关闭真实 generation check

前一版把 `ContextPackMemory.ts` 恢复为 tracked 内容以规避空 JSDoc 尾随空白，导致“正式新生成树 vs tracked 生成树”仍有 1 文件差异，`generate.ps1 -Check` 无例外机制故不得报告 PASS。精确修法：

1. 在 formal spec 为两个既有字段增加真实、准确、非敏感 description：
   - `evidenceOccurredAt`：Latest occurrence time among the saved evidence of this delivered memory；
   - `evidenceAgeDays`：Number of complete 24-hour periods between evidenceOccurredAt and issuedAt（既有算法，非自然日）；
2. baseline 逐字节同步；
3. 使用正式 `scripts/generate.ps1` 重生成（禁止手改生成树）；
4. 运行真实 `powershell -File scripts/generate.ps1 -Check` → **`GENERATION_CHECK_PASS`，exit 0**；
5. `git diff --check` PASS，生成 whitespace 例外 = 0。

description 只修生成链闭合，不改变字段类型、required、算法或 API 行为。不重跑 Maven/Node/业务测试。

## H1-01｜显式 null 不是缺失

`LocalV1ContextPackRequestMapper` 原对 `maxResults:null` / `minScore:null` 走默认值，属错误。已改为：

- 属性真正缺失 → 默认 `3 / 0.6`；
- 属性存在但为 `null` → `REQUEST_SCHEMA_INVALID`，HTTP 422；
- unknown / type / range 继续拒绝。

新增单因子 `LocalV1ContextPackRequestMapperTest`（6 用例）证明：缺失→默认、显式 null→拒绝、显式合法值→解析、类型错误→拒绝。该测试只驱动 mapper，不触碰 coordinator / Embedding / 数据库，故 Embedding 与审计到达恒为 0。

## H1-02｜新测试 SQL schema 修正

`LocalV1ContextPackRelevanceGateTest` 原错误查询 `public.retrieval_trace / public.context_delivery / public.context_delivery_item`。已精确修正为正式对象：

- `runtime.retrieval_trace`
- `runtime.context_delivery`
- `runtime.context_pack_delivery_item`
- `runtime.idempotency_receipt`

空结果审计断言补全为 trace / delivery / item / receipt = `1/1/0/1`（含 receipt 计数，通过 idempotency_key 查询）。

## H1-03｜Phase 2 测试补齐

原 7 个表面用例保留，新增下列要求，共 16 用例全 PASS（真实 PostgreSQL/pgvector）：

1. `score == minScore` 接受（既有），新增 `score 略低于 minScore` 拒绝（near-parallel 向量余弦恰低于 1.0，minScore=1.0 拒绝）；
2. 低分候选不调用 S2B detail/evidence：窄 spy（`CountingS2b` 继承计数）证明高分候选触发 1 detail + 1 evidence，低分候选 0；
3. 可见性失败后允许少于 maxResults，不拿低分候选补位（fenced S2B 使一高分候选 visibility 失败，结果仍为 1 而非 2）；
4. 同 key 仅 maxResults 异值 / 仅 minScore 异值 → `IDEMPOTENCY_KEY_REUSED`，事实增量全 0；
5. replay item score 被改到门槛下 / item 数超 max → fail closed（`INTERNAL_FAILURE`）；
6. Task42 旧算法 receipt（request hash 无 v2 策略版本）不得冒充 v2 默认 replay → `IDEMPOTENCY_KEY_REUSED`；
7. 默认与非默认策略 manifest 内容绑定篡改（改可写向量：delivery item score 改为仍达标但不同的值）→ replay REJECTED。`context_delivery` 身份列由 HDM006 触发器直接不可改（DB 层已封口）；
8. 同值 replay 精确证明 Embedding / trace / delivery / item / receipt 增量全 0，不只比较响应 ID。

测试复用既有 tamper/trigger 临时解除模式；delivery 身份列不可变门未降低。测试间隔离通过专用 basis 指数（400+）避免共享 DB 串扰。

## H1-04｜生成物 whitespace

`git diff --check` 命中：`ContextPackRequest.ts` 新字段空 JSDoc 尾随空白、`ContextPackMemory.ts` 两处无关空 JSDoc 尾随空白。

- 为 `maxResults/minScore` 在 formal spec 增加真实、简短 description，用正式 `scripts/generate.ps1` 重生成（非手改生成文件）；生成后 `ContextPackRequest.ts` 新字段 JSDoc 带真实描述、无尾随空白；
- `ContextPackMemory.evidenceOccurredAt/evidenceAgeDays` 的空 JSDoc 尾随空白，最终在 R1-01 通过给两字段增加真实 description 后由正式重生成自然闭合（见「R1-01」），未手改、未改变 schema/类型/required/算法；
- 最终 `git diff --check` PASS，生成 whitespace 例外 = 0。

## Phase 2｜Application / PostgreSQL targeted

`LocalV1ContextPackRelevanceGateTest` 16、`LocalV1ContextPackEmptyTest` 2、`LocalV1ContextPackRetrievalTest` 20 全 PASS。`RetrievalTest` 因新默认 `minScore=0.6` 产生两处既有不一致已就地修正（不降低断言）：

- `aRanksFirstWithHigherScore`：B 改为 partial-aligned（score 0.8）使其越过门槛但仍低于 A（score 1.0），排名断言保留；
- `policySetTraceAndDeliveryManifestAreExact`：手工 manifest 重建补齐 `v2 / maxResults / minScore` 三个新绑定字段。

## Phase 3｜正式 HTTP integration

`LocalV1ContextPackHttpIntegrationTest` 由 5 扩至 12 用例，全 PASS：

- 旧四字段请求 → 默认 `maxResults=3`（cap 至 3）；
- 新字段省略 / 单独传 / 同时传均 200；显式 `maxResults=2` → 恰好 2；
- `maxResults:null` / `minScore:null` → 422；type / range / unknown → 422；
- 0 条响应 → HTTP 200 + `NO_RELEVANT_RESULT` + `memories=[]` + `policyRevisionSet=[]`；
- 同 key 参数异值 → 409；同值 replay EXACT。

测试用专用 embedding basis 隔离（政策类用例与空集用例各占独立 basis，空集 basis 无任何记忆投影），避免共享 DB 干扰既有 ranking/replay 断言。

## Phase 4｜Codex Adapter

- `contextPackToolInputSchema` 恰 6 个 property，required 仍为原四字段 `retrievalKey/threadKey/turnKey/query`；`maxResults` 可选 1–5、`minScore` 可选 0.4–1.0；
- 输入校验解析确定值：省略 → 显式发送 `maxResults=3, minScore=0.6`；显式 `5/0.4` 正常；显式 null / 越界 / 类型错 → REJECTED；
- POST body 显式携带 `maxResults/minScore`（integration 断言默认 `3/0.6` 明确发送）；
- response 二次门：`memories.length <= maxResults`、每条 `score >= minScore`；违反整包 `INVALID_RESPONSE`；
- 工具说明逐字锁死：默认 `maxResults=3, minScore=0.6`、空集合是正常结果、不得降门槛凑数、仅默认空后强线索才允许一次放宽 `maxResults=5, minScore=0.4`、放宽必须新 `retrievalKey` + 同 `threadKey/turnKey`、禁止第三次 / 低于 0.4 / 已有结果再放宽；
- query 规则：一次只写单一正向目标、禁检索控制型否定与排除概念名、事实自身否定语义保留（`小林不喜欢香菜`）、当前无 `excludeTerms`、正反例逐字存在；
- CandidateSet、MemoryEvidence 回归 PASS；`tools/list` 仍恰 3。

Codex Adapter 测试 284 passed、1 skipped（既有）、0 failed。

## Phase 5｜质量门

| 门 | 结果 |
|----|------|
| ContextPack application/API/contracts/Codex Adapter targeted | PASS |
| OpenAPI generate（正式脚本，含 description 重生成） | PASS |
| OpenAPI check（真实 `generate.ps1 -Check`） | **GENERATION_CHECK_PASS，exit 0**（whitespace 例外 = 0） |
| 兼容门 `contract-compatibility.ps1` | `baseline-vs-current verdict=COMPATIBLE`；工具自测 fixtures 正确判 INCOMPATIBLE |
| Codex Adapter 四门 typecheck/lint/test/build | PASS / PASS / PASS / PASS |
| 根 Node 四门 typecheck/lint/test/build | PASS / PASS / PASS / PASS |
| JDK25 `mvnw -o clean verify` | 最终门 PASS（总尝试 2 次，见下） |
| 架构门 `modules/architecture-tests` | PASS（ArchitectureTest 7 + DatabaseBoundaryTest 9 + PortBoundaryTest 13 = 29，0 failure） |
| `git diff --check` | PASS（仅 LF→CRLF 转换警告） |
| Docker 残留 containers/volumes/networks | 0-0-0（仅既有 bridge 网络） |

### Maven 尝试事实

- **attempt 1**：baseline 尚未同步（`evidenceOccurredAt/evidenceAgeDays` 缺 description）时，`contracts` 模块 `ReadApiContractTest.formalSpecAndBaselineMustBeByteIdentical` 失败；**不得写成 PASS**；
- **attempt 2**：baseline 同步后，JDK25 `mvnw -o clean verify` **BUILD SUCCESS**（~7 min）；
- 最终门状态 PASS，但**总尝试次数 = 2**，不再写“只运行一次”。

## 兼容门权威裁定（如实披露）

本任务未修改 `contract-compatibility.ps1`、插件版本、synthetic fixtures 或 `additionalProperties:false`。最终区分三项：

1. baseline 未同步时，openapi-diff 2.1.7 对 closed request addProp 给出 **INCOMPATIBLE**（工具对 closed schema 新增可选属性的局限，真实发生，如实披露）；
2. 独立契约测试 `ContextPackRequestContractTest` 8/8 PASS 证明旧四字段请求仍合法且解析为新默认 3/0.6，新增字段 optional / 严格范围 / unknown 仍拒绝；
3. accepted baseline 与 formal spec 字节一致（diff 验证 byte-identical，仅 CRLF 差异）后，正式仓库 `contract-compatibility.ps1` 给出 `baseline-vs-current verdict=COMPATIBLE`，兼容门 PASS。

baseline 同步本身未写成“独立证明兼容”。

## 修改文件（Git scope 由 `git status --short -uall` 机械生成）

**Git modified（18）**

- `apps/api/src/main/java/io/github/candyxi0/hidenest/api/LocalV1ContextPackRequestMapper.java`
- `apps/api/src/test/java/io/github/candyxi0/hidenest/api/LocalV1ContextPackHttpIntegrationTest.java`
- `apps/codex-adapter/src/context-pack-canonicalizer.ts`、`context-pack-canonicalizer.test.ts`
- `apps/codex-adapter/src/context-pack-client.ts`、`context-pack-client.test.ts`
- `apps/codex-adapter/src/context-pack-input.ts`、`context-pack-input.test.ts`
- `apps/codex-adapter/src/mcp.ts`、`mcp.integration.test.ts`
- `contracts/openapi/hide-nest-api.yaml`
- `contracts/compatibility-fixtures/baseline.yaml`
- `modules/application/src/main/java/io/github/candyxi0/hidenest/application/coordinator/LocalV1ContextPackCoordinator.java`
- `modules/application/src/main/java/io/github/candyxi0/hidenest/application/model/LocalV1ContextPackRequest.java`
- `modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1ContextPackEmptyTest.java`
- `modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1ContextPackRetrievalTest.java`
- `packages/api-client-ts/src/generated/models/ContextPackMemory.ts`（正式脚本生成）
- `packages/api-client-ts/src/generated/models/ContextPackRequest.ts`（正式脚本生成）

**Git added（5）**

- `apps/api/src/test/java/io/github/candyxi0/hidenest/api/LocalV1ContextPackRequestMapperTest.java`
- `modules/contracts/src/test/java/io/github/candyxi0/hidenest/contracts/ContextPackRequestContractTest.java`
- `modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1ContextPackRelevanceGateTest.java`
- `reports/Iteration1-ContextPackRelevanceGate-执行报告.md`
- `reports/Iteration1-ContextPackRelevanceGate-Evidence.json`

**Git deleted（0）**

**task paths = modified + added = 18 + 5 = 23**

QA 临时目录 `reports/local-v1-read-browser-qa/.playwright-artifacts/` 单列，不计入 task paths。

禁止范围（迁移 / jOOQ generated / React / Console / 部署 / MemoryEvidence MCP / CandidateSet / 删除 / Embedding adapter / pgvector SQL）修改：0。

## 机械证明

- Java / 数据库 / 迁移 / OpenAPI / generated / React / 部署 越界修改：0。
- operation / failure-code / event 计数：30 / 50 / 12 UNCHANGED；schema 计数 UNCHANGED（仅新增属性，未新增 schema）。
- HEAD / index 起止：MATCH；staged = 0；越界 = 0。
- `git diff --check`：PASS。
- 未联网；未 stage / commit / push。
- 正文、query、完整向量、token、capability、secret、家庭主机地址、绝对 payload 路径泄漏：0。

## 环境

- Git head：`5dde3bf34a4ec34489b1c6cd024f6d0aceac0c70` @ `main`
- 越界修改：0
- Staged 文件：0
- Git 写操作：无
- 联网：无
- 真实家庭 Embedding 烟测：NOT_RUN（留给独立部署工单）

## 回执

```text
收口状态：ITERATION1_CONTEXT_PACK_RELEVANCE_GATE_R1_READY_FOR_LOCAL_COMMIT
正式generate-check／exit：PASS/0
生成whitespace例外／diff-check：0/PASS
Git modified-added-deleted／task paths：18-5-0/23
Maven尝试1／尝试2／最终门：CONTRACTS_FAILED/BUILD_SUCCESS/PASS
生产业务／测试逻辑修改：0/0
报告-Evidence状态／路径／计数：MATCH/MATCH/MATCH
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\Iteration1-ContextPackRelevanceGate-执行报告.md
```
