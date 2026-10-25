# O1c gateway read-only status — implementer handoff

Status: DONE, implementation and focused verification complete; pending root finite audit and independent review. No commit, package, deployment, automation or subagent. Source/target/backend-class write ownership is returned with the final seal. This is task-local evidence: T01 remains the sole globally completed task; it is not global Task09/G6E/F or 17-function/34-task/30-acceptance approval.

## Authorized scope and baseline

Every shell used `G:/207/项目/算法重组工具/代码/agent-platform` explicitly. No adjacent implementation, configuration, storage, cache or process was read/reused/modified. The accepted v1.2 design, four contracts, POMs, migrations, production configuration, engine production, selectors/catalog, old published proxy/metadata client, global Json/error handling and application main remain unchanged.

The formal [brief](iam-stage-4-o1c-gateway-status-brief.md), final interface notes, Portal verifier supplement and root seam approval govern this implementation. Root also explicitly approved the [original POST classpath supplement](iam-stage-4-o1c-regression-classpath-supplement.md) and [trailing-empty-entry decision](iam-stage-4-o1c-regression-classpath-tail-note.md). The approved design was already finalized; no repeat human scope/test approval was requested. TDD, Java/Spring, Ponytail, systematic debugging and verification instructions were applied with the SDD implementer template; user prohibition on commits supersedes its generic commit step.

Before source edits, all **579** necessary baseline rows matched accepted O1b fix `source-after.json` byte-for-byte and by SHA-256. Six originals were preserved. O1b latest overlay is `.local/iam-o1b-review-fixes/final01/source/`, followed by original O1b/O1a/D4a/I1/N4b2 lineage. No invented old overlay or old target-tree scan was used.

Final necessary source is **585 = backend393 + frontend192**: six modified files and six additions, no removal. Exactly eight production files and four new test/fixture files changed. See [source delta](iam-stage-4-o1c-gateway-status/source-delta.json), [source manifest](iam-stage-4-o1c-gateway-status/source-after.json) and [composition](iam-stage-4-o1c-gateway-status/composition.json).

## Implemented behavior

`GatewayIngressBoundary` has an explicit STATUS installation flag. Both old constructors default it off. Only actual REQUEST/GET `/admin/runs/r_[0-9a-f]{32}`, with no query including literal bare `?`, body or identity/control header pollution, can obtain its private STATUS capture. Actual MVC controller/method, original request wrapper anchor, current proof attribute, owning boundary/thread, one claim and lifecycle are checked. `requireLive` checks the claimed capture independently of the old unclaimed proof check, including after actual output-stream acquisition. Source expiry uses wall and monotonic facts and yields a dedicated `PORTAL_TOKEN_INVALID`401. Typed verifier/JWK dependency503 is kept separate. Existing RUN/SESSION branches remain.

`CurrentAuthorityClient` adds two fixed default-closed methods. `HttpCurrentAuthorityClient` privately mints separate query/status proof types only from real signed Portal exchanges. They retain owner, original source binding, subject, origin authentication, exact operation/resource and independent grant/operation identities. Source expiry is independent of current/ticket/grant/operation expiry; local internal expiry or owner closure is safe503. Failures latch. Internal authority/service-ticket/JWK exceptions cannot reach the old ingress broad JwtException→401 catch. There is no DTO/Actor/VA/Runnable proof factory and no gateway-target online authorize of an engine ticket.

`RunObservationMetadataClient` owns no transport. It borrows the dedicated transport and implements fixed engine Q HTTP, full wire8192/data4096 separation, exact seven fields, positive long versions, bounded exact owner/root/run/hash relations and a privately constructed facts result. Q failures stop before M. The facts do not authorize themselves: the test registry is populated from trusted stored admission binding, never from browser DTO or metadata response.

`RunStatusProxy` performs original Portal → independent Q → actual engine metadata HTTP → independent M for `run-snapshot/run/acceptedSHA` → actual engine status HTTP. Its single five-second monotonic operation deadline never resets; each engine HTTP hop is at most two seconds and the remaining total. Local capture/proof/deadline checks run after connection before actual send, during/after reading, and after actual `getOutputStream`. It validates one complete strict successful envelope/run before any success business byte, then forwards the original UTF-8 bytes. Big integer/decimal spelling, Chinese, escapes, nulls and complete fields remain unchanged. The limit covers raw response bytes; byte-array copies, decoded text and JSON trees are additional memory, not a total two-MiB heap guarantee.

