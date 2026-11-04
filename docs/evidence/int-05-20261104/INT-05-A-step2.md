# INT-05 宿主 API 子步（A 席）：open/close/updateContext/on/off/destroy 与体验投影

- 日期：2026-11-04
- 席位：A
- 基线 HEAD：`88e70e76`（refactor(int-05)… 首子步结构拆分）
- 范围：`web/assistant.js/src/{main,runtime,experience,host-events,types}.ts` 的宿主 API；`widget.ts`/`widget.css` 的体验容器与可选浮球；`web/tests/assistant-sdk-split-check.mjs` 扩展；证据更新。
- 未触：`web/shared/*` 共享实现、后端服务、`contracts/`、`webapp/src`（`App.vue` 使用 `shared/Chat.vue`，无必要联动）、`dist/` 未提交。

## 一、原责任 → 新责任

| 原责任 | 新位置 | 说明 |
| --- | --- | --- |
| `init` 校验、元素装配、ball/close/send 监听、destroy | `web/assistant.js/src/main.ts` | 新增 mount 校验、`WeakMap` 重复 init 规则、`AbortController`、事件总线；原两条校验文案不变 |
| 打开/关闭/页上下文与运行编排 | `web/assistant.js/src/runtime.ts` | 新增 `open/close/updateContext`；experience 拉取与回退；四类生命周期事件；send 继续复用共享模块 |
| 体验描述渲染 | `web/assistant.js/src/experience.ts` | `validatePageContext`、`renderExperience`、`renderExperienceFallback`、`promptDraft`、文件字段拦截 |
| sessionId 与事件注册面 | `web/assistant.js/src/host-events.ts` | 保留 URL 读写；新增 `createEventBus`（on/off/emit/clear） |
| 公共类型 | `web/assistant.js/src/types.ts` | PageContext/事件 payload/六方法 handle/Widget 扩展 |
| 体验容器、可选浮球、关闭句柄 | `web/assistant.js/src/widget.ts`、`widget.css` | `.experience` 区块；`autoLauncher:false` 不渲染 ball；`mount` 目标 |

`main.ts` 仍是参数校验、装配、监听与 destroy 门面；会话/运行状态机仍在 `runtime.ts` 且继续调用 `shared/session|approval|artifact|run-observer|input-schema|content`。

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `web/assistant.js/src/main.ts` | 76 | init 门面 + 六方法 handle + WeakMap/事件总线/监听清理 |
| `web/assistant.js/src/runtime.ts` | 444 | open/close/updateContext + experience + 事件 + 原运行编排 |
| `web/assistant.js/src/experience.ts` | 238 | pageContext 校验、体验渲染/回退、文件字段 |
| `web/assistant.js/src/host-events.ts` | 72 | sessionId 读写 + 事件总线 |
| `web/assistant.js/src/types.ts` | 72 | 类型扩展 |
| `web/assistant.js/src/widget.ts` | 73 | 体验容器、可选 ball、close 句柄、mount |
| `web/assistant.js/src/widget.css` | 1 | 体验区块样式（单行原文） |
| `web/tests/assistant-sdk-split-check.mjs` | 1044 | Playwright 行为检查 12 项 |
| `docs/evidence/int-05-20261104/assistant-sdk-structure.json` | — | 本轮请求/断言证据 |
| `docs/evidence/int-05-20261104/assistant-sdk-chat.png` | — | 本轮截图（内容未变化） |

## 三、不变量

1. 旧宿主契约不变：`init({endpoint,appCode,ticket,context})` 仍返回可 `destroy()` 的实例；未显式关闭浮球时仍自带右下浮球；两条校验文案仍为 `endpoint 仅允许 http/https`、`必须提供宿主后端出票函数`。
2. Shadow DOM 仍为 `mode:"open"`；`<style>` 仍只注入 shadow root（tokens + widget 两段），`document.head/body` 无新增 style。
3. 对话与恢复链路不变：GET input-schema →（CHAT 且无会话）POST sessions → POST turns/runs → GET events → GET run；恢复仍走 session → `input-schema?versionNo=` → 逐 run 回填。
4. 确认与下载仍调用 `shared/approval.decisionPayload`、`shared/artifact.downloadArtifact`，未复制状态机。
5. 体验仅在首次 `open()` 时拉取一次 `GET /v1/apps/{code}/experience`；404/409（`CONFIG_RELEASE_NOT_ACTIVE`）/5xx/不可解析/网络异常一律回退直接对话，只显示「未获取到该应用的体验描述，可直接提问。」，不伪造能力或推荐问法。
6. 体验内容只用 `textContent` 渲染；欢迎语里出现的 `<script>` 按字面文本显示，不产生脚本/HTML 节点（本步测试断言 shadow 内无该 script 元素）。
7. pageContext 在任何网络调用前本地校验：必须是对象、≤16 字段、键匹配 `^[a-z][a-zA-Z0-9]{0,31}$`、禁九个身份键、值仅 string/number/boolean 且字符串 ≤256；`open({pageContext})` 与 `updateContext()` 同规则；发送时作为 `inputs.context` 上送，未通过校验的值不会发出。
8. 事件为数据面：仅 `open/close/session/destroy` 四类，payload 无函数/令牌；`on` 返回取消函数，`off` 可用；宿主监听抛错被隔离；未知事件名本地报错。
9. `destroy()` 置位、abort 观察、移除 DOM 监听并清空事件表、移除 host；销毁后 `open()` 报「助手已销毁，请重新初始化」；同一显式 mount 重复 `init` 先销毁旧实例再挂新实例（旧实例的 `destroy()` 幂等）。
10. 文件输入：声明 `format:"binary"` 渲染 file 控件，选中文件后发送抛出「需由业务系统上传后以 artifactId 传入；嵌入端不提供上传通道。」，不发请求、不静默丢弃。
11. 三端构建：`vue-tsc --noEmit` exit 0；`assistant.js`、`webapp`、`admin` 均可构建。

