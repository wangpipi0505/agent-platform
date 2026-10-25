# 独立运行 status 与 PORTAL 观察：下一有限接缝提案

日期：2026-10-21。状态：只读架构输入，等待 root 编制正式实施 brief；不是实现完成、测试通过或生产验收。遵循指定 SDS 架构角色及 AGENTS。原 v1.2、四契约、实施计划和裁决均未修改。

## 1. 建议与证据边界

建议顺序：**O1 独立 PORTAL GET status → O2 PORTAL SSE 当前材料与实际双端流生命周期 → O3 RR1 INTEGRATION 原响应材料 → O4 RR2 模型后台新动作及其余消费者**。RR2 中既有 pure FLOW local advance 不倒退，也不能等同真实模型恢复链已完成。O3/O4 可以按不同依赖拆实施，但不得因此开放未完成入口。

O1 仅 `GET /admin/runs/{id}`，仅已有完整 run_authority/material_scope 的有限 published pure FLOW。保持原完整 status JSON；不扩大为列表、debug、v1/SDK、CHAT、模型/技能/确认/附件/回放。O2 仅对应 `GET /admin/runs/{id}/events` 的有限 PORTAL 流；普通 PORTAL 必须硬停止，不能借用 INTEGRATION permit。

source380/target1933、D4a/I1 接受状态由 root 提供，本次未复测、未累计、未扫描 target。没有启动测试、服务、浏览器或端口探针；没有读取前端半成品、邻接旧工程、旧 10GB 证据或依赖库源码；没有提交。本报告只基于下列已读冻结后端/计划片段，SHA 清单见附录。无新开源机制对照结论；使用 Spring/Apache 类名仅报告本项目现有调用点。

## 2. 真实路径及现状

以下路径相对唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。

| 文件/行 | 实际行为与限制 |
| --- | --- |
| `admin-common/.../authority/GatewayIngressBoundary.java:35,96–105,190–218` | 私有 Purpose 仅 RUN/SESSION；真实请求 anchor、已安装 filter、实际 HandlerMethod 和一次 claim 约束存在。RUN 只收无 query 的 POST `/admin/apps/{code}/runs`；不是 catalog 有 GET 就能入站。 |
| `platform-gateway/.../api/GatewayController.java:45–55,63–121` | IAM 分支 claim RUN 并转 PublishedRunProxy；旧复制循环在非 IAM 分支，8192 byte buffer 直接写/flush，没有响应租约。不得把 IAM GET 回落到旧循环。 |
| `platform-gateway/.../application/PublishedRunProxy.java:72–98,164–218,247–281` | 当前异步机器针对 POST JSON body；metadata→同原 source 第二次 main exchange→POST；不能直接复用它的 JSON body 入口处理 GET。可抽取现有取消/期限/输出终检设施，但 purpose 必须独立。 |
| `platform-gateway/.../application/PublishedMetadataClient.java:63–98,100–137` | 固定 app endpoint、独立 metadata selector/service ticket，严格四字段解析；只能查发布指针，不能用于历史 run（发布切版不应改变 run 钉住版本）。 |
| `admin-common/src/main/resources/agent-service-authority.json:794–828,902–934` | status 和 events catalog 已登记 gateway→engine，status action=`proxy.engine.get.RunController.status`，events action=`proxy.engine.get.RunController.events`。admin/debug/v1 alias 共用 action；v1 可 integration。catalog 是能力表，不是已接线证明。 |
| `engine-runtime/.../api/RunController.java:70–72` | 三个 status handler template：`/admin/runs/{id}`、`/admin/debug-runs/{id}/status`、`/v1/runs/{id}`，调用 `RunService.status(id,Actor.current())`。首切片须显式约束实际匹配 template，不能只看共用 action。 |
| `engine-runtime/.../application/RunService.java:1627–1636,1663–1689` | 受保护 run 调 statusPublished；原 composeStatus 保留 output/error/versionRefs/nodes/events/paused 等字段，并最终 requireCurrent。 |
| `engine-runtime/.../infra/TraceStore.java:277–287,325–345` | protectedRun 只读 id/authority existence 并限定 tenant/principal；statusPublished 在事务外读窄 id/root 后要求原 POST purpose、刷新 retained proof。GET 不能拿现方法直接成功。 |
| `engine-runtime/.../infra/TraceStore.java:346–442` | 一个只读 RR 快照重核 root/owner，先列全部必需 scope/node/event metadata，再核所有材料权限，之后读内容/摘要，最后本地终检。拒绝 tool_call/controlled skill 路径，不能以固定空 fileRefs 掩盖未迁移事实。 |
| `engine-runtime/.../infra/RunAuthorityStore.java:76–98,125–129` | Binding 的 root 为持久 `app-version/code@versionNo/resource_digest`；另有 acceptedSnapshotSha、owner、initial ceiling、数字版本及委托。load 比对 run 与 authority 行 owner/version/root，未读业务 snapshot/inputs。requirePurpose 只接受 POST main 或明确 DELEGATED local advance。 |
| `engine-runtime/.../infra/MaterialScopeStore.java:41–84,204–226,505–515` | `read(ref,VerifiedAuthority)` 恒拒绝；真实 reader 需要私有 RetainedAuthorization 和 live frame/local proof。先 scope/owner/root 再 payload，真实摘要失配属于读后拒绝。不能重新开放 public VA 重载绕过证明。 |
| `admin-common/.../authority/ServiceAuthorization.java:55–65,75–88`；`AuthorityContext.java:31–69` | receiver 从已验 service JWT 建立 retained frame，refresh 只能重核同 call/delegation/operation，requireBound 拒绝已刷新/关闭/跨线程旧 capture。不能把它放进 StreamingResponseBody 后继续当异步 current。 |
| `admin-common/.../authority/HttpCurrentAuthorityClient.java:151–203,267–294` | GatewayRunAuthorization 私有构造、绑定 POST main，带原 source/decision/票据/operation/grant/单调期限；RetainedAuthorization 也有私有完整证明。public currentOnly 不具备完整证据。两者可复用验证原语，不能把旧 POST proof 更名为 status proof。 |

