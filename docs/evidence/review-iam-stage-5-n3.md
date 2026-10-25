# N3 独立任务审查：Spec Compliance 与 Code Quality

## Spec Compliance

**结论：❌ Issues found。** 原 N3 受理链、范围限制和大多数安全边界已按 brief 落地，但发现两个实际异步/阻塞边界缺少发送前复核：提交后的 metadata 查询可跨过授权期限后仍调用 `scheduler.created`（I1）；published 的 executor 任务可在原 proof 已失效但 2 秒传输期限尚未到时启动 HTTP（I2）。这两项需要修复后复审，当前不能签收 N3。

**范围依据：** `docs/evidence/iam-stage-5-n3-admission-app-client-brief.md`（17150 B，SHA256 `cf5246583b8fea428592e2cefe27a2d1cc05bd31665a8c704b84911fc2865a93`）与唯一具名 `scope-addendum.md`（2681 B，SHA256 `b2d15a89d4b12cd135c3acd3db2c1bf6ebfd048e178bd80e05d76f981c1b15d9`）。原三个生产 Java 加 engine POM 一个固定 httpclient5:5.4.4 依赖符合该例外；不把原 brief 的未修订文字单独解释为禁止已批准例外。没有要求本轮完成真实 GatewayController 或普通全局 IAM。

## Code Quality

**结论：Needs fixes。** 当前实现复用了真正的原业务链和既有授权材料接口，测试有实际 HTTP、PG、事务故障与并发行为。两个问题都能通过现有类中的局部修正和定向边界测试解决；不需要复制受理流程或新增传输框架。未发现 Critical 级数据泄漏/跨租户越权证据，但这不替代 I1/I2 的明确期限不变量。

## 已确认的优点

- `RunController.java:37–52` 在 IAM 路径验证实际 POST 和匹配模板，只开放 admin run。测试对持有效 targeted main 的 v1 alias 同样拒绝；没有仅依靠无效票据让别名测试自然失败。
- `RunService.java:371–388` 在任何受理授权/应用外发前拒绝 ambient transaction，随后沿原 `createInternal` 运行输入、guard、DAG、预算逻辑。`175–217` 的有限节点/依赖 gate 位于 `492`，先于 `prepareSnapshot`；正常空容器与 null 的兼容不导致旧客户端外发。
- `RunService.java:632–648,727–735` 实际以 `RunAuthorityStore.createPublished` 与 `TraceStore.createNode` 替换 IAM 分支的裸 INSERT。窄查 `RunAuthorityStore.java:22–72` 确认其同源事务、初始 ceiling/scopes、随机 publicId/input_ref 及 rollback-only 接口吻合，未在历史行上补授权。
- `RunService.java:105–107,241–260,403–411,444–462,581–599,777` 的三处 requestId 分支保留窄 metadata；已存在结果在锁外进入单次 `statusPublished`，再执行原 `verifyReplay` 和提取的 `composeStatus`。`1629–1687` 没有复制第二套完整响应，末尾仍 `published.requireCurrent()`。
- `RuntimeClients.java:44–116,121–145,219–237,278–281,331–339` 使用已验证的显式部署属性、固定 app origin、实际私有 retained proof、有限 service switch、同 root forwarding，以及 D3b 数字 ID/版本键/候选摘要校验。未用返回 JSON hash 冒充 candidate digest，也未把 public VerifiedAuthority record 当来源证明。
- `RuntimeClients.java:78–105,167–212,248–275` 的实例级 retries/redirect/auth challenge/compression 关闭、4 MiB 限制、完整 body future 期限和取消生命周期均是实际代码，非仅注释承诺。历史 JDK GET 重发行为红和具名依赖例外有保留证据。

以上生产文件均位于 `engine-runtime/src/main/java/cn/agentplatform/engine/` 下，路径分别为 `api/RunController.java`、`application/RunService.java`、`infra/RuntimeClients.java`。

## Critical

