# H1-6 工具管道融合+意图键+审批子项——证据补档（R-12）

- 台账归属：§7.2 阶段 1（预估 4 人日）
- 状态：**通过**（2026-11-14 用户 M1 批量签认；三半卡①②③）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `d5c68d90` H1-6/H1-8(contracts): 对外契约桩——工具调用会话事件/意图键/恢复事实（链规则先行定桩）
  - `b0d0818d` + `bed61325` 半卡①：共用登记入口的会话调用事件（八类回归 27/27 绿）
  - `6b738232` 半卡②：intent 键用例③定向测试——缺值首发 BUSINESS_INTENT_REQUIRED 不落待对账
  - `3410079a` + `ccc2f975` 半卡③：审批子项——never 派发前拦截/approval.asked 关联 actionPlanId/fail-closed APPROVAL_UNAVAILABLE（十类回归 32/32 绿）
  - 复查命令：`git log --oneline --all --grep="H1-6"`

## 验收口径（卡面摘录）

> 四场景、同键拦截、有会话的真实 Flow/计划步骤写入可定位的调用事件，工具越界不出站+意图三条用例（并发受理一次/改参数冲突/缺值参数错误）。

## 复现命令（代表类：意图键三用例之缺值分支）

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=BusinessIntentMissingValueTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验·代表类）

```
[INFO] Running cn.agentplatform.engine.infra.BusinessIntentMissingValueTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.425 s
```

**代表类 2/2 全绿**（缺值首发 BUSINESS_INTENT_REQUIRED 不落待对账链路实测复验）。
诚实登记：卡面"十类回归 32/32"为交付时点（半卡③ ccc2f975）的回归口径，本补档未整链复跑十类；工具越界不出站与审批子项的机制断言分布在 SessionEventsEndpointTest/AgentEngineShadowTest 等类中（均随本批次实跑全绿，见各卡目录），完整十类清单复现命令同上替换 `-Dtest` 即可。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.BusinessIntentMissingValueTest.txt`
- 报告 mtime：**2026-11-19 01:42**（本补档实跑产物，batch4）
