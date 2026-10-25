# O1b engine 独立只读 status：候选实施要求

状态：候选、未实现、未测试，供 root 在 O1a Accepted 后最终化正式 brief。不得把本文建议的 API 当作 O1a 已交付接口。本次只写此单文件，不改旧提案、正式 brief、八索引、产品源码、target 或旧证据。

## 1. 范围、基线与依赖

唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，每条 shell 显式以 `G:/207/项目/算法重组工具/代码/agent-platform` 为 workdir，执行工具同 root 正式 brief 指定工程 cwd。禁止相邻旧工程/依赖/进程访问。原 v1.2、四契约、IAM计划和裁决、Java21/Spring Boot3.5.0等版本、跨业务HTTP和真实PG约束保持。没有迁移、POM、依赖或生产部署改动。

O1a metadata → **O1b engine 受限独立 GET status** → O1c gateway联合GET → O2 PORTAL SSE。O1b仅原实际 `RunController` 上 `GET /admin/runs/{id}`，仅有完整 run_authority/material_scope 的既有 PORTAL published pure FLOW；保留完整原 status JSON。debug/v1 alias、列表、variables、events、CHAT、技能/模型/确认/附件/回放、后台新动作、RR1/RR2均不开放。不能通过现通用gateway代理或生产全局IAM闭锁变更来使本测试可用。

root提供冻结基线为D4a/I1后backend380/target1933；本次没有重扫或重跑。原只读提案 `docs/evidence/iam-next-run-observation-seam-proposal.md` 为38357 bytes，SHA256 `e6dede6002498c1580a607e39fd2c3fa3dd4703359e847892fe5319d3ea6a0e4`。本次仅重读该提案及原read-manifest，并按root指定从N4b2 frozen物理目录读取四份必要源码片段；它们的bytes/SHA与D4a/I1已冻结源相同，精确路径/范围列于附录。没有读取O1a作者当前源码/target/半成品。

root已经允许O1a作者采用的**方案，尚非实现事实**：`RunObservationSelectors.metadata(SubjectKey,String)`及双向purpose；common ServiceAuthorization/catalog/Loopback窄改；新engine controller/service/domain/tests；`RunAuthorityStore.observationMetadata(SubjectKey,String,RetainedAuthorization)` 只读control及三根scopes，顶层readOnly RR/requireBound，精确七字段data JSON UTF-8≤4096。O1a未计划改TraceStore或CurrentAuthorityClient。本文将复用其已验能力，但正式实施前必须核最终diff，见§8。

## 2. 精确用途与信任分离

### 2.1 入站许可

- HTTP仅 `GET /admin/runs/{id}`；真实已安装SecurityFilterChain、ServiceAuthorization MappedInterceptor、实际HandlerMethod和该模板均须经过。拒绝raw query（含bare ?）、body、未支持dispatcher/alias，不接受用户声明tenant/principal/root/actor。受保护publicRunId沿O1a的 `r_[0-9a-f]{32}`。
- caller=`gateway`，target=`engine`，action=`proxy.engine.get.RunController.status`，authority mode仍为既有`delegated`，source_kind/origin_kind均为`PORTAL`。**不增加自创JWT authority_mode=observer**；OBSERVER_STATUS是本地私有目的类别，不冒充新签名信任域。
- resource精确为 `ResourceBinding("run-snapshot",publicRunId,acceptedSnapshotSha256)`。operation.resource与call.resource全等，主体来自已验JWT/当前决定；digest必须是O1a读取的真实acceptedSnapshotSha，不能是candidate/status JSON/selector摘要。
- 既有status三alias共用action，单核action不够。真实IAM status入口必须核实际 `BEST_MATCHING_PATTERN_ATTRIBUTE=/admin/runs/{id}` 和精确方法/dispatcher；不能使同票到`/v1/runs/{id}`或debug/status变为成功。legacy非IAM原开发语义独立保留。

