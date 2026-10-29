# EX-03 绿测采集入口准备（未占实际窗）

后续：该初版入口已实际采集一次29P/1E，见 [首轮Target](target-first-attempt.md)。测试获授权补submitted协议ack后，采集器仅更新冻结测试SHA；当前源码与采集器摘要见 [修正记录](ack-fixture-correction.md)。以下D204摘要和ParseFile链接保留初版准备事实，不冒充当前脚本摘要或重跑结果。

当前仅新增本卡 [run-verify.ps1](run-verify.ps1) 与证据，未运行 Java/Maven/PG/HTTP，未改三个候选 Java。采集器 SHA256：`D20478C15B3E4B1F280A517B0B893C83C7BC339B3E2D8634A8FB12D93B49FABA`；编码 UTF-8 BOM。

PowerShell 7.6.5 与系统 Windows PowerShell 5.1 只执行静态 ParseFile，均0语法错误，见 [当前解析器](collector-parse-current.json)、[Windows 5.1解析器](collector-parse-windows51.json)。这不是采集器运行验证、编译或产品绿测。

## 下一实际窗的入口

只在主代理明确交还实际窗后执行，两个模式分别产生独立RunId，定向通过后再启动完整模块：

```powershell
& './docs/evidence/2026-10-28-EX-03/run-verify.ps1' -Mode Target
# 核实Target的native-exit.json、summary.json与真实PG/header用例全部通过后：
& './docs/evidence/2026-10-28-EX-03/run-verify.ps1' -Mode Full
```

Target 精确选择 OntologyManifestImportsTest、SkillAuthSchemeRegistrationTest、AuthSchemeOutboundTest 三类，按当前候选18+7+5共30方法检查；Full不加test过滤器，以当前70源类与EX-00原664方法身份为基线，另要求本卡9新增方法都实际出现，实际合计按报告记录，不能用预计数代替。两模式均无-am，不在采集过程中安装/换依赖，记录已安装admin-common jar摘要；如需重新安装当前依赖，先由主代理另行协调窗口。

## 入口的可复核约束

- 运行前核三个已审候选SHA与模块完整源码摘要，保存当前HEAD、70类源清单、精确选择器、`.env`摘要及允许展示的AGENT_DEV_AUTH/ENGINE_PG_TEST/AGENT_SECURITY_MODE；不保存密码或其它环境值。保持同EX-00的1GiB堆/900秒fork参数，不改变授权provider规则或legacy开关。
- 每次重新核127.0.0.1:25432进程/本仓可执行路径/真实data_directory，随后严格核专库owner+本卡comment。入口不建库/清理/接管；测试自身的归属断言仍保留。加载的进程环境和PGPASSWORD在finally恢复。
- Maven返回后，**先持久化native-exit.json与command.json**，再立即复制本轮开始时间后的XML，保存mtime/长度/SHA。随后用XmlDocument.Load按文件声明读取UTF-8，不通过默认控制台编码重解中文方法名。
- CSV保留class/method、XML显式displayName、suite路径、case序号、状态及时间。拒绝重复身份覆盖、空/旧XML、缺类/缺基线方法/缺新增方法、XML声明计数不符及源码漂移；输出覆盖、完整方法、缺失、新增、状态变化、新非通过、保留原非通过各清单。
- Target不忽略失败，要求原生命令0、30方法全通过。Full仅为采集保留failure.ignore=true，既要求原生0也逐方法核对；`allTestsPassed`与`regressionGatePassed`分开，原有6F/106E/4S不因Maven0洗绿。任何新增非通过/跳过、缺失或同方法非通过类型变化会使比较门失败。

原始raw与after下XML仅本地保留，`.gitignore`已覆盖；脱敏摘要/CSV供最终逐文件评审。native0不能单独作为通过，收集器自身抛错也不覆盖已保存的native退出事实。

## 红阶段及尚未到达的链路

v1与补强v2原产品真红原件均保留，均18项=9P/7F/2E、Maven实际1。**“补API_KEY后还会因ONTOLOGY来源得到400”此前只是源码核实，没有运行过这份中间实现，不存在可宣称的中间实测红。** 当前两处最小修复一起进入候选，下一绿必须真实走到PG创建、读回、受控实际HTTP事实、治理连接/确定性模型协议替身选型、生产发布门、发布后从PG读取并发出精确X-API-Key且无Authorization。若出现新的夹具/权威字段或其它规则前置错误，记录其实际原因，不能用该异常冒充产品目标红或放宽生产规则。

原18方法身份保留，测试SHA仍`4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF`；Importer仍`41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5`，Catalog仍`7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03`。当前没有新增产品或测试源码变更。
