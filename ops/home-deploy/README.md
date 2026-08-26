# hide-nest 家庭一键发布流水线 V1

版本化、可复跑、可审计的家庭发布流水线。小林只需：push `origin/main` → 运行仓库外
`发布-hide-nest家庭版.ps1` → 核对目标提交短哈希 → 普通更新自动完成构建、备份、上传、
原子切换、重启、基础烟测、清理与报告 → 最终体验仍由小林本人验收。

**V1 只做"通用基础门"，不伪造每项新业务语义已验收。** 新功能若需要专用真实烟测，仍需
单独、已评审的 smoke profile 或工单。

## 目录职责分层

| 文件 | 职责 |
|---|---|
| `Deploy-Home.ps1` | Windows 本机编排：Git 门、组件分类、离线构建、制品、SSH、报告 |
| `remote-deploy.sh` | 家庭主机引擎：精确路径校验、flock 锁、备份、原子切换、服务控制、回滚、状态 |
| `component-map.json` | 变更路径 → API/Console/Adapter/Migration 影响映射（Auto 分类） |
| `tests/Test-DeployHome.ps1` | 自带反证自测（28 项），使用临时 fake repo + fake home，测试后精确清理 |
| `tests/fake-remote.sh` | 生成本机 fake 家庭根并驱动 `remote-deploy.sh`，绝不动真实家庭主机 |

仓库外：
| 文件 | 职责 |
|---|---|
| `发布-hide-nest家庭版.ps1` | launcher：固定本机仓库与家庭主机入口，面向小林，不含秘密 |
| `hide-nest家庭部署基线-bootstrap.json` | 已知混合基线（只读），供 PlanOnly/校验 |

## 命令

```powershell
# 本机（仓库内引擎）
Deploy-Home.ps1 `
  -RemoteHost <ssh-host> `
  [-TargetCommit <full-sha>] `
  [-PlanOnly] `            # 默认
  [-Execute] `
  [-ConfirmCommit <short-or-full-sha>] `
  [-BootstrapStatePath <json>]

# 小林日常（仓库外 launcher）
发布-hide-nest家庭版.ps1 -PlanOnly                  # 只读计划
发布-hide-nest家庭版.ps1 -Deploy <目标短哈希>        # 短哈希确认后执行
```

## 安全语义（V1）

- **默认 `-PlanOnly`**；没有 `-Execute` 绝不产生远端写操作。
- `TargetCommit` 省略时取 `origin/main`（本地 remote-tracking ref，不取脏工作树）。
- Execute 必须满足：`HEAD == origin/main == TargetCommit`、`staged=0`、tracked worktree
  clean、除精确 QA 目录外无未知 untracked、`ConfirmCommit` 精确匹配目标提交。
- 从 `git archive <TargetCommit>` 构建，不使用当前工作树文件。
- 未知参数/组件/路径/远端状态均 **fail closed**。
- 所有远端写操作先取得 `flock`（或可移植 mkdir 锁）部署锁。
- 同一提交同一状态重跑 **NO_OP/EXACT**，不重复重启或复制。
- 禁止 `git add/commit/push/reset/checkout/clean`。
- 禁止输出任何 secret/env 正文。

## 组件影响（Auto 分类，冻结门）

- 根 Node `package.json`/`package-lock.json` 变化 → Console + Adapter。
- 新 migration → Migration + API，且触发**高风险确认门**。
- V001—已部署版本任何修改/删除/checksum 变化 → 立即 **BLOCKED**，不允许自动部署。
- 未命中 component-map 的 tracked 路径 → `UNCLASSIFIED_PATH`，停止。
- `reports/** docs/** README.md scripts/**` 纯报告/文档变化 → `NO_RUNTIME_IMPACT`
  （显式白名单，不用宽泛正则吞未知路径）。
- 不允许人工指定 Components 绕过 Auto 的更大影响集合。

## 高风险确认门

出现以下任一必须返回 `HOME_DEPLOY_HIGH_RISK_CONFIRMATION_REQUIRED`，并停在任何远端写
操作之前：新 Flyway migration、数据库角色/权限变化、`deploy-r1-grants.sql` 变化、
PostgreSQL 容器/镜像/卷变化、payload root 变化、Embedding 模型/维度/地址变化、
wrapper/secrets/service definition 变化、删除/重建动作、流水线自身被目标提交修改。

迁移执行须另有第二次确认（`MIGRATION:<target-short-sha>`），家庭主机保存 0600 备份/manifest，
报告只记 hash/count。**V1 禁止自动数据库回滚**；迁移后失败应停止并返回数据库恢复裁定。

## 远端部署与回滚

- staging 与正式目标同一文件系统；上传后远端重新计算逐文件 hash；本地/远端集合双向一致才继续。
- 部署前备份受影响组件 + manifest；原子切换（API JAR 临时文件+rename；Console/Adapter dist 目录 rename）。
- 只停止/重启受影响服务；PostgreSQL/Embedding 不受影响时 PID/容器 ID 必须 MATCH。
- 普通二进制/静态部署失败自动恢复旧制品；回滚失败立即停止返回 `ROLLBACK_FAILED`。
- 保留最近固定数量回滚目录（建议 3）；V1 只实现策略，不在真实主机执行清理。
- 删除临时目录前必须解析绝对路径并证明位于精确 staging/temp 根；禁止 broad glob/rm。
- 危险目标（HOME / `~/hide-nest` 根 / `/`）一律拒绝删除。

## 基础烟测（V1 通用 — 收窄范围）

V1 的 `remote-deploy.sh smoke_basic` 只实现**通用基础门**，绝不冒充全套业务 PASS：

- 四服务 8080/4173/5432/8090 均存在且 **loopback-only**（`ss` 检查绑定地址）。
- Console 根路径 HTTP 200（可用 `curl` 时）。

以下**不属于 V1 烟测**（未实现，不得由 V1 声称 PASS）：正式 MCP initialize/tools-list=3、
Flyway history 一致性、fixture HTTP 暴露、Console 安全头（no-store/nosniff/CSP）、
memory/revision/vector/payload 计数起止、profile/运行角色、Docker 新增 container/volume/network、
遗留进程清理。这些需单独、已评审的 smoke profile 或工单。

部署成功状态只能是：`HOME_DEPLOY_BASIC_GATES_PASS_READY_FOR_XIAOLIN_QA`（仅代表上述通用基础门通过，不代表业务语义已验收）。

## 验证门

- PowerShell Parser：全部 ps1 PASS
- `bash -n`：全部 sh PASS
- 自带 self-test：全部 PASS，报告精确计数
- `git diff --check` PASS
- Evidence JSON：Node/PowerShell 双解析、严格重复 key
- 路径集合与 Git status 双向 MATCH；staged=0
- 无联网（仅真实 PlanOnly 的 Tailscale SSH）

## 说明

本仓库内 V1 不执行真实 InitializeState / 真实 Execute（另开 Task49C，经 hide 复核与小林确认后
进行）。真实家庭 `PlanOnly` 为只读，目标 3a56e5f 计算为 `PLAN_NO_OP`，远端写操作=0。
