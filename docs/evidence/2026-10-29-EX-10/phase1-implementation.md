# EX-10 Phase 1：能力包声明登记（2026-10-30）

已实现 app-management 内部登记入口；完整 EX-10 继续进行中。仅保存声明，明确 UNRESOLVED / NOT_EVALUATED / appCreated=false，不创建应用、技能或可执行路线。

- 四类、V108与冻结schema原字节资源；不改AppService、Skill、Run、网关或POM。原schema与副本SHA均0456799D04DEB57DFB114D169D8A37C7B57A49CAD3996FEE917A93EFA704A634。
- Actor/EDITOR授权、完整schema/包内引用检查，服务端canonical SHA；PG租户/package/version唯一键，重复摘要回原记录、异摘要409，短事务不覆盖旧声明。
- 拒绝请求绑定/app计划、明文身份/包装凭据、URL明文userinfo与明显凭据查询键，不发外部请求。

最多两轮已用完：首轮 CapabilityPackageImportTest 5F0E0S，准确缺少HTTP handler的404状态；实现后第二轮新增5项加AppDigestTest/AppRulesTest原13项，共18P0F0E0S，native0、3freshXML、七源码前后相同。一次轻量非作者检查发现URL用户信息与递归凭据标记遗漏，已在第二轮前修复；5个测试的旧断言保留并补负例。

真实PG app夹具与MVC处理链；认证上下文由fixture注入，不宣称JWT验签或网关正式入口。原始run位于本仓忽略目录.local/ex10-package-intake/intake-{red,green}-20261030-01，不提交raw日志/XML、数据库数据或凭据。

接续：Phase 2真实已发布技能/注册表绑定，Phase 3应用草稿、发布/run快照和统一网关路由，随后实际HTTP/MCP/PROCESS完整运行；当前内部登记成功不替代这些出口。正式服务包未因本轮测试而重建/部署。
