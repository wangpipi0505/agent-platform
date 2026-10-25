# Isolated initial stream component — implementation handback

Status: **ALL / noBackground / NOTAccepted**. This is an implementation and evidence handback for independent review, not an acceptance decision or default deployment activation. Root must qualify the source, execution controls and review package independently. Existing execution history remains current C0/I2/M2 and historical I1; this work does not waive or repair it. No project task/status promotion is made.

All paths below are relative to `G:/207/项目/算法重组工具/代码/agent-platform` unless an absolute path is shown. Owner evidence base is `.local/iam-model-initial-stream/` (B). Machine-readable final identities are in B/handback.json; B/inventory.json records finite evidence identities, B/control.json records scope and control qualifications, and B/review-package.zip contains the explicitly listed public review inputs. Private configuration, credentials, tickets, TLS keys, Git stores and dependency caches are excluded from the review ZIP. Their owning evidence remains local where needed; a reference does not imply that its content was copied into the ZIP.

## Scope and implemented behavior

The input is the independently qualified 677-file code baseline, manifest SHA256 `039e79c10827671a6916c72185a844dcff818cc311f0b258512dd5c87a7404c1`. Final source is exactly 680 files: four modifications, three additions, 673 unchanged, zero deletions. Final manifest B/final-source/source.json SHA256 is `00e5b91d7072adbd54cd41d01aec2aff40005d013f72df81eabf9426610d9868`. The final freeze precedes the final source-bound tests. No canonical source changed after that freeze.

Modified production files are ModelController.java, ModelCalls.java, ModelStore.java and FrozenRequestConnector.java in model-gateway. Added tests are InitialStreamIntegrationFixture.java, InitialStreamIntegrationTest.java and infra/InitialStreamSendFactsPostgresTest.java. No POM, dependency, schema, DTO, IAM framework, engine implementation or unrelated production file changed. The connector expansion was explicitly authorized by `.local/iam-model-initial-stream-scope-addendum.md`: its new ClientHttpConnector overload borrows the supplied connector; the original BiConsumer constructor retains its default delegate behavior. The change does not claim that legacy default clients now have the new lifecycle.

The explicit five-argument ModelCalls component constructor requires store, quota, initial verifier, frozen binding verifier and DataSource. The existing two-argument constructor remains the default @Autowired assembly. **Default production IAM/D3 remains closed** under existing common security configuration. The integration receiver explicitly assembles production components within the isolated fixture. This proves that component assembly, not production activation, EngineRuntimeClients migration, or security coverage for every other model profile/action.

The initial-only component refuses the unselected legacy stream handler before Actor, emitter, material, credential, quota or model work. The selected handler requires exactly the initial profile and one materialRef; duplicate, conflicting and unknown selectors are rejected. Same genuine ticket plus stripped markers and valid unrelated legacy JSON cannot downgrade into legacy execution. Direct legacy ModelCalls streaming use is also denied in the configured component.

The selected servlet handler checks the actual current authorization frame before reading the bounded body, reacquires genuine initial material and frozen binding, uses current tenant credential resolution/SecretVault, builds the native Spring AI prompt, and observes the actual SDK encoded bytes through the production FrozenRequestConnector. Servlet and SDK guard callback ownership are asserted as the same real thread. Current proof is reacquired after waits and before tokenizer/provider egress, each response chunk, and final completion. No proof or credential authority is delegated to a worker.

The tokenizer receives the full semantic SDK request, must acknowledge its semantic SHA for every supported format, and returns a strictly positive integral count. Admission is model-specific, same-origin, bounded to 64 KiB, without redirects, and has a 10-second whole-response deadline. Actual transport starts on the owner thread; its bounded body subscriber/future is cancelled on expiry or overflow. This fixture establishes the protocol and enforcement, not vendor tokenizer accuracy or calibration. The display estimate/factor is not the admission count.

Quota intent precedes a real short PostgreSQL transaction containing SENDING insertion and quota sending CAS. A local current-proof check runs before commit. Provider dispatch requires confirmed commit and the same owner/current proof. Raw SDK wire is persisted as a JSON string inside existing metering_snapshot.initialRequest. Decoding that string to UTF-8 is compared to actual provider bytes. Accepted canonical SHA, raw SDK SHA, semantic SHA, native prompt state and JSONB presentation are distinct. Request-specific facts are excluded from the display calibration key.

