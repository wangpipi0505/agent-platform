# 持久会话边界与用量展示

复用既有PG会话/轮次及上下文投影，仅补冻结轮数上限、历史关联run自身归属复核、三维token/缺用量和适用配额状态、精确gateway读路由、运行中心展示及新会话切换。8生产文件，无新迁移/权威表，RunService及公共身份代码未改。接口与兼容见contracts/chat-session-usage.md。

第一轮实际1个类级ERROR、0方法：新增测试误选agent_platform_v12，其engine schema非空但没有Flyway历史，因此在beforeAll失败；不能记为产品红。已改用本仓既有EngineTestDatabase的engine_test配置，只做正常migrate，不baseline/repair/清库，不改任何业务断言。原native1/XML/summary保留，不补跑红测。

唯一第二轮新ConversationPersistenceTest3+原ContextProjectionTest2=5P0F0E0S/native0/fresh XML2，源稳定。新例用本次独有tenant/session/行并只清理自己创建的行，覆盖重建存储对象后读到既有轮次、固定发布版本/跨主体与错误run指针拒绝、无会话无历史、上限受理前拒绝不耗号且旧requestId重放、三维汇总不把缺用量当已知零、窗口适用范围。RunService作为受理边界mock，不冒称新JVM/真实模型跨轮推理已验证；旧裁剪两控制为纯unit，未运行含全表清理的旧PG类。

执行命令保存在私密.local/ex21b-conversation-run/run-first-20261030-01.ps1和run-second-20261030-01.ps1；第二轮mvn -pl engine-runtime test，选择上述两类，明确failure.ignore=false。前端第二轮内仅一次既有session.test、vue-tsc、admin构建，结果见frontend-second-20261030-01；不重跑后端、不追加第三轮。

ROOT完成一次轻量非作者检查，确认上限在原会话锁下复核、旧幂等分支优先、历史两层归属、缺用量/窗口提示及前端身份切换清理；无阻断。整体EX21b仍进行中，IAM会话可信授权、多轮评测及真实浏览器/重启/指代验收继续承接，金额不做。
