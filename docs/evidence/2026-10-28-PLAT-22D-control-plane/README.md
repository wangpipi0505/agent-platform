# PLAT-22 D 段（T22D）：控制面收口盘点 + §11.9 六条演示 — 证据目录（2026-10-28）

- 基线：agent-platform HEAD `bc51df8`（T22C）、ontorepo HEAD `16d9698`，两仓工作树除既有未跟踪目录外干净（开工 `git status` 已核）。
- 任务口径：盘点/接线/演示执行型——能接线的接线，不能接线的以"盘点+缺口清单"交付；演示以既有测试与真实服务执行并归档证据。**禁止 git add/commit/push（本卡全部改动在工作区）**。
- 设计依据：《平台侧优化方案与实施细则-20261027》§11.7（1857–1880 行，统一控制面六共享对象+六控制面要求）、§11.9（1892–1912 行，六条验收口径逐字）；台账 §2 PLAT-22 行、§3 T22D 行；本体台账 §7/§2（O 系列待验收 7 项现状）。

## 0. 执行方式与边界（先读）

| 项 | 口径 |
| --- | --- |
| 真跑锚点（本卡新跑） | ① `node scripts/verify-capability-package.mjs` 全套（C 项接线红→绿+回归，§1）；② `mvn -pl skill-registry test`（AdapterRegistryZeroChangeTest + SkillProcessRegistrationTest + SkillProcessRoutingTest，**19/19 绿**，含 FakeProcessWorker 真实子进程崩溃路径实跑，§2） |
| 六服务未起（如实声明） | 本轮**未起六服务**。理由：服务级活证据已有 2026-10-27 G-10 首跑（`docs/evidence/2026-10-27-S1-acceptance/`：六服务 UP + dev-token + 注册期鉴权四例 + 发布门禁 409/豁免绑定/撤销全真跑）；此后产品侧对演示 1/5 涉及的注册/门禁/pin 面无行为变更（22C 只动 skill-registry 分发内层），其行为由 §2 的 19/19 定向复跑覆盖到 HEAD。本机无模型端点、ontorepo 未部署（S1 §3/§5、路线一验证报告 §4 已记载），六服务重跑无法补齐评测/本体真跑，只会复现 S1 的部分覆盖。**因此六条演示中"真跑"= 既有真跑证据（含 G-10）+ 本卡两个新锚点；其余为代码路径验证，逐条标注。** |
| ontorepo | 只读引用其测试与证据（OrS02/ReleaseBinding 等类名与结果取自本体台账与仓内测试文件），**未修改 ontorepo 任何文件**。 |
| C 项接线（唯一产品文件改动） | `scripts/verify-capability-package.mjs`（做前已在过程报告声明）：校验器内置注册面与 T22C 后运行层现状同步。详见 §1。contracts/** 零改动（route1 声明里"PROCESS 归 T22C"字样是 22A 时点历史记录，按只读纪律未动）。 |

## 1. C 项接线：校验器注册面与 T22C 现状同步（先红后绿）

**缺口来源**：T22A 时校验器把 PROCESS 标为"适配器未注册（归 T22C）"（`REGISTERED_ADAPTERS` 四类 + `PENDING_ADAPTER_OWNER='T22C'`）；T22C（`bc51df8`）已使 AdapterRegistry 五单元全注册。声明层诊断文案已过时——新声明 PROCESS 会被错误告警"需要新增适配器"。

| 步骤 | 命令 | 结果原文 | 文件 |
| --- | --- | --- | --- |
| 红（改前） | `node scripts/verify-capability-package.mjs --file docs/evidence/2026-10-28-PLAT-22D-control-plane/fixtures/demo2-local-algorithm-process.json` | 0 错误 **1 过时警告**：`kind=PROCESS：适配器未注册（现状注册面：HTTP_SYNC/HTTP_ASYNC/MCP/ALGORITHM）。需要新增适配器——实现归 T22C…` | `c1-red-process-stale-warning.out` |
| 改 | `scripts/verify-capability-package.mjs` 仅两处：`REGISTERED_ADAPTERS` 增 PROCESS 行（ProcessExecutionAdapter，algorithm-process/1）+ `PENDING_ADAPTER_OWNER`/头注释改为"五类已全注册，新 kind 先扩 AdapterRegistry 再解本表留白" | — | 改动清单见 §7 |
| 绿（改后） | 同上命令 | 0 错误 **0 警告**，EXIT=0 | `c1-green-process-registered.out` |
| 回归·绿锚点 | `node scripts/verify-capability-package.mjs`（默认全集=路线一） | 通过：1 个声明，0 错误 0 警告，EXIT=0 | `d1-green-route1-default-set.out` |
| 回归·三红例 | 22A 三负例夹具逐个重跑 | 仍全红：缺绑定 1 错/明文凭据 3 错/未知 kind 1 错 1 警告，EXIT=1×3（错误数与 22A 证据 §4.2 逐条一致；未知 kind 告警诊断文案更新为五类注册面） | `c1-red-regression-invalid-{missing-tool-binding,identity-plaintext,unknown-kind}.out` |

夹具 `fixtures/demo2-local-algorithm-process.json`：路线二（PROCESS + ontology.mode=NONE）最小声明演示件，**文件内多处明示"演示夹具，六门布尔值仅为 schema 形态占位，不构成真实验收"**；它同时是演示 2"只新增能力包声明"的形态证据（无 skillRef——不引用技能中心既有条目）。

## 2. 真跑锚点：注册表分发 + PROCESS 注册/路由（HEAD `bc51df8`，工作区含本卡改动）

命令（仓库根，同 22C 证据口径）：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot" && source .tools/env-load.sh && mvn -B -pl skill-registry test "-Dtest=AdapterRegistryZeroChangeTest,SkillProcessRegistrationTest,SkillProcessRoutingTest" "-DargLine=-Xmx1024m"`

结果（`d2d4-mvn-registry-process-tests.log`，BUILD SUCCESS，3.3s）：

| 测试类 | 结果 | 覆盖的演示 |
| --- | --- | --- |
| `AdapterRegistryZeroChangeTest` | **8/8** | 演示 2/4：TEST_ECHO 测试 kind 零改代码接入（实现 SkillAdapter+注册即完成注册期放行与执行分发）+ 五既有 kind 零回归 |
| `SkillProcessRegistrationTest` | 6/6 | 演示 2：PROCESS 注册期语义（仅 SYNC、禁 endpoint/httpMapping、契约规范化） |
| `SkillProcessRoutingTest` | 5/5 | 演示 2/4：PROCESS 执行路由语义（403/502/504 原样冒泡等） |

日志内**真实子进程执行记录**（非 mock）：`FakeProcessWorker, exit3` → `PROCESS_CALCULATION_FAILED ... message=算法进程计算失败（exitCode=3），stderr 尾部: boom-stderr: 故意退出 3`，并落 `dumpDir` 失败转储——崩溃用例在当前 HEAD 实跑通过。

## 3. §11.9 六条演示（逐条状态表）

状态图例：**真跑** = 测试/服务真实执行（含既有真跑证据与本卡新锚点）；**代码路径验证** = 以仓内代码/声明层证据说明，未起新链路；**缺口** = 如实列明。

### 3.1 新 HTTP 软件（§11.9 第 1 条）——**真跑（四门证据链）+ 缺口（自动装载未实现）**

验收口径："只新增能力包和工具声明，不改平台代码，完成连接→契约→评测→发布"。

| 环节 | 状态 | 证据链 |
| --- | --- | --- |
| 能力包声明 | 真跑 | 路线一重新声明 `contracts/capability-packages/route1-visibility.json`（22A，`a439557`）；本卡重跑默认全集通过（`d1-green-route1-default-set.out`，0 错 0 警）；三负例仍被拒（§1 回归） |
| 连接门 | 真跑 | 路线一验证 5 项已发布技能连接测试 SUCCEEDED（真实 DEBUG 调用 call 215–218，非 mock）——`docs/evidence/2026-10-27-route1-visibility-agent/README.md §2`；声明 `verification.connection=true` |
| 契约门 | 真跑 | 技能经 OpenAPI 3.0.3 机器合同导入 + Schema 校验 + 契约收敛（同上 README §2）；异步对账契约与 PLAT-06 冻结形态逐字段一致（22A 证据 README §7 映射表） |
| 模型门 | 真跑 | 选型验收命中率全部 1.0（≥0.8 门禁）（同上 README §2）；声明 `verification.model=true` |
| 评测门 | 真跑·缺口 | 预检实测 **409 EVAL_GATE_BLOCKED 三件套齐全**（`2026-10-27-S1-acceptance/README.md §2`）+ 豁免绑定 sha 不匹配不放行 + 撤销可用（同节四例）；**但评测断言对自由文本 CHAT 不可用（整份 output 精确 JSON 全等，正确答案仍 REJECTED）= 已登记产品缺陷**，声明如实 `evaluation=false`+豁免留痕（route1 `verification.exemptions[0]`） |
| 发布门 | 真跑 | S1 六服务真跑：未知应用 400 APP_NOT_FOUND、门禁不再固定 BLOCKED（同上 §2）；门禁分层/豁免模型 `PublishGateTest` 11/11（PLAT-02，`88f12a8`）+ 前端同屏 T02.2（292/292） |
| "不改平台代码" | 达成（声明期） | 22A 零平台代码改动；本卡校验器接线也只是构建期脚本，非运行链路 |
| **缺口** | — | **能力包→平台发布的自动装载未实现**：声明通过后仍需在技能中心/应用管理按既有流程建技能与应用（两张皮）；装载归下轮（§4 建议卡 P-1）。评测门禁 CHAT 缺陷未排卡（route1 `implementationGaps[4]`） |

### 3.2 新本地算法（§11.9 第 2 条）——**真跑（四类用例 + 零改接入）**

验收口径："只新增能力包（+已注册的 PROCESS 适配器），不改分发主链，完成成功/超时/崩溃/产物四类用例"。

| 用例 | 状态 | 证据链 |
| --- | --- | --- |
| 成功 | 真跑 | T21a `ProcessAdapterTest`（10/10，`4be5715`）：信封 stdin/输出 schema 校验/退出码语义（T21c 后 8 类 64/64） |
| 超时 | 真跑 | T21a：超时杀树 `ProcessTreeTest` 3/3 + Windows 实机 `taskkill /T /F` 父子三进程均终止（`docs/evidence/2026-10-27-PLAT-21a-process/`） |
| 崩溃 | **真跑（本卡 HEAD 复跑）** | §2 日志：FakeProcessWorker exit3 → `PROCESS_CALCULATION_FAILED` + stderr 尾部 + 失败转储 dumpDir；T21d 失败转储/审计事实（`8ada745`，模块 270/270 子集口径） |
| 产物 | 真跑 | T21a 产物回收/1MiB 截断（ProcessAdapter 测试内）；产物引用名声明形态 = 夹具 `artifacts:["result.json"]`（§1） |
| 只加声明不改分发主链 | **真跑** | 22C：产品主代码 `TEST_ECHO` 零命中 + `switch (contract` 零命中；本卡 HEAD 复跑 §2 的 19/19（含零改接入 8/8） |
| 声明形态 | 真跑 | 夹具 PROCESS 声明校验通过（§1 绿）——schema 枚举冻结表达 + 注册面诊断已同步 |
| **缺口** | — | 与 3.1 同根：声明→技能中心 PROCESS 技能注册的自动装载未实现（运行仍按 T21b 形态注册）；真实算法 worker 未交付（X-1，台账 PLAT-21 余项）；取消无生产接线、files 通道后续卡（T21d 降级如实） |

### 3.3 新本体约束（§11.9 第 3 条）——**真跑（既有测试）+ 代码路径验证（平台侧强制点）**

验收口径："只新增/引用本体发布，不改智能体代码，contextPack 自动注入、guard 拒绝可解释、部门隔离可验证"。

| 子项 | 状态 | 证据链 |
| --- | --- | --- |
| contextPack 自动注入 | 真跑 | T13（`7839085`）：`OntologyContextPacks`（只发 X-API-Key、5s/15s 超时、256KiB 上限、不跟随重定向、TTL 5min/容量 64、失败不缓存）+ `ContextPackText`（四段固定顺序、8000 截断）+ `RunService` 冻结段注入（正文进 `config.ontologyContext`、审计进 `snapshot.contextPack`）+ `ContextAssembler` 在 `${` 守卫后追加`[本体上下文]`；测试 8/8 + 编排者复跑 14/0/0（台账 T13.1 行） |
| 运行期 release 解析（不改智能体代码的前提） | 真跑 | T18.2（`a31bc95`）：快照缺 releaseId 按绑定解析（30s TTL/负结果不缓存/仅 BINDING_NOT_FOUND 404 fail-open），18/18（本卡独立核 LEDGER 行 + `docs/evidence/2026-10-28-T18.2-binding-resolution/`） |
| guard 拒绝可解释 | 真跑（服务级） | S1 六服务真跑四例（`2026-10-27-S1-acceptance/README.md §1`）：`SKILL_AUTH_SCHEME_CONFLICT`/`SKILL_AUTH_SCHEME_INVALID` 均带字段名与可执行下一步文案；错误七字段信封（semantic/code/category/retryable/userMessage/detail/traceId）T05 双半（`bdc663f`/`0b704d9`） |
| 部门隔离 | 真跑（本体侧） | ontorepo `OrS02ConsumptionIdentityTest` 四负例（本卡只读核实存在）：dept_scope 驱动真实过滤/撤销立即生效（:40）、缺 dept_scope 缺省零访问且 issue 拒绝（:95）、非属主不能发 issue 且 dept scope 不得扩空间授权（:149）、**空间 A 的 key 打空间 B release → 403**（:245，O-09，`1b43fba`） |
| 平台侧对应 | 代码路径验证 | run 准入租户一致性校验（`RunService.java:317`（initial 与 current tenant 相等）/`:342`（actor 与 run.metadata tenant 相等））；技能契约 guard roles/attributes 声明（route1 identity.guard）；绑定按 platformTenant+appCode+environment 三元组（T18.0 `ReleasePins` 状态机，18/18） |
| **缺口** | — | dept 属性的**运行期强制点在本体侧网关**（dept_scope 是本体消费面概念），平台侧 guard.attributes 仅为声明+守卫输入，引擎无逐属性强制点（如实标注：跨侧"部门隔离"是一条链两段执行，非单侧闭环）；本体发布未部署，端到端 contextPack 注入未在真服务上跑过（ontorepo 未部署，路线一验证报告 §4） |

### 3.4 路线切换（§11.9 第 4 条）——**代码路径验证 + 真跑（分发层）+ 缺口（运行层切换面）**

验收口径："同一个应用把 routeProfile 从 HTTP 切到 PROCESS（或反向），应用/提示词/工具语义不变"。

| 子项 | 状态 | 证据链 |
| --- | --- | --- |
| kind 分发正确性 | 真跑 | §2：注册表五单元分发 8/8（本卡 HEAD 复跑）；22C 证据 §1：五内置单元与原 switch 各臂**执行语义零搬运**（HTTP 同步/异步/MCP/PROCESS/ALGORITHM 逐臂对照），未注册 kind 执行期同码（PARAM_ERROR）仅正文升级 |
| 同一契约切 kind 后分发正确 | 真跑 | T21b `SkillProcessRoutingTest` 5/5（本卡复跑）：PROCESS 路由 403/502/504 原样冒泡；T21b `SkillProcessRegistrationTest` 6/6：同一技能目录按 kind 施加各自契约形状（PROCESS 仅 SYNC/禁 endpoint） |
| 契约层"语义不变" | 代码路径验证 | 契约 §15（`contracts/skill-invocation.md`，freeze 登记）：两层 kind 关系定死——声明层 kind（五类）与运行层 adapterType×invocationKind 组合；工具的 inputSchema/outputSchema/exposure/confirmRoles 与 kind 正交（route1 声明同 6 工具绑两个不同 interface 即为声明层实例：space-visibility-submit→http-async、其余→http-sync，工具语义字段不变） |
| **缺口** | — | **routeProfile 是声明层字段，无运行层落点**：应用运行走 `config.skillRefs` 钉版本，run 记录无 route 字段，"同一应用切 routeProfile"没有可操作的运行面开关（切换=改应用配置重发布，非 routeProfile 翻转）；自动装载（P-1）落地前本条只能算分发机制就绪、切换面未实现。提示词语义不变部分：promptRef/modelBinding 与执行方式解耦（声明层即分字段），无证据反驳 |

### 3.5 兼容性（§11.9 第 5 条）——**真跑（pin 三卡 + 拦截）+ 缺口（本体侧破坏性变更门禁）**

验收口径："旧应用 pin 旧 release 仍可运行；新 release 的破坏性变更在发布前被拒绝或标记"。

| 子项 | 状态 | 证据链 |
| --- | --- | --- |
| 发布写 pin | 真跑 | T18.0（`4caf8af`）：`ReleasePins` 状态机 DISABLED/SKIPPED/PINNED/FAILED、事务外调用、fail-open 缺省/fail-closed 502；红 18 例 14 失败→**18/18**（`ReleasePinsTest` 10 + `PublishPinTest` 8，`docs/evidence/2026-10-28-T-pin/`） |
| 运行期按绑定解析（旧应用不抄 releaseId 即可运行） | 真跑 | T18.2（`a31bc95`）：**18/18**（红=编译 8 处+行为负例，`red-1-compile.log`/`red-2-behaviour.log`）；显式 releaseId 永远优先（旧应用 pin 语义） |
| pin 落库可观测 | 真跑 | T18.3（`a6e429e`）：`app.publish_pin`（V106，每应用一行 O(1) 反查）；红（编译 8 处）→**9/9** |
| 本体侧绑定实体 | 真跑（本体仓） | O-11（`e4c65d1`）：`release_binding` V26 + 部分唯一（同 app 同环境至多一条 ACTIVE）+ `ReleaseBindingTest` 9/9；O-12（`1b43fba`）：`SERVICE_ACCOUNT_BINDER`+`release.bind` 写权限分离，六类 36/36 |
| 破坏性变更拦截（平台侧） | 真跑 | 发布门禁 409 拦截 + 豁免绑定 sha/draftRev 生效、未匹配不放行（S1 §2 真跑四例）；契约冻结：schema `additionalProperties=false` 全程冻结 + 明文凭据/未知 kind 负例（§1 回归仍红） |
| 破坏性变更拦截（本体侧） | **缺口** | **未实现**：`ChangeImpact` 级别只用于 diff 展示（`ReleaseHistoryService.java:253-272`），五门禁（`ReleaseGatePolicy.java:12-18`）无破坏性项——O-04 待启动（本体台账 §7 行原文）。"新 release 破坏性变更在发布前被拒绝或标记"目前只有平台侧评测/门禁半边，本体侧发布门禁不拦 |
| **缺口（口径）** | — | "旧应用 pin 旧 release 仍可运行"的**端到端真服务运行**未做（需 ontorepo release+凭据，S1 §5 已记为未执行项）；本条以 pin 三卡测试 + T18.2 解析测试为真跑口径 |

### 3.6 统一观测（§11.9 第 6 条）——**盘点：4/6 维已有，package/route 未落，trace 贯穿部分**

验收口径："一次运行一个 trace_id，能查到 tenant/app/package/route/tool/release 六个维度"。

六维逐项（代码指针为本卡只读盘点所得）：

| 维度 | 状态 | 证据 |
| --- | --- | --- |
| tenant | 已有 | `engine.run.tenant_id`（V200）+ run 准入租户一致性校验（`RunService.java:317/342`）+ T19 租户级滑动窗口（`RunQuota.java:102-128`）+ usage summary（`UsageController`） |
| app | 已有 | `engine.run.app_code` + 运行中心分页过滤 `appCode`（`RunService.java:2338-2377`：tenant 隐式/app_code/status/时间/q）+ T19 app 级窗口（`RunQuota.java:132`） |
| tool | 已有（=技能引用粒度） | run 快照 `config.skillRefs` 钉版本（code+version+sha256）+ `engine.tool_call` 明细（V200/V220 external_job）+ `ToolCallController` |
| release | 已有 | T18.2：运行期解析的 `bindingId/releaseId` 写进冻结快照（`RunService.java:1141-1142/1368-1393`）+ T18.3：`app.publish_pin`（V106）落库反查 |
| **package** | **缺失** | 全仓 `capability.package/capabilitypackage/routeprofile` 仅命中 `AdapterRegistry.java:18`/`SkillAdapter.java:11` 的 javadoc（两层 kind 关系说明）；无表、无列、无 run 字段——capability_package_id 未落运行层 |
| **route** | **缺失** | route_profile 仅存在于声明文件；run 记录无 route 字段；运行中心无 route 过滤（§3.4 同根缺口） |
| trace_id 贯穿 | **部分** | ① 每服务入站接受/回显 `X-Trace-Id`（`admin-common TraceContextFilter`：入站合法 token 直用、否则 UUID，MDC traceId，响应回显）；② gateway→上游转发并回传上游 trace（`GatewayController.java:100/116-117`）；③ monitor→engine 转发（`OperationsService.java:243`）；④ 技能错误信封携带 traceId 进 run.error 七字段（`SkillExecution.java:642/913` + `AgentDriver.java:441-474`）；**⑤ 缺口：engine→skill 出站不转发 X-Trace-Id（`RuntimeClients.java:597-599` 只发 Content-Type/Accept/Authorization）——技能侧 trace 是本机新 UUID，与 run 的 trace 不同源**；⑥ 缺口：run 表无 trace_id 列（V200–V223 零 trace 列命中），六维查询不能按 trace 串起一次运行 |

**结论（如实）**：演示 6 不达标——六维中 package/route 两维未落（根因=能力包运行层装载未实现，P-1）；trace_id"贯穿"现有两段断点（engine→skill 不转发、run 无 trace 列）。"一次运行跨两侧可追"（§11.9 D 段验收）同样不达标：本体侧 trace 未接入平台 X-Trace-Id 链（ontorepo 侧无对应盘点命中，本轮只读范围未发现转发实现）。

## 4. §11.7 控制面盘点（逐项：已有/缺失/建议卡）

### 4.1 六条共享对象（§11.7 表）

| 共享对象 | 现状 | 判定 |
| --- | --- | --- |
| `tenant_id` | 两侧同源：平台 run/审计/配额带 tenant；本体空间/凭据按租户；T18.0 绑定写 `platformTenant` | **已有** |
| `capability_package_id` | 仅声明文件字段；平台运行层零落库（§3.6 grep） | **缺失** |
| `software_system_id` | 同上（声明 `software.system` 字符串，无 ID 对象） | **缺失** |
| `ontology_space_id` / `ontology_release_id` | 平台侧以 UUID 引用不复制（T13 快照 `ontology.space/releaseId`、T18.2 绑定解析、pin 透传 bindingId） | **已有（引用形态）** |
| `tool_id` 跨侧统一 | **未统一**：平台侧工具=技能 code+version；本体侧 O-02 投影 tool 随 operation 落库（exposure），两侧无外键/映射——T14.1 工具档案未做 | **缺失（两侧各自有 ID，无跨侧绑定）** |
| `app_id` / `skill_id` / `route_profile_id` | app/skill 已有；`route_profile_id` 缺失（§3.4） | **部分** |
| `run_id` / `trace_id` | run_id 贯穿平台内（gateway 代理/运行中心/SSE）；trace_id 部分贯穿（§3.6） | **部分** |

### 4.2 六条控制面要求（§11.7 列表 1–6）

| # | 要求 | 已有 | 缺失 | 建议 |
| --- | --- | --- | --- | --- |
| 1 | 统一目录 | 平台侧各目录齐（应用/技能/模型/运行中心/审计，`web/admin/src/router.ts` 全表已核）；本体侧有自己的 designer 台 | **两台互不导航**：平台台无"软件接入/本体"入口，本体台无智能体入口；无跨侧统一目录查询 | P-3（下轮）：能力包作为统一目录的根对象先落（依赖 P-1） |
| 2 | 统一契约注册 | contracts/freeze.json + 四契约哈希 + 七模块边界（verify-contracts 门禁）；能力包 schema v1 冻结（engineeringAddenda 登记 sha256）；技能版本 sha256 落库；本体侧 O-02 投影/exposure 落库 | 注册分散三处（contracts 冻结/技能库/本体投影），无"同一注册表"；能力包校验器未接入门禁 | P-2（小时级候选，下轮）：verify-capability-package 接入 verify-contracts（22A 留的决策点） |
| 3 | 统一生命周期 | 两侧各有 draft→validate→verify→publish（+平台豁免/回滚）：平台 PublishGate 11/11 + SkillCatalog.publish 预检（PROCESS_PRECHECK_FAILED 等）；本体五门禁+发布报告 | 状态机未合并；"本体发布和应用发布互相引用"只有 pin 单向写（发布应用→写绑定），本体发布事件不回流平台 | P-1 的生命周期分片 |
| 4 | 统一身份 | 平台 V107 `identity_mapping`（tenant+principal→subject_snapshot+mapping_key 不可逆 SHA-256）+ PLAT-12 X-User-* 注入；本体 O-07 agent_principal `{kind:AGENT,platformAppId}`、O-05 轮换+grace、X-8 服务账号双凭据面 | 配置在两侧分别进行（无一处配置的映射面）；mapping_key 与 agent_principal 仅文档口径对齐（O-07 行原文） | 下轮：以能力包 identity 区块为统一配置入口（依赖 P-1） |
| 5 | 统一审计/观测 | 审计记录页 + run 审计快照（contextPack/pin/动作计划）；T19 usage summary；错误七字段信封全链 | 即 §3.6 缺口：package/route 两维、trace 断点两处、本体侧 trace 未接入 | P-4/P-5（§4.3） |
| 6 | 统一管理台 | 平台导航按对象组织（智能体/运行/技能/模型…），**未按"路线一/二/三"组织（该半条达标）** | 无"软件接入"分区；两侧后台并存 | 随 P-1/P-3 |

### 4.3 缺口清单（建议卡，供下轮排卡）

| 卡 | 内容 | 依据 | 量级 |
| --- | --- | --- | --- |
| **P-1 能力包运行层装载**（PLAT-22 剩余主体） | 声明→平台运行链路：capability_package/software_system 落库、声明生成技能/应用草稿、routeProfile 进 run 记录、tool_id 跨侧映射挂接 | §3.1/3.2/3.4/3.6 四条演示的共同根因；route1 `implementationGaps[2]`；台账 T22D 行 | 人级（下轮主体） |
| P-2 校验器接入门禁 | verify-capability-package.mjs 接入 verify-contracts.mjs（22A 显式留白"接入决策归 T22D"；本轮只同步了注册面，未改门禁行为——避免在演示卡里改既有门禁） | 22A 证据 README §1/§5 | 小时级 |
| P-3 统一目录与管理台分区 | 软件接入/本体导航 + 跨侧目录查询 | §4.2 #1/#6 | 人级 |
| P-4 观测六维补全 | run 记录补 package/route/trace_id 落点 + 运行中心过滤 | §3.6 表 | 人级 |
| P-5 trace 贯穿补线 | engine→skill 出站转发 X-Trace-Id；本体侧接入同一 trace 链 | §3.6 ⑤⑥ | 小时–人级 |
| P-6 本体破坏性变更门禁 | O-04（本体侧）：ChangeImpact 级别接入发布门禁（拒绝或标记） | §3.5 缺口；本体台账 O-04 待启动 | 人级（本体侧） |
| 另记（非本段引入） | 评测门禁对 CHAT 不可用=产品缺陷未排卡（route1 implementationGaps[4]）；取消生产接线/files 通道（T21d）；真实 worker 联调（X-1） | 各处已登记 | — |

## 5. 本卡新跑/新产文件清单（全部未提交）

```
docs/evidence/2026-10-28-PLAT-22D-control-plane/
  README.md                                  # 本文件
  c1-red-process-stale-warning.out           # C 接线红：PROCESS 过时告警原文（EXIT=0 含警告口径，见文件尾 EXIT 行）
  c1-green-process-registered.out            # C 接线绿：0 错 0 警（EXIT=0）
  c1-red-regression-invalid-missing-tool-binding.out   # 22A 负例回归仍红（1 错）
  c1-red-regression-invalid-identity-plaintext.out     # 同上（3 错）
  c1-red-regression-invalid-unknown-kind.out # 同上（1 错 + 更新后注册面告警原文追记）
  d1-green-route1-default-set.out            # 演示 1：路线一默认全集校验通过（EXIT=0）
  d2d4-mvn-registry-process-tests.log        # 真跑锚点：19/19 + BUILD SUCCESS + FakeProcessWorker 崩溃实跑
  fixtures/demo2-local-algorithm-process.json # 路线二最小声明夹具（含"非真实验收"声明）
scripts/verify-capability-package.mjs        # 唯一产品文件改动（C 项接线，§7）
```

## 6. 回滚方式

全部改动在工作区、未提交：

```
git checkout -- scripts/verify-capability-package.mjs
rm -r docs/evidence/2026-10-28-PLAT-22D-control-plane/
```

即回到 HEAD `bc51df8`。回滚后 `node scripts/verify-capability-package.mjs` 恢复 T22A 时点行为（PROCESS 重新出现"归 T22C"过时告警——该告警本身是回滚的核对锚点）。ontorepo 零改动，无需回滚。

## 7. C 项接线改动明细（唯一产品文件）

`scripts/verify-capability-package.mjs` 三处，共约 15 行，零行为面变化除 PROCESS 诊断：

1. 头注释第 16–17 行：`PROCESS 未注册（SkillCatalog 现只允许 HTTP/MCP/ALGORITHM）…留白归 T22C` → `与 T22C 的 AdapterRegistry 五单元对齐…未注册 kind 指向归属卡`；
2. `REGISTERED_ADAPTERS`：增 `PROCESS → ProcessExecutionAdapter（PROCESS×SYNC，algorithm-process/1，T21a–d + T22C）`，四条既有条目改指 22C 实际单元类名；增注释声明"此表只做声明层诊断、运行层白名单唯一来源是 AdapterRegistry，新 kind 两处必须同步"；
3. `PENDING_ADAPTER_OWNER`：`T22C（A-01 适配器注册表）` → `下一张适配器卡（五类 kind 已全注册；新 kind 先扩 AdapterRegistry 运行层，再同步解本表留白）`。

验证=§1 五条命令（红→绿 + 路线一绿锚 + 三负例回归）；schema sha256 不变（`0456799d…`，两轮输出一致），freeze.json 登记不受影响。

## 8. 偏差

- **六服务未起**：派发单预期"参考 G-10 首跑做法"可起服务实测；实际以"既有 G-10 真跑证据 + 本卡两个零依赖锚点"替代，理由见 §0（无模型端点/本体未部署，重跑只复现已录覆盖；token 纪律）。这是口径偏差，不是漏做——S1 证据 README §5 的"未执行"清单（评测 A、异步 C、路线三直连 D）本轮同样未执行，维持其"未验"定性。
- **T22D 台账行口径为"控制面收口（共享对象、发布运营）"**，派发单将其细化为"盘点+六条演示+量力接线"并明确"不能接线的以盘点+缺口清单交付"——本卡按后者执行：P-1（运行层装载）确认超小时级，未强做，转 §4.3 建议卡。
- 演示 2 的"成功/超时/产物"三用例引用既有测试结果未重跑（token 纪律），仅"崩溃"用例因恰在 §2 复跑范围内而获得 HEAD 新证；四类的既有证据均为真跑（T21a–d 各卡证据目录）。
- 其余无偏差；ontorepo 零改动；contracts/** 零改动；web/**、.env、其它模块零改动。
