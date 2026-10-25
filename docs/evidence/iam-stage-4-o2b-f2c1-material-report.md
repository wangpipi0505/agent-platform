O2b F2c1 implementation report — DONE_WITH_CONCERNS

2026-10-21；唯一工作根 `G:/207/项目/算法重组工具/代码/agent-platform`。这是私有同Session材料验证候选，待root fresh review/有限审计，不是完整IAM或公开EVENTS签收。无commit/deploy、无真实IAM/model/K8s、无真实enginePG或Servlet/SSE发布。

**交付与身份**

最终两新测试19/19，failures/errors/skipped均0。AuthorityTest 14项/43.495s，VerifierTest 5项/0.253s。最终source627完整manifest SHA256 `0802f48d8e4071cf3f80ec667bd262e7cd7cb992411f6c4217487f401f06c84a`，123392B；before623中唯一既有修改是Http，新增Verifier、两test及唯一resource，其余622名字/字节逐项相同。源写权恰五路径；common/TLS/F1/engine/pom/main/controller/assembly/frontend/design/四契约/八索引未写。source/source5副本、primary/replay完整627及全部阶段epoch均冻结。

有限target373候选（实际compiler inputs/created lists及对应class、Maven resource copy、common test-fixture JAR、实际Running两suite报告），历史logical2239继承B2冻结manifest后overlay→logical2264；不是whole-current-target/whole-classpath扫描证明。target-after SHA256 `d247ca387b7220370d707782b4d461ba2deea9e0684b493270f7d22222f2c698`；target-overlay SHA256 `7756e3ea9c08c090a559708672acda149ae679101f6aea2ccd4e75c5703d3956`。具体目录/字节见`.local/iam-o2b-f2c1-material/source-after.json`、`target-after.json`、`target-overlay.json`及`docs/evidence/iam-stage-4-o2b-f2c1-material/finite-target-basis.json`。

冻结输入为`.local/iam-o2b-f2c1-material-frozen-inputs.json`（363e9774001d129378cc9cfe31f16fbfc7acbfc9ebe77e898ac2697d2da07794）。正式brief、三份要求、Accepted API补充全文已读；sourceBaselineDirectory继承root已验不可变目录证明，未重复全量hash该原目录。实际623 before副本/Git blob、最后627 live/source/replay另作要求内验证。

两份root窄澄清显式生效：`.local/iam-o2b-f2c1-test-observation-clarification.md`（5a2f9e20e7ab9964f3eb02bcbae1ccd17b5f35396824971f881745c405cf123a）仅允许新test用JDK Proxy原样delegate并观察同真实client返回的同一G，解包InvocationTargetException；无setAccessible/private读写/权限伪造。`.local/iam-o2b-f2c1-generation-checkpoint-clarification.md`（559bf16b1960ecbefd016599b3dc0b10cdfba2865d913b3588819ea8b53ec9d9）允许默认null的包内phase/index mechanical checkpoint。实际hook不接G/Owner/facts/packet，不改capture/结果/重验次数；test只对已观察同G调用真实公共checkGatewayPortalEvents。

**最终行为及关键不变量**

Http唯一新增块为WindowConsumer/consumeVerified、Session私有构造MaterialContext与默认null checkpoint。删除这一完整新增块后，Http与before原字节完全相同，包含既有consumePrivate、原Q和awaitClosed共享退出逻辑；CRLF也恢复原样。证明在`legacy-private-path-unchanged.json`。据Accepted补充选择以新的C1真实borrow退出/两Owner/第三拒绝测试替代旧`stuckCallbacksAndBorrowLeaksKeepTwoOwnersAndRejectThird`，旧方法未运行，不计其通过；未重跑旧F1/E/provider/PG矩阵。

同Session context仅由真实Session构造，取自己的G、原Q七事实/Owner和具体F1 packet。外部不能拼G/facts/Owner制造context或window；Verifier、入口和描述符均包内，VerifiedWindow构造private。上下文与具体packet及实际Capture绑定，consumer退出后所有句柄访问拒绝。没有创建线程亲和限制，同受控consumer可以同步等待另一callback线程；没有异步队列或永久窗口cache。writePayload是**排序且精确数值归整后的重序列化JSON**，不是原payload字节，也没有公开SSE frame；后续C2保留lexeme/转义的raw借用要由root另行决定，本轮不扩接口。

