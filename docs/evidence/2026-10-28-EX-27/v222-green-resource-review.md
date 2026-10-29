# EX-27 V222 Green4 与当前源包资源独立复核

审阅者：`docs_reader_check`；2026-10-29。仅只读本仓源、JSON/XML/ZIP、Git及保存原件，新增仅本报告；未运行任何 Java/Maven/Node/PG/HTTP/服务，未改源、Test、manifest、collector、raw run、HEAD 或 index，未读取凭据值。

## 结论

**Green4 / 当前源包资源阶段通过独立复核，无阻断。** `v222-resource-green-20261029-01` 为 build0 / test0 / collector0，精确4方法全部PASS、0E/0S。Test66EF与RED完全相同，当前306源恰为已批准RED305原字节加唯一SQL419，src/target/新JAR的24份迁移逐字相同、版本200–223唯一且各SHA/CRC符合固定catalog。

本报告中的 **24是迁移资源数，不是24项相邻测试通过**。相邻24、模块933相对回归、历史库Flyway validate、独占新空库migrate/重复正常启动均尚未据本报告签收；也未启动AP正式服务或执行DDL。EX-27、EX-06、All/J1–J9/S1整卡或场景不因此完成。

原RED审阅 `v222-red-restore-review.md` SHA **`01f64e222eb943c9a0d6056796de875cf4803cc018bb0eca0b9eb72f618ccb99`** 保持；不把原RED的资源缺失1F倒写为PASS，也不改dev失败4AD、旧BBAD或fresh527E历史证明。

## 固定实际运行与断言

私密原件目录 `.local/ex27-v222-recovery/v222-resource-green-20261029-01`：

| 原件 | SHA-256 |
| --- | --- |
| 实际 collector / `run-resource-green.ps1` | `5c97a30b5b139e572f0dfc7a5ef16e23da3383ab37a0471eb61552fab0fc6680` |
| 实际 approved manifest / `manifest-green.json` | `191f6f78792b669397781ef91706edb5b539b9163b1a16330bd37699fc8d3aee` |
| `summary.json` | `e25759959aabd91e56579b69ed9107a20e2ad9dd93c0955f2ee89652a16cc864` |
| `methods.xml` | `f11b5707513ff806807a56c8e0eb3e304868d440690456c41cf4be772756c185` |
| `methods.json` | `c5ffd8ace41489cdd980c64c01fb19a7f74fd736660819c6f66b20372960329b` |
| `build-native.json` / build0 | `991b48523603c23cf25db604ac6204a016633a837f952448c35c3f7aff1d4937` |
| `test-native.json` / test0 | `00b99ee85628477bab251146f5edeb3ad71e3c67c1647908264a5f7fd3820d13` |
| `collector-exit.json` / collector0 | `deb83da44640e52871e81c80392d6705bb0506f4c7d4868de56f54a54eac5d43` |
| `green-dependency-receipt.json` | `f4d04843cb224f86f1cb5ce7e093e9bd23f8e02c9f3917a025f2355a63770025` |

原XML suite恰4/0F/0E/0S；逐testcase身份与RED、manifest及methods.json精确相同且唯一：原3个版本枚举/区间计数/文件名方法，加 `preservesExecutedV222MigrationResourceBytes`。全部无failure/error/skipped节点，确为4P。新方法已越过RED的resource-null断言，完成286字节、SHA419C和versions contains222断言；没有替换或跳过该方法。

当前 `EngineMigrationsTest.java` 仍 SHA `66ef434ab8abb818599c69c9e0bf1d80c02087cfb7cd05cfd8ab577f2ff6109f`。旧3方法/断言及helper不变，其原字节复构已由RED报告核实；本次未改任何测试以适应恢复结果。

Green实际build/test command的参数数组与RED逐项相同：仅父/admin-common/engine-runtime最小闭包 `-pl engine-runtime -am clean install -DskipTests`，另行精确EngineMigrationsTest，`maven.test.failure.ignore=false`、缺类拒绝、timeout900/Xmx1024不变。三个实际build.directory原始UTF-8输出与RED逐字相同，均native0，仍只本仓三个target。没有用旧target残留或扩大测试预算取得绿。

静态逐函数独立比较，Green与E8 RED的14共用函数 Hash/Fact/Pin/Safe-Directory/Clean-Tree-Safe/Sources/Same/Stable/Read-Pg/Check-HistoricalStorage/Check-NoFormalServices/Archive-Sql/Maven/Installed **全部逐字相同**。新增只读 `Check-RedProvenance`、固定catalog CRC计算及Green资源门取代RED缺失资源门；没有扩大数据库或进程范围。Green receipt只在完整gate成立后写入，没有预先返回成功。

## RED来源与306源精确差异

