# 后续维护与最终整体审查输入

此表只收录已独立评审为非阻塞的维护项；不是功能缺口降级表。G6剩余D3–G/E、G2许可、真实IAM/集群/容量等尚未验收事项仍按实施计划/台账逐项闭环。

| 项目 | 来源与实际证据 | 后续处理 |
| --- | --- | --- |
| D1补丁CRLF空白噪声 | review-iam-stage-4-d1.md M1；freeze-commands.json实际apply stderr为1499行whitespace warnings，67源码字节回放一致 | 后续正常编辑保持/统一明确行尾，避免无意整文件diff；不改写当前冻结补丁、raw日志或伪称原输出无警告 |
| 旧配置负例堆栈噪声 | 原Task3 M1、D1 review M2是同一条维护债务；AuthorityConfigurationTest预期启动失败仍打印大量ERROR，但145最终XML全绿 | 下次触及该测试时窄捕获并断言拒绝原因，保留原负例和历史日志；D1新增启动负例已经这样处理 |
| V5 Flyway schema exists WARN | algorithm-process-live-v5.md及review-algorithm-process-runner.md限定事后复核；两个已存在schema警告为非阻塞 | 后续专用测试库启动可减少重复建schema日志；原V5并非无警告，不为该噪声重跑实际5场景 |
| 前端稳定布局采集 | review-frontend-mcp-live-layout.md中P3建议；实际390/1440修复及三表末列可达已独立核 | 后续浏览器采集采用稳定状态/布局等待，避免仅靠固定等待；不以该建议推翻已核页面证据或伪称新增浏览器测试 |
| D2补丁与配置负例噪声 | review-iam-stage-4-d2.md M1；初始patch30行CRLF警告，返修patch无新警告；最终common150仍有38处预期配置失败堆栈，原日志保留 | 与旧配置M1属于同一维护债务，后续触及测试时窄捕获/断言；保留原字节和历史警告，不为消噪倒跑 |
| D2中间绿色XML粒度 | review-iam-stage-4-d2.md M2；初轮部分中间绿只有rawlog；关键最终XML齐全；返修六轮已即时归档 | 后续runner每轮结束立即归档XML/rawlog/sourceSHA；不能用后轮XML冒充旧轮，不倒跑补历史 |
| D3a0新文件行尾 | review-iam-stage-4-d3a0 M2；原patch8处whitespace，344源真实apply一致；deadline补强patch无新警告 | 后续正常编辑统一新文件行尾，保留原patch/raw；本次不为格式重跑绿轮 |
| D3a0原输出与Surefire根路径 | review-iam-stage-4-d3a0 M3及复审；common155含38处预期启动拒绝/39行ERROR，初轮Flyway/旧unchecked保留；补强dumpstream精确SHA见报告，具名1/0实际已执行 | 与旧配置噪声同债务；后续查明并统一classpath表示，不照抄提示关闭JDK检查，不把raw WARN/ERROR=0当全部输出无诊断 |
| D3a0期限测试精度 | 原review M1；合法响应最后一字节闩锁+客户端失败/有限退出，真实具名1/0，复审Closed | 本项已闭合；保留旧误通过条件、原报告/审查前缀与新独立结果，不重复同码测试 |

| E1a Surefire与预期启动日志 | review-iam-stage-5-e1a M1/M2；六dumpstream绝对classpath/different-root和完整174轮39行预期startup ERROR，原raw/XML/SHA完整保留 | 与既有日志债务同追踪；后续定位Windows路径/窄捕获预期日志，保持非预期ERROR可见，不按提示禁用JDK检查，不为消噪重跑绿色suite |

| E1b归档与字节重放历史 | review-iam-stage-5-e1b M1：早期120秒窗口夹带XML已逐轮按实际Running排除；attempt01 apply字节失败与最终13行CRLF提示留档 | 保留原始失败和incidental审计，仅实际执行XML计数；局部证据属性不改工程/全局Git，不重跑旧绿色轮 |
| E1b及I1测试/日志与采样边界 | 原M2及复审Minor：setup/stub/数值断言与真实行为红分开；39条预期ERROR、Flyway及Surefire诊断保留；fix初采漏两个.local父制品，root随后独立核旧903备份/确定性重建audit，明示无直接事前audit SHA | 维持原raw/XML/输出和证据边界，不伪补事前采样，不按日志禁用JDK检查；I1两个期限边界已闭合，功能范围继续按D3/E验收 |

| D3b锁观测时序 | review-iam-stage-4-d3b Minor1；beforeSql latch早于真实SQL发送，单次pg_stat_activity存在偶发假阴性窗口；本轮真实Lock/blocker成功 | 后续fixture采用明确上限的有限观测等待，再推进Clock；保留finally释放，不做压力轮询，不为本审查重复绿色轮 |
| D3b工具链与采样历史 | Minor2：final344B Windows classpath dumpstream；原采样MinIO ENOENT无before、编译装配/异常映射断言/helper与root数组顺序/提前读重冻路径噪声均留档；after135304原exit1保留，root另核135302静态原件、两精确授权变化 | 不伪造事前采样/0差异，PG日志原前缀保持、原JAR窄读与root读相等；不禁JDK检查，不为消噪复跑；legacy仅controller分支、发送/全局IAM边界保持 |

| N1新增编译提示 | review-iam-stage-5-n1 Minor1；FlowMaterialStatusPostgresTest:93/111/538带泛型强转，final01 raw66–67为unchecked/unsafe Note；非WARN不等于pristine | 后续相关修改时用List<?>/Map<?,?>和局部typed提取处理，不动本轮冻结或为消噪重跑Maven |
| N1既有输出与证据局限 | Minor2：原RunAuthorityStore deprecation/ContextProjection unchecked，raw55–58；red02/red07噪声、red05尾计数未达、preflight事后整源适用性、root漏根pom inventory噪声均保留 | 最终69源码/执行逐项匹配；不将旧提示归为新增、不把早期归档冒称完整编译输入；实际51target仅engine，生产/真实链边界仍待 |

| N2预期负例日志 | review-iam-stage-5-n2 M1：I1后当前25/0的最终raw有15个预期负例WARN（原11+新4）；逐test XML映射已核，但测试未逐条捕获断言，不称pristine | 后续相关测试维护时局部捕获并断言类型/次数，对意外WARN失败；生产WARN保持，无整体静默，不重跑本轮绿色 |
| N2 Surefire路径诊断 | M2：修复红/绿各一份344B dumpstream，Manifest-JAR绝对classpath/other has different root；实际fork/两XML25例完整，精确stage/路径/SHA在fix handoff | 保留原件并调查本机路径映射/manifest，不能采用提示中的禁检argLine或为本轮消噪改runner/POM；无新增engine阻断问题 |

| N3修复报告勘误 | I1独立fix报告的green末态attempt1错误；实际final01 metadata-expiry为attempts2/scopes52/createdCalls0，负例检查时attempt0的源码断言仍绿 | 同reviewer复审/root签收明确勘误；原报告/hash保持，不修改业务或为文字重跑 |
| N3保留诊断 | 原15执行阶段13份、修复两阶段2份共15份344B Surefire跨盘Manifest-JAR提示；实际Running/XML逐核，unchecked/预期worker及55P03错误保留 | 不采用禁检argLine/全局静默或为消噪重跑；相关工具维护时精确调查。固定源archive basename等作者/root工具路径失误原件保留 |
| N4发布元数据传输待核 | N3真实红显示JDK HttpClient内部GET重发窗口；gateway PublishedMetadataClient已在N4b2按固定Apache闭合；common BoundedIamHttp仍用JDK，旧证据不能扩大为整链内部零重试 | N4b按实际gateway受影响链具名评估，必要时先批准最小固定依赖例外；本轮仅engine成熟传输闭合 |

| N4a兼容边界 | 独立JVM仅设test/profile不再可issue；必须经过实际严格validated Environment登记。未登记user/integration仍旧非IAM兼容；一旦合法IAM三API均不可逆关闭 | 已按设计验证，不是未修缺陷；新增相关测试用独立child保持classloader状态隔离，不引reset API或按顺序碰运气 |
| N4a保留诊断与工具拒绝 | 3轮共4份344B路径诊断；PowerShell证据脚本写入被策略拒绝blocked by policy且未执行，改apply_patch安全创建后单独node。原tool-noise/excerpts说明命令显示截断 | 不改POM/runner/argLine消噪，不声称拒绝payload完整；分批48+25不冒称一次73绿 |

D3a0具名证据限制：最初前缀过滤漏采原brief SHA，无法重建，root已明确接受该历史可验证性边界，未把该文件计入8954项保护。后续采集只排除目录加斜杠；原manifest/历史不重写。功能缺口仍按后续D3/E验收，不在此降级。

最终整个开发分支审查必须读取并逐项裁定：是否应在交付前修复、可接受的保留理由或后续维护归属。未经最终审查，不把本表当作已清零或永久豁免。

N4b1期限证明及诊断：本组件C0/I0/M0；最终49/0、prototype红29/4F与setup Running0分别保留。两份344B Surefire和已有编译提示未静音；before重复索引metadata及patch换行/跳路径问题已透明纠正并root复核。不重跑已绿49，不把组件当gateway/fullTask5；下一B2真正JSON发送/异步生命周期，common JDK retry/close和Servlet慢端物理限制保留具名验收边界。

### G6 N4b2实际门户创建链与异步安装闭环（2026-10-21）

原I1 late-async安装/close与M1测试名称均已按独立复审闭合，初审原文不改；只关闭该有限差异。新报告：review-iam-stage-5-n4b2-close-install-fix.md。作者实际红/绿及适用性见iam-stage-5-n4b2-close-install-fix-report.md，五个selector分批3＋2有效覆盖，不是最终同源码一次五绿。

| 具名观察／边界 | 实际证据 | 后续处理 |
| --- | --- | --- |
| N4b2保留诊断 | 修复五份344B Surefire Manifest-JAR跨G:/V:根路径提示；既有PG deprecation/common unchecked，原阶段诊断也保留 | 相关工具维护时定位路径表示；不禁JDK检查、不改runner/POM来静音、不为消噪重跑已绿 |
| N4b2历史生命周期边界 | proxy16 app历史PID/port/exit缺失；red02清理suppressed失败；green04真实context/HttpClient/caller结束与当前精确JVM0分别记录 | 不倒推历史有序退出；不处理未知PID；保留原始证据 |
| 有限Servlet/传输保证 | slowreader73728成功bytes/5500ms自关、未归属async安全空503；common JDK内部retry/close尚待 | 不把FutureDone/active0当worker或实际context完成；不承诺撤回已提交字节/wholechain零重试/恶意peer物理硬断 |
| 门户与完整覆盖后续 | D4a session、D4浏览器、独立GETstatus/SSE/RR1/RR2、其余Task5/F及真实IAM仍未完成 | 按依赖持续开发，生产门禁保持；50个当前@Test是分批适用清单，不是一次50全绿 |

最终整个开发分支审查仍须逐项裁定本台账，不将观察当永久豁免或全零缺陷签收。

### G6 D4a门户会话与签名时间区间闭环（2026-10-21）

