# D3b：真实已发布应用快照读取组件（正式授权）

唯一工程/workdir G:/207/项目/算法重组工具/代码/agent-platform。原v1.2/四契约/固定版本、七模块HTTP边界、独立新工程和生产IAM闭锁不变。不得读取/关联相邻旧实现。E1b及I1唯一作者均已归还source/target，独立Spec compliant / Task quality Approved并登记六执行索引与维护/来源记录。本稿现正式授权单一作者实现下述有限组件，原准备稿保留。

前置提案 docs/evidence/iam-d3b-published-payload-seam-proposal.md，SHA e8d613dd6b534723c7a175697617aef8dcdedb1503311afc73c460bcf1eb5e09，root已全文读取并接受其真实源码事实。以本稿及提案共同限定有限交付，不改名宣称全D3/E完成。

## 范围和真实入口

仅两生产文件：app application/AppService.java新增publishedAuthorized(code)及私有窄metadata/helper/专用短TransactionTemplate；api/AppController.java的真实GET /internal/apps/{code}/published在已验证部署mode=iam时必须调用该安全方法。显式legacy mode按原逻辑，未知mode/缺frame失败，不catch后回落。通过既有AuthorityProperties构造注入，不添加隐式legacy默认构造器。对应实际测试装配最小迁移允许；无POM/配置/路由JSON/迁移/common/engine/gateway修改。

安全方法不能使用AppService.get/code→draft/旧version作为元数据。get实际读draft config/definition/layout/a.*，version直接读snapshot，published现两者都用。snapshot?draft=false别名、draft/candidate/version/admin/input-schema各自能力不在本段迁移，不能偷把它们统一成published action。有限组件不解除整个AppController IAM门禁；后续必须明确禁用/迁移其它未保护handler，不能声称合法其它action已资源安全。

## 可信来源和顺序

真实MVC HandlerMethod+decoder+ServiceAuthorization已核票。当前call必须caller=engine、target=app、action=app.published.read、root.kind=app-version、origin=PORTAL；source可PORTAL或真实resume的DELEGATED，不强制forward=DELEGATED。tenant来自已验actor，不从请求/body/header补身份。无业务事务已存在才可进入，先拒绝ambient Tx，保证在线刷新不持调用方锁；不REQUIRES_NEW掩盖。

1. 同租户/URL code窄metadata join application/current app_version，选真实a.id/status/pointer/v.id/no/stored candidateSHA。无应用404；disabled/archived/无pointer/悬空version409。沿原业务判定，不能只允许status=PUBLISHED，合法DRAFT+pointer保留。
2. 从实际code@no/storedSHA重建root并比签名call.resource。旧同code no与当前pointer不符409，不自动切新版本/重试exchange；错kind/code/同no错SHA403。真实数字versionId来自PG关联，不是签名rootid。metadata-selector不得碰payload。
3. 事务外AuthorityContext.requireInitialFor(actor,actualRoot)一次，取得同frame新retained；403/503/过期直接退出。preHandle已有一次current映射，本步骤明确刷新一次，锁内不联网。
4. 专用有限超时READ_COMMITTED写允许的TransactionTemplate，先窄SELECT application ... FOR SHARE OF a，再本地requireBound。不可PG readOnly=true后FOR SHARE。设置有限语句/锁等待边界，不重试续票。
5. 锁后下一条SQL单独重读JOIN metadata，核status/a.id/pointer/v.id/no/SHA与步骤1观察值全等。不把带LEFT JOIN的单次FOR SHARE等待后join列当最新快照。状态/pointer变化409，真实root/数字关联不能变。
6. 本地requireBound后才SELECT snapshot，谓词含tenant/app_id/code/numeric id/no/storedSHA，0行/结构身份错拒绝，无draft/版本fallback。payload后核snapshot appCode/versionNo/candidateSHA及原appVersionId=code@no，沿现version投影保留appVersionKey字符串、appVersionId/versionId数字。
7. callback结束前requireBound；真正tx.execute提交返回后、Map返回前再requireBound。异常不得返回局部缓存Map。完全物化结果，不lazy流/ResultSet，不持锁跨网络或因postcommit变化自动切版本。

