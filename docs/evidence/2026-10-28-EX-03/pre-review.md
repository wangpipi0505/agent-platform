# EX-03 红候选与设计独立预审

- 作者：本卡实施代理（本文不代填未核实的任务标识）；审阅者：独立代理 `/root/ex02_independent_review`，未参与测试或产品实现。
- 范围：只审当前红测试、设计与脱敏证据；不是产品实现终审、绿测或 DoD④完成记录。未运行 Maven/Java/PG/HTTP，未读取真实配置、凭据或原始 XML/日志正文；未改产品、测试、index 或三主入口。
- 候选：AP HEAD `0cc967278007343bd5ab09faf9953d86cd573bf8`。`OntologyManifestImportsTest.java` SHA256 `ACA2886F6AFDD833821B92BA27E602908C26408FB9D0C524D80FA98FAF556021`；产品 Importer SHA256 `E0C4A725533CBE5C84962D2DDA2A8CB408344960DB389BE2542809F85942BF5D`、Catalog SHA256 `CC1DEBCBC26EF5DE1B48DE74E3C627A671B605B8BDC5F1593D7DBD5C151B4EC1`，均仍为未修复产品。
- 已读：两仓约束与三主入口 EX-03、测试完整源码、Importer/Catalog/治理发布及共享鉴权路径、OR 当前 formalize/validateImport、既有手工 HTTP 与 OPENAPI 测试、[真红 README](README.md)、[方法清单](red/20261028-222522-749/method-status.csv)、[专库前提](red/20261028-222522-749/database-precondition.json)和命令/源码指纹。

当前设计能在前置修复后证明：真实 Catalog 创建并写 PG、内部读回 API_KEY/opaque authRef/ONTOLOGY 来源、未具备连接与选型报告时发布被拒绝；真实 SkillExecution/HTTP 调试事实被治理代码读取，选型报告由真实治理代码计算落库；发布前后 definition/sha 相同，随后从 PG 读取已发布契约并经真实适配器发送 HTTP，最终两次请求均只有精确 `X-API-Key`、没有 `Authorization`。测试没有在出站前手工补 scheme/ref，也没有直接写假通过报告或发布状态。

引擎 claim/authority/fact 与模型选型响应是显式协议替身，Actor 也是测试 SecurityContext 身份。它们使本测试验证平台存储、治理门和适配器的确定性路径，不证明真实 JWT 入口、真实引擎调度、真实模型选型、OR 服务权限或 EX-07/J4。源码注释和 README 对这些边界的表述诚实。

| ID/级别 | 定位（当前红候选） | 必要缺口与影响 | 最小处置与复核条件 |
| --- | --- | --- | --- |
| PRE-03-01 / P2 测试充分性 | `OntologyManifestImportsTest.java:319–327` | “malformed coordinates”仅覆盖空串和额外键。未来实现只检查精确 keySet＋非空就能通过，却不能证明非空错误摘要/版本坐标被拒绝。当前该方法通过只因原 Catalog 不接受任何 ONTOLOGY 来源，不能算新分支已验。 | 在既定六字段严格形状下补缺字段、错误 kind、非空非法 releaseId、非空错误 contentDigest/长度等反例，保留合法正例。OR `ContractValidator.validateImport:23–26`明确摘要为64位小写hex；`PublicationService:513`按 UUID 使用 preReleaseId，formalize把它写入来源。`version`当前只是非空字符串，不新增臆测版本语法。不得通过删除来源或改成 OPENAPI 绕过。 |
| PRE-03-02 / P2 校验顺序 | `OntologyManifestImportsTest.java:301–304` | 非 ADMIN 用的是合法自有凭据，且只断言403；即使先解析凭据后检查角色也会通过，无法证明设计要求的先 ADMIN 后凭据解析。 | 增非 ADMIN＋不可解析引用，明确断言角色拒绝码 `SYS-APP-4031`及 catalog 零交互；保留合法引用的非 ADMIN 负例和跨租户 ADMIN 的 `SECRET_SCOPE_DENIED`。不放宽或替换共享凭据规则。 |
| PRE-03-03 / P1 复跑准入 | `OntologyManifestImportsTest.java:334–340` | 测试自身只按 datname 判存在，缺失便自动建库，随后直接 Flyway。现有红轮外层 runner 已核归属并创建/comment，不能称本轮越界；但主卡三类命令及后续完整模块直跑可绕过该外层核验。 | 删除测试内自动建库。测试入口查询并精确核对固定库 owner=`agent_v12`及 comment=`agent-platform EX-03 2026-10-28 ontology import isolated test database`；缺库或不符明确失败，不能 skip。新建库继续由已核准的 runner 在统一测试窗内负责，不 drop/truncate 或接管不明库。 |

