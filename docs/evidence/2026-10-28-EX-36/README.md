# EX-36 本体上下文客户端的Spring构造装配

当前：等待独立终审与本地提交。真实Spring原产品失败已复现，最小构造注入修复后定向4方法通过，完整engine对照无新增非通过，正式JAR已实际就绪并由真实注入客户端读取OR。作者为主执行者`/root`，起始HEAD为`0cc967278007343bd5ab09faf9953d86cd573bf8`。用户已授权按台账持续实施且不使用superpower；本卡先登记在三主入口，再实施准确白名单。尚未计本卡完成。

候选文件/工作区与Git blob身份见[candidate-manifest.json](candidate-manifest.json)；独立最终结论见[review.md](review.md)（评审生成后生效，作者自检不替代）。

白名单仅`engine-runtime/src/main/java/cn/agentplatform/engine/infra/OntologyContextPacks.java`的生产构造装配、对应`OntologyContextPacksTest.java`及本目录。不改其它协作者文件、真实配置、缓存/鉴权/密钥解析规则、迁移或依赖。其它卡的现有修改全部保留，不暂存。

## 原失败与测试设计

EX-01的[正式启动记录](../2026-10-28-EX-01/runs/live-ex01-20261028-223728-310/run.json)保存当前源码/JAR指纹、本卡独立新空库和`ontologyContextPacks` Bean实例化失败。原客户端同时有public两参`@Value`生产构造和private四参测试工厂构造，缺明确注入入口；三个既有测试都使用`forApiKey`工厂，无法证明Spring能创建该组件。

新增同文件方法`springInjectsProductionConstructorAndUsesConfiguredSecretReference`：只注册真实组件并刷新`AnnotationConfigApplicationContext`，URL/ref由属性源注入；刷新成功后实际取包，通过环回HTTP验证注入URL被消费、真实Vault解析合成引用、精确`X-API-Key`及无Authorization。没有给容器手工提供组件实例、替换构造选择或传入测试工厂。凭据仅临时合成，JUnit临时目录回收，相关系统属性在finally恢复。环回端点是协议桩，不是OR联合或真实JWT入口验收。

本卡测试源码先在EX-01仅使用固定JAR、不再编译测试的窗口写入，产品当时保持冻结。EX-01完成其余固定JAR实际验证后明确交还唯一Java/Maven/PG/HTTP与src/main窗口，主执行者才运行原产品真红。原EX-00的906方法状态与失败不得覆盖；新增失败/坏类/跳过/未执行均0方可放行。

原产品真红见[runs/red-spring-20261028-225550-607](runs/red-spring-20261028-225550-607/run.json)：Maven真实退出1，编译成功、1份新鲜XML/4方法=原3通过＋新1错误。该错误在真实context.refresh处，精确原因为`NoSuchMethodException: OntologyContextPacks.<init>()`，安全核验见[failure-facts.json](runs/red-spring-20261028-225550-607/failure-facts.json)，与EX-01正式JAR异常目标一致，不是测试桩/凭据/PG前提错误。

随后产品仅加`Autowired`导入、既有public两参构造的`@Autowired`及一条中文不变量注释；没有新增默认构造、改测试工厂或任何客户端规则。[绿色定向](runs/green-spring-20261028-230009-785/run.json)真实Maven退出0、1新鲜XML/4方法全部通过。新方法实际到达Spring属性消费、Vault解析及HTTP取包/头断言，原3工厂方法亦通过；只是局部绿，尚不关闭本卡。

以下为AP仓根目录中**当时实际执行的Windows PowerShell5.1命令原文**。`red-spring`对应原产品SHA `29049534…56550`；当前修复后不能只按Phase名字把一次新运行称为红。实际Maven参数/原生退出码另在各轮`execution.json`中保留。

```powershell
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'docs/evidence/2026-10-28-EX-36/run-tests.ps1' -Phase 'red-spring'
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'docs/evidence/2026-10-28-EX-36/run-tests.ps1' -Phase 'green-spring'
& 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe' -NoProfile -NonInteractive -ExecutionPolicy Bypass -File 'docs/evidence/2026-10-28-EX-36/run-tests.ps1' -Phase 'after-module' -All -Collect
```

## 运行及交接

`run-tests.ps1`须用独立Windows PowerShell5.1进程执行，环境仅加载本仓`.env`到该进程；退出不污染调用者。命令、原生退出码在解析XML之前立即落`execution.json`；UTF-8 XML按自身声明加载，原始日志/XML本地忽略，方法及源/配置指纹可入证据。`-All -Collect`用于完整模块保全报告，不把Maven0当测试绿；`-All`明确移除定向选择器，避免Windows原生命令丢空参数。当前环境与原EX-00均未声明AGENT_SECURITY_MODE，也未在argLine注入该属性；后续记录键存在性和值，不能只据.env哈希判断继承模式。