Commit acknowledgment loss retains preknown reservation/tenant/invocation/send-sequence correlation and an unresolved recovery state. A real commit-then-throw can leave SENDING plus SENT without a returned log ID; it cannot produce a false NO_SEND, refund or retry. Existing recovery APIs do not resolve this SENT/unknown-usage condition; that limitation remains explicit. A real rollback has a separate proven no-send refund path.

Streaming is pulled and flushed on the servlet owner with bounded cancellation. Only a confirmed normal `stop` terminal reason produces durable successful completion/final. `length`, `tool_calls`, `content_filter`, unknown reason and ordinary EOF without a legal terminal fail, preserve sent facts, and do not retry/refund. Missing usage remains UNAVAILABLE. Controlled usage 64 + 2 = 66 verifies propagation only. Owned provider HttpClient resources use cancellation/shutdown and bounded termination checks; plain HttpClient.close is not asserted to have an inherent deadline.

## Actual test executions and source identities

B/tests-union.json is the selected actual-method union, not a count of copied XML files. It contains **26 final-source executions: 10 DIRECT_JUNIT and 16 Surefire**, plus **two independent pre-final SendFacts executions**. DIRECT_JUNIT uses the cached pinned JUnit Platform Launcher with the real @Test and Before/After lifecycle. Its raw testStarted/testFinished/uniqueId/result/throwable events and custom derived XML are explicitly labelled; they are not Surefire XML or fabricated Running lines. Matrix scenarios inside one method are not counted as separate JUnit tests.

The final DIRECT_JUNIT groups all bind the final source manifest in their test.prelaunch.json and each contains one successful method:

| Group under B | Actual command time | Evidence/behavior |
|---|---:|---|
| final-tokenizer-drip | 23.334 s | Whole-body drip timeout; actual 504; tokenizer peer sees application cancellation before fixture/process teardown; zero quota/provider/SENDING |
| final-provider-error-direct | 11.485 s | Actual 503; exactly one provider dispatch despite SDK retry facilities; FAILED + SENT, no refund/retry, request closes |
| final-disconnect | 11.101 s | Client TCP RST after a complete first event; servlet owner returns in 221 ms; FAILED, one dispatch |
| final-terminal-length | 11.065 s | Non-stop terminal fails without final |
| final-terminal-filter | 11.027 s | Filter terminal fails without final |
| final-terminal-tool | 11.079 s | Tool terminal fails without final, initial zero-tools contract retained |
| final-terminal-unknown | 10.809 s | Unknown terminal fails without final |
| final-controlled-usage | 10.343 s | Controlled 64 input + 2 output = 66 propagation |
| final-positive | 12.129 s | Genuine HTTP positive, exact durable raw wire, chunk/final, absent usage UNAVAILABLE, owner callback, same-ticket downgrade and duplicate zero-extra-send |
| final-provider-eof | 10.414 s | Missing finish reason/EOF fails without final and retains unavailable usage |

The tokenizer drip method timeout is 27 seconds, the outer command guard is 30 seconds, and production tokenizer timeout remains 10 seconds. These are distinct bounds. The observed entire HTTP request was 14.341 seconds including material/authorization/other work, not a claim that all ingress work finishes within the tokenizer-only deadline. Prior failed runs are retained below.

Final Surefire selections are the actual names in B/tests-union.json and their original XML:

* final-legacy-stream-regression: three ModelCallsPostgresTest methods, command 13.795 s.
* final-quota-regression: two ModelQuotaPostgresTest methods and one ModelQuotaRedisTest method, 10.587 s.
* final-wire-guard-regression: four accepted wire guard methods, 11.934 s.
* final-native-prompt-regression: two FrozenInitialPromptTest methods plus genuine producer HTTP guard method, 10.233 s.
* final-wire-lifecycle-regression: two wire/current frame/thread/expiry/interrupt methods, 10.520 s.
* final-authority-wire-regression: **ServiceAuthorizationTest.retainedInitialUsesOnePreHandleQueryAndOneExplicitRefreshThenClosesOnHttpAndAsync**, one actual common MVC case passed. The overall mixed-module command failed because its subsequent two model methods encountered NoSuitableDriver setup errors; that overall command is not called successful. The two model methods passed in the separate final-wire-lifecycle-regression JVM. This is the concrete MVC security-route coverage, supplemented by the real authenticated initial receiver tests.

