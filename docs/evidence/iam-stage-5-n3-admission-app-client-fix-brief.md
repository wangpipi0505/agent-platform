# N3 I1/I2 定向修复要求

本要求由 root 在完整读取独立审查和具名生产源码后批准。用户已授权按计划持续开发、没有真实联调环境时使用隔离测试端点；不再请求重复许可。唯一工作目录为 G:/207/项目/算法重组工具/代码/agent-platform，每条 shell 显式指定。禁止访问、引用或影响相邻旧工程及其配置、数据、进程。

## 依据与本次基线

- 原 brief：iam-stage-5-n3-admission-app-client-brief.md，17150 B，SHA256 cf5246583b8fea428592e2cefe27a2d1cc05bd31665a8c704b84911fc2865a93。
- 已批准 POM 例外：iam-stage-5-n3-admission-app-client/scope-addendum.md，2681 B，SHA256 b2d15a89d4b12cd135c3acd3db2c1bf6ebfd048e178bd80e05d76f981c1b15d9。本轮不得再改 POM 或依赖。
- 原报告：iam-stage-5-n3-admission-app-client-report.md，28426 B，SHA256 0312f487df70a93a124f4e183834c8d88eb3dc251333a7862351fc6a352507a4，保持全部原字节，不追加或改写。
- 独立审查：review-iam-stage-5-n3.md，18890 B，SHA256 d971492e77ece4178c710185f8aaa0b159a9addb468aa01e3a67a74e46a8ceca。Spec Issues found、Quality Needs fixes，只有 I1/I2 两项 Important；原审查保持字节，后续复审由原 reviewer 另行追加。
- 当前 source367 manifest SHA256 297a5b55920d7bc38adb8a4af8d7426fcbaa9827cf81a6805d89d76fd48603f5；target1795 manifest SHA256 eb7cc047f3181f1324a2859090bc2648a0d0e02ba8e672fbe86d689170543876。文件见原 N3 证据目录的 source-after.json、target-after.json。
- Root 已独立物理核 before364/1764、baseline364、live/frozen/replay各367、live/frozen target各1795及完整清单；15轮实际 Running/XML/source、23上游文件、4次窄编译、实际app115项classpath与Apache源归档/3个既有jar；90336静态+28runner+8索引总6841049754 B无漂移或未验证项。记录在 .local/iam-n3-root-fast-check.json、iam-n3-root-protected-check.json、iam-n3-root-audit-addendum.json。
- 先读 receiving-code-review 技能，按源码确认问题再实施。不要把原始历史绿色、编译/setup失败、SHA重建或root四项审计工具错误重写成新的业务证据。

## 允许的最小范围与所有权

只允许修改 engine-runtime 下两个原生产文件 application/RunService.java、infra/RuntimeClients.java，以及两个既有测试 PublishedAdmissionHttpPostgresTest.java、RuntimeClientsPublishedHttpTest.java。其余源码、POM、app薄helper、common、schema/migration、route catalog、runner、配置、前端、契约、原设计及八份共享索引均不改。禁止新生产文件、通用传输/授权框架、公共reset/全局重试属性及跨业务JAR/反射兄弟target。

如果确定性排队测试确需接缝，可在原 RuntimeClients 文件内增加很小的 package-private 构造/工厂，接收明确所有权的 ExecutorService；正常装配仍创建原owned虚拟线程executor，配置校验和缺能力闭锁不能改变。不能增加部署开关或从Actor/frame/Bean存在与否推断mode。优先使用现有测试接缝；缺范围时先给具体源码依据，未经root确认不扩张。

自有证据：docs/evidence/iam-stage-5-n3-admission-app-client-fix/；自有本地工作目录 .local/iam-n3-admission-app-client-fix/；新报告 docs/evidence/iam-stage-5-n3-admission-app-client-fix-report.md。原N3报告、handoff、所有阶段和root审计产物保持原字节。开始变更前物理复制367source/1795target，逐项与本基线一致。

允许复用root已实际核过的90336保护条目作为不可变基底，不必在before重复哈希旧证据；必须补捕之后全部新增静态证据、原审查、root完整diff/审计/工具错误记录及本brief，并覆盖全部原路径。八索引使用当前N2 checkpoint最新afterSHA。after仍只做一次全量物理保护核对；异常逐项登记，不用缺失/忙文件冒充已核。精确排除root state、自有目录/报告、原六runtime目录和原精确postgres日志；每轮log/fresh-secret先具名登记，不宽豁免.local或stem。

## I1：提交后查询与调度通知之间

实际原顺序为 store.atomic真正返回 → requireBound → 独立responseMetadata queryForMap → scheduler.created → requireBound。事务内SET LOCAL超时不再覆盖这次查询；连接获取或查询等待可跨过proof期限。

保留真正commit返回后的检查，在提交后metadata查询已返回、新建分支scheduler.created的紧前再次本地 requireBound(admissionProof)。不得在锁内刷新，不回滚或回填已经提交的run/scopes，不修改scheduler合法fresh-current恢复机制。不能把失效请求的nudge问题扩大成后台绕过授权执行。

