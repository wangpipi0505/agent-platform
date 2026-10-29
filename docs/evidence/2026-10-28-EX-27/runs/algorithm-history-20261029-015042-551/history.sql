WITH scoped AS MATERIALIZED (
 SELECT tenant_id,call_id,state,last_error_code,created_at,updated_at,deleted_at,
        cleanup_attempts,cleanup_after
 FROM skill.artifact
 WHERE created_at >= timestamptz '2026-10-28T23:49:19.2381126+08:00'
   AND created_at <= timestamptz '2026-10-28T23:52:48.9248179+08:00'
   AND left(tenant_id,20)='algorithm_interrupt_'
   AND tenant_id LIKE 'algorithm!_interrupt!_%' ESCAPE '!'
 ORDER BY created_at,call_id LIMIT 13
), annotated AS (
 SELECT *,min(call_id) OVER(PARTITION BY tenant_id) AS first_call,
        row_number() OVER(PARTITION BY tenant_id ORDER BY created_at,call_id) AS creation_order
 FROM scoped
), anonymous_groups AS (
 SELECT min(created_at) AS first_created,
 json_build_object(
  'groupHash',md5(tenant_id),'rows',count(*),'distinctCalls',count(DISTINCT call_id),
  'callSpan',max(call_id)-min(call_id),
  'callsConsecutive',count(DISTINCT call_id)=count(*) AND max(call_id)-min(call_id)=count(*)-1,
  'firstCreatedAt',min(created_at),'lastCreatedAt',max(created_at),
  'firstUpdatedAt',min(updated_at),'lastUpdatedAt',max(updated_at),
  'readyRows',count(*) FILTER(WHERE state='READY'),
  'pendingRows',count(*) FILTER(WHERE state='CLEANUP_PENDING'),
  'deletedRows',count(*) FILTER(WHERE state='DELETED'),
  'unconfirmedCodeRows',count(*) FILTER(WHERE last_error_code='ALGORITHM_IO_STOP_NOT_CONFIRMED'),
  'cleanupAttemptsTotal',sum(cleanup_attempts),
  'records',json_agg(json_build_object(
    'creationOrder',creation_order,
    'relativeCall',CASE WHEN call_id-first_call BETWEEN 0 AND 2 THEN call_id-first_call ELSE -1 END,
    'state',CASE WHEN state IN('UPLOADING','READY','CLEANUP_PENDING','DELETED') THEN state ELSE 'OTHER_STATE' END,
    'errorCode',CASE WHEN last_error_code IS NULL THEN NULL
        WHEN last_error_code IN('ALGORITHM_IO_STOP_NOT_CONFIRMED','OFFLOAD_FAILED','RETENTION_OR_INTERRUPTED_UPLOAD') THEN last_error_code
        WHEN last_error_code ~ '^OBJECT_DELETE_FAILED_[A-Za-z][A-Za-z0-9_$]{0,42}$' THEN last_error_code ELSE 'OTHER_CODE' END,
    'createdAt',created_at,'updatedAt',updated_at,'deletedAt',deleted_at,
    'cleanupAfter',cleanup_after,'cleanupAttempts',cleanup_attempts,
    'updatedInsideOriginalWindow',updated_at BETWEEN timestamptz '2026-10-28T23:49:19.2381126+08:00' AND timestamptz '2026-10-28T23:52:48.9248179+08:00'
  ) ORDER BY created_at,call_id)
 ) AS payload
 FROM annotated GROUP BY tenant_id
)
SELECT json_build_object(
 'observedAt',clock_timestamp(),'database',current_database(),
 'rowCap',13,'matchedRowsCapped',(SELECT count(*) FROM scoped),
 'possibleTruncation',(SELECT count(*)>=13 FROM scoped),
 'groupCount',count(*),'groups',coalesce(json_agg(payload ORDER BY first_created),'[]'::json)
) FROM anonymous_groups;
