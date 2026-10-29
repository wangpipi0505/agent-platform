# EX-27 V222 / cache UTF-8 / SSE await 四源子批提交候选

2026-10-29，准备者 `docs_reader_check`。此为**已封版、未stage、未commit**的有限公开候选，不是整卡关闭或可单独运行的完整证据包。`public-commit-manifest.json` 列出唯一45路径：4源、leaf30、root审阅10及父属性1；最终独立审阅 `v222-sse-full-review.md` 已由root交付实际12,980字节，SHA `383F6F28BDAA096F8C733D5D6CAC3CF1682B18071961706EDBBC0B801E0561F9`；只接受本子批，不关闭整卡。

## 四源与不同执行时点

| 源路径 | 固定字节身份与范围 |
| --- | --- |
| `engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql` | 286字节 / SHA419C…59A90 / CRC701700386；复制本仓受保护旧资源原字节，保留已执行迁移兼容性，不新增独立审计写入或回填。 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineMigrationsTest.java` | SHA66EF…6109F；旧3例原断言不动，追加已执行V222资源字节不变量。 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java` | SHA021629…1747F8；11身份及4秒预算不改，仅把原两个await加强到真实owner1/0，等资源finally后再断言。 |
| `scripts/verify-cache.ps1` | SHA0A2A…90D920；登记JSON显式按UTF8读取：原一行Get-Content增加-Encoding UTF8，不授权启动/停止Redis或改配置。 |

准备父HEAD及这些engine实际窗口的runtimeHead均为 `6ca930d8b9b7ef32c4eb7de1ce88ae87fc75c946`，实际使用工作树源，不能称整棵工作树当时干净/冻结。oldGreen306是RED305加SQL419，执行时SSE测试仍旧3FFC；新Target/Full306仅将其中同一路径换成0216，其余305源不变，不能倒填成旧Green已执行新测试。新包3D2B与F4D0六依赖仍为原Green构建。

当前另外的skill Process两生产源、原三Test及后加McpAdapterTest共六文件、process-files-io契约、MCP候选/patch、PhaseA证据及其它untracked全部排除。MCPTest是在engine Full运行后才apply，不属于该窗口的306源范围；本批不把这些其它改动带入提交，也不清理工作区。

## 实际结果及保留的失败

| 阶段 | 公开记录的实际边界 |
| --- | --- |
| 原dev历史前置 | 4AD原失败保留：21SQL、未查列；不能改成24或迁移dev来凑前提。BBAD仅旧目标来源，fresh527E才是准确engine_test只读前置。 |
| Resource RED | 当前源clean构建0，原3P+新资源缺失1F、test1/collector0，真实V222_RESOURCE_REQUIRED；不是编译错误或伪stub。 |
| Resource Green | 同Test66EF、build0/test0/collector0、4P；src/target/新包24资源逐字/CRC一致。旧包、红包及两轮依赖备份在私密原位保留，不提交二进制。 |
| Adjacent24 | 5XML/24P/native0/collector0，原23+资源1；资源/loopback回归，不冒称PG验证。 |
| Full01 | STORAGE_BEFORE/登记JSON读取编码准备失败，native=null、collector2、未调用测试；原件保留，不算业务红。 |
| 旧Full02 | 933=817P36F80E、2类级事件/14源未执行，ignore采集native0、collector1、原gate=false。SSE原11=10P1F，expected62/actual61不倒写。 |
| history01 | PRIVATE_DIRECTORY ACL拒绝，compile/validate null、collector2，未到JVM/DB；原B9/550C保留。 |
| history02 | v2只换ACL应用入口，compile0/validate0/collector0；25=24SQL+原SCHEMA，2连接前后只读/关闭、history/column不变；不是新空库migrate。 |
| SSE Target | 全类11P/1XML/noignore/native0/collector0；当前306、六依赖、资源、14库/317fixture前后门通过。实际输入331E保存在原run；后封Full清单C5FB不能替换这个历史输入。 |
| 新SSE Full | 933=818P35F80E/0S，2类级事件、14源仍未执行；native0使用ignore采集、collector1，原strict reason gate=false、allTestsGreen=false不改。 |

两份复制到leaf的reason-proof是实际原件字节：旧run02为9D8B…4558，新SSE Full为0E64…9B96。两者均只针对当次186份固定XML、115旧非通过的92完全同hash与23有限identity-token位置/46字段hashpair证明（16 Spring身份、7 testConnection身份），不提供泛化归一化或未来运行豁免。旧run02仅原原因比较技术接受，SSE新增失败使整体仍false；新SSE派生6D89/268E实际native0、adjustedRelativeRegressionGate=true，但原raw gate/collector1/allGreen=false仍并列。**独立最终SSE Full审阅383F已接受本子批及这次有界相对比较；原raw gate和allGreen=false保留，不作为整卡通过。**

历史candidate中的preparedOnly、未运行或尚无实际RunId等字段保留原时间点语义；`public-summary.json` 的执行索引说明哪些同字节后被实际运行。私密归档的旧未执行版本不因现在有新版本通过而被追认成业务红/绿。

## 字节、属性与明确文件集

本批采用response-ownership公开清单同一逻辑：每个非self、非pending条目记录raw SHA、expected clean SHA与Git blob OID；manifest自身不内嵌自SHA/OID，由最终handoff和精确stage后独审另外固定。45是明确名单，不是`git add`目录许可；最终审阅已按真实字节绑定；后续任何文件变化都须重新封版并作精确stage独审。

父 `docs/evidence/2026-10-28-EX-27/.gitattributes` 的原25500字节/C56F…41A1前缀一字不改，只追加本批10个root审阅文件的精确`-text -whitespace`行，排除MCP候选。leaf的`* -text -whitespace`仅用于保留本叶已冻结证据字节；它不授权提交任何未来或未知文件。四源继续既有Git换行规则，不能拿工作区raw SHA冒充clean blob。

不纳入原XML、raw日志、PG查询原件、实际进程记录、JAR/依赖/备份、.env、凭据、其它工作树改动或整目录归档。只保留有限脚本/清单/证据main、独立审阅和安全结果/精确proof，公开Git集合无法脱离私密原件与原环境重放全部历史；任何后续执行仍需原独占窗口、冻结指纹与准确权限。

本准备仅做文件读取/散列、受控属性追加和新公开材料写入；不运行Java/Maven/PG/HTTP/Node/WSL，不改四源、主文档、旧原件或HEAD/index，不stage/commit。EX-27整卡、新空库正常migrate/重复启动及EX-06联验仍未完成。
