-- G5-0 存量基线验证：三模块 history 顶版本钉在升级前版本，且无失败迁移。
-- 期望顶版本：engine=228 / app=121 / skill=320（以各模块 db/migration 实际文件核对：
--   升级段 engine V229/V230/V231、app V122/V123、skill V321/V322；V901/V232 均不得出现在基线）。
-- 输出：check|module|actual|expect|pass（脚本以行尾 false 判失败）。
with tops as (
    select 'engine' as module, (select version from engine.flyway_schema_history order by installed_rank desc limit 1) as top_version
    union all
    select 'app',  (select version from app.flyway_schema_history  order by installed_rank desc limit 1)
    union all
    select 'skill',(select version from skill.flyway_schema_history order by installed_rank desc limit 1)
)
select 'baseline_top_version', module, coalesce(top_version,'(none)'),
       case module when 'engine' then '228' when 'app' then '121' else '320' end,
       (top_version = case module when 'engine' then '228' when 'app' then '121' else '320' end)
  from tops order by module;

select 'failed_migrations', 'engine+app+skill',
       (select count(*) from engine.flyway_schema_history where not success)
     + (select count(*) from app.flyway_schema_history where not success)
     + (select count(*) from skill.flyway_schema_history where not success),
       0,
       ((select count(*) from engine.flyway_schema_history where not success)
     + (select count(*) from app.flyway_schema_history where not success)
     + (select count(*) from skill.flyway_schema_history where not success)) = 0;

-- 基线不得含 V901（演示种子属升级段链头）
select 'no_v901_at_baseline', 'engine+app+skill',
       (select count(*) from engine.flyway_schema_history where version='901')
     + (select count(*) from app.flyway_schema_history  where version='901')
     + (select count(*) from skill.flyway_schema_history where version='901'),
       0,
       ((select count(*) from engine.flyway_schema_history where version='901')
     + (select count(*) from app.flyway_schema_history  where version='901')
     + (select count(*) from skill.flyway_schema_history where version='901')) = 0;
