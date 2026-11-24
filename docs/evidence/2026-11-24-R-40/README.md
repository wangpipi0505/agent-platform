# R-40 接入档案角色映射

接入档案 V124 已保存 `role_mapping`。本轮把该字段接入凭据签发、策略更新、业务角色出票和 PostgreSQL 策略复核。调整角色映射时同时更新策略版本及摘要。档案未配置映射时沿用既有业务角色兼容规则。

同一用户和同一业务角色在两个软件档案中可得到不同的使用期角色。档案固定业务系统和数据标签上限。测试中的软件 A 映射到 `AGENT_ADMIN`，软件 B 映射到 `AGENT_VIEWER`。已允许的 `AGENT_BUILDER` 声明降为 `AGENT_VIEWER`，请求自报的业务系统字段不会改变归属。

```powershell
# JDK 21。AGENT_DB_URL_POLICY 指向已独立准备的 agent_platform_iam_policy_v12。
mvn -B -ntp -pl app-management,platform-gateway -am '-Dtest=IntegrationClaimsTest,IntegrationPolicyTest,IntegrationPolicyDatabaseTest,IntegrationPolicyResolverHttpTest#mappedBusinessRolesAreReadFromTheCredentialAndInvalidateOldPolicy+policyAndOrdinaryAdminServiceBothWorkInSameMappingAndNoSecretsLeak+policyAndRevokeChangesAdvanceRevisionAndOldExpectedVersionRejects' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

定向共 12 例通过，零失败、零错误、零跳过。包括模拟双软件出票、凭据映射写入、真实 PostgreSQL 读取和旧策略版本返回 409。原始定向输出在工作区 `.local/r40-role-focused.log`。全量 `IntegrationPolicyResolverHttpTest` 另有 7 例未通过，分别出现在测试凭据签发和旧集成令牌换票夹具。在本卡剩余的 authority 集成换票工作中继续核验。

待接通 IAM `exchange_integration` 服务端和网关 `/v1/**`。同软件中断、跨边界拒绝审计、平台授权隔离及 FT-INT 整栈联验继续跟踪。
