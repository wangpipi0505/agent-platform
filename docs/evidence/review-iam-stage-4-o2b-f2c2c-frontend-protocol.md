# Spec Compliance

- Issues found: the requested preservation of existing CHUNK behavior is incomplete at web/shared/run-protocol.ts:48; see I1. Exact sequence/status/backfill boundaries otherwise satisfy the reviewed requirements and both binding amendments.
- CannotVerify: source 640→644, 13 modified + 4 added + 627 unchanged, protected eight, finite 87, native apply proof, process cleanup, and retained test/browser/build epochs require root's separate physical reconciliation. This report does not substitute author claims for that reconciliation or advance global T09/T11/T12/17/34/30, external IAM/K8s, launcher, capacity, or JVM acceptance.

# Strengths

- web/shared/run-protocol.ts:17–44 uses native reviver source tokens and final holder identities. Only the final successful data watermark and direct final event-row sequences are converted. Duplicate keys retain native surviving-key behavior; nested payload numbers remain native, absent events stays absent, malformed rows are rejected, and snapshot rawData is not fabricated. web/tests/run-protocol.test.ts:8–40 meaningfully covers long boundaries, duplicate/nested holders, malformed rows, missing context and active 401.
- web/debug-panel/events.ts:15 and :109 preserve one optional ASCII space, actual frame type, unknown parsed payload and exact rawData. Canonical cursor checks and length/lexical comparison at web/shared/run-protocol.ts:6–11 avoid Number/BigInt state. web/shared/run-observer.ts:55–90 retains control/chunk exclusion, authoritative status recovery and terminal handling.
- web/debug-panel/DebugPanel.vue:105 guards unknown instance identifiers before coercion; web/debug-panel/events.ts:42 uses an own-key known-status lookup. These address the object coercion and constructor-name bugs without changing raw event identity.
- Actual consumers use the decoder at web/admin/src/RunDetail.vue:22, web/debug-panel/DebugPanel.vue:72, web/shared/Chat.vue:155 and web/assistant.js/src/main.ts:332. Chat combines the captured auth signal at :138; SDK preserves error codes at :161. The local replay slider remains an array index at web/admin/src/RunDetail.vue:140.
- web/tests/stream-protocol.html:8–25 mounts the real components/SDK. Browser scenarios at web/tests/stream-protocol-browser.mjs:59–126 assert exact reconnect cursors, authority/manual recovery, approvals, EOF behavior, native capability error, generation/unmount reader closure, late status rejection, cancellation and displayed backfill rows. This is meaningful integration code, though its execution/epoch identities remain root checks.

# Issues

## Critical (0)

- None found in the task delta.

## Important (1)

- I1 — Preserve nonempty CHUNK field fallback. web/shared/run-protocol.ts:48 returns the first string even when it is empty. For valid payload {"text":"","delta":"visible fragment"}, it returns ""; similarly {"delta":"","content":"visible fragment"} loses content. Both web/shared/Chat.vue:163 and web/assistant.js/src/main.ts:341 then append nothing. The native patch shows these callers previously used text || delta || content || "", so this is a task-introduced loss of accepted incremental content, contrary to the explicit CHUNK-preservation requirement. Final authoritative output may later replace the display, but until then the stream is silently missing valid text; on failed recovery the partial answer stays incomplete. Select the first nonempty string while retaining unknown-value narrowing and without trimming whitespace. Add a narrow regression for empty text→delta and empty delta→content, plus non-string skipping/whitespace preservation, and exercise the affected Chat/SDK display path. Static finding; no reviewer test rerun was performed.

## Minor (1)

- M1 — Retained final build output is not warning-free. .local/iam-o2b-f2c2c-frontend-protocol/epochs/amended-build-final/output.txt:21 and :35 report chunks over 500 kB; the corresponding entry bundles are 1,356.97 kB and 1,314.85 kB at :19 and :33. This is a recorded build/performance follow-up, not evidence that this protocol task introduced the bundle size or a reason to broaden its scope. Keep the warnings explicit in acceptance and handle the bundle budget in a separately scoped follow-up rather than suppressing the warning or rerunning builds for cosmetic cleanliness.

# Assessment

- Task quality: Needs fixes. C0 / I1 / M1. The native precision and event-boundary work is narrow and well covered; the shared text selector introduces one concrete behavior regression affecting both Chat and SDK.
- CannotVerify: the reviewed test sources do not cover I1; author runs cannot prove that missing case. The required follow-up is the bounded failure-first regression and affected-consumer verification after the minimal selector fix, with a new source/build evidence epoch as needed. No broad suite/browser/native/PG reruns were performed by this reviewer.
- CannotVerify: actual browser/runtime/console outcomes, final-source epoch equality, native check/apply and physical cleanup are root-owned checks. The browser harness retains resource console errors but filters generic resource-load messages at web/tests/stream-protocol-browser.mjs:54; root should classify retained messages against the intentional HTTP-error scenarios rather than infer an entirely empty console from the filtered assertion.

# Reviewed inputs and checks

- Read complete final-source package exactly once in disjoint ranges 1–400, 401–750, 751–1150, 1151–1550, 1551–1950, 1951–2250, 2251–2550, 2551–2569 (EOF); all 17 EOF markers reached. This package contains final complete sources, not the native diff. Package: .local/iam-o2b-f2c2c-frontend-protocol/complete-eof-task-review.md, 131979 bytes, SHA256 9f14d08cd41bc2b43bef7172088bcb372dc22054a6a01ea653228581870646ca.
- Read native task patch exactly once through EOF in disjoint ranges 1–350, 351–650, 651–880, 881–970, 971–1041, following a hunk-header-only index. Patch: .local/iam-o2b-f2c2c-frontend-protocol/native-proof/task.patch, 81586 bytes, SHA256 ca3c0c20974f2ee3d419507391b9985a2d6d87398ff6ae49d9af39f8a431c44a. SHA256 identities were read independently; no Git command ran.
- Read formal/reviewer briefs, local reviewer template, frozen inputs, both amendments, author report and handoff. Read AGENTS.md and applicable review skill guidance. No changed physical source was separately reread; package-only metadata extraction established exact file line references.
- One named unchanged dependency check: web/shared/content.ts:3–19, to determine whether unknown event output forwarded by Chat/SDK can break finalAnswer/outputText/markdown handling. Its null/scalar/object serialization and sanitization preserve the required safe output path; no additional dependency crawl was needed.
- Inspected only the retained amended final build output for the reported warning finding. No test, build, browser, native Git, PG, process mutation, source edit, or adjacent-project access occurred. Every shell command used G:/207/项目/算法重组工具/代码/agent-platform explicitly as cwd.
- Sole writable path was docs/evidence/review-iam-stage-4-o2b-f2c2c-frontend-protocol.md. This report is frozen on completion; its sole write right is returned to root. Exact report bytes/SHA are returned separately to avoid recursive self-identity.