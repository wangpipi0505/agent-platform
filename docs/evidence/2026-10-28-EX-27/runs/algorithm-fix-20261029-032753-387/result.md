# SDK interruption candidate 43EC: default verification stopped at module regression gate

Run ID: `algorithm-fix-20261029-032753-387`. Actual window was returned to ROOT immediately after the single Full process completed. No further Java, Maven, PG, HTTP, source fix, or repeat sampling was performed. This is a finite failure report; it does not close EX-27 or EX-03 and is not a passing publication handoff.

## Actual commands and separate gates

| Stage | Actual native interval (+08:00) | Native / collector | Result |
|---|---|---|---|
| Default original class plus one handoff identity | 2026-10-29 03:28:17.1581595–03:28:28.6139005 | 0 / 0; no failure.ignore | 5 reports, 5P / 0F / 0E / 0SKIP; method gate and all-green true |
| Default complete skill module | 2026-10-29 03:29:06.8257279–03:32:13.8391840 | 0 / 1; original failure.ignore used only for evidence collection | 70 classes, 674 reports = 557P / 7F / 106E / 4SKIP; collection complete, method gate and all-green false |

Both commands used the original JDK21, local Maven repository, common JAR, default fork/reuse/order, original budgets, and original environment. Neither diagnostic flag was passed. Class and Full each produced zero diagnostic JSON and four safe network outcome records. Full started only after the class's native0/no-ignore/five-identity/source149/context gate passed (`full/class-dependency.json`). No additional test command followed Full.

The Full collector stopped at `METHOD_COMPARISON`, fixed reason `METHOD_COLLECTION_OR_REGRESSION_GATE_MISMATCH`, script line219. The native exit had already been persisted. Fresh XML collection is complete; this is a real method regression gate failure, not a compilation failure or missing-report substitute.

## New module failure

`cn.agentplatform.skill.application.SkillExecutionTest#automaticOutboxRecoversTransientCallbackWithoutRepeatingBusiness` changed from PASS in EX-00 and the original EX-03 Full to FAILURE in this Full. Its XML reports `org.opentest4j.AssertionFailedError`, `expected: <skill-tester> but was: <task-result-tester>`, at `SkillExecutionTest.java:757`; the caller is that method's `drainTenant` call at line767. The project path is `SkillOutbox.drainTenant → SkillExecution.retry/deliver → mock SkillClients.post`.

`module-failure-facts.json` preserves this bounded safe fact set and hashes. This source is among the unchanged other148 files. No cause has been established, and no product/fixture attribution or waiver is made. The failure must remain visible pending ROOT's separately authorized next step; the algorithm class's 5P does not erase it.

## Algorithm identities and actual safety coverage

All five exact identities passed in **both** the class run and the complete module context:

1. Original `interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]` (PUT).
2. Original `[2]` (GET).
3. Original `[3]` (GET_BODY), with the 43EC normal-body readiness assertions reached.
4. Original `unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit` (deterministic finite Source).
5. New `originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety` (raw GET_BODY handoff).

| Context | Handoff GET_BODY | Normal PUT | Normal GET | Normal GET_BODY |
|---|---:|---:|---:|---:|
| Class join return (ms) | 25 | 24 | 25 | 23 |
| Full join return (ms) | 23 | 24 | 22 | 22 |

Every recorded network outcome has interrupted=true, inputClosed=1, firstQuiescent=true, explicitUnconfirmed=false. Thus neither raw handoff run exercised its explicit-unconfirmed branch. A later quiescent state is not used to reconstruct an earlier false result. These default runs have no diagnostic stack/phase samples; normal body readiness is evidenced by reaching the exact passing 43EC assertions, not by claiming a captured stack log.

