# Metadata and command interpretation

- Initial discovery `rg --files -g AGENTS.md engine-runtime admin-common` returned1 because it found no nested AGENTS. The root AGENTS was read and applies.
- One bounded read initially named `engine/domain/RunScheduler.java`, which does not exist; the actual `engine/application/RunScheduler.java` was then read. This was a discovery/read error, not a test failure or behavior red.
- A first exact-PID `Get-Process` query returned no objects/exit1 for the already-ended own JVMs. The unfinished resource metadata was then written by a bounded exact-PID/port query returning explicit empty arrays in resources.json. No tests were rerun to repair this metadata.
- Large combined read outputs were truncated; needed review tail/source ranges were read in bounded subsequent calls. Neither the output truncation nor the discovery errors are counted as TDD evidence.
- Both actual test commands completed with their real exit codes; each actual Running-class XML was copied immediately by the command helper before any later run. Final tests were not rerun for inventory, private replay, resource checks or report assembly.
- Final candidate/source preparation and actual private replay returned0. Their generated composition/journal remains the authoritative physical metadata. The inventory includes its scripts, logs, stages, replay, private git metadata, copied target delta and reports; no pre-existing broad evidence tree is inventoried.
