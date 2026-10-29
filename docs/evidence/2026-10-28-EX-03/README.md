# EX-03 本体 manifest 导入鉴权

当前阶段：**三类定向30/30通过，真实PG治理/发布/header目标已到；后续同149源共享Full674方法/原因门通过，实质终审接受**。精确父树/索引与本卡本地提交仍待ROOT收口，当前不预先宣布整卡完成。本阶段起点HEAD `0cc967278007343bd5ab09faf9953d86cd573bf8`，实际共享运行HEAD `83726d50a6cce116251c03c5c193080d8ed5aad2`；原[阶段审](review.md)保留，最新见[final-review.md](final-review.md)。实际提交串联以[统一台账§14.7](../../../../../文档/优化方案/优化台账-统一-20261028.md)与[EX-08索引](../2026-10-29-EX-08/README.md)为准。

原完整停点保留在[三类绿测与模块逐方法比较](green-and-module-regression.md)：Target native0/30P；Full70新鲜XML、556P/7F/106E/4S，原664和新增9身份全部保留，但AlgorithmOffloadInterruptionTest的GET_BODY参数新增1F。报告采集完整不代表所有方法体执行或全绿；该轮实际窗已交还，随后按EX-27获准测试白名单处理SDK及outbox两处前提/隔离缺口，原失败不改写。

最新共享结果见[outbox-fix-20261029-085643-361](../2026-10-28-EX-27/runs/outbox-fix-20261029-085643-361/result.md)：70类674=558P/6F/106E/4旧S，原664＋导入9＋handoff1齐全，新非通过/缺失/源漂移0，原116原因及四skip保留；唯一原迁移消息限定2UUID/2timestamp解释，原摘要差异留存。默认28定向与SDK5项另列，Full只运行一次；native0的failure.ignore只采集、全绿仍false。命令原文直接见[共享Full command.json](../2026-10-28-EX-27/runs/outbox-fix-20261029-085643-361/full/command.json)及[本卡30P command.json](after/target-20261028-234843-846/command.json)，各自保留原参数数组、起止和native。该Full包含未提交的五份联合候选源，三个独立提交串联后才恢复被测149源，不把本卡孤立提交称为674可复现树。

历史首轮结果见 [首轮三类定向停点](target-first-attempt.md)。当时唯一PG闭环异常为引擎协议替身遗漏`/submitted`响应，在真实HTTP前NPE；主代理技术核实并明确授权后，仅补准确ack，见 [最小夹具delta与新SHA](ack-fixture-correction.md)。该历史29P/1E原件继续保留，不与最新30P或Full拼接。

后续已收到独立测试预审，三项测试缺口已在同一测试文件补强，见 [候选v2与预审响应](test-pre-review-response.md)。v2已在原产品上重跑真红，随后才写最小产品候选；两轮身份/时间/指纹与当前源码见 [v2真红与产品候选](implementation-candidate.md)。下列22:25数字仍只描述原v1轮，不覆盖为v2或绿测。

本卡 [三类/完整Skill采集入口](verification-ready.md) 已用于首轮及后续Target/Full；各轮数字/RunId以顶部独立记录为准，入口说明保留其准备阶段身份，新冻结测试SHA已有明确记录。来源400此前仅为源码核实，没有单独运行“只补scheme”的中间实现；不得把它写成已观察到的中间红。产品两Java及本卡Test继续冻结，等待精确索引及本地提交，不追加实际窗。

各次真红均由主代理临时授予本卡唯一 Java/Maven/PG/HTTP 窗口，只执行 `OntologyManifestImportsTest` 一轮，结束后立即交还。v1时EX-01产品JAR验证尚需冻结产品；v2在EX-01交还实际窗和全部产品窗后运行，Importer/Catalog均在v2结束后才开始修改。在上述v1/v2真红阶段尚未执行其它定向组或模块回归；后续Target30P与Full673已另记录在顶部，不将这句历史边界解释为当前未跑。

## 真红结果

运行：[red/20261028-222522-749](red/20261028-222522-749/command.json)，北京时间 22:25:24.8025650 至 22:25:35.3471986。Maven 实际退出 **1**；测试正常编译且运行 18 项，**9 passed / 7 failure / 2 error / 0 skipped**，本轮新鲜 XML 1 份。命令未用 failure.ignore。PowerShell 收集脚本正常返回不改变记录的 Maven 退出码。

```powershell
mvn.cmd -B -ntp -pl skill-registry test "-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2" "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dsurefire.timeout=900 -Dsurefire.failIfNoSpecifiedTests=true -Dtest=OntologyManifestImportsTest
```

