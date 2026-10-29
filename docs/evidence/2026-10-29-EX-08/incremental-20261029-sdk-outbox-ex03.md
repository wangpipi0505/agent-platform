# EX-08 第二批增量：outbox、SDK与EX-03收口

2026-10-29。本次仅补证据索引和主入口快照，不修改代码、不运行测试。当前为8张整卡完成、EX-27和EX-08两张进行中、29张待启动；EX-27只有SSE、SDK、outbox三个子步完成，不关闭其余Y4。与[首批快照](README.md)合计8整卡＋3子步，48能力/45原子项完整映射及其余31整卡仍待，能力统计未升级。

## 本次七维覆盖

| 有限范围 | 实现 | 定向及原场景 | 模块回归 | 契约/浏览器 | 联合与未覆盖 | 提交及回滚 | 独立评审 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| EX-27 outbox子步 | 仅SkillExecutionTest逐例UUID统一24处租户、两处SQL参数化；原97断言保留并加精确due-call/URL两断言，产品主体恢复不改 | 原Full身份断言F保留；默认两类25＋3＝28P/native0/noignore/无skip，原身份齐 | 下述同一次674，新非通过/缺失/源漂移0；原方法F→P | 无接口/依赖/浏览器改动 | 实际PG；未清旧队列，不追认缺callId的具体历史行，不外推任意产品恢复场景 | `c109171958e6a260089cad123549473d20a5c201`，112文件、代码恰1Test；仅反向该Test差异，保留证据/未知队列 | [静审](../2026-10-28-EX-27/outbox-actor-static-review.md)、[运行审](../2026-10-28-EX-27/outbox-runtime-review.md)、[索引审](../2026-10-28-EX-27/outbox-index-review.md) |
| EX-27 SDK子步 | 仅AlgorithmOffloadInterruptionTest；正常正文SDK/网络读交接见证、原handoff新增默认身份、确定性Source完整后置，原预算/产品不变 | 默认整类5P；原GET_BODY F、历史PG分支及取证轮保留。当前raw handoff确认退出，不声称本轮命中未确认；Source默认严格覆盖未确认 | 与outbox/EX03共享下述674，SDK5P/4outcome/0诊断 | 无接口/SDK依赖/浏览器改动 | 不恢复旧实时内存现场；Source退出后的shared首次HTTP与正常向量暖场复用分列 | `6eda42cb3201dea2005b5442ac5e5966c31c3300`，250文件、代码恰1Test；仅反向该Test，保留产品/预算和历史档 | [历史技术门](../2026-10-28-EX-27/algorithm-history-technical-review.md)、[静审](../2026-10-28-EX-27/algorithm-fix-static-review.md)、[运行审](../2026-10-28-EX-27/algorithm-runtime-review.md)、[索引审](../2026-10-28-EX-27/algorithm-index-review.md) |
| EX-03整卡 | OntologyManifestImports/SkillCatalog与Test；显式API_KEY、凭据授权整批预检、严格正式ONTOLOGY来源；旧手工HTTP兼容 | v1/v2真红保留；三类30P，真实PG→治理→发布→再读→两次HTTP精确API_KEY且无Authorization | 与两Test子步共享674；原664＋本卡9＋handoff1全在，无新增方法/原因差异 | 无新端点；OpenAPI/生成client同步仍EX05，浏览器N/A | 引擎/模型协议替身与实际PG/HTTP头分列，不称真实OR/J4；分页投影EX04、联合EX07 | `cffbd3613273f6c8a810cc25e8cb75508709841b`，102文件、代码恰2产品Java＋1Test；仅反向三源差异，保留专库/证据 | [阶段审](../2026-10-28-EX-03/review.md)、[实质终审](../2026-10-28-EX-03/final-review.md)、[索引终审](../2026-10-28-EX-03/index-review.md) |

## 共享回归与提交上下文

实际Run为[outbox-fix-20261029-085643-361](../2026-10-28-EX-27/runs/outbox-fix-20261029-085643-361/result.md)。Target28P后一次Full70类674＝558P/6F/106E/4旧SKIP，native0使用原failure.ignore只采集；collector0、方法及原因门通过，全绿false。原116非通过状态/type/Caused-by链/首项目帧保持，四旧skip原样；唯一旧迁移消息只定位2UUID＋2timestamp后message及完整detail相同，原始摘要差保留。三处引用只算一次Full，旧673/674新增失败原件不改写。

运行时HEAD为83726d50，五源仍在联合工作区。后续提交严格串联为`83726d50 → c109171 → 6eda42c → cffbd36`，不是三个孤立674复现树。[149源上下文](shared-skill-source-context.json)逐项记录：当前工作区149原始SHA等实际运行，最终Git149路径/标准LF过滤blob相等；22个源的原始换行与LF提交字节不同，不能声称Git树逐字等于运行raw字节。三提交代码范围总计仅上述5源，index清空后才更新文档，未重跑或改源码。

实际最终代码树为`a95e0f158ed3bf9c3606d7eecbb2432d7f012f29`。真实命令原文、原生退出与阶段冻结记录见各Run的command.json/native-exit.json；[EX27入口](../2026-10-28-EX-27/README.md)直链SDK5项/Target28/Full674三份命令数组。

## 三份主入口及边界

统一台账v2.12、执行卡片v1.12、联合验收手册v1.12已回填结果、依赖及提交；执行仍只用原来的三份文件。仓外原文的[精确归档清单](main-entry-snapshot-20261029/manifest.json)保存路径/版本/SHA及本次原字节，归档副本不作为新增执行入口。原文件不在AP/OR Git仓内，不能声称产品提交包含它们；本次证据检查点保存的是其快照。

EX03完成解除EX04硬依赖，EX04仍待启动；PLAT11的真实直连/错误残项及EX05/07/22未关闭。EX27与EX08整体仍进行中，48/45逐项完整覆盖和其余31整卡待后续推进。未使用Superpower、未push/tag或外部发布。
