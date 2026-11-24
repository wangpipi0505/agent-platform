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

## 统一 IAM 通道、纯 FLOW 候选与发布预检

本分支已合入 R-39 应用授权判定及会话能力。网关使用已有 authority-service 为评测和发布预检换票，业务软件接入见 [统一授权通道](../../../contracts/business-platform-access.md)。

- 修复前发布预检使用普通构建资源，应用级预检用例收到 403。修复后网关、app 与 monitor 之间使用按应用编码绑定的评测证据委派。monitor 拒绝拿该委派读取其他应用，app 的发布环境门另用 PostgreSQL 回归覆盖报告匹配与阻断。
- 修复前 engine 拒绝 monitor 转发的候选委派。V232 使纯 FLOW 候选按草稿修订独立保存授权、材料范围和原委托引用。PostgreSQL 实跑由网关用户委派经 monitor 进入 engine，后台续用原授权跑到 `SUCCEEDED`，用例重放返回同一运行 ID，改输入返回 409。
- 真实 authority-service HTTP 回归覆盖换票、monitor 判权、转发、engine 受理与记录同一引用、原授权续票；修改草稿修订的续票返回 403。
- 门户查询及恢复保持原报告身份，monitor 在继续执行前通过 app 复核当前应用构建权限。前端传回原报告的冻结候选元组，恢复沿用同一报告和用例。

```powershell
# 设置隔离 PG 测试库，engine 专用库名为 agent_platform_r37_eval。使用 JDK 21。
mvn -B -ntp -pl engine-runtime,authority-service,platform-gateway,app-management,monitor -am '-Dtest=EvaluationRunAuthorityTest,EvaluationRunPostgresTest,ExchangeContractTest,EvaluationEnvironmentGateTest,GatewayIamPortalHttpTest,GatewayIngressBoundaryTest,EvaluationCandidateReadTest,EvaluationDelegationTest,EvaluationRulesTest,EvaluationAssertionsTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 从 web 执行
npm exec -- vue-tsc --noEmit
npm exec -- vitest run tests/evaluation-observer.test.ts tests/portal-auth.test.ts tests/navigation-policy.test.ts
```

Java 58 例和前端 53 例通过。 [输出摘录](iam-candidate-result.txt)。

## CHAT 两轮与授权换代

- V233 在原运行上追加授权代次。隔离 PostgreSQL 回归证明 grant 到期后，变更输入重放返回 409，原请求重放保留 runId，原授权仍在，活跃代次更新后运行达 `SUCCEEDED`。
- 两轮 PostgreSQL 回归验证会话与原候选绑定、第二轮模型材料包含已成功第一轮的输入和回答及来源摘要、重放返回同一 runId。实际 authority HTTP 验证模型读材料后，engine 仍可依据同一 grant 的执行决策继续推进。
- 本机运行 authority、gateway、app、monitor、engine 和 model 的 Spring Boot 服务，使用 PostgreSQL 16.15、Redis 8.8.0、已有 user-mgmt 18072 主体代查及 authority 测试档 RS256 门户票。模型响应由独立回环测试端按收到的请求生成。未部署 E2，未执行发布提交。
- 应用 `r37chat61c680a6` 走 `POST /admin/evaluations` 和报告恢复。两轮均达 `SUCCEEDED`，报告达 `ACCEPTED`。走网关发布预检返回 `passed=true`，同报告的评测门禁和评测环境均为 `PASS`，证据摘要一致。 [联合验证结果](joined-http-result.txt)

```powershell
# JDK 21，分别配置 AGENT_R37_ENGINE_DB_URL 和 AGENT_DB_URL 到隔离 PostgreSQL 测试库。
mvn -B -ntp -pl authority-service,platform-gateway,app-management,engine-runtime,model-gateway,monitor -am '-Dtest=EvaluationRunAuthorityTest,EvaluationRunPostgresTest,ExchangeContractTest,EvaluationEnvironmentGateTest,GatewayIamPortalHttpTest,EvaluationDelegationTest,EvaluationRulesTest,EvaluationAssertionsTest,ServiceAuthorizationTest#selectsTheServiceTrustChainForExactPortalDelegates' '-Dsurefire.failIfNoSpecifiedTests=false' test
docker compose -f deploy/compose-test.yaml config --no-interpolate --quiet
```

本机 HTTP 联验的响应链依次为 `GET /auth/session`、`GET /admin/apps/{code}`、`POST /admin/evaluations`、`POST /admin/evaluations/{id}/resume`、`POST /admin/apps/{code}/publish/precheck`。联合测试使用的环境、请求脚本和原始日志保存在本工作区 `.local/r37-joined/`、`.local/r37-*.ps1` 及本证据目录的 `raw-r37-*.log`。

最终定向回归通过 54 例，另有应用发布环境门禁 2 例通过。Compose 配置检查通过。
