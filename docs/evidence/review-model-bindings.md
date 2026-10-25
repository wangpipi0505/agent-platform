# 模型冻结与模型网关独立审查

审查日期：2026-10-20。范围：`model-gateway/`、`app-management/` 模型冻结及精确发布版本 input-schema 增量；只为确认接口消费而查看 monitor 评测候选与 engine 冻结引用代码。未扩审施工中的上下文/会话实现。未读取相邻旧工程。采用 code-review 技能，先读本工程 AGENTS、implementation-brief、contracts/README 及冻结详设模型册、发布语义。

结论：**请求修改**。冻结引用的主路径有实质实现，但仍有 2 项 P1 授权问题和 5 项 P2 正确性问题；现有绿色测试不能证明这些权限、交错与长恢复窗口正确。以下是源码可确定的问题，未声称已运行攻击复现。

## 确定问题

### F0 — P1：EDITOR 可自行登记可信端点，绕过模型册明确的管理员信任登记边界

位置：`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:48-69,114-128,167-168,195-198`；`model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java:66-68`；`model-gateway/src/main/java/cn/agentplatform/model/domain/ModelPolicy.java:11-15`。

设计依据并非推测：冻结详设 :567 明确 F1-F3 管理组“管理员使用”，:571-572 将供应商协议/地址/凭据登记测试启停、模型条目登记指定为管理员；:757 规定端点 trust_labels 词表由租户级标签字典统一维护。工程 Actor.requireRole 允许 ADMIN 覆盖 EDITOR，但不会让仅 EDITOR 等同 ADMIN。

当前 createProvider、updateProvider、createEntry、updateEntry、testProvider 仅要求 EDITOR；无密钥供应商的 create/update 不会追加 ADMIN 校验，trustLabels 只校验非空数组。应用构建者因而无需竞态就能注册自控外网端点，自报 `[LOCAL, tenant:T1, SECRET...]`，让自控 HTTP 服务通过列模型+短对话，再建模型条目绑定到应用。出域校验仅做标签包含，最终会将要求 LOCAL/敏感标签的数据送到该外网端点。这是可信端点 authority 缺失，不能因标签包含算法正确而认定出域安全成立。

建议按 F1/F2 设计将供应商与条目的管理写入、连接测试、启停统一要求 ADMIN；应用构建 EDITOR 只读并消费已登记条目。trustLabels 还应验证管理员维护的租户字典与可信登记来源。补仅 EDITOR 对上述管理操作 403、已登记条目仍可消费，以及自报 LOCAL 端点无法登记的权限测试。F1 的细粒度密钥竞态在全面收紧 ADMIN 后不再对仅 EDITOR 可达，但其并发授权实现仍应避免重新开放时回归。

### F1 — P1：凭据安装与端点修改竞态可绕过 ADMIN 的密钥转接保护

位置：`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:116-123,133-142`；并发写入：`:96-110`；发送：`model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java:95-104,124-131`。

`updateProvider` 依据之前读到的 `old.secret_ref` 决定改地址是否需要 ADMIN；后续 UPDATE 没有比较该字段、配置版本或加锁，且会保留数据库最新 secret_ref。可发生：

1. EDITOR 开始改供应商端点，读到空 secret_ref。
2. ADMIN `rotate` 为同一供应商装入真实密钥并提交。
3. EDITOR 根据旧值通过角色检查，把 base_url 写为其控制的地址；新密钥仍留在此行。
4. EDITOR 有权执行连接测试，测试将新 ADMIN 密钥作为 Bearer 发到该地址。

后果是实际凭据外送，不能以接口不返回明文来认定凭据不泄露。建议为供应商配置/密钥写入建立共享行锁事务，在锁内读值、检查权限并写入；或所有写使用 config_revision CAS，冲突后重新读并重新授权。必须覆盖“空凭据→有凭据”并发路径。

### F2 — P2：准入计量遗漏发送给模型的 response_format Schema

位置：`model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java:210-233,247-254,421-430,448-457`。

两个计量分支仅包含 messages 和 toolDefinitions，但真实 OpenAI 请求还包含 `params.output.format.schema` 对应的 response_format。一个短 message 搭配大型 JSON Schema 会按短输入通过窗口/TPM/日额准入，再发送远大于该结果的模型上下文；2MiB 网络上限不能替代模型窗口限制。专用 tokenizer 分支也遗漏该部分，却返回 exact=true。

