# D3/E 最小内部协议接缝提案

2026-10-20。状态：**待 root 裁决；没有实施、测试或验收结论**。

## 1. 事实、范围与选择

本报告只读取唯一工程中的 AGENTS.md、implementation-brief、contracts/README、D3 resource-callmatrix/root-handoffs、Task4 原文、Task5 implementation-brief/ceiling-handoff-note，以及 i3/frozen/admin-common 中针对 exact binding 与 ceiling 映射的少量 C 文件。没有读取正在冻结的 common/app 候选，没有读取旧工程/凭据，没有访问网络、服务、PG 或进程，没有运行测试，没有修改生产/测试代码或四契约。D1 Approved、D2 common150+appHTTP13 正在冻结，是 root 交接状态，不是本报告复审所得。

已核事实：

1. C `ResourceBinding(kind,id,versionDigest)` 三项非空；`CurrentAuthorityClient` 的 exchange/resume/forward 使用明确 ServiceCall；根资源不能在已有 Grant 上升级或替换。`HttpCurrentAuthorityClient.forward` 保持 operation 相等。`ResourceBinding` 的注释明确要求具体版本化资源。
2. gateway 首次常只有 appCode/runId，既没有本域 PG，也没有真实版本摘要。现有 app snapshot、run status、tool-call DTO 含业务 payload，不能拿它们作受理前 metadata 查询。
3. 已有 PG app-version→run→tool-call/invocation 业务锚点可复用，但还没有完整的授权受理/关系投影；关系只存在于 body 不足以证明。
4. C 的 authority 映射已经验签并检查 `initial_ceiling` 与 operation hash，但返回的九字段 VerifiedAuthority 丢掉完整值。普通 bind(VerifiedAuthority) 不能恢复它。
5. measure/tokenizer 可能实际发送 messages/tools/request；仅保护 chat 首发不完整。docs、集合、健康探测、事实投递有独立语义。

**推荐方案 1：增加独立的 metadata admission 客户端 profile，先核原认证与当前限定查询许可，再读真实窄 metadata；主 exchange 必须消费该受理证明。** C 的普通 Grant、exact ResourceBinding、forward/resume 行为不变。新 profile 的 authority 端行为仅作为外部集成契约及有限测试 fixture 实现；不在工程增加生产 IAM 服务器。真实 IAM 未实现/签收该 profile 时，该入口保持闭锁，`BLOCKED_IAM_SCOPE_ACCEPTANCE` 不变。

| 首入口方案 | 合法实现必须具备 | 本提案裁决建议 |
| --- | --- | --- |
| 1 独立 metadata admission | 独立 token_use/受众、原认证核验、限定 selector、当前策略核实、固定域投影、主 exchange 消费证明 | 采用；不制造 metadata Grant，不碰 C 根资源语义 |
| 2 C metadata-only grant | 单独精确 metadata action；受限 selector 必须作为独立查询许可资源；主动作同原认证新建真实版本 Grant；受理证明消费与策略重核仍不可少 | 可设计但本轮不采用；多一个 Grant/operation 生命周期，并没有省掉新协议 |

方案 2 中 `typedSelectorSha256` 只能表示规范化、受限查询的许可语义，例如“tenant T 的 app C 的 published 元数据查询”；它不表示被查询 app 的真实发布版本。它只能出现在明确的 metadata-query 资源种类，不能作为 run.execute/skill.execute/model.chat 的 versionDigest，也不能 resume/forward 为真实版本。若以后选此方案，必须明确裁决该新查询资源种类、第二个 Grant 和原认证绑定；不能用普通 request hash 包装全动作。

## 2. 首入口协议：让 authority 直接取得固定域投影

推荐由 authority adapter 直接调用固定、受信的域 metadata receiver，并把所得解析证据封入其签名 receipt。gateway 不转述域证明。这样不用给每个业务域新建一套“任意资源证明签发器”，也不需要 metadata receipt PG 表或在首入口先读业务 payload。

推荐顺序：

1. gateway 用现有专用 portal/integration 验证链取得原认证证据，完成精确 method/template→主 action 分类。未知分类拒绝。session 投影等不需业务版本的认证路径沿专用链处理，不强塞业务 Grant。
2. gateway 调 `resolveMetadata`。authority 独立核原 portal JWT 或 integration token，核当前主体/策略和“可查询该 selector 的元数据”权限；不能只核 gateway 身份或其 Actor。
3. authority 用独立 metadata-only 票，通过配置中的固定 HTTPS 域地址调用 `POST /internal/authority/metadata/resolve`。域 receiver 核独立 profile，并在线 `authorizeMetadata`；该 decision 仅重新核权限，不再递归解析元数据。
4. 域在限定 tenant/subject/visibility 的窄 SQL 中取真实版本列/预存摘要，返回严格白名单投影。authority 校验固定 producer、返回关联字段、nonce/request digest 与 selector，签发短寿命 receipt。
5. gateway 调 `exchangeResolved`，仍提交同一原认证证据与精确主 ServiceCall。authority 原子消费 receipt，重验当前身份/策略、实际 binding、主 action 许可，创建第一个真实主 Grant/operation。随后 gateway 按 C forward 到业务服务。
6. 业务 receiver 在线 authorize，核实际对象 metadata 与票中 exact root 一致后，才读 payload。mutable 对象必须解决同快照/版本竞态；receipt 不是免检 payload 票。

这里新增的是 adapter profile 契约，不是宣称现有 IAM 已有这些 API。为保持外部配置最少，建议复用已配置 tokenUri/decisionUri 的固定 `operation` 分派，不新增可由请求指定的任意 URI。metadata 域地址是 authority 运维配置，不能取 gateway 请求中的 URL。

### 2.1 最小 Java 类型与方法（拟新增，不是现有 API）

