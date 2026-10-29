# EX04采集顺序与当前红skill入口

2026-10-29，只准备、不实际运行。只写本collector目录；未读.env、连接PG/HTTP、运行Java/Maven、改源码/index或提交，不用Superpower。当前准备基点AP HEAD为`d7880a66b345f15aecf37133a702df769e591fa5`；三主入口和独立mapping/preflight review为规格依据，历史准备稿的EX03未关闭说明不再作为当前阻断。

## 先交付的红skill安全入口

`run-red-skill.ps1`复用EX03红测的原默认Maven参数、EX27的冻结/原生exit先落盘/UTF8 XmlDocument.Load/唯一报告身份逻辑。**没有照搬EX03缺库CREATE/COMMENT分支**：只接纳已经存在且owner/原EX03 marker准确的ontology_import专库，没有DDL/清理或自动补标。原始日志/XML留本地，collector窄.gitignore已经限定相应路径。

实际入口目前缺作者/ROOT冻结输入，因此不可直接启动。`red-skill.freeze.template.json`保留false审批和PENDING输入；这些门在任何.env读取、PG或Java动作之前检查。模板里的env/JAR摘要仅抄既有已准EX27元数据，本准备没有读取环境文件。

ROOT在测试仅新增/修改、生产仍旧基线时提供：

1. 全部`skill-registry/src`当前路径/原始SHA JSON，数组行`path,sha256`，含新Projection测试；它与实际文件集合须完全相等，不能只冻结几个目标文件。
2. 两类全部精确报告身份CSV：`classname,method,display_name,suite_path`。原Importer身份来自最近共享Full的具名记录，新类来自已冻结作者清单；参数展开身份须明确，不猜报告总数。
3. 精确红向量CSV：同四身份列，加`status`（failure/error）、`exception_type`。每一行必须属于完整身份清单；其余报告都要求PASS。先由ROOT/独审确认是投影丢字段/原OFFSET缺提示所对应行为，而非编译或夹具故障。
4. 将模板复制为独立实际freeze文件，填上三输入路径/各文件SHA、测试独审身份及ROOT当次独占窗口授权；三个生产pin保持review记录的旧Importer/SkillRules/Catalog，HEAD及runtime再次冻结后才改审批true。该文件不是用户权限替代品，仍须ROOT明确授窗。

预定调用（本准备未执行）：

```powershell
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile `
  -File 'docs/evidence/2026-10-28-EX-04/collector/run-red-skill.ps1' `
  -FreezePath '<ROOT已核准的red-skill-freeze.json>' -RunId '<唯一新run-id>'
```

固定选择器为`OntologyManifestImportsTest,OntologyProjectionImportTest`，无-am/ignore/skip/诊断/order/fork覆盖。独立进程仅在实际获准阶段加载AP.env并finally恢复。PG预检是5秒超时只读SELECT；核环回25432/PID15204/精确程序与数据目录、既有专库owner/marker。失败停止，绝不创建/reset/清理未知数据。

原生返回即在finally持久native-exit，然后结束HEAD/env/JAR和全源码身份，再归档新鲜XML。红门要求两类完整精确身份、无空method/skip、所有声明红的状态/异常类型匹配、其余PASS且native非0；collector0是“预先声明的红向量采集成功”，不是native0或绿。编译/类夹具故障、旧XML、缺报告、未声明新红或生产漂移都不能算目标红，保留并交ROOT审阅。新RunId禁止复用，错误仅固定phase/reason/InvocationInfo.ScriptLineNumber，不打印异常消息/凭据/原日志。

## 红engine安全入口与作者输入边界

`run-red-engine.ps1`直接复用上述红skill入口的冻结/原生命令/归档/精确红向量机制，仅切到engine模块和`ToolOutputPolicyTest,RunToolProfileTest`两个既有类，旧产品pin为ToolOutputPolicy94CF。两类目前分别为纯策略与使用RuntimeClients依赖桩调用实际prepareSnapshot的向量，没有Jdbc/业务HTTP连接；因此这个定向collector没有PG或业务HTTP调用，不为纯函数红测顺便访问完整engine的专库。模板强制`pureTwoClassNoPg`及实际源集合冻结。完整engine的专库准入仍是另一个原EX36范围，不能据这两个纯向量免除。

调用形状与skill相同，脚本换为`run-red-engine.ps1`、实际freeze换为独立red-engine文件。`red-engine.freeze.template.json`同样false审批、精确方法/红向量SHA待作者；不能执行模板或拿旧完整907填定向预期。作者目前预报47＝22＋25，最终仍由冻结的完整展开身份CSV决定。

