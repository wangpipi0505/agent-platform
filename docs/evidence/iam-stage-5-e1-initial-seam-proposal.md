# E1 完整初始上限与真实 run 受理持久接缝提案

状态：只读设计提案，待 root 裁决；没有实施或验收 E1，更不关闭完整 E / Task5。日期：2026-10-20。

本次仅读取唯一工程根内的指定说明、D2 冻结 common 和与问题直接有关的 engine 源码/迁移文件名；未读取 D3a0 正在修改的 common/app/gateway live 源码，未运行测试、Maven、网络、PG 或服务进程。唯一写入是本文件。未改四契约、执行索引、旧报告、迁移、source/target 或业务源码。

## 1. 事实与定位

下文 `C/` 特指 `docs/evidence/iam-stage-4-d2-fixes/frozen/admin-common/src/main/java/cn/agentplatform/common/authority/`，不是正在变动的 admin-common live 树。`E/` 特指 `engine-runtime/src/main/java/cn/agentplatform/engine/`。行号来自本次读取；实际开工须对 D3a0 冻结结果重新定位。

| 已核事实 | 实际文件与行号 |
| --- | --- |
| VerifiedAuthority 只有九个分量，没有完整 initial_ceiling。DelegationRef 也仅有 value/subject/expiresAt。 | `C/VerifiedAuthority.java:6`；`C/DelegationRef.java:6` |
| OperationBinding 是 operationId/sourceKind/originKind/issuedAt/expiresAt/grantRef/subject/resource/ceilingSha256；**没有 originAuthenticationId**。 | `C/OperationBinding.java:6`；`C/AuthorityProtocol.java:54-63` |
| authorize 先 decode 收到的 service JWT，再只做一次 authorize query；核 ticket_jti、caller、grant、authorizationVersion、actor 子集及 operation 全等。 | `C/HttpCurrentAuthorityClient.java:63-76` |
| authority(current decision) 校验完整 initial_ceiling 与 operation hash、当前 actor 范围，却只返回 VA；verifyCeiling 局部 Actor 被丢弃。 | `C/HttpCurrentAuthorityClient.java:119-135` |
| query 的 signed current decision 验签、nonce、规范 request_sha256、client_id、call、iat、active/current 已经闭合。不是仅取两个未验 JSON 比字符串。 | `C/HttpCurrentAuthorityClient.java:137-149`；`C/AuthorityResponseVerifier.java:31-52` |
| service JWT 经固定信任域 decoder 验签与 service scope；原始 JSON 的类型/重复字段检查不能被新接缝绕开。 | `C/TrustedJwtDecoder.java:34-69` |
| ServiceAuthorization 当前 `require` 返回 VA；preHandle 把 VA 与刷新 supplier 放入请求 frame。workload 分支不绑定用户 frame。 | `C/ServiceAuthorization.java:55-65,72-92` |
| requireFor 在复核前 invalidated=true，任一失败后不能再读取旧 current；成功才替换。close 支持非 LIFO，跳过已关闭祖先。bind(VA) 没有刷新能力。 | `C/AuthorityContext.java:17-51` |
| 当前 RunService 还没有主 root/current 接入；普通 create 在 240-261 行决定并读取 app snapshot，269 行后准备/冻结依赖。 | `E/application/RunService.java:161-269,282-283` |
| 实际创建事务是 persist supplier 内的 store.atomic，包含 requestId 锁/重查、run INSERT、node INSERT；提交后才调度。 | `E/application/RunService.java:319-467`；run INSERT `380-418`；nodes `446-465`；`scheduler.created` 为 `480` |
| TraceStore.atomic 使用同一个 TransactionTemplate；这是新 authority/scope 行应加入的事务，不是另起事务的理由。 | `E/infra/TraceStore.java:18-28` |
| scheduler.created 可以领取别的已排队 run；异步闭包只携带 dispatch/runId，不捕获当前请求 Actor/VA。 | `E/application/RunScheduler.java:235-274,302-328`，尤其 `313-314` |
| driveRecovered 从 guard_ctx 重建 Actor，随后 drive；drive 的直接入口也只传 Actor。 | `E/application/RunService.java:1539-1561,883-885,950-959` |
| 当前 RuntimeClients get/post 仍是 ServiceTokens.issue(actor)，不能声称真实显式授权出站已完成。 | `E/infra/RuntimeClients.java:38-55` |
| 调度领取是另一个短事务，会将 PENDING 改 RUNNING 并发 RUN_STARTED；现候选条件没有 run_authority 阻断项。 | `E/infra/RunScheduleRepository.java:28-80` |
| ChatSessions 在创建会话时先读取 published，turn 另有 reservation 事务，之后才 createReservedSessionTurn；不能把 run 的一个事务误称覆盖整个 session/turn。 | `E/application/ChatSessions.java:26-40,76-86,105-176,189` |

