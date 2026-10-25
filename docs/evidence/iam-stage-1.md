# IAM Stage 1：common 路由能力、属性语义与模式闭锁

2026-10-20。本报告仅对应批准 G6 Task1。唯一工程为 `G:/207/项目/算法重组工具/代码/agent-platform`；root 已明确移交 common 源码/target 并授权本 Task1 实施。输入为 `docs/evidence/iam-stage-1-implementation-brief.md`（已读取末尾追加的原计划 §4；SHA256 `f0e3054577ec017e4dd4d5702cf1ffb6b6fc49ced8483afbc1d66e971d2f58f4`）。历史“只规划”状态不用于否定本次授权，后续 Tasks2–7 没有实施。

结果：最终定向 **45/45**、完整 common **55/55**，失败/错误/跳过均为 0。IAM 即使提供完整配置，也在 WebServer 构造前以 `IAM_TRUST_CHAIN_UNAVAILABLE` 拒绝；没有新增可配置的 ready 开关。保持 **BLOCKED_IAM_SCOPE_ACCEPTANCE**，没有生产 IAM 接通、生产开放或总体验收完成结论。

## 1. 范围与需求映射

仅修改一个既有 common 文件、新增六个指定类型、一个 JSON 和两个测试，共 10 个源码/资源/测试文件；POM 不变。完整变化与逐文件前后 SHA 在 [changes.json](iam-stage-1/changes.json)。基线保存全部 19 个既有 common src/POM 文件，最终冻结保存全部 28 个文件；未改 Actor 四字段、ServiceTokens、业务模块源码/POM、迁移、前端、四契约、设计或三份外部台账。六服务 controller/caller 只读。

| 要求 | 实现与证据 | 本阶段边界 |
| --- | --- | --- |
| caller/target/method/template 精确能力；未知/重复拒绝 | `ServiceRoutePolicy` 以四元键精确查表；未知/重复、无调用入口、未知 mode/caller/target、可授权通配模板拒绝；`AuthorityPolicyTest` | 只接受已识别 controller template，不把具体 URL 匹配为授权；目录将在 Task3 接入 handler 守卫 |
| ADMIN 不覆盖 caller 能力 | `require` 不读取 Actor/roles；以 ADMIN 放入 SecurityContext 的负例仍拒绝未授予 caller | 既有 Actor.requireRole 域角色行为不改；服务能力与域角色仍需分别执行 |
| 权限集合缩小、缺项不补权 | `ClaimCeiling.intersect` 为不可变交集；null/缺集合按空；`attributes` 仅将已定义的 region 映射为权限集合，缺侧为空 | 未定义的属性不推测语义、不自动映射；后续新增许可属性需明确注册 |
| dataLabels 不削弱 | `attributes` 对两侧标签取不可变并集；一侧缺失不删另一侧标签 | 纯语义函数；历史材料/current IAM/出域调用链消费仍在后续任务 |
| tenant/sub 与 biz/user 归属相等 | `SubjectKey` 非空；`subject` 两侧 exact equals、缺侧拒绝；bizSystemCode/userId 两侧必须同一个非空集合单值，缺项/冲突/多值拒绝 | 未通过身份/资源核实的 record 不能据此成为可信凭据 |
| 具体资源独立绑定 | `ResourceBinding(kind,id,versionDigest)` 三项非空；目录 `require` 返回 resource=null 的能力描述 | template 不能充当资源；后续 controller 必须实际解析归属/版本摘要并在线授权，null 描述不能冒充已核资源 |
| 配置强类型/未知配置拒绝 | `AuthorityProperties` 带 `@ConfigurationProperties(ignoreUnknownFields=false)`，启动以 Boot Binder + NoUnboundElementsBindHandler 严格绑定 | 未读取 env 值或解析 workload secret；本阶段仅检查配置引用形状 |
| 显式开发/测试、非法配置不降级 | dev-hmac / test / iam，缺 mode 拒绝；dev-hmac 必须 dev-auth=true 与固定 loopback；test 必须 mode=test、active profile=test 与固定 loopback | test profile 不补缺 mode，也不替不完整 iam 降级；dev/test 仍沿用旧 HS256 安全链，不能宣称生产互信 |
| IAM 开 listener 前闭锁 | 静态 BeanFactoryPostProcessor 中校验并拒绝 IAM；每个非法配置启动用真实 SpringApplication/Tomcat factory 计数验证为 0 | 即使完整 IAM 也拒绝；没有 TrustedJwtDecoder/current-authority 装配，也没有 HS256 回退 |
| 行为红绿、完整 common | 初始可编译接缝红 27 项/25 断言失败/0 错误；最终定向 45 绿；完整 55 绿 | 不把编译缺类、工具参数错误、初始 Unix socket 临时目录错误当行为红 |

