# N4a 可信部署模式与旧签发器一致性实施报告

状态：DONE_WITH_CONCERNS（限定实现完成，待 root 审计和独立 fresh review；未签收 N4a/整体 IAM）。冻结输入：11891B / SHA bbed10df38458b2ea6c14b99736dcb86ffa6828615f31b0e060c09cbeab3e96d。基线 N3/I1/I2 的验收记录与 reviewer 报告未修改。最后实际验证为 final02 BUILD SUCCESS；不得将分批结果称作同次 common53 或同次73全绿。

## 1. 范围与实现不变量

唯一工程 G:/207/项目/算法重组工具/代码/agent-platform，所有 shell 均显式 workdir。未读取、导入、复用、关联或修改相邻旧工程；未启动主服务，未使用/探测数据库、真实 IAM/model/ontology/K8s。无 package、deploy、commit、push；无 HEAD/remote，git 只用于真实 no-index diff/apply/replay。未改 POM/deps、AuthorityProperties、RouteCoverageVerifier、runner、SQL/routes/config/frontend、旧阶段/报告/复审/共享八索引。唯一额外设计读取为授权设计目录中的原 v1.2 文件和八个已授权索引逐 SHA。

只改 SecurityConfiguration、ServiceTokens 两个现有生产类。公开且唯一的可信入口 SecurityConfiguration.bindDeployment(Environment) 原样复用 Boot3.5.0 Binder.get(environment)、NoUnboundElementsBindHandler、缺省 AuthorityProperties 构造及真实 dev-auth/server.address/activeProfiles validateDeployment。严格绑定/纯验证全部成功后才登记 ServiceTokens；返回的正是同一个已验证 properties。完整配置 authorityModeGuard 调用它后仍在 BeanFactoryPostProcessor 抛原 IAM_TRUST_CHAIN_UNAVAILABLE，非 IAM 仍注册原 authorityProperties singleton。未知 key/缺项/无效值错误优先级保持。

ServiceTokens 仅一个私有 deploymentMode 字符串，生命周期为定义该类的 classloader。registerDeployment/issue/private sign 使用同一个 Class monitor；issue 判断到签名位于同一锁持有期，private sign 覆盖另两条签发 API。无注册的 issue 默认拒绝，即使 JVM test + test profile；合法 dev-hmac/test 属同一 legacy 类别、允许重复合法登记，之后不逐次读取 JVM mode/profile/dev-auth。合法 IAM 登记不可逆；登记发生于完整生产配置原启动异常之前。此后三 API 拒绝，重复合法 IAM 幂等后仍原启动异常；尝试合法 legacy 启动也明确 PlatformException/AUTHORITY_CONTEXT_MISSING，不静默降级。关闭/失败的 Spring context 和 JVM 属性修改都不重开签名。

未登记时 userToken/integrationToken 保留原非 IAM 兼容签发，报告不声称未登记三 API 全禁。secretBytes 仅取 key，保持原行为。JWT issuer/claims/TTL/audience/key 来源均未改变；HS256 不进入 TrustedJwtDecoder 的 RS256 域、不作 IAM fallback。只承诺同 classloader 线性化；不撤回已签字符串、不关闭先前已运行 listener、不作跨 classloader 全局保证。测试保留登记前签出的字符串并验证可继续解析/验签。未新增生产类、registry、reset API、测试后门、context close 恢复钩子或线程竞速压测。

N4b 的真实两个 ANY gateway 例外注册与真实运行默认拒绝守卫整体未处理；没有单独 marker 令 coverage 变绿。隔离 receiver 有自己的有限 deny-all 测试 chain，可显式调用同入口登记 IAM；这不表示完整生产 IAM 已开通。

## 2. 有限影响盘点与测试隔离

