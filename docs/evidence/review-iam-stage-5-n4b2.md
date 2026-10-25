# Spec Compliance

- ❌ Issues found：本轮有限交付仍有一个生命周期阻断（I1）。`platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java:82–105` 把 Exchange 纳入 active 后，在其 monitor 外启动 MVC async 并安装 AsyncContext；`close()` 可先终止它，之后启动线程仍创建异步上下文，不能满足 brief 第49行的 terminal/close/迟到回调要求。不是授权旁路结论，也不是恶意慢 socket 硬截止要求。
- ✅ 其它可由本 diff 核实的主要规格已落实：真实 controller 的 IAM 分支先 claim 再 proxy，legacy 在其后（完整 diff `docs/evidence/review-iam-stage-5-n4b2-package.diff:1093–1128`）；唯一 REQUEST POST、无任何 query、安全 code、真实签名 user/platform.access 与 owned proof/anchor/单claim（`admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java:74–111,147–180,206–244`）。
- ✅ 生产8位置、测试5位置，独立装配不自动扫描；实际最外 FilterChainProxy 引用贯穿 chain/registration/verifier，具体 final registration 保存真实 add/configure/context/config snapshot，并对照当前配置、全部 dispatcher、URL、async/order（`GatewayIngressBoundary.java:265–361`；`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/GatewayIamAssembly.java:24–77`；diff 第1051–1057行）。全局 ANY 仅真实同 bean/declared method 的两项，全量 HandlerMapping 检查且不放宽 ordinary Ingress（diff 第401–413、504–559、601–649行）。这里只证明给定有限、debug=false 装配，不能凭 getFilterChains 认证任意未知 outer decorator。
- ✅ 真实 MVC AsyncWebRequest、2MiB请求/成功体、严格对象/重复键/尾随校验、单调5s业务预算、metadata→独立原 Portal main→固定 engine、发送紧前/缓冲后/每次成功写前复核，以及真实 HTTP handle 迟到安装取消均有实现；I1 是 AsyncContext 安装的另一个未闭合窗口（`PublishedRunProxy.java:73–110,153–288,313–327`；`platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java:111–157,183–269`）。
- ✅ 专用 Apache5.4.4/core5.3.4 使用公开配置，关闭 retry/redirect/cookie/auth challenge/compression，普通 initial headers 与 chunk/trailer parser 约束分离；第一条未声明 trailer 被 maxHeaderCount=1 拒绝，chunk行8192有界（`PublishedRunHttp.java:35–90`；固定 core parser 第199–200行，见下方命名风险检查）。不把 gateway 两跳特性扩大成 common IAM 全链零重试/所有网络硬取消。
- ✅ 联合测试源码确实启动两个独立业务子 JVM，各自模块/common/ownm2 classpath 和 private STDIN，真实 app/engine PG及业务类；父JVM无业务JAR/classes（`platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayPublishedChainPostgresTest.java:100–178,624–657`；`engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayPublishedEngineFixtureMain.java:29–175,177–254`）。正常 create/replay、pointer/disable/sourceexpiry/current/material等断言真实业务结果，非503 stub（PG test 第351–591行）。
- ⚠️ 证据边界：作者28阶段/当前48用例是逐case最后有效green，不是最终源码一次48全绿（作者报告第61–98行）。root已独立报告 current/frozen/replay source核、target1879/before1814、85差异、16347新封存项和28阶段35份XML逐case一致；我未重复执行这些物理全量核验，保护lineage最终结论仍由root负责。我的源码结论不以作者自述代替diff判断。
- ⚠️ 后续Task4/5/6/7及外部依赖不属于本次验收：完整生产IAM闭锁、D4门户、独立GET status、其它执行形态、RR1/RR2、真实IAM/模型/本体/K8s和容量仍未完成；主服务仍原962制品、T01仍唯一整体完成（brief 第9、74行；作者报告第104、116行）。本报告即使修复I1也不能签收这些事项。

# Strengths

