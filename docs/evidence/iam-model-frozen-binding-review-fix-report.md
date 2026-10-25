# Frozen model binding I1 repair — implementer handoff

Status: repair completed, pending independent review; **not Accepted**. Sole engineering root is `G:/207/项目/算法重组工具/代码/agent-platform`. No public model endpoint, provider/credential/tokenizer path, deployment, commit or main-service activation was added. Original C0/I1/M0 Needs fixes remains the historical review result; this report provides the narrow I1 repair evidence for a fresh reviewer.

## Exact source and repair

Before any canonical edit, all667 files were physically read and compared to original final667. Own before blobs/manifest are exact to that baseline. Final667 retains the same path set; **3 existing files changed,664 unchanged,0 added,0 removed**:

- `model-gateway/src/main/java/cn/agentplatform/model/application/FrozenModelBindingVerifier.java`
- `model-gateway/src/main/java/cn/agentplatform/model/infra/FrozenModelBindingStore.java`
- `model-gateway/src/test/java/cn/agentplatform/model/application/FrozenModelBindingVerifierTest.java`

Every other canonical product/test/POM/config/migration/catalog/contract/web file is unchanged, including both real integration fixtures and the accepted consumer. Only the listed files, own fix work/evidence/helpers, and this report were written. TDD, Java Spring Boot, systematic debugging, minimal implementation and verification skills were read/applied. No child agents were used.

.local/iam-model-frozen-binding-review-fix/before/source.json — 132133 B; SHA256 `5e4a053394066ff115a617c89cd3b85cc58518d49de50e1f5a91265ab20f89b7`

.local/iam-model-frozen-binding-review-fix/red-source/source.json — 132133 B; SHA256 `f46625446d10d0375f2e3ba8e31f0a46cafa3d70f2c147c9504c048c407ec024`

.local/iam-model-frozen-binding-review-fix/green-source/source.json — 132133 B; SHA256 `2aa978a9b19297fc15ea428f2fddca3cd4a3604f1c00b43174002a8ac826fa7c`

.local/iam-model-frozen-binding-review-fix/final/source.json — 132133 B; SHA256 `2aa978a9b19297fc15ea428f2fddca3cd4a3604f1c00b43174002a8ac826fa7c`

The cause was the independently identified hand-JDBC gap: TransactionTemplate's3s holder deadline was set but not consulted by the SQL statements or commit path. The store now calls `DataSourceUtils.applyTimeout(statement,dataSource,2)` before **each** of the two SET executions and the SELECT, then clamps the resulting JDBC timeout to2s. Thus a large holder TTL never increases the separate query cap. It checks holder millisecond expiry and interruption before SQL and after each row-advance/materialization boundary. PostgreSQL transaction-local statement_timeout remains2000ms and lock_timeout1000ms.

Spring's whole-second JDBC TTL rounds up. The source comment makes that rounding and the required post-read/callback/cleanup rejection explicit. Separate System.nanoTime checks enforce a3s monotonic transaction-work budget, conservatively starting **before transaction acquisition/begin**, and check again before callback return and after TransactionTemplate finishes commit/cleanup, before parsing/final IAM. Starting before acquisition is stricter than timing only an already begun transaction; acquisition remains separately configured<=2s. The original monotonic8s verifier envelope and actual private retained-clock lifetime checks remain independent. No public clock/deadline seam, executor, retry, background cancellation or authority change was added.

On expiry the verifier returns sanitized503 `SYS-MODEL-503` / `模型绑定暂不可用`, releases actual transaction/connection resources and reaches neither final IAM refresh nor proof minting. Spring RuntimeException expiry is translated at the existing verifier boundary. Authority/material/label/digest/parser/capability rules, interrupt preservation and same-frame private proof construction are unchanged.

**Cooperative limit:** JDBC second rounding, thread scheduling and arbitrary DataSource/JDBC begin/commit/close code cannot be forcibly preempted portably. The3s and8s checks reject work that returns after budget expiry; they do not guarantee a hard OS wall-time cutoff. Cleanup may itself consume time after SQL cancellation. No proof/final refresh is allowed on a returning transaction overrun. The component remains private/unwired; actual future production wiring must separately provide bounded pool acquire/connect/socket behavior. The owned fixture uses Hikari acquisition2000ms/validation1000ms/max2, PG connectTimeout2s/socketTimeout3s and existing normal TLS/finite HTTP authority/manifest clients.

## Meaningful RED and affected GREEN

