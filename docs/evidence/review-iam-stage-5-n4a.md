# N4a 可信部署模式独立 Spec / Task Quality 审查

结论：**Spec compliant；Task Quality Approved。Critical 0 / Important 0 / Minor 0。** 本结论仅覆盖 N4a 的可信部署模式登记和旧签发器一致性，不等同整个 Task 5、全局 IAM、N4b 或生产部署验收。完整生产 IAM 仍应以 `IAM_TRUST_CHAIN_UNAVAILABLE` 在监听器创建前关闭。

## 审查输入与实际范围

工作目录始终为 `G:/207/项目/算法重组工具/代码/agent-platform`。本 reviewer 只做静态代码、证据阅读与摘要核验，没有运行构建、测试、服务、数据库或端点探测，没有修改 source、test、target、依赖、配置、runner、共享索引和旧报告；唯一输出为本新报告。未访问相邻旧工程，也未使用不存在的 HEAD 或 remote 制造空 diff。

2026-10-21（Asia/Shanghai）实际逐个核验以下冻结输入的文件大小和 SHA256，全部匹配交接值：

| 输入 | 字节 | SHA256 |
| --- | ---: | --- |
| `docs/evidence/iam-stage-5-n4a-deployment-mode-brief.md` | 11891 | `bbed10df38458b2ea6c14b99736dcb86ffa6828615f31b0e060c09cbeab3e96d` |
| `docs/evidence/iam-stage-5-n4a-deployment-mode-report.md` | 29800 | `a8ca2b197831a3f04e6739cdacf253e580de50521fd09a886a82b974f0a19812` |
| `docs/evidence/iam-stage-5-n4a-deployment-mode/handoff.json` | 28740 | `7948381976938abbb7d85d7b34192c82dc7883b3a5b6ddd2ad141923abc3756e` |
| `docs/evidence/review-iam-stage-5-n4a-package.diff` | 140271 | `cb22977fd2ca86abe1d868b21c7b37fcbae8ec6a1a77e3961f72fcc0aa91f954` |

先完整读取正式 brief、作者 report 和 handoff，再完整阅读 root package。package 按不超过 250 行连续分段读完 1–2627 行，没有内容截断或跳段；root 的 2628 是按 LF split 含最后空项的计数，二者无文件差异。package 含两生产类和四测试文件的完整前后上下文，包括新增 mode 测试。没有把作者文字结论当成代码事实。