Green准入绑定原RED E315/E8/DB953、summary EB33、source-after1B0E、installed-after2E03和红包EF26。原RED确为build0/test1/collector0、原3P/精确V222_RESOURCE_REQUIRED新1F，所有原件保留。`red-prerequisite-receipt.json` SHA `92a0840cdeaee74a15ace1cb73186d9a10528e7296d7410eca8c8410f4e9b832` 与这些原件及唯一加SQL事实相符。

Green source-before/source-after字节相同，SHA **`1ca4a1ff19a5a8e6141c0d92bf992354aa26d915f8bada8ffe9be4ec2f42e337`**。按精确路径→SHA独立比较：完整306路径无重/漏，RED原305路径的每个SHA保持，唯一增加 `engine-runtime/src/main/resources/db/migration/V222__run_context_pack.sql` SHA419C。当前父POM+admin-common/src+engine-runtime/src的完整文件集合与该306 inventory相同，逐文件hash也相符，没有其它修改源/新增源。

## 资源、CRC与新包来源

独立读取当前源码24 SQL、target/classes24 SQL及新正式JAR的ZIP中央目录/实际entry，三者文件名与完整字节逐一相同。名称/版本恰V200–V223，无重复entry/重复版本；V222在包内 `BOOT-INF/classes/db/migration/V222__run_context_pack.sql` 恰1个。

- 新正式JAR整体 SHA **`3d2bd7b517a5115725ce9ace62a10cc7a2ae2816458afaefaa909b050b2d56c7`**；这与旧整体包不同，是本轮clean当前源新产物，不要求与旧整体包SHA相等。
- V222三处均286字节，逐字等于受保护归档SQL及旧两包entry，SHA **`419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`**；UTF-8逐行去换行的signed CRC32为 **701700386**。
- 对全部24源码独立重算SHA及Flyway signed CRC32，各自与固定catalog及原history逐script/version/checksum匹配；因target/JAR字节完全相同，其各自SHA/CRC亦相同。没有只看最大版本或单个V222。
- before-test/after-test built-resource原件字节相同，SHA **`85b5820a1c6c63e40f30508d7934515cb18b7815908d4fe632e6d541c9bee95c`**，清单与本审阅实读24资源逐SHA相同；v222Present/fixedCatalogCrcValidated=true、oldEngineJarAddedByCollector=false。

## 历史、依赖与备份链

Green before-clean/after-test history与原RED after-test字节相同，SHA **`5565b3e58007f2502a28477ecc7dddd764888e413328cad7507d5c74bccdf49f`**；对应fresh527E的24成功SQL、唯一原SCHEMA、原rank顺序、owner/session/column及PG身份。service before-resolve/before-clean/after-test亦与RED字节相同，SHA `fb7133f520b1a2e0800c4a013a4a77f195170ca550f8581f7d5bd39ba362c5a8`；记录无本仓正式服务匹配、未启停进程、不枚举其它工程。summary保留pgMetadataReadOnly=true、businessValuesRead/httpCalled/formalServicesMutated/wholeCardClosed=false。本审阅只核原件，没有重新检查活进程或连接PG。

新六份installed artifact的before-test/after-test inventory逐字相同，SHA **`11236879cbfb1db4ff05b5ff4986a454ac55f3c9f1a04b5b8a36657b3784ccae`**；当前六份文件逐hash匹配，receipt内六条也精确相同。允许真实源码重建生成新的common/engine JAR，没有把旧依赖SHA强行套在新构建上。

保护链仍完整：原归档正式JAR76AB/`.jar.original`0D5F/286SQL419C均原SHA不变；RED前 `installed-common-before` 的旧common三件仍匹配原1B0D/1C912/B5BA；Green的 `installed-red-before` 全六件逐hash匹配原RED installed-after。Green另保留 `backend-before.jar`，独立核得 **EF26E325…9D564**，即原23资源红包整体SHA。不是仅留指纹而已，没有覆盖/删除原包或原common前态，也没有自动恢复依赖掩盖新构建。

## F4D0 receipt与后续边界

receipt F4D0 的实际runId、RED来源runId、HEAD、191F manifest、5C97 runner、build/test0、4/4P、sourceCount306均与实际原件相符；其 sourceAfter/installedAfter/builtResources/historyAfter/approvedHistory527E/summary 各SHA均逐文件重算匹配。engineJar3D2B、sqlResourceCount24、V222419C与实际包一致。额外重hash冻结pins/tools及当前源、依赖，亦与记录相符；只读取.env字节hash，未读取凭据。

因此可接受本次 **同断言RED→原字节恢复→clean Green4与24资源一致** 子阶段。24/933实际回归、真实历史validate和独占新库正常migrate/重复启动应各有后续独立原件与准入；本报告不以resource单测或元数据只读结果代替这些运行，也不关闭任何整卡。
