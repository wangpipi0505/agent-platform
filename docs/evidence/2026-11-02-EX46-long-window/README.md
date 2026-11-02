# EX-46 长窗口：55s+ 连续续期与重启失效的验收补齐

## 1. 问题（缺口）

2026-11-02 复核退回 EX-46 时留下两处未验：

1. **55s+ 连续出流**：`59ef2b42` 已把 S3/S5 的登记 `expiresAt` 改成 `hard_deadline`、续期改走 `DecisionStore.touchExpiry(...)`，并有 `GatewayEventsWindowExpiryTest` 证明“过首个断言窗后登记还在、续期仍 200”。但那条用例只续期一次，没有覆盖“以 1s 间隔连续续期、跨过首个断言窗、直到 `hard_deadline` 才失效”的全周期；也没有证明续期途中被 `sweep()` 扫到不会断流。
2. **重启后行为**：`DecisionStore` 是进程内账本，重启会整体丢登记；此前没有用例把“新实例 → `find`/`touchExpiry` 为空 → 续期入口 403”钉住，证据文档里也只写了“既有债务”，没有明确“这不是静默容忍，而是显式 403”。

55s 无法在单测里真等，所以先把硬窗做成可缩短的配置；**断言窗仍是 1..5s、客户端仍是 55s 夹取**，两者都不动。

## 2. 改动

| 文件 | 改动 |
| --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/config/AuthorityServiceProperties.java` | 新增 `@DefaultValue("55") int eventsHardWindowSeconds`（`agent.authority.events-hard-window-seconds`）；越界 `5..55` 抛 `IllegalStateException("AUTHORITY_TTL_INVALID: events-hard-window-seconds 必须在 5..55")`，启动即失败 |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | 删除 `EVENTS_OPEN_MAX_SECONDS = 55L`，S3/S4/S5 三处 `hard_deadline` 上界改读 `properties.eventsHardWindowSeconds()`；默认 55 时生产行为与之前等价 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsLongWindowTest.java` | 新增：硬窗 6s + 断言窗 1s，5 次续期跨首个断言窗、sweep 触发、到点 403 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/DecisionStoreRestartTest.java` | 新增：换新 `DecisionStore` 实例模拟重启，store 层 fail-closed + `DelegatedAuthority.eventsWindowCheck` 403 |
| `authority-service/src/test/java/cn/agentplatform/authority/config/EventsHardWindowPropertiesTest.java` | 新增：默认 55、边界 5/55 可启动、4/56 启动失败且 message 稳定 |

未改 `admin-common` / engine / gateway，未改客户端夹取与 `assertion-seconds` 上限，未改既有断言。`.local/` 与各 yml 不需要改动（缺省 55 由 `@DefaultValue` 提供，既有上下文照常启动）。

## 3. 用例表

| 用例 | 断言 |
| --- | --- |
| `GatewayEventsLongWindowTest#renewalsCrossTheFirstAssertionWindowAndStopAtTheHardDeadline` | 开窗 `hard_deadline - now ∈ [5,7]`；5 次续期（间隔 1s，第 3 次前开第二张窗口触发 `sweep()`）全部 200，`decision_id`/`continuation_ref` 不变，`checked_until` 单调前推且 `<= hard_deadline`、`exp == checked_until`；中途登记仍 `present` 且 `expiresAt == hard_deadline`；越过 `hard_deadline` 后 403 + `code=SYS-AUTH-403` + `message=当前授权不允许此操作`；此时登记仍 `present`（证明 403 来自 continuation 到点，而不是登记被清） |
| `DecisionStoreRestartTest#aRestartedLedgerFailsClosedAndTheWindowCheckReturns403` | 旧账本续期 200；新 `DecisionStore` 的 `find`/`findByContinuationJti` 为空、`touchExpiry` 返回 empty 且不创建登记；用新账本构造的 `DelegatedAuthority.eventsWindowCheck` 返回 403 `SYS-AUTH-403`，`auditReason=EVENTS_CHECK_CONTINUATION` |
| `GatewayEventsWindowExpiryTest`（既有，未改） | 首个断言窗后登记不被 `sweep()` 清掉、续期 200 |
| `DecisionStoreTest`（既有，未改） | `touchExpiry` 推到硬截止、已消费不复活 |
| `EventsHardWindowPropertiesTest` | 配置绑定契约：默认 55；5/55 可启动；4/56 启动失败且根因 message 稳定 |

## 4. 实测 `Tests run:`

命令（`JAVA_HOME` 指向 Temurin 21.0.12）：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -o -pl authority-service test \
  -Dtest='GatewayEventsLongWindowTest,DecisionStoreRestartTest,GatewayEventsWindowExpiryTest,DecisionStoreTest' \
  -DfailIfNoTests=false
