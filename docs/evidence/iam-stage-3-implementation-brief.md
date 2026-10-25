# G6 Task3 当前 authority 客户端与响应生命周期交接

Task1/2已独立双Approved，root于2026-10-20核对Task2集中复审后授权按本交接实施Task3。唯一工程G:/207/项目/算法重组工具/代码/agent-platform；遵守AGENTS，禁止访问/关联相邻旧代码/配置/数据库/缓存/进程。用户已授权持续实施及隔离端点，真实IAM缺失不停止内部软件工作、不虚构验收。

本文件摘自已批准计划8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14。任务前置的实际类型/方法/源码SHA以root未来移交的Task1/2冻结报告为准；不要重复已关闭任务、不要凭旧846规划状态改写当前事实。

## root正式移交

Task2三项P2集中复审均Closed，Spec Approved / Quality Approved，见review-iam-stage-2.md最新追加（完整报告SHA 5229f7c7db24d9da08c277b776c9c38ae0cd4dbcb7abda43d1311bd207040085）。当前源码起点为iam-stage-2-review-fixes/source-after.json的34文件、完整common82，不是原77版；修复patch SHA df1aad38462d57a0e9ccbca5cc86f85445861b01372f2950486b79fb98c61abb。root已读复审及audit，复审原11303字节前缀保留；common/target已归还。本次仅移交common与自己的Task3证据，其他业务模块/配置/迁移、主服务与外部文档不在实施者写入范围。下文“待复审/尚未授权”的准备期描述以本节正式移交为准。

## 阶段约束

- 只实施原Task3的common客户端/上下文/响应生命周期和测试fixture；不建生产IAM。app resolver实际HTTP/PG服务、六服务守卫/真实async循环、PG持久grant/材料表仍分别属于Task4/5，不能被common单测提前计绿。
- 原§2.3–2.6完整保留用于协议/生命周期交接，但本轮不因此扩写其中列举的其他业务模块/前端/部署/迁移。集成fixture必须经过独立签名policy assertion和HTTP resolver协议，小型测试resolver可供本Task协议反例，不能以静态ACTIVE代替协议，也不声称真实PG resolver完成。
- 仅fixed validated端点/客户端，凭据来自明确受限resolver；本轮测试使用独立临时测试凭据，不读.env/vault/masterkey/实际秘密。Test RSA issuer/authority在测试目录，每测试实例且有限用户/keys/响应，不对真实服务发请求/启停/打包。
- 当前scope/role/批准者、grant/operation/response/material绑定缺失、撤销、503均默认拒绝；不能从旧Actor自签或借缓存续权。内存thread-local只能存已验本请求上下文，后台用显式已验对象，不能自动跨线程继承授权。普通portal/DELEGATED流期限与INTEGRATION当前响应完成permit必须分用途，不以permit新执行/下载/重放/换票。
- Task3完成后是否开放mode=iam，必须由root核Task4缺失的服务能力/资源守卫时点再裁定；禁止仅common客户端可用便对旧六服务放行。全链未迁移前维持启动闭锁与BLOCKED_IAM_SCOPE_ACCEPTANCE；测试夹具通过不签生产IAM。
- 先保存完整common src/POM及实际修改文件baseline/SHA；新增最小可编译接缝后，原Task3提及的缺实现/编译红不算关键行为红，须真实错误授权/生命周期反例断言失败→绿，包含RR1/RR2。Clock/闩锁和有限小参数，不长sleep/耗尽/慢攻击。
- 交docs/evidence/iam-stage-3.md及完整patch/frozen/SHA/真实apply字节比较；给真实命令/行为红绿/完整common与限制、开源源码实际阅读。冻结后交target独立审查，无HEAD不造空diff/提交，不发布、不派子agent。不要无故重跑七模块；其服务配置迁移和统一回归由root组织。

## 原Task3
### Task 3: 接入 authority 客户端，禁止从 Actor 自签和陈旧授权续票

**Files:**
- Create: 本文 §2.3 的 `DelegationRef.java`、`VerifiedAuthority.java`、`WorkloadAuthority.java`、`ServiceTicket.java`、`CurrentAuthorityClient.java`、`ServiceTicketProvider.java`，均在 `admin-common/src/main/java/cn/agentplatform/common/authority/`。
- Create same package: `HttpCurrentAuthorityClient.java`、`IamServiceTicketProvider.java`、`AuthorityContext.java`、`WorkloadCredentialResolver.java`。
- Create same package: `ResponseBinding.java`、`ResponseLease.java`、`ResponseAuthorityClient.java`、`StreamAuthorityGuard.java`、`ResponseMaterialPermit.java`、`ResponseMaterialAccess.java`、`ResponseMaterial.java`、`OperationBinding.java`，精确接口/生命周期见 §2.4；`StreamAuthorityGuard.beforeWrite()`、`beforeMaterialWrite` 和 `close()` 必须核当次lease/材料能力、停止位与回收watchdog；close幂等。
- Modify: `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java`（生产 issue(Actor) 明确拒绝；integration/dev 隔离）。
- Test: `admin-common/src/test/java/cn/agentplatform/common/authority/CurrentAuthorityClientTest.java`、`ServiceTicketProviderTest.java`、`LoopbackAuthority.java`。

**Interfaces:** 本文 §2.3/2.4 是固定 task 交接；`ResponseAuthorityClient.open` 与 `check` 均返回 ResponseLease，check 只能更新同一 binding 的当前决定/checkedUntil，保留原 hardDeadline/sourceExpiresAt/operation；`CurrentAuthorityClient.forward` 只换同 operation 子票，resume 只在显式新动作检查点请求新 operation。签名 operation 必须由 mapper 附着至 VerifiedAuthority 和 ServiceTicket，不能靠调用者填 source/时间。`AuthorityContext.current()` 只返回已验证请求上下文，无则拒绝；后台显式传 `VerifiedAuthority`，不通过全局 ThreadLocal 跨线程“继承”身份。`WorkloadCredentialResolver.resolve()` 无用户参数，只解析部署配置绑定的本 client secret_ref，返回秘密只用于固定 token/decision endpoint 的认证头；无配置即启动失败。`LoopbackAuthority` 仅测试，维护有限 alice/approver、两 region、六业务 client，另有独立 `iam-policy-resolver` 签名身份；支持 grant revoke/version change/503、签名 response permit 和 DELEGATED operation。集成 current policy 只通过 §2.6 app HTTP 取得，禁止 fixture 静态 ACTIVE 替代。每测试独立实例，最多2个用户，普通JWKS≤8 key、policy独立固定≤2 key，不把 fixture 当生产实现。

- [ ] 测试先让已认证 engine 申请其他 tenant/subject 或 action、缺 grant、旧已撤 grant、错误 resource、篡改响应、过期 decision 都失败。抓取 token endpoint 请求，断言没有 caller 自报 roles/attributes 字段且认证来自该 client 的独立测试凭据。
- [ ] 执行 `mvn.cmd -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=CurrentAuthorityClientTest,ServiceTicketProviderTest" -Dsurefire.failIfNoSpecifiedTests=false`，预期实现缺失而红。
- [ ] 实现固定 endpoint 的交换/恢复/authorize/approver 协议；profile 中 `grant_ref` 和 decision 是自定义适配而非冒用标准 grant type。客户端不允许调用方指定 endpoint/caller。最低执行顺序：

