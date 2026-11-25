# R-40 集成短票过期与白名单审计

基线通过正常 IAM 出票后请求白名单外任务操作，网关返回 403 `SYS-AUTH-403`。修复后同样的有效短票请求 cancel 和 resume，均返回 403 `APP_BINDING_DENIED`，两次拒绝各有一条 `AUDIT INTEGRATION_PATH_FORBIDDEN` 记录。审计保留凭据编号、HTTP 方法、路径 SHA-256 和 traceId，实测网关日志无测试 ApiKey 原文。

过期分支在 authority 签名、集成票类型和受众校验后识别。使用真实短票等待到 `exp`，调用发布版输入模式返回 401 `INTEGRATION_TICKET_EXPIRED`。另一张尚未过期的票在档案撤销后立即返回 403。正确短票继续返回 200，跨应用和无运行角色继续返回 403。

此处的白名单审计由网关记录。任务受理、详情、事件、同软件管理员中断与可从业务审计查询到的运行记录仍待接线。