主代理已认可上述三项；此处保留 v1 预审时点，后续闭环状态见末节。v1 当时产品两文件仍冻结至 EX-01 实 JAR 阶段结束，不应为让测试通过修改无关产品规则。

红证据为18方法=9PASS/7FAIL/2ERROR、Maven实际退出1，不是编译/夹具失败。两个 ERROR 都停在目标 `SKILL_AUTH_SCHEME_REQUIRED`；来源正向、治理、发布和最终 HTTP 断言尚未走到。原9方法均保留；其中原完整映射方法新增 API_KEY 断言而红，其余原8通过，不能把它描述成既有基线回归。结构负例现在带合法临时引用，可继续检验原 preview/路由/类型/超时/重复编码/整批零创建场景。

后续绿证据还须包含以下兼容范围：

- 主卡指定 `OntologyManifestImportsTest,SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest` 全部当次执行且无新增非通过。后两类现有7＋5方法分别验证手工 HTTP 的 legacy 开关归一化和旧存储契约的真实 Bearer 出站，必须保留，不能把所有 HTTP 改成 API_KEY。
- 新 Catalog 分支只接纳 ONTOLOGY 六字段。既有 OPENAPI 五字段的严格 shape/digest、来源不可变、httpMapping 保存规则保持；完整 skill 回归应包含原 `OpenApiImportsTest`、`OpenApiImportPersistenceTest` 等新鲜报告及逐方法对照，不以本卡 mock 转换测试代替。
- 真实 PG 正向方法不跳过，连接/选型/发布门与最终真实 HTTP 头全部实际到达；原缺 scheme 红、补 scheme 后的来源前置阻断及最终绿如实记录，不根据测试名称提前宣布链路通过。
- 全模块按 EX-00 方法身份比较，保留原664项的状态与归因，逐项列新增方法、坏类、跳过/未执行，不能只看合计或 Maven0。

预审结论：红候选设计方向可继续；三项必要缺口补齐后再审实现与完整证据。当前无最终通过结论，不关闭 EX-03、EX-07 或来源/鉴权相关主项。

## v2 测试补强与产品实现静态复核（尚未终审）

本次只读复核的文件 SHA256：

| 文件 | SHA256 |
| --- | --- |
| OntologyManifestImports.java | `41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5` |
| SkillCatalog.java | `7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03` |
| OntologyManifestImportsTest.java | `4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF` |

三份当前文件摘要均与 [implementation-candidate.md](implementation-candidate.md) 一致，三文件 `git diff --check` 退出0。未编译或执行修复后代码。

- PRE-03-01 静态关闭：测试325–355现在覆盖六字段缺失/空白、额外键、错误 kind（含伪装 OPENAPI）、非空坏 UUID、摘要非小写hex及63/65位长度；314–321保留真实 normalize 的合法来源/API_KEY正例。产品 Catalog 只新增 ONTOLOGY 分支，精确六键、值均为非空字符串、UUID可解析、64位小写hex；version只要求非空，没有添加臆测语法。旧 OPENAPI 五键/digest/text 分支逐条件保持。
- PRE-03-02 静态关闭：测试301–310同时使用合法自有引用与不可解析引用，并明确断言 `SYS-APP-4031`。Importer 中 ADMIN 检查先于 `SecretVault.resolve`；跨租户 `PlatformException` 原样抛出，其他解析运行异常变成固定400文案，不附引用、路径或密钥值。
- PRE-03-03 静态关闭：测试360–376不再自动建库，先精确查询 owner/comment，缺库或不匹配断言失败，之后才连接专库/Flyway。v2当次 [数据库前提](red/20261028-230240-211/database-precondition.json)为已有库、owner/comment匹配、未创建或清理。