冻结 `Actor` 构造器对 roles/attributes 做深层集合拷贝，可作为私构造保留产物中的不可变值；它的类型本身不证明来源。`MaterialScopeV1` 只有 initialCeilingSha256 与完整 requiredRoles/requiredEntitlements/requiredDataLabels，没有完整 ceiling 字段。完整值应保存于 authority row；scope 必须实际保留材料要求，不能只有一个 hash。

## 2. 建议裁决：一个保留映射类型，default 兼容入口，HTTP 真正 override

**建议选择**：保留 VA 九字段、OperationBinding 公共构造器和 CurrentAuthorityClient 原七方法；增加一个 default 方法及一个私构造的嵌套返回类型。无需新 IAM 服务器、全局缓存、通用 proof 框架或新业务授权中心。

以下都是拟议签名，现代码并无这些 API。为使私构造者确实只有验签实现，类型直接嵌套于 HttpCurrentAuthorityClient；接受这一个明确的 adapter 类型依赖，避免为了隐藏依赖再引入若干可伪造 factory/marker 层。

```java
// CurrentAuthorityClient：原方法全部保留。
default HttpCurrentAuthorityClient.RetainedAuthorization authorizeRetained(
        String serviceTicket, ServiceCall call) {
    return HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(
            authorize(serviceTicket, call));
}

// HttpCurrentAuthorityClient：新增嵌套类型，构造器 private。
public static final class RetainedAuthorization {
    private RetainedAuthorization(
            VerifiedAuthority current, Actor initialCeiling,
            String originAuthenticationId, String ticketJti,
            Instant ticketExpiresAt, String nonce, String requestSha256);
    public static RetainedAuthorization currentOnly(VerifiedAuthority current);
    public VerifiedAuthority current();
    public boolean hasInitialProof();
    public Actor requireInitialCeiling();
    public String requireOriginAuthenticationId();
    public void requireVerified(Clock clock);
}

// HttpCurrentAuthorityClient：只有此 override 可构造带完整 proof 的实例。
@Override public RetainedAuthorization authorizeRetained(
        String serviceTicket, ServiceCall call);
@Override public VerifiedAuthority authorize(String serviceTicket, ServiceCall call) {
    return authorizeRetained(serviceTicket, call).current();
}
```

`currentOnly` 永远把其余私有字段置空；不得接收 ceiling/origin/raw claims，也不得提供 `withInitial`、公共完整构造器、Jackson creator、record canonical constructor 或反序列化 proof 的入口。`requireInitialCeiling/requireOriginAuthenticationId` 在缺 proof 时拒绝；返回数据不是单独的持久授权。`requireVerified` 核有 proof、所有绑定和当前有效期；序列化只允许业务层显式取出初始 Actor 的指定字段，不能序列化整个 proof/VA/service JWT。toString 脱敏，不打印 grant/nonce/原认证 ID。

这类私构造封装防止正常业务代码把当前 Actor/Map 包装成可信初始值，不声称能防同进程恶意反射或任意改库代码。实际信任锚是验签链、scope 生命周期和受控 PG 写入共同构成。

**与另设窄接口比较**：可以新增 `RetainingCurrentAuthorityClient` 并让 guard 做类型判断，但它仍需同一个不可伪造返回类型，又增加能力探测/装配分支。这里 default current-only 已表达兼容边界，更小。不要把原 authorize 调用后再 decode/query 一遍来补 initial；Http 的 authorize 只能投影 override 的同一结果，不能形成 default↔override 递归。

## 3. 同一次验证保留什么，来自哪份签名

在现 `authorize:63-76` 上做一个内部映射路径：

1. 保留 `decoder.decode(serviceTicket)` 后的**同一已验 token**原始类型严格 claims。不得在其它业务调用点重新裸解 JWT。继续核 delegated、exact call 及现 route/current 检查。
2. 只调用一次 `query("authorize", ...)`。沿用现 nonce/request digest、签名与 active/current 验证；保留已核 nonce 和 request_sha256 至私有字段，确保结果属于这次请求及这张 ticket。
3. 将现 verifyCeiling 收敛为内部返回 Actor 的校验方法，在 service JWT 与 current decision 两侧均校验完整值；两侧规范初始值相等，hash 均等于相应签名 operation.ceilingSha256，主体同 VA/delegation/operation，当前 actor 被初始上限覆盖。当前 actor 可 A、初始上限仍 A/B。
4. 继续核 `jwt.jti == current.ticket_jti`、subject_caller、grant_ref、authorization_version、operation 全等和各自 exact root。私有 proof 同时保留 VA（内含 current decision jti、call、grant/expiry、完整 operation）与 ticket jti/expiry、nonce/request digest，不接受事后拼接另外一个 VA。
5. proof 的可使用时限是 ticket exp、current exp、operation exp、grant exp 的最小值；不能因 decision 比 ticket 活得久而延长该受理证明。签名 iat/nbf/max-age 与 source/operation 的既有校验仍走原路径。提交前短事务再做本地时间检查，不在持锁事务内联网。

