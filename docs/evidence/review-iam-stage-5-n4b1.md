# N4b1 网关已发布运行私有授权 capture 独立审查

审查结论：**Spec compliant；Task Quality Approved。Critical 0 / Important 0 / Minor 0。** 结论仅覆盖正式 brief 的 N4b1 common 组件，不代表 N4b、全 Task 5、真实门户联合链或生产 IAM 签收。没有需要在本范围返修的产品缺陷，未提出脱离本切片的重构或扩大测试要求。

本 fresh reviewer 依据完整补丁和原始证据独立判断，使用 code-review-expert 的授权、并发、异常和边界检查清单。所有 shell 显式 workdir 为 G:/207/项目/算法重组工具/代码/agent-platform；未访问相邻旧工程、配置、数据、缓存或进程，未委派其他 agent，未执行构建、测试、探针、package、deploy、commit 或 push。唯一写入为本新报告，source/target、既有证据、manifest 与共享八索引均未改。

## 1. v1.2、契约、固定版本与服务边界

已读 AGENTS.md、docs/implementation-brief.md、contracts/README.md，及当前 IAM plan 的约束/证据语义、root-decisions 全文、published-chain 提案的 JSON 边界。唯一设计仍为 v1.2，四契约未改变；PG 仍是业务权威，跨服务仍 HTTP。该组件不创建生产 IAM、业务内存权威或额外服务 JAR 依赖。Java 21、Boot 3.5.0、Security 6.5.0、Spring AI 1.0.0、Nimbus 9.37.3 固定版本不变。

先核 SHA 后完整阅读以下输入：

| 输入 | 字节 | SHA256 |
| --- | ---: | --- |
| iam-stage-5-n4b1-gateway-run-authorization-brief.md | 13109 | 9642f0fdd7f8deb3042c28c4a53acbb37bdfdf64e715dda3e189977aa5d28bf4 |
| review-iam-stage-5-n4b1-package.diff | 68085 | 58fcb9d64b5cb5cdcd6d00c22b228b68e60adb637e65a767536617e1d4781745 |
| iam-stage-5-n4b1-gateway-run-authorization-report.md | 14213 | b99c0a3082f535d2011589739efa5f248a02de8bc4688d4e601b60af2813601a |
| iam-stage-5-n4b1-gateway-run-authorization/handoff.json | 7842 | fc35256ca12ff4de6155cbee0cf1843f5757a7a11fdc691270ac05c72f3a01d4 |

以上路径均在 docs/evidence/ 下。完整 full-context 审查包按 1–130、131–260、261–390、391–520、521–650、651–763 六段读至 EOF，每段不超过 150 行且未截断。文件含 763 个内容行；按 LF split 为 764 个字段，最后一项来自末尾换行，并无遗漏的一行正文。没有把补丁摘要当完整代码。

生产改动仅 CurrentAuthorityClient.java、HttpCurrentAuthorityClient.java；测试改动为新增 GatewayRunAuthorizationTest.java 和原 CurrentAuthorityClientTest.java 增加 default-fail 用例。完整补丁中原测试断言没有删除或削弱。LoopbackAuthority、SecurityConfiguration、ServiceTokens、AuthorityProperties、routes/catalog、POM、wire、SQL、runner 均不在变更集合。

## 2. 私验来源、期限、owner 与旧 API 兼容性

源文件定位以下均相对 admin-common/src/main/java/cn/agentplatform/common/authority/。

