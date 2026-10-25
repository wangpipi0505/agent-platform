# Terminal reset automatic cleanup — focused implementation

Focused implementation complete with the documented contention limitation. Final all-rights freeze is recorded by this task's handoff and inventory. No global or B2 acceptance is claimed.

## Scope and method

Read the formal task brief and frozen-inputs first, then repository AGENTS, implementation handoff/contracts, SDD implementer template, systematic-debugging, TDD and ponytail. Added only `PortalRunEventsSseResetCleanupTest.java`. Existing 639 source identities remained exact for both actual runs; phase source count 640. Existing raw `PortalRunEventsSseLifecycleTest.Server` and `SequencePeer` supply real embedded Tomcat HTTP/1.1 NIO, normal CA/SAN TLS, signed source and receiver authority. The regression observes private primitive lifecycle flags and actual threads without modifying production state.

The finite `completion-entry` latch exposes the legitimate order where reader abort has captured the original socket before completion disarms its token. The latch is released before onComplete starts its two-second finish budget. Browser socket remains open through response completion and the automatic refund assertion; manual fixture teardown is in finally. This is a focused lifecycle proof, not the joined engine/Postgres proof.

## Actual RED evidence

Command: `C:/Program Files/nodejs/node.exe .tools/iam-o2b-f2c2b2-reset-cleanup-fix-run.mjs red-02`. Node v24.16.0; JDK21.0.12; offline repository `.tools/m2`. Exact Maven command and source epoch are in `docs/evidence/iam-stage-4-o2b-f2c2b2-reset-cleanup-fix/red-02/command.json`.

- `red-01`: genuine compilation failure, Maven exit1 before tests. Diagnostic attempted `hasQueuedThread` on the declared Lock interface; corrected only the new test to cast the actual ReentrantLock. Preserved source/compiler/finite-target/process artifacts; this is not behavioral RED.
- `red-02`: Maven exit1; actual JUnit 1 test, 1 failure, 0 errors, 0 skipped. Child PID54192 exit1; waitFor true, alive false, collector joined and not alive. Child assertion is automatic owner expected0/actual1, not a parent diagnostic/charset failure.
- Child evidence directory: `red-02/observer/reset-eca48640-e79b-4528-a67c-103578abfdb7/`.
- Real public response: HTTP200, 341 total response wire bytes, 157 SSE frame bytes, stream_reset/CURSOR_EXPIRED/FAILED; cursor remains0. No response/event contents or credentials persisted.
- `held.properties`: actual callback thread60 stack `State.onComplete → AsyncContextImpl.fireOnComplete → WrappedProcessor.process`; reader74 is WAITING at `RequestSlot.abort:416 → State.stop → State.read`. Original socket ReentrantLock reports reader queued. State actions1, heavy0, completed=false; TLS slots0; owner1/9315526 bytes.
- `automatic-before-teardown.properties`: actual reader TERMINATED; original completion callback has returned to the Tomcat pool; completed=true, finished=false, finalizing=false, actions0, heavy0; session active=false, transportClean=false; TLS slots0; owner1/9315526 bytes. Only finally/manual teardown repairs to owner0/baseline3145728.
- Phase source manifests and actual finite snapshots retained: red01 finite414, red02 finite423. No clean, fake Maven success, copied old XML, old-project access, commit or deployment.

## Established cause and authorized fix

`WrappedProcessor.process` owns the original socket lifecycle lock while Tomcat calls onComplete. The reader's `RequestSlot.abort` captured that socket/token before disarm and waits unconditionally for the same lock. onComplete disarms the token, then finish cannot drain the outstanding action and returns after its finite wait. Only then can the reader acquire the lock, reject the now-invalid token, exit the action and terminate. No automatic finisher remains to release the lease. The observed actions1 means this run exhausts the action gate before Thread.join, not a join that actually began; both gates are correctly mandatory.

Root approved exact method scope and control flow in `.local/iam-o2b-f2c2b2-reset-cleanup-fix/production-authorization.md`. Sole production change: `PortalRunEventsSse.RequestSlot.abort`. Original socket/processor snapshot, socket→io→token action exclusion and final full identity guard remain. Unconditional lock acquisition is replaced by timed tryLock slices and a token/processor identity recheck before each slice, bounded by the existing State deadline. A captured waiter now notices disarm without acquiring the lock held by the completing callback. InterruptedException is recorded locally and the interrupt flag restored on exit. No new fields, deadlines, workers, registries, dependencies, configuration, public hooks or accounting changes.

