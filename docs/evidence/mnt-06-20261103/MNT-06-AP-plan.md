# MNT-06 AP 子步：抽取计划与事务边界（实施前证据）

日期：2026-11-03　席位：A（AP 后端）　状态：计划完成；**后续已由 `MNT-06-AP.md` 取代**——真实 PG 窗口（`127.0.0.1:35432/agent_platform_test`）开放后，生命周期与 pin 持久化两子步已实施，本文只作实施前的边界测量记录保留。

## 1. 卡面约束（不重复原文，只列会约束实现的点）

- 顺序：**发布子步先合入**（INT-03 会触达同一批方法）；生命周期与发布分开；持久化协作对象仅在有稳定 SQL 职责与明确事务所有权时抽。
- 白名单：`application/AppService.java`、`DraftMetadataService.java`、`PublishedMetadataService.java`、`domain/AppDigest.java`、`PublishGate.java`、`infra/ReleasePins.java`，拟新增 `application/AppLifecycleService.java`、`AppPublicationService.java`、必要的 `infra/AppPublicationStore.java`。
- 不得改变：发布请求/幂等号/候选摘要/评测证据/环境确认/版本只读/pin 失败独立记录；本地事务与"提交后远端 pin"边界；不得因移动方法触发自调用事务失效、双重提交或远端失败回滚已提交发布。
- 验收要求：**必要事务子步使用真实 PG 定向检查，不用 Mock 证明事务成立**。

## 2. 现状测量（`app-management/.../application/AppService.java`，2014 行）

- **无 `@Transactional`**：全部为编程式事务，构造注入 `PlatformTransactionManager` 后建两个 `TransactionTemplate`：
  - `tx`（:131，默认传播）
  - `publishedReadTx`（:132-134，READ_COMMITTED、timeout 3s）
- 事务边界共 8 处：copy(:291)、create(:344)、save(:372)、metadata(:427)、lifecycle(:502)、delete(:552，`executeWithoutResult`)、publishedAuthorized(:722，`publishedReadTx`)、commit(:1661)。
- 发布路径依赖密度：`precheck`(:1048-1260) 触及 16 个成员（get×24、check×5、tenant×2、remotePost×2、referenceChecks×2、candidate×2、validPublicationEvidence×2，其余各 1）；`commit`(:1623-1855) 触及 get×29、tenant×14、candidate×3 等。
- 已完成的前置减耦（MNT-08，提交 `fd73758d`）：`text/object/objects` 已下沉到 `domain/CapabilityValues`，`CapabilityBinding` 不再 import `AppService`；`candidate()`(:196) 与 `sha()`(:212) 已是 `public static`，可被新服务直接复用而**不产生循环 bean 依赖**。

## 3. 三步归属与搬运清单

| 子步 | 新文件 | 搬出成员（行号） | 必须共享的 helper |
| --- | --- | --- | --- |
| S1 发布 | `application/AppPublicationService.java` | precheck(:1048)、commit(:1623)、validPublicationEvidence(:1253)、requirePublicationEnvironment(:1275)、publishGate(:1313)、createWaiver/revokeWaiver/activeWaiver/waivers(:1321-1424)、check/parameterChecks/typeChecks/skillRefs/referenceChecks(:1430-1623)、recordPin/pinStatus/pin(:1855-1946)、publication(:1966)、releasedCandidate(:1947)、validateSchema(:1490 区) | tenant(:147)、row(:175)、get(:216/:220)、log(:462)、publishedMetadata(:762)、assertCapabilitySource(:1004)、waiverActive(:1400)、candidate(:196)、sha(:212) |
| S2 生命周期 | `application/AppLifecycleService.java` | create(:332)、copy(:286)、save(:371)、metadata(:426)、lifecycle(:501)、delete(:551)、log(:462)、lifecycleLogs(:476)、references(:573)、versions(:581)、version(:602)、versionDiff(:623) | 同上一组（读取类 helper 已抽入 S3 协作对象） |
| S3 持久化协作 | `infra/AppPublicationStore.java` | tenant(:147)、row(:175)、get(:216/:220)、log(:462)、publishedMetadata(:762)、assertCapabilitySource(:1004)、waiverActive(:1400) 等纯 SQL 读取 | —（只依赖 `JdbcTemplate`） |

注：S3 必须在 S1/S2 之前落地，否则两个新服务会各复制一份 `row()/get()`，属于卡面禁止的"把复杂度挪个地方"。

## 4. 事务前后图

- 现状：`AppService.tx.execute(() -> { ... db 调用 ... })`，连接由 Spring 线程绑定，`JdbcTemplate` 在事务内复用同一连接。
- 迁移后：新服务各自持有 `new TransactionTemplate(同一 PlatformTransactionManager)`；**线程绑定语义不变**，事务边界仍由唯一入口方法持有。
- 必须遵守的 3 条：
  1. 事务 lambda 内部只允许"已加入当前事务"的 db 调用，**不得再开第二个 `tx.execute`**（会造成嵌套语义与锁等待变化）。
  2. `commit` 的事务边界保持"本地写入提交"为止，`recordPin`/远端 pin 仍在事务外（现状 :1855 与 :1937 的调用顺序不得改变）。
  3. 兼容门面 `AppService.create/copy/save/...` 保留同名同签名同返回，内部委托新服务；不得出现"AppService 调用自身代理方法"的路径。

## 5. 验证计划

- 无 DB（当前环境可用）：`mvn -q -pl app-management -am -Dtest='cn.agentplatform.app.domain.*Test' test` 全绿；新增结构性断言（`CapabilityBinding`/领域不得 import AppService）；编译期检查新服务不持有 `AppService` 引用。
- 有 DB（当前环境阻塞）：`ApplicationLifecycleTest`、`AppCopyConcurrencyTest`、`PublishGateTest`、`PublishTransactionTest`、`PublishPinTest`、`PublishedMetadataHttpTest` 定向跑，证据中记录真实记录 ID 与提交/回滚回执。

## 6. 当前阻塞（已实测）

- docker daemon 未运行；本机无 PostgreSQL 可执行文件；`127.0.0.1:25432`（`docs/implementation-brief.md:7` 指定的开发 PG）与 `127.0.0.1:5432` 均 Connection refused。
- 结论：S1/S2 的"事务成立"与正负结果一致性无法在本机产出证据，故**未开始改生产代码**，避免以 Mock 或纯编译冒充验收。
