# N2 task review — original owned scheduler / pure FLOW

## Spec Compliance

**Needs fixes.** One Important scope-gate defect remains: `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:1102` excludes `CHAT` instead of requiring `FLOW`. Missing, null, or unknown snapshot types can therefore enter the newly authorized FLOW worker. See I1 below.

The reviewed implementation otherwise matches the concrete N2 composition:

- Original scheduler ownership and finite slots remain in use. `RunScheduler.java:378` exposes only the existing thread's dispatch; `RunScheduler.java:385` defers through the original release CAS. `RunScheduleRepository.java:29` routes both claim entries through one metadata-only selection, including authority/backoff checks and the protected-only terminal-node aggregation candidate.
- `RunService.java:1588` uses the actual owned worker, obtains fresh current outside each store transaction, separates pure execution faults from commits, and refreshes current again for aggregation. The protected branch does not reconstruct authority from `guard_ctx`.
- `TraceStore.java:513` locks run → authority → nodes and rechecks real dispatch time after node locks before payload reads. `TraceStore.java:566` retains top-level RR ownership and same-proof callback-end / actual-commit-return checks. N1 public proof-only writers have not been made generally joinable.
- `TraceStore.java:610` refreshes values and SQL-null flags after each appended scope. Claim's real attempt scopes retain execution parents; commit reloads all current materials including those attempt scopes. `OwnedFlowMaterialsPostgresTest.java:495` checks no execute-time PG query and retained ancestry after another output changes.
- `GraphRules.java:16` preserves edge transformations, original incoming order, actual-SUCCEEDED ANY selection, and exclusive direct predecessor selection. `TraceStore.java:1248` commits preparation on the finite budget result before any claim/attempt/budget write. `TraceStore.java:1368` independently checks the actual locked budget before terminating.
- `TraceStore.java:1301` routes default commits through the shared private CAS, so error/output/attempt/events/scopes share the transaction. `TraceStore.java:1346` retains attempt failure on node CAS0 under a valid dispatch and includes the final dispatch fence. `TraceStore.java:1422` aggregates after preparation and value refresh; `GraphRules.java:105` receives SQL NULL separately from explicit JSON null.
- `RunAuthority.java:40` preserves original Binding/check-version denial recording, distinguishes short expiry from durable grant expiry, and leaves local successful-CAS/commit failures outside online rejection classification. `RunAuthorityLocalCurrentPostgresTest.java:24` adds recovery after short proof expiry without weakening the existing narrowing/strict-binding cases.

### Cannot verify from diff — cross-task / unchanged boundaries

- **Global v1.2, four contracts, fixed Java/Boot/Security/PG/Vue versions, seven-module and HTTP boundaries, unchanged configuration and production IAM closure.** This package has only the seven authorized existing production files and four test files. Their unchanged definitions and complete deployment closure are not re-audited here. Root reports the original 75,091 files, 28 scripts and eight indexes verified with zero drift in `.local/iam-n2-root-protected-check.json`, including the formal brief and original design. Root has handled this physical boundary; this review does not claim a whole-program deployment review.
- **Complete N1 authority/material guarantees.** Full `RunAuthorityStore.strict/load/recordCheck`, `MaterialScopeStore.requireWriter/append/readCurrentValue`, original-grant binding, parent proof validation, and overwritten historical-payload behavior live in unchanged N1/common code. The changed call sites preserve their use. Root should retain the accepted N1 review and its resolved cross-task checks; this N2 review does not re-sign the entire N1 implementation.
- **Formal Spring IAM admission and original HTTP chain.** Explicit constructors are visible in `RunService.java:40` and `RunScheduleRepository.java:24`; these are isolated assembly seams. Original RunController/createInternal/RuntimeClients integration and the gateway chain remain N3/N4, not an N2 completion claim.
- **Whole recovery domain and external systems.** The changed `resumeExternalFacts` candidate SQL excludes rows with authority, but independent ExecutionRecovery, Approval, debug recovery, CHAT, tools/models, streaming, Observer, RR1/RR2, real IAM/ontology/K8s and capacity behavior are outside this task. Root must retain their unaccepted status.
- **Evidence completeness and provenance.** Root reports source362 → source364, target1740 → target1761, exact inventories, replay and 12 rounds independently verified; target changes are 22 added + 24 modified + one removed class, 47 changed paths, JAR changes zero. These inventory-wide results are external root checks, not re-derived by this reviewer. The final raw output directly confirms 67/0F/0E/0skip; root's Running/XML/source audit establishes the seven actual classes and excludes 58 incidental XML files.
- **Upstream source/version/licence provenance and original acceptance transaction completion.** The proposal explains the intended RR/PG behavior; complete upstream-reading records and unchanged admission's real commit ownership are outside the source delta. Root should keep the existing source/provenance and N1 transaction checks rather than treating the N2 worker tests as a replacement.

