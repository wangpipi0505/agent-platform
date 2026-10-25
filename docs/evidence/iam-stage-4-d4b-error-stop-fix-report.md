# D4b聚焦修复：附件401与reader清理异常

状态：实现及自验冻结交还，待root有限审计和原reviewer聚焦复审；不自行关闭I1/I2门禁或完整D4b。初审、原报告、原908叶seal均保留。

## 已核问题与最小修复

先读本次brief与原review全文，再按receiving-code-review核真实调用路径。I1确为artifact将HTTP401包装为无status普通Error、Artifacts仅显示文字；I2确为finally中cancel拒绝覆盖既定AUTHORITY_DENIED，被未修改的observer视为瞬态错误，red真实得到readStatus=1并返回SUCCEEDED。未盲改observer分类。

仅7文件修改，0新增/删除：生产web/shared/artifact.ts、web/shared/Artifacts.vue、web/debug-panel/events.ts；测试web/tests/portal-auth.test.ts、stream-auth.test.ts、run-observer.test.ts、portal-browser.mjs。没有修改其他业务组件、API/observer生产分类、后端/依赖/lockfile/契约/八索引、原主进程、全局环境；未部署或提交。

- 纯downloadArtifact原五参及既有可选第六scope保持兼容，非OK Error增加status，保留原message；不导入全局auth，不额外读错误体取code，不新增错误框架。
- Artifacts catch先确认捕获scope仍有效且非AbortError，只有所属当前HTTP401调用auth.invalidate(session.generation)，统一递增generation、清token/user/roles并abort旧观察。旧scope迟到401直接丢弃，不清新身份、不显示旧错误、不click；403保留当前身份并显示“当前账号无权下载此附件，请联系管理员。”。没有自动重连、下载重试或POST重放。
- streamEvents finally撤销abort listener，尽力await cancel且捕获其拒绝，然后始终尝试releaseLock；两种清理异常均不替换原主结果。正常EOF仍正常返回，已有授权reset/HTTP拒绝/AbortError/parser/network主错误保持；真实锁释放由ReadableStream.locked=false断言。没有把TypeError或普通503改为授权失败，也不保证底层cancel拒绝时物理socket一定关闭。stopReader原本的异步catch保持。

## 实际TDD与适用

所有shell/下面每条实际命令cwd均为`G:/207/项目/算法重组工具/代码/agent-platform`。每次命令前source-before.json含完整572必要逻辑源（380后端冻结项继承+192实际前端SHA），overlay只含相对原D4b的新改文件；命令、输出、exit均保留新own目录，不倒改事前状态。

