# G2 不可变对账证明与 APPLIED 旧代 CAS 独立评审

**当前状态（06:48 返修复核）：APPROVE，原 P2 已关闭。** 下方原冻结版的问题与红测证据保留；返修依据和独立验证见末尾附录。此结论限本报告范围，不等同 G2/T11 完成。

评审日期：2026-10-20。唯一工程根为 `G:/207/项目/算法重组工具/代码/agent-platform`。本轮按 code-review、requesting-code-review、verification-before-completion 的证据原则评审；评审者未参与业务实现。范围为 `skill-reconciliation-proof-sha256.txt` 的 7 文件及必要的新工程相邻安全、回调、执行上下文；未修改业务源码、契约、设计、服务或部署。

## 结论

**暂不通过：确认 1 项 P2，严格内部票据边界没有落实。** 除下述问题外，在本轮冻结范围内未发现有证据支持的其他 P0/P1/P2。NOT_APPLIED/NOT_SENT 证明、不可变持久化、原代身份和范围约束、APPLIED CAS 增量的实现与 29 项测试基本相符；不能据此宣称整个 G2/T11 或真实引擎/供应商验收完成。

### P2：SERVICE 角色用户票据可以读取限定为内部服务票据的证明

落点：`ReconciliationProofController.java:18–19`、`ReconciliationProofs.java:28–31`；上下文 `admin-common/.../SecurityConfiguration.java:47–52,141–142`。控制器直接调用 `proofs.get`，应用层只检查 Actor 的 SERVICE 角色。公共安全配置既接受 `aud=agent-platform, token_use=user`，也接受 internal/service 组合；`/internal/**` 额外只检查 ROLE_SERVICE。因此，**原 tenant/principal 的合法 portal 用户 JWT 只要含 SERVICE 角色即可读到证明**。不需要伪造签名，不需要跨租户，也不意味着能由证明直接启动重试；缺口是被明确要求的票据用途/受众隔离失效。证明暴露原代身份、原幂等键和核实元数据，不能用原主体匹配来替代此隔离。

这与本增量报告“真实安全过滤链验证 internal audience/service token”及控制器注释所述边界不符。原 `proofHttpRequiresVerifiedInternalTicketAndExactOriginalOwner` 的 userToken 负例仅给 EDITOR 角色，403 实际能由缺少 SERVICE 角色单独产生，不能证明票据用途限制。现有 `SkillCallbackController.java:25–31` 已显式检查经过验签的 Jwt、`token_use=service`、internal audience 和 SERVICE，可作同工程实现对照。

**独立复现：** 保持冻结源码不变，在 `.local/review-proof-token-probe/ReviewProofTokenProbe.java` 复制原测试夹具，更名类、加独立 main，仅将该方法的 actor 从 `Set.of("EDITOR")` 改成 `Set.of("EDITOR", "SERVICE")`。直接 javac/java 使用刚完成测试的 classpath，依旧经过真实 PG、Nimbus 验签及 MockMvc Spring Security 过滤链。有效 service token 的 200 先通过，随后原 userToken 预期 403 的断言实际得到 **200**；日志 `.local/review-proof-token-probe-assert.log`，06:41:47，进程退出 1，明确 `expected: <403> but was: <200>`。探针未修改源码或 target。

首次启动探针遗漏本工程 Windows `-Djdk.net.unixdomain.tmpdir=V:/.local/tmp` 参数，发生 SocketException，记录在 `.local/review-proof-token-probe.log`；这是探针环境错误，不算缺陷复现。补齐与 Maven 相同 JVM 参数后得到上述有效断言红。

建议在新证明读取及生成入口落实经过验签的 service token/internal audience 与 SERVICE 的联合检查，保留原 tenant/principal 限定；补上 SERVICE 角色 userToken 负例，不能通过放宽断言关闭问题。本轮动态确认的是 GET 新证明读取入口；POST reconcile 也调用同一个只查角色的 service helper，应一并核对其入口防线，但不冒称已对 POST 跑过同一 HTTP 探针。此项已即时告知根任务，待作者修复及重新冻结后单独复核。

## 冻结与验证证据

