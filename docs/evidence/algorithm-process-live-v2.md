# 算法真实进程第二轮：engine 健康失败，资源清理成功

2026-10-20 10:56—10:58（Asia/Shanghai），在 root 再移交原固定锁后，使用已审 runner SHA `9edbf0c02e17f8e792146ed065339c3877a737e17d2802633d1cab3c5647e8e8` 实际执行 `node tests/smoke-algorithm-processes.mjs --execute-reviewed .local/algorithm-reviewed-artifact-lock.json`，退出1，错误 `Timed out: engine-runtime health`。未修改或重跑本轮源码、fixture、超时与生产。原 v1/第一次修复/准备证据全部保留。

原始目录 `algorithm-process-runner/live-49498fe27b72/`，完整命令输出 `algorithm-process-live-v2-execution.log`，独立追加只读核验 `algorithm-process-live-v2-readonly.json`。唯一新库 `agent_platform_algorithm_live_49498fe27b72` 保留，不 drop。使用同一固定962制品：engine SHA `d19504f307dd5ac956ce5cc9556cef2119b038a43913f9206288f7069440fcb7`，skill SHA `2543a6c9c3aa0bd8ec6f0d893093e3b9fe5f4664a0634ea6dd509f07b0835196`。

这轮修复后的 Unicode 路径精确归属检查实际通过：skill PID59292、端口65282，engine PID36984、端口65281，processes.json 均保存原创建时间、完整对应固定 JAR/SHA 和 listener owner。skill 启动健康通过；engine 也打开记录端口且归属通过，但健康检查始终失败，原60秒上限结算，不跳过健康或延长等待。

根因原日志明确：engine 的 RedisReactiveHealthIndicator 连接本工程默认 `127.0.0.1:26379`，反复得到 `NOAUTH HELLO must be called with the client already authenticated`。实际 application.yml 第11—14行读取 AGENT_REDIS_HOST/PORT/PASSWORD；runner 为隔离而使用显式环境白名单，但未加入这三项，因此认证密码使用空默认值。追加诊断仅输出布尔值，确认本新工程 `.env` 三项均存在且 endpoint 恰为 `127.0.0.1:26379`，没有输出密码或执行 Redis 管理操作。root 已独立复核该根因。

五场景仍未开始业务链：complete 停在 preparing，fixture POST/Job GET/Pod list/log GET/authRejected/denied 全0；拒绝上游计数0。没有 run/call/attempt/UID、逻辑提交或 offload/download 成果可报告。仅对本轮新库显式只读核验：skill/fact/binding、engine run/tool_call 全0；engine Flyway200—215及skill300—311均成功登记。原查询完整 stdout 与退出0保存在 readonly.json，不把迁移成功当作算法执行成功。

这轮 finally 完整成功：skill59292与engine36984都 stopped=true，final pid=null/listenerPids=[]，没有 AggregateError 或清理错误。有限 fixture 关闭；随后再次仅查询上述记录 PID/端口，pids=[]、listeners=[]。对照第一轮清理失败，此结论仅属于 v2，原 v1 记录不改写。

root 随后授权下一轮窄修复：显式注入本工程 `.env` Redis host/port/password，固定 endpoint、缺配置拒绝、密码脱敏及有限假配置红绿，仍禁止整包继承父环境、禁用健康、放宽超时或生产修改。待本轮报告归档后实施，第三轮真实入口仍须审查及 root 再授权。本轮实际结果为失败，不能用准备或有限 Node 通过代替五场景验收。