|阶段|实际命令|exit|结果|
|---|---|---:|---|
|red-helper-reader|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts tests/stream-auth.test.ts tests/run-observer.test.ts -t D4b fix`|1|12新例：8失败/4既已通过；54 skipped|
|red-browser-current401|`node web/tests/portal-browser.mjs fix-red-browser-current401 D4b fix artifact current`|1|真实当前附件401仍保留A/ADMIN/user，断言失败|
|green-helper-reader|`node web/node_modules/vitest/vitest.mjs run --root web tests/portal-auth.test.ts tests/stream-auth.test.ts tests/run-observer.test.ts -t D4b fix\|D4b late artifact blob\|D4b aborts a pending\|D4b stops after reader\|D4b stops named IAM 503\|uses status while the transient`|0|17通过=12新+5直接相关旧controls；49 skipped|
|green-browser-artifact|`node web/tests/portal-browser.mjs fix-green-browser-artifact D4b fix artifact`|0|仅3新组件场景通过，零pageerror|
|final-build-webapp|`node web/node_modules/vite/bin/vite.js build --config web/webapp/vite.config.ts`|0|对应应用本轮一次构建|
|final-build-assistant|`node web/node_modules/vite/bin/vite.js build --config web/assistant.js/vite.config.ts`|0|对应应用本轮一次构建|
|final-typecheck|`node web/node_modules/vue-tsc/bin/vue-tsc.js --noEmit -p web/tsconfig.json`|0|完整vue-tsc一次通过|
|final-build-admin|`node web/node_modules/vite/bin/vite.js build --config web/admin/vite.config.ts`|0|对应应用本轮一次构建|
|final-sdk-copy|`node web/shared/copy-assistant.mjs`|0|本轮SDK同步到webapp/dist|

red-helper-reader真实8失败包括：HTTP401/403/503 status缺失（三例）；三个AUTHORITY_* reset在真实ReadableStream底层cancel返回reject后丢失reason；parser SyntaxError被cleanup TypeError覆盖；真实streamEvents接observeRun产生opens1/readStatus1/resultSUCCEEDED。其余4新断言在修复前已通过：旧scope401、纯五参成功、abort+cancel拒绝、EOF/primary network error；未倒称全部红。

green-helper-reader为17具名通过，未重跑旧57/127：12新例加旧late artifact blob、pending reader abort、buffer callback abort、named IAM503 vs ordinary503、transient network恢复5例。observer生产文件未改。cleanup组合的最终锁/opens/readStatus/fact等断言来源及限制见probe-results.json，不伪造未单独输出的变量raw。

所有生产source最后一次修改发生在这两绿阶段之前，随后仅证据工具/报告封存，没有重新改变source。完整vue-tsc与三个应用build本轮各一次，不以旧结果代替。三build独立输出并行发起，SDK先完成、webapp其次、admin最后；全部完成后运行copy-assistant，仅把本轮web/assistant.js/dist/assistant.js复制到web/webapp/dist/assistant.js，SHA核相等：12da80d13fc3a86151df710ba57a6813180d51776df28746afcca62be08a372c。admin/webapp仍有大chunk警告M1，SDK/类型/定向单测无stderr；未提高阈值或扩打包重构。

## 三个实际组件场景

Playwright本机Chrome+原已稳定自有HTTP/Vite middleware，仅随机127.0.0.1端口；无route mock替代artifact helper。browser fixture仅新增phase.startsWith('fix-')时输出新fix目录，所有本轮命令都传fix-前缀，不向原908目录写新phase。受控响应/原始rawHeaders/Authorization/Cookie/body/response status在对应raw-http.json，case有rawStart/rawEnd范围。没有重复旧32浏览器场景。

red-browser-current401的实际浏览器phase为fix-red-browser-current401，观察到A/ADMIN/user仍存在，provider1/click0，旧错误文字下载401；单测红之后、生产修复之前真实执行，非setup错误。其lifecycle正常close，失败与截图保留。

green-browser-artifact的实际phase为fix-green-browser-artifact，只有以下3新用例，phase exit0、page-errors.json为空：

- D4b fix artifact current 401 clears whole identity and stops observation without replay；raw[0,6)。generation1→2，token空/usernull/roles[]，provider1/download1/POST0/click0，观察请求稳定。
- D4b fix artifact delayed old 401 cannot invalidate new identity or show old error；raw[6,13)。先等待B新session成功再释放A旧下载HTTP401；B/gen2/ADMIN保留，provider2/download1/POST0/click0，无旧错误。
- D4b fix artifact 403 preserves current identity and explains denied download；raw[13,17)。A/gen1保留，provider1/download1，显示中文无权下载。

旧401用例只对该附件fetch测试性忽略abort signal，以保证原HTTP401确在新session完成后返回；真实helper的scope检查仍运行。它不把被网络abort提前拒绝冒充迟到401成功返回。当前401保持RUNNING观察以核invalidate后status/open停止增长，不发cancel或业务POST。403不误清session。

## 视觉、资源与边界

实际view两张本轮PNG：fix-red-browser-current401/current401-observed.png与fix-green-browser-artifact/current401-observed.png。红图保留工作区下载401；绿图移除旧业务数据并显示“会话已失效，请重新连接”。仅本次状态图，不重复原8图或声称新移动适配。旧工作区1366px、真实外部IAM/联合签名链、真实dev-env启动及D/E/F/17/34/30限制均仍保留。

两个浏览器phase各有lifecycle-start/end：自有node PID/随机端口/ready/HTTP+Vite+Chromium关闭记录；没有setup错误或强杀，本次未触碰原主服务。owned-process-check.json最终过滤本修复命令行后0残留。取消底层资源仅能尽力，不承诺reject cancel的socket物理关闭。

## 精确冻结、replay与保护

source572保持380backend+192frontend；本次仅7修改、0新增删除。backend target1933继承D4a+I1，不重扫、不复制JAR；原D4b composition、192必要前端基线与其物理frozen/base沿用。旧908叶不遍历不复制，原报告/review/seal/handoff/manifest等12锚点及8执行索引当前SHA与事前记录相同。

dist最终15：新增6/修改4/删除6，仅新/改10文件有本次物理overlay，未改项沿原D4b输出组成，删除项显式tombstone。source/dist精确delta和bytes/SHA在source-delta.json/dist-delta.json及freeze.json。

source-fullcontext.patch是7文件完整上下文真实git diff --no-index（差异命令exit1是预期）。replay-check.json有实际工程rootcwd git apply --check与apply两条exit0命令；只在本own replay目录放7个事前原件，应用后全部7改动及完整192必要前端组成逐名/bytes/SHA相同，380backend沿既验manifest继承，最终572逻辑源相等。本修复不动App混合CRLF，因此不需要改旧bare证据repo或工程.git/config/.gitattributes；新patch直接精确重放。没有标准化文本相等代替bytes检查。

仅本fix两own目录和新report纳入新evidence-manifest/seal，root state/planning/review不在我的seal。报告不自写自身哈希以免循环；最终handoff/seal记录报告、manifest、patch和所有引用的bytes/SHA/总文件数。全部source/target/dist与own报告/证据写权冻结交还后停止写入，等待原reviewer聚焦复审，不自行变更八索引或全局签收。

封存工具补记：第一次seal的Array.map(entry)将索引传作可选physical路径，fs.readFileSync按FD读取发生EBADF，命令exit1；seal-first-attempt.json据实际工具输出记录。改成map(f=>entry(f))后仅重试seal，未改产品source/dist、未重跑任何已绿测试或构建。该失败不计行为red。
