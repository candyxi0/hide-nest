# hide-nest

hide 记忆系统的正式源码仓库。当前阶段已经完成前端范围、技术架构、交互设计和高保真原型；生产工程脚手架将在 `HDM-001` 锁定 `DevelopmentBaselineManifest` 后由后续任务创建。

## 当前可审阅成果

- `docs/frontend/nest-console-product-ux-spec-v0.1.md`：产品范围、信息架构、页面与交互规范；
- `docs/frontend/nest-console-technical-architecture-v0.1.md`：React 本地治理控制台技术架构；
- `docs/frontend/nest-console-api-addendum-v0.1.md`：前端所需查询与本地 Adapter 接口增补；
- `docs/frontend/nest-console-acceptance-matrix-v0.1.md`：实现与验收矩阵；
- `docs/frontend/nest-console-design-review-v0.1.md`：设计自审、浏览器验证与残余风险；
- `docs/frontend/nest-console-delivery-manifest-v0.1.md`：本轮交付、验证与待确认项总表；
- `docs/frontend/prototype/index.html`：只含合成数据的高保真交互原型。

## 本地查看原型

在 `docs/frontend/prototype` 目录启动任意静态文件服务器，例如：

```powershell
python -m http.server 4173 --bind 127.0.0.1
```

然后访问 `http://127.0.0.1:4173/`。原型不会连接后端、不会保存数据，也不包含真实记忆、账号或密钥。

## 当前边界

- 这里的 HTML 原型是可执行设计基线，不是生产前端源码；
- 精确依赖版本由 `CandidateManifest` 在实际构建验证后锁定；
- 不在治理页面另建“关窗编辑器”，最终确认继续发生在 Codex 对话；
- 浏览器只连接本机回环地址上的 CodexAdapter，不直接持有远端凭据。
