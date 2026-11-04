# INT-05 首子步（A 席）：SDK 结构拆分——只拆不增，先证明兼容

- 日期：2026-11-04
- 席位：A
- 基线 HEAD：`cda37732`（feat(int-04)…）
- 范围：`web/assistant.js/src/main.ts`（407 行）结构拆分；新增同目录 widget/runtime/experience/host-events/types/widget.css；SDK 行为测试；`vue-tsc` 与两个构建。
- 未触：`web/shared/*` 共享实现、后端、既有 vitest 用例、UI-07 文件；未新增宿主人机接口。

## 一、原责任 → 新责任

| 原责任（main.ts） | 新位置 | 说明 |
| --- | --- | --- |
| 内联 `<style>` 全部规则 | `web/assistant.js/src/widget.css` | 原文迁移，无选择器/令牌改动 |
| 自定义元素注册、shadow 装配、markup、tokens 注入、connection 节点 | `web/assistant.js/src/widget.ts` | `mountWidget()` 返回 `Widget` |
| 历史恢复、发送、request helper、审批卡片、`observeRun` 接线、附件下载 | `web/assistant.js/src/runtime.ts` | `createRuntime()` 闭包，状态机未复制 |
| 声明类型校验、`inputFields` 渲染、额外输入收集 | `web/assistant.js/src/experience.ts` | 复用 `shared/input-schema` |
| `sessionId` 读取与 URL 发布 | `web/assistant.js/src/host-events.ts` | 仅 `location.search`/`history.replaceState` |
| options/handle/widget 类型 | `web/assistant.js/src/types.ts` | 无行为 |

`main.ts` 现仅做参数校验、`mountWidget`+`createRuntime` 装配、ball/close/send 生命周期门面与 `destroy` 转发。

## 二、改动文件

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `web/assistant.js/src/main.ts` | 40 | 重写为门面，导出 `init` 与类型 |
| `web/assistant.js/src/widget.ts` | 58 | 元素注册/shadow/markup/tokens |
| `web/assistant.js/src/widget.css` | 1 | 原内联 CSS |
| `web/assistant.js/src/runtime.ts` | 356 | 会话恢复与运行观察 |
| `web/assistant.js/src/experience.ts` | 81 | 声明输入渲染与收集 |
| `web/assistant.js/src/host-events.ts` | 14 | sessionId 读写 |
| `web/assistant.js/src/types.ts` | 30 | 公共类型 |
| `web/tests/assistant-sdk-split-check.mjs` | 431 | SDK 行为测试（Playwright） |
| `docs/evidence/int-05-20261104/assistant-sdk-structure.json` | — | 请求/断言证据 |
| `docs/evidence/int-05-20261104/assistant-sdk-chat.png` | — | 运行截图 |

## 三、不变量

1. `init({endpoint,appCode,ticket,context})` 与返回 `{destroy}` 不变；两条校验文案不变（`endpoint 仅允许 http/https`、`必须提供宿主后端出票函数`）；未新增 open/close 等宿主 API。
2. Shadow DOM 仍为 `mode:"open"`；`<style>` 只注入 shadow root（tokens + widget 两段），`document.head/body` 无新增 style。
3. 对话链路不变：GET input-schema → POST sessions（仅 CHAT 且无 sessionId）→ POST turns/runs → GET events → GET run；请求体仍为 `{inputs:{message,context,…},requestId}`。
4. 恢复链路不变：`?sessionId=` → GET session → GET input-schema?versionNo= → 逐 run 回填历史。
5. 确认/下载仍调用 `shared/approval.decisionPayload` 与 `shared/artifact.downloadArtifact`，未复制状态机；决策体 `{approved,requestId,snapshotDigest}`。
6. `observeRun` 重连、WAITING_APPROVAL、CHUNK 渲染、terminal 收尾、失败文案与 finally 清理保持原行为。
7. 构建：`webapp` 与 `assistant.js` 均可构建，产物 `assistant.js/dist/assistant.js`。

## 四、命令与结果

真实环境：Windows、Node 24、Chrome（`C:/Program Files/Google/Chrome/Application/chrome.exe`）；命令均在 `代码/_worktrees/upgrade-20261103/agent-platform/web` 下执行。

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./node_modules/.bin/vue-tsc --noEmit` | exit 0 |
| 2 | `./node_modules/.bin/vite build --config assistant.js/vite.config.ts` | `dist/assistant.js 234.42 kB │ gzip 76.70 kB`，built |
| 3 | `./node_modules/.bin/vite build --config webapp/vite.config.ts` | built（仅 chunk >500 kB 警告） |
| 4 | `node tests/assistant-sdk-split-check.mjs` | 失败：Playwright 找不到 `chromium_headless_shell-1248`（`Executable doesn't exist`） |
| 5 | 同上（改用 Chrome `executablePath`） | 失败：`crypto.randomUUID is not a function；可查看权威运行记录。`（`http://sdk.local` 非安全上下文） |
| 6 | 同上（改 `http://localhost`） | 失败：`参数快照缺少摘要，请刷新确认卡片`（夹具把 `snapshotDigest` 放在 `parameterSnapshot` 内；`decisionPayload` 只读行顶层） |
| 7 | 同上（夹具改行顶层） | `checks` 5 项全 true，20 条请求，`pageErrors: []`，`ok: true` |

通过轮证据：`assistant-sdk-structure.json` 记录 `mountContractAndShadowIsolation`、`chatFlowAndSessionHandoff`、`restoreSessionCompatibility`、`flowRunPath`、`approvalAndArtifactDownload` 均为 true；请求序列含 turns `r_1`、`r_2`、decision `ni_1`、artifact `a1/download`，Authorization 均为 `Bearer tok`。

## 五、未覆盖

- 未在真实业务页面/iframe 宿主中验证，仅本地 Playwright 拦截页；未与 UI-07 联调（UI-07 未开工）。
- 行为测试是独立 Playwright 脚本，未纳入 `npm test`（vitest）流水线；`dist/` 未提交，由构建生成。
- 只跑了 Chrome，未跑 Firefox/WebKit；未做触屏/窄屏视觉回归。
- `host-events.ts` 目前只覆盖 sessionId 读写，postMessage/host-event 桥留待下一子步。

## 六、契约缺口

- 本步按指令不新增 open/close 等宿主 API；L2 的 host-event 协议尚未在 SDK 落地，仅保留独立模块与注释。
- 除上述外未发现契约缺口；未私改 `contracts/`。

## 七、回退

- `git revert <提交号>` 即可回到拆分前单文件实现；无数据库/配置/迁移影响。
- `assistant.js/dist` 由构建重建，不需要回滚数据。
