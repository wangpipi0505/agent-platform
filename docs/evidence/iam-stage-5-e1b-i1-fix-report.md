# E1b I1 期限终检修复与复审交接

2026-10-20；唯一实施作者 `iam_e1b_i1_deadline_fix`。组件修复已取得实际具名红与受影响 current 类绿，并冻结交还 source/target；等待同一 E1b 独立 reviewer 复审，尚未登记 E1b 闭合。唯一工程及全部 shell workdir 为 `G:/207/项目/算法重组工具/代码/agent-platform`，没有读取、关联相邻旧工程代码、配置、数据、进程。

正式依据 `iam-stage-5-e1b-i1-fix-brief.md` SHA256 `72b1738cf225468fb6031cc294341be5c213abd6f22a2b4be50ca34aaf71957b`；已实际阅读 AGENTS、contracts/README、implementation-brief、原 review、有关生产文件与 fixture/current 全类。原 review SHA `bb1322991e9983d874108c4bc5da04502784dc538c800e7720a745457a7d19e9`、原报告 SHA `7070a68c00a6b0f52d14735ac0a78ae39d407ff729981d50f9d627e2efdca5db` 保持保护。应用 systematic-debugging/TDD/ponytail 与 verification-before-completion，仅作这项授权范围内修复。

## 实际修复与不变量

仅四个源文件不同，完整差异及逐文件前后 SHA/bytes 见 `iam-stage-5-e1b-i1-fix/changed-files.json`：

- `RunAuthorityStore.recordCheck`：保留原 fresh Retained proof、immutable Binding、三个 scope、旧阻断与 checkVersion CAS；实际 UPDATE 返回 1 后，在同一短事务内对同一私有可信 Clock proof 再 `requireVerified()`。锁等待期间到期会抛原 `AUTHORITY_EXPIRED`，此次成功写入、清阻断、版本增加均回滚。公共仓储成功入口也受这一终检约束。
- `RunAuthority.current`：`recordCheck` 的真实提交完成返回后、交付 VA 之前，再验同一 proof。提交期间才到期时拒绝本次交付。已提交历史成功观察保持真实；catch 内仍以旧 Binding CAS 记录失败，完整重读发现新 checkVersion 时返回 false，不能覆盖已提交新检查，也不会掩盖原到期异常。
- `RunAuthorityLocalCurrentPostgresTest`：新增以下两个具名实际 PostgreSQL 用例。
- `RunAuthorityFixture.CountingDataSource`：最小测试专有真实连接装饰。匹配确切 authority UPDATE，获取真实 PG backend pid；只有第一个参数为非空成功 decision 且 UPDATE 真返回 1，才在该连接标记待成功提交；`Connection.commit` 先实际委托 PG，然后仅对此成功提交运行测试 hook。load/create 及失败检查的早期 commit 都不会触发。

生产没有新增 hook、接口、依赖、事务框架或收据，没有联网移入锁内，没有延期、续期或重造 operation。没有改变 Grant/initial/scope 语义、构造注入、未装配状态、MaterialScopeStore、RunService、HTTP/调度、配置、迁移、common、POM、前端或契约。期限终检只保证本次交付边界；后续真实动作仍必须由 driver/scheduler 再验。

## 真实数据库验证

唯一库 `agent_platform_iam_engine_v12`，唯一端点 `127.0.0.1:25432` / 用户 `agent_v12`，实际 PostgreSQL 16.15、schema 216。密码只沿既有 `.tools/run-iam-module-tests.mjs` 控制子环境传递；未另解析环境文件、打印凭据或修改 runner。没有 fake PG、替代 repository、反射造 proof 或旧 Actor 出票。

`successExpiresWhileCasWaitsOnUnchangedRow` 使用一条真实 PG 连接持确切 run_authority 行锁，且不改 version/binding；另线程真实连接提交公共仓储成功检查。闩锁取得准确 backend pid 后，在有限 5 秒上界内每 20ms 查询 PG `wait_event_type='Lock'` 与确切 UPDATE SQL，确认已经真的阻塞；没有靠等实际授权 5 秒到期。确认等待期间 Binding 完全相同，再推进既有可信 fixture Clock 六秒，finally 释放/回滚锁，future 限六秒。断言原 `AUTHORITY_EXPIRED`，整个 Binding 与旧临时 503 完全保持、无成功检查提交，run 业务状态仍为 PENDING。

`currentDoesNotReturnProofExpiredDuringCommit` 先实际委托 PG commit；hook 用不参加 Spring 当前绑定事务的独立 DataSource 查询确认该成功 checkVersion 已对另一真实 PG 连接可见，然后才推进同 Clock 六秒。断言 current 抛原 `AUTHORITY_EXPIRED`、hook 恰好一次；已提交成功 checkVersion 为原值+1、decision 真实非空、没有伪称回滚。随后的旧版本失败 CAS 保留该成功记录，initial/scopes 不变。