- ✅ 私有请求能力不是 public Jwt/VA/ThreadLocal 的包装；真实解码、request anchor有限祖先、owner、实际 handler 与单次领取形成连续检查，CGLIB 使用resolved bean与target method，没有 `this==proxy` 假设（`GatewayIngressBoundary.java:38–71,83–111,147–180`；`GatewayPublishedRunProxyTest.java:643–725,728–785,1366–1407`）。
- ✅ FutureTask先安装再execute，inline WriteListener 当前worker取消使用 cancel(false)，其它线程才interrupt；worker finally计数与真实HTTP句柄观测分开，避免以 Future.isDone 证明退出（`PublishedRunProxy.java:199–227,340–346,400–409`）。
- ✅ 真实无query raw-wire、已注册 DevAuthentication、特定启动错误码与 WebServerInitializedEvent 对照、secondary mapping等测试有实际可失败断言；startup18的immutable-UOE不被当成guard证据（`GatewayPublishedRunProxyTest.java:619–640,788–996,1036–1066,1211–1229`；作者报告第96行）。
- ✅ PG正常测试区分一个 originAuthenticationId 和四个 distinct grants（两query+两main，含相同requestId重放），核同run、2 attempts、输出a7与worker0（`GatewayPublishedChainPostgresTest.java:351–421`）。实际52 materials/appPayloadRead1来自阶段证据，测试本身只断言materials>15；两者不混写成更强的源码断言（作者报告第55行；PG test 第383行）。
- ✅ 报告保留真实边界：slowreader自己5500ms关闭且已提交73728成功bytes；proxy16历史app PID/port/exit缺证，当前JVM0不能补成历史退出；engine最终134非空CP来自旧N3 final03，compile04仅0-test源码/class对齐。失败setup、self-interrupt、undeclared trailer red23、长chunk行red26和green27的1失败都未删（作者报告第37、47、53、57、61、96、114行）。

# Issues

## Critical (Must Fix)

- 无。本次完整diff范围内未发现能伪造Portal/request proof、越过真实handler守卫或把原Portal token发到业务服务的Critical缺陷（`GatewayIngressBoundary.java:162–180,206–244`；`PublishedRunProxy.java:240–274`）。

## Important (Should Fix)

### I1 — AsyncContext可以在Exchange终止后迟到安装

- 位置：`platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java:82–105`，关联第348–349、381–396、422–427行。
- 根因和精确交错：请求线程已完成 `active.add(exchange)`，尚未执行 `mvc.startAsync()`/`exchange.async = request.getAsyncContext()`；另一线程执行 `close()`，对该Exchange `fail(503)` 后 `terminate()`，把状态设TERMINAL、从active移除，但async仍null，不能complete任何上下文。关闭线程可以结束。原请求线程恢复后仍会 `mvc.startAsync()` 并赋值async；随后closed分支调用 `fail(503)`，但fail因TERMINAL立即返回。以后timeout/error/completion调用同样被terminal短路。等价问题也存在于实际startAsync返回而async字段尚未赋值的安装窗。
- 影响：组件关闭后仍创建已失去代理跟踪/完成责任的异步请求；active=0/worker已退出不能证明该请求已完成。其后只能依赖容器超时或连接结束处理，违反本轮明确的close、单terminal和迟到callback生命周期约束。这里不声称无限socket泄漏、已发生PG写入、权限泄漏或必定返回某一状态码。
- 固定框架核对：Spring6.2.7 `docs/evidence/iam-stage-5-n4b2-gateway-published-chain/upstream/StandardServletAsyncWebRequest.java:142–164` 在自身状态机内直接创建并登记AsyncContext，不知道Exchange已经terminal；第185–207行只运行已登记handler，不能替代理修复此迟到安装。
- 修复方向：把closed/terminal检查、async启动和句柄安装作为同一受控生命周期操作，或明确实现“迟到安装看到terminal则立即complete”的幂等交接。不能只在close前再读一次closed，也不能以active=0代替AsyncContext完成。保证与close登记请求的竞态同时闭合。
- 验证缺口及建议：现有 `GatewayPublishedRunProxyTest.java:1262–1327` 直到真实engine读已开始才close；第1104–1160行验证的是Apache请求句柄的迟到安装。它们均未覆盖AsyncContext安装窗。建议root授权一个确定性latch用例，分别阻住startAsync前/返回后安装前，交错close后释放，验证上下文完成仅一次、无metadata/main/engine调用、无容器超时依赖、late callback不触碰已完成response。此审查没有构建或执行该测试，不把静态推导称作实测复现。

## Minor (Nice to Have)

### M1 — disabled catalog服务guard测试名与实际断言不符

