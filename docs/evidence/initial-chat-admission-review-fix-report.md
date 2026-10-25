# 首次 CHAT I1 顶层保留字段修补记录

2026-10-23。实现交付；组件保持 **NOT_ACCEPTED**。本报告不签条件档，不替代全新独立复审、root 限定物理核对或项目负责人批准。

本轮只闭环独立报告 I1：显式顶层 `chatSession`、`chatTurn`、`sessionHistory`、`history` 曾被首次 CHAT 请求忽略，也可能随已有 requestId 提前回读。最终在已有 `requirePublishedRequest` 名单中增加这四个名字，继续用 `has` 按字段存在性拒绝，包括 null、空值、非空值。没有剥离字段后继续，没有增加会话能力或任意未知字段拒绝框架。

## 授权、源范围与调用路径

FIRST 完整读取 `.local/initial-chat-admission-fix-brief.md`，另读 AGENTS、implementation-brief、contracts/README、原 grant/plan/rebind/execution-context、原作者报告和独立报告。部分组合读取输出截断后补读。采用 TDD、系统化排错、Java/Spring、ponytail、完成前验证；没有子 agent、主 Git、commit、部署、聊天、自动化或目标操作。所有 exec_command 显式 cwd 为 P=`G:/207/项目/算法重组工具/代码/agent-platform`。

基线 `.local/initial-chat-admission/final-source2/source.json`：136836 bytes，SHA256 `2d9378c13c487bfe4fe40d191bb961dd1e76f2f4058c83a0486015c4e7190f20`，688 文件。最终仍 688，**3 MOD / 685 same / 0 ADD / 0 DEL**：

1. `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java`：仅新增四个名单元素。
2. `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionHttpPostgresTest.java`：新增 I1 RED、两矩阵及轻量 FLOW 正控。
3. `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialChatAdmissionFixture.java`：最小有限测试 action、只读本波 tenant/app 关联计数、保存真实 published 来源供规则检查，以及允许新 action 名在私有证据文件中区分。没有业务 UPDATE 或故障注入。

静态调用检查确认同一 gate 在首次 requestId 查询、容量不足时的 requestId 回读、受理事务内 requestId 回读之前。实际测试触达普通新建和已接受 requestId 回读；没有声称强制触发三种竞争时序。共享 gate 不按请求声明猜 CHAT 类型，保留其原受保护请求边界；合法 FLOW 与旧私有 TASK 均有本轮必要正控。

最终冻结后再编译 `green-build2`，四个 GREEN 波都使用该生产源。最终 provenance 再核对 canonical 688 文件全部与冻结源相同，work 的 468 个非 test 输入与 canonical 相同。唯一生产变更没有触及 InitialChatRules、模型/common/IAM、迁移、配置、依赖或四契约。

## 实际 RED 与 GREEN

使用全新专用 PG/三角色、独立 app/engine/model 业务 classpath、真实签名测试 IAM、实际 app 受保护 published HTTP 和数字版本/FrozenModels、实际 admin HTTP。model JVM 内装配隔离 provider/tokenizer，engine JVM 内装配 IAM；每波最多三 server JVM 和一个专用 Redis，测试 runner 是单独 30 秒命令。没有真实生产 IAM、模型或部署验收声明。

