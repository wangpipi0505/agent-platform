# Engine remaining v1.2 projection work: implementation contract

This plan is a continuation, not evidence of completion. Current verified baseline is the 45-test P1 package described in engine-report.md.

## Token measurement and immutable model binding

Select the frozen reference from run snapshot `dependencyVersions.modelRefs` by the effective modelEntryId, including node-specific model overrides. Each item is `{modelEntryId,modelBindingRef:{bindingId,sha256},entry}`. Never allow runtime request fields to replace this reference. Pass only the authoritative reference to chat and measure; entry is inspection data. Preserve all sampling/output/context/timeout/budget parameters. Measure with the model service endpoint; persist inputTokens/contextWindow/maxOutputTokens/tokenizer/exact in the final request manifest. Unknown tokenizer remains an explicit refusal, not character-density admission. A conservative exact=false measurement is labeled as such.

## Projection without destroying evidence

Original model messages, full tool execution results and full model returns stay in PostgreSQL facts/checkpoints. Only the newly assembled request is trimmed. A projection manifest identifies each original message index, tool call id, character span retained or omitted, and frozen model reference. A retry with the same persisted invocation must reuse the persisted final request and projection, not recompute against changed policies.

Policy order: first trim old tool-result content to configured head/tail excerpts while retaining the assistant tool call and result message identity. Next remove only complete historical turns or complete assistant-call/result groups; never retain an orphan tool result, and never remove the current user instruction or system governance. The current in-flight round cannot be dropped as historical context. Re-measure after each material change. When configured, summarize dropped complete groups with a separately logged model invocation and actual usage budget; persist the source-range digest and resulting summary so recovery does not regenerate it. The summary is a projection named previous-context summary, not replacement truth. A final hard-limit stage may trim permitted historical content spans while preserving pair identities. If protected content plus output reserve still exceeds the measured window, fail explicitly. Do not silently shrink mandatory output reserve.

Regression cases must cover multi-tool assistant groups, tool result text containing apparent message delimiters, empty content, current-user retention, exact pair preservation, unknown tokenizer rejection, actual summary usage, restart after summary response but before main call, and immutable projection reuse.

## Debug controls and LAST_REAL

Mutable debug control management needs a row-version CAS, owner/tenant check, DEBUG-only scope and persisted control revision. FREEZE mocks may return only schema-valid envelopes. LAST_REAL lookup selects a durable non-mock successful fact with the same skillVersionId, canonical args digest and contract digest, and records the source callId/fact digest in the new trace. It must not accept a client-supplied result or use process memory as lookup authority. Formal/evaluation runs reject all mock overrides. Active claimed calls retain their frozen control revision; updates affect only later registrations.

## Sessions and replay

A conversation session requires tenant/principal/app binding and immutable message rows, with turn sequence allocated transactionally. A turn references a concrete run and the app/model/skill snapshots used; previous messages cannot silently attach to another published version or owner. Recovery uses existing run identifiers instead of creating replacement successful turns. Replay creates a new explicit DEBUG run from frozen snapshots and facts; recorded tool facts are referenced as replay provenance and never presented as a real external execution. Publishing, statistics and evaluations exclude mock/replay artifacts by authoritative trigger/provenance fields. Artifact content requires separate owner/role checks and bounded retrieval, not public storage keys.
