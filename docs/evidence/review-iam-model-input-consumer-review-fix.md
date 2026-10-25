# Independent review: private model-input consumer I1/M1 repair

Spec compliance: **Spec compliant within the reviewed repair scope**. Code quality: **Approved with a nonblocking Minor observation**. Counts: **Critical 0 / Important 0 / Minor 1**. Original I1 and M1 are closed by the reviewed repair. This review does not grant root acceptance, public model activation, provider binding acceptance, or global task completion.

## Reviewed identities and scope

The reviewer read `.local/review-iam-model-input-consumer-review-fix-brief.md`, the implementation brief, numeric supplement, reviewer template, the original review's I1/M1 findings, the complete repair report and control. The code-review skill was applied subject to this task's narrower no-rerun/read-only contract.

The following identities were independently checked using filesystem byte lengths and SHA256:

| Input | Bytes | SHA256 |
| --- | ---: | --- |
| Original source660 manifest | 127244 | `09a1d86d1636385620127e39e7f53d650b7a0f0a30689cef3a65b6bebedf6ea0` |
| Final source660 manifest | 127244 | `de1ac03e734b8aae7288ecafe553dbc0bcaa7d4d2a82961a6d5f887910d03c4e` |
| `.local/iam-model-input-consumer-review-fix/final-seal/source-660-to-660.patch` | 24889 | `b45bb9d391274e17d52979ce416216a9d42cb7b3b0fb854c9bf09aa57a504f29` |
| `docs/evidence/iam-model-input-consumer-review-fix-report.md` | 26452 | `7cef1211464999a528ddc8f98f651ea7cf7c51d19e071525f2d1aad322fac2ec` |
| `.local/iam-model-input-consumer-review-fix/control.json` | 8266 | `529ad52031cf11bcefe1fc024a06c85ddd47e9f16eeef17fcd2a55b33e4ee6a8` |

The sole native repair diff was read to EOF. The combined tool output clipped a short verifier header; only that missing header/context window was recovered. The original 2700-line consumer diff was not reread. The repair diff contains exactly the five authorized existing source paths, with no additional file patch. Physical all660 equality, exact unchanged655 closure and native application remain part of root's separate audit.

## Strengths and specification checks

