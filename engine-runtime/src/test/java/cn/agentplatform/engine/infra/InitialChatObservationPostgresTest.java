package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class InitialChatObservationPostgresTest {
    @Test void freshObserverReadsGenuinelyAdmittedChat()throws Exception {
        var created=InitialChatAdmissionHttpPostgresTest.control("chat");
        assertThat(created.path("httpStatus").asInt()).as("genuine admission prerequisite").isEqualTo(200);
        var metadata=InitialChatAdmissionHttpPostgresTest.control("metadata");
        assertThat(metadata.path("httpStatus").asInt()).as("independent current observer metadata").isEqualTo(200);
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        com.fasterxml.jackson.databind.JsonNode facts;
        do {facts=InitialChatAdmissionHttpPostgresTest.control("poll");
            if(java.util.Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(facts.path("run").path("status").asText()))break;
            Thread.sleep(50);
        }while(System.nanoTime()<until);
        assertThat(facts.path("run").path("status").asText()).as("genuine CHAT reaches one logical result").isEqualTo("SUCCEEDED");
        assertThat(facts.path("attempts").asInt()).as("one real sender attempt").isEqualTo(1);
        var status=InitialChatAdmissionHttpPostgresTest.control("status");
        assertThat(status.path("httpStatus").asInt()).as("independent current observer status").isEqualTo(200);
        var events=InitialChatAdmissionHttpPostgresTest.control("events");
        assertThat(events.path("httpStatus").asInt()).as("independent durable fact SSE").isEqualTo(200);
        assertThat(events.path("body").asText()).contains("chunkAvailable","false","SUCCEEDED");
    }
    @Test void reconnectAndFreshCurrentDenialPreserveDurableResult()throws Exception {
        freshObserverReadsGenuinelyAdmittedChat();
        var replay=InitialChatAdmissionHttpPostgresTest.control("events-after");
        assertThat(replay.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(replay.path("body").asText()).contains("LOGIC_RESULT_COMMITTED","RUN_FINISHED").doesNotContain("event:RUN_STARTED","event:MODEL_DISPATCHED");
        var ahead=InitialChatAdmissionHttpPostgresTest.control("events-ahead");
        assertThat(ahead.path("httpStatus").asInt()).isEqualTo(200);
        assertThat(ahead.path("body").asText()).contains("stream_reset","SUCCEEDED");
        assertThat(InitialChatAdmissionHttpPostgresTest.control("observe-narrow").path("httpStatus").asInt()).isEqualTo(403);
        assertThat(InitialChatAdmissionHttpPostgresTest.control("observe-outage").path("httpStatus").asInt()).isEqualTo(503);
        var facts=InitialChatAdmissionHttpPostgresTest.control("facts");
        assertThat(facts.path("run").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(facts.path("attempts").asInt()).isEqualTo(1);
    }    @Test void immutableStoreRejectsDedicatedRunTamper()throws Exception {
        freshObserverReadsGenuinelyAdmittedChat();
        var attempted=InitialChatAdmissionHttpPostgresTest.control("immutable-tamper");
        assertThat(attempted.path("storageRejected").asBoolean()).as("real unchanged V219 immutable trigger").isTrue();
        assertThat(attempted.path("changed").asInt()).isZero();
        assertThat(attempted.path("observer").path("httpStatus").asInt()).isEqualTo(200);
    }}




