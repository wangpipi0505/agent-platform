# Private LOCAL initial-input meter — implementation handoff, NOT Accepted

Generated 2026-10-22T05:13:57.908404+00:00. This is the sole implementer's finite result. Fresh independent review and root audits remain required. Dependency binding has limited CODE acceptance only; its original failed execution, historical scope restrictions and prior uncertainty remain recorded. This report does not assert unconditional task compliance or erase historical adjacent-access/package/runtime limitations.

## Scope and semantics

The private final `FrozenInitialInputMeter` accepts the actual verified frozen binding plus previously captured canonical UTF-8 bytes. It checks interruption/cooperative monotonic 2 seconds, live exact-frame proof, 1..2097152-byte bounds, one defensive clone, JDK SHA256 of that exact clone, captured live scalar identifiers/metadata, explicit remote tokenizer refusal, strict UTF-8, shared local jtokkit count, then live proof/time again. It never reparses/recanonicalizes input and never refreshes authority. Existing proof exceptions propagate. Null binding is 403 MATERIAL_SCOPE_MISSING_OR_CHANGED; invalid length is 400 MODEL_INPUT_INVALID; byte mismatch is 403 MATERIAL_SCOPE_MISSING_OR_CHANGED; remote configuration is 409 MODEL_TOKENIZER_REQUIRES_EGRESS; unsupported model is 409 MODEL_TOKENIZER_UNSUPPORTED; deadline/interruption is sanitized 503 SYS-MODEL-503 with interrupt preserved.

Result is ordinary data: request SHA, model entry, selected binding ID/SHA, inputTokens, method JTOKKIT_WITH_CONSERVATIVE_STRUCTURE, basis ACCEPTED_CANONICAL_INPUT, exact=false. Existing +64 remains a conservative semantic-input structure estimate. It is not calibrated provider-wire counting, a safety-margin rule, reservation, admission or permission. No bean/controller/public route was added.

Exactly five canonical paths changed: 4 added (meter, its two test classes and composing fixture), 1 narrow ModelCalls helper extraction. The old local branch still canonicalizes the same request and delegates; the exact unknown-model IllegalArgumentException/message survives. No remote/public/wire/quota/store/constructor behavior was changed. Actual native diff confirms 667→671, 666 existing full blobs unchanged, zero deletion; eight protected documents match CURRENT afterBytes/afterSha256. All binding/consumer/common/engine production and existing tests, POMs/config/migrations/catalog/contracts/web remain read-only.

## Failure first and stable verification

| Epoch | Maven exit | tests / failures / errors / skipped | source manifest SHA256 |
|---|---:|---|---|
| red2 | 1 | 2 / 2 / 0 / 0 | `a5914db137afe28fa339c0b54fe56958220d33c4faea31b7e9eaeeec7794b096` |
| green | 0 | 8 / 0 / 0 / 0 | `579c02b5fed6ce7467de91bdcf37cb6f84e39deb59c30c74a7b4c346d5279688` |
| regression | 0 | 31 / 0 / 0 / 0 | `6d04b36892d173f8193164da8f1a3c3729d5ccf8677fbb039a0824ae786bb39b` |

RED `red2` is meaningful: original accepted bytes count successfully before the one-ASCII-byte mutation assertion; a genuine supported LOCAL freeze succeeds before a genuine supported-model explicit-remote freeze assertion. Both missing guards returned a count, causing the two exact “Expecting code to raise a throwable” assertion failures, with zero setup/compiler errors. The RED source was a compiling actual-count skeleton without only these new guards. Mapping replacement already fails through genuine inherited proof getters and was covered truthfully; no fabricated stale-proof RED, fake proof, reflection, cache or bypass was introduced.

The first directory `red` is an unsuccessful setup attempt, not RED: a helper classpath encoding issue caused class-not-found before CREATE; its actual setup command/exit/log remain retained. Initial helper javac failures and a later bytecode-text decoder mismatch are preserved in the tool transcript; the latter was resolved by decoding the retained raw GBK javap output into a separate UTF-8 file. No historical bytes were reconstructed. The initial test-compile command/log exited 0; its start metadata was not separately retained. Subsequent actual Maven commands, arguments/cwd/start/exit, fixture argfiles/PIDs and observed test descendants were recorded as they ran.

