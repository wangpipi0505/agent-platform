# O1b historical evidence correction — M1

This supplement corrects evidence interpretation only. The original O1b report, commands, source/target manifests, raw logs, XML and independent review are unchanged. Authority: frozen `review-iam-stage-4-o1b-readonly-status.md` and the formal review-fix brief.

- Original **green01 raw log** `.local/iam-stage-4-o1b-green01.log` records its one positive test passing. **No valid green01 XML was preserved.** The XML filed under `.local/iam-o1b-readonly-status/green01/TEST-cn.agentplatform.engine.infra.RunObservationStatusHttpPostgresTest.xml` is byte-identical to the original red02 XML: tests=2/errors=1, with the budget and actual-stream-expiry methods. It is excluded as green01 evidence. The misfiled artifact remains in its original location; this supplement does not replace it.
- Original **green02** also has its actual raw log only, without a preserved stage XML. This separate missing XML is not repaired by reusing another stage's report.
- Original final04's valid XML belongs to its actual final04 command. Original final01's common/GraphRules/status/O1a coverage belongs to those actual commands. No historical command was rerun to fill either gap, and no new result is attributed to an old stage.
- This fix's red01 and final01 each copied the XML for the class actually named in that command's `Running` line immediately after runner completion. See `commands.json`; those XML files belong solely to the new fix commands.

The earlier budget sample observed `ByteArrayOutputStream.size()` (data count), not backing-array capacity. Its reported maximum cannot establish the original 2 MiB retained-array claim. The new regression observes the actual array supplied by standard `ByteArrayOutputStream.writeTo`; new final01 HTTP sampling uses that same observation. The original raw measurements are not edited or reinterpreted as capacity measurements.

The earlier source/target FLOW fixture used edge `source`/`target` and was persisted through the store seam. Those status reads exercised genuine PG/security/HTTP but did not prove a complete public admission success chain or a production-admissible FLOW graph. The new fixture uses `from`/`to`; the new finite-flow and actual pre-persistence DAG check has its own narrower scope, described in the fix report.

Original partial-I/O oracle evolution is unchanged: red03/final03's `delegateClosed=true` expectation is distinct from final04's suppressed-close/actual-unknown expectation. This fix's final01 reruns the affected class with the final oracle; it does not manufacture an old red-to-green history.
