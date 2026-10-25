# G6 Task5 N2：原 owned scheduler 与纯 FLOW 实际推进（正式授权）

唯一工程、每次 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。不得读取、关联、导入、链接、复用或修改相邻旧代码/配置/缓存/数据/进程。用户已授权从前置任务按计划持续开发，暂无真实模型/本体/IAM/K8s，使用隔离端点，不再询问环境或是否继续。原 v1.2、四契约、固定版本、七模块 HTTP 边界与生产 IAM 闭锁不变，不排时间，不 package/deploy/commit/push/发布。

## 前置、证据与本段完成定义

N1 已独立 Spec compliant / Task quality Approved，无 Critical/Important；`docs/evidence/review-iam-stage-5-n1.md` SHA `b9576db7964c02bf1d78ccfe2354baa979583cd2a9a7660abcff02e2221b0904`，root全文读并处置全部跨度事项。实际最终69/0F/0E/0skip，362源/1740目标已冻结、重放与root独立核；14源变化/51目标变化仅engine，业务JAR变化0。N1 source manifest `docs/evidence/iam-stage-5-n1-flow-material-status/source-after.json` SHA `bbc0491672b771781405173869706a2d741673dd96b76d9b919666560a2aa51c`，target manifest SHA `6f2f43db986d1ec09a942342b469e9d9d998814889a86a2ed92a1cc7a354c706`。八索引已登记`.local/checkpoint-iam-n1-docs-before/manifest.json`；只有T01整体完成。

读本稿后读`docs/evidence/iam-n2-concrete-transaction-seams-proposal.md`（19695 bytes，SHA `dc910c15ce0d71befa2bcd43eaaebdfcbaa18b3633ca71535c4cb9f608445a58`）；它是实际N1 API上的具体接缝设计，本稿裁决优先。`docs/evidence/iam-next-published-chain-seam-proposal.md`仅读§4和必要N3接口，不重读整个历史计划。N1报告/具体root-decisions按真实接口需要窄查，不重做N1或D3b。

本段必须通过**原RunScheduler reserve/created/poll→owned dispatch→原RunService.driveRecovered/drive→原FlowDriver**，在实际PG中产生真实节点/attempt/events/output并完成聚合。允许N1 createPublished/createNode作为真实PG受理组件fixture；不能称经过原RunController/RunService.createInternal或gateway。后续N3原受理与app client、N4真实gateway链仍待；不开放全engine/全Task5/E/CHAT/工具/模型/审批/流/RR1/RR2。

唯一源/target作者，N1作者和reviewer、下一段架构代理均归还权限。root只并发写精确`.local/current-work-state.json`，无其它已授权并发源码/target/docs新增。不要改共享进度/来源索引或旧报告，交接后由root登记。

## 允许生产范围与分层

现有engine七文件：application/RunAuthority.java、RunService.java、FlowDriver.java、RunScheduler.java；infra/RunScheduleRepository.java、TraceStore.java；domain/GraphRules.java。GraphRules的增加由root明确授权，理由是纯决策应留领域层，infra不得反向依赖application。无新生产类/第二driver/common/app/gateway/skill/model/monitor/POM/yml/route JSON/框架变更。RunAuthorityStore若确有必要改原load/recordCheck而现API不足，先给root具体源码证据；不得自行扩大。MaterialScopeStore只复用现包内API，若确需最小改动先给实际依赖。

原N1公共protected writer仍只接受自有顶层RR；不放宽为通用joined接口，不加任意SQL/table/column/URL/业务Supplier回调、不引入通用事务框架/receipt/权限ThreadLocal。root采纳具体提案方案A：TraceStore固定用途方法拥有受保护组合事务，复用private写原语；GraphRules最小提取原纯准备/聚合决策；FlowDriver/RunService保留应用编排/纯节点执行。拟名startOwnedFlow/prepareAndClaimFlow/commitFlow/commitDefaultFlow/failFlow/stopFlowForBudget/aggregateFlow可按实现微调，但职责、实际提交拥有者和边界必须明确。