Final stable command, once for these five affected classes (31 cases total): `mvn.cmd -o -B -ntp -pl admin-common,model-gateway,engine-runtime -am test -Dmaven.repo.local=.tools/m2 -Dtest=FrozenInitialInputMeterTest,FrozenInitialInputMeterHttpTest,FrozenModelBindingVerifierTest,FrozenModelBindingStorePostgresTest,FrozenModelBindingConsumerHttpTest -Dsurefire.failIfNoSpecifiedTests=false` plus actual private fixture/TLS/temporary-path properties in regression/command.json. The flag permits reactor modules with no selected class; actual Running lines and fresh selected XML identify all five model classes, 31 tests, zero skips. No old 73 replay or hardcoded ModelCallsPostgresTest was run. Existing binding classes contribute 22 cases; new meter classes contribute 9.

New cases cover original scientific 0.00001 bytes, generic reserialization/1.0E-5 refusal, CJK/nested output schema, another genuinely accepted request's bytes, null/zero/over-limit/inclusive-limit hash refusal, caller mutation after return, mapping replacement/closed/different frame/cross-thread/retained expiry/fresh healthy frame, explicit remote, unknown model, interrupt preservation and ambient local transaction. Exact old local counts for English/CJK/scientific strings match pinned jtokkit+64. Unreachable malformed-UTF8-with-valid-proof is not forced by fake proof.

Real integration reuses the read-only accepted FrozenModelBindingProducerFixture in a distinct engine JVM, scoped PG publication/prepareInitial, ordinary CA/SAN-trusted HTTPS, real signed consumer and actual PG binding. New model fixture composes accepted helpers, uses ModelStore.updateEntry on its own entry then real ModelStore.freeze, and selects that returned LOCAL binding before rebuilding the request/manifest. It never rewrites a positive binding snapshot/hash, changes final fields or launches the old hardcoded setup. Its proof observations record actual selected request/entry/binding/SHA. Inherited generic setup observations retain initial remote-binding setup identities and are explicitly not selected-meter identities.

Regression real HTTP observations: engine PID 61684, model test PID 14184, selected entry1/binding2, requestSHA 37458dbf526661a1e23c40a80e6b628544cbe6081e7b70c37305796c2710bb13, bindingSHA 12a152cc10186c43d786a8cd7e45b6de8a07a4e0e2ab28545ae66dcda08258e5, count332, exact=false for first/repeat/newGrant. This is test-only signed HTTP loopback IAM plus HTTPS engine/provider/tokenizer; it is not production IAM or an actual model send.

## Structural versus dynamic boundaries

`meter-javap.txt` retains actual compiled bytecode disassembly; UTF-8 companion and static-local-check.json record local helper+JDK exact-byte hash and no SecretVault/ModelStore/HttpClient/public measure collaborator. This is structural evidence, not a live SecretVault invocation counter. Poison provider secret_ref is set after setup freeze and local count succeeds without resolver dependency; no disconnected static-zero canary was used.

Dynamic endpoint controls hit the actual inherited HTTPS `/provider` and `/tokenizer` once each using fixed GET probes, verify each counter=1, then reset the measurement window and observe both=0. During the local meter window, observed JDBC SQL is empty and IAM call count unchanged; full contents of model provider/entry/binding_snapshot/call_log/quota_reservation/metering_calibration compare equal. Saved local-meter-window.json identifies actual proof and content digest. These observations are scoped to the actual local invocation window; test setup is separate. Engine closed evidence records attempt/model_invocation/sdkAttempts=0, input snapshots=2 and only metadata read SQL after ready. No claims of provider execution, tokenizer calibration or first-send completion follow.

The 2-second guard is cooperative around synchronous library initialization/encoding, not arbitrary JVM preemption. New cases have JUnit @Timeout(30); coordinator has readiness30s, stop10s and a total-test-run300s emergency watchdog restricted to recorded descendants (not a per-library hard preemption promise). No artificial production executor/caller clock/delay hook was added. No actual hang or forced kill occurred. Binding connection/query/TX/envelope bounds remain the accepted dependency's real checks and affected regressions; they are not widened by this component.

