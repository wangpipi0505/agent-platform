# Initial model-input consumer — authorized single-task implementation brief

Root applies subagent-driven-development. The producer repair has now been independently reviewed and accepted. This brief authorizes one sole implementer for the bounded consumer task below.

## Binding root resolutions

- The single-task plan below is the requirement body. It is a consumer task, not a request to revisit the already reviewed producer or accepted common authority framework.
- Producer baseline: ACCEPTED certificate .local/iam-model-input-producer-acceptance.json3338B SHA6b99890d6bffdd99bd61b1efe06d07e2531bf61374d49cc83f8fc2bfb96be9d0. Full repaired source .local/iam-model-input-producer-review-fix/final/source.json125472B SHA0e51caf00543e924c52a40b506b3d1509c24b338db9bb02ba7d9d04413c8f992; full blobs sibling source/. Control .local/iam-model-input-producer-review-fix/control.json5124B SHA7969d681ab98d90f080eb88ef4e168dd0b7cb87bd027633d4dc1978ace39aa29. Review docs/evidence/review-iam-model-input-producer-review-fix.md11331B SHAa2eb47f2d0b093edf19b4f6a010618d05fc6317149fd4ed010a6b911e9c7c376. Historical pending-review statements in the copied architecture rationale below are superseded only by this accepted repaired baseline; no producer API or common authority refactor is authorized.
- A focused check of frozen RunAuthorityStore.createPublished confirmed that all three initial parent MaterialRef.version values are acceptedSnapshotSha, while each payloadSha256 describes its respective payload. Keep app candidate SHA, accepted snapshot SHA, accepted request SHA and future provider-wire SHA distinct.
- Exact scope extension to the plan's file allowlist: admin-common/src/main/resources/agent-service-authority.json may change only the existing model-input metadata-read row's callerSource string to name cn.agentplatform.model.infra.EngineModelInputManifestClient and state that no public model route invokes it yet. Preserve every other JSON leaf and every other file byte. This corrects implementation metadata when the planned client becomes real; it grants no new authority or route policy. If the client is not implemented, do not change that metadata.
- All other common production code, route policies, migrations, public model controller, ModelCalls, ModelStore, provider/tokenizer/measure/SecretVault/attempt/budget/response paths, default guards, web and protected documents remain read-only.
- Only fresh task-owned PG databases, helper processes and HTTPS endpoints are permitted. Record absence before creation and ownership before resource allocation; never connect to or reset previous tasks' fixture databases. Preserve primary services and all old evidence.
- A successful test/build on unchanged source is evidence to retain, not a reason to rerun. New real failures or changed affected source justify focused additional checks. Report exact test epochs and source hashes; do not present multiple epochs as one final full-suite run.
- No main commits, deployments, restarts, schedules, new conversations or nested agents. Sole implementer returns all source/target/helper/report/runtime rights explicitly before any root or reviewer reads active products.
- Final delivery must include full immutable before/final canonical source snapshots, a native binary diff with actual check/apply evidence in fresh owned scratch (no commits), actual finite compiler/resource/JAR/Running/XML evidence, task-owned resource closure, and a report plus control/inventory sealed last. Canonical source count is expected to change from 652 to 660, with eight additions, model POM test-only dependency and the single allowed catalog metadata leaf.

## Single-task plan
# Initial model-input manifest consumer — single implementation plan

> For agentic workers: use root's SDD implementer/reviewer handoff for this one task. This file is an architecture plan, not source-write authorization. No implementation, build, test, PG, browser, native Git or service operation was performed by the planner.

**Goal:** authenticate and strictly validate one history-free initial-chat-main-v1 accepted request against the real engine manifest producer, returning a private request-bound verification result before any model/measure/tokenizer/credential/provider boundary.

**Architecture:** model-owned wire DTO + fixed-engine-origin HTTP client + internal verifier. The verifier uses the existing concrete signed authority client and a live private retained request context. Engine remains the PG material authority. No engine business JAR is introduced into model, no common authority API is changed, and no public route invokes the verifier in this task.

**Tech stack/global constraints:** Java21 / Boot3.5.0 / Security6.5 / Nimbus9.37.3 / SpringAI1.0.0; common plus six business services with api/application/domain/infra. PG is the sole business authority. Design v1.2, four contracts and time exclusions remain unchanged. All commands explicitly use cwd G:/207/项目/算法重组工具/代码/agent-platform. No adjacent project reads or changes. Public CHAT/modelRefs/LLM/AGENT/driver gates and default IAM stay closed.

