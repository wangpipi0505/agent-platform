# R-40 接入档案角色映射

接入档案 V124 已保存 `role_mapping`。本轮把该字段接入凭据签发、策略更新、业务角色出票和 PostgreSQL 策略复核。调整角色映射时同时更新策略版本及摘要。档案未配置映射时沿用既有业务角色兼容规则。

同一用户和同一业务角色在两个软件档案中可得到不同的使用期角色。档案固定业务系统和数据标签上限。测试中的软件 A 映射到 `AGENT_ADMIN`，软件 B 映射到 `AGENT_VIEWER`。已允许的 `AGENT_BUILDER` 声明降为 `AGENT_VIEWER`，请求自报的业务系统字段不会改变归属。

```powershell
# JDK 21。AGENT_DB_URL_POLICY 指向已独立准备的 agent_platform_iam_policy_v12。
mvn -B -ntp -pl app-management,platform-gateway -am '-Dtest=IntegrationClaimsTest,IntegrationPolicyTest,IntegrationPolicyDatabaseTest,IntegrationPolicyResolverHttpTest#mappedBusinessRolesAreReadFromTheCredentialAndInvalidateOldPolicy+policyAndOrdinaryAdminServiceBothWorkInSameMappingAndNoSecretsLeak+policyAndRevokeChangesAdvanceRevisionAndOldExpectedVersionRejects' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

定向共 12 例通过，零失败、零错误、零跳过。包括模拟双软件出票、凭据映射写入、真实 PostgreSQL 读取和旧策略版本返回 409。原始定向输出在工作区 `.local/r40-role-focused.log`。全量 `IntegrationPolicyResolverHttpTest` 另有 7 例未通过，分别出现在测试凭据签发和旧集成令牌换票夹具。在本卡剩余的 authority 集成换票工作中继续核验。

## IAM 凭据验证

应用管理新增内部 `POST /internal/integration-credentials/{id}/verify`。正向调用由真实 authority-service 签发网关 workload 票，接收方绑定该票的凭据 ID，使用 PostgreSQL 中的密钥摘要核验，返回凭据策略及策略摘要。IAM 来源检查同样绑定经过工作负载认证的 Origin 值。

| 本机真实 HTTP 验证 | 结果 |
| --- | --- |
| 直接调用凭据验证，缺少 workload 票 | 401 |
| 网关 workload 票绑定该凭据且 ApiKey 正确 | 200，无密钥材料或密钥库引用 |
| 复用 workload 票请求另一个凭据 ID | 403 |
| 票据有效但 ApiKey 错误 | 401 |
| 增加同租户的独立平台 ADMIN 记录后以相同凭据再请求 | 200，凭据策略响应逐字段相同 |
| Origin 正确与改写 Origin 对照 | 200、403 |
| 数据库撤销凭据后重放原工作负载票与 ApiKey | 401 |

定向命令以 JDK 21 对 `admin-common,app-management,platform-gateway` 及其依赖执行 `PlatformBuildRoutesTest,PlatformRoleStorageTest,ServiceAuthorizationTest,GatewayIamPortalHttpTest,RouteCoverageVerifierTest,IntegrationCredentialVerificationTest,IntegrationPolicyTest`，62 例通过。其中新增 PostgreSQL 测试覆盖验证前后的平台授权对照、错误密钥和即时撤销。集成凭据由隔离库的测试记录初始化。

## 公开出票联验

本地网关的 `POST /embed/ticket` 经网关 workload 认证调用应用管理。应用管理核验 ApiKey 并按当前接入档案裁剪声明，然后以自己的服务身份请求 authority-service 签发 RS256 短票。票据使用独立的集成类型和受众，有效期最多 60 秒。

| 本机真实 HTTP 验证 | 结果 |
| --- | --- |
| 缺少 ApiKey 或 ApiKey 错误 | 401 |
| 出票 URL 附带查询参数 | 400 |
| 声明其他应用或请求来自未登记 Origin | 403 |
| 正确凭据和 Origin | 200，使用 authority JWKS 验证通过 |
| 声明额外角色、跨软件属性和超额数据标签 | 票面仅保留 `AGENT_VIEWER`、凭据所属软件及允许的数据标签 |
| 同租户增加平台 ADMIN 后以原 ApiKey 再签 | 200，票面角色及属性逐项相同 |
| 撤销凭据后重新出票 | 401 |

```powershell
mvn -B -ntp -pl app-management,platform-gateway,skill-registry,authority-service,monitor,engine-runtime,model-gateway -am '-Dtest=PlatformBuildRoutesTest,PlatformRoleStorageTest,ServiceAuthorizationTest,GatewayIamPortalHttpTest,RouteCoverage*Test,IntegrationCredentialVerificationTest,IntegrationPolicyTest,IntegrationExperiencePolicyTest,TokenEndpointTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.jar.forceCreation=true' package
```

JDK 21 定向 72 例通过。新增出票端点测试先复现 app 客户端签发返回 403，实施后返回 200，并确认 gateway 客户端不能直接签发集成短票。

IAM `exchange_integration`、网关 `/v1/**`、同软件中断、跨边界拒绝审计、运行面平台授权隔离及 FT-INT 整栈联验继续跟踪。
