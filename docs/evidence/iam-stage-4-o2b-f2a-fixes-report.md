# O2b F2a 独立审查修正报告（待同一独立 reviewer 复审）

本修正实现原审查 I1/I2/I3，并处理 M1/M2。唯一新 final 实际 Running DebtTest 7 + TlsTest 40 = **47 tests / 0 failures / 0 errors / 0 skipped**，exit 0。原 Needs fixes 不由作者自行改成 Accepted；仍待独立复审和 root 有限审计。

## 绑定输入与隔离

固定 shell workdir 为 G:/207/项目/算法重组工具/代码/agent-platform。已读 AGENTS.md、docs/implementation-brief.md、contracts/README.md、正式 fixes brief、原 review、原 TLS brief/boundary clarifications、原 evidence contract，按 receiving-code-review / TDD / ponytail / verification 执行。未访问相邻旧工程，未新增依赖、修改 pom/common/main/assembly/controller/四契约或八执行索引；EVENTS 继续关闭。只读复用原 PKCS12，无 keytool、provider probe 或旧测试包装重跑。

- 正式修正 brief：docs/evidence/iam-stage-4-o2b-f2a-fixes-brief.md，8536B/SHA256 fcae15430b58383a25f00330f25430bb60f32758feac7ddc73261d884be1eb71。
- 原 review：docs/evidence/review-iam-stage-4-o2b-f2a-tls.md，16561B/SHA256 29bf1098be6afdf1b41b31470a323cd1bc4e66e75b4e3ff6e121cada90684e39；C0/I3/M4、Needs fixes。原文件未改。
- 原 source-after618 与本轮 before：118270B/SHA256 b64fbebf0a650bb8d9cc6988c78752560f657679e3f6bce3f157b17370a339c2。完整私有 before 从原 replay 的 manifest 精确列出的 618 source 文件复制，未复制其 .git。
- 原 target-after2200 为有限历史逻辑 lineage，459636B/SHA256 2c0dc655b7664f3e1dd4efe965a71cd10590edea1ab3ec06c4df69a4566450b8。它与原 39 green、原六 phase/root 审计均保留为原 epoch，不能作为本修正已验收的证明。
- 新 owned：.local/iam-o2b-f2a-fixes/、docs/evidence/iam-stage-4-o2b-f2a-fixes/、.tools/iam-o2b-f2a-fix-*、本报告及六个具名新 stage 的外层日志。原作者 owned、原 patch、原报告、原 review、原 snapshots/库存/八索引均冻结。

## 实现与精确行为

**I1：整次公开读取调用持有单 reader 与 active ownership。** Connection 的 ApplicationInput 在 read()/read(byte[])/read(byte[],off,len)、readNBytes(byte[],off,len)、transferTo(OutputStream) 的允许读取入口共享 beginRead/endRead。read()、继承 read(byte[]) 进入受保护的三参 read；两个 bulk 方法各自在整个方法范围持有 CAS 和 active，包括 transferTo 的 target.write 阻塞期间。内部 readOwned 只读取、检查 stop、对正 n 唯一 credit 并更新时间，不再 CAS/重复进入应用 ownership/重复 credit。CAS 拒绝位于其他调用的 abort 范围之外，因此并发探针不会终止原 owner。

调用方缓冲区下标/null 在取得 ownership 前校验；readNBytes 的有限目标缓冲、len=0、首次 EOF 短读、无数据 credit、输出异常/stop/abort 清理均有直接检查。transferTo 使用固定 8192B buffer 和 checked 累计值；readAllBytes/readNBytes(int)、正 skip、reset 的既有拒绝政策保留。readOwned 在每次下层读取前和交付前校验 stop，bulk 异常 abort，finally 才归还 ownership。raw debt/reservation、W、期限、每 handle/全实例两槽策略不变。

