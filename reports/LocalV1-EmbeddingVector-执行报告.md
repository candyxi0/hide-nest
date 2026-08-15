# Task34A｜Local V1 Embedding 与 pgvector 最薄纵切 执行报告

状态：`LOCAL_V1_EMBEDDING_VECTOR_REAL_SMOKE_PASS`
冻结基线：HEAD=`bcdde0f799c0f8dcb4d82bc34dcda74ea3560b28`
执行时间：2026-08-15（Asia/Shanghai）

## 一、执行摘要

完成一条最薄、真实、可重复验证的向量闭环（离线路径已全绿）：

> 当前 MemoryRevision 正文 → 家庭服务器 Embedding 服务（/embed 协议）→ 调用方严格校验并 L2 归一化 → PostgreSQL pgvector 持久化 → 精确 cosine 相似查询返回正确记忆。

真实家庭服务器 smoke 已通过（R2）；离线实现、R1 并发封口与全部质量门通过。

## 二、冻结外部服务事实（核对一致）

- 模型 `BAAI/bge-small-zh-v1.5`，GGUF F16，SHA-256 `ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c`
- 服务 `hide-nest-local-embedding-candidate/0.1`，`llama-cpp-python 0.3.21`，维度 512，最大 batch 32，超时 30000ms
- 服务端不归一化；调用方执行 L2 归一化；`/health` + `/embed`（非 OpenAI-compatible）
- 实测确认：pgvector `0.8.2`；`public.vector(512)` 类型、`public.vector_norm(vector)` 可用；`<=>` 需 `OPERATOR(public.<=>)` 限定；`vector_dims(vector)`/`l2_norm(vector)` 不存在

## 三、实现内容

### 新模块 / 文件

| 位置 | 文件 | 说明 |
|---|---|---|
| modules/memory | `port/EmbeddingProviderPort.java` | 嵌入 provider port（health/embed + 不可变 result） |
| modules/memory | `port/MemoryVectorStorePort.java` | 向量 store/search port（upsert/search + 不可变 request/result） |
| modules/memory | `port/ModelFingerprint.java` | 模型指纹（模型名+SHA+维度+归一化） |
| modules/embedding-adapter | `HttpEmbeddingProviderAdapter.java` | JDK HttpClient + Jackson，/health /embed，fail-closed |
| modules/application | `LocalV1VectorCoordinator.java` | 唯一向量协调器（锁定/校验/归一化/持久化/查询） |
| modules/application | `VectorMath.java` / `LocalV1VectorException.java` / model records | L2 数学、异常、最小结果 |
| modules/database-adapter | `JooqVectorStoreAdapter.java` | 向量 store/search 实现（raw SQL `?::public.vector` + `OPERATOR(public.<=>)`） |
| modules/database-adapter | `db/migration/V018__memory_revision_embedding.sql` | 记忆版本向量表 |
| modules/database-adapter | `src/generated/java/**`（重生成） | jOOQ 生成物（A/B/tracked 一致） |

### 修改文件

- 根 `pom.xml`（登记 embedding-adapter 模块）
- `modules/database-adapter/pom.xml`、`modules/architecture-tests/pom.xml`（新增 embedding-adapter 测试依赖）
- `modules/architecture-tests/**`（新增 HTTP/Jackson/embedding-adapter 边界规则 + 3 个同规则非法 fixture + 2 个 adapter 完整性测试）
- 数据库测试迁移计数 17→18（database-adapter 12 个文件、apps/api 3 个文件）
- `DatabaseSliceBContractTest`（表清单 +1、vector 列计数 0→1、migrator 表 37→38、FK 策略：唯一 CASCADE 为 embedding 表）

## 四、R1 返工（并发与烟测前封口）

### R1-01｜Embedding 期间 current revision 精确变化

`LocalV1VectorCoordinator.persist` 第二事务现在同时复核：`record.currentRevisionId == snapshot.revisionId`、`revision.memoryRevisionId == snapshot.revisionId`、`revision.memoryId == snapshot.memoryId`、`revision.revisionNo == snapshot.revisionNo`、正文 SHA 一致。任一不符稳定抛 `CURRENT_POINTER_CHANGED`（不新增正式 failure code），且在 vector INSERT 前拒绝。

新增真实测试 `sameBodyNewRevisionConcurrentChangeIsRejected`：embedding provider 用 latch 阻塞，调用期间把 current pointer 改到“正文相同、revision ID/revisionNo 不同”的新版本，释放后稳定拒绝，向量行 0。

### R1-02｜同值并发 upsert 稳定幂等

`JooqVectorStoreAdapter.upsertEmbedding` 从 `SELECT→INSERT` 改为原子 `INSERT ... ON CONFLICT ON CONSTRAINT memory_revision_embedding_fingerprint_unique DO NOTHING`。成功返回 `Inserted`；冲突时同一事务精确读既有行并校验完整模型指纹 + embedded body hash：同 hash 返回 `AlreadyPresent`，异 hash 稳定抛 `HDM018_VECTOR_BODY_HASH_CONFLICT`（不泄漏 SQL/正文/向量）。

