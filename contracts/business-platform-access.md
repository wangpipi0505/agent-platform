# 业务软件接入构建平台的统一授权通道

接入方通过 platform-gateway 调用平台公开接口。平台内部由现有 authority-service 统一签发、转发和核验授权。业务软件按公开接口集成，平台负责下游服务身份、授权目录和内部票据。

## 接入方接口

| 使用场景 | 入口与凭据 | 权限来源 |
| --- | --- | --- |
| 从业务软件打开构建工作台 | 同源宿主注入 `window.agentPlatformPortal`，提供 `getAccessToken()` 和 `onSessionChanged(listener)`。门户通过 `Authorization: Bearer <portal JWT>` 调用 `GET /auth/session` 和已开放的 `/admin/**`。 | 平台根据已验证的租户和主体查询本地角色、能力及应用范围。 |
| 在业务软件内执行绑定的智能体 | 可信后端从已验证的业务会话读取用户，经 `POST /embed/ticket` 使用接入凭据换短票。客户端用短票调用部署清单中已开放的 `/v1/**`。 | 业务软件决定其用户权限，网关依据凭据控制绑定应用、租户、业务系统和角色映射上限。 |
| 平台自身定时任务 | 由部署方为任务所在服务登记 workload 的 caller、target、action。 | 权限归该服务，审计记录服务身份。 |

嵌入接口、HTTP 字段、签名和有效期见 [宿主短票协议](assistant-host-ticket.md) 与 [助手集成规范](assistant-integration.md)。业务软件保存本地会话与平台会话的关联，从可信后端提交稳定用户标识和登记角色。浏览器通过 Authorization 头携带短票。租户、应用和业务系统由平台从已验门户身份或接入档案确定。

## 平台 IAM 配置

1. 业务服务配置 `AGENT_SECURITY_MODE=iam` 及各自的服务凭据。网关统一入口使用 TLS，平台服务通过受信网络访问 authority 的 token、decision 和 JWKS 地址。
2. authority 开启 `AGENT_AUTHORITY_DELEGATION_ENABLED=true`。门户票的 issuer、audience 和 client ID 与网关验签配置逐字匹配。主体代查配置 `AGENT_AUTHORITY_IDENTITY_URL`、绑定租户及 `AGENT_AUTHORITY_IDENTITY_SUBJECT_KEY_REF`。由 authority 提供登录会话时，同时配置会话签发和身份源档案。
3. 网关为精确登记的入口建立用户委派。服务按动作、资源和来源核验委派，再执行平台能力检查。异步运行保存委托引用和资源绑定，调度时用当前代次重取授权。评测委派过期后，门户恢复会重新核验身份和平台应用权限，并按评测契约记录授权换代。
4. 使用期短票的 IAM 交付由 R-40 验证。平台侧需接通 authority 的集成换票处理及网关的运行入口。对接方以部署清单所列已开放能力为准，宿主可以先按本契约准备后端与调用代码。

## 联验标准

- 构建面持同一门户票，平台授权前拒绝、授予后通过、回收后再次拒绝，租户和应用范围随请求复核。
- R-37 评测使用门户委派，monitor、app 与 engine 使用目录中登记的评测动作。评测达 `ACCEPTED` 后，发布预检以当前草稿修订和摘要读取真实报告并按环境门判定。
- 使用面同一用户在业务软件 A 与 B 的权限分别生效。跨租户、跨应用和跨业务软件的访问被拒绝并留审计。
