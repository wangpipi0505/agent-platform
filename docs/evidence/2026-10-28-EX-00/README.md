# EX-00 真实基线采集（2026-10-28）

本卡 [显式文件候选清单](candidate-files.txt) 仅供主代理逐文件评审选择；未执行 git add/commit，不含本地原始日志/XML/测试生成大JSON/缓存。

状态：原规定命令及新增前提收口均已实测结束，范围/链接核验完成，证据与显式候选可交主代理评审。原主基线仍有死锁坏类/未报告类及失败，EX-00 不放行；不把部分执行、单类复验或不同环境拼接称为完整回归通过。

- RunId：`20261028-164553-272`；[本轮目录](baseline/20261028-164553-272/)。
- AP：`ebbfd62ca1d19efc4d172804efb5ac79453ceb64`，分支 `feat/v1.2-implementation`。
- OR：`16d96989dfc7fa5b80d31d8b1cb88b21a7a0054e`，分支 `feat/or-s02-consumption-identity`。
- 未手工修改产品代码、测试、工程入口脚本、分支或迁移；交还执行窗口前两仓 tracked/staged diff 均为 0。OR 测试自动写入两份旧证据的副作用已归档并恢复原 HEAD，详见下文。
- 原有未跟踪内容保留：AP 的 `acceptance-audit-20261026/`、`batch-1-usability-20261026.md`、`output/`；OR 的 `user-audit-20261022/`。

## 执行范围与口径

当前源先 AP 后 OR 串行 `install -DskipTests`，两次退出码均为 0，只证明构建安装成功。之后 AP 七模块分别无 `-am` 运行；OR 十三模块按 BASE/P4 显式类清单分组，并在每次命令结束后立即归档本轮时间戳之后的 XML。测试采集使用 `maven.test.failure.ignore=true` 以继续采集；命令退出 0 或 BUILD SUCCESS 不代替 XML 方法级判定。

命令与退出码见 [采集器](baseline/20261028-164553-272/collect-baseline.ps1) 和本轮 `*-run.json`。源测试/迁移/历史失败见 [只读源码盘点](source-inventory.md)。方法级汇总由主代理的 [汇总器](summarize-baseline.py) 生成。未运行真实模型、真实 IAM、跨系统 HTTP 联合验收或手册 J 组；API 未变更，未生成前端 API 类型。

## 实际环境与准备记录

PowerShell 7.6.5；JDK 21.0.12.8；Maven 3.9.16；Node 24.16.0；npm 11.17.0；pnpm 11.21.0。采集器新增脚本使用 UTF-8 BOM，静态 ParseFile 无错误。环境来源为 AP 被忽略的 `.env`、OR 被忽略的 `.runtime/local-env.json`，只导入当前进程，秘密值不写入摘要。

| 仓 | PG 与专用库 | 缓存/对象存储 | 归属核验 |
| --- | --- | --- | --- |
| AP | 127.0.0.1:25432；`agent_platform_v12` 及源码明示的 `agent_platform_*_test` 测试库 | 专用 WSL AgentPlatformV12 Redis 7.2.16 :26379；MinIO :29000/:29001 | V: 映射/本仓 PG 目录、Redis 登记+认证+数据目录、MinIO 登记+可执行路径+健康 |
| OR | 127.0.0.1:15472；BASE `onto_test`，P4 `onto_test_p4` | MinIO :19100/:19101；后续独立收口启动本仓样例 HTTP :19180 | O: 工程标记、PG 目录/认证、MinIO/样例登记+可执行路径+命令行标记+健康 |

P4 七类名单与两夹具调用已核实，当前端口恰为夹具要求的 15472；没有把整仓改为 P4。仅使用各仓既有脚本停止登记并核验归属的 API/Vite，释放运行 JAR；没有批量终止进程，没有接管相邻工程。原登记 JAR 摘要见本轮 `registered-jars-before-install.json`，运行中的旧制品不作为当前源通过证据。

最初预检两 PG 认证成功后，其原监听消失，Redis/AP MinIO 原监听也随后消失；原因未证实。保留“工具子进程/作业生命周期”假设，不将其写成已证实根因。AP 既有启动脚本在原目录启动后经历 interrupted/fsync/redo，自然于 16:54:29 ready；OR 于 16:56:55 自然 ready。未重建 PG 目录、清库、放宽夹具或改应用代码。启动产生的后台 PG 继承了重定向文件句柄，相关工具会话保持存活；后续只做状态核验的 Prepare 正常完成。

## 当前发现

