# nest console｜API 与读模型补充 v0.1

`状态：IMPLEMENTATION_READY_DRAFT`

`形成时间：2026-08-06`

## 0. 边界

本文只补齐既有 HDM-023 页面无法从当前端点取得的数据。它不新增状态机，不允许 UI 直接写数据库，也不改变关窗、身份、删除和恢复的权力边界。

所有端点沿用：`/v1`、opaque cursor、`X-Request-Id`、Bearer opaque token、必要时 `X-Action-Capability`、`application/problem+json`、`additionalProperties:false` 和无正文错误。

## 1. 复用现有端点

- `GET /v1/runs/{runId}`；
- `GET /v1/review-sessions`；
- `GET /v1/review-sessions/{id}`；
- `POST /v1/review-sessions/{id}/cancel`；
- 归档、恢复 ACTIVE、隔离、隔离恢复预览／确认；
- 删除预览／确认、DeletionRun；
- exports 与 restore validations（只显示授权结果，不在 V1 console 中激活生产恢复）。

## 2. 必须新增的读端点

### 2.1 记忆列表

```http
GET /v1/memories?query=&state=&memoryType=&perspectiveActorId=&sourceAvailability=&cursor=&limit=
```

返回授权后的 current revision 摘要：

```json
{
  "requestId": "uuidv7",
  "resultCategory": "SUCCEEDED",
  "items": [{
    "memoryId": "uuid",
    "currentRevisionId": "uuid",
    "revisionNo": 3,
    "state": "ACTIVE",
    "isolated": false,
    "memoryType": "INTERPRETATION",
    "perspective": {"actorId":"uuid","displayLabel":"共同"},
    "title": "合成标题",
    "summary": "合成的规范正文摘要",
    "sourceAvailability": "AVAILABLE",
    "uncertaintyCode": null,
    "updatedAt": "2026-08-06T02:00:00Z"
  }],
  "nextCursor": null
}
```

要求：

- `title/summary` 必须来自当前授权 revision，不建立第二份规范正文；
- query 使用受权限过滤后的 FTS，不通过向量推断无权对象；
- 无权对象不出现在 items、total、facet 或 timing 差异中；
- V1 不返回全库精确 total，避免昂贵计数和存在性侧信道；
- `limit` 默认 30、最大 100。

### 2.2 记忆详情

```http
GET /v1/memories/{memoryId}
```

返回：current MemoryRevision、ACTIVE／ARCHIVED、独立 `isolated` 派生值、AccessPolicy 可读摘要、relation 摘要、source availability、最近 ChangeEvent 摘要。不得返回 policy grant 内部结构、Token、object_ref、provider metadata 或已删除正文。

### 2.3 变化轨迹

```http
GET /v1/memories/{memoryId}/trajectory?cursor=&limit=
```

返回当前调用者可见的 revision／relation 时间线。旧 revision 正文只有在当前 policy 明确允许 HISTORY_VIEW 时返回；否则只返回 revisionNo、relationType、occurredAt 和不可见原因。

### 2.4 绑定证据

```http
GET /v1/memories/{memoryId}/evidence?revisionId={currentRevisionId}
```

只返回该 revision 已有 `EVIDENCED_BY` 关系指向的 SourceAnchor：完整 SourceUnit 边界、actor label、occurredAt、availability 和被授权的最小充分证据正文。不得接受任意 object_ref 或自由全文查询。

安全要求：

- 小林治理授权；
- `Cache-Control:no-store`；
- Adapter 与浏览器不得持久化；
- SourcePayload 不可用时返回元数据＋明确 availability，不伪造空字符串；
- 无权时整个对象返回 DENIED，不按单句泄露；
- 响应不得包含 OSS URL、bucket、object key 或 content hash。

### 2.5 运行列表

```http
GET /v1/runs?kind=&state=&cursor=&limit=
```

返回 runId、kind、可见 phase／state、计数、failureCode、retryable、startedAt、terminalAt。只用于定位；完整详情继续复用 `GET /runs/{id}`。

### 2.6 无正文系统状态

```http
GET /v1/system/status
```

返回 API、Worker、FTS、vector、PayloadStore 的 `HEALTHY/DEGRADED/UNAVAILABLE/DISABLED`，最近成功时间和 `productionAcceptsRealMaterial`。不得返回 hostname、IP、数据库、bucket、provider secret 或内部异常。

本地 Adapter 状态由 loopback `/local/status` 提供，不代理进远端规范 API。

## 3. 本地 Adapter 端点

这些端点只存在于 loopback console host，不进入公网 OpenAPI：

| 端点 | 用途 |
|---|---|
| `GET /local/status` | Adapter 版本、远端可达性、最近同步；无凭据 |
| `POST /local/codex-reference` | 生成“回到对话修正”的安全引用；不创建 Decision |
| `POST /local/console/close` | 清空内存 session 并停止 console host |

Adapter 对 `/v1/*` 做透明代理时必须：覆盖 Authorization；拒绝浏览器自带 capability；重写 Host；限制正文大小；不记录请求／响应正文；保留 requestId；阻止 open proxy。

## 4. 写动作 DTO 统一要求

所有治理动作至少携带：

```json
{
  "targetId": "uuid",
  "expectedRevision": 3,
  "expectedPolicyRevision": 7,
  "requestManifestHash": "sha256"
}
```

页面不从 DOM 文案计算 hash。生成客户端提供明确 DTO；Adapter 不修改业务字段。

删除确认另携带服务端 previewId、previewRevision、manifestHash。任何不一致返回 `DELETION_PREVIEW_STALE` 或 `DELETION_CLOSURE_MISMATCH`。

## 5. 前端结果映射

| resultCategory | UI 行为 |
|---|---|
| SUCCEEDED | 更新到响应给出的 revision／phase |
| NO_RELEVANT_RESULT | 只用于真实搜索无匹配；不是故障 |
| DENIED | 清除敏感 query cache；不显示目标存在性 |
| STALE | 禁用旧动作，读取当前 revision |
| FAILED | 显示 failureCode／requestId；保持服务端更严格状态 |
| PARTIALLY_DEGRADED | 显示受影响路线；只有明确安全结果仍可展示 |

前端不得根据 HTTP 200 或异常字符串自行推断业务成功。

## 6. 缓存与浏览器头

console host 对 HTML、API 和字体之外的敏感响应统一设置：

```text
Cache-Control: no-store
Pragma: no-cache
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
Content-Security-Policy: default-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'
```

静态 hash assets 可 `public,max-age=31536000,immutable`，但 HTML 本身不得长期缓存，以便 launch nonce 与资源清单更新。

## 7. 契约测试

- denied memory 不出现在 list、facet、trajectory、evidence；
- list title／summary 与 current revision 一致；
- state 只允许 ACTIVE／ARCHIVED，isolated 是独立字段；
- evidence 只包含已绑定完整 SourceUnit；
- source 不可用与空正文可区分；
- stale action 不执行；
- deletion preview 变化必须重新确认；
- run phase 不倒退；
- unknown enum／field 拒绝；
- Token、object_ref、bucket、SQL、stack、正文 canary 在 Problem／日志／trace 中零命中；
- 生成 TS 客户端与 Java DTO 的 schema hash 一致。