另读本工程 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`，以及 IAM root-decisions 和 plan 的相关约束；没有声称重读全部 v1.2 正文或整个 IAM 实现。必要的未改代码补充限于 `AuthorityProperties.java:25–99` 的纯验证逻辑和 `ServiceTicketProviderTest.java:9–21` 的未登记负例。证据补充包括 root FAST JSON、最终 protected-check JSON、fixture delta、resources、tool-noise、固定来源 reading-manifest，以及三轮 raw 中实际 Running、统计和 child marker 的定向读取。

采用 code-review、ponytail、verification-before-completion 技能；本任务明确禁止 reviewer 重跑行为，因此“通过证据”均指保存的实际运行及 root 的独立核验，绝不表示 reviewer 新执行了一轮测试。未派生子智能体。

## 六个审查跨度与处置

| 跨度 | 实际核对与处置 |
| --- | --- |
| 全局约束与边界 | package 的生产差异仅 `SecurityConfiguration`、`ServiceTokens`，无新增生产类、通用 registry、配置开关或 reset。固定技术版本、原 v1.2 与四契约没有在该差异中变更。root FAST 给出的完整 source 清单为 +1/~5/-0；root 最终 protected-check 对109089静态文件、28 runners和8 indexes核验通过。接受本次限定范围。 |
| Common 可信接缝 | `SecurityConfiguration.java:44–67` 保持 BFPP 时序，唯一公开 `bindDeployment(Environment)` 先严格 Binder/NoUnbound，再纯 `validateDeployment`，最后登记并返回同一个 properties。`ServiceTokens.java:17–31,63–70` 使用单一 class monitor；未登记 issue 拒绝、legacy 类别许可冻结、合法 IAM 关闭共同 sign 且不能回退。符合 brief。 |
| 业务 legacy 与隔离 | `CommonBoundaryTest.java:55–78` 保留原 JWT 断言；`RuntimeClientsPublishedHttpTest.java:234–272` 将唯一受影响正向 fixture 移至自身 child，真实绑定后仍访问 app/skill/model HTTP 并核 response、sends=3，追加未知目标拒绝且 sends 不增加。其余 19 例和原 IAM 行为保留，生产客户端未改。符合影响范围。 |
| Gateway / 全量 IAM 范围 | `SecurityConfiguration.java:50` 仍抛原 IAM 启动异常。原 gateway legacy integration decoder 测试迁入 child 后断言保持。新 receiver 只有测试内 deny-all chain，不代表生产 IAM 开放；RouteCoverageVerifier 与两个 ANY 例外没有被局部 marker 绕过，N4b 的真实运行守卫和两项注册继续整体留后续。不得据此宣称全量 IAM 完成。 |
| 行为证据、制品与噪声 | 真实红为 1/1F/0E；final01 为 common53/5F/0E，engine 未运行；final02 为五个失败 common case 加首次 engine20，即25/0F/0E/0skip。有效73是保留48+续跑25，非同轮73也非重复48。fixture-only 小补丁、tests.jar 重建及原诊断有明确归因；root FAST 已给出物理与逐 SHA 核对结果，完整保护审计不在本报告冒签。 |
| 来源机制、注释与简化质量 | 注释解释实际验证先后、classloader 生命周期、锁原子性及已签字符串/旧 listener 的局限，没有把开源出处包装为本工程获得跨系统保证。固定版本来源与许可有记录。Ponytail 必要删减 0：一个状态字段、一个内部登记方法、现有共同 sign 和固定具名 child 已足以完成任务，不需要新框架或更多行为重跑。 |

## 核心正确性与测试质量

`SecurityConfiguration` 的抽取保持原绑定顺序和缺省构造。未知 key 在 strict Binder 阶段失败，部署参数由传入 Environment 的 `agent.dev-auth`、`server.address` 和 active profiles 提供。实际补读的 `AuthorityProperties.validate()` 仅允许 dev-hmac/test/iam，并保留 service、portal、issuer、algorithm、audience、预算等纯校验；`validateDeployment()` 在其后核 dev-auth、test profile 与 literal loopback。注册在全部校验之后，因此未知 key、缺项、无效模式或不合法部署不会先授予签发许可。

`ServiceTokens.registerDeployment` 为 package-private，生产新增的调用在上述唯一公开入口中。私有 `deploymentMode` 是已验证模式的不可变字符串快照，不保存可变 Environment，也不重新读 JVM mode/profile/dev-auth。合法 dev-hmac 与 test 可重复登记，权限含义相同；任何合法 IAM 登记把状态置为 iam，后续 legacy 登记在赋值前抛明确 `PlatformException(403, AUTHORITY_CONTEXT_MISSING)`，重复 IAM 幂等。完整生产 guard 在该登记之后才抛原启动异常，因而捕获启动失败也不能重新使用旧 signer。

登记、`issue` 和共同 `private sign` 都是 static synchronized，持有同一 `ServiceTokens.class` monitor。`issue` 的许可判断与嵌套 sign 在同一次锁持有期内，Java monitor 的可重入性不会产生中间释放窗口。userToken/integrationToken 最终必须进入同一个受保护 sign。IAM 登记返回之后进入这些签发临界区的调用均拒绝；先前已取得锁并完成的签名允许在线性化顺序中早于登记。无需额外 volatile、上下文 registry 或竞速压测。

该状态只属于定义类的 classloader；没有跨 classloader 隔离承诺，没有撤销已签字符串或主动关闭旧 listener 的实现，也没有做这些承诺。未登记 userToken/integrationToken 的原兼容签发有明确正向断言；“未登记三 API 全禁”不是本实现或 brief 的语义。secretBytes 仍仅取得原 key。完整前后代码中 issuer、claims、角色添加、audience、TTL、jti、额外 integration claims、key 来源和签名算法均保留，未向 TrustedJwtDecoder 的 RS256 域加入 HS256 fallback。

新增 `ServiceTokensDeploymentModeTest.java` 的六个具名用例与 child 分支逐项覆盖：

- `:94–100`：仅 JVM test/profile 不足以让未登记 issue 签发，另两 API 的兼容范围得到正向核对。
- `:101–116`：完整合法 Spring legacy context 可签，关闭后改 JVM mode/profile/dev-auth 仍使用既有许可；再次验证 dev-hmac/test 登记均可用。`:76–89` 解析并 MAC 验签，检查 issuer/sub/tenant/audience/token_use/roles/attributes、120 秒 TTL 和 jti。
- `:117–132`：通过 initializer 的 addFirst PropertySource 明确压过 JVM test，避免 defaultProperties 优先级错误；原完整配置两次 IAM 启动都 factory=0，捕获异常后、改 JVM dev 后、合法 legacy 重启尝试后都拒绝三条 API。
- `:133–146`：legacy 先签出字符串，再合法 IAM 登记；登记返回后同线程拒绝，CountDownLatch 放行的另一线程同样检查三 API 拒绝。Future/线程池有有限等待与回收；保留旧 token 可验签边界。
- `:147–157`：在一个 child 中循环所有原 invalid configuration（排除唯一合法 IAM），明确移除 systemProperties 对无效输入的干扰，核原原因并核 issue 仍拒绝，没有给每个无效 case 建进程。
- `:169–202`：测试 receiver BFPP 显式调用同一入口，snapshot singleton 名无碰撞；有限真实 HTTP 返回403，context 关闭后仍拒绝签发，再验证完整生产配置 factory=0/原异常。

`AuthorityConfigurationTest` 的原非法配置集合、三处合法 IAM（complete、literal loopback、portal ceilings）及 dev-hmac/test/gateway 成功 context 均有对应固定 child；parent 留无效/纯校验路径，未通过重置静态字段或 JUnit 顺序消除 sticky 状态。decoder 的 portal/internal audience 与 token_use 配对、错误配对拒绝、默认 integration 拒绝及 gateway integration 接受均保留。域业务角色与 SERVICE/GATEWAY_AUTH/空角色的原验证也保留。`ServiceTicketProviderTest` 的原未登记 issue 负例保持在 parent，源码未改。

Common child launcher 的白名单为有限具名场景，自身 classpath、45秒退出限时、5秒强杀回收、真实 exit0 与末尾 executed marker 同时成立才通过。engine child 为自身 classpath、30秒限时和相同回收原则。marker 在内部断言全部完成之后打印，不能由启动成功替代断言。代码未引入 mode reset、反射改静态状态或签发后门；engine 原有动态代理仅是未改的当前授权延迟 fixture，不能误算本次重置技巧。

## 实际验证记录及可信边界

已读三轮 raw 的实际 Running、统计与 marker，和 `.local/iam-n4a-root-fast-check.json` 的独立核验结果一致。red01 原配置先出现 `IAM_TRUST_CHAIN_UNAVAILABLE`，随后断言“应抛异常”失败；这是已编译行为红，非缺 API/编译失败。root FAST 确认该轮生产仍为 baseline；红阶段已有新 mode 测试和修改过的 AuthorityConfigurationTest，不能声称当时所有测试文件未改。

final01 的40个 AuthorityConfigurationTest 中3失败、新 mode6中2失败，其余 ServiceTicketProvider3、CommonBoundary4均有实际通过记录。原 IAM complete/portal/loopback、核心 IAM-before-listener、invalid、unregistered、legacy-then-IAM 和 common-boundary 的 executed/reaped markers 存在。final02 的三个 authority 参数化/具名 case、新 mode 两个 case 和 engine20有真实 Running，所有 child marker 后均有 reaped，最终 BUILD SUCCESS。

已定向阅读 `final01-to-final02-fixture.patch` 的所有增删行：两处 child 启动加既有批准短 temp JVM 参数，receiver singleton 与对应读取改名；没有生产变更，没有改 assertion 意义或有效 classpath。该参数解决四例 Tomcat NIO Pipe/UnixDomainSockets Windows 错误，第五例为 fixture bean 名碰撞。保留48个通过用例加续跑25是该差异下合理的验证组合，无需重跑 common179、七模块962、N1/N2 PG 或已经通过的48。

root FAST 记录的物理核验范围为：两套 before 各 source367/target1798、baseline367、live/frozen/replay source各368、live/frozen target各1806，完整清单。source +1/~5/-0，target +8/~23/-0。唯一变化 JAR 为 `admin-common-0.1.0-SNAPSHOT-tests.jar`，224108→236449 字节，是既有 process-test-classes 测试 fixture；生产 JAR 变化0。compiled production class 有变化，不应以“生产 JAR 0”称整个 target 不变。完整 context patch/apply/replay 与所有 after 字节一致的物理结论由 root FAST 核验，本 reviewer 没有再次应用补丁或操作 target。

作者报告的 protected-after 为109089文件/8718696454字节、28 runners、8 indexes，无漂移；这里明确区分作者报告与 root 的独立最终审计。冻结本报告前，已完整读取 root 最终 `.local/iam-n4a-root-protected-check.json`：2026-10-20T16:22:14.956Z至16:28:04.573Z，核验109089个静态文件/8718696454字节，加28 runners和8 indexes共8719171811字节；`unverified=[]`、`unauthorizedAdditions=[]`，原101048基底完整SHA覆盖、6项授权内部过渡和8索引精确过渡全部正确，原详设与brief不变。root该审计没有build/test/probe或source/target写入。101048既有基底中的6项精确授权更新为 review+5内部文档，另3外部索引单列；其余8041新静态补采不能被概括为放行任意旧证据。本 reviewer 没有自行重复该物理扫描；最终 N4a 签收仍由 root 决定。本新 review 是 root 明确授权新增的审查产物。

工具噪声均不应改写成干净无错误执行：作者的 CRLF 编辑定位错误发生于生产写入前；final01 五例失败真实保留；XML贪婪解析39/40是在冻结之后的证据处理错误，仅续 metadata；一次 PowerShell here-string evidence write 被自动策略以 `blocked by policy` 拒绝、未执行，随后安全创建脚本并独立 node 执行。拒绝工具显示截断，不能声称保留完整原始 payload。四个344字节 Surefire Windows Manifest-JAR dumpstream 保留，没有用禁检、改 argLine 或 runner 消噪。

root FAST 也保留其自身 schema 输出截断、历史 tests.jar.before 被初次当作 live descriptor、红阶段测试字节假设错误及 metadata continuation 缺 purechanges helper 的记录；这些是审计脚本问题，后续按未完成步骤修正，不能误当实现行为失败或重新运行测试。

本 reviewer 另有一次仅阅读噪声：对历史 IAM plan 的关键词上下文查询过宽，返回381行，受输出预算截断；未把截断部分当作已读，随后只窄读 plan 的 Global Constraints、配置、Task1和Task5相关区间。主 package 完整阅读不受影响。一次 JLS 精确文本 rg 未匹配返回1，之后按实际 HTML 行窄读；没有文件写入或行为执行。这些限制不隐藏在“全量读过”表述中。

## 固定来源与注释审查

已读 `upstream/reading-manifest.json`，其中8个文件及3个 archive/原 manifest 的物理 SHA 已在 root FAST 中核对为11项。本 reviewer 只补读下列实际机制段落，没有声称全文对照全部上游：

- Spring Context6.2.7 `AbstractApplicationContext.java:598–633`：BFPP 在 onRefresh 之前。
- Boot3.5.0 `ServletWebServerApplicationContext.java:163–196`：onRefresh 调 createWebServer，随后取得 factory 并 getWebServer。
- Boot3.5.0 `NoUnboundElementsBindHandler.java:77–103`：onFinish 对根绑定检查未绑定项并抛异常。
- Boot3.5.0 `Boot-Binder.java:251–263,526–548`：绑定 handler 参数和 Environment property sources 的真实入口。
- JLS21 ch17 HTML `:1240–1266`：同一 monitor 的 unlock/后续 lock 可见性，以及 start/join 顺序。结合实际 static synchronized 代码，形成同 classloader 线性化推导。

manifest 区分 Spring Apache-2.0、OpenJDK21+35 GPL-2.0-only WITH Classpath-exception-2.0，以及 Oracle JLS 文档快照；没有将后者重新标成开源代码。JDK21+35 是来源参考，不等于实际运行 Temurin21.0.12+8。代码中的 lifecycle/strict-binding/monitor 注释与实际实现一致，有意限制说明足够，未出现为未来框架铺垫的无用注释。

## Findings 与最终处置

Critical：0。Important：0。Minor：0。没有需要定位到具体行返修的可行动缺陷，因此不制造格式偏好、未来吞吐量或跨 classloader 需求作为阻断项。Ponytail 必要删减：0。

N4a 的限定 Spec / Task Quality 审查通过。root 完整保护审计结果已核对；生产 IAM 闭锁、N4b 待办、真实 IAM/外部环境未验收和 root 最终签收权限均保留。本 reviewer 不改共享索引，不追加旧阶段报告，也不要求用户再次批准继续工作。
