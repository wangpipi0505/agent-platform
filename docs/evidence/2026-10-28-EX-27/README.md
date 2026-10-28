# EX-27 前置子步：网关回收夹具

本轮只推进EX-27的网关回收夹具子步，Y-4其它部分未启动。仅测试及本证据目录在白名单内，所有src/main、SSE/HTTP/Budget、EX35 Proxy候选、Tomcat依赖与原期限不改；不使用superpower、不操作index/commit。root负责主入口、独立验收和提交。

当前冻结测试SHA=`6B144C0033CE22556996897824B2F0D864A862E39F6E12EF2C21B5212B5199C1`。**两准确方法2PASS、默认完整38方法38PASS，最终网关27类逐方法回归门通过；模块保留15个原非通过，Maven实际exit1，不是整模块全绿。** 实际窗口已明确归还root，当前没有在跑命令。独立验收及本子步提交仍待root完成，不关闭整张EX-27/Y-4或自行改写EX35主卡状态。

## 实际顺序门

| 阶段 | RunId | 实际结果 |
| --- | --- | --- |
| 两默认准确方法 | safety-targeted-20261028-233001-643 | 2PASS/0F/E/SKIP；Maven0、2 child真实exit0；安全事实独立归档。 |
| 默认完整类 | safety-class-20261028-233128-749 | 原37+新增1=38PASS/0F/E/SKIP；49 child真实exit0；原37及全部38缺失0、重复0。 |
| 网关全部27类 | safety-module-20261028-233458-012 | 27XML/288方法=273PASS/7FAIL/8ERROR/0SKIP；Maven1、49 SSE child真实exit0。Proxy33和SSE38均PASS。 |

各阶段均无诊断flag，保持同.env/argLine/flags、逐类fresh JVM `-DreuseForks=false`，没有清ServiceTokens。8项产品/测试/YAML/.env指纹及HEAD前后一致、采集错误0；先保存native Maven exit，再复制子JVM退出和XML，XML使用XmlDocument.Load绝对路径。独立PowerShell脚本进程退出，不污染root环境。raw/XML在忽略目录，安全事实分列，无敏感头或凭据输出。

最终[逐方法对照](runs/safety-module-20261028-233458-012/comparison.json)明确EX00原254（主29+补225）、恢复原Proxy30、新Proxy3、新SSE1，共288；缺失/重复/异常类0、newNonpass=0，15个保留非通过的top type及caused-by类型集合变化0。TLS旧FAIL本轮PASS单列，不声称本卡修复了TLS产品。原非通过没有因总数或单方法额外通过被抵消。

## 需求、安全与原件

[独立技术门](technical-review.md)批准原正例在真实socket锁内确认原processor关联且未closed后close，保留原5秒及全部旧断言。新增默认off-lock安全向量保留P1/P2/P2b真实恶劣交错，继续验证Owner、真实退出和动作安全，没有只把原红改成预期成功。当前[静态审阅](safety-static-review.md)核过E175及两类型delta；最终运行事实另见[current-stage-results](current-stage-results.md)。

目标两方法、完整38及最终模块均实际核实：原5秒recycled0/waiting2，Owner2/completion0；主动取消后两个实际reader退出，仍不虚退并拒第三真实SSE。旧closed连接各一次CLOSE_NOW且真实返回，另一真实keepalive连接两次普通REQUEST均无SSE token、均HTTP200/pong。新增向量没有原SSE processor复用；真实跨socket/keepalive复用由同次38里的两个原向量另证。完整类和模块的safety-facts-summary.json分别保存原关联、四阶段持费/worker、旧动作目标、普通请求及原真实复用事实，不能混算。

[447唯一实际诊断红](diagnostic-result.md)、EX35历史新增FAIL/单独PASS、FD34未运行的静态ping夹具错、E175两处long类型编译FAIL全部独立保留。没有认定历史唯一同因，也不把未执行后续断言写成已验证。详见[编译失败原件](compile-failure.md)、[ping静态修正](fixture-review-delta.md)、[类型delta](compile-type-delta.json)及[37方法范围](method-scope.md)。最终新增测试没有手工mint token或recycle，也未修改产品义务来凑绿。
