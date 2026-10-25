### Spec Compliance

- ❌ Issues found：初始模型输入 producer 的主要边界已实现，但仍有三项可从源码确认的缺口：写入未绑定最新已持久化的 current 检查结果（I1）；模板展开在 2 MiB 限制前可放大分配（I2）；params 分支未拒绝明显错误的参数类型（I3）。独立 spec verdict：Needs fixes。
- ⚠️ Cannot verify from diff：作者报告的 RED/GREEN 实际执行身份、最终源到有限 target/fixture JAR 的对应、217→218 适配与数据库替换的精确字节、过滤 classpath 的全清单、native check/apply、protected8、资源归还和端点零外发运行记录，由 root 的独立物理/epoch 审计闭环。本评审没有复跑测试、构建、浏览器、PG、runtime 或 native Git。
- ⚠️ 验收范围：仅本次 history-free initial-chat-main-v1 producer 和认证元数据 GET；不签全平台、默认 launcher、公开 CHAT/modelRefs/LLM/AGENT、driver、model consumer、真实 IAM/K8s 或容量验收。

### Strengths

- engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelInputMaterials.java:40：prepareInitial 仅接收 runId，先拒绝现存调用者事务，再从既有 RunAuthority.currentRetained 取得 proof；事务内没有新增 IAM HTTP 调用。:68 的 beforeCommit 与 :84 后提交检查区分真实回滚和提交后拒绝返回，没有把持久事实冒充发送许可。
- engine-runtime/src/main/java/cn/agentplatform/engine/infra/ModelInputMaterialStore.java:180：insertInitial 重新锁定并读取真实 run/binding/三根材料，比对 supplied 数据并重新组装 expected request；public InitialSource 不直接赋予任意 request/scope 写权。:586 的 ConnectionHolder rollback-only 处理支持调用者捕获异常后仍整体回滚；engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:175 真实注入第二次 scope INSERT 失败并断言双表无残留。
- engine-runtime/src/main/java/cn/agentplatform/engine/infra/ModelInputMaterialStore.java:317、:358：GET 使用独立只读 repeatable-read、owner/key 限定查询和元数据探长，未 SELECT request/snapshot/inputs/version_refs 正文；同时校验 manifest、scope、三父 closure、根和完整原始义务。engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialHttpTest.java:195 走实际控制器、canonical catalog、签名 authority 与 TLS，校验 headers、无 payload 和 fresh 第二次读取。
- engine-runtime/src/main/java/cn/agentplatform/engine/api/ModelInputMaterialController.java:28：kind、唯一 version、重复/额外 query、精确 id/hash 均显式限制；:37 捕获请求线程内 initial proof。ModelInputMaterialStore.java:560 的独立 export-purpose 检查没有放宽 RunAuthorityStore.strict 的 local run.advance 语义。catalog 仅新增实际控制器一条 delegated model→engine 路由，并如实标注未来 consumer。
- engine-runtime/src/main/resources/db/migration/V218__initial_model_input_material.sql:2：request/scope 由同一事务追加，unique(run_id)、UPDATE/DELETE trigger、正 ID/hash/object/size CHECK 限定事实；新 kind/version 扩展保持原格式分支。两份既有 PG 测试的 diff 各仅一处已授权 217→218。
- engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialHttpTest.java:454、:480：有限 legacy bean 构造缺少 IAM 时默认拒绝，既有默认 IAM guard 保持拒绝；这两项测试没有充分性外推为完整默认应用已可启动。

### Issues

#### Critical (Must Fix)

- 无已确认 Critical 问题。

#### Important (Should Fix)

- **I1 — 写入未把 retained proof 绑定到当前已持久化的成功检查。** 位置：engine-runtime/src/main/java/cn/agentplatform/engine/infra/ModelInputMaterialStore.java:540（localBinding），关联 :84、:180。该检查仅验证 strict tuple、blocked/retry、期限和父 scope，没有验证锁定 run_authority.last_decision_id 等于 proof.current().decisionId()。RunAuthorityStore.Binding 的两次相等比较只证明锁内读到同一份 binding，不能证明它仍属于事务外获得 P1 的 CAS。具体顺序：P1 的 currentRetained 成功；在 P1 获取 run 锁前，P2 对同一 run 完成另一轮成功 current/CAS，并带有新增 dataLabels 义务；P1 随后锁定新版本行，blocked/retry 仍为空，旧 proof 仍未到期，因此可按 P1 旧标签创建永久材料。已有 MaterialScopeStore.requireWriter 在 engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java:245 明确校验 persisted last_decision_id，该新 writer 绕开了此不变量。影响：较新的已知授权观察不能使旧写入证明失效，最新标签义务可能被遗漏；即使未来 GET 会拒绝，已违反当前生产事实的要求。修法：在同一已锁定 authority 行上复用 requireWriter 的成功检查绑定，或以等价的局部 SQL 校验 decisionId/成功检查版本，在源 payload 读取前及写入时保持不变量；不要修改公共 strict 接受用途。补有意义的 P1→P2→P1 陈旧证明拒绝用例，并以 P2 正向为对照。现有同一个 retained proof 的并发用例（ModelInputMaterialPostgresTest.java:512）不能证明此竞争被拒绝。

