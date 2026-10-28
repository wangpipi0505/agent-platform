# G-8 收尾：Y-4 处置决定 与 Y-5 核实记录

日期：2026-10-28　基线：`feat/v1.2-implementation` @ `4b26625`
约束：另一泳道在改 platform-gateway 限流，本轮 **零触碰** platform-gateway / 产品 main 代码 / app-management / engine-runtime / .env。
最终代码改动：**无**（test.ps1 曾做一行补线，实测引入新增失败后已 `git checkout` 回退，详见 §1.3）。

## 0. 结论速览

| 项 | 结论 |
| --- | --- |
| Y-5 | **-WithCache 接入链路已闭环并实测**（ModelQuotaRedisTest 13/13 绿）；默认路径存在"误启用"残留缺陷，完整闭合需 2 行改动但其中 1 行在 model-gateway 测试文件（本轮白名单外），已回退并留档 §1.3 |
| Y-4 | **本轮不做（选项 b）**。影响面实测精确为 app-management `IntegrationPolicyResolverHttpTest` 1 类 15 Error（全为容器级 `PORTAL_CEILING_INVALID`）；全量修复跨 5 模块共享夹具与 24 处手工铸令牌，半修会更红的实测在案，本轮不满足 ≤2h 低风险路径。生成器设计要点与分批迁移顺序见 §2.4，下轮可直接照做 |

---

## 1. Y-5：Redis 测试接入核实

### 1.1 链路核实（静态 + 实测）

`scripts/test.ps1` 的接线链：**L8** 无条件把 `.env` 载入进程环境 → **L22** `if($WithCache){start-cache.ps1}` → `start-cache.ps1` 在专用 WSL 发行版 `AgentPlatformV12` 启动固定 Redis 7.2.16（127.0.0.1:26379，随机密码写回 `.env`，首次运行自动补齐 `AGENT_REDIS_HOST/PORT/PASSWORD` 三键）→ mvn 子进程继承环境变量 → `@EnabledIfEnvironmentVariable(named="AGENT_REDIS_PASSWORD")` 门控类启用。顺序正确（先载 .env 后启缓存，两路都能把密码放进进程环境）。

实测（本机，专用 Redis 已在跑）：

1. `scripts/verify-cache.ps1` 原样输出——身份校验通过：
   `version=7.2.16, port=26379, authenticated=True, directory=/var/lib/agent-platform-redis7, distribution=AgentPlatformV12`
2. 复刻 test.ps1 环境链（.env 载入 + `JAVA_HOME=jdk-21.0.12.8` + `-DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"` + `-Dmaven.repo.local=.tools/m2`），`-pl model-gateway -am -Dtest=ModelQuotaRedisTest`：
   - 密码在（等价 `-WithCache` 路径）：**Tests run: 13, Failures: 0, Errors: 0, Skipped: 0**（真实 Redis Lua 配额路径，4.4s）
   - 剥离 `AGENT_REDIS_PASSWORD`：**Tests run: 13, Skipped: 13**（门控干净跳过，无错误）

**判定：台账 Y-5（"Redis 未接入测试"）的原始诉求由 `-WithCache` 已闭环**，本轮由上述两条实测收口（Y-2 证据 §6 当时记"-WithCache 未在本机验证"，现已补验）。

### 1.2 受益面

- `model-gateway` `ModelQuotaRedisTest`（13 例，本轮实测全绿）
- `engine-runtime` `ChunkBusRedisTest`（同门控注解，engine-runtime 本轮未单测验证，门控方式相同，随 `-WithCache` 同步解锁）
- `model-gateway` `ModelNoSendStatisticsPostgresTest`（见 §1.3，属残留缺陷面）

### 1.3 残留缺陷（本轮不闭合，理由与下轮做法）

**缺陷**：`ModelNoSendStatisticsPostgresTest` 的门控是 `AGENT_DB_PASSWORD`（:27，默认路径必然启用），内部却 `setPassword(System.getenv("AGENT_REDIS_PASSWORD"))` 硬连 26379（:50-51），**没有 Redis 门控**。叠加 test.ps1 L8 无条件载入 `.env`，默认路径行为取决于机器状态：

| 场景 | ModelNoSendStatisticsPostgresTest（6 例） | ModelQuotaRedisTest（13 例，有门控） |
| --- | --- | --- |
| 默认路径 + Redis 在跑 + .env 有密码（本机现状） | 绿（实测 6/6） | 绿（被 .env 泄漏误启用） |
| 默认路径 + Redis 没跑 | **6 Error**（既有非确定性假红） | 13 Error |
| 默认路径 + 脚本剥离密码（本轮试过的补线） | **6 Error（实测）** | 13 Skipped（实测） |

