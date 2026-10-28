# EX-00 owned_flow环境重验唯一失败的只读归因

日期：2026-10-28；执行者：`ex00_source_inventory`；源码基准 `ebbfd62ca1d19efc4d172804efb5ac79453ceb64`。只读新归档XML、安全summary及相关测试/必要调用方；未访问/输出properties/system-out、原始长消息，未读取真实配置/秘密，未运行任何测试/服务/PG或修改源码/台账/索引/HEAD，未使用superpower。

## 独立重验事实

本次为 `environment-recheck/20261028-181800-266`，不是主RunId after代码修复：

- [summary.json](environment-recheck/20261028-181800-266/summary.json)记录只新建了原先不存在的正向专用库 `agent_platform_iam_owned_flow_v12`，产品/配置源码未改；25个case为24 passed、1 failure、0 error、0 skipped；mergeIntoPrimaryBaseline=false。[重验HEAD](environment-recheck/20261028-181800-266/HEAD.txt)与本报告当前源基准相同。
- 唯一非通过：`cn.agentplatform.engine.infra.OwnedFlowMaterialsPostgresTest#lastNodePostCommitProofExpiryLeavesGrantUsableAndAggregateOnlyNewCurrentNeverRerunsNode`。
- [新XML](environment-recheck/20261028-181800-266/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.OwnedFlowMaterialsPostgresTest.xml)：failure / `org.opentest4j.AssertionFailedError`，必要本仓帧 `OwnedFlowMaterialsPostgresTest.java:478`，断言expected=15 / actual=17；没有明确产品错误码。

主基线中该类是专用库不存在造成的空name/BeforeAll错误，尚未运行到此方法；见[原类级XML](baseline/20261028-164553-272/engine-runtime-xml/TEST-cn.agentplatform.engine.infra.OwnedFlowMaterialsPostgresTest.xml)与[原归因](baseline-failure-attribution-rest.md)。这是环境前置恢复后首次观察到的存量业务路径/断言不一致，**不能直接记为“代码改动新增回归”**。它仍是真实非通过，不能按“只是环境变化”抹去。

## 已通过的前段断言与尚未执行的断言

测试文件完整路径为 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowMaterialsPostgresTest.java`：

1. `:447`～`:463`建立真实fixture和Worker，节点材料事务commit后通过hook将夹具时钟推进6秒。hook只有确认不存在未终态节点时才触发；原始PG commit已在 `RunAuthorityFixture.java:71`～`:75`返回后才执行hook。
2. `:464`～`:472`第一次poll/await后，本次已走过once=true、持久grant未被标blocked、run仍为RUNNING的断言。
3. `:473`～`:478`记录attempt总数和IAM请求数，将dispatch_next_at置为到期，再次poll/await；失败只在请求数预期calls+4处。本次expected15意味着基准calls=11，actual17即第二次实际增加6个请求。
4. `:479`～`:490`的attempt数量不变、最终SUCCEEDED、RUN_FINISHED仅1条均在失败断言之后，**本轮没有执行完成这些断言**。不能因为源码看起来会聚合就声称“节点绝未重跑/已成功结束”。

## +2请求的准确源码解释

`engine-runtime/src/test/java/cn/agentplatform/engine/infra/OwnedFlowSchedulerPostgresTest.java:186`～`:217`的Worker创建真实RunService/RunAuthority并复用原FlowDriver，只统计调用；`:236`～`:240`等待activeWorkers归零。夹具 `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:162`～`:171`的requestAttempts计数的是权威HTTP入口请求。

当前产品源码存在三个独立current阶段：

| 阶段 | 直接源码路径/行 | 当前行为 |
| --- | --- | --- |
| 选择已拥有run的应用类型 | `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java:2543`～`:2547` | driveOwned先用currentRetained读ownedApplicationType，再分FLOW/CHAT；这一门已经在当前源中 |
| 进入FLOW/start | 同文件`:2584`～`:2585` | 再取得当前proof执行startOwnedFlow |
| 聚合是独立动作 | 同文件`:2637`～`:2638` | 聚合前再次获取currentRetained，网络仍在owned事务之外 |

每次 `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java:54`～`:56`都执行resume与authorizeRetained两个在线操作。因此在全部节点终态、不再进入node执行循环的预期聚合路径上，**三阶段对应6次权威请求，正好能解释本次实际+6；旧断言+4只覆盖FLOW/start与aggregate两组，遗漏了前置应用类型门的两次请求**。

节点候选过滤在 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:1538`～`:1541`只保留READY/RUNNING，`:1603`～`:1605`再次拒绝终态节点进入claim；这为“调用计数差异不必意味着节点重算”提供源码依据。但当前XML只有总计数，没有逐请求operation与阶段关联记录，本文不把源码推导提升为本轮每一请求/最终业务状态的完整实测证明。

## 判定、承接与剩余

可证实的是**旧固定RPC数量断言与当前三阶段授权路径不一致**；本轮没有证据证明原grant被错误失效，也没有证据把+2定为产品越权或节点重跑。三阶段解释与实际数吻合，优先由 **EX-27** 的此方法/共享Worker授权观测夹具核定，保持已有产品类型读取、事务外current与独立聚合授权门；不为满足+4而移除安全检查，也不只把常量改6后宣称场景通过。

建议在获准的后续测试范围内，按调用阶段/operation/资源绑定说明应有的在线操作，并保留attempt不变、执行次数、最终run与单次RUN_FINISHED的原断言；用新的独立RunId定向重验。若顺序与资源证明不符合预期，或后续业务断言仍失败，再按实测失败登记产品责任卡。本文不实施或新建ID。

本次25case结果仅说明此次独立环境重验范围；不能把engine-runtime原883case、AP其它模块或整个EX-00视为已复验通过。AppCopy另一次1case环境重验已有summary，不在本文重复归因。原主基线缺库结果、新环境重验失败和后续任何修复结果都应分别保留，不能互相覆盖。