```java
public ServiceTicket issue(VerifiedAuthority authority, ServiceCall call) {
    if (!authority.expiresAt().isAfter(clock.instant()))
        throw new PlatformException(403, "AUTHORITY_EXPIRED", "当前授权已过期");
    if (!authority.delegation().expiresAt().isAfter(clock.instant()))
        throw new PlatformException(403, "AUTHORITY_EXPIRED", "委托已过期");
    // Same operation: forwarding cannot reset its signed deadline.
    return currentAuthority.forward(authority, call);
}
```

`clock` 与 `currentAuthority` 是构造注入的 final 字段；forward 在服务器端重验当前 caller、链、主体、资源、scope 和 operation，既有 PORTAL/INTEGRATION 响应也保持其签名来源期限，不用 resume 偷换后台新动作。显式后台新动作入口才使用 resume 并验证新 DELEGATED operation，见 §2.4.2；两者都不只检查客户端的旧 expiresAt。响应签名和 nonce/绑定验证完成后才返回票据。没有证明的 `Actor` 不提供生产重载；保留旧静态签发仅于受控 dev/test 路径并加模式校验。
- [ ] 用 Clock 测试 receive 与下一次 issue 之间撤 scope/角色、版本变化、grant 到期、批准者撤 APPROVER；网络 503/错误 JSON/签名拒绝都不得返回缓存票据。错误响应体不进入日志。恢复网络后显式重新查询成功才可继续。
- [ ] **R1 红→绿：** 新增 `admin-common/src/test/java/cn/agentplatform/common/authority/ResponseAuthorityTest.java`，先证明原 request 生命周期无法在decision过期后停止async writer；测试使用 MutableClock 和两闩锁，让opening后阻塞writer，推进时钟/撤scope/503，再放行一次chunk。实现 §2.4 response-only open/check、不可变绑定和stop位；分别断言普通流0个后续chunk、INTEGRATION drain仅原response可写、换responseId/新action/check延长hardDeadline均403。命令：`mvn.cmd -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=ResponseAuthorityTest" -Dsurefire.failIfNoSpecifiedTests=false`；先红后绿，不能用该组件测试替代Task4真实循环测试。
- [ ] **R3 authority侧红→绿：** 新增 `CurrentAuthorityClientTest` 的集成profile用例，以独立policy assertion调用固定app resolver URL，缺resolver/错签名域/旧版本/active=false均拒绝；token/decision请求不可依赖自报roles。绑定DTO使用 §2.6 完整字段，response check重核集成策略也经此adapter（已进入drain的当前响应除外）。运行原Task3命令，记录resolver调用次数及决策拒绝原因，真实PG闭环留Task4完成。
- [ ] 重跑同命令，检查 fixture 的业务发送计数始终 0。仅测试 authority 交互，不声称已接生产 IAM。

- [ ] **RR1/RR2 能力行为红→绿：** 在 ResponseAuthorityTest/CurrentAuthorityClientTest 先以真实 fixture 签名证明过期普通 VerifiedAuthority 仍拒绝，而独立完成 permit 仅原 response/owner/resource/ceiling/version/deadline 可读写；误用其换票/新open/其他用途均拒绝，PORTAL无完成permit。覆盖 authority 在原门户JWT过期后签新 DELEGATED operation、forward保持operationId/截止、caller自报来源/延期及撤grant拒绝；缺 operation policy/签名字段默认拒绝。使用Task3已有命令加 ResponseAuthorityTest，最小骨架后的行为失败才算红；不是执行本轮测试。


## 原精确类型与响应、材料、集成协议

### 2.3 当前证据内容及有效性

建议新增 `admin-common/src/main/java/cn/agentplatform/common/authority/` 包；以下内部接口为各任务统一交接，不改 `Actor` record 的四个字段，也不将敏感票据塞进 attributes 或四契约 JSON：

```java
public record SubjectKey(String tenantId, String principalId) {}
public record ResourceBinding(String kind, String id, String versionDigest) {}
public record ServiceCall(String target, String action, ResourceBinding resource) {}
public record DelegationRef(String value, SubjectKey subject, Instant expiresAt) {}
public record VerifiedAuthority(
    Actor actor, String caller, ServiceCall call, DelegationRef delegation,
    String authorizationVersion, Instant checkedAt, Instant expiresAt, String decisionId,
    OperationBinding operation) {}
public record ServiceTicket(String value, Instant expiresAt, OperationBinding operation) {}
public record WorkloadAuthority(String caller, ServiceCall call,
    String decisionId, Instant checkedAt, Instant expiresAt) {}
public interface CurrentAuthorityClient {
    ServiceTicket client(ServiceCall call);
    ServiceTicket exchange(String subjectToken, ServiceCall call);
    ServiceTicket resume(DelegationRef delegation, ServiceCall call);
    ServiceTicket forward(VerifiedAuthority authority, ServiceCall call);
    VerifiedAuthority authorize(String serviceTicket, ServiceCall call);
    WorkloadAuthority authorizeWorkload(String serviceTicket, ServiceCall call);
    VerifiedAuthority approver(DelegationRef delegation, SubjectKey approver,
                               ResourceBinding approval);
}
public interface ServiceTicketProvider {
    ServiceTicket issue(VerifiedAuthority authority, ServiceCall call);
}
```

这些 public 类型各自同名文件，使用 `cn.agentplatform.common.Actor`、`java.time.Instant`；客户端的 caller 仅来自本机 validated config。`VerifiedAuthority` 的可信性由构造入口纪律和接收端重新验证保证，不能以 Java record 本身防伪；业务请求不得反序列化成该类型。测试可以构造它，生产只能由验签+在线查询后的 mapper 构造。`client(ServiceCall)` 使用 client_credentials 取得纯机器票据，`authorizeWorkload` 验当前机器能力；只供已明确列出的 credential/origin/health 等机器动作，不能生成用户 Actor、用户 grant 或访问其他业务动作。两种内部票据额外使用互斥 `authority_mode=delegated|workload`；workload 的 sub 是可信 client 标识，没有用户 tenant/sub/grant_ref，接收端不得补虚拟用户身份。用户委托票据仍要求完整 tenant/sub/grant_ref。

`authorize` 接口只接已验 service ticket 与本地提取的 target/action/resource，响应必须签名且匹配 request nonce、ticket jti、caller、grant_ref、tenant/sub、resource digest 和查询时间；缺项/错类型/超时/版本回退均拒绝。权限版本是 authority 不透明值，客户端不比较字典序；每次在线读取判 active/current，只有完全匹配的决定可使用。`approver` 查询持久批准记录中的真实 approvedBy，response 必须绑定该批准摘要与主体，禁止传入当前发起人的角色冒充。

