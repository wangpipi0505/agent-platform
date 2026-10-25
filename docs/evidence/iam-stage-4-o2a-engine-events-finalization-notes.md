# O2a common＋engine 事件流：实施前细化 notes

状态：候选，未实现、未测试，不授权打开接口。root 可据本文形成分步正式 brief；须待 O1c Accepted 后先核最终 API/manifest。本文只新增本文件，原 O2 proposal/cursor notes/四契约/生产测试源/target/执行索引保持不变。未读取正在实施的 O1c 源或 target，未运行测试、probe、服务或数据构造。全部命令显式 workdir 为 `G:/207/项目/算法重组工具/代码/agent-platform`。

## 1. 固定结论与实施拆分

继承 O1b Accepted frozen source579，及两份冻结只读结论：O2 proposal 40892 B，SHA256 `4b8014b2081d7e7a73783b16fcfe3da6e4df423a6520445ec351d66a7d5c59da`；cursor-scope notes 16195 B，SHA256 `d2c6fad0c7d79d92acb32f74aaec85433f0dcdd1333526e5e157c6778923a742`。

O2a 是独立 engine receiver 的 PORTAL-only 持久事件流。fresh run-snapshot EVENTS 观察 grant 与 execution app-version grant/strict/Retained 写路径独立；不接受后台 DELEGATED source、INTEGRATION completionPermit、STATUS proof 或 QUERY ticket替代。pure FLOW 与可信受理范围沿 O1b，不放开 CHAT/模型chunk/技能/恢复。gateway 尚不对浏览器开放events；O2a通过不等于O2b或G6整体通过。

| 步骤 | 精确交付 | 允许开启的边界 |
|---|---|---|
| A：common 固定 PORTAL response-current | EVENTS双向purpose、signed open/check、private异步holder/capture、受控authority profile行为测试 | 只形成能力；缺安全engine handler时业务路由仍拒绝 |
| B：engine 单事件只读能力 | bounded metadata→全部必要scope/digest→单payload验证，private emission proof；真实PG测试含>64 parents | 不安装公开events新分支，不调用旧裸SQL SSE |
| C：engine 实际 HTTP SSE | admission/open、资源预留、同步首轮检查、异步writer/续核/取消/错误；真实HTTP＋PG窗口测试 | A+B+C整体就绪且明确有限assembly安装后才允许IAM admin events |
| D：handoff | 封存接口、证据、差异、O2b待接点 | 不自动打开gateway路由、不宣布跨hop已验 |

A/B可分别审查提交，但不能因为common purpose已放行，就让旧 `RunController.events` 的Actor.current＋裸payload查询接到新票据。新增handler readiness必须先于旧fallback，缺任何配套bean一律关闭；不得自动降级回旧handler。测试明确验证“A已装、B/C缺失”的真实route仍无payload。

## 2. common：最小签名 profile 与私有 API

以下方法/类名是实施候选，最终签名在O1c交还后核；不要求为了这些名字提前建立通用响应框架。

### 2.1 purpose 与 API 面

- `RunObservationSelectors.events(runId, acceptedSha)`：资源仍为run-snapshot，新增固定EVENTS action配对。`requirePurpose`双向约束target=engine、caller=gateway、mode=delegated但source/origin=PORTAL；原STATUS/Q配对与execution规则保持。现catalog已有 `proxy.engine.get.RunController.events`，不新增重复admin行或ANY。
- `ServiceAuthorization`增加本地 `OBSERVER_EVENTS` 读目的/私有admission获得点。必须由同frame实际签名服务票、当前授权与精确资源获得；public Actor/VerifiedAuthority/DTO不能自己铸造。保留原 `requireInitialFor`/bound检查的模式，但不把原写用Retained直接交异步线程。
- `ResponseAuthorityClient`（或同包有限专用接口）候选 `openPortalEvents(admission, binding)`、`checkPortalEvents(lease)`。open要求实际原frame且purpose=EVENTS，输入不能是任意public record。HttpCurrentAuthorityClient完成真实签名/绑定核验后才铸造private lease；旧generic open/check及INTEGRATION完成类型语义保持。
- 新窄 `PortalEventResponse`/holder：只能消费一次verified lease，持停止位、generation、当前签名capture、原期限、资源/来源和取消句柄。对engine只暴露 `currentCapture()`、`requireLive(capture)`、`close()` 等窄操作；capture只提供必要subject/resource标识及 `requireMaterial(scope)`，不导出Actor/ServiceTicket/Retained/STATUS转换。
- 同包复用 `MaterialAuthorization` 的validate/cover/scopeDigest规则。必要窄重载只接收private Portal capture；禁止new一个任意VerifiedAuthority绕过异步生命周期，禁止调用旧requireForResponse取得completion access。

