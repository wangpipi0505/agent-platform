# EX-15a gateway 完整回归采集候选

2026-10-29，`algorithm_interrupt_readonly`。本轮仅新增 `run-module.ps1` 与本说明；未运行 Maven/Java/PG/HTTP/服务，未读取 `.env` 或凭据，未改产品、测试、主入口、旧证据或 index；未使用 Superpower。运行入口由 root 静审并授唯一实际窗后，才可在独立 Windows PowerShell 5.1 进程执行。

## 已核事实与准入

- 比较基线为 EX-27 `safety-module-20261028-233458-012`：27 XML / 288 报告身份 = 273 PASS / 7 FAIL / 8 ERROR；49 个原 SSE peer 子进程均退出。上下文为 `-DreuseForks=false`、Xmx1024m、既有本仓 m2/Java21/unix tmpdir，未重置 ServiceTokens、未使用 failure.ignore。旧 EX-00 的 fork 复用差异已经是该基线历史，不能在此再换 fork/顺序。
- 新类实际两轮原件：`gateway-red-20261029-ex15a-01` 11=7P4F/native1，四个大请求用例精确 `expected 200 but was 502`；`gateway-green-20261029-ex15a-01` 同11全部PASS/native0。独立读取两个原 XML确认身份和停点；原测试 SHA 始终 `b4fcd5ab65dc80f0f509c31c1f5bd9ce56d1dd57942ed9d802f8198d99994f32`，controller 从 D920…变为 `75ef9a91d893c20d727e1251123901488719fb81c1e38621ee0171ec51f52837`。这些是 root 的实际运行，非本准备者重跑。
- 最小生产 diff 独审无阻断：只在 POST、原始URI严格 `/admin/artifacts`、解析后严格 multipart/form-data 时给65MiB总wire额度，普通路径/方法/无效或相似媒体类型仍2MiB；仍8KiB流式逐字节计数，超限块不写上游，原错误信封保留。11项保留已知/未知Content-Length、64MiB文件、65MiB整wire、超1字节、普通2MiB±1、PUT/其它路径/畸形和相似类型。该定向证据不证明入站FilterChain、PG/MinIO完整持久链。
- 完整预期现可明确为28类/299身份 = 284P7F8E；**runner仍从已冻结green XML读取新增11身份**，不把数量写成新类替代品。原288逐身份/状态不变，新身份必须全部PASS；类级事件和未报告身份不得丢弃。

## 运行输入：一个批准候选与既有回执

`run-module.ps1` 参数为 `-RunId -CandidatePath -RuntimeWindowGranted [-ApRoot]`。不硬绑旧HEAD、旧绝对仓路径或旧SSE peer根；记录实际开始/结束HEAD，peer根从冻结的原SSE测试源码解析。root可在本目录写允许的 `module-manifest.json`；字段如下，路径均为本仓相对路径或本仓绝对路径：

```json
{
  "approved": false,
  "baselineDirectory": "docs/evidence/2026-10-28-EX-27/runs/safety-module-20261028-233458-012",
  "greenXml": "docs/evidence/2026-10-29-EX-15a/gateway/gateway-green-20261029-ex15a-01/methods.xml",
  "greenSummary": "docs/evidence/2026-10-29-EX-15a/gateway/gateway-green-20261029-ex15a-01/summary.json",
  "greenSources": "docs/evidence/2026-10-29-EX-15a/gateway/gateway-green-20261029-ex15a-01/source-before.json",
  "expectedFingerprints": "docs/evidence/2026-10-29-EX-15a/gateway/module-fingerprints.json",
  "pgAdmission": "docs/evidence/2026-10-29-EX-15a/gateway/module-pg-admission.json",
  "addedClass": "cn.agentplatform.gateway.ArtifactUploadGatewayTest",
  "addedSource": "platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java",
  "javaHome": "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot",
  "unixTmpDir": "V:/.local/tmp",
  "extraFingerprintPaths": []
}
```

以上是格式，**不是已批准收据**。`approved=false` 必拒绝。fingerprints/PG回执由 root 在正式 live 两服务结束、源码冻结及实际PG只读准入后生成，不由采集器自批。

`expectedFingerprints` 是严格排序JSON数组，每项固定顺序为 `{path,exists,sha256}`：path用 `/`、SHA小写、按path排序、显式exists；必须与runner `Fingerprints` 函数所得全部项目一致。包括 gateway/admin-common **全部src文件**、root/admin-common/gateway三份pom、`.env`摘要、installed common主JAR及tests JAR，另加root冻结的子fixture classpath文件/相关实际目标制品路径。缺`.env`或installed JAR拒绝，不自动安装/重建补齐；不读取或复制环境内容到公开证据。可复用本仓EX-01的AST提取方式只调用 `Own-Path`/`Sha`/`Fingerprints` 生成该数组，不能为此执行runner顶层或旧run-safety入口。

