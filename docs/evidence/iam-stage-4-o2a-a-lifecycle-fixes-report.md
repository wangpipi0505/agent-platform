# O2a A 生命周期窄修复实施报告

实现及授权验证完成，等待原 reviewer 窄复审与 root 有限审计；本报告不宣布 A Accepted，也不宣布全局 IAM、SSE、PG 或 B/C 完成。唯一工程为 G:/207/项目/算法重组工具/代码/agent-platform；每条 shell 显式使用该 workdir。无 commit、部署、自动化或新 chat，无邻接旧工程访问。

## 冻结输入、范围和读边界

正式生命周期 brief 与原 Needs fixes review 已读到 EOF。输入精确身份集中于 inherited-input-identities.json；原 review 统计 C0/I3/M1。先读 review，再完整分段读取本轮两生产文件、Profile 和必要 Loopback fixture；原整包没有机械重扫。本轮只修改以下三个既有文件，0 新增、0 删除，LoopbackAuthority 保持原字节：

- admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java — 72236B / SHA256 1636a6c3388d3eb7f3dae55ce36f00be488f64768fcb6d087b89db51979e6b0b
- admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java — 6338B / SHA256 042ca6ff04bb7076dde3ae7a2dfcd9467945274ca95846c2f20b8c81658ac6c6
- admin-common/src/test/java/cn/agentplatform/common/authority/PortalEventsAuthorityProfileTest.java — 51138B / SHA256 dcf8f89d690599a8287f838550e47ddb1c0969b1314e88411ced34b1d922b988

最终 source 589=backend397+frontend192；独立 rg 枚举实际源文件名与冻结589一致，非仅对已知名字做计数。相对原 final02 仅上述三项变化。最终命令 before/after 的 source 与 finalSource 全部 bytes/SHA 相等，executedSource 与 finalSource 独立保留。

## I1：停止发布与实际取消交付分离

slot 在锁内只创建并保留一份 cancellation task，预留 callback work 后发布 stopped；任何并发 closer 都可将这份同一 task 交付。runtime.close 对 snapshot 内每个 slot 无条件执行 slot.stop，不以 holder.cancellationStarted 代替交付事实。submitted 的检查及提交在 task monitor 内协调，回调实际执行始终在 holder/slot/runtime/task 生命周期锁外。

worker executor 拒绝取消动作时，未执行的 callback 不被 cancel/finish 当成已完成。非 watchdog 的停止调用线程在锁外执行同一 FutureTask，保留 slot 到 callback 的实际 finally。唯一 watchdog 遇到拒绝则不标 submitted、不执行 callback；该 slot 的固定字段保留未交付任务及 work，下个250ms tick只重试本地取消派发，不重试 authority HTTP。close 先停 scheduler，再由非 watchdog closer 帮助派发或锁外兜底。没有新线程、逃生队列或每 holder executor；仍为2 workers/queue64/64 slots/250ms watchdog。

确定性 close-gap 用例在 holder 已标 cancellationStarted、尚未进入 slot.stop 时阻塞，另一线程令 client worker shutdown 后再恢复，断言 callback 恰好一次、运行中 slot仍1、实际退出后slot0及workers/scheduler终止。另两个饱和用例用2个有限阻塞任务+64个真实queue filler制造真实拒绝；断言 caller回调锁外执行、回调运行时容量未归还，以及 watchdog 后续deadline barrier可达、submitted仍false、回调最终不在watchdog上、64 fillers实际执行退出。无概率循环或sleep复现。

caller兜底允许非watchdog close调用者被其拥有的callback阻塞；不声称物理关闭或即时撤销SLA。若worker被外部I/O长期占用，未交付callback保持占额，未把逻辑cancel当成物理完成。

## I2：预留之前完成可失败的期限计算

renewEvents 在创建task/addWork之前取得previous.remainingNanos并计算deadline。deadline、owner或current失败时不存在未发布reservation；预留之后到pending发布之间只剩不会抛出的字段赋值。发布后到execute之间的异常路径显式cancel这一个task，正常finish继续幂等回收。

用例在第二次deadline核验之前以barrier暂停；此时第一次holder.requireCurrent已成功，推进受控时钟6秒后恢复。原实现在task预留后失败，callback实际结束仍留slot；修复后续期HTTP为0，callback结束后slots0，后续合法response可重新占用容量。这与已发布queued-opening timeout不同。

## I3：注册、提交与终态共用task monitor

