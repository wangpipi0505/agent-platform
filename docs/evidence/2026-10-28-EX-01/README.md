# EX-01 服务级配置与有效开关

状态：进行中，待最终候选冻结与独立终评。PowerShell 隔离、配置占位、定向、真实配置与 engine-only 补验已记录；EX-27 最终共享 gateway 轮的回归门通过且源指纹匹配，原生退出码1及15项原非通过保留，不称全绿。各轮历史红测、诊断及采集限制保留，不据作者文档收口关闭整卡。

终评入口：[候选清单/五产品SHA](candidate-manifest.json)、[完整显式待暂存清单](stage-files.txt)、[命令记录](command-index.json)、[共享gateway指纹与门](gateway-shared-regression.json)、[engine报告口径](engine-report-scope-correction.json)、[敏感扫描范围与结果](sensitive-scan.json)、[独立前置评审](review.md)。评审文件由独立审阅者维护，本轮不替其作终评或改结论。

## 版本、范围与前提

- 执行日：2026-10-28；作者：独立实现代理 `/root/ex01_service_environment`。
- 起始 AP HEAD：`20333f35bc9f97dc11df296c04c8028e27fd42c8`（EX-00 已独立验收提交）。未提交本卡，未暂存文件。
- 已读：本仓 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`、统一台账/EX-01 卡片/联合手册 §3 与本次派发简报。按用户要求不使用 superpower；采用 ponytail 的最小实现原则，无新增依赖或测试框架。
- 权威设计实际路径：`文档/设计/详细设计/智能体构建/智能体构建平台详细设计-整合版.md`；实际 SHA256 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`，与 `contracts/freeze.json` 相同。
- 白名单：`scripts/start-backend.ps1`、`.env.example`、app/engine/skill/gateway 的 `src/main/resources/application.yml`、新 `scripts/Test-ServiceEnvironment.ps1` 及本目录。首阶段仅改前两个与新脚本/本目录；获得后续窗口后，仅改 gateway 三项环境占位和 engine 凭据引用注释，app/skill YAML 原有占位沿用。
- 开工时已有未跟踪的 EX-00 数据库名清单、EX-35 证据、历史验收证据、`output/`。执行中 EX-35 改 `PublishedRunProxy`/对应测试，EX-02 改 `ReleasePins` 对应文件与其证据；均不属本卡，未暂存、修改或恢复。
- 首阶段 Maven/PG/前端/HTTP 窗口由 EX-35 独占，当时只做 PowerShell 微测。EX-35 冻结并交还后，主执行者将 Maven/PG/HTTP/Java 窗口独占授予本卡，后续实际执行另见下节；不使用该授权改 EX-35 产品/测试或其它卡候选。

## 源码确认与精确变更

| 事实 | 当前依据与处理 |
| --- | --- |
| 旧脚本先启动数据库，随后读取唯一 `.env`，逐服务只改服务身份，无 finally | 新参数 `-ServiceEnvironmentFiles`；三函数分别读环境文件、预检并形成每服务覆盖、在真实 `Start-Process` 边界应用并恢复环境。所有服务文件在数据库/构建/启动前校验 |
| SecretVault 只接受 `env:AGENT_SECRET_*`，且需要 `AGENT_SECRET_BINDINGS` 租户映射 | `admin-common/.../SecretVault.java:78–91`；不放宽 Java 校验。样例与检查均采用实际命名规则，绑定租户以验签身份为准 |
| 父环境已有两把 key 时，单改 secret_ref 会互漏 | 收集全部服务文件定义、env 引用、绑定表指向的私有变量；每个子进程仅保留其显式归属变量，其他私有变量与指向它们的继承引用一并清空。绑定表同步滤掉别服务项 |
| 覆盖不能污染父进程或下一服务 | 子进程创建用 Windows PowerShell 5.1 可用的环境临时覆盖与 finally；额外 outer finally 恢复全局 `.env`、服务身份、PATH/JAVA_HOME/代理等启动器修改，原先不存在的变量会删除 |
| 旧单文件入口需要兼容 | 没有服务文件时，计划只加本模块服务身份；全局 dotenv 保留宽松解析。服务文件使用严格 KEY=VALUE，拒绝非法/重复键。新模式不复用旧运行进程，防止新配置被当成已生效 |
| 路径必须本仓忽略目录、不能越界 | 要求绝对普通文件路径、本仓前缀、父目录通过 `git check-ignore --no-index --quiet`；路径包含文件/目录重解析点时拒绝。日志只枚举覆盖键名，异常不含环境行值或 JSON 值 |
| skill 的 schema、预算、PROCESS、AUTHORITY 占位已存在 | `skill-registry/.../application.yml:34/36/51/57/71`；不重复实现。`ProcessAdapter` 容器构造在 enabled 且白名单空时拒绝，测试便捷构造的兼容路径不代表容器路径；`ServiceTickets` 在 AUTHORITY 缺 provider 时拒绝签发，未回落 LEGACY |
| engine 的配额窗口与三级阈值占位已存在 | `engine-runtime/.../application.yml:74–79`；三阈值 0 为不限，`.env.example` 显式说明 |
| 初读 gateway 限流三项仍硬编码 | EX-35 冻结后已将 enabled/window/max 改为环境占位；默认 false/60/0 不变。engine 仅更正凭据引用注释，与实际 env/vault 能力对齐 |