原chain测试会读取 `.local/iam-n4b2-gateway-published-chain/{app,engine}-classpath.txt`、`.local/iam-o1c-gateway-status/engine-classpath.txt`；root还应按原PublishedMetadata launcher的既有classpath状态记录对应文件/制品。**不要修复历史缺类或编译子fixture来让旧15失败消失**；已知缺失路径可作为exists=false指纹保留。额外路径通过候选 `extraFingerprintPaths` 输入，不扩成自动发现或任意工程扫描。

## PG实际准入接口（采集器零SQL、零DDL）

PG回执需 `passed=true/checkedAtUtc/configurationSha256/host/port/pid/dataDirectory/databases`；运行前须在15分钟内，配置摘要与本轮`.env`一致，本仓127.0.0.1:25432/PID/数据目录一致。runner只查PID/进程路径与数据目录命令行，不输出命令行，不连PG；数据库归属由root独立只读核验。每个数据库项为 `{name,existed:true,owner:"agent_v12",projectOwnershipVerified:true,historyVerified:true}`，必须包含：

| 原本仓专库 | 准确原源码行为 |
|---|---|
| `agent_platform_iam_admission_app_v12` | `PublishedAdmissionFixtureMain`：存在检查；缺库分支会CREATE，随后app Flyway.migrate。必须提前证实存在，禁止触发缺库兜底。 |
| `agent_platform_iam_admission_engine_v12` | `GatewayPublishedEngineFixtureMain`：连既有库，核engine源码迁移上限及原history，Flyway.migrate。 |
| `agent_platform_iam_o1c_gateway_status_v12` | `GatewayRunStatusEngineFixtureMain`：缺库会CREATE，随后engine Flyway.migrate；同样必须提前存在。 |
| `agent_platform_iam_metadata_v12` | `PublishedMetadataFixtureMain`：仅既有库及app history100..104校验，无新gateway业务schema。 |

上述原fixture没有DROP/clean/TRUNCATE式schema重放；不可虚构“gateway Flyway history”。原migrate可能补既有迁移，root须按真实app/engine库history与本仓迁移文件逐项核准其范围，并保存前后历史；不为通过而插marker、清队列、reset或建未知库。缺失/归属不明即停；collector本身不发任何DDL。原fixture当前可能在缺classpath/类处先失败，不可据此省掉PG准入。

## 收集与门的含义

- 全module命令沿基线：`mvn.cmd -B -ntp -pl platform-gateway test`，本仓m2、Xmx1024m、同unix tmpdir、reuseForks=false、原surefire选项；无 `-am`、install、skip或failure.ignore，不加诊断flag。原 `surefire.timeout` 参数照留，不冒称它单独实现了整体看门狗；root持唯一实际窗并监督有界执行。
- native Maven exit首先单独保存；仅接纳本次开始后写入的新鲜XML。使用XmlReader/XmlDocument.Load解析真实字节，不用PowerShell默认ANSI强转。完整保存原XML、日志及15项原文原因到忽略目录 `.local/ex15a-gateway-module/<RunId>/`；安全清单与摘要写本卡 `gateway/runs/<RunId>/`。不把含属性/报文的raw XML/日志/凭据放公开清单。
- 原288+新green身份精确并集，检查重复、缺失、意外新增、状态改变、suite声明计数与class event。旧15逐项比较 status、顶层type、有序cause、首个项目帧、message/detail原文及SHA；**任何原文变化先令reason gate失败并给差异，不用泛化正则吞掉UUID/端口/对象identity或栈行号**。如属表示差异，root须另作有界独审，不能改旧摘要或采集器判绿。
- 新鲜peer目录按执行前后集合差识别；49条退出记录及scenario多重集合必须保留。原forced/off-lock properties固定安全不变量逐项比较；动态 `recycled` 值单独保留观察，不把回收计数当完成凭据。复制原安全JSON/协议属性及phase顺序到本地raw，原SSE 38项也必须全部PASS。不重置旧peer目录、不加新诊断或等待。
- `nativeMavenExit=1` 是保留7F8E时的预期，不是模块全绿。另列 `collectorExit/identityGate/original15RawReasonGate/sseAnd49ChildGate/regressionGate/allTestsPassed`；只有相对门全通过才collector0，原15保留时allTestsPassed仍false。编译/前置/采集失败不计目标红，固定phase/行号/安全类型留证，finally恢复父环境；采集器结束不替root宣告运行窗已交还。

入口示例（仅root独审/冻结/实测PG准入后执行，本准备者未运行）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  "$ApRoot/docs/evidence/2026-10-29-EX-15a/gateway/run-module.ps1" `
  -ApRoot $ApRoot -RunId 'gateway-module-<actual-runid>' `
  -CandidatePath 'docs/evidence/2026-10-29-EX-15a/gateway/module-manifest.json' `
  -RuntimeWindowGranted
```

实际RunId需满足入口字母数字/连字符规则，不能把示例尖括号原样传入。独立PowerShell进程会只读加载AP `.env`，不会加载OR凭据，finally恢复所触及的环境变量。执行后先由root明确交还实际窗，再纯文件做原因复核与证据收口，不循环抽样、不关整张EX-15a。
