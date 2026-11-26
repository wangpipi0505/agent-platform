# R-42 门户会话回归

冻结标签 `r22-20261127-plan4`，代码 `e122a508d412d2674ac5dc7de54cf4e97fa1e181`。 两名真实用户各自读取本人会话成功。跨主体和跨应用读取返回 403，两条拒绝的 traceId 与数据库审计对应。撤销用户票后读取会话身份返回 401，另一张有效票继续返回 200。三个 CHAT 轮次均为 SUCCEEDED，断点后连续读到事件 12，无缺号或重复。

[主体和授权范围](portal-ownership.json) · [三轮请求](e2-session-cursor.json) · [断线续读](e2-replay.json)