有效业务范围 = 原委托上限 ∩ issuer/client 声明上限 ∩ 此刻可信 IAM/集成策略范围 ∩ 具体操作守卫。运行历史仅是上限，不能补回缺失的当前属性。scope action 需完全覆盖目标动作；不同服务的授权决策不能交叉使用。属性按语义处理：`region` 等授权集合取交集；`dataLabels` 等数据敏感级别是限制义务，保留历史材料标签与当前约束的并集，不能求交而删掉保密标签；tenant/sub、biz/user 归属值要求相等，不进行集合补全。未知属性默认不映射。

网络不可用不使用上次成功授权、旧角色或本地签票。有效签名但当前 scope/role 收窄时，新动作和历史受保护材料复用均重新判定；历史材料的所需范围不在当前范围内则拒绝整份复用，不能静默删字段当作安全裁剪。唯一原响应完成例外使用 §2.4.1 独立限用途 permit，不放宽本段普通 current 规则。版本、摘要缺失的旧材料不能假设已获授权。新鲜度只能说明核实时刻；已完成外部副作用不能靠后来撤权撤销。取证在短事务外，事务内复核证据未过期、资源版本/取消/lease/attempt 未变；每次新发送和 G2 claim/submitted 检查点都重新获取证据，不对漫长业务持锁查询 IAM。

### 2.4 R1：长响应授权生命周期与异步传递

这里的“新发送”指新业务请求/新模型或技能调用，不把已经获准的一次响应中的每个字节当成新的业务调用。长响应仍需要输出权限生命周期；生产不能只在握手验一次 JWT。所有源类型来自 authority 已签声明 `source_kind=PORTAL|INTEGRATION|DELEGATED`，不能由请求头或 URL 自报。DELEGATED 是 §2.4.2 的新后台动作来源，原 PORTAL 来源另存审计，不混为仍在使用原门户 JWT。模型返回中的 toolCalls 只是已在途响应的数据，后续工具执行是新动作。

**固定内部接口：** 在 common authority 包新增 `ResponseBinding(String responseId,String kind,ResourceBinding resource,String requestSha256,Instant hardDeadline)`、`ResponseLease(ResponseBinding binding,String sourceKind,String continuationRef,String openingDecisionId,Instant checkedUntil,Instant sourceExpiresAt,ResponseMaterialPermit completionPermit)`、`ResponseAuthorityClient.open(VerifiedAuthority,ResponseBinding)` 和 `check(ResponseLease)`。open/check 是外部 IAM adapter profile 的明确扩展，仅生产客户端；测试 authority 有对应有限处理器，不新建生产服务端。check 使用接收服务自己的 client 凭据、authority 签发且绑定本次响应的 continuationRef，响应绑定最初 caller/target/action/resource/jti 与当前 request nonce，返回新的当前决定。continuationRef 仅允许继续同一已受理响应，不能换票 execute、延长 hardDeadline、创建第二个 responseId 或新模型调用；它不是 expired bearer 通行证。lease 不保存可冒充 current 的 openingAuthority；completionPermit 只在已验 INTEGRATION open 时可非空，定义见 §2.4.1。缺此能力的生产长流入口保持关闭。

| 流/受理点 | 活跃期间、到期与撤销 | 关闭与后续 |
| --- | --- | --- |
| 门户观察 SSE：gateway 验门户 token，engine 验内部票据/owner/历史事件 scope，在订阅与提交 writer 前 open | 每轮读取新事件/转发 chunk 前检查租约；到 checkedUntil 必须在线 check；独立最多 1 秒的轻量 watchdog 检查到期并取消阻塞读取，防 idle 流永不重核。decision≤5 秒到期不是自动延长。原 portal JWT 或 grant 到期为硬终点；scope/role 撤销为 403，authority/JWKS 503 为不可继续 | 未 committed 返回 401/403/503；已 committed 最多发送既有 `stream_reset` 控制事件，reason 为 AUTHORITY_EXPIRED/DENIED/UNAVAILABLE，不含业务 payload，然后关闭；不再发送待缓冲业务事件/final。客户端识别这些原因后停止自动重连，显示重新连接/恢复授权。Last-Event-ID 重连是新请求，重新验 JWT、当前授权、owner 和历史事件材料 scope |
| 普通内部模型 stream（PORTAL 来源）：model controller 受理后、第一次真实 provider 外发前 open；engine RuntimeClients 与 gateway 只传递同一响应绑定 | 内部票据≤60秒只用于受理，后续超过该票据寿命仅可凭该响应的 continuationRef 在线续核；原 portal/grant 到期仍终止。每 chunk/final 前核当前租约，idle watchdog 不等待下一 chunk。check 拒绝/503 后禁止输出，并取消本地 reactive subscription/HTTP read，保留真实已发送/计量事实 | 续核不授权下一轮 MAIN、SUMMARY、输出修正 RETRY 或工具发送；这些都须重新取当前业务决定。若 provider 已返回但续核失败，结果可作为不可外送的已发生事实持久化，带 §2.5 scope，不能通过后续 final 补发 |
| 后台新动作的内部模型 stream（DELEGATED 来源） | 仅消费 authority 根据有效持久 grant/current 签出的 §2.4.2 operation；sourceExpiresAt 为签名 operationExpiresAt，与原门户 JWT 到期无关。内部票据只用于受理，响应仍在线续核；operation/grant 到期、撤权或503关闭，不允许 drain | 每个新动作需新当前决定；不能为原 PORTAL 观察流续期，也不能把原 PORTAL 票据改标来源后使用 |
| INTEGRATION 已建立 SSE 与已在途单次响应 | 保留 snapshot:2887：在受理时有效且已完成 open 的当前响应，后续票据/grant到期、凭据吊销/策略撤销或查询503，转为 `DRAIN_CURRENT_RESPONSE`，仅完成该响应原绑定范围内数据，不扩范围/期限。SSE 截止是原 55 秒响应窗口，模型截止为原请求 timeout 与 emitter 610000ms 上限的较小者；这些是当前实现边界，非新增设计 SLA | 完成/原超时即关闭；不自动重连，不因此允许下一业务动作。已经受理但尚未实际 provider 外发的请求仍须发送检查点当前授权，不能借 drain 发首次业务；后续任何新模型/技能/SUMMARY/修正调用或新 SSE 都重核，撤销/503 则拒绝 |

PORTAL SSE/普通内部流及 DELEGATED 流续核失败默认关闭；只有 INTEGRATION 的明确当前响应例外允许 drain，该例外不改变 PG 运行授权状态，也不覆盖新资源/新调用。开始 drain 后，只允许 §2.4.1 的原响应材料读取/发送和在途事实记账；客户端不能用 `source_kind=INTEGRATION` 自报获得例外。集成 SSE 中允许输出的仍是该 run 已授权材料；超出开流 ceiling 的新材料必须拒绝，不能用 drain 扩权。