首次源码编辑/Maven 之前，有限检索 common/engine 的 ServiceTokens.issue/userToken/integrationToken。实际生产 callsites：engine RuntimeClients 三处 legacy HTTP 调用和 TestInputClient 一处；两者都是完整合法 legacy Spring 部署下的兼容入口，本任务未改。CommonBoundaryTest 的正向 issue、AuthorityConfigurationTest 的合法 IAM 三位置与成功 legacy contexts、RuntimeClientsPublishedHttpTest 的 JVM-only legacy fixture 真实受影响。其它 engine userToken 测试仍在未登记非 IAM 兼容范围，不需要扩大测试选择。ServiceTicketProviderTest 原未登记负例保留 parent，源文件未改。没有重跑 common179、七模块962、N1/N2数据库用例或无变化绿色集合。

AuthorityConfigurationTest 的 complete-iam-still-closed 参数、explicitTestProfileMayUseOnlyLiteralLoopbackTrustEndpointsAndIamStaysClosed、portalCeilingsAreRequiredAndStrictlyBoundByTheDeploymentBinder，以及两个 development modes 和 gateway integration 正向都在固定具名 child main 运行；保留每条原断言、factory0、decoder audience/token_use 断言。parent 只保留真实无效配置/纯验证，不登记合法模式，不依赖 JUnit 顺序。没有逐个 fork 无效 case。

CommonBoundaryTest 原 JWT tenant/sub/audience/EDITOR/SERVICE/无 ADMIN 断言全部由自己的 validated child 执行。新 mode 场景另外用 Nimbus MACVerifier 验证签名及原 issuer、subject、tenant、audience、service use、roles、attributes、120秒TTL、jti；不是只看无异常。engine 正向移至同文件固定 main，真实 validated Environment 后访问 app/skill/model 实际 HTTP 路由，原 response/sends 断言保留并增加 unknown target 拒绝与发送数不变；其它 IAM19 例仍在 parent 执行。

固定 child classpath 仅 own common/engine classpath，没有兄弟业务 JAR/反射 target/classes；child 凭据仅继承受控环境，未自写 env/secret parser。common 45秒、engine 30秒 wait；超时 kill+5秒 reap；真实 exit0 + 固定 executed marker 必须同时满足。输出重放进原受控日志；资源 finally 关闭 server/client/executor，child PID/reaped markers 与有限 receiver 端口见 resources.json。child 成功启动本身不算通过，所有内部断言完成后才打印 executed。最终 source 不含 reset/reflection/signing 后门。

## 3. 真实红、实际命令与分批绿

受控原 runner `.tools/run-iam-module-tests.mjs` 未修改，固定 Java21 / offline own .tools/m2 / -am / test；内部完整 Maven 参数由原 runner 和 commands.json 可复核。每轮由自有 evidence wrapper 先冻全部 source，调用该原 runner，再只对真实 Running 保存 selected XML；旧 XML 只作 metadata。wrapper 不解析/转发秘密、不替代原 runner。原 Maven stdout+stderr 由原 runner 按其既定实现合并到 raw.log，命令 metadata 另存 wrapper 的 stdout/stderr/exit；没有声称重新分离已合并的 Maven 流。

| stage | exit | 实际 tests | failures | errors | skipped |
|---|---:|---:|---:|---:|---:|
| iam-stage-5-n4a-red01 | 1 | 1 | 1 | 0 | 0 |
| iam-stage-5-n4a-final01 | 1 | 53 | 5 | 0 | 0 |
| iam-stage-5-n4a-final02 | 0 | 25 | 0 | 0 | 0 |

red01：生产字节完全原基线；新增测试已编译执行。JVM 显式 mode=test/profile=test，实际高优先级 MapPropertySource addFirst IAM 覆盖 systemProperties（不是 setDefaultProperties 伪覆盖）。完整原配置仍抛 IAM_TRUST_CHAIN_UNAVAILABLE，factory=0；捕获失败后 ServiceTokens.issue 仍实际签名，期望 PlatformException 的断言失败，child exit1，parent failure1/error0。不是缺 API/编译错误，原失败源/stack/raw/XML 均冻结。final01 同反例已绿，且覆盖重复 IAM/三 API/改 JVM dev/合法 legacy 重启仍拒绝。

final01 真实 common53=48pass/5fail，engine 未执行。实际失败五例：

