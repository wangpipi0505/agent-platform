# CR05：同模式 app 出站与 R2 开发证明接线

状态：新增实现，功能未复核。没有启动第三轮功能测试，没有更改原断言；编译结果仅说明源码可编译，不能记为完整链路 PASS。原 R2 与阶段1证据保留历史状态，本记录只说明后续追加实现。

## 本席精确文件白名单

- `app-management/src/main/java/cn/agentplatform/app/infra/AppAuthorityTransport.java`（新增）
- `app-management/src/main/java/cn/agentplatform/app/infra/OnboardingSkillImportsClient.java`（root 释放后接手，改为薄客户端）
- `skill-registry/src/main/java/cn/agentplatform/skill/application/OnboardingSkillImports.java`（R2后续：显式模式与开发请求证明）
- 本证据 `docs/evidence/cr05-app-authority-transport-20261104.md`

未修改公共 Proof 类、公共 authority 目录、AppService、两个旧 metadata/preflight 客户端、RuntimeClient 或任何前端；这些协作文件由其他席交付。没有 stage/commit。

## 统一调用契约

`AppAuthorityTransport.call(String base,String path,Map<String,Object> body)` 及末尾增加 `long deadlineNanos` 的重载。body 为 null 时 GET，否则 POST；成功仅返回已解包的 ApiResponse.data 对象；失败为 PlatformException，无 checked exception。

四个部署 origin 分别从 `agent.services.skill/model/engine/monitor` 构造注入。只接受 HTTPS 或固定 HTTP 回环字面地址（127.0.0.1、::1）；拒绝带用户信息、非根路径、query/fragment的 origin。运行时 base 必须精确对应已配置 origin。

调用方法和 raw path 必须唯一匹配 ServiceRoutePolicy 中 caller=app、mode=delegated、相应 target 的已登记 handler；使用 Spring PathPatternParser，歧义即拒绝。拒绝绝对/外部、编码、矩阵、反斜线、重复分隔和点归一路径、fragment。query 只跟随已经匹配的 handler，不参与选 target 或能力。

请求和响应各最多2MiB；响应采用有界 JDK HttpClient BodySubscriber，在接收数据块时检查总量，不先无界读完整体。HttpClient 禁止重定向。HTTP 请求及整个异步响应体的等待受30秒与调用方剩余 deadline 的较小预算约束，超时取消请求；授权刷新/forward前后也检查该剩余预算。合法4xx继续作为4xx抛出；非JSON的4xx亦保留状态，拒绝空、尾随值和非对象 envelope。没有自动重试。

## IAM 模式

仅 `agent.security.mode=iam` 使用当前能力客户端。读取 Actor/AuthorityContext，要求本 app 入站上下文及非空 root/operation；沿原 root 通过 requireFor 刷新，再根据已匹配目录 action forward 同 root。出站票据必须保留 operation。缺当前正式能力明确失败，不签 legacy HMAC，不发用户 token，不转换 body 摘要为授权资源。

OnboardingSkillImportsClient 保持 preview/create/get/version 四个方法，只做固定参数校验并调用统一 transport。metadata 的精确注册版本校验仍由既有业务校验保留。

## 显式开发模式

仅 `agent.security.mode=dev-hmac` 且 target=skill、action 位于以下四项时，统一 transport 才会签原 service JWT 并附独立 app 请求证明：

- skill.onboarding-import.create
- skill.onboarding-import.read
- skill.metadata.read
- proxy.skill.post.OpenApiImportController.preview

`agent.onboarding.dev-app-key-ref` 默认空，必须显式设为公共 Proof 工具认可的 `env:AGENT_ONBOARDING_DEV_APP_KEY`；实际独立环境密钥要求至少32 UTF-8字节。该证明不替代 service JWT，不扩张角色，也不允许 DEV 调其他 target/action。

R2接收端仍显式要求SERVICE，并以 Actor.requireRole(EDITOR) 保留ADMIN上位语义。仅显式dev-hmac从实际Servlet RequestContext取得 method/raw URI/query，要求精确create/read路由及唯一 `X-Agent-Onboarding-Proof`，对已解析同一body验签。租户和主体只取已验JWT Actor；证明不能自报身份。审计明确记录 `mode=DEV_ONBOARDING`，不生成VerifiedAuthority。IAM分支保留原caller=app/target=skill/action/current root检查，绝不失败后回退开发证明。

旧 metadata/preview 的 DEV 接收语义未改：专用证明只有新 R2 imports 强制。旧 AppService/RuntimeClient 等 DEV 原调用仍由各原路径承担，不因本段改动扩大允许列表。

## 编译与未覆盖

transport 席已对当时真实 AppAuthorityTransport、公共 Proof 和两个旧客户端做隔离 javac，exit0；随后本席仅追加严格响应JSON/空base/IPv6边界处理和R2模式分支。连接席负责最终 app+skill+engine 的跳过测试编译窗口，最终结果由下方追加，不能把较早隔离编译冒充最终文件验证。

最终连接席报告：使用JDK21，在2026-11-04 19:07:48至19:07:56 +08:00执行：

```text
mvn -q -pl app-management,skill-registry,engine-runtime -am -DskipTests test-compile
```

exit 0。编译输入 AppAuthorityTransport SHA-256 确认是 `f6f913de11b3a6e9cd6f4077794cc3849b19072e39ffe42cd3d31f87e2b72dff`，已覆盖最后的响应JSON解析后 deadline 检查；其后本席没有修改生产源码。连接席本地ignored日志为 `docs/evidence/cr05-onboarding/cr05-r3-compile-final.log`。这是跳过测试的生产/测试源码编译，不是新增功能测试，也没有执行迁移。

未运行网络请求、服务、数据库迁移、功能测试或浏览器。未覆盖正式IAM forward/撤权、实际DEV双密钥部署、签名拒绝/重放、真实HTTP超时/大小/重定向/4xx、导入与草稿测试/发布全链；以上均为未复核。

## 本席最终源码 SHA-256

| 文件 | SHA-256 |
|---|---|
| AppAuthorityTransport.java | f6f913de11b3a6e9cd6f4077794cc3849b19072e39ffe42cd3d31f87e2b72dff |
| OnboardingSkillImportsClient.java | b831a51fb5724d7f3397301d67433add14e23eccc0d357a357d1ad6ace6e0b9e |
| OnboardingSkillImports.java | 6dad6bd86a27fad2fb0a3817a9eb5951cf83fbc31186cc239d2efcd91fc4710d |
