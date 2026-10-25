package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;
import cn.agentplatform.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InitialChatAdmissionHttpPostgresTest {
    @Test void topLevelHistoryMustRejectBeforeCreation()throws Exception {
        var published=control("published");
        assertThat(published.path("type").asText()).isEqualTo("CHAT");
        assertThat(published.path("versionId").asLong()).isPositive();
        assertThat(published.path("hasFrozenModel").asBoolean()).isTrue();
        assertThat(control("i1-new-history-empty").path("httpStatus").asInt()).isEqualTo(403);
    }

    @Test void explicitTopLevelFieldsRejectNewRequestsWithoutEffects()throws Exception {
        var published=control("published");
        assertThat(published.path("type").asText()).isEqualTo("CHAT");
        assertThat(published.path("versionId").asLong()).isPositive();
        assertThat(published.path("hasFrozenModel").asBoolean()).isTrue();
        var before=control("i1-counts");
        for(String field:java.util.List.of("chatSession","chatTurn","sessionHistory","history"))
            for(String shape:java.util.List.of("null","empty","present"))
                assertThat(control("i1-new-"+field+"-"+shape).path("httpStatus").asInt()).as(field+"/"+shape).isEqualTo(403);
        assertThat(control("i1-counts")).isEqualTo(before);
        for(String field:java.util.List.of("chatSession","chatTurn","sessionHistory","history"))
            assertThat(control("i1-inputs-"+field+"-empty").path("httpStatus").asInt()).isEqualTo(403);
        assertThat(control("i1-snapshot").path("rejected").asInt()).isEqualTo(4);
        assertThat(control("i1-counts")).isEqualTo(before);
    }

    @Test void explicitTopLevelFieldsRejectReplayWithoutChangingResult()throws Exception {
        var published=control("published");
        assertThat(published.path("type").asText()).isEqualTo("CHAT");
        assertThat(published.path("versionId").asLong()).isPositive();
        assertThat(published.path("hasFrozenModel").asBoolean()).isTrue();
        var first=control("i1-accept");
        assertThat(first.path("httpStatus").asInt()).isEqualTo(200);
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        JsonNode before;
        do{before=control("poll");if(before.path("run").path("status").asText().equals("SUCCEEDED")&&before.path("activeWorkers").asInt()==0)break;Thread.sleep(50);}while(System.nanoTime()<end);
        assertThat(before.path("run").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(before.path("providerRequests").asInt()).isEqualTo(1);
        var legal=control("i1-accept");
        assertThat(legal.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(legal.path("body").path("data").path("runId")).isEqualTo(first.path("body").path("data").path("runId"));
        for(String field:java.util.List.of("chatSession","chatTurn","sessionHistory","history"))
            for(String shape:java.util.List.of("null","empty","present"))
                assertThat(control("i1-replay-"+field+"-"+shape).path("httpStatus").asInt()).as(field+"/"+shape).isEqualTo(403);
        var after=control("facts");
        for(String field:java.util.List.of("run","nodes","attempts","invocations","providerRequests"))
            assertThat(after.path(field)).as(field).isEqualTo(before.path(field));
        assertThat(control("i1-accept").path("body").path("data")).isEqualTo(legal.path("body").path("data"));
    }

    @Test void legalFlowStillCompletes()throws Exception {
        assertThat(control("flow").path("httpStatus").asInt()).isEqualTo(200);
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);JsonNode facts;
        do{facts=control("poll");if(facts.path("run").path("status").asText().equals("SUCCEEDED"))break;Thread.sleep(50);}while(System.nanoTime()<end);
        assertThat(facts.path("run").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(control("status").path("httpStatus").asInt()).isEqualTo(200);
        assertThat(facts.path("providerRequests").asInt()).isZero();
    }

    static JsonNode control(String action)throws Exception {
        var dir=Path.of(System.getProperty("ica.phase"));
        String origin=Json.read(Files.readString(dir.resolve("initial-chat-ready.json"))).path("origin").asText();
        try(var client=HttpClient.newHttpClient()){
            var response=client.send(HttpRequest.newBuilder(URI.create(origin+"/control?"+action)).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("fixture control transport").isEqualTo(200);
            return Json.read(response.body());
        }
    }
    @Test void genuinePublishedChatCreatesProtectedAgent()throws Exception {
        var published=control("published");
        assertThat(published.path("type").asText()).isEqualTo("CHAT");
        assertThat(published.path("versionId").asLong()).isPositive();
        assertThat(published.path("hasFrozenModel").asBoolean()).isTrue();
        var flow=control("flow");
        assertThat(flow.path("httpStatus").asInt()).as("existing FLOW admission positive").isEqualTo(200);
        assertThat(control("status").path("httpStatus").asInt()).as("fresh FLOW observer positive").isEqualTo(200);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        JsonNode flowFacts;
        do {flowFacts=control("facts");if(flowFacts.path("run").path("status").asText().equals("SUCCEEDED"))break;Thread.sleep(50);}while(System.nanoTime()<deadline);
        assertThat(flowFacts.path("run").path("status").asText()).as("existing FLOW reaches terminal before next admission").isEqualTo("SUCCEEDED");
        var result=control("chat");
        assertThat(result.path("httpStatus").asInt()).as("actual RunController published CHAT admission").isEqualTo(200);
        var facts=control("facts");
        assertThat(facts.path("run").path("trigger_type").asText()).isEqualTo("CHAT");
        assertThat(facts.path("nodes")).hasSize(1);
        assertThat(facts.path("nodes").get(0).path("node_type").asText()).isEqualTo("AGENT");
        assertThat(facts.path("nodes").get(0).path("node_def_id").isNull()).isTrue();
        assertThat(facts.path("nodes").get(0).path("step_seq").asInt()).isEqualTo(1);
    }
    @Test void rejectsUnsupportedInputsAndReplaysOriginalRequest()throws Exception {
        for(String action:java.util.List.of("reject-step","reject-debug","reject-session","reject-chatSession","reject-historyRefs","reject-tools","reject-materialRefs")) {
            var denied=control(action);
            assertThat(denied.path("httpStatus").asInt()).as(action).isBetween(400,499);
        }
        assertThat(control("facts").path("admittedCount").asInt()).isZero();
        var first=control("chat");
        assertThat(first.path("httpStatus").asInt()).isEqualTo(200);
        var again=control("chat");
        assertThat(again.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(again.path("body").path("data").path("runId")).isEqualTo(first.path("body").path("data").path("runId"));
        assertThat(control("reject-conflict").path("httpStatus").asInt()).isBetween(400,499);
        assertThat(control("facts").path("admittedCount").asInt()).isEqualTo(1);
    }    @Test void durableResponseRecoveryReusesSameAttempt()throws Exception {recoverWindow(false);}
    @Test void durableDispatchUnknownNeverResends()throws Exception {recoverWindow(true);}
    private static void recoverWindow(boolean unknown)throws Exception {
        assertThat(control(unknown?"chat-unknown-window":"chat-response-window").path("httpStatus").asInt()).isEqualTo(200);
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        JsonNode before;
        do{before=control("facts");if(before.path("faultUsed").asBoolean())break;Thread.sleep(50);}while(System.nanoTime()<until);
        assertThat(before.path("faultUsed").asBoolean()).isTrue();
        assertThat(before.path("attempts").asInt()).isEqualTo(1);
        assertThat(before.path("providerRequests").asInt()).isEqualTo(unknown?0:1);
        assertThat(before.path("invocations").get(0).path("response_present").asBoolean()).isEqualTo(!unknown);
        control("recover-current");
        until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        JsonNode after;
        do{after=control("poll");if(unknown?after.path("workerFailure").asText().equals("MODEL_RESULT_UNKNOWN"):after.path("run").path("status").asText().equals("SUCCEEDED"))break;Thread.sleep(100);}while(System.nanoTime()<until);
        assertThat(after.path("dispatchGeneration").asLong()).isGreaterThan(before.path("dispatchGeneration").asLong());
        assertThat(after.path("attempts").asInt()).isEqualTo(1);
        assertThat(after.path("providerRequests").asInt()).isEqualTo(unknown?0:1);
        assertThat(after.path("invocations").get(0).path("id")).isEqualTo(before.path("invocations").get(0).path("id"));
        if(unknown){assertThat(after.path("workerFailure").asText()).isEqualTo("MODEL_RESULT_UNKNOWN");assertThat(after.path("invocations").get(0).path("status").asText()).isEqualTo("DISPATCHED");}
        else{assertThat(after.path("run").path("status").asText()).isEqualTo("SUCCEEDED");assertThat(control("status").path("httpStatus").asInt()).isEqualTo(200);}
    }    @Test void concurrentDuplicateAndReconstructedReceiverReplayOneResult()throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->control("chat"));var b=executor.submit(()->control("chat"));
            var first=a.get();var second=b.get();
            assertThat(first.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(second.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(first.path("body").path("data").path("runId")).isEqualTo(second.path("body").path("data").path("runId"));
            var permits=control("facts");assertThat(permits.path("interactiveAvailable").asInt()+permits.path("activeWorkers").asInt()).isEqualTo(2);
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);JsonNode facts;
            do{facts=control("poll");if(facts.path("run").path("status").asText().equals("SUCCEEDED"))break;Thread.sleep(50);}while(System.nanoTime()<until);
            assertThat(facts.path("run").path("status").asText()).isEqualTo("SUCCEEDED");
            long released=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);do{facts=control("facts");if(facts.path("activeWorkers").asInt()==0)break;Thread.sleep(20);}while(System.nanoTime()<released);
            assertThat(facts.path("interactiveAvailable").asInt()).isEqualTo(2);assertThat(facts.path("activeWorkers").asInt()).isZero();
            assertThat(control("restart-receiver").path("receiverReconstructed").asBoolean()).isTrue();
            var replay=control("chat");assertThat(replay.path("httpStatus").asInt()).isEqualTo(200);
            assertThat(replay.path("body").path("data").path("runId")).isEqualTo(first.path("body").path("data").path("runId"));
            facts=control("facts");assertThat(facts.path("admittedCount").asInt()).isEqualTo(1);
            assertThat(facts.path("attempts").asInt()).isEqualTo(1);assertThat(facts.path("providerRequests").asInt()).isEqualTo(1);
        }
    }
    @Test void finiteSourcePolicyRejectsUnsupportedConfiguration() {
        var source=Json.tree(java.util.Map.of("type","CHAT","config",java.util.Map.of("modelEntryId",1),"dependencyVersions",java.util.Map.of("modelRefs",java.util.List.of(java.util.Map.of("modelEntryId",1)))));
        var inputs=Json.tree(java.util.Map.of("message","first"));
        cn.agentplatform.engine.domain.InitialChatRules.requireSources(source,inputs);
        for(String field:java.util.List.of("tools","frozenPrompts","guard","dataSources","skillRefs","summarizer","sessionHistory","modelOverride")) {
            var bad=source.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("config")).set(field,Json.tree(java.util.List.of()));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->cn.agentplatform.engine.domain.InitialChatRules.requireSources(bad,inputs)).isInstanceOf(RuntimeException.class);
        }
        for(String field:java.util.List.of("sessionId","chatSession","chatTurn","sessionHistory","history","historyRefs","materialRefs","tools")) {
            var bad=inputs.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad).set(field,Json.tree(java.util.List.of()));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->cn.agentplatform.engine.domain.InitialChatRules.requireSources(source,bad)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void reviewedPrivateTaskControlStillCompletes()throws Exception {
        var result=control("private-task");
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(result.path("sends").asInt()).isEqualTo(1);assertThat(result.path("commits").asInt()).isEqualTo(1);
    }}





