# EX-27 响应所有权修复：SDK6、相邻83、Full701 采集候选

2026-10-29，`/root/algorithm_interrupt_readonly`。本轮只新增本目录 runner、manifest 与本文；未执行任何采集入口、Java/Maven/PG/HTTP/Node，未读取 `.env` 内容，未改产品/测试/旧证据/主入口/index，不使用 Superpower。以下是静态准备，不是运行结果。每阶段须 root 独审、明确给予唯一实际窗口后，单独执行；没有自动连跑。

## 冻结

| 项目 | 精确值 |
| --- | --- |
| runner SHA256 | `5498A0679E7F1AC4D97F9961BF66A1ED15B91E044D3D5E0622968EEE7F40AAB2` |
| manifest SHA256 | `BBFE4A1AD8282B5A010696A03CB83452187738C8C983AE294DB2A2C2262549F6` |
| HEAD | `acad6bdee223b23f331420c6de50045cb5fa8941`，运行结束必须相同 |
| ArtifactStore.java | `75F89B938A054BE6AC385C7152F49A9525D25E696BCE3634C42C7BD3201AE907` |
| 新 Ownership Test | `40474F829DEBD58D0B1D689D8F139F5BD89E2B2A07B298470D90C870960397F6` |
| 原 SDK 五身份 Test | `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D` |
| `.env`，只公开摘要 | `AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A` |
| installed common JAR | `1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87` |
| 产品窄范围独审 | `../response-ownership-independent-review.md`，SHA `79701338AF62826B9DCB2CEDD7AE4C9384058AFFF5C2EDB594709FD6ABCD6903` |

完整 skill 源为 152 项：相对旧 EX-15b 的 151 项只允许 ArtifactStore 的 967849→75F89B 变更及新增 Ownership Test；原 151 路径不得删除。其余 150 源不变。运行前后逐项匹配 manifest，不以“总数正确”代替内容一致。

root 已有 EX06 独立 124 文件暂存，报告 index tree `d653bc84d770910b7c117921fd9ef31efa9a3956`。这是上下文记录，非产品运行证据；本入口不要求 index empty、不改 index，仍要求被测源/HEAD/env/common 的精确冻结。

## 集合与阶段依赖

| Mode | 精确报告/XML | 期望状态 | 运行前置 |
| --- | --- | --- | --- |
| SDK | 6 / 2 | 6P、0F/E/S | 已冻结单例 Ownership Green 原件与同152源 |
| Adjacent | 83 / 10 | 83P、0F/E/S | 本目录已完成同栈 SDK6，必须显式传 RunId |
| Full | 701 / 73 | 585P、6F、106E、4旧S | 同栈 SDK6 与 Adjacent83，显式传两 RunId |

SDK 是原 `AlgorithmOffloadInterruptionTest` 五身份加新 Ownership 一身份；四条原网络 outcome 必须齐全且语义成立，诊断 JSON 为 0。新 Ownership 安全行必须唯一、同原 future 成功且 calls=0。没有使用两个诊断 flag。Adjacent 是旧相邻 manifest 的原十类（含 ProcessFilesTest），准确 83 个身份，不调用旧 254 项采集入口。

Full 唯一正式基线仍为 EX-15a `full-skill-20261029-ex15a-01` 的原 698=582P/6F/106E/4S、71 XML，再加 ProcessFiles 两身份及本次 Ownership 一身份，形成 701/73。旧基线 71 XML 的 manifest 逐个核 SHA，CSV 与 XML 698 个完整身份/状态/message/detail SHA 逐项相同，才生成上述集合。新增三个身份不得与基线重复。

依赖回执必须已完成，native0/noignore、collector0、全绿、同 manifest/runner/HEAD/152 源；不能用一个 summary 数字替代源与准入指纹。单例 Green 的 summary/source/witness/XML 原件也全部 pin。任何阶段非通过、缺失、额外身份、类级意外事件、跳过或指纹漂移均停，不自动进入下一阶段。

## 环境、资源与原因门

入口从旧 EX-15b Full 采集器派生。`Write-Fact/Hash/Assert-Pin/Sources/Identity-Map/Read-Pg/Check-Storage/Read-ExactReasonText` 八函数 AST 文本逐字相同；原因比较块从 UUID/stamp 到逐身份 proof 输出也逐字相同。仅 source 数量门为 152，新增模式/新向量/依赖门。