- **I2 — request 上限在模板展开后才检查，允许源内的小输入触发大分配。** 位置：engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelInputMaterials.java:187、:196。每份 source 的 1 MiB 检查是有效的，但 ContextAssembler 调用先完成整段 prompt 替换、messages JSON 和估算，再执行 bounded(request, 2 MiB)。定点依赖检查确认 ContextAssembler.java:31 调用 Values.resolve，engine-runtime/src/main/java/cn/agentplatform/engine/domain/Values.java:35 直接 String.replace 全部匹配。一个约 53 KiB、包含 4096 个 `${input.name}` 的合法 frozen systemPrompt，加一个约 256 KiB 的 name，均在 source 限制内，却能先展开约 1 GiB 的字符串；更大合法组合会在大小检查前耗尽 heap。影响：2 MiB 有界 profile 不能提供承诺的展开/分配边界，可能在持有 PG 行锁时消耗巨大内存或 OOM；最终抛错/回滚无法补救该资源占用。修法：在本有限 profile 内，在调用通用 assembler 前以有界方式计算/验证展开后 UTF-8 与 JSON 转义成本，或使用保持相同语义的有界展开，超过剩余 request 预算立即拒绝；不要扩大为通用框架或修改未授权公共类。补两个源都低于 1 MiB、但展开必超限的负向与接近上限的正向；现有 source/depth 测试没有覆盖此放大。

- **I3 — params 只校验键名，没有校验对应值，错误配置可成为不可变 request。** 位置：engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelInputMaterials.java:148。:134 对 sampling.temperature/topP 做数字类型检查，而 params 分支仅枚举允许的字段名。因此 `params.temperature="bad"`、`params.topP={}`、`params.stop={}` 都通过该 profile 校验；未设置覆盖它们的 sampling 字段时，既有 ContextAssembler.java:79–91 会原样放入 request.params。影响：绑定计划要求显式拒绝 malformed/unsupported config，但这些配置会消耗该 run 唯一、不可变的初始材料槽，得到无法按普通模型参数解释的已受理请求；后续消费者拒绝不能在本行内纠正。修法：在本类为接受的 params 逐项校验类型/结构，并与同义 sampling/output 入口保持一致，明确禁止未支持形状；无需新增模型网关行为或推定未规定的 provider 专属数值范围。补错误类型不落双表的断言和有效参数原样保留的正向。当前 rejectsUnsupportedAndMismatchedFrozenConfiguration 只覆盖顶层禁止功能、模型关联与摘要等，未证明此要求。

#### Minor (Nice to Have)

- 无需另列影响接受范围的 Minor 项；跨 JVM 测试的证明范围和历史执行噪声见下文，不将未证实风险伪装成产品缺陷。

### Digest interoperability assessment

- **核心跨 JVM scopeDigest 疑点不成立。** engine-runtime/src/main/java/cn/agentplatform/engine/domain/ModelInputManifest.java:59–74 为 manifest wire 的 roles、entitlement values、labels 使用稳定 TreeSet；common MaterialScopeV1.java:8–10 的 Set.copyOf 确实不保序，Json.java:73–76 也保留数组顺序，但不能到此便认定缺陷。实际公共 MaterialAuthorization.scopeDigest（admin-common/src/main/java/cn/agentplatform/common/authority/MaterialAuthorization.java:28–34）调用 AuthorityProtocol.digest；admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProtocol.java:39–44 在 JSON 转换前递归处理 Map/List/Set，并按 Json.canonical 排序 Set 元素。因此另一个 JVM 重构 parent/current MaterialScopeV1 后，集合迭代顺序不会改变公共 scopeDigest；require 的摘要检查仍成立。parentScopeSha256 是显式有序列表，不应当被当作 Set 重排。
- **wire digest 与 typed scope digest 分别验证。** manifestSha256 是排序后的完整 manifest wire（不含自身）摘要；scopeSha256 按公共 Set 语义算法验证。对于包含需 JSON 转义字符的值，TreeSet 字符序与 canonical JSON 字符串比较序可能不同，所以消费者不应自行对 raw scope wire 去掉 scopeSha256 后直接 SHA，替代公共 scopeDigest。本任务契约已规定 common scopeDigest，当前未实现 model consumer，不据此新增消费者缺陷。
- engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java:332 与 ModelInputMaterialFixture.java:231 的两个独立 JVM 仅复算 requestSha、manifestSha、manifestWireSha；并未在子 JVM 调 MaterialAuthorization.scopeDigest/require。公共 scope 摘要由同 JVM replay 测试 :53 验证。这是现有实测证据的准确边界；结合上述完整静态调用链可排除集合顺序缺陷，不能把这些测试称为已执行的跨进程公共 scope 校验。

