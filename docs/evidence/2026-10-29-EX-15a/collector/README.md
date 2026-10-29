# EX-15a 完整回归与相邻扩面采集器

当前状态：**准备并静态检查，未启动实际窗口。** 本目录只新增本说明、`run-regression.ps1`、`manifest.json` 三个文件。没有修改旧 EX-04 采集器、旧 run、源码或 index；不新增忽略规则，不自动 stage、清理或改写原始证据。

## 唯一基线与集合

- 完整基线为 `../../2026-10-28-EX-04/collector/runs/full-skill-legacy-save-20261029-1112` 的原 `methods.csv`、71 份 XML 和 `source-after.json`，共 **689=573P/6F/106E/4 旧 S**。它是 EX-04 已完成后的基线，不能换回 EX-27 的 674。
- 本卡原 `MultipartStreamingTest` 7 项加 4 项、原 `ArtifactPersistenceTest` 11 项加 5 项，新增身份共 9。完整期望为 **698=582P/6F/106E/4 旧 S，71 份 XML**；所有原身份/状态以及旧 116 个非通过的原因必须保留。
- 相邻扩面仅固定 5 类：`ArtifactBoundTest` 3、`ArtifactReferenceTest` 5、`MultipartFormMappingTest` 13、`HttpAsyncAdapterTest` 2、`PathParamsAsyncMappingTest` 9，合计 **32P/0F/0E/0S、5 份 XML**。不重跑别的范围来掩盖红例。
- manifest 冻结 150 份 skill 源文件，并核相对 EX-04 只变 5 份：3 生产 `ArtifactStore`/`MultipartFormMapping`/`SkillAdapters`，2 测试 `ArtifactPersistenceTest`/`MultipartStreamingTest`。其他文件或新增源路径出现就拒绝。
- 两个前置绿轮分别为 `upload-green-20261029-1305` 16P、`multipart-green-upload-20261029-1305` 11P，原 summary/native/XML/source-after 全部 pin；运行前必须与当前 150 源完全一致。

## 实际窗口前置

脚本只能在 root 独审三文件、核对冻结 SHA 并授予唯一实际窗口后运行。`-ExecuteApproved` 与 `-ApprovedManifestSha256` 必须同时提供；它们不是采集器自行批准，也不代表可与别的 Maven/PG/服务操作并发。

启动时读取当时 HEAD，结束时核它未变，**不绑定 EX-05 提交前的旧 HEAD**。工作区产品尚未提交不等于不能验证，实际运行对象由 manifest 的 150 份原始字节哈希定义。源/`.env`/installed admin-common JAR 均在前后核验。

固定环境：

- `.env` SHA `AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A`；原 `AGENT_DEV_AUTH=true`，不设置 IAM 模式，不注入诊断 flag。
- installed admin-common JAR SHA `1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87`；仅本仓 `.tools/m2`，不 install 新依赖、不改 Maven 生命周期。
- JDK `C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot`；原 `-Xmx1024m`、`-Djdk.net.unixdomain.tmpdir=V:/.local/tmp`、`-Dsurefire.timeout=900`；默认 fork/reuse/order 不改。继承的额外 JVM/Maven options 非空则停止，不自动清掉它们。
- PowerShell 5.1 语法，脚本带 UTF-8 BOM；JSON 按 UTF-8 显式读取。

PG/MinIO 前后检查均由采集器在实际授权窗口内执行，只读：

1. 原 AP PG `127.0.0.1:25432`/PID 15204、固定 postgres executable 与 `V:/.local/pgdata`、当前 role `agent_v12` 必须一致；不扫描其它实例。
2. 五个既有库为 `agent_platform_v12`、`agent_platform_skill_algorithm_test`、`agent_platform_skill_algorithm_migration_test`、`agent_platform_skill_digest_test`、`agent_platform_ontology_import_test`；逐个 owner、skill schema/history owner、已知成功迁移脚本/version/checksum 必须匹配。只认一个合法 rank0/NULL-version/NULL-checksum/`"skill"` SCHEMA 行，不能把 unknown 当白名单；主库必须含全部当前迁移。迁移专库可保留原合法已知前缀，不帮它补 schema/history。
3. ontology 专库还须原 EX-03 精确 marker。MinIO 只检查原 `http://127.0.0.1:29000/minio/health/live`，不操作或清理桶。
4. 原 JUnit 仅 `AlgorithmMigrationPostgresTest`/`AlgorithmTestDatabase` 的 `agent_platform_skill_algorithm_migration_test.skill`，及 `SkillPublishDigestPostgresTest` 的 `agent_platform_skill_digest_test.skill` 保留既有 DROP/replay 授权。采集器不发 DDL、不建缺失库、不清旧队列，不给其它原 ERROR 类补运行前提。五库任一不存在/归属或历史不符直接停。

