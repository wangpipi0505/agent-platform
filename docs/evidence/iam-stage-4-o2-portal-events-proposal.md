# O2 PORTAL 运行事件 SSE：只读预设计候选

状态：候选，未实现、未测试、不是正式 brief。仅供 root 在 O1c Accepted 后形成有限实施要求。本文不把自研 authority fixture 的能力当成外部 IAM 已签收能力，不标记 G6、RR1、RR2 或整个平台完成。

## 1. 范围、基线与本次操作

唯一工程和每条 shell 的显式 workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。本次只读固定证据与冻结原件，只新增本文；未读取 O1c 作者活动 source/test/target，未运行测试、probe、服务或编译，未改生产源、索引、旧提案和评审材料。使用既有 SDS 只读架构原则，不派子代理。

root 已确认 O1b Accepted：原有限审计 799 项加 fix 122 项；fix review 12273 B，SHA256 `d13a52b19ff2b1c99a69f8036f25a92b9619bd1ab7c0c47e8e91d7e9430ac820`。fix `source-after.json` 为 579 项（backend 387/frontend 192），109789 B，SHA256 `d5fd1b495a0a447b6a786d47869c590dca18908f488401dddb149b73afc88648`；target 1972 项的 manifest SHA256 `bc35d56e8de50731422e17f2a2fbfe176aa8ecf3b48478a5832fe92f147a189c` 仅继承 root 交还信息，未读取 target 内容。O1c 正在独占实现；本文只沿正式 brief 的批准边界作假设，不声称其 API 已冻结。

目标仅为 PORTAL 观察同原门户身份拥有的 IAM run 的持久事件。保留 execution 的 app-version root/grant、strict/Retained 写路径、原受理 initial ceiling 与历史材料。观察另取 run-snapshot fresh grant，action 必须是 EVENTS，不能借 STATUS、metadata QUERY 或 execution grant。首切片继承 O1b 已验 pure FLOW/可信受理支持范围；不能借 events 顺带开放 CHAT、模型 chunk、INTEGRATION、后台 DELEGATED 恢复或 completion drain。

## 2. 结论与最小切片

建议按 O2a、O2b 分开立正式 brief，均在自己的实际 HTTP receiver 上红绿闭合；O2a 通过不等于浏览器经 gateway 的 SSE 已可用。

| 切片 | 最小交付 | 必要前置和不能宣称的结果 |
|---|---|---|
| O2a：engine PORTAL 当前材料与有限事件流 | 新固定 EVENTS purpose；PORTAL response open/check 返回可验证 fresh current 的窄能力；engine 真实 PG 的 metadata→scope→payload→digest→逐帧输出；独立异步 holder 与停止检查 | O1c 最终接口先核；补自研 response profile；gateway 路径仍关闭，不能称端到端 |
| O2b：gateway 长连接与逐 hop 响应关联 | 同 originalPortal 的 Q、gateway 本地观察动作 G、engine EVENTS 动作 E；本地与 child response 各自租约；固定 Apache 流式传输、跨 hop 签名关联与最后一跳输出检查 | O2a Accepted；真实 authority 关联签发/验证；按 root 裁定落实 gateway 逐事件材料终检，见 §7；不能用短同步 O1c proof 冒充长期能力 |

若 O2a 的公共 profile 与 engine writer 评审面过大，可先封存 O2a-1 的固定 profile/材料读能力，再做 O2a-2 实际 handler，但前者不得对外打开 SSE，测试也不得仅凭租约单测宣布 O2a 通过。本文不替 root 决定最终类名或扩张 O1c 当前 scope。

三项硬缺口已由冻结代码证实：

1. `ResponseLease` 只有 openingDecisionId、continuation、期限与可选 completionPermit；没有续核后的 actor/roles/attrs/material current。只延长 checkedUntil 不能授权 PORTAL 的下一事件材料。
2. `StreamAuthorityGuard.materialAccess()` 必须有 completionPermit，属于 INTEGRATION 原响应完成路径；O1b `StatusReadProof` 又绑定原线程/AuthorityContext frame。两者均不是异步 PORTAL 事件读取能力。
3. authority fixture 要求 open/check 的 opening call target 等于接收方凭据身份。gateway 不能拿 target=engine 的票据/decision 伪装成 gateway 本地接收授权。fixture 的 `parent_response_id="parent-"+responseId` 没有另一 hop 事实，不能当作已经完成的跨 hop 关联。

## 3. 固定 action、resource、当前权限和新 profile

### 3.1 保留的 action 与有限扩展

现 catalog 已有三个 events alias，均使用 `proxy.engine.get.RunController.events`：admin/debug 非 integration，v1 integration 可用。首切片只打开 admin GET 的 IAM PORTAL 分支；共享 action 不代表 debug/v1 自动可用。GatewayIngressBoundary 必须在路由、method、原始 query、source-kind 和启用条件上独立限制 EVENTS；旧构造默认关闭，旧 POST/SESSION/STATUS purpose 不变。

