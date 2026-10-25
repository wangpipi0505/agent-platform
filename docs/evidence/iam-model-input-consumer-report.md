# Private initial model-input consumer — frozen implementation report

Status: DONE_FROZEN_PENDING_REVIEW. This report does not grant root acceptance or global completion. All work used shell cwd G:/207/项目/算法重组工具/代码/agent-platform. No nested agents, main Git commits, deployment, restart, schedule, other conversation or historical business replay was performed.

## Authorized input and source boundary

- docs/evidence/iam-model-input-consumer-brief.md — 40119B, SHA256 4f873811731c2baddc984f507162c42156432d2717a69f2f52e13049ab76bbb4
- .local/iam-model-input-consumer-test-client-accessor-supplement.md — 2628B, SHA256 5f394cc616154f7795f1110ce95e0c6b5afd40554fa2775dd22f95b978fbc524
- Accepted producer certificate: .local/iam-model-input-producer-acceptance.json — 3338B, SHA256 6b99890d6bffdd99bd61b1efe06d07e2531bf61374d49cc83f8fc2bfb96be9d0
- Accepted source652: SHA256 0e51caf00543e924c52a40b506b3d1509c24b338db9bb02ba7d9d04413c8f992. All652 canonical entries were verified against it before canonical edits; complete byte copies are retained in .local/iam-model-input-consumer/before/source/. .local/iam-model-input-consumer/before/source.json — 125472B, SHA256 0e51caf00543e924c52a40b506b3d1509c24b338db9bb02ba7d9d04413c8f992
- Final source660: .local/iam-model-input-consumer/final/source.json — 127244B, SHA256 09a1d86d1636385620127e39e7f53d650b7a0f0a30689cef3a65b6bebedf6ea0. Complete final blobs are retained in .local/iam-model-input-consumer/final/source/. Eight additions, three existing modifications and649 unchanged canonical files. No common production authority, migration, public controller, ModelCalls/ModelStore, provider/SecretVault, default IAM, frontend or protected document behavior changed.

Exact added/modified canonical paths:

- modified: admin-common/src/main/resources/agent-service-authority.json
- modified: admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java
- added: engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputConsumerProducerFixture.java
- modified: model-gateway/pom.xml
- added: model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java
- added: model-gateway/src/main/java/cn/agentplatform/model/domain/EngineModelInputManifest.java
- added: model-gateway/src/main/java/cn/agentplatform/model/infra/EngineModelInputManifestClient.java
- added: model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java
- added: model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerHttpTest.java
- added: model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputVerifierTest.java
- added: model-gateway/src/test/java/cn/agentplatform/model/infra/EngineModelInputManifestClientHttpTest.java

The POM adds only a test-scoped common test-JAR dependency. Catalog changes only the existing row's callerSource leaf. The common test fixture changes only Set.of("app","gateway") to Set.of("app","gateway","model") in its existing testClientSecret accessor. final/source-equality.json proves all three reversions byte-for-byte. A real late byte audit detected an unintended2B POM EOF CRLF; it was removed before the final source/package/diff. Earlier candidate/native/package records remain intact under formatted-candidate, pre-transaction-candidate, native, native-final, package-work, package-work-final and the corresponding package/package-final evidence. The final artifacts use native-transaction, package-work-transaction and package-transaction.

## Result and trust mechanism

The model-owned DTO is ordinary untrusted wire data. InitialModelInputVerifier is explicitly constructed with the concrete final HttpCurrentAuthorityClient and one HTTPS engine origin; there is no new bean, public route, generic callback, DTO-to-proof factory or actor-based entry. Its only verification entry accepts candidate bytes, an exact relative selector and the real private retained mapping. It requires the same live AuthorityContext before parsing, engine→model/model.chat-stream/DELEGATED/PORTAL purpose, owner/root/operation agreement and a real inbound refresh. It locally rejects an already active Spring transaction before any authority refresh; direct transport likewise rejects an active transaction before metadata HTTP. These are IllegalStateException programming-boundary guards, consistent with the existing producer's no-network-in-transaction convention; neither component owns a datasource.

