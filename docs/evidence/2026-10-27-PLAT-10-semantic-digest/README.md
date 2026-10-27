# T10 / PLAT-03/10：semanticDigest 与受控证据重绑定

- 日期：2026-10-27
- 分支：`feat/v1.2-implementation`（未 commit、未 git add）
- 范围：仅 `skill-registry`；未改 pom、web、ProcessContract、SkillExecution、SkillAdapters、`admin-service-authority.json`。
- 任务书：`文档/优化方案/派发卡片-B1-20261027.md` 卡片 12。

## 1. 结论

发布门禁从“只比 `candidate_sha256`”升级为“sha + semanticDigest + passed”三条同时成立（验收另加
`hit_rate>=0.8`）；新增 ADMIN 端点把来源版本已通过的证据复制到目标草稿，历史行一字不改；
`semantic_digest` 由 V315 幂等迁移回填（历史行 = `sha256`，V315 之前不存在“改了展示字段”的证据语义）。
验收命令 22 例全绿，既有 `SkillRulesTest` 6 例不受影响。

先红后绿原文：

```
# 红（SkillDigest 临时骨架：naive digest=canonical(contract)，未排序、未剔除展示字段）
[ERROR] Tests run: 22, Failures: 5, Errors: 2, Skipped: 0
[ERROR]   SkillDigestTest: Tests run: 6, Failures: 3, Errors: 0
          失败用例：displayOnlyEditsKeepDigest / keyOrderDoesNotChangeDigest / nestedDisplayFieldsAreExcluded
[ERROR]   SkillPublishDigestPostgresTest.descriptionOnlyEditKeepsDigestButChangesSha（naive digest 等于 sha）
[ERROR]   SkillEvidenceRebindPostgresTest.rebindsEvidenceForDescriptionOnlyEditAndKeepsHistory
          （旧 digest 与目标不一致 → 409 SEMANTIC_CHANGE_REQUIRES_RETEST）
# 同轮 2 例 error：复制 SQL 参数错（DataIntegrityViolation）与 sourceWithoutPassingEvidenceIsRejected
# 的 NOT NULL 违约，均已在实现中修正（见 §3.4）

# 绿（实现 SkillDigest.sort+排除集、修正复制 SQL 参数）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.application.SkillEvidenceRebindPostgresTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.application.SkillPublishDigestPostgresTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.domain.SkillDigestTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.domain.SkillRulesTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

验收命令（显式 JAVA_HOME）：

```bash
cd /g/207/项目/算法重组工具/代码/agent-platform
source .tools/env-load.sh
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
mvn -B -ntp -pl skill-registry test \
  -Dtest='SkillDigestTest,SkillPublishDigestPostgresTest,SkillEvidenceRebindPostgresTest,SkillRulesTest,SkillCatalogTest' \
  "-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" -Dmaven.repo.local=.tools/m2
```

`SkillCatalogTest` 在仓库中不存在（历史验收命令里的旧类名），surefire 只提示无匹配、不会失败；
本次回归用 `SkillExecutionTest`（25 例）+ `ProcessPublishPrecheckTest`（10 例）等覆盖发布链路。

## 2. 改动文件

生产：

- `skill-registry/src/main/resources/db/migration/V315__skill_semantic_digest.sql`（新增）
- `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillDigest.java`（新增）
- `skill-registry/src/main/java/cn/agentplatform/skill/domain/SkillRules.java`（仅加 `semanticCanonical` 纯入口）
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/application/SkillGovernance.java`
- `skill-registry/src/main/java/cn/agentplatform/skill/api/SkillEvidenceController.java`（新增）

测试：

- `skill-registry/src/test/java/cn/agentplatform/skill/domain/SkillDigestTest.java`（新增，6 例）
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillPublishDigestPostgresTest.java`（新增，5 例）
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillEvidenceRebindPostgresTest.java`（新增，5 例）
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillStatisticsPostgresTest.java`（造数补 `semantic_digest`，兼容 NOT NULL）
- `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java`（两处手写证据插入补 `semantic_digest`）

## 3. 实现要点

### 3.1 digest（纯函数）

`SkillDigest.of(Map<String,Object> contract)`：Jackson `valueToTree` → 递归规范化 → `Json.write` →
`Digest.sha256`（小写 64 位 hex）。规范化规则：

- object 按键名自然排序重建；array 保持顺序（数组顺序有语义）；
- 键名匹配 `(?i)^(description|examples?|doclinks?|documentation|changelog|displayname|title)$`
  的键整棵剔除（嵌套 schema 内同样生效）；
- 其余全部纳入（endpoint/method/authScheme/httpMapping/inputSchema/outputSchema/execution/
  reconciliation/guard/process/timeoutMs…）；`SkillDigest.VERSION = 1` 只在响应字段 `digestVersion`
  返回，不写进 contract。
- 只依赖 Jackson 与 `common.Digest`，不读 DB、不注入 Spring；`SkillRules.semanticCanonical` 只是
  让 domain 侧有唯一规范化入口。

### 3.2 迁移（幂等）

`ALTER TABLE … ADD COLUMN IF NOT EXISTS semantic_digest char(64)` → `UPDATE … WHERE semantic_digest IS NULL`
（skill_version 回填 = `sha256`；connection_test/acceptance 按 `skill_version_id` join 回填）→ `SET NOT NULL`；
新建 `skill.evidence_rebind` + `UNIQUE(tenant_id,to_version_id)`，不建外键（草稿删除后审计行必须留存）。
`protect_published_contract()` 未改。

### 3.3 门禁

`SkillCatalog.publish`：先查草稿 `semanticDigest`，空 → 409 `CONNECTION_TEST_REQUIRED`；
连接测试/验收均为

```sql
… where tenant_id=? and skill_version_id=? and candidate_sha256=? and passed
        [and semantic_digest=?]        -- 开关打开时追加
