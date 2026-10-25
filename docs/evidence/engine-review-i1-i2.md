# Engine I1 / I2 repair evidence — 2026-10-20

Independent source findings: docs/evidence/review-execution-increment.md I1/I2. This report records engine repairs and directly executed tests; it does not close model I3 or claim real-provider/IAM/ontology acceptance.

## I1: current effective scope before recorded-data reuse

`ReplayScope` reproduces SkillRules' frozen-contract narrowing semantics: all declared roles are required (ordinary ADMIN role handling remains common policy), empty attribute value arrays mean the verified Actor's nonempty authorization set, declared values intersect current Actor values, and proposed array parameters are intersected/deduplicated. Missing parameter scope or empty intersections reject. An entirely undeclared skill guard defaults to deny. Arrays follow the same string-value conversion/order semantics as the real skill guard; Actor set insertion order does not widen the scope.

The current effective args are schema-checked and hashed canonically. Recorded Envelope.guardTrace.argsDigest must exist and match this digest. Otherwise NO_PERMISSION is raised before tool claim, storage copy or logical result reuse. A preflight over all source tool facts also runs before creating the new controlled run, preventing a cached terminal model response from bypassing a source tool scope that is no longer authorized. Ordinary snapshot preparation was aligned for legal empty attribute declarations as well.

Initial `ReplayBoundaryTest` execution genuinely failed: narrower scope and missing guard evidence were still accepted, empty array declaration was incorrectly refused, and a projected scalar was incorrectly validated as an object. Red log: engine-runtime/target/replay-boundary-red.log. The new boundary suite now has seven passing PG cases, including a narrowed-scope attachment case that forbids storage calls and checks preflight refusal.

## I2: trusted projected facts, current trace ownership, original model material

A saved same-version/same-contract authoritative execution fact establishes that original raw outputSchema validation already occurred before returnSpec projection. Engine no longer treats Envelope.structured as the original HTTP response. Valid projected scalars and null structured with controlled fileRefs are accepted from that trusted fact after current guard/input checks. Source Envelope identity/status must agree with the authoritative source run/call/node/version.

The new trace receives a new Envelope owner (tenant/principal/run/node/attempt/call/version/code), isMock=true and explicit sourceProvenance (source run/call/owner/Envelope SHA). Historical source Envelope remains unchanged. Agent replay uses the exact saved tool message text from the original next-round model source_request; rewriting current trace ownership therefore does not silently change original model-visible material. The ordinary Agent regression now asserts both the new current owner and exact historical tool-message preservation.

For attachments, engine persists mock_source_call_id and canonical source digest before calling the storage-only internal port. It first claims the new tool under the normal live lease, and records the source pointer under run → node → call locking. Authority GET/claim now includes parsed persisted arguments. Storage call:

`POST /internal/skill-facts/{sourceCallId}/reproduce-artifacts`

body `{callId,nodeInstanceId,attemptId,leaseOwner,runId}` → `{fileRefs}`.

This infrastructure HTTP operation is permitted; model and business-skill execution remain forbidden. The skill service independently rechecks current lease/ownership/source pointer/args/contract/current guarded args against its own PG fact and copies objects into new current-run artifacts. Engine requires all returned artifact IDs to be new, complete and uniquely bound to `/v1/runs/{currentRun}/artifacts/{id}/download`; source or foreign-run references are rejected. No caller-supplied file list or physical storage key is accepted. The engine test uses an explicit storage-port fixture and bans other exits. Root separately reports 48 real skill PG/MinIO tests in `.local/skill-reproduce-artifact-green.log`; those are root's execution evidence, not tests run by this agent. A live combined engine-to-skill storage HTTP acceptance remains a deployment follow-up.

## Exact controlled connection tests

The compatibility path `/internal/skill-draft-tests` now accepts exact DRAFT/PUBLISHED/ARCHIVED versions while requiring EDITOR + SERVICE, object inputs, exact code/version/id and canonical contract SHA. A disabled skill is refused before creating a run. Normal write approval remains mandatory; read-only tests need no business approval. No ordinary run flag can grant DRAFT execution.

V212 adds neutral `controlled_skill_test_version_id`, `controlled_skill_test_digest` and `controlled_skill_test_status`, restricted to DEBUG with a paired immutable version/digest/status. Existing DRAFT markers are backfilled. DRAFT tests continue to set the legacy controlled_draft_* fields; PUBLISHED/ARCHIVED tests set only neutral fields. Status exposes controlledSkillVersionId/controlledSkillStatus and callId, so a published connection no longer disappears into an empty result. Tool authority exposes neutral fields too. Root handles SkillGovernance PlatformException propagation so callers receive structured errors rather than false-without-runId.

## Verification and limits

Final full Maven suite: **75 tests / 0 failures / 0 errors / 0 skipped**, after formatting all owned Java source/tests. Log: engine-runtime/target/i1-i2-full-green.log. Surefire XML totals were independently summed after completion. Uses JDK21, project-local Maven cache and the dedicated engine PostgreSQL test database. V200–V212 validation passes; only V212 is newly appended by this package. Parent's status.inputs addition is retained and asserted by the published read-only connection test. No service process was stopped by this agent.

The prior documented limits remain: old records lacking trustworthy guard/owner/model materials reject; multi-generation readonly source replay is explicitly unsupported; arbitrary JSON Schema composition is not newly claimed; hierarchical compression and external deployment acceptance are separate work. The infrastructure-only copy allowance supersedes previous broad wording that all controlled reproduction performs zero HTTP.
