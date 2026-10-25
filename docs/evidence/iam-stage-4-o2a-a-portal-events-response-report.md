# O2a A 固定 PORTAL events response 实现报告

状态：实现与最终候选封存完成，等待 fresh review/root 有限审计；不是 Accepted，也不是公开 SSE 开启。没有 commit、手动 package、deploy 或子代理。

唯一工程及全部 shell workdir 为 G:/207/项目/算法重组工具/代码/agent-platform。原设计/四契约/8 个索引未修改。source585 的实际字节与 accepted manifest 严格相等后才改源。最终仅 9 文件，5 改、4 增、0 删；source589=backend397+frontend192。

## 实现与信任边界

- ResponseAuthorityClient 增加 default-closed openPortalEvents(RetainedAuthorization,PortalEventsBinding,Runnable) 与 checkPortalEvents(PortalEventsResponse)，旧 open/check 不变。PortalEventsBinding 是 bounded 数据，摘要 schema 固定为 portal-run-events-observation.v1，GET/public admin run route/resource/实际 subject/PORTAL/origin/cursor 独立重算。它不是权限。
- RunObservationSelectors 仅增加 EVENTS 常量/纯资源 helper/独立固定 response-call verifier；requirePurpose 原方法体字节完全未改，receiver 仍 STATUS-only。真实 Servlet mapped ServiceAuthorization 对 admin/debug/v1、另 run、HEAD/POST/PUT/DELETE/OPTIONS、错 route 的签名 E 请求全部 403，online authorize/frame/handler 均 0。没有 engine HTTP/PG 成功声明。
- HttpCurrentAuthorityClient.authorizeRetained 私有构造加入 minting owner，currentOnly owner=null。AuthorityContext 私有 final firstMapping 只保留首次实际 authorize 请求起点，refresh 只替换当前 mapping；requireFirstBound/claimPortalEventsOpening 均先核真实当前 frame/mapping，每 frame 一次开启尝试，失败也不重开。历史 first 不调用 requireVerified 充当 current 权限。
- HttpCurrentAuthorityClient:517–556 私有 admission 只从实际同 owner Retained 创建，不保存 Retained/frame；其跨 worker 部分只有已验证不可变快照。原线程在真实 open 前后调用 requireOpening(initial)，核实际 frame/mapping、原 JWT、当前 monotonic≤5s 与原 hard 边界。first/current 的 call/caller/operation/grant/initial/source origin/ticketJti/expiry 逐项相同。
- 本地 hard=min(first observedAt+55s, original operation/source expiry, original grant expiry, public binding deadline)，单调 anchor 永不重置，远端 deadline 只能缩短实际许可。新 fresh decision 的 current window≤5s，并锚定实际请求开始。仅成功开启后的 admission JWT 到期可用原 continuation 继续；不能新 exchange/action/response/resume grant。
- HttpCurrentAuthorityClient:691–720 的新 eventsQuery 独立创建完整 authority HTTP body；outer request_sha256=该实际 body digest，严格核 nonce/client/call/iat/active/current/exp。verifySession 只复用密码学验证 primitive 将 issuer/alg/kid/typ/token_use/signature/JWK 故障归一安全503；随后新 profile 自己严格要求 fresh expiry，未调用任何 session/STATUS/Q/INTEGRATION 权限或工厂。旧 query 与全局 Json 未改。
- HttpCurrentAuthorityClient:722–817 的 immutable/continuation/current 核 subject/caller/receiver/action/resource/response binding/cursor/origin/grant/source/operation/原 ceiling/原 admission nonce+digest/原 decision+version/ticketJti/hard deadline；continuation 使用专用 typ/token_use，拒假 parent/completion。新签名 current 重新解析 roles/attrs/version/decision/checked_until，不给 opening roles 换 expiry。只有新 profile 本地预期 JSON 表示做显式 wire round-trip，仍严格拒 float/overflow cursor。
- PortalEventsEvidence 类型因跨文件 Java seam 包可见，但所有构造 private；holder 包构造只接该证据并单消费。Capture private 构造，不导出 Actor/VA/Retained/ticket。requireLive/requireMaterial 均本地检查；requireMaterial 在策略计算前后核同 holder/owner/generation/stop/期限，validate+owner+cover；历史 execution ceiling/version/origin 是 provenance，不与新观察 ceiling/version/origin 强行相等。B 必须从实际 PG 验证历史 floor/row/digest 才能 mint engine emission。

## 生命周期与有限资源

