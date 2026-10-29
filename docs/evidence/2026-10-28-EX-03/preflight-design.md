# EX-03 只读前置核实与最小失败测试设计

日期：2026-10-28。核实起点 HEAD：`0cc967278007343bd5ab09faf9953d86cd573bf8`。

此文件只记录源码与已审 EX-00 证据的核实，不是实施、红测、通过或独立评审证据。当前 EX-01 独占打包/测试窗口；本阶段未修改产品/测试 Java，未执行 Maven、Java、PG 或 HTTP。未读取 OR 真实配置或私有夹具，未 add/commit。

已读 AP `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md`，统一台账、统一执行卡片的共同约束与 EX-03/04、联合回归手册及 EX-00 比较口径。按用户要求不使用 superpower；采用已告知的 ponytail 原则，只复用已有鉴权与凭据规则。

## 1. 调用链与现状

| 环节 | 当前源码 | 结论 |
| --- | --- | --- |
| 管理导入 | `skill-registry/src/main/java/cn/agentplatform/skill/api/OntologyImportController.java:21` | 2 MiB JSON 对象转 Map 后直接调用导入器；没有 DTO，不需要控制器/DTO 字段扩展。全仓实际调用者只有该控制器与既有导入测试，未找到前端业务调用者。 |
| 转换 | `skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java:43`、`:74` | `importManifests` 只读取顶层 `authRef`；调用者的 `authScheme` 未被检查。`toSkillBody` 仅在非空时复制引用，从不写 `authScheme`。 |
| 保存 | `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:293`、`:394`、`:440` | `create` 在事务内调用 `normalize` 并将规范化契约写入 PG。普通 HTTP 的引用检查只做 vault 前缀/env 正则与 ADMIN；不会解析普通 `authRef` 以证明存在或租户归属。 |
| 鉴权归一 | `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java:242`；`admin-common/src/main/java/cn/agentplatform/common/auth/AuthScheme.java:87` | 缺声明且 legacy-bearer=true 时补 BEARER；false 时拒绝。API_KEY 要求 HTTP+非空凭据，NONE+凭据拒绝。该存量兼容默认无需修改。 |
| 来源校验（额外阻断） | `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java:730` | 当前只接受 OPENAPI 的五键来源形状。导入器实际生成 ONTOLOGY 的六键来源形状，真实 `create` 因而拒绝 `INVALID_IMPORT_SOURCE`。现有导入测试 mock 目录层，没有覆盖此失败。 |
| 读回/编辑 | `SkillCatalog.java:177`、`:206`、`:222`、`:326`；`skill/api/SkillController.java:40` | JSONB 契约完整读回；旧编辑器省略 authScheme 时 save 会补回旧值。公共管理读回将 authRef 显示为 authConfigured，保留 authScheme。 |
| 发布 | `SkillCatalog.java:828` | 读取已保存契约并检查真实连接、选型的 sha/semanticDigest 证据；发布只改变状态/指针，不改 authScheme/authRef，也不重新按 legacy 开关解释。不能用手写假通过报告声称真实治理链已验。 |
| 执行 | `skill/application/SkillExecution.java:68`、`skill/infra/SkillAdapters.java:529` | 执行读取精确已发布/归档版本（或受控草稿），同步 HTTP 在出网前调用共享 `AuthScheme.headers`。API_KEY 只生成 X-API-Key，不生成 Authorization。异步 HTTP 同样复用该方法，但本卡导入仅支持 HTTP/SYNC。 |
| 凭据规则 | `admin-common/src/main/java/cn/agentplatform/common/SecretVault.java:83` | 仅 vault UUID 或 env:AGENT_SECRET_*；env 要求同进程 AGENT_SECRET_BINDINGS 对已验 tenantId 的绑定。env:ONTO_* 不合法。租户越权为 403 SECRET_SCOPE_DENIED，不可吞成普通配置错误。 |

额外阻断已向主代理报告。只有两文件白名单时，无法如实完成“真实导入→读回→发布后出站”验收；不能删除/伪装 ONTOLOGY importSource 绕过保存校验。建议由主代理先登记 `SkillCatalog.java` 仅来源校验分支的精确扩展；分页、非 HTTP 与投影映射仍全部留在 EX-04。

## 2. 最小修改建议（尚未实施）

1. 本体导入缺省明确选 API_KEY；调用者若显式提供 authScheme，先用现有 `AuthScheme.parse`/`checkCombination` 校验，仅接受 API_KEY。显式 NONE+凭据保留共享冲突码；显式 BEARER、未知值、空声明不能静默丢弃后强改 API_KEY。无声明可以按本体消费协议生成 API_KEY，不改变手工 HTTP 默认。
2. 整批创建前要求非空引用，并先 ADMIN 校验，再按 `OpenApiImports.java:138` 已有模式 `SecretVault.resolve`，证明格式、存在性与租户归属。保留 PlatformException（尤其 403）；非平台运行异常转成不含引用/密钥/文件路径的 400 导入错误。只把 opaque secret_ref 写入契约，不保存解析出的明文。
3. `toSkillBody` 显式写 `authScheme=API_KEY` 和已校验 authRef；更新“authRef 可选、由 catalog 证明 vault”这两处不准确注释，解释本体消费必须凭据及不得静默降级的不变量。
4. 若来源校验被批准纳入 EX-03，只新增严格 ONTOLOGY 形状分支；保持原 OPENAPI 分支不变，不接受额外任意执行字段，不引入新通用框架或依赖。

