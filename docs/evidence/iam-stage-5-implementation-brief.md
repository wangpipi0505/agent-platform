# G6 E / Task5 原文移交准备稿

尚未授权实施。D2 正在执行；D3 的真实资源/父子调用和出站迁移还需闭合。此文件只将已批准计划 Task5 原文提取为后续交接文件，不修改原设计、契约、计划或业务代码。

唯一工作根 G:/207/项目/算法重组工具/代码/agent-platform；用户已授权持续开发和隔离端点，无真实IAM/K8s环境，不再索要环境。PG唯一权威、HTTP跨域、Java21/Boot3.5/Vue3.5及原模块分层保持。先读 AGENTS.md、iam-stage-5-ceiling-handoff-note.md 和 iam-stage-4-d3-root-handoffs.md；后二文件是尚未实现的跨任务缺口，不能臆造公共API。

common 的 MaterialRef/MaterialScopeV1/AuthorizedMaterial/MaterialAuthorization、ResponseMaterialPermit/ResponseMaterialAccess 等纯类型已在C落地；真实PG写入/消费尚未实现，应复用精确实际API，不重复新造。实际RunAuthority、FactAuthority、同快照metadata-first/material表与完整初始上限来源、真实流sidecar、批准者冻结confirmRoles仍需实施。完整初始ceiling必须从实际已验签名且与本次grant/operation/hash绑定的证据保留；hash/currentActor/旧guard_ctx均不能反推。

原Task5候选迁移号是顺序约束，非预占；正式开工前核实际源与专用测试库Flyway，不能重写已应用迁移或开outOfOrder。原四契约/v1.2和生产闭锁不动，任何新内部profile接缝先报root具体接口。后续root正式交接会补确切写入范围、D3实际API、测试库/受控runner与报告路径。当前不应运行本文件中的命令。

## 已批准 Task5 原文（逐字提取）

### Task 5: 持久委托、恢复、历史材料与批准者新鲜度

**Files:**
- Create: `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java`、`infra/RunAuthorityStore.java`；`skill-registry/src/main/java/cn/agentplatform/skill/application/FactAuthority.java`。
- Create common: `admin-common/src/main/java/cn/agentplatform/common/authority/MaterialRef.java`、`MaterialScopeV1.java`、`AuthorizedMaterial.java`、`MaterialAuthorization.java`（各自同目录）。Create each domain: `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java`、`skill-registry/src/main/java/cn/agentplatform/skill/infra/MaterialScopeStore.java`、`model-gateway/src/main/java/cn/agentplatform/model/infra/MaterialScopeStore.java` 及各模块同包根的 `api/MaterialScopeController.java`；所有权与scope校验在各域内执行，不能导入其他业务jar。
- Create migrations, **按顺序候选但未占位**: `engine-runtime/src/main/resources/db/migration/V216__run_authority_context.sql`、`skill-registry/src/main/resources/db/migration/V312__fact_authority_context.sql`。本次规划最初扫描 engine 到 V215、skill 到 V310，root 同时实施算法 V311；仅当算法 V311 已冻结且 G6 确实先于 G2 实施时这两个候选成立。实施前重新扫描实际迁移及 Flyway history，仅新工程已授权测试库；若顺序改变，选各 schema 当时下一可用版本并在阶段证据中同步本计划路径引用。绝不重写已执行迁移、不先上高号再补低号、不启用 outOfOrder 规避排序。
- Modify engine: `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`、`application/ApprovalService.java`、`application/RunScheduler.java`、`application/ReplayService.java`、`application/ContextAssembler.java`、`application/ChatHistory.java`、`application/ChatSessions.java`、`application/ModelExchange.java`、`application/ContextProjection.java`、`application/ControlledReplay.java`、`infra/TraceStore.java`、`infra/ExecutionRecovery.java`、`infra/ToolCalls.java`；模型/技能实际发送检查点为已定位的 `application/AgentDriver.java`、`application/FlowDriver.java`，禁止新增第二套 driver。这里的 application/infra 同属该 engine Java 包根。
- Modify skill: `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillOutbox.java`、`application/SkillExecution.java`、`application/SkillCallbacks.java`、`application/ReconciliationProofs.java`、`infra/ArtifactStore.java`、`api/ArtifactController.java`；后五路径同属该 skill Java 包根，下载与历史复用也要核当前 scope，不能只核 tenant/owner。
- Modify model: `model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java`、`infra/ModelStore.java`（后者同属model包根；机器 health 独立 action、完整用户context/历史材料标签当前核实及结果scope持久）。本阶段增加model schema下一连续版本的material_scope迁移，先扫描实际版本再定名，不抢高号；engine/skill material_scope并入本任务尚未执行的候选迁移。
- Test: `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RecoveryAuthorityTest.java`、`ApprovalAuthorityTest.java`；`skill-registry/src/test/java/cn/agentplatform/skill/application/OutboxAuthorityTest.java`。
- Test additions: `engine-runtime/src/test/java/cn/agentplatform/engine/infra/MaterialScopeHistoryTest.java`、`MaterialScopeReplayTest.java`；`skill-registry/src/test/java/cn/agentplatform/skill/infra/ArtifactAuthorityHttpTest.java`；`model-gateway/src/test/java/cn/agentplatform/model/application/MaterialScopeEgressTest.java`。

