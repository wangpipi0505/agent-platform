# T20 / T22 前端消费者独立评审

日期：2026-10-20。结论：**本次冻结前端增量通过独立评审，未发现确定 P1/P2。** 结论限于命名测试输入与单项提升后的数据集选择消费者，不代表完整 T20/T22 或真实业务评测集闭环。

## 评审输入与执行范围

唯一工程根：`G:/207/项目/算法重组工具/代码/agent-platform`。遵循本工程 AGENTS、implementation-brief 与 contracts/README，阅读 `test-input-plan.md`、`test-input-report.md`、`frontend-test-inputs.md`，逐一检查 `frontend-test-inputs-sha256.json` 中 8 个文件。复核开始时 `Get-FileHash -Algorithm SHA256` 的 **8/8 摘要匹配**；无 HEAD，不称 Git diff 评审。

使用 Vue、frontend-design、code-review、requesting-code-review、verification-before-completion 与 ponytail 的检查原则：沿现有接口与最小增量核查，不要求另造请求框架、评分器或输入存储。本次只写本报告，未改源码、未操作 Git、未启动/停止服务、未访问相邻工程。

直接读完 NamedInputs、EvaluationInputs、DebugPanel、SchemaInputs，核对 App 的候选评测接线、API AbortSignal 支持与原输入 schema helper；阅读两份 Chrome 脚本、最终 JSON、83项测试日志和构建日志。既有23项 Chrome/83项测试/三构建均明确是实现者已有执行证据，未冒称本次重跑。

本 reviewer 新执行一个跨应用迟到数据集探针及三个相关测试文件；没有重复跑整套83项或三构建，没有新增真实后端写入。

## 关键结论

| 边界 | 独立检查结果 |
| --- | --- |
| 命名 CRUD 与 CAS | 新建仅发送名称/当前收集输入，草稿ID来自服务端应用详情；编辑使用详情revision，删除携带列表revision。409保留编辑器与本地输入并给出重载提示，不自动换revision重试覆盖。Schema400修正显式使用当前表单与元数据revision保存。 |
| 提升幂等 | 仅发送 revision/datasetVersion/caseId/expected/safety/requestId；来源ID取已选metadata，不带当前未保存inputs或运行output。fingerprint包含来源ID、revision与完整目标内容；同内容失败重试使用原body和requestId，内容改变生成新号。busy阻重复提交，源快照不在响应失败后偷偷换成最新revision。 |
| 预期与安全属性 | expected初始空字符串，必须由用户填写并JSON.parse；显式JSON null、false、0均可解析，不用真值替换。safety初始undefined，仅显式boolean可提交；不从实际输出生成expected。文案说明当前为完整输出精确比较。 |
| 草稿与迟到响应 | NamedInputs在appCode/draftId/declaration变化时递增scope并abort；列表/详情另持序列，详情使用前核对当前选择及表单编辑计数。卸载使alive=false。DebugPanel读取草稿、开始、恢复及观察回调均检查草稿序列；旧应用成功结果不能写进新草稿。 |
| 数据集读取与使用 | EvaluationInputs按appCode读元数据和不可变版本详情；变更app清空版本/cases及手工状态并abort，详情写回核对generation/sequence/selected/manual。collect在loading、无详情或版本不匹配时拒绝；向原评测API交付原cases深拷贝与datasetVersion，不从表单重建expected。 |
| 保留高级入口 | 原手工用例入口需版本与非空数组；没有选择并载入保存版本时不能直接评测。保留服务端输入校验与不可变版本冲突，不在前端伪评分。 |
| 现有Chat/SDK | 本次SchemaInputs新增replace与可选change事件，collect/原声明默认值校验保留；新父组件接change用于保护用户编辑，未改变Chat/SDK调用方式。现有输出warning、消毒及最终快照/观察器代码未属于本次修改。针对输入、输出与run-observer的18项本次执行通过，不能由此推称所有Chat/SDK业务场景重新验收。 |

后端权限、owner/tenant、草稿归属、源revision与不可变dataset仍由服务端最终裁定。前端CAS/幂等报文和作用域检查不替代其授权；本轮后端路由尚未部署时没有以fixture证明真实API联通。

## 本次新增定向证据

### 数据集详情迟到后切应用

工程 `web` 目录用 PowerShell 内联 JavaScript：`@' ... '@ | node --input-type=module`，导入工程现有 `@playwright/test`，本机 Chrome `C:/Program Files/Google/Chrome/Application/chrome.exe`，新建临时 browser/context，结束finally关闭。使用已运行15175的 `statistics-lifecycle.html`；全部 `/admin/` API 由本探针route fixture拦截，不访问真实后端、不落盘浏览器profile或票据。

