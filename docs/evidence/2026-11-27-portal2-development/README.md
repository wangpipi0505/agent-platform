# R-42 门户主体绑定回归

`ed4b3f8e` 的 E2 网关将签名归属限制为 admin。该版本的现场回执、配置与临时凭据回收记录在 ../2026-11-27-portal-R-42/。

新增测试分别以 admin 和 operatorA 为签名主体，检查同一配置下的本人归属，并拒绝缺失、异主、多值和不匹配的业务系统。red.txt 记录新断言在原实现失败，green.txt 记录修改后同一测试和网关、委派回归。运行 `mvn -pl platform-gateway,authority-service -am test -Dtest=PortalClaimMapperTest,GatewayIamPortalHttpTest,DelegatedSubjectTokenTest,GatewayPublishedRunProxyTest -Dsurefire.failIfNoSpecifiedTests=false`，JDK 21。
