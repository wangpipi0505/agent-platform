# EX-31 配额拒绝码：验收失败的真实原因（2026-11-02）

## 1. 现象

`RunQuotaAdmissionPostgresTest` 的 `tenantOverrunRejectsBeforeRunInsertAndReleasesAdmissionSlot`
与 `appLevelOverrunRejectsReadableBeforeRunInsert` 两条用例期望 `QUOTA`（429），实得
`ENGINE_BUSY`（429）。单方法隔离运行同样复现，所以一开始看起来是“判定顺序”缺陷。

## 2. 根因

`RunScheduler.reserveInteractive()`（`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunScheduler.java:203-226`）
在拿到许可后**先** `repository.claimNext(INTERACTIVE, owner, lease)`：若队列里还有可认领的旧行，
它会把该行启动、然后主动抛 `RunAdmissionRejected(false)`（即 `ENGINE_BUSY`），目的是“先做已受理的活”。

共享测试库 `agent_platform_engine_test` 里积压了 **48 条 `status='RUNNING'` 且租约早已过期**的
历史行（来自 10-27 之后的各轮测试，进程退出后没被清理）。它们全部可被 `claimNext` 认领，
于是每一次 `create` 都先领走一条旧行并返回 `ENGINE_BUSY`，配额判定（`RunService.java:711`）
根本没轮到。

## 3. 处置与验证

1. 把测试库里这批残留行收敛为 `FAILED`（`update engine.run set status='FAILED' where status='RUNNING'`，
   48 行）——它们是死进程的遗留，不是有效在途工作。
2. 重跑：`RunQuotaAdmissionPostgresTest` **3/3**、`RunQuotaPostgresTest` **10/10**，BUILD SUCCESS。

## 4. 结论与后续

- **EX-31 的产品行为符合卡片要求**：超限在 `engine.run` 插入前以 `QUOTA` 拒绝，被拒运行不落库，
  受理槽位照常释放。之前的失败是测试库状态污染，不是顺序缺陷。
- 仍然存在一个语义边界：引擎有旧行可领时，`ENGINE_BUSY` 会先于 `QUOTA` 返回。是否把配额判定
  提到受理之前，需要一次口径确认（会让“已受理的旧工作优先”让位于“租户配额优先”）；本轮
  不改，记入台账 §18.7.1 的待定项。
- 测试基座债务（G-8）新增一条：**共享 PG 的残留 RUNNING 行会改变受理语义**，测试前置应清理
  或按租约收敛，否则同类“期望 QUOTA 实得 ENGINE_BUSY”会反复出现。
