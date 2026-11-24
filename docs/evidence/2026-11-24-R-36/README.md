# R-36 单步 AGENT 任务执行验证

日期 2026-11-24。开发分支 `r36-agent-task-execution`，契约提交 `7d290ce4`。

使用 JDK 21 和隔离的 PostgreSQL 16.15。先在真实引擎 HTTP 端口提交 AGENT 任务。受理返回 200，状态仍为 ACCEPTED，关联分派记录实际 0 行。失败基线见 `baseline-result.txt`。

```text
mvn -B -ntp -pl engine-runtime -am -Dtest=TaskEventsSseRealPortTest#acceptedAgentRequestCreatesAnEntryDispatch -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl platform-gateway,engine-runtime -am -Dtest=TaskEventsSseRealPortTest,TaskDispatchTest,StepExecutionMappingTest,TasksApiTest,TaskConfigReleasePinTest,GatewayIamPortalHttpTest,RunAdmissionServiceTest,ServiceAuthorizationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -pl engine-runtime -am -Dtest=TaskEventsSseRealPortTest -Dsurefire.failIfNoSpecifiedTests=false test
```

定向与相邻测试共 68 例通过，最后回归真实引擎 HTTP 4 例通过，零失败、零错误、零跳过。初次复用的试验库含前一轮回归留下的节点和模型事实，`StepExecutionMappingTest` 清理被外键拒绝。正式复验使用新建数据库。

- 从真实引擎 HTTP 入口提交任务，使用固定模型响应夹具，生产运行调度器拾取执行。观察到 SUCCEEDED、分派 DONE、终态事件、持久输出和原任务重放。成功运行无 NODE_FAILED，重放未增加模型调用。
- 模型调用阻塞期间取消，任务和关联运行均为 CANCELLED。解除阻塞后，同号重放仍为取消，未启动新模型调用。
- 真实网关 HTTP 端口与测试接收端验证门户任务 POST 先查应用发布元数据，再换发绑定应用版本和摘要的服务票，既有运行集合负向边界继续成立。
- 实际 E2 环境的门户、引擎与模型联合验证待执行。已关联会话、计划逐步执行和跨任务依赖不属于直接单步交付。