`AuthorityProperties.validate()` 校验类型内部的 mode/service-id、服务入口、IAM 必需字段及边界；`validateDeployment` 另消费部署级 `agent.dev-auth`、`server.address`、active profiles。禁止根据请求 Host 判断。dev/test 只接受字面 IP `127.0.0.1`、`::1`、`[::1]`（不做 DNS 解析）；测试实际为 `127.0.0.1:0`，mode=test 的正例显式启用 test profile（测试正例同时设置 dev-auth=true，但 test 模式的规则不要求该开发登录开关）。非 gateway 的 portal.enabled、integration.enabled、旧 allow-embed=true 均拒绝。

IAM 必需配置为 authority issuer/jwks-uri/token-uri/decision-uri/client-id/client-secret-ref/allowed-algorithm/audience；只接受本地已知 service-id、同名 client-id、`env:AGENT_SECRET_IAM_<SERVICE>_CLIENT` 引用、RS256 与 `urn:agent-platform:service:<service>`。URL 必须 HTTPS、含 host、不含 userinfo/query/fragment，不从 token 派生地址。票据 1..60 秒、决定 1..5 秒、连接 1..1000ms、读取 1..2000ms 为批准计划的保守上限/默认；此处只校验数值，未实施网络/JWT字节预算。portal/integration 的独立可信配置仍在后续任务完善。未给 IAM 测试 HTTP 例外；本阶段 IAM 始终关闭，显式 test 模式测试旧装配。

## 2. 实际路由与调用边盘点

最终 [逐行库存](iam-stage-1/route-inventory.md) 包含 method/template/caller/target/action/mode、target 源码行及 caller 证据；[controller-inventory.json](iam-stage-1/controller-inventory.json) 保存实际 controller 模板及每个源文件 SHA；[call-sites.txt](iam-stage-1/call-sites.txt) 保存初始定点搜索。`inventory.py` 中的边均手工按实际 caller 证据登记，扫描器仅提取 controller 模板；不会按 URL 前缀推断 internal caller。

| target | 实际 controller method/template | 最终 JSON 行 |
| --- | ---: | ---: |
| gateway | 6 | 6 |
| app | 39 | 41 |
| engine | 50 | 51 |
| skill | 29 | 31 |
| model | 19 | 21 |
| monitor | 15 | 16 |
| 合计 | **158** | **166** |

166 = 158 个源模板 + 5 个条件 Springdoc GET `/v3/api-docs` 模板 + 3 个第二 caller 行（app.candidate.read、skill.metadata.read、model.binding.freeze）。150 delegated、2 workload、14 disabled。Springdoc 2.8.9 模板来自现有依赖和 GatewayController:127 的真实聚合调用，属于条件框架 handler；gateway 当前 api-docs.enabled=false 的情况下自身已有聚合 handler，并未把它当下游可授权通配。静态测试独立枚举六模块所有 `@RestController` 映射并与目录逐模板相等，包含非 `*Controller.java` 的 DevAuthentication、类级 base path、多模板 alias、正则变量模板及无括号方法注解。后续 Task3 仍须真实 `RequestMappingHandlerMapping` 枚举，因为静态扫描不能代替条件装配、依赖升级和实际 handler 歧义检查。