`RunObservationSelectors.requirePurpose` 当前将所有 `run-snapshot` 双向限定为 STATUS action；直接拿 run-snapshot 发 EVENTS 会在本地 purpose 检查失败。因此必须显式增加有限 EVENTS 配对：target engine、caller gateway、delegated 模式但 source/origin 均 PORTAL、资源 `run-snapshot(publicRunId,acceptedSnapshotSha)`，且 STATUS 与 EVENTS 私有证明不可互转。这里 delegated 是服务交换模式，绝不表示可接受后台 DELEGATED source。metadata 仍是原 SubjectKey+publicRunId query selector，不产观察 capability。

既有 admin events catalog 行通常可复用，不加重复匹配行、不用 `ANY`。新 gateway 本地接收动作尚不存在，候选名 `gateway.run.events.observe` 仅用于说明：target 必须 gateway，由固定 PORTAL 交换/验证路径使用，绑定同 run-snapshot。正式 profile 要决定其精确注册方式；不凭空造一个对外 HTTP handler 来绕过目录枚举，也不把 engine action 注册给任意 receiver。若现 authority 自研接口不支持该有限本地 action，O2b 须新增相应固定 profile，而非忽略接收身份校验。

### 3.2 每次新请求的来源链

沿 O1c 已批准的私有原 Portal capture：先验证原浏览器门户身份、原始路由/cursor，Q fresh exchange 取得 metadata；Q 仍仅能读 7 字段 metadata（data UTF-8≤4096，总 wire≤8192），其结果不作为许可。用可信 Q 的 owner/root/acceptedSha 与原 Portal SubjectKey 重新绑定，再从同一原 Portal 分别申请 E 及（O2b）G 的 fresh 观察 grant。请求中自报 tenant/owner/app/root/hash/期限均不能取代可信 metadata。

E/G 与原受理 execution grant 独立；不 resume 后台 grant、不延长后台 operation、不取得 strict 写能力。未知 run、其他 owner/tenant、legacy 无 IAM binding 按正式隐藏策略拒绝；不能先读 snapshot/event payload 再决定是否隐藏。owner 与历史 initial ceiling 都从可信受理持久化材料获取；不是当次 JWT 自报字段。Q 与 E/G 之间若 run 被删/metadata 不符，engine 必须在自己的 PG 事务内重核，不能信任 gateway 已查过。

### 3.3 PORTAL response-current 固定 profile（待实现）

可复用原签名校验、单 claim/停止位的机制；不能原样复用只含时间的 `ResponseLease` 作为材料 current。建议新增 PORTAL 专用、私有工厂铸造的 response-current/holder，或在既有 profile 中增加独立且不可混用的已验类型。正式 API 名由实施 brief 确定，以下是必须验证的关系：

- open 的输入来自本 receiver 已验的实际 admission，而非调用者任意 new 的 `VerifiedAuthority` 数据壳；authority 将 opening decision 与具体 call、subject、grant/operation、来源票据绑定。
- 签名响应明确 issuer/kid/alg/typ/token_use、consumer/receiver、原 caller/action、tenant/principal、source_kind=PORTAL/origin、grant/operation ID、原 source/票据关联、responseId、run-snapshot、规范 requestSha256、checkedUntil、sourceExpiresAt、grantExpiresAt 与 hardDeadline；身份与各不可变字段须对输入逐项比较。字段名称为候选，不能称现 profile 已具备。
- 每次 open/check 返回可用于当前材料校验的完整、签名绑定的当次 actor/roles/attrs/ceiling 或等价窄授权证据及 authorizationVersion/current decision ID。只有这些内容能证明本轮 current；续核只返回新 checkedUntil 不够。不得从 openingAuthority 拷贝 roles 后换 expiry，不得将 opening authorizationVersion 强行写到后续 worker 历史材料。
- check 使用接收方自己的凭据、同 response continuation 与不可变绑定；到期前可使用未过期 capture，到 checkedUntil 必须在线成功后才再读/发。current 有效窗≤5秒；不得把定时器命中当成 IAM 已认可。
- 原 Portal expiry、观察 grant expiry、响应最初 admission+55秒（以及更短既有限额）共同形成硬上界。service ticket 仅 admission，到期后只有原 response 的已验 continuation 可以续核到这些硬终点，不能据此新 exchange、重连或新动作。
- 私有 current capture 绑定 holder 实例/responseId/generation、subject/resource/action、不可变来源、截止与停止位，普通构造函数不能铸造；它不提供 ServiceTicket、Actor/AuthorityContext、strict/Retained、STATUS proof 或 completionPermit 转换。

这些均为自研 IAM profile 的新增要求，不是 RFC 自动保证。真实 authority 未实现前，fixture 红绿只能证明本软件拟议机制；不宣称生产对接已完成。

## 4. engine：真正的只读事件材料路径

### 4.1 admission 与异步线程

在 servlet admission 线程完成原始路由/cursor校验、fresh 当前接收授权、可信 run binding/owner 与资源核对，open 成功后才占订阅资源/提交 writer。取得 admission 时的硬期限，不能沿旧 handler 在 writer 真正启动时再给 55 秒。

异步线程显式携带私有 holder 与不可变 run binding；禁止重新 `Actor.current()`、伪装原 thread-local frame、把 O1b `StatusReadProof` 去掉 ownerThread 后复用。O1b 的 `loadObservationBinding` 与 snapshot 规范化/关系验证可以作为包内纯验证复用，但新的 EVENTS 消费入口必须要求自己的私有 proof。旧 strict/Retained 主业务入口保留；metadata DTO 不升级为凭证。