建议生成统一、完整的模型请求材料用于组装计量与实际序列化，将 JSON Schema/response format 一并交给模型适配 tokenizer；明确结构余量与 exact 的含义。补同消息、不同大小输出 Schema 的计量/窗口拒绝测试。

### F3 — P2：新实例在首次探活前绕过 PG 已持久化的熔断状态

位置：`model-gateway/src/main/java/cn/agentplatform/model/application/ModelCircuits.java:30-39,66-76`；`ModelCalls.java:194,217-218`；`ModelHealthProbe.java:23-24`；`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:306-336`（resolve 冻结分支）。

acquire 创建的本地 CircuitBreaker 初始 CLOSED，从不恢复 PG 的 DEGRADED；只有定时器 probe 才在 registry 中首次见到该供应商时转 OPEN。已有 bindingRef 的 resolve 按设计绕过当前启用状态，因此：实例 A 熔断并持久化 DEGRADED 后，实例 B（或重启后的 A）在首次定时探活前接到历史绑定调用，仍会拿到许可并真实请求供应商。默认初始延迟为 30 秒。多个运行副本中，未失败的本地 breaker 也不会因别的副本持久化 DEGRADED 而立即拒绝。

建议在首次 acquire 时从持久状态恢复，并明确跨副本 OPEN/半开探针所有权语义；不能只在探活入口恢复。补“先写 DEGRADED→构建新 ModelCalls→立即历史绑定调用”的失败测试。

### F4 — P2：用户日额配置实际落在应用共享日计数器，既可跨应用超用又会误伤同应用其他用户

位置：`model-gateway/src/main/java/cn/agentplatform/model/application/ModelQuota.java:28,33,37,84-89`；配置选择：`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:502-545`。

配置接受 userPrincipal/dailyTokens，且会选择用户专属记录，但 ACQUIRE 只有一个 daily key，维度为 tenant+date+app，不含 principal。用户级 100 token 日额可在应用 A 和 B 各消费 100；反过来同应用其他用户的消耗也会占用该用户的额度。用户 ZSET 是并发限制，不能实现用户日额。

建议分别解析供应商桶、应用额度、用户额度，在同一个 Lua 脚本内检查并预留两个日计数器；usage 对账 receipt 同时包含二者并原子调整。补跨应用同用户与同应用不同用户测试。当前“应用日额+用户并发共享”测试没有覆盖用户日额。

### F5 — P2：连接测试成功可启用测试开始后已经修改或禁用的供应商

位置：`model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java:95-98,153-155`；`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:88-93,132-142,161-165`。

测试基于一次 provider 读取执行真实列模型与短对话，但 `tested(true)` 仅按 tenant/id 无条件设置 ENABLED。交错：测试已读取配置 A、HTTP 仍进行→另一请求改为配置 B（按规则 DISABLED）或明确 disable→配置 A 的测试完成→tested(true) 将 B/已停用行重新启用。此时“启用前通过当前配置连接测试”与人工停用语义均失效。

建议测试记录配置修订，并仅对仍等于测试开始修订的行条件启用；配置/凭据/人工禁用均推进该修订。检测冲突时返回已变更需重测，不能让网络期间长持行锁。补阻塞短对话期间修改/禁用的交错测试。

### F6 — P2：Redis 对账幂等标记仅保留 48 小时，PG 待重投记录却没有恢复期限

位置：`model-gateway/src/main/java/cn/agentplatform/model/application/ModelQuota.java:48-53`；`ModelCalls.java:79-87,360-365`；`model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:460-471`。

Redis 扣退额成功与 PG RECONCILED 更新之间有恢复窗口。若 Redis 已调整账务，但 PG 标记因故障/崩溃未提交，该记录保持 PENDING；若恢复发生在 172800 秒后，幂等 marker 已消失，恢复任务将再次扣退同一笔额度。provider 桶不带日期，重复退款会进入当时的活跃桶。原日额 key 如已过期，SET KEEPTTL 还会重建无 TTL 的历史 key。

建议账务投递使用可持久去重的版本/流水，并定义跨窗口恢复规则；如果保留有期限 marker，必须有与之匹配且可安全结转的 PG 恢复边界，不能过期后直接再次应用差额。至少补“Redis 成功、PG 未确认、marker 过期后重投”的测试及过期日额 key 不复活测试。

