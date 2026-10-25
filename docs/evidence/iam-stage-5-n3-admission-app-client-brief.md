# G6 Task5 N3：原受理与固定 app published 客户端（正式授权）

用户已授权持续按计划实现；暂无真实模型/本体/IAM/K8s，继续隔离端点，不再询问环境或是否继续。时间不排程。唯一工程、每shell workdir G:/207/项目/算法重组工具/代码/agent-platform，所有旧相邻工程/配置/数据/进程完全隔离。

N2及I1已独立同reviewer Spec compliant / Task quality Approved、root全文读且处置六跨度，原review13504B前缀保持；当前review docs/evidence/review-iam-stage-5-n2.md SHA2082ee6bf56ac988942b529046f2f5443674badac085b66b0977125d3ad5ff1a。当前受影响最终25/0F/0E/0skip（两N2实际类），旧67属修复前历史，不相加。当前源码364，docs/evidence/iam-stage-5-n2-owned-flow-fix/source-after.json SHAea61bf74aeea2cce1e3d464edc4c091a6e2afc5a8a568ca048b2cae3bb6e93e0；target1764，target-after.json SHA19b714cddf010c4d7a47bde610bebf28c4126dc076ae0449668875a4d0b517d3。root before/live/frozen/replay/actualXML/raw/source及84553static+28runner+8indexes共6103912586B已核，无越权/未验证；唯一原report授权追加保留15189B前缀，复审另授权追加。八执行/维护/来源索引已登记.local/checkpoint-iam-n2-docs-before/manifest.json，并逐afterSHA核；T01仍唯一整体完成。

本稿是N3唯一要求来源。读后只读已批准 published-chain proposal §5及真实接口所需§2/§7；N1/N2/D3b报告只按具体API窄查，不能重读全部历史方案/重复已绿套件。本段要做到真实engine/app HTTP受理链，仍未经过真实GatewayController，N4才接gateway。

## 范围与依赖

- 沿 N1 材料/status、N2 owned worker、D3b app payload，接真实 RunController.run → RunService.createInternal → RuntimeClients → 独立 app HTTP/PG → 原受理事务 → owned scheduler → 全量 status。
- 只 admin POST `/admin/apps/{code}/runs`，TASK/FULL/published、显式 FLOW、START/END/CODE/CONDITION；main 为 gateway→engine `proxy.engine.post.RunController.run`，source/origin=PORTAL，root=app-version。v1 alias、显式 versionNo、CHAT/debug/controlled/evaluation/session/history/replay 等尚未迁移用途仍拒绝。
- 主要既有生产文件 RunController、RunService、RuntimeClients。不新增生产源文件/common/POM/route/yml/迁移；若实际共享 PublishedView/纯 gate 缺接口，先向 root 提供精确调用点与最小变更建议，不能自行扩展。原 IAM_TRUST_CHAIN_UNAVAILABLE 闭锁保持；本段为真实业务类与 guards 的隔离显式装配，不能声称正式全 EngineApplication 已能 IAM 启动。
- 唯一工程 G:/207/项目/算法重组工具/代码/agent-platform；每个 shell 显式 workdir；不涉及相邻旧工程。不变更 v1.2/四契约/固定版本；无 package/deploy/commit/push。无业务服务 JAR/反射兄弟 target 类加载。

## 固定可信客户端

- IAM 由验证后的 AuthorityProperties（agent.security、serviceId=engine）或明确受信隔离装配启用，不能 System 属性/decoder instanceof/缺 Bean 降级。保留明确 legacy 行为；IAM get/post/chatStream 任何旧 Actor 路径在签票和发送前拒绝，未知 service 穷尽拒绝而非 default model。
- 新具体 published 方法只接受 code 与实际绑定 frame 的私有 RetainedAuthorization，AuthorityContext.requireBound 在请求前与完整响应返回前核验；不要把 public VerifiedAuthority record 当来源证明。RunService 在实际主 frame 取得 retained，不能自己 bind 调用者构造对象。
- 仅固定可信 app origin，HTTPS，显式隔离 loopback 仅127.0.0.1 HTTP；无 userinfo/query/fragment/path 前缀、重定向、自动重试、cache。只 GET `/internal/apps/{code}/published`，code 与 current app-version root 一致，URI segment 安全构造。调用 authority.forward(current,new ServiceCall("app","app.published.read",root))，保留独立 main 的 operation/grant/origin，不 exchange/resume、不传门户 source。
- 固定 connect≤1000ms、完整响应≤2000ms、JSON 整体≤4MiB；这是本具体内部客户端的有界读取保护，不修改冻结契约。必须覆盖 headers 后未完成 body 的 deadline，超限/中断/截止关闭 subscription/连接/future，客户端有明确生命周期。不为此引通用传输框架或依赖其它业务模块。
- 正常 payload 采用实际 D3b 既有响应结构，校验 code/versionNo/sha 与 main root 及真实正整数 numeric versionId。401/403/409/503 等实际 HTTP 语义保持；协议/IO/截止/格式错误有可辨暂不可用类型，不被原 unwrap 一律502掩盖，不回显票/密钥或完整上游 body。

