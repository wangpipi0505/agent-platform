package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Real published separate engine provenance; only the later provider encoding is offline. */
@Timeout(30)
class FrozenInitialPromptHttpTest {
  @BeforeAll static void start() throws Exception { FrozenModelBindingConsumerHttpTest.start(); }
  @AfterAll static void close() throws Exception { FrozenModelBindingConsumerHttpTest.close(); }
  @Test void realProducerPenaltiesReachActualSpringAiStreamWithoutTransport() throws Exception {
    var dir=FrozenModelBindingConsumerHttpTest.dir;byte[] incoming=Files.readAllBytes(dir.resolve("request.json"));
    var records=new ArrayList<Object>();
    for(String ticket:List.of("first","repeat","newGrant")) FrozenModelBindingConsumerHttpTest.received(ticket,retained->{
      var input=FrozenModelBindingConsumerHttpTest.consumer.verify(incoming,FrozenModelBindingConsumerHttpTest.ready.path("materialRef").asText(),retained);
      byte[] bytes=input.acceptedCanonicalRequestUtf8();var proof=new FrozenModelBindingVerifier(FrozenModelBindingConsumerHttpTest.ds).verify(input);
      var sql=List.copyOf(FrozenModelBindingConsumerHttpTest.ds.sql);
      var semantic=Json.read(new String(bytes,StandardCharsets.UTF_8));assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("0.00001");
      assertThat(semantic.path("params").has("frequencyPenalty")).isTrue();assertThat(semantic.path("params").has("presencePenalty")).isTrue();
      var wire=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(proof,bytes),true);
      FrozenInitialPromptFixture.penalties(wire.body());FrozenInitialPromptFixture.noPrivateFields(wire.body());
      assertThat(wire.body().path("model").asText()).isEqualTo("gpt-4o-mini");
      assertThat(proof.bindingId()).isEqualTo(FrozenModelBindingConsumerHttpTest.descriptor.path("bindingId").longValue());
      assertThat(proof.requestSha256()).isEqualTo(FrozenModelBindingConsumerHttpTest.ready.path("requestSha256").asText());
      assertThat(FrozenModelBindingConsumerHttpTest.ds.sql).isEqualTo(sql);assertThat(FrozenModelBindingConsumerHttpTest.ds.borrowed).hasValue(0);
      FrozenInitialPromptFixture.record("http-"+ticket,wire,proof.requestSha256());
      records.add(Map.of("ticket",ticket,"bindingId",proof.bindingId(),"bindingSha256",proof.bindingSha256(),"requestSha256",proof.requestSha256(),"wireBytes",wire.bytes().length));return null;
    });
    Files.writeString(dir.resolve("prompt-http-results.json"),Json.write(Map.of("modelPid",ProcessHandle.current().pid(),"enginePid",FrozenModelBindingConsumerHttpTest.ready.path("enginePid").longValue(),"transportSent",false,"records",records)));
  }
}