AP `GatewayPublishedRunProxyTest` 在 `@AfterAll stop → PublishedRunProxy.close` 出现真实 Java 级死锁。见本轮 [安全诊断摘要](baseline/20261028-164553-272/platform-gateway-deadlock.json)；原始线程转储本地保留。本次按手册设置 900 秒 fork 参数并记录实际行为，不为基线修产品或造测试红阶段。

900 秒参数在该次调用中未终止死锁。17:15:26 在核实 Surefire 进程树后，仅终止 PID 40868（已运行 991 秒，退出 -1），保留 [动作记录](baseline/20261028-164553-272/platform-gateway-fork-stop.json)、[三线程锁环](baseline/20261028-164553-272/platform-gateway-deadlock-thread-facts.json) 与本地原始转储；未处理其他 Java/PG 进程。原 Maven 因 failure.ignore 退出 0，run.json 另记 `INCOMPLETE_FORK_TERMINATED`，该事实不会被补采成功覆盖。

另在独立 RunId `20261028-173319-158` 只补采原先未到达的 23 类，23 份 XML/225 testcase；原先已报告 3 类及死锁类均未重跑。原始报告分别保留，主汇总使用 `--supplement` 合并覆盖。最终 AP 仅 `GatewayPublishedRunProxyTest` 未报告；四个类级错误不代表其各测试方法执行过。

| AP 模块 | XML/源类 | testcase | passed | failure | error | skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| admin-common | 34/34 | 363 | 352 | 10 | 1 | 0 |
| platform-gateway（含补采） | 26/27 | 254 | 238 | 8 | 8 | 0 |
| app-management | 23/23 | 177 | 158 | 0 | 19 | 0 |
| engine-runtime | 93/93 | 883 | 766 | 35 | 82 | 0 |
| skill-registry | 70/70 | 664 | 548 | 6 | 106 | 4 |
| model-gateway | 22/22 | 175 | 84 | 7 | 84 | 0 |
| monitor | 9/9 | 63 | 60 | 0 | 3 | 0 |
| 合计 | 277/278 | 2579 | 2206 | 66 | 303 | 4 |

证据见 [summary.json](baseline/20261028-164553-272/method-summary/summary.json)、[逐方法清单](baseline/20261028-164553-272/method-summary/methods.csv)、[非通过清单](baseline/20261028-164553-272/method-summary/nonpass.csv)、[类覆盖清单](baseline/20261028-164553-272/method-summary/coverage.csv)。保留 XML 的原始 case name、显式 displayName 和嵌套路径，不反推缺失的展示名。四个类级错误为 AppCopyConcurrencyTest、OwnedFlowMaterialsPostgresTest、OwnedFlowSchedulerPostgresTest、RunEventReaderTimeoutBudgetPostgresTest。

四项跳过均为 LocalSpikeLifecycleLiveTest 的 coldStartAndReadyHealth、concurrentCallsExecuteSeriallyWithCorrectResults、externalKillIsDetectedAndAutoRestarted、shutdownKillsChildrenAndReleasesPort，原因是 `AGENT_SPIKE_JDK8` 缺失。环境按手册加载 `.env`，非秘密输入 `AGENT_DEV_AUTH=true`、`ENGINE_PG_TEST=true`；`AGENT_SECURITY_MODE` 在 `.env` 和基础进程环境均缺省，显式参数也未设置其系统属性。运行期由测试自身写入的属性未据此推断。后续方法集合比较必须保留同一环境口径。

实际 Surefire 为 3.5.3。独立只读复核确认插件元数据将 `${surefire.timeout}` 绑定到 forkedProcessTimeoutInSeconds，未见 POM 覆盖；本轮不把观察到的行为归因为参数键错误。gateway 未按预期退出的具体原因仍未核准。OR Designer 组持续有测试进展并正常耗时 30:31 完成，不因整组耗时超过 900 秒推断死锁，也未修改原 900 参数。

## OR 回归与前端

OR 十三模块全部已定位：七个模块有测试源，形成八个 BASE/P4 组；六个模块没有 `*Test.java`，按源码/Surefire 规则核对后逐模块记录“不适用/无源测试”，不称测试通过。88 个源测试类均有当次 XML；496 个 testcase 为 495 passed / 1 failure / 0 error / 0 skipped。唯一失败为 `cn.ontorepo.P3ProtocolTest#controlledHttpPostPaginationIsAuthorizedBeforeResultLimit`。详见 [OR 本卡说明](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/README.md)。

| 前端 | 测试 | 类型/格式/构建 |
| --- | --- | --- |
| AP | 37 文件 / 295 项 passed | `npm run build`（含 vue-tsc 与三个 bundle）退出 0 |
| OR | 33 文件 / 269 项：267 passed、2 failed | frozen install、typecheck、build 均 0；format:check 为 1，41 个文件有既有格式问题 |