是否在既有ResponseAuthorityClient上加方法、还是新增一个仅PORTAL接口，可由root按O1c最终接口选择。不得为了默认实现方便把未实现方法变成allow，缺实现应明确拒绝。

### 2.2 fresh current 的签名关系

open/check必须返回真实新current证据，不能只续checkedUntil。固定response profile至少核：issuer/算法/kid/typ/token_use、receiver=engine、原caller/action、subject、source/origin=PORTAL、grant/operation/source关联、responseId、run-snapshot、canonical observation request digest/cursor、当前decision ID/authorizationVersion及当前roles/attributes/必要ceiling、checkedUntil、原source/grant expiry与hardDeadline。将source/资源/response/原operation等不可变字段与opening及previous逐项比较；续核只允许固定可变current/version与短时有效窗变化，不能换run/owner/parent或加completionPermit。

当前roles/attrs必须来自签名响应和实际authority判断。openingAuthority的旧roles不能换一个expiry后继续用；历史material sourceAuthorizationVersion不同于当次current是正常情况，不能重写为opening版本。

原Portal与观察grant expiry为硬终点；响应deadline≤首次admission+55秒及其他更早边界。serviceJWT只用于admission，原response的合法continuation可在仅serviceJWT到期后续核，但不得产生新动作/新exchange或新响应。current检查窗≤5秒；所有本地TTL、最大续核网络时间和取消都受原hardDeadline限制。

O2a standalone engine response无跨hop parent不等于允许伪造parent。它是authority明确签发的一跳响应；任意自报parent/header拒绝。O2b将增加另一种固定child-open输入，届时要求真实parent关联，不把“没有parent的模式”当跨hop绕过路径。O2a不提前增加gateway本地G action或假parent签发。

### 2.3 异步 holder 的停止/续核

同步admission在原frame里完成身份、fresh授权和open，再将独立holder交给writer；旧frame结束不使holder依赖thread-local继续存在。holder没有写/执行权限。

online open/check全在PG事务外，不能在数据库锁或socket写互斥锁内做网络。单飞renew用generation/停止位提交结果；close先置stop，再best-effort取消reader/statement/subscription，迟到renew不能复活。watchdog最多1秒调度本地期限/续核，writer也主动检查，不靠idle是否有数据触发续核。wall expiry加单调elapsed上限，不允许时钟回拨重新给55秒。

若capture在读取/序列化过程中被续核替换，旧emission不重贴新generation标签。最小实现直接丢弃该未发帧，用新capture重新读/验；次数受原时间窗口和有限重试上界约束，不能无限重试。这样O2a无需缓存所有parent政策供未来current重新解释。未查询的远端撤权不能由本地检查凭空发现，≤5秒窗口不包装成全撤权SLA。

## 3. engine：一个有界读事务，只产生一个待发事件

建议独立 `RunObservationEventsService` 负责同步admission/循环协调；infra新增窄event reader（独立类或MaterialScopeStore窄方法均可），不把statusPublished整体调用当成events实现。

候选入口：`readNext(bindingKey, effectiveCursor, privateCapture, reservedWorkspace)`，仅受信service可调用；返回私有 `NoEvent/ResetControl/EventEmission`，不是公开Map授权。不能仅传owner DTO或public scope record就调用payload入口。

1. **事务外**取得有效capture和容量工作区permit；未获得时不进SQL、不排无界队列。检查ambient，建立同DataSource的顶层readOnly REPEATABLE READ；事务timeout/statement timeout不超过剩余current/hardDeadline的本轮预算。没有网络、writer、sleep或跨轮等待在事务中。
2. 仅加载可信observation binding/initial/三根metadata，暂不读根payload；后续沿O1b真实snapshot规范化与accepted/candidate关系验证。root/owner/legacy判定不能靠gateway自报；不知道/不归属404，归属但legacy/缺受理材料按O1b既有403，不补历史binding。只读initial/material验证不能resume原execution grant。
3. 只查下一event的locator/seq/type和manifest metadata，不查payload。在当前RR内选定该版本，再取得该event真实直接父集合。对根、event及其全部直接父的真实PG row/producer/kind/locator/version/owner/initial/scopeSha与floor作完整检查；scope中父hash数组也必须完整计入字节预算并重算摘要。动态parent只做基线的一层直接父核验，不递归读取祖先payload。
4. 当前capture覆盖全部将消费的scope；每个scope的sourceDecisionId/sourceAuthorizationVersion作为真实历史provenance保留。parent是可信writer事实，不是新的权限；不把压缩floor facts视作完整scopeDigest等价。
5. **全部必要metadata、scope和digest通过后**才查询event payload。在同RR读取真实投影 `id,run_id,seq,type,payload,created_at`，沿现精确数值/JSON规范验证MaterialRef.payloadSha256。只hash公开payload是不够的。先前仅加载metadata的必要root snapshot/refs也必须等本轮全部所需scope预检成功后才读取、核摘要与accepted/candidate/pure FLOW关系，再读event payload；不消费的root输入payload不为凑全量而读取。负例分别记录root/event查询，metadata阶段失败不能预读root或event业务payload。
6. 事务结束再核同capture，构造完整有界SSE帧并绑定其实际bytes摘要、事件locator/seq、run-snapshot、capture identity/generation与holder停止位，铸造private EventEmission。只有实际reader完成以上步骤才能mint；调用者不能把任意Map放进Emission。
7. 原始parent metadata和解析中间对象在本轮结束即释放。Emission只保留一帧必要bytes和定长绑定/私有capture，不保留全run父scopecache。因为写前必须仍是原已验capture，O2a不需要为未来generation留一套“已允许”政策；generation变化就丢弃重读。