HELLO先用同G.acceptChildReceipt消费确切ASCII≤4096的签名原串，随后严格解析该同一串并交叉比parent/child ID；header再对原Q、G subject/resource/cursor。F1的HELLO顺序与common一次性attempt机制继承，未冒称本轮另跑旧common全矩阵。WINDOW严格14键及嵌套字段/类型/重复/unknown/UTF8/surrogate/尾随拒绝；revision/locator/hash规范、owner/initial/provenance/Q七事实、全部scope与三roots/直接父精确并集、完整父顺序digest、两个floor、state/event身份与业务SHA逐层验证。历史initial/origin没有被改写为新observer来源；没有递归扩大父闭包或裁掉>64父项。

P1顺序读取一个scope源模型、建立primitive index并只保留固定facts；不保留全metadata DOM。P2单scope取两目标required集合和父membership；P3 fresh Capture逐scope重算digest、requireMaterial、累积两个floor，并重算state/event业务SHA。常规3次metadata扫描；最多2次额外P3，不能给旧结果换代标签。index为4096项/8192槽primitive hash+revision表及两target bitset，受限探测每64步、parser每256 token调用同Session deadline/live。每个parser/Reader/Segment实际close后才进入后继阶段。

真实换代checkpoint分别在已验scope slot0、slot34（实际父行）、全部业务digest后。最终first/middle/digest各真实更换2次，P3 starts均3、实际scope visits分别71/139/207，callback各1；持续末digest更换3次时starts3/visits207/callback0，第三次保留真实common `SYS-AUTH-403`终止。真实旧Capture.isSuperseded已断言；`generation.properties`记录逐case观测。consumer开始后的换代另测旧window立即拒绝，不能当作P3重验覆盖。进度仅保存原after；不推进任何公开cursor。

CAPACITY/HELLO-only/边界EOF不回调window、不产生run事实/终态/游标。新noevent WINDOW允许完整scope并集中的历史event父；null payload为真实4B JSON事件。32个CJK字符事件type（96UTF8B）正例通过，不trim/ASCII化；没有冒称C2事件类型注入策略已完成。

**实际测试、RED和故障分类**

命令在固定根执行：`python .tools/iam-o2b-f2c1-run.py final-candidate 'PortalEventsMaterialAuthorityTest,PortalEventsMaterialVerifierTest'`。wrapper启动Java21/Maven3.9.16 offline reactor，仅gateway及必要common，不读PG密码或连接PG。最终实际gateway main/test compiler输入计数各21，实际Running仅两new suite；raw、inputFiles.lst、createdFiles.lst、输出class及source resource副本均绑定同627 epoch。common fixture JAR继承可用状态；不把compiler列表扩张成全classpath证明。

每阶段process-exit先journal/cache，再解析报告；成功测试没有因文案/metadata修复重跑。14阶段、49个实际新child JVM，最终14 child全exit0且actual waitFor、collector join已确认；peer worker也实际join。每个child有(run,PID)、launch/args/raw/exit记录。最终按case源码计51次真实两TLS连接Session（不是51个JUnit test），分类及证据见`final-observations.json`、`peer-exits.json`。存在早期失败child，未抹去或当作成功。

| phase | Maven exit | suite tests/failures/errors |
|---|---:|---|
| active-borrow-green | 0 | 1/0/0; 5/0/0 |
| active-material-borrow-red | 1 | 1/1/0 |
| active-material-red | 1 | 1/1/0 |
| borrow-retention-red | 1 | 1/1/0 |
| canonical-expanded | 1 | 5/1/0 |
| corrected-generation-profiles | 0 | 2/0/0 |
| expanded-authority | 0 | 9/0/0 |
| final-candidate | 0 | 14/0/0; 5/0/0 |
| first-implementation | 1 | 4/1/0; 1/0/0 |
| generation-profiles | 1 | 4/2/0 |
| red-boundary | 1 | 3/3/0 |
| red-real-boundary | 1 | 3/3/0 |
| red-receiver-fixed | 1 | 3/3/0 |
| red-seed | 1 | 3/3/0 |

真正行为RED是`red-real-boundary`三项：真实Q→G→child→公开receiver→retained initial→E receipt后，正确段SHA/COMMIT的receipt签名篡改、nested unknown、业务payload篡改被permissive seed接受，断言“Expected PlatformException but nothing was thrown”。所有负例先修wire SHA/COMMIT。schema13/relations12/floors6进一步断言具体拒点；floor变异先重新scopeDigest并级联替换完整引用，再重封wire，不能拿早期坏包冒称floor覆盖。仅单parent加region C的变异实际由最新Capture material权限拒绝，单独标明，不称floor业务拒绝。

