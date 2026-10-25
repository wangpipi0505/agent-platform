# G2/T11 异步执行增量计划

依据冻结 v1.2 第三部分 F5/F6、§3.3 与技能调用契约，不修改冻结正文。

## 当前缺口

HTTP 非 SYNC 被注册层拒绝；适配层忽略 queryOnly。原 external_job_binding 只覆盖 K8s，缺精确幂等键及代次归属。reconcile 的 NOT_APPLIED 只返回 false，尚无引擎控制重试闭环；取消运行尚未等同外部取消。

## 实施顺序及边界

1. 真实 PG + 测试 HTTP 服务先验证 SUBMIT_QUERY：创建请求前绑定唯一调用，持久精确 key/run/call/attempt，发送前标记不可重新提交；响应 jobId 持久化后仅查询原 job；完成响应先验证 key/job 归属，再复用输出大小闸、Schema 和四份返回映射。异常保持 UNKNOWN，不自动重提。恢复复用现有 reconcile/proof/outbox，不更改引擎事件流。
2. CALLBACK 最小补充需技术对齐：在已存在 invocation/reconciliation JSON 中声明 jobIdPath（默认 $.jobId）、jobId 查询占位符、callback.secretRef；回调采用内部服务验签身份再以 HMAC-SHA256 原始请求体验签，调用归属从 PG 查得，不信任 body 身份。事实包含 jobId/runId/callId/attemptId/idempotencyKey/result；唯一摘要去重，旧代留痕；成功事实经既有 execution_fact outbox 交给引擎租约判定，禁止取消后复活。
3. 取消采用外部配置 endpoint 的确认三态 APPLIED/NOT_APPLIED/UNKNOWN，持久审计；引擎负责实际重试授权与终态。涉及 ToolCalls/ToolCallController/RunService.cancel 前先报告接口，V214 需协调后使用。未完成的闭环必须如实记为缺口。

工程补充配置保存在实现证据，原文未定义的 wire 字段不得解释为已冻结契约。HTTP+SUBMIT_QUERY 先落地；未支持协议组合继续明确拒绝；真实 MCP/K8s 适配验收仍未验。

## 验证

独立 agent_platform_skill_async_test 数据库；单模块 Maven -pl skill-registry（不带 -am），本工程 .tools/m2，Java 21 与 V:/.local/tmp。先红后绿记录命令/用例/结果；无服务启停、git 提交或发布。
