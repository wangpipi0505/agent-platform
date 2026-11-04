# INT-07（A 席）：标准结果回传与宿主刷新

- 日期：2026-11-04
- 席位：A
- 基线 HEAD：`f6f6d16a`（开工时）；工作期间其他席位推进至 `e93cf974`（onb-02），本次仅暂存本卡文件
- 范围：`web/shared/assistant-host-events.ts` 及 JSON Schema、`web/assistant.js/src/host-events.ts`、发布页接成交接示例段
- 未触：`contracts/`、后端、`docs/evidence/int-06-*` 既有证据、其他席位未提交文件

## 一、原责任 → 新责任

| 原责任 | 现责任 | 说明 |
| --- | --- | --- |
| SDK 仅以 `open/close/session/destroy` 四类内部事件通知宿主 | 七类标准宿主事件 `ready/opened/closed/run-started/approval-required/result/error` | 信封按契约 §4.1；线上事件名不带 `assistant:` 前缀 |
| 事件负载无 schema 校验 | `validateHostEvent` 严格校验 schemaVersion/枚举/字段/长度/字节上限 | 未知版本、未知事件、未知字段、函数与 DOM 值一律拒绝 |
| 无终态去重 | `createHostEventBridge` 按 `runId` 对 `run-started`/`result` 去重，按 `runId+nodeId` 对 `approval-required` 去重 | 重连、重复 SSE 终态只触发一次 `result` |
| 无 iframe 通道 | `postHostChannelEvent`/`acceptHostChannelMessage` + `web/webapp/src/host-channel.ts` 最小通道 | 精确 origin、一次性 nonce、实例 ID、`event.source` 校验，不使用 `*` |
| 无宿主注册面 | `AssistantOptions.onHostEvent` 与 `AssistantHandle.onHostEvent(listener)` | 同页明确回调；返回取消函数；监听异常隔离，不影响 SDK 状态 |
| 发布页只展示配置版本引用 | 追加七事件说明与 `onHostEvent` 示例 | 示例不含密钥、不含回调地址、不含导航 URL |
| 旧全局 `AIAssistant` | 保持兼容 | 本次不改变既有 `init/destroy` 行为与全局名 |

## 二、改动文件

| 文件 | 说明 | 行数 |
| --- | --- | --- |
| `web/shared/assistant-host-events.ts` | 新增：类型、七事件枚举、校验器、去重桥、iframe 通道校验、hello 解析 | 542 |
| `web/shared/assistant-host-events.schema.json` | 新增：与契约 §4.1 一致的 JSON Schema | 105 |
| `web/assistant.js/src/host-events.ts` | 重写：保留既有会话 URL 交接，新增 SDK 事件包装 | +47/-4 |
| `web/assistant.js/src/types.ts` | 增 `integrationId`/`onHostEvent` 选项与句柄方法 | +8 |
| `web/assistant.js/src/runtime.ts` | 发出七类事件；终态 `result` 携带 status/structured/fileRefs/businessRecordRefs | +119 |
| `web/assistant.js/src/main.ts` | 装配事件桥、`onHostEvent`、`ready`、销毁清理 | +25/-2 |
| `web/webapp/src/host-channel.ts` | 新增：webapp iframe 最小通道（hello → ready/opened） | 67 |
| `web/webapp/src/App.vue` | 挂载/卸载时连接与关闭通道 | +14/-1 |
| `web/admin/src/pages/workspace/AppPublishPanel.vue` | 集成交接示例段：事件说明、`onHostEvent` 片段、业务记录反查提示 | +58 |
| `web/tests/assistant-host-events.test.ts` | 新增：正负例 12 例 | 266 |
| `web/tests/assistant-sdk-split-check.mjs` | 句柄断言更新；新增 `hostResultEventDelivery`；证据目录改 `int-07-20261104` | +51/-2 |

## 三、不变量