## Source, finite artifacts and packaging

BEFORE667: 132133 B / SHA256 `2aa978a9b19297fc15ea428f2fddca3cd4a3604f1c00b43174002a8ac826fa7c`.

Final671: 133037 B / SHA256 `6d04b36892d173f8193164da8f1a3c3729d5ccf8677fbb039a0824ae786bb39b`.

Regression finite post-test capture was completed BEFORE package: 154869 B / SHA256 `6814d3fc6623831d7a21ef6c14455d9e1924b49720d481a4f258796bcea647e2`. It contains 627 finite classes/resources/compiler-input/created-list files; fresh Running-only XML is retained separately there. Actual XML classpaths and observed process command lines are retained; automatically removed Surefire temporary files are honestly marked not retained. Post-test/fresh package source equality and class/resource equality are finite observations, not historical loaded-class attestation.

Only a fresh owner package-workcopy containing exact671-source full blobs was packaged, with actual command/cwd/prepackage manifest SHA/start/exit recorded. Offline Java21 common+engine+model -DskipTests package exited0. Canonical workspace was not packaged and the locked engine main artifact was not touched. Finite pairing: common108 classes/resources +92 test files, engine139+215, model37+24; all class majors65, zero class/resource differences between posttest and fresh build; six JAR/original files recorded, JAR entries equal fresh classes/resources, no sibling business JARs.

Actual native binary patch: 22275 B, SHA256 8ba11db6561afc86ce49a8e11cf67e4804221e67a5e6a4747157fb2d89595ec1. Own scratch git init/index only, core.autocrlf=false and cr-at-eol; diff --check0, apply --check0, apply0, all671 applied full blobs equal final. No commit/deploy/public activation/new chat/automation.

## Owned resources and closure

Owner iam-model-egress-local-meter. Registry-before-CREATE, absence check, private epoch ACL exit0/protected identity before credential writes. Three fresh databases total (budget<=8; sequential, at most one epoch database live), on new PG127.0.0.1:25432 only:

- iam_model_input_meter_3e40c8d06a6c485aba1e5ed9af4abf44 (red2)
- iam_model_input_meter_8d78961f90de48b7b12deab24e3158d1 (green)
- iam_model_input_meter_e48379c7758f4ffc8104df5e2edd63ed (regression)

Each has fresh imim_m_/imim_e_ roles with matching suffix, isolated model/engine schemas. Admin credentials were removed before children; no real upstream credentials or endpoints. All three closed with sessions0, roles NOLOGIN/nonsuperuser/nocreatedb/nocreaterole/noreplication/nobypassrls, no other memberships and reciprocal schema denial. DB data/roles retained; none dropped/reset/reused. Actual own fixture/test/setup PIDs and listener closure show no living registered process/no owned port. HTTPS/IAM servers/pools close through owned fixture lifecycle. Only read-only existing TLS assets are reused; V: mapping is checked for project identity before child unix-domain temp use.

Main15173–15175/18080–18085/Redis26379/MinIO29000/29001 were not operated on. This run did not capture an initial global listener/process census, so it does not retroactively certify those unrelated runtime states. No neighboring-project files/resources were intentionally accessed; this cannot erase original historical outside enumeration, failed canonical package/unknown main impact or historical loaded-class limitations. Source667 match and protected8 current-after checks are finite facts only.

## Handoff

Global T01 only complete, T09/T11/T12 ongoing, T33 not started; v1.2/four contracts/no schedules unchanged. The component stays private and unassembled. It does not open CHAT/LLM/AGENT, measure route, egress, provider credential resolution, actual wire counting, reservations/admission, attempts/response or default production IAM.

ALL canonical-target, owned-source, DB/roles, HTTPS/IAM/runtime, helper, workcopy, evidence and report rights are returned to root after the final inventory/control seal. No background work remains. NOT Accepted; independent review/root audits decide subsequent disposition.
