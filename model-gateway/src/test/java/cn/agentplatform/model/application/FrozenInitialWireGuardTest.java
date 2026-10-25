package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityContext;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.*;

@Timeout(30)
class FrozenInitialWireGuardTest {
  @Test void unchangedActualSdkStreamPassesThenMutatedNativeModelIsDenied() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      f.with((binding,accepted)->{
        var prompt=ModelCalls.frozenInitialPrompt(binding,accepted);
        var wire=FrozenInitialPromptFixture.encode(prompt,true,body->FrozenInitialWireGuard.require(binding,accepted,body));
        FrozenInitialPromptFixture.record("wire-positive",wire,binding.requestSha256());
        assertThat(wire.body().path("stream").booleanValue()).isTrue();
        ((OpenAiChatOptions)prompt.getOptions()).setModel("mutated-prepared-data");
        FrozenInitialPromptTest.denied(()->FrozenInitialPromptFixture.encode(prompt,true,body->FrozenInitialWireGuard.require(binding,accepted,body)),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        return null;
      });
    }
  }
  @Test void realSdkDefaultsJsonAndNestedSchemaPreserveEffectiveNativeValues() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      for(int profile=0;profile<3;profile++){
        f.params().removeAll();f.params().put("maxOutputTokens",256);
        if(profile>0){
          f.params().put("frequencyPenalty",0.0);f.params().put("presencePenalty",0.0);
          f.params().set("temperature",new DecimalNode(new java.math.BigDecimal("0.00001")));f.params().put("topP",0.0);f.params().put("seed",profile==1?Integer.MIN_VALUE:Integer.MAX_VALUE);
          if(profile==1)f.params().put("stop","结束");else f.params().putArray("stop").add("结束").add("STOP");
          var output=f.params().putObject("output");output.put("maxTokens",128);var format=output.putObject("format").put("type","json");
          if(profile==2)format.set("schema",Json.read("{\"type\":\"object\",\"properties\":{\"model\":{\"type\":\"object\",\"properties\":{\"stream\":{\"type\":\"string\"},\"中文\":{\"enum\":[\"种子\",\"结束\"]}}}}}"));
        }
        f.rebuild();int id=profile;
        f.with((binding,accepted)->{
          var original=accepted.clone();var prompt=ModelCalls.frozenInitialPrompt(binding,accepted);
          var wire=FrozenInitialPromptFixture.encode(prompt,true,body->{var before=body.clone();FrozenInitialWireGuard.require(binding,accepted,body);assertThat(body).isEqualTo(before);});
          FrozenInitialPromptFixture.record("wire-profile-"+id,wire,binding.requestSha256());assertThat(accepted).isEqualTo(original);
          assertThat(wire.body().path("max_tokens").intValue()).isEqualTo(id==0?256:128);
          if(id>0){assertThat(new String(accepted,StandardCharsets.UTF_8)).contains("0.00001");assertThat(wire.body().path("temperature").doubleValue()).isEqualTo(.00001);}
          return null;
        });
      }
    }
  }
  @Test void realSdkNativeOptionsMessagesAndCallModeMutationsDeny() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      f.params().put("temperature",.3);f.params().put("topP",.8);f.params().put("frequencyPenalty",0.0);f.params().put("presencePenalty",0.0);
      f.params().put("seed",7);f.params().putArray("stop").add("STOP");
      f.params().putObject("output").putObject("format").put("type","json").set("schema",Json.read("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}"));f.rebuild();
      f.with((binding,accepted)->{
        Consumer<byte[]> guard=body->FrozenInitialWireGuard.require(binding,accepted,body);
        FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(binding,accepted),true,guard);
        List<Consumer<OpenAiChatOptions>> mutations=List.of(o->o.setMaxTokens(1),o->o.setTemperature(.4),o->o.setTemperature(null),o->o.setTopP(.7),
            o->o.setFrequencyPenalty(.1),o->o.setPresencePenalty(.1),o->o.setSeed(8),o->o.setStop(List.of("different")),
            o->o.setResponseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT,null)),o->o.setStreamUsage(false),o->o.setN(2),
            o->o.setToolChoice("required"),o->o.setTools(List.of(new OpenAiApi.FunctionTool(new OpenAiApi.FunctionTool.Function("lookup","lookup","{\"type\":\"object\"}")))));
        for(var mutate:mutations){var prompt=ModelCalls.frozenInitialPrompt(binding,accepted);mutate.accept((OpenAiChatOptions)prompt.getOptions());FrozenInitialPromptTest.denied(()->FrozenInitialPromptFixture.encode(prompt,true,guard),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");}
        var expected=ModelCalls.frozenInitialPrompt(binding,accepted);
        for(List<Message> messages:List.<List<Message>>of(List.of(new SystemMessage("changed"),new UserMessage("sensitive input")),
            List.of(expected.getInstructions().get(1),expected.getInstructions().get(0)),List.of(new SystemMessage("Hello Alice"),new SystemMessage("sensitive input")),
            List.of(expected.getInstructions().get(0),expected.getInstructions().get(1),new UserMessage("extra")))){
          FrozenInitialPromptTest.denied(()->FrozenInitialPromptFixture.encode(new Prompt(messages,expected.getOptions()),true,guard),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        }
        var call=ModelCalls.toPrompt(Json.read(new String(accepted,StandardCharsets.UTF_8)),"gpt-4o-mini",2048,false);
        FrozenInitialPromptTest.denied(()->FrozenInitialPromptFixture.encode(call,false,guard),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");return null;
      });
    }
  }
  @Test void explicitlySyntheticParserInputsAreStrictBoundedAndPayloadFree() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      f.with((binding,accepted)->{
        var actual=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(binding,accepted),true);
        FrozenInitialWireGuard.require(binding,accepted,actual.bytes());
        List<byte[]> malformed=new ArrayList<>(Arrays.asList(null,new byte[0],new byte[2097153],new byte[]{(byte)0xc3,0x28}));
        for(String input:List.of("[]","{} {}","{\"model\":\"first\",\"model\":\"second\"}","{\"x\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}","{\"x\":"+"1".repeat(129)+"}"))malformed.add(input.getBytes(StandardCharsets.UTF_8));
        for(byte[] body:malformed)FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,accepted,body),400,"MODEL_INPUT_INVALID");
        var wrongType=actual.body().deepCopy();((ObjectNode)wrongType).put("model",5);
        FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,accepted,Json.write(wrongType).getBytes(StandardCharsets.UTF_8)),400,"MODEL_INPUT_INVALID");
        for(String field:List.of("temperature","tools","tool_choice","metadata","private")){
          var extra=(ObjectNode)actual.body().deepCopy();extra.put(field,"payload-marker-never-diagnosed");
          assertThatThrownBy(()->FrozenInitialWireGuard.require(binding,accepted,Json.write(extra).getBytes(StandardCharsets.UTF_8))).isInstanceOf(PlatformException.class).hasMessageNotContaining("payload-marker");
        }
        var missing=(ObjectNode)actual.body().deepCopy();missing.remove("max_tokens");
        FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,accepted,Json.write(missing).getBytes(StandardCharsets.UTF_8)),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        return null;
      });
    }
  }
  @Test void rawInputAndGenuineLiveBindingFrameThreadExpiryAndInterruptRemainRequired() throws Exception {
    FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(null,null,null),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
    try(var f=new FrozenInitialPromptFixture()) {
      var old=f.with((binding,accepted)->{
        var wire=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(binding,accepted),true);FrozenInitialWireGuard.require(binding,accepted,wire.bytes());
        for(byte[] input:Arrays.asList(null,new byte[0],new byte[2097153]))FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,input,wire.bytes()),400,"MODEL_INPUT_INVALID");
        var changed=accepted.clone();changed[1]^=1;FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,changed,wire.bytes()),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        Thread.currentThread().interrupt();try{FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,accepted,wire.bytes()),503,"SYS-MODEL-503");assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}
        try(var executor=Executors.newSingleThreadExecutor()){assertThatThrownBy(()->executor.submit(()->FrozenInitialWireGuard.require(binding,accepted,wire.bytes())).get()).hasCauseInstanceOf(PlatformException.class);}
        try(var frame=AuthorityContext.bind(AuthorityContext.current())){assertThatThrownBy(()->FrozenInitialWireGuard.require(binding,accepted,wire.bytes())).isInstanceOf(PlatformException.class);}
        AuthorityContext.requireInitialFor(AuthorityContext.current().actor(),AuthorityContext.current().call().resource());assertThatThrownBy(()->FrozenInitialWireGuard.require(binding,accepted,wire.bytes())).isInstanceOf(PlatformException.class);
        return Map.entry(binding,Map.entry(accepted,wire.bytes()));
      });
      assertThatThrownBy(()->FrozenInitialWireGuard.require(old.getKey(),old.getValue().getKey(),old.getValue().getValue())).isInstanceOf(PlatformException.class);
      ((ObjectNode)f.accepted.setup.consumer.request.path("messages").get(1)).put("content","another genuine input");f.rebuild();
      f.with((binding,accepted)->{
        var wire=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(binding,accepted),true);FrozenInitialWireGuard.require(binding,accepted,wire.bytes());
        FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,old.getValue().getKey(),wire.bytes()),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        f.accepted.setup.consumer.iam.advanceClock(Duration.ofSeconds(61));FrozenInitialPromptTest.denied(()->FrozenInitialWireGuard.require(binding,accepted,wire.bytes()),403,"AUTHORITY_EXPIRED");return null;
      });
    }
  }
  @Test void activeInstrumentationThenPureGuardAndEncoderHaveNoExternalEffects() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      var setup=f.accepted.setup;
      try(var http=java.net.http.HttpClient.newHttpClient()){
        for(String path:List.of("/provider","/tokenizer"))assertThat(http.send(java.net.http.HttpRequest.newBuilder(setup.consumer.origin().resolve(path)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
      }
      assertThat(setup.providerHttp).hasValue(1);assertThat(setup.tokenizerHttp).hasValue(1);setup.providerHttp.set(0);setup.tokenizerHttp.set(0);
      f.with((binding,accepted)->{
        var before=FrozenInitialInputMeterTest.business(f.accepted);int iam=setup.consumer.iam.authorityCalls();assertThat(iam).isPositive();assertThat(setup.ds.sql).isNotEmpty();setup.ds.reset();
        var wire=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(binding,accepted),true,body->FrozenInitialWireGuard.require(binding,accepted,body));
        assertThat(setup.ds.sql).isEmpty();assertThat(setup.ds.borrowed).hasValue(0);assertThat(setup.consumer.iam.authorityCalls()).isEqualTo(iam);assertThat(setup.providerHttp).hasValue(0);assertThat(setup.tokenizerHttp).hasValue(0);
        assertThat(FrozenInitialInputMeterTest.business(f.accepted)).isEqualTo(before);FrozenInitialPromptFixture.record("wire-pure-window",wire,binding.requestSha256());
        try{java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("binding.fixture")).resolve("wire-pure-window.json"),Json.write(Map.of("providerControl",1,"tokenizerControl",1,"iamControl",iam,"sqlActiveControl",true,"providerWindow",0,"tokenizerWindow",0,"sqlWindow",List.of(),"borrowed",0,"iamDelta",0,"businessSha256",Digest.sha256(Json.write(before)))));}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;
      });
    }
  }
}
