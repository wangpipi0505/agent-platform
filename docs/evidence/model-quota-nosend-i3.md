# I3 模型发送前失败的配额恢复

日期：2026-10-20。范围限定独立新工程的 model-gateway、模型测试与本报告。先读工程约束、实施简报、contracts/README 和 `review-execution-increment.md`；沿用 TDD、最简实现、系统调试与 code-review 自审。未访问旧工程、开发运行库或其他服务源码，未启动/停止服务、打包或提交 Git。

## 修复结果

无效 timeout 与不可解析凭据现在在 Redis 准入之前拒绝，不产生预留。SDK 的真实序列化材料仍在实际发送前复核；其超预留拒绝以及发送日志持久化失败，经过独立 PG 退款流水恢复，不再只释放并发槽位。

新增迁移 **V305__durable_unsent_quota_reservations.sql**，已通知 root。没有修改已有 V304。`model.quota_reservation` 持久保存 tenant/principal、invocation、模型/provider、app、不可变预留 receipt、发送状态、账务状态、错误码与准入截止时间。

流程与不变量：

1. `ModelQuota.acquire` 用 Redis TIME 得到截止时间，将预留意图通过回调先写 PG，再执行原子准入 Lua。PG 意图写失败时不进行 Redis 预留。Redis 准入在扣 TPM/RPM、两个日额的同一个 Lua 中保存该 reservation 的实际 provider generation/窗口证据，因此“Redis 已预留，但客户端没有拿到结果或 PG 后续写失败”也有恢复材料。
2. 本地状态仍未开始发送时，任何异常都尝试将 PG `RESERVING → NO_SEND/PENDING`，然后以实际 token=0 退款；Redis 或 PG 确认暂时失败时保留持久待处理记录。若 NO_SEND 的 PG 更新本身失败，之前已写入的 RESERVING 意图仍在，准入租约到期后可恢复，不丢 receipt。
3. 同步 HTTP interceptor 与流式 connector 都在请求日志写成功后、交给底层发送之前执行 PG `RESERVING → SENT` 条件更新。只有仍拥有未过期意图的调用可以越过此边界。`SENT` 是“可能已发送”的保守界线；之后任何无响应、错误、未知 usage 都不会作为零发送退款。
4. 恢复任务处理 `NO_SEND/PENDING`；超时的 RESERVING 先以同一 PG 行的条件更新转 NO_SEND，再恢复账务。和发送者竞争同一行，恢复先赢时迟到发送者不能发送；发送先赢时恢复不退款。PG 账务终态采用 PENDING CAS，不可重复覆盖。
5. 另有 Redis 时间的 `admissionUntil=意图时间+15min`，同时写入 PG 的 lease_until 并传入准入 Lua。Lua 拒绝超过此时间的新预留。恢复若找不到 Redis 预留证据且 admissionUntil 尚未到，继续保持 PENDING，不提前认定最终未预留；到期后才能安全标记 EXPIRED。这个约束封住“PG 意图到期被恢复后，暂停线程醒来才执行 Redis 预留”的跨存储竞态。

仍保留真实 wire 检查在准入之后：先持久意图与可恢复预留，再计实际 SDK 字节；这使多种本地/SDK 失败统一进入同一恢复路径，无需复制 SDK 序列化。没有把近似材料当作已发送材料，也没有删除原安全拒绝。

## 账务策略与运维核查

零发送已确定时，应用日额与用户日额均按 token=0 退款。供应商 TPM 退还原预留，RPM 退还一次请求；RPM 退款仅用于本轮新增的零发送账务，正常已发送调用的 usage 结算不退 RPM。

全部继续服从 F6 的限制：供应商必须仍是原 generation、预留后 60s 内且 key 存在/有 TTL；两个日额必须仍是所属 UTC 当日且 key 存在/有 TTL；总恢复截止 24h，幂等 marker 比截止时间晚 24h 到期。过窗不把退款转入当前桶，也不复活过期 key；按实际能调整的维度终结为 RECONCILED、PARTIAL 或 EXPIRED。已过供应商窗口的闲置 token/request 桶由自然补充恢复，不跨窗另退。

运维可按租户在 PG 权威流水核查 `quota_reservation.status`、`usage_reconciliation`、`error_code`、`quota_receipt`、`lease_until` 与 invocation_ref。NO_SEND 明确表示外部模型业务请求未越过发送边界、实际用量为 0；SENT 表示可能已发送，真实 usage 继续在 call_log 记录，缺失时保留预留。NO_SEND 的恢复任务只重投账务，不重发模型 HTTP，不修改调用方运行状态。

