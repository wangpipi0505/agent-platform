# EX-04 engine 最终 PG admission 独立补审

- 审阅者：`/root/ex04_pg_scope`，2026-10-29；未参与产品实现。
- 本次仅阅读源码、原 XML、ROOT 已归档只读 PG 结果与最终脚本，并用 Python 对本地字节重算 SHA/CRC；未运行 Java/Maven/PG/HTTP，未改源码、collector、旧报告或 index。唯一新增本报告，不使用 superpower。
- 结论：接受下述精确环境前提进入完整回归；**不提前签署完整回归结果**。原失败方法、类级事件和未执行项仍全部受比较门约束。

## TestInputHttp 专库无 schema 的原前提

独立解析原 EX-36 `after-module-20261028-230322-842/xml/TEST-cn.agentplatform.engine.infra.TestInputHttpTest.xml`：共 9 个具名 testcase，全部 `java.lang.IllegalStateException`/error；无通过、跳过或缺方法。

- 首例 `pagingIsBoundedStableAndDoesNotExposeInputsInMetadata` 的 cause 是 `BeanDefinitionOverrideException`：`PortalRunEventsJoinedEngine$Receiver` 的 `decoder` 与已登记的 `InitialChatAdmissionFixture$Receiver` 同名 bean 冲突。
- 余下 8 例为原同一上下文的 `ApplicationContext failure threshold (1) exceeded`；不能改成 skip，也不能只用首例代表全部 9 个身份。
- 这是 Spring Bean 定义注册阶段冲突，在 Flyway bean 执行之前。`TestInputHttpTest:47–63` 的 DynamicPropertySource 会查/创建精确数据库并设置 datasource；它不自行初始化 engine schema。实际 ROOT admission 记录 `agent_platform_test_input_test` 存在、owner=agent_v12、schemaPresent=false、historyPresent=false，与该原失败前提相容。
- 同样有 input 字样的 `TestInputPromotionPostgresTest` 使用独立 `agent_platform_test_input_promotion_test`，原 baseline 3 个方法均 passed，源码显式 Flyway migrate；它没有得到空 schema 例外。

当前 `pg-preflight.ps1` 仅在精确库名 `agent_platform_test_input_test` 且 DB owner 已验证后，允许 schema 不存在，或 schema 归 agent_v12 且 Flyway history 尚不存在。其他库仍走原归属/历史判定；主库保持只读 catalog 用途。脚本不创建 schema，不调用 Flyway，也不以此证明 9 项功能通过。全回归预期集合仍包含这 9 个 error 身份，最终必须逐项比较类型、cause/首项目栈及其他原基线记录；任何新变化由 ROOT 按事实处置。

对旧 `engine-full-pg-scope.md` 的必要补充：其中“helper 没有默认 JUnit caller”仅指没有调用其 main/fixture 构造器的测试调用链，**不代表其嵌套 `@Configuration` 对 Spring 组件扫描不可见**。原 XML 证明上述两 Receiver 确实被扫描并冲突；它们的建库 main 仍未因此被执行。旧报告不倒改，以此补审说明准确边界。

## V222 唯一 runtime 历史项复核

读取 ROOT 原 `legacy-runtime-history.json`（PS5 `value` 数组及 Count=3 保留）与 `legacy-runtime-proof.json`：三行 database/script/version/type/installedBy/success/checksum 精确对应。按 proof 中记录的 Flyway 11.7.2 算法，对当前本地文件 UTF-8 去首 BOM、逐行去分隔符后计算 CRC32 并转 signed int，独立结果为：

| 控制/已知 runtime 项 | 独立本地 CRC | ROOT PG 历史值 | 结果 |
|---|---:|---:|---|
| V200__engine.sql（当前源码） | -1129590471 | -1129590471 | 相同 |
| V201__engine_tool_approval.sql（当前源码） | 580067347 | 580067347 | 相同 |
| V222__run_context_pack.sql（当前 target 残留） | 701700386 | 701700386 | 相同 |

三文件 SHA 也分别与 proof 相同；V222 仍为原 286 字节、SHA `419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`。本评审者没有独立连接数据库，PG 数值来自 ROOT 已保存的只读查询原件；不把本地重算称为二次 PG 复跑。

