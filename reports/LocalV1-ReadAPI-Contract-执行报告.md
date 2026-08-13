# Local V1 Read API 响应契约补齐 — 执行报告

## 执行摘要

- **工单**: Task30A-LocalV1-ReadAPI响应契约补齐工单
- **仓库**: D:\myproject\hide-nest
- **基线 HEAD**: `bedba3279cc9d8a9edf8931b767614d8cb3b3ec8`
- **执行日期**: 2026-08-12
- **执行者**: DeepSeek (Claude Code)

---

## 契约状态：LOCAL_V1_READ_API_CONTRACT_READY_FOR_HIDE_REVIEW

新增 schema／总 schema：**2／48**
MemoryDetail／MemoryEvidenceItem 精确字段：**PASS／PASS**
wrapper Object 残留：**0**
operation／failure code／event type：**30／50／12**
正式 spec／baseline：**MATCH**
Java／TS 真实生成：**PASS／PASS**
双次生成／tracked TS：**PASS／PASS**
契约测试／TS typecheck：**PASS／PASS**
V001—V015／业务代码／API启动代码：**MATCH／MATCH／MATCH**
真实 Git index：**未变**
工作区：tracked=**6**、untracked=**3**、staged=**0**、越界=**0**
联网／Git写操作：**否／否**

---

## 变更清单

### 1. contracts/openapi/hide-nest-api.yaml

- 新增 `MemoryDetail` 命名 schema（10 字段，9 required + 1 optional `uncertaintyCode`，`additionalProperties: false`）
- 新增 `MemoryEvidenceItem` 命名 schema（8 字段，全部 required，`additionalProperties: false`）
- `MemoryDetailResponse.memory` 改为 `$ref: "#/components/schemas/MemoryDetail"`（移除匿名空 object）
- `MemoryEvidenceResponse` 新增 `memoryId`、`currentRevisionId`、`revisionNo` 必填字段
- `MemoryEvidenceResponse.evidenceItems[]` 改为 `$ref: "#/components/schemas/MemoryEvidenceItem"`（移除匿名空 object）
- Schema 计数标记更新：42 total (9 enums + 33 API/DTO) → 44 total (9 enums + 35 API/DTO)
- 30 operation、operationId、所有路径、security、no-store、Problem status 全部未变

### 2. contracts/inventory/ContractInventory-HDM-003-v0.1.json

- `schemas.apiSchemas` 新增 `MemoryDetail` (source: Task30A-4.1) 和 `MemoryEvidenceItem` (source: Task30A-4.2)
- `schemas.totalSchemaCount`: 46 → 48
- `statistics.apiSchemas`: 33 → 35
- `statistics.totalSchemaCount`: 46 → 48

### 3. contracts/compatibility-fixtures/baseline.yaml

- 旧 SHA256: `4f449ab008484c5ec85bea2d89d46f6c4a25300b07d4ca52fd71bde89f066b97`
- 新 SHA256: `f25343d1c4354962982929ecbbb65cc1a48c21e8da45ae2a7db8f8b70bb98aa0`
- 更新后与正式 spec 逐字节完全一致（diff 验证通过）
- Synthetic compatibility fixtures 未修改

### 4. modules/contracts 新增测试

- `ReadApiContractTest.java` — 9 个测试方法：
  - `memoryDetailSchemaMustHaveExactFieldsAndRequiredSet` — MemoryDetail 字段/required/format/minimum 精确匹配
  - `memoryEvidenceItemSchemaMustHaveExactFieldsAndAllRequired` — MemoryEvidenceItem 字段/required/format/minimum 精确匹配
  - `memoryDetailResponseWrapperMustUseRefNotAnonymousObject` — wrapper 使用精确 $ref
  - `memoryEvidenceResponseMustHaveMemoryIdAndRevisionFields` — wrapper 新增字段
  - `memoryEvidenceResponseItemsMustUseRefNotAnonymousObject` — evidenceItems 使用精确 $ref
  - `mutationRevertingToAnonymousObjectMustBeDetected` — 改回匿名 Object 可检测
  - `mutationRemovingRequiredFieldMustBeDetected` — 删除 required 字段可检测
  - `mutationAddingForbiddenFieldMustBeDetected` — 加入 objectRef 禁字段可检测
  - `formalSpecAndBaselineMustBeByteIdentical` — 正式 spec 与 baseline 字节一致

### 5. packages/api-client-ts 生成物

- 新增: `models/MemoryDetail.ts` — 明确 `MemoryDetail` interface（无 Object 或 Record）
- 新增: `models/MemoryEvidenceItem.ts` — 明确 `MemoryEvidenceItem` interface
- 更新: `models/MemoryDetailResponse.ts` — `memory: MemoryDetail`（非 Object）
- 更新: `models/MemoryEvidenceResponse.ts` — `evidenceItems: Array<MemoryEvidenceItem>`（非 Object）+ 新增 `memoryId`/`currentRevisionId`/`revisionNo`
- 更新: `models/index.ts` — 新增两个 export

---

## 验证结果

| 检查项 | 结果 | 说明 |
|--------|------|------|
| Maven 离线 test（46 tests） | PASS | 0 failures, 0 errors |
| generate.ps1 -Check | PASS | 双次生成一致，tracked TS 一致，comparator 突变检测正常 |
| npm typecheck | PASS | tsc --noEmit 零错误 |
| Operation 30/30 | PASS | OperationMatrixTest 通过 |
| Failure code 50 | PASS | EnumCompletenessTest 通过 |
| Event type 12 | PASS | EnumCompletenessTest 通过 |
| Spec/baseline 字节一致 | PASS | diff 无差异 |
| Object 残留 | 0 | 所有 TS 类型为 MemoryDetail/MemoryEvidenceItem，无 Object/Record |
| 越界文件 | 0 | 仅允许路径内文件被修改 |

---

## 未修改内容（按工单要求）

- 30 个 operation/operationId/路径/security/caller role/no-store/Problem status — 全部不变
- MemoryListItem — 本轮不改
- 9 个共享 enum、50 个 failure code、12 个 event type — 全部不变
- OpenAPI server 仍为 `/v1`
- `application`、`database-adapter`、migration、`apps/api`、`apps/web`、前端设计包 — 未修改
- Synthetic compatibility fixtures — 未修改
- 无 policy grant、token、objectRef、contentHash、文件路径、provider metadata、旧 revision 正文或删除前正文

---

## 生成文件清单

```
contracts/compatibility-fixtures/baseline.yaml
contracts/inventory/ContractInventory-HDM-003-v0.1.json
contracts/openapi/hide-nest-api.yaml
modules/contracts/src/test/java/io/github/candyxi0/hidenest/contracts/ReadApiContractTest.java
packages/api-client-ts/src/generated/models/index.ts
packages/api-client-ts/src/generated/models/MemoryDetail.ts
packages/api-client-ts/src/generated/models/MemoryDetailResponse.ts
packages/api-client-ts/src/generated/models/MemoryEvidenceItem.ts
packages/api-client-ts/src/generated/models/MemoryEvidenceResponse.ts
```
