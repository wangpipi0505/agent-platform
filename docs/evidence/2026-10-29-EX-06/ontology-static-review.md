# EX-06 Ontology阶段独立静态审阅

审阅者：`algorithm_interrupt_readonly`，2026-10-29。只读实际脚本、OR/AP源码与指定安全证据；没有运行协议入口、Node、Java/Maven、API、PG或服务，没有读取真实`.env`、OR配置值、私密凭据文件或原始签发响应。只对四份PS执行了`Parser.ParseFile`静态解析，均0错误且有UTF-8 BOM。

**最终结论：准入由root唯一实际窗口执行显式StageOntology。** API、发布顺序、权限、私密journal及重入未发现其它静态阻断。C2A6版本私密原子替换的真实协议红保留；root只修一处CLR null表示法至A008版本，原测试未改，新RunId实际25协议检查及32准备检查均通过，已读原回执核实。此准入不是OR资源准备通过；`Ontology`不是完整Prepare，EX-06及EX-07不能因此关闭。

## 审阅对象与身份

| 文件 | SHA256 |
| --- | --- |
| `scripts/acceptance/Prepare-Joint.ps1` | `fce9ce3a4e06d3b01ac9b34f80283de86740c0393a14788cbe8ef29d842fab98` |
| `scripts/acceptance/Joint.Common.ps1`（本次最终准入） | `a008f692d89d18a416b657c9cb3b5f08f804b2bb44205e616253b90db48505cd` |
| `or-stage-source/Joint.Common.ps1`（保留的原子替换失败版本） | `c2a6c8a483c7f3785497a7dcfb94d089e632891abc70d7ea9e70720f9ed6b01a` |
| `scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs` | `31cb04c62461097d9a02b28337184990a2601642ef6e59c0ea336317657a1d4e` |
| `Test-JointProtocol.ps1` | `b3ea5caffc377b406b8ab9e4eb30e74313566fedda04017361ae5ae8f9d8678e` |
| `Test-JointPreparation.ps1` | `59a8aa9f9c6273a154c60cd848877a97a487d4c12e79ca3f4a8c62eaa0c1d1c6` |
| `or-stage-candidate.md`（时限表述纠正后） | `d61fe05c5a95241f3c4c2feb92e05db6e41a4b6814487d6fe69fffe09901afa3` |
| `or-stage-source/manifest.json` | `cb7715b1c11d2d7aff8de1b3ebb72e006c17c4a44b78d5a23e31d319f3cee5a5` |

初始五份当前文件与manifest所列SHA及五份字节快照均相同；11份OR只读参考的当前SHA亦逐项相同。最终只有Common改变下方所列的一处表达式，其余四份当前文件SHA仍精确等于原冻结；新Common再次ParseFile为0错误。原manifest/快照继续标识C2A6候选，不能把它们倒填为A008运行来源；最终来源以本表和两次绿回执为准。下列新增读取仍只读：OR `ModelController`、`SupplyController`、`CandidateService`、`V25/V27`，AP `DevAuthentication`、`SecretVault`及app/engine的`application.yml`。没有修改这些来源。

## 实质核对

