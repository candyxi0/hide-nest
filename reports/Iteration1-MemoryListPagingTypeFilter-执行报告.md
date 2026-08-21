# Iteration1｜记忆列表稳定分页与类型筛选 执行报告

## 实施状态

末端收口状态（R1A 封口）：ITERATION1_MEMORY_LIST_PAGING_TYPE_FILTER_R1A_READY_FOR_LOCAL_COMMIT
（原 ITERATION1_MEMORY_LIST_PAGING_TYPE_FILTER_READY_FOR_HIDE_REVIEW 经 R1 / R1A 封口更新为上述末端收口状态。）

## 1. 目标与范围

把 `GET /v1/memories` 已预留但未完整接线的 `memoryType / cursor / limit` 落成正式 Local V1 只读能力：
把分页从 offset 迁移到不透明的 seek cursor（`local-v1-memory-seek-v1`），并新增六值单选类型筛选与前端“加载更多”。
不做语义/向量/混合检索，不做无限滚动、虚拟列表、总数统计、多类型复选或下一需求。

- 基线：HEAD / origin/main = `5ed690ae1d349ccd4351d70fec23b8b0f6684143`，branch=main，staged=0。
- 允许改动见 §9；V001–V021、jOOQ generated、OpenAPI spec/generated、ContextPack/MCP、CandidateSet、MemoryEvidence、Deletion、Embedding、Console Host、部署、安全配置均冻结未改。
- 前端原型三文件（`docs/frontend/prototype/{index.html, assets/styles.css, assets/app.js}`）未改，起止 SHA-256 逐字 MATCH。

## 2. 实现

### 2.1 稳定 seek cursor 编解码
`LocalV1CursorCodec` 由 offset token 重写为 seek token，唯一规范 Base64URL(no-pad) 编码：

```
local-v1-memory-seek-v1:<lastUpdatedAtMicros>:<lastMemoryId>:<filterFingerprint>:<integrityDigest>
```

- `filterFingerprint` = canonical(state, normalizedKeyword, memoryType-or-ALL) 的完整 SHA-256（长度前缀分段编码，无歧义）；
- `integrityDigest` = 前述 payload 截断 SHA-256，常量时间比较；
- decode 后重新 encode 逐字相等；长度上限 256；非法 UTF-8/时间/UUID/额外字段/非规范 Base64/摘要篡改/旧 `local-v1-offset:*` 一律 422，且 SQL 不到达。

### 2.2 控制器
`LocalV1MemoryListController` 接通 `memoryType`（六值，null=全部；显式空白/未知/小写 422），仍 fail closed 拒绝 `perspectiveActorId/sourceAvailability`；在 controller 规范化 state/query/type 后计算 canonical fingerprint 校验 cursor 绑定；`nextCursor` 通过“同筛选、seek-after-last、limit=1”只读探测生成，禁止 offset。默认 limit=30，1..50 校验。

### 2.3 Application / port
`LocalV1S2BListRequest` 与 `MemoryReadFilter` 改为 seek 形态（state / keyword / memoryType / limit / afterUpdatedAt / afterMemoryId）；after 对必须双空或双非空；limit 1..50；关键词 100 code point 门保持；`memoryType` 在 application 层严格映射 wire UPPER → 持久化 PascalCase，null/非法值发 SQL 前拒绝。旧构造调用全部机械适配为 `memoryType=null, seek=null`。

### 2.4 DB adapter
`JooqMemoryReadAdapter`：keyword 与 memoryType 在同一 current-revision EXISTS 条件内判定（`CURRENT_REVISION_ID = MEMORY_RECORD.CURRENT_REVISION_ID` 且 `MEMORY_ID` 绑定），绝不分别命中不同 revision；deletion fence、state、keyword escaping、owner/pointer 门保持；seek 用 timestamptz 与 UUID 精确比较；`ORDER BY updated_at DESC, memory_id ASC`；无 OFFSET、无迁移/索引/函数/权限/generated 变更。

