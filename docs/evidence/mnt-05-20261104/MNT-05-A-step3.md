# MNT-05 子步3（A 席）：恢复协调抽取为 RunRecoveryCoordinator

- 日期：2026-11-04
- 席位：A
- 基线：`ab0c7f17`（MNT-05 受理子步）；本子步工作树上的分支 HEAD 已含 F 席 `906da0e8`（UI-07），未触碰其文件
- 范围：engine-runtime 调度器的三条恢复回调——恢复分发、节点租约续期、外部事实接续
- 未触：公开 Controller、受理事务、数据库 schema、`drive`/`driveOwned` 执行状态机、`versionRefs`、网关

## 一、原责任 → 新责任

| 原责任（RunService） | 新责任（RunRecoveryCoordinator） | 输入/输出 | 保留语义 |
| --- | --- | --- | --- |
| `driveRecovered(long)` 的受保护/legacy 分流 | `recovered(long, Handler)` | run id + 回调面；无返回 | 受保护运行直接交回 owned 执行；legacy 运行从 `guard_ctx` 重建 Actor |
| legacy guard 快照与 run 归属核对 | 同上 | 读 `engine.run.guard_ctx` | tenant/principal 不一致时回调 `guardRejected`，门面仍以 `stopForBudget(...,"GUARD_SNAPSHOT_INVALID")` 停止 |
| `renewLeases()` 的节点/工具租期续期 | `renewLeases(owner, activeNodes)` | owner 字符串与活动节点集合 | 每节点独立短事务；`skip locked`；owner 前缀 `owner_%`；失败只记 warn、不中断其他节点 |
| `resumeExternalFacts()` 的等待节点唤醒 | `resumeExternalFacts()` | 无 | 单事务内锁 run、翻节点 READY、清 run error、`scheduler.wake`、写 `EXTERNAL_READY`；不重放技能 |
| 调度器 `bind` 的三条入口 | 门面仍绑定 `driveRecovered`/`renewLeases`/`resumeExternalFacts` | 同签名 | 调度入口与公开 `recover()` 不变 |

留在 `RunService`：`drive`/`driveOwned`/`driveOwnedInitial`/`driveOwnedFlow` 执行驱动（正常受理与恢复共用，不属恢复协调）、`markWaitingExternal`（执行期写等待快照）、`stopForBudget`、持久化与状态组装。

## 二、改动文件

| 文件 | 行数 | 变化 |
| --- | --- | --- |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 2446（原 2521） | 新增 `recovery` 字段与 `recoveryHandler` 回调面；生产构造器增加第 8 个 `RunRecoveryCoordinator` 参数；兼容构造器内建默认实例；三个恢复方法改为薄委托；删除已迁移实现 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunRecoveryCoordinator.java` | 177 | 新增；`@Service`，构造 `(TraceStore, RunScheduler)` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunRecoveryCoordinatorTest.java` | 156 | 新增 6 例 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/application/RunSnapshotAssemblyTest.java` | 58 | 断言 `RunRecoveryCoordinator` 与容器 bean 同一 |

分支上 F 席与 O 席的未跟踪/已提交文件未暂存、未修改。

## 三、不变量

1. 调度入口不变：`scheduler.bind(this::driveRecovered, this::renewLeases, this::resumeExternalFacts)` 仍在 `RunService` 构造器内，三方法签名不变；`recover()` 仍只触发 `scheduler.poll()`。
2. 公开门面不变：测试可直接调用 `runs.recover()`、`runs.renewLeases()`、`runs.resumeExternalFacts()`；`CrossServiceTraceTest` 反射的 `driveRecovered(long)` 仍存在。
3. 执行状态机不复制：协调器只做路径选择、租约续期与状态翻转；`driveOwned*` 的调用、锁与提交仍在门面，避免两套状态机。
4. 事务边界不变：`renewLeases` 每节点一个短事务；`resumeExternalFacts` 每节点一个短事务并在事务内 `lockRun`；`recovered` 不开事务。
5. 锁顺序不变：外部事实仍先 `lockRun(rid)` 再更新 node/run；租约续期仍 `for update skip locked`。
6. 网络不进长事务：本子步没有新增任何 HTTP 调用；恢复分发不触网。
7. 安全模式不变：iam 下受保护运行仍走 `driveOwned` → `runAuthority.currentRetained` 的 fail-closed 链；非 iam 的 legacy 恢复仍从落库 guard 重建 Actor，不伪造角色。
8. 装配无旁路：生产构造器显式要求 `RunRecoveryCoordinator` bean；旧 `new RunService(...)` 夹具构造期自建默认实例；新增 `recovery()` 仅包可见，供装配测试断言同一实例。

## 四、命令与结果

| 轮次 | 命令 | 结果 |
| --- | --- | --- |
| 1 | `mvn -q -pl engine-runtime -am -DskipTests compile` | exit 0 |
| 2 | `mvn -q -pl engine-runtime -am -DskipTests test-compile` | exit 0 |
| 3 | `mvn -q -pl engine-runtime -am -Dtest='RunRecoveryCoordinatorTest,RunSnapshotAssemblyTest,RunAuthorityAssemblyTest,RunAdmissionServiceTest,RunServiceContextPackTest,PromptFreezeDigestTest,RunToolProfileTest' -Dsurefire.failIfNoSpecifiedTests=false test` | exit 0；6/1/4/8/9/4/13 全绿（45 例） |
| 4 | 真实 PG 定向（`AGENT_DB_*` + `ENGINE_PG_TEST=true`） | 无法执行：35432/25432 均 down；shell 无 `AGENT_DB_URL` 等变量；本机无 postgres 进程/安装目录/docker 容器 |

## 五、未覆盖

- 功能检查要求的三条真实 PG 证据本轮未取得：恢复后同 call 继续零重复副作用、租约过期重新受理、外部事实接续一致。
- `RunAuthorityLiveResumeTest` 需 `EX26_LIVE_RESUME=true`、真实 18086 authority 与 PG，未跑。
- `ExecutionRecoveryTest`、`ExternalJobStateTest`、`RunSchedulerReviewPostgresTest`、`RunSchedulerPostgresTest` 均 `ENGINE_PG_TEST` 门控，未跑。
- `driveOwned` 的幂等/提交路径未在本轮通过 HTTP 或 PG 复验；仅由既有实现保持原样。

## 六、白名单外改动 / 契约缺口

- 白名单外仅：测试文件 `RunSnapshotAssemblyTest` 扩展、包可见 `recovery()` 访问器（沿用 `snapshots()`/`admissions()` 受控模式）。
- 未新增错误码、路由或数据库字段。
- 契约缺口：恢复协调暂无独立健康/指标面，`renewLeases` 失败仍只记 warn；本轮不改。

## 七、回退

- `git revert <本子步提交>`：恢复 `RunService` 内联三条回调，删除 `RunRecoveryCoordinator` 与两个测试扩展。
- 数据影响：无 schema/数据迁移；租约、外部事实与已恢复运行的事实不变。
