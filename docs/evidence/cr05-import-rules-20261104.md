# CR05 R1/R4 纯来源规则编译记录

日期：2026-11-04。范围：仅新增 `app-management/src/main/java/cn/agentplatform/app/application/OnboardingImportRules.java`。

`prepare(document, preview, declaration, routePlan, selectedOperationIds, connectionFacts, manualOverrides)` 只接收真实 OpenAPI 预览、冻结包声明、批准连接事实及显式人工字段；六参重载使用空人工字段并默认拒绝缺项。输出 importer 的 `defaults`、`overrides`、`includeOps`，以及供严格回读比较的 `operations` 和另存的 `provenance`。本子集仅接受无固定 skillRef 的同步只读 HTTP，端点、Schema、guard、执行及返回约定须精确一致，不补造 Schema 或业务语义。

`mapping(declaration, routePlan, preparedOperation, actualVersion)` 先核验真实 definition 的原生 OPENAPI 五字段来源，再核验受控合同；只在用于旧 `CapabilityBinding.mapping` 的比较副本移除其本体方言 `operation` 字段。原始 tool、真实 definition 与 OPENAPI 来源不改写。不改 `CapabilityBinding`、已交付 `OnboardingPlan`、session 服务或技能导入服务。

验证：JDK 21 `javac -encoding UTF-8 --release 21 -cp <已有隔离依赖类路径> -d D:/CodexPrep/algorithm-reorg/cr05-import-rules app-management/src/main/java/cn/agentplatform/app/application/OnboardingImportRules.java`，退出码 0。类路径读取 `D:/CodexPrep/algorithm-reorg/cr05-plan-rules/classpath.txt`，编译日志 `D:/CodexPrep/algorithm-reorg/cr05-import-rules/compile.log` 无诊断。未写共享 Maven target。

按本轮上限要求未执行新功能测试；本记录仅证明当前类编译成功，不表示导入、数据库、授权或端到端链路通过。