- 保留旧五库原严格只读准入：本仓 PG25432/PID15204/`V:/.local/pgdata`/agent_v12，库/schema/history owner，已知16 SQL/合法 SCHEMA 分类，ontology 精确 marker。缺库、未知库或未知迁移即停；collector 不建库、不 DDL、不补标、不清队列。
- 保留本仓 MinIO29000 的原只读健康检查，不清桶；SDK 测试仍由其原有限 loopback fixture 拥有，不把该健康检查说成真实存储场景验收。
- 仅 Full 选中的原两迁移测试沿原白名单重放各自既有专库 skill schema；SDK/Adjacent 不选中这些迁移测试。collector 自身始终无 DDL。
- 沿原独立进程 env 加载/finally 恢复、ENGINE_PG_TEST=true、JDK21、本仓 m2、Xmx1024m/unix tmpdir、fork/reuse/order/各测试预算。额外 JVM options 非空停，未增加 skip 或 failure-ignore 范围。
- Full 原 `maven.test.failure.ignore=true` 只为采集全部原非通过。native0、collector、逐方法相对门和 allGreen 分列；即使相对门通过，原116非通过仍在，allGreen=false。
- 原116（6F+106E+4S）逐项 status/type/cause/首项目帧/message/detail 原 SHA 比较。唯一旧 migration 表示解释仍限原精确身份，前后 message/detail 的 UUID/timestamp 各2，余下全文相等；原 rawReasonComparisonPassed 可能 false，解释后的门另列，不改原 XML 或 summary。

manifest 50 个 pin 均已只读复核；包括旧 collector/manifest、旧原件、单例红绿及独审。原 Full700 的新增 handoff F、其 summary/XML/native/source 原件继续保留，不以新运行或确定性 Ownership 红追认为唯一根因。

## 独立调用

以下从 AP 根目录执行，每条须单独获得 root 实际窗口；当前没有执行。RunId 必须新建，目录存在即拒绝。失败不改源/重试，先保存并交还窗口。

```powershell
powershell.exe -NoProfile -NonInteractive -File .\docs\evidence\2026-10-28-EX-27\response-ownership-full\run-regression.ps1 -Mode SDK -RunId sdk-six-20261029-01 -ApprovedManifestSha256 BBFE4A1AD8282B5A010696A03CB83452187738C8C983AE294DB2A2C2262549F6 -ExecuteApproved
```

```powershell
powershell.exe -NoProfile -NonInteractive -File .\docs\evidence\2026-10-28-EX-27\response-ownership-full\run-regression.ps1 -Mode Adjacent -RunId adjacent-20261029-01 -SdkRunId sdk-six-20261029-01 -ApprovedManifestSha256 BBFE4A1AD8282B5A010696A03CB83452187738C8C983AE294DB2A2C2262549F6 -ExecuteApproved
```

```powershell
powershell.exe -NoProfile -NonInteractive -File .\docs\evidence\2026-10-28-EX-27\response-ownership-full\run-regression.ps1 -Mode Full -RunId full-701-20261029-01 -SdkRunId sdk-six-20261029-01 -AdjacentRunId adjacent-20261029-01 -ApprovedManifestSha256 BBFE4A1AD8282B5A010696A03CB83452187738C8C983AE294DB2A2C2262549F6 -ExecuteApproved
```

## 静态回执及输出

真实 Windows PowerShell5.1.26100.9711 Parser=0，runner=UTF8 BOM；PS7 Parser 亦0。只读重建得到 SDK6/2、Adjacent83/10、Full701/73，Full 状态585/6/106/4，701身份唯一；50 pin 漂移0。没有调用 runner 来“验证”这份回执。

每轮保存 native-exit（先于 XML）、fresh XML及摘要、未改 raw.log摘要、完整 methods、缺失/额外/状态变化、原116原因/proof、SDK outcomes、Ownership witness、前后152源/HEAD/env/common、前后 storage、依赖回执和 collector-exit。catch 只输出阶段/行号/固定安全 reason/异常类，不输出凭据或任意异常消息。原始 raw 保本地既有 ignore 边界，不进行 Git 暂存/提交或任何旧证据清理。

当前只能交独审；没有实际 SDK6、相邻83或 Full701 结果，不关闭 EX27/EX15b 或平台能力。
