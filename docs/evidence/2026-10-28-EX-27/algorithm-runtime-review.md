# EX-27 SDK 中断前提及安全覆盖：独立运行终审

- 评审对象：EX-27 的 `AlgorithmOffloadInterruptionTest.java` 前提与安全覆盖子步骤。保留原 PUT/GET/GET_BODY 三身份及有限 Source 身份，新增默认 raw GET_BODY handoff；不验收整张 EX-27、EX-03 或算法能力整体。
- 作者：ROOT 调度的 SDK 测试作者及实际采集代理；审阅者：独立代理 `/root/ex00_collector_review`，未编写候选、采集器或派生结果。此前 [43EC静审](algorithm-fix-static-review.md) SHA256 `dce3da6c24f60ecdf73c44c304a860becada8d15a6f1359860a4d0737eb1c408`、[历史技术门](algorithm-history-technical-review.md) SHA256 `e6adc415cbdefa2729a8e405cc0f618a73c20e3e4e3d55078e52e373f2775f58` 均未变。
- 候选：实际 HEAD `83726d50a6cce116251c03c5c193080d8ed5aad2`；Test SHA256 `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D`；生产 ArtifactStore SHA256 `9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D` 未改。
- 证据：[原五报告定向及被新outbox F阻断的Full](runs/algorithm-fix-20261029-032753-387/result.md)、[本次共享Full结果](runs/outbox-fix-20261029-085643-361/result.md)、[组合门](runs/outbox-fix-20261029-085643-361/execution-gates.json)、对应原 XML、command/native-exit、149源清单及逐方法/原因对照。两份运行报告共享一次最终 Full，不按两子步骤重复计运行次数。
- 已核查：直接读原五报告定向 XML、本次全部 Full XML、三套旧模块 XML，并独立复算身份、状态及116旧原因；只输出严格白名单的四条网络结果，不导出 XML properties 或完整 system-out。
- 未覆盖：审阅者未运行任何脚本/Maven/Java/PG/SQL/HTTP，也未读 `.env`。默认运行没有诊断栈快照，不伪称有额外实时阶段记录；未恢复历史旧失败的实时 stage/Call/executor 分项。提交范围/最终串联树另核。

| 问题ID/级别 | 场景 | 问题与影响 | 处理/理由 | 复核状态 |
| --- | --- | --- | --- | --- |
| 原真实 F，历史保留 | 原 EX-03 Full673 的 GET_BODY[3] 首次 quiescent | 原 peer 闩锁不足以单独证明客户端已处于正文读取且交接已结束 | 正常向量补实际客户端正文链与交接前提；同时保留无事前观察的默认原 handoff，不能删除合法未确认分支安全义务 | 43EC静审已核；原 F、PG历史取证与诊断原件不改写 |
| 前次模块阻断，已另修 | SDK43EC Full674 的新增 outbox F | SDK五项P不能豁免模块新失败 | 独立607B逐例隔离后，同栈唯一Full674再次逐身份核查 | 本轮原outbox F→P；本报告不冒领outbox代码验收 |

原 SDK 定向（`algorithm-fix-20261029-032753-387/class`）实际为 **1类5P/0F/0E/0S、native0、无failure.ignore**，命令区间2026-10-29 03:28:17.1581595–03:28:28.6139005（+08:00）。直接解析原 XML 为5个完整身份、4条默认网络结果、0诊断，其 XML 与旧清单SHA相符。随后该次 Full674=557P/7F/106E/4S 被真实 outbox 新 F 阻断；原 collector1/方法门false和失败现场仍保留，未以五项P提前签收。

本次 outbox 定向28P后，唯一共享 Full674 于08:58:18.0024223–09:01:22.7141050完成，结果 **70类674＝558P/6F/106E/4旧S**。SDK五个确切身份均P：原三个带参数序号的身份、原 `unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit`、新增 `originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety`；没有改名替换、缺失或重复。

从本次 SDK XML 独立提取的白名单事实如下，且与 network-outcomes.json 一致：

| 默认网络向量 | join返时ms | interrupted/inputClosed | 首次quiescent | explicitUnconfirmed |
| --- | --- | --- | --- | --- |
| raw GET_BODY handoff | 23 | true/1 | true | false |
| 正常PUT | 31 | true/1 | true | false |
| 正常GET | 21 | true/1 | true | false |
| 正常GET_BODY | 22 | true/1 | true | false |

