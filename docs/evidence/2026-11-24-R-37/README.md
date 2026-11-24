# R-37 monitor 出站委派开发证据

D1 采用用户委派和独立 workload 两条路径。当前开发分支 `r37-evaluation-delegation` 已接入 monitor 向 app 读取候选，以及向 engine 提交评测 run 和 turn 的授权转发。校验目标、端口、目录动作、用户来源和候选绑定，使用刷新后的当前委派。候选根为 `evaluation-candidate`、`<appCode>@<draftRev>` 和候选摘要。

基线为同一候选、有用户授权上下文时仍走旧式出票，实际请求被依赖错误中断。测试在真实环回 HTTP 接收器断言转发票、候选越界、无上下文拒绝。实际凭据签验另由现有 authority 合同覆盖。

JDK 21 执行 `mvn -B -ntp -pl monitor -am -Dtest=EvaluationDelegationTest,EvaluationRulesTest,EvaluationAssertionsTest -Dsurefire.failIfNoSpecifiedTests=false test`，21 例通过。

网关评测入口、engine IAM 候选运行受理及发布预检联合验证待接线。现有 workload 能力目录保持独立。
