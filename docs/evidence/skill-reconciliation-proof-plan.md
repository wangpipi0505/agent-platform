# G2 不可变对账证明实施小计划

范围仅为冻结详设册20 F6、册31 F7/W1–W3的技能事实前置增量，不修改原契约、不生成重试许可、不重发业务、不实施取消三态。以真实PG和HTTP协议夹具验证，供应商/IAM/K8s验收另列。

1. 先建立真实PG/HTTP红测：明确NOT_APPLIED尚无证明、NOT_SENT无不可变证明；增加错key/job、未配置标记、404/5xx/超时、权限收窄、并发成功/新代、伪主体及重复冲突验证。
2. 新增 `V310__reconciliation_proof.sql`。每个tenant/call/attempt至多一份不可变证明，绑定原主体、run/node、技能版本与合同摘要、原始/守卫参数摘要、幂等键、发送状态、明确结论与来源摘要、PG身份和验证时间。DB触发器拒绝覆盖/删除。
3. 新增 `ReconciliationProofs` 和只读内部API：`GET /internal/skill-facts/{callId}/reconciliation-proofs/{attemptId}`。内部SERVICE精确角色、验签票据、tenant/principal限定。旧代证明可以读取，始终不表示当前允许重试。
4. `reconcile` 仅在原UNKNOWN、原代与绑定匹配、当前守卫覆盖历史范围且当前engine run/node仍活动时记录证明；HTTP不跨PG锁。真实查询返回后的短事务重验UNKNOWN/同代/原绑定，若回调已写成功/代次改变则拒绝。异步查询识别已配置NOT_APPLIED标记前必须证明原job/key，普通HTTP须证明原key。
5. 本地NOT_SENT仅由REGISTERED/send_started=false到NOT_SENT的可靠发送栅栏或已存在同代栅栏证明。响应仅增加proofRef/proofSha256，`retryBusinessAllowed=false`。普通错误维持UNKNOWN。
6. 格式检查、skill模块完整回归、源文件SHA冻结和边界报告后交独立评审；本任务不启停服务、不部署、不把该增量称作G2完成。