Only the first six methods use **ADAPTED_FIXTURE**: the three original old test files have an isolated regression-workcopy adaptation confined to @BeforeAll DB URL/user/password and Redis port configuration. Canonical old tests are unchanged, and all @AfterAll and business test bodies are byte-identical. B/regression-overlay.json and the three overlay-*.patch files contain exact original/adapted identities and contextual diffs. Passwords came from group-private configuration/environment, not source/evidence text. These tests are not claimed to have run unmodified canonical fixtures. The regression overlay is separate from the exact 680-file package source and its original canonical test JARs.

InitialStreamSendFactsPostgresTest is **not merely an integration helper**. Its two actual independent JUnit executions used the same registered own DB `iam_model_initial_stream_4ce90a55ca7a4c15ad2d992118d1511c`:

| Group | Actual method | Command/result |
|---|---|---|
| green-commit-unknown-1 | realCommitThenAckLossPreservesSendingSentAndNeverRefundsOrDispatches | 23.634 s, 1/0/0; real commit then Connection throws; SENDING + SENT and exact facts remain; dispatch 0, no refund/retry, unresolvedRecovery=true |
| green-rollback-1 | realSqlAbortRollsBackSendingAndRefundsOnlyProvenNoSend | 17.083 s, 1/0/0; actual PostgreSQL division-by-zero abort; no SENDING/provider, NO_SEND + RECONCILED |

Those two runs are **PRE_FINAL_SOURCE_SEND_FACTS**, with exact immutable compiler inputs in B/commands/initial-production-compile-1.inputs.json and B/compile-input/, and actual per-group command/arg/helper bindings. ModelCalls then had SHA `0c61d3cc364f49877c41a028e7eab775b5e93c478d240c06b21824fbe54bffbf`. ModelController, ModelStore and FrozenRequestConnector identities from that compile remain final. Later ModelCalls changes strengthen tokenizer full-body transport and response terminal validation; they do not change the injected commit/rollback transaction branch. Final positive and tokenizer drip separately bind the revised transport. The two fault results are not presented as re-executions against final source.

Additional successful pre-final evidence is retained, not silently promoted into the final union:

| Group | Actual one-method execution | Internal scenarios / boundary |
|---|---|---|
| green-tokenizer-1 | tokenizerRejectsBadFullRequestCountsAndAcknowledgementsBeforeReservation, 25.686 s command | Eight modes: missing/wrong ACK, fractional/zero/overflow count, response 65,537 bytes, redirect, context overflow; all reject before quota/provider/SENDING |
| green-current-1 | actualCurrentLossDuringTokenizerAndReservationDeniesCompletion, 23.736 s | Four actual current-loss cases: revoke, narrow, outage during tokenizer and revoke after reservation; no provider/SENDING, proven last reservation NO_SEND |
| green-body-bounds-1 | badSelectorsAndSlowBodiesStopBeforeMaterialOrModelWork, 19.109 s | Three selector rejections; silent body bounded; active 150 ms drip returns 408 after monotonic 2,060 ms; 2 MiB + 1 byte returns 413; zero material/tokenizer/provider/log work |
| green-flush-3 | firstChunkFlushesBeforeProviderFinalAndResponseRevocationStopsFinal, 19.219 s | Real full legal client chunk plus blank line precedes provider final gate release; then actual IAM revoke produces error and no final |

These groups use their archived compile inputs and group command bindings. ModelCalls after the bounded-tokenizer update and before terminal validation was SHA `25b6864b64fbd33a7b9edfa4e660d74f3e0abc2c00b14604c4083ec7ed25dff8`; final is `afeb70b84d289861fd2d038ecb92e83fd58a6ab0c79daafcd172e41eb36e8eb8`. Final production delta from these pre-final groups is the normal terminal/EOF validation after response retrieval. It cannot bypass the earlier selector/body, tokenizer admission, current-loss or revoked-response rejection branches. The altered successful terminal behavior is directly covered by final positive/EOF/non-stop groups. B/compile-input contains full immutable historical bytes; the report does not assert every test used the final source.

