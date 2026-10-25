# N4b1 gateway published-run local authorization proof

Status: component implemented and isolated tests verified; pending root audit and independent Spec/Quality review. Not N4b/full Task5 acceptance. Source/target ownership is returned with the sealed final handoff; implementer performs no further source/target writes.

## Scope and behavior

Only production `CurrentAuthorityClient.java` and `HttpCurrentAuthorityClient.java` changed. Added finite default-fail `exchangeGatewayPublishedRun(originalPortalToken,publishedRoot)`: validated iam/gateway only, fixed engine/action=proxy.engine.post.RunController.run, PORTAL original token, app-version safe code@positive long version and SHA256 root (MetadataSelectors.code/AuthorityProtocol.hash reused). Root is a later metadata lookup result; this component does not establish publication facts.

The public final nested proof has a private constructor and only ticket()/operation()/requireVerified(). It is minted from the same private portal/query/ticket verification. Private records retain the actual verified current claims and ticket claims, original source expiry, actual grant_expires_at, operation and ticket; no public VA/currentOnly/provided-ticket wrapping. The small private portalExchange/verifiedTicket factors reuse existing cryptographic and ceiling checks. Ordinary exchange/integration/resume/forward/authorize/retained/workload/approver/response semantics remain, including engine target and gateway receiver rejection.

requireVerified() checks minimum signed current/ticket/operation/real-grant/source expiry with its original private Clock. Its monotonic budget starts before source decoding/query/JWK work and caps at configured <=5 seconds; using the earliest observation may shorten the actual signed window conservatively. System.nanoTime elapsed subtraction prevents wall-clock rollback extension. Checks never reset the budget. Failed proofs stay invalid; the volatile owner-close flag invalidates proofs when close begins. The factory performs a final local check after ticket/JWK validation. No ThreadLocal rebind, HTTP refresh, resume, new protocol, arbitrary-target authorization, public factory, dependency or production clock hook.

Future real gateway code must explicitly check before engine POST, after complete buffering and immediately before JSON write. These boundaries are not connected here; bytes already sent cannot be withdrawn. No instant online revocation/whole-wire atomicity promise. Production SecurityConfiguration.bindDeployment and sticky legacy signer closure are untouched and production IAM remains closed.

## Actual TDD and commands

Every shell used explicit workdir G:/207/项目/算法重组工具/代码/agent-platform. Actual controlled runner retained fixed Java21 Temurin21.0.12+8, offline .tools/m2 and parent/admin-common reactor. No env parser/runner edits/secret output. All stage source/raw/Running/selected XML/command metadata are frozen under the matching stage directory. Full commands are command.json and test-audit.json.

| Stage | Original runner arguments | Actual result |
| --- | --- | --- |
| setup01 | iam-stage-n4b1-setup01 admin-common GatewayRunAuthorizationTest | exit1 missing API compile failure; Running0, no executed test count |
| red01 | iam-stage-n4b1-red01 admin-common GatewayRunAuthorizationTest | exit1,29 tests,4 failures,0 errors,0 skipped |
| final01 | iam-stage-n4b1-final01 admin-common GatewayRunAuthorizationTest,CurrentAuthorityClientTest | exit0,29+20=49 tests,0 failures/errors/skips |

Commands were invoked via node .local/iam-n4b1-gateway-run-authorization/evidence.mjs stage <stage> <tests>, which freezes source then calls the unchanged node .tools/run-iam-module-tests.mjs <stage> admin-common <tests>. No successful green rerun. No common179/N4a53/engine20/PG/seven-reactor suite run. Existing tests are20 because one default-fail compatibility case was added; no old assertion weakened.

The missing-API compile failure is setup, not a security behavior red. Root approved the explicitly incomplete compilable prototype (prototype.json and frozen red source): real signed exchange/ceiling/route/owner/ticket/source checks existed, while current/monotonic/final guards were intentionally pending. It is not evidence of an old production vulnerability or unchanged production bytes. All four compiled red failures were the expected missing-throw assertion: signedCurrentExpiresWhileActualTicketRemainsValidAndClockRollbackCannotReviveProof; laterTicketJwkVerificationCannotReturnProofAfterSignedCurrentExpiry; monotonicBudgetExpiresEvenWhenWallClockRollsBackBeforeFirstRecheck; closeDuringSignedExchangeDoesNotMintCapture. Final guard is the sole production change from red to final; phase-source-deltas.json records every phase.