**Interfaces:** `RunAuthority.current(long runId,ServiceCall call)` 返回当前 VerifiedAuthority；`RunAuthority.approver(long callId)` 取持久 approvedBy 和批准摘要后调用 §2.3 approver。`FactAuthority.current(String tenant,long callId,long attemptId,ServiceCall call)` 只能核已有事实对应绑定。都禁止从历史 Actor 单独签票。

材料接口以 §2.5 为准：`MaterialAuthorization.require(MaterialScopeV1,VerifiedAuthority)` 不返回payload，只验owner/当前有效性/集合覆盖；各域 `MaterialScopeStore.write(MaterialScopeV1)` 必须处于调用方业务事务，`read(MaterialRef,VerifiedAuthority)` 先判权再由同域读取绑定payload，返回AuthorizedMaterial。仅原响应完成使用 §2.4.1 的 `nextEventRef`、`readForResponse`、`acceptForResponse` 和 `MaterialAuthorization.requireForResponse`，后者返回的材料只能由原 response holder 写出或记录在途事实，不可提交给新动作 assembler。`ChatHistory.load(store,run,current)` 不提供无current的生产重载；`ContextAssembler.assemble` 的历史参数改为AuthorizedMaterial集合，输出完整material refs/manifest摘要，不接受裸旧Map冒充已授权材料。

- [ ] 写 PG+loopback authority 失败测试：旧 run 无 grant_ref，历史有 region A/B 而当前只有 A，approvedBy 另一人且其角色撤销，grant 过期，authority 503，队列延迟后证据过期；断言不调用外部模型/技能，不改变原事实为成功、不新增可发送 attempt、不吞兄弟调用/取消状态。
- [ ] 执行 `mvn.cmd -B -ntp -pl engine-runtime,skill-registry -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=RecoveryAuthorityTest,ApprovalAuthorityTest,OutboxAuthorityTest" -Dsurefire.failIfNoSpecifiedTests=false`，使用已授权的新工程专用 PG 测试库，预期旧 guard 恢复/空 Actor 取票路径产生红色；此命令不启动数据库。
- [ ] 创建新增内部 side table，避免给原契约对象塞字段。engine 最小结构：

```sql
create table engine.run_authority (
  run_id bigint primary key references engine.run(id),
  tenant_id varchar(64) not null,
  principal_id varchar(64) not null,
  grant_ref text not null,
  grant_expires_at timestamptz not null,
  initial_ceiling jsonb not null,
  resource_digest varchar(64) not null,
  source_kind varchar(24) not null,
  origin_authentication_id varchar(128) not null,
  last_decision_id varchar(128),
  last_checked_at timestamptz,
  blocked_reason varchar(64)
);
```

tenant/principal 的 64 长度来自实际 `V200__engine.sql:7`，实施前核后续迁移没有更改；grant_ref 不写日志，仅授权调用者凭据可使用。这里 source_kind 保存原认证来源（例如PORTAL）和 origin_authentication_id 审计，不据此给新后台流打PORTAL标签；新动作 source_kind/operation 仅取authority本次签名。初次exchange也返回来源明确的OperationBinding；纯workload无用户operation且不得进入用户路径。skill side table 主键 `(tenant_id,call_id,attempt_id)`，保存同一 delegated reference 的受限子引用、subject/有效期/原 ceiling/绑定摘要，不保存用户 JWT、API key 或明文 refresh token。收到首次已验受限票据时与业务创建同事务落表；没有授权 row 的历史数据不自动回填“当前有效”。新的 G2 attempt 必须重新绑定当前许可和授权，不能覆盖原代记录。
- [ ] **R2 可信材料写入红→绿：** 先写scope缺失/摘要错/事务回滚测试，再按 §2.5 在run/turn、response/checkpoint、output/error/event、model invocation/SUMMARY、skill fact、artifact READY写入同事务metadata。材料初始full ceiling只取签名verified authority，不从guard_ctx补录；故意在payload与scope之间抛异常，断言两者均未公开，artifact不得READY。每个parent要求并集，dataLabels只增不减。命令：`mvn.cmd -B -ntp -pl engine-runtime,skill-registry,model-gateway -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=MaterialScopeHistoryTest,MaterialScopeReplayTest,ArtifactAuthorityHttpTest,MaterialScopeEgressTest" -Dsurefire.failIfNoSpecifiedTests=false`。
- [ ] **R2 ChatHistory真实消费红→绿：** 构造真实ChatSessions.turn创建时A/B、在AgentDriver调用ChatHistory.load前闩锁暂停，authority收窄A后放行；再覆盖known requestId复用、缺run的reserved turn恢复和旧checkpoint。删除load旧对旧授权比较，改用刚取current证据逐material核验；断言模型/summary/tokenizer/provider网络发送均0。相同A/B合法多轮、同scope恢复和已声明EDITOR批准正例必须成功，证明新metadata可用。
- [ ] **R2 派生/下载/回放红→绿：** 走真实ModelExchange SUMMARY→ContextProjection→下一MAIN、TraceStore checkpoint→recovery、ArtifactStore READY→HTTP下载、ReplayService.log/get/reexecute。摘要和artifact summary保留全部parent要求；被压缩的B内容使当前A不能进入SUMMARY或MAIN；同scope但provider缺旧dataLabels也不得发送。scope缺项/版本digest不符时下载对象流打开数=0、回放业务输出字节=0；不得只测集合函数。重跑上一材料命令，全绿后才视为E的材料子任务完成。
- [ ] 用 `RunAuthority.current` 替换 driveRecovered 旧 Actor 的权限来源，历史 guard_ctx 保留审计；在实际模型/技能动作前再次检查当前权限和历史材料 required scope。恢复控制骨架：

