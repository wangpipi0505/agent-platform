# 单次完整skill诊断结果：状态差异门通过，原SDK失败仍未复现

2026-10-29。本轮按root授权独占窗口，在冻结HEAD `ae2d1860071c9e49d07e80ecace331e41f898c9d`执行一次完整skill70类；Maven与独立PS采集进程结束后已立即明确归还窗口。此后仅文件/XML/Git元数据分析，无追加Java/Maven/PG/HTTP/Node、循环抽样、新模式准备、测试或产品修改，没有操作index或三主入口。未使用superpower。

## 前置与真实运行

先经PS5.1解析并运行只读preflight，实际PID15204、环回25432、data_directory `V:/.local/pgdata`及既有algorithm_test主专库owner/schema/history归属通过；17行history按SCHEMA内部建立行与16条本仓SQL迁移精确分类。新目录保留固定phase/reason枚举及InvocationInfo.ScriptLineNumber，不输出任意异常消息/秘密。

完整70类固有两个重放测试的既有专库`agent_platform_skill_algorithm_migration_test`与`agent_platform_skill_digest_test`，也分别核库存在、owner/schema/history为agent_v12、迁移script逐条在本仓V文件、合法内部SCHEMA行及success。root明确准许两项原测试在这两个已核专库执行原skill schema重放；采集器无DDL，不创建缺库、不重置algorithm_test主库、不接管未知库。ontology_import专库的原owner/EX03标记门同样通过。各前置JSON均在本目录。

preflight SHA `15A5AA6D1B1C079E9D66B989CD116E7EECC44AC34E098CB7C2FF42C894760FC8`；最终Full采集器SHA `63BB75168EB579C10B082658B3883FAB3E40BB172030DFF316965CC5A1CA7F0C`。两者均先经PS5.1.26100.9711 ParseFile，0错误。准备时catch替换误用了.NET regex replacement中具有“全输入”意义的`$_`，PS5.1发现5处静态解析错误；改为字面量catch/finally尾块后才执行，旧解析记录保存在run-parse-preparation-failure.json。没有因此启动Maven或计目标红。

采集器复用EX03 run-verify.ps1的成熟XML Load、完整类/方法覆盖和EX00集合比较，不新增证据框架。command-context.json实际校验：从原Full命令唯一增加argLine中的`-Dex27.algorithmInterruptDiagnostic=true`，默认fork/reuse/顺序未改；同JDK21.0.12.8、本仓m2、Xmx1024m、unix tmpdir、.env及已安装common Jar，不-am、不-Dtest删选类。

实际Maven起止01:11:57.590～01:15:01.466（+08）。native exit=0，沿原Full `maven.test.failure.ignore=true`仅采集；已先持久native-exit.json，再归档所有新鲜XML/UTF-8 XmlDocument.Load。collector exit=0。

## 完整集合、存量原因与真实指纹

| 项 | 本次事实 |
| --- | --- |
| 新鲜XML/源类 | 70/70，无非选择类、XML计数异常或未报告类 |
| 报告记录 | 673=557P/6F/106E/4旧SKIP，allTestsPassed=false |
| EX00原664＋导入新增9 | 全齐；缺原方法0、缺新增方法0 |
| 相对EX00 | 新非通过0、原116非通过状态保留、状态变化0 |
| 相对EX03原Full | 唯一状态变化是GET_BODY `[3]` 原FAIL→本次PASS；原Full原件保留 |
| 当前方法状态差异门 | true；不等于全绿或SDK问题根因关闭 |
| 源码 | 149文件指纹齐、实际采集末sourceDrift=0，D64测试与9DEB ArtifactStore未改 |

已在窗口归还后仅用保存的XML完成原116非通过对照，见retained-nonpass-reasons.csv和reason-comparison-summary.json：116项全部存在，状态、异常type、完整Caused-by类链及首个项目栈方法变化均0；115项原始message摘要完全相同。唯一message/detail摘要变化为AlgorithmMigrationPostgresTest的v310Upgrade…，旧/新均在`:123`的同一个HTTP前后canonical JSON相等断言失败，消息长度均1371；各有2个随机UUID与2个timestamp，仅替换这两类已定位的易变值后全文精确相同。见one-message-delta-check.json；保留原始hash差异，不对全部异常做泛化归一化，不输出消息本身。4个旧SKIP准确身份和原因均保留。

实际前指纹见environment.json及source/module-source-sha256.json；采集结束149源零漂移。归还后的真实文件/Git复核见post-window-fingerprints.json（01:20:47，明确不是补造命令结束时间）：HEAD仍AE2D、测试仍`D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A`、ArtifactStore仍`9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D`、.env仍`AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A`、common Jar仍`1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87`。DEV_AUTH/ENGINE_PG_TEST true，security mode未显式设置，与原Full一致。

## 三份诊断JSON

diagnostic-facts.json为XML内的原安全JSON，facts-summary.json仅作字段选择；无观察错误。原类四项均PASS，三个参数各四phase，独立未确认向量没有混算为这三次正常中断。

| 参数 | joinReturnMs | 首次quiescent相对interrupt纳秒 | 首次值 | entered取栈 |
| --- | ---: | ---: | --- | --- |
| PUT | 24 | 24,564,500 | true | await=true/readJson=false |
| GET | 24 | 24,245,900 | true | await=true/readJson=false |
| GET_BODY | 22 | 22,829,600 | true | await=false/readJson=true，未截断 |

GET_BODY本次栈实际为ArtifactStore.readJson:42→ObjectMapper.readTree→JsonFactory.createParser→BufferRecyclers.getBufferRecycler；与上次单类只看到SDK await不同，确已进入**JSON解析入口**。这不是已经消费前16字节或在socket正文读阻塞的直接见证，也不是interrupt精确瞬间的栈。随后另取IO字段stage0/Call1、executor active0且未shutdown/terminate，不能与更早取栈拼成原子快照。

三个原首次检查均true，故本次仍未捕获原Full的首次false。before-peer-release均worker已退出/中断flag true、stage0/Call0、executor shutdown/terminated、active/queue0，stopNotConfirmed=false；错误根码均OUTPUT_ARTIFACT_UNAVAILABLE，PUT/GET cause InterruptedException，GET_BODY cause SocketException，无未确认固定码。own随机tenant/call PG均rows1/CLEANUP_PENDING/OFFLOAD_FAILED/READY0，支持本次正常确认退出分支，不证明原失败轮走了哪一分支。

释放前routes分别5/6/6、DELETE0；原peer释放/finished/40ms及原无迟到/无READY断言之后仍5/6/6、DELETE0，PG与静止分项相同。此后原测试真实共享复用与cleanTenant，originalBodyCompleted=true；最终routes14/15/15、DELETE3、PG state OTHER包含主动复用/清理动作，不能与释放前计数直接作差认定迟到。entered输入close已1，也不能把该close单独归因为后来的interrupt。

## 结论与停止点

本次完整模块的准确方法状态差异门通过，存量116类型/原因已复核并保留；这是一次新的独立事实，不能删除或覆盖原EX03 Full新增GET_BODY FAIL，也不能用native0/两次P关闭SDK问题、EX27子步、EX03或整卡。原失败轮异常/PG/分项仍缺失，本次诊断在interrupt前取栈/反射可能扰动交接，产品或夹具根因均未确证。

按root最新指令，本代理到此停止：没有修改Test/产品，没有准备新诊断模式或实际运行，没有重复抽样。独立审阅者对“首次原判断后才观察”的最小模式另行技术评估，本报告不预先批准或执行该变更。旧004058准入失败、原Full新F、单类4P与本次Full分别保留。
