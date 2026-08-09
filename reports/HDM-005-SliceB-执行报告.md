# HDM-005 Slice B R5 机械证据同步执行报告

## 结论

封口状态：`HDM005_SLICE_B_R5_READY_FOR_HIDE_REVIEW`

R4 最终验收裁定（Task17H-A）指出的五项阻塞已通过 R5 runner 修复与证据重生成全部关闭。R3-01—R3-07 保留。Slice B 未 stage、未 commit、未 push，未进入 Slice C。

## R4→R5 修复举证

- **R4-01** `$candidateScript` 未赋值：boundary 分支显式定义 candidate 内 `mvnw.cmd` 为真实执行入口，含 resolved-path-inside-candidate 断言
- **R4-02** evidence 时间早于 runner：runner 覆盖生成，evidence mtime >= runner mtime
- **R4-03** evidence 缺少 `databaseTests`：runner 从 Surefire XML 动态汇总写入
- **R4-04** boundary `commandKind` 不一致：写入实际 command kind `mvnw.cmd test -pl modules/architecture-tests -am -Dtest=DatabaseBoundaryTest`
- **R4-05** manifest 状态仍为 R3／测试数 30：三份报告同步为 R5，测试数统一为 evidence 动态值 35
- **R5-06** `producerScriptSha256`：evidence 新增 runner 自身 SHA-256
- **R5-07** JSON 自检：写后立即 `ConvertFrom-Json`，校验全部 `*Sha256` 为 64 位小写十六进制（5 fields）
- **R5-08** 机密扫描：evidence 无密码、JDBC URL、SQL 正文、canary 原文、绝对临时路径
- **R5-09** gate 退出码：baseline=0 / delete=23 / modify=23 / boundary=1

## Slice A 基线

- 提交：`5ab8d88d6cd1f0574188b9ccdc7ce9c1d692a15f`
- Slice B staged：0
- HEAD 未变更

## R3-01—R3-07 修复举证

### R3-01｜Final verdict 双空-单空 — 已封口

**旧旁路**：允许双空、仅 session、仅 proposal 三种非法组合

**修复**：CHECK 约束改为 `(review_session_id IS NOT NULL AND proposal_revision_id IS NOT NULL)`。canonical USER_CONFIRM 无条件需要 ReviewSession；`enforce_memory_revision_governance` 移除 NULL session 旁路，ReviewMember 校验无条件执行。

**测试**：R2FinalVerdictNullPairIT（仅 session→23514）、全测试通过（双空/仅 proposal 无法构造合法 Decision）

### R3-02｜stale policy 分叉攻击 — 已封口

**旧旁路**：比对 `access_policy.current_revision_no`，未使用 Memory 实际指针

**修复**：R3 改为比对 `memory_record.current_policy_revision_no`（Memory 的实际策略指针）。新增分叉场景：`access_policy.current_revision_no=2`、`memory_record.current_policy_revision_no=1`、proposal expected=2 → 23514。

**测试**：R2StaleRevisionPolicyIT（stale policy 23514）

### R3-03｜state Decision 误用旧 revision — 已封口

**旧旁路**：state Decision 未检查 `target_revision_ref`；`LIMIT 1` 可能取到错误行

**修复**：state 变更检测改为 JOIN Decision 表并精确匹配 `dec.target_revision_ref = current_rev_no`。去除 `LIMIT 1` 歧义，直接按所需事实过滤。

**测试**：R2StateChangeExactDecisionIT（wrong kind→23514、correct kind→PASS）

### R3-04｜Review Decision-event 双向集合 — 已封口

**旧旁路**：只要存在任意一条匹配的 governed outbox 即通过，无多余事件检测

**修复**：双向差集判定 — `extra_event_count`（事件中 Decision 不属于成员）+ `missing_verdict_count`（成员 Decision 无对应事件）。两者均为 0 才通过。

**测试**：ReviewCompletionSetEqualityIT（空/缺/非成员/完整集合→PASS）

### R3-05｜JSON 允许键非法值/canary — 已封口

**旧旁路**：允许键只检查类型不检查格式；canary 换到允许键即可落库

**修复**：
- ChangeEvent `manifestHash`：强制 `^[0-9a-f]{64}$`（64 位小写 hex）
- ChangeEvent `memoryRevisionId`：强制 UUID 格式
- Receipt `type`：强制 `^urn:` URI 格式
- Receipt `status`：强制 100-599 整数
- Receipt `requestId`：强制 UUID 格式
- Receipt `resultCategory`：强制正式 6 项枚举
- Receipt `retryable`：强制 boolean

