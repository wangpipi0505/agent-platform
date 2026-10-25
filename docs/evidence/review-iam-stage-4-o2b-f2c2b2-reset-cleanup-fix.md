# Focused task review — terminal reset automatic cleanup

Spec compliance: **PASS for the authorized focused implementation**, with the verification boundaries below. Task quality: **Approved with one non-blocking Minor finding**. Critical / Important / Minor: **0 / 0 / 1**. This does not approve broader B2 or global acceptance.

## Scope and frozen inputs

Reviewed the task-review brief first, formal requirements, frozen-inputs, exact production authorization, repository constraints, SDD task-reviewer template, author report, handoff and complete two-path patch. Verified these physical identities by SHA-256 and byte length:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `.local/iam-o2b-f2c2b2-reset-cleanup-fix/handoff.json` | 6669 | `3ae4713e01c97e92ebebca88c9ba12c7e84e13929b213490793b848eda525d2d` |
| `.local/iam-o2b-f2c2b2-reset-cleanup-fix/review/review-package.json` | 10693 | `8c25dfb16fab44c99b5da6fc67464501a8c6fbb4cb943b25e2af0f6632bad28f` |
| `.local/iam-o2b-f2c2b2-reset-cleanup-fix/review/complete-two-path-plain.patch` | 12975 | `d9afbae32fde31069e790e49f83e75a896ba7d3a4b1416c7f63051e69e463e87` |
| `docs/evidence/iam-stage-4-o2b-f2c2b2-reset-cleanup-fix-report.md` | 10136 | `77180e9adeed75cd02d9ab268b803c9ba235c9f1bd394a307e85f5eebf46b6ec` |

The first batched tool response was globally truncated, omitting the changed-source view. A subsequent dedicated read presented the complete patch through EOF; this was the one complete review read. No separate body reread of either changed file occurred. Narrow unchanged dependencies below were read only to resolve named lifecycle/test risks. No tests, PG, native Git, replay, compilation, deployment, restart, commits, or additional agents were run.

## Spec compliance and strengths

- `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java:416`: the production delta is confined to `RequestSlot.abort`. No fields, owners, registry, threads, deadlines, budgets, configuration, hooks or contracts are added. The new locals retain interruption and successful acquisition state only.
- `PortalRunEventsSse.java:419`: the complete token/socket/processor guard is checked before every acquisition cycle. Timed waiting happens after leaving the token monitor; cancellation can therefore be observed while Tomcat retains the socket lock. The immediate acquisition at line 424 precedes deadline rejection at line 428. Timed waits at line 430 use the existing hard deadline and at most 10 ms, with no new grace period or retry owner.
- `PortalRunEventsSse.java:433`: after successful acquisition, the original socket → io → token exclusion and full identity guard remain. A disarm/recycle between precheck and acquisition cannot authorize stale CLOSE_NOW. `used` is still set only behind that final guard. The finally at line 438 releases an acquired lock and restores any observed interrupt on normal, guard-return, or action-exception exits. A preexisting interrupt remains set on immediate acquisition; an interrupt consumed by timed acquisition is remembered and restored.
- `PortalRunEventsSse.java:428`: exhausted original-deadline contention returns without inventing completion or refund. Because baseline `abortIssued` remains set, there is no assurance of another action owner or eventual CLOSE_NOW. This is a real availability/retention limitation, explicitly authorized and accurately disclosed; it is not misrepresented here as universal cleanup.
- `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseResetCleanupTest.java:55`: the new test uses the existing real Tomcat, normal TLS and signed-authority peer helper. Its completion-entry observer records the actual callback thread and original socket. A finite latch exposes a legitimate reader-abort overlap; it releases before onComplete begins its finish budget. It does not mutate production flags, counters, queues, refund state or callback outcomes.
- `PortalRunEventsSseResetCleanupTest.java:74`: real HTTP200 terminal-reset response validation precedes actual reader join and owner/baseline/TLS-slot/detachment assertions. These assertions are inside the client socket and server resource scopes, before client close, Handle close/awaitClosed or fixture teardown. The child wait and collector joins at lines 36 and 44 bound execution and preserve actual failure diagnostics.

## Narrow dependency checks and independent evidence

