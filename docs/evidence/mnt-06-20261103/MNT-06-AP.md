# MNT-06 AP 子步：生命周期与发布 pin 持久化拆分（功能完成待统一验收）

日期：2026-11-03　席位：A（AP 后端）　分支：`upgrade/20261103`　基线：`370fe994`（父提交）

## 1. 本次范围（卡面三子步中的两个）

MNT-06 要求"按生命周期、发布、持久化三个可独立检查子步提交"。本次交付前两个：

| 子步 | 内容 | 结果 |
| --- | --- | --- |
| 生命周期 | 新建/复制/保存/改元数据/停启用归档回滚/删除与生命周期日志搬入 `AppLifecycleService` | 本提交 |
| 持久化 | `app.publish_pin` 读写下沉到 `infra/AppPublicationStore` | 本提交 |
| 发布预检/提交 | `precheck`/`commit` 内部再拆协作对象 | **未做**，见 §6 |

## 2. 原责任 → 新责任

| 原位置（`AppService`） | 新位置 | 门面 |
| --- | --- | --- |
| `create(Map)` | `AppLifecycleService.create` | `AppService.create` 一行委托 |
| `copy(String,Map)` | `.copy` | 一行委托 |
| `save(String,Map,boolean)` | `.save` | 一行委托 |
| `metadata(String,Map)` | `.metadata` | 一行委托 |
| `lifecycle(String,String,Map)` | `.lifecycle` | 一行委托 |
| `delete(String)` | `.delete` | 一行委托 |
| `lifecycleLogs(String)` | `.lifecycleLogs` | 一行委托 |
| `log(...)` | `.log`（改为 public，发布路径复用同一写入点） | `AppService.log` 一行委托 |
| `PIN_UPSERT` 常量 + `recordPin` 的 SQL + `pinStatus` 的查询 | `infra/AppPublicationStore` | `AppService` 保留开关与 fail-open 语义 |

`AppService` 行数 2014 → 1769（−302/+64，新增 `AppLifecycleService` 372 行、`AppPublicationStore` 77 行）。

生命周期读取仍归 `AppService`（`get(code,lock)`、`get(code)`、`version(code,int)`、`templateConfig`、`assertCapabilitySource`），通过 `AppLifecycleService.Port` 窄接口反向注入——生命周期不依赖发布实现，没有循环 bean 依赖。

## 3. 不变量（逐条保持，未随搬移改变）

1. 错误码与消息逐字不变：`INVALID_INPUT`/`APP_NOT_FOUND`/`APP_CODE_CONFLICT`/`DRAFT_CONFLICT`/`APP_STATE`/`APP_TYPE`/`CAPABILITY_SOURCE_CONFLICT`/`VERSION_NOT_FOUND`/`INVALID_ACTION`。
2. 事务边界不变：每个写方法各自 `TransactionTemplate` 执行；`copy` 内部调用 `create` 仍因 REQUIRED 传播加入外层事务，复制整份成功或整份回滚。
3. 写库顺序与 SQL 不变（含 `draft_rev`/`draft_no` 自增条件、`status` 的 CASE 更新、`publish_report`→`app_draft`→`application` 的删除顺序）。
4. 草稿摘要与候选摘要算法不变：`candidate`/`sha` 仍在 `AppService`，生命周期只通过 Port 取视图。
5. `delete` 仍只允许删除从未发布的草稿。
6. pin 落库失败仍是 fail-open：`AppPublicationStore.recordPin` 抛错由 `AppService` 的 try/catch 记 warn，不影响已提交发布。
7. 依赖方向：`domain` 层无新增对 `application` 的 import；生命周期服务不 import `AppService`（只用 `Port`）。

## 4. 两轮定向功能检查（真实 PostgreSQL 16.15）

坐标：`AGENT_DB_URL=jdbc:postgresql://127.0.0.1:35432/agent_platform_test`、`AGENT_DB_USER=dev_ap_test`（口令取自私有准备根，未写入仓库、未回显）。

命令：

```
mvn -o -pl app-management -Dtest='ConnectionProfileRepositoryTest,PublishTransactionTest,PublishPinTest,ApplicationLifecycleTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

| 轮次 | 结果 |
| --- | --- |
| 第一轮（拆分前，作为基线复测） | 38 跑 35 绿 3 error；3 个 error 与基线 worktree `fd73758d` 逐字一致 |
| 第二轮（拆分后） | **38 跑 35 绿 3 error**，同样是那 3 个既有环境失败，无新增红 |

逐类结果（第二轮）：`ApplicationLifecycleTest` 11/11、`PublishPinTest` 8/8、`ConnectionProfileRepositoryTest` 6/6 全绿；`PublishTransactionTest` 13 跑 10 绿。

3 个既有失败（与本次改动无关，已在基线 worktree `fd73758d` 复现并逐字比对）：

- `PublishTransactionTest.candidateAndPublishedVersionUseIdenticalFrozenModels:176` — `Platform 校验失败: /internal/model-bindings/freeze`
- `PublishTransactionTest.globalCredentialLookupRequiresDedicatedGatewayIdentity:350` — `Platform 缺少当前授权上下文`
- `PublishTransactionTest.integrationSecretOnlyShownOnceAndRevocationPersists:318` — 同上

## 5. 过程中发现并修复的自污染（如实登记）

第一轮基线复测时，三个发布测试全部在 `@BeforeAll` 的 Flyway 迁移上失败：

```
FlywayMigrateException: Script V111__connection_profiles_and_env_bindings.sql failed
SQL State: 42P07 / ERROR: relation "connection_profile" already exists
```

根因是 **INT-02 子步1 的仓储用例**（`ConnectionProfileRepositoryTest`）当时用手写 DDL 建 V111 的五张表，Flyway 历史里没有 V111 记录；后续任何跑 Flyway 的用例都会判定 V111 未应用 → 再执行 → 表已存在。

修复（只改测试，不动产品代码，随本提交附上）：仓储用例改为走**正式 Flyway 迁移**建表，并在捕获 `FlywayException` 时先清掉本卡自己的五张表再重迁（自愈历史残留），每 run 用独立租户前缀隔离数据。修复后该用例 6/6 绿，且顺带证明 V111 能在既有 V110 库上升级。

## 6. 未覆盖 / 未做

- `precheck`/`commit` 的内部再拆（第三子步"发布"）未做：两者触及 16 与 40+ 成员、上千行，按卡面警告"不得把复杂度塞进另一个大方法"，需要单独一轮设计与验证。
- `DraftMetadataService`/`PublishedMetadataService`/`PublishGate`/`ReleasePins` 未触达（本次不需要）。
- 未跑全量回归、未启动应用：`PublishGateTest`、`PublishPinTest` 之外的启动期装配（Spring 上下文中 `AppLifecycleService` 由 `AppService` 构造，无独立 bean）由 P 的全量窗口确认。
- 前端与生成客户端未同步（本卡不涉及接口形状变化）。

## 7. 回退

单提交可整份回退：`git revert <本提交>`。回退后 `AppLifecycleService`/`AppPublicationStore` 随提交删除，`AppService` 恢复 2014 行版本；无数据库迁移、无配置变更、无接口形状变更，因此不需要数据回滚。
