# 版本、依赖与可复核 checksum

本页固定“SDK 版本 + 依赖版本 + 配置 Schema”组合；任何一项变化都应生成新的交付包版本，而不是原地覆盖。

## 组合版本

| 组成 | 固定值 | 说明 |
| --- | --- | --- |
| 交付包 | `integration-kit@1.0.0` | 本目录（INT-09） |
| SDK | `@platform/assistant.js@1.0.0` | IIFE，全局 `AgentPlatformAssistant`，兼容 `AIAssistant` |
| 构建提交 | `a3597509` | 产物由该提交（含 INT-07/08/09 与 MNT-03 类型收窄）构建 |
| Node（构建） | `v24.16.0` | 仅影响构建环境，不随产物分发 |
| vite | `6.4.4` | 构建器 |
| TypeScript | `5.8.3` | 类型检查 |
| marked | `15.0.12` | 打包进 SDK，Markdown 渲染（输入已净化） |
| DOMPurify | `3.4.17` | 打包进 SDK，HTML 净化 |
| 宿主事件 Schema | `urn:agent-platform:assistant-host-event:1.0`（`schemaVersion: "1.0"`） | `web/shared/assistant-host-events.schema.json` |
| pageContext Schema | `urn:agent-platform:assistant-page-context:1.0` | `contracts/assistant-integration.md` §5.1 |
| 体验配置 | configuration-profile §6 体验 schema v1（`schemaVersion: "v1"`） | 服务端投影 |
| 三路线分级声明 | `three-route-tiering.md`（2026-11-04，INT-10） | 声明/交接口径；真实场景与验收 BLOCKED，不代表已落地 |
| 契约 | `assistant-integration.md` v0.1 冻结草案 | sha256 见下 |

## 构建产物

| 文件 | 字节 | gzip | sha256 |
| --- | --- | --- | --- |
| `web/assistant.js/dist/assistant.js` | `252936` | `81736` | `ae33f0eab09719c2a2ae575a1ddd0a7d5a2b158149a917819da3703103f7bf7d` |

构建命令（在 `web/` 下，锁文件已就位）：

```bash
./node_modules/.bin/vite build --config assistant.js/vite.config.ts
```

产物 `dist/` 被 `.gitignore` 忽略，因此**不要**把 dist 当作源码事实；以本页 checksum 与 `checksums.txt` 为准，重建后必须一致。

## 配置/契约文件摘要

| 文件 | sha256 |
| --- | --- |
| `web/shared/assistant-host-events.schema.json` | `63df3cde8a9538d89ecebca94b2d52fbf0ef0d2e852344565a5af3b2c446a049` |
| `contracts/assistant-integration.md` | `d03a6839923edd253f9fb3485457789fb02adc7abdc6760fd3b674f73301fff5` |
| `contracts/configuration-profile.md` | `a5ff667b00eeb029744e767663341a18adf503c114099bdabef3ad3e29b8e2db` |

契约摘要会随契约评审变化；变化时本交付包需重新生成 checksum 并在 `upgrade-rollback.md` 记录影响。

## 公开 API（1.0.0）

```ts
init(options: {
  endpoint: string;               // 平台网关基址（http/https）
  appCode: string;
  ticket: () => Promise<string>;  // 宿主后端短票；只在内存
  integrationId?: string;         // 事件归属，默认 appCode
  context?: PageContext;          // 仅标量、≤16 键、≤256 字符
  autoLauncher?: boolean;         // false 时不挂浮球
  mount?: HTMLElement;            // 显式挂载点
  onHostEvent?: (event: HostEventEnvelope) => void;
}): AssistantHandle

interface AssistantHandle {
  open(options?: { pageContext?: PageContext }): void;
  close(): void;
  updateContext(context: PageContext): void;
  on(event: "open" | "close" | "session" | "destroy", listener: (payload: unknown) => void): () => void;
  off(event: string, listener: (payload: unknown) => void): void;
  onHostEvent(listener: (event: HostEventEnvelope) => void): () => void;
  destroy(): void;
}
```

兼容矩阵与旧版行为见 `contracts/assistant-integration.md` §7；旧调用 `init({endpoint,appCode,ticket,context})` 与 `destroy()` 保持原语义。

## 校验命令

```bash
# 1) 重建并核对产物
cd web && ./node_modules/.bin/vite build --config assistant.js/vite.config.ts && cd ..
sha256sum web/assistant.js/dist/assistant.js   # 应为 ae33f0…7bf7d

# 2) 一键核对交付包（checksum + 示例/Schema 对齐）
node docs/integration/verify-delivery.mjs
```
