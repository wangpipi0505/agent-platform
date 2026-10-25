# D4a：门户会话的有限后端实现

状态：实施前要求。仅在 N4b2 关闭／异步安装修复完成独立复审并正式交还源码后派发；本文不宣称功能已完成。

## 1. 授权、依据和边界

用户已授权从前置任务持续开发；没有真实模型、本体、IAM、K8s 联调环境，使用隔离测试端点。唯一工程为 `G:/207/项目/算法重组工具/代码/agent-platform`，每个 shell 调用显式指定该 workdir。不得访问或复用相邻旧工程代码、配置、数据、依赖、缓存和进程。原 v1.2 及四个契约不动，不做时间安排、外部发布、打包部署或开启全局 IAM。

直接需求是 `docs/evidence/iam-service-authority-implementation-plan.md` §2.7 的 `GET /auth/session`，属于 Task 4 的门户前置，不代表整个 D/E/F/G 完成。先阅读本文件、AGENTS.md、docs/implementation-brief.md、contracts/README.md、该计划 §2.7 与 root decisions 的相关约束。按适用技能执行失败先行、源码对照、独立复审。没有 HEAD／提交基线，不创建提交或批量暂存。

Java21 / Spring Boot3.5.0 / Spring Security6.5 / Nimbus9.37.3；固定现有依赖，不改 POM。common＋六服务和 api/application/domain/infra 分层不变，跨服务 HTTP，不引入业务 jar。不新造 authority 服务、协议、认证框架或 session 存储；现有 LoopbackAuthority 只作隔离签名测试端点。

## 2. 一个有限交付、两个审查面

common 私验会话投影、gateway 的真实精确 handler 和安装覆盖作为一个交付闭环。禁止先只登记路由而遗漏 handler／coverage，也不能依靠全局 permitAll。生产 `IAM_TRUST_CHAIN_UNAVAILABLE` 保持；仅显式有限测试 assembly 安装会话入口。已有 published-run 的两条 ANY、真实安全链注册、私有 proof、独立 metadata/main、发送及写出前复核不被放宽。

本次不改前端、SSE、SQL、原设计、旧阶段报告、原 B2 证据、runner 或其他服务。前端门户 provider 与会话 generation 是下一阶段，不在本次制造替代页面或登录方式。

## 3. 固定会话能力和资源

- 入口精确 `GET /auth/session`，拒绝 query（含实际到达服务器的空 query）、请求业务 body、Cookie、ApiKey、重复 Authorization、身份／租户注入头以及其他凭据混入。仅真实门户 user JWT 且具 `platform.access` 可继续；integration/workload/service 凭据拒绝。本有限入口沿用已验收 B2 的严格 Cookie 拒绝边界；后续浏览器适配必须明确省略请求 Cookie，并用真实同源 ambient Cookie 场景验证，不能假设 XHR 的 withCredentials=false 会省略同源 Cookie。本次不修改全局 Cookie 政策，也不宣称浏览器已验证。
- catalog 只增加对应精确 GET 行：caller=`gateway`，target=`gateway`，action=`portal.session.read`，mode=`delegated`，integrationAllowed=false。不得增加 ANY、通配模板、泛化门户豁免或修改其他行。
- 资源全部在真实来源验签后内部派生，调用方不提供 resource、tenant、subject、target、action 或 Clock：

```text
kind = "portal-session"
id = AuthorityProtocol.digest([
  "portal-session.subject.v1", verifiedIss, tenantId, principalId
])
versionDigest = AuthorityProtocol.digest([
  "portal-session.authentication.v1", verifiedIss, verifiedClientId, verifiedJti,
  verifiedIatEpochSeconds, verifiedExpEpochSeconds
])
```

复用既有 canonical／digest；数组顺序固定，iat/exp 为真实已验整数 long 秒，不是毫秒、ISO 字符串或浮点数，不补缺值。沿用 TrustedJwtDecoder 对必填文本、整数日期、溢出、issuer/client/audience、用途与零钟漂的验证。资源摘要标识认证实例，不替代既有 nonce、请求摘要、ticket jti、operation、grant 和 origin 绑定。普通资源协议不因本接口而全局改变。

## 4. common 私验投影

在既有 CurrentAuthorityClient 增加固定 session 能力，输入仅原门户 token。默认实现明确 503 会话能力不可用；不可用默认实现、公开 VerifiedAuthority 或 `RetainedAuthorization.currentOnly` 不能产生成功投影。真实实现只放在 HttpCurrentAuthorityClient，限定 IAM gateway；不另造客户端，不改造 B1 run proof 为通用权限壳。

