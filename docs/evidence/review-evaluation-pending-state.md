# 评测等待态独立评审

2026-10-20，独立评审者 review_test_inputs。**结论：APPROVE；本次 WAITING_EXTERNAL/PENDING 状态分类修复未发现 P0/P1/P2。** 该结论独立于 engine 运行入口评审中尚未闭环的会话预约 P1，不代表 engine 可以同步放行。

## 范围及摘要

已阅读 `evaluation-pending-state.md`、两文件冻结清单、EvaluationService.execute/resume/report、EvaluationPersistenceTest 及其真实 TCP fixture。重算 **2/2 SHA256 匹配**：

- EvaluationService.java：`770206f5f5ffa8b08198efa59884af28ffedb11340076ffb1d09d4aaaad4e034`。
- EvaluationPersistenceTest.java：`1136884ab7c44b66e39d5489f6e4f061c75fa1e88b885e6109229c577f9fc895`。

只评审 pending 集合新增的两个非终态及相应测试；未扩展调度实施、异步平台完整性或 proof 验收。只访问新工程，未修改业务代码、原证据或契约，未启停／部署服务。

## 代码复核

- WAITING_EXTERNAL 和 PENDING 与已有 CREATED/READY/RUNNING/WAITING_APPROVAL/WAITING_STEP 一起标记 pending；`accepted` 显式要求 `!pending`，因此等待中的安全／普通用例不会提前通过。
- `execute` 对仍等待的报告持久化 RUNNING、completed_at=null；终态判分仍要求 SUCCEEDED 且 expected 与完整 output 精确相等。FAILED/CANCELLED 均未加入 pending，也不可能被当成成功。
- `resume` 仍检查原发起人，已 ACCEPTED/REJECTED 直接返回；可恢复报告从PG读取冻结 candidate 与同版本dataset cases，沿原 reportId + caseId 发请求，未改引擎幂等键。
- 安全100%、通过率阈值、基线规则、数据集不可变、候选绑定、租户与owner恢复约束未因这两个状态放宽。
- 异常/transportError处理保持原逻辑。本修复仅使已知非终态继续可恢复，不宣称所有外部依赖错误或完整调度状态机已验收。

## 本评审亲自运行

Java21.0.12；本工程 `.env` 只载入进程，不打印凭据；离线Maven使用本工程 `.tools/m2`，无-am：

```text
mvn -o -B -ntp -pl monitor
  -Dmaven.repo.local=<本工程绝对路径>/.tools/m2
  -Djava.io.tmpdir=V:/.local/tmp
  "-DargLine=-Djava.io.tmpdir=V:/.local/tmp -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"
  -Dtest=EvaluationPersistenceTest test
```

**06:30:25 +08:00完成：9 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS。** 已释放 monitor target。

数据库为测试类明确指定的本工程 PG25432 `agent_platform_v12`，这是合法共享测试库，未连接 `agent_platform_dev_v12` 开发运行库。评分报告与dataset是真实PG数据，HTTP对端为测试内随机TCP端口fixture，无真实供应商调用。测试使用独立随机appCode；没有清理运行服务数据或重启服务。

亲自运行覆盖：两个新增状态各自保持RUNNING/accepted=false/completed_at=null；对端完成后原报告恢复ACCEPTED，原两个runId与唯一业务run计数2不变。两个终态对照仍REJECTED且有完成时间，resume不重做。已有安全失败、普通RUNNING恢复、其他owner拒绝及同版本dataset冲突一起通过。

注意：fixture中的calls计数表示按 reportId+caseId 创建的唯一业务run数，并非HTTP请求总数；resume仍会发幂等查询／运行请求。本报告不将“未新增业务run”误写为“完全无HTTP请求”。真正引擎的相同requestId读取逻辑已在独立运行入口评审中阅读，但此9项测试本身使用协议fixture。

另亲自执行 `node scripts/verify-contracts.mjs`：v1.2快照、四契约原文哈希、七模块依赖边界通过。root的9项红测中2失败与monitor22项全回归属于作者证据，本评审不将其计作本人重跑或与其他模块合并为同轮总数。