```java
VerifiedAuthority current = runAuthority.current(runId, call);
if (!current.expiresAt().isAfter(clock.instant()))
    throw new PlatformException(403, "AUTHORITY_EXPIRED", "恢复授权已过期");
drive(runId, current.actor());
```

此调用后必须把 `current` 显式传至 worker/client，不能只传 actor 又在 ServiceTokens.issue 偷偷补权。503 保留可恢复阻断原因及调度退避；403 权限不足保留原事实并停止推进，RunScheduler 的可领候选必须排除被 authority side row 阻断的运行，后续显式操作或受控重核才清除阻断。不能加新的 run 枚举状态改写冻结执行语义，也不能反复占槽空转。缺 grant 的旧 run 不自动续跑；允许通过既有明确用户操作重新授权同一资源和历史上限后绑定新 grant，不能后台迁移猜测授权。
- [ ] ExecutionRecovery.fact-read、SkillOutbox.fact-deliver 使用独立 action，禁止这类证据用于 execute/claim/submit；scope 被撤后事实继续保存在 PG，不把事实内容通过回调泄露。若将来希望撤权后仍允许纯机器事实对账，需要单独签收独立服务授权与数据边界，本计划初版默认拒绝。
- [ ] G2 接口对接：取证/排队前/领取前/提交前核当前主体与 approvedBy；短事务内重验 expiry、资源摘要、lease、expectedAttempt、原批准到期。role version 变化后必须重新核实原批准者满足本 call 原声明的实际 `roles/confirmRoles`；ADMIN/EDITOR 没有自动豁免，但原声明为 EDITOR 时拥有当前 EDITOR 的合法批准者应继续通过。测试同时保留原声明 EDITOR 正例和原声明 APPROVER 却仅有 EDITOR 的负例，不把全部确认规则硬编码成 APPROVER。未来复用只认可原批准者当前证明及未过期原批准快照，不用发起人身份代替；批准者无当前证据沿既有确认流程重新确认。
- [ ] 重跑上述命令；撤权→拒绝→可信重新授权→恢复，仅已有事实可投递一次，新业务发送计数仍为 0；另测合法首次调用 1 次。审查历史结果、附件、检查点、原 prompt/context 的 scope 覆盖，scope 缩小但仍读取原大范围结果必须为红色阻断项。

- [ ] **RR2 后台新动作红→绿：** RunAuthority.current 在每个真实新动作点调用resume+authorize，保存/传递签名DELEGATED operation；同动作RuntimeClients取票改forward，绝不改原PORTAL observer holder。IamRecoveryHttpTest 以fixture Clock设置原portal exp=t+10s、grant exp=t+300s、authority模型operation上限120s；推进到t+11s后从真实driveRecovered进入ModelExchange→RuntimeClients→model stream（首个provider外发须实际发生且仅1次），断言源DELEGATED、origin PORTAL、签名operation截止≤t+131s且各hop不变、用户JWT未持久/未重放、同scope材料合法、chunk/final成功。对照撤grant/当前scope收窄/伪来源/改截止拒绝且provider=0；已进入DELEGATED流后撤grant/503/operation到期则后续输出0。并行原PORTAL观察在t+10s仍关闭，后台成功不使其恢复。测试用有限Clock/闩锁，无真实IAM；命令纳入Task6 IamRecoveryHttpTest，E完成需具备该接口与真实路径测试。

