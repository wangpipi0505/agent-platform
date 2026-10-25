# G4 应用生命周期增量独立评审

评审日期：2026-10-20。结论：**发现 1 项 P2，修复并发复制来源快照后再关闭本增量**。未发现本次新增入口的跨租户读取、SQL 注入、继承发布许可或版本写回问题；这不是全工程验收结论。

## 基线与范围

仅阅读本独立工程。依据 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`、冻结快照 `contracts/snapshot/detailed-design-v1.2.md` 第七部分 F1/F2/F3（2556–2572）、§3.5（2708–2714）、状态机（2857–2868），以及 `docs/evidence/application-lifecycle-completion.md`。

使用 code-review、requesting-code-review 与 verification-before-completion 技能；本评审即实现者请求的独立审查。没有 HEAD，不把当前源码当作 Git diff；按主任务指定的增量边界审查：复制、六维筛选、生命周期日志、版本 diff，以及 CREATE / metadata MODIFY 留痕。既有保存、发布、停用、回滚等只作为相邻事务与不变量上下文。

现场计算 SHA-256，与 `.local/reviews/g4-application.json` 四项完全一致：

| 文件 | SHA-256 |
| --- | --- |
| `app-management/src/main/java/cn/agentplatform/app/api/AppController.java` | `9d7c884027c12b904c7b585cbe0334004312641835d7638fc9f875a10df6f361` |
| `app-management/src/main/java/cn/agentplatform/app/application/AppService.java` | `db56e76a3c53cb923446df75bfda87d7d86310d3c8576eda864c8891d49c292e` |
| `app-management/src/test/java/cn/agentplatform/app/application/ApplicationLifecycleTest.java` | `f363f78e416ed259d647aeb0b63fae1f330629d5cead964abbb1593c01f1e59a` |
| `docs/evidence/application-lifecycle-completion.md` | `e330037ec9b7999d04fa31d29fbe95ecf35db664773c7e7c71a1da5825cd9d69` |

## P2：获得草稿锁后须重新读取完整复制来源

位置：`AppService.java:197`（`copy` 使用 `get(sourceCode, true)` 的返回值）；关联 `AppService.java:124–134`（join 只 `FOR UPDATE OF d`）、201–219（复制元数据并记录 sourceDraftRev）、306–337（元数据编辑同时更新主表与草稿修订号）。

`TransactionTemplate` 未设置更强隔离级别，工程也未发现覆盖数据库默认隔离级别的设置。PostgreSQL 默认 READ COMMITTED 下，锁定语句的普通读取使用语句开始时的快照；等待其他事务提交后，被锁定的 d 行可返回新版本，但未锁定的 a 行仍来自旧语句快照。这个 join 不能把“已获得 d 锁”等同于“返回的主表字段和草稿字段属于同一修订”。[PostgreSQL 16 官方文档 §13.2.1](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-READ-COMMITTED) 明确说明等待更新后使用被锁行的新版本，以及同一命令可看见被更新行的新值而看不见其他行的并发更新。

可达交错（同租户、同源应用）：

1. 初始 `a.domain=A`、`d.draft_rev=1`。T1 执行 `metadata`，先锁 d，再把 domain 改为 B、draft_rev 改为 2，尚未提交。
2. T2 开始 `copy` 的 join 查询。其语句快照读到旧 a.domain=A；获取 d 锁时等待 T1。
3. T1 提交。T2 获得 d 锁，返回更新后的 draft_rev=2，却仍持有旧 a.domain=A。
4. T2 复制 domain=A，COPY detail 却记录 `sourceDraftRev=2`。源修订 1 的 domain=A，修订 2 的 domain=B；复制内容与声称的来源修订不一致。类似的等待发布/生命周期事务窗口也会使 COPY 的 sourceCurrentVersionNo / fromState 落后于获得锁时的来源状态。

影响：复制成功且没有越权，但来源审计不能精确解释该副本取自哪个修订，违背完成报告“草稿行锁保障与编辑/发布竞争同一边界”的承诺。本项不声称复制会修改源应用或继承发布许可。

建议：在复制事务内先取得既有 d 锁，再用下一条查询重新读取完整来源，并在创建目标、复制配置、写 CREATE/COPY 日志完成前保持该锁。这样可保留既有编辑/发布的共同锁边界，避免仅为复制扩大共享锁实现的改动面。不要把旧 join 返回值继续当作复制内容。加定向并发测试：用隔离 tenant 和明确锁栅栏，让复制实际等待 metadata；断言复制的 domain 与 COPY sourceDraftRev 对应同一源修订，并验证源未被修改、目标仍为全新 DRAFT。可再覆盖等待发布后的来源版本/状态留痕。

证据性质：这是根据当前 SQL、写入次序与 PG 官方语义确认的静态并发缺陷；本评审没有执行并发 SQL 复现，没有向业务表或数据库测试对象写入数据。应由实现者进行定向红绿验证后复审关闭。

## 已核对的增量边界

| 边界 | 核对结果 |
| --- | --- |
| 身份与权限 | 新复制入口先 `edit()`，沿用 EDITOR/ADMIN；读入口沿用公共 JWT 过滤链。tenant / actor 从 `Actor.current()` 获取，body 的租户、状态、版本、配置覆盖不被 copy 使用。测试手动建立 JWT SecurityContext，因此其本身不是验签集成测试；验签结论来自公共安全配置源码。 |
| 复制数据隔离 | 新 application / draft 使用数据库默认 DRAFT、空发布指针、rev/no=1；只写目标草稿 config/definition/layout；未复制版本、候选模型冻结、预检/发布凭据、集成密钥或外服务运行数据。业务模型与技能引用保留。新建、草稿与 CREATE/COPY 处于嵌套 REQUIRED 的同一事务。code/name 有字符串、长度及编码约束；冲突映射 409。 |
| 六维筛选 | q / code / domain / bizCategory / tag / status 在服务端 AND 组合；q 是大小写无关的字面子串，固定列名白名单、值均绑定参数。默认隐藏归档；显式合法状态可检索；未知状态拒绝。排序含 code 稳定次序。 |
| 生命周期日志 | CREATE 与 metadata MODIFY 在原事务内，actor 来自 JWT；COPY 含源编码、修订、当前版本（允许 null）。新读取先验证应用存在，再按 tenant/code 查询；只读且 id 降序。其并发来源一致性受上述 P2 限制。 |
| 版本差异 | 两个查询均限定当前 tenant / appCode / versionNo，正整数校验；只比较不可变版本的业务字段，没有版本写入，也不读取未发布草稿作为对照。对象路径确定性排序，JSON Pointer 转义正确，null 与不存在可区分；数组整体比较。既有 immutable_app_version 触发器仍存在。 |
| 既有发布与 CAS | 没有在本增量中另开发布路径。原 draftRev 校验、草稿锁、候选摘要复核、预检绑定、幂等提交、同候选复用与不可变版本边界仍可见；原并发发布用例覆盖的是发布，不证明新增复制的等待锁窗口正确。 |

## 已有验证证据与验收限制

只阅读日志与断言，没有重跑已成功套件、构建或服务操作：

- `application-lifecycle-red.log:243`：31 tests / 8 failures / 0 errors，缺路由、筛选未生效和 diff 路由冲突；原 22 项通过。
- `application-lifecycle-audit-red.log:149–151`：创建/元数据留痕期望 `[MODIFY,PUBLISH,CREATE]`，实际 `[PUBLISH]`；33 tests / 1 failure / 0 errors。
- `application-lifecycle-green.log:86–89` 标明 PostgreSQL 16.15、app schema 103；124、130、132、134、138 行分别记录新增生命周期 11、原发布 13、规则 7+2，共 33 项零 failures/errors/skips。147、150 行记录 BUILD SUCCESS、2026-10-20 04:44:37 +08:00。admin-common 同轮 5 项通过。

已读新增 11 项全部断言与相邻发布测试；其覆盖复制重置与隔离、各源状态、冲突/非法类型、伪造输入、读者拒写、跨租户、筛选、留痕、版本差异与不可变性。没有复制等待编辑的并发断言，故全绿日志不能关闭上述 P2。

分页和前端消费属于后续交付范围，本次不把未做 UI / 分页列为安全缺陷，也不宣称 F1/F2/F3 全量端到端完成。仅生成本报告；未改业务源码、未 Git 提交、未访问相邻工程或启动/停止服务。

## 定向复审关闭（2026-10-20，04:51:04 全量证据之后）

**当前结论：原唯一 P2 已关闭，本次应用生命周期后端增量局部评审通过。** 上文保留首次评审的问题与证据历史；不再表示当前仍有该阻断项。

本轮只复审 `docs/evidence/application-copy-fix.md`、`AppService.copy` 的锁后重读修复和 `AppCopyConcurrencyTest`，未扩大需求范围，未重跑套件或操作服务。

- `AppService.java:197–200` 先执行 `get(sourceCode, true)` 取得既有 d 锁并丢弃旧 join 结果，然后在同一事务中执行第二条 `get(sourceCode)`，使用新的 READ COMMITTED 语句快照读取完整来源。源锁保持到目标创建与 COPY 日志完成；既有修改、保存、发布、生命周期均竞争同一 d 锁，锁顺序和共享查询未改。这直接消除了本 P2 的旧 a / 新 d 混合读取窗口。
- 新用例连接固定独立库 `agent_platform_app_copy_test`，外层真实事务调用 metadata 和 save，使元数据与 config 一起更新到 rev=3；观察连接要求 `pg_stat_activity.wait_event_type='Lock'`、`pg_blocking_pids` 包含真实阻塞 PID、等待 SQL 含 `for update of d` 后才释放事务。它证明复制确实进入目标竞争窗口，而非仅依靠线程启动或等待时间推断。
- 读到的红绿摘要：04:49:55 的旧实现定向 1 项失败，domain/tags/分类/描述四项仍旧，config 与来源 rev=3 已新；04:50:25 修复后定向 1/1 通过。红轮与独立绿轮依据实现者报告中的执行摘要，没有保留单独日志文件，本评审不冒称重新执行。
- 额外核对当前 `target/surefire-reports`：并发用例 XML 记录 tests=1、failures/errors/skipped=0，数据库 PostgreSQL 16.15 / `agent_platform_app_copy_test`，并保留 `COPY_LOCK_BARRIER blocker=12224 waiter=36552 event=transactionid`。其余 XML 分别为生命周期 11、发布事务 13、规则 7+2、common 4+1，均无失败/错误/跳过，与实现者报告 04:51:04 的 app34 + common5 全绿一致。报告也如实保留补临时目录参数前的环境错误，未把该轮计为成功。

现场重新计算并确认修复证据所列 SHA-256：

| 文件 | 当前 SHA-256 |
| --- | --- |
| `AppService.java` | `06c40d8ab3d3d04d844a0ae5eb00834c99d0ae5597eb1293011a3f649eba0e2d` |
| `AppCopyConcurrencyTest.java` | `4f3c1602fd10b61037112b2e3260d0afb9b79853f86aaac35bd42c08ded39361` |

闭环范围：复制取得来源锁后必须读取同一已提交来源修订。等待发布后的版本/状态未新增专门交错测试，但使用相同锁后完整重读；结合已核对锁边界及既有发布回归，未发现该局部修复引入新阻断项。前端接入六维筛选、复制、生命周期日志和版本对照仍属消费者交付工作；分页及全工程端到端验收未由本结论覆盖。