未发现有证据支持的 Critical 项。特别是 I1 不意味着后台 owned worker 绕过 current 授权，I2 不意味着 app receiver 已泄露 payload。

## Important

### I1：提交后 metadata 查询返回，必须再次校验 bound 再调用 created

**位置：** `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:767–779`，关键调用 `778`；变更包 `1615–1635`。

**实际顺序：** `store.atomic` 真正返回 → `requireBound(admissionProof)` → 单独 `queryForMap(responseMetadata)` → 新建分支 `scheduler.created(run, admission)` → 下一次 `requireBound`。该 metadata 查询处于已结束事务之外；前面 `SET LOCAL` 的 statement/lock timeout 也已经结束。连接获取、数据库响应或锁等待都可能使查询返回晚于 proof 有效期。

**具体触发：** 新 run 的 commit 已完成且 `767` 检查通过，在 `768–774` 的 SELECT 期间推进测试授权时钟（或现实中查询等待使其到期），然后 SELECT 正常返回。`778` 仍被调用；`779` 才拒绝请求。

**实际影响：** 失效请求已发出调度通知，破坏 brief 的“提交后失效阻止继续调度通知”要求。窄查未变的 `RunScheduler.java:235–273` 确认带 Admission 的 `created` 会进入 CLAIMING、`repository.claimSpecific`、`launch`，不是无副作用记录。已提交 run 和材料不能回滚，响应最终拒绝也不能撤回已发通知。后台任务自身另取合法 fresh current 后继续执行仍是允许行为，本项不将其错误扩大为无授权 worker 执行。

**最小修复：** 保留实际 commit 返回后的检查，在 metadata 查询与 created 之间、新建分支调用 `scheduler.created` 的紧前，再进行同 caller/frame 的本地 `AuthorityContext.requireBound(admissionProof)`。不引入锁内刷新，不改 scheduler 的合法恢复机制。

**定向验证：** 在 `PublishedAdmissionHttpPostgresTest` 现有 `afterQuery` 测试观察接缝，对该提交后 metadata SELECT 返回时只触发一次 proof 过期。要求请求拒绝、`createdCalls=0`、已提交 1 run/2 nodes/15 scopes 保留 PENDING、attempt=0、slot 恢复；之后合法 main/poll 可恢复。现有 `expiryDuringNodeMaterialsRollsBackButAfterActualCommitPreservesPendingAndSlot`（测试源 `1106` 起，包 `4468–4514`）只在 actual commit 回调处过期，不能捕获 commit 检查之后的新查询窗口。本审查未执行该新增测试。

### I2：caller 的 pre-submit 检查没有覆盖 executor 真正开始 HTTP 的期限

