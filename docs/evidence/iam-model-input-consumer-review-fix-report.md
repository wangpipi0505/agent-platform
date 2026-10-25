# Private initial model-input consumer I1/M1 repair

Status: implementation frozen pending independent review. No Accepted status, public model enablement, provider frozen-binding acceptance or global completion is asserted.

## Binding inputs and final scope

This is the sole-implementer repair of review C0/I1/M1. The exact original brief and the later root-authorized numeric clarification both apply. The clarification explicitly requires real producer decimal `0.00001`, common scientific `1.0E-5`, whole-valued floats, and the previously accepted finite JSON decimal `1E+400`; a double range restriction is not part of the profile.

| Input | Bytes | SHA256 |
| --- | ---: | --- |
| `docs/evidence/iam-model-input-consumer-review-fix-brief.md` | 9503 | `679bd5b700a5953a777fdd18239c538c23946f879cdd9ed766e0b88fe3687c71` |
| `.local/iam-model-input-consumer-review-fix-numeric-supplement.md` | 2446 | `eaa186fee1d4b7f044eb875fe0311c8348508b268fb6b2b52a01edf3085f717d` |
| `docs/evidence/review-iam-model-input-consumer.md` | 11683 | `ceac49010d1f652fd4064a754931423df439c44198740bd4ff59244180d8af21` |
| `.local/iam-model-input-consumer-review-fix/before/source.json` | 127244 | `09a1d86d1636385620127e39e7f53d650b7a0f0a30689cef3a65b6bebedf6ea0` |

Before the first canonical edit, all 660 current files matched frozen source660 `09a1d86d1636385620127e39e7f53d650b7a0f0a30689cef3a65b6bebedf6ea0`; all complete before blobs were copied into this repair's before/source. Final canonical count is 660: **5 allowed existing files modified, 655 unchanged, 0 additions, 0 deletions**. Backend source/POM enumeration is exact; every listed canonical file is hashed; the eight protected documents match the accepted producer checkpoint's after identities. The final native diff includes every canonical delta.

Only these existing paths changed:

- `engine-runtime/src/test/java/cn/agentplatform/engine/infra/ModelInputConsumerProducerFixture.java`: 21667 B, SHA256 `d5c4783453cdc001a549db6314277468315b3f53e505ffe7cacbd7ab852cdbd4`.
- `model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java`: 23388 B, SHA256 `74407403bebed85708371fb0e16d22c6ee46c6e31ed335ff914d568557b2eb2f`.
- `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerFixture.java`: 10735 B, SHA256 `73de75204ae086553d4c9c0943e9e00740f5a05cd74938a1b73f0e1f8910d56f`.
- `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputConsumerHttpTest.java`: 9856 B, SHA256 `f915dbefaea573eff3c4f741f2f619b4545ea5588459aa2f5adb1f0b4249dbbc`.
- `model-gateway/src/test/java/cn/agentplatform/model/application/InitialModelInputVerifierTest.java`: 23541 B, SHA256 `e2787ecaa3def21aabeb6e1fdced36a965e4bc50694894b5ce9637ac6be25156`.

Transport/DTO/common/producer production, POM/catalog, public controllers, provider/credentials/tokenizer/measure/invocation/attempt/quota/budget/response/default guards, SQL, web and design/contracts are unchanged. All shell cwd values were the sole new project. No subagents, new chats, automation, goals, commits, deploy, restart, main-service operation or deletion occurred.

## I1 mechanism and repair

The frozen local strict parser used BigDecimal float nodes with trailing-zero stripping. A real signed bound request whose common canonical input contains `1.0` was rejected before GET because parsing changed its signed numeric spelling to `1`. Merely removing USE_BIG_DECIMAL_FOR_FLOATS passed that case, but genuine producer testing then proved it rejects the actual producer's exact-decimal canonical `0.00001`: common Double parsing emits `1.0E-5`. Both are legitimate accepted canonical spellings in this codebase. Neither an alternate digest nor a producer/common serializer change can resolve that boundary safely.

The final verifier retains the **original REQUEST/CLAIMS mapper configuration**, including BigInteger, BigDecimal, trailing-token checks and original strict factory bounds. A second pass over the same UTF-8 text uses that same strict lexer to retain floating-point token text at the already parsed tree location. Only lexer-produced VALUE_NUMBER_FLOAT tokens can instantiate the private immutable CanonicalFloat. Its DecimalNode delegate preserves numeric APIs, and its raw serialization preserves that validated numeric token through the actual common Json.canonical -> valueToTree -> TokenBuffer path. writeNumber(String) would be reparsed there; writeRawValue retains the validated numeric value through RawValue. No arbitrary unparsed fragment can enter this production node.

