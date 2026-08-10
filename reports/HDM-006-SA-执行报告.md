# HDM-006 Slice A R1 执行报告

`状态：HDM006_SLICE_A_R2_READY_FOR_LOCAL_COMMIT` `基线：main @ eec36bb` `时间：2026-08-10` `补丁：Task19B-R2`

## 0. R2 执行摘要（报告补丁）

HDM-006 Slice A 范围内返工完成。关闭 4 个问题：R1-01 Anchor-SourceUnit 同源约束、R1-02 MemoryRelation 目标类型闭合、R1-03 受控 code 字符格式、R1-04 报告机械真实性。

- 新表／迁移 history：6／8
- jOOQ 生成文件：63（evidence:14 + memory:32 + runtime:16 + DefaultCatalog:1）
- 针对性测试：57 PASS（42 DatabaseSliceBContractTest + 15 SliceCCoordinatorTest）
- 全量 Maven offline clean verify：11 模块 BUILD SUCCESS

## 1. R1-01 Anchor-SourceUnit 同源约束

### 实现

新建 `evidence.enforce_anchor_unit_same_source()` 函数（LANGUAGE plpgsql, SET search_path = pg_catalog, evidence）与 `source_anchor_unit_same_source_guard` CONSTRAINT TRIGGER（AFTER INSERT OR UPDATE, DEFERRABLE INITIALLY DEFERRED）：

- 逐行验证 `source_anchor.source_id = source_unit.source_id`
- 不匹配时 raise HDM006_ANCHOR_UNIT_SOURCE_MISMATCH (ERRCODE 23514)
- PUBLIC EXECUTE 已 REVOKE

### 测试证据

| 场景 | 结果 |
|---|---|
| 同 Source 绑定（anchorA+unitA 均属 srcA） | PASS |
| 跨 Source 绑定（anchorA(srcA) + unitB(srcB)） | REJECTED 23514 |
| 反向跨 Source（anchorB(srcB) + unitA(srcA)） | REJECTED 23514 |

## 2. R1-02 MemoryRelation 目标类型闭合

### 实现

替换原有两个独立 CHECK 为单一 `memory_relation_target_check`：

```sql
CHECK (
    (relation_type = 'EVIDENCED_BY' AND to_anchor_id IS NOT NULL AND to_revision_id IS NULL)
    OR (relation_type <> 'EVIDENCED_BY' AND to_revision_id IS NOT NULL AND to_anchor_id IS NULL)
)
```

保留 9 个 frozen relation_type，不新增第 10 个。

### 测试证据

| 场景 | 结果 |
|---|---|
| EVIDENCED_BY → anchor（合法） | PASS |
| INTERPRETS → revision（合法） | PASS |
| 双 NULL | REJECTED 23514 |
| 双非空 | REJECTED 23514 |
| EVIDENCED_BY → revision | REJECTED 23514 |
| SUPPORTS → anchor | REJECTED 23514 |
| REFINES → anchor | REJECTED 23514 |
| INVALID_TYPE | REJECTED 23514 |

## 3. R1-03 Code 字符格式

### 实现

5 个 code 列统一使用 `CHECK (col ~ '^[A-Z][A-Z0-9_]{0,63}$')`：

- `source.source_kind`
- `source_payload.payload_kind`
- `source_payload.store_adapter`
- `source_payload.retention_class`
- `source_anchor.anchor_kind`

保留 text COLLATE "C"，不猜语义 allowlist。platform、external_ref、source_version 不受此格式约束。

### 测试证据（6 类攻击均以 23514 拒绝）

| 攻击类型 | 示例 | 结果 |
|---|---|---|
| 小写 | `codex` | REJECTED |
| 前导空格 | ` CODEX` | REJECTED |
| 内部空格 | `CODE X` | REJECTED |
| 连字符 | `CODE-X` | REJECTED |
| 纯数字 | `12345` | REJECTED |
| 非 ASCII | `CODÉX` | REJECTED |
| store_adapter 小写 | `local_file` | REJECTED |
| 合法值 | `CODEX`, `MESSAGE`, `TEXT`, `PERSISTENT`, `AWS_S3` | PASS |

## 4. R1-04 报告机械真实性

### 修正内容

- untrackedFilePaths：21（`git ls-files --others --exclude-standard`）
- gitStatusCollapsedEntries：8（`git status --porcelain` 目录折叠）
- untrackedLogicalCategoryCount：6（V008 + evidence新生成 + MemoryRelation新生成 + 预检报告×2 + 执行报告 + Evidence JSON）
- 类别内文件动态相加：1+14+2+2+1+1 = 21
- jOOQ 分类：evidence 14 + memory 32 + runtime 16 + DefaultCatalog 1 = 63
- 新增生成文件：16（evidence 14 + MemoryRelation 2）
- 修改生成文件：47（原有文件因 schema 变化重新生成）

## 5. 迁移与生成门

| 门 | 结果 |
|---|---|
| 空库 V001→V008 | PASS（8 migrations, v008） |
| V007→V008 升级 | PASS |
| 重复迁移（migrationsExecuted=0） | PASS |
| jOOQ A/B 双生成一致 | PASS |
| jOOQ tracked 一致 | PASS |
| GenerateCheck | PASS |

## 6. 最终返工状态

```text
报告补丁状态：HDM006_SLICE_A_R2_READY_FOR_LOCAL_COMMIT
Anchor 同源合法／跨源：PASS／REJECTED
Relation 合法集合／错误目标：PASS／REJECTED
Code 合法／六类攻击：PASS／全部拒绝
空库／升级／重复迁移：PASS／PASS／PASS
jOOQ A-B-tracked／总数：PASS／63（evidence:14 + memory:32 + runtime:16 + DefaultCatalog:1）
针对性测试／Java offline clean verify：57 PASS／11 模块 BUILD SUCCESS
正文／secret 泄漏：0／0
预检两文件起止 hash：MATCH／MATCH
tracked modified／untracked file paths／staged／越界：12／21／0／0
git status 折叠条目／逻辑类别：8／6
遗留进程／Docker 新增残留：0／0
联网／Git 写操作：否／否
报告路径：D:\myproject\hide-nest\reports\HDM-006-SA-执行报告.md
证据路径：D:\myproject\hide-nest\reports\HDM-006-SA-Evidence.json
```
