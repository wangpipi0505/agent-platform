# 前端实施与验证报告（审查版）

## 当前结论

独立工程前端工作包可构建，真实Chrome已完成 **创建应用 → 保存配置/编排 → FULL/STEP调试与刷新恢复 → 候选评测 → 两阶段发布 → 正式运行 → 监控/版本链/事件回放**。本次流程为明确的START→END基础流程，不依赖真实模型；不能据此声称全部业务、外部模型或全部34任务验收完成。

写入范围仅`web/`和`docs/evidence/frontend-*`；未读取、引用、复用相邻旧工程。设计输入为当前工程冻结v1.2第八部分、原60/61全文、implementation-brief与当前工程API交接。没有提交他人文件，没有修改根pom或创建子智能体。

## 工程及运行

- npm workspaces：`web/admin`、`web/webapp`、`web/assistant.js`、`web/flow-designer`、`web/debug-panel`、`web/shared`。
- Vue 3.5 / TS / Vite / Pinia / Element Plus / X6 2 / axios / Ajv2020 / CodeMirror 6 / DOMPurify；未使用React或Vue beta。
- ECharts原5.x存在npm审计告警，按主智能体明确授权锁定稳定6.1.0；保留canvas/richText tooltip，最终npm audit无漏洞。
- `cd web; npm install`；`npm run dev`启动127.0.0.1:15173，`npm run dev:webapp`启动15174；`/admin`、`/auth`、`/v1`通过Vite代理18080。
- `npm run build`先执行vue-tsc，再构建admin、webapp及无外部依赖IIFE `assistant.js/dist/assistant.js`。
- `npm run api:generate`使用当前网关真实JWT保护的`/v3/api-docs`生成`shared/generated/api.d.ts`。服务端曾有重复operationId，已由root修网关；最终生成脚本**不改写元数据、不补静态接口**，直接真实生成成功。
- `shared/routes.ts`将实际列表路径及`/admin/{kind}`投影模板与生成paths做编译检查；当前后端多处响应声明为Object，因此不能声称完整响应字段类型及CI契约漂移检查已实现。

## 关键实现

- 藏青导航、浅色企业工作台、蓝主色、状态浅底深字双token；1366紧凑、1440/1920弹性布局，画布自动收窄导航，三档密度；token不进入浏览器存储。
- 应用列表六维筛选/分页、创建、元数据、版本、生命周期操作；对话配置真实模型/模板/技能选项，正式发布版本运行。
- X6新增/连线/属性编辑、Ctrl+S、Delete、X6 History撤销重做、ResizeObserver；definition与layout双向拆分，保存前直接引用工程共享graph-definition.schema.json验证。
- 未提交配置/画布草稿带draftRev存储；只有与服务器同修订时允许显式恢复，不能把过期浏览器草稿覆盖到新服务端版本。
- FULL/STEP调试、权威status恢复、认证fetch SSE、Last-Event-ID去重/重连、挂起instance+pauseSeq+requestId CAS放行、X6颜色联动、CodeMirror只读请求全文搜索与变量检查器。离开页面仅断开查看，不取消任务。
- 真实候选评测与重新预检绑定；服务端passed为false时禁用确认发布，commit带候选摘要、修订、预检票据与固定幂等号；发布二次确认。
- 模型供应商/模型条目登记、信任标签、连接测试入口；模板变量、版本、试渲染、发布；技能六步注册、详情、连接测试/模型选型验收入口；确认、审计、评测、系统支撑入口。
- 运行详情节点摘要、版本链、持久化事件回放、取消；运行中心显式排除DEBUG/EVALUATION，图表仅使用真实列表。
- UI权限按网关登录返回角色默认拒绝，编辑/审批按钮使用同一permission指令；这只是展示控制，所有服务端Actor授权不可替代。401清空内存票据；SYS隐藏内部细节保留trace；BIZ展示真实消息。
- webapp与assistant模型输出经DOMPurify；外部图片禁用，禁止script/iframe/事件属性/javascript链接。assistant是真实Custom Element + Shadow DOM，Shadow DOM不承担安全隔离。
- 业务对话使用`/v1/apps/{code}/runs`、`/v1/runs/{id}/events`、`/v1/runs/{id}`及cancel；宿主后端持ApiKey向`/embed/ticket`出票，浏览器ticket回调只接收短期令牌，不写长期secret。

