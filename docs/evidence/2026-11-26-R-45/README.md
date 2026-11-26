# R-45 材料摘要诊断与重冻结

## 冻结候选实测

[同一任务的重启前后结果](e2-task.json) 返回 SUCCEEDED，result 与产物均可再读。另对该 run 在第二次 [model/engine 重启](e2-restart.txt) 前后分别只读采集可核验材料、发布摘要及冻结绑定。两份文件字节相同，均含 43 条材料记录，SHA-256 为 3ac9e72557260900e13363c2fcd00319f8b83d074367be2c12d37a24e660be00。见 [重启前](e2-material-before.txt) 与 [重启后](e2-material-after.txt)。

E2 上用冻结字节码、独立 PostgreSQL 16 和回环夹具执行 [20 项契约回归](e2-isolated-regression.txt)，覆盖 BINDING_NOT_FROZEN 和 MATERIAL_DIGEST_MISMATCH。另用 [404 材料夹具](e2-isolated-missing.txt) 检查客户端确实返回 MATERIAL_MISSING。缺失或损坏材料的负向验证在隔离环境完成。

## 切换前记录

E2 原样本 task-0a6ffe07e33b45c8bafc 已受理。只读核对显示其运行引用与库中冻结绑定摘要相等。授权日志给出材料读取 permit，模型客户端在 HTTP 回读引擎材料阶段失败。现有现场信息不足以确认是重启改变了摘要，或确认哪一项本地修复能消除该次故障。

已拆开材料缺失、材料摘要不符和缺少冻结绑定。引擎返回经过验证的原始持久化清单，保留签名时数组顺序。授权层分别保留原授权的到期与当前操作的较短窗口，消费侧继续按较早截止时间执行。后台服务不自动重冻结。

`ModelInputMaterialHttpTest` 在本机 PostgreSQL 与可信测试 HTTPS 端点执行 12 项，包含读取已有清单、数组顺序变化、缺失与损坏。模型侧 `FrozenModelBindingVerifierTest` 19 项通过。app 的 `ModelRefreezePostgresTest` 1 项通过，核对原版本、同请求号重放、冲突及审计。`DelegatedForwardContractTest` 4 项及 `DelegatedResumeContractTest` 8 项通过。测试使用独立回环夹具，未调用真实供应商。

```text
mvn -pl app-management -am -Dtest=ModelRefreezePostgresTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl model-gateway -am -Dtest=FrozenModelBindingVerifierTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl engine-runtime -am -Dtest=ModelInputMaterialHttpTest -Dsurefire.failIfNoSpecifiedTests=false test
```

E2 待部署后提交 r22e2-real-v2，到 SUCCEEDED 后读取 result，并在受控重启 model/engine 前后核对同一运行、材料及冻结引用。正式真实模型通过后再判 R-23 结果和文件下载。
