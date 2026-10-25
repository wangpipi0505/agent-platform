# D3b 后首条 published FLOW：下一有限接缝提案

日期：2026-10-20。状态：只读架构准备；未实现、未运行测试，不签收完整 D3/E。唯一工程及所有 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。本次唯一写入为本文，没有读取正在修改的 live app source/target，没有数据库、网络、进程、环境凭据、Git 或测试操作。

依据：工程 AGENTS、implementation-brief、contracts/README、IAM implementation plan Global Constraints 与完整 Task4/5；完整旧 `iam-d3e-first-published-path-proposal.md`；D3b payload proposal 与正式 `iam-stage-4-d3b-brief.md`。app 事实仅引用这两份 D3b 文档。engine/common/gateway 位置来自本次稳定源码，行号供定位，正式执行仍按符号核对。

## 1. 建议顺序与完成定义

建议 D3b 冻结评审后，依次实施 **N1 材料及真实 status → N2 owned worker → N3 真实受理与 app client → N4 gateway 信任装配及联合验证**。每段单独冻结和有限验证，不一次提交二十余文件。N1/N2 可以使用实际 E1b 受理组件创建 PG fixture；N3 必须通过原 RunController/RunService；N4 必须通过原 GatewayController。前两段准确称组件证据，不能把测试直接调用组件冒称门户链。

最终首正例是同一原 PORTAL source，经真实 gateway metadata 查询、独立 main exchange、真实 engine 接收、真实 D3b app payload、engine PG 同事务受理、原 owned scheduler、原 FlowDriver、真实 attempts/events/output，再由原创建或相同 requestId POST 返回完整原 status。不靠假 controller、只返回 runId、固定空 events/nodes、暂停 worker 隐藏输出竞态或手工调用第二套 driver。

生产 IAM 启动闭锁、`BLOCKED_IAM_SCOPE_ACCEPTANCE`、原 Task4/5 全量验收均保留。当前 root 提供的主服务基线 962 只是已知基线，本次未复测、不重新累计。隔离 authority/PG/端点用于未来有限验收；没有真实 IAM 也可继续这些软件切片，但不能解除生产门禁。

## 2. 已关闭前置与真实未接线处

| 位置 | 已有事实及直接后果 |
| --- | --- |
| common `authority/HttpCurrentAuthorityClient.java:66,103`、`AuthorityContext.java:25` | E1a retained 已实现。`authorizeRetained` 返回私构造、私有可信 Clock 的 `RetainedAuthorization`；`requireInitialFor` 刷新实际 frame，`requireBound` 验同对象/同 frame/时限。不是待实现接口。 |
| common `authority/ServiceRoutePolicy.java:52`、client `:51,191`、`TrustedJwtDecoder.requireServiceScope` | 唯一 engine→engine `run.advance` local 能力已存在。仅 resume、DELEGATED/PORTAL/app-version；HTTP guard 不因此接受 local 票。无需再提新 local framework 或假 HTTP 行。 |
| engine `application/RunAuthority.java:20` | 真实 load→resume→authorizeRetained→三种 scope 覆盖→recordCheck CAS→提交后 proof 终检已存在；禁止 ambient transaction；尚未由 scheduler 调用。E1b+I1 已关闭，不重开原协议。 |
| engine `infra/RunAuthorityStore.java:22–71` | `createPublished(retained,snapshot,inputs,refs,budget,mode,requestId,labels)` **拥有 INSERT**，返回 numeric run id，内部生成 public id。不是给已存在 run 附 authority 的方法。要求同 DataSource 写事务，catch 后标 rollbackOnly，绑定 TASK/FULL/PORTAL 主入口。 |
| 同文件 `:63`；`MaterialScopeStore.java:20,59,71` | 当前只写/读 run-snapshot、run-inputs、run-version-refs；scope 查询所有行并要求恰好 3，且 source decision/version 固定受理值。不能直接把动态材料塞入 KINDS，不能原样用于 worker 后继事实。 |
| `V216__run_authority_initial_materials.sql:12` | 已有真实 V216；kind CHECK 只有三种，`material_version char(64)` 为 SHA，`UNIQUE(run_id,kind)`；authority/scope 不可变触发器。旧提案的未创建 V216/通用 revision 表设想已过时。后续只能新连续迁移，本文不占号。 |
| `RunService.java:184,208,334,381,449,480` | 三次 requestId 查询仍 `select *`，旧 INSERT 仍独立，随后 nodes INSERT、`scheduler.created`、真实 `status`。E1b 并没有自然替换这段。 |
| `RunScheduleRepository.java:28–80` | claim 在取得网络 current 以前把 PENDING 改 RUNNING 并写 RUN_STARTED，无 authority 条件。仅改 driveRecovered 仍会先制造未授权材料。 |
| `RunService.java:1539`、`:883` | recovered 从 guard_ctx 恢复旧 Actor；drive 先读全部 run/nodes。所有实际消费点仍须接入，不得保留 protected run 的 Actor-only 旁路。 |
| `RuntimeClients.java:38,48,60,78` | GET/POST/stream 仍 `ServiceTokens.issue(actor)`；未知 service 默认 model。新安全 app 方法必须实际被 createInternal 调用。 |
| gateway `application/PublishedMetadataClient.java:15,23,39`；`api/GatewayController.java:34` | metadata 客户端已有严格四字段解析、固定 origin、deadline/no redirect，注释明确尚未接 proxy。proxy 仍 ANY、旧 issue。 |
| common `SecurityConfiguration.java:44–56`、`ServiceTokens.java:18`、`RouteCoverageVerifier.java:13` | Spring mode 验证后 IAM 启动仍拒绝；旧静态 issue 单读 JVM property；Ingress 不支持 ANY/通配模板。不能仅注入 trusted decoder 就假称真实部署已装配。 |

