# Frozen binding I1 repair — fresh independent task review

## Spec Compliance

- **修复行为 Spec：通过；Quality：Approved；代码 C0 / I0 / M0。** 原 I1 的真实累计事务期限缺口已闭合。该结论限于本轮三文件私有前置组件修补，不代表公开模型入口、整模块或分支验收。
- **执行合规：存在已发生的不合规。** 初始越界文件/目录名称枚举违反唯一本工程根边界，不能由修复正确或绿色测试抵消。具体事实、转抄身份限制和缺失临时字节分列于下文；不把未知影响编造成确定代码漏洞。
- ⚠️ 完整实物来源、资源关闭、打包配对和原历史限制仍由 root 独立核验。下列实际 XML/日志核查不等于逐历史 JVM loaded-class 证明。

## Identity and scope

本评审完整读取 `.local/iam-model-frozen-binding-review-fix/native-sealed/change.patch` 第1–165行一次至EOF，未重读原1288行补丁、旧inventory或旧绿套件。实际核对以下文件的字节长度及SHA256，全部匹配交接：

- 作者报告 `docs/evidence/iam-model-frozen-binding-review-fix-report.md`：19481B；`f48a3161881f79681b74ecf1a0e369a5fd11134c64b06beefa41a09b21ecb29e`。
- control：5700B；`b2809332656bae0ab37dacf3970ca52aa186a03bf1ce57086da7fea05c65bd92`。
- 窄diff：10303B；`0e0f10c8eb364fc7f8c33918c5305b0fe870b876109e451711a71ec847dc7346`。
- final/source.json：132133B；`2aa978a9b19297fc15ea428f2fddca3cd4a3604f1c00b43174002a8ac826fa7c`。

已读本次独立review brief、repair brief、reviewer模板、原I1独立报告及本轮作者report/control。667→667、3改664原样由窄diff支持其声明范围，完整全量字节核对属于root实物审计。root在评审过程中通知其newidentity已PASS（8565 owned entries、21 control refs、before/final/current667、exact3mods664unchanged、green/package/nativework/applied同源、current8 observed exact）；此处明确为root传递的审计结果，非本评审重复执行所得。

## Strengths and deadline assessment

- `model-gateway/src/main/java/cn/agentplatform/model/application/FrozenModelBindingVerifier.java:69`–`:90`：单调3秒计时保守地从获取连接/开启事务之前开始，在回调入口、store返回后、authority检查后及TransactionTemplate完整返回后检查。没有只依赖声明性的setTimeout(3)，也没有只靠8秒封套。清理耗时超过预算时，第90行在metadata解析及第94行最终IAM刷新前拒绝；事务成功返回后才刷新仍成立。
- `model-gateway/src/main/java/cn/agentplatform/model/infra/FrozenModelBindingStore.java:23`、`:25`、`:29`：两条SET及SELECT各自调用timeout；`:47`–`:59`咨询Spring holder期限并在前后检查中断、单调时间及毫秒TTL。applyTimeout得到的整秒期限再经Math.min(2, getQueryTimeout())截断，holder较大余时不会提升单条2秒上限。SQL的statement_timeout=2000ms及lock_timeout=1000ms保留（`:24`、`:26`）。
- `FrozenModelBindingStore.java:32`–`:39`：每次rows.next和字段物化后重新检查，缺行、多行、null快照判断仍在。不能以ResultSet遍历/取值成功绕过累计预算。try-with-resources保留statement/resultset释放，finally的DataSourceUtils.releaseConnection保留原事务绑定连接管理；回调后及事务完成后的检查覆盖这些close路径耗时。
- `FrozenModelBindingVerifier.java:85`–`:90`：holder超时等RuntimeException转为既有sanitized503；store SQLException同样在`:43`–`:45`转换。PlatformException保留原权限/材料失败语义；未引入重试、线程池、清除interrupt或公开时间/authority参数。
- `FrozenModelBindingVerifier.java:205`–`:212`：checkTransactionTime首先调用独立8秒/中断检查，再比较3秒单调预算。现有私有retained clock、摘要、冻结内容、材料、标签、能力和proof构造逻辑没有本轮改动，未再扩展审核已接受机制。事务外metadata/IAM处理继续受原8秒封套与retained有效性约束。
- 正确限定保证：JDBC整秒TTL可向上取整；例如余下不足1秒时，驱动可能仍执行不足1整秒后才返回。此补丁通过holder毫秒检查和返回后的单调预算拒绝过期结果，并保留PG实际statement/lock cancellation；没有声称普通JDBC可在恰好3秒强制抢占所有begin/commit/close。作者报告`:29`对此明确披露。这与任务明确允许的合作式执行限制一致。
- Store内部3秒计时起点比Verifier的保守起点晚，但store还检查真实holder剩余期限，Verifier在其前后使用同一较早的transactionStarted；成功结果不能借重启Store局部计时越过Verifier累计3秒边界。无新增通用deadline子系统。

