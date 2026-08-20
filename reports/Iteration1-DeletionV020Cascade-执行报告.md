# Task41B｜Nest 第一次迭代：永久删除 V020 级联与 NO_RUN 恢复 · 执行报告

实施状态：`ITERATION1_DELETION_V020_CASCADE_READY_FOR_HIDE_REVIEW`

## 1. 结论

V020 新增的不可变 `memory.candidate_evidence_mapping` 通过
`candidate_evidence_mapping_anchor_fk` 引用 `evidence.source_anchor`，而 V017 的正式擦除函数未处理该映射，导致已确认删除在物理删除 anchor 时以 SQLSTATE `23503` 回滚。

本轮只新增 `V021__candidate_evidence_mapping_erasure.sql`，以当前事务、CONFIRMED closure、confirmed Decision、精确 fence、同事务 deletion_run 和
`SOURCE_ANCHOR + DELETE_CANDIDATE` member 共同授权 mapping 删除；随后在 V017 完整 carry-forward 函数中、删除 anchor 前精确删除对应 mapping。CandidateSet root/member/Decision/ProposalRevision 均保留。

现有 `LocalV1DeletionWriteCoordinator.confirm(...)` 已能在同一确认请求和同一幂等键下恢复
`CONFIRMED + fence + NO_RUN`，因此没有修改 application/adapter 生产代码，也没有新增恢复旁路。

## 2. 冻结基线

- 仓库：`D:\myproject\hide-nest`
- HEAD：`1b2e753ebee1b222311a80cfc0fa8ea5ecb19dba`
- 初始 staged：0
- Task41A 四文件初始 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `apps/nest-console/src/App.tsx` | `63dc967429e4bc7138da84ade3e4d0ae5e5e9aa21604d7be655b8214e848d67c` |
| `apps/nest-console/src/app.css` | `bcd78476e4a28c289da77a3f1e3bf25794523443d8126d6fe318c0f85fea19b3` |
| `apps/nest-console/src/test/App.test.tsx` | `8509c8d739d74a3dca1ab80597ba38a1a008af0d7e4f466208ef63b1bada245b` |
| `reports/Iteration1-DeleteFeedback-执行报告.md` | `704ea08ca5878a5a581f2ba1670611f4cb79dfbc5298c000fa2f87c12847fd1b` |

终验四项逐字 MATCH；既有 `reports/local-v1-read-browser-qa/.playwright-artifacts/` 保持在原范围内。

## 3. V021 实现

### 3.1 erasure-aware 不可变门

新函数 `memory.enforce_candidate_evidence_mapping_immutable_or_erasure()`：

- UPDATE 永远以 SQLSTATE `23514`、`HDM021_CANDIDATE_EVIDENCE_MAPPING_ERASURE_DENIED` 拒绝；
- 普通 DELETE、无 marker、伪 marker、wrong closure/anchor、RETAIN_SHARED 均以同一稳定内部诊断拒绝；
- 合法 DELETE 必须同时满足：
  - marker 与 deletion_run 均由当前事务写入；
  - marker 时间与 run 的 started/database-erased 时间一致；
  - closure 为 CONFIRMED 且 Decision、fence、run 三者绑定同一 confirmed Decision；
  - member 精确为当前 OLD.anchor_id 的 `SOURCE_ANCHOR + DELETE_CANDIDATE`；
- PUBLIC 对新函数零 EXECUTE；api/worker 均无 mapping 表 DELETE；
- 正式擦除函数仍只授权 `hide_nest_worker`，PUBLIC 零 EXECUTE。

未使用 ON DELETE CASCADE、DROP FK、DISABLE TRIGGER、宽泛 DELETE grant 或冻结 failure-code 扩展。

### 3.2 擦除函数

`runtime.execute_confirmed_deletion_database_phase(...)` 完整 carry-forward V017；自动对比去掉新增 mapping 段后逐字 MATCH。唯一业务窄改位于既有 marker 建立和全部闭包复核之后、source_anchor 删除之前：

1. 精确删除 closure 中 `DELETE_CANDIDATE SOURCE_ANCHOR` 对应的全部
   `candidate_evidence_mapping`；
2. 沿用 relation → anchor_unit → payload → anchor → capture_scope_unit → unit → revision → memory_record 顺序；
3. 失败仍由单语句事务整体回滚。

V001—V020 未修改；表总数不变；jOOQ A/B/tracked SHA-256 tree MATCH，generated 修改 0。

## 4. V021 Testcontainers 契约

新增
`LocalV1V021CandidateEvidenceMappingErasureTest`，全部使用一次性 PostgreSQL 18.4 Testcontainers：