query与main：同originalPortal取得O1a query ticket，真实engine metadata HTTP返回DTO，然后以同source**独立新exchange**得到上述status票。query grant不resume/forward到status；DTO不携带读能力。O1b测试可由测试客户端扮演已配置gateway client完成这两个步骤，但必须标记这不是O1c真实gateway链。M的独立按run拒绝及当前scope收窄由有限authority profile实测；不能借仅catalog接受字符串资源声称真实IAM产品已支持。

### 2.2 执行根与观察根

持久 `run_authority.root`仍是`app-version / appCode@versionNo / resource_digest(candidateSha256)`；原grant、initial ceiling、originAuthenticationId、admission decision/version、blocked/check字段及旧材料不改变。status的新M grant/operation/initial proof可以不同于原运行，新的门户认证也可以不同于原受理认证。必须同tenant/principal、签名来源PORTAL，**不要求原portal token/JTI/originAuthenticationId与新观察者相同**；不能因此禁止用户新token读取旧run。

旧执行grant过期/blocked不禁止一个新的合法观察决定覆盖历史scope；反之新观察成功不得清blocked、延长执行grant或唤醒worker。不能调用`RunAuthority.current/resume`取得status权限，不能把观察proof交给`RunAuthorityStore.strict/requireWriter`。原`strict`要求执行app根/原grant/initial/origin完全匹配，属于写/后台执行边界，不能为了status改成宽松条件。

## 3. 私有只读证明与候选接口

已有 `HttpCurrentAuthorityClient.RetainedAuthorization` 可证明**本次M**签名及live请求frame；它不是原运行执行许可，public `VerifiedAuthority`/`currentOnly`/Actor仍不能充当可信能力。O1b不需预先修改CurrentAuthorityClient/HttpCurrentAuthorityClient；现独立exchange+authorizeRetained可承载新resource，须在O1a最终profile上实测绑定。

建议在engine infra建立封闭的 **StatusReadProof**（候选名，可作为TraceStore内部嵌套final类型）：

- 私有构造；只在实际观察入口通过live frame检查、从真实PG读到绑定后创建。工厂不接受caller自报的公开metadata DTO/Binding record作为已经可信的来源。
- 持有本次RetainedAuthorization/frame身份、签名观察resource，以及本域真正读出的numeric/public run id、owner、原app root、acceptedSnapshotSha、initialSha；只能读该run的当前status必需材料。
- 每次使用前后核frame仍绑定**同一**retained对象、proof未到期/未关闭、当前来源与用途、run/owner/观察根/原执行根均匹配；过期或刷新后旧capture失效。不得跨线程、跨请求、换run、用于写入或导出ServiceTicket。
- 不提供public `(VerifiedAuthority,Binding)`构造、任意Runnable/BooleanSupplier“已核权回调”，不允许调用者任选purpose。需要复用内部逻辑时用封闭明确的POST读取与STATUS读取分支；禁止把现writer签名改为接受泛化读写proof。

候选调用结构（名称待正式brief最终化）：

1. `RunController.status` 真实IAM分支→`RunService.observeStatus(id)`；legacy分支沿原`status(id,Actor)`，创建POST仍沿原调用。
2. `observeStatus`拒绝ambient事务，取得真实frame actor；窄核status purpose/resource后调用 `TraceStore.statusObserved(id,...)`。这条路径不得以`protectedRun=false`回落legacy。
3. `TraceStore.statusObserved`先事务外窄元数据解析/验证签名resource，事务外 `AuthorityContext.requireInitialFor(actor,observerResource)` 刷新同M，并 `requireBound`；再在顶层只读RR中重新装载Binding并铸造只读proof。
4. `MaterialScopeStore`新增**仅package内部**的明确status读入口，如 `readStatusValue(Binding,MaterialScopeV1,StatusReadProof)`；与原readCurrentValue共用固定payload解码/摘要原语。这个新入口核观察resource与绑定，而不是调用原仅main/local的requireReader。原 `read(ref,VerifiedAuthority)` 继续恒拒绝；read/write/public API不扩大。
5. status内部结果保存原run/nodes/events及已验snapshot供application复用现`requirePublishedFlow`纯校验；既有`composeStatus`复用。最终对外只返回原status字段，不输出snapshot/authority/scope/proof。

