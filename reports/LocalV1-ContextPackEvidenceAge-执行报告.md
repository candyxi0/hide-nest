# Task40｜Local V1 ContextPack「证据距今天数」执行报告

## 实施状态

LOCAL_V1_CONTEXT_PACK_EVIDENCE_AGE_R1_READY_FOR_LOCAL_COMMIT

## 变更摘要

在 Nest 返回给 hide 的每条 `ContextPackMemory` 中新增两个字段：

- `evidenceOccurredAt`：该记忆所依赖证据消息中最晚一条的 `occurredAt`。
- `evidenceAgeDays`：`floor((issuedAt - evidenceOccurredAt) / 24h)`，非负整数。

实现严格复用 `LocalV1S2BQueryCoordinator.getFullEvidence(memoryId)`，没有新增第二套证据查询链；`evidenceOccurredAt` 已纳入 delivery manifest 哈希绑定，`evidenceAgeDays` 在重放时由已绑定的 `issuedAt` 与 `evidenceOccurredAt` 确定性派生，确保同幂等键重放字节级 `EXACT`。

## 修改文件

| 领域 | 文件 |
|------|------|
| Application Model | `modules/application/src/main/java/.../model/LocalV1ContextPackMemory.java` |
| Coordinator | `modules/application/src/main/java/.../coordinator/LocalV1ContextPackCoordinator.java` |
| API Mapper | `apps/api/src/main/java/.../api/LocalV1ContextPackResponseMapper.java` |
| OpenAPI & baseline | `contracts/openapi/hide-nest-api.yaml`、 `contracts/compatibility-fixtures/baseline.yaml` |
| 生成 TS client | `packages/api-client-ts/src/generated/models/ContextPackMemory.ts` |
| Codex Adapter 解析 | `apps/codex-adapter/src/context-pack-canonicalizer.ts` |
| Codex Adapter tests | `apps/codex-adapter/src/context-pack-canonicalizer.test.ts`、 `apps/codex-adapter/src/context-pack-client.test.ts`、 `apps/codex-adapter/src/mcp.integration.test.ts` |
| Java targeted tests | `modules/database-adapter/src/test/java/.../LocalV1ContextPackRetrievalTest.java` |

未修改：migration、jOOQ generated、React/UI、部署脚本、MCP 关窗/CandidateSet 行为、正式 failure-code 集合。

## 验证结果

| 验证项 | 结果 |
|--------|------|
| `LocalV1ContextPackRetrievalTest` | 20 测试，0 失败，0 错误 |
| `LocalV1ContextPackEmptyTest` | 2 测试，0 失败，0 错误 |
| `LocalV1ContextPackHttpIntegrationTest` | 5 测试，0 失败，0 错误 |
| Contract tests (`modules/contracts`) | 46 测试，0 失败，0 错误 |
| Codex Adapter `typecheck` | PASS |
| Codex Adapter `lint` | PASS（仅 generated/events 有两条既有 unused-eslint-disable warning） |
| Codex Adapter `test` | 209 passed，1 skipped |
| Codex Adapter `build` | PASS |
| `git diff --check` | PASS（仅 LF→CRLF 转换警告，无 trailing whitespace 错误） |

### Java  targeted 关键新增用例

- `evidenceAgeUsesLatestOccurredAtAndIsNonNegative`：多条证据取最晚 `occurredAt`，天数计算正确。
- `evidenceAgeBoundaryAt24Hours`：`23:59:59` 之前为 0 天，`24:00:00` 整为 1 天。
- `futureEvidenceFailsClosed`：证据时间晚于 `issuedAt` 时返回 `INTERNAL_FAILURE`。
- `futureEvidenceByOneMicrosecondFailsClosed`：未来仅 1 微秒仍精确拒绝。
- `evidenceAgeBoundaryAt24HoursMinusOneMicrosecond`：`24h-1μs` 为 0 天；整 24h 为 1 天。
- `replayAcrossNaturalDayUsesOriginalIssuedAtAndAgeDays`：在 10 分钟有效期内跨过自然日重放，响应与原 delivery 字节级一致，embedding/审计事实增量为 0。
- `replayRejectsEvidenceTimeTampering`：篡改 `evidence.source_unit.occurred_at` 后重放，manifest 哈希不匹配，fail closed。

> 说明：原 `replayRejectsManifestHashTampering` 尝试直接 `UPDATE runtime.context_delivery.manifest_hash`，被数据库触发器 `enforce_context_delivery_invalidation` 以 `HDM006_CONTEXT_DELIVERY_IDENTITY_IMMUTABLE` 拒绝；该保护已由 schema 层面保证，故移除该用例，由 `replayRejectsEvidenceTimeTampering` 覆盖 manifest 绑定失效路径。

### R1 微秒精度收口

- Java 在计算前使用精确 instant 比较拒绝未来时间；不再以整秒截断判断未来。
- Codex Adapter 使用 `BigInt` 纳秒时间轴，RFC3339 最多 9 位小数不再丢失为毫秒。
- 清除了中断工作树中遗留的静态调试状态与异常时间明文。
- 两个未来证据反证用例在 `finally` 中归档自身夹具，避免共享测试库的未来候选污染后续检索。

## 环境

- `JAVA_HOME`：`C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot`
- Java version：`25.0.4`
- PostgreSQL/pgvector image：`pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c`
- Git head：`5b9a1f91579640de7ad031e906dd18f6bc3d6f81` @ `main`
- 越界修改：0
- Staged 文件：0
- Git 写操作：无

## 回执

```text
实施状态：LOCAL_V1_CONTEXT_PACK_EVIDENCE_AGE_R1_READY_FOR_LOCAL_COMMIT
单条／多段最晚证据：PASS/PASS
24h-1μs／24h：0/1
未来1μs／篡改攻击：REJECTED/REJECTED
同键跨日重放／Embedding-审计增量：EXACT/0-0
OpenAPI／Java targeted／Codex Adapter四门：PASS/PASS/PASS
migration-jOOQ-React-deployment修改：0-0-0-0
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
报告路径：D:\myproject\hide-nest\reports\LocalV1-ContextPackEvidenceAge-执行报告.md
```
