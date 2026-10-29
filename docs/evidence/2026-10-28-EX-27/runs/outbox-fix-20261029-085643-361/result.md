# Outbox逐例隔离：28定向与共享Full674实际结果

RunId：`outbox-fix-20261029-085643-361`。2026-10-29，Asia/Shanghai。ROOT授予唯一实际窗口后，已先核正式独立静审准入，再严格依次执行一次只读PG前置、一次默认28项定向、一次默认完整skill。Full结束立即明确交还窗口；之后只有归档文件/XML比较，没有Java/Maven/PG/HTTP命令、重复抽样或源码修改。

**本轮28定向全绿，共享Full的精确方法/原因回归门通过；Full保留116项原非通过，并非全绿。运行后独立复核、精确提交及主入口关闭回填仍待ROOT办理，本报告不关EX-27或EX-03。**

## 准入与冻结栈

正式准入：[outbox独立静审](../../outbox-actor-static-review.md)，SHA256 `9EC2AC8FB1F5ADBAE7C0FF2006C14E86CAA2665842A0C488B35BE437AC232A8E`。本轮复制成熟准备包的原字节后运行，清单及授权见`run-intent.json`。脚本均保留UTF-8 BOM；运行前已在PS5.1解析0错误、19项静态检查全通过。

| 对象 | 实际冻结身份 |
|---|---|
| HEAD | `83726d50a6cce116251c03c5c193080d8ed5aad2` |
| SkillExecutionTest | `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8` |
| AlgorithmOffloadInterruptionTest | `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D` |
| ArtifactStore | `9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D` |
| SkillTaskResultTest | `600F1A9DF2D5F0199A52F7F2152D339F2E6C6D78839382C30C5BA9C5E7FA164F` |
| SkillOutbox | `30BAE61B51DB4DF75462C5D92BADC5455FBEC1FC7437856CD1D9A9DA2DA32DB7` |
| SkillExecution | `8CD3D8EDAE7C0DEE54DD562C6F2F3F3664759DE85D122F70C1E00B9BC6D25218` |

两次实际运行前后env均为既有AF038摘要、已安装admin-common JAR均为既有1B0D摘要；完整值见各阶段`command-end-fingerprints.json`。原JDK21、Xmx1024m、unix tmpdir、DEV_AUTH/ENGINE_PG_TEST、默认fork/reuse/order及原等待预算不变。未传两个诊断flag，未加skip、顺序或fork覆盖，不使用`-am`。

149份源码集合与上轮SDK43EC Full一致：只允许本Test由14AE→607B，SDK43EC及其余147份保持。Target与Full之间149份逐项一致，实际结束时漂移0。当前源码未在此实际窗口改写。

## 只读PG前置实际通过

`preflight.ps1`在独立PS5.1进程加载AP环境并finally恢复；没有输出密码、完整连接串、请求内容或OR凭据。实际核环回25432/PID15204、精确本仓PG可执行文件、V:/.local/pgdata及角色agent_v12。

新增的主库准入已实际核实：`agent_platform_v12`既有，库owner、skill schema owner及skill.flyway_schema_history owner均为agent_v12。历史为1条合法SCHEMA内部记录与16条当前本仓V300–315 SQL；当前V脚本恰各一次，未知/坏行/缺失重复均0。记录见`main-precondition.json`。未靠测试的migrate去补缺失主库/schema。

原algorithm_test、两个既有命名迁移专库及ontology导入库亦按原归属/合法历史/marker门通过。collector始终只有catalog/Flyway历史SELECT，不读、认领或清理旧outbox队列，不发DDL、不创建/重置库；Full仅保留已获准原迁移测试在两个既有专库中的原skill schema重放。新Test的业务写入/清理限定其本例UUID；未修改另一类原固定租户测试、产品主体恢复规则或未知旧队列。

## 实际命令结果及分别判门

| 阶段 | 原生起止（+08:00） | 原生/collector | 完整身份与结果 |
|---|---|---|---|
| 默认Target | 08:57:18.1091782–08:57:39.1473225 | native0 / collector0，无failure.ignore | SkillExecution25＋SkillTaskResult3＝28P，0F/0E/0SKIP；与EX-00原28身份完全相等，无新增/缺失；0算法outcome/0诊断 |
| 默认Full | 08:58:18.0024223–09:01:22.7141050 | native0 / collector0，原failure.ignore仅用于采集 | 70类674＝558P/6F/106E/4旧SKIP；原664＋导入9＋handoff1齐全，新非通过0、状态变化0、XML问题0、源漂移0 |

完整命令参数与原生exit分别保存在阶段`command.json`/`native-exit.json`。原生返回后先持久exit，再记录结束指纹，再归档本轮新鲜XML。Full在开跑前重新核本轮Target28P/native0/noignore/无skip及同149源/HEAD/env/JAR，见`full/target-dependency.json`。

