# Independent review — private initial model-input consumer

## Spec compliance

**Issues found.** The private entry, origin wrapper, material closure and bounded transport substantially implement the selected task, but accepted requests with ordinary floating-point parameters can fail the request hash check because the consumer changes the producer's canonical numeric semantics (I1). The runtime egress-count contract also lacks measured provider/tokenizer/measure/SecretVault counters; the report accurately calls its substitute static isolation, while the fixture still contains disconnected zero assertions (M1).

**Code quality: Needs fixes.** Critical: **0**; Important: **1**; Minor: **1**. No root acceptance or global completion is asserted.

## Reviewed identities and scope

- Review brief: `.local/review-iam-model-input-consumer-brief.md`, 4723 B, SHA256 `4ae864f93f448eef8060529f7e8faa80c6147880fd21476347f60b206bb46e73`.
- Binding implementation brief: `docs/evidence/iam-model-input-consumer-brief.md`, 40119 B, SHA256 `4f873811731c2baddc984f507162c42156432d2717a69f2f52e13049ab76bbb4`; accessor supplement: 2628 B, SHA256 `5f394cc616154f7795f1110ce95e0c6b5afd40554fa2775dd22f95b978fbc524`.
- Accepted base source652: supplied SHA256 `0e51caf00543e924c52a40b506b3d1509c24b338db9bb02ba7d9d04413c8f992`.
- Reviewed complete native patch: `.local/iam-model-input-consumer/final/source-652-to-660.patch`, 113129 B, SHA256 `9a484c86d923cc862a661abcd4da2b67470d5b8efc5f5fe1c52d7bb09748df3b`.
- Final source660 manifest: 127244 B, SHA256 `09a1d86d1636385620127e39e7f53d650b7a0f0a30689cef3a65b6bebedf6ea0`.
- Implementer report: 25296 B, SHA256 `c7a6edf811239013ba7740d48ac3474c7a45d14bcca6d11c2e63956ab57ef75d`; control: 5966 B, SHA256 `63f06a17f255df7c65704d7a65cd3f24cc8192243687aa42402659a84f04a199`.

All seven reviewed input identities above were independently size/hash checked except the base, whose supplied identity is recorded as such. The complete native patch was read once, to EOF, in disjoint line windows 1–400, 401–800, 801–1200, 1201–1600, 1601–2000, 2001–2400 and 2401–2700. No changed source file was separately reread; all new files are complete hunks. The diff contains exactly eight additions and the three authorized existing-file changes. Catalog callerSource, test-only POM dependency and the one common test accessor expression match their narrow grants.

## Strengths