可以保留原 `TraceStore.PublishedView` 作为纯内部结果载体及其 `requireCurrent`，或另加封闭ObservedView后共用组装函数；不能把该public record本身当成reader授权能力。原POST proof与新观察proof应在类型/固定分支上可见区分。实现前作者须列出最终类/方法签名、constructor可见性、mint点及消费点，root按已授权范围确认；不得用更换字符串action包装原POST授权来代替新增目的。

## 4. 两阶段真实读取与 snapshot 关系

### 4.1 事务外当前授权与窄metadata

在任何业务payload SELECT之前，真实receiver已完成签名/route/目的校验。通过publicId和当前owner查询窄run control，复用O1a**已接受的**控制/三根manifest校验；404语义延续O1a：不存在/不归属统一404，归属但缺authority/scopes为403。旧记录不自动回填。

校验签名resource.id等于真实publicId、resource.versionDigest等于PG acceptedSnapshotSha；app root和initial ceiling仍用原run_authority事实。O1a DTO可帮助测试client构建M，但engine不能把来路DTO作为capability或跳过再次读取。Q→M之间改变acceptedSnapshotSha时，即使app根相同也须在payload前拒绝。

当前在线刷新必须在DB事务之外。拒绝进入任何ambient事务，包括已有readOnly RR，避免网络在锁/快照内、避免helper静默加入READ_COMMITTED/可写RR。刷新只核原接收M同call/operation/grant，不重新exchange或获取更宽授权；已到期票/旧frame不能自行续命。

### 4.2 单一顶层readOnly RR：先核全部metadata

在同一DataSource连接、顶层只读REPEATABLE_READ中重核numeric/public id、owner、run/appVersionId、原app root、appVersionNo、acceptedSnapshotSha、initialSha，必须与签名M及事务外解析一致。O1a如仅公开DTO投影，不把其独立事务作为本次payload快照；需要在本次RR中复用包内原始装载/验证，而不是嵌套调用另一个顶层metadata服务。

完整根manifest校验沿`load(...,true)`/`MaterialScopeStore.scopes`：三份固定root种类、owner、version=acceptedSnapshotSha、原admission decision/version、initial ceiling hash/roles/entitlements、labels义务与父链/摘要。根snapshot的payloadSha还必须等于acceptedSnapshotSha。注意这些原provenance不等于新观察M的decision/version；不得改为两者全等。

然后沿现statusPublished枚举当前material scope、node id、event id：

- 三根KINDS + RUN_KINDS（state/budget/output/error） + 每个node的definition/state/output/error + 每个event的run-event；原status不暴露attempt明细，沿现逻辑不把attempt材料加入payload集合。
- `requireComplete`的exact集合、每locator当前最高revision、根/父scope floor、原owner、actual row↔manifest冗余对应全部保持。nullable output/error缺scope仍拒绝，不解释为空。
- 每份**需要读取的**材料均按新M fresh current检查roles/entitlements覆盖；当前属性不能替换历史initial floor。dataLabels仍是义务，不要求用户属性具备同名许可或删除历史label。
- 先用窄control存在性拒绝tool_call/controlled skill、非支持trigger/mode/debug/eval范围，不能先读tool result或snapshot再判断当前有没有读取它的权限。实际pure FLOW内容校验留已授权snapshot读取之后。
- 预检完全部必要scope才允许第一份payload SELECT。不以先读snapshot获得appCode/owner/权限，亦不先读后筛选。

### 4.3 先读真实snapshot，复核原执行根关系

建议在全部scope预检完成后，**先**通过新只读proof读取run-snapshot及run-version-refs；沿冻结MaterialScopeStore固定payload查询和EXACT/normalize/digest实现，保留PG JSONB数字精度与零/小数规范化。不能用Json.write/普通double重新定义摘要，不能只比较两个metadata hash而不读真实snapshot。

读取后、任何响应释放前，逐项核对冻结 `RunAuthorityStore.createBound:40–46` 原已建立关系：

