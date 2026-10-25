# D3/E 首条真实 published run 联合切片提案

状态：只读准备完成；未实施、未运行测试，不签收完整 D3/E。日期：2026-10-20。

唯一工程根为 `G:/207/项目/算法重组工具/代码/agent-platform`。本次仅写本文；未读取当前 D3a0 作者正在改动的 common/app/gateway 源文件或任何 target，未操作 PG、网络、进程、环境凭据、迁移或索引。common 事实来自 `docs/evidence/iam-stage-4-d2-fixes/frozen/admin-common`；按授权读取了稳定 GatewayController、IntegrationAccess、engine RunController/RunService/RuntimeClients/RunScheduler/RunScheduleRepository。AppService 事实引用最新 root handoff，未重新读取 live app。

依据：AGENTS.md、contracts/README.md、docs/implementation-brief.md、iam-stage-4-d3a0-brief.md、iam-stage-4-d3-root-handoffs.md 末节、iam-stage-5-e1a-brief.md 末节、iam-stage-5-implementation-brief.md 的原 Task5、iam-stage-5-e1-initial-seam-proposal.md 第10节。旧候选签名与末节冲突时以后者为准。

## 1. 建议切片与真实验收终点

下一联合切片应以原门户 `POST /admin/apps/{code}/runs` 为唯一首正例入口，经过实际 GatewayController、D3a0 冻结后的实际 metadata 客户端/控制器、实际 RunController.run/RunService.createInternal、实际 AppService published 读取、真实 PG 受理和原 owned scheduler。首个用例选择已发布、有效非空图的纯 FLOW；精确允许哪些现有纯节点须在 FlowDriver 冻结源码核验后列出，不能仅凭 type=FLOW 就认为无外发。

“可用”至少意味着真实 run 被持久受理并由原 scheduler 合法领取、执行一个已核纯节点、写出真实结果，原创建响应仍保持现有结构和安全语义。只返回假的 runId、测试 target handler 回包、只写 authority 表、或只证明 metadata HTTP 均不够。若只完成受理而 worker/响应材料未闭合，报告必须准确称“受理子集”，不能称首条可用链路完成。

目前有两个具体硬依赖尚未由已有裁决解决：第6节的 worker 新动作授权能力与第5节的真实 status/事件材料闭环。它们必须随联合切片闭合；本文不把既有七方法 client 或 E1a 提案描述成已具备这两个能力。

所有原功能、原路径及原 Task4/5 验收保留。IAM 子集未迁移路径继续拒绝；显式 dev/test 的原功能不能被删掉或换成空响应。生产 IAM 启动闭锁与 BLOCKED_IAM_SCOPE_ACCEPTANCE 不解除。

## 2. 已核实际入口、方法、事务与问题

以下源码路径均相对唯一工程根；common 的简称 C 指冻结 common 的 `src/main/java/cn/agentplatform/common/`。