## 自动验证及红绿记录

最终`npm run api:generate`直接真实生成成功；`npm test`：**12文件20测试通过**；三端完整build exit 0；`npm audit` 0漏洞。生产构建仍有chunk >500KB建议，详见剩余缺口。覆盖空服务端草稿、首次START入口、坐标不改变definition、保留joins/executionRules、共享Schema缺字段/未知类型、CRLF多行/半帧、UTF8跨块、无seq增量、事件去重、认证缺失不发送请求、认证与游标、401失效、trace/SYS错误、挂起代次与允许动作、草稿修订、权限默认拒绝。

真实先红后绿记录：挂起缺状态/未允许动作；SSE缺token；SSE401未清身份；502登录JSON错误；共享Schema缺字段/未知类型；空草稿读取崩溃及executionRules缺失；小写chunk归一；旧修订草稿误用；SYS内部消息泄露；权限默认拒绝。均在命令输出中观察失败后修复并复验。

初始4项测试在npm尚未安装完成时无法运行，随后首次行为运行即通过；不能把工具不可用算成行为红测试。后续以上关键失败路径具备实际红绿记录。

## 真实Chrome服务联调

全部通过实际网关、服务、PG；没有拦截API或搭建假后端。

| 项目 | 真实结果 |
|---|---|
| FLOW创建 | `frontend_browser_flow_20261020`，领域/分类独立 |
| X6保存 | draftRev=2，2节点1边，业务节点无position |
| 候选摘要 | `4d09f5eebf48fe6455bb1f335bc768d06895add88aca571f83d3c023d5d5794e` |
| FULL调试 | `r_c4f12c5c690a4af8a575b3077e81b335`，SUCCEEDED |
| STEP调试 | `r_1a543bd6f63e42f4be078edffb6d2273`，instance29/pauseSeq3，执行本节点→运行到结束成功 |
| 刷新恢复 | `r_b77f21aa4b54458d8145b54203c205e7`，重新登录恢复instance31/pauseSeq3，继续成功 |
| 真实失败门禁 | dataset_v1错误预期`{}`与真实`null`不等，REJECTED；预检6组BLOCKED，发布按钮禁用 |
| 候选通过 | 无声明输出流程按契约用新不可变dataset_v2/expected:null，报告`er_3ae83bd7-2bad-412d-8bec-ccecc2daafbe` ACCEPTED |
| 二阶段发布 | 重新预检6组PASS→二次确认→版本1，摘要与候选一致 |
| 正式运行 | `r_8d2e3aa2597149eb99cb7d12989cf32b`，SUCCEEDED，自动进入详情 |
| 运行中心 | 真实ECharts画布、正式运行可见、DEBUG不混入，appVer=1及RUN_STARTED持久事件回放通过 |
| 模型配置 | 供应商id1/模型条目id1登记；CHAT `frontend_browser_chat_20261020`选择模型保存，draftRev2 |
| 模型真实性边界 | 上述端点28400/v1为root提供的**测试fixture**，仅证明配置链路，不是真实模型验收 |
| 提示词 | 真实创建name必填变量→渲染“你好，测试员。”（tokenCountExact=false）→版本1发布→草稿版本2保留变量 |
| 身份存储 | 实际Chrome检查localStorage无token；刷新后需要重新取得内存票据 |

可重放Playwright CLI脚本位于`web/tests/live-*-check.js`。创建脚本会写明确命名的本地验收记录，不应直接重复用于生产租户；不包含票据或密钥。组件夹具与真实服务脚本分开，`web/tests/index.html`只做Vue props/回调测试，不提供生产fallback。

浏览器组件验证：拖动`businessUnchanged:true/layoutChanged:true`、撤销恢复；技能六步输入校验及回调payload；安全输出`script:false/handlers:false/javascript:false`。这些不冒充技能服务真实验收。

浏览器发现并修复：X6尺寸容器被压缩、节点新增重叠、模板详情正文遗漏、模板新草稿变量丢失风险、正式运行误复用评测JSON/请求号、HMR后未恢复当前数据、ECharts已dispose后重复操作。最终正式监控/详情/回放浏览器检查Console **0 errors / 0 warnings**。

