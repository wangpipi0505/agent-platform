# E1b I1：数据库等待与提交边界的授权期限补强（正式授权）

唯一工程/workdir G:/207/项目/算法重组工具/代码/agent-platform；严格不读/关联相邻旧代码/配置/数据/进程。先读AGENTS.md、contracts/README.md。固定v1.2/四契约/依赖/生产IAM闭锁、未接线范围、不排工期不变。用户已授权持续开发和隔离端点，不重复询问。

E1b作者与审查者均已归还source/target。root已全文读取原报告及独立审查，I1为本组件自身的重要缺陷，接受该发现，未登记E1b闭合。正式授权唯一fix作者仅修改下述有限范围。基线356源/POM见iam-stage-5-e1b/source-after.json（SHA35f339e649d7849c3b10c17607c06bc067cc5947047386443c04e9f9d5bce54e），全部当前live/final-frozen/replay-final/finalengine35已由root核同。原报告SHA7070a68c00a6b0f52d14735ac0a78ae39d407ff729981d50f9d627e2efdca5db；原独立review SHA bb1322991e9983d874108c4bc5da04502784dc538c800e7720a745457a7d19e9。root审计.local/iam-stage-5-e1b-root-handoff-check.json。

## 需修复的实际窗口

RunAuthorityStore.recordCheck只在UPDATE前requireVerified。另一PG连接对同run_authority行持锁而不改checkVersion，current检查完成后UPDATE会等待；期间当次signed current/ticket/operation/grant最早期限到期，UPDATE仍可能返回1、事务提交，RunAuthority.current直接返回proof.current。原staleCAS测试改变checkVersion，不能覆盖此情况。

修复两个边界：

1. 成功UPDATE返回后、仍在短事务内，重核同一proof的私有可信Clock期限；失败必须抛出使本次成功记录回滚，不能清旧503/登记新success。原异常不吞，不锁内联网/续期/换新operation，不用外部传入Clock伪造本地检查。
2. 真正commit返回后、向调用方交付可推进VA前，再验同一proof。若提交期间才到期，该调用拒绝，不返回过期VA。已提交的历史观察无法回滚，不伪造“回滚成功”；后续失败记录若CAS已过时应尊重新版本，不能覆盖其它检查结果，不能掩盖原到期异常。无需为了这项补强新增授权收据/事务能力框架或改变原Grant/initial/scope。

public recordCheck成功入口本身也必须保持完整fresh proof/immutable binding/三个scope和可清阻断规则，不能只在application补check而让public仓储入口绕过UPDATE后的终检。保留同DS事务要求、网络在事务外、CAS0拒绝、永久403/未到期503不可清、未知DB错误原样传播。返回之后下一真实动作的检查仍由后续driver/scheduler负责，本段不声称直到任意未来时刻一直有效。

## 允许文件与真实验证

仅engine production infra/RunAuthorityStore.java、application/RunAuthority.java，以及infra包下RunAuthorityLocalCurrentPostgresTest.java；确有必要可最小扩展既有RunAuthorityFixture.java测试装饰器。不得改变common/MaterialScopeStore/迁移/RunService/调度/HTTP配置/POM/前端/契约、其它业务源，不加生产故障hook或改变公共API形状。构造注入形状和生产未装配状态保留。

先在当前原码取得实际可编译行为红，再实施并具名绿。至少两个具名用例：

- successExpiresWhileCasWaitsOnUnchangedRow：两真实PG连接/有限闩锁，对确切run_authority行持锁且不改version/binding；观察真正CAS UPDATE已进入等待后推进原fixture Clock越过最早期限，释放锁。原实现应错误返回/记成功而断言红；修复后无可推进VA、成功检查未提交/旧临时阻断不被清，原材料/initial/业务事实保持。finally有限释放/关闭，不实际sleep等5秒，不压力循环。
- currentDoesNotReturnProofExpiredDuringCommit：真实PG transaction commit确实完成后、对调用方返回前通过测试专有Connection/DataSource包装推进同Clock，定位本次成功authority更新对应的commit；不能在load/create的早期commit随意推进。原实现返回过期VA应红，修复后拒绝。不要用fake数据库/替代repository，不能用仅callback内advance冒充commit返回边界。必要时验证过时失败不能覆盖新检查；已有staleCAS语义保留。

测试装饰器可以拦真实Connection.commit并先委托真实提交后触发有限hook，或使用等价实际事务装饰；不为测试给生产新增可调用故障入口。不修改Clock绑定、不反射造proof、不从旧Actor出票。唯一PG仍agent_platform_iam_engine_v12、127.0.0.1:25432/agent_v12，凭据沿既有.tools/run-iam-module-tests.mjs受控子环境，不另写env解析器/不打印配置/不碰其它库。

每轮raw/XML/source立即归档；仅按raw实际Running类计本轮XML，禁止120秒mtime窗口夹带旧XML。先两具名红，再实施后具名或该受影响current测试类一次完整绿；只有实际新增共享变更/失败/未解疑点才扩大到其它新PG类，说明理由。common源码不变，不重跑179或原35/七reactor/旧D2/D3。最终精确说明新测试适用源范围，不能把旧35与新具名数字相加成一次新完整套件。

## 冻结与交接

独立报告docs/evidence/iam-stage-5-e1b-i1-fix-report.md与专属目录iam-stage-5-e1b-i1-fix/。原E1b报告、目录、审查、审查包/root原审计/903target备份均只读不改，保护before先采。root当前未授权任何并行docs新增，.local/current-work-state等root内部状态除外；若遇保护差异先保留事实并报告，不能扩大目录排除或重写before。

保留完整src/POM before/final/frozen/实际apply replay全356字节、target前后差异/必要实际编译快照、protected清单、实际红绿raw/XML/执行集合/sourceSHA，回报具体命令/输出及自审。吸取前轮Git规范化失配：仅专属证据根局部.gitattributes * -text供复制树继承，控制文件单列SHA，不改工程root属性/源码行尾/全局Git；正确parent ceiling，无HEAD/remote不伪造commit/空diff。保留任何失败和whitespace诊断，不照提示禁用JDK检查。

受控Maven正常维护common依赖target时逐项记录实际变化，不把它当common新源/新测试；作者不得手动改common target。旧179的77项common+rootPOM source字节不变适用性继续核，原engine35与本fix全356的差异必须透明记录。修复完成交回source/target，再由同一E1b reviewer仅审fix包并复核两门；未通过前不登记闭合、不推进下一源码作者。