- `model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java:67` validates the private retained input and exact live binding before protected parsing; purpose checks at :131 constrain caller/target/action, DELEGATED/PORTAL, app-version, owner and operation resource. It captures fresh mappings and never rebinds the replaced proof.
- The verifier at :108–115 directly forwards through the concrete authority client, compares operation and extracts the origin only from that local verified return. The private helper at :158 bounds the decoded payload, checks strict parsing and compares the original origin before GET. The corresponding signed changed/missing-origin test establishes generic forward's positive baseline and then GET0 (`InitialModelInputVerifierTest.java:99`).
- Final refresh and material authorization at verifier :121 and :349 bind the result to actual current authority, full initial obligations, exact three-parent kinds/run/owner/version and exact parent-hash relationships. Historical decision/grant equality is deliberately absent. Result accessors at :467–484 check exact mapping and return defensive byte/tree copies; the referenced common scope record also copies its collections.
- `model-gateway/src/main/java/cn/agentplatform/model/infra/EngineModelInputManifestClient.java:56` constructs a finite normal-TLS origin with no redirect/proxy. Its asynchronous read at :81–159 covers headers and full body under one monotonic deadline; the subscriber at :339 checks aggregate chunk size before allocating/copying and cancellation is explicit. Active Spring transaction guards precede authority refresh or metadata HTTP in both public entries.
- Transport parsing at :168–322 rejects malformed UTF-8, duplicate keys, missing/unknown/null/coerced fields and bounded permission arrays before typed Sets. Raw manifest hashing removes only manifestSha256 (:222–225); each typed scope uses the common scopeDigest (:315–316), preserving the distinct raw-array and permission-Set digest domains.
- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputConsumerProducerFixture.java:171` uses the existing real createPublished transaction, then prepareInitial and the actual controller/security (:217, :345). `InitialModelInputConsumerHttpTest.java:112` exercises initial/repeated/new-grant reads across explicit distinct JVM PIDs; negative fixtures preserve immutable triggers. The code and report distinguish that integration from mocked transport negatives.

## Issues

### Critical

None confirmed.

### Important — I1: Preserve producer numeric canonicalization for accepted requests

**Location:** `model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java:27` (USE_BIG_DECIMAL_FOR_FLOATS), with rejection at :105.

The producer accepts floating-point sampling parameters and hashes the complete request using the existing `Json.canonical` semantics. The consumer's local REQUEST mapper changes float nodes to BigDecimal; the installed Jackson 2.19.0 default node factory strips trailing decimal zeroes. A genuine producer request containing `"temperature":1.0` or `"topP":1.0` therefore has canonical numeric text `1.0` at the producer and `1` at this consumer. The selector remains the genuine producer request SHA, so verify rejects the unchanged accepted request with MODEL_INPUT_INVALID/400 before any metadata GET. This is an interoperability failure for supported, common parameter values, not a malicious-input rejection or merely an object-node equality issue.

Focused supporting source: `admin-common/src/main/java/cn/agentplatform/common/Json.java:10` uses the default mapper; :57 canonicalizes nodes without defining a new numeric normalization. `engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelInputMaterials.java:148` permits numeric temperature/topP/penalties and :194 invokes ContextAssembler. `ContextAssembler.java:79` deep-copies params and :87–90 copies sampling numeric nodes; `ModelInputMaterialStore.java:204` hashes Json.canonical(request). Read-only javap inspection of the installed Jackson dependency confirmed BaseNodeDeserializer._fromFloat routes enabled BigDecimal parsing into _fromBigDecimal, which invokes stripTrailingZeros under the default JsonNodeFactory. No test or application execution was used to establish this finding.

**Fix:** retain the producer's existing floating-point parse/canonical behavior in the consumer's local strict mapper while retaining duplicate/type/shape/size validation. Do not fix this by changing the accepted producer/common canonical contract or by accepting alternative hashes. Add a focused genuine producer/consumer positive with whole-valued floats (1.0 and 0.0), alongside a fractional value and a one-field negative. The current unit request (`InitialModelInputConsumerFixture.java:51`) has only integer maxOutputTokens, and the real producer snapshot (:115) supplies no floating params, so their positives miss this regression. These are recommended changed-input checks, not a request to rerun unchanged green suites.

### Minor — M1: Remove disconnected zero assertions and label structural evidence explicitly

**Locations:** `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java:31`; `InitialModelInputVerifierTest.java:337`; `InitialModelInputConsumerHttpTest.java:88`.

forbiddenEgress is an AtomicInteger that nothing increments; asserting it remains zero cannot detect an accidental provider/tokenizer/measure/SecretVault call. The integration result also writes literal zero for provider/tokenizer/measure/SecretVault/invocation/quota/attempt references and response-lease references. These values are configuration declarations, not observations. The implementer report candidly states this limitation, and the actual private production source has no such collaborators, so this is not a confirmed egress defect and does not invalidate the separate real engine SQL/IAM observations. Nevertheless the dead assertion and numeric output resemble the measured counters explicitly requested by the brief.

**Fix:** remove the disconnected sentinel/assertion and make structural declarations unmistakably structural; if retaining the brief's runtime-counter claim, supply actual boundary observations instead of constants. Keep runtime PG/IAM measurements separate. No broad business execution is warranted merely to make cosmetic zero fields green.

## Focused checks and evidence limits

Two named risks justified all outside-diff source inspection:

1. **Immutable proof DTO escape / scope semantics:** inspected only common MaterialScopeV1 and MaterialAuthorization. Collection copying closes the result-mutation risk; require validates schema1, current authority, scope digest, owner and permissions. No defect found there.
2. **Producer/consumer numeric canonical compatibility:** inspected only common Json and the specific numeric assembly/hash portions of accepted ModelInputMaterials, ModelInputMaterialStore and ContextAssembler; inspected Jackson 2.19.0 BaseNodeDeserializer/JsonNodeFeature/JsonNodeFactory bytecode with read-only javap. This established I1. The narrow path lookup and source searches did not expand into a codebase audit.

No test, build, PG, browser, service or native-Git command was run. No existing diagnostic or RED evidence was altered. The report/control retain distinct epochs: 21 unit tests at 338a09d..., 2 real PG integration tests at ccab570..., 41 regressions at 338a09d..., and only the latest focused 5 tests at final 09a1d86.... The latest guards have two behavioral RED failures followed by those five passing cases. Earlier setup/compilation/zero-Running failures are not counted as RED or passes. These are implementer-reported results; root owns actual Running/XML/epoch verification. No latest-source full-suite pass is inferred.

**Cannot verify from this diff:** complete physical source/blob equality; native apply/check equivalence; actual compiler/resource/classpath closure, ASM and JAR equivalence; actual test process exits/XML and pristine output; database identities/migrations/rows/sessions; listener/process shutdown; original protected-document and unchanged-source identities. Root's separate physical/evidence audit must resolve these. Static absence of forbidden collaborators is visible in the three production hunks; dynamic provider/tokenizer/measure/SecretVault counters are not established by the fixtures (M1). These limits do not waive I1 or convert old epochs into a final full suite.

## Assessment and freeze

**Spec compliance: Issues found. Code quality: Needs fixes. Counts: Critical 0 / Important 1 / Minor 1.** The security structure is careful and narrowly scoped, but a valid accepted request must survive the producer/consumer canonical boundary before this task can be trusted.

Only `docs/evidence/review-iam-model-input-consumer.md` was written. This report is frozen. ALL report write and review execution rights are returned to root; no source/target/helper/original-evidence/control/inventory/database/runtime write was performed, no subagent was created, and no background work remains.