gateway 的 `/admin/**`、`/v1/**` 为实际 dispatch handler，但这两行是 disabled、caller=null，没有授予通配能力。其余公开业务入口按各个实际目标 controller 模板分别授权给 gateway；IntegrationPolicy 只对 11 个具体 `/v1/` 模板登记 integrationAllowed，标志不替代 app/owner/current policy 校验。GET/POST、freeze/validate、tool.fact.record/tool.submit、metadata/execute 等分别为不同 action。

以下 **8 个现有 internal handler** 明确 disabled（保留业务实现与现有 dev/test 行为，本轮目录不授予服务能力）：

| target | method/template | 原因 |
| --- | --- | --- |
| app | GET `/internal/integration/{code}/{biz}` | 未见 main 源码 caller |
| engine | POST `/internal/tool-calls/register` | 无跨服务 caller；实际注册使用同进程 ToolCalls |
| skill | GET `/internal/statistics` | monitor 实际没有该调用 |
| model | GET `/internal/statistics` | monitor 实际调用 POST query，而非此 GET |
| skill | POST `/internal/skill-facts/{callId}/reconcile` | 未见 main 源码 caller；后续接入需审查 |
| skill | POST `/internal/skill-facts/{callId}/retry` | 未见 main 源码 caller；后续接入需审查 |
| skill | GET `/internal/skill-facts/{callId}/reconciliation-proofs/{attemptId}` | 未见 main 源码 caller；后续接入需审查 |
| skill | POST `/internal/skill-jobs/{callId}/callback` | 尚无已批准、已命名的独立 callback client，不虚构客户端 |

另外 6 个 gateway 模板 `/auth/dev-token`、`/admin/**`、`/v1/**`、`/v3/api-docs`、`/assistant.js`、`/embed/ticket` 是 ingress/dispatch 专用、不是普通服务能力，目录标 disabled。此标记不删除现有公开入口、不声称已修改其认证实现。

实际调用与计划示例差异已向 root 说明并获“按实际最小边继续”答复：

- `skill/application/SkillGovernance.java:139` 确有 skill→model POST `/internal/model/chat`，已登记；engine 当前用 `RuntimeClients.java:85` 的 POST stream 和 `ModelExchange.java:175/213` 的 measure，没有给 engine 非流 chat 权限。
- `monitor/application/StatisticsService.java:101` 实际为 monitor→model POST `/internal/statistics/query`；未为 monitor→skill/model 的 GET statistics 增权。
- app→model freeze/validate/entry、engine→model freeze 分别由 AppService:627/753/964 与 RunService:696 证明；未给 engine entry/validate 权限。engine→app candidate/version/snapshot/published、monitor→app 仅 candidate 按实际调用登记。
- engine→skill fact.read、artifact.reproduce 有 ExecutionRecovery:61、ControlledReplay:329 证据；reconcile/retry/proof 的既有功能要求未取消，但无实际跨服务 caller，目录暂禁用。
- gateway 对 engine GET `/v1/runs/{id}` 还有 IntegrationAccess:154 的集成归属预检调用，复用同一精确能力，不授予额外动作。
- 未来 `/internal/material-scopes/{kind}/{id}`、`/internal/iam/integration-policy/resolve` 尚无实际 controller，本轮不造入口、不授虚构能力。它们是后续任务，不属于“本轮已覆盖现有 handler”。新 controller/caller 必须重新显式登记。

## 3. 真实红绿与可复核命令

命令均在上述工程根执行；不加载 `.env`，进程显式使用 JDK21 与工程离线 Maven 仓库。原始日志均保留在 [证据目录](iam-stage-1/)。测试仅启动测试 ApplicationContext 的随机 loopback Tomcat，完成/异常后关闭；没有启停六个实际服务、连接真实 IAM、package、发布、提交或资源耗尽探针。

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dtest=AuthorityPolicyTest,AuthorityConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

