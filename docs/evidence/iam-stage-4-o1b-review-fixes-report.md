# O1b focused review fixes — implementation handoff

Status: DONE for the approved narrow implementation; independent re-review and root audit remain required. No commits, deployment, package command, schedule, contract or version changes. Sole shell cwd: `G:/207/项目/算法重组工具/代码/agent-platform`. No adjacent old code/config/data/cache/process reads, reuse or writes.

## Scope and approval

Read the formal review-fix brief, original O1b brief, output-budget supplement, frozen independent review to EOF, project AGENTS/implementation brief/contracts README, and the SDD implementer template. Used receiving-code-review, TDD, verification-before-completion, Ponytail and Java guidance. Root approved the exact two-file seam before the first production edit. No subagents were started. Source baseline was all579 names/bytes/SHA equal to frozen O1b, initial delta0. Original source base SHA `a956230f2e97c552e026196748fb67519c77acc1bcfaeee747df744cac25f8de`; target1968 base SHA `06e1fa0bd59c8e95e0f54a11cc59960d48f5f3d6670d958be4950decc49932f6`.

Only changed source:

1. `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java`: private `StatusBuffer()` preallocates exactly `STATUS_MAX_BYTES`. Existing count guard prevents inherited writes from growing the backing array. Formatted the touched status/buffer methods; preserved full response, raw numeric handling, legacy/POST branches, actual-stream acquisition followed by same-frame proof check, and partial-I/O primary/suppressed handling.
2. `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java`: shared snapshot uses actual `from`/`to`; added actual retained-array regression and a production pre-persistence FLOW validation proof; changed HTTP budget instrumentation to actual capacity; completed forwards stop the original fixture dispatch. Formatted touched fixture/budget/dispatcher methods.

No TraceStore/RunService/Json/common/contract/POM/migration/frontend source changes.

## Meaningful TDD evidence

Exact launcher commands, ROOT cwd, runner SHA, timestamps, exit codes, command-before full source manifests/deltas, raw logs and immediately copied actual `Running`-class XML are in `iam-stage-4-o1b-review-fixes/commands.json` and `.local/iam-o1b-review-fixes/{red01,final01}/`. Source579 was checked unchanged across each complete command. The fixed runner used Java21 and offline own `.tools/m2`; credentials were not printed.

RED actual command:

```text
node .tools/run-iam-module-tests.mjs iam-stage-4-o1b-review-fixes-red01 engine-runtime RunObservationStatusHttpPostgresTest#retainedBufferCapacityNeverExceedsBudgetForAdversarialWriteShapes,RunObservationStatusHttpPostgresTest#sharedSnapshotPassesProductionFiniteFlowAndAdmissionDagGates
```

Exit1, tests2/failures2/errors0/skipped0. The unchanged production buffer accepted count2,056,000 from 8,000-byte batches while its actual array was4,096,000 bytes, exceeding2,097,152. The same shared snapshot passed the finite type/dependency gate but actual RunService.createInternal:502 rejected its old source/target edge as UNKNOWN_EDGE, rather than reaching the pre-persistence sentinel. These were assertion failures of the intended behavior, not setup, reflective-access or compilation failures.

GREEN/final actual command, run once after all source changes:

```text
node .tools/run-iam-module-tests.mjs iam-stage-4-o1b-review-fixes-final01 engine-runtime RunObservationStatusHttpPostgresTest
```

Exit0, tests19/failures0/errors0/skipped0, BUILD SUCCESS. This is the corrected affected class's prior17 methods plus the2 new methods, not a combined old suite. No common/O1a green tests were rerun. The class already contains its directly relevant original POST-purpose/limited legacy construction and partial-I/O checks; no separate legacy/POST run was needed because product response policy did not change.

Capacity was observed by reflecting only the private constructor of the project-owned StatusBuffer, then standard `ByteArrayOutputStream.writeTo` with an OutputStream that inspects the actual array length passed to its bulk write. No JDK private fields, Unsafe, module flags, new product diagnostics or generic framework. Actual final capacity stayed2,097,152 for batch8000, single-byte and mixed31/8000/8191 writes, including exact and +1 rejection checks. Real PG/HTTP budget test measured full UTF8 envelope2,097,152 bytes successful and2,097,153 bytes rejected with403/RUN_STATUS_TOO_LARGE, no status business fields, and positive payload reads; Chinese/newline/quote/backslash data remained covered. Its actual backing-capacity maximum was2,097,152.

Preallocation retains a2 MiB array even for a small successful status. `toByteArray()` separately allocates a bounded response copy up to2 MiB. The cap covers the serialization backing array, not total request heap, the pre-read material graph, Jackson internals or all temporary allocations.