## 四、命令与结果

真实环境：Windows、Node 24、Chrome（`C:/Program Files/Google/Chrome/Application/chrome.exe`）；命令均在 `代码/_worktrees/upgrade-20261103/agent-platform/web` 下执行。

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./node_modules/.bin/vue-tsc --noEmit`（新代码落盘后） | exit 0 |
| 2 | `./node_modules/.bin/vite build --config assistant.js/vite.config.ts` | `dist/assistant.js 240.63 kB │ gzip 78.61 kB`，built |
| 3 | `node tests/assistant-sdk-split-check.mjs` | 失败：`offEvents` 期望 `[]` 实得 `[1]`（测试把 `on` 返回的取消函数当成监听器传给 `off`） |
| 4 | 同上（改为独立 listener 再 `off`） | 失败：`getByRole("查询本周订单")` strict violation——能力卡可访问名包含推荐问法文字，命中两个按钮 |
| 5 | 同上（推荐问法用 `exact:true`） | 失败：session 期望 2 条实得 3 条（证据数组跨页面累计，把第 4 项用例的第二应用会话也算进来） |
| 6 | 同上（按本轮起始下标切片） | 失败：文件输入断言读到空 `.error`（发送后的错误异步写入，读取过早） |
| 7 | 同上（等待错误文案出现再读） | `ok: true`，12 项 checks 全 true，58 条请求，`pageErrors: []`，dialog 仅 `确认执行当前参数快照？` |
| 8 | `vue-tsc --noEmit` + `vite build admin/webapp/assistant.js` | exit 0；assistant 240.63 kB，webapp/admin 均 built（仅 chunk >500 kB 警告） |
| 9 | `./node_modules/.bin/vitest run` | 339 passed / 4 failed：`tests/approval.test.ts` 3 例、`tests/run-protocol.test.ts` 1 例；失败位于未改动的 `shared/approval.ts`、`shared/run-protocol.ts`，作为基线事实登记，本步不修改其断言 |

通过轮证据 `assistant-sdk-structure.json`：`mountContractAndShadowIsolation`、`hostApiAndEvents`、`experienceProjectionAndPrompts`、`experienceFallbackAndLongChinese`、`twoApplicationsIsolated`、`pageContextValidation`、`fileInputBlocked`、`errorRecovery`、`chatFlowAndSessionHandoff`、`restoreSessionCompatibility`、`flowRunPath`、`approvalAndArtifactDownload` 均为 true。

## 五、未覆盖

- 未在真实业务页面/iframe 宿主中联调，仍为本地 Playwright 拦截页；experience 端点用夹具，未穿过真实网关/应用服务取数。
- 未覆盖 `autoLauncher:false` 且不传 `mount` 的组合（宿主只持 handle 调用 open），也未覆盖真实移动端/窄屏视觉。
- 未显式覆盖运行中 `destroy()` 的 SSE 中断时序（仅断言 destroy 后 host 移除、open 报错、监听解绑）。
- 网络层断网/超时未单独构造（已覆盖 404/409/5xx 与不可解析响应）。
- 行为脚本仍为独立 Playwright 脚本，未纳入 `vitest`；只跑 Chrome。
- 既有 vitest 4 个失败与本步无关，未修复（避免改他人断言）。

## 六、白名单外改动与契约缺口

白名单外改动（按领取单要求登记）：

1. `web/assistant.js/src/widget.ts`、`widget.css`：本步白名单只列 main/runtime/experience/host-events/types，但 `autoLauncher:false`、显式 `mount`、体验容器必须落在 widget 层，故同步最小改动（不复制业务逻辑，不引样式到业务页）。
2. `docs/evidence/int-05-20261104/assistant-sdk-structure.json` 由脚本重写（证据，非产品文件）。

契约缺口（只登记，未私改 `contracts/`）：

1. **行为前置**：`IntegrationPolicy.allowedPath` 当前没有 `GET /v1/apps/{code}/experience`。经网关的集成票据（embed/BFF 模式）请求体验描述会得到 403 `APP_BINDING_DENIED`，SDK 只能走回退。需要网关后续把该精确路径加入白名单，或明确由宿主 BFF 代取后注入。
2. **schemaVersion 取值**：`contracts/configuration-profile.md` §6 示例与实现不一致（实现返回 `"v1"`，契约兼容矩阵提到新 schemaVersion）。本步接受 `"v1"` 与 `"1.0"`，其他值回退；建议契约统一。
3. **事件面**：本步只实现 `open/close/session/destroy` 四类数据事件注册面；L2 的 host-event 桥（postMessage 等）仍未落地，留待下一子步。
4. **重复 init 规则**：显式 `mount` 采用「旧实例 destroy 后挂新实例」，未 mount 的旧式调用各自独立（与首子步行为一致）；该规则目前只在 SDK 内实现，未写入契约。
5. **pageContext**：SDK 严格按 §5.1 九键黑名单本地拒绝；服务端是否二次校验未见实现，建议在网关/应用侧补同等校验。

## 七、回退

- `git revert <提交号>` 回到 `88e70e76` 的 SDK；无数据库、配置、迁移影响。
- `assistant.js/dist` 由构建重建；证据文件随提交回退。
