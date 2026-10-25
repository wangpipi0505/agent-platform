# N4b1 网关已发布运行主授权证明实施说明

## 依赖、范围和授权

用户授权以v1.2持续开发，暂无模型/本体/IAM/K8s环境，继续隔离验证；不再索取相同许可，不排工期。唯一工程 G:/207/项目/算法重组工具/代码/agent-platform，所有shell显式workdir；绝不访问/导入/关联相邻旧工程、配置、缓存、数据和进程。先读AGENTS.md、docs/implementation-brief.md、contracts/README.md及现有IAM计划/root-decisions，唯一设计仍v1.2，四契约不改。

N3/I1/I2和N4a均已root审计及独立Spec compliant/Task Quality Approved。N4a review docs/evidence/review-iam-stage-5-n4a.md 16512B SHA26888e119b87f2af23ddcc3d838750a008107047a3dfcd58ae5f5f0f3a392476。当前source368（manifest docs/evidence/iam-stage-5-n4a-deployment-mode/source-after.json SHA0dbeae6acb133e467fac95918ce911addb42b5ba55070fe502f1dcace4e8c970），target1806（对应target-after.json SHA70a01e7b910c26560580597ec1b833196cb32bc0bfb3d12248c1e60ad448f148）。最新八索引及授权before/afterSHA见.local/checkpoint-iam-n4a-docs-before/manifest.json，T01唯一整体完成。完整production IAM仍IAM_TRUST_CHAIN_UNAVAILABLE；N4a唯一可信入口SecurityConfiguration.bindDeployment(Environment)不改。

本任务只是N4b的第一个有限common组件：从真正主exchange的同一次私验结果保留用于网关响应前本地期限复核的证明。不做gateway控制器/两个ANY登记/运行guard/真实门户HTTP联合链，不把本组件记作N4b全完成或全Task5。原已接受方案docs/evidence/iam-next-published-chain-seam-proposal.md §6:170要求JSON释放前本地当前期限检查，180要求区分服务返回/网关写出/已发字节；不要求有效签名决定窗口内即时线上撤权。

唯一生产修改允许admin-common的CurrentAuthorityClient.java、HttpCurrentAuthorityClient.java。可新增一个聚焦GatewayRunAuthorizationTest.java（或准确同义名），直接受影响原CurrentAuthorityClientTest.java允许必要测试修改，但不削弱旧断言。测试helper LoopbackAuthority保持原wire；若必须加测试钩子先报root具名原因，不先改。不得改SecurityConfiguration/ServiceTokens/AuthorityProperties/其它authority组件、ServiceRoutePolicy/catalog、ResponseAuthorityClient、POM/deps/runner/config/routes/SQL/frontend/原报告与共享索引。任何额外生产文件/测试选择先回报root。无package/deploy/commit/push，无HEAD/remote不造空diff。

独占source/target和自有证据；root在交接前只改精确.local/current-work-state.json、不审计半成品。自有.local/iam-n4b1-gateway-run-authorization/、docs/evidence/iam-stage-5-n4b1-gateway-run-authorization/、报告docs/evidence/iam-stage-5-n4b1-gateway-run-authorization-report.md。不要生成通用授权/transport/测试进程框架。

## 已核现有机制和正确路线

CurrentAuthorityClient已有exchange(String,ServiceCall)，现接口已引用HttpCurrentAuthorityClient.RetainedAuthorization。HttpCurrentAuthorityClient.exchange约25–49：门户JWT严格decoder→PortalClaimMapper→platform.access→query(exchange)→ticket私验→initial ceiling/sourceKind/源期限检查。query约189–204：签名current decision验证audience/client、nonce、request_sha256、精确call、iat、布尔active/current及<=5秒期限。ticket约150–170：私验实际服务JWT target/caller/client/action/scope/route/mode、主体、grant_expires_at、authorization_version、operation、完整initial ceiling及ticket≤operation≤grant。只是返回ServiceTicket时丢弃query截止与真实grantExpiry；不能从public operation.exp补造grantExpiry。

现有gateway.authorize(engineTicket)不成立：decoder目标是gateway，实际LoopbackAuthority.authorize也要求client==call.target。forward同样要求client==原decision.target；resume生成新的DELEGATED operation。gateway不存在已登记的gateway-target run能力。不得删这些receiver检查、伪造DelegationRef、用portal.session.read顶替main，或新增outbound_current/refresh profile。现有wire已经有足够签名字段，不扩协议、不重建main/重放POST。ResponseAuthorityClient.open只支持sse/model，不借它造JSON续期。