`ResponseLease` 在原请求线程创建后作为显式 final 参数传给 `StreamingResponseBody`、model 虚拟线程、ModelCalls callback 和 RuntimeClients reader。不能在异步线程重新 Actor.current()、复用 ambient SecurityContext 或从 Actor 重建权限；局部更新使用一个受该响应拥有者约束的 lease holder，两个流不共享。gateway 在每次 buffer 写出前检查本响应状态，防上游已拒绝但本地缓冲继续泄露。取消/超时/final/error 路径都关闭订阅、timer、InputStream、HTTP connection，watchdog 不能发新业务、不能阻塞于 DB 锁；先标记停止再关闭，已排队 chunk 在写出前复查停止位。

“同一lease”指同一服务本次响应内的各异步消费者；跨HTTP只传播authority签名绑定的响应关联信息，每服务按自己的接收动作open本地lease，不能信任另一个服务直接序列化的Java holder。各hop绑定同一来源和资源/请求摘要，无法靠换hop重置sourceExpiresAt或hardDeadline。临时chunk继承已验证请求材料scope，不新增持久trace字段或声称可重放；完整响应持久化时才绑定完整payload摘要，历史SSE事件另按其各自持久manifest验证。

**断流语义：** 断开观察 SSE 只终止该响应，不自动取消 run。关闭模型 subscription 只是本地停止消费，不能宣称供应商已取消或撤销外部效果；run 后续由原事实/UNKNOWN/恢复机制决定，且所有新动作须重新核当前权限。现有显式 cancel 接口和原四契约保持；已发数据无法追回。1 秒 watchdog 是软件建议的检测调度上限，不声称生产撤权 SLA；Clock+闩锁验收不使用长 sleep。

#### 2.4.1 RR1：原响应专用材料读取/发送能力

**与 current 分离的最小类型和入口：** common 增 `ResponseMaterialPermit`、`ResponseMaterialAccess`、`ResponseMaterial(payload,scope,responseId)`，域内 `MaterialScopeStore` 增 `readForResponse(MaterialRef,ResponseMaterialAccess)`；普通 `read(ref,VerifiedAuthority)` 和 `MaterialAuthorization.require(scope,current)` 的到期规则不变。响应专用 read/accept 只返回 ResponseMaterial，不能隐式转换为 assembler 接受的 AuthorizedMaterial。`ResponseMaterialAccess` 仅可由本地仍活跃的 response holder 根据已验 permit 建立，持 responseId/owner/绑定资源/停止位，不提供 Actor、VerifiedAuthority 或 ServiceTicket 转换。`MaterialAuthorization.requireForResponse(scope,access)` 及 `StreamAuthorityGuard.beforeMaterialWrite(material,access)` 专门验证以下约束，不能只在 beforeWrite 跳过 current。permit 本身不能把尚未外发的请求标为在途：模型 holder 必须已有 FrozenRequestConnector 实际首发事实，SSE 必须已合法建立原响应；缺该状态拒绝 drain。

`ResponseMaterialPermit` 是 authority 在有效 INTEGRATION open 决定中签出的独立限用途对象：固定 issuer/RS256/kid、`typ=agent-response-material+jwt`、`token_use=response-completion`、jti、iat/nbf/exp；绑定 tenant/principal、source_kind=INTEGRATION、openingDecisionId/authorizationVersion、operationId、原 responseId/parentResponseId、caller/producer/consumer、原 action、ResourceBinding（run snapshot 或 invocation/attempt/round/outputAttempt 的版本摘要）、requestSha256、完整开流 ceiling 与 ceilingSha256、允许的材料 kind/归属选择器、hardDeadline。exp 等于不超过原 hardDeadline 的独立完成期限，authority 按既有响应窗口/请求超时政策钳定，不接受caller任意延长；这不是把已过期 service ticket 放宽验证。SSE 选择器仅原 run/version 的事件流和初始 cursor 后单调事件序列；模型仅本 invocation/attempt/round/outputAttempt 的最终结果，不能匹配另一个调用。未来事件/最终内容的 payloadSha256 不可预知，必须从后述可信 manifest 取得并逐项核实际内容，不能用 selector 省略版本/摘要。

生产接收端在 open 仍有效时完成签名、独立用途、主体及全部绑定校验，并固定已验 signer 公钥及原 permit 原文于该短生命 response holder；每次 read/send 仍验证 permit 签名与独立 exp、response 活跃性、owner/资源/版本/摘要/ceiling。后续 grant/decision 到期或查询503仅允许此用途继续，不让普通 JWT decoder 接受过期票据；这里保留开流验过的公钥只供此 response permit，不作普通票据/JWKS 缓存 allow。缺 permit/已关闭/重启丢失 holder/错误签名/PORTAL 或 DELEGATED 来源一律不能 drain。完成期限内仍核材料要求：permit ceiling 的 roles/entitlements 必须覆盖 scope 的 required 集合，owner 一致，dataLabels 义务完整保留且接收/出域边界仍满足已绑定限制。permit/manifest/版本/摘要缺项拒绝，不用旧 Actor 补齐。

**本域真实 PG 读取：** `RunController.events` 当前 `:227` 查询下一事件同时取 payload，`:259–264` 直接发送；改为通过 `MaterialScopeStore.nextEventRef(runId,lastSeq,resourceVersion)` 在短一致性读快照中只取 seq/type/version/manifest（不选 payload）。当前正常路径传 fresh current；进入 drain 后仅调用 `readForResponse(ref,access)`，先核真实 PG material_scope 与上述 selector/ceiling，再在同一一致性快照内按已验 id/version 取 payload，验证 payloadSha256/scopeSha256 后返回 ResponseMaterial，写出前再核 access deadline/停止位。不可在握手预读第二事件、将整个 run payload 先装内存再过滤、只验 run owner 或跳过新材料接口。另一 run、错 run snapshot、重写版本、缺 scope 或超 ceiling 都在取 payload 前拒绝；摘要失配在读取后但发送前拒绝。

**跨域 manifest 与模型 final：** 采用现有已建立 model stream 的私有 sidecar，不在 drain 时发新的 manifest HTTP 请求。model 在 `ModelCalls` 完成本 invocation 的事实和 material_scope 同事务写入后，从本域经 readForResponse 取得受限最终材料；`ModelController` 在原 HTTP 流上先发送私有 `material_manifest` 控制帧，再发送原格式 `final`。控制帧仅含 ResponseBinding、MaterialRef、MaterialScopeV1 和 scopeSha256；producer/consumer、invocation/attempt/round/outputAttempt、requestSha256 必须匹配开流 permit，且 final 实际 bytes 与 manifest digest 一致。RuntimeClients 只在固定已验 TLS model endpoint 的同一已受理连接上接收此帧，在本地对应已验 response permit/holder 下验证；它先等待有效 manifest，再经 `MaterialScopeStore.acceptForResponse(manifest,bytes,access)` 检查来源、scope、version/digest 并把结果交 ModelExchange 同事务记账。不转发私有帧到门户/SDK，不改原 final JSON/四契约对象；乱序/缺失/重复冲突帧拒绝 final，有限缓冲沿现有响应字节上限。

