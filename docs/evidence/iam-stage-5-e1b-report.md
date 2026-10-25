# IAM Stage 5 E1b 实施报告

日期：2026-10-20。作者状态：DONE_WITH_CONCERNS，提交独立 Spec/Quality 双审；不是审查人签收。唯一工程为 `G:/207/项目/算法重组工具/代码/agent-platform`。

## 交付和边界

正式 brief SHA `68be5835eb98f01a31ab56fe43d15af228a9e7341aaca0bb561b4083cdca0f60` 已在改源前校验。E1a 346 项 src/POM 基线每项实际 SHA 校验通过，原清单 SHA `275b5c1d65948a5f594ea74662b0a4abe2765f7d953540ab27fa2c117dcff075`。本段最终 356 项、16 文件变化：三个新生产类、一项 V216 迁移、六个新增测试/fixture 文件；四个 common 生产文件最窄修改、原 LoopbackAuthority 测试 fixture 最窄扩展及 engine POM 的 existing common test-jar test scope。

- `RunAuthorityStore.createPublished` 自行 INSERT RETURNING 新 run id，同真实调用方 DataSource 写事务中保存完整原 initial/origin、不可变受理审计与三 scope；开始/结束 requireBound，不接收可被补权的任意旧 runId。异常设置同连接 holder rollback-only，调用方捕获异常也不能提交半成 run。budget/FULL/requestId 依 root 明确裁决，见专属 `root-decisions.md`。
- `MaterialScopeStore` 仅处理 run.snapshot、run.inputs、run.version_refs。版本取实际 PG snapshot 精确规范 SHA，三个 payload 各有实际 PG 内容 SHA；versionRefs 的父 scope 必须真实属于同 run snapshot。权限 floor 来自完整 initial；labels 保留 initial/current/config/parent 并集。write 为 infra 包可见的一参可信原语。
- 普通 read 用同 DataSource 的只读 REPEATABLE_READ；已有事务低于该隔离级别拒绝，绝不 REQUIRES_NEW。先 metadata/scope/owner/root/purpose/current 覆盖，再 fixed-switch 单列 payload SELECT 和 SHA 复核。新 same-owner Grant/new initialABC 可以读取旧 AB，即使旧 Grant 到期或后台已被 403 阻断。
- `RunAuthority.current` 仅固定 engine→engine run.advance、DELEGATED/PORTAL-origin。网络在 PG 事务外，真实 resume 和 authorizeRetained 各一次；原 Grant/expiry/fullinitial/origin/root 精确核对、三 scope 覆盖，再短事务重读绑定和 checkVersion、proof 期限终检并 CAS。永久 403 不恢复，503 有最多一分钟范围内的固定构造时退避，未到期零网络，CAS0 不返回成功。未知数据库/编程错误不伪装成 503。
- local tuple 没有 HTTP 路由条目；client/exchange/forward 不得出票，实际 HTTP guard 拒绝 local 票。LoopbackAuthority Grant 保存 originalCall，仅首次 main gateway→engine published-run Grant 能 resume；app read/debug/status/metadata-selector Grant 均失败。原 origin 与 originalCall 在后续链路保留。
- 三新类无 stereotype，未装配到生产。RunService/RunController/TraceStore/driver/scheduler/Gateway/AppService、YML、HTTP 路由 JSON、四契约和生产 IAM 启动闭锁保持冻结。

原 published TASK guard_ctx 的 tenantId/principalId 在最终自审中发现遗漏，新增具名红测后补齐；它们只作审计，改空 guard_ctx 后 side-row 授权仍正确加载，证明不以审计字段替代 retained proof。

## 实际 PG 和测试边界

仅固定 `127.0.0.1:25432 / agent_platform_iam_engine_v12 / agent_v12`。既有受控 launcher 只把新工程 AGENT_DB_PASSWORD 传给子进程；没有读取/输出环境、打印密码、另写 env 解析器或连接 main/D2/其它业务库。管理连接仅 postgres 中按固定库名查询存在性、在不存在时 CREATE DATABASE。

