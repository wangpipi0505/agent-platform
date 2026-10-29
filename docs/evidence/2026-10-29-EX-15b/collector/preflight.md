# EX-15b 父目录段最小修复：skill Full 采集器候选

2026-10-29，准备者 `artifact_boundary_review`。**仅完成静态准备，未执行采集器入口或任何 Maven/Java/PG/HTTP/产品进程。** 本次只新增本目录 `run-regression.ps1`、`manifest.json`、本文三个文件；旧 EX-15a/EX-04 采集器、证据、产品、测试、三主入口及 index 均未改。未使用 Superpower。此候选须由 root 独审后获得唯一实际窗口，不自行宣布实际准入或回归通过。

## 冻结对象与基线

| 对象 | 精确值 |
| --- | --- |
| 当前 collector SHA-256 | `87D0B0A35920AC843DE065DC4A57009723F9110A5DACA732622FCA795B9EA957` |
| 当前 manifest SHA-256 | `833EE0F815DAB47FDD22F6F672E23B7EE31E295389A8BE761055CA4C50511C9F` |
| manifest.expectedHead | `acad6bdee223b23f331420c6de50045cb5fa8941` |
| ProcessAdapter.java | `5A0C87FD88EECE89C2B5A2F85C95B599C59124468830E5A500C734A935118E80` |
| ProcessFilesTest.java | `7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7` |
| `.env`，只读取摘要不输出内容 | `AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A` |
| installed admin-common JAR | `1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87` |

最初准备时 HEAD 为 `1cc453388950ec90eba51a1484994208c78d67fd`；root 完成 EX-08 仅文档检查点后，本次实读 HEAD 为上表 `acad6b…`，只将 manifest.expectedHead 更新为此提交并重新冻结 manifest SHA。运行前必须等于该精确 HEAD，结束仍须相同；没有删除 HEAD 门。

唯一基线为 `docs/evidence/2026-10-29-EX-15a/collector/runs/full-skill-20261029-ex15a-01`：**698=582P/6F/106E/4 旧 S，71 XML**。本次直接读取并核验其 71 份 XML 的原 manifest SHA，逐一对照 698 条 CSV 的完整方法身份、状态、message SHA、detail SHA，全部相同；原 summary 的 regressionGatePassed=true、allGreen=false 均保留。

本步只新增 `cn.agentplatform.skill.infra.ProcessFilesTest` 两个精确方法：

- `acceptsValidEnvelopeWithoutOutputFiles`
- `rejectsParentRelativeOutputPathBeforeReturningSuccess`

它们与全部原 698 个身份合并后无重复，形成 **700=584P/6F/106E/4 旧 S、72 类/XML** 的期望集合。不能只比较总数或非通过数量；遗漏、额外身份、大小写变化、任何原状态变化均失败。总跳过必须为 4，新增跳过必须为 0。

实数冻结 `skill-registry/src` **151 文件**。相对基线 150 源只允许 ProcessAdapter 原路径内容变更和新增 ProcessFilesTest，未删除任何基线源。其余 149 源逐文件 SHA 相同。前置绿使用本卡 `path-green-20261029-02` 的 native0、2P/0F/0E/0S，要求 identityComplete/actualBehaviorGreen/sourceStable/contextStable 全 true；其原源快照包含 admin-common 和 POM，collector 仅按精确 `skill-registry/src/` 前缀取 151 项与当前全量比较，全部相同，不能把旧 150 快照或 subset 当本轮 151。

manifest 共有 22 个明确文件 pin，包含新 collector、原 EX-15a collector/manifest、原 EX-04 reader/原因逻辑/PG 前提、原 MinIO SDK、上述唯一基线、前置绿原件。XML manifest 的 71 个文件 SHA 另逐个校验；151 源、`.env`、common JAR 另有固定门。没有重写原件。

## 沿用的环境、数据库及原因边界

与原 EX-15a 对比，`Read-Pg`、`Check-Storage`、`Read-ExactReasonText`、`Identity-Map` 函数 AST 文本逐字相同；完整原因比较块从 UUID/timestamp 定义到逐身份原因记录逐字相同；knownSqlHistory 的 16 个 script/version/checksum 条目完全相同。