- CurrentAuthorityClient.java:9 的默认 API 直接拒绝，不调用旧 exchange/authorize，也不包装 public VerifiedAuthority。原实现者无需实现新方法即可保持默认拒绝。
- HttpCurrentAuthorityClient.java:59 的有限入口先核 closed、validated config 的 iam/gateway，固定 engine / proxy.engine.post.RunController.run。根限 app-version、安全 code、无前导零的正 long 版本与小写 SHA256，不能选择任意 target/action。ResourceBinding 构造器保证非空 ID；原 token 的 null、长度、JWT 格式与验签由 TrustedJwtDecoder 检查。MetadataSelectors.code 的 INVALID_APP_CODE 分类继续沿用，不把它改造成权限成功。
- :47 的 portalExchange 从原 token 真正解码、PortalClaimMapper 和 platform.access 检查开始，保存 source 截止与进入验源前的 nano/Clock 观测。:254 的 query 以固定凭据进行原签名 HTTP exchange，核 nonce、完整 request_sha256、client、call、iat、active/current；签名 envelope 自身包含随后私验的 ticket，因此不是独立传入任意票据。:215 的 verifiedTicket 保留真正 RS256 私验后的 claims，继续核 caller/client/target/action/scope、route、主体、initial ceiling、grant、authorization_version、operation、ticket≤operation≤grant。
- :81 的 public final GatewayRunAuthorization 只有 private 构造器，私有 PortalExchange/VerifiedTicket 不向外暴露；ticket()/operation() 返回的是同次私验结果，没有 public factory、copyFrom、currentOnly 或任意 VA/票据壳。它直接绑定创建 client 和其私有 Clock；没有接受外部 capture 的消费 API，故没有为了测试而扩出跨实例授权入口。
- :88 的真实截止取 min(signed current exp, ticket exp, operation exp, signed grant_expires_at, source exp)。grant 截止读取真正 ticket claims，不从 public operation.exp 补造。单调预算取源入口观测至该 min 的时长，再以 validated maxDecisionSeconds≤5 秒保守封顶；不是返回时重给五秒。:102 的 synchronized requireVerified 使用 elapsed subtraction，检查壁钟实际截止和原预算，失败后 sticky，跨线程不依赖 Actor/SecurityContext/AuthorityContext。检查不联网、不刷新、不重置预算。
- :69–72 在所有 ticket/JWK 验证后终检，阻止验证等待耗尽 current 窗口后交出可用 proof。:324 在 decoder/http 关闭前发布 volatile closed；已有 proof 即失效，新的入口被挡住，在途 exchange 还必须经过终检。此处没有声称本地检查与后续业务发送原子化，也没有把 owner 标志当 JDK 全部在途 HTTP 的硬取消机制。
- 原 exchange 的 integration 分支完整保留，门户分支仅抽取原校验并改用已经验签的 claims；ticket 返回给旧 caller 的 ServiceTicket 语义保持。resume、forward、authorize、authorizeRetained、approver、workload、response 未放宽 receiver target、初始 ceiling、恢复或来源约束。实际正常对照仍由 engine 接收；gateway 不能把 engine ticket 当自己的入站票据。

补读 AuthorityProtocol、ResourceBinding、ServiceTicket、OperationBinding、TrustedJwtDecoder、AuthorityResponseVerifier 以及 AuthorityProperties 的具名验证区间，核实 source/subject/initial/dataLabels 的规则与数值边界。现有 verifier 的失败分类保持，新入口默认拒绝及 owner 拒绝使用 SYS-AUTH-403，期限及 sticky 拒绝使用 AUTHORITY_EXPIRED；没有吞错放行。proof 的 toString 为固定私有证明标识，ServiceTicket 保持 redacted；没有新增原 token、上游 body 或密钥回显。

## 3. 实际 TDD、测试和生命周期证据

只核 frozen 的 command/raw/execution/source/test-audit 与 actual Running 对应的 selected XML，没有执行任何测试。旧 targetMetadata 中的历史 XML 只视作元数据，不计作本轮执行。三阶段 frozen raw 与 selected XML SHA 均与 execution 描述符一致；实际 raw Running 集合、XML 根统计与 testcase 数量相符；test-audit 的全部阶段 case(name/classname/time) 与 XML 逐项一致，共 29 红阶段记录加 49 最终记录，不能称为 78 个唯一绿色测试。

| 阶段及受控 runner 参数 | exit | 实际 Running | 结果 |
| --- | ---: | ---: | --- |
| iam-stage-n4b1-setup01 admin-common GatewayRunAuthorizationTest | 1 | 0 | 缺 API 编译失败，未执行测试 |
| iam-stage-n4b1-red01 admin-common GatewayRunAuthorizationTest | 1 | 1 | 29 tests / 4 assertion failures / 0 errors / 0 skipped |
| iam-stage-n4b1-final01 admin-common GatewayRunAuthorizationTest,CurrentAuthorityClientTest | 0 | 2 | CurrentAuthorityClientTest 20 + GatewayRunAuthorizationTest 29 = 49 / 0 failures / 0 errors / 0 skipped |

命令均为既有 node .tools/run-iam-module-tests.mjs，由作者自己的 evidence.mjs 阶段包装冻结证据；Java 21 Temurin 21.0.12+8、offline own .tools/m2。没有绿色重跑、额外 selector、common179、N4a53、N3PG 或七模块962。Maven 的 deprecation/unchecked 输出和两份 344B Windows Surefire dumpstream 诊断保留，没有 argLine/POM/runner 静音。

setup 不是安全行为红。已读 prototype.json、phase-source-deltas.json，并窄读 frozen red 的真实实现：root 批准的可编译原型已经保留真正 signed portal/query/ticket、主体/ceiling/route、ticket/op/grant/source 以及 owner/sticky 校验；current 截止、nano 预算和返回前 final check 尚未实现。四红全部是 Expecting code to raise a throwable 的 AssertionError：

