# Engine initial sender implementation handback

Disposition: **ALL / noBackground / NOTAccepted**. Code is submitted for independent review, not self-accepted. Whole execution remains **NONCOMPLIANT**; passing tests do not erase historical or current execution deviations. No commit, deployment, main Git operation, public CHAT admission, default IAM opening, protected-document promotion, or global task completion is claimed.

## Input, scope and final identity

P = `G:/207/项目/算法重组工具/代码/agent-platform`. All command workdirs were explicitly P. Implementation remained within the seven production modifications, V219 and three new test files authorized by `.local/engine-initial-sender-brief.md` (14799 bytes, SHA256 `44af8c6a3f7be5acbe3643bdaedaf399464a4804b244b72bce7d59c1cd2412f9`) and the complete static plan (26443 bytes, `eb480e16e89a3ef286a39e8e6c09f635f8381e0fcde88d61d6d7b7326e0a33b2`). SDD implementer, TDD, systematic debugging, Java/Spring, ponytail and verification instructions were applied; no child agents.

Accepted input is CODE680: `.local/iam-model-initial-stream-fix/final2-source/source.json`, 135063 bytes, `dea83bd7cc0a1ff997379b894280343aa879db618a71092cf03f54a7d2c1ca0a`; certificate `.local/iam-model-initial-stream-code-acceptance.json`, 9008 bytes, `0bedaef3bd6d911eff23bd8e9a7fb290a2f4a9409356a28df06d8bf37ade15b8`. This acceptance is code-only.

Final source is `.local/engine-initial-sender/final2-source/source.json`, **135941 bytes**, SHA256 **`75cec022e9048a1bd3da3271a44ea0bf55615cb283d3d76db9587d3d5b6ff960`**, with readonly blobs in sibling `source/`: **684 files = 7 MOD + 4 ADD + 673 unchanged, 0 deletes**. `canonical-verification.json` rehashed every canonical source file against final2. Five authorized internal protected documents match their recorded after-hashes; the three external documents were not read or hashed and remain root's responsibility. The frozen design/four contracts and task status were not edited.

Earlier final1 source remains separate: `.local/engine-initial-sender/final-source/source.json`, 135941 bytes, `dcc5b91dbc5d85a4f2291b2215c78c2a133f07d1a9d916d528717e6311c2096b`. Final1 to final2 only corrected the new positive test's metering field assertion and made UNAVAILABLE explicit; all seven production files are byte-identical. Intermediate successes are not relabeled final2.

## Requirement-to-file implementation

| File under engine-runtime | Concrete behavior |
|---|---|
| application/RunService.java | Explicit trusted construction enables private owned initial CHAT branch from actual drive and driveRecovered. Existing owned FLOW dispatch remains. Private TASK/FULL/CHAT fixture admission is not public CHAT creation/status/events. Unsupported initial shapes fail closed. |
| application/ModelInputMaterials.java | Actual prepareInitial precedes invocation; validates finite initial profile and rejects RETRY/positive maxRetries without changing legacy policies. Exposes bounded original-byte reader under genuine retained current authority. |
| infra/ModelInputMaterialStore.java | Computes canonical UTF8 once, stores it with original hash/manifest, revalidates exact immutable raw/manifest/parent/binding identity under current authorization. Historic NULL and byte mutation fail; no JSONB reserialization/backfill. |
| infra/MaterialScopeStore.java | Handles model-input as the one immutable hashed material kind with exact m_ locator/SHA, owner/root/manifest/real parent graph. Other mutable revision rules remain. No unknown-kind or containsAll bypass. |
| infra/TraceStore.java | Actual protected attempt/request snapshot, frozen route, call-budget consumption, invocation and DISPATCHED/sdk_attempts=1 commit before HTTP. Checks independent run dispatch and node lease fences. Real response/checkpoint precedes fenced logical commit; response recovery reuses same attempt without HTTP. Durable marker without response remains unknown. |
| application/ModelExchange.java | Composes real current/material/claim/transport path, checks final actualModel against frozen route and no tool calls, persists full response before output-policy validation/logical commit. Missing usage stays unavailable/unreconciled. |
| infra/RuntimeClients.java | Explicit origin/trusted clients only; borrowed genuine retained forwarding. Sends original bytes once with once-encoded relative ref. Instance-owned Apache transport, no retry/redirect/proxy/auth/cookie/compression fallback. Strict incremental UTF8/SSE, bounded line/event/total allocation, final/error/truncation validation, owner-thread current/callback gates, concrete-request-only timer cancellation. |
| src/main/resources/db/migration/V219__initial_request_utf8.sql | Nullable bounded original canonical TEXT, 1..2 MiB when present; existing row immutability retained. No historical rewrite. |
| InitialSenderIntegrationFixture.java / InitialSenderIntegrationTest.java / InitialSenderFactsPostgresTest.java | Genuine issuer/producer and private run, separate engine/model/provider JVMs, actual RunService caller, actual PG transaction/lease/current/dispatch faults, actual first-chunk observation, restart recovery and protocol fault cases. |

Paths in the first seven rows are below `src/main/java/cn/agentplatform/engine/`; the three new tests are below `src/test/java/cn/agentplatform/engine/infra/`. No new production class/framework/DTO/dependency/POM/config/common/model/AgentDriver change.

The sender uses header limit 10 seconds, body idle 5 seconds, total 15 seconds, taking the shorter actual execution/application limits. Application time uses the injected authority clock; DB lease/dispatch remaining time uses DB realtime, avoiding mixed clock domains. The timer only cancels a concrete request; authority acquisition and callbacks remain on the owner thread. Parsing bounds are 64 KiB line, 2 MiB event, 4 MiB stream, strict duplicate JSON/depth 32 checks. Cancellation does not claim external provider effects were undone.