The node retains the 256-character token bound and original finite JSON decimal semantics. NaN/Infinity are rejected by strict JSON lexing. Finite BigDecimal values outside double range remain supported; no provider range was invented. Immutable scalar deepCopy is safe; returned object trees and canonical byte arrays remain defensive copies. Object-key sorting and all other common canonical behavior are unchanged. Verification still computes **one complete canonical request SHA**, compares that one SHA to the original selector, and enforces the original manifest/request/material/scope closure. No alternate hashes, omitted fields or digest relaxation exist.

Purpose/origin, exact current mapping, full initial ceiling, parent closure, owner/root/version, private result lifetime and ambient-transaction guards remain intact and are covered by the verifier class. The final Json.canonical/Digest assertions compare canonical request bytes/SHA, not JsonNode implementation identity. Numeric API checks include number/floating/integral shape, double/asDouble/float/short, decimal, BigInteger, conversion bounds and exact-integral behavior. The accepted request's custom scalar remains numeric; the POJO representation is confined to common canonical serialization's internal valueToTree path.

## Meaningful RED and prior-support controls

- red-numeric-02 ran actual frozen defective production parser bytes with genuine signed retained/bound authority and `1.0`. It produced **1 assertion failure / 0 errors**, explicitly expecting accepted producer numeric input not to throw. red-numeric-01's uncaught PlatformException is retained as an error, not counted as the required assertion RED.
- green-verifier-01 was still defective: an initial edit used the wrong line-ending anchor and did not remove the feature. Its **13 tests / 1 failure** is preserved and not called green. green-verifier-02 passed 13 after the intended initial change, but that was an intermediate candidate.
- integration-numeric-01 and -02 each ran 3 tests with 1 failure and 1 error. First the fixture exported request::text; later it exported genuine assembled canonical bytes. The retained numeric diagnostics prove the scientific spelling mismatch, rather than a missing fixture, fake manifest or setup-only failure. These failed integrations are not credited as passes or as a replacement for the explicit signed assertion RED.
- red-numeric-token-01 produced **1 assertion failure / 0 errors** on producer decimal `0.00001` against the default-double intermediate candidate. The final token mechanism fixes this while preserving common `1.0E-5`, `1.0`, `0.0`, fractions, exponent notation and long decimal precision.
- red-wide-number-01 produced **1 assertion failure / 0 errors** on signed `1E+400` against a temporary double-finiteness restriction. frozen-wide-positive-02 actually recompiled the frozen original verifier production bytes and passed the same signed large-finite test. frozen-wide-positive-01 had reused a newer compiled class because restoring an older file retained an older mtime; its failure is **not** old-behavior evidence. The explicit mtime refresh/recompile in -02 is the valid prior-support control. The final implementation removed the unauthorized double range restriction.

Every source epoch has a complete 660 manifest and full blobs. All Running lines, XML counts, command exits and log identities are tied to the relevant epoch. Earlier extra copied XML in a few pre-fix directories is not credited: final/test-epochs reporting considers only the actual Running classes for each logged command. Compiler-only runs have no Running tests.

## Final verification and exact epochs

**16 verifier tests passed** at green-numeric-token-final, complete source660 SHA `65b104c574685a75af41b74a35f35fc488489e7b7fa3d2794ae3df2562b21204` (0 failures/errors/skips). This includes the signed common float tests, exact producer decimals/precision, `1E+400`, strict non-JSON numbers, duplicate-key/raw-fragment rejection and 257-character tokens, plus the existing authorization/lifetime/transaction tests. Each numeric mutation remains rejected before the fixture GET count can increase.

After that run, **only the HTTP integration test's negative construction changed**. Json.read -> Json.write would also rewrite an unrelated `0.00001` token into `1.0E-5`, making the claimed one-field mutation ambiguous. The final test locates exactly one actual `temperature` token, replaces only its numeric segment, and explicitly compares both unaffected prefix and suffix. No other payload byte is changed.

**3 real PG/HTTP tests passed** at integration-numeric-06, final complete source660 SHA `de1ac03e734b8aae7288ecafe553dbc0bcaa7d4d2a82961a6d5f887910d03c4e` (0 failures/errors/skips). The 16 verifier tests were not rerun for this HTTP-only test change. The verifier production, verifier test and signed fixture sources are identical across these epochs, and **all 5 associated class files are byte-identical** in retained tested targets; final-seal/verifier-epoch-equivalence.json records this. **There is no claim that 19 tests ran on one complete660 epoch or that any final full-project suite passed.**