- `cn.agentplatform.common.authority.AuthorityConfigurationTest#explicitGatewayDevPreservesIntegration`
- `cn.agentplatform.common.authority.AuthorityConfigurationTest#explicitDevelopmentModesKeepLegacyDecoder(String)[1]`
- `cn.agentplatform.common.authority.AuthorityConfigurationTest#explicitDevelopmentModesKeepLegacyDecoder(String)[2]`
- `cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest#actualLegacySnapshotSurvivesJvmMutationsAndContextClose`
- `cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest#isolatedReceiverExplicitlyRegistersIamWithoutOpeningProductionChain`

其中四例为 child 未继承原受控 JVM 的短 Unix-domain temp 参数，Tomcat NIO PipeImpl/UnixDomainSockets 抛 Invalid argument: connect；第五例是隔离 receiver fixture 的 BFPP bean 与 snapshot singleton 重名。仅修两个 child launcher 加原已批准 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp，并把 snapshot singleton 改为 validatedReceiverDeployment；生产两文件没有再变，断言未弱化。final01-to-final02-fixture.patch 保存真实小差异，source manifest 逐 SHA 证明。没有改 POM/argLine/runner/系统 tmp，不因噪声关闭校验。

final02 只续 AuthorityConfigurationTest#explicitGatewayDevPreservesIntegration（1）、#explicitDevelopmentModesKeepLegacyDecoder（dev-hmac/test 两参数）、新 mode 的 legacySnapshot 与 isolatedReceiver（2），及尚未运行的 RuntimeClientsPublishedHttpTest20。exact 方法选择可表达上述五失败 case，没有重放已绿48。最终不同用例73（common53+engine20）均有通过证据，但分属 final01保留48与final02的25；不能按各轮累加成测试总数。current-covered-tests.json 含每一实际 testcase 的有效 stage；全测试方法名、Running、XML/源/raw SHA 见 test-audit.json。

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-n4a-red01 admin-common ServiceTokensDeploymentModeTest
```

实际工作目录：`G:/207/项目/算法重组工具/代码/agent-platform`。exit=1；Running=cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest；XML totals={"tests":1,"failures":1,"errors":0,"skipped":0}。原始输出：`docs/evidence/iam-stage-5-n4a-deployment-mode/iam-stage-5-n4a-red01/raw.log`（SHA a5df87ccac2381b3ceb3f8acb5655a09050b36164e2acd1dc8f41d718df2c8f8）。

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-n4a-final01 engine-runtime AuthorityConfigurationTest,CommonBoundaryTest,ServiceTicketProviderTest,ServiceTokensDeploymentModeTest,RuntimeClientsPublishedHttpTest
```