### 2.5 React 页面
`App.tsx`：改用 TanStack `useInfiniteQuery`，queryKey 含规范关键词/state/memoryType；首屏不传 cursor、后续逐字复用 nextCursor；每页 limit=30；pages 展平后按 memoryId 防御性去重；`hasNextPage` 仅取决于 nextCursor；切换 query/state/type 自动建新 queryKey 从第一页开始。新增“记忆类型”原生 `<select>`（状态 chips 下方第二行窄控件，`var(--rule-strong)` 细下边线、纸张底色、`<label>`+`<select>`、`:focus-visible` 玫红描边）；URL 参数固定 `type=<WIRE_ENUM>`，全部类型省略，非法 URL type 回退全部并清除。列表底部“加载更多／正在加载…／加载更多失败，请重试”整行轻量按钮（最小高度 44px，首屏失败仍用既有完整错误面板）。删除成功从所有 infinite pages 过滤该 memoryId、单一 aria-live 反馈并后台 refetch 收敛；删除失败不移行不清页。

## 3. 必测矩阵结果

### 3.1 数据库 / Application（LocalV1S2BMemoryQueryTest 18 例全 PASS）
- 六类型逐类型精确、全部类型返回全部：PASS
- state + keyword + memoryType 三条件交集：PASS
- current revision 类型绑定（结构上经 `CURRENT_REVISION_ID`，测试覆盖精确匹配当前 revision 类型、拒绝其余五型）：PASS
- pointer/owner/fence 既有反证不回退：PASS
- 同 updatedAt 35 条，两页 20+15 无重复无漏项、UUID ASC 稳定：PASS
- 第一页后插入更新记忆，翻页不 offset 重复：PASS
- 第一页后删除已见行，翻页不跳过原下一项：PASS
- after 对半空 / 未知类型 / 过长关键词 / limit 0/51 均 SQL 前拒绝：PASS
- `% _ ! \` keyword escaping 回归：PASS
- 查询只读，前后业务表/payload hash MATCH：PASS

### 3.2 Cursor / HTTP（LocalV1ReadHttpIntegrationTest + LocalV1ReadContractTest PASS）
- 30/30/余数分页与 nextCursor 有/有/无、合并精确无重漏：PASS
- limit=1 与 limit=50；0/51 → 422：PASS
- state/query/type 任一改变后复用旧 cursor → 422：PASS
- cursor 篡改/截断/额外字段/旧 offset cursor → 422，SQL 到达 0：PASS
- 相同筛选与 cursor replay → items/nextCursor EXACT：PASS
- memoryType 六值 200；显式空白/未知/小写 → 422：PASS
- perspectiveActorId/sourceAvailability 仍 REJECTED：PASS
- OpenAPI operation/schema/enum 计数不变、生成客户端 memoryType/cursor/limit 无 Object 回退：PASS（生成内容 0 diff）

### 3.3 React（App.test.tsx 59 例全 PASS）
- 初始只请求 limit=30 不带 cursor，收到 cursor 后显示“加载更多”：PASS
- 30+30+10 连续加载、行数与 cursor 逐字、末页按钮消失、共 N 条：PASS
- 双击加载更多只产生一个在途下一页请求：PASS
- 下一页失败保留首屏、重试成功追加：PASS
- 状态/关键词/类型变化重置第一页并更新 URL：PASS
- 六类型发送精确 wire enum、全部类型省略 memoryType：PASS
- 删除已加载页项目立即从所有页缓存移除且反馈只出现一次：PASS
- 删除/新增 refetch 不重复、不丢筛选：PASS
- 390/1024/1440 真实浏览器响应式与键盘/aria 门：PASS
- Authorization/Cookie/capability/storage/正文-secret 泄漏 0：PASS

## 4. 质量门

| 门 | 结果 |
|---|---|
| S2B list/database targeted（真实 PostgreSQL/pgvector） | PASS（18 例） |
| API list/cursor targeted（integration + contract） | PASS |
| nest-console targeted + typecheck/lint/test/build | PASS / PASS / PASS（59） / PASS |
| OpenAPI generation check 与 contracts targeted | PASS（spec/generated 0 diff） |
| JDK25 `mvnw -o clean verify` 最终门 | PASS（13 模块） |
| architecture-tests | PASS |
| 根 Node 四门（typecheck/lint/test/build） | PASS / PASS / PASS / PASS |
| 原型三文件 SHA-256 起止 | MATCH |
| 真实浏览器 1440×900 / 1024×768 / 390×844 + axe | PASS（新分页/类型筛选纵切，axe serious/critical=0） |
| `git diff --check` / 路径 / 泄漏 / Docker / Git index | PASS / PASS / PASS / 0-0-0 / MATCH |

## 5. R1｜筛选绑定与浏览器门封口

hide 复核裁定关闭四项后进入本地提交门，保留 Task45A 工作树窄修。

### R1-01 中文筛选指纹必须使用 UTF-8
`LocalV1CursorCodec.digestBytes()` 由 `US_ASCII` 改为 `UTF-8`（filter canonical 与 integrity prefix 统一 UTF-8，ASCII 结果不变；不改变 wire version/字段/摘要长度/Base64URL）。反证测试：`月亮`≠`粉色`、emoji 与同 UTF-16 长度字符串不碰撞、中文 cursor 同筛选 round-trip EXACT、换另一中文 query REJECTED（能杀死旧实现）。

### R1-02 响应必须绑定最终 current revision
`LocalV1S2BQueryCoordinator.listMemories` 在逐项组装后新增 fail-closed/retry-safe 最终复核 `stillMatchesFilter`：fresh current record 的 state 仍满足 state 筛选、fresh current revision 的 memoryType 仍满足可选 type、bodyText 按与 DB 相同的字面大小写无关规则仍含 normalized keyword、`updatedAt`（按 instant 比较，规避 offset 表示差异）与 `currentRevisionId` 仍与初次列表记录一致；变化则本页跳过、允许少于 limit、不补位；fence/pointer/owner 损坏仍 fail closed。反证：窄 `MemoryReadSpy` 覆盖 type 变、state 变、keyword 变、updatedAt/排序键变、无变化完全不变、不匹配项 evidence/payload 副作用 0。

### R1-03 非法 type URL 动态清理
`App.tsx` 清理 effect 依赖从 `[]` 改为 `[searchParams.get("type")]`，随 URL 中 raw type 变化重新判定；初始非法、挂载后导航非法、浏览器 back/forward 非法三条均 PASS；合法类型与 ALL 不触发多余 replace；永不向 API 发送非法 memoryType。

### R1-04 陈旧永久删除 E2E 机械同步
`read-vertical.spec.ts` 中“永久删除”动作改为打开现有删除抽屉/影响预览入口（不再断言“尚未接线”），仍证明“修正/归档/隔离”三个未接线动作写请求为 0，不真正确认删除、不改生产实现。顺带机械同步了同测试内被长期掩盖的两处陈旧断言：合成证据 fixture 缺 `displayLabel`（在 spec 内按 `actorStableRef` 推导注入，不改生产），以及单消息证据当前渲染为单个证据气泡（`.single-evidence` 旧类已不存在）。完整 Playwright 套件三视口真实 PASS，不再标 stale/known failure。

### R1 验证门
| 门 | 结果 |
|---|---|
| cursor/API targeted | PASS（contract 6 + integration 6） |
| S2B list targeted | PASS（23 例，连续 5 次稳定） |
| React targeted + nest-console 四门 | PASS（61） / PASS / PASS / PASS |
| 完整 Playwright 1440/1024/390 | PASS（27 例全 PASS，axe serious/critical/moderate/minor=0） |
| 必要 Java 离线编译 | PASS |
| `git diff --check` / 原型三文件 hash | PASS / MATCH |
| 本轮全仓 Maven、根 Node 四门、架构门 | NOT_RUN_R1_REUSE_TASK45A_PASS |

### R1 完成回执

```
返工状态：ITERATION1_MEMORY_LIST_PAGING_TYPE_FILTER_R1_READY_FOR_LOCAL_COMMIT
中文-emoji筛选指纹／跨query cursor：PASS/REJECTED
current revision真实变化／TOCTOU攻击：PASS/REJECTED
state-query-type-updatedAt最终绑定：PASS
非法type初始-动态-back-forward清理：PASS/PASS/PASS
read-vertical陈旧断言／完整Playwright三视口：UPDATED/PASS
cursor-S2B-React targeted／必要离线编译／diff：PASS/PASS/PASS
本轮Maven-根Node-架构门：NOT_RUN_R1_REUSE_TASK45A_PASS
生产业务修改／测试修改／报告修改：3/4/2
原型三文件／V001—V021／OpenAPI-generated／ContextPack-MCP：MATCH/MATCH/MATCH/MATCH
正文-query-token-capability-secret泄漏：0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\Iteration1-MemoryListPagingTypeFilter-执行报告.md
```

## 5B. R1A｜历史 Revision 反证与 Git Scope 收口

### R1A-01 真正的 historical revision 反证
`LocalV1S2BMemoryQueryTest` 新增 `historicalEventRevisionAndCurrentClaimBindToCurrentRevision`，在真实 PostgreSQL 中构造同一 memory 的两条 revision（historical Event + current Claim，`memory_record.current_revision_id` 精确指向 Claim）。测试经 `LocalV1S2BQueryCoordinator` 全路径与 `JooqMemoryReadAdapter` 直查双断言：
- `memoryType=EVENT` 不返回该 memory；
- `memoryType=CLAIM` 精确返回一次，且 `currentRevisionId`/正文为 current Claim；
- 临时将 DB 实现改为“去掉 `MEMORY_REVISION_ID = CURRENT_REVISION_ID`、仅按 memory_id 匹配任意 revision”后，该测试在直查断言处失败（EVENT 误命中），证明能杀死错误实现；随后已恢复正确实现。
- 原 TOCTOU spy 测试更名为 `finalRecheckSkipsItemWhenCurrentRevisionTypeChangesAfterList`，不再用不存在的“historical”事实冒充。
- fixture `makeCurrentRevision` 使用局部 test-only SQL，临时禁用并始终恢复 `memory_revision_governance_guard` / `memory_record_update_guard` 触发器，不修改生产迁移/触发器；测试前后 `databaseRowSnapshot`/`payloadFileSnapshot` MATCH。

### R1A-02 报告与 Evidence 精确 scope
按 `git status --short -uall`（排除 `reports/local-v1-read-browser-qa/`）机械生成 task scope：modified=14、added=3、deleted=0、taskPaths=17、pathSet 去重 17，与真实 Git 双向完全相等。Evidence 新增 `scope.modified/added/deleted/taskPaths/pathSet/excluded`；Markdown 本段为机械路径清单。

```
apps/api/src/main/java/io/github/candyxi0/hidenest/api/LocalV1CursorCodec.java
apps/api/src/main/java/io/github/candyxi0/hidenest/api/LocalV1MemoryListController.java
apps/api/src/test/java/io/github/candyxi0/hidenest/api/LocalV1ReadContractTest.java
apps/api/src/test/java/io/github/candyxi0/hidenest/api/LocalV1ReadHttpIntegrationTest.java
apps/nest-console/e2e/read-vertical.spec.ts
apps/nest-console/e2e/paging-typefilter.spec.ts
apps/nest-console/src/App.tsx
apps/nest-console/src/app.css
apps/nest-console/src/test/App.test.tsx
modules/application/src/main/java/io/github/candyxi0/hidenest/application/coordinator/LocalV1S2BQueryCoordinator.java
modules/application/src/main/java/io/github/candyxi0/hidenest/application/model/LocalV1S2BListRequest.java
modules/database-adapter/src/main/java/io/github/candyxi0/hidenest/database/adapter/JooqMemoryReadAdapter.java
modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1S2BMemoryQueryTest.java
modules/database-adapter/src/test/java/io/github/candyxi0/hidenest/database/LocalV1S3C2FileDeletionTest.java
modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/port/MemoryReadFilter.java
reports/Iteration1-MemoryListPagingTypeFilter-Evidence.json
reports/Iteration1-MemoryListPagingTypeFilter-执行报告.md
```

### R1A 验证门
| 门 | 结果 |
|---|---|
| `LocalV1S2BMemoryQueryTest` targeted | PASS（24 例） |
| historical/current 反证杀死错误任意 revision 匹配实现 | PASS |
| 数据库/payload 前后快照 MATCH | PASS |
| `git diff --check` | PASS |
| Git scope 14M+3A+0D/17 双向 MATCH | PASS |
| Evidence 三解析门（Node / PowerShell / 严格重复键） | PASS / PASS / PASS |
| staged=0、越界=0、无联网/Git 写 | PASS |
| 全仓 Maven、Node、Playwright、架构门 | NOT_RUN_R1A_REUSE_TASK45A_R1_PASS |

### R1A 完成回执

```
末端收口状态：ITERATION1_MEMORY_LIST_PAGING_TYPE_FILTER_R1A_READY_FOR_LOCAL_COMMIT
historical Event／current Claim真实事实：PASS/PASS
EVENT不命中／CLAIM精确命中：PASS/PASS
任意历史revision错误实现反证：PASS
S2B targeted／数据库-payload快照／diff：PASS/PASS/PASS
Git modified-added-deleted／taskPaths：14-3-0/17
报告-Evidence状态／路径／计数：MATCH/MATCH/MATCH
Node-PowerShell-strict JSON：PASS/PASS/PASS
生产代码／其他测试修改：0/0
本轮Maven-Node-Playwright-架构门：NOT_RUN_R1A_REUSE_TASK45A_R1_PASS
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\Iteration1-MemoryListPagingTypeFilter-执行报告.md
```

## 5A. 完成回执（Task45A 原始）

```
实施状态：ITERATION1_MEMORY_LIST_PAGING_TYPE_FILTER_READY_FOR_HIDE_REVIEW
稳定seek cursor／offset残留：PASS/0
30-30-余数／同时间戳稳定／插入删除不漂移：PASS/PASS/PASS
六类型／state-query-type交集／current revision绑定：PASS/PASS/PASS
cursor筛选绑定／攻击矩阵：PASS/PASS
首屏／加载更多／失败重试：PASS/PASS/PASS
筛选URL／详情返回保留／删除缓存收敛：PASS/PASS/PASS
原型三文件hash／1440-1024-390真实视觉／可访问性：MATCH/PASS/PASS
S2B-API-React targeted／Maven offline／架构门：PASS/PASS/PASS
OpenAPI generation-contracts／Node四门：PASS/PASS
V001—V021／jOOQ generated／ContextPack-MCP／CandidateSet-MemoryEvidence-Deletion：MATCH/MATCH/MATCH/MATCH
正文-query-token-capability-secret泄漏：0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
Docker新增containers-volumes-networks／遗留进程：0-0-0/0
联网／Git写操作：否／否
家庭部署：NOT_STARTED
报告路径：D:\myproject\hide-nest\reports\Iteration1-MemoryListPagingTypeFilter-执行报告.md
```

## 6. 说明

- 真实浏览器 QA 已在本机真实 Chromium（Playwright）执行。R1 封口后完整 Playwright 套件（`read-vertical.spec.ts` + `paging-typefilter.spec.ts`，含 fixture 模式）在 1440×900 / 1024×768 / 390×844 三视口共 27 例全 PASS，所有 axe 扫描 serious/critical/moderate/minor 全为 0，console error/pageerror/Authorization/外部请求均为 0，截图见 `reports/local-v1-read-browser-qa/`。
- R1-04 按工单授权仅机械同步 `read-vertical.spec.ts` 中“永久删除”陈旧断言（该动作已被 Task33B2 接线为删除抽屉），并在同一测试内机械同步两处被长期掩盖的陈旧断言（合成证据缺 `displayLabel`、单消息证据当前渲染形态），均为测试侧修改、不改生产实现；完整套件现已真实 PASS，不再标 stale/known failure。
- 未新增任何数据库迁移/索引/函数/权限；OpenAPI spec 与生成客户端未改动（生成内容与 HEAD 一致）。
- 遗留的 `hide-nest-*` Docker 容器均为数日前既有任务产物（已 Exited），本任务未新增任何 container/volume/network；Testcontainers 测试容器已自动清理。
- 不 stage/commit/push，不部署，不开始正式真实记忆写入口或下一需求。