The verifier derives only engine/material.scope.read from the signed root and checks the actual canonical GET capability. It directly calls the concrete forward implementation. A private helper extracts only origin_authentication_id from that already verified raw return, requiring exactly three JWT segments, duplicate-free object parsing, at most64KiB decoded payload and one nonblank textual origin at most128 characters. It compares origin and OperationBinding before engine GET. The helper does not accept public token/ticket input or implement a second signature policy.

Candidate bytes are privately copied, bounded to2MiB, strictly parsed as the finite history-free profile and canonically hashed without dropping diagnostics, computed labels, messages or invocationRef. Selector ID/version, whole request SHA, manifest metadata, model binding ID/SHA, run, root, owner, material and scope are checked. Request object key order may differ; message arrays remain ordered. App candidate SHA, accepted snapshot SHA, accepted request/material SHA and a future provider-wire SHA remain separate domains.

Transport uses one validated HTTPS host/optional port; ordinary chain/SAN checks, no redirects/proxy/cookies/retry/cache and no candidate upload. It enforces connect≤2s and a monotonic total≤5s over connect/headers/full body, with asynchronous chunk checks before allocation/copy and a256KiB envelope limit. It rejects Content-Encoding, invalid/multiple assertions and non200 responses, cancels on overflow/deadline/interruption and preserves interrupt status. Parse errors and trusted-source semantic/hash violations are sanitized403; unavailable transport is sanitized503; bad candidate/selector is400 before GET. Local Jackson copies reject malformed UTF-8, duplicate keys, trailing values, missing/unknown/null/coerced fields, depth/string/count violations and duplicate permission arrays before constructing Sets. Json.mapper is not mutated.

Manifest SHA is computed from the raw wire tree minus only manifestSha256; each scope SHA uses the accepted common MaterialAuthorization.scopeDigest Set semantics. Exactly one run-snapshot/run-inputs/run-version-refs parent is required, all same run/owner/full initial/acceptedSnapshotSHA version. Snapshot/input have no parents, refs points to snapshot, child points to the three unique sorted hashes. Full initial roles/entitlements/labels, parent requirements and current trusted labels cannot be subtracted. After HTTP and all metadata checks, the verifier refreshes inbound authority again, checks every scope against that actual current with the trusted clock, then mints its private nested final result. Every result accessor checks retained validity and exact final AuthorityContext mapping and returns copies of JSON/bytes. It cannot cross a thread, closed/replaced request frame or serve as a durable send permission.

## Test epochs and actual commands

TDD and ponytail were applied to the bounded implementation. Setup/compile errors are retained and are not counted as behavioral RED. red-01 was Maven property splitting/trust-store setup, red-02 a test syntax compilation error. red-03 had an actual invalid HTTP origin accepted by the new compiling client signature stub (1 failure,2 positive-path errors). red-04 used the new minimal value-returning verifier skeleton and had actual missing selector/hash/origin/closure/current/private-result rejections (9 failures,0 errors across12 tests). No old production security was loosened to obtain RED. That initial signed test assembly forwarded a PORTAL operation; green-01 exposed the purpose mismatch. The fixture was corrected to obtain a real engine resume ticket with DELEGATED/PORTAL; the production purpose guard stayed strict. green-02 then passed10 verifier cases. The normal typed-wire comparison was corrected to compare canonical digest rather than Jackson IntNode/BigInteger node identity. Exact source/Running/XML records retain these distinctions.