OR 两个失败均为默认 5000ms 超时：`components/concurrency.test.ts` 的“属性面板保存后，类edit仍绑定原类树修订”，以及 `features/p4/flows.test.ts` 的“真实 trace 图只画响应节点与边，未知引用不生成虚构节点”。原全组结果保留。独立 [frontend-recheck/20261028-182255-797](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/frontend-recheck/20261028-182255-797/summary.json) 使用同一配置和默认 5000ms 阈值、只选这两文件、单 worker，2 文件 / 31 项 passed；没有修改源码或阈值。该观察提示时序/负载敏感性，根因未定，不能替代原全组结果或把原失败洗绿。未执行格式写入或 API 类型生成。

随后按同一配置、默认5000ms、单worker、去掉文件过滤，独立 [frontend-recheck/20261028-185900-493](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/frontend-recheck/20261028-185900-493/summary.json) 完整跑33文件/269项，全 passed（48.21秒）。命令为 web 目录下 `pnpm.cmd test --maxWorkers=1 --minWorkers=1 --no-file-parallelism`。这提供明确的单worker全组对照环境，仍不覆盖原全组2个timeout记录；既有41个格式问题未改。

## 独立环境前提重验与净变更

原主基线保持不变。主代理核准后，在所有原定命令结束后的空闲窗口分别确认以下库原先不存在，核验本仓 25432 与数据目录后，仅创建新的空专用测试库；没有清理未知已有库，没有编辑真实 `.env`、测试源或工程脚本。

| 独立重验 | 环境动作 | 实际结果 |
| --- | --- | --- |
| [AppCopy 20261028-181751-069](environment-recheck/20261028-181751-069/summary.json) | 新建 `agent_platform_app_copy_test` | 1 项 passed，Maven 退出 0 |
| [OwnedFlow 20261028-181800-266](environment-recheck/20261028-181800-266/summary.json) | 新建 `agent_platform_iam_owned_flow_v12`；确认是本次创建的新空库后才允许会 truncate 的两类运行 | 25 项：24 passed / 1 failure，Maven 退出 1；Scheduler 10 项全 passed |

OwnedFlow 非通过方法为 `OwnedFlowMaterialsPostgresTest#lastNodePostCommitProofExpiryLeavesGrantUsableAndAggregateOnlyNewCurrentNeverRerunsNode`，在计数断言先失败，478行之后的断言未执行，不能根据方法名声称后续授予/聚合/不重跑行为已获本轮证明。两个重验目录均有 HEAD、命令/退出码、建库前提事实、原 XML 和方法清单，显式 `mergeIntoPrimaryBaseline=false`；不并入主汇总或 gateway 补采。新专用库验证后保留测试数据，不自动删库。这证明环境前提处置后的真实结果，不声称产品代码修复。原 AppCopy/两 OwnedFlow 类级错误仍在主基线。

进一步在独立 [baseline/20261028-184530-895](baseline/20261028-184530-895/module-scope.json) 按原HEAD、相关源码净差异为空、同 `.env`/JVM/argLine、无-am、failure.ignore，对 app-management 与 engine-runtime 两个完整模块复采；未重跑其余五模块。唯一声明的AP前提变化是上述两个本次新建、归属可追的专用库。文件指纹/mtime见 [环境来源指纹](baseline/20261028-164553-272/environment-source-fingerprint.json)，不含配置内容。

| 新独立模块范围 | XML/源类 | testcase | passed | failure | error | skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| app-management | 23/23 | 177 | 159 | 0 | 18 | 0 |
| engine-runtime | 93/93 | 906 | 791 | 35 | 80 | 0 |
| 仅这两个模块合计 | 116/116 | 1083 | 950 | 35 | 98 | 0 |

方法集合差见 [precondition-method-diff.json](baseline/20261028-184530-895/precondition-method-diff.json)。engine旧/新failure均为35，但方法发生替换：新观察到上述OwnedFlowMaterials失败；旧 `PortalRunEventsLifecycleTest#timeoutOnCompleteAndShutdownKeepTwoActualGetterOwnersUntilEachReturns` 本次不再非通过；另两个OwnedFlow类级错误随前提补齐消失。app原AppCopy类级错误消失，其余18个非通过方法保留。不能以失败数相同声称无新增失败，也不能把无代码改动的一次通过声称稳定修复。独立 [summary.json](baseline/20261028-184530-895/method-summary/summary.json) / [methods.csv](baseline/20261028-184530-895/method-summary/methods.csv) / [nonpass.csv](baseline/20261028-184530-895/method-summary/nonpass.csv) / [coverage.csv](baseline/20261028-184530-895/method-summary/coverage.csv) 已按明确两模块范围汇总：not_reported=0、collection_complete=true只表示报告采集完整，测试仍不通过；还剩RunEventReaderTimeoutBudgetPostgresTest一个类级错误，不能声称其方法体已执行。旧2579总数、原类级错误和单类重验均不覆盖；不与其余五模块拼成一轮新的全量结果。

