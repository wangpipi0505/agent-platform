# EX-15a gateway：PublishedMetadata smoke 缺类变化独立复核

2026-10-29；复核者 `artifact_boundary_review`。本轮只读 Git、源码、现有 JSON/XML 与 JAR ZIP 项，未运行 Java/Maven/PG/HTTP/Node，未改 runner、产品、classpath、历史基线或 index。除本文外，仅按主线程指示纠正 EX-16 preparation 的 engine 基线行。

## 结论与处理边界

**独立技术判定：接受本项归为同一历史 smoke 夹具的固定子 JVM classpath 债务，不认定为 EX-15a 新增产品依赖回归。** 两轮都在旧 smoke 自建子 JVM 的 `PublishedMetadataClient.ownedTransport:40` 失败，其固定 classpath 缺少 Apache client/core 依赖；当前 Maven 测试父 JVM 与 gateway 正式打包均包含这些依赖。相关 POM、传输源码、launcher/Main 与固定 classpath 对基线 Git 均未改变，本卡上传路径改动也不进入这条 metadata 子进程启动链。该判断依据具体依赖与调用路径证据，不依据 FAIL 总数相同。

**这仍是一项真实的缺类符号变化，不能按 UUID 或 Spring 对象 identity 做表示归一化。** 本轮没有复原为什么旧进程首先报 client 类、新进程首先报 core 接口。历史基线未留存/固定相关运行字节码与全部 classpath 内容，不能从当前缺两类推导旧 JVM 的首次解析顺序，也不能声称 client 类现已加载成功。这一历史限制阻止“原原因逐字保持”的断言，但不推翻本项非回归判断：两个类都属于基线已有依赖和传输代码，子进程仍绕开正常 Maven 依赖列表；本卡没有引入对应的新依赖或新引用。因此本项不要求为恢复首次解析顺序另开业务运行窗口。

保持 run02 原 `original15RawReasonGate=false`、`regressionGate=false`、`allTestsPassed=false`。本报告不修改、不覆盖这些结果，不批准重写基线。主线程如另建解释后组合门，可对**这一准确方法/两类缺失/两轮证据**引用本报告的独立接受；必须同时保留原门与未复原边界，不扩展为所有缺类错误豁免，也不能借其余 11 项可解释字符串差异自动吞并本项。

## 原始失败对照

原件：

- 基线 `docs/evidence/2026-10-28-EX-27/runs/safety-module-20261028-233458-012/` 的原 XML；run02 collector 读出并保留的 `.local/ex15a-gateway-module/gateway-module-20261029-ex15a-02/baseline-nonpass-reasons.json`。
- 当前 `.local/ex15a-gateway-module/gateway-module-20261029-ex15a-02/raw-reasons.json` 及同目录 `xml/TEST-cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest.xml`。
- 公共汇总 `docs/evidence/2026-10-29-EX-15a/gateway/runs/gateway-module-20261029-ex15a-02/summary.json`。

固定身份：`cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest#launchTwoIsolatedJvmFixturesWithFiniteDeadline`。两轮均为 FAIL / `org.opentest4j.AssertionFailedError`，子进程 expected exit 0 / actual 1，错误为 `NoClassDefFoundError`，cause 为 `java.lang.ClassNotFoundException`，首个项目帧均为 `PublishedMetadataClient.ownedTransport(PublishedMetadataClient.java:40)`。

| 项 | EX-27 原基线 | EX-15a run02 |
| --- | --- | --- |
| 缺类 | `org/apache/hc/client5/http/classic/methods/HttpUriRequestBase` | `org/apache/hc/core5/http/io/HttpMessageParserFactory` |
| message SHA-256 | `4516fb374ee99532ec81d230d8cd865518cc2fa1dc37530e7d3e4d8f2144536e` | `02df55fd9e66d74aee1d757fcaf44956c39cc564795acffa30936bfdbfe81be7` |
| detail SHA-256 | `5b47ada0f2e0f1c28402bc71bc6135305d8cc1e7f437b38f97554a3325a00259` | `85ae5774578659f7ea166873891a6fc41f8a235b20ce536187eca148d38b4d76` |

run02 的完整模块事实为 28 XML、299=284P/7F/8E、无 class event，原 288 身份加本卡 11 个通过，identityGate=true、sseAnd49ChildGate=true、sourceStable=true，native Maven/collector exit 均为 1；这些不改变本项 raw reason 差异。

## 实际启动链与两个不同 classpath

`PublishedMetadataSmokeLauncherTest` 直接读取 `docs/evidence/iam-stage-4-d3a0/smoke-classpaths.json` 的 `gateway` 字段，使用 `java.home/bin/java.exe -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -cp <该字面字符串> cn.agentplatform.common.authority.PublishedMetadataSmokeMain <JSON路径>` 启动子 JVM。它没有把当前 Surefire 的 `java.class.path` 传给子进程，也不按当前 POM 重新计算依赖。

`PublishedMetadataSmokeMain` 再读取 JSON 的 `app` 字段启动自己的 app fixture，等待 READY，随后在第 29 行构造 `PublishedMetadataClient`。该 client 构造器第 36 行进入 `ownedTransport`，第 40 行调用 `PublishedRunHttp.origin(...)` / `new PublishedRunHttp()`。两次缺类都在这一路径，不能把 launcher 的 FAIL 当作已经完成后面的 metadata HTTP 业务断言。

