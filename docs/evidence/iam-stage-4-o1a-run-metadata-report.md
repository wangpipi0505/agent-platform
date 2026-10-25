# O1a 独立运行观察元数据接缝交还

结论：**DONE_WITH_CONCERNS，交独立复审**。已实现唯一内部 `GET /internal/runs/{id}/observation-metadata`，以独立签名 query、真实 engine HTTP/security/handler、专用 PG 的只读 RR 控制投影验证。未开放外部 status/events。最后适用结果分别是 green02 的 common 7 例与 green03 的 engine 16 例，不能相加称一次 23 例全绿。

## 1. 范围、批准与精确变化

唯一 cwd 为 `G:/207/项目/算法重组工具/代码/agent-platform`；所有 shell 显式使用此目录。先读 AGENTS、implementation-brief、contracts/README、O1a 正式 brief；root 批准精确文件/API/SQL/RR/测试范围后才编辑 source。原后台 app-version 的 grant、initial、origin、POST 受理与本地推进目的保持；query 不 resume/forward 原 grant，不清 blocked，不派生未来 status 许可。

新增六文件：

- common authority `RunObservationSelectors.java`、对应 `RunObservationSelectorsTest.java`。
- engine api `RunObservationMetadataController.java`、application `RunObservationMetadataService.java`、domain `RunObservationMetadata.java`。
- engine infra 测试 `RunObservationMetadataHttpPostgresTest.java`。

修改四文件：common `ServiceAuthorization.java`、`agent-service-authority.json`、测试 fixture `LoopbackAuthority.java`；engine `RunAuthorityStore.java`。精确 bytes/SHA 见 [source-delta](iam-stage-4-o1a-run-metadata/source-delta.json)。没有修改 CurrentAuthorityClient、HttpCurrentAuthorityClient、TraceStore、gateway、迁移、POM、依赖/lock、前端、原 v1.2 或四契约；没有部署、commit、启动主业务进程。

共享 API 为 `RunObservationSelectors.metadata(SubjectKey verified, String publicRunId)`；kind、tuple、descriptor、digest 均按 brief。`requirePurpose(ServiceCall, caller, mode, source, origin)` 在 ServiceAuthorization 实际路由检查处双向绑定唯一 action/kind，并限定 gateway→engine、delegated、PORTAL/PORTAL。catalog 仅追加精确 GET 行，integrationAllowed=false。

controller 从已装配真实 guard/frame 取得授权，拒绝 raw query、bare `?`、body、非 REQUEST dispatcher 与 URI/template/method 不符。service 从 live verified actor 构造 SubjectKey，不使用 URL/query/header 自报身份。数据 record 恰七字段，owner/root 也是纯值；正 long、格式、root/id/version 与 hash 受域投影检查。服务本地序列化 data 的 UTF-8 bytes 超过 4096 时拒绝，不截断；实际 HTTP 正例为 485 bytes。

## 2. PG 列、事务与窗口

新 `RunAuthorityStore.observationMetadata(SubjectKey, String, RetainedAuthorization)` 的成功读取恰三条 SELECT，同一实际连接、隔离值 4（RR）、readOnly=true、autoCommit=false：

1. 按 public_id、verified tenant/principal 窄查 r.id/public_id/tenant_id/principal_id/trigger_type/mode；另取 debug_snapshot/eval_candidate、两组 controlled 列的 IS NOT NULL 布尔组合，以及 tool_call 的 EXISTS。只作已知未迁移用途排除，不取这些内容。
2. 复用 `load(db,id,true)`：r.id/public_id/tenant_id/principal_id/app_code/app_version_id，加 a.*。a.* 仅 run_authority 控制列：run_id、tenant_id、principal_id、grant_ref、grant_expires_at、initial_ceiling、resource_digest、source_kind、origin_authentication_id、app_version_id/no、root_kind/id、initial_ceiling_sha256、accepted_snapshot_sha256、admission_decision_id、authorization_version、operation_id、last_decision_id、last_checked_at、blocked_reason、retry_not_before、check_version。旧 grant/initial 原文仅内部校验，均不输出；不调用原执行 strict，也不把旧 grant 到期或 blocked 当独立 query 的拒绝依据。
3. 现 `scopes(db,binding)` 仅读三根 manifest 的 tenant_id/producer/kind/material_id/material_version/payload_sha256/scope_sha256/scope::text，核 owner、snapshot 版本、initial、原 admission decision/version、scope 摘要与父链。必须恰 run-snapshot/run-inputs/run-version-refs。

