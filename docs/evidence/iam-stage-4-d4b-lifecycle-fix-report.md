# D4b生命周期修复：退出订阅与时钟回退

状态：本波实现与自验已冻结交还，待root有限核验及原reviewer对I1–I4聚焦复审；不自行Approved或关闭完整D4b。原908叶、I1/I2的163叶/两报告/review/seal均保留，不回写原目录。

## 接收评审与最终行为

先读本波brief及独立补充全文，按receiving-code-review核真实路径。原controller只在dispose退订，Shell退出仅卸载App；原expiry测试changed后自动取票的绿断言与等待手动重连要求冲突。原timer只用deadline-Date.now分段，回退能延期。均由本波真实红确认，未以旧绿否认要求。

精确4文件修改、0新增/删除：生产web/shared/portal-auth.ts、web/shared/api.ts；测试web/tests/portal-auth.test.ts、web/tests/portal-browser.mjs。PortalShell无需改接线，I1/I2 artifact/events/observer生产实现保持原样。无新增依赖/框架/账号体系/storage/全局总线；默认portal/显式dev及provider接口、SDK用途保持。

- controller内同步watch auth.connection：signed-out/unauthenticated/expired先paused，并递增subscriptionEpoch使旧回调失效，再调用外部unsubscribe。退出必须退订；401/expiry本实现也选择退订，虽然原计划并非同一句硬要求二者都unsubscribe。旧排队回调、清理时同步回调及旧provider promise均不能复活session。
- manual connect才解除paused，按需恢复一份有效订阅。正常connecting/authenticated宿主通知仍取新票/新session并产生新generation（同token也一样）；forbidden/unavailable等入口的宿主通知不自动恢复，仍可手动重试。
- 订阅注册期间同步通知仅这一小窗口被忽略，同一次manual connect紧随注册取最新票；不是忽略所有活跃通知。已有有效订阅不重复注册。注册失败不回退dev；失效先关本地epoch再做外部清理，即使清理函数同步通知也被丢弃。dispose幂等，先关闭内部watch、回调归属与订阅，再统一invalidate清timer/身份/请求。
- install固定本次已验expiresAt所对应的剩余寿命，以performance.now建立单调上限；每次remaining=min(wallDeadline-Date.now,monotonicDeadline-performance.now)，按最多60s分段和generation归属处理。回退不延长安装寿命，前跳可提前失效；已过期/无效寿命不部分安装。旧timer不能清新generation。

准确边界：不能证明初始客户端时钟与服务器同步，也不承诺浏览器冻结/后台调度时即时到期SLA；恢复执行的检查按既定较早截止判定。UI不是后端许可缓存，每个业务动作仍由后端独立鉴权。host清理函数失败时本地旧回调仍失效，不承诺有缺陷宿主真的移除了其物理listener。没有服务端越权结论。

## 实际命令与分阶段源码适用

所有下面shell/实际命令cwd固定`G:/207/项目/算法重组工具/代码/agent-platform`。每阶段source-before.json保留完整572必要逻辑源描述（backend380继承、frontend192逐项SHA），仅变动文件有物理overlay。初始before真实捕获后才改测试/source，不倒改原件。