online refresh/open/check 全在 PG 事务外完成。异步读取每轮先拿尚有效的 current capture，再进入一个顶层 readOnly REPEATABLE READ 事务；入口拒绝 ambient 事务。入/末/读 payload 前后核同 capture 未停止、未过期、generation 未变；事务期间不向 authority 发 HTTP。若续核跨线程改变 generation，应丢弃该轮结果并以新 capture 重试有限次数或关闭，不能把旧读结果换标签输出。

### 4.2 metadata→payload 两阶段

最小先一次只选一个持久事件，避免一次预读整个历史。候选 `nextEventRef` 是 package-private metadata 查找，不是公开 material API；真实实现目前不存在。

1. 同 RR 内读取 trusted control 与 observation binding，核 public run ID/internal ID、tenant/owner、root kind/id、acceptedSnapshotSha、app/version、initial ceiling 和根 manifests；保留 O1b 的 snapshot payload 规范化、accepted sha 与 candidate 关系校验，pure FLOW 依赖可信受理/immutable 约束，不编造额外 receipt。首次必要 snapshot 业务读取也必须先有 current+initial scope，再读后验摘要；不是仅检查合法64hex就声称验证了 payload。
2. 仅选下一条事件的 locator/seq/type、对应真实 material scope/version/digest/parents 等 metadata，不选 payload。event material ID 是真实 row ID，version 是 material_revision，不是 event seq，也不是 public run ID；不能沿用旧 completion selector 的 revision后缀假设。
3. 先检查本次需要的全部根、状态/水位及选中事件和 parent 依赖的 scope：current 覆盖、原受理 initial ceiling/owner/根绑定、可信 producer/kind/id/version、scopeSha/digest/parents 关系。缺失、他人run、超scope、错snapshot、错revision 在选 payload 前拒绝。不能只验根scope，也不能将所有历史材料的 sourceAuthorizationVersion 必须等于 opening/current version。
4. 同 RR 按已验 id/version 读取实际 payload并验证规范化摘要。现 `run-event` 摘要覆盖 `id,run_id,seq,type,payload,created_at` 完整投影；不能只 hash 事件 payload。实际输出仍是原 seq/type/payload，内部 locator/授权元数据不能泄露给浏览器。
5. 事务结束后再核 capture、持有的一帧数据与 proof 关联；进入 writer 前再核。授权/metadata负例 payload读取=0；实际内容摘要错的负例 payload读取>0 但 wire业务字节=0。不得为了让前者好测而伪造冗余receipt或改写历史记录。

新 event 并发提交：只承诺本 RR 看到的行；下一轮另取新事务和有效 capture，对新scope逐条核验。不能开流时一次授权整个未来run，也不能要求未来payload预知。只在正常完成该帧 send 后推进本地 lastSeq；未授权事件不能跳过。短 RR 避免跨整个 SSE 持有数据库快照。现 MaterialScopeStore.currentScopes 的全run最新材料集合不能无区别用作每次扫描所有历史payload；最窄应取此轮实际依赖的 manifests。

run状态/水位、CURSOR_EXPIRED/AHEAD reset 中的 status/eventsWatermark 同样是受保护材料，不保留旧裸 SQL 捷径。真实 PG retention/并发必须在同一 RR 解释；根/所需scope不合法不能靠 cursor reset 输出受保护状态。

## 5. 游标、临时 chunk 与原响应语义

现 RunController 接受 header `Last-Event-ID` 和 query 同名参数，query 优先。新 IAM 分支需保留已承诺的游标载体，避免未经裁定悄悄移除 query；建议严格解析非负 long，拒绝重复参数、非法/溢出值、未知 query，并对同时出现但不一致的 header/query 返回既有 EVENT_CURSOR 类400。是否允许两个完全相同值由正式 brief 固定，不能由框架偶然选首值。规范 cursor 与精确 run/resource、动作及选定输入形式纳入 requestSha256；重连不是继续使用原lease。

持久 event seq 单调，重连从最后已消费 id 回放；网络断在一帧中可能重发，客户端按 seq 去重，不能承诺 socket flush 等于浏览器已收到。CURSOR_AHEAD/EXPIRED、FINAL_SNAPSHOT 提示、chunkReplay=false 继续沿既有契约；授权失效 reset 只含静态授权原因，不含 status、水位、pending event或final。

首个纯 FLOW 切片只覆盖持久事件。不得把旧 `subscription.poll().text` 当授权材料直接输出；若关闭临时 chunk 分支，应如实发 chunkAvailable=false 且不宣称已完成完整平台 chunk 能力。若正式 brief 选择保留临时 chunk，则另需证明它继承本响应已验证请求scope、同原run/response、每帧 current 和停止位；不能仅凭全局 ChunkBus 订阅成功。这里是有限实现范围声明，不修改原持久回放或 future CHAT 契约。

正常 terminal 仍应排空已经提交且允许观察的持久事件后关闭；这是正常流完成，不是 INTEGRATION drain。断开观察、过期、容量拒绝和关闭都不得调用 cancel run；后台执行继续受其自己的原授权机制控制。

