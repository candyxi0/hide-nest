# LocalV1-S2B R1 执行报告

返工状态：LOCAL_V1_S2B_R1_READY_FOR_HIDE_REVIEW
基线 HEAD：e1ecb3982a15ae97907c87297417a9a19737ff0c

执行范围：仅 S2B 针对性测试；未执行全仓 Maven；未执行 Git stage/commit/push/checkout/reset/clean。
测试命令：.\mvnw.cmd -o -pl modules/database-adapter -am -Dtest=LocalV1S2BMemoryQueryTest -Dsurefire.failIfNoSpecifiedTests=false test

S2B 针对性测试：10/0/0/0
Surefire XML：modules/database-adapter/target/surefire-reports/TEST-io.github.candyxi0.hidenest.database.LocalV1S2BMemoryQueryTest.xml
XML 动态计数：tests=10 failures=0 errors=0 skipped=0
报告与 XML 计数：MATCH

R1-01 current pointer mismatch／owner mismatch／revision missing：
OWNER_BINDING_INVALID／OWNER_BINDING_INVALID／CURRENT_POINTER_INVALID

R1-02 跨Source／actor缺失／重复anchor：
SOURCE_UNIT_INVALID／ACTOR_INVALID／EVIDENCE_RELATION_INVALID

R1-03 100条／101条：
PASS／EVIDENCE_LIMIT_EXCEEDED

R1-03 4MiB／4MiB+1：
PASS／PAYLOAD_INVALID

R1-04 正式 S1/S2A 记忆只读快照：
memory/evidence/runtime/security 四 schema row-count：MATCH
payload 相对路径/size/SHA256 集合：MATCH
证据正文泄露到日志/报告：未写入

生产代码修改：1
modules/application/src/main/java/io/github/candyxi0/hidenest/application/coordinator/LocalV1S2BQueryCoordinator.java：
将 SourceUnit.actorId == null 从 SOURCE_UNIT_INVALID 独立归类为 ACTOR_INVALID，以满足 actor 缺失稳定分类。

全仓 Maven：NOT_RUN（沿用上一轮 311 PASS，不冒充本轮执行）
S1／PayloadStore：NOT_RUN（上一轮记录为 26/0/0/0／13/0/0/0，本轮未重跑）

V001—V010／generated／契约／前端：MATCH／MATCH／MATCH／MATCH
冻结范围变更计数：
modules/database-adapter/src/main/resources/db/migration=0
modules/database-adapter/src/main/java/io/github/candyxi0/hidenest/database/generated=0
docs/contracts=0
frontend=0
modules/frontend=0

真实 Git index：fb275c85948bc70848d3b77e1ce93a56e31642c1／fb275c85948bc70848d3b77e1ce93a56e31642c1
工作区：tracked=2、untracked=13、staged=0、越界=0
git diff --check：PASS（仅 LF/CRLF 工作副本提示）
联网／Git写操作：否／否

报告路径：D:\myproject\hide-nest\reports\LocalV1-S2B-执行报告.md