## Real RED and intermediate learning

`positive-model-control` first established the genuine signed/material/model chain (one actual method, 12954 ms). `baseline-drive-red` then called actual RunService.drive: the accepted engine rejected the new model-input material kind before the required initial sender path. This is a real behavioral RED, distinct from discovery/compile/fixture failures.

`output-retry-profile-red-001` is a second real behavioral RED: unsupported RETRY actually dispatched and committed before the finite profile guard was added. Its send/commit facts remain; it is not rewritten as a zero-send failure.

`initial-drive-green-attempt-006` was the first complete intermediate GREEN (11915 ms), run22/attempt5, original885 bytes/SHA `52d4b63b2c10f003cc1d62494b52cd3b09601cff80fcb51e7dfe93d95ef6519c`, first chunk before held provider final. It is not final-source acceptance. Rollback/ACK-loss, response recovery and preparation recovery had their own later intermediate cohorts, retained separately in test-summary and original phase directories.

## Final2 primary chain and independent facts

`final2-positive` called actual RunService.drive through genuine producer/material/claim, separate HTTP model verifier/binding/tokenizer/SDK and provider. At model ingress, run52 had real attempt15, request ref `pg:attempt/15`, invocation1/send1/commit0, so the dispatch transaction was independently visible before model business execution. After response, invocation1/send1/logical commit1 and response ref `pg:attempt/15/response` were durable.

Actual engine ingress was **887 UTF8 bytes**, SHA `8fd456b4008f47de1cb89ef363826e7f5f8ccb3f5f454037589232e4f88dae87`, identical to the immutable frozen request hash. The test checks CJK, escaped content and the original decimal spelling. Provider wire is a different byte domain: **310 bytes**, SHA `b8e30e08e573d1cf043b6d08d5477be6cd82ba5b959237c7b7a3786d49d51332`, independently identical to model metering.initialRequest.wireUtf8. Neither digest is substituted for the other.

Actual first chunk observation: `2026-10-22T13:48:26.1782667Z`; provider held-final release: `2026-10-22T13:48:26.1822640Z`. Two provider text deltas accommodate the pinned SDK lookahead; no single-delta guarantee is claimed. Provider usage omitted -> model `UNAVAILABLE`, totalTokens NULL, engine usage_accounted false. A separate controlled-usage phase proves propagation/accounting once, not real tokenizer calibration. The primary wire has no response_format: this is a text-output positive, **not** a JSON/schema output-format positive.

`behavior-observations.json` binds the above to original fact references. Silent stream: one ingress, independently recorded header-to-post-drive-observation 5012.5005 ms. Active drip: one ingress, 14285.3508 ms; total timer starts before headers. These are wall observations including owner cleanup/check overhead, not exact cancellation latency claims. Redirect has one independently recorded ingress and was never followed.

Final fault tests preserve the distinction between real rollback/no send, engine commit ACK loss/durable marker without response, model SENDING/SENT ambiguity, and already durable engine response. Actual recovered flow is claimSpecific -> scheduler.poll -> RunService.driveRecovered. Preparation may first-send once; marker recovery never resends; response recovery commits the same attempt once without a second HTTP. Node recovery owner is an independent UUID36; run dispatch owner/generation remain independently verified. Callback failure, cancel, node lease loss, dispatch generation loss and current revocation/outage cannot publish a logical result. Revocation can prevent diagnostic mutation, so prior durable facts are retained rather than bypassing authority.

## Final tests and source cohorts

**53 final passing methods: 31 new final2 direct-JUnit integration methods + 18 selected existing PG/HTTP regression methods + 4 normal Surefire model raw/wire/scope methods.** Counts come from real events/XML, not adapter self-checks. All selected final runs have zero skipped/failed methods/failed containers. Commands are JDK21 with bounded 30-second children; services are separate 90/30/10-second total/readiness/stop phases. Raw XML, Properties, argfiles and logs stay private.

New direct tests use sealed run helper, `-Direct -EngineTests`, exact `Class#method`. Each phase has test.prelaunch/test-command/direct-junit-events/direct-junit-derived XML, source SHA binding and closure references. Existing regressions use sealed regression helper and original method bodies with the finite fixture overlay described below. Exact paths, counts and elapsed outcomes are in test-summary.json.

