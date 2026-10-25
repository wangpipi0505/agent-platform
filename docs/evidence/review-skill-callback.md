# HTTP CALLBACK 独立实现与安全质量评审

日期：2026-10-20。结论：**本阶段限定范围通过，未发现新的确定 P1/P2。** 本评审覆盖新增 CALLBACK 实现及其必要交付修复，不只是原 H1/H2 复核。评审者独立运行 CallbackHttpTest + SignedCallbackTest，**34/34、0 failures/errors/skipped，BUILD SUCCESS，06:00:53 +08:00，退出码 0**。作者最终全模块 132/132 日志另行核对，不冒称评审者重跑。

后续响应字段更名已完成独立只读复核，结论通过：当前字段为 `deliveryAccepted`，不再返回 `logicalAccepted`。最新12文件摘要全部匹配，作者133/133日志已核对；本轮更名未由评审者重跑测试或真实进程纵切。下文原34/132及旧字段内容保留为原实现阶段证据，当前接口解释与闭环结论以末尾补充为准。

## 对象、权限与依据

唯一工程根为 `G:/207/项目/算法重组工具/代码/agent-platform`。使用 code-review、requesting-code-review、verification-before-completion 组织检查。已依据本工程 AGENTS、README、implementation-brief、contracts/README、冻结技能调用 §3.2/§3.3/§6 和本轮 skill-callback.md，检查完整新增服务/控制器/协议实现、数据库迁移和测试，并读取 SkillExecution/SkillOutbox、SkillAdapters/ArtifactStore、SkillRules、公共 JWT/SecretVault 及既有引擎事实裁决的必要上下文。

未读相邻旧工程，未改业务源码或冻结契约，未启停/部署服务、Git 提交或另派评审代理；唯一评审文档产物为本文。获授权运行单 skill module 定向测试、不带 -am；测试自身正常使用 target 及专用测试数据库/HTTP/MinIO 夹具。

实际 Get-FileHash 核对 `skill-callback-sha256-final.txt` **12/12 匹配**：SkillCallbacks、SkillExecution、SkillCatalog、SkillCallbackController、SkillController、SignedCallback、AsyncHttpResponse、HttpAsyncAdapter、SkillAdapters、V309__signed_callback_receipt.sql、CallbackHttpTest、SignedCallbackTest。使用 final 清单，未以中间 skill-callback-sha256.txt 代替。源码行号绑定 final 清单版本。

冻结详设快照仍为 `3C72B2062BEB2E8B377F2384843EA935A0074B50C0063E5106566BBD8DCD0C4F`；旧 V308=`4A2229C9C0DF0BC95C6E782306ABF01FC60A7B7BCB3F9A825BE6B0EE15390DAF`、旧 AsyncHttpExecutionTest=`464736B6A2D287A165A211F92BC249389C687E2CA5377FFAC5318DD3B6C17B26`，实际摘要与原阶段一致。

## 规格及安全边界检查