```

原文：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.047 s -- in cn.agentplatform.authority.DecisionStoreTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.271 s -- in cn.agentplatform.common.authority.DecisionStoreRestartTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.906 s -- in cn.agentplatform.common.authority.GatewayEventsLongWindowTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.481 s -- in cn.agentplatform.common.authority.GatewayEventsWindowExpiryTest
[INFO]
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

补充（配置绑定契约单独跑）：

```bash
mvn -o -pl authority-service test -Dtest='EventsHardWindowPropertiesTest' -DfailIfNoTests=false
```

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.351 s -- in cn.agentplatform.authority.config.EventsHardWindowPropertiesTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

模块全量（含既有 1c/1d/1e/1f 契约，确认 `hard_deadline` 改读配置没有回归）：

```
[INFO] Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 5. 实测行为摘录

同一次运行里 6s 硬窗的审计轨迹（摘录，字段有删减；`decision_id=dec-f9455320-…`）：

```
permit portal_gateway_events_open  hard_deadline=1793607375 checked_until=1793607370
permit portal_gateway_events_check checked_until=1793607370 hard_deadline=1793607375
permit portal_gateway_events_check checked_until=1793607371 hard_deadline=1793607375
permit portal_gateway_events_check checked_until=1793607372 hard_deadline=1793607375
permit portal_gateway_events_check checked_until=1793607373 hard_deadline=1793607375
permit portal_gateway_events_check checked_until=1793607374 hard_deadline=1793607375
deny   portal_gateway_events_check status=403 code=SYS-AUTH-403 reason=EVENTS_CHECK_CONTINUATION
```

即：每次续期只前推 1s（`assertion-seconds=1`）；续期途中（第 3 次前）第二张窗口的 `put` 触发过 `sweep()`，登记仍在；`now > hard_deadline` 后同一条 continuation 被拒。

**为什么是 403 而不是 409**：continuation 的 `exp` 就等于 `hard_deadline`，`KeyRing.verify` 对 `exp<=now` 一律按 403 拒绝，`eventsWindowCheck` 再把它归一成 `EVENTS_CHECK_CONTINUATION`。这一点在 `DelegatedAuthority.java:1165` 的注释里已写明（“过期不在这一层区分……客户端不应该重试”）。因此本条按实现断言 403，而任务描述初写的 409 与实现不符；`EVENTS_CHECK_EXPIRED`（409）只作为防御性检查存在。

## 6. 重启后的口径（写进验收文档）

- **不是静默容忍**：新进程上 continuation 仍能验签、`now` 也可能远未到 `hard_deadline`，但 `DecisionStore.findByContinuationJti` 落空，`eventsWindowCheck` 返回 **403 `SYS-AUTH-403`（auditReason `EVENTS_CHECK_CONTINUATION`）**。客户端收到 403 会结束流，不会拿旧窗口继续出流。
- **为什么是 fail-closed**：账本是进程内的（`DecisionStore` 类注释：EX-44 §6.1 已接受为显式债务）。重启、实例替换、滚动发布都会让旧窗口立即失效；这是单实例部署口径下的已知行为，不是“重启后还能续”的假象。
- **持久化方案**：EX-46 的 PG 迁移由唯一约束接管（`decision_id` / `continuation_jti`），届时重启不再丢登记；本次不实现。
- 测试用“同配置、同密钥、只换一个空 `DecisionStore` 实例”模拟重启，不启动第二个 JVM；这样能精确断言拒绝原因，而不是只看 HTTP 403。

## 7. 发现（未修，只报告）

- `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java:976-979` 仍有遗留调试输出：
  `System.out.println("DEBUG-S5 imm nonce=" + admissionNonce + " sha=" + ... )`，
  会把 admission nonce / request sha / decision id / ticket jti / consumer 打到 stdout。
  不是功能缺陷，但属真实调试残留（生产日志噪音 + 内部标识外泄），建议单独清理；本卡为避免扩大改动未动。
- 除上述外未发现功能缺陷：模块全量 103 例通过，默认配置与既有测试/yml 兼容。

## 8. 未覆盖

- **未起真六服务跑 55s+ SSE**：本卡只验 authority 侧契约（续期、sweep、重启）；gateway→engine→skill 的连续出流仍需集成环境验收。
- **未做真实进程重启**：用新 `DecisionStore` 实例模拟；真实 JVM/容器重启行为等价，但未在部署层验证。
- **PG 持久化未实现**：重启丢登记仍是显式债务（EX-46 迁移卡承接）。
- **多实例/滚动发布的可用性影响**：未验；当前口径仍是单实例。
- **客户端 403 后结束流的端到端**：未跑（需要 gateway SSE 集成测试）。
- **容量淘汰**：`capacity` 在窗口密集时仍可能提前清掉合法窗口，属既有边界，未在本卡扩张。
