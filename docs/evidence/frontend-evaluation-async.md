# 异步候选评测消费者与可读报告

2026-10-20。唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`。本轮新增前端消费者，不改monitor API/响应/判分，不改engine/gateway/skill/common、原契约、NamedInputs/DebugPanel冻结CSS、Chat/SDK/既有observeRun或root的MCP/scheduler脚本。未启停服务、打包JAR、提交或发布。全部10个本轮变更/新增源码及测试SHA见 `frontend-evaluation-async-sha256.json`；target现已释放，待root安排独立review和G5部署后真实联调。

## 阅读与遵循的原始输入

- AGENTS.md、docs/implementation-brief.md、contracts/README.md、冻结快照detailed-design-v1.2.md第九部分及发布门禁段落；沿v1.2、既有Vue3.5/Element Plus第二版风格实施。
- `monitor/.../api/EvaluationController.java`、`monitor/.../application/EvaluationService.java`完整查询/创建/execute/resume/list/report语义：GET/list只查询，不更新评分；列表为tenant内最近200份；resume需EDITOR且原createdBy，ACCEPTED/REJECTED直接返回，ERROR可显式恢复；恢复从原报告candidate和不可变dataset取原cases，不读取新草稿。
- `platform-gateway/.../api/DevAuthentication.java`、`web/shared/api.ts`：身份来自开发登录返回的user.principalId；不解析JWT猜用户，不信任客户端自报身份；401沿既有API清登录，支持AbortSignal。
- `web/admin/src/App.vue`现有load/check/evaluate/commit/发布接线、`EvaluationInputs.vue`现有不可变选集与独立预期语义；既有named输入/评测受控和真实脚本、frontend-query-state测试结构。原脚本与业务cases未修改。

使用Vue、TDD、frontend-design/ui-ux及ponytail原则；采用独立observer与小组件，不新增依赖、请求框架、存储或评分器。

## 实现边界

`EvaluationPanel.vue`负责单次显式创建、已有报告选择和观察生命周期；`EvaluationReport.vue`提供状态、通过/失败/待完成、安全通过数及逐例结果，完整JSON与预期/实际输出折叠到高级详情。排队/运行/等待外部/等待确认分别显示；等待确认提供原run与确认中心入口，页面没有调用批准接口。待完成且passed=false不会被误计成失败；依赖错误会计入失败/安全失败。

`evaluation-observer.ts`只沿同一报告ID串行GET→必要POST resume，不重POST创建、不重造cases、不批准、不cancel业务。普通观察间隔1至5秒，外部/确认等待3秒；网络/5xx连续失败按1/2/4/8秒等待，4次后暂停并提供重新连接。401/403及其他4xx立即停止。ERROR默认停止，仅“重试此报告”明确恢复原ID；ACCEPTED/REJECTED终止观察。当前app、修订、候选SHA、报告ID、createdBy及组件generation/abort共同约束响应，不采纳迟到或归属不同的报告。

创建API没有客户端requestId。若ACK丢失或响应缺少合法身份/候选元数据，页面不会猜ID或自动重发POST；提示从已受理列表找回。重进后先预检，再选择code/rev/SHA匹配的已有报告，不手填ID；非原创建者仅查看，未知principal默认不自动resume。身份/权限变化、换app、换候选和卸载中止观察；离开不会发送cancel请求。

App只作必要接线：创建/选报进行中、结果未完成或尚未重新预检时禁止确认发布；ACCEPTED后用户明确“重新预检并绑定报告”。新预检候选与旧报告不符时清除旧报告。最终发布仍由原服务端预检凭证和候选摘要裁定。判分仍为独立expected与完整output精确相等，没有从actual生成expected。

## 红绿执行证据

| 证据 | 精确性质与结果 |
| --- | --- |
| `frontend-evaluation-async-unit-red.log` | 实现前observer模块尚不存在，导入失败、0 tests；这是缺实现红，不能称已有状态机的行为失败。 |
| `frontend-evaluation-async-red.json`、`frontend-evaluation-async-browser-red.log` | 实现前真实App SFC使用受控API返回RUNNING，16秒内只有一次创建，没有任何报告GET/resume，也未出现“评测已通过”，实际超时退出1。这是旧消费者缺口的行为红。 |
| `npm test` → `frontend-evaluation-async-unit.log` | 原25文件83项保留；新增observer13项。最终26文件96项全部通过，退出0。覆盖串行同ID多次RUNNING→终态、确认等待不失败、ERROR显式恢复、400/401/403/404/409停、4次有界网络退避、离开晚包、候选/owner不符拒绝、依赖错误统计。 |
| `node web/tests/evaluation-async-browser.mjs green` | 3项通过：真实App SFC连续3次GET/resume后ACCEPTED且创建仅1次、可读安全统计、390px无横溢。`frontend-evaluation-async-green.json`、`frontend-evaluation-async-browser-green.log`。 |
| `node web/tests/evaluation-async-scenarios.mjs` | 最终13场景通过，pageErrors为空；`frontend-evaluation-async-scenarios.json`、`frontend-evaluation-async-scenarios-final.log`。人工确认不批准；ERROR明确同ID重试；网络恢复不重建；重进找回/重新绑定/换候选；400/401/403停；跨app/换候选/离开/权限变化晚包；ACK未知找回；其他owner只读。所有创建帮助动作实际连续点击两次，已断言只POST一次。 |
| `node web/tests/evaluation-async-compat-regression.mjs` | 命名输入18+原评测5项共23项通过，`frontend-evaluation-async-compat-final.log`、`frontend-evaluation-async-compat-regression.json`、`frontend-evaluation-async-compat-evaluation.json`。下述元数据适配已获root许可，不改原冻结脚本或历史证据。 |
| `npm run build` → `frontend-evaluation-async-build.log` | vue-tsc、Admin、WebApp、SDK及复制脚本均退出0。既有chunk体积提示仍保留。 |

兼容runner仅为旧评测stub补齐真实契约的id/status/appCode/draftRev/candidateSha256/createdBy/results，原`passed:false`保持并映射REJECTED；GET/list返回数组。新增消费者会读取报告列表，故原“0创建/精确创建body”断言只明确筛选POST，语义不变。inputs/expected/safety、候选既定值及原业务断言未改，旧脚本文件SHA仍保持。这是旧fixture元数据适配，不能称逐字不变地执行旧响应。

真实Chrome已检查 `frontend-evaluation-async-green.png`（1440px）与 `frontend-evaluation-async-390.png`：默认先显示状态与安全/逐例结果，原始JSON折叠，运行/确认入口明确，手机动作与文案可见，documentWidth不超过390。浏览器使用root已有15175测试服务，响应均明确是fixture，不代表真实外部等待或真实批准链路。

## 待统一验收与限制

- `evaluation-async-live-browser.mjs`已准备并语法检查，通过独立`--g5-ready`门禁；尚未执行。它复用原真实9链的专用FLOW、独立输入/expected与候选事实，只将“创建立即ACCEPTED”适配为观察原报告到ACCEPTED，输出独立async-live前缀证据，原真实脚本/历史9项证据保留。遵root要求，独立review与G5部署后再统一执行。
- 当前16项新浏览器检查与23项兼容均为受控SFC；不宣称已完成本轮真实G5外部等待/人工确认。此前真实纯FLOW9链为历史证据，未冒称本轮重跑。
- 列表API仅返回最近200份tenant报告，前端再按候选筛选；超出该API范围的历史报告查找需要后续服务端能力，前端不伪造分页。无localStorage业务数据或票据保存。
- 不增加LLM判分、人工标注、多用例库治理、生产IAM、真实供应商或容量验收。