依次执行：

1. 真正验证来源门户 JWT、映射 issuer ceiling、核 platform.access，并从这一次已验来源生成固定资源。
2. 复用私有 exchange／ticket 验证路径获得固定 gateway self-call 的 delegated ticket；移交 authorize 前再次确认 exchange 决定仍有效。
3. 对同一 ticket 发出真实在线 authorize；必须存在 retained initial proof，source/origin 都是 PORTAL，并逐项一致绑定主体、resource、operation、grant、真实 grant expiry、initial ceiling、origin authentication ID。保留现有 nonce、签名请求摘要、caller、ticket jti、授权版本和 ceiling 收窄校验。
4. 仅由该真实私验路径构造 final 私有来源证明，无公开构造器、capture/from(VerifiedAuthority) 工厂、ThreadLocal 或外部 Clock。对象不保存原始门户 JWT，不把 ticket/grant/claims/attributes 暴露给响应。可公开只读的四字段 DTO，但 DTO 本身不能被当作授权证明。

角色为 mapped issuer ceiling 与当前允许角色交集。已授权 action 的角色交集可以为空，不额外要求 ADMIN/EDITOR。任何当前 evidence 扩张仍按既有 within 规则拒绝。

区分两个截止：

- UI 的 expiresAt 仅取真实 `min(portal JWT exp, grant exp)`，不把五秒 current 决定寿命冒充页面会话寿命。
- 成功 JSON 首次实际释放前的短 proof 同时检查来源、grant、ticket、operation、最新 authorize decision，绑定 owner 的验证 Clock。该输出 proof 的单调预算锚定最新 authorize 请求开始时的 Clock／nano 观测，由最新决定及各真实截止取界；不是从门户验签或旧 exchange 开始后再统一硬限五秒。owner close 立即使证明失效；失效后不可恢复。exchange 决定在交接 authorize 时必须有效，之后不能错误地要求旧 exchange 决定陪同整个响应。不得刷新、延长或复用旧成功许可。

实现前报告真实 JSON 释放位置、生命周期、取消及异常映射的最小方案。避免写一个 controller 检查后让其他异步写出绕过 proof；不引入通用响应框架。不承诺撤回已经提交给客户端的字节，也不扩大为任意慢客户端的物理强制断开保证。

状态按来源区别：签名／用途／日期无效，以及门户 JWT 在验签后、首次释放前到期，均为 401；真实身份当前拒绝，或 grant／ticket／operation／当前决定的授权截止，为 403；IAM 不可达、无法建立可信 authority 证据、能力不支持为 503。不能把所有上游验签错误归为用户 401，也不能成功返回旧角色。

## 5. gateway 安装和 fixture

新增 `PortalSessionController`，成功响应为既有 `ApiResponse<{tenantId,principalId,roles,expiresAt}>`，字段精确白名单，`Cache-Control: no-store`。tenant/principal 取已验来源与当前证据的一致绑定，禁止从请求身份参数取得。expiresAt 保持工程现有时间序列化约定，并在报告中写明，供后续前端消费。

在 N4b2 已验收的有限 GatewayIngressBoundary 上增加显式 endpoint purpose，绑定真实 session bean＋declared method；保留旧 run-only 构造语义或等效不可伪造的固定安装。两条 run ANY 的计数、所有 HandlerMappings（含 secondary）、同 bean／method、实际 outer FilterChainProxy 及实际 servlet 注册证明继续保留。不要把 session 加成第三条 ANY，也不开放任意 handler 列表。正常非 IAM 启动不得因为新 controller 强依赖 IAM client 而失败。

LoopbackAuthority 只对新 action 增加窄策略：gateway self-call、exchange／authorize、PORTAL 来源与 origin、真实 verifySubject 派生的资源；authorize 必须对照所存 grant 来源，不能相信 caller 自报 resource。原 route() 仅有 caller/target/action/mode，不能冒充已做 session resource 或 integrationAllowed 校验。新 action 的 operation 截止不晚于来源／grant 较早值；现有普通 exchange 用 source exp，不能用其构造一个 operation 比 grant 更晚的“合法”正例。其他 action、wire 与旧 fixture 行为保持。

隔离 gateway 测试需要真实 handler、真实安全链、有限实际 HTTP 和上述签名端点。session 自身必须发生一次 exchange 和一次 authorize；published-run 的 metadata 查询／main exchange、engine 和旧业务调用均为零。无需启动 PG／模型／集群来证明这一只读身份投影。

