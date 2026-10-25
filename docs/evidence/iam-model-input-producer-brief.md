# 模型初始输入材料生产者：正式实现任务

本任务已获 root 授权实施；用户授权持续按详设 v1.2 开发，并确认使用隔离测试端点。唯一工作目录 G:/207/项目/算法重组工具/代码/agent-platform；每次 shell 显式设置 workdir。禁止读取、引用或影响相邻旧工程代码、配置、数据库、进程、依赖。无用户确认步骤，无主进程重启、主仓提交、部署、外部消息、自动化或新聊天。

## 1. 需求和已裁决边界

先完整读 `.local/iam-model-material-next-task-plan.md`（27505B；SHA256 cff029fbc1722e10ec621de7411dd1233e390a4c8336d07390ed3604b71c4c64）。该计划§2～§6的四项绑定选择、具体API、事务/摘要/父scope/元数据读取、HTTP/action与失败优先验证作为本任务需求，以下澄清优先于候选措辞。读 AGENTS.md、docs/implementation-brief.md、contracts/README.md 和相关 TDD/Java/Spring/验证技能。

交付一个引擎所有的初始模型输入持久生产组件和真实认证的 manifest GET。真实PG保存 request+scope，并通过真实控制器、canonical catalog、签名authority、正常TLS验证读取。它暂未接入公开模型运行链：GraphRules/TraceStore 的纯本地 FLOW 限制、公开 CHAT/modelRefs 门禁、driver 和默认生产 IAM guard 全保持。不调用模型、tokenizer、measure、SecretVault，不新增发送许可/response lease，不修改模型网关。

仅支持计划定义的 history-free initial-chat-main-v1；从已有不可变 snapshot/inputs/versionRefs 和 FrozenModels 组装输入。prepareInitial不接受任意DTO消息、scope、owner、modelBindingRef或AuthorizedMaterial；父材料和模型ref来自PG受保护根。requestSha是完整规范受理请求摘要，不是SpringAI wire摘要。当前无实际发送授权；后续wire映射/first-send单次proof另做。真实正向组件fixture经现有owned createPublished建立model-bearing根，位于仍闭锁的公开受理以下；必须如实标注。

固定 Java21/Boot3.5.0/SpringAI1.0.0、Vue3.5/TS5.8；PG唯一业务权威，跨业务HTTP，无其他业务JAR；ApiResponse/PlatformException/Actor/Json/Digest及既有authority机制复用。secret_ref约束、验签tenant/principal、默认拒绝、四契约/v1.2/无排期不变。不新增通用材料框架、权限框架、reaper或后台executor。

## 2. 当前冻结基线

限定证书 `.local/iam-o2b-f2c2c-acceptance.json` 5354B / eb80ee2273fc114b1df01652cb14ebcee1ffb79d476af82963d66c833486bae3。

source644：`.local/iam-o2b-f2c2c-chunk-fallback-fix/final/source.json` 123759B / d666e8dc7f021659ff813e0cb196d7e7e02042a252c4bc6995b536fb59723fcf，完整字节在同级 source/。Backend与accepted B2相同。不得把历史后端2339逻辑目标当当前全classpath。保护8文档：`.local/checkpoint-iam-o2b-f2c2c-docs-before/manifest.json` 4664B / 9f988054a129a0506270cbf1e6c9c10e935546aeb9a58d62f65766f0328e7f00，比较AFTER身份；全局34状态/T01唯一完成保持。

root 已核活动 engine migration 最后为V217，V218目标尚不存在。固定唯一新文件 `engine-runtime/src/main/resources/db/migration/V218__initial_model_input_material.sql`；写该文件之前，先在自有新PG测试库验证V200～V217的实际Flyway history和当前源migration列表，没有漂移/重号再落V218。不是预占任意高版本。禁止改旧迁移、启用outOfOrder或触碰主库/主服务。新增模型输入表及model-input kind/version CHECK扩展严格按计划，原scope语义和immutable trigger保持。

## 3. 精确源码写权限

只改1个既有文件（只增加计划规定的单条canonical row，不改其他row）：

- admin-common/src/main/resources/agent-service-authority.json

只增8个文件：

- engine-runtime/src/main/java/cn/agentplatform/engine/domain/ModelInputManifest.java
- engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelInputMaterials.java
- engine-runtime/src/main/java/cn/agentplatform/engine/infra/ModelInputMaterialStore.java
- engine-runtime/src/main/java/cn/agentplatform/engine/api/ModelInputMaterialController.java
- engine-runtime/src/main/resources/db/migration/V218__initial_model_input_material.sql
- engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialPostgresTest.java
- engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialHttpTest.java
- engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputMaterialFixture.java

源码644→最多652；不删源。其余全部源码、Java业务模块、common authority实现、模型、前端、package/lockfile、四契约/原设计/生成API、其他旧证据及保护8文档只读。任何必需额外symbol修改先给root具体现有调用和最小范围，不可静默扩大。