最终脚本先固定该 target 路径/SHA，只给 engine schema 增加这个精确脚本名，并额外校验 version222/typeSQL/checksum701700386/installedByAgentV12/successTrue。其余未知 SQL/type 仍拒绝；没有 clean、repair、补源迁移或 DDL。此条件满足上一份 `legacy-runtime-context-review.md` 的静态准入要求，仍不能证明历史 EX-36 当时 artifact 字节，也不能声称 clean 构建已通过。

## 原拒绝记录与尚待实际后置门

ROOT 将初次 V222 拒绝和 TestInput 空 schema 拒绝分别保存在 `inputs/full-engine-candidate/preflight-array-stop/pg-before-known-history-stop.json`、`pg-before-empty-context-schema-stop.json`；collector 数组展开修正的旧脚本/停止记录也单独保留，不覆盖成产品红测。

已归档的 `full-pg-admission.json` 为 passed=true、固定库 14，保留唯一 TestInput 无 schema 事实、V222 精确限定和 cleanBuildVerified=false；`freeze-approved.json` 固定它的 SHA 并明示当前回归上下文。

本次审阅时完整 engine 窗仍在执行，以下不能预签：932 条身份/状态与原原因一致；2 类级事件/14 源未执行；只新增一个由原 timeout fixture 创建的 `timeout_fix_<32hex>` 且 owner 正确；旧动态库 catalog 身份未变；runtime V222 前后 SHA 不变。ROOT 后置只读脚本须补齐，且本次仅核 name/owner 集合，不能扩大为“证明旧库所有业务数据未改变”。最终代码与证据独立终审仍由对应审阅者执行。

## 本次只读对象 SHA-256

下表固定审阅的实际字节版本，不代表本报告作者执行过这些脚本。

| AP repo 相对路径 | SHA-256 |
|---|---|
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/pg-preflight.ps1` | `61f78546065bf8b80ebffb3034f6dbb66bbd7bdf6b458bc7be376facc76a7367` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/legacy-runtime-history.json` | `6aae94046a9b34179a7724c1d5c17cd9df706fb3dfbcea0f1f45e20cca893abf` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/legacy-runtime-proof.json` | `3878033ffbe2673b9e641c21396e9a13c6e7a98e072698506bbe10de08db3df8` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/read-known-runtime-history.ps1` | `d21332c2456d457094c7229cbfdea0cbc7e82432f65eddd88c944eca6dbb1287` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/full-pg-admission.json` | `bbadcb36e055c15f8cf053a2177bcbb6639314fe88c7df7047ce72d290f3376e` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/fixed-database-before.json` | `dcd23eca11038ce47c0a7d701f503391e84388d0bd1206154c7c9bb1c7d894f0` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/freeze-approved.json` | `d6e75653c1607553250e1e4bd833a9c109347b595de8629b071722bc3ab27202` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/preflight-array-stop/pg-before-known-history-stop.json` | `06e644be923a258da16a9938d103a045dd05fb530f2b3422ef15314e4ab87960` |
| `docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/preflight-array-stop/pg-before-empty-context-schema-stop.json` | `78ebc4628213aeaa3814392c6158000e197f4524774d86e7852dd24aa2b6c990` |
| `docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842/xml/TEST-cn.agentplatform.engine.infra.TestInputHttpTest.xml` | `f66716f8c02d5db71d0aae0a6e94da5788c034bce2593cb72c193dccb7e38ea3` |
| `docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842/method-summary/methods.csv` | `76a4f8611684ef032b675ace3bff11f6ba208d6c8914b50b3660758673fc2c97` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputHttpTest.java` | `fc8a028390928b41cb19ad6b49f0fa0ea1a2a6e5a6e48afb4f1ebeb865d55e9d` |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/TestInputPromotionPostgresTest.java` | `a0dfbd86b3652597adbec366ced9a18cc84e3770331b3f6e29b233d65f3e0dcf` |
| `engine-runtime/target/classes/db/migration/V222__run_context_pack.sql` | `419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90` |
