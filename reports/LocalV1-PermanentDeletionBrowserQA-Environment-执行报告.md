# Task33B3｜Local V1 永久删除真实浏览器验收环境 — 执行报告

## 完成回复

```text
环境状态：LOCAL_V1_PERMANENT_DELETION_BROWSER_QA_ENV_READY_FOR_XIAOLIN
V001—V016／真实API／Console Host：PASS/PASS/PASS
正式closeout合成记忆／list-detail-evidence：2/PASS
静态HTML-JS-CSS／loopback-only：PASS/PASS
浏览器秘密暴露／真实资料：0/0
自检永久删除执行：0（保留给小林）
SelfTest清理 containers-volumes-networks／进程／payload：0-0-0/0/0
Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
启动脚本：C:\Users\tangx\Documents\hide-talk\260731-hide协作文件夹\启动-hide-nest-永久删除浏览器验收.ps1
小林真实浏览器QA：WAITING_FOR_XIAOLIN
报告路径：D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionBrowserQA-Environment-执行报告.md
```

## 一、产物

- 启动器：`C:\Users\tangx\Documents\hide-talk\260731-hide协作文件夹\启动-hide-nest-永久删除浏览器验收.ps1`（UTF-8 BOM，非交互 + `-SelfTest` 两种模式）。
- 本报告：`D:\myproject\hide-nest\reports\LocalV1-PermanentDeletionBrowserQA-Environment-执行报告.md`。
- 运行期临时文件仅位于 `%TEMP%\hide-nest-delete-qa-<sessionId>\`，由本次唯一 sessionId 精确拥有。

## 二、启动器完成的真实链

1. 离线构建当前工作树：`mvnw -o -pl apps/api -am package -DskipTests`（JDK 25）产出 `apps/api/target/hide-nest-api-0.0.1-SNAPSHOT-exec.jar`；`npm run build` 产出 `apps/nest-console/dist` 与 `apps/codex-adapter/dist`（含 `console.js`、`closeout-canonicalizer.js`）。
2. `docker run --pull=never` 启动唯一命名、带 session label 的一次性 `pgvector/pgvector@sha256:2ac2…c73c` 容器，仅绑定随机 loopback 端口，`--tmpfs` 数据卷。
3. Flyway `flyway-maven-plugin:12.4.0:migrate` 应用 V001—V017，`public.flyway_schema_history` 精确验证 history=17。（Task33B4 新增 V017 共享原文保留语义；本报告其余事实不变。）
4. 真实 API：`local-v1-synthetic` profile、`127.0.0.1` 随机端口、临时 PayloadStore、随机高熵 bearer/capability——全部经 `SPRING_APPLICATION_JSON` 子进程环境传递，绝不在命令行参数。
5. 经正式 `POST /v1/closeout-submissions` 主链写入两条合成记忆（复用 `apps/codex-adapter/dist/closeout-canonicalizer.js` 的 `buildCloseoutRequest`，未插库、未调用测试 helper）：
   - `QA-永久删除样本`：两条连续证据（hide 在前、小林在后），正文标注“合成浏览器验收数据”。
   - `QA-保留对照样本`：单条证据，用于证明删除不误伤。
6. Task33B4 追加 `POST /v1/deletion-fixtures`（synthetic-only，bearer+capability 门控）生成共享原文夹具：A=`QA-共享原文删除目标`、B=`QA-共享原文保留目标`、C=`QA-无关对照样本`；A/B 共同引用同一 SourceUnit/SourcePayload objectRef+contentHash，C 完全无关。详见 `LocalV1-PermanentDeletionHumanEvidenceSharedSource-执行报告.md`。
6. 真实 Console Host：静态目录指向本轮 `apps/nest-console/dist`，上游指向本轮 API，host 进程持有 bearer/capability，浏览器不可见，`127.0.0.1` 随机端口。
7. 就绪提示仅一行：`HIDE_NEST_DELETE_QA_READY http://127.0.0.1:<port>`。
8. 按 Enter/Ctrl+C（或 `-SelfTest` 自动）后只清理本 session 的容器/进程/临时目录，不触碰其他 Docker/Java/Node 资源。

## 三、自检结果（-SelfTest 实际运行）

- 两条 closeout 均 202，经 Console Host list/detail/evidence 全部 200，`memories=2`。
- 静态 HTML/JS/CSS 从 Console Host 加载 200；浏览器侧请求不带 Authorization/Cookie/X-Action-Capability。
- 未执行最终永久删除（留给小林）。
- 自检后新增 containers/volumes/networks、API/Console 进程、临时 payload 目录均为 0。
- 未把 curl/Invoke-WebRequest 成功写成“真实浏览器 QA PASS”。

## 四、约束核验

- HEAD=`f5f0051`，Git index 与 staged=0 起止一致；仓库生产/测试文件零改动（B3 只新增 hide-talk 启动脚本 + 本报告）。
- 全程离线，无联网、无 Git 写操作。
- 真实资料 0，浏览器秘密暴露 0。

完成即停，未 stage/commit/push。真实浏览器验收由小林本人执行与签字。
