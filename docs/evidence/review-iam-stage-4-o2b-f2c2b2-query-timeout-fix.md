# Spec Compliance

- Spec compliant for the nested two-source timeout-budget fix. No missing or extra implementation requirement found. This is not full B2 acceptance; globally accepted source remains B1 source634.
- RunEventReader.java:334 chooses max(1, remainingWholeMillis/2); :337 bounds attempts to three; :339 recomputes from newly measured remaining time and validates actual remaining >= installed timeout. Positive 1ms is the explicitly required minimum when half-floor would be zero.
- RunEventReader.java:325-328 retains immutable anchor/allowance/capture and live/interruption checks. :338 retains parameterized transaction-local set_config and closes the result/statement. :344 withholds business SQL unless installation succeeded; :345-346 retains bounded rows, resource closure and post-query live validation. There is no zero timeout, reset or fallback.
- CannotVerify from this two-path review: exact physical frozen638/other636 identity relation, all finite541 artifacts and compiler provenance, historical2333 reconciliation, native apply proofs, complete child exit/collector evidence, protected-document identity and phase-specific source hashes. These remain the root's independent identity/runtime-artifact audit against the existing frozen handoff, not a reason to repeat successful tests. No changed-code behavioral concern remains unverified. Whole authorization infrastructure, whole target/classpath and unresolved B2 slow-reader cleanup are outside this task's acceptance scope.

# Strengths

- RunEventReader.java:334-339 changes only the timeout candidate and its explanatory comment. The final check still uses actual budget after SET completion, so the fix addresses the observed millisecond-floor race without weakening admission.
- RunEventReaderTimeoutBudgetPostgresTest.java:20-29 creates a random fresh database and verifies the actual connected database. :39-48 runs production Work.query in a real transaction, verifies result42, database identity, actual server pg_settings timeout and positive observed timeout within the execution budget.
- RunEventReaderTimeoutBudgetPostgresTest.java:55-81 checks exhausted-budget and SET-error denial with zero business calls. Its older anchor consumes allowance instead of extending it. The proxy delegates actual JDBC calls and injects delay/error after a real SET; no SQL success is mocked.
- RunEventReaderTimeoutBudgetPostgresTest.java:85-87 uses the existing fixture's public open API and currentCapture rather than fabricated Capture/private fields. Named dependency-risk check: RunEventReaderAuthorityFixture.java:27-39 registers observation, exchanges the portal ticket, authorizes retained authority, binds the trusted frame and opens the response through the engine client. This resolves the specific concern that the new test could bypass the established authority seam; deeper unchanged HTTP/JWT infrastructure was not re-reviewed.
- Existing red-02/raw.log:74-82 identifies the failure at old Work.query:344. red-02/observer/live.json:1 records settings4965/4960/4954ms, post-SET remaining4960/4954/4949ms and businessCalls0 with4948ms still available. Thus this is behavioral RED with a live budget, not just fixture failure.
- Existing green-01/observer/live.json:1 records installed2481ms, remainingAtBusiness4958ms and businessCalls1. The successful test assertions verify actual PG timeout and SELECT output. green-01/observer/consumed.json:1 records installed1ms, negative remaining and businessCalls0; setting-failure.json:1 records one real setting and businessCalls0.
- Parsed existing Surefire XML testcase results, without printing XML properties: red-01 has3 tests/3 errors due invalid observation resource; red-02 has3 tests/1 error only in liveBudgetSurvivesRealTimeoutSettingRoundTrip; green-01 has3 tests/0 failures/0 errors/0 skipped. red-01 is correctly excluded as behavioral RED. Evidence paths are docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/{phase}/finite-target/engine-runtime/target/surefire-reports/TEST-cn.agentplatform.engine.infra.RunEventReaderTimeoutBudgetPostgresTest.xml.

# Issues

## Critical

- None. Count0.

## Important

- None. Count0.

## Minor

- Count1, inherited evidence noise, nonblocking: docs/evidence/iam-stage-4-o2b-f2c2b2-query-timeout-fix/green-01/raw.log:55-58 and :66-69 contains deprecation and unchecked/unsafe compiler diagnostics in unchanged RunAuthorityStore, ContextProjection, RunEventReaderPostgresTest and FlowMaterialStatusPostgresTest. Output is therefore not pristine. The author explicitly discloses this. Retain the diagnostics and address them under a separately authorized cleanup; no changes or recompilation are requested in this frozen fix. No new-test-specific compiler warning was observed.

# Review Checks and Limits

- Read .local/iam-o2b-f2c2b2-query-timeout-fix-review-brief.md and the named SDD task-reviewer-prompt.md, then requirements and author report. Read the COMPLETE native complete-two-path-plain.patch once to EOF. Dispatch binds patch10745B/SHA256 c47434e10fcebdb4230d9a65674db1ea7d35f9a5b0481c3f3fcba5ca0c38fe2e; author report17051B/SHA256 a599e52caf9af793e32423154afa79a3f77b9c704652c06fc6b3fc028189b4b8; handoff3838B/SHA256 a71364f6eca1b3e0867798b6ab8673d49f6c1efc60af69ef1d3d0d78ff3148ef. Independent physical identities remain root-owned as dispatched.
- Cut-off recorded: production patch context begins within Work.query and ends before the final installed check/execution. A single bounded source read RunEventReader.java:291-380 supplied complete Work.query:329-348 plus Work constructor/remaining/live/millis:324-328. This was necessary to verify no budget reset, zero timeout, fallthrough or resource/row-limit regression; no other changed-source reread occurred.
- One named unchanged dependency-risk check was made: legitimate authority fixture construction/open, described above. No broad source/history/target crawl, source modification, target mutation, native regeneration, test/PG/GC/replay rerun or sibling-project access was performed.
- Read-only runtime evidence checks were confined to actual existing testcase summaries, observer JSON, and focused raw-log failure/diagnostic/success excerpts. Root owns final reconciliation to exact source/target epochs and process provenance.
- The unchanged guard-to-execute interval and server-side timeout semantics remain as documented at RunEventReader.java:341-343. This fix neither promises an exact network deadline nor establishes resolution of B2 test09 cleanup.

# Assessment

- Task quality: Approved. Spec: compliant within the bounded reviewed implementation. C/I/M=0/0/1; the one Minor is disclosed inherited compilation noise.
- The minimal headroom calculation fixes the reproduced old installation rejection while retaining all existing fail-closed gates. Real PostgreSQL evidence exercises success, consumed budget and SET failure with legitimate authority, and distinguishes the initial fixture-only failure from behavioral RED.
- Sole written artifact: docs/evidence/review-iam-stage-4-o2b-f2c2b2-query-timeout-fix.md. All report write rights are frozen and returned upon this report's final identity handoff; no source/target/other artifact rights were exercised.