```java
// 各 record 实现时必须严格校验未知字段、长度、枚举、空值和互斥分支。
record MetadataSelector(String kind, Map<String, Object> keys) {}
record MetadataIntent(String target, String action, MetadataSelector selector) {}
record MetadataAdmission(String receipt, ResourceBinding resource, Instant expiresAt) {}

interface MetadataAdmissionClient {
  MetadataAdmission resolveMetadata(String subjectToken, MetadataIntent intent);
  ServiceTicket exchangeResolved(String subjectToken, ServiceCall mainCall,
                                  MetadataAdmission admission);
}
interface MetadataDecisionClient {
  VerifiedMetadataAccess authorizeMetadata(String metadataTicket,
                                           MetadataIntent intent,
                                           String requestNonce);
}
```

`Map` 只表示 JSON 的有限联合字段；接收端按 kind 解码成 sealed selector，不接受透传 Map 查询。例如首个切片仅注册：

- `app.published.v1: {app_code}`；
- `app.version.v1: {app_code,version_no}`；
- `app.draft.v1: {app_code}`，返回实际 draft revision；candidate 需单独 `{app_code,candidate_id}` 并核已冻结行；
- `run.owner.v1: {run_id}`；
- 后续按真实路由补 `session.owner.v1`、`artifact.owner.v1` 等，每种固定 owner/visibility SQL 和输出字段。

`VerifiedMetadataAccess` 为验证器产生的不可反序列化对象，包含 verified subject/source、policy version、intent、nonce、expiry、origin authentication binding；不提供 Actor→普通 ServiceTicket 的方法，不提供 payload read 方法。metadata receiver 不注册到普通 delegated/workload 通用目录，不接受普通 SERVICE/ADMIN/门户票直连。

### 2.2 JSON 字段与信任绑定

gateway → authority（tokenUri）：

```json
{
  "operation": "metadata_resolve_v1",
  "subject_token": "<原认证证据，仅在请求内存中>",
  "intent": {
    "target": "engine",
    "action": "<目录中的精确主动作>",
    "selector": {"kind": "app.published.v1", "keys": {"app_code": "demo"}}
  },
  "nonce": "<每次随机>",
  "request_id": "<本次受理幂等标识>"
}
```

`target` 是主动作接收域，metadata producer 由固定 selector 表导出（此例是 app）；不能让请求自行把 app 元数据的 producer 改成 engine。caller 从 gateway workload credential 得出。请求 digest 按统一规范 JSON 覆盖完整请求；日志不得输出 subject_token。

authority → 域：固定 POST，body 为 `{intent, nonce}`；独立 bearer JWT：

| 字段 | 必须核验的值/含义 |
| --- | --- |
| `typ`, `token_use` | `agent-metadata-access+jwt` / `metadata-access`，与 service、POLICY_MACHINE、response permit 分离 |
| `iss`, `aud`, `caller`, `target` | 固定 authority issuer；`urn:agent-platform:metadata:<domain>`；固定 `authority-adapter` 客户端身份；实际域 |
| `action`, `intended_target`, `intended_action` | 仅 `metadata.resolve`；与 intent 的主 target/action 精确一致 |
| `tenant_id`, `sub`, `source_kind` | 原认证核实所得；仅 PORTAL/INTEGRATION 首入口，不收 body 身份 |
| `origin_authentication_id`, `subject_token_sha256` | 原认证实例与原 token 的绑定；不得等同“当前同用户名” |
| `authorization_version`, `policy_digest` | 本次 authority 策略版本与规范摘要；integration 另带原 `credential_id`, `app_code`, `policy_revision`，经实际 resolver 核实 |
| `selector_sha256`, `request_sha256`, `nonce`, `jti` | 分别绑定规范 selector、实际域请求、请求 nonce、单次票；digest 是防篡改字段，不是业务 version |
| `iat`, `nbf`, `exp`, `admission_deadline` | 建议 metadata max TTL 15 秒、总受理窗口最多 30 秒；均不得超原认证有效期；实际值由 root/IAM profile 裁决 |

无 grant_ref/operation/执行权限、无初始 ceiling 的冒充字段；它尚不是主动作。`authorizeMetadata` 的签名 decision 必须回绑 ticket_jti、原请求 nonce/digest、caller/target/subject/intent/policy，并核 active/current。短 TTL 不能代替这次当前核实。域必须核调用方实际认证为 authority adapter，不能只看 JSON caller；开发 fixture 也覆盖这个边界。

域投影响应：

```json
{
  "producer": "app",
  "nonce": "<域请求 nonce>",
  "request_sha256": "<域请求规范摘要>",
  "selector_sha256": "<同 selector>",
  "tenant_id": "<已验 tenant>",
  "sub": "<已验 subject>",
  "resource": {"kind": "app-version", "id": "<真实版本行标识>", "versionDigest": "<预存真实版本摘要>"},
  "object_revision": "<若对象可变则为真实 revision；immutable 可省略>",
  "relation": {"app_code": "demo", "owner_checked": true},
  "observed_at": "<读取时刻>"
}
```

`owner_checked` 只是结果标志，不能单独构成来源证明；其可信性来自固定受信 receiver 的 SQL 与 authority 直接验证的传输来源。app visibility 不一定按个人 owner，具体谓词待 app D2 冻结后核实；run/session/artifact 必须核真实 owner。integration 对 run 的 appCode 还须与原 credential app 一致。没有匹配对象不返回可枚举 metadata。该 DTO 不含 snapshot/config/prompt/inputs/arguments/output/result/mock/cases/secretRef/对象存储键。

authority 只接受固定配置的域 HTTPS 服务身份及当前请求直接返回的响应，禁 redirect、代理重定向和 gateway 上传同形 DTO。生产认证可用固定 TLS 服务证书验证/mTLS；不能只靠 `producer:"app"` 或 loopback 地址。fixture 必须有拒绝假 producer/错误传输身份/旧 nonce 的实际边界。authority 不得从 receiver 响应推导更宽主体权限。

authority → gateway 的 `receipt` 为签名 JWT，`typ=agent-metadata-admission+jwt`、`token_use=metadata-admission`、`iss=authority`、`aud=urn:agent-platform:authority:admission`。claims 至少含上述原认证/主体/策略字段、`client_id=gateway`、`intent`、`selector_sha256`、`resource`、`producer`、`object_revision?`、`resolution_id`、`resolution_sha256`（完整投影的规范摘要）、`nonce`、`request_sha256`（绑定原 gateway 请求）、`jti/iat/exp/admission_deadline`。gateway 客户端验证完整签名及这些字段；不能只把 resource 回显当已验。