### 3.1 O2a 精确调用顺序与 bounded binding 新接缝

建议正式锁定以下顺序，避免先调用旧loader再补预算：

`实际engine安全入口/ServiceAuthorization.requireInitialFor(EVENTS)` → `拒ambient并核原frame/call/resource/cursor` → **事务外** `openPortalEvents(admission,binding)`，得到本response真实fresh current → 创建唯一private holder/capture → 预留observer slot和metadata workspace → **一个短RR** `RunAuthorityStore.loadObservationBindingBounded(db, subject, publicRunId, budget)` → 三根/snapshot及本轮event全部metadata/scope校验 → 必要payload/digest → commit → requireLive同capture → mint/serialize emission → actualstream后requireLive → write。

此新bounded loader是候选必须新增的窄API，不能在内部先调用原 `loadObservationBinding` 或 `load(true)`。它先复用原owner/publicId/unsupported/has_tools的标量查询语义，再对authority initial JSON及三根manifest做长度/数量预检和有限projection，最后完成同等control/root/initial关系验证并返回同包只读binding。必要时提取不发SQL的校验函数共享，原O1b/O1c已验入口保持原实现与契约，不做无关全局重构。

命名风险精确限定：已冻结 `loadObservationBinding`:105–120 的首个查询本来是窄标量；其117行随后调用 `load(...,true)`，后者122–137读control `a.*`、解析initial并加载三根；`MaterialScopeStore.scopes`:136–147直接取scope::text。**只有当新O2a按候选复用这些整段方法时**，才无法满足本次新增“JDBC取得完整JSON前限额”的容量要求；本文不据此推断或否定accepted O1b/O1c行为验收，也不声称O1c活动代码正调用什么。

后续每轮：事务外holder `currentCapture()`必要时在线check → 取得workspace → 新短RR bounded loader/next event → 读后与commit后同capture检查。根缓存默认不启用，因此不从旧frame复用绑定当新的许可。没有 `RunAuthority.current/resume`、没有写用strict refresh、没有在材料loader内发HTTP。capture在RR中失效则结束该轮并丢弃，不能在原事务内续核后继续。
run-state/watermark只在实际要输出相关control或判断cursor/terminal时读取，并同样先scope后payload。不能将裸state查询隐藏在控制分支。没有event时短事务退出，再在事务外等下一轮；并发新event在下一轮新RR中按其scope检查，不能握手时预授权未来材料。cursor只在完整帧send完成后推进；flush不能证明浏览器已消费。

## 4. 真正容量界：建议值是容量策略，不是合法run上界

已证实当前集合 `C=7+4N+4A+E`，event直接父 `D=C+U`（U包含同动作新增不同revision）；完整父scope又带自己的parent数组。25/40只是默认maxSteps/modelCalls，不能推出node/attempt硬界。O2a无法承诺所有合法run都在一个固定metadata总预算内完成；应承诺容量内完整验证、超限明确失败，绝不截断/跳过scope。

### 4.1 可执行的首切片容量候选

以下是一组便于正式brief裁定的具体起点，**未测量、不是最终数字，也不是原公开契约**：