execute在同一task monitor内检查finished/isCancelled/submitted、创建timer、交付timeout字段和提交worker；cancel的Future终态发布与queued移除也使用该monitor。finish在该monitor内标终态并取消已交付timer，holder/slot回收在锁外。isCancelled检查覆盖cancel已发布但锁外finish尚未开始的窗口；它不是没有协调的一次isDone检查。

两个确定性交错分别停在pending已发布但execute未开始，以及scheduler.schedule返回但timeout字段尚未赋值。第二种交错中真实取消线程到达task monitor并阻塞，恢复注册线程后取消完成；没有把测试hook移出该窗口。两例检查实际worker queue和scheduler queue均0、work回到0、重复cancel不再递减；续期HTTP为0，旧capture和closed holder均拒绝。原已在真实HTTP处阻塞的late-renew用例也随整类最终命令通过，迟到签名成功不能重新install。

观察接缝仅为每client、包内默认无动作的阶段字符串Consumer；无全局可变hook、公开权限factory或证据导出。新测试暂时取消该runtime原periodic watchdog，再由barrier精确驱动交错；watchdog拒绝用例在同一真实scheduler上驱动下一deadline与tick，不增加生产调度器。

## M1：准确限定grant覆盖

原 idleRevocationAndSourceOrGrantExpiryStopWithoutAConsumerAndNeverResumeExecution 改名为 idleRevocationAndElapsedHardBoundariesStopWithoutAConsumerAndNeverResumeExecution；两个长跳分支改成past-source/past-all-boundaries。真实协议要求operation.expiresAt<=grant.expiresAt，fixture opening又要求binding<=operation；合法签名不能构造grant严格早于所有其他硬边界。默认current5秒、binding30秒、source120秒先于grant300秒，所以推进301秒不构成独立grant-first证据。生产grant hard-min仍保留；本轮明确没有独立grant首触发覆盖，不修改协议，也不通过非法签名事实造绿。Loopback fixture无需改动，因此按授权不重跑ReceiverClosed。

## 实际命令与结果（分阶段，不相加冒充一次）

所有测试均通过unchanged .tools/run-iam-module-tests.mjs，Java21、offline .tools/m2、独立stage、逗号/# selector，无加号。每条命令执行前后立即保存完整source/必要target manifest、三任务文件真实文本快照、raw和仅actual Running XML，具体argv见commands.json。

| Stage | 实际Profile方法 | PASS | FAIL | ERROR | 用途 |
|---|---:|---:|---:|---:|---|
| iam-stage-o2a-a-fix-red01 | 5 | 0 | 5 | 0 | I1 close-gap/拒绝、I2、I3两个交错的真实行为RED |
| iam-stage-o2a-a-fix-green01 | 5 | 5 | 0 | 0 | 上述五方法定向GREEN，尚未覆盖watchdog拒绝 |
| iam-stage-o2a-a-fix-red02 | 1 | 0 | 1 | 0 | 通用caller-runs会阻塞watchdog，deadline barrier真实失败 |
| iam-stage-o2a-a-fix-final01 | 28 | 28 | 0 | 0 | 唯一一次完整Profile最终命令，原22+新6 |

最终精确命令：node .tools/run-iam-module-tests.mjs iam-stage-o2a-a-fix-final01 admin-common PortalEventsAuthorityProfileTest。Maven编译57 main/29 test sources并自动重建fixture tests.jar；编译不是执行那些旧测试。未执行旧六类、旧Service冲突、PG、engine/gateway、POST或SESSION验证。

red01 raw另保留两个人工worker blocker的3秒超时Assertion诊断；它们不改变实际XML的5FAIL/0ERROR，之后仅将有限blocker退出等待改为10秒并在finally释放。final没有这些额外worker诊断。原A final01八类85=72PASS/13ERROR，随后green04仅Profile22/22，是只读历史分阶段事实，绝不改写成85绿。

## 源、target与新diagnostic归因

- docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/source-after.json — 111999B / SHA256 3ad9c38b06e8ddc637f6ef4a36a5c2e474d15cf306ba1af341a3d6fad01ae7e4
- 正式最终target：docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/final/target-after.json — 419611B / SHA256 b5bedc4fe8a66f1e4ee171adafd823d72cae3fb576e2e8e3358b82a827e2a335
- docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/final/target-lineage.json — 4121B / SHA256 5fffe76826db77cfca03b3eb544952e8595f733eecc2f150e6e83be19d1dadd8
- docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/final/target-metadata-correction.json — 1658B / SHA256 b2287789990ac70f54d4cf1866b3501cccd0d46b84f75e8cafa4f71a9ccbcf25

