# Private frozen model binding verifier/store — implementer handoff

Status: implemented and covered as a private prerequisite; submitted for independent review. **Not Accepted.** No public admission, default bean/controller wiring, model send, tokenizer, credential, attempt, deployment, commit, automation, new chat or global completion claim.

## Authority and scope

Requirements are `docs/evidence/iam-model-frozen-binding-brief.md`, frozen `.local/iam-model-input-egress-next-task-plan.md`, `.local/iam-model-frozen-binding-root-plan-clarifications.md`, AGENTS.md, implementation brief and contracts. Root clarifications read and applied: `.local/iam-model-frozen-binding-jdbc-observer-supplement.md`, `...-clock-supplement.md`, `...-empty-fixture-supplement.md`, `...-artifact-evidence-supplement.md`. TDD, Java/Spring, minimal implementation, systematic debugging and verification-before-completion skills were used; the root owns independent SDD review. No subagent was spawned.

All shells used cwd `G:/207/项目/算法重组工具/代码/agent-platform`. Before canonical edits, all accepted660 files were physically byte/SHA compared and copied to `.local/iam-model-frozen-binding/before/source/`. Final667 are physically compared to `.local/iam-model-frozen-binding/final/source.json`. Exactly seven additions, one narrow modification, 659 original files unchanged, no canonical deletion.