candidateSHA是发布时保存的候选摘要，不能用最后返回JSON整体hash替代。读取时线性化点是锁内payload读取；postcommit local check覆盖服务返回前，不声称保护至Servlet序列化/最后网络字节。内嵌config.guard尚非独立metadata策略投影，本段不能预读它假装payload前guard；后续engine guard/材料义务仍保留。

## 实际测试与隔离

优先新PublishedPayloadHttpTest，复用现app test common test-jar/有限authority与真实AppController/AppService、真实security/filter/全局MappedInterceptor/runtimecoverage、PG事务管理器；不造目标handler，不业务JAR/H2/Map替代PG。IAM属性显式装配，不能test profile误入legacy。使用受控.tools/run-iam-module-tests.mjs，不复制env读取、不打印凭据；专用PG与迁移权限以本稿末尾正式隔离授权为准。

fixture保存同一PORTAL source，实际authority metadata exchange与真实app metadata HTTP取值，另main engine run Grant再engine forward app.published.read。app模块不引用gateway业务类；报告区分这是模拟caller的真实authority+app组件，未运行真实GatewayController/RunController，不计端到端FLOW。

先真实可编译行为红再实施，每轮raw/XML/source立即归档。需要的有限实际对照：

- HTTP200完整真实snapshot/真实数字versionId/no/SHA/appVersionKey；缺票/portal直传/签名/caller-target-action/method/metadata票等guard前401/403，业务SQL0。
- 业务root/tenant/code/SHA/proof错误时payload0；current第二次403/503/过期payload0；合法current收窄且authority允许的对照不误拒。正常preHandle+explicit共两current，锁内/postcommit网络0。
- online刷新前/进行中修改application真实pointer/status可完成，证明未持锁；恢复后409/payload0。旧票不返回新snapshot，新metadata/main请求可成功。
- FOR SHARE期间另一PG连接写pointer/status真实有限lock_timeout阻塞，reader读完释放后writer成功；等锁期间推进可信Clock过期，锁后bound拒绝/payload0。所有latch有有限await/finally清理，不轮询压力。
- payload后commit前、真正commit后服务返回前分别推进Clock，局部终检失败且HTTP无成功data；此两例payload可能已读，不能谎报0。用真实DS/TM测试装饰器，不给生产故障hook；postcommit测试须super.commit完成后才暂停。
- SQL观察分类三类窄metadata与真实payload（含a.*/draft/snapshot），记录连接事务；不能沿旧粗糙leftjoin分类。失败后锁释放、无业务写，无虚构run回滚。
- published票撞snapshot/candidate/version/admin/input-schema实际guard拒绝；不把其它各自合法票的安全性计闭。显式legacy行为、DRAFT+pointer与原404/409按实际保留。

最终一次新/确受影响app组件集（包括已改真实controller装配的原metadata用例）；common源码无变则不重跑完整common，不重跑无关D2/七reactor。原PublishedMetadataHttpTest和PublishedMetadataFixtureMain的显式Receiver需AuthorityProperties bean时，仅补实际装配依赖。前者实际固定库为agent_platform_iam_metadata_v12；它的有限fixture写入与新payload专用库在下文分别列明，不能误用D2 policy库。若确实修改PublishedMetadataFixtureMain，则原跨JVMlauncher因该child fixture源变更属于受影响验证，可仅跑一次并保留原ownedPID/TCP清理；不改fixture则不重复旧smoke。命名和数量以实际报告，不将各轮或旧962相加。必要开源对照实际读取固定Spring事务/锁/生命周期等方法，写注释、来源/SHA；root最终汇总到open-source-references，不改旧证据。

## 交付和后续