| 项目 | 核对结果 |
| --- | --- |
| 阶段与边界 | `Prepare-Joint`第144行在任何目录/HTTP/资源写前拒绝默认`All`，固定错误码/exit2；`Ontology`成功仍`complete=false`、仅`partial.json`，不写最终manifest、不声称三个AP应用或J1–J9/S1完成。ModelMode及Real显式模型ID只保留上下文，不伪称模型已验证。 |
| AP身份与服务依赖 | StageOntology仍需18080网关`agent.dev-auth=true`的实际`POST /auth/dev-token`，校验响应为`local-v12/developer-v12`后才做OR业务写。该响应字段与`DevAuthentication`一致，region来源为服务固定`LOCAL`；脚本没有自报身份头、转发此开发token到OR或从请求体扩大权限。18182的OR API/其既有存储也必须实际就绪。 |
| OR管理身份 | PSCredential只入登录请求内存，真实`/auth/me`与journal owner一致后继续；所需系统/空间权限仍由当前API校验，系统管理员没有被脚本当作任意旧空间OWNER。finally只尝试登出本次token，不改其它会话。配置值由root安全wrapper处理，本审阅未读取它。 |
| 独立空间与写修订 | A/B空间绑定随机nonce、code/namespace及当前OWNER/ACTIVE；只写新会话资源。class/operation/guards/grants/assistant/test-case接口、字段及返回`id/draftRev`与真实控制器一致，每次未完成空间写先读当前draftRev。查询仅筛`JointTask`，A新增revision类不改变被冻结精确结果。 |
| 正式发布 | 当前draftRev建candidate→validate→真实tests→OWNER review→prepare→commit，test passed及candidate SHA精确相等；review/prepare绑定candidate draftRev/SHA；prepare必须`READY_TO_COMMIT`且四制品种类齐、ready、正size、64位SHA；commit回传ID必须是preReleaseId、正确space/摘要且READY。不是单靠HTTP200或自造状态；真实PublicationService保留机器门、OWNER门及修订锁。 |
| 发布读回 | A原版/替换版、B隔离版共3个release，逐一真实详情确认READY/space/摘要与四制品，按opCode读唯一ACTIVE端点。没有复用Smoke末尾会弃用的release，也没有在准备阶段撤销/弃用它们。 |
| 凭据种类与主体隔离 | 4个SERVICE_ACCOUNT不传principalId、明确environment，由服务端创建独立主体；2个PLATFORM_ACTOR使用两个新ACTIVE MEMBER用户，不传environment。6个actor/principal不同，deptScope/attributes显式LOCAL，12h有效。符合ReleaseGatewayService两类字段限制和一次43字符key返回；没有用旧`onto_`或互换`ontosa_`/`ontopa_`。 |
| 最小授予 | 仅两个新binder追加已有ACTIVE BINDER角色，先读当前用户、保留原roleIds/status/displayName并带expectedRevision；不改共享角色。实时核四个service主体ACTIVE及有效权限：readonly有release.read且无release.bind，binder有read/bind。真实V25/V27及服务创建路径分别只授read与显式bind。两新消费者各授A两端点共4次，不授B。 |
| 真正key可用检查 | 目录逐ID/主体/租户/种类/环境及ACTIVE校验，随后4把service key实际读manifest，2把consumer key各调用A两版操作。consumer端点返回`code/data`与CandidateService一致。此层只验证OR可用，不能冒充平台绑定/导入/运行链，也没有提前执行J7破坏性撤权或声称J8负例通过。 |
| 秘密输出边界 | key仅经受限`credentials.env`及按角色划分的app/engine/skill env落盘；JSON只摘ID/ref/状态/摘要，元数据检查递归拒绝秘密字段、原始key/Bearer/JWT形状。env中的租户绑定格式与SecretVault实际解析一致；main app/engine各拿独立binder/read key，skill拿consumer key。没有把带key原响应、密码、headers、异常原文写入证据。 |
| 私密路径/ACL | 全路径限制在本仓`.local/acceptance`，拒绝现有reparse祖先；私密目录新建后保护ACL，仅当前用户/SYSTEM/Administrators，已有目录只验证不自动扩权。文件名不能逃逸；写前核现有文件ACL，临时文件从已保护目录继承，目标写后再验证。原子替换一处表示法修复及同断言实际通过见下方，不更换为先删除目标再写的非原子路径。 |
| 写入中断与恢复 | 每次业务写先原子存pending；只调用一次，不做写重试。key响应先记录`SECRET_SAVE`及安全ID再落env，未知请求结果/秘密保存失败保留pending；Resume在任何HTTP前拒绝pending。没有重新签发、清pending猜成功或自动撤旧key。已完成动作可复用，但不是把partial当最终manifest。 |
| 最终Reuse边界 | 主19字符串字段及两组fixture白名单、UUID/区别资源、context/URL/secret_ref先静态校验；必须同session私密journal且ONTOLOGY_READY，再实际读资源/主体/权限/key可用，最后核manifest资源映射相等。即使成功仍只声称OR阶段，AP appCode不被虚报为已验证。 |
| HTTP/夹具 | loopback HTTP根URL，无userinfo/query/fragment/附加根path；单请求不重定向、不混Bearer与API key，响应长度上限及固定错误码，stream/response finally关闭。协议夹具仅本机临时端口、pid/nonce握手、100请求/60s生命周期/echo64KiB，不能模拟OR或模型业务成功。驱动准备失败与断言失败分列并停止子进程；本次未自行执行。 |