独立初审I1签名时间区间遗漏已闭合，聚焦复审Spec compliant / Task quality Approved；初审与原失败不改。原报告及修复报告在同目录，root修复审计.local/iam-d4a-fix-root-check.json已通过。当前25个唯一用例按阶段适用，最终修复仅定向3条绿。

| 观察或剩余边界 | 证据与处理 |
| --- | --- |
| 非阻断诊断M1 | 保留既有Json deprecation/common unchecked和本阶段21＋修复2份344B Manifest-JAR跨根路径提示；维护工具时定位，不禁JDK检查、不为消噪重跑。见.local/iam-d4a-root-diagnostic-notes.json。 |
| legacy启动覆盖 | Optional构造、有限实例与扫描结构已核；完整GatewayApplication legacy启动本轮未执行，不能扩称全启动验证。 |
| 输出与外部环境 | 已提交字节不撤回；没有恶意慢peer物理硬断保证，没有真实IAM/模型/本体/K8s联合环境验证。 |
| root工具记录 | 首修复审计误用replay字段名中止；保留失败脚本，404项已完成source核验据实重建，续核剩余项通过，无重复测试或伪造事前缓存。 |
| 后续覆盖 | D4b前端、独立GETstatus/SSE/RR1/RR2、其余Task5/F和真实IAM继续推进；T01唯一整体完成。 |

最终分支审查仍须逐项裁定诊断及覆盖边界，本有限签收不替代整体验收。

### G6 D4b前端门户及四项修复闭环（2026-10-21）

原D4b初审I1（附件当前401未统一失效）、I2（reader.cancel拒绝覆盖授权错误）及独立补审I3（退出未退订/失效后旧宿主自动重连）、I4（wall回拨延长UI身份）已由两波修复关闭；最终聚焦复审Spec compliant / Task quality Approved，无新增Critical/Important。原review-iam-stage-4-d4b.md、review-iam-stage-4-d4b-lifecycle-supplement.md和所有红/设置失败记录保留。root三波有限证据审计通过，逐波数字见进展和独立报告，不汇总为最终全套测试。

| 观察或剩余边界 | 后续处理 |
| --- | --- |
| M1 chunk警告 | 两波final-build-admin/output.raw:18、final-build-webapp/output.raw:11均存在>500kB警告；测量首屏成本后决定拆包，不提高阈值。 |
| 宿主/时钟/页面 | 真实外部provider/IAM与签名后端联合浏览器链、实际dev新进程未验证；初始时钟/冻结标签页无即时清除保证；旧工作区1366px最小宽度仍在。 |
| 未来RR1事件选择器 | 只读架构核查发现现permit对材料id/version与sourceAuthVersion的固定预期不适合真实PG event row id/全局revision/逐事件授权版本。启用RR1前必须以可信nextEventRef绑定run/row/seq/snapshot并保留真实MaterialRef/provenance；不得改写历史版本迁就fixture。见iam-next-run-observation-seam-proposal.md。尚未开放外部独立GET/SSE，此项进入后续切片验收。 |
| 既有网络边界 | common JDK路径重试/关闭边界、恶意peer物理硬断及全链瞬时撤权仍按既有未完成项处理，局部Apache POST隔离证据不外推。 |
| 外部与整体覆盖 | PORTAL独立GETstatus/SSE、RR1/RR2、CHAT/批准/全恢复、Task5其余/F及真实IAM待；T01唯一整体完成。 |

最终分支审查仍须逐项裁定诊断与覆盖边界；本有限签收不替代整体验收。

### G6 O1a独立运行元数据接缝验收（2026-10-21）

O1a独立复审Approved，无Critical/Important；root641项有限审计PASS。详见同目录review-iam-stage-4-o1a-run-metadata.md；原报告/失败/重放链不改，以下Minor须进入最终分支审查，不能因局部通过静默丢弃。

| ID | 当前观察与后续处理 |
| --- | --- |
| O1a-M1 | RunObservationMetadataHttpPostgresTest:52/119 static HttpClient未显式close；四轮JVM已退出，不能称每个client主动关闭。后续fixture维护显式关闭并校正文档精度。 |
| O1a-M2 | 同测试:171/173的SQL allowlist投影段.*过宽，未来r.snapshot/r.inputs可能误通过。当前生产SQL经独立源码与实际语句核为control-only；后续固定允许列/表达式或可靠识别保护列，加入直接保护断言。 |
| O1a-M3 | 保留既有unchecked/Flyway提示及采集中文replacement字；.local/iam-o1a-root-diagnostic-notes.json给定位。See dump files(if any exist)只是失败模板，不是实际dump证据；不禁检查或重跑美化。 |
| 可信存储/发送边界 | metadata-only对自洽合法resource_digest替换明确200/payload0；O1b真实读snapshot后核candidate关系。O1a终检止于service return，不能称已覆盖后续MVC首次wire；新只读status必须单独实现真实首写门禁。 |

当前只完成内部metadata接缝，O1b/O1c/O2、RR1/RR2及完整D/E/F/真实外部待；T01唯一整体完成。原D4b chunk等历史Minor仍有效，最终分支审查须一并裁定。

### G6 O1b独立只读状态及审查修复验收（2026-10-21）

O1b原复审Needs fixes C0/I2/M5，后续两文件窄修复复审Approved C0/I0；root原799与修复122项有限证据审计PASS。原review/report/失败及错置XML均保留，新的historical-evidence-supplement单独更正。详见review-iam-stage-4-o1b-review-fixes.md。

| ID | 当前处置与继承边界 |
| --- | --- |
| O1b-I1/I2 | 已关闭：真实数组容量超限→私有固定2MiB；不合法source/target正例→from/to及真实持久化前DAG证据。先2F0E、后受影响类一次19全绿，不重写原17结果；原门禁/proof/RR审查按未改接缝继承。 |
| O1b-M1/M2 | 解释及生命周期已处理：green01错XML继续原地保留但排除，green02无XML，均不补跑旧档；forward后结束原dispatch，新raw无Stream closed。closed不表示历史XML已补回。 |
| O1b-M3 | 固定Jackson2.19 decorator弃用、RunAuthorityStore弃用、旧unchecked及Windows中文编码损失保留。后续改善诊断不能换全局mapper或抹raw；partialIO WARN为明确注入/断言行为。 |
| O1b-M4 | appCode/root、双versionId、appVersionNo/key等post-digest关系分支未逐项动态覆盖，静态检查仍在；直接改immutable payload先触发digest，不能虚报命中后续关系，禁止协调重写可信历史伪作证明。 |
| O1b-M5 | controller/本次fixture已格式化，未触及TraceStore386–460、RunService1590–1611压缩逻辑仍是可读性债；最终分支审查继续裁定。 |
| 原O1a及更早Minor | O1a staticHttpClient/宽SQL断言、D4b chunk等仍保留原处置，不由O1b通过自动消失。 |
| 输出及验收边界 | 预分配2MiB加最多2MiB副本不等于全请求堆有界；实际128字节已发无法追回，MVC close拒绝不等于强制peer硬断。合法FLOW存储seed+pre-persist gate不等于完整public IAM受理链。 |

当前没有O1c最后一跳/O2/SSE/RR/完整Boot/真实外部验收，T01仍唯一整体完成；原全局覆盖未签收。

### G6 O1c网关只读状态及取消边界修复验收（2026-10-21）

O1c原审查Needs fixes C0/I1；原冻件有限证据审计PASS2192。I1仅两文件窄修后复审Approved C0/I0，修复有限审计PASS2398。两轮原件、失败、helper诊断、独立source归属correction均保留。

| ID | 当前处置与边界 |
| --- | --- |
| O1c-I1 | 已关闭：新observation handler所有终止异常先cancel再由Apacheclose；inner read仍先cancel再input.close。声明超限、状态/头/类型提前拒绝有真实stalled peer定向证据；旧GET/POST语义不改。 |
| O1c-M1 | 已澄清：26/31 historical source字段是finalSource；用各command.sourceBefore与actual XML对应执行身份。31/3跨15命令，非最终所有源码单套通过；中间全文缺失不逆造。 |
| 新复审Minor | 本轮无新增Minor；原O1c-M1以独立归属更正处理，旧O1b-M3/M4/M5与更早项仍沿原台账保留 |
| 原O1b-M3/M4/M5 | 弃用/unchecked/编码诊断、逐项post-digest关系动态覆盖、未触及旧TraceStore/RunService可读性债沿原处置保留，不由O1c自动销账。 |
| 原O1a及更早Minor | staticHTTPclient/SQL断言宽度、D4b chunk等沿原记录继续有效。 |
| 物理及覆盖边界 | partial已发字节不能追回，实际below-stream unknown；取消不保证blocked peer硬断。Q/M容量限额不表示全堆有界，有限receiver不等于fullBoot/真实外部IAM。 |

O2/SSE/RR及全G6/F仍待，T01唯一整体完成；仅本切片获准，不提高全局完成率。

### G6 O2a A事件响应公共授权及生命周期窄修验收（2026-10-21）

O2a A 按限定范围 Accepted：common 固定 PORTAL events response-current、私有跨线程 Capture 与生命周期资源约束完成。source589=backend397+frontend192，相对O1c为5改4增；后续窄修仅3既有文件。固定签名绑定、fresh current、first-frame单调55秒上限、generation和material前后本地检查有效；历史执行ceiling/origin不强行等同新观察会话，历史材料来源仍由B的真实PG读取建立。receiver继续STATUS-only，本项不代表engine/gateway公开SSE或全局IAM完成。

原fresh review的I1关闭漏交取消、I2未发布reservation泄漏、I3终态task遗留timer全部解决；M1按实际operation<=grant关系准确改名并披露没有独立grant-first覆盖。仍为2workers/queue64/64slots/250ms watchdog；watchdog拒绝时不执行callback，固定slot保留任务待本地派发，普通closer可锁外执行且实际退出后归还额度。非watchdog调用者可能被自有callback阻塞，不承诺物理关闭或即时撤权SLA。

测试按实际阶段保留：原final01八类85方法为72PASS/13ERROR，旧ServiceAuthorizationTest的13项输入冲突逐名待迁移；随后原green04仅Profile22/22。窄修red01为5FAIL/0ERROR，定向green01为5/5，watchdog补充red02为1FAIL/0ERROR，唯一整类final01为Profile28/28。没有将多阶段相加成一次suite，也未重跑旧六类/PG/engine/gateway/POST/SESSION。Loopback未改，ReceiverClosed沿用原实际闭门证据。

代码依据：docs/evidence/iam-stage-4-o2a-a-portal-events-response-report.md、iam-stage-4-o2a-a-lifecycle-fixes-report.md；独立复审docs/evidence/review-iam-stage-4-o2a-a-lifecycle-fixes.md为Approved C0/I0/M0。根原有限审计.local/iam-o2a-a-root-check.json为5phase/2464physical PASS，窄修.local/iam-o2a-a-fix-root-check.json为5phase/2153physical PASS，分别继承而不合并计数，未重测/重放。正式source清单取修复目录source-after.json，正式target取final/target-after.json，logical2023包含历史诊断，不是纯classpath；新compiler class为0。

