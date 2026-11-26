# R-49 模型失败终态常规回归

开发提交 `73118057`，测试类为 `cn.agentplatform.engine.infra.ModelFailureTaskPostgresTest`。用例走已签票的 TaskController、正式受理事务、RunService、模型 HTTP 客户端和任务分发器。运行、尝试、租约和任务投影使用 PostgreSQL。模型端口由 JUnit 在本机动态分配。应用发布快照和签票服务使用隔离夹具。

| 场景 | 注入位置 | 必须成立的事实 |
| --- | --- | --- |
| 已确认的供应商 503 | 模型服务先前已记录失败，在已经打开的 SSE 连接交付 `failed` 终止帧及 `SYS-MODEL-503` | 任务详情与结果均为 FAILED、同一原因、retryable=false。持久调用为 FAILED，实际模型端收到一次请求。 |
| 已发送后失去终止回执 | 模型端收到请求后截断事件流。第一代 worker 结束，调用仍为 DISPATCHED。在本次运行的节点租约到期后启动新的 worker。 | 恢复代次递增。详情与结果均为 FAILED、MODEL_RESULT_UNKNOWN、retryable=false。调用事实为 UNKNOWN，实际请求数和数据库 SDK 尝试数均为 1。 |

受控 503 使用模型服务向引擎交付的 SSE 协议。供应商 HTTP 503 到模型失败帧的跨服务观测见 [R-48 实测记录](../2026-11-26-R-48/README.md)。

## 红绿对照

修复前采用 `23a4a4c6` 的 engine-runtime 源码，加上与当前提交相同的 R-49 测试和签票测试夹具。两条测试均复现错误，运行留在 RUNNING。当前开发提交上两条均通过，两个任务均已落为 FAILED。回环签票夹具对门户任务使用绑定应用发布版本的原始授权；生产鉴权代码没有变更。

- [修复前 JUnit 输出](before-r48-junit.txt) 和 [修复前数据库行](before-r48-rows.json)
- [当前 JUnit 输出](current-junit.txt) 和 [当前数据库行](current-rows.json)

## 复跑

Java 21，在代码根目录执行。准备独立测试库，并设置 `ENGINE_PG_TEST=true`、`ENGINE_PG_URL`、`AGENT_DB_USER`、`AGENT_DB_PASSWORD`。第一条命令安装当前提交的公共模块和供引擎使用的测试夹具。回归用例由 Surefire 自动发现。

```sh
mvn -pl admin-common -am install -DskipTests
mvn -pl engine-runtime test -Dtest=ModelFailureTaskPostgresTest
mvn -pl engine-runtime test
```

完整套件使用新的 PostgreSQL 16 实例，回环端口为 25432、35432，Redis 7 使用 26379。除上面的 PG 变量外，还设置项目已有的 `AGENT_DB_URL`、`AGENT_REDIS_PASSWORD` 和 `AGENT_R37_ENGINE_DB_URL`。后者指向单独的 `agent_platform_r37_eval_*` 测试库。独立 IAM、流程材料、运行统计用例使用各自命名的测试库。

## 全套门槛与整卡状态

R-49 的两条用例在定向执行、相邻回归和全套自动发现中均通过。整套共 1188 项，1032 项通过、45 项断言失败、108 项执行错误、3 项跳过。按用户决定保留全套通过门槛，R-49 保持进行中。36 个本次失败类也出现在首次运行的失败清单中。[完整套件逐类输出](full-suite.txt) · [历史失败单独跟踪](engine-suite-follow-up.md)。

在独立测试库上复跑 R-49、评测与门户会话、任务结果读取及调度器四类，共 30 项通过、零跳过。其中 EvaluationRunPostgresTest 在整套有一次 JDBC 建连错误，底层异常为 java.net.BindException，单独运行的三个用例均通过。[相邻回归输出](adjacent-tests.txt)。

```sh
mvn -pl engine-runtime test -Dtest=ModelFailureTaskPostgresTest,EvaluationRunPostgresTest,IntegrationTaskReadPostgresTest,RunSchedulerPostgresTest
```