| 已验payload字段 | 必须与哪份真实metadata/字段一致 |
| --- | --- |
| snapshot.appCode | 有效原app code；与run.app_code以及原root.id的code一致 |
| snapshot.versionNo | 正long；与run_authority.app_version_no一致 |
| snapshot.appVersionId，缺失时versionId | 正long；与run.app_version_id及authority.app_version_id一致；若两个字段都存在须一致 |
| snapshot.candidateSha256 | 64位小写hex；等于原持久app root.versionDigest/resource_digest |
| snapshot.appVersionKey（若存在） | 等于appCode+"@"+versionNo；不把原可选字段强改成必需 |
| versionRefs.appVer | 正long，等于versionNo |
| 实际snapshot摘要 | 同时等于已验snapshot MaterialRef.payloadSha、PG acceptedSnapshotSha及签名观察resource.versionDigest |

snapshotSha是**完整已接受snapshot**摘要；candidateSha是snapshot内的原应用candidate标识，两者通常不同，不要求彼此相等。snapshot↔candidate字段绑定能发现现存冗余关系不一致，不代表重新跑app发布候选算法或跨app数据库在线校验；不为status查询当前发布指针。

将已经授权且摘要正确的snapshot交给RunService现 `requirePublishedFlow:175–216` 或等价共享纯校验，保持FLOW、有限START/END/CODE/CONDITION、无外部依赖/controlled来源；不调用prepareSnapshot/RuntimeClients解析依赖或创建执行节点。只在O1b观察入口增加这项检查，原已冻结POST行为不顺带重定义。合法测试fixture必须使用真实可接受FLOW snapshot，不用只有config的最小存储fixture冒充完整FLOW入口。

snapshot/refs关系通过后，再按同快照读取剩余已预检材料、核payload digest，并组装原run/nodes/events。写者若同时提交新的dynamic revision，整个status只能来自一个RR快照；不能每份material打开一个独立事务，也不能拿scope旧revision去读另一次事务的新内容。

### 4.4 能证明与不能证明

- metadata错owner/root结构、缺scope、scope摘要/冗余version/floor异常、签名snapshot不符：应在业务payload前拒绝，payload=0。
- 实际snapshot内容被单独改坏：读snapshot后摘要拒绝；合法64hex resource_digest被单独替换而snapshot未变：读snapshot后candidate关系拒绝。两者都必须如实记录payload>0、业务wire=0，不能归类metadata-only成功检测。
- 原run/grant/authority/material immutable触发器与可信PG是前提。若恶意DB管理员协调改写snapshot、全部manifest/acceptedSHA/相关authority数据，并取得匹配的新签名观察许可，不能宣称本读取器具有独立于可信存储的历史真实性证明。O1b不新存伪receipt、补历史签名或宽松重算scope来满足测试。
- 当前决定有效短窗内发生尚未查询的远端撤权，不会被本地检查自动知道。线上最后查询成功只证明核实时刻；不能宣传即时撤权。失败矩阵中的撤权/503要定位到下一次实际在线refresh，Clock到期/frame关闭定位到本地终检。

## 5. 原响应、生命周期与首次write边界

原完整结果保留：`runId/appCode/status/eventsWatermark/inputs/output/fileRefs/error/versionRefs/nodes/events/paused`，nodeView原字段与别名、events的`seq/type/payload/created_at`，原时间呈现、大精度数值、SQL NULL与JSON null差异。pure FLOW fileRefs为空以真实未存在tool结果的metadata检查为前提；不能统一填空nodes/events/output以通过测试。

原 `RunService.status:1627–1629` 内部POST路径及 `TraceStore.statusPublished:328–442` 保留独立purpose；新`observeStatus`明确调用新reader，再复用`composeStatus:1632–1689`。其末尾`published.requireCurrent()`在返回Map之前，**不是Servlet首次write检查**。不得把这个已有终检单独称作整个HTTP发送授权完成。