四记录分别位于对应通过用例的输出下，0诊断行，未从其他用例或旧运行拼接。当前 raw handoff 走确认分支，**不能声称本次它命中了未确认**；确定性未确认覆盖来自仍默认运行且P的有限 Source 用例。Source为5报告中独立身份，没有第五条网络结果是其原零网络前提，不是缺结果。

在已静审且SHA相同的43EC中，正常 GET_BODY PASS 必须先在原总1秒准备预算内命中同一worker的 readJson、Exchange ResponseBodySource、Okio、socket实际读取链，并满足阶段/dispatcher/executor交接状态；随后保留原严格首次quiescent断言。raw handoff仍在原peer闩锁后即interrupt，即使打开诊断也不做事前取栈；本次实际上未开诊断。正常P支持修正后的前提，不能反推原失败当时也满足它。

网络向量PASS还要求：首次q在PG/后续观察前固定，严格ISE码与PG码双向一致，无明确未确认码时必须首次q=true；目标唯一pending、无READY、输入关闭/中断、cleanup延后且早清理0；停点路由序号先于闩锁固定，释放前后无新路由；原stage/Call/拥有executor实际退出后，才有意使用同shared对象做真实PUT/GET并清理。有限Source PASS另外到达原 retained=true/q=false、明确未确认码、零网络、原500ms实际退出及retained=false等义务，再首次真实使用shared；不把其首次使用称为暖场后复用，也不对executor尾段普遍声称retained=true。

以上来自已核源码的严格断言与实际PASS结合；默认运行没有保存额外阶段快照。本审阅不把晚样本说成首次原子态，也不声称原历史调用已经回溯验证了所有后置。原历史字段仅支持强一致归因及可见字段无后续清理痕迹，不构造绝对未改审计链。

独立模块比较结果：EX-00原664身份全在，新增EX-03九项及handoff一项均P；原116非通过无状态/type/Caused-by链顺序/首项目frame变化，四旧跳过message/detail逐字一致。仅一个旧迁移失败的2UUID/2timestamp有限变化可解释，范围和原始摘要差均保留。相对原EX-03 Full673仅GET_BODY[3] F→P并新增handoff1P；相对上一SDK43EC Full674仅outbox F→P。原两次Full共140份XML仍符合各自原SHA清单。不存在用总数变化抵消新失败。

本次 Full native0 使用原failure.ignore，`allTestsPassed=false`准确保留；独立复算支持collector0及方法/原因门通过，不把ignore退出码当全绿。命令时summary的reasonComparisonPending保留，交窗后execution-gates另行收口。70源测试类与所选/实际类完全一致；本轮72份XML的SHA、大小、mtime本轮窗匹配。Target/Full149源相同且当前漂移0，SDK43EC/ArtifactStore9DEB及其余冻结源前后一致；环境/common JAR仅核准入/结束摘要，未读取秘密。

**逐卡结论：接受43EC前提修正与完整安全覆盖子步骤的定向、共享模块运行证据，可办理精确本地提交。** DoD①保留真实原F并补正确正常前提及默认恶劣向量，未放宽产品安全义务；②最终模块无新增坏类/方法或旧原因变化；③README实际命令原件与数字的明确导航已核；④独立审阅成立。⑤本地提交、回滚及台账回填尚待ROOT。整张EX-27、EX-03及存量红不由本报告关闭。

共享Full验证的是149源联合工作区；SDK单Test提交与outbox单Test提交均不是孤立的674项复现树。ROOT将它们及EX-03三源分别串联后，须核最终源集合等于本轮149清单（SHA256 `788fdb7547fac98ad55117470a46bafcf0a833f9bdf896ec011341c6e71ec4b1`）。回滚只反向本SDK Test的授权差异，保留产品、其他子步和原失败/诊断/PG历史证据。

- 作者处置：原预算、产品9DEB、默认身份及全部历史失败保留；此前静审文档未覆盖或重写。新实测解决的是当前测试前提及覆盖，不宣称旧内存现场已重建。
- 导航复核：README SHA256 `f2f5e0270f1d40ae198b30e6954957c2f61d068b800ce167a47de5b1f1c72433`；SDK整类、Target、Full三个command.json直链均指向真实原文参数/区间/native，且明确历史红仅对应当时Run/SHA。
- 审阅者复核：本次SDK XML SHA256 `0fdf4d7adcb364816882f89e301285e3df64e3c42b928881b1b322804d4bd74a`，共享result SHA256 `738e596815cd56adee89be22868ff3530895ed026573d734ebe560b11ac0c55c`；2026-10-29（Asia/Shanghai），结论限上述候选与已核证据，提交范围待独立窄核。