| Final phase | Actual method | ms |
|---|---|---:|
| final2-cancel-chunk | cancellationAfterFirstChunkCannotPublishOrCommit() | 10024 |
| final2-dispatch-before | dispatchGenerationLossBeforeSendCannotPublish() | 3360 |
| final2-drip | activeDripHasTotalDeadline() | 17066 |
| final2-duplicate-final | duplicateFinalNeverCommits() | 2364 |
| final2-error | errorEventNeverCommits() | 3184 |
| final2-final-revoke | finalRevocationPreservesModelFactsWithoutEngineCommit() | 12393 |
| final2-freeze-ack-loss | committedDispatchAckLossNeverResendsDuringRecovery() | 4300 |
| final2-freeze-rollback | realFreezeRollbackProvesNoSend() | 3095 |
| final2-historic-null | historicNullRawCannotBeReserializedAndSent() | 2425 |
| final2-history | historyProfileRejectsBeforeDispatch() | 2435 |
| final2-invalid-utf8 | invalidUtf8NeverCommits() | 2662 |
| final2-known-usage | explicitProviderUsageIsAccountedOnce() | 14099 |
| final2-lease-before | leaseLossBeforeSendCannotPublish() | 3285 |
| final2-lease-chunk | leaseLossAfterFirstChunkStopsExposure() | 10789 |
| final2-malformed | malformedEventNeverCommits() | 2354 |
| final2-missing-final | missingFinalNeverCommits() | 2435 |
| final2-model-ack-loss | modelCommitAckLossKeepsSendingSentWithoutEngineResend() | 9915 |
| final2-observer-failure | observerFailureCancelsWithoutLogicalCommit() | 14188 |
| final2-outage-before | currentOutageBeforeSendKeepsDurableUnknownMarker() | 2736 |
| final2-outage-chunk | currentOutageAfterFirstChunkStopsExposure() | 9609 |
| final2-output-retry | outputRetryProfileRejectsBeforeDispatch() | 2574 |
| final2-oversized | oversizedLineFailsBeforeUnboundedAllocation() | 2403 |
| final2-positive | ownedDriveCommitsInitialResultAfterRealMaterialPreparation() | 12870 |
| final2-preparation-recovery | preparationRecoveryMaySendExactlyOnce() | 11921 |
| final2-raw-change | changedOriginalRawCannotBeReserializedAndSent() | 2179 |
| final2-redirect | redirectIsNeverFollowed() | 5202 |
| final2-response-recovery | durableResponseRecoveryCommitsSameAttemptWithoutHttp() | 14352 |
| final2-revoke-before | revocationBeforeSendKeepsDurableUnknownMarker() | 2743 |
| final2-revoke-chunk | revocationAfterFirstChunkStopsExposure() | 9906 |
| final2-silent | silentStreamHasIdleDeadline() | 7683 |
| final2-tools | toolsProfileRejectsBeforeDispatch() | 2355 |
| regression-default-guard | unchangedDefaultIamDeploymentGuardStillRejectsBeforeListener() | 6043 |
| regression-flow-legacy-deny | protectedLegacyEntryPointsDenyBeforeAnyPayloadReadOrMutation() | 2439 |
| regression-flow-materials | actualClaimCommitAndEventsAppendRevisionsWithRealParentsAndPreciseNullTimePayloads() | 3549 |
| regression-legacy-main-003 | finalRequestPrecedesSdkAndResponseIsReusedAcrossReplicas() | 4405 |
| regression-legacy-summary | summaryIsAnIndependentDurableBudgetedCallAndRestartReusesBoth() | 2380 |
| regression-material-http-current-002 | currentNarrowingWrongRootMissingParentAndStoredScopeMismatchDenyAtTheirOwnBoundary() | 7862 |
| regression-material-http-identity-002 | signatureIssuerAudienceTargetActionAndOwnerMutationsAreDenied() | 7206 |
| regression-material-http-positive-003 | actualSignedHttpsReadsReusableMetadataAndHeadersWithoutPayload() | 7516 |
| regression-material-http-selectors | unsignedPortalWrongCallerAndWrongSelectorsCannotRead() | 7440 |
| regression-material-narrow | narrowedCurrentDeniesBeforeAnySourceAndCannotRemoveOldObligations() | 3766 |
| regression-material-replay | replayRecomputesFullRequestAndNeverCreatesDispatchFacts() | 3986 |
| regression-material-stale-002 | latestSuccessfulDecisionRejectsOlderWriterBeforePayload() | 3817 |
| regression-material-union-002 | persistsRequestAndScopeAtomicallyWithFullParentUnion() | 3629 |
| regression-owned-chat-closed | chatSnapshotTypeNeverStartsOwnedWorker() | 4405 |
| regression-owned-flow | originalReserveCreatedAndPollRunServiceFlowDriverCompleteExpiredPortalThroughDelegated() | 6111 |
| regression-owned-materials | executionUsesOnlyMaterializedWorkAndItsRealAttemptPreservesPreviouslyConsumedParents() | 4438 |
| regression-scheduler-cancel | cancelledPendingWorkNeverRunsAndRemainsDurableAfterSchedulerReplacement() | 2654 |
| regression-scheduler-generation | oldGenerationCannotRenewOrReleaseSuccessor() | 2089 |

Model Surefire `final2-model-focused-surefire-003` completed in 12478 ms: InitialModelInputVerifierTest.producerNumericCanonicalShaSurvivesStrictParsingAndMutationDeniesBeforeGet and closureOwnerRootInitialSnapshotAndUnionCannotBeChangedWithOnlyManifestRehash; FrozenInitialWireGuardTest.unchangedActualSdkStreamPassesThenMutatedNativeModelIsDenied and explicitlySyntheticParserInputsAreStrictBoundedAndPayloadFree. Each suite reports 2 tests/0 failures/0 errors/0 skipped. Native XML copies are private in final2-model-focused-surefire-passed/. The first two failed Surefire cohorts remain separately copied, not overwritten.

Overlay: six fixture/setup sources under regression-overlay/source/. Final manifest includes exact accepted original/adapted SHA and independent final class capture. EngineTestDatabase points only to second owned DB; RunAuthorityFixture/ModelInputMaterialFixture constructors and OwnedFlowScheduler setup use that endpoint. ModelInputMaterialHttpTest TLS setup reads fresh private password/alias. InitialModelInputConsumerFixture's two TLS unlock expressions are changed only in owner Maven workcopy. No canonical existing test is edited. Twenty method bodies are byte-identical and every actually executed existing business method body is unchanged. The **unselected** old `testTransportRefusesRedirectAndDoesNotForwardAnyTicket` contains two TLS setup expressions changed by the same adaptation; it is explicitly excluded from unchanged-body and executed-test claims. Early overlay manifests/captures remain their earlier cohorts; final-manifest.json is authoritative for final overlay bytes.

## Failure and execution qualification register

No failure below is erased by a later success. All direct phase failures and guard command outcomes are tabulated in test-summary.json/closure-summary.json, with original private records and exact references. Native `diff --no-index` exit1 means differences found; it is not a test failure.

