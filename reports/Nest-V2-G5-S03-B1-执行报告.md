# Nest V2 G5-S03-B1 执行报告

状态：`G5_S03_B1_READY_FOR_COMMANDER_REVIEW`
日期：2026-09-23
基线：`main` / `46448380691e03765277cde340d218641b63a96e`；开工时 staged=0。

## 实施

- Formation result 保持 `nest.worker.v1`。每个 WriteSet item 必须声明本批局部 `itemRef`；CREATE、REVISE、SUPERSEDE 的结果 revision 含义和 expected-current 组合写入 Schema 与 README。
- SUPPORT／COUNTER 可引用已提交 `revision:` 或同批 `item:`；单数组内重复引用被 Schema 拒绝，数组顺序没有关系含义。
- Event／Claim／Quote 必须有直接 Anchor。Understanding 可以只有 SUPPORT 关系；直接有 Anchor 时可没有关系。Understanding 仍是可修正解释，没有指令优先级或人格锁定字段。
- README 明确 sourceRef／sourceVersion 只是任务绑定来源的回显，不扩张读权；generation 对比产生结果的 attempt envelope，结算槽按首个通过 Core 硬校验的完整结果占用。
- 增加同批双顺序、混合动作、直接 Anchor 与 15 个 Schema 反例。保留四种非 WriteSet 出口的无半成品形状。

## 验证

| 门 | 结果 |
| --- | --- |
| JDK | Temurin 25.0.4 |
| NetworkNT Draft 2020-12 | PASS：50 个 manifest fixture 全部按真实 Schema 解析；其中 Formation result 29 个 |
| Worker 定向测试 | PASS：13 项，0 失败、0 错误、0 跳过 |
| contracts 全量测试 | PASS：70 项，0 失败、0 错误、0 跳过 |
| manifest 与 fixture 路径双向一致 | PASS：无孤儿、无缺口 |
| WorkerContractTest 定向 Spotless | PASS |
| 全仓跳过测试构建 | PASS：`mvn -q -DskipTests package` |
| `git diff --check`／staged | PASS／0 |
| Ajv 2020 + format 离线交叉核对 | NOT_RUN：本机仅有 Ajv 6.15.0 与 ajv-formats 3.0.1，没有可用 Ajv 2020；未联网安装 |

首次 `WorkerContractTest` 试跑失败 1 项：旧断言把所有空 Anchor 都判为无效，而新的 relation-only Understanding 应允许空 Anchor。将该断言限定为 Event 后重跑通过。首次全模块 `spotless:check` 失败，报告了既有 `ContextPackRequestContractTest.java`、`OperationMatrixTest.java`、`ReadApiContractTest.java`、`SchemaStructureTest.java` 与本单测试文件的格式差异；只格式化本单测试文件后，它的定向格式检查通过。没有修改其余文件。两次失败均保留为失败事实。

## Core 后续责任与未运行门

Schema 只证明表达形状。S03-B2 Core 仍须在解析／事务中 fail-closed 校验：WriteSet 全局 itemRef 唯一；本批目标存在且无自指／环；同一目标不同时 SUPPORT 与 COUNTER；同一旧 Record 不发生两个变化动作；memory／revision 属于当前 world 且 expectedCurrent 仍 current；Anchor 属于 task 绑定来源、版本、冻结范围；合并既有关系后 SUPPORT 最终通向 Anchor。测试中的未知目标、自指、环和 SUPPORT＋COUNTER 重叠明确标为 `RUNTIME_SEMANTIC_VALIDATION_NOT_APPLICABLE_TO_SCHEMA`，不是 Schema 已证明的运行正确性。

Core runtime graph validation、真实 Worker JSON、模型语义质量、真实 Source Adapter、用户体验均为 `NOT_RUN`。`G5-FW-REL-01` 仍开放，须 B1 合同与 B2 运行校验都通过后才能闭合。

## 工作区与停止门

本单实际路径仅在工单允许的 Schema、README、manifest、Formation fixture、`WorkerContractTest.java` 和本报告／Evidence 内。开工前既有的 V1 `LocalV1BubbleCoreDatabaseTest.java` 标记、`docs/planning/**`、`reports/local-v1-read-browser-qa/**` 保留原样。未 stage、commit、push、部署；未开始 S03-B2 或真实 Worker。