## 1. Evidence and selected dependency slice

Accepted source references resolve under `.local/iam-o2b-f2c2c-chunk-fallback-fix/final/source/` plus the repository-relative path. source644 manifest:123759B / SHA256 `d666e8dc7f021659ff813e0cb196d7e7e02042a252c4bc6995b536fb59723fcf`.

After producer freeze/rights return, root permitted only ModelInputManifest, ModelInputMaterialStore and ModelInputMaterials under `.local/iam-model-input-producer/final/source/`. Supplied source-manifest SHA `747463804aeadbbad190cf37a8186dacb4f3f23f05ad4c20dfe0d8cb6d0c24a4`, diff SHA `2f9b228d15378f218ef74788b281b061232b46cfb44f76793d235c7b877a4d3c`. Planner read those files, not the diff again, and did not read producer tests/helpers/report/catalog/V218. Frozen producer is still under independent review; its corrected accepted identity must replace this candidate before implementation integration. Root reports writer decision binding/template allocation findings under repair; they are outside this consumer plan and are not re-reviewed here.

1. `model-gateway/src/main/java/cn/agentplatform/model/api/ModelController.java:106` invokes measure from JSON+Actor; chat at:111; stream:120–144 captures Actor before a virtual thread. Existing AuthorityContext is request/thread local. Do not install the verifier after that thread switch.
2. `model-gateway/src/main/java/cn/agentplatform/model/application/ModelCalls.java:240` resolves configuration, :256 measures, :272 resolves SecretVault, :273–293 acquires circuit/quota, :333–356 freezes sync wire bytes, :359–375 freezes reactive bytes, :403/:409 calls SpringAI. countTokens:512–555 can send the full request to a tokenizer and resolve its secret. measure:49–58 reaches that same boundary. A late wire-interceptor check is insufficient.
3. `model-gateway/src/main/java/cn/agentplatform/model/infra/ModelStore.java:366–391` supports a mutable modelEntry fallback or a tenant binding_snapshot lookup, then loads provider secretRef. That is not an engine material trust check. New verifier must not depend on ModelStore/ModelCalls/ModelQuota/SecretVault.
4. `engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelExchange.java:252–283` saves source_request; :395–403 changes invocationRef/adds attemptId; :405–413 consumes budget/increments sdk_attempts and freezes. Do not run the new initial accepted request through this path and claim its original digest remains unchanged.
5. `admin-common/src/main/java/cn/agentplatform/common/ServiceTokens.java:27–30,:66–69` is legacy HMAC and rejects IAM. The actual delegation mechanism is `authority/HttpCurrentAuthorityClient.forward`:292–296, called by IamServiceTicketProvider:12–14. Its verifiedTicket:420–440 validates signed caller/client/target/action/audience/current ceiling/delegation/operation, then returns the same verified raw token string in ServiceTicket.
6. A public VerifiedAuthority/ServiceTicket is not intrinsically trusted. AuthorityContext.bind:28 yields currentOnly; requireInitialFor:43,:73–94 needs a live refreshable private retained proof. HttpCurrentAuthorityClient:371–389 makes that proof constructor private. A wrapper that accepts only the real bound retained object and calls the concrete final client internally has no arbitrary-record input path.
7. Generic forward does not compare child origin_authentication_id to incoming retained origin. Existing authorizeRetained:337–347 already rejects a child ticket whose origin differs from its current signed decision. Frozen producer ModelInputMaterialStore:560–568 compares owner/root/op.subject/full initial/origin; :571–583 requires model→engine/material.scope.read/DELEGATED/PORTAL and matching operation.resource. It does not require the original run's DelegationRef. This is an authenticated reusable metadata read; new valid grants/operations under the same owner/root/fullinitial/origin are allowed. This plan adds no original-run grant/operation equality requirement and does not claim an executed cross-run exploit.
8. Scope canonical semantics are already stable: `MaterialAuthorization.java:28–34` calls `AuthorityProtocol.digest`; `AuthorityProtocol.java:39–44` recursively sorts Sets by Json.canonical, sorts maps and preserves Lists. MaterialScopeV1 Set.copyOf iteration is therefore immaterial to scopeDigest/require. Json.java:57–76 alone is not the whole digest chain. The planner's earlier suspected Set-order blocker is withdrawn. Manifest SHA must use the raw wire JSON tree (producer ModelInputManifest:41–55,:59–73); scope SHA must use the common Set semantics. Request arrays remain ordered. These are distinct digest domains.
9. `common/SecurityConfiguration.java:49–50` keeps IAM startup closed. Accepted catalog at:1731–1763 distinguishes engine→model model.measure/model.chat-stream from skill→model model.chat. This task changes no route/controller/default guard.

