# Engine initial sender — independent task review

## Spec Compliance

**❌ Issues found.** The real owned caller, authority/material chain, durable dispatch, bounded transport and response recovery are implemented, but output-policy admission/handling and response durability on budget failure need fixes. The mandatory meaningful-output-format positive is missing. **Counts: Critical 0; Important 4; Minor 1.**

**Task Quality: Needs fixes.** This is a review of the one component, not code acceptance, global task completion, or resource/execution acceptance. Disposition: **ALL / noBackground / NOTAccepted**.

All code references below are relative to the candidate immutable tree `.local/engine-initial-sender/final2-source/source/`; contracts references are relative to P. Main Java paths use `engine-runtime/src/main/java/cn/agentplatform/engine/`; test paths use `engine-runtime/src/test/java/cn/agentplatform/engine/`.

## Strengths

- `application/RunService.java:1880` and `:1891`, together with the changed `drive` and `driveRecovered` calls, implement the actual protected dispatch branch. Preparation occurs before invocation creation; the explicit construction overload keeps the default initial component unavailable. FLOW remains on its existing branch.
- `infra/ModelInputMaterialStore.java:202`, `:285`, `:372` preserves the canonical request string, hashes and stores that string, and reads it under retained local binding, manifest/parent checks, metadata byte limits and exact SHA equality. Historic NULL is rejected. V219 adds only the nullable bounded original-text column.
- `infra/TraceStore.java:495`, `:1092` integrates the optional exact model-input member without weakening scope-set equality, and uses the existing protected claim machinery to create an actual attempt, request reference, one budgeted invocation and DISPATCHED/sdk_attempts=1 before HTTP. The frozen route comes from the digest-checked dependency entry, not a live catalog.
- `application/ModelExchange.java:22` obtains genuine current authority on the owning thread and rechecks original material plus execution state. `infra/RuntimeClients.java:101` forwards that authority, prohibits a transaction around HTTP, sends cloned original bytes once, and gates exposure. Its instance-owned transport disables retry/redirect/auth fallback; `:163` bounds bytes before growing SSE data, rejects bad UTF-8/duplicate JSON/final/error/truncation, and has idle/total cancellation.
- `infra/TraceStore.java:1126` does not resend a durable dispatch lacking response. Response-bearing recovery checks the original request/checkpoint and takes ownership of the same attempt. `:1230` commits through existing node CAS/events and the run dispatch fence. Missing usage remains unaccounted rather than becoming proven zero (`:1213`).
- New tests exercise actual PG commit/rollback, signed authority, actual RunService, separate HTTP service processes, first-chunk observation, revocation, transport faults and scheduler recovery. `infra/InitialSenderFactsPostgresTest.java:31`, `:38`, `:48` distinguish ACK loss, durable response reuse and preparation recovery. These are meaningful behavioral tests rather than replacement business mocks.

## Issues

### Critical — 0

None identified in this scoped static review.

### Important — 4

**I1 — Validate the output declaration before dispatch, not after paying for a response.**

Locations: `application/ModelInputMaterials.java:142` and `application/ModelExchange.java:49`.

The added preparer guard checks only RETRY and nonzero maxRetries. The first construction of `OutputPolicy` is after `chatInitialStream` and `responseInitial`. Thus a malformed declaration such as `output.format.type="bogus"`, a wrong-typed format, unknown onInvalid, or an invalid schema can enter the dispatch path before the engine rejects its configuration. The unchanged assembler copies output into params (`application/ContextAssembler.java:84`); the receiver only requires output to be an object (`model-gateway/.../InitialModelInputVerifier.java:428`), and prompt mapping simply selects JSON when type equals json (`model-gateway/.../ModelCalls.java:907`). The unsupported declaration therefore does not have a reliable pre-egress rejection boundary. The longValue-only maxRetries comparison also lacks a range check, so an oversized integer that truncates to zero can pass this initial guard and fail much later.