私有集合边界按主执行者确认：必须把服务专属凭据的定义、env 引用或绑定声明显式放进服务文件；没有出现在任何服务文件中的全局变量继续继承，不擅自清空所有 `AGENT_SECRET_*` 破坏无关既有配置。2026-10-28 后续已获独占 Maven/PG/HTTP/Java 窗口并落 gateway 三处环境占位与 engine 注释；后续实际记录在新 runs 中，前阶段“未执行”事实保留。

## 红到绿与可复跑命令

1. [red-baseline.json](red-baseline.json)：修改前静态确认参数、隔离启动函数和 gateway 环境占位均不存在。该静态采集宿主为 PowerShell 7.6.5，不读取真实 `.env`。
   [口径更正](red-baseline-interpretation-correction.json)保留该原文件事实，并撤回其 scenario 中“gateway 忽略环境覆盖”的未验证推断：当时没有启动旧版验证 Spring 环境属性优先级，不能仅凭 YAML 无显式占位推定运行时覆盖无效。
2. [red-isolation-test.txt](red-isolation-test.txt)：新增测试先在 Windows PowerShell 5.1 运行，因缺生产 `Read-AgentEnvironmentFile` 失败；测试子进程退出码 **1**，未执行任何服务动作。
3. 最小实现后三轮 5.1 检查逐步为 39、44、46 项 PASS；最新可复跑结果为 [powershell-isolation-results.json](powershell-isolation-results.json)，Windows PowerShell **5.1.26100.9711**，**46 项通过，0 失败**，命令退出码 **0**。
4. [powershell-static-results.json](powershell-static-results.json)：Windows PowerShell 5.1 实际解析两脚本，语法错误均为 **0**，均含 UTF-8 BOM；记录候选 SHA256。`git diff --check -- scripts/start-backend.ps1 .env.example` 退出码 **0**。

```powershell
Set-Location -LiteralPath 'G:/207/项目/算法重组工具/代码/agent-platform'
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' `
    -NoProfile -NonInteractive -ExecutionPolicy Bypass `
    -File './scripts/Test-ServiceEnvironment.ps1' `
    -EvidenceDirectory './docs/evidence/2026-10-28-EX-01'
