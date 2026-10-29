# EX-27 V222 Adjacent24 独立复核与历史 validate 候选

审阅者：`docs_reader_check`；2026-10-29。只读原 XML/JSON/CSV、源和本地JAR类元数据，新增仅本报告；未运行产品、Java、Maven、Node、PG、HTTP或服务，未改源/断言/三入口/原件/HEAD/index。后半节只是候选方案，未写helper、未连接数据库。

## 实际 Adjacent24 结论

**独立复核通过：5 XML / 24 个精确方法全部PASS，native0 / collector0 / 0F / 0E / 0S。** 24恰为原EX04相邻23加同一个 `EngineMigrationsTest#preservesExecutedV222MigrationResourceBytes`；没有更换或删减原方法。此为资源、内存及自有loopback夹具相邻回归，不是PG历史validate、模块933或整卡闭环。

私密run `.local/ex27-v222-recovery/v222-adjacent-24-20261029-01` 的固定原件：

| 原件 | SHA-256 |
| --- | --- |
| 实际collector / `run-regression.ps1` | `82589cd43d0472675759c21c97e2adee35053fb76cf8e1a4ac038129d6a44937` |
| 实际approved manifest / `regression-manifest.json` | `27fd43ecc5c433ab857edb1a42c86de70aff191c843bfa5ec18d5381e812559a` |
| `summary.json` | `dc32403b21c1e1f68c15366db43ec725bc301ca4cf669dbef999c2d65f358c56` |
| `native-exit.json` / native0 | `7c795dba6a6590bbfe77958f27235fd38b18c81ce802a514da4b8eaeba9d9a84` |
| `collector-exit.json` / collector0 | `dc209d35982c6f67357441f57152dc411db9a01607255ce6984bf6d462a62e50` |
| `xml-manifest.json` | `9d6dc18dff9929aa06da3b92e26071825d59ad59288be134439e07d9c27d7f78` |
| `methods.csv` | `80f7724a6d4e65e0dcaaed1eb35b7f04103a16faec6283f1a25540352def4dc8` |

逐归档 XML 文件集、字节数、SHA及suite/testcase计数核验：

| 类名（`cn.agentplatform.engine.`之后） | 方法数 | XML SHA-256 |
| --- | --- | --- |
| `infra.EngineMigrationsTest` | 4 | `1fe2c200b4b3d38cc8de10f8022c12cd8a9dda9717057435c450f672a28e6f84` |
| `domain.ContextPackTextTest` | 1 | `a95ad4f4fcd166e7890cbac8f9d0a48abd4e9ceebd7f5908945e66f165783403` |
| `infra.OntologyContextPacksTest` | 4 | `432d3e5be0a61d8e39cbf5836f9b9d2197ace482eeabc457120f808cd84af205` |
| `application.RunServiceContextPackTest` | 9 | `55313628ff021748855b7468d270eaec45f3cb7678a6c70a1ba72781e4d10706` |
| `infra.OntologyBindingResolutionTest` | 6 | `4b8743a322e42ec721806b523a6133bd77d5a16dba448b6389676e08b348ead2` |

独立解析原 `EX04/collector/runs/full-engine-20261029-1124/xml` 同五类的23方法，恰原3/1/4/9/6，全部原PASS；当前恰这23身份加唯一新方法，无重复/缺失/额外方法。CSV中原23的classname/method/display_name/suite_path/record_kind/status/exception_type/cause_types/first_project_frame/message_sha256/detail_sha256完整语义字段逐项不变。当前24均具名，无类级事件；class-level-events、method-status-delta、strict-reason-delta、xml-issues全为空，没有原因归一化。

source-before/source-after逐字等于真实Green source-after，SHA `1ca4a1ff19a5a8e6141c0d92bf992354aa26d915f8bada8ffe9be4ec2f42e337`，306个精确路径/SHA与当前源相符，Test66EF及原断言不变。installed-before/after逐字等于Green六依赖inventory（SHA `11236879cbfb1db4ff05b5ff4986a454ac55f3c9f1a04b5b8a36657b3784ccae`），当前六实物及engine JAR3D2B也逐hash相符。Green F4D0 receipt和全部9份引用原件、pins/tools均独立匹配。

实际命令仅 `-pl engine-runtime test` 加上述精确五类，无clean/install/package。Xmx1024、timeout900、unixdomain临时目录沿原EX04预算；default fork/reuse/order不改、未加registry properties。Adjacent使用failure.ignore=false且缺类拒绝，未把原全量failure.ignore=true当成定向通过。manifest仅sealedModes=Adjacent，没有借本轮运行Full。

