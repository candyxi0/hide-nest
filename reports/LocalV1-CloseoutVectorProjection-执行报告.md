# Task35A1｜Local V1 关窗后向量索引投影纵切 执行报告

状态：`LOCAL_V1_CLOSEOUT_VECTOR_PROJECTION_REAL_TUNNEL_SMOKE_PASS`
冻结基线：HEAD=`ae2c0650d9c27ca2b107be274e20891c4d74e562`
执行时间：2026-08-15 / 2026-08-16（Asia/Shanghai，R1 真实隧道封口）

## 一、执行摘要

完成关窗后的向量索引投影纵切（离线门全绿，真实隧道不可用，停在 hide 复核门）：

> 正式 closeout 规范事实先独立提交 → 事务外索引当前 MemoryRevision → submit 与 run status 精确反映 `INDEX_READY`；Embedding 失败绝不回滚已经保存的记忆，同值重放可补索引。

不启用通用 Worker、不改 React、不改 MCP、不改 ContextPack、不改冻结枚举、不新增表或迁移。base URL 仅来自 `HIDE_NEST_EMBEDDING_BASE_URL`，默认空值（索引不可用但 closeout 仍可保存），不硬编码任何 SSH 用户 / Tailscale IP / 家庭服务器地址 / 转发命令。

## 二、冻结模型事实（核对一致）

- family：`BAAI/bge-small-zh-v1.5`（仅报告说明）
- service model ID：`bge-small-zh-v1.5-f16`（`ModelFingerprint.modelName` 持久化值）
- GGUF SHA-256：`ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c`
- dimension：`512`
- normalization：`CALLER_L2`

## 三、实现内容

### 新文件

| 位置 | 文件 | 说明 |
|---|---|---|
| modules/application | `coordinator/LocalV1CloseoutVectorProjectionCoordinator.java` | 投影装饰器：canonical 先提交 → 事务外 `indexCurrentRevision` → `INDEX_READY`/`CANONICAL_COMMITTED`；run status 只读精确复核 |
| modules/database-adapter | `LocalV1CloseoutVectorProjectionTest.java` | 离线确定性门 12 项（真实 PG/pgvector + 假 HTTP /embed） |
| apps/api | `LocalV1CloseoutVectorProjectionHttpIntegrationTest.java` | HTTP 装配证明 2 项（base URL 绑定 + INDEX_READY 贯通） |

### 修改文件

- `modules/memory/port/MemoryVectorStorePort.java`：新增只读 `hasEmbedding(...)` 精确复核方法（禁止重嵌入）
- `modules/database-adapter/adapter/JooqVectorStoreAdapter.java`：实现 `hasEmbedding(...)`（精确 `memory_revision_id + model_name + gguf_sha256 + dimension + normalization + embedded_body_sha256` 的 SELECT 1）
- `apps/api/pom.xml`：新增 `hide-nest-embedding-adapter` 依赖
- `apps/api/LocalV1CloseoutWriteConfiguration.java`：装配 `JooqVectorStoreAdapter`、`HttpEmbeddingProviderAdapter`（`@Value("${HIDE_NEST_EMBEDDING_BASE_URL:}")`）、`ModelFingerprint`（冻结事实）、`LocalV1VectorCoordinator`、`LocalV1CloseoutVectorProjectionCoordinator`
- `apps/api/LocalV1CloseoutWriteController.java`、`LocalV1RunStatusController.java`：改注入投影协调器

## 四、唯一投影顺序与失败语义（核对）

1. 先调用冻结 `LocalV1CloseoutWriteCoordinator.submit`（canonical 主链，逐阶段独立事务提交）。
2. 确认 Memory/Revision/Evidence/closeout receipt 已提交（`closeout.submit` 返回即已提交）。
3. 再在独立事务边界调用 `vector.indexCurrentRevision(memoryId)`（其内部 `readAndLock` → `embed` → `persist` 两段独立事务，embed 落在两事务之间）。
4. 索引成功或同值向量重放：返回原 `runId/memoryId`，phase=`INDEX_READY`。
5. Embedding/transport/响应/向量写入失败：返回已保存的 `CANONICAL_COMMITTED`，不回滚、不删除、不改成 `FINAL_FAILED`。
6. 同值 closeout 重放再次尝试缺失索引；成功后收敛 `INDEX_READY`。
7. 已索引重放不产生第二个 vector 事实（`ON CONFLICT ... DO NOTHING`）。