RED was executed on the unchanged production implementation plus the new negative test, with a fresh real PG database, actual ModelStore freeze, signed accepted consumer, real retained frame and working positive control. The transparent existing beforeRead observer asserts an already begun read-only/non-autocommit transaction, then sleeps3250ms before otherwise healthy SELECT. The test's own monotonic measured interval must be>=3000ms and<=7900ms. No fabricated row, authority or proof was injected.

Actual command is recorded before launch in `red/command.json`, full exact argument invocation in own `.tools/iam-model-frozen-binding-review-fix-red-maven.ps1`, JVM descendants in `red/test-processes.json`, actual Running/XML and maven status in `red/target/`, exit in `red/exit.json` (all relative evidence paths below mean `.local/iam-model-frozen-binding-review-fix/`). Essential command:

```powershell
mvn.cmd -o -B -ntp -pl admin-common,model-gateway,engine-runtime -am test -Dmaven.repo.local=.tools/m2 '-Dtest=FrozenModelBindingVerifierTest#transactionBudgetRejectsDelayedReadBeforeFinalRefresh+realFreezeAndAcceptedConsumerPositiveControl' -Dsurefire.failIfNoSpecifiedTests=false
```

The actual command additionally supplies owned binding.fixture/consumer.fixture, read-only accepted TLS/trust assets, trust password fixture-only, and `-Djdk.net.unixdomain.tmpdir=V:/.local/tmp`; full contemporaneous arguments, cwd and source SHA are in the command JSON. `V:` mapping was checked against this sole project. Real scoped credentials are in private ACL-protected files/environment, not command arguments.

RED result: **2 tests /1 failure /0 errors /0 skipped; Maven exit1**. `realFreezeAndAcceptedConsumerPositiveControl` passed. The negative reached its intended assertion: **“expired transaction cannot mint a binding proof — Expecting actual not to be null”**. Thus the old implementation actually returned a proof after the3250ms transaction-internal delay; this is a behavioral assertion RED, not compilation/setup/timeout error. The same test had already passed its measured3s–7.9s interval and borrowed-connection-zero assertions before failing the missing503 assertion. Original log/source/Running/XML/command/exit remain frozen in own red epoch.

After the two-file production repair, the exact final stable source added a second necessary negative: `transactionCleanupOverrunCannotReachFinalRefresh`, which delays3250ms in the existing real afterRelease callback and asserts sanitized503, authority-call count unchanged and borrowed0. The delayed-read case also asserts sanitized503, no final authority refresh and borrowed0 on GREEN. The actual private constructor/static dependency checks are recorded separately; a throwing verify call yields no return proof.

Affected command (full owned fixture/TLS arguments in `green/command.json`):

```powershell
mvn.cmd -o -B -ntp -pl admin-common,model-gateway,engine-runtime -am test -Dmaven.repo.local=.tools/m2 '-Dtest=FrozenModelBindingVerifierTest,FrozenModelBindingStorePostgresTest,FrozenModelBindingConsumerHttpTest' -Dsurefire.failIfNoSpecifiedTests=false
```

GREEN on final source `2aa978a9b19297fc15ea428f2fddca3cd4a3604f1c00b43174002a8ac826fa7c`: **22 /0 /0 /0, Maven exit0**, one complete affected run. Actual Running classes/XML: Verifier19, Store1, ConsumerHTTP2. The verifier cases retain real PG lock cancellation/release followed by a healthy frame, acquisition exhaustion, interruption, retained expiry,3250ms transaction delay,3250ms release cleanup overrun and8050ms whole-verifier cooperative overrun. The two HTTP tests use the unchanged actual separate model/engine fixture protocol, real frozen descriptor/publication/prepareInitial/locked canonical export, signed receiver, normal CA/SAN HTTPS, actual consumer and tenant model PG verification; fixture absence fails rather than skips. Engine/model use distinct PIDs and scoped schema credentials, with no sibling business/test JAR exchange.

**No old73 regressions were rerun.** Original green5/green6 epochs and original green7 findings remain separate and immutable. `test-summary.json` selects XML only for actual Running classes; RED's copied report directory also contains stale non-Running HTTP/Store XML from previous canonical test epochs, which is not claimed as RED execution. The initial overinclusive own metadata summary is retained as `test-summary-initial-stale-selection.json`; correcting this selector required no product/build/test rerun. Original root evidence-reader copy-helper/package-launcher and root closure-reader numeric-lifetime two-SELECT corrections remain original root metadata, not product failures; those completed root audits were not repeated.

## Actual bounds, resources and closure