Validate the complete supported frozen output declaration during preparation, before invocation/budget/HTTP. Reuse the existing policy validation and add an explicit finite-profile admission rule; do not create a second validator framework. Preserve the separate requirement that validation of the *returned model text* happens only after its real response is durable. These are different validations; response-first ordering is not a reason to defer declaration validation.

Focused new verification needed: `InitialSenderFactsPostgresTest.invalidOutputDeclarationRejectsBeforeDispatch` with wrong format/type/onInvalid, invalid schema, and out-of-range maxRetries, asserting no invocation marker, no call-budget consumption and independently zero ingress. Existing RETRY coverage answers only the one RETRY case.

**I2 — The branch admits PASS_WITH_FLAG but always treats an invalid answer as a thrown failure.**

Locations: `application/ModelExchange.java:50`, `application/ModelInputMaterials.java:143`, `application/RunService.java:1907`.

The guard excludes RETRY but permits PASS_WITH_FLAG. For an admitted JSON policy with PASS_WITH_FLAG and an invalid returned text, the new exchange unconditionally throws OUTPUT_INVALID. The catch in driveOwnedInitial then defers the run; recovery reuses the response and throws again. It neither produces the frozen pass-with-diagnostic result nor explicitly rejects this unsupported policy before sending. TERMINATE failures likewise enter the generic defer path rather than a recorded output-policy terminal outcome while authority remains valid.

This is observable semantics, not a request for correction retries. The frozen policy includes TERMINATE/PASS_WITH_FLAG (`contracts/snapshot/detailed-design-v1.2.md:177`), and the existing `OutputPolicy.onInvalid()` plus legacy branch (`application/ModelExchange.java:117`) distinguish them. Either support the allowed no-retry outcomes with protected diagnostics/terminal handling, or explicitly reject unsupported policies before invocation. Root should settle any intended narrower profile; accepting the field and silently treating every outcome alike is not an acceptable profile boundary. Keep paid response and usage facts and never mint a correction call.

Focused new verification needed: `InitialSenderFactsPostgresTest.initialInvalidOutputHonorsAdmittedPolicy` covering admitted PASS_WITH_FLAG and TERMINATE, or a pre-dispatch rejection test if PASS_WITH_FLAG is intentionally unsupported. Assert durable response retention, no repeated HTTP, and the appropriate stable run/node outcome after scheduler recovery. None of the declared 31 new tests exercises invalid returned structured output.

**I3 — Token-budget rejection rolls back the real response/checkpoint that recovery needs.**

Location: `infra/TraceStore.java:1219` (within `responseInitial`, starting `:1207`).

Response persistence and `consume(view,"tokens",...)` are in the same protected transaction. `consume` calls `budgetAfter`, which throws when actual usage exceeds the remaining token budget or totalDurationMs (`:1032`, `:1037`). Consequently an otherwise valid final response with known usage above the engine budget rolls back the invocation response update as well as the attempt response/checkpoint. A later recovery sees only DISPATCHED and reports MODEL_RESULT_UNKNOWN, even though this owner actually received and validated the response. For example, a genuine private run with token limit 10 and the already-used controlled provider usage 66 reaches this path; the model service does not enforce the engine run's remaining token budget for it.

Preserve the protected paid response/checkpoint independently of rejecting further logical progress for a budget overrun. Record/account the returned usage once with an explicit supported over-budget outcome rather than losing the response or claiming reconciliation; do not retry/refund. This can remain inside the permitted existing classes, and must retain fresh authority and node/run fences.

Focused new verification needed: `InitialSenderFactsPostgresTest.overBudgetUsagePreservesResponseAcrossRecovery` using the existing controlled-usage endpoint and a smaller run token budget. Assert a durable response/checkpoint, truthful once-only usage state, no successful logical result beyond policy, and no HTTP resend after recovery. Existing `explicitProviderUsageIsAccountedOnce` (`:97`) tests usage within budget only; the durable-response recovery test does not exercise a budget rejection.