## 最小API和不变量

1. 增加一个用途明确API，例如CurrentAuthorityClient默认拒绝的exchangeGatewayPublishedRun(String originalPortalToken, ResourceBinding publishedRoot)，由HttpCurrentAuthorityClient具体实现，返回其public final嵌套GatewayRunAuthorization（名字可调整但语义固定）。默认方法不能把普通ServiceTicket/public VA包装成真证明；不支持的实现明确拒绝。只允许validated IAM/gateway、固定target=engine/action=proxy.engine.post.RunController.run、PORTAL、app-version根（安全code@正整数版本和合法摘要，复用现有校验）；不接受任意ServiceCall/target/action。root来自后续真实metadata lookup，此阶段不自造metadata发布事实。
2. 新证明构造器私有、仅在原真正query/ticket/source全部私验成功的内部创建。保存同一次signed query current决定、实际ServiceTicket及operation、真实grant截止、已验门户source截止和原private Clock/创建client身份。必要只读ticket()/operation()/requireVerified()即可，不公开任意record/factory/copyFrom构造能力，不复用public VA/currentOnly假壳。若消费API接收外部capture必须核owner；没有此消费API则不要只为跨实例测试添加方法，私构造/owner Clock/关闭边界需明确。
3. requireVerified在任何线程仅用私有捕获/Clock/单调截止，不依赖AuthorityContext/Actor/SecurityContext ThreadLocal，不重绑public VA。实际有效期是query exp、ticket exp、operation exp、真实grant exp、source exp及保守本地单调deadline的最小值；query最大<=5秒是已有约束、不是新SLA。不得从ticket<=60秒推定current仍有效，不重计决定窗口。query验证之后还可能ticket/JWKS验证等待，返回capture前必须再次终检。
4. 在私验源/决定时保存相应单调时间预算，后续验证消耗同一窗口；不能在最后return或每次requireVerified重新给足5秒。使用System.nanoTime差值避免普通壁钟回拨延长本地窗口；不做可注入生产time开关。证明一旦过期/失败永久失效，不能Clock回拨后恢复。单调计时只是本地保守上限，不替代签名所有实际截止；原Clock验证仍须做。
5. capture绑定创建client，client close开始即使其证明失效、关闭后不再创建新证明；只为此加最小私有状态即可，不改变其它API协议，不声称既有BoundedIamHttp.close已可硬中断所有在途请求。原exchange/client/resume/forward/authorize/authorizeRetained/approver/response API保持原验证与返回语义；抽取小private helper复用真实Portal映射/私验流程，避免复制一套签名校验或放宽任何初始ceiling/数据标签/主体/root。
6. 票据/operation/initial/source/current/authorization_version必须互相一致。主操作依原PORTAL source开新exchange，不能把metadataquery grant作为main；这里不创建新local/recovery权限。保持原错误分类、固定凭据来源与TLS/loopback测试限制，不回显原票据/上游body/密钥。
7. 未来gateway在真正发engine POST前、完整缓冲后和JSON写出紧前再次调用本地requireVerified；本任务只交付可用proof和验证组件，不假称这些业务边界已接线。已发字节不能撤回，不承诺即时撤权/全wire原子性。

## TDD与有限实际测试

先写关键失败路径再完成权限行为。缺API/编译失败是setup，不当作安全行为红。新类型最小可编译接缝若为观察边界所需可以先加，但必须记录其具体源差异与不完整语义；真正红要在已编译、真正签名exchange上体现“普通票据仍有效而短current proof到期/私验等待跨期不能放行”。不要测试假造public proof或空HTTP代替实际query/验签，也不要为取得红故意写生产后门。遇到新API形状无法合理拆出行为红，向root说明实际阶段，不编造旧漏洞/伪称所有生产字节未改。