`exchangeResolved` 请求为现有 exchange 所需原 `subject_token`、主 call 三字段，加 `operation=exchange_resolved_v1`、`admission_receipt`、新 nonce、原 request_id。authority 必须：

- 同一原 token digest、原认证实例、subject/source、gateway client、intended action/target、真实 resource 精确匹配；
- 对原认证和 integration resolver 重新核 current，策略版本/摘要不同则拒绝并要求重新解析，不能自动按旧 receipt 放行；
- `exp/admission_deadline` 内消费；主 operation 从本次主 exchange 开始，符合 C 原寿命规则且不超原认证/Grant 界限；metadata 解析不产生可恢复执行寿命；
- receipt jti 与 exchange request_id 原子一次消费。相同请求的网络重试只返回同一结果/拒绝重复，不能生成第二个 Grant；同 receipt 不同 request_id 拒绝。该幂等状态是外部 authority 职责，fixture 只验证有限行为，不冒充生产持久实现；
- 常规主授权不因 receipt 成功而跳过。其签名 exchange decision 额外回绑 `admission_jti`、`resolution_sha256`、`subject_token_sha256`，客户端逐项核验后才接受 ServiceTicket。

这不是把 metadata grant 升级；首个 Grant 从一开始就绑定真实业务版本。现有执行 operation 也不能调用首入口 profile 换根/刷新期限：它只用于显式的新外部请求，不能接 service ticket、grant_ref 或已失效门户证据。

## 3. 资源分类与 TOCTOU

以下为建议语义；实际 kind/action 字符串需 root 与已有能力目录对齐，不能通过新增别名绕过原 action。首个纵向正例是 published app→run→冻结 skill/model；其余入口仍须按 Task4 全覆盖，不能删掉验收。

| 动作类 | 真实资源与版本 | payload 前界限 |
| --- | --- | --- |
| app/version 执行 | app 真实 immutable version id + 内容摘要；debug 为真实 draft revision/candidate identity+摘要 | metadata SQL 先读预存摘要/状态；主票受理后同版本读取 snapshot |
| run/session/artifact 观察 | 真实对象 id + 冻结来源或实际 material version；绑定观察专用 action | 首次 owner metadata；每项 material 仍由 E 判权；观察票不能执行模型或恢复原 operation |
| mutable 管理 | provider/entry/skill draft 等实际对象 revision + 预存配置摘要 | expected revision 与本次更新同事务 compare-and-set；变更后拒绝旧绑定或重新外部受理 |
| list/aggregate | **本提案优先采用持久 query snapshot**：实际 query 行 id + 规范 query spec、主体、受限过滤条件、排序/页界和冻结候选 refs/revisions 的摘要 | selector 查询权限先行；仅在域内以窄 metadata 建 query snapshot；主票按 snapshot 读；每项 payload 独立 E scope，不将候选清单当授权清单 |
| create | metadata admission 先核 tenant+具体创建 action，再建立短寿命 `creation_intent` 行，真实 id + `{tenant,subject,resource_type,namespace,action,nonce,expiry}` 摘要 | intent 只预留许可与命名空间，不写业务 payload；主票后校验创建内容并与 intent 一次消费同事务创建对象；禁止以 intent 执行新对象的后续动作 |
| docs 聚合 | 每个服务真实部署 API 文档制品 id/digest；实际 build/runtime mapping 清单变动即换摘要 | docs 独立 action；本机由可重复生成的制品 metadata 提供 digest，授权后读对应不可变文档，聚合时各域分别授权；不假装为 run child |
| health/usage maintenance | provider 的真实 id/config_rev/config 摘要，独立机器 action | 严格固定探测方法、目的地/参数；不接受用户 messages/tools/任意请求；probe 首发核 revision/current；usage reconciliation 另列 action |
| fact-read/fact-deliver | 同一执行根下的真实 call/attempt/fact generation+原始材料摘要，固定 producer 证明关系 | **按 Task5 初版保留用户 current 与 E scope**；机器身份只证实生产者，不允许撤权后泄露 fact；事实票不能 execute/claim/submit |

query snapshot/creation intent 是授权内部 side table，不增加产品入口或改四契约。无现成集合 revision 时不得填 `1`，不得以过滤 queryHash 当集合真实版本。为避免一开始引入长期集合版本管理，建议 snapshot 有固定 TTL/条数/页界；分页引用同一个 snapshot，超界重新受理。候选集合可先仅保存元数据/引用，不得为了生成摘要先读取 payload。聚合数值/敏感标签不应塞 metadata 快照，应在主授权与各项 scope 成功后计算。

如果 root 认为 query snapshot 对某个纯列表过重，可另裁决 typed queryscope；必须精确定义 kind/id/digest 为规范许可边界（tenant、action、允许过滤字段、最大页界/排序、subject visibility predicate version），声明它从来不是结果集合版本，每行仍 current+scope+revision 校验。本提案没有默认批准该替代；它也不能泛化到执行动作。

TOCTOU 规则：

- immutable root：后续只取已解析版本，current published 改变不能静默换版；若产品动作必须“当前发布”，则主受理时比较指针并冲突重来。
- mutable root：metadata revision/digest 同事务锁定或条件匹配后取 payload/写结果；不在网络 authorize 期间长期持数据库锁。拿 current 决策后进入短事务，重新核 expiry、revision、owner/关系；外发前再 current。
- `SELECT *`、读取 JSON snapshot 再删字段不算 metadata-first。缺预存可信摘要时，在创建/冻结业务对象时同事务生成列；已有数据缺证明保持阻断，受控迁移另核，不能在本次未授权查询中回读 payload 现算。
- query snapshot 的候选关系可变时，读取各行 payload 前校验冻结 revision/真实 scope；不匹配则拒绝或重新受理，不能混入更新后内容。SQL 限 tenant 是必要条件，不能代替 IAM/owner/scope。

## 4. E1：同一次映射保留完整初始 ceiling

最小新增通道，不改变 VerifiedAuthority 九字段，复用 C 的 Material* 纯类型：