**I4 — The mandatory meaningful-output-format integration positive was replaced by TEXT only.**

Locations: `infra/InitialSenderIntegrationFixture.java:235`, `infra/InitialSenderIntegrationTest.java:11`.

The fixture overwrites output with `{maxTokens:256}`. The positive asserts raw bytes, ingress marker, chunks and missing usage, but never sets a JSON/schema format or proves the expected final body against one. The report correctly discloses that provider wire has no response_format; disclosure does not fulfill mandatory positive item 3 in the binding plan. The implementation admits JSON output and has a new post-response validator, so the text positive also leaves the newly added success branch unverified.

Add one actual RunService -> reviewed model -> SDK/provider positive with a meaningful supported format (for example, a JSON object constrained by a small schema and a matching provider result). Verify original canonical bytes, native response_format behavior where applicable, exact durable response/logical output, one dispatch/commit and response recovery without HTTP. Retain the current missing-usage text positive. Do not equate synthetic parser inputs or the four selected model guard methods with this missing end-to-end positive.

Focused new verification needed: `InitialSenderIntegrationTest.ownedDriveCommitsSchemaConstrainedInitialResult`, plus the invalid-output boundary in I2. If the component is deliberately restricted to TEXT, that conflicts with the authorized positive and the currently admitted formats: root/human must resolve the scope explicitly rather than marking this requirement satisfied.

### Minor — 1

**M1 — The new model-input SQL size guard is placed in a query that excludes model-input.**

Location: `infra/MaterialScopeStore.java:141`.

The CASE predicate mentions kind=model-input, but the same query's WHERE admits only run-snapshot/run-inputs/run-version-refs. It is therefore always the unrestricted branch. The actual model-input traversal enters via `currentScopes` (`:261`), which still selects scope::text before `initialScope` parses that row (`:390`). Normal produced scopes are already bounded, so this is not evidence of an ordinary unbounded provider response; it is an ineffective defensive addition in the new persisted-material path.

Remove the dead SQL condition or move the intended bound to the query that actually reads model-input, with explicit rejection for an oversized/null projection before parsing. Keep ordinary-kind behavior unchanged. Ponytail judgment: delete the dead conditional rather than adding an abstraction; no broad file split or new framework is justified by this slice.

## Evidence and execution limits

The supplied contextual patch was read once in successive finite ranges, covering all 1,648 package lines. No Git command or invented commit identity was used. Local byte/hash checks confirmed:

| Input | Bytes | SHA256 |
|---|---:|---|
| Base `.local/iam-model-initial-stream-fix/final2-source/source.json` | 135063 | dea83bd7cc0a1ff997379b894280343aa879db618a71092cf03f54a7d2c1ca0a |
| Candidate `.local/engine-initial-sender/final2-source/source.json` | 135941 | 75cec022e9048a1bd3da3271a44ea0bf55615cb283d3d76db9587d3d5b6ff960 |
| `.local/engine-initial-sender-review-package.md` | 104170 | 788e9e90bb982117851cba1897f1e3c32411f92bce7dc46eb0edacb092ec78fc |
| `.local/engine-initial-sender/native/change-native.patch` | 102281 | 2c33323acef409a550448e9ced3ad176920891def4f871f43e0746c1e46adeb5 |
| Author report `docs/evidence/engine-initial-sender-report.md` | 28758 | c20bf44c527fcdab4b057f85b8930183e3ff17350893db82cb2b94252342c37f |
| `.local/engine-initial-sender/test-summary.json` | 81795 | 47dda03761b047a6f3f7f4f154c43934a091e6c1cf2979c9d8f6eac96932e8aa |

The package describes 7 MOD + 4 ADD + 673 unchanged / 0 delete. Root separately reports its complete physical source comparison; this reviewer did not redundantly rehash all source blobs.

