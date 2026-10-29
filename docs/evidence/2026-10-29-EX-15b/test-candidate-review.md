# EX-15b 首轮两例与采集器独立静审

2026-10-29；审阅者 `algorithm_interrupt_readonly`。只读候选、既有产品/测试签名、原算法进程契约及Git元数据，唯一新增为本报告。没有编译或运行Java/Maven、Node、API、PG、HTTP、服务或候选入口，没有读取真实`.env`或密钥，没有修改产品、测试、index、三主入口，未使用Superpower。

**结论：无静态阻断，准入root唯一窗口执行一次该两例首轮定向红测。** 这是测试/采集入口准入，不是编译通过、已取得实红或files功能通过；生产改动仍须另冻结协议增补、准确范围和后续用例。

## 冻结身份

观察HEAD为EX-15a提交后的`1cc453388950ec90eba51a1484994208c78d67fd`。以下当前真实字节SHA均已独立核对：

| 文件 | SHA256 |
| --- | --- |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessFilesTest.java` | `7b363256f026266c5a65d4c85e2beb79d4af3daddc497ac1831e30834868a7d7` |
| [test-candidate.md](test-candidate.md) | `bfa68529abaa109b31889bbde827e30a7ceeb651a320f38144bdc766e551aee2` |
| [run-path-red.ps1](run-path-red.ps1) | `cf4850bcd43ee7de5b52d07210b4664eaa86ae6b653083ac3f0a7c96b8232830` |
| `ProcessContract.java` | `72699070d4a722bc7a3ffb7edeeb3d1cff13d51f825c133abc7e1cfb645252d9` |
| `ProcessAdapter.java` | `6898320e164be2a254dcf009cfc24ce00be0aa9b63954ffd7b6f9ba26d58a293` |
| `ProcessProtocolTest.java` | `77495d77d4ca372ee536db08278351a04015e36f3f2f1f2cf3cb11ed7dfc9b28` |
| `ProcessAdapterHardeningTest.java` | `0de036bfccb3f5e061d5491315920cf55e8f218f95430a324c0c2e42d764c064` |

原四输入与preparation一致，尚未改产品来制造或消除红。本复核对采集器另作`Parser.ParseFile`静态解析0错误；root已报告Windows PS5静态解析0，本复核没有自行执行采集器。测试恰有下列两个@Test，没有环境条件/Assume/skip。

## 可编译性与真实场景

静态检查未见未交付API、签名或Java21语法阻断：使用实际public七参数ProcessAdapter构造、现有Request/Output/execute/cancel/inFlight、ProcessContract.parse/PROTOCOL和ProcessTree.killTree(ProcessHandle)。父POM固定Java21/UTF-8，JUnit Test/Timeout/TempDir已有依赖。future任务所捕获变量为有效final，异常解包保留实际RuntimeException。**这些只支持准入实际编译，不替代编译结果。**

| 方法 | 实际被测链与严格停点 |
| --- | --- |
| `acceptsValidEnvelopeWithoutOutputFiles` | 从正式ProcessAdapter配置构造，经真实ProcessBuilder/管道/stdin EOF/strict JSON成功解析。worker回显实际stdin，父端核exit0、marker、协议algorithm-process/1、输入files是空对象、输出files是空数组。它同时检验本轮真实JDK/嵌套Worker classpath/握手/协议前提，不能用虚构Output常量替代。 |
| `rejectsParentRelativeOutputPathBeforeReturningSuccess` | 相同链，仅stdout files变为`path=../escape.bin`、name、contentType、bytes=0和空内容正确SHA；仍是单一成功协议JSON。严格要求PlatformException及502/PROCESS_OUTPUT_INVALID。当前success只arrayOrEmpty复制files，未逐项校验path，故预测为assertThrows失败，不能凭编译错误/启动失败认作该缺陷的红。 |

原方案契约§4.5确实要求工作目录内相对path、禁止`..`/绝对路径/符号链接/junction，并复核bytes/SHA；现测试的path字段与原语义相符，没有新造一个产品字段。worker不实际创建或打开escape.bin，非法字符串不交给任何文件API；因此是在安全地实测“非法声明被成功返回”的现有缺口。

输出worker只用JDK标准库；argv为当前真实java绝对路径、64MiB堆、当前测试编译classes目录和嵌套Worker，不经shell、不借用外部业务worker/公共假执行服务。父端校classpath为目录；子端接收至多1MiB+1并要求stdin EOF后才输出，回显父端随机requestId，stdout只有一个合法JSON，没有日志/banner混入。新类位于现有测试目录，无新依赖或运行服务。

## Windows所有权、有限等待与finally

- 每方法独立@TempDir，其下新建中文/空格允许根；adapter独占且全局/tenant额度各1、queueWait=0、keepFailedDirs=0。白名单只含真实JDK可执行文件，沿ProcessWhitelist对绝对路径的真实归一化/Windows不区分大小写机制，不用空白名单绕过正式构造约束。
- Worker的identity.tmp→identity及父端release.tmp→release全部在本次adapter创建的callDir内，先完整写入再重命名。父端只枚举自己新根，拒绝符号链接目录/identity；校requestId、实际ProcessHandle启动时间、直接parent=测试JVM、真实java路径及callDir的real parent=本根后才登记own worker。release内容必须精确requestId。没有按进程名、旧PID或主机目录查杀。
- worker只写上述控制文件，不起子孙、不联网、不连PG/MinIO，不访问声明中的恶意路径。ProcessContract缺省env为空映射，实际ProcessTree.builder会清空宿主env再注入最小Windows必需变量，不把AGENT密钥或整份父环境传给worker。
- 原有限等待分别为JUnit60秒、process contract20秒、父握手15秒、子release10秒、future25秒和caller finally10秒，没有扩预算。JUnit注解及既有同步OS调用不冒称严格端到端硬时限；root仍须监督原采集窗口及Maven兜底。
- finally先cancel此私有adapter的本次requestId；只对已登记且同startInstant仍存活的确切handle调用既有ProcessTree，再取消future、shutdown/await自己的caller。严格检查caller退出、inFlight=false、同一worker退出及允许根条目0。尚未登记PID不擅自遍历其它进程，startup异常仍由本次adapter注册表/中断finally处理。若这些后置失败则留实况并停，不能把它覆盖成预期路径红。
- callDir回收仍由现有ProcessAdapter自己的finally完成；测试不为了绿直接删目录或伪造零条目。已有ProcessTree/taskkill机制保持只读，没有新造监督器或扩大到EX-16生产取消接线。

## 小采集器准入

入口只限`-pl skill-registry -Dtest=ProcessFilesTest`，无-am、clean、install、skip或failure.ignore。它先锁测试7B363及原Adapter689832，再保存admin-common/skill全src及三POM指纹、HEAD、原common JAR/`.env`摘要和JDK二进制摘要；**只计算`.env`摘要，不导入其值**，这两例不需要PG、MinIO、HTTP或Spring服务。拒绝继承四种JVM/Maven选项；设置的JAVA_HOME/PATH在finally恢复，工作目录亦恢复。root需按既定前置确认JDK21和V:别名/临时根属于本仓。

原生Maven退出码在任何XML解析前保存。只接受开始后新鲜的本类XML，按XmlDocument.Load读取真实UTF-8字节，原XML另留本卡新RunId；两方法身份精确相等，必须控制例PASS、路径例FAIL且0ERROR/0SKIP、native1。路径FAIL还必须精确为AssertionFailedError及`Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.`；普通异常、握手/清理失败、编译错误、坏classpath或超时均不能被当成这一行为红。

只有上述原停点吻合并且源/HEAD/common/环境摘要前后稳定，collector才返回0表示“预期实红采集完成”。这不是产品绿；实际native1/一项FAIL必须保留。其它情况保存collection-error/collector-exit并停，不改source或重复抽样。root实际执行后仍须读完整原断言/资源后置是否到达，再登记台账，不从本静审倒填预测2=1P1F为实测。

## 本轮不能推出的结论

首红只钉住现有execute对非法files声明应拒绝。它没有读取根外哨兵，也没有证明未来实现必定在任何文件打开前拒绝：例如未来错误地先尝试打开缺失文件而返回同码，仍需静态检查和额外精确测试辨别，不能仅凭此两例绿证明path-before-open。完整输入授权artifact映射、真实输出持久化、bytes/SHA/数量预算、symlink/junction/TOCTOU、父先退后代仍写、真正重启及失败精确补偿均未覆盖，仍按preparation/协议增补另冻结后执行。X-1真实业务算法worker和EX-16取消生产接线不在本次准入。

本报告完成后停写。准入仅当前冻结两例及CF485采集器的一次实际首轮；任何source/期望/范围变更先保留原因并重新给出最小准确差异，不以放宽原断言追绿。