## Actual RED/GREEN evidence

以下路径均在 `.local/iam-model-frozen-binding-review-fix/` 下。仅只读现有留存，不运行任何测试。

- `red/command.json`指定新延迟负例加原正常正控，sourceSha=`f46625446d10d0375f2e3ba8e31f0a46cafa3d70f2c147c9504c048c407ec024`；`red/exit.json`实际exit1。`red/tests.log:93`只Running VerifierTest，`:102`、`:125`记录2 tests/1 failure/0 errors/0 skipped，`:135`BUILD FAILURE。
- `red/target/model-gateway/surefire-reports/TEST-cn.agentplatform.model.application.FrozenModelBindingVerifierTest.xml:72`–`:74`的失败是“expired transaction cannot mint a binding proof / Expecting actual not to be null”，非编译/setup错误；`:93`正常真实freeze/accepted consumer正控无failure。对照新测试`FrozenModelBindingVerifierTest.java:18`–`:37`，异常断言前已检查3000–7900ms及borrowed0，故这个RED命中了旧实现返回成功、未按预算拒绝的行为。
- `green/command.json`仅指定Verifier/Store/ConsumerHTTP三类，sourceSha为最终`2aa978…fa7c`；`green/exit.json`实际exit0。`green/tests.log:98`、`:104`、`:318`分别Running这三类，`:103`、`:317`、`:323`分别2/19/1，`:327`总22/0/0/0，`:337`BUILD SUCCESS。没有把RED目录的旧非Running XML计入RED，也没有将旧73绿并入本轮。
- 直接读取上述三份GREEN XML而非只相信test-summary：Verifier XML`:175`延迟负例3.609s、`:182`正控0.317s、`:245`cleanup负例3.573s均无failure；`:164`中断/expiry/pool、`:252`retained expiry、`:263`原8秒延迟8.325s、`:311`真实PG锁超时后健康帧均通过。ConsumerHTTP XML`:72`、`:77`为实际既有双JVM join/numeric digest和producer前置拒绝两例；Store XML`:72`为真实只读事务要求例。
- 新用例`FrozenModelBindingVerifierTest.java:18`–`:37`断言事务readOnly=true/autoCommit=false、3250ms延迟、sanitized503、IAM调用数不变、borrowed0；`:39`–`:50`另测真实连接close后3250ms清理延迟。命名外查既有透明观察器`FrozenModelBindingConsumerFixture.java:128`确认beforeRead发生于真实prepareStatement委托之前、事务状态取自真实连接；`:129`–`:132`确认afterRelease仅在真实close委托成功且borrowed减为0后触发，并解包真实异常，没有替换行或返回值。
- 两份tests.stderr.log均实物0B；读取Running/汇总时未发现GREEN WARN/WARNING/ERROR。RED中的AssertionError与Maven failure是预期行为RED。RED日志以默认UTF8查看时中文路径乱码；严格UTF8读确实失败，随后只读严格CP936解析并逐字节roundtrip相等，正确恢复`:142`本工程报告路径。这是本评审读取编码修正，未改日志、未重放命令，也不把解码显示问题当产品缺陷。
- 测试名称、XML与源码可支持这些具体断言，但完整实际fixture PID/进程命令/classpath、source blobs和有限class/resource/JAR对应关系仍交root独立核验；报告不把XML case名等同于全部运行来源已认证。

## Issues

### Critical — 0

本轮窄实现无确认Critical代码问题。

### Important — 0

原I1已关闭，未发现新的阻断代码/测试问题。执行边界历史违反和证据不足不能写成“完全合规”，见下节；它们也不能仅因不易补救就被虚构成新的运行漏洞。

### Minor — 0

未发现需修补的Minor代码问题。更多边界延迟测试可增强覆盖，但当前具名真实RED、延迟GREEN、清理GREEN及源码检查已覆盖本次缺口，不以泛泛增加测试作为要求。

## Execution compliance and provenance qualifications