`pg-preflight01` 实测库原不存在、history 为 null；新建后按源迁移 V200–V215 连续执行 16 个有版本迁移，预检实际 engine 表数 13（含 Flyway history）。因此分配 V216；`persistence-green01` raw 记录实际执行 V216，之后最终各 fixture 日志确认 schema 版本216、18条被验证的 Flyway 记录（包括 schema 创建标记）。没有 outOfOrder、重写旧迁移或高号占位。最终表数没有另做查询，不把预计增表数写成实测。测试库保留包括红测半成 run 在内的有限测试事实，不以清库隐藏失败。

fixture 使用原 LoopbackAuthority 的真实 Basic 服务身份、RSA 签名/JWKS/nonce/requestDigest，真实 TrustedJwtDecoder/JwtAuthenticationToken，并以真正 `RunController.run(String,JsonNode)` Method 调用真实 ServiceAuthorization.preHandle 建立 frame，再 requireInitialFor；finally 调用真实 afterCompletion 并清理 Security/Request 上下文。没有反射 bindRetained、没有 new VerifiedAuthority 造 proof，没有复制 authority。范围是 guard+store+PG 组件测试，未执行真正 MVC/filter/controller 方法体、已发布 FLOW 或 scheduler。

## 最终验证

```text
node .tools/run-iam-module-tests.mjs iam-stage-5-e1b-final-common01 admin-common '*'
179 tests / 0 failures / 0 errors / 0 skipped，exit 0。

node .tools/run-iam-module-tests.mjs iam-stage-5-e1b-final-engine02 engine-runtime RunAuthorityPersistencePostgresTest,InitialMaterialScopePostgresTest,RunAuthorityLocalCurrentPostgresTest
35 tests / 0 failures / 0 errors / 0 skipped，exit 0。
```

最终 engine 为 persistence13、material12、current10。逐类 XML SHA、raw 摘要、时间、执行集合在每轮 `execution-audit.json`。不与旧 962、其他阶段或重复轮计数相加；没有重跑 D2/D3/七 reactor/旧427无关 suite。

**源码适用性**：common179 当轮保存完整356源快照，但不是最终全356同码；随后仅 `RunAuthorityStore.java` 和 `RunAuthorityPersistencePostgresTest.java` 变化。最终验证精确比对 common 全源/POM 加 root POM 共77项均与179当轮相同，因此179适用于最终 common；不为 engine 审计修正重复 common。最终 engine35 当轮全356源码与 final source/frozen/replay 每项 SHA 一致。

具名测试覆盖：AB/AB create+三 scope/三真实 read；A 收窄在提交前全回滚（包括调用方捕获异常）；新 Grant ABC 读旧过期 AB；current A/错 owner/root/status purpose/version/digest 的零 payload 拒绝；null/currentOnly/旧 bind/替换/过期/跨线程/关闭 frame/不同 DataSource 拒绝；run 后、authority 后、第二/第三 scope、后续 node SQL 故障全事务回滚；requestId 冲突无补权；历史无 authority/少 scope 拒绝且零 payload；scope 不可变、owner/source审计/parent/hash/version 负例；精确超 double 范围整数、高精度相邻小数、1/1.0/1e0、-0/0、nested null/{}、array 顺序；并发闩锁证明 scope→payload 窗口内不可变约束生效；READ_COMMITTED 加入拒绝；旧 PORTAL 到期但原 Grant 有效的 resume；原 Grant 到期/撤销、稳定 origin 篡改、503 退避/恢复、并发旧 CAS0、未知 DB 错误不伪装503；local main 来源、非main originalCall、错 tuple/source/origin、HTTP guard 拒绝。

## 红绿与噪声真实性

原始 raw/XML/源码快照全部保留，各轮真实执行集见 `test-audit.json` 及逐轮审计。表中的 F/E/S 分别为 failures/errors/skipped；这些是轮次结果，不是独立功能累计。

