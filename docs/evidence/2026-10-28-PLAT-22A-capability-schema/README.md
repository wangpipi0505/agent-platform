# T22A · PLAT-22 A 段：能力包 schema 冻结 + 路线一用能力包重新声明（2026-10-28）

## 1. 范围与依据

- **任务**：优化台账 §3 T22A——能力包 schema 冻结 + 路线一（可见性规划智能体）用能力包重新声明；**不写平台产品代码**（§11.9 A 段："不写平台代码，路线一用能力包重新声明并跑通连接/契约/评测"；本卡产出是声明文件与冻结记录）。
- **设计依据**：《平台侧优化方案与实施细则-20261027》§11.2（能力包：软件接入的唯一声明入口，最小字段表 11.2.2、示例 11.2.3、校验规则 11.2.4）、§11.3（路线配置）、§11.9 A 段；开发计划 §6.5 契约纪律（"能力包/路线配置（22A）是声明文件，不是代码分支"）。
- **产出**：
  - `contracts/capability-package.schema.json`——`urn:agent-platform:capability-package:1.0`（JSON Schema 2020-12），schema v1 冻结；
  - `contracts/capability-packages/route1-visibility.json`——路线一重新声明实例（纯声明文件，不接入运行链路，不改变任何线上行为）；
  - `scripts/verify-capability-package.mjs`——构建期校验工具（零依赖，类比 `verify-routes.mjs`；**未接入 `verify-contracts.mjs`**，门禁接入决策留给 T22D）；
  - `contracts/freeze.json` 顶层新增 `engineeringAddenda[0]` 登记 schema sha256（沿用 skill-invocation `engineeringAddendum` 的增补登记模式）；`contracts/skill-invocation.md` 与 `contracts/snapshot/**` 未动。
- **冻结口径**：schema v1 冻结后，任何变更须走计划 §6.5 契约纪律——freeze.json `engineeringAddenda[0].sha256` 更新 + `contracts/snapshot/` 同步 + 台账 §7.3 变更记录；schema 演进 = 新 `$id` 版本（`urn:agent-platform:capability-package:N.0`），版本不可原地覆盖（§11.2.4）。

## 2. 运行环境

| 项 | 值 |
| --- | --- |
| Node | v24.16.0（Git Bash on Windows，仓库根目录执行） |
| 平台服务 | 未启动（本卡无 Java/前端/PG 改动，无需 JDK/PG/端口；校验为纯构建期文件校验） |
| 依赖 | 零新增（node 内置 `node:fs/promises`/`node:crypto`/`node:path`；仓内无 ajv 等 JSON Schema 运行时，校验器内置最小关键字集） |

## 3. 执行的命令原文

```bash
# 绿锚点：路线一声明通过校验（输出见 green-route1.out）
node scripts/verify-capability-package.mjs

# 红用例：非法声明（由 route1-visibility.json 经 node -e 定点变异生成，命令见第 4 节）
node scripts/verify-capability-package.mjs --file docs/evidence/2026-10-28-PLAT-22A-capability-schema/fixtures/invalid-missing-tool-binding.json
node scripts/verify-capability-package.mjs --file docs/evidence/2026-10-28-PLAT-22A-capability-schema/fixtures/invalid-identity-plaintext.json
node scripts/verify-capability-package.mjs --file docs/evidence/2026-10-28-PLAT-22A-capability-schema/fixtures/invalid-unknown-kind.json

# JSON 语法自证
node -e "JSON.parse(require('fs').readFileSync('contracts/capability-package.schema.json','utf8')); JSON.parse(require('fs').readFileSync('contracts/capability-packages/route1-visibility.json','utf8')); console.log('JSON 语法自证通过')"

# freeze.json 登记后既有门禁不受影响
node scripts/verify-contracts.mjs
```

负向夹具生成（provenance：全部由真实声明文件定点变异而来）：

