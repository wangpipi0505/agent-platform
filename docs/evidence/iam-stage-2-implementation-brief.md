# G6 Task2 可信 JWT 输入实施交接（尚未移交）

仅准备，必须等root明确交付common源码/target且Task1独立审查闭合后实施。唯一工程G:/207/项目/算法重组工具/代码/agent-platform；AGENTS/v1.2/四契约与PG权威、HTTP边界保持。禁止关联相邻旧代码/配置/数据/缓存/进程。用户已授权持续实施与隔离端点，不索要真实IAM环境。

下列为已批准计划8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14原文提取及root阶段约束。计划中的规划历史不当作本次功能证据。Task1现状/接口/SHA将在实际移交时以其冻结报告为准；不得重做已完成Task1或绕过其闭锁。

## 当前阶段范围

- 仅本Task2的common输入验证/HTTP/映射与测试；不写生产IAM、不改业务服务/POM/迁移/前端/部署/其他smoke/台账。测试issuer仅测试目录中的有限loopback HTTP/RSA钥对，不读取.env/vault/masterkey/真实凭据，不启停主服务。
- 即使本轮可信验签完成，尚无Task3当前authority和Task4逐caller/action/resource守卫，mode=iam仍必须拒绝启动业务HTTP；不能以验签成功替代在线当前授权或允许缺失装配。dev/test维持显式限定，不自动回退旧HS256。
- 保留现有Actor四字段和对外ApiKey/SDK形式；不授SERVICE/GATEWAY_AUTH业务角色、不把workload投影用户。不把portal/integration密钥域混入内部decoder。票据/JWKS不能自报URL驱动网络。
- 数值与具体负向样本按下方原文；只用有限小样本/Clock/闩锁/随机loopback端口，不做耗尽/攻击式慢探针，不长sleep。不要自行实现密码学；复用固定Spring Security/Nimbus。
- 起步存全部common src/POM及本轮改动文件完整baseline/SHA；最小可编译接缝之后取得真实错误验签/用途映射允许的行为红，不能编译缺类当行为红。迭代跑覆盖选择器，完成后一次完整common回归；不无故重跑七模块。
- 报告docs/evidence/iam-stage-2.md：需求映射、真实开源源码阅读、真实行为红/绿和完整common命令/原日志、边界/遗留、完整可应用patch/frozen/manifest/真实apply逐字比对。无HEAD，不造git diff/提交，不发布、不派子agent；完成移交target给独立审查。保留生产BLOCKED_IAM_SCOPE_ACCEPTANCE，不把隔离通过当IAM签收。

## 原Task2
### Task 2: 严格多信任域 JWT/JWKS 验证和 issuer 声明上限

**Files:**
- Create: `admin-common/src/main/java/cn/agentplatform/common/authority/TrustedJwtDecoder.java`、`BoundedIamHttp.java`、`PortalClaimMapper.java`。
- Modify: `admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java`、`Actor.java`、`ServiceTokens.java`。
- Test: `admin-common/src/test/java/cn/agentplatform/common/authority/TrustedJwtDecoderTest.java`、`PortalClaimMapperTest.java`。
- Create test helper: `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackIssuer.java`。

**Interfaces:** `TrustedJwtDecoder implements JwtDecoder` 只使用已配置 trust domain；`PortalClaimMapper.map(Jwt)` 产出裁剪 Actor 和允许 scope；`BoundedIamHttp` 提供 `byte[] get(URI)`、`byte[] postForm(URI,Map<String,String>)`、`byte[] postJson(URI,Object)`，每次请求固定边界、no redirect、有限 timeout。LoopbackIssuer 实现 AutoCloseable，用 JDK HttpServer 绑定 127.0.0.1:0，生成测试 RSA 密钥，仅返回小 JWKS，支持在测试线程同步替换 k1/k2 和返回 503，不运行生产 IAM。