实际工作目录：`G:/207/项目/算法重组工具/代码/agent-platform`。exit=1；Running=cn.agentplatform.common.authority.AuthorityConfigurationTest, cn.agentplatform.common.authority.ServiceTicketProviderTest, cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest, cn.agentplatform.common.CommonBoundaryTest；XML totals={"tests":53,"failures":5,"errors":0,"skipped":0}。原始输出：`docs/evidence/iam-stage-5-n4a-deployment-mode/iam-stage-5-n4a-final01/raw.log`（SHA f87a1b0b1697c8eeb61e38b80e61ddea09b10c42696fb3c0942001aba63935b2）。

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-n4a-final02 engine-runtime AuthorityConfigurationTest#explicitGatewayDevPreservesIntegration,AuthorityConfigurationTest#explicitDevelopmentModesKeepLegacyDecoder,ServiceTokensDeploymentModeTest#actualLegacySnapshotSurvivesJvmMutationsAndContextClose,ServiceTokensDeploymentModeTest#isolatedReceiverExplicitlyRegistersIamWithoutOpeningProductionChain,RuntimeClientsPublishedHttpTest
```

实际工作目录：`G:/207/项目/算法重组工具/代码/agent-platform`。exit=0；Running=cn.agentplatform.common.authority.AuthorityConfigurationTest, cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest, cn.agentplatform.engine.infra.RuntimeClientsPublishedHttpTest；XML totals={"tests":25,"failures":0,"errors":0,"skipped":0}。原始输出：`docs/evidence/iam-stage-5-n4a-deployment-mode/iam-stage-5-n4a-final02/raw.log`（SHA 1eea27600f6b8cc30693e00bc115ade5614a726f7c0d07fcfcc05696057e4ea8）。


具名新 mode 覆盖：unregisteredJvmTestCannotIssue；actualLegacySnapshotSurvivesJvmMutationsAndContextClose；actualIamOverridesJvmTestAndClosesAllLegacySignersBeforeListener；legacyThenIamClosesSigningAcrossThreads；invalidDeploymentDoesNotRegisterPermission；isolatedReceiverExplicitlyRegistersIamWithoutOpeningProductionChain。跨线程用确定性 CountDownLatch 与有限 Future.get，不做 CPU/竞速测试；登记后同线程与另一线程三 API 全拒绝。无效配置集中调用同严格入口，缺项/typo/预算/算法/loopback/dev-auth/test-profile 等原原因保持、issue仍拒绝。隔离 receiver 真实 HTTP403 后关闭，再验证完整原生产配置仍 factory0/IAM_TRUST_CHAIN_UNAVAILABLE。

## 4. 完整 source/target、补丁和制品归因

首次编辑/Maven 前：source367 与 target1798 共2165个完整物理副本，逐 SHA 对照固定 N3 after manifests；没有用 HEAD 伪造基线。before.json/source-before/target-before 记录原证据摘要，own before/ 及 local before/ 可复核。最终 source368（新增1、修改5、删除0），target1806（新增8、修改23、删除0）。完整 source/target 前后 manifest、各路径 before/after SHA、归因分别见 source-changes.json/target-changes.json。

| 路径 | 变更 | 归因 | after SHA256 |
|---|---|---|---|
| `admin-common/src/test/java/cn/agentplatform/common/authority/ServiceTokensDeploymentModeTest.java` | 新增 | Fixed child scenarios, precedence red/green, snapshot, invalid binding, lifecycle/barrier and isolated receiver. | `ba473cebdf2804c30aac230ba864a6ff7ac69680af55463ba4370b0ee9622c9f` |
| `admin-common/src/main/java/cn/agentplatform/common/SecurityConfiguration.java` | 修改 | Extract the one strict validated Environment binding/registration entry; retain complete IAM prelistener closure. | `f864681861e0d777b2b51489767e60408b6a6fa6307cfa0c1b4120b19ad9ff7a` |
| `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java` | 修改 | Classloader lifetime deployment snapshot; common irreversible IAM signing closure, one monitor covers registration/issue/sign. | `0ecf4aac5652e01d7c7e7e208720ebbc6345fb26d2c532e6c110fdd4dcb6ec05` |
| `admin-common/src/test/java/cn/agentplatform/common/CommonBoundaryTest.java` | 修改 | Move existing positive JWT claims assertions to validated isolated child. | `0ce53038b4743bb513ab9e8fc2a53f4c614e3f0f504b4fccbc459ac8aa4a81af` |
| `admin-common/src/test/java/cn/agentplatform/common/authority/AuthorityConfigurationTest.java` | 修改 | Isolate all legitimate IAM and successful legacy contexts without weakening original assertions; parent invalid cases stay unregistered. | `19037e462a9e580b684e0644f5880ca7e5da9dcedd3e72917cef08cb605729bf` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RuntimeClientsPublishedHttpTest.java` | 修改 | Only JVM-only legacy positive fixture moves to validated child; parent IAM19 retained, unknown target asserted. | `b3c6e965c6fe1fa783a7a241fdf52b28038eda56101bff02a67d5adc61928dc7` |

target 全31个变化逐路径如下（无删除；Surefire 原诊断保留）：

