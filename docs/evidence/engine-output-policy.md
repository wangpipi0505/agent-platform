# Engine output policy execution evidence

Scope: v1.2 snapshot §2.5, §31 request-material invariant and runtime budgets. Work stayed inside the independent `agent-platform` project. No business service was started/stopped, no package/deployment or Git commit was performed. Runtime sources are frozen after 2026-10-20 04:39:40 +08:00; the sequential test window ended at 04:41:22 +08:00.

## Production behavior and contract

`ModelExchange.exchange` now consumes frozen `params.output` for Agent final answers and FLOW LLM nodes. `OutputPolicy` uses strict Jackson parsing (rejecting trailing JSON, duplicate keys and empty input) and networknt 1.5.6 Draft 2020-12 validation, including composition and local references. Remote `$ref`/`$dynamicRef`/`$recursiveRef` are rejected. JSON without a schema still requires a strict JSON document; `format.type=none` permits ordinary text. This replaces the previous subset limitation for model final-answer contracts; existing `JsonGate` consumers outside this scope retain their documented subset.

The frozen application config is merged with `nodeOverrides[nodeDefId]`, then the node's inline `modelOverride`. Unspecified nested settings inherit; a replacement schema replaces the old schema as a whole. An explicit node `outputContract` supplies the node format/schema and uses the same retry loop. Invalid configuration fails before model dispatch with `OUTPUT_POLICY_INVALID`: unknown or non-string mode/type, malformed output/format/schema containers, non-integral or negative `maxRetries`. Missing type defaults to `none`; missing `onInvalid` defaults to `TERMINATE`; missing `maxRetries` defaults to 2.

- `TERMINATE`: record the original model response and validation diagnostic, then throw `RuntimeFault(OUTPUT_INVALID, output, reason)` for the existing node error-policy handler.
- `PASS_WITH_FLAG`: retain original text, return `outputValidation.valid=false` with semantic/path/reason/mode/outputAttemptNo, and do not present invalid data as accepted `structured`. FLOW business output mappings retain this failure diagnostic under reserved engine metadata field `outputValidation`.
- `RETRY`: make at most `1 + maxRetries` explicit model requests. Each correction includes the previous invalid answer and validation reason. Each actual call uses the ordinary measurement, immutable request persistence and runtime budget gates. Exhaustion fails with `OUTPUT_INVALID`; it cannot enter the skill read-only retry path.

Agent JSON final output adds `structured` (the accepted parsed JSON value, including scalar/null) and `outputValidation` (`valid`, `semantic`, `path`, `reason`, `onInvalid`, `outputAttemptNo`) alongside existing `text`, `usage`, `actualModel`. FLOW accepted structured output continues through its ordinary output-slot mapping. Its complete acceptance record remains in the model invocation. `usage` describes the returned model response; cumulative paid-call usage remains in run budget consumption and per-invocation response evidence.

Tool-call intermediate rounds bypass final-answer JSON/schema acceptance. SUMMARY calls remain separately measured, persisted and charged, remove `params.output`, and never inherit final-answer acceptance or retry policy. There are no new HTTP endpoints.

## Persistence and recovery

Migration V213 adds `model_invocation.output_attempt_no` (nonnegative, default 0) and `output_validation`; uniqueness is `(node_instance_id, round_no, kind, output_attempt_no)`. Each output attempt has its own `invocationRef`: `node:<node>:<round>:output:<attempt>:<kind>`. Existing previously frozen invocation references remain unchanged when their responses are reused.

Original source material, fully measured/projected final request, original provider response, and acceptance diagnostic are separate records. The response commits before acceptance. The diagnostic commits before usage-budget rejection or a correction request. `MODEL_OUTPUT_VALIDATED` events are emitted only when first writing that invocation's diagnostic. Model-call budget debit and final-request freezing commit before dispatch. Usage is charged once per persisted response using the existing `usage_accounted` flag. Every correction is subject to existing modelCalls/totalTokens/totalDurationMs limits; a failed budget cannot dispatch the next request.

Recovery traverses the same saved output attempts and reuses persisted responses without another SDK/model request or another usage charge. Agent checkpoints no longer bypass the common acceptance path. A DISPATCHED invocation lacking a durable response is explicitly rejected as `MODEL_RESULT_UNKNOWN` on recovery; it is never silently resent. A transport failure records `MODEL_DISPATCH_FAILED` evidence in `attempt_note` and run events. The runtime does not invent a successful result for this uncertain case.

