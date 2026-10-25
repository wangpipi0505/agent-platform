# 执行增量独立 spec / quality 审查

日期：2026-10-20。结论：**Request changes**。原运行时 R3 的 FAILED 恢复缺陷及原模型 F0–F6 七项具体发现可以关闭；本轮另外发现 **1 项 P1、2 项 P2**，当前增量不能据此宣布通过。root 已确认接收三项新发现并安排修复；本报告不把“计划修复”计作关闭。

## 范围、方法与证据限制

- 按 code-review 技能先读本工程 AGENTS.md、docs/implementation-brief.md、contracts/README.md；只访问独立新工程，未访问相邻旧工程的代码、配置、数据库或进程。
- 冻结依据为 contracts/snapshot/detailed-design-v1.2.md：模型管理/计量/配额 §1.1–1.8（567–618 行）、技能守卫与 Mock（202、999、1145、2282 行）、请求持久化与上下文（461、1723 行）、会话隔离（2175–2176 行）、三级回放（2582–2584、2716–2721、2890–2896 行）。未扩展冻结规格。
- 审查明确增量为 engine V205–V211 的模型引用、上下文、CHAT、调试控制、复现与恢复；skill authority-only Mock / LAST_REAL / copyToRun 与受控连接；model V304 与配额恢复；gateway 会话路由和集成权限。已读前两轮 runtime 审查、model-bindings 审查及本轮三份实现报告。
- 本仓库没有可用 HEAD 基线，未伪造 Git diff；以指定文件、实际调用链、迁移和定向测试源码审查。只新增本报告，不改业务、测试、配置，不启动/停止进程，不访问运行 dev 库，不重跑完整测试。
- 以下新发现均为可达路径的**静态证据**，不是本审查者已执行的故障复现。已直接读取现存 Surefire XML：ExecutionRecoveryTest 4 项、ModelCallsPostgresTest 24 项、ModelQuotaRedisTest 13 项、ModelQuotaPostgresTest 3 项，均 0 failures/errors/skipped；测试文件由其他工作者运行，本报告不将其计作自己的执行证据。
- 协作者正实时稳定源码。下列行号对应发现时源码，关闭须复核修复后的实现和新回归；后续报告不得仅凭旧绿色计数覆盖本轮发现。

## 新发现

### I1 / P1：历史技能结果复用没有重核当前有效数据范围

**LAST_REAL 位置**：skill-registry/src/main/java/cn/agentplatform/skill/application/SkillExecution.java:110、:196、:311–338；关联同文件 :248、SkillRules.java:39–61、engine-runtime/.../application/DebugControls.java:90–105。

参数 guard 会对数组参数与已验 Actor 属性求交，产生 `secured`；真实适配器使用该求交结果。LAST_REAL 却只将原始 `argsDigest` 传给 recordedMock，比较源事实的原始 args_digest、版本、合同、principal、run 和结果摘要，未比较源 request_snapshot.guardedArguments 与本次 secured，也未验证源授权范围仍处于当前授权范围内。

可触发路径：同一主体首次具有 region=[A,B]，用原始参数 region=[A,B] 执行真实技能，得到含 B 的数据；随后其授权缩为 [A]，再以相同原始参数使用 LAST_REAL。当前 guard 合法收窄为 [A]，不会报 NO_PERMISSION，但 recordedMock 仍命中原始参数摘要并返回旧 A+B 结果。附件的 copyToRun 只验证当前 tenant/principal/源 run/有效期，故会把同一超范围内容复制为新 run 的附件。非 Mock 成功事实与结果摘要校验能证明来源未被替换，不能证明当前仍获授权。此路径也适用于同一主体在不同应用具有不同属性范围；不能简单把“principal 相等”当作 scope 相等。

**受控复现的同类位置**：engine-runtime/src/main/java/cn/agentplatform/engine/application/ControlledReplay.java:28–44、:139–158。guard 只要求当前 Actor 与声明属性清单存在至少一个交集；后续对 args 只做 Schema 和原始摘要比较。原合同 region=[A,B]、原参数 [A,B]、当前 Actor=[A] 时仍可取回旧宽范围响应，未执行真实技能路径的参数求交。该增量声称“当前守卫仍有效”，实际语义与真实执行不一致。

