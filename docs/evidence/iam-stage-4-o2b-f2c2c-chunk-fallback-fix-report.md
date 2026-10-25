# C2c I1 nonempty CHUNK fallback correction

Status: DONE; frozen for independent review; NOT ACCEPTED. This addresses the single Important I1 in the original frozen C2c Needs fixes review. C0/I1/M1 is the inherited original review result; this author does not replace that independent decision.

## Result and exact scope

For payload {text:'',delta:'visible delta'}, eventText now selects the nonempty delta; empty text/delta falls through to nonempty content. It skips non-string fields and empty strings safely, preserves whitespace strings unchanged, and retains text→delta→content priority. Both existing real Chat/SDK consumers remain byte-identical. The production change is one predicate conjunct at web/shared/run-protocol.ts:48; the other permitted existing change is the twelve regression rows plus import in web/tests/run-protocol.test.ts.

Source644→644:2 existing modified,0 added,0 removed,642 unchanged. Full exact before/final blobs and all actual command source epochs are retained. Protected8 match the existing AFTER baseline. Native JSON reviver holder semantics, cursors/rawData/status/backfill/401/unsupported recovery and lifecycle behavior are untouched. Framework/packages/lockfiles/backend/Java/generated API/contracts remain unchanged within canonical644.

## Actual bounded verification

| Epoch | Actual result | Source meaning |
|---|---|---|
| selector-red |5 failed /46 passed /51 in one affected file |New regression tests with unchanged production selector |
| browser-red |4 expected missing-partial failures:2 Chat +2 SDK |Same RED source644; final/status held until partial assertion |
| selector-green |51/51 passed in the same affected file |Final source644 |
| browser-green |All4 affected partial-display cases passed |Final source644; final/status still held for every partial assertion |
| build-final |exit0 |Final source644; existing npm run build includes vue-tsc,3 Vite builds,SDKcopy |

Exact executable/args/cwd/start/end/process exit/child-close wait/output SHA and full source snapshots are under epochs/. Both browser results retain payloads, per-case request counts, actual partial texts/screenshots, page/console errors, final release ordering, native Chrome capability, Vite response bytes, fresh profiles and process collectors. No browser case was hidden by final output replacement. No page or console errors occurred in these affected cases. Screenshots for both Chat delta and SDK content were also visually inspected.

M1 remains: build output records2 >500kB warnings. Entry bundles are1356.97kB(admin) and1314.86kB(webapp). No suppression or unrelated bundle change. No new installation or framework upgrade. No unrelated suites, prior broad4-consumer replay, Java/PG/full-old-audit rerun. Earlier frozen C2c epochs are inherited at their real bytes, not labeled new final reruns.

## Retained finite artifacts and cleanup

Finite manifest:165records /163distinct physical input paths /163distinct snapshot paths. Two duplicate URL/content records share hashed Vite files; counts are deliberately separate. It includes actual fresh15 build/copy outputs, affected test/browser/build command records and outputs, actual Vite response bytes,8 partial screenshots and final process collector, all with byte snapshots. Cache metadata/hashes cover35 existing Vite/Vitest files only.

Both owned ephemeral ports and Chrome/Vite processes are absent in the final actual collector. Profiles and private scratches are retained; no destructive deletion. Child close was awaited and stdout/stderr drained. Primary15173/15174/15175 were observed only. The initial RED inline profile collector also matched its own instrumentation PowerShell; raw output is retained. The GREEN collector narrows to Chrome and the final independent collector confirms0 actual owned processes/listeners.

Evidence limit: canonical644 source bytes were captured before every command. The GREEN browser helper is copied unchanged after execution. The RED helper is exactly recovered by reversing the sole collector predicate edit recorded in tool history; its metadata explicitly does not claim a separately measured pre-run helper hash. This does not alter the retained RED failures, actual Vite outputs, partial screenshots or source epochs.

## Review inputs and handoff

- Full final source manifest:.local/iam-o2b-f2c2c-chunk-fallback-fix/final/source.json;123759bytes;SHA256 d666e8dc7f021659ff813e0cb196d7e7e02042a252c4bc6995b536fb59723fcf
- Complete two-path EOF package:.local/iam-o2b-f2c2c-chunk-fallback-fix/complete-eof-task-review.md;12518bytes;SHA256 2d4818845067d2a4b5d9b25c3427222706b7c06dfe7c51ba63e46e12e2c82a46
- One private native patch:.local/iam-o2b-f2c2c-chunk-fallback-fix/native-proof/task.patch;3187bytes;SHA256 fc4388db9830713322631e3661794393d33df2ad69ec81cc540b8cd0f30dccc4
- Finite manifest:.local/iam-o2b-f2c2c-chunk-fallback-fix/final/finite-final.json;86043bytes;SHA256 23b48a52449abcbba4f8128d71293529f9377b3a2963a80fc81f3e4750c62d48
- Native proof:.local/iam-o2b-f2c2c-chunk-fallback-fix/native-proof/proof.json;check/apply reproduces all644 final blobs from exact644 baseline in separate fresh private scratch. Main repository Git untouched.
- Controls:.local/iam-o2b-f2c2c-chunk-fallback-fix/final/control-identities.json;source relation:.local/iam-o2b-f2c2c-chunk-fallback-fix/final/source-relation.json;process collector:.local/iam-o2b-f2c2c-chunk-fallback-fix/cleanup-collector.json;self-review:.local/iam-o2b-f2c2c-chunk-fallback-fix/self-review.md.

Read exact original frozen source/review/brief/handoff/inventory identities from frozen-inputs.json; they remain unchanged and original owned files were not rewritten. Original640→644/17-path native/evidence epochs remain immutable. This new patch covers only the2 existing changed paths.

Limits: synthetic local affected fixtures only, no external model/real IAM/K8s/launcher/capacity/global34/JVM/whole-backend acceptance. No commits in main Git, deployment or primary restart. Independent reviewer/root review remains pending. All source/target/browser/helper/owned/evidence/report rights are returned at freeze. inventoryLAST.json is the final filesystem write and excludes only itself recursively.