建议有限O1b采用**同步、同request frame的受限JSON输出分支**：API层先拿到已验观察结果及封闭终检能力，完成原ApiResponse JSON序列化后，紧挨首次向Servlet输出业务字节之前做本地`requireBound/requireVerified`及精确用途终检，再写出。只暴露`requireCurrent`不暴露raw proof给业务调用者。不得在serialize后返回裸Map/byte[]给不可观察的后续converter，却声称实际首次write已受控。

候选最窄实现是给原RunController.status增加Servlet request/response参数：legacy仍返回原ApiResponse对象；IAM固定路径同步序列化/终检/写出并完成响应，返回null，保证没有第二次converter输出。方法签名及Method反射/覆盖测试须同步精确更新；若作者选择更小的目标专用converter/output adapter，必须证明限定同实际handler/request、序列化完成后检查、无global advice扩域，root再定最终写法。不要为O1b引入SSE异步writer、response lease/watchdog或通用代理框架。

所有payload读完后、RR真实commit完成后、composition完成后、序列化完成且首次write前均有明确本地终检；终检不得在PG事务内联网。任一到期/失效，尚未committed时返回现401/403/503错误，不返回部分status或吞掉错误变空成功。若IO已部分发出，停止/关闭并如实记实际字节，不能换写JSON成功、重发GET或宣称已追回字节。

本切片保证这些真实检查点；一次socket write内部及其阻塞期间不是连续在线授权，不做生产撤权/慢客户端取消SLA承诺。O1c负责gateway最后一跳复制/有界输出，O2负责长流生命周期。现有响应上限若应用于该路径则复用并测边界，超限拒绝不截断；本文不把O1a metadata的4096字节上限错误套到完整status。

## 6. 最窄候选文件与实施前声明

| 层/文件 | 候选责任及不应扩大部分 |
| --- | --- |
| common authority新窄purpose helper，或O1a helper的独立status分支 | 固定STATUS action+run-snapshot+gateway/engine+delegated+PORTAL；query和status双向隔离。不要让整个run-snapshot kind在所有future action可用，也不要破坏暂未实现的events默认拒绝。命名待O1a后核。 |
| `ServiceAuthorization.java` | 调用明确purpose验证，保留真实frame与模板校验；catalog status行已存在，是否需更新source定位/alias接收策略待O1a最后catalog核定，不新增ANY。 |
| `RunController.java` | 仅真实admin GET的IAM分支和同步首次write；legacy及其它alias的行为明确；不新建第二个同path controller。 |
| `RunService.java` | 新observer入口、复用原composeStatus/requirePublishedFlow；不更改调度/执行/POST重放语义。必要内部结果保持已验snapshot与终检能力，不对外暴露。 |
| `TraceStore.java` | 新观察读proof/mint点、独立readOnlyRR入口、窄复用status物化核心；原POST purpose保持。避免复制整段约110行status读取算法；也不通过泛化任意授权回调抽象绕过证明。 |
| `MaterialScopeStore.java` | 封闭的status proof读入口，共用现真实payload/EXACT/digest/manifest原语；原public VA拒绝、writer严格性不改。 |
| `RunAuthorityStore.java` | 优先复用O1a验过的包内控制/根manifest事实；若O1a最终API不能在已有RR里复用，增最小包内read-only projection/验证原语，不能从application调用顶层metadata API再假称同快照。原strict/写权限不放宽。 |
| 新domain只读结果/验证类型（仅确有必要） | 无public能力工厂；不新造外部status协议，不为候选关系加新持久列/receipt。 |
| common `LoopbackAuthority.java` 与具名测试 | 独立query/status operation、真实run resource/按run拒绝、当前scope与失败注入；不是泛化任意resource allow。仅测试profile，不生产authority服务。 |

无需gateway源码、CurrentAuthorityClient/HttpCurrentAuthorityClient、数据库迁移、依赖、POM、前端、SSE/模型/技能恢复改动。若实际O1a接口导致上述外的必要改动，先向root报具体调用点与原因，不借“下一阶段要用”扩scope。