## 截图

- 真实应用列表：`frontend-live-apps-1366.png`、`frontend-live-apps-1440.png`、`frontend-live-apps-1920.png`。
- 真实链路：`frontend-live-flow-saved-1440.png`、`frontend-live-step-recovered.png`、`frontend-live-published.png`、`frontend-live-formal-run.png`。
- `frontend-flow-*.png`为显式组件夹具；`frontend-live-flow-before-fix.png`为历史开发态零样式故障证据，不能当最终效果。

## 明确剩余缺口（未标验收）

1. 图节点属性仍是通用JSON编辑，尚无后端动态node-types驱动表单；校验错误尚无分组点击定位；没有完整Mock/断点配置交互。正式输入目前JSON输入，不是任意inputs Schema自动表单。
2. 技能连接/选型验收表单已接真实接口，但未用真实外部技能、真实模型跑完80%选型验收；UI六步组件验证不等于后端选型验收。模型供应商编辑/删除/凭据轮换及完整技能生命周期UI仍未覆盖。
3. 生产IAM/SSO、组织权限完整菜单/路由能力、用户/角色/租户系统管理未完成；系统入口对缺失服务显示真实错误。
4. webapp/assistant已构建、票据回调与v1边界已实现，但真实宿主出票+CORS+真实模型增量全链路尚未验证；历史会话、内联确认卡片、产物受控下载UI未完成，不展示虚假能力。
5. 发布响应目前返回`/webapp/{code}`及data-app脚本片段；当前开发对话实际`/w/{code}`且assistant需要显式ticket回调init。已告知root，这些服务端片段尚非复制即用四件套，不冒充密钥/凭据已签发。
6. OpenAPI已直接真实生成，已消费路径类型；完整请求/响应DTO类型和CI漂移检查仍需后端更细DTO及CI配套，不能仅以生成文件替代该验收。
7. ≥2560仅有样式规则，未做真实截图；未做完整无障碍审计/容量/长列表虚拟化验收。首次生产chunk有>500KB提示；已拆画布/调试/详情/ECharts，Element Plus/X6主chunk仍需性能优化。
8. 未验证真实模型、本体、外部SDK和容量环境；不将基础START→END流程成功计为全部业务完成。


## 第二阶段可用性补齐（2026-10-20 03:15）

写入范围仍仅 `web/` 与 `docs/evidence/frontend-*`。本阶段未读取旧原型，未修改后端或 root pom。

- 节点面板从通用 JSON 改为结构化表单：冻结 schema 的 START/END/LLM/SKILL/CONDITION/CODE/RETRIEVE/CONFIRM/AGENT/ONTOLOGY 类型；START 声明输入、END 输出引用、类型化参数和变量映射、技能版本、节点模型/模板、受限转换表达式、条件目标、确认角色/超时及失败策略。特殊配置仍可折叠高级 JSON。CODE 使用冻结命名，不新增 TRANSFORM 执行语义。AGENT/ONTOLOGY 只编辑已有通用契约，未声称外部能力已配置。
- 参数转换保留 false/0/null/嵌套值并拒绝重复键、危险键与非法类型值；缺省 args/output 不因打开属性面板被注入空对象（END 有实际语义）。保存前错误列表带节点编码，点击中心定位节点。布局仍独立保存。
- 调试新增创建时断点选择及 FIXED 技能 Mock 规则。未提供 LAST_REAL 或运行中修改规则界面；当前引擎不支持这些能力。继续使用服务端 allowedActions，而非前端猜测放行权限。
- 新增声明输入组件，兼容冻结 inputs 数组和 JSON Schema，保留数字/布尔类型并执行 schema 校验。正式运行必须取得所选发布版本的冻结声明，当前 app versions 列表缺少 snapshot 字段；正在协调精确版本公开端点，未使用当前草稿冒充发布版本，也不将该链路记为已验收。
- WebApp/assistant 接入 `/v1/runs/{id}/approvals` 和 decision。显示真实参数快照/摘要/截止时间，决定携带摘要与 requestId；缺摘要或过期拒绝提交。角色可见性/owner/app 范围继续由服务端控制。真实审批业务回路尚未创建完整测试用例，因此仅记为实现且本地验证，不记真实业务验收。
- WebApp/assistant/运行详情提供真实 fileRefs 的受控附件入口，Bearer fetch 后 Blob 下载；业务票据仅允许当前 run 的 externalDownloadUrl，不向任意 URL 或 admin 地址发送票据。附件下载后端刚接入，真实 MinIO 文件下载待服务重启后验证。
- assistant IIFE 全局仍为 AIAssistant。build 自动复制到 `webapp/dist/assistant.js`；15174 dev 的同路径实测 HTTP 200，构建资产来源为本工程，宿主仍仅提供短期 ticket。