报告iam-stage-4-d3b-report.md与专属证据，source/target/protected before-after、完整context patch实际apply字节一致、每轮raw/XML/source、实际PG SQL/counter/并发/锁释放、setup与行为红分开、自审/噪声/未测范围。无HEAD/remote，冻结patch不伪装Git提交。root同期文档逐文件精确处置，保护不排除同前缀brief；完成归还source/target后独立双审。

全局生产IAM仍闭锁，后续真实gateway可信realm/metadata→main、engine received snapshot比较与E1b新建调用、全status/requestId/TraceStore/material/owned调度及全Task5/portal/RR1/RR2/真实IAM验收仍保留。T01唯一完成，不排工期，不部署/推送。

## 正式授权与冻结交接（2026-10-20）

前置E1b/I1结论：review-iam-stage-5-e1b.md完整SHA565196377a0ee09022efc5314f5c7804c284caaa1315a051e3deabb2caee6194，原15557字节前缀保持；root已全文读取追加、核对两个期限边界及归档限制。六索引+维护+来源八文件登记在.local/checkpoint-iam-e1b-docs-before/manifest.json，正式授权前已再次核after SHA；四契约验证脚本本次通过。当前final source manifest为docs/evidence/iam-stage-5-e1b-i1-fix/source-after.json，SHA ee0d2c6d95bfef26ae5185b41252bba1ea46a7971d6d6a2dcefcdc42ff6803ab（先核实际文件名/manifest结构，再逐字比356项目）。

新payload测试仅可在本工程私有127.0.0.1:25432 PG以既有受控agent_v12凭据，创建/迁移/写固定新库agent_platform_iam_app_payload_v12；postgres管理库仅允许该固定库exists/create。迁移仅app既有V100–V104，不增加迁移文件、不触engine V216/skill/main/D2 policy库。测试数据用独立唯一tenant/app隔离，有限连接/latch/lock_timeout/清理，不扫描其它业务库。

受真实AppController构造注入影响，允许原PublishedMetadataHttpTest及PublishedMetadataFixtureMain在其原固定agent_platform_iam_metadata_v12库执行既有V100–V104预检/迁移和有限唯一tenant fixture；这是独立metadata库，不是agent_platform_iam_policy_v12。若childFixtureMain源码实际改动，允许一次确受影响原跨JVMlauncher；其原owned PID/TCP清理与6主服务保护保持。除此不扩数据库、进程或测试范围。若现有runner/fixture环境无法满足这些精确范围，先向root报告，不悄改库/复制凭据解析。

允许修改仅两生产文件及本稿所需app测试/有限专属fixture/证据与本任务.local辅助工具。新生产类、POM/common/gateway/engine/src配置/路由/契约/原v1.2与旧证据不可改；其它构造调用如实际需要迁移，先说明真实编译依赖。不创建业务JAR依赖、不新打包部署、不操作Git index/提交/外部发布。没有HEAD，冻结before/final完整源+实际git apply逐字核验，不伪造空Git diff。

作者开始先捕获本任务source/target/protected before，必须包含正式brief本身、八执行文档、所有既有证据及现有.local根audit/旧903备份/新906冻结物；不要用不带斜杠前缀误排同名前缀brief。root唯一并发可写文件为.local/current-work-state.json，用逐次明示root变更日志解释，不把宽目录当豁免。root不写source/target/执行文档，作者完成后归还独占权。保护原始采样不可重写，未采项及时披露，后续核验不可伪称事前SHA。

输出docs/evidence/iam-stage-4-d3b-report.md与docs/evidence/iam-stage-4-d3b/。报告附实际命名测试/命令/输出、每轮真实Running/XML与incidental分类、逐轮source、固定源码阅读/SHA、完整context patch及实际apply比对、source-target-protected机器交接、初始/最终时间与真实噪声。绿色后只一次受影响最终集合；自审再改代码才按受影响面复验。最终答复只给DONE/DONE_WITH_CONCERNS/BLOCKED/NEEDS_CONTEXT、测试摘要、报告路径与关注点。