测试 bulkReaderOwnsWholeCallIncludingBlockedTarget 用真实 TLS peer 发 1024 个字节42，target.write 在 CountDownLatch 暂停后，第二个 read/readNBytes/transferTo 必须立即 IOException。正常模式核对全部数据/1024 唯一 delivered；abort 模式另外占第二槽，abort 后 bulk 未退出时 awaitClosed=false 且第三 acquire 被拒，放行 target 后才允许退出；target-failure 模式验证真实 output IOException 传播及关闭。独立 bulk-eof 首次 readNBytes(2048) 返回1024，后半缓冲仍零，delivered=1024。未把 repeated JSSE EOF 行为改造成组件新契约。

**I2：plain 服务器进行有限实际观察，且存在会击穿原常量 helper 的负控制。** rawServer 的 plain 分支最多捕获65536客户端字节，固定3500ms读取总期限；保存 client-wire.bin。只按外层 TLS record header 分类，不自造 crypto：type20/21/22 计入失败握手允许记录；type23 标为 opaqueTlsRecords，绝不假称已解密；非 TLS 剩余字节计为 application，截断/超大 record 单列 incompleteTlsBytes，明确应用 marker 另记。正常 plain 拒绝断言 captured>0、failureHandshakeRecords>0、application=0、opaqueTlsRecords=0、incompleteTlsBytes=0、applicationMarker=false。

服务器在首连接关闭后真正执行300ms accept窗口，最多接受8次后记录 acceptLimitReached，实际 accepted 每次 accept 才加一，每条额外 socket 立即关闭。正常拒绝 accepted=1；窗口之后的无限未来不在证明范围。负控制由测试发送器向第一个 loopback Socket 发 F2A-APPLICATION-NEGATIVE-CONTROL 明文，保持首连接时把第二连接排入队列，再结束；观察器须报告 application>0 与 accepted=2。只读 ClientHello 不会算作应用；未知 TLS ciphertext 也不会被冒称明文。

**明确纠正原声明适用范围：** 原报告 docs/evidence/iam-stage-4-o2b-f2a-tls-report.md 第79行的“全部拒绝端点均已独立观察零应用/一次连接”对原 plain helper 不成立，其 application=0/accepted=1 曾为常量。原文件和错误 claim 保留不改。本轮只以新 raw wire/分类记录、有限 accept 实际计数及负控制，建立修正后 plain case 的有限证明；不向旧 epoch 追溯补证，不把 stall/drip 未捕获的内容当作独立零应用观察。

**I3：start 成功后立即承担清理责任。** ProcessBuilder.start 的下一条 try 覆盖 launch callback、Properties/文件写入、Child 创建和 collector 建立；任何 Exception/Error 都先 destroyForcibly（仍alive时）并以3s单调期限 waitFor 实际退出，清理失败 suppressed，原失败重新抛出。terminate 在 wait 被中断时继续有限收敛并恢复 interrupt，不用取消标记代替退出。Child 先同步打开输出文件，再建立 unstarted collector，字段完成后才 start；构造中失败会关闭已开输出，外层退出新进程。已建立的 Child.close destroy/wait/join collector 后记 exit。

测试将本测试 owned 的 server.launch.properties 或 server.out 目标设为目录，捕获刚创建的精确 Process，核对 IOException 与实际 waitFor/!isAlive；RED 复现泄漏时测试自身 finally 同样销毁并有限 waitFor。未枚举、查询或操作系统既有 PID/服务。numeric PID 可被 Windows 在已退出的不同 run 中复用，身份使用 (run, PID)，不是全系统 PID 唯一假设。

**M1：关闭状态与归槽结果分开。** 在清除 bottom/SSL delegate 前保存实际已知 isClosed/isInputShutdown/isOutputShutdown，retained SafeSsl 在引用已清后返回缓存状态；terminated 仍仅表示所有 close 成功、active归零且已释放槽。关闭失败继续保留槽。机械 close-state 用真实 loopback socket、实际 shutdownInput/shutdownOutput/super.close 后故意抛 IOException，断言 bottom 与 view 已closed/shutdown，但 awaitClosed仍false且第三槽拒绝。仅用反射取得现有内部 view 进行状态检查，未设置verified/未制造 TLS 成功或 public verified 工厂。