`canonical-expanded`的真实Unicode RED由独立手写canonical UTF8+JDK digest捕获：默认UTF8JsonGenerator对补充平面字符的escape不同于E Writer→UTF8。实际定位到本地Jackson2.19公开JsonWriteFeature后启用COMBINE_UNICODE_SURROGATES_IN_UTF8，最终手写Unicode/control/null/高精度BigInteger/BigDecimal/负零/指数/object排序/array保序向量全通过。冻结E payload/state material.payloadSha256为真实producer采样oracle；小向量为按E语义独立推导，二者不混称。诊断source-launcher小输出受Windows控制台编码影响，仅作定位，不作业务oracle；原raw保留。

`active-material-red`是真实borrow RED：两worker阻塞在writePayload内部的String遍历，而consumer已经返回；旧实现awaitClosed竟true。修复为window.close先拒新调用，实际等待activeWriters归零，interrupt/cancel不冒充退出。最终两Owner实际持费176685742B，第三请求在IAM调用前被拒；release后worker/future实际退出，随后Owner费归零。先前`borrow-retention-red`及`active-material-borrow-red`把阻塞安排在generator外部flush，不能证明material仍活跃，只作fixture guard失误；中间一次UTF8 Python读取失败后同一失败阶段被重跑，不是成功阶段重跑，不隐藏。

其他早期阶段如实分类：red-seed是wrapper遗漏大小写不敏感SYSTEMROOT导致Winsock10106，非业务RED；red-boundary是Q信封漏traceId，非业务RED；red-receiver-fixed是JwtAuthenticationToken单参构造未认证，非业务RED。first-implementation的68父正例/nested/business已通过，receipt拒绝后peer将预期socket abort视为失败；仅调整新fixture的已发receipt阶段SocketException归类。generation-profiles两失败分别为StreamConstraintsException被总JSON catch误归权限（已修capacity类型catch），以及第三次真实common代际拒绝码与过严测试预期不符（保留实际SYS-AUTH-403）；不是伪造续核成功。

准备/证据元数据故障也保留：空Git template没有info目录；首次add命令Windows206未启动child；改为确切NUL pathspec。大段shell编辑被Windows206拒绝，随后分块写own helper；未产生部分production写入。controls原helper比较旧locale顺序与Python字典序误报，实际primary-after blob验证已缓存；只续未完成catalog，不再跑测试/编译/Git apply/已成blob阶段。原helper及诊断文件都在own root。一次asset keys诊断错误地打印nested base64，tool截断；资产不含JWT/receipt/私钥，准确登记输出失误，后续只输出身份/计数，不为补美化重跑任何成功阶段。未声称原tool stderr已被后来summary文件原样重建。

**容量与分配核对（engineering charging model）**

80MiB=83886080B一次Owner.reserve，Reservation另128B；发生在context/Verifier/index/parser/新模型之前。B2 lazy1MiB只计一次。F1 readNext的64MiB scan已在finally释放才进入此段；全部parser/Segment/内部consumer及active payload遍历真实退出后才退80MiB，然后才允许下一次F1扫描。

| 分项 | bytes | 实际对应图/过渡 |
|---|---:|---|
| A parser16MiB |16777216| 一个UTF8 REPORT Reader/parser、8KiB reader/decoder、TextBuffer拒绝前扩容、strict duplicate name表及depth栈；不回收/不intern/canonicalize字段名，不保留Jackson异常/cause/source graph。 |
| B index/facts9MiB |9437184| SHA原始byte[]、revision/locator long[]、8192 int槽表与revision槽表、两BitSet；仅固定metadata事实、initial、两target required集合、root labels及两个有界TreeSet floor。没有4096 DTO常驻。 |
| C one scope4MiB |4194304| 当前有界Map/List/TreeSet源模型、MaterialRef/SubjectKey/MaterialScopeV1及Set/Map/List.copyOf并存；P1/P2/P3逐scope丢弃。schema token+64 wrapper余量在token进入时检查，string occurrence计数同时限制拒绝前模型。 |
| D common24MiB |25165824| ceiling/scopeDigest/requireMaterial的sorted/stream/TreeMap、valueToTree/TokenBuffer、normalize/TreeSet、完整String与UTF8及recycler过渡。调用前后drain当前线程4 byte/4 char槽；不动其它线程/全局池。raw scope canonical保守≤1MiB且typed helper更小；initial ceiling新增owner wrapper在131072 chars+8192 token组合上界内仍<1MiB（含固定wrapper余量）。 |
| E payload12MiB |12582912| 先scan后第二次精确Map/List/BigInteger/BigDecimal graph，32768 token、262144B wire和number1000共同限制数字总量；state/event固定小包装、字符串与数值转换scratch。没有metadata整树。 |
| F canonical8MiB |8388608| object Java String排序引用数组与递归栈、单数值≤65536字符cost的BigDecimal/BigInteger/String过渡、generator小buffer和计长MessageDigest sink；不建立8MiB整串/整byte[]/normalize第二树。数值先lexical precision/scale/exponent计代价，未用double。 |
| G fixed/receipt7MiB |7340032| ≤4096 ASCII receipt、严格decoded claims、私有context/window/closure/capture固定图、schema/mapper固定描述、mechanical hook引用与保守余量。任意测试lambda/generator/native图不外推为生产全堆费用。 |

