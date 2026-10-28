# EX-27 SSE回收夹具子步独立只读终审前置

- 评审对象：仅EX-27的SSE回收夹具子步；修正实际processor回收正例的前提，新增默认off-lock处理中close安全向量。**不关闭整张EX-27/Y-4，不修改任何SSE/Http/Budget产品义务。**
- 作者：`/root/ex35_lock_boundary`；root登记白名单、需求技术门及运行窗口。
- 审阅者：`/root/ex35_independent_review`；与实现独立。本人只读审阅源码/实际安全证据、提出问题并写评审记录，未编写候选Java，未运行或重复Maven/Java/HTTP/PG/浏览器测试。
- 候选：运行HEAD=`0cc967278007343bd5ab09faf9953d86cd573bf8`；唯一Java候选`PortalRunEventsSseLifecycleTest.java` SHA256=`6B144C0033CE22556996897824B2F0D864A862E39F6E12EF2C21B5212B5199C1`，本轮实际文件摘要相符。**待root明确冻结精确暂存树、父提交/提交号；当前没有核准index或虚构最终提交。**
- 证据：[README](README.md)、[技术门](technical-review.md)、[静态问题及类型修复](safety-static-review.md)、[447实际诊断](diagnostic-result.md)、[当前实际结果](current-stage-results.md)；命令原文及真实exit在各run.json/execution.json。

## 实质核查

原fixes brief规定实际delegate.recycle/新REQUEST/completion之前失效旧token，以及无completion继续持费；没有任意socket.close立即回收的保证。固定Tomcat真实Poller也有off-lock close路径。新正例在原socket锁内确认未closed及原processor关联后调用真实close，原5秒和后续全部旧断言保留；没有手工recycle、mint token或假completion。

默认类保留原37方法并新增1方法，合计38。off-lock向量继续执行P1/P2/P2b及后续安全断言，没有在原正例entered预期处提前退出。两次第三SSE拒绝均由额外真实Servlet REQUEST经原start/原私有工厂到Owner.acquire，要求固定slots异常、未发布Handle及实际分配计数+1，排除了旧Handle唯一性门的假拒绝；没有调用或反射调用私有工厂。

实际安全事实已读取class、module各自安全JSON及对应peer属性：

- 正例两条socket在锁内仍关联原processor；真实recycled=2、waiting=0，token失效、completion未伪造，旧动作0。
- off-lock原5秒内recycled=0、waiting=2，两个close正常返回、处理退出fence各2。四阶段Owner均2、completion均0；主动取消后实际Thread.join/isAlive、worker-body-exit及Session.active确认两个reader退出。费用从184175046降至15485324是原工作费用退出，Owner/SSE lease/HTTP未被虚退，第三SSE仍实际拒绝。
- 旧closed连接各一次CLOSE_NOW且返回，安全hook在真实原socket锁下核原tuple；另一个真实keepalive socket两次普通REQUEST均没有SSE token、均200/pong，旧关联未转向、旧游标未推进。off-lock原SSE processor没有回收/复用，不将其冒作复用证明。
- 同次完整类/模块的原keepalive、processor-reuse属性分别见证实际disarm后的同socket请求，以及同processor/differentSocket/pong2/lateActionCalls0。两种证明分列，没有混算。

## 实际覆盖与方法门

| 运行 | 实际结果 | 独立核对 |
| --- | --- | --- |
| `safety-targeted-20261028-233001-643` | 2PASS；Maven0，2 child exit0 | 两准确方法、2份真实exit.properties均exit0/alive=false/collectorAlive=false。 |
| `safety-class-20261028-233128-749` | 默认38PASS；Maven0，49 child exit0 | 原37/候选38无缺失或重复；逐份读取49份真实exit.properties，无异常退出/残留。 |
| `safety-module-20261028-233458-012` | 27XML/288方法=273PASS、7FAIL、8ERROR、0SKIP；Maven1 | 独立按完整类名＋方法键复算：原254均报告，Proxy原30恢复＋新3，SSE新1，无缺失/重复/空坏类/新增非通过；Proxy33和SSE38全PASS。逐份核49份child退出。 |

原254来自EX-00主29＋补225，不能拼称当时全模块全绿。15个保留非通过的状态、顶层类型及caused-by类型集合逐项一致；旧TLS负对照FAIL→PASS单列，不能归功于本子步。**regressionGate=true，allTestsPassed=false，Maven实际1均成立。**

三阶段8项before/after指纹文本一致，包括SSE测试、EX-35两文件、SSE/Http/Budget、gateway YAML及.env；HEAD前后相同，采集错误0。与EX-00的条件差异已明确披露：依赖恢复、每类fresh JVM、EX-01 YAML服务配置；没有清ServiceTokens、隐藏测试标志或将环境变化当产品修复。

## 问题与处理

| 问题ID/级别 | 场景 | 问题与影响 | 处置及复核状态 |
| --- | --- | --- | --- |
| EX27-S1 / P2夹具 | FD34普通ping要求SSE token | 会在安全断言前失败。 | 已在最终候选明确普通REQUEST无SSE token，真实bridge/socket＋两次pong；实际两准确方法/38/模块通过，关闭。 |
| EX27-S2 / 编译夹具 | E175 long计数误用int/mapToInt | 0XML/0child，未执行目标断言。 | 仅改long/mapToLong；反向两处替换摘要精确恢复E175；原编译失败保留，未冒目标RED，最终实际通过，关闭。 |
| META-27 / 收口待核 | 最终index/commit与回滚 | 运行与源码已冻结，root尚未提供本卡精确暂存树及最终提交。 | 非源码缺陷；待root授冻结后窄核路径、摘要、提交/父树与回滚元数据，当前不能标子步完成。 |