## 原受理和所有 requestId 分支

- 在 prepareSnapshot/其它可能联网代码之前，先穷尽拒绝不支持的 graph/config/dependency：空图、非 FLOW、未知节点、CONFIRM/AGENT/LLM/SKILL/RETRIEVE、controlled/step/debug、config.skillRefs/promptRefs/modelEntryId/modelBinding/tools/frozenPrompts/dataSources、node.skillRef/promptRef/modelOverride、dependencyVersions.modelRefs 等。合法空数组/对象/null 按既有发布结构处理，不能仅字段存在就拒绝正常空容器。
- 保留原 app guard/InputDeclarations/GraphRules DAG、预算、输出映射。原 explicit versionNo 走未迁移 versions 路径，须在任何该外发前拒绝，不能冒称 published 支持。
- 读取/准备 snapshot 后，在真实根下 requireInitialFor；current 覆盖全部即将保存的原 initial 义务，A/B→A 不得缩窄 scope 先提交再 status 失败。
- 保留 Admission.persist、requestId advisory lock 与短本地事务。取得锁之后 requireBound；新分支以 RunAuthorityStore.createPublished 取代旧 run INSERT，只实际插入一次，不 upsert/backfill。N1 createNode 取代原每个节点 INSERT，全部初始/动态/节点 scopes 同原事务。真实 numeric appVersionId 只来自实际 app PG/HTTP，public_id 由 E1b 生成，input_ref 与其一致。
- 传真实准备 snapshot/inputs/versionRefs/initialBudget/FULL/requestId/labels。完整材料写完后 callback 末再 requireBound/覆盖屏障；tx.execute 真正返回后、scheduler.created 和响应之前再检查。提交后截止保留已提交事实，阻止该失效请求继续调度通知/响应交付，且容量 slot/队列保留有明确可恢复行为。
- 三处 requestId：首次命中、容量满后的 advisory-lock 重查、persist 锁内竞态重查，所有 query 仅 owner/tenant/id/publicId/app/root 等窄 metadata。锁内已存在分支只返回标识，退出事务/锁后，依真实主 frame 的 N1 单次 protected RR PublishedView 读取并做原 verifyReplay/全量 status；不能在 RC 中刷新/读输入，也不能二次不一致 view 拼响应。
- 相同 owner/root/input/版本只一个真实 run，旧 grant/initial/origin 不变，节点提交不重复。换发布根、错 owner/root/缺 authority/旧 scope/收窄在 payload 前拒绝；相同 requestId 换版可409/拒绝，不伪造跨版历史重放。保留完整 status/node/event 结构。

## 真实隔离验证计划

- 新固定库 agent_platform_iam_admission_engine_v12 与 agent_platform_iam_admission_app_v12；root已核实际源码最大engine217/app104；作者仍先核实际源及这两个目标库的exists/history，再正常迁移至连续engine217/app104，不加迁移、不写主库或旧 D3b app 库。
- 实际两个服务 HTTP context/进程，各自类路径。engine 原 Controller/RunService/RuntimeClients 与真正 ServiceAuthorization receiver；app 原 D3b Controller/Service/PG 与真正 guards。main 可来自签名 fixture，明确尚未经过真实 GatewayController。app fixture 可 seed 真正 published PG，不声称完整发布管理工作流。
- 对实际 RunService/RunScheduler/FlowDriver 走正常 admission 和 requestId，验证 RUN_STARTED/attempt/events/scopes/output/terminal/status；worker 可以在响应前完成，仍安全全量响应。复用 N2 不重做无关 worker 套件。
- 有限并发覆盖三处 requestId/容量满/锁等待后 bound 失效；各新增写点故障全回滚；真实 commit 返回后失效不称回滚；不同 main 重放不改原绑定；初始A/B覆盖不足新增0、合法后收窄拒绝；不支持配置发送skill/model/prompt=0；pointer切版/禁用新增run/材料/节点=0。
- 客户端实际有界 HTTP：正确票/目标/root，旧 Actor 路径/未知 service/错误origin/redirect=0发送或拒绝，app401/403/409/503保留，oversize 和 headers 后 body 截止有界取消，deadline 后不泄漏线程/连接，所有端点不触主六服务。
- 使用现受控 runner / Java21 / offline Maven。先真实可编译行为红后实现；每轮 raw/actual Running/XML/确切 source 冻结，setup/断言/工具噪声与实现失败分别记录；同码绿不重跑。最终只新受理/client类、实际确受影响旧类，不 common全套/七reactor/旧D3b/耗尽probe。

