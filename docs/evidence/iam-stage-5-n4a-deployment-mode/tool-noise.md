# Tool/setup noise retained
- Initial capture-before was launched as a PTY session and its first returned output was empty; the script continued and completed once. Checked before.json existence; did not rerun capture or overwrite baseline.
- Bounded code reads emitted truncation in initial combined tool output; repeated only narrower reads. No source mutation or test execution in those reads.
- Initial implement.mjs failed before any source write: `AssertionError: assert.ok(at>0)` because existing SecurityConfiguration uses CRLF and lookup expected LF. Confirmed both production files unchanged; normalized newline handling in local edit script and continued only implementation. This was a setup failure, not a behavioral red.
- One read-only `Get-Process node` list was used to inspect whether baseline capture was ongoing; no process termination or main service probing occurred.

- final01 executed common53; 48 passed, 5 failed. Four positive startup child scenarios lacked the existing runner JVM short Unix-domain temp directory argument, producing Tomcat NIO PipeImpl -> UnixDomainSockets Invalid argument: connect; one receiver fixture registered its validated snapshot under its existing BFPP bean name. Fixed only child launcher argument and receiver snapshot bean name. The two production sources stayed byte-identical. Resume final02 selects only the five failed cases plus the not-yet-run engine class; no replay of the 48 green cases.
- A read of final01 runner log before runner completion returned file-not-found because the original controlled runner writes output on completion; no runtime/test command rerun resulted.

- A PowerShell here-string write of upstream-evidence.mjs containing JavaScript regex was rejected by automatic command policy before execution. No file was written. Used apply_patch to safely create the evidence script and resumed only that unfinished evidence step.

- finalize.mjs successfully froze source/target/baseline and applied/replayed complete patch, then stopped in evidence XML parsing with AssertionError 39 !== 40. A greedy regex consumed a self-closing testcase and the next testcase as one. No build/test/source phase was rerun. Resumed only unfinished evidence parsing with a reluctant attribute matcher; exact testcase count asserted against testsuite totals.
- Read-only original-design Get-FileHash used a path from the abbreviated contract text; returned file-not-found. Narrow listing of the explicitly authorized design directory locates the actual source. No document write.

- Final report generator passed node --check but its Markdown backticks inside a JavaScript template were parsed as an unintended tagged/member expression, causing ReferenceError: run is not defined at write-report.mjs:41:20 before any report or handoff write. Escaped only report template backticks and resumed final report creation; no source/target/build/test/protection phase repeated.