| 阶段 | 原始日志 | 结果/实际含义 |
| --- | --- | --- |
| 最小可编译接缝 + 未修改的旧 SecurityConfiguration | `behavior-red.log`，源码留 `red-seam/src/` | 27 项，25 断言失败、0 错误、0 跳过。Policy 5/5 失败是 caller/方法错误允许与属性放宽；Configuration 20/22 失败为非法配置实际启动成功，另 2 个显式开发测试正常启动。不是缺类编译红 |
| 初次实现绿 | `green-attempt-1.log` | 29 项全绿；新增目录重复/未知项校验测试较初始增加 2 项 |
| 扩充库存/配置/真实 HS256 回归 | `targeted-green.log` | 44 项全绿；包含源模板全覆盖与真实 HS256 user/service/集成用途回归 |
| 旧 allow-embed 在 engine 上的独立真实启动红 | `legacy-embed-behavior-red.log` | 仅运行配置非法启动方法，34 参数项中 1 失败、0 错误：engine+dev-hmac+dev-auth=true+allow-embed=true 实际错误启动 |
| 覆盖旧开关后最终定向绿 | `targeted-final-green.log` | 45/45，Policy 8 + Configuration 37；其中 34 非法启动场景均在 factory 构造次数为 0 时失败，3 正例是 dev-hmac、test+test profile、gateway dev-hmac+allow-embed |
| 冻结同码完整 common | `full-common.log` + `full-surefire/` + `full-test-summary.json` | **55/55**：上述45 + CommonBoundary4 + SharedSchemaAdmission5 + SecretVault1；0失败/错误/跳过，BUILD SUCCESS |