规范 ceiling hash **继续采用现** `AuthorityProtocol.ceiling`（`AuthorityProtocol.java:39-46`）：`owner/roles/attributes` 的规范摘要。PG JSONB 的物理键顺序、Java record 默认 JSON 或原始 JSON 文本不能替代这个算法。集合需要规范排序，labels 是义务而非权限集合；现 `within:47-52` 要求当前 labels 包含初始 labels，不能反向裁剪。

### originAuthenticationId 的最窄缺口

事实是现 operation/public VA 和冻结签名映射里均没有该原认证标识。`decisionId` 是当次 current decision 的 jti，不是原认证；ticket jti、operationId、grant_ref、guard_ctx 也不能冒充它。

建议只扩展**内部签名 profile 顶层**字段 `origin_authentication_id`，同时出现在 delegated service JWT 和对应 signed current decision：非空字符串，按计划数据库上限拒绝超过 128 字符；两侧全等。它由 authority 在原 PORTAL/INTEGRATION 认证建立 grant 时确定，并由该持久 grant 固定，forward/resume 不重置。主体/grant/root/operation/nonce/ticket_jti 等仍按上述链绑定。无需把字段塞入 OperationBinding 或改它的 equals/公共构造器。

兼容规则必须明确：

- 两侧均无该字段：原 authorize/current 仍可工作，`authorizeRetained` 返回 **current-only**；真实 run 新受理持久路径缺 proof 即拒绝。
- 仅一侧有、类型错、空白、过长或两侧值不同：拒绝，不降级 current-only。
- 两侧均合法且其余验证完整：才 mint 私构造 proof。
- 旧 client stub/default 实现和旧 C/D1/D2 fixture 无须凭空制造 origin。新增 E1 fixture 明确给测试 Grant 保存一个稳定的原认证 ID，再在签名两侧输出；不能用每次 decision 的 ID 补齐。

这里新增字段及真实 authority 含义需 root 签收；测试 fixture 两份签名相同仍不证明真实 IAM 的持久 Grant 锚点已落地，生产 IAM 闭锁保持。若实际 authority 不支持该字段，E1 持久受理继续拒绝，而不是把列变可空或填写常量。

## 4. ServiceAuthorization → AuthorityContext：生命周期不扩大

拟议新增/内部调整如下，保留原公开 `require`、`current`、`requireFor`、`bind(VA)`：

```java
// ServiceAuthorization 内部；沿用现 require 中的 jwt/route/integration 检查。
private RetainedAuthorization requireRetained(ServiceCall call);
public VerifiedAuthority require(ServiceCall call); // 返回 requireRetained(call).current()

// AuthorityContext；包可见，仅守卫装配带刷新入口。
static AuthorityContext bindRetained(
        RetainedAuthorization initial, Supplier<RetainedAuthorization> refresh);
// 与 requireFor 共用一次 refresh 实现，不先 requireFor 再额外 authorize。
public static RetainedAuthorization requireInitialFor(Actor actor, ResourceBinding resource);
// 不联网；供真实新 run 事务绑定本次已刷新的实例。
public static void requireBound(RetainedAuthorization expected);
```

preHandle 改为一次 requireRetained，绑定本请求的 retained mapping 与同 call/route 刷新 supplier。旧 require 的其它调用不凭空 bind 新 frame。旧 `bind(VA)` 仅建立 current-only frame，保持普通 current 读取语义，不产生可持久初始值/refresh 权。

frame 内以一个 retained mapping 为权威值；current() 只返回它的 current。requireFor 与 requireInitialFor 都使用原顺序：先终止旧 frame 的可读性，再检查本地主体/资源，再调用 supplier，再检查 caller/call/delegation/operation/authorizationVersion 不变、当前范围不扩大。带 proof 的刷新还要求 initial 完整值及 origin 不变；新 ticket_jti/nonce/decisionId 不混成一份对象。相同收到的 ticket 应保持 jti；nonce 和 decision jti 则来自这次刷新。

**缺 proof 降级、过期、403、503、origin/hash/绑定错均让这次 frame 永久 invalidated**。AuthorityContext 不能在失败被捕获后再提供旧 current/initial，也不能随后刷新成功使该 frame 复活。已捕获的不可变 mapping 只是历史验证数据，不能靠其 getter 或本地 requireVerified 绕过 requireBound 取得持久权限。普通 current-only 成功刷新仍可普通使用，但 requireInitialFor 缺 proof 必须失败并退休该 frame。

requireBound 做：当前线程/当前最顶 frame 存在且未 closed/invalidated、`frame.mapping == expected`（对象身份）、expected.requireVerified(clock) 成功。故旧捕获 proof 在刷新替换后、非 LIFO 关闭后、作用域退出后或别的请求/线程不能用于 insertNew。close 保持原非 LIFO 行为，已关闭祖先永不再可见。若 requireBound 本地检查失败，同样退休当前相关 frame；不能继续用旧证明写库。

不新增可跨线程共享的 ThreadLocal 或 InheritableThreadLocal。异步业务显式传 VA；需要持久授权时读取已提交 PG 行并取新 current。不能把请求 proof 搬入 worker 再尝试模拟 request frame，也不能公开 `bindRetained` 给普通业务任意重绑。

