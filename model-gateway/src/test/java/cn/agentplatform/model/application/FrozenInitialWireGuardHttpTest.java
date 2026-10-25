package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import static org.assertj.core.api.Assertions.*;

/** Genuine engine producer and signed receiver; provider bytes stop before transport. */
@Timeout(30)
class FrozenInitialWireGuardHttpTest {
  @BeforeAll static void start() throws Exception { FrozenModelBindingConsumerHttpTest.start(); }
  @AfterAll static void close() throws Exception { FrozenModelBindingConsumerHttpTest.close(); }
  @Test void genuineProducerStreamPassesAndMutatedBodyDeniesAcrossFreshGrants() throws Exception {
    var incoming=Files.readAllBytes(FrozenModelBindingConsumerHttpTest.dir.resolve("request.json"));
    for(String ticket:List.of("first","repeat","newGrant")) FrozenModelBindingConsumerHttpTest.received(ticket,retained->{
      var input=FrozenModelBindingConsumerHttpTest.consumer.verify(incoming,FrozenModelBindingConsumerHttpTest.ready.path("materialRef").asText(),retained);
      var accepted=input.acceptedCanonicalRequestUtf8();var binding=new FrozenModelBindingVerifier(FrozenModelBindingConsumerHttpTest.ds).verify(input);
      var prompt=ModelCalls.frozenInitialPrompt(binding,accepted);var sql=List.copyOf(FrozenModelBindingConsumerHttpTest.ds.sql);
      var wire=FrozenInitialPromptFixture.encode(prompt,true,body->FrozenInitialWireGuard.require(binding,accepted,body));
      FrozenInitialPromptFixture.record("wire-http-"+ticket,wire,binding.requestSha256());
      ((OpenAiChatOptions)prompt.getOptions()).setModel("mutated-prepared-data");
      FrozenInitialPromptTest.denied(()->FrozenInitialPromptFixture.encode(prompt,true,body->FrozenInitialWireGuard.require(binding,accepted,body)),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
      assertThat(FrozenModelBindingConsumerHttpTest.ds.sql).isEqualTo(sql);assertThat(FrozenModelBindingConsumerHttpTest.ds.borrowed).hasValue(0);return null;
    });
  }
}