A narrow own admin coordinator was compiled under own helper-classes. It follows existing fixture file protocol and invokes no old hardcoded ownership setup/coordinator. It registers absence-preflight plus explicit owner **before CREATE**, creates fresh scoped credentials/schemas, writes model-db/engine-db configuration, and uses the existing public fixture pool settings/Flyway migration behavior. Actual existing model serve and engine fixtures perform business setup; no new business/proof fixture was introduced.

Exactly2 fresh DBs were created across2 epochs (budget<=6; maximum1 live owned fixture DB at a time):

- RED `iam_model_frozen_binding_fix_31e30d3ffa214e7882d10a1fe875195f`
- GREEN `iam_model_frozen_binding_fix_0bc56524744c45c297bb8f1d4c9ac8ab`

Both retained DBs have0 sessions in closure,4 unique retained roles are NOLOGIN/NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS with0 role memberships. Model roles have model schema usage and engine usage=false; engine roles have engine usage and model usage=false. Actual engine negative query also records model-schema denial. No old retained DB was connected/reset/reused. Setup/close each use the explicitly authorized administrative agent_platform_v12 connection only for new owned resources. No existing role was changed. Actual DB/schema/roles and counter records are `databases.jsonl`, `red/setup/db-closure.json`, `green/setup/db-closure.json`, engine closed descriptors and focused observations.

Own red/green directories had protected ACLs **before any credential configuration write**, with icacls exit0, protected=true, recorded owner/SDDL/output in each epoch `acl.json`. Model child credential environment filtering strips unrelated AGENT_DB/SECRET/IAM/ENGINE/MODEL values before launch; only its scoped private config reaches it. Maven receives the specifically scoped model IAM secret through its environment. No credentials/JWT/request bytes were written to stdout. Private-value scan found0 occurrences of18 actual private values in281 retained log/XML files (`private-value-log-check.json`); credentials/tickets stay only in protected fixture files.

Each epoch uses<=2 fixture JVMs (model seed + engine), one Maven launcher and one Surefire test JVM; setup admin JVM runs before fixture launch and close admin JVM after fixtures stop. Readiness<=30s; stop wait<=10s followed only by recorded-own-PID fallback, with actual fixture stop files/final descriptors retained. `runtime-closure.json` shows every18 recorded own process IDs absent and all8 explicitly known fixture ports without listeners. `process-closure.json` and own model/engine closed records document their actual connection and resource shutdown. No background helper/observer remains. Test-case internal ephemeral listeners are bounded to their now-exited test JVM; no invented exhaustive historical port inventory is asserted.

Actual provider/tokenizer positive HTTP controls were retained; green focused successful/denied verifications end with providerHttp0/tokenizerHttp0/borrowed0. Own DB closure has call_log0/quota0; engine closed records retain attempts/invocations/sdkAttempts0. Actual SQL/before-after write observations are separate from static dependency evidence. `javap-private-constructors.txt` confirms the result constructor stays private; `jdeps-private-component.txt` has no ModelCalls/SecretVault/ModelExchange dependency. No dead endpoint counter is claimed to prove credential-resolver execution.

Main15173–15175/18080–18085, Redis26379, MinIO29000/29001 and all old fixture/runtime artifacts were left untouched during authorized engineering execution. No real upstream requests were made. No canonical package, engine artifact replacement/move or main lockholder operation occurred in this repair.

## Post-test evidence before fresh package

`post-test/capture.json` and `post-test/manifest.json` were written **after affected GREEN but before any package**. They preserve actual finite622 class/resource/compiler input+created-list files from common/engine/model canonical targets, including test classes, with Java major65. This is a post-test finite capture, not live loaded-class instrumentation. Actual source epochs, Running/XML, process commands, fixture argfiles and classpaths are separately retained. Maven's temporary Surefire booter/config directory had already been removed before capture; its bytes were not retained or reconstructed (`*/surefire-temp-capture.json`). Actual JVM command lines, XML java.class.path/sun.java.command and fixture argfiles remain. This limitation is not repaired by replaying tests.

Only a fresh own `package-workcopy/` containing exact final667 source bytes was packaged. Full contemporaneous actual command/cwd/source/post-test-manifest SHA/start time is `package-command.json`, separate actual exit/end is `package-exit.json` (exit0), log is `package.log`:

