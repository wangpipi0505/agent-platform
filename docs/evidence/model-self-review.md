# 模型接入自审与设计/开源对照

> 本文主体记录早期32项测试阶段。最新模型54项及V305发送前账务恢复见[model-quota-nosend-i3.md](model-quota-nosend-i3.md)、[model-quota-f4-f6-recovery.md](model-quota-f4-f6-recovery.md)和[progress.md](progress.md)。receipt现为绝对结算截止与额外24小时标记保留，不应将下文旧“两天”口径作为当前实现；新增结论以专题复核为准。

日期：2026-10-20；使用 code-review 检查逻辑、权限、并发、边界和故障路径。只审本独立工程，不对外部仓库或相邻业务工程作代码复用。

## 已核对的不变量

1. 运行模型配置来自 PG binding_snapshot，不从请求 body 的配置副本取信任标签、端点或能力；tenant、主体只来自 Actor。应用候选持久键首次固定 refs，报告 refs 与发布快照一致。发布事务无网络等待。
2. 已发布引用不受管理元数据编辑/停用影响；secret_ref 每次仍取权威 provider，不进快照/模型 prompt/公开列表。真实 HTTP 头已验证前后两次轮换分别使用新凭据，拒绝 EDITOR 轮换。
3. 实际网络发送前对完整 SDK wire（含 response_format/schema）再次计量并保存；超过预留拒绝，绝不只测 messages/tools。专用tokenizer带格式请求要求完整材料摘要确认，忽略格式的 `/tokenize` 实现不能冒充精确计量。非流式/真 SSE/工具返回而不自执行都有真实 HTTP 回归。HTTP错误码和正文隔离；同步和reactive响应有2 MiB边界。
4. Redis 准入以租户 hash tag 为同一原子域；独立客户端竞争同一 RPM/日额/并发许可。拒绝不自动切换本机计数。模型调用成功后 Redis 失败不会把已成功业务重发：PG保留 usage 与 PENDING 账务状态，receipt 对账幂等。
5. 熔断源失败与本地准入失败分开；未发 HTTP 的配额拒绝只释放半开许可。冷实例也必须读PG DEGRADED并拒绝普通调用，只由半开健康探测恢复。人工禁用更新使用条件保护。供应商元数据与凭据轮换使用同一PG行锁，probe按config_rev CAS写回，不允许旧配置测试启用新配置。
6. 展示校准不进入准入计量，且按模型配置/工具/格式隔离。专用 tokenizer count 与 jtokkit 保守结构计数的 exact 标记不同；缺 usage 不记成精确零消耗。

## 实际读码对照

源码通过 Maven Central 固定 sources JAR取得，仅保存在本工程 `.tools/references/model-upstream/`；未复制上游业务实现。

| 上游固定源 | 实际阅读 | 本工程落点 |
| --- | --- | --- |
| [Spring AI openai 1.0.0 sources](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/1.0.0/spring-ai-openai-1.0.0-sources.jar)，Apache-2.0，SHA256 `089f00fe17be299fff737ca8abb5f13e86cb007f518822f494c86bf97c9b3257` | OpenAiChatModel.java 同步/流式 toolExecutionEligibilityPredicate→executeToolCalls→递归路径，以及 EmptyUsage 与最终流 usage 合并 | ModelCalls 显式 internalToolExecutionEnabled=false；SDK maxAttempts=1；缺实际usage不造零；受控循环留在engine |
| [Resilience4j circuitbreaker 2.3.0 sources](https://repo.maven.apache.org/maven2/io/github/resilience4j/resilience4j-circuitbreaker/2.3.0/resilience4j-circuitbreaker-2.3.0-sources.jar)，Apache-2.0，SHA256 `2b24812c57edf2b341c526ae2e4c347c0522881d308f13d4cc6baddc341c460f` | CircuitBreakerStateMachine.handleThrowable、OPEN.tryAcquirePermission、HALF_OPEN.tryAcquirePermission/releasePermission/onSuccess/onError；未被recordException命中的异常会计为成功 | ModelCircuits 按tenant/provider独立；未发网的配额拒绝释放许可，不当作半开成功；真实30秒恢复回归验证PG状态同步 |
| Redis Lua | Lua 脚本为本工程业务自研，使用真实Redis EVAL脚本验证原子预留、共享配额、队列与receipt去重 | ModelQuota；不宣称已阅读Redis内部令牌桶实现或复制上游脚本 |
| jtokkit1.1.0 | 直接依赖已安装JAR模型映射API；本轮未宣称阅读完整上游源码 | 仅明确支持模型启用jtokkit；未知模型要求专用tokenizer |

## 尚需保留的验收边界

- Resilience4j失败滑动计数仍在每模型服务副本内，PG DEGRADED是所有调用（含新实例）的持久拒绝门；Redis配额是分布式。已补冷实例持久熔断守卫与真实半开健康恢复，但尚未验收多个副本同时发生故障时的统一失败计数。定时探测当前覆盖 DEGRADED；未实现所有空闲 ENABLED 供应商的周期预热巡检。
- Redis 批量队列提供32槽、最多10秒有界等待和拒绝retryAfter；没有另设异步排队任务资源或在线队列位置SSE。32个队列槽之外还有本机32调用容量上限。不能据5个Redis用例声称完整容量验收。
- 展示校准已按scope自动应用；真实云端100次中文/JSON Schema/工具集偏差与vLLM tokenizer部署仍需供应商实测。canonical+64+现有安全窗口为保守准入策略，不能称其是所有模型的精确token计数。专用tokenizer要求同源，跨源部署需明确受控信任方案。
- Redis receipt 去重保留2天，进程崩溃的并发租约最长660秒；超长期账务故障和跨日大规模恢复仍需专项演练。PG usage 是实际消耗权威，缺usage时继续保守保留预留额。
- 新旧发布衔接必须由engine拒绝“含模型ID但缺冻结refs”的旧发布版本；新候选与新发布已提供完整refs。不允许用运行时读取可变模型配置补齐历史正式快照。engine消费回归由对应工程责任代理验证。
- 发布事务外启用复核与跨服务瞬时禁用存在分布式时间窗口；本实现不宣称跨服务原子停用。若要求“禁用瞬间后所有正在提交发布都阻断”，需额外有期限的发布许可/撤销协议，不能靠应用事务内HTTP达成。
- 预检目前对存在技能引用的应用采用保守 tools 能力要求；复杂 FLOW 中普通LLM节点与独立SKILL节点的精确能力归属仍需按节点角色细化。平台默认模型绑定、出域自动备选路由/切换由运行准备与路由策略联调；本轮实现安全拒绝不匹配端点，不假称自动找到备用端点。
- 已验证同步与真实SSE成功/工具返回/错误归一/响应边界；尚未演练外部真实供应商全错误谱、长时间流中断与跨副本恢复。内部服务caller/audience权限细分仍沿用公共安全审查登记项，不在模型模块自行另造安全协议。

独立模型绑定审查进一步发现并修复：输出schema计量遗漏、冷实例绕过PG DEGRADED、凭据/端点并发转接与旧probe启用新配置、EDITOR可自声明信任域。四项先实际红后绿，并补两线程真实PG行锁测试与专用tokenizer完整格式材料回归。最新模型32项、应用事务13项同轮全绿，日志与时间详见 model-completeness-report。

这些边界明确保留；本增量不扩展二期LLMjudge，不将局部32模型测试或应用22项历史全模块测试换算为全平台交付完成。