| 项目 | 候选限制 | 检查位置 |
|---|---:|---|
| 单轮全部required metadata UTF-8文本（含三根、initial、选中scope和全部直接父、必要control） | 8 MiB | 先查长度/数量元数据，累加溢出安全；超限不返回大text |
| 单份scope或initial文本 | 1 MiB | SQL CASE或等价有界返回，客户端解析前核 |
| 单轮不同scope记录及每份parent列表项 | 各≤4096 | 读metadata locator/array长度时；多取至limit+1判超限，不截断当成功 |
| 单份JSON深度/token/string | 深度32，token≤65536，单string≤65536字符；字段集合另按真实schema有限验证 | 真正streaming parser限制，不是先建无限JsonNode再检查 |
| 同时执行metadata准备的工作区 | 2个permit，无无界等待队列 | 进入RR/分配大buffer前；读完释放 |
| 本轮实际需要的root业务payload（snapshot/refs等） | 合计≤8 MiB；每份仍须SQL受限返回；仅必要control投影另≤256 KiB | 全部本轮scope通过后、JDBC取完整text之前；DOM深度/token/字符串也设有限预算 |
| public event payload | 既有262144 UTF-8 bytes | 材料授权通过后的真实payload SQL与编码器 |
| 完整单SSE帧 | payload上限加经计算的id/type/data/换行固定开销 | 先计算精确UTF-8长度后分配；不能仅用BAOS.count |
| 待发业务帧 | 每observer最多1帧；沿既有全局response预算/订阅slot机械约束 | 分配、排队、实际写和blocked writer期间 |

为什么不是又一个64假设：4096/8MiB等是**显式容量拒绝边界**，不据此声称更大合法run不存在。要宣称支持更大样本，实际样本必须在所有字节/token/parent/revision限制内跑通；不能只看scope count。>64 parent正例要求真实writer生成至少65个不同直接父且全部通过，不要求任意4096-parent极端run必成功，因为其他独立预算仍可能先触发。

正式实现可选择其他常量或提高预算；root应结合测试时间/内存实测裁定，不新增生产配置或将常量误写成业务合法性约束。若要求“所有目前合法run均不能因metadata大小失败”，本固定总量方案无法证明满足，须先解决全局业务硬界或另立分片/增量验证范围，不偷偷改writer/history/schema。

### 4.2 SQL与真实中间副本

先metadata预检不等于先把巨大scope::text拉进Java。查询scope长度、parent-array长度和受限locator；只在预算允许时返回对应完整文本。缺字段/错误JSON类型按材料损坏，不伪装容量超限。计数limit+1、求和long溢出、重复hash、查询返回重复row都必须显式处理。

可行SQL形状（仅候选，未执行）是先按已确定run/hash查询标量 `octet_length(scope::text)`、`jsonb_typeof(scope->''parentScopeSha256'')`，仅当类型为array时取 `jsonb_array_length(...)`；行数用limit+1检测。此时不select scope本体、parent JSON、initial JSON，也不对这些列调用getString/getObject。检查选中scope自身预算后，第二步用 `CASE WHEN octet_length(scope::text)<=? THEN scope::text ELSE NULL END` 取受限文本并核返回的长度/身份；先对这一份有界scope解析得到最多4096个已校验64hex父hash，才生成固定上界的父查询。之后同RR先取得全部所需父的长度/数量标量并通过总预算，再取其受限完整文本，缺失/重复行拒绝。initial JSON同理，不能先取a.*再测量。JSON类型不合法与NULL语义必须用明确状态位区分，不能把坏metadata当超限或把CASE的NULL当合法scope。

PG服务端计算octet_length可能需要扫描/展开JSON，该工作不等于Java已分配对应巨型字符串；本方案只声称控制客户端返回/分配，PG CPU/内存仍须以statement timeout和有限并发限制，不能宣称仅用CASE就消除了所有数据库资源成本。所有探测属于metadata查询，不触event payload；第二步仍必须验证真实row内容，长度probe不是权限证明。
不能在授权前为了限event大小先读其payload::text。event长度/投影大小检查属于全部event scope通过后的payload阶段，实际PG读取计数应据此记录；超大event可读到长度/受限投影信息但不返回/发送超限payload。原材料摘要验证与容量检查各有明确失败位置。

JDBC String、UTF-8 byte[]、JSON节点/集合、初始scope、parent hash列表、规范化digest输入、事件投影、公开payload string和SSE帧可能同时存在。8MiB不是Java heap实际8MiB：UTF-16 String可占双倍，规范化/序列化会复制，DOM有对象开销。正式实现应选“每次只解析一份scope、总raw文本≤8MiB、避免全scope DOM列表、增量汇总/即时校验”的窄方案，记录实际最大backing capacity、同时存活副本数与集合元素上限；不能只测最后wire长度。

每份已验scope若确需重新读取以验证floor，可以在同短RR二次按已验hash取metadata，但仍计总工作/时间预算；不得为省内存把中间scope写临时文件/对象存储或跨事务混revision。任一scope自身parent数组超过单份限制安全失败，不把它裁成前4096项。各scope仍完整重算scopeDigest；不改digest算法。

