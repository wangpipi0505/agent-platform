# 独立工程 ServiceAuthorizationTest 单文件迁移任务

root已批准执行，承接已Accepted的O2a A；用户授权按计划持续开发、无外部环境时用隔离端点验证。唯一工程/每条shell workdir：G:/207/项目/算法重组工具/代码/agent-platform。不得读取、复用或影响相邻已有实现；原v1.2/四契约/时间安排不动，无commit、deploy、自动化、新chat。此处旧测试指新独立工程前期测试，不是相邻原实现。

## 精确前置和范围

O2a A的三生命周期问题已Approved C0/I0/M0，root原审计2464及窄修2153分别PASS；Accepted source589=397backend192front，docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/source-after.json111999B/SHA3ad9c38b06e8ddc637f6ef4a36a5c2e474d15cf306ba1af341a3d6fad01ae7e4。正式target=同目录final/target-after.json419611B/SHAb5bedc4fe8a66f1e4ee171adafd823d72cae3fb576e2e8e3358b82a827e2a335，logical2023包含历史diagnostic而非纯classpath。8索引已闭环，checkpoint .local/checkpoint-iam-o2a-a-docs-before/manifest.json4381B/SHA c0278d57b1da6edcebc3ac484eb9560a149b345a3a5ff382b85f2d6d6401a5f1；T01仍唯一完成。

最终API准备检查 .local/iam-legacy-service-tests-baseline-check.json3099B/SHA6e5d2d4208dc1e26468baf203578b3692bc77e1dba009ad42cd2e0910b53634c，7个具名current API/fixture/test身份与Accepted源清单相同。初次helper只因猜错参数名失败，已经按真实类型签名恢复，不是代码/生产故障。迁移notes .local/iam-legacy-service-tests-migration-notes.md14357B/SHA13b4edc3911330a218219b9a269fa7c3e473e21cc750fc6653aacb35c89e364a，93行EOF，root完整读完；本任务应读其13方法矩阵与精确用途，不另找设计。notes引用旧frozen源仅用于准备，实施以Accepted当前源为准。

你是唯一source/target writer，只允许修改 admin-common/src/test/java/cn/agentplatform/common/authority/ServiceAuthorizationTest.java 一个既有文件；旧字节33932B/SHA7ddcda5d63b6ecb8d2aa4731865d93e499c45de47065aa3ed4e4ce89e04c4c6d，14个@Test。0新增0删除，production/catalog/fixture/其他测试/POM/migration/frontend/engine/gateway均不改。可以在同一文件内调整常量、helper、嵌套controller、统计链构造。真实scope阻碍先向root具体说明，不问用户、不擅改生产。

## 完整任务

1. Keep all14 methods and substantive projection/owner/resource refresh/revocation/cleanup/async/concurrency/current binding/workload/secondary/retained count assertions. Do not disable/remove/rename to avoid original purpose.
2. Use2legal r_<32hex> IDs + RunObservationSelectors.status acceptedSHA; update URLs/domainRuns/mockURI/byResource/negative resources consistently. Trusted limited registerObservation(owner,run,sha) fixture facts before first exchange; no PG/admission claim.
3. First widerADMIN projection positive uses actual wider portalActor exchange then online current narrows; delete redundant success resigning. All positive tickets actual signed exchange/authorize/forward.
4. Integration STATUS exchange denied. Preserve delegated origin anti-laundering via actual registered cancel action, ordinary run resource, real exchange integration->engine.authorize->gateway.resume, POST v1 allowed/admin and debug denied; negative forged tickets only attacks.
5. Secondary statistics positive actual gateway->monitor drilldown authorizeRetained -> same trusted frame requireBound/forward(engine statistics.read)/requireBound -> frame ends, only actual returned ticket reaches real engine secondary HTTP mapping. Separate non-snapshot stats resource, wrong-route actual engine STATUS ticket403 (not monitoraudience401).
6. Health14th method unchanged. Full original13ERROR remain historical; root correction actual actorProjection error and healthpass used as truth.
7. Only this test file changes; production/catalog/fixture/AuthorityContext/POM/other tests do not change. Any real blocker tell root before expansion.

具体使用两个合法r_<32hex> id，RunObservationSelectors.status固定acceptedSha，domainRuns/URL/mock URI/计数key/负例资源一起调整。每个STATUS fixture在首次exchange前以已知actor/资源常量调用registerObservation(SubjectKey,String,String)，不是从未验JWT或HTTP参数注册；这是common可信有限fixture事实，不是PG/admission/engine业务证明。

