# 平台标准接入包（SDK 1.0.0）

本目录是业务软件接入智能助手平台的**交付包**：固定 SDK 版本、依赖版本、配置 Schema 与宿主接线示例的组合。它面向宿主研发/运维，不替代 `contracts/` 中的契约正文。

## 交付物清单

| 文件 | 用途 |
| --- | --- |
| `l0-l3.md` | L0～L3 接入分级、选择方法、宿主前提与最小步骤 |
| `host-checklist.md` | 「需要宿主提供什么」自检页 + origin/CSP/反向代理清单 |
| `examples/blank-host.html` | 空白宿主页：按钮 → 对话 → 只读调用 → 结果回传的完整接线示范 |
| `examples/vanilla-host.html` | 原生 JS 最小接线（仅 init/open + 事件日志） |
| `examples/vue-host/` | Vue 3 组件接入示例（`App.vue` + `main.ts`） |
| `examples/iframe-host.html` | iframe 最小通道（精确 origin/source/nonce/实例） |
| `examples/bff/ticket-server.mjs` | 可信后端短票示例（密钥只在服务端） |
| `templates/experience-template.json` | 能力说明/推荐问法/提示词引用的配置模板 |
| `templates/prompt-template.md` | 提示词变量与 `promptRefs` 配置模板 |
| `versions.md` | SDK/依赖/配置 Schema/契约版本与可复核 checksum |
| `error-codes.md` | 故障码表（从 `contracts/assistant-integration.md` §6 派生） |
| `upgrade-rollback.md` | 升级、回退与撤销语义 |
| `checksums.txt` | 构建产物 checksum 与校验命令 |
| `verify-delivery.mjs` | 交付包自检：checksum、示例与 Schema/契约枚举对齐 |

业务接入（ONB）的**总入口口径**见 [`../onboarding/README.md`](../onboarding/README.md)：独立入口全路径、内嵌交付「待集成」清单与已知问题登记；本目录仍是 SDK/宿主接线的技术交付包。

## 版本与状态

- SDK：`@platform/assistant.js` **1.0.0**（IIFE，全局 `AgentPlatformAssistant`，保留 `AIAssistant` 兼容入口）。
- 宿主事件 Schema：`urn:agent-platform:assistant-host-event:1.0`（`schemaVersion: "1.0"`）。
- pageContext Schema：`urn:agent-platform:assistant-page-context:1.0`。
- 体验配置：configuration-profile §6 体验 schema v1（`schemaVersion: "v1"`）。
- 契约状态：`contracts/assistant-integration.md` 为 v0.1 **冻结草案**；本交付包不改变其激活状态。未激活的能力不因本包存在而可宣称已启用。

## 快速开始（L2）

1. 宿主后端提供同源短票接口（示例：`examples/bff/ticket-server.mjs`），浏览器拿不到任何长期密钥。
2. 页面引入固定版本 SDK：

   ```html
   <script src="/assistant/assistant.js"></script>
   ```

3. 初始化并绑定宿主按钮：

   ```js
   const host = window.AgentPlatformAssistant ?? window.AIAssistant;
   const handle = host.init({
     endpoint: "https://assistant.example.com",
     appCode: "your_app",
     integrationId: "your_integration",
     autoLauncher: false,           // 由宿主按钮打开
     ticket: async () => {
       const r = await fetch("/assistant/api/ticket", { method: "POST", credentials: "same-origin" });
       const data = await r.json();
       if (!r.ok || data.code !== "OK") throw new Error(data.message || "取票失败");
       return data.data.ticket;
     },
     onHostEvent: (event) => {
       if (event.event === "result" && event.businessRecordRefs?.length) {
         // 只使用经过 SDK 校验的引用；是否可读仍由宿主后端重新鉴权
       }
     },
   });
   document.querySelector("#open").addEventListener("click", () => handle.open());
   ```

4. 按 `host-checklist.md` 逐项完成 origin、CSP、代理与身份对接，再进入 L3（如需原生身份适配）。

## 校验

在仓库根目录执行（Web 依赖已安装时）：

```bash
cd web && ./node_modules/.bin/vite build --config assistant.js/vite.config.ts
cd .. && node docs/integration/verify-delivery.mjs
```

`verify-delivery.mjs` 会重新计算 `web/assistant.js/dist/assistant.js` 的 sha256，核对 `checksums.txt`；并把示例中出现的 API/事件名与 `web/shared/assistant-host-events.ts` 的枚举、`contracts/assistant-integration.md` §4.1/§5.1 的字段对齐。任何不一致都会以非零码退出。

## 边界

- 示例只演示接线，**不是运行验证**；真实浏览器/iframe/跨源验证留在 P 窗口。
- 示例中不出现 API Key、集成密钥、模型密钥、vault 凭据、Authority 凭据，也不把票据写入 URL/localStorage。
- 服务端不下发回调函数、宿主 JS 或导航 URL；事件只携带数据。
- 诊断与降级：缺少可信身份时返回可解释阻塞（`INTEGRATION_IDENTITY_PROVIDER_UNAVAILABLE`），不得降级 dev/共享账号。

相关文档：`deploy/integration/README.md`（部署拓扑、nginx 同源代理、Authority 身份档案、BFF 伪代码与回退）、`contracts/assistant-integration.md`、`contracts/configuration-profile.md`。
