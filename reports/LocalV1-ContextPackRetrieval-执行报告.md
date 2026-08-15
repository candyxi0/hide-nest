# Local V1 ContextPack 最薄检索与审计纵切 执行报告（R1 封口 + R2 真实隧道烟测）

> 工单：Task35A2 + Task35A2-R1 + Task35A2-R2
> 冻结基线：`efd5e105d14c5f707fd53ada3bdcd3510c3804e8`（未变）
> 完成状态：`LOCAL_V1_CONTEXT_PACK_REAL_TUNNEL_SMOKE_PASS`

## 0. 结论

保留 Task35A2 主体与 R1 封口（API 角色写权限、回放可见性门、S2B 异常分类、提交竞态分类），
并完成真实家庭 Embedding 隧道烟测：正式 closeout A/B 自动索引 → 正式 context-packs 语义查询 →
pgvector 排序 → S2B 当前可见性 → 审计 → 无 Embedding 回放。全部 PASS，未改实现、未 stage/commit/push。

## 1. R1 封口（回顾）

- R1-01：V019 补 `hide_nest_api` 对三张审计表 `SELECT, INSERT` + `EXECUTE ON valid_receipt_manifest`；
  真实 `SET ROLE` 角色测试通过，UPDATE/DELETE/越界写 `42501` 拒绝。
- R1-02：回放 fail-closed 复核（invalidated/expired/可见性），六类攻击 REJECTED，旧正文 0。
- R1-03：`verifyCandidate` 仅对可见性变化跳过，结构损坏/非 S2B 异常 → `INTERNAL_FAILURE`。
- R1-04：提交竞态同 key 异值精确 `IDEMPOTENCY_KEY_REUSED`，确定性并发测试通过。

## 2. R2 真实隧道烟测

- `GET http://127.0.0.1:18090/health` → 200，model=`bge-small-zh-v1.5-f16`，dimension=512。
- 正式 closeout A/B：HTTP 202，phase 均 `INDEX_READY`，Memory/current Revision/vector 事实各 1，
  向量模型指纹与正文 hash 精确（完整向量未输出）。
- 正式 context-packs 语义查询：HTTP 200，`SUCCEEDED`；A 排 B 前，`score(A) > score(B)`；
  A/B 的 memoryId/currentRevisionId/revisionNo/policyRevisionNo/memoryType/bodyText/score 与
  数据库/S2B 当前事实精确一致；policyRevisionSet 与交付集严格相等；
  RetrievalTrace.consideredIds 保持候选顺序、deliveredIds 与 response 顺序严格相等；
  ContextDelivery manifest、V019 items、receipt 精确绑定；trace/delivery/item/receipt 各恰好一组。
- 真实无 Embedding 回放：正常停 API → 同库、不配置 `HIDE_NEST_EMBEDDING_BASE_URL` 重启 →
  同 key 同请求 replay 仍 2xx，response 与首次完全相同，trace/delivery/item/receipt 事实增量 0。

### 2.1 烟测事实（仅合成标题/ID/hash/分数/计数/耗时）

- A（粉色偏好）：memoryId=`9e1a05b3-4556-36b0-a93e-e5c3e92025b7`，
  revisionId=`675ae490-c337-46b2-9671-71dbde5fe471`，revisionNo=1，policyRevisionNo=1，
  memoryType=INTERPRETATION，bodyHash=`c014dac774807e18bf9622bd043f243ef8bce0262220de334dad2385290018a8`，
  score=0.48789390993750725
- B（家庭服务器预算）：memoryId=`7e26de1c-6cbb-3536-9603-a79e51f4c27e`，
  revisionId=`afb6bfbb-e13f-49ae-b1b0-ea7d08c0e625`，revisionNo=1，policyRevisionNo=1，
  memoryType=INTERPRETATION，bodyHash=`ed2aea1bf883295c6c9c17728a603dbc382cccb0ada55f3b9c8043ffdeb6ff38`，
  score=0.1583985537290573
- queryHash=`fb19f47943e5b63577cb172d181d9a773370dff7bdd7a74d516299d9cdd2f60e`
- 顺序：A_FIRST；分数：A_GT_B（0.4879 > 0.1584）
- 事实：trace=1、delivery=1、item=2、receipt=1（1-1-2-1）
- 回放：EXACT；重复 embedding=0；审计事实增量=0
- 耗时：1612ms

## 3. 质量门

| 门 | 结果 |
|---|---|
| 真实 health / A-B closeout-index | PASS/PASS |
| 正式 context-packs / resultCategory | PASS/SUCCEEDED |
| 语义 A-B 顺序 / 分数关系 | A_FIRST/A_GT_B |
| S2B-current revision-policy 精确复核 | PASS |
| policy set / trace considered-delivered / delivery manifest | PASS/PASS/PASS |
| trace-delivery-item-receipt 事实 | 1-1-2-1 |
| API 重启禁用 Embedding 回放 / 响应 | PASS/EXACT |
| 回放重复 embedding / 审计事实增量 | 0/0 |
| query-正文-完整向量-秘密泄漏 | 0/0/0/0 |
| Git index / staged / 越界 | MATCH/0/0 |
| Docker 新增 containers-volumes-networks / payload / 遗留进程 | 0-0-0/0/0 |

## 4. 未做

- 未 stage/commit/push。