| 波次 | 实际结果与覆盖 |
|---|---|
| `red-i1` | TLS alias 装配失败，未运行业务测试；不是 RED。 |
| `red-i1b` | 旧生产 SHA `6d0cbbba9809dd5d4beb94b40c8984fe627b49195e5d9fdde5021143238bcc48`；真实 published CHAT、正数字 versionId、有效 FrozenModel 后，顶层 `history: []` admin 请求实际 200，返回 runId 和一个 node。测试期待 403，DIRECT_JUNIT **1 test / 1 failure / 0 skip**；才是 I1 业务 RED。 |
| `green-new` | **1 / 0 / 0**。四字段 × null/空数组/非空对象，共 12 个新 requestId HTTP 请求均 403；run、run_authority、node_instance、model_invocation、provider 请求计数前后相同。四个 inputs 空字段实际 HTTP 拒绝；从真实 published 来源派生的四个 snapshot 空字段在既有纯规则层拒绝；后者不冒称错误快照完整 HTTP 发布链。 |
| `green-replay` | **1 / 0 / 0**。无历史 CHAT 创建并 SUCCEEDED，provider 实际 1 次；正常 requestId 回读同 run。四字段 × 三形状共 12 个已有 requestId HTTP 请求均 403；原 run/status/result、nodes、attempts、invocations 与 provider 次数不变；随后合法回读仍返回原结果。 |
| `green-flow` | **1 / 0 / 0**。合法 FLOW 实际 HTTP 200、SUCCEEDED、独立 status 200、provider 0。 |
| `green-task` | **1 / 0 / 0**。旧私有 TASK 的必要单正控，SUCCEEDED、实际 send 1 / commit 1；复用原测试方法，不重跑旧九群组。 |

四个 GREEN runner 均 exit0、timedOut=false、rootAlive=false。红波 runner exit1、无 timeout/存活 root；对应所有 server/Redis 都有实际结束证据。所有失败数据、原始私有 DIRECT_JUNIT 事件、派生 XML 和 guard 记录保留。XML 只用 DTD 禁止、resolver=null 的读取方式投影 counts/names/status。

本次没有重跑原九重点群组、SSE/恢复、sender/schema/model/预算/材料完整矩阵；它们只按原 source2/原报告继承引用，不能称为本修补重新完整通过。新 case 也没有扩大任意未知字段策略、公共会话/第二轮、token/chunk/cancel、默认 IAM/D3 或部署能力。T01/T09/T11/T12/T33 等状态不由本报告修改。

## 新资源与测试来源

仅创建一个 `initial_chat_admission_fix_<32hex>` 数据库及 `icaf_e/m/a_<32hex>` 三角色。原三模块迁移串行执行，外层独立 guarded finally 撤销临时数据库 CREATE；实际九项检查后才启动业务。未授 superuser/createdb/createrole，未进行跨 schema 业务访问扩张。关闭保留数据，sessions0、memberships0、三角色 NOLOGIN/高权限 false、九项 schema USAGE/CREATE false。

每波新建 TLS/master/provider/IAM/key/alias/password、配置、端口与 Redis 数据目录；不读取或复用旧运行配置、密钥、数据库、进程、端口或捕获类/JAR。TLS 使用专用 truststore 与 loopback SAN；没有 trust-all。使用授权的 Redis v2 源机制，先 trap、fork 后记录 PID/start/config、限定 exe 交接，六波都有精确自有 identity/closure，absent=true。没有全局进程/端口扫描或未知 kill。

只复制授权的五份 test-only overlay 源到新 work，保留 live 秒级零 advance、同一 iat 基准和原 TTL、exp=min(iat+TTL,op.exp)。producer 的新 TLS alias 属性补充只改 work 测试源码；canonical common/verifier 未改。新增 common test-only Credentials 源与私有 app/model/resource/migration/JUnit/Redis helper 均单独列于 provenance。app/model ready 使用 temp→atomic replace。引擎四个 GREEN preseal 中其他 app/model 业务 classpath 条目数均 0。

## 新发生的 setup 与过程记录

这些事实不倒填、不自归规则 §3、不以历史旧组件状态自动决定本组件档位：