1. **Risk: bypassed completion, worker, action or physical cleanup gates.** Read unchanged `PortalRunEventsSse.java:234-367`. Reader finalization still calls stop before worker-body-exit; stop decrements actions in finally; onComplete disarms before publishing completed and calling finish. Finish waits for completed/actions/heavy, elects finalizing, then performs actual timed Thread.join and verifies no live worker/slot before release and session close/awaitClosed. The fix adds no body-return shortcut. In particular lines 341-354 establish that RED02's actions gate expires before awaitWorker/Thread.join is reached.
2. **Risk: stale processor/socket reuse or changed lock ordering.** Read unchanged `PortalRunEventsSse.java:579-618`. Processor socket and slot are volatile; actual process and recycle retain the original socket lifecycle lock, with token invalidation before delegate recycle. The new preliminary guard is advisory; the preserved final guard under actual exclusion remains authoritative. No application monitor is held during the new socket wait. Existing stop releases io before calling token.abort.
3. **Risk: test-only callback simulation, hidden manual cleanup, or unbounded observation.** Read unchanged `PortalRunEventsSseLifecycleTest.java:149-224` and `:719-775`. Server constructs real embedded Tomcat and invokes the adapter on its servlet request; normal request read timeout is 12 seconds. Manual Handle close/awaitClosed is in Server.close, reached after the new automatic assertions. SequencePeer uses a real TLS listener and decoded signed identity. The new reflection reads only state; stack and lock snapshots describe actual threads. This helper is not a joined typed MVC/engine/Postgres proof.
4. **RED02/GREEN01 selectivity.** Read both actual held and automatic-before-teardown properties, response properties, traces, exit properties and test summaries under `docs/evidence/iam-stage-4-o2b-f2c2b2-reset-cleanup-fix/`. Both held snapshots have owner1/9315526, actions1, completed=false, reader queued on the original lock and actual callback stack in onComplete beneath WrappedProcessor.process. RED02 uses blocking abort line416; GREEN01 uses timed abort line430. RED02 later has a terminated reader and callback returned to pool, but owner1/finished=false/transportClean=false. GREEN01 before teardown has owner0/3145728, TLSslots0, completed/finished/transportClean=true, closed session, terminated reader and callback returned to pool. GREEN trace places before-refund after worker-body-exit. The callback thread remains alive as a pooled Tomcat thread; callback return, not thread termination, is the relevant observation.
5. **Actual outcomes and affected checks.** RED02 raw log reports JUnit1/1/0/0 with actual automatic-owner assertion; GREEN01 raw log reports JUnit1/0/0/0. Child54192 exits1 and child55528 exits0, with waitFor=true, alive=false and collectors not alive. RED01 remains a distinct compile diagnostic, not behavioral evidence. Read the seven standalone process exits and observer properties: EOF baseline, action-retention exactly-one CLOSE_NOW/action-thread exit, abort-order worker exit, processor reuse lateActionCalls0, keepalive next request success, forced-release owners2/no invented completion/lateActionCalls0, and physical HTTP helper exit before owner0/baseline. These are seven standalone scenarios, not a seven-test JUnit suite. No successful run was repeated.

## Issues

### Critical

None.

### Important

None.

### Minor — preserve successful-run warning and diagnostic limits in acceptance

`docs/evidence/iam-stage-4-o2b-f2c2b2-reset-cleanup-fix/green-01/observer/reset-c077447c-5dd8-42ec-80ea-6a66e2874138/child-diagnostic-utf8.log:14`, `:25`, `:38`, `:51`, `:53`: successful child teardown still emits Tomcat warnings about `iam-portal-events-watchdog`, `iam-portal-events-worker-1`, and `portal-events-tls`, plus unavailable ThreadLocal and RMI leak inspection without the indicated module opens. Localized prefixes also decode as replacement characters; the raw native log is preserved. The author report's success/limitations discussion omits these diagnostics. Consequently a green summary cannot be read as clean container shutdown or complete leak inspection. Stacks show idle executor waits and child exit is recorded; these warnings do not establish retained request ownership and do not invalidate the before-teardown refund observation. **Fix:** root's acceptance should explicitly carry these warnings and the inspection/encoding limits, with any executor-lifecycle investigation separately scoped. Do not suppress them or broaden production changes merely to obtain clean output. This report preserves them without changing frozen author evidence.

## Cannot verify and exact root follow-up

- **Physical scope/build/native chain:** this code review establishes what the complete patch changes, not the full physical 639→640 source reconciliation, other638 identity relation, protected8, locked nested4/B2 fixtures, compiler inputs/created outputs, fixture resources, runtime classpath, native scratch application, inventory-last ordering or all process-state checks. Root must reconcile the frozen source/phase/cover/native/inventory records and actual selected files. Actual finite423 and historical logical2339 remain distinct; neither is a whole-current target/classpath claim. Do not rerun author-green suites to substitute for reconciliation.
- **Unforced branches:** interruption restoration and expired-but-contended/expired-but-immediately-available behavior were reviewed in source, but the new runtime scenario does not drive them separately. Root must retain this coverage limitation and the no-eventual-CLOSE_NOW guarantee. If separate acceptance later requires dynamic proof, authorize focused branch scenarios then; no such rerun is required for this task's stated scope.
- **Broader integration:** original B2 test14 with real PG/engine/gateway, cumulative native proof and final original-three-fixture review remain root's separate follow-up. C2c consumers, external IAM/K8s/default launcher/capacity/global T09/T11/T12 are not approved by this review.

## Assessment and rights

Task quality **Approved**, with C/I/M **0/0/1** and the explicit CannotVerify boundaries above. The code breaks the observed completion/abort wait cycle by observing disarm while preserving lifecycle exclusion and actual cleanup gates; the unchanged regression's RED/GREEN observations support the focused effect.

Sole permitted write was this report. Source, targets, author artifacts and controls remained read-only. All review write rights are now returned; root owns further acceptance and reconciliation.