- **已发生越界名称枚举。** 作者报告`:102`与`execution-deviations.json:6`–`:32`明确记录两个初始rg命令分别从父根与其代码目录搜索，结果含相邻工程AGENTS文件名；另有父根/代码目录的即时目录名列举。违反仅在授权根工作的约束。没有再次搜索或打开任何相邻路径；不能用本次正确设置cwd或测试成功追认过去命令。
- **转抄身份只证明转抄内容自身。** `execution-deviations.json:8`–`:23`保存两条实际命令/cwd/exit及有限输出，并明确标注recopied finite tool transcript，不是原stdout字节捕获。本评审对记录中两个output字符串重新UTF8计算，分别472B/SHA `8a8273bc1b817bd2f947f01493d9e7fbe0528f9ddaded47ca8ff1cc37dff4b2f`、309B/SHA `976ffcbcb9026cc51ae6832fbf768457f97979026692ed2cfd2eb325a0f583c1`，均匹配记录。这不能证明转抄完整性，也不能从文件名推断相邻代码内容。作者“没有读取相邻内容/启动资源”是声明；root需以原会话命令轨迹核验，当前两条输出不足以独立证明否定事实。
- **即时目录列举元数据较弱。** `execution-deviations.json:26`–`:28`仅记录两处“immediate directories”的文字描述，缺少像两条rg那样的精确命令/exit/完整有限输出。root应从已经存在的会话记录核对这一部分，明确保持证据粒度差异，不以再跑目录搜索补造历史。本评审不因该缺口断言发生内容读取或代码复用，也不替root宣布执行合规通过。
- **Surefire临时字节未保留。** 作者报告`:78`、`execution-deviations.json:47`–`:51`如实披露。直接读取red/green各自surefire-temp-capture.json，均exists=false/files=[]；每份还有两个包含java命令片段的错误解析候选，以及一个合理的真实临时目录候选。不能把不存在或错误候选当作曾完整捕获临时booter/config的证明。实际command/XML/fixture argfiles与post-test finite capture能分别支持运行配对，但不能恢复临时文件字节或历史加载证明。root需保留这一限制；不建议为了补齐历史再跑已绿业务测试。
- **旧执行偏差仍然存在。** 作者报告`:86`、`:106`保留原canonical package/engine JAR写入及失败移动、主运行影响unknown、缺历史loaded-class的限制。新owned package成功只能证明新包，不能消除旧偏差。本轮保护8应对当前root observed baseline核验，不能恢复为“旧8原hash全部相等”或归因未知actor。

## Named outside-diff checks and root pending audit

具体风险外查仅限：

1. diff截断异常catch及IAM边界，无法判断holder RuntimeException/cleanup过期是否绕过最终刷新：只读Verifier第30–113行和Store第14–63行相应完整流程，未重读整份变更文件。
2. 新回归测试观察器可能在事务之前或虚假释放处延迟：只读既有Fixture第109–139行中的透明连接代理/池设置片段，确认上述真实委托位置；未重审业务setup及旧测试套件。
3. 作者可能将setup RED、旧XML或源epoch混为实际本轮运行：只读本轮命令/exit、两个launcher、精确tests.log/stderr、四份实际XML及test-summary，核对实际Running与断言。日志路径由root通过封存inventory提供，没有目录枚举。未重跑测试/build/PG/HTTP/native/javap或进程探针。
4. 初始越界元数据的转抄身份与Surefire临时文件完整性：只读本轮execution-deviations及两个surefire-temp-capture，重算两个现存output字符串hash；未重放任何越界命令。

root剩余实物核验必须独立完成：

- final stable source与RED test-only epoch、GREEN实际Running/XML/process/argfiles/classpaths/有限target字节对应；真正独立engine/model JVM和无 sibling business JAR；已有no retry、lock cancellation、authority/HTTP/connection计数观察的实际记录。
- post-test finite捕获在任何package之前，fresh owned package实际command/cwd/source/exit/log；622有限files、major65、JAR内容与package/post-test class/resource一致、compiler path差异诚实保留。不能将临时Surefire或历史loaded-class缺口写成已补齐。
- 本轮fresh2 DB/4 roles的注册先于CREATE、无旧fixture连接/复用、ACL先于凭据写入、环境过滤、角色隔离/NOLOGIN、sessions0、18记录PID及8已知监听器关闭、调用与外发计数闭合。评审没有主动连接任何DB或探测进程/端口。
- 作者report/control/inventory封存身份和顺序、protected8当前baseline、原历史报告不变、所有本轮运行权限交还；越界命令原始会话轨迹尤其目录列举的缺失明细。执行不合规及未知历史影响应独立保留，不因代码Approved改写。

## Assessment and rights return

**修复行为Spec通过；Quality Approved；C0/I0/M0。执行过程存在明确越界名称枚举，不给出无条件全任务合规结论。** 有效3秒累计期限已在SQL、行读取、回调与事务清理/IAM边界实际施加，具有真实失败先行及本轮22项GREEN证据；完整来源与资源结论待root物理审计闭合后由root决定有界接受。

本评审每次exec_command显式使用唯一授权根 `G:/207/项目/算法重组工具/代码/agent-platform`。唯一写入为 `docs/evidence/review-iam-model-frozen-binding-review-fix.md`，未启动子agent/后台工作，未改源/target/config/helper/运行状态。封存后交还 **ALL review/report rights**；没有保留source/DB/HTTP/runtime权限或后台任务。