实际命令如下，全部在唯一显式 workdir 执行；每次都保留原 stage，不复用或覆盖：

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-e1b-i1-fix-red01 engine-runtime RunAuthorityLocalCurrentPostgresTest#successExpiresWhileCasWaitsOnUnchangedRow+currentDoesNotReturnProofExpiredDuringCommit
node .tools/run-iam-module-tests.mjs iam-stage-5-e1b-i1-fix-red02 engine-runtime RunAuthorityLocalCurrentPostgresTest#successExpiresWhileCasWaitsOnUnchangedRow,RunAuthorityLocalCurrentPostgresTest#currentDoesNotReturnProofExpiredDuringCommit
node .tools/run-iam-module-tests.mjs iam-stage-5-e1b-i1-fix-red03 engine-runtime RunAuthorityLocalCurrentPostgresTest#successExpiresWhileCasWaitsOnUnchangedRow,RunAuthorityLocalCurrentPostgresTest#currentDoesNotReturnProofExpiredDuringCommit
node .tools/run-iam-module-tests.mjs iam-stage-5-e1b-i1-fix-green01 engine-runtime RunAuthorityLocalCurrentPostgresTest
```

| 轮次 | 实际结果 | 准确原因 |
|---|---|---|
| red01 | launcher exit 1，未执行 Maven，无 XML/raw | `+` 不符合既有 launcher 闭合字符文法；记录实际控制台摘录于 `launcher-red01.json`，不算测试红。未改 launcher，后改用逗号完整 class#method。 |
| red02 | 编译成功，2 tests / 2 failures / 0 errors / 0 skipped，BUILD FAILURE | 行锁用例实际行为红；commit 用例 hook 误用 `store.load`，Spring 仍绑定 RC 事务而触发测试侧 REPEATABLE_READ_REQUIRED，非正确 commit 行为红。原 raw/XML/source 全保留。 |
| red03 | 编译成功，2 tests / 2 failures / 0 errors / 0 skipped，BUILD FAILURE | 仅改测试 hook 为独立 DS 查已提交版本；两具名原实现均没有抛异常，分别出现 `Expecting actual not to be null` 与 `Expecting code to raise a throwable`。两生产文件仍为原实现。此轮才是两个边界的具名实际行为红。 |
| green01 | 12 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS | 最小生产补丁后，只运行受影响 current 全类一次；包括两个新边界、原 stale CAS、永久/临时阻断、无 proof、未知 DB 错误、错误 tuple/事务与真实 HTTP guard 负例。 |

每轮仅从 raw `[INFO] Running` 的实际类集合选取 XML，未使用 mtime 窗口、未归入任何旧 XML；每轮只有 `cn.agentplatform.engine.infra.RunAuthorityLocalCurrentPostgresTest` 一个实际类。`archive.mjs` 功能位于本包 `audit.mjs archive`；Maven返回后立即执行 `node docs/evidence/iam-stage-5-e1b-i1-fix/audit.mjs archive <stage>`，保存本轮 raw、唯一实际 XML、完整356源/POM快照、source manifest 与 execution-audit。所有三个实际运行均已保存，incidental 为0。

| 轮次 | raw SHA256 | XML SHA256 |
|---|---|---|
| red02 | `70043ffa1d73fbaa4b2037a2351a1abe20e7b65cc4f6631f7cb0108a5aeb46d7` | `3a1fe7b673044db4d5c35439402d204371bd6781dd24f88349bfa47de0cf5ac5` |
| red03 | `c042d84b2b4ae4001a1414a2cbdca5b256550ee5f5e77089cf064ffc25af3ae4` | `a4bc596d4398d5af2ceff7954a95dafa4bd4ff16ebb4ff200820f5db8b4ebc3a` |
| green01 | `3fd94d311bd65ecec519eff7bb03bedb181166c0af20278d8080b72bbf27d1a4` | `85444295b0eb8d1dcdebfc0e044a2009886b09d34045168e46734c0092ca7d93` |

三轮 Surefire 各生成一份 Windows classpath 根路径 dumpstream，均随 final target 物理快照保存；内容是 Boot Manifest-JAR absolute paths / `'other' has different root`，不是 XML 测试错误。保留原建议文本，未禁用 JDK URL 检查。green raw 没有 ERROR/WARN，XML 0失败/错误。

未重跑 common179、原 engine35、七 reactor、旧 D2/D3 或其它 PG 类。源码搜索确认本次 fixture 新 SQL 分支只匹配 recordCheck，而此测试目录的 recordCheck 调用只在 current 类；其它 fixture 分支与承载 PG 保持原真实行为。没有额外共享生产变化或未解失败需要扩大范围。common179 对应77项 common+root POM仍逐字一致；原engine35的最终全356与本fix存在明确4文件差异，不能把旧35与新12拼成一次新完整套件，也不能声称12项覆盖所有 engine 功能。

## 固定版本事务源码对照

复用旧 E1b upstream 只读源，实际针对性阅读 `DataSourceTransactionManager.java` 的 doCommit/doRollback 段（330起）与 `AbstractPlatformTransactionManager.java` 的 processCommit（767起至cleanup）。前者真正调用 `Connection.commit()`；后者先 doCommit、后 afterCommit/afterCompletion 并清理，再返回。故事务内的 UPDATE 后终检与外部 commit 返回后交付终检是两个必要边界；没有声称读完 Spring 项目或全部八份上游文件。也实际读 `RetainedAuthorization.requireVerified`，确认使用 proof 自己封装的 verificationClock，检查 current 与 ticket 到期。

- 固定 Spring 6.2.7 `DataSourceTransactionManager.java` SHA `1cbf0f1a13d957ccaff5bfe3fe511cb70823445eef92a062e5d552a2ff67b135`。
- 固定 Spring 6.2.7 `AbstractPlatformTransactionManager.java` SHA `060a6b97928f033a8e0caec76a96d378e4f8b7c22309d54acf832dcdcf7f3081`。

## 冻结、保护与所有权归还

先执行本包 `audit.mjs before`，完整356 src/POM before与原 E1b source-after逐项相等；保存1692 target before清单，并物理复制当时903 common+engine target。初始 `protected-before.json` 保存36384项 docs/contracts/web/deploy/config/AGENTS/受控 runner（排除本fix目录、报告和原既定 node_modules/dist）；原 E1b 全证据、报告、review、root放在docs的证据均在内。final 对同一范围仍36384项，0新增/修改/删除，没有扩大排除或重写 before。

初始保护遗漏了 `.local/iam-stage-5-e1b-root-handoff-check.json` 与 `.local/artifacts/iam-stage-5-e1b-common-engine-target` 的显式 before采样，这是本次证据边界缺口，已即时向 root 说明。作者没有写这些路径；随后作补充核验，903个旧root target备份与旧E1b `target-after.json` 逐字相等，原backup自身 manifest也单列SHA；root audit当前SHA `72569ee3d41c59644c3c91f08e4684e16c0157f6ebb476727fa002da7b43a806` 交 root 用其独立基线确认。补充事实写 `supplemental-root-protection.json`，不把事后采样伪称事前保护，不改初始before。root可依据其独立证据补这一个audit的适用性。

root 随后独立完成并记录 `.local/iam-e1b-i1-parent-artifacts-check.json`：903个备份对受保护旧target-after历史清单（SHA `e4fcea9e61e0c23a5224dde58896ba5802dedf0b8eeeb053cefa1fcf939031c2`）全部字节一致；原root audit当前SHA同上，并用原 `2026-10-20T10:04:13.871Z` 完整工具输出和旧不可变证据重建确定性JSON，实际全字节匹配。该处置明确 `directPreFixRootAuditShaSampleAvailable=false`；这是后续独立核验，不是反推事前直接SHA采样。作者实际读取该处置文件后引用，未替root改写其记录。

`freeze.mjs` 完成真实最终冻结，`verify.mjs` 再读核验：

- 当前356项 = green01同轮356项 = `freeze-source-final`356项 = `replay-final`356项，全部实际字节相同，无额外 replay 文件。最终 `source-after.json` SHA `ee0d2c6d95bfef26ae5185b41252bba1ea46a7971d6d6a2dcefcdc42ff6803ab`。
- target1692→1695，仅11项 engine 差异：6项编译类、2项 current报告、3份新dumpstream；0项 common target变化、其它789 target不变。final common+engine906 target物理保存；311项 compiled manifest已冻结。未手工改 common target。逐项SHA/bytes见 target-before/after/changes。
- 全上下文补丁 `change.patch` SHA `e906abacf1af23418a376915c745612111f9e46a54945d2a27866f28afef96c0`；`git apply --check` exit0；随后对 before完整356物理树实际 `git apply` exit0，最终356逐字一致。没有仅凭exit0推断字节成功。
- 唯一本包局部 `.gitattributes` 为 `* -text`，SHA `705fd4d6451a31d36b3df7de96f83f30ac976c9b4a6d1e51671d8e2f33e2d0da`，控制文件单列 `replay-controls.json`。工程root属性SHA仍为原 `08bb8d13896f619a5794638b2c0b553a1d3beedb4fcaa484719c78fba93f667b`。没有修改工程属性、源码行尾或全局Git配置来规范化字节。
- Git parent ceiling显式为工程的直接父目录；实际 HEAD验证exit128 `fatal: Needed a single revision`，remote空；没有add、commit或伪造HEAD。完整context diff确有4文件变化。diff/apply stderr空；`git diff --no-index --check` exit1且whitespace log空，记录为两树有差异的实际退出状态，不将其伪称exit0，也没有隐藏whitespace诊断。

自审确认两个独立漏洞窗口均由原实现行为红支持，并由current类新绿验证；public仓储后检、提交后VA交付、旧失败CAS不覆盖新观察都保持明确事务事实。未知数据库异常仍沿原路径传播。没有把失败观察写成授权许可，未放宽生产IAM闭锁。

本报告与 `iam-stage-5-e1b-i1-fix/` 为本次唯一新增证据；旧报告/证据/review/root备份只读。source/target所有权现归还 root，作者停止改源码与Maven，等待同一reviewer仅审fix包、独立复核两门。独立复审通过前不登记闭合、不推进下一源码作者。原v1.2/四契约/固定依赖/生产闭锁/仅T01完成保持；本修复不等于全Task5、真实controller/业务接线、scheduler或生产IAM完成，不重新排工期。