| Cohort/event | Actual failure or qualification |
|---|---|
| Initial discovery | Guessed source paths and a `.tools` recursive file search unintentionally traversed m2; session21168 was interrupted and actually exited1. Static discovery only, not behavioral RED. Later searches used finite source/owner prefixes. |
| baseline-compile | 30-second timeout (30098 ms), actual guarded closure; continuation failed readonly own resource output. Only own work/target readonly flags were cleared; later normal continuation passed. No cap increase/canonical/cache mutation. |
| helper preparation | Missing cached launcher reference caused compile failure before corrected classpath. No product RED. |
| initial-drive-green-attempt-001 | Fixture model origin had trailing slash; assembly failure. |
| attempt-002 | Real committed marker then Java number-type comparison rejection. Before-call stack and absent downstream records are retained; **no independent zero-ingress counter proof** is claimed for this old failed phase. |
| attempt-003 | Injected authorization-clock and realtime DB lease comparison mixed domains and rejected. Fixed by separate remaining durations/minimum, not moving clocks or extending leases. |
| attempt-004/005 | Actual model ingress but header deadline failure; 005 preserves actual receiveResponseHeader SocketTimeout cause. Ingress-positive alone is not full GREEN. |
| response-recovery-001/002 | Real persisted response but recovery owner exceeded existing varchar64. 002 retains SQL integrity cause; UUID36 correction did not change V200 or dispatch fence contract. |
| preparation-recovery-001 | Transport unavailable with incomplete cause evidence; not proven header timeout. Later isolated fresh run002 passed after explicit header/idle separation. |
| output-retry-profile-red-001 | Genuine unsupported profile dispatched/committed. Real behavioral RED; final profile guard later passed in a new phase. |
| final-positive-001 | Final1 test used wrong metering field; actual product chain succeeded but test failed. Test-only correction frozen as final2; not a production behavioral RED. |
| native first check | --git-dir/--work-tree were owned but missing -C made apply --check resolve relative paths in explicit cwd P. Read-only check, no writes; 11 path failures retained. |
| native first replay | Corrected owned cwd check/apply returned0 but 11 hashes differed due stdout/newline conversion. Exit0 was not byte-equality evidence. Original patch/replay mismatch retained. Native --output into fresh replay2 later matched all684. |
| Later preparation | Absolute source argument passed to relative-only compiler helper, guessed timer .json suffix, a guessed model test filename, and PowerShell reserved Error variable assignment each failed. No behavioral RED. |
| regression-legacy-main / -002 | Wrong test package failed discovery; corrected package then failed URL validation because private URL had timeout query parameters. Fixture-only validation fixed. |
| regression-material-union/stale first runs | Fixture filename derived from URL included query; Windows InvalidPath. Database-name field now used. |
| regression HTTP first runs / positive-002 | Missing new trust.p12, then server TLS fixed unlock/alias remnants. All are before-business setup failures; fresh trust generated, only overlay TLS setup adapted. |
| model Surefire first / second | Four errors each: fork lacked short UDS property (loopback setup), then missing consumer TLS property. Third cohort uses bounded fork properties and fresh TLS setup, four passed. |
| overlay proof first two | Overbroad claim that every test method body was unchanged rejected the unselected redirect method's TLS setup substitutions. Final proof narrows to all executed business bodies and explicitly lists the unselected setup-modified method. No source rewrite/retest to erase history. |
| Two fixture-constant output events | Two source-search tool outputs exposed an accepted synthetic provider credential literal. Root registered this current execution deviation. Do not reread/reprint the value; no fresh vault/TLS/signing/env secret output is claimed. Raw conversation outputs remain original evidence and are excluded from public artifacts. No fabricated filesystem digest for a conversation-only output is supplied; root's registered execution qualification remains controlling. Canonical accepted fixture source bytes are unchanged. |

The new stdout incidents independently keep execution NONCOMPLIANT. They do not imply code acceptance/rejection; root reviews code and evidence separately. Raw sensitive records remain private; public references/hashes do not contain their values. Creation helper setup metadata has an inherited `closed` flag that is not lifecycle proof; actual role/session/schema closure records and chronological commands define ACTIVE/CLOSED.

## Native, runtime and fresh package provenance

Native contextual `-U10 --binary` patch: native/change-native.patch, 102281 bytes, SHA `2c33323acef409a550448e9ced3ad176920891def4f871f43e0746c1e46adeb5`. It uses an owner-only bare store/no commits or remotes. Replay2 independently starts accepted680 and applies the raw native patch; all684 final hashes match. The original stdout-converted patch/check/replay failures remain. No invented main-repository commit identity.

Finite actual-runtime capture preceded fresh package: runtime-capture/manifest.json, 212366 bytes, SHA `2f580726e4ca0cf2d6f8b7ae99f857645ab5cd0cad360553c97ca8c2439db0aa`; 667 files, 638 Java21 major65 classes,156 exact classpath dependency JAR references. It captures actual engine/model/common classes and helper classes then present, not retrospective binaries for older helper cohorts. Later final regression overlay/model-test classes have separate final-manifest capture. Sealed helper sources and compile inputs retain each earlier cohort.

Normal own offline Maven `final2-fresh-engine-package` exit0/12505 ms (`package -DskipTests`, pinned JDK21, own workcopy/m2, one Maven mutex). Final1 normal test-compile had passed24571 ms; final2 changed positive test was compiled1135 ms and then all final2 selected tests ran. Package did not replace testing with skipped tests. Fresh engine142 production class/resource entries and common108 entries match actual captured bytes; all engine classes are major65. Embedded fresh common JAR SHA equals standalone fresh common. Engine classpath/JAR contains no model business JAR/classes; model runtime classpath contains no engine business classpath. Common test fixture is permitted.