不选 run.snapshot/inputs/version_refs/output/error、node input/output、event payload 或 tool result；不调用 TraceStore.run/composeStatus，也不先读 payload 再过滤。实际 SQL 与连接状态保存在 [HTTP/PG observations](iam-stage-4-o1a-run-metadata/http-pg-observations.json) 和原始 runner/XML；测试 whitelist 对当前所有实际执行 SQL 逐条检查，并非旧局部 payload regex 的零计数推断。

service 入口先拒绝 ambient transaction，再做 `requireInitialFor` 在线刷新；store 防御性再次拒绝 ambient，并建立顶层只读 RR。事务入场、读取结束以及服务返回前均 `requireBound(proof)`，再核固定 query 目的。事务内无在线 authority 调用、写锁、全局隔离修改或重试。已窄读固定 Spring6.2.7 `doBegin`、`prepareConnectionForTransaction`、`handleExistingTransaction`，对应 [实际读源记录](iam-stage-4-o1a-run-metadata/upstream-read.json)。

404 对不存在/其他 owner/其他 tenant 一致；已归属但缺 authority、缺 scope、冗余绑定或 manifest 损坏为 403。query 正例保留原 blocked/check_version/last_decision_id，旧后台 grant 已过期仍可由新当前 query 授权读出。

## 3. 真实行为、测试命令与最后覆盖

均使用未修改的 `.tools/run-iam-module-tests.mjs`，root cwd、Java21、专用 `.tools/m2` 离线；无 `+` selector、跨业务 JAR、兄弟 target/classes 反射、旧 live helper 或旧全套。命令事前有完整必要源 SHA 清单与变化物理 overlay。

| 阶段 | 精确命令尾部（均以 `node .tools/run-iam-module-tests.mjs` 开头） | exit / 实际结果 |
|---|---|---|
| red01 | `iam-stage-o1a-red01 engine-runtime RunObservationMetadataHttpPostgresTest#querySelectorCannotBecomeExistingRunWritePermission` | 1；engine 1 failure / 0 error；真实行为红 |
| green01 | `iam-stage-o1a-green01 admin-common,engine-runtime RunObservationSelectorsTest,RunObservationMetadataHttpPostgresTest,MetadataSelectorsTest` | 1；common 7 过；engine 11 中 2 oracle 失败 |
| green02 | `iam-stage-o1a-green02 admin-common,engine-runtime RunObservationSelectorsTest,RunObservationMetadataHttpPostgresTest,MetadataSelectorsTest` | 0；common 7、engine 13，同一轮 20 例通过 |
| green03 | `iam-stage-o1a-green03 engine-runtime RunObservationMetadataHttpPostgresTest` | 0；仅 engine 16 / 0 failure / 0 error / 0 skip |

原始命令/cwd/exit/XML/SHA 与具名方法清单见 [stage-ledger](iam-stage-4-o1a-run-metadata/stage-ledger.json)。初始收集器在 red01 目录额外复制了一份旧 MetadataSelectorsTest XML；ledger 明标 `unexecutedXmlCopiedByInitialCollector`，不计 red01 执行或覆盖。后续收集器按实际 Running 行筛选。