The body loop checks elapsed time before and after each read and caps bytes at 2 MiB + 1. Blocking silent reads also depend on the isolated Tomcat 1-second socket/upload timeout. Thus total ingress bound includes the configured blocking-read timeout; D3 deployment must provide corresponding container limits. No fake per-request timeout setter or async authority handoff was added.

The first-flush timestamps are UTC: provider first flush `09:54:44.4165078`, second nonterminal text flush `09:54:44.4175092`, complete client chunk `09:54:44.7949347`, final gate release `09:54:44.8144548`. The client latch triggers only after a full legal SSE chunk and blank separator. Fixed Spring AI 1.0.0 internally uses buffer(2,1), so the fixture sends two real textual nonterminal deltas before holding final. This proves early delivery after the SDK's required lookahead; it does **not** promise immediate delivery of a single provider delta. The bytecode inspection and failed one-delta fixture attempts remain retained.

## Failed and intermediate results retained

Only actual assertion failures against the working HTTP path are behavioral RED. Setup/compile/deadline failures remain separately labelled:

* Prerequisite transport attempts first failed on child UDS configuration, then relative evidence path/child working directory; -Dbasedir did not solve actual cwd. Clean prerequisite command passed in 15.320 s. This was only pinned SDK/production connector scheduling/loopback evidence, not admission authority. Integrated HTTP subsequently proved genuine servlet owner behavior.
* Fixture Maven compile reached its 30-second guard; direct javac continuation passed. A missing common test-fixture artifact was corrected in the isolated producer build. No dependency download or canonical POM change was made.
* red-http-1 failed engine readiness due empty trust anchors. Engine actually exited 1; group closure retains SERVER_EXIT_FAILED_engine. All owned resources nevertheless closed. **Resource closure does not mean healthy service exit.** red-http-2 failed fixture IAM URI validation. Neither is behavioral RED.
* red-http-3 is genuine HTTP behavioral RED: one failure, zero errors, command 27.563 s; exact durable initial raw wire was missing. green-http-1 then passed in 17.310 s. Later final-positive binds the completed final source.
* green-flush-1 hit command guard at 30.172 s. diagnostic-flush-2 had a 20-second JUnit timeout and command guard at 30.132 s. Logs established the SDK lookahead/one-delta fixture wait. No production logic was changed to fake a flush or release final early. green-flush-3 passed with the two-text-delta latch described above.
* red-provider-eof-1 is genuine behavioral RED, 1 failure/0 errors, 22.658 s: EOF without terminal incorrectly emitted final. First fix passed in 18.794 s but allowed length; root then required stop-only. Final source-bound EOF and all four disallowed terminal groups passed.
* green-tokenizer-drip-1 hit Maven guard at 30.176 s; cancellation then is not claimed as application evidence. green-tokenizer-drip-direct-1 failed engine readiness and never started JUnit. green-tokenizer-drip-direct-2 actual DIRECT command failed in 25.542 s due its 24-second method deadline, despite observing HTTP 504, peer cancellation and no quota/provider. Those partial observations do not turn that run green. Only the later final-tokenizer-drip whole method passed with a 27-second method deadline and unchanged production 10 seconds.
* final-provider-error first Surefire attempt hit guard at 30.330 s before provider/receiver completion; it establishes no 503 behavior. The subsequent authorized same DIRECT adapter completed with one dispatch and real 503.
* First overlay preparation failed on readonly copies and was corrected only in owner files. The mixed common/model Surefire command exited 1 in 13.409 s with common 1/0/0 and model 2 setup errors; the independent model JVM later passed as above.
* First package command exited 1 in 3.460 s because readonly copied application.yml could not be overwritten by Maven resources. Owner package-workcopy attributes alone were cleared after verifying resolved containment and unchanged per-file bytes/SHA. Immutable source and captures stayed intact. Full retry exited 0 in 8.232 s; partial first-attempt JARs are not called a successful final package.
* First final-artifacts helper launch pointed to a nonexistent PowerShell path and created no process; current actual runtime path was then used, yielding exit 0 in 1.713 s. Three closure-summary attempts failed: initial policy incorrectly rejected red-http-1's historical nonzero service exit despite closed resources; the next two encountered a reused historical PID whose Process.StartTime was unavailable. The final summary uses only registered PID identity checks with exact creation time fallback; no unrelated process is stopped or inspected beyond that identity. Final closure-summary command passed in 2.011 s. A final precise identity recheck records CIM at its available microsecond precision and preserves uncertainty as failure rather than guessing.

