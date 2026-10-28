# 6B144C当前实际顺序门

冻结测试SHA=`6B144C0033CE22556996897824B2F0D864A862E39F6E12EF2C21B5212B5199C1`。两准确方法、默认完整38、网关全部27类按门先后执行，没有诊断flag。未改src/main、原期限、ServiceTokens或keepers；同.env/argLine与逐类fresh JVM条件。每次独立PowerShell进程退出，不污染root环境。8项指纹包含EX35两文件、SSE测试/SSE/Http/Budget、gateway YAML及.env，各阶段前后一致，HEAD亦一致。

| 阶段 | 原件 | 真退出及覆盖 | 阶段结果 |
| --- | --- | --- | --- |
| targeted | safety-targeted-20261028-233001-643 | Maven0、1XML/2方法、2 child exit0、不alive | 准确2PASS/0F/E/SKIP，stage-gate=true。 |
| class | safety-class-20261028-233128-749 | Maven0、1XML/38方法、49 child exit0、不alive | 默认38PASS，原37/候选38缺失0、重复0，stage-gate=true。 |
| module | safety-module-20261028-233458-012 | Maven1、27XML/27类/288方法、49 SSE child exit0、不alive | 273PASS/7FAIL/8ERROR/0SKIP；逐方法regressionGate=true，allTestsPassed=false。 |

模块与EX00 comparison-baseline指向的原254方法逐项对照，主29与补225分别列示；恢复原Proxy30加新Proxy3，新增默认SSE1，全部288完整报告，缺失/重复/类异常0。Proxy实际33PASS、SSE实际38PASS。newNonpass=0；保留15项原非通过的状态、top异常类型及caused-by类集合均相同。旧TLS negative control FAIL本次PASS仅为额外观测，不归因本卡修复。模块command/execution/run、fingerprints-before/after、methods、27份raw XML、cause比较及stage-gate全部保留。

两个默认安全向量在target、完整class、module分别有实际事实：正例两个socket锁内原关联；off-lock正常返回2且退出fence2，原5秒无recycle/waiting2。四阶段Owner2、completion0、waiting2，取消后实际readerAlive0。旧closed连接CLOSE_NOW各1且返回，另一连接两次普通REQUEST均无SSE token、均HTTP200/pong且同keepalive socket。off-lock原SSE processor没有复用；beforeStop全局recycled4来自两个额外拒绝请求和普通ping，不冒称原SSE回收。原slot未转向断言实际通过。同次完整38/模块的原keepalive与processor-reuse向量另有真实复用properties和child exit，保存在各run/peers，safety-facts-summary.json分列。

原447确定性诊断FAIL、EX35历史新增FAIL、FD34未运行静态夹具错和E175编译FAIL均保留，不改为产品修复或省略中间失败。主动取消退出没有冒称未经取消的原期限自然退出；原期限相关方法仍在完整38中实际通过。模块环境与EX00原条件差异（依赖恢复、逐类fresh JVM及EX01 YAML服务配置）已在comparison.environmentDisclosure单列，不能算为产品修掉旧非通过。

三阶段完成即向root明确归还Java/Maven/PG/HTTP窗口；此后仅整理安全证据，没有追加运行。regressionGate是本轮方法对照结果，整模块仍15原非通过，卡片独立验收/提交归root，本记录不关闭整张EX27/Y4或EX35。