1. 空库／V020→V021／重复：`21 / 1 / 0`，升级前后表数 MATCH；
2. CandidateSet CREATE 独占 anchor：mapping/anchor/memory/revision/vector/unit/payload 清除，CandidateSet root/member 保留；
3. 两候选共享 anchor：删第一份后 closure 为 RETAIN_SHARED，mapping/anchor 保留；删最后引用后 anchor 与全部 mapping 清除；
4. 无 marker DELETE、mapping UPDATE、伪 marker、wrong closure/anchor、RETAIN_SHARED marker：全部 `23514/HDM021_CANDIDATE_EVIDENCE_MAPPING_ERASURE_DENIED`；
5. mapping、anchor、revision、memory 四个注入点：mapping/anchor/memory/revision/vector/run/task 全部 MATCH；
6. 构造真实 `CONFIRMED + fence + deletion_run=0`，用正式
   `LocalV1DeletionWriteCoordinator.confirm(...)` 同请求同键重放：
   - 创建恰好 1 个 deletion_run；
   - 文件阶段完成，最终 COMPLETED；
   - Decision/fence/closure/member 不重复；
   - 异值重放拒绝且事实不扩大；
   - COMPLETED 后再重放 EXACT，事实增量 0；
7. 在同一 Testcontainers schema 上离线运行两次 jOOQ generation，A/B/tracked 三棵树逐文件 SHA-256 MATCH。

未连接或修改家庭小主机数据库，未执行真实 NO_RUN 恢复。

## 5. 验证结果

### Targeted

- V021 契约：6/6 PASS（其中 jOOQ A/B/tracked 单独 targeted 1/1 PASS）
- DatabaseSliceB：95/95 PASS
- CandidateSet projection：26/26 PASS
- CandidateSet core：33/33 PASS
- S3C1A：18/18 PASS
- S3C2：18/18 PASS
- 删除 HTTP 正式链：12/12 PASS
- database targeted 组合：195/195 PASS（jOOQ gate 另计 1）
- Node/OpenAPI 专门门：NOT_RUN_UNCHANGED；生产契约与前端未由 Task41B 修改

### 唯一一次 JDK25 offline 全量门

命令：`mvnw.cmd -o clean verify`

结果：`BUILD SUCCESS`，总耗时 5:13。

| 模块 | 结果 |
|---|---|
| payload-adapter | 13，0 failure / 0 error |
| application | 50，0 failure / 0 error |
| embedding-adapter | 13，0 failure / 0 error |
| database-adapter | 388，0 failure / 0 error / 1 原有可选 smoke skipped |
| contracts | 46，0 failure / 0 error |
| api | 71，0 failure / 0 error |
| worker | 5，0 failure / 0 error |
| architecture-tests | 29，0 failure / 0 error |

全量中的预期失败分支会打印 Spring/Flyway 错误日志，但其所属测试均 PASS；最终 Reactor 13/13 SUCCESS。

## 6. 终验边界

- `git diff --check`：PASS；
- staged：0；`git diff --cached` 为空；
- Task41B 改动仅限 V021、database/api 测试机械计数与 V021 契约、本报告和 Evidence；
- V001—V020：MATCH；
- jOOQ generated：MATCH；
- OpenAPI/TS generated/MCP：MATCH；
- Task41A 四文件：MATCH；
- 正文/path/token/capability/secret 运行时泄漏：0/0/0/0/0（删除 HTTP、contracts leakage、architecture body-text scan 全绿）；
- Testcontainers 退出后残留 containers/volumes/networks：0/0/0；
- surefire/Maven/jOOQ 遗留进程：0；
- 家庭小主机真实数据修改：0；
- 外部联网：否（Maven 强制 `-o`，容器镜像本地命中）；
- Git stage/commit/push/config 写操作：否。

## 7. 完成回执

```text
实施状态：ITERATION1_DELETION_V020_CASCADE_READY_FOR_HIDE_REVIEW
根因SQLSTATE／约束：23503/candidate_evidence_mapping_anchor_fk
V021空库-升级-重复：21/1/0
独占anchor mapping擦除／共享mapping保留：PASS/PASS
无marker-wrong closure-RETAIN_SHARED-UPDATE攻击：全部REJECTED
mapping-anchor-revision-memory注入回滚：PASS
CONFIRMED-fence-NO_RUN正式重放／最终run：PASS/COMPLETED
Decision-fence-closure重复事实：0-0-0
CandidateSet core-projection／删除回归：PASS/PASS
targeted／Maven offline／架构门：PASS/PASS/PASS
V001—V020／jOOQ generated／OpenAPI／React-MCP：MATCH/MATCH/MATCH/MATCH
Task41A四文件hash：MATCH
正文-path-token-capability-secret泄漏：0-0-0-0-0
真实Git index／staged／越界：MATCH/0/0
Docker新增containers-volumes-networks／遗留进程：0-0-0/0
家庭小主机真实数据修改／联网／Git写操作：0/否/否
报告路径：D:\myproject\hide-nest\reports\Iteration1-DeletionV020Cascade-执行报告.md
```
