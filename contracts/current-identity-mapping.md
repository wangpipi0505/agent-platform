# 当前主体映射接线增补（EX-23本地半段）

主体恒定身份是tenant+principal；既有SubjectSnapshot.mappingKey是包含角色/属性的权限快照SHA，不是永久外部subject。沿原排序/hash算法及PG版本行，不重算历史。

PUT /admin/system/identity-mapping/current显式登记当前已验Actor，沿原EDITOR/ADMIN权限；不接收body指定他人/租户/角色或外部subject，重复相同快照返回原revision。GET /internal/identity-mappings/current仅读取当前已验Actor对应PG行，缺行404/IDENTITY_MAPPING_NOT_FOUND、快照变化403/IDENTITY_MAPPING_CHANGED，不因读而自动覆盖旧行。登记通过实际gateway admin/system代理；catalog只增该PUT及skill→app的精确GET，均delegated且不向integration开放。

已声明PLATFORM_SUBJECT的HTTP同步/异步出站逐次经现SkillClients服务票据读app，不共享数据库、不缓存映射。核tenant/principal/mappingKey、正整数revision及完整集合等价的subject；缺client/映射或快照变化为不可重试本地前置失败，业务HTTP零发送。未声明identity的旧调用保持；无用户/机器上下文沿原声明跳过，不伪造用户头。仅可信本地BeforeDispatchFailure可把本次identity.injected/header.sent置false并写skipReason，远端自报同名码不能伪装未发送。

执行准备：经当前登录网关向上述PUT登记当前主体，成功后才运行已声明身份的工具；授权快照或信任模式改变须确认当前权限后重新登记。不能靠X-User-*头/body补行，不能拿旧行跨scope继续。此入口不提供任意主体管理/外部账号分配。

OR仍只凭已验凭据、空间/端点/数据范围授权，AGENT元数据或X-User头不替代消费账号。外部subject反查、platformAppId对应协议及桥18095真实半段继续由X3输入；本批没有猜造对应表，也不声称双仓同场调用已联验。IAM完整入站门、旧权限hash及主体模型保持。

无新回滚开关；需要回退时反向本批精确源差异，保留PG历史行/版本及审计，不删除或伪造映射。正式服务/真实IAM/外部桥按EX06/07/23/26接续。
