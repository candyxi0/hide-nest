# Nest Core

面向 AI 的长期记忆与连续性底座。

Nest 起源于长期使用 AI 时遇到的问题：会话结束、上下文压缩或模型更换后，过去讨论过的事情、共同形成的理解和尚未结束的事项，往往难以自然接续。

项目希望将有价值的交互整理为可追溯的长期记忆，在相关情境下提供给当前模型，并保留继续修正和演变的空间。

**当前主分支为 V1 实现。V2 正在独立分支开发，重点推进自动摄取、记忆演变及 Agent 检索。**

→ [查看 V2 设计与开发进度](https://github.com/candyxi0/hide-nest/tree/codex/nest-v2-g5-progress)

## 为什么做 Nest

保留聊天记录，并不意味着过去的经历能够有效参与下一次判断。

例如，一个项目的设备超时配置从90秒调整为30秒，后续模型需要知道的不只是两个数字，还包括哪个配置当前有效、为什么修改，以及原始依据在哪里。

相似的问题也存在于日常协作中：一次反馈可能改变双方对某种沟通方式的理解，但这份变化很容易随着窗口切换而丢失。

Nest 围绕三个问题展开：

- 哪些内容值得成为长期记忆？
- 如何区分整理后的记忆与原始证据？
- 如何在需要时提供相关材料，而不反复加载全部历史？

## V1 提供什么

V1 已实现通过 MCP 提交确认后的记忆候选、检索上下文材料及读取记忆证据的路径，并包含后端 API、本地 Adapter 和 Web 控制台。

### 记忆与证据分离

长期记忆用于高效阅读，证据用于追溯依据。

系统分别组织记忆记录、版本和证据映射，使整理后的内容仍能回到其来源。模型生成的总结与原始材料保持区别。

### 经确认的记忆写入

V1 采用人工触发和确认的摄取流程：

1. 在对话中整理记忆候选与必要证据；
2. 确认候选内容；
3. 通过 MCP 提交；
4. 后端校验并保存记忆与证据关系。

这也是 V1 的一个使用成本：长期内容的形成依赖用户或上层 Agent 主动推动。

### 检索与证据下钻

通过 MCP 获取与当前问题相关的记忆材料，必要时进一步读取证据。

V1 使用向量检索及相关度控制。检索结果供当前模型参考，返回材料是否适用仍需要结合当前上下文判断。

### 查看与管理

仓库包含用于记忆查看和管理的 Web 控制台，以及相应的 API 合同、Adapter、测试和部署脚本。

## V1 的系统组成

| 组件                | 职责              |
| ----------------- | --------------- |
| Java Core / API   | 记忆、证据、版本及应用流程   |
| Codex Adapter     | MCP 工具接入和本地接口适配 |
| Nest Console      | 记忆查看与管理界面       |
| PostgreSQL        | 持久化存储           |
| Embedding Adapter | 向量生成与相关检索集成     |

Java 后端采用模块化组织，将领域能力、应用编排和基础设施适配分开。

本项目中的 Nest Console 是记忆管理界面。聊天前端属于独立应用。

## 技术栈

- **后端：** Java 25、Spring Boot、Maven
- **存储与检索：** PostgreSQL、Embedding 集成
- **Agent 接入：** MCP、TypeScript Adapter
- **控制台：** React、TypeScript、Vite
- **合同与测试：** OpenAPI、JSON Schema、Testcontainers、Vitest、Playwright

## 从 V1 走向 V2

V1 的实际试用帮助我们发现了一些限制：

- 手动摄取容易积压，难以随着日常交互持续进行；
- 主模型拥有 MCP 工具，并不意味着它会及时主动调用；
- 固定向量相似度阈值难以兼顾漏查与误召回；
- 单条摘要不足以表达长期理解的适用条件和变化过程；
- 已经出现在当前上下文中的内容，需要避免重复召回。

V2 正在围绕这些问题重设计。

| 方向    | V1             | V2 开发目标                |
| ----- | -------------- | ---------------------- |
| 摄取    | 人工触发、确认后提交     | 自动摄取并结合已有记忆形成或修订       |
| 检索    | MCP 调用、向量相关性筛选 | 自动触发、有预算的检索 Agent 按需追查 |
| 记忆演变  | 已有记忆、版本与关系机制   | 明确修订、接替及支持／反例的变化语义     |
| 上下文配合 | 依赖宿主调用与使用      | 根据来源和已交付版本避免重复贡献       |
| 认知处理  | 主要依靠上层模型       | 可插拔摄取与检索 Worker        |

V2 选择 LlamaIndex AgentWorkflow 承载认知处理，Java Core 继续负责正式数据、版本和事务边界。具体实现状态以 [V2 分支 README](https://github.com/candyxi0/hide-nest/tree/codex/nest-v2-g5-progress#readme) 为准。

## 项目的边界

Nest 为当前模型提供长期材料、依据和变化历史。具体业务规则、角色设定与本轮回应由上层应用和当前模型负责。

工程验证与连续性效果分别验收：

- 写入和检索测试通过，证明对应技术路径可用；
- 过去的经历是否在未来相关情境中被正确接续，需要进一步观察和评测。

目前项目仍在持续开发，不承诺不同模型和宿主具有相同的记忆使用效果。

## 仓库结构
```text
apps/
  api/                 后端 API
  worker/              仓库现有 Worker 应用
  codex-adapter/       本地 Adapter 与 MCP 接入
  nest-console/        Web 控制台

modules/
  evidence/            证据领域
  memory/              记忆、版本与关系
  runtime/             运行状态
  application/         应用流程编排
  security/            安全与访问边界
  database-adapter/    PostgreSQL 适配
  embedding-adapter/   Embedding 适配
  payload-adapter/     来源载荷适配
  contracts/           合同校验
  architecture-tests/  架构测试

contracts/             接口及消息合同
packages/              共享客户端与测试材料
docs/                  设计文档
reports/               实施及验证记录
ops/                   部署相关脚本
```

主分支现有 `apps/worker` 不等同于 V2 规划中的 Python Cognitive Worker。

## 开发与验证

### 环境

- JDK 25
- Node.js 24 或更高版本
- npm，版本约束见根目录 `package.json`
- Docker，用于需要数据库容器的集成测试

### Java 模块

Windows：
```powershell
.\mvnw.cmd -pl modules/contracts -am test
.\mvnw.cmd -pl modules/database-adapter -am test
```

Linux / macOS 使用 `./mvnw`。

### TypeScript 应用
```bash
npm ci
npm run typecheck
npm run lint
npm test
npm run build
```

以上命令用于开发验证，不包含完整的生产环境初始化。

仓库中的 [部署说明](ops/home-deploy/README.md) 面向作者现有的自托管环境，使用前需要核对配置、依赖和目标环境。

## AI 协同研发

Nest 采用人和 AI 共同设计、Coding Agent 分任务实施的研发方式。

项目负责人负责真实需求、产品含义与关键取舍，与 AI 推演领域模型、数据流和状态转换。实施任务明确范围与验收条件，通过代码审查、测试和执行回执进行复核。

协作过程中形成的任务拆解、上下文交接和验收经验，持续沉淀为可复用的共创 Skill。

项目保留设计与验证记录，便于区分已经实现的能力、仍在探索的方案，以及尚待真实使用验证的效果。
