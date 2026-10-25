package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Reuses the accepted separate-JVM signed receiver protocol, with the selected real LOCAL freeze. */
@Timeout(30)
class FrozenInitialInputMeterHttpTest {
  @BeforeAll static void start() throws Exception { FrozenModelBindingConsumerHttpTest.start(); }
  @AfterAll static void close() throws Exception { FrozenModelBindingConsumerHttpTest.close(); }
  @Test void realPublishedSeparateEngineCanonicalInputCountsLocally() throws Exception {
    var dir = FrozenModelBindingConsumerHttpTest.dir;
    byte[] incoming = Files.readAllBytes(dir.resolve("request.json"));
    var records = new ArrayList<Object>();
    for (String ticket : List.of("first", "repeat", "newGrant"))
      FrozenModelBindingConsumerHttpTest.received(ticket, retained -> {
        var input = FrozenModelBindingConsumerHttpTest.consumer.verify(incoming,
            FrozenModelBindingConsumerHttpTest.ready.path("materialRef").asText(), retained);
        byte[] bytes = input.acceptedCanonicalRequestUtf8();
        var proof = new FrozenModelBindingVerifier(FrozenModelBindingConsumerHttpTest.ds).verify(input);
        assertThat(FrozenModelBindingConsumerHttpTest.ds.borrowed).hasValue(0);
        var sql = List.copyOf(FrozenModelBindingConsumerHttpTest.ds.sql);
        var result = FrozenInitialInputMeter.measure(proof, bytes);
        assertThat(result.requestSha256()).isEqualTo(FrozenModelBindingConsumerHttpTest.ready.path("requestSha256").asText());
        assertThat(result.bindingId()).isEqualTo(FrozenModelBindingConsumerHttpTest.descriptor.path("bindingId").longValue());
        assertThat(result.inputTokens()).isEqualTo(ModelCalls.countLocalTokens("gpt-4o-mini", new String(bytes, StandardCharsets.UTF_8)));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("0.00001");
        assertThat(FrozenModelBindingConsumerHttpTest.ds.sql).isEqualTo(sql);
        assertThat(result.exact()).isFalse(); records.add(result); return null;
      });
    Files.writeString(dir.resolve("local-meter-http-results.json"), Json.write(Map.of("modelPid", ProcessHandle.current().pid(),
        "enginePid", FrozenModelBindingConsumerHttpTest.ready.path("enginePid").longValue(), "measurements", records)));
  }
}
