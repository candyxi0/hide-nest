# nest console 前端设计包

## 权威顺序

1. `nest-console-product-ux-spec-v0.1.md`：做什么、不给页面什么权力；
2. `nest-console-technical-architecture-v0.1.md`：在哪里运行、如何构建与隔离凭据；
3. `nest-console-api-addendum-v0.1.md`：实现页面所需但旧契约缺失的接口；
4. `nest-console-acceptance-matrix-v0.1.md`：如何证明实现没有越界；
5. `prototype/`：交互和视觉的可执行参考；
6. `nest-console-design-review-v0.1.md`：当前复核结果与残余风险。
7. `nest-console-delivery-manifest-v0.1.md`：交付总表与小林确认入口。

如原型和前三份文字契约冲突，以文字契约为准；如本目录与系统已 Accepted／LOCKED 的身份、治理、closeout、删除或恢复语义冲突，以系统正式契约为准并停止实现。

## 已确定技术方向

- React＋TypeScript＋Vite；
- React Router Declarative Mode；
- TanStack Query 管理远端状态；
- Radix Primitives 只提供无样式可访问行为；
- CSS Modules＋原生 CSS design tokens；
- OpenAPI 生成 Fetch 客户端；
- Vitest＋Testing Library＋MSW，Playwright＋axe；
- npm workspaces；
- 控制台由本机 CodexAdapter 通过回环地址提供并代理 `/v1`，浏览器不持有远端凭据。

精确版本在 HDM-001／002 的 CandidateManifest 中通过构建与兼容验证后锁定，不在设计阶段凭记忆写死。