| 检查点 | 实际实现与结论 |
| --- | --- |
| 服务身份，不以 HMAC 代替授权 | 公共 SecurityConfiguration 通过 Nimbus HS256 验签、issuer/时效/aud/token_use 验证；internal 路由要求 ROLE_SERVICE。SkillCallbackController 再明确要求 Jwt、service token_use、internal audience 和 Actor 的真实 SERVICE 角色，ADMIN 不能单独替代。SkillCallbacks.binding 查询包含 JWT tenant/principal。正确 HMAC 的其他主体/租户仍被拒绝。 |
| 签名字节和时间窗 | SignedCallback 对头格式先拒绝，timestamp 为 1–12 位十进制秒、±300 秒；Mac 输入为 timestamp 原字符串、换行、原请求流字节。没有 JSON 重序列化后验签。签名固定 64 hex，比较两边 32 字节 MessageDigest.isEqual。每次重投需重新满足时间窗，receipt digest 是正文摘要，允许同正文重新签时间。 |
| 资源与解析边界 | HMAC 和 SHA256 以 8 KiB 块同步处理，原正文磁盘硬限 64 MiB；超限在继续写入前拒绝，最多额外读一个块。验签通过后才解析。AsyncHttpResponse 有 document 64 MiB、250000 token、depth100、string8MiB、name1024 与重复键/尾随 JSON 拒绝；抽取 result 的临时文件另有64MiB写入硬限。metadata 65536 是字符/标量预算，不宣称其为严格 UTF-8 字节限额。正常/异常退出通过 close/delete 清理临时文件。 |
| 原调用归属 | SkillCallbacks.receive 94–119 以 PG binding 的精确 skillVersion 与 contractSha256 加载冻结合同；body 的 call/attempt/version 必须为可转换 long 的 JSON 整数，其余 key/run/job 为文本并与绑定匹配；event/job 非空且≤128字符。send_started=false 或 NOT_SENT 均拒绝。body 中自报身份不进入授权链。 |
| 前置登记和 ACK | CALLBACK 继续复用 PREPARING、原 claim/submitted 和 send_started 栅栏；ACK 只校验 job/key。HttpAsyncAdapter 对 CALLBACK 初次提交后保持 UNKNOWN 等回调，只有显式 queryOnly 且有合法可选查询配置才查原 key/job，不隐式重发。 |
| early ACK | job 尚未由 ACK/独立查询证明时，当前授权和权威通过后只记 WAITING_BINDING，返回409；回调不写 external_job_id，不生成 artifact、不保存结果正文。相同 eventId/逐字节相同 body 可重签重投；不同摘要409。ACK永久丢失、又无独立查询时需提供方重投/人工处理，未被伪称无重投交付保证。 |
| 当前授权 | 128–143 在验收/MinIO物化前重跑当前 guard/inputSchema，再用 requireHistoricalScope 验证当前范围覆盖冻结 guardedArguments。拒绝记录 AUTHORIZATION_DENIED，原执行事实仍UNKNOWN；同一收据恢复为足够权限可继续。STORED/LATE/SUPERSEDED 的重复回包也先验签/归属/当前授权，仅返回收件元数据。原始结果临时解封不等于已向用户公开或创建受控产物。 |
| 确定失败语义 | FAILED 仅允许 PARAM_ERROR、NO_PERMISSION、QUOTA、RETRYABLE_FAILURE；不需要成功 outputSchema 的 result。raw=null，structured=null、fileRefs=[]，只保留确定错误语义与服务端固定说明。带 result 的失败报文仍受签名/总字节/JSON结构闸，但不使用其作为成功内容或物化为文件。RESULT_UNKNOWN/空/未知语义422，不产生确定FAILED。 |
| 成功结果 | SUCCEEDED 必须能打开抽取的 result，调用已有 adapters.read、原 outputSchema、returnSpec；超阈进入已有受控 MinIO 分支。Schema/returnSpec/缺结果被422拒绝且不改原UNKNOWN。FAILED 特例没有削弱 SUCCEEDED 验收。 |
| 收据唯一性与处理租约 | V309 主键tenant/call/event；ensureReceipt 200–221 原子登记并比对完整正文SHA256。claim 256–297 短事务锁同call执行事实、清理过期PROCESSING，再按部分唯一索引/当前PROCESSING数量仲裁。已有处理中租约409；5分钟后重投必须重新经过身份/签名/归属/授权/权威检查。没有锁跨网络。 |
| 原子事实及旧owner | store 300–339 锁fact，核对 receipt PROCESSING、owner、租约尚有效，再重核 job/attempt/send_started；同一事务写执行事实终态、PENDING和receipt STORED。另一事实已经确定则SUPERSEDED，权威不再有效或代次不匹配则LATE。finishOwned带owner与PROCESSING条件，旧worker不能清掉已被接管的新owner。 |
| 取消及 TOCTOU | current 224–253 在输出处理前后以原主体服务票据读取 tool-call 和 run/node，核对精确调用/参数摘要/合同/节点/attempt及允许状态。预检晚于取消则仅记LATE；检查后仍可发生取消，最终由已有 engine.fact 的原子租约与运行状态条件裁决。该服务没有直接改引擎run/node/事件。 |
| STORED、DELIVERED、逻辑采纳 | receive 不同步重新执行技能；response 366–381 单列receipt outcome和fact deliveryState，仅 STORED 且DELIVERED时 logicalAccepted=true。STORED + LATE明确false。STORED表示事实持久化，不等于引擎已提交结果或节点完成。 |
| 旧 UNKNOWN 在途响应 | SkillExecution.deliver 831–855 对accepted/late回写及error回写同时比较tenant/call/attempt/status/result JSON。旧UNKNOWN响应不能消费或污染新的SUCCEEDED/PENDING。原execute末尾UNKNOWN条件更新也不能覆盖回调先落的终态。 |
| 契约入口与凭据 | CALLBACK仅HTTP POST JSON；混用httpMapping/其他方法拒绝；可选reconciliation仍限定GET原job/key。callback secretRef 注册要求ADMIN并经SecretVault证明租户归属，仅冻结引用；管理视图递归脱敏为secretConfigured。未放开MCP异步等未支持组合。 |

## 实际测试与断言边界

评审者独立执行：