归因纠正完整保留：原194诊断源中183历史/11本轮新增，旧logical2004已含908历史dump；旧13错误名单应含actorProjection而health通过，按root两份correction与实际XML为准。窄修4新dump独立归档，误吸收的2027及原physical-check作为superseded元数据保留，正式清单缓存组合为2023。作者及root的helper错误、red01人工blocker超时诊断均保留，最终无相同worker诊断；只续行未成功阶段。三个历史/修复私有replay各自实际check/apply0/0，原件不改写，没有工程commit。

下一顺序：先单文件迁移ServiceAuthorizationTest并完整验14方法，恢复真实合法STATUS/cancel/monitor-forward覆盖；再以Accepted A接口冻结B受限PG/material reader（真实writer超过64父、先校材料再读payload），随后C安全SSE、O2b真实父子响应链。RR/CHAT/F/真实IAM及运行容量仍待独立验收。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12保持进行中；不新增时间安排，不改v1.2原设计与四契约。

O2a A本项原C0/I3/M1已全部关闭；旧O1b的M3/M4/M5等既有Minor沿前节继续保留。旧Service13项是明确后续测试迁移缺口，未静默豁免或计为通过。

### G6服务授权测试输入迁移验收（2026-10-21）

新独立工程内ServiceAuthorizationTest单文件迁移Accepted：全部14原方法名和实质断言保留，health原文不变；source589=backend397+frontend192，仅1测试文件修改，0新增删除，无production/fixture/catalog/POM/其他服务改动。合法STATUS使用run-snapshot及可信已知actor的有限fixture登记；这不是PG admission/engine业务存在证明。integration STATUS明确拒绝，cancel通过真实exchange/authorize/resume及POST别名验证origin不洗白；统计positive通过真实gateway→monitor同frame retained/forward→engine secondary HTTP，成功票均非改签制造。

唯一实际命令iam-stage-service-migration-final01完整执行ServiceAuthorizationTest，exit0、Running仅本类、XML14/0/0/0。原13项输入冲突的迁移待办关闭；原A历史85=72PASS/13ERROR保持原件，Profile28/Receiver及其他旧类/PG/engine/gateway/POST/SESSION不重跑，不把新14项拼入另一次全量suite。同步2/async3在线调用计数、双run真并发、工作负载与失败关闭矩阵均保留。

独立审核docs/evidence/review-iam-stage-4-service-tests-migration.md为Spec/Quality Approved C0/I0/M0；root .local/iam-service-migration-root-check.json为5phase/2034physical PASS且首轮审计完成。报告docs/evidence/iam-stage-4-service-tests-migration-report.md及同名证据目录封存唯一源码pre/post、raw/XML、187实际compiler/resource/Running overlay和1833 own leaves。正式source-after.json SHA01121a4739ff5a0299cce9e5ecaa0ab5d433cd0be177ffd5d03f9410c67fe346；target-after.json SHA244535cb02df60585217088e321f2b6ac44aaca20de22821a080d66d41967e20，historical logical2023，不是纯classpath。新dump1单独归档，不吸入正式logical。

私有589重放实际顺序保留为首次LF patch check1、CRLF byte-exact补丁第二次actual check0、首次actual apply0；589名称/bytes/SHA严格相同，apply后无copy。没有忽略空白、修改工程.gitattributes/.git或改变已测试源码；只恢复未成功重放元数据，成功source/target阶段缓存，14方法不重测。此前A的replay和诊断归因纠正全部只读继承。

下一依赖顺序保持B受限PG/material reader→C安全SSE→O2b真实父子响应链。B按已Accepted Capture接口补最小本地initialCursor数据接缝，使用response.requireLive(Capture)而不在PG事务内隐式联网；真实writer大于64父、完整材料摘要及权限先于payload读取、双workspace实际消费退出后归还均须独立验收。receiver仍STATUS-only，公开SSE/PG材料读取尚未交付。17需求/34任务/30验收未全闭环，T01仍唯一完成、T09/T11/T12进行中，RR/CHAT/F/真实IAM和容量待验；原v1.2/四契约不动，不安排时间。

旧Service13项测试迁移已关闭，实际14方法全绿且fresh review C0/I0/M0；原失败历史及root错误名单纠正保留。旧O1b M3/M4/M5等既有Minor不因本项关闭而消失。

### G6 O2a B受限事件材料读取验收（2026-10-21）

独立新工程的B受限PG/material reader及借用输出修复已Accepted。最终source593（backend401/frontend192），相比前一Accepted589新增4文件，B完整范围8文件；后续借用修复只更新其中3文件。固定public event256KiB、完整row1MiB、metadata/root各8MiB、记录/直接父4096及model64MiB/canonical8MiB/总192MiB相交；原两个permit覆盖准备、pending与实际consumer finally，active逻辑close不提前归还。自建只读RR内先完整元数据/scope/current校验再payload，无authority联网；真实15节点writer样本含67直接父、68scopes/H2180。保守预算不是精确JVM heap或生产容量证明。

独立审核初次C0/I1/M2的三项已修复：输出前同ledger计费独立byte[]副本，调用方不能改写reader原数组；payload/labels共同guard拒绝交叉重入并在异常后恢复；补root字段名1048576/+1机械边界；原报告编译提示归属由追加修复报告纠正。最终review为Spec/Quality Approved，C0/I0/M1仅既有编译提示及路径乱码，原Needs fixes/错误原件保留。报告为docs/evidence/iam-stage-4-o2a-b-material-reader-report.md及iam-stage-4-o2a-b-borrow-fixes-report.md，审核为review-iam-stage-4-o2a-b-material-reader.md。

验证按实际阶段分别成立：原final01六类65=64PASS/1旧Initial setup ERROR，原final02仅B两类31通过；借用修复RED01实际4项2assertion失败，GREEN01为Budget14+新PG3共17通过，最终+24对齐slack后GREEN02仅新PG3通过。没有把阶段拼为一套最终17或全回归，也未重跑旧65/PG18/Service14/Profile28。root原B7phase/5836 physical及修复4phase/2170 physical各自有限审计通过，不加总为全仓覆盖；两代原B和本次修复的新patch均实际首check0/apply0且私有源码593名称/bytes/SHA相同，成功阶段均缓存，无重复运行。

最终源码清单.local/iam-o2a-b-borrow-fixes/source-after.json SHA83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20；最终target-after.json SHAa23b333e7e0efd1f4d74cfac75804d9c791a3f9bbc4905c3ca92b132009e1fac，为历史逻辑2060项、实际新overlay464，不是纯classpath/全target树。原14+3及本修复3个新dumpstream分别归档排除；旧历史诊断只读继承。原内部backing58B测量属于修复前epoch，修复后sink收到copy，其长度不能再解释为内部数组身份；新增输出上界由同ledger预收费证明。

下一依赖先处理InitialMaterialScopePostgresTest单方法输入迁移，再C同RR保护窗口/游标/真实Servlet SSE，再O2b真实父子响应链。Initial失败来自STATUS+app-version在exchange阶段被既有selector拒绝，尚未进入read断言；必须建立合法STATUS/run-snapshot真实frame后验证其不能读取initial材料，不能只改成断言exchange拒绝。此前Service13项迁移已关闭，不因这个独立旧用例重新打开。

C尚未开启：B Optional.empty不提供终态或游标窗口证明，common receiver仍STATUS-only，安全HTTP/SSE与实际getter/write/flush/complete生命周期待验。新增具名风险：历史event物理清理后scope仍在，会触发严格locator/执行完整性拒绝；C只可在明确范围内验证终态保留窗口观察语义，生产retention执行链/活跃run清理兼容仍待独立实现验收，不能用测试DELETE冒充生产清理。C准备与retention风险文档是只读准备，不是已交付功能。

17需求/34任务/30验收继续未全闭环，T01仍唯一完成、T09/T11/T12进行中；RR/CHAT/F/真实IAM及K8s/全容量仍待验。只使用隔离端点，不关联相邻旧实现；v1.2/四契约保持原样，不安排时间。

B原I1/M1/M2已关闭，当前仅M3编译/乱码噪声非阻断；旧Initial单方法迁移与C retention/真实HTTP尚待。旧O1b M3/M4/M5等先前Minor保持，未因本项消失。

### G6 O2a Initial材料测试迁移验收（2026-10-21）

InitialMaterialScopePostgresTest单文件迁移Accepted：12个原方法名/顺序及其他11方法原始字节不变，仅目标方法和7个必要imports修改，生产/fixture/runner/POM/契约0改动。旧STATUS+app-version交换保留403负例；新的阳性前提使用真实已受理run的owner/publicId/已接受snapshotSha、真签JWT、实际GET/三参status HandlerMethod和ServiceAuthorization frame。retained proof在同frame读前后验证，生产requirePurpose先于owner/root比较精确拒绝403/RUN_AUTHORITY_DENIED；payload0，控制SQL允许且完整材料SQL未发生。这个迁移待办关闭；原B历史65=64PASS/1setup ERROR不重写为65绿。

唯一实际命令iam-stage-o2a-initial-migration-final01完整12方法，exit0/0F/0E/0S；没有重跑原65、旧B PG21/Budget14、Service14/Profile28或C。独立review docs/evidence/review-iam-stage-4-o2a-initial-migration.md为Spec/Quality Approved C0/I0/M1，M1仅既有编译INFO/路径乱码/Surefire环境提示；root .local/iam-o2a-initial-migration-root-check.json为4phase/2157 physical有限审计通过，1682自有叶子、593私有源码真实首次check0/apply0且完全相同。source593仅1测试delta，SHAe9791749f842e9403604f5fc3ff354e596536746577c65eb7b610374d7d69d16；target SHA9dd29bc3bd986d324a94dd100fa1c30e46518c715c0215aba555e8a644c4e98d，462实际compiler/resource/Running overlay、历史逻辑仍2060；新dump1单独归档。成功阶段缓存，物理检查/测试/replay不重复。

报告docs/evidence/iam-stage-4-o2a-initial-migration-report.md及同名证据目录保留raw/XML、单文件pre/post、逐方法字节核对和准确执行源身份。测试原raw在执行后一次移入本任务归档，original路径是历史地址，归档bytes/SHA相同；不补造原路径。本次仅handler/security seam用途隔离验证，不宣称完整status HTTP/JSON或SSE。B受限读取/borrow修复结论继承，旧Service13项迁移保持关闭。

下一依赖为C同RR完整state/cursor-window+可选event、唯一2permit owner、真实Servletasync getter/write/flush/complete及共享frame配额，再O2b真实父子响应链。C使用最小本地generation查询，真正superseded才有限重读；不可一般403重试。历史event清理观察仅在明确的终态窗口与完整scope/current校验下处理，原B严格读不暗改；production retention执行及active清理兼容仍单独待验。当前C仍未开放，准备稿不算实现。17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中；RR/CHAT/F/真实IAM/K8s/全容量待验。v1.2/四契约不动，不安排时间，继续使用隔离联调端点。

Initial唯一旧输入冲突迁移已关闭，12实跑且fresh semantic/root byte审计通过；历史B65/1ERROR保留，工具噪声M1及先前其它Minor不消失。

### G6 O2a C真实engine事件流及评审修复验收（2026-10-21）