Fresh engine JAR: 51835089 bytes, `8e37dd0c4d2ca8c1969c4bce59bdb18a1b79a504bde68664df90045ea97e4711`.
Fresh common JAR: 259737 bytes, `8e5d94737c04cce76ada814b3c7833779394273ca8d1355a329f6c53b4b743fd`.
Fresh common test JAR: 380314 bytes, `0e5e380a729bf149cd5a2e9b3f95638ba693e1205c2ec4a9f90b76cb0fded2da`.
All are under `.local/engine-initial-sender/fresh-artifacts/`; verification.json contains per-entry provenance. The locked canonical JAR/unknown holder and main ports were untouched.

## Physical closure and finite handback

First owned DB `engine_initial_sender_fc09c92afb5f4a2fb992cfb87340f295` closed before second creation; second `engine_initial_sender_7d8ab94874264026aff30a202a10ab0d` closed after regression. Both retain data; sessions0, memberships0, exact business roles NOLOGIN/nonprivileged, schema privileges removed. No CLOSED DB/key/Redis/port was reopened. Fresh random master and fresh TLS/signing resources were used; second TLS password was newly random and protected. No real IAM/provider environment was claimed.

closure-summary.json aggregates 48 real phase closures and326 guarded command records as of package completion. Every phase has disposed runspaces, empty closure errors, and exact Linux Redis PID/start identity absent (not merely proxy exit). First DB closure command2638 ms; second2296 ms. Timed-out historical commands remain timed out. The later finite filesystem seals are separately referenced by final-control and last-command completion supplement. No global process/port census, unknown holder kill, canonical Maven/Git, deployment or background service remains.

Finite public inventory is a reference/hash index, not a giant historical ZIP. Sensitive configs, key stores/master files, source raw HTTP bodies, logs, XML/Properties and argfiles are excluded from public content; their necessary private evidence is referenced by hash only. Inventory/control exclude their own future self-hash and final completion supplement explicitly. The final supplement records actual last guarded seal command termination; no file claims to hash itself or a future output. All resources were closed before report/inventory/control creation.

Limits/concerns for root review: this is one private owned initial TASK/FULL/CHAT component; default IAM, public CHAT create/status/events, D3 and all other history/tools/correction/summary/FLOW-model profiles remain closed/unaccepted. Primary positive is text output and does not establish a JSON/schema output-format positive. Structured output failure/correction loops are not added. Live IAM/provider calibration, capacity/deployment, distributed UNKNOWN reconciliation/refund and token replay remain outside scope. Chunks are transient and no external-effect rollback guarantee follows from cancellation. Tests cover selected concrete boundaries, not a universal timing/interleaving proof. Root independently reviews code, resource chronology, raw source and unresolved coverage qualification before any code acceptance. T01 remains the only globally complete task; T09/T11/T12 ongoing and T33 not started.

## Exact primary artifact references

All following paths are relative to P. The source/fresh manifests provide finite subordinate references. Report's own digest, future inventory/control and the actual last-command supplement are supplied in handback; they cannot be self-included here.

| Artifact | Bytes | SHA256 |
|---|---:|---|
| .local/engine-initial-sender/final2-source/source.json | 135941 | 75cec022e9048a1bd3da3271a44ea0bf55615cb283d3d76db9587d3d5b6ff960 |
| .local/engine-initial-sender/canonical-verification.json | 3598 | 7a909281c1d5e55855f639886b66e255debd54a9db06efa3dcfe77e528385426 |
| .local/engine-initial-sender/protected-internal-verification.json | 1567 | e28d29002bebf45cb75661b8aa2130c41227ff30e68037b0ec27badf8837533d |
| .local/engine-initial-sender/native/change-native.patch | 102281 | 2c33323acef409a550448e9ced3ad176920891def4f871f43e0746c1e46adeb5 |
| .local/engine-initial-sender/native/replay2-verification.json | 282 | 967459d43071dbde4b3ed5f1f81f5c0e3c66d512e5beadb7b5f94eb4d0411f71 |
| .local/engine-initial-sender/runtime-capture/manifest.json | 212366 | 2f580726e4ca0cf2d6f8b7ae99f857645ab5cd0cad360553c97ca8c2439db0aa |
| .local/engine-initial-sender/regression-overlay/final-manifest.json | 22379 | 669b7b454a62fa10dbece8321d7be4bc6f7baf1c6c40e7300d6a814b8371cfec |
| .local/engine-initial-sender/test-summary.json | 81795 | 47dda03761b047a6f3f7f4f154c43934a091e6c1cf2979c9d8f6eac96932e8aa |
| .local/engine-initial-sender/behavior-observations.json | 1922 | 8934d5cc9f6792dea3be5cb91ebe461fc0340ef4504b8eb910b3466b2df5482a |
| .local/engine-initial-sender/closure-summary.json | 234563 | d0e9be1aec0bb7b8281cdfccdcd9e844237bcaae673c748f657cf8f239b59798 |
| .local/engine-initial-sender/setup/db-closure.json | 1097 | e047efd87219f37d11c1a31864d1a5af4bbe951e8dbb14973ae2b9faae112aee |
| .local/engine-initial-sender/regression-setup/db-closure.json | 1095 | 6e4ac5c1fc43064bc92b6cbd7eaab40b0890e839795864ed3e3112c7bc213ccc |
| .local/engine-initial-sender/fresh-artifacts/verification.json | 71303 | 685c2771f69556a8b5eece8d628f132202fb1c5391f66831c8c6a25bd068ed99 |


