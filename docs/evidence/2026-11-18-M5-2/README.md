# M5-2 页面切真实 API（mock 分支清理）——交付与验收证据（2026-11-18）

卡片：台账 §7.6 **M5-2**（依赖 M5-1+F3/F4）；交付=**mock 分支清理**；验收=**演示真实链路**。
分支 `m52-real-api`（基线 f1f40e66）。本目录内含起栈/证据脚本与截图；**本地提交不推送**。

## 一、mock→api 切换清单（页面 × 端点对照）

页面模板/交互结构零改动；仅数据面从 `shared/mock-*.ts` 换到新增 `shared/api-*.ts`（经网关语义）。
所有 api 模块经 `useApi()` 走统一 transport：真实模式=axios→网关；`VITE_MOCK=true` 演示模式=
mock-transport（演示路由见 `shared/mock-api-m52.ts`，M5-2 新增——委托保留的 mock 域模块，A28 口径不变）。

| 页面/组件 | 原 mock | 新 api 模块 | 真实端点（engine=18202 / app=18201 / skill=18203，经网关 18200） |
| --- | --- | --- | --- |
| admin TasksPage | mock-task | api-task | GET /v1/tasks、GET /v1/tasks/{id}、/events、POST cancel/resume、POST /v1/apps/{code}/tasks、steps/{stepId}/calls、…/reconcile |
| admin PlanDetailPage | mock-plan | api-plan | GET /v1/tasks/{taskId}/plans/{planId}/revisions/{revision}（深链需 ?taskId=&revision=） |
| admin SessionDetailPage | mock-session-detail | api-session | GET /v1/apps/{code}/sessions/{id}、/events、GET /v1/sessions/{id}/subagents、GET /v1/tasks/{taskId}（recovery） |
| admin MemoriesPage / webapp MyMemories | mock-memories | api-memories | GET/POST /v1/apps/{code}/memories、GET/PATCH/DELETE /v1/apps/{code}/memories/{id} |
| admin RulesPage | mock-rules | api-rules | GET /admin/apps（目录聚合）、GET/POST /admin/apps/{code}/rules、PUT /admin/rules/{code}/draft、POST versions/{v}/publish、GET versions、POST enable|disable |
| admin PluginsPage | mock-plugins | api-plugins | GET/POST /admin/plugin-sources、POST …/discover、GET …/discoveries/{id}、GET /admin/plugins、POST …/enable|disable|grants、DELETE /admin/plugins/grants/{id} |
| admin McpConnectionsPage | mock-mcp | api-mcp | POST/PUT/GET /admin/mcp-connections[/{id}]、POST …/test、POST …/discover、GET …/tools、POST …/tools/review |
| admin ApprovalsPage + AppShell 角标 | mock-confirmation-counts/matrix | api-confirmation | GET /admin/confirmation-center/counts、GET /v1/tasks?confirmableBy=me、GET /v1/apps/{code}/memories?status=DRAFT、GET/POST /admin/config-releases[…/approve]（应用聚合） |
| admin SkillsPage/SkillDetailPage | mock-skill-precheck | skill-precheck（纯投影） | 数据面已真实（GET /admin/skills，既有）；checklist 自真实技能行确定性推导（独立预检端点未交付，`derived:true` 如实标注） |
| admin ManifestPanel | mock-manifest | api-manifest | GET/PUT /admin/apps/{code}/manifest（VITE_MOCK 分支收口进 api-manifest） |
| admin AppChatPanel | mock-artifacts | api-artifacts | POST /admin/artifacts（multipart）、GET /admin/artifacts/{id}/download（VITE_MOCK 分支收口进 api-artifacts） |
| webapp SessionList / App.vue | mock-session | api-session | GET /v1/apps/{code}/sessions?mine=true；truncateTitle 迁出为纯函数 |
| webapp AssistantChat | mock-task/mock-plan | api-task/api-plan | submitTask→POST /v1/apps/{code}/tasks；任务推进改真实端点轮询（demoAdvance 演示驱动移除） |
| webapp cards/TaskCard | mock-task | api-task | GET /v1/tasks/{id}/events + /v1/tasks/{id}（轮询游标增量） |
| webapp cards/PlanCard | mock-plan/mock-task | api-plan | confirm→POST …/plans/{planId}/revisions/{rev}/confirm、execute→POST /v1/tasks/{taskId}/execute；计划投影经 confirmableBy=me 桥（见登记①） |
| webapp demo-flow.ts | mock-task | （演示示意数据内联） | 不再 import mock-*；真实会话返回空流 |
| IntegrationConsolePage / ModelManagementPage / AppRunRecordPage（RunDetail） | —（本就真实） | — | integrationClient(api)/api()/useApi 既有真实端点，本轮仅核对 |