OR唯一失败原先停在connectivity，分页断言尚未执行。已只读核对本仓Start/Stop脚本与README、确认19180无监听且旧登记PID已不存在，然后用现有Start-Sample-Http启动样例（PID56756，/health200，keeper27955保留）。独立 [environment-recheck/20261028-184211-773](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/environment-recheck/20261028-184211-773/summary.json) 只跑原精确P3方法，1项passed/命令0，原496中1failure仍保留。全OR Java源19180仅两处：P3Protocol实际分页用例需要服务；P3Mapping原始查询拒绝用例预期400、在管理边界拒绝而不发网络请求。没有重跑整OR Designer、修改源/配置或接管外部服务。归因见 [独立环境重验](environment-recheck-failure-attribution.md) 与 [OR原失败](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/or-baseline-failure-attribution.md)。

OR 的 P2SupplyTest:409/707 会自动改写旧 `c4-openapi.json` / `c4-lucene-retrieval.json`。本轮发现后已将生成结果只归档到 OR 本次 `generated-by-tests/` 并记录 hash，再仅恢复这两个初始干净、确认由本轮测试生成的文件到原 HEAD；详见 [恢复记录](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/test-side-effect-restoration.json)。没有恢复其他协作者文件。两仓 [AP 净变更核验](baseline/20261028-164553-272/final-source-verification.json) / [OR 净变更核验](../../../../ontorepo/docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/final-source-verification.json) 记录交还窗口前 tracked/staged diff 均 0、原 HEAD/分支保持；未 add/commit/push。

## 证据与提交候选

主基线归档 hash 见各仓 method-summary/archived-xml-manifest.json（AP 原主+补采277XML、OR原主88XML），新两模块独立范围另有116XML。另有根目录 [xml-manifest.csv](xml-manifest.csv) 涵盖各阶段：AP共396XML=277原主/补+116独立模块复采+3独立环境重验，OR共89XML=88原主+1独立P3重验；这不表示把不同阶段合并到主基线。逐项安全归因见 [主轮首批](baseline-failure-attribution.md)、[主轮其余](baseline-failure-attribution-rest.md)、[gateway补采](gateway-supplement-failure-attribution.md)、[关闭死锁独立分析](gateway-close-deadlock-analysis.md)；未证实根因按假设或待核实保留。

汇总器的明确 `--module` 范围经 [22项合成检查](summarizer-checks.json) 覆盖；新旧主汇总的原字段 [generation-replay.json](baseline/20261028-164553-272/method-summary/generation-replay.json) 已重演相等，原summary没有覆写。新两模块 summary 的 `scope=SELECTED_MODULES`，不称全reactor。五个本次新增PowerShell采集/重验脚本均UTF-8 BOM，并由系统Windows PowerShell5.1静态ParseFile确认0语法错误；实际执行环境为7.6.5，见 [检查记录](baseline/20261028-164553-272/collector-parse-check.json)。

原始 `*.raw.txt` 和归档 XML 仅在本轮目录本地保留，已由本卡 `.gitignore` 排除默认跟踪。最终提交候选仅含 README、源码盘点、采集/汇总/检查脚本、无秘密的命令元数据、环境定位、方法/覆盖/失败清单及其检查摘要。原始日志/XML不得直接批量加入提交。

[敏感信息检查器](baseline/20261028-164553-272/scan-sensitive-evidence.py) 在结束后按当前配置秘密精确值及 JWT 模式扫描，只输出路径、键名和计数；这不证明排除了所有可能敏感值。执行人：EX-00 基线子代理；采集器只读复核：EX-00 采集器审阅子代理；方法汇总与最终协调：主代理。没有虚构人工签字。本轮没有产品/入口变更可回滚；证据保留、不回滚。新专用测试库与既有环境/数据均保留，后续清理需单独核验归属与授权。

最终候选逐文件指纹见 [candidate-manifest.json](candidate-manifest.json)，独立审阅与验收结论见 [review.md](review.md)；基线失败保留，不以采集完成宣称产品全绿。