**M2：复杂控制流展开。** 仅在授权两文件展开含锁、计数、异常、finally、循环和阻塞测试的复杂单行，保留简单委托。临时格式 helper 无依赖，并逐字符词法核对全部非空白 token（包括注释/字符串）一致；format-token-check* 保留格式阶段记录。未重构无关对象或添加通用框架。

## 实际 RED、GREEN、失败历史与唯一 final

所有实际测试沿 node .tools/run-iam-module-tests.mjs <stage> platform-gateway <selector> 的既有 offline wrapper，Java21/project-only .tools/m2。stage-catalog.json 对每个物理 stage 列完整 argv/cwd/exit/stdout/stderr、外层日志/raw、实际 Running XML/txt、完整source epoch、原始 compiler inputFiles/createdFiles、有限target及新diagnostics。每个 stage 退出先保存 command/result/journal；测试运行期间没有修改 source。

| Stage | exit | tests/failures/errors/skipped |
|---|---:|---:|
| iam-stage-o2b-f2a-fix-functional-red | 1 | 4/3/1/0 |
| iam-stage-o2b-f2a-fix-observer-state-red | 1 | 2/2/0/0 |
| iam-stage-o2b-f2a-fix-focused-green | 0 | 13/0/0/0 |
| iam-stage-o2b-f2a-fix-bulk-collector-green | 1 | 6/1/0/0 |
| iam-stage-o2b-f2a-fix-eof-collector-green | 0 | 6/0/0/0 |
| iam-stage-o2b-f2a-fix-final | 0 | 47/0/0/0 |

functional-red 的两项 bulk 用例真实失败“second reader while target.write is blocked: expected IOException but nothing thrown”；launch 元数据故障真实失败“new owned process”1s未退出，但测试 finally 清理完成。该阶段 plain 先遇第二连接被拒的测试顺序错误，记作1 error，不冒充 I2 functional RED。observer-state-red 调整为首连接保持时先排队第二连接后，原 helper application=0/accepted=1 与负控制断言真实失败；M1 真实 bottom已closed而view=false。此时原生产 Connection 尚未改动。

focused-green 为三个 Important/M1 修正后的13项直接矩阵，全部通过。bulk-collector-green 名称虽含green，实际 exit1/6项1失败：新增重复正长度 EOF probe 触发真实 JSSE SocketException: Socket input is already shutdown，client checked未设置；未隐瞒或改名。随后删去不成立的重复EOF假设，改为独立首次 bulk-eof 短读断言，eof-collector-green 6/0/0/0。只对受影响方法重验，未重复包装已成功的旧阶段。

唯一 final 实际运行两类：PortalEventsTlsDebtTest 7/0/0/0、PortalEventsTlsTest 40/0/0/0，总47/0/0/0，TLS耗时131.1s，整个 Maven 约2分13s。final后源码未改、未再运行测试。原39green没有重跑为旧字节美化日志。

**编译 epoch 的实际继承：** iam-stage-o2b-f2a-fix-eof-collector-green 的 raw 确实重新编译 gateway19 main/15 test，其完整618 source.json 与 final source.json 逐file/bytes/SHA一致，身份同为118270B/SHA256 b0c594e9e4e66748f517b3acd983d402afc1c9982d43b5b038e352f65a6e7853。final Maven如实报告 up-to-date，Running仅上述两类；这里使用此前已实际编译的完全相同字节，未动mtime/复制旧class/强制重编译，也不虚称final原日志包含Compiling。

## 新端点退出、诊断及元数据局限

peer-exits.json 实际汇总71个run、118份成功launch元数据、123份真实owned Process退出记录；全部alive=false，建立过collector的记录均collectorAlive=false。额外5份失败launch没有完整Child交付，由测试精确Process finally记录实际退出。121个不同numericPID对应123个(run,PID)退出，Windows在已退出run之间复用PID，未进行系统PID枚举。每peer只loopback临时端口、有限I/O/日志/时间并finally关闭。

本轮包装有三次纯元数据失败，全部保留于 .local/iam-o2b-f2a-fixes/metadata-corrections.json：Properties.store Unicode转义最初未解码；解码后早期RED的7份launch cwd字段仍为父Surefire user.dir；再后不成立的numericPID全局唯一断言。成功的stage-catalog、target-overlay、测试、Git check/apply、byte replay均未重跑；peer-observation-cache.jsonl保存逐file成功观察后仅恢复未完成聚合。

