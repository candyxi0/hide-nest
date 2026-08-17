# Task37｜Local V1 本地私有模式启用执行报告

## 实施状态

```text
LOCAL_V1_PRIVATE_MODE_READY_FOR_HARDWARE_HANDOFF
```

## 关键约束声明

```text
LOCAL_PRIVATE_SINGLE_USER_TAILSCALE_SSH_TUNNEL_ONLY
NO_PUBLIC_LISTENER
LEGACY_SYNTHETIC_NAMES_RETAINED_FOR_COMPATIBILITY
HARDWARE_DEPLOYMENT_NOT_STARTED
```

## 1. 基线与 Git 状态

| 项目 | 值 |
|------|-----|
| HEAD | `620a786886da1d372d2c022e493faaba06e87051` |
| 索引树 | `609c0668cff12e6197b43125596c4270f83e513f` |
| 索引与 HEAD 一致 | 是 |
| staged 文件数 | 0 |
| 越界改动 | 0 |

允许的未跟踪目录：`reports/local-v1-read-browser-qa/.playwright-artifacts/`（工单明示保留）。

## 2. 实施范围

仅修改以下允许路径内的文件：

- `apps/api/src/main/java/io/github/candyxi0/hidenest/api/**`
- `apps/api/src/main/resources/application-local-private.properties`
- `apps/api/src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`
- `apps/api/src/test/java/io/github/candyxi0/hidenest/api/LocalV1PrivateModeHttpIntegrationTest.java`
- `reports/LocalV1-PrivateMode-执行报告.md`
- `reports/LocalV1-PrivateMode-Evidence.json`

未触碰 V001—V020 migrations、jOOQ generated tree、OpenAPI/contract generated、React 原型、MCP 工具名、Embedding/检索排序、CandidateSet 决策与投影、REVISE/SUPERSEDE 等冻结项。

## 3. 关键改动摘要

1. **Profile 扩展**：将所有 Local V1 正式读取与写入组件的 `@Profile("local-v1-synthetic")` 窄扩展为 `@Profile({"local-v1-synthetic", "local-private"})`；`DefaultProfileFailClosedConfiguration` 改为 `@Profile("!local-v1-synthetic & !local-private")`，默认与未知 profile 继续启动失败。
2. **Loopback 硬边界**：新增 `application-local-private.properties`，固定 `server.address=127.0.0.1`；新增 `LocalV1LoopbackEnvironmentPostProcessor`（`EnvironmentPostProcessor`）在 web 服务器绑定前校验 loopback 与高熵 bearer/capability；`LocalV1ReadConfiguration` 与 `LocalV1PrivateStartupGuardConfiguration` 双重启动 guard 在运行时再次校验。
3. **Capability 门锁**：`local-private` 启动必须同时配置高熵 bearer 与高熵 action capability，缺失/弱值均 fail closed，不降级为运行时 403。
4. **合成夹具物理隔离**：新增 `LocalV1SyntheticFixtureConfiguration`（仅 `@Profile("local-v1-synthetic")`），将 `LocalV1SharedEvidenceFixtureCoordinator` Bean 从共享 deletion configuration 中移出；`LocalV1DeletionFixtureController` 仍仅 synthetic。`local-private` 下 fixture controller/coordinator Bean 数均为 0，`POST /v1/deletion-fixtures` 返回 404。
5. **错误标题中性化**：gate 与 handler 中用户可见的“合成访问凭据”最小改为“本机访问凭据”；failure code、HTTP 状态与门控语义不变。
6. **异常处理补全**：`LocalV1ExceptionHandler` 扩展为双 profile 生效，并补充 `NoHandlerFoundException` / `NoResourceFoundException` 映射为 404，以覆盖 private 模式下不存在的 fixture 端点。

## 4. 强制测试结果

### 4.1 Task37 新增 targeted 测试

- **类**：`io.github.candyxi0.hidenest.api.LocalV1PrivateModeHttpIntegrationTest`
- **结果**：PASS
- **统计**：Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
- **覆盖要点**：
  - `local-private` + loopback + 强 bearer/capability + 测试 datasource/payload 正常启动；
  - 无 profile、未知 profile 启动失败；
  - `0.0.0.0`、非 loopback IP 启动失败；
  - bearer 缺失/弱值、capability 缺失/弱值分别启动失败；
  - `local-private` 下 fixture controller/coordinator Bean 数均为 0，`POST /v1/deletion-fixtures` 精确 404；
  - 合法 bearer 可达 read endpoint，非法 bearer 被拒绝；
  - closeout、CandidateSet、ContextPack、deletion 正式 endpoint 在 `local-private` 下已注册并受原 gates 保护。

### 4.2 API 模块回归测试

- **结果**：PASS
- **统计**：Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
- **说明**：包含 `local-v1-synthetic` 既有行为验证，fixture 仍仅在该 profile 可用。

### 4.3 Maven 离线完整构建

- **命令**：`./mvnw.cmd -o clean verify`（JDK 25）
- **结果**：PASS（BUILD SUCCESS）
- **统计**：Tests run: 600, Failures: 0, Errors: 0, Skipped: 1；总耗时 5:33 min
- **说明**：离线执行，未联网。

### 4.4 架构测试

- **结果**：PASS
- **统计**：Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
- **正文扫描哈希**：`df84709c46551276f1ef1bb194b2f1d72817ff83c62a45429bf4b3c49abc5de0`

## 5. 质量门

| 门 | 状态 | 说明 |
|----|------|------|
| `git diff --check` | PASS | 0 个空白错误；4 个 LF/CRLF 转换警告（非错误，不影响构建） |
| 秘密扫描 | PASS | token/capability 仅出现于测试源码；报告与生成产物中 0 次出现 |
| 正文扫描 | PASS | 0 处正文泄漏 |
| 主机地址扫描 | PASS | 0 处 Tailscale/LAN/公网地址泄漏 |
| Docker 状态 | PASS | 新增 containers/volumes/networks = 0/0/0；遗留进程 = 0 |
| Git index 起止一致 | PASS | 索引树与 HEAD 一致，staged = 0 |
| 越界改动 | PASS | 0 |

## 6. 冻结项状态

| 项目 | 状态 |
|------|------|
| V001—V020 migrations | UNCHANGED |
| contracts / generated clients | UNCHANGED |
| React / CSS / 原型 | UNCHANGED |
| MCP 工具名 / schema version | UNCHANGED |
| Node 构建 | NOT_RUN_UNCHANGED |
| OpenAPI generate/check | NOT_RUN_UNCHANGED |
| jOOQ GenerateCheck | NOT_RUN_UNCHANGED |

## 7. 网络与操作声明

- 联网：否（全程 `./mvnw.cmd -o` 离线）
- Git 写操作：否（未 stage、未 commit、未 push）
- 家庭小主机部署：NOT_STARTED

## 8. Evidence 文件

- `D:\myproject\hide-nest\reports\LocalV1-PrivateMode-Evidence.json`