O2a C限定切片Accepted：真实签名E/trusted decoder/ServiceAuthorization/handler→RunController→PortalRunEventsService→同RunEventReader真实PG，完成engine一跳PORTAL/pure FLOW保护事件流；同client opening，run-snapshot与STATUS/EVENTS双向purpose，严格真实游标来源/请求/默认拒绝和unknown/other-owner404。完整state11字段、state/event父并集和原root/metadata预算在同一readOnly RR核验，terminal/cursor/retention窗口来自同轮完整材料，不拼裸scalar。原A生命周期、B readNext严格borrow/copy与legacy接口保持。

专用Servlet async单owner覆盖实际getter/write/flush/complete；opening真实窗口预检后归还B/frame，queued只持小owner/slot/holder/原cursor，RUNNING完整fresh读避免排队饥饿。原B2permit、共享Cslot和16MiB池，完整262245B backing另8192B scratch先计费，两个阻塞owner逐实际退出才恢复；原55秒/源/grant/op硬界不因续核重置，观察断线不cancel后台run。机械long19/type64边界与PG int/varchar32可达范围分别记载。

独立首审C0/I2/M2的I1/I2已按新修复证据和同一评审人复审关闭：consume入口及最终guard失效的终止complete覆盖实际持有B/frame直到调用返回；健康superseded有限重读保持旧实际退出后再完整读取；fresh RUNNING未提交容量失败走HTTP映射，仅已提交且current live才有界reset。本轮先有入口guard、最终guard、未提交容量三项真实1F RED，focused3绿，再唯一最终四类24/0/0/0（Lifecycle11/Capacity5/Http6/Budget具名2），所有新阶段raw/XML/epoch即时归档。 原C最终六类29/0/0/0为修复前真实冻结历史，未扩大宣称修复后重跑原29或全后端。

作者报告docs/evidence/iam-stage-4-o2a-c-events-report.md及新修复报告docs/evidence/iam-stage-4-o2a-c-fixes-report.md、独立review docs/evidence/review-iam-stage-4-o2a-c-events.md保留全部实际阶段。root原C五phase/2373 distinct物理审计及新修复审计 .local/iam-o2a-c-fixes-root-check.json分别通过，首次私有raw patch check/apply及最终source逐项一致；不重复旧测试/PG/replay或扫描旧target。最新source600（SHA44192fbf077285773a9c8d35c9e89c1e6e9f1958184a16aee81ae0c211da9b54），最新target历史逻辑2096（SHA65646da310fefd754a22b1284854291de3447551ff801a2b8da9f150473eebc9），新overlay/diagnostic按各自报告分开，历史逻辑清单不是当前干净classpath。

M1真实工具/容器负路径日志噪声保留，不能称零警告/零ERROR日志。M2早期red/http-red/http-red-route/http-red-mapped/first-green五阶段仅raw，XML及完整source epoch缺失是已发生的历史归档偏差；root依据后期八阶段完整epoch/XML及新修复针对性证据接受本限定切片，但不认可缺失的早期精确XML/epoch已被验证。禁止补造或重跑冒充原阶段，所有新阶段必须即时归档。

终态测试仅DELETE自己真实writer完成后的event物理行，验证清理后PG观察语义；production retention job、active-run清理可继续及writer/replay/去重全平台兼容仍未关闭。Gateway/O2b G/E关联、TLS/私有完整material packet、真实gateway独立验证和browser SSE尚待后续D→E→F逐门验收；本轮不代表全IAM/CHAT/chunk/skill/model、生产容量或socket物理强断/撤权SLA。旧B65/1setup ERROR等历史不重写。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12进行中；RR/CHAT/F/真实IAM/K8s/全容量待验。v1.2/四契约不动，不安排时间，继续隔离联调端点。

C首审I1/I2针对性修复和原reviewer复核关闭；M1工具噪声、M2历史raw-only归档偏差显式保留，旧Minor及production retention/active清理待验不消失。

### G6 O2b D common父子授权与私有准入验收（2026-10-21）

限定D Accepted：真实original Portal decode与Q exchange，独立固定G exchange/actual authorize/open，opaque G holder，再经authority一次parent-child association得到E exchange、实际authorize/同receiver frame child open、生产受限receipt导出及G一次真验；双方check分别验证各自fresh current。G固定gateway.run.events.observe/private profile，只允许私有G验证入口，不新增publicroute或放宽requirePurpose。旧A standalone明确拒child marker，新child严格要求实际签名ticket/current一致的私有证据；没有用public DTO/Actor造权。

Opening自decode/Q原时点计5秒及原55秒硬界、自动到期、同64slot有限runtime转移与actual active退出后释放；不复制scheduler、不把短current或新hop重设硬期限。authority有界64parent/一父一子原子状态，过期前不为容量逐出防重放记录；G/E source/subject/run/cursor/完整关联一致、current版本和roles可独立。receipt为actualENGINE_CHILD owner/live只读≤4096ASCII JWS，不是G权限或后续任意字节认证；continuation不跨consumer。

D最终六类只一次实际87 tests=86 passed/1F/0E/0S（exit1），新G18+child22与原A28/closed2/Service14均通过。原失败是继承selector断言，永久保留原87/1F；后续唯一测试方法体迁移另一次完整3方法实际3/0F/0E/0S（exit0），fresh review Spec compliant/Approved C0/I0/M0。仅测试delta1、production/fixture0，三方法名/顺序及其他两方法和方法外bytes保持。不得把两阶段改写成87全绿。D审计5阶段6476 distinct/own6245，迁移审计5阶段1835 distinct/own1622，原始命令/Running/XML/source epoch与单次不同patch隔离回放均有限核对。 独立review docs/evidence/review-iam-stage-4-o2b-d-common.md为Spec/Quality Approved；root有限审计分别为 .local/iam-o2b-d-root-check.json、.local/iam-o2b-d-selector-migration-root-check.json。最新source603（SHAab0310201aff0a59e87f73b0692bf6e89b050591d7f33c2b4f099da3a97283c4），target历史逻辑2118（SHAf04cd892a8842083b1d546795a5ce146aa3b4edec794f90f3fa0f2e58035a893），有限overlay仅实际common compiler/resources/fixtureJAR/actualRunning报告，新diagnostic分开；历史逻辑不是当前全后端classpath。没有重跑原C29/修复24/旧B65、PG或全后端。

D独立review原C0/I0/M3中的M1继承selector断言已由上述独立迁移关闭；M2原3035B early replay独立check/apply raw未即时落盘，保持原现场和证据缺口，最终不同patch一次603完整回放不补旧记录；M3本轮D compiler清单JSON-Buffer归档格式不一致，冻结原件可无损解码并与overlay原始bytes一致，迁移新清单已按原字节归档。M2/M3仍作为非阻断Minor保留，未重写历史。 原C M1工具诊断/M2早期五阶段raw-only XML/epoch缺口及所有历史Minor继续保留，不补造、不重写历史。

当前只common协议和受控issuer验证，不等于生产authority已支持/部署，也未开放gateway实际EVENTS。下一E：真实engine child→同RR完整材料私有packet，保留原complete-before-release及16MiB池；再F：固定engine TLS身份/CA/必要mTLS、完整packet与独立G材料验证后公开SSE。签名关联/摘要不替代engine来源认证，gateway不把可信PG事实扩大为独立重算所有root payload。production retention/active prune/writer-replay兼容、RR/CHAT/F/真实IAM/K8s/生产容量继续待验，不承诺socket物理强断/撤权SLA。17需求/34任务/30验收未全闭环，T01仍唯一完成，T09/T11/T12进行中；v1.2/四契约不动、时间不安排、新工程隔离继续。

D剩余问题按本轮独立review/验收裁定显式保留；旧C M1/M2、retention/active清理和其它Minor不消失。

### G6 O2b E引擎child与完整材料私有报文验收（2026-10-21）

限定E Accepted：真实G/E签名链与engine ServiceAuthorization/filter/frame，固定private Accept只走实际child open，普通表示只走standalone open；双向拒绝/no fallback，非IAM私有在legacy前拒绝，同三参handler/action和debug/v1限制保持。HELLO从actual owner/live/ENGINE_CHILD production出口取得receipt，首次fresh RR全部验证之后与首WINDOW同consume输出。receipt仍不是G权限或任意字节认证。

私有v1固定24B APEV prefix和44B APCM COMMIT，10键header、完整14键WINDOW、HELLO/CAPACITY，header8KiB/metadata8MiB/payload256KiB及checked长度交集。完整initial/provenance/三root refs/state/event refs/revision/all scopes/完整parents/直接父/11键state/5非payload事件字段/window来自同一原RR；历史opaque grant/operation ID保留，可用service票/continuation不出wire。采用同borrow两遍受限count/hash/UTF8，第二遍等长等hash并live才COMMIT，不分配完整大wire buffer。

沿原B192MiB账本对新增模型/scratch/descriptor/隔离copy分配前收费，保留16MiB response池、270437B frame、2reader permit和有限原runtime；原B读者/普通C路径保持。entry/action/final guard失败也先actual complete，再释放B/frame/slot；阻塞get/write/flush/complete仍持额。首/游标reset/必要终态为完整材料WINDOW，后续活动idle可无包，EOF不证明terminal；partial不advance，不跨generation拼包。

原E独立源码epoch的最终7类45项通过（新增21、受影响C24），保持原运行及90459B patch/一次check-apply/608回放冻结。本轮fresh fixes仅Service+HTTPtest delta2：有效RED3/3F；初始有限final4为3pass/1E，write核心断言通过后在postrelease长存探针遇renew generation交错。仅测试尾部改用新正常holder，write-corrected单项通过；生产同字节/另三方法及核心断言未变的继承经原reviewer和root核实，不声称一次4green或最新epoch重跑其余三项。两项I1/I2均关闭。新19201B delta2 patch首次check/apply均0，完整608名称/bytes/SHA一致；新530有限overlay合并历史逻辑target2148，新4份diagnostic另列。 fresh独立review docs/evidence/review-iam-stage-4-o2b-e-engine-packet.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-e-root-check.json、.local/iam-o2b-e-fixes-root-check.json。最新source608（SHA303ec0625e9b8491d834cbd0ce27a38c0dcffab77104e333b38f960e102a5adf），target历史逻辑2148（SHA30422b7bcf37c2e31ef6c596ced94543d432bf847bde17b513640d6dba403f51）；overlay按真实compiler/resources/common fixtureJAR/actual Running报告有限合并，新dumpstream/dump分离，历史逻辑不是当前全后端classpath。

本轮E修复未关闭问题C0/I0/M0。pre-HELLO失败现为固定有界ApiResponse JSON；CAPACITY输出异常也在beforeRelease内finally等待actual complete，再归还B permit/frame/slot。原E初期TDD顺序/controlled-baseline、HTML400/stale INVALID及本轮0 Running INVALID、初始final1E保留，不补造、不改写。I2为实际已授权borrow上的编码故障及write/flush注入，不是最大BUFFER_LIMIT压力；192MiB为静态保守模型及账本断言，未记录精确heap峰值。F仍待实现，三root大payload不出wire，未验实际TLS来源/网关完整材料/公开SSE/真实外部IAM/K8s/生产容量。 原D87/86pass/1F和独立selector3green永久保持；E受影响C24在新源码epoch回归与历史C阶段严格分开。旧C M1工具诊断/M2早期raw-only XML/epoch缺口、D early独立记录缺口/JSON-Buffer归档及其他历史Minor不补造、不隐去。

