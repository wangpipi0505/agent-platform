# R-38 USER_MGMT 主体查询

本地 user-mgmt 的 `GET /system/apikeys/identity/self` 用于持有者身份查询。服务端代查使用 `GET /system/apikeys/identity/{userName}`，调用凭据需有 `system:apikey:identity`。实际信封为 HTTP 200 内的 `code` 和 `data`。数据字段为 `userName`、`nickName`、`status`、`deptId`、`deptName`、`roles`，当前来源无租户字段。

平台保留现有签名主体 `sub = userName`，按已验证票据的租户和主体代查。租户由 authority 配置绑定，外部响应的租户字段如有映射也必须吻合。代查时使用 authority 配置的独立密钥引用，动态路径只接受服务端验证后的 subject。

成功需要 `code=200`、可用状态 `"0"` 与同一主体。停用、不存在和身份不一致均拒绝，依赖异常不签发授权。每次新的门户授权查询读取当前状态，无本地用户账号、密码或可用性缓存。

## R-41 启动与失败语义

- authority 的正常启动需要 `agent.authority.identity.subject-lookup-key-ref`（`AGENT_AUTHORITY_IDENTITY_SUBJECT_KEY_REF`），格式为 `env:NAME`。仅显式启用 `test`、`dev` 或两者的进程允许缺少该配置。
- 代查使用同一 USER_MGMT 连接、租户和状态映射。`identity.enabled` 控制 `operation=session` 的签发。门户换票始终查验当前主体。
- 未配置代查或环境变量没有可用凭据时，门户换票返回 `503 IAM_IDENTITY_UNAVAILABLE`，不签发票据。启用了会话签发但缺少代查凭据的部署在启动时记录告警，告警不包含凭据。
- 外部目录返回停用、不存在或主体不匹配时，门户换票返回 `401 SYS-AUTH-401`。

`deploy/compose-test.yaml` 透传目录地址、绑定租户和密钥配置。通过此编排启动时，设置
`AGENT_AUTHORITY_IDENTITY_URL` 和 `AGENT_AUTHORITY_IDENTITY_SUBJECT_KEY_REF=env:AGENT_SECRET_USER_MGMT_SUBJECT_LOOKUP`，
并由部署环境注入 `AGENT_SECRET_USER_MGMT_SUBJECT_LOOKUP`。

## 门户身份与应用授权

真实目录的角色用于校验账号及展示身份。门户签名凭据中的目录角色不授予 Spring 角色或服务间委派权限，已签发且仍有效的门户凭据遵循同一规则。签名主体、身份源可用性、租户、属性约束、票据期限及单登录撤销照常核验。

平台在 app 的有效授权表按租户、主体、能力及应用范围判定访问。门户的会话、任务与记忆入口每次请求都读取当前应用可见范围，已有会话的详情、轮次与重连也重新判定。随后由引擎按应用及本人会话核对归属。接入方仍使用集成档案的签名作用域与角色映射。