不允许把 embedding 放进 closeout 的规范事实事务（`closeout.submit` 原样未动，embedding 在返回之后）。

## 五、run status 精确事实复核（核对）

`GET /v1/runs/{runId}` 只有当前 revision 存在完全匹配以下事实的向量才返回 `INDEX_READY`：

- memoryRevisionId（当前指针）；
- model family（`ModelFingerprint.modelName` = service model ID）；
- GGUF SHA-256；
- dimension=512；
- normalization=`CALLER_L2`；
- 当前正文 SHA-256。

无向量、旧 revision、错模型、错正文 hash、错 current pointer 均保持 `CANONICAL_COMMITTED`。判断 ready 只读 `hasEmbedding`，禁止重调 Embedding。

## 六、离线确定性门结果（12 项全 PASS）

| # | 项 | 结果 |
|---|---|---|
| 1 | Embedding 抛错时 Memory/Revision/Evidence/closeout receipt 已真实存在，phase=CANONICAL_COMMITTED | PASS（`embeddingFailureKeepsCanonicalFactsCommitted`） |
| 2 | embedding 发生在规范事实提交之后，不在同一数据库事务内 | PASS（`embeddingRunsAfterCanonicalCommitInSeparateTransaction`：embed 内新鲜读已见已提交事实） |
| 3 | 同值重放补齐缺失向量并收敛 INDEX_READY | PASS（`replayBackfillsMissingVectorAndConvergesToIndexReady`） |
| 4 | 已索引重放 EXACT，vector 事实数恒为 1 | PASS（`alreadyIndexedReplayDoesNotProduceSecondVectorFact`） |
| 5 | 同 key 异值 / 内容哈希攻击 / 事实重放保护不回退 | PASS（`sameSubmissionDifferentContentIsRejectedWithoutVector` + 既有 `LocalV1CloseoutWriteHttpIntegrationTest` 18 项） |
| 6 | run status：正确向量=INDEX_READY；无向量/旧 revision/错模型/错正文 hash=CANONICAL_COMMITTED | PASS（`runStatusReflectsReadinessAfterVectorRemoved`、`runStatusRejectsWrongModelFingerprint`、`runStatusRejectsWrongBodyHash`、`runStatusRejectsStaleRevisionAfterPointerChange`） |
| 7 | current pointer 在索引期间变化时拒绝旧向量，不伪 ready | PASS（`currentPointerChangeDuringIndexFallsBackToCanonicalCommitted` + 既有 `sameBodyNewRevisionConcurrentChangeIsRejected`） |
| 8 | Embedding 4xx/5xx/invalid JSON/模型错/维度错稳定失败且零正文/向量/URL 泄漏 | PASS（`embeddingFailureModesKeepCloseoutCommittedWithoutVector` + 既有 `HttpEmbeddingProviderAdapterTest` 13 项） |
| 9 | 默认未配置 base URL 时 API 可保存 closeout，不尝试公网/未知地址 | PASS（既有 `LocalV1CloseoutWriteHttpIntegrationTest` 空 base URL → CANONICAL_COMMITTED） |
| 10 | 既有 closeout、read、delete、Task34 vector 测试不回退 | PASS（全量 verify 全绿） |

## 七、真实隧道烟测（R1 封口，PASS）

真实家庭 Embedding 服务 `http://127.0.0.1:18090` 可用，经正式 Local V1 合成 profile + 真实 API + 真实 PostgreSQL + 真实 PayloadStore 完成唯一合成记忆闭环（未改实现，未重跑离线全仓门）。

### 7.1 health

`GET /health` → HTTP 200，`{"status":"ok","model":"bge-small-zh-v1.5-f16","dimension":512}`。

### 7.2 唯一合成记忆链

- 合成 submissionId / runId：`e4d3c2b1-a1b2-c3d4-e5f6-1a2b3c4d5e6f`
- 确定性 memoryId：`ec7be3ab-f580-33ff-854d-3cd3dad40568`
- 正文 SHA-256：`15757ab1d9ee1de292de1cbfc0f398b59e8c8313931f3782e7447b82675ea90e`

