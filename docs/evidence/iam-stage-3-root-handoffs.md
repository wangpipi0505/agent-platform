# Task3 实施接缝裁定

这是root在原批准任务范围内对具体依赖的裁定，不是测试或独立审查结论。Task3完整要求见iam-stage-3-implementation-brief.md。

1. 允许专责AuthorityResponseVerifier校验决定/完成permit的独立typ、nonce和签名绑定；将TrustedJwtDecoder既有严格JWKS解析原样提取为包内static parseKeys(byte[])供双方复用。普通decoder的用途隔离、≤8公钥、重复字段/禁止私钥字段、RS256及错误语义不变，仍走同一BoundedIamHttp，禁止第二HTTP客户端绕过有界传输。
2. 允许提前实现原§2.5精确common纯类型MaterialRef、MaterialScopeV1、AuthorizedMaterial、MaterialAuthorization来支撑Task3 RR1。真实PG/schema/store/业务消费点仍属后续任务，不能因此宣称Task5已完成；避免先造临时无类型scope再重写。
3. ServiceTokens.issue(Actor)默认及iam拒绝。静态system property可作为当前显式dev/test兼容接缝；dev-hmac必须dev-auth=true，test须显式test profile，不能从密钥存在/测试类名推断。测试finally还原全局属性；不放松生产启动闭锁。Spring配置与静态入口的模式来源由Task4统一迁移，不让局部组件通过掩盖该依赖。integration保留独立用途与≤300秒，不得成为service fallback。
4. 原计划§2.1/2.2允许原integration短票据作为交换材料，只传固定authority。Task3仅在配置gateway且integration.enabled的明确分支使用它；authority侧必须真正验证独立integration签名、用途、期限和credential/app/biz/user/policy，并通过独立HTTP resolver核当前政策。普通TrustedJwtDecoder不接HMAC；实际gateway入站验证/生产桥接留Task4。不能捕获portal签名/JWKS错误后降级另一分支。客户端核签回ticket/decision的nonce、原subject-token摘要/主体/资源/来源及集成绑定，原票据不发业务下游。fixture用独立实例临时HMAC密钥，覆盖篡改、跨用途、其他client、resolver失败/旧版本/inactive；不把未验decode当信任，不读取真实密钥，不自建生产IAM。

上述修改应在作者完整patch、行为测试、实际开源对照和报告中交证据，并交独立spec/quality复核；尚未签收Task3。
