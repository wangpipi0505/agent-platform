# R-40 集成短票过期与白名单审计

基线通过正常 IAM 出票后请求白名单外任务操作，网关返回 403 `SYS-AUTH-403`。修复后同样的有效短票请求 cancel 和 resume，均返回 403 `APP_BINDING_DENIED`，两次拒绝各有一条 `AUDIT INTEGRATION_PATH_FORBIDDEN` 记录。审计保留凭据编号、HTTP 方法、路径 SHA-256 和 traceId，实测网关日志无测试 ApiKey 原文。

过期分支在 authority 签名、集成票类型和受众校验后识别。使用真实短票等待到 `exp`，调用发布版输入模式返回 401 `INTEGRATION_TICKET_EXPIRED`。另一张尚未过期的票在档案撤销后立即返回 403。正确短票继续返回 200，跨应用和无运行角色继续返回 403。

此处的白名单审计由网关记录。任务受理、详情、事件、同软件管理员中断与可从业务审计查询到的运行记录仍待接线。

## 策略版本复核

在本机网关、authority-service、app-management 和 PostgreSQL 联验。递增凭据的 `policy_revision`，保持策略摘要不变。修复前，旧短票仍可换票并读到 HTTP 200。修复后，旧短票换票返回 403，修订前已换出的服务票从应用管理直接读也返回 403。用同一 ApiKey 重新出票，RS256 票内策略版本由 `<credentialId>:1` 变为 `<credentialId>:2`，策略摘要一致，读取恢复 HTTP 200。

JDK 21 在 admin-common、platform-gateway、app-management 和 authority-service 共执行 72 条定向测试，零失败、零跳过。覆盖签发主体、错误凭据版本、PostgreSQL 当前策略及服务调用。原始运行结果位于本地 `.local/r40-policy-version-green.log` 和 `.local/r40-policy-version-package.log`。

旧版策略解析 HTTP 夹具补齐 PlatformAccessService 装配后复跑，仍保留台账已登记的 7 条失败。六条使用旧的 integration → engine run.status 换票路径，一条使用旧的 app-version 资源执行凭据签发。详见本地 `.local/r40-policy-version-legacy.log`。

## 集成任务读取

网关、authority-service、app-management 和 engine-runtime 使用隔离的 PostgreSQL 联验。Flyway 执行 V234。测试向 PostgreSQL 写入带接入归属的任务 `task-82c7e7ed0a324be2844b` 和一条事件，用真实 RS256 集成票访问网关，再由引擎复核当前凭据版本。

| 断言 | HTTP |
| --- | --- |
| 所有者读详情、增量事件及结果信封 | 200、200、200 |
| 同一凭据直连 ApiKey 读详情 | 200 |
| 增量查询重复 limit | 400 TASK_QUERY_INVALID |
| 缺受理归属、跨应用、跨业务系统、跨租户、标签不足 | 403 APP_BINDING_DENIED |
| 同软件 AGENT_ADMIN 读他人正文，添加平台 ADMIN 后复验 | 403、403 |
| 调用未开放的 resume | 403 |

三个读取动作签入同一凭据应用和精确任务业务号。引擎按验签后的租户、主体、应用、业务系统和标签检查 PostgreSQL 归属。JDK 21 定向回归执行 66 条，零失败、零跳过，首次混合 JVM 运行触发 IAM 与开发票模式冲突，按测试类隔离进程复跑通过。

导出输出见本地 `.local/r40-task-read-live.log` 与 `.local/r40-task-read-package.log`。本批验证读取链及权限拒绝。集成提交时写入归属、启动和续权、同软件中断与业务审计仍待接入。

## 同软件管理员中断及业务审计

Flyway 执行 V235，使用本地真实网关、authority-service、应用管理、引擎、monitor 与独立 PostgreSQL。任务与运行预置为 RUNNING，接入归属来自夹具。平台审计门户票使用本地身份目录夹具验证，集成票通过现有 ApiKey 出票和实时策略复核。

| 断言 | 实测 |
| --- | --- |
| AGENT_VIEWER 同时有平台 ADMIN | 中断 403 |
| 跨应用、跨租户、跨软件且直传伪造软件属性 | 三次中断 403，运行保持 RUNNING |
| 同软件 AGENT_ADMIN 中断他人运行及重复请求 | 两次 200，响应仅任务号、状态 |
| 中断后读取他人任务与请求恢复 | 两次 403 |
| PostgreSQL 终态 | 任务、运行、步骤均 CANCELLED，派发 DONE |
| 审计接口 | 有 audit.read 返回 200，可查成功和跨软件拒绝。无权限返回 403 |

任务 `task-86fcd50414294f5e9143`，运行 `r_86fcd50414294f5e9143457694124597`。成功中断与业务审计同事务提交，重复调用后成功审计仍仅一条。原始响应状态见 `task-control-http.txt`。

JDK 21 定向回归 81 条全过，零跳过。首次运行复用旧引擎库触发 Flyway V235 乱序，使用新建专用库复跑通过。策略解析夹具改用现行元数据动作和平台凭据签发授权后，原登记的 7 条异常全部消除，`IntegrationPolicyResolverHttpTest` 20 条和真实库迁移 1 条全部通过。完整命令输出位于本地 `.local/r40-control-package.log`、`.local/r40-control-gateway-normalize.log`、`.local/r40-policy-resolver-full.log`。

集成任务提交、受理时归属落库和执行续权仍待接入。当前中断功能通过已有关联运行与接入归属的任务联验。

## 集成任务提交至终态

独立 PostgreSQL 执行 Flyway V236。启动本机正式网关、authority-service、应用管理、引擎、model-gateway、monitor 和 skill-registry，门户登录使用本地身份目录夹具，模型使用经回环 HTTP 提供的 OpenAI 兼容夹具。经评测、发布预检及发布后，使用接入档案 `software-a` 的真实 RS256 集成票提交新任务。数据库中的任务、运行、接入凭据归属和派发来自实际提交，无预置任务。

| 断言 | 实测 |
| --- | --- |
| AGENT_VIEWER 提交及跨应用提交 | 两次 403 |
| 提交 PLAN 或申请工具授权 | 两次 403，工具请求返回 INTEGRATION_PATH_FORBIDDEN |
| 并发相同请求，使用同一请求号改变目标 | 两次 200 且同任务，一次 409 REQUEST_REPLAY |
| 发布版本绑定、任务与运行终态、派发回执 | 一条任务及一条运行，均为 SUCCEEDED，INTEGRATION/software-a，派发 DONE |
| 任务详情、增量事件、终态结果 | 三次 200，结果包含非空模型回复 |
| 引擎在原授权期限内重取调度票 | 策略同版 200，递增 policy revision 后 403，旧短票读结果 403 |

端到端联验首次在模型输入材料读取阶段被拒。冻结输入证明的导出入口仅识别门户来源。补上已验集成来源与存储侧授权同源检查后复跑，保留门户路径与材料约束。JDK 21 定向回归和七服务构建共 86 条用例通过。最后一处材料读取修复后，重新运行引擎及共享模块 28 条，零失败、零跳过。模型冻结绑定另用隔离 PostgreSQL 与本地 TLS 夹具复跑 19 条，全过。联验终端记录见 `task-admission-http.txt`，本地构建日志见 `.local/r40-admission-package-final.log`、`.local/r40-admission-engine-final.log` 与 `.local/r40-admission-model-tls.log`。正式 E2 与真实外部宿主的联验由 R-22、R-23 跟踪。