## Named coverage and limits

- signedCaptureKeepsMainBindingAndWorksWithoutThreadLocalsOrAuthorityRefresh: actual portal subject/initial ceiling/source/origin/root and ticket/op identity, actual engine receiver accepts; gateway receiver rejects; local cross-thread checks after clearing SecurityContext and absent AuthorityContext add no authority requests.
- signedCurrentExpiresWhileActualTicketRemainsValidAndClockRollbackCannotReviveProof: signed current1s, clock advances1s, actual engine receiver still accepts the original unexpired ticket; local proof rejects and remains rejected after rollback.
- laterTicketJwkVerificationCannotReturnProofAfterSignedCurrentExpiry: only new-test-owned original /jwks context replaced/restored. Exact authority JWK fetch1 verifies current query; fetch2 is the following verifiedTicket verification and advances injected fixture Clock6s. Exactly2 fetches and1 exchange request asserted; ticket operation still future. No helper/production time/TLS hook, sleep lottery or CPU exhaustion. Clock advance is explicit fixture injection, not real latency measurement.
- monotonicBudgetExpiresEvenWhenWallClockRollsBackBeforeFirstRecheck: real1.1s timed latch wait tests elapsed1s current budget while fixture wall Clock rolled back before first check. This is a local monotonic-time test, not the late-JWK test.
- signedDeadlineMinimumRejectsWithoutBreakingTicketOperationGrantOrdering [source,ticket,operation,grant]: signed true boundary tuples respect ticket<=op<=grant. Source/op/grant short cases necessarily share nested shorter deadlines; this does not claim grant can independently precede a still-valid ticket. Sticky rollback rejection retained.
- closeInvalidatesExistingCaptureAndPreventsNewExchange / closeDuringSignedExchangeDoesNotMintCapture: owner closes, existing proof rejects, no new valid capture; blocked second-JWK exchange closes with bounded fixture latches/Future observations/actual executor termination. Does not prove JDK hard cancellation.
- separateCapturesUseFreshNonceOperationAndGrant: two real main captures use fresh nonce/op/grant. Formal metadata/main HTTP chain separation remains N4b2.
- finiteApiRejectsOtherServiceModeRootAndNonPortalSourcesBeforeAuthority: wrong service/test mode/kind/zero/leading-zero/unsafe/overflow version/bad hash/null root and integration/service/workload/non-access source all reject before authority query.
- signedTamperingNeverMintsCapture17 rows: nonce/hash/active/current/issuer/audience/caller/target/action/subject/initial/version/operation/missing grant expiry/invalid expiry/source/missing origin. Existing CurrentAuthorityClientTest additionally exercises bad signatures/JWKS, portal ceilings, integration/workload, forward/resume/approver and receiver constraints.
- unsupportedClientDefaultCannotWrapPublicAuthorityAsGatewayRunProof: existing interface implementations default-deny, never call ordinary authorization methods to synthesize proof.

bounded-inventory.json names the actual private ticket/query caller inventory before selector expansion; none was needed. Existing receiver target checks were not relaxed. No metadata/grant substitution, wire expansion, main POST replay, request-context proof invention or unsigned HTTP placeholder.

## Frozen source/target and actual replay

Before edits/Maven source368 and target1806 physically copied, each SHA-equal to fixed N4a manifests. Before capture is phased, not atomic. Final source369:1 added,3 modified,0 deleted (two production, original test, new test). Target1814:8 added,12 modified,0 deleted; target-changes.json includes all20 paths, diagnostics and incremental metadata. Final source manifest e9e2140273874502f0eff0bd8ad035f795694a4e80cb4e167daee7e6f28401f8; target manifest 1e4de86fa31e89c3d7d004e5eb603dcc772077fba3cc8e2f8b8dd923a04b0d55.

common test-fixture -tests.jar changed236449→249103B via existing jar:test-jar goal during test. It is not a production JAR. All production JAR bytes unchanged/stale from pretask package; no package/deploy performed. Compiler rebuilt53 production/23 test source files; classes include new private records/proof and recompilation of existing retained/nested fixture classes. No claim recompilation equals new behavior in untouched methods. Setup compile failure's intermediate target removals/rebuild and each phase delta are retained. Historical before JARs were checked from physical before/, never final live JAR.