```bash
node -e "const fs=require('fs');const base=JSON.parse(fs.readFileSync('contracts/capability-packages/route1-visibility.json','utf8')); /* 见 fixtures/*.json 注释同款变异 */"
```

三条变异规则：①`delete base.tools[2].binding`（缺工具绑定）；②`base.identity.secrets=[{ref:'admin123456',...}]` + `base.identity.apiToken='sk-plain-value-not-a-reference'`（身份形态非法/明文凭据）；③`base.interfaces[0].kind='GRAPHQL'`（执行方式未知 kind）。

## 4. 结果（原文）

### 4.1 绿锚点（`green-route1.out`）

```text
== 能力包声明校验（T22A 冻结口径，schema urn:agent-platform:capability-package:1.0）==
schema: contracts\capability-package.schema.json  sha256=0456799d04deb57dfb114d169d8a37c7b57a49cad3996fee917a93efa704a634
[1/1] contracts\capability-packages\route1-visibility.json v1.0.0  software=space-ground-visible
  interfaces(2): space-http-sync[HTTP_SYNC] space-http-async[HTTP_ASYNC]
  tools(6): space-target-query->space-http-sync[READ_ONLY] space-visibility-plan->space-http-sync[READ_ONLY] space-visibility-submit->space-http-async[WRITE_WITH_APPROVAL] space-visibility-status->space-http-sync[READ_ONLY] space-visibility-result->space-http-sync[READ_ONLY] space-satellite-options->space-http-sync[READ_ONLY,停用]
  ontology: NONE
  routeProfiles(2): http-sync http-async
  verification: connection=通过  contract=通过  model=通过  evaluation=关闭(豁免留痕)  safety=通过  concurrency=关闭(豁免留痕)
  错误 0 条，警告 0 条
能力包校验通过：1 个声明，0 条错误，0 条警告
EXIT=0
```

### 4.2 红→绿记录

- **开发期首跑即红（真红，非构造）**：校验器首版把 `if` 分支内层失败误判为分支匹配（嵌套失败未向父级传播返回值），且 schema 漏声明 `interfaces.description`，首跑路线一声明报 **18 条错误**（interfaces 被同时套用 PROCESS/MCP/ALGORITHM 三分支的 required、READ_ONLY 工具被要求 confirmRoles、ontology.mode=NONE 被要求 space/release 等）。修复两处后转绿；这正是"先红后绿"要求的失败路径实证——校验器缺陷是被它自己的红输出暴露的。
- **构造红用例（非零退出 + 字段路径可读，原文见 `red1/2/3-*.out`）**：

| 用例 | 变异 | 退出码 | 关键错误原文（含字段路径） |
| --- | --- | --- | --- |
| 1 缺工具绑定 | 删 `tools[2].binding` | 1 | `能力包.tools[2]：缺少必需字段 binding` |
| 2 身份形态非法 | `secrets[0].ref='admin123456'` + `identity.apiToken` 明文 | 1 | `能力包.identity.secrets[0].ref：不匹配模式 ^(secret|cred)://（收到 "admin123456"）`；`能力包.identity：未知字段 apiToken`；`identity.apiToken：疑似明文凭据：键 apiToken 的值不是 secret:// 或 cred:// 密钥引用（§11.2.4 禁止明文）` |
| 3 执行方式未知 kind | `interfaces[0].kind='GRAPHQL'` | 1 | `能力包.interfaces[0].kind：值 "GRAPHQL" 不在枚举 ["HTTP_SYNC", "HTTP_ASYNC", "PROCESS", "MCP", "ALGORITHM"] 内`（附未注册 kind 告警诊断指向 T22C） |

### 4.3 JSON 语法自证（`json-syntax-check.out`）

`JSON 语法自证通过`，EXIT=0。

### 4.4 freeze.json 登记后既有门禁（`verify-contracts-after-registration.out`）

```text
G-9 路由目录：服务票据面 0 MISSING；门户面缺行告警 28 条、STALE 5 条（均不阻断，清单见 docs/evidence/2026-10-27-G9-route-drift/README.md）
v1.2快照、四契约原文哈希与七模块依赖边界通过
EXIT=0
```

