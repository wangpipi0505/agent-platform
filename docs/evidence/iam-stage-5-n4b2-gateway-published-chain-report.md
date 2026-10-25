# IAM Stage 5 N4b2：实际 Gateway 已发布运行门户链

本轮按正式 brief 完成有限装配下的真实 GatewayController 门户链、异步 JSON proxy 及独立 app/engine PostgreSQL 联合验收。实现与证据已冻结，等待 root 独立 Spec/TaskQuality 审核；本报告不自行签收 full Task 5，也不表示普通 GatewayApplication 已切换 IAM。没有真实外部 IAM、模型、本体或 K8s，验证使用本工程隔离 loopback authority/HTTP 端点及真实专用 PostgreSQL。

## 范围、起点与所有权

唯一工程为 G:/207/项目/算法重组工具/代码/agent-platform。正式 brief 已按小段读至 EOF，21337B，SHA256 cb28e508813a3af81326c30ea7557d98b742683f48f79705c763fc25b074f38e。已读 AGENTS.md、docs/implementation-brief.md、contracts/README.md 与前置设计/交接。使用既定设计、TDD、系统调试、验证技能；没有再派子代理、重新征求人类同一功能许可或创建工作树。

进入实现前物理备份全部369 source、1814 target并核SHA，修改及Maven均在其后。source-before.json与B1 author source-after manifest一致，SHA e9e2140273874502f0eff0bd8ad035f795694a4e80cb4e167daee7e6f28401f8；target-before.json与B1 author target-after一致，SHA 1e4de86fa31e89c3d7d004e5eb603dcc772077fba3cc8e2f8b8dd923a04b0d55。最后又核对全部2183物理before副本，未重写before来掩盖差异。

生产改动仅8个批准位置：common新增GatewayIngressBoundary及修改RouteCoverageVerifier；gateway新增plain lite GatewayIamAssembly、PublishedRunProxy、唯一专用PublishedRunHttp，修改GatewayController、PublishedMetadataClient、gateway POM。测试5个位置：新common边界测试、新gateway proxy测试、新gateway同authority包PG联合测试、新engine test-only有限main、受影响原PublishedMetadataClientTest。全部13处source差异是8新增、5修改、0删除。

没有修改B1两个common生产类、完整SecurityConfiguration/ServiceTokens/AuthorityProperties/catalog、其它业务生产、SQL、前端、配置、runner或旧app/engine fixture。未访问相邻旧工程代码/配置/缓存/数据库/进程；没有HEAD/remote操作、commit、package、deploy、push、主服务切换。Maven test阶段原绑定生成common tests JAR，明确列入target差异，不称业务服务打包发布。

## 实际入口、proof和安装证明

GatewayController只保留真实注入的4参构造器；正常legacy由同一validated AuthorityProperties.mode=legacy保持，不新增new默认props或2参捷径。IAM分支先claim私有能力再进入proxy。有限fixture从实际Environment调用现有SecurityConfiguration.bindDeployment，BFPP登记同一validated snapshot，未import完整IAM BFPP来绕启动边界。

GatewayIamAssembly没有@Component/@Configuration；只有显式@Import才激活@Bean。正常组件扫描测试确认它不成为候选。固定@EnableWebSecurity(debug=false)，没有额外WebSecurityConfigurer、任意customizer、decorator或另造FCP。注入@Qualifier(springSecurityFilterChain) Filter最终bean，确认instanceof FilterChainProxy；保留Security6.5实际MVC cache/path最外CompositeFilterChainProxy，同一引用用于容器注册、coverage和实际doFilter。未将转型发布成第二个@Bean，也未剥外层包装。

最小final InstalledFilterRegistration extends FilterRegistrationBean，持有框架实际最外FCP。真实addFilter返回Dynamic、configure成功后保存context、对象和配置快照；当前getFilter、async、order、URL和dispatcher配置也必须与快照一致。Servlet metadata仅作额外检查，不能单独证明实例/顺序/dispatcher。Boot因真正FilterRegistrationBean.getFilter被标记seen，且有限fixture排除SecurityFilterAutoConfiguration的重复DelegatingFilterProxy注册。链内owned GenericFilter不另作Filter@Bean。注册显式覆盖REQUEST、ASYNC、ERROR、FORWARD、INCLUDE全部5种；REQUEST外一律拒绝。