原legacy构造/显式test/dev-hmac行为保留；IAM必须由可信已验证部署配置或本段明确装配启用，不能System属性、decoder instanceof、数据库缺行或缺bean静默降级。允许有限显式构造用于隔离真实组件，正式Spring可信装配仍由N3接入，不声称已存在配置类。缺RunAuthority能力时protected路径默认拒绝。禁止业务服务JAR或反射加载兄弟target/classes。

## N2.1 队列只领 dispatch，之后实际当前授权

RunScheduleRepository.claimNext与claimSpecific共用protected分支：窄metadata筛选authority存在、grant有效、未DENIED/EXPIRED；UNAVAILABLE仅retry_not_before已到才允许有限重新核验，两个入口都不能绕过退避。IAM缺authority的旧run不得自动领作legacy；原legacy部署候选/队列行为保留。控制查询不带snapshot/inputs/guard_ctx/node definition/output/error。

protected claim仅写dispatch_owner/until/generation，不改PENDING→RUNNING、不写RUN_STARTED、不读业务payload。保留队列、slot、generation、owner、原lease与SKIP LOCKED；不跨网络持锁。

worker必须持有本线程原owned dispatch，以run真实binding.root构造`new ServiceCall("engine","run.advance",root)`，事务外调用RunAuthority.currentRetained；不捕获创建请求的frame/proof给其它run使用。已有RunScheduler ThreadLocal只保存dispatch；允许窄ownedDispatch(rid)取出后显式传，不加授权ThreadLocal、不把proof跨线程bind为请求frame。

startOwnedFlow在实际顶层RR按run→authority observation→node锁序核完整原binding、current成功CAS/无block、dispatch owner/generation/live lease，再授权材料/读取payload，防御检查纯FLOW，PENDING→RUNNING及RUN_STARTED/run-state/scope同提交。RUNNING重复进入不重发开始事件。mayAdvance仍仅认RUNNING，不能全局放宽PENDING执行；具体start入口单独允许owned PENDING/RUNNING。

**活性裁决：** 仅protected额外允许run.status=RUNNING、nodes非空且全部terminal的aggregate-only候选。用于最后节点/prepare已提交而proof提交后到期导致本轮退出；下次取得新current只聚合，不重跑节点，不把WAITING_EXTERNAL/无node/其它unsupported范围当正常候选。原legacy队列不扩大。

锁等待后实际dispatch/node期限检查使用真实时点（例如clock_timestamp）而非只依赖PG事务开始时now()；写CAS也带dispatch fence。不得用取消/过期旧generation写当前generation的成功或失败事实。

## N2.2 原prepare、claim、execute与真实父关系

protected driveRecovered不读取guard_ctx重建权限；protected drive(long,Actor)也必须进入同owned/current路径。每个新节点动作重新取得current；同一prepare/claim/execute/commit动作显式使用同proof与dispatch，聚合是另一个新动作，事务外重新current。N1已具备的严格originalGrant/full-initial/origin、持久current CAS/blocked检查不可省。

只允许FULL/TASK/published纯FLOW的START、END、CODE、CONDITION。snapshot经授权读取后先防御检查未知/CONFIRM/AGENT/LLM/SKILL/RETRIEVE、controlled/debug/step、未迁移工具/依赖；拒绝须早于任何实际execute/AgentDriver/外发，不能在N2worker走旧CONFIRM假approved分支。N3稍后另在prepareSnapshot外发前做受理穷尽检查；本段不替代它。

TraceStore组合方法在单RR锁定/判权后物化实际输入。FlowDriver.scope实际消费所有node output/error，不能只记录图上前驱；保守复用N1完整currentScopes/readValues允许，父union必须覆盖实际读取的每份材料。execute仅消费claim中实际授权并物化的普通工作数据，不再raw store.nodes/scope数据库读取；工作数据无授权能力，仍显式核proof/dispatch，不新增receipt。

