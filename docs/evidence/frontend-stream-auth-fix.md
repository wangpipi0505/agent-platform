# G5 前端恢复鉴权边界修复证据

2026-10-20。唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。根据 `review-frontend-stream.md` 的两个 P2 定向修复；已阅读 AGENTS、implementation-brief、contracts/README、原报告及冻结包。采用 Vue、TDD、receiving-code-review、systematic-debugging、Ponytail、Playwright、verification-before-completion。没有 Git 提交、后端修改、业务服务启停或外部发布；没有操作邻接旧工程、统计/历史组件、主 App 或 tokens。

冻结输入 `.local/frontend-stream-review-package.txt` 的 SHA256 为 `2913B4A0CC31F3EE8B554470DD67B7872999E497E52E904514CD166EF898D8C4`，保留原包。原共享观察器、原观察器测试、原恢复 HTML/浏览器脚本、原计划及原报告 SHA 均保持冻结值。生产修改只有以下四文件，新增两测试文件及本报告。构建生成物和本组测试日志、隔离 Chrome profile 位于本工程原有构建目录及 `.local`。

## 修复行为

- SDK `request` 为不可解析响应体提供带 HTTP status 的提示，HTTP 错误始终抛出 `status`；JSON 错误保留业务 message。403 即使返回 `code: OK` 也不能作为成功。401 空体、403 text/plain、404 HTML 均只读状态一次、只创建一个 run、只开一条流；不会为同一确定错误继续刷新票据。
- SDK `request` 新增第三可选 `signal` 参数，恢复状态读取传入观察器 signal；出票前后、fetch 完成后和 JSON 解析后检查 abort，并把 signal 传给 fetch。
- `createApi` 保留原有 GET/POST/body 用法，增加第四可选 `signal`；请求开始、transport 返回后、JSON 解析后检查 abort。默认 axios transport 传递同一个 signal。Chat 只改恢复 `readStatus` 的 API 调用，贯通该 signal。
- API 和 SSE 各自在发请求前捕获实际携带的 token，清除登录前比较当前 token。请求已 abort 时不会执行鉴权副作用；旧登录的 401 不清除新登录。活动请求的当前 token 收到 401 仍沿原回调清除登录，并立即停止观察。不是仅在 observer 的回调处过滤。
- SSE 同样在 fetch 前/响应后检查 abort；没有修改事件解析、游标或共享观察器。

## 修复前真实行为红证据

所有命令从本根 `web` 执行。改生产源码前直接提取评审报告中的 JavaScript 并在真实 Chrome 执行，退出 0（原脚本只打印复现结果，不包含“修复通过”断言）：

```powershell
$reviewText = Get-Content -Raw ../docs/evidence/review-frontend-stream.md
$probeScript = [regex]::Match($reviewText, '(?s)```javascript\r?\n(.*?)\r?\n```').Groups[1].Value
$probeScript | node --input-type=module
```

`.local/frontend-stream-auth-probes-red.log` 实际输出：

```json
{"probe":"SDK status 403 text/plain","creates":1,"streams":1,"reads":5,"error":"Unexpected token 'o', \"forbidden\" is not valid JSON；可查看权威运行记录。"}
{"probe":"WebApp unmount late status 401","statusCalls":1,"authBefore":"component-fixture-only","authAfter":"","mountedChats":0}
```

之后补测试再修生产代码：

| 命令 | 实际红结果 | 日志 |
| --- | --- | --- |
| `npm test -- tests/stream-auth.test.ts` | exit 1，13 条中 10 失败、3 通过；缺 signal/取消前检查、晚到 401 副作用、旧 token 清新登录等真实断言失败 | `.local/frontend-stream-auth-unit-red.log` |
| `node tests/stream-auth-browser.mjs` | 当时 11 场景中 8 失败、3 通过，exit 1；401空/403文本/404HTML各读5次、出票8次；卸载/stop未取消状态请求且清auth，新登录被旧status/SSE401清空，SDK destroy未取消HTTP | `.local/frontend-stream-auth-browser-red.log` |

红测没有以缺模块、编译错误或完整旧套件通过代替行为失败。SDK probe 仅将既有 750/1500/3000/6000/8000ms 退避计时缩短到零；未更改生产重试次数或分类逻辑。

## 修复后验证

| 命令 | 实际结果 | 日志 |
| --- | --- | --- |
| `npm test -- tests/stream-auth.test.ts` | exit 0，13/13；旧调用兼容、fetch/axios signal、预先abort、晚到200/401、解码中abort、API/SSE旧token免清新登录、活动401仍清auth | `.local/frontend-stream-auth-unit-green.log` |
| `node tests/stream-auth-browser.mjs` | exit 0，最终12/12；包括额外HTTP错误覆盖成功envelope断言及卸载DOM断言 | `.local/frontend-stream-auth-browser-green.log`、`.local/frontend-stream-auth-browser.json` |
| 原 `tests/stream-recovery-browser.mjs` 的原断言，以下命令仅映射输出路径 | exit 0，原5场景通过 | `.local/frontend-stream-auth-recovery-regression.log`、`.local/frontend-stream-auth-recovery-regression.json` |
| `npm test` | exit 0，25 文件、83 测试通过，0失败；原70 + 新13 | `.local/frontend-stream-auth-all-green.log` |
| `npm run build` | exit 0，`vue-tsc --noEmit` 与 admin/WebApp/SDK 三构建、SDK拷贝成功；admin/WebApp原有 >500kB 提示仍存在 | `.local/frontend-stream-auth-build.log` |

