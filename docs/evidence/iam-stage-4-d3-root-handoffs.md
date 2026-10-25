# D3 / E 真实接入前的 root 检查点（准备稿）

尚未授权实施。D1候选145正在独立审查，D2尚未开始。本文件不把任何跨服务/业务流计作已通过。

## gateway 通配代理与验证域

现D1 `Ingress(method, template, Method)` 只接受精确入口，不接受 ANY/通配；普通service目录中的disabled handler也要求全局守卫。因此 gateway 现有 ANY `/admin/**`、ANY `/v1/**` 不能直接照搬 downstream 装配。D3需要在真实门户/集成专用验证链下，明确绑定实际gateway proxy HandlerMethod 的受限覆盖登记，并做实际启动/请求正负矩阵。它只能分类本机代理入口，不能授普通 service capability，也不能让其它服务接受通配能力或disabled路径。D1独立review将另判断当前分工是否有遗漏，root不得据本准备稿预先关闭发现。

## 具体资源和版本的首次解析

root已经确认 C 的请求绑定是非空三字段 ResourceBinding；authorize/forward精确比对原资源，forward不支持任意子资源。D1的controller用两份固定的版本化metadata证明接缝，这不证明真实gateway能取得业务域版本摘要。

D3/E正式移交前必须逐条实际调用分析：入口中哪些已给定immutable version/摘要，哪些只有appCode/runId或“当前版本”选择器；create/list/metadata入口的真实资源语义；gateway无本域PG时如何从可信metadata得到具体版本，以及受保护payload读取之前如何完成验证。不能让服务票据与自身字段比较冒充域对象解析，也不能把通用请求摘要、随意常量或从header自报版本当所有实际资源的替代。

若真实流程需要父资源→子资源或先解析metadata再绑定执行，应形成明确的可信父子关系/metadata-only协议接缝，保持原subject/grant/operation/ceiling和截止；authority需核真实服务身份与所声明关系，不能仅因签名字段彼此一致便允许任意改resource。任何新增内部profile接缝需要root说明来源、可核事实和有限正负例，再交实施和独立review。尚未选择具体新接口，不改C原行为或四契约，不把这一待核点称为现有已部署漏洞。

### 后续可核对的方案方向（推演，不是实施裁决）

可以先检查是否能保留同一真实根operation/resource跨hop，再由目标服务核真实父→子关系，避免直接放松C的exact binding。例如：受理root app-version后，在真实PG记录该operation对应的run/冻结call；目标服务在读取配置/执行外部请求之前，经固定受信来源取得metadata-only证据，核该run/call确实属于此operation、实际skill/model版本及请求摘要与冻结记录一致，然后复核根resource和本域guard/material范围。此方向必须依据真实表/调用参数和生产者身份证明，不能仅检查请求body中的runId或在目标本机拼一个相同摘要；若实际没有关系锚点则仍不成立。

对于浏览器只提供appCode/runId的首次入口，可研究专用、精确限字段的授权metadata查询是否能先完成可信当前主体/归属校验，再对实际版本执行主动作。该查询若需要新内部能力，必须单独定义它的具体资源、固定动作、仅metadata输出和零业务发送矩阵；不能给所有动作统一填常量版本或通用请求摘要，也不能把metadata查询票当payload许可。若实际流程需要新操作/子资源签名profile，要保持来源期限且独立评审，不能借metadata步骤偷偷延长原操作。

正式D3实施前，root应产出实际调用矩阵：入口/本域metadata来源、根资源、目标子资源、可信关系锚点、读payload/发送前检查，以及后台恢复/健康worker/机器事实投递各自用途。当前没有选择上述方向，也没有新建路由、迁移或签发能力。

## 其余必须随实际接入验证

- ServiceTokens静态dev/test入口与Spring配置来源统一，不让当前显式serviceSecurity或组合decoder退回共享HMAC规则；所有普通出站取票、URL/redirect/SSE/docs聚合路径都迁移。未知target默认拒绝。
- D2 POLICY_MACHINE是独立签名域/精确路径，不能走普通workload。gateway bootstrap只允许实际两条机器能力，不能以 `_gateway` 或角色字符串通用跨租户。
- 初始Grant ceiling持久锚点不可被current替代。后台恢复显式新DELEGATED operation，原PORTAL浏览器流不能随之续命。
- provider实际首发/合法SSE受理后才accepted；实际writer/reader/subscription在final/error/timeout/cancel后finally close。本地取消不冒称外部副作用停止。
- private manifest必须出自原受理连接，绑定真实producer/run/invocation/attempt/round/outputAttempt/资源版本/请求及payload摘要；不新开任意HTTP补manifest，不允许跨连接重放。
- PG scope与事实/材料同事务、metadata-first同快照读取payload、完整历史ceiling/父材料要求与labels并集，真实原始字节摘要；原RR1/RR2依赖E并与D联合，不能预缓存两个chunk代替。
- 业务批准严格按持久approvedBy与冻结confirmRoles，保留EDITOR合法正例与要求APPROVER/当前仅EDITOR负例，无ADMIN自动豁免。