下一F仍待实现/验收：复用D Opening的原Q票实际metadata HTTP七字段→固定engine HTTPS身份/CA/必要mTLS与正确workload secret_ref→完整packet decoder/COMMIT/receipt关联/完整scope直接父与state/event摘要及fresh G current→实际公开SSE。E不导出三root大payload，F不能声称重算其payload SHA；engine可信PG负责row membership/latest/同RR选择。当前未开放gateway EVENTS，也不代表生产issuer/TLS/网关材料验证/真实外部IAM/K8s/生产容量/Socket强断SLA完成。production retention/active prune、RR/CHAT/F与全17需求/34任务/30验收继续待验，T01唯一完成、T09/T11/T12进行中；v1.2/四契约/时间约束及新工程隔离不变。

E剩余问题以本轮review与验收明确保留；旧C/D证据缺口、production retention/active prune和真实外部验收仍不消失。

### G6 O2b F1严格私有报文解码与本地预算验收（2026-10-21）

限定F1 Accepted：仅gateway infra新增strict packet decoder与有限resource budget及两测试。逐段验证24B APEV prefix/44B APCM COMMIT、精确segment/packet SHA、ordinal、长度与严格UTF8/JSON结构；一次只持有一个有界包，无下一包预取。结果明确为UntrustedPacket，不代表engine来源、receipt验签、parent关联、run/floor/current、业务row SHA或公开输出许可。gateway EVENTS继续关闭，STATUS/POST、common/Controller/assembly/main/HTTP/TLS/四契约保持。

实际测试保留五阶段：功能RED2失败；首次green编译失败不计RED；compiled17含预算2失败；纠正及新增focused5通过；唯一final20通过（Budget9+Decoder11，16main/13test真实编译，common无Running）。root六阶段3610个不同物理文件审计PASS；未重跑作者成功测试、PG或重放。原52743B文本patch首check/apply均0但两生产文件末尾CR丢失，原失败现场完整保留；另制22378B二进制patch、独立608基线首check/apply均0，一次完整612字节核对吻合。 独立review docs/evidence/review-iam-stage-4-o2b-f1-gateway-decoder.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f1-root-check.json。最新source612（新增4、SHAb391ee9fe99f69b51066006bdb468bcf4750cf2f6bd16a2dc22ae458fae301f4），target历史逻辑2167（SHA86cf879d8893d7b49bcec0e261a262e1339596f2ce5c431e37190cb07c7530f0）；仅合并实际compiler/resources/common fixtureJAR/actual Running有限overlay，新diagnostics另列，不称全后端当前classpath。

M1保留：4份Manifest-JAR跨盘诊断、既有弃用与日志路径提示，不隐藏、不重跑。parser 65667584B静态模型小于64MiB预留，仅固定ReaderBased路径；allocation/backing观测不冒充精确存活堆上界。固定schema使部分JSON深度/token边界不可达，已明确交集限制；UntrustedPacket没有业务授权效力。F2 TLS/HTTP/Servlet资源和来源、receipt/G-current/完整验材与SSE仍待实现；未宣称生产容量。 原E45、fixes初始final3pass/1E及纠正write1pass、两份patch/回放/独立review/审计保持冻结，不重跑或改写；旧C/D证据缺口与所有已记录限制继续保留。

下一F2仍待实现/验收：Opening原Q票实际metadata HTTP七字段、固定engine CA/hostname/origin HTTPS、发送headers前实际child/G live、真实receipt关联/fresh G current、完整nested schema/scope直接父/floor/initial/state-event业务规范化SHA、公开SSE/frame/cursor/terminal及真实upstream abort/close/actual complete所有权。G/E均已有自动renew，不另造scheduler。F1不计HTTP/TLS/Servlet底层buffer；其预算与来源必须在F2另证，E16MiB也不能替代。三root大payload仍不出wire，不假称网关重算其payload SHA。production retention/active prune、真实外部IAM/K8s/生产容量/强断SLA与全17需求/34任务/30验收继续待验。T01唯一完成、T09/T11/T12进行中，v1.2/四契约/时间及独立新工程隔离不变。

### G6 O2b F2a隔离TLS基础限定验收（2026-10-21）

限定F2a Accepted：新增3个gateway infra TLS生产类、两测试类及仅测试PKCS12素材。固定专用CA/hostname/SAN、单literalIP、TLS1.3唯一AES128GCM；每physical连接C65536累计raw输入减唯一已交付应用字节、W131072累计raw输出，全实例共用两物理槽、零等待队列，单调connect/handshake/idle/hard期限与实际abort/退出后归还。TLS只证明固定服务端来源与组件边界，不给业务授权，组件仍未装配；gateway EVENTS继续关闭。STATUS/POST、common/Controller/assembly/main/pom、v1.2及四契约保持。

原F2a final39仅为历史。独立审查I1/I2/I3、M1/M2已关闭；功能修正唯一final实际Debt7+TLS40=47/0/0/0，raw/XML/Running已核。19main/15test在前一eof-collector-green实际编译，final同618源码epoch按up-to-date执行。新的格式head只改两文件具名行首空白，含注释/字符串的token完全等价，未重编译或重跑47。root分别完成原6phase5941、功能修正5phase6296和格式3phase21个实测物理身份+616复制身份继承的有限核对，不能相加成新测试数。原/新binarypatch分别首次check/apply成功且完整source回放一致；最新完整618为明确复制快照。原612source逐字节不变，累计新增6。123份自有(run,PID)真实退出已核，早期cwd仅请求字段、numericPID复用和失败历史如实保留。 独立review docs/evidence/review-iam-stage-4-o2b-f2a-tls.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f2a-root-check.json、.local/iam-o2b-f2a-fixes-root-check.json、.local/iam-o2b-f2a-format-root-check.json。source618（新增6、SHAe9a961138967910fae61a2f611c8fd0f69d1391c4b4c973dc78b79da8e6be1e0），target历史逻辑2202（SHA95313cf613f9b3045eb85b524740a77b2fb36fc6a6148ce83b43cce75d3d8067）；finite overlay只采实际编译/资源/fixtureJAR/actual Running，新diagnostics单列，不冒称全后端当前classpath。

保留两项非阻断限制：M3原13份与功能修正6份Windows Manifest-JAR跨盘诊断及旧deprecation；M4原failed thresholds的较晚input-source观察不能当编译epoch。原plain常量观察声明不成立，由新报告明确纠正，只以新有限raw分类/额外accept负控制建立当前证据，不追溯改写旧报告。 H32768/peer chain4只在全新隔离测试JVM的明确启动flags下实际验证；生产launcher与静态JSSE属性初始化未验证，本轮不修改main。未知DER/X509全局cache/JCE/native费用与完整TLS存活堆/全链192MiB/生产容量继续待验。原F1归一化失败与另制binarypatch完整回放，以及全部E/C/D历史证据继续冻结保留，不重跑或抹平。

下一F2b实际Apache socketFactory/TlsSocketStrategy接缝、原Opening Q事实HTTP及私有E输入、send-header前真实proof与有限HTTP费用；F2c真实receipt关联/fresh G current/完整nested schema及scope父关系/floor/initial/state-event规范化SHA、公开SSE/frame/cursor/terminal和Servlet实际complete所有权。完整跨JVM engine/issuer/PG正例、production retention/active prune、真实外部IAM/K8s及强断/生产容量SLA仍待验。全17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中。时间安排及独立新工程隔离约束不变。

### G6 O2b F2b1原Q HTTPS元数据限定验收

限定F2b1 Accepted：同一实际Opening的原Q仅一次exchange，私有Session固定HTTPS origin/literalIP，通过已验F2a的真实TLS handle接入Apache固定socket/upgrade路径；在实际executor及writer首字节前复核同一请求/原Q/Opening/期限。私有无headers/wire日志Managed adapter、零复用/重试/重定向/代理/压缩/cookie/auth fallback，严格有限head/body及EVENTS流式schema解析。EventsMetadata为绑定同Opening/Q的事实，不能充当G或业务permit。每Owner预留3MiB加128token，持有直至自有连接、活跃读写及worker/timer实际退出；费用仅覆盖具名HTTP组件模型，未覆盖全TLS/JVM/native。当前组件未装配，gateway EVENTS仍关闭。

原F2b1 epoch实际final18（metadata7/HTTP11）；修正epoch新的确定性并发行为RED1F/0E、focused3绿、final HTTP14/0F/0E/0S，focused2真实20main/17test编译、final同完整621 epoch up-to-date；没有重跑旧18或metadata7。真实TLS CL8192及chunked8192成功、chunked8193读取超限/未知尾部主动关闭、原Q一次exchange/七事实与实际退出已补齐。root原审计6阶段/18095 physical，修正审计6阶段/6622 physical，另有限live source名字621独立一致；metadata词法相同和STATUS前6168B exact，18新增child全部实际exit/collector join（17绿0、1RED1），finite overlay143合并历史logical2226。 独立review docs/evidence/review-iam-stage-4-o2b-f2b1-http.md 为Spec compliant/Approved，root有限审计 .local/iam-o2b-f2b1-root-check.json、.local/iam-o2b-f2b1-fixes-root-check.json、.local/iam-o2b-f2b1-fixes-live-names-root.json。source621（本轮三新增、一已有metadata client修改，其余617不变；SHA7ce27abedc333786bff2c7c886ab6092814b52118bf1fd3fd87f6a52eacf81fa），target历史逻辑2226（SHA4b7643c6b7f82ba96199f3d9b8e934089f94cbeb084e383ad13f5c6cf6ec3f53）；finite overlay仅来自实际compiler/resources/common fixtureJAR/actualRunning，不能当全部当前classpath或新部署。

原I1/I2和M1已闭合，M2 Windows classpath/deprecation诊断留项，修正新增3份344B与原10份分开保留。历史API缺失/harness RED不能说成完整业务guard RED。本轮第二辅助replay-index add与plain apply交叠失败、无预存独立preapply全工作树清单，以及两次高层metadata stderr仅有transcript的限制继续保留。root独立核主baseline621 actual tree、现有raw四个正反向image及blob SHA1、其余617前后与复制谱系、完整after621，确认首次普通worktree check/apply各1次exit0的有限重放闭合；未把辅助index失败或摘要当PASS/raw、未再运行Git apply。3MiB仅固定HTTP组件工程模型。公开EVENTS未装配，G/E、完整材料、SSE/Servlet、真实全链和生产启动/容量仍待。 前置F2a原39、功能修正47及纯空白token/copy-lineage分属既定epoch，不重复合计为本轮新测试；新一轮只以本轮实际Running/XML/编译及端点记录为准。原报告/patch/replay/诊断与失败历史继续保留。STATUS既有流程/POST、common/Controller/assembly/main/pom/前端、v1.2及四契约保持。

