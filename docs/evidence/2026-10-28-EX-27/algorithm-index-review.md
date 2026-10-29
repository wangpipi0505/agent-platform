# EX-27 SDK 精确索引独立窄核

- 对象：SDK Test43EC及其历史/诊断/运行证据的提交候选；不重做运行或历史实质审，也不验收整张EX-27或EX-03。
- 作者/暂存者：ROOT；独立审阅者：`/root/ex00_collector_review`，未编写候选或改index。本次仅新增本文件，未运行PS采集器、Java/Maven、PG/SQL、HTTP，未读`.env`。
- 基准HEAD：outbox提交`c109171958e6a260089cad123549473d20a5c201`，tree `614c1ccf53020537f3f449a1b1561b3788e0d02c`；独立核该提交恰112文件，并包含冻结的outbox索引审阅SHA `5a7efcdae07adaf7c630fb3202478d521f3a8a934b5289e0e02cedcb22e5bf45`。
- 本候选：249文件，index tree `1addfc390236eae5b305e4da12e4ac157d8ddbcf`；[algorithm-index-candidate.json](algorithm-index-candidate.json) SHA256 `a44e9aabd221c3eaa303c61d6175bd6485a03dcff66b79e244c1c8b56b0b3778`。这是追加本审阅前的树，尚无SDK提交。

直接读取index条目，在内存按Git对象格式重建tree，独立得到上述1add树。cached差异与manifest的248个唯一文件加自身精确相等，无额外/缺失；全部工作SHA/大小/raw OID/expected stage OID吻合，manifest自身stage等于原始工作字节。仅一个文件经过文本过滤，详见下文。`git diff --cached --check`实际exit0、输出0字节。

[algorithm-public-candidate-files.csv](algorithm-public-candidate-files.csv)的243条均唯一，逐条当前大小/SHA与manifest相符；其范围为一个SDK源码、五份顶层算法文档、十个明确算法目录的安全文件。manifest另加runtime-review、公共CSV及summary、属性文件及属性回执共五项，再加manifest自身=249。十目录与清单一致，没有混入outbox目录、EX-03、生产源码、其他旧未跟踪材料或raw.log/XML/真实环境文件。公共summary保留生成时的“尚未stage/待ROOT”阶段字段，当前范围以本次index manifest为准。

代码唯一为`skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java`。运行工作区raw SHA256仍是`43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D`，含894个CRLF；真实src继续适用`text:auto/eol:lf`，`filter`及`whitespace`均未指定，无源码属性例外。独立执行不写对象的标准路径散列`git hash-object --path=<真实源码路径> <真实源码路径>`，结果为stage blob OID `e59e06cf2ff7c7e479a2be9bf978b10ea28c418c`。直接读取blob恰为raw CRLF→LF，blob字节SHA256为`8661232f34cc1a4241da3aa57465cb2e2855309b25c2670f2c4ee177a132fa5c`；不把它与运行raw43EC混为同一字节SHA。

[algorithm-archive-attributes.json](algorithm-archive-attributes.json)记录的199条增量与实际属性差异精确相等。原outbox/SSE属性前缀完整保留，base属性SHA与回执一致；199条均为一个确切SDK证据文件的`-text -whitespace`，无通配、目录级、源码或全局规则。199个stage blob逐项等于各自raw字节及SHA/OID。两份冻结源快照另核：155D快照raw SHA仍`155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1`、669个CRLF；43EC候选快照raw SHA仍43EC、894个CRLF；两者stage均保持完整raw字节。真实源码走LF过滤与归档快照保raw是两种明确规则，没有重写旧原件以凑SHA。

当前149源工作字节仍逐项符合共享Full清单，漂移0；清单SHA256 `788fdb7547fac98ad55117470a46bafcf0a833f9bdf896ec011341c6e71ec4b1`。本次没有重跑72XML或历史PG分析，沿用此前独立[SDK运行终审](algorithm-runtime-review.md)及其冻结证据：SDK默认整类5P/native0/noignore；最终共享Full仅一次674=558P/6F/106E/4旧S、116原因保留、无新增回归；原两次失败Full、诊断及有限历史归因边界不被新提交改写。本次raw handoff确认分支与确定性Source未确认覆盖仍分别表述。

本SDK提交以已提交outbox为基准，但EX-03三源码仍待另行提交，因此不是孤立674项复现树。最后串联后需分别核：①运行工作区raw149仍符合实际清单；②最终Git tree的149 blob符合各真实源码路径标准过滤。不能把最终Git blob与raw149清单逐SHA机械等同，尤其不能对真实SDK源码加`-text`来消除差异。

| 问题ID/级别 | 核查范围 | 结论 | 状态 |
| --- | --- | --- | --- |
| 无新增阻断 | 249精确文件/tree/blob、199归档规则、SDK raw与LF边界 | 全部符合冻结manifest与既有源码规则 | 接受 |
| 待最终链 | SDK本地提交及EX-03三源提交后的149上下文 | 不提前声称已提交或单提交复现674 | ROOT后续核验 |

**结论：接受上述1add树/a44e manifest的249文件冻结候选。** ROOT可只后附本审阅文件，复验原249文件及manifest/blob/工作SHA不变后办理预计250文件本地提交。回滚仅反向授权SDK Test差异，保留生产9DEB、其他子步骤、预算及原失败/PG历史证据，不整仓reset。当前无新增回归不等于全模块全绿，EX-27整体与EX-03不由本次索引窄审关闭。

- 审阅者复核：2026-10-29（Asia/Shanghai）；本文件为本轮唯一新增，未改旧报告、源、属性或index。