Commands were invoked through the repair-owned test.ps1/run.mjs, Java21 and Maven offline with own .tools/m2. Exact args and times are retained per command JSON. The verifier command selects InitialModelInputVerifierTest; the final integration runner starts the separate engine JVM and selects InitialModelInputConsumerHttpTest with an explicit fresh fixture. Engine fixture compiles were test-compile with skipTests, not test passes. No unchanged common41 or transport green rerun was performed. Earlier original task's 21-unit/2-integration/41-regression/latest-5 evidence remains immutable at its own epochs.

| Epoch | tests/failures/errors/skips | Maven outcome | Complete660 source SHA256 |
| --- | --- | --- | --- |
| engine-compile-01 | no Running; compile only | SUCCESS | `7991da6c06d5ae9914f451e7d18fc6b073cd6bb0405ee7392b03b86f6707b15d` |
| engine-compile-02 | no Running; compile only | SUCCESS | `abde53bd49b42ffe3481e18c2bc37769121ba767eee84be7eef00a7d6bbd1342` |
| engine-compile-03 | no Running; compile only | SUCCESS | `34b428e27410ac0a77317a7503a29238f0ac33d8db60d555592535a6428e755a` |
| engine-compile-04 | no Running; compile only | SUCCESS | `e2e0cfe8be119f67b596d47ca91db80e261cc68995830691ca2225c08e6cdb4a` |
| frozen-wide-positive-01 | 1/1/0/0 | FAILURE | `6d5f0cbd2a076e05f9e2e9b22c4e628802b854fae2b3ce98687a3494576aaf87` |
| frozen-wide-positive-02 | 1/0/0/0 | SUCCESS | `6d5f0cbd2a076e05f9e2e9b22c4e628802b854fae2b3ce98687a3494576aaf87` |
| green-numeric-token-01 | 14/0/0/0 | SUCCESS | `ae46e898a1b283063a8f5277581f83ad3f92c83fd0f3159439bff12bb4290ca8` |
| green-numeric-token-02 | 16/0/0/0 | SUCCESS | `e2e0cfe8be119f67b596d47ca91db80e261cc68995830691ca2225c08e6cdb4a` |
| green-numeric-token-final | 16/0/0/0 | SUCCESS | `65b104c574685a75af41b74a35f35fc488489e7b7fa3d2794ae3df2562b21204` |
| green-verifier-01 | 13/1/0/0 | FAILURE | `921c3de6c31839aa5344df69b95932f00a5a7da8039dba6ad7a25cdec4aa0fad` |
| green-verifier-02 | 13/0/0/0 | SUCCESS | `7991da6c06d5ae9914f451e7d18fc6b073cd6bb0405ee7392b03b86f6707b15d` |
| integration-numeric-01 | 3/1/1/0 | FAILURE | `7991da6c06d5ae9914f451e7d18fc6b073cd6bb0405ee7392b03b86f6707b15d` |
| integration-numeric-02 | 3/1/1/0 | FAILURE | `abde53bd49b42ffe3481e18c2bc37769121ba767eee84be7eef00a7d6bbd1342` |
| integration-numeric-03 | 3/0/0/0 | SUCCESS | `34b428e27410ac0a77317a7503a29238f0ac33d8db60d555592535a6428e755a` |
| integration-numeric-04 | 3/0/0/0 | SUCCESS | `e2e0cfe8be119f67b596d47ca91db80e261cc68995830691ca2225c08e6cdb4a` |
| integration-numeric-05 | 3/0/0/0 | SUCCESS | `65b104c574685a75af41b74a35f35fc488489e7b7fa3d2794ae3df2562b21204` |
| integration-numeric-06 | 3/0/0/0 | SUCCESS | `de1ac03e734b8aae7288ecafe553dbc0bcaa7d4d2a82961a6d5f887910d03c4e` |
| red-numeric-01 | 1/0/1/0 | FAILURE | `934e853e4e44d05a8772a2fc2e377d0ab70bf4cf4a0bde0dee54a750a884f2c2` |
| red-numeric-02 | 1/1/0/0 | FAILURE | `8b1f5d9effddf67eca276a464bd9cee45d0839f36507bdd4e970a7c437b86e2b` |
| red-numeric-token-01 | 1/1/0/0 | FAILURE | `dc9d618312e0f293594ef0756f375f392b743cf0c199139d1f6ac0ee4878ce65` |
| red-wide-number-01 | 1/1/0/0 | FAILURE | `68b24ca8a8524af51fb18c94f9bd4e520b9128f0fdc080573d56e5f4ecb64c1a` |