## 6. holder、实际 wire 与有界资源

现 StreamAuthorityGuard 的 checkedUntil renewal 在 synchronized beforeWrite 内做网络请求；只有 wall clock，materialAccess 仅permit。不能声称原样复用已解决异步PORTAL材料或所有阻塞问题。建议独立有限 PORTAL holder：停止位先置，renew单飞，网络与 PG/socket write 不持有关闭所需互斥锁，续核迟到结果必须比较 generation/closed 后丢弃。硬预算使用可控 wall clock 加单调 elapsed 上限，防时间回拨延长原响应；不自行增加生产SLA。

最多1秒的 watchdog 调度观察本地到期/停止，必要时发起有界 online check或取消正在等待的读取；业务写也必须主动检查。local proof只能发现本地过期/关闭或已经取得的拒绝，不能证明尚未查询的远端撤权。authority故障/撤权后不再读下一payload、不输出缓冲业务帧；允许最多一个既有 stream_reset(reason AUTHORITY_EXPIRED/DENIED/UNAVAILABLE)，只能由唯一writer发送，watchdog不可与writer并发写控制帧。写阻塞时允许来不及reset而直接关闭；不能保证终止已经进入操作系统的所有字节。

首次与每帧输出顺序：构造完整有界帧→序列化后核同private capture/material→取得实际Servlet stream→再次核同proof→实际首次write。之后每帧发出前、每次可能继续业务写的边界及flush前检查有效性；若续核导致capture不再匹配，丢弃该帧或按新current重新核全部该帧scope。不能只在 `getOutputStream()` 前检查，因为它也可能阻塞；不能只在入queue时检查。整帧byte[]不意味着TCP原子发送；只承诺检查之后不启动下一业务写，已开始write不可追回。

现 cap：`MAX_EVENT_BYTES=262144`、`MAX_CHUNK_BYTES=8192`、response payload预算16MiB、subscription queue32、默认订阅256。可以复用机械 admission/reservation，不能把O1c完整JSON的2MiB总响应限制搬到长SSE。单event payload限额与完整SSE帧的id/event/data/分隔符开销须分别计算，分配容量（不只是count）有界；超限沿既有 EVENT_LIMIT/reset 语义，不截断JSON。跨hop同时缓冲数必须明确，不能两边无界队列。

旧 ChunkBus reserveResponse 在读取前持有到send结束；subscription close 对已停但阻塞sender明确保留slot直到实际退出。新 holder停止可先取消底层资源，但不得提前释放仍被blocked writer持有的大buffer/并发slot再允许无限新连接。DB连接只在短事务中占用，网络读写/flush不在事务内。关闭须幂等，覆盖提交writer前失败、async未启动、连接断开、watchdog失败、finally与迟到回调。

## 7. gateway：独立接收身份、跨 hop 关联和最后一跳

### 7.1 新固定传输与独立 proof

O1c的短同步 STATUS proof/完整body接口不能用于55秒SSE。可共用 Apache 5.4.4/core5.3.4 的固定地址、无retry/redirect/authchallenge/cookie与有限header/line解析机制，不重用有限POST或STATUS purpose类型，也不把GET默认幂等重试带入新路径。长响应需要自己的固定endpoint、connection owner、stream handle、取消与deadline，不以继承某通用GET函数获得任意URL能力。正常关闭 response/body 必须释放连接，upstream read/socket阻塞取消须真实验证。

新 EVENTS boundary、metadata client、events proxy和transport在有限显式装配里安装。沿O1c：GatewayIamAssembly不是stereotype，旧构造默认功能关闭，只有实际events bean存在才打开新增kind；不补旧POSTbeans，不改GatewayApplication，不声称全Boot G6E/F上线。唯一transport owner明确destroy；Q与events按批准的可共享mechanics借用，长连接是否独立client/pool由正式brief按容量隔离决定，不擅自改生产配置。

### 7.2 真正签发的响应关联

候选先打开gateway本地G响应，再由authority签发仅给engine本次E open使用的关联凭证。它至少签名绑定：parentResponseId、child receiver=engine、原source/subject、同run-snapshot、canonical requestSha/cursor、父opening/operation/grant关联、父hardDeadline及一次本响应用途；engine用自身E admission核对关联后open child，authority返回真实childResponseId和关联回执。gateway验证来自该固定子响应的回执与自己的parent，随后才允许业务帧下行。具体字段名称/transport header尚未实现；不能把公开JSON parentId或fixture拼接字符串当proof。

每receiver使用自己的凭据续核自己的lease；不把Java holder经HTTP序列化，不把G current当E业务票、不把E票作G current。所有hop硬期限不超过原parent/source/grant，换hop不能重新获得55秒。私有header在gateway ingress拒绝/剥离，禁止用户供给parent关联；下行不泄漏ticket/continuation/scope。缺失/错subject/resource/cursor/receiver/parent、换连接重放、扩大deadline均在业务输出前失败。

这是新增自研fixed profile，须与同仓authority fixture一起具体验证；不声称真实authority现已支持derive/parent关联，也不要求修改原execution root。若authority方案选择其他等价的配对签发方式，必须仍证明各receiver接收动作与双方不可变关联，不能省去此条件。