The new fixed observation GET policy in `PublishedRunHttp` shares existing Apache mechanical transport while leaving old GET/POST policy intact. It keeps no retry/redirect/auth challenge/cookies/compression, fixed generated paths/origins, actual cancellation, 64 initial headers, 8192 header/chunk lines and first-trailer rejection. Strict known403/404 status/code/error-envelope combinations retain safe owned messages; internal401, internal400, Q metadata-size denial, malformed/unknown statuses and unsafe wire are safe503. It never forwards `WWW-Authenticate` or upstream error text. The source does not set a successful Content-Length; Tomcat may accurately compute a fresh local length after a small complete buffered body.

Incremental observation overflow or proof/read failure cancels the installed request **before** input close. Core chunk stream close drains unread frames; cancel-before-close avoids waiting for a stalled peer until the hop timer. This branch is observation-only. Partial browser write preserves the primary IOException, best-effort closes, logs committed/attempted/actual-unknown, never retries or appends a second JSON, and does not claim physical hard-close of a blocked peer or a continuous revocation SLA inside one blocked write.

`GatewayIamAssembly` remains an explicit-import finite assembly without a stereotype. `ObjectProvider<RunStatusProxy>` enables STATUS only when that actual bean exists. Controller optional status injection preserves the old four-argument constructor and fixed proxy signature. Only the new test Receiver explicitly constructs borrowed Q/status clients and one dedicated HTTP bean with a unique close owner. No normal production Boot IAM readiness is claimed.

## Real fixture and observations

The new engine fixture runs in a separate JVM with fresh own-module classes/test-classes and admin-common outputs; dependency-only `.tools/m2` entries are bounded and validated. The gateway test classpath contains neither engine nor app business classes/JARs. Only private finite stdin/stdout management, trusted stored owner/run/SHA facts and actual HTTP cross that boundary. A separate database, `agent_platform_iam_o1c_gateway_status_v12`, has migrations200–217 (18). No scheduler start, worker wake or provider call is used for observation.

The seed uses production `RunAuthorityStore.createPublished`, `createNode` and material event writers, valid FLOW edges `from/to`, and actual finite-flow plus real admission DAG/input gates with a before-persistence sentinel and wrong-edge control. It is explicitly a **validated production storage seam**, not public IAM run-admission-chain success. Setup execution/writer authority calls are recorded separately before the observation reset. After clearing finite grants, observation registry is rebuilt from stored binding; no original execution grant/root/history is resumed by the Q/M chain. Oversize fixture material is written during initial valid seed authority, before that reset.

Real normal observation records exactly `exchange, authorize, authorize, exchange, authorize, authorize`: gateway exchanges are independent; engine guard/refresh account for the four authorize calls. There is no extra gateway authorize. Q reads control metadata/manifests with payload0; M payload reads share one actual read-only RR connection. Run/node/attempt/event/tool facts and workers/provider counts remain unchanged during observation. On the final positive with Chinese/escape/numeric fixture, browser and actual engine output bytes are identical (867 bytes in that run), including `123456789012345678901234567890`, `1.2345678901234567890123456789` and null.

Real actual-stream windows prove capture invalidation, proof-attribute replacement, internal expiry, original-source expiry and owner-client closure release no success business bytes. A real engine online current denial retains403/SYS-AUTH-403; actual engine stream acquisition followed by its own six-second clock advance produces403/AUTHORITY_EXPIRED, retained at the gateway. These are distinguished from separately labelled injected HTTP error-envelope combinations.

Q encoding and wire-budget tests use exact legal fields, a long version4294967297, data503/base envelope552 bytes in the recorded case, plus valid trailing whitespace to wire8192. Wire8193 stops before M. The exact-field bounds make a legal data4096 extreme unreachable; no illegal DTO was fabricated. M exact2097152 wire bytes pass, +1 is safe503, while real engine oversize is distinct403/RUN_STATUS_TOO_LARGE without observation execution writes.