下一F2b2在同一私有Session由真实Opening转交G、绑定child E并读取私有包；F2c1真实receipt关联、fresh G current、完整nested schema/scope父关系/floor/initial及state-event规范化SHA；F2c2公开SSE/frame/cursor/terminal与Servlet实际生命周期。真实gateway/engine/issuer/PG/TLS联合正例、生产JSSE启动/完整容量、retention/active prune及真实外部IAM/K8s仍待验。全17需求/34任务/30验收未全闭环，T01唯一完成，T09/T11/T12进行中；继续独立新工程，时间安排不在范围。

### G6 O2b F2b2同会话私有事件读取限定验收

限定F2b2 Accepted：同一私有Session已验证原Q事实后，原Opening转真实G、恰一次交换同G child；在固定HTTPS/TLS/actual request双guard下请求唯一私有MIME，F1按单包同步借用消费。七事实在Opening移交前私有保存，后续仅同G最新current控制，不把短child JWT用于每包续验。Q物理图和timer实际退出后才E图；原t0的5秒Opening/55秒hard、E head2秒与15秒idle及阶段取消机制保持。纯有界E错误code解析与原STATUS/Q事实方法保持独立。Q-only Owner3211392B；consume前新增1MiB+128token，E-phase4260096B，退出/borrow未完成则持费并拒第三Owner。费用仅有限组件模型，不是RSS/全TLS/JVM/native容量保证。公开EVENTS尚未装配。

最终source623同一epoch真实执行31项（原Http14、新Authority8、新Http9），0失败/0错误/0跳过；实际新编译20main/19test。root六phase有限审计PASS，9551 owned、三改两新及其余618精确字节、原STATUS6168B与原14断言、主before621真实Git树、预存replay before621名字和字节、首次plain check/apply各一次0与完整after623闭合。finite overlay155→历史逻辑target2239；60个实际child均waitFor且collector退出（53成功、7保留历史失败），最终31 child退出0。普通8482B/额外签名前padding8559B child、43原始拒绝向量、21345B CL/chunked、原t0 55011ms/55callback、15134ms idle及实际撤权读退出已按原始证据核对。没有重跑作者成功测试、PG、provider或Git replay。 独立review docs/evidence/review-iam-stage-4-o2b-f2b2-private-http.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2b2-root-check.json。source623（三既有修改、两新test、其他618不变；SHAc2dd5086c9718098dc0957aa7f0dd3e7c809f56793d7eaea1b53e3987262c246），target历史逻辑2239（SHA38cd8356238f4363f10d3bbdf46660b727d10fbcbdf8cf47ad6ed1c68311bfd5）；finite overlay仅actual compiler/resources/common testJAR/Running，不作全部current/classpath/部署声明。

保留2项Minor：12份各344B Windows classpath/编码诊断及deprecation，新增并发测试长单行可读性。初始Python GBK在写入前失败，其raw仅存在工具transcript，owned为准确observed摘要而非伪造raw；stage子进程归属含mtime启发式，实际退出独立核对。费用仅有限组件模型，排除全TLS/JVM/native/common内部图和任意consumer图；G.close不证明common内部任务物理join。HELLO receipt及WINDOW目前仅未授权结构输入，C1真实receipt/material/current校验、C2公开SSE/Servlet、真实全链和生产launcher/容量继续待办。 原F2b1的18、修正14及本轮各stage严格按source epoch保留，不把旧结果改名新跑或合并为虚假同场测试。v1.2/四契约、common/TLS/F1/engine/main/controller/assembly/pom/前端保持；时间安排不在范围。

下一C1在同Session验证真实receipt与parent-child、完整nested schema/scope直接父/floor/initial、state-event业务canonical SHA和fresh G generation，借用VerifiedWindow；C2再接公开SSE/frame/cursor/terminal、Servlet实际生命周期与物理abort。真实gateway/engine/issuer/PG/TLS全链、生产JSSE launcher/完整容量、retention/active prune及外部IAM/K8s仍待验。17需求/34任务/30验收未全闭合，T01唯一完成、T09/T11/T12进行中，继续独立新工程。

### G6 O2b F2c1完整事件材料校验限定验收

限定C1 Accepted：同一私有Session的原Q七事实、真实G及其child实际接收链生成的HELLO receipt恰一次验证，交叉核parent/child关联；WINDOW严格14键和完整nested材料、root/state/event与直接父精确并集、完整父数组、initial/floor/代际逐项验证。state/event业务SHA按精确Java String排序、BigInteger/BigDecimal与JSON转义语义流式canonical，数值展开前容量准入。最多两次完整P3重验；同步VerifiedWindow只在同Session/具体packet/同代借用内有效，不产公开cursor/终态/浏览器字节。固定80MiB先收费、真实解析/consumer/borrow退出才退款，并早于下一F1 scan；不是全JVM/TLS/native容量保证。

原C1 candidate epoch的19/19验证与14个最终child实际退出，继承其完整独立机械审计；修复后新627 epoch只新增一次受影响矩阵8/8（Authority3＋原Verifier5），最终3个child实际waitFor并collector join，未重跑原19，不能合称同场27。旧实现的真实并发/重入RED均保留；I1同窗口单writer在任何payload遍历前原子拒绝，第二generator调用0，异常后顺序使用成功；M1 schema/factory仅排版。并发/reentry单Owner88342871B、双Owner retention176685742B/第三拒绝/实际writer退出后退款。root新delta六phase PASS核627源码两修改/其他625 exact、actual Git627 before/after、首次plain check/apply各一次0/fullafter627、finite378→历史logical2269和全部6个child实际退出，未代跑成功测试。 fresh review docs/evidence/review-iam-stage-4-o2b-f2c1-material.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2c1-root-check.json、.local/iam-o2b-f2c1-fixes-root-check.json。source627（一既有修改、四新含classpath资源、其他622不变；SHA6b4a6488efa08b27266ccceff39fd3e79b62b137a3edcc6dfd3b071abd83f68c），target历史逻辑2269（SHA1998bf8be7352c46b2ca2698ca16e90cb092b5a09eb5817401b8bca441caa378），finite overlay仅actual compiler/resources/common fixtureJAR/Running。source resource身份与Maven copy及root E WINDOW提取gate闭合；旧HELLO/JWT/私钥不进入新asset，新test运行不读ignored.local或engine业务JAR。

本次I1/M1闭合，仅原M2编译编码/deprecation诊断保留；原F1 ignored.local whole-branch可移植性仍属后续对应任务。writePayload仍为排序/数值归整后的JSON；C2需最小同window rawpayload共享writer借用接缝以保留浏览器原JSON数值/转义。公开EVENTS/SSE/真实Servlet生命周期、实际enginePG latest/membership/rootpayload与生产issuer、真实跨服务首链尚待C2；不据局部验证签收完整IAM或全JVM/TLS/native容量。 旧E/F1/F2a/B1/B2各stage结果按原source epoch保留，不重跑或合并为虚假同场证据。v1.2原文/四契约、common/TLS/F1/engine/pom/main/controller/assembly/前端保持；时间安排不在范围。

下一C2依实际C1私有API接公开SSE/Frame/cursor/terminal与Servlet生命周期，采用真实WriteListener实际写、同Session同步借用、有限ready-idle等待及精确request CLOSE_NOW/disarm；bootstrap在原请求线程等首个可信window后注册并返回，具体源码/并发/有限退出必须另验。真实gateway/engine/issuer/PG/TLS全链、生产JSSE launcher/完整容量、retention/active prune、外部IAM/K8s仍待。17需求/34任务/30验收未全闭合，T01唯一完成、T09/T11/T12进行中，继续独立新工程。

### G6 O2b F2c2a未装配SSE与真实Servlet组件限定验收

限定C2a Accepted：同Session完整receipt/material验证后的原payload以受控同步raw借用写出，仅删除字符串外JSON格式空白，保留原数值词法、转义/Unicode和字符串空白；raw/canonical共享单writer准入。原Q/G initialCursor保持固定，WINDOW.after/event/reset依当前已成功公开flush后的cursor；只有完整write＋requiredflush/readydrain＋最终fresh检查才确认推进。reset优先、status最多一次，终态排完backlog才结束，EOF不冒终态。

真实Tomcat HTTP1.1 NIO中，原请求线程有限等首可信窗后登记listener并返回，首次get在原请求线程完成guard，实际WriteListener执行ready/write/flush guard与有界chunk；ready-idle轻shell有限等待、heavy helper真正返回/slot detach后才进下一F1scan。固定Http11NioProtocol＋公开Processor包装以真实socket锁串行完整IO、CLOSE_NOW与回收，原请求Valve绑定每次REQUEST新epoch；外部stop先释放应用gate再等待socket锁，onComplete及delegate.recycle前失效旧token，late abort不触复用后的请求；SSE实际worker/IO/borrow/重状态/completion gate先于Http旧G/费用cleanup，失败或卡住继续持费并拒第三Owner。这里只签收已实际验证的应用组件，不声称全container/channel/TLS/native同步join。

证据按各自真实字节epoch继承：原C2a最后29方法/38child，后续Q与原REQUEST/分配修正13、7、2各属独立epoch；共享人口/强锚修正保留14与10的失败、corrected7/1fail、action1及elected-join3，mixed首INCONCLUSIVE与改fixture后Handle/Processor通知各1、shared reuse2分列。最终Http修正先5方法GREEN/6child，再对真实c/r均非空见证分别做旧行为stop单项RED及当前stop单项GREEN；其余4方法继承先前epoch，11child全部实际waitFor/collector退出，无GC。两个实际取消/cleanup重试窗口在helper暂停时Owner1/reserved7405824且另一closer未退款，helper和线程真实退出后回3145728基线；light-shell实测9315526→3145728。四轮root各六phase有限审计、各自首次plain check/apply及全native blobs/源码名/有限target已缓存，未重跑已绿测试、原审计或成功Git。 独立review docs/evidence/review-iam-stage-4-o2b-f2c2a-sse.md 为Spec compliant/Approved；root有限审计 .local/iam-o2b-f2c2a-root-check.json、.local/iam-o2b-f2c2a-fixes-root-check.json、.local/iam-o2b-f2c2a-retention-fixes-root-check.json、.local/iam-o2b-f2c2a-http-exit-fixes-root-check.json。source630（3新4修改、其余623 exact；SHA6f61ed3ce5ce87fbafead1dd1434f2b1d9dc1b3c3692be81a51c19a7155e3b9d），target历史逻辑2298（SHA304be175bbbcd46f11fa045617b34c456526be83925a0599b3de43877081eeda），finite overlay只actual compiler/resources/common fixtureJAR/Running；旧C1原19/fix8等按各自epoch继承，不重跑或合为本次同场结果。

