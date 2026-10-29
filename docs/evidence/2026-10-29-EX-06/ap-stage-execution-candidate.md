# EX-06 AP阶段精确执行与白名单候选

2026-10-29，docs_reader_check。root本轮只授权修改本报告与`ap-stage-plan.md`；下面是后续待准入范围，不是我已取得的脚本写许可。不运行Node/PS driver/HTTP/Java/PG/Redis/服务，不改Git index。阶段结果由root实际执行，不用静态报告冒充已创建或已通过。

## 1. 当前封版与历史边界

- 已存在Ontology session：`joint-or-20261029-01`，私密目录`.local/acceptance/ex06-or-20261029/joint-or-20261029-01`；两轮ONTOLOGY_READY/complete=false、49 actions不变、key文件仅SHA相同。其opCode=joint_list，不能重新派生、重新发布或改manifest响应。
- 初始模型候选的历史封版身份：`scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs` SHA `bcfa907779dd98cb69a26a8de9d20d4e048acebc6cbdf2e38255f2ecca5ff401`；`docs/evidence/2026-10-29-EX-06/Test-JointProtocol.ps1` SHA `26107bc6e05f1db85fda28f6c3f02cff34c3f60c0057450619e8a82c2cbff4c0`。独审无阻断，原25逐字保留、目标63；初始静态PS5 Parser=0。后续root实跑与两行Handle工具修复以其原件为准，本报告不倒填此历史SHA为修复后身份、不重改脚本。当前Model角色60s寿命适合协议测试；不能假定足够覆盖AP全资源链。若AP窗口需要更长存活，必须另冻结明确总时限/nonce停机/进程身份与对应测试，再修改这两路径，不能静默去掉TTL或开无限进程。
- Prepare FCE9、Common A008、PreparationTest59A8保持原字节；All依旧明确未交付。`ap-stage-source-index.json`及归档验证是模型改动前的历史快照，不能改旧SHA伪称同源新版验证。

## 2. 下一实现最小步骤：只补opCode兼容

候选仅修改以下3个既有文件，必须root另行准入；不与模型63执行并写：

| 精确文件 | 允许改动 | 必须保留 |
|---|---|---|
| `scripts/acceptance/Joint.Common.ps1` | 小纯函数计算新session的joint_+UTF8 SessionId SHA256前24小写hex；解析/校验journal中已确认opCode，矛盾拒绝 | A008私密Replace修复、有限HTTP、安全metadata与32原规则，不增加另一套HTTP框架 |
| `scripts/acceptance/Prepare-Joint.ps1` | 首个OR业务写前把opCode原子写入journal；所有operation/端点查找、消费URL、A/B resource字段取同一已固化值；旧partial由resources/已确认action和实际operation读回保持joint_list | 原49动作/资源ID/秘密文件不重签；Resume pending拒绝；All未交付拒绝；不改生产/OR Java |
| `docs/evidence/2026-10-29-EX-06/Test-JointPreparation.ps1` | 先追加纯准备用例：同Session确定性、不同Session差异、30字符/字符交集、ASCII Session按UTF8哈希、非法Unicode Session仍按原入口拒绝、旧partial joint_list保留、冲突值/缺已确认事实拒绝 | 原All=32完整断言不删不放宽，旧实跑源保留；缺函数/解析失败记准备失败，不当业务红 |

新session opCode30字符，符合OR字母开头/ASCII字母数字_-且≤80与AP≤64交集；不能以随机nonce替代SessionId，不能截断SessionId直接当code。已有session资源重入仅用journal实际值；若旧journal缺新顶层opCode，只有resources及实际操作一致才能补该安全字段，不能把未知pending推测为成功。此小步完成后封源、PS5 Parser、独审，root先跑准备层原32+新增向量与模型63回归；该步不创建AP资源。

## 3. 后续AP实现候选白名单（分批准入，不一次授权）