### 7.3 root 已裁定：gateway 同样做材料级终检

本候选形成期间 root 已选择更强边界：gateway 独立使用实际签名本地 current/private lease 对每帧材料终检，不能降为仅观察 action 检查。“仅信任 engine 的材料裁决”不作为正式默认，O1c 当前短同步 scope 不扩。公开 SSE 仍保持原 id/type/payload 与 Last-Event-ID 语义。

现公开 SSE 没有历史scope；engine已读缓冲B事件、gateway续核后只剩A时，仅核gatewayaction无法拒掉B。因此O2b新增仅固定内部连接使用的有界 manifest/帧绑定。它是可信engine给出的材料事实，不自赋读权，不增加普通 material API，也不使用 completionPermit。建议最小格式/规则如下（字段名尚为候选）：

1. **响应绑定先行。** 在允许业务packet前验证§7.2实际authority签发的parent/child关系、source/subject、run-snapshot、requestSha/cursor、各截止及当前连接。内部帧只能从本请求固定engine endpoint返回的已登记stream handle进入；客户端自报header、其他连接缓存或同run另一response均不能铸造FrameProof。信任边界是受控固定engine服务与该真实连接，防恶意被攻陷engine不属于本切片；scope本身没有签名，不假称它能独立认证producer。
2. **单packet原子验证、单帧输出。** 每packet包含schema/type、parentResponseId/engineResponseId、单调packetOrdinal、run ID/resource、源SubjectKey关联、event row ID/seq/type/created_at、原public payload文本，以及该event和必要根/parent的真实MaterialScopeV1。还须带原initial ceiling及其摘要/根绑定的可信事实，不能拿当前ceiling替代历史initial。若使用固定根缓存，只允许本响应一次验证的有界不可变根集合，按摘要引用；不得建立随事件增长的无界manifest缓存。单帧缺manifest、乱序、重复/跨response重放、未知schema/type拒绝，不释放业务data。
3. **内容关系。** gateway重建真实run-event投影 `id,run_id,seq,type,payload,created_at` 并用既有规范化/摘要规则与MaterialRef.payloadSha256比对；另验证待输出的原payload UTF-8字节摘要与packet绑定，避免解析后验证一份、输出另一份。public seq/type与投影对应字段必须一致，material ID是rowID、version是material_revision，scopeDigest使用现规则。不能只核public payload hash就声称验证了持久row摘要。根/parent与scope关系仍逐项检查，dataLabels义务保留。
4. **独立权限。** engine用自己的current先读先验；gateway用自己的最新有效capture核owner/同原Portal、initial ceiling约束及每份scope所需roles/entitlements，序列化后与actualstream后再次核同private FrameProof/current generation。engine返回的allow布尔或scope事实不能替代本地current。若renew后权限缩小，旧packet重新校验失败即丢弃，不替换为旧enginecurrent，不请求宽scope补票。
5. **明确有界候选。** 保留public event payload≤262144 UTF-8 bytes；内部授权metadata（不含payload文本）候选≤65536 UTF-8 bytes、依赖scope集合候选≤64项、JSON深度与各集合/字符串长度均有限。单内部packet候选≤2MiB，依据payload被JSON字符串转义的最坏额外开销与上述metadata预算设置，并非复用O1c整个响应2MiB限制；流总长仍受原55秒/容量约束。正式brief须核编码器真实最大capacity和上界计算，不能仅数序列化后count。metadata/依赖超限失败关闭，不截断scope、不跳过event、不把超限当authority撤权；公开使用既有容量/stream-reset框架的确切理由待brief固定，不私加新的公开reason。每侧最多持有明确数量（建议一份）的packet与输出帧，发送后释放。
6. **控制帧也分级。** 静态stream_status、无业务授权reset无需伪造持久event manifest；凡带受保护status/watermark/FINAL_SNAPSHOT事实的cursor/terminal控制packet，必须附其真实run-state材料绑定与scope，走相同终检。packet类别只能由固定解析器白名单接受。剥离全部内部授权/locator信息后输出原公开SSE格式，不转发私有控制包、continuation或任意backend字段。

固定source579清单中没有名为 `MaterialPolicyEngine.java` 的文件；实际可复用的是 common `MaterialAuthorization`：`require(scope,current,clock)`校验current、scope格式/摘要、owner和覆盖；`scopeDigest`按现MaterialScopeV1字段取摘要，`cover`对region做包含、bizSystemCode/userId严格单值相等，对未知entitlement拒绝；dataLabels是义务，不是角色许可。新PORTAL私有proof的消费应在同包复用这些真实规则/纯验证函数，必要时增加只接受该私有capture的窄重载，不 new 一个公开VerifiedAuthority来绕生命周期，也不另造通用策略或签名框架。旧 `requireForResponse` 仍只供原completion access。

新增成本是每帧有限scope传输、gateway摘要和覆盖检查、一个固定内部解码器及对应行为测试。它换取最后一跳也有实际材料判断；不迁移数据库、不改历史材料，不要求authority预先知道未来event摘要。尚未查到本次所读契约条款禁止这种固定私有服务封套；本次并未重审四契约全文，因此不得声称已证明一切私有扩展均允许。若实现核到真实冲突，必须指出原文件/段落交root处理，不能改公开输出化解。
### 7.4 错误与公开流保持

