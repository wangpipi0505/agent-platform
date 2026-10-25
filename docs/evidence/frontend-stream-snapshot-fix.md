# G5 非终态快照误终结修复

2026-10-20，唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`。依 AGENTS、详设 v1.2、implementation-brief、contracts/README 及三份原前端流报告执行，采用 systematic-debugging、TDD、Vue、Ponytail、Playwright、verification-before-completion 和请求独立评审流程。生产修改仅 `web/shared/Chat.vue` 与 `web/assistant.js/src/main.ts`；新增本组 Chrome 测试和证据。没有改 API、events、observer、管理页或后端，没有 Maven、服务启停、Git 提交或外部发布。没有读取或关联相邻旧工程代码。

## 根因与修复

已亲看 live agent 提供的 `frontend-stream-live-webapp-recovery.png` 和 `frontend-stream-live-sdk-recovery.png`：运行仍 busy，回答却显示 `{}`。两消费者原 `onStatus` 不区分状态，对 RUNNING 的 PG 默认 `output:{}` 调用 `applyFinal`，同时置 `receivedFinal=true`。这既覆盖既有片段，又阻断恢复后的 CHUNK；最终权威输出可能覆盖回来，因此只检查最终答案会漏报。

修复复用现有 `events.terminal` 状态判断，不按内容是否为空判断终态。RUNNING、WAITING_APPROVAL 等非终态不修改答案或 `receivedFinal`。明确终态始终应用权威快照，保留合法 `{}`、空字符串、空 `text`，null/缺输出也清掉旧临时答案；FAILED/CANCELLED 优先使用权威 error，缺 error 时回退 output，再回退失败/取消提示。原 `RUN_FINISHED` 事实答案仍可临时展示，busy 仍持续到权威终态；快照覆盖会清除旧 invalid 答案和 warning，PASS_WITH_FLAG 的最终 warning 保留。

恢复中和恢复后都明确提示临时片段可能缺失，最终以完整结果为准。没有声称 chunkReplay=false 时丢失 token 能重放。没有改动原 abort、auth、票据、确认、取消或游标逻辑，也没有新增依赖或纯 helper。

## 实际红绿证据

新增 `web/tests/stream-snapshot-browser.mjs` 使用已有 15175 `stream-recovery.html`，真实加载 Chat SFC / SDK；Playwright route 只替代本测试的 `/v1` 响应。Chrome 为 `C:/Program Files/Google/Chrome/Application/chrome.exe`，隔离 profile `.local/chromium-stream-snapshot`。这不是实际网关或模型纵切。

每端 8 个终态变体，共 16 场景：首流片段 → RUNNING/output:{} → 第二流未来片段 → WAITING_APPROVAL/非空 output → 第三流未来片段 → RUNNING/output:{} → RUN_FINISHED 临时 invalid 答案和提示 → 权威终态。每场断言两次非终态均不覆盖、未来片段继续展示、恢复缺失提示、终态前禁发、最终内容及 warning 替换、单次创建、4 流/4 状态、游标精确 0/4/8/8。终态变体为结构化对象、空对象、空 text、空字符串、null、PASS_WITH_FLAG、FAILED/error+默认空 output、CANCELLED/error。

| 命令（本根 web） | 实际结果 | 日志/JSON |
| --- | --- | --- |
| `node tests/stream-snapshot-browser.mjs`，修改生产代码前 | exit 1；16 场景执行完成，74 条行为断言失败 | `.local/frontend-stream-snapshot-browser-red.log`、`-red.json` |
| 同命令，修复后 | exit 0；16 场景、0 失败断言 | `.local/frontend-stream-snapshot-browser-green.log`、`.local/frontend-stream-snapshot-browser.json` |
| `npm test` | exit 0；25 文件、83 测试全部通过 | `.local/frontend-stream-snapshot-all-green.log` |
| `npm run build` | exit 0；vue-tsc、admin/WebApp/SDK 三构建和 SDK 拷贝成功；保留原 admin/WebApp >500kB 提示 | `.local/frontend-stream-snapshot-build.log` |
| 原恢复脚本，仅映射输出路径 | exit 0；原 5 场景全部通过 | `.local/frontend-stream-snapshot-recovery-regression.log`、同名 `.json` |
| 原鉴权脚本，仅映射输出路径，最终单独执行 | exit 0；原 12 场景全部通过 | `.local/frontend-stream-snapshot-auth-regression.log`、同名 `.json` |

测试编写期间曾发现结构化 JSON 的浏览器排版空白差异，正式行为红测前已把最终输出比较统一为规范空白。首次修复后试跑又发现 SDK `<p>` 布局带尾随空行，于是仅给 3 个片段内容比较加 `trim()`；原红测的 `{}` / 非终态文本替换在 trim 后仍然不同。没有削弱状态、禁发、片段内容或终态清理断言，最终全 16 场景重新执行通过。鉴权脚本首次与构建及其他 Chrome 并行时，前 2 场仅 `page.goto` 超过原 5000ms，后 10 场通过；原样保留为 `.local/frontend-stream-snapshot-auth-first.log/.json`。待其他任务结束后未改原 timeout 和断言，单独重跑 12/12 通过。

恢复和鉴权回归通过 PowerShell 将原脚本正文传给 `node --input-type=module`，仅替换 `path.resolve(import.meta.dirname, '../..')` 为 `path.resolve('..')`，Chrome profile 和 JSON/截图输出换成本组 `.local/frontend-stream-snapshot-*`，没有改原测试文件或覆盖原证据。恢复保留原确认、票据刷新、401 清 auth、卸载/destroy 场景；鉴权保留原 12 个非 JSON HTTP、abort、旧/新 token 边界。

## 截图与冻结评审输入

已用图片工具亲自查看本轮 `frontend-stream-snapshot-webapp-recovered.png` 和 `frontend-stream-snapshot-sdk-recovered.png`。两图均在 WAITING_APPROVAL 之后、第三流等待放行期间截取；已显示首流+恢复未来片段、明确缺失提示，并仍然禁发。WebApp 确认区展示当前无待办，属于本 controlled fixture 的空列表返回。

源码和新测试冻结后交主协调者独立评审。工程无 HEAD，以 `.local/frontend-stream-snapshot-review-package.json` 的全文与 SHA256 作为输入；修复前两消费者全文另存 `.local/frontend-stream-snapshot-before-Chat.vue` 和 `-before-sdk.ts`，只供差异复核。包中同时记录 API/events/observer、原恢复/鉴权测试和 HTML 的 SHA，核对它们未被本次修改。

| 文件 | 冻结 SHA256 |
| --- | --- |
| `web/shared/Chat.vue` | `FACA2CA174BC577AB20FD82833F2A1E11FAB0E9CC753B4369E8797C36B377A42` |
| `web/assistant.js/src/main.ts` | `1E0CEE4A93B8DB268716480F8B417839A7C40814E836553AFAA113B21A0B9448` |
| `web/tests/stream-snapshot-browser.mjs` | `6F6454001B42CB7F2D8F925BE53F75934845C4F11ADA16EC6A1E8CC5624EA17F` |

本任务未亲跑 65 秒实际模型 HTTP 联调，也未验证修复后真实 55 秒 EOF 链路；原真实恢复图片来自 live agent，本轮只查看并据此复现。实际长任务、双 engine 和 Redis 跨进程验收仍由主协调者及 live agent 执行，不以本组 16 个 controlled 场景替代。