正式target继承logical2023，按本common实际createdFiles/inputFiles、resource、自动tests.jar与actualRunning reports逐项overlay；新增compiler class为0。历史logical包含旧diagnostics，不是纯classpath。本轮4个真正新增dumpstream各344B，全部是Surefire Boot Manifest-JAR absolute classpath/root提示，按命令pre/post身份差异独立保存于diagnostic-attribution.json及各after/diagnostics；不加入正式logical target。

作者最初误将这4个新dump吸收为2027，原target-after.json、target-lineage.json及final-physical-check.json作为superseded元数据原样保留。root指出后仅用缓存身份删除这4条，生成final/正式2023与各target-overlay-corrected.json；已成功2027项/0漂移的有限物理核验中，其余2023项已核验，未再次读取/重哈希旧target。原app4差异沿既有corrected lineage继承，未重编或重新归因。

compiler list文本在封存时补存：每一份都先与该命令即时manifest的bytes/SHA完全匹配才复制，并在compiler-list-provenance.json明确记录这一恢复来源，不伪称当时已复制文本。

必须继承的root纠正：docs/evidence/iam-stage-4-o2a-a-diagnostic-attribution-correction.json — 41759B / SHA256 dfafb36faf1735dd726fd472fb022a027993ec8b672514a6bdfb99573cf8ffbf。旧194中183是历史源log、11真正新增；旧logical2004本含908dumpstream，不能借历史lineage将183或本轮4伪计新增compiler产物。另见docs/evidence/iam-stage-4-o2a-a-service-error-method-correction.json — 2176B / SHA256 9dd13931a47abe2441920e58e10256f0caf8fec17c58c3f0c13a17fbe4b43b15：旧error名单误含health、漏actorProjection，实际13按XML/root决定保留。两份root纠正及作者旧封存件均只读。

## 私有replay、元数据失败与资源退出

docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/freeze/replay.json — 688B / SHA256 693b6020c390f71130b03fb64041a0405c1e5ee84b536aa23099e37746988ed6。本次冻结baseline589原字节先复制到独立private no-commit repo；首次实际git apply --check与apply均0，apply后没有copy final字节进去。实际589 names/bytes/SHA与finalSource全部一致；补丁docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/lifecycle-fixes.patch — 31121B / SHA256 24db27119ab4e366003781433b414bf5d290d090749fcada3bf1724d2f83e4bc。原A freeze01和final02此前各自成功的replay只读继承，本次不是把整个A历史说成只有一次replay。

两次metadata helper失败和一次归因更正均保留，不重测/重放：

1. freeze首次已成功source阶段，target-lineage复制common createdFiles.lst到自己after/compiler-status时因目的目录未建而ENOENT。只建自己目的目录后缓存续行，source成功阶段未重复。
2. verify-final误选继承app compiler list：对app-management/target/maven-status/maven-compiler-plugin/compile/default-compile/createdFiles.lst执行只读identity(bytes/SHA)后，尝试fs.copyFileSync到docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/iam-stage-o2a-a-fix-red01/before/compiler-status/compile-default-compile-createdFiles.lst，使用COPYFILE_EXCL。目的已是common同名副本，EEXIST失败；没有覆盖目的，没有对任何app物理路径写入、恢复或删除。过滤改成仅admin-common后，仅补建/校验自己证据目录中的common list副本；未再执行原误选逻辑。metadata-failures.json有明确源、目的和操作，不将此淡化为仅“未编app”。
3. 2027 target分类错误按上述root要求改成缓存组合的2023，原物理成功阶段/原清单不覆盖。

每个Session均关闭engine/gateway client与两个loopback HTTP fixture；Session.close新增断言events workers/scheduler已terminated。I1另在owner.close返回后核实线程池实际terminated；人工filler实际退出数64、active0；callback仍运行时slot保持。原worker终止诊断O2A_OWNED_WORKERS_TERMINATED=true在final raw中。Maven/Java子进程已返回0；未启动browser、PG或应用服务，没有借用外部owner或枚举相邻进程。

8执行索引/README/原设计未改，index-check.json逐项符合既有checkpoint SHA；不由作者更新Accepted台账。所有自身新leaves、原阶段失败、原始诊断、baseline/final/replay副本与必要脚本将由inventory.json精确列出；inventory与handoff的自包含循环仅以两个精确self exclusion声明，其他自身leaves均带bytes/SHA。

## 交还

handoff.json指向正式source、final/target、inventory、报告、patch/replay、actualRunning及全部纠正。交还所有三源码、target/backend class、自己证据/脚本/报告写权并停止写。root原审计5phase/2464physical PASS仅是证据，不是Accepted；本轮也需原reviewer独立窄复审及root有限审计。

EOF
