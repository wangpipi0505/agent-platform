# 实际 MVC 观察超时修复

2026-10-20，root 根据 `frontend-stream-live.md` 中真实服务两端首 SSE 约 30 秒结束的证据排查。业务仍完成且能重连，但没有执行到 RunController 55 秒的预期观察上限。

原因是生产 Boot MVC 没有显式 async timeout；既有 RunEventsHttpTest 的独立 HTTP fixture 自己设置了 60000，未能代表生产配置。修复只在 engine `application.yml` 设置 `spring.mvc.async.request-timeout: 60s`，为 55 秒有界 writer 留余量，仍保留有限上限。没有调整模型调用、业务预算、取消或重试策略。

新增 EngineContextPostgresTest 用真实 Boot 上下文和 MVC handler 启动测试 Callable，直接检查其 Servlet AsyncContext 的有效 timeout 大于 55000；fixture 本身没有覆盖 async timeout。测试控制 latch 在 finally 释放，不等待 55 秒、不会残留工作线程。原真实仓储/调度器测试保留。测试接口只在 TestConfiguration，不进入生产 JAR。

| 执行 | 结果 |
| --- | --- |
| `.local/engine-mvc-timeout-red.log` | 05:43:16；2 项、1 失败，明确有效 timeout 未超过 55 秒，0 错误/跳过 |
| `.local/engine-mvc-timeout-green.log` | 05:43:50；2/2 通过 |
| `.local/engine-mvc-timeout-final-green.log` | 格式化后，EngineContextPostgresTest 与 RunEventsHttpTest 合计 3/3，0 失败/错误/跳过，05:44:42 BUILD SUCCESS |

命令：先加载独立 `.env` 至进程、Java21、ENGINE_PG_TEST=true；`mvn -B -ntp -pl engine-runtime test -Dtest=EngineContextPostgresTest,RunEventsHttpTest -Dmaven.repo.local=<本工程>/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`。无 `-am`，没有与技能阶段共写 target。

冻结 SHA256：

- `engine-runtime/src/main/resources/application.yml`：`e6b3926d23be538ca17e9d87dd3115bb99d936ec59fcf2a2afa3f881a4ccd38d`
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineContextPostgresTest.java`：`b17c7f38f8f28ec89bbc79eb626927a2233524fd39cb499b06d0ad11ae52f575`

366 项整轮是修复之前的固定阶段，不将新增测试与该整轮混称 367 项。定向独立复核已追加于 review-engine-streaming.md：两个 SHA 匹配、生产配置与实际 Boot 回归有效、测试探针不进入产物、55 秒 writer 和 permit 生命周期未改。05:46:31 完成构建后启动本工程 engine PID57412，健康检查 UP，部署日志 `.local/engine-mvc-timeout-reviewed-deploy.log`。真实 65 秒模型协议 fixture 与 Chrome 两端正在重新执行，完成前不确认首 EOF 已达到约 55 秒。原 30 秒失败证据保留。

后续实际复验已完成：`frontend-stream-live.md` 的最终绿证据记录 WebApp/SDK 首EOF分别55018/55017ms，均重连一次、最终SUCCEEDED，且只有一个run/turn；65秒提供方fixture和实际Chrome均结束关闭。这关闭本报告的首EOF实网复验项，保留上述发现时状态；不据此签收完整容量或生产宿主集成。