新增并发测试（两线程独立连接同步起跑）：同值 `1 Inserted + 1 AlreadyPresent`、异值 `1 成功 + 1 VECTOR_CONFLICT`、事实数均为 1。

### R1-03｜不可变契约与 URL 最小加固

- `EmbeddingResult` 构造时对每个 `double[]` 深复制，accessor 返回全新副本（测试 `embeddingResultDeepCopiesVectors`）。
- HTTP adapter base URL 只接受 `http` scheme + loopback host + 显式有效端口 + 空/`/` path + 无 user-info/query/fragment（测试 `rejectsUrlAttackMatrix`）。

### R1-04｜jOOQ 生成树说明

本轮不删除已生成的 V014—V017 routine/table-valued-function 文件。`db.ps1 -Action GenerateCheck` 三向（A/B/tracked）一致；生成用 `clean=true` 且输出日志含“Removing excess files”，故孤儿 0；生成基于全新一次性 DB（V001—V018 迁移），不含任何测试临时函数/trigger/随机对象。本次生成补齐此前未进入 tracked tree 的历史 V012—V017 数据库对象（`is_erasure_marker_active_for`、`deletion_erasure_marker`、`deletion_run`、`deletion_payload_task`、`execute_confirmed_deletion_database_phase`、`complete_deletion_run`、`record_deletion_file_failure`、`settle_deletion_payload_task`、`reject_if_*_fenced_checked` 等）以及 V018 新表 `memory_revision_embedding`，并非只有 V018 生成变化。

## 四-bis、R2 真实服务标识纠偏与烟测

### 事实纠偏（已确认）

hide 通过隧道真实探测（2026-08-15 20:40）与本轮首测一致确认：

- 模型家族/来源：`BAAI/bge-small-zh-v1.5`（仅报告说明）
- GGUF 形态：F16
- **服务协议 model ID：`bge-small-zh-v1.5-f16`**（`/health` 与 `/embed` 的 `model` 字段）
- 维度 512、GGUF SHA-256、归一化等其他事实不变。

### 纠偏实现

- `LocalV1EmbeddingVectorSmokeTest.MODEL` 改为 `bge-small-zh-v1.5-f16`；真实链 `ModelFingerprint.modelName` 持久化即用该精确 service model ID（模型家族仅作报告，不混为同一字段）。
- `HttpEmbeddingProviderAdapter.health()` 现解析 `/health` 响应并校验 model/dimension：`healthy = 2xx 且 model、dimension 精确匹配`，否则 `healthy=false`。
- smoke 增加显式 `health()` 断言（healthy、model、dimension 匹配后才写向量）。
- `HttpEmbeddingProviderAdapterTest.healthProbesAndDisabledBaseUrl` 更新为返回真实 health JSON（model+dimension）。

### HTTP/1.0 连接复用缺陷修复（第二次真实失败根因）

真实服务为 Python `ThreadingHTTPServer`，响应 `HTTP/1.0`（无 keep-alive）。adapter 原先持有一个跨请求复用的 JDK `HttpClient`（keep-alive 连接池），`health()` 用掉的连接被 HTTP/1.0 服务端关闭后，`embed()` 复用陈旧连接 → `transport failure` → `EMBEDDING_UNAVAILABLE`。离线测试从未暴露（离线 fixture 为 HTTP/1.1 keep-alive）。

修复：`HttpEmbeddingProviderAdapter.health()` 与每次 `embed()` 各自 `try (HttpClient client = newHttpClient())` 创建全新 JDK 25 `HttpClient` 并确定性关闭；禁止跨请求复用、禁止自动重试、未设置 `Connection: close` 或 `jdk.httpclient.allowRestrictedHeaders` 等全局属性。

新增回归 fixture：`HttpEmbeddingProviderAdapterTest.http10ServerConsecutiveRequestsUseIndependentConnections` 用 raw `ServerSocket` 模拟 HTTP/1.0（每条连接只处理一个请求并主动关闭），证明 `health → embed A → embed B → embed replay → embed query` 连续成功且每次独立连接（连接计数=5）。

### 三次真实失败证据（保留，不伪装）

1. **首次失败（SERVICE_MODEL_ID_FACT_MISMATCH）**：旧期望值 `bge-small-zh-v1.5` → `EMBEDDING_UNAVAILABLE`；适配器正确拒绝旧 model，非隧道/模型/PG 故障。
2. **第二次失败（TUNNEL_DROPPED）**：纠偏后首轮 `health()` 曾实测返回正确 model，随后 SSH 隧道中断（`127.0.0.1:18090` connection refused），smoke 未完成，未判 PASS。
3. **第三次失败（HTTP_1_0_CONNECTION_REUSE）**：隧道恢复后 smoke 确定性失败于 `embed()` 的 `transport failure`；定位为 HTTP/1.0 连接复用缺陷（上文已修复）。

