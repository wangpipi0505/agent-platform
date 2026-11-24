# R-39 构建期授权存储

构建期用户身份由 user-mgmt 提供。平台按租户和外部主体存授权关系，保存平台角色、应用范围、授权人、授权时间和回收状态。应用范围空数组表示租户内全部。回收保留原授权行，恢复可复用同一关系。

角色能力与页面映射存于 app schema。能力由平台配置，角色对能力的开关和页面展示各自独立。用户与授权页提供用户检索、角色分配、应用范围设置、保存与回收。

首个管理员通过部署配置 `AGENT_BOOTSTRAP_ADMIN_SUBJECT=tenant:subject` 引导，仅在授权表为空时生效。服务多实例串行判断首次授权，授权与 `BOOTSTRAP_ADMIN_GRANTED` 事件同事务提交。后续配置变更不追加管理员。

集成凭据的角色映射只接受平台约定的三档运行角色。运行期继续按接入方身份和权限执行，构建期授权与外部运行权限分属不同判定路径。

## 构建工作台与授权管理

- `GET /auth/session` 返回已验证的租户和主体，以及来自平台有效授权的 `roles[]`、`capabilities[]` 和 `pages[]`。网关使用单独的门户委派获取授权投影，app 根据身份和授权表查询。
- 应用列表按 `agent.view` 与应用范围过滤。草稿写入按 `agent.build` 判定，发布按 `agent.publish` 判定。构建能力和应用范围同时满足才允许操作。创建应用和管理授权需要该能力具备租户内全部应用范围。
- `GET /admin/platform-authorization/users?q=...` 检索用户目录。 `GET /admin/platform-authorization/grants?subject=...` 查询授权。对同一路径 `PUT` 保存或恢复，参数为 `subject`、`role`、`appScope[]` 和可选的 `expiresAt`。 `DELETE` 按 `subject` 与 `role` 回收。 `GET /admin/platform-authorization/audit?subject=...` 查询操作审计。
- 用户检索使用 `GET /system/user/list` 与 `system:user:list`，保存前用命名查询复核账号有效性，需 `system:apikey:identity`。app 配置使用 `AGENT_AUTHORITY_IDENTITY_URL`、`AGENT_AUTHORITY_IDENTITY_TENANT` 和独立引用 `AGENT_PLATFORM_USER_SEARCH_KEY_REF=env:AGENT_SECRET_PLATFORM_USER_SEARCH`。当前 USER_MGMT 实例无租户字段，目录只能服务绑定的租户。
- 保存和回收与追加审计同事务提交。角色能力及能力页面的配置变更也写入追加审计，使用 `GET /admin/platform-authorization/configuration-audit` 查询。重复请求保持幂等。授权不缓存，后续请求读取新状态。应用范围和用户查询均按已验证租户限制。