Q, M-success and retained-error paths each actually reject UTF-16 with BOM, UTF-16 without BOM, UTF-32 and malformed UTF-8. Duplicate/trailing/empty/non-object/wrong-run JSON, missing/extra fields and long overflow stop protected output. Raw transport cases cover chunked multi-header success, byte limits, 401/challenges/redirects/no retry, unknown errors, unsafe headers, trailers, long header/chunk lines, truncated body, timeout/cancel, actual connect-before-send denial and owned closure.

## TDD and command evidence

The unchanged runner was always `node .tools/run-iam-module-tests.mjs <stage> <modules> "<selectors>"`, Java21/offline `.tools/m2`, comma/# selectors and unique stages. Every command has a complete pre-source manifest. Actual Running-class XML was copied immediately; no copied older XML is counted as executed. [Test verification](iam-stage-4-o1c-gateway-status/test-verification.json) maps **31 distinct new methods** (common9, chain14, wire8) to their latest actual passing execution, plus the three named old regressions. These are results across distinct commands, **not one combined suite**.

| Stage suffix | Actual class result | Classification |
|---|---|---|
| red01 | chain1/F1/E0 | Fixture bean initialization failure; not behavior RED. |
| red02 | chain1/F1/E0 | Meaningful closed real gateway: expected200, actual403; Q/M/engine observations0. |
| chain01 | chain1/F1/E0 | Actual200 chain; overstrict absence-of-Content-Length oracle, corrected to accurate local length. |
| common-wire01 | common8/0; wire6/0 | Focused initial proof and real transport checks. |
| chain02 | chain12/F3/E3 | Incorrect outer-facade close hook; late fixture writer correctly denied after grant reset; child failure caused later setup errors. All original failures retained. |
| utf8-red01 | chain1/F1; wire1/F1 | Meaningful UTF16 wrong200/wrong retained404, expected503. |
| chain-wire03 | chain12/F1; wire7/0 | All except JDK-normalized bare-query test passed. |
| ingress04 | chain1/F1 | JDK also stripped Proxy-Authorization; literal socket evidence added, no production weakening. |
| gap05 | common1/0; chain2/0; wire1/0 | Wrong signed purpose/source, explicit Q/M/error encoding, Q8192/+1, literal bare?/Proxy-Authorization and ingress rejection. |
| positive-session06 | chain1/0; session2/0 | Current Chinese/escaped/numeric raw positive and both named SESSION regressions. |
| portal-code-red07 | chain1/F1 | Explicit source401 inherited wrong SYS-AUTH-403 code; expected dedicated Portal code. |
| errors-green08 | chain2/0 | Dedicated Portal code and verifier503; actual engine current/expiry403. |
| post-regression09 | original POST1/0 | The exact named published metadata/main run/PG/owned FLOW/status/replay regression. |
| overflow-red10 | wire1/F1 | Actual stalled chunk: 2044ms versus required under1s; one request. |
| overflow-green11 | chain1/0; wire3/0 | Abort43ms, one request/one cancellation; valid chunk and exact/+1 controls; actual partial-I/O WARN. |

For the primary missing-behavior RED:

```text
node .tools/run-iam-module-tests.mjs iam-stage-o1c-red02 admin-common,engine-runtime,platform-gateway "GatewayRunStatusChainPostgresTest#realGatewayQueryAndStatusReturnOriginalBytes"
expected: 200; actual: 403
metadata/status/dependency/worker/authority observation counts: 0
```

Its final positive is `iam-stage-o1c-positive-session06`, selecting that same method plus `PortalSessionHttpTest#normalLegacyStartsWithoutIamClientAndRunOnlyFiniteAssemblyKeepsOriginalBoundary` and `#installedExactHandlerReturnsOnlyCurrentSessionWithNoBusinessCalls`: actual chain1/0 and session2/0. Full exact selectors/modules and copied XML are indexed in the test verification and command directories.

The overflow green selects `RunObservationGetTransportTest#overflowAbortsBeforeClosingUnreadChunkStream`, `#realChunkedMultiHeaderResponseUsesOnlyFixedPathAndOneTicket`, `#metadataAndStatusActualReadLimitsAcceptExactAndRejectPlusOne`, and `GatewayRunStatusChainPostgresTest#partialOutputNeverAppendsOrRetries`. It records WARN `committed=true, attemptedBytes=867, actualBytes=unknown`; the actual peer received a 128-byte business prefix/311 total raw HTTP bytes and EOF. The injected write exception/close suppression are expected finite failures, not hidden warnings. Inherited compiler deprecation/unchecked/encoding output is retained verbatim, not described as pristine.