最大收费候选为65536 Owner +3145856 HTTP +1048704 PRIVATE +8659172 wire +83886208 material +384单borrow =96805860B/Owner；双193611720B<201326592B。实际two-owner retention较小；未用一次heap峰值冒充证明。供后续C2的98715562B/Owner只是既定计划候选，本轮没有其frame/writeguard分配。

全部profile在source实现：metadata65536 tokens/name occurrence524288/单name256/scalar65536/number1000；scope8192 tokens（含固定helper余量）/字符串occurrence131072/canonical≤1MiB/parents≤4096；initial同局部模型；persistent16384 occurrence/1048576 UTF16；payload262144B/depth64/tokens32768/scalar262144/number1000；business保守canonical≤8MiB及单数值cost≤65536。strict unknown/type优先于无意义的大字段容量测试。

实际可达边界：metadata scalar65536正例/+1由Jackson string constraint→capacity；oneScope string occurrence131072正例/+1由scope strings guard；payload32768 token正例/+1、262144B正例/+1、depth64正例/+1；number lexical1000正例/+1及integer expansion cost65536正例/+1、巨大指数在BigDecimal/整数展开前capacity。65+父实际为原69scope/max68完整正例，未截断。name256/depth80/4096父与其它固定schema/profile可能先被unknown/本地strings/tokens限制，未放宽schema制造假最高值正例；persistent及metadata总tokens/name occurrence/oneScope canonical等全部组合没有逐一极值正例，本报告不把已实现guard写成全部最大组合的运行证明。保守number cost可先拒会缩零的输入，明确capacity，不修改值。

仍未形式化证明的对象布局：common mapper缓存/JDK BigInteger全部temporary、GC不可达垃圾累计、provider/TLS/JCA/native/线程栈与全JVM RSS。这里是固定输入/固定类型/有限次数的工程收费模板，不是全JVM堆硬界证书。

**Git重放、证据冻结及交还**

主privateGit从实际before623完整树开始；隔离system/global配置、空template、info attributes `* -text -diff`先于首次add，原始blob bytes实际与before manifest一致。before tree `236daba5a8a64a0cc283bc1a82ce8deb5884c2bd`；after index627中未变622 blob继承原实核身份，五变更blob另实核。`.local/iam-o2b-f2c1-material/primary-before-blobs.json`/`primary-after-blobs.json`记录实际object ID/长度/SHA，不是仅ls-tree名字。没有commit/remote。

独立replay初始化后先存完整before623 bytes/names；从未建立replay index或辅助index。`source.binary.patch`为五文件实际binary patch（1既有+4新），首次plain check及首次plain apply各一次且exit0；shell child process cwd仍固定根，Git -C仅选内部replay工作树。apply后不copy任何source；完整after627 names/bytes匹配candidate，no replay index。每步结果先缓存再断言，见replay-check.json/replay-apply.json/replay-after.json及journal。

各阶段raw/source epoch/五source副本、实际input/created lists/Running reports均保留；其他622关系继承同一before清单，不声称编译了其它服务或检查全classpath。source/target/owned/report/handoff/inventory最终冻结。owned inventory覆盖两个ownedRoot、具名.tools helpers与独立报告，每个leaf只出现一次，仅self-exclude inventory自身。交还五source及本轮target/owned写权；之后root独立review/审计，才可接受候选及更新八索引。

**保留顾虑**

候选有意只覆盖私有验证，19项JUnit并非所有参数笛卡尔积或整套IAM任务。上述不可达/未逐一极值guard、whole-branch原F1 ignored.local portability及B2旧Minor由最终whole-branch review继续核；不升级依赖或顺手改旧runner。当前Verifier为单个较长的严格schema/canonical类，遵守单文件allowlist，仍需fresh reviewer核对预算模板和安全关系。真实engine latest/RR/DB membership/row provenance/root payload读取及真实issuer生产契约由相应producer/外部验收承担；本轮不宣称独立证明DB最新行，也不声称Engine重算了三个root（现Reader实际消费snapshot/version_refs，inputs不在本次读取重算内）。公开EVENTS、浏览器字节、Servlet、异步complete、C2每write/flush guard和真实双JVM/PG全链都保持未签收。
