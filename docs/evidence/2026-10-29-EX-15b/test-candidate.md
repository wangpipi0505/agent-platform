# EX-15b 第一轮 test-only 候选（未执行）

日期：2026-10-29。作者：`ex04_pg_scope`。本次只新增以下两文件，未修改生产/原测试/契约/三主入口/索引，未运行 Java、Maven、Node、HTTP 或 PG：

- `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessFilesTest.java`
- `docs/evidence/2026-10-29-EX-15b/test-candidate.md`

测试源 raw SHA-256：`7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7`，10,665 字节，210 行。尚未编译，不能记定向通过或实红；等待 root 独立静审、EX-15a 提交完成及唯一实际窗口。本文不授权自动运行或后续改生产。

## 两个方法及精确期望

| 完整类 `cn.agentplatform.skill.infra.ProcessFilesTest` 的方法 | 实际动作 | 固定断言 / 预测 |
|---|---|---|
| `acceptsValidEnvelopeWithoutOutputFiles` | 已存在 ProcessAdapter 正式配置构造 → 真实内嵌 JDK worker；读取 stdin 至 EOF，stdout 一个合法信封，files=[]；result 回显本次 stdin。 | exitCode=0；marker 精确相等；收到协议=algorithm-process/1；stdin files 为对象且0项；输出 files 为数组且0项。预测 PASS，用于证明同一夹具/JDK/协议前提成立。 |
| `rejectsParentRelativeOutputPathBeforeReturningSuccess` | 同一真实调用链；唯一业务差异为 stdout files 含 `path="../escape.bin"`、bytes=0、空内容正确 SHA。该字符串从不交给任何文件 API。 | 必须抛 PlatformException，**status=502、code=PROCESS_OUTPUT_INVALID**；不能只检查“某个异常”或改为接受空引用。当前 success 仅检查 files 数组形状，预测 assertThrows FAIL。 |

当前预期原始结果是 **2=1P1F / 0E / 0S**，尚未实测。若编译/JDK/classpath/身份握手/超时/目录权限出错，则是夹具或前提错误，不能把 ERROR 或无关失败记为路径拒绝实红。待实际 XML 确认严格拒绝断言失败后再冻结红件；不删旧断言，不更新期望来追绿。

测试仅用现有 `ProcessContract.parse`、`ProcessAdapter` 构造/Request/execute/cancel/inFlight/Output、`ProcessTree.killTree(ProcessHandle)`。没有引用尚不存在的 files API。两个测试都未创建 artifact、不假冒授权下载/PG持久/JVM重启或 X-1 业务验收。

## 自有资源及有界回收

- 每方法独立 JUnit `@TempDir`，在其中建立 `中文 空格 PROCESS 文件根`；ProcessAdapter 在此根内创建唯一 call-*。keepFailedDirs=0，不涉及历史 failed-*，finally 严格断言本根条目归零。根外没有 sentinel/输出文件的读写；`../escape.bin` 只作为 JSON 声明。
- 唯一可执行白名单是当前测试 JVM 的真实 `java.home/bin/java.exe`（非 Windows 为 java），实际运行前由 root 确认它为既定 JDK21。调用 argv 不经过 shell：该 java、`-Xmx64m`、`-cp`、本测试编译 classes 绝对目录、`ProcessFilesTest$Worker`、模式 empty/escape。worker 只用 JDK 标准库，不加载产品逻辑/外部 worker/Node，不起子孙进程，不联网，不访问 PG/MinIO。
- 每次调用随机 requestId，适配器实例独占，全局/tenant额度各1，queueWait=0。传入的 files-test 仅用于该实例的并发配额，不据此宣称 JWT/租户隔离已经验收。
- worker 读至 stdin EOF 后，仅在**本次 callDir**写 identity.tmp→identity，内容为 pid/startInstant/requestId。父测试在自己的根内轮询该文件，核 requestId、真实 ProcessHandle 启动时间、直接父 PID=测试 JVM、真实可执行路径、callDir 直接父目录；才登记为 own worker。
- 父测试只在同一 callDir 写 release.tmp→release；worker 收到精确 requestId 后输出信封并退出。两个文件均先完整写入再重命名，避免半写入握手。没有向 callDir 外写 PID/control 文件。
- 有限等待：每测试 JUnit 60秒；进程契约20秒；父身份等待15秒；子放行等待10秒；结果等待25秒；finally caller收敛10秒。只有本次身份/requestId 能触发 release/cancel，不能因同名 Java 或旧 PID 杀进程。
- finally 首先取消此私有 adapter 的本次 requestId；若已登记 worker 仍活且 startInstant 相同，再用该确切 handle 的既有 ProcessTree 原语收尾；随后取消 future、终止并等待自己的 caller线程，严格断言 inFlight=false、该同一进程已退出、callDir已清空。未登记 PID 不尝试遍历/杀其它进程。

## 待 root 执行的准确 Maven 目标

只在 EX-15a 本地提交完成、测试源 SHA 经独审冻结、源码/JDK/环境/installed admin-common 指纹登记后执行。当前 AP 路径及 `.tools/m2` 沿 EX-15a 原配置；V: 别名必须已确认仍映射本 AP，`.local/tmp` 归属已核。下面只是待执行命令，本文未执行：

```powershell
$apRoot = 'G:\207\项目\算法重组工具\代码\agent-platform'
# root 的采集器先保存/固定环境，并核 JAVA_HOME 为原 JDK21：
# C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot
# 在 $apRoot 工作目录执行，保留原 .env/installed common/默认 fork 与 order。
$mavenArgs = @(
    '-B', '-ntp', '-pl', 'skill-registry', 'test',
    "-Dmaven.repo.local=$apRoot/.tools/m2",
    '-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp',
    '-Dsurefire.timeout=900',
    '-Dsurefire.failIfNoSpecifiedTests=true',
    '-Dmaven.test.failure.ignore=false',
    '-Dtest=ProcessFilesTest'
)
& mvn.cmd @mavenArgs
```

不执行 clean/install，不加 `-am` 扩到其它模块，不用 ignore 抹掉红；这两例没有 PG/MinIO 环境条件注解，不能环境缺失就 skip。root 应使用本卡新 RunId 保存原 Maven log、2方法 XML/状态/原因、native、开始结束源码指纹、JDK/依赖/环境指纹和此次资源事实；不要覆盖 EX-15a 原结果或旧 sure-fire 证据。实际超时由 root 现有采集流程有界执行，不让新脚本接管共享进程。

## 静态确认与下一步

本次静态确认：仅2个 @Test；代码无尾随空白；使用的现有产品方法签名已读；4个原输入源码 raw SHA 与 preparation 相同：ProcessContract=`72699070…45252D9`，ProcessAdapter=`6898320E…58A293`，ProcessProtocolTest=`77495D77…FC9B28`，ProcessAdapterHardeningTest=`0DE036BF…4C064`。这不是编译/测试结果。

root 独审后方可运行第一轮。生产修复及文件通道完整测试仍等待协议增补和准确白名单；本候选没有扩写完整 IO、清理/持久、文件预算、symlink/TOCTOU 或 EX-16 取消生产接线。外部 X-1 worker 仍不能由本测试桩代验。