## Review-fix wave — 2026-10-23 — ALL / noBackground / NOTAccepted

本追加段只报告 engine-initial-sender-fix 新 owner。原28758字节报告保持逐字节不变；candidate684仍非代码接受状态，accepted680不变。执行资格为 NONCOMPLIANT；实现完成不构成代码接受或全局阶段推进。无子代理、无主仓库Git/提交/部署，无旧库/旧服务重开、无原53全量回放。

### 范围和修复

顺序：源/API核对 → fresh真实TEXT正控 → I1/I2/I3真实RED → 最小实现 → 冻结各源cohort → 结构化/权限/预算定向验证 → 当前提交窗口追加RED/最小修复 → final4冻结与受影响验证 → 有限旧接口回归 → 捕获、原生差分重放、正常打包、关闭及交接。fix-sequence.md保留实施前顺序。

final4为684文件，candidate684→final4：7MOD/677same/0ADD/0DELETE；accepted680→final4：7MOD/4ADD/673same。仅授权文件中的 ModelInputMaterials、ModelExchange、TraceStore、MaterialScopeStore、InitialSenderIntegrationFixture、InitialSenderIntegrationTest、InitialSenderFactsPostgresTest 有改动；RunService无需改变。无 OutputPolicy/common/config/POM/迁移 canonical 改动。

* I1：prepare/request读取及dispatch重新调用既有OutputPolicy；RETRY和显式非零maxRetries拒绝，未显式声明maxRetries时不把既有默认2误作启用重试。巨大整数沿既有完整类型/范围校验拒绝。后续真实测试证明OutputPolicy构造未拒绝bogus schema type，故final4另以 pinned networknt 1.5.6 内置2020-12元schema做声明语义预检。仅精确classpath资源白名单，无网络/文件fallback；固定有界诊断。不会将响应文本校验提前为声明校验。
* I2：真实response先持久保留，之后在受保护事务派生outputValidation/structured或initialFailure。PASS_WITH_FLAG返回原文、无伪造structured，成功一次；TERMINATE记录OUTPUT_INVALID稳定失败。checkpoint修订/节点/attempt/run/事件沿现有权限、父集合和floor机制原子提交；不校正重试、不再HTTP。legacy fail本体未改变。
* I3：已支付response、attempt response_ref、checkpoint及已知usage不会被预算异常回滚；已知66 tokens真实记账一次，超token/time预算进入稳定BUDGET_TOKENS/BUDGET_DURATION失败。未知usage仍未记账，不伪造零消耗核销。dispatch对新调用检查deadline；收到response后的scheduler恢复可越过耗尽的engine时限完成保护终态，仍须当前权限/dispatch/node lease有效。只捕获既有预算两种错误，不吞其他故障。
* I4：真实RunService→model→SDK→controlled provider发送小schema对象并返回匹配JSON；核对canonical bytes、实际native response_format.type=json_schema及json_schema.schema字节域、durable raw/derived output、一发送一调用一提交；实际claimSpecific→poll→driveRecovered复用同一响应。没有声称真实外部provider实现schema强制。TEXT缺失usage控制另存。
* M1：移除ordinary-only query死CASE，把model-input scope大小投影上限放到真正currentScopes/currentScope读取处；超限/NULL在解析前拒绝。实际持久化大scope正面触发该边界；ordinary种类规则未改。
* 新提交窗口缺口：原fail允许node CAS失效后记录attempt失败；initial提交在prepareForCommit租约失效时仍终态化。final4只给initial终态路径增加node/attempt/run结果态、owner、lease、关联及dispatch/proof复核，立即、beforeCommit、事务后检查。真实RED曾写run/node/attempt=FAILED和2个终态事件；GREEN保留三者RUNNING、终态事件0、初始真实response仍在、无initialFailure修订。没有用“抛异常”代替状态断言。

### 元schema预算及兼容限制

meta-admission.json列出8份pinned内置资源的精确字节/SHA：schema，meta/core，applicator，unevaluated，validation，meta-data，format-annotation，content。实际合计324个JSON节点、2条固定regex、RE2程序量15。先对原声明SchemaWorkBudget.inspect；不对循环元schema放宽全局引用规则。整个资源读取/初始化/validate处于同一2秒SchemaDeadline；format assertions关闭，防止元schema format:regex走JDK编译器。

元校验把声明视为instance，复用现有Plan，系数324，comparisons/uniqueItems/characterScans/numericOperations全部启用。既有准入公式同时限制324*N≤2M、324*N²≤2M、15*324*(C+N)≤4M、324*(C+N)≤4M、324*C≤4M、324*N*D²≤4M（N节点数、C字符数、D最大数值长度）。固定词汇图内重用/递归沿有限声明子树进入；全资源节点系数保守覆盖每层各词汇分支，比较与唯一性另按整个声明平方上界准入，而非使用用户schema自己的低估标志。此为实现的保守推理，独立复审仍须核对。较大但语义合法schema可能因此在initial profile中被预算拒绝，是明确兼容限制；没有放宽任何全局限额。100项required的合法声明触发保守预算拒绝并证明发送/调用消耗/HTTP前拒绝；bogus根及嵌套type均拒绝。小schema真实正控通过。

### 真实测试与阶段标签

I1 red-declaration：非法format实际send1/invocation1，保留response而未正常结果；I2 red-invalid-pass：真实无效JSON response保留但恢复仍RUNNING；I3 red-budget-002：已收provider response却因BUDGET_TOKENS使response_ref为空、usage_accounted=false、tokens0。均为编译/真实链路成立后的行为RED，随后各自green-*通过。red-budget首轮因冻结预算声明不一致而失败，仅setup admission失败。control-text-001为fresh TLS alias启动失败，control-text-002为真正TEXT正控。