| 步骤 | 结果 |
|---|---|
| 首次 `POST /v1/closeout-submissions` | HTTP 202，phase=`INDEX_READY`，runId=`e4d3c2b1-…` |
| Memory / Revision / Evidence / closeout run / receipt | 1 / 1 / source=1·unit=2·anchor=1·EVIDENCED_BY=1 / 1(COMPLETED) / 1 |
| 当前 revision 向量事实 | 恰 1；model=`bge-small-zh-v1.5-f16`、GGUF SHA=`ab9b81d9…`、dimension=512、normalization=`CALLER_L2`、正文 SHA=`15757ab1d…`、`vector_norm≈1.0`（未读取/输出完整向量） |
| `GET /v1/runs/{runId}` | HTTP 200，phase=`INDEX_READY` |
| 同请求同幂等键重放 | HTTP 202，runId 完全一致，phase=`INDEX_READY` |
| 重放后事实数 | Memory/Revision/receipt/vector/evidence source 均仍为 1，无第二条事实 |
| 真实 embed 往返耗时 | 约 1.24s |

### 7.3 清理

烟测结束后已移除本轮一次性 PostgreSQL 容器、API 进程、临时 payload/脚本/日志；保留 pinned 镜像。Docker 新增 containers-volumes-networks=0-0-0；遗留进程=0。Git index 起止 MATCH（`46022c5ee10896f4cbdc47c04f018912a64e8a01`），生产/测试文件内容 hash 起止 MATCH，staged=0，越界=0。

## 八、质量门结果

| # | 门 | 结果 |
|---|---|---|
| 1 | targeted tests（Surefire XML） | PASS：`LocalV1CloseoutVectorProjectionTest`=12、`LocalV1CloseoutVectorProjectionHttpIntegrationTest`=2；回归 `LocalV1EmbeddingVectorTest`=13、`LocalV1CloseoutWriteHttpIntegrationTest`=18、`HttpEmbeddingProviderAdapterTest`=13、`VectorMathTest`=5 |
| 2 | `mvnw.cmd -o clean verify`（JDK 25） | PASS（database-adapter 301、contracts 46、api 42、worker 5、arch 29、embedding 13、application 50、payload 13；smoke 1 skipped） |
| 3 | 正式架构门／非法 fixture | PASS/PASS（Architecture 7 + DatabaseBoundary 9 + PortBoundary 13 = 29） |
| 4 | V001—V018、database generated、contracts、React-prototype、MCP | MATCH（未改动） |
| 5 | Node 未改 | NOT_RUN_UNCHANGED（`apps/*`、`packages/*` 未动） |
| 6 | `git diff --check` | PASS（无 whitespace 错误） |
| 7 | query/正文/完整向量/path/token/capability/secret/主机地址泄漏 | 0-0-0-0-0-0-0-0 |
| 8 | Git index 起止 MATCH；staged=0；越界=0 | MATCH/0/0 |
| 9 | Docker 新增 containers-volumes-networks／payload／进程 | 0-0-0/0/0 |
| 10 | 未联网（真实 smoke 只允许既有 127.0.0.1 隧道）；无 Git 写操作 | 仅 127.0.0.1 探测（隧道不可用）；否 |

## 九、越界与禁止项

- 未修改 V001—V017、V018 迁移、database generated、OpenAPI/inventory/baseline/API 客户端、contracts、React/prototype、MCP、ContextPack、DeepSearch。
- 未启用通用 Worker（未设置 `hide.outbox.worker.enabled=true`；未新增后台循环/线程/调度器/outbox 改写）。
- 未修改冻结枚举（`RunPhase.INDEX_READY` 为既有值）、冻结 failure code、event type、operation。
- 未新增表或迁移；未硬编码 SSH 用户 / Tailscale IP / 家庭服务器地址 / 转发命令。

## 十、完成回复

```text
烟测状态：LOCAL_V1_CLOSEOUT_VECTOR_PROJECTION_REAL_TUNNEL_SMOKE_PASS
真实health／embed：PASS/PASS
正式closeout／vector／run status：PASS/PASS/PASS
首次／同值重放phase：INDEX_READY/INDEX_READY
runId-memoryId重放：EXACT/EXACT
规范Memory-Revision-Evidence-receipt：1-1-2-1
当前revision向量事实／重复事实：1/0
模型ID／维度／调用方L2：bge-small-zh-v1.5-f16/512/PASS
报告-Evidence一致性／泄漏扫描：PASS/PASS
真实Git index／文件hash／staged／越界：MATCH/MATCH/0/0
Docker新增containers-volumes-networks／payload／遗留进程：0-0-0/0/0
联网：仅既有Tailscale隧道后的127.0.0.1；其他否
Git写操作：否
报告路径：D:\myproject\hide-nest\reports\LocalV1-CloseoutVectorProjection-执行报告.md
```

完成即停。禁止 stage、commit、push。