|阶段|实际命令|exit|结果|
|---|---|---:|---|
|red-lifecycle-unit|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts -t D4b lifecycle`|1|7生命周期例：5失败/2原已通过；20 skipped|
|red-lifecycle-browser|`node web/tests/portal-browser.mjs lifecycle-red-browser D4b lifecycle Shell logout`|1|真实退出后旧宿主通知恢复A/ADMIN，失败|
|green-lifecycle-unit|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts -t D4b lifecycle\|same token reconnect\|late provider and late session\|segments a far future`|0|10通过：7生命周期（含纠正旧expiry）+3直接相关旧controls|
|green-lifecycle-browser|`node web/tests/portal-browser.mjs lifecycle-green-browser D4b lifecycle Shell`|0|仅3真实Shell场景通过，零pageerror|
|green-error-entry-gates|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts -t D4b lifecycle session HTTP`|0|只新增403/503宿主门控2例通过，27 skipped|
|final-build-admin|`node web/node_modules/vite/bin/vite.js build --config web/admin/vite.config.ts`|0|该应用本波一次构建|
|final-typecheck|`node web/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p web/tsconfig.json`|0|本波完整vue-tsc一次通过|
|final-build-assistant|`node web/node_modules/vite/bin/vite.js build --config web/assistant.js/vite.config.ts`|0|该应用本波一次构建|
|final-build-webapp|`node web/node_modules/vite/bin/vite.js build --config web/webapp/vite.config.ts`|0|该应用本波一次构建|
|final-sdk-copy|`node web/shared/copy-assistant.mjs`|0|SDK同步并核SHA|

红证据：原logout unsubscribe=0；同步subscribe导致getToken2；当前401/expiry通知导致getToken增长；安装60s后wall回退1h、单调推进60001ms仍保留ticket。真实Shell红实际为providerCalls2/subscriptions1/unsubscribed0/active1，A及ADMIN/user被恢复。不是setup错误。pending provider在活跃宿主变化时被新身份淘汰、旧timer归属两例修复前已通过，不倒称全部红。

green-lifecycle-unit的10例由7个本波生命周期（6新+1纠正原expiry预期）与旧same-token/late-provider-session/far-future-forward-jump3例组成；后续只新增403/503门控两例单独运行，未改生产source、未重跑这10例或3browser。不同阶段各自source manifest保留，不能合成一次12例套件。clock-and-subscription-assertions.json清楚标注断言来源，不伪造未单独输出的变量raw。

最后只新增测试两例后，生产source仍与10/3绿时一致；最终完整typecheck及三个应用build均本波各一次。三个独立dist并行构建，SDK先完成、webapp其次、admin最后；全部完成后运行copy-assistant到web/webapp/dist/assistant.js，源SDK和副本SHA相等：12da80d13fc3a86151df710ba57a6813180d51776df28746afcca62be08a372c。SDK本次构建字节与I1/I2输出相同是实际结果，不以旧构建冒充；admin/webapp仍有M1大chunk警告，未改阈值或重构消噪。没有重跑旧57/127、I1/I2全部17或原browser32。

## 三个真实PortalShell/App边界

已授权本机Chrome和隔离HTTP/Vite middleware，仅本项目随机127.0.0.1端口；无真实外部IAM。输出路由先判lifecycle-再判旧fix-，本波不落旧163/908目录。wrapper阶段green-lifecycle-browser实际浏览器phase为lifecycle-green-browser，全部3用例exit0、page-errors.json为空；原始请求header/body/受控response与区间在各phase raw-http.json/cases.json。

- D4b lifecycle Shell logout ignores queued host notifications and manual reconnect restores one subscription；raw[0,6)。退出token空/usernull/roles[]，provider1/sub1/unsub1/active0；manual后sub2且仅active1，旧callback不推进新gen，正常活跃host切B仍成功。 POST/cancel均0。
- D4b lifecycle Shell artifact401 stays invalid after host event until manual reconnect；raw[6,17)。实际附件当前401后旧通知不取票/不请求session/不恢复；manual后B成功，provider2、download1，无自动下载/POST/cancel。 POST/cancel均0。
- D4b lifecycle Shell expiry bounds rollback and waits for manual recovery after host event；raw[17,21)。受控session给now+1200ms horizon，安装后wall回退1小时，仍在原单调寿命界限清身份/工作区；旧host通知不恢复；先恢复测试wall clock再手动连接B，provider2。 POST/cancel均0。

浏览器expiry horizon1200ms为受控端点输入，不冒称测得精确即时到期SLA；等待有界3500ms用于调度。单元60s测试通过可控wall与performance闩锁，不长sleep或只数setTimeout调用。实际current artifact401保留I1路径对照，I1/I2生产源码完全未动；本波不以此重新签收整个I1/I2套件。

## 图、资源与保护

实际view两图：lifecycle-red-browser/logout-host-event.png与lifecycle-green-browser/logout-host-event.png。前者红时退出通知恢复工作区，后者绿时保持退出手动入口。未改布局/重做原8图，旧1366px工作区限制保持，不新增移动自适应声明。visual-check.json有各图bytes/SHA。

红/绿两个browser phase都有owned node PID/随机origin/ready与http/vite/chromium正常close记录；本波没有setup/工具错误或强杀，owned-process-check.json最终本任务进程0残留。没有重启/终止原Vite/后端或未知PID。只检查自有命令行进程，不访问相邻旧工程依赖/数据/进程。

16锚点（含原两波seal/handoff/report/review、独立补充与root I1/I2核验）和8执行索引SHA与before相同；原908+163叶没有遍历/复制/写入，沿封存manifest/composition继承。root规划/state由root独占，未纳入我的own seal。

## 精确组成与交还

必要source572=backend380+frontend192，4修改、0新增删除；backend target1933不重扫/复制，沿D4a+I1及前两波既验组成。source-before/after/delta和frozen-overlay只保留本次实际范围。dist最终15，新增5/修改2/删除5，只新/改7个物理overlay，未改项继承I1/I2→原D4b组成，删除项tombstone明确。

source-fullcontext.patch是真实git diff --no-index全文上下文，差异生成exit1为预期；真实工程rootcwd的git apply --check及apply各exit0，4个变动文件与全部192前端组合逐名/bytes/SHA一致，380backend manifest继承，共572逻辑源相等。只在own replay目录放4个事前原件；不复制旧完整树，不改工程.git/config/.gitattributes；App混合CRLF文件未改，精确沿原冻结文件复用。

本波全部source/target/dist/own报告与两目录写权冻结交还；新seal/handoff/evidence-manifest记录精确叶数、总bytes、SHA及报告哈希，报告本身不写自哈希。原I1/I2七文件9阶段和163叶事实保持原样；后续由root有限核验及原reviewer一起复审I1–I4和新增风险，不自行Approved、更新八索引或声称生产IAM/完整D/E/F/17功能34任务30验收已完成。