GraphRules纯准备保持原edge顺序、CONDITION选择/ERROR_EDGE转换、ALL/ANY/skipCountsAs与真实状态语义。ANY选择原incoming中实际SUCCEEDED的首个winner，不擅改为转换后逻辑成功；winner只写一次，只ABANDON独占且READY/RUNNING的直接前驱，共享消费者保留。每个实际changed row追加scope；原FAILED/SKIPPED事件保留；原ANY无事件的winner/ABANDONED仍无需造新事件，但不可漏scope。CAS0不造该行成功scope。

准备写后重新物化本事务新state及新scope供后续claim/prepare/aggregate消费，不把旧READY map视为当前值。原运行级maxParallel、node lease/attempt_no、首次steps预算、attempt默认request/checkpoint/error初始材料、NODE_STARTED保持。

**预算裁决：** prepareAndClaimFlow可具体合并同RR，但预算耗尽在任何claim/node RUNNING/attempt/预算写之前预检，返回有限BudgetExhausted(code)，提交已完成prepare副作用并仍做真实提交后期限终检，保留旧prepare先提交的语义。随后用同有效proof调用具体budget停止；proof已失效则不写，下次新动作重核。真正SQL/材料/完整性错误全回滚，不吞为预算结果；私有复用实际预算规则，不能复制两套不一致算法。

claim实际attempt的初始scope保存所有已消费执行材料为父；commit从本attempt真实metadata/scope恢复这些父的传递义务并与本次已消费材料并集。不能把调用者public scope record/候选SHA/自造父当证明；别的节点执行期间更新输出，也不能丢掉本次execute实际使用旧材料的要求。旧ref业务payload被覆盖不可读的N1边界不变，不伪造历史副本。

## N2.3 原commit/default/fail/budget/aggregate实际写点

具体commitFlow与commitDefaultFlow复用同私有提交实现。DEFAULT_VALUE的node.error必须进入同个node/dispatch/lease/committed CAS约束的成功UPDATE，与default output、node/attempt state、error/output scopes及原LOGIC_RESULT_COMMITTED/NODE_FINISHED同事务。CAS0保留原SUPERSEDED_SUBMIT、不写default error/成功scope/成功事件；不得先独立UPDATE error再调用另一个顶层commit。

有效dispatch下node CAS0的fail保留原真实attempt失败事实、无逻辑NODE_FAILED/node scope；dispatch本身失效的fail退出，不把旧执行结果写给新代。失效dispatch的commit返回superseded false；若保留原note，先核真实attempt/node/run FK及有效本地proof/authority，不凭过期proof写note。权限错误先于普通业务结果处理。

stopFlowForBudget仅接真实预算业务原因，run FAILED/stop/error/ended、实际被CANCELLED节点state、RUN_FINISHED及scope同Tx；不接权限错误/短proof过期/serialization/DB故障/GUARD_SNAPSHOT_INVALID冒充预算。任一点故障全回滚。

aggregateFlow一个RR内授权物化→按原节点顺序执行准备决策/副作用→刷新新值→GraphRules纯聚合→实际run UPDATE→terminal事件/scopes。保留ERROR_EDGE失败豁免、runnable/WAITING_STEP/WAITING_EXTERNAL/WAITING_APPROVAL顺序；pure gate不开放这些外部/审批用途。输出按step_seq首个**SQL非NULL**的END，否则最后node输出；沿N1 sqlNull flag区分显式JSONnull和SQLNULL，原output_digest/展示语义保留。run-state/output及事件父必须覆盖全部实际消费材料和本事务新prepare scopes，不仅选中output。terminal早返回不重复RUN_FINISHED。

每个固定TraceStore方法自己拥有顶层RR、callback末与实际commit返回后同proof终检。初始化仍沿N1原受理Tx规则，无外层commit冒称。提交后才过期时保留已提交事实，拒绝继续业务/成功交付，不假称回滚。

## N2.4 失败分类、有限退避和周期入口