committed前沿O1c分类：只有独立证据证明浏览器原Portal无效/到期才401；Q/E/G内部签发、验票、过期/预算、authority验证服务故障是503或已核定授权403，内部原始JwtException必须在固定方法内归类，不能逃到ingress broad Jwt catch变401。实际engine已验证envelope的403/AUTHORITY_EXPIRED仍是403；未知/他人run隐藏404，容量429/cursor400等按确切异常源白名单核定，不能盲透传任何backend状态或错误文案。

committed后不再改HTTP状态，也不自动refresh Portal或重连原response。授权reset仅三种静态原因且无业务内容；正常transport/cursor/event-limit控制按既有契约区分。禁止Set-Cookie、额外Authorization、伪造hop-by-hop头、任意redirect和自动retry。rawsocket畸形chunk/headers/trailers必须由专用streamtransport实测；不能因O1c完整body测试通过就宣称持续SSE解析和取消已覆盖。

## 8. 精确候选文件与后续 diff 门槛

以下是候选必要位置，不是已批准writer范围；正式brief应在O1c最终manifest上固定最小集合。避免为了架构图预建大量空类。

| 层 | 已有路径/候选接缝 | 保留界限 |
|---|---|---|
| common purpose | `admin-common/.../authority/RunObservationSelectors.java`、`ServiceAuthorization.java`、固定 `CurrentAuthorityClient`/`HttpCurrentAuthorityClient` 入口 | EVENTS双向限定，private Portal responsecurrent工厂；POST/STATUS/Q不互转 |
| common response | `ResponseAuthorityClient.java`、`ResponseBinding.java`、`ResponseLease.java`、`StreamAuthorityGuard.java` 所在包 | 优先新增窄PORTAL类型或明确分支，旧INTEGRATION/RR1类型语义保持；不可无证据删旧限制 |
| catalog/fixture | `admin-common/src/main/resources/agent-service-authority.json`、`admin-common/src/test/java/.../LoopbackAuthority.java` | 现engine action不重复；仅必要本地G profile/真实关联/current字段，绝不宽ANY |
| engine API/application | `engine-runtime/.../api/RunController.java`、候选独立 `RunObservationEventsService` | 新IAM admin events分支、同步admission与异步holder；旧legacy三alias不得顺带改行为 |
| engine infra/domain | `RunAuthorityStore`包内 observation binding复用；`MaterialScopeStore`窄 `nextEventRef/readObservedEvent`；必要 `TraceStore` package seam或独立私有proof | snapshot验证纯函数可复用；StatusReadProof仍thread-bound；不动主执行strict |
| engine资源 | `ChunkBus` 机械预算/slot/取消接口按实际需要 | 不默认改bus、Redis或临时chunk格式；若无需source改则仅调用现有机械API |
| gateway | `GatewayIngressBoundary`、`GatewayController`、`GatewayIamAssembly`，候选 `RunEventsProxy`/专用transport；借用O1c已验metadata入口 | 本地G+E proof、有限EVENTS启用；不改main/POM，不泛化全部GET |

O1c Accepted后必须逐项diff：Q private入口的source/frame/expiry约束及可跨阶段传递范围；final STATUS与EVENTS能否共享纯validator而不共享proof；CurrentAuthorityClient/HttpCurrentAuthorityClient固定exchange返回类型；Boundary新构造和异常catch范围；PublishedRunHttp实际owner/close、request策略、headers/body上限；metadata七字段严格解析；assembly ObjectProvider/条件与tests Receiver所有权。若接口只能在原frame消费，应在同步admission完成需要的工作，再明确转入新response holder，不能擅自延长原frame或改短proof期限。

O1b fix后的 controller cap和fixture修正已经Accepted；本文使用最新RunController冻结原件，不回退原B版本。O2 source基线必须从最终O1c handoff重新固定，本文579manifest只是当前设计证据，不能当未来writer起点。

## 9. 有限真实红绿矩阵（建议，均未执行）

使用真实PG、独立engine/gateway JVM或独立模块classpath Receiver、受控authority HTTP与固定真实HTTP client；不在同一Spring扫描中混入两服务导致假绿色。数据可通过既有可信受理存储seam与正确FLOW from/to构造；若没有真正公开admission，不声称已验全创建链。禁止为测试增加生产冗余receipt、改写history或放宽purpose。