关键执行序列：

1. 进入 app_a 发布页，预检并选择 v1；拦截 app_a 的数据集详情并延迟响应。
2. 更改hash进入 app_b 发布页，预检并载入 app_b/v1；两应用故意使用同名数据集版本。
3. 放行 app_a 旧详情，随后点击 app_b 的“执行候选评测”。
4. 核查唯一评测报文只包含 app_b 的candidate、inputs、expected、caseId；无pageerror。

本次实际输出，进程退出0：

```json
{"probe":"evaluation-late-cross-app","fixtureOnly":true,"sent":[{"appCode":"app_b","draftRev":4,"candidateSha256":"sha-app_b","datasetVersion":"v1","cases":[{"id":"case-app_b","inputs":{"app":"app_b"},"expected":{"app":"app_b"},"safety":true}]}],"pageErrors":[],"passed":true}
```

该探针补充原23项Chrome证据未显式覆盖的“相同版本名、数据集详情未返回先切应用”边界。没有将其扩张为真实跨租户/跨owner服务测试。

### 保留输入与输出行为的定向回归

本次实际命令，在本工程 `web` 目录：

```text
npm test -- --run tests/input-form.test.ts tests/output.test.ts tests/run-observer.test.ts
```

退出0，`3 files / 18 tests passed`，开始时间06:18:00、290ms。未运行整套83项、未构建。此检查用于SchemaInputs调用契约及既有输出保护的关联回归；不能替代新消费者的真实浏览器和后端联调。

## 既有证据审读与视觉检查

- `frontend-test-inputs-final.json`：18项checks全部true、pageErrors为空；直接读测试脚本核对，确有结构化使用、编辑期间迟到输入保护、CAS409、schema400修正、显式expected/safety、完整body同号重试/改内容换号、游标、删除、空态、403、切草稿和卸载行为断言。
- `frontend-test-inputs-evaluation.json`及对应脚本：5项包括未选择不能发评测、保存cases/version原样交原评测API、手工入口兼容、真实Admin调试/发布390px横溢检查。
- `frontend-test-inputs-unit.log`记录25文件/83项通过；`frontend-test-inputs-build.log`记录vue-tsc及Admin/WebApp/SDK构建和既有体积提示。以上仅为读取实现者已有记录。
- 本次实际用view_image查看 `frontend-test-inputs-promotion-390.png`、`frontend-test-inputs-evaluation-390.png`、`frontend-test-inputs-admin-debug-390.png`。390px下提升弹窗位于屏内，预期值、类型与提交操作可见；Admin页导航换行，命名输入动作可用，数据集选择和评测动作可见。本轮未发现确定新增视觉阻断。

## 未纳入通过结论的边界

- 本轮新API尚未部署，真实前端→网关→PG命名保存/提升/选集/评测联通由后续安排完成；本报告不写“真实闭环已通过”。
- 单项提升仍是单用例不可变版本；普通用例单独评测会受既有“必须有安全用例”规则拒绝。完整多用例治理、分类、人工标注及真实业务集验收未由本前端增量补齐。
- 当前后端完整output精确比较可能涉及CHAT动态元数据；本页面已明确完整输出语义，没有新增业务字段投影评分。评分器改善属于独立范围，不作为本次前端缺陷。
- 实际供应商/生产IAM/业务评测集和容量验收未由组件fixture、18项关联测试或既有构建替代。

范围内无须开列修复项。后续若后端联调暴露契约差异，应以具体响应和报文追加定向证据后重判对应消费者边界。

## 真实 FLOW 联调及390px适配增量复核

日期：2026-10-20。**本次限定适配增量通过独立复核，未发现确定 P1/P2。** 本节更新上文“尚未部署新API、待真实联调”的阶段状态：已有受控确定性FLOW的前端→真实网关→保存输入→调试→提升→原候选评测链路证据。仍不等于完整T20/T22、多用例治理或真实外部供应商/业务集验收。

### 改动边界与源码证据

只读检查 `frontend-test-inputs-mobile-fix-sha256.json` 及当前源码。原8文件清单中，仅 `DebugPanel.vue` 摘要变化；另7个冻结文件全部仍匹配（包含原测试fixture/脚本，不能将这7项都称为生产组件）。本次未修改任何产品源码。

```text
DebugPanel.vue before 81281d6a57decdd8f7219a43a2645b0b2d4d6a6ef96a5e55a77c426cfe98380b
DebugPanel.vue after  577e27f3c62dd574a997034d3d9909683c6082f897555b47298a7fca1f56351e
```

