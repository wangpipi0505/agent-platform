# EX-15b PROCESS 相邻 83 例静态采集候选

本目录仅三个候选文件：`run-selected.ps1`、`manifest.json`、本说明。未执行 Maven/Java/PG/HTTP，不能凭此候选认领83绿。主线程已报告父目录段两例绿，现可安排这轮原断言相邻回归；完整文件IO仍按 `../io-implementation-candidate.md` 分阶段推进。

## 精确选择与资源核对

原身份来自 EX-15a `collector/runs/full-skill-20261029-ex15a-01/methods.csv`（SHA `998D47C93AB42937C403568AA61CC9673EB238AB42158BF7AB3F2C7672D6A08F`），以下原81条均 passed、具名普通 @Test，没有参数化显示名/条件跳过。再加入 ProcessFilesTest 原两方法，合计 **83 / 10 XML**。manifest逐条冻结 classname/method，运行时重读原CSV再次交叉核对。

| 类（`cn.agentplatform.skill.` 下） | 例数 | 源码核到的真实资源 |
|---|---:|---|
| domain.ProcessContractTest | 17 | 纯解析；JUnit自有临时根、当前JDK绝对路径字符串，不启动进程。 |
| infra.ProcessAdapterTest | 11 | FakeProcessWorker纯JDK子进程、当前测试临时allowed/outside根、超时PID文件、stdio；env模式仅允许环境名单及测试显式变量。 |
| infra.ProcessProtocolTest | 11 | 同纯JDK worker和本方法独占allowed根；stdin/stdout/schema/退出码，失败转储只在本根。 |
| infra.ProcessAdapterHardeningTest | 15 | 同worker、原并发线程/许可、取消/超时、中文路径、自己 seed 的failed目录；newRoot用Files.createTempDirectory，清失败转储但不承诺删除每个空根。 |
| infra.ProcessTreeTest | 3 | FakeProcessWorker plain/tree及其JDK后代、JUnit临时目录、stdio读PID；原精确 Process/ProcessHandle 杀自有树，未新增系统进程扫描或广域清理。 |
| infra.SkillProcessRoutingTest | 5 | 注入本地ProcessAdapter，SkillAdapters其他依赖null，仅PROCESS invoke/queryOnly；自身临时allowed根和worker，不调用HTTP适配器。 |
| application.SkillProcessRegistrationTest | 6 | SkillCatalog(null,null,null,...)的normalize，测试SecurityContext及JDK路径；http://unused和https://algo.example.test只是初始化/拒绝字段文本，不发请求。 |
| application.SkillProcessWhitelistRegistrationTest | 3 | 纯normalize+白名单，当前JDK路径及不存在路径字符串；无运行/PG/HTTP。 |
| application.ProcessPublishPrecheckTest | 10 | 包内processPrecheck直接调用，只探测当前JDK/自己临时文件路径和schema；不会调用publish事务或PG，不实际启动命令。 |
| infra.ProcessFilesTest | 2 | 既有冻结SHA `7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7`；纯JDK嵌套worker、中文空格唯一根、PID/creation/父子/可执行核对、bounded finally；../escape.bin仅响应JSON，未写外部路径。 |

逐源读取还追到 FakeProcessWorker：java.net.URL仅取本地CodeSource定位test-classes；没有socket/HTTP/数据库请求。上述83例不需要loopback监听。SkillCatalog只是构造HTTP客户端的对象也不等于已发请求；本轮入口只执行normalize/processPrecheck。原测试中的清理仅自有临时根，不允许采集器借此遍历清理其他根/进程。

**明确另跑3例，绝不算跳过通过：** SkillProcessExecutionTest 1、SkillProcessAuditTest 2有 `@EnabledIfEnvironmentVariable(AGENT_DB_PASSWORD)`，实际连本AP `127.0.0.1:25432/agent_platform_v12`，skill Flyway migrate（两类BeforeAll约49–64行）。它们从本轮精准选择中分离，必须在后续既有独占PG准入与完整skill回归里执行。当前完整skill起点是698+本卡原2=700，新增IO测试后再增量，不以本轮83取代完整回归。

## 冻结与执行

manifest固定当前151个skill源、100个admin-common源及3个POM，共254个raw SHA；固定`.env`文件SHA但**从不加载其值**，固定本仓installed admin-common JAR/POM、JDK21 java/javac/modules/release，以及Maven启动器。准备时HEAD仅为上下文；实际执行取当时HEAD并要求前后一致，不要求回到旧提交。不得一边跑一边改这254源。

脚本UTF-8 BOM，适用Windows PowerShell 5.1。执行前必须由主线程独审候选、持有唯一实际窗口并显式传当前批准manifest SHA：

```powershell
# 在AP仓执行；runId必须新建，不能覆盖已有run。
$manifestSha=(Get-FileHash -LiteralPath 'docs/evidence/2026-10-29-EX-15b/adjacent/manifest.json').Hash
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File 'docs/evidence/2026-10-29-EX-15b/adjacent/run-selected.ps1' `
  -RunId 'process-adjacent-20261029-01' -ApprovedManifestSha256 $manifestSha -ExecuteApproved
```

上例读取SHA不能代替事前审阅；批准后任何源/manifest/runner漂移会在启动Maven前拒绝。实际命令保留本仓m2、JDK21、`-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`、`-Dsurefire.timeout=900`、默认fork/order，显式 `-Dmaven.test.failure.ignore=false`，精准10个全限定类名。只 `-pl skill-registry test`，无clean/install/-am，不启动正式服务、不加载.env、不碰PG/MinIO。Maven依赖处理仍遵循原本本地m2配置，本文“无HTTP”是测试资源结论，不冒称对Maven进程施加网络沙箱。

## 结果门与原件

- 新run先使用新DirectorySecurity建立当前SID/SYSTEM专用ACL；不改既有目录ACL，不接管旧run。原始Maven日志和10个新鲜XML完整落盘，不重写原因/栈、不新增.gitignore、不自动stage或删原报告。
- 记录manifest/collector副本、source/context前后、精确命令/native exit、methods、XML尺寸/SHA、summary及collector exit。XML只接受本次启动时间后的精确10类；方法83身份无缺失/额外/重复，suite计数一致，83P/0F0E0S、native0、源码/上下文稳定，才regressionGate=true。
- 任一收集失败或编译/前置异常不得算产品实红，原异常仅私有raw；安全receipt保留phase/native事实。失败也尽力保存after指纹，原件不删。脚本不杀未知进程；实际挂起仍由主线程按本次PID身份处理，不广扫Java。
- 旧测试失败时其真实错误仍为失败，不通过更换断言、放宽时间窗或跳过来做绿；任何必要采集器纠正须保留旧run/旧脚本再新run。
- 即使83全绿，summary仍wholeCardComplete=false；PG3、完整skill、新IO链/正式JVM重启、非作者复核与主入口更新尚须按卡执行。Git入库前由主线程逐文件扫描真实凭据，不能因为此目录有原始日志就直接git add目录。