登记方式：freeze.json 顶层新增 `engineeringAddenda[0]`（date/card/artifact/sha256/schemaId/declarations/verifier/basis/changeControl/note），`verify-contracts.mjs` 未改动、不读取该键——既有门禁行为零变化（接入决策归 T22D）。

## 5. 未覆盖与已知失败

- **无失败用例遗留**：三红一绿全部符合预期；`verify-contracts.mjs` 通过。
- **声明≠实现（本卡口径下的显式留白，均已在声明文件 `implementationGaps` 逐条登记）**：
  - 本体发布 READY 解析、工具投影注册表、consumer 注册、contextPack 自动注入、统一主体映射——**归 T22B**（O-02/O-03/O-05/O-07、A-03）；路线一 context pack 现状为手写进 systemPrompt（ontorepo 未部署），声明如实 `ontology.mode=NONE`；
  - `interfaces.kind=PROCESS`（以及未来新执行方式）——schema 枚举已冻结表达，适配器注册表/stdio/超时杀树/产物回收**归 T22C**（A-01/A-02）；校验器对未注册 kind 出"需要新增适配器"告警诊断（§11.2.4），路线一未声明 PROCESS 故绿锚点无告警；
  - 共享对象 ID（capability_package_id 等 §11.7）、按 package/route/tool/release 的观测与成本归因、SLO 登记与管理台目录——**归 T22D**（A-06/A-07/O-07/O-08）；
  - 能力包驱动的工具档案/toolProfile——**归 T14.1**；
  - 应用发布评测门禁对自由文本 CHAT 智能体不可用（整份 output 精确 JSON 全等断言）——路线一验证报告 §5 已登记的产品缺陷，本卡如实以 `verification.evaluation=false` + 豁免留痕表达，不在 22B/C/D 范围。
- **能力包 schema 的"草稿→验证→发布"三态生命周期**（§11.2.4 首句）本卡只冻结声明形态，状态机/发布面归 T22D 控制面收口。
- **校验器边界**：schema 层只实现本 schema 所用关键字（2020-12 子集）；schema 演进引入新关键字必须同步扩展校验器（注释已写明）。不触网、不校验本体发布态、不校验密钥系统。
- **确认角色来源限制**：space-visibility-submit 的确认角色（ADMIN/APPROVER）取自场景 2 实测审批节点（scenario-2-async-submit.json / README §3），非技能契约原文快照——契约 JSON 全文仅异步对账段有原文留档（路线一验证报告 §2），其余技能契约字段以验证报告/落地验证方案/证据 JSON 三方交叉为据。

## 6. 回滚方式

- 本卡未提交（禁止 git add/commit），全部改动为工作区新增/修改，直接还原即可：
  - `git checkout -- contracts/freeze.json`（唯一被修改的既有文件）；
  - 删除新增文件/目录：`contracts/capability-package.schema.json`、`contracts/capability-packages/`、`scripts/verify-capability-package.mjs`、`docs/evidence/2026-10-28-PLAT-22A-capability-schema/`；
- 回滚后 `node scripts/verify-contracts.mjs` 恢复登记前状态（该脚本本就不读取新增键，回滚前后均通过）；
- 纯声明文件不进任何运行链路，回滚无线上行为影响。

## 7. 路线一声明与现状的映射表（声明文件关键字段 ↔ 现状出处）

出处缩写：**R1README** = `docs/evidence/2026-10-27-route1-visibility-agent/README.md`；**R1REPORT** = `文档/路线一验证报告-可见性规划智能体-20261027.md`；**LVPLAN** = `文档/落地验证方案-三路线本体与智能体-20261026.md`；**APP** = 同目录 `app-config.json`；**SKILLS** = 同目录 `skills-state.json`；**TCDB** = 同目录 `tool-calls-db.json`；**S2** = 同目录 `scenario-2-async-submit.json`；**v1.2契约** = `contracts/skill-invocation.md`。