**Decision:** implement the internal consumer wrapper + fixed-origin typed transport as one bounded task. No common prerequisite/refactor is necessary: the wrapper can compare original origin against the already signature-verified token returned directly by the concrete forward method, before any engine GET. The exact provenance restriction in §3 is mandatory. Real producer acceptance/corrected frozen interface is an execution prerequisite, not an excuse to invent a different producer.

## 2. Exact new APIs and ownership

Create three focused production files:

```java
// model/domain/EngineModelInputManifest.java — untrusted wire data only
record EngineModelInputManifest(
    int schemaVersion, String producer, String profile,
    SubjectKey owner, ResourceBinding root, String runId,
    String acceptedSnapshotSha256, String requestSha256,
    long modelEntryId, ModelBindingRef modelBindingRef,
    MaterialRef material, MaterialScopeV1 scope,
    List<MaterialScopeV1> parents, String manifestSha256) {
    record ModelBindingRef(long bindingId, String sha256) {}
}

// model/application/InitialModelInputVerifier.java
VerifiedInitialModelInput verify(byte[] acceptedRequestUtf8,
    String relativeMaterialRef,
    HttpCurrentAuthorityClient.RetainedAuthorization received);

// model/infra/EngineModelInputManifestClient.java
// Focused typed transport; production callers are limited to InitialModelInputVerifier.
public EngineModelInputManifestClient(URI engineOrigin);
public ManifestReply read(String materialId, String requestSha256, String internallyIssuedTicket);
public record ManifestReply(ApiResponse<EngineModelInputManifest> envelope,
    JsonNode rawManifest, String materialRefHeader, String scopeShaHeader) {}
// AutoCloseable; rawManifest copied in constructor and accessor. This record is data only.
```

The verifier constructor is `InitialModelInputVerifier(HttpCurrentAuthorityClient authority, URI engineOrigin)`. A package-private trusted fixture constructor may also supply a Clock; normal production uses Clock.systemUTC, never a request-supplied clock. The transport retains bounded raw response bytes only until strict parsing/digest validation. Visibility follows existing layering: transport may expose an immutable ordinary response record (data, never proof). Verifier must never accept that response record/DTO or an HTTP client callback as its public trusted entry. Construct its concrete fixed-origin transport internally from configured URI; no injectable arbitrary producer function or public result factory.

Use explicit finite construction with concrete HttpCurrentAuthorityClient and one fixed engine HTTPS origin. Do not add a mandatory IAM bean/auto-discovered production integration. Missing client/config/context denies. Legacy/dev startup stays compatible because no old constructors or production beans change. No new controller is introduced.

`VerifiedInitialModelInput` is a nested final class in the verifier, private constructor, no Jackson binding/public factory/record constructor. It stores immutable accepted canonical request bytes, validated DTO and the exact final bound retained mapping. Every accessor/requireVerified checks retained validity and exact AuthorityContext binding. JSON access returns copies. It is reusable only within the same live request frame, not a durable cache, async capture, send token, lease or renewal. No caller-provided Actor/currentOnly/record can create it.

## 3. Authority and exact-origin wrapper sequence

