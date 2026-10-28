# EX-35 独立只读评审（实质终审前置）

- 评审对象：EX-35 / PLAT-18中PublishedRunProxy与Tomcat回调的锁反序及关闭死锁，产品/测试仅两Java文件；不关闭PLAT-18其它残项。
- 作者：`/root/ex35_gateway_fix`、接续实施`/root/ex35_lock_boundary`及root；审阅者`/root/ex35_independent_review`未编写候选源文件，只读预审、提出问题并独立复核证据。未运行或重复Maven/Java/HTTP/PG测试。
- 候选：运行基底从`20333f35bc9f97dc11df296c04c8028e27fd42c8`推进至共享模块运行HEAD=`0cc967278007343bd5ab09faf9953d86cd573bf8`；产品SHA256=`B4AFF02FFC69CBDC5697A5B7D1B9BC6151B6D75D180791AD5B05E5926C92E129`，Proxy测试SHA256=`03533FDD869AC48D3F1036871FCA76F0EE4CDB05B98A03FA8D62C78F00E376D6`。本次实际文件摘要及最终共享模块指纹相符。
- **提交身份待定**：root尚未明确冻结本卡精确暂存树及提交号；本记录不核准当前index、不虚构最终commit，不据实质通过提前标完成。
- 证据：[原EX-00锁环归因](../2026-10-28-EX-00/gateway-close-deadlock-analysis.md)、本卡[README](README.md)、`red-20261028-194626-659`、`review-races-red-wire-20261028-202145-165`、`frozen-class-20261028-205615-721`、原`frozen-module-20261028-205724-595`及其新增失败，另见[EX-27最终共享模块](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/run.json)、[逐方法比较](../2026-10-28-EX-27/runs/safety-module-20261028-233458-012/comparison.json)、[SSE子步独立终审前置](../2026-10-28-EX-27/review-sse-substep.md)。

## 源码与失败路径核查

原红事实是产品timer持Exchange锁进入真实setWriteListener并等待Tomcat处理锁，真实容器timeout持处理锁进入Exchange.fail而等待Exchange，close随后阻塞；它是Java锁环，不是普通请求超时或单纯夹具等待。原close后面的3秒await不能约束锁环之前的同步清理。新增同文件隔离JVM用真实Spring/Security/Tomcat固定该竞争，原实现报告同型Exchange↔Tomcat锁环并有限退出，未以扩大超时或假close替代。

最终候选把Servlet注册/读取/readiness/write/status/complete及worker/HTTP cancellation移出Exchange监视器；锁内只协调状态、唯一writer、在途操作及句柄权属。逐项核对read/submit/成功writer/fail/terminate/installAsync/close、inline/late callback与Future先安装后execute，未发现剩余持Exchange进入Servlet或取消回调的路径。普通终态由最后在途操作领取唯一context，真实容器timeout在锁外领取并完成以确认timeout；迟到注册/回调不能再取得输出权。

授权proof仍在readiness之后、有限chunk领取之前检查；5秒总预算、2MiB上限、拒绝状态白名单、输入/输出JSON校验、header与目标规则未放宽。claimed有限块在并发终态后保留其状态所有权，不让已领取成功正文被重新标成503/403。取消权单次领取、句柄计数单调增量，worker退出与取消并发不重复统计；PublishedRunHttp/metadata/授权直接调用方只读，没有为假设的HTTP死锁扩改。

## 独立发现与关闭

| 问题ID/级别 | 文件/场景 | 事实与影响 | 处置/独立复核 |
| --- | --- | --- | --- |
| EX35-R1 / P2 | PublishedRunProxy.installAsync，close先于active注册 | 初始候选closed-only分支按默认200完成迟到context。 | 原候选实际红空200；最终503、零字节/上游、唯一完成通过。静态追加发现两次closed读之间窄窗，最终先算stopped再同值设503，B4AFF候选关闭。 |
| EX35-R2 / P2 | 有限块已领取、实际write前close | 原候选HTTP503却实际收到完整111字节成功JSON，非人工写计数。 | 最终只在未commit且position=0时改拒绝状态；客户端实际111字节/bodyMatchesClaim/200，重复关闭无额外输出，关闭。 |
| REG-35 / 模块门 | 原frozen模块新增SSE entered失败 | 原27类287方法有新的SSE FAIL，旧TLS FAIL消失不能抵消。 | 原失败及单次额外PASS保留；EX-27经独立需求门修正实际recycle正例前提，并新增默认off-lock安全向量，没有改SSE产品。最终共享模块本方法及新向量通过，原观察未被覆写或声称历史唯一同因。 |
| META-35 / 收口待核 | 本卡精确树/commit与报告现状 | 尚无最终stage freeze/commit；原README当前结论仍指历史frozen gate=false。 | root收口时须明确追加最新共享模块及本review链接，保留原轮false；精确树/提交/父树和两文件回滚元数据待最后窄核。非新源码缺陷。 |

## 实际覆盖与回归结论

冻结B4AFF/03533候选完整Proxy类33PASS（原30＋新增3）；最终共享模块同两SHA仍33PASS，原403首次实际write前零成功字节、慢读、真实HTTP取消、late async、重复close等原方法全部保留并执行。新增死锁探针有24秒watchdog、30秒父wait与强制回收，红/绿不拖死Surefire/AfterAll。两个发现的竞态均保留同fixture旧候选红及最终结果，不以编译/API错误冒目标RED。