change.patch 67949B SHA759d751d1958f8881ae1ab2ea3d8d4d17cc78adbb6c068cf0d4f0aab406bb9c1, fullcontext git diff --no-index --binary --no-renames --unified=100000. Actual root-cwd git apply --check -p2 --directory=own/replay exit0 and apply exit0; all369 replay files byte-equal to final source. Own .gitattributes * -text retains exact CRLF/LF; project .gitattributes unchanged. No HEAD/remote/commit/SDK/cache used to create a fake diff.

## Sources actually read

upstream/ archives fixed Spring Security6.5.0 NimbusJwtDecoder (Apache2), Nimbus9.37.3 DefaultJWTProcessor/JWSObject/RSASSAVerifier (Apache2), and official OpenJDK jdk-21+35 Clock/System (GPL2+Classpath exception). Exact source SHA/license/URL and archive entry checks are reading-manifest.json, exact-archive-entry-verification.json and mechanism-reading.json. Reused known source SHA/descriptors and physical archive baseline were checked before claiming provenance; no new cryptography/dependency/framework. Read NimbusJwtDecoder.decode/createJwt/validateJwt, DefaultJWTProcessor.process(SignedJWT), JWSObject.verify, RSASSAVerifier.verify, Clock.instant and System.nanoTime contract. Private current/ticket verification/source mapper/protocol/root validators were read in this project.

The conservative nano budget is this implementation's inference from Java elapsed-time semantics; not an upstream authorization feature. jdk-21+35 reference is not represented as identical to runtime Temurin21.0.12+8. Shared source index unchanged for root to update after signoff.

## Protection and evidence/tool issues

Reused root-verified109089 protected files, allowed only latest8 previously approved index transitions (5 internal static,3 external). Added8450 actual new N4a/root/report/brief/backup files =>117539 static files,9814266735B; no blanket .local/stem exclusion. 28 runner files and latest8 indexes tracked. Before transition table physically verified all8, but before.indexes duplicated internal5 old SHA metadata; protectedFiles internal5 were correct. effective-index-before.json transparently derives all8 from frozen records, without rewriting before or resampling a baseline. Original N3 review30396B and N4a review16512B retained; no review update here.

One complete after physical hash finished 2026-10-20T16:54:19.572Z: all117539 files/9814266735B verified; unauthorized static changes0/additions0, runner changes0, latest8 index changes0, all369 source+1814 target frozen bytes equal. Full results are protection-summary.json and protected-after/protected-changes.json; exact six runtime directories .local/minio/,pgdata/,redis/,logs/,tmp/,wsl/, postgres.log, root current-work-state.json, own local/evidence/report and the exact three stage logs/fresh-secret directories excluded. No extra source/target/test/log directories are omitted. After-script compares all frozen source/target bytes and original2174 saved before copies. Shared eight indexes not changed.

tool-events.json preserves the actual phase/setup/tool issues and excerpt limitations. Initial supplement recursion applied exclusions too late, was interrupted before before.json; source/target copies already complete and preserved, resume checked them and only completed supplementation. Initial finalize wrongly copied live data into generated baseline prefix21 files and stopped at interface SHA assertion; original before copies untouched. Next nested-cwd git apply returned0 while skipping paths, caught by replay SHA; root-cwd apply exposed CRLF normalization, fixed only own attributes and regenerated patch. All initial actual git results/normalized patch retained. No failed step concealed by test reruns.

Some exploratory tool displays were truncated and guessed optional paths were missing; only narrow actual source reads support claims. Full Maven raw logs are complete, while tool-event excerpts are explicitly not a complete tool transcript. Existing deprecation/unchecked warnings and two344B Windows Surefire dumpstream diagnostics retained; no argLine/POM/check disable used to quiet them. No new policy rejection or escalation occurred.

## Lifecycle / remaining work

resources.json separates Future observation/interruption request from asserted actual worker/handler termination; no OS-wide endpoint/PID closure audit recorded. Existing BoundedIamHttp JDK retry/close limitation remains a named pending task. This proof does not establish literal zero retries or hard bounded cancellation for every network operation.

This component is isolated signed HTTP software verification only. Actual gateway proxy/JSON boundary integration, full portal metadata/main/app/engine/PG joint chain and real IAM/model/ontology/K8s acceptance remain outstanding. Global production IAM closure and T01-only overall completion remain untouched. No schema/POM/config/routes/frontend/shared index/commit/push/package/deploy edits. Root audit and fresh independent Spec/Quality review decide N4b1 acceptance.