本轮曾给 test.ps1 加 `else{Remove-Item Env:AGENT_REDIS_PASSWORD}`，实测上表第三行：`ModelQuotaRedisTest` 干净跳过，但 `ModelNoSendStatisticsPostgresTest` 6 绿 → 6 Error，**新增失败** → 已回退。结论：**只改脚本不能闭合默认路径**，必须连测试门控一起改，而门控在 `model-gateway/src/test`（本轮白名单外）。

**下轮收口（2 文件 2 行，可直接照做）**：
1. `model-gateway/src/test/java/cn/agentplatform/model/application/ModelNoSendStatisticsPostgresTest.java` 类级加 `@EnabledIfEnvironmentVariable(named = "AGENT_REDIS_PASSWORD", matches = ".+")`（抄 `ModelQuotaRedisTest.java:16`）；
2. `scripts/test.ps1` L22 后加 `else{Remove-Item Env:AGENT_REDIS_PASSWORD -ErrorAction SilentlyContinue}`（注意保留 BOM+CRLF，Y-2 教训）；
3. 验收判据：默认路径三处 Redis 用例全部 Skipped 且无 Error；`-WithCache` 路径 `ModelQuotaRedisTest` 13/13 绿（本文件 §1.1 实测数字即对照基线）。

---

## 2. Y-4：门户属性上限与旧夹具不同步——处置决定：本轮不做

### 2.1 现状影响面（2026-10-28 开工实测，引用原始输出数字）

`-pl app-management` 全量：**Tests run: 177, Failures: 0, Errors: 19, Skipped: 0**，坏类 4 个：

| 坏类 | Errors | 根因 | 是否 Y-4 |
| --- | --- | --- | --- |
| `IntegrationPolicyResolverHttpTest`（19 例中） | **15** | 全部容器级 `PORTAL_CEILING_INVALID`（surefire 报告 grep 计 15 处） | **是（Y-4 全部现实影响面）** |
| `AppCopyConcurrencyTest` | 1 | `FATAL: database "agent_platform_app_copy_test" does not exist` | 否（Y-6 桶：夹具自建库） |
| `DraftMetadataHttpTest` | 1 | `BeanDefinitionOverrideException`：`PublishedMetadataFixtureMain$Receiver` 与 `PublishedAdmissionFixtureMain$Receiver` 重复定义 `authorityProperties` | 否（夹具 Bean 冲突桶） |
| `PublishTransactionTest`（13 例中） | 2 | `PlatformException: 缺少当前授权上下文` | 否（Y-3 桶：deployment mode 未登记） |

admin-common 开工实测：**Tests run: 363, Failures: 10, Errors: 1, Skipped: 0**——与 2026-10-27 基线（319/10F/1E）失败数完全一致（模块新增 44 例），**admin-common 现状没有 Y-4 错误**。

**台账口径修正**：旧口径"PORTAL_CEILING_INVALID 致 admin-common/app-management 20 Error"应修正为——**Y-4 现实影响 = app-management 单类 15 Error；"admin-common 被拖红"只是 2026-10-27 半修实验的代价，不是现状**。

### 2.2 根因（代码位置级）

- 校验：`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityProperties.java:63-64` 要求 `attributeValueAllowlist.keySet() == attributeAllowlist`（键集完全相等）；`:66-70` 要求各值集非空、`bizSystemCode`/`userId` 恰一值。
- 夹具：`admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:114-115` 把 `attributeAllowlist` **硬编码**为 `Set.of("region","dataLabels")`，却把 `attributeValueAllowlist` 换成 `explicitPortalActor.attributes()`——凡是 `portalActor(...)` 传入属性键集 ≠ `{region,dataLabels}` 的 actor，`client()` 构造即抛 `PORTAL_CEILING_INVALID`（`HttpCurrentAuthorityClient.java:67` 在构造时 `validate`），容器起不来。
- 触发点：`app-management/.../IntegrationPolicyResolverHttpTest.java:64` `portalActor(new Actor(TENANT,"admin-d2",Set.of("ADMIN"),Map.of("dataLabels",Set.of("restricted"))))`（295/296/297 同型）。

### 2.3 为什么本轮不做（三条可复核理由）