| Stage 后缀 | 实际 Tests/F/E/S | 结论 |
|---|---:|---|
| audit-red01 | 1/1/0/0 | 非绿；分类见下文 |
| code-red01 | 1/1/0/0 | 非绿；分类见下文 |
| current-edge01 | 13/0/0/0 | exit0；raw/XML绿 |
| current-green01 | 1/0/0/0 | exit0；raw/XML绿 |
| current-red01 | 1/1/0/0 | 非绿；分类见下文 |
| edge-green01 | 14/0/0/0 | exit0；raw/XML绿 |
| edge-red01 | 14/1/0/0 | 非绿；分类见下文 |
| final-common01 | 179/0/0/0 | exit0；raw/XML绿 |
| final-engine01 | 33/0/0/0 | exit0；raw/XML绿 |
| final-engine02 | 35/0/0/0 | exit0；raw/XML绿 |
| local-green01 | 2/0/0/0 | exit0；raw/XML绿 |
| local-red01 | 2/0/1/0 | 非绿；分类见下文 |
| local-red02 | 2/1/0/0 | 非绿；分类见下文 |
| material-check01 | 7/2/0/0 | 非绿；分类见下文 |
| metadata-green01 | 30/0/0/0 | exit0；raw/XML绿 |
| metadata-red01 | 30/1/0/0 | 非绿；分类见下文 |
| persistence-green01 | 1/0/0/0 | exit0；raw/XML绿 |
| persistence-red01 | 1/1/0/0 | 非绿；分类见下文 |
| persistence-red02 | 1/1/0/0 | 非绿；分类见下文 |
| pg-preflight01 | 1/0/0/0 | exit0；raw/XML绿 |

- local-red01 是未捕获 PlatformException 的运行错误（2/0F/1E），local-red02 才是明确 doesNotThrow 断言红；没有将前者写成 assertion 红。
- persistence-red01 在 fixture 的默认未认证 JwtAuthenticationToken 处失败，未进入 store，属于 setup 噪声。修正认证构造后 persistence-red02 才是新增 API 的 `E1B_NOT_IMPLEMENTED` stub 行为红；current-red01 同为新增 API stub 红，不声称发现既有生产持久化漏洞。
- material-check01 两个失败是 JsonNode 的 BigInteger/Decimal/Int 类型相等断言问题；之后改成精确数值比较，没有转 double、舍入或放宽内容 SHA。并发不可变失败断言本身已执行。
- edge-red01 真正发现 catch 授权异常后半成 run 可提交；同 DS rollback-only 修正后14绿。metadata-red01 真正发现公共 CAS 成功入口可缺 retained proof；实现入口自验 fresh proof/绑定/阻断规则后30绿。code-red01 发现即使签名 root 一致也应拒绝不合法 appCode，复用既有 MetadataSelectors.code；audit-red01 发现审计 owner 字段遗漏。后二者均由最终 engine35 覆盖为绿。
- 完整 common179 raw 的77条 ERROR/Exception匹配行来自原启动闭锁负例（SECURITY_MODE_REQUIRED/IAM_CONFIG_REQUIRED/信任链不可用等），XML仍为0F/0E；未通过添加默认 bean 救启动。最终 engine35无 ERROR/WARN 噪声。PG初建预检有一条已存在 schema 的 Flyway WARN，原文保留。
- 早期归档按120秒 XML mtime 窗口，可能夹带上轮 XML。原拷贝和原 executed-suites.json 均保留；逐轮新增 execution-audit.json 精确列 raw `[INFO] Running` 名称、对应本轮XML SHA/计数/时间/raw摘要，以及所有未执行 incidental XML（不计数）。后续只按 raw 实际类名拷贝；最终两轮无 incidental XML。没有倒跑补历史。
- 首次保护枚举因 web/node_modules 链接被主动拒绝；未跟随链接。改在遍历前排除已生成的 web node_modules/dist 后完成 before，发生在任何生产修改之前。docs/contracts 无这两种目录；所有保护文档（含 brief/同前缀文件）完整保留，仅排除本段目录加斜杠和精确本报告。枚举范围在 protected-enumeration-scope.json。

## 上游源码实际阅读