C2a已无Critical/Important。保留两项非阻断Minor：既有日志/编码/容器关闭诊断噪音；最初prelaunch断言失败helper无独立历史快照，其准确字节不可追补，后续actual source/测试/Git证据独立保存。支持的VM/layout、共享TLS/config/B2归属及非穷尽并发属于固定有限模型；库长期不退出时继续持费，不承诺GC/可用性期限。 未装配component无扫描/自动路由：公开IAM EVENTS仍关闭。下一C2b显式common ingress EVENTS claim、Controller/Assembly/同ControlledProtocol＋Valve接线，以真实独立gateway/engine/issuer/新PG/TLS和生产E encoder≥65父材料做首链；不能以C2a测试Servlet/private peer代替生产接线证据。固定connector每JVM终身唯一，以共享1536个名额同时计数原delegate与独立Handle/State/Session小图，forSse在Session创建前准入，final Session与State锚定唯一Handle；只在实际GC队列通知后归还名额，构造不确定失败烧槽，不靠旧1218估算。GC/通知迟延与烧槽可能降低可用性。预收3MiB全局基线直至JVM实际退出；Owner清零后该基线保留，双Owner候选合计200576852B属于有限工程模型。生产JSSE launcher/全容量、retention/activeprune、外部IAM/K8s和整个17需求/34任务/30验收仍独立待办。T01唯一完成、T09/T11/T12进行中；v1.2原文/四契约/时间与新工程隔离保持。

### G6 O2b F2c2b1精确EVENTS入口与显式生产组件接线限定验收

C2b1已限定 Accepted：IAM 原REQUEST的精确 EVENTS 路由经真实验签JWT、实际Controller/Handler、一次私有capture、实际Spring6.2.7 manager/native响应身份绑定后启动生产SSE。仅该purpose允许唯一Last-Event-ID请求头或单一字面query二选一，默认0、canonical ASCII long；其余RUN/STATUS/session规则不扩大。实际MVC supplied response承担getter/listener/写入，不绕过框架包装，不把proof带入异步。已接管响应后的启动失败执行原清理与持费门，避免普通JSON混写；raw入口仍保持严格facade与抛错语义。

显式import的生产配置提供真实Http/Sse/RunEventsProxy beans，并通过公开Boot API安装同ControlledProtocol/ContextValve。固定最终1024连接/64线程/64cache、内部executor、HTTP1.1；真实独立JVM验证正常启动及5种错误覆盖配置拒绝。共享1536/1792B/3MiB及实际helper/worker退出、未退出继续持费等原C2a门保持。

新Common2及原Common3在其实际epoch通过；真实Boot默认/头/query SSE、framework/54负向、5种最终profile拒绝、原raw helper及最终单请求故障/Q错误/缺组件按各自epoch验证。22个真实child=17成功/5保留诊断失败，全部waitFor/collector完成；原raw child成功与父级UTF8元数据error分开保留。最终head仅窄6child对应JUnit1通过，不包装为最终全套。root六阶段有限审计通过，实际634 before/after/native blobs及8段binary patch/plain check/apply首轮各一次闭合。 独立评审 docs/evidence/review-iam-stage-4-o2b-f2c2b1-events-wiring.md：Spec compliant/Approved，C0/I0/M2；root审计 .local/iam-o2b-f2c2b1-events-wiring-root-check.json 六阶段通过。source634（对C2a为4修改4新、其余626 exact；SHA c91a517192cb8caecf84da5726640d90c759793065dce9cbe0e94291a737d74d）；target历史逻辑2314/finite417/delta48（SHA e8c4ef2cf272f953f4e1282b939d8f6fa055a487da1b16eac9393b0dc150ee3f）。这是限定组件与各真实epoch证据，不是最新全工程测试或部署记录。

保留两项Minor：编译deprecation/unchecked与乱码诊断摘要不完整；原raw JVM缺少两项Tomcat ThreadLocal/RMI leak-detection opens。原始失败、元数据错误和告警均保留，只在未来有功能理由的新runner epoch改善，不为清理文案重跑绿色测试。有限资源模型不等于全JVM/native容量或通用无泄漏保证。

下一C2b2使用独立GW/E/网络issuer、新PG库和正常CA/SAN TLS，验证真实E reader/encoder≥65完整父材料、游标/状态/权限变化及实际退出；C2c随后闭合四个前端消费点的raw payload、精确decimal cursor/watermark。当前公开EVENTS仅在显式IAM装配组件中可用，默认GatewayApplication生产IAM启动尚未验；private peer不替代联合链。外部IAM/K8s、发布部署、全容量与17需求/34任务/30验收仍独立待办。T01唯一完成，T09/T11/T12进行中；原v1.2、四契约、时间安排和新旧工程隔离保持。

### G6 O2b F2c2b2真实网关／引擎／PG联合链限定验收

| 跟踪项 | 已核对事实 | 后续处理 |
| --- | --- | --- |
| IAM-O2B-F2C2B2-M1：夹具失败收尾 | 首链的 readLine 阻塞不受外层 12 秒条件约束，Executor.close 可能先于 body.close 等待；外层 300000ms 进程上限不等于本地有序清理 | 下次有实际夹具修改理由时，先取消／关闭 body，再有界等待读取任务及子进程；当前保存的成功证据有效，不为此重跑绿色。 |
| IAM-O2B-F2C2B2-M2：重置断言精度 | 最终实际 reset 为 CURSOR_EXPIRED／FAILED／watermark35，但当前回归仅断言 HTTP200 与 reason 子串，其余为已审查记录 | 下一次修改该夹具时，对解析后的唯一 reset event／reason／status／watermark 与真实 finish／inventory 做精确断言。 |
| IAM-O2B-F2C2B2-M3：继承的诊断边界 | 两份 nested cleanup errata 保留 Tomcat 线程警告、编码与可选 ThreadLocal／RMI 检查缺失、非原子采样及截止时间竞争时持费限制 | 不声称全 JVM／容器干净或所有未退出工作最终自动关闭；有具体失败再独立调查，保留原始告警。 |

三个实际生产故障已有独立修复、回归及证书；上述三项为独立评审的非阻塞维护项。独立评审 docs/evidence/review-iam-stage-4-o2b-f2c2b2-joined-chain.md 为 Compliant / Approved with Minor，C0/I0/M3；root 六阶段物理、编译、运行、原生应用与保护文档核对通过。限定证书：.local/iam-o2b-f2c2b2-acceptance.json。 本轮只闭合 C2b2 后端联合链，未重跑所有当前方法或完整后端套件。C2c 四前端消费、默认生产 IAM 启动、真实外部 IAM／K8s 与容量仍待；E 的 32768 请求头上限仍是已验证夹具配置，默认生产配置未验。T01 唯一完成，T09／T11／T12 继续进行中，T33 待启动；详设 v1.2、四契约与无工期安排保持。

### G6 O2b F2c2c四前端协议与流式文本修复限定验收

| 跟踪项 | 结论与证据 | 后续 |
| --- | --- | --- |
| IAM-O2B-F2C2C-I1 | 空text遮蔽有效delta/content已关闭；先失败的单测及Chat/SDK片段显示验证，fresh复审C0/I0 | 保留回归，不扩大为最终107项整套通过 |
| IAM-O2B-F2C2C-M1 | 原任务及修复均保留两条>500kB包体积告警 | 单独测量首屏与包预算后再决定拆分，不提高阈值消噪 |
| IAM-O2B-F2C2C证据限界 | RED浏览器辅助脚本仅依据精确collector谓词编辑历史恢复，无独立运行前hash；业务源码644逐阶段实际快照、原RED输出及Vite响应保留 | 不补造缺失的事前测量，不为文案重跑绿色；有限证书保留该限界 |

独立评审：docs/evidence/review-iam-stage-4-o2b-f2c2c-frontend-protocol.md（原 C0/I1/M1）及 review-iam-stage-4-o2b-f2c2c-chunk-fallback-fix.md（I1关闭，C0/I0/M1继承）；限定证书 .local/iam-o2b-f2c2c-acceptance.json，绑定原任务 root-qualification。

本轮源码640→644累计13处修改、4份新增，627份原文件不变；I1修复只改2份既有源，其余642份不变。仅验收有限前端协议消费和恢复。默认生产IAM启动、模型／技能的可信材料出域及恢复链、真实外部IAM／本体／模型／K8s与容量仍待；后端历史逻辑2339保持原有边界。T01唯一整体完成，T09／T11／T12继续进行中，T33待启动；详设v1.2、四契约和无工期安排保持。


### 模型初始输入生产组件限定验收

已完成无历史 initial-chat-main-v1 输入的不可变 PG 落库与同事务材料作用域，以及经过真实签名授权的只读元数据接口。三项评审问题均已闭环：锁内绑定最新成功授权决定；模板大规模展开前检查 UTF-8/JSON 预算；已支持参数逐项校验类型。保留正常模板语义与近 2 MiB Unicode 合法输入。

修补后的同一份源码通过 PG21＋HTTP11＝32项测试，零失败、错误和跳过；独立同源副本打包成功（skipTests），原生补丁应用、有限产物与隔离资源退出经 root 核对。原任务及旧回归证据按各自阶段保留，没有合并为最终全工程结果。独立复审 Approved／Approved，C0／I0／M1；M1为保留的编译／Flyway输出告警，后续有实际维护理由时处理。

源码累计644→652：8份新增、3份原文件修改、641份原文件精确未变；本次修补只改其中4份，其他648份未变。限定证书 .local/iam-model-input-producer-acceptance.json；修补报告 docs/evidence/iam-model-input-producer-review-fix-report.md；复审 docs/evidence/review-iam-model-input-producer-review-fix.md；验收边界 .local/iam-model-input-producer-root-final-qualification.md。

下一步实现模型网关对材料和完整请求的校验。公开 CHAT／LLM／AGENT／模型出域、默认生产IAM启动、真实外部IAM／本体／模型／K8s与容量验收仍待。整体34任务状态不作完成提升：T01唯一整体完成，T09／T11／T12进行中，T33待启动。详设v1.2、四契约、工期排除和新旧工程隔离保持。


### 模型初始输入消费组件限定验收

已完成无历史 initial-chat-main-v1 私有输入校验：完整请求摘要、真实签名授权、固定 HTTPS 材料读取、父子材料闭环及当前授权／生命周期检查。原 I1 数值摘要兼容问题和 M1 无效零计数断言已关闭；严格保留合法数值词元，兼容生产小数、科学计数法、整值浮点及大有限数，仍只核对一个完整请求摘要。公开模型链尚未启用。

独立复审 Spec compliant／Approved，C0／I0／M1；M1 为预期 IAM 负例告警及编译提示，保留日志并登记后续有实际维护理由时处理。16项校验测试与最终3项真实独立 JVM／PG／HTTPS联调分别有完整源码阶段记录；仅 HTTP 负例构造变化，相关3份源码和5个类精确一致，不合并为同阶段19项或全工程通过。最终同源副本打包成功，566类／370 JAR条目、660份补丁应用与6个专属数据库退出经根核对。

组件累计652→660：8新增、3原文件修改、649原文件未变；此次修补仅改5份、其余655份未变。限定证书 .local/iam-model-input-consumer-acceptance.json；修补报告 docs/evidence/iam-model-input-consumer-review-fix-report.md；复审 docs/evidence/review-iam-model-input-consumer-review-fix.md；边界 .local/iam-model-input-consumer-root-final-qualification.md。中间元数据重建及审计读取修正均已限定并保留原记录，不影响最终独立证据。

下一步：冻结模型绑定快照的租户、全量摘要、当前材料标签和冻结端点能力校验，仍先完成私有前置组件。真实外部 IAM／本体／模型／K8s、模型首次发送及容量验收待后续闭环。整体34项状态不提升：T01唯一整体完成，T09／T11／T12进行中，T33待启动。详设v1.2、四契约、工期排除及新旧工程隔离保持。