- PG 仍固定 `127.0.0.1:25432`、原 PID **15204**、`V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe`、`V:/.local/pgdata`、`agent_v12`。不发现或接管新实例。
- 仅既有五库：`agent_platform_v12`、`agent_platform_skill_algorithm_test`、`agent_platform_skill_algorithm_migration_test`、`agent_platform_skill_digest_test`、`agent_platform_ontology_import_test`。仍核库 owner、skill/history owner、已知成功迁移、原 SCHEMA 行约束、主库完整 16 SQL，以及 ontology 专库原精确 marker。任一缺失、归属或未知历史不符即停，不建库、不修 history、不扩大 SQL 白名单。
- 原基线 after-storage 实读为上述五库各 17 条 history、owner 一致、已知历史通过；这是历史原件，**不是本次已查询当前 PG**。当前五库/实例是否仍满足，必须由实际窗口内原 before 检查决定。
- 仅原 `agent_platform_skill_algorithm_migration_test.skill` 与 `agent_platform_skill_digest_test.skill` 保留既有 JUnit DROP/replay 范围；collector 自身无 DDL、建库、队列或数据清理。没有给其它旧 ERROR 类补前提。
- MinIO 仍只核原 `http://127.0.0.1:29000/minio/health/live`；不清桶。原 `.env` 白名单、`ENGINE_PG_TEST=true`、JDK21 路径、common JAR 和 Maven 本仓仓库均不扩大；额外 JVM/Maven options 非空即停。没有注入诊断开关。

完整比较全部原 **116 非通过（6F+106E+4S）**，要求 status、exception type、cause 类链、首项目栈以及 message/detail 原 SHA。唯一已有可解释波动仍只限 `AlgorithmMigrationPostgresTest.v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity` 的 AssertionFailedError：精确身份/结构一致，message/detail 前后共 8 个 UUID/timestamp 计数均恰 2，替换这两种表示后**完整文本**相同。其它任何字符、原因或方法不套该逻辑，不泛化归一化。原 XML、message/detail SHA 及 bounded proof 全保留。

新增 summary 的 `rawReasonComparisonPassed` 明确记录未经表示解释的原始门；存在该合法波动时它仍 false。`boundedVolatileAccepted` 单独计数，`reasonComparisonPassed` 为原有逐项解释后的相对门，不能倒写 raw 门。AlgorithmOffloadInterruptionTest 原五方法、四 SDK outcome、无诊断 flag 门照旧。

## 静态核验结果与实际命令

实际使用 `C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe` 的 Parser：**5.1.26100.9711、0 errors、UTF-8 BOM=true**。最后一次解析对应上表 collector SHA。只做 ParseFile/AST/文件摘要/XML数据读取，不调用 collector 入口。

纯文件核验确认：22 pin 全匹配；基线 71 XML/698 CSV 的精确身份、状态与原因摘要全匹配；当前 151 源匹配 manifest；delta 恰两路径、removed=0；绿轮的 skill 投影恰 151 且全同；knownSqlHistory、上述四函数及完整原因块与原版相同；`.env` 和 common JAR 摘要仍同。最终 `expectedHead` 实读匹配 `acad6b…`。

一次辅助静态检查尝试直接 dot-source 已 pin 的旧纯文件 reader，被本机 PS5 执行策略以 `AuthorizationManager check failed` 阻断；没有进入 collector、Maven或网络。随后改用内联的直接 XmlDocument 数据读取完成 XML/CSV 哈希核对，未更改本机执行策略、不将这次辅助失败冒充业务红。实际启动仍须由 root 使用其已授权的 PS5 执行环境；本报告不自动修改执行策略。

root 独审三文件后，唯一实际窗口的命令候选为（未执行）：

```powershell
Set-Location -LiteralPath 'G:\207\项目\算法重组工具\代码\agent-platform'
$ex15bManifestSha = '833EE0F815DAB47FDD22F6F672E23B7EE31E295389A8BE761055CA4C50511C9F'
$ex15bRun = 'full-skill-ex15b-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
& 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile -NonInteractive -File .\docs\evidence\2026-10-29-EX-15b\collector\run-regression.ps1 -Mode Full -RunId $ex15bRun -ApprovedManifestSha256 $ex15bManifestSha -ExecuteApproved
```

Maven 参数与唯一基线 Full 相同：`-B -ntp -pl skill-registry test`、本仓 `.tools/m2`、`-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`、`-Dsurefire.timeout=900`、`-Dsurefire.failIfNoSpecifiedTests=true`、`-Dmaven.test.failure.ignore=true`。没有 `-Dtest` 子集、clean、install 或 fork/reuse/order 更改。

native 原退出码、failureIgnore 和 collector 退出码各自保留。Maven native0 不代表 700P；collector0 仅表示 700 方法的完整相对门、原因门、4旧S/新增S0、SDK、源/上下文和前后存储检查通过，1 为回归门未满足，2 为前置/采集失败。已有 run 拒绝覆盖，不自动重跑、清理或 stage。若实际五库/实例/history 守卫失败，保留阻断并报告，不能更换基线或放宽范围。本步即使相对回归通过，也不关闭 EX-15b 完整 files 通道。