### Assessment

**Task quality:** Needs fixes。

**Reasoning:** 已有真实授权、PG 原子写入、元数据读取和多项失败路径验证，闭锁的范围也表达准确；但 I1 的成功检查绑定、I2 的展开前资源界限和 I3 的参数类型拒绝均属于此有限 producer 的明确职责，必须修复后才可接受。

### Checks, named dependencies and provenance limits

- 完整读取正式 brief、绑定架构、task-review brief、reviewer template、作者报告、frozen-inputs、六份 regression-isolation 补充；应用 code-review 技能与本仓 AGENTS.md。完整 native diff `.local/iam-model-input-producer/final/source-644-to-652.patch` 按互不重叠区间 1–330、331–750、751–1170、1171–1610、1611–2060、2061–2505 读至 EOF 一次；身份采用 root 已核 110355 bytes / SHA256 2f9b228d15378f218ef74788b281b061232b46cfb44f76793d235c7b877a4d3c。全文 source 包未冒充 diff；没有额外重读变更文件正文，后续只从 patch 提取定位行号。
- 定点未变依赖检查一：为 scope 跨 JVM/public digest 风险读取 MaterialAuthorization、MaterialScopeV1、Json 与 AuthorityProtocol.digest/ceiling；结果如上，无公共算法变化要求。
- 定点未变依赖检查二：为写入成功 current/CAS 与 closure 风险读取 RunAuthority.currentRetained、RunAuthorityStore.load/recordCheck/strict/requireWrite、MaterialScopeStore.scopes/validate/withDigest/requireWriter；三父 exact closure 和完整原始 ceiling 由既有代码校验，新增 localBinding 缺少最新成功 decision 绑定形成 I1。
- 定点未变依赖检查三：为 request 配置映射及展开分配风险读取 ContextAssembler.assemble、Values.resolve/path、FrozenModels.reference；I2/I3 不依赖假设未来模型发送实现。
- 定点未变依赖检查四：为 export 是否误用独立 grant/operation 的具体疑问检查 AuthorityContext.requireInitialFor/requireBound、RetainedAuthorization.requireVerified、LoopbackAuthority exchange/resume 的 origin/operation 构造。resume 会创建新 operationId；不能要求它等于 admission operationId。相同 originAuthenticationId 代表同次原认证，可派生多个 grants；当前出口要求受验 current、同 owner/root/原 initial/origin 和自身 operation.resource 等于 call.resource。未证明缺少 b.delegation==current.delegation 在该可重复元数据契约下导致越权，故不列缺陷；不得把它与 I1 的本地写入 latest decision 不变量混为一谈。
- 保留作者报告中的限制：27 个独立新增用例来自多个不同 epoch，非最终同一源码一次完整 27/27；92 个旧回归来自数据库/迁移顶点适配及分阶段纠错，非未适配整套一次成功。RunAdmissionHttpTest 使用已授权旧测试组件过滤 classpath；不能据此声称默认 launcher 通过。common route 测试正常模块结果不等于 engine 中失败上下文通过。
- 保留构建/诊断噪声：原 target 的 Boot repackage 失败不能被同源独立 package-work 成功覆盖，失败根因不能无证据归于主进程文件锁；preflight 路径/schema-marker、fixture bean 碰撞、跨类加载器失败不算产品行为 RED。JFR 文件读取的零事件不独自证明任意零 SecretVault 方法调用；静态无 egress 调用入口与接线 canary 是补充证据。root 仍需审核这些既有记录，评审未重跑它们。
- 唯一写入为 `docs/evidence/review-iam-model-input-producer.md`；未修改任何源码、证据原件、数据库或运行资源。报告落盘冻结后归还唯一报告写权；无后台工作。