447唯一实际诊断FAIL、EX-35历史新增FAIL/单次独立PASS、FD34未运行静态错、E175编译失败均保留。诊断只证明所控交错，未将历史失败认定唯一同因。

## 逐卡前置结论

**本子步候选的实质实现/测试/回归可接受，未发现仍需回改的实质问题；最终接受待root精确暂存树、提交号及回滚元数据复核。** 不因index未冻结假接受，也不要求重复已完成测试。

DoD①：实际诊断红与最终正例/恶劣关闭安全向量绿齐，编译错不充RED；②：完整模块逐方法无新增失败/坏类/跳过/未执行，15原非通过保留；③：README及实际命令/数字齐；④：本独立实质审阅完成，树身份仍待最后复核；⑤：最终提交/回滚待root提供。

回滚只反向本子步提交中唯一LifecycleTest的差异，不回滚产品或证据；需由root登记确切提交/父树后形成准确方式。EX-35的两源码回滚属于另一卡。已核公开候选清单264项不含raw/XML及`algorithm-interruption-preflight.md`；新增评审文件及最终路径集合仍以root冻结树为准，不能把该算法只读观察混入SSE提交。

未覆盖：未重新运行测试、未读取秘密配置值或原始日志/XML、未对全JVM/native/生产容量或任意Tomcat版本作保证。主动取消reader退出不是未取消情况下原期限的实测，相关既有期限方法已在本轮默认38中通过。所有这些限制不扩写为整张EX-27/Y-4或全平台完成。

- 作者处置：引用本卡README、原失败及逐阶段证据，不覆盖本记录意见。
- 审阅者复核：2026-10-29完成实质前置；待root提供明确index freeze及提交/回滚后追加最终元数据结论。

## 精确暂存树元数据复核（2026-10-29）

本轮按root明确冻结指令，只核元数据，未重跑既有测试。父提交实际为`0bd90eab1980bddeab14723447f8ac205e823728`；`index-candidate-sse.json`实际SHA256为`7326DAB3172A69AEFCE06D15FD3228009AC300C33D23F9ECA0E9214ABABA2B74`。265项暂存路径与manifest完全一致，亦恰等于作者264项显式清单加本卡`.gitattributes`，无重复。index与声明的树`88e11d7df721232ff4b6cf7c6aefb32bbce8bf54`比较无差异；manifest及本报告此时尚未暂存，后续仅允许补入本次收口元数据。

逐项工作区SHA256及stage blob均与manifest一致；九项声明保留原字节的证据，其`git hash-object --no-filters`均等于index blob。冻结范围只有本卡公开证据和唯一LifecycleTest，无raw/XML、算法新子步、其它卡或src/main。LifecycleTest仍为`6B144C…5199C1`，共享运行的Proxy产品/测试仍为`B4AFF02F…E129`/`03533FDD…76D6`；`git diff --cached --check`实际0。SSE子步提交不单独冒充共享模块完整产品修复，EX-35两Java另卡提交。

**META-27-A / P3：属性例外尚未严格限定九个路径。** `.gitattributes`前五条为无斜线文件名模式，会匹配本卡任意后代同名文件。实际逐265项查询有效属性得到10项`whitespace: unset`，额外命中`runs/safety-targeted-20261028-232611-304/run-safety.ps1`；对不存在的后代`future-substep/design.md`、`future-substep/run-safety.ps1`查询亦命中。Java和产品未受例外影响，源码及原证据字节均正确；问题仅是范围比root授权的九条精确路径宽。最小处理为将前五条加前导`/`锚定（或九条统一锚定），不改任何原证据、脚本、测试源码，再更新manifest/tree身份。本人未改属性或index。

本轮已确认父树、265项内容及源码身份，回滚仅反向本子步唯一LifecycleTest源码差异并保留全部证据的范围正确；最终准确提交号由root提交后登记。**实质接受结论保持；精确树最终接受暂待META-27-A锚定delta复核，不要求任何产品重跑，不关闭EX-27/Y-4。**

## 属性收窄复核与最终接受（2026-10-29）

root已将九条属性模式全部加前导`/`。本人再次按冻结265项查询index有效属性，`whitespace: unset`现恰为manifest声明九项，无多余命中；根`design.md`仍保留原字节，而两个后代同名探针及第十个编译失败run脚本均恢复`text: auto / whitespace: unspecified`。新旧base树之间唯一差异为本卡`.gitattributes`，其余264项blob及所有工作区SHA与新manifest完全相符，Java及原证据未改。**META-27-A已关闭**，前节原问题记录保留。

实际父提交仍为`0bd90eab1980bddeab14723447f8ac205e823728`；新base265树为`c584336ca3cbc8027551f0396978860047d6b681`，与当前index无差异；新manifest SHA256为`CB79D4135EB59D5B89D85C16F63C760BE99A2609F09FFC1706FB65DD6E81BDDA`。manifest保留旧树及旧manifest摘要。265路径与清单仍完全一致，`git diff --cached --check`实际0；本轮没有运行产品测试或修改index/源码。

**最终结论：接受上述精确候选的EX-27 SSE回收夹具子步。** 实质审查、真实红绿/默认38/共享模块方法门、父树与回滚范围均已核，无未关闭问题。root可仅补入该manifest与本报告后提交，并登记实际提交号；本结论不预先核准额外源文件或内容变更。回滚仅反向本子步LifecycleTest源码差异，保留本卡全部证据。共享运行含EX-35冻结两Java，其独立提交与终评另行办理；不声称SSE这一提交单独完成原模块产品修复，也不关闭EX-27整卡/Y-4。