| Epoch | Tests/failures/errors/skipped | Canonical source-manifest SHA256 | Process result |
|---|---:|---|---|
| final-unit-01 | 21/0/0/0 | 338a09d13cec7e1b7ef690bf0fbc18a49fe9b8565a6aed78b814b48394162c50 | exit0 |
| green-01 | 19/2/3/0 | dd9b4863e8ed1609204660a6f4053a62ad5b80011022c389652be63cdf9a6bff | exit1 |
| green-02-verifier | 10/0/0/0 | faf117c7b3aaec21392b2530cb2ab8743f771326d5052f6524699973d8751e7b | exit0 |
| integration-04 | 2/0/0/0 | ccab5701320d8a81f0b57399635e576f32fae85c8248fbd0c2f31debab411828 | exit0 |
| red-03 | 5/1/2/0 | 23aefaa9dc7546601e0e01471aaff4c5bdcc477e010061174104519f46e35344 | exit1 |
| red-04 | 12/9/0/0 | d9b76b70072f85b745644eba3a5e2c4d3ee8b4b92df0446e602df2200d4670c1 | exit1 |
| regression-01 | 41/0/0/0 | 338a09d13cec7e1b7ef690bf0fbc18a49fe9b8565a6aed78b814b48394162c50 | exit0 |
| transaction-green-01 | 5/0/0/0 | 09a1d86d1636385620127e39e7f53d650b7a0f0a30689cef3a65b6bebedf6ea0 | exit0 |
| transaction-red-01 | 2/2/0/0 | 7fcdd980a6a472a148dfe5e12c850cfbc3c14cc9db73ed3b3df9b5f9ba6ea699 | exit1 |

The red-04 HTTP source entry was reconstructed from the retained byte-identical red-03 file because additional HTTP cases were edited after that compile had already consumed the two-case source. Its two-case XML corroborates that input; epoch.json records this explicitly. All later executed epochs save source identities before execution. No multiple epochs are described as one final full-suite run.

Earlier affected tests: final-unit-01 has11 verifier +10 transport tests, all21 passing. Integration04 has2 real separate-JVM producer/PG tests, all passing. Regression01 has AuthorityContextRetained5 + CurrentAuthorityClient20 + RetainedAuthorization13 + ModelPolicy3 =41 passing, run once. Formatting on the eight new Java files and the2B POM EOF correction followed, with bytecode equivalence evidence retained in the preceding candidate.

A subsequent final self-audit found a genuine ambient-transaction gap: not owning a datasource does not ensure the caller has no transaction. Before adding guards, transaction-red-01 ran two new cases against a live valid bound DELEGATED/PORTAL request and ordinary HTTPS and recorded2 actual assertion failures,0 errors. After local guards were added to the verifier and direct transport, transaction-green-01 passed the2 new branches plus3 necessary signed normal/origin/HTTPS positive controls,5/5. The transaction flag is always restored in finally; the guarded verifier observes no extra IAM call and no material GET, and the direct transport sees GET0. This verifies Spring's local guard branch, not a new real-PG transaction integration. The latest source has these guards and reformatted affected files. The older21-unit,2-PG-integration and41-regression results remain their own epochs; they are not described as a latest-source full suite or re-executed for cosmetics. The final exact-source package and ASM comparison below use the transaction-green compiler outputs.

Final unit command (cwd fixed above):

`mvn.cmd -o -B -ntp -pl model-gateway -am test -Dmaven.repo.local=.tools/m2 -Dsurefire.failIfNoSpecifiedTests=false -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dconsumer.tls=G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-input-producer/tls/engine.p12 -Djavax.net.ssl.trustStore=G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-input-producer/tls/trust.p12 -Djavax.net.ssl.trustStorePassword=fixture-only -Dtest=InitialModelInputVerifierTest,EngineModelInputManifestClientHttpTest -Dconsumer.unit.evidence=G:\207\项目\算法重组工具\代码\agent-platform\.local\iam-model-input-consumer\final-unit-01-observer`

Regression command:

`mvn.cmd -o -B -ntp -pl admin-common,model-gateway -am test -Dmaven.repo.local=.tools/m2 -Dsurefire.failIfNoSpecifiedTests=false -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dconsumer.tls=G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-input-producer/tls/engine.p12 -Djavax.net.ssl.trustStore=G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-input-producer/tls/trust.p12 -Djavax.net.ssl.trustStorePassword=fixture-only -Dtest=RetainedAuthorizationTest,AuthorityContextRetainedTest,CurrentAuthorityClientTest,ModelPolicyTest -Dconsumer.unit.evidence=G:\207\项目\算法重组工具\代码\agent-platform\.local\iam-model-input-consumer\regression-01-observer`

