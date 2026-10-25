# G6 Task4 D2：独立 policy resolver 与 PG revision

root正式移交：D1已独立Spec compliance Approved / Task quality Approved，见review-iam-stage-4-d1.md（SHA9e5b7041c55619c85796e3339bdf3ecb7d56536dfb21bd658817d0218e98a3a2）。root已阅读全文，X1–X4分别移交D2/D3/E，本次X4必须落地，D1 source/target已归还。起点common67源码，完整145/0/0/0，source-after.json在iam-stage-4-d1，补丁55eea451fdea3bf2bc7e669d51d67617eff4097b063679ce88c747adffb2569e；root原字节/保护/12XML核对见.local/iam-stage-4-d1-root-handoff-check.json。下文准备期“未授权/待补”以本节及root-handoffs正式裁决为准。

唯一新工程 G:/207/项目/算法重组工具/代码/agent-platform；不关联旧工程，原四契约/v1.2不改。用户已授权持续开发和隔离端点，真实IAM缺失不停止内部实现。本稿不签收Task4完整R3，D3实际网关/下游接入后仍须做原联合链。

原任务要求：`iam-stage-4-implementation-brief.md` 的“原Task4完整正文”和本文件后附原 §2.6。开始前读 AGENTS；Java21/Boot3.5/Security6.5/Nimbus9.37.3、PG唯一权威、分层/HTTP、不引入业务服务JAR依赖保持。

## 正式实际接缝、范围与测试运行

必读 `iam-stage-4-d2-root-handoffs.md` 的全部裁决，尤其最后正式X4窄proof接缝。D1实际API：ServiceAuthorization(serviceId,routes,currentClient[,healthMethod])、receiver.mappedInterceptor()全局@Bean、RouteCoverageVerifier(serviceId,routes,Collection<RequestMappingInfoHandlerMapping>,Collection<Ingress>)、Ingress(method,template,Method)、SecurityConfiguration.serviceSecurity(http,decoder,receiver)、AuthorityContext.requireFor(actor,resource)。不能仅登记Ingress便期待policy域通过。

本次写入范围：原列出的app api/application/domain/infra、IntegrationService/IntegrationController及候选V104迁移和必要独立policy配置；common最小IntegrationClaims/PolicyRequestVerifier、ServiceAuthorization的专用proof衔接、相关测试、common test-jar POM和最小公开fixture入口；gateway仅IntegrationPolicy委托共用纯规则及其直接回归测试；app POM仅test-jar测试依赖。其它gateway桥接/业务服务/前端/父POM/部署/原契约/外部文档、前序证据均不动。任何超出上述范围先向root报具体原因，不派子agent。

专用PG：本新工程127.0.0.1:25432，D2新测试库固定 `agent_platform_iam_policy_v12`（开始前核是否存在；只在该库创建/迁移app测试schema，不能改main运行库）。凭据由root准备的 `.tools/run-iam-module-tests.mjs` 受控注入；先读脚本，不读取/打印配置内容。它只加载新工程命名PG测试密码，子进程JWT/master临时生成，secret目录新建，不读旧vault/master/IAM秘密。示例：`node .tools/run-iam-module-tests.mjs iam-stage-4-d2-red admin-common,app-management,platform-gateway IntegrationPolicyResolverHttpTest,PolicyRequestVerifierTest,IntegrationPolicyTest,IntegrationRulesTest`。每次日志stage唯一，保留所有失败；需要其它覆盖方法选择器可先说明语法，不拼秘密进命令。

聚焦开发后，一轮完整common应含原145及新增覆盖；app/gateway跑新实际resolver/直接修改覆盖，准确列出没有跑的原六服务旧SpringBootTest（其mode/service-id迁移属D3）。不通过全局mode/service-id覆盖破坏common原负向配置测试，也不把跳过旧服务测试说成全app/七模块通过。D2必须在同一真实mapping中保留普通service守卫并验证policy正负；真实ADMIN既有policy/revoke接口与PG revision变更必须经过HTTP和正确身份/资源检查。若需要最窄地补该三处实际域资源复核，以真实PG metadata为准，向root报清形态，不以固定测试资源代替产品域对象。

## D2 最小实施范围

