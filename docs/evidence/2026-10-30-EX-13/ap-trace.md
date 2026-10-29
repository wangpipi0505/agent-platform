# EX-13 AP 请求透传与冻结 worker trace

公共小Scope保存/恢复MDC；RunService仅在legacy及已验IAM worker边界绑定本run冻结trace，不替代owner/material权限。RuntimeClients出站与异步发布读传当前trace，SkillAdapters HTTP最后set单一头。历史缺值不写回，身份票据、超时预算和业务载荷不变。

两轮：

- 首轮 `CrossServiceTraceTest` 2 FAIL，nativeExit=1、新XML、源稳定；真实loopback缺头和复用worker未绑定冻结trace。`.local/ex13-trace/trace-ap-first-20261030-01/`。
- 第二轮先仅构建新common（skipTests），再两模块 `CrossServiceTraceTest,PromptFreezeDigestTest,OpenApiHttpMappingTest`，nativeExit=0，3新XML共13 PASS（2+4+7），源稳定。`.local/ex13-trace/trace-ap-second-20261030-01/`。收集器误预计11，漏算原HTTP映射的两个参数用例，原targetedGate=false保留；post-analysis.json直接核13个当次XML记录，不重跑、不改原测试或结果。同轮原契约门禁0。

ROOT一次非作者轻量检查无阻断。新例使用真实PG scheduler/claim/commit与独立拥有线程，验证回收复用、异常恢复及历史不回填；HTTP为真实loopback协议peer。旧HTTP映射7项是控制，不能替代同一组正式gateway→engine→skill→OR业务联验；IAM保留校验路径但不宣称外部IAM真验。

范围4生产、新测试、原工程契约和本记录；回滚本地提交撤透传/Scope，原持久事实不改。OR请求边界另仓提交；其执行UUID及请求trace落库关联仍单列，整EX-13不冒称全闭合。