- `064d23`：首次只读脱敏 inspect helper 从 authoring 路径运行，未先封存 outer。输出已省略全部字符串和注释；无子进程/运行资源。已及时向 root 披露；之后文件 helper 使用封存副本。该事实不能被后来的正确运行覆盖。
- `78f362`：sealed execute 的自身路径判断混用了斜线形式，在启动 child 前失败；修正后派生新封存副本，没有重放资源。
- 所有新 `.tools/initial-chat-admission-fix-*` helper 都有事前精确路径不存在登记。**输出登记粒度仍有缺口**：早期 `work/` 与其 copy manifest、`build/{common-build1,services-build1,classpaths1}/`、`compile/{helper-engine1,helper-model1,helper-app1,producer-alias1}/`、`database-commands/{database-create1,migration-open1,migration-close1}/`、`setup/schema-migrations1/`、`waves/{red-i1,red-i1b}/` 的部分内部生成输出，仅有新 owner/目录不存在检查或有限预列，未逐文件保留 PREabsence。`red-i1` 的预列还含与最终实际路径不同的占位名。后续 run/build/compile/db 增加了输出根事前记录；这些机制生成的内部日志、argfile、ready、class 等仍不能一概宣称每个文件都逐一 PRE登记。新的 source/runtime/native/package 证明 helper 对直接复制/写入文件逐个登记。早期事实保持，未重新执行绿色功能补历史。
- `red-i1`：新随机 keytool alias 与复制 producer 测试源码中的固定 TLS key-alias 不匹配，实际 Tomcat SSLUtilBase.getKeyManagers 拒绝。只在 NEW work 改为读取 fresh property；原波模型 JVM被既有 stop 流程强制结束（guard exit -1），其稳定身份结束、Linux absent=true，不计正常全绿 setup。
- 初次源码 patch 因上下文不匹配未应用；一次 rg 的 Windows wildcard 路径参数错误。均在静态编辑/读取阶段，无业务影响。
- `source-freeze`：自写 `Dir` 被 PowerShell 同名 alias 优先解析，661ms exit1，尚未创建 final-source。`source-freeze2` 6370ms exit0 才是实际冻结。中间 `green-build1` 仍编译旧 work，没有用于 GREEN；随后 `green-build2` 使用已冻结最终源。
- `native-apply`：native check/apply 和全部 688 hash 比较完成后，结果文件 `replay.json` 与准备阶段 metadata 同名，6759ms exit1。没有重新 apply 或制造第二种 patch；`native-verify` 对现有树做 native reverse-check 及全 688 byte/hash核对，827ms exit0，新结果为 `replay-result.json`。原失败与原准备 metadata 都保留。
- `fresh-common/fresh-services` 默认只 test-compile；首次 `package-equality` 因未产 JAR 614ms exit1。其空 `final-package/` 保留。显式 `fresh-package` 离线 package 14312ms exit0 后，`package-equality2` 856ms exit0；新证明在 `final-package2/`。没有重跑业务测试或增加 cap。

没有已知凭据原值输出；旧/新 helper 源输出使用全字符串/注释省略或 AST 名字，私有配置/JWT/argfile/rawXML/provider/user payload 没有 raw 输出。此声明不补齐上述 outer/PRE 记录缺口；新复审须按实际事实独立判断。

## 源、运行物、JAR、原生回放和关闭

运行物在 fresh package 前捕获：721 files、686 个 Java21 major65 classfiles（含测试/helper）、158 个实际依赖 JAR。捕获按四个 GREEN 的实际 server/JUnit preseal 逐路径验证；source/input、test overlay 与执行副本身份在 provenance 中分开记录。RED 使用自己当时的 source-input hash 清单和 actual-runtime preseal；没有把 GREEN class 字节冒充 RED 的旧 class。RED 没有另存独立全 runtime blob 树，报告保留这一证据粒度限制。

fresh `package-work/` 从最终源及明确 test overlays 新复制，未复用旧 target/class。四个 JAR 的 317 个 production entry 与实际捕获逐字节一致：common108、engine143、model40、app26；三个服务内嵌 common 与 fresh common JAR 一致。依赖/源码版本保持 Java21、Boot3.5.0、SpringAI1.0.0、Security6.5/Nimbus9.37.3及本工程离线 cache。