1. Before protected parsing/HTTP, require non-null received, requireVerified, and AuthorityContext.requireBound(received). Require actual caller engine, target model, action model.chat-stream, DELEGATED/PORTAL, app-version root, operation.resource=call.resource and owner identity. Reject measure, skill ordinary chat, direct portal/gateway/workload/integration/events purposes. This limited initial profile does not broaden model admission.
2. Refresh with AuthorityContext.requireInitialFor using the actual received actor and signed root. Capture its returned `fresh` mapping; repeat purpose checks. Do not requireBound on the old object after refresh has replaced it. Owner/root/full initial/origin are derived from private retained evidence; candidate metadata is not an authority source.
3. Derive only `new ServiceCall("engine", "material.scope.read", fresh.current().call().resource())`. Require the actual frozen canonical row from producer: GET `/internal/material-scopes/{kind}/{id}`, caller model, target engine, delegated, integrationAllowed=false. Call the **concrete final** HttpCurrentAuthorityClient.forward directly. No ServiceTokens, caller token, caller ServiceTicket, generic CurrentAuthorityClient implementation, workload/resume fallback or token cache.
4. Immediately inspect the returned token's origin claim inside a private verifier helper. This is **field extraction from already verified bytes**, not a new JWT verifier: HttpCurrentAuthorityClient verifiedTicket:420 obtains raw, verifies it, and:440 places that same raw into ServiceTicket; forward:295 checks operation before returning. The helper receives only this local return value, never public input. Bound decoded payload length<=64KiB, require exactly three JWT segments, strict duplicate-free object parsing and one textual nonblank origin_authentication_id<=128 chars; compare it exactly with fresh.requireOriginAuthenticationId. Also compare returned OperationBinding to fresh.current.operation. A changed/missing origin denies before engine GET.
5. Do not expose token/claims or treat a separately supplied ServiceTicket as equivalent. Annotate the private helper's verified-source precondition with the concrete source lines above. No independent signature/issuer policy duplication, common changes or unverified token acceptance. Keep JWT details inside this one private helper.
6. Fixed-origin GET uses only the internally obtained ticket and constructed selector. Material transport is outside every PG transaction; this component has no DataSource. After complete manifest validation, refresh the same inbound authority again. Existing AuthorityContext checks preserve call/delegation/operation/fullinitial/origin across its refresh. Validate all material scopes against that new actual current, then mint the private result bound to that exact mapping. If any prior proof expires, recheck fails, current narrows or IAM is unavailable, return no result.

Forward may produce a narrower current actor; engine export reauthorizes that child ticket and validates scope coverage. The final model-side recheck independently requires the incoming current to cover all scopes. Do not widen either current from full initial ceiling. Child metadata reads need not share the run's historical operationId/grant; they must keep the incoming model request's original origin association. A new signed current decisionId/authorizationVersion is not a changed material version.

## 4. Candidate request, selector and hash bindings

Take a private byte copy of acceptedRequestUtf8, <=2MiB before parse; strict JSON object parsing and parsed/canonical byte bounds. The request is untrusted candidate data until authenticated producer manifest matches it. Compute `Digest.sha256(Json.canonical(request))` over every field, including diagnostics/computed requiredLabels/invocationRef. Do not remove fields, trim/measure, rebuild messages or append attemptId. Object key reordering is permitted by canonicalization; message/other arrays keep order.

Relative locator must be exactly `/internal/material-scopes/model-input/m_[0-9a-f]{32}?version=[0-9a-f]{64}`. Exactly one query key; reject percent-encoded alternatives, duplicates, unknown keys, fragment, trailing slash, userinfo, path traversal, absolute or scheme-relative URI. Request SHA equals selector version before GET. Construct path/query from validated id+SHA; never URI.resolve an arbitrary caller string. Scheme/host/port come solely from constructor configuration.

Require manifest schema1/engine/initial-chat-main-v1. Compare owner/root to the private retained evidence; runId/modelEntryId/modelBindingRef to candidate request fields; requestSha256/material.version/material.payloadSha256 to complete candidate hash; material producer/kind/id to exact locator. Require request.callKind=FORMAL, positive model/binding IDs, r_32hex run, and invocationRef=`material:{id}:MAIN`. Request payload must match the first history-free profile; no unsupported tool/history/summary/retry/material ref inputs are accepted merely because a DTO parsed. Producer provenance plus the exact request hash supplies the accepted payload association.

acceptedSnapshotSha256 is authenticated producer metadata, not automatically trusted because a candidate field uses that name. Verify the full three-root parent structure and versions against it. Root versionDigest is the app-version candidate SHA; acceptedSnapshotSHA is a run snapshot SHA; material version/requestSHA is the complete accepted model request SHA; SpringAI wire SHA is a later different digest. AuthorizationVersion, operationId and PG checkVersion are unrelated fields and must never be compared to these content hashes.

## 5. Strict typed manifest and current material checks

Transport retains the raw parsed manifest tree until its SHA passes. Require HTTP200, ApiResponse.code=OK and non-null typed data. Exact one X-Agent-Material-Ref equals the constructed relative selector; exact one X-Agent-Material-Sha256 equals child scopeSHA. These headers are assertions to check, not independent proof.