ROOT独占`collector/inputs/red-skill-candidate/`四份输入和`freeze-candidate.json`的填写，本准备不写这个目录。ROOT已通知skill作者候选为原Importer18＋新Projection15＝33，预期18P/15个AssertionFailedError红（新类14＋原完整映射1）；完整skill150源码/71类、后身份689＝旧674＋新增15。这些是作者当前冻结说明，非已经运行的结果；脚本仍从输入集合计算，不硬编码33/689。引擎新方法展开/红向量由作者另交，不推断后完整engine总数。

## 全卡唯一实际窗口的串行顺序

| 顺序 | 选择范围 | 运行/比较要求 |
|---|---|---|
| R1 | skill原Importer＋新Projection两类 | 测试冻结、三生产旧pin保持，先留下真实红；上面入口优先待审 |
| R2 | engine ToolOutputPolicyTest＋RunToolProfileTest | 两Test由engine作者冻结，ToolOutputPolicy旧pin94CF保持；默认无ignore红，缺OFFSET提示的精确红向量按作者身份；不把启动/编译错误算红 |
| I | 四生产及四测试白名单实施/独审 | ROOT负责skill三生产和两Test，engine作者负责既有两Test；按卡的真实PG读回脱敏契约与SHA传递给engine，不跨业务JAR；未冻结前不推断新方法数 |
| G1 | 同skill两类 | 新源冻结后默认无ignore，全身份PASS、无skip；PG持久化/保存省略/发布/摘要/真实头与混合批零create/零新增draft分别到达 |
| G2 | 同engine两类 | 新源冻结，默认无ignore全绿；真实prepareSnapshot保字段，OFFSET提示准确且原PAGE/小响应保持；协议桩边界明确 |
| O | OR onto-contract ToolBindingExposureTest | OR产品只读、同本仓OR受准环境与JDK/本仓m2；原9身份无ignore全绿。不能把生成/formalize透传称为真实OR业务分页/J4 |
| F1 | 完整skill | 继承EX27成熟Full入口与默认fork/order；旧ignore仅收集，按最新674基线逐身份/原因比较，新增测试身份依作者冻结清单计算 |
| F2 | 完整engine | 继承EX36默认Full与最终类级事件分析口径；旧ignore仅收集，按最新907记录基线逐身份/原因/未执行比较 |

每个红或绿阶段原生/collector/采集/差异门/全绿分别保存。实际只由ROOT统一授唯一窗口并指定顺序；本collector准备不占窗口，不能隐含并行跑两个模块。任何未声明新增F/E/skip/缺失、bad class、未执行或源码/环境漂移都保留停止，不循环采样/自动修源码/自动恢复keepers。

## 固定基线与动态后总数

skill基线为`EX27/runs/outbox-fix-20261029-085643-361/full/`：70类、674精确身份、558P/6F/106E/4原SKIP；116原非通过逐项保存type/caused-by/项目帧/原始消息摘要。SDK五身份与原四安全outcome在完整skill仍需齐，0诊断；本卡不改SDK/Outbox测试。

engine基线为`EX36/runs/after-module-20261028-230322-842/`的最终method-summary/summary与comparison，而非初版或中间report-collection摘要：93XML/907记录，其中905具名与两空method类级事件（ModelInputMaterialHttpTest FAIL、RunEventReaderTimeoutBudgetPostgresTest ERROR）；两类14个源Test没有具名执行。必须保留两事件及14未执行，不得把reports_collected=true升级成all_source_methods_executed。原115非通过身份/状态/原因保留，新增差异0；相对门通过不意味着全模块全绿。

674/907仅是**前基线**，不得写死为本卡绿后总数。后预期身份为原完整身份集合＋作者冻结新增身份，删除/重命名/重复也须显式拒绝。新增具名方法不能抵销旧方法丢失，类事件的FAIL减少也不能抵销新的具名F。原因比较先保原始hash；只有精确定位的一项动态UUID/时间差异可另存有界解释，不泛化归一化。

完整skill复用此前已准原两迁移专库及其它本仓测试库范围，完整engine专库沿EX36原精确白名单/owner；实际前置按源码逐库列明，缺失/未知停止。collector不创建/DDL/清旧队列，不把为过库准入的标记补写当验证。红skill仅需要ontology_import已准专库，不顺便连接完整回归所有库。

## 仍待冻结/续备

两个红入口已有静态候选；最终source/expected identity及独审/授窗仍须作者/ROOT给定。两个绿定向、OR9与完整双模块入口的最终source/expected identity、PG专库白名单和衔接JSON内容/SHA，均须冻结后接入成熟收集器；目前没有这些阶段的运行通过声明。优先红skill入口交ROOT独审，红engine亦不执行模板。脚本/模板/静态结果均仅在本collector目录，原keepers和既有证据不变。现有`baseline-facts.json`及三个基线CSV只从已存证据抽取，保留skill4旧SKIP、engine2类级事件和14源方法未执行清单，未连接数据库。