## 交接、保护与源码对照

- before 精确继承最终已批准 N2 源/target，物理副本；既有 docs/证据/静态 artifacts/backups/checkpoints/old stages、runner、八索引、正式 brief 与原设计受保护。精确排 runtime 目录、postgres.log、root state、自有 N3 local/evidence/report 和逐轮预登记 log/fresh-secrets，不能宽stem或整个.local豁免；不全扫 SDK/Maven 依赖。
- 每轮归档固定授权 evidence 目录，写脚本后先核 resolved 路径与 stage目录；不复制旧stem造成路径错配。目标统计包含增加/修改/删除三类，不仅筛 after 路径。
- actual source/target before/after、完整 context patch、真实 git apply、逐字重放、保护 after、raw/Running/XML/source、有限数据库 history、upstream固定 URL/版本/许可/实际阅读方法与SHA、机器 handoff/关停资源齐全。无 HEAD 不虚构空diff/commit。
- 对照实际 Spring6.2.7 受理/事务返回、PG16.15 advisory lock/RC/RR、JDK21 HttpClient 完整 body/取消生命周期；复用原 source 先核SHA；注释解释 bound frame、真实 commit、三重 requestId、outbound 同主票/固定地址，不逐句翻译。
- 保持未完成：N4真实gateway/普通IAMmode全局统一、独立GETstatus/SSE、工具模型CHAT、完整恢复、Observer/RR1/RR2/门户浏览器/真实IAM本体K8s容量/全Task5。

## Root具体接口裁决（优先于草案式建议）

1. 正常published响应取实际D3b结构：外层ApiResponse data里的appCode、versionNo、candidateSha256、appVersionKey以及真实数字appVersionId/versionId。AppService.authorizedSnapshot把存储的code@no文本appVersionId改成真实PG id，并补appVersionKey/versionId；两个数字id必须正整数且一致，versionNo必须正整数，code@no与主root.id完全一致，candidateSha256与主root.versionDigest完全一致。candidate digest不是返回JSON的hash，不能用Json.sha(payload)替代。保留实际config/definition/type/guard。root具名读AppService567–694已确认，无需再造metadata receipt或让前端提供数字ID。

2. RuntimeClients具体published API接私有frame-bound RetainedAuthorization，用AuthorityContext.requireBound在开始、forward返回后且app HTTP发送前、完整body返回后检查。只从retained.current派生forward参数，不让public VA构造对象代表来源。可信已验证AuthorityProperties.serviceId=engine/mode=iam决定IAM；缺client/capability拒绝，不能Spring=iam/JVM=test时落入old ServiceTokens.issue。生产Spring/明确隔离@Bean都须用此可信构造，原legacy构造兼容有明确用途；不要以bean缺失/Actor形态/当前frame存在推断部署mode。普通静态ServiceTokens全局mode统一仍属N4，勿把本client护栏称全局闭合。请求只有固定app origin/具体path，无任意service URL参数。未知service（含null/blank/拼错）穷尽拒绝，legacy已知app/skill/model行为保留。

3. 原TraceStore.atomic是REQUIRED、可join ambient，Admission.persist只拥有Java slot状态、调用Supplier。故protected原受理入口先拒绝ambientTx（在任何authority/app外发前），才能以原store.atomic拥有实际commit。三处requestId复用同窄metadata→锁外statusPublished单RR→原verifyReplay→原完整status的私有组合；可在RunService提取现status的具体private组合方法，传现TraceStore.PublishedView，不复制整段status，不新建receipt/事务框架，不在锁内调用公共status的网络刷新。原N1PublishedView已有真实run/nodes/events/proof与requireCurrent，通常不需要改TraceStore。明确所有IAM候选TASK/FULL/owner/root/authority；旧legacy/CHAT/controlled不能借replay分支读payload。