## 审阅后执行

以下命令仅在 root 确认实际窗口后执行；每轮生成唯一目录，已有目录会被拒绝。没有运行中的其它产品验证时才可以切下一模式。

```powershell
Set-Location -LiteralPath 'G:\207\项目\算法重组工具\代码\agent-platform'
$ex15ManifestSha = 'E2041FFD36FF25A74790DD6888B2A37DFDB920E07F7F48766B48FB647D5086BC'
$ex15FullRun = 'full-skill-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
powershell.exe -NoProfile -NonInteractive -File .\docs\evidence\2026-10-29-EX-15a\collector\run-regression.ps1 -Mode Full -RunId $ex15FullRun -ApprovedManifestSha256 $ex15ManifestSha -ExecuteApproved
```

完整轮已退出并归还窗口后，相邻扩面另开 run，不覆盖原件：

```powershell
$ex15AdjacentRun = 'adjacent-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
powershell.exe -NoProfile -NonInteractive -File .\docs\evidence\2026-10-29-EX-15a\collector\run-regression.ps1 -Mode Adjacent -RunId $ex15AdjacentRun -ApprovedManifestSha256 $ex15ManifestSha -ExecuteApproved
```

Full 只增加 `-Dmaven.test.failure.ignore=true` 收完整原失败报告；**native 0 不表示全绿**。Adjacent 不使用 failure.ignore。Maven 原退出码总是独立写入 `native-exit.json`；采集器退出 0 仅表示该模式的身份/状态/原因/环境门全满足，1 表示已收集但回归门不满足，2 表示前置/采集/完整性错误。旧 6F/106E/4S 必须在总结中保留，不能把 Full 的 collector 0 写成 698P。

## 证据与原因比较

- 每轮完整保存原始 `raw.log`、新鲜 XML 及每份 SHA/长度/mtime，保存实际 approved manifest 和 collector 副本；不依赖旧 target 中的残留 XML 充数。原始日志和 XML 不重写、不脱敏替换。
- 只读 dot-source 已 pin 的 EX-04 `full-report-records.ps1`，沿用四字段方法身份、class event、异常类型、cause 类链、首项目栈、原 message/detail SHA 的读取逻辑。
- `compare-full-reasons.ps1` 只作为 pin 住的既有逻辑出处，不运行其入口，不使用它的 674 guard、旧目录或 EX-04 projection extractor。新脚本只移植精确 case 定位和原单一 `AlgorithmMigrationPostgresTest.v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity` 的有界两 UUID/两 timestamp 比较：身份/type/结构原因先相同、8 个计数全部恰 2、完整 message/detail 临时替换后均相等，才接受该一项波动；原哈希和 XML 均保留。其它原因不得泛化归一化。
- Full 另外保留原 AlgorithmOffloadInterruptionTest 五方法、四条 SDK outcome、无诊断 flag 门，不以方法通过代替它们的原边界义务。
- Collector 不 stage、不修改 index、不清理任何运行产物。后续 root 按精确清单审查原件是否含真实秘密，再决定入库范围；发现敏感项时原件保留在明确的本仓 runtime 位置并另出说明，不由此采集器自动删除或覆盖。
- 任一门红时保留原件并定位，不能改断言、换基线或扩大 scope“刷绿”。本采集器不会宣布 EX-15a 完成；真实 JVM 重启及最后独审/三主入口/本地提交另行收口。

准备冻结：脚本 SHA `25681F23901F3ED2290A4F3AE772C761EE1FB13DCC020FD70803B57418C850A2`；manifest SHA `E2041FFD36FF25A74790DD6888B2A37DFDB920E07F7F48766B48FB647D5086BC`。静态 parser 为 0 error；未执行脚本或实际产品验证。