- 位置：`admin-common/src/test/java/cn/agentplatform/common/authority/GatewayIngressBoundaryTest.java:38–56`。
- `ordinaryReceiverStillRequiresItsServiceGuardOnDisabledCatalogRows` 仅注册 `/unregistered` 并期待 `ROUTE_COVERAGE_MISSING`，在检查service guard之前就失败，不能证明名字宣称的“已登记disabled catalog row仍需要ServiceAuthorization”。生产原guard分支仍保留（完整diff第604–644行），故这是测试说明/局部覆盖问题，不额外升级为当前业务阻断。
- 建议：改成准确描述unregistered route的名字；如要保留当前名字，应选真实disabled catalog method/template并精确期待 `ROUTE_GUARD_MISSING`。无需因此重跑旧全套receiver测试。

# Assessment

**Task quality: Needs fixes.** Spec门因I1未通过；质量门同样因关闭后异步句柄迟到安装未闭合而不通过。其它主要信任边界与真实联合链有可审查实现和历史行为证据；修复应聚焦这个窗口，不扩大为全链重跑或完整生产IAM发布。

- 检查范围：先读AGENTS及task-reviewer-prompt模板；brief第1–74行、作者report第1–118行、handoff全文；完整review package按连续150行小段第1–4869行读至EOF，13文件完整上下文，无中途函数截断、无另读current changedfile。PowerShell Get-Content显示4869逻辑行；派发splitlines为4870，EOF实际已读完。brief/report/handoff/diff四项bytes与给定SHA均逐项匹配。Base为e9e2140273874502f0eff0bd8ad035f795694a4e80cb4e167daee7e6f28401f8，Frozen为cdc3f4a1151a57005aed2f9a605226bae7f6456d8c7d1c339af5a2476a11968a；没有HEAD/remote或git操作。
- 命名风险R1（async安装与close）：只窄读上述固定Spring6.2.7 StandardServletAsyncWebRequest第131–219行，确认I1。R2（框架response锁反序）：另读同固定文件第217–369行，看到ERROR状态及tryLock循环避免onError ABBA；未据此另报deadlock，也未把这段检查扩大为所有callback无锁风险证明。
- 命名风险R3（真实发送前检查位置）：只读固定client5.4.4 `upstream/ConnectExec.java:119–201` 和 `upstream/MainClientExec.java:97–137`，确认endpoint取得/连接后proceed，再protocol interceptor，然后execute；与 `PublishedRunHttp.java:82–89` 的检查落点一致。
- 命名风险R4（trailer配置是否真实生效）：只读固定core5.3.4 `upstream/core-ChunkedInputStream.java:230–305`、`core-AbstractMessageParser.java:161–212`、`core-BHttpConnectionBase.java:163–204` 和client5.4.4 `client-ManagedHttpClientConnectionFactory.java:74–97`，确认incoming chunk使用connection配置、第一trailer行触发>=阈值、独立responseParserFactory不被覆盖。七个实际窄读上游文件SHA均匹配reading-manifest；这是固定归档检查，不是全库或CVE审计。未联网抓取其它版本。
- 历史适用性检查：读取coverage-ledger的case/lastgreen/changedMethods/applicability记录，并仅抽取五个历史方法：proxy16第111–113行、chain17第113–115行、lifecycle19第175–177与184–186行、trailer-green25第1453–1524行。前四个现有方法仅字符串字面量分拆/排版，wire字节、时长、断言和cleanup相同。第五个原body长度11转成等值chunk十六进制b，未声明trailer字节和异常/线程退出断言相同，仅私helper和线程名调整；新helper长chunk路径实际green28，不能称提取后trailer调用又重跑。PG25与最后8192边界后的适用性属限界判断，不是最终全套green（当前测试第1453–1481行；作者报告第94–98行）。
- 历史失败/噪声检查：已读tool-events所有事件和作者报告阶段表，setup/编译/runner预校验错误不当作行为红；原失误留档，当前最后green与scope解释不因旧失败自动失效。对green25/green28 raw检索WARNING/WARN/Mockito/Sharing/deprecated/unchecked无匹配；这仅是两份raw的结果，不能声称全部日志pristine。root另行逐stage XML/日志核验不由本报告替代。审查自己的reading-manifest汇总输出曾发生JSON深度警告和局部截断，未据此声称通读manifest；之后仅精确选择所需entries并独立核SHA，源码diff与正式brief/report没有截断。
- 未执行：Maven/javac、任何测试suite/probe/loop、浏览器、HTTP、DB、进程控制、全仓爬取或相邻旧工程访问。未改source/target、git/index/HEAD、共享索引或已有evidence；唯一新增物是本审查报告。全文写入并封存后即归还该文件写权，停止写入；封存bytes/SHA通过交还消息提供。