root接受提案短proof裁决：**本次短ticket/operation/proof到期不等于原持久grant永久失效**。RunAuthority最小分开网络resume/authorize的真实403/503、原grant真实到期、与本地proof校验/成功CAS后终检的错误分类。真实在线拒绝沿原完整Binding/check_version CAS记录；真实grant过期仍EXPIRED。仅本次短proof到期时不写DENIED/EXPIRED永久封死有效grant，中止动作、有限dispatch退避，下次新current；原I1的CAS UPDATE后及真实commit返回后的两终检仍必须保留。

writer材料覆盖/完整性拒绝、旧decision/已有block、serialization或数据库失败不假装新线上拒绝；不得新load一个Binding后盲写block覆盖新decision。现API不足先给root实际证据，不自造action receipt。原安全负例若仅“短proof到期写永久block”期望需变化，明确指出新语义、最小调整并新增有效grant下重新current恢复正例；原零成功交付/rollback/CAS不覆盖等安全断言不可删改为弱断言。

RunScheduler允许固定deferOwned(rid,boundedDelay)：通过现owned dispatch的release CAS保存有界dispatch_next_at，立即退出；finally第二次release因owner已清CAS0，不能把退避缩成poll，slot恰归还一次。retry_not_before与dispatch_next_at都检查，claimSpecific不绕过。无无界重试/紧循环/压力测试。

权限、期限、完整性、写入基础设施错误必须在普通业务异常/default/fail/budget分流前退出。尤其commit失败不能被原catch(Exception)转成RUNTIME_ERROR/DEFAULT_VALUE/NODE_FAILED；可以将具体execute业务异常处理与事务提交分开，不以message前缀混认所有错误。数据库未知/序列化错误保留真实类型，不伪装503或凭猜测写新失败事实。

原RunService每poll的resumeExternalFacts在metadata候选SQL/进Tx前排除protected未迁移工具事实，保留legacy。renewLeases只续真实live控制lease，不写scope事实、不复活过期租约。独立ExecutionRecovery/Approval/debug周期入口未被本段迁移，N2fixture无其外部候选，报告保持全恢复域/生产IAM未签收；不能因pure switch就宣称那些入口安全完成。

## 实际隔离验证与一次最终受影响集

新固定库`agent_platform_iam_owned_flow_v12`，本工程私有PG127.0.0.1:25432/agent_v12，凭据只经原受控runner引用，不输出、不复制.env parser。postgres管理连接只可固定exists/create；先核实际source Vmax=217与目标history，再正常应用至当前连续版本。N2预期不需新迁移；如确有schema需要先报告具体依赖，不占218、不改V216/V217、不触主agent_platform_v12或app/policy/skill/model库。G2下一engine218仍候选，skill312/G2skill313仍候选。

新增有限真实PG测试可按OwnedFlowSchedulerPostgresTest/OwnedFlowMaterialsPostgresTest等实际命名，复用N1 signed私有proof/真实PG fixture，不能H2/Map/mockstore/假targethandler/第二driver代替实际owned worker。所有真实网络authority在事务外，提供计数/真实Tx观测。有限验证至少：

- 原reserve/created/poll→driveRecovered→START→END真实output/attempt/events/scopes；原PORTAL已到期而持久grant有效仍可通过真实DELEGATED继续，不宣称模型RR2/observer闭环。
- 初始current403/503的RUN_STARTED/attempt/payload增量0；queue排除DENIED/EXPIRED/无authority，503 retry前两个claim入口均不空转，slot恰归还，恢复有一次新current成功。
- CONDITION/ALL/ANY/ERROR_EDGE真实状态/winner/ABANDONED/共享分支和父范围；claim后其它node输出变化时原执行输入父要求不丢，execute无raw payload查询。
- 预算预检保留prepare副作用但attempt/消耗0，真实budget终止各写点故障全回滚；DEFAULT_VALUE正常和error/output/attempt/event/scope中故障同回滚，不能权限失败触发fallback。
- 真实旧dispatch generation、短lease等锁过期、node CAS0、有效dispatch下fail CAS0事实保留；CAS0无成功scope/错误default写；持续控制心跳不破坏digest。
- 聚合同事务多次prepare见新值，SQL NULL/JSONnull END fallback、ERROR_EDGE豁免、RUN_FINISHED恰一次；最后节点提交后proof到期→有效grant未永久blocked→aggregate-only新current完成且节点执行计数不增。
- 真正PG Lock有界观测后推进Clock、callback末/实际commit后到期、持久denial CAS排队；serialization/infrastructure错误不转业务失败或503，不在Tx/锁中联网。
- legacy无authority原调度/流程实际对照与protected旧Actor/raw入口拒绝；不改主库既有legacy集来凑绿。