## 二、mock 文件保留/删除清单

**零删除**。mock-*.ts 全部保留——按口径"仅剩测试/演示消费则保留"：

- **测试直测**（vitest 契约锁定，语义不变）：mock-task、mock-plan、mock-session、mock-session-detail、mock-memories、mock-rules、mock-plugins、mock-mcp、mock-confirmation-counts、mock-confirmation-matrix、mock-skill-precheck、mock-manifest、mock-transport 等。
- **演示运行时消费**（VITE_MOCK=true 经 mock-transport / mock-api-m52 / api-manifest / api-artifacts 动态引用）：同上域模块 + mock-artifacts、mock-state-extensions、mock-runtime-extensions、mock-onboarding-extensions、mock-selected-tools、mock-tool-catalog、mock-config-releases、mock-run-failures、mock-response-artifacts、mock-skill-access。
- **页面 import mock-\* 清零**：`grep -rn "mock-" admin/src webapp/src` 仅剩注释与 api 层内的 VITE_MOCK 分支（api.ts→mock-transport 为既有 A28 机制，非页面代码）。

## 三、vitest 基底对比与 build 结果

- 改前基底：**13 failed | 621 passed (634)**（§9.4 基线一致；失败=assertion-editor 6 + mcp-registration 2 + run-detail-navigation 4 + satellite-planning 1，均与本卡无关）。
- 改后（终轮）：**13 failed | 621 passed (634)，失败集合逐条与基底相同（diff=空）——零新增失败**。
- 测试适配（断言语义保持，数据源桩从 mock-\* 换 api-\*，桩异步委托保留的 mock 域）：
  `task-console.test.ts`、`session-drill.test.ts`、`plan-detail.test.ts`（mountPlan 改异步+route.query）、`asset-pages.test.ts`（四页+Approvals 桩改 api-\*；顺带修复该文件 24 处既有 TS 联合类型 narrowing 噪声）、`manifest-panel.test.ts`（页面传输层断言改 api-manifest）、`skill-precheck.test.ts`（页面符号 fetchMockSkillPrecheck→fetchSkillPrecheck）。`webapp-assistant.test.ts` **零改动全绿**（26/26）。
- **vue-tsc：基底 67 错 → 0 错**。基底 67 含四资产页历史性错误 import 前缀（../../../shared 与 ../PageHeader 均不存在——四页自诞生未通过过 typecheck，属本卡范围文件故顺带修复）+ 共享 mock-task 1 处 + 测试文件 25 处；本卡新增代码零容忍。
- **npm run build 通过**（vue-tsc --noEmit + admin/webapp/assistant.js 三段 vite build + copy-assistant，exit 0）。

## 四、真实链路演示（验收核心）——方式与证据

**栈**：本分支四服务 jar（mvn package）起在 182xx（gateway 18200 / app 18201 / engine 18202 / skill 18203），
专用库 **m52_demo**（127.0.0.1:25432 项目专用 PG16.15 实例；除 CREATE DATABASE m52_demo 外未触碰任何库）。
全链 flyway 迁移自举 + **V901 种子落库**（app.flyway_schema_history 901/demo seed；种子行经 psql 验证：
app workspace_manifest=2、engine.memory_entry demo=2、skill 目录/规则/插件/MCP 发布记录均在位）。
Redis/MinIO 未起（engine 健康探针 503 属预期，不阻塞数据面）；model-gateway/monitor 未起（登记⑧）。

- 起栈脚本：`start-stack.sh`（幂等；engine 经 PropertiesLauncher+`shim/` 外挂补装配——
  **Memories 双构造器阻塞 engine 全上下文启动**为平台缺陷（wt-m53 缺陷登记①同源，归口服务卡），
  shim 以 BeanFactoryPostProcessor 挂确定性实例供给器，仅补装配不改业务语义，修复合入后可下线）。
- **API 级证据**：`api-evidence.mjs` → `api-evidence.log`（页面消费的同一批端点经网关逐个抓真实响应）：
  任务受理/列表/详情(数字 id)/事件游标、确认中心四类计数+confirmableBy=me、记忆
  写→读→PATCH CAS **409 MEMORY_VERSION_CONFLICT 实录**→正确版本更新→DELETE REVOKED、
  会话 mine 列表、规则目录+真实登记（DRAFT v1）、技能库列表（V901 种子 55KB 目录）、
  插件来源/插件目录（V901 种子）、MCP 登记→按 id 读→**真实连通测试**（FAILED UNREACHABLE——
  服务端 StubMcpTransport 如实报告桩不建连）、集成控制台应用/发布记录（V901 ACTIVE 发布指针）、
  manifest 读（V901 种子 manifest revision1+validation ok）。