1. signedCurrentExpiresWhileActualTicketRemainsValidAndClockRollbackCannotReviveProof：current 到期，实际 engine 仍可接受未来 ticket，而原型 proof 未拒绝。
2. laterTicketJwkVerificationCannotReturnProofAfterSignedCurrentExpiry：实际第二次 JWK fetch 后 current 已跨期，原型仍返回。
3. monotonicBudgetExpiresEvenWhenWallClockRollsBackBeforeFirstRecheck：第一次本地复核之前壁钟回拨，真实单调预算已耗尽，原型未拒绝。
4. closeDuringSignedExchangeDoesNotMintCapture：第二次 JWK 验证阻塞期间 owner 开始关闭，原型未作最终检查而返回。

不能据此称旧生产代码存在这些 API 漏洞或红阶段生产字节未变。阶段差异证明测试首先加入，红阶段只加入最小可编译实现及默认拒绝测试，red→final 只改 HttpCurrentAuthorityClient 的保护；新测试正文未为变绿而改断言。

GatewayRunAuthorizationTest.java:19 的正例核真实主体、initial、PORTAL 来源、root、origin、operation 与 engine 接收，清理线程上下文后在 worker 复核且 authority 请求数不增加。:48 的 late-JWK 用例替换/恢复该测试私有 f.server 的 /jwks context，真实第二次 fetch 推进 fixture Clock 六秒，并断言恰两次 fetch、一次 exchange、未来 operation；这是确定性时间注入，不是真实网络延迟测量。:102 的 monotonic 用例才执行真实 1.1 秒 latch 等候，二者没有混称。

:63 的 source/ticket/operation/grant 四组保留 ticket≤op≤grant，短 grant/op 必然同时约束嵌套期限，未伪称 grant 能在更长仍有效 ticket 之前独立到期。:76/:83 核 close 后拒绝和 close 中终检；:111 核多个 capture 的新 nonce/op/grant；:118 与 :137 核有限入口/非 Portal 来源及 17 种具名签名字段破坏。原 CurrentAuthorityClientTest 的实际20例涵盖旧 Portal/integration/workload/forward/resume/approver/receiver 约束，包括新 default-fail 测试。

resources.json 与测试源码一致：Future.get 负责观察结果，shutdownNow 只是中断请求；awaitTermination 和 handler exited latch 才支持所拥有 worker/handler 的退出断言。fixture/client 的 try-with 关闭实际执行。没有逐端点/PID/OS socket 清点证据，也没有全部网络操作硬取消或所有 close 有界的证明。

## 4. N4b2、全 Task 5 与生产边界

N4b1 API 只交付私有 proof 组件，尚未接真实 GatewayController、安全链/两个 ANY 的有限登记、engine POST 前、完整缓冲后、JSON 写出紧前的 requireVerified。publishedRoot 由后续真实 metadata lookup 提供，本组件本身不证明应用已发布。上述接线、metadata/main grant 分离、同 originAuthenticationId、实际 app payload / engine / RunService PG / owned scheduler 联合链，留 N4b2 的明确正式范围；不能以未做这些任务判本 B1 缺失，也不能把本 B1 计作它们通过。

当前≤5秒签名决定窗口是有限适配约束，不是即时撤权 SLA；已经写出的字节不可撤回，本地 proof 不承诺跨 IAM/PG/网络全局原子性。已有 BoundedIamHttp 的 JDK 内部 retry / close 缺口仍是具名待办，不能据本组件声称整链 literal zero retry 或 hard bounded cancellation。

SecurityConfiguration.java:46–50 的 validated deployment 入口和 IAM_TRUST_CHAIN_UNAVAILABLE 生产闭锁保留。真实 IAM、模型、本体、K8s 环境尚未验收；T01-only 整体完成口径与全 Task 5 剩余边界保持。没有要求为完成隔离软件组件再次索取相同外部环境许可。

## 5. before / frozen / replay / target、保护与工具问题

已读 root 的三个最终 PASS 结果并独立核对其 SHA，明确将 root 的物理审计与本 reviewer 的代码/日志/XML 审查区分：

| root 结果 | 字节 | SHA256 |
| --- | ---: | --- |
| .local/iam-n4b1-root-fast-check.json | 3653 | 9793c1d7f9fbf2a9ea83cc3cfcf47be03dc44ca1dc7a6d1990dfecff3dfc41cb |
| .local/iam-n4b1-root-protected-check.json | 1111 | 67900959d26619fe159082afb590151817cbc530aca60883e7e83d76931b9e11 |
| .local/iam-n4b1-root-upstream-entry-check.json | 1491 | 668a28b120aa31b95d3093606f744debc5fc20b110bb50e3f8d66f5c8f6ff504 |