```java
interface AdmissionAuthorityClient {
  VerifiedAdmission authorizeAdmission(String serviceTicket, ServiceCall call);
}
// VerifiedAdmission/InitialCeilingProof 是不可由 JSON 构造的验证产物。
// 仅返回只读 view；构造路径局限于实际验签、current decision 映射器。
final class VerifiedAdmission {
  VerifiedAuthority authority();
  InitialCeilingProof initialCeiling();
}
// guard 将 VerifiedAdmission 显式交给业务受理；旧 require 仍可返回 authority。
// 不增加 bind(Actor) 或 bind(rawClaims) 的可信入口。
```

在 `HttpCurrentAuthorityClient` 内复用同一个 decode/query/authority mapping 核心：一份实际已验 service JWT + 一次对应的 signed authorize decision，生成一个映射结果。`authorizeAdmission` 返回完整结果；旧 `authorize` 只取其 authority view。不能先调用 authorize，再独立 decode 未验 token 拼 proof；不能两次 current 查询混用版本。

`InitialCeilingProof` 最少只读字段：`initialCeiling`（完整 roles/attributes 规范结构；材料 dataLabels 沿已有 MaterialScope 类型另行保留）、`ticketJti`、`decisionId`、`subject`、`grantRef`、`grantExpiresAt`、`operationId`、`rootResource`、`sourceKind`、`authorizationVersion`、`ceilingSha256`、`originAuthenticationId`、`checkedAt`、`expiresAt`、本次 `mappingId`。已聚焦核实冻结 C 的 OperationBinding 只有 operationId/sourceKind/originKind/issuedAt/expiresAt/grantRef/subject/resource/ceilingSha256，没有 originAuthenticationId。故建议只在 adapter signed decision/service ticket 增加 `origin_authentication_id` 并绑定原 Grant 来源，经同一次映射保留；不改 OperationBinding 公开形状，不拿 decisionId 冒充。生产 IAM 对该来源字段的持久真实性仍需签收。

映射器必须检查 service JWT 与对应 current decision 的完整 `initial_ceiling` 规范值/hash、grant、subject、operation、call、authorizationVersion 一致，current actor 被初始 ceiling 覆盖；当前策略仅收窄 actor，不改历史 initial。service JWT 的 `ticket_jti` 必须等于 decision 所绑定的 ticket，decision 必须回绑本次请求 nonce/digest。proof 和 authority 同一 mappingId/同一映射对象来源；不是“任意两个签名字段恰好相等”。

接收 guard → controller/application 的内部方法显式传 `VerifiedAdmission`。`AdmissionContext.requireFor(authority)` 如需兼容既有签名，必须绑定本请求验证产物并精确核同一 mapping，不接受任意公开 record 作为证明；异步 worker 显式捕获不可变产物，不能依赖 ThreadLocal 传播。无 proof 的普通 VerifiedAuthority 能作现有 current 检查，但不得创建要求 initial ceiling 的 run/material/fact。

业务受理事务写 Task5 `run_authority` 与业务 run 同事务，额外保留 exact root 三字段、受理 operation id/source/截止及必要审计关联；不要仅存不可恢复的 resource_digest。后续新 DELEGATED operation 是新动作上下文，不能覆盖原 PORTAL observer 的 operation/deadline。skill/model 各域受理同样持久真实 Grant/初始 ceiling/根和本域 material scope，不能仅复制 gateway header。

材料要求来自完整原始 ceiling 与全部父材料要求/labels 的并集。原 A/B 当前 A 时保留 A/B，读取旧 B 材料必须拒绝；payload/scope 事务故障整体回滚。没有授权 row 的旧数据不自动补“当前有效”。后台从持久 Grant/初始 ceiling开始，resume+authorize 得到新 DELEGATED，再与持久 ceiling/root 精确核对；`guard_ctx` 只审计。

## 5. D3b：保持 exact root，通过固定 metadata 证明 child

推荐增加一个窄 `RootRelationClient.require(current, ChildSelector)` 与各域 `RootRelationController`。它是 D3/E 内部用途，不改变 C 的任意子资源能力。它走**普通 exact-root delegated ServiceCall**，每条 caller→producer→relation action 精确登记，在线 current；不复用首入口 metadata admission，也不借机器 bootstrap。

最少三种投影：

| producer | selector | PG 权威关系与输出 |
| --- | --- | --- |
| app | `app.dependency.v1 {dependency_kind,dependency_id}` | 主 root 的冻结 app version/candidate 真实依赖；返回实际 skill/model/prompt version/digest 与冻结 dependency generation；用于 run 尚未创建阶段 |
| engine | `run.tool-call.v1 {call_id,attempt_id}` | run_authority→run→node→tool_call/attempt 同 tenant join；返回冻结 skill version/contract digest、args digest、状态/代次 |
| engine | `run.model-material.v1 {invocation_id,material_id}` | run_authority→run→node→invocation→发送材料；返回 model binding/digest、请求 digest、round/kind/outputAttempt、状态/代次 |

有需要时在 monitor 加 report→case→candidate/dataset、在 skill 加 fact/artifact lineage，但只扩精确 selector，不引入通用 parentIds。各投影统一回绑：`producer, nonce, request_sha256, subject, grant_ref, operation_id, root_resource, ceiling_sha256, operation_expires_at, child_kind/id/version_digest, relation_generation, observed_at` 与该种专用字段。Grant 引用不输出到用户/日志；只在受限服务通信中使用。

`run_authority` 保存原受理 operation 供来源审计，投影的 `operation_id/operation_expires_at` 则绑定**本次**在线核实的 operation：原执行内各 hop 精确相等；合法后台 resume 的新 DELEGATED 与原受理 operation 不会相等，必须凭同 Grant/subject/root/完整初始 ceiling 证明可恢复，再生成只属于新 operation 的投影。不能把两者混用而拒绝所有合法恢复，或把旧 operation 的期限带进新证据。显式观察请求的新 Grant 只能走观察关系分支及实际 run/session 的 scope，不能借这个分支附着原执行链。跨域 statistics 也需 query snapshot→真实候选 run/material 的固定投影，不能直接信任请求 runIds。