**位置：** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RuntimeClients.java:140–154`，关键 `152–154`；变更包 `3083–3100`。相关检查 `199–200,237` 位于完整响应后。

**实际顺序：** caller 在 forward 返回后 `requireBound(retained)`，创建请求并提交 executor；Callable 直接进入 `publishedHttp.execute`。执行任务没有 retained proof 的本地有效性校验，也没有以该 proof 的剩余期限约束发出动作。2 秒 future 期限是传输上限，不等于 main/current proof 的到期时刻。

**具体触发：** forward 后原 bound proof 仅剩很短有效时间，caller 检查仍成功；虚拟线程任务调度延迟跨过该有效期，但尚未达到 `response.get(2000ms)` 超时。任务启动后依旧发出带 forwarded token 的 app HTTP GET。这个窗口也不需要证明存在无限队列或真实线程泄漏。

**实际影响：** 已失效的原受理 proof 仍触发 app 请求，违反实际发送前有效性的边界。完整响应后的检查可阻止向 caller 交付，却不能实现“过期不发送”。app 的 receiver/当前授权仍能独立拒绝，因此这里不声称已突破 app payload 授权。

**最小修复方向：** 保留 owner 线程上的 frame-bound 检查，同时在异步任务真正进入传输前验证捕获的私有 retained proof 仍有效；必要时结合具体客户端的请求发送接缝和剩余期限，确保等待后不以陈旧 proof 发出。已有 `HttpCurrentAuthorityClient.RetainedAuthorization.requireVerified()`（`admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java:123–126`）按私有 verificationClock 检查 current/ticket，且不读取 ThreadLocal，可用于这种局部有效性检查。不要把 `AuthorityContext.requireBound` 直接移到工作线程，不要重新 bind public VA。注意工作线程抛出的授权异常目前会被 `ExecutionException` 分支统一变成 503，修复时需保持明确授权失效语义。

**取消边界的区别：** 已窄读固定 Apache 5.4.4 源。`HttpUriRequestBase.java:59–85` 的 cancel 标记与后注册 dependency 取消，`InternalExecRuntime.java:96–126,213–237,295–303` 的 lease/执行前取消检查和立即丢弃 endpoint，支持当前超时/中断后的防线。没有证据表明已执行 `request.cancel()` 后还必然迟发。本项专指“proof 已过期而 2 秒 timeout 还没到、cancel 尚未发生”的窗口，不把两种情况混在一起。

**定向验证：** 有界阻住 executor 的实际工作开始，在 pre-submit 检查通过后推进 proof 时钟到失效，保持传输 2 秒期限未到，然后释放执行；断言 app 接收次数 0、返回明确拒绝、client 能结束。现有 `forwardReturningAfterProofExpiryCannotSendAppRequest`（测试源 `500` 起，包 `5322–5353`）使时间在 forward 返回前推进，覆盖的是 submit 之前；headers/complete-response 过期测试也没有覆盖此处。本审查未运行新探针。

## Minor 与维护建议

没有另列会阻断的维护项。Ponytail 复杂度检查未找到能安全删除的独立框架、无用扩展层或第二套受理流程；不建议为了缩行删除实际授权 fence、取消分支或 PG/HTTP 负例。已批准 Apache 依赖有实际零重发缺口支撑，不能凭“可用 JDK”直接删除。可确定的额外净删行数：0。

测试输出不是 pristine：现有 unchecked 提示、受控负例 scheduler WARN、实际 advisory lock timeout 的 SQLSTATE 55P03/servlet ERROR，以及 Windows dumpstream 仍应保留为噪声/环境限制。`noise-audit.json:545–583,640–643` 可定位最终阶段记录。没有因这些记录而修改公共错误映射或全局 argLine；它们不抵销真实的行为断言，也不被包装成无噪声通过。

## 六跨度处置

| 跨度 | 本轮实际处置与证据 | 剩余边界 |
| --- | --- | --- |
| 全局约束、契约、mode closure | 生产 diff 仅原三个 Java 和获批 POM 一项；固定 Boot/Java/PG 业务边界未扩张。`RuntimeClients:49–54,278–281` 由验证属性决定 mode，并在签发/发送旧 Actor 路径前拒绝 IAM。`RunController:37–52` admin 模板限制有效。root 保护结果记录设计/brief/runner/八索引无漂移。 | 普通静态 ServiceTokens 和完整 Spring IAM 的统一仍是 N4；隔离 IAM client 的 JVM-mode 反证测试不代表全局闭合。原 `IAM_TRUST_CHAIN_UNAVAILABLE` 保持关闭是本轮约束，未在本审查重新全仓扫描。 |
| common/domain authority seams | 窄读 `AuthorityContext:34–38,45–68` 确认同 frame identity、失效闭锁及 refresh 非事务内行为；`RetainedAuthorization:103–127` 为私构造证明且按验证时钟验证；`RunAuthorityStore:22–72` 同事务材料写入与初始 label union 正常。`RunService:225–239` 先检查完整初始角色/属性覆盖，不能 A/B→A 后先入库。 | common/domain 无改动，不重新签收此前所有 authority 任务；I2 是当前使用 seam 的新边界问题，不建议扩大 common API。 |
| 当前原业务链、scheduler、storage、admission、client | 全包覆盖原 createInternal、status、RuntimeClients、RunController 及三个新增测试。真实 app own-classpath HTTP/PG 返回 numeric ID；保留 InputDeclarations、DAG、预算、纯节点和输出映射。三重 requestId metadata→锁外单 RR 组合无复制响应；owned worker 的 current、materials、scope/root/owner保护由既有接口与受影响测试支持。 | I1 和 I2 阻断当前 N3 签收。后台合法新 current 推进和原提交后 durable PENDING 保留均是正确行为；不与失效请求通知混淆。 |
| 待迁移 gateway、恢复、非纯执行、Observer/RR、真实环境 | `PublishedAdmissionFixtureMain` 与 engine fixture 装配真实业务 controller/service/guards；签名 main 来自 LoopbackAuthority，并未经过 GatewayController。依赖 gate 在 legacy prepare 外发前穷尽拒绝当前不支持的节点/依赖。 | 真实 gateway、普通 IAM 启动、独立 GET status/SSE、CHAT/工具/模型/Session/history/replay、完整恢复、Observer/RR1/RR2、门户浏览器、真实 IAM/本体/K8s/容量明确 pending。理由是本 brief 明确仅 N3 component chain，现有四类测试不提供这些工作流的证据，不能写全 Task5 完成。 |
| 证据、source/target、provenance、测试、噪声 | 下节记录本审查实际读取与 root 独立物理审计边界；final01 三类40项与 final03 client19项分别成立，历史 error 不删除。helper 初次失败源是 SHA 对齐的重建，非当时物理采集。受损 scope fixture 有独立 bounded Tx/trigger 恢复，不当成合法创建。 | 本审查不重复源码/target全量哈希、Maven或数据库探针。现有两类新增测试未覆盖 I1/I2 对应窗口，因此当前绿不能消除此两项。root 四个工具审计噪声与本 reviewer 一次错路径 rg 均不计业务红。 |
| 固定开源比较、注释、许可 | reading-manifest 23 项的固定 URL/version/license/SHA 已读；root 独立审计核物理源与归档/既有三二进制。reviewer 对实际 Apache cancel/lease/execute/endpoint 清理作了具名源码窄查，确认上文取消判断。注释说明 bound frame、真正 commit、requestId RR 与超限先 cancel 的边界。 | Spring6.2.7/PG16.15 和已放弃 JDK 的全面源码对照采用已冻结 author 记录和 root 复核，不宣称本 reviewer 重读全部上游。JDK `jdk-21+35` 只作机制参考；运行时为 Temurin21.0.12+8，不能宣称精确二进制一致。Apache LICENSE/NOTICE 与固定5.4.4/5.3.4归档存在，不代表已完成未来依赖升级审计。 |

## 阅读覆盖、证据与限制

1. 先读工程 `AGENTS.md`、指定 subagent-driven-development SKILL/task-reviewer-prompt，随后读 code-review、ponytail-review、requesting-code-review SKILL/template。本审查为指定 fresh task gate，不是未限定的全仓合并审查。
2. `docs/evidence/review-iam-stage-5-n3-package.diff` 已从首行到 EOF 逐段读完，每次最多250内容行；294075 B，现场 SHA256 `ec70cc8315d54e74f2cb4bead401e0f7f6f043c6752f71d1264ed1eaba1c7bd9`。root 的按 newline split 计数为5536；PowerShell Get-Content 展示5535内容行，末尾 newline 无额外文本。完整上下文包含三个 changed Java、POM、三个新增测试。没有截断包，也未重新整读 changed live source；只 `rg` 定位当前行号。
3. author report 195行/28426 B 已完整读取，现场 SHA256 `0312f487df70a93a124f4e183834c8d88eb3dc251333a7862351fc6a352507a4`。brief、addendum 的现场 SHA 与要求一致。机器 handoff 和 current-covered-tests 完整读取；test-audit/noise-audit 按最终阶段具名读取并与 report 全阶段记录、root audit 对照，不宣称重新读完15轮所有 raw/XML。
4. 具名的 diff 外源码窄查：I1 的调度副作用/Admission slot 状态风险 → `RunScheduler:134–273`；初始完整义务/同事务接口风险 → `RunAuthorityStore:22–72`；跨线程证明与原 frame 风险 → `AuthorityContext` 的 require/refresh 和 `RetainedAuthorization:103–127`；I2 取消后迟发判断 → 固定 Apache `HttpUriRequestBase`、`InternalExecRuntime`、`MainClientExec` 的对应区间。没有扩展到旧工程或重复此前不相关代码审查。
5. 本审查读取 `.local/iam-n3-root-fast-check.json`、`iam-n3-root-protected-check.json`、`iam-n3-root-audit-addendum.json`：root 独立物理核 before364 source+1764 target，baseline364，live/frozen/replay各367 source，live/frozen各1795 target；source新增3/修改4/删除0，target新增31/修改11/删除0/JAR0；真实 apply-check/apply退出0，367文件 replay一致。root 核15阶段实际 Running/selected XML/source，incidental XML是旧文件metadata，不计实际执行。此处采用 root 物理审计结论，不伪称本 reviewer重新执行这些工作。
6. root 保护审计90336静态+28 runner+8当前索引，共6841049754 B，unverified为空、原design/brief无漂移。root 补充核own app classpath115项全部在新工程、无其他业务域，固定Apache sources.jar及三个现有binary逐SHA一致。root 的四个路径/schema/parser错误是已保留工具噪声，未导致重新跑业务测试或改source/target。
7. 当前测试的独立 raw 定位：final01 raw `75/140` FlowMaterialStatus11/0F/0E、`141/232` OwnedFlowScheduler10/0F/0E、`233/567` PublishedAdmission19/0F/0E；同轮 client `568/569` 为19/0F/1E，整轮 `585` 为59/0F/1E。final03 raw `70/71,75` 为client19/0F/0E。准确当前覆盖是 **final01另三类40项 + final03 client19项**，不是一次59项全绿。final02 client19/1E保留。root已经核 final01之后只改新的 legacy test mode/profile setup/finally，生产/POM/另三测试未变；不要求为凑总数重跑同码三类。
8. 真实 app fixture 的初次失败 helper javac 源未当时复制，SHA匹配重建的来源限制已在report/compile provenance承认；不能提升为同期物理副本。own app/common 类路径、私有STDIN凭据、固定新DB、原 guards 与正常PG published行的证据支持本轮隔离 component chain，不能等同真实发布管理流程。
9. 锁超时实际55P03经原/error guard形成403是既有错误映射；本审查没有要求为此改common。测试内丢失scope模拟在新库独立事务中只临时disable精确trigger、删除目标行后finally恢复，随后查询确认enabled；不能据此宣称生产能够backfill。
10. 生命周期结论采用已核 cleanup：own app child9、ephemeral ports59、Listen残留0；被复用为postgres的历史PID仅辨认、未kill。Windows344B dumpstream保留，不用全局argLine掩盖。本审查没有新建进程、测试、网络探针或执行关停。
11. Reviewer工具噪声：最初并行读取规范/brief时外层输出budget曾截断，随后补读丢失的brief头与skill尾；正式diff读取从未截断。一次把RunAuthorityStore误定位到admin-common导致rg“文件不存在”，立即按实际engine路径窄查；没有写入源/target或据该失败作判断。

## 建议与交接

先修 I1/I2，使用上文对应的实际边界负例验证修复，再由同审查者针对修复 diff 与当前受影响证据复审。应保留全部历史 raw/source/target 与噪声，更新当前覆盖口径，仍不得扩大为普通IAM/真实Gateway/全Task5完成。

本 reviewer 未运行 build/test/probe/fuzz/package/deploy、未执行 git 状态变更、未改 source/test/target/config/ledger/既有证据。唯一写入文件为本报告 `docs/evidence/review-iam-stage-5-n3.md`。

---

# N3 I1/I2 定向修复复审追加

## 当前双审结论

**Spec Compliance：✅ Spec compliant（本 N3 及具名 I1/I2 修复范围）。Task Quality：Approved。Critical 0 / Important 0；Minor 1 条报告计数勘误已在本追加明确处置。** 原 I1/I2 均闭合；本结论取代前文对修复前代码的 Needs fixes 判定，前18890 B历史审查原文保持。

这次只审查修复差异及其直接影响，不重新扩展到全仓，也不签收 N4、真实 GatewayController、普通全局 IAM 或全 Task5。批准依据是实际修复源码、真实行为红/绿和已核物理证据；没有重跑测试来重复确认作者结果。

## I1 闭合：metadata 后的调度前 fence

`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:778–782` 在 metadata SELECT 返回、created/replay 分支确定后，`780` 本地 `requireBound(admissionProof)` 紧接 `781 scheduler.created`。原 actual commit 返回后的检查和调度后的检查均保留，既不锁内联网也不回滚已提交事实；finally 中 Admission.close 不变。

新增 `PublishedAdmissionHttpPostgresTest.java:1155` 的 `expiryAfterCommittedResponseMetadataNeverNotifiesSchedulerAndFreshPollRecovers` 利用已有透明 afterQuery 接缝，在精确提交后 SELECT 返回时断言不在事务，并一次推进验证时钟。该控制注入模拟查询跨期限，不冒称实际自然 PG 等待。原 created override 仍真实调用 super，未空置调度器。

fix-red01 原始 `raw.log:86,100–109` 显示 createdCalls=1、attempts=1，新增断言实际失败；红前 RunService 未改，RuntimeClients 的唯一生产变化是受批准的 owned executor 构造测试接缝。修复后测试在拒绝时断言 createdCalls=0、1 run/2 nodes/15 scopes/PENDING/attempt=0/容量2恢复；之后合法 main 复用同 requestId，poll 可推进原 durable run 到 SUCCEEDED。fix-final01 实际通过包括此新例和原 commit-expiry 例。这里闭合的是失效请求发出的 nudge，合法后台使用 fresh current 的恢复能力仍保留。

## I2 闭合：连接之后的真实请求拦截器

`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RuntimeClients.java:108–120` 使用该固定 published 客户端的 `addRequestInterceptorLast`，缺少私有 retained proof 或 Long deadline 时403拒绝；随后 `proof.requireVerified()` 检查原私有验证时钟下的 current/ticket，deadline到期明确暂不可用。没有在线程间调用 ThreadLocal `AuthorityContext.requireBound`，没有 bind public VA，也没有通用授权/传输框架。

`RuntimeClients.java:178–183` 每次请求创建独立 HttpClientContext，将实际 retained 与一个 System.nanoTime 两秒绝对期限放入；实际 `execute(request, context, handler)` 使用该 context。`235–237` 用相同 deadline 的剩余 NANOSECONDS 等待完整响应，不在排队或连接后重计两秒。`280–282` 将 ExecutionException 的直接 PlatformException cause 原样透传，实际新增用例确认过期为403；其它 IO/协议/timeout仍按原暂不可用分类。caller frame检查（152、169、238、275）、finally取消、立即关闭client/owned executor、有界body、无重试/redirect/auth挑战、identity、fixed origin与实际HTTP状态保持不变。

固定 Apache5.4.4 源顺序已经在前次澄清核过，本次按新增 context/异常风险继续具名窄查：HttpClientBuilder:833–834 使用 castOrCreate；HttpClientContext:166–183 对现有 typed context 保留同一实例；InternalHttpClient:153,180–186 把它放入 ExecChain.Scope，且仅包裹 HttpException，不吞 PlatformException。原 Protocol→Connect→Main 顺序让 ConnectExec:127/144/178/198 完成 lease/connect/TLS 后进入 MainClientExec:118 的 request processor，再到120传输。MainClientExec:182–184 对 RuntimeException discard endpoint 后原样重抛。因此新增检查确实位于等待后的请求处理位置，不是仅 Callable 入口提前检查。

新增 `RuntimeClientsPublishedHttpTest.java:534` 的 `proofExpiringWhileRealExecutorQueuedNeverSendsPublishedGet` 使用真实单线程、有界队列执行器，透明提交真实请求，入队后推进proof时钟再释放。没有替换Apache或HTTP端点；latch有限等待、finally释放、client关闭、executor终止和control join均有实际断言。fix-red01 `raw.log:112–120` 为实际 GET=1、测试耗时0.407秒、期望0失败，尚未到两秒deadline；fix-final01同例为实际GET=0/403并通过。由于修复未增加 task入口proof检查，该真实队列用例也直接验证了 actual request interceptor 的拒绝路径。

没有另做真实 TLS/连接等待探针；此限制保留。固定源码方法顺序与真实排队请求红绿已经支持当前具体位置和期限修复，无需扩大TLS信任或引入额外平台试验。检查到发送之间仍不是原子撤权承诺，不能撤回已发送字节；也未把原I2错误改写为“执行cancel后必然仍迟发”。

## Minor M1：作者报告恢复后计数勘误

`docs/evidence/iam-stage-5-n3-admission-app-client-fix-report.md:17` 写“green raw末尾attempt1”。准确数据为该例的 `iam-stage-5-n3-fix-final01/raw.log:96`：**attempts=2、scopes=52、createdCalls=0、runs=1、nodes=2**。这是之后合法 main/poll 恢复完成后的末态；负例拒绝时的 **attempts=0、scopes=15、PENDING** 来自该测试先前的明确断言。红轮该例的末态才是 attempts=1/scopes=33/createdCalls=1。

此项是文字转录错误，未改变源码、测试结果或I1结论。root已独立核同段raw并接受在本复审/签收记录中勘误；原作者报告16409 B及其SHA、handoff保持不可变，不为计数勘误修改源码、重跑或重写历史。

## 当前覆盖与证据

- fix-red01：实际两具名新例 **2 tests / 2F / 0E / 0skip**，不是setup错误。fix-final01：实际 **PublishedAdmissionHttpPostgresTest20 + RuntimeClientsPublishedHttpTest20 = 单次40 / 0F / 0E / 0skip**。最终raw 414、416、420行与 test-audit/current-covered-tests一致，root另核实际Running/XML/源快照及SHA。76个旧XML只作incidental metadata，不计执行。
- 原N3 admission19/client19分别由当前20/20取代，历史失败照保留。旧 FlowMaterialStatus11/OwnedFlowScheduler10本轮未跑、测试源码SHA不变，作为原覆盖证据保留；不累加成一次61项全绿，也不要求为总数重跑同码无关类。
- fix brief 9826 B / SHA `c33e6732bf9dec8a43234294c4fa3939a52f4de7e950fd4449c352a954e76458` 与 fix report 16409 B / SHA `b61daadcaa106bd6a6eec1dd51f75608039fc0ad229a769a921b5553a3ed6c60` 完整读取；handoff268行、test-audit、current-covered-tests、upstream reading-manifest和tool-noise完整读取。变化包 `.local/iam-n3-fix-root-review-changes.diff` 33292 B / SHA `3b07f9dbb2f75414f9f30e1ad180efad6d221b13277d58292dd2745441a9b3a3` 一次按≤250行完整读到EOF（587内容行/588 splitlines）；红前接缝包3885 B/70行也完整读完。原实现已在初审全包读取，本次未重读4851 splitlines的完整上下文包或整仓。
- root fast audit已读：两份before367source/1795target、baseline367、live/frozen/replay各367、live/target-final各1798、完整inventory；仅原四文件修改，source+0/~4/-0、target+3/~10/-0/JAR0，真实apply-check/apply=0及367文件重放一致。26个handoff descriptor和11个上游artifact物理核验成立；本reviewer不冒称重做root物理全扫。
- root在允许追加前完成 `.local/iam-n3-fix-root-protected-check.json`：101048静态/7634684209 B，加28 runner、8当前索引共 **7635135657 B**，unverified=[]、unauthorizedAdditions=[]、90336旧基底完整SHA覆盖，原设计/原报告/初审及最新八索引不变。本追加发生在该保护核验完成和root明确授权之后。

## 六跨度复审处置

| 跨度 | 本次处置 | 仍保留的边界 |
| --- | --- | --- |
| 全局约束、契约、部署mode | 修复只两个既有生产Java和两个既有测试；POM、app helper、common、routes、schema、配置、runner及契约均无新改动。正常public装配仍验证属性并创建owned虚拟线程executor，小package-private接缝明确转移测试executor所有权。 | 全局 IAM_TRUST_CHAIN_UNAVAILABLE仍关闭；没有以I2的局部context替代正式全局mode统一。 |
| common/domain authority seam | 使用既有私有Retained.requireVerified；caller requireBound、initial/scope/owner/root语义不变。无需新增common API或公有授权record。 | 此次只验证新增使用边界，不重新宣称所有先前common任务经过全量复测。 |
| 原业务链、scheduler/storage/admission/client | I1在实际查询后created前闭合；I2在lease/connect后processor闭合，并保留错误语义、取消/大小/整体期限。新真实边界例和两个完整当前受影响类通过。 | 合法后台fresh current恢复继续允许；不将拒绝请求后的合法恢复误判为越权。 |
| gateway、非纯执行、恢复、Observer/RR及真实环境 | 本次没有扩张入口；原纯FLOW gate、admin限定及component chain继续适用。 | 真GatewayController、普通IAM、N4a模式收口、独立GET status/SSE、CHAT/工具/模型/Session/history/replay、完整恢复、Observer/RR1/RR2、浏览器与真实IAM/本体/K8s/容量仍pending，原原因不变。 |
| 证据、来源、source/target、测试与噪声 | 当前单次40绿、真实2红、完整物理审计和保护结果均具体定位；M1已用raw精确勘误。原初审18890 B前缀不改。 | 不把历史21项写成本轮执行，不把TLS机制审查写成实测，不抹去原helper SHA重建限制或JDK版本差异。 |
| 固定开源比较、注释、许可 | 既有Apache5.4.4官方sources.jar复用，新增窄读ConnectExec/HttpClientContext/InternalHttpClient；固定链顺序、context保留和runtime异常清理支持修复，LICENSE/NOTICE归档保留。新注释解释具体等待窗口与ThreadLocal限制。 | 无依赖/POM升级或新下载；JDK21+35参考与Temurin21.0.12+8运行时区别保留。没有可证明安全删除的通用框架/冗余实现，Ponytail额外可删行数0。 |

## 工具噪声、资源与权限

作者本轮保留Windows rg通配符123错误、初次组合输出截断后具名补读、finalize已完成freeze/apply/replay/test-audit后sources.jar basename误定位ENOENT，随后只续未完成upstream/resources。root本轮保留初读猜protected-before路径ENOENT及猜capture.mjs/protect-after.mjs文件名ENOENT；均在物理hash阶段之前只读失败，纠正后未重跑已核物理阶段。它们与两个实际行为红分开记录。

现有unchecked/中文编码噪声、实际55P03 servlet ERROR、两份Windows344 B dumpstream仍保留，未称输出pristine，未用argLine或改公共错误映射掩盖。资源结论采用已核两own app退出、23已登记engine/app端口无残留；client小fixture由其实际finally/termination断言支持，不冒称每个client端口均做OS逐项审计。

Reviewer全程未运行build/test/probe、未修改source/test/target/依赖/配置/其它证据或共享索引。只在root保护核验完成并明确授权后追加本报告；原前18890 B/SHA `d971492e77ece4178c710185f8aaa0b159a9addb468aa01e3a67a74e46a8ceca` 保持。推荐root在上述范围内接受N3修复并继续后续计划；不据此发布、部署或签收全Task5。