自有可写 `.local/iam-model-input-producer/`、`docs/evidence/iam-model-input-producer/`、`.tools/iam-model-input-producer-*`；报告 `docs/evidence/iam-model-input-producer-report.md`。可写生成目标只限本任务实际Maven compiler输入/createdFiles/resources/common fixture JAR/实际Running报告及自有诊断/快照；据实际清单取有限集合，不扫全node_modules/历史target。允许现有 `.tools/m2` 依赖缓存和该任务实际独立编译缓存。自有全新测试DB名以iam_model_input_开头、唯一且预检不存在；仅新工程PG25432/agent_v12。随机loopback测试端口/自有正常CA/SAN TLS；不使用主18080～18085、15173～15175或其他既有监听端口。不得删除临时目录。

## 4. root补充澄清（无需再向用户询问）

1. 真实受保护run字段为trigger_type=TASK、mode=FULL（RunAuthorityStore现状），不要发明FORMAL run mode。计划的request.callKind=FORMAL是请求计量字段，两者分开；r_32hex与m_32hex按计划。
2. 新类必须保持legacy/dev bean构造兼容。现有HttpCurrentAuthorityClient并非每种部署都有，优先沿用现有ObjectProvider/显式有限装配模式，不能新增长驻硬依赖使legacy上下文启动失败。在无可信IAM组件/上下文时，新export端点须在受保护元数据读取前默认拒绝。现有main/security guard不能修改；新增测试以真实PG的有限legacy上下文证明新增bean可构造/拒绝，不能外推完整默认主类启动已验。正常IAM fixture使用实际新controller/组件及canonical路由，不用替代业务controller。
3. 只读取manifest元数据，不能select request后声称没泄露payload；写入时同事务绑定payload/manifest/scope，GET依immutable事实核身份/根/父closure/current。受信DB管理员禁trigger后篡改payload不在本GET检测保证内。正文/日志/异常不输出grant、token、request正文或凭据。
4. 模型首请求profile不宣称session/chat_turn/模型历史/重试支持。旧ModelExchange冻结会改invocationRef和扣预算，禁止调用它冒充此profile发送。当前三根父scope、exact closure、full original ceiling、所有父义务与可信标签按计划，并用真实PG再次读取核对。
5. getter捕获AuthorityContext.requireInitialFor后仅同请求线程使用；prepareInitial通过既有RunAuthority.currentRetained在事务外取proof。PG事务中不联网；首/末本地private proof检查及最终rollback-only行为按计划。响应/元数据可重复授权读取，不是一次性发送凭据。expiry检查存在与PG提交的真实非原子边界，后commit检查拒绝返回不等于回滚已提交事实。
6. 新catalog row的source必须指向实际新接收控制器；callerSource明确标注model消费仍计划中，不写不存在调用已落地的证据。HTTP TLS负向仅证明新控制器所用夹具连接的身份验证；未来model专用client未实现，不能据此签收其fixed-origin/redirect策略。
7. 计划给出的边界数值是这个有限初始profile：source每份1MiB/合计3MiB、request2MiB、manifest256KiB/depth32/3父/128roles/32entitlement keys/每key256values/256labels；在分配或materialization前探长/有界读，再核parsed/canonical。不要扩大到“全JVM/容量通过”。

## 5. 执行与验收

先保存frozen-inputs与before字节，核所有准入身份、活动原文件和新路径不存在。先写有意义的失败/正向对照，编译缺符号不算RED；最小可编译接口下记录真实PG缺scope/双写原子性/未验HTTP等失败。再实现；本任务不要求为了RED把安全现有路径写坏。使用计划§5的真实authority/新PG/SQL约束/HTTP/TLS验证：正常创建/幂等/并发、事务中异常即使上层捕获亦不能partial commit、锁等待/提交前期限、父current收窄、伪scope/Actor/currentOnly、错owner/root/model/ref/digest、legacy行和scope缺失拒绝；实际控制器正向manifest和headers、所有签名/调用方/用途/selector/父scope负向；网络/secret/tokenizer/provider=0。每个失败保留真实原因，不能把更早拒绝冒充后续读取检查。

按计划的定向新测试→受影响既有回归→common+engine package（skipTests不冒充测试）执行，采用本工程现有Maven/JDK21/自有m2；每个阶段保留实际Running/XML/输出/命令/源码/有限目标和进程wait/collector。不要重跑C2c/已绿O2链、全后端/前端/PG历史；出现新变化或失败才补受影响检查。迁移/新类不导入其他业务JAR。没有网络外发的组件不宣称模型调用成功。

完成自审与注释（不变量、事务边界、真实开源机制、有意限制，未读上游源码不能声称已读）；完整最终源码/blob、有限产物字节快照、实际新PG identity/迁移/HTTP/反向计数/资源退出、一次自有scratch native before644→after实际源码的plain/binary-capable patch+check/apply、完整任务差异与源EOF（明确哪份是diff，哪份是全文），报告/control/inventory LAST。错误留原helper/记录/cache，只续未完成metadata phase；不为文案重复绿色。

报告仅宣告DONE/frozen待review，不宣告Accepted/公开模型已接通/全17功能34任务30验收/真实IAM/K8s/容量。返回全部source/target/DB/HTTP/helper/owned/evidence/report写权与精确身份后，root和fresh reviewer再读取活动产物；禁止冻结后继续修改。无子代理。
