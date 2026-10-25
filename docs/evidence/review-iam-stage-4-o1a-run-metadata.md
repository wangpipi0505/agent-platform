# O1a run-observation metadata 独立任务评审

## Spec Compliance

- ✅ **本任务实现符合已检查规格。** 唯一新增 HTTP 路由为 `GET /internal/runs/{id}/observation-metadata`；catalog 的 caller/target/action/mode/integrationAllowed 与 brief 一致，未新增 ANY 或别名。共享 selector 按已验 subject、public run id 和版本化 projection 构造 canonical tuple/digest；实际 ServiceAuthorization 在在线 authorize 前双向限制 selector kind/action 及 gateway→engine、delegated、PORTAL/PORTAL。证据：`admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java:10`、`:20`；`admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java:101`；`admin-common/src/main/resources/agent-service-authority.json:2019`；`engine-runtime/src/main/java/cn/agentplatform/engine/api/RunObservationMetadataController.java:15`。
- ✅ 请求验证和主体绑定符合范围：controller 拒绝非 REQUEST、非 GET、模板/原始 URI 不符、任意 query 和 body；service 从 live AuthorityContext 提取 subject，验证 path selector，入口先拒绝 ambient transaction，再调用 requireInitialFor 在线刷新。store 重验同一 proof/owner/selector，返回前仍有本地终检。证据：`engine-runtime/src/main/java/cn/agentplatform/engine/api/RunObservationMetadataController.java:18`；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunObservationMetadataService.java:19`、`:32`；`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:85`。
- ✅ 成功 data 是精确七字段值 record，owner/root 为现有纯值对象，两个版本数字为正 long，域构造器检查 runId/root/hash 约束，service 按 data 的 UTF-8 JSON bytes 拒绝超过 4096，不截断、不赋予 capability。证据：`engine-runtime/src/main/java/cn/agentplatform/engine/domain/RunObservationMetadata.java:7`；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunObservationMetadataService.java:29`；实际 HTTP 七字段/long 断言：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:190`、`:363`。
- ✅ 新 store 建立顶层 readOnly RR，先以 public_id + 已验 tenant/principal 窄查；不存在/其他 owner 同 404，已归属损坏材料 403。当前三个 SELECT 不取 run/node/event/tool payload，也不调用 TraceStore.run、composeStatus 或原执行 strict。load(true) 与三根 scopes 同处此 RR；旧 grant 的 expires/blocked 不参与独立 query 是否允许的决策，代码未改 grant/block/运行。证据：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:89`、`:98`、`:109`、`:117`；三根校验聚焦核查：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:136`、`:179`。
- ✅ 请求、绑定损坏及生命周期测试有实际断言：真实签名票经 engine HTTP/security/handler，再查询独占 PG；生产 createPublished 受理创建 run/material_scope，非手填假 run；raw socket 的 bare ? 检查接收端 queryString 为空字符串；缺 guard 的真实 MVC 装配拒绝启动；manifest 与 post-commit 时点失效均不得返回 200；ambient 测试断言在线调用和 SQL 都为零。证据：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:70`、`:91`、`:159`、`:212`、`:235`、`:251`、`:270`、`:288`、`:331`、`:343`、`:358`。采用既有执行结果，评审未重跑测试。
- ✅ 对已知信任边界的披露与实现一致：有限 pure FLOW 借助可信受理、immutable 存储与控制标识排除；合法自洽 root digest 替换不能靠 metadata-only 重算识别，不读取 payload 迁就负例。终检位置是 service return 前，后续 MVC wire 首写不被声称覆盖。证据：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:99`；`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunObservationMetadataService.java:31`；`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:388`；`docs/evidence/iam-stage-4-o1a-run-metadata-brief.md:43`、`:45`、`:47`。
- ⚠ **跨任务限制，不作为 O1a 缺陷：** 生产 authority 资源关系解析、真实 gateway GET 链、O1b/O1c/O2、RR1/RR2、真实外部环境，以及完整 legacy Boot 启动不在本 diff 验证范围。legacy 本次仅是有限 Spring context；测试 receiver 使用真实 RunController handler 的拒绝路径，但其依赖为空，不能据此声称 POST 主业务或完整 engine 已验收。证据：`docs/evidence/iam-stage-4-o1a-run-metadata-brief.md:9`、`:53`、`:59`、`:70`；`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:74`、`:352`；`docs/evidence/iam-stage-4-o1a-run-metadata-report.md:50`、`:54`、`:62`。

## Strengths

- 生产改动保持窄边界：新增 selector/controller/service/domain 分别 28/26/40/19 行；RunAuthorityStore 从 161 到 195 行，仅增加 import 和新只读投影；ServiceAuthorization 仅新增 2 行目的检查。未引入新通用框架、缓存、重试或未来 status capability。证据：`docs/evidence/review-iam-stage-4-o1a-run-metadata-package.diff:16`、`:49`、`:2736`、`:2768`、`:2814`、`:2838`。
- 在线刷新、DB 快照、返回前 proof 校验分界清楚，注释说明 ambient 拒绝与 wire 边界，不把旧执行 grant 的寿命误用为新 query 的授权条件。证据：`engine-runtime/src/main/java/cn/agentplatform/engine/application/RunObservationMetadataService.java:20`、`:31`；`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:87`、`:106`。
- 新测试虽然 394 行，但集中在同一独占 HTTP/PG fixture，覆盖真实安全边界、控制数据损坏、隔离/连接属性及无写副作用，不是仅断言 mock 返回值或无意义测试。SQL 拦截器保留真实执行文本和连接状态；raw socket 解决了 bare ? 被客户端规范化的问题。证据：`engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:124`、`:190`、`:270`、`:288`。
- 新增生产异常处理没有发现吞掉授权/DB失败后继续成功的路径：损坏元数据的类型/构造异常归并为 403，其他运行错误正常传播；proof 失败使事务/投影终止。证据：`engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java:109`、`:113`；`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:38`、`:48`。既有 LoopbackAuthority 的异常到协议响应映射属于测试 fixture 原有行为，本次未扩大其吞错逻辑。

## Issues

### Critical

- 未发现本次改动引入的 Critical 问题；本结论限于冻结任务包及下述具名聚焦检查。

### Important

- 未发现阻断本切片的 Important 问题。不能把后续切片尚未实现或未重跑旧全套当成本任务缺陷；依据 `docs/evidence/iam-stage-4-o1a-run-metadata-brief.md:9`、`:59`、`:70`。

### Minor

1. **测试 HTTP client 清理不完整，报告的关闭表述过宽。** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:52` 创建 static HttpClient，`:119` 的 AfterAll 关闭 context/gateway/engine/iam，却未关闭 http。建议在清理中调用 http.close()，或者在证据中明确它随测试 JVM 退出。作者报告 `docs/evidence/iam-stage-4-o1a-run-metadata-report.md:83` 的“各 decoder/client/context 关闭”不能按逐个 client 主动关闭理解。已退出测试 JVM 限定了影响，故不提升为生产资源泄漏或阻断项。
2. **SQL 保护断言允许投影段任意扩展。** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:171` 仅禁止 snapshot::/inputs:: 等特定拼写，`:173` 首两个 regex 分支使用 `.*` 覆盖投影段；未来把 `r.snapshot` 或 `r.inputs` 直接加入 SELECT，仍可能满足此 helper。建议固定允许的列/表达式，或采用能明确识别受保护列的校验。当前生产三条 SQL 已逐条读过，确为控制/manifest 查询（`RunAuthorityStore.java:98`、`:118` 与 `MaterialScopeStore.java:139`），所以这是防回归力度问题，不是本次实际 payload 泄漏，也不据此推翻当前零 payload 结论。

3. **已有日志噪声与诊断编码损失需保留为非阻断事项。** root 提供并逐行核对的摘录见 `.local/iam-o1a-root-diagnostic-notes.json:5` 至 `:55`：red01 `runner.log:62`、green01 `:41/:79/:88`、green02 `:76`、green03 `:38/:66` 是未改动的 GatewayRunAuthorizationTest / ContextProjection / FlowMaterialStatusPostgresTest 的 unchecked 编译提示；red01 `runner.log:76` 是 schema 已存在的 Flyway WARN。中文诊断中有 replacement 字符，降低原始诊断可读性。保留原件，后续在对应既有代码/采集工具工作中处理，不通过本次重编译或抑制日志美化证据；不归因于新投影的生产故障。red01 `runner.log:283` 和 green01 `:183` 的 “See dump files (if any exist)” 只是 Maven 失败模板，不能当作实际 dump 存在证据。

## Evidence and focused checks

- 评审输入独立核验匹配：brief 14577 bytes / SHA256 `31292edd840dd2dad32901dea3a85a1ae2e89a132af5777f43c805309abd28b2`；作者报告 14351 bytes / `f60db9ef148115e1591fda39eb1b0bc2ef5da45934fc5119238bd556818e3d9e`；diff 221265 bytes / `816da4f2a391394af564bd9312d09f73ba6550e5e0112a1524fe3122d0128a21`。base/head 不使用 HEAD 或 commits；沿冻结 D4a/I1 backend380 + D4b frontend192 的 572 到 O1a 578，6 new / 4 modified / 0 deleted。包标识：`docs/evidence/review-iam-stage-4-o1a-run-metadata-package.diff:1`。
- 完整 diff 只按互不相交范围读取一次：1–550、551–1200、1201–1850、1851–2380、2381–2700、2701–3000、3001–3160、3161–3310、3311–3433（EOF）；无工具截断、无需补读。未重新读改动源文件，未运行 git、测试、浏览器、数据库探测、旧全套或旧业务 helper。
- 具名风险 A：load(true) 是否真覆盖三根，而非只检查控制记录。一次聚焦读取 `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:133` 至 `:201`，确认限定三 kind、恰三条/无重复、owner/id/version/initial/原 admission decision 与 authorization version、row 冗余、scope 摘要及 parent 链。
- 具名风险 B：刷新后 proof 被替换/关闭是否仍可返回。一次聚焦读取 `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java:28` 至 `:81`，确认 requireBound 比较 frame.mapping 对象身份并验证 proof，frame 关闭/失效拒绝；requireInitialFor 刷新保持 caller/call/delegation/operation/version/initial/origin 绑定。
- 具名风险 C：新 DTO 对 owner 字段仅检查长度是否可能留下 null/blank。一次聚焦读取 `admin-common/src/main/java/cn/agentplatform/common/authority/SubjectKey.java:1` 至 `:8`，确认构造器拒绝 null/blank；域值不需重复相同构造器约束。
- 已阅读唯一工程 AGENTS.md、docs/implementation-brief.md、contracts/README.md 与指定 task-reviewer-prompt/requesting-code-review 两模板。所有 shell 显式 workdir 为唯一工程；未访问相邻旧工程。唯一文件写入为本评审报告；不创建 commit、不改 index、不部署。

### Root 有限证据审计已核验

- ✅ root 审计结果由 reviewer 读取并核对文件 bytes/SHA：`.local/iam-o1a-root-check.json:2` 为 PASS，2920 bytes，SHA256 `11a95ef509e5a1feb3887919f0f085e46039c6da2f021a186464f0d713a96765`。它是 root 的独立物理/执行证据审计，不能替代上面的源码判断；原先“待 root”的证据项在本报告冻结时已解除。
- 审计覆盖 641 distinct physical：当前 backend386、frontend192 继承、完整必要源578、206 own leaves 共6061385 bytes、32变化 target；生产 JAR 未变，旧谱系不重扫。两 patch 重放链保留首次 check/apply 0/0 后的 EOL mismatch、repair01 check1/未 apply、repair02 0/0，最终578 names/bytes/SHA相等。证据：`.local/iam-o1a-root-check.json:5`、`:8`、`:22`；细节由作者报告定位 `docs/evidence/iam-stage-4-o1a-run-metadata-report.md:68`、`:74` 至 `:79`。
- 四次实际命令/XML 分别为 red01 1例1F/0E、green01 18例2 oracle F/0E、green02 20例全过、green03 16例全过；最后适用 common7 来自 green02，engine16 来自 green03，不是一次23例。red01 额外复制的旧 XML 未计执行；first-red 时间更正为子进程 Finished 后16759ms新增 common 测试，原573清单未倒改。证据：`.local/iam-o1a-root-check.json:31` 至 `:71`；`docs/evidence/iam-stage-4-o1a-run-metadata-report.md:41` 至 `:48`、`:70`。
- root 核对52条实际 O1A JSON与原 runner：七字段 data485B、3 SQL同连接RR/readOnly、bare ?实际400、manifest/post-commit两种失效各403、effects不变、四次测试JVM资源close、storage trust boundary200均有实际记录。controller/service边界语义已由本 reviewer 对测试及生产代码核对。证据：`.local/iam-o1a-root-check.json:75` 至 `:86`；对应代码 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationMetadataHttpPostgresTest.java:190`、`:288`、`:331`、`:388`。
- 跨文件冻结项：root 审计确认8索引、原设计和README未改，前端源/dist继承；不在本评审扩大旧证据扫描。证据：`.local/iam-o1a-root-check.json:90` 至 `:100`。

## Assessment

**Task quality: Approved。** O1a 的授权用途、只读投影与返回前失效控制有清楚实现及针对性真实 HTTP/PG 测试；两项代码质量 Minor 建议及既有诊断噪声不阻断本切片。此结论不等同于完整 O1/O2、全分支或生产外部环境签收。

**冻结与交还：** 报告完成后，本 reviewer 停止全部写入，唯一报告写权与其余 source/target/证据所有权交还 root。报告自身 bytes/SHA 由交还消息提供，以免自引用。