所有实际服务配置、handler（含Actuator实际映射）和正例链完成前维持IAM启动闭锁；真实IAM未签收前BLOCKED_IAM_SCOPE_ACCEPTANCE不变。这里只登记下一阶段必须解决的问题，没有操作服务、数据库或源码。
## root裁决：首个published入口复用普通C的受限查询能力

2026-10-20，root已全文读resource-callmatrix与protocol-proposal（含第9节窄最小性挑战）。选择第9节方案2；第2节独立metadata admission/receipt/profile仅作未采用备选，不实施新metadata JWT、递归authority callback、receipt消费状态或十余profile类型。

- 首个切片新增唯一typed查询资源kind metadata-selector-v1、精确action app.published.metadata.read、target app及GET /internal/apps/{code}/published-metadata。它表示具体、版本化的查询描述对象，绝非app执行版本。descriptor为schema=app-published-selector.v1、verified tenant_id、校验后的app_code、selector=PUBLISHED、projection=app-published-metadata.v1；id用规范无歧义tuple编码，versionDigest=该descriptor canonical SHA。描述/schema版本不冒充业务对象revision。
- 元数据查询Grant经现C exchange/serviceSecurity/current和真实域tenant/visibility检查，只能返回真实versionId/versionNo/stored candidateSha及规范根。查询Grant不能upgrade/forward/resume换app-version根；即使authority误签metadata kind的payload动作，真实域也必须在读取前拒绝。新资源kind和精确action必须双向有限绑定，不任意套在普通业务动作。
- 正式主Grant由同一原外部sourceToken明确再次exchange，从开始即真实app-version（沿D2既定code@versionNo+storedSHA语义）。它与查询Grant明确不同；查询Grant不持久作run执行授权、不传成subjectToken，也不刷新原source期限。主域独立核当前主体、真实PG版本与权限后才payload；无需把曾查询过metadata作额外授权条件或新增receipt。
- 首入口元数据从固定受信app端点直接返回，禁redirect/任意URL/跨subject缓存/用户上传DTO。两次exchange之间source到期、旧integration认证policy版本锚点失效均拒绝；主操作按当前收窄范围。完整初始ceiling来自主Grant实际映射，不能拿queryGrant或当前Actor替代。
- published指针竞态不得静默切版；为保持现无version参数的“当前发布”入口语义，正式受理重核它仍指向已解析版本，否则明确409并要求重新获取，不用后台重换票给旧主操作续期。显式version入口另按immutable版本语义移交。

root一次聚焦核实：AppService.get(code,false)实际查询a.*并联查draft.config/definition/layout，row还计算draft候选SHA；published()先get再version。因此不能复用get/published/PublishedInputs或名称为Metadata的现方法作首入口。当前app既有读取按已验tenant+code隔离，没有个人owner谓词；不得凭空引入principal_id app owner。保留真实应用status DISABLED/ARCHIVED/未发布409；窄SQL只查询application/current pointer与app_version存储字段，绝不选snapshot/config/definition/prompt。

尚须正式D3a开工前核实：集成票据签名域中app/credential绑定如何从实际已验service JWT/current产物进入本域校验；不能只信路径/HTTP header，不能借现两条机器bootstrap扩权。现目录engine→app version/published read的integrationAllowed=false，未来合法集成依赖链需在实际root/child检查落地后逐条显式调整，不能把所有internal行一律打开或因同action改变admin别名。gateway ANY信任域登记/身份来源、Spring与静态ServiceTokens模式统一、E完整ceiling/受理持久与child relation仍按原依赖实施；本段不签完整D3，也未授权在D2返修审查关闭前改源。

list/create/docs/aggregate/health/fact不套用published查询descriptor。其具体资源语义、真实元数据与guard另行逐项裁决，不默认落地原提案的query snapshot/creation-intent新表。事实投递/读取仍按Task5初版当前主体与材料scope，机器身份不替代用户许可。所有原Task4/5正负和真实流验收保留。
