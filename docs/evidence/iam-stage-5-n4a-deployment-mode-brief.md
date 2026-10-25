# N4a 可信部署模式与旧签发器一致性实施说明

本任务依赖已验收N3/I1/I2；只解决部署模式一致性。用户已授权按v1.2持续开发，暂无真实联调环境，继续隔离端点/进程验证。不得因环境缺失停工或再次索取许可。无需工期安排。

## 工作边界与基线

唯一工程 G:/207/项目/算法重组工具/代码/agent-platform；所有shell显式workdir。不得访问、复用、关联相邻旧实现、数据库、进程、缓存或配置。明确授权的外部设计执行文档位于 G:/207/项目/算法重组工具/文档/设计/详细设计/智能体构建，但实现者只读，不改索引。先读本工程AGENTS.md、docs/implementation-brief.md、contracts/README.md及IAM计划/root-decisions，按已批准v1.2和固定技术版本落实；不重开设计讨论。

当前N3/I1/I2同reviewer Spec compliant / Task quality Approved，Critical0/Important0。复审 docs/evidence/review-iam-stage-5-n3.md 30396B SHA c71a48ede77c4a5112deaa2e7a3df72ef2a39ad1192a7b001f9f23ee93615e92，保留原18890B前缀。源码367，当前manifest docs/evidence/iam-stage-5-n3-admission-app-client-fix/source-after.json SHA730a2b8c74caa0c47fc1244b47055d956776e3df4b2a0f125d42f4b093b92656；target1798，manifest SHA1a4ac516fa75fe4ffcb706db938dc05caee476f605e8b4b10aeebd7f087f9923。原详设SHA3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f。最新8索引/授权修改前后SHA见.local/checkpoint-iam-n3-docs-before/manifest.json；T01唯一整体完成。

独占source/target和自有证据；root在交接前只改精确.local/current-work-state.json，不审计半成品。自有本地目录.local/iam-n4a-deployment-mode/，证据docs/evidence/iam-stage-5-n4a-deployment-mode/，报告docs/evidence/iam-stage-5-n4a-deployment-mode-report.md。不要修改N1/N2/N3的报告、阶段、handoff、初审/复审或root审计文件。

## 要解决的真实缺口

SecurityConfiguration.authorityModeGuard:44–56以实际Spring Environment严格Binder绑定AuthorityProperties，validateDeployment后在合法IAM时抛IAM_TRUST_CHAIN_UNAVAILABLE，发生在web listener前。ServiceTokens.issue:18起却逐次读取可变JVM agent.security.mode/spring.profiles.active/agent.dev-auth；userToken与integrationToken直接到共同private sign。实际Spring IAM与JVM test冲突、启动失败被捕获后仍可旧issue是本次核心反例。N3仅关闭RuntimeClients局部legacy入口，不能据此称全局签发一致。

不得开放production IAM：完整SecurityConfiguration在合法IAM仍须原IAM_TRUST_CHAIN_UNAVAILABLE，beforelistener。不得依据Actor、JWT/frame、decoder、bean缺失或JVM默认模式推导可信部署。

## 最小实现决策