Use a local copy of common Jackson settings, never mutate Json.mapper. Reject duplicate keys, unknown/missing fields, mandatory nulls, malformed UTF-8, trailing values and scalar coercion. Inspect arrays before constructing Set-based scope records so duplicates are rejected, not silently collapsed. Manifest schema has exactly its14 fields; modelBindingRef has bindingId/sha256 only. All parents/scopes/material/owner/root have their exact existing field sets and primitive types. Material metadata: UTF-8 envelope<=256KiB, depth<=32, exactly3 parents, roles<=128, entitlement keys<=32 and values/key<=256, labels<=256, strings<=256 or stricter existing ID limits. Content over bounds is rejected before large allocation/materialization and again after canonicalization.

Digest sequence:

- `manifestSha256 = Digest.sha256(Json.canonical(rawManifestTree minus only manifestSha256))`. Do not hash ApiResponse, reorder raw arrays or hash a lossy typed DTO.
- For child and every parent, strictly construct MaterialScopeV1 after field/count/duplicate validation and require `MaterialAuthorization.scopeDigest(scope).equals(scope.scopeSha256())`. The common helper sorts Set-valued permissions internally through AuthorityProtocol.digest; use that existing semantics rather than an alternate raw-JSON scope hash.
- Apply MaterialAuthorization.require for child and each parent against the final verified current. Use the trusted runtime clock (systemUTC in production; the same trusted fixture clock for tests) and retained.requireVerified; no request-supplied Clock or recreated authority record. Require owner and original full initialCeilingSHA explicitly. Do not demand historical sourceDecisionId/sourceAuthorizationVersion equal a new current check.

Require exactly one engine run-snapshot/run-inputs/run-version-refs parent, all same run/owner/initial and acceptedSnapshotSHA version. run-snapshot/run-inputs have no parents; run-version-refs points exactly to run-snapshot. Child parent hashes are exactly the three unique parent hashes in sorted canonical order, no missing/extra/digest-only substitution/cycle. Recompute all hashes before trusting relations.

Child requiredRoles/entitlements/dataLabels must contain the full original initial obligations plus every parent's requirements. Current trusted labels also remain obligations; candidate labels cannot subtract any of them. The request's requiredLabels must include child obligations that apply to its payload; preserve exact producer semantics and reject under-union. MaterialAuthorization checks permission sets; labels remain obligations for later provider policy. Scope digest validation never replaces authenticated engine provenance.

The result proves this accepted request and material closure. It does not resolve model.binding_snapshot, verify provider state/secret, authorize wire send, create response history or authorize a tokenizer.

## 6. Fixed-origin transport implementation requirements

`EngineModelInputManifestClient` is focused infrastructure, not a general service discovery/client framework. Configure one HTTPS origin, host and optional port only; reject credentials/query/fragment/non-root path. Ordinary TLS chain+hostname validation, no trust-all. GET only. No redirects, including same-origin; no cookies/discovery/retries/cache or caller-controlled proxy. Reject unexpected Content-Encoding; do not transparently inflate unbounded content.

Connect<=2s; one monotonic total<=5s covers connect/headers/full body. Use a bounded asynchronous body subscriber; check Content-Length where present and every incoming chunk against remaining256KiB before allocation/copy. Per-read timeout/header arrival alone is insufficient. On overflow/timeout/interruption, cancel subscription/future, close owned response resources and preserve interrupt status. HTTP client lifecycle is explicit and closed by owned component/fixture shutdown.

Return sanitized503 for transport/unavailable producer; return403 MATERIAL_SCOPE_MISSING_OR_CHANGED for trusted-response semantic/hash violations. Bad selector/request is400 before GET. Authority failures retain their meaningful existing403/503. Never propagate untrusted remote messages, token/grant/request text or secret values to logs/exceptions. No fallback to old model paths. Engine GET transmits no candidate payload, only relative selector and new internal bearer.

## 7. Exact implementation/test allowlist

Production creates only:

- model-gateway/src/main/java/cn/agentplatform/model/domain/EngineModelInputManifest.java
- model-gateway/src/main/java/cn/agentplatform/model/infra/EngineModelInputManifestClient.java
- model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java

Tests create only:

- model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputVerifierTest.java
- model-gateway/src/test/java/cn/agentplatform/model/infra/EngineModelInputManifestClientHttpTest.java
- model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerHttpTest.java
- model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java
- engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputConsumerProducerFixture.java

