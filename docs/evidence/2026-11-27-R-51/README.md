# R-51 E2 复跑证据

2026-11-27，冻结源码 `489b5a177b6429024378113820ef5b8f97ab5d77`，E2 八个业务容器均运行 `cand-20261127-489b5a17`。

## 八场景结果

| 场景 | 内容 | 原断言数 | 结果 |
| --- | --- | ---: | --- |
| S1 | 直接任务链 | 22 | PASS |
| S2 | 计划修订与执行 | 29 | PASS |
| S3 | 记忆域 | 31 | PASS |
| S4 | 插件发现闭环 | 19 | PASS |
| S5 | MCP 连接 | 19 | PASS |
| S6 | 确认中心 | 23 | PASS |
| S7 | 集成服务 | 20 | PASS |
| S8 | 角色矩阵 | 28 | PASS |

原有 191 项全部通过。另有 10 项应用范围和跨主体断言通过，保存在 `authorization` 字段。四轮结果依次为 177/191、187/191、188/191、191/191。各轮报告和清理回执均按原候选留存。 [第四轮回执](m5-3-e2e-report.json) · [第三轮回执](m5-3-e2e-report-third.json) · [第二轮回执](m5-3-e2e-report-second.json) · [首轮回执](m5-3-e2e-report-first.json)

| R-51 条目 | 同轮正向及负向证据 |
| --- | --- |
| 任务控制 | 本人取消后任务与关联 CHAT 运行均 CANCELLED，重复取消版本一致。其他主体取消与恢复都返回 403 TASK_OWNER_DENIED。 |
| 记忆明细 | 本人 GET/PATCH/DELETE、CAS 及删除校验通过。他人私有条目删除返回 403。 |
| 确认中心 | ADMIN 和授权 BUILDER 可读计数，USER 可读本人待办。USER 管理计数及 BUILDER 范围外计数均返回 403。 |
| 部署资源清单 | ADMIN、BUILDER、OPERATOR 在应用授权内可读。USER 和范围外 BUILDER、OPERATOR 均返回 403。 |
| 会话诊断 | OPERATOR 可读本人会话诊断。USER 无诊断权限，他人会话仍返回 403。 |
| 集成撤票 | 过期和主动作废后的旧票返回 401。未登记 Origin、跨应用受理等返回 403。 |

## 局部回归

最终候选运行 116 项定向测试，全部通过。由本地隔离 PostgreSQL 提供业务数据库与材料夹具库。

```text
mvn -o -B -ntp -Dmaven.repo.local=.tools/m2 -Dsurefire.failIfNoSpecifiedTests=false -pl platform-gateway,app-management,engine-runtime -am -Dtest=GatewayIamPortalHttpTest,PortalTaskRoutesTest,PortalSessionRoutesTest,PlatformBuildRoutesTest,PlatformBuildEntryTest,PortalSessionAuthorizationTest,ConfirmationCenterTest,TasksApiTest,TaskEventsSseRealPortTest,RunAuthorityLocalCurrentPostgresTest,FlowMaterialScopePostgresTest,InitialMaterialScopePostgresTest test
```

## 部署与隔离

- 冻结分支和标签确认同一源码，八镜像 ID 和前端资产摘要逐一比对。基础 Compose 一致，部署前数据库备份可列目录。
- 门户健康和控制台均返回 200。Flyway 迁移全成功，六个 Prometheus 目标 UP，三条接入档案已批准，三条原有效凭据仍有效。真实门户 DeepSeek 探针 `r_d429a182335d4663b0c64a2aa2d61250` 返回预期的 32 字符，探针票撤销 200。
- 八场景以真实 IAM 门户主体运行，使用现有 DeepSeek 和独立 PostgreSQL、Redis。隔离库核对任务和关联 CHAT 运行终态、分派完成、取消事件与材料均已落库。两张临时门户票撤销 200，临时目录密钥及隔离服务已清理。主库测试任务和临时集成凭据均为 0。
- R-51 具备待验收条件。R-22 正式发布门禁待核对，场景签认仍待验收方办理。

[冻结](source-freeze.txt) · [部署](e2-deploy.txt) · [环境自检](e2-selfcheck.txt) · [模型探针](deepseek-probe.json) · [隔离和清理](e2-isolation.txt) · [本地定向回归](local-regression.txt)