使用既有LoopbackAuthority真实HTTP签名fixture与实际HttpCurrentAuthorityClient；不改authority wire。测试至少覆盖：
- 合法main capture：原Portal subject/initial ceiling/source/originAuthenticationId、精确engine动作/root、ticket/op绑定保持；可在清理ThreadLocal后/另一线程有效期内本地复核，没有额外authority请求。
- 决定短期到期但ticket/op/grant/source仍未来时拒绝；分别验证真实source/ticket/op/grant更短边界与min语义（原合法ticket<=op<=grant关系不能为单独用例破坏后假称正常grant）。拒绝后回拨fixture Clock仍不可恢复。
- 真实query验证后、后续ticket/JWKS处理跨期限，最终API不得返回可用capture。只允许既有或经root批准的窄确定性钩子、私有Clock控制并明确是注入；不靠CPU耗尽/sleep赌博/全局TLS信任改造。若现fixture无此hook，报root选择最小可测接缝，不偷偷扩helper/生产time参数。
- 错service/deployment/root/kind/unsafe code、integration/service/workload source、不含platform.access、nonce/hash/active/current/issuer/audience/caller/target/action/主体/initial ceiling/authversion/缺字段、错误截止全部拒绝；已有具名测试可复用，不为矩阵镜像每行实现而写冗余case。真正正常对照必须验证signed ticket可被实际engine receiver接受，原普通API与receiver target约束不变。
- client关闭后/关闭中证明无效、新proof不产生；不为此宣称底层JDK连接硬取消。多个capture不复用nonce/operation/grant，meta/main分离的正式HTTP联合证据留N4b2。

最终只跑新GatewayRunAuthorizationTest与直接受影响原CurrentAuthorityClientTest；先盘点实际原exchange refactor触及调用点，若确有其它具名类需回报root原因再扩。不要common179、N4a53、engine20、N1/N2/N3PG/七模块962或同源码绿色重跑。正常受控node .tools/run-iam-module-tests.mjs stage admin-common ClassA,ClassB（Java21/offline ownm2）不改；-am test可能重建common测试fixture JAR，完整归因而非隐藏。每阶段实际Running/XML/source/raw完整冻结，旧XML仅metadata不算执行，不预填总数。

## 来源、隔离保护和交接

所有编辑/Maven前物理保存当前368source/1806target，逐SHA核对N4a manifests。保护可复用root已核109089基底 docs/evidence/iam-stage-5-n4a-deployment-mode/protected-after.json及.local/iam-n4a-root-protected-check.json；唯一随后授权旧静态变化是最新.local/checkpoint-iam-n4a-docs-before/manifest.json的8索引before→after（内部5在static，外部3在indexes），N4a review是之后新产物需真实补采，原N3review30396B不再改。所有新N4a/root证据、备份、工具、本brief逐SHA补采；28runner/8最新索引核。只精确排六runtime、postgres.log、单一root currentstate、自有local/evidence/report及各stage exactlog/fresh-secret，不排整个.local或宽stem。after一次完整物理保护，不重写before遮差异。

先核可复用固定源/许可/SHA，再实际窄读Spring Security6.5/Nimbus9.37.3的JWT/JWK验证、Java21 Clock/单调计时相关既有官方源方法和本工程原HttpCurrentAuthorityClient/AuthorityResponseVerifier/TrustedJwtDecoder。没有新加密框架或依赖，不把JDK21+35参考等同实际Temurin21.0.12+8。不声称未读源码；注释解释新proof的私验来源、期限边界、ThreadLocal区别与owner/close限制。来源在自有upstream先归档，root签收后统一更新source索引。

完整冻结source/target before/after与每路径新增修改删除归因（tests.jar/编译metadata/diagnostics包括），fullcontext patch实际git apply --check/apply/replay与全部source逐字一致，无HEAD假diff。保存所有实际运行命令/exit/raw/Running/XML/testcase和setup/tool/policy失败，区分截断摘录与完整原文；仅续未完成步骤、不重跑已绿。来源descriptor先看shape/实际entry再用，历史before JAR核before副本，别当live；红阶段测试修改与生产修改分开核。已知Windows344B/Surefire提示保留，不用禁检argLine/POM/runner消噪。固定真fixture服务器/线程/客户端有界关闭，newproof close语义和真实worker退出分别报告，未记录端点不冒称逐OS核。

报告写实际实现/测试命令与具名覆盖/red-green/setup/noise/固定源/所有限制、protections与machinehandoff；不自签收N4b1/N4b/全Task5，不改共享八索引。全部Maven和资源结束、report/handoff冻结后明确归还source/target，不再写。root将独立audit与fresh Spec/Quality review。common现JDK传输内部retry与close限制是后续具名任务，不据本capture声称整条链literal零重试/有界取消；本任务不做gateway transport或Servlet async接线。