### 冻结模型绑定校验组件：实现限定验收

已完成 initial-chat-main-v1 私有冻结绑定校验：真实消费证明、原始规范请求摘要、PG完整快照摘要与租户/标识、事务释放后当前授权/材料/标签覆盖、冻结能力及结果生命周期。原 I1 的3秒累计事务预算已修补，每次SQL消费剩余期限并保留单条2秒/锁1秒上限，事务清理完成后、最终IAM前再次拒绝过期结果；普通JDBC为合作式限制，不宣称硬抢占。

独立复审：修复行为Spec通过、Quality Approved、代码C0/I0/M0。真实PG失败先行2测/1断言失败/正控通过；最终同阶段22/0/0/0，旧73项回归保留原阶段，不重跑或合并。根核对667份源码（修补仅3改、664原样）、622有限产物/582类/28资源、12编译输入清单、6包/548条目及实际补丁应用；独立副本打包成功。2新库会话0、4角色NOLOGIN、18记录进程和8已知端口关闭。

执行不合规单独保留：修补初始曾在父目录枚举相邻文件/目录名称，未获得全任务无条件合规结论；转抄记录及作者关于未读相邻内容的声明不能替代完整原始轨迹。原误用canonical打包的未知主运行影响、历史loaded-class缺口及Surefire临时字节缺口均未被新绿结果消除。限定证书 .local/iam-model-frozen-binding-acceptance.json；范围 .local/iam-model-frozen-binding-root-final-qualification.md；复审 docs/evidence/review-iam-model-frozen-binding-review-fix.md。当前保护文件基线保留此前7原样+1外部格式变化，不恢复未知来源格式。

下一步为私有LOCAL输入token估算：绑定真实有效证明及原始accepted canonical字节，估算不作为发送/预算准入许可。公开模型链、真实IAM/本体/模型/K8s和容量仍待后续闭环。整体34任务状态不提升：T01唯一整体完成，T09/T11/T12进行中，T33待启动；详设v1.2、四契约和工期排除保持。


### 私有LOCAL输入估算组件：限定验收完成

已完成真实冻结证明与原始canonical字节绑定的JTokkit本地估算（+64结构余量，exact=false），以及进程异常、候选身份、partial setup收尾修补。最终31项在五组各30秒外部上限下全部通过；独立复核未解决C0/I0/M0，源码671不变，隔离资源已闭合。范围、实际证据及执行偏差见 .local/iam-model-local-meter-acceptance.json、.local/iam-model-local-meter-root-final-qualification.md 和 docs/evidence/review-iam-model-local-meter-watchdog-review-fix.md。

该估算尚不承担实际wire计量、准入或发送许可；下一项为共享Spring AI冻结参数转换。T01仍为唯一整体完成任务，T09/T11/T12进行中、T33待启动；真实环境/容量验收待闭环。详设v1.2、四契约及不排工期保持。


### 冻结初始Prompt转换：代码基线确认，执行I1保留

已补齐共享Spring AI原生参数转换及原始冻结字节校验；最终20项相关回归通过，674份源码/4 JAR/补丁及隔离资源完成物理核对。代码复核Approved、无生产代码阻断；总体仍C0/I1/M0：早期未guard Python的历史时间/PID缺口不可补证，整体验收NOT_ACCEPTED。可复核代码作为后续隔离开发基线，I1继续记账。依据 .local/iam-model-frozen-prompt-code-acceptance.json、.local/iam-model-frozen-prompt-root-code-qualification.md、docs/evidence/review-iam-model-frozen-prompt.md。

普通Prompt不是实际发送/计量/准入许可。v1.2、四契约、不排工期及T01唯一整体完成/T09T11T12进行中/T33未启动不变，真实环境和容量仍待闭环。


### 初始流式 SDK 请求体校验：代码基线677，执行偏差保留

已完成私有实际 SDK 请求体与真实冻结输入的对应校验，覆盖模型、消息、参数、输出格式、流式设置及当前证明生命周期；函数未接入生产发送。真实失败先行后，同一final3源码16项相关测试通过。独立复核CodeSpec符合要求、代码Quality附Minor通过；本任务总体C0/I2/M2：双Maven重叠及一次helper启动前字节封存缺口为Important，夹具强停与预期ERROR栈噪声为Minor，历史I1仍保留，整体验收NOT_ACCEPTED/NONCOMPLIANT。

根核对9545份有限证据、677源码五份树、637编译/资源记录、4JAR/377项目ZIP条目及原生补丁重放；4隔离库/8角色和记录进程已收尾。仅确认代码可作为后续独立开发基线，不重跑测试修饰历史。依据 .local/iam-model-frozen-wire-code-acceptance.json、.local/iam-model-frozen-wire-root-code-qualification.md、docs/evidence/review-iam-model-frozen-wire.md。

下一步为显式隔离组件中的认证模型入口→当前材料→专用tokenizer→配额/持久化→provider流式链；默认生产IAM/D3、引擎IAM发送与attempt整合仍未接通。v1.2、四契约、不排工期及T01唯一整体完成/T09T11T12进行中/T33未启动不变。

### 初始模型流式组件：代码基线680，执行历史保留

显式隔离组件现已连通认证入口、当前材料/绑定、专用 tokenizer、配额/持久化和真实 Spring AI SDK/provider SSE。独立复审后 I1/I2/I3 均闭环，适配器诚实记录 skip/container；限定代码评审 C0/I0/M1，最终规范化680源码取得 CODE_ACCEPTED。默认生产 IAM/D3、公共 CHAT 与引擎调用方尚未激活，整体任务 NOT_ACCEPTED、执行 NONCOMPLIANT。

本批13方法实际绑定规范化前源码；仅7个CR删除后，同一编译器/选项产生的3个 ModelCalls class 与运行字节完全相同，未重标或重跑历史。Root完成新交付九项物理核对，六包634项目条目及3848公开ZIP条目一致；27组隔离Redis已关闭，数据库保留数据、零会话，测试角色NOLOGIN/无高权。早期固定测试key复用、独立helper常量输出、原评审偏差及全部失败/超时/ERROR记录继续保留，不因代码通过豁免。

依据：.local/iam-model-initial-stream-code-acceptance.json、.local/iam-model-initial-stream-root-code-qualification.md、docs/evidence/iam-model-initial-stream-fix-report.md、docs/evidence/review-iam-model-initial-stream-fix.md。下一步依赖该680基线，实现真实RunService.drive/driveRecovered→原始请求/attempt持久化→单次HTTP/SSE→fenced logical commit，并验证不确定结果不重发。v1.2、四契约、不排工期与T01唯一整体完成/T09T11T12进行中/T33未启动不变。
### 2026-10-23 组件三档验收规则应用与 source684 开发基线

当前最新限定开发源码为 source684（最终 final4，清单 SHA256 1d11e82ab89aa589aa8415fee8a14e36b38fab40dd18e4567c9b085ea9153901）；独立 SpecCompliant / QualityApproved，C0/I0/M0，初始发送 I1–I4/M1 及租约终态窗口已闭环。根证据 .local/engine-initial-sender-development-baseline.json 仅批准后续开发使用已评审代码，正式组件验收仍为 NOT_ACCEPTED。

本次按《组件验收口径-条件验收规则》v1.0 完成独立适用性复核：核对的四个组件为 **0 项完全验收 + 0 项条件验收 + 4 项不通过**，不是全平台统计。旧代码 Approved 保留；原隔离/边界影响未知和实际凭据字面量输出不能按纯证据缺口转档。完整逐条登记、未补证事实、对冲计划及 T34 硬闸见新工程 docs/evidence/component-acceptance-register-v1.md 与两份独立报告 review-component-conditional-acceptance-v1.md、review-engine-initial-sender-fix.md。无条件档提案、无负责人批准记录，不涂改历史结论。

本轮 final4 DIRECT 12 项、第二数据库回归 DIRECT 6 项；Surefire 4 PASS + 1 类条件 SKIP 投影，SKIP 未计为通过。旧 candidate53 项和两项 final3 串行核对保留原源码/群组归属，不累加冒充 final4 全量。源码/编译输入/运行捕获/三制品/250生产条目/2988引用与52个Linux关闭记录已限定核对；两个自有数据库保留且会话0、角色NOLOGIN。执行历史 NONCOMPLIANT 与 root 丢失的一次只读查找结束身份继续披露。

后续顺序：首次无会话 CHAT 的真实创建→执行/恢复→状态及持久事件观察，复用已评审初始模型线协议；实际 source684 重绑定后再授权实现。会话历史、公共 token 流、公共取消、默认生产 IAM/D3 和部署均另有前置条件。T31 17项联合链、T32故障/并发/隔离、T33容量和T34签收须逐项覆盖，未覆盖不得生产签收；未来条件档出现功能缺陷立即撤回。T01仍唯一全局完成，T09/T11/T12进行中，T33未启动；不安排工期、不改四契约。


### 2026-10-23 首次无会话 CHAT：I1 闭环与 source688 开发基线

独立复审确认原生产I1闭合，Spec Compliant/生产Quality Approved、C0/I0/M0；总发现另含证据组织M1。M1通过root与独立有限分类追加准确计数，历史误称保留。P1–P14/F1–F10及逐条对冲见复审原文，P6UNKNOWN不被新绿消除，也不推断已泄露。
最终源码688，清单SHA256 `2625ce4d7f40fcf40c0d529f8b0d59284ceda1ff40253eca629f9bc75ff18ca7`；相对原688仅3MOD/685same。生产只增加chatSession/chatTurn/sessionHistory/history四个顶层保留名。真实RED后，四GREEN方法均通过：新建与重放各12请求全部403，初建无新增/外发、重放不改变原事实，合法CHAT/TASK/FLOW通过。root核对2752文件身份、721运行文件/686Java21类/158依赖及四制品317生产条目一致；未重跑旧九重点群组。

生产代码C0/I0/M0、Spec Compliant/Quality Approved，仅作为后续隔离开发基线；正式组件保持NOT_ACCEPTED，P6部分早期原输出安全边界UNKNOWN，不能证明全部缺口属于规则§3，亦不认定已泄露。所登记五组件为0项完全验收＋0项条件验收＋5项不通过，不是全平台统计；没有条件档提案/负责人批准。关闭审计准确口径为79实际guard/176有效身份观察，另2metadata/693空身份项，control另列审计后3次真实结束；原81/869记录不涂改。

依据：新工程 `.local/initial-chat-admission-development-baseline.json`、`.local/initial-chat-admission-root-final-qualification.md`、`docs/evidence/review-initial-chat-admission-fix.md`、`docs/evidence/component-acceptance-register-v1.md`。T31/T32/T33及真实IAM/D3对冲按 `.local/initial-chat-admission-joint-acceptance-obligations.md` 逐项执行；T34未覆盖/未批准不得签收，未来条件档暴露功能或安全缺陷立即撤回。T01仍唯一全局完成，T09/T11/T12进行中、T33未启动；默认IAM/D3、公共token/cancel/session不启用，冻结v1.2、四契约及不排工期保持。