### 数字 versionId 的来源，避免扩大协议

main root 始终是 `app-version / code@versionNo / storedCandidateSha256`，没有数字 versionId 字段。gateway lookup 得到的四元组在 gateway 构建根时使用；engine 的数字 ID 来自**固定可信 app HTTP 返回、由 D3b 在真实 app PG 锁内验证过的投影**。engine 核 appCode/no/candidateSHA 对主根全等，appVersionId/versionId 为相等正整数，appVersionKey=code@no，随后将这个真实数字 ID 交给 E1b。

不从 body/header 补“可信版本 ID”，不增加 receipt/新 metadata JWT，不改 root。原 Task4/5 没有 gateway→engine 另传未签数字 ID 的要求。旧 D3b proposal §3 的“与其已取得的真实 metadata 四元组比较”和旧 D3/E §3.9 的交叉核对，应按上述真实来源理解，不能声称 engine 已收到 gateway 的同一四元组或数字 ID 已被 root 独立签名。app 域内 pointer 重核是 D3b 的责任，不声称跨 app/engine PG 全局事务。

## 3. N1：有限材料版本 + 原 status 的真实安全读取

下一正式 brief 建议首先只覆盖：

- `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java`
- 同包 `RunAuthorityStore.java`、`TraceStore.java`
- `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` 的 status/材料投影范围
- engine 下一连续迁移，建议用途名 `run_flow_materials`，编号实施时核源与授权库实际历史，不改 V216。

这是既有生产 writer/reader 的接缝，不新增一套材料框架。现 E1b `createPublished` 继续是唯一主 run INSERT；追加真实初始 run-state/budget/output/error scope。真实 nodes INSERT 由 N3 接入前，N1 测试可在同一原业务事务插有限 fixture node 后调用具体初始节点 scope 方法；报告必须说明尚未是 RunService 受理路径。

### 3.1 保留三种不可变根材料，扩展固定真实 locator

| 固定 kind 集合 | 真实位置与要求 |
| --- | --- |
| 现三种 initial kind | 原 publicId、accepted_snapshot_sha256 version 和已存 scope 完全保留；snapshot/inputs/version_refs 仍不可变。 |
| run-state / run-budget / run-output / run-error | run publicId；state 逐列固定，至少含返回的 status/event_seq、started/ended/stop_reason；预算及 nullable output/error 各独立实际内容。dispatch 心跳 owner/lease 不混入用户材料 state。 |
| node-definition / node-state / node-output / node-error | node numeric id，经 node.run_id 核真实 owner；state 包含 nodeView 字段和实际控制所用 winner/committed/released 等固定列，不 SELECT *。 |
| attempt-state / attempt-request / attempt-checkpoint / attempt-error | attempt numeric id，经 node 关联 run；claim 创建的 request_snapshot={}、checkpoint 默认{}也写初始 scope；不宣称覆盖后续 Agent checkpoint/response。 |
| run-event | run_event numeric id，经 run_id/seq 关联；固定投影 seq/type/payload/created_at，与原 events 响应保持。 |

