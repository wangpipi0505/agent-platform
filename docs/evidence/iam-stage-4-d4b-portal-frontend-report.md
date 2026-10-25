# D4b 管理门户身份接入与会话隔离实施报告

状态：实现已冻结，交回独立 review/root 有限核验；不替代其签收。无 commit（本工程无 HEAD），无 git add -A、部署、服务重启、后端测试或原127例整套运行。

## 范围和最终行为

唯一 shell cwd：`G:/207/项目/算法重组工具/代码/agent-platform`。遵守 AGENTS、implementation-brief 与 contracts/README；采用 Vue3.5/TS/Pinia/Element Plus，未升级/增加框架、依赖或改 lockfile。应用 using-superpowers 的 subagent 例外、implementer contract、vue-skilld（只用稳定3.5 API）、TDD、ponytail、Playwright 与 verification-before-completion；没有另派子代理。

- 默认 `VITE_AUTH_MODE` 未设/空/portal 为 portal；只显式 dev 使用原开发签票入口，再验证 GET /auth/session。未知非空值显示配置错误。缺宿主provider显示“请从门户打开工作台”，没有dev按钮/回退。
- Provider只有同源宿主显式注入window.agentPlatformPortal两方法，保留this；main挂载前取得provider，连接Promise不会阻塞入口挂载。宿主变更、手动重连用同一流程；连接中也允许手动重连来淘汰挂起provider。token仅内存，不读取URL/storage/postMessage/JWT claims身份。
- session响应须精确tenantId/principalId/roles/expiresAt四字段，非空身份与角色字符串、未来有效ISO时间；空roles可登录而不赋ADMIN。先token候选+完整session校验，再同步安装。角色只用于UI可见性，后端仍每次独立授权。
- auth统一generation/snapshot/invalidate/install与会话AbortController，401/到期/退出先递增generation、清token/user/roles、abort旧工作，不立即自动登录。同token重连也是新generation。过期timer按真实截止每最多60s分段检查，避免超长timeout溢出/时钟回退问题。
- PortalShell以generation key卸载整个App工作区，立即隐藏旧业务/选择/草稿。App捕获scope约束导航、failure、消息、后续网络、allSettled整批应用、chart动态import/observer与timer；卸载实例的纯本地refs不做无意义逐字段reset。EvaluationPanel由key卸载，因此未改其watch。
- createApi原调用签名兼容，第四可选scope；useApi在组件创建时捕获generation与组件生命周期signal，发送前/响应后/body后检查，旧闭包不能获取新generation凭据继续POST。标准AbortSignal.any合并会话/调用者signal。错误保留code/status/traceId；403显示无权，IAM503显示授权暂不可用。
- SSE每次read后/每帧emit前后检查，授权reset不释放后续buffer事实，finally取消和释放reader，pending reader在abort也可结束。三个AUTHORITY_* reset、HTTP401/403和具名IAM_AUTHORITY_UNAVAILABLE/PORTAL_SESSION_UNAVAILABLE503停止自动观察；普通DEPENDENCY_UNAVAILABLE503和TypeError仍原瞬态恢复。CURSOR_EXPIRED/CURSOR_AHEAD/EVENT_LIMIT与最终快照/事实游标语义保留。
- RunDetail与DebugPanel各自有blocked/manual-reconnect和timer取消，包含business frame触发status的异步错误catch。断连/换身份/到期不cancel后端run；仅新的显式用户取消仍发cancel。
- DebugPanel原sessionStorage debug:appCode运行选择恢复被移除，避免generation key重挂载恢复旧身份run。生产调用仅admin/src/App.vue；web/tests/named-inputs.html是组件fixture。assistant.js不导入DebugPanel，只用events/observer/artifact，因此SDK ticket五参下载/受理/drain合同未改变；dev管理工作台刷新后的旧调试run自动选择也一并取消。纯导航和density偏好保留。
- artifact helper保持原五参兼容，第六可选下载scope，HTTP/blob后和创建URL/click前检查归属，finally释放ObjectURL；Artifacts传组件+会话scope，旧错误不会显示给新身份。

## 显式开发方式与适用限制

PowerShell新终端中：`$env:VITE_AUTH_MODE='dev'`，再从上述root执行`npm --prefix web run dev`；只用于显式隔离开发环境。本任务没有改已有主进程或全局环境，也没有实际重启这条开发命令。Vite环境值也会在build时嵌入：若人为dev构建，该产物仍会选择开发签票，所以只能用于隔离开发，不能当生产配置。此次正式构建未设置该值，默认portal。

