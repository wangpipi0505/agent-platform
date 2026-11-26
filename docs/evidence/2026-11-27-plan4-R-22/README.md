# R-22 本人 PLAN 授权专项复验

冻结标签 `r22-20261127-plan4`，代码 `e122a508d412d2674ac5dc7de54cf4e97fa1e181`。 E2 通过真实 user-mgmt 账号、authority-service 和正式门户取证。

| 核对项 | 结果 |
| --- | --- |
| ADMIN、BUILDER、USER | 本人任务创建、追加、确认和执行返回 200。重放同一创建或执行请求复用任务，重复确认复用确认序号。 |
| OPERATOR | 可读本人有效修订，修改与确认各返回 403。执行缺原因返回 400，提供原因后返回 200。角色按应用生效。 |
| 应用范围与归属 | 同应用跨主体读写、范围收窄后的本人读取、撤权后的读写均返回 403。 |
| 版本一致性 | 过期基线和不匹配的修订摘要返回 409。同号异参创建返回 409。子任务固定摘要与选中修订一致，purpose 为 EXECUTE，role 为 PLANNED_EXEC，parentTaskId 回指来源。 |
| 助手端 | 浏览器实际创建本人计划、点击确认，并用 OPERATOR 权限填写原因后执行。执行子任务 `task-6439898abb014e2db171` 已受理，原因与 TASK_ACCEPTED 事件一致。计划流程没有未处理的页面异常。打开侧栏时仅请求当前应用，侧栏返回 HTTP 200。 |

接口共 86 次请求。执行断言取到子任务 ACCEPTED 及父任务 RUNNING。正式发布门禁继续由发布负责人签认，R-22 总卡保持进行中。上一候选的 PR-B1、PR-C 和 RV-05 30 分钟记录均保留原版本归属。

[接口明细](plan-authorization.json) · [浏览器与审计回执](plan-browser.json) · [确认前](before-confirm.png) · [填写执行原因](operator-reason.png) · [双角色页面](operator.png) · [镜像、迁移与清理](../2026-11-27-plan4-freeze/README.md)
