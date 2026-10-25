# Spec Compliance: Issues found — I1 harness repair

Task quality: Needs fixes. C0 / I3 / M0. This is a task-scoped review, not an acceptance decision. The original 335-line product review and original root audits remain closed; no production defect is added here.

## Scope and evidence basis

Reviewed the absolute reviewer requirements, task brief, reviewer template and timer supplement. Read the supplied implementer report, control and complete narrow harness package. Code references below are the LF-delimited lines of exact `afterText`/`beforeText` embedded in `docs/evidence/iam-model-local-meter-watchdog-fix/harness-review-package.json`; changed files were not separately reopened. All paths are relative to `G:/207/项目/算法重组工具/代码/agent-platform`.

Verified input identities:

- Implementer report `docs/evidence/iam-model-local-meter-watchdog-fix-report.md`: 10176 bytes; SHA256 `6bde2adca47174da79302cb8f292852b28af7f1a2f62570a7cc1f8a10ba1d9bb`.
- Handoff control `.local/iam-model-local-meter-watchdog-fix/control.json`: 2092 bytes; SHA256 `92bc6615038660f649d43b8c3a17288c0411b771153611a33b70df582f2d7ff1`.
- Harness review package: 308596 bytes; SHA256 `3c5a6d986f34db8a7decf4fb462d8fbb00a497d9b69eb46919d4cac1bd3c7c23`.

Read every distinct new helper and both before snapshots, including all argument files, in bounded views. Parsed complete JSON through EOF and verified all 20 exact UTF-8 new-file lengths/hashes, both transition before/after identities, every contextual diff against its exact text, and duplicate transition after-text equality. One rendering truncated part of a repeated model classpath; an explicit equality check recovered that section: compile.args:4 and seed.args:7 equal close.args:5 after removing its exact own-helper prefix. No code was inferred from a truncated function.

## Strengths and verified compliant portions

- Shared enforcement is real and used consistently: `.tools/iam-model-local-meter-watchdog-fix-wait.ps1:39–48` validates positive caps <=30 before launching, starts the guard before launch, and has no 300-second fallback; control.ps1:11–17 and run.ps1:61–67 invoke that function for controls and each sequential Maven group. run.ps1:35 rejects timeout even if an exit value were otherwise successful.
- `.tools/iam-model-local-meter-watchdog-fix-deadline.cs:6–33` uses a concurrent collection, monotonic stopwatch, one-shot .NET timer, single-process Kill, and callback-disposal waiting. The callback does not perform CIM/PowerShell/global census or implicit tree kills; it operates on the current invocation's registered collection. Add checks Fired after insertion, covering registration racing with expiry. The observed 239 ms decision-to-return interval is not treated as a zero-jitter failure.
- The actual short controls establish meaningful failure-first behavior. `.local/iam-model-local-meter-watchdog-fix/controls/red/hung.json:9–17` records cap2, timedOut=true, forced=false, live root at2250ms; red/result.json:4–8 records healthy/invalid-cap positives and the required failed hang assertion, and :9–30 records final closure. Initial green/hung.json:14–34 preserves the 2272ms decision/2278ms return. Corrected green-timer/hung.json:9–35 records decision2000ms, no kill errors, both identities closed, exit-1 and return2239ms. green-timer/healthy.json:11–27 records exit0, no forcing,409ms; green-timer/result.json:4–8 records all three assertions passed. These are real retained outcomes, not a claim of a production hang.
- Explicit argument selectors form five disjoint groups of9/3/5/3/11 cases (`.tools/iam-model-local-meter-watchdog-fix-group-{1..5}.args:15`). run.ps1:61–73 invokes them sequentially with cap30 including startup. capture.py:6–15 binds results to actual Running classes and selected method names; :32–36 enforces31 unique cases. Whole qualification may exceed30 without violating that grouping design. Actual XML/source/closure byte audits are delegated to root as stated below.
- The final coordinator change is transparently separated from the executed31 snapshot: package transition `.local/iam-model-local-meter-watchdog-fix/before/executed-runner.ps1:74–88` to `.tools/iam-model-local-meter-watchdog-fix-run.ps1:74–91` adds an outer try/finally around final process cleanup. Its cleanup-control.ps1:7–15 intentionally throws with live owned processes; `.local/iam-model-local-meter-watchdog-fix/finally-control.json:2–24` records both forced closed in14ms, timerFired=false, simulated close failure retained and no business replay. This validates that control pattern; it does not cover every failure below or prove final coordinator bytes ran31.
- Finite artifact reuse is appropriately limited: capture.py:17–46 captures post-test classes/resources before later builds, compares against frozen own package bytes, verifies class major65 and same-path source bytes, and qualifies compiler paths/deleted Surefire temporaries. The report:39–47 retains historic limitations and distinguishes final coordinator bytes. No new package/native run is claimed.

## Important findings

### I1 — A watch-loop exception cancels the active deadline before stopping its tree

Evidence: `.tools/iam-model-local-meter-watchdog-fix-wait.ps1:49–56,61–65`; `.tools/iam-model-local-meter-watchdog-fix-run.ps1:74–89`.