显式dev浏览器用例复用Vite对真实main.ts的转译，仅测试服务器将`import.meta.env.VITE_AUTH_MODE`表达式替换为字面dev；它证明代码分支，不冒称执行了env启动命令。没有真实IAM；HTTP与宿主均隔离受控，所有token/tenant是测试值。不得把这些前端证据与D4a既有真实签名证据拼成未执行的生产全链路。

## 实际命令、阶段与结果

下列每条命令cwd均为工程root；各阶段command.json记录完整source inventory SHA及仅变动overlay，output.raw/result.json保留实际输出/exit。省略的中断launcher无raw/result则明确标注，不补造。stage-ledger.json是机器可读索引。没有把多轮结果合成一次全绿。

|阶段|实际命令|exit|用例/适用|
|---|---|---:|---|
|red-portal|`node web/node_modules/vitest/vitest.mjs run web/tests/portal-auth.test.ts`|1|中断，无行为结论|
|red-boundaries|`node web/node_modules/vitest/vitest.mjs run --root web tests/stream-auth.test.ts tests/run-observer.test.ts -t D4b`|1|10 failed | 26 skipped (36)|
|red-portal-root|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts`|1|no tests|
|red-portal-behavior|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts`|1|15 failed (15)|
|green-core|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts tests/stream-auth.test.ts tests/run-observer.test.ts tests/login.test.ts tests/stream.test.ts tests/expiry.test.ts tests/permission.test.ts`|0|57 passed (57)|
|red-download-login|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts tests/login.test.ts -t D4b`|1|2 failed | 16 skipped (18)|
|green-download-login|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts tests/login.test.ts -t D4b`|0|2 passed | 16 skipped (18)|
|browser-1|`node web/tests/portal-browser.mjs browser-1`|1|0/8具名断言；pageErrors=0|
|browser-2|`node web/tests/portal-browser.mjs browser-2`|1|5/6具名断言；pageErrors=1|
|browser-3|`node web/tests/portal-browser.mjs browser-3 explicit\|real axios\|late\|RunDetail\|DebugPanel\|logout`|1|0/1具名断言；pageErrors=1|
|browser-4|`node web/tests/portal-browser.mjs browser-4 explicit\|real axios\|late\|RunDetail\|DebugPanel\|logout`|1|0/1具名断言；pageErrors=0|
|browser-5|`node web/tests/portal-browser.mjs browser-5 explicit\|real axios\|late\|RunDetail\|DebugPanel\|logout`|1|14/15具名断言；pageErrors=4|
|browser-6|`node web/tests/portal-browser.mjs browser-6 DebugPanel\|logout`|0|5/5具名断言；pageErrors=0|
|browser-7|`node web/tests/portal-browser.mjs browser-7 late chart\|empty roles\|HTTP 401\|HTTP 403\|ordinary dependency`|0|8/8具名断言；pageErrors=0|
|red-debug-event-status|`node web/tests/portal-browser.mjs red-debug-event-status DebugPanel status authorization`|1|1/1具名断言；pageErrors=1|
|green-event-status|`node web/tests/portal-browser.mjs green-event-status status authorization`|0|2/2具名断言；pageErrors=0|
|red-authorization-message|`node web/node_modules/vitest/vitest.mjs run --root web tests/stream-auth.test.ts -t D4b names authorization`|1|1 failed | 20 skipped (21)|
|red-manual-pending|`node web/tests/portal-browser.mjs red-manual-pending manual reconnect supersedes`|1|0/1具名断言；pageErrors=0|
|green-final-affected|`node web/node_modules/vitest/vitest.mjs run --root web tests/stream-auth.test.ts tests/login.test.ts tests/stream.test.ts tests/expiry.test.ts -t D4b names authorization\|D4b aborts a pending\|D4b stops after reader\|clears the current API\|API refuses application\|login explains\|D4b late development\|expires viewer`|0|8 passed | 19 skipped (27)|
|green-manual-pending|`node web/tests/portal-browser.mjs green-manual-pending manual reconnect supersedes`|0|1/1具名断言；pageErrors=0|
|final-typecheck|`node web/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p web/tsconfig.json`|0|完整vue-tsc|
|final-build-admin|`node web/node_modules/vite/bin/vite.js build --config web/admin/vite.config.ts`|0|对应应用构建|
|final-build-assistant|`node web/node_modules/vite/bin/vite.js build --config web/assistant.js/vite.config.ts`|0|对应应用构建|
|final-build-webapp|`node web/node_modules/vite/bin/vite.js build --config web/webapp/vite.config.ts`|0|对应应用构建|
|final-copy-assistant|`node web/shared/copy-assistant.mjs`|0|SDK输出同步|
|final-build-admin-copy|`node web/node_modules/vite/bin/vite.js build --config web/admin/vite.config.ts`|0|对应应用构建|
|final-entry-screenshots|`node web/tests/portal-browser.mjs final-entry-screenshots visual-only final`|0|1/1具名断言；pageErrors=0|

关键TDD：red-boundaries为10新边界失败（含3个旧实现授权reset不停止导致5s超时）；red-portal-root是0test导入失败，不计行为red；随后显式未实现占位接口的red-portal-behavior为15/15行为失败。green-core为当时57/57，无stderr。artifact/旧dev-login真实红2→green-download-login2/2。后续授权文案red→定向green-final-affected8/8；pending provider手动按钮disabled真实红→green-manual-pending1/1。business-frame status403阶段exit1因真实未处理pageerror，green-event-status两实际消费者均通过且零pageerror。

green-core后实质差异及最终适用：portal controller/session校验和observer算法未再改变；useApi/login及artifact新增保护由2例+浏览器late-blob证明；events新增pending-reader取消由green-final-affected的pending-read/callback-abort/原expiry覆盖；HTTP403/IAM503文案由green-final-affected验证，原login网关错误/401/trace仍具名通过。两消费者错误catch由green-event-status覆盖，原reset/HTTP/普通503恢复由browser5/6/7保留适用。最终只有PortalShell底部一句非逻辑文案，经root明确授权重开后删除；只追加final-build-admin-copy与final-entry-screenshots，未重跑类型/另两build/已绿交互。

三个独立dist构建并行发起，SDK先结束、webapp其次、admin最后；全部完成后执行copy-assistant，把本轮web/assistant.js/dist/assistant.js同步至web/webapp/dist/assistant.js，未改public。root后续一句文案修订只重建admin。两次admin和webapp原构建均有大chunk警告，未声称零构建警告，SDK构建/类型和最终定向单测stderr为空。SDK与webapp部署副本SHA均为cde3ca987284af21afc303f0f851f7afd0acf088189f38eb42952935ce5e571f。

## 浏览器实际具名用例与原始记录

浏览器为本机Chrome，真实HTTP server+Vite middleware仅随机127.0.0.1端口，没有route mock替代axiosTransport。每phase raw-http.json包含逐请求rawHeaders、Authorization、Cookie、请求body和服务端受控response envelope/SSE原文；cases的rawStart/rawEnd是该phase原始请求区间。Cookie用例先设置同源ambient Cookie，document.cookie和/ambient原始HTTP正对照仍有Cookie；真实axios/session、业务HTTP、SSE及实际附件下载四类请求Cookie均为null且Bearer A正确。不是XHR withCredentials=false的推测；本机axios1.20.0的defaults与fetch adapter实际代码读范围/SHA在axios-inspection.json，配置adapter:fetch、withCredentials:false；直接fetch均credentials:omit。

### browser-1（exit1，pageErrors 0）

- FAIL missing provider uses portal entry with no development fallback；raw[0,0)
- FAIL pending provider mounts connection entry immediately; same token identity switch clears drafts；raw[0,0)
- FAIL session 401 entry and manual reconnect；raw[0,0)
- FAIL session 403 entry and manual reconnect；raw[0,0)
- FAIL session 503 entry and manual reconnect；raw[0,0)
- FAIL explicit development mode is manual and verifies session before workspace；raw[0,0)
- FAIL real axios session and business HTTP, fetch SSE and artifact omit same-origin Cookie；raw[0,0)
- FAIL late App allSettled/finally and old closures cannot mutate new identity, navigation, message or network；raw[0,0)
### browser-2（exit1，pageErrors 1）

- PASS missing provider uses portal entry with no development fallback；raw[0,0)
- PASS pending provider mounts connection entry immediately; same token identity switch clears drafts；raw[0,4)
- PASS session 401 entry and manual reconnect；raw[4,7)
- PASS session 403 entry and manual reconnect；raw[7,10)
- PASS session 503 entry and manual reconnect；raw[10,13)
- FAIL explicit development mode is manual and verifies session before workspace；raw[13,13)
### browser-3（exit1，pageErrors 1）

- FAIL explicit development mode is manual and verifies session before workspace；raw[0,0)
### browser-4（exit1，pageErrors 0）

- FAIL explicit development mode is manual and verifies session before workspace；raw[0,0)
### browser-5（exit1，pageErrors 4）

- PASS explicit development mode is manual and verifies session before workspace；raw[0,3)
- PASS real axios session and business HTTP, fetch SSE and artifact omit same-origin Cookie；raw[3,16)
- PASS late App allSettled/finally and old closures cannot mutate new identity, navigation, message or network；raw[16,28)
- PASS late real App copy POST cannot navigate new identity or replay a POST；raw[28,33)
- PASS late providerOptions and published input consumers remain inside old workspace；raw[33,45)
- PASS late artifact blob from actual component cannot click after identity switch；raw[45,52)
- PASS RunDetail stops AUTHORITY_EXPIRED without status/open/cancel/POST growth；raw[52,56)
- PASS RunDetail stops AUTHORITY_DENIED without status/open/cancel/POST growth；raw[56,60)
- PASS RunDetail stops AUTHORITY_UNAVAILABLE without status/open/cancel/POST growth；raw[60,64)
- PASS DebugPanel stops AUTHORITY_EXPIRED without status/open/cancel/POST growth；raw[64,71)
- PASS DebugPanel stops AUTHORITY_DENIED without status/open/cancel/POST growth；raw[71,78)
- PASS DebugPanel stops AUTHORITY_UNAVAILABLE without status/open/cancel/POST growth；raw[78,85)
- PASS RunDetail stops named IAM 503 with manual recovery entry；raw[85,89)
- PASS DebugPanel stops named IAM 503 with manual recovery entry；raw[89,96)
- FAIL logout clears identity and waits for explicit reconnect; desktop and narrow layout；raw[96,98)
### browser-6（exit0，pageErrors 0）

- PASS DebugPanel stops AUTHORITY_EXPIRED without status/open/cancel/POST growth；raw[0,7)
- PASS DebugPanel stops AUTHORITY_DENIED without status/open/cancel/POST growth；raw[7,14)
- PASS DebugPanel stops AUTHORITY_UNAVAILABLE without status/open/cancel/POST growth；raw[14,21)
- PASS DebugPanel stops named IAM 503 with manual recovery entry；raw[21,28)
- PASS logout clears identity and waits for explicit reconnect; desktop and narrow layout；raw[28,32)
### browser-7（exit0，pageErrors 0）

- PASS late chart dynamic import does not restore old workspace or busy state；raw[0,4)
- PASS empty roles authenticate without admin controls, expiry and unmount never auto reconnect；raw[4,6)
- PASS RunDetail stops HTTP 401 without retry or cancel；raw[6,10)
- PASS RunDetail stops HTTP 403 without retry or cancel；raw[10,14)
- PASS DebugPanel stops HTTP 401 without retry or cancel；raw[14,21)
- PASS DebugPanel stops HTTP 403 without retry or cancel；raw[21,28)
- PASS RunDetail ordinary dependency 503 retains status/reopen recovery；raw[28,34)
- PASS DebugPanel ordinary dependency 503 retains status/reopen recovery；raw[34,43)
### red-debug-event-status（exit1，pageErrors 1）

- PASS DebugPanel status authorization denial after business frame stops observation；raw[0,9)
### green-event-status（exit0，pageErrors 0）

- PASS RunDetail status authorization denial after business frame stops observation；raw[0,5)
- PASS DebugPanel status authorization denial after business frame stops observation；raw[5,13)
### red-manual-pending（exit1，pageErrors 0）

- FAIL manual reconnect supersedes held provider while connecting and ignores late old provider；raw[0,0)
### green-manual-pending（exit0，pageErrors 0）

- PASS manual reconnect supersedes held provider while connecting and ignores late old provider；raw[0,2)
### final-entry-screenshots（exit0，pageErrors 0）

- PASS visual-only final missing and signed-out entry copy screenshots；raw[0,2)

browser2先五场景有效；browser3/4均setup失败，见setup-events。browser5的Debug四场景虽停止断言通过，但错误fixture造成4pageerror，因此不记零噪声通过；browser6只补这四场景和窄屏，exit0、零pageerror。browser7八场景、green-event-status两场景、green-manual-pending一场景均exit0零pageerror。所有旧phase和图均保留；实际语义用例按最后有效phase去重，不累加为单次运行。

## 视觉与资源

实际view共8PNG，在visual-check.json逐文件bytes/SHA与适用阶段说明。最终入口图在final-entry-screenshots/{missing-desktop,missing-narrow,signed-out-desktop,signed-out-narrow}.png，1440/390可读，重连可用；已连接工作区图沿browser6，纯文案修订没有改变工作区。旧P3 .shell固有min-width85.375rem：390viewport实际scrollWidth1366，不冒称手机自适应，只证明无新增宽度；连接壳390没有横向溢出。root也分别实际查看并确认8张图，其独立记录不纳入我的own seal。

每browser阶段lifecycle-start/end有ownedPID/随机origin/ready/close。browser1以taskkill仅终止自己PID10724及子树，未伪造finally成功；以后均context/browser/vite/http正常close，最终owned-process-check.json过滤本任务命令行后0残留。未终止任何未知PID/原Vite/主服务。

## 冻结delta、谱系与真实patch replay

4新增、12修改、0source删除：

- 新增 web/admin/src/PortalShell.vue
- 新增 web/shared/portal-auth.ts
- 新增 web/tests/portal-auth.test.ts
- 新增 web/tests/portal-browser.mjs
- 修改 web/admin/src/App.vue
- 修改 web/admin/src/RunDetail.vue
- 修改 web/admin/src/main.ts
- 修改 web/debug-panel/DebugPanel.vue
- 修改 web/debug-panel/events.ts
- 修改 web/shared/Artifacts.vue
- 修改 web/shared/api.ts
- 修改 web/shared/artifact.ts
- 修改 web/shared/run-observer.ts
- 修改 web/tests/login.test.ts
- 修改 web/tests/run-observer.test.ts
- 修改 web/tests/stream-auth.test.ts

完整宽inventory含backend380+web238→242（总618→622）；其中50个既有web/.playwright-cli文件只为当时完整web快照被列入，未改变、未复制旧日志。必要源码排这50项，frontend188→192（初始必要base778610bytes），总568→572。source-before/after保留原宽口径，necessary-source-before/after是明确必要口径，不倒改阶段manifest。每轮source manifest保留backend380冻结项，未重新物理扫描这些backend或1933 targets。

frontend-base在最终封存时由实际事前before-overlay原件和与事前SHA一致的未改文件重建，provenance明确复制时间，不谎称所有原件在测试前已拷贝。每阶段与最终frozen-overlay只含准确新/改文件。目标backend1933继承D4a+I1不可变composition；没有全量JAR/旧证据复制。dist-after15，新增8/修改4/删除8，frozen-dist-overlay含12新/改，dist-unchanged含3已验未改项，composition保留8tombstone。

真实rootcwdpatch：source-text-attempt.patch是完整可读上下文diff；普通text和临时global binary属性两次check/apply虽exit0，却因既有.gitattributes text=auto eol=lf过滤App唯一CRLF而byte验证失败，原patch/失败目录/诊断全保留。最终source-fullcontext.patch对App使用真实Git binary delta，其余文件保留全文context；用own.local无commit私有bare证据repo的info/attributes -text提高优先级，进程级GIT_DIR/GIT_WORK_TREE，不改真实.git/config/.gitattributes。

replay-check.json记录实际工程rootcwd的git apply --check和apply命令/临时env/exit0；16变动文件逐名/bytes/SHA相等，全部192必要frontend组成也相等，backend380以既验不可变manifest继承，共572必要逻辑源。不是只比较规范化文本。保护检查9锚点+8执行索引+axios源码均未变；root新planning文件/current-work-state独立授权，不当作产品变动。

## 交还与限制

源码、backend target、三个dist及own报告/证据写权全部交还root；全部停止写入。八索引、旧review/runner/契约/v1.2/lockfile/后端均未改。后续独立review与root核验仍待，不签收完整D/E/F、生产IAM或17/34/30全验收。残留限制为既有1366工作区、admin/webapp大chunk警告、只有隔离HTTP/宿主与本机Chrome证据；未跑真实外部IAM/实际dev-env进程启动/完整旧127测试。

精确seal/handoff/evidence-manifest用相邻JSON给出文件总数、总bytes与SHA，并把报告SHA纳入；避免报告自哈希循环。最终完成信息只引用这些封存JSON。