首红真实到 engine HTTP：签名 run-observation selector 撞既有 POST，最终 403，但缺双向目的检查时已在线 authorize 一次，期望目的错配在 authorize 前拒绝（delta=0）失败。不是缺类/编译/装配红。green01 两失败分别为损坏认证 profile、过期 source/service JWT 的既有 401 被测试误写成 403，仅修 oracle，不作为生产缺陷红。在线 current 撤权为 403、authority unavailable 为 503；service 刷新前 proof 本地到期为 403，SQL 均零。fixture `authorityCalls` 计已解析请求，503 在解析前返回，因此不把该 counter 冒称所有失败 HTTP 尝试次数。

真实 PG `agent_platform_iam_o1a_metadata_v12` 首轮独立建库，生产迁移 V200–217 共 18 项。fixture 以真实签名 exchange/authorize、ServiceAuthorization frame、生产 `RunAuthorityStore.createPublished` 创建 protected FLOW 与 material_scope；受理 fixture 的 servlet frame 为本模块 MockHttpServletRequest，metadata 读取则是真实 loopback HTTP/security/handler/PG。没有启动 app 或 gateway 子进程、POST worker；不声称贯通生产 HTTP POST 或双服务 GET。

最后 16 个 HTTP/PG 方法覆盖：精确七字段/独立 query/真实 SQL、同 app/version 另 run、owner/tenant/path/digest 替换、错误 action/caller/target/source/origin/workload profile、query 撞 status/events/debug/POST、统一 404、缺 authority/三根/根与数字版本/owner/initial/snapshot/manifest 损坏、真实 PG 非法 root_kind/hash、raw query/bare?/body/forward/alias、service 当前刷新失败、读取后与 commit 后 expiry/frame-close、ambient 先于在线刷新拒绝、无 IAM bean 的有限 legacy 装配、实际 MVC 未装 guard 启动失败、两个版本数字超过 int、后续受理 run 不改旧根、自洽合法 resource_digest 替换的信任边界。

raw socket 确认 bare `?` 原样到 handler 且 queryString="" 后 400；非空 queryString="x=1" 亦 400。forward 在已有 frame 下被实际 guard 拒绝，未进入新投影。guard 缺失测试由现有 D1 coverage 机制使 Receiver 启动失败；不把仅 catalog 行当安装证明。legacy 是无 IAM bean 的局部真实 Spring context/实例检查，不是完整默认 Boot 启动验收。

实际正例 request 窗口 runs 50→50，nodes/attempts/events/tools 均 0→0，worker/runtime-client bean 均 0；该窗口三条 SQL 全为 SELECT。没有 provider/skill 服务或 worker 启动，未以不可观测的外部计数冒称完整联调。两字段实际为 appVersionId=4294967297、appVersionNo=4294967298。后续 run 受理不改变旧根；未启 app，不宣称真实发布指针切换。

## 4. 信任边界及未覆盖

可信前提仍是实际 PG 与 immutable 触发器。metadata-only 可验证已有冗余、类型、hash 格式、manifest 绑定；不能重算 resource_digest 与 snapshot candidate 的内容关系。测试明确绕过 immutable 后把 resource_digest 换为另一合法自洽 64hex，响应 **200、payload SELECT=0**，这是已记录的有限信任边界，不伪称能够拒绝。非法 hash、root kind/id 与 app_code/version 矛盾及 manifest/冗余不一致则真实拒绝；破坏测试临时移除/恢复专用库 CHECK 并恢复字段，无生产迁移变更。

metadata 不重读 snapshot 内部图来证明 pure FLOW，依赖可信受理与 immutable 存储，只排除已知控制标识。未来 run-snapshot 独立只读 status 授权由 O1b/O1c 实施；O2 SSE、RR1 INTEGRATION、RR2 模型恢复及真实 IAM/模型/本体/K8s 均未完成。

返回前本地终检在 service 返回数据前，随后 controller 包装并由 MVC 序列化；未扩称已验证所有后续 wire 首写边界。本地 proof 不能发现未再次查询的远端变化，不承诺瞬时撤权。LoopbackAuthority 是隔离协议 fixture，不代表生产 authority 已支持 run 关系解析。

