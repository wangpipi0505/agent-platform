# R-22 E2 入口复核

2026-11-25，按台账登记的 E2 Nginx 地址 `192.168.1.2:18091` 发出只读探测。另发出一次无 ApiKey 的出票请求，核对接入拒绝。

| 请求 | HTTP | 结果 |
| --- | --- | --- |
| GET /healthz | 200 | Nginx 健康 |
| GET /console/ | 200 | 控制台入口可达 |
| GET /auth/session | 401 | SYS-AUTH-401 |
| GET /auth/dev-token | 404 | 生产代理关闭开发票入口 |
| GET /actuator/prometheus | 404 | 公开代理隐藏指标 |
| POST /embed/ticket，无 ApiKey | 401 | API_KEY_REQUIRED |

以项目旧记录使用的 SSH 批处理方式连接 E2，远端返回 Permission denied。E2 真实登录后的页面走查、双小时长稳和新镜像部署复验待执行。此前真实供应商及用量对账结论沿用 2026-11-20 的存档。