## 3. O1：最小独立 GET status

### 3.1 metadata/query 与 main 的精确关系

新增仅内部 `GET /internal/runs/{id}/observation-metadata`，建议 action=`run.observation.metadata.read`；精确 caller=gateway、target=engine、mode=delegated、source/origin=PORTAL，integrationAllowed=false。endpoint 名及新 action 是本提案建议，尚不存在。

建议 `RunObservationSelectors.status(SubjectKey verified,String id)`：ResourceBinding.kind=`run-observation-selector-v1`；id 为 canonical JSON tuple `["run-observation-selector.v1",tenant,principal,publicRunId,"run-observation-metadata.v1"]`；versionDigest 为下述 descriptor 的 canonical JSON SHA256：`{schema:"run-observation-selector.v1",tenant_id,principal_id,run_id,projection:"run-observation-metadata.v1"}`。只接受当前 published 生成的 `r_[0-9a-f]{32}`；不加 query/body 自报主体。这是查询描述摘要，不是 run 数据版本。对 kind/action/caller/target/source 做双向 purpose 检查，query 票不得进入 status/events/write。

receiver 先经过 ServiceAuthorization 严格真实 handler/template/action，再用已验主体构建同 selector 并 requireResource；之后才窄查 `run.public_id,run.id,tenant_id,principal_id,app_code,app_version_id` 与对应 run_authority 的 owner、root、appVersionNo、acceptedSnapshotSha、initialCeilingSha。禁止 `select r.*` 或先调用 `TraceStore.run`。内部 load 使用 `a.*` 当前只包含 authority metadata，可供本域重核；对外 DTO 必须投影，禁止输出 grant_ref、initial_ceiling、origin token、完整 scope/payload。

建议成功 DTO 的固定字段：`runId,owner:{tenantId,principalId},root:{kind,id,versionDigest},appVersionId,appVersionNo,acceptedSnapshotSha256,initialCeilingSha256`。这些是固定 engine HTTPS HTTP 与真实 PG 产生的事实，不是 caller 发来的记录；gateway 严格字段集/类型/格式/owner/runId 检查，最大 4KiB、现 bounded deadline/no redirect/cancellation 模式。不存在或不归属返回既有 404/403 语义；无 authority/scope 的旧 run 不降级读取。

一次观察 GET 的 Q/M：

1. gateway 私有 `Purpose.STATUS` capture 来自原已验 PORTAL source，固定 `GET /admin/runs/{id}`；无任意 query、alias、forward/include/error dispatcher。source 只发固定 authority。
2. **Q exchange**：同 source → `ServiceCall(engine,run.observation.metadata.read,selector)` → 真实 engine metadata GET；engine 自己在线 authorize query ticket、重核 selector、查真实 PG metadata。此处 payload SELECT=0。
3. **M exchange**：使用**同一原 source**重新 exchange 到 status action 和 `run-snapshot / publicRunId / acceptedSnapshotSha256` 观察资源；不是 query grant resume/forward，不把 query current 变成 status current。Q/M 的 grant/operation 独立、subject 相同、origin authentication 一致。M source 已过期/撤权/503立即失败。
4. gateway 以 M 子票发原 `GET /admin/runs/{id}`；engine 不信任 gateway 附加 metadata header/body，独立窄查相同 run owner/root/acceptedSnapshotSha 并刷新 live M retained frame。fresh current、payload read 与最终 wire 放行均有实测检查点。

这里“两次”指两个逻辑 exchange；每个接收端 authorize 以及事务外显式 refresh 是额外决策请求。验收计数必须记录实际 query exchange/main exchange/receiver authorize/refresh，不能把总 IAM HTTP 数硬写成 2。