Under the reviewed 43EC source, the passing normal GET_BODY case requires the actual readJson + body/network-read chain, zero stages/dispatcher running and queued/executor active and queued, and the held original GET Call within the original total1s preparation budget. The original normal strict first-quiescent assertion remains. Raw handoff retains peer-latch→interrupt with no pre-interrupt observation. The exact ISE/code and PG-code classification agrees in both directions; absence of the code requires first-quiescent true. The explicit-unconfirmed case cannot be reclassified as confirmed merely because q later becomes true.

The passing finite Source identity reaches its original retained/false-quiescent/finite-exit and 500ms post-exit obligations plus the complete added unconfirmed postconditions: exact interruption code/PG code, target pending/noREADY/deferred cleanup, no early deletion, input closure, zero unintended network requests, actual original exit, and genuine subsequent PUT/GET on the same shared object with parent identity finally cleared. It retains the original zero-network premise; this is the Source vector's first subsequent shared-object use, while the normal/handoff vectors cover reuse after warmup.

For all relevant passing vectors, fixed route baselines, release/actual exit, cleanup and no-late-request checks occur before intentional shared-object PUT/GET. Explicit test PG reads and intentional cleanup use only this run's new UUID resources; the collector did not perform a historical PG query. This does not assert that the unchanged product's global cleanup paths can never visit older expired resources. The source/coverage matrix remains the authoritative exact assertion map. No claim is made that these results reproduce the original Full's in-memory stage/Call/executor distribution or establish safety properties beyond those assertions.

## Complete method and old nonpass comparison

The current Full contains all original664 identities, the nine EX-03 importer additions, and the one new handoff identity: no baseline or required new identity is missing. There are ten new identities relative to EX-00, and no unselected class or XML issue. The added failure is an original baseline method that was previously green, not a missing/imported test.

`full/retained-nonpass-reasons.csv` and `full/reason-comparison-summary.json` compare all116 original nonpasses individually: no missing identity, status/category/type/cause-chain/project-frame change. All four old SKIP identities and exact message hashes are retained in `old-skips.json`. Exactly one old migration failure has a changed raw message/detail hash; `message-delta-checks.json` proves that its sole 1371-character message difference disappears when only its two UUIDs and two timestamps are replaced. Raw hashes and XML remain preserved. No blanket message normalization or broader reason-equivalence claim was used.

Relative to the original EX-03 Full673, exactly two statuses differ: original GET_BODY[3] F→P under the corrected 43EC test premise, and the outbox callback test P→F. The new handoff adds one PASS. Totals alone are not the comparison criterion.

## Frozen context and evidence boundaries

Actual start/end HEAD: `83726d50a6cce116251c03c5c193080d8ed5aad2`.

Actual Test SHA256: `43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D`.

Actual ArtifactStore SHA256: `9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D` (product unchanged).

The actual149-source manifests have zero drift; other148 match the previous Full. Environment and common JAR before/end hashes match the admitted AF038/1B0D stack. `class/command-end-fingerprints.json` and `full/command-end-fingerprints.json` were captured after native exit and before XML collection. The collector itself issued no DDL, did not create/reset a database, and admitted the same existing owner/history-verified PG instance and private databases. Only the previously authorized original migration tests replayed their two existing private schemas.

The original EX-03 Full GET_BODY failure, historical PG ALGORITHM_IO_STOP_NOT_CONFIRMED recovery and its identity/in-memory inference limits, earlier diagnostic observations, failed pre-admission attempt, and immutable155D/43EC source snapshots all remain untouched. Current test-only semantic correction plus green five identities does not rewrite any historical result. The current complete-module failure also remains an explicit execution stop.

Local raw.log and XML remain under the existing ignored raw evidence policy. No source/index/commit/README/public-candidate-list action was taken after the stop.

## File-only analysis note

The first optional volatile-message check command encountered a PowerShell array-concatenation path representation error before reading XML. It ran no product command and wrote no replacement evidence. The corrected bounded file-only check succeeded; the note is retained in `message-delta-checks.json`. It does not alter the actual native/collector status or method gate.