The verifier's expected IAM_TRUST_CHAIN_UNAVAILABLE negative emits an expected Spring context warning. Compilation retains existing deprecation/unchecked notices. These are disclosed, not represented as entirely warning-free output.

## Genuine producer, HTTP and numeric export

The engine fixture still uses actual createPublished, original prepareInitial, PostgreSQL immutable facts, the existing real controller/security and signed delegated metadata authorization. Numeric setup supplies temperature1.0, frequencyPenalty0.0, topP0.25 and presencePenalty1e-5 in the frozen snapshot. The actual producer's locked EXACT source parser emits whole-valued canonical output **1/0**, not literal1.0/0.0; this is reported honestly. Whole-float literal canonical support is established by the signed common-compatible unit positives. Genuine producer output includes ordinary fractional0.25 and exact-decimal0.00001.

For the request export the fixture obtains the real RunAuthority.currentRetained proof, enters the actual transaction, calls unchanged ModelInputMaterialStore.lockInitial and ModelInputMaterials.assemble over actual locked frozen inputs, and exports **that actual assembled canonical byte string**. It hard-asserts its SHA equals the actual prepareInitial manifest.requestSha256. The selector/manifest is never rewritten, and no fake proof factory, synthetic signed manifest or reflection is used for this integration positive. The persisted jsonb display remains separately retained as persisted-request.json. Numeric-export/diagnostic evidence retains both actual producer SHA and the different common-reparsed display SHA, explaining the outbound representation risk for future real send work.

Final live engine JVM PID **48828**, model JVM PID **39812**, distinct and both closed. Actual final engine measurements: **8 metadata GETs**, **0 unexpected GET bodies**, **73 authority calls**, **48 captured post-ready SQL statements** (includes fixture closure measurement queries). Positive first/repeat/new-grant reads and existing wrong owner/root/origin, missing manifest and legacy-scope negatives execute. The final single-temperature mutation returns400 before metadata GET; total GET remains8 for the 3 genuine reads and 5 existing producer/transport negatives.

Measured own PG facts at closure: model invocation rows **0**, attempt rows **0**, summed SDK attempts **0**, model-input rows **2**, sessions **0**. These real SQL counts are separate from structural provider evidence. No model datasource or engine PG client is constructed. The model child environment has engine PG credentials/connection variables removed; the shared ready metadata still contains the database name as an identity label, so this is not described as receiving no PG-related information whatsoever.

The model uses the existing actual public testClientSecret accessor and the approved public WorkloadCredentialResolver constructor assembly with identical service/client/secret-ref. HTTPS-shaped fixture.invalid endpoint fields validate only that resolver constructor and are never contacted. Actual IAM is **signed HTTP loopback with explicit testProfile**; actual engine manifest transport is **normal CA/SAN HTTPS**, distinct facts. TLS assets were reused read-only. Fresh fixture directories were restricted with icacls before credential/config/ticket files were written. No key, password, JWT or whole request payload was printed.

## M1 structural cleanup

The disconnected forbiddenEgress AtomicInteger and its zero assertion are removed. Literal provider/tokenizer/measure/SecretVault/invocation/quota/attempt and response-lease reference-zero output fields are removed. model-results instead states in words that this is structural collaborator evidence and that dynamic provider/tokenizer/measure/SecretVault/response-lease instrumentation does not exist.

Actual source inspection of the three private consumer production types and final class identity show only the concrete authority client and bounded metadata HTTP client as collaborators; forbidden business/provider/store/tokenizer/SecretVault dependencies are absent. This is **static closure evidence**, not dynamic method counters. No new production instrumentation or dead canary was installed. Real engine GET/IAM/SQL observations remain separate and intact. No broad business rerun was done just for cleanup.

## Exact source, native, compiler and package closure

The final exact-source copy package-work-seal contains all final660 blobs. Maven package selected common+engine+model and -am with skipTests, exited0 after real compilation/resource processing and JAR production. It uses a fresh owned copy and never retries the known original-target rename problem. Final compiler input lists match the exact canonical main/test Java rosters for all 3 affected modules (6 lists per active/package set). All declared created class files and copied canonical resources are checked and retained: **611 active finite entries + 617 package finite entries =1228**. Existing active-tree JARs included in finite inventory are historical/nonexecuted artifacts; final JAR proof uses the fresh package-work-seal products. Actual runtime dependency JARs are separately hashed in classpath manifests.