新增具名真实受理负例：使用已有afterQuery接缝，对真正提交后该metadata SELECT返回时仅触发一次proof过期，或有界真实数据库等待。若用注入时钟，明确标记控制钩子而非自然等待。断言拒绝、createdCalls=0、原1run/2nodes/15scopes/PENDING/attempt0保留、Admission slot恢复；之后新的合法main/poll仍可恢复。观察created时必须透明调用super，不能用空实现假造零调度。现有commit回调到期测试原断言保留。

## I2：排队、连接等待后真正发出HTTP之前

Caller frame-bound检查必须保留；不能跨线程调用ThreadLocal requireBound，也不能把public VA重绑为proof。仅在Callable开头requireVerified仍不足，因为随后pool lease/connect可继续等待。

Root请原reviewer复核的固定Apache5.4.4实际顺序：HttpClientBuilder.build944–963构造 ProtocolExec → ConnectExec → MainClientExec；ProtocolExec.execute192转下一层；ConnectExec.execute122–127取得endpoint，130–192完成route/connect/TLS，198才进入MainClientExec；MainClientExec.execute118执行httpProcessor.process，120才调用execRuntime.execute；InternalExecRuntime.execute222–236检查取消但不认识proof期限。

使用该具体客户端 addRequestInterceptorLast 的实际请求处理接缝：每次published调用创建独立HttpClientContext，携带真正私构造RetainedAuthorization和本次单调时钟deadline，通过已有execute(request,context,responseHandler)重载传递。在lease/connect等待之后、应用GET headers发出之前，缺proof/deadline则拒绝，调用retained.requireVerified并检查deadline。可以在任务入口提前检查，但不能以它替代此处理接缝。使用现有private proof的验证时钟，不增加common API。保持同一个每请求deadline与caller完整body上限、取消/close一致；避免任务或连接等待后重新起算2秒。只有本具体fixed GET用途，不造通用permit/context注册框架。

工作线程授权失效必须从ExecutionException保留为明确授权拒绝，不能统一吞成APP_PUBLISHED_UNAVAILABLE/503；caller仍执行原frame检查与finally取消/资源清理。实际HTTP 401/403/409/503、协议/IO异常分类、4MiB、identity、无重发/redirect/auth挑战、固定origin及生命周期均保持。检查与wire write并非原子撤权，不承诺撤回已经发送的字节。现有Apache cancel支持timeout之后的闭锁，不把I2误称必然timeout后仍发送。

新增具名实际负例：有界阻住真实executor任务开始，pre-submit检查通过后使proof失效，在传输总期限仍有效时释放；app实际接收0、明确拒绝、client正常结束和owned资源可清理。测试执行器可使用透明的真实有界线程池/队列，所有latch均有超时和finally释放；不靠sleep/CPU耗尽/假HTTP客户端。至少通过源顺序及该真实请求验证实际request interceptor生效；如果还需额外确定性连接等待负例，给最小方案，不改TLS全局信任或放宽origin。

## 定向验证、冻结及交接

先在当前未修复生产代码上取得两项对应真实行为红。测试编译/setup/观察错误只记工具或装配噪声，不充行为红。修复后最终只运行两个实际受影响类 PublishedAdmissionHttpPostgresTest、RuntimeClientsPublishedHttpTest；原N1 status11/N2 scheduler10已绿且相关逻辑未变时不重复。若实际修改范围或源码显示新影响，先给具名额外验证理由。

仍用原 .tools/run-iam-module-tests.mjs，stage使用iam-stage-5-n3-fix-唯一名，-Dtest类/方法列表逗号或#、不用+。固定fixture仅agent_platform_iam_admission_engine_v12、agent_platform_iam_admission_app_v12；正常既有Flyway校验，无新库/迁移/旧history preflight、主服务、旧D3b或七模块回归。只在app helper未改且其class SHA一致时复用原合法窄编译产物，不重跑0测试setup。

保留每次raw、实际Running对应XML、完整物理source快照；旧incidental XML仅登记不算执行。报告列实际命令、具名测试与真实输出，不预填数量，不把分批组合写成单次全绿，不合并历史失败为当前绿色。final01原40绿色、final03原client19及本轮覆盖分别定位；说明哪些代码/测试差异使新证据取代旧证据。

最终包含before/after完整source/target增改删（诊断文件和任何JAR变化逐项归因）、真实全context patch与git apply --check/apply/逐字replay、一次保护after、原报告/审查不变、必要上游实际阅读记录、有限DB/history与own进程端点清理。保留所有根源和工具噪声，不重新抓取/复跑同码绿套件。禁止commit/package/deploy/push或改共享索引。完成后给机器handoff、报告bytes/SHA、source/target所有权明确归还root。原reviewer仅在拿到实际覆盖测试和完整修复diff后复审。

本轮不签收N3/全Task5/真实gateway或正式IAM，global IAM_TRUST_CHAIN_UNAVAILABLE始终保持。N4a及其模式草稿等待本轮两项闭合后再推进。
