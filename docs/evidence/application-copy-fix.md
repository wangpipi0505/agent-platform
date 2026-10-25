# 应用复制来源快照 P2 修复证据

日期：2026-10-20，时间为 +08:00。依据 `review-application-lifecycle.md` 的唯一 P2，完成实现者定向修复与真实 PostgreSQL 红绿测试；待原独立评审者复审关闭。本报告不是全工程验收。

## 最小修复与锁顺序

`AppService.copy` 原先把 `get(sourceCode, true)` 的 join 结果直接作为复制来源。现在仍先使用原查询取得 `d` 草稿行锁，丢弃该查询结果，再在同一事务中用下一条 `get(sourceCode)` 读取完整来源。READ COMMITTED 的新语句快照能看到等待期间已提交的主表及草稿；既有编辑、保存、发布和生命周期操作均先竞争同一 `d` 锁，该锁持有到目标创建和 COPY 留痕完成。

未扩大为同时锁 `a,d`，未改变既有先锁草稿再写主表的顺序；没有变更共享 `get`、metadata、save、发布或状态机语义。生产改动仅为 copy 中一条额外读取及其不变量注释。tenant 条件、HTTP 权限校验、输入校验、新目标 DRAFT/rev=1/no=1/无发布指针、引用保留与发布证据隔离沿用原逻辑。

## 确定性并发测试

新增 `AppCopyConcurrencyTest.copyWaitingForMetadataUsesOneCommittedSourceRevision`。仅连接本工程独立 PG `127.0.0.1:25432`，数据库固定为新建专用 `agent_platform_app_copy_test`；使用原 app Flyway 迁移至 v103，随机 tenant 隔离。该用例不连接运行库，不使用内存库，不启动服务。测试只复用 `PublishTransactionTest.actor` 建立线程安全上下文，不调用其数据库初始化。

1. 初始源 domain/tags 为 before、rev=1。
2. 主线程 T1 开启真实外层 Spring 事务，断言 `transaction_isolation=read committed`；调用真实 metadata 修改 domain/tags/分类/描述，再调用真实 save 修改 config，源 rev=3，事务保持未提交。
3. 工作线程 T2 调用真实 copy，竞争 T1 持有的草稿锁。
4. 第三个独立 JDBC 连接查询 `pg_stat_activity`，同时要求 `wait_event_type='Lock'`、T1 PID 出现在 `pg_blocking_pids(pid)` 中、等待 SQL 包含 `for update of d`。只有实际观察到该锁等待才允许 T1 提交。10 秒截止仅用于失败保护，无 sleep、随机延迟或“线程已启动即视为已等待”的假设。
5. T2 完成后，断言 domain/tags/分类/描述/config 都来自新源、COPY sourceDraftRev=3、sourceAppCode 正确、源完整状态未改变；目标 DRAFT、rev/no=1、无发布指针与版本。原生命周期测试继续覆盖权限、tenant、已发布源及其他隔离约束。

## 红绿执行记录

测试环境：Java 21.0.12、PostgreSQL 16.15、Google Java Format 1.25.2 `--aosp`。先从本工程 `.env` 加载进程环境变量，设置本机既有 JAVA_HOME/PATH，未输出凭据。没有调用会启动数据库的 `scripts/test.ps1`，而是直接运行 Maven。

定向命令（红、绿相同）：

```powershell
& mvn.cmd -B -ntp test `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  -pl app-management -am '-Dtest=AppCopyConcurrencyTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'
```

**红：04:49:55，退出码 1。** 测试已添加、生产 copy 尚未修改。1 test，1 failure，0 errors/skips；四个断言失败，其余 config 与 sourceDraftRev=3、源未变、目标重置断言均通过，直接证明旧主表字段与新草稿混合。

```text
COPY_LOCK_BARRIER blocker=57284 waiter=49696 event=transactionid
domain ==> expected: <after> but was: <before>
tags ==> expected: <[after]> but was: <[before]>
expected: <after-category> but was: <>
expected: <updated together> but was: <>
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
BUILD FAILURE
```

**绿：04:50:25，退出码 0。** 只修改 copy 获锁后重读；同一定向测试通过。

```text
COPY_LOCK_BARRIER blocker=24084 waiter=4016 event=transactionid
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**最终全量：04:51:04，退出码 0。** 命令沿用本仓库 `scripts/test.ps1` 中的 Windows Unix-domain 临时目录参数：

```powershell
& mvn.cmd -B -ntp test `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' -pl app-management -am
```

```text
COPY_LOCK_BARRIER blocker=12224 waiter=36552 event=transactionid
admin-common: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
app-management: Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Finished at: 2026-10-20T04:51:04+08:00
```

构成：新增并发 1，生命周期 11，原发布事务 13，领域规则 7+2。既有两个集成测试类仍按原约束使用本工程 `agent_platform_v12`、随机 tenant/code；没有修改其连接、清库或迁移文件。新增并发用例独占专用测试库。

如实记录：04:50:47 的首次全量命令漏带仓库已有 `jdk.net.unixdomain.tmpdir` 参数，导致原发布测试创建本地 HttpServer 时 `Unable to establish loopback connection` / `UnixDomainSockets ... Invalid argument: connect`，34 tests、0 failures、1 error。补回原脚本同一参数后全量通过；未为该问题修改源码或服务配置。

最终格式验证：对两个改动 Java 文件运行 `google-java-format-1.25.2-all-deps.jar --aosp --dry-run --set-exit-if-changed`，退出码 0、无输出。

## 交付文件与复审定位

- `app-management/src/main/java/cn/agentplatform/app/application/AppService.java`：只修改 copy 取得来源的局部代码。SHA-256 `06c40d8ab3d3d04d844a0ae5eb00834c99d0ae5597eb1293011a3f649eba0e2d`。
- `app-management/src/test/java/cn/agentplatform/app/application/AppCopyConcurrencyTest.java`：真实数据库锁栅栏回归用例。SHA-256 `4f3c1602fd10b61037112b2e3260d0afb9b79853f86aaac35bd42c08ded39361`。
- `docs/evidence/application-copy-fix.md`：本报告，内嵌本次工具执行输出摘要，无额外证据文件。

未读取相邻旧工程，未修改 web/engine、其他协作者文件、表结构或依赖，未 Git 提交、启动/停止服务或外部发布。等待发布后的来源版本/状态没有新增专门交错用例；其读取使用同一次锁后完整重读，原生命周期及发布回归全绿。本次关闭范围应限定为评审指出的复制快照一致性 P2。