Safe projection of test-summary independently counted 31 final2 new methods, 18 successful selected regression methods, and 4 passed model Surefire methods. Selected final entries show zero failures/skips/failed containers; earlier failing cohorts remain present. These are inspected evidence records, not tests rerun by this reviewer. The model XML/Properties/argfiles and private helper bodies were not dumped. No new tests, builds, HTTP, PG, processes, resource probes, Git operations, child agents or background jobs were run.

### Focused outside-diff checks

1. **Output admission and policy semantics:** read unchanged `domain/OutputPolicy.java:42`, `:128`; `application/ContextAssembler.java:64`, `:84`; finite output-specific sections of the reviewed model `InitialModelInputVerifier.java:398` and `ModelCalls.java:907`, `:960`; and frozen contract lines 173–183. This establishes I1/I2 and the supported format mapping, without auditing unrelated model behavior. An initial guessed application/OutputPolicy.java read failed; the class was found in domain and read there. This was a static path error, not test evidence.
2. **Frozen route authenticity:** read unchanged `application/FrozenModels.java:13`; it verifies entry digest and duplicate selection. No mutable route fallback found in the changed caller.
3. **Inherited transaction/claim/commit mechanics:** read the exact existing TraceStore writable/protectedAtomic/claim/commit/requireDispatch functions invoked by new initial methods (`:535`, `:588`, `:764`, `:858`, `:1284`). Their bodies are outside the new method hunks; this check was necessary to judge real locks, attempt references, top-level transaction completion and CAS rather than assuming them from wrapper names. Read `consume/budgetAfter` (`:1003`) for the concrete response-rollback risk in I3.
4. **Incomplete changed function context:** the ModelInputMaterials assemble hunk ends mid-function; read its remaining assembly section (`:152–218`) to establish where output goes and whether declaration validation occurs. The ModelExchange exchange hunk ends near its opening; read only its output-policy branch (`:74–135`) to compare PASS_WITH_FLAG semantics. The MaterialScopeStore dynamic hunk omits its query caller; read `currentScopes/currentScope` (`:256–298`) for the newly added size-check placement risk M1. Read ModelInputMaterialStore localBinding (`:611`) to verify the raw-reader's latest-check binding.

No entire changed production file was reread. Additional targeted rg calls obtained precise line references and method locations only. No unrelated project crawl, private credentials, old helper content or sibling business code was accessed.

## Items for root qualification — not inferred from this diff

- ⚠️ Reconcile actual source/test/artifact cohort identities and private event/XML totals through `.local/engine-initial-sender/test-summary.json`, `regression-overlay/final-manifest.json`, `runtime-capture/manifest.json` and `fresh-artifacts/verification.json`. The selected business-method overlay claim excludes the unselected redirect method with two TLS setup substitutions; do not promote it to an unchanged/executed-method claim.
- ⚠️ Independently qualify finite runtime/classpath/JAR provenance, exact resource chronology, preseal/command completion and shutdown using `closure-summary.json`, both DB closure records, final control/inventory and last-command completion supplement. Static code review cannot certify physical closure, secret handling or process identity.
- ⚠️ Retain the registered execution NONCOMPLIANT disposition for the two fixture-constant stdout incidents and all earlier setup/product failures. No credential value is reproduced here. Passing code tests cannot erase these execution qualifications.
- ⚠️ Default IAM/D3, public CHAT creation/observation, real IAM/provider calibration, global status, external protected documents and the four frozen contracts remain root's independent boundary checks. Existing regression evidence covers selected boundaries, not every timing interleaving.

## Assessment

**Spec Compliance: ❌ Issues found (4 Important, 1 Minor; 0 Critical).**

**Task Quality: Needs fixes.** The core authority/byte/dispatch integration is substantial and appropriately uses existing components, but unsupported output configurations can reach a paid call, frozen invalid-output semantics are not respected, and a budget exception can destroy the exact response needed for safe recovery. Resolve these and supply the explicitly required structured-format evidence before code acceptance.

**ALL / noBackground / NOTAccepted.** Only this review report was written. Root controls acceptance and any subsequent fix grant.