`RootRelationClient` 必须只连固定 producer 地址、验证服务传输身份、同一次 HTTP nonce 与 DTO；禁止 redirect、缓存跨 operation、接受用户转发 DTO。receiver 校验 caller/target/action/resource 及本域 PG 关系，并在线核 current；relation DTO 本身不包含 payload、credential 或 response manifest。

目标 skill/model 接收器先核主 ticket/root，再经固定 producer 查真实关系，然后核本域 child metadata/version/state/current guard/material scope，之后才读取合同/配置/输入材料，最后在真实外发钩子重核 current+冻结发送材料+代次。不能先取完整 skill contract 再核 call。服务票的 caller/jti/aud 每跳改变，subject/grant/root/op/初始 ceiling/deadline 全部保持。

关系不可变部分（父 run、冻结 child version、请求摘要）由受理事务固定；可变的 claim/状态/代次必须用已有 engine 原子 claim/expectedAttempt/lease 协议复核，不能把早先关系快照当当前可执行证明。model 需要相应最小 dispatch claim/CAS，消费的正是持久冻结的 material id+digest；此 claim 仍在原根/operation/current 下。调用远端和事务之间存在撤权竞态，沿 Task5 在短事务及实际首发点复核，不承诺跨 IAM/PG/外部 provider 的原子取消。

### measure/tokenizer 的必要窄扩展

每次实际发送的 projection/summary measure 在 engine 发送前建立 `model_request_material` side row：真实 run/node/invocation 关联、`purpose=MAIN_MEASURE|SUMMARY_MEASURE|CHAT`、单调 generation、实际序列化 request digest、完整 material scope、binding id/digest。summary measure 早于 summary invocation 时，先建立与 run/node/原 source invocation 关联的不可变 measurement 行；不能引用尚不存在的 final invocation。后续 summary invocation 显式引用该行。

material id 来自持久记录，不能只信 body 中的 hash。model 使用上述关系投影，核实际收到的请求与冻结摘要；tokenizer 构造附加包装时另记录实际 provider wire digest，保持与已授权 source material 的可核变换关系。`countTokens` 的实际 HTTP 也走首发 current/scope/目的地校验，拒绝自动 redirect。不是删除 measure 正例，也不是把 final chat hash套给所有 measure。

## 6. streams 与事实的边界

本提案只补首入口/映射/关系，Task4 R1/RR1 与 Task5 R2/RR2 原要求全部保留：

- RunController 的 `nextEventRef` 只读窄 metadata，E 判权后在同快照读取 payload；不能预缓存第二事件。model、RuntimeClients、gateway 同一原响应关联的 lease 落到真实 reader/subscription/writer，idle watchdog/final/error/timeout/cancel 关闭可观察。
- provider accepted 只能由真实受理响应事件确认，不能在序列化/beforeSend 直接认定。取消本地资源不等于外部副作用已停止。
- 私有 manifest 来自原受理连接，真实 producer/run/invocation/attempt/round/outputAttempt/资源/请求及 payload bytes digest 全部绑定。关系 metadata 不能替代 manifest。失效 drain 不得新开关系/manifest HTTP，不新建模型/技能动作，不执行 toolCalls。
- PORTAL 失效后下一 payload 读取/输出为零。INTEGRATION 只按 C 原响应 permit 完成已在途响应，hardDeadline/原连接/材料 scope 继续核验。readForResponse 产物不能投入新 assembler。
- 后台新 DELEGATED 保持原根/Grant/完整初始上限，原 PORTAL 观察仍按原期限关闭。fact-read/fact-deliver 初版按 Task5 当前主体权限闭锁；固定机器生产者证明没有撤权后材料读取权。callback 原签名/body/attempt 检查继续存在。
- approvedBy 必须是持久原批准者，按冻结 confirmRoles 与当前证据核验；保留 EDITOR 合法正例、原要求 APPROVER 而当前只有 EDITOR 的负例，无 ADMIN 自动替代。

## 7. 分段实施范围与依赖（均待 root 定稿后实施）

下述新文件名是建议的确切落点，不表示已存在。共同 Java 根为 `admin-common/src/main/java/cn/agentplatform/common/authority/`；各服务 Java 根为 `<module>/src/main/java/cn/agentplatform/<gateway|app|engine|skill|model|monitor>/`。

| 切片 | 最小文件范围 | 依赖与交付界限 |
| --- | --- | --- |
| D3a 配置/首入口 | common 新 `MetadataAdmissionClient.java`, `HttpMetadataAdmissionClient.java`, `MetadataDecisionClient.java`, `MetadataIntent.java`, `MetadataSelector.java`, `MetadataAdmission.java`, `VerifiedMetadataAccess.java`；改 `AuthorityProperties.java`, `SecurityConfiguration.java`, 路由/coverage 配置；gateway 改 `api/GatewayController.java`, `application/IntegrationAccess.java`, `domain/Routes.java`，新增 `application/IngressResourceResolver.java`；六服务 application.yml 显式 identity/独立 profile | 先接 D1、D2 冻结终稿；现两条 credential.read/origin.check 不扩权。新增 metadata domain chain 默认关闭，配置缺项启动闭锁。首个 app/run metadata receiver 可随 E1 交付，只有 fixture 正例不能标整个 D3 完成 |
| E1 受理/metadata | common 新 `AdmissionAuthorityClient.java`, `VerifiedAdmission.java`, `InitialCeilingProof.java`，改 `HttpCurrentAuthorityClient.java`, `ServiceAuthorization.java`, `AuthorityContext.java` 的映射/传递；app 新 `api/AuthorityMetadataController.java`, `application/AuthorityMetadataService.java`；engine 同名 controller/service；engine Task5 `application/RunAuthority.java`, `infra/RunAuthorityStore.java`，改 `RunService.java`, `TraceStore.java` | app 实际 draft/version/visibility metadata 待 D2 冻结后一次聚焦补核。先闭合真实 metadata→主 exchange→run/ceiling 同事务链。迁移号按实际连续版本另核，不预占、不碰已执行 V 文件 |
| D3b 跨 hop/首发 | common 新 `RootRelationClient.java`, `ChildSelector.java`（严格联合 DTO）；app/engine 新 `api/RootRelationController.java` 及 `application/RootRelationService.java`；engine 改 `RuntimeClients.java`, `TestInputClient.java`, `ToolCalls.java`, `ModelExchange.java`, `AgentDriver.java`, `FlowDriver.java`；skill 改 `SkillClients.java`, `SkillExecution.java`, `SkillAdapters.java`；model 改 `ModelController.java`, `ModelCalls.java`, `ModelStore.java`, `FrozenRequestConnector.java`；monitor 改 `EvaluationService.java`, `StatisticsService.java` | 依赖 E1 真实根受理行及 frozen child/material metadata，不能以 body parent 自洽交付。全普通客户端与非 RestClient 出站都迁移，未知 target 拒绝、redirect 关闭 |
| E 完整材料/恢复 + D/E 流 | 原 Task5 全部 Files 保留：各域 MaterialScopeStore/Controller、FactAuthority、各材料/历史/回放/下载/恢复/审批消费点与 side tables；gateway/engine/model stream 原 Task4 文件；docs、query snapshot/creation intent 在各实际所属域的 metadata service/store 和下一连续迁移实现 | 本报告不以“最小切片”删除长尾。D4 portal-auth/session/provider、原 SDK ticket 协议和全部 route runtime coverage 仍按原 Task4；Task6 真实有限链/统一回归另行签收 |

