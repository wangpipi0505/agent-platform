# 前端消费者独立评审：T24 / T26 / G1 输出展示

评审日期：2026-10-20。结论：**需修改，确认 2 项 P2；本范围未发现 P1。** 本次仅写此报告，不修改业务、不操作 Git、不启停后端。

## 范围与方法

- 唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。已读本工程 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md` 及生命周期、统计接口、前端接入、输出消费者交接报告；未访问相邻旧工程。
- 无 HEAD，采用 `.local/reviews/frontend-consumers.json` 的 `current-source-snapshot-no-HEAD`，不称 Git diff 评审。用 `Get-FileHash -Algorithm SHA256` 逐一对照清单：15/15 文件与捕获摘要相同。
- 应用 Vue、frontend-design、code-review、requesting-code-review、verification-before-completion 技能。此任务本身是独立 reviewer 阶段；不再嵌套发起评审。设计检查限定本轮消费者，不扩大为全平台设计整改。
- 阅读既有测试、构建、Chrome 和真实网关证据；**没有重跑 48 项测试或三端构建**。交接报告记录 22 文件 / 48 测试及三端构建通过；已直接读取的较早 `.local/frontend-output-policy-all.log` 是 21 文件 / 41 测试，二者轮次不同。另读 `.local/frontend-output-policy-final-build.log`、两份组件 JSON 及 `docs/evidence/frontend-statistics-lifecycle-live.json`，不把历史记录表述为本次执行。
- 仅新增执行下面两个原证据未覆盖的定向 Chrome 探针。用全新临时浏览器上下文及测试票据 `component-fixture-only`；全部 `/admin/` 业务请求由测试 route 拦截，无后端数据变更、无持久票据、未使用既有浏览器 profile。

## 确认问题

### [P2] 翻页混入尚未提交的筛选，跳过新筛选第一页

位置：`web/admin/src/StatisticsPanel.vue:16`，以及分页的 `@current-change="query(false)"`。

`filters` 同时绑定可编辑表单与当前分页查询。当前第一页已有结果和分组总数时，只修改应用、日期等字段，不点击“查询统计”，随后翻页，`query(false)` 会直接从新的表单字段建立第 2 页查询。结果由旧筛选的总页数触发，却属于新 cohort，跳过新条件的第一页；新条件只剩一页时还可能展示空页，给出错误的无结果印象。这违背本轮要求的筛选与分页一致性。

本次 Chrome 观察（退出码 0）：

```json
{"probe":"unsaved-filter-pagination","componentFixtureOnly":true,"queries":[{"from":"2026-09-20T00:00:00.000Z","to":"2026-10-20T00:00:00.000Z","groupBy":"day","page":"1","pageSize":"20"},{"from":"2026-09-20T00:00:00.000Z","to":"2026-10-20T00:00:00.000Z","groupBy":"day","appCode":"unsubmitted_app","page":"2","pageSize":"20"}],"problem":true}
```

建议：区分表单编辑值与已提交条件。点击查询时校验并冻结条件且重置第一页；翻页从冻结条件读取，已保存快照继续按 snapshotId 读取。覆盖“改条件但未查询后翻页”及“新条件总数缩小”的回归。

### [P2] 对照请求迟到响应恢复已失效版本差异

位置：`web/admin/src/ApplicationHistory.vue:11-12`。

选择器变化只同步清空 `comparison`，`compare()` 内先前发出的异步请求仍会无条件写回。用户发起 v1→v2，在等待时把目标改为 v1，旧响应到达后，选择器显示 v1→v1，表格却展示 v1→v2 的修改。用户据此检查发布差异会得到与当前版本选择不一致的结论；应用切换同样需要使旧比较请求失效。

本次 Chrome 以 route 延迟该请求，改变目标选择后再放行。观察（退出码 0）：

```json
{"probe":"diff-late-response","componentFixtureOnly":true,"selectorText":"v1\n→\nv1\n对照版本","staleResultVisible":true}
```

建议：为比较请求捕获 appCode/from/to 和请求序列；参数改变、新比较或组件卸载时使旧序列失效。成功、失败、finally 都只更新当前请求；也可在请求中禁止改选择，但须处理应用变化。补充“响应未到先改选择”的行为回归。

## 定向探针命令与关键载荷

两次均在本工程 `web` 目录用 PowerShell 内联脚本执行：

```powershell
@'
// Playwright JavaScript；载荷如下所述
'@ | node --input-type=module
```

两次公共初始化实际使用 `import {chromium} from '@playwright/test'`、`chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})`、`browser.newPage()`，结束 `finally { await browser.close(); }`。`page.route('**/admin/**', ...)` 仅拦截 `url.pathname.startsWith('/admin/') && !url.pathname.startsWith('/admin/src/')` 的接口；Vite `/@fs/` 模块请求原样继续。

第一个探针的接口返回空的 groups/skillGroups、`groupsTotal:41,skillGroupsTotal:1,pageSize:20`，将请求查询条件保存到 `requests`，report.page 回显请求页码。快照列表返回空数组。实际交互载荷：

```js
await page.goto('http://127.0.0.1:15175/statistics-lifecycle.html#/statistics');
await page.getByText('共 41 个分组',{exact:false}).waitFor();
await page.getByPlaceholder('全部应用',{exact:true}).fill('unsubmitted_app');
await page.locator('.el-pagination').getByText('2',{exact:true}).click();
await page.getByText('第 2 页',{exact:false}).first().waitFor();
// 输出 requests；断言观察为第二个请求携带新 appCode 与 page=2。
```

第二个探针源应用 fixture 为 PUBLISHED/CHAT，版本接口返回 `[{versionNo:2},{versionNo:1}]`，日志为空。diff route 在 `await gate` 后返回 `fromVersion:1,toVersion:2` 及一行 `/description` 修改，新值为 `stale v2`。`started` 在该 route 收到请求时 resolve，`release()` 放行 gate。实际交互载荷：

```js
await page.goto('http://127.0.0.1:15175/statistics-lifecycle.html#/apps/source');
await page.getByRole('button',{name:'对照版本',exact:true}).click();
await started;
await page.locator('.history-select .el-select').nth(1).click();
await page.getByRole('option',{name:'v1',exact:true}).click();
release();
await page.getByText('stale v2',{exact:true}).waitFor();
// 输出 .history-select 文本与 stale v2 可见性。
```

上述探针没有落盘脚本、截图或新增产品数据。测试拦截只验证组件时序，不属于真实服务验收。

## 其余已核验的不变量

- 应用六维筛选使用 URLSearchParams 向服务端传 q/code/domain/bizCategory/tag/status，应用列表不在返回结果上继续宽松过滤；迟到列表响应按路径与当前条件丢弃。复制体仅重建 code/name，写操作依赖 edit 权限及服务端授权。新 DRAFT/currentVersionNo=null/draftRev=1 的证据来自真实网关 JSON，不由 UI 自行拼造。
- 归档按钮仅在 DISABLED 可见，默认不含归档，显式 ARCHIVED 查询由服务端执行；生命周期操作发送 JSON `{}`。概览日志来自独立接口，版本对照使用两个已发布版本号，不读取草稿作比较；ADD/REMOVE 缺失字段与 null 的显示不同。上面的 P2 仅涉及异步结果归属。
- 平台统计 ADMIN 拒绝态及请求入口双重限制；两个表保留各自服务端 total，共用最大页数，不把当前页累加标全量。缺少模型 usage/totalTokens=null 显示“待计量”，UTC 左闭右开日期及 366 天校验明确；来源时刻说明 PER_SOURCE_SNAPSHOT。
- 已保存快照按 snapshotId 读取，不先校验未提交表单日期；读回后回填真实筛选。重建体白名单只含筛选和 requestId，busy/rebuilding 防重复；同筛选失败保留请求号，筛选变化换号，成功清空号并接受后端 pageSize。错误可见，422 不生成假零指标。live 查询分页仍有上述 P2。
- failureTarget 只接受精确 `/admin/(statistics/)?runs/{安全id}`，不打开外部 href；owner 导航与 ADMIN 最小故障元数据弹窗分开，弹窗不取其他 owner 输入输出。
- Chat 与 SDK 的最终输出通过同一 finalAnswer 完整替换流片段，含合法空串；取消排队 animation frame，终态后忽略 CHUNK，避免无效重试内容附着。PASS_WITH_FLAG 保留正文并独立展示原因；历史轮次保留各自 warning。Markdown 继续经 DOMPurify，warning 使用 Vue 插值/textContent；票据保留内存。
- 既有输出浏览器 JSON 明确标组件 fixture，不能冒充真实供应商能力。主任务提供的真实受控输出策略 smoke（terminate/retry/pass/无效ref）属于后端策略验收边界，本评审未重跑，也未将其扩大为真实模型能力验收。

## 视觉复核与边界

本次实际用 view_image 复核 5 张既有截图：`frontend-statistics-lifecycle-statistics.png`、`frontend-statistics-lifecycle-mobile.png`、`frontend-statistics-lifecycle-live-diff.png`、`frontend-output-policy-webapp.png`、`frontend-output-policy-sdk.png`。统计表层次及两个独立 total 清楚；390px 下日期/查询/重建完整；版本差异与生命周期记录可识别；两种输出提示的原因可见且与正文分离。未发现本轮新增的确定视觉阻断。对比度 ≥4.5 的数值来自既有浏览器测量记录，本次未重新测量。

真实网关 JSON 提供复制、归档查询、统计、重建/快照读取、两不可变发布版本 diff、技能详情 200 的已有证据；本次没有另做真实业务 I/O，也没有将组件 fixture 当真实网关。除两项定向探针外，测试/构建结果均为读取既有证据。两项 P2 待实现者修复并定向复核后再更新该局部结论。

## 两项 P2 修复后的独立定向复核

日期：2026-10-20。**两项 P2 均关闭，本轮两组件定向复核通过，未发现新的确定 P1/P2。** 此结论更新前述“需修改/待修复”状态；原发现与红证据保留，便于审计。

本轮仅复核 `StatisticsPanel.vue`、`ApplicationHistory.vue` 及修复测试/证据，只追加本报告。遵照交接，未重跑 57 项测试、三构建或已有 Chrome 成功场景，未检查后续 G5 run-observer/Chat/SDK 改动。参考 `frontend-query-state-fix-report.md`，直接阅读实际 SFC 源码、`web/tests/frontend-query-state.test.ts`、`web/tests/frontend-query-state-browser.mjs`、红绿日志、`frontend-query-state-fix-green.json` 和 `.local/frontend-query-state-fix-preserved-browser.json`。

- **统计翻页关闭**：`query(reset=true)` 在 await 前复制并校验编辑条件，提交成功发起前设置 `appliedFilters` 并重置页1；`query(false)` 从 `appliedFilters` 取维度，只使用当前 page/pageSize。表单未提交应用及无效日期不再影响既有 live 分页。Chrome 绿 JSON 的 page2 不含未提交 appCode，点击查询后新 cohort 使用该 appCode 且 page1，与原探针失败条件逐一对应。
- **迟到版本差异关闭**：比较前冻结 appCode/from/to/token；选择、应用或 token 改变同步增加 `compareSequence` 并清除结果/错误/loading。成功、错误、finally 均检查同一 `current()`，防止 A→B→A 恢复旧结果或旧 finally 清掉新请求 loading；卸载设置 disposed 并失效序列。Chrome 绿 JSON 中 v1→v1 时 `staleResultVisible=false`，pageErrors 为空。
- **旧边界保留**：保存快照路径独立于日期 helper，回填服务端条件并更新 applied 条件；重建选中值和 body 在 await 前冻结，失败同条件复用 requestId，成功沿用 snapshotId 和服务端 pageSize。统计查询/重建与快照列表分别持有序列、token/ADMIN 和 disposed 判断；撤销权限、换身份、卸载会清空并失效，旧响应不能恢复统计。原快照、重建、权限及错误边界未因修复丢失。
- **行为证据有效性**：9 项新增测试编译真实 SFC setup，使用真实 Vue watch/renderer 和可控异步 API，覆盖原两失败、无效未提交日期、权限撤销、重建同号/pageSize、迟到成功/错误、旧 finally、新应用与卸载；不是只测试实现 helper。已读红日志 `8 failed / 1 passed`，绿整轮日志 `23 files / 57 passed`；本 reviewer 未执行这些命令，结论基于源码与已保存执行记录。
- **既有边界 Chrome 证据**：preserved JSON 记录 24 条 fixture 请求；snapshotIndependentOfUnsavedFilters、independentPaging、rebuildFilterOnly、readerDenied、sixFilterWire、copyWhitelist、archivedServerFilter、lifecycleJsonBody、adminFailureDialog、mobileChecked、skillVersionAndOwnerLink、failureHasNoFakeZeros 均 true。仍明确属于组件 route fixture，不将其解释为新增真实网关验收。

本轮另实际用 view_image 查看 `frontend-query-state-fix-green-pagination.png`、`frontend-query-state-fix-green-comparison.png`：前者在表单有未提交编码时仍显示原41分组第2页；后者选择 v1→v1 后无旧差异表。没有新增布局问题。构建日志入口包含 vue-tsc 及三构建，尾部记录 assistant.js 产物完成；只读取既有记录，不宣称本次重新构建。

复核对象 SHA256（本轮实际 `Get-FileHash` 采集，不将原15文件快照用于后续无关改动）：

```text
StatisticsPanel.vue  34682cd66e949a136dff8ac625e0adcce9dd858db8b14b7a3d598a1a1d782647
ApplicationHistory.vue  404dc9dbc6411b8cfdb990f4dc02139e57cdcdf01ef754acb8e6728da2d8d53d
```

本轮未新增真实业务 I/O、未改业务代码、未启停服务、未操作 Git。后续 G5 连接恢复的正确性须由其独立证据判定，不包含在本次两 P2 关闭结论中。