最小迁移在同一 `engine.material_scope` 加单调 revision，扩有限 kind CHECK，将 material_version 改为可容纳十进制 revision 的文本，保留 initial kind 的 SHA64 格式条件。撤掉全局 `UNIQUE(run_id,kind)`，换成仅 initial kinds 的部分唯一约束；新材料按 producer/kind/id/version 唯一，scope 历史仍 append-only。revision 用这张表的数据库序列分配，回滚序列空洞合法；不建历史 payload 副本框架。历史 payload 已被原业务表覆盖时，旧 ref 必须拒绝，不能用新 payload 配旧 scope。node/event 的不可变版本也可使用同一 revision 机制，避免多套版本规则。

`RunAuthorityStore.load` 继续要求恰好三个合法 **initial** scope，但查询须限定这三个 kind；动态材料由当前 ref 枚举读取。不能简单把 `rows.size()!=3` 改成 `>=3` 并忽略缺失/重复/未知行，也不能把动态 scope 加入 Binding.equals 导致每次事件改变都被误当 immutable authority CAS 冲突。

当前 `MaterialScopeStore.validate` 强制所有 sourceDecisionId=admissionDecision。必须分成初始和派生两种有限验证：初始保持现精确不变量；派生保留原 owner/full-initial hash，sourceDecision/version 来自该次真实 current，许可要求及 dataLabels 是可信初始要求与**实际已消费父 scopes**并集。parent digest 必须指向同 run 已存 scope；不得伪造 parent 或把 candidateSHA 当 parent。公开 `AuthorizedMaterial` record 本身不是不可伪造凭证。

### 3.2 真实 writer 必须能够消费证明，不只是附加 helper

保留现 `read(MaterialRef,VerifiedAuthority)`；增加有限 `currentRefs(runId,...)` 和包内固定 locator writer，禁止任意 SQL/table/column 回调。TraceStore 的实际 claim/commit/fail/event/consume 增加显式 retained 参数或等价具体重载；protected run 从旧 Actor-only/raw writer 进入必须拒绝，不允许“新安全方法存在，但旧 writer 一样可改 protected row”。legacy mode、无 authority 的原 dev/test 行为仍按原功能回归，不对旧 run 自动回填。

request admission proof 使用 `AuthorityContext.requireBound`；worker proof 使用 E1a `requireVerified`、`RunAuthorityStore.strict` 与实际 dispatch/run 关系检查，不伪造 request frame。任何真实 payload mutation、scope 插入、原事件及原 CAS 必须在同一个 DataSource transaction；失败后即使调用方 catch 也不能提交一半。

运行写事务按 run→node 锁顺序，**先窄锁 run/control metadata，再 scope 判权，最后 payload SELECT**。不能直接在 TraceStore 默认 READ_COMMITTED atomic 内调用现 `MaterialScopeStore.read`：后者明确要求 RR/更强，否则抛 REPEATABLE_READ_REQUIRED。最小选择是对这些有限的受保护写动作使用同 TM 的专用 RR transaction（拒绝加入较低隔离 ambient），或在所有该 run 写方遵守同 run 锁的前提下实现包内锁保护读取；正式 brief 选前者，序列化冲突关闭本动作，不锁内联网、不无界重试。不要全局修改 legacy atomic 隔离等级。

精确数值摘要复用现 MaterialScopeStore.EXACT/normalize，而非旧提案泛称 Json.canonical 足够。扩展 nullable payload 时区分 SQL NULL→JSON null 与对象{}；status 再沿原 TraceStore.json(null) 展示{}。时间投影固定为同一 UTC 字符串规则。原 output_digest 字段语义不改，material payloadSha 独立计算。

### 3.3 status 真正调用受保护 reader

在现 `RunService.status` 增加明确 mode/实际 main frame 分支，或由原创建分支明确调用一个 `statusPublished` 私有方法；不能只新增没人调用的 MaterialScopeStore 测试方法。N1 的可测试入口是实际 RunService status 组合逻辑，后续 N3 创建和三个 requestId 命中必须复用它。

