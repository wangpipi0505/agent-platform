package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Each selected method owns a fresh service phase against the retained owner database. */
class InitialSenderFactsPostgresTest {
    static final String SCHEMA = "{\"maxTokens\":256,\"maxRetries\":0,\"onInvalid\":\"%s\",\"format\":{\"type\":\"json\",\"schema\":{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"const\":true}},\"additionalProperties\":false}}}";
    final Path dir=Path.of(System.getProperty("initial.fixture"));
    JsonNode action(String action) throws Exception {
        try(var http=HttpClient.newHttpClient()) {
            String origin=Json.read(Files.readString(dir.resolve("engine/control-ready.json"))).path("origin").asText();
            var response=http.send(HttpRequest.newBuilder(URI.create(origin+"/control?"+action)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return Json.read(response.body());
        }
    }
    JsonNode drive(String mode) throws Exception { Files.writeString(dir.resolve("mode"),mode);return action("drive"); }
    void declaration(String value) throws Exception { Files.writeString(dir.resolve("output-declaration.json"),value); }
    void rejectedDeclaration(String value) throws Exception {
        declaration(value);var facts=drive("normal");unsent(facts,0);
        assertThat(facts.path("invocations").asInt()).isZero();
        assertThat(facts.path("budget").path("consumed").path("modelCalls").asInt()).isZero();
        assertThat(Files.exists(dir.resolve("model-ingress-events.jsonl"))).isFalse();
    }
    @Test void invalidOutputDeclarationRejectsBeforeDispatch() throws Exception { rejectedDeclaration("{\"format\":{\"type\":\"bogus\"}}"); }
    @Test void wrongTypedFormatRejectsBeforeDispatch() throws Exception { rejectedDeclaration("{\"format\":[]}"); }
    @Test void wrongTypedFormatTypeRejectsBeforeDispatch() throws Exception { rejectedDeclaration("{\"format\":{\"type\":1}}"); }
    @Test void invalidOnInvalidRejectsBeforeDispatch() throws Exception { rejectedDeclaration("{\"onInvalid\":\"BOGUS\"}"); }
    @Test void invalidSchemaRejectsBeforeDispatch() throws Exception { rejectedDeclaration("{\"format\":{\"type\":\"json\",\"schema\":{\"type\":\"bogus\"}}}"); }
    @Test void nestedInvalidSchemaRejectsBeforeDispatch() throws Exception {
        rejectedDeclaration("{\"format\":{\"type\":\"json\",\"schema\":{\"properties\":{\"value\":{\"type\":\"bogus\"}}}}}");
    }
    @Test void metaSchemaComparisonBudgetRejectsBeforeDispatch() throws Exception {
        var declaration=Json.mapper().createObjectNode();
        var schema=declaration.putObject("format").put("type","json").putObject("schema");
        schema.put("type","object");var required=schema.putArray("required");
        for(int i=0;i<100;i++) required.add("field"+i);
        rejectedDeclaration(Json.write(declaration));
    }
    @Test void hugeRetryCountRejectsBeforeDispatch() throws Exception { rejectedDeclaration("{\"maxRetries\":18446744073709551616}"); }
    void invalidAnswer(String policy, boolean schema) throws Exception {
        declaration(SCHEMA.formatted(policy));
        if(schema) Files.writeString(dir.resolve("provider-text"),"{\"ok\":false}");
        var first=drive("response-recovery");
        assertThat(first.path("attempts").get(0).path("response_ref").isTextual()).isTrue();
        assertThat(first.path("commits").asInt()).isZero();
        var raw=Json.read(first.path("durable").get(0).path("response").asText());
        assertThat(raw.has("outputValidation")).isFalse();
        var ingress=Files.readString(dir.resolve("model-ingress-events.jsonl"));
        var recovered=action("recover");
        assertThat(recovered.path("status").asText()).isEqualTo(policy.equals("PASS_WITH_FLAG")?"SUCCEEDED":"FAILED");
        assertThat(recovered.path("sends").asInt()).isEqualTo(1);
        if(policy.equals("PASS_WITH_FLAG")) {
            assertThat(recovered.path("commits").asInt()).isEqualTo(1);
            assertThat(recovered.path("output").path("text")).isEqualTo(raw.path("text"));
            assertThat(recovered.path("output").path("outputValidation").path("valid").asBoolean(true)).isFalse();
            assertThat(recovered.path("output").has("structured")).isFalse();
        } else {
            assertThat(recovered.path("commits").asInt()).isZero();
            assertThat(recovered.path("stopReason").asText()).isEqualTo("OUTPUT_INVALID");
            assertThat(recovered.path("durable").get(0).path("node_status").asText()).isEqualTo("FAILED");
        }
        assertThat(action("recover").path("status")).isEqualTo(recovered.path("status"));
        assertThat(Files.readString(dir.resolve("model-ingress-events.jsonl"))).isEqualTo(ingress);
    }
    @Test void initialInvalidOutputHonorsPassWithFlag() throws Exception { invalidAnswer("PASS_WITH_FLAG",false); }
    @Test void initialSchemaMismatchHonorsPassWithFlag() throws Exception { invalidAnswer("PASS_WITH_FLAG",true); }
    @Test void initialInvalidOutputHonorsTerminate() throws Exception { invalidAnswer("TERMINATE",false); }
    @Test void initialSchemaMismatchHonorsTerminate() throws Exception { invalidAnswer("TERMINATE",true); }
    void overBudget(boolean duration) throws Exception {
        Files.writeString(dir.resolve("provider-control.txt"),"controlled-usage");
        Files.writeString(dir.resolve("budget-limits.json"),duration?"{\"totalDurationMs\":30000}":"{\"totalTokens\":10}");
        var first=drive(duration?"duration-response":"response-recovery");
        assertThat(first.path("status").asText()).isEqualTo("RUNNING");
        assertThat(first.path("commits").asInt()).isZero();
        assertThat(first.path("attempts").get(0).path("response_ref").isTextual()).isTrue();
        assertThat(first.path("usage").get(0).path("usage_accounted").asBoolean()).isTrue();
        assertThat(first.path("budget").path("consumed").path("tokens").asInt()).isEqualTo(66);
        var ingress=Files.readString(dir.resolve("model-ingress-events.jsonl"));
        var recovered=action("recover");
        assertThat(recovered.path("status").asText()).isEqualTo("FAILED");
        assertThat(recovered.path("stopReason").asText()).isEqualTo(duration?"BUDGET_DURATION":"BUDGET_TOKENS");
        assertThat(recovered.path("commits").asInt()).isZero();
        assertThat(recovered.path("budget").path("consumed").path("tokens").asInt()).isEqualTo(66);
        assertThat(action("recover").path("budget")).isEqualTo(recovered.path("budget"));
        assertThat(Files.readString(dir.resolve("model-ingress-events.jsonl"))).isEqualTo(ingress);
    }
    @Test void overBudgetUsagePreservesResponseAcrossRecovery() throws Exception { overBudget(false); }
    @Test void durationBudgetPreservesResponseAcrossRecovery() throws Exception { overBudget(true); }
    void lostAfterResponse(String mode) throws Exception {
        declaration(SCHEMA.formatted("TERMINATE"));var first=drive(mode);
        assertThat(first.path("status").asText()).isEqualTo("RUNNING");
        assertThat(first.path("attempts").get(0).path("response_ref").isTextual()).isTrue();
        assertThat(first.path("commits").asInt()).isZero();
        assertThat(first.path("durable").get(0).path("node_status").asText()).isEqualTo("RUNNING");
        assertThat(first.path("attempts").get(0).path("status").asText()).isEqualTo("RUNNING");
        assertThat(first.path("terminalEvents").asInt()).isZero();
        assertThat(Json.read(first.path("durable").get(0).path("checkpoint").asText()).has("initialFailure")).isFalse();
    }
    @Test void revokedCurrentCannotCommitInvalidOutcome() throws Exception { lostAfterResponse("revoke-response"); }
    @Test void lostNodeLeaseCannotCommitInvalidOutcome() throws Exception { lostAfterResponse("lease-response"); }
    @Test void leaseLossAtOutcomeCommitRollsBackDerivedFailure() throws Exception { lostAfterResponse("lease-outcome-commit"); }
    @Test void oversizedPersistedInitialScopeRejectsBeforeDispatch() throws Exception {
        var facts=drive("oversized-scope");unsent(facts,0);
        assertThat(facts.path("initialScopeBytes").get(0).path("bytes").asInt()).isGreaterThan(262144);
        assertThat(facts.path("error").asText()).isNotBlank();
    }
    void unsent(JsonNode facts,int marker) {
        assertThat(facts.path("commits").asInt()).isZero();
        assertThat(facts.path("sends").asInt()).isEqualTo(marker);
        assertThat(Files.exists(dir.resolve("ingress-attempt-facts.json"))).isFalse();
    }
    @Test void realFreezeRollbackProvesNoSend() throws Exception {
        var first=drive("rollback");unsent(first,0);
        assertThat(first.path("attempts")).isEmpty();
    }
    @Test void committedDispatchAckLossNeverResendsDuringRecovery() throws Exception {
        var first=drive("ack-loss");unsent(first,1);
        var recovered=action("recover");unsent(recovered,1);
        assertThat(recovered.path("attempts").get(0).path("id")).isEqualTo(first.path("attempts").get(0).path("id"));
    }
    @Test void durableResponseRecoveryCommitsSameAttemptWithoutHttp() throws Exception {
        var first=drive("response-recovery");
        assertThat(first.path("commits").asInt()).isZero();
        assertThat(first.path("attempts").get(0).path("response_ref").isTextual()).isTrue();
        var ingress=Files.readString(dir.resolve("receiver-completed.json"));
        var recovered=action("recover");
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(recovered.path("commits").asInt()).isEqualTo(1);
        assertThat(recovered.path("sends").asInt()).isEqualTo(1);
        assertThat(recovered.path("attempts").get(0).path("id")).isEqualTo(first.path("attempts").get(0).path("id"));
        assertThat(Files.readString(dir.resolve("receiver-completed.json"))).isEqualTo(ingress);
    }
    @Test void preparationRecoveryMaySendExactlyOnce() throws Exception {
        unsent(action("prepare"),0);
        var recovered=action("recover");
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(recovered.path("commits").asInt()).isEqualTo(1);
        assertThat(recovered.path("sends").asInt()).isEqualTo(1);
    }
    @Test void revocationBeforeSendKeepsDurableUnknownMarker() throws Exception { unsent(drive("revoke-before-send"),1); }
    @Test void currentOutageBeforeSendKeepsDurableUnknownMarker() throws Exception { unsent(drive("outage-before-send"),1); }
    @Test void leaseLossBeforeSendCannotPublish() throws Exception { unsent(drive("lease-before-send"),1); }
    @Test void dispatchGenerationLossBeforeSendCannotPublish() throws Exception { unsent(drive("dispatch-before-send"),1); }
    @Test void outputRetryProfileRejectsBeforeDispatch() throws Exception { unsent(drive("output-retry"),0); }
    @Test void toolsProfileRejectsBeforeDispatch() throws Exception { unsent(drive("tools"),0); }
    @Test void historyProfileRejectsBeforeDispatch() throws Exception { unsent(drive("history"),0); }
    @Test void historicNullRawCannotBeReserializedAndSent() throws Exception { unsent(drive("historic-null"),0); }
    @Test void changedOriginalRawCannotBeReserializedAndSent() throws Exception { unsent(drive("raw-change"),0); }
    void chunkFault(String mode) throws Exception {
        Files.writeString(dir.resolve("hold-final"),"hold");
        var result=drive(mode);
        assertThat(result.path("observedChunks").size()).isEqualTo(1);
        assertThat(result.path("commits").asInt()).isZero();
        assertThat(result.path("sends").asInt()).isEqualTo(1);
        assertThat(result.path("status").asText()).isEqualTo("RUNNING");
    }
    @Test void revocationAfterFirstChunkStopsExposure() throws Exception { chunkFault("revoke-chunk"); }
    @Test void currentOutageAfterFirstChunkStopsExposure() throws Exception { chunkFault("outage-chunk"); }
    @Test void leaseLossAfterFirstChunkStopsExposure() throws Exception { chunkFault("lease-chunk"); }
    @Test void observerFailureCancelsWithoutLogicalCommit() throws Exception { chunkFault("callback"); }
    @Test void cancellationAfterFirstChunkCannotPublishOrCommit() throws Exception {
        Files.writeString(dir.resolve("hold-final"),"hold");var result=drive("cancel-chunk");
        assertThat(result.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(result.path("observedChunks").size()).isEqualTo(1);
        assertThat(result.path("commits").asInt()).isZero();
    }
    @Test void modelCommitAckLossKeepsSendingSentWithoutEngineResend() throws Exception {
        Files.writeString(dir.resolve("model-fault"),"ack-loss");var result=drive("normal");
        assertThat(result.path("commits").asInt()).isZero();
        assertThat(result.path("sends").asInt()).isEqualTo(1);
        var facts=Json.read(Files.readString(dir.resolve("model-durable-facts.json")));
        assertThat(facts.path("calls").get(0).path("status").asText()).isEqualTo("SENDING");
        assertThat(facts.path("reservations").get(0).path("status").asText()).isEqualTo("SENT");
        assertThat(facts.path("ackLossCount").asInt()).isEqualTo(1);
        var before=Files.readString(dir.resolve("receiver-completed.json"));
        var recovered=action("recover");assertThat(recovered.path("sends").asInt()).isEqualTo(1);
        assertThat(recovered.path("commits").asInt()).isZero();
        assertThat(Files.readString(dir.resolve("receiver-completed.json"))).isEqualTo(before);
    }
    @Test void explicitProviderUsageIsAccountedOnce() throws Exception {
        Files.writeString(dir.resolve("provider-control.txt"),"controlled-usage");var result=drive("normal");
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("usage").get(0).path("usage_accounted").asBoolean()).isTrue();
        var facts=Json.read(Files.readString(dir.resolve("model-durable-facts.json")));
        assertThat(facts.path("calls").get(0).path("total_tokens").asInt()).isEqualTo(66);
    }
    @Test void finalRevocationPreservesModelFactsWithoutEngineCommit() throws Exception {
        Files.writeString(dir.resolve("model-final-revoke"),"revoke");var result=drive("normal");
        assertThat(result.path("commits").asInt()).isZero();
        assertThat(result.path("attempts").get(0).path("response_ref").isNull()).isTrue();
        var facts=Json.read(Files.readString(dir.resolve("model-durable-facts.json")));
        assertThat(facts.path("calls").get(0).path("status").asText()).isEqualTo("SUCCEEDED");
    }
    void transportFault(String mode) throws Exception {
        Files.writeString(dir.resolve("transport-mode"),mode);
        var result=drive("normal");
        assertThat(result.path("sends").asInt()).isEqualTo(1);
        assertThat(result.path("commits").asInt()).isZero();
        assertThat(result.path("error").asText()).startsWith("INITIAL_STREAM_");
        assertThat(result.path("attempts").get(0).path("response_ref").isNull()).isTrue();
    }
    @Test void redirectIsNeverFollowed() throws Exception { transportFault("redirect"); }
    @Test void errorEventNeverCommits() throws Exception { transportFault("error"); }
    @Test void missingFinalNeverCommits() throws Exception { transportFault("missing-final"); }
    @Test void duplicateFinalNeverCommits() throws Exception { transportFault("duplicate-final"); }
    @Test void malformedEventNeverCommits() throws Exception { transportFault("malformed"); }
    @Test void oversizedLineFailsBeforeUnboundedAllocation() throws Exception { transportFault("oversized"); }
    @Test void invalidUtf8NeverCommits() throws Exception { transportFault("utf8"); }
    @Test void silentStreamHasIdleDeadline() throws Exception { transportFault("silent"); }
    @Test void activeDripHasTotalDeadline() throws Exception { transportFault("drip"); }
}