Immediate `tryLock()` precedes deadline rejection, so an available valid original socket still receives CLOSE_NOW after hard expiry. Contention remaining at the original hard deadline returns fail-closed, without action, refund or fictional completion. This is **not equivalent to unconditional eventual CLOSE_NOW** and there is **no new retry owner**; abortIssued retains its semantics. Future actual container progress may observe stopping and complete, and existing completion/explicit-close paths may finish. No promise is made that every stalled contention case eventually closes or refunds. This limitation was explicitly adjudicated by root before implementation.

## GREEN and affected verification

Command: `C:/Program Files/nodejs/node.exe .tools/iam-o2b-f2c2b2-reset-cleanup-fix-run.mjs green-01`.

- Maven exit0; JUnit1/0/0/0. Actual child55528 exit0, waitFor=true, collector join returned, collectorAlive=false. The full new test bytes are identical to behavioral red02.
- Before any client close/manual Handle close/awaitClosed/fixture teardown: owner0, reserved3145728, TLSslots0, reader TERMINATED, completed=true, finished=true, actions0, heavy0, transportClean=true, session.closed=true. Actual callback is captured at its original completion entry and later observed returned to the pool. The trace adds before-refund only after worker-body-exit.
- Seven original immutable scenarios ran against the changed production using `.tools/iam-o2b-f2c2b2-reset-cleanup-fix-cover.mjs <mode>`. These are actual standalone scenario processes, not an asserted seven-test JUnit suite. Exact JVM arguments and classpath identity from final Surefire XML are recorded per command; all synchronous child process waits returned exit0 with no alive child or separate collector thread.

| Mode | Child PID | Changed-mechanism coverage |
|---|---:|---|
| eof | 37148 | Real private EOF does not invent terminal output. |
| action-retention | 34000 | Both real actions retain owners; each CLOSE_NOW exactly once; both action threads exit. |
| abort-order | 20440 | Real output/action exclusion, third admission denied while held, actual worker exit before refund. |
| processor-reuse | 46388 | Same processor on a different socket serves two pipelined pings; old action count0. |
| keepalive | 41772 | Same socket next request succeeds after real token disarm; old late abort inert. |
| forced-release | 7180 | Recycle invalidates tokens, no invented completion, owners2 retained, late actions0. |
| http-cleanup-exit | 65312 | Actual cleanup helper and competing closer must return before physical refund to baseline. |

No historical joined tests06/10/13slow/15expiry were rerun. No successful final source/check was replayed just for metadata.

## Self-review and immutable review package

Read the entire two-path plain patch through EOF. Verified method-only production scope; pre-acquisition identity check does not replace the final guard; no application monitor is held while waiting for socket lock; successful acquisition is released on every guard/action/exception exit; observed interrupts restore on all exits; actual finished/actions/heavy/completed/worker join/transport gates remain unchanged. Existing four nested production/regression paths and original three B2 fixtures remain exact. The finite latch releases before the finisher's budget starts, and failure assertions precede all manual cleanup.

The focused complete task delta is `.local/iam-o2b-f2c2b2-reset-cleanup-fix/review/complete-two-path-plain.patch` (and binary form); `review-package.json` records exact identities and native operations. Native no-index diff exit1 is expected for both complete deltas. Separate newly initialized private Git scratch trees ran plain/binary `git apply --check` and actual `git apply`, all exit0; both resulting files match current source hashes. No worktree commits or cumulative B2 proof were created.

`.local/iam-o2b-f2c2b2-reset-cleanup-fix/final/` holds full640 source snapshot, exact relation (one modified + one added, other638 exact), actual finite423 snapshot, phase index, cover index, finite delta and historical logical merge2339. The historical merge is not a whole-current target/classpath claim. Against baseline actual760, the423-path subset has3 new,9 modified,411 unchanged,340 prior paths outside this actual finite subset. The9 modified include class line-number shifts from the Sse method and actual compiler input/created lists. No target cleaning occurred.

All red01 compilation artifacts, red02 assertion artifacts, green01 artifacts, native operations, helper commands and phase process exits remain preserved. Final `handoff.json` precedes `inventory.json`; inventory is written last and includes handoff, report, helpers, source/finite snapshots and review proof. The inventory excludes only itself to avoid circular self-hashing.

## Scope limits

Root's subsequent task review and original B2 test14 rerun/cumulative proof are outside this focused implementation. The new raw real helper confirms this lifecycle path, not the full typed MVC/engine/Postgres integration. Interruption and expired-contended-lock behavior are source-reviewed branches; no separate real scenario explicitly drives these branches in this task. No claim of B2/global T09/T11/T12/T33/full capacity/default-launcher completion, deployment, primary restart or commit.