```

验收另加 `hit_rate>=0.8`。0 行 → 409，消息带“；下一步：对 v{version} 重跑连接测试/选型验收”。
开关 `agent.skill.evidence-digest-gate`（默认 true）只放宽 digest 条件，sha 与 passed 永不放开。

### 3.4 重绑定

`POST /admin/skills/{code}/versions/{version}/rebind-evidence`，body `{fromVersion, reason(10..500)}`，
`Actor.current().requireRole("ADMIN")`。`SkillCatalog.rebindEvidence` 在单事务内：

1. 目标必须 DRAFT；from 必须存在且 `semantic_digest` 相等，否则 409
   `SEMANTIC_CHANGE_REQUIRES_RETEST`（“下一步：对该版本重跑连接测试与选型验收”）；
2. 先写审计行 `skill.evidence_rebind`（`DuplicateKeyException` → 409 `EVIDENCE_ALREADY_BOUND`，
   UNIQUE 约束是并发裁决者）；
3. `insert … select` 复制通过的 connection_test（`report || jsonb_build_object('reboundFrom', …::jsonb)`，
   `candidate_sha256` 用目标 sha、`semantic_digest` 用目标 digest）；acceptance 逐行复制并换新 id
   （原表 PK 是业务 id），report 追加 `reboundFrom:{version,sha256,at,operator,reason}`；
4. 复制不到任一类证据 → 409 `EVIDENCE_INCOMPLETE`（审计行随事务回滚）；
5. 返回 `{rebound:true, fromVersion, semanticDigest, connectionTest:true, acceptance:true}`。
   不改任何历史行。

### 3.5 写入点

`SkillCatalog.create/save` 同事务写 `sha256=Digest.sha256(Json.canonical(contract))` 与
`semantic_digest=SkillDigest.of(contract)`；`SkillGovernance` 按当前候选版本 digest 写入两张证据表，
report 内也带 `semanticDigest`/`digestVersion`；`versionRow` 返回 `semanticDigest` 与 `digestVersion`
（旧 `digest` 字段保留为 sha，兼容旧前端）。

## 4. 未覆盖 / 风险

- **排除集是白名单式硬编码**：未来新增展示字段（如 `summary`、`icon`）若不在正则里，会被计入语义，
  改它会让 digest 变、证据失效（偏安全，但会误伤）。
- **并发重绑定**：两个请求都过预检时由 `UNIQUE(tenant_id,to_version_id)` 兜底，第二个得到 409；但审计
  行先写、复制行后写，若复制阶段异常整个事务回滚，不会留下审计行。
- **protect_published_contract 与复制行**：只 INSERT 新行、不 UPDATE 历史行，触发器不参与；但审计表
  没有外键，草稿被 `SkillCatalog.delete` 删除后 `from_version_id/to_version_id` 成为悬空 id（审计保留是
  有意设计）。
- **旧前端兼容**：不传/不认 `semanticDigest` 的调用方仍可读旧 `digest`（=sha），发布与证据写入在服务端
  完成，无需前端配合；但旧证据（V315 前）语义即字节，改过 description 的历史草稿不能直接发布，必须
  重新保存/重测。
- `SkillCatalogTest` 不存在于仓库，验收命令中该类名永远空跑。
- 未跑模块全量（任务书明确 9 例 `ServiceTokens`/`deploymentMode` 既存失败属 Y-3）；回归中
  `AlgorithmMigrationPostgresTest.v310…` 的 1 例失败是 execution_fact 既有列断言问题（V311+ 早已加列），
  与 V315 无关；`ReconciliationProofTest` 34 例 error 是部署密钥/依赖服务环境问题。
- 本工作区有并行的 `engine-runtime` 改动（7 个已修改测试 + 2 个未跟踪文件），不属于本次 T10，未触碰。

## 5. 回滚

`agent.skill.evidence-digest-gate=false`：门禁退回只比 `candidate_sha256`（仍要求 passed 与 hit_rate），
新列与新端点保留，不做数据回滚；迁移本身幂等，可重复执行。