验证记录：

- 新增节点转换/错误定位、声明输入、确认摘要、受控附件范围测试均先执行红再实现。最终 `npm test`：16 文件 / 27 tests 通过；三端 vue-tsc/Vite build 通过（完整最终命令仍见后续记录）；bundle 体积警告仍存在。
- Chrome 夹具组件测试：填写 count/NUMBER/必填/说明后保存，验证 inputs 正确，节点业务对象无 position。截图 `frontend-structured-2560.png`。该项仅组件集成证据，不是后端成功凭据。
- Chrome 真实网关：既有联调 FLOW 草稿新增 stage2Count/NUMBER/required，PUT flows 返回 200、draftRev=3，重新选中节点仍显示持久化字段。已发布 v1 保持不变。截图 `frontend-live-structured-2560.png`。
- 真实断点：POST debug-runs 为 FULL、inputs.stage2Count=7、breakpoints=[n_4d7f1dc6]、mocks=[]，返回 run `r_836d1fde3ea545509c0a0de540f93858`，挂起 nodeInstanceId=45/pauseSeq=3，allowedActions NEXT_NODE/RUN_TO_END/CANCEL。点击运行到结束后 SUCCEEDED。截图 `frontend-live-breakpoint-2560.png`。FIXED Mock 表单已接契约，未运行实际技能 Mock 用例，不宣称验收。
- 调试最初遇裸 500，root 日志确认 app 重启窗口 Connection refused（03:12:39），恢复后上述断点实证通过。另一次开发 HMR 重置输入 {} 导致新必填缺失的 FAILED 留存为真实失败，未伪造成功。

## 最后联通包（2026-10-20，最终事实覆盖前述阶段待办）

本包完成生命周期双轨、精确发布输入、受控技能测试简化、模型ADMIN写控件、WebApp/assistant会话恢复、内联确认、受控附件下载与运行中FIXED控制。写入仍仅web/及frontend证据；未修改后端、未停止root管理服务。生产门户base改为`/console/`，WebApp为`/w/`，API仍`/admin`与`/v1`，已与部署agent协调。

实现要点：

- 正式输入来自真实 `/v1/apps/{code}/input-schema?versionNo=`，快速切版本时旧响应不会覆盖当前契约。currentVersionNo有效且非停用/归档时保留正式入口；DRAFT+currentVersion显示“已发布v1，新草稿待发布”。未用草稿输入Schema替代已发布版本。
- 技能测试默认根据所选精确技能版本inputSchema显示结构化字段，高级JSON可折叠选用。一次点击提交inputs/requestId，显示真实runId与等待状态；确认中心显示运行编码和节点以便识别。成功后自动读取controlledCallId并重检；已有ID仅放高级项。开发实证发现等待审批时已经预留callId，修正为只有SUCCEEDED可充当验收事实；新增红绿测试防止提前重检。
- 模型供应商/条目创建与供应商连接测试UI要求ADMIN；信任标签字段也仅ADMIN可见，EDITOR仍可在编排中选择模型。UI权限只是可见性，服务器仍为权威。
- CHAT通过sessions/turns执行；sessionId仅写URL。恢复时先GET会话固定versionNo，再读取该版本输入，读取已授权run.inputs/output恢复历史。新会话默认当前发布版本。WebApp遵循type=FLOW走正式run，type=CHAT走会话，缺type显示清楚错误，不以失败后fallback猜应用类型。
- assistant同样支持创建会话、URL恢复、运行内确认和受控附件。会话恢复在打开抽屉时读取，正文/凭据不写localStorage；业务回答优先显示output.text，路由、usage等技术信息不混入正文。SDK额外声明输入生成原生字段，使用发布Schema校验。
- SDK真实Chrome发现抽屉100vh加padding使发送按钮落视口外；已用border-box、100dvh和日志min-height:0修复并实点击验过。恢复后发送按钮仍位于视口内。
- debug controls接真实GET/PUT与expectedRevision，服务端冲突显示错误并要求重新载入。当前表单只支持FIXED；若服务端存在LAST_REAL，不允许用这个表单覆盖它。LAST_REAL完整UI未交付，安全验收由root后续结论决定。
- 直接跨app切调试路由曾复用旧组件，现DebugPanel按appCode建立key，确保图与run状态隔离。