| 变更 | 路径 | 归因 | after SHA256 |
|---|---|---|---|
| added | `admin-common/target/surefire-reports/2026-10-21T00-00-39_805.dumpstream` | Surefire diagnostic | `b8dffea3d5532b66cd4d159b508c1d407937e1f1b072630b4ee6355e521731c1` |
| added | `admin-common/target/surefire-reports/2026-10-21T00-04-15_357.dumpstream` | Surefire diagnostic | `93b562763f422bcffa13d67b7d997810bdb0e655d60afb5634d0fa0fbf056b9f` |
| added | `admin-common/target/surefire-reports/2026-10-21T00-05-39_201.dumpstream` | Surefire diagnostic | `7780f88e8807fade253625fa8c3e52e9b7cbd952041754df9797102a10f6929d` |
| added | `admin-common/target/surefire-reports/TEST-cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest.xml` | Surefire report | `0fd1d71fd200dd7833bb298005c6476f718afa4be366be60bc2de6de3caa3689` |
| added | `admin-common/target/surefire-reports/cn.agentplatform.common.authority.ServiceTokensDeploymentModeTest.txt` | Surefire report | `8e972525608cd954cacc01140c3525b05d34ab636f2cce1998751ef47cb7a990` |
| added | `admin-common/target/test-classes/cn/agentplatform/common/authority/ServiceTokensDeploymentModeTest$Receiver.class` | compiled test fixture | `4e3f0f98358688feb738068410ed8f184ed7dbea16817355852bfe1dd8f0a6c4` |
| added | `admin-common/target/test-classes/cn/agentplatform/common/authority/ServiceTokensDeploymentModeTest.class` | compiled test fixture | `bc39fb04c89d3c43a8262795ff41970a1e8ee7eae64bb251c3cd989d5a2fd320` |
| added | `engine-runtime/target/surefire-reports/2026-10-21T00-05-39_201.dumpstream` | Surefire diagnostic | `752380d2aa358333f7d1f980ed1a92df3e9d2b5f86e538f3e9bf8b002b7af8b9` |
| modified | `admin-common/target/admin-common-0.1.0-SNAPSHOT-tests.jar` | test-fixture JAR | `c6ccefcd5cade5ff11caba1719aeec11039af66ad9e6521b732c41fb8cb45ba7` |
| modified | `admin-common/target/classes/cn/agentplatform/common/SecurityConfiguration$1.class` | compiled production class | `c178e9be9eb2740f1c28219450bae33b016990160acc6cb45f3c60ca12fe3db3` |
| modified | `admin-common/target/classes/cn/agentplatform/common/SecurityConfiguration$2.class` | compiled production class | `88f9867189a52aefe7d3a205a834af1c9d3082eb76856f7d4c7555ef8676e911` |
| modified | `admin-common/target/classes/cn/agentplatform/common/SecurityConfiguration$3.class` | compiled production class | `1784c6a2211a0dc2a7078794f91005bf18bcb5823d268c6a4959a53a90d78e14` |
| modified | `admin-common/target/classes/cn/agentplatform/common/SecurityConfiguration.class` | compiled production class | `00e887b78e4ad0daefbbaeae2b9f0e4592ec084cde92148cee02f6e82fbe863d` |
| modified | `admin-common/target/classes/cn/agentplatform/common/ServiceTokens.class` | compiled production class | `e0740d1ee3e321fa30696732ec433307a5a2aa997bb0245e07265ac63001ceb5` |
| modified | `admin-common/target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/createdFiles.lst` | compiler metadata | `9173be3bcb29f216ff26df13a4abd5c96b8d26151d1e826a5c9eb777b7ac2dcc` |
| modified | `admin-common/target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst` | compiler metadata | `3a2882b1edab36b98589611f4614a494471a041311603688fdac2c63810c4d3d` |
| modified | `admin-common/target/surefire-reports/TEST-cn.agentplatform.common.CommonBoundaryTest.xml` | Surefire report | `fcaedd417a8000bce2b393367d2e64dbc222d2f41228e0028b5f0627f2137b47` |
| modified | `admin-common/target/surefire-reports/TEST-cn.agentplatform.common.authority.AuthorityConfigurationTest.xml` | Surefire report | `56d109a233ca246f85cf340f53661496bfb2a124ce031ba521b1e8b5c6794f52` |
| modified | `admin-common/target/surefire-reports/TEST-cn.agentplatform.common.authority.ServiceTicketProviderTest.xml` | Surefire report | `7dd22c105998343b3952034b52f85fddf5f8b61a8e35e9a2857d7b97da7ab597` |
| modified | `admin-common/target/surefire-reports/cn.agentplatform.common.CommonBoundaryTest.txt` | Surefire report | `8bd150d99be0b478659953c078f5a591e8cfd5eb5e5973c4c52bb02236e751d3` |
| modified | `admin-common/target/surefire-reports/cn.agentplatform.common.authority.AuthorityConfigurationTest.txt` | Surefire report | `6bee2df7303c796cca0e2d57b0e64011c40f5527a3991ccd77f85e253e2cfd6a` |
| modified | `admin-common/target/surefire-reports/cn.agentplatform.common.authority.ServiceTicketProviderTest.txt` | Surefire report | `02df0b121f3b0fe287d00c7a22b36f32ed36f6194df3e2ca50bc6eebc74ec69f` |
| modified | `admin-common/target/test-classes/cn/agentplatform/common/CommonBoundaryTest.class` | compiled test fixture | `17abe2c9054f5077c1d2a6d1adefaf5ddd38612b7556705c43d998d4e6f58c3d` |
| modified | `admin-common/target/test-classes/cn/agentplatform/common/authority/AuthorityConfigurationTest$1.class` | compiled test fixture | `ac1998c703b0cff066b571112ac5720099593a2a9d4e0ffcfef049a7dd695843` |
| modified | `admin-common/target/test-classes/cn/agentplatform/common/authority/AuthorityConfigurationTest$Invalid.class` | compiled test fixture | `13a11120f3c9bc4b170cafac28478f1b40371527f5cc750ddb4bc5ddde77cef2` |
| modified | `admin-common/target/test-classes/cn/agentplatform/common/authority/AuthorityConfigurationTest$TestApp.class` | compiled test fixture | `d3d47a5cf4149c77c95717b1c260c1b50edc276bbf7f750c33f96a6e99cf880f` |
| modified | `admin-common/target/test-classes/cn/agentplatform/common/authority/AuthorityConfigurationTest.class` | compiled test fixture | `4d08d7dfdef9669b2d1718034bc66ef9992f3fdbdeda9e4bb4b8ba3a79f04112` |
| modified | `engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.infra.RuntimeClientsPublishedHttpTest.xml` | Surefire report | `8e55344cf10ece4326ebf226adad00e9c31f0ff3011a4ffd94a74f622ae7d75a` |
| modified | `engine-runtime/target/surefire-reports/cn.agentplatform.engine.infra.RuntimeClientsPublishedHttpTest.txt` | Surefire report | `4abee2e8d4a032e215d7a02c08e940a2aa512027ac78cbc7de2ef3dd9f1f0873` |
| modified | `engine-runtime/target/test-classes/cn/agentplatform/engine/infra/RuntimeClientsPublishedHttpTest$1.class` | compiled test fixture | `08b43cb8281d0f2180088b6865cd2f23949fdae9ad4bcc35ba6c54a86ab357e4` |
| modified | `engine-runtime/target/test-classes/cn/agentplatform/engine/infra/RuntimeClientsPublishedHttpTest.class` | compiled test fixture | `1919b0bcdbf1147b26556aefffa15e445ecfb22a8351ee972d5a52f21af86beb` |