最终共享模块`EX-27/runs/safety-module-20261028-233458-012`是27XML/288方法=273PASS、7FAIL、8ERROR、0SKIP，**Maven真实exit1，allTestsPassed=false**。独立从EX-00所选原方法CSV按完整类名＋方法复算：原254（主29＋补225）无缺失，恢复Proxy原30＋新增3，再新增SSE1；无重复、空坏类、新失败、新跳过或未执行，regressionGate=true。15个保留非通过的状态、顶层/caused-by类型集合无变化；旧TLS FAIL→PASS不记产品修复。

同次Proxy33和SSE38均通过。SSE完整类及共享模块各49份真实child exit.properties已逐份核对，exit0、alive=false、collectorAlive=false；不把flag或peerEOF作退出证据。三阶段8项指纹及HEAD前后一致，最终共享模块确实包含本卡冻结两SHA。

依赖监听恢复、逐类fresh JVM及EX-01 gateway YAML是披露的测试条件变化，不是本卡产品修复。原无监听导致21方法未执行、共享fork部署生命周期冲突6错误、原16非通过方法替换、所有诊断/失败原件保留；没有清ServiceTokens、隐藏AGENT_DEV_AUTH/ENGINE_PG_TEST或将原基线拼成全绿。共享模块关闭当前新增回归观察，但不改写原frozen轮gate=false。

## 逐卡前置结论、回滚与限制

**EX-35候选实质可接受，未发现仍需回改的实质问题；最终接受待root明确本卡精确暂存树、提交/父树和回滚元数据，并补齐当前README的共享模块收口链接。** 本记录不要求再跑已完成测试，也不因尚无index freeze假接受。

DoD①原死锁与两竞态失败/绿色证据满足；②最终完整模块方法门满足，15原非通过保留；③本卡命令/原件齐，共享模块当前结论须由root补链接，不覆盖旧失败；④独立实质审查完成、最终树身份待核；⑤提交号及准确回滚待root。

回滚scope仅：

- `platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java`
- `platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java`

root登记确切提交后，仅反向该提交相对父树的这两文件差异，保留EX-35/EX-00与EX-27证据；不把LifecycleTest、EX-01 YAML/脚本、EX-02、EX-36或算法观察纳入本卡回滚，不做整仓reset。EX-27子步为独立一测试文件范围。

未覆盖：没有重复运行测试、读取真实.env值或原始日志/XML；未声称线上已装配/部署、真实外部IAM联合验收、任意Servlet恶意阻塞或全JVM/native/容量完成。实际root已确认的设计与测试范围之外不作额外保证，不以本卡关闭PLAT-18其它残项。

- 作者处置：引用本卡及EX-27独立证据，不覆盖原审阅意见。
- 审阅者复核：2026-10-29完成实质终审前置；待root显式index freeze与最终提交身份后追加有限元数据复核结论。

## 精确树元数据终评与最终接受（2026-10-29）

root已明确冻结本卡候选。本轮仅查暂存元数据、README收口和源码摘要，未重跑Maven/Java/HTTP/PG或重新读取原始日志/XML。实际父提交为已独立提交SSE子步的`83cc21d0a490a50ae6e5ac3dbad57bd53e0381ef`，其父为`0bd90eab1980bddeab14723447f8ac205e823728`；没有将两卡产品范围合并。

本卡base108树`abeb08315b67118d189e59034e251bf0494f94e7`与index逐路径比较无差异；`index-candidate.json`实际SHA256=`33135C60906924EABA88FED0ED1E8455F0716B6B4A1FF298E6DCEA01C632E288`。108项恰为`closeout.json.publicStageFiles`的108项去掉本报告，再加入本卡`.gitattributes`，无重复、无缺失。逐项工作区SHA256与stage blob均匹配manifest。范围仅两Java及EX-35公开证据，不含raw/XML、实际.env、算法观察或其它卡；manifest与本报告此时待root补入暂存。

两源码仍精确为已实际验证的候选：

- `PublishedRunProxy.java` SHA256=`B4AFF02FFC69CBDC5697A5B7D1B9BC6151B6D75D180791AD5B05E5926C92E129`，stage blob=`48b651793d2780ded5246184ca7ff40499834a82`。
- `GatewayPublishedRunProxyTest.java` SHA256=`03533FDD869AC48D3F1036871FCA76F0EE4CDB05B98A03FA8D62C78F00E376D6`，stage blob=`8ac93220cce4a0ef0cc9d2046097e7f1d03f201c`。

两条原stdout安全摘录的属性模式均以`/runs/…/safe-facts.txt`精确锚定；逐108项有效属性查询仅声明两项`whitespace: unset`。两项`hash-object --no-filters`均等于index blob，未清理或改写原证据字节。Java保持`text: auto / whitespace: unspecified`，后代同名探针未被例外命中，`git diff --cached --check`实际0。

README已补顶部历史轮导航及末尾共享模块收口：真实`stage-gate.json`等链接全部存在；273PASS/7FAIL/8ERROR、native1、`regressionGate=true / allTestsPassed=false`、15个原非通过状态及异常类型不变、TLS变化不记产品修复均准确，旧frozen失败未覆盖。原待补README项关闭。

**最终结论：接受上述精确EX-35候选。** 原锁环及两竞态红绿、完整Proxy33与共享模块方法门已完成独立实质核查，本轮精确树/父身份/文档/回滚范围亦通过，无未关闭问题。root可仅补入该manifest与本报告后本地提交，并登记实际提交号；不预先核准额外内容变更或虚构尚未形成的提交号。

准确回滚限定为最终本卡提交相对父树的上述两Java差异，核后续编辑后反向应用，全部证据保留；不回滚父提交的SSE测试或其它卡。SSE子步的实际提交身份现已明确，但整张EX-27/Y-4及PLAT-18整体不由本卡关闭。共享模块仍保留15个原非通过，本接受不称全模块全绿或生产部署完成。