固定版本源码文件、原始 URL、实际 SHA、许可证和具体方法/不变量在 `iam-stage-5-e1b/upstream/manifest.json`、`upstream/READING.md`。实际阅读 Spring6.2.7 事务加入/隔离、DataSource connection holder 与 rollback-only；Jackson2.19.0 精确 BigDecimal/BigInteger 分支；Spring Security6.5.0 NimbusJwtDecoder 的签名→validate 顺序；Nimbus9.37.3 DefaultJWTProcessor 的签名验证分支。均 Apache-2.0。只声称所列方法/片段，不声称读完整项目。没有更改保护的 docs/open-source-references.md，root 将审查后汇总。

## 冻结、重放和保护

- `source-before.json` 346；`source-after.json` 356，SHA `35f339e649d7849c3b10c17607c06bc067cc5947047386443c04e9f9d5bce54e`；16文件逐项见 changed-files.json。
- 最终源字节在 `freeze-source-final/`；最终编译 class/test-class 与 common测试JAR共311项在 `freeze-target-final/` 和 compiled-freeze.json，清单SHA `c2e9a3d0241d02e5d354aaa88206e9cde3e6c2f8914709552d68ba458c34e1f0`。没有新 package/deploy，旧打包生产JAR不冒充本段新构建交付。
- 全七模块 target-before1648、target-after1692，target-after清单SHA `e4fcea9e61e0c23a5224dde58896ba5802dedf0b8eeeb053cefa1fcf939031c2`。所有当前 target 实际字节已复核，common/engine以外 target 改变0。
- protected-before26367、protected-after26368。唯一差异是 root 明确授权的 `docs/evidence/iam-d3b-published-payload-seam-proposal.md` 新增18928字节、SHA `e8d613dd6b534723c7a175697617aef8dcdedb1503311afc73c460bcf1eb5e09`；before没有重写，没有扩大文档前缀/目录白名单。其余保护字节全部一致。
- 最终完整上下文 patch 为 `change.patch`，178875字节，SHA `1b425f21483994eeb5e23c5e0f2722f40aa7100c0334c23659b4728195b66027`。实际 git apply --check 和 apply 均exit0，`replay-final/` 全356文件每字节等于最终源；patch-check.json/final-verification.json保存核验。
- attempt01 的 apply 虽exit0但root .gitattributes `text=auto eol=lf` 使 RunAuthority3158→3157字节，未达byteequal；attempt01 patch、日志、失配SHA与失败记录保留。只在本段证据根增加局部 `.gitattributes`（`* -text`）给复制树继承，工程root属性/源码/全局Git均未修改。作用和控制文件SHA在 replay-controls.json；复制树只有登记356源，不把控制文件算源。actualapply有13行末CR导致的 whitespace提示，未使用whitespace修正，保持真实字节。
- 自审前的成功补丁/manifest在 `pre-selfreview/`，源/编译复制树为旧 `freeze-source/`、`freeze-target/`、成功重放 `replay02/`；它们只代表补审计字段前，不冒充最终。第一次失败重放 `replay/` 保留。
- 无HEAD/remote，git-boundary.json保存实际结果；没有造空diff、git add -A或commit。没有写其它工程或读取相邻旧代码/配置/数据/进程。

## 保留的后续依赖与交回

完整 Task5/联合 D3–E 仍须真实 gateway metadata→main→app发布指针与完整快照→RunService真正新建分支接线、requestId/status用途、节点/事件/checkpoint/output/error/parent等全材料、owned scheduler以及显式重新授权。显式重新授权必须保留旧initial/scope/审计并设计新代际/绑定切换/CAS，不能把当前Actor覆盖旧initial；这是必需后续项。acceptedSnapshot真实性仍依赖未来真正 app受理 writer 核远端发布指针，本组件不会跨库猜 app版本存在。外部 data-source/历史引用 writer、本次未接的 CHAT/facts/approver/下载/回放/skill/model、RR1/RR2真实HTTP/stream、生产IAM/集群/容量均未验收。

只 T01 完成；17功能/34任务/30验收没有整体通过，不新部署/推送。作者完成本段实施和自审后，交回 common+engine 的 source/target 所有权，不再运行 Maven/改源/改target；等待独立Spec/Quality审查，后续修正需root重新授权作者取得所有权。归档缺陷和首次重放失败已透明保留，不作为隐藏的绿色证明。