| Canonical path | Bytes | SHA256 |
|---|---:|---|
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/FrozenModelBindingProducerFixture.java` | 21466 | `1069921859e39c3bfdbec4c99558ecfe6a06677439910695a4d6ca57a9c1d3ac` |
| `model-gateway/src/main/java/cn/agentplatform/model/application/FrozenModelBindingVerifier.java` | 14307 | `16a72dcbebe142754b323dc57a1adfa4ccb5b7f57f437c88e03ad8e3dae6e263` |
| `model-gateway/src/main/java/cn/agentplatform/model/infra/FrozenModelBindingStore.java` | 2341 | `cd31a501739af555a5d7c5b93d343b504fe40c4574a7ff8022dc9bba39f16aae` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerFixture.java` | 16048 | `12b548b9958f19d981706ce2a4e55d5590b9521fcbecfd19ecbde1800f2cf2ed` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java` | 5635 | `54e8e875e2d86a1973a387421c06333abbaa1e0ed14390105a132d4afe21b1be` |
| `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingVerifierTest.java` | 19228 | `c3f4a86c68fd344562ba6df85a3ddf8798d0746e551b922d075435dff2c770bc` |
| `model-gateway/src/test/java/cn/agentplatform/model/infra/FrozenModelBindingStorePostgresTest.java` | 549 | `00ff9791155b10c74440f1c85dd0174008863be512783e40ac8a615b312e46f5` |
| `model-gateway/src/main/java/cn/agentplatform/model/application/InitialModelInputVerifier.java` | 23492 | `4fcd54de59ee80d432400294b0aa4360995f472618cd01949e0b213ab15fa189` |

Existing consumer modification is only package-private `authorityForBinding()`: first `requireVerified()`, return the existing retained mapping. Existing public copy-safe request/manifest/canonical-byte accessors are reused. Existing consumer parser, transport, guard, minting and all other canonical production/test/POM/migration/config/web files are unchanged. T01 remains the only globally complete task; T09/T11/T12 ongoing and T33 notstarted. This report does not update project ledgers.

## What the private result proves

The verifier only accepts the actual private consumer result and internally constructs the concrete store from its supplied trusted DataSource. It rejects ambient transaction, interrupted/null/stale/cross-thread proof before SQL; captures all old-frame facts before refresh; hashes genuine accepted canonical UTF8 bytes directly; obtains structure through checked `request()` without lossy generic reparsing. Real producer scientific decimal `0.00001` is exercised in both focused and independent-JVM joins. There is one accepted request digest, with no alternate candidate or reserialized replacement.

The store performs one parameterized tenant/id binding SELECT in actual read-only REPEATABLE_READ, with maxRows2, 256KiB SQL CASE before JDBC materialization, statement query<=2s, transaction-local statement_timeout2000ms/lock_timeout1000ms, transaction<=3s. Setup statements are separate from business reads. No entry/provider/engine/secret SELECT or mutable fallback occurs in verifier/store. PG whole snapshot is parsed with duplicate detection, trailing-token rejection and depth32, then its complete tree is hashed before any projection/Set conversion. Row/manifest/request IDs and SHA, nested provider identity, exact root/provider fields, integral limits, strings, arrays, protocol and endpoint/tokenizer origin are checked. Request/root/run/manifest/binding/scope/wire digest domains remain distinct; no wire digest is claimed.

Transaction and connection return precede final `AuthorityContext.requireInitialFor`. Old proof access is never used after refresh by production. Fresh purpose, owner, root, operation, exact initial ceiling and origin remain bound to captured evidence. Each parent and child scope is validated against fresh current authority. Labels union includes request, child, all parents, original initial ceiling and fresh actor, refuses >256 and applies frozen trust including empty->LOCAL. Frozen chat/tools/json_mode capability and effective output precedence/limit are checked. Mutable disabled/model/endpoint/label changes do not replace frozen facts; provider status/credential availability are not proven by this prerequisite.

`VerifiedFrozenModelBinding` has a private constructor, copied validated metadata and immutable accepted material facts. Every public accessor calls retained verification plus exact live frame/mapping binding. Access after replacement, close, expiry or thread change fails. Returned metadata mutation does not affect the proof. There is no request-body/retained/secret serialization surface, renewal, lease, async transfer or send permission.

Material scope has no separate TTL. In accord with the clock supplement, `MaterialAuthorization.require(..., Clock.fixed(fresh.current().checkedAt(),UTC))` only reuses structural/owner/coverage checking at the signed observation; actual `fresh.requireVerified()` and exact bound checks bracket scope checks and minting. Retained private clock still enforces current/delegation/operation/ticket expiry. Fixed observation is never a caller clock or a lifetime substitute. The independent monotonic verifier envelope is cooperative<=8s, with no retry/executor/preemption claim for arbitrary DataSource.

Errors are sanitized: invalid/missing/mismatched material 403 MATERIAL_SCOPE_MISSING_OR_CHANGED; trust 403 SYS-EGRESS-4031; capability/output 409 SYS-MODEL-4002; DB/time/deadline 503 SYS-MODEL-503. Existing meaningful authority403/503 pass through. Ambient transaction programming error is CURRENT_NETWORK_REQUIRES_NO_TRANSACTION before activity. SQL/snapshot/token/grant/credential data are not propagated in these errors.

## Failure-first and source epochs

Actual test logs, selected Running/XML and classpaths: `docs/evidence/iam-model-frozen-binding/test-epochs.json` and each epoch directory. Full source blobs/manifests are in this task's corresponding `.local/.../*-source/`. Table is tests/failures/errors/skipped; epochs are never merged into one run.

| Epoch | Complete source manifest SHA256 | Actual counts |
|---|---|---|
| red2 | `dd18e101a9aa42b767e6f69873132920c5f1353b209821d388daf3718bbdb2de` | 3 / 2 / 0 / 0 |
| green-candidate | `f1848269a3ce1cca08178bcb17cca51f1971becc355a3a65f226a84d4121dd17` | 15 / 0 / 1 / 0 |
| green5 | `b4769340c13704a3f60bfa940a5d8a587fde2293914654fe11d9b94c23bea8f1` | 93 / 0 / 2 / 0 |
| green6 | `bc2099156c4e5231704e6797e0ad309cb4a28a8a4b6f4a35b7a833c44039ab7c` | 93 / 0 / 1 / 0 |
| green7 | `5e4a053394066ff115a617c89cd3b85cc58518d49de50e1f5a91265ab20f89b7` | 20 / 0 / 0 / 0 |

`red2` is a compiling skeleton with a genuine accepted consumer, real ModelStore.freeze and real PG. Positive control passes. Both `rejectsChangedSnapshotUnderOldSha` and `retainsLostLabelObligation` fail the explicit "expected throwable" assertion (2 assertion failures, 0 setup errors). These are the behavioral REDs. Earlier `red` setup failed scoped CREATE SCHEMA database privilege and is retained; it is not a RED. Fresh database per stateful run was preserved. The attempted identity-ID mutation in green-candidate was rejected by PG GENERATED ALWAYS; it is fixture/constraint evidence, not a verifier denial, and was replaced by a genuine accepted nonexistent binding selector.

Green5/6 new-test errors were fixture orchestration: old-proof access terminally invalidates the whole current frame, and generic LoopbackAuthority gateway configuration cannot express empty dataLabels. Tests were corrected without changing common/production guard behavior. Green4 @argfile Chinese-encoding startup failed before DB creation; subsequent argfiles use the verified V: mapping. All diagnostic files remain. Green6 was already running when root requested no further old-suite replay; no later old green suite was replayed for metadata.

Final new binding group is **green7:20/0/0/0**, exact final source SHA `5e4a053394066ff115a617c89cd3b85cc58518d49de50e1f5a91265ab20f89b7`. Command is recorded exactly in `green7/command.json`: offline Maven `-pl admin-common,model-gateway,engine-runtime -am test`, test selection FrozenModelBindingVerifierTest,FrozenModelBindingStorePostgresTest,FrozenModelBindingConsumerHttpTest, own binding/consumer fixture and accepted TLS/trust properties, no integration skip. Missing explicit fixture is failure, never skip.

Specified unchanged regressions are73 green tests in green5/green6: RetainedAuthorizationTest13, AuthorityContextRetainedTest5, CurrentAuthorityClientTest20, FrozenModelsTest2, InitialModelInputVerifierTest16, EngineModelInputManifestClientHttpTest11, InitialModelInputConsumerHttpTest3 and ModelPolicyTest3. They retain their own actual epoch/source/Running/XML identities. Since green5, final source changed only these new test files: `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerFixture.java; model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingConsumerHttpTest.java; model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingVerifierTest.java`. Neither new production class nor the narrow consumer seam changed after those regressions. The accepted consumer/common sources covered by the regressions are byte-identical to final. This is source equivalence, not a fictitious single whole-suite green epoch.

## Covered boundaries and evidence type

| Boundary | Actual focused/integration coverage |
|---|---|
| Proof before SQL | null, closed, replaced, cross-thread and ambient tx; currentOnly denied by unchanged consumer regression before metadata/SQL; private constructor is also statically inspected |
| Stored content and exact IDs | old-SHA tamper; tenant/no row; snapshot entry/provider/nested provider disagreement; real freeze positive |
| No mutable fallback | disabled/renamed/URL/labels changed mutable rows plus poison secret_ref still use exact historical snapshot; missing row denies |
| Digest domains | array reorder old SHA denies, object-key reorder succeeds, accepted canonical0.00001 preserved, consumer request mutation regressions deny |
| Current/material/labels | postPG role/region loss, revocation/outage, signed owner/root/initial/origin changes, fresh new label and >256 union, lost parent label, empty->LOCAL |
| Bounds | unknown/secretRef/status/null/coercion/fraction/negative/overflow/duplicates in capabilities/trust, width, depth, oversize SQL CASE, protocol/userinfo/fragment/tokenizer origin |
| Capability/output | missing chat, invalid/excess output and strict nested output precedence; tools/json branches are static in the new verifier (nonempty tools are unreachable under the accepted initial consumer profile) |
| Time/resources | actual PG AccessExclusive delay, query lock timeout, depleted actual Hikari pool, preserved interruption, expiry during SQL, >8s cooperative envelope; fresh healthy frame succeeds after lock release |
| Lifetime | exact-frame repeated access and deep copy, old proof retirement after binding refresh, replacement/close/thread/retained expiry denial |
| Real join | distinct engine/model JVMs and schemas, actual createPublished/prepareInitial, signed receiver and CA/SAN HTTPS metadata, actual ModelStore.freeze descriptor, three current-grant positives and owner/root/origin producer denials |

PG JSONB cannot retain duplicate object keys/trailing JSON syntax; parser duplicate/trailing configuration is structural evidence, not a fabricated reachable lexical-PG negative. No alternate public parser/proof seam was added solely to force that case. Token/context budget is not measured. Any later tools/other profile needs its own accepted consumer path and focused validation.

The empty classification setup uses its own test issuer's actual authenticated /token exchange with genuine empty portal JWT, verifying RS256, issuer/audience/nonce/request SHA/client/current/active/expected call before using the issued engine ticket. Actual concrete engine.authorizeRetained→resume→model receiver→consumer mint the proof. This tests private binding empty->LOCAL, **not generic gateway configuration admission**. No handcrafted retained or ordinary authority is accepted as a positive.

## Actual observations versus structural closure

Green7 `model/focused-observations.jsonl` has55 real accepted-case observations. Actual provider/tokenizer HTTPS counts are0 in verification cases; their observers have independent real normal-TLS positive controls (one request each, asserted then reset). Seeded real frozen provider/tokenizer endpoints remain live through integration, with actual0 counters in `model-seed-closed.json`. No dead zero canary is used as credential/measurement evidence.

For observable focused frames, call_log/quota before-after counts agree. Depleted-pool case explicitly records -1 as unobservable within that frame rather than a fake zero; final administrative PG audit records actual aggregate zero call_log/quota writes. Actual binding transactions are readOnly=true, isolation4(REPEATABLE_READ), autoCommit=false. The read observer checks no IAM call advancement while borrowed; final refresh and narrowing occur only after actual release. Counting JDBC proxies only delegate real calls/errors and unwrap InvocationTargetException; no row/result/freshness is fabricated.

Separate-JVM green7 join records3 successes, each exactly1 binding SELECT, borrowed0 and copied whole snapshot digest equal actual frozen SHA. WrongOwner/wrongRoot/differentOrigin each stop at producer403 before model binding SQL. Final engine attempt/model_invocation/sdk_attempts remain0. Model call_log and quota rows0. IAM refresh/manifest GET are expected and counted separately. No response open/check appears in actual fixture operation lists.

measure and SecretVault.resolve have **zero dependency/call-site structural evidence**, not invented dynamic counters. Source and javap/jdeps show neither new class depends on ModelCalls/ModelStore/SecretVault/tokenizer/provider dispatch. Observed providerHTTP0 cannot itself prove no credential read. Business-store ModelStore is used only by explicit test setup before verification. New result is not an egress permit.

## Packaging execution deviation and artifact limits

The first package command incorrectly targeted canonical targets rather than a fresh owned source copy. It recompiled canonical common/engine and wrote a plain engine JAR, then Boot repackage rename failed due a file lock. Model was SKIPPED. The affected absolute path is **G:/207/项目/算法重组工具/代码/agent-platform/engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar**. A narrow attempted move of this new failed output into owned retention also failed with a sharing violation. No lock holder was enumerated, modified, stopped or identified, and no further compensation/write was performed on the locked artifact. **Zero main-runtime impact is not established.** The failed log/command/exit remain `package.log`, `package-command.json`, `package-exit.json`.

There was no finite class capture before that package attempt. `.local/.../post-green7-plus-failed-package/` honestly contains model's green7 post-test target plus common/engine post-failed-package compilation; it is not a retrospective capture of every historical loadedClass byte. Historical tests retain actual source/Running/XML/classpath/PID/argfile evidence. No new class probe or test replay is misrepresented as historical attestation.

Final package is a brand-new exact667-source owned `.local/iam-model-frozen-binding/package-workcopy/`, offline `mvn -f .../pom.xml -pl admin-common,model-gateway,engine-runtime -am package -DskipTests`, with absolute repository `G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2`. It passed all three modules; log and exit are `package-workcopy.log` / `package-workcopy-exit.json`. All source bytes match final667. Final files, finite compiler inputs/created classes/resources/JARs and class major65 are in `finite-package.json`; all class/resource bytes match the accurately named current-target capture. Maven-status paths naturally identify each workcopy. This is a fresh same-source package, with the historical limitations above.

| Owned-copy JAR | Bytes | SHA256 |
|---|---:|---|
| `admin-common/target/admin-common-0.1.0-SNAPSHOT-tests.jar` | 380314 | `11bf541903c0b19203903d573d12f58ffac5a3b4f63804afde8202f67dacc0a4` |
| `admin-common/target/admin-common-0.1.0-SNAPSHOT.jar` | 259737 | `82d6b568a9f8e1ced2d7650fd5811e5cb6d878bba9c3cabbf31541fb4c0262e0` |
| `model-gateway/target/model-gateway-0.1.0-SNAPSHOT.jar` | 58293537 | `deb25cbaefde9db4eecb69fe923e6eb7f949887763d05eadf2c4abb016a2eaac` |
| `model-gateway/target/model-gateway-0.1.0-SNAPSHOT.jar.original` | 111782 | `90a3329298945487dcb7f2bad1c2acb7471c11a9684ce013282b0343933933cc` |
| `engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar` | 51814983 | `04c58f61af8cc5ffc89a5ea912205ca21aed2e6ac89f9e88e90908c36d342e99` |
| `engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar.original` | 452097 | `75d7844d6160f687f87890955c9b2bde000b72d0f54e88b12e012ca18adddf23` |

`fixture-classpath-identity.json` seals concrete model/engine launcher classpath entries; neither includes the other business artifact. Accepted common test fixtures remain permitted. Static `javap.txt`/`jdeps.txt` inspect constructors/accessors/dependencies only; no business/private proof reflection. No dependencies were installed or frameworks changed.

## Native source replay and protected documents

Before manifest: `1c8263238566ff53de32a71196109966e7838cbf9bedafe9d414d8c995613056`. Final manifest: `5e4a053394066ff115a617c89cd3b85cc58518d49de50e1f5a91265ab20f89b7`. Root accepted baseline manifest and own before have identical660 entries despite formatting. Binary-capable native Git patch in `native-sealed/change.patch`: 84296B SHA `19a35639d891a0f22a7962372467be878544c8cb3fbe986771e68a50a5f8a08a`. Native diff --check, apply --check and apply all exit0, actual replay result equals every final667 path/byte/SHA. Native scratch has its own index/config, no commits; Windows CR-at-eol and autocrlf=false are explicit. Earlier unconfigured CRLF check/apply failures are retained in `native/` and `native-final/`, not passed off as green.

Final physical canonical667/659-original check passes. Read-only protected baseline observation found **7/8 equal**, with one external design execution plan difference versus supplied manifest afterBytes/afterSha: expected133244B SHA9c5ef79b2d1d9160ba73df964e9b444f3c8153b725545d484a0f6ee0b506faa2; observed132238B SHA971f6c898cc250c2832a54920601d6e5c253dd3236230b799722cf006385d210. Full identity is in `protected-observed.json`. Implementer never wrote that path or repaired it. Root independently classified the sole difference in `.local/iam-model-frozen-binding-root-external-plan-classification.json`: after ignoring empty lines and trailing whitespace, all339 nonempty lines are identical, semanticDiffLines=0. This is seven byte-identical protected files plus one external formatting-only change of unknown origin/operator. Root did not write the external file either. Current file and original checkpoint remain untouched; no claim that all8 retain their old hashes is made.

## Owned resource closure and handoff

Only6 fresh registered `iam_model_frozen_binding_<32hex>` databases were created (budget12; <=2 live). Absence and owner registry preceded CREATE. Each got unique separate model/engine scoped roles; actual reciprocal schema access is denied. Setup used administrative credentials only in its separate process; model child received only model-scoped file config and its own current-client environment, with unrelated engine/admin credential environment removed. Fixture directories were ACL protected before sensitive writes. Actual trusted Hikari acquisition<=2s, PG connect<=2s/socket<=3s, readiness<=30s, stop wait<=10s. Generic arbitrary DataSource still cannot be portably preempted; production datasource integration configuration remains an explicit future prerequisite. Component stays unwired.

`database-role-closure.json` records all6 databases at0 active sessions before audit, retained12 roles NOLOGIN/nonprivileged, no cross-schema usage, database CREATE grants revoked, and actual owned table row counts. Owned schemas/data/roles are retained, never reset/deleted. `process-listener-closure.json` checks only recorded owned PIDs and their listeners; all exited/closed. Each separate fixture also records closure and HTTP/PG facts; test JVMs ended through actual Maven exits. No main service, Redis, MinIO, K8s, ontology, real IAM/model request or adjacent old project/DB was used. Unknown canonical JAR lock-holder impact remains explicitly unproven as above.

No scoped secret/ticket exact value occurs in retained logs/XML (private values are preserved only in ACL-protected fixture files). All diagnostics remain. Final report is sealed before inventory/control. Inventory excludes its own hash and control to avoid recursion; control binds inventory/report/source/native/package/resource identities and explicitly returns **ALL canonical source, target, owned source/copies, DB/schema/roles, HTTP/listeners, helpers, workcopies, evidence/report and runtime rights** to root. No writer, helper JVM, observer or background work remains. Independent fresh review/root audit determine acceptance.