### 3.2 推荐 run-snapshot 观察资源，执行根保持原义

**推荐 O1 M 的精确 `ServiceCall("engine","proxy.engine.get.RunController.status",new ResourceBinding("run-snapshot",publicRunId,acceptedSnapshotSha256))`。** 公共 id 即真实 PG public_id；digest 为真实 run_authority.accepted_snapshot_sha256（accepted run snapshot digest），不拿可变 status JSON digest、candidate digest或metadata selector digest冒充。不同 run 即便同 app/同版本/snapshot内容相同，resource.id也不同；任一runId/版本摘要更改均不匹配票据。

这是一份**独立新鲜读许可**：Q与M都从同originalPortal独立exchange；M并不resume/forward原持久app grant，不写原run_authority，不把旧运行source改为新来源。因此无需迁移原持久 `app-version/code@versionNo/resource_digest` root、grant、initial ceiling、origin或既有材料。observer的新grant/operation与后台原grant独立；旧后台grant过期/blocked仍可由当下获准、覆盖全部历史材料范围的用户读status，但观察不得清blocked、换grant或调用后台resume。

metadata回传的parent app root保留为真实PG事实，用于engine本域双重绑定和authority既有政策解释；不能假称它也是run-snapshot签名字段。实现时由固定authority adapter/隔离fixture明确支持run-snapshot资源及该status action，允许按run拒绝。新资源不会自动获得app级读取许可；若实际部署的authority需根据parent app策略决策，必须由其已签收的可信资源映射/固定resolver获取关联，不能信任caller自报parent。当前工程只是隔离软件profile，生产仍闭锁；没有真实IAM支持时默认拒绝，不增加宽泛fallback。**独立exchange已足够，不要求新增derive接口**；只有未来决定从原执行grant导出子票才涉及derive。现 `forward:211–215` operation全等、`AuthorityContext.refresh:58–61`同resource约束均保持，不把它们改成换根捷径。

新增本域私有 `RunStatusReadProof`（建议名），构造器不向业务调用者开放。仅实际status handler受限service在live retained frame、精确template/action/source、真实metadata全部核实后铸造；绑定frame身份、numeric/public run id、owner、签名run-snapshot观察资源、持久app root、acceptedSnapshotSha、initialCeilingSha及当前retained proof。当前retained的签名initial证明用于确认新M来源，不要求它与旧运行initial ceiling相等；历史scope逐份按fresh current覆盖，同时仍以PG原initial floor/parent关系核材料来源。不能经过写用`RunAuthorityStore.strict`，不能提供任意public Runnable/boolean作为已核权证据。

receiver在事务外先窄读PG owner/root/snapshot，对签名M的run id/digest和实际路径全等；刷新同M frame后进入单只读RR，再比较run owner、public id、app root、snapshot、initialSha；全部scope metadata判权后才读payload，返回前终检。Q之后换snapshot但app root不变，M仍绑定旧snapshot，receiver在payload前拒绝；跨run/tenant/owner、同app同版本另一个run替换path、旧proof跨frame/跨线程复用都拒绝。已读取内容被篡改的digest失配仍是读后拒绝，不伪报payload=0。proof只有读取/返回当前GET的能力，writer/local advance/create仍拒绝。

**不推荐方案 A（仅app-version读票+本地run proof）的真实上限**：本地private proof能防已解析A的能力被拿去读B及单事务metadata竞态；但签名票没有runId，重新进入handler后同owner同版本的另一个run仍可能满足同app许可，authority无法凭该resource按run拒绝。Q/M之间acceptedSnapshot改变而app root相同时，缺少已签snapshot也无法让receiver知道gateway原查询版本；本地新铸proof不能补这一缺口。因此若要求单run/changedsnapshot负例，A不满足。它虽少改资源profile，却给下一SSE的run-snapshot留下不必要的换根前置，故本提案按root补充分析改为推荐上面的独立run-snapshot新exchange。

O2继续用相同run-snapshot观察资源，但换精确events action与新的请求/response绑定；status票不能用于events，也不从status grant偷偷续用。原执行app root与观察run root是两种用途，不把后台operation绑定换成observer资源。
### 3.3 真实材料读取与最小变更面

将 TraceStore 现 statusPublished 中“单 RR 快照、全部 metadata 先验、payload 摘要、原 map 组合”提炼成包内受限核心；保留现 POST 入口并新增独立 status 入口。不可把 `RunAuthorityStore.requirePurpose` 广泛改为 main OR status 后让 writer 也接受 status。`requireReader` 对新 read proof 的分支只用于读；`requireWriter/strict/createPublished/localRunAdvance` 原要求不变。新查询接口不能顺带读业务材料。所有 IAM status 在确认 protected binding 后才进入 protected core；缺 run_authority 直接拒绝，不能走 `published==null` 的 legacy compose。

