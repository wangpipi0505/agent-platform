# EX-27：V222 历史校验与原失败原因表示差异独立复核

日期：2026-10-29。审阅者：`ex04_pg_scope`，未编写本次历史校验 Java/helper、history-v2 runner 或原因派生器。本报告只接受下述两组实际证据的有限结论；**Full 933 仍未通过，EX-27 不能据此关闭**。

本轮只读本地文件、XML、ZIP、SHA 和 ACL；没有运行 Java、Maven、PG、HTTP、WSL、Node、服务或原采集器，没有读取 `.env` 凭据值，没有改动产品、测试、原证据、三主入口或 index。唯一新增文件为本报告。

## 1. 精确审阅对象与结论

| 对象 | SHA-256 | 结论 |
| --- | --- | --- |
| [run-history-validate-v2.ps1](v222-source-recovery/run-history-validate-v2.ps1) | `EE988BFF97E467D266C0AB00A97DADC5812A54DCB81C1C10963E6BC4F34439E8` | 只读历史校验采集边界可接受；实际 run 02 通过 |
| [history-validate-manifest-v2.json](v222-source-recovery/history-validate-manifest-v2.json) | `5B4AE6C6A455C4C1107BEDCF25C5A13976D3DC26D2AF683F847B09E25498CB1A` | 固定实际旧 306 源、94 依赖及历史目标，未从当前修改后的测试重新学习期望 |
| [V222HistoryValidate.java](v222-source-recovery/V222HistoryValidate.java) | `AFEE873B11C26ACE47E5A8EFF67640773EBD19D2EB7127653919FCDD26F6B117` | 仅 `validateWithResult()`；连接只读和关闭检查、日志边界可接受 |
| [derive-full-reasons.ps1](v222-source-recovery/derive-full-reasons.ps1) | `55C5837F951F2B451E26E458DE74341D22E3C53C685AEACF03B6816BF7D1C33B` | 仅该次固定 XML 对的 23 项表示差异证明可接受 |
| [full-reasons-manifest.json](v222-source-recovery/full-reasons-manifest.json) | `CFEAF05C9C9BB36055A721F3D01EFE50ABB8C3BF6601BA9E2715632B542A431D` | 精确方法、原字段 SHA、前缀、数量及既有失败边界未放宽 |

以下 `.local/` 均相对 AP 仓库，属于私密原件定位，不表示可以直接加入公开提交。公开报告不复制原异常全文、命令凭据或环境文件。

## 2. history-v2 实际通过，只证明已登记历史库兼容

实际目录：`.local/ex27-v222-recovery/v222-history-validate-20261029-02/`。其中 `collector.ps1`、`approved-manifest.json`、`validator-source.java` 与上表对应文件 SHA 一致。

实读 `summary.json`（SHA `705E4B2F1AC4FB2EFC2E92F98D41529E257B28C01CC9A2B3B2CBF3CE3B841BA7`）、`collector-exit.json`、两份 `*-native.json` 和 `validator-result.json`：

- `compileNativeExit=0`、`validateNativeExit=0`、`collectorExit=0`，没有 timeout；实际编译与校验均在各自 60 秒预算内。
- `validationSuccessful=true`、`validateCount=25`、`invalidMigrationCount=0`、`errorDetailsPresent=false`、`errorCodes=[]`。
- 25 条为 **24 条 SQL（版本 200–223）及 1 条原 SCHEMA 标记**。保留实际安装顺序：V222 rank 22、V223 rank 23、V221 rank 24，没有按版本重新编排历史。
- `openedConnections=2`、`verifiedBeforeConnections=2`、`verifiedAfterConnections=2`、`closedConnections=2`；`connectionGuardsPassed=true`。
- 实际目标仅 `127.0.0.1:25432/agent_platform_engine_test`、schema `engine`、role/数据库/schema/history owner 均为 `agent_v12`；PG PID 15204 与创建时间前后相同，其他 client 为 0。
- 前后记录均为只读 `on`、`statement_timeout=5s`；`context_pack` 为 `pg_catalog.jsonb`、可空、无默认。未读取业务列值。

独立核对了以下原件的前后**完整文件字节 SHA 相同**，并将 source 列表与 approved manifest、真实 resource Green 的 source-after 清单逐路径核对：

| 前后原件 | 同一 SHA-256 |
| --- | --- |
| `source-before.json` / `source-after.json`（306） | `1CA4A1FF19A5A8E6141C0D92BF992354AA26D915F8BADA8FFE9BE4EC2F42E337` |
| `installed-before.json` / `installed-after.json` | `11236879CBFB1DB4FF05B5FF4986A454AC55F3C9F1A04B5B8A36657B3784CCAE` |
| `before-history.json` / `after-history.json`（含列结构） | `5565B3E58007F2502A28477ECC7DDDD764888E413328CAD7507D5C74BCCDF49F` |
| `before-services.json` / `after-services.json` | `FB7133F520B1A2E0800C4A013A4A77F195170CA550F8581F7D5BD39BA362C5A8` |
| `resources-before.json` / `resources-after.json` | `4A86DE0F2DE762D505535EB4850EB89743ED2B9A6784E3893026CE8B94384373` |