workload 与 POLICY_MACHINE 保持独立：authorizeWorkload 不返回此 proof、不写用户 run_authority、不以 workload Actor/roles 或 origin 常量进入用户路径。

## 5. 真实 published run 的最小受理链与事务

以下限于 root 已选择的 published 首切片；DEBUG/candidate/受控 skill/session/replay 不套查询 descriptor，不因此默认开放。

1. D3 的 metadata-selector-v1 查询只得到真实 app-version 根。主 Grant 必须从同一仍有效外部 sourceToken 再次 exchange，operation 从开始即该 app-version 根；metadata 查询 Grant 没有 run 持久用途。
2. **在 RunService 当前 256-261 行读取 source 之前**，以可信 metadata/主票已绑定且域内核对的具体根调用 requireInitialFor。核 root kind=app-version、id 为已裁决 code@versionNo、versionDigest 为实际存储 candidateSha；不能只比较 token 自己的两个字段。传给 app 读取的是保留同一 root/operation 的明确 current，经真实出站 forward；不得 resume 给首请求延期。app 在读 snapshot/config/definition 前仍核自己的真实版本与当前主体。
3. published pointer 重核与 409 语义沿 D3：metadata 与主操作间变版即拒绝，不能后台替换 root/票据续命。E1 单靠先验票据不能承诺跨库原子；该重核属于 app 域既定入口与不可变版本校验。此次未读取 D3a0 live 文件，具体方法名需冻结后接入。
4. 初次取源之后仍会 prepareSnapshot/freezeModelRefs（269、283 行）读取 skill/model 依赖；D3 root→child 真实关系与出站迁移未闭合时，E1 的 row 接缝不能单独开放这些真实网络路径。不得用 root equality 假装拥有任意子资源。
5. 网络/冻结准备完成后、进入 319 行 persist supplier 之前再做受理屏障 requireInitialFor，得到 `final RetainedAuthorization admission`，并用其当前 actor 重核本域 guard；若此次发生进一步收窄而已有材料不覆盖，拒绝。此为两个业务时点各一次复核，不是为了取 ceiling 对同次 authorize 重复 online query。
6. **在现 321-467 行的同一 store.atomic 内**，取得 requestId 锁并重查后，插入新 run（380-418 行），紧接着 `runAuthorityStore.insertNew(rid, admission)`，写该 run 的受理材料 scope，再创建 nodes。insertNew 与 scope.write 都强制存在调用方事务、使用同 DataSource/事务连接；不得 REQUIRES_NEW、autocommit 或提交后补写。插入前以及最终返回 rid 前检查本地 proof 有效期；提交前检查发生在事务内，网络不进事务。
7. 事务异常则 run/snapshot/inputs、authority、scope、nodes 全回滚，480 行 scheduler.created 不执行，外部发送数为零。任何已成立的当前请求响应仍需原 response holder/material 规则，不能因为新 row 已写就绕过历史材料覆盖。

拟议 engine 接缝：

```java
// cn.agentplatform.engine.infra.RunAuthorityStore
public void insertNew(long runId, RetainedAuthorization admission);
// 内部读方法只供 RunAuthority，固定列投影、不加载 run.snapshot/inputs。
// Row 是 RunAuthorityStore 内部定义的 public 嵌套 record，不新增通用 DTO 层。
public RunAuthorityStore.Row load(long runId);

// cn.agentplatform.engine.application.RunAuthority：保持 Task5 已约定公共返回形状。
public VerifiedAuthority current(long runId, ServiceCall call);

// MaterialScopeStore 的 Task5 接口不另造。
public void write(MaterialScopeV1 scope);
```

load 和嵌套 Row 的 public 仅满足 application/infra 分包使用，不能对外加 HTTP 序列化/controller。Row 仅是 PG 元数据，不是可伪造的当前授权；每次使用必须经过 RunAuthority.current 的签名匹配。

insertNew 内调用 requireBound(admission)，核新 run 的 tenant/principal、实际 app_version_id/已冻结具体版本与 admission.call.resource 一致，再保存完整初始值。主体/根核对需要先前可信 metadata 与本域冻结记录的确切对应；不能只核用户 body 中 appCode/versionNo。proof 不允许用于任意已有历史 run 的 upsert；原 run 没行不自动补。原 API 的显式重新授权是后续受控流程，必须同时核同资源及历史完整要求。

现 requestId 在 184-197 行、208-235 行与 334-348 行有三条已存在返回路径。E1 接入须在它们读取/返回 payload 前经过当前 metadata/scope 验证；锁内重复命中返回原 row，不把新 proof 覆盖其 initial/origin/grant，不给旧缺 row 的 run 自动补权。不能只在 INSERT 下方加一行而漏掉这三处。返回已有 run 的“可查看”与“可重新执行”仍为不同许可。

## 6. 最小候选迁移字段（仅源树观察，不占号）