- 7 个当前文件 SHA256 全部与作者清单一致；清单文件自身 SHA256 为 `A3ADBCC33E9632705829144B6C9B6F1A96B9F65F0A44A86521E0E19C37D9BA58`。工程无 HEAD，本报告不将源码清单称作 Git diff。
- **评审者亲跑：** `mvn.cmd -B -ntp -pl skill-registry test -Dtest=ReconciliationProofTest -Dmaven.repo.local=V:/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`，无 `-am`。29 tests、0 failures/errors/skipped，BUILD SUCCESS，06:41:18；日志 `.local/review-skill-reconciliation-proof-29.log`。仅使用专用测试库及测试夹具 HTTP 服务，没有启停部署服务。
- **作者日志审阅：** `.local/skill-reconciliation-proof-applied-full-final.log`，162 tests、0 failures/errors/skipped，06:37:45。日志 SHA256 核得 `89B92160540BB6EEFF7EEF961AF8FEC97A10E820771B41A26BA56147ECE41AE9`。其中 AsyncHttpExecutionTest 21、CallbackHttpTest 32、SignedCallbackTest 3；本轮没有再次亲跑完整 162。
- 实际审阅中间失败断言：基础 3/3 failures；APPLIED latch 红 4 tests/3 failures，随后 4/4 绿；普通 HTTP returnSpec 红为 `expected UNKNOWN but was NOT_APPLIED`；首轮全模块 155/3 errors；命名含 lost-ack-green 的首次修复日志仍 1 failure。最终日志不能抹去这些中间失败。作者记录的初始夹具 errors 不计有效功能红测。
- 作者 AOSP/契约脚本通过属于审阅报告，评审者本轮未重复运行；不将其列为亲跑结果。测试目录已于探针完成后释放给根任务，评审者停止 Maven/target 写入。

## 规范和质量核对

**来源与权限。** 普通 HTTP 查询先去除业务 `httpMapping` 和 `returnSpec`，按原查询协议根状态判定，显式配置且非 null、不同于 doneValue 的 notAppliedValue 才可给出 NOT_APPLIED；查询响应必须匹配原 key，异步另核 job。404/5xx、超时、未配置及错 key/job 保持 UNKNOWN。ACK 丢失只查原 key，建立 job 后按原 job 继续查询，submit 次数断言仍为 1。当前守卫必须覆盖原 guardedArguments，普通及异步路径均在查询/证明物化前执行；合法集合顺序变化由双向覆盖比较处理。

**本地发送证据。** NOT_SENT 限于原代 REGISTERED/send_started=false 的条件更新，或同代已被栅栏阻止的状态。任意 SUBMITTED/false 不足以证明未发送。CAS 限定原主体、run、attempt、version、key 和 snapshot；迟到发送仍受状态 REGISTERED 的发送 CAS 限制，不因产生 proof 自动开放重发。生成前再次检查 engine 原 call 完整身份、UNKNOWN、活动 run/node；`retryBusinessAllowed` 恒 false。

**不可变事实。** ReconciliationProofs 在短事务按 execution_fact→external_job_binding 加锁，重验原 UNKNOWN、非 Mock、attempt/version/run/contract/args/snapshot、绑定及可靠发送状态。正文绑定 tenant/principal/run/node/call/attempt、合同和原始/守卫参数摘要、原 key、来源状态及摘要、PG 序号/时间；正文规范 JSON 可重算 proofSha256。V310 的唯一键限制每 tenant/call/attempt 一份证明，UPDATE/DELETE 触发器阻止原行改写。相同并发观察返回相同 proof，不同来源摘要冲突 409，不覆盖已有证明。

`sourceSha256` 是规范化来源 JSON 元数据摘要；异步结果流被分离后不包含 result 原文，不能解释成供应方原始字节签名。允许原 key lookup→已绑定 job 的合法 URI 切换时，重复证明比较忽略 queryEndpointSha256，保留首次 URI 摘要；key/job/外部状态/来源摘要仍需一致。历史证明读取按原主体与原 attempt 返回历史事实，不代表当前 scope 扩权或新重试许可。

**事务和竞态。** HTTP 与 engine 检查在证明持久化事务之外；真实第二 PG 连接在供应方 HTTP 悬挂期间能够对 fact/binding 取锁的测试通过。并发相同/冲突响应、查询期间原 fact 变终态/attempt 变化均有断言。APPLIED 最终 UPDATE 绑定原 fact 全身份、快照、UNKNOWN，并要求异步原 binding 为 DONE；0 行 409，不再次投递当前已由其他路径形成的终态。适配器 ACK、job lookup、DONE 写入及 generation 复查阻止旧响应更新新 binding。正常同代 APPLIED 仍成功、投递一次。

这些并发用例使用真实 HTTP latch 和第二 PG 操作；“callback 已成功”的竞态测试通过直接写入对应 PG 终态来安排并发点，**不是在该测试中执行完整签名 callback HTTP 请求**。engine authority/fact 接口为测试 HTTP 夹具，不是真实运行引擎逻辑 CAS；旧 callback 过滤链/收件证据的独立回归由作者全模块日志支持。

## 后续范围与不作出的结论

