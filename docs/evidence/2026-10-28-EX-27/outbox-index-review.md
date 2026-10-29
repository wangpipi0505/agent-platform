# EX-27 outbox 精确索引独立窄核

本文件保留初轮a60/79条属性的核验记录；末尾“归档元数据delta最终复核”给出后续9123/81条属性候选的最终结论，初轮树不冒充最终树。

- 对象：仅 outbox607B 子步骤的提交候选，不重新验收 SDK/EX-03 或运行产品命令。作者/暂存者：ROOT；独立审阅者：`/root/ex00_collector_review`，未改候选、属性或 index。
- 基准 HEAD：`83726d50a6cce116251c03c5c193080d8ed5aad2`；HEAD tree：`0c41f2524e7cc195b62df9fe96faadf48c2c2e45`。
- 已核候选：111文件，index tree `a60bdd695d06810de7abdac8a62458b9c85bc3cf`；[outbox-index-candidate.json](outbox-index-candidate.json) SHA256 `22dfdb098a7bcd29edef58c2090ee5708c23f59fcf9ab266fbf2de03b4f730ff`。这是加本审阅文件之前的树，不虚构提交。
- 依据：[运行终审](outbox-runtime-review.md)、候选110条文件记录加manifest自身、[79条属性回执](outbox-archive-attributes.json)及实际stage blob。未运行PS采集器、Java、Maven、SQL/PG或HTTP，未读`.env`；本轮仅新增本文件。

逐项读取 `git ls-files --stage`，在内存按Git目录排序和对象格式重建tree，得到上述a60bdd树，未调用write-tree或修改index。实际cached差异与manifest的110个唯一条目加自身精确相等，无额外或缺失。110条的工作字节数、SHA256、raw Blob OID及stage OID全部吻合；manifest自身stage字节也等于工作文件。`git diff --cached --check`实际exit0、输出0字节。

唯一源码差异为 `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java`，工作SHA256 `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8`；其stage与工作内容一致。没有SDK Test、EX-03三源码、生产修改、原始日志/XML、真实环境/凭据或其他旧未跟踪材料。证据范围限本子步骤三个明确Run/prepare目录、outbox专属文档/清单、局部属性及README有限导航。

属性增量恰79条，原属性行完整保留，旧属性SHA与回执一致。每条均为本子步骤一个确切证据路径的`-text -whitespace`；无通配符、目录级例外、源码或全局规则改动。79个归档blob逐个等于原始工作字节及回执SHA/OID，冻结PS BOM/CRLF和原Java快照未被归一化。真实Test仍为`text:auto`，`whitespace`未指定。仅README及根级公共候选CSV/摘要三个普通文本blob按既有Git规则CRLF→LF，manifest准确区分raw与expected OID，未假称这些blob等于raw字节。

根级 `outbox-run-public-candidate-files.csv` 及其summary是原111候选中的既有文件；91条记录的当前大小/SHA与候选范围均相符。其“尚未stage/待ROOT最终清单”等字段保留生成阶段事实，当前提交范围以本次index manifest为准。新算法公共清单不在本次index，不据文件交回时间扩大范围。

本轮Target2份及Full70份原XML继续留在本地，72份实际SHA与已stage的XML清单全部相符；两份raw.log仍被忽略且未入index，字节/SHA保持运行终审时观测：Target10444字节/`c8db80b9303df8c3284611f0d871c924aa9391bfc15d5b0b30265a2ada7b9c5b`，Full565196字节/`604055a79db880af164dcc5c9743a1e4be3ec731ce0170ac0df907da5bec75fc`。未输出原始正文或XML环境属性。

实际Full的149源清单SHA256仍为`788fdb7547fac98ad55117470a46bafcf0a833f9bdf896ec011341c6e71ec4b1`，逐项与当前149源吻合、漂移0，607B与实测一致。该清单同时包含SDK43EC及尚待另行提交的EX-03三源；本outbox单提交不是孤立674项复现树。最终三提交串联后，ROOT应再核最终149源集合/字节，不能用本次窄审提前声称该依赖链已落地。

| 问题ID/级别 | 场景 | 结论与影响 | 状态 |
| --- | --- | --- | --- |
| 无新增阻断 | 111文件/tree/blob/79属性/实测149源 | 候选范围及原字节归档符合冻结清单 | 接受 |
| 待最终串联 | 本地提交与SDK/EX-03依赖树 | 本审不冒充已提交或孤立Full复现 | ROOT后续独立核验 |

**初轮结论：上述a60树的范围及工作指纹通过窄核。** 后续归档delta的最终提交准入见下文。本子步骤回滚仅反向授权Test差异，保留证据及其他候选，不清旧队列、不整仓reset。运行结论仍为Target28P、共享Full558P/6F/106E/4旧S，无新增回归而非全模块全绿；EX-27整体/EX-03不由本次索引审关闭。

- 审阅者复核时间：2026-10-29（Asia/Shanghai）；仅覆盖上述HEAD、树及manifest。除本文件外未改旧报告、源码、属性、候选或index。

## 归档元数据delta最终复核

ROOT为使根级两份公共清单的stage字节保持原SHA，随后仅重建本子步骤`.gitattributes`、属性回执和index manifest。原三个元数据字节保留于本地`output/ex27-closeout-20261029/initial-outbox-index`，未入index；其原manifest SHA仍为22df所指值。这是归档修正，不是测试红/绿或一次新运行。

独立限域复核的新候选仍为**111文件**，HEAD不变；新index tree为`91234084145d26ab7bd061a634850ba762200bd4`，新manifest SHA256为`77e1a43c3d0412a337ba570336585576f07fbddfe7574214d8db1d379c38f7bb`，其`preIndexRevision`准确指回原a60/22df。再次用只读index记录在内存重建tree，新111精确集合、每条工作SHA/大小/raw OID/expected stage OID及manifest自身原字节全部吻合。

与原候选逐项比较，工作字节仅上述三个ROOT元数据文件改变，其他108文件原样；两树的blob差异恰这三个文件加根级公共CSV/summary，共5项，其他106个blob不变。没有源码、运行原件、README或旧报告改动。属性回执保留原79条记录及属性原前缀，新增恰两条：`/outbox-run-public-candidate-files.csv -text -whitespace`和`/outbox-run-public-candidate-summary.json -text -whitespace`。最终81条均为精确证据文件，无通配或源码例外，81个stage blob逐个等于raw工作字节/回执SHA/OID。两份派生清单自身内容未改，只取消其Git换行过滤；README仍按原全局文本规则处理。真实Test属性仍为text:auto/whitespace未指定，SHA仍607B。

本次delta不重复72XML/149源实质审；原108工作指纹及源607B未变，前述运行证据结论继续适用。`git diff --cached --check`复查exit0、输出0；审阅者未改index，也未运行采集器或产品。

后续149源的“相同”须分两层核对：运行工作区raw SHA保持本轮清单，最终Git tree各blob符合真实src的标准路径过滤。尤其SDK Test43EC的运行raw含CRLF，真实源码继续遵守全局LF过滤，不能将其raw SHA直接当作提交blob字节SHA；归档155D/43EC快照另按精确`-text`保存raw。这一差异不授权真实src属性例外，也不把孤立提交称为本轮raw树。

**最终结论：接受9123树/77e1 manifest的111文件冻结候选。** ROOT可仅后附本审阅文件并复验原111集合/blob/工作SHA不变，再办理预计112文件的本地提交。原a60阶段记录保留但不作为本次提交树；最终SDK/outbox/EX-03串联149源依赖仍待后续核实，不在此提前签收。最终复核日期2026-10-29（Asia/Shanghai）。