Latest focused guard and positive-control command:

`mvn.cmd -o -B -ntp -pl model-gateway -am test -Dmaven.repo.local=.tools/m2 -Dsurefire.failIfNoSpecifiedTests=false -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dconsumer.tls=G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-input-producer/tls/engine.p12 -Djavax.net.ssl.trustStore=G:/207/项目/算法重组工具/代码/agent-platform/.local/iam-model-input-producer/tls/trust.p12 -Djavax.net.ssl.trustStorePassword=fixture-only -Dtest=InitialModelInputVerifierTest#ambientTransactionDeniesBeforeAuthorityRefreshOrMaterialHttp+realSignedBoundRequestProducesPrivateResultAndRetiresWithFrame+signedForwardWithChangedOrMissingOriginDeniesBeforeDataGet,EngineModelInputManifestClientHttpTest#ambientTransactionDeniesBeforeMetadataHttp+boundedNormalTlsTypedManifest -Dconsumer.unit.evidence=G:\207\项目\算法重组工具\代码\agent-platform\.local\iam-model-input-consumer\transaction-green-01-observer`

Actual integration orchestration: .tools/iam-model-input-consumer-run.ps1 -Stage integration-04; the exact engine java argv, finite classpath, ready descriptor references, source SHA, model Maven command and both process exits are preserved in .local/iam-model-input-consumer/integration-04-fixture/ and integration-04-command.json. No absent fixture test is silently skipped. Engine-compile01/02 and model-compile01 are compilation evidence only, with zero Running cases; they are not tests. Integration01 failed finite classpath preflight before allocation; integration02 failed missing common test class before PG allocation; integration03 ran the actual producer/fresh PG but model test compilation found a package-private resolver constructor and then cleaned up. These failures remain setup/compile evidence, not RED or integration passes.

The accepted loopback signed IAM issuer is HTTP test-profile traffic. The engine manifest is normal CA/SAN HTTPS. The model secret is obtained only through the explicitly authorized test accessor, stored in an owned private fixture file and injected only into the owned child environment. A public WorkloadCredentialResolver validates HTTPS-shaped endpoint metadata with the same model service/clientId/clientSecretRef; it never uses those endpoints. The real concrete authority client separately uses the actual signed loopback config with testProfile=true. Root explicitly approved this fixture-only assembly after inspecting the resolver. No common API/validation or production trust policy was changed; no reflection or production credential value is printed.

## HTTP, current and parent evidence

- {"destinationGets":0,"case":"sameOriginRedirect","sourceGets":1}
- {"destinationGets":0,"case":"otherOriginRedirect","sourceGets":1}
- {"case":"stalledHeaders","elapsedMs":5011,"status":503}
- {"case":"tricklingBody","elapsedMs":5012,"status":503}
- {"case":"boundedNormalTls","gets":1,"normalChainAndHostname":true}
- {"case":"wrongHostAndUntrustedCa","gets":0,"status":503}
- {"case":"chunkOverflow","serverObservedCancellation":1,"status":403}

The normal bounded HTTPS positive returned typed metadata and defensive raw-tree copies. Same- and different-origin redirects each produced exactly one source GET and zero destination GETs/bearers. Chunk overflow produced a server-observed write cancellation. CL overflow, duplicate assertions, unexpected encoding, malformed UTF-8/JSON, duplicate keys, unknown/missing/null fields, scalar coercion, depth/string/role/entitlement/label/parent bounds, raw-manifest/scope hash mutations and strict selectors deny. Stalled headers and trickling bodies both consume the same finite total bound, not independent read timeouts. Signed generic-forward changed/missing-origin baselines still return signed tickets, while the wrapper denies with data GET0. Current narrowing/revocation/expiry/IAM503 during transport returns no result; an explicit recovered valid-current call succeeds. Closed/replaced/cross-thread/currentOnly mappings deny. Default IAM model startup still throws IAM_TRUST_CHAIN_UNAVAILABLE before any listener.

