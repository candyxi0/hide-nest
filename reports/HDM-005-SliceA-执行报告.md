# HDM-005 Slice A R2 传递版本纠偏后完成报告

`完成状态：HDM005_SLICE_A_R2_READY_FOR_HIDE_REVIEW`

## 1. 完成结论

Slice A 已在 Task17B、R1 与 R2 的共同 allowlist 和安全门内完成。两个新模块、九项直接候选、API/Worker 依赖骨架、正式架构规则、四组同规则负例与 fail-closed `db.ps1` 均已实现并通过验证。没有 migration、jOOQ generated 源码、repository/coordinator、业务表或 Slice B 实现。

R2 采用权威纠偏后的真实传递路径：

```text
spring-boot-starter-jooq 4.1.0
  -> spring-boot-jooq 4.1.0
     -> jooq 3.21.5
        -> r2dbc-spi 1.0.0.RELEASE
           -> reactive-streams 1.0.4 (version managed from 1.0.3)
```

项目没有直接声明 R2DBC/reactive-streams，没有 provider，也没有生产源码或项目字节码引用这些 API。未通过 POM 直加、降级、排除或 override 干预 Spring Boot 4.1.0 的 effective resolution。

## 2. 三次执行历史

1. 初始 Task17B 被用户级 youzan mirror 改道并在用户全局 Maven cache 留下 33 个变化文件；该轮立即停止，其制品不接受、变化不清理。
2. R1 使用 Central-only settings 与独立 cache，解析出 `reactive-streams 1.0.4`，与当时只授权 `1.0.3` 的裁定冲突，因此按门停止，没有自行改 POM。
3. R2 权威裁定把允许的 effective resolved 版本纠正为 `1.0.4`。本轮重新创建唯一 Central-only settings/cache，完成全部未执行项并清理仓库外临时目录。

R2 中 `verify.ps1` 先后暴露了 fail-closed judge 的 stderr 捕获、离线 compatibility plugin 预取、Spotless CRLF 三个工具链问题，均在既有 allowlist 内纠正；最终统一校验和独立格式校验通过。所有 Maven 下载行动态提取出的 artifact transfer 域名只有 `repo.maven.apache.org`，youzan 接受数为 0。

## 3. 模块、依赖与许可证

| 项目 | 实际结果 |
| --- | --- |
| 新模块 | `modules/application`、`modules/database-adapter` |
| 直接候选／许可证缺口 | 9／0 |
| jOOQ | 3.21.5 |
| Spring JDBC／TX | 7.0.8／7.0.8 |
| pgJDBC | 42.7.11，runtime |
| JUnit Jupiter | 6.0.3，test |
| Testcontainers | 2.0.5，test |
| Flyway core／PostgreSQL module／plugin | 12.4.0，仅 `database-tools` profile |
| jOOQ codegen Maven plugin | 3.21.5，仅 `database-tools` profile |
| API／Worker runtime Flyway | 0／0 |

九项候选的坐标、scope、接受路径、许可证与 Central 来源详见 DependencyLock。核心证据哈希：

- database-adapter effective POM：`E94580423DAD299617EA469C0A4FCFA6F1CB7B2C7C5DE93E826D97666C62504D`
- database-adapter verbose tree：`3A3CEAA62BA2502E68C2AE6606AEBD9B5E21D352BE967BA2CC792AC0D1B8821D`
- API runtime tree：`3264378EE036ECC0568D8A2D6DAF03D88552C40FA0D03F08E7E141721051A00C`
- Worker runtime tree：`9529EEFB2358D788696132252187811002565ED5608C73607C6AC473ABFB821D`

## 4. R2DBC 与架构 judge

| 门 | 结果 |
| --- | --- |
| 项目 POM 直接声明 R2DBC/reactive-streams | 0 |
| R2DBC provider/driver/pool、Spring Data R2DBC、Reactor | 0 |
| 生产源码引用 `io.r2dbc..`／`org.reactivestreams..` | 0 |
| 正式项目字节码引用 | 0；ArchUnit PASS |
| 允许传递 API 精确版本 | `r2dbc-spi 1.0.0.RELEASE`、`reactive-streams 1.0.4`，2/2 |
| reactive-streams upstream declared／managed-from | 1.0.3 |
| reactive-streams Spring Boot effective resolved | 1.0.4 |

`ArchitectureTest` 7/7、`DatabaseBoundaryTest` 5/5，共 12/12 正式测试通过。四组 fixture 使用同一正式规则验证：application 引入数据库基础设施、application 引入 reactive API、database-adapter 反向依赖 application、evidence 反向依赖 database-adapter 均被拒绝，4/4 PASS。

## 5. 构建、工具链与 smoke