早期两个RED source snapshot 已显式 ProcessBuilder.directory(projectRoot())，因此只能说启动**请求**为root；旧launch字段记录父module cwd，不能冒充子进程OS实际cwd观察。focused-green开始 metadata改记requested projectRoot，final亦如此。没有独立子进程OS cwd观测记录，不追补或改写旧peer文件。

M3永久保留：原13份Windows跨盘classpath诊断和旧deprecation未扩修；本fix epoch另有6份新344B dumpstream，按stage独立归档，未关闭JDK检查。focused重编译时仍可见未修改IntegrationPolicy/GatewayPublishedChainPostgresTest deprecation；成功Running/XML/exit证明测试执行，不宣称输出pristine。M4永久保留：原 failed thresholds 编译时刻与较晚 input-source 观察不能拼成同一epoch，本报告未把它用作新source编译证明。

## 源码、有限target、二进制补丁与交还

最终仍618 source，恰好2 existing changes/new0，其余616从实际before逐file/bytes/SHA核对不变：

- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsTlsConnection.java`：before 30361B/d83114741dd9ca04bf81f1db7a158916cf2071816360c301350d2bb8c32d6c31；after 35287B/de6d77ca8e5a7e093da63ca9d29dbd4be0e23b600fddc921ecdabb6c482d03b0。
- `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalEventsTlsTest.java`：before 36481B/1902769990fdaa36aac47e2ef930dd42a4b9522d7046f2e58af7660c49f80a33；after 57162B/160108502526edd71ed3aa66e64944f9066c5b9d0c0989a49a9b75535059a2df。

完整 before、after、source-delta/new、source snapshot manifests在 .local/iam-o2b-f2a-fixes/；source-after：118270B / SHA256 b0c594e9e4e66748f517b3acd983d402afc1c9982d43b5b038e352f65a6e7853。阶段source快照保留两授权文件的每epoch字节，完整618的names/bytes/SHA保留在各source.json。所有final字节与最后focused实际编译epoch一致。

target只列实际common/gateway compiler input/created、实际Running报告、明确source资源复制、实际测试fixture JAR。P12的source/target身份与六次实际gateway testResources Copying 1 resource日志单列 resource-copies.json；不遍历旧target全树。实际物理overlay316，原历史logical2200按有限overlay合并为2202；460075B / SHA256 95313cf613f9b3045eb85b524740a77b2fb36fc6a6148ce83b43cce75d3d8067。这不是整个当前classpath或整个物理target的断言，新diagnostics独立保留。

新的完整private baseline/replay均source618，独立空system/global Git配置、空template，add前私有info/attributes为* -text -diff且实际check-attr确认unset；索引cat-file逐blob核对原618 byte manifest，长pathspec经文件传入。无commit。新的different binary raw patch只含两授权existing文件：30553B / SHA256 42556c82f636d9b822da94b84f99fe41da5fcb27f12e02ded7e47212fe347712。首次 git apply --check、首次 apply各一次exit0；回放后完整618 byte rows一致，并独立做names-only闭合，未after-apply复制任何source。原168795B/5750a5a… patch冻结。

handoff将列report/control manifest、source/target、所有stage命令/source/原始compiler列表、actual peer exits、private Git/tree/check/apply/回放及owned helpers；own-inventory覆盖全部新owned/privateGit/stage/raw/peer/snapshot/report，仅排自身。先固定report/controls，再handoff不嵌inventory自身SHA，再生成包含handoff的inventory；最终消息给出report/handoff/inventory真实bytes/SHA，避免循环。

完成后冻结并向root交还全部source/target/owned写权；作者不自行签收、不commit。生产launcher H32768/chain4/AIA启动契约、全量TLS/JCE/native/globalcache/容量、Apache固定Q HTTP、私有E、真实验材/公开SSE仍待F2b/F2c后续；不声称全IAM/34任务或生产已完成。