Allow one existing-file change: model-gateway/pom.xml adds only a test-scoped admin-common test-jar dependency to reuse accepted signed LoopbackAuthority fixtures. Current model pom:10–64 lacks that dependency. No engine business/test JAR is added to model; no model business JAR is added to engine. No library version change.

The new engine test fixture runs as its own JVM, reusing corrected accepted producer test setup read-only after root grants those frozen test-source reads. It starts the actual ModelInputMaterialController with real receiver security and its owned PG, uses existing RunAuthorityStore.createPublished:23–24 within its required transaction and `ModelInputMaterials.prepareInitial(long)` to create genuine data. Do not substitute a fake production manifest controller. It must not edit the accepted producer's tests. Propose that root separately authorize exactly one owned orchestration helper `.tools/iam-model-input-consumer-run.ps1` plus `.local/iam-model-input-consumer/` and `docs/evidence/iam-model-input-consumer-report.md` for implementation evidence.

Fixture process coordination is test-only: a task-owned ready descriptor identifies random HTTPS origin, authority endpoint configuration, separate private fixture credential/ticket files, candidate request file and exact material ref; no business secrets/tokens in logs or report. Model tests receive these files via explicit system properties, use real concrete authority clients and real GETs, and never connect to engine PG. A task-owned stop signal and bounded finally cleanup terminate only that fixture. The engine fixture reports PG/source/counter identities after shutdown. Test input files are disposable test credentials, not production authority or business storage.

If corrected producer test setup cannot be reused with this one test file, return the exact missing fixture interface to root; do not silently introduce cross-service JAR dependencies or broaden production source. Independently mocked HTTPS endpoints remain allowed for malformed/redirect/timeout negatives; only the actual PG-backed producer qualifies as the integration positive.

Read-only all common authority/helpers/catalog, producer business sources/migrations, ModelController/ModelCalls/ModelStore, other engine/driver/GraphRules/TraceStore, application.yml/security/main classes, design/four contracts/protected execution documents, frontend and old evidence. Public gates/defaultIAM stay unchanged.

## 8. Failure-first HTTP/PG test contract

Tests use the actual signed loopback authority protocol and canonical catalog, normal TLS with test-only CA/SAN and random ports. No real environment question remains. New PG database is task-owned, uniquely named, preflight nonexistent and on the new project's authorized PG25432; no H2 or Map production substitute.

1. **Private entry:** Actor/currentOnly/arbitrary public authority or DTO cannot create a verified result. Valid retained from wrong purpose/owner/root, replaced/closed/cross-thread proof denies before material GET; count IAM source rechecks separately from data-plane GET. Positive uses real signed engine→model model.chat-stream with DELEGATED/PORTAL.
2. **Origin wrapper:** from an otherwise valid signed source, use existing fixture mutation hook to return a genuinely signed forward ticket with origin changed/missing while root/owner/initial/operation/grant stay valid. Generic forward's raw return is the baseline; verifier denies before engine GET (counter0). A fabricated ServiceTicket cannot be passed into the verifier API. Test the genuine unchanged-origin positive. Downstream ticket-vs-decision mismatch is a separate existing defense, not evidence this wrapper check ran. Do not add historical-run grant equality.
3. **HTTP hard limits:** wrong host/untrusted CA fail TLS; absolute/scheme-relative/query-duplicate/encoded selectors fail before HTTP; redirect target receives zero requests/bearers. Test both same-origin and other-origin redirects. Stalled headers and trickling body exceed the one total bound; oversized Content-Length and chunked overflow cancel. Malformed UTF-8/JSON/duplicate keys/depth/string/count violations deny. A normal bounded HTTPS response succeeds.
4. **Request/hash semantics:** changing only diagnostics/computed labels/messages/modelRef/run/invocationRef invalidates the request association. Reordering object keys alone is a positive; reordering message arrays is negative. Wrong headers/manifestSHA/scopeSHA and a tampered field with only one hash recomputed deny. Raw manifest vs scope Set semantics are checked independently, including permission arrays in a different iteration order that legitimately preserve scopeDigest; request arrays never become unordered.
5. **Closure and current:** missing/extra/duplicate/foreign parent, wrong root/owner/initial/model/acceptedSnapshotSHA, child under-union obligations and legacy missing scope deny. Real initial A/B then current narrowing to A denies a material requiring A/B even with a cached valid manifest. IAM503/revocation/expiry during transport yields no result/fallback. An explicit later call under recovered valid current is the positive. A material's historic sourceDecisionId differing from current is a positive, not a replay failure.
6. **Real producer+PG positive:** dedicated engine JVM uses owned createPublished plus accepted prepareInitial for model-bearing initial profile, under the still-closed public admission layer. Model consumer verifies the actual new controller's metadata over HTTPS and validates every request/scope/manifest/parent digest. A repeated newly authorized read succeeds. Same origin with a valid new grant/operation must follow existing export semantics and is not rejected merely for differing from historical run grant/operation. Different origin must not be accepted as the original inbound origin.
7. **Real PG negatives:** fixture creates missing-manifest/legacy-scope and wrong-owner/root cases without disabling immutable triggers. A missing parent fixture is assembled before publication/owned insert where supported; if DB constraints prevent that corruption, preserve that real constraint denial separately and use malformed-response negative for client parent validation. Do not disable triggers then imply ordinary supported state. All negatives must identify the actual failing boundary.
8. **Separate JVMs:** real engine and model consumer run in independent JVMs with multiple roles/regions/labels; verify scope digest using common helper and manifest digest from raw tree. This is directed cross-service evidence, not a new whole-platform canonical audit or a suspected canonical defect. No repeated random JVM loop is required.
9. **No egress/side effects:** provider/tokenizer/measure/SecretVault counters0; model invocation/quota/attempt writes0; no response lease open/check. Actual IAM and engine manifest HTTP are expected and counted separately. Model-side tests do not read engine schema. Confirm unchanged guards/source identities, not a claim that public business routes succeeded.

