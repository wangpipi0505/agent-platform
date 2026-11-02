# EX-46 C4：读窗口登记错误地跟首个断言窗到期

## 1. 问题（用户初步验收指出）

长时间 SSE 观测在第一个 5s 断言窗之后续期会被拒，表现为流在 5s 处断掉，而 `hard_deadline`（55s）还远未到。台账 §19.2 把它记为 EX-46 的“尚未完成：修复合法续期窗口被错误清理的问题”。

## 2. 根因

读窗口在开窗时登记一条 `DecisionStore.Decision`：

- S3 `portal_gateway_events_open` → `Purpose.EVENTS`
- S5 `portal_events_child_open` → `Purpose.CHILD_OPEN`

原实现把这条登记的 `expiresAt` 写成 `Instant.ofEpochSecond(checkedUntil)`，即**首次断言窗**（`assertion-seconds`，默认 5s、上限 5s）；而续期（S6/S6' `eventsWindowCheck`）只重签短断言、**不新建也不刷新登记**。

`DecisionStore.put(...)` 每次都会先 `sweep()`，凡是 `expiresAt <= now` 的决策一律 `remove`。于是：

1. 客户端第 6 秒发出第一次续期；
2. 期间只要有任何一次决策写入（同一进程内任意 `authorize`/`exchange` 都会 `put`），`sweep()` 就把仍在硬截止内的窗口登记当过期清掉；
3. 续期按 `continuation_jti` 反查落空 → `403 EVENTS_CHECK_CONTINUATION`。

即“登记的生命周期”与“断言的生命周期”被混成了一件事：断言该短，登记该活到硬截止。

## 3. 改动

| 文件 | 改动 |
| --- | --- |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DelegatedAuthority.java` | S3 `Purpose.EVENTS` 与 S5 `Purpose.CHILD_OPEN` 的 `expiresAt` 改为各自的 `hardDeadline`；`eventsWindowCheck` 在算出 `checkedUntil` 后 `decisions.put(window.withExpiresAt(window.hardDeadline()))`，让历史登记也统一收敛到硬截止 |
| `authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java` | 新增 `Decision.withExpiresAt(Instant)`：只推后有效期，判权结果与一次性消费标记（`consumedAt/consumedBy`）都不动 |
| `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsWindowExpiryTest.java` | 新增 1 例回归（`assertion-seconds=1`）：开窗 → 等过首个断言窗 → 用第二条窗口触发 `sweep()` → 断言登记仍在、续期 200、`checked_until` 前推且不超过 `hard_deadline`、登记 `expiresAt == hard_deadline` |

未改客户端：`checked_until`/`hard_deadline`/`continuation_ref` 的语义与字段都没变，只是登记不再提前消失。

## 4. 红 → 绿

红（把 S3 的 `expiresAt` 临时改回 `checkedUntil`，跑同一条用例）：

```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[首个断言窗已过，但仍在 hard_deadline 内的窗口登记必须还在]
Expecting Optional to contain a value but it was empty.
```

绿（修复后同一条用例 + 相邻 1c/1d/1e/1f 契约类）：

```
GatewayEventsWindowExpiryTest         Tests run: 1,  Failures: 0
GatewayEventsSlice1dContractTest      Tests run: 6,  Failures: 0
GatewayEventsSlice1eContractTest      Tests run: 8,  Failures: 0
GatewayEventsSlice1fContractTest      Tests run: 10, Failures: 0
```

模块全量：

```
total=90 F=0 E=0 S=0
```

（`mvn -o -pl authority-service test`，`-DargLine="-Xmx1024m"`，BUILD SUCCESS。）

## 5. 未覆盖 / 仍属验收

- **真实长观测（55s+ 连续出流）未在真六服务上跑**：本条只用“登记 + 续期”的服务端契约证明窗口不会被误清；`PortalRunEventsJoinedChainTest`、receipt 篡改必拒、以及 5s→55s 的连续续期仍要真起 gateway/engine/skill 才能验收。
- **`DecisionStore` 仍是进程内账本**：单实例重启会丢登记（既有债务，EX-46 卡内已记；PG 持久化方案属 §19 的设计门）。
- **容量淘汰未覆盖**：本条只测过期回收；`capacity` 淘汰在窗口密集时仍可能提前清掉合法窗口，属已知边界，未在本条扩张。
