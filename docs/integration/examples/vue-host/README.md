# Vue 3 宿主示例

把本目录文件放进宿主 Vue 工程（Vite 或等价构建器），或在独立页面里用于验证接线：

- `index.html`：加载固定版本 `/assistant.js`，挂载 `main.ts`。
- `main.ts`：`createApp(App).mount("#app")`。
- `App.vue`：一个按钮、一个短票函数、一个 `onHostEvent`；`autoLauncher:false` 由宿主按钮打开。
- `assistant.d.ts`：示例用的最小全局声明（正式接入建议由平台类型生成）。

## 接线要点

1. `endpoint` 是平台网关基址；`appCode`/`integrationId` 来自配置发布页。
2. `ticket()` 调用同源 BFF（见 `../bff/ticket-server.mjs`），浏览器不保存长期密钥。
3. `recordId` 只作为 pageContext 的显式业务字段；它不会改变 Actor、tenant 或角色。
4. `onHostEvent` 只处理 `ready/opened/closed/run-started/approval-required/result/error` 七类；未知事件忽略。
5. `result` 到达后，宿主如需读取业务记录，必须由宿主后端重新鉴权，再按 `businessRecordRefs` 查询；不要把模型输出当作已提交。
6. 组件卸载时 `destroy()`，避免重复挂载与残留观察。

## 验证

示例本身不运行；如接入真实宿主，按 `../../host-checklist.md` 的 E 节逐项验证，并在 P 窗口做真实浏览器/跨源验证。