| 声明字段 | 值 | 现状出处 |
| --- | --- | --- |
| `metadata.name/version/domain` | route1-visibility / 1.0.0 / space-visibility | §11.2.3 示例命名口径（orbit-visibility → 本实例按路线一目录命名）；纯声明标识 |
| `software.system/environments` | space-ground-visible / dev=http://127.0.0.1:8080 | R1README §1（"空间服务 http://127.0.0.1:8080，真实 MySQL space_target_visible + Orekit"）；LVPLAN §2.3（"35 个操作…OpenAPI 3.0.3 机器合同"） |
| `interfaces[0].id/kind` | space-http-sync / HTTP_SYNC | LVPLAN §4.1 技能资产表（SYNC 形态端点清单）；§11.3 路线配置 http-sync |
| `interfaces[1].id/kind/endpoint/timeoutMs` | space-http-async / HTTP_ASYNC / …/visibility/tasks / 120000 | R1README §2 关键契约（`invocationKind:SUBMIT_QUERY, timeoutMs:120000`）；LVPLAN §4.1（"受理异步观测规划 POST /api/v1/space/visibility/tasks，SUBMIT_QUERY"） |
| `interfaces[1].reconciliation.*` | endpoint/jobIdPath/donePath/doneValue/failedValue/failureMessagePath/resultEndpoint/resultEndpointPath/idempotencyKeyEcho | R1README §2 契约原文逐字段（"reconciliation:{endpoint:…/tasks/{jobId}, jobIdPath:$.taskId, donePath:$.status, doneValue:SUCCEEDED, failedValue:FAILED, failureMessagePath:$.errorMessage, resultEndpoint:…/tasks/{jobId}/result, resultEndpointPath:$, idempotencyKeyEcho:false}"）；字段形态 = v1.2契约 §12（PLAT-06 冻结） |
| `ontology.mode` | NONE（不声明 contextPack） | R1REPORT/R1README §4（"ontorepo 未部署…context pack 按本体设计手写成文后经 systemPrompt 注入，未走 ontorepo API 自动生成"）；§11.2.4（NONE 不得声明 contextPack） |
| `tools[].id/skillRef`（6 项，含钉版本与 sha256） | space-target-query@1、space-visibility-plan@1、space-visibility-submit@1、space-visibility-status@2、space-visibility-result@1、space-satellite-options@1(停用) | SKILLS 全部 versionNo/sha256 逐条对应；R1README §2 发布门表格（5 项 PUBLISHED + satellite-options DISABLED） |
| `tools[].binding` | 只读 4 项 → space-http-sync；submit → space-http-async | TCDB 调用 219/220（SYNC 调用）；调用 221/225（SUBMIT_QUERY 对账）；LVPLAN §4.1 端点形态列 |
| `tools[].exposure` | 5 项 READ_ONLY；submit WRITE_WITH_APPROVAL | R1REPORT §4 应用提示词技能使用规则（"查询、规划、读取结果都是只读操作；提交任务属于有副作用的操作，需要人工确认"）；TCDB 调用 221 进 WAITING_APPROVAL |
| `tools[submit].confirmRoles` | ["ADMIN","APPROVER"] | S2 + R1README §3（"nodeInstanceId 571，confirmRoles [ADMIN,APPROVER]"；confirmRoles 来源 = 技能契约 executionPolicy.confirmRoles，见 skill-registry SkillExecution.java:152） |
| `tools[satellite-options].enabled=false` | 停用 | R1README §2（"DISABLED（5414 条 / ~550 KB 响应超输出预算）"） |
| `identity.guard.roles/attributes` | [ADMIN,EDITOR,APPROVER,SERVICE] / region=[LOCAL] | APP config.guard 逐字段 |
| `identity.subjectMapping` | platform_user → Actor.subject() | v1.2契约 §3.2（SkillInvocation 身份三件套）；PLAT-12 §13（出站身份唯一来源是已验主体快照） |
| `identity.secrets=[]`（无出站身份声明） | 空引用清单 | R1README §2（连接测试直连 8080 无凭据）；LVPLAN §4.1（无鉴权列）；PLAT-12 落地晚于路线一验证，技能契约未声明 identity |
| `agents[0]`（code/type/skillRefs 钉版本/modelBinding/promptRef/requiredLabels） | space-visibility-agent / CHAT / 5 项钉版本 / modelEntryId=13 deepseek-flash / draftRev 4 candidateSha256 08222c1e… / [PUBLIC] | APP 全文（code/name/type/draftRev/candidateSha256/config.skillRefs/modelBinding/guard/requiredLabels）；R1README §1（"model.entry 13 = deepseek-flash (chat,tools)"）；R1README §4（systemPrompt 1396 字符） |
| `verification.connection/model/safety=true` | 三门通过 | R1README §2（连接测试 SUCCEEDED call 215–218；选型命中率全部 1.0）；S2（审批→对账→采纳链路走通）；R1README §3 场景 2b（REMOTE_FAILED 携带 TLE 校验和原因） |
| `verification.evaluation=false` + 豁免 | 评测门禁 ERROR（REJECTED 实测） | R1README §5（预检 5/6，评测断言整份 output 全等对自由文本不可用）；同目录 app-publish-gate.json / app-evaluation-report.json |
| `verification.concurrency=false` + 豁免 | 未覆盖 | R1REPORT §3（本轮三场景无并发用例）；LVPLAN §2.3（"异步任务缓存重启不恢复"等运维注记未实测隔离） |
| `operations.notes` | 不编造数值；超时 30000/120000ms、输出硬闸 1MiB 旁证 | v1.2契约 §4.2（timeout_ms DEFAULT 30000、output_max_bytes DEFAULT 1048576）；R1README §2（~550KB 停用）；SLO 缺测如实留白，归 T22D |
| `routeProfiles[http-sync]/[http-async]` | 两个配置实例 | §11.3 路线配置表（http-sync/http-async 行）；§11.8（"路线一 HTTP 服务：interfaces=[HTTP_SYNC/HTTP_ASYNC]…路线配置 http-sync 或 http-async"） |
| `implementationGaps[0..4]` | T22B/T22C/T22D/T14.1/评测门禁缺陷 | §11.5/§11.6 表（A-01/A-03/A-04/A-06/A-07、O-02/O-03/O-05/O-07）；台账 §3 T22A–T22D 行；B4 派发卡 E-6（T14.1 门控 T22A 冻结后）；R1README §5 |

