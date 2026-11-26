# R-45 材料摘要诊断与重冻结

E2 原样本 task-0a6ffe07e33b45c8bafc 已受理。只读核对显示其运行引用与库中冻结绑定摘要相等。授权日志给出材料读取 permit，模型客户端在 HTTP 回读引擎材料阶段失败。现有现场信息不足以确认是重启改变了摘要，或确认哪一项本地修复能消除该次故障。

已拆开材料缺失、材料摘要不符和缺少冻结绑定。引擎返回经过验证的原始持久化清单，保留签名时数组顺序。授权层分别保留原授权的到期与当前操作的较短窗口，消费侧继续按较早截止时间执行。后台服务不自动重冻结。

`ModelInputMaterialHttpTest` 在本机 PostgreSQL 与可信测试 HTTPS 端点执行 12 项，包含读取已有清单、数组顺序变化、缺失与损坏。模型侧 `FrozenModelBindingVerifierTest` 19 项通过。app 的 `ModelRefreezePostgresTest` 1 项通过，核对原版本、同请求号重放、冲突及审计。`DelegatedForwardContractTest` 4 项及 `DelegatedResumeContractTest` 8 项通过。测试使用独立回环夹具，未调用真实供应商。

```text
mvn -pl app-management -am -Dtest=ModelRefreezePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl model-gateway -am -Dtest=FrozenModelBindingVerifierTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl engine-runtime -am -Dtest=ModelInputMaterialHttpTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E2 待部署后提交 r22e2-real-v2，到 SUCCEEDED 后读取 result，并在受控重启 model/engine 前后核对同一运行、材料及冻结引用。正式真实模型通过后再判 R-23 结果和文件下载。