```text
mvn -B -ntp -pl skill-registry test -Dtest=CallbackHttpTest,SignedCallbackTest -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

仅从本工程 .env 读取进程变量且不输出密钥，选择Java21，不带-am。结果 CallbackHttpTest31 + SignedCallbackTest3 = **34/34，0 failures/errors/skipped，06:00:53 +08:00，退出0**。Flyway确认专用 `agent_platform_skill_callback_test` 当前schema309且无待执行迁移。Surefire报告与命令输出相符。

已逐条阅读断言，而非仅看总数：

- 身份用实际 MockMvc + 公共 springSecurityFilterChain/Nimbus 解码；send方法使用Authorization Bearer，未用jwt()测试注入绕过签名链。匿名/坏JWT/带SERVICE角色用户票据/其他principal/其他tenant被拒绝。创建测试技能时直接设置 Actor 是测试准备步骤，不等于回调入口绕过过滤链。aud/token_use/issuer的源码链已核对，未宣称对每个JWT畸形组合都另有负例。
- HMAC错误、正文增加空格、±301秒拒绝；primitive补充中文UTF-8与换行逐字节保留、超过64MiB只再读≤8192字节、无效时间在读体前拒绝。没有声称做了恒定时间侧信道测量。
- 逐项错误job/key/run/call/attempt/version保持UNKNOWN；同event不同摘要409；6路同事件并发只留下一个receipt并维持一次submit。并发测试接受200/409，最后重投确认SUCCEEDED。
- 真实HTTP提交屏障下早到回调409/WAITING_BINDING且PG job仍null；ACK后同body重投成功。ACK丢失不能靠回调夺取绑定或重发；另有原key查询证明绑定后同event恢复用例。
- 当前AB→A返回403、receipt AUTHORIZATION_DENIED、fact UNKNOWN，再以AB重投可继续。该测试没有检查每种文件旁路；物化前调用顺序由源码核实。
- 成功Schema失败为OUTPUT_REJECTED；returnSpec同时核对structured、summary、card；2MiB结果验证受控fileRef和fact。没有逐字节下载整个MinIO对象做比对。
- 最终新增失败用例分别验证无result的QUOTA、非成功Schema结果的PARAM_ERROR均接受但不公开structured；RESULT_UNKNOWN、缺失/未知语义均422、fact UNKNOWN。其余两种明确失败枚举NO_PERMISSION/RETRYABLE_FAILURE的接纳由源码集合核实，未夸称四种都逐项动态覆盖。
- 原取消run/旧attempt只记LATE；expired PROCESSING图像能恢复、live租约409；另有独立恢复已写终态后过期receipt转SUPERSEDED。旧owner不能提交由带owner/租约的SQL及短事务核实，现有租约测试重建持久图像，未实际让两个活worker跨5分钟交接。
- PG trigger故障注入receipt STORED更新，验证fact同事务回滚为UNKNOWN，撤除触发器后可重投成功。响应丢失场景实际是忽略一次成功响应再重投；引擎503重投使用真实SkillOutbox。旧UNKNOWN在途accepted/late/error三种屏障断言均验证新终态仍PENDING且callback_error为空。
- 入口Spring过滤链和PG是实件，外部供应方与engine是测试HTTP服务；engine夹具没有真实引擎的完整鉴权/取消/租约实现。终态逻辑采纳安全性还依赖已检查的engine.fact裁决，不据此宣称本轮跑过真实engine跨进程纵切。

## 原始红绿链核对

| 作者记录 | 读取结论 |
| --- | --- |
| contract-red → contract-green | 注册CALLBACK断言1失败→1/1，05:43:31。 |
| http-red → http-first → http-green | 16项15失败→16项1失败（旧UNKNOWN ACK吞新终态）→Callback16+Async21共37/37，05:47:49。早期红多在共同准备路径，不能算每个安全负例都有独立红。 |
| protocol-red → protocol-assert-red → protocol-green | 27项1failure+1error（DELETE与尾随JSON归一）→2条明确断言失败→27/27，05:50:30。保留中间error身份。 |
| full / lease-red | 中间128项0failures但3errors（缺省invocationKind触发Set.contains(null)）；过期receipt独立1failure（预期SUPERSEDED，实际PROCESSING）。均不能写成通过。 |
| full-final / formatted-final | 失败语义纠正前129/129，05:53:32；最后测试格式整理后定向31/31，05:54:51。两者均不是最终132记录。 |
| failure-red → failure-full-final | 3项2failures：无result和error result的有效FAILED被422错拒；最终132/132、0failures/errors/skipped、05:58:09。源码已改raw=null失败信封，评审者34项独立运行亦包含这3条。 |

作者报告称读取JDK21 src.zip中的MessageDigest.isEqual/Mac.update并运行AOSP格式干跑；本评审核对了本实现的固定长度比较与逐块调用，未另行重读JDK源码或运行格式器，不将作者这两项操作写成评审者亲跑。完整132项回归同样为已读取的作者日志证据。

## 结论与保留边界

规格符合性：CALLBACK在已登记、已发送、精确原绑定的前提下验签/授权/去重，确定结果走正确成功或失败分支，技能侧只持久化事实，逻辑采纳仍由引擎唯一裁决。安全质量：本轮实现和独立34项验证未发现确定P1/P2，接受本阶段有限范围。

不据此关闭全部T11。可信外部relay、专用可达回调URL、供应方共享secret_ref与短服务票据换发、真实engine/供应方验收、NOT_APPLIED新代授权重试、外部取消三态、UNKNOWN人工产品闭环仍保留后续边界。ACK未形成可信job绑定时依赖重投/独立查询，不提供无需重投的回调交付保证。PROCESSING崩溃通过持久图像验证，未OS kill；输出在最终store之前创建的artifact若遇取消/崩溃，沿既有TTL/登记清理，没有证明即时全量回收。短事务和处理租约不等于生产容量/负载验收。

原HTTP评审另列的“非UNKNOWN历史执行事实再次交付”的授权语义待核对项不属于本次CALLBACK新增入口缺陷，也未被本报告默认为全面修复；CALLBACK自身重复STORED收件会重验当前权限且只返回收件元数据。

## 后续定向复核：deliveryAccepted 字段更名

结论：**更名范围通过，无新增确定P1/P2。** 这次只读复核纠正的是回包字段的解释，不改变事实采纳、权限、安全或执行状态机。原 `logicalAccepted` 名称容易使调用方把一次outbox投递的ACK误认为引擎全局逻辑结果；先由ExecutionRecovery/W3按同factId采纳、旧代outbox随后LATE是合法顺序，此时投递布尔为false不代表run尚未成功。根任务报告的该真实纵切触发作为本次更名背景，评审者没有把它冒称亲跑。

实际核对 `skill-callback-delivery-field-sha256.txt` 12/12匹配，对比原final清单只有SkillCallbacks.java和CallbackHttpTest.java两项不同，其余10项不变。当前SkillCallbacks SHA为 `8C644CB051A46C435DFEBAA8C33B88E831F00E665F218C7D2AC092A332091D0B`。另外做了不落盘的确定性核验：读取当前文件，在内存中恢复旧字段名并移除两行新解释注释，计算SHA得到原 `2BEA510B74A91DE5339A8B59CD1408DD6C869BEC9073AE518F9844E2EAADD42D`；证明业务源变更确实仅限该字段名和注释。

SkillCallbacks.response 当前381–382返回 `deliveryAccepted = receipt.outcome == STORED && fact.callback_state == DELIVERED`，布尔条件与原版完全相同。它仅说明所记事实投递ACK，不承诺全局未采纳/已完成；`false + LATE`可以与W3已采纳、run SUCCEEDED并存。没有为解释这个值新增engine查询、冗余authority字段或另一个逻辑提交者；最终运行/事件仍由engine权威读取。冻结原契约未修改，这一实现补充API旧字段被移除而非并行保留。

两条测试实际断言已读：

- `receiptReportsDeliveryAcceptanceWithoutClaimingGlobalLogicalAcceptance`：STORED+LATE，deliveryAccepted必须是实际JSON boolean且false，logicalAccepted不存在。
- `deliveredReceiptReportsAcceptedDeliveryWithoutGlobalLogicalAcceptanceField`：STORED+DELIVERED，deliveryAccepted必须是实际JSON boolean且true，logicalAccepted不存在。

已读原始 `skill-callback-delivery-field-red.log`：两项、两条assertion failure、0errors/skipped，06:08:21；原回包缺新boolean导致两分支isBoolean断言失败。已读 `skill-callback-delivery-field-full.log`：133项、0failures/errors/skipped、BUILD SUCCESS，06:09:46，CallbackHttpTest32、SignedCallbackTest3、原Async21均在内。以上是作者日志及本次源码/摘要复核，不写成评审者本轮运行。评审者亲跑34/34仍仅对应之前132版本。

本轮没有改业务代码、运行Maven、启停/部署服务或重跑真实四场景；只更新本评审文档。`.local/signed-callback-live.json` 与 `signed-callback-live.md` 所代表的旧132字段真实纵切记录不能作为新字段已经实测的依据；根任务后续更新本工程服务并重跑四场景前，本报告仅确认源码/API更名与测试证据闭环。T11及供应商/relay等原边界不因本小修关闭。