Full的`allTestsPassed=false`准确保留。命令结束时`full/summary.json`的`reasonComparisonPending=true`是当时事实，未倒写；交窗后的`execution-gates.json`另记方法、原因、上下文合并门，不用ignore的native0声称全绿。

## Outbox后置与原失败边界

两阶段均完整运行原25报告和未改的另3报告。607B保留原97条JUnit断言，新增两条严格断言，未放宽原principal、SERVICE、业务执行一次、自身事实DELIVERED义务。相关方法PASS表示其原主体/角色和自身持久事实断言以及新“该UUID租户仅当前call到期可认领”“回调精确为该call的/fact”均到达并满足；不以仅总数或原生exit替代这些义务。

相对上轮`algorithm-fix-20261029-032753-387/full`，唯一状态变化为`SkillExecutionTest#automaticOutboxRecoversTransientCallbackWithoutRepeatingBusiness` **FAIL→PASS**，见`full/previous-sdk43-full-status-delta.csv`。原失败XML与其expected skill-tester / actual task-result-tester现场保留，没有清旧队列后重测或把产品恢复主体改成当前JWT。

本轮隔离结果支持单例测试命名空间修正及共享模块回归，不能追认旧失败所选具体callId、创建批次或当时due/lease。旧XML未记录这些数据，本轮也未查历史业务队列。已有机制分析与当前新UUID结果不能替代缺失的历史审计链。

## 共享Full中的SDK五报告

本Full仅计一次，同时覆盖SDK43EC与outbox607B两个独立候选的模块影响；两者源码/独审/提交范围仍分列。原PUT、GET、正常GET_BODY三参数身份、原确定性Source及新增raw GET_BODY handoff **五身份全部PASS**，0诊断JSON，四条安全outcome齐全。

| Full网络向量 | join返时ms | 中断标志/输入关闭 | 首次quiescent | 显式未确认 |
|---|---:|---|---|---|
| raw GET_BODY handoff | 23 | true / 1 | true | false |
| 正常PUT | 31 | true / 1 | true | false |
| 正常GET | 21 | true / 1 | true | false |
| 正常GET_BODY | 22 | true / 1 | true | false |

本轮raw handoff未命中显式未确认分支，不能把后续真值反推旧现场。正常GET_BODY的真实readJson＋正文/网络读链、交接分项与原1秒总准备预算，由43EC的实际PASS断言保证；默认运行没有诊断栈样本，不伪称保存了额外实时取栈。原确定性有限Source PASS覆盖明确未确认码/PG码、pending无READY、延后且无早删、输入关闭、原资源实际退出、固定零网络前提及退出后同shared对象真实PUT/GET的完整后置。

五报告在43EC下还执行原严格正常quiescent断言、双向码一致分类、peer固定停点路由、释放前后无迟到请求、共用收尾预算与原40ms、共享对象身份finally清理及本轮新UUID资源的有意清理。精确义务以43EC静审/覆盖矩阵为准；没有普遍断言executor尾段也必为retained。确定性Source的共享对象此前无HTTP暖场，其后续使用不冒称“暖场后复用”。

原EX-03 GET_BODY F、历史PG ALGORITHM_IO_STOP_NOT_CONFIRMED分支恢复、三轮取证及身份/实时内存分项边界全部保留。本次PASS不恢复旧stage/Call/executor分项，也不改判原现场。

## 原116原因及四旧SKIP逐项比较

`full/retained-nonpass-reasons.csv`保留116原非通过身份、状态、异常类型、Caused-by类链、首项目帧及原始message/detail摘要：缺失0，状态/type/原因链/项目帧变化0。四旧SKIP的精确身份及message/detail摘要均相同，另存`old-skips.json`。

115项message/detail摘要完全相同。唯一旧`AlgorithmMigrationPostgresTest#v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity`的原始摘要有差异：两个message均1371字符，恰各含2UUID和2时间戳；只替换这四个动态片段后，message及完整detail均逐字符相等。此有界解释见`message-delta-checks.json`，原XML/hash差异保留；没有对全模块做泛化归一化或隐去新失败。

相对原EX-03 Full673，唯一旧身份状态变化为GET_BODY[3] F→P；另新增handoff1P。相对上轮SDK43EC Full674，唯一变化为上述outbox F→P。不能以两次总数差计算替代精确身份对照，也不把共享Full算成两份独立运行。

## 当前可签收范围

本轮运行/文件比较证据满足既定Target28及Full674无新增差异门；未宣称存量红清零、历史现场全部安全后置已回溯或所有产品恢复场景均已验收。产品ArtifactStore/SkillOutbox/SkillExecution均未改。

原始raw.log/XML按本Run窄.gitignore保留本地，安全元数据可精确入审。不提交raw、环境、凭据、JAR。尚未stage/commit；ROOT仍需独立运行终审、精确文件/字节与索引检查，再按真实状态回填主入口。窗口已归还，无任何追加实际运行。