- [ ] 先写真实签名样本测试：RS256/k1 正例，HS256 用 RSA 公钥作 secret、none、错 issuer/aud/use、缺 exp/iat/kid、过期/未来 nbf、无 tenant/sub、多个额外受众、ID token、未知 kid 都拒绝；上限之外 ADMIN/region 被拒或裁剪，缺必需 scope 403。至少一个测试直接把通用 SERVICE portal token 请求 `/internal/...`，结果拒绝。
- [ ] 执行 `mvn.cmd -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=TrustedJwtDecoderTest,PortalClaimMapperTest" -Dsurefire.failIfNoSpecifiedTests=false`；decoder/mapper 缺失只算编译接缝，补最小可编译骨架后必须针对真实验签/用途映射错误允许路径先观察行为断言失败，再实现转绿，不能把编译失败当关键失败路径证据。
- [ ] 保留 Nimbus 密码学验签；定制 JWKSource 每次使用 BoundedIamHttp 读取固定 JWKS、校验仅 RSA 公钥/非空唯一 kid/算法/用途后选择密钥。不得自行实现 RSA/JWS；拒绝重复 kid、私钥字段、oct、未知 kid。核心 processor 装配为：

```java
var processor = new DefaultJWTProcessor<SecurityContext>();
processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
processor.setJWTClaimsSetVerifier((claims, context) -> {});
var decoder = new NimbusJwtDecoder(processor);
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
    new JwtTimestampValidator(Duration.ZERO),
    new JwtIssuerValidator(configuredIssuer),
    requiredClaimsValidator));
```

`source` 为该类受限 JWKSource 实例；`requiredClaimsValidator` 在同类验证 issuer 对应的精确单 aud、token_use、必需 exp/iat/sub/jti、票据上限和可接受 claim 类型。portal/delegated 还必须有 tenant，delegated 必须有 grant_ref；workload 必须是配置登记的 client subject、精确机器动作，拒绝携带用户委托 claims。issuer 选择只允许配置白名单，未验 claims 最多用于固定白名单候选选择，不得用于网络发现。integration HMAC decoder 使用独立路径和固定目的，不与此 RS256 key source 混用。`Actor.current()` 消费归一化已验 claims；workload 不能投影用户 Actor；不授 SERVICE/GATEWAY_AUTH。
- [ ] 注入 Clock 直接推进 exp/nbf；JWKS k1→k1+k2→k2，旧 kid 删除后立即拒绝，未知 kid 不回退 HMAC。断开小 JWKS 返回 503 后请求不得进入 handler；恢复端点后新请求成功，旧过期票据不复活。
- [ ] 重跑同命令；记录候选实现的完整负向矩阵。此阶段只完成可信输入，不证明当前权限或开放生产。


## 原信任域与证据类型约定

### 2.1 三种用途和三个信任域

1. **门户 JWT**：受信门户 issuer/JWKS、显式算法、网关受众、`token_use=user`、访问 token 用途；tenant/sub 与 claims 上限配置按 issuer+客户端绑定。只由 gateway 接收，不能用于下游 admin/internal/v1。JWT 可作为 `subject_token` 交给明确配置的受信 IAM token endpoint，不能交给业务服务、模型/算法/MCP 供应商或其他 URL。
2. **内部服务票据**：由外部 authority 签名，`token_use=service`，aud 恰为 `urn:agent-platform:service:<target>`（建议命名）；签名声明 caller、target、action、tenant、sub、grant_ref、authorization_version、resource binding、iat/nbf/exp/jti。caller 来自经过 IAM 验证的专用 client 身份；客户端 body 中自报 caller/roles/attributes 不被 authority 采信。业务服务只拥有本服务 client credential 的 secret_ref，不持有 authority 签名私钥，也不共享其他服务 secret。
3. **外部集成短票据**：保持 `agent-platform-embed`/integration、`credential_id/app_code/biz_system_code/user_id/policy_digest` 和 ≤300 秒。HMAC 仍可以用于这个设计明确允许的集成出票域，但生产密钥只在专用集成签发/验证侧使用，与 portal/internal 信任完全隔离。不能把该 HMAC 加入任一业务服务 internal decoder 的 fallback。ApiKey 仍按现有验密、claim_limits 和吊销策略处理，现有 SDK 不改 Authorization 形式。

`Actor` 中的业务 ADMIN/EDITOR/APPROVER 可以保留既有域语义；生产 SERVICE/GATEWAY_AUTH 不再作为业务角色授予。服务能力来自独立 `VerifiedAuthority`。入口先验证服务能力，再执行现有 tenant/owner/guard/approval/版本约束；scope 不替代资源所有权。

### 2.2 委托获取与恢复：不是标准协议天然提供的能力

