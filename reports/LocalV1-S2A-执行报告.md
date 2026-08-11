# LocalV1-S2A 执行报告

## 执行状态

**验收状态：LOCAL_V1_S2A_READY_FOR_LOCAL_COMMIT**

基线 HEAD：`b58613a7d4e37be43fdc84f12f272afd64cdbdff`

## 测试结果摘要

| 项目 | 结果 |
|------|------|
| PayloadStore put-head-get-delete | PASS |
| 同值幂等／异值冲突／并发 | PASS / PASS / PASS |
| 路径穿越／hash篡改／超限 | REJECTED / REJECTED / REJECTED |
| S1 SourceUnit／SourcePayload／文件对象 | 2/2/2 |
| 完整证据正文／说话者／顺序 | PASS / PASS / PASS |
| prepare replay／DB失败文件补偿／后续失败文件补偿 | PASS / PASS / PASS |
| 未选闲聊 canary（DB／文件／manifest／日志报告） | 0/0/0/0 |

## 测试计数

| 测试套件 | tests | failures | errors | skipped |
|----------|-------|----------|--------|---------|
| PayloadStore契约测试 (LocalPayloadStoreContractTest) | 13 | 0 | 0 | 0 |
| S1测试 (LocalV1S1WindowCloseTest) | 26 | 0 | 0 | 0 |

（数据来源：target/surefire-reports XML）

## 全仓 Maven offline clean verify

PASS — 所有 12 个模块 BUILD SUCCESS；Surefire XML 汇总 23 files / 305 tests / 0 failures / 0 errors / 0 skipped

## 文件变更摘要

### 新建文件（untracked=9）

- `modules/evidence/.../domain/PayloadHeadResult.java`
- `modules/evidence/.../domain/PayloadPutResult.java`
- `modules/evidence/.../domain/PayloadStoreException.java`
- `modules/evidence/.../port/PayloadStore.java`
- `modules/payload-adapter/pom.xml`
- `modules/payload-adapter/.../payload/PayloadAdapterModule.java`
- `modules/payload-adapter/.../payload/LocalPayloadStore.java`
- `modules/payload-adapter/.../payload/LocalPayloadStoreContractTest.java`
- `reports/LocalV1-S2A-执行报告.md`

### 修改文件（modified=8）

- `pom.xml` — 新增 modules/payload-adapter 模块登记
- `modules/evidence/.../port/PayloadStore.java` — 新建 port 接口（计入 untracked）
- `modules/application/.../LocalV1S1PrepareRequest.java` — EvidenceMessage 增加 bodyText 字段
- `modules/application/.../LocalV1S1WindowCloseCoordinator.java` — 注入 PayloadStore，prepare 写入 payload + SourcePayload 元数据，失败补偿仅删除本次新建文件
- `modules/payload-adapter/pom.xml` — 新模块 POM（计入 untracked）
- `modules/database-adapter/pom.xml` — test scope 依赖 hide-nest-payload-adapter
- `modules/database-adapter/.../LocalV1S1WindowCloseTest.java` — EvidenceMessage 增加 bodyText，新增 S2A 测试 7 项，canary 表补扫 evidence.source_payload
- `modules/architecture-tests/pom.xml` — test scope 依赖 hide-nest-payload-adapter
- `modules/architecture-tests/.../ArchitectureTest.java` — 新增 payload marker 检查
- `modules/architecture-tests/.../DatabaseBoundaryTest.java` — 新增 payloadAdapterBoundaryRule

（实际 untracked=9, modified=8, staged=0, 越界=0）

## 合规检查

| 检查项 | 结果 |
|--------|------|
| V001—V010 migration 文件 | MATCH（未修改） |
| jOOQ generated 代码 | MATCH（未修改） |
| 正式 OpenAPI／事件 schema | MATCH（未修改） |
| 前端设计包 | MATCH（未修改） |
| 联网 | 否 |
| Git 写操作（stage/commit/push/reset/restore/checkout/clean） | 否 |
| 正文是否进入 DB/manifest/日志 | 否（SourcePayload 仅元数据，无 bodyText 字段） |
| 新增正式 failure code | 否（PayloadStoreException 为供应商中立错误码，非 CanonicalFailureCode） |
| application 是否依赖具体 adapter | 否（仅依赖 PayloadStore port，不引用 LocalPayloadStore/Path/Files） |
| OSS/加密密钥/后台清扫器 | 未实现 |

## hide 验收补丁

- PayloadPutResult 增加 `created` 标记：同 ID 同内容重放返回 `created=false`，补偿不会误删既有 payload。
- LocalV1S1 prepare 的补偿范围扩大到 SourcePayload 写入之后的 anchor/proposal/review/receipt 全段；数据库回滚时仅删除本次新建的文件。
- 新增 `s2aLatePrepareFailureCompensatesPayloadFiles`，覆盖 SourcePayload 已插入后、后续数据库步骤失败时的文件补偿。
- LocalPayloadStore 的符号链接检查改为逐级检查已存在路径组件，避免旧实现只检查相对 path 片段导致实际目录链接漏判。

## 已知限制（记录为合成 V1 范围外）

- 进程在文件写成后、数据库提交前崩溃可能留下无引用 orphan payload 文件。此为合成 V1 已知限制，不暴露给读取接口，不实现清扫平台。
- objectVersionRef 在本地 V1 保持 null。
- 不实现历史版本读取。

## 报告真实性声明

- 测试数从 target/surefire-reports XML 读取，非方法名手填；本轮全仓为 305/0/0/0。
- Git tracked/untracked/staged 从 `git status` 现算。
- 未将"没有读取"写成"0 泄漏"；canary 扫描证实未选闲聊未进入 request、DB、文件或 manifest。
