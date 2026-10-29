# Outbox UUID isolation: collector preparation only

Prepared 2026-10-29. This directory contains dormant collectors adapted from `algorithm-fix-20261029-032753-387`, plus file-only static checks. **No Java/Maven/PG/HTTP command, .env read, product/Test source edit, index action, or repeated test sampling was performed.** The candidate pin607B is provisional pending ROOT's final source SHA, independent review, HEAD freeze and exclusive actual window. This preparation is not a PG admission or test result.

## Frozen source and single allowed delta

The planned HEAD is `83726d50a6cce116251c03c5c193080d8ed5aad2`; the already recorded env AF038 and common JAR1B0D pins are retained without reading either in this preparation. The runtime/JDK/default flags/argLine/fork/reuse/order stay as the original EX-03 command. Neither diagnostic flag nor a failure-ignore option is passed to the directed phase.

Source149 is compared against the preceding SDK43EC Full's preserved manifest. Its only allowed change is `SkillExecutionTest.java`, old14AE→candidate `607BA3C31AE5EF8DC4568E8EB35D338446A9ED444A7366285124E28BFDA17EA8`. SDK43EC remains exactly unchanged, as do the other147 files. Explicit frozen pins additionally cover ArtifactStore9DEB, SkillTaskResultTest600F, SkillOutbox30BA and SkillExecution8CD3. The static check confirmed precisely this source set and delta. No outbox author file or snapshot was rewritten here.

## Dormant actual sequence, only after ROOT grants it

1. Copy this prepared bundle into a fresh actual RunId, preserving PS1 UTF-8 BOM, and record the granted final identities before any runtime action. The collector has one-attempt guards per directed/Full phase.
2. Run `preflight.ps1` once in an independent PS5.1 process during the granted window. It restores process environment in finally; no OR credentials are loaded. PG connection/statement timeouts and read-only mode remain. It verifies loopback25432/PID15204/exact executable and V:/.local/pgdata/agent_v12, the existing algorithm database and strict legal Flyway SCHEMA/SQL classification, the two original existing migration replay databases, and the original ontology database marker. It also verifies **existing `agent_platform_v12` owner, exact skill schema/history owners, and every current skill V script exactly once** under the same strict known-type classification. Missing/unknown ownership/history stops without creating/resetting anything. Collector SQL never reads, claims, deletes or alters the old outbox queue, and issues no DDL. Only the already authorized original two migration test methods may replay their existing private skill schemas during Full.
3. Run `run-verify.ps1 -Mode Target` once. Its exact Maven selector is `-Dtest=SkillExecutionTest,SkillTaskResultTest`, default context, no `-am`, no failure.ignore. Reports are written under `targeted/`. It requires **25+3=28 exact original identities**, all PASS, native0, no SKIP, no new/missing identity, no source/context drift, **zero SDK rows/outcome/diagnostic JSON**. Identity equality is checked against EX-00, rather than inferred from a total.
4. Only after that directed gate is true, run `run-verify.ps1 -Mode Full` once under the same149-source/HEAD/env/common-JAR freeze. It consumes the directed summary/native/end/manifest and checks exact28P/no-ignore/no-skip before starting. Full retains the original failure.ignore solely for complete evidence collection. It requires70 classes/674 identities: original664+importer9+SDK handoff1. SDK's exact five identities and four safe network outcomes are required, default diagnostics0. Both target classes must still be28P. All116 original nonpasses must retain their status; no baseline status change or new nonpass is allowed.
5. Persist native exit **before** end fingerprints/XML. Fresh XML is loaded using XmlDocument.Load; suites, unique identities, selected class set, complete source manifest and source drift are checked. Native exit, collector exit, method gate and all-green remain separate. `reasonComparisonPending=true` on Full explicitly requires the following file-only review; native0 under ignore is not a pass claim.
6. Once the actual process ends, return the exclusive window immediately. Only then run `compare-nonpass-reasons.ps1` as file analysis. It preserves all116 status/type/caused-by/project-frame/message hashes against EX-00, the original EX-03 Full delta, and a separate delta against the SDK43EC Full that contained the outbox failure. Exact message differences require bounded explanation; the script does not normalize them. Original Full failures, raw XML/logs, historical PG evidence and snapshots are never replaced.

Any compile/fixture/collection/new-method failure or drift stops the sequence and is reported with fixed phase/reason/InvocationInfo.ScriptLineNumber. No source fix, assertion relaxation, repeated command or follow-up sampling is authorized by this bundle.

## Static result and evidence packaging

PS5.1.26100.9711 ParseFile reports zero errors on all four PS1 files; all19 file-only checks in `static-check.json` pass. The first preparation attempt exposed PS5.1's UTF-8-without-BOM path decoding; scripts are now UTF-8 BOM. A static-check-only JSON array wrapper was then changed to the mature direct assignment form; actual149 source comparison passed without relaxing any admission predicate. These preparation events are retained in `preparation-attempts.json` and do not count as a product/test red.

The directed output directory is named `targeted`, because the repository's general `**/target/` ignore would hide even safe summary metadata under `target`. This bundle's local `.gitignore` excludes only directed/Full raw.log and XML plus PG raw stderr; it changes no existing root/EX27 ignore or earlier evidence. `packaging-static.json` verifies raw/XML are ignored and safe summary paths remain visible. No raw files were created for that check.

| File | SHA256 |
|---|---|
| preflight.ps1 | B53EC32F0E36E526931B0E64B6287CBB7BAE6F3AA8B181D4CBA14AD18910E254 |
| run-verify.ps1 | 01F9DCC17562834F404FF9DAAF566FF615B346A6DBF05A6EE770118CE2A7C2B8 |
| compare-nonpass-reasons.ps1 | E5FD54E69CE79331CE38A23B0A8D94A25A19E7E403C1A33FB4AD4A40F9C1FB5B |
| check-preparation.ps1 | 6FFD641A3E4A033FA4EF93168A2E8B7AE98CA2C62CBE5FE2C2111B99E371AE59 |

No existing failure is waived by the preparation. Full is expected to retain baseline red items even if its method regression gate eventually passes; root/independent review and exact commit management remain separate steps.
