# R-33 DIRECT 评测提示词复验

2026-11-25，本地正式 Spring Boot 服务经 IAM 网关完成两轮 CHAT 评测。隔离 PostgreSQL 存储候选和报告，模型响应来自回环测试端。POST 评测返回 200，最终报告 `er_d4d32e55-1d22-42a3-86b8-0795fcb001b3` 为 ACCEPTED，随后 GET 报告返回 200。

内联提示词原始 UTF-8 内容为 `Answer the evaluation fixture. 校验内联提示词。`。从客户端字节计算的 SHA-256 与报告 `promptTemplateRef.promptSha256` 相同，报告 `source` 为 DIRECT。

```text
7e3429804e6d365000d4e8e639af361d7db22533a4d4d574340c4f67cf378f08
```

`9e0beb74` 已是 `upgrade/20261103` 的祖先。在 `c7deaaf6` 独立源码快照运行 JDK 21 和隔离 PostgreSQL 后，monitor 全量 71 例通过，零跳过。台账开发分支在隔离库复验包含委派扩展的 73 例，零跳过。复验串行执行，避免两个工作版本争用测试使用的固定租户。

```powershell
mvn -B -ntp -pl monitor -am '-Dtest=cn.agentplatform.monitor.**.*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

数据库测试使用独立监听端口 25432 的 `agent_v12` 账号，数据库包括 `agent_platform_v12` 和 `agent_platform_statistics_test`，由测试执行 Flyway。联验脚本和原始输出位于本工作区 `.local/r25-r33-verify.ps1`、`.local/r25-r33-verify.log` 及 `.local/r33-main-monitor.log`。

E2 真实应用提交和报告读回复验待执行。
