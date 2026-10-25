# C2c exact frontend event protocol and recovery

Status: implementation DONE, frozen for independent review; no acceptance declaration.

## Result and source scope

Live SSE exposes {type,seq:string|undefined,data:unknown,rawData:string}; type is the actual frame event, rawData preserves payload text. Durable ids/cursors/watermarks are canonical decimal strings0..Long.MAX_VALUE. Shared native JSON.parse context.source decoding selects only final successful data.eventsWatermark and direct surviving data.events[i].seq holders. Payload/nested numbers retain native semantics. Snapshot rows never synthesize rawData. Unsupported native capability and malformed status use existing recoverable UI, preserving auth/error envelope recovery.

DebugPanel narrows node instance fields to string/number and updates nodes only for own known event names, preventing constructor prototype lookup. RunDetail keeps local slider indices. Chat binds observation to its captured auth-generation signal and safely extracts string chunks; SDK independent status path uses exact shared decoding and preserves named IAM error codes. Existing UI/style, approval/cancel/manual reauth/terminal/reset/unmount/host controls remain.

Exact source relation:640→644;13 existing files modified,4 added,0 removed,627 existing blobs unchanged. Source delta/native proof and complete EOF package cover all17 paths. Both task amendments are in final/control-identities.json. Protected8 match their existing AFTER identities; contracts/AGENTS/implementation brief are final read-only observations, not newly claimed before/after proofs.

## Actual RED and correction epochs

| Epoch | Actual result | Meaning |
|---|---|---|
| red-01 |2 failing assertions |null.type TypeError;9007199254740993 became9007199254740992 |
| green-01 |1 failure /79 pass |fixture migration still passed numeric chunk cursor10; fixed to string, not a product regression |
| green-02 and green-final |80/80 |original focused protocol boundaries and existing recovery checks |
| browser-01 /02 |API-route MIME fixture failures |preserved diagnostic epochs; first contained actual Chat/SDK case successes, not all-green runs |
| browser-03 |case successes then fixture timing failure |four consumers/recovery/upgrade cases passed; detail pending-reader fixture initialized too late |
| browser-generation-red |expected failure |original Chat auth invalidation did not cancel pending reader |
| browser-04 |case successes then fixture expectation failure |fixed actual reader/generation/stale-status cases passed;401 portal invalidates session and aborts observation rather than showing SDK alert |
| browser-05 |selected auth+cancel passed |portal auth clears; SDK error remains visible; unsupported capability does not mask actual401 |
| browser-iam-red → green |5 status reads →1 |SDK now preserves IAM_AUTHORITY_UNAVAILABLE code and stops retry |
| backfill-red |15 failures /24 pass |large seq rounding,13 malformed backfill acceptances, constructor inherited node state |
| browser-unknown-node-red → green |error alert →none |actual nonempty DebugPanel node list safely handles object nodeInstanceId |
| backfill-green-01 / amended-green-final |95/95 in7 files |final native holder/backfill/arbitrary field/stream/auth/observer tests |
| browser-backfill-green |actual DebugPanel and RunDetail passed |993/994/LongMAX replay values are strings, payload stays native, no fabricated rawData, slider index remains0/1/2 |

Each command retains exact executable/args/cwd/source snapshot/start-end/process exit/wait/collector/output bytes and SHA under epochs/. Each browser epoch retains actual profile/port/Chrome capability, request cursors/counts, errors, reader cancel/release, screenshots where relevant, and finally/process cleanup. Per-case earlier successes are not mislabeled final-head whole runs. Unaffected Chat/SDK were not replayed after the backfill amendment.

## Build and finite artifact epochs

Pre-backfill build-final: actual vue-tsc --noEmit +admin/webapp/assistant.js Vite builds+SDKcopy exit0;644 full source blobs and15 fresh dist outputs preserved in pre-backfill/. It remains an earlier epoch.

Amended-build-final: same required command on exact final bytes exit0.95 focused tests pass on the same final source snapshot. Rebuild was required by the new real backfill/field-narrowing changes. Both builds retain ordinary bundle-size warnings; no framework/dependency upgrade or historical127-suite rerun.

Final finite artifact manifest has87 selected physical artifacts with full byte snapshots:15 fresh final build/copy files, actual final Vite responses, final focused test/build records, final affected browser record/screenshots and actual cleanup collector. No whole node_modules/old backend/JVM target certification. Historical earlier task targets remain references only.

## Native proof, controls and handoff

One native plain+binary-capable task patch was produced in new private owned Git scratch. git apply --check then apply in separate exact640 baseline scratch reproduced all644 final blobs. Scratch baseline commits are isolated; main repository Git was not changed. Complete task review source is included to each file EOF.

- Final source manifest: .local/iam-o2b-f2c2c-frontend-protocol/final/source.json; 123759 bytes; SHA256 fb29c15ee44cdef3659132bea23c00329699785f64ea5b229965b4cb34bc17ea
- Native patch: .local/iam-o2b-f2c2c-frontend-protocol/native-proof/task.patch; 81586 bytes; SHA256 ca3c0c20974f2ee3d419507391b9985a2d6d87398ff6ae49d9af39f8a431c44a
- Complete EOF review: .local/iam-o2b-f2c2c-frontend-protocol/complete-eof-task-review.md; 131979 bytes; SHA256 9f14d08cd41bc2b43bef7172088bcb372dc22054a6a01ea653228581870646ca
- Finite artifact manifest: .local/iam-o2b-f2c2c-frontend-protocol/final/finite-final.json; 47280 bytes; SHA256 f5320298bae4b06469744356ac894e9a775088663da132259b2d74d735f54083
- Actual process collector: .local/iam-o2b-f2c2c-frontend-protocol/cleanup-collector.json; owned Chrome/Vite/listeners absent;15173/15174/15175 only observed read-only.
- Exact source relation, protected8, controls, command/browser epochs and self-review live under .local/iam-o2b-f2c2c-frontend-protocol/final/ and .local/iam-o2b-f2c2c-frontend-protocol/self-review.md.

Limits: synthetic local fixtures; no external model/real IAM/K8s/default launcher/capacity/global34 acceptance, no deployment/main restart/whole-JVM clean claim. Independent review and root audit remain pending. All source/target/browser/helper/owned/report write rights are returned at handoff freeze. inventoryLAST.json is written last and excludes only its own recursive identity.
