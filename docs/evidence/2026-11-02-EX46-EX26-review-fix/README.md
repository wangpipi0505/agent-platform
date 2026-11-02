# EX-46 / EX-26 复审缺陷修复证据（2026-11-02）

范围：只改 `authority-service`（`DecisionStore.java`、`DecisionStoreTest.java`、两个新增测试类），
未改 `admin-common` / engine / gateway。除任务明确要求反转的 EX-46 既有用例外，未改其他断言语义；
未做任何 git commit / push。

命令（真实执行，JDK 21.0.12）：

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -o -pl authority-service test
```

结论：**模块全量绿，108 例**（基线 103 例 + 新增 5 例；`DecisionStoreTest` 仍为 5 例，只改了 1 个用例的断言）。

## 0. 基线

修复前同一命令：`Tests run: 103, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`
（见 `baseline.log`）。基线绿不代表两个缺陷被覆盖——它们都没有失败用例，属于语义缺陷。

## 1. EX-46：已消费窗口的首次续期必然 403

### 复现路径（修复前代码，HEAD `434ac2b8`）

1. S3 开窗登记 `EVENTS` 决策 D3（`DelegatedAuthority.java:627-638`，`purpose=EVENTS`，
   `expiresAt=hardDeadline`——登记的是整段读窗口，不是首个断言窗）。
2. S4 child 换票用单键 CAS 消费父窗口 D3：
   `DelegatedAuthority.java:781` `if (decisions.consume(parent.id(), clientId).isEmpty()) { ... EVENTS_CHILD_REPLAY }`
   ——消费标记落在父 EVENTS 决策上。
   （任务描述里说“S5 正常会 consume 父窗口”，实测不是：S5 `eventsChildOpen` 在
   `DelegatedAuthority.java:983` 消费的是 CHILD 决策 D5；消费父窗口的是 S4 `eventsChildExchange`，
   即 `:781`。两条链路都会走到本缺陷，但触发点是 S4。）
3. S6 gateway 首次续期：`DelegatedAuthority.java:1089`
   `if (decisions.touchExpiry(window.id(), window.hardDeadline()).isEmpty()) throw ... denied("EVENTS_CHECK_CONTINUATION")`；
   旧 `DecisionStore.touchExpiry`（HEAD `:259-272`）在 `current.consumedAt() != null` 时返回 empty
   → 合法续期被拒成 `SYS-AUTH-403` / `EVENTS_CHECK_CONTINUATION`。

红跑证据：`GatewayEventsConsumedWindowRenewalTest.aConsumedParentWindowCanStillBeRenewedButNotReplayed:60`
`expected: 200 OK but was: 403 FORBIDDEN`，body `{"code":"SYS-AUTH-403","message":"当前授权不允许此操作"}`。

### 改法（`DecisionStore.java:319`）

`touchExpiry` 现在允许作用于**已消费**的登记，只做 `current.withExpiresAt(until)`；
`consumedAt` / `consumedBy` 原样保留，`consume(...)` 的语义完全不变（已消费仍返回 empty）。
注释明确写了“**续期 ≠ 复活**”：续期只改有效期，已消费的窗口仍不能再换票/再消费；
必须用 `entries.compute` 基于当前值改，不能用“旧快照 + put”回写（那会把并发落下的消费标记擦掉，
才是真正的复活）。保留区镜像也用字段级 `withExpiresAt`，同样不会丢消费标记。

### 既有测试修正说明（为什么必须改）

`DecisionStoreTest.touchExpiryNeverResurrectsAConsumedRegistration` 原来断言的正是错误语义：
“消费后 touchExpiry 必须 empty”。这条断言把 EX-46 的 403 固化成了预期行为，正是缺陷本体。
按任务要求改名为 `touchExpiryExtendsAConsumedRegistrationWithoutResurrectingIt`，断言反转为：

- 消费后 `touchExpiry` 必须 **present**，`expiresAt` 推后到硬截止；
- `consumedAt` 与消费时返回的值**逐字段相等**，`consumedBy=gateway` 保持；
- 随后同一登记 `consume` 仍 **empty**（续期没有把它变回可消费）。

这里没有“放宽断言换绿”：真正放宽的是旧断言对错误行为的锁定，新断言对“复活”的约束更强了
（同时钉住 expiresAt 推后、消费标记不变、不可再消费三件事）。

## 2. EX-26：短 decision TTL 丢掉仍有效 grant 的恢复来源

### 复现路径（修复前代码）

1. `authorize` 登记决策时把展示有效期夹到 grant 以内：
   `DelegatedAuthority.java:1558`
   `earlier(issued.plusSeconds(delegation.decisionSeconds()), grantExpiresAt)`；
   当 `decision-seconds < grant-seconds`（例如 5s vs 60s）时，`expiresAt` 只有 5s。
2. `DecisionStore.sweep()` 在每次写时按 `expiresAt` 清 `entries`；带 `grantRef` 的原委托决策
   （S2 authorize 登记）`expiresAt` 只有 5s，10s 后任意一次写入（续期/开窗/authorize 都会 `put`）
   就把它清掉——即使它已被消费，撤销标记也随 `entries` 一起消失。
3. `operation=resume` 的 `resumeGrant` 只扫 `entries`（HEAD `findByGrantRef`，`DecisionStore.java:169`）：
   找不到候选 → `DelegatedAuthority.java:300` `RESUME_GRANT_UNKNOWN`（403），而 grant 其实还没到期；
   用户看到的是“从未签发过”，不是 `AUTHORITY_EXPIRED`，无法按设计重建运行。

红跑证据：`DecisionStoreGrantRetentionTest.aShortDecisionTtlDoesNotLoseTheGrantResumeSource:106`
`[grant 仍有效时 findByGrantRef 必须仍返回原委托] Expected size: 1 but was: 0 in: []`；
同一红跑里已消费路径 `:139` 直接 `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0`
（`findByGrantRef(...).get(0)` 拿不到保留项）。

### 改法

1. 新增按 decision id 的 grant 保留区 `DecisionStore.java:78`
   `private final Map<String, Decision> grantEntries`：
   - 只在 `decision.grantRef() != null` 时登记（`put`，`:156`）；没有 grant 的决策不可 resume，覆盖写会撤掉残留。
   - 过期时间只看 **`grantExpiresAt`**，不是 `expiresAt`；`sweep()` 调
     `sweepGrantReservations(...)`（`:367`）清理过期项。
   - 容量与 `capacity` 同量级；超限时按 `grantExpiresAt` 淘汰最旧（`oldestGrant()`，`:384`），
     而不是按插入序（签发早但 grant 更晚的恢复来源不能被误删）。
   - `put` 合并同一 id 时用 `keepConsumption(...)`（`:181`）：`consumedAt` 是单向状态，
     回写票 jti 之类的覆盖写不能把已有消费标记擦掉。
2. `consume(...)`（`:278`）在 entries 的 CAS 成功后，对同一 id 的保留项再做一次
   `current.consumed(at, consumer)` 字段级同步：撤销标记必须跟到 resume 唯一还看得见的来源。
3. `touchExpiry(...)`（`:319`）在 entries 更新成功后，对保留项做 `withExpiresAt(until)` 镜像；
   不整体替换，避免把消费标记写丢。
4. `findByGrantRef(...)`（`:208`）合并 `entries` 与 `grantEntries`，按 decision id 去重
   （entries 优先，字段除 expiresAt 外同源），仍**不做任何过期过滤**：读路径保留过期项，
   resume 才能先报 `AUTHORITY_EXPIRED` 而不是 `UNKNOWN`。
5. 保留区清理只发生在写入路径的 `sweep()`，读路径不清理。因此
   `grantExpiresAt` 已经过去但还没发生写时，`resume` 仍能看到过期登记并返回
   `AUTHORITY_EXPIRED` / `RESUME_GRANT_EXPIRED`（`DelegatedAuthority.java:317`）；
   下一次 `put` 触发 sweep 之后 `findByGrantRef` 才为空（此后按既有逻辑是 `RESUME_GRANT_UNKNOWN`）。
   这个顺序在 `anExpiredGrantReportsAuthorityExpiredThenGetsSwept` 里显式钉住，避免把
   “sweep 前先查空”误当成 `AUTHORITY_EXPIRED`。

### 红→绿

- 红：临时把 `DecisionStore.java` 换回 HEAD 版本（其余文件不动，测试类不依赖任何新增 API），
  `Tests run: 10, Failures: 3, Errors: 1`，失败点见上（`red-before-fix.log`）。
- 绿：恢复修复版后同一组用例 `Tests run: 10, Failures: 0, Errors: 0`。
- 全量绿：`Tests run: 108, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（`green.log`）。

