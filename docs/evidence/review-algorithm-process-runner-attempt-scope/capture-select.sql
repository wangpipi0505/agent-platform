BEGIN READ ONLY;
SET LOCAL statement_timeout=5000;
select json_build_object('run',json_build_object('id',r.id,'publicId',r.public_id,'status',r.status,'tenantId',r.tenant_id,'principalId',r.principal_id),
        'node',to_jsonb(n),
        'tenantRuns',(select json_agg(json_build_object('id',x.id,'publicId',x.public_id) order by x.id) from engine.run x where x.tenant_id=r.tenant_id),
        'calls',(select coalesce(json_agg(to_jsonb(c) order by c.id),'[]'::json) from engine.tool_call c where c.run_id=r.id),
        'facts',(select coalesce(json_agg(to_jsonb(f) order by f.call_id),'[]'::json) from skill.execution_fact f where f.tenant_id=r.tenant_id and f.run_id=r.public_id),
        'bindings',(select coalesce(json_agg(to_jsonb(b) order by b.call_id),'[]'::json) from skill.external_job_binding b where b.tenant_id=r.tenant_id and b.run_id=r.public_id),
        'toolCommits',(select count(*) from engine.run_event e where e.run_id=r.id and e.type='LOGIC_RESULT_COMMITTED' and (e.payload->>'nodeInstanceId')::bigint=t.node_instance_id),
        'attempts',(select count(*) from engine.attempt a where a.node_instance_id=t.node_instance_id),
        'runCalls',(select count(*) from engine.tool_call c where c.run_id=r.id),
        'runFacts',(select count(*) from skill.execution_fact f where f.tenant_id=r.tenant_id and f.run_id=r.public_id),
        'runBindings',(select count(*) from skill.external_job_binding b where b.tenant_id=r.tenant_id and b.run_id=r.public_id),
        'attemptRows',(select coalesce(json_agg(to_jsonb(a) order by a.attempt_no,a.id),'[]'::json) from engine.attempt a where a.node_instance_id=t.node_instance_id),
        'terminalNotes',(select coalesce(json_agg(to_jsonb(x) order by x.id),'[]'::json) from engine.attempt_note x where x.node_instance_id=t.node_instance_id and x.kind='TOOL_EXECUTION_FACT' and x.payload->>'accepted'='true' and x.payload->>'status' in ('SUCCEEDED','FAILED')),
        'acceptedTerminalFacts',(select count(*) from engine.attempt_note n where n.node_instance_id=t.node_instance_id and n.kind='TOOL_EXECUTION_FACT' and n.payload->>'accepted'='true' and n.payload->>'status' in ('SUCCEEDED','FAILED')),
        'events',(select coalesce(json_agg(to_jsonb(e) order by e.seq),'[]'::json) from engine.run_event e where e.run_id=r.id))
        from engine.run r join engine.tool_call t on t.run_id=r.id join engine.node_instance n on n.id=t.node_instance_id where r.tenant_id='algorithm-live-f7fecdf481a3' and r.public_id='r_2700d27889274aea890a7042b9e0f994' and t.id=2;
COMMIT;