1. 只改admin-common的SecurityConfiguration和ServiceTokens两个现有生产Java。不新建生产类、通用mode/context registry、重置API、开关、Spring多上下文框架。AuthorityProperties保持纯验证不改。RouteCoverageVerifier及两个ANY gateway例外整体留到N4b，与真实默认拒绝运行守卫一起落地；N4a不能单独加marker让coverage绿。
2. 抽出一个小的公开可信Environment绑定入口（名字自定但唯一），复用原严格Binder/NoUnboundElementsBindHandler、缺省构造及validateDeployment的真实dev-auth/server.address/activeProfiles。校验成功后将可信模式快照登记到旧ServiceTokens，返回同一validated properties。authorityModeGuard调用此入口，并保持原合法IAM prelistener异常、非IAM注册原authorityProperties singleton；不要改未知key/无效值错误优先级。隔离receiver可以显式调用同入口后装自己的有限测试chain，这不证明生产IAM已开通。
3. ServiceTokens保存最小私有classloader状态：未登记时issue默认拒绝，即使只有JVM test+profile也拒绝；登记合法dev/test后issue按冻结可信快照运行，不逐次看JVMmode/profile/dev-auth。兼容边界：未登记时userToken/integrationToken可保留旧非IAM行为，不得声称三API在未登记时全部禁用。任一合法IAM登记必须在原完整配置抛异常之前不可逆关闭共同private sign，从而issue/userToken/integrationToken全部拒绝；重复合法IAM幂等后仍原boot异常。之后合法legacy登记启动也拒绝。dev-hmac/test属于同一legacy类别，可重复合法登记；无context close/restart或JVM改值重开，无反射/reset/test后门。
4. 登记与签发使用同一短同步边界，完成IAM登记之后的签发调用、跨线程都拒绝。issue的许可判断与签名不可在可竞态两个独立锁段；private sign覆盖另外两API。只承诺同一classloader下线性化，不承诺撤回已签字符串、关闭先前已运行listener或跨classloader全局状态。无需线程竞速/CPU压测，确定性barrier验证可见性即可。
5. secretBytes是取key，不签名，原行为保持；原JWT claims/TTL/audience/issuer/key来源不改；不得把HS256纳入TrustedJwtDecoder的RS256域或作IAMfallback。权限拒绝保持明确PlatformException，不把注册失败静默降级。

## 测试与隔离

先实际编译可执行行为红，再修改生产权限行为。核心红：JVM显式test+test profile，实际更高优先级Spring合法IAM，完整原配置仍prelistener关闭/factory创建0；捕获失败后旧ServiceTokens.issue实际仍能签。保留真实失败，不以缺API/compile failure当红。新绑定入口创建前可用已有SpringApplication配置做红，不需先修mode行为。

测试只用新工程own common/engine classpath、固定具名child main场景；无兄弟业务JAR/反射target/classes、不运行主服务、不新建/探测DB、不需要真实IAM/model/ontology/K8s。子进程有界wait/timeout/kill/reap/真实exit与明确executed marker、父测试严格核断言；不能成功启动就算行为通过。不得拷贝受控runner的env/secret parser、输出secret或新增通用runner，不修改POM、Surefire fork/argLine或28脚本。沿用合法最小launcher方式，凭据只子环境引用。

实际测试矩阵：
- 未登记+仅JVMtest的issue拒绝。
- 仅实际Spring合法legacy（完整profile/dev-auth/loopback验证）可issue；随后JVM mode/profile/dev-auth变动不改变快照；测试解析JWT原claims，正向不降级成只看无异常。
- 实际更高优先级Spring IAM覆盖JVM test，完整配置在listener factory0时原异常；捕获失败/close后，三API全部拒绝；改JVM dev或再建合法legacy仍拒绝。
- 先合法legacy后合法IAM，登记完成后同线程/另一线程全部sign拒绝，保留原已签字符串可存在的边界。
- 严格未知key/missing/invalid配置与非loopback/dev/test限制保持原错误原因，无效配置不误登记；不要为了全部隔离而fork每个无效case。
- 实际isolated receiver显式调用同绑定入口可登记IAM，但完整生产配置的闭锁仍在。

已有AuthorityConfigurationTest同JVM混合合法IAM和legacy场景，必须真实隔离防sticky state污染，不依赖JUnit顺序：合法IAM位置48（complete-iam-still-closed参数）、132（explicitloopback firststartup）、150（portalCeilings完整配置）；成功dev/test/gateway legacy场景亦独立child，不把parent状态登记。保留每一原断言、factory0和decoder audience/use语义。当前application helper用setDefaultProperties:198，低于JVM；核心冲突例必须用实际高优先级PropertySource/command args，不得把default IAM误当覆盖成功。

CommonBoundaryTest:59–73的正向legacy ServiceTokens.issue也移到自己的validated child，保留JWT角色/SERVICE/tenant/principal等原断言。ServiceTicketProviderTest保留未登记负例parent，不要无意提前登记导致假通过。可以新增一个聚焦ServiceTokensDeploymentModeTest及少量固定测试child main；生产仍只两个现有类，不建通用测试进程框架。

