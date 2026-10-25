# CHAT历史权限范围修复（H1）

来源：全工程审查发现，同一主体的旧宽范围回答会在下一轮权限收窄后进入模型。修复只在本独立工程，未改变原设计契约。

新requestId受理前，`ChatHistory.checkScope`按当前tenant/principal/app核历史run.guard_ctx；当前角色和属性值集合必须覆盖历史集合。集合重排和扩大允许，收窄、角色撤销及缺少授权证据返回403 `CHAT_HISTORY_SCOPE_CHANGED`，提示新建会话。该检查发生在预留turn及模型调用前。`ChatHistory.load`在实际组装成功回答或失败错误文本前再次核对当前run.guard_ctx，覆盖执行与恢复入口。

已受理旧requestId保持幂等读取，不借此创建新的模型请求。本修复不替代运行中生产IAM实时撤权，也不更改另行规定的历史记录可读/保留策略。

## 真实验证

- `.local/chat-history-scope-red.log`：2026-10-20 04:29:41，单项实际行为失败，期望PlatformException但未抛出；不是编译错误。
- `.local/chat-history-scope-green.log`：04:33:19，ChatSessionsTest共4项、0失败/错误/跳过，BUILD SUCCESS。真实本工程专用PG；外部模型为测试HTTP fixture。
- 新回归预置源权限[A,B]及包含B的旧回答；当前[A]的直接load和新轮受理均拒绝，新增模型调用0、chat_turn仍仅原1条；覆盖[B,A]等价、[A,B,C]扩大、角色撤销、FAILED错误文本和缺guard证据。既有同权限双轮正例仍实际调用模型两次，第二次携带前轮问题/回答。
- 独立审查者已读取代码、断言和红绿日志，H1关闭记录见[全工程审查追加](implementation-coverage.md)。审查者未冒认重跑测试；04:26:56的251项完整回归早于该修改，不能覆盖它。输出策略和统计增量完成后再整轮回归。

修改文件：engine的`application/ChatHistory.java`、`application/ChatSessions.java`及`infra/ChatSessionsTest.java`。无迁移，尚待本轮稳定构建部署到独立开发进程。