| 微步骤 | 精确候选路径 | 边界 |
|---|---|---|
| 运行计划只读校验 | 上述Common、Prepare、Test-JointPreparation | 在已有参数内先校验安全runtime plan。若需要新增`-RuntimePlanPath`，root先冻结参数/JSON字段；不要硬编码共享DB/26379，不直接启动DB/Redis/Java |
| provider/entry薄步 | 上述Common、Prepare、Test-JointPreparation | 只实际创建本session fixture provider/entry并真实test、记录可恢复阶段；仍complete=false。Loopback协议证明不得假回ENABLED/评估PASS |
| 单技能准备 | 上述Common、Prepare、Test-JointPreparation | 正式manifest原文导入、DEBUG连接/重检、选型、publish；完整stage未知则停。只保存连接实际字段，无连接reportId |
| 三应用准备/All完成 | 上述Common、Prepare、Test-JointPreparation | 三应用各自create/config/evaluate/precheck/commit；按第5节绑定初态核验后才能完成manifest |
| J验收入口 | `scripts/acceptance/Invoke-JointAcceptance.ps1`（已冻结计划入口，当前未实现） | J1→J2→J3...J9真实API与安全证据，失败关联原卡，不在验收器改产品 |
| S1入口 | `scripts/acceptance/Invoke-S1Acceptance.ps1`（已冻结计划入口，当前未实现） | ModelMode明确，正负candidate隔离；异步/故障桩需另冻结精确fixture增量再写 |
| 任何后续模型寿命/协议补差 | 仅现Model fixture与Test-JointProtocol两路径 | 先提出TTL/协议向量并freeze，不在AP步骤偷改已封版63；根线程实际执行 |

不新增产品Java、SQL迁移、公共启动器、整租户清理器、网络依赖或未知文件；生产/三主文档/Git index不在此候选白名单。安全运行产物仅在`.local/acceptance/<RunId>/<SessionId>/`，不把secret文件作为docs/evidence源。

## 4. 运行窗口准入表（root实际核验后再执行）

| 资源 | 精确核验/登记 | 拒绝条件 |
|---|---|---|
| PG | 本仓127.0.0.1:25432进程/数据目录owner；root先选本session唯一新DB名/owner，启动前无业务表/history；六服务同DB app/engine/skill/model/monitor schema；显式Resume只复用同session已登记DB/owner/准确history，不再要求已有业务库为空；现共享库旧run/artifact只读核归属 | 不能因服务健康就接共享库；不能cleanTenant/repair/修改history；创建DB前先登记pending资源名，未知创建结果停 |
| V222 engine包 | 候选JAR `engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar`整体SHA `76ab67c3a72cd1931e2dc64e053cae8a11a86a850cfa9da5eb95fee1132b419c`；JAR/target V222 SHA `419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`；23源码/24包迁移清单；root单独确认Java来源可追溯 | 任一包/源/迁移变化先停回EX-27重新准入。新库由已准入既有Flyway正常迁移，读回24份history及V222 checksum701700386/success；此值尚非本轮验证。不抄SQL、不clean/repair、不伪造checksum |
| Redis | root核空闲并登记本session独占loopback物理端口、二进制来源、私有data/config目录、PID/启动时间、配置SHA；model/engine一致指该endpoint | 禁复用26379共享实例、禁仅换逻辑DB/SELECT/前缀：RedisChunks.java:93实际connectPubSub。未知占用不接管，禁FLUSHALL/FLUSHDB；启动未知结果保留pending不重复拉起 |
| Model fixture | 已核候选SHA/role=Model、modelName=ready.model、注册opCode名单、端口0实际分配、PID/nonce、自限TTL与停机预算；仅明示fixed协议输出 | 60s仅证明63协议用例；AP长窗口若寿命不足要另冻结/实测，不调成无限；不伪造provider/entry状态或published/eval |
| DEMO归属 | providerCode=`fixture_joint_`+SessionId SHA24，长度38、命中FixturePolicy.code contains fixture；真实POST创建/test/读回source=FIXTURE，entry同provider、capabilities含chat/tools、trustLabels=LOCAL | 不用避开fixture命名的“正式provider”偷渡桩；model与engine都显式AGENT_DEMO_MODE=true，engine run runtime.environment=DEMO/demoMode=true。Real模式只显式合法ID且不冒用此fixture |
| 六服务env/进程 | 6份受限文件同AGENT_DB_URL；engine/model的AGENT_REDIS_HOST/PORT/受限password指独占endpoint，app另AGENT_SYSTEM_REDIS_ADDRESS同一独占endpoint；app binder/engine readonly/skill consumer隔离；model不接OR key。记录全服务PID/JAR/源/安全env SHA及开关 | 不能只覆盖三份OR secret env就认为六服务切库/Redis；start-backend会调用start-database并可能build，先核其副作用或用root已有安全wrapper，不新增启动框架。不能启动scheduler到未知共享库 |
| MinIO/OR | 真实已归属MinIO及本session对象前缀；既有OR18182 READY/credential/grant活性读回，主/临时key分别保留 | 不清旧bucket/对象，不撤主key，不用健康响应替代资源所有权。OR管理密码仅受限配置→PSCredential，不入命令行 |