root snapshot同样不是免费的小对象。上述metadata预算不包含它的业务JSON，必须另给真实需要的root payload设置总字节cap，先以SQL受限投影/长度状态控制JDBC返回，再做精确JSON规范化与GraphRules；建议root解析深度≤64、token≤262144、单string≤1MiB作为另待root定的容量策略，不能借GraphRules只限制node类型就宣称内存有界。本轮workspace必须同时计metadata raw/解析态、root JSON/规范化副本和单event投影；必要control投影也限额。根payload超限属于容量失败，不修改历史snapshot或绕过摘要。若不需要run-inputs实际内容，只验其必要scope/floor不读payload。
两个工作区permit限制**大metadata准备的并发**，并非精确heap字节保证；每工作区的文本/token/集合数量有限才形成可审查的资源界。正式测试应观测最坏中间数组capacity与释放路径，不能用permit计数代替内存分析。旧16MiB response预算只覆盖其原payload reservation，新增metadata workspace不能默称已被它覆盖。

**组合预算不能乘上旧queue。** 新O2a不将metadata bundle或EventEmission放进ChunkBus的32帧chunk队列；每连接最多一份pending业务帧。最多两个metadata workspace限制大JSON准备并发，单workspace同时存活metadata raw文本总量≤8MiB，另含本轮必要root payload≤8MiB及有限control/event投影并配合上述单record/parser边界；订阅slot另限制活跃连接。frame分配之前取得全局response reservation，直到实际sender释放frame才归还。原reserveResponse只计256KiB payload，完整帧额外framing容量必须另行有界计费，或在窄机械API里按实际frame容量计费；不能把包含额外bytes的byte[]冒称完全落在原16MiB内。

若继续使用原256KiB固定reservation，最大同时持frame数还受16MiB/256KiB=64限制，必须另给每帧已计算的framing开销设置全局有界池；不允许256个连接各自绕过reservation分配一帧。若新API改按完整frame实际capacity计费，也不能改变旧调用方计费语义。byte数组之外String/DOM/集合的有界副本同样列入workspace设计与观察，不能声称semaphore数字就是实际heap使用量。

为避免pure FLOW的IAM观察者无用地接收临时chunk，可在ChunkBus中新增窄“仅占observer slot、不注册chunk队列”的机械lease，或由有限Events组件提供经root明确批准的独立slot预算；这是scope需明确的二选一，不通过创建一个不消费的旧subscription隐式承诺queue为0。被取消但仍阻塞的writer继续占其slot/frame额度；metadata query取消后未真正退出也继续占workspace，防止反复取消造成未计费并发。
O2a暂不建立根cache；三根每轮受预算约束重验。若root选择固定根cache，仅缓存该response的3份已验immutable根，必须计入全局/每observer预算、在当前capture下重核覆盖，不缓存allow，不把常数3优化说成解决D/H增长。

### 4.3 不持长RR准备大manifest

metadata准备只有本轮current剩余窗内的短RR；不持续续核以延长同一PG快照。expiry/timeout发生则rollback/关闭statement，释放workspace和未发帧；外部续核后另开新RR。单轮4096个父查询若实际超时，就是有限容量/时间内无法完成，不允许在事务里call authority或延长到55秒。

为减少往返可一次受限批量获取已选parent rows，但必须先证明总返回byte与每行上限；不能无界queryForList后再累加。大manifest分片网络传输属于O2b待定，不在O2a持RR给网络逐页发送。O2a不用为将来私有协议预建通用spool、lease框架或任意导出接口。

## 5. public SSE writer 与容量错误依据

### 5.1 首次输出之前

建议同步admission做：原始路由/cursor→fresh EVENTS admission→事务外response open→预留observer slot/首轮workspace→同短RR bounded owner/root及首个control/至多一个event检查→退出RR并核capture。失败在返回/提交异步writer前处理，因此通常可给真实HTTP错误，而不是已经发200之后补JSON。无event也只准备已授权的初始control，不等待事件出现才返回。

异步writer接到的是实际OutputStream和private holder/emission。先完成有界序列化与proof核验，再取得实际Servlet stream（若框架已经传入，则以实际callback入口为获取完成点），**获取后再次核同proof**，然后才write。不得将提前获取的可阻塞stream当无副作用getter。framework可能已提交headers时，必须根据实际isCommitted选择关闭/reset，不能保证所有writer延迟失效都能改成HTTP403。

首次及每帧均在序列化后、实际stream可用后、写前/flush前核同capture与停止位；分多次业务write时每次都核。最小可用完整帧byte[]一次write减少窗口，但不宣称TCP原子或write阻塞期间持续在线授权。生成完帧至进入write的闩锁窗口必须测试。续核后旧emission不复用；已进入底层write的字节不可追回。

单writer负责所有业务/control，watchdog只通知停止/取消资源，不能并发写reset。授权失效可最多发送既有静态AUTHORITY_EXPIRED/DENIED/UNAVAILABLE reset，无event/status/watermark等业务事实；blocked peer时允许没有reset，不能承诺强制物理断开。

### 5.2 现有依据与待 root 裁定的扩展

