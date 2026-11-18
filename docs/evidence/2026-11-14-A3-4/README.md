# A3-4 记忆 CRUD、状态和读写策略——证据补档（R-12）

- 台账归属：§7.4 阶段 3（预估 3 人日）
- 状态：**通过**（2026-11-18 用户 M2 批量签认）
- 交付日期：2026-11-14；补档日期：2026-11-19（R-12 批量补档）
- 合入提交：
  - `734fe1a7` A3-4(engine): 记忆CRUD/状态/读写策略——权限先过滤检索+DRAFT审批+CAS+网关登记
  - `5c344f18` merge: waveP2-1-A3-4（15/15 绿，A09 子集逐条）
  - 复查命令：`git log --oneline --all --grep="A3-4"`

## 验收口径（卡面摘录）

> MemoryApiTest 9/9 绿（A09 子集逐条：私有显式保存/授权共享读+USER 写 SHARED 403/CAS/DRAFT 审批按层/删除即退出/检索顺序/幂等/分页/目录行）+SessionListEndpointTest 回归绿；网关五路径显式接线（RoutesTest 12/12+verify-routes MISSING=0）。

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
export ENGINE_PG_TEST=true; set -a; source .env; set +a
mvn -pl engine-runtime test -Dtest=MemoryApiTest -DfailIfNoTests=false
```

## 关键输出摘要（2026-11-19 实跑复验）

```
[INFO] Running cn.agentplatform.engine.infra.MemoryApiTest
（Flyway: agent_platform_engine_test，schema engine=901 up to date；实跑日志含 AUDIT MEMORY_STATUS_FORBIDDEN/MEMORY_READ_FORBIDDEN——越权 403 审计在案）
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.016 s
```

**9/9 全绿**——与卡面判据数一致（A09 子集九条），越权 403 审计日志实跑可见。

## Surefire 报告指针

- `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.MemoryApiTest.txt`
- 报告 mtime：**2026-11-19 01:41:15**（本补档实跑产物）
