# 命名输入与数据集查询的网关路径

root 为正在实施的 T20/T22 消费者补固定服务映射：

- `/admin/debug-inputs`、`/{数字id}`、`/{数字id}/promote` 送 engine。
- `/admin/evaluation-datasets`、`/{appCode}/{version}` 送 monitor。
- `/internal/datasets/promotions` 继续拒绝在网关透出；`/admin/debug-inputs/42/secret` 等未定义子路径也拒绝。

先补 RoutesTest 实际执行 red：旧映射抛未知入口，6 项/1 error/0 failure/skip，05:57:01（`.local/test-input-routes-red.log`）。增加上述两个精确匹配后，全 gateway 模块 15/15、0 failure/error/skip、05:57:22 BUILD SUCCESS（`.local/test-input-routes-green.log`）。没有将只补映射等同后端资源权限通过；资源的 tenant/principal/EDITOR/revision 仍由各服务验证。

此轮不部署网关，避免影响正在执行的真实长任务。后端输入/数据集稳定、独立复核后再统一更新自有开发服务，真实浏览器及路由 HTTP 检查仍待后续。原契约/详设不改，内部 RPC 不开放。