SecurityConfiguration 的匿名内部类编译变化是外层新增方法/调试行号重编译结果，不代表新增生产源码；测试内部类同理。唯一变化 JAR 是 admin-common-0.1.0-SNAPSHOT-tests.jar，224108B/5e879609d0cbf78cc20a88ac76bbc0436716f82ace037aa1e97198f53b6f2f48 → 236449B/c6ccefcd5cade5ff11caba1719aeec11039af66ad9e6521b732c41fb8cb45ba7，为 -am test 的既有 process-test-classes 插件重建测试 fixture JAR；生产 JAR变化0。未因 JAR0 口号漏列 fixture。诊断/编译 metadata 也纳入完整31路径，不省略。

change.patch 为真实 git diff --no-index --binary --no-renames --unified=100000 完整上下文，139085B/SHA f59e65cb3ed40a367244c480687b4799a99c7b980fadc36a954fe14aa88e6b8f。真实 git apply --check exit0、git apply exit0；replay全部368 source逐字节与after相同，包括新文件，.gitattributes * -text 保留真实换行。未假定 sources.jar 的根文件路径：Boot 源码按准确 descriptor entry 读取，basename只用于输出文件名。git-boundary.json 记录仓库无 HEAD、无 remote/commit，未使用它们造 diff。

## 5. 固定来源、许可与实际阅读