两个服务的 permit 均须在有效 open 时由 authority 签出，绑定同一 operation/响应关联、原资源、调用摘要和不递增期限；由客户端拼接 parentResponseId 不成立。模型输入 manifest 仍在首次 provider 外发前走普通当前授权接口；drain 只接受已在途响应的结果 manifest，不创建新输入请求。非流式在途响应采用同一已受理连接的私有 `X-Agent-Response-Material` header，承载上述manifest的base64url canonical JSON，解码上限16KiB（首版软件建议上限，fixture验边界），超限/缺项拒绝释放body，不改原body对象；由内部client消费并在gateway边界剥离。未实现该sidecar的路径保持拒绝，不用任意URL/新HTTP补查替代。普通 `GET /internal/material-scopes/...` 在 drain 时仍不可调用过期 delegated token，其他下载/回放/新响应也不能使用 permit。正常PORTAL/DELEGATED模型final仍经fresh current检查，不能强行走只接受INTEGRATION的响应能力。

该能力只完成原 HTTP response：不能调用 token/exchange/resume/open、创建 responseId、授权 MAIN/SUMMARY/RETRY/skill、下载 artifact 或新建 replay。可保存已经发生的原结果事实，但后续 ContextAssembler/ChatHistory/恢复复用仍走 fresh VerifiedAuthority。SSE 历史事件在本次原 cursor 范围内继续发送并不授予一次新的 Last-Event-ID 重连；关闭后同 permit 重放必拒。

#### 2.4.2 RR2：持久委托跨门户寿命与后台 operation

**root 本轮软件 profile 裁决：** 已可信签发并持久化的 grant 可在其自身有效期、当前 authority 允许下跨越原门户 JWT 寿命。`resume(DelegationRef,ServiceCall)` 由已认证服务 client 请求，authority 查自身 grant 状态、当前 subject/roles/scope/资源，签发新的 `DELEGATED` 操作证据；不要求保存或重放原 JWT。caller 不能请求改 source 或自行填写 expiry。原 `origin_kind=PORTAL`、初始认证/decision 标识保留审计，原 JWT exp 可以作为审计时间，不充当后台动作的凭证或期限。

common 增 `OperationBinding(operationId,sourceKind,originKind,issuedAt,expiresAt,grantRef,subject,resource,ceilingSha256)`，由已验 authority 声明 mapper 构造，作为 VerifiedAuthority/ServiceTicket 的关联上下文显式传递；签名 service ticket 和在线 decision 同时绑定 operationId、source_kind=DELEGATED、origin_kind=PORTAL、operation_expires_at、caller/target/action/resource/material ceiling/version，nonce/请求摘要按原要求校验。authority 按部署签收的 action lifetime 上限和 grant 到期较早者定 operationExpiresAt；无该 policy/必需签名字段默认拒绝，不能拿本机 now+60 秒补值。service ticket exp 仍≤60秒且≤operationExpiresAt；sourceExpiresAt 取签名 operationExpiresAt，response hardDeadline≤operationExpiresAt 及本地原请求超时。每≤5秒续核 current；grant 被撤/权限不足/503/operation 到期即停止 DELEGATED 输出，没有 INTEGRATION drain。

新增 `CurrentAuthorityClient.forward(VerifiedAuthority,ServiceCall)`：在同一后台动作的 engine→model 等下一 hop 重新核 current 并换取受众/action 精确子票，保持 operationId/operationExpiresAt，子资源和 material 只能收窄；禁止重置同一动作截止。`ServiceTicketProvider.issue` 对已有 operation 调 forward，对尚无 operation 的后台入口不得隐式造新动作。**只有明确的新动作检查点**经 `RunAuthority.current(runId,call)` 请求 resume，authority 可签一个新 operation；它不是旧动作自动续期，新的 MAIN/SUMMARY/纠正 RETRY/技能均重新核 grant/current/material/业务守卫，operationId 与调用身份绑定。来源由 authority 自己判定，伪造 DELEGATED 请求头、过期原 PORTAL 票据或旧 Actor 均不能创建 operation。

真实恢复链为 `RunService.driveRecovered → RunAuthority.current → ModelExchange → RuntimeClients.chatStream → ModelController/ModelCalls`。每个动作显式传新 operation/current；历史材料在组装前和实际发送前重新核。ModelExchange 已持久成功 response 仍按原事实恢复，不因授权更新重新外发。原浏览器 PORTAL SSE 继续在原 JWT 到期关闭，即使后台 DELEGATED 操作有效；不把后台 holder/permit 或其期限替换到旧 observer，重新观察必须新门户认证。此 profile 是外部 IAM 适配合同而非 RFC8693 自带保证，fixture 只验证软件；真实签收前仍 blocked。

### 2.5 R2：可信材料 scope 的来源、持久化与真实消费点

首版采用 root 允许的保守规则：**每份材料 required scope 至少等于其生成运行的完整可信初始 ceiling，再并入全部父材料要求。** 这可能比材料实际用到的权限更宽，导致权限收窄后整体拒绝，但不会把“当前少量字段”误判成可复用全部历史数据。ceiling 必须来自首次 verified authority 的签名决定/委托上限，经本地映射后与 run/turn 受理同事务写入；不能从旧 guard_ctx、模型输出、请求体或客户端声明反推。`initial_ceiling` 单独存在不够，还要有实际 payload/version 的绑定记录。

**模型与规则：** common 新增 `MaterialScopeV1`、`MaterialRef`、`AuthorizedMaterial` 和 `MaterialAuthorization.require(MaterialScopeV1,VerifiedAuthority)`；scope 作为私有 side table/服务间 sidecar，不写入原四契约对象。

```java
public record MaterialRef(String producer, String kind, String id,
                          String version, String payloadSha256) {}
public record MaterialScopeV1(
    int schemaVersion, MaterialRef material, SubjectKey owner,
    String sourceDecisionId, String sourceAuthorizationVersion,
    String initialCeilingSha256, Set<String> requiredRoles,
    Map<String,Set<String>> requiredEntitlements, Set<String> requiredDataLabels,
    List<String> parentScopeSha256, String scopeSha256) {}
public record AuthorizedMaterial(Object payload, MaterialScopeV1 scope) {}
```

schemaVersion 固定 1；payloadSha256 对实际持久/发送内容的 canonical JSON 或 artifact 原始 bytes 求摘要，version 绑定 run snapshot digest、attempt/round/outputAttempt、event seq 或 artifact READY 内容版本；scopeSha256 对除自身外全部字段 canonical JSON 求摘要。PG 是受信生产者写入源，hash 只检查绑定和漂移，不能证明用户自报 metadata 可信。sourceDecisionId/authorizationVersion 取已验 authority response；parentScopeSha256 必须来自受信生产者读取且校验过的记录。验证当前证据到期及 owner 后，以 `current.roles ⊇ requiredRoles`、每项 `current.entitlements[key] ⊇ requiredEntitlements[key]` 判许可覆盖；不允许旧 current/旧 guard 替换。derived 要求对所有父材料求 **并集**，不能求交集或用更窄新运行覆盖旧要求。整个历史 ceiling 在每个新 material 上保留，因此缺语义映射的属性不能猜成许可。