事务外窄读取 run owner/root；在实际当前 main frame 刷新 retained，再开启顶层只读 RR，在同一快照枚举 scope/node/event 元数据、逐份判权、读 payload、核摘要，物化原响应。禁止 select * 预读；同一个 status 不能每份材料开独立快照。返回前做本地 proof 终检；不得读事务内在线续票。

必须保留字段：

`runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused`，以及 nodeView 原字段和别名、events 的 seq/type/payload/created_at。pure FLOW 的 fileRefs 为空必须通过窄 metadata 确认没有 tool_call；如存在未迁移 tool 结果则拒绝，不能先读 result 再筛。不存在的 nullable output/error 也有明确初始材料，不能用缺 scope 解释为空。

本首链只授权**创建响应和重复 POST 响应内部的 status**。独立 GET `/admin/runs/{id}`、debug/status/v1 alias 需要真实 run metadata→root 解析及各自动作许可，目前不在已实现 main/local purpose 中；维持 IAM 拒绝，不把 POST 主票冒用为 GET 票，也不谎称完整监控/观察已迁移。

### 3.4 N1 验证边界

用实际 E1b createPublished、真实 PG、真实 signed main/local proof、TraceStore writer、RunService status；不 mock 材料 store。有限合法写读、payload与scope中间故障回滚、missing/错 digest/错 owner/root/current收窄、同 locator新revision/旧ref、SQL NULL/大精度小数/时间往返、RR混入READ_COMMITTED拒绝、writer捕获错误后仍回滚。SQL计数区分 metadata、payload 与写入；合法对照必须真的读取数据。

状态：只能记“有限 FLOW 材料及响应组件”，不记真实 admission、调度或门户成功。

## 4. N2：原 owned scheduler 与 pure FLOW 实际推进

依赖 N1。生产范围限现 `RunAuthority.java`、`RunService.java`、`FlowDriver.java`、`RunScheduleRepository.java`、`RunScheduler.java` 和 N1 的 `TraceStore.java` 必要接线；材料定义不另复制。无需 common 新能力。

现 `RunAuthority.current` 返回 VA，已在返回前做真实提交后终检；但 worker 随后的锁等待/执行/提交仍需保留同一私 Clock 和 ticket 截止。建议在现类提取 `currentRetained(long,ServiceCall)` 返回已验 `RetainedAuthorization`，原 `current` 委托并取 `.current()`；这是现证明的显式传递，不是新 receipt。未经核验的 public VA 或 `currentOnly` 不得进入受保护写方法。保留 I1 的 CAS内锁等待后终检与真实提交返回后终检。

1. claimNext/claimSpecific 共用分支先查 authority side row、grant有效、blocked状态；403/expired排除，503只在 retry_not_before 已到时允许有限重核。保留队列/slot/generation/lease，claim 仅占 dispatch，不把 PENDING 改 RUNNING、不写 RUN_STARTED、不读 payload。
2. worker 持有原 dispatch 后，用该 run 的真实 binding 构造 `new ServiceCall("engine","run.advance",binding.root())`。RunAuthority 在事务外 resume+authorize。不要捕获某个新建 HTTP 请求 proof 供 claimNext 领到的其它 run 使用。
3. 短事务中核完整 immutable binding、retained、dispatch owner/generation/lease；将 PENDING→RUNNING 与 RUN_STARTED/scope 同写。这里单独核 PENDING/RUNNING dispatch；`RunScheduleRepository.valid` 现只认 RUNNING，不能全局放宽 mayAdvance 让 PENDING 执行。
4. `driveRecovered` 不读 guard_ctx 取权限；protected run 的 `drive(long,Actor)` 也不得绕开，必须到相同 owned/current 路径。保留旧 guard_ctx 仅审计。每个新节点动作重新 current，prepare/claim/execute/commit 用同动作 proof；聚合新动作也显式取得/验证证据，禁止在 aggregate 写事务内 current 联网。
5. 所有判权/期限/503错误必须先于普通业务异常分类退出，记录 RunAuthority 阻断/退避并释放 slot；不能被 `catch(Exception)` 变成 RUNTIME_ERROR、DEFAULT_VALUE、NODE_FAILED 或 RUN_FINISHED。

允许纯节点为 START、END、CODE、CONDITION；CONFIRM 在 execute 前会涉及审批，不能算纯计算。复用原 FlowDriver，不写第二个 driver。N3 受理时做有限穷尽校验，N2 worker读取 snapshot 后再防御性核验，未知/外发依赖拒绝。