Write meaningful negatives/positive controls before implementation. Missing symbol/compile failure is not RED; create minimally compiling signatures if needed and record real selector/origin/hash acceptance failures. Never weaken existing security for RED.

## 9. Execution, build, evidence and freeze

One task, in these bounded stages:

- [ ] Root supplies corrected accepted producer/source/catalog/test-fixture identities and freezes exact write/read allowlists above. Do not build a mixed candidate while independent producer fixes are still active.
- [ ] Save before identities; add focused failure/positive tests and capture behavioral RED.
- [ ] Implement three consumer classes and test fixture only. Do not connect public model calls or change common authority.
- [ ] Run focused model tests and the real separate-JVM producer/PG integration; collect actual assertions/counters/source/PG identity.
- [ ] Run relevant retained/context/current-authority and model-policy regressions once, plus finite legacy construction/default guard check. Package affected modules. Do not rerun unchanged full backend/frontend/PG history or earlier accepted O2 chains.
- [ ] Freeze exact source/blobs/diff/evidence/owned target inventory and return all rights. Report metadata last; correction after freeze is a new candidate.

Candidate commands (later authorized implementation only):

```powershell
mvn.cmd -B -ntp -pl model-gateway -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=InitialModelInputVerifierTest,EngineModelInputManifestClientHttpTest,InitialModelInputConsumerHttpTest" -Dsurefire.failIfNoSpecifiedTests=false
mvn.cmd -B -ntp -pl engine-runtime -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=ModelInputConsumerProducerFixture" -Dsurefire.failIfNoSpecifiedTests=false
mvn.cmd -B -ntp -pl admin-common,model-gateway -am test "-Dmaven.repo.local=.tools/m2" "-Dtest=RetainedAuthorizationTest,AuthorityContextRetainedTest,CurrentAuthorityClientTest,ModelPolicyTest" -Dsurefire.failIfNoSpecifiedTests=false
mvn.cmd -B -ntp -pl admin-common,model-gateway,engine-runtime -am package "-Dmaven.repo.local=.tools/m2" -DskipTests
```

The owned helper launches engine fixture first, waits for its verified ready descriptor, runs model integration with the explicit fixture properties, signals shutdown in finally and collects both process exits. Commands above are stage definitions; do not accidentally run the integration without its ready fixture or count a skipped absent-fixture test as success. Use actual project JDK21/Maven/own cache. Package skipTests is compilation evidence only.

Evidence: exact allowed source before/after bytes+SHA, complete final source blobs, actual diff vs fulltext distinction, scratch plain/binary-capable patch/check/apply owned by root, RED/GREEN commands/exits/actual Running/XML, HTTP counts/TLS/deadline outcomes, PG identity/migration/final rows, current/origin negative boundary, finite generated compiler/target inputs, protected document equality, task-owned listener/process exit, redacted fixture credential handling and report/control/inventory LAST. No main Git/commit/deployment, no directory deletion, no main service operation. Stop only owned fixture processes and preserve diagnostics/caches. Do not repeat green tests on identical source for cosmetics.

