# R-37 monitor 出站委派开发证据

D1 采用用户委派和独立 workload 两条路径。当前开发分支 `r37-evaluation-delegation` 已接入 monitor 向 app 读取候选，以及向 engine 提交评测 run 和 turn 的授权转发。校验目标、端口、目录动作、用户来源和候选绑定，使用刷新后的当前委派。候选根为 `evaluation-candidate`、`<appCode>@<draftRev>` 和候选摘要。

基线为同一候选、有用户授权上下文时仍走旧式出票，实际请求被依赖错误中断。测试在真实环回 HTTP 接收器断言转发票、候选越界、无上下文拒绝。实际凭据签验另由现有 authority 合同覆盖。

JDK 21 执行 `mvn -B -ntp -pl monitor -am -Dtest=EvaluationDelegationTest,EvaluationRulesTest,EvaluationAssertionsTest -Dsurefire.failIfNoSpecifiedTests=false test`，21 例通过。

## 门户提交与候选读取

IAM 网关只为准确匹配的 `POST /admin/evaluations` 受理用户评测，限定请求体大小，拒绝查询参数、workload 票和无效候选。monitor 与 app 分别在候选调用前和读取前复核绑定。网关正例使用真实 HTTP 监听，校验 monitor 收到的委派主体、动作、候选和原始请求体。app 测试使用授权上下文覆盖缺授权、修订错配和正确绑定。

```powershell
mvn -B -ntp -pl platform-gateway -am '-Dtest=GatewayIamPortalHttpTest#evaluation*' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -ntp -pl app-management -am '-Dtest=EvaluationCandidateReadTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -ntp -pl platform-gateway,monitor,app-management -am '-Dtest=GatewayIamPortalHttpTest,GatewayIngressBoundaryTest,EvaluationDelegationTest,EvaluationRulesTest,EvaluationAssertionsTest,EvaluationCandidateReadTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

入口两例和 app 候选读取一例在实现前复现失败。联合回归共 46 例通过。见 [输出摘录](gateway-monitor-result.txt) 和同目录的 `raw-02-gateway-before.log`、`raw-05-app-before.log`、`raw-06-iam-evaluation.log`。

engine 的受理、存储和恢复仍要求 `app-version` 根。候选 `run` 和 `turn` 需要自己的持久授权绑定。发布预检委派和从真实评测执行到预检证据消费的联合验收待完成。