| 验证 | 结果 |
| --- | --- |
| Central-only 空 cache 在线 `clean verify` | PASS |
| 同 cache `-o clean verify` | PASS |
| 最终仓库离线 `clean verify` | PASS；日志 SHA-256 `5CEE0EF00D831FEE4ADEB04A7A65A1D8E48E4DD6842F798149D5E3EBCB30C819` |
| 完整 `verify.ps1` | PASS；最终标记 `HDM003_VERIFY_PASS` |
| Node `npm ci/ls/typecheck/lint/test/build` | PASS；lint 仅 2 个既有 warning、0 error |
| OpenAPI 生成／check／兼容性 | PASS |
| Spotless／Prettier／`git diff --check` | PASS／PASS／PASS |
| 临时候选复制离线 `clean verify` | PASS；日志 SHA-256 `F039CA07E2AE445E9562307DED5F0E289C87DA3D5F2C1385E399EE055D396D1D` |
| API／Worker 有界启动 | exit 0／0；两个 `Started ...Application` 与脚本 marker 均存在 |
| API／Worker DataSource/Flyway 自动配置 | 0／0 |
| 遗留 JDK 25 Java 进程 | 0 |

候选副本由 `HEAD` 的仓库外 archive 基线生成，只覆盖当前 21 个 allowlist 工作树路径；`git diff --no-index --check` 无空白问题，随后在 JDK 25、Central-only 独立 cache、`-o` 下完成 11 模块 reactor 构建。

## 6. `db.ps1` fail-closed

| Action | exit | marker | 结果 |
| --- | ---: | --- | --- |
| Validate／Migrate／Generate／GenerateCheck／Test | 21 | `HDM005_SLICE_B_NOT_READY` | 5/5 PASS |
| Clean／Repair／Unknown | 20 | `HDM005_DB_ACTION_FORBIDDEN` | 3/3 PASS |

八次 judge 前后隔离 Maven cache 文件数为 3664/3664，Docker 为 15/1/37 → 15/1/37，Git 状态相同，禁止调用扫描为 0。脚本没有运行 Maven/Flyway、Docker、数据库或文件写入副作用。

## 7. 容器 metadata-only 锁

| 项目 | 证据 |
| --- | --- |
| 候选 | `pgvector/pgvector:0.8.2-pg18` |
| OCI index digest | `sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d` |
| linux/amd64 child digest | `sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c` |
| child response digest | 与 descriptor MATCH |
| config digest | `sha256:5a9c2dbe6ab521f35e87c81124aa5137678992ddabb9c11ef46e04e5172af73c` |
| PostgreSQL | `PG_MAJOR=18`；`PG_VERSION=18.4-1.pgdg12+1` |
| pgvector | OCI history 指向 `pgvector.git#v0.8.2` |
| 许可证 | 官方 v0.8.2 LICENSE SHA-256 `6bba9ebeb73e27477463b05e5ef1bf303bccbddb3db9bbc95905d351604d6a87` |
| SBOM | `NOT_PUBLISHED_OR_NOT_DISCOVERABLE_WITH_METADATA_ONLY` |
| attestation | amd64 attestation manifest descriptor 已发现；未下载其内容 |
| layer 下载／pull/create/run/build | 0／0 |

仅查询 token、OCI index、linux/amd64 child manifest、config blob、referrers、Docker Hub tag 和官方许可证文本；没有请求 15 个 layer descriptor 中的任何 layer blob。Docker 本地 images/containers/volumes 最终仍为 15/1/37。

## 8. 供应链与受保护输入

| 项目 | 结果 |
| --- | --- |
| 正式 Maven transfer 域名 | `repo.maven.apache.org` |
| Node registry | `registry.npmjs.org` |
| 容器元数据域名 | `auth.docker.io`、`registry-1.docker.io`、`hub.docker.com`、`raw.githubusercontent.com` |
| youzan 接受 | 0 |
| 用户全局 Maven repository 本轮变化 | 0 |
| 用户 Maven settings SHA-256 | `C1F8C3EAF92474855C811E4401DEBB043DC7C7CAA487B8CECF41EE18013C7D1B`，MATCH |
| 预检报告 SHA-256 | `DFFE4F41FD89EF67457749AB2C1DDFB5ACB471A19DE39FA01B654EC26919E10E`，MATCH |
| CandidateManifest SHA-256 | `852E3CEB5DDDC10469B2A76E5DAA66268B14DA77EC0258FA682AFAC023AA4257`，MATCH |
| package.json SHA-256 | `697A91C81E8AB14A6E5F9A76B65870062955724D997B8618B2581FF37B421CAB`，MATCH |
| package-lock.json SHA-256 | `F2DA16114A2AC1D21C2E6DA3E9EC6658925AC4C3D447899996BE99452DA6178C`，MATCH |

R2 的仓库外 settings/cache、evidence、日志、候选副本以及未使用的临时副本均在报告固化后删除；未清理或修改用户 cache。

## 9. Git、边界与最终状态

| 项目 | 最终值 |
| --- | --- |
| HEAD | `8877cc72ecbd320a31ce2f922e579c1d00495c55` |
| index tree | `67c00ee0eeca70d8858eef17e8d328f6fc23a676`，未变 |
| 工作树 | tracked modified 6、untracked 15、staged 0 |
| allowlist 外变化 | 0 |
| migration／generated | 0／0 |

没有执行 Git add、commit、push、reset、restore、checkout、stash、clean 或 rebase。实现与三份 Slice A 报告均处于 Task17B allowlist；两份预检输入只读且哈希不变。Slice B 未开始。