Accept only the private initial-input consumer's producer authenticity/request/hash/parent/current guarantees. Model-side frozen binding verification and first-wire/send authorization before tokenizer/credentials/provider remain the next dependencies. Safe async stream capture, response renewal, history/SUMMARY/RETRY and public CHAT/modelRefs/LLM/AGENT/driver remain unimplemented. No full-platform/real IAM/K8s/capacity completion claim.

## 10. Planning handoff

Only `.local/iam-model-input-consumer-next-task-plan.md` was written. writing-plans and SDD architecture guidance were used; explicit assignment overrides default extra files/commits/tests/dispatches. No subagent was created. The earlier Set-order blocker and generic common prerequisite were rejected after reading AuthorityProtocol.digest's actual recursive Set normalization. The final selected task is model consumer wrapper+transport, with common source read-only. Final bytes/SHA report returns this planner's sole write right.


## Execution and handoff contract

The design and architecture plan are already selected and user-authorized; begin implementation without another user approval. Follow AGENTS.md, docs/implementation-brief.md, contracts/README.md, TDD and relevant Java coding guidance. Keep task comments about actual invariants, transaction boundaries and mechanisms actually inspected.

Sole owned auxiliary paths: .local/iam-model-input-consumer/, docs/evidence/iam-model-input-consumer/, .tools/iam-model-input-consumer-* and docs/evidence/iam-model-input-consumer-report.md. The named PowerShell orchestrator may use Node/Python helpers under that exact helper prefix. No writes to old task helpers/evidence/reports/control/inventory, root certificates/checkpoints or protected execution documents. Before touching canonical files, snapshot and verify all652 against the accepted baseline. The same narrow ownership grant applies throughout implementation; root will not inspect active product/helper/report/target files until full freeze and return.

The engine producer fixture is a test-scope separate-JVM main helper, not a production route or service JAR. Compile it through the appropriate test-compile lifecycle and launch its main with explicit own finite classpath; candidate commands in the architecture plan do not require treating a non-JUnit helper as a test or counting zero-Running as success. Actual integration assertions belong to the named model test and require a ready real engine producer fixture. Default tests cannot silently skip missing fixture inputs. Preserve full actual command/cwd/source/Running/XML and process-exit evidence.

New PG databases must have a distinct iam_model_input_consumer_<32hex> prefix, be absent in own PG catalog, and be registered explicitly before CREATE; audit exactly the owned registry names. Do not connect/reset older fixture databases. Frozen TLS fixture assets may be reused read-only if normal chain/SAN verification remains valid; live endpoints and all new files belong to this task. Keep database password, signing keys, raw JWTs and request payloads out of outputs. Runtime credentials stay in fixture-owned config with references and bounded private descriptors, never committed product config.

Use local JDK21 C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot, Maven .tools/m2, Node C:/Program Files/nodejs/node.exe. Child unix-domain temp flag only -Djdk.net.unixdomain.tmpdir=V:/.local/tmp. Every shell cwd G:/207/项目/算法重组工具/代码/agent-platform. Preserve all main15173–15175/18080–18085, PG25432, Redis26379 and MinIO29000/29001 processes and configuration. Package in a fresh exact-source owned workcopy to avoid the known historical originaltarget Boot rename failure. No test/build/database/browser history reruns for metadata, cosmetic logs or cleanup.

Write the full report to docs/evidence/iam-model-input-consumer-report.md. It must state scope, exact changed/new paths, actual RED/GREEN tests and commands/results on each source epoch, HTTP/TLS/total-deadline and cancel evidence, real separate-JVM producer/PG positive, authority/current/origin negatives, forbidden-egress/fact-write witnesses and limits, source/native/finite package equivalence, task-owned runtime/database closure and unresolved concerns. Seal control/inventory LAST, with source and report identities and immutable complete blobs/native diff (no commits), then return only status, report/control/inventory/source/diff bytes/SHA256, concise tests and concerns. Explicitly return ALL source/target/DB/HTTP/helper/owned/evidence/report/runtime write and execution rights and confirm no background work. Do not mark root acceptance or global completion. No subagents.