- **浏览器级证据**：`browser-demo.mjs` → `screenshots/01-10*.png`（playwright 驱动真实 SPA）：
  登录（dev 模式）→任务工作台（**5 条真实任务**，公网 id 可见）→记忆管理（**真实记忆行**）→
  规则管理（**4 条真实规则**=V901 种子+演示登记）→插件/MCP（各自真实形态/空态）→
  确认中心（真实四类计数 0/0/0/0=角标 total）→集成控制台（**真实应用上下文+ACTIVE 发布指针
  demo-release-satellite-planning-001+摘要/生效范围**）→会话详情/计划详情（无上下文深链如实空态）。
  浏览器 0 个 pageerror。
- 票据口径：SPA 登录=网关 `/auth/dev-token` 真实出票（tenant local-v12）；V901 种子租户为 demo——
  种子租户数据面经本地铸造 HS256 票据（与 ServiceTokens 同构、服务端同密钥真实验签；wt-m53 同先例），
  浏览器演示经 playwright 拦截 /auth/dev-token 响应注入 demo 票据（登录流程与其余链路全部真实）。

## 五、诚实登记项

1. **task→plan 桥与计划确认**：task→plan 全量读取端点缺位——PlanCard 计划投影经
   `confirmableBy=me` 解析（已确认且凭据有效修订不回流→刷新后无计划投影，卡片如实隐藏）；
   PlanDetailPage 修订读需 ?taskId=&revision= 深链上下文，steps 固化清单无读取端点（表格如实为空）。
2. **任务详情/事件/取消/恢复仅接受内部数字 id**，受理/列表返回 public_id（字符串）——浏览器面无桥
   （400 MethodArgumentTypeMismatch，engine 日志留痕；wt-m53 同款以 psql 桥绕过）。工作台列表/受理
   正常；卡内展开/任务卡轮询如实降级（错误可读、页面不崩）。归口服务卡。
3. **网关代理三缺陷**（wt-m53 缺陷登记②③④同源）：任务计划端点未登记网关目录、PATCH
   ProtocolException、DELETE 体丢弃——本地演示期页面 dev 代理以 `AGENT_DIRECT_SERVICE_PROXY=1`
   按服务前缀直连 182xx（web/admin/vite.config.ts，dev-only；生产构建不受影响），API 证据中
   PATCH/DELETE 标注 `(direct 18202)`。归口网关卡。
4. **MCP 连接无 GET 列表端点**：页面列表以本地已知 id 索引（localStorage）逐条 GET 聚合（均为
   服务端真实行）；他端创建的连接不出现。登记表单字段映射：transport STDIO/HTTP→STREAMABLE_HTTP、
   SSE→SSE_LEGACY、code 由名称派生、timeoutMs 默认 30000（页面表单不变）。
5. **连通测试 FAILED UNREACHABLE** 为服务端 StubMcpTransport 如实应答（测试/演示桩不做真实建连，
   wt-m53 登记⑦同源）——端点链路真实、桩边界如实。
6. **发布评审"拒绝"无独立端点**（REJECT 返回 501 形错误对象如实提示）；**高危操作无全局待审
   端点**（列表如实留空；InlineApproval 按 run 查询在运行详情侧）。
7. **规则 scope 模型差异**：真实 scope 为对象（app/stage/toolClass），页面 scope 档位
   （DATA/OPERATION/OUTPUT，mock 时代口径）无对应键——登记 scope={app}，chips 显示按 DATA 映射。
8. **发布评审聚合=DRAFT 配置发布**（各应用 /admin/config-releases 过滤 DRAFT），与
   counts 端点 publishReview 口径的一致性以 counts 端点为权威（页面 Tab 计数独立推导）。
9. **REVOKED 记忆不可显式查询**（服务端仅 ACTIVE/DRAFT，REVOKED 退出检索）——页面"已撤销"
   chip 在真实端点下如实空。
10. **预检投影无独立端点**：checklist 由真实技能行确定性推导（`derived:true`），端点交付后整行替换。
11. **model-gateway/monitor 未起**、任务执行派发停受理态（wt-m53 ⑧⑩同源）——模型真实出话未覆盖。
12. **Memories 双构造器**（登记①）与任务 id 契约缺口（登记②）为**平台缺陷，本卡不改后端源码**，
    以 shim/证据脚本绕过并留痕，修复归口服务卡。

## 六、本卡改动范围

`web/**`（11 个新 api 层模块 + mock-api-m52 演示路由 + 页面数据面切换 + 测试适配 +
admin/vite.config.ts dev 代理分流 + 四资产页历史性 import 前缀修复）
+ `docs/evidence/2026-11-18-M5-2/**`（本 README、start-stack.sh、shim/、api-evidence.mjs/.log、
browser-demo.mjs、screenshots/）。后端源码/台账零改动。