Adjacent的 `storageGate=true` 是本模式的分支门值；只有Full分支调用Check-Storage。本轮没有生成PG/Redis/MinIO fresh存储证明，不能把此字段或24P改称历史validate通过。summary明确wholeCardClosed=false，原932/未来933的失败及未执行边界也未被本轮覆盖。

## 历史 validate：仅候选路径与最小方案

root已确认现有历史reader就是已实际执行的 `v222-source-recovery/run-resource-green.ps1` 中 `Read-Pg`/`Check-HistoricalStorage`，不存在另一个独立fresh reader。候选复用其固定 **agent_platform_engine_test / engine / PG25432** 身份、owners/session、24SQL+原SCHEMA、column、527E与前后字节门；不复用EngineTestDatabase.url()，该helper可能创建库。本轮不因此准入其它历史库或原14库范围。

建议后续由root先登记以下准确新文件；本报告没有创建它们：

- `docs/evidence/2026-10-28-EX-27/v222-source-recovery/V222HistoryValidate.java`：单main证据launcher，不是产品/test源，不启Spring，不调用EngineApplication或任何测试数据库helper。
- `docs/evidence/2026-10-28-EX-27/v222-source-recovery/run-history-validate.ps1`：PS5受限采集入口，复用上述只读门、秘密输入与原生退出码/有限进程等待；只编译本证据main到唯一`.local/<RunId>`，不Maven重建或替换产品。
- `docs/evidence/2026-10-28-EX-27/v222-source-recovery/history-validate-manifest.json`：冻结source306、Test66EF、Green F4D0、新JAR3D2B、24资源/catalog/CRC、依赖classpath/JAR SHA、527E及精确库/工具/预算。新manifest不得改旧Green/Adjacent原件。

classpath采用root已收敛的 **当前Green target/classes + fresh XML实际依赖classpath + 独立证据class输出目录**，排除所有test-classes、测试artifact及旧engine包；不使用Boot launcher。运行前再核target的24资源与3D2B包逐字相同，并核所有其余classpath根无额外`db/migration`资源/回调；任一重复或来源不明停止。凭据仅从本仓受限配置在内存读取并经stdin/受限文件传给main，不放CLI、manifest或公开log。

当前依赖已静态实证：Green新包含Flyway core及PostgreSQL插件 **11.7.2**、PG JDBC **42.7.5**及该fresh XML中的现有Jackson/日志等依赖，不新下载或引入依赖解析框架。纯Python读取当前Flyway class元数据确认真实API：`validateWithResult()`返回ValidateResult，公开字段有validationSuccessful/validateCount/invalidMigrations/errorDetails；FluentConfiguration有createSchemas/cleanDisabled/skipDefaultCallbacks/ignoreMigrationPatterns(String[])/validateMigrationNaming。core JAR SHA `ff01fab3bcfd79e9345df191ecf82cbe1f9f65a757266158bd691564b1c1282c`；此为静态API核查，不是Java执行或数据库验证。

候选main只允许固定engine schema、`classpath:db/migration`、createSchemas(false)、cleanDisabled(true)、skipDefaultCallbacks(true)及明确空Callback数组；显式空String[] ignoreMigrationPatterns并validateMigrationNaming(true)，不依赖默认豁免，不设置版本/校验和忽略。仅调用 **validateWithResult()**，不提供migrate/repair/clean/baseline或SQL执行参数。DataSource的每个连接须固定readOnly，并通过PG连接选项保证default_transaction_read_only=on、statement_timeout=5s、有限connect/socket timeout；验证前后读回transaction_read_only/statement_timeout，不能只设一个未验证的Java标志。

成功门应同时包含validationSuccessful=true、invalidMigrations为空、errorDetails为空、当前24资源/catalog来源完整、前后同一PG身份/24SQL+原SCHEMA/column精确不变及所有source/deps/JAR指纹不变。validateCount另记实际值，不用这个单计数代替完整script/version/checksum门。异常/超时或任何不符原样保留私密原件，只公开错误码/数量/指纹；不重签成功、不迁移、不换库。总等待应有限，root独占实际窗口准入后才可运行。

以上只是精确候选建议；未签收尚未编写的helper/manifest或任何历史validate结果。新空库正常migrate/重复启动仍须EX-06另行登记窗口，24P、资源24和只读history三者均不能代替该验收。
