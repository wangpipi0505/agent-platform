# 评测等待外部结果与排队状态修复

本工程 root 在核对后续调度兼容边界时阅读 `EvaluationService.execute/resume`，发现 pending 集合遗漏已有 `WAITING_EXTERNAL`：此类用例被写成终态REJECTED，随后resume直接返回旧报告，不能在原外部任务完成后继续评分。冻结详设的PENDING队列状态也须保持可恢复。此次只修复状态分类，不改评分口径、expected、数据集、候选或引擎请求幂等键；原契约未改，未接触相邻工程。

先在既有真实PG/HTTP测试中加入两个非终态反例和两个终态对照。`.local/evaluation-pending-state-red.log` 于06:27:05出现9项、2 failures、0 errors/skipped，两个失败均明确为 expected RUNNING but was REJECTED。没有以编译失败充当红测。

修复将WAITING_EXTERNAL和PENDING纳入既有pending集合，并解释等待态不能提前结案。测试在本工程PG25432专用测试库 `agent_platform_v12`（非开发运行库dev_v12）持久化报告，以测试目录内HTTP对端返回不同状态。两个等待态必须report=RUNNING、accepted=false、completed_at=null；对端随后完成，再resume为ACCEPTED，报告id、两个用例runId及调用计数2均不变。FAILED/CANCELLED仍REJECTED且有完成时间，再resume不重做。原安全失败、不可变数据集、owner限制及普通RUNNING恢复测试保留。

Java21，进程读取本工程.env不打印，Maven只使用独立.tools/m2。本轮定向9项测试包含新增4项，monitor完整回归为22项：

```text
mvn.cmd -o -B -ntp -pl monitor test
  -Dmaven.repo.local=<本工程>/.tools/m2
  -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
  "-DargLine=-Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Djava.io.tmpdir=V:/.local/tmp"
```

两个Java文件先用google-java-format1.25.2 AOSP格式化，再实际完整测试。`.local/evaluation-pending-state-monitor-full.log` 于06:27:33 BUILD SUCCESS，22/0/0/0。红测选择 `-Dtest=EvaluationPersistenceTest`，绿测无选择、未跳过任何monitor测试。冻结清单见 `evaluation-pending-state-sha256.json`。

本证据是root实现与执行，不冒认独立评审。待独立复核后更新开发进程。本次HTTP对端为协议fixture，PG为真实本工程专用测试基础设施；尚不代表完整异步评测、真实业务数据集、批量调度或生产IAM验收。

后续独立复核已完成，见 `review-evaluation-pending-state.md`：两个源码SHA匹配，评审者亲自执行9项定向通过，未发现范围内P0/P1/P2。之后才停止本工程原monitor PID51608，构建并启动新版PID44208、18085；`.local/evaluation-pending-state-reviewed-deploy.log` 于06:31:21 BUILD SUCCESS。未操作其他工程进程。