新增测试仅在对应服务 `src/test` 中：`MetadataAdmissionHttpTest`、`InitialCeilingAdmissionTest`、`RootRelationHttpTest`、`ModelMeasureAuthorityHttpTest`，可并入原 ServiceAuthorityHttpTest 等减少重复。复用有限 LoopbackAuthority fixture，扩新 profile 的原子消费/策略变更；不放生产源。真实 PG 与 HTTP fixtures 按 root 指定专用 runner 运行；本报告没有启动它们。

不创建第二套 driver、生产 IAM server、通用资源签名服务或任意 metadata proxy。内部 query/intent/material side tables 如需多域落地，按域所有权新建；跨服务 HTTP，禁止业务 jar 互引。

## 8. 有限正负矩阵与 root 裁决项

| 有限正例 | 对照反例 | 必须观测的边界 |
| --- | --- | --- |
| 同原 portal/integration 从 appCode 解析真实 published version，主 exchange 后创建 run | 错原 token/认证实例、策略变更、metadata 欺骗 producer、nonce/replay、receipt 改资源/动作、普通票打 metadata | 失败前 metadata/业务 payload/provider 计数分层断言；主 payload=0；没有第二个 Grant |
| runId 仅 owner metadata→观察主票 | 同 tenant 别 subject、integration app 不匹配、status 旧路径先读完整行 | 无权限时 payload SQL/输出=0；不能用观察票继续模型 |
| 真实 immutable 根贯穿 run→call/model material | 同租户别 run/call、伪 parent、错 attempt/round/outputAttempt/version/digest/producer、关系旧代次 | 读合同/配置/材料或首发分别计数；正确链 jti/aud 不同但 root/op/deadline 不变 |
| projection 与 SUMMARY measure 真正 tokenizer 发送 1 次 | 只有 chat permit、未冻结 measure、错误 source/final 摘要、首发前撤权/503 | tokenizer/chat 请求均=0；不删 measure 功能 |
| A/B 完整 ceiling受理，当前 A 仍持久 A/B | 缺 proof、错 ticket/grant/mapping/hash、用 guard_ctx/current actor 回填 | 拒绝受理；payload/scope 故障两者同事务回滚 |
| 列表 snapshot、一次 create intent、docs 制品、合法聚合各一个 | snapshot 主体/页界/候选 revision 变更；intent 重用/越 namespace；docs digest错；聚合混入越 scope 项 | 对应业务正例可用；负例先判权后 payload，不能用全拒绝冒充覆盖 |
| 固定健康探测；有 current 的真实既有 fact 投递一次 | health 带用户内容、fact 尝试 execute、事实 scope 撤权、callback 错签名/attempt | adapter/业务发送=0；原事实保留，不篡改成功 |
| 原 Task4 R1/RR1、Task5 RR2 的真实双 chunk+final/PG恢复 | PORTAL过期、错manifest/connection、跨连接重放、drain开新HTTP、旧 Grant/原批准者权限失效 | 实际 reader/subscription/writer关闭；PORTAL下一payload/输出0；合法INTEGRATION completion/新DELEGATED恢复保留正例 |

需要 root 明确裁决，才能转实现：

1. 选择方案 1 及 authority-brokered metadata profile；是否接受固定域认证传输作为投影来源，不要求业务域另签新资源 JWT。真实 IAM 必须支持/签收，fixture 不替代。
2. 首个 selector/action 列表、15s/30s 建议时限、集成原认证/策略字段与 D2 终稿的精确对应；profile 关闭时全链默认拒绝。
3. 采用同一次 `authorizeAdmission` 映射产物保存完整 ceiling；冻结 C OperationBinding 未提供 origin_authentication_id，接受只扩 adapter 签名字段/同映射证明并审查真实 Grant 来源。
4. list/aggregate 采用真实 query snapshot、create 采用 creation intent；如改 typed queryscope 必须另给精确许可语义，不能退回常量 version。
5. 固定 root relation 作为 target 域 guard 的强制附加检查；新增精确 relation routes 与 model measure/dispatch metadata。C 不支持任意 child 的限制保持。

以上裁决不等于 D3/E 通过。D1/D2 的组件/HTTP通过不替代六服务真实映射、Task4全部正负矩阵、Task5全部材料/恢复/批准者验收以及独立审查。文中尚未核实的 app metadata/字段与全部实际 handler必须在冻结终稿后补核；真实 IAM/K8s 未签收仍记录阻断，不要求用户提供环境后才继续已授权隔离验证。

## 9. root 要求的最小性挑战：仅 appCode→published metadata→真实根（追加）

本节保留上文原提案不改，重新评估 root 提出的方案 2。只讨论首次 published app 纵向入口，不扩展列表、创建、docs，不改变 E/跨 hop/streams 的待办，也不表示 root 已批准。追加时仅聚焦读取已归还的 `app-management/src/main/java/cn/agentplatform/app/application/DraftMetadataService.java`，未读 D2 全差异，未改源或运行测试。

