# 不可变对账证明的实际引擎纵切

原票据边界P2经 `review-skill-reconciliation-proof.md` 独立关闭后，停止本工程skill PID63724，构建并启动PID18484、18083。部署日志 `.local/skill-reconciliation-proof-reviewed-deploy.log` 于06:49:11 BUILD SUCCESS；独立开发库迁移V310。引擎为已审核的PID59704，未操作相邻工程。

执行 `node tests/smoke-reconciliation-proof.mjs`，退出0，原始证据 `.local/skill-reconciliation-proof-live.log` / `.json`。Node28417提供方是专用协议fixture，实际链路经过本工程网关、技能管理/受控连接、引擎调用登记及持久事实、技能原job/key查询、证明短事务、真实鉴权GET和PG计数。fixture不是真实供应商。票据仅为新工程开发测试签发，内部用途与受众明确，凭据没有写入证据。

| 核对项 | 实际结果 |
| --- | --- |
| 技能/运行/逻辑调用 | smoke_proof_de1348e970 / r_f897939f3c7b47739b7fb4653961be50 / call70，attempt238 |
| 明确外部观察 | 原任务先RUNNING，随后对原job/key返回已配置NOT_APPLIED；查询总计3，业务提交始终1 |
| 不可变证明 | srp:1，PG恰1行；重复对账返回相同ref与SHA；完整证明另从内部GET读取 |
| 摘要 | ab4a530dc1c8d39f4f53b4efdf300756b0d3b33026fb51ecbf70fe83b79cd766；Node按完整证据规范化JSON独立重算一致 |
| 归属绑定 | 读取证明中的run/call/attempt、原idempotencyKey和jobId与真实PG绑定逐一相等 |
| 票据边界 | 已验签但portal用途且带SERVICE角色的票据403；内部票据的其他主体404 |
| 执行权 | 对账后run仍WAITING_EXTERNAL、engine.tool_call仍UNKNOWN，retryBusinessAllowed=false；未创建新代、未重新提交业务 |

测试finally只取消本脚本创建的等待运行并关闭28417及socket，保留证明与审计。该取消只控制本地运行，不声称外部停止或效果撤销。初次脚本漏填技能必需description，在创建时被真实400拒绝，尚无业务提交；保留 `skill-reconciliation-proof-live-fixture-error.log`，补齐测试元数据后才完整通过，没有修改产品校验。

同一新版本还重新执行 `smoke-http-async.mjs` 和 `smoke-signed-callback.mjs`，均退出0。SUBMIT_QUERY三场景（立即完成/等待后完成/ACK丢失）各业务提交1、节点逻辑提交1并SUCCEEDED；签名CALLBACK四场景保持成功、FAILED无result、ACK前早到重投和取消后迟到不复活。当前原始记录为 `.local/skill-http-async-live.*` / `.local/signed-callback-live.*`；之前98/133阶段保留为 `-stage98.*` / `-stage133.*`，132更早阶段也保留。异步脚本同时将开发服务票据修正为明确internal audience/service用途，不再借portal票据附SERVICE角色测试正常服务路径。

这些结果关闭本次证明、代次CAS与票据边界的本地协议纵切，不代表引擎NOT_APPLIED单次重试许可已实现。adapter expectedAttempt传播仍是未来启用新代前必须实现的前置；取消三态、人工UNKNOWN处理、真实供应商/IAM和容量仍另行推进。