| 检查面 | 只读结果 |
| --- | --- |
| 固定 JSON gateway CP | 106 项全部存在；既无 `httpclient5`，也无 `httpcore5` / `httpcore5-h2` 项。包含 gateway target/test-classes、target/classes、common target/classes 和 common tests JAR。 |
| 106 项逐一查类 | 对目录检查相对 `.class`，对每个 JAR 读取 ZIP entry；两种缺类命中均为 0。没有任何 JAR manifest 的 `Class-Path` 可隐式补入依赖。 |
| 当前实际 Maven JAR | `httpclient5-5.4.4.jar` 内存在 `HttpUriRequestBase.class`；`httpcore5-5.3.4.jar` 内存在 `HttpMessageParserFactory.class`。是未列入该子进程 CP，不是当前仓库依赖 JAR 缺此 entry。 |
| 两轮父 Surefire XML | `java.class.path` 均包含 client5 5.4.4、core5 5.3.4、core5-h2 5.3.4 的同一路径。父测试进程的依赖完备不能传递给显式 `-cp` 的子进程。 |
| 当前 gateway 可执行 JAR | `platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar` 的 `BOOT-INF/lib/` 实有上述三个 Apache JAR。只检查 ZIP 清单，没有运行 JAR。 |

当前 gateway POM 明确依赖 `org.apache.httpcomponents.client5:httpclient5:5.4.4`；其本地 POM 声明 core5/core5-h2 依赖。`PublishedRunHttp` 已有 `HttpUriRequestBase` 字段/方法签名，以及 `ManagedHttpClientConnectionFactory` / `responseParserFactory(...)` 使用。当前 `PublishedRunHttp.class` 的原始字节同时包含两种完整类名引用。因此报 core 接口不证明 client 类已经被补入或已成功完成实例化；两个未满足的符号都属于同一传输类的链接依赖。

## 历史与当前上下文核对

基线 `run.json` HEAD 为 `0cc967278007343bd5ab09faf9953d86cd573bf8`；run02 HEAD 起止为 `1fe99d67b9f72fad576d5e3a424b24d550dbf0b2`。

对基线 HEAD 与当前工作树执行只读 `git diff`，以下路径无差异；它们当前也无 working-tree diff：根 POM、admin-common POM、platform-gateway POM、`PublishedRunHttp.java`、`PublishedMetadataClient.java`、`PublishedMetadataSmokeLauncherTest.java`、`PublishedMetadataSmokeMain.java`、固定 `smoke-classpaths.json`。相关传输源码及固定 JSON 的 Git 历史仍只有初始工程基线提交 `156335302e8cb8da9aba903e9b6bf3bcc55ce27c`。

固定 JSON 在基线 HEAD、当前 HEAD 的 Git blob 以及当前 `git hash-object --no-filters` 都是 `a4159ee3313e1b12ef9fc3b7ac7080da5a80d255`，不是仅依靠换行归一化的“无 diff”。当前 raw SHA-256 为 `b4d0eda0440917306161a0a2fc29c1eefa07bdd86efdfe1256419be6c0ba388b`，与 run02 before/after 均一致。

两轮原 XML 的 `java.home` 均为 `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot`，`java.version=21.0.12`，`java.runtime.version` / `java.vm.version=21.0.12+8-LTS`，vendor=Eclipse Adoptium。EX-27 runner 与 run02 context 也指向该 JDK。没有 JDK 版本切换的证据；未用 `java -version` 或任何 Java 程序重验。

run02 before/after 与当前文件的相关 source、3 个 POM、固定 JSON、`PublishedRunHttp.class` 指纹均一致。当前 class SHA 为 `4763c25f925d3aed098b90a07f7e597435f28442d8a2009ea766bef1d387cfeb`，source SHA 为 `5656dacfdd8bbb77b9b25526f135854f1ab78a573084ce7fdc6a91d4e1a7794b`。

**历史覆盖限制：** EX-27 fingerprints-before/after 只有 8 项（SSE/proxy 相关源码/测试、application.yml、.env），没有固定 JSON、此 launcher/传输源码、相关 target class、Apache JAR 或 JDK 二进制。Git 快照与当前相同，不等于已取证旧运行时每个未登记文件的原始字节。现有材料不能排除旧编译产物状态或具体 JVM 链接/校验过程的差别；也不能选择其中一个作为已证明的首次缺类变化原因。本轮未为填补历史缺口另跑旧代码或修改夹具。

## 当前只读 JAR 指纹

| 文件 | SHA-256 | 检查 |
| --- | --- | --- |
| `.tools/m2/org/apache/httpcomponents/client5/httpclient5/5.4.4/httpclient5-5.4.4.jar` | `3f8657eab91b7533e5cf232d4e268253c4ff9f99f1e4bcd67841618bed67f2de` | client 缺类 entry 存在 |
| `.tools/m2/org/apache/httpcomponents/core5/httpcore5/5.3.4/httpcore5-5.3.4.jar` | `f2b2fd7a0164e8c3120cd8437bc9fea439239e165c17262b3e907daa7e979f80` | core 缺类 entry 存在 |
| `platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar` | `9ecb0173d829b637a2a56e76e743b5ff53f2db0eb576df82073753490e4bf592` | 包含三 Apache lib |

若以后修复这个旧 smoke，需单独登记准确夹具范围并让子 JVM 使用与本轮构建一致且被冻结的依赖列表，重新取得该方法实际结果；不能现在通过补 JSON、挪类、换基线或替换异常类名使 EX-15a raw gate 变绿。该后续方向不是本轮产品或测试修改准入。