4. 原受理本地Tx使用set local statement_timeout='2000ms'、lock_timeout='1500ms'限制本段实际advisory/写锁等待；仅IAM该具体Tx，不全局改legacy atomic或数据库参数。取得锁之后、各关键材料写之后与callback末仅本地requireBound，绝无锁内refresh/forward/app网络；真实store.atomic返回后、scheduler.created和返回响应之前再bound检查。保留try-with Admission.close在提交后失效时释放有限slot，已提交PENDING由合法后台新current自行推进；不把已提交事实谎称回滚或为重试再插入。

5. 生产仅三个既有文件：api/RunController.java、application/RunService.java、infra/RuntimeClients.java。保持原受理实现位置/校验/调度/响应，不用一个复制版createInternal重写全流程。少量固定用途普通结果record可表达created/replay标识，但不是授权能力。确需改Store/TraceStore/GraphRules/其它源，先给root具体调用缺口和最小差异，不自行扩大。无新框架/POM/路由JSON/配置文件/迁移。全局IAM_TRUST_CHAIN_UNAVAILABLE仍无条件闭锁；有限HTTP配置必须声明不等于正式全EngineApplication已迁移。

6. 实际HTTP测试可新增/修改engine测试和必要app模块**test-only**薄启动main/config helper，app生产源码不动。各域在自己的classpath/独立JVM或已证明隔离context运行；父engine测试不反射/载入兄弟app target/classes，不引app业务JAR。可复用D3b已经批准的app test入口，但必须证明确实使用本段新app库，不能写旧app_payload库。若需编译新app薄helper，先报告具体既有受控compile/child-launch接缝：允许固定Java21/offline的app test-compile或对该own-module test源的窄javac；setup compile不冒充测试执行，不重复D3b套件，不自行修改28个原runner/复制.env解析器。引用凭据仅原受控child env或私有STDIN，不输出/写明文到报告。

7. 预期新测试可名RuntimeClientsPublishedHttpTest、PublishedAdmissionHttpPostgresTest；按实际命名记录。最终集合是这两个新实际类 + 受影响原FlowMaterialStatusPostgresTest（status共享组合）+ OwnedFlowSchedulerPostgresTest（原RunService/构造/完整status）。明确允许这两个旧类在既有agent_platform_iam_flow_material_v12、owned_flow_v12的有限fixture写；不跑一次性旧215/216/history preflight，也不重跑N1全部/LocalCurrent/N2Materials/common/D3b/七reactor。若实际改动证明需要别的覆盖先给root具体源码依赖。本段新业务库仅agent_platform_iam_admission_engine_v12与agent_platform_iam_admission_app_v12，不动主库/旧D3b/policy/skill/model。编译与最终actual Running/XML/source分开，最终数不预填。

8. 生命周期与证据：先before source364/target1764物理副本并核最终N2manifest，核八索引最新afterSHA。既有84553静态可引用root已实际核基线与授权review追加/八索引更新，建立新的当前before；不能把旧hash机械当当前（review/八索引是已授权最新变更），补采本轮root审计/文档更新/本brief等新静态并逐项记录。保护.after一次；原文档/四契约/正式本brief也保护。唯一源/target作者，root只并发精确.local/current-work-state.json。自有.local/iam-n3-admission-app-client/、docs/evidence/iam-stage-5-n3-admission-app-client/及精确报告排除；六原runtime目录/精确postgres.log同前，runner每轮log/fresh-secret实际精确路径先登记，不能宽stem/整个.local排除。不要重做SDK/Maven扫描或复制旧stage错误路径。

9. 本轮报告docs/evidence/iam-stage-5-n3-admission-app-client-report.md，同名evidence目录含完整context patch/真实git apply/源逐字重放/source与target增改删统计/JAR0、所有物理before/final/真实redgreen raw/Running/XML/source/有限DBhistory/HTTP进程与端点清理/一次保护after/upstream实际读源与machine handoff。先实际可编译行为红再实现，setup/断言/工具与实现错误分别保留，不回退生产造红。每次源码变化或真实失败才按实际影响复验，不重复同码绿。完整冻结/报告/handoff后停止Maven、归还source/target，root审计与新独立双审后再台账登记。只回状态、实际测试摘要、报告路径和具体关注点，不自行部署/写共享索引或宣布全Task5。