### 实际必须接的写读位置

| 现位置 | 接线要求 |
| --- | --- |
| `FlowDriver.scope:23` | 实际读取 inputs 与所有 node.output/error；父要求并集覆盖所有已消费材料，不能仅取图上直接前驱。 |
| `FlowDriver.prepare:41–148` | 入边 definition/state/output、CONDITION、ERROR_EDGE、ALL/ANY；SKIPPED/FAILED、winner、ABANDONED 与事件按原事务写 scope。ANY没有事件的状态修改也不能漏。 |
| `TraceStore.claim:75–147` | snapshot executionRules、node definition/state先授权；consume预算、node运行态、attempt初始 request/checkpoint/state/error、NODE_STARTED同事务。 |
| `TraceStore.commit:168–209` | live lease + original node CAS + dispatch generation + 本地 proof；output、attempt成功、两个原事件同事务；CAS零保留 SUPERSEDED_SUBMIT，不产生成功 scope/事件。 |
| `TraceStore.fail:211–232` | node/attempt实际 error/state与原 NODE_FAILED；节点CAS失败不得错写当前node的scope。 |
| `RunService.drive:1038` | DEFAULT_VALUE 目前 error update 在 commit 外；合并真实外层事务，error/default output/attempt/events/scopes共同回滚。 |
| `RunService.stopForBudget:1124` | 真预算业务失败的 run.error/state、各 CANCELLED node状态及 RUN_FINISHED；权限失败不得借此转换。 |
| `RunService.aggregate:1226` | 同快照/锁内已授权材料、prepare副作用、选中END或最后node输出、run state/output及RUN_FINISHED；不能继续 select * 预读。 |

纯 FLOW 不调用 TraceStore.freeze/response，也不消费后续 checkpoint；这些方法对 protected run 在未迁移前明确拒绝，不声称 attempt 全部用途已完成。attempt 默认{}初始 scope仍必要。

N2 有限测试通过原 RunScheduler reserve/created/poll→driveRecovered→FlowDriver，不手调 execute 代替 worker。覆盖 START→END 真实 output/attempt/events、CODE失败DEFAULT_VALUE原子性、CONDITION/ANY/ERROR_EDGE父范围、预算失败、过期dispatch CAS零、current 403/503时 RUN_STARTED/attempt/payload=0、退避不空转、旧无authority不领、proof等锁/提交后到期、不跨线程bind。原 PORTAL 到期、持久grant有效时真实 local DELEGATED 可推进；不据此声称 model RR2/原observer闭环通过。

## 5. N3：原 RunService 受理接上 D3b 与 E1b

依赖 D3b 关闭、N1/N2 关闭。主要生产范围三文件：`api/RunController.java`、`application/RunService.java`、`infra/RuntimeClients.java`；实际 engine 可信 beans 由有限装配文件提供，若当前正式装配尚未创建，则在本段新增一个具体 EngineAuthorityConfiguration 或先在隔离测试 context 显式装配，不能假称它已存在。

### 5.1 精确入站和 outbound API

真实 `RunController.run` 在 verified deployment mode=iam 时进入受保护 TASK/FULL/published 分支；要求当前 frame 的 caller=gateway、target=engine、action=`proxy.engine.post.RunController.run`、source/origin=PORTAL、root=app-version。入口仅 admin POST；相同方法的 v1 alias 不随之开放。

RuntimeClients 增加一个具体方法，例如 `published(String code,VerifiedAuthority current)`：固定 app origin + `GET /internal/apps/{code}/published`，`forward(current,new ServiceCall("app","app.published.read",current.call().resource()))`；不接受任意 path/target，不透传 source，不 resume 改期限。关闭 redirect，有界连接/读超时，错误保持 app 401/403/409/503 等可辨语义；不要被现 unwrap 统一变成502掩盖 pointer 冲突。所有 service switch 穷尽 app/skill/model，未知目标403。旧 get/post/chatStream 在 IAM 不得 fallback ServiceTokens.issue，即使 JVM property误设test也无成功通道。