The final native binary-capable 660->660 diff is from actual git diff --no-index --binary --no-renames --no-ext-diff --no-textconv (status1), followed by fresh scratch git init, git apply --check -p2 and git apply -p2 (all status0). All660 applied files are byte-identical to the final source manifest. No main Git operation or commit occurred.

Read-only final ASM/class inspection compared **566 tested/package classes: 566 exact byte matches, 0 debug-only differences**. Each of **370** fresh package JAR class/resource entries equals its corresponding compiled tree entry. Real Running/XML classpaths, Java home/version/command properties, engine launch argv and process IDs are retained, alongside exact source and target identities.

| Final artifact | Bytes | SHA256 |
| --- | ---: | --- |
| `.local/iam-model-input-consumer-review-fix/final-seal/source.json` | 127244 | `de1ac03e734b8aae7288ecafe553dbc0bcaa7d4d2a82961a6d5f887910d03c4e` |
| `.local/iam-model-input-consumer-review-fix/final-seal/source-660-to-660.patch` | 24889 | `b45bb9d391274e17d52979ce416216a9d42cb7b3b0fb854c9bf09aa57a504f29` |
| `.local/iam-model-input-consumer-review-fix/final-seal/native.json` | 3358 | `53a82e63151f60b964b56521906aca5ec570b95815c23d9847d7d8fe5bfd7f1c` |
| `.local/iam-model-input-consumer-review-fix/final-seal/task-delta.json` | 1250 | `661d504f960e393e7e664043faf9785a269bcc1182f5361997123c0ee2126123` |
| `.local/iam-model-input-consumer-review-fix/final-seal/source-equality.json` | 1140 | `ed833ddf0dcfe58f20fc754dd5095318238e614bd34cfe79a0adac600e8a4ad1` |
| `.local/iam-model-input-consumer-review-fix/final-seal/tested-finite.json` | 332001 | `02c4eee26b2d305aaf243e4d0e6e90fc2ebaf8b895daae6a73fb4d30a434be5b` |
| `.local/iam-model-input-consumer-review-fix/final-seal/package-finite.json` | 379208 | `066e9c1bfc26083b8a7520e16e10e6b8c128adfefc72d34d1f411067b0c53297` |
| `.local/iam-model-input-consumer-review-fix/final-seal/tested-compiler-inputs.json` | 2362 | `85b2c05cdae913d481dab31f28aaa38f1de915b68b43ee836f19ad1e36f1f493` |
| `.local/iam-model-input-consumer-review-fix/final-seal/package-compiler-inputs.json` | 2736 | `542096a2558d7d202a10932dd342ad5cf1494b1232a33155b2152639be4f401d` |
| `.local/iam-model-input-consumer-review-fix/final-seal/bytecode-equivalence.json` | 181742 | `2efaa48972cfa4a34e6fd06eb794d6b943957524e143e2adad757ca410899934` |
| `.local/iam-model-input-consumer-review-fix/final-seal/verifier-epoch-equivalence.json` | 5278 | `196eeef9025e688cb336508eacaff135e5b2bcc720e790f15c0c964db753a77d` |
| `.local/iam-model-input-consumer-review-fix/final-seal/test-epochs.json` | 23698 | `7af085cf6830e20f02c2a271b32a55110a5b57e367b2b746abff6d6a175591b5` |
| `.local/iam-model-input-consumer-review-fix/final-seal/process-classpath-inputs.json` | 103295 | `ae03348e9ca31f75693fa1a5606f6acc3c425669a255d346387949c97388ce8e` |
| `.local/iam-model-input-consumer-review-fix/final-seal/protected-eight.json` | 1811 | `011cff68a91a54a3ec48f92d9f5485d0ae7c7a6c309e308408564211fa74ae96` |
| `.local/iam-model-input-consumer-review-fix/final-seal/static-boundaries.json` | 1271 | `08de159bcd1e1b9158de21fb041feec4f2a7ff68ab1e7b8041fd342f9b5a5d8c` |
| `docs/evidence/iam-model-input-consumer-review-fix/package-seal/command.json` | 751 | `b6ec1ea36a2105dcdb3f2b3c315a34a84769d7b2d2c50a84dbda173715b3773a` |
| `docs/evidence/iam-model-input-consumer-review-fix/package-seal/exit.json` | 149 | `ff0cdc360a19312ba5d9d743a50ca5a9c9e157480b7ff70233b84734ce6b8ced` |
| `.local/iam-model-input-consumer-review-fix/databases.jsonl` | 984 | `59d8fec9020908580f986e78fa7cbe9b3f5232c9d756aa7c9d6acf19a436f6b9` |
| `.local/iam-model-input-consumer-review-fix/database-audit.json` | 27754 | `e081e2a4e415f165091a8ea42f09fbf787b310cd86aa5f9de85652544357bf3c` |
| `.local/iam-model-input-consumer-review-fix/runtime-exit.json` | 2137 | `fb5478e4d0251b888703b1b339321884c6a17cb88e054c6ffd9043474a8c284c` |