验证实际框架bean只有唯一expected catchall链、AnyRequestMatcher和同一owned filter，owned filter位置在SecurityContextHolderAwareRequestFilter前。真实所有RequestMappingInfoHandlerMapping（包括secondary）严格登记实际GatewayController同一bean/method的两个ANY admin/**、v1/**；所有handler必须同一个全局MappedInterceptor，禁止第三ANY/缺一个ANY/替代handler/partial拦截器。普通Ingress的ANY及wildcard禁令、app/engine的service guard保留。DevAuthentication通常因IAM条件不注册，未注册404不算handler守卫证据；有限fixture显式@Bean真实DevAuthentication且validated agent.dev-auth=false不改，验证入口先拒，未改其生产类或注册条件。

只允许无query（空query也拒绝）的REQUEST POST /admin/apps/{safeCode}/runs；safeCode为首字母加有限字母数字下划线连字符，最长64。其它method/path、编码、contextpath、重复或非Bearer授权、Cookie/X-Api-Key、身份/重入均默认拒绝。TrustedJwtDecoder实际验Portal用户并检查platform.access；原Portal token只发authority，app与engine只收各自ticket。

私有proof属于boundary及request anchor；沿有限wrapper祖先寻找owned anchor，检查循环/深度和anchor identity。preHandle核真实解析actual HandlerMethod bean及方法，Controller单次claim。不能用公开VA、普通attribute、SecurityContext或ThreadLocal重绑造proof；CGLIB controller及Security wrapper实际正例通过。common仅做可真实触达的有限负例，合法proof正例在gateway模块，无同FQN假GatewayController或为测试公开factory。

## 有限异步生命周期与真实HTTP

业务入口建立一次monotonic总预算5000ms（不包含前置JWT验证）。使用MVC已有WebAsyncRequest，注册timeout/error/completion，按剩余预算setTimeout并startAsync；没有裸request.startAsync。状态为READING→UPSTREAM→WRITING→TERMINAL。ReadListener/WriteListener不执行blocking HTTP/read/wait/flush/close；原body完整读取、严格JSON对象/重复字段/尾随token校验后才启动metadata。请求和成功JSON各最多2MiB；合法原body字节不改。

owned worker先metadata query获取实际发布版本，再用同一原Portal token执行独立B1 main exchangeGatewayPublishedRun，固定engine origin POST。没有resume/重建main或POST重试，没有把两个票据合并。metadata/engine各最多2000ms，connect1000ms，并取总剩余min；不按chunk或阶段重置总预算。

唯一新Apache transport为httpclient5固定5.4.4，core5/core5-h2由Boot管理固定5.3.4；只gateway POM加此依赖，既有RestClient显式Simple工厂保持。禁自动retry、redirect、cookie、auth challenge/cache、compression/connection state。每个实际HttpUriRequestBase安装取消句柄，完整body超期有自己的timer；句柄安装晚于terminal也被实际cancel。真实request interceptor在ConnectExec已取得/连接endpoint后的MainClientExec protocol processing触发beforeSend，执行requireVerified；完整成功buffer后与每次实际成功write前再次requireVerified。buffer后过期成功payload为0字节；已经提交的成功字节只能停止、不可撤回。

FutureTask在execute前先于同一monitor赋值，排除inline worker完成后迟到安装Future的重算中断竞态。真实WriteListener可能在setWriteListener里同步回调，早期Future.cancel(true)自中断worker，造成HTTP零字节EOF。diagnostic15实记gateway-published-0线程中断前false/complete后true；修正为当前worker自终结cancel(false)、其它线程终结cancel(true)，实际HTTP取消独立、worker finally退出单独观测。close、复用、迟到callback、真实read中关闭、deadline/body完成竞态均有具名测试。

响应只接受有限JSON/ApiResponse，拒绝编码、cookie、location、已声明Trailer、异常媒体/字段/重复尾随JSON、超界成功body、unsafe错误状态。原LimitedBody仅兼容旧Flow API及其既有单测，不作为Apache句柄取消证据。新证据都在实际Apache请求、timer和worker退出路径上。

### Chunked trailer与行上界

真实red23证明仅检查Trailer响应头会放过未声明的chunked trailer。core IncomingHttpEntity.getTrailers返回null，未用反射/内部unwrap/自造HTTP parser。framing24一次授权诊断只打印framing头：真实metadata GET与engine POST均是chunked（CL空），故不能限制Content-Length或改app/engine响应迁就实现。

固定core5.3.4 AbstractMessageParser.parseHeaders在headerLines.size() >= maxHeaderCount时报错，connection配置maxHeaderCount=1拒第一条实际trailer；显式独立DefaultHttpResponseParserFactory仍用正常64头/8192行，显式正常DefaultHttpRequestWriterFactory，不影响普通多header chunked JSON。green25实际trailer拒绝、普通chunked、真实PG正例共3绿。

root窄读指出connection默认maxLineLength=-1，trailer count前先读行。新增有限9000字节合法chunk extension（无trailer、小JSON）真实red26被接受；第一次修改脚本因LF/CRLF未插入setter，green27是1绿1失败，原source/XML保留。实际connection加setMaxLineLength(8192)后green28仅长行拒绝+普通chunked2绿。这是新有限上界，不声称原9000byte trailer会200；未跑危险无限输入或重复PG25。

## 独立子进程与真实PostgreSQL联合链

gateway父JVM不含app/engine业务JAR/classes，不反射父JVM挂sibling target。app使用现有PublishedAdmissionFixtureMain原样不改，固定app库agent_platform_iam_admission_app_v12、100..104、admission-flow、n3-tenant，实际metadata和AppController/AppService。engine新增自己模块test-only main，真实RunController/RunService/RuntimeClients/RunAuthority、owned scheduler/FlowDriver/fullstatus和engine库agent_platform_iam_admission_engine_v12；启动前保留source max217与完整history：19行含null schema row，实际18条非null migration200..217，未加migration或碰主库。

engine CP最终取既有N3 final03真实XML134非空entries；app CP115 entries。逐项canonical核准仅本模块/common targets与ownm2，保留来源SHA及自身CP；compile-input-provenance.json将历史已覆盖的当前CP指针从精确源XML恢复，并与原compile descriptor SHA相等核验，明确区分恢复证据与当时保留的物理副本；最初误用old green03的131非空entries（split132含空尾项）缺Apache导致setup，未伪称132真实依赖。临时secret只通过既有同包LoopbackAuthority.secrets.get(engine)、app现有testClientSecret，写private STDIN；无反射resolver、public构造器扩张或读取主IAM秘密。JVM直传-cp及main，无子JVM @argfile，短tmp参数V:/.local/tmp；源码和resource descriptor保留实际构造。

真实联合正例由Portal→实际metadata query→独立main exchange→engine RunController/RunService→独立app AppController/AppService+PG→owned scheduler/FlowDriver/fullstatus组成。正例一次tenant记录1 run、2 attempts、52 materials、output a7、appPayloadReads1、worker0、legacy0；相同requestId重放仍同一run。真实same-origin authority id只有1，四个grant distinct：两次metadata query与两次main exchange（同requestId重放也独立授权），四个nonce独立；app与engine按各自operation/subject/ticket校验，不打印token/secret。不是503 stub。pointer switch/disable、过期原Portal、ticket错root/篡改/current narrowing、真实material scope越界、缺platform.access以及signed service/workload/integration身份等负例逐tenant验0 run/attempt/material。全局created计数可能保留此前正例1，不能把失败响应推成全局run0。

main在buffer后或callback前过期时成功payload0，但engine POST可能已接受，不能声称该请求PG0。slowreader实测fixture自己5500ms主动close，已提交73728成功字节、worker starts/exits均3；不把它写成0总字节或Servlet公共API能强行关闭恶意慢socket。

## 测试命令、真实阶段与当前适用覆盖

唯一四个selector类为GatewayIngressBoundaryTest、GatewayPublishedRunProxyTest、PublishedMetadataClientTest、GatewayPublishedChainPostgresTest。engine有限main只独立javac，最终setup04 compileExit0、134entries、testsExecuted0，未执行旧engine测试。此前PG使用setup03编译的相同逻辑fixture；其后AOSP只改排版（含debug行位置），最终setup04将格式后的源字节重新编译到冻结target，未将这次0测试编译冒称新的PG绿。每轮调用形式为node .local/iam-n4b2-gateway-published-chain/stage.mjs <唯一stage> <模块> <具名selector>；其command.json保存实际原命令、root cwd、时间和exit，raw.log、execution.json、XML、每次完整source及target manifest均冻结。没有同源码重复green拼总数，没有common179/N4a53/B1历史49/旧engine20/N1N2N3PG/全962。

|stage|actual Running类数|XML tests（含setup类错误）|通过case|进程exit|
|---|---:|---:|---:|---:|
|red01|1|1|0|1|
|red02|1|1|0|1|
|red03|1|1|0|1|
|setup04|0|0|0|1|
|guard05|0|0|0|1|
|guard06|1|1|1|0|
|proxy07|3|20|5|1|
|diagnostic08|1|1|0|1|
|diagnostic09|1|1|0|1|
|proxy10|2|17|15|1|
|diagnostic11|0|0|0|1|
|diagnostic12|1|2|0|1|
|diagnostic13|1|1|0|1|
|diagnostic14|1|1|0|1|
|diagnostic15|1|1|0|1|
|proxy16|2|4|2|1|
|chain17|2|2|1|1|
|boundary18|2|10|10|0|
|lifecycle19|2|9|9|0|
|final20|0|0|0|1|
|final21|3|8|8|0|
|final22|2|4|4|0|
|trailer-red23|1|1|0|1|
|framing24|1|1|1|0|
|trailer-green25|2|3|3|0|
|line-red26|1|1|0|1|
|line-green27|1|2|1|1|
|line-green28|1|2|2|0|

stage-ledger.json记录全部28阶段的actualRunning及每个XML testcase；coverage-ledger.json记录当前48个用例的最后有效green、当时完整源码/测试方法SHA、执行历史及后续适用理由，不能简单相加上表，也不声称最终全部源字节曾一次合跑。原metadata10断言全部保留；共享transport变化只复核19的fullBodyDeadline/targetedTicket及21的close具名case；后来的trailer配置25重验真实PG正例，最后单行加界28重验普通chunked，root明确不重跑PG25。

主要红/修正均保留：red01严格binding setup；red02实际落legacy闭锁500并非授权负例成功，red03实际IntegrationAccess.check expected0 actual1才是关键行为红；setup04/guard05多余括号编译0Running；proxy07/08/09一参JwtAuthenticationToken未认证，改现有二参认证构造；metadata fixture补regionA及合法Content-Type未弱化断言；diagnostic11加号被runner预校验拒，未进入Maven；proxy10/12..15真实EOF与self-interrupt；proxy16 history含schema计数setup及cleanup缺陷；chain17旧CP缺Apache setup；JDK客户端归一化裸?，raw链17真实403才证明empty query，server规则未放松；startup18 earlier-empty变体最初immutable UOE不算guard，19改actual坏FCP+具名code+listener事件对照；final20 checked IOException catch编译0Running；trailer23真红、framing24真实chunked、25真绿；line26/27/28如上。提取路径、截断输出与工具换行问题列入tool-events.json，无删除历史失败。

固定AOSP formatter只针对旧文件变化行/新文件全文，一次12Java，format.json与before-format物理source保留。它会拆分compile-time字符串，coverage中4个旧方法的文本变化仅此格式；trailer测试后来提取私有有限receiver helper，准确记录未重跑原调用，原25语义和新28 helper路径证据分别保留。其它逻辑差异见完整patch。

## 固定上游出处与限制

upstream/reading-manifest.json含47个归档Java及实际窄读范围、固定URL/档案SHA/Apache license notice；提取但未全文阅读的helper明确标记，不等同全库源码审核。实际窄读覆盖MVC WebAsyncRequest/DispatcherServlet lifecycle、Boot注册seen/actualaddFilter、Security6.5两层Composite FCP/getFilterChains委托/真实doFilter包装、Tomcat有限callback输出语义、client5 ConnectExec/MainClientExec/request.cancel及core chunked trailer parser/connection factory的固定方法。Spring6.2.7、Boot3.5.0、Security6.5.0、Tomcat10.1.41未升级。Java Temurin21.0.12+8，无未核验JDK源码身份声明。

client5 source753157B SHA1e56c35e4130f4917012cf0c8d021403bca2c805f60c9e689c65eac7d9c15312；core5 source830502B SHA2e3977831daf45041cf2f2bac9edd21a339e709b7b1e3c4acbebe6fc3cd3d654；三个实际runtime JAR SHA也在reading manifest。使用公开配置，未新增Apache框架或Flow适配层。有限装配固定debug=false不代表getFilterChains能检测任意未知装饰行为。common BoundedIamHttp JDK内部retry/close缺口本轮未改，不宣称whole-chain0retry、全部网络硬取消或物理socket总deadline。

## 冻结、重放、保护和资源

source-after377、target-after1879已物理拷贝到frozen及target-final并逐SHA核对；source-before369和target-before1814保留原before副本。target85差异=65新增20修改0删除，target-attribution.json完整列明43编译class（含诊断/fixture）、33测试XML/text、8编译metadata、1common测试夹具JAR及逐stage来源。未归因的target变化为0；不隐去javac遗留未再引用的diagnostic nested class。

source-fullcontext.patch是git diff --no-index --binary --unified=100000形成的完整上下文patch，证据目录先有* -text保留CRLF，不改工程根gitattributes。实际工程root cwd执行git apply --check及apply -p2 --directory=<own replay>，两者exit0；全部377 replay文件名/bytes/SHA与current/frozen source一致，不靠嵌套cwd跳过路径制造通过。replay-check.json保存命令及结果。源码和target冻结后没有再Maven或修改生产/测试源码。

保护before复用root已独立核117539基底，补采6298本轮此前新增B1证据/brief/备份等共123837；先按唯一最新checkpoint同步8索引afterSHA（内部5+外部3），没有误用B1历史rawbefore重复metadata。after一次完整物理重读123837文件并独立核28 runners及8最新索引。精确例外只有root当前state、本报告、postgres.log、两个own目录、六runtime目录，以及27个实际本轮stage log和每轮精确fresh-secret路径（实际不存在fresh-secret目录）；未宽排.local或stem。结果见protection-check.json，原before不改。

resource-evidence.json收集原raw中48个ready/PID/port/case/exit等marker，current-fixture-processes.json为有限main的当前CIM读证据，0匹配；子JVM直接main命令覆盖项目/V: CP形态，不依赖argfile内容。proxy16历史app在APP_READY/PID/port前setup失败且旧cleanup缺陷跳过其close，无法补造当时PID或退出观察；仅engine47000 exit1实际留存。当前0存活不补成当时已退出。chain17 engine55452 exit1和app8000 exit0确有raw，后续成功联合阶段双方均ready并STOP/exit0。没有追杀未知PID或操作主进程，所有Maven均已结束。历史OS commandline census未捕获，明确与源码ProcessBuilder/CP构造证据区分。

交接descriptor收录报告、brief、physical before/current/frozen/replay/source/target manifests、patch及actual root apply、stage/coverage ledger、upstream、target attribution、资源和一次完整保护的paths/bytes/SHA。没有编辑共享8索引/runner或自行宣告D4、GET status入口、全平台IAM rollout、RR1/RR2真实外部环境验证或full Task5验收。

最终保护实测：123837个protected文件全部物理重读SHA，28 runners、8最新索引一致；mismatch=0、unapproved additions=0。保护开始/结束时间及精确例外在protection-check.json。
