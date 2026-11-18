-- G5-0 演练/升级：三模块 flyway_schema_history 逐行（schema|version|script|success），按 installed_rank。
-- 用法（psql，AnyDB 参数化）：psql -X -At -f history-summary.sql <db>
--   psql -X -h 127.0.0.1 -p 25432 -U "$AGENT_DB_USER" -d <db> -At -f history-summary.sql
select 'engine' as schema, version, script, success
  from engine.flyway_schema_history order by installed_rank;
select 'app' as schema, version, script, success
  from app.flyway_schema_history order by installed_rank;
select 'skill' as schema, version, script, success
  from skill.flyway_schema_history order by installed_rank;