定向通过后，按约定先跑完整engine，再从当前源码重包engine并交EX-01补正式启动、JMX及真实OR客户端。此前legacy/PROCESS及其它服务已绿阶段保留原RunId，不拼成同一轮全绿；实际完成情况见下节。

## 完整模块与正式JAR补验

[after-module-20261028-230322-842](runs/after-module-20261028-230322-842/run.json)：`-All -Collect`实际Maven退出0，93份新鲜XML、907条记录=792通过/35失败/80错误/0跳过。其中905条具名记录，另2条是原类级事件：`ModelInputMaterialHttpTest`空method的FAIL与`RunEventReaderTimeoutBudgetPostgresTest`空method的ERROR；两类共14个源`@Test`没有具名执行记录，不称其已执行。原906条身份全部保留，唯一新增为Spring方法且通过；原115条非通过的身份/状态及异常类型、caused-by类集合完全相同。见[逐方法差异](runs/after-module-20261028-230322-842/method-summary/comparison.json)、[异常类型对照](runs/after-module-20261028-230322-842/nonpass-cause-comparison.json)。报告已收齐`reports_collected=true`，但`collection_complete=false`、`all_source_methods_executed=false`、`tests_all_passed=false`；相对基线的新增非通过/坏类/跳过/未执行均0，独立差异门`module_comparison_passed=true`，不等于全模块全绿。

派生分析曾混用“报告已收齐”和“源方法都执行”：初版两门均false，保留于`analysis-initial-summary.json`与`analysis-initial-comparison.json`；中间版按报告收齐给collection_complete=true，保留于`analysis-report-collection-summary.json`与`analysis-report-collection-comparison.json`。独立审阅进一步确认应同时列空method的FAIL/ERROR，最终采用上段明确区分的字段：2原类级事件及14个未执行源测试仍保留，只把相对基线零新增作为差异门。未删XML、改变任何测试状态/退出码或重跑Maven。原始失败仍由EX-27清单承接，不以本卡解消。

[engine-only打包](runs/package-engine-20261028-231712-740/run.json)实际退出0，记录154份engine/common生产文件名及摘要，前后未变；跳过测试编译/执行，测试数0不计绿。新JAR SHA256为`76AB67C3A72CD1931E2DC64E053CAE8A11A86A850CFA9DA5EB95FEE1132B419C`，与原阻断JAR `B15763C5…6FF875`分开保存。实际复跑构建命令为`mvn.cmd -B -ntp -pl engine-runtime -am package -Dmaven.test.skip=true -Dmaven.repo.local=<本仓>/.tools/m2`。

EX-01在自身受限环境文件与原本卡独立空库上执行[engine正式补验](formal-engine-startup.json)，原件/副本字节一致关系见[交接摘要](formal-engine-evidence-handoff.json)。两次登记PID50360/6276分别健康200：非零quota窗口97/tenant301/app211/credential101配置通过；仅重启engine的enabled=true/三阈值0配置通过。OR客户端读取另由**从该新JAR提取的类路径下启动的独立Spring探针进程**完成：真实Spring注入OntologyContextPacks读取既有app pin及READY context-pack成功，绑定ID为`c94c828b-5734-4474-8209-195a83ba5354`；它不是在上述运行PID内触发J9。engine只持readonly引用/key、不持app binder，父双key/引用恢复；154份源码和JAR前后匹配，`.env`未变、密钥扫描未发现值。仅停止本次登记PID，PG/Redis/存储及OR保活未动。

该正式补验是EX36构造装配及EX01配置证据，不是J9真实publish/run或完整EX07。EX01的gateway新增SSE失败仍独立保留，`wholeCardPassed=false`不被此处改为true；本卡只使用engine模块及正式启动门。

`entry-documents-check.json`保留执行前期39卡/4进行中/1待验收时的离线回执；不是当前进度。候选有限收尾时的三主入口结构/路径与SHA快照另见`entry-documents-check-precommit.json`，只对应其checkedAt时点，后续台账回填提交号会继续变化，不冒称持续有效的状态源。

## 回滚

最终仅反向本卡两个源码/测试文件的提交差异，保留证据和独立验收资源；不使用整仓reset、add -A或撤销他人修改。无业务API/数据迁移或开关变化。修复后PLAT-13仍需EX-07真实联合，不以本卡代替。