本次 engine 源最大版本 V215；skill V311；model V305。未查 Flyway history；不得据此认定数据库下一号已批准。若正式执行时源与已授权测试库顺序仍一致，engine 候选 V216、skill 后续候选 V312、model 材料后续候选 V306。重新核实后决定，禁止改已应用迁移/outOfOrder；本文件不创建或占用迁移号。

engine.run 的 tenant_id/principal_id 在 V200:7 均 varchar(64)，本次后续 migration 搜索未发现修改这两列（V214 的 128 是别表，不套用）。最小 run_authority 采用 Task5 原字段，**补足 root 的 kind/id**：

| 列 | 建议类型/约束 | 来源/用途 |
| --- | --- | --- |
| run_id | bigint PK/FK engine.run(id) | 同事务新 run |
| tenant_id / principal_id | varchar(64) NOT NULL | 同次已验 VA，与 run 主体相同 |
| grant_ref / grant_expires_at | text / timestamptz NOT NULL | 同次已验 DelegationRef；grant_ref 不日志输出 |
| initial_ceiling | jsonb NOT NULL | 私构造 proof 的完整规范对象：tenant_id/sub/roles/attributes，保留全部初始 region/labels |
| resource_kind / resource_id | text NOT NULL | 已验主 operation 的真实根 kind/id，经域核对 |
| resource_digest | varchar(64) NOT NULL | 同一 root.versionDigest；不是 ceiling hash 或任意请求摘要 |
| source_kind | varchar(24) NOT NULL | **原认证来源**，按 Task5 保存 originKind=PORTAL/INTEGRATION；不写本次 DELEGATED 来覆盖它 |
| origin_authentication_id | varchar(128) NOT NULL | 两份已验签名同值的原认证 ID |
| last_decision_id / last_checked_at | varchar(128) / timestamptz | 最新核查审计，不能冒充原认证 |
| blocked_reason | varchar(64) nullable | 缺 grant/撤权/材料不足/authority 不可用等受控阻断 |

initial_ceiling_sha256 可从固定 schema 全值按现算法复算，不要求再加冗余列；若增加存储列必须与全值一致校验，不能只信列。建议 CHECK 限 root digest 格式、source_kind 范围与 JSONB object，应用层做完整类型/集合/主体与 hash 验证。schema 解码固定且严格，不能把未知版本当当前结构。

ticket、用户 JWT、服务 JWT、refresh token、nonce、signed decision 原文均不需要持久到此表；nonce/jti/request digest 是瞬时映射 proof 的绑定材料。新增 accepted operation ID/deadline 审计列不是 E1 恢复所必需，也不能用它替代原 PORTAL response holder。

run 受理材料 scope 与 payload 同事务。复用现 MaterialRef/MaterialScopeV1；完整 requiredEntitlements（含 A/B）与 requiredDataLabels 从可信初始值和实际 parent scope 按 §2.5 派生，parent 要求取并集、labels 只增不减。保守受理根材料以完整初始要求为基础，不能用当前 A 重写为 A；若进一步细分材料要求，应另有可信生产者语义，不能为让测试变绿而裁剪。不同 payload 的 MaterialRef/version/digest 必须真实匹配，不把 run ID hash 冒充内容摘要。该 material_scope 表的完整键、metadata-first 同快照读协议沿 Task5 后续实施，不以这里一张 authority 表替代。

## 7. PG 恢复与异步：新 DELEGATED，不续原 PORTAL

保持 scheduler 的 runId 持久队列边界。它可能派发另一个 run，不能把请求中捕获的 authority 附到下一次任意 worker。准确捕获点分为：受理前捕获 final admission 进入同步事务闭包；worker 在 driveRecovered 内为自己的 runId 取新 current，之后把这个 VA 显式传递。

RunAuthority.current 的候选流程：

1. 只读 run 主体/根关联和 run_authority 的元数据，不先 `select *` 加载 snapshot/inputs；缺 row/grant/完整 initial/origin 即拒绝，历史 guard_ctx 只审计。
2. 严格解析 PG 初始值，复算 ceiling hash，核 PG owner、run owner、root 三字段、grant 有效期；call.resource 必须等于持久根，action/target 由具体受信代码选择并受目录限制。
3. `ticket = client.resume(storedDelegation, call)`；`mapped = client.authorizeRetained(ticket.value(), call)`。这是新动作的一次 resume 加一次 current authorize，不能用旧 Actor exchange，也不从 PG 构造一个假的 signed proof。
4. mapped.requireVerified；必须 sourceKind=DELEGATED、originKind=PG source_kind、originAuthenticationId=PG 原认证、subject/grant/grantExpiry/root 全等、mapped 完整 initial 与 PG 严格规范全等、复算 hash 等于新 operation.ceilingSha256。operationId/issuedAt/expiresAt 为新签名值，**不**与旧 PORTAL operationId 全等，不要求旧 PORTAL operation 仍有效。
5. 在使用 payload 前对每份已存 scope 做 MaterialAuthorization.require，再经同域同快照获取 payload。PG initial 保存 AB、当前 A 时，不能把 PG 改为 A；材料若要求 AB 就阻断。合法同 scope 则可继续。
6. 短事务更新 last_decision_id/checked_at 或记录阻断，重核 row 未被并发显式重新授权改变（至少比较原 grant、origin、root、initial 完整值），并核当前 expiry/dispatch lease。不能拿一次较早外部查询覆盖新授权行或无条件清掉另一失败写入的 blocked_reason；成功清阻断仅在已定义受控重核路径。
7. 返回已核 VA，显式传到 drive/AgentDriver/FlowDriver/ModelExchange/RuntimeClients。每个真实新动作点由 RunAuthority.current 再签新 DELEGATED；同一动作跨 hop 用 forward，不能每个 hop resume 延期。不能只 `drive(id, current.actor())` 丢回旧 ServiceTokens.issue(actor) 路径。