复用 `SubjectKey/ResourceBinding/ServiceCall/MaterialRef/MaterialScopeV1/MaterialAuthorization`、ServiceAuthorization live retained frame、固定 PublishedRunHttp GET transport。新增 selector/query DTO/client、状态专用私有 gateway proof 与 engine read proof。gateway proof 可仿 GatewayRunAuthorization 共用私有验证实现，但必须固定 STATUS purpose、状态 action、metadata 关联，不能给调用者任意 ServiceCall 的构造权。最终输出前及非阻塞 WriteListener 每次写前检查 source/grant/operation/decision/单调 deadline；5秒内未完成则关闭，不循环交换原 GET 或借 POST 重放续命。先物化有界 JSON，超限拒绝不能截断成貌似成功。

预期文件范围：

- common：`GatewayIngressBoundary.java`、`MetadataSelectors.java`（或独立 RunObservationSelectors）、`CurrentAuthorityClient.java`、`HttpCurrentAuthorityClient.java`、`ServiceAuthorization.java`、`agent-service-authority.json`，以及实际安装/coverage 所需的窄接线；不放开全局 IAM 启动闭锁。
- gateway：`GatewayController.java` 分派 STATUS、新增 `RunObservationMetadataClient.java` 与 `RunStatusProxy.java`（建议名）；复用 `PublishedRunHttp.java` GET，不复制一套无取消的 transport。PublishedRunProxy POST 行为保持。
- engine：新增精确 metadata controller/service（建议 `RunObservationMetadataController/Service`），`RunController.java` 的受限 GET 分支、`RunService.java` 显式独立 status/共用 compose、`TraceStore.java` 受限快照核心、`RunAuthorityStore.java` metadata projection、`MaterialScopeStore.java` 专用 read proof 消费。优先不新增数据库列/迁移；现不可变 metadata 已足够。
- 仅新增/扩展对应定向测试和自己的证据；root 再划分 writer 所有权。需要变更 shared proof/read core 时必须一起做 writer 拒绝回归。

legacy：非 IAM 旧开发测试流程保留；TraceStore.run 的 protectedRun 拒绝仍保留，IAM旧无 metadata run不能降级。admin/debug/v1 catalog 同 action 的别名必须由真实 request-template gate 拒绝首切片未授权 alias；不得因共用 status 方法而一并开启。原创建/重复 POST 返回完整 status 的已有成功与失败断言应回归。

## 4. O2：PORTAL SSE 的实际接线与不可省前置

### 4.1 现循环

`RunController.events:133–141` 先 `store.run(id,actor)`；TraceStore.run:65 对 protected run拒绝，所以目前受保护 published run根本不能进入旧流。legacy writer :174–218直接查 status/watermark 并发控制帧；:244–281 以单条SQL同时选择event和payload并发送；:285–318又读状态；:338–348读ChunkBus裸text。:170–171 55秒窗口，:331–348轮询450ms/20ms不是权限watchdog。订阅已有完成回收钩子，但没有authority open/check。

gateway旧代理 `GatewayController:67–104` 使用 HttpURLConnection、650000ms readTimeout、直接复制byte buffer。没有本地流 holder、buffer放行前权限核验或idle期间主动cancel。新 PublishedRunHttp 是有界完整body GET/POST传输，不能直接拿2秒hop/完整byte[]接口冒充55秒SSE传输。

### 4.2 common已有实现与缺口

`HttpCurrentAuthorityClient:380–432` 已实现在线 response_open/check、签名 continuation、operation/绑定/期限校验；`ResponseLease:16–39,60–65` 的public构造仅data shell，已验open/renew共享一次claim lifecycle；`StreamAuthorityGuard:23–96` 已有1秒watchdog、stop-before-cancel、checkedUntil在线check、INTEGRATION accepted+permit drain及幂等close。它们不是空接口骨架。

但是对engine/gateway/model的生产 `src/main/java` 窄搜索没有 `ResponseAuthority/StreamAuthorityGuard/readForResponse/nextEventRef/acceptForResponse` 命中。现 ResponseAuthorityTest 的闩锁/计数是common组件证据（135–187），不能替代RunController/PG/真实gateway复制循环。

**普通 current 材料能力缺口**：ResponseLease 只带时限/continuation/permit，没有签名check之后的当前scope/roles/owner私有材料证明；check仅返回renewed checkedUntil。MaterialScopeStore真实read要求RetainedAuthorization且requireBound绑定请求线程；旧opening proof还受原service ticket寿命约束。故仅每轮 `guard.beforeWrite(); read(ref,openingAuthority)` 不成立，异步手工bind/public VA也不成立。

