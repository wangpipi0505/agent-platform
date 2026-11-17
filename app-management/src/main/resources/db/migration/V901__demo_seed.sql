-- app V901：演示种子（demo-seed）——台账 M5-1（阶段 5）。
-- 演示种子，生产经 placeholder 跳过；幂等 ON CONFLICT DO NOTHING。
-- 数据权威：contracts/demo-seed-v901-design.md（2026-11-13 用户评审补正版）§2 种子范围总表、§3 manifest 示例
-- （冻结 manifest.schema.json 已过 ajv 校验）、§3.1 ACTIVE 发布与激活指针、§4 两场景数据映射表；
-- 字段逐一对照数据库稿《05-数据库设计-20261112》§4（M5-1 领卡须知），并以各模块既有迁移（V100-V123/V300-V322/V200-V231）实际列为准。
-- 本体线剔除（C0-1）：不含 ontology releaseRef、语义绑定、planning-ontology-* / um-ontology-check 本体对象；
-- 业务侧数据（departments/actors/accounts）不入平台种子；model-gateway 不动（红线）：manifest.model 仅引用编码。
-- 幂等（A16 验收项“迁移可重复”）：全部 INSERT ... ON CONFLICT DO NOTHING；状态推进 UPDATE 带 WHERE 守卫，
-- 重复执行零行变化；只增不改（不触碰既有行）；demo 租户隔离（全部行 tenant_id='demo'）。
-- 种子行清单：application 2 / app_draft 2 / app_version 2 / skill_reference 12 /
-- workspace_manifest 2 / assistant_experience 2+修订2 / integration_profile 2+修订2 /
-- integration_config_release 2（APPROVED→ACTIVE）+ 激活指针 2 / rule_asset+version 3。