建议 worker 增加 `drive(long runId, VerifiedAuthority current)` 的显式路径；旧 Actor 重载不得仍作为生产恢复的权限来源。无需改变 scheduler LongConsumer API 或把 proof 塞入队列。具体每个 driver/client 签名由后续 D3/E 联合迁移冻结，不能虚称已完成。

403 保留事实并阻断推进；503 保存可恢复原因和退避。RunScheduleRepository 的 claimNext/claimSpecific 都排除被 authority 行阻断/缺 authority 的用户 run，避免反复占槽空转；不新增 run 枚举状态。自动清阻断策略须与受控重核共同定义，不能由普通 poll 无条件复活。

原 PORTAL observer 的 holder/lease/sourceExpiresAt 仍固定在原连接；本方案既不更新它，也不把新 DELEGATED operation 填回它。PORTAL t+10s 关闭后，grant t+300s 可支持合法后台新动作，前提是新签名和材料覆盖通过；这不会恢复旧浏览器流。RR2 的实际首个 provider 发送、chunk/final、后续撤权/503/过期与双流并行验收仍必做。

## 8. 有限验证清单（建议，未执行）

| 场景 | 必须断言 |
| --- | --- |
| 初始 region A/B + 原 labels，current 仅 A | 真正新 run 同事务保存 A/B 全值与 labels；对应受理/父材料 scope 不缩成 A；以当前 A 读要求 A/B 的材料拒绝，外部模型/技能发送 0 |
| subject/grant/root/hash/ticket_jti/nonce/request digest 任一错 | proof 不产生；真实新 run/authority/scope/nodes 不出现，不能回退 currentActor/guard_ctx |
| origin 两侧缺失 / 单侧缺失 / 错值 | 双缺仍可旧普通 current，但受理缺 proof 拒绝；单缺/错值 outright 拒绝；没有 decisionId 兜底 |
| 公共 VA/Actor 自组、currentOnly、旧 bind(VA)、旧客户端 default | 普通原用法保留；requireInitialFor/insertNew 无法创造 persist 权；不要求旧 fixture 伪造历史身份 |
| 单次映射计数 | authorizeRetained 只有一次 authorize online query；authorize 投影同一路径；preHandle/每次明确刷新各一次，无补 ceiling 第二次查询 |
| refresh 成功/失败与非 LIFO | 成功替换同 initial/origin；旧捕获实例不能写。撤权/到期/503/错绑定后即使异常被捕获，current/initial/insert 永久失败；关闭祖先不会复活 |
| 线程/请求隔离 | 不同请求不能混 proof；worker 没 ambient initial；显式 VA 与本 run 的 PG 元数据匹配。workload/policy-machine 无用户 proof |
| payload 后、authority 后、scope 写入期间故障 | 同一真实 PG 事务故意抛异常；run payload/authority/scope/node 全回滚，scheduler 未获得新 run；无独立提交补写 |
| 事务前排队导致 proof 过期 | 本地检查拒绝并回滚；不在事务内联网续票 |
| 正常同 scope 首次受理与 requestId 重放 | 正常新 run 各行一次提交；重放验证当前可读 scope 后返回原 run，不改原 initial/origin/grant；旧缺 row 不补权 |
| 同 scope 新 DELEGATED 恢复正例 | 真正 driveRecovered 从 PG 元数据 resume+authorize，通过全值/origin/根匹配后显式传证；原 PORTAL 过期不阻断合法后台新动作，也不被复活 |
| 恢复错 grant/origin/hash/全值/root 或并发换 grant | 拒绝、不加载/返回受保护 payload、不发送；不覆盖新行/旧材料、不伪造成功事实 |

实现时先失败测试再最小代码；沿原计划使用真实授权测试 PG + 有限 loopback authority/Clock/闩锁。这里的检查清单不代表测试已经运行或通过。

## 9. E1 subset、后续依赖与待 root 裁决

建议 E1 subset：保留同次 strict mapping proof；顶层 origin 字段与缺失兼容规则；request frame 生命周期；published 主 root 受理入口核对；真实 run + authority + 受理材料 scope 同事务；RunAuthority.current 与 PG 全值匹配的新 DELEGATED 接口/有限正例。未闭合的 root→child 出站路径保持关闭。不要只做 holder 单测便声称真实流通过。

