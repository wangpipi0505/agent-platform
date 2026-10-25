# G6 Task4 D1：服务接收守卫与运行时路由覆盖

状态：root 已于 2026-10-20 正式移交 D1。唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`；持续开发和隔离端点已获用户授权。

前置 Task3 合并修复由原独立 reviewer 复审，Spec compliance Approved / Task quality Approved，I1/I2/I3 Closed。root 已阅读全文、只读核验摘要及原15,212字节前缀：复审报告 `review-iam-stage-3.md` 当前 SHA 5a95cfb500e32b4abd346a49cf237ca8316d96b1a1971430583596b360a66ea1；原前缀 SHA df842cf0e5b1d987f077419e4f3b9ba2794c7307bdda7eabb35ca605fbc4e9d9。source/target 均已归还，root 再核当前63源码与最终清单一致。下文“准备/待正式移交”的历史措辞以本节为准。跨Task事项已分别落实到本交接和后续 D2/D3/E/联合验收，不冒称在 C 完成。

先读本文件，它是本交付的要求。原 Task4 全文在 `iam-stage-4-implementation-brief.md` 的“原Task4完整正文”，其中 D2/D3/D4、E 和联合流验收保持原依赖，本次只完成下面 D1。原计划及 root-decisions 不改；本文件细化交付顺序，不删原验收。

## 本次范围

- Create common authority 包的 `ServiceAuthorization.java`、`RouteCoverageVerifier.java` 及实现所必需的最小接缝；Modify common `SecurityConfiguration.java`、`Actor.java`、`authority/AuthorityContext.java`。如果确需额外少量 common 类型，请先说明具体原因和最窄形态。现有 `CurrentAuthorityClient`、`ServiceTicketProvider`、七字段 ResponseLease 公共调用形状保持；不要因接收守卫重写 C 协议/响应生命周期。
- 原接口：`ServiceAuthorization.require(ServiceCall)` 比对已验 caller/target/action，并在线 authorize 返回 VerifiedAuthority；`requireWorkload(ServiceCall)` 只对策略列出的纯机器路径调用 authorizeWorkload，返回 WorkloadAuthority；`requireResource(ResourceBinding)` 在实际 controller 参数/域对象解析后、任何发送/读取受保护 payload 前再核验绑定。
- 此任务定义 `AuthorityContext.requireFor(Actor,ResourceBinding)`：先比较 tenant/sub 与当前已验上下文，再调用 authority 当前核实；无上下文拒绝。请求帧可保存服务端接收的已验票据/明确刷新依赖等最小内部状态，以满足在线复核；不能把敏感值放入 Actor.attributes、业务 JSON、请求自报身份头或全局缓存。原 `bind(VerifiedAuthority)` 与 `current()` 行为及 C 的非 LIFO/owner-thread/无子线程继承回归必须保留。显式后台对象仍需独立使用客户端，不继承请求帧。
- 接收链使用实际框架匹配的 method/template 和部署绑定 serviceId 校验目录。目录中 action 相同不代表模板的来源限制相同。caller/authority_mode/operation/source/resource 均来自已验证证据；HTTP header/body 不提供可信身份或 source。
- `ServiceRoutePolicy` 是能力目录，不是具体资源判权。permit holder、Grant/材料 PG、真实六服务 handler 的域对象解析、出站迁移、gateway portal/integration session/resolver 均非本次实现范围；不要假装 D1 完成便能启用整个 Task4。

## 安全与行为要求

1. 始终按已验 caller、本机 target、实际 method/template、精确 action、mode 选择目录行。未知/disabled 能力默认拒绝；机器只允许目录 workload 行，不构造 Actor；delegated 与 workload 互斥。health 仅精确独立入口，其他未登记路径不能默认可用。不得把仅 ADMIN/SERVICE、共享 HMAC、用户 portal 或 integration 票据当下游能力。
2. 初次接收和 `requireResource`/`requireFor` 之后的当前证明必须与原主体、grant、operation、资源、调用行绑定，不能接受调用者拼出的更大 Actor、换资源、改 action、错误租户/用户。现有 HttpCurrentAuthorityClient 的签名/nonce/版本/期限检查复用。到期/撤销/503 不能使用上次成功决定兜底；当前证明没有业务操作许可的全局原子性承诺。
3. `Actor.current()` 的可信 delegated 投影使用在线决定返回的当前 Actor，并核 tenant/sub 与入站 JWT 一致；缺当前上下文默认拒绝。不得继续返回尚未到期旧票据里的 ADMIN 或旧 attributes。Actor 四字段、requireRole 业务规则不改；workload 仍无用户投影。门户投影保留明确的原入口语义，不把此接缝用于未经验证的 gateway 身份桥接。
4. 运行时 `RequestMappingHandlerMapping` 实际 handler 必须逐个明确登记：完整 method/template；别名分别检查；含未使用 handler 的 disabled 行按目录拒绝。未知 handler 或错误/不完整登记必须在 web server **开始接收请求之前**阻止启动。不能用静态源码数量或 ApplicationReady 之后检查代替。参考 `iam-stage-4-inventory/startup-review-note.md` 和已冻结 Boot3.5.0/Context6.2.7 实际源码；选择最小合适生命周期扩展点，以有限实际启动反例证明。框架 health/error/docs 的精确例外须写清依据，不能通配忽略 `/admin/**`、`/internal/**` 或所有框架/自定义 controller。
5. gateway 六个 disabled service 行 reason 是“Gateway ingress/dispatch only; no service capability granted”，它们不授普通 service capability。门户、集成、POLICY_MACHINE 的专用入站登记由后续接入提供；不能据此目录关闭所有合法 gateway 入口，或把 gateway 通配入口当所有下游的豁免。D1 必需时提供窄的显式入站登记接缝，禁止 generic allow-all 或 readiness 布尔绕过。
6. 验证信任域来自 validated 部署模式/明确验证入口，不能继续仅靠 `decoder instanceof TrustedJwtDecoder` 决定安全语义。后续组合 portal/integration decoder 不能落回 dev-HMAC/SERVICE 分支；验签失败不回退。D1 可交付最小可复用安全链接缝和组件接收测试，**完整生产配置 `mode=iam` 的 `IAM_TRUST_CHAIN_UNAVAILABLE` 启动闭锁继续保留**，直至 D3 全服务真实迁移由 root 另行核对。不得新增可被环境变量/HTTP打开的 ready 开关绕过该闭锁。
7. 同 action 不同来源：RunController.status 对 `/admin/runs/{id}`、`/admin/debug-runs/{id}/status` 的 integrationAllowed=false；`/v1/runs/{id}` 为 true。INTEGRATION 的原始来源即使当前为 DELEGATED 恢复，也应依据可信 operation 的 origin 语义与原目录限制核对，不能借来源名称洗掉限制。
8. `requireResource` 需比较真实域对象解析的绑定；common 测试 fixture 可用明确版本化小资源代表该接缝，不能由服务票据字段与自身比较冒称真实 PG 资源已判权。现 C forward 只支持 exact ResourceBinding；D3/E 的子资源派生必须另有可信父子关系，此轮不放宽为任意资源。

## 必需验证

- 先建立最小可编译接缝，保存旧行为真正断言失败的 red（缺类/编译错不是行为红）。用有限固定样本、Clock、一次请求和计数；无耗尽/慢探针，不重放旧失控 scope/schema 用例。
- 新增具名 `ServiceAuthorizationTest`/`RouteCoverageVerifierTest` 或等价聚焦测试；实际随机 loopback 端口 Spring Boot HTTP receiver 经过本次公共安全接缝。复用 common 测试目录中的 LoopbackIssuer/LoopbackAuthority，真实临时 RSA 签名及 HTTP 当前决定，不以纯 mock authorize 证明受信链；最多有限两用户/两region/六client，测试业务计数明确 0 或 1。D1 组件 receiver 不冒称已迁移实际 engine/skill 服务。
- 必测：合法 delegated 一次进入；错误 caller/target/method/action/tenant/sub/resource、disabled/unregistered、portal/integration直连、只有ADMIN/SERVICE均0；合法两种 workload 行以及错误 mode 的互斥；撤权/到期/503后不回退；context finally 清理和异常/并发请求无串权。入口签名/结构错误401、已验动作/能力拒绝403、依赖不可用503保持清楚。
- **实签旧 JWT 有 ADMIN，但在线决定仅 EDITOR**：实际 HTTP controller 的 `Actor.current().requireRole(...)` 不能沿用旧 ADMIN 通过；正向允许当前合法业务角色。attributes 同样来自当前收窄证明。保留 tenant/sub 不一致和缺上下文拒绝。
- **别名限制**：真实匹配 method/template 下 INTEGRATION 的 v1 正例进入1，admin 两别名负例0；action 相同不能扩大允许集。另测 DELEGATED operation 的原来源不可洗白，依精确 OperationBinding 字段实现。
- **启动时序**：未登记的真实 handler、未覆盖 method/别名有限反例启动失败，并可观察 server 未开始监听/未接收；合法登记成功且实际 HTTP 请求可用。保留完整 iam 配置仍在原工厂处理器拒绝且 listener=0 的测试。不要让这一原闭锁掩盖 runtime verifier 自身的测试。
- 聚焦测试开发完成后，运行一轮完整 common（当前最终基线123项）确认没有 C 回退；冻结后不因写报告/清单反复跑套件。不跑全部七 reactor、不 package、不启停主六服务/长驻辅助进程。旧 M1 配置负向日志噪声可在本次触及所属测试时用窄的测试日志捕获约束，保留断言和原始历史日志；不为降噪改安全行为。

## 交付、边界和实际基线

原 Task3 最终源码清单 `iam-stage-3-review-fixes/i3/source-after.json`：63 common src/POM；实际测试 common123，14:06:47。完整合并补丁 `i3/full.patch` SHA 7c7e3c3f4e51e429c02e8b187ba32255ebb1a1058a8f568f5ba4de5d7e42f2c2；root 核查 `.local/iam-stage-3-fix-root-handoff-check.json`。**正式移交仍以 root 后续读取独立复审结论为准。**

实施者只写 common src/POM 与自己的 `docs/evidence/iam-stage-4-d1*`；需要改 POM 依赖或测试公共 fixture 对外形态先报具体理由。其他六业务模块/配置/迁移/前端/原契约/外部文档、原证据不得改。保护父 POM 与六模块当前 src/POM，原 Task3/三项修复/独立审查证据（审查合法追加前缀分开）及本轮 baseline。

无 HEAD/remote，不造空 git diff/提交；交完整 patch、baseline/frozen/SHA/真实 git apply 后原字节比较、当轮实际 logs/XML、最终源码与 target 冻结清单，归还 source/target 后独立审查。报告路径 `docs/evidence/iam-stage-4-d1-report.md`，完整写清命名覆盖、实际命令输出/red原因/green、真正读过的固定开源源码与应用点、自审疑点/跨Task缺口。短回复 DONE/DONE_WITH_CONCERNS/NEEDS_CONTEXT/BLOCKED、测试摘要、报告路径即可。不派子agent，不发布，不读实际秘密，不碰相邻旧工程。

生产 BLOCKED_IAM_SCOPE_ACCEPTANCE 与 T01 唯一完成标记保持；D1不签收D2/D3/D4、E、完整G6、真实IAM/集群/容量。static ServiceTokens 与 Spring 模式统一在 D3 实际迁移时一并核验，不在此交付据旧静态路径开生产门。