真实Chrome证据：

1. `smoke_flow_3cfa0d2fcb` v1输入message自动生成；截图frontend-live-published-inputs.png。旧应用frontend_browser_flow_20261020草稿3新增stage2Count后，发布v1仍按无参数运行，run `r_7adc11a387ce4dab8e5df87a78a9c4cb` SUCCEEDED，证明双轨与精确版本契约有效。
2. FIXED写技能Mock run `r_9ff6449148bf4bb0bd2c6817dbda895d`仍WAITING_APPROVAL，确认快照后批准，输出value=frontend-fixed、SUCCEEDED。HTTP写fixture计数前后均5，controlledCallId=null，不冒充真实连接测试。截图frontend-live-fixed-await-approval.png及frontend-live-fixed-succeeded.png。
3. 运行中controls真实GET后PUT `{expectedRevision:0,breakpoints:[n_4d7f1dc6],mocks:[]}`回revision1，继续运行SUCCEEDED。脚本web/tests/live-controls-check.js。
4. 新草稿smoke_ui_draft_9a1a8a55af没有已有调用ID。真实输入表单发起 `r_8771d1af79ff422e8d5f5ad5dd6e40d0`，另一浏览器页确认，原页自动轮询成功并以controlledCallId38重检ok=true。截图frontend-live-controlled-draft-waiting.png及frontend-live-controlled-draft-verified.png。首个开发尝试预留ID36时提前重检被后端拒绝，修复后重做上述完整闭环，未将失败伪装成功。
5. 门户下载已有大结果run `r_36c8e3b74a764621be4175a63dc2a762`，HTTP200，5242926字节，SHA256 `c4d407e5c01c46cc6c66145a1c43f3391ba86a6c129ac91080e0118aaa9f3edc`与status.fileRefs一致；小证据frontend-artifact-download.json，截图frontend-live-artifact-download.png。浏览器临时大文件验hash后删除。
6. WebApp实际新建只读大结果run `r_68650fd336ac41c7814591b6bd6af5ca`，使用v1 externalDownloadUrl下载HTTP200，5242926字节，下载证据frontend-webapp-artifact-download.json及frontend-live-webapp-artifact.png。
7. WebApp运行内确认run `r_8b8ad070fd7f481fafab20b23e200ac7`：同页显示真实参数快照，POST v1 decision批准后得到value=frontend-inline-confirmation；截图frontend-live-inline-confirmation.png。
8. CHAT `smoke_chat_bff1adb366`恢复已有session `s_d85ba3dfa66a4210911641881c250be9`两轮，继续第三轮run `r_9f83e2d6318647498cc79d16308c129e`，6气泡，localStorage空。截图frontend-live-chat-session-restored.png。
9. assistant真实dev-token回调创建session `s_4085fe050ef44867ac8b2d0d86864877`、run `r_4ba625703bbc4b1a85ecb6a24ae5df22`得到“受控测试完成”；刷新带sessionId后重新打开立即恢复历史，发送按钮在视口内，localStorage空。截图frontend-live-assistant-session.png及frontend-live-assistant-restored.png。此处是开发身份SDK集成，不是宿主短票据全链验收；root报告宿主bridge启动被自动审批拒绝，未在JS/DOM/storage放ApiKey，也未绕过拒绝重试。

最终验证：`npm test` 19文件/30 tests全绿（会话固定版本、预留调用ID门禁、业务文本显示均新增先红后绿）；`npm run build`含vue-tsc、admin/WebApp/assistant三端构建和assistant复制通过。`npm audit --omit=dev`为0漏洞。真实gateway OpenAPI已再次直接生成shared/generated/api.d.ts，无客户端修补operationId。ElementPlus主包体积告警仍存在，未以调高阈值隐藏。