取得 payload 后按第2节核 root 与真实数字 ID。**prepareSnapshot 以前**拒绝 CHAT、空图、未知节点、CONFIRM/SKILL/RETRIEVE/LLM/AGENT、显式 draft/evaluation/controlled/replay/session/history/material引用及未支持依赖；实际检查至少覆盖 config.skillRefs/promptRefs/modelEntryId/modelBinding/tools/frozenPrompts/dataSources、node.skillRef/promptRef/modelOverride、dependencyVersions.modelRefs。否则 prepareSnapshot:773/866 会先发 skill/prompt HTTP。保留原 app guard、InputDeclarations、GraphRules DAG、预算和输出映射。合法空依赖集合可接受，不能只以字段存在一律误拒发布后的空容器。

首段显式 versionNo 暂不开放；当前原代码会走 `/versions/{no}`，D3b 未迁移该入口，不能偷偷当 published 已支持。draft/debug/evaluation/CHAT/reservation原legacy用途保留，IAM仍关闭。

### 5.2 原 INSERT 的替换必须在原事务位置

1. 取到合法 snapshot、完成准备后，再 `AuthorityContext.requireInitialFor(actor,actualRoot)` 得受理 retained；current须覆盖全部即将保存材料 requirements。初始 A/B、current A 不得先commit再让status失败；initial/scope不得被缩成A。
2. 保留 `RunScheduler.Admission.persist`、原 requestId advisory lock 和短本地 transaction；锁等待后 requireBound。在新建分支**以 createPublished 替换旧 run INSERT**，不要先旧INSERT再createPublished，不添加 insertNew/upsert旧run。
3. 传实际 frozen/source版本校验后的 snapshot、inputs、`Json.tree(versionRefs(...))`、`Json.tree(initialBudget(...))`、FULL、原requestId及真实labels。E1b自行生成publicId；后续取 numeric id 对应 public_id，删除该IAM分支无效的预生成字符串，避免 input_ref/publicId不一致。
4. 原 nodes INSERT 仍在该事务；N1具体节点初始scope随每行写入。完整 run/authority/initial/dynamic/node/material集合写完、callback退出前再 requireBound和覆盖屏障；真实 tx.execute 返回之后、scheduler.created之前再本地终检。提交后期限变化无法“回滚已提交”，但禁止启动该失效请求的调度通知/泄露响应；已受理行由合法后台grant决定后续推进。
5. 成功保留 scheduler.created + 原完整status；不能用 runId-only响应回避 N1/N2。

### 5.3 三处 requestId 的处理必须共享

首次命中、容量拒绝后advisory锁重查、persist锁内竞态重查，都只查 owner/publicId/id/app/root等窄metadata。锁内已存在分支只返回窄标识；**退出写锁事务之后**，在同main根/current及N1受保护快照里读取inputs/version、执行原verifyReplay规则并返回完整status。不能锁内在线刷新；也不能调用现RR reader加入原READ_COMMITTED事务。

合法相同输入/同版本只一个run，旧grant/initial/origin不变，不重复业务执行。current收窄、旧缺scope/authority、错root均不得先读inputs/snapshot。相同 requestId 若发布指针已换版，新main根不匹配旧run，应409/拒绝，不能换root或覆盖authority；要保留跨版历史重放需后续run根解析能力，本首段不假称完成。调度是否允许幂等重入重新唤醒原已接受队列可沿原行为，但必须仍是owned/current且不得重复已提交节点。

N3真实HTTP+PG验证用实际 RunController/RunService/RuntimeClients + D3b实际app进程/context、真正guards；gateway caller可由既有fixture出主票，明确**未经过真实gateway**。覆盖pointer变化→run/scopes/nodes新增0、依赖拒绝→skill/model/prompt发送0、受理各写点故障全回滚、三处并发重放/容量满、worker抢先完成后status材料仍正确、initial A/B合法受理后收窄A读/下一动作拒绝。不要省掉正常响应的 node/event/attempt 持久证据。

## 6. N4：gateway 实际 proxy、信任覆盖与最终联合证据

依赖 N3。可先把 common覆盖/旧mode冲突保护单独作为N4a（最多 RouteCoverageVerifier、SecurityConfiguration、ServiceTokens及定向测试），再做N4b真实GatewayController和具体装配；不把N4a绿色记整链。保留原全局IAM闭锁，首链测试只使用明确隔离receiver配置，并如实标明这是实际生产业务类的隔离组合，不是已可部署IAM服务。

