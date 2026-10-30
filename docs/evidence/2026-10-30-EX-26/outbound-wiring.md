# skill AUTHORITY出站装配

新增条件Spring装配：AUTHORITY且部署mode=iam/serviceId=skill时创建有界、容器关闭的CurrentAuthorityClient及IamServiceTicketProvider。两处工厂都校验部署，外部替换client也不能绕过。LEGACY不建IAM网络资源，缺provider仍保持原失败关闭。公共provider只在已登记出站ServiceCall未声明资源时继承当前已验operation.resource；显式资源及原时效复核保持，不信调用者Actor参数。

测试夹具仅复用admin-common现有test-jar及LoopbackAuthority受限clientCredentials工厂，不引入跨业务服务jar。SecurityConfiguration原IAM启动门未放开，本批不宣称整服务入站已迁完或外部IAM可用。

最多两轮：首轮新装配3例准确3F0E0S（缺Bean）。第二轮原计划公共4+skill9，公共实际4=1P0F3E：本机沙箱拒绝JDK loopback连接，fresh XML2/native1/source稳定；Maven因此跳过依赖它的skill，9项未执行。未重复公共4项。在获运行权限的本机loopback窗口，完成第二轮尚未执行的skill新3+原ServiceTickets6，共9P0F0E0S/native0/fresh XML2/source稳定。不是第三轮重跑同组；两份原件均保留，公共3E没有改写成通过。

新增3例实际覆盖容器自动发现、真实签名exchange/authorize/forward及独立engine peer核权、伪造Actor不改变主体、缺上下文/未知路由/撤权在业务发送前拒绝、容器销毁后精确503/IAM_AUTHORITY_UNAVAILABLE且无网络请求、LEGACY不建IAM Bean及非IAM部署拒绝。旧目录null资源断言保持。ROOT一次轻量非作者检查无阻断，未放宽断言。

执行原文在私密`.local/ex26-authority-run/run-first-20261030-01.ps1`、`run-second-20261030-01.ps1`和`run-skill-second-20261030-01.ps1`，各目录保留command/native/XML/summary。公共源及test-jar先以当前源-DskipTests install，最终目标是mvn -pl skill-registry test，明确-Dmaven.test.failure.ignore=false；不安排追加测试。

当前子步“装配已实现、目标9项已验”，整卡仍进行中；公共网络控制组、全部服务入站/其他出站、真实IAM和正式业务联合待验。回滚agent.skill.service-ticket-mode=LEGACY或反向本批源差异，保留现有IAM安全门和历史事实。
