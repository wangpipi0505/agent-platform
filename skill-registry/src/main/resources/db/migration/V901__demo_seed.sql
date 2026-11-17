-- skill V901：演示种子（demo-seed）——台账 M5-1（阶段 5）。
-- 演示种子，生产经 placeholder 跳过；幂等 ON CONFLICT DO NOTHING。
-- 数据权威：contracts/demo-seed-v901-design.md（2026-11-13 用户评审补正版）§2 种子范围总表、§3 manifest 示例
-- （冻结 manifest.schema.json 已过 ajv 校验）、§3.1 ACTIVE 发布与激活指针、§4 两场景数据映射表；
-- 字段逐一对照数据库稿《05-数据库设计-20261112》§4（M5-1 领卡须知），并以各模块既有迁移（V100-V123/V300-V322/V200-V231）实际列为准。
-- 本体线剔除（C0-1）：不含 ontology releaseRef、语义绑定、planning-ontology-* / um-ontology-check 本体对象；
-- 业务侧数据（departments/actors/accounts）不入平台种子；model-gateway 不动（红线）：manifest.model 仅引用编码。
-- 幂等（A16 验收项“迁移可重复”）：全部 INSERT ... ON CONFLICT DO NOTHING；状态推进 UPDATE 带 WHERE 守卫，
-- 重复执行零行变化；只增不改（不触碰既有行）；demo 租户隔离（全部行 tenant_id='demo'）。
-- 种子行清单：skill+skill_version 14（GUIDE 2 + TOOL 12 代表集）/ plugin_source 1 /
-- plugin 1 / plugin_version 1 / plugin_grant 1（demo-capability-pack 一套）。