1. 事件只能是七种声明值；未知事件被忽略而不是抛错。
2. 每个 `runId` 的 `result` 只投递一次；重复 SSE 块、重连、状态轮询都不重复。
3. 事件只承载数据：函数、DOM 节点、窗口句柄、不可序列化值一律拒绝；`structured` ≤32 键，引用 ≤16 项，整包 ≤8192 字节。
4. `pageContext` 只能经 `updateContext`/`open({pageContext})` 进入；事件不携带 tenant、角色、密钥。
5. 同页桥使用明确回调；iframe 桥要求精确 origin、一次性 nonce、实例 ID 与 `event.source`，从不使用 `*`。
6. 业务效果恒为 `UNKNOWN`：助手消息与模型输出不作为业务已提交证据。
7. `businessRecordRefs` 只交给宿主自己的反查路由；SDK 与服务器都不下发回调函数、宿主 JS、导航 URL。
8. 旧路径零变化：未传 `integrationId`/`onHostEvent` 时，既有 `init/destroy`、会话、恢复、确认、下载行为不变。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./node_modules/.bin/vue-tsc --noEmit` | 失败：`error` 事件字段应嵌套在 `error` 对象下（契约条件形状） |
| 2 | `./node_modules/.bin/vue-tsc --noEmit` | exit 0 |
| 3 | `./node_modules/.bin/vitest run tests/assistant-host-events.test.ts` | 12/12 |
| 4 | `./node_modules/.bin/vitest run tests/publish-gate.test.ts` | 21/21 |
| 5 | `node tests/assistant-sdk-split-check.mjs` | 失败：既有 chat 检查 `send.disabled` 竞态（终态片段先渲染，composer 尚未重新启用） |
| 6 | 同上（断言前等待 composer 重新启用） | `ok:true`，17 项 checks 全 true，91 条请求，`pageErrors:[]`，dialog 1 次 |
| 7 | `vite build --config assistant.js/vite.config.ts` | `dist/assistant.js 251.96 kB`，gzip 81.90 kB |
| 8 | `vite build --config webapp/vite.config.ts` / `admin/vite.config.ts` | 均 built（仅 chunk >500 kB 警告） |

真实 PG、后端均未涉及；本卡为前端事件契约与通道。

## 五、未覆盖

1. 真实宿主页的 iframe 集成（需宿主侧真正挂载接收器）；本卡只有 postMessage 级单测与 webapp 最小通道。
2. 真实浏览器跨源场景（测试为同源 `http://localhost` 与纯逻辑 postMessage 模拟）。
3. Safari/Firefox 兼容性；本轮只在 Chrome 验证。
4. `businessRecordRefs` 的业务反查行为属宿主责任，未在仓库内实现。

## 六、白名单外改动与契约缺口

- 白名单外必要集成：`types.ts`、`runtime.ts`、`main.ts`、`webapp/src/host-channel.ts`、`webapp/src/App.vue`、`web/tests/*`。`web/shared/assistant-host-events.ts` 与 schema 为白名单允许的新增。
- 契约缺口（只登记，未私改）：
  1. §4 只规定 `integrationId` 的字符集与长度，未定义其申请/注册来源；本卡默认回退 `appCode`。
  2. §7 提到的 iframe 通道未定义 `hello` 帧格式；本卡使用 `{type:"agent-platform:assistant:hello",nonce,instanceId}` 包装并登记。
  3. §4 未定义 `structured.businessEffect` 的枚举；本卡固定为 `UNKNOWN`，待契约补登。
  4. 发布页示例为静态说明，未生成可复制的最小宿主代码包。
  5. 事件未包含 `integrationProfileRef`；如需宿主审计接入档案修订，需要契约扩展。

## 七、回退

- `git revert <本次提交>`；无数据库迁移、无服务端变更、无持久化数据影响。
- 回退后旧 SDK 的 `init/destroy`、四类内部事件与既有自检脚本恢复原状。