必须保留原 Task5 全部内容：FactAuthority 与 skill attempt 独立事实绑定；approvedBy 与冻结 confirmRoles 当前核实（EDITOR 合法正例、要求 APPROVER 而仅 EDITOR 负例，无 ADMIN 自动豁免）；所有 run/turn/response/checkpoint/output/error/event、model invocation/SUMMARY、skill fact、artifact READY 同事务 scope；ChatHistory/requestId/reserved turn、ContextAssembler、SUMMARY→MAIN 派生并集；附件下载/回放 metadata-first 同快照；恢复/事实投递独立 action；503 退避/403 原事实保留；撤权→显式可信重新授权→恢复；RR1 真实响应材料与 RR2 后台新动作/provider/PORTAL observer 双流验收。原 Task5 的测试文件、命令与验收矩阵不删不降格，本提案不复写它们。

待 root 明确签收四点：

1. 是否采用本 default + Http 私构造嵌套 RetainedAuthorization，而不是另设 capability interface；public VA/OperationBinding 形状保持。
2. 内部 signed profile 顶层 origin_authentication_id 的两侧一致、同 grant 恒定与双缺 current-only 规则；这是新增内部 profile 字段，不能伪称已经有。
3. D3a0 冻结之后的具体主 root/metadata/出站接线与 E1 实施者文件边界；不能并发修改当前唯一实施者的 common/app/gateway 文件。
4. E1 是否同时实施 run 受理 scope 最小存储与 RunAuthority.current 有限恢复正例；若拆开，必须显式保留缺 scope/未迁移出站时的默认拒绝，不能把“表写成功”当完整 E 完成。

结论：完整初始值可以不改变 VA 九字段与 OperationBinding 公共构造器而保留；必须给内部签名 profile 增加可信原认证 ID，并以同次私构造映射、请求 frame 存活、真实同事务写入及 PG 与新 DELEGATED 全值匹配共同闭合。源代码事实已核，以上 API、迁移、验证均仍是提案。

## 10. root 后续精化：E1a / 原认证跨 Grant 恒定 / 可信 Clock

本节按 root 后续裁决追加，优先于上文相应候选签名和 subset 描述；仍仅为设计，不表示实施或测试通过。E1a 只闭合 common retained mapping、origin 和 frame；真实 PG、RunAuthority、run/material scope 同事务留给 E1b。源路径尚未迁移或 scope 缺失时继续默认拒绝与生产 IAM 闭锁，E1a 不关闭 E。

**原认证 ID 的唯一最小 fixture 方案。** 冻结 `LoopbackAuthority.java:89-108` 中 portal() 每调用一次都会由 base 产生新的 jti；portal issuer 为 `issuer()+"/portal"`。因此 query/main 测试必须先保存同一份 `String source=f.portal()` 再用于两次 exchange，不能分别调用 portal() 后声称是同一次原认证。D2 integration 外部 policy 分支在 `214-239` 行验 HMAC、固定 `ServiceTokens.ISSUER`（值为 `agent-platform-v12`）、jti 与主体字段，`248-258` 行以已验 issuer+jti 找原认证，且逐项比较原绑定/期限/原 source Actor；`182-189,352-370` 行继续以首份 Grant 的 policyVersion/ceiling 核后续 exchange。source_token_sha256 在 `179-184` 行明确仅为诊断，不能转成原认证身份。

只给测试 Grant 增加一个 `String originAuthenticationId` 分量；在 `verifySubject` 成功之后、创建 Grant 之前计算：

```java
String originId = "oa1:" + AuthorityProtocol.digest(List.of(
        "origin-authentication.v1",
        AuthorityProtocol.text(verifiedSubject, "iss"),
        AuthorityProtocol.text(verifiedSubject, "jti")));
```

固定 JSON array 的顺序就是 tuple 顺序；复用现规范编码/摘要，不拼接带分隔符的字符串，不包含 grant/resource/operation/decision/token 文本/当前 policyVersion。结果是 68 字符，符合原 128 上限。同 issuer+jti 的 query/main 两个不同 Grant、forward、新 DELEGATED resume 必须保留同一 originId；不同 jti 则不同。query/main 的 grant_ref/root/operation 仍各自独立，origin 相同不产生跨 root 升权。固定版本摘要足够覆盖现 portal 与 D2 integration；稳定不透明映射也可以表达相同语义，但此有限 fixture 不再引入一张映射表或随机 ID 缓存。

fixture `identity:293-296` 从 Grant 写入签名两侧的顶层 origin_authentication_id；approver 的 Grant 投影 `209` 行也原样携带它，不能把批准者当前 decision 当发起人的原认证。D2 的 originalAuthentication issuer+jti 查找、原字段/Actor 全等、首 policyVersion/ceiling 锚点与每次当前 policy 复核全部保持；不得替换成“originId 相同即信任”。特别是同 source 改 JWT JSON/header 表示并重新签名可保持 originId，policy 版本变化仍拒绝，不允许旧认证通过重新 exchange 获取新锚点。