The production gate proof uses that same shared snapshot with `GraphRules.requirePublishedFlow`, then existing legacy `RunService.createReservedSessionTurn(..., null)` and acceptedReservation=true, with no requestId/dependencies/models. A dedicated TraceStore subclass overrides its existing atomic supplier entry to throw a unique sentinel before any persistence. Real preparation/input/DAG code executes; the corrected graph reaches that sentinel and an intentionally old-shaped edge still gets actual UNKNOWN_EDGE. SQL statements, provider calls and scheduler start/reserve/created actions are asserted0; the own scheduler is only bound and closed, never started or permitted to claim/wake workers. This is finite pure-gate plus real pre-persistence DAG acceptance evidence, not an IAM/public admission success chain. The intentionally unsupported FLOW remains a separate original negative.

## Review dispositions and truthful limits

- I1 fixed by the private fixed-capacity array plus meaningful actual-capacity red/green and HTTP boundaries. Existing serialization, proof and partial-I/O behavior remains covered by the affected class.
- I2 fixed by the corrected shared graph and production gate proof above. Earlier store-created status tests are not relabeled as public admission success tests.
- M1 corrected by `iam-stage-4-o1b-review-fixes/historical-evidence-supplement.md`: original green01 raw passed but no valid stage XML was preserved; its misfiled XML is red02 and excluded. Original green02 has raw only. All original artifacts/report remain immutable; no retest fills historical gaps.
- M2 fixed only in the fixture: after nested forward completion, preHandle ends the original dispatch. Actual raw-query/bare?/body/dispatcher test passed and completed-forward flag was asserted; new final raw has no Stream closed stack. Production exception handling was not broadened.
- M3 remains a Minor limitation. Named Jackson2.19 JsonFactory bytecode API inspection confirmed setOutputDecorator is deprecated. This test uses the fixed final Json.mapper factory for its real compose/serialization windows; a modern builder replacement would replace that mapper or expand production scope. No warning suppression/reflection workaround was introduced. New final raw retains this diagnostic, RunAuthorityStore deprecation, ContextProjection and old FlowMaterialStatus unchecked diagnostics, and existing Windows output encoding loss. Flyway reports existing dedicated schema217 up to date, not migration failure. The partial-I/O WARN remains the expected asserted injected write/close behavior; bytes already sent cannot be recalled. No claim of pristine output.
- M4 remains a Minor coverage gap. No new coordinated snapshot/manifest/authority history rewrite was used. Mutating appCode/appVersionId/versionId/appVersionKey in an already accepted immutable snapshot would first demonstrate payload-digest rejection; existing control binding checks can reject other single-row version changes before that specific content branch. Such mutations cannot honestly establish every post-digest relationship branch, so no misleading extra coverage is claimed.
- M5 partially addressed within approved ownership: touched controller and fixture methods were formatted. The unchanged compressed TraceStore/RunService methods remain a Minor readability debt; no unrelated broad refactor.

Original partial-I/O oracle history is preserved. The final class still proves actual128 peer business bytes and MVC's rejected delegate-close/suppressed exception, with actualBytes=unknown in the controller; it does not claim physical forced disconnect or continuous authorization inside one blocking write. No O1c actual gateway hop, O2 stream lifecycle, full Boot, real IAM/model/ontology/K8s, RR1/RR2, full project or global security acceptance.

## Seals, replay and resources

Final source579 has exactly2 physical source deltas. Target composition inherits frozen1968 and hashes234 bounded actual engine compiler-created-list/resource/Running-report candidates, yielding logical1972 entries and16 physical target deltas. No old target/JAR/node_modules/full protected inventory walk. `composition.json`, `source-after.json`, `source-delta.json`, `target-candidates.json`, `target-after.json`, `target-delta.json` and the own target overlay record the exact chain. Interim red class files were not separately archived; its command-before source/raw/immediate XML are preserved. Final compiler outputs are the applicable sealed targets.

Actual private replay completed first check0 and first apply0, from ROOT cwd with explicit own `--directory=.local/iam-o1b-review-fixes/replay`, private bare evidence git and private info/attributes `* -text`. Full579 logical names/bytes/SHA exactly equal final source; only2 changed files are physically replayed and577 unchanged entries inherit the exact frozen base. No commits, real git config/index modification, successful-apply retry or manual byte overwrite. See source.patch, replay-journal.json, replay-summary.json and replay-logical-source.json.

Both real test JVMs reported O1B_RESOURCES_CLOSED=true. Exact logged JVM39240/engine53211 (red) and JVM35240/engine56474 (final) were absent/no listening sockets at the bounded post-run check. The fixture closes HTTP context, runtime, scheduler, dependency HTTP server, http clients and authority resources. Authority/dependency ephemeral ports were not logged and are not invented. Dedicated database agent_platform_iam_o1b_status_v12 remains; shared PG service and other processes were preserved. See resources.json.

Own inventory and final handoff seal are `iam-stage-4-o1b-review-fixes/inventory.json` and `handoff.json`; their schemas explicitly exclude their own two self-referential files from the inventory while the handoff hashes the inventory. All source/target and own local/evidence/report writes are frozen and returned to root with this handoff. No further green run is needed for metadata or reviewer inspection.
