-- G5-0 链头验证（升级后 / 回退前的正向态）。
-- 断言：三模块 history 顶版本 = V901（链尾演示种子）；无失败迁移；
--       V901 种子在案：engine 2 / app 38 / skill 32 行（tenant_id='demo'）；
--       激活指针 active() 口径每工作区 1 条、release.manifest_digest 与 workspace_manifest.digest 一致。
-- 输出：check|detail|actual|expect|pass（脚本以行尾 false 判失败）。

-- 1) 顶版本
with tops as (
    select 'engine' as module, (select version from engine.flyway_schema_history order by installed_rank desc limit 1) as top_version
    union all
    select 'app',  (select version from app.flyway_schema_history  order by installed_rank desc limit 1)
    union all
    select 'skill',(select version from skill.flyway_schema_history order by installed_rank desc limit 1)
)
select 'head_top_version', module, coalesce(top_version,'(none)'), '901', (top_version = '901')
  from tops order by module;

-- 2) 无失败迁移
select 'failed_migrations', 'engine+app+skill',
       (select count(*) from engine.flyway_schema_history where not success)
     + (select count(*) from app.flyway_schema_history where not success)
     + (select count(*) from skill.flyway_schema_history where not success),
       0,
       ((select count(*) from engine.flyway_schema_history where not success)
     + (select count(*) from app.flyway_schema_history where not success)
     + (select count(*) from skill.flyway_schema_history where not success)) = 0;

-- 3) V901 种子行数（种子清单见各模块 V901__demo_seed.sql 头注）
select 'seed_engine_rows', 'engine.memory_entry(tenant=demo)',
       (select count(*) from engine.memory_entry where tenant_id='demo'), 2,
       ((select count(*) from engine.memory_entry where tenant_id='demo') = 2);

select 'seed_app_rows', 'app 13 表 tenant=demo 求和',
       (select count(*) from app.application where tenant_id='demo')
     + (select count(*) from app.app_draft where tenant_id='demo')
     + (select count(*) from app.app_version where tenant_id='demo')
     + (select count(*) from app.skill_reference where tenant_id='demo')
     + (select count(*) from app.assistant_experience where tenant_id='demo')
     + (select count(*) from app.assistant_experience_revision where tenant_id='demo')
     + (select count(*) from app.integration_profile where tenant_id='demo')
     + (select count(*) from app.integration_profile_revision where tenant_id='demo')
     + (select count(*) from app.workspace_manifest where tenant_id='demo')
     + (select count(*) from app.integration_config_release where tenant_id='demo')
     + (select count(*) from app.integration_config_release_activation where tenant_id='demo')
     + (select count(*) from app.rule_asset where tenant_id='demo')
     + (select count(*) from app.rule_asset_version where tenant_id='demo'),
       38,
       ((select count(*) from app.application where tenant_id='demo')
     + (select count(*) from app.app_draft where tenant_id='demo')
     + (select count(*) from app.app_version where tenant_id='demo')
     + (select count(*) from app.skill_reference where tenant_id='demo')
     + (select count(*) from app.assistant_experience where tenant_id='demo')
     + (select count(*) from app.assistant_experience_revision where tenant_id='demo')
     + (select count(*) from app.integration_profile where tenant_id='demo')
     + (select count(*) from app.integration_profile_revision where tenant_id='demo')
     + (select count(*) from app.workspace_manifest where tenant_id='demo')
     + (select count(*) from app.integration_config_release where tenant_id='demo')
     + (select count(*) from app.integration_config_release_activation where tenant_id='demo')
     + (select count(*) from app.rule_asset where tenant_id='demo')
     + (select count(*) from app.rule_asset_version where tenant_id='demo')) = 38;

-- 注：skill.plugin_grant 无 tenant_id（授权走 grantee_type/grantee_code），按挂靠的 demo 插件版本计数。
select 'seed_skill_rows', 'skill 6 表 tenant=demo 求和',
       (select count(*) from skill.skill where tenant_id='demo')
     + (select count(*) from skill.skill_version where tenant_id='demo')
     + (select count(*) from skill.plugin where tenant_id='demo')
     + (select count(*) from skill.plugin_version where tenant_id='demo')
     + (select count(*) from skill.plugin_source where tenant_id='demo')
     + (select count(*) from skill.plugin_grant g
          join skill.plugin_version pv on pv.id = g.plugin_version_id
          join skill.plugin p on p.id = pv.plugin_id
         where p.tenant_id='demo'),
       32,
       ((select count(*) from skill.skill where tenant_id='demo')
     + (select count(*) from skill.skill_version where tenant_id='demo')
     + (select count(*) from skill.plugin where tenant_id='demo')
     + (select count(*) from skill.plugin_version where tenant_id='demo')
     + (select count(*) from skill.plugin_source where tenant_id='demo')
     + (select count(*) from skill.plugin_grant g
          join skill.plugin_version pv on pv.id = g.plugin_version_id
          join skill.plugin p on p.id = pv.plugin_id
         where p.tenant_id='demo')) = 32;

-- 4) 激活指针 active() 口径——SQL 与 app-management
--    ConfigReleaseRepository.active(Actor,appCode,scope) 的主查询逐字同源（去语义视图装饰）：
--    activation ⋈ release where status='ACTIVE'。每工作区恰 1 条；manifest_digest 与
--    workspace_manifest.digest（revision=1）一致（“每工作区 1 条 digest 一致”）。
--    4a 明细行（供证据留痕）：
select a.app_code as workspace, a.scope, r.release_id, r.status, r.digest as release_digest,
       r.manifest_digest, m.digest as manifest_row_digest,
       (r.manifest_digest = m.digest) as digest_match
  from app.integration_config_release_activation a
  join app.integration_config_release r
    on r.tenant_id = a.tenant_id and r.release_id = a.release_id
  left join app.workspace_manifest m
    on m.tenant_id = a.tenant_id and m.app_code = a.app_code and m.revision = 1
 where a.tenant_id = 'demo' and r.status = 'ACTIVE'
 order by a.app_code;
--    4b 断言行：ACTIVE 恰 2 条（每工作区 1 条）且全部 digest 一致
select 'activation_pointer', 'ACTIVE rows / digest一致',
       (select count(*) from app.integration_config_release_activation a
          join app.integration_config_release r
            on r.tenant_id = a.tenant_id and r.release_id = a.release_id
         where a.tenant_id='demo' and r.status='ACTIVE')
       || '行/' ||
       (select count(*) from app.integration_config_release_activation a
          join app.integration_config_release r
            on r.tenant_id = a.tenant_id and r.release_id = a.release_id
          join app.workspace_manifest m
            on m.tenant_id = a.tenant_id and m.app_code = a.app_code and m.revision = 1
         where a.tenant_id='demo' and r.status='ACTIVE' and r.manifest_digest = m.digest)
       || '一致',
       '2行/2一致',
       ((select count(*) from app.integration_config_release_activation a
          join app.integration_config_release r
            on r.tenant_id = a.tenant_id and r.release_id = a.release_id
         where a.tenant_id='demo' and r.status='ACTIVE') = 2)
       and ((select count(*) from app.integration_config_release_activation a
          join app.integration_config_release r
            on r.tenant_id = a.tenant_id and r.release_id = a.release_id
          join app.workspace_manifest m
            on m.tenant_id = a.tenant_id and m.app_code = a.app_code and m.revision = 1
         where a.tenant_id='demo' and r.status='ACTIVE' and r.manifest_digest = m.digest) = 2);
