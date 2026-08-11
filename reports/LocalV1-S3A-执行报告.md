# LocalV1-S3A R1 幂等快照与缺失判定器返工报告

## 结论

- 返工状态：`LOCAL_V1S3A_R1_READY_FOR_LOCAL_COMMIT`
- 基线 HEAD：`4ebec3a6f917c12f25bd59ded94c97ef645101b7`
- 保留 Task25A 未提交候选；未 stage、commit、push、reset、restore、checkout、Git clean。
- 本轮未运行全仓 Maven；沿用历史证据：318 tests，0 failures，0 errors，0 skipped。

## 最终验收矩阵

| 项目 | 结果 |
|---|---|
| 同图精确重放／变图旧 key／变图新 key | `PASS / PREVIEW_STALE / PASS` |
| 历史 revision 共享 | `PASS` |
| 图攻击拒绝 | `7 / 7` |
| 1000／1001 节点边界 | `PASS / REJECTED` |
| closure/member UPDATE-DELETE | `4 / 4 REJECTED` |
| 失败回滚 closure/member | `0 / 0` |
| 业务表／指针／payload 文件快照 | `MATCH / MATCH / MATCH` |
| 正文 canary／secret 泄漏 | `0 / 0` |

## R1-01 修复

- 同幂等键命中后，先校验 root 与 request hash，再锁读并校验当前 graph。
- 当前 graph 重新规范化 members 并重算 manifest hash。
- hash 相同：仅返回既有 closure/member 快照的 previewId、revision、expiresAt、state、manifest 与结果字段。
- hash 变化：稳定抛出本地 `PREVIEW_STALE`；不会拼接旧 member 与新 graph/body preview。
- 新幂等键在变图后创建 `preview_revision + 1` 与新 manifest；旧 closure/member 快照保持一致。
- 持久化竞争或 member 写入异常归类为本地 `PERSISTENCE_CONFLICT`，不泄漏原始 SQL/constraint 文本。
- 增补 coordinator 层的 current pointer、policy pointer/owner、payload metadata 与 1000 节点校验。

## R1-02 测试覆盖

`LocalV1S3ADeletionPreviewTest` 当前 8 个测试全部通过：

- 同图重放逐字段一致；同 key 异 hash与异 root均为 `IDEMPOTENCY_CONFLICT`。
- 增加共享关系后旧 key 为 `PREVIEW_STALE`，新 key 为 revision 2，旧快照逐行 MATCH。
- 另一 Memory 的历史 revision 引用共享 SourceUnit/Payload 时进入 `AFFECTED_PENDING_CHOICE`。
- current pointer、policy owner、跨 Source、零 payload、多 payload、非法 hash、负 size 共 7 类攻击均在 closure 插入前拒绝。
- 规范化节点数 1000 可通过，1001 返回 `GRAPH_LIMIT_EXCEEDED`。
- closure/member 的 UPDATE 与 DELETE 四类操作均由数据库拒绝。
- 通过真实 PostgreSQL 事务制造 member 持久化失败，closure/member 新增数均为 0。
- 独占预览前后四 schema 业务表、Memory/current-policy 指针、payload 相对路径/size/SHA-256 均 MATCH。
- evidence 正文 canary 不出现在 result、exception 或本报告；正式 Memory preview 仍按 120 code-point 规则保留。

## 执行命令

```text
./mvnw.cmd -o -pl modules/database-adapter -am -Dtest=LocalV1S3ADeletionPreviewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`。

迁移路径在真实 PostgreSQL 临时数据库中验证：空库 `11`、V010→V011 `1`、重复 `0`。

## 机械与范围核对

- V001–V010：`MATCH`；仅保留 V011 与本轮 S3A 代码/测试。
- jOOQ A/B/tracked：`MATCH`；沿用同一 V011 快照的 85 文件 A/B 路径与哈希核对。
- contracts：`MATCH`；frontend：`MATCH`；无 API/UI、OpenViking、S3B/S3C、fence/run/block 或物理删除变更。
- `git diff --check`：`PASS`。
- 仅合成数据与本地 Docker PostgreSQL；未读取或修改真实 payload 文件。

## 网络与 Git 事实

- 首次 Task25A 旧生成脚本：网络请求 `是`（Maven 远程解析曾发起尝试）；域名 `UNKNOWN`（未保留完整域名日志）；下载成功 `否`；缓存变化 `UNKNOWN`。
- 本轮所有 Maven 命令均使用 `-o`：网络请求 `否`；Docker 使用本机已有镜像，未下载新依赖。
- 真实 Git index：未暂存 `0 → 0`；HEAD `4ebec3a6f917c12f25bd59ded94c97ef645101b7 → 4ebec3a6f917c12f25bd59ded94c97ef645101b7`。
- 工作区：`tracked=11`、`untracked=15`、`staged=0`、越界=`0`。
- Git 写操作：`否`。
