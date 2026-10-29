# EX-27 outbox 逐例隔离：独立运行终审

- 评审对象：EX-27 的 outbox 测试隔离子步骤。仅 `SkillExecutionTest.java` 的逐例 UUID 租户及两条新增严格断言；不验收整张 EX-27、SDK 子步骤或 EX-03。
- 作者：ROOT 调度的 outbox 候选作者及实际采集代理；审阅者：独立代理 `/root/ex00_collector_review`，未编写候选或采集器。此前静审见 [outbox-actor-static-review.md](outbox-actor-static-review.md)，其 SHA256 `9ec2ac8fb1f5adbae7c0ff2006c14e86caa2665842a0c488b35be437ac232a8e` 未变。
- 候选：实际 HEAD `83726d50a6cce116251c03c5c193080d8ed5aad2`；未提交 Test SHA256 `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8`。生产 `SkillOutbox`、`SkillExecution`、另一类 `SkillTaskResultTest` 均保持冻结身份。
- 证据：[本轮 result](runs/outbox-fix-20261029-085643-361/result.md)、[分别判门](runs/outbox-fix-20261029-085643-361/execution-gates.json)、Target/Full 各自的 command/native-exit、XML 清单、方法 CSV、149 源清单、前后指纹，以及 [原失败结果](runs/algorithm-fix-20261029-032753-387/result.md)。本次共享 Full 仅计一次。
- 已核查：直接解析本轮 72 份 XML，并与 EX-00、原 EX-03 Full、上一 SDK43EC Full 的原 XML 逐身份比较；独立核对 116 项原因与四跳过、XML SHA/大小/时间窗、源文件实际 SHA。未执行候选脚本或产品命令。
- 未覆盖：没有运行 Maven/Java/PG/SQL/HTTP，没有读取 `.env`；没有恢复旧失败所选具体 call、创建批次或历史 due/lease，也没有审计任意旧队列。当前尚无本子步骤本地提交；精确暂存及最终串联树另核。

| 问题ID/级别 | 文件/场景 | 问题与影响 | 处理/理由 | 复核状态 |
| --- | --- | --- | --- | --- |
| 原真实失败，已保留 | 上一 Full 的 `automaticOutboxRecoversTransientCallbackWithoutRepeatingBusiness` | 回调主体 actual `task-result-tester`，expected `skill-tester` | 产品按被认领事实恢复主体的路径不改；本 Test 从共用租户改为逐例 UUID，不清未知旧队列 | 原 XML、失败摘要与结果仍在；本轮 Target/Full 均 PASS |
| 文档收尾，已关闭 | README 的当前子步骤入口 | 需让命令原文与运行数字可直接追溯 | ROOT补SDK整类/Target/Full三个实际command.json直链，明确command/arguments原文数组及历史SHA边界 | 独立核三个文件存在、选择器/ignore/native与实测一致；精确索引另核 |

独立复算 Target 为 **2 类、28 身份、28P/0F/0E/0S**，其中原 `SkillExecutionTest` 25 身份和 `SkillTaskResultTest` 3 身份齐全，无新增或缺失；native0，未传 failure.ignore。实际区间为 2026-10-29 08:57:18.1091782–08:57:39.1473225（+08:00）。原主体、SERVICE、业务执行一次及自身 DELIVERED 断言均保留；同一已审 SHA 的 PASS 还要求该 UUID 租户恰有自身 call 到期可认领及精确回调 URL，不以总数替代这些后置条件。

随后唯一共享 Full 为 **70 类、674 身份＝558P/6F/106E/4 旧 S**；区间 08:58:18.0024223–09:01:22.7141050。native0 使用原 failure.ignore，仅表示命令返回；collector0 与独立复算的方法/原因门通过，`allTestsPassed=false` 保持。原 `summary.reasonComparisonPending=true` 作为命令结束时事实保留，之后的 execution-gates 单列原因核查，不倒写原汇总。

比较使用 classname、原 method（含参数序号）、displayName 与完整 suite 路径；无重复、空方法、缺类或坏 XML。当前源目录 70 个测试类与所选/实际报告类完全相等。原 EX-00 664 身份全部保留，额外 9 项 EX-03 importer 测试及 1 项 SDK handoff 全通过；无新增非通过或基线状态变化。相对上一 SDK43EC Full674，唯一状态变化就是上述 outbox 方法 F→P；没有用其他方法转绿抵消新失败。

116 项旧非通过的状态、顶层异常类型、Caused-by 类型顺序及首项目 frame 全部一致。四个 `LocalSpikeLifecycleLiveTest` 跳过身份及 message/detail 逐字相同。仅原迁移测试 `v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity` 的 message/detail 摘要不同；独立检查恰各含 2 UUID 和 2 时间戳，只在这一身份替换四个动态片段后，1371 字符 message 及完整 detail 均相等。其余 115 项未归一化；原摘要差保留。

Target/Full 的 149 源路径与 SHA 完全相等，均与当前文件相符、漂移0；较上一 SDK43EC Full，仅本 Test 由14AE→607B，其余148不变。各阶段 XML 清单的72份 SHA、大小与本次原生命令时间窗吻合。前后 HEAD、环境摘要及 common JAR 摘要与准入记录一致；本审阅仅检查这些记录，不据此声称独立读取了秘密环境。原两次 Full 的140份 XML仍与各自清单吻合，原失败未被替换。

**逐卡结论：接受607B的运行与无新增回归证据，可办理此子步骤的精确本地提交；不是全模块全绿，也不关闭整张EX-27或EX-03。** DoD①实际旧 F→当前 Target/Full P 证据成立，②本轮模块逐方法/原因门成立，③README到实际命令原件和数字的明确入口已核，④本独立审阅成立；⑤仍待ROOT的本地提交、回滚记录和台账回填。

本轮运行使用 SDK43EC、outbox607B、EX-03 三份源改动等组成的149源联合工作区。outbox单Test提交并不是独立的674项复现树；SDK和EX-03分别提交后，最终串联树必须重新核对本轮149源清单（SHA256 `788fdb7547fac98ad55117470a46bafcf0a833f9bdf896ec011341c6e71ec4b1`）。回滚仅反向本 Test 的授权差异，不 reset 联合工作区、不删除证据或旧队列。

- 作者处置：原失败及历史推断边界保留；没有改产品主体恢复、业务次数、断言、排序、fork或skip来豁免失败。
- 导航复核：README SHA256 `f2f5e0270f1d40ae198b30e6954957c2f61d068b800ce167a47de5b1f1c72433`；新增有限入口/三个原命令链接可读，旧SSE正文在Git换行规则下与HEAD相同，历史阶段单独标识。
- 审阅者复核：本轮 result SHA256 `738e596815cd56adee89be22868ff3530895ed026573d734ebe560b11ac0c55c`，execution-gates SHA256 `a37c12de611f18f61c66de26c63749a4e905599f26395382e1d4fc5409f8bb9c`；2026-10-29（Asia/Shanghai），仅限上述冻结源码及已核运行证据。
