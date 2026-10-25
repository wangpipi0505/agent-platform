package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.Map;
import cn.agentplatform.common.Json;
import org.junit.jupiter.api.Test;

class InitialSenderIntegrationTest {
    @Test void ownedDriveCommitsSchemaConstrainedInitialResult() throws Exception {
        var test=new InitialSenderFactsPostgresTest();
        test.declaration(InitialSenderFactsPostgresTest.SCHEMA.formatted("TERMINATE"));
        Files.writeString(test.dir.resolve("provider-text"),"{\"ok\":true}");
        var first=test.drive("response-recovery");
        assertThat(first.path("commits").asInt()).isZero();
        var raw=Json.read(first.path("durable").get(0).path("response").asText());
        assertThat(raw.path("text").asText()).isEqualTo("{\"ok\":true}");
        assertThat(raw.has("structured")).isFalse();
        var canonical=Files.readString(test.dir.resolve("ingress-private-request.bin"));
        assertThat(cn.agentplatform.common.Digest.sha256(canonical)).isEqualTo(first.path("attempts").get(0).path("request_sha256").asText());
        assertThat(canonical).contains("0.00001","界","\\n");
        assertThat(Json.read(canonical).path("params").path("output").path("format").path("schema").path("required").get(0).asText()).isEqualTo("ok");
        var wire=Files.readString(test.dir.resolve("provider-wire.bin"));
        assertThat(Json.read(wire).path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(Json.read(wire).path("response_format").path("json_schema").path("schema"))
                .isEqualTo(Json.read(canonical).path("params").path("output").path("format").path("schema"));
        var model=Json.read(Files.readString(test.dir.resolve("model-durable-facts.json")));
        assertThat(Json.read(model.path("calls").get(0).path("metering").asText()).path("initialRequest").path("wireUtf8").asText()).isEqualTo(wire);
        var before=Files.readString(test.dir.resolve("model-ingress-events.jsonl"));
        var recovered=test.action("recover");
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(recovered.path("output").path("text")).isEqualTo(raw.path("text"));
        assertThat(recovered.path("output").path("structured").path("ok").asBoolean()).isTrue();
        assertThat(recovered.path("output").path("outputValidation").path("valid").asBoolean()).isTrue();
        assertThat(recovered.path("invocations").asInt()).isEqualTo(1);
        assertThat(recovered.path("sends").asInt()).isEqualTo(1);
        assertThat(recovered.path("commits").asInt()).isEqualTo(1);
        assertThat(Files.readString(test.dir.resolve("model-ingress-events.jsonl"))).isEqualTo(before);
    }
    @Test void ownedDriveCommitsInitialResultAfterRealMaterialPreparation() throws Exception {
        Path dir = Path.of(System.getProperty("initial.fixture"));
        Files.writeString(dir.resolve("hold-final"), "hold");
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            String origin = Json.read(Files.readString(dir.resolve("engine/control-ready.json"))).path("origin").asText();
            var reply = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/control?drive"))
                    .timeout(java.time.Duration.ofSeconds(24)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(reply.statusCode()).isEqualTo(200);
            var result = Json.read(reply.body());
            assertThat(result.path("status").asText()).as(result.path("error").asText()).isEqualTo("SUCCEEDED");
            assertThat(result.path("sends").longValue()).isEqualTo(1);
            assertThat(result.path("commits").longValue()).isEqualTo(1);
            assertThat(result.path("observedChunks").size()).isGreaterThanOrEqualTo(1);
            var ingress=Json.read(Files.readString(dir.resolve("ingress-attempt-facts.json")));
            assertThat(ingress.path("commits").asInt()).isZero();
            assertThat(ingress.path("sends").asInt()).isEqualTo(1);
            assertThat(ingress.path("attempts").get(0).path("request_snapshot_ref").asText()).startsWith("pg:attempt/");
            var raw=Files.readAllBytes(dir.resolve("ingress-private-request.bin"));
            assertThat(cn.agentplatform.common.Digest.sha256(new String(raw,java.nio.charset.StandardCharsets.UTF_8)))
                    .isEqualTo(result.path("attempts").get(0).path("request_sha256").asText());
            assertThat(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).contains("0.00001","界","\\n");
            var model=Json.read(Files.readString(dir.resolve("model-durable-facts.json")));
            assertThat(model.path("calls").size()).isEqualTo(1);
            var call=model.path("calls").get(0);
            assertThat(call.path("total_tokens").isNull()).isTrue();
            assertThat(call.path("usage_reconciliation").asText()).isEqualTo("UNAVAILABLE");
            assertThat(result.path("usage").get(0).path("usage_accounted").asBoolean()).isFalse();
            assertThat(Json.read(call.path("metering").asText()).path("initialRequest").path("wireUtf8").asText())
                    .isEqualTo(Files.readString(dir.resolve("provider-wire.bin")));
            assertThat(java.time.Instant.parse(Files.readString(dir.resolve("engine-first-observed"))))
                    .isBeforeOrEqualTo(java.time.Instant.parse(Files.readString(dir.resolve("provider-final-released"))));
        }
    }
}
