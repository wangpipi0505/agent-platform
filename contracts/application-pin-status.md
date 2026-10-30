# 应用发布绑定观测读取（EX06）

`GET /admin/apps/{code}/pin-status`沿既有管理应用GET的认证与租户边界，返回统一`ApiResponse`。没有额外写权限、跨租户参数或身份头入口；服务端从已验证上下文`Actor`取得tenant，复用`AppService.pinStatus(code)`。网关既有`/admin/apps`路由转发，无新增网关/IAM规则。

成功`code=OK`，`data`是现有`app.publish_pin`最近一次观测的公开投影：`appCode,versionNo,state,releaseId,bindingId,reason,environment,lastPinnedReleaseId,lastPinnedBindingId,lastPinnedAt,updatedAt`。`state`保持`PINNED/FAILED/SKIPPED/DISABLED`；可能为空的字段仍可为null，不增造READY/PUBLISHED状态。

同租户应用存在但无观测行时返回`data={}`，不能解释为绑定成功或失败。应用不存在或只存在于另一租户时沿原`404 APP_NOT_FOUND`；未认证沿原`401 SYS-AUTH-401`。不泄露凭据、原始SQL或其它租户行。

该入口不调用precheck、commit或ReleasePins，不创建/更新业务行，也不访问OR。`releaseId`是本次发布尝试声明的目标，`bindingId`来自当时回执；`lastPinned*`仅表示最后一次成功观测，不能当成OR当前绑定权威。最近一次FAILED/SKIPPED/DISABLED不抹掉既有lastPinned字段，也不能据此推断远端已经解绑。

EX06后续All可用该读取面，与实际发布版本和OR只读绑定查询核对；真实预检、发布及联合验收必须取得用户后续明确确认。本接口开发及功能测试不构成上述验收。