if ($LASTEXITCODE -ne 0) { throw 'EX-01 PowerShell隔离检查失败' }
```

测试只提取生产脚本的三个函数；app/engine/未分配 skill 的子进程均调用生产 `Start-AgentServiceProcess`，子程序为无业务的 `powershell.exe` 环境探针。另检查实际生产调用确实使用此函数与对应服务计划、预检在数据库调用之前，直接执行生产日志与 outer finally 语句验证输出和恢复。未用第二套启动算法替代生产边界。

夹具只在本仓 `.local/ex01-service-environment-<随机ID>/` 创建；两份环境文件与全部 key 值均为无秘密标记，不读取真实 `.env`。测试 finally 先核对绝对路径与固定前缀再递归清理本次目录，父环境也恢复；只将通过项目名称等脱敏事实写入证据。

最新 46 项覆盖：app binder/engine readonly 的引用、值与租户绑定分离；父进程预存两把 key 的隔离；未分配服务无私有 key/继承引用；普通键逐服务覆盖与跨服务不串；服务身份固定；成功/启动异常/全局 finally 的存在与不存在变量恢复；无参数兼容；定义/绑定表单独声明的所有权；未知服务、相对/仓外/非忽略/不存在路径、坏 dotenv/重复键、非法引用、坏 JSON/空租户/未绑定 key、未选服务的坏文件启动前拒绝；日志无值。

## 独占窗口：定向与模块对照（进行中）

使用 [run-tests.ps1](run-tests.ps1) 按手册参数加载本仓 `.env`，`AGENT_DEV_AUTH=true`、`ENGINE_PG_TEST=true`，JDK21；仅 gateway 按已经批准的条件使用 `-DreuseForks=false`。每次新目录记录命令、HEAD、配置/候选摘要与新鲜 XML；raw/XML 忽略入库，方法表与汇总入库。EX-01历史模块采集使用 `-Dmaven.test.failure.ignore=true` 保全报告，不把 Maven 成功当测试通过；EX-27最终共享轮未忽略失败，其native1真实保留。完整Maven参数数组、带引号命令与入口命令见 [command-index.json](command-index.json)，本轮文档整理未执行其中命令。

| 轮次 | 结果与证据 |
| --- | --- |
| skill 定向 | [targeted-20261028-212920-848](runs/skill-registry-targeted-20261028-212920-848/run.json)：SchemaDegradedValidationTest、ServiceTicketsTest、ProcessAdapterTest、ProcessAdapterHardeningTest，4 XML/46 PASS，exit 0 |
| engine 定向 | [targeted-20261028-213039-849](runs/engine-runtime-targeted-20261028-213039-849/run.json)：RunQuotaPostgresTest、RunQuotaAdmissionPostgresTest、OntologyContextPacksTest、OntologyBindingResolutionTest，4 XML/22 PASS，exit 0 |
| gateway 定向 | [targeted-20261028-213346-767](runs/platform-gateway-targeted-20261028-213346-767/run.json)：IntegrationRateLimitTest，1 XML/6 PASS，exit 0；不计 EX-35 其它路径通过 |
| skill 完整模块 | [module-20261028-213410-422](runs/skill-registry-module-20261028-213410-422/run.json)：70源测试类/70 XML，664方法=548 PASS/6 FAIL/106 ERROR/4 SKIP。[逐方法对照](runs/skill-registry-module-20261028-213410-422/comparison.json)与 EX-00 完全一致，缺失、重复、状态变化和新增非通过均为0；既有失败与缺 JDK8 的4跳过原样保留，不称全绿 |
| gateway 历史完整模块 | [module-20261028-214612-758](runs/platform-gateway-module-20261028-214612-758/run.json)：27 XML/287方法=271 PASS/8 FAIL/8 ERROR，采集命令 exit 0。[对 EX-00](runs/platform-gateway-module-20261028-214612-758/comparison.json)的新增 SSE 失败与当时 gate=false 原样保留；[对 EX-35 冻结轮](runs/platform-gateway-module-20261028-214612-758/comparison-ex35-frozen.json)相同仅是历史来源对照，没有promote基线。最终共享轮见下行 |
| gateway 最终共享模块 | [EX-27 safety-module-20261028-233458-012](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/stage-gate.json)：真实native1，27 XML/288方法=273P/7F/8E/0S。原254、恢复的Proxy原30、新Proxy3、新SSE1全部在；新增非通过、坏类、缺失、重复均0，原15项非通过及原因集合不变；Proxy33P、SSE38P、49子进程均0。共享回归门true，allTestsPassed=false；TLS原FAIL→PASS单列观测，不算本卡修复。8项前后指纹与当前一致，含本卡gateway YAML；本卡只引用，不重跑。原EX-35失败、EX-27诊断与早期夹具编译错误均在原卡保留 |
| engine 历史完整模块 | [module-20261028-215906-700](runs/engine-runtime-module-20261028-215906-700/run.json)：93 XML/906报告项=904具名方法+2个原类级事件，状态总数791P/35F/80E，采集命令exit0。[对 EX-00](runs/engine-runtime-module-20261028-215906-700/comparison.json)906报告项身份/状态差异0、缺失/重复/新增非通过0，同时collectionComplete=false原样保留。两个method为空的事件分别为ModelInputMaterialHttpTest的FAIL、RunEventReaderTimeoutBudgetPostgresTest的ERROR；对应源码11+3=14个@Test未执行，不称全方法采集完整 |
| engine EX-36 新模块 | [EX-36派生口径](../2026-10-28-EX-36/runs/after-module-20261028-230322-842/method-summary/summary.json)：907报告项=905具名方法+同2个原类级事件，792P/35F/80E；仅新增Spring装配方法PASS，原115项非通过保留、新增非通过0。14个源码@Test仍未执行、collection_complete=false；只引用EX-36结果，不重跑或改其raw/源码/结果 |
| 当前服务打包 | [package-20261028-215851-805](runs/package-20261028-215851-805/run.json)：仅 app/engine/skill/gateway 与 admin-common 的 `package -am -Dmaven.test.skip=true`，exit 0；记录所有生产源摘要与4个可启动 JAR 摘要。未 install 全 reactor，跳过测试编译/执行，测试数0，不计测试通过 |

skill 模块首次收集器用 5.1 默认 ANSI 读取中文 UTF-8 XML，原生 Maven 已结束后采集器 `InvalidCastToXmlDocument` 失败。修正为 `XmlDocument.Load`，并显式 UTF-8 读取 JSON/CSV；[补采脚本](recover-skill-collection.ps1)从仍保留的同轮原始70份报告归档，没有重跑测试或覆盖原结果。原始 raw 有 `BUILD SUCCESS`，但当时未持久化的原生退出码/精确开始 HEAD 保持 null，不补造 exit 0；起始时刻标明取目录创建时间，实际源摘要由更早同候选定向记录交叉验证（全部一致）。后续采集器先落 `execution.json` 再解析，避免同类证据丢失。

gateway 模块结束后，[源码写窗释放记录](gateway-test-source-release.json)保存旧 SSE 测试 SHA 与时点，之后才允许 EX-27 修改其唯一已登记测试文件；EX-01 不再读 gateway 测试源码。skill 完整模块后也释放 EX-03 的唯一红测文件写窗。后续 package 明确跳过测试编译；两卡产品修改/真实测试仍由主执行者另行协调，不混入上述已归档结果。

## 真实启动与配置验证（进行中）

[run-live.ps1](run-live.ps1)使用本卡新空数据库承接所有真实服务启动；不从旧开发库取业务数据。源码确认 skill 并非“没有 engine 调度器就没有副作用”：`ServiceLifecycle.java:282` 的 ApplicationReadyEvent 会清理陈旧运行态/核对遗留 PID，`SkillOutbox.java:24` 会认领并投递既有事实，`ArtifactStore.java:641` 会清理到期对象，`FixtureStartupGuard` 也会启动查询 PG。因此先建本卡唯一空库，再启动 legacy 与 PROCESS；两例均不传服务文件，临时使用标准 `SPRING_DATASOURCE_URL` 指向新库，JMX 核对有效 datasource.url。正向前清空 `SPRING_DATASOURCE_URL`/`SPRING_APPLICATION_JSON`，只用各服务文件中的 `AGENT_DB_URL`，避免标准高优先级变量掩盖服务文件接口。

第一轮 [live-ex01-20261028-221520-103](runs/live-ex01-20261028-221520-103/run.json)已实际创建本卡独立空库，legacy skill PID28160 启动后通过6项 JMX 检查（含 datasource.url），受限原日志的安全行是 `EX01_JMX_PASS service=skill checks=6 valuesLogged=false`；该 PID 已由本仓停止脚本停止。首轮报告器在 finally 使用 `@($facts)` 转换 `List[object]` 时发生 ArgumentException，最终报告未落，且可能遮盖更早异常，不能推断剩余步骤完成。该轮执行的脚本/Java已原样复制到 run 目录，补采记录只保留可证事实，不冒造完整成功。

[纯 PowerShell 兼容性复现](reporter-compatibility-red-green.json)确认 5.1 中 `@([List[object]])` 报 ArgumentException、`.ToArray()` 正常；已仅修证据采集，增加不含异常消息值的安全失败栈和探针退出事实。没有重跑 Maven。之后主执行者临时接回实际运行窗口，为 EX-03/27 采集真红，本卡保持文件/普通 PS 工作，待明确归还后继续。

其后 [实际采集函数无业务红](live-reporter-harmless-red.json)进一步复现：普通 PowerShell 子进程已输出成功，但经原异步采集边界后 ExitCode 为 null；启动后保住 Process.Handle 后，[同函数绿检查](live-reporter-harmless-check.json)得到真实 exit 0。`.NET File.ReadAllLines` 取代带 PSDrive/Provider 附加属性的 Get-Content 字符串，避免报告膨胀。这里只修异步证据采集器；生产 Start-AgentServiceProcess 没改，生产启动路径只取 Id/登记 PID，原46项微测用原生 Wait=true，不据此宣称新增生产等待语义。原超大无秘密诊断对象只留忽略 raw，不纳入可版本化证据。

| 真实阶段 | 实际结果与范围 |
| --- | --- |
| [live-ex01-20261028-223728-310](runs/live-ex01-20261028-223728-310/run.json) | 独立空库创建0；legacy skill 健康200、JMX含 datasource.url 的6属性检查exit0；正式skill JAR PROCESS=true/空白名单退出1、精确生产构造拒绝匹配true、日志显示该新库。随后正向app健康200，但engine退出；本轮整体 INCOMPLETE、脚本exit1，不计正向通过 |
| [engine 精确阻塞](runs/live-ex01-20261028-223728-310/engine-startup-blocker.json) | 原JAR确切异常 `java.lang.NoSuchMethodException: cn.agentplatform.engine.infra.OntologyContextPacks.<init>()`；public两参与private四参构造均未声明@Autowired。记录源/JAR SHA与受限原日志SHA，Java未在EX-01中修改；由EX-36独立卡补修 |
| [局部续跑](runs/live-partial-20261028-225338-553/run.json) | 脚本真实exit0、状态PARTIAL_PASS_ENGINE_BLOCKED、wholeCardPassed=false。复用原独立库与环境文件，未重跑legacy/PROCESS/Maven；只启动app/skill/gateway，三个真实JAR均健康200/JMX通过，父进程预有两把key仍隔离，父值恢复 |
| app凭据与协议 | 在独立Java探针进程中，Spring从当次app JAR classpath/YAML装配ReleasePins并注入binder引用，向真实OR写入新appCode，首次PINNED/changed=true、重复false，同bindingId `c94c828b-5734-4474-8209-195a83ba5354`。只读引用经SecretVault解析后的真实POST为403/NO_PERMISSION；不是在运行中的app PID内触发发布/J9，也不冒充engine Bean已装配 |
| skill配置 | 正式skill JAR实际STRICT、展开预算128、PROCESS=false、AUTHORITY属性匹配；AUTHORITY缺provider拒绝且不回落仍由既有ServiceTicketsTest负例证明，不冒充已接外部IAM |
| gateway配置与0语义 | 正式gateway JAR实际enabled=true/window17/max2；当次JAR真实Spring装配的IntegrationAccess限流器第三次调用拒绝。只重启gateway为true/0，JMX匹配，配置限流器32次放行。CLI范围为configured-bean-only；HTTP鉴权链由前述IntegrationRateLimitTest六例覆盖，不把CLI当真实业务HTTP链 |
| [engine-only 补验](runs/live-engine-20261028-232216-804/run.json) | EX-36 提供的新包记录真实 exit0；engine/common 的154份生产源码集合与SHA、新engine JAR `76AB67C3A72CD1931E2DC64E053CAE8A11A86A850CFA9DA5EB95FEE1132B419C` 在补验前后均匹配。只启动engine PID50360，健康200、JMX9属性exit0，窗口97/三级301/211/101；父进程预存两把key与binder引用，engine PID中只有readonly值/引用，binder值缺席，父原值/引用恢复。另在独立Java探针进程中，用新JAR classpath/YAML经Spring装配OntologyContextPacks，向真实OR读回既有binding及context-pack，探针exit0；该读取不在engine PID内触发业务run或J9 |
| engine true/0 | 仅停并重启engine PID6276，健康200、JMX9属性exit0，quota-enabled=true/窗口97/三个阈值0。不限语义关联此前真实PG定向中 `RunQuotaPostgresTest#zeroLimitMeansUnlimitedAndUnknownModelSkipsCredentialLevel` 的PASS，EX-36新engine完整轮也保留该方法PASS；本轮不预置成功业务记录、不冒称新业务运行准入已执行。脚本原生exit0、ENGINE_SUPPLEMENT_PASS、wholeCardPassed=false |