### 9.1 结论：这个有限场景可省去 receipt 消费 profile

**接受 root 的最小性挑战：若主 Grant 与真实域校验按以下规则完全独立成立，receipt 并非必要的安全条件；建议 root 优先裁决此窄方案 2。** 上文“主 exchange 必须消费受理证明”是对方案 1 自身信任链的要求，不应推广成所有 metadata-first 实现都必须增加 receipt 的硬约束。

理由不是“两次调用字段相等”，而是第二次调用不把第一次成功当授权依据：

1. 第一个 Grant 只授予查询指定 app published 元数据的权限；该查询本身也经过实际 current 与域归属/visibility 检查。
2. 第二个 Grant 是基于原已验外部 sourceToken 的显式新 exchange，完整执行 C 的原认证/current/主体/策略检查，从创建起就绑定真实 app-version。它不接受 metadata Grant 作为身份或权限来源。
3. app 在主动作读取 snapshot 前，仍根据已验主体、主票 resource、实际 appCode/version 元数据判断归属/visibility/版本；engine 也不能仅比较 gateway 回传字段。真实根必须通过 app 主 receiver 的窄 metadata 检查后才读取 snapshot。
4. 因而，即便 gateway 给第二次 exchange 传错“解析结果”，结果只能是：主域因真实资源/权限不符拒绝；或者主域对该实际资源本来就具有独立充分授权。后者不因缺少 metadata receipt 变成越权。若主域会在不符时放行，漏洞是主域实际资源校验缺失，补 receipt 也不能替代这个边界。

两个 Grant **明确不同**，不是保持同 Grant；它们也有各自 operation/寿命。不存在从 metadata Grant 换根、升级、续接主执行的操作。不持久 metadata Grant 为 run 授权来源，不用它的初始 ceiling 代替主 Grant 的初始 ceiling。E 的完整 initial ceiling 仍取主动作实际验签映射。

receipt 只有在 authority 必须依据一个已解析业务事实作额外主授权决策，而主域不再独立检查该事实，或产品明确要求“只有消费该次查询结果才能执行”时才承担新增安全语义。此首入口没有这个已确认需求，不能为了形成完整审计链自行增加一次消费协议。两次调用可用本机 trace/requestId关联审计，该关联不参与授权。

### 9.2 typed query 与 C concrete versioned resource 的界限

C 构造器要求非空三字段，exact Grant/forward/resume 比较本身不禁止新的资源种类。其“具体版本化资源”注释要求资源有明确对象与版本语义；这里可以把**具体受限查询描述对象**定义为资源，而不是把“未知 app 版本”伪装成资源。

建议只新增一个精确资源种类和一个精确 action：

```text
kind   = metadata-selector-v1
id     = canonical identity of (verified tenant, app code, selector=PUBLISHED)
digest = SHA256(canonical TypedPublishedSelectorV1)
action = app.published.metadata.read
target = app
```

上述 action 名是拟定的目录条目，实施时与冻结目录统一，不创建同义别名绕过规则。`id` 使用确定性、无歧义编码，不能用简单未转义的字符串拼接；canonical tuple 编码可复用现有规范工具。tenant 必须来自已验主体，不能来自客户端 body。描述对象示例：

```json
{
  "schema": "app-published-selector.v1",
  "tenant_id": "<已验主体 tenant>",
  "app_code": "<规范化且校验通过的具体 code>",
  "selector": "PUBLISHED",
  "projection": "app-published-metadata.v1"
}
```

`schema/projection` 的 v1 是查询/输出语义版本，不是捏造集合 revision=1。descriptor digest 不随 published 指针变化，因为它绑定“查询哪个 app 的当前 published 元数据”的固定许可语义；**它从不证明查询结果的新鲜性、app 内容版本或执行权限**。真正版本仍是域返回的版本行 id/摘要，由主域再次核对。

因此，这不是违反 C exact binding 的技术冲突，但确实是需要 root/IAM 能力目录明确接受的新资源语义。若 root 将 concrete resource 限定为“只能是业务实体持久版本行，禁止查询描述对象”，方案 2 就不满足这项额外限定；单凭当前三字段结构/注释不能推出该禁止。不得悄悄把资源类型扩展藏在普通 hash 中。

该 kind 必须有穷绑定到 metadata action/receiver。普通 payload action 在 action-resource kind 目录和实际域资源检查均拒绝它；不得有“任意非空 kind 都可以执行”的通路。即使 authority fixture 故意发出 metadata-kind 的主动作票，真实 payload receiver 仍应在读取前拒绝。

### 9.3 最小接缝：复用 C exchange/serviceSecurity/current

不增加 metadataJWT、独立 SecurityFilterChain、receipt、消费状态或 authority 递归调用；需要的变化限于普通精确能力/路由条目与域 metadata 实现：

1. gateway 本次入口保留原已验 `sourceToken` 于内存，构造 descriptor 和 query ResourceBinding，调用：

   ```java
   ticketsFromAuthority.exchange(sourceToken,
       new ServiceCall("app", "app.published.metadata.read", selectorResource));
   ```

   `ticketsFromAuthority` 指现有 CurrentAuthorityClient；不是新增 API。原 portal/integration token 不发送给 app，app 只收 C service ticket。

2. 通过固定受信 app HTTPS 地址调用拟新增普通内部端点：

   ```text
   GET /internal/apps/{code}/published-metadata
   ```

   无请求 body，无 tenant/subject/version header。app 普通 serviceSecurity/全局 guard 核精确 caller/target/action/method/template 并在线 authorize。app 用已验 subject tenant 和已校验 path code **重新构造** selectorResource，再 `requireResource`；不能仅把票里的 resource 传回去自比。随后按真实 tenant/visibility/应用状态及 integration app 限制查询窄列。