## 已核对的设计一致性与现有保障

- model freeze 将非秘密配置 canonical 后计算 SHA；binding_snapshot 按 tenant+sha 寻址，运行 resolve 同时限定 tenant+id+sha，并检查显式 modelEntryId 是否吻合。不采信调用方任意 entry/allowDisabled；历史引用保持原模型、端点、窗口、标签，凭据从当前权威 provider 实时解析。
- 候选首次 model_refs 以 `(tenant,appCode,draftRev,candidateSha)` 主键固定；并发 insert 返回最先写入的 refs。预检消费同一行；commit 在草稿行锁中比较候选 refs 与预检 refs，再把 refs 附加到发布版本。engine FrozenModels 进一步验证 entry canonical SHA 与 bindingRef。现有篡改预检 refs 测试覆盖了不一致被拒。
- 正常顺序下，禁用供应商后历史绑定可继续使用，freeze/validate 拒绝新的发布引用；commit 在事务外复核可用性。禁用与发布同时发生仍是跨服务检查窗口，未提供跨服务线性化保证，应在验收口径中明确，不能宣称全局原子禁用门禁。
- 管理查询不返回 secretRef/明文；vault/env 解析校验 tenant，env 引用要求权威租户绑定。实际模型请求快照不写凭据头；同步拦截器与流式连接器均在发送实际字节前持久化请求材料。
- 单次 Lua 准入原子检查供应商 RPM/TPM、现有应用日额与用户/应用并发，Redis 不可用拒绝；批量队列是有界 Redis ZSET、最长等候 10 秒，许可用租约释放。F4 是维度缺失，不是否认已实现原子性。
- 缺失 usage 保持 NULL/UNAVAILABLE，并保守保留预留额度；已知 usage 持久化 PENDING 后重投账务，不重发模型请求。现有实现没有从供应商事后补取未知 usage 的接口/任务，属于剩余功能边界，不能称未知用量已经最终结清。
- 401/403、429、503、408/504、超时均有固定平台错误与安全提示；框架 retry maxAttempts=1，工具执行显式关闭。连接测试确实调用列模型和短对话两阶段，F5 是配置并发一致性问题。
- public `/v1/apps/{code}/input-schema` 从 app_version 的精确 snapshot 投影 appCode/versionNo/inputs/inputSchema，不返回模型依赖、提示词与内部 snapshot；未发布、停用及不存在版本拒绝。当前产品策略是改稿使应用变 DRAFT 后也拒绝原发布版本 schema（测试明确如此），不是本轮以测试结果推定的事故。若预期旧发布入口在编辑新草稿时仍可用，需要先统一生命周期口径。

## 剩余功能边界与协调事项

- 健康定时器只遍历 DEGRADED，不探测 ENABLED 的空闲供应商；当前是调用失败触发熔断后探活恢复，尚未实现详设 F8 对启用供应商的常态周期探测。
- 当前仅实现 OPENAI_COMPATIBLE，OLLAMA/vision 与平台默认模型绑定、按策略选择排队仍有边界；不能因为局部测试绿而宣称模型册 F1-F8 全部完成。
- engine 冻结引用消费有真实摘要校验；其 RunService.freezeModelRefs 的 ID 收集只识别应用主绑定与 definition.nodes.modelOverride，而 app.collectModelIds 还识别 nodeOverrides/modelBinding，仍需与施工中的 engine 最终支持字段统一。这里只登记接口协调风险，不扩审其上下文/会话代码。
- 本轮未判定“候选摘要本身不含 refs”是错误：已由同候选首次 refs 固定以及发布 refs 比较提供依赖身份；但 monitor accepted 只返回 app/rev/hash 通过报告，若未来允许删除/重建候选依赖行，必须同步增加报告依赖摘要验证，不能保留旧 accepted 报告重新绑定。

## 验证证据与限制

已阅读现有测试源码，并读取 `.local/model-final-green.log` 的模型 26 项（18 PG/HTTP、5 Redis、3 policy）零失败零跳过、BUILD SUCCESS，以及 `.local/model-app-final-green.log` 的 PublishTransactionTest 13 项零失败零跳过、BUILD SUCCESS。按委派要求未重跑完整测试。本报告仅新增此文件，未修改实现或测试。上述 7 项来自源码、明确权限事实与触发交错，不将测试全绿等同于无缺陷。
