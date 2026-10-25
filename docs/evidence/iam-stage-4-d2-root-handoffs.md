# D2 内部接缝准备裁决（尚未实施）

本文件解释后续软件实现的最小兼容接缝。D1现已独立双Approved，root于2026-10-20正式移交D2时本文件转为约束；准备阶段没有运行D2数据库/测试。原设计、契约、IAM协议及已冻结历史不改。

1. **共用实际集成规则。** D2正式授权后，允许在common新增最小纯 `IntegrationClaims`（名称可在交接时按实际已有类调整），仅集中现有principal映射、policy digest和平台角色判定；gateway现有IntegrationPolicy用它保持原行为，app resolver用同一规则。无HTTP/持久化/可插拔策略框架，不复制整个gateway业务类，不引入gateway JAR。保留外部ApiKey/短票据格式、长ID hash与canonical摘要兼容；用既有gateway策略测试及新增实际resolver对照证明。
2. **独立policy验签复用。** 允许最窄common `PolicyRequestVerifier`，在既有严格 pinned RS256/JSON/key解析上提供仅 `agent-policy-request+jwt` / `policy-query` 的入口，固定policy caller/target/action、30秒/零skew和实际body摘要/nonce绑定，不能返回Actor/普通业务能力或调用authority。最多两固定public kid/RSA keys；缺/私钥/未知kid/跨用途默认拒绝。app自己的SecurityFilterChain负责401/403语义；本地请求验签失败不能借远端503语义掩盖为依赖成功或fallback。若作者提出同样窄而更简单的复用方式，先报精确形态供root核对，不复制通用验签逻辑。
3. **测试fixture可达性。** common仅测试源码允许必要的public fixture入口/有限external-policy配置和test-jar。现本地maven-jar-plugin3.4.2已缓存；可在process-test-classes阶段生成test-jar供同reactor app测试依赖，禁止把测试authority放进main或依赖任何业务服务JAR。优先最小公开现有fixture所需方法/只读数据，不为每个字段加一层泛化facade。保留原默认C有限协议行为；真实新联测显式切到实际app resolver URL、测试policy独立issuer/key、真实credential/subject/principal/digest/revision/上限。初次版本来自这次真实PG结果，后续expectedPolicyVersion来自已存Grant；每次决定至多一查询，不可查询两遍来拼上限。不可把旧固定alice/7/policy-v1当实际app映射。
4. **测试凭据与runner。** 用户已授权本新工程真实本地PG和隔离端点持续验证。本轮仅允许专用launcher将本新工程已配置的 `AGENT_DB_PASSWORD` 注入测试子进程；已有环境有值则直接使用，否则程序只提取本新工程忽略的 `.env` 中这个命名设置，绝不把文件内容/值输出给模型、日志或报告。它不读取或转发IAM真实秘密、旧vault/master key；JWT和master key为该子进程临时生成，secret目录全新隔离。此为已有新工程测试配置的受控使用，不是读取生产IAM材料。准备脚本 `.tools/run-iam-module-tests.mjs` 已保存但尚未执行；模块/测试/日志阶段参数采用封闭语法，命令不拼接密钥、不覆盖旧日志。
5. **测试覆盖边界。** D1后原六服务配置仍未迁移，不能以全局system property强改所有common配置用例来让app旧SpringBootTest启动。D2新有限app配置显式装配真实controller/service/PG与独立policy chain；必要开发ADMIN链显式test配置，不能误签可信IAM入站。聚焦D2覆盖后报告真实已跑和未跑的旧服务测试；原Task4全体回归/真实gateway→downstream在D3配置迁移后必须补齐。旧测试缺模式/服务ID不能跳过后冒称全app绿。
6. **POLICY_MACHINE与全局守卫。** root已核D1候选ServiceAuthorization.preHandle除明确health外均要求service JWT；Ingress目前只参与覆盖检查。D2独立policy chain还必须在同一实际handler mapping上与该全局guard形成明确的信任域交接，不能仅新增Ingress就宣称可用。root已将此具名事实发给D1 reviewer独立判断本轮范围；D2正式交接前根据该结论给出最窄的已验policy请求接缝，必须证明普通service/admin/portal票据及HTTP自报标志不能借此绕过。不得通过漏装全局guard、分离成伪生产替身或大范围exclude来“修好”正例。当前尚未选择新公共接口。

实际D1接缝、最终写入文件范围、PG测试数据库名及命名测试命令会在正式D2交接补齐。此稿不替代开工前重核迁移号、D1复审或作者具体疑点确认。

## 正式 D2 混合信任域接缝裁决

D1独立审查X4确认：仅新增policy chain与Ingress不够。root批准在真实D2接入时最小扩展common，不解冻或改写D1历史：