O2必须先增加窄、不可伪造的 **response current material proof**：由严格response_open/check解析并验证当前主体、scope、action、run-snapshot、operation、原response绑定和nonce/request digest后铸造，只允许同本地live holder读/写当前响应材料。它表示新鲜当前决定，不是RR1 completion permit；不得变为service ticket、后台新动作或任意assembler权限。签名check profile若不提供完整当前决定/material ceiling则默认关闭，不从opening authority复制其roles/expiry。允许新鲜VerifiedAuthority作为纯集合校验值，但真实reader还必须消费封闭私有证明。每次current更新失效旧capture；网络调用在PG短事务之外。原内部ticket过期后由同response continuation续核，不重新exchange/open，不延长source期限。

**双hop绑定缺口**：每服务本地open、holder不可跨HTTP序列化。gateway当前只有engine-target的GatewayRunAuthorization票，不是gateway本地接收动作的VerifiedAuthority；必须定义gateway观察响应的固定本地动作/authority开流入口，并让authority签名关联engine/gateway响应、parentResponseId、source/资源/requestSha与不递增hardDeadline。当前open要求binding.resource等于current.call.resource；不能把app-version current配run-snapshot binding而声称已支持。也不能由gateway自行拼parentResponseId就算跨hop证明。这个前置属于O2，不能由O1 GET成功外推。

### 4.3 一轮正确的 PORTAL 行为

1. 新请求验PORTAL JWT/精确events action；真实metadata解析 owner/run snapshot/parent root；规范化Last-Event-ID。第一版只支持header，query alias明确拒绝；若要保留query必须在brief精确定义冲突规则及绑定摘要，不能照抄旧query优先。游标与请求method/template/runId一起绑定requestSha。
2. engine在订阅及提交writer前，gateway在向浏览器提交响应前，各自建立有效open。hardDeadline以原受理时刻固定，≤55秒且≤PORTAL来源期限，不在异步writer启动时重新给55秒。保留现chunk admission/backpressure/response byte限制。
3. `nextEventRef(runId,lastSeq,acceptedSnapshotSha)` 只取event row id、seq/type、owner/run/snapshot、真实material revision及manifest；不用原含payload的SELECT。正常路径先在事务外获得fresh response current proof，再在同一RR快照中重核这些metadata/当前scope，读取对应payload、验真实digest，write前再检查holder/current/stop。过期或收窄在读前发生则第二payload=0；读后发生则payload>0但第二wire=0。
4. run status/watermark/error同样是受保护信息，不可把旧直接SQL结果夹带进授权reset。PORTAL首切片可只释放pure FLOW持久事件；若ChunkBus临时chunk没有可验证继承scope/producer绑定，不把裸frame.text加入IAM流。将来接模型临时chunk必须继承其已验请求材料scope，不能只按rid假定安全。
5. 未committed：401/403/503按原错误语义返回；committed：最多一个不含业务payload的stream_reset，reason=`AUTHORITY_EXPIRED|AUTHORITY_DENIED|AUTHORITY_UNAVAILABLE`，随后close。控制写需受限专用路径；不能把已经停止的guard重新启用来发reset，也不能与业务writer并发乱写。无法写reset时直接关流，不补final/待缓冲event/status。
6. 每个payload读取、enqueue、转发buffer写之前及真正write前复查；watchdog≤1秒调度并能cancel实际阻塞HTTP read/PG statement或中断相应有界等待。先stop，后关闭subscription/reservation/stream/connection/timer，late callback不重新安装handle。close不等待续核锁、不持PG锁联网。不要在同锁下做长阻塞write使watchdog失去停止机会。
7. cursor推进只在获准事件实际发送后；cursor reset、retention race保留原事实语义。断观察cancel-run计数=0。新Last-Event-ID重连重新认证/Q-M/open/current/material检查；新宿主token恢复不自动重放POST。前端D4b独立作者负责其消费，此报告未读取前端。

## 5. O3 RR1 与 O4 RR2：禁止混用

### 5.1 RR1真实材料与现类型不匹配，单独修正

计划§2.4.1及v1.2 snapshot:2887只允许**已合法建立/实际已在途的INTEGRATION原响应**完成；不是普通PORTAL/DELEGATED续命。common permit 已有签名/独立用途/holder lifecycle，但直接接当前PG材料必失败：

- `ResponseMaterialPermit:59–64` 期望固定material_kind/id和`material_version前缀:seq`；`StreamAuthorityGuard:81` 从ref.version最后冒号后取cursor。
- 真实 `MaterialScopeStore:320,453–464` 的version是独立全局material_revision数字字符串；run-event的kind=`run-event`、id是engine.run_event真实行id（:384–407），每事件不同，不是runId或`event`泛化材料。
- permit:55–56要求当前opening ceilingSHA/authorizationVersion等于每份材料的initialCeilingSHA/sourceAuthorizationVersion；真实派生材料:470–477保存原运行initialSha以及实际worker当前decision/version。不同合法worker决定、历史事件与开流current版本可以不同。不能为满足permit重写历史材料、改provenance或把全部版本冒充opening版本。opening ceiling与历史required scope的覆盖也不能仅以hash相等代替。

