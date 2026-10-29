# EX-03 三类30P；完整Skill新增1F，不放行

本阶段经主代理明确授予唯一 Java/Maven/PG/HTTP 窗口。先执行submitted替身修正后的Target，30项全部通过后才继续Full。Full结束即交还窗口；没有追加重跑、其它模块、源码或规则改动，未add/commit。

## 实际命令与结果

| 本轮 | 时间（北京时间） | 新鲜XML/源类 | 方法记录 | 原生命令/采集器退出 | 判定 |
| --- | --- | --- | --- | --- | --- |
| [Target](after/target-20261028-234843-846/command.json) | 23:48:45.6082849–23:48:55.5156722 | 3/3 | 30P / 0F / 0E / 0S | Maven0 / 采集器0，未ignore | 三类通过 |
| [Full](after/full-20261028-234917-584/command.json) | 23:49:19.2381126–23:52:48.9248179 | 70/70 | 556P / 7F / 106E / 4S，共673 | Maven0（failure.ignore）/采集器1 | 采集完整；新增1F，回归门失败 |

入口分别为 `run-verify.ps1 -Mode Target` 与 `-Mode Full`。精确Maven参数、起止与真实native exit先落盘，然后立即归档新鲜XML并以XmlDocument.Load读取UTF-8；原始raw/XML本地保留且默认忽略。不能把Full的Maven0或报告齐全写成全绿。

两轮三Java源码SHA相同：Importer `41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5`；Catalog `7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03`；Test `97D0A6B1B48ECD833568C9E3F584303FEEF6D2A094BDAE6979AEC36353432051`。每轮149个模块源码/资源文件前后摘要一致；collector在命令结束时已核sourceDrift=0，23:55另只读保存明确after清单与捕获时间，不冒充更早捕获。

## PG与真实header关闭门

Target的 `postgresImportReadPublishAndExecuteKeepsOnlyApiKeyWithLegacyDisabled` 实际passed，耗时1.362秒，见 [方法记录](after/target-20261028-234843-846/methods.csv)。固定Test SHA的断言已全部执行：

1. 专库准确owner/comment准入、Flyway、真实Catalog导入/PG读回，DRAFT契约明确API_KEY、同opaque引用及ONTOLOGY来源；legacy-bearer=false仍可导入。
2. 缺连接报告时发布拒绝；真实SkillExecution的受控DEBUG调用经真实HTTP适配器成功并落非mock PG事实，治理connection读取同摘要事实通过；缺选型报告时发布仍拒绝。
3. 选型使用明确的确定性模型协议替身，由生产SkillGovernance计算并写入报告，而非手写PASS行；生产Catalog.publish成功，PG再读definition/sha未变。
4. 已发布版本第二次真实执行成功，两次本机HTTP请求均收到精确单值X-API-Key，Authorization列表为空。没有在出站前补scheme/ref或直接改发布状态。

Full内该方法及本卡全部9新增方法也全部passed。此处证明平台存储、治理门、发布保持与适配器路径；引擎权威接口、模型选型响应和Actor是明确测试替身，不称真实OR J4/JWT入口/真实外部模型或EX-07验收。旧Target的29P/1E遗漏与v1/v2红证据保持独立，不覆盖。

## Full逐方法比较

[summary.json](after/full-20261028-234917-584/summary.json) 显示：原664方法身份全部保留，新9方法全部出现且通过，missingBaselineMethods=0、missingRequiredNewMethods=0、xmlIssues=0、sourceDrift=0。原116非通过（6F/106E/4S）精确保留在 [retained-baseline-nonpass.csv](after/full-20261028-234917-584/retained-baseline-nonpass.csv)。

唯一变化见 [status-changes.csv](after/full-20261028-234917-584/status-changes.csv)：

`cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest#interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]` 从EX-00 passed变为failure。故本轮556P/7F，而不是预计的557P/6F；newNonpass=1，regressionGatePassed=false、allTestsPassed=false。不能把它因不在本卡直接调用链内就自动豁免。

4个旧JDK8条件跳过身份保持为 `LocalSpikeLifecycleLiveTest` 的 coldStartAndReadyHealth、concurrentCallsExecuteSeriallyWithCorrectResults、externalKillIsDetectedAndAutoRestarted、shutdownKillsChildrenAndReleasesPort；没有新增skip。基线与本轮空method事件各0，见 [execution-disclosure.json](after/full-20261028-234917-584/execution-disclosure.json)。仍有106个错误和4个跳过，**不声称所有方法体都执行**；collectionComplete仅描述本轮命令/XML/选择范围可核对。

两轮保存当前HEAD、相同.env摘要、DEV_AUTH=true、ENGINE_PG_TEST=true、security mode未显式设置、同JDK/argLine及依赖jar摘要。专库owner/comment再次核准，本阶段不新建或清理库，进程环境及PGPASSWORD在finally恢复。

## 新观察的准确停点与待核实

失败参数为ValueSource第三项 `GET_BODY`；源码 `AlgorithmOffloadInterruptionTest.java:94` 断言 `scoped.get().algorithmIoQuiescent()` 为true，实际false。前面的实际SDK阶段到达、worker在有界join后退出、已捕获异常断言通过；后续interrupt flag、输入关闭、CLEANUP_PENDING及无迟到请求等断言未执行，不能据方法名补填。

`ArtifactStore.algorithmIoQuiescent()` 是 stages/calls已settled且owned executor已terminated的组合检查，当前失败没有记录哪一部分未满足，不能擅自归因为某一线程/库或“偶发”。该测试直接构造ArtifactStore并测SDK中断回收，不经Importer/Catalog；ArtifactStore与该测试相对EX-00 `ebbfd62` 无源码差异。但这些静态事实不能证明因果无关或满足模块门，新增失败保留为待主代理分配精确诊断范围。

当前不修改ArtifactStore、该原测试、时限或授权规则，也不追加单类重跑。EX-03定向与PG/header目标已达到，完整模块新增失败尚未收口，独立终审/提交/DoD关闭仍待主代理安排。