第二轮正式PROCESS负例保留了真实退出码与精确异常匹配事实；它的默认skill日志在后续正向启动时被现有启动器覆写，未保留该负例完整原始日志，不重跑已绿阶段补造“首轮原件”。engine失败日志已在本轮受限目录另存，后续补修不能覆盖原失败。局部续跑保留公开space/release/appCode及绑定ID，未预置业务成功数据。

两轮均只停止本卡由生产脚本登记且命令归属匹配的PID，清理结果true；共享`.env`指纹前后一致，所有扫描的四服务日志/诊断输出/可版本化文本均未出现两把授权key值。受限真实环境文件仅在本仓`.local/ex01-private/`，不入Git；PG/Redis/MinIO/OR与keepers未停。

engine续接约定见 [engine-continuation-context.json](engine-continuation-context.json)，实际最小入口为 [run-live-engine.ps1](run-live-engine.ps1)：复用生产三函数与原实际采集函数，只校验EX-36的engine/common新源与engine JAR，未重跑Maven/legacy/PROCESS/app/skill/gateway。使用原本卡独立库、受限服务文件与既有pin；所有进程仅按本仓登记PID停止。两次engine启动PID50360/6276均已停止，.env前后同SHA、75个扫描文件未出现两把授权key。运行窗与全部src/main固定写窗已在结果落盘后立即交还主执行者，无后台实际命令；其余服务仅引用历史已绿轮，不冒同轮全绿。

