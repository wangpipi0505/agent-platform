# 算法已知失败与只读重试的实施裁决

root依据冻结设计册20八步管道⑤、册30节点只读重试，以及当前RunService/SkillExecution实际路径作本增量裁决；不修改原四契约。补充算法任务brief/只读计划中的笼统“所有可信Job Failed都RETRYABLE_FAILURE”分类，避免把确定性原Job终态错误当作可重新创建Job的授权。

1. **原Job可信Failed是该任务的确定终态。** 保留完整原身份/UID/失败证据，落FAILED，按现有不可重试错误语义NON_RETRYABLE_FAILURE交付。该观察不证明未生效，不触发同call自动重建或删除原Job，不生成新随机key。必须有“原Job已Failed/重复execute/reconcile”的有限正例：一次创建、原终态和UID保持、引擎能正常消费失败，不因新节点attempt与旧binding不匹配而卡住。成功/失败均须原fact→binding同代短事务原子采纳。此处是新算法分类计划的细化，不宣称原设计要求所有Job Failed都可重建。
2. **明确未开始创建的只读暂时故障保留既有重试能力。** 例如首次Job GET临时不可用/超时/连接拒绝，且该原代send_started=false，生产代码必须先在真实PG以完整原身份CAS将REGISTERED栅栏为NOT_SENT，证明所有旧sender均失去发送权；只有此明确未发送路径可落FAILED/RETRYABLE_FAILURE供原引擎只读重试。CAS败/已有send_started/原Job可能已存在时保持UNKNOWN或原可信终态，不靠一个本地布尔断定未发送。
3. **只读新代移交是窄边界，不是UNKNOWN reset。** 原事实必须FAILED/RETRYABLE_FAILURE、原合同readonly=true且sideEffect=false、固定call/版本/原参数/key不变、原binding可靠NOT_SENT；SkillExecution已核引擎新REGISTERED/新claim原attempt和当前守卫后，才允许同一短事务归档原事实和完整binding、创建本次新attempt的PREPARING/REGISTERED持久图像。新尝试参数仍显式传递，不从当前行补原代。旧记录/证明不可覆盖。当前或旧事实UNKNOWN、send_started=true、可信Job终态、未核新引擎权威都不能进入此分支。
4. **守住登记和完成时序。** 原代归档/新fact与binding准备必须原子、先于新网络，不以post-send归档弥补；最终完成CAS应对已准备的新attempt，不再用已归档旧attempt替换当前行。原PREPARING不交付给引擎。若新attempt在submitted/守卫/取消/租约处失效，新POST=0；旧submitted ACK线程即使恢复也因原attempt和NOT_SENT栅栏零POST。不能把此仅已知失败只读分支扩成写许可。
5. **不引入“当前节点代随意映射旧外部发送代”。** 本轮仍沿原同代事实/UID模型。已知原Job只读终态用第1条完成，不制造虚构的新执行事实；UNKNOWN走原查询。将来G2确需区分节点租约代与原外部发送代时单独设计并评审，不在此用读当前binding替代原attempt。
6. **测试与范围。** 先以真实PG/有限HTTP取得行为红，覆盖未POST只读暂时失败→原引擎授权新attempt→成功（总业务POST1）、两个新代并发只一次、新代准备事务回滚、旧ACK迟到零POST、UNKNOWN/sideEffect/无授权/旧Job终态禁止该移交；原Job Failed直接交付且不额外安排可发送代。HTTP/MCP已有合法只读重试继续原样回归。若需V311内新增小型binding历史表以保留上述原代证据可以纳入同一迁移和升级测试；不得改engine生产、抢后续迁移或关闭未知结果门禁。

若实现确认某个必要边界无法在本轮安全完成，先明确报告该边界与失败证据；不能以额外兼容标志、body自报许可、吞掉binding不匹配或改为全部禁止只读重试掩盖问题。完整G2 UNKNOWN证明许可、可信当前IAM、写重试和取消三态仍属后续增量。