Separate compilation/classpath preparation logs preserve the initial missing Maven test-jar dependency failure and a completed reactor compilation followed by helper validation failure; cached completed output was reused for unfinished metadata steps. `compile02` corrected fixture bean dependencies; later compilation prepared actual new fixture revisions before the gateway-first reactor could run them. No wrong setup/compile result is counted as behavior RED.

## Original POST classpath and PG isolation

Fresh O1c app and engine dependency lists were generated independently, without modifying the two original hardcoded locator files or existing tests. App115 entries are ordered-equal. Engine134 nonempty entries are ordered-equal, but the old locator has a trailing empty entry/current-ROOT fallback; literal CP equality is **not** claimed. Exact ROOT `cn/agentplatform` is absent. Own Java21 `Class.forName(...,false,loader)` and resource-byte probes separately checked seven classes per old/fresh module CP: first origin and SHA match current own-module/admin-common classes or the current target test JAR. No two business modules were combined or initialized. See `.local/iam-o1c-gateway-status/regression-classpath-verification.json` and the retained initial difference comparison.

Only then the exact old POST method ran once. Dedicated `agent_platform_iam_admission_app_v12`/`...engine_v12` were used with the new UUID tenant. Before/after read-only RR row-hash snapshots across28 tables prove all3846 previous rows unchanged; migration histories are unchanged. Added rows are the new fixture's app/version/run/node/attempt/material/event records. No table/database was truncated, old row cleared, PG service stopped or unrelated process touched. Both owned old fixture JVMs exited0, and the parent test completed. These are this independent project's approved test databases, not adjacent old implementation storage.

## Sealing, self-review and limits

Self-review confirmed source scope, no capability factory/cross-service Java use, exact purpose/source relation, typed errors, independent lifetimes, raw numeric/UTF-8 behavior, full read-before-send, actual stream recheck and resource ownership. It found and fixed UTF autodetect, dedicated new-STATUS Portal error code and drain-before-cancel, each with a genuine failing behavior test. Test-only errors and oracle changes are distinguished above. No known required implementation work remains; independent review is still required.

Final target is **2005 logical rows**: inherited1972 plus33 additions, with64 actual changed/new rows from524 named compiler/report candidates. The target manifest inherits old rows and uses compiler createdFiles/status, actual test JAR and actual Running report outputs; it does not walk old target inventories. Exact frozen source12 and target64 overlays are under the evidence folder and own `final/` mirror. See [target delta](iam-stage-4-o1c-gateway-status/target-delta.json).

The actual private Git diff uses an own bare repository, explicit EOL attributes `* -text`, no commits, and explicit own `--directory=.local/iam-o1c-gateway-status/replay-source`. First check/apply are **0/0**; all585 logical names/bytes/SHA match the final source. The replay starts with verified accepted baseline579; final bytes were copied only into the diff work tree, never into replay. A private init argument error128 occurred before check/apply, was retained, and only unfinished init02 onward continued; green tests or successful apply were not repeated for bookkeeping. See [replay verification](iam-stage-4-o1c-gateway-status/replay-verification.json).

Resource records cover actual owned child exit/close facts, including explicitly failed fixture children, without a physical blocked-peer hard-close claim. [Upstream reading](iam-stage-4-o1c-gateway-status/upstream-reading.json) records only the narrow actually read fixed Apache client5.4.4/core5.3.4 source sections. No whole-library audit is claimed. Old JDK authority HTTP hard cancellation, online instantaneous revocation, blocked-write physical close, full production IAM Boot and real external IAM/model/ontology/Kubernetes acceptance remain outside this slice. Isolated endpoints were explicitly authorized and fully sufficient for this implementation; no external environment was requested again.

The final inventory/handoff/seal contain exact hashes and exclusions. After that seal, source, target, backend classes, own local/evidence/report are frozen and all write ownership returns to root. Root owns execution indexes, review and acceptance updates.