```powershell
mvn.cmd -o -B -ntp -f <own-fresh-workcopy>/pom.xml -pl admin-common,model-gateway,engine-runtime -am package -Dmaven.repo.local=<sole-project>/.tools/m2 -DskipTests -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

All common/engine/model package modules passed. `package-finite-manifest.json` records actual relative files; `finite-package.json` records6 JAR identities, fresh finite counts and comparison. All fresh classes/resources are byte-equal to the captured post-test files; maven-status files retain honest own-root compiler path differences. All class majors are65; JAR classes/resources equal their fresh finite outputs and neither business artifact embeds a sibling business JAR. No canonical locked engine JAR was touched. Main-runtime impact of the **original** canonical package deviation remains unknown; fresh success does not retroactively authorize it or produce historical loaded-class attestation.

Native binary scratch validation uses own before667/final667 blobs, `core.autocrlf=false` and `core.whitespace=cr-at-eol`. Actual diff/check/apply commands and results are `native-sealed/native.json`: changed3,unchanged664,zero additions/deletions, diff check0/apply check0/apply0, full667 exact byte equivalence. No old native audit was replayed.

.local/iam-model-frozen-binding-review-fix/native-sealed/change.patch — 10303 B; SHA256 `0e0f10c8eb364fc7f8c33918c5305b0fe870b876109e451711a71ec847dc7346`

.local/iam-model-frozen-binding-review-fix/native-sealed/native.json — 15521 B; SHA256 `db74c6f44854e4f053d224e58108f0a9f8487dec7b5399525bb19718421b0c6d`

.local/iam-model-frozen-binding-review-fix/post-test/manifest.json — 153596 B; SHA256 `902c7dd79466db982a27834297661132e0145928007824032650293eae366eaf`

.local/iam-model-frozen-binding-review-fix/package-finite-manifest.json — 153598 B; SHA256 `0954799991368c098484878ab8632a1bfbfc608740133490725d2ea5bc0d9fb9`

.local/iam-model-frozen-binding-review-fix/finite-package.json — 13776 B; SHA256 `70c2e3cbb7c7192d2307b5664e740d5f15a7154d18b0f47541750f02cd47afff`

## Execution deviations and evidence qualifications

**This repair did initially enumerate adjacent file/directory names.** Before receiving the explicit absolute root correction, the inherited cwd was the parent `G:/207/项目/算法重组工具`. One failed relative brief read returned path-not-found. Two `rg --files` location searches from that parent and its `代码` directory returned finite file names including `ontorepo/AGENTS.md` and a front-end-design AGENTS.md. Immediate directory listings also returned sibling directory names. No adjacent file contents/code/config were read or reused and no adjacent resource was launched, but **zero adjacent access/zero enumeration is not claimed**. Exact command/cwd/filter/exit and bounded transcript-recopied output identity are preserved in `execution-deviations.json`; output is honestly labeled transcript recopy, not independently captured stdout bytes. Both commands completed, and every subsequent shell command explicitly used the sole engineering root. No compensation cleanup or expanded neighboring scan occurred.

Own coordinator generation initially failed with Python string SyntaxError before runner/resource creation; corrected before RED setup. Own evidence summary first used Windows defaultGBK against UTF8 native JSON and failed with UnicodeDecodeError; explicit UTF8 fixed the metadata read. Both failures and stale-XML selector correction are retained in `execution-deviations.json`; none is a product failure or caused a business test/build rerun. All original task controls/report/snapshots/helpers and original root PASS audit files remained read-only.

The original task's failed canonical package/plain engine-JAR write/failed move, unknown lockholder/main-runtime impact, lack of common/engine historical loaded-class capture and successful later own-copy package remain original, immutable qualifications. This repair did not repeat or erase them. Likewise the protected8 original checkpoint stays intact: original7 exact +1 externally classified format-only change/339 normalized nonblank-line equality, actor unknown. This repair's `protected-closure.json` physically confirms **all8 equal the current root observed baseline**, not the older8 original byte hashes; it did not restore formats or attribute the external actor.

## Seal and rights return

This report is sealed before owned inventory and control. Inventory excludes itself/control to avoid recursive identity; control is written LAST and binds report/inventory/source/native/package/closure identities. Own evidence summary is `docs/evidence/iam-model-frozen-binding-review-fix/summary.json`. No source/target/DB/HTTP/helper/workcopy/evidence/report/runtime operation remains after handoff.

I return **ALL canonical source/target, owned source/snapshots/native scratch, retained DB/schema/roles, HTTP/listeners, helpers, workcopies, evidence/report and runtime rights** to root, with no background work. Fresh independent task review/root validation decides acceptance; this report does not mark Accepted or change T01-only-complete, T09/T11/T12-ongoing, T33-notstarted, v1.2/four contracts or time schedules.