3. 最少返回以下业务元数据，不返权限证明：

   ```json
   {
     "appCode": "demo",
     "versionId": "<真实 immutable app version id>",
     "versionNo": 7,
     "versionSha256": "<持久版本真实摘要>"
   }
   ```

   如主业务 root 的实际 id 编码不同，统一由域 metadata 输出规范 root 或用既定规则构造；不能混淆 versionNo 与版本行 id。响应必须来自固定 app 传输身份及本次 HTTP 请求，禁止 redirect、任意 URL、用户上传同形 metadata、跨 source/tenant 缓存。

4. gateway 对同一原 `sourceToken` 显式调用现有 C `exchange`，mainCall 的 resource 从开始即为实际 app-version，action 是现有精确主动作。metadata Grant/decision 不作为 exchange 输入。接收后的主调用才沿 C forward 保持该新主 Grant/root/operation。

5. engine→app snapshot 读取必须使用确定的实际版本与同主 root；app 在读取 snapshot 前将 path appCode、真实版本行、tenant/visibility、摘要与主票核对。不能用刚才返回的 digest与票自比代替 PG，也不能随后悄悄调用当前 published 并换版。main root 与 query selector 的类型分支不可混用。

只有 metadata 出站客户端、query descriptor 的确定性构造/校验、一个 app metadata controller/service 窄方法、能力/route coverage 条目是这个切片新增的核心代码。descriptor 可作为局部小 record/工厂，无需把上文十多个 profile 类型全部落地。

新的 metadata action 仍必须得到明确的原身份策略许可，不能因为能 exchange 就默认允许。对 integration，可将其限定为原 credential app 的 published 元数据，且只有已允许该 app run 入口的 source profile 可用；该约束必须由 authority policy与域实际检查落实，不能仅在 gateway 条件判断。真实 IAM 不认识这一精确 action/kind 时仍闭锁；可扩有限 fixture 验证，但不能把本方案说成零 IAM 配置变化。

### 9.4 原认证、策略变化和 TOCTOU

- 两次 exchange 各自按照 C 核原认证/current。source 到期/吊销、integration 旧 policy revision/digest 锚点失效必须按现有 profile 拒绝；metadata 查询不刷新 source，不交换成服务身份再建主 Grant。
- 门户当前权限变化是否直接使原 token 失效，服从已有 portal profile，不能新假定“任何策略变化都会使 token 无效”。若 C 允许同原认证在收窄后的 current 下继续，主 Grant 也只能使用该收窄权限，实际域/材料要求仍须被覆盖。receipt 不是强行要求两次策略版本相等的理由。
- main operation 在第二次 exchange 创建，寿命仍不超原 source/Grant 的 C 界限；它不是 metadata operation 的续期。不能在一个已开始的主 operation 中反复重新 exchange 来重置该主 operation；此方案只用于新外部请求的受理阶段。
- published 指针在两步间变化：若主请求语义允许固定这次已解析的 immutable 版本，则只执行该版本；若语义要求受理瞬间仍为 published，则 app 主受理再次核指针，变化即冲突重来。两种都不能静默切换，也都不需要一次消费 receipt 来完成真实检查。
- metadata 本身可能泄露存在性/版本信息，所以第一个 Grant 的 current、归属/visibility、精确投影和速率/长度界限不能省。TLS可信仅证明响应来源，不代替查询授权。

### 9.5 一个已核实际风险与有限正负验证

聚焦文件 `DraftMetadataService.get` 的实际 SQL 直接读取 `d.config::text,d.definition::text`，并将两者解析返回；名称含 Metadata 不意味着 metadata-only。它还保留 SERVICE/EDITOR 检查，这不能代替本方案的普通 C current + selector + 真实 visibility。**不能直接复用这个方法作首入口查询。** 本节不据该文件推断 published SQL、真实摘要列或其它 D2文件已满足要求；published 窄 SQL 的具体列和 visibility谓词仍由正式实施时聚焦核实。

有限验证建议（均未执行）：

| 正例 | 反例/变化 | 断言 |
| --- | --- | --- |
| 有权限原 source：query exchange→app 窄 SQL→main exchange→app 真版本 snapshot→run | 查询票的 caller/target/action/method/tenant/selector 任一错；跨 app integration | metadata handler/query按既定门槛为0；业务 payload/provider=0 |
| 两个 Grant 明确不同、主根真实，主后各 hop 保持一致 | query Grant forward/resume 换实际 app-version；query-kind票访问 snapshot/run执行 | C exact binding或实际域kind/metadata检查拒绝，payload/provider=0 |
| 正确 metadata 选择器读4个字段 | 将现 DraftMetadataService 当解析器；返回config/definition/secretRef；先取snapshot再裁字段 | SQL/DTO边界测试发现违规；不能只断言最终JSON不含敏感字段 |
| 主域对自己真实 PG版本独立核验 | 网关被测试夹具喂入同tenant别app version、假摘要、非归属版本，随后仍以原source申请主票 | 即便fixture故意签了主票，实际域payload读取=0；不得只测authority拒绝 |
| 两次exchange使用同原source，当前许可足够 | 第一次后 source 到期/吊销、integration policy revision变更、portal权限收窄至不覆盖主动作 | 第二次或实际域当前检查拒绝，payload/provider=0；不重认证/续期 |
| 指针稳定或明确固定已解析immutable版本 | 中途改published指针 | 按选定语义固定原真实版本或冲突；不读另一个版本payload |
| 第一次lookup成功后正常执行 | 跳过lookup但直接持有真实版本并获主授权，或lookup结果重复使用 | 不能把“未查过/查过两次”本身当安全拒绝依据；主身份/current/真实版本检查仍独立成立，Grant不可借query续期 |

最后一项用于验证最小性结论：如果一个请求已经知道真实版本，原设计的主授权理应由当前权限与真实资源决定。为强制它“先消费某次查询”而新增 receipt，会引入本场景未要求的前置条件。本节不取消业务 requestId 幂等，也不主张重复创建 run；run 的已有业务幂等按原语义保持。

**追加建议：root 可选方案 2 作为首个 published app 纵向切片，明确批准查询资源语义与精确 action 后进入实施；上文方案 1 留作备选，不因其较完整而优先落地。** E 同映射完整初始 ceiling、真实 child relation、measure/stream首发、Task4/5全验收与真实 IAM 签收仍全部保留。