N3 RuntimeClientsPublishedHttpTest中的explicitLegacyConstructionKeepsKnownAppSkillModelRoutes是实际受影响JVM-only正向fixture：仅将此测试迁到通过真实validated登记的独立engine child或等价最小隔离，保留实际legacy app/skill/model路由、拒绝未知service和response/HTTP断言。IAM其它19例parent不能因child吞断言。隔离receiver调用入口不许修改N3 app helper/其它生产客户端以绕过本任务。

先有限检索common/engine实际ServiceTokens三API调用/测试及当前构造的行为影响。若确需超出以下具名测试选择，先回报root实际调用点和原因，root批准前不扩大；不得为整齐数字重跑common179、七模块962、N1status/N2PG或同源码绿色。

最终受影响选择限AuthorityConfigurationTest、CommonBoundaryTest、ServiceTicketProviderTest、新mode测试（及真实child）+engine RuntimeClientsPublishedHttpTest。可通过原受控runner一轮common+engine明确类名；-am test会经过common test-JAR插件process-test-classes，若tests.jar改变必须列为测试fixture JAR，与生产JAR分开，不为JAR0口号忽略。每轮冻结实际Running的XML/source/raw，旧XML仅metadata不计运行。无需预填测试总数。

## 文件、版本、来源与保护

所有生产source/target在首次编辑/Maven前完整物理367/1798 before，SHA核对上列N3 manifests；必要的tests新文件仅自有模块。原Common+六业务/分层/HTTP边界/固定Java21 Boot3.5.0 Security6.5 Nimbus9.37.3保留，不动POM/deps/routes/SQL/contracts/frontend，未授权package/deploy/commit/push。没有HEAD/remote，不能伪造empty diff/使用HEAD基线。

保护可复用root已核101048静态基底 docs/evidence/iam-stage-5-n3-admission-app-client-fix/protected-after.json 与.local/iam-n3-fix-root-protected-check.json，但必须准确更新root随后已授权变更：原review只追加（原18890B前缀SHA d971492e77ece4178c710185f8aaa0b159a9addb468aa01e3a67a74e46a8ceca，当前30396B/c71a48ed...），8索引按.local/checkpoint-iam-n3-docs-before/manifest.json明确beforeSha→afterSha核验，不能泛化允许任意旧证据变化。随后所有新静态证据/root脚本/备份/本brief实际补采；28runner逐SHA、8最新索引逐SHA独立核；不要重扫旧基底当作首次before要求。精确排除六runtime目录、postgres.log、root state、自有local/evidence/report及每阶段精确log/fresh-secret，不排整个.local或宽stem。after一次完整物理保护，原报告/阶段/契约/设计/许可不许重写。

实际对照固定Spring Context6.2.7 BeanFactoryPostProcessor→onRefresh、Boot3.5.0 listener时序、Boot严格Binder和JDK21同步可见性官方固定源/文档及本工程既有出处；先核可复用原件SHA再窄读实际方法/段落，写解释不变量/状态生命周期/测试隔离的注释。不得声称未读的上游、参考JDK21+35等于运行Temurin21.0.12+8或用开源名包装自行推断。新增官方来源需版本/URL/许可/SHA/实际读范围，不全缓存/SDK递归搜索。

最终冻结所有源/target前后manifest、增加修改删除完整清单及每路径归因（含fixture JAR、Surefire诊断）；完整context patch真实git apply --check/apply/replay与全部after逐字一致；正确区分relative basename descriptor，不重犯sources.jar根路径假设。每轮保存原stdout/stderr/exit、真实Running/XML/testcase名/源快照；setup/工具错保留并只续未完成步骤，不重跑已绿或重写before遮差。预期WARN/Windows Manifest-JAR344B原件保留，不静默禁检/为消噪扩测。独立报告与machinehandoff写实际命令/输出/覆盖具名方法/状态语义/来源/资源cleanup/保护结果和所有限制，返回source/target所有权后root再audit和fresh review。你只报告本任务完成，不能替root签收N4a/整个IAM或更新共享八索引。