另以纯 ZIP/文件读取核实：真实 Green XML 的 `java.class.path` 为 135 项，所选 94 个 runtime library 在其中各出现一次，其磁盘 SHA/长度及正式 engine JAR 内嵌对应 library 的解压字节全部匹配；JAR 中精确 24 个 SQL 与 manifest、源码及 target 逐项 SHA 相同。实际 history 的 24 个 script/checksum 逐版本与固定 catalog 相同。V222 保持 286 字节、SHA `419C753C652B326DBD59DE40409F080A16F26B98787A7F8CF870B6ECF2859A90`、已执行 checksum `701700386`。

engine JAR 保持 `3D2BD7B517A5115725CE9ACE62A10CC7A2AE2816458AFAEFAA909B050B2D56C7`。Green 来源 receipt 保持 `F4D04843CB224F86F1CB5CE7E093E9BD23F8E02C9F3917A025F2355A63770025`，其九项原件 pin 均复核一致。

### 2.1 代码边界及秘密处理

`run-history-validate-v2.ps1:61–94` 仅使用 `psql -X -w` 查询数据库身份、history 与列元数据；连接前设置只读和 5 秒 statement timeout，前后与既有批准 proof 精确核对。没有 create/drop/repair/migrate、业务扫描或其他数据库探测。

`V222HistoryValidate.java:84–180` 对两个实际连接的取得与关闭分别核当前库、角色、schema、JDBC read-only、事务/default read-only 和 5 秒超时；拒绝切换到可写、原始 connection unwrap 和凭据覆盖。最终关闭计数来自真实连接代理，并非预填常量。

`V222HistoryValidate.java:200–254` 在库代码工作前关闭通常 stdout/stderr 输出，仅保留固定安全 JSON；Flyway 禁止建 schema、禁 clean、禁止默认和显式 callback，仅调用 `validateWithResult()`。此结论不包含新库 migrate，也不代表 adjacent/full 测试通过。

runner `:190–228,250–287` 将密码经内存 stdin 协议传入校验子进程，不置于命令行；移除子进程继承的两项密码环境变量。编译不接收密码，其两份私密 raw 日志实际均 0 字节；PG raw 日志亦为 0 字节。校验 stdout 仅在内存中严格解析固定字段 JSON，实际 424 字节；stderr 为 0，校验原始流不落盘，异常只记安全码。`validator-result.json` SHA 为 `3917C989D2E90D607125F25D9D9AFFA96F99ECA8663298C032C3F377FC5A285A`。

本复核没有读取环境秘密进行全盘秘密扫描；“没有秘密日志”的接受范围是上述实际输出、落盘策略与精确命令/结果字段，不延伸为全仓扫描结论。

### 2.2 旧 ACL 失败保留；v2 没有放松权限

`.local/ex27-v222-recovery/v222-history-validate-20261029-01/` 仍保留原 `collector-error.json` 与 `collector-exit.json`：`PRIVATE_DIRECTORY` line 137、collector 2，compile/validate native 均为 null。它是采集前置失败，不能写成 Flyway 红测或通过。

独立逆向替换核实，v2 对旧 runner `B9EEDBBD25FA72B81CB543F9B36E221E7E33F6E310C35E16B3D9C6DBC44DF275` **仅两处文本差异**：manifest 文件名，以及 `[IO.Directory]::SetAccessControl($run,$acl)` 改为 `Set-Acl -LiteralPath $run -AclObject $acl`。同一个新 ACL 对象、当前 SID owner、关闭继承、唯一当前 SID FullControl Allow 及严格回读条件均未改。manifest 旧字段仅 runner/pins 对应变更，新增 v2 provenance 保留失败来源；Java、306 源、94 libs、预算与校验条件不变。

主线程提供的实际诊断 tool chunk 为 `9422fe`。本复核实读其私密脚本 `acl-api-probe-20261029-01.ps1`（SHA `179EBC5CE64D8FB90D1AA99698CE7B2C05095F2AAA2D194A14CE1E39BC87791A`），确认两分支构造完全相同 ACL，仅调用 API 不同；没有重新执行该脚本或设置 ACL。另只读 Get-Acl 回查实际目录：dotnet 分支目录仍继承、8 条规则；cmdlet 分支及 history run 02 均关闭继承、仅当前 SID 的 1 条 Allow、owner 为当前 SID。与旧失败、新成功事实一致，未通过增加其他主体权限绕过失败。

## 3. run 02 原失败原因：23 项有界表示证明通过，整体仍失败

实际派生目录：`.local/ex27-v222-recovery/v222-full-933-20261029-02-reason-proof/`。其中原始 `deriver.ps1` 和 `approved-manifest.json` 与上表 SHA 一致；`bounded-representation-proof.json` SHA 为 `9D8B90FF52A87104D63E83E0F998A9B21AE02DC99127A7EA63DBB5A4B9DC4558`。