- **Safe token provenance and retained bounds:** `model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java:184` clones and strictly decodes the bounded input. At :189 the complete input must pass the original tree parser before the :193 second lexer pass can construct a node. Only VALUE_NUMBER_FLOAT plus `parser.getText()`/`getDecimalValue()` can reach the private constructor at :224. The original mapper at :23 still requires trailing-token rejection, BigInteger/BigDecimal semantics, duplicate detection, depth32, name256 and number256 limits. The constructor repeats the token256 bound. `writeRawValue` at :315 receives only successfully parsed numeric text, with no public/raw-fragment constructor or nonnumeric source. The added injection, duplicate-token, non-JSON-number and oversize cases in `InitialModelInputVerifierTest.java:196` exercise rejection before GET.
- **Numeric behavior and canonicalization:** the private immutable NumericNode at `InitialModelInputVerifier.java:219` delegates numeric conversions to DecimalNode and retains floating-point classification. It does not impose double finiteness, so finite BigDecimal `1E+400` remains supported. The raw numeric serialization at :311 preserves spelling through common `Json.canonical`/valueToTree. The focused unchanged common implementation, `admin-common/src/main/java/cn/agentplatform/common/Json.java:57`, still sorts object fields recursively at :65 and preserves array iteration order at :73. No serializer or producer production change appears in the repair.
- **Single complete digest and defensive result:** `InitialModelInputVerifier.java:103` computes one complete canonical request hash and checks it against the selector at :106 before forwarding or metadata GET at :116. No alternate digest attempt, omitted field, relaxed binding or new public interface was added. `requestProfile` at :419 retains integral bounds for maxOutputTokens/topK/seed and numeric sampling checks; CanonicalFloat satisfies those numeric APIs. Result construction at :592 clones bytes and deep-copies the tree; accessors at :603 and :608 revalidate lifetime and return defensive copies. Sharing the private immutable scalar during deepCopy does not expose mutable token/decimal state. Unit assertions at `InitialModelInputVerifierTest.java:63`, :123 and :165 verify returned request canonical SHA and accepted bytes, rather than asserting a particular node implementation.
- **Actual producer export:** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputConsumerProducerFixture.java:234` retains jsonb display separately, obtains the actual retained continuation, and calls unchanged locked-source `ModelInputMaterials.assemble` in a transaction. At :248 it asserts that the assembled canonical SHA equals the actual prepareInitial manifest before writing request.json. This is not construction of an expected manifest/hash from a synthetic payload. The changed registry prefix/owner and duplicate-registry check remain repair-specific at :46, :77 and :91.
- **One-field negative and honest numeric forms:** `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerHttpTest.java:158` finds exactly one actual temperature token, replaces that token, and compares both unchanged prefix and suffix. It does not parse/reserialize another numeric field. Positive assertions at :118 check both returned representations against the actual manifest SHA and read all four numeric values. The retained request physically contains temperature1, frequencyPenalty0, topP0.25 and presencePenalty0.00001. Literal1.0/0.0/common-scientific support is separately established by signed unit cases; the real producer result is not misreported as literal1.0/0.0.
- **Original guards preserved:** the diff confines production edits to numeric request parsing and the private node. Purpose/origin/current/closure/lifetime/ambient-transaction guard blocks are unchanged. The actual final verifier XML includes the existing wrong-purpose, selector/hash, closure, origin, no-context, narrowing/revocation/outage/expiry, transaction and fail-closed configuration cases, together with the new numeric cases. This does not substitute a wholesale re-review of the accepted baseline guard implementation.
- **Original M1 closed:** `InitialModelInputConsumerFixture.java:31` removes the disconnected forbiddenEgress counter; the corresponding assertion is removed from the verifier test. `InitialModelInputConsumerHttpTest.java:90` replaces constant zero reference claims with explicit structural evidence and absence of dynamic provider/tokenizer/measure/SecretVault/response-lease instrumentation. The retained final model-results.json:1 contains the same nonnumeric declaration. Actual engine GET/IAM/SQL observations remain separate. No new dead canary or production instrumentation is introduced.

## Actual retained-evidence checks

No test, build, application, PG, HTTP, native Git, service or background process was started by this reviewer. Checks below read already frozen artifacts.

- `.local/iam-model-input-consumer-review-fix/red-numeric-02.log:70` actually runs the verifier test; :71 reports 1 test/1 failure/0 errors, and :74 identifies accepted numeric1.0. Its XML failure type is AssertionError. Physical verifier source SHA in before, red-numeric-02 and frozen-wide-positive-02 is identical: `8a32d94fc4017ac0d08f2d06df9408a3080fbdc111244573d4513fdb402369cd`. The extra unrelated old HTTP XML in red-numeric-02 was observed and excluded because that command's Running line is verifier-only.
- `red-numeric-token-01.log:70` and :74 show the signed decimal0.00001 assertion failure, 1/1/0/0; `red-wide-number-01.log:70` and :71 show the large-finite assertion failure, also 1/1/0/0. `frozen-wide-positive-02.log:75` and :76 show the same large-finite test passing, and its log records recompilation. The failed frozen-wide-positive-01 is not credited as a prior-behavior control.
- `green-numeric-token-final.log:75` and :77 show actual Running and 16/0/0/0; `integration-numeric-06.log:70` and :71 show actual Running and 3/0/0/0. The corresponding XML counts, test names and failure types were parsed directly. The final integration names include the numeric mutation and genuine producer positive as well as the existing producer negatives.
- `.local/iam-model-input-consumer-review-fix/final-seal/verifier-epoch-equivalence.json:1` was read, then all three relevant source blobs were physically size/hash checked in both named epoch source directories. All ten retained class files (five unit/final pairs) were physically size/hash checked against its entries. These checks confirm the claimed unchanged verifier/fixture sources and five identical classes. They do not turn unit source660 `65b104c574685a75af41b74a35f35fc488489e7b7fa3d2794ae3df2562b21204` into final complete660 `de1ac03e734b8aae7288ecafe553dbc0bcaa7d4d2a82961a6d5f887910d03c4e`, and no 19-test same-epoch/full-suite claim is made.
- The final actual request.json SHA is `0b912ad3aace52e2025a9d4e4f258ae2c64c1b9727cacdbbe5a7efb48761d8b1`. It physically equals both retained manifest.requestSha256 and ready.requestSha256. Numeric-export.json:1 records the same assembled/manifest SHA and a distinct reparsed-display SHA. Only the four numeric spellings and digest comparisons were emitted; no whole request, JWT, key or password was printed.
- Final fixture model-results.json:1 identifies distinct engine48828/model39812 JVMs, first/repeat/new-grant verification and numeric400. Final closed.json:1 records GET8, unexpected bodies0, IAM73, invocation/attempt/SDK-attempt rows0, and sessionsAfterClose0. These are read retained observations, not new live measurements or dynamic provider/SecretVault method counters. Root owns process/classpath/DB/runtime validation.

## Issues

### Critical

None found in the reviewed repair.

### Important

None found in the reviewed repair.

### Minor N1 — Expected negative-path warning remains in successful test output

**Evidence:** `.local/iam-model-input-consumer-review-fix/green-numeric-token-final.log:76` emits Spring WARN with `IAM_TRUST_CHAIN_UNAVAILABLE`; the following line reports all16 tests passed. `docs/evidence/iam-model-input-consumer-review-fix/package-seal/raw.log:108` also retains the verifier deprecation notice, alongside unchanged-module compilation notices. The report discloses output noise at `docs/evidence/iam-model-input-consumer-review-fix-report.md:82`.

The reviewer template requires warnings/noise to be recorded as findings. This expected fail-closed warning is not a functional regression, missing guard or failed test, and does not reopen original M1. For future fixture maintenance, capture/assert the expected negative-path warning and keep unexpected warnings distinguishable. Preserve the frozen logs; no cosmetic rerun or broad compiler cleanup is requested for this repair.

## Named outside-diff checks and limits

Only three focused source risks justified inspecting unchanged code/context: (1) whether raw token construction retained the original parser/exception bounds and single digest-before-GET path (verifier mapper, parse tail and verify digest block); (2) whether numeric classification and immutable deepCopy/capture remain compatible (verifier requestProfile and result accessors); (3) whether common canonicalization reparses/sorts the custom numeric representation or changes array significance (unchanged Json.java). No changed file was reread wholesale, and no adjacent project or old database was accessed.

**Cannot independently establish from this diff/these focused reads:** complete current/source-copy/native-applied660 identity, full compiler/resource/dependency/JAR closure, all566 final class comparisons, all370 JAR entry comparisons, protected8/unchanged655 identity, complete inventories and six-DB/listener/process closure. Root's separate physical audit must establish these; author metadata is not treated as independent proof.

`docs/evidence/iam-model-input-consumer-review-fix-report.md:137` explicitly limits the earlier565 metadata to a later reconstruction with no captured original hash. This review does not call that file an original capture or prove its pre-overwrite identity. The final566 evidence is a separate claimed capture whose input/path independence root must verify. This disclosed historical evidence limitation is not a demonstrated defect in the final source or a reason to rerun unchanged green business tests.

## Assessment and rights return

**Spec compliance: Spec compliant (task-scoped, with the explicit physical-audit limits above). Task quality: Approved. Counts: C0 / I0 / M1.** Numeric spelling now survives the strict consumer boundary without weakening the one-digest binding, and original M1's static-versus-dynamic ambiguity is removed. The remaining Minor concerns disclosed test output noise only.

Only `docs/evidence/review-iam-model-input-consumer-review-fix.md` was written. ALL review report write and execution rights are returned to root. No source, target, configuration, helper, evidence package, control, inventory, DB or runtime was modified; no commit, publication, deletion, child agent or background work occurred. No further reviewer mutation is pending.