-- 生产跳过开关（数据库稿 §6“生产经 Flyway placeholder 跳过”的可执行实现，占位符接线属 G5-0）：
-- 以 PostgreSQL 会话/库级自定义设置 agent.demo_seed_skip 控制，未设置=执行（默认演示/验证库行为不变）；
-- 生产库执行 ALTER DATABASE <prod> SET agent.demo_seed_skip = 'on' 后，本迁移整段 no-op。
DO $demo_seed$
BEGIN
  IF coalesce(current_setting('agent.demo_seed_skip', true), 'off') = 'on' THEN
    RAISE NOTICE 'V901 demo seed skipped (agent.demo_seed_skip=on)';
    RETURN;
  END IF;

  -- GUIDE 2 + TOOL 12（§4.1）：TOOL 契约照搬 mock 契约形状（inputSchema/outputSchema/endpoint 改真实形态；
  -- 本体类 planning-ontology-* 与 um-ontology-check 按 C0-1 不入种子），副作技能 execution.approval.businessIntentInput
  -- 声明意图输入字段（发布预检红线：非 null 必填）；GUIDE 契约五字段 {name,description,whenToUse,modelInvocable,content}
  -- （V321 形状）。sha256=sha256(canonical(contract))、semantic_digest=SkillDigest 口径，均预计算常量；
  -- 行状态直接 PUBLISHED（等价真实链 DRAFT→PUBLISHED 终态），发布指针经下方守卫 UPDATE 补挂（可重复）。
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'satellite-planning-guide', '卫星规划操作指引', 'GUIDE', '卫星规划', '["演示","指引"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'satellite-planning-guide', 1, 'PUBLISHED', '{"name":"卫星规划操作指引","description":"卫星规划行为知识：查询、约束、规划、下传校验、确认与任务提交的组合方式。","whenToUse":"用户提出观测需求需要生成观测规划、提交任务或读回结果时。","modelInvocable":false,"content":"# 卫星规划操作指引\n\n## 何时使用\n用户提出观测需求（目标、日期、时间范围、云量约束）需要生成观测规划、提交任务或读回结果时。\n\n## 规划怎么组合\n1. 查目标：planning-space-target-query 确定目标、可用载荷与窗口概况；\n2. 定约束：向用户确认云量上限（satellite-cloud-limit 规则默认 30%）、时间窗与载荷偏好；\n3. 生成规划：planning-space-plan 返回观测窗口与排除项，检查星历时效、载荷匹配、光照与分辨率；\n4. 下传校验：窗口含数据下传时先做站星下传校验，避免看得见传不回；\n5. 推荐与确认：给出推荐窗口并说明评分依据，等待用户确认后再提交；\n6. 提交任务：planning-task-submit（副作用动作，按 planId 幂等重试，提交前冻结计划）；\n7. 结果读回：任务结果以读回为准，规划产出用 planning-artifact-export 导出归档。\n\n## 边界与红线\n- 云量超限的窗口不得推荐；被 satellite-cloud-limit 规则阻断时如实说明原因，不绕过规则；\n- 任务提交必须来自已确认的规划；不伪造任务状态，一切以工具读回为准。"}'::jsonb, '82b27b005243cc96889f82ad75361b7f59010c8b44ddb0e2de1f05468c083240', 'dc678358ddb149637c90929f20dc11ef33d7f4e7529f5acb3916e33c597d7d6b', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'user-governance-guide', '账号治理操作指引', 'GUIDE', '用户治理', '["演示","指引"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'user-governance-guide', 1, 'PUBLISHED', '{"name":"账号治理操作指引","description":"账号开通四步操作知识：拉事实、出计划、双人审批、执行读回。","whenToUse":"用户提出账号开通、角色授予或授权查询需求时。","modelInvocable":false,"content":"# 账号治理操作指引\n\n## 何时使用\n用户提出账号开通、角色授予或授权查询需求时。\n\n## 账号开通四步\n1. 拉事实：um-identity-resolve 与 um-permissions-pull 取当前业务账号、部门与权限范围；\n2. 出计划：um-request-prepare 生成账号变更计划，明确账号、角色、有效期与互斥角色检查结论；\n3. 双人审批：um-request-submit 提交申请，um-approval-decide 由独立审批人审批（高权限申请需安全复核）；\n4. 执行读回：审批通过后 um-account-create 与 um-role-assign 执行，um-request-readback 读回账号与授权。\n\n## 边界与红线\n- 申请人不能自审批；互斥角色与跨部门越权一律阻断（um-permission-match 规则）；\n- 账号 revision 过期先重查再提交（um-account-revision 指引）；执行结果以业务读回为准，不以自然语言自述代替业务读回。"}'::jsonb, 'd0aacee9b5fa6e4677a310092f6e5674cbda3d17ac1fdf3d8d5a02e2cf024e97', '6f1d493f3907b4d769d3a988640d1c613870115c36fae1314e308cadd43e3fdd', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'planning-space-target-query', '空间目标筛选', 'TOOL', '卫星规划', '["演示","查询"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'planning-space-target-query', 1, 'PUBLISHED', '{"type":"HTTP","description":"空间目标筛选，按目标编号、时间范围与云量约束返回候选观测目标与可用窗口概况。","endpoint":"http://planning.demo.local/api/v1/space/targets/query","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"targetId":{"type":"string","description":"空间目标数字ID或地面目标GT编码"},"satelliteId":{"type":"integer","description":"观测卫星目录ID"},"startTime":{"type":"string","description":"查询开始时间（ISO-8601）"},"endTime":{"type":"string","description":"查询结束时间（ISO-8601）"},"cloudLimit":{"type":"number","description":"云量上限百分比（0-100）"}}},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"scope":["satellite-planning-demo"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, 'cabb732da71a1dfa00a74711e661f3f694c10518a0eab2af78036281ac89d7bd', 'e8e89359e317a67afee9b65956c8063583bd00125850543b1e2ee87418400410', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'planning-space-plan', '对空观测窗口规划', 'TOOL', '卫星规划', '["演示","规划"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'planning-space-plan', 1, 'PUBLISHED', '{"type":"HTTP","description":"对空观测窗口规划，输入目标与约束，返回可用窗口、排除窗口与推荐理由。","endpoint":"http://planning.demo.local/api/v1/space/visibility/plan","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"targetId":{"type":"string","description":"空间目标数字ID"},"startTime":{"type":"string","description":"规划开始时间（ISO-8601）"},"endTime":{"type":"string","description":"规划结束时间（ISO-8601）"},"cloudLimit":{"type":"number","description":"云量上限百分比（0-100，受 satellite-cloud-limit 规则约束）"}}},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"scope":["satellite-planning-demo"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, 'c1a3fe294978c175d370acc963eca95f2e5df08c990fa6a53e82ccae2468b81b', '8e2c30ad9db1b133a10c9d94b7ad04cf4880fe4ef94c3c44a98a1d8c9854dac4', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'planning-task-submit', '观测任务提交', 'TOOL', '卫星规划', '["演示","写操作"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'planning-task-submit', 1, 'PUBLISHED', '{"type":"HTTP","description":"观测任务提交：冻结已确认规划并生成任务，副作用动作，按 planId 幂等重试。","endpoint":"http://planning.demo.local/api/v1/space/visibility/tasks","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"planId":{"type":"string","description":"已确认规划的标识（业务意图输入，幂等键来源）"},"targetId":{"type":"string","description":"空间目标数字ID"},"plan":{"type":"object","description":"已冻结的规划、窗口、下传与约束"}}},"outputSchema":{"type":"object"},"riskLevel":"CONFIRM_REQUIRED","execution":{"readonly":false,"sideEffect":true,"idempotent":true,"cancellable":false,"approval":{"mode":"SINGLE_STEP","businessIntentInput":"planId"},"confirmRoles":["OPERATOR"]},"guard":{"roles":["USER","OPERATOR"],"attributes":{"scope":["satellite-planning-demo"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, 'ffbd67481ad217ddc8895cf16ca7a39e0c859e5becbc29f536cb2c98c440f00a', 'adbadcbc9b4c45fc40a4c77d766c3d1667d2b5ab8920f2b4bf597027efab917d', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'planning-artifact-export', '导出规划报告与窗口CSV', 'TOOL', '卫星规划', '["演示","导出"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'planning-artifact-export', 1, 'PUBLISHED', '{"type":"HTTP","description":"导出规划报告与观测窗口CSV归档文件，只读动作。","endpoint":"http://planning.demo.local/api/v1/planning/artifacts/export","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"planId":{"type":"string","description":"规划标识"}}},"outputSchema":{"type":"object","properties":{"files":{"type":"array","description":"导出文件清单（名称/媒体类型/大小）"}}},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"scope":["satellite-planning-demo"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, '1194e4e780612b99d8661b26db05c049f9031822a80ac00cdbf1bcb84b536bf7', 'b4e2179c5febc040c7f8fcbe2f170704657c67911f5baf5a073e0906f962a78f', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-identity-resolve', '识别当前业务账号', 'TOOL', '用户治理', '["演示","查询"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-identity-resolve', 1, 'PUBLISHED', '{"type":"HTTP","description":"识别当前业务账号与所属部门，返回业务身份快照。","endpoint":"http://user-mgmt.demo.local/um/identity-resolve","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"请求标识"}}},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, '3735fb888a8a29e4eec98763335eae28c323b9ea59fa222f977c40a9574cf53d', 'bbc9b0d4c65503311e6faf20eb78b5a56337534a86eb7ec22315d7d68aade4fd', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-permissions-pull', '拉取业务角色与权限', 'TOOL', '用户治理', '["演示","查询"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-permissions-pull', 1, 'PUBLISHED', '{"type":"HTTP","description":"拉取业务系统的角色、权限与部门范围，作为账号治理的事实基础。","endpoint":"http://user-mgmt.demo.local/um/permissions","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"请求标识"},"departmentId":{"type":"string","description":"部门ID"}}},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, '54d3be25c15589fe6f132e0359a86e8df9fe3210338662aa55a21805afe7b6b2', '469143cc10f66373c063b22defe17761980be442fe8d73ee1b68215235526d25', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-request-prepare', '生成账号变更计划', 'TOOL', '用户治理', '["演示","查询"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-request-prepare', 1, 'PUBLISHED', '{"type":"HTTP","description":"根据开通需求生成账号变更计划（账号、角色、有效期与互斥检查结论）。","endpoint":"http://user-mgmt.demo.local/um/requests/prepare","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"请求标识"},"accountId":{"type":"string","description":"业务账号ID"},"departmentId":{"type":"string","description":"部门ID"}}},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, 'c5f0b573f7e988a16d9471e70347bfdf2ee0fa05026a045837cccaf3b161269c', 'f24568cc0df584e6cbd942c44a5a2568d26dd5fd2a43077d08ae8fe4efdf45d4', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-request-submit', '提交账号申请', 'TOOL', '用户治理', '["演示","写操作"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-request-submit', 1, 'PUBLISHED', '{"type":"HTTP","description":"提交账号开通申请进入审批流，副作用动作，按 requestId 幂等重试。","endpoint":"http://user-mgmt.demo.local/um/requests/submit","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"申请标识（业务意图输入，幂等键来源）"}}},"outputSchema":{"type":"object"},"riskLevel":"CONFIRM_REQUIRED","execution":{"readonly":false,"sideEffect":true,"idempotent":true,"cancellable":false,"approval":{"mode":"SINGLE_STEP","businessIntentInput":"requestId"},"confirmRoles":["OPERATOR"]},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, '30163c7a75c1c30bea0ddeec8dc52da6042879e7d9ce43e0fbd6e6e0c7cd1bab', 'bb9d0c6e79a7965f117a4eee45757efce611ba4e8c6e5229a87b7089f67a76dc', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-approval-decide', '审批账号申请', 'TOOL', '用户治理', '["演示","写操作"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-approval-decide', 1, 'PUBLISHED', '{"type":"HTTP","description":"审批账号开通申请（同意/驳回），副作用动作，审批人须独立于申请人。","endpoint":"http://user-mgmt.demo.local/um/approvals/decide","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"申请标识（业务意图输入，幂等键来源）"},"decision":{"type":"string","description":"审批结论：APPROVE / REJECT"}}},"outputSchema":{"type":"object"},"riskLevel":"CONFIRM_REQUIRED","execution":{"readonly":false,"sideEffect":true,"idempotent":true,"cancellable":false,"approval":{"mode":"SINGLE_STEP","businessIntentInput":"requestId"},"confirmRoles":["APPROVER"]},"guard":{"roles":["APPROVER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, 'b9d72133564f3d85b95fd519d72dfe1a01b91c2f8e7f4f5643568486305db44a', '230f4b9bf4752eeb8ec54719f75d773ed3ffde30ba55ed400e22d9fed8de9610', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-account-create', '创建业务账号', 'TOOL', '用户治理', '["演示","写操作"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-account-create', 1, 'PUBLISHED', '{"type":"HTTP","description":"审批通过后创建业务账号，副作用动作，执行前按 um-permission-match 规则复核权限。","endpoint":"http://user-mgmt.demo.local/um/accounts","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"申请标识（业务意图输入，幂等键来源）"},"accountId":{"type":"string","description":"业务账号ID"},"departmentId":{"type":"string","description":"部门ID"}}},"outputSchema":{"type":"object"},"riskLevel":"CONFIRM_REQUIRED","execution":{"readonly":false,"sideEffect":true,"idempotent":true,"cancellable":false,"approval":{"mode":"SINGLE_STEP","businessIntentInput":"requestId"},"confirmRoles":["OPERATOR"]},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, '4eece2a99df7ea7f9b55e505f8f0ba7a472019a193c74746aedd5b3a06e96582', '3e05e2cf4fb4c40c854837943cd8696ffa7c2bd4e3e58d63178db20f5428694e', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-role-assign', '授予业务角色', 'TOOL', '用户治理', '["演示","写操作"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-role-assign', 1, 'PUBLISHED', '{"type":"HTTP","description":"为业务账号授予角色（受互斥角色与跨部门约束），副作用动作。","endpoint":"http://user-mgmt.demo.local/um/roles/assign","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"申请标识（业务意图输入，幂等键来源）"},"accountId":{"type":"string","description":"业务账号ID"},"roleCode":{"type":"string","description":"业务角色编码"}}},"outputSchema":{"type":"object"},"riskLevel":"CONFIRM_REQUIRED","execution":{"readonly":false,"sideEffect":true,"idempotent":true,"cancellable":false,"approval":{"mode":"SINGLE_STEP","businessIntentInput":"requestId"},"confirmRoles":["OPERATOR"]},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, '471a01ab8cef3fbe09b96eb9cac487eb4a37f55bd7b3ba50caa22568b707a126', '630131561985d58b012b9e988f160be4ec5d510edcd807f8cabb0322c8c23e26', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  INSERT INTO skill.skill(tenant_id, code, name, kind, category, tags, status, updated_at)
       VALUES ('demo', 'um-request-readback', '读回账号与授权', 'TOOL', '用户治理', '["演示","查询"]'::jsonb, 'PUBLISHED', now())
       ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.skill_version(tenant_id, skill_code, version_no, status, contract, sha256, semantic_digest, published_by, published_at)
       VALUES ('demo', 'um-request-readback', 1, 'PUBLISHED', '{"type":"HTTP","description":"执行完成后读回业务账号与授权结果，作为对用户的最终答案来源。","endpoint":"http://user-mgmt.demo.local/um/requests/readback","authRef":"","authScheme":"NONE","httpMethod":"POST","invocationKind":"SYNC","timeoutMs":30000,"inputSchema":{"type":"object","properties":{"requestId":{"type":"string","description":"申请标识"}}},"outputSchema":{"type":"object"},"riskLevel":"READ_ONLY","execution":{"readonly":true,"sideEffect":false,"idempotent":true,"cancellable":false},"guard":{"roles":["USER","OPERATOR"],"attributes":{"bizSystem":["user-mgmt"]}},"errorSemantics":[],"returnSpec":{},"outputMaxBytes":1048576,"reconciliation":{}}'::jsonb, 'c98cd53d9a899530944a05a2e3473a8e480c310c56f1adf8811f106b962ad04a', '006071949f10610ab84e18646eb5ff972ef37099f25194ee5ac185e9f21213e9', 'demo-seed', now())
       ON CONFLICT (tenant_id, skill_code, version_no) DO NOTHING;
  -- 发布指针补挂（照 SkillCatalog.publish 的 current_version_id 推进；WHERE 守卫使重复执行 no-op）。
  UPDATE skill.skill s SET current_version_id = sv.id, updated_at = now()
     FROM skill.skill_version sv
    WHERE sv.tenant_id = s.tenant_id AND sv.skill_code = s.code AND sv.version_no = 1
      AND s.tenant_id = 'demo' AND s.current_version_id IS NULL;
  -- 插件链一套（§4.4 route1 能力包；V321 五表）：来源 CAPABILITY_PACKAGE + 插件 1.0.0 + 授权 WORKSPACE。
  -- capabilities 元素 {type:'SKILL',ref,digest} 过 skill.valid_plugin_capabilities 形状 CHECK；digest=被引技能
  -- 版本 sha256 预计算常量（内容寻址与 skill_version 一致）。版本快照与授权身份受不可变触发器保护（只 INSERT）。
  INSERT INTO skill.plugin_source(tenant_id, code, name, source_type, location, status, created_by)
     VALUES ('demo', 'demo-capability-source', '演示能力包来源（route1）', 'CAPABILITY_PACKAGE', 'demo://capability-pack/route1', 'ACTIVE', 'demo-seed')
     ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.plugin(tenant_id, code, name, source_id, status, created_by)
     VALUES ('demo', 'demo-capability-pack', '演示能力包',
             (SELECT id FROM skill.plugin_source WHERE tenant_id='demo' AND code='demo-capability-source'), 'ACTIVE', 'demo-seed')
     ON CONFLICT (tenant_id, code) DO NOTHING;
  INSERT INTO skill.plugin_version(tenant_id, plugin_id, source_id, version, manifest, manifest_digest, capabilities, status, enabled_at)
     VALUES ('demo',
             (SELECT id FROM skill.plugin WHERE tenant_id='demo' AND code='demo-capability-pack'),
             (SELECT id FROM skill.plugin_source WHERE tenant_id='demo' AND code='demo-capability-source'),
             '1.0.0', '{"pluginCode":"demo-capability-pack","version":"1.0.0","sourceType":"CAPABILITY_PACKAGE","description":"演示能力包（route1）：随包分发卫星规划只读查询技能与使用指引。","capabilities":["SKILL:planning-space-target-query"]}'::jsonb, '0b42a364c4538740d48eeb651ae014c0aee33b70ef4dc3ad79fbc1d07d9c7ced',
             '[{"type":"SKILL","ref":"planning-space-target-query","digest":"cabb732da71a1dfa00a74711e661f3f694c10518a0eab2af78036281ac89d7bd"}]'::jsonb, 'ENABLED', now())
     ON CONFLICT (tenant_id, plugin_id, version) DO NOTHING;
  INSERT INTO skill.plugin_grant(plugin_version_id, grantee_type, grantee_code, capabilities, status, granted_by)
     VALUES ((SELECT pv.id FROM skill.plugin_version pv
               JOIN skill.plugin p ON p.id = pv.plugin_id
              WHERE pv.tenant_id='demo' AND p.code='demo-capability-pack' AND pv.version='1.0.0'),
             'WORKSPACE', 'satellite-planning-assistant', '[{"type":"SKILL","ref":"planning-space-target-query","digest":"cabb732da71a1dfa00a74711e661f3f694c10518a0eab2af78036281ac89d7bd"}]'::jsonb, 'GRANTED', 'demo-seed')
     ON CONFLICT (plugin_version_id, grantee_type, grantee_code) DO NOTHING;
  UPDATE skill.plugin p SET latest_version_id = pv.id, updated_at = now()
    FROM skill.plugin_version pv
   WHERE pv.tenant_id = p.tenant_id AND pv.plugin_id = p.id AND pv.version = '1.0.0'
     AND p.tenant_id = 'demo' AND p.latest_version_id IS NULL;

END
$demo_seed$;