先留可编译真实行为红，再实施，setup/断言/观测噪声分别记录，不回改代码造红、不拼历史轮总数。通过后一次最终集合：N2新增实际类+确受影响N1三类；GraphRules纯测试若其实现变化则纳入；RunAuthority本地分类改动纳入原LocalCurrent十二例（含I1）；若进一步动共用Store/initial语义则先确认并纳入原相关E1b类。明确允许这些既有隔离flow_material_v12/engine_v12有限fixture写；不重跑一次性旧target215/216 preflight。其它旧legacy类可testCompile，硬编码主库的测试不可运行。最终以actual Running/XML/source计，不预填数量。

使用既有`.tools/run-iam-module-tests.mjs`、固定Java21/offline Maven；stage名按`iam-stage-5-n2-...`，tests参数只用允许逗号/class#method，不用“+”。不跑common全套/七reactor/D3b/D2/无关套件或旧StackOverflow/资源耗尽probe。同码已绿不重复，源码变化/真实失败才按受影响复验。

## before保护、开源对照与交接

开始先不可覆盖捕获source362、全target1740及物理副本，核等于N1final；root八索引afterSHA作为当前文档基线。保护contracts/AGENTS/root POM/正式本brief、既有docs证据、.local历史root audit/脚本/静态artifacts/backups/checkpoints/各旧stage冻结物（含N1所有原件）、8执行/维护/来源文档，以及原受控runner相关28实际脚本。无无关SDK/Maven全树扫描，固定来源记录不等于全部工具逐字采样。

before明确排除六运行目录`.local/minio/pgdata/redis/logs/tmp/wsl/`各自目录，精确`.local/postgres.log`、精确root current-work-state、自己的`.local/iam-n2-owned-flow/`和`docs/evidence/iam-stage-5-n2-owned-flow/`目录及精确报告。正式brief同stem但在目录外，必须保护。测试runner新stage日志与fresh-secrets按实际精确路径在本段root-decisions记录，首次执行前登记；不排整个.local或宽stem旧证据。保护after一次，实际采样清单/未采边界透明；出现非授权旧文件漂移保留原失败先向root报告，不改before遮差异。

只读固定Spring6.2.7/PG16.15锁、RR、SKIP LOCKED/真实期限与现GraphRules实际机制所需上游源码；已有N1 upstream可复用原件并核SHA，记录实际阅读的方法/URL/版本/许可，不冒称全读。注释解释事务拥有者、父范围、预算准备窗口、dispatch fence/短proof与grant区别；不写逐句翻译废注释。共享来源索引由root双审后更新。

交付`docs/evidence/iam-stage-5-n2-owned-flow-report.md`与同名目录，含真实命令/raw/Running/XML/source、每轮实际分类/incidental、数据库history、完整context patch/真实git apply逐字重放、source/target/protection before-after/物理副本与机器handoff、未验边界。无HEAD/remote，不虚构空diff/commit，不改global/root Git属性。完整冻结/报告/机器交接后归还source/target，停止Maven，再独立双审；只回状态、实际测试摘要、报告路径与关注点，不自行更新全Task5/台账或部署。必要技术问题向root给源码事实，已有用户授权不重复问。