本轮没有引擎新代许可、重置绑定或重新提交业务分支。作者明确的 adapter `expectedAttempt`/引擎授权绑定属于未来启用新代前的前置工作，不能因人工测试制造 replacement generation 就宣称产品已开放新代 reset。本轮 APPLIED CAS 防御的实测范围也不能替代这个后续前置。

证明是核实时刻的历史观察。engine/run 与本地 PG 不在同一个分布式事务；后续引擎必须重新核实活动状态、代次与审批并做自身 CAS，不能只凭 proofRef 放行。APPLIED 在最终 CAS 前可能已生成 artifact，未交付对象沿既有登记/TTL 回收边界处理。本轮未扩展取消三态、NOT_APPLIED 自动重试、终态历史结果再外发授权、生产 IAM、真实供应商协议或容量验收，也不据局部测试关闭 G2/T11。

## P2 返修独立闭环（06:48）

**APPROVE：关闭本报告唯一 P2。** 当前限定范围内无尚未关闭的 P0/P1/P2。新清单为 `skill-reconciliation-proof-ticket-sha256.txt`，清单 SHA256 核得 `41297148FB819FDCD49E7BC34BAE39A6D97326128A6DA1A540DC14CBD9120EB9`，7 文件逐项匹配。相对旧清单只有 ReconciliationProofs、SkillExecution、ReconciliationProofTest、AsyncHttpExecutionTest 四文件变化，原 V310、Controller、HttpAsyncAdapter 摘要不变。原 162 版清单及本报告前文作为历史保留，不改称原版已通过。

代码核对：`ReconciliationProofs.requireProofTicket` 从安全上下文读取 Jwt，同时要求 `token_use=service`、audience 包含 `agent-platform-internal`，再检查确切 SERVICE 角色。get/store 均调用该检查，保留原 tenant/principal 查询限定；`SkillExecution.proofAuthority` 在 NOT_SENT 发送栅栏前、NOT_APPLIED 证明生成前调用该检查，因此拒绝不仅影响返回值，也阻止用户票据形成发送栅栏或持久证明。公共安全过滤链未改；非证明 APPLIED 分支仍按既有策略执行，不将本轮票据修复扩大解释成全平台 IAM 策略验收。

新增断言实际核对：portal+SERVICE 读证明拒绝；双 audience 但 user purpose 仍拒绝；错误 audience/purpose 组合由 Nimbus 过滤链 401；portal+SERVICE 生成 NOT_APPLIED 后 proof=0、fact=UNKNOWN、submit=1；NOT_SENT 被拒后 binding=REGISTERED、proof=0、submit/query=0。合法内部原主体成功、跨主体/租户 404、坏票据 401 继续保留。直接服务夹具明确补全 internal/service Jwt，不把这些直接调用夹具称作真实验签证据。

评审者本次亲跑：

1. `mvn.cmd -B -ntp -pl skill-registry test -Dtest=ReconciliationProofTest,AsyncHttpExecutionTest -Dmaven.repo.local=V:/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`，无 `-am`。Proof34 + Async21 = **55/55，0 failures/errors/skipped**，BUILD SUCCESS，06:48:32。日志 `.local/review-skill-reconciliation-proof-ticket-55.log`。
2. 原独立探针的同一复现方式再次运行：从返修测试复制到 `.local/review-proof-token-probe-fixed/ReviewProofTokenProbeFixed.java`，只强化原 HTTP 测试 actor 为 EDITOR+SERVICE，另加 main；不修改工程源码/target。直接 javac/java 经真实 PG、Nimbus、MockMvc 过滤链，**内部原主体 200、portal+SERVICE 403、跨主体/租户 404、坏票据 401** 全部断言通过，06:48:49，exit=0。日志 `.local/review-proof-token-probe-fixed.log`。旧版同一权限组合为 200 的独立红探针与本次 403 构成闭环。

作者证据审阅：四条新 HTTP 红测日志 `skill-reconciliation-proof-ticket-red.log` 为 4 tests/4 assertion failures/0 errors，均 expected403/actual200，06:43:27；最终 `.local/skill-reconciliation-proof-ticket-full-final.log` 为 **167/167，0 failures/errors/skipped，06:46:41**，文件 SHA256 核得 `83E158E92E9664A7A2BE6211FF677D896C8CFEAB3B45A0CBE9D562CF94DC305E`。评审者未重复完整 167，完整模块回归结果只按作者日志审阅记录。

06:48:49 后已向根任务报告 APPROVE 并释放 skill target；本评审不执行部署、真实 engine 纵切或七模块最后整轮。这些后续结果需由执行者另立证据，不能从本报告推导为已完成。前述 expectedAttempt、新代许可、取消、供应方协议和历史证据边界继续保留。