RR1建议保留真实MaterialRef/schema/revision/payloadSha。新增仅可信域内nextEventRef产生的事件描述，把runPublicId/owner/acceptedSnapshotSha/eventRowId/seq与真实manifest全等关联；不可由caller任意record构造取得能力。permit签的是原run snapshot和允许材料kind/cursor选择器，domain reader验证真实row归属及seq单调；holder使用该已验seq推进，不从material revision解码seq。验真实sourceDecisionId/sourceAuthorizationVersion存在且与PG scope原文一致、scope/parent digest正确、owner/root/required集合/labels满足permit，而不是强制历史provenance等于opening决定。模型final另有invocation/attempt/round/outputAttempt选择器，不泛化成任意材料读。

这些修正依赖实际INTEGRATION run/material生产与策略链；当前RunAuthorityStore.load:91硬要求source_kind=PORTAL，O1有限FLOW的同owner测试数据不能冒充完整INTEGRATION运行。需补真实集成受理/必要元数据闭环后，再验PG下一事件readForResponse。PORTAL O2不用该permit，因此可先完成；若共用接口改动影响其安全，先做窄公共类型前置，保留两套分离的证明能力。

模型侧RR1必须到实际ModelCalls持久scope→readForResponse→同连接私有material_manifest+final→RuntimeClients.acceptForResponse→ModelExchange记账；不能在drain中发新manifest HTTP，也不能因返回toolCalls再执行工具。私有sidecar剥离、帧顺序/缺帧/重放/上限见计划§2.4.1，不在本次O1/O2顺带宣称完成。

### 5.2 RR2后台新动作

DELEGATED来源只由authority对有效持久grant的新动作resume签出。新MAIN/SUMMARY/RETRY/技能分别取新operation；同动作hop forward保留operationId/截止，不能替旧PORTAL observer延寿。真实验收目标仍是`driveRecovered→RunAuthority.current→ModelExchange→RuntimeClients.chatStream→ModelController/ModelCalls`。原portal在t+10秒到期观察关闭，后台grant t+300秒仍可在t+11签受120秒policy限制的新operation（截止≤t+131）；撤grant/503/operation到期硬停止，没有drain。现pure FLOW local advance不是上述模型链证据。

## 6. 分切片真实验收与counter

所有以下为**拟实施测试**，本次未运行；测试使用独立issuer/authority/固定loopback端点与真实专用PG，各业务服务独立classpath/HTTP。先红后绿，不以fake controller或本地Map替代真实业务路径。主服务不占用、不重启；实际命令由root正式brief固定。

| 切片 | 正例及必要负例 | 必须记录的实际计数 |
| --- | --- | --- |
| O1 metadata | 已接受FLOW→真实gateway/Q→engine metadata，无业务payload；错issuer/aud/caller/action/subject/run/selector digest；query票撞status/events/POST；未知/缺authority；非admin alias拒绝 | Q exchange、metadata HTTP/handler、metadata SQL、业务payload SELECT=0、M exchange是否发生；不存在/跨owner不得泄露元数据 |
| O1 status | 真实PG START/END完整status包含真实nodes/events/output；旧发布指针切版仍读被钉版本；旧后台grant失效但新用户同scope可读且不重绑；scope A/B→A、错root/owner、缺nullable scope、版本/parent digest错、真实payload篡改；私有proof换run/重放/Actor/public VA/main/local/query滥用 | M exchange、receiver authorize/refresh、RR连接/隔离/只读快照标识、metadata/payload分开、最终wire bytes、run/attempt/event新增=0、provider/skill=0；内容损坏及读后到期必须记录payload>0/wire=0 |
| O1网关 | source在Q/M之间过期、M 503、返回前到期、慢响应/慢客户端/close race；engine直连合法正例与未支持alias负例；同app同owner另一个run替换path也须因run-snapshot签名绑定拒绝，不能把Actor owner测试写成该证明 | installed/cancelled HTTP handle、worker start/exit、scheduled task关闭、status提交前错误、部分已写字节不能追溯记0、POST重放=0 |
| O2 PORTAL | 首事件实际wire成功，第二eventRef前闩锁→跨decision/源到期/撤scope/503→第二payload/输出0；读后write前失效→payload>0但第二wire0；idle真实阻塞read能取消；同scope新token/cursor恢复 | 每hop open/check、nextEventRef、current material proof读取、PG payload SELECT、event/chunk/final wire、buffer后停写、subscription/reservation/connection/timer回收、cancel-run=0、重连次数 |
| O3 RR1 | 真实集成原流在第二PG事件未读时失效，readForResponse真读并完成；超ceiling/错run/snapshot/version/manifest/provenance绑定/digest/deadline拒绝；PORTAL对照无permit；模型final真实sidecar链 | readForResponse/acceptForResponse、payload读取前后分界、manifest接收、实际wire；drain期间新manifest HTTP=0，新模型/技能/SUMMARY/新SSE=0；关闭后permit重放0 |
| O4 RR2 | portal已过期后真实后台model首发1次、每hop同operation/截止；撤grant/伪来源/改期限/材料收窄provider=0；后台成功不复活observer | resume/authorize/forward实际HTTP，operationId/截止，各真实provider/skill发送，持久JWT=0，原observer后续wire=0；不以新状态覆写原事实 |

