-- G5-0 行数对照：engine/app/skill 三 schema 全部基表逐表行数（含 flyway_schema_history）。
-- 输出：tbl|cnt（按 schema.table 排序），升级前/升级后/回退后三次采集互 diff 用。
-- psql 专属（\gexec）：psql -X -h 127.0.0.1 -p 25432 -U "$AGENT_DB_USER" -d <db> -At -f row-counts.sql
select format('select %L as tbl, count(*) as cnt from %I.%I;',
              table_schema || '.' || table_name, table_schema, table_name)
  from information_schema.tables
 where table_schema in ('engine','app','skill') and table_type = 'BASE TABLE'
 order by 1 \gexec
