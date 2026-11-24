# R-38 USER_MGMT 主体查询

本地 user-mgmt 的 `GET /system/apikeys/identity/self` 用于持有者身份查询。服务端代查使用 `GET /system/apikeys/identity/{userName}`，调用凭据需有 `system:apikey:identity`。实际信封为 HTTP 200 内的 `code` 和 `data`。数据字段为 `userName`、`nickName`、`status`、`deptId`、`deptName`、`roles`，当前来源无租户字段。

平台保留现有签名主体 `sub = userName`，按已验证票据的租户和主体代查。租户由 authority 配置绑定，外部响应的租户字段如有映射也必须吻合。代查时使用 authority 配置的独立密钥引用，动态路径只接受服务端验证后的 subject。

成功需要 `code=200`、可用状态 `"0"` 与同一主体。停用、不存在和身份不一致均拒绝，依赖异常不签发授权。每次新的门户授权查询读取当前状态，无本地用户账号、密码或可用性缓存。