All original logs, immutable helpers/argfiles, command outcomes and actual XML/events remain under B. A passing later run does not erase any prior failure or change earlier execution history.

## Source, compiler, package and native provenance

B/native-change.patch is a complete native binary/full-index contextual diff against the accepted 677 baseline, made in owner native-workcopy with no commit and no main repository Git operations. Independent native-replay passed git apply --check and apply; B/native-replay.json confirms all 680 replayed files match final manifest. Scope is exactly four modified plus three added, no deleted files.

Finite actual runtime classes/resources/compiler-status files were captured **before** fresh packaging in B/compiled-capture/{admin-common,engine-runtime,model-gateway}.json and corresponding full files. Engine producer capture comes from its independently built accepted-677 producer-workcopy. The exact final-680 package-workcopy has no regression overlay. Fresh common/engine/model compile commands passed in 14.150 / 17.734 / 16.270 seconds. Fresh compile bytes were separately captured before package under B/package-compiled/.

B/artifacts.json verifies all expected captured classes/resources against six final JAR archives and enumerates their ZIP entries. It also verifies all 680 package-workcopy source files against final source. The fresh test JARs contain original canonical test fixtures, not the regression overlay. The package command skipped tests; these JARs are not claimed to be the actual previously loaded test byte cohort.

Runtime/fresh comparisons are explicit: common 202 identical and 2 compiler path-list differences; engine 356 identical and 2 compiler path-list differences; model 51 identical and 32 different/absent entries. The model differences include direct-javac versus fresh Maven class bytes, compiler status lists and original test classes absent from the finite runtime cohort. Source identity and each actual compiler arg/input archive establish provenance; no blanket byte-equivalence or rerun-against-package claim is made.

## Runtime controls and closure

Prospective runtime rules are the original brief plus root runtime/scope/regression/terminal/DIRECT_JUNIT addenda. Every command child is guarded at 30 seconds, cwd is P, and each Maven obtains owner mutex `Local\AgentPlatformIamModelInitialStreamMaven` before launch. Commands were awaited to actual completion before launching the next. Executed helper and argument bytes were presealed at immutable paths; failed variants remain preserved. Test servers are separately authorized for total lifetime 90 seconds, readiness 30 and shutdown 10. These caps do not retrospectively excuse old execution history or turn commands into servers.

Only the own registered DB was created and reused (one of maximum four fresh, maximum one live). Final close command exited 0 in 3.098 s. Data is preserved: 21 call logs and 26 quota records. Sessions are zero; both owner roles are NOLOGIN and non-superuser/non-createdb/non-createrole/non-replication/non-bypassrls, memberships zero. Both roles have no USAGE or CREATE on either own model/engine schema. Shared PostgreSQL remains running and untouched.

Own Redis used only the authorized dedicated WSL distribution AgentPlatformV12 and exact `/opt/agent-platform/redis-7.2.16/src/redis-server`, with unique owner configs/data/ports. Every group has Linux PID/start/executable/config identity and exact matching closure; closing the Windows wsl.exe proxy alone was never treated as Linux closure. B/closure.json references the raw per-group Windows guards and Linux records. Known recorded Windows identities were rechecked, including PID reuse discrimination. No global process census, global WSL shutdown, old Redis config, main port, old database, locked canonical engine JAR, main Git commit or deployment was used.

Remaining limitations are deliberate and visible: default production remains closed; EngineRuntimeClients integration and broader actions are outside this slice; commit-ack unknown requires future recovery design; SDK one-delta latency is limited by lookahead; container upload bounds must be supplied at deployment; controlled tokenizer/usage do not establish real vendor accuracy or billing. Independent review and physical qualification remain pending.