冻结 `ChunkBus.subscribe` 59–61 已对closed或slot不足抛 `PlatformException(429,"STREAM_CAPACITY",...)`。这是现有**订阅容量**的真实实现依据，并非已发现四契约对任意metadata预算规定429。

冻结 `ChunkBus.offer` 118–126 在全局byte permit不足时 `stop("BUFFER_LIMIT")`，queue满时SLOW_OBSERVER；冻结RunController 289–304将subscription.resetReason作为stream_reset.reason，带原chunkReplay/lastEventId/recovery。故 BUFFER_LIMIT 是已有公开reset值，但原触发是chunk buffer，不是已实现的metadata预算。冻结RunController 327–342对超大持久event发既有EVENT_LIMIT/FINAL_SNAPSHOT reset。

**建议供root正式裁定：** 新IAM opening阶段metadata/解析工作区容量不足沿 `429/STREAM_CAPACITY`；已经committed时沿既有 `BUFFER_LIMIT` reset后关闭，保留现有控制字段和值；payload超过原256KiB仍用EVENT_LIMIT。不新增公开error code/reason、不截断合法事件、不把资源压力标成AUTHORITY_DENIED。这是把新有限工作区纳入已有容量类别的明确语义扩展，须由正式brief批准，不能说四契约已经自动允许。

若root不采用该扩展，最小替代是在新路径保持关闭，先定义沿已有语义的精确错误映射；不能以裸500/空200把容量失败隐藏。capacity错误与metadata篡改、当前撤权、internal credential故障分开。当前不可用/expiry优先按实际authority原因停止，不能为了发BUFFER_LIMIT跳过授权停止位。业务wire=0允许合法错误envelope，不表示socket字节必为0。

### 5.3 取消与资源拥有权

open后到writer启动前失败、async未启动、正常terminal、55秒届满、renew失败、IO断开、client取消都要幂等清理。stop先置，取消statement/upstream等待（本切片无gateway上游流）及订阅，再由真正持有者释放buffer/slot。

旧ChunkBus.close:149–160明确“stopped but blocked SSE仍占slot直到sender退出”；保留该机械事实。新实现不能watchdog先归还slot/大frame预算、blocked writer仍持bytes，从而允许重复连接无限占内存。metadata workspace在不再有JDBC/解析工作占用后释放；未返回的query不能因逻辑stop立即把同workspace借给另一请求。需要记录cancel后的迟到返回如何丢弃且释放，不许新mint Emission。

观察断流不调用cancel run，不中断后台执行grant。首切片不消费临时chunk队列数据，公开chunkAvailable按本路径能力如实为false、chunkReplay=false；是否复用ChunkBus subscription纯slot机制由root决定，若另设有限slot也必须保持相同资源语义而非绕过现容量管理。

## 6. cursor 与 route 原始输入

root已定：query与header都合法但不同仍query优先，不新增冲突400；重复或无效选定cursor拒绝。选择在第一信任边界完成；无query才解析header，两者都无为0。若合法query遮蔽另一个header，后者不能被engine再次选中。

O2a独立receiver必须核raw route只有admin GET且启用安全handler，debug/v1共享action仍不开放；private purpose/transport字段不能由客户端自报。选定非负long以规范十进制绑定run/resource/source和逻辑request摘要。独立engine测试既测试header/query行为，也测试固定内部规范载体；O2b将统一由gateway ingress选定后只发一个内部query，不混发两个carrier。

共享逻辑requestSha不是任一hop的原始URL hash：签名profile固定schema、GET、public route/run、run-snapshot、原source/SubjectKey关联、effectiveCursor；各receiver/实际目标另由call/response核验。原raw入口仍有本地capture。若O1c最终接口的同名字段已有raw HTTP定义，必须明确区分/绑定，不暗改旧定义；目前不假称已核O1c API。

## 7. 与 O2b 的必要接缝：现在不实现

O2a可交还的稳定事实：固定EVENTS resource/action与有效cursor；private engine admission→response current→capture→已验EventEmission的mint/consume点；真实scope/digest规则、容量错误与行为证据。EventEmission是engine本地私有对象，不能HTTP序列化成gateway权限。

O2b仍需新增：gateway自己的G接收action/current、真实authority parent/child关联签发/验证、同来源/资源/request/deadline；固定内部完整manifest/帧协议、gateway独立current的逐材料终检和实际最后一跳检查。root已定不能降为仅观察action检查。完整scopeDigest重算与只收可信floor facts的证据差别继续保留，后者不是默认替代。

O2a不导出全runmanifest、不增加任意scope下载endpoint，不提前保留8MiB元数据到网络writer。O2b若要完整manifest输出，可在future固定internal transport reader中另行准备有界bundle或分片；其数据库RR必须在网络输出前结束，超current窗需新读，不能跨在线调用维持事务。大单scope parent数组分片/规范digest增量验证是该阶段真实新增工作，不用一个备用接口假装已实现。