执行环境仅加载本仓 `.env` 到当前进程，退出 finally 恢复全部加载键及 JAVA_HOME/PATH/ENGINE_PG_TEST/PGPASSWORD 原值，不输出值。未读取 OR 配置。

原 9 方法与新 9 方法以 EX-00 方法名称显式匹配分列，而非根据名字语言推断：

| 范围 | passed | failure | error | skipped |
| --- | ---: | ---: | ---: | ---: |
| 原 9 方法（原场景保留；成功方法补鉴权断言） | 8 | 1 | 0 | 0 |
| 新 9 方法 | 1 | 6 | 2 | 0 |

精确清单：[method-status.csv](red/20261028-222522-749/method-status.csv)。原方法 `正式manifest完整映射为技能契约` 停在新增的 API_KEY 断言（实际 null），此前原映射断言已执行；该失败不能叫旧基线回归失败。其余原 8 方法通过。

新 6 个断言失败分别为：`credentialImportNeedsAdminBeforeAnyDraftIsCreated`、`explicitApiKeyDeclarationSurvivesImport`、`anotherTenantCredentialKeepsScopeDeniedAndCreatesNothing`、`invalidOrUnavailableCredentialRejectsBeforeCreationWithoutExposingReference`、`explicitConflictingOrUnknownSchemeIsNeverDiscarded`、`missingOrBlankCredentialRejectsTheWholeBatchBeforeCreation`。分别观察到未拒绝原请求，或 API_KEY 字段仍为 null，均对应本卡目标。

`strictRegistrationAcceptsTheImportedSchemeAndOntologySource` 与 `postgresImportReadPublishAndExecuteKeepsOnlyApiKeyWithLegacyDisabled` 两个 error 都是生产 `PlatformException` 的 `SKILL_AUTH_SCHEME_REQUIRED` 拒绝点（原产品缺 authScheme 且 legacy 关闭）。它们不是编译失败、缺权威字段或网络/PG夹具失败；后续来源校验、发布、出站断言尚未执行，不据方法名声称已验证。新来源负例 `ontologySourceRejectsUnknownFieldsAndMalformedCoordinates` 通过，只证明原严格 OPENAPI 分支也拒绝这些输入，仍需正向闭环证明 ONTOLOGY 来源被接纳。

## PG 与源码前提

[database-precondition.json](red/20261028-222522-749/database-precondition.json) 记录核验：25432 环回监听 PID15204；可执行文件 `V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe`、实际 `show data_directory=V:/.local/pgdata` 均为本仓；`.env` SHA256 与 EX-00/EX-01 同源指纹一致。

主代理已核准的唯一专库 `agent_platform_ontology_import_test` 运行前不存在。本卡在归属核验后新建，owner=agent_v12，并写 pg_database comment：`agent-platform EX-03 2026-10-28 ontology import isolated test database`。测试只执行 skill Flyway 及随机 tenant 的 create 尝试；create 因 REQUIRED 回滚，未 drop/truncate/改共享开发库。专库和迁移保留，后续复跑先核 owner/comment，不按名字猜归属。

[source-sha256.json](red/20261028-222522-749/source-sha256.json) 保存实际运行前三个源码指纹；两个产品文件与 [红候选](red-candidate.md) 的原始指纹一致。运行后产品路径 `git diff --name-only` 仍为空；测试源静态 `git diff --check` 通过。

原始 Maven 输出与归档 XML 本地保留在本轮目录，已由本卡 `.gitignore` 排除默认提交；新鲜时间、长度及摘要见 [xml-manifest.json](red/20261028-222522-749/xml-manifest.json)。原始控制台的中文方法名有编码显示问题，方法身份和状态以当次 UTF-8 XML 提取为准。

## 剩余与边界

当前最小修复的定向三类、真实PG/header链及共享Skill模块逐方法/原因门已满足，独立实质终审接受；最终精确文件/父树、提交与台账关闭仍待。原673新增SDK F及后续674新增outbox F各自保留，不覆盖旧失败或EX-00基线为绿。完整前置追踪与测试设计见 [preflight-design.md](preflight-design.md)。

本卡测试使用合成正式 manifest、环回 HTTP 以及引擎/模型协议替身；不称真实 OR J4、真实外部模型或 EX-07 联合验收。分页/非 HTTP/投影映射留在 EX-04。最终回滚仅恢复本卡产品提交与测试，证据保留，不原地改已发布版本。