final-source与final2-source期间schema正控曾有provider主类指向及native response_format预期错误：失败保留，不当成生产限制；修复own helper后final2-schema-002通过。final3仅强化预算真实scheduler恢复断言，生产字节与先前修复cohort相同；其duration/invalid-pass、known usage、ACK-loss、response recovery、retry rejection、大小投影和声明类型等按final3标签保留。final3-invalid-schema实际send1是新的I1行为RED，不因旧构造通过而放过。

final3-schema和final3-budget发生执行重叠，排除作为最终证明；共享DB初始化可能相互影响，仅这两方法在final3-schema-serial/final3-budget-serial串行重验通过。duration/invalid-pass原无交叉，未为此回放。

final4独立冻结后12个DIRECT_JUNIT方法实际通过：schema真实成功+恢复、根bogus、嵌套bogus、元比较预算拒绝、提交窗口lease失效、response后current撤销、response后lease失效、invalid PASS_WITH_FLAG、schema TERMINATE、token预算恢复、duration预算恢复、TEXT。final4-schema首次拼错方法名导致discovery失败；正确方法在final4-schema-002执行；另一次RuntimeFault包名编译失败保留。它们不计行为RED。最终源码无再改；新覆盖不把旧53或final3结果重标final4。

第二fresh DB的6个DIRECT_JUNIT有限回归实际通过：FLOW commit/event/material修订、protected legacy入口拒绝、model input原子持久化父集合、current decision拒绝旧writer、HTTP current/root/parent/scope boundary、默认IAM启动防护。HTTP首轮因新TLS key alias配置遗漏导致container启动失败，原overlay/runtime2保留；只修own overlay2及重验该方法，未改canonical或原常量。新库先在首库NOLOGIN/session0后建立，最终也已关闭。

普通Surefire实际为4PASS+1SKIP投影：OutputPolicy explicit null、strict duplicate/empty JSON、composition/local refs、none text四方法通过。ModelExchangeTest类级ENGINE_PG_TEST条件未开启，其finalRequestPrecedesSdkAndResponseIsReusedAcrossReplicas命名XML testcase标记skipped；该类/容器条件投影不是方法执行，不能算GREEN。先前口头“5通过”已更正。首次直接surefire目标缺reactor依赖附着失败，随后正常test生命周期通过。legacy ModelExchange其他方法本体未改（native delta可核），但该静态事实不自动关闭覆盖缺口；parent仅从既有root元数据指出旧candidate684 regression-legacy-main-003及legacy-summary通过，可引用为旧阶段继承证据，不重标本波。两库已关，不重开或增第三库；必要性由独立复审/root判定。

### 编译、运行时、原生重放与fresh产物

