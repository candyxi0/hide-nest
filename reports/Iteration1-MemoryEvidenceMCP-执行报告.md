# Task42A-R1｜完整证据 MCP 正式排序与适用边界封口执行报告

## 实施状态

ITERATION1_MEMORY_EVIDENCE_MCP_R1_READY_FOR_LOCAL_COMMIT

## R1 返工裁定

保留 Task42A 当前工作树继续窄修，禁止回退。三个 R1 点：

1. **R1-01**：正式 API 按 `relation.createdAt + relationId` 输出 anchor block，可能 `[20,21]` 在前、`[1,2]` 在后；原实现直接要求原始顺序按 ordinal 上升，会误判合法响应。
2. **R1-02**：工具说明写成"仅用于合成资料"，但该工具是 local-private 只读证据读取能力，未来导入真实记忆后仍可用。
3. **R1-03**：`validateLoopbackBaseUrl()` 抛 `CloseoutClientError`，不被 `sanitizeError` 识别，导致非法 base URL 映射为 `INTERNAL_FAILURE`；`actorKind/actorStableRef` 未校验空字符串。

## R1 修改文件（窄修范围内）

R1 共触碰 6 个文件，其中 2 个为 Task42A 初始修改（modified）、4 个为 Task42A 初始新增（added）——从 Git 视角分类不变：

**Git modified = 2**

- `apps/codex-adapter/src/mcp.ts`
- `apps/codex-adapter/src/mcp.integration.test.ts`

**Git added = 10**（R1 在其中 4 个上做了进一步编辑，但 Git 新增状态不变）

- `apps/codex-adapter/src/memory-evidence-input.ts`、`*test.ts`
- `apps/codex-adapter/src/memory-evidence-canonicalizer.ts`（R1 触碰）、`*test.ts`（R1 触碰）
- `apps/codex-adapter/src/memory-evidence-client.ts`（R1 触碰）、`*test.ts`
- `apps/codex-adapter/src/memory-evidence-tool.ts`、`*test.ts`（R1 触碰）
- `reports/Iteration1-MemoryEvidenceMCP-执行报告.md`、`-Evidence.json`

总路径 12，去重 12。

## R1-01｜语义 ordinal 规范化

`buildSegments()` 改为：

1. 按原始响应识别连续 anchor block（anchor 回流仍拒绝）
2. 每个 block 内 sourceUnitId 唯一、ordinal 严格连续递增
3. block 完整验证后，按 `firstOrdinal` 升序规范化 block 顺序；相同 firstOrdinal 用 anchorId 仅作确定性 tie-break，随后因区间重叠拒绝
4. 在规范化后的 block 上检查区间不重叠、不倒序、不得相邻伪分段
5. `segmentNo` 与输出顺序使用规范化后的语义顺序
6. 不得对消息正文、说话者或时间排序；仅重排完整 anchor block

**新增测试**：

- 正式合法倒序 block：原始 `B=[20,21], A=[1,2]` → PASS，输出 `[1,2]→[20,21]`
- 三段随机 relation 顺序 `C=[30], A=[1,2], B=[20]` → PASS，输出 `A→B→C`
- anchor 回流仍 REJECTED（不被全局 ordinal 排序掩盖）
- 相同 firstOrdinal 因区间重叠仍 REJECTED

## R1-02｜修正只读工具适用边界

删除 `仅用于合成资料，不适用于真实资料或生产记忆。`

替换为：

> 仅用于当前本地私有 Nest 中、已由 ContextPack 返回并完成三字段绑定的已保存记忆；不得读取任意外部、未授权或未经 ContextPack 绑定的资料。

MCP integration 精确断言新描述，禁止旧 synthetic-only 文案残留。

## R1-03｜配置错误稳定分类 + actor 字段收紧

`loadMemoryEvidenceConfig()` 内对 `validateLoopbackBaseUrl()` 做最小 try-catch 转换：任何 base URL 校验失败稳定抛 `MemoryEvidenceClientError("LOCAL_CONFIGURATION_MISSING", ...)`，不得回传原始 URL。

`actorKind`、`actorStableRef` 合法性收紧为非空字符串，并加空值反证；仍不投影内部 actor 字段。

**新增测试**：

- 非 loopback base URL → 精确 `LOCAL_CONFIGURATION_MISSING`，HTTP 到达 0，错误中无 URL
- 空 `actorKind`、空 `actorStableRef` → REJECTED

## 验证结果

| 验证项 | 结果 |
|--------|------|
| Codex Adapter `typecheck` | PASS |
| Codex Adapter `lint` | PASS（仅 generated/events 两条既有 unused-eslint-disable warning） |
| Codex Adapter `test` | 17 文件，275 passed，1 skipped（既有），0 failed |
| Codex Adapter `build` | PASS |
| `git diff --check` | PASS（仅 LF→CRLF 转换警告，无 trailing whitespace 错误） |
| 根 Node 四门 | NOT_RUN_REUSE_TASK42A_PASS |
| Maven/Docker/OpenAPI/浏览器/真实家庭 API | NOT_RUN |

### 测试汇总（Task42A 初始 + R1 新增）

- **Task42A 初始（61）**：输入 9 + canonicalizer 28 + client 19 + tool 4 + integration 3
- **R1 新增（5）**：canonicalizer 语义排序 4 + actor 空值 1 + tool 非 loopback URL 1（一个测试覆盖两个空值）
- **总计**：275 passed，1 skipped（既有），0 failed

### 回归

- CandidateSet 关窗工具、ContextPack 检索工具行为不变；`tools/list` 恰好三个工具。
- 旧 synthetic-only 文案残留 = 0。
- 无配置环境下 `tools/list` 成功且为三个工具；工具调用 fail closed `LOCAL_CONFIGURATION_MISSING`。

## 机械证明

- Java / 数据库 / 迁移 / OpenAPI / generated / React / 部署修改：0-0-0-0-0-0-0。
- HEAD / index 起止：MATCH；staged = 0；越界 = 0。
- 未联网；未 stage / commit / push。
- 正文、token、capability、secret、base URL、绝对路径泄漏：均为 0。

## 环境

- Git head：`195fd953a62252bf9de51a9bdac57314b1ad4c01` @ `main`
- 越界修改：0
- Staged 文件：0
- Git 写操作：无
- 联网：无

## 回执

```text
返工状态：ITERATION1_MEMORY_EVIDENCE_MCP_R1_READY_FOR_LOCAL_COMMIT
正式API随机block顺序／语义规范化：PASS/PASS
anchor回流／gap-overlap-adjacent攻击：REJECTED/PASS
local-private真实记忆适用说明／旧synthetic-only残留：PASS/0
非法base URL稳定分类／HTTP到达：LOCAL_CONFIGURATION_MISSING/0
内部actor空值攻击／投影：REJECTED/0
Codex Adapter四门／diff-whitespace：PASS/PASS
本轮根Node／Maven／OpenAPI：NOT_RUN_REUSE_TASK42A_PASS/NOT_RUN/NOT_RUN
真实Git index／staged／越界：MATCH/0/0
联网／Git写操作：否／否
真实家庭API烟测：NOT_RUN（待提交部署后）
报告路径：D:\myproject\hide-nest\reports\Iteration1-MemoryEvidenceMCP-执行报告.md
```