初次门户请求可使用 RFC 8693 token exchange：网关向 IAM 提供已验门户 token；IAM 验客户端、原 token、当前用户权限及可委托服务边，返回目标票据。下一个服务用收到的内部票据交换更下游票据，不能把原票据原样转发到另一 aud。

**持久委托引用 `grant_ref`、当前授权查询、恢复续票、批准者角色核实是本计划的 IAM adapter profile，原则已由 root 裁决接受，不能声称 RFC 8693 强制支持。** production adapter 消费实际 IAM 对应接口；本工程不实现它们的生产服务端。引用必须由 authority 签发/登记并绑定 subject/tenant、允许 caller 链、资源、最初授权上限、有效期、撤销状态；PG 保存的是引用，不是 bearer/refresh token。只有持有授权 caller 专用凭据且资源绑定一致才能使用引用；单独获得引用不等于获得权限。authority 不支持这些能力时，相应生产动作保持关闭，但软件适配和测试继续。

集成身份的委托不能伪装成 portal 用户。authority 的集成 profile 必须信任受限 gateway 认证能力，验证集成来源证明并从权威 app policy resolver 查询当前 credential ACTIVE、claim_limits、app/biz 和策略摘要；独立 resolver 必须验证 authority 专用 client，不能循环依赖尚未签发的用户票据。原 integration token 仅可作为交换到 authority 的材料；该原票据不进入业务下游。若实际 IAM 无此 profile，本阶段生产集成桥接保持关闭，而 dev/test 现有 ApiKey/SDK 与隔离集成 authority fixture 可完整验收；不得接受 gateway 任意提交的 Actor 作为替代。此 adapter/profile 是需要 root 审查的实质边界，不自动扩建一个 IAM 产品。

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


## 原配置、数值与错误边界

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


## Task1 实际接口交接提示（待其独立审查闭合）

当前Task1作者冻结报告为iam-stage-1.md、source-after/full.patch在iam-stage-1目录；仍在独立审查，必须等root正式移交后才能使用为已关闭前置。AuthorityProperties为record，已含mode/serviceId/Portal/Authority/Integration及旧开发字段；允许显式dev-hmac、test、iam，dev/test均固定loopback，test还要求显式test profile，iam始终由静态BeanFactoryPostProcessor闭锁。

Portal现有字段为enabled、issuer、jwksUri、audience、clientId、roleAllowlist(Set)、attributeAllowlist(Set名称)。这些只是Task1装配形状；Task2必须实现批准要求的issuer+client绑定和角色/属性值上限，不能把“键名在白名单”当作region值已获授权。按需要窄增强同一配置类型及测试，不另造绕过强校验的配置源。敏感dataLabels为限制义务，不能通过属性裁剪削弱；region等许可仍取交集。记录具体支持的已签JWT client/用途/scope字段及严格类型，测试错误client/缺scope/越界region；不声称兼容未验证的任意外部issuer。

Task1尚未实现IAM测试HTTP例外。Task2按原§4只在显式test profile允许固定loopback HTTP的小型issuer/authority端点，生产配置仍HTTPS固定可信源、无URL发现/重定向。不得因这条例外打开业务IAM listener、引入ready开关或回退开发解码器；完整当前authority和六服务能力守卫未接入。门户原exp是后续响应来源期限；原计划60秒上限针对内部票据，不把门户旧3600秒实现值当设计硬约束。任何新的profile边界需在报告明确说明，不伪称原契约字段。
## root 正式前置交接（2026-10-20）

Task1已独立Spec/Quality Approved，原I1/P2通过两行最小修复及六反例闭合；见review-iam-stage-1.md最新追加。实际当前common28文件/target来自iam-stage-1-ownership-fix/source-after.json、target-sha256.json，完整common61/0/0/0；这不是七模块962的重跑或新部署。根类型配置/路由仍来自原Task1，只有ClaimCeiling和AuthorityPolicyTest另有修复补丁；Task2起步baseline必须取当前源码，不取原55版frozen或失败补丁副本。

具体闭合类型与模式见iam-stage-1.md、iam-stage-1-ownership-fix.md。后续HTTP/JWT类按本Task原文及上方实际接口提示实施。测试可以用测试目录中独立随机loopback HTTP链验证可信decoder/mapper/受保护handler拒绝；不能为了该测试让实际mode=iam业务启动，不能配置ready/跳过guard。业务服务配置仍未迁移，主六服务保持原固定962JAR。