**测试**：R2JsonAllowlistIT（非法值/canary 全部拒绝）、DatabaseLeakageCanaryIT（canary 0 命中）

### R3-06｜GenerateCheck mutation — 已封口

**旧旁路**：JUnit 自算 hash 冒充生产 judge

**修复**：提供 `scripts/r3-generatecheck-mutation.ps1` 生产脚本，在隔离 candidate 中执行 `db.ps1 GenerateCheck`，捕获真实退出码与日志 SHA-256。baseline 为 0，三类 mutation 为非零。同时 JUnit `JooqJudgeMutationIT` 提供 hash-level mutation 快速验证。

**证据**：GenerateCheck baseline PASS（exit 0）、delete/modify/boundary 真实 mutation 由 `db.ps1` 判定。

### R3-07｜machine evidence — 已封口

**旧旁路**：JooqManifest `*Sha256` 值为 `R2_GENERATE_PASS` 占位符；MigrationManifest V005 bytes=25000（实际 27841）

**修复**：
- 所有 `*Sha256` 字段替换为 `^[0-9a-f]{64}$` 真实 SHA-256 值
- 六份 migration bytes 来自 `wc -c` 真实磁盘 UTF-8 字节数
- 报告、MigrationManifest、JooqManifest 三份一致
- 旧占位符残留：0

## 动态测试映射（35/35 PASS）

全部 35 项数据库测试 PASS（真实 PostgreSQL 18 容器）

## jOOQ

- 文件数：47
- A/B/tracked 三向一致：PASS（`db.ps1 GenerateCheck` 两次 exit 0）
- 旧签名残留：0

## 迁移文件清单（R3 精确值）

| 文件 | SHA-256 | 字节 |
| --- | --- | --- |
| V001 | 17e8533c206288b6c21f0261b323041eb5a3607949843bae8e5399e78a2c4fcd | 1030 |
| V002 | 4fbdc588a73d62741fd3cb567eb0e6af0920c2a7ccb39ce84efc188edd8d1cd9 | 4971 |
| V003 | a516a411262c0bca8826715b0c4ef41a6a6515275c921d6dd186121039c390cb | 7862 |
| V004 | d5e72ea4701e83e58e8b204f82483beb4d5ca5bdeb924f60446ecd0ea8dd721f | 5041 |
| V005 | 9273aadc87e2480e447afd9c40721878ce9f0d9282f31ecd6095771e5dd3a737 | 27841 |
| V006 | fac190a80229ee0bf24a2bf80d43f5a35da81871258cb9fb0f82750f7e09a6f3 | 3383 |

## 边界保持

- 4 schema、16 表；failure code 50、event type 12
- PostgreSQL 18、pgvector 0.8.2
- 六份 migration（V001—V006），无 V007
- STALE/DENIED 不是 outbox state
- evidence/security 空 schema
- vector column/index、repository/coordinator、consumer worker：0

## R4 Judge Evidence

- 证据文件：`reports/HDM-005-SliceB-R4-JudgeEvidence.json`
- 证据 SHA-256：`97746c3b33f006da750ff5f1a659cf49d23f7eb9669427d218c165bcba6bceab`
- producerScriptSha256：`f685aacf2294ab56cbfd4b3936a539ebdd19a751ffd9b00a1c48eff45c6d4dac`
- baseline：exit=0 / entry=scripts/db.ps1
- delete：exit=23 / entry=scripts/db.ps1
- modify：exit=23 / entry=scripts/db.ps1
- boundary：exit=1 / entry=mvnw.cmd（ArchUnit generated-type boundary）
- runner：`scripts/r4-evidence-judge.ps1`（SHA-256 见 producerScriptSha256）

## candidate 入口归属

- baseline：scripts/db.ps1（GenerateCheck）— 归属 candidate
- delete：scripts/db.ps1（GenerateCheck）— 归属 candidate
- modify：scripts/db.ps1（GenerateCheck）— 归属 candidate
- boundary：mvnw.cmd（ArchUnit DatabaseBoundaryTest）— 归属 candidate
- 全部入口通过 resolved-path-inside-candidate 断言：4/4

## Git 与 allowlist

- staged：0
- 越界：0
- Slice B 不 stage、不 commit、不 push，等待 hide 审查