未来安全迁移方式：child-open profile与内部transport形状由authority签名绑定，engine选择专用internal serializer；public standalone SSE不接受调用者任意请求“省略parent”的fallback。是否继续支持明确的一跳receiver由正式profile权限决定；gateway public路线只能使用实际绑定child响应。不得以Boolean header或公共DTO打开弱路径。

## 8. 文件边界与 O1c 后必核接口

| 步骤 | 最小候选位置 | 禁止顺带改动 |
|---|---|---|
| A | common RunObservationSelectors、ServiceAuthorization、固定ResponseAuthorityClient/HttpCurrentAuthorityClient入口；少量PORTAL私有holder/capture类型；LoopbackAuthority相应固定profile与新test | 不改执行root/grant/旧completion permit，不放开integration/debug/v1，不复写O1c短proof |
| B | engine RunAuthorityStore新增loadObservationBindingBounded（仅复用旧纯验证语义，不调用旧整段loader）；MaterialScopeStore窄只读event metadata/payload方法或独立同包reader；新有限EventsService | 不复用StatusReadProof去掉thread限制；不改append历史writer、schema、migration/POM；不重写snapshot |
| C | RunController中只对安全IAM admin分支dispatch；有限显式engine装配/依赖提供方式按最终baseline确定；必要机械预算封装 | 不默认全Boot启用，不将旧events裸SQL路径接到EVENTS，gateway仍关闭 |
| tests | common PortalEventsAuthorityProfileTest；engine PortalEventsMaterialPostgresTest、PortalRunEventsHttpPostgresTest（均候选名） | 无gateway/SSE联链成功声明，无CHAT/模型/RR1恢复实现 |

O1c Accepted后准确diff：CurrentAuthorityClient/HttpCurrentAuthorityClient新增固定交换入口及caller/source绑定；ServiceAuthorization/selector的私有purpose校验和frame；metadata client7字段/4096data与总wire8192；Boundary新STATUS构造/错误catch；common tests fixture profile的最终注册。engine以O1c完整handoff逻辑manifest为起点重新核本次引用的O1b frozen类，不能用579旧清单覆盖新source。

C装配具体文件须按最终frozen engine装配确认；本次未读活动装配，不虚报已有bean名。common的EVENTS验票与engine安全handler启用必须有一致的关闭测试，不能靠“以后会安装bean”填补暴露窗口。

## 9. 实际红绿要求与建议命令（未执行）

A在真实Loopback HTTP/JWKS上验证签名profile；B在真实PG immutable材料上；C在独立engine JVM/模块classpath Receiver上用真实HTTP/Servlet输出。受控fixture可作为gateway凭据端，但必须明确它不是O2b gateway链。所有SQL观测核实际select列/参数/返回行，不能只用旧counter。

| 组 | 正/负例与可判定结果 |
|---|---|
| capability/red gate | 旧闭route对fresh EVENTS真请求失败；A安装但安全handler缺失仍业务SQL0；仅A+B完成不能误接旧handler。编译不存在不算行为红 |
| open/check | 签名fresh current实际角色/属性变动；错误subject/resource/action/source/receiver/requestSha、假response/过期/replay均拒；legacypublic constructor不能mint；服务票期过但原source/grant有效仅原response可续；关闭不复活 |
| private async | servlet frame结束后真实worker仍只凭私有holder合法读；普通Actor/VA/STATUS/Q/Retained伪转拒；跨holder/generation/已closed emission拒；INTEGRATION access调用0 |
| owner/root | 未知/他人404、归属legacy403、accepted/candidate不符、同app不同run、Q到E间metadata改变；错误材料前eventpayload0，真实摘要错eventpayload>0但业务wire0 |
| >64直接父正例 | 通过真实可信writer创建至少65个不同parent hash，保留全部父数组及真实revision/source版本；新reader完整通过，接收正确event。记录N/A/E/U/D/H和metadata真实bytes，不手工删父或裸插简化scope |
| 容量边界 | 每一独立cap分别正好/超过：总bytes、单scope、parent数、token/depth/string、工作区并发；记录中间buffer capacity与同时存活副本，超过时选中eventpayload0；不得把正例只限制52份 |
| 同动作revision | 多次append后event直接父含旧/新revision，不能按kind/id错误去重；历史授权版本与opening不同仍合法 |
| scope/floor | 少parent/重复parent/错scopeDigest/错locator/初始floor缺失/超current、错误row投影摘要；完整父scope和所含parent数组被验证，无截断/递归读祖先payload |
| 时间/并发 | 闩锁卡nextEventRef前撤scope/503→下一payload0；payload后/commit后/current刷新→旧emission不写；新event并发与retention/reset只在一致RR解释，无长RR跨renew |
| 真实stream窗口 | 序列化后、getOutputStream阻塞返回后、进入write前/下一帧/flush前推进clock/stop；业务write0或停止后无下一write，已开始write不声称追回；实际isCommitted前后错误不同 |
| idle/取消 | idle watchdog、renew超时迟到、query取消迟到、async从未启动、blocked writer保留slot/frame预算；stop先置，资源最终释放，cancel-run=0 |
| cursor/control | query优先含不同合法header；无效选定/重复/溢出；ahead/expired/terminal控制scope受保护；授权reset无status/watermark/payload；seq只在完整send后推进 |
| regression | 原POST、STATUS完整response、Q元数据、旧completion/legacy路径的相关既有测试；不将这些回归算RR1/全G6新完成 |