`dataLabels` 是限制义务：derived labels = 历史材料 labels ∪ 本次可信规则 labels ∪ 配置/数据源附加 labels，永久不能因为当前 token 没此 label 就删掉。ModelCalls 出域判定对完整 assembled labels 检查 provider trustLabels 覆盖；“用户当前 dataLabels 包含所有旧标签”不是授权许可判定。tenant/principal、biz/user 归属要求相等；roles/region 等仅映射明确的许可字段；未知/缺失 schema、source、摘要、父链、必需 scope 字段默认 403 `MATERIAL_SCOPE_MISSING_OR_CHANGED`，不能自动回填旧行。

**同事务与跨服务：** engine/skill/model 各自 domain 建 `material_scope` side table，唯一键 `(tenant_id,producer,kind,id,version)`，保存以上 JSON 和 scopeSha256；不建立跨 schema 外键。与本域可见业务事实/READY 写入同事务，缺 scope 即禁止公开 payload；blob 上传保持现有先 UPLOADING 后上传流程，但 UPLOADING 登记与暂存 scope 同事务，最终内容摘要/完整 scope 和 READY 同事务，异常保留清理态，不因上传成功提前可下载。各域实际 schema 的下一迁移号连续分配，纳入 G6 E 候选迁移；model 额外迁移按当时最大版本核定，不能沿用 engine 号。

普通跨服务取材料仍走已登记 HTTPS service endpoint，固定服务 TLS 身份与当前 delegated ticket/资源绑定构成来源信任；响应头 `X-Agent-Material-Ref` 指向生产者 `GET /internal/material-scopes/{kind}/{id}` 的特定 version（query `version`），`X-Agent-Material-Sha256` 绑定 scope。该内部只读 endpoint 需要 `material.scope.read`、当前 owner/资源授权，返回 manifest 不含业务 payload，禁止 URL 从响应任意指定 host。接收方核 producer、id/version、payload digest 和scope，再与本域接收事实同事务复制。请求头、artifact reference、外部工具结果自带 scope 不能作为来源。HTTP 失败/manifest缺失不得先消费后补。模型出域请求的 material refs/manifest digest 由受信 engine 生成并绑定完整请求 digest；model 在调用 provider 前从 engine 已验接口核 scope，再把标签并入本次出域检查；不是相信业务 body 的 requiredLabels。仅 §2.4.1 原响应 drain 改用独立 readForResponse/acceptForResponse 和同一受理连接上的可信 manifest；绝不把过期 openingAuthority 传给这里的普通接口。

| 实际写入/消费点 | 必须落地的 scope 行为 |
| --- | --- |
| `ChatSessions.create/turn`、`RunService.createReservedSessionTurn` | session metadata、turn inputs 与初始 scope 同事务；known requestId 只保留幂等身份，不豁免重新授权。返回现有 run status、重建缺失 run、实际领取执行前均取当前证据并核 turn/父材料；缺 scope 的旧 reservation 不自动生成授权 |
| `ChatHistory.checkScope/load`、`AgentDriver` 每轮 assembler 前 | 修改为 `checkScope(...,VerifiedAuthority)` 和 `load(TraceStore,Map<String,Object>,VerifiedAuthority)` 返回 `List<AuthorizedMaterial>`；查询旧 inputs/output/error 对应 manifest，使用刚取得的 current，删除“历史 guard vs 本轮 guard”的授权用途。先只读元数据并判权再取 payload，payload/version 同一读取快照；在真正模型发送前再次核证据及 digest，覆盖创建与加载间收窄 |
| `ContextAssembler`、`ModelExchange`、`ContextProjection` | assemble 接受 AuthorizedMaterial 列表；MAIN/摘要 SUMMARY/输出纠正 RETRY/被压缩材料，都以来源 scope 并集计算 manifest。摘要文本不清除 region 或 labels；SUMMARY 的模型调用本身发送前就核全部被摘要材料。`model_invocation` 请求/响应和摘要结果与 manifest 同事务，随后主调用再核当前授权 |
| `AgentDriver` checkpoint、`TraceStore.response/commit/event`、`ModelExchange` | response、每 round checkpoint、node/run output/error、event payload 和 manifest 同事务；当前代码分开的 response/checkpoint update 须纳入明确 atomic 单元，不能在两写之间把无 scope 材料公开。恢复旧 checkpoint 先核 material scope/current 再复用，不重复生成模型响应 |
| `SkillExecution` 事实/`ArtifactStore` READY、摘要/`ModelCalls` 持久结果 | skill/model 结果继承请求完整 ceiling 和所有输入 manifest；artifact summary 继承原 artifact 全 scope；下载打开 MinIO stream 前核当前 scope，READY/sha/schema 不符拒绝。对于在途集成响应 drain 产生的事实，仅持久相同原 scope，不扩大委托 |
| `ReplayService.log/recordMaterial/reproduce/reexecute/get`、`ControlledReplay`、`RunService.status`、SSE 历史事件 | 日志回放也先核各 snapshot/input/output/error/event/attempt/model/tool 材料；生成 replay digest 前不能把未授权 payload 读进响应。新 reproduction/reexecute 必须重新核当前 evidence，再继承来源要求；GET status/失败错误和 event replay 不能成为旧材料侧门 |

物理 PG 内部必要的系统发现不等于向用户/模型释放材料。元数据与 payload 必须通过同版本 snapshot 读取，最终输出前校验内容摘要一致；若 scope read 与当前核实之间发生版本变化，重新核实而不是写新 scope 覆盖旧记录。相同 scope 的新合法多轮/摘要/恢复必须成功；缺 metadata 的旧数据默认拒绝是迁移边界，不是“所有新历史都永不可用”。

### 2.6 R3：独立 app policy resolver 的固定接口与无环信任

生产实现责任明确：**app-management 实现只读 policy resolver；本工程 authority 仅测试 fixture，实际生产 authority 消费这个接口。** 不复用返回 secretRef 的 gateway credential 查询，不让 authority 冒充 gateway，不通过通用 `ServiceAuthorization.requireWorkload → authorizeWorkload` 来验证 resolver 请求。

