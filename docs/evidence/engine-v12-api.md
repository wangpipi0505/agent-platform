# Engine v1.2 incremental API contract (source package V205–V211)

Deployment status: this file describes the new source package. Restart only after the complete Maven suite is green. All APIs retain platform authentication; v1 routes additionally require gateway app binding.

## Immutable model bindings and request projection

The effective modelEntryId selects exactly one snapshot.dependencyVersions.modelRefs entry `{modelEntryId,modelBindingRef:{bindingId,sha256},entry}`. Engine checks entry's canonical SHA against reference SHA; model service independently resolves the tenant-owned binding ID + SHA and checks entry identity. DEBUG can freeze missing metadata from model service at run creation. Existing formal or evaluation snapshots missing references fail closed and must be re-evaluated/published.

Each model request includes modelBindingRef, appCode from the run, and callKind FORMAL/DEBUG/EVALUATION. `/internal/model/measure` is the tokenizer authority. Manifest persists measurement method/exact, protected input count, configured output reservation (separate from model output capability), safety margin, history budget, source/projected message SHA and trim log. The four stages preserve original PG evidence and tool pairing: head/tail tool text projection, whole historical group removal, separately logged SUMMARY model invocation, then permitted historical content truncation. System governance and current user instruction are protected. Unknown tokenizer, protected overflow, invalid reservation and summary-source overflow fail explicitly.

`engine.model_invocation` stores original source_request, final_request before dispatch, response, SDK attempt count, summary kind, usage accounting and mock/source provenance. Successful returns survive cancellation or later budget failure as facts. Token debit is an idempotent separate ledger operation. Existing persisted final material is reused across lease recovery; successful responses eliminate duplicate calls. A pending model invocation without a durable return may make a separately budgeted SDK retry with the same frozen material; this does not promise provider billing deduplication.

## Debug controls

`GET /admin/debug-runs/{id}/controls` → `{revision,controls:{breakpoints,mocks}}`.

`PUT /admin/debug-runs/{id}/controls` body:

```json
{"expectedRevision":0,"breakpoints":["nodeDefId"],"mocks":[{"skillCode":"lookup","mode":"FIXED","fixedResponse":{"value":42}}]}
```

LAST_REAL configuration: `{skillCode,mode:"LAST_REAL",sourceCallId?:number}`. Controls require owner + tenant + EDITOR and an active DEBUG run. Unknown nodes/skills, duplicate skill entries and stale revision are rejected. Stable calls freeze their selected mock and control revision at registration, so later control changes only affect new calls.

LAST_REAL selects only a current-user-readable, non-mock, successful execution with the same exact skill version, canonical args digest and frozen contract digest. It freezes the source Envelope, source call/run/principal and canonical result SHA. No global result cache or process-memory authority is used. Formal/evaluation registrations freeze no mock. Skill independently honors only the authoritative mock_snapshot, not an untrusted body flag. Execute sends an empty mock object; the persisted authority is the sole source. LAST_REAL source facts are independently checked again by the skill service.

Attachment-only LAST_REAL (structured=null + fileRefs) is delegated to the authority-aware skill service, which copies source objects and mints new current-run controlled artifact references. Engine neither strips the Envelope nor forwards physical object keys. The previous temporary attachment rejection has been removed after root supplied the skill PG/MinIO test evidence.

## Cross-run CHAT sessions

- `POST /v1/apps/{code}/sessions` (also `/admin/...`) creates a session pinned to the current published CHAT snapshot.
- `GET /v1/apps/{code}/sessions/{sessionId}` returns app/version, nextTurn and turn/run links.
- `POST /v1/apps/{code}/sessions/{sessionId}/turns` body `{requestId,inputs:{message:"..."}}` (requestId at most 48 characters) creates/reuses the next run.

Sessions bind tenant + principal + app. A new request ID first checks current app publication/enablement without changing the pinned snapshot. A current turn must finish before another request ID can reserve a new turn. Reservation commits before run creation; repeating the same request ID recovers a crash between reservation, idempotent run creation and attachment. Changed input under the same request ID is rejected. Prior turn messages come from persisted owned runs, ordered by turn number. Input payloads cannot supply arbitrary prior messages. Current user position is explicit in the manifest and protected during trimming. Failed/canceled turn state is represented as such, without inventing an assistant success.

## Three replay modes

`GET /admin/runs/{id}/replay` returns LOG mode, sourceDigest, snapshot/version chain, events, nodes, attempts/notes, model material and tool facts. It does not invoke services.

`POST /admin/runs/{id}/reexecute` body `{requestId,sourceDigest}` (requestId at most 48 characters) confirms the displayed terminal source, then starts a new DEBUG run from old configuration using current services. It records sourceRunId and REEXECUTE provenance. Writes still use ordinary confirmation, guard, lease and fact rules. A source controlled-DRAFT acceptance must be recreated from the current draft contract instead.

`POST /admin/runs/{id}/reproduce` with the same confirmation body starts a new CONTROLLED DEBUG run. Engine-owned source references replace model/skill exits with exact recorded responses; the same FlowDriver/AgentDriver recomputes conditions, expressions, outputs and logical state. It produces a separate trace. Model invocations have is_mock=true, source_invocation_id and sdk_attempts=0; tool calls have is_mock=true and source call/result digest. Input/contract/version/round mismatch or missing frozen guard/schema/model material is rejected. All source access rechecks ownership, and current frozen guard and input-schema validation remains active. Guarded args must match the source fact digest; raw outputSchema acceptance is trusted from the exact-contract fact because structured may already be a scalar/null returnSpec projection. Current Envelope owner/provenance is separate from original saved model tool messages. It performs no model or business-skill HTTP calls. Attachment reproduction may use the independently verified storage-only POST /internal/skill-facts/{sourceCallId}/reproduce-artifacts port, which returns newly owned current-run references. Current limitation: sources with multiple read-only execution retry generations are explicitly rejected rather than silently collapsing their history.

An internal evidence archive also exists (`engine.reproduction`, GET `/admin/reproductions/{id}`); it is distinct from executable CONTROLLED reproduction and does not claim a new successful business run.

## Artifact projection

Run status includes top-level fileRefs aggregated from its authoritative successful tool results while node.output remains the structured output contract. Only controlled metadata is exposed: artifactId, downloadUrl `/admin/artifacts/{id}/download`, externalDownloadUrl exactly `/v1/runs/{thisRunId}/artifacts/{id}/download`, sha256, bytes/size metadata, contentType, names and summary. Legacy uri, bucket and physical object keys are omitted. Artifact download authorization is rechecked by the artifact service.



## V212 exact skill connection compatibility

POST /internal/skill-draft-tests accepts exact DRAFT/PUBLISHED/ARCHIVED (EDITOR + SERVICE, disabled refused). It remains server-constructed DEBUG and preserves normal write confirmation. Neutral status/authority fields controlledSkillVersionId/controlledSkillStatus and controlled_skill_test_version_id/digest/status distinguish published tests from DRAFT-only permission markers. Root SkillGovernance propagates structured upstream failures. Details and 75-test evidence: docs/evidence/engine-review-i1-i2.md.