## Strengths

- The authority/material/dispatch composition has concrete ownership instead of a new general transaction API. Locks and live fences are visible at `TraceStore.java:513`, while network current refresh stays in `RunService.java:1588`.
- Failure atomicity tests assert actual PG facts before and after faults rather than merely checking exceptions: default at `OwnedFlowMaterialsPostgresTest.java:127`, budget at `:266`, and callback-end failure expiry at the first test in the same file (`:22`).
- Real node-lock expiry (`OwnedFlowMaterialsPostgresTest.java:581`) observes PostgreSQL Lock state, waits for actual elapsed expiry, and asserts zero payload increase. The suite also explicitly distinguishes same-transaction injected expiry from natural lock/elapsed-time evidence.
- The end-to-end isolated test uses original reserve/persist/created/worker components and verifies output, attempts and event sequence (`OwnedFlowSchedulerPostgresTest.java:172`). The final-node post-commit expiry test verifies aggregate-only recovery without repeating node attempts (`OwnedFlowMaterialsPostgresTest.java:445`).
- Source growth is concentrated in the specifically authorized fixed-purpose store methods and extracted graph decisions. No new production source file, second driver, authority ThreadLocal, receipt, business-service JAR coupling or generalized callback interface is introduced in this delta.

## Issues

### Critical

None found in the reviewed task.

### Important

**I1 — Require the positive FLOW discriminator before starting protected work.**

- **Evidence:** `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java:1097`, specifically `:1102`, rejects only `snapshot.path("type").asText().equals("CHAT")`. `startOwnedFlow` calls this gate before PENDING→RUNNING (`TraceStore.java:1159`). The focused unchanged acceptance check, `RunAuthorityStore.java:33–73`, validates binding/version/mode/material constraints but does not require `snapshot.type == FLOW`.
- **Trigger and result:** use the authorized N1 acceptance fixture with the normal valid published metadata and a matching START→END graph, but omit `type`, set it to JSON null, or set it to an unknown value. The binding and material checks can succeed; this gate also succeeds and the worker creates RUN_STARTED, attempts and node results. All existing new worker fixtures force `type=FLOW`, so they do not exercise this gap.
- **Why blocking:** the formal brief permits only FULL/TASK/published pure FLOW and explicitly requires a defensive gate after materialized snapshot read. A deny-one-value check silently admits unsupported application types into that execution scope. This is a scope-validation defect in the new isolated component; it is not a claim that global production IAM is enabled or that the current gate permits external model/tool dispatch.
- **Fix:** require the positive `FLOW` discriminator. Add finite real-component negative cases for missing/null/unknown/non-FLOW snapshot type through the existing N1 fixture → original owned worker, asserting no RUN_STARTED/attempt/execute. Preserve the successful FLOW case. Return the fix and its justified affected validation to this same reviewer.

### Minor

**M1 — Expected negative tests leave 11 unasserted scheduler WARN lines in green output.**