- 固定 `POST /internal/iam/integration-policy/resolve`；caller=`iam-policy-resolver`，target=`app-policy-resolver`，action=`integration.policy.resolve`。gateway、engine、普通 authority 内部业务票据都无此能力。route coverage 单列 `POLICY_MACHINE` 验证域，不能由 proxy 暴露。
- 独立 `@Order(1)` SecurityFilterChain 仅匹配该精确 method/path，RS256 签名请求 assertion，`typ=agent-policy-request+jwt`、`token_use=policy-query`、固定 issuer、sub/caller、aud、action、iat/nbf/exp/jti、bodySha256/nonce。app 从部署配置读取这一**独立 policy 签名域**最多两把已固定 kid/RSA 公钥；它不访问 authority JWKS/token/decisions，也不信任普通 service key。专用私钥仅在真实 IAM policy-client 侧，本工程不创建生产私钥；fixture 在测试内生成。未知 kid/跨域算法/过期/请求摘要不符拒绝，缺公钥配置拒绝启动该生产入口。30 秒 assertion TTL、skew 0 是建议值，只授权精确幂等只读查询；重复相同 jti 不产生业务副作用，不据此开放其他资源。
- resolver 直读 app PG 的一次一致 snapshot，不能调用外部 authority，也不需用户票据或 SecretVault。authority 认证 resolver 的固定 HTTPS 主机/证书并验证 nonce、返回绑定和版本，错误TLS/503/404/字段缺失拒绝授权；结果仅供该次决定，不缓存 ACTIVE。最终用户/service 决定仍由 authority 签名。

最小版本化 DTO（内部适配约定，不改现有外部 ApiKey/SDK）：

```java
public record PolicyResolveRequest(int schemaVersion, long credentialId,
    String expectedTenantId, String expectedAppCode, String expectedBizSystemCode,
    String userId, String expectedPrincipalId, String expectedPolicyVersion,
    String nonce) {}
public record PolicyResolveResult(int schemaVersion, long credentialId,
    String tenantId, String appCode, String bizSystemCode, String userId,
    String principalId, boolean active, boolean subjectAllowed,
    Set<String> roleCeiling, Map<String,Set<String>> attributeCeiling,
    Set<String> dataLabels, Set<String> allowedOrigins,
    String policyVersion, String policyDigest, String nonce) {}
```

schemaVersion=1；initial exchange 的 expectedPolicyVersion 可空，resume/authorize 必须取 authority 已存 grant 的版本，不能由 gateway body 改写。其余 expected 字段必需，来自 gateway 已验集成证明或 authority 已存 grant，app 查询必须按 credentialId+tenant+app+biz 限定。principalId 由 app 按当前 `IntegrationPolicy` 的 biz/user 映射规则重新计算，userId 必须在当前 principalIds 上限内；不匹配返回 404/403，不能回传另一租户事实。roleCeiling/attributeCeiling 只含当前该主体允许值，剔除平台内部角色；dataLabels 独立义务，不能与许可属性混放。**没有 secretRef、API key、密文、grant_ref、其他 principalIds 全名单或用户 payload 字段。** resolver 通过 DTO 明确返回允许 Origin 供 authority 检查已绑定来源，不接收调用方自报 allowed=true。

app 新增候选 `V104__integration_policy_revision.sql`（当前读取最大 V103，实施前重新按顺序定号），为 integration_credential 增加 `policy_revision bigint not null default 1`。issue/reissue/key replacement/policy/revoke 的所有实际变更在原事务内递增 revision；policyVersion=`<credentialId>:<revision>`。policyDigest 与现有 IntegrationPolicy.digest 的 canonical 语义兼容，内部计算可包含 secretRef 绑定但绝不返回其原值；修复过程中不更改旧出票对外格式。旧 grant 在任一 revision 改变时一律拒绝 exchange/resume/authorize，必须重新显式认证和建新 grant；不能因新政策“看似包含旧值”自动延长旧引用。REVOKED 返回 active=false，subject 移出上限返回 subjectAllowed=false，authority 必须拒绝。预期旧版本不匹配可返回409并由 authority 转授权拒绝，不能当可重试成功。

真实有限链必须是 `gateway → LoopbackAuthority token/decisions → app 真实 policy controller → app 专用 PG → authority → downstream 真实 SecurityFilterChain`。fixture 的 policy 值只能来自这条 HTTP，不允许在内存伪造 ACTIVE 来替代 PG；fixture 只持有限 grant/签名状态与独立 policy-client 测试密钥。计数断言一次 grant 决定最多一次 resolver 查询、resolver→authority 调用数始终0，验证没有递归环。独立 resolver 不扩大 gateway 原 secretRef 查询能力，该原查询仍仅供已认证 gateway 的验密用途。

### 2.7 配套：管理端消费宿主门户 JWT，避免生产关闭 dev-auth 后无入口

实际读取 `web/shared/api.ts:75` 的 login、`web/admin/src/main.ts`、`web/admin/src/App.vue:220`/`:631`：目前只有 `/auth/dev-token`，token 存 Pinia 内存；没有可复用的管理端 tokenProvider。`web/assistant.js/src/main.ts:9` 已有 `ticket:()=>Promise<string>`，其用途是集成短票据，保留原 API 和用途，不能挪作门户令牌。

首版只增加同源受信宿主启动适配 `web/shared/portal-auth.ts`：`PortalTokenProvider { getAccessToken():Promise<string>; onSessionChanged(listener:()=>void):()=>void }`，由 `web/admin/src/main.ts` 在 mount 前从宿主显式传入。可用 `window.agentPlatformPortal` 作为同源宿主约定，但不接受 URL query/hash token、localStorage、任意 postMessage 或外部来源脚本。生产 `portal` 模式没有 provider 时显示“请从门户打开工作台”，不回退 dev-token；显式开发模式仍保留原开发登录。采用已有 Pinia/createApi 的内存 token 和迟到401隔离，不另造账号密码、OAuth服务端或刷新token存储。

**新增对外配套接口，root已允许纳入计划依赖、仍待独立复核：** `GET /auth/session`（新接口，不改原四契约/既有签名）仅 gateway 接受门户 JWT，通过固定 `portal.session.read` 的 gateway 当前交换+authorize 投影返回 `ApiResponse<{tenantId,principalId,roles,expiresAt}>`。字段白名单仅这四项；tenantId/principalId从已验portal JWT与authority当前证据的一致绑定取得，禁止body/query/header指定租户，roles取issuer ceiling与当前允许角色交集，expiresAt取portal JWT与grant较早到期值。不要回传attributes全量、原始claims、用户 JWT/grant_ref/凭据或secretRef。它不是 permitAll；integration/workload票据拒绝401/403。签名/用途/到期无效401，已验身份当前权限拒绝403，authority不可用503；生产profile缺当前session投影时返回会话不可用，不取未验证解码角色做可信登录。UI角色仅控制可见性，最终所有动作仍由后端授权；该接口不可据session响应缓存后端许可。

进入工作台：provider 获 token→调用 session 接口成功→设置 token/user/roles→load。平台不保存refresh token，刷新归宿主：onSessionChanged触发时先递增会话generation、清空旧内存身份、abort旧请求/观察，再调用provider取新token并重新GET session，成功后替换；旧generation任何迟到结果都不能写回。到expiresAt用本页timer清空并显示重新连接，卸载/退出时取消timer和provider订阅并清token/roles/user；页面刷新也从provider重新获取，无storage恢复。401/到期清空；403显示无操作权限但不触发开发登录；503显示可重试会话不可用并停止受影响自动操作，不冒用历史权限。手动重新连接调用provider获取新token、GET session；不能自动重发之前POST/发布/创建run。旧请求迟到401不能清除新会话。门户SSE授权reset使用既有observer停止态，断流不发送cancel run。

