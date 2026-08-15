# LocalV1 永久删除人类证据语义与共享原文闭环 — 执行报告

`状态：LOCAL_V1_PERMANENT_DELETION_HUMAN_EVIDENCE_SHARED_SOURCE_R1_READY_FOR_XIAOLIN_QA`

`冻结基线：HEAD=f5f0051cad6317c1505f69dbf3dd34c1530d258f`

`执行时间：2026-08-14`

## 〇、R1 返工（V017 历史闭包升级 + 共享夹具清洁封口）

Task33B4-R1 关闭两个机械缺口：

1. **R1-01**：V017 在收紧 CHECK 前，对 V016 既有旧闭包成员逐行机械转换（`AFFECTED_MEMORY`→`SHARED_REFERENCE`、`AFFECTED_PENDING_CHOICE`→`RETAIN_SHARED`），保持 closure_id／target_id／target_revision_ref／ordinal 等身份字段不变；旧 `CONFIRMED` 语义闭包 fail-closed 拒绝。不可变触发器在 V017 事务内最小 drop/恢复，不留旁路。
2. **R1-02**：`LocalV1SharedEvidenceFixtureCoordinator` 重指向 B 的 EVIDENCED_BY 后，精确清理 B 原独占 anchor／unit／payload 元数据及 payload 文件（每个记忆独立 actor，杜绝跨记忆 actor_ref 主键冲突）。

## 一、返工目标与结论

Task33B4 正式返工：把永久删除从"内部对象计数"改成小林能直接理解的完整证据与共享原文后果，并让"两个规范记忆共同引用同一条原文"的真实 PostgreSQL＋PayloadStore 删除闭环通过。已在 Task33B1/R1/B2/R1/B3 未提交工作树上返工，未 stage、未 commit、未 push。

## 二、实现内容

### 2.1 数据库（V017__shared_evidence_retain.sql，唯一前向迁移）
- 处置/种类 CHECK：废弃 `AFFECTED_MEMORY`／`AFFECTED_PENDING_CHOICE`，新增 `SHARED_REFERENCE`／`RETAIN_SHARED`。
- `validate_deletion_fence_insert`／`validate_deletion_closure_confirmation_fences`：只对 `DELETE_REQUESTED`／`DELETE_CANDIDATE` 建 fence。
- `execute_confirmed_deletion_database_phase`：移除 choice-required fail-closed；fence 双向等式与共享引用拒绝只作用于 `DELETE_CANDIDATE` 独占对象；`RETAIN_SHARED` 对象保留、不进物理清除。未新增 failure code，未放宽权限，未引入 CASCADE。

### 2.2 领域／应用
- `DeletionPreviewGraph` 增加 `sharedAnchorIds/sharedUnitIds/sharedPayloadIds`、有序 `EvidenceUnit`（含 objectRef/actor/time/hash）、`SharedMemory`（memoryId/revisionNo/title）。
- `JooqDeletionPreviewAdapter`：精确计算独占 vs 共享；读取证据元数据与共享记忆标题。
- `CanonicalClosureComputer`：独占→`DELETE_CANDIDATE`，共享→`RETAIN_SHARED`，共享引用记忆→`SHARED_REFERENCE`/`RETAIN_SHARED`。
- `LocalV1S3ADeletionPreviewCoordinator`：注入 `PayloadStore`，从已保存 PayloadStore 读证据正文（UTF-8 严格解码），组装 `evidence` + `sharedMemories` 闭合投影。
- `LocalV1S3B2BDeletionConfirmCoordinator`：只 fence `DELETE_REQUESTED/DELETE_CANDIDATE`，跳过 `RETAIN_SHARED`。

### 2.3 契约与前端
- `DeletionPreviewResponse` 新增 `evidence`（`DeletionEvidenceItem`）与 `sharedMemories`（`DeletionSharedMemory`）；30 operation 不变。同步 inventory、baseline、Java/TS 生成物。
- React `DeleteDrawer`：渲染"完整证据：1 段，共 N 条消息"+ 说话者/时间/正文；共享时显示"这段原文也被 N 条其他记忆使用：《标题》…删除后仍会保留"，非共享时显示"删除后：这条消息的原文副本也会清除"。技术名词（SOURCE_ANCHOR/UNIT/PAYLOAD/UUID/hash/objectRef）用户 DOM 0 命中。

### 2.4 合成夹具（synthetic-only）
- 新增 `LocalV1SharedEvidenceFixtureCoordinator` + `POST /v1/deletion-fixtures`（`local-v1-synthetic` profile，bearer+capability 门控，不进 OpenAPI、生产不注册）。
- 真实 S1 治理 + 真实 PostgreSQL + 真实 PayloadStore 生成 A=`QA-共享原文删除目标`、B=`QA-共享原文保留目标`、C=`QA-无关对照样本`；B 的 EVIDENCED_BY 重指向 A 的第一条 anchor，A/B 共同引用同一 SourceUnit 与同一 SourcePayload objectRef/contentHash，C 完全无关。
- 边界：`SHARED_FIXTURE_NOT_PROOF_OF_MULTI_CANDIDATE_CLOSEOUT`。

## 三、完成回复

```text
返工状态：LOCAL_V1_PERMANENT_DELETION_HUMAN_EVIDENCE_SHARED_SOURCE_R1_READY_FOR_XIAOLIN_QA
V016含旧闭包升级／重复：PASS／0
旧种类-处置转换／身份字段保持：PASS／PASS
旧确认fail-closed／新preview可用：PASS／PASS
旧CONFIRMED异常保护：PASS
共享夹具孤儿元数据／payload文件：0／0
删除A-B保留payload／再删B最终清除／C保持：PASS／PASS／PASS
synthetic外fixture bean：0
targeted tests：39/0/0/0
V001—V016／prototype／contracts／generated：MATCH/MATCH/MATCH/MATCH
正文-path-token-capability-secret泄漏：0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
小林真实浏览器QA：WAITING_FOR_XIAOLIN
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionHumanEvidenceSharedSource-执行报告.md
```

## 四、执行者不代签

小林真实浏览器 QA（§7.3 八步）尚未执行，状态为 `WAITING_FOR_XIAOLIN`；执行者不代签浏览器 QA PASS。