Trigger: a terminating error after the child is registered but before the normal-path Stop-OwnProcesses call, such as Get-CimInstance failing at wait.ps1:19 during Find-OwnDescendants. The function's finally disposes the timer and writes evidence, but never stops its local records. In the actual caller, fixture signaling/waiting and the separately bounded DB-close invocation happen before the outer all-owned stop. Thus a failure near the original30-second deadline can cancel that deadline while its Maven/Surefire tree remains alive during up to10 seconds of fixture waiting plus a30-second close operation. A control caller happens to clean immediately, but the business coordinator does not. The new outer finally guarantees eventual all-owned cleanup if closing throws; it does not preserve the current command's deadline.

Required repair: make stopping this invocation's already registered records part of Invoke-OwnBoundedProcess's unconditional exit path, before relinquishing the guard and before returning/throwing to callers. Dispose/wait the callback and retain evidence in nested finally blocks even when cleanup fails. Add a short owned-child control that exercises an exception inside the bounded function itself; the existing cleanup-control bypasses this function. No actual escaped process is asserted in the successful supplied epoch.

### I2 — Descendant discovery can enroll a reused unrelated PID as owned

Evidence: `.tools/iam-model-local-meter-watchdog-fix-wait.ps1:16–21,6–9`; `.tools/iam-model-local-meter-watchdog-fix-deadline.cs:18–24`.

Trigger: the filtered CIM query returns a child PID, that child exits, and the PID is reused before GetProcessById at line21. The code registers whichever Process is returned and adopts its new StartTime as the expected identity, without comparing it with the CIM candidate's CreationDate or revalidating the parent identity after the query. Later timer/foreground identity checks then validate the accidentally enrolled replacement against the replacement's own recorded start time and may kill it. Checking the known parent before the query and checking a process against an identity first captured after the query do not close this gap. This is distinct from the disclosed limitation that descendant discovery is sampled.

Required repair: validate the candidate's creation identity against the exact opened process before registration, recheck the recorded parent around the query/registration boundary, and retain a stable owned process handle for subsequent termination; reject stale or unverifiable candidates. Preserve filtered-parent-only discovery. Add a narrow candidate-identity mismatch control; do not create or kill unrelated processes to test it. The current evidence does not show that reuse actually occurred, but the implementation fails the explicit no-PID-reuse/outside-kill boundary under this interleaving.

### I3 — Partial setup can leave new database roles LOGIN after failure

Evidence: `.tools/iam-model-local-meter-watchdog-fix-resources.java:28–37`; `.tools/iam-model-local-meter-watchdog-fix-run.ps1:42,84–86`.

Trigger: setup creates one or both LOGIN roles/database at resources.java:30, then a later grant/schema operation, file write or Flyway migration fails, or the setup watchdog expires before line37 writes setup.json. The durable registry already records the exact owned names at line28, but coordinator cleanup only calls close when setup.json exists. It skips close on this partial-setup path, leaving any created roles LOGIN and resources not closed according to the required exception policy. The outer process finally cannot change role state. This is a new helper-path defect, not a claim that the actual successful database remained open.

Required repair: use the already-written owned registry or a pre-create ownership record to close partially created resources on failure; handle absent roles/database safely and retain data as required. Do not depend on a success-only setup.json marker for cleanup eligibility. Verify a bounded controlled setup failure after owned role creation, or equivalent narrow failure-path verification authorized by root, without touching older databases.

## Critical / Minor

Critical: none. Minor: none. The three Important findings are separate ownership/exception paths; passing normal qualification does not waive them.

## Named outside-package checks and limits

- Named risk: actual RED/GREEN behavior and whether the new cleanup control covers the bounded wait's failure path. Read only the nine `controls/{red,green,green-timer}/{result,hung,healthy}.json` records and `finally-control.json`. Their exact observations are cited above. They do not exercise stale candidate identities, an exception inside Invoke-OwnBoundedProcess, or partial DB setup. No tests were rerun.
- Root separately reports `.local/iam-model-local-meter-watchdog-root-audit.json` PASS for2127 inventory entries/current671/protected8, actual five-group31 Running/XML union,627 post-test equality/615 frozen-package equality,33 qualification identities/44 all-epoch identities/four ports and DB closure. I did not reopen or duplicate that physical audit. Those are root-audited facts, not independent reviewer probes or proof of the exceptional interleavings identified above.
- From this package alone I cannot independently attest actual runtime PID/port/DB closure, canonical no-add/no-delete equality, all historic loaded classes, original package/native execution, or absence of all historical outside-root activity. Those remain assigned to the root audit and the original qualified historical record. Original closed inventories/335-line source/native/business RED/old73 were not replayed or re-reviewed.
- No shell outside the explicit project cwd; no parent/sibling enumeration/access, child agents, tests/build/native execution, PG/HTTP/process/listener probes, commits, goals, automations or background work. Read-only except this report. The identity/diff checks were offline evidence checks, not test execution.

## Assessment and handback

Spec Compliance: Issues found. Task quality: Needs fixes. C0 / I3 / M0. Normal control/qualification evidence supports the intended timer approach, but unconditional active-tree containment, candidate ownership and partial resource cleanup still need repair. No Accepted status is issued.

ALL reviewer rights returned: canonical-target, owned-source, DB/roles, HTTP/IAM/runtime, helpers, workcopies, evidence and this report. Only this report was written; no runtime or database rights were exercised. noBackground=true. No pending tool/process/session or child agent remains. Exact report bytes/SHA256 are returned in the handoff message after this file is sealed.