## 真实 TDD 证据

全部连接本工程 `.env` 提供的专用 PostgreSQL `127.0.0.1:25432/agent_platform_model_test` 与 Redis `127.0.0.1:26379`；受控 HTTP 模型/计量端点仅在测试代码。没有打印密码或密钥。

| 阶段 | 日志 | 实际结果 |
| --- | --- | --- |
| 原实现红测试 | `.local/model-quota-nosend-red.log` | 5 项、4 失败、0 errors/skips；timeout 遗留 204、实际 wire 拒绝遗留 155、发送日志失败遗留 191 日额，并缺持久恢复表；已发送未知 usage 保守预留对照项通过 |
| 第一轮修复绿 | `.local/model-quota-nosend-green.log` | 5 项全部通过，V305 在专用测试库真实迁移成功 |
| 首次全量 | `.local/model-quota-nosend-full-green.log` | model 52 项 + common 5 项，全部通过；随后继续补准入截止竞态，不把该轮作为最终源码的证据 |
| 自审竞态红测试 | `.local/model-quota-nosend-fence-red.log` | 1 项真实失败：预留证据缺失但准入窗口尚未关闭时错误终结为 EXPIRED，预期保持 PENDING |
| 最终定向回归 | `.local/model-quota-nosend-final-targeted-green.log` | 27 项，0 failures/errors/skips；包含 9 个 HTTP/故障场景、5 个 PG 账务与 13 个真实 Redis Lua 用例 |
| 最终模型全量 | `.local/model-quota-nosend-final-full-green.log` | model 54 项（33 HTTP/PG、5 配额PG、13 Redis、3 policy）+ common 5 项，全部 0 failures/errors/skips，BUILD SUCCESS；该轮针对最终冻结源码，04:05:53 完成 |

执行命令（仅向当前进程加载本工程 .env，不回显环境值）：

```powershell
mvn.cmd -B -ntp -pl model-gateway -am test `
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 `
  -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

红与定向轮使用同样命令，另加 `-Dtest=...` 与 `-Dsurefire.failIfNoSpecifiedTests=false`，具体选择记录在上述日志的测试类/计数与测试源码。Java 21，既有本地 Maven 依赖，Java 源码用缓存 google-java-format 的 `--aosp` 保持 4 空格。`node scripts/verify-contracts.mjs` 通过。

覆盖：无效 timeout、缺失凭据零预留；同步/流式实际 wire 超预留零 LLM 外发、两个日额/TPM/RPM恢复；发送日志写失败也可恢复；PG 意图写失败拒绝且不预留；真实 Redis 预留后对账接口注入失败，PG 保存 PENDING，换新 ModelCalls/ModelQuota 实例恢复且只退款一次；NO_SEND PG 写失败后租约恢复；过期租约阻止迟到发送；缺失 Redis 证据在准入窗口内保持待恢复；已发送且 usage 缺失保守保留。

对账故障通过方法边界注入 Redis 调整失败，预留及最终恢复均执行真实 Redis Lua；未停 Redis 或声称做过 Redis 服务崩溃测试。流式拒绝会触发 Spring AI MessageAggregator 的预期错误日志，测试断言与 HTTP 计数均通过，不把该日志误报成测试失败。

## 自审及边界

- 核对了意图先于准入、Redis 扣额/证据同 Lua、同步与流式发送 CAS、NOSEND/超时恢复 CAS、准入过期 Lua 围栏、原 F6 截止与 TTL，全部使用 tenant hash slot；业务 PG 写与读取保持 tenant 身份，恢复扫描为内部任务并逐行使用原 tenant。
- 不将“写了发送日志”当作已发送事实；发送 CAS 和实际 transport 调用之间的小窗口保守归类 SENT，避免不确定网络结果被零退款。没有因退款失败覆盖原业务异常。
- 保留 ADMIN、config_rev、完整 schema/实际 wire 计量与参数边界、历史绑定摘要和已有 F4/F6 修复。没有修改其他服务、部署脚本、运行进程或扩大模型支持范围。
- 新增流水不自动清理，以保留恢复与审计；告警前端、人工冲正、Redis 整体丢失/回滚后的重建及已发送未知 usage 的供应商补取仍未实现。PG 持续不可用或 Redis 无法持久保持证据时不宣称任意故障下 exactly-once；F6 已声明的恢复假设继续适用。