建议：复用前依据冻结合同重算当前有效参数与授权范围，要求源持久守卫证据不比当前范围更宽；缺证据时拒绝复用。至少比较源 guardedArguments 与本次 secured，且对合同声明的守卫属性冻结/比较相关授权集，不能只保存或比较角色与 raw args。受控复现应采用同一范围判定且保持零外调。不要通过直接放宽源数据读取检查来兼容旧记录。

建议最小回归：真实 PG 源事实为 A+B；同 principal 缩到 A 后，原 raw args 不变的 LAST_REAL 必须拒绝，且无新增外调/附件复制。受控复现同样拒绝范围收缩；等价授权集按规范化集合比较；跨 principal/tenant、源摘要篡改、合同不符仍拒绝。

### I2 / P2：受控复现把信封投影当原始输出校验，并复制旧归属信封

位置：engine-runtime/src/main/java/cn/agentplatform/engine/application/ControlledReplay.java:164–182。关联 skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java:180–215、:61–81；ArtifactStore.java:286–294；engine-runtime/.../application/RunService.java 的当前 run fileRefs 投影。

真实技能先按 outputSchema 校验原始 HTTP JSON，再由 returnSpec.structuredPath 生成 structured；大结果流式转存后 structured 为 null。受控复现却无条件用同一个原始 outputSchema 校验 result.structured。例如原始 Schema 要求 `{data:{value:number}}`，returnSpec 取 `$.data`，真实成功信封为 structured=`{value:42}`；复现必然 OUTPUT_INVALID。原始对象 Schema 的附件型成功事实也会因 null 失败。测试 ControlledReplayTest 仅使用原始 Schema 与 structured 同形的普通对象，未覆盖这两种合法成功事实。

即使 Schema 宽松到允许通过，:182 仍把源 Envelope 原封写给新调用，owner.runId/node/attempt/call 及 fileRefs 仍属旧 run。当前 run 的受控下载地址不对应这些旧附件；不能将此行为写为“已生成新 run 工件归属”。LAST_REAL 的独立复制机制没有被该复现入口使用。需要保留用于模型输入重建的源材料与新执行 trace 的归属之间的明确关系，不能通过改坏历史工具消息使下一轮模型材料不匹配。

建议：对保存的信封使用对应的投影/信封校验，原始 Schema 验收依赖同合同的可信原事实或持久原始材料；不能再把 structured 冒充原始响应。为新 trace 明确生成当前归属信封和附件引用，保留源 provenance；若附件需要服务读取，必须先明确如何满足受控复现零 HTTP 的边界，暂不支持的类型应在创建复现前明确拒绝，而非执行中误报输出不合法。

同处另有守卫兼容性：ControlledReplay.guard:37–43 对合法“仅声明属性名”的空数组声明固定拒绝，而 SkillRules.guard 对该声明使用当前 Actor 的非空范围。建议随 I1 的公共语义修复覆盖该情形，不另扩功能需求。

建议最小回归：returnSpec 投影后的普通对象、structured=null 的已验附件、空数组属性声明；确认普通图条件/表达式仍重算、所有模型和业务出口均零 HTTP，当前归属和源 provenance 分明，拒绝场景不伪造成功。

### I3 / P2：模型请求发送前拒绝后，已预留日额与 TPM 没有持久恢复路径

位置：model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java:255–268、:307–309、:317–328、:441–465、:528–541；关联 ModelQuota.java:39–42、:287–293，ModelStore.java:523–528。

ModelCalls 先成功 quota.acquire，Lua 已扣供应商 TPM/RPM 及 app/user 两个日额；之后才校验 timeout、解析凭据、序列化请求以及 checkActualWire。新增 wire 校验可在实际完整计量大于初始 reservedInput 时安全拒绝，发生位置却早于 store.sending，因此 logId=0，没有 PG call_log/quota_receipt。catch 不退款；finally 的 Permit.close 仅删除两个并发 ZSET 槽位。预留 token 仍留在两个日额中，恢复任务也找不到 PENDING 流水。timeout=1 的合法 JSON 请求也是确定的发送前失败例子；wire tokenizer 对真实 SDK 请求材料返回更高计量则无需使用非法参数即可触发。

