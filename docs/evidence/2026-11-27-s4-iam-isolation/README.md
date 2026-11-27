# S-4 真实 IAM 八场景

候选 `cb378202`，E2，2026-11-27。八场景均实际执行，3 场景通过，191 项断言中 152 项通过、39 项失败。失败断言包括入口拒绝后的投影缺失。

| 场景 | 断言 | 现场结果 |
| --- | ---: | --- |
| S1 直接任务 | 17/22 | 受理、幂等、事件游标和真实 DeepSeek 终态通过。本人取消、恢复收到 `403 SYS-AUTH-403`。 |
| S2 计划 | 29/29 | 修订 CAS、确认摘要、确认幂等、执行子任务回指及事件通过。 |
| S3 记忆 | 15/31 | 私有保存、列表、检索和共享写拒绝通过。明细 GET、PATCH、DELETE 在 IAM 入口返回 403。 |
| S4 插件 | 19/19 | 真实治理接口完成发现、差异、启用、授权、重复保护与撤回。使用仓库的插件来源夹具。 |
| S5 MCP | 19/19 | 连接档案、修订、受控失败、目录保留与审核前置通过。覆盖当前传输失败路径。 |
| S6 确认中心 | 10/23 | 本人计划修订与确认成功。ADMIN/BUILDER 待办计数和 USER 待办列表在 IAM 入口返回 403。 |
| S7 集成服务 | 19/20 | 真实出票、四端点、范围拒绝和自然到期 401 通过。有效期内撤销后同票被拒绝，实测 `403 IAM_AUTHORITY_FORBIDDEN`，细则预期 401。 |
| S8 角色矩阵 | 24/28 | USER/SERVICE 管理负向、归属保护通过。ADMIN/BUILDER/OPERATOR 资源清单及 OPERATOR 本人诊断视图返回 403。 |

隔离 PostgreSQL 数据库名 `s4_e2e`，Redis 和七个服务随隔离网络运行。主环境 E2 查询确认本次任务和集成凭据均为 0。真实 user-mgmt 核对目录，独立 authority 签发票据。BUILDER、OPERATOR 依次授予已确认的第二测试主体并回收。配置中仅 `deepseek / deepseek-flash`。两轮 S1 的任务、运行与供应商调用均为 SUCCEEDED。首轮将 QUEUED 视为错误，第二轮依任务状态契约判断 ACCEPTED 或 QUEUED，并校验 TASK_ACCEPTED 事件。

隔离 authority 的默认容器探针曾标记 unhealthy，实际 JWKS、签发、授权和撤销均可用。可复用准备脚本使用 E2 的 JWKS 探针。已撤销临时门户票与目录 key，删除隔离库、服务和密文目录，停止两路转发。主候选八服务健康，R-22 正式发布门禁待核对。

[最终断言](m5-3-e2e-report.json) · [首轮断言](first-run.json) · [原始输出](runner.txt) · [模型与隔离证明](database-model.txt) · [回收票据](cleanup.txt) · [收尾复查](cleanup-verification.txt)

[准备脚本 E2 启停复验](provision-smoke.txt).