- app 新增 IntegrationPolicyResolverController、IntegrationPolicyResolver、PolicyResolveRequest、PolicyResolveResult、PolicyResolverSecurity，路径按原Task4。新增 policy_revision 迁移前重核实际最大版本；准备期仍V103，候选V104不占位。
- 修改 IntegrationService/IntegrationController 的真实策略/凭据变化及必要配置绑定，revision 在原事务内变更，角色和原租户/归属检查保留。当前只有 issue/revoke/policy 三处写入；没有独立reissue/key-replacement API，不为追逐名称新增无必要入口。初次revision和后续每次实际变更/回滚/并发更新需明确PG证明。
- 精确独立POLICY_MACHINE chain只接收 `POST /internal/iam/integration-policy/resolve`。普通workload/portal/integration/admin或普通authority签名域均无此能力，不调用通用authorizeWorkload或Actor.current/SecretVault。固定独立公钥/issuer/TTL/类型/用途/nonce/body摘要，缺配置不开放该生产入口。
- 严格版本化DTO与一次参数化PG snapshot；不可使用静态ACTIVE、旧secretRef credential查询或内存Map作为生产resolver。返回字段仅原完整DTO，无secretRef/密文/key/principalIds全名单/业务payload。
- 运行时覆盖接入D1的实际精确入站登记；这不是为所有internal路径开豁免。gateway不能proxy暴露该入口。

## root 已核实的兼容约束

参见 `iam-stage-4-inventory/d2-preparation-note.md`。gateway实际principal=短 `biz:user`，过长时为 `ext_` + SHA256(biz + NUL + user)前60；digest使用既有secretRef/claimLimits/allowedOrigins canonical语义，不能改变已出票外部格式。角色仍剔除平台内部角色，dataLabels作为独立义务返回。D2正式移交前root将裁定最小共用纯规则接缝，不能复制整段gateway实现或依赖gateway业务jar。

现common LoopbackAuthority包私有且硬编码测试resolver/credential7/alice/policy-v1，它不等同真实app映射。必须扩展有限测试接缝使新联测从真实app resolver HTTP读当前PG，不对旧fixture改几布尔字段冒称联通。拟使用最小common测试构建/公开facade；具体形态、PG测试凭据注入runner、D1实际guard/登记接口由root在正式移交节补齐后才可实施。不得借准备稿擅自扩写common生产框架或读取/打印真实秘密。

## 有限真实验收

1. 最小可编译接缝后先保存真正行为红，尤其旧policy/revoke不变revision、旧版本仍可用、错误签名/方法/body绑定错误不得查PG；缺类/编译失败不算行为红。使用小样本、Clock和一次请求，无耗尽/慢探针。
2. 真实随机端口app端点+专用PG。独立测试RSA键签请求；精确合法DTO成功，错误caller/target/action/method/path、普通service签名域、unknown kid、HS256/none、过期/未来iat/nbf/重复JSON、nonce/body摘要不符分别401/403且resolver查询0。错误租户/app/biz/credential/user/principal不能返回他人事实；字段类型/缺失/超界明确拒绝。
3. 两个有限用户：合法主体当前上限和独立dataLabels；principal被移除/REVOKED/expectedPolicyVersion不匹配默认拒绝授权。校验返回JSON精确字段，无秘密及principal全名单；包括长principal映射与旧digest兼容。
4. 经真实ADMIN既有policy/revoke HTTP入口更新PG，验证原事务revision递增及失败回滚；authoritative新版本不能被请求body覆盖。有限并发不能丢递增或读出混合snapshot。LoopbackAuthority再次exchange/resume/authorize旧grant均拒绝，新认证按真实新政策显式建立新grant才可成功。一次grant决定最多一次resolver查询，resolver→authority=0。
5. 以上D2用真实app controller/service与PG，不冒称gateway业务桥接已完成。D3后必须补原gateway→authority→实际app/PG→真实downstream SecurityFilterChain的完整R3矩阵，并保留原ApiKey/CORS/SDK行为。
6. 聚焦 `IntegrationPolicyResolverHttpTest` 和实际修改覆盖先通过，再一轮适当common/app回归；旧测试真实依赖与跳过项必须准确披露，不拼不同轮次或纯fixture计完整Task4。冻结前保持源码同码；无关七reactor/主服务/package不运行。

报告与证据拟为 `docs/evidence/iam-stage-4-d2-report.md` 和同名前缀目录，完整patch/baseline/frozen/实际apply字节比较、源码/target/保护SHA、实际red/green命令日志XML、开源实际阅读及自审疑点。仅正式范围可写；不派子agent、不发布、不动原证据或其他模块迁移。source/target归还后独立复审。

所有组件通过仍保持 T01唯一完成、IAM_TRUST_CHAIN_UNAVAILABLE/BLOCKED_IAM_SCOPE_ACCEPTANCE及真实外部签收边界；D/E真实材料链、冻结confirmRoles与G2许可另行落实。

## 原批准计划 §2.6（逐字摘录，附后）

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