## 3. 最小有意义红测设计（尚未执行）

| 测试 | 旧实现应暴露的失败 | 关键断言 |
| --- | --- | --- |
| 缺省声明的本体导入显式 API_KEY | 捕获交给目录层的 body 没有 authScheme | API_KEY 和同一合法引用明确存在，且无明文；不是只断言 mock 被调用。 |
| 真实 normalize 且 legacy-bearer=false | 先出现 REQUIRED；补声明后还将暴露来源阻断 | 不 mock normalize；完整 ONTOLOGY 来源保持，最终 API_KEY。分阶段保留两种原失败，不能为绕过来源删除字段。 |
| 缺失/空白 authRef | 旧实现继续创建无凭据草稿 | 400 + CODE_CREDENTIAL_MISSING，catalog 零创建。 |
| NONE+有效凭据 | 旧实现丢弃 NONE 声明并继续创建 | 400 + CODE_CONFLICT，零创建。另以 BEARER/BASIC 验证显式声明不被忽略。 |
| 非法/不存在/其它 tenant 引用 | 旧实现只透传引用 | 非法/不可用引用 400、其它 tenant 403 SECRET_SCOPE_DENIED，全部零创建，不含引用或密钥值。 |
| 非 ADMIN 合法引用 | 旧 mock 测试不能证明管理边界 | 403，且无创建；不能先解析其它租户秘密再检查角色。 |
| 导入→PG 读回→发布→实际 HTTP 头 | 旧实现先被来源校验阻断；修复该前置后仍会暴露 Bearer | 真实 catalog/PG JSONB 读回声明；对同一契约摘要走发布门；发布后从 PG 再读，交真实 SkillAdapters，环回 HttpServer 收到精确 X-API-Key 且无 Authorization。不得在请求前手工补 authScheme/改 authRef。 |

测试仍放在既有 `OntologyManifestImportsTest.java`。临时 vault 目录/独立测试主密钥按既有 `AuthSchemeOutboundTest` 模式，finally 恢复系统属性并只清理本测试创建的临时目录。PG 用例须先获主代理测试窗与专用测试库准入，使用随机 tenant/code，不 drop/truncate 已有共享 schema。正式环境 env 规则只复用，不为测试放宽。

真实 header 用例明确标为本机测试端点证据；本卡不因此宣称 EX-07 的真实 OR J4 或真实模型选型通过。若只用目录层测试夹具装入同摘要门禁记录，报告必须说明它仅证明存储/发布保持契约，不能称真实连接/模型验收通过。优先复用实际连接治理入口产出的可核对证据，避免新增假 PASS 夹具。

既有结构失败用例在添加鉴权前置后需继续携带有效引用，或将鉴权校验放在结构转换后、写入前；否则只会提前因缺凭据失败，掩盖原 preview/路由/类型/超时/重复编码断言。整批非法零创建语义必须保留。

## 4. 比较基线与后续执行门

EX-00 [比较基线](../2026-10-28-EX-00/comparison-baseline.json) 指定 skill-registry 使用 `baseline/20261028-164553-272/method-summary`。该模块为 70/70 类、664 条：548 passed / 6 failure / 106 error / 4 skipped。原非通过分属 AlgorithmExecutionPostgresTest、AsyncHttpExecutionTest、CallbackHttpTest、ReconciliationProofTest、SkillCallbackWakeTest、AlgorithmMigrationPostgresTest、McpManagementTest 与 LocalSpikeLifecycleLiveTest，逐方法原因按 EX-00 保留并由 EX-27 承接，不在本卡泛化修复。

已审基线中本卡三个入口均 passed：OntologyManifestImportsTest 9、SkillAuthSchemeRegistrationTest 7、AuthSchemeOutboundTest 5，共 21；这只证明原覆盖，不证明导入链已闭环。

后续在主代理明确释放源写窗/测试窗后：先只加失败测试并归档原实现的非通过；再最小产品修复，运行手册 `Test-AP skill-registry 'OntologyManifestImportsTest,SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest'`。真实 PG/HTTP 证据与定向分别归档。当前源 reactor 依赖安装后，对 skill-registry 无 `-am` 跑完整模块并与原方法集合逐项比较；新增 failure/error/坏类/skipped/未执行均为 0 才可放行，不能用总数相同或 failure.ignore 的退出 0 替代。

最终 README 再填写实际命令、退出码、源码指纹、红绿数字、原场景前后、模块差异、未覆盖项、独立评审及回滚；此设计稿不能代替这些实际证据。