现有可复用测试位置：`FlowMaterialStatusPostgresTest:46–118`真实单只读RR和14次payload示例；:123–172当前/owner/root无payload；:177–213新main可读旧blocked历史但不能写；:350–395区分内容损坏/读后到期。数字14只是该有限fixture现断言，不是新GET固定计数。`ResponseAuthorityTest:135–187`是组件闩锁/guard证据，应保留回归但不得计为O2/O3实际wire证据。`GatewayPublishedChainPostgresTest:351–529`是已有创建链测试位置，复用其独立classpath/HTTP+PG搭建，不复制整套10GB证据或把POST成功当GET成功。

建议新增 `RunObservationMetadataHttpPostgresTest`、`RunStatusHttpPostgresTest`、`GatewayRunStatusChainPostgresTest`（建议名），O2/O3沿计划 `StreamAuthorityHttpTest`，后续模型沿 `ModelStreamAuthorityHttpTest/IamRecoveryHttpTest`。选择覆盖受影响proof/route/material reader/writer的定向测试后，再由root做一次必要稳定基线；不预报新总数、不重复已冻结target。

## 7. root正式 brief 的有限决定

1. 采纳独立run-snapshot status新exchange：原执行app-version root/grant不变，query与status各自新grant；固定新观察资源profile及receiver双绑定，不把same-app票误作单run票。生产authority关系解析仍需签收，隔离fixture仅验证软件。
2. 固定O1唯一外部路径与metadata DTO/action/selector；为GET加独立Purpose/proof，明确遗留alias仍拒绝且无IAM legacy fallback；材料writer拒绝status proof。
3. O2先做run-snapshot跨hop响应绑定及fresh response-current material proof；RR1 permit/selector/provenance修正单列。不得把public VA、过期openingAuthority或Java holder反序列化当新鲜证据。
4. O1/O2仅有限pure FLOW；全量Task4/5/6、真实IAM签收、INTEGRATION/RR1/RR2模型链及生产启动门禁仍未完成。没有真实外部环境时继续用隔离端点，软件切片不等于生产批准。

## 附录：实际读取文件、范围和 SHA256

