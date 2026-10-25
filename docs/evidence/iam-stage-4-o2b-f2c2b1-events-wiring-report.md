# C2b1 exact EVENTS ingress and explicit production wiring — implementation handoff

Status: implemented and frozen for fresh task review/root audit; **not Accepted**. Scope is the independent v1.2 platform at `G:/207/项目/算法重组工具/代码/agent-platform`. B1 verifies the actual gateway component with isolated issuer/private TLS peers. It does not verify a real production E encoder + new PG + issuer joined chain, the default GatewayApplication IAM launcher, external IAM, deployment or all17/34/30 tasks.

## Changes and authorization

Exactly four existing sources changed and four new sources were added, as listed by the frozen input. Native source630 →634; all other626 paths retain their exact names/bytes/SHA. No contract/design/eight-index/POM/main/Http/Budget/TLS/engine/frontend/protected-test changes, no commit/deploy/main restart, and no old-project access.

`GatewayIngressBoundary` adds a private EVENTS capability for IAM/GET/original REQUEST/empty context/canonical `/admin/runs/r_[0-9a-f]{32}/events`. Availability comes from an actual RunEventsProxy bean. Identity comes only from real TrustedJwtDecoder signature verification. Cookie/API-key/identity headers/body/Transfer-Encoding are rejected. Only this purpose accepts one Last-Event-ID header OR exactly one literal Last-Event-ID query. Canonical ASCII0..Long.MAX_VALUE is parsed once; absent means0. Other purposes keep their original no-query rules and compatibility constructors.

preHandle captures the exact request/response and exact current WebAsyncManager after the owned filter/actual Controller handler checks. Spring6.2.7 creates LifecycleHttpServletResponse later in RequestMappingHandlerAdapter. The authorized seam binds only the same manager's exact StandardServletAsyncWebRequest, exact native Lifecycle response, exact request and direct preHandle response delegate. Foreign manager/current/native response, arbitrary wrapper and subclass are rejected. Subsequent requireLive binds that exact pair/thread/route/claim/expiry/source budget. clear/afterConcurrent/afterCompletion cut all five new reverse references. No proof is carried into async State/Session/Handle/callbacks.

Controller explicitly branches EVENTS before independent STATUS/RUN. The small application proxy only invokes typed SSE bootstrap and maps pre-async errors. SSE validates the capture then the existing original controlled request/thread/processor/socket/token gates, and bounds public wrapper chains to the actual Catalina facades. The real supplied MVC response obtains the stream/listener/writes. The raw entry is package scoped and still demands exact facades; it keeps throwing behavior. Shared1536/1792B/3MiB and existing lifetime/exit/fee gates are unchanged. The typed post-async branch retains response ownership and attempts existing stop/complete/close in finally order even on cleanup Runtime/Error, logs only a finite constant, and returns the existing Handle as ownership rather than business success. Local asyncOwned records only a successfully returned startAsync and is not a permit/State field.

GatewayPortalEventsConfiguration is explicitly imported, with actual production Sse/Http/RunEventsProxy beans and a Boot factory customizer installing ControlledProtocol by public setProtocol plus the same-context Valve. It has no scanning stereotype. Existing actual TLS/CurrentAuthorityClient/trusted decoder dependencies must be unique and installed. Real Boot succeeded with final1024 connections/64 threads/64 cache/internal executor/HTTP1.1 and rejected actual wrong thread/connection/cache/HTTP2/custom-executor overrides, each in a fresh JVM. No per-request TLS clone, new registry, history, reaper, executor or timer was added; the finite shell model is in `component-allocation-model.json`, without a whole-JVM/RSS/native-capacity claim.

Primary frozen brief/input remain unchanged. Three incremental root decisions:

- `.local/iam-o2b-f2c2b1-typed-postasync-error-authorization.md`: 1981B / `952d703b078bad93fa16c9785928f7769c4d4754226209a5aa264ad1cfd1a899`
- `.local/iam-o2b-f2c2b1-spring-response-binding-amendment.md`: 3601B / `b1026b127ac8c1eb0f05d81384307e6894c64a3dbe4e7a84f279b318fd30a397`
- `.local/iam-o2b-f2c2b1-async-owned-race-authorization.md`: 2002B / `7fb95631df3610e0b45c2b39c39ad57e359be3a51f89ce746d9a707737e973df`

## Failure-first evidence and bounded verification

The native630 before copy was verified before adding the test. `red-old-api` uses only then-existing public API and real Boot Controller/Assembly/Security/JWT: actual EVENTS GET403 vs expected200, one assertion failure, zero errors. It is a behavioral missing-route RED, not a new-API compilation failure.

All Maven stages ran from the sole workspace via `.tools/iam-o2b-f2c2b1-events-wiring-run.py`, using Java21 and offline fixed local dependencies. Base command: `mvn.cmd -o -B -ntp -pl platform-gateway -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dtest=<selector> -Dsurefire.failIfNoSpecifiedTests=false`. Actual args, process waitFor result, source epoch, Running lines/XML, diagnostics and finite compiler artifacts are cached separately for every stage. The final narrow stage additionally uses `-Dc2b1.groups=postasync,postasync-cleanup,postasync-cleanup-error,q403,qtransport,no-proxy`; no broad suite/PG/provider/old C2a matrices/GC ran.