integration方法保持原名与反洗白目的：明确STATUS exchange403；用已登记proxy.engine.post.RunController.cancel和普通run资源，真实integration exchange→engine.authorize→gateway.resume；source从INTEGRATION到DELEGATED但origin仍INTEGRATION。first/resume在POST /v1/runs/{id}/cancel都200/业务1，POST admin/debug同action都403/业务0。可以改签做STATUS攻击负例，禁止改签生成positive。不能用GET expect误测POST。

secondary统计positive使用独立非run-snapshot STATS_RESOURCE：gateway exchange到monitor/proxy.monitor.get.StatisticsController.drilldown；monitor实际authorizeRetained；同一可信bindRetained frame内requireBound→monitor.forward(retained.current(),engine/statistics.run.read/同资源)→requireBound；关闭frame后只把返回真实ServiceTicket交给真实engine secondary HTTP。无跨frame缓存VA/Retained，无假成功重签。wrong-route403使用另一张真实gateway→engine STATUS票，不能用monitor-audience票退化成decoder401。默认fixture最多2grant，forward不另开grant，client都关闭。

逐13原方法的投影、当前role/attr缩窄、首次成功后的resource refresh撤权/过期/503、吞掉失败后Actor失效、身份绑定、nested dispatch原frame不覆盖、Callable无ambient、两run真实并发/latch/各业务1、workload两行和后半攻击、secondary全局guard、retained同步精确2/async精确3authorityCalls全部保留。health第14方法保持不变。新的支持输入若暴露不同精确错误分类，先按实际设计/代码归因，不能将断言放宽到任意4xx或删除目的来绿。

## 验证与交还

这是测试输入迁移，不是production功能变更。原final01真实85=72PASS13ERROR、13方法错误根因和旧测试原字节已封存，不重复旧baseline来制造相同失败，也不将其冒称新production行为RED。root原服务错误方法correction docs/evidence/iam-stage-4-o2a-a-service-error-method-correction.json2176B/SHA9dd13931a47abe2441920e58e10256f0caf8fec17c58c3f0c13a17fbe4b43b15为实际名单：actorProjection错误，health通过；原作者名单错误不能照抄。

使用适用skills及既有Java21/offline .tools/m2/unchanged .tools/run-iam-module-tests.mjs，独立iam-stage-service-migration-* stage。迁移后只一次完整ServiceAuthorizationTest整类最终命令，实际Running/XML14/0/0/0；不能只选health或exclude/disable/rename原方法。新的失败/必要定向修复原样保留后继续，仅有新变化/故障才复跑，不能承诺第一次必绿。不要重跑O2a新Profile/Receiver或其他旧类/PG/engine/gateway/POST/SESSION。

每命令保留source pre/post完整清单及该任务文件实际文字、necessary common compiler/resource/actualRunning候选、raw/XML/真实exit；executedSource与finalSource独立。最后589源项不变、仅1修改，使用实际createdFiles/inputFiles及Running构造target overlay，保持旧历史logical诊断但本次新dump独立归档不加入compiler/正式logical；不扫旧target，不编/恢复app。资源实际退出，不把Future cancel/完成当物理退出。本次不新增第三方库、不复制外部代码，注释说明可信fixture事实和真实forward边界即可。

自己的 .local/iam-service-tests-migration/、docs/evidence/iam-stage-4-service-tests-migration/，报告docs/evidence/iam-stage-4-service-tests-migration-report.md。source before/after及private no-commit补丁重放可沿已验工具保存；如使用全589 replay须以Accepted589原字节建立、真实firstcheck/apply0/0、589names/bytes/SHA相等，apply后不copy final。已成功物理阶段缓存，metadata/helper失败仅恢复未成功阶段，不为封存重测/重放。保护原作者/根/reviewer证据及8索引/README/原设计，作者不更新台账Accepted。

结束交还一个源码、target/backendclass、自己证据/报告全部写权，返回report/handoff/source/target/inventory/rawXML/replay的精确EOF/bytes/SHA及实际执行次数，停止写。新fresh reviewer和root有限审计后决定Accepted。本任务只恢复14方法覆盖，17需求/34任务/30验收不全闭环；之后才正式冻结B材料reader接口/SQL/预算与真实PG验收。

EOF