## Retained intermediate evidence and metadata correction limits

All source snapshots, all meaningful RED/error/setup logs and all 6 fixture directories are retained. Initial intermediate package-work/native/final and later package-work-token/native-token/final-token remain separate from final package-work-seal/native-seal/final-seal. No previous task path under .local/iam-model-input-consumer/ or docs/evidence/iam-model-input-consumer/ was written; original task reports/control/inventory/helpers were not edited.

One repair-owned metadata path error affected **only .local/iam-model-input-consumer-review-fix/final/bytecode-equivalence.json**: the first token Inspect helper retained that earlier intermediate output path and wrote the actual566-class result there. That actual result was copied intact into this repair's final-token directory before correction. The earlier565 metadata's original standalone hash was **not captured before overwrite**. The current565 file at the intermediate final path is therefore labeled **later deterministic reconstruction from the preserved earlier own tested-target/package-work**, not original capture and not proven identical to the overwritten original hash. The associated path-correction command and log are retained; an initial direct PowerShell JVM-option quoting failure is a metadata setup failure, not a test failure. Final final-seal566 evidence was then produced independently by the corrected helper directly at final-seal, using the final tested classes and fresh final package. It has no dependence on reconstructed565 metadata.

The final collector also encountered an audit-log filename with no test command JSON; its test-epoch scan was narrowed to actual command+source epoch pairs, and metadata collection then completed without rerunning business tests. Original failed test epochs remain included. Current root database/runtime summaries cover all6 registered resources; the earlier5-resource audit/runtime capture is separately retained under final-token. No old task evidence was reconstructed, overwritten or re-audited.

## Ownership, runtime exit and handoff

The registry has **6** distinct own names, each matching iam_model_input_consumer_fix_<32hex>, each checked absent in pg_database and absent in this own registry before its explicit owner record and CREATE. The final auditor validates those exact owner/prefix records and connects only to those own databases plus the allowed administrative database. All6 retain engine migrations through218 and immutable triggers enabled; all registry-only session-before/session-after counts are0. No old two consumer databases or prior producer/other fixture database was connected/reset. Databases are retained for review; none was dropped.

- `iam_model_input_consumer_fix_11e8d7400eec45a6a8c445ea8acb83cc`: sessions before/after0; immutable triggers enabled.
- `iam_model_input_consumer_fix_cb8179c9fb5d4c4ca5341c69a6b66e49`: sessions before/after0; immutable triggers enabled.
- `iam_model_input_consumer_fix_74474af189ac4f51816956a851d11641`: sessions before/after0; immutable triggers enabled.
- `iam_model_input_consumer_fix_07dbe70a43054910a553bb98310c2c7c`: sessions before/after0; immutable triggers enabled.
- `iam_model_input_consumer_fix_9af399c8f1a743eaa24af7ee2e53c41b`: sessions before/after0; immutable triggers enabled.
- `iam_model_input_consumer_fix_9d49c93190844fcd9d58c27dbd570a8c`: sessions before/after0; immutable triggers enabled.

runtime-exit.json reports **0 matching task processes, 0 observed PIDs alive, 0 owned listeners remaining**. Every fixture engine/model process exited and HTTP/TLS/IAM workers were closed; no background work remains. Main ports15173-15175/18080-18085/PG25432/Redis26379/MinIO29000-29001 were observed only and not operated. The exact final report identity is referenced by the last-written control/inventory seal. This report does not mark global T09/T11/T12 complete or start T33; T01 remains the only globally complete task under the binding brief.

ALL canonical/owned source, target, DB, HTTP, helper, owned workcopy, evidence, report and runtime write/execution rights return to root on the final seal. Author will perform no mutation after that seal. Independent review remains required.