| 场景 | 必须观测的事实 |
|---|---|
| 原实现红 | IAM admin events原闭路/裸payload路径不能满足新purpose；测试失败须来自真实handler/wire行为，不能只用类不存在编译红 |
| 同Portal持久事件正例 | Q→E（O2a），Q→G/E及各receiver open/check（O2b）真实HTTP；actualPG事件、真实material revision、scope、规范化digest；收到原SSE id/type/payload；没有execution resume/model/skill/cancel调用 |
| owner/root/query票/类型负例 | 他人tenant/owner、未知/legacy、篡改acceptedSha、用STATUS或Q票发events、sourceDELEGATED/INTEGRATION、自报parent/owner、debug/v1路径；必要payload查询0，业务wire0 |
| 当前授权与历史scope | A+B受理，第一事件后闩锁停在第二nextEventRef前；role/scope缩为A、grant/source到期或authority503；第二payload0/wire0；新current恢复合法时新请求可回放；不借completionpermit |
| 实际内容绑定 | 错version/locator/parent/scopehash在payload前拒；实际payload或row投影摘要错读后拒；SQL实际列/参数与PG行为佐证，不能只断言旧counter |
| 并发 | 开流后新增合法事件可见；新增超scope事件在读前拒；retention与状态/水位同RR一致；authorizationVersion不同的合法历史worker材料不被openingversion误拒 |
| 时间/线程 | Clock+闩锁跨≤5秒current、serviceJWT期限、原Portal/grant/harddeadline；同response续核可越过仅service票期，重连不能；worker晚启动不续55秒；无threadlocal仍合法privateholder，伪造/跨holder/已closedcapture拒 |
| 输出窗口 | 分别卡住序列化、actual getOutputStream、queue到writer、首write/下一frame、renew迟到；过期/撤权后不启动业务write；gateway只读已缓冲第二帧时仍执行自己的终检；不以看不到网络字节代替确切write边界证据 |
| idle/阻塞 | 无事件idle watchdog，authoritycheck挂起有界，upstreamread阻塞取消，downstreamblocked writer stop先置；lateframe不得输出，slot/buffer不提前无界复用；不要求操作系统已开始write可追回 |
| cursor | header/query解析、重复/冲突、负值/溢出、ahead/expired、retentionrace、断帧重连seq去重；重连重新Portal/Q/E/G，不复用lease；授权reset无status/watermark/payload |
| 跨hop profile | 正确parent-child实际authority签发；错receiver/source/resource/cursor/deadline、假parent、跨连接/关闭后重放；gateway不能用engine票open；每hop续核各用自己credential |
| 有界传输 | 正好/超过event cap，UTF-8与帧额外开销，buffer实际容量；slowobserver、全局slot/预算回收；零cookie/redirect/retry/authchallenge；畸形chunk/trailer/headers有限rawsocket样本；非授权error白名单 |
| 回归边界 | 旧POST/STATUS/Q分离、O1b完整2MiB响应与错误保持；finiteassembly缺eventsbean仍关闭；断观察cancel-run0；INTEGRATION/RR1旧测试只证明未回归，不能登记新RR完成 |

建议最终测试名（尚不存在）：common `PortalEventAuthorityHttpTest`，engine `PortalRunEventsHttpPostgresTest`，gateway `GatewayPortalRunEventsHttpTest`。可由root按最终test类名固定命令：

`mvn.cmd -B -ntp -pl admin-common,engine-runtime -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=PortalEventAuthorityHttpTest,PortalRunEventsHttpPostgresTest" -Dsurefire.failIfNoSpecifiedTests=false`

O2b 再用同形式 `-pl admin-common,engine-runtime,platform-gateway -am` 与明确三个测试类，Receiver以独立进程/模块classpath启动；实际类不存在时不能把该候选命令当执行事实。测试矩阵优先采用可控Clock/闩锁，禁止长sleep假冒时间证据。未实施、未跑红绿、未启动任何Receiver是本文的事实状态。

## 10. 四契约、G6与待 root 裁定项

不修改原四契约或v1.2；保留持久事件seq/回放/去重、原reset/FINAL_SNAPSHOT语义、chunk不回放、断观察不cancel及INTEGRATION原响应规则。v1.2:2887 的INTEGRATION当前响应例外没有被PORTAL覆盖，也不由O2补做RR1。

新增自研profile的本地G action、fresh PORTAL response-current、真实parent-child关联与内部帧manifest，属于IAM适配/内部传输扩展，不是默许修改公开契约。若具体实施发现必须改变公开events数据格式、游标载体/优先规则、chunkAvailable承诺、reset reason集合或原权限目录契约，须列精确差异由root定正式范围；本文没有授权修改它们。

root正式化前的有限裁定：①O2a/O2b及是否再分common前置；②本地G action注册与签名关联具体profile；③§7.3已定强材料边界下的内部packet预算/确切字段；④IAM cursor双方同时出现的处理、纯FLOW临时chunk禁用的明确声明。其他常规实现选择不必向human重复求许可；这些事项是有真实接口缺口的root架构裁定，不是不存在环境造成的阻塞。

不覆盖：生产authority部署签收、全Boot边族、CHAT/模型/skill流、INTEGRATION completion、RR1/RR2、SDK、公开创建run全链、灾难恢复、已发数据回收、供应商取消、全撤权SLA。O2a/b有限样本通过也不能自动勾选G6E/F整体或Task4/5/6所有条目。

## 11. 实际读取记录与固定来源

本次先以具名manifest条目和冻结优先级定位，后窄读相关range；未扫全仓/旧target。以下前缀均相对唯一工程根，拼接后是实际物理文件；不是current源码简称。

- F：`.local/iam-o1b-review-fixes/final01/source/`
- B：`docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/`
- A：`docs/evidence/iam-stage-4-o1a-run-metadata/frozen-overlay/`
- D：`docs/evidence/iam-stage-4-d4a-portal-session/frozen-overlay/`
- N：`docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/`