后果：模型业务请求从未发送、实际用量确定为零，但反复尝试会耗尽应用/用户当天额度，并暂时耗尽供应商桶。此处与“已发送但 usage 未知，保守保留预留”不同；新增用户日额使同主体其他应用也受影响。

建议：尽可能将完整本地验证和真实 wire 计量前移到准入之前；对准入后、明确未发送的失败建立持久 receipt 和零实际用量结算路径。记录发送边界，避免把网络结果未知错误当成零用量退款。退款必须沿用 F6 的截止时间/幂等规则，不能只在 finally 中做一次不可恢复 Redis 操作。

建议最小回归：预留成功后分别注入 wire 超预留、timeout 参数失败、凭据读取失败、发送前日志持久化失败，断言外部业务请求为零、最终 app/user/TPM 预留恢复且恢复只执行一次；另保留“已开始发送但响应未知不退款”反例。

## 原发现关闭复核

“关闭”针对原报告的具体触发路径，不表示对应整册能力或新发现全部通过。

| 原发现 | 结论 | 实现与定向证据 |
| --- | --- | --- |
| runtime R3 / followup F1：采纳 FAILED 后再次交付旧代，永久 UNKNOWN | **CLOSE** | ExecutionRecovery.java:133–171 从 proof.result.error 取得并持久化 error_semantic；AgentDriver.java:207–210 在发 skill 请求前直接消费权威 FAILED。RunService.java:869–879 仅为允许的只读重试将调用转 REGISTERED 并增加独立 readonly_retry_count。ExecutionRecoveryTest:151 起已覆盖 FAILED proof 后真实 drive 到 FAILED 且零 redispatch；:221 起覆盖 RETRYABLE_FAILURE 的一次显式新执行。现存 XML 四项通过，已检查断言，未只看 READY 中间态。 |
| model F0：EDITOR 自行登记可信端点 | **CLOSE** | ModelStore.java:59、:148、:201、:216、:266 的管理写入均要求 ADMIN；ModelCalls.testProvider 同要求 ADMIN。已有 editorCannotAdministerProviderTrustOrModelMetadata 覆盖权限。管理员标签字典治理仍按原边界台账，不能宣称已完整实现。 |
| model F1：凭据安装与端点修改竞态 | **CLOSE** | ModelStore.java:109–194 以同一 providerLocked 行锁和事务保护 rotate/update，且两端入口均 ADMIN；配置修订同步递增。providerMutationAndCredentialRotationHoldSamePostgresRowLock 使用 PG 锁验证序列化。 |
| model F2：response_format Schema 漏计量 | **CLOSE 原缺陷；新增 I3** | countTokens:469–525 将完整请求传给专用 tokenizer，输出格式要求 request_sha256 回执；jtokkit 计数完整 canonical 请求。同步 :319、流式 :341 发送前再计真实 SDK wire，actual>reserved 拒绝。responseSchemaCannotBypassAdmissionOrProviderQuota、dedicatedTokenizerReceivesAndCountsOutputSchema 有对应测试。实际 wire 不能再绕过初始窗口/额度预留，但拒绝后的预留恢复仍见 I3。 |
| model F3：新实例绕过持久 DEGRADED | **CLOSE** | ModelCircuits.java:34–42 每次 acquire 先读 PG provider 状态，DEGRADED 即拒绝；coldInstanceCannotIgnorePersistedDegradedProvider 验证冷实例即时拒绝。多副本全局唯一半开探针仍非此次关闭保证。 |
| model F4：用户日额落在应用共享计数器 | **CLOSE** | ModelQuota.java:133–139 独立 tenant+day+principal 用户 key，无 app/provider；ACQUIRE 同时检验和预扣两个日额。ModelStore.java:596–638 分开解析 provider、app、user，用户配置跨 app/provider 取最严值。Redis/PG 定向用例覆盖跨应用/供应商、同应用不同用户、拒绝原子性及双日额结算。 |
| model F5：旧连接测试启用新配置/已停用供应商 | **CLOSE** | V304 提供 config_rev；ModelCalls 保存起始修订；ModelStore.tested/probed 使用 config_rev CAS；update/rotate/disable 推进修订。probeForOldConfigurationCannotEnableEditedOrDisabledProvider 覆盖网络期间修改与停用。 |
| model F6：marker 过期后无限重投重复扣退/复活过期 key | **CLOSE，限报告声明的恢复假设** | ModelQuota RECONCILE 在读取 marker 前按 Redis TIME 检查不可延长的 reconcileUntil；marker 比截止时间晚一天过期；providerUntil/generation 和 dailyUntil/PTTL 防跨桶/跨日退款及复活。ModelStore.reconciled 仅 PENDING→终态，pendingUsage 排除已终态。现存真实 Redis TTL 与 PG 未确认回归覆盖原时序。正常 TTL/服务崩溃闭环可关闭；Redis 已确认数据回滚、人工删除、持久文件损坏没有被验证，不宣称该情况下 exactly-once。 |