**声明文件未做的替代（零线上行为变更的自证）**：本卡不改任何技能契约、应用配置、数据库行与平台代码；路线一在平台内的真实形态仍是 skill-registry 的 5+1 技能版本与 app-management 的 CHAT 应用草稿——能力包是对同一现状的第二表达（§11.2.1："能力包不绑定某个智能体应用……路线配置只是能力包的一种使用方式"），两者的字段级对应关系即上表。

## 8. 证据文件清单

| 文件 | 内容 |
| --- | --- |
| `green-route1.out` | 绿锚点：路线一声明校验通过原文（EXIT=0） |
| `red1-missing-tool-binding.out` | 红用例 1：缺工具绑定（EXIT=1，路径 `tools[2]`） |
| `red2-identity-plaintext.out` | 红用例 2：身份形态非法/明文凭据（EXIT=1，3 条错误） |
| `red3-unknown-kind.out` | 红用例 3：未知执行方式 kind（EXIT=1，枚举错误 + T22C 诊断告警） |
| `json-syntax-check.out` | schema 与声明文件 JSON 语法自证 |
| `verify-contracts-after-registration.out` | freeze.json 登记后既有门禁通过原文 |
| `fixtures/invalid-*.json` | 三个负向夹具（由真实声明文件定点变异生成） |