| Actual stage | Process exit | Actual XML suites | Actual source epoch SHA256 |
|---|---:|---|---|
| red-old-api | 1 | GatewayPortalEventsServletTest:1 tests/1 failures/0 errors | `04ba8c10417eaec2a22e302fb199396f73a40e9d355e1389a8e5891b4aaf782c` |
| green-first | 1 | GatewayEventsIngressBoundaryTest:2 tests/0 failures/0 errors; GatewayIngressBoundaryTest:3 tests/0 failures/0 errors | `cb6726c13c5041dc094a8219f00437a9ae6ea5e503eb778d2226fcd825eb6b6f` |
| green-gateway | 1 | GatewayPortalEventsServletTest:4 tests/1 failures/1 errors | `88688397bcbabf20ec506aac95285b70695f7fc141591165ecb0b56e9890e837` |
| green-framework-binding | 1 | GatewayPortalEventsServletTest:2 tests/1 failures/0 errors | `2e85439e944043a5c0e3b485dc09917955634914c86aa942f65a076f1a6badfe` |
| postasync-diagnostic | 1 | GatewayPortalEventsServletTest:1 tests/1 failures/0 errors | `201f7a53481c83f99192b28464fd1f57973e7d87af27cc7d19b01a54e7504193` |
| postasync-owned-fact | 1 | GatewayPortalEventsServletTest:1 tests/1 failures/0 errors | `2e7e478f87715bd422820a997c4c7e73a7dfc6eb6b90b9151327bf2d551c9a31` |
| postasync-branch-diagnostic | 1 | GatewayPortalEventsServletTest:1 tests/1 failures/0 errors | `c7c5c62033e37ed9cdda929a862fd17059a599909337308dbc84817345d509fd` |
| green-single-request-postasync | 0 | GatewayPortalEventsServletTest:1 tests/0 failures/0 errors | `c91a517192cb8caecf84da5726640d90c759793065dce9cbe0e94291a737d74d` |

Coverage is aggregated from its actual epochs, never presented as one final full-suite pass. New Common2 and old Common3 passed in green-first; that stage subsequently failed only on Gateway test's ambiguous DispatcherType import, corrected in the owned test. green-gateway's five real startup refusal children, direct boundary child and raw sequence child exited0. The raw child executed the existing module helper `scenario("sequence", ownPath)` for originalRequestBootstrapAndContinuousCursorTerminal; it did not run the old hardcoded-root parent JUnit. Its parent metadata decoding then errored on local console bytes; that child remains exit0 and was not rerun. Decoder was corrected to replacement UTF-8 decoding.

The first actual MVC default child returned403 before Q and exited1. Fixed-version local javap established the post-preHandle Lifecycle response creation; the new framework seam then produced successful actual default/header/query SSE children and a successful manager/native-response/foreign-handler negative child. The boundary covers canonical0/1/Long.MAX_VALUE/default0 and54 invalid cursor/body/identity/path/context/dispatcher vectors, fake/copied proof, foreign wrapper/request/response/thread, expiry/reclaim/clear, no Proxy and original purpose restrictions, all before Session/Q admission.

Post-async failure diagnosis is preserved without rewriting old facts. Initial JDK HttpClient cases showed a real async getter failure and JSON conversion. Finite entry markers later showed an owned typed return followed by a **second** pre-async typed failure/proxy mapping. HTTP automatic GET retry is inferred from those two entries; there was no original REQUEST counter, so the old runs are not claimed as a measured two-request count. asyncOwned remains an authorized history-preservation guard, not a claimed fix for those earlier REDs. Fresh single Socket GET cases measured REQUEST=1 and proved writer0, one actual getter, no ordinary JSON, actual completion (including complete-return-then-Runtime/Error), Owner0, baseline3145728, no live SSE reader, context/private peer exit and parent collector join. Q403, Q transport error and no Proxy then passed separately. Three already-green SSE positives/five profile cases/raw/boundary were not rerun for metadata.

Exactly22 distinct child PIDs/directories:17 exit0 and5 retained diagnostic exit1; every child has real waitFor and collector joined evidence. All run under the new owned root, with fixed TLS JVM flags and the existing asset/receipt/authority private-peer helpers. No sibling business target reflection or production E/PG substitution claim.