| 物理文件（前缀+相对路径） | bytes；SHA256 | 本次读取范围/继承限制 |
|---|---|---|
| F `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 27185；`06ee90387fb6fb47e82695f59d288a14c56783a34d53cfd3c8bb7ba3f46ba366` | rg events；190–240、241–305、309–352、354–427、432–443 |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/ResponseAuthorityClient.java` | 294；`a52d6e4f1eda133ba5a76f718be1f311350cea144b6163efc65965f82dfeb1ef` | 1–6 |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/ResponseBinding.java` | 265；`ed8c445417278503f880a003389b76c06d1bb76dcea8974d3f384a889c29868e` | 1–6 |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/ResponseLease.java` | 4750；`aab61512525ef7841a97d32f61d4008f9105202e2bfce93cfda07f68d8c967b5` | 1–67；单claim生命周期与字段 |
| N `admin-common/src/main/java/cn/agentplatform/common/authority/StreamAuthorityGuard.java` | 6436；`e4add24b40f821f2edc3122e2b915155110d125ec1ae567c0dfa009ea791390e` | 1–97；再以rg核方法位置，没有重读其他类 |
| D `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java` | 36371；`7f29e6397283d72c85562a616b8488578475f5cf1071e4bc70471c131b1da412` | 380–434；仅固定旧response实现，未读O1c活动版本 |
| B `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | 33599；`7674bd68fb1dcb17a150d4a93dba68ada1f43c56b4546bac3fc59f7a89836e29` | rg签名/nextEventRef不存在；204–229、255–270、380–411、451–479、505–526、553–581 |
| B `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java` | 49744；`523d93b484f47065f15d1b998f182bb95d2f1bf79d154bff10685fa65a0f7518` | rg response；162–188、377–394 |
| A `admin-common/src/main/resources/agent-service-authority.json` | 86120；`fa37b6d8602e470e6511284b463b9905600aba1cd392a4f868927ce475515d9b` | rg events行902–938附近匹配上下文、response_open/check未登记搜索；未读全catalog |
| B `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java` | 2918；`3e53c32a47856fc52f0bc61f45ea40fea70143875c0aec9e76274035e44adf24` | rg签名/action；26–40 |
| N `engine-runtime/src/main/java/cn/agentplatform/engine/infra/ChunkBus.java` | 7105；`fe6e95db8ac4ff65700321f6ee9f7c157179d387296772f6d2ca7f5cad3514d4` | rg常量/预算/关闭；47–65、94–105、142–161 |

上表条目均与fix source579中的精确相对路径/bytes/SHA核对；物理冻结hash沿前置读取核验，ChunkBus本次明确重核。未把目录里存在base文件当作最新版。以下为前次接口核对已读、此次仅匹配manifest而没有重读的方法：B `TraceStore.java` 76375 B / `2187eea017f65173ce01e23bf268647445d845a3533ca7a43234967734f58516`（先前351–418的StatusReadProof）；B `RunAuthorityStore.java` 20003 B / `b49d3408c70f3ca553a1a12d4161b1508c60eadddadfeeb316e9a6955c9cf764`（先前loadObservationBinding/metadata接口）。这些只用于已冻结接缝推论，不声称此次重审全部snapshot算法。

补充实际读取：N `admin-common/src/main/java/cn/agentplatform/common/authority/MaterialAuthorization.java` 3752 B / `0834dc440e06341322cb0e4aeadb17227d3d245735821ca106b48fbfdb54b72b`，先manifest与物理hash核对、rg，再10–45；N `MaterialScopeV1.java` 同authority包，910 B / `3d3cd724c9f2a5d553a9194d1b65f1bf92f9abc80c8cad5616fd54d23604d092`，1–13。只搜索source579清单中的Material/PolicyEngine命名，未扫source树；未找到MaterialPolicyEngine类不等于声称仓库外不存在该概念。

稳定文档：implementation plan 118983 B / `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14`，本次rg O2/SSE/G6并窄读64–88、160–178，另rg命中188、239、472–480、567–581；不声称全文读完。O1c formal brief 23192 B / `9f1201688af6733a17c3a68e00a57141ce7bfdc292f05d4fb75320c942ea88e2`，本次相关标题/rg及38–57，其他接缝继承上一已冻结O1c notes，最终必须diff。

`contracts/trace-model.md` 43889 B / `7d0fe91476d6336877b00ece046d5e2daf1a239bf316a7d0b2f6c4507eaacb5f`，本次rg seq/Last-Event-ID/回放相关85、440、526附近；`contracts/snapshot/detailed-design-v1.2.md` 239660 B / `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`，2880–2893。其余四契约仅继承既有约束，本次未全面重读。

旧architecture brief 2785 B / `868754dc6510fac3781bab46da077e6bac2429ff37bd2ebb18ebe8cfcae1fb68` 本次全文；旧seam proposal 38357 B / `e6dede6002498c1580a607e39fd2c3fa3dd4703359e847892fe5319d3ea6a0e4` 本次仅SSE/lease/PORTAL相关rg与原发现；这两项SHA继承旧read-manifest。既有O1c notes/candidate未改。本文是单一候选交还物；交还后冻结，root另写正式brief。