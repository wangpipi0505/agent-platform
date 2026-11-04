# CR-05 网关接入与公共权限目录

日期：2026-11-04，仅本项必要功能检查，不启整栈、外部模型/业务软件或全量验收。

- 新业务入口：`/admin/onboarding-sessions`，会话详情、preview、cancel及ASSEMBLE/VERIFY/PUBLISH三种步骤。仅登记的形状转发app，任意后缀/v1入口拒绝。
- 公共权限目录登记OnboardingSessionsController的8个明确方法，包括EDITOR只读PUBLISHED QUERY模板目录；原ADMIN模板维护接口没有放权。
- 下游声明仅app→engine创建debug-run/读其status、app→monitor提交evaluation三项；沿用原Handler action，integrationAllowed=false。Controller仍核当前主体、资源归属与门禁。目录登记不等于IAM下游已经联通；不能透传用户票据或回退IAM旧签发。

## 两轮功能结果

命令（JDK21，AP根目录）：

```powershell
mvn -o -pl platform-gateway -am '-Dtest=RoutesTest#routesOnlyPersistentOnboardingSessionOperations' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

RED：1例ERROR，原生产Routes对根会话入口抛`IllegalArgumentException: 此入口不存在`。GREEN：1/1通过，0失败/错误/跳过，BUILD SUCCESS。测试检查7种合法路径与6种拒绝路径，断言未放宽。原始两轮日志保存在`D:/CodexPrep/algorithm-reorg/upgrade-review-20261104/cr05-routes-{red,green}.txt`。

第二轮后仅补实际Controller的目录元数据；没有第三轮功能测试。尚未验证真实网关HTTP、Authority签发/撤权、会话Controller与下游联调；不把路由选择绿称为这些已通过。