## 其余已核对内容及明确边界

- **CHAT**：ChatSessions.owned 限 tenant+principal+app；新 requestId 先重查当前 published/enablement，已有接受的 reservation 可在停用后幂等恢复；行锁和最新轮次终态判断串行化同 session。ChatHistory 使用持久 session/turn/run 顺序及归属，不接受 inputs 自报历史。已读停用、新轮与既有轮回查测试。当前 published 检查与跨服务 reservation 之间没有全局线性化事务，未把顺序门禁描述为强原子停用。
- **Gateway**：Routes 在 app CRUD 前识别 admin/v1 sessions；IntegrationPolicy 对绑定 app 用精确路径比较，并限制 sessions 子路径；IntegrationAccess 对每个 v1/runs 请求先由 engine 检查主体再比 appCode。相同主体跨 app 的路径隔离存在，不能因为 LAST_REAL 的 scope 缺陷否定该独立保证。当前发现未证明会话路由越权。
- **Mock authority 与源事实**：body.mock 不再决定替身；仅 DEBUG 的 claim.mock_snapshot 生效；正式/EVALUATION 不命中。技能独立核对成功、非 Mock、精确版本/参数/合同/源 run/principal/result SHA。源附件读取限定 tenant/principal/run，copyToRun 重登记和校验并生成新 opaque ID。以上保证均真实存在，但不足以覆盖 I1 的当前属性范围。
- **受控复现**：确实由普通 FlowDriver/AgentDriver 重算条件、表达式、输出；ModelExchange 先选 recorded port，工具从记录回包，模型 is_mock/source_invocation_id/sdk_attempts=0 有持久字段。ControlledReplayTest 用禁止 HTTP 的 clients 和篡改源最终输出（999）后重算得到 43，能证明所覆盖普通图不是复制最终答案。I1/I2 说明不能将两个简单场景扩大为所有响应形态的复现验收。
- **受控连接**：审查时 SkillGovernance.connection 已将 readonly 与写技能都通过 internal/skill-draft-tests 登记真实 DEBUG run，验收事实限定 tenant/principal/version/合同摘要/非 Mock 成功；不再靠裸适配调用生成无 run 归属的大结果。该小改由 root 同时跑定向回归，本报告不冒认自己的运行结果。
- **模型引用/上下文**：FrozenModels 按精确引用摘要核对；ModelExchange 保存 source/final 请求、摘要调用和独立 usage 标记；ContextProjection 使用计量回调并保留原记录、工具配对及保护消息。没有把启发式 jtokkit 标成精确计量。当前源码与模型网关的安全余量口径分别为 64/256，临界窗口可能被下游保守拒绝，属于需台账对齐的已知限制；未为此扩为开放式需求。
- 既有内部 caller/target 隔离、全量容量/舱壁、未知 usage 供应商补取、完整取消/对账人工闭环、真实模型/IAM/MCP/K8s 验收等不在本轮自动关闭范围。不能将 engine 65 / skill 45 / model 43 / gateway 14 等测试计数当作全部 v1.2 验收完成。

审查到此停止。新发现的修复、定向红绿与关闭复核另记；本报告只建立本轮发现时的可追溯结论。