实施前交root精确文件/API、proof可见性/mint/consume点、metadata及payload SQL列、snapshot字段校验、首次write实现和具名测试selectors。O1a必须Accepted且source所有权移交后再编辑；root定最终新brief，本文不能直接当O1a完成批准。

## 7. 定向真实HTTP/PG红绿验收

本节全部是未来验收要求，**本次未运行**。使用真实隔离authority/JWKS、engine实际Spring HTTP安全/handler、专用真实PG及V200–217已接受迁移。root确认独占database/schema/端点和最终artifact后执行；不碰主服务，不引入兄弟业务JAR，不复扫旧全量证据。使用root既有定向runner及精确selector，不修改runner、不以编译/装配失败充行为红。

建议测试名 `RunObservationStatusHttpPostgresTest`（最终名由root定）；common仅新增/扩展实际受影响purpose/profile测试，保留直接相关原POST material status和writer失败用例。正例需要实际生产受理路径/已验domain fixture写完整FLOW根、节点、事件与scope，再经O1a真实HTTP取得metadata、fresh M exchange、实际status HTTP。worker可作为fixture建立真实有限事实，但请求status期间不得启动/唤醒新业务。

| 组 | 真实要求 | 计数/错误位置 |
| --- | --- | --- |
| 合法独立读 | 新M同owner/run/full scope，原输出/节点/事件及nullable/数字/时间语义保留；原后台grant过期/blocked但新读合法，存储原grant/initial/origin/blocked不变 | Q与M exchange分开、M receiver authorize/refresh、真实handler、control/manifest SQL、各payload实际SELECT、单RR连接/隔离/readOnly、真实HTTP JSON与wire bytes；业务UPDATE/INSERT、resume、worker/provider/skill=0 |
| 目的及入口 | query/main POST/local advance票撞GET；status票撞POST/events/debug/v1；错caller/target/source/origin；伪Actor/publicVA/currentOnly/DTO不能mint；重复/unsupported dispatcher、rawquery/bare?/body | 按真实安全链/handler到达位置计数；不接受只测helper最终403；合法新status proof传writer仍拒绝 |
| 精确run绑定 | 同app/同版本/同owner两个run，A的签名M访问B；跨tenant/owner；Q后snapshot metadata不匹配M | 业务payload=0，真实受保护payload之前拒绝；不存在/不归属404沿已验策略，其余绑定错403，不能用存在性差异泄露 |
| 全scope先验 | A/B历史当前仅A；缺nullable/event/node scope；错误owner/parent/scope digest/revision/initial floor；unsupported tool存在 | 第一payload前拒绝，payload=0；保留原PG事实；不能先读snapshot再发现后续事件未授权 |
| 内容及关系 | 真实payload损坏；合法64hex resource_digest与已验snapshot.candidate不一致；snapshot/app版本/refs.appVer冗余关系错；snapshot内容虽能parse但不属已支持FLOW | 明确读取过哪份payload，payload>0/wire业务0；不回写scope/history修复；PG故障注入需使用隔离库明确操作，正常immutable触发器负例另测 |
| 一致快照 | 第一payload后另一连接提交合法writer新event/output/revision，再完成GET | 响应来自一个RR快照，不能拼新旧watermark/events/output；原writer事务断言保持，测试不是mock SQL返回 |
| 当前授权 | refresh前撤权/503；metadata后、payload后、实际RR commit后、composition后、JSON序列化后首次write前推进Clock至失效；frame关闭/替换后旧proof重用 | 区分metadata/payload读取数；首次write前失效业务wire=0；读后不得假报payload=0；未再查询远端变化不伪称即时可见；不得缓存allow/重exchange |
| legacy与回归 | 无IAM bean的有限构造/装配，非IAM原status行为、原创建/重复POST完整status；受保护run不能走legacy | 明确有限实例与完整boot证据区别，不通过放开生产IAM闭锁测试；status read proof不能执行写操作 |

SQL监听必须覆盖真实`executeQuery`并区分control/manifest/payload。旧RunAuthorityFixture只匹配部分payload查询形式，不能单以其payloadReads=0排除select */direct event payload。正例要观察真实payload>0和真实response字段；wire断言必须消费实际HTTP响应，不能用内存最后状态代替。单fixture已有14次payload读不是本新路径固定目标；额外snapshot关系校验尽量复用已经读过的材料，不为计数重复SELECT。