## 3. 用例表

| # | 用例 | 文件 | 断言 | 修复前 |
|---|------|------|------|--------|
| 1 | `touchExpiryExtendsAConsumedRegistrationWithoutResurrectingIt` | `src/test/java/cn/agentplatform/authority/DecisionStoreTest.java` | 消费后 touchExpiry present、expiresAt 推后、consumedAt/consumedBy 保持、consume 仍 empty | 红（touchExpiry empty） |
| 2 | `aConsumedParentWindowCanStillBeRenewedButNotReplayed` | `src/test/java/cn/agentplatform/common/authority/GatewayEventsConsumedWindowRenewalTest.java` | 开窗→S4 换票（消费父窗口）→S6 续期 200；第二次 S4 仍 403 `EVENTS_CHILD_REPLAY` | 红（续期 403） |
| 3 | `aShortDecisionTtlDoesNotLoseTheGrantResumeSource` | `src/test/java/cn/agentplatform/common/authority/DecisionStoreGrantRetentionTest.java` | T0+5 的 entries 被 sweep 后 findByGrantRef 仍返回；resume 成功换票且票面 sub/grant_ref/caller 正确 | 红（findByGrantRef 空） |
| 4 | `aConsumedGrantStaysRevokedAfterTheDisplayEntryIsSwept` | 同上 | sweep 后保留项仍带 consumedAt/consumedBy；resume 403 `RESUME_REVOKED` | 红（保留项不存在，用例取不到元素） |
| 5 | `anExpiredGrantReportsAuthorityExpiredThenGetsSwept` | 同上 | grant 过期后先 resume → 403 `AUTHORITY_EXPIRED`/`RESUME_GRANT_EXPIRED`；再 put 触发 sweep → findByGrantRef 空 | 绿（守卫用例，见下） |
| 6 | `theGrantReservationIsBoundedByCapacity` | 同上 | capacity=4 写入 6 条不同 grant：可查到的不超过 4 条，最旧两条为空、最新一条仍在 | 绿（守卫用例，见下） |