| 实际位置 | 当前行为 | 下一切片必须改变的地方 |
| --- | --- | --- |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java:34`，`proxy(HttpServletRequest,HttpServletResponse)` | ANY `/admin/**`、ANY `/v1/**`；Routes.service 选服务；Actor.current + integration.check；HttpURLConnection；ServiceTokens.issue(actor) | 在真实 proxy 内建立精确 portal published 分支，两个 exchange、固定 target、原路径/方法转发；其它 IAM 请求拒绝，不能 ANY 都算已迁移 |
| 同文件 `openapi()` | 聚合五域 `/v3/api-docs`，静态 issue，任一域失败报502 | 不纳入首正例；IAM 下不能继续静态出票或把聚合端点豁免为 portal 万能通道 |
| `IntegrationAccess.authenticate/ticket/check/credential/allowed` | 已验 ApiKey 策略裁剪；仍有静态 bootstrap/用户 issue；check 在 `/v1/runs/` 先读 engine run | 首入口只 portal；不能以 `_gateway` 或这些旧 helper 放开 integration。实际 D2 两条机器能力按原有限域，剩余集成和 run lookup 留闭锁 |
| `engine-runtime/.../api/RunController.java:35`，`run(String,JsonNode)` | 同方法映射 `/admin/apps/{code}/runs`、`/v1/apps/{code}/runs`；调用 service.create(code,req,"TASK",Actor.current()) | 普通 service guard 后从可信 frame 取得主授权，不能以 Actor alone 进入 IAM 受理 |
| `RunService.createInternal:184–197/208–235/334–348` | 三处 requestId 命中；均先 select *，verifyReplay 读 inputs/snapshot，随后 status | 改窄 metadata 查找，当前授权及 scope 先行；锁内竞态重查不能绕过；旧缺 row 禁止自动补权 |
| `RunService.createInternal:240–261` | DEBUG snapshot、candidate、显式 version、published 四路；普通路径 clients.get("app",path,actor) | 首切片仅实际 published 分支；读取前取得可信主 root/current，再以 forward 取 app 票，不能传 source token |
| `RunService.prepareSnapshot:720–880` / `freezeModelRefs:662–716` | 前者可能读取 skill 和 prompt；后者对 published 校验已有冻结 model refs，DEBUG 可能发 freeze HTTP | 在任何依赖读取前按可信快照识别未支持依赖并拒绝；不能等外发后才判断 pure FLOW |
| `RunService.createInternal:319–467` | persist supplier 中 store.atomic；requestId advisory lock、run INSERT、node INSERT | authority、initial material scope 加入这一个事务；不另起事务、不提交后补写 |
| `RunService.createInternal:468–481` | Admission.persist/completeReservation 或 scheduler.persistNew；随后 scheduler.created，再 status | 保留 admission/限流/排空/提交后调度；本地证明过期、回滚不得进入 created；status 不能泄露未检查材料 |
| `RunService.status:1309` | store.run、nodes、inputs/output/error/versionRefs/events、artifactRefs | 新建返回也读这些真实材料；仅给 input 写 scope 不能使完整响应安全 |
| `RunService.driveRecovered:1539` / `drive:883` | 前者 select *、guard_ctx 重建 Actor；后者 select *、nodes、flow.prepare/execute、store.commit | metadata→当前签名→材料同快照读取→显式 VA；原 guard_ctx 只能审计，不能签票/恢复 |
| `RunScheduleRepository.claim:28–80` | 独立短事务领取，PENDING→RUNNING，并 store.event(RUN_STARTED) | 缺/阻断/过期 authority 不领；RUN_STARTED 也是新材料，须同事务 scope |
| `RunScheduler.bind/created/launch/execute/runNow` | RunService 绑定 this::driveRecovered；虚拟线程只传 dispatch/runId；created 可能领取别的已排队 run；runNow 也走有限池和 PG generation | 保留原 ownership/lease/队列，不把当前 HTTP VA 捕获后套给任意领取 run |

TraceStore.atomic 使用一个 TransactionTemplate 的事实来自 E1 提案第1节（原定位 TraceStore:18–28），本次未超范围重读该文件。其同 DataSource/传播行为与实际材料写点必须在下一作者获得该文件范围后复核。

## 3. 原 source 到不可变 published payload 的精确链

1. 门户 JWT 经 gateway 固定 portal issuer/JWKS/audience/client_id/claims 映射及 platform.access 验证。source 保存一次，query/main 使用同一个原 token 字符串；不重登录、不分别生成 fixture portal token。浏览器传来的 tenant/principal/role/header、DTO 版本摘要不提供身份或版本权威。
2. 以 verified tenant + 校验后的 code 构造 D3a0 selector。gateway 的 CurrentAuthorityClient.exchange(source,call) 签出 query Grant：target=app，action=`app.published.metadata.read`，kind=`metadata-selector-v1`，HTTP 为 `GET /internal/apps/{code}/published-metadata`。真实 app 窄 SQL 查状态、current pointer、真实 versionId/versionNo/stored candidate SHA；只出严格四字段。元数据查不到404，未发布/DISABLED/ARCHIVED保持409。
3. gateway 基于该可信返回重建 `ResourceBinding("app-version", code+"@"+versionNo, storedCandidateSha256)`，从**同一 source** 再次 exchange，target=engine，action 是现目录的 **`proxy.engine.post.RunController.run`**，HTTP 为 **`POST /admin/apps/{code}/runs`**。不是虚构的 run.create action。main 和 query Grant/root/operation 独立；originAuthenticationId 相同不代表互换许可。
4. engine 用自己的 service decoder、ServiceAuthorization 全局 MappedInterceptor 和完整 route coverage 进入真实 RunController.run。已核目录该 admin 路由 caller=gateway、integrationAllowed=false；同 action 的 v1 alias integrationAllowed=true 不自动使首切片 integration 可用，入口及下游未闭合时仍明确拒绝。
5. 在 source payload 前，RunService 刷新主 frame，要求 E1a `requireInitialFor(actor, concreteRoot)` 的 retained 证明；code 要与路径一致。engine 无 app PG，不能自证 ticket 里的 SHA 就是真实版本；此时只认可可信主票与路由绑定，真正 PG 核对必须继续由 app 完成。不把主票先持久成 run 再验证版本。
6. engine 以同 main operation/current `forward(current, appCall)`，target=app，action=`app.published.read`，URL=`GET /internal/apps/{code}/published`。原 CurrentAuthorityClient.forward 保持 operation 全等，因此不改根为 runId，也不 resume 续期。app 票只给固定 app origin，source/authority client secret 都不能送业务域。未知 service 必须拒绝；现 RuntimeClients.base 的 default→model 要改为穷尽拒绝。
7. app 的实际 published 路径先按 verified tenant+code 窄读 application/current pointer 与对应 app_version metadata，检查状态、versionId/versionNo/stored SHA，与 incoming app-version 根及 request code 全等；`requireResource`/当前授权通过后才 select snapshot/config/definition 等 payload。不能沿现 `published→get(code,false)→version` 先读 a.*、draft.config/definition/layout，也不能以“查询方法叫 metadata”替代 SQL 核验。
8. published 指针须在这一次实际 payload 读取的 app 域事务/快照中重核并一致读取；metadata 查询后已切版返回409，不自动重做 query/main，不返回旧版或最新另一版。显式 `/internal/apps/{code}/versions/{version}` 的 action=`app.version.read` 保持 immutable 版本语义，不能偷偷拿它绕过 published 指针重核。本文不假称 app/engine 跨 PG 原子；若要把“指针仍当前”延伸到 engine 提交瞬间，会需要超出既定域内重核的协调，尚无该承诺。
9. engine 对返回 code、真实 versionId/versionNo/stored SHA 与主根交叉核对后才进入 prepareSnapshot；实际快照的发布摘要规则沿既有发布语义，不用 JSONB 文本重排摘要代替 stored candidate SHA。再次检查当前 app guard；完整初始 ceiling 不等于当前执行权限。

app-version root 中 versionId 用于真实 PG 外键关系核对，不能替代 code@versionNo；runId 是 engine 新生成且归属于该 root 的业务事实，不是允许 C.forward 改 root 的理由。首个纯 FLOW 不引入 skill/model 子资源。若遇到技能、model、prompt version、subflow/子运行或历史材料，需要真实冻结记录/父子关系与对应域内检查；此切片拒绝这些未闭合路径，不用根相等通行所有子资源。

## 4. gateway ANY 登记与模式装配不能成为绕过

冻结 RouteCoverageVerifier.Ingress 仅接受 GET/POST/PUT/DELETE/PATCH/OPTIONS/HEAD 和无 `*` 模板；实际 proxy 是 ANY `/admin/**` 与 `/v1/**`，因此现构造器无法表示它。不能通过放宽普通 Ingress 构造器或 ServiceRoutePolicy 的 wildcard 规则解决。

下一段需要一个有限的 **gateway proxy 信任域覆盖登记**，精确绑定 serviceId=gateway、这两个模板、GatewayController.proxy 的实际 Method 与对应 portal/integration 验证链。登记只是说明该 HandlerMethod 由专用外部入口链管辖，不授予 service capability。运行时仍逐请求验证实际 method、规范 path、主体类型、目标服务、精确动作、本次支持的资源语义；首正例仅上述 POST/admin 路径，OPTIONS 仅原 CORS 处理，其他业务匹配不能因同属 ANY 而通过。替换 HandlerMethod、第三个通配模板、其它服务使用该登记、缺专用链、普通 service票/workload票撞门户路径均应启动或请求拒绝。

app/engine 使用普通 serviceSecurity + ServiceAuthorization.mappedInterceptor（无 include/exclude）及 RouteCoverageVerifier；不得给实际 RunController/AppController 登记普通 ingress 逃开接收守卫。被 disabled 的映射仍需实际拦截器。docs、health、error、自定义 Actuator alias 都按真实映射登记/拒绝；Boot exact error 例外不扩大。

冻结 SecurityConfiguration.authorityModeGuard 绑定 Spring Environment 后仍一律抛 IAM_TRUST_CHAIN_UNAVAILABLE。其原 jwtDecoder 是共享 HS256、serviceSecurity 是显式静态装配入口。ServiceTokens.issue 却只读 JVM System properties 判断 dev/test。下一段须统一显式模式来源：Spring 与静态入口不得出现 Spring=iam、System=test 的降级组合，不能设全局 System property 开门。仅 dev-hmac+devAuth 或 test+test profile+loopback 可用旧票；iam 无 Actor-only issue。分别配置 portal/service/policy/integration 固定域，禁止“RS256 失败再试 HMAC”。

单一联合 slice 可用隔离 test context 装配真实 gateway/app/engine 生产类与真实 guards、authority 客户端；不得以漏扫全局守卫跑正例。生产 common startup 闭锁继续存在，六域完整配置/所有入口/出站迁移未完不能发布半套 IAM。所需 YML/配置键在冻结交接时由下一作者核精确现状，本次没有读取配置值，也不虚构已存在的自动配置类。

## 5. 真实受理事务、初始材料与原响应

E1a 仍为先决组件，不是当前可调用的实现：四生产文件 CurrentAuthorityClient、HttpCurrentAuthorityClient、ServiceAuthorization、AuthorityContext；采用 default current-only + Http 私构造嵌套 RetainedAuthorization；proof 私存同 client Clock，公开 requireVerified() 无 Clock 参数；同次已验 service/current 双侧 initial/origin，单次 current query。query/main 同已验 issuer+jti 的 origin 恒定；同 issuer+jti 改源声明必须拒绝。不能拿当前 Actor、guard_ctx、hash 或 public VA 补 full initial。

真实新 run 在完成 app/依赖前置检查后再刷新受理证明。进入原 persist supplier/store.atomic 后：

- requestId advisory lock 与窄 metadata 重查；新建才插入 run，核 tenant/principal、实际 app_version_id、code@versionNo/stored SHA 与 retained 完全对应。
- RunAuthorityStore.insertNew 与 engine MaterialScopeStore.write 强制已有业务事务，使用同一个事务连接；保存 grant、grant expiry、完整 initial Actor、原 source kind、originAuthenticationId、根 kind/id/digest，不能仅保存 resource_digest。run payload、authority、初始材料、nodes 全部同事务。
- 插入前、锁等待后、提交前检查同 frame 的 `requireBound(expected)` 和可信 Clock 截止；失效则回滚，事务内不联网续票。不能拿请求外的 retained 实例持久化。
- 原 input/frozen snapshot/node definition 等持久材料需要各自可定位 MaterialRef 与真实 payload digest。required roles/entitlements 保留实际完整初始要求，labels 只增不减。初始 A/B、current A 可以保留 A/B 的受理记录，但当前 A 不能读/执行/返回要求 A/B 的材料。
- parentScopeSha256 只能引用真实已存且验证过的材料 scope digest；app-version candidate SHA、operation.ceilingSha256 都不是 parent scope digest。若某初始材料无历史父材料，空父列表可以表达真正根材料，但不得通过把派生材料重新标根删掉已存在依赖。snapshot 的域来源与 published root 关系另精确记录，不能把 root hash 当材料继承。
- 出事务成功后保留原 scheduler.created；失败则本次 run/authority/scope/nodes 均不存在，不能调度。

创建后立即调用的 `status` 与三处 requestId 返回都是真实材料读取。必须 metadata-first 授权后在同域同数据库快照读 payload，并在响应序列化前保有合法当前证据；不能先 select * 再删字段、不能把已返回 status 的同租户/同用户检查当 scope。

由于 scheduler.created 可立即写 RUN_STARTED、attempt/checkpoint/output/error/events，完整原 status 结构要求这些**首个纯 FLOW 实际触及的写点**同步写 scope，读取端逐份验证，包含父材料要求并集。若只写 input scope，却返回没有 scope 的 output/events，仍是失败；若改成只返回 runId 或伪造空 nodes/events，则改变原行为，也不能作为满足本提案的最小实现。完整 Task5 其余材料种类仍保留后续验收，不能借纯 FLOW 正例声称已覆盖模型/技能/附件。

requestId 的三处现有返回、并发锁内重查、容量拒绝后重查都必须覆盖。合法同版本/同输入重复返回原 run，不重写原 grant/initial/origin、不重复 scheduler 执行；历史缺 authority/scope 默认拒绝。无 versionNo 的请求若 current pointer 已改变，而旧 requestId 属旧版本，主 Grant 绑定新版本不能读取旧版本 run：不能静默换根或更新旧授权。保留该历史可读行为需要另一个已批准的真实 run metadata/root 解析与材料许可路径；本首 published-only 入口目前没有它，必须明确报资源冲突/拒绝，并保留后续需求，不能宣称已覆盖所有原 idempotency 情形。

ChatSessions 的 reserved turn 是单独 reservation 事务，已接受 reservation 可走 `createReservedSessionTurn(..., acceptedReservation=true)` 与 Admission.completeReservation。仅改 RunService INSERT 不能保护 reserved turn 先前 payload/known requestId 返回。首切片不开放 IAM CHAT/session；下一材料切片必须检查真实 ChatSessions.turn 的 reserved-without-run、known requestId、原 checkpoint 读取，不能用 suppliedTicket 作为用户许可。保留既有 dev/test 行为。

## 6. owned scheduler 与一个必须解决的具体协议缺口

使用现有 RunScheduler：池/队列、PG generation、dispatch owner、dispatch_until、renew/release、drain 不替换；LongConsumer 可继续只携带 runId。RunAuthority 从该 run 的已提交 PG metadata 取原 grant/initial/origin/root，签新的 DELEGATED operation，在当前权限与全部材料覆盖通过后显式传入 drive/FlowDriver。每个真实新动作重新 current；同一动作跨 hop forward；原 PORTAL observer 的 sourceExpiresAt/operation 不被 worker 改写。

两条 claimNext/claimSpecific 共用 claim，应增加缺 authority、blocked_reason、grant 到期的排除；受控重核才清阻断。403 保留原事实并停止；503 保留可恢复原因及退避。lease 有效性不等于用户授权。旧 run 无 grant 不领、不 select snapshot、不发送，不能从 guard_ctx 自动 upsert 新权限。

**冻结 API 的真实缺口：** E1 提案伪代码 `client.resume(delegation,call)` 后 `client.authorizeRetained(ticket.value(),call)` 对 engine 本地 worker 不自动成立。

1. HttpCurrentAuthorityClient.ticket 要求目录存在 exact caller=config.serviceId、target、action、mode。
2. engine 本地 authorize 使用本 client 的 TrustedJwtDecoder，要求 aud=`urn:agent-platform:service:engine`、target=engine。
3. 当前目录没有 engine→engine 已核 worker 推进能力。gateway→engine 的 `proxy.engine.post.RunController.run` 不能冒充 engine 自调用，也不是恢复推进动作。
4. engine→app 的 app.published.read 确实存在，但其票 target/aud 是 app，不能放进 engine 的 authorize 当 worker current。这不是多调用一次现方法就可解决的问题。

因此下一联合段必须在真实 local drive 边界定义并签收一个**有限的本地推进能力与验证域**，或签收等价的真实 current 验证接缝；必须保持 caller=engine、target=engine、用户 delegation、实际 run→app-version 根关系、固定动作与目录白名单。现目录是 HTTP method/template 表，不应用虚构 URL/假 controller 行充当本地能力。新增方案不得放宽成任意 local action 或改成跳过 decoder；具体动作名/登记形式目前未裁决，本文不发明一个已存在 API。

在该问题闭合前，可以证明真实 PG 受理/拒绝与调度闭锁，但不能记 owned scheduler 执行正例成功。若根团队另选择仅在原请求 operation 内执行的首动作，也必须明确可靠地把该 run 的显式证据传到实际 owned dispatch、保留全部期限、禁止用于别的排队 run；那仍不替代 Task5 的后台 resume 正例。

### 6.1 root 要求补充的最小可评审替代方案：唯一 non-HTTP local tuple

本节是追加候选，**root 尚未授权实现新 action**。建议选择独立 delegated local action，候选名 `run.advance`：caller=engine、target=engine、mode=delegated、resource.kind=app-version、root仍为该真实run持久的 code@versionNo/storedSHA；首切片仅 origin=PORTAL、source=DELEGATED。它对应现 `RunService.driveRecovered → drive → 真实纯 FLOW 节点` 的本地动作，无 URL、无 HTTP method、无新增 controller。不是新的 run 枚举状态。

已复核冻结代码：ServiceRoutePolicy.load 把 `agent-service-authority.json` 解析为 Route[]，require(caller,target,method,template) 从 exact HTTP key 查找；HttpCurrentAuthorityClient.ticket:109 附近和 TrustedJwtDecoder.requireServiceScope 均直接扫描 routes；LoopbackAuthority.route:324–325 也只扫描该 HTTP 列表。因此只增加一个 JSON “local行”会破坏语义或迫使虚构 URL，不能这样实施。

| 候选 | 实际变更 | 判断 |
| --- | --- | --- |
| 单一有限 local tuple helper | 在 ServiceRoutePolicy 内放一个清楚命名的 static/package合适范围的纯匹配方法，或一个极小 LocalCapabilities helper，固定唯一 engine/engine/delegated/run.advance/app-version；HTTP routes()/require/load 原样；只在明确需要 local能力的出票/本机验签处调用 | 推荐；一个确切用途，不接受注册回调、不枚举任意本地动作、不加框架。优先内嵌方法避免新生产类型；名称只是候选，非已有API |
| catalog 独立 localActions 一项 | 维持原 HTTP JSON 数组与原 require，另加只含一个 tuple 的 local JSON；或把原文件改成 {routes,localActions} 并同时改读取与清单测试 | 可审但此时不优：新资源解析/验证/缺失处理比单一固定tuple大；改原文件顶层会扩大现158 actual HTTP清单相关适配。若未来有已批准多项才另评，不为猜测需求先做 |

选择 helper 时的具体调用限制：

1. **现 HTTP 目录完全保留。** 原158 actual HTTP集合及POLICY_MACHINE独立集合的真实比较继续；D3a0已经批准新增的metadata真实入口另外按其冻结清单准确登记。local tuple 计数单列为1，不把它算成第159个 HTTP endpoint，不豁免任何普通守卫，不给 disabled handler 新 capability。RouteCoverageVerifier 的普通服务路径和第4节 gateway 独立受限登记互不混用。
2. **client出票与验签只放这个tuple。** `HttpCurrentAuthorityClient.resume` 允许该有限 local call，ticket 的 capability 检查接受“原HTTP能力或精确local”；同时核local返回 source=DELEGATED、origin=PORTAL、app-version根、同delegation/原ceiling/expiry。`exchange`、`client`、`forward` 的目标call若是这个local action直接拒绝；初次main仍走原 gateway→engine run action，local新动作只能resume。不能加一个所有action共用的 `local=true` 请求布尔值绕过目录。
3. **TrustedJwtDecoder.requireServiceScope** 仅在本service=engine、已验caller/client_id=engine、target/aud=engine、scope恰等于run.advance、mode=delegated、root.kind=app-version时认可local tuple；签名、固定issuer/JWKS/RS256/typ/时间/subject/grant/operation/initial检查全部保留。HTTP ServiceAuthorization.route **不**接受local tuple，仍只查真实 method/template；即使将合法local票发给RunController.run/status或任意internal URL，普通guard拒绝、业务0。local票不产生HTTP通行能力。
4. **RunAuthority.current** 在owned worker明确构造这个本地ServiceCall，以PG加载的真实根resume，再在engine本地authorizeRetained；现在target/aud都engine，解决原app-target票不匹配。核原grant、owner、origin、完整initial与hash、真实run→app-version关系及新operation，才读取scope/payload。不得用该action代替读取任意别的run，也不将本地current转换成Actor后走旧issue。
5. **每个真实新节点动作** 从实际run元数据重新获取current；同动作下的流程提交前再核有效期和dispatch generation。只改driveRecovered开头一次然后无限推进不满足“每个新动作”。纯FLOW仍通过既有FlowDriver；生产代码在prepareSnapshot之前排除 skillRefs/promptRefs/model配置/未知或可外发节点及未处理历史材料，而不只把测试图设成空依赖。允许的纯节点集合须读取真实FlowDriver后有限穷尽确定，未知节点拒绝；保留原既有输入验证/guard/预算/DAG/lease语义。
6. **事实边界。** run.advance 授实际本地推进，不自动授skill/model/prompt/下载/回放/response continuation。后续若要从local current forward到真实子域，仍需另验目录和真实父子资源关系；本纯FLOW实现不因此开放这些动作。

有限 fixture 必须真的验服务身份和签名，不能靠测试stub回VA。冻结 LoopbackAuthority.handle:130 通过已配置Basic凭据识别caller；dispatch resume:191–194 查真实有限Grant、current、exact root并新签DELEGATED；authorize:199–205 核真实service票、target、active operation、authorizationVersion、subjectCaller；ticket/decision:298–307 分别出RS256 service JWT和signed decision。应最窄扩展这些**原真实路径**：

- route 对local使用同一固定tuple语义，但额外只允许 authenticated engine 的resume/authorize，禁止workload/client、初始exchange到local、forward到local。不能把fixture放宽成caller==target就允许所有action。
- 首main Grant必须确实来自已验证portal source、已批准gateway→engine运行受理call；现Grant只保留root/source等，不保留该受理call，若要核这一归属，给现有限Grant增加最小受理call/consumer锚点并在创建时固定，不能让传来的runId/target自报成为事实。query Grant的metadata root、其它主体/服务Grant不可用于local。E1a已有origin/原认证不变量、D2 policy锚点和有界数量全部保持。
- fixture只证明authority对真实服务身份/Grant/root/动作的判定；实际run属于该Grant与root仍由engine PG核对，不让fixture声称读取engine数据库，也不新建run关系authority服务。
- service票target/aud=engine、caller/client_id=engine、source=DELEGATED、origin=PORTAL；两个签名中的完整initial、origin、nonce/request digest/ticket_jti按E1a真实严格映射。推进Clock至原portal过期后仍可按grant签新operation，原observer截止不变。
- 负例至少覆盖：portal直接local exchange、gateway/skill/model服务resume local、workload local、metadata Grant local、换root、错origin、过期/撤Grant、local票撞所有实际HTTP入口、同主体另一run错误grant关联、签名/nonce/initial错。各自断言真实payload SQL或节点execute=0。

这个候选新增/修改生产范围最多是 **ServiceRoutePolicy 内一个有限匹配方法、HttpCurrentAuthorityClient、TrustedJwtDecoder**，加本来就要实现的RunAuthority/RunService/FlowDriver调用点；不改变原CurrentAuthorityClient七方法公共形状，不增加HTTP目录行或新的authority服务。LoopbackAuthority、common测试和真实engine PG/调度测试最窄适配。若採用独立local JSON才需要新增资源文件；推荐方案不需要修改原HTTP JSON来登记local。

本节提供了可实施的最小方向；必须先由root确认该单一local动作的语义及文件边界，再进入实现/独立审查。通过后，第6节“不存在已核worker能力”的现状由此明确替换，首条真实FLOW成功仍须同时通过第5节status/requestId材料保护，不以local helper单测代替联合链。

## 7. 下一联合切片最小生产文件集合

这是下一次正式冻结授权应覆盖的文件，不是当前写入许可；不能并发进入 D3a0 作者范围。新增文件名为规划名称，不宣称已经存在。业务类名根包沿既有 cn.agentplatform 分层。

| 范围 | 精确文件或确定的待接文件 | 最小职责 |
| --- | --- | --- |
| E1a 先决 | `admin-common/src/main/java/cn/agentplatform/common/authority/{CurrentAuthorityClient,HttpCurrentAuthorityClient,ServiceAuthorization,AuthorityContext}.java` | 完成已裁决 retained/origin/frame/Clock；联合段使用冻结结果，不复制 proof |
| 通用接收/模式 | `admin-common/src/main/java/cn/agentplatform/common/{SecurityConfiguration,ServiceTokens}.java`；`authority/RouteCoverageVerifier.java` | 显式模式一致、真实固定信任域装配、gateway 唯一受限 proxy 登记；生产闭锁保持 |
| 有限能力依赖 | `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceRoutePolicy.java`、`HttpCurrentAuthorityClient.java`、`TrustedJwtDecoder.java`；实际HTTP目录仍为 `admin-common/src/main/resources/agent-service-authority.json` | 第6.1节推荐有限local helper方案须单独裁决；local不添加HTTP目录行、不改原HTTP集合，不硬塞假HTTP route |
| gateway | `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | 实际入口双 exchange 与精确转发、请求/响应限制、拒绝未迁移 IAM 路径 |
| gateway装配 | 规划 `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayAuthorityConfiguration.java` | portal/service域、有限 proxy coverage；若冻结结果已有窄装配，直接扩展已有文件，避免重复类 |
| D3a0 接入 | D3a0 实际已冻结 gateway metadata 客户端与 app metadata service/controller | 复用其真实类与 4字段 DTO；当前未读 changing source，确切文件名必须由 D3a0 冻结清单移交，不能先凭本文造第二套 |
| app主payload | `app-management/src/main/java/cn/agentplatform/app/api/AppController.java`、`application/AppService.java`；其实际安全装配文件 | published/version metadata-before-payload，真实 pointer/状态/SHA核查与当前 guard；保留既有状态/响应语义 |
| engine受理/返回 | `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java`、`application/RunService.java`、`infra/RuntimeClients.java` | 主frame/真实forward、三条重放路径、新建事务、原响应材料；未知target拒绝 |
| engine持久授权 | 新 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java`、`infra/RunAuthorityStore.java`、`infra/MaterialScopeStore.java` | 已裁决 Task5 职责；无新通用授权框架，无 JWT 持久 |
| engine纯FLOW材料/调度 | `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java`、`infra/RunScheduleRepository.java`、`application/RunScheduler.java`、`application/FlowDriver.java` | 原真实材料写读/父范围、claim/start事件、显式 VA 推进、lease不变；FlowDriver/TraceStore 的精确改动点待正式允许读取后核定 |
| engine装配 | 规划 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/EngineAuthorityConfiguration.java` | 实际 service decoder/receiver/全局interceptor/coverage 和 stores；已有装配优先复用 |
| PG迁移 | `engine-runtime/src/main/resources/db/migration/` 的下一连续版本，源树目前候选 `V216__run_authority_context.sql` | run_authority、material_scope及必要索引同一次新迁移；未查 Flyway，候选号未占用，不覆盖已应用、不 outOfOrder |
| 服务配置依赖 | gateway/app/engine 各 `src/main/resources/application.yml` 与最终六服务显式安全配置 | 下一作者核已有键/装配来源后最窄改动；本次没有读取其内容。不能只移植三域就放开全局 production IAM |

IntegrationAccess 在本首 portal 正例可不改业务实现，但其 IAM 入口和调用必须确实关闭或仅进入已批准 D2 machine 域；若实际 gateway 装配仍调用旧 bootstrap/issue，就成为必须修改的同路径文件，不能用“首例没调用”回避。AgentDriver/ModelExchange/skill/model/ChatSessions 等不为本纯 FLOW 增加假实现；未迁移 IAM 分支明确关闭，完整 Task5 原文件清单与验收仍保留。

最小性边界：TraceStore/FlowDriver/worker 能力/响应 scope 是真实成功路径的必要成本，不能以“只改五个文件”为目标省掉；完整 child/model/stream/材料功能又不能借首例顺手宣称全做完。没有 receipt、query snapshot、creation-intent、新 IAM server、新 run enum 或通用框架。

## 8. 有限验证矩阵与真实证据要求

沿原失败测试先行。以下为下一段应执行的测试，本文全部未执行。复用有限 authority/Clock/闩锁、真实独立 PG、各域独立 classpath 和真实 HTTP；不得业务 JAR 互引、反射加载别域 target、假 target handler。缺 fixture/PG 前置条件算失败，不 skip 计绿。测试类可命名 PublishedRunIngressHttpTest、PublishedRunAdmissionPostgresTest、RunAuthorityRecoveryPostgresTest，最终以实际类名记账，原 Task5/RR1/RR2 测试不能删。

| 真实场景 | 必须能观察的结果 |
| --- | --- |
| 同一 source→真实 metadata→新 main→真实 RunController→AppService→PG→owned scheduler | query/main 两 grant/root 独立；origin相同；main根=code@no/storedSHA；HTTP downstream无source token；真实纯节点及真实结果、原响应结构合法；无 model/skill/prompt 外发 |
| 元数据查询成功但第二次 exchange 时 source 到期/当前收窄 | 旧 source 不重签；到期拒绝；收窄遵守 current 与完整 initial；不自动重查询延长 operation |
| metadata到payload之间发布指针改变 | 实际 app GET published 返回409；snapshot/config/definition读取0，engine run/authority/scope/nodes新增0；不得转取新版本 |
| 错tenant/code/no/SHA/caller/target/action、query票冒充payload、普通workload/portal直连app/engine | 守卫或域metadata校验拒绝；payload SQL/业务handler/发送计数为0；“只返回403”不够 |
| gateway ANY正负 | 实际 handler有限登记成功；第三通配/换handler/缺专用验证链/非gateway使用登记启动失败；不支持路径/方法即使同ANY也无下游发送 |
| 受理回滚 | 在run payload之后、authority之后、scope中间、node之后故意异常；真实 PG同事务全回滚，scheduler.created本run调用0、外发0 |
| 锁/队列延迟到proof截止 | 本地可信Clock检查拒绝；不在持锁事务续票；旧对象、public VA/currentOnly/旧bind无法insert |
| initial A/B+labels，current A | PG完整值/材料要求仍A/B；不能回读该材料，worker/响应不泄露；labels不消失 |
| 三条requestId命中/容量满并发/锁内竞态 | 先metadata/scope后payload；合法同版本重复只一run、不重执行、不覆盖grant；旧无authority/scope拒绝；不同root不静默读旧payload |
| 原status和调度竞态 | created先执行到RUN_STARTED/output，status仍只返回当前可读且有scope的材料；缺scope/错payload摘要拒绝；不靠暂停worker隐藏响应风险 |
| 旧run、缺grant、错origin/root/fullinitial、撤grant、503 | claim/drive均拒绝或阻断退避；不读旧snapshot、不外发、不改原事实为成功、不自动回填授权 |
| 已签收worker能力的同scope恢复 | 真实driveRecovered用PG行resume+current，显式DELEGATED，真实owned dispatch/generation；正常纯FLOW成功，直接Actor入口不能旁路 |
| source t+10s / grant t+300s，t+11s后台新动作 | 后台仅在真正DELEGATED/current/材料通过时可继续；原PORTAL响应仍按原截止关闭，不能复活或替换holder。若真实observer尚闭锁，只记录common/期限断言，不能宣称RR2双流通过 |
| 模式及配置 | Spring iam + JVM test不可签旧票；test无profile/dev无devAuth/非loopback拒绝；业务域不接受portal或integration共享HMAC回退；六域生产闭锁仍在 |

PG metadata/payload 计数应在真实 SQL/仓储访问边界观测；no-payload-before-check 不能只靠假 client 的调用次数。正常用例必须证明合法读取确实发生，以免“所有请求都拒绝”假绿。scope SQL与payload须同事务/同快照，错摘要/缺scope时不先读取受保护值。

原 PORTAL source deadline、主 operation、grant 三者分别记录边界；新后台 operation 不更新原响应。未启用 SSE/model/provider 的首 FLOW 不得计完整 RR1/RR2，仍保留其真实 writer/reader/subscription关闭、首provider发送、chunk/final、撤权/503/超时、材料manifest来自原受理连接等全部要求。

## 9. 实施前具体交接项

1. 等 D3a0 冻结、root核验和独立双审后接收其实际 helper/controller/DTO/装配清单；再完成 E1a 已裁决组件。当前不占作者 source/target。
2. 定义第6节真正 worker 本地动作的有限可信能力及 current验签方式；现有目录/decoder冲突不可略过，不新增假HTTP handler。
3. 授予下一作者读取实际 AppService/TraceStore/FlowDriver/装配与新工程迁移历史的精确范围，以核实 pure节点、发布SQL锁/快照、材料事务和实际下一迁移号；本文不猜测这些未读实现。
4. 将首次完整 status/调度事件输出的 scope 写读纳入联合文件范围，或准确缩小验收名称为受理子集；不能宣称保留原行为但省略这些材料。
5. 旧requestId跨发布版本、CHAT reserved turn、skill/model/prompt/child、事实/批准/附件/stream 留原后续任务。当前有限正例不删除它们，不解除生产 IAM 门禁。

结论：最小真实推进方向明确，但 D3a0 metadata + E1a retained 并不会自然组成可运行的完整路径。应把实际 published payload 守卫、同事务受理、首 FLOW 真实材料返回和合法 owned worker current 作为下一联合边界；上述未裁决点是具体协议/文件依赖，不是请求新的外部环境或许可问题。

## 10. root 采纳 local tuple 后的真实生产接缝补核

追加状态：root 已采纳第6.1节唯一内嵌 local tuple 方向，作为后续正式 brief 裁决，**仍未授权改源**。具体为 run.advance、caller/target=engine、delegated、app-version、source=DELEGATED/origin=PORTAL；仅 resume 可出票；exchange/client/forward 到该 local 目的拒绝；普通 HTTP guard 绝不接受；不加假 HTTP 行。以下补核按新增只读授权进行，未运行测试、数据库、网络、进程或读取 target/凭据。只读 AppService/AppController、TraceStore、FlowDriver、实际安全装配、三个 application.yml 的占位结构、engine 迁移源码；common 类型与 fixture 仍读 D2 冻结副本。未来 source 实施须等 E1a 冻结双审与正式范围移交。

### 10.1 真实 published 版本数据与锁点

`AppController.published(code):154–156` 直接调用 `AppService.published(code):560–565`；后者调用 get(code) 再 version。`AppController.version(code,version):134–139` 自身也先 apps.get(code) 检查状态；仅修 published 而开放 version/snapshot alias 会留下前置 payload 读取。

`AppService.get(code,lock):124–137` 查询 `a.*` 与 draft.config/definition/layout；其 `for update of d` 锁的是 draft，不能当 application current pointer 锁。`AppService.version:485–503` 直接 `select id,snapshot::text ... tenant_id/app_code/version_no`。返回时把快照原字符串 `appVersionId=code@no` 转存为 **appVersionKey**，再将 **appVersionId、versionId 改为数字 PG id**。这是原响应必须保留的细节；不可把 root.id 改成数字或从数字ID推导 stored SHA。

`AppService.commit:1108–1133` 写 snapshot 的 versionNo/draftRev/candidateSha256/appVersionId 与 frozen model refs，然后插 app.app_version；`1152–1158` 在同事务更新 application.current_version_no/status。真实指针是 current_version_no，非本文早先概述可能让人误解的 version_id 列。窄 metadata 应以 application.id/tenant/code/current_version_no 与 app_version.app_id/tenant/app_code/version_no 真实关联，并读取存储 candidate_sha256。

下一实现最小改法留在 AppService：

1. 真实接收 guard 后先窄 metadata 查 code、status、current_version_no、version id/no/SHA；不调用 get/row/version。
2. 用这份真实 root 刷新 E1a retained/current；网络复核在短锁事务外完成。
3. AppService 现 tx 内重新窄读并 `FOR SHARE OF a` 锁实际 application 行，重新核 status/pointer/版本三元组与先前根；若改变即409，不能用旧票自动重新解析。app_version 保持不可变；读取的 version id 与 stored SHA 固定。
4. 锁内 `AuthorityContext.requireBound(retained)` 做无网络时限/frame核验，然后按固定 version id+tenant+app_id+code/no 读 snapshot；返回前再本地核验。application 更新与发布 pointer 更新会与该行锁互斥。保留现 snapshot 投影的 appVersionKey/appVersionId/versionId 兼容字段。
5. 显式 version 路径若一起迁移，同样移除 AppController.version 的前置 apps.get；按已有 status/已发布要求检查，但不把 explicit version 强行改成当前指针版本。未迁移的 snapshot/input-schema/adminVersion alias 仍闭锁，不能因为共享 AppService 方法而默认开启。

这里不把 app_version.snapshot 本身 canonical hash 等同 stored candidate_sha256：后者来自 AppService.sha→AppRules.canonical(candidate)，snapshot 还包含发布/冻结元数据。root用存储candidate SHA；实际接收的engine snapshot材料需要另一个真实payload摘要，两者用途不同。

### 10.2 实际可允许的 pure FLOW 集合

已读 `FlowDriver.execute:150–281`：**START、END、CODE、CONDITION** 四个分支使用本地 Values/InputDeclarations/JsonGate 等计算；SKILL/RETRIEVE 调 agent.skill，LLM/AGENT 调 agent.drive。CONFIRM 虽在 execute 返回 approved=true，但 RunService.drive 在 execute 前执行 approvalGranted/freeze/WAITING_APPROVAL，属于批准流程，首切片不能允许。未知 type 已有 NODE_HANDLER_UNAVAILABLE；IAM受理应更早拒绝，以免先落库再发现未知能力。

生产受理检查放在 RunService 实际 source 校验之后、prepareSnapshot 之前，限定 type=FLOW、非空graph、所有节点type属于上述四项。至少支持正常 START→END，并可用CODE/CONDITION覆盖真实分支计算；保留原 GraphRules/DAG/输出映射/JsonGate/输入声明/预算语义。还必须拒绝 config/node 中 skillRefs/skillRef、promptRefs/promptRef、modelEntryId/modelBinding/modelOverride、非空冻结模型/工具/提示依赖、controlledSkillVersion、未支持子图/外部依赖声明。不是“有纯节点便忽略其它字段”：按实际解析路径穷尽检查，不能让未知嵌套执行声明落入默认允许。

`FlowDriver.scope:23–38` 实际读取 inputs 和 **所有 node 的 output/error**，不是仅当前 edge 的父节点。`prepare:41–148` 查询所有入边 parent 的 definition/output/state，包含 CONDITION 分支、ERROR_EDGE、ALL/ANY join、SKIPPED/FAILED/ABANDONED。故首切片若保留这些现行为，应对实际读取集合全部判权，派生scope取全部已消费父scope要求并集；不能仅记图上直连父节点。用全部已核 run 材料作保守并集可以接受，不能漏掉未直连但 scope() 已读取的历史输出。

DEFAULT_VALUE 是纯节点也可触及的错误策略。现 RunService 在该分支先单独 db.update(node.error)，再 store.commit(defaultValue)，两者**目前不在一个明确外层事务**。必须合并到原 store.atomic（嵌套 commit 加入同事务），同时写 error/default output 的 scope，不能以正常 START→END 不触发为由保留裂缝。ERROR_EDGE、ANY竞争、SKIPPED和预算失败也要覆盖相应实际写点；不为本次新增第二套精简 FlowDriver。

### 10.3 真实 PG 写读与 material scope 必须同行的位置

`TraceStore` 构造器创建 `new TransactionTemplate(tm)`，atomic:27 默认 REQUIRED 与默认数据库隔离。它**不自动保证多个 SELECT 使用同一快照**，也不是每次方法调用都已在事务中。

| 现实际方法/语句 | 真实材料变化/读取 | 下一实现的最小接点 |
| --- | --- | --- |
| RunService.createInternal 原 persist/atomic | run.snapshot/inputs/version_refs/budget，node.definition，初始output/error为SQL NULL | 同事务 authority及初始材料写入；保留原节点插入与requestId锁 |
| TraceStore.run:31、nodes:43、lockRun:239 | select * 加载run/node全部payload | IAM使用新增窄metadata读取后再显式payload读取；lockRun先改为只锁/返回必要控制列，旧payload读取不能偷藏在锁函数 |
| RunScheduleRepository.claim | 更新run.status与event_seq/RUN_STARTED | 见10.5；有合法新current后才写状态材料与事件scope |
| TraceStore.claim:75–147 | run→node锁顺序；读snapshot.executionRules；budget.consume；更新node运行态；插attempt request_snapshot={}、checkpoint默认{}；NODE_STARTED | 判权先于snapshot/definition读取；同事务写node-state、attempt-request/checkpoint初始scope、budget变化、事件scope |
| TraceStore.freeze:150 / response:158 | 独立update request_snapshot / response+routed_model；自身没有atomic | 首pure不调用；IAM仍拒绝未迁移调用，后续应加入各自真实调用事务及scope，不能签成已完成 |
| TraceStore.commit:168–209 | CAS写node.output/output_digest和committed attempt；attempt成功；LOGIC_RESULT_COMMITTED/NODE_FINISHED | node.output、node/attempt状态及两事件scope与CAS同事务；CAS=0保留SUPERSEDED_SUBMIT事实，不能产成功材料 |
| TraceStore.fail:211–232 | node.error CAS；attempt.error更新；NODE_FAILED | error的scope分别对应实际node/attempt写入；节点CAS=0也不能错写node当前scope，保留原事实及attempt状态语义 |
| FlowDriver.prepare非READY/ANY分支 | 节点SKIPPED/FAILED、winner_node_id、部分父节点ABANDONED | 原store.atomic内附node-state与事件scope；当前scope必须覆盖全部实际读取的父材料 |
| RunService.stopForBudget:1139附近 | run.error/status、节点CANCELLED、RUN_FINISHED | 原事务中写run-error、各state及事件scope；授权503/撤权不得伪装业务预算失败 |
| RunService.aggregate:1230附近 | select * run/nodes，调用prepare；取END或最后node.output；写run.output及状态，terminal时RUN_FINISHED | 当前授权及所有已用材料先行；run-output派生父要求包含实际选中的node-output以及参与判断的材料；scope和原聚合事务同写 |
| TraceStore.event:48–62 | UPDATE event_seq后INSERT event payload；当前自身不保证原子 | 加入调用方原事务；若需要对无外层调用兜底，atomic使用REQUIRED而非REQUIRES_NEW；payload与scope必须共提交 |
| TraceStore.events:64 / RunService.status/nodeView/artifactRefs | event payload、node output/error、run inputs/output/error/versionRefs、tool_call result | 使用同域同快照material-first加载；首pure可先metadata确认不存在tool_call，若存在未迁移结果材料就拒绝，不能读取result后发现无权限 |

本次读到的三个纯FLOW类中 **没有 checkpoint 更新或恢复读取**；V200 定义 attempt.checkpoint JSONB NOT NULL DEFAULT '{}'。纯节点 claim 创建attempt时这个真实默认值也存在，应同事务写它的初始scope；首pure不能声称已覆盖后续 AgentDriver/ExecutionRecovery 真checkpoint恢复。以后覆盖{}必须另写新revision/scope，缺scope的旧checkpoint读取拒绝。request_snapshot={} 同理。不能把“当前路径不读checkpoint”写成“系统没有checkpoint”。

### 10.4 最小 MaterialRef、scope表与store方法候选

沿冻结实际类型，不改四契约、不新增业务枚举：

```java
MaterialRef(String producer, String kind, String id,
            String version, String payloadSha256);
MaterialScopeV1(int schemaVersion, MaterialRef material, SubjectKey owner,
    String sourceDecisionId, String sourceAuthorizationVersion,
    String initialCeilingSha256, Set<String> requiredRoles,
    Map<String,Set<String>> requiredEntitlements, Set<String> requiredDataLabels,
    List<String> parentScopeSha256, String scopeSha256);
AuthorizedMaterial(Object payload, MaterialScopeV1 scope);
```

`MaterialAuthorization.require(scope,current)` 只验证scope/current，不读payload、不验证producer本地归属；`scopeDigest(scope)`计算scope摘要；AuthorizedMaterial是公开record，不能因调用者自己new过就认为是可信材料。producer=engine，scope.schemaVersion=1。生产MaterialScopeStore负责实际归属、固定locator、同事务写入、摘要及同快照读取。

建议首pure只采用下列有限kind/locator，字符串是规划schema而非现有已部署目录；不要建通用插件注册表：

| kind | id/真实payload位置 | version |
| --- | --- | --- |
| run-snapshot / run-inputs / run-version-refs | run.public_id；分别对应snapshot/inputs/version_refs | 不可变受理材料的首revision |
| run-state / run-budget / run-output / run-error | run.public_id；state为明确列投影，其他对应列 | 每次实际变更产生的新revision |
| node-definition / node-state / node-output / node-error | node.id十进制规范串；FK反查run及tenant/principal | definition不可变；其他新revision，不能用同nodeId覆盖旧scope |
| attempt-state / attempt-request / attempt-checkpoint / attempt-error | attempt.id规范串；经node关联run | 每次真实变化新revision；未迁移response/routed_model不开放 |
| run-event | 已有run_event.id规范串；关联run_id/seq/type | 每条event不可变首revision；payload投影含type/seq/payload，时间若纳入须统一字符串规范 |

id用于定位，**payloadSha256必须来自该条实际材料内容**，不能是Digest(runId)、node idempotency_key、版本号、app candidate SHA或initial ceiling hash。`TraceStore.commit` 现 output_digest=Digest.sha256(Json.write(output)) 与受控材料摘要不是天然同算法；不修改原字段含义，material独立按明确表示计算。

首pure均为engine拥有的JSON材料，可定义一个固定 `engine-json-material.v1` 内容表示：把对应JSON值/明确state投影转换为JsonNode，以既有 Json.canonical 产生实际UTF-8材料字节，再对这些字节SHA256；写入前计算，写入后在同事务核对PG JSONB往返得到的相同表示。payload digest对应这份明确的JSON材料表示，不能声称它是浏览器原HTTP body字节或未来provider原stream字节。JSON对象键排序、数值/时间投影规则固定并以往返测试证明；不能跨不同序列化器无条件“看起来相同”。未来原响应manifest/provider byte语义仍需保留真正原字节，不拿此JSON表示替代。

SQL NULL 与 JSON {} 必须区分：material采用JSON null表示不存在的nullable值，读后沿原 TraceStore.json(null)→{} 的展示转换；不能因为旧API展示{}就把已有错误payload改成空。初始NULL/空checkpoint也有真实摘要。state投影必须逐列固定（例如status、started/ended、stop_reason、event watermark；node status/pause_seq/winner/committed attempt等），不能用select * 把未判权payload混成metadata。

最小表可沿 Task5 的engine.material_scope持久schema，增加一个PG生成的 `material_revision` identity、run_id FK、producer/kind/material_id、payload_sha256、完整scope JSONB，唯一约束至少覆盖producer/kind/material_id/material_revision及scopeSha256；run_id与scope.owner/root由可信写方核实。利用该表本身的revision，version=`material_revision`规范十进制串；不借event_seq当所有材料revision，也不新建一张通用版本表。写入时先分配本事务revision，再构造scope/ref并插入；允许事务回滚留下序列间隙。原root三字段留run_authority，不挤进MaterialRef.version。

scope历史行append-only，当前材料以同locator最大revision识别，旧父scope行保留。payload仍留原业务表，不复制一整套TraceStore；旧revision若业务表已覆盖而无原payload可取，read必须拒绝不可用，不能拿最新payload套旧ref。父scope的历史要求仍可由保留scope元数据验证。首pure不可变node输出/事件自然有真实可定位payload；run-output等最新投影按当前revision读取。若后续产品要求读取已覆盖历史payload，须接原业务历史存储，不能在这次首链暗自声称支持任意历史版本。

store保持原Task5两个公共职责：

- `write(MaterialScopeV1 scope)`：强制存在业务事务，核run/owner及可信writer所指定固定locator，scope摘要、payload摘要与真实写入值对应；不upsert旧scope，不REQUIRES_NEW。
- `read(MaterialRef ref, VerifiedAuthority current)`：同一已建立的业务读快照中，先只查scope metadata+run归属/current revision，调用MaterialAuthorization.require，**然后**按固定switch读取对应业务列/投影、验证payload digest，返回AuthorizedMaterial。不接受任意SQL/table/column/URL回调，不暴露“先getRaw再check”API。

为实现version分配与批量status，允许MaterialScopeStore包内/private极小辅助：固定locator的nextRevision、currentRefs(runId)、构造本域scope/父并集。跨application/infra确需调用的方法可保持具体参数/具体用途；不新增泛化material framework或第二个TraceStore。完整scope值来自本次已验initial+真实parent，sourceDecisionId/authorizationVersion来自本次合法current；不能只把旧scope换个digest更新decision。

### 10.5 metadata-first同快照与owned调度的具体最小实现

**读取。** 保留TraceStore原写入atomic，不全局改其隔离等级。可以在同一TraceStore新增一个专用只读snapshot方法，使用同PlatformTransactionManager、只读REPEATABLE_READ TransactionTemplate；只允许顶层建立该快照或加入已经是REPEATABLE_READ/更强的读事务，不能悄悄加入READ_COMMITTED后声称同快照，也不使用REQUIRES_NEW绕开正在写入的原事务。需要write事务内部读取的claim/aggregate则继续先锁run再固定metadata/授权读取，所有该run写方按原run→node锁顺序更新，不能在锁函数读取payload。

status/requestId按以下顺序：

1. 事务外只查owner/public_id/root/requestId等窄metadata取得可信call根，刷新当前权限；旧grant/initial/scope缺失当场拒绝。
2. 开只读快照，重新核metadata/root/authority generation，读取本次响应所需的**scope元数据与currentRefs**；逐scope判权，不先查snapshot/inputs/output/error/definition/event payload。
3. 在该快照内读每份payload、重算digest、构造原status对象。真实node/event枚举先metadata查id/seq，不能为省查询先select *。必要的本地expiry在返回前再核，不在快照内联网续期。
4. 三个requestId命中点均转入同一受保护响应路径。锁内重复发现已有run只能返回其窄标识，退出原写锁事务后按上述最新current读；不能从锁内返回原select *行。新proof不得改写旧grant。

原 `status` schema精确保留：runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused；controlled skill字段仅对应原特殊run，首pure不开放。nodeView保留id、node_def_id、node_type、status、pause_seq、error、output、started_at、ended_at，以及别名nodeInstanceId/nodeDefId。events保留seq/type/payload/created_at。paused保留nodeInstanceId/nodeDefId/pauseSeq/allowedActions或null。pure无tool_call的fileRefs应来自metadata确认确无记录后的真实空列表，不从假fixture写死。读取字段缺scope就拒绝，不能删字段“兼容”。

**调度。** 现claim在RunAuthority.current之前就写RUN_STARTED，不能用入库时旧decision作为新事实来源。最小IAM调整留在现RunScheduleRepository/RunScheduler/RunService：

- claim只窄查符合条件的run和已有authority，获取原dispatch owner/generation/lease，先不把PENDING改RUNNING、不写RUN_STARTED；不联网、不读payload，仍占原有限slot。旧缺grant/blocked/expired不领。
- 实际worker开始后，RunAuthority.current用PG该run的原main Grant执行run.advance resume+authorizeRetained；同scope证明通过后，进入短事务，重核grant/root/initial未并发改变、dispatch owner/generation/未过期、本地current有效，才完成原PENDING→RUNNING与RUN_STARTED及scope。已RUNNING恢复不重复发RUN_STARTED。
- 给这一“已领但尚未开始”的短路径做明确lease校验；现repository.valid要求status=RUNNING，不能直接拿它校验PENDING。使用本类内窄检查PENDING/RUNNING+exact generation，不能把mayAdvance全局改成PENDING也可执行业务。
- 失败时记录403阻断或503退避，释放原owned slot/lease；不产成功事件、不改变成虚假FAILED业务结果。所有new动作的scope/source来自真实current，而非scheduler机器身份。
- 节点prepare/claim/execute/commit及aggregate沿同一个已验证当前动作显式传证；若进入下一个真正新节点则重新RunAuthority.current。流转期间proof到期/lease被换，提交前本地核验阻止写logical result。原CAS失败SUPERSEDED_SUBMIT与现ownership语义保留。

这不添加第二个调度器、不在队列放ThreadLocal proof、不缓存当前HTTP证据给别的run。START→END可通过原owned调度真实完成，但支持状态/事件材料这一段不可省略。

### 10.6 originalGrant 的最小fixture锚点与实际调用点

root要求的是 **原main Grant确实由gateway→engine RunController.run受理call建立**，不只是resource.kind=app-version。冻结LoopbackAuthority.Grant目前包含id/ceiling/resource/expires/origin/sourceExpires/version/policyVersion/integration，不能从这些字段恢复最初action/target。

最小建议在该现有有限record只新增一个 `ServiceCall originalCall` 分量：成功verifySubject后、最初exchange创建Grant时存入这次已核caller=gateway的call；callee/action分别须engine及`proxy.engine.post.RunController.run`。固定exchange分支已有caller=gateway限制，故不必另加可变caller字段。originalCall不是从resume请求重填，不是从HTTP body/新runId猜测。

local resume同时要求 originalCall.target=engine、action为上述run action、originalCall.resource与Grant.root全等、root.kind=app-version、origin PORTAL，并核authenticated caller=engine和local tuple；query metadata Grant、app-version read Grant、debug/evaluation Grant均拒绝。forward/resume保留同Grant，不变originalCall；fixture approver另建Grant投影时也原样携带（与E1a origin一致），不能意外丢失或将approver call覆盖originalCall。E1a扩展record的实际冻结分量需统一适配，不复制fixture或开新map。

具体production调用点：

- GatewayController真实主exchange仅构造原run入口call；源字符串同query。
- RunController.run取得实际入口frame；RunService新建在同事务保存main root/grant/fullinitial/origin与真实app_version_id。
- RunAuthority.current从run_authority构造local call，用engine client.resume后engine authorizeRetained；本地run归属、material要求与dispatch校验独立于fixture originalCall校验。
- ServiceRoutePolicy内嵌唯一tuple、HttpCurrentAuthorityClient受限local出票入口、TrustedJwtDecoder本机验签按第6.1节裁决；ServiceAuthorization.HTTP route不改成接受local。
- RunService.driveRecovered/drive、FlowDriver.scope/prepare/execute、TraceStore.claim/commit/fail/event及RunService.aggregate/stopForBudget传递显式当前证据或经过本域受保护材料，不能再走旧Actor-only权限来源。

actual authority尚未生产接入；fixture该字段和签名链正例只证明有限测试profile，不替代真实authority对持久Grant原call/consumer绑定的支持。原认证origin ID也不替代originalCall，二者含义不同。

### 10.7 装配/迁移补核与有限新增验证

实际三个application.yml都仅有旧agent.security.jwt-secret/issuer结构，gateway另有allow-embed=true和dev-auth占位；均未见mode/service-id/portal/authority完整配置。三个domain扫描中仅app的 `infra/PolicyResolverSecurity` 提供独立机器链：conditional agent.policy-resolver.enabled、Order(1)、exact POST PolicyRequestVerifier.PATH、固定验签proof；它不是published的普通service装配。engine/gateway未发现自己的ServiceAuthorization/RouteCoverageVerifier装配。共同SecurityConfiguration事实仍以D2冻结为准；本次没有读取其changing live source。app原机器链的精确match与proof生命周期必须保留，不以新普通app链抢占或全面permit `/internal/**`。

现engine源迁移列表仍V200–V215；无run_authority/material_scope既有生产迁移。V200真实run/node/attempt/event列与nullable语义见10.3；V215是dispatch字段。这里只确认源码，不查Flyway、不占V216，不创建/修改迁移。正式实施先核新工程授权库的已应用顺序，沿当时下一连续版本增加side tables与必要索引。

补充真实测试必须包括：CODE错误→DEFAULT_VALUE事务回滚；CONDITION/ANY/ERROR_EDGE父scope并集；scope(run)读取无直连历史node仍拒绝越权；claim的{}request/checkpoint初始scope；NODE_STARTED/LOGIC_RESULT_COMMITTED/NODE_FINISHED/RUN_FINISHED与payload回滚；status在worker完成输出的竞态下不遗漏scope；READ_COMMITTED多SELECT不能冒充同快照；数值/NULL/时间canonical往返摘要；旧revision不能读取新payload；PENDING已领lease但current失败不写RUN_STARTED；同root但originalCall为app.version.read的Grant不得run.advance。每项均需合法对照，不能把生产全部拒绝计作纯FLOW正例。

本追加把前文待核的真实类/字段/节点/事务点具体化；只有方案已核，source未改、未执行测试。首链要实际可用，必须同时闭合published pointer守卫、初始与后续材料scope、原status/requestId、合法local current和owned调度；未知child/model/stream与完整Task5材料/历史/批准验收继续关闭且保留。

### 10.8 受理屏障与 initial A/B、current A 的精确边界

本节按root补充裁决精化前文第5/8节关于initial A/B、current A的表述；**不得把“完整值仍为A/B”解释为此时必然允许真实新run提交**，不修改原Task5计划。

首条真实成功链以受理时current仍覆盖本次实际初始材料requirements为前提。若保守材料策略把initial A/B落实为本次requirements A/B，则合法首次PG成功正例应为 **initial A/B、current A/B**，同事务保存initial A/B与scope A/B，正常原结构响应和owned调度均通过。不能用current A创建一条明知自己接下来无法安全读取/返回的run来凑“保留完整initial”成功测试。

若受理刷新已经得到 **initial A/B、current A**，而本次inputs/snapshot/初始材料要求A/B，RunService的**提交前受理屏障**必须对即将落库的真实scope集合调用当前覆盖校验并拒绝；run/authority/scope/nodes全回滚或未写入，scheduler.created/业务发送为0。禁止先commit并启动调度，再由原完整status抛403，使用户无法知道已经受理。除非另有已明确批准且可验证的产品受理语义，本切片不另造“受理成功但响应无权”的隐式行为，也不把current A反写成initial/scope A降低要求。

要验证“历史完整值不得缩小”，使用真实顺序：先在 **A/B覆盖A/B** 时成功受理并持久，之后闩锁收窄到A，再触发status/requestId读取、下一节点/恢复动作。此时PG initial A/B和已存在scope A/B原样保留；当前A拒绝历史payload及推进，不回填、不改旧grant、不把原事实改成成功。这与“首次受理前已不足则拒绝”是两个不同时间点，测试应分别命名与断言。

E1a retained initial A/B/current A的正例仅证明同次验签后的完整值保留、current不扩大和proof生命周期；它不承诺真实PG新建成功。原Task5关于历史A/B、当前A、同事务scope及拒绝外发的要求通过上述先合法受理再收窄的真实路径保留，不为满足某个fixture排列而引入静默部分成功。

提交后到响应发送前若发生**新的**撤权/到期，仍须按当前响应保护拒绝泄露；这是不可消除的外部权限变化竞态，不能据此允许在提交屏障已知current不足时创建。联合测试应分别证明提交屏障没有已知不可返回状态，以及原响应在后发撤权时不越权、不自动续票、不重复业务受理。