编译/命令均在own副本，固定JDK21.0.12.8、离线.tools/m2；实际direct运行cp含own fixtures。compile-input保存执行源码，commands/*.inputs.json保存精确源/编译引用。final4源码之前各冻结manifest独立保留；overlay-manifest.json列出work及regression适配。runtime2-capture记录final4 direct字节，runtime3-capture记录最终legacy/HTTP overlay字节；663文件/634个Java21 class/156依赖JAR，不声称每一class已加载。二者production class无变化，差别列于final-closure-verification.json。

candidate684→final4原生Git --no-index --binary --output差分在native3/change.patch；独立apply --check及-p2重放684文件全部匹配，main Git未用、无commit。source-delta-final4同时记录accepted680组合比较；没有重新资格审计已关闭旧资源。native diff exit1是有差异的正常结果。早先native init参数错误exit128及一次native-prep脚本准备失败原样保留。

runtime捕获先于fresh package。实际正常离线Maven package参数-DskipTests -Dmaven.main.skip=true -Dmaven.jar.forceCreation=true：保留已实际运行的production编译字节，强制新JAR，不把新的不同编译假装测过。final4-fresh-package实际exit0、13.044秒、无存活根进程；verification.json核对250个生产entry与runtime3逐字节相同、embedded admin-common与standalone一致、Java21、无sibling业务类。common tests JAR另有精确hash，并非将其所有测试宣称执行。

### 关闭和执行资格

两fresh PG库保留数据但sessions0、roles NOLOGIN/nonprivileged、memberships0、schema权限0；先闭首库再开第二库。每phase exact Java guard及own Linux Redis PID/start/exe/config关闭事实保留；未做全局进程/端口探针或触碰未知资源。control-text-001 closure中SERVER_EXIT_FAILED_engine代表启动失败，具体进程guard已结束；不是忽略仍存活资源。初次baseline compile达30秒被guard终止，随后正常编译成功，失败不删除。HTTP/direct/Surefire/包装各真实command完整保留，最后guard为final4-fresh-package。

本波两项执行偏差导致NONCOMPLIANT，后续通过不覆盖：①旧run-helper一次源码投影输出固定synthetic TLS字面量，已登记会话原记录；不复现值、不伪造conversation digest，之后仅省略全部字面量/注释或AST符号方式检查。②前次wait尚running就启动下一batch：2026-10-22T15:42:22.5833389Z–15:42:36.6907487Z，final3-schema/final3-budget重叠，峰值5 server JVM+2 Redis，违反max3/max1全程保证。overlap-incident/command-records保留实际身份/DB/port/时间；两者确已停止，再作精确串行重验。其他新phase逐一等待实际完成后启动，没有声称全波合规。

保留所有默认IAM/D3/public CHAT/UNKNOWN边界；无真实外部provider兼容性承诺、无RETRY/correction/history/tools/其他call profile扩展。元schema保守准入和本波legacy PG方法SKIP是待评审限制。仅交接待独立复审，NOTAccepted。

### 条件验收规则v1.0：仅候选和核验建议

已读root授权精确副本.local/component-conditional-acceptance-rule-v1.md（6866B，SHA0817c6021decb015a6daef3ebdb4f3d1fe301babdc91ed0d38748cd4d9404b05）。新规则不自动豁免、不改历史状态；当前仍ALL/noBackground/NOTAccepted、execution NONCOMPLIANT。conditional-review-candidates.json逐条列出作者候选、排除疑问与T34硬闸；由独立复审提出、项目负责人批准，作者不自批条件验收或改台账。

W1：历史phase overlap可提出§3“测试执行过程类”候选；实际历史重叠及共享初始化干扰不可被后续串行通过补写，故保留原cohort并排除。Q1：常量输出违反执行要求且不直接属于三类穷尽白名单，不能因为synthetic/已披露自动纳入；须独立分类。Q2：legacy PG类级SKIP覆盖疑问按§4.3核验，不能当纯过程缺口。Q3：元schema保守准入兼容范围及内置图工作上界按§4.1/4.2核验，不能纳过程白名单。Q4：final4尚无独立复审，Important闭环仍由§4.4把关。Q5：继承的旧执行缺口由root按原证书逐项登记，本波不概括豁免。准备/断言失败的纠正不是伪造RED，也不需要冒充不可补证白名单项。

对冲建议：在T31/T32/T33及正式生产IAM/D3启用后的真实联合链，逐条覆盖冻结材料原字节与身份链、TEXT/JSON真实发送接收、声明在付费前拒绝、PASS_WITH_FLAG/TERMINATE、已支付response和已知usage跨预算耗尽/真实crash-recovery复用、current/dispatch/node提交窗口权限失效、scope/父集合/floor/版本隔离、UNKNOWN保守性，以及legacy MAIN/SUMMARY共存。每项登记实际环境/源码/方法/结果。T34逐条件组件核对批准清单与联合覆盖证据；未覆盖不得生产签收，发现功能缺陷立即撤销条件档回NOT_ACCEPTED。候选清单、对冲计划、实际结果随交付物透明披露。

### 精确artifact引用

- .local/engine-initial-sender-fix/final4-source/source.json — 135942 B — SHA256 1d11e82ab89aa589aa8415fee8a14e36b38fab40dd18e4567c9b085ea9153901
- .local/engine-initial-sender-fix/source-delta-final4.json — 2984 B — SHA256 4f4c78ebd1399d263758450c0b377830986236b92dc97abf1eee6f56d46aadc9
- .local/engine-initial-sender-fix/native3/change.patch — 63405 B — SHA256 77cf2e021fd38b962d5b974077aa09a56db9b6673e1c6987d8efa9c5776e2ef6
- .local/engine-initial-sender-fix/native3/replay-verification.json — 209 B — SHA256 282f5383c5f0304e2a2afd0dd5c420a904482b8547f450a11de054d26ba5fc36
- .local/engine-initial-sender-fix/runtime2-capture/manifest.json — 326594 B — SHA256 98be30328bf86768c81a4ff0c2439ae2bd1596ac041e17b5c58bc7d6e60e83a1
- .local/engine-initial-sender-fix/runtime3-capture/manifest.json — 326594 B — SHA256 4539695b6b31884ea0ff67edeb53e5a48d36a634f0dc6d054e4d86c3a9f67b9d
- .local/engine-initial-sender-fix/overlay-manifest.json — 5508 B — SHA256 dc54529fe9a5f312a5dd4729daae1268a77946bda58161e88a4a336f39d05c6a
- .local/engine-initial-sender-fix/meta-admission.json — 1170 B — SHA256 8dc6e51ecfc653b5ec810c3de8203402200fc9bab834f1906517933fa3b7fbf5
- .local/engine-initial-sender-fix/test-summary.json — 63794 B — SHA256 73c678a552014c4c5bff6b3fd216ccb2b414d1b93f9423f416798d5746dc9e56
- .local/engine-initial-sender-fix/surefire-final4/summary.json — 2446 B — SHA256 b04af8d65e38b52c9b6af5a283d8b76fe2b33503c68ce07e8a4c7fe5455c7ea3
- .local/engine-initial-sender-fix/fresh-artifacts/verification.json — 60554 B — SHA256 4b910abd58cfbdd58d48a7f8cad221ec1c2182305b505b5713f6739769d26761
- .local/engine-initial-sender-fix/closure-summary.json — 36364 B — SHA256 43b2a5cb98aef39e6c9748b7a5d3455d8f3b295a5a6d7a9e7a043783e1e27a16
- .local/engine-initial-sender-fix/final-closure-verification.json — 125402 B — SHA256 a8561482940151ffa090b01736eac15841120ed609cbb823e66d157274f5f562
- .local/engine-initial-sender-fix/overlap-incident.json — 6320 B — SHA256 4b00ea6f46baca2bbae065dc28d070bdd79785b10e4122a1398dd1fc81ec5e38
- .local/engine-initial-sender-fix/conditional-review-candidates.json — 6146 B — SHA256 0301ba21013e391719c4d4bc688966525bf9a5bc8535932f9e304a3b5bb5a38a
- .local/engine-initial-sender-fix/commands/final4-fresh-package.json — 1308 B — SHA256 7b3d2db28f50c016a33f1c873897efe2ffd700d1f96849f8d0fff8cedc694103

有限inventory/control/completion在本报告之后封存，作为自引用/未来文件明确排除；各自相互引用方向记录于control和completion。私钥、TLS密码、vault master、DB/provider凭据、JWT、sealed argfile/raw logs/raw XML/private provider payload不作为公开内容发布；私有事实只经安全元数据引用，不输出值。