History-free full-request association covers diagnostics/computed labels/messages/modelRef/run/invocationRef mutations and message-order reversal; object-key reversal succeeds. Permission array order can legitimately change with a recomputed raw manifest SHA while preserving the common Set scope digest. Parent duplication/foreign run/version, owner/root/snapshot/request/model mismatch, missing legacy scope and child role under-union deny. Historic sourceDecisionId/current decision differences are positive. No historical-run grant/operation equality was added.

## Real producer, PG and separate JVMs

Engine JVM 10500 and model test JVM 8752 are distinct. The engine fixture starts the actual accepted ModelInputMaterialController, receiver JWT/security/catalog and genuine createPublished transaction plus prepareInitial. It uses a new own database, multiple roles/regions/labels and normal TLS. Three successful independent reads verify initial, repeated and same-origin new grant/operation access; operation IDs differ from the historical run and historic decision IDs differ from current. Wrong owner, root and different origin deny at the real producer. A nonexistent manifest and an INSERT-only legacy run/authority/model-input row with zero scopes deny at the producer PG boundary. The original parent's attempted DELETE is actually denied by MATERIAL_SCOPE_IMMUTABLE; no trigger was disabled. Malformed response tests separately exercise consumer parent validation. Candidate requests never enter ModelExchange or public admission.

Owned registry has exactly2 databases, each preflight absent and registered before CREATE, all with iam_model_input_consumer_<32hex> names. They are retained for review, not dropped:

- iam_model_input_consumer_51786d530d4b4516a6f7a959a602a0fb — migrations V200..V218; sessions before/after audit 0/0; immutable triggers enabled.
- iam_model_input_consumer_78248e4cac9048c9887eb1eae5550700 — migrations V200..V218; sessions before/after audit 0/0; immutable triggers enabled.

The model JVM never receives PG configuration or reads the engine schema. The standalone post-run database audit connects only the exact registered names plus the allowed PG catalog, confirms identities/migrations/trigger state/content-hash metadata and zero remaining sessions, and closes its own connections. .local/iam-model-input-consumer/database-audit.json — 9274B, SHA256 b016b32cd81a90d0b474486d89e6eb6597a5402e42c9288581ecbd1b2f25162b

## Dynamic observations versus static isolation

Actual integration04 observations: engine metadata GET8; unexpected GET bodies0; engine.attempt rows0; engine.model_invocation rows0; summed sdk_attempts0; SQL writes after ready0; IAM operations only exchange/authorize/resume/forward, no response lease operation. Engine model_input_snapshot has2 rows (one positive material and one INSERT-only legacy negative). Detailed SQL names, counters and shutdown identities are in closed.json. These are actual runtime/PG measurements.

Provider/tokenizer/measure/SecretVault isolation is a static guarantee of this private component: actual source-token inspection finds no ModelCalls,ModelStore,ModelQuota,SecretVault,OpenAiChatModel,Encodings,DataSource or JdbcTemplate reference in any of the three production files. It has no datasource/provider/credential injection seam and invokes only the concrete authority client and fixed metadata transport. The unused unit forbiddenEgress sentinel and model-results reference-zero fields are structural fixture assertions, not dynamic method instrumentation or real egress counters; they are not used to claim measured provider/SecretVault calls. No provider/tokenizer/measure/SecretVault method instrumentation was installed. No public first-wire, provider binding/secret, tokenizer or send success is claimed. .local/iam-model-input-consumer/final/static-boundaries.json — 1250B, SHA256 4aa877759236f83b009ce2e654e079600e6a2287789f2b155d4179b2ade8b05c

## Native/source/package equivalence and closure