-- 生产跳过开关（数据库稿 §6“生产经 Flyway placeholder 跳过”的可执行实现，占位符接线属 G5-0）：
-- 以 PostgreSQL 会话/库级自定义设置 agent.demo_seed_skip 控制，未设置=执行（默认演示/验证库行为不变）；
-- 生产库执行 ALTER DATABASE <prod> SET agent.demo_seed_skip = 'on' 后，本迁移整段 no-op。
DO $demo_seed$
BEGIN
  IF coalesce(current_setting('agent.demo_seed_skip', true), 'off') = 'on' THEN
    RAISE NOTICE 'V901 demo seed skipped (agent.demo_seed_skip=on)';
    RETURN;
  END IF;

  -- §3.1 步骤1：application 两个演示工作区（§4.2 mock 资产化）。
  -- 对照 V100+V102 application 实际列：tenant_id/code/name/type/description/domain/biz_category/tags/status/current_version_no/created_by
  INSERT INTO app.application(tenant_id, code, name, type, description, domain, biz_category, tags, status, current_version_no, created_by)
       VALUES ('demo', 'satellite-planning-assistant', '卫星规划助手', 'CHAT', '从卫星与目标查询，到载荷约束、观测下传规划、人工确认、任务与结果的完整示例。', '航天', '观测任务规划', '["对空","对地","下传"]'::jsonb, 'PUBLISHED', 1, 'demo-seed')
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO app.application(tenant_id, code, name, type, description, domain, biz_category, tags, status, current_version_no, created_by)
       VALUES ('demo', 'user-governance-assistant', '账号开通与角色授权助手', 'FLOW', '从业务权限拉取、账号治理规则校验到审批执行及结果读回的账号管理流程。', '用户管理', 'USER_GOVERNANCE', '["账号开通","权限治理","流程"]'::jsonb, 'PUBLISHED', 1, 'demo-seed')
       ON CONFLICT (tenant_id, code) DO NOTHING;
  -- app_draft：V102 分离草稿实体（app_version.app_draft_id NOT NULL FK 的前提；UNIQUE(tenant_id,app_code)）。
  -- config/definition 为 mock 应用资产化（CHART 应用 definition 为空对象；FLOW 应用为收敛后的画布定义，本体校验节点按 C0-1 由 rule_asset 承载）。
  INSERT INTO app.app_draft(tenant_id, app_id, app_code, draft_no, draft_rev, config, definition, layout)
       VALUES ('demo', (SELECT id FROM app.application WHERE tenant_id='demo' AND code='satellite-planning-assistant'), 'satellite-planning-assistant', 1, 1,
               '{"modelBinding":{"providerCode":"demo-provider","modelName":"demo-model"},"promptSource":"DIRECT","systemPrompt":"你是卫星规划助手，面向空间目标监测与地面目标遥感观测。先确认目标、日期、时间范围、观测星及载荷；区分空间目标SPACE、地面目标GROUND与地面站，下传不是对地成像。先查询目录和载荷，再按卫星规划操作指引组合规划工具。检查星历时效、载荷匹配、光照、云量、分辨率和下传时序及容量，列出可用与排除窗口，给出推荐理由。任务提交、取消与确认选择具有副作用，必须人工确认；提交前冻结计划，不得把模型建议当审批通过。每次回答先给结论，再给窗口表、工具调用与规则判定。无窗口、信息缺失和约束不满足要明确说明与建议。","maxTurns":200}'::jsonb, '{}'::jsonb, '{}'::jsonb)
       ON CONFLICT (tenant_id, app_code) DO NOTHING;
  INSERT INTO app.app_draft(tenant_id, app_id, app_code, draft_no, draft_rev, config, definition, layout)
       VALUES ('demo', (SELECT id FROM app.application WHERE tenant_id='demo' AND code='user-governance-assistant'), 'user-governance-assistant', 1, 1,
               '{"modelBinding":{"providerCode":"demo-provider","modelName":"demo-model"},"promptSource":"DIRECT","systemPrompt":"根据业务系统的当前身份、角色、权限和部门范围处理账号开通。先拉取事实，再按账号治理规则校验。生成变更计划后等待独立审批。审批恢复后重查发起人权限与目标修订，成功执行后读回业务账号和角色。缺事实或约束不满足时停止并说明原因。以Markdown输出流程、规则和结果，不以自然语言自述代替业务读回。","maxTurns":200}'::jsonb, '{"definitionVersion":"1.0","trigger":"CHAT","entry":"start","inputs":[{"key":"message","type":"STRING","required":true,"description":"账号开通与授权需求"}],"outputs":[{"key":"receipt","type":"JSON","description":"业务读回与审批回执"}],"nodes":[{"id":"start","type":"START","name":"接收账号申请","next":"identity"},{"id":"identity","type":"SKILL","name":"识别业务身份并拉取权限","skill":"um-permissions-pull","next":"prepare"},{"id":"prepare","type":"SKILL","name":"生成变更计划","skill":"um-request-prepare","next":"department"},{"id":"department","type":"APPROVAL","name":"部门负责人审批","next":"security"},{"id":"security","type":"CONDITION","name":"高权限安全复核","expression":"request.privileged == true","nextTrue":"security-approval","nextFalse":"execute"},{"id":"security-approval","type":"APPROVAL","name":"安全岗位复核","next":"execute"},{"id":"execute","type":"SKILL","name":"重查权限并执行业务变更","skill":"um-account-create","next":"readback"},{"id":"readback","type":"SKILL","name":"读回账号和角色","skill":"um-request-readback","next":"end"},{"id":"end","type":"END","name":"输出授权回执"}]}'::jsonb, '{}'::jsonb)
       ON CONFLICT (tenant_id, app_code) DO NOTHING;
  -- app_version：已发布版本行（§3.1 步骤1）。candidate_sha256=预计算常量（真实链公式
  -- AppService.sha=sha256(canonical(candidate))）；snapshot 与真实发布同构（candidate+编目字段+manifestPin）；
  -- eval_report_id 为演示评测编号常量；published_at 走列默认 now()（库稿 §3.1 口径）。
  INSERT INTO app.app_version(tenant_id, app_id, app_draft_id, app_code, version_no, draft_rev, candidate_sha256, snapshot, eval_report_id, published_by)
       VALUES ('demo',
               (SELECT id FROM app.application WHERE tenant_id='demo' AND code='satellite-planning-assistant'),
               (SELECT d.id FROM app.app_draft d WHERE d.tenant_id='demo' AND d.app_code='satellite-planning-assistant'),
               'satellite-planning-assistant', 1, 1, 'a29c1e28417ada928f1f4bbd93bd390a746d6103e17abef070fe5d8d2c5f9206', '{"appCode":"satellite-planning-assistant","type":"CHAT","name":"卫星规划助手","description":"从卫星与目标查询，到载荷约束、观测下传规划、人工确认、任务与结果的完整示例。","domain":"航天","bizCategory":"观测任务规划","tags":["对空","对地","下传"],"config":{"modelBinding":{"providerCode":"demo-provider","modelName":"demo-model"},"promptSource":"DIRECT","systemPrompt":"你是卫星规划助手，面向空间目标监测与地面目标遥感观测。先确认目标、日期、时间范围、观测星及载荷；区分空间目标SPACE、地面目标GROUND与地面站，下传不是对地成像。先查询目录和载荷，再按卫星规划操作指引组合规划工具。检查星历时效、载荷匹配、光照、云量、分辨率和下传时序及容量，列出可用与排除窗口，给出推荐理由。任务提交、取消与确认选择具有副作用，必须人工确认；提交前冻结计划，不得把模型建议当审批通过。每次回答先给结论，再给窗口表、工具调用与规则判定。无窗口、信息缺失和约束不满足要明确说明与建议。","maxTurns":200},"definition":{},"versionNo":1,"draftRev":1,"candidateSha256":"a29c1e28417ada928f1f4bbd93bd390a746d6103e17abef070fe5d8d2c5f9206","appVersionId":"satellite-planning-assistant@1","interpreterVersion":"1.0","manifestPin":{"revision":1,"digest":"e68aa654066fbe3200d88094aa6317214aedc995bd3540b606ce768220d5a726"}}'::jsonb, 'demo-seed-eval-0001', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, version_no) DO NOTHING;
  INSERT INTO app.app_version(tenant_id, app_id, app_draft_id, app_code, version_no, draft_rev, candidate_sha256, snapshot, eval_report_id, published_by)
       VALUES ('demo',
               (SELECT id FROM app.application WHERE tenant_id='demo' AND code='user-governance-assistant'),
               (SELECT d.id FROM app.app_draft d WHERE d.tenant_id='demo' AND d.app_code='user-governance-assistant'),
               'user-governance-assistant', 1, 1, '2f53531358932d45f7affd57473d0b0575d0e4144268a46b154b9f7bf270ab4c', '{"appCode":"user-governance-assistant","type":"FLOW","name":"账号开通与角色授权助手","description":"从业务权限拉取、账号治理规则校验到审批执行及结果读回的账号管理流程。","domain":"用户管理","bizCategory":"USER_GOVERNANCE","tags":["账号开通","权限治理","流程"],"config":{"modelBinding":{"providerCode":"demo-provider","modelName":"demo-model"},"promptSource":"DIRECT","systemPrompt":"根据业务系统的当前身份、角色、权限和部门范围处理账号开通。先拉取事实，再按账号治理规则校验。生成变更计划后等待独立审批。审批恢复后重查发起人权限与目标修订，成功执行后读回业务账号和角色。缺事实或约束不满足时停止并说明原因。以Markdown输出流程、规则和结果，不以自然语言自述代替业务读回。","maxTurns":200},"definition":{"definitionVersion":"1.0","trigger":"CHAT","entry":"start","inputs":[{"key":"message","type":"STRING","required":true,"description":"账号开通与授权需求"}],"outputs":[{"key":"receipt","type":"JSON","description":"业务读回与审批回执"}],"nodes":[{"id":"start","type":"START","name":"接收账号申请","next":"identity"},{"id":"identity","type":"SKILL","name":"识别业务身份并拉取权限","skill":"um-permissions-pull","next":"prepare"},{"id":"prepare","type":"SKILL","name":"生成变更计划","skill":"um-request-prepare","next":"department"},{"id":"department","type":"APPROVAL","name":"部门负责人审批","next":"security"},{"id":"security","type":"CONDITION","name":"高权限安全复核","expression":"request.privileged == true","nextTrue":"security-approval","nextFalse":"execute"},{"id":"security-approval","type":"APPROVAL","name":"安全岗位复核","next":"execute"},{"id":"execute","type":"SKILL","name":"重查权限并执行业务变更","skill":"um-account-create","next":"readback"},{"id":"readback","type":"SKILL","name":"读回账号和角色","skill":"um-request-readback","next":"end"},{"id":"end","type":"END","name":"输出授权回执"}]},"versionNo":1,"draftRev":1,"candidateSha256":"2f53531358932d45f7affd57473d0b0575d0e4144268a46b154b9f7bf270ab4c","appVersionId":"user-governance-assistant@1","interpreterVersion":"1.0","manifestPin":{"revision":1,"digest":"ef15732cd5003a4a1eb8e556c0da116df1f328763edb60bb6870b44327e7a39d"}}'::jsonb, 'demo-seed-eval-0001', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, version_no) DO NOTHING;
  -- skill_reference：对外能力清单以“应用版本实际引用的技能”为准（ConfigReleaseRepository.skills 读取路径）。
  -- 列对照 V100+V102：tenant_id/app_code/app_version_no/app_version_id/skill_code/skill_version。
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'satellite-planning-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='satellite-planning-assistant' AND v.version_no=1),
               'planning-space-target-query', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'satellite-planning-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='satellite-planning-assistant' AND v.version_no=1),
               'planning-space-plan', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'satellite-planning-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='satellite-planning-assistant' AND v.version_no=1),
               'planning-task-submit', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'satellite-planning-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='satellite-planning-assistant' AND v.version_no=1),
               'planning-artifact-export', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-identity-resolve', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-permissions-pull', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-request-prepare', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-request-submit', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-approval-decide', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-account-create', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-role-assign', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  INSERT INTO app.skill_reference(tenant_id, app_code, app_version_no, app_version_id, skill_code, skill_version)
       VALUES ('demo', 'user-governance-assistant', 1,
               (SELECT v.id FROM app.app_version v WHERE v.tenant_id='demo' AND v.app_code='user-governance-assistant' AND v.version_no=1),
               'um-request-readback', 1)
       ON CONFLICT (tenant_id, app_code, app_version_no, skill_code) DO NOTHING;
  -- assistant_experience + 修订：integration_config_release.experience_rev NOT NULL FK 的前提（V113）。
  -- 演示体验声明照 V113 列形状落最小行；declaration_sha256 预计算常量。
  INSERT INTO app.assistant_experience(tenant_id, app_code, current_rev, status, created_by)
       VALUES ('demo', 'satellite-planning-assistant', 1, 'APPROVED', 'demo-seed')
       ON CONFLICT (tenant_id, app_code) DO NOTHING;
  INSERT INTO app.assistant_experience_revision(tenant_id, app_code, experience_rev, title, welcome_text, declaration_sha256, created_by)
       VALUES ('demo', 'satellite-planning-assistant', 1, '卫星规划助手', '演示工作区（demo 种子预置）。', '99fbbd5508e6c7cf2b3205ff8648948e96865be4ce4178b2543ec3b29993fff8', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, experience_rev) DO NOTHING;
  INSERT INTO app.assistant_experience(tenant_id, app_code, current_rev, status, created_by)
       VALUES ('demo', 'user-governance-assistant', 1, 'APPROVED', 'demo-seed')
       ON CONFLICT (tenant_id, app_code) DO NOTHING;
  INSERT INTO app.assistant_experience_revision(tenant_id, app_code, experience_rev, title, welcome_text, declaration_sha256, created_by)
       VALUES ('demo', 'user-governance-assistant', 1, '账号开通与角色授权助手', '演示工作区（demo 种子预置）。', '4ae9a099b12184145eca60888c56dd127d21abd4a47464e83b8e245ae1395768', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, experience_rev) DO NOTHING;
  -- integration_profile + 修订：integration_config_release.integration_profile_id/rev NOT NULL FK 的前提（V113）。
  -- environment='demo' 命中 CHECK ^[a-z][a-z0-9-]{0,31}$；mode=L1（演示接入级别）；凭据走演示无凭据口径（credential_policy_ref 空）。
  INSERT INTO app.integration_profile(tenant_id, profile_id, app_code, current_rev, status, created_by)
       VALUES ('demo', 'demo-integration-satellite', 'satellite-planning-assistant', 1, 'APPROVED', 'demo-seed')
       ON CONFLICT (tenant_id, profile_id) DO NOTHING;
  INSERT INTO app.integration_profile_revision(tenant_id, profile_id, profile_rev, biz_system_code, environment, mode, allowed_origins, context_schema, credential_policy_ref, experience_ref, declaration_sha256, created_by)
       VALUES ('demo', 'demo-integration-satellite', 1, 'satellite-demo', 'demo', 'L1', '[]'::jsonb, '{}'::jsonb, '', '{}'::jsonb, '66aec01bf3213dddbfe1ab54f010f4b446014833fe3286cec06f7f97e810fb71', 'demo-seed')
       ON CONFLICT (tenant_id, profile_id, profile_rev) DO NOTHING;
  INSERT INTO app.integration_profile(tenant_id, profile_id, app_code, current_rev, status, created_by)
       VALUES ('demo', 'demo-integration-governance', 'user-governance-assistant', 1, 'APPROVED', 'demo-seed')
       ON CONFLICT (tenant_id, profile_id) DO NOTHING;
  INSERT INTO app.integration_profile_revision(tenant_id, profile_id, profile_rev, biz_system_code, environment, mode, allowed_origins, context_schema, credential_policy_ref, experience_ref, declaration_sha256, created_by)
       VALUES ('demo', 'demo-integration-governance', 1, 'user-mgmt', 'demo', 'L1', '[]'::jsonb, '{}'::jsonb, '', '{}'::jsonb, '2888fed07f03a9a28bc49e5ba951dfe997020e52b963ecf9cc7028984c7c031f', 'demo-seed')
       ON CONFLICT (tenant_id, profile_id, profile_rev) DO NOTHING;
  -- §3.1 步骤2：workspace_manifest revision=1（V122；§3 示例资产化：metadata/model/skills/plugins/rules/
  -- memoryPolicy/contextPolicy/permission 全分区；digest=sha256(canonical(manifest)) 预计算常量，内容寻址口径与
  -- WorkspaceManifests.put 一致；governance 另带 flowRef='flow-user-governance'（schema 校验注记①：无流程引用省略键））。
  -- 不可变修订链（V122 触发器）：只 INSERT，不 UPDATE/DELETE。
  INSERT INTO app.workspace_manifest(tenant_id, app_code, revision, manifest, digest, created_by)
       VALUES ('demo', 'satellite-planning-assistant', 1, '{"metadata":{"name":"卫星规划助手","category":"算法重组","tags":["演示","卫星"]},"model":{"defaultBinding":{"providerCode":"demo-provider","modelName":"demo-model"},"inferencePolicy":{"budget":{"maxTurns":40,"maxModelCalls":120}}},"skills":{"guideRefs":["satellite-planning-guide@1"],"toolGrants":[{"skillCode":"planning-space-target-query","version":1},{"skillCode":"planning-space-plan","version":1},{"skillCode":"planning-task-submit","version":1,"businessIntentInput":"planId"},{"skillCode":"planning-artifact-export","version":1}]},"plugins":[{"pluginCode":"demo-capability-pack","version":"1.0.0","capabilities":["SKILL:planning-space-target-query"]}],"rules":["satellite-cloud-limit@1"],"memoryPolicy":{"kinds":["PREFERENCE","EXPERIENCE"],"readScopes":["PRIVATE"],"writeScopes":["PRIVATE"],"topK":5},"contextPolicy":{"maxTurns":200,"contextBudget":60000,"compactionThreshold":0.85,"historyWindow":20},"permission":{"roles":["USER","OPERATOR"]}}'::jsonb, 'e68aa654066fbe3200d88094aa6317214aedc995bd3540b606ce768220d5a726', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, revision) DO NOTHING;
  INSERT INTO app.workspace_manifest(tenant_id, app_code, revision, manifest, digest, created_by)
       VALUES ('demo', 'user-governance-assistant', 1, '{"metadata":{"name":"账号开通与角色授权助手","category":"算法重组","tags":["演示","账号"]},"model":{"defaultBinding":{"providerCode":"demo-provider","modelName":"demo-model"},"inferencePolicy":{"budget":{"maxTurns":40,"maxModelCalls":120}}},"skills":{"guideRefs":["user-governance-guide@1"],"toolGrants":[{"skillCode":"um-identity-resolve","version":1},{"skillCode":"um-permissions-pull","version":1},{"skillCode":"um-request-prepare","version":1},{"skillCode":"um-request-submit","version":1,"businessIntentInput":"requestId"},{"skillCode":"um-approval-decide","version":1,"businessIntentInput":"requestId"},{"skillCode":"um-account-create","version":1,"businessIntentInput":"requestId"},{"skillCode":"um-role-assign","version":1,"businessIntentInput":"requestId"},{"skillCode":"um-request-readback","version":1}]},"rules":["um-permission-match@1","um-account-revision@1"],"memoryPolicy":{"kinds":["PREFERENCE","EXPERIENCE"],"readScopes":["PRIVATE"],"writeScopes":["PRIVATE"],"topK":5},"contextPolicy":{"maxTurns":200,"contextBudget":60000,"compactionThreshold":0.85,"historyWindow":20},"flowRef":"flow-user-governance","permission":{"roles":["USER"]}}'::jsonb, 'ef15732cd5003a4a1eb8e556c0da116df1f328763edb60bb6870b44327e7a39d', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, revision) DO NOTHING;
  -- §3.1 步骤3：integration_config_release 直接落 APPROVED 终态（等价真实链 VALIDATED→APPROVED；
  -- DRAFT/VALIDATED 为无运行时读者的瞬态，种子不经过）。manifest_revision=1/manifest_digest 成对（V122 成对 CHECK）；
  -- digest=预计算常量（真实链公式 IntegrationConfigReleases.digest）；release_id 为 demo 专用编码（§2 幂等键）。
  INSERT INTO app.integration_config_release(tenant_id, release_id, app_code, app_version, candidate_sha256,
         experience_rev, integration_profile_id, integration_profile_rev, connection_profile_refs,
         activation_scope, status, digest, created_by, manifest_revision, manifest_digest)
       VALUES ('demo', 'demo-release-satellite-planning-001', 'satellite-planning-assistant', 1, 'a29c1e28417ada928f1f4bbd93bd390a746d6103e17abef070fe5d8d2c5f9206', 1, 'demo-integration-satellite', 1, '[]'::jsonb,
               'demo', 'APPROVED', 'a436fd333b3faf1239313a2ebefa41519f1160fc45a1c206b224f96a288fa7ec', 'demo-seed', 1, 'e68aa654066fbe3200d88094aa6317214aedc995bd3540b606ce768220d5a726')
       ON CONFLICT (tenant_id, release_id) DO NOTHING;
  INSERT INTO app.integration_config_release(tenant_id, release_id, app_code, app_version, candidate_sha256,
         experience_rev, integration_profile_id, integration_profile_rev, connection_profile_refs,
         activation_scope, status, digest, created_by, manifest_revision, manifest_digest)
       VALUES ('demo', 'demo-release-user-governance-001', 'user-governance-assistant', 1, '2f53531358932d45f7affd57473d0b0575d0e4144268a46b154b9f7bf270ab4c', 1, 'demo-integration-governance', 1, '[]'::jsonb,
               'demo', 'APPROVED', 'b3bae251d9a0c4a14860da351335beb3a27b33d52c2131d25b779b2154f9af80', 'demo-seed', 1, 'ef15732cd5003a4a1eb8e556c0da116df1f328763edb60bb6870b44327e7a39d')
       ON CONFLICT (tenant_id, release_id) DO NOTHING;
  -- §3.1 步骤4：激活指针 upsert（ON CONFLICT (tenant_id,app_code,scope) DO NOTHING，activation_rev 取默认 1），
  -- 随后发布置 ACTIVE（WHERE status='APPROVED'）。重复执行：发布已 ACTIVE（更新 0 行）、指针已存在（DO NOTHING）——全 no-op。
  -- 作用域 scope='demo'：演示启动脚本（G5-0“真实后端+demo 种子”）按 active(actor,appCode,'demo') 读取；
  -- 旧 ACTIVE 退位语句照 activate() 原样保留（demo 每工作区单发布，实际不触发）。
  INSERT INTO app.integration_config_release_activation(tenant_id, app_code, scope, release_id, activated_by)
       VALUES ('demo', 'satellite-planning-assistant', 'demo', 'demo-release-satellite-planning-001', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, scope) DO NOTHING;
  UPDATE app.integration_config_release SET status = 'SUPERSEDED', updated_at = now()
   WHERE tenant_id = 'demo' AND app_code = 'satellite-planning-assistant' AND activation_scope = 'demo'
     AND release_id <> 'demo-release-satellite-planning-001' AND status = 'ACTIVE';
  UPDATE app.integration_config_release SET status = 'ACTIVE', updated_at = now()
   WHERE tenant_id = 'demo' AND release_id = 'demo-release-satellite-planning-001' AND status = 'APPROVED';
  INSERT INTO app.integration_config_release_activation(tenant_id, app_code, scope, release_id, activated_by)
       VALUES ('demo', 'user-governance-assistant', 'demo', 'demo-release-user-governance-001', 'demo-seed')
       ON CONFLICT (tenant_id, app_code, scope) DO NOTHING;
  UPDATE app.integration_config_release SET status = 'SUPERSEDED', updated_at = now()
   WHERE tenant_id = 'demo' AND app_code = 'user-governance-assistant' AND activation_scope = 'demo'
     AND release_id <> 'demo-release-user-governance-001' AND status = 'ACTIVE';
  UPDATE app.integration_config_release SET status = 'ACTIVE', updated_at = now()
   WHERE tenant_id = 'demo' AND release_id = 'demo-release-user-governance-001' AND status = 'APPROVED';
  -- §4.3：规则资产 3 条（rule_asset 主档 ENABLED + rule_asset_version PUBLISHED；V123）。
  -- content={trigger,action,requirement}；sha256=sha256(canonical(content)) 预计算常量；
  -- PUBLISHED 版本受不可变触发器保护（只 INSERT）。
  INSERT INTO app.rule_asset(tenant_id, code, name, nature, scope, status, current_version_no, updated_at)
       VALUES ('demo', 'satellite-cloud-limit', '云量约束', 'ENFORCED', '{"app":"satellite-planning-assistant"}'::jsonb, 'ENABLED', 1, now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO app.rule_asset_version(tenant_id, rule_code, version_no, content, sha256, status, published_by, published_at)
       VALUES ('demo', 'satellite-cloud-limit', 1, '{"trigger":{"event":"planning.plan.generate","description":"规划生成动作执行前（planning-space-plan / planning-ground-plan）"},"action":{"type":"BLOCK","description":"云量超限阻断并提示：云量超过上限的观测窗口不得进入推荐，提示用户调整云量上限或改期"},"requirement":{"field":"cloudLimit","maxPercent":30,"appliesTo":"GROUND 观测窗口","message":"窗口云量超过上限，已按规则阻断"}}'::jsonb, '91d541427cf80c82b2dd26d177434305f98d3ebe609f072aa365c5fbb70c71c9', 'PUBLISHED', 'demo-seed', now())
       ON CONFLICT (tenant_id, rule_code, version_no) DO NOTHING;
  INSERT INTO app.rule_asset(tenant_id, code, name, nature, scope, status, current_version_no, updated_at)
       VALUES ('demo', 'um-permission-match', '审批执行权限匹配', 'ENFORCED', '{"app":"user-governance-assistant"}'::jsonb, 'ENABLED', 1, now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO app.rule_asset_version(tenant_id, rule_code, version_no, content, sha256, status, published_by, published_at)
       VALUES ('demo', 'um-permission-match', 1, '{"trigger":{"event":"governance.approval.execute","description":"审批执行前（um-approval-decide / um-account-create 提交前）"},"action":{"type":"REJECT","description":"权限不匹配拒绝提交：发起人或执行人缺少所需业务权限时阻断本次申请"},"requirement":{"permissions":["um.request.approve"],"onMismatch":"拒绝提交并提示缺失权限（替换原本体条件语义）"}}'::jsonb, 'df63676e9cce9ed6c89a3f2db001bf7e06f44b288362b808c634266e3980fc3f', 'PUBLISHED', 'demo-seed', now())
       ON CONFLICT (tenant_id, rule_code, version_no) DO NOTHING;
  INSERT INTO app.rule_asset(tenant_id, code, name, nature, scope, status, current_version_no, updated_at)
       VALUES ('demo', 'um-account-revision', '账号数据乐观并发提示', 'GUIDANCE', '{"app":"user-governance-assistant"}'::jsonb, 'ENABLED', 1, now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO app.rule_asset_version(tenant_id, rule_code, version_no, content, sha256, status, published_by, published_at)
       VALUES ('demo', 'um-account-revision', 1, '{"trigger":{"event":"governance.account.submit","description":"账号变更提交前（um-request-submit）"},"action":{"type":"PROMPT","description":"提示账号 revision 过期时先重查再提交"},"requirement":{"check":"account.revision","message":"账号数据已变更（revision 过期），请重新读取账号后再次提交"}}'::jsonb, 'dceab4e7c731eca9f33bd25ca61b2921edb1d6d2c93a4fdaab7529805aa16be7', 'PUBLISHED', 'demo-seed', now())
       ON CONFLICT (tenant_id, rule_code, version_no) DO NOTHING;

END
$demo_seed$;