这部分依赖 D 的 gateway 生产输入、C 当前权限和 F 有限浏览器链；单独 decoder 通过不能称“生产管理端可用”。本次只读使用 Vue skill 核现有入口，继续 Vue3.5，不采用技能目录中的3.6 beta能力。


## 原配置与有限测试边界

## 4. 配置、错误语义与测试数值来源

新增内部属性归 `agent.security.mode`、`agent.security.service-id`、`agent.security.portal`、`agent.security.authority`、`agent.security.integration`，通过 Java `@ConfigurationProperties` 强校验。建议模式 `dev-hmac`、`iam`；生产构建/部署必须显式 `iam`，缺值拒绝启动。`dev-hmac` 必须同时显式 `agent.dev-auth=true`、loopback 绑定和发布门禁仍 blocked；test 单独显式 profile，不从 Host header 推断部署安全。

```yaml
agent:
  security:
    mode: iam
    service-id: engine
    portal:
      enabled: false
    authority:
      issuer: https://iam.example.invalid/agent
      jwks-uri: https://iam.example.invalid/agent/jwks
      token-uri: https://iam.example.invalid/agent/token
      decision-uri: https://iam.example.invalid/agent/decisions
      client-id: engine
      client-secret-ref: env:AGENT_SECRET_IAM_ENGINE_CLIENT
      allowed-algorithm: RS256
      audience: urn:agent-platform:service:engine
      max-ticket-seconds: 60
      max-decision-seconds: 5
      connect-timeout-ms: 1000
      read-timeout-ms: 2000
```

以上域名只用于展示配置形状，不作为可运行默认值；这些 URI 和 secret_ref 都必须由部署配置注入。生产只允许显式 HTTPS trust origins，禁止 token 的 jku/x5u/iss 派生网络目标、禁止重定向；test loopback HTTP 例外只在 test profile。gateway 独立配置 portal issuer/aud/client/role/attribute allowlist 和 integration 专用密钥引用，其他服务禁止 portal/integration enabled。实际读过 `admin-common/src/main/java/cn/agentplatform/common/SecretVault.java` 源码：现有 env/vault resolver 按用户 tenant 绑定，不能用伪造 Actor 解决 IAM client 的启动凭据循环；新增仅接受配置中的本服务 workload secret_ref 的 resolver，固定 `service-id → client-id → env 名` 绑定，不能解析请求给的引用，不改现有业务 SecretVault 语义。本次没有读取任何 vault 数据或环境变量值。

| 数值 | 来源和用途 |
| --- | --- |
| 集成 ≤5 min，即 300 秒 | 设计 snapshot:279、:2885，现有 ServiceTokens 已限制 1..300 |
| 旧内部 120 秒、dev 用户 3600 秒；恢复 3000 ms、16 条、失败 30 秒；outbox 8 条、2 秒 | 当前实现值，描述现状，不能升级成设计硬约束 |
| 内部票据 ≤60 秒、当前证据 ≤5 秒、时间 skew 0 秒 | 本计划建议且已由root接受为首版保守软件默认；不是设计原值或生产SLA。严格 fail-closed，测试注入 Clock，不 sleep 等过期 |
| 连接 1 秒、读取 2 秒、JWT ≤16 KiB、JWKS/决定响应 ≤64 KiB、小 JWKS ≤8 把公钥 | 本计划保守建议，只用小有效/无效样本验边界，不进行耗尽/慢速攻击测试 |
| 初版授权决定缓存 0、初版验签公钥每次从已配置端点获取 | 简化撤权/故障语义的建议；明确拒绝陈旧网络结果。后续缓存优化需单独审查轮换/撤销窗口，不偷偷沿用库默认缓存 |
| resolver独立固定公钥≤2、policy assertion≤30秒、流watchdog最多1秒一次 | 本次返修建议值；resolver为避免递归使用独立部署公钥域，不适用普通JWKS每次在线获取。watchdog与证据≤5秒构成软件检查窗口，不声称生产撤权SLA；以Clock/闩锁验收 |

入口无效签名/issuer/aud/purpose/exp/kid 为 401 `SYS-AUTH-401`；已验主体但 caller/action/resource/guard 被拒为 403 `SYS-AUTH-403`；IAM 网络/不可验证响应为 503 `IAM_AUTHORITY_UNAVAILABLE`，controller/adapter 未执行；无持久授权引用为 403 `AUTHORITY_CONTEXT_MISSING`。沿用 `ApiResponse`/`PlatformException`，不把 401/403 包装成普通 UPSTREAM_ERROR 后盲目重试。日志只记录 correlation/decisionId 的非秘密摘要、action 和错误类别，不输出票据、grant_ref 明文、Authorization 或 secret。



## root 后续复核关注

实施/审查时还需读 iam-ceiling-propagation-review-note.md。它把原§2.3有效上限要求具体化为初次换票/转发/恢复的关注例，不是现有源码漏洞结论或新增协议。不得丢失Task2已经裁剪的原始上限；实际等价验证及持久scope衔接在报告交证据。该关注不授权提前实施Task3或修改冻结契约。

## 已核实的 Task3 传输支持接缝

root只读核实Task2 BoundedIamHttp：已固定GET JWKS和POST token/decision精确端点、64KiB接收上限、正文deadline/取消/无重定向，但原postForm/postJson暂不支持客户端认证头。Task3按原WorkloadCredentialResolver要求，需要在同一类窄增强认证POST（保留既有公开方法和边界），允许把固定本服务凭据用于固定token/decision端点；不能另建一套绕过已审传输的HTTP客户端。认证材料不来自请求给的caller/headers/secret_ref，不送JWKS或其他URI，不打印；测试只用临时独立凭据并验证实际接收头/无自报roles属性。具体方法/构造形状以最小实现为准，不在此指定新的外部协议字段。

其余原定接口若需已有common支持类的最窄增强，应先向root列具体依赖和改动范围；不要仅因Task3文件表未穷举辅助接缝而自行扩建框架或放松HTTP/验签/生产闭锁。Task2仍处独立返修，本段不授权提前写common；实际Task3起步以复审通过后的源码接口为准。

## Task2 当前接口待复审交接

Task2最新候选为iam-stage-2-review-fixes.md及同目录source-after.json/full.patch/manifest；完整common82，34源码，三P2待同一独立reviewer复审，尚不授权Task3。复审通过后以这版起步，不取原77版。公共输入接口实际为TrustedJwtDecoder(AuthorityProperties, Clock, boolean testProfile)，实现JwtDecoder与AutoCloseable；PortalClaimMapper(AuthorityProperties.Portal)的map(Jwt)返回Mapped(Actor actor, Set<String> scopes)。BoundedIamHttp(AuthorityProperties, boolean testProfile)实现AutoCloseable及get/postForm/postJson，认证POST接缝权限见前节。Task2只对ServiceTokens补用途注释，生产签发/旧Actor自签拒绝由Task3落实；不能误认已有实现。