本 reviewer另用Node `readFileSync`/`createHash('sha256')` 做纯内存检查：去除文件末尾新增的第二个 `@media(max-width:600px)` 块后，摘要恰好还原为before值。由此证明该文件除新增CSS块外无脚本、模板或原样式修改。

新增选择器处于DebugPanel的`<style scoped>`与≤600px media内：运行信息所在row纵向排列、子项`min-width:0;overflow-wrap:anywhere`及动作换行；只读FlowDesigner的canvas-layout改单列，画布/属性面板允许在父容器中收缩。DebugPanel传给该FlowDesigner的`read-only`仍存在。没有通过新增`overflow:hidden`、移除图或缩到零宽掩盖失败；属性面板保留并下排。桌面宽度不满足该media。只为核查选择器作用与ResizeObserver机制读取FlowDesigner，未改其核心、全局tokens或执行状态。

### 真实链路证据审读

`frontend-test-inputs-live.json`记录`realApi:true,mockedRoutes:0`、9项checks全部true、pageErrors空、completed=true。已核对实际报文与响应：

- 应用 `smoke_named_inputs_3db244a637`、draftId34、FLOW仅START→END，无模型/外部业务调用。
- inputId2由revision1显式修订为revision2；调试 `r_0d146af8bc274df2af751baedf7c687f` 为SUCCEEDED，输出`{"answer":"命名输入完整联调"}`。
- 独立填写expected后将保存revision2提升为`debug-2026-10-19-cbe1858b`，source仍明确inputId2/revision2/draftId34。
- 原评测API收到同一保存版本完整cases及candidateSha256 `676db1269ccad10ec2fca00a4fd9bc90fc4e8df33d4b8e9a38e1ec5089c55e8d`，返回`er_dc98bd58-2c7b-415a-8c45-edc6f449034e`、ACCEPTED。

上述为作者已执行的真实API记录，本 reviewer未重做保存、调试、提升或评测业务I/O。

### 红绿截图与独立只读几何探针

实际view_image复核4图：`frontend-test-inputs-live-flow-mobile-red.png`、`frontend-test-inputs-live-flow-390.png`、`frontend-test-inputs-live-flow-canvas-390.png`、`frontend-test-inputs-live-debug-succeeded.png`。红图长runId/动作横溢、属性面板占据宽度而画布被挤压；绿图runId/动作换行、两节点与连边完整、属性面板下排。桌面仍为画布与属性双列。

本 reviewer新运行一个隔离Chrome只读渲染探针，命令入口为工程web目录PowerShell内联脚本 `@' ... '@ | node --input-type=module`。从live JSON读取已保存响应，route重放GET结果；用临时sessionStorage恢复已有runId，所有非GET请求均记录并拒绝。页面使用已运行15175 fixture，未使用真实票据/既有profile，未启动/停止服务、未操作后端。

该探针明确属于**真实响应快照的组件渲染重放**，不冒充本次新增真实API联调。依次测量390与1440宽度，进程退出0：

| 视口 | 文档宽 | 画布宽 | 节点尺寸 | 属性布局 |
| --- | --- | --- | --- | --- |
| 390 | 390 | 244 | 两节点各75.37×27.72 | 属性top1499.02，画布bottom1483.02，单列下排 |
| 1440 | 1440 | 730.47 | 两节点各174×64 | 属性left993.06，画布right977.06，双列 |

断言两节点全部四边均包含在画布内、画布宽>150、documentWidth=viewport、两种布局位置正确；`pageErrors:[]`、`writeRequests:[]`、`passed:true`。live JSON另有真实页390的244px画布及同等节点包围盒，两种证据一致。未以“页面无横向滚动”一个断言替代真实画布/节点可见性。

### 作者回归记录与本轮边界

直接读取`frontend-test-inputs-mobile-recheck-regression.json`及`frontend-test-inputs-mobile-recheck-evaluation.json`：18+5项checks全部true，均无pageerror。读取`frontend-test-inputs-mobile-fix-unit.log`：06:43:03，25文件/83项通过；读取`frontend-test-inputs-mobile-fix-build.log`：命令含vue-tsc与三端构建，尾部SDK产物完成，保留既有chunk体积提示。上述均是作者本轮回归证据，本 reviewer没有并行运行npm或build，也没有覆盖dist。

本次仅新增只读Chrome渲染探针和本评审附录。既有CAS、幂等、显式预期、安全属性和异步响应归属结论沿原冻结源继续成立；受控确定性FLOW的真实链路已有证据，生产IAM、外部模型、完整业务评测集及容量边界仍未由此替代。