Importer 缺省 API_KEY；显式声明交共享 `AuthScheme.parse/checkCombination`，NONE/BEARER冲突与未知值拒绝，没有丢声明后静默强改。保存的只有原 opaque authRef 和显式 API_KEY，解析出的明文没有放入契约。共享 AuthScheme/SecretVault、手工 HTTP 的 Bearer 默认与关闭 legacy 后拒绝缺声明的行为未改。

执行顺序曾按派发消息中的箭头提出核对，主代理随后按主卡纠正口径：主卡硬门是“凭据在全部 create 前校验，ADMIN 先于 SecretVault”，没有规定所有纯结构转换必须早于凭据解析。当前顺序为顶层数组界限→scheme→ADMIN→SecretVault→全批结构转换/重复编码检查→逐个 create；任何结构失败仍在全部创建前，原结构负例用合法引用保持覆盖。该顺序未发现具体安全或契约损害，撤回将其作为必要重排的判断，不为文字箭头增加无价值重构。

v2 [真红记录](red/20261028-230240-211/command.json)仍为原产品18方法9PASS/7FAIL/2ERROR、Maven native exit1。审阅者独立比较v1/v2的 method/origin/status，18→18、差异0。两条 ERROR 仍只证明缺 scheme 的旧拒绝点，不证明新来源正向、治理发布和真实 HTTP 头已经通过。

本轮静态结论：上述最小实现候选未发现确定阻断，可按既定测试窗进入三类定向与真实 PG/header 正例验证。所有绿测、完整模块逐方法差异、旧 OPENAPI/手工 Bearer 的实际回归、最终候选冻结与独立终审仍待完成；本预审不替代它们。本轮只更新本预审文件，产品/测试/index/三主入口只读，未运行 Java/Maven/PG/HTTP。

## 首次 Target 的特定协议 ack 补齐预审

[target-20261028-232734-622](after/target-20261028-232734-622/summary.json) 实际为30项29PASS/1ERROR、native exit1，原21方法全通过、新增9方法齐、sourceDrift=0。唯一非通过为 PG 闭环方法；停止点是生产 `SkillExecution:311–315` 调用 `/submitted` 后读取 `submitted=true`，原 fixture 对该准确URL漏桩，Mockito返回null导致NPE。该行早于真实适配器HTTP调用，不能据本轮宣称连接、选型、发布或最终header断言已经到达。

主代理仅授权补本测试 `executeFixture` 的准确 ack，不改产品门。当前测试 SHA256 `97D0A6B1B48ECD833568C9E3F584303FEEF6D2A094BDAE6979AEC36353432051`；独立在内存移除新增的1行注释和1行 `/submitted` 桩后，重构得到原v2完整SHA `4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF`，确认只有这两行差异。两个产品SHA仍为上节候选，测试 `git diff --check` 退出0。

窄读本卡 readonly HTTP/SYNC 首调分支后确认：GET registration与POST claim已有精确URL和完整归属字段；新补的 `endpoint + "/submitted"` 返回 `{"submitted":true}` 与现有 `SkillExecutionTest:162–164` 的协议一致；最终 `/fact` 在 `deliver:1473–1481` 读取 `accepted`，fixture原已按该精确URL返回 `accepted=true`。`connection(controlledCallId)`读取PG真实非mock DEBUG事实，模型选型仍只用原特定 `/internal/model/chat` 协议替身。未发现该路径还缺其它必要特定ack，不建议增加通配成功响应或default answer。

本次只确认上述最小测试前提修正可进入新Target；没有运行修正后的测试，也没有宣称其通过。真实PG→治理→发布→HTTP的证明范围与协议桩限制继续沿用前文。本审阅者仅追加本预审文件，未改测试、产品、index或主入口，未运行 Maven/Java/PG/HTTP。
