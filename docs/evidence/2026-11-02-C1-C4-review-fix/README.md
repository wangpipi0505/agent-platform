# C1–C4 复核修复（用户 m10220 指出的四项缺陷）

日期：2026-11-02　范围：`authority-service`（EX-26/EX-46）、`scripts/acceptance`（EX-06）
关联提交：EX-26 `3e39d8a5`、EX-46 `3bf2d2a8`、EX-12 `ed99d60`(ontorepo)、EX-06 `c1bb506d`

## 1 问题的来源

用户按 `file:line` 逐条指出四项缺陷，并要求把 EX-06/12/26/46 从 `待验收` 退回 `进行中`。
在此之前我的“C1–C4 全部落地、开发面清零”结论不成立：四张卡的**主路径**通了，但都缺一段闭环。

| 卡片 | 用户指出的问题 |
| --- | --- |
| EX-26 | `resume` 已能签票，但消费新票时因缺路由返回 `403 ROUTE_NOT_REGISTERED`，生产恢复链没闭合 |
| EX-46 | 续期把旧窗口快照整条写回；与 child 换票并发时会覆盖已写入的消费标记，同一授权可再次换票 |
| EX-06 | All 只按“J1–J4 通过 + 字段非空”标完整，未核 J5–J9；S1 的 C 段强制 UUID，而真实 runId 是 `r_<32hex>` |
| EX-12 | 契约与生成客户端用 `tenant_id/error_count`，真实响应是 `tenantId/errorCount`，检查器反而固化了错名 |

## 2 逐项修复

### 2.1 EX-26 恢复票消费（`DelegatedAuthority.fromTicket`）

- 根因：`fromTicket` 只认目录 `routes.permits(upstream, target, action, DELEGATED)`；而 `resume` 签出的是
  `engine→engine run.advance` 的**本地续接边**，目录里没有该行（它由 `ServiceRoutePolicy.localRunAdvance` 表达）。
- 修法：签发侧与消费侧共用同一条规则——目录不行时再试 `localRunAdvance(upstream, call, MODE, "DELEGATED", originKind)`，
  `originKind` 取票面 `operation.origin_kind`（`PORTAL`），**不能**用 `origin_authentication_id`（那是 `oa1:` 身份串）。
- 用例：`DelegatedResumeContractTest#resumeTicketCanBeConsumedByTheSameEngineLocalAdvance`
  （`resume` → `engine.authorize(ticket, advance)` → 断言 `decisionId` 非空）。

### 2.2 EX-46 续期与并发换票（`DecisionStore.touchExpiry`）

- 根因：`eventsWindowCheck` 用 `decisions.put(window.withExpiresAt(hardDeadline))` 把**整条**旧快照写回；
  `put` 是无条件 `entries.put`，会把并发 child 换票刚写入的 `consumedAt/consumedBy` 擦掉。
- 修法：新增 `Optional<Decision> touchExpiry(String id, Instant until)`，在 `entries.compute` 的同一把 bin 锁内
  读-改-写，只改 `expiresAt`；已消费或不存在时返回 `empty` 且**不动**登记。调用点改为
  `if (decisions.touchExpiry(...).isEmpty()) throw AuthorityException.denied("EVENTS_CHECK_CONTINUATION")`。
- 用例：`DecisionStoreTest#touchExpiryExtendsTheRegistrationToTheHardDeadline`、
  `#touchExpiryNeverResurrectsAConsumedRegistration`。

### 2.3 EX-06 All 完整性判定与 S1 的 runId 形状

- `Prepare-Joint.ps1`：新增 `$requiredStages=@('J1'..'J9')` 与 `$missingStages`；
  `verdict` 在“字段齐 + 阶段齐”时才算 PASS，`complete` 同时要求两者为空，manifest 增 `missingStages`，
  BLOCKED 行打印 `missingStages=`。含义：本轮只交付 J1–J4，**不允许**再标 `complete=true`。
- `Invoke-S1Acceptance.ps1`：C 段 runId 校验改为 `^(r_[0-9a-f]{32}|<uuid>)$`，提示与错误码同步。
- 复核：两脚本恢复 UTF-8 BOM + CRLF 后 `Parser::ParseFile` 均为 0 错误；S1 `-DryRun` 仍按段输出
  `[SKIPPED-BLOCKED]`（C 段提示已是 `r_<32hex> 或 UUID`）。

### 2.4 EX-12 契约字段名（ontorepo）

- 根因：`onto-common/src/main/java/cn/ontorepo/common/infra/Db.java` 的 `list` 用 `getColumnLabel` 转驼峰，
  响应是 `tenantId/appCode/toolCode/errorCount`；契约与 `generated.ts` 却写成 snake_case。
- 修法：`contracts/openapi.json` 的两个 schema 改 camelCase；`deploy/Verify-ApiContract.mjs` 的
  `observationSummary` 改查 camelCase，并加“出现 snake_case 即失败”的反向断言；`generated.ts` 重新生成。
- 复核：`node deploy/Verify-ApiContract.mjs` → `passed=true`。

## 3 测试（本人实跑）

```
mvn -o -pl authority-service test \
  -Dtest='DecisionStoreTest,DelegatedResumeContractTest,GatewayEventsWindowExpiryTest'
→ DecisionStoreTest 5/5、DelegatedResumeContractTest 6/6、GatewayEventsWindowExpiryTest 1/1
```

`node deploy/Verify-ApiContract.mjs`（ontorepo）→ `passed=true`。

## 4 边界（未覆盖，仍是各卡 `进行中` 的原因）

- EX-26：生产 `RunService` 的 IAM 装配只在 `mode=iam` 生效，本修复只证明**票能换回**；真实恢复链需六服务同栈。
- EX-46：`DecisionStore` 仍是进程内账本，55s+ 连续出流与重启失效未验。
- EX-06：J5–J9 未交付（三应用评测/发布、恢复、隔离），All 的 Ontology 段仍用 `-Resume` + 手工 journal；S1 只做 DryRun。
- EX-12：真实 `/onto/spaces/{spaceCode}/observations/summary` 的端到端响应未联调，只做了契约-检查器一致性。