| Child group | PID | Actual exit | Unique owned run |
|---|---:|---:|---|
| bad-cache | 50292 | 0 | `bad-cache-32b87ed2-1235-4a17-b299-0c33fd8072e7` |
| bad-connections | 30200 | 0 | `bad-connections-3ef1c993-fead-4a90-9db3-afaeba4de943` |
| bad-custom-executor | 54328 | 0 | `bad-custom-executor-d3cb495d-4ee7-4e9a-935f-957b49b9526a` |
| bad-http2 | 56848 | 0 | `bad-http2-06dc4980-d830-42db-bcae-0d2780909f22` |
| bad-threads | 51600 | 0 | `bad-threads-0cdd36a0-577f-4f2a-b558-91b6359aa0f4` |
| boundary | 30928 | 0 | `boundary-528983d4-dfeb-4361-ac63-0f981fcce16f` |
| default | 19088 | 1 | `default-3fb63546-176d-440d-9167-aac3d0426713` |
| default | 63604 | 0 | `default-62bd62a0-1de3-406b-ac54-9cc1a5368c16` |
| framework-boundary | 60928 | 0 | `framework-boundary-9358cc08-e925-41d3-9127-8847b627f140` |
| header | 31136 | 0 | `header-a32afd14-30fb-40b6-94cc-166531d1c8dc` |
| no-proxy | 42740 | 0 | `no-proxy-06b3594d-d708-4f99-b649-15609bcf1e7b` |
| postasync | 51992 | 1 | `postasync-004db3f5-adbf-4d56-a263-a1199eac99e3` |
| postasync | 57444 | 1 | `postasync-0415d323-a371-4de9-92ec-ae80f9f5850e` |
| postasync | 13472 | 1 | `postasync-1a7345cf-24d2-4d95-b6bb-296b9fe64ce6` |
| postasync | 63704 | 1 | `postasync-3ddf46d2-85b4-46c9-bb69-2593ccc2a483` |
| postasync | 42624 | 0 | `postasync-cc6806b5-599c-4bc3-8fe5-81bb02e080f7` |
| postasync-cleanup | 23752 | 0 | `postasync-cleanup-102d7ebf-5831-429e-ba73-043a108ba321` |
| postasync-cleanup-error | 56960 | 0 | `postasync-cleanup-error-8177661a-697d-446c-ba0f-f3698ae9400b` |
| q403 | 49828 | 0 | `q403-6eadd5bf-a043-40ba-84d1-a969e2713d49` |
| qtransport | 57492 | 0 | `qtransport-1c5bc301-9b35-4cb0-880e-37b5b9ce6dd6` |
| query | 40988 | 0 | `query-bf8baadf-3d52-4a23-8147-1727447f688b` |
| raw-sequence | 49488 | 0 | `raw-sequence-90ed9122-81c8-445e-a537-a9094b0884f9` |

The initial owned runner-root assertion failed before process launch and is preserved with its original failed helper. A later read-only protected-doc metadata collector attempted project-relative paths for explicitly authorized design-plan files outside the project; it failed before writing results, then resumed using absolute identities. Actual eight protected after hashes now match the frozen checkpoint. No test, native Git, patch check or apply was repeated to repair metadata.

## Native source and finite target closure

Eight-source self-review and manual delta inspection preceded the first final native Git operation. Native primary before630 and after634 staged blobs were read and SHA/bytes verified. The actual eight-path binary patch was generated once, then plain `git apply --check` and `git apply` each executed once with exit0, cached immediately. Complete replay names/bytes equal after634, with no replay index and no after-apply copy. No commit was made.

- Source after: 124959B / `c91a517192cb8caecf84da5726640d90c759793065dce9cbe0e94291a737d74d`.
- Binary patch: 22085B / `acc57e073a1abc23e234d013504a9571781ad63b71baa99f7c2e5b5d4b0e585a`.
- Plain check: 864B / `51eb2106a6db4f089eefaf940403e2a6f740364a81157a562bc36cdbe4cb0144`; plain apply: 848B / `f61a672d6f219352858afcad2e78615c5740bf37841474274444c525d2af0f72`.
- Finite current target overlay417; logical target2298 →2314, delta48. Current target files are bound only to actual compiler created/input/status lists, selected Running XML/text, actual source-bound resource copies and the common fixture JAR goal. Earlier actual epochs remain evidence, not invented final reports.
- Target after: 497502B / `e8c4ef2cf272f953f4e1282b939d8f6fa055a487da1b16eac9393b0dc150ee3f`.
- Self-review: 5322B / `3bd05cc78850425112c2f42c4f0c7e41d8c3918b9a3d7c35d2e8164afa6a2177`. Native completion and immutable command caches are in `.local/iam-o2b-f2c2b1-events-wiring/finalized.json` and `native-command-cache/`.

Inherited deprecated-API and console encoding diagnostics are retained. Only B1 component/private-peer behavior is supported; real joined production E/issuer/new PG, default production launcher and external deployment remain subsequent work. startAsync itself failing before successful return is not claimed as an owned-async guarantee. Fresh reviewer and root finite audit are still required.

## Frozen handoff

All eight source paths, finite target rights, both new owned roots, helper glob and this report are frozen and returned to root. No Accepted/index changes were made. Handoff: `.local/iam-o2b-f2c2b1-events-wiring/handoff.json`; complete inventory (all new owned/helpers/report/handoff, excluding only itself): `.local/iam-o2b-f2c2b1-events-wiring/own-inventory.json`.