复用前先核原 source-sha256.json 与 Spring 源 archive/file SHA，再窄读实际方法；新官方来源带版本、URL、许可和 SHA。无递归缓存/SDK搜索、无升级依赖。完整文件和 reading-manifest.json 在 own upstream/。

- Spring Context 6.2.7 / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/AbstractApplicationContext.java`；Apache-2.0；实际阅读 refresh 591-659: factory processors before onRefresh, exception teardown；SHA `074cd3029bccb96e3193bc18024ee47ddbe331743d9297965b345756f8c29dcf`；来源 [Spring Context 6.2.7](https://repo.maven.apache.org/maven2/org/springframework/spring-context/6.2.7/spring-context-6.2.7-sources.jar)。
- Spring Boot 3.5.0 / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/ServletWebServerApplicationContext.java`；Apache-2.0；实际阅读 onRefresh/createWebServer/doClose 163-201；SHA `0c88140c8651f4d2218d87eb6786849a64e437f58b1297ec8fffcb85f73395e5`；来源 [Spring Boot 3.5.0](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0-sources.jar)。
- Spring Boot 3.5.0 / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/NoUnboundElementsBindHandler.java`；Apache-2.0；实际阅读 onFailure/onFinish/checkNoUnboundElements/collectUnbound/isUnbound 75-128；SHA `2a177ab504b13a6b630f1a8996fadc7f54df659b4d4e9c8363d91f5351e347a9`；来源 [Spring Boot 3.5.0](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0-sources.jar)。
- Spring Boot 3.5.0 / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/Boot-Binder.java`；Apache-2.0；实际阅读 bind(String, Bindable, BindHandler) 251-263; get(Environment) 526-548；SHA `83e5be4b7073abf2c8d6e70a617b3383958f3c43b580dc1708ea6ef3e877fd26`；来源 [Spring Boot 3.5.0](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0-sources.jar)。
- Spring Boot 3.5.0 / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/Boot-LICENSE.txt`；Apache-2.0；实际阅读 license header；SHA `7200000367e0e8b47822d563a5ddd2dd3a97cc05b32bd7dbcc7b4eeb92b76628`；来源 [Spring Boot 3.5.0](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0-sources.jar)。
- Spring Boot 3.5.0 / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/Boot-NOTICE.txt`；Apache-2.0；实际阅读 retained notice；SHA `e31f9c3e808edca4fbcbba9652cfe729fd985e8a8be145836bf400a01908acd8`；来源 [Spring Boot 3.5.0](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot/3.5.0/spring-boot-3.5.0-sources.jar)。
- OpenJDK jdk-21+35 source reference / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/Jdk21-Thread.java`；GPL-2.0-only WITH Classpath-exception-2.0 (header retained)；实际阅读 header 1-24; lifecycle 67-80; start synchronization 1514-1544; bounded join(long) 2032-2084；SHA `23a38e9d8deba442c845137d46d6fd8fa74c507e364a697934827b0c783da85e`；来源 [OpenJDK jdk-21+35 source reference](https://raw.githubusercontent.com/openjdk/jdk/jdk-21%2B35/src/java.base/share/classes/java/lang/Thread.java)。
- Java SE 21 language specification / `docs/evidence/iam-stage-5-n4a-deployment-mode/upstream/JLS21-ch17.html`；Oracle specification documentation reference snapshot; not relicensed as open source；实际阅读 17.1 monitor/static synchronization; 17.4.5 monitor unlock/lock and start/join ordering (HTML 1240-1266)；SHA `5352da1f94167b6db4c194a51e11e3d1d511f0292f62a44c56f7a92280bae9f1`；来源 [Java SE 21 language specification](https://docs.oracle.com/javase/specs/jls/se21/html/jls-17.html)。

根据 Context6.2.7 refresh 的 BFPP→onRefresh 顺序与 Boot3.5.0 onRefresh/createWebServer，将 IAM 登记与原拒绝都放在 listener factory 调用前。Binder/NoUnbound 的 Environment 来源与 onFinish 严格检查继续存在。JLS21 §17.1/§17.4.5 的同 monitor 互斥/可见性支持本实现的 classloader 线性化；这是本工程推导，不把源码许可证包装成安全保证。JDK21+35 仅来源参考，不声称等于实际 runner Temurin21.0.12+8。源内注释解释状态生命周期、错误优先级、同步边界和已签字符串/旧 listener 局限。

## 6. 保护、工具失败与残余限制

复用 root 已核101048保护静态基底，按 brief 只精确更新 review18890B前缀保持后的30396B/c71a48ed... 与8索引明确 before→after SHA；其余旧证据不泛化放行。before authorizedUpdates=6（静态集合中的review与5个内部索引；另3个外部索引在独立indexes集合），全部8项基底before→approvedAfter→actualAfter逐SHA关系另存 authorized-prior-transitions.json，新增静态8041逐SHA补采，共109089。原 review18890前缀SHA d971492e... 单独核验。28受控 runners和最新8索引各自逐SHA；原v1.2文件正确物理位置与3c72b206...校验见 design-reference.json。

一次完整物理 protected-after：109089文件/8718696454B，2026-10-20T16:10:21.705Z → 2026-10-20T16:14:38.375Z；未授权变更0/新增0，28runner变化0、8index变化0、全部2174 source+target frozen差异0/额外路径0/缺失0。protected-after.json/protected-changes.json/protection-summary.json 可独立复算。精确排除六runtime目录、postgres.log、root的单一current-work-state、自有local/evidence/report，以及本三轮精确log/fresh-secret目录；没有排整个.local或宽stem。

原 WARN/Manifest-JAR Windows different root 诊断保留：三轮共4个新dumpstream，均344B；原文提示 disableClassPathURLCheck，但本任务没有采用提示、没有静默禁检。最终构建 exit0，但不能称 pristine/no-WARN。

- iam-stage-5-n4a-red01：1 条 WARN/已知诊断匹配行；67 个旧 XML 仅作 metadata，未计运行。
- iam-stage-5-n4a-final01：1 条 WARN/已知诊断匹配行；64 个旧 XML 仅作 metadata，未计运行。
- iam-stage-5-n4a-final02：2 条 WARN/已知诊断匹配行；65 个旧 XML 仅作 metadata，未计运行。

工具/setup失败完整说明在 tool-noise.md：首次读输出截断后窄读；CRLF 本地编辑脚本定位断言失败于任何生产写之前，仅续编辑；final01五fixture失败如上；XML evidence正则原贪婪匹配计39/40触发断言，完整patch/replay/source/target已冻结后只续未完成证据解析并核每类case数，没有重跑测试或重写before；提前读取尚未落盘runner日志/原设计简写路径的只读ENOENT亦保留。before捕获只运行一次、physical after保护只运行一次。

另有一次自动策略拒绝：动作是 PowerShell here-string 写入 .local/iam-n4a-deployment-mode/upstream-evidence.mjs 并拟随后运行 node，实际工具返回 exec_command failed: CreateProcess Rejected(... rejected: blocked by policy)。明确 stated reason 仅“blocked by policy”，未提供更细规则；不自行猜测成业务/权限/环境缺失。该动作没有执行/没有写文件；后用 apply_patch 安全创建该局部证据脚本，单独 node 执行成功。automatic-policy-rejection.json 记录精确动作、目标、实际原因及替代执行；工具原显示包含命令并被截断，未声称保留未经截断的完整拒绝payload；原样原因“rejected: blocked by policy”及其它工具错误摘录另存tool-error-excerpts.txt。无明文凭据、无权限升级。

当前仅独立软件/隔离测试完成；完整生产 IAM 仍原闭锁，真实 IAM/model/ontology/K8s 仍未验收。N4b、共享索引更新、root审计与独立 fresh review 留 root。未自签收 N4a/整个IAM。报告与 machine handoff 写完并最终逐SHA检查后归还全部 source/target 所有权；此后实现者不再写工程。
