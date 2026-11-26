# R-35 门户运行入口

IAM 网关只接受已验证、含 `platform.access` 的门户票。允许 GET 和 POST 访问 `/v1/apps/{code}/sessions`、`/v1/apps/{code}/tasks`、`/v1/apps/{code}/memories` 三个集合，以及下列本人会话明细。应用编码沿用路由登记的字符范围。会话与记忆沿用引擎接口，应用任务列表沿用 `/v1/tasks` 的列表信封、归属规则和分页，应用范围固定取路径中的 `code`。

入口证明绑定到实际 MVC handler、请求方法、路径和查询串，消费一次。网关按服务能力目录换发给 engine 的短时委派票。运行入口票绑定应用编码，引擎核对 URL 中的应用编码后再处理请求。人、租户与角色来自已验票据，资源归属及数据范围由引擎继续判定。需要 authority 启用既有 delegation 配置。

会话创建、任务受理及记忆策略读取需要当前生效的发布快照。引擎沿用本次委派授权向 app 转发，app 在同一租户、同一应用下锁定并复核当前发布后读取快照。发布运行入口继续校验原票绑定的具体版本和摘要。

仅接受 Authorization Bearer 票据。请求查询串不能带票，客户端身份、租户及角色声明头一律拒绝。其他运行面路径保持各自的入口和权限规则。


## R-42 门户会话

| 方法 | 路径（相对 `/v1/apps/{code}/sessions/{id}`） | 结果 |
| --- | --- | --- |
| GET | 本路径 | 本人消息投影与同一前缀的 `eventWatermark` |
| POST | `/turns` | `{requestId,inputs:{message}}` 受理；同一请求重放返回原 runId |
| GET | `/events` | 读取 `afterSeq` 后的事件 |
| GET | `/events/stream` | SSE；以 `Last-Event-ID` 排他续读，断开只终止观察 |

网关和引擎核对绑定的应用与会话，数据库核对租户、主体。外部提交不能指定历史。引擎从本人会话已成功的历史轮次构造正式模型输入。回答写入 `assistant/message.content`，结束帧 `turn/end` 含 turn、reason、status、runId。用户面过滤系统消息。每次重连由网关重新换票，已连接的流定期复核授权。

在签发服务提交 `POST /oauth/session/revoke`，以 Authorization Bearer 携带要撤销的本人门户票，成功返回 `{"revoked":true}`。重复撤销幂等。过期或被撤销的门户票不能继续换取会话权能，其他登录独立。签发服务用 `AGENT_AUTHORITY_REVOCATION_DB`、`AGENT_DB_USER`、`AGENT_DB_PASSWORD` 连接同一 PostgreSQL，Flyway 在独立 `iam_revocation` schema 保存登录指纹与到期时间。多实例共用该库，撤销登记不可读时拒绝换票。独立 dev/test 未提供撤销库时撤销请求返回 503；正式身份签发需配置撤销库。