用例 5、6 在修复前的红跑里也是绿的，这是预期：

- 用例 5 的第一阶段在 sweep 之前，旧 `entries` 里还有那条过期登记，旧代码同样会进入
  `AUTHORITY_EXPIRED` 分支；它守的是“读路径不提前清理 + sweep 后清理”，不是缺陷本体的复现。
- 用例 6 在旧代码里 `entries` 自身也按容量淘汰，最旧两条查不到、最新一条存在，所以碰巧同样成立；
  它守的是保留区不能无界增长。保留区容量逻辑本身没有独立的红跑失败证据（只有代码审查 +
  新代码下“仍 ≤4”的断言），在“未覆盖”里如实列出。

## 4. 全量 `Tests run:` 原文

修复前基线（`baseline.log`）：

```
[INFO] Tests run: 103, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

修复后（`green.log`，`mvn -o -pl authority-service test`）：

```
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.016 s -- in cn.agentplatform.authority.DecisionStoreTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.242 s -- in cn.agentplatform.common.authority.DecisionStoreGrantRetentionTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in cn.agentplatform.common.authority.GatewayEventsConsumedWindowRenewalTest
...
[INFO] Tests run: 108, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（`...` 处为其余既有测试类，逐类输出见 `green.log`；汇总行只出现一次 `Tests run: 108`。）

红跑（`red-before-fix.log`，仅换回 HEAD 的 `DecisionStore.java`）：

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.authority.DecisionStoreTest
[ERROR] Tests run: 4, Failures: 1, Errors: 1, Skipped: 0 -- in cn.agentplatform.common.authority.DecisionStoreGrantRetentionTest
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 -- in cn.agentplatform.common.authority.GatewayEventsConsumedWindowRenewalTest
[ERROR] Tests run: 10, Failures: 3, Errors: 1, Skipped: 0
```

## 5. 改动文件

- `authority-service/src/main/java/cn/agentplatform/authority/decision/DecisionStore.java`
  （唯一生产代码改动；未改 `DelegatedAuthority.java`——两处调用点语义正好是期望语义）
- `authority-service/src/test/java/cn/agentplatform/authority/DecisionStoreTest.java`（1 个既有用例改名+断言反转）
- `authority-service/src/test/java/cn/agentplatform/common/authority/GatewayEventsConsumedWindowRenewalTest.java`（新增）
- `authority-service/src/test/java/cn/agentplatform/common/authority/DecisionStoreGrantRetentionTest.java`（新增）
- 本目录证据文件：`baseline.log`、`red-before-fix.log`、`green.log`、本 README
  （Maven 原始输出为系统 GBK 编码，已无损转 UTF-8；行内容未改）

## 6. 未覆盖 / 有意限制

- **跨两个 Map 的原子性**：`consume`/`touchExpiry` 各自先改 `entries` 再镜像 `grantEntries`，
  两次 `compute` 不是同一事务。并发交错时最坏只可能丢保留区那份的 `expiresAt` 镜像
  （resume 不读 expiresAt）；`consumedAt` 由“基于当前值 + keepConsumption”保证不会被旧快照擦掉。
  没有为此引入全局锁——这两个结构都只影响单进程内恢复来源，锁会放大所有 authorize 的写路径。
- **生产 60s TTL 未真等**：Red/Green 用例用可变 `Clock` 推进 store/authority 的时间；
  `KeyRing` 仍用系统时钟，所以换出的票只解码 claims，不做时间窗验签（签名本身由既有
  `DecisionStoreRestartTest` / 1e/1f 契约测试覆盖）。
- **多实例 / 重启**：保留区仍是进程内内存，重启即丢（EX-44 §6.1 的显式债务，EX-46 迁移 PG
  时由唯一约束接管）。本修复只保证“同一个进程内 grant 有效期内可 resume”，不改变该债务。
- **grant 过期后的 UNKNOWN 窗口**：sweep 只在写时发生。过期后、下一次写之前 resume 返回
  `AUTHORITY_EXPIRED`；一旦发生写，保留区被清，resume 回到 `RESUME_GRANT_UNKNOWN`。
  这与既有 `entries` 的“读不过滤、写时清理”语义一致，用例 5 按这个顺序断言。
- **forward 多跳候选顺序**：`findByGrantRef` 合并后 entries 优先、保留区追加，和旧实现的
  “entries 扫描顺序”一致；没有对同一 grant 的多条候选做显式排序或优先级证明。
- **容量上界的独立红证据**：见用例表说明，用例 6 没有在旧代码上失败（旧 entries 自身有界），
  只在新代码下证明保留区不会突破 `capacity`。