实际 proxy 的 IAM 分支仅精确 POST `/admin/apps/{code}/runs`、无任意query/alias，验证门户固定issuer/JWKS/audience/client_id与platform.access。从已验 JWT 获取原字符串和SubjectKey，调用现 PublishedMetadataClient.lookup；用**同一原source**第二次 exchange 到 engine/main动作/metadata.resource。转发原body和原响应协议，不把浏览器传来的身份/版本头作为事实。query/main grant独立，originAuthenticationId一致；engine forward app仍main operation/root不变。

现 IntegrationAccess.check 对非integration会直接return，因此首portal路径无需改其业务算法；IAM不支持的ApiKey/ticket/v1、openapi聚合及其它ANY路径必须在真实安全链/handler guard拒绝，不能落入静态issue。D2已批准机器policy域保留自身精确链，不扩大成internal豁免。

RouteCoverageVerifier新增一个**仅gateway、仅实际GatewayController.proxy Method、仅两个原ANY模板**的显式外部入口登记。普通Ingress仍禁止wildcard。登记须绑定专用可信入口链及运行时有限路由判定；缺链、换handler、第三wildcard、其它service使用均启动拒绝。app/engine保持全局ServiceAuthorization.mappedInterceptor；尚未迁移合法action也必须明确关闭，不能只证明拿published票撞alias失败。普通route catalog继续完整，disabled也要有guard。

mode必须来自同一经过验证部署配置；Spring=iam与JVM=test冲突不能使旧issue成功。首链可以在隔离配置对旧签票路径显式拒绝，但这不证明公共静态API全局一致性；N4a应给冲突加实测保护。不得为使测试运行设全局test属性后假称IAM没有fallback。不得放开SecurityConfiguration的全局IAM启动异常作为本段捷径。

最终实际HTTP链以各域独立classpath/进程或已证明可隔离的context运行；跨业务不引JAR，不反射别域target。测试authority可模拟外部身份和授权，但app/engine/gateway必须是真实原类。主验收至少：

- 同一portal→实际gateway→实际metadata→独立main→实际app payload→RunService PG→owned scheduler START/END成功；真实output、attempt、全部events/scopes，创建与重复requestId原响应字段和语义保持。
- metadata后切版/禁用、main exchange时source过期或current收窄、错误caller/target/action/root、metadata票冒充主票、local票撞HTTP均拒绝；明确业务SQL/发送/落库计数。
- worker快于响应时，current收窄/缺scope/错误digest不泄露；不可把已读payload后失败谎报为payload=0。gateway普通JSON响应在向客户端释放前要有明确本地当前期限检查，不以原无guard复制循环证明发送安全；若需要联网续核只走原响应机制，不能重建main/重放POST。
- 503恢复仅新的合法请求/current继续，原requestId不重复受理；所有闩锁/线程/端点有界关闭，测试不能动现六主服务。

此处“portal”指真实验签的门户source入口，**不等于管理端浏览器配套已验收**。独立GET status/SSE、模型/技能、ChatSessions/history/SUMMARY、批准者、附件、回放、集成在途完成permit、portal provider/session/browser、完整RR1/RR2及六服务迁移仍按原Task4/5/6保留。

## 7. 正式 brief 前需要 root 明示的有限决定

1. 接受上述顺序及每段证据标签；N1是实际存储/status组件、N2是真实owned worker组件、N3是真实engine/app受理组件、N4才是首门户HTTP链。禁止据N1/N2完成数更改整体Task4/5状态。
2. 采用当前V216之上的新连续迁移扩展同一表，保留三个immutable初始scope语义；编号与授权PG库由正式作者预检，不由本文预占。现材料schema无法只改Java便通过。
3. writer隔离选择专用RR、现proof在worker显式保留、HTTP状态读取只限创建/重复POST；如要求同时打开GET监控，需把真实run metadata根解析与status action纳入另一个正式切片，不能暗用main/local purpose。
4. N4最终JSON响应的精确发送边界须在正式测试中命名：服务返回前、gateway写出前及已发送字节不能混为同一原子撤权承诺。原SSE/模型流RR1不因短JSON首链自动关闭。

没有需要用户提供真实IAM后才能继续的软件前置。没有新增授权框架、driver、receipt、通用材料SQL插件或签收记录；没有修改既有提案、执行索引、设计契约或source/target。本文仅列未来实现和有限测试边界，所有建议测试均未执行。