下表 SHA 针对文件完整当前字节；“读取范围”仅表示本次实际内容/rg定位范围，不表示全文件审计。只对该固定版本负责。脚本对下列明确文件取 hash，未递归扫描源码/target。
| 工程相对路径 | 实际读取范围 | SHA256 |
| --- | --- | --- |
| `AGENTS.md` | 全文 | `aae39dd313e9d3b1298a2620df9156d369f7acfaa45600832af8cbf4b1dbc012` |
| `docs/implementation-brief.md` | 全文 | `7ec443a2f0382a71b64c6c31af6c1abd40b14bba9579e8a4b7f46384f6656cd1` |
| `contracts/README.md` | 全文 | `47d01a5327aa5376a4fdce769f6a140b587ac8bdf39120c1f9fdcb3b618a6b41` |
| `contracts/freeze.json` | 全文 | `4608f87d914042c6a8ef24693e066eef6c4651ad49c4129de88b196e0a85354d` |
| `contracts/snapshot/detailed-design-v1.2.md` | 2880–2893 | `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f` |
| `contracts/trace-model.md` | rg:29,85,440,501,526 | `7d0fe91476d6336877b00ece046d5e2daf1a239bf316a7d0b2f6c4507eaacb5f` |
| `contracts/authorization-confirmation.md` | rg:126,186,244,277 | `7283fb06f4f790b030f154d3546931e71907f48e498f2b869f622d28e392c995` |
| `docs/evidence/iam-next-run-observation-architecture-brief.md` | 全文 | `868754dc6510fac3781bab46da077e6bac2429ff37bd2ebb18ebe8cfcae1fb68` |
| `docs/evidence/iam-service-authority-implementation-plan.md` | 159–242,440–581；其余heading/引用rg定位 | `8b2fb5e7e08907625827d06a592688a868b3e23e0ccd1907d424e96840832d14` |
| `docs/evidence/iam-next-published-chain-seam-proposal.md` | 75–91,154–173；其余heading/引用rg定位 | `5a7bef43ea57290367895cef38a029437f66733e0c6a3a636e34d1fc9aa9d308` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/GatewayIngressBoundary.java` | 26–110,180–230；purpose/registration rg定位 | `2525da73a86ce83f538c03745cec69e8e835965bfa41851eab4bccc3610c2442` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/MetadataSelectors.java` | 全文 | `c7c29222c7a3f606b16af78e7d57e8550b598df3aeeeec0f5bd79a66d99839a0` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceRoutePolicy.java` | 全文 | `4defe1972ca35db4b9d5c85fe43b1073528fcdd6bf910891f39a1b13f5aee685` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java` | 45–109 | `5dacc7a313010e1d1a77254d259859bee78211c996797e3dd813b6fe60270a56` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java` | 31–70；其余签名rg定位 | `d3a038ba6ef7e2263a685b644753db3abe425ccd42a81e707b9d9cd04df6037c` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java` | 90–228,255–325,380–432 | `7f29e6397283d72c85562a616b8488578475f5cf1071e4bc70471c131b1da412` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ResponseAuthorityClient.java` | 接口签名rg定位 | `a52d6e4f1eda133ba5a76f718be1f311350cea144b6163efc65965f82dfeb1ef` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ResponseLease.java` | 全文 | `aab61512525ef7841a97d32f61d4008f9105202e2bfce93cfda07f68d8c967b5` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/StreamAuthorityGuard.java` | 全文 | `e4add24b40f821f2edc3122e2b915155110d125ec1ae567c0dfa009ea791390e` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ResponseMaterialPermit.java` | 19–68；fields rg定位 | `b1a40249b2e7e5a2bec356861e4dd77e97f29c2d28ecc7c007a725c6fa8d7022` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/MaterialAuthorization.java` | 全文 | `0834dc440e06341322cb0e4aeadb17227d3d245735821ca106b48fbfdb54b72b` |
| `admin-common/src/main/java/cn/agentplatform/common/authority/RouteCoverageVerifier.java` | 29–80 | `c46b9045ebb93475ca9de3956832555c46aa38afd4bb21ef8521944f2ba538b9` |
| `admin-common/src/main/resources/agent-service-authority.json` | 794–829；POST/events/metadata相关行rg定位 | `058d9e45eb0862d8837a38711a3a0cc8d482dec21e0b702075c5ac224f8f2f7f` |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java` | 1–151 | `2aaf73d8ff93094dec7712f2c5672bf8b5b3e9ea858265d5371cf063828e239f` |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java` | 65–283,430–461；未把截断输出视为全读 | `0621e61eedef1a9a6db6faac42597e4d0386772a60c426a4fd25231709e4548e` |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedMetadataClient.java` | 全文 | `203d6be3c08e82396c94af0257c4eb612cd2900e608ce3bd5f5a783de4f35304` |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PublishedRunHttp.java` | 160–208；接口/cancellation签名rg定位 | `443efcd7179ddaa366e4d933d9b4921d0b334a69b6f8a0c18ef86a60ae5ff5a9` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 124–365；status mapping 70–72 rg定位 | `2d3d0a8480f44f50adb2334281d5557b235794310157bd252cd91d9c751ca91a` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 1627–1638,1660–1692 | `d39c8ec1342e276c9150697e0d6af72deeae6ccbdb91aac9fe7cc63a35636237` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java` | 64–76,277–444；其余声明rg定位 | `5e2666a0fac8c31132fe65fa6f9da6f53c2da18b914810c08944df3e00a0b3c1` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 76–100,121–136；publicId生成53 rg定位 | `5f9cb84785a44e5130fde5cbbdc5c8125e74846fd35641cb809ec0ed6822f3a8` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | 41–85,196–228,288–354,384–522,580–602；其余声明rg定位 | `de40af7225d88d215483763e747bd473466b75be437c38ebd318e9b44cceefa6` |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunAuthority.java` | current/currentRetained/proof/recordCheck相关rg定位 | `6a879ca45785f65c04aae72beac6753038a1d62e82555c6e034672d108016dee` |
| `admin-common/src/test/java/cn/agentplatform/common/authority/ResponseAuthorityTest.java` | 135–187；其余测试名rg定位 | `3ad1c50c6e7ea1c04ced6395bb146246a9083f316e5f2faf2c0b206af78eee87` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FlowMaterialStatusPostgresTest.java` | 123–215；46–118,350–400 counter/assertion rg定位 | `b7c22d33af8ee497b773d86114b9cbaf50d0f6bc42b95e2f689ecf68199a1e05` |
| `platform-gateway/src/test/java/cn/agentplatform/common/authority/GatewayPublishedChainPostgresTest.java` | 351–529测试名及fixture生命周期rg定位 | `632b11e58628024a3e154a306f036735f93d1872711f984318fbc4b9396d6c00` |

SDS角色来源：`D:/00-Work/02-Application/01-CodeIDE/UserData/Codex/DotCodex/skills/subagent-driven-development/SKILL.md`，本次全文读取；SHA256=`41ab239a6ad1c487cd839fdac972a8c9cf0f5e90efa59a63f963767864f0df4c`。
初始按名称检查 using-superpowers 后依据其 SUBAGENT-STOP 跳过该工作流；本次仅采用任务指定 SDS 架构约束。

本报告交还 root 后冻结所有权；后续修改需 root 明确重新分派。