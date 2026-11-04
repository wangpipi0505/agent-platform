# 内嵌交付：已交付能力与「待集成」清单

本页把内嵌交付分成两部分：**SDK 已交付**（可直接接线）与**宿主仍待集成**（如实标注，不用示例冒充已完成）。SDK/宿主细节以 `docs/integration/` 为准，本页只给口径。

## 1. 已交付（SDK 1.0.0）

- 固定版本 IIFE 产物：`assistant.js`（全局 `AgentPlatformAssistant`，保留 `AIAssistant.init` 兼容入口）；checksum 见 `docs/integration/versions.md` 与 `checksums.txt`。
- 宿主控制面：`init({endpoint, appCode, ticket, context?, autoLauncher?, mount?})`、`open({pageContext?})`、`close()`、`updateContext()`、`on()/off()`、`destroy()`；旧 `init/destroy` 路径零变化。
- 体验投影：打开时 `GET /v1/apps/{code}/experience`；404/409（`CONFIG_RELEASE_NOT_ACTIVE`）/网络失败时回退直接对话，不伪造默认能力。
- 配置版本提议：客户端可携带 `configReleaseId`，仅作提议；恢复会话以服务端返回的引用为准，不一致时提示。
- 宿主事件（数据事件，七种）：`ready/opened/closed/run-started/approval-required/result/error`；一次权威终态只回传一次 `result`；同页回调与 iframe 精确 `origin/source/nonce/实例 ID`。
- pageContext 校验：≤16 字段、键正则、九类身份键黑名单、值仅标量且 ≤256；非法即本地拒绝，不上送。
- 文件输入：嵌入端只渲染文件控件并给出「由业务系统上传后以 artifactId 传入」的可解释提示，不静默丢弃、不提供上传通道。

示例位置（均已随交付包提交）：

| 场景 | 文件 |
| --- | --- |
| 空白宿主页（按钮→对话→结果→只读反查） | `docs/integration/examples/blank-host.html` |
| 原生 JS 最小接线 | `docs/integration/examples/vanilla-host.html` |
| Vue 3 组件 | `docs/integration/examples/vue-host/` |
| iframe 最小通道 | `docs/integration/examples/iframe-host.html` |
| 可信后端短票 | `docs/integration/examples/bff/ticket-server.mjs` |

## 2. 待集成（不伪装为已完成）

以下项在 INT-05/07 已交付 SDK 能力，但**宿主业务页面的接线示例/联调尚未交付**，统一标注「待集成」：

| 项 | 现状 | 缺少什么 |
| --- | --- | --- |
| 宿主业务按钮集成示例 | `open/close` 与事件已交付；示例页是独立宿主页 | 真实业务页中「打开助手」按钮与业务路由/登录态对接示例 |
| 宿主事件消费 | 七事件与去重已交付 | 宿主侧把 `result` 写入自身业务记录、按 `businessRecordRefs` 反查的示例 |
| CSP / 反向代理 | 清单在 `docs/integration/host-checklist.md` | 宿主实际域名下的 CSP 与同源代理联调记录 |
| iframe 宿主页 | SDK 侧通道与 `webapp` 最小通道（hello→ready/opened）已交付 | 宿主 iframe 容器的真实页面与跨源联调 |
| 受控文件上传 | 嵌入端无上传通道 | 业务系统上传后回传 `artifactId` 的宿主实现 |
| 体验编辑入口 | 体验编辑属专业入口（INT-03/08） | 向导只引用体验，不内嵌编辑器 |

## 3. 接线边界（必须遵守）

- 浏览器不得持有集成 ApiKey、模型密钥、vault 或 Authority 凭据；短票只在可信后端换取。
- 票据不进 URL、日志、localStorage 或可导出配置。
- 服务端下发的 HTML/脚本不执行；事件只传数据，不传函数/DOM/窗口句柄。
- 宿主只能用验证后的 `businessRecordRefs` 做只读反查；消息/模型输出不等于业务已提交。
- `pageContext` 只用于页面上下文，不携带 tenant/角色/密钥，也不改变 Actor。

## 4. 验证口径

- SDK 自检：`node web/tests/assistant-sdk-split-check.mjs`（含宿主事件与配置版本检查）。
- 交付包自检：`node docs/integration/verify-delivery.mjs`（checksum、事件枚举、示例接线、版本一致性）。
- 内嵌真机联调（真实业务页 + CSP + 跨源 iframe）未在本卡执行，登记在 `known-issues.md`；不得以示例页通过替代业务页验收。