来源严格性补充：现 fixture portal verifySubject 的 `234-237` 行只有验签及 user/client 检查，不能把已由 gateway 严格验证这一事实误称为 fixture 自己已完整验证。计算 origin 前须在该有限 profile 补固定 portal issuer、aud、RS256/at+jwt、非空有界 jti 与时间校验；非 external 的旧 integration fixture 固定使用其已有 `issuer()`，external D2 integration 继续使用 ServiceTokens.ISSUER，不能从传入 issuer 动态选择信任域。所有 ID 只能由这些已验 claims 生成。此补强只在现有限 LoopbackAuthority，不新增生产认证实现或放宽 D2 profile。

**Clock 修正。** 接受 root 建议：取消上文拟议公开 `requireVerified(Clock)`，改为 `public void requireVerified()`；带 proof 的私构造器额外接收并私存 `HttpCurrentAuthorityClient.this.clock` 的同一引用，`private final Clock verificationClock`，不暴露 Clock getter/setter、外部 Clock 参数或另一个可覆盖时钟的重载。requireBound 调用该无参方法，所有 proof 过期检查都使用这个验证客户端时钟。currentOnly 没有 proof，先拒绝，不能自行选择时钟变成 proof。应保存 Clock 引用而非验证瞬间的 Instant；测试推进原 MutableClock 后，旧 proof 必须过期。既有 client 构造器已接收 Clock（`HttpCurrentAuthorityClient.java:15,20-22`），fixture client 也已共用 `f.clock`（`LoopbackAuthority.java:81-82`），无需改旧 client/default/VA/OperationBinding API，故没有现有调用方兼容破坏；原公开 Clock 方法尚未实施。测试持有并推进注入的时钟属于受控 fixture，不给业务调用方临时传旧 Clock 的入口。I2 的 frame 失败终止性仍由 invalidated/closed/对象身份保障，时钟回退也不能复活已退休 frame。

**E1a 精确候选文件范围。** 实施者在 D3a0 冻结、root 移交后才可修改 live 的 `admin-common/src/main/java/cn/agentplatform/common/authority/{CurrentAuthorityClient,HttpCurrentAuthorityClient,ServiceAuthorization,AuthorityContext}.java`；嵌套 proof 留在 HttpCurrentAuthorityClient，不新增独立通用类。测试只需 `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java`、新增 `RetainedAuthorizationTest.java`、扩展 `ServiceAuthorizationTest.java`。保持原其它 C/D1/D2 测试不降格；需要协调其它 fixture 的编译适配由 root 指定，不能自行修改冻结副本、app/gateway/engine、迁移、契约或 target。以下是 E1a 新增有限测试集合，不含 PG：

1. PORTAL 和 INTEGRATION 各一次保存原 source 后双 exchange（两个不同具体 root/Grant）：origin 相同、grant/root/operation 独立；同 source forward/resume 保留 origin，新 source jti 变化则 origin 变化；错误/缺失 issuer/jti 的 source 拒绝。query/main 不要求在 E1a 另造 metadata kind 路由测试，用既有合法目录的两个具体绑定验证此身份不变量。
2. D2 integration 表示变化仍为同 issuer+jti/origin；同认证绑定/期限/Actor 篡改拒绝；policyVersion 变化后旧认证重 exchange/authorize 仍拒绝，原锚点和 policy 调用计数不变。涉及真实 D2 app policy 的 PG 联合重跑留给 root 已有 runner，E1a common 测试不冒充该联合验收。
3. 初始 A/B + 原 labels、current A：retained 完整值仍 A/B，labels 不减少。参数化错 subject/grant/root/hash/jti/nonce/request digest/current operation；无 proof、双缺 origin、单缺/错型/错值 origin 的兼容/拒绝规则分别断言。
4. authorizeRetained 每次恰好一次 authorize query；旧 authorize 投影无第二次 query；default 旧 stub 返回 currentOnly，VA 九字段及 OperationBinding 原构造器继续使用。currentOnly/旧 bind(VA)/workload 无 requireInitialFor 或 requireBound 的可用证明。
5. 注入可信 MutableClock 后推进超过 ticket/current/operation/grant 中任一最早截止，旧 proof 无参 requireVerified 与 requireBound 都拒绝；公开 API 无 Clock 参数重载。合法未过期 retained 正例通过。
6. 真实 ServiceAuthorization frame：成功 refresh 替换实例且 initial/origin 恒定；旧实例、跨请求/线程、错误 root、缺 proof 均拒绝；403/503/过期或错绑定后捕获异常再调用也永久失败；非 LIFO/async completion/正常 close 不复活祖先或残留 frame。保留合法嵌套返回外层的正例。

E1a 的“持久权限拒绝”在这里精确指 requireInitialFor/requireBound 接缝，不能声称已验证真实 insert/PG 回滚。E1b 再执行上文真实受理事务、完整材料 scope、同 scope DELEGATED 恢复和 Task5/RR2 联合矩阵。
