# 异步回调下一阶段方案（未实现）

HTTP SUBMIT_QUERY 阶段审查期间不修改共享执行器语义。以下是 root 已技术对齐的实现补充，原冻结契约正文不变。

## 接收与身份

- 内部入口拟为 `POST /internal/skill-jobs/{callId}/callback`。沿用现内部 JWT SERVICE 校验，必须保留原 tenant/principal，不能将任意 SERVICE 主体视作授权。
- 外部提供方通过专用可信 relay 换发短期服务票据；没有 relay、可达 URL、secret_ref 的专用生产环境时，真实联调仍记未验。不开放公共免 JWT 路由。
- 冻结契约中 `callback.secretRef` 指向现 SecretVault；回调请求头 timestamp 和 signature，HMAC-SHA256 签名内容为 `timestamp + "\n" + rawBody`，时间窗 ±300 秒，常量时间比对。
- body 包含 eventId/jobId/runId/callId/attemptId/idempotencyKey/result，字段只用于比对，不用于赋权。租户和主体由已验票据及 PG 绑定确定。

## 持久事实与交付

- V308 新 callback_receipt 唯一 `(tenant_id,call_id,event_id)`，保存 payloadDigest、绑定的代次、验签时间与结论；同 eventId 同摘要幂等，不同摘要拒绝。
- 验签之后依次核对 PG 原绑定的 key/job/run/call/attempt/skillVersion，以及引擎当前调用权威。错归属拒绝；旧代或取消后的消息留痕，不能复活已取消 run。
- 有效结果经现 Schema/OutputGate/returnSpec 处理后，原子更新 execution_fact 与 receipt；复用现 outbox 回调引擎，最终逻辑提交仍由引擎当前代次与租约裁决。
- 重放、事实已保存但响应丢失、引擎回调失败、旧代迟到、取消竞态均须真实 PG 失败路径测试。不得把已存 receipt 计作逻辑结果已采纳。

## 仍需后续闭环

外部取消三态需与引擎 RunService.cancel 协调；NOT_APPLIED 重试许可由引擎独立控制，不能由适配器主动重发。当前 HTTP 阶段尚未声称完成这两项。