与源码同步记录实际红色边界、命令/cwd/exit/XML/计数；setup/脚本/装配失败单列；最终只宣称O1b有限engine read路径。结果不代表O1c gateway GET、SSE、完整D/E/F或真实IAM签收。原提交前后错误及IO部分输出分别记录；不包部署。

## 8. O1a交还后必须逐项diff复核

1. `RunObservationSelectors`最终类/API、kind/action双向规则及subject/publicId校验；新增status规则不能放宽query规则。
2. `ServiceAuthorization`最终route/purpose调用位置、frame生命周期、request-template绑定；三status alias仍共享action时，确定O1b实际handler限制位置。
3. catalog新增query行与既有status行、handler反射/coverage实际装配；不从候选文件清单推断已安装成功。
4. `RunAuthorityStore.observationMetadata`是否只提供顶层DTO返回，是否有可在O1b已有RR中复用的包内load/scopes；实际控制列、错误语义和ambient拒绝检查，不以旧load(false)代替三根完整性。
5. metadata service/controller是否真实返回七字段/long/4096-byte bounded DTO及return-before-write限制；O1b不能把其返回对象或读后本地终检升格成材料能力。
6. `LoopbackAuthority`最终selector/profile/原始source支持和计数hook；证明Q/M independent grant/operation、错误run可按签名资源拒绝，不能共享query grant获取status。
7. O1a测试最终数据库/线程/HTTP生命周期及闭合证据；O1b只增相关selectors，不覆盖/重写O1a已封存测试报告。同步确认D4b前端、D4a/I1 backend和O1a新的source/target最终谱系。
8. 四份本次读到的frozen文件与O1a交还最终逻辑源diff；只接受root移交后实际一致的API，不读作者半成品补上下文。

## 9. 本次只读来源与边界

下表是本次实际读取的冻结物理原件；SHA/bytes对完整文件取值，范围仅表示实际阅读片段，不宣称全文件审计。该目录是root明确指定的可信frozen base，与root核过的D4a/I1源相同；不是当前作者工作树。
| 冻结物理文件（绝对路径） | Bytes | SHA-256 | 本次窄读行范围 |
|---|---:|---|---|
| `G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 117574 | `d39c8ec1342e276c9150697e0d6af72deeae6ccbdb91aac9fe7cc63a35636237` | 146–218；1627–1691 |
| `G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | 33041 | `de40af7225d88d215483763e747bd473466b75be437c38ebd318e9b44cceefa6` | 26–38；233–267；523–577；603–636 |
| `G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 16421 | `5f9cb84785a44e5130fde5cbbdc5c8125e74846fd35641cb809ec0ed6822f3a8` | 22–74 |
| `G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-5-n4b2-gateway-published-chain/frozen/engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java` | 73160 | `5e2666a0fac8c31132fe65fa6f9da6f53c2da18b914810c08944df3e00a0b3c1` | 313–442；445–498 |

继承的冻结架构证据为 `G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-next-run-observation-seam-proposal.md`（38357 bytes，SHA-256 `e6dede6002498c1580a607e39fd2c3fa3dd4703359e847892fe5319d3ea6a0e4`）及其原读取清单 `G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-next-run-observation-architecture/read-manifest.json`（9001 bytes，SHA-256 `739db2add73e5b32dcd2600b8b9fe062f9a4d06db2053c3642139e881362a964`）。原提案中的common/controller/旧测试定位仅是既有冻结发现；本次没有重新读取O1a作者可能变动的当前源文件。O1a方案信息来自root交接，尚未验为最终实现。

本次只新增本文；未改既有提案、brief、八索引或产品/target文件；未运行测试、probe、服务或部署。第7节为未来验收要求，不是已执行结果。交还后本文冻结，由root在O1a Accepted且完成第8节diff复核后形成正式O1b brief。