明确剩余范围：宿主真实短票据完整浏览器链未做（root的API范围/吊销证据不能替代宿主验收）；LAST_REAL表单尚未交付；OpenAPI导入UI已由root另派独立前端任务接SkillWizard；外部真实模型/本体/企业SDK业务环境仍不等于本机fixture验收。本报告不声称全部v1.2业务条款最终验收。

最终复核补记（04:23）：协作导入任务新增4个测试后，当前全web `npm test`为20文件/34 tests通过；vue-tsc及三端build通过、production audit0。App仅新增SkillWizard open-imported事件关闭对话框并进入详情，由导入agent独立实现导入UI，不重复调用注册API。随后仅SDK原生控件样式改为现有蓝主色/ink/line tokens并单独重建，vue-tsc通过；Chrome再次恢复会话，历史不包含actualModel/providerId，发送按钮仍在视口内，更新frontend-live-assistant-restored.png。

## T20 LAST_REAL表单收尾（2026-10-20 04:29）

本节覆盖此前“LAST_REAL表单未交付”的限制。仅改web/debug-panel组件、转换helper与测试及frontend证据，未碰SkillWizard。

- Mock提供“固定响应 / 复用最近真实结果”。LAST_REAL默认自动匹配，不要求抄sourceCallId，也不提交fixedResponse。可选来源ID放折叠高级项，清空即恢复自动匹配。FIXED严格要求JSON对象，与真实engine约束一致。
- GET/PUT controls可完整回显LAST_REAL，保留规则额外元数据与控制配置，仍携带expectedRevision。新run清空上次run控制修订；冲突或未知模式要求重新载入，不覆盖无法理解的配置。
- LAST_REAL找不到匹配时显示真实错误和可重试提示，不向真实业务调用fallback；写技能继续走确认快照，未放松输入Schema或审批。
- 新增3个单测，先红后绿：自动匹配不携带固定响应、显式来源和额外字段往返不丢、非法来源/FIXED类型拒绝、清空来源回自动匹配。

真实Chrome验证：

1. `smoke_flow_3cfa0d2fcb` inputs.message=frontend-inline-confirmation、mode FULL、mocks=[{skillCode:smoke_write_ac2a778bae,mode:LAST_REAL}]，没有sourceCallId/fixedResponse。run `r_253c2992da2f471992ef1d7601966122`先在start断点挂起，GET当前控制后PUT expectedRevision0→revision1，LAST_REAL往返保持；放行后仍WAITING_APPROVAL，核对快照批准后SUCCEEDED，output.value=frontend-inline-confirmation。28411写计数前后均8。截图frontend-live-last-real-controls.png和frontend-live-last-real-succeeded.png。
2. 使用从未成功过的新参数frontend-never-matched-20261020，run `r_9521f11c3e3b4e4a84e972e044b33e27`真实FAILED，界面显示“没有可读取且参数合同匹配的真实结果”，可重新开始调试；没有回退外呼，写计数仍8。截图frontend-live-last-real-missing.png。权限收窄的后端独立安全复核由root完成，本次UI实测的是无匹配来源错误，未伪称额外角色收窄浏览器实验。
3. `smoke_artifact_49f62b7a31` inputs.message=large-formal、readonly大结果LAST_REAL，run `r_8ddb0c84c0a246438391c9a7390a5cbe` SUCCEEDED，创建新产物art_5c8b61b177164784813bb869ce0743a5（原来源art_0d43305cac9141dead8e288e265d9a18）。Chrome受控下载5242926字节，SHA256 c4d407e5c01c46cc6c66145a1c43f3391ba86a6c129ac91080e0118aaa9f3edc一致；28412/result调用数前后均9，未发生外调。小证据frontend-last-real-artifact.json，截图frontend-live-last-real-artifact.png。临时下载文件验hash后删除。

当前全web验证：21文件/39tests通过；vue-tsc和三端build通过，audit生产依赖0漏洞。最后仅补新run清空旧controls revision并重做vue-tsc/admin构建。主包体积告警仍如前。宿主ticket bridge未重试，本包未触及长密钥或宿主出票。
