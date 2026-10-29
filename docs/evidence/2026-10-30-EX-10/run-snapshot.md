# EX-10 能力包运行快照与版本一致性

RunService 沿原运行入口冻结包版本/摘要、软件、环境、route、全部逻辑 tool origins 及其选择状态；version_refs 同源保存。首次冻结用现有 requireRegistered 精确读口核实际技能 ID/版本/契约摘要与 guard 等字段，来源/route/图节点版本不一致在运行入库之前拒绝。回放仅核原冻结快照自洽，不读当前包或技能目录。旧无包应用保留原快照形状；未增加执行器、事实表或迁移。

最多两轮：

- 首轮 `.local/ex10-run-snapshot/snapshot-first-20261030-01/`：3 项=1 FAIL / 2 ERROR，nativeExit=1，raw targetedGate=false。发布元数据的 appVersionId 被夹具误设为字符串，抑制调度后容量/全局 claim 也不适用；不能声称是三项预期业务红。
- 修正上述夹具编号、隔离 claimNext 与容量，业务断言不变。第二轮 `mvn -B -ntp -pl engine-runtime test -Dtest=cn.agentplatform.engine.infra.CapabilityRunSnapshotTest,cn.agentplatform.engine.application.PromptFreezeDigestTest`：7 PASS，nativeExit=0，2 份新 XML，源稳定。完整参数与原始结果 `.local/ex10-run-snapshot/snapshot-second-20261030-01/`。不追加第三轮或全仓回归。

真实专用 engine_test PG 与 RuntimeClients 签名 HTTP；远端元数据为协议夹具，调度器只保留真实 admission/persist，禁止取其它队列/派发。本测试未执行模型/业务工具，不代表真实联合运行。ROOT 做一次非作者轻量代码检查，无阻断问题。

范围仅 RunService、新测试、能力包工程契约与本记录。回滚本地提交撤新冻结校验，保留已持久化历史快照。服务重建后的实时文档、实际联合业务及外部输入仍单列，EX-10 不因此冒称全部真链验收完成。