每 client 懒建一个250ms watchdog、两个 owned workers、ArrayBlockingQueue64，最多64 slots；这是容量策略。每 response 单飞，同步 check 等同一 task，实际网络和成功后的 exited-latch 等待均在 lifecycle 锁外且受剩余 current/hard 预算限制。每在途任务另有同一 scheduler 的精确 deadline timer，取消网络不等于物理退出。

stop 先发布终态、使旧 generation 失效；cancel handle 在 slot 创建即归属，正常/失败/超时/close 都幂等。queued 任务 cancel/reject 正确释放 task reservation；running Future.cancel/done 不释放实际 task 占用，只有 run finally 归还。取消 callback 另先预留 work，再提交到相同 bounded workers，callback 实际返回前继续持 slot；不在 watchdog/生命周期锁中执行 callback。迟到网络成功无法 install。client.close 停 watchdog、关闭全部 slots、orderly shutdown workers，并关闭 decoder/HTTP，有限等待 owned termination。没有物理关闭/即时撤权 SLA。

真实用例覆盖64允许/第65拒绝、阻塞 cancellation仍占64 slots、queue≤64/worker=2、queued open超时不发 authority HTTP、并发 renew单飞/close后迟到、无数据时503/撤权/期限停止、实际 executor termination；try-with-resources 关闭 finite authority/JWKS/resolver/Servlet/HTTP client/调用线程池，Maven/Surefire child已实际退出。原测试 launcher 不改，未启动生产服务或额外数据库/容器，未读取相邻旧工程/进程。

## TDD 与实际测试

所有命令均为 unchanged .tools/run-iam-module-tests.mjs、Java21/offline .tools/m2、admin-common；每次唯一 stage。每阶段 before/after 有完整 source/必要 target manifest、task 源码实际文字副本，after 有原 raw 与 actual Running XML。compile/setup 错误没有算 RED。

| stage | exit | run/failure/error | 事实 |
|---|---:|---|---|
| red01 | 1 | 1/0/1 | setup: gateway configuration created before fixture actor; not RED |
| red02 | 1 | 1/0/1 | meaningful RED: actual signed E exchange/authorize/frame reaches default-closed open |
| green01 | 1 | 0/0/0 | setup: Java generic inference compilation failure; not RED |
| green02 | 1 | 1/0/1 | behavior failure: local LongNode versus wire IntNode equality |
| green03 | 0 | 1/0/0 | first positive real open/check/cross-thread GREEN |
| red03 | 1 | 1/1/0 | meaningful RED: local expiry followed by wall rollback reused capture |
| focused01 | 1 | 13/1/1 | 13-method matrix: 11 pass, first anchor and internal-key category expose defects |
| focused02 | 0 | 2/0/0 | targeted two defects GREEN; physical worker exit awaited outside lifecycle locks |
| focused03 | 0 | 6/0/0 | six new boundary methods GREEN |
| final01 | 1 | 85/0/13 | only eight-class affected command; 72 PASS, 13 inherited ERROR retained |
| red04 | 1 | 1/0/1 | meaningful RED: self-added historical/new ceiling equality rejects valid current coverage |
| green04 | 0 | 22/0/0 | post-fix Profile whole class 22 GREEN; necessary regression for final production change |

final01 原命令：node .tools/run-iam-module-tests.mjs iam-stage-o2a-a-final01 admin-common PortalEventsAuthorityProfileTest,PortalEventsReceiverClosedTest,AuthorityContextRetainedTest,RetainedAuthorizationTest,ResponseAuthorityTest,RunObservationSelectorsTest,GatewayStatusAuthorizationTest,ServiceAuthorizationTest。实际8类85方法：7类71通过，旧Service类1通过/13 ERROR，合计72通过/13 ERROR，退出1。不能称85全绿。

root 独立窄处置见 docs/evidence/iam-stage-4-o2a-a-inherited-service-test-decision.md（4094B/3f61d9207f55eff52162945fa77d0010b1f5c0cd69a90e3550b08a4c1b123a1f），13个结构性旧 STATUS/run-r-test 冲突逐名保留为迁移待办；没有改旧测试、放宽 receiver、单选 health 或重跑其余已绿类。

自检的历史/new ceiling额外相等先做 red04 实际 E-holder 正例RED，再仅删除一项自加判断；按 root 指示 green04 只跑新 Profile 整类22/22通过，退出0。它是新 production 变更的必要回归，未重跑原其余6个已绿类/旧Service冲突；不是另一次8类全绿命令。两次独立 candidate replay 都如实保留。

