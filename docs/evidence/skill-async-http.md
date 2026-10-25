# HTTP SUBMIT_QUERY 第一阶段实现证据

日期：2026-10-20。范围仅新工程 skill-registry；原冻结契约正文未改，engine、统计及 OpenAPI 业务语义未改。

## 实现

- HTTP 可注册 SUBMIT_QUERY；支持 POST JSON 提交 + GET 原 job/key 查询。MCP 异步、CALLBACK、HTTP 异步 OpenAPI 映射及其他提交方法明确拒绝。
- V307 扩充外部绑定的精确 idempotency_key、tenant/principal/run/call/attempt/skillVersion 与冻结请求。绑定唯一约束仲裁；UNKNOWN 执行事实与绑定同事务提交，之后才申请引擎 submitted 并实际发出请求。
- send_started 持久 CAS 先于发送；任何响应丢失、超时或不确定状态只查询，绝不隐式再提交。ACK 必须在 64 KiB 内证明 jobId + 原幂等键，不按最终 outputSchema 验收 ACK。
- 查询核对原 job/key，再按 donePath/doneValue 采纳结果。result 流式抽取到有硬限的临时文件，元数据限制 64 KiB；真实结果走已有 OutputGate、Schema、returnSpec 与受控 MinIO artifact。失败保持 UNKNOWN。
- 权威归属和 guard 仍走现 SkillExecution；执行事实在回调前落 PG，现 outbox 和引擎租约裁决唯一逻辑结果。查询耗时只记 reconciliation_note，不计入原业务时长。

## 实现层协议补充

`invocationKind=SUBMIT_QUERY`（或 invocation.kind），jobIdPath 默认 `$.jobId`。reconciliation.endpoint 支持 `{jobId}` 或 `{idempotencyKey}`，替换值按 URL 段编码；method 仅 GET；donePath/doneValue 必须明确。idempotencyKeyPath 默认 `$.idempotencyKey`，resultPath 默认 `$.result`。可选 lookupEndpoint 必须含原 `{idempotencyKey}` 且不含 jobId，用于 ACK 丢失。上述字段属于工程协议实现补充，未改写冻结正文。

## 红绿证据

命令统一先从本工程 .env 加载进程变量（不打印），Java 21，`mvn -B -ntp -pl skill-registry test -Dmaven.repo.local=<本工程>/.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp`；定向增加 `-Dtest=AsyncHttpExecutionTest`，不带 -am。

- skill-async-red.log：2 例因为既有 UNSUPPORTED_INVOCATION_KIND 缺口失败；skill-async-green.log：2/2 通过（05:12:12）。
- skill-async-stream-red.log：18 MB 结果因旧协议缓存限制为 UNKNOWN，5 例中 1 断言失败；skill-async-stream-green.log：5/5 通过（05:13:56）。
- skill-async-protocol-red.log：显式 HTTP403 错分类、DELETE 异步提交未拒绝，两断言失败；skill-async-protocol-green.log：7/7 通过（05:15:42）。
- skill-async-binding-race-red.log：原 key 查询在响应抵达期间被另一 job 绑定抢先，旧实现错误采纳 SUCCEEDED；补 PG 绑定重核验后通过。
- skill-async-regression-final.log：最终完整 skill-registry **85/85 通过，0 失败、0 错误、0 跳过**（05:18:58）。其中 AsyncHttpExecutionTest 8/8；此次编译包含已格式化的最终源文件。前一次完整回归 skill-async-regression.log 为84/84历史基线。
- 覆盖：提交前有绑定、精确幂等键、一次提交、原 job 查询恢复实际输出、异 job 拒绝、ACK 丢失按 key 找回、输出 Schema 失败、18 MB 真实 MinIO 受控产物、权限失败分类及不支持协议拒绝。
- 测试 DB：新增 agent_platform_skill_async_test；旧集成测试白名单 agent_platform_v12、agent_platform_statistics_test 均为本工程 25432 实例专用测试库。真实开发运行库 agent_platform_dev_v12 未使用。

## 限制

这是 SUBMIT_QUERY 受控实现验收，不代表真实 MCP/K8s 或生产外部系统验收。签名 CALLBACK、取消三态、NOT_APPLIED 引擎重试授权与 UNKNOWN 人工审计完整产品闭环尚未完成。不能将本报告计作全部 T11 完成。
