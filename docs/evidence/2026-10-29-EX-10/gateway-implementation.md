# EX-10/11 能力包网关入口（2026-10-30）

Routes 仅增加能力包 base、import、正整数版本详情、bindings/resolve、applications 的精确路由至 app 服务。未知尾路径、相似前缀、非正整数与 internal 路径仍拒绝；权限及归属继续在 app 服务验证。

两轮 `mvn -B -ntp -pl platform-gateway test -Dtest=cn.agentplatform.gateway.domain.RoutesTest`（完整参数各 command.json）：

- `.local/ex10-11-gateway-route/gateway-first-20261030-01/`：7 项，6 PASS / 1 FAIL，nativeExit=1，新正例因缺路由失败。
- `.local/ex10-11-gateway-route/gateway-second-20261030-01/`：7 PASS，nativeExit=0，1 份新 XML，源文件稳定。

旧六例不变，新增一例覆盖 8 正例、16 反例。ROOT 完成一次非作者轻量代码复核。范围只含 Routes、RoutesTest 和本记录。回滚本地提交即可撤入口；没有变更鉴权、控制器或存储。不是浏览器联合验收或 EX-10/11 整卡关闭。