基线仅 EX04 `full-engine-20261029-1124`，实际仅 `v222-full-933-20261029-02`。本复核没有执行派生器，而是另写一次性内存中的纯文件核对逻辑，直接读取两组原始 XML，并执行以下独立比较：

| 独立重算项 | 结果 |
| --- | --- |
| manifest 输入 pin / 两组原 XML 文件与长度 SHA | 27 / 186 全部匹配；各组精确 93 XML |
| CSV 唯一记录 | 原 932、现 933；仅新增 V222 资源测试，原身份无缺失 |
| 原非通过记录 | 115；类型、状态、cause_types、首项目栈帧保持 |
| message/detail 原文完全相同 | 92 个用例 |
| 有界改变 | 23 个用例：16 Spring 对象身份、7 测试 connection identity；共 46 个 raw 字段 |
| 捕获值 | 438 个；其中 306 次值实际改变，132 次值原样 |
| 捕获之间及末尾的剩余文本 | 484 段逐字相同；没有删栈、SQL、数字、超时或其他字符 |
| 同一 case 的 message/detail 联合身份等价关系 | 1,148 个同前缀成对比较均保留，正反向对应唯一 |
| 原 class events / 原未执行 source methods | 2 / 14，身份及未执行范围保持 |

核对没有调用作者的三个正规化函数：按报告的 UTF-16 offset 直接取原 XML 解码字符串的捕获片段，核其字面值、SHA、严格 hex 或有符号十进制形状、固定前缀顺序、数量和不重叠位置，再逐段比较剩余字符。XML detail 仅沿原 reader 的 CRLF→LF 读取约定；没有额外换行、空白、UUID、十六进制或数字清理。

两个代表性精确 pair（全量 pair 留在私密 proof）：

- `EngineContextPostgresTest.actualMvcTimeoutLeavesRoomForTheFiftyFiveSecondObservationWindow` 的 `WebMergedContextConfiguration@`：`1970351a` → `99f0e04`。前缀不变，message/detail 联合身份关系不变。
- `RunObservationMetadataHttpPostgresTest.actualHttpProjectsSevenFieldsFromReadOnlyRepeatableReadWithoutPayload` 的 `connection=`：`29487705` → `1090616023`。源码 `RunObservationMetadataHttpPostgresTest.java:136` 实际使用 `System.identityHashCode(connection)`，文件 SHA `5E53C2AA895C0A7E2790418AD75B2044717DE93FD02716B6F1CB92B3ABA9ADF9`；不是业务 SQL 值或持久连接 ID 的任意替换。

旧未执行候选仍独立归档于 `.local/ex27-v222-recovery/unexecuted-full-reasons-f4d-b3b-20261029-1dd7d650/`：旧派生器 `F4D2A9BFEFDAC7624D42ACDEC90219371754D8B48C2F7141A191EC314F4BBED9`、旧 manifest `B3B9D308963D0F3875F340B55AD6FD32FB8BD3637181B058265A71821EA25D0E`。它们不是本次实际运行版本，不追认为执行证据。

### 3.1 不得推导出的“通过”结论

原 Full run 02 仍为 **93 XML / 933 = 817P + 36F + 80E + 0S**。原 native 0 是使用既定 failure-ignore 的采集结果；`methodIdentityAndStatusGate=false`、`strictOriginalReasonGate=false`、`collectorGatePassed=false`、`allTestsGreen=false` 和 `wholeCardClosed=false` 全部保留。

唯一原 P→F 仍为：

`PortalRunEventsLifecycleTest.timeoutOnCompleteAndShutdownKeepTwoActualGetterOwnersUntilEachReturns`

该 SSE 新失败没有进入 23 项 allowlist，没有被原失败原因表示证明吞掉。派生器 native 0 只说明固定这次 23 项表示证明完成；`adjustedOriginalReasonComparisonPassed=true` 不能升级为方法/整体门通过，实际 `adjustedMethodAndReasonEvidenceGatePassed=false` 仍正确。

本证明只针对被冻结的当前 raw hash 对，不能作为未来 run 的全局对象身份正规化器；未来变动必须重新固定精确输入并审阅。没有修改任何原 XML、CSV、原 gate 或原异常文本。

## 4. 源码时间顺序及下一步边界

历史 run 02 的 306 文件与原 resource Green 逐路径一致，其中 `PortalRunEventsLifecycleTest.java` 为 **`3FFC0280107643AD942557F1EECF5A6DF5DA760EE9BB5979603DEAF973F3FBE8`**。

本次只读审阅时当前工作区该测试已为 **`021629A09E978A177F98764AF28BA7B96569F707F27DAE4DC85994657F1747F8`**。这是后续 SSE 诊断/修正阶段的源码，未出现在旧历史 Green 或旧 Full run 02 快照中；不得把它倒填为旧运行来源。本报告不独审或批准该测试修正，也不声称它已通过回归。

可接受的收口仅是：历史库 V222 只读 validate 已实际通过；23 项原失败的表示差异具有精确文件证明。后续仍须依照主线程独审、冻结和实际回归处理 SSE 新失败；原 115 非通过、14 未执行及整卡残项保持，不以此报告提前解除。