## 必须保留的实红、最小修复与绿回执

root执行的[原协议安全回执](../../../.local/acceptance/ex06-protocol-ontology-20261029-142808-008/protocol-checks.json)显示`PROTOCOL_ASSERTION_FAILED`、`passed=false`、22条已完成检查、`JOINT_PRIVATE_WRITE_FAILED`、`businessValidationPerformed=false`。第一次私密写入/读取已达，第二次原子替换未通过，后续检查未达。[execution.json](../../../.local/acceptance/ex06-protocol-ontology-20261029-142808-008/execution.json)确认为实际Windows PS5.1进程、native1、五源before/after稳定。目标原42B保留/Node退出由root报告，本审阅未重新操作目标或进程。

root的[PS5独立表示法实证](../../../.local/acceptance/ex06-replace-proof-a5df0eef396d46e5adbceab316bd2046/proof.json)记录原调用`ArgumentException`、`The path is not of a legal form.`、原目标保持及NullString替换成功。独立逐字比对确认最终Common除第227行的`[IO.File]::Replace($temp,$path,$null)`改为`[IO.File]::Replace($temp,$path,[System.Management.Automation.Language.NullString]::Value)`外，全文相同；没有改等待、权限、HTTP、测试或其它路径，也没有退化成非原子覆盖。

| root实际新RunId | 本审阅读到的原回执 |
| --- | --- |
| `ex06-protocol-ontology-green-20261029-143041-803` | [protocol-checks.json](../../../.local/acceptance/ex06-protocol-ontology-green-20261029-143041-803/protocol-checks.json)：25条检查、passed=true、safeError=null；含第二原子替换保留首个secret条目及路径逃逸拒绝；[execution.json](../../../.local/acceptance/ex06-protocol-ontology-green-20261029-143041-803/execution.json)：Windows PS5.1、native0、A008/其余四源前后稳定、businessValidationPerformed=false。 |
| `ex06-plan-ontology-green-20261029-143055-816` | [preparation-checks.json](../../../.local/acceptance/ex06-plan-ontology-green-20261029-143055-816/preparation-checks.json)：Case All、32检查、checksPassed=true、safeError=null、HTTP0/业务资源0；[execution.json](../../../.local/acceptance/ex06-plan-ontology-green-20261029-143055-816/execution.json)：Windows PS5.1、native0、A008前后相同，Test59A8未改。 |

以上均由root执行，本审阅只读取记录并复核当前源，不把它写成自己实跑或真实OR业务已绿。C2A6仍不可作为真实签发版本；当前A008可进入root唯一实际窗口的StageOntology。随后仍须逐项保存3个真实READY/6key主体/4端点授予及私密ACL、重复/恢复边界证据；不能关闭整卡或调用尚未交付的Invoke-Joint/Invoke-S1。任何实际失败保留新RunId和pending，不做盲重试/重签或拼接局部通过。

## 时限表述的精确边界（非新增实现要求）

当前代码是有限调用序列、无业务写重试，HttpWebRequest设置30秒请求/读写超时，OR封装在每次进入时检查900秒剩余预算。它不是严格端到端30秒/900秒截止证明：同步请求流/响应流有分段等待，dev/login及后段key可用检查直接调用公共HTTP helper。候选说明的“最长/总预算”应按这一实际机制表述；不据本静审声称绝对wall-clock上界，也不要求因此新增执行器、计时线程或重写HTTP框架。