1. **改造面是 5 模块共享夹具**：`LoopbackAuthority` 经 admin-common test-jar 被 5 个模块消费，`portalActor` 调用 38 处（清单见 §2.5 附录）；platform-gateway 是另一泳道在改的模块，本轮**无法在其上验证**任何夹具行为变化。
2. **半修更红的实测在案**（`docs/evidence/2026-10-27-Y2-test-script/README.md` §5）：只补配置四键 → app-management 20→19（容器错误消失、仅 5 例转绿、出现新的测试级残留），admin-common `ServiceAuthorizationTest` 0→12E、`LocalRunAdvanceAuthorityTest` 1→4E。根因 `ClaimCeiling.attributes`（`ClaimCeiling.java:27-34`）：ownership 键（bizSystemCode/userId）任一侧出现即要求两侧同值单例——**配置侧补了键、手工令牌没带键，解令牌即 403**。
3. **只修 config 侧也不闭环**：按 Y-2 同条件实验，容器级 15 错只会转化为"5 绿 + ~10 个待归因的新测试级失败"。本轮剩余预算不支持把 10 个新失败面全部归因（app-management 的坏类里已有 3 个非 Y-4 桶在排队），交付"零未解释残留"不可信。

### 2.4 下轮建议卡（可直接执行）

**卡名**：夹具统一生成 AuthorityProperties 与配套令牌（Y-4 收口）
**预估**：1 个整日（含每步模块对照跑），前提是 platform-gateway 泳道已落地、无并行改动。

**生成器设计要点**：

1. **生成器只加不改语义**：在 `LoopbackAuthority` 增加 `portalCeiling()` 静态工厂，聚合"Portal 配置 + portal() 令牌 claims + ceiling()"三件事。核心不变式：`attributeAllowlist ≡ attributeValueAllowlist.keySet() ≡ actor.attributes().keySet()`（**从 actor 推导键集**，不再硬编码）；roles 取 actor.roles()；ownership 值取 actor 单例。
2. **等价性证明（本轮已完成逐点核对）**：38 处 `portalActor` 调用方中，除下述 2 处外，actor 属性键集**全部恰为 `{region,dataLabels}`**——对它们"键集推导"与现状逐字节等价（显式 actor 为 null 的分支保持原 `Set.of("region","dataLabels")`）。仅有的例外：
   - `IntegrationPolicyResolverHttpTest:64/295/296/297`（键集 `{dataLabels}`）：无效→有效，**即本卡的目标修复点**；
   - `FrozenModelBindingVerifierTest:236`（`{region,dataLabels}` 且 dataLabels 空集）：`AuthorityProperties.java:67` 要求值集非空，仍无效——与现状一致（该处当前也不触发校验），无行为变化。
3. **令牌侧天然一致**：`portal()` 在 `explicitPortalActor != null` 时 claims 的 `tenant/sub/roles/attributes` 全部取自同一 actor（`LoopbackAuthority.java:131`），与推导出的上限同源，**不会复现"配置有键、令牌没键"的 403 陷阱**——该陷阱的根源是各测试手工铸令牌，生成器统一后无手工令牌。
4. **分批迁移顺序**（每步 `mvn -B -pl <模块> test "-DargLine=-Xmx1024m ..."` 模块对照，标准：既有坏类清单纯不增、通过数不降）：
   - Step 1：仅改 `LoopbackAuthority.config` 的键集推导（~2 行）→ 跑 admin-common（对照 363/10F/1E）与 app-management（对照 177/19E）；预期 `IntegrationPolicyResolverHttpTest` 15 容器错 → ~5 绿 + ~10 新测试级失败（Y-2 同条件数据）；
   - Step 2：**triage 那 ~10 个新失败**（逐个归因：预期能再救回一部分，剩余按桶登记），这是全卡最大的不确定点，故单独成步、允许中途停下；
   - Step 3：admin-common 手工铸令牌 24 处（`ServiceAuthorizationTest` 5、`RetainedAuthorizationTest` 4、`TrustedJwtDecoderTest` 3、`CurrentAuthorityClientTest` 3、其余 9）收敛到工厂——仅当 Step 2 清零或显式降级时才做；
   - Step 4：model-gateway / engine-runtime 消费方夹具（`InitialModelInputConsumerFixture:46`、`FrozenModelBindingProducerFixture:246` mallory 等，键集已核对等价，属机械替换）；
   - Step 5：platform-gateway（等限流泳道落地后最后做）。
5. **验收**：admin-common 363/10F/1E 不变；app-management Y-4 面清零（坏类 4→3，只余 §2.1 表中三个非 Y-4 桶）；每步输出摘录入 `docs/evidence/`。