## 5. 基线、重放与错误保留

完整必要源基线为 D4b lifecycle 572（后端 380 继承 D4a time-order，前端 192 不变），新增六文件后 578；只复制 10 文件 source overlay。target 基线为 D4a 1933，新增 15/修改 17 后逻辑 1948；仅 hash 382 个实际 compiler 输出/资源/具名报告/fixture JAR 候选并复制 32 变化物理文件，未重扫 target 全树、复制旧制品或前端依赖。没有重新 package boot JAR；生产 JAR 仍继承原基线，不能部署此测试类产物。精确谱系见 [composition](iam-stage-4-o1a-run-metadata/composition.json)、[target attribution](iam-stage-4-o1a-run-metadata/target-attribution.json)。

首红 source-before 为 573。作者在未 poll shell session 时误报“等待运行时新增 common 测试”；实际 red Finished at 为 2026-10-21T05:13:19+08:00，而测试文件 CreationTimeUtc 为 2026-10-20T21:13:35.759Z，晚 **16759ms**，真实 Maven 子进程内未交错编辑 source。原 573 清单不倒改，后续 578 清单独立保存；[时间更正记录](iam-stage-4-o1a-run-metadata/first-red-timing-correction.json) 保留初始误判及原始依据。

重放实际 cwd 始终为唯一工程 ROOT，发生顺序完整保留：

1. 第一 full-context text patch `--check` 0、apply 0；后置 bytes 核验失败：工程 `* text=auto eol=lf` 导致三个混合 EOL 文件字节不同，未再次执行成功的首次 apply。
2. 按 root 建议用自有 private bare evidence repo，info/attributes=`* -text -diff`，仅对三份私有 replay 文件生成 binary EOL repair。repair01 check 1、未 apply；root cwd 相对目标仍需显式 directory 前缀。root 后来澄清最初的 GIT_WORK_TREE 建议不完整，错误及更正均保留。
3. repair02 使用实际记录的 private GIT_DIR/private replay GIT_WORK_TREE，加显式 `--directory=.local/iam-o1a-run-metadata/replay-overlay`，check 0、apply 0；未按后来的参考方案再执行任何等价命令。
4. 两 patch 顺序链后，完整逻辑 **578 names/bytes/SHA 全等**；实际 replay 仅 10 文件，其他条目继承已验 immutable 基线，没有复制 578 全树或用直接覆盖代替补丁。真实 source/target 均未被 repair 修改。

见 [replay-check](iam-stage-4-o1a-run-metadata/replay-check.json)、两份 patch、首次 mismatch JSON 与所有 check/apply 原始日志。EOL/目标前缀错误只归重放工具阶段，不伪作产品失败；未因此重跑测试或成功物理阶段。早期 read-only rg 通配/参数错误亦单列 discovery，不计行为红。

## 6. 资源、封存与交还

四轮 test JVM/engine port：red01 51304/58903；green01 4064/58021；green02 28608/56337；green03 60128/59903。原日志均有 ready/close，最终精确 PID 均不存在、对应端口均无监听。LoopbackAuthority.close 停止 authority/resolver 并关闭 policy HttpClient；各 decoder/client/context 关闭，所属 JVM 已退出。authority/resolver 的随机端口未逐个打印，不能补造；PG 专用数据库保留，未启动/停止 PG 或其他主进程。见 [resource-check](iam-stage-4-o1a-run-metadata/resource-check.json)。

source、对应 target 及全部 own 证据在最终 seal 后冻结并交还唯一写权。own inventory 含作者专用 `.local/iam-o1a-run-metadata/`、证据目录和本报告，排除自身 manifest/seal 以免自引用；seal 记录其精确 bytes/SHA。后续 root 只需有限审计与建立独立完整上下文 review package；本交还不是完整 O1/O2、Task4–6、17 功能/34 任务/30 验收签收。
