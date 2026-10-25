BEGIN READ ONLY;
SET LOCAL statement_timeout=5000;
SELECT json_build_object('database',current_database(),
 'runs',(select json_agg(to_jsonb(r) order by r.id) from engine.run r),
 'calls',(select json_agg(to_jsonb(c) order by c.id) from engine.tool_call c),
 'facts',(select json_agg(to_jsonb(f) order by f.call_id) from skill.execution_fact f),
 'bindings',(select json_agg(to_jsonb(b) order by b.call_id) from skill.external_job_binding b),
 'attempts',(select json_agg(to_jsonb(a) order by a.id) from engine.attempt a),
 'nodes',(select json_agg(to_jsonb(n) order by n.id) from engine.node_instance n),
 'events',(select json_agg(to_jsonb(e) order by e.run_id,e.seq) from engine.run_event e),
 'notes',(select json_agg(to_jsonb(n) order by n.id) from engine.attempt_note n),
 'artifacts',(select coalesce(json_agg(to_jsonb(a) order by a.artifact_id),'[]'::json) from skill.artifact a),
 'engineMigrations',(select json_agg(json_build_object('version',version,'success',success) order by installed_rank) from engine.flyway_schema_history),
 'skillMigrations',(select json_agg(json_build_object('version',version,'success',success) order by installed_rank) from skill.flyway_schema_history));
COMMIT;