候选命令，最终test类名确定后由root采用：

`mvn.cmd -B -ntp -pl admin-common -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=PortalEventsAuthorityProfileTest" -Dsurefire.failIfNoSpecifiedTests=false`

`mvn.cmd -B -ntp -pl admin-common,engine-runtime -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=PortalEventsAuthorityProfileTest,PortalEventsMaterialPostgresTest,PortalRunEventsHttpPostgresTest" -Dsurefire.failIfNoSpecifiedTests=false`

Receiver按单模块classpath/独立进程启动；用Clock/闩锁而非长sleep。全流程尚未执行，命令不是测试记录。>64正例及容量差一边界若不通过，不得用“更安全的全拒绝”作为完成。

## 10. root 可直接裁定的有限选择

1. 分A/B/C实施；A/B route关闭，C才整体启用。既有O1c未Accepted时只准备文档，不派实施。
2. 采用§4候选容量起点或根据有限实测调整；明确其为容量策略，不改变run合法性/历史材料。若要求无metadata容量拒绝，需另立完整分片/增量方案，不能称当前固定预算已满足。
3. 明确批准或修订§5.2对新metadata容量不足沿429/STREAM_CAPACITY与BUFFER_LIMIT的映射。已有值有冻结依据，新触发归类仍需正式brief确认；不自动新增公开code/reason。
4. O2a暂不root cache/不导出私有manifest；C复用已有订阅slot机械能力或新增有限同义slot封装，以不引入无用chunk订阅副作用为准。
5. common方法放旧接口或窄新接口、engine reader独立类或同包方法，按O1c最终diff选最小修改；不需要为这些常规选择再次问human。

## 11. 来源与本次新增窄读

原O2两份冻结文档中的source579定位、完整SHA与ranges继续有效；本次未重新全面读取那些类。关键继承路径：B=`docs/evidence/iam-stage-4-o1b-readonly-status/frozen-overlay/` 下 MaterialScopeStore 33599 B / `7674bd68fb1dcb17a150d4a93dba68ada1f43c56b4546bac3fc59f7a89836e29`；TraceStore 76375 B / `2187eea017f65173ce01e23bf268647445d845a3533ca7a43234967734f58516`；RunAuthorityStore 20003 B / `b49d3408c70f3ca553a1a12d4161b1508c60eadddadfeeb316e9a6955c9cf764`。均为engine-runtime/src/main/java/cn/agentplatform/engine/infra相对路径。writer父集合/预算默认值/GraphRules的精确范围见cursor-scope notes §5；未重读。

本次为容量错误归类新增两次窄读并重核物理SHA：

- `docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/engine-runtime/src/main/java/cn/agentplatform/engine/infra/ChunkBus.java`，7105 B / `fe6e95db8ac4ff65700321f6ee9f7c157179d387296772f6d2ca7f5cad3514d4`；59–64、113–136。slot保留依据149–160沿已读冻结记录，未重复整文件。
- `.local/iam-o1b-review-fixes/final01/source/engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java`，27185 B / `06ee90387fb6fb47e82695f59d288a14c56783a34d53cfd3c8bb7ba3f46ba366`；289–343。cursor194–209、实际send432–443沿旧窄读记录。

本次按root具名loader风险补读 B RunAuthorityStore.java 105–121、B MaterialScopeStore.java 135–155；load的a.*与scopes链接以先前已核122–138范围为据。未读取current或评断O1c活动调用。

§5.2的429/BUFFER_LIMIT/EVENT_LIMIT依据是这些冻结实现，不声称本次发现四契约明确列出所有新触发条件。trace-model 85/440/526/542、v1.2的INTEGRATION条款与implementation plan §2.4的期限/续核/每hop规则只继承前两份已读证据，本次未重读四契约全文。本文没有修改公开SSE格式、数据库schema或历史scope。

交还后本文冻结。所有候选API/数字/测试名仍待root正式brief；O1c Accepted后的接口差异核对是实现前门槛。