- **Evidence:** `docs/evidence/iam-stage-5-n2-owned-flow/iam-stage-5-n2-final03/raw.log:272`, `:310`, `:412`, `:417`, `:422`, `:427`, `:432`, `:437`, `:442`, `:459`, `:464`.
- The frozen per-test XML maps them exactly to: last-node post-commit proof expiry (one PlatformException), injected worker commit infrastructure fault (one UncategorizedSQLException), missing authority plus six unsupported node types (seven PlatformExceptions), and current403/current503 (two PlatformExceptions). Corresponding test code is `OwnedFlowMaterialsPostgresTest.java:445`, `:185` and `OwnedFlowSchedulerPostgresTest.java:280`, `:228`.
- The fact assertions establish intended negative behavior, so this does not show an additional runtime failure or invalidate 67/0. However, the test harness currently leaves generic warnings indistinguishable from unexpected worker failures and does not assert their exact occurrence/classification.
- Prefer test-local capture and assertion of expected scheduler warnings with failure on unexpected ones; keep normal production warning behavior and avoid blanket logger suppression. This is test-output polish, not a second blocking defect.

## Review checks and exceptions

- Read the task-reviewer contract, formal brief, implementer report and chronological root decisions. Read the concrete transaction-seam proposal and only §4 of the earlier published-chain proposal.
- Verified package SHA-256 `ab930084215156ddd40a01bd9420d484e6ab558f721ab3787e3492a1f67953ba` and size 357,488 bytes. Reviewed its complete 6,574 lines once in bounded, non-overlapping chunks. No changed-source file was separately read. A subsequent mechanical package pass emitted only source path/line locations for report citations; it did not re-derive Git history or print source bodies.
- The initial combined requirements/report output was truncated; the missing requirements/report portions were recovered in bounded reads. No diff chunk was truncated.
- **Focused unchanged check A:** named risk that the narrowed execution catch misses ordinary CODE/schema failures. Read `domain/Values.java` and `domain/JsonGate.java`: expression errors become IllegalArgumentException and schema errors are RuntimeFault, matching the new catch. An initial guessed JsonGate path was absent; an exact-filename lookup located the actual domain class. No finding.
- **Focused unchanged check B:** named risk that an earlier acceptance gate already enforces FLOW and would make I1 unreachable. Read only `RunAuthorityStore.java:31–103`; `createBound` does not enforce this discriminator. This supports I1 without expanding to a full N1 re-review.
- **Focused unchanged check C:** named risk that short retained-proof expiry uses a different classification than the new RunAuthority branch expects. Read the narrowly matched retained-proof/current/expiry portions of `HttpCurrentAuthorityClient.java` and the MaterialAuthorization expiry search. `requireVerified` checks current plus retained ticket expiry; no contrary classification was found in the inspected seam. Complete common authority semantics remain the N1 boundary above.
- Read exact WARN contexts in final03 raw output and mapped all 11 warnings through the two relevant frozen test XML documents. No Maven/suite/probe, network test, DB mutation, or source/target mutation was performed.
- Read relocation scalar metadata only: 12 stages, 4,421 files, preserved bytes/hashes, original raw/manifest bytes unchanged, and explicit absence of pre-capture directory-object evidence. Did not print the 4,421-row mapping or repeat root's physical audit.
- External named checks supplied by root: `.local/iam-n2-root-fast-check.json`, `.local/iam-n2-root-rest` audit continuation as identified in the dispatch (`.local/audit-iam-n2-root-rest.mjs`), retained phase-one audit failure, and `.local/iam-n2-root-protected-check.json`. Their results are attributed to root; they do not substitute for the independent source conclusions above.
- Sole review write: this new report. No progress/index updates, commits, package/deploy/publish actions, or adjacent-project reads.

## Assessment

**Spec Compliance: Needs fixes.**

**Task quality: Needs fixes.**

The owned scheduler, transaction ownership, material ancestry, expiry/failure separation and actual-PG evidence are strong. I1 must be fixed and returned for same-reviewer re-review before this task is approved; M1 is a nonblocking test-output issue. This is the N2 task gate only, not whole-branch or production IAM approval.