### 2.5 附录：portalActor 全量调用点核对清单（2026-10-28 grep）

键集恰为 `{region,dataLabels}`（生成器等价替换，共 31 处文件/调用行）：
admin-common：`AuthorityContextRetainedTest:14,38`、`GatewayStatusAuthorizationTest:14`、`PortalEventsJoinedAuthorityFixture:13`、`RetainedAuthorizationTest:56,146,175`、`PortalEventsAuthorityProfileTest:31`、`ServiceAuthorizationTest:130,467`；
app-management：`PublishedMetadataHttpTest:58,64`、`PublishedPayloadHttpTest:107,180`；
model-gateway：`InitialModelInputConsumerFixture:46`、`FrozenModelBindingProducerFixture:65,246(mallory=actor.attributes())`、`ModelInputConsumerProducerFixture:64,269,271`；
platform-gateway：`GatewayPublishedChainPostgresTest:105,205,509`、`GatewayRunStatusChainPostgresTest:77,91,152`、`PublishedMetadataSmokeMain:21`、`GatewayPublishedRunProxyTest:189`、`RunObservationEventsMetadataTest:24`、`PublishedMetadataClientTest:29`、`PortalEventsMaterialAuthorityTest:605`；
engine-runtime：`RunEventReaderAuthorityFixture:16`、`FlowMaterialStatusPostgresTest:184`、`InitialMaterialScopePostgresTest:30,49`、`ModelInputMaterialFixture:43`、`PublishedAdmissionHttpPostgresTest:347,601`、`RunAuthorityFixture:34`、`RunObservationMetadataHttpPostgresTest:111,254,257,260`、`RuntimeClientsPublishedHttpTest:113`、`RunObservationStatusHttpPostgresTest:162,425`。

键集 `{dataLabels}`（无效配置，Y-4 修复目标）：`IntegrationPolicyResolverHttpTest:64,295,296,297`。
键集 `{region,dataLabels}` 且 dataLabels 空集（无效但当前不触发校验）：`FrozenModelBindingVerifierTest:236`。

---

## 3. test.ps1 对 G-8 完成标志的评估（只读分析，未跑七模块全量）

**部分满足**。"单命令跑完"的骨架已在位且工程上可用：`verify-contracts.mjs` 前置门 → 白名单建测试库（不碰开发库）→ `.env` 载入 → 堆与 fork 超时钉死（Y-2 的 native OOM 与无限挂两大崩溃面已绕过）→ `-Modules` 过滤 → `-WithCache` 显式缓存 → 前端开关。**单命令可以跑完全部模块、每模块有界结束**（本轮实测：app-management 全量 ~2min、admin-common 全量、model-gateway 单类 28s 含上游构建，均正常退出并给出计数）。

**未满足"坏类清单可解释"的三个缺口**：
1. 全量跑不完绿：Y-4（15E）、Y-3（skill-registry 64×502）、Y-6（platform-gateway 夹具自建库缺失）、X-7（隔离假红）仍在；解释坏类清单目前必须**外挂** `docs/evidence/2026-10-27-baseline/README.md` 对照，脚本输出不自解释；
2. 默认路径 Redis 语义非确定（§1.3），七模块全量在"Redis 没跑"的机器上会比本机多出 `ModelNoSendStatisticsPostgresTest` 一类假红；
3. `surefire.timeout=900` 把 RuntimeStreamTest 类挂起从"无限"降级为"15 分钟失败"，但一旦触发仍会阻塞后续模块 15 分钟，且该类本体未修（Y-2 只做了工程绕）。

**结论**：G-8 完成标志判"部分满足"；转"满足"依赖 Y-3/Y-4/Y-6 落地 + §1.3 两行收口，届时坏类清单可由脚本输出 + 当期 baseline README 双向对账。

## 4. 回滚方式

- 本轮最终代码改动：**无**。过程中唯一提交级改动（test.ps1 一行补线）已用 `git checkout -- scripts/test.ps1` 回退，`git diff scripts/test.ps1` 为空。
- 未执行任何 `git add/commit/push`；未触碰 Redis 专用实例（verify-cache 只读）、PG、`.tools/m2`、任何产品代码。
- 验证期临时文件（均在 gitignore 的 `.local/`，可删）：`y5-run1.ps1`、`y5-run2.ps1`、`y5-check-nosend.ps1`、`y4-baseline-app.ps1`、`y4-baseline-admin.ps1`、`y4-baseline-admin.log`。全部命令已原样收录在本文件 §1.1/§2.1，删临时文件不影响复现。