## 6. 写入所有权与实施前说明

派发后作者唯一拥有本任务批准的源码／target 和自身证据，root 只管理 current-work-state、后续 brief、root 审计及最终八份索引；禁止双方同时写 source。新建源码仅允许：common session 测试及必要私有投影类（能放既有 client 则不单独建类）、gateway PortalSessionController 与其有限测试。候选修改：CurrentAuthorityClient、HttpCurrentAuthorityClient、GatewayIngressBoundary、必要的 RouteCoverageVerifier、GatewayIamAssembly、agent-service-authority.json、LoopbackAuthority；只有真实依赖要求才动已有位置。

首次编辑前回报准确文件列表、API、proof 释放点、finite fixture 和测试 selectors、沿用 baseline manifest 与 source/target 截止。清单外需求先说明依据；不以补充测试为由遍历或重跑整个历史套件。

自身目录 `.local/iam-d4a-portal-session/`、`docs/evidence/iam-stage-4-d4a-portal-session/`；唯一报告 `docs/evidence/iam-stage-4-d4a-portal-session-report.md`。原 B2、修复、review 报告和索引不由作者改写。

## 7. 定向红绿与开源对照

失败先行，之后只跑真实覆盖新增／修改行为的命名 selectors。建议新 common `PortalSessionAuthorizationTest` 和 gateway `PortalSessionHttpTest`，实施前把以下风险归并为有限案例，避免穷举重复矩阵：

- 真实门户 exchange＋authorize 正例、精确四字段／no-store、角色收窄及空交集；portal 和 grant 各自先到期的合法正例。
- 不支持的 client 默认实现、public VA／currentOnly 无初始证明、非门户来源、错误资源／主体／origin／grant／operation／ticket 的可信绑定拒绝，区分 401/403/503。
- exchange 交接前到期、authorize 返回后与首次写出前到期、真实 owner close、Clock 回退不能延长预算；成功 body 为零且不产生业务调用。
- 精确 method/path、实际 query/body/注入头、替换或 secondary handler、错误或重复真实注册；正常 legacy 启动不因新增依赖失败。单个测试必须证明其名称声称的行为。

现有 decoder 全矩阵、B2 48、joint PG 25、common179、全工程962、其他历史绿均不重跑；只有实际改动影响已有路径时，报告具体理由再选最小受影响用例。setup／编译失败不算行为红，所有失败原始日志、源状态、命令和实际 Running XML 保留。多阶段适用绿明确逐项来源，不拼成一次 final suite。

参考现有固定版本源码归档，确实读到的具体范围才登记；现有 HttpCurrentAuthorityClient 的 B1／retained 私验机制是本次复用入口。需要 Spring MVC／Servlet 输出机制时，读对应实际版本官方源码并记录版本、原文路径、SHA 和范围，不依据模糊记忆或伪造“全源码对照”。源归档存在 CRLF/LF 表示差异时分别记录，不声称原始字节相同。不要下载新版本或增加依赖。

## 8. 有限、可重建的证据要求

开工以修复后的正式 manifest／sealed source 作 before；复用已封存物理基线及 SHA，不再复制每阶段全部 source／fat JAR／十余 GB 历史证据。每次真实测试前保存完整当前 source 哈希清单、仅批准变动文件的物理副本和 immutable baseline 引用，记录捕获时刻、实际命令、cwd、exit、Running 对应 XML 与案例结果。对未改文件提供明确重建规则；不可把后补清单伪称命令前捕获，也不把 overlay 叫完整 frozen tree。

交还时完整当前 source/target 清单与差异、批准源码物理 overlay、full-context patch、从 pristine baseline 实际 root-cwd apply/check＋全部重建源码一致性、实际新证据清单及 SHA、关键保护物理锚点／完整历史 lineage、准确测试及工作进程结束证据。保护范围沿用既有封存谱系；本轮只核新增／变动与关键锚点，不再全量重复哈希 123837 个历史文件。不得声称做了未执行的全量扫描。

报告逐项说明结果、实现不变量、实际开源范围、限制及剩余功能覆盖。源码和 target 冻结后明确归还所有权，再由 root 审计、生成任务 diff 并派发独立复审。作者和 reviewer 都不重复已经绿的行为套件。完成本有限接口不等于生产 IAM、浏览器、持久化历史材料、17需求／34任务／30验收项全闭环。