Controlled reproduction matches and replays every saved output attempt independently, with current guard/scope preflight inherited from the existing replay service. It recomputes acceptance/corrections through `ModelExchange`, creates distinct source-linked mock rows with zero SDK attempts, and performs no model HTTP calls. Tool-message reconstruction selects output attempt 0 of the next Agent round to avoid multiple-row ambiguity.

## Executed red/green checks

PostgreSQL commands loaded the independent project `.env` without printing credentials and set `ENGINE_PG_TEST=true`. Commands used Java 21 at the authorized Adoptium path, Maven repository `.tools/m2`, and NIO temp `V:/.local/tmp`. PostgreSQL tests targeted the independent `agent_platform_engine_test` database on port 25432. Pure domain checks require no database. The protocol fixtures are confined to tests and do not establish real supplier/model capability acceptance.

Base command:

```text
mvn.cmd -B -ntp -pl engine-runtime -am test -Dmaven.repo.local=.tools/m2 -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

Directed commands append `-Dtest=<selection> -Dsurefire.failIfNoSpecifiedTests=false`.

| Selection/log under engine-runtime/target | Observed result |
| --- | --- |
| OutputPolicyPostgresTest / output-policy-red.log | 6 tests, 6 assertion failures: missing termination, flag, retry, config rejection, strict JSON |
| OutputPolicyPostgresTest / output-policy-green-1.log | 6/0/0/0 |
| Agent/Flow additions / output-policy-agent-red.log | 8 tests, Agent structured missing and FLOW override unused |
| OutputPolicyPostgresTest,ModelExchangeTest / output-policy-agent-green.log | 10/0/0/0 |
| OutputPolicyTest / output-policy-null-red.log | 4 tests, explicit null policy silently defaulted |
| OutputPolicyTest,OutputPolicyPostgresTest / output-policy-boundary-green.log | 18/0/0/0 |
| OutputPolicyPostgresTest / output-policy-dispatch-red.log | 17 tests, uncertain dispatch was resent instead of refused |
| OutputPolicyTest,OutputPolicyPostgresTest,ModelExchangeTest,ControlledReplayTest / output-policy-targeted-green.log | 25/0/0/0 |
| Two self-review regressions / output-policy-audit-red.log | 2 tests, 2 assertion failures: mapping lost failure flag; token gate preceded durable validation |
| Same two / output-policy-audit-green.log | 2/0/0/0 |
| Full engine / output-policy-full-green.log | **100 tests, 0 failures, 0 errors, 0 skipped**, completed 04:41:01 +08:00; XML totals independently checked |
| RunStatisticsPostgresTest / output-policy-statistics-green.log | **2/0/0/0**, repeated sequentially at 04:41:22 +08:00; these same two tests were already included in the full-suite 100 |

The full suite includes ChatSessionsTest's four history/scope cases and RunStatisticsPostgresTest's two cases (full log lines 86–98); the sequential statistics repeat is not counted as another two distinct tests. New output-policy coverage totals 22 tests: 4 domain checks and 18 real-PG runtime checks. It covers three invalid-output modes, exact retry cap, model-call/token/time budgets, response reuse and interruption recovery, uncertain-dispatch refusal, strict JSON, schema composition/local refs, no-schema JSON, non-JSON output, skipped tool rounds, SUMMARY separation, Agent structured final results, FLOW node overrides/legacy contract/mapping, and three-attempt controlled reproduction with forbidden model HTTP exits.

Self-review confirmed no edits to root-owned ChatHistory, ChatSessions or ChatSessionsTest. Production changes are restricted to OutputPolicy, ModelExchange, small AgentDriver/FlowDriver/ControlledReplay integration, engine dependency declaration and V213. No claim is made about real provider quality or external environment acceptance.

## G1 review snapshot manifest

Paths are relative to the sole project root `G:/207/项目/算法重组工具/代码/agent-platform`. These are all files changed or added for G1; no HEAD-based diff is needed to collect the review snapshot.

```text
engine-runtime/pom.xml
engine-runtime/src/main/java/cn/agentplatform/engine/domain/OutputPolicy.java
engine-runtime/src/main/java/cn/agentplatform/engine/application/ModelExchange.java
engine-runtime/src/main/java/cn/agentplatform/engine/application/AgentDriver.java
engine-runtime/src/main/java/cn/agentplatform/engine/application/FlowDriver.java
engine-runtime/src/main/java/cn/agentplatform/engine/application/ControlledReplay.java
engine-runtime/src/main/resources/db/migration/V213__engine_model_output_policy.sql
engine-runtime/src/test/java/cn/agentplatform/engine/domain/OutputPolicyTest.java
engine-runtime/src/test/java/cn/agentplatform/engine/infra/OutputPolicyPostgresTest.java
docs/evidence/engine-output-policy.md
```
