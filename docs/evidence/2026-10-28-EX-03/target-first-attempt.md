# EX-03 首轮三类定向：29P/1E，闭环尚未到达

经主代理唯一短窗授权，仅运行 `run-verify.ps1 -Mode Target`；结束即交还 Java/Maven/PG/HTTP 窗口，没有运行Full或追加重试，也没有据此修改产品/测试。

本轮 [target-20261028-232734-622](after/target-20261028-232734-622/command.json) 在北京时间23:27:36.5379650至23:27:49.2318884运行。Maven **nativeExit=1**，采集器保存全部报告后因定向门失败抛错，采集器进程也返回1；两者分别记录，不能相互替代。

新鲜XML3份，实际30方法：29 passed / 0 failure / 1 error / 0 skipped。原21方法均覆盖且通过，新增9方法全部出现；missingBaselineMethods=0、missingRequiredNewMethods=0、sourceDrift=0、xmlIssues=0。collectionComplete=true只说明采集完整，allTestsPassed=false、regressionGatePassed=false，**本轮不放行**。

精确证据：[summary.json](after/target-20261028-232734-622/summary.json)、[native-exit.json](after/target-20261028-232734-622/native-exit.json)、[全部方法](after/target-20261028-232734-622/methods.csv)、[唯一新增非通过](after/target-20261028-232734-622/new-nonpass.csv)、[新鲜XML摘要](after/target-20261028-232734-622/xml-manifest.json)。原始raw/XML本地保留且已忽略默认提交。

## 唯一异常及实际停点

`postgresImportReadPublishAndExecuteKeepsOnlyApiKeyWithLegacyDisabled` 为 `java.lang.NullPointerException`：`submitted` 为null，生产 `SkillExecution.java:315` 调用 `submitted.path("submitted")` 时失败；调用栈来自测试 `executeFixture:470`，再回到首个DEBUG执行调用点`:417`。

源码追踪确认：生产执行链在claim后、真实适配器HTTP前还需 `POST /internal/tool-calls/{callId}/submitted`，并检查响应 `submitted=true`。本卡协议替身只设置get/claim/fact，遗漏该响应，Mockito返回null。这是新测试夹具前提遗漏，不能当作导入器目标红、产品权限错误或远端失败。既有 `SkillExecutionTest.java:162–164`已采用 `Json.tree(Map.of("submitted",true))` 的协议替身模式。

本轮PG正例已越过专库owner/comment准入、Flyway、真实Catalog创建/读回，已执行DRAFT/API_KEY/同opaque引用/ONTOLOGY来源断言及缺连接证据时发布被拒绝断言。随后首个受控DEBUG执行停在上述提交权前置；HTTP头、成功执行事实、治理连接/选型通过、实际发布与发布后执行断言均**尚未到达**，不能凭方法名声称链路通过。

最小建议只在 `executeFixture` 为准确 `/submitted` 路径补 `submitted=true` 协议响应，沿用既有测试模式；不修改SkillExecution或放宽提交权规则。已将技术核实交主代理，当前仍遵守本轮“失败后不未经汇报改产品/测试”约束，测试SHA保持v2不变，待明确写窗后再处理。

## 前提与源码保持

[数据库前提](after/target-20261028-232734-622/database-precondition.json) 再核本仓127.0.0.1:25432/PID15204、V:数据目录及已建专库准确owner/comment，本轮不创建/清理数据库。非秘密环境标志与依赖摘要见 [environment.json](after/target-20261028-232734-622/environment.json)，仍AGENT_DEV_AUTH=true、ENGINE_PG_TEST=true、AGENT_SECURITY_MODE未显式设置。

[三Java指纹](after/target-20261028-232734-622/source-sha256.json) 与已审候选一致，模块完整源摘要亦留存。此次没有源码漂移或其它候选变更。v1/v2真红保留，来源400仍仅是此前源码核实，不补造中间红。
