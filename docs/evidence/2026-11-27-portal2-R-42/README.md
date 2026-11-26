# R-42 门户双主体 E2 复测

候选 `r42-20261127-portal2`，提交 `76fc32528795f9201a80cfeb9acb6544d0e0550a`。已配置的真实 user-mgmt 向测试主体签发有效身份，门户经 authority-service 换会话。管理员通过平台授权接口为 operatorA 分配两个应用的 OPERATOR。

| 检查 | HTTP | 核验 |
| --- | --- | --- |
| 授权前访问应用 | 403 | PLATFORM_CAPABILITY_DENIED |
| admin、operatorA 的 /auth/session 和各自建读本人会话 | 200 | 平台角色分别为 ADMIN、OPERATOR |
| 跨用户读取 | 403 | SUBJECT_MISMATCH，响应 traceId 等于拒绝审计 trace_id |
| 跨应用读取 | 403 | APP_MISMATCH，响应 traceId 等于拒绝审计 trace_id |
| 范围外应用、授权管理、发布预检 | 403 | PLATFORM_CAPABILITY_DENIED |
| 撤回平台授权后使用原登录读取本人会话 | 403 | PLATFORM_CAPABILITY_DENIED |
| 撤销单次登录后再读取，及使用并行登录读取 | 401 / 200 | 仅撤销指定登录 |

[双主体回执与审计](portal-ownership.json) · [原候选失败回执](../2026-11-27-portal-R-42/portal-ownership.json) · [三轮库内终态](e2-session-cursor.json) · [从 2 续读 3 至 12](e2-replay.json) · [登录撤销](e2-chat.json) · [测试目录凭据已回收](second-cleanup.txt)

复核时按上述顺序操作并比较原始 traceId。浏览器实拍见相邻 R-22 证据目录。
