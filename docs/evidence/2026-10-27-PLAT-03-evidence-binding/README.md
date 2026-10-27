# PLAT-03 / PLAT-10 评测证据绑定（语义摘要）

日期：2026-10-27。目标：把"候选内容变了"拆成两种——**语义变了**必须重新评测，**只是描述/展示变了**可以复用已有通过报告。本卡做平台侧（app + monitor），技能侧语义摘要与重绑定已由 `879121f`（PLAT-10）完成。

## 1 原来错在哪

`app-management/.../application/AppService.java` 用 `Digest.sha256(AppRules.canonical(candidate))` 作为候选身份，`canonical` 覆盖 `appCode/type/name/description/domain/bizCategory/tags/config/definition` 全部字段。于是**只改一句 description 就换了 sha**，`monitor` 侧 `accepted(code,rev,sha)` 只按 sha 精确匹配，结果：

- 发布预检直接 409 `EVAL_GATE_BLOCKED`，只能重跑整轮评测；
- 管理员想"确认这次改动不影响语义"没有合法通道，只能改数据库或伪造报告；
- 反过来，如果有人为了绕过门禁去删评测记录，没有任何审计线索。

## 2 改了什么

| 位置 | 改动 |
| --- | --- |
| `app-management/.../app/domain/AppDigest.java`（新增） | `semantic(Map candidate)`：键名忽略大小写剔除 `description/displayname/title/notes/example/examples/doclinks/documentation/changelog`，其余键按 `TreeMap` 递归排序后 `Digest.sha256(Json.write(...))`；数组保持原序（用例顺序是语义）；不改入参 |
| `app-management/.../app/application/AppService.java` | `row()` 先算 sha 再写 `candidateSha256` 与 `semanticDigest`（避免哈希自包含）；`frozenCandidate` 在追加 `draftRev/candidateSha256` 等上下文**之前**算语义摘要；`precheck` 调 monitor 时追加 `&semanticDigest=`，并在 `matchedBy=SEMANTIC_DIGEST` 时写 `gateCheck.evidence` 与一条 warning |
| `monitor/.../db/migration/V505__evaluation_semantic_digest.sql`（新增） | `evaluation_report` 加 `candidate_semantic_digest varchar(64)`；部分索引 `evaluation_report_semantic_idx (tenant_id,app_code,draft_rev,candidate_semantic_digest) where status='ACCEPTED'` |
| `monitor/.../application/EvaluationService.java` | `create()` 落 `candidate_semantic_digest`；`accepted(code,rev,sha,semanticDigest)` 用 `sha=? or (digest is not null and digest=?)` 一次查回，**sha 命中优先**，返回体加 `matchedBy`；新增 `candidateDigest()` 校验缺省/null 或 64 位小写十六进制（否则 400 `INVALID_CANDIDATE_DIGEST`） |
| `monitor/.../api/EvaluationController.java` | `GET /internal/evaluations/accepted` 增可选 `semanticDigest`（不传时行为与以前完全一致） |

两条不变量：

1. **sha 仍然权威**。语义摘要只用于"能不能复用证据"，不改变 `candidate_sha256` 的任何判定；`accepted` 在有 sha 精确命中时一定返回那条，`matchedBy` 前缀才能说明"这次是复用"。
2. **报告不可变**。复用不新建报告、不改旧报告，只让预检引用既有 ACCEPTED 报告；真实重跑仍走 `create()`。

## 3 测试

新增 `monitor/src/test/java/cn/agentplatform/monitor/application/EvaluationSemanticDigestTest.java`（7 例，真实 PG）：

| 用例 | 断言 |
| --- | --- |
| `shaMatchWinsWhenBothPresent` | 同 sha 报告与同 digest 报告并存时取 sha 那条，`matchedBy=SHA256` |
| `semanticDigestReusesAcceptedReport` | sha 不同、digest 相同 → `accepted=true`、`matchedBy=SEMANTIC_DIGEST`、报告 id 正确 |
| `noMatchNeverFallsBack` | sha 与 digest 都不命中 → `accepted=false`（不回落"最近一次通过"） |
| `rejectedReportIsNotEvidence` | 同 digest 但 status=REJECTED → `accepted=false` |
| `differentDraftRevisionNeverMatches` | 跨 draftRev 不匹配 |
| `missingDigestDegradesToShaOnly` | 不传 digest → 只走 sha 精确匹配（历史调用方） |
| `candidateDigestRejectsNonHex` | 非 64 位小写十六进制 → 400 `INVALID_CANDIDATE_DIGEST` |

新增 `app-management/src/test/java/cn/agentplatform/app/domain/AppDigestTest.java`（6 例）：描述变化同摘要而 sha 变、`config` 变化异摘要、嵌套 description 忽略但节点 id 计入、`displayName`/`title` 忽略、输出为 64 位小写 hex 且入参未被修改、键序无关。

红灯记录：修复前 `EvaluationSemanticDigestTest` 无法编译（`accepted` 只有 3 参、`candidateDigest` 不存在）；`AppDigestTest` 无 `AppDigest` 类。先用"类不存在"证明用例覆盖的是新行为，再补实现。

```
$ mvn -B -ntp -pl monitor test -Dtest=EvaluationSemanticDigestTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ mvn -B -ntp -pl app-management test -Dtest='PublishGateTest,AppDigestTest,AppRulesUnionTypeTest,AppSchemaDialectTest'
Tests run: 37, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`PublishGateTest`（11 例）是 PLAT-02 的回归：`precheck` 新增查询参数后，桩 monitor 仍能命中，门禁四种结论未变。

## 4 有意不做

- **技能侧不递归**：候选里引用的技能若只改了描述，本次仍按 sha 判"变了"。技能侧已有自己的 digest+重绑定通道（`879121f`），跨实体签名规则不同，不在本卡强行统一。
- **不建语义摘要的历史回填**：V505 只加列，不重算历史报告（历史候选已无法还原当时入参）；旧报告 `candidate_semantic_digest` 为 null，自然退化为 sha 精确匹配。
- **不提供"强制复用"接口**：复用判定只在 monitor 查询里发生，管理员不能指定"用某份报告的结论"，避免把门禁变成可配置项。
- **未覆盖**：没有 HTTP 容器级用例（预检到 monitor 的真实链路）、没有并发（两个预检同时复用同一报告）压测、前端未展示 `matchedBy`。

## 5 回滚

代码回退即可：`V505` 加列是向后兼容的（旧代码不读该列），`accepted` 的 3 参重载保留，`precheck` 去掉 `&semanticDigest=` 就恢复旧行为。已在 `agent_platform_v12` 应用 V505，回退只影响读取，不需要删列。
