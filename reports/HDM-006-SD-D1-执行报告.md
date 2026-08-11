# HDM-006 Slice D1 数据库机械核心 执行报告（R2 封口）

`状态：HDM006_SLICE_D1_R2_READY_FOR_D2_ORDER` `基线 HEAD：2425883` `时间：2026-08-11` `返工依据：Task22B-R2`

## 1. 执行摘要

完成 HDM-006 Slice D1 R2 封口：关闭全部 5 项证据真实性缺口。真实事务派生 DSL 并发测试、BEFORE UPDATE trigger 故障注入证明回滚、真实角色权限验证、LeaseLost 状态 allowlist、完整 DTO 字段验证。全部 221 测试通过（160 database + 37 contracts + 24 architecture）。jOOQ generated tree MATCH。V001-V009 MATCH。

## 2. R2 返工逐项

### R2-01 事务派生 DSL 并发

- Claim 与 Purge 并发测试：使用 `tr.dsl()` 创建 adapter，非外层 DSL
- `pg_backend_pid()` 在事务回调内读取，PID 与 SQL 执行连接一致
- Worker 1 保持事务未提交（latch 等待 Worker 2 完成），第三方连接验证未提交变更不可见
- 两线程使用 `ExecutorService`，`Future.get()` 传播异常，`finally` shutdown/awaitTermination

### R2-02 两个 UPDATE 分支均精确为 1

- `inserted==0`（marker 已存在）分支：UPDATE 后检查 `convRows != 1` → `IllegalStateException`
- `inserted==1`（首次插入）分支：UPDATE 后检查 `rows != 1` → `IllegalStateException`
- 两个分支均不回退到伪成功/伪 ALREADY_SETTLED

### R2-03 真实 trigger 故障注入

- 测试专属 `BEFORE UPDATE` trigger，`RETURN NULL` 使 `affectedRows=0`
- 首次 INSERT 分支：INSERT ConsumerEffect 成功 → UPDATE=0 → `IllegalStateException` → 事务回滚 → ConsumerEffect=0 + 事件仍 LEASED + completed_at=null
- marker 已存在分支：INSERT 被 ON CONFLICT 跳过 → UPDATE=0 → `IllegalStateException`（非 ALREADY_SETTLED）→ marker 保持原状 + 事件 LEASED
- `finally` 中 DROP TRIGGER + DROP FUNCTION；测试失败也不残留

### R2-04 真实角色权限

- `GRANT hide_nest_worker TO migrator` + `SET ROLE hide_nest_worker` → 原始 JDBC DELETE 成功 → 正式 adapter `purgeExpiredWorkArtifacts` 成功
- `SET ROLE hide_nest_api` → 正式 adapter purge → 权限拒绝
- `finally` 中 REVOKE 清理

### R2-05 删除占位断言并收紧

- 删除 `assertEquals("w1", getState(dueId) != null ? "w1" : null)` 占位
- 改为精确查询 `lease_owner` 与 `lease_until`
- `LeaseLost.observedState` allowlist：UNKNOWN / READY / LEASED / SUCCEEDED / FINAL_FAILED
- Failure 与 Terminal 两种 LeaseLost 均拒绝非法字符串
- DTO 测试补齐 14 字段逐字段断言

## 3. 测试结果

| 测试类 | 结果 |
|--------|------|
| DatabaseSliceD1OutboxMechanicsTest | 22/22/0/0/0 |
| DatabaseSliceBContractTest | 95/95/0/0/0 |
| SliceCCoordinatorTest | 15/15/0/0/0 |
| C2A/C2B | 14+14/0/0/0 |
| Contracts | 37/37/0/0/0 |
| Architecture | 24/24/0/0/0 |
| **合计** | **221/221/0/0/0** |

## 4. 验证

| 检查项 | 结果 |
|--------|------|
| V001—V009 | MATCH |
| jOOQ generated tree | MATCH |
| trigger/function 残留 | 0 |
| 线程/连接/进程/Docker 残留 | 0 |
| Maven/Java | 3.9.16/25.0.4 |
| git diff --check | PASS（CRLF 警告） |
| 正文/secret 泄漏 | 0/0 |

## 5. Git 状态

- staged：0
- tracked modified：6
- untracked new：11
- 越界：0
- 联网/Git 写：否/否

---

```text
封口状态：HDM006_SLICE_D1_R2_READY_FOR_D2_ORDER
D1 数据库测试：22/22/0/0/0
Claim／Purge 派生DSL事务绑定：PASS/PASS
不同PID／未提交不可见／集合不相交：PASS/PASS/PASS
首次marker分支 UPDATE0 回滚：PASS
既有marker分支 UPDATE0 拒绝且不伪成功：PASS
Worker／API 正式adapter DELETE：PASS/REJECTED
DTO 14字段／LeaseLost状态门：PASS/PASS
V010 空库／V009升级／重复：10/1/0
Slice B／Slice C／C2A／C2B：95/95／15/15／14/14／14/14
架构正式／同规则非法 fixture：PASS/PASS
Maven／Java：3.9.16／25.0.4
V001—V009／generated：MATCH/MATCH
测试trigger-function／线程连接进程／Docker新增残留：0/0/0
正文／secret 泄漏：0/0
真实 Git index：未变
工作区：tracked=6、untracked files=11、staged=0、越界=0
联网／Git 写操作：否／否
报告路径：D:\myproject\hide-nest\reports\HDM-006-SD-D1-执行报告.md
```