root fast 已核 before/source/live/frozen/replay/targets 的完整 inventory、scope/delta、三阶段 source/raw/XML、6260 sealed evidence files 和10个 local tools、来源/归档描述符。before source368 / target1806，after source369(+1/~3/-0) / target1814(+8/~12/-0)。唯一变动 JAR 是 common tests.jar 236449→249103B，生产 JAR 变化0；编译产生的 class/增量 metadata/diagnostics 全部归因，不把 stale 生产 JAR 当本轮新制品。

作者 fullcontext change.patch 为67949B，SHA759d751d1958f8881ae1ab2ea3d8d4d17cc78adbb6c068cf0d4f0aab406bb9c1。冻结实际 apply --check/apply 均 exit0，369 replay 与 final source 逐字一致由 root 独立复核；本 reviewer 未重跑 apply 或测试。

root 最终完整保护实际运行 2026-10-20 17:01:26.370–17:07:04.417 UTC，117539 静态文件共9814266735B、28 runner、8索引，合计9814765971B；未验证项和未授权新增均空。旧109089完整 SHA 覆盖，新补8450；5个先前授权内部转换、最新8索引、duplicate metadata correction 与原 v1.2 均已核。它独立于作者自己的 after 保护结果，不能混为同一次自证。

透明保留且已纠正的证据工具问题如下，未发现由它们造成的产品改动或未核范围：

- before 为分阶段采集。补采初次晚应用目录排除导致中断；原 source/target 2174 个物理 before 副本保存并核 SHA 后，只续未完补采，未重新基线化。
- before.indexes 内部5条重复 metadata 仍是旧 SHA；protectedFiles 内部5与external3正确。effective-index-before 从冻结转换表导出，未改原 before，也未重采掩盖差异。
- initial finalize 错选 live，仅写生成 baseline 前21路径，前20相同，第21 interface 被 SHA 抓到；原物理 before 未动。随后使用 historical before 恢复正确 replay 起点。
- nested-cwd apply 返回0但跳过路径，由 replay SHA 抓到；root-cwd 随后遇 text auto CRLF。最终只在自有 evidence 内设置 * -text，保留真实换行再生成 patch，工程 .gitattributes 不改；旧 patch/命令/失败日志保留。
- root fast 初次将 suite 全对象与5个统计字段比较而失败，之前完成的 physical 阶段不重跑；原 helper 与 .local/iam-n4b1-root-audit-noise.json 保留，只以 rest 继续 metadata 和剩余步骤。最后 PASS 不能消除这次原始错误记录。
- 作者报告脚本一次语法错误、猜测可选路径缺失及展示截断均在 tool-events.json 说明。本 reviewer 也遇到辅助 execution/批量计划展示截断，随后用字段投影、独立 raw 小文件、XML 解析和窄范围原文完成被引用核对；没有把截断输出称为完整外部工具 transcript。正式四份输入与六段完整补丁阅读无截断。

这些属于有原记录、有 SHA 拦截和最终独立复核的证据制作问题，不应另造产品缺陷，也不能隐藏为从未出错。root 后续负责更新八索引，本 reviewer 不修改索引。

## 6. 固定上游版本、实际窄读、许可与推论限制

复用并阅读本任务 upstream/reading-manifest.json、exact-archive-entry-verification.json、mechanism-reading.json；独立核六个本任务冻结源文件 SHA 和许可头，实际窄读范围为：NimbusJwtDecoder 135–205（decode/createJwt/validateJwt）、DefaultJWTProcessor 334–390（process SignedJWT）、JWSObject 360–395（verify）、RSASSAVerifier 154–179（verify，实际文件至180）、Jdk21-Clock 430–448（instant）、Jdk21-System 510–571（wall/nanoTime 与 elapsed subtraction）。没有泛扫旧归档或声称全部上游源码已读。

Spring Security 6.5.0 与 Nimbus 9.37.3 为固定 Maven source archive，Apache-2.0 许可头保留。root 四个 exact zip entry 实际内容逐字 SHA 相等；其结果仅证明这四个 entry，不扩为整库源码机制审查。官方 OpenJDK jdk-21+35 的 Clock/System 为 GPL-2.0-only WITH Classpath-exception-2.0，原头保留。引用版本不等同实际 Temurin21.0.12+8 runtime identity。

上游代码支持“密码学验签之后仍需独立 claims/用途/时间验证”和“nanoTime 只能计算同 JVM elapsed，宜用相减比较预算”；本实现将源入口时间、signed min 和≤5秒封顶结合，是本项目的保守期限推论，并非 Spring/Nimbus 提供的授权或即时撤权功能。没有新加密框架、依赖或生产可注入 time hook。

独立 Spec 与 Quality 两项均通过；未发现可执行返修项，C0/I0/M0。已知 N4b2 和生产/传输剩余工作按上文保留。本报告在 root 最终保护与 upstream entry PASS 到齐后一次写入；完成 SHA 回报后停止写入，归还本审查唯一报告的所有权。
