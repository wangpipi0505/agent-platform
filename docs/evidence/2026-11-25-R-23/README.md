# R-23 FT-INT 负向联验

2026-11-25，使用本地真实 gateway、app-management 和 authority-service，通过随机隔离凭据验证 FT-INT-02、03、04 受理口径。短票经 authority 的 JWKS 验签。E2 公开 Nginx 入口另做无凭据拨测。

| 场景 | 实测结果 |
| --- | --- |
| FT-INT-02 白名单外任务 cancel 与 resume | 两次均为 403 `APP_BINDING_DENIED`，网关记录 `INTEGRATION_PATH_FORBIDDEN` 审计 |
| FT-INT-03 未登记 Origin | 403 |
| FT-INT-03 错密钥 | 401 `INVALID_CREDENTIAL` |
| FT-INT-04 已验签集成票达到 exp 后读取 | 401 `INTEGRATION_TICKET_EXPIRED` |
| FT-INT-04 未过期集成票对应凭据被撤销后立即读取 | 403 |
| E2 无 ApiKey 请求 `POST /embed/ticket` | 401 `API_KEY_REQUIRED` |

审计记录包含凭据编号、方法、路径摘要和 traceId。使用测试密钥原值在本地网关日志中检索，结果为空。回归脚本位于本工作区 `.local/r40-force-audit.ps1`、`.local/r40-expiry.ps1`，运行前先启动该分支本地七服务与隔离 PostgreSQL。

FT-INT-01 的真实外部宿主受理、详情、事件、结果及下载摘要待联验。当前卡记录平台侧联验，外部宿主的有效接入凭据和 E2 环境授权仍需实际衔接。