- Full before/final source snapshots are independent actual blobs with byte/SHA manifests, not a fulltext dump mislabeled as a diff.
- .local/iam-model-input-consumer/final/source-652-to-660.patch — 113129B, SHA256 9a484c86d923cc862a661abcd4da2b67470d5b8efc5f5fe1c52d7bb09748df3b. Native git diff --no-index --binary output is retained. Actual git apply --check -p2 and apply -p2 ran in fresh own scratch (no commits), and all660 resulting files matched final bytes/SHA. .local/iam-model-input-consumer/final/native.json — 4696B, SHA256 d9311dbb02f88a5f08c2bdf3519fbed793a14e269645b8dd3fcffd21979d9629
- Fresh exact-source package-work-transaction builds admin-common, engine-runtime and model-gateway with -DskipTests, exit0. This proves compilation/resources/JARs only. Exact argv/start/end/status are docs/evidence/iam-model-input-consumer/package-transaction/command.json and exit.json; raw.log retains actual compiler/resource output. Earlier package/package-final belong to prior candidates and are not the final source proof.
- 565 actual tested-versus-final-package class files have equivalent ASM reconstruction with debug attributes skipped; 563 are byte-identical and 2 differ only in debug information after formatting. The tested-target-transaction snapshot includes the actual new guard code from transaction-green-01; the earlier separate-JVM integration remains explicitly its previous source epoch. 369 actual JAR class/resource entries were compared to the owned package class/resource files. .local/iam-model-input-consumer/final/bytecode-equivalence.json — 181410B, SHA256 83dc3abc3fd3c9bee6a1dd502b74d8d149848908b77f735642c31ae8826566dd
- Finite compiler createdFiles/inputFiles lists, their actual class files, exact source resources and package JARs were copied and hashed: .local/iam-model-input-consumer/final/target-finite.json — 698921B, SHA256 955104d43b0f7595c1ce852afa2044fd930df720cb8dcf438c94213761101c25. Active target pre-existing JARs are explicitly classified in prior-active-jars.json as inventory only, not final package proof. Actual Running/XML evidence is retained per epoch, including regression XML; no target-wide stale report total is counted.
- Protected eight documents equal the producer-accepted afterBytes/afterSha256 checkpoint, including the unchanged original byte identities required by the current baseline. .local/iam-model-input-consumer/final/protected-eight.json — 2075B, SHA256 5eba6d88358496b1dba03f3e74d00e4299adfaa33260698089ba5afa86f06b6b. Other649 canonical entries preserve public gates, authority production and producer code bytes.
- Actual runtime audit has0 matching task java/node processes,0 observed task PIDs alive and0 owned listeners remaining. Own engine/model processes exited normally, TLS servers/executors/clients closed, owned PG sessions are0 and no main service operation was issued. .local/iam-model-input-consumer/runtime-exit.json — 1884B, SHA256 f2812c403cbedae3d6ee554f706f993255abbcece5f027c35b92e0f7b9391b1c

## Limits and rights return

No blocking implementation concern remains within this selected private initial-input scope. The tests use signed local IAM test fixtures, not a production IAM installation. This report deliberately distinguishes the setup failures, initial PORTAL fixture correction, reconstructed early source entry, separate green epochs, formatting-equivalent bytecode and prior EOF candidate. Static boundary checks are not presented as dynamically measured provider/credential invocations.

Model-side frozen model binding/provider state verification and first-wire authorization before tokenizer/credentials/provider remain next dependencies. No public CHAT/modelRefs/LLM/AGENT/driver opening, safe async stream capture, response renewal/history/SUMMARY/RETRY, wire-send permission, real provider or whole-platform/real-IAM/K8s/capacity completion is delivered.

Control and inventory are sealed last after this report. ALL canonical/owned source, target, DB, HTTP, helper, owned workcopy, evidence, report and runtime write/execution rights are returned to root. No background work remains; the author performs no mutation after the final control seal. Acceptance remains the root/reviewer's decision.