旧 allow-embed 独立红的精确选择器为：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dtest=AuthorityConfigurationTest#illegalConfigurationFailsBeforeCreatingListener' '-Dsurefire.failIfNoSpecifiedTests=false'
```

完整回归命令为：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

非法启动矩阵包含缺/未知 mode、字段齐备 IAM、逐项缺必需字段、client/service 不符、未知 service、dev-auth 与 IAM 同开、非 gateway 的新旧 portal/integration 开关、HTTP/userinfo URI、错算法/受众/secret_ref、四项预算的零值/越上限、dev 缺开关/非loopback、test 缺profile、未知属性拼写。所有值都是固定测试值；引用只检查字符串，不取 secret。正例对新建 decoder 使用 Nimbus MACSigner 的真实 HS256 签名验证 user/service，拒绝 aud/use 混用，非gateway禁集成；gateway 旧集成正例保留。

诊断错误也保留且不计安全行为红：`command-quoting-error.log` 是未引号 Surefire 参数被 PowerShell 拆分；`initial-unixdomain-error.log` 是误用 java.io.tmpdir 未设置 jdk.net.unixdomain.tmpdir 的环境启动错误。修正命令后才取得 `behavior-red.log` 的真实错误允许/错误启动断言失败。

## 4. 实际读取的开源源码

源码从 Maven Central 的固定版本 sources JAR 下载到本 Task1 证据目录，未引入产品依赖或更改 POM。每个所读文件与 JAR 的 URL/SHA 在 [upstream/source-sha256.json](iam-stage-1/upstream/source-sha256.json)，原文件保留。实际读了这些相关方法与 Apache-2.0 文件头，不声称全库审计：

| 固定来源 | 已读机制 | 本地用途 |
| --- | --- | --- |
| Spring Context 6.2.7 `AbstractApplicationContext.refresh` | invokeBeanFactoryPostProcessors 先于 onRefresh、finishBeanFactoryInitialization | 不把普通 JwtDecoder bean 的初始化顺序当作“listener 尚未构造”证明 |
| Spring Boot 3.5.0 `ServletWebServerApplicationContext.onRefresh/createWebServer` | onRefresh 调用 factory.getWebServer，随后注册生命周期 | 将装配拒绝放到静态 factory processor；参数测试观测真实 factory 未进入 |
| Spring Boot 3.5.0 `NoUnboundElementsBindHandler.onFinish/checkNoUnboundElements` | 在根绑定结束检查剩余未绑定元素并抛异常 | 对未知配置明确失败，不将 typo 悄悄忽略 |
| Spring Security OAuth2 JOSE 6.5.0 `NimbusJwtDecoder.withSecretKey/SecretKeyJwtDecoderBuilder.macAlgorithm/processor` | SecretKey builder 固定单一 MAC 算法选择器，Spring 独立校验 claims | dev/test 原 HS256 decoder 保留；IAM 关闭，不将该构造器当 RS256/外部 IAM 实现 |

## 5. 冻结、完整补丁与实际 apply

仓库 `git rev-parse --verify HEAD` 返回 `fatal: Needed a single revision`，因此不以空 git diff、虚构提交或 HEAD 对比作证。

- [baseline/](iam-stage-1/baseline/)：起步保存的完整 common src/POM；[baseline-sha256.json](iam-stage-1/baseline-sha256.json) 为当时 SHA。
- [frozen/](iam-stage-1/frozen/)：最终完整 common src/POM；[source-before.json](iam-stage-1/source-before.json)、[source-after.json](iam-stage-1/source-after.json) 在完整 common 回归前后均为相同 28 文件；`source-verify.log` 有断言结果。
- [full.patch](iam-stage-1/full.patch)：10 文件的完整 unified patch（包括完整新增 JSON、测试和六类型，无截断）；SHA256 **98135a64048ccdf00588fcfb1a74128b30f950f3dd8817642f30b331788f51f4**。
- [changes.json](iam-stage-1/changes.json)：逐改动文件类型与基线/最终 SHA；[patch-apply-verification.json](iam-stage-1/patch-apply-verification.json)、[actual-apply.log](iam-stage-1/actual-apply.log)：实际 git apply 返回 0，`applied-bytes/admin-common/` 全部28文件与 frozen、实际源码逐字节一致。
- [target-sha256.json](iam-stage-1/target-sha256.json)：`admin-common/target/classes` 和 `test-classes` 41 个输出摘要；target 留给独立审查，没有打 JAR 包或接管主服务。
- [read-only-input-sha256.json](iam-stage-1/read-only-input-sha256.json)：本轮只读上下文与六服务 main Java 当前冻结输入摘要，供评审复核；它不冒充起步前后证明。
- [artifact-sha256.json](iam-stage-1/artifact-sha256.json)：交付报告与证据文件摘要（清单自身不自包含）。

```powershell
python docs/evidence/iam-stage-1/freeze.py freeze
git -c core.autocrlf=false apply --verbose --whitespace=nowarn --directory=docs/evidence/iam-stage-1/applied-bytes docs/evidence/iam-stage-1/full.patch
python docs/evidence/iam-stage-1/freeze.py verify
```

freeze.py 的 freeze 步骤内部已执行上面的真实 git apply，不能在已应用的目录重复应用；`verify` 可直接重跑。初次 git diff --no-index 受仓库 `.gitattributes` 的 eol=lf 归一化影响，两个含 CRLF 的新增文件在 apply 后未逐字相同；该失败副本 `applied/` 和 `line-ending-normalization-error.patch/log` 仅为诊断。最终改用读取原字节生成完整 unified diff，再实际 git apply 到新的 `applied-bytes/`，断言全部28文件完全相同。另保留最初 Windows 路径转义错误 `freeze-path-error.log` / `actual-apply-path-error.log`。未通过的补丁尝试没有被记录为成功。

## 6. 限制与移交

本轮实现的是 Task1 基础层。目录尚未成为六服务运行时鉴权；没有TrustedJwtDecoder、外部IAM/current-authority客户端、可信 claims mapper、资源归属在线核实、恢复续票、流续核、材料scope绑定或业务调用端改造。纯 record 不具防伪性质，route 返回 null resource 不是可信决定。尚无实际 caller 的 reconcile/retry/proof 能力要求、独立 callback client、material scope 和 policy resolver 入口留后续任务，不视为取消需求。

六服务 application.yml 仍为旧配置，缺显式 mode/service-id；本 Task1 不迁移它们、不重启既有服务、不把新 common target 嵌回旧已固定制品。既有962七模块/实际V5五场景是 root 的前置结论；本报告只声称本轮55个common测试。服务迁移后统一后端/端点回归由 root 组织。

没有读取 `.env`、vault 数据、masterkey、真实凭据或任何相邻旧工程；没有运行供应商、集群、耗尽、长sleep或真实服务启停。测试预算不代表生产SLA。独立功能/安全审查尚待 root 分配，不把计划批准或本作者测试当作独立批准；**BLOCKED_IAM_SCOPE_ACCEPTANCE** 保持。