- `PolicyRequestVerifier`提供专门的已验policy请求产物，使用内部/私有构造的嵌套proof即可，不新增通用权限框架。proof只能由本次独立固定公钥/issuer、精确typ/use/caller/target/action、期限、原body摘要/nonce验证成功产生，并绑定本次真实HTTP请求；public DTO或普通当前证明不能充作它。只在request attribute/明确对象传递，不写Actor/业务JSON/全局授权缓存；复制到另一请求、过期、body不符均拒绝。
- `ServiceAuthorization`可保留原构造并增加绑定具体policy Handler Method的最窄装配参数/工厂。全局preHandle仅对该Method和固定POST/path，在核实上述实际请求proof后交给独立POLICY_MACHINE域；不是只按路径、HTTP自报header/flag、已认证任意主体或Ingress登记跳过。其它普通/disabled service handler仍经原守卫，MappedInterceptor仍全路径无exclude。
- app专用FilterChain先验证真实assertion与受限body并产生proof，controller取相同已验DTO；runtime coverage登记同一实际Method。不得把隔离到另一个假server当混合装配正例；至少在**同一实际app servlet context / handler mapping / SecurityFilterChain集合**，合法policy查询1、普通合法服务能力1，二者错域/直达绕过均0。新注册缺proof/错Method/缺验证器的启动或接收负例明确可观察。
- 这只是首版policy域专用接缝；不预先实现gateway通配代理/门户/集成的generic realm注册器。D3另按真实入口实现。若作者能提出同样窄且更简单的形态，先向root说明具体接口，不能无审查改成可任意permitAll的回调/开关。

作者须先把实际接口/装配方案短报root确认，可并行做baseline、DTO/SQL及测试准备；禁止在架构未澄清时靠漏装守卫或全局属性绕过。root在当前任务内答复，不请求用户重复授权。

## root已确认的实际方案与资源语义

作者提出的 `verify(HttpServletRequest, byte[])` 产物方案已确认：Proof私构造，保留原请求、body clone/digest、exp及verifier实例；交接时再次核同一请求/时间/body。ServiceAuthorization新增最窄5/6参构造（healthMethod、policyMethod、verifier），其它路径保持原全局守卫。配置独立prefix `agent.policy-resolver`；enabled=false时不注册controller/chain/Ingress，enabled=true缺公钥或错误公钥必须启动失败。这个精确端点配置不替代生产IAM readiness，D3必须明确检查所需能力。

三处真实ADMIN mutation的资源绑定：

- **issue**：metadata-only查询真实 `app.application.current_version_no` 及该 `app.app_version.candidate_sha256`；原事务锁application行或等价CAS稳定指针。`ResourceBinding("app-version", code + "@" + versionNo, storedPublishedCandidateSha)`。未发布保留原409；先核实际发布资源，再进行原凭据写入。不要先读snapshot/config/definition，不能用 `AppService.get().candidateSha256` 替代：root核实它在AppService:96是读取draft后计算的候选摘要，不是存储的当前发布SHA。
- **policy/revoke**：按已验tenant+app+biz查询真实credential id、原revision和授权策略metadata并锁行。`ResourceBinding("integration-credential", Long.toString(id), Digest.sha256(Json.canonical(Map.of("credentialId", id, "policyVersion", id+":"+revision, "policyDigest", existingCompatiblePolicyDigest))))`。这里credentialId为真实数值、policyVersion为真实PG版本；digest保持旧secretRef/claimLimits/allowedOrigins规范，不另造外部出票摘要。所需secret_ref与策略字段仅作授权metadata，不读取SecretVault实际密钥。先requireResource再返回完整get/写入；错id/version要求0写入。

本次授权mutation产生的新revision结果属于该次已授权变更的返回，不把持有旧资源的下一独立请求自动提升到新版本。D3实际gateway入口如何取得可信具体版本及后续父子资源链仍待单独实现，这里的测试harness已知PG metadata不代替该业务路径。

## 相同策略更新后的原认证版本锚点

root已确认：真实ADMIN即使提交相同policy而digest保持，PG revision仍递增，原Grant的exchange/resume/authorize必须失效；不能通过同一原认证token再次exchange便取得新版本。有限external-policy authority fixture可用已验原subject token的hash在既存Grant中找到原policyVersion，并同时核原issuer/jti/subject。每次决定至多一次真实resolver查询；重复exchange保留原expectedVersion，失败不消耗新Grant额度。只有明确新jti/token的重新认证才能建立新初始版本。不得只信调用方自报字段、默默把同一认证当新initial version，也不改ServiceTokens旧外部票据/ApiKey格式。

至少覆盖真实ADMIN同policy更新（digest不变、revision+1）后原source token重复exchange、旧Grant resume和authorize全拒绝；fresh认证与新Grant正例成功。真实IAM profile还须持久保存原认证→初始policy版本锚点；有限fixture实现不计作生产IAM签收。此为原Task4 §2.6已有旧授权失效与显式重新认证要求的实现，不扩展原契约。

## 新独立入口对原静态目录测试的维护

完整common首轮150/1断言失败/0error/0skip的唯一失败为AuthorityPolicyTest旧源码统计假设：新增policy controller后实际159，而旧正则不解析常量路径。root批准最窄相关测试维护：controller精确固定literal POST路径与PolicyRequestVerifier.PATH直接相等；测试将真实159集合与原158普通service目录加唯一POLICY_MACHINE精确入口逐项对照，保留原158与gateway不授通配能力，并断言policy路径绝不作为ServiceRoutePolicy普通capability。不能仅改数字或移除旧断言，不泛化源码解析器。首轮原日志/XML保留，这不是HTTP授权行为红；修改后受影响测试和一轮最终完整common是新码必要验证。