真实 Chrome 可执行文件 `C:/Program Files/Google/Chrome/Application/chrome.exe`；新场景隔离 profile `.local/chromium-stream-auth`，恢复回归 `.local/chromium-stream-auth-regression`。仅使用本工程 15175 fixture 及 Playwright 网络 route，登录/票据都是测试假值，没有真实票据持久化或浏览器 API Key。

两个原问题的绿证据及相邻边界：

- SDK 403 text/plain：`creates=1, streams=1, reads=1, tickets=4`，提示 `服务返回不可读响应 (403)`。401空、404HTML同样只读1次、出票4次；票据4次对应输入声明、创建、首流、状态，没有错误重试刷新。JSON 403 提示 `没有访问权限`；HTTP403/`code:OK` 提示 `请求失败 (403)`。
- WebApp 状态挂起后卸载：组件数0，`creates=streams=reads=1, cancels=0`，浏览器 `requestfailed` 记录状态 HTTP 取消1次；放回401后 auth 保持 `component-fixture-only`。显式 stop：取消业务运行1次、状态HTTP取消1次，auth保持。SDK destroy：host数0、状态HTTP取消1次。
- 旧 status/SSE 请求挂起期间切换为 `component-new-login`，放回401后新token均保留，且观察停止；有效活动 status401 清auth，创建/流/状态仍各1次。SSE卸载同样取消HTTP且不清auth。单测还故意使用忽略signal而继续返回401的transport，证明保护实际位于清auth之前，不依赖浏览器总能及时取消。

原恢复回归继续验证两端 EOF → RUNNING(水位8) → CURSOR_EXPIRED(reset提示999) → WAITING_APPROVAL(权威水位12) → 点击确认 → 事实终态 → 权威终态；每端同一run只创建1次、3条流、3次状态读取、游标精确0/8/12、确认1次、cancel0次。旧事实去重、禁发保持到权威终态、最终结果替换临时文本、PASS_WITH_FLAG原提示、SDK每流新短票据均通过。原WebApp流401仍清auth，原卸载/destroy晚到成功快照不重连。

回归命令如下，保留全部原断言及源码，只让截图/JSON/profile写入本组 `.local`，不覆盖原截图与证据：

```powershell
$regressionScript = Get-Content -Raw tests/stream-recovery-browser.mjs
$regressionScript = $regressionScript.Replace("path.resolve(import.meta.dirname, '../..')", "path.resolve('..')").Replace('docs/evidence/frontend-stream-', '.local/frontend-stream-auth-regression-').Replace('.local/frontend-stream-browser.json', '.local/frontend-stream-auth-recovery-regression.json').Replace('.local/chromium-stream-recovery', '.local/chromium-stream-auth-regression')
$regressionScript | node --input-type=module
```

## 供原评审者定向复核的冻结候选

以下为所有四个生产文件和两个新增测试文件的 SHA256。生产源码在完成单测/Chrome绿证据后未继续修改，完整测试与三构建检验的即此源码。

| 文件 | SHA256 |
| --- | --- |
| `web/shared/api.ts` | `85BAF479648EC1E9AE4CD541E2612ECA1418223C04262BFBDEF80ACB140D7C8F` |
| `web/shared/Chat.vue` | `666313CFB21A13F5013204B14800C6F10E19EB31984374BE1F5090F98A254EFC` |
| `web/assistant.js/src/main.ts` | `8776C0048005B83FC2D0CFB9CFA3806172E8DFD3039B4FDC1028018102A2F6A2` |
| `web/debug-panel/events.ts` | `4ED0EA7F6577D0A1D3129B338639A085BA1D9F78BB576D7FB9CB58C5B46FE8F7` |
| `web/tests/stream-auth.test.ts` | `44AF7DD968A1D852B2E9997874DBC23EF2626736721705D4F8B0D7CBFDDF7B8A` |
| `web/tests/stream-auth-browser.mjs` | `94EA5E279878B0651C368FC5E7684674CEF022BBE6397C06F9102AF5E30D24A3` |

本报告提供两个 P2 的修复与验证候选，由原独立评审者定向关闭。证据限消费者本地测试与真实 Chrome 组件 fixture；没有借此声明真实网关、55秒EOF、双 engine进程或Redis跨进程恢复已通过，也不签收全部G5/T15。没有发布。