## 未覆盖与后续门槛

- gateway 环境占位及实际JAR配置/限流器已验证。最终共享轮已取得回归门true；历史forcedRelease红、诊断及编译失败仍保留，没有把它们promote为基线。共享轮保留15项原非通过和native1，不称全部测试通过；本卡没有修改EX-35两Java或EX-27夹具。
- 本节以上 PowerShell 微测不替代 Java/PG/HTTP 验收；后续定向与完整模块对照按上节实际记录。本卡不改前端，不以未执行的前端或独立真实外部系统验收记通过。
- app真实配置/binder客户端及EX-36后engine正式启动/实际只读客户端/三级配额配置已分别验证。J1/J2仅上述凭据/客户端片段；J9与完整联合链路仍需EX-02/06/07，不在此处冒记。
- “服务文件模式拒绝复用旧 Java 进程”、重解析点拒绝尚只做源码检查，未运行端口或链接场景；仍列未覆盖。
- [review.md](review.md)已由独立审阅者完成前置核验，尚未作最终接受结论；本轮补共享门、统计口径与显式候选导航供其窄补终评。最终index冻结、DoD④终评、DoD⑤提交与台账回填仍由主执行者/独立审阅者处理，本卡未自闭卡、未操作index。

## 回滚

仅恢复本卡修改的 `scripts/start-backend.ps1`、`.env.example`、本卡将改的 YAML，并移除本卡新增测试脚本；真实 `.env` 与任何凭据均未修改，无需凭据回滚。恢复前先按精确文件 diff 核查是否出现后续他人改动，不使用整仓 reset/add。已经由其它卡创建的真实凭据如需撤销，由各自验收记录独立处理。

真实阶段创建的两份独立空库、受限服务环境文件与一个新appCode本体绑定均有run归属，已用于engine补验并继续保留复核；未删除任何既有库/绑定。共享EX-02授权凭据未轮换或撤销；新绑定的退役与私有文件清理由联合夹具所有者另行登记，不因回滚脚本擅自撤共享key。
