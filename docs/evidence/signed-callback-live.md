# 签名 CALLBACK 的实际引擎纵切

2026-10-20，在 `review-skill-callback.md` 独立评审无确定P1/P2后，更新本工程 skill PID49204、18083，V309迁移通过；主 engine PID57412、18082。构建启动日志 `.local/skill-callback-reviewed-deploy.log`。未改相邻工程、原详设或契约。

运行 `node tests/smoke-signed-callback.mjs`，最终退出0，原始证据 `.local/signed-callback-live.log` / `.json`。脚本独占28416的本地提供方fixture，并只在内存生成测试HMAC密钥、按现AES-GCM记录格式写入本工程专用vault；没有使用真实集成长密钥、没有向后台出票桥传递凭据。服务JWT是本地开发测试签发，保留主体、严格internal受众/service用途，不代表生产relay。

| 场景 | runId / callId | 实际判定 |
| --- | --- | --- |
| 成功、重复收件与摘要冲突 | r_f4954f7ef22243a28f2bb442045b6bbf / 56 | 外部提交1；STORED；W3按相同factId独立采纳1；技能节点逻辑提交1；SUCCEEDED |
| 合法FAILED无result | r_9856bedf8b344ab6a467d2f567365ec5 / 58 | 外部提交1；错误语义QUOTA，structured=null；engine接纳，最终FAILED，无成功逻辑提交 |
| ACK前早到后重投 | r_a94a994f690a44ef817e7c68bbb8befe / 60 | 外部ACK用latch延迟；先409/WAITING_BINDING，释放ACK后同原字节event重投STORED；提交仍1、逻辑提交1、SUCCEEDED |
| 本地取消后迟到 | r_39a574052417419991d5844e9bfc8251 / 62 | 本地CANCELLED后签名消息只记LATE；原UNKNOWN事实未被改成功，逻辑提交0，运行不复活 |

成功场景还实际验证用户票据403、坏HMAC401，拒绝后receipt为0；有效收件后同event不同正文409。所有场景receipt各一条，业务提交各一次；管理回包只有secretConfigured，不返回opaque引用。

**投递与采纳不能混同。** 成功场景中引擎 ExecutionRecovery 在 outbox 之前按持久 `sf:callId:attemptId` 证明先行采纳，故技能旧代投递随后是LATE、回调回包logicalAccepted=false；PG中VERIFIED_FACT_ADOPTED对应同一factId且节点仅一次LOGIC_RESULT_COMMITTED、运行SUCCEEDED。回包的布尔字段是该投递观察，不能据false推断引擎全局未采纳；最终逻辑结果以engine权威状态/事件为准。此试验同时覆盖了实际W3竞速路径。

最初两次脚本未通过：先假定过滤器403一定有JSON正文，随后又假定成功只能通过原outbox确认。分别保留 `.local/signed-callback-live-first-failure.log` / `-second-failure.log`；修正测试对空拒绝正文的处理，增加同factId的W3独立采纳断言，未修改业务代码或放宽唯一逻辑提交。首轮遗留本工程fixture运行 r_9ab72214e3f4423798f2620b6e194166 已显式取消、保留审计；脚本最终finally也只取消自身未终态fixture运行。没有删除运行或事实。

fixture socket与服务器结束关闭，vault密文保留供冻结测试记录引用。此结果不证明外部停止或效果撤销，不是取消三态完成；未做真实供应商/可信relay/OS kill/容量验收。NOT_APPLIED授权恢复与人工产品闭环仍按后续方案实现。

## 回包字段澄清后的再次纵切

随后将新增回包中容易与全局采纳混淆的 `logicalAccepted` 改为 `deliveryAccepted`，仅表示本次 outbox 投递观察；没有新增引擎查询或修改采纳判定。修复经133项完整技能测试及独立复核后，更新本工程 skill PID63724，日志 `.local/skill-callback-delivery-reviewed-deploy.log`。原132阶段日志及JSON保留为 `.local/signed-callback-live-stage132.log` / `.json`。

再次执行相同真实纵切脚本退出0；脚本额外断言回包不再含 `logicalAccepted` 且 `deliveryAccepted` 为布尔值。当前 `.local/signed-callback-live.log` / `.json` 对应本轮：

| 场景 | runId / callId | 本次投递观察与最终状态 |
| --- | --- | --- |
| 成功 | r_4ad07428b23a4eaebd490e5bc6a7ac85 / 63 | deliveryAccepted=false；W3按同factId采纳1，逻辑提交1，SUCCEEDED |
| FAILED无result | r_12bb1d62e2d4474dbc142af2637afdf7 / 65 | deliveryAccepted=true；QUOTA、structured=null、FAILED |
| ACK前早到再重投 | r_8d180eb3d6bd4abf94799029ee4c97c0 / 67 | WAITING_BINDING后同消息重投STORED；deliveryAccepted=true；逻辑提交1，SUCCEEDED |
| 本地取消后迟到 | r_1312deccf07d419595c9a8121a663c84 / 69 | receipt=LATE、deliveryAccepted=false；原事实仍UNKNOWN，逻辑提交0、CANCELLED |

四场景仍各仅一次外部提交、各一条receipt；用户403、坏签名401、同event摘要冲突409断言保持。成功场景再次真实经过W3先采纳路径。该新增字段更名没有修改冻结原契约正文，仍不代表取消三态或真实relay验收完成。

skill167证明及票据修复独立关闭后，又在PID18484/engine59704上完整重跑四场景退出0，仍各提交一次；成功中的W3先行采纳、FAILED无result、早到重投及取消不复活均保持。上面的133阶段原始日志/JSON另保留为 `.local/signed-callback-live-stage133.log` / `.json`；当前无阶段后缀文件对应167阶段，收集时outbox状态可能仍PENDING，逻辑采纳仍以同factId的W3与唯一提交断言为准。见 `skill-reconciliation-proof-live.md`，不以观察时刻outbox尚未完成推断需要重新业务提交。