端口与DB具体名称由root在实际窗口确定，本报告未占用/探测/创建。运行计划要先包含这些准确值与ownership证据；若没有，就在业务首次写前拒绝，不能默认26379或agent_platform_dev_v12。当前JAR历史盘点不是新窗口启动许可。

## 5. 三应用发布与J用例的唯一顺序

1. 在本session skill已真实发布、model可用后，取确定性三个独立code（建议joint_<SHA24>_resolve/_explicit/_pin），每个创建CHAT、按最新draftRev保存config。全部显式modelBinding和精确skillRefs版本；actor region=LOCAL，guard只声明真实属性。不得编辑OR manifest改变tool_code。
2. `appCode`只保存ontology.space，不带releaseId；不初始pin。真实评估ACCEPTED、BLOCKING预检passed、commit，返回pin=SKIPPED且查该app/environment无ACTIVE绑定。评估的BINDING_NOT_FOUND跳过context是准备事实，不是J3；不得先建后删伪造J2首次状态。
3. `explicitAppCode`、`pinAppCode`都保存old releaseId，各自独立dataset/eval报告、预检与commit；核OR绑定和app.publish_pin均old且pin=PINNED。三者都PUBLISHED才满足准备完成的一部分；还需OR/私密ref等全条件才允许输出完整成功manifest。
4. J1只读写拒绝后，J2对动态app第一次binder POST：changed=true，重发false、bindingId不变；随后J3真实运行动态app查contextPack。J5将动态app与explicitApp远端pin都改new，前者缓存边界后取new，后者冻结old/最终old。J6/J7/J8各按临时组和隔离空间既定门。
5. J9在pinApp已发布old的基础上，PUT config把releaseId改new，核新draftRev/sha/semanticDigest；为新候选真实评估→新预检→commit。保存新版本/新pin，再以同idempotencyRequestNo和完全相同发布body重放，核没有第二新版本/重复ACTIVE绑定。不是重放准备期旧报告，不把J9说成首次绑定。

应用评估请求有expected和safety布尔、至少一安全用例；普通CONTAINS(text正文)，安全SCHEMA强约束text.const=EX06_REFUSE并保留metadata形状。TestFixture的固定拒绝只证明门禁/协议；S1另做正文EXACT负例，不能借已有ACCEPTED/豁免或semanticDigest旧命中洗掉负例。J9新release是语义改变，必须新实际报告，不用展示字段变更冒充新候选。

## 6. pending先写与unknown-stop

- root创建DB/Redis/进程的动作先记安全planned/pending资源名、路径、端口和源指纹；API创建provider/entry/import/connection/acceptance/app/evaluation/precheck/commit也先原子落pending。含凭据的body不可直接落盘；只存安全摘要、请求号及资源预期，不存Authorization/key/token/密码。
- 成功响应经白名单验证、准确安全ID/sha/阶段落盘成功后，才能写confirmed并清pending。连接响应无reportId；acceptance取真实id；evaluation取真实id；precheck取真实precheckId；不能为统一框架编造同名字段。
- 超时/断连、未知响应、响应验证失败或落盘失败保留pending，停止后续写，不能自动再create/import/issue key。Resume遇pending一律拒绝，交root只读比对实际资源与精确请求身份；无证据不按code猜归属。密钥已签发但未存成功走SECRET_SAVE停，不重签覆盖。
- commit的事务可能已成功但pin未知；502/超时也unknown-stop。root先只读核版本/OR绑定/app.publish_pin，再明确同号恢复。J9已确认成功后的同号重放是单独登记用例，不是无限自动重试；允许审计增加，要求版本与绑定幂等。
- final manifest只有全链真实完备才原子写；任意局部阶段都partial/complete=false。资源、DB/Redis/进程清理只针对本session准确归属并记录到期/终止结果；未核进程退出不能宣称清理完成。

本轮交付仅文档候选；root复核后逐步下发代码/运行准入。完成本报告后停写。
