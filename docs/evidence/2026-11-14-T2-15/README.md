# T2-15 外部系统联调（首期 SERVICE AGENT）——证据补档（R-12）

- 台账归属：§7.3 阶段 2（预估 1.5 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `39658c9c` T2-15(engine,gateway): 外部集成白名单——SERVICE仅AGENT四端点/mode强制/归属核验
  - `bdb1785e` merge: waveP2-T2-15（5/5 绿+Routes 15/15+IntegrationSecurity 7/7）
  - 复查命令：`git log --oneline --all --grep="T2-15"`

## 验收口径（卡面摘录）

> ExternalIntegrationTest 5/5+TasksApiTest 6/6=11/11 绿；IntegrationSecurityTest 7/7+IntegrationRateLimit 6/6；白名单四端点 integrationAllowed=true（POST tasks/GET detail/GET events/GET result）+9 条拒签名录逐一核对+mode 强制 AGENT 落引擎（403 SERVICE_AGENT_ONLY+审计行）+归属核验（他人任务 404/跨应用 403/白名单外 403 INTEGRATION_PATH_FORBIDDEN 审计）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=ExternalIntegrationTest -DfailIfNoTests=false
# 网关侧：mvn -pl platform-gateway test -Dtest='IntegrationSecurityTest,IntegrationRateLimitTest'
```

## 关键输出摘要（2026-11-19 实跑复验·引擎主类）

```
[INFO] Running cn.agentplatform.engine.application.ExternalIntegrationTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.407 s
```

**主类 5/5 全绿**（同批 TasksApiTest 7/7，合计 12/12）。网关侧 IntegrationSecurityTest 7/7 于 2026-11-18 R-4 重跑归档（`docs/evidence/2026-11-18-gateway/`）；IntegrationRateLimit 未在本补档复跑（复现命令附上，诚实登记）。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.application.ExternalIntegrationTest.txt`
- 报告 mtime：**2026-11-19 01:39:58**（本补档实跑产物）
