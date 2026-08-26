# Task49A-R2B｜hide-nest 家庭发布流水线真实传输最终收口 · 执行报告

最终收口状态：`HOME_DEPLOY_PIPELINE_V1_R2B_READY_FOR_LOCAL_COMMIT`

本报告记录 R2B 三项真实传输收口。**未 stage/commit/push，未真实 scp / Execute / InitializeState，家庭写=0。**

---

## R2B-01｜scp 远端目标绑定 hide-nest 根（PASS）

- 冻结 `RemoteBase = ~/hide-nest`（不随用户输入变化）。
- scp 目标精确为 `user@host:~/hide-nest/deploy/tmp/incoming/<target>-<nonce>/<tar>`。
- `execute-bundle` 仍只接收 BASE 相对路径 `deploy/tmp/incoming/<target>-<nonce>/<tar>`。
- fake scp 反证断言完整目标字符串（含 `~/hide-nest` 根），旧缺根路径被测试杀死。
- `RemoteHost` 只允许 Tailscale：`user@100.64.0.0/10`（100.64–100.127）或 `user@*.ts.net`；公网/LAN/任意域名拒绝。

## R2B-02｜真实 SSH 统一经 SshPath（PASS / cmd 拼接残留 0）

- 所有真实 SSH 调用统一经 `Invoke-SshScript`（已解析 SshPath；`.sh` fake 经解析 Git Bash 运行），无硬编码 ssh，无 `cmd /c` 命令字符串拼接。
- remote-deploy.sh 以 **UTF-8/LF 原始字节**经 .NET `Process` 写入子进程 stdin（无 CRLF 转换）。
- 参数按独立 argv 传递（逐参数加引号供 OS 解析）；target/nonce/path 先闭合验证。
- fake ssh/scp 覆盖完整真实分支：`prepare-upload → scp → execute-bundle → cleanup-upload`，断言顺序/argv/stdin-hash/exit/失败停止位置。
- prepare 失败不 scp；scp 失败不 execute；execute 失败仍 cleanup；cleanup 失败如实返回。

## R2B-03｜Bundle SHA 部署锁内校验（PASS / 双重锁 0）

- 先解析并验证 incoming 相对路径 → `acquire_lock` → **锁内**计算并比较 tar SHA → SHA MATCH 后才创建 unpacked 并解包。
- SHA 检查与解包之间不释放/重取锁；`op_execute_locked` 不再取锁。
- TOCTOU 反证：锁前替换/锁冲突/错误 SHA 均零解包、零 switch、state 不变。

## 验证门（全 PASS）

- 正常 `powershell.exe -File` self-test：**58 PASS / 0 FAIL**
- 完整真实分支 fake ssh/scp 端到端：PASS；scp 目标路径精确反证：PASS；Tailscale RemoteHost 攻击矩阵：PASS
- prepare/scp/execute/cleanup 故障矩阵：PASS；锁内 SHA/解包顺序反证：PASS
- PowerShell Parser / Git Bash bash -n / `git diff --check`：PASS/PASS/PASS；staged=0
- Evidence Node/PowerShell/strict 三门：PASS/PASS/PASS
- 真实家庭只读 PlanOnly：`PLAN_NO_OP`，远端写=0；真实 scp/Execute/InitializeState：NOT_RUN

## 完成回执

```text
最终收口状态：HOME_DEPLOY_PIPELINE_V1_R2B_READY_FOR_LOCAL_COMMIT
scp远端目标hide-nest根绑定：PASS
RemoteHost Tailscale闭合：PASS
SSH统一SshPath／cmd拼接残留：PASS/0
prepare-scp-execute-cleanup完整真实分支fake：PASS
失败停止与cleanup矩阵：PASS
bundle SHA锁内校验／双重锁：PASS/0
API-Console-Adapter真实离线构建：PASS/PASS/PASS
Adapter rawMaps／artifact files-maps-tools：196-98/98-0-3
bundle SHA-manifest-request：PASS/PASS/PASS
self-test／parser／bash-n／diff-check：PASS/PASS/PASS/PASS（58）
真实家庭PlanOnly／家庭写：PLAN_NO_OP/0
真实scp-Execute-InitializeState：NOT_RUN
Evidence三门／Git scope-报告：PASS/PASS
Git index／staged／越界：MATCH/0/0
联网：仅既有Tailscale SSH只读Plan
Git写操作：否
报告路径：D:\myproject\hide-nest\reports\HomeDeployPipeline-执行报告.md
```

完成即停，不开始真实初始化、部署或 Bubble。