### 真实 smoke 最终结果（PASS）

- health：PASS（model=`bge-small-zh-v1.5-f16`、dimension=512）。
- 合成 A/B 均经真实 `/embed`。
- 查询后：A 第 1、`score(A)=0.4164388179779053 > score(B)=0.13017562419699513`。
- 两条落库向量 dimension=512、范数 `~1.0`（容差内）；重复索引 A 幂等 `idempotent=true`。
- hash：A=`b1df966ed69f734e96b7a24104acd2d4d4e1e9e6bd2fe5275740bce94a93c0d8`、B=`c83ff1b7b1837ed3666a0cbf2e08debeb0ba024aa328d4aadeb8de6679a798eb`；耗时 705ms。

## 五、质量门结果

| # | 门 | 结果 |
|---|---|---|
| 1 | V018 空库/升级/重复 | 18 / 1 / 0（`LocalV1V017SharedEvidenceMigrationTest` 升级 2、重复 0） |
| 2 | pgvector 版本 | 0.8.2（V001 断言 + 实测） |
| 3 | jOOQ A/B/tracked | MATCH（`db.ps1 -Action GenerateCheck` PASS；旧签名残留 0） |
| 4 | targeted tests | 31 PASS（VectorMath 5 + EmbeddingAdapter 13 + EmbeddingVector 13，Surefire XML；含 R1 并发/深复制/URL 攻击矩阵 + HTTP/1.0 fixture） |
| 5 | 架构测试/非法 fixture | 29 PASS（Arch 7 + DatabaseBoundary 9 + PortBoundary 13；fixture 全部被拒） |
| 6 | `mvnw.cmd -o clean verify`（JDK 25） | PASS（database-adapter 289、contracts 46、api 40、worker 5、arch 29） |
| 7 | Node 四门 | NOT_RUN_UNCHANGED（本工单未改 Node；`apps/*`、`packages/*` 未动） |
| 8 | `git diff --check` | PASS（无 whitespace 错误） |
| 9 | V001—V017 / contracts / React-prototype / MCP | MATCH / MATCH / MATCH / MATCH（仅新增 V018） |
| 10 | 正文-完整向量-path-token-capability-secret-主机地址泄漏 | 0-0-0-0-0-0-0（源码/报告无正文、向量、路径、token、capability、secret、主机地址） |
| 11 | Git index 起止 / staged / 越界 | MATCH / 0 / 0 |
| 12 | Docker 新增 containers-volumes-networks / 遗留进程 | 0-0-0 / 0（已清理本工单一次性容器） |

## 六、真实服务烟测

真实家庭服务器 smoke 已通过（详见四-bis）：health PASS、合成 A/B 经真实 `/embed`、A 第 1 且 `score(A) > score(B)`、512 维、范数容差、幂等、指纹一致。烟测 runner 为 `LocalV1EmbeddingVectorSmokeTest`（`@EnabledIfEnvironmentVariable` 仅在基址环境变量就绪时启用），仅记录合成标签/正文 SHA-256/分数/维度/范数/耗时。

## 七、越界与禁止项

- 未修改 V001—V017、正式 OpenAPI/inventory/baseline/API 客户端、React/prototype、MCP closeout/deletion 工具。
- 未修改冻结 failure code 50、event type 12、operation 30。
- 未新增第三方依赖（embedding-adapter 仅用 JDK HttpClient + 仓库现有 Jackson 管理版本；未引入 pgvector Java 库）。
- 未新增公共 HTTP operation、UI、自动后台轮询。

## 八、完成回复

```text
烟测状态：LOCAL_V1_EMBEDDING_VECTOR_REAL_SMOKE_PASS
首次失败根因：SERVICE_MODEL_ID_FACT_MISMATCH
模型家族／service model ID：BAAI/bge-small-zh-v1.5／bge-small-zh-v1.5-f16
真实 health／embed：PASS/PASS
真实 A-B 排序／分数关系：A_FIRST/A_GT_B
维度／调用方L2／范数：512/PASS/PASS
重复索引／pgvector exact cosine：EXACT/PASS
真实 smoke tests：1/0/0/0
正文-完整向量-path-token-capability-secret-主机地址泄漏：0-0-0-0-0-0-0
Git index／staged／越界：MATCH/0/0
Docker新增containers-volumes-networks／payload／遗留进程：0-0-0/0/0
联网：仅127.0.0.1隧道
Git写操作：否
报告路径：D:\myproject\hide-nest\reports\LocalV1-EmbeddingVector-执行报告.md
```

完成即停。