## 库存、执行身份与重放

最终目录 docs/evidence/iam-stage-4-o2a-a-portal-events-response/final02。executedSource=docs/evidence/iam-stage-4-o2a-a-portal-events-response/green04/after/source.json；finalSource=docs/evidence/iam-stage-4-o2a-a-portal-events-response/final02/source-after.json，两者589项 names/bytes/SHA严格相等，源码文字另有 frozen-source。

最初 helper 错把 logical target2004 当全部物理一致，assertion失败；失败快照保留 baseline/before，随后 source585仍严格PASS，baseline-physical记录实际。发现4个 app PublishedAdmissionFixtureMain*.class历史物理差异，发生时间未知，独立known-inherited-target-correction，0新增0删除/count2004，不归A编译；没有恢复/覆盖/编译app。5个prior dumpstream（各344B）作为诊断排除，未吸收成logical target。

freeze01（历史候选）的actualcompiler/resource/report候选201，由corrected2004覆为2023；语义修复后最终候选187，由先前2023覆为2023（本次增0/改5/删0）。compile inputs57/test inputs29；所有179 class实际来自createdFiles.lst，4个compiler status、resource1、tests-jar1及本次Profile XML/txt2。前次真实8类XML/txt仍按历史candidate继承，并保留原结果。

resource closure实际源 admin-common/src/main/resources/agent-service-authority.json 与输出 admin-common/target/classes/agent-service-authority.json：86120B/fa37b6d8602e470e6511284b463b9905600aba1cd392a4f868927ce475515d9b，字节摘要相等。测试goal的既有finite-test-fixtures test-jar phase属实际输出，未执行手动package。旧main jar不是本次重建候选。所有owned新增dumpstream另在diagnostic-closure.json冻结，不算classpath。

freeze01/private replay首check0/apply0成功，后因语义修复被取代，其source/target/patch/replay原件未改写。final02/private replay另从实际已验baseline585 task原字节＋逐项验证未变的其它baseline源重建；单独新patch首check0/apply0，apply后没有复制final字节进去，589 names/bytes/SHA严格相等。两个private repo均init但无commit，不是工程commit。

## 最终9文件 EOF/bytes/SHA256

| file | lines | bytes | SHA256 |
|---|---:|---:|---|
| admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java | 106 | 6290 | 770d9cdf06c7f4d757d4d96bcfd5f5fadd0991f2531edf53a6d948c91328678a |
| admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java | 1073 | 69839 | 5368e60035128bc7a953d2fe666979a9176b679108ead7501473e9928e3505d3 |
| admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsBinding.java | 41 | 2052 | 447854adbba6f912c8455c6c35eb7014e0789c69a584ae24cd7a80f9ac6f9800 |
| admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java | 170 | 6169 | bb2d917da887d3551efdc5f5182b58d1c50920c0923cd28776ae330b030a58eb |
| admin-common/src/main/java/cn/agentplatform/common/authority/ResponseAuthorityClient.java | 16 | 667 | 50b511b2fed83965b2f49ccb91ffd3bd63196fa41574ea130966f3fd46502519 |
| admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java | 56 | 3822 | cb31869195ddaace6608b8592af7ec9ed315f859c1c9c5df79c52a799b9bc10b |
| admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java | 621 | 58454 | e3eb6c286381bf74df6856e82da54b2c5435e96bdba6ff9364a4e8186dc9c5b2 |
| admin-common/src/test/java/cn/agentplatform/common/authority/PortalEventsAuthorityProfileTest.java | 624 | 34862 | d89631984f0e3053b8ae6bca42ff070ca9edf5821f1d3da00a7d3757ae8edfb0 |
| admin-common/src/test/java/cn/agentplatform/common/authority/PortalEventsReceiverClosedTest.java | 137 | 6784 | 6f6ff0836d04ab3006e4d8e82436c5bc1e0eb8ff883bb3fb920e1ac906a42ad8 |

全部9文件以LF结束，尾16bytes见 source-eof-identities.json。upstream读取限正式brief、两份frozen notes、root补充、适用AGENTS/implementation-brief/contracts README及本次common接缝/fixture/具名测试范围；无engine/gateway/PG/POM/catalog/MaterialAuthorization写入，无原件/设计/8索引变更。

交还 source/target/backend class/own evidence/report 的全部写权。root与fresh reviewer再审核最终candidate，A仍不是public SSE opening；B/C、历史测试迁移、全局IAM验收均未完成。

EOF