原生 Git 只在新 owner scratch 使用显式 -C/--git-dir/--work-tree/config。baseline/replay 各复制实际 688 文件；diff scratch 只 index 三个修改路径，replay 不建全 index；native --binary/--full-index/--output 生成补丁。最终原生检查和独立全688回放字节相同，零 commit、零主 Git 操作。

closure 审计覆盖 81 个此前 guard records、869 次稳定 identity observations、六个 Linux closures：被登记 Windows identity 当前存活数0、DB sessions0/memberships0。不是全局进程清点。审计自身及其后 provenance/inventory 的实际 guard 返回由最终 control 单列关联；不发虚构“seal运行”补最后一条记录。

原30388B作者报告、23998B独立报告、原source2 manifest及239660B冻结详设的 SHA 均保持原值；详设仍为 `3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。原 report/control/inventory/cohort 未写。

以下路径相对 `.local/initial-chat-admission-fix/`：

| 证据 | bytes | SHA256 |
|---|---:|---|
| final-source/source.json | 136835 | 2625ce4d7f40fcf40c0d529f8b0d59284ceda1ff40253eca629f9bc75ff18ca7 |
| final-source/scope.json | 1288 | 5e1597fc866c644f30dc6ca91ecb3b40b991487a1ac55bbf662e7e04b888366f |
| final-source/overlays.json | 2420 | d37ff03333e49b5485d57d867bc9e908969fa29c985318461b6a4b8426b3def8 |
| final-runtime/files.json | 454270 | a09c4416b6532a474c0d949fba46a63d9a045d2c5777843802537dbd9c4315ee |
| final-runtime/dependencies.json | 54391 | 78b5bd5e72b69b23f30f129998dc9bcfeb8b85cec5df8ca3379e6ad2a8d6e6ab |
| final-runtime/binding.json | 4236 | ede8d1430b59f58a8d992f767b1a78a0e9142362f2e204bc9d254a5079315dee |
| final-package2/equality.json | 1697 | 61d5ec3957c0a709fbc5767a3c5eda199e1c683062a09e1b4a57ea72b84424a1 |
| final-native/change.patch | 12798 | 39ae8ba9cbc5241911df48ce06af097c467a3201b04a231ff3c02f7c6c1fdb2b |
| final-native/replay-result.json | 697 | 37fab493ad610364017c853939d965c68b33dbf78a7c2dd6780f17bbbfee1641 |
| final-evidence/tests.json | 31174 | 1d53b2d60f979dde228ceb6635dda3695a2c66c8c648f58562776a7d6c5d6461 |
| final-evidence/closure.json | 387323 | cfd8adafbbb2dacf84f396a57b485a2d9478a9fa90a10397cbee8aed6d636853 |
| final-evidence/provenance.json | 9751 | 71f4b62c7a46d0658700804276c644f540012fd031120bbdb46b988084b8bdc6 |

最后有限 inventory 在本报告保存后生成；control 在其实际 guard 结束之后写入，链接本报告/inventory和真实末次结果。二者故意不被本报告自引用哈希；原始私密日志、XML、密钥、配置、argfile、provider payload 不列入公开 finite inventory，blob 树按 manifest 引用。最终交回 ALL / noBackground / NOTAccepted 只表示本授权修补与交付结束，不表示正式验收。

主要实际工具 envelopes：prepare `942e5f`；source-freeze2 `1255e7`；green-build2 `36f161`；RED `40d70d/session87813` → `1caca5`；GREEN new `f5b17e/session81555` → `17be40`，replay `dda1f7/session58276` → `311974`，FLOW `eca8a5/session64693` → `e1d8be`，TASK `8d9d30/session37612` → `687e1c`；capture `0418d5/session52704` → `0226e7`；fresh-package `513dea/session4454` → `d4532f`；native verify `2ce040`；JAR equality `10ac8b`；DB close `6279f3`；tests summary `aaf2b6`；closure `6208f0`；provenance `d0c718`。没有为对话 envelope 虚构文件 hash。
