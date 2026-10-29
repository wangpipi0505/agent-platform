# EX-08 第二批增量独立窄审

- 评审对象：outbox、SDK、EX-03三次本地代码提交的索引、最终149源上下文及三主入口快照；仅本次增量，不验收整张EX-08或重新审计原首批七卡。
- 作者/暂存者：ROOT；独立审阅者：`/root/ex00_collector_review`，未编写本候选或操作index。本次只读Git对象/文件及标准路径散列，未运行PowerShell采集器、Java/Maven、PG/SQL、HTTP或产品测试，未读`.env`。
- 基准HEAD：`cffbd3613273f6c8a810cc25e8cb75508709841b`，tree `a95e0f158ed3bf9c3606d7eecbb2432d7f012f29`。
- 文档候选：9文件，index tree `dcb0a240f77c2b8cdff1d0cd8c61c719349054ba`；[incremental-index-candidate.json](incremental-index-candidate.json) SHA256 `54e692391d848f6cb83e8861fcd460a9eeaef5c53f6b5b46240b6ae8cbe122ee`。此为后附本审阅前的树，尚无本次文档提交。
- 证据：[三行七维增量](incremental-20261029-sdk-outbox-ex03.md)、[逐149源上下文](shared-skill-source-context.json)、[三主入口原字节清单](main-entry-snapshot-20261029/manifest.json)以及本轮已完成的outbox/SDK运行与精确索引终审。EX-03实质验收引用其独立final/index review，本次不重复产品场景。

独立读取三次实际提交对象，父链/树/范围均与context逐字段一致：

| 提交 | 父提交 | tree | 文件数/非证据代码 |
| --- | --- | --- | --- |
| `c109171958e6a260089cad123549473d20a5c201` | `83726d50a6cce116251c03c5c193080d8ed5aad2` | `614c1ccf53020537f3f449a1b1561b3788e0d02c` | 112；仅SkillExecutionTest |
| `6eda42cb3201dea2005b5442ac5e5966c31c3300` | `c109171958e6a260089cad123549473d20a5c201` | `2fdeaf71fde011c13cabe0b777a4c5d3fbe010ea` | 250；仅AlgorithmOffloadInterruptionTest |
| `cffbd3613273f6c8a810cc25e8cb75508709841b` | `6eda42cb3201dea2005b5442ac5e5966c31c3300` | `a95e0f158ed3bf9c3606d7eecbb2432d7f012f29` | 102；仅OntologyManifestImports、SkillCatalog及OntologyManifestImportsTest |

三提交非证据代码合计恰五源，无额外生产/测试文件；它们共同恢复共享Full的源码上下文，不能把任一孤立子提交说成独立674项复现树。实际运行HEAD837与之后提交HEADcff分列，未将未来提交倒填旧红。

独立枚举当前`skill-registry/src`，149路径恰与运行清单及context相等；逐文件工作raw SHA仍等实际运行，运行清单SHA256为`788fdb7547fac98ad55117470a46bafcf0a833f9bdf896ec011341c6e71ec4b1`。逐路径执行不写对象的`git hash-object --path`标准散列，最终cff tree及当前index的149 blob均吻合；context中每个raw OID、expected LF OID及归一化布尔也相符。全部149源属性为text:auto/eol:lf/filter未指定，恰22文件因换行使raw与LF blob字节不同，SDK43EC包括在内。未对真实src设归档例外，未把Git字节SHA与运行raw SHA机械等同；无需为纯提交/归档另跑产品来掩盖差异。

本次9文件index由只读条目在内存重建，所得dcb0树与冻结标识相同。实际差异与manifest八条唯一文件加自身精确相等；工作大小/SHA/raw OID/expected stage OID均符。唯一普通文本过滤是README的CRLF→LF，其他stage与原字节一致。三条属性仅锚定本次三个仓外主入口快照，无通配或源码规则；没有任何产品文件入本次index。`git diff --cached --check`实际exit0、输出0。

三主入口当前原文、归档快照与stage blob逐字节相等，大小/SHA及版本分别符合manifest：统一台账v2.12、执行卡片v1.12、联合手册v1.12。原文确在AP/OR仓外，快照明确只作证据、不变成第四执行入口，也未宣称代码提交包含这些仓外原文。未刷新首批旧hash；README除新有限指针外，原首批正文在既有Git换行规则下保持原样，旧7卡/SDK未关闭等措辞已明确标历史阶段。

直接核台账§14.1的39个唯一EX行，实数为**8完成、2进行中、29待启动**。EX-03已完成且cff身份准确；EX-27只有三个子步完成、整卡仍进行中，EX-08也仍进行中；EX-04仍待启动，其EX-03硬依赖现在满足，不把设计准备写成实现。8整卡与3子步分开，48能力/45原子项及其余31整卡完整覆盖仍待，未以子步扩大能力完成数。

七维三行与已审范围一致：outbox28P/native0/noignore；SDK5P、4安全outcome、0诊断；EX-03定向30P及实际PG/HTTP头链与协议替身分列。共享Full只计一次674=558P/6F/106E/4旧S，failure.ignore仅采集，116旧非通过及唯一有限动态消息差保留；不冒称全模块绿。当前raw handoff确认退出与确定性Source未确认覆盖分列，不反推历史实时内存；EX-03不冒称真实OR/J4，真实联合、分页、OpenAPI及PLAT-11其它残项仍由后续卡承接。旧673/674失败原件不被增量索引改判。

| 问题ID/级别 | 范围 | 独立结论 | 状态 |
| --- | --- | --- | --- |
| 无新增阻断 | 三提交链、149 raw/LF、9文档候选、三入口快照/状态及有限七维表述 | 与冻结清单和已审运行边界一致 | 接受 |
| 未覆盖/待后续 | 产品重跑、原首批整批重审、48/45完整覆盖及其余EX能力关闭 | 本次纯增量不承担这些验收 | 明确保留 |

**结论：接受本次dcb0树/54e6 manifest的9文件纯文档候选。** ROOT可只后附本审阅，复验原9文件集合/blob/工作SHA不变后办理预计10文件文档提交；不因此关闭EX-08或EX-27。该增量无需制造功能红阶段；独立窄核成立，当前文档提交及台账最终指针仍待ROOT完成。回滚只反向本增量文档差异，保留三代码提交、原运行证据及仓外主入口历史，不整仓reset。

- 审阅者最终复核：2026-10-29（Asia/Shanghai）；本文件是本轮唯一新增，未改index、主入口、源码、旧报告或历史hash。
