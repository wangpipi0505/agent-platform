package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityContext;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.ai.openai.OpenAiChatOptions;

@Timeout(30)
class FrozenInitialPromptTest {
  static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int status,String code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(PlatformException.class,e->{
      assertThat(e.status()).isEqualTo(status);assertThat(e.code()).isEqualTo(code);
    });
  }
  @Test void genuineAcceptedNativeOptionsReachActualCallAndStreamBody() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      var p=f.params();p.put("frequencyPenalty",0.0);p.set("presencePenalty",new DecimalNode(new java.math.BigDecimal("0.00001")));
      p.put("seed",-2147483648);p.putArray("stop").add("结束").add("STOP");p.put("temperature",0.25);p.put("topP",0.9);
      p.putObject("output").put("maxTokens",128).putObject("format").put("type","json").set("schema",
          Json.read("{\"type\":\"object\",\"properties\":{\"中文\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"值\":{\"type\":\"string\"}}}}}}"));
      ((ObjectNode)f.accepted.setup.consumer.request.path("messages").get(1)).put("content","中文原始输入");f.rebuild();
      f.with((proof,bytes)->{
        assertThat(new String(bytes,StandardCharsets.UTF_8)).contains("0.00001","中文原始输入");
        var prompt=ModelCalls.frozenInitialPrompt(proof,bytes);
        assertThat(((OpenAiChatOptions)prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
        for(boolean stream:List.of(false,true)) {
          var actual=stream?prompt:ModelCalls.toPrompt(Json.read(new String(bytes,StandardCharsets.UTF_8)),"gpt-4o-mini",128,false);
          var wire=FrozenInitialPromptFixture.encode(actual,stream);var b=wire.body();FrozenInitialPromptFixture.record("native-"+stream,wire,proof.requestSha256());
          FrozenInitialPromptFixture.penalties(b);
          assertThat(b.path("model").asText()).isEqualTo("gpt-4o-mini");assertThat(b.path("max_tokens").intValue()).isEqualTo(128);
          assertThat(b.path("temperature").doubleValue()).isEqualTo(.25);assertThat(b.path("top_p").doubleValue()).isEqualTo(.9);
          assertThat(b.path("seed").longValue()).isEqualTo(Integer.MIN_VALUE);assertThat(b.path("stop")).isEqualTo(Json.tree(List.of("结束","STOP")));
          assertThat(b.path("messages").get(0).path("role").asText()).isEqualTo("system");
          assertThat(b.path("messages").get(1).path("content").asText()).isEqualTo("中文原始输入");
          assertThat(b.path("response_format").path("type").asText()).isEqualTo("json_schema");
          assertThat(b.path("response_format").path("json_schema").path("schema")).isEqualTo(p.path("output").path("format").path("schema"));
          assertThat(b.path("stream").asBoolean()).isEqualTo(stream);
          if(stream) assertThat(b.path("stream_options").path("include_usage").booleanValue()).isTrue(); else assertThat(b.hasNonNull("stream_options")).isFalse();
          FrozenInitialPromptFixture.noPrivateFields(b);
        }
        return null;
      });
    }
  }
  @Test void originalBytesPositiveThenOneAsciiByteChangeMustDeny() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      f.with((proof,bytes)->{
        assertThat(ModelCalls.frozenInitialPrompt(proof,bytes)).isNotNull();
        byte[] changed=bytes.clone();
        String text=new String(bytes,StandardCharsets.UTF_8);int index=text.indexOf("system");assertThat(index).isGreaterThan(0);
        changed[index]='x';
        denied(()->ModelCalls.frozenInitialPrompt(proof,changed),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");return null;
      });
    }
  }
  @Test void rawScientificIdentityCallerMutationAndAnotherGenuineRequest() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      f.params().set("temperature",new DecimalNode(new java.math.BigDecimal("0.00001")));f.rebuild();
      byte[] other=f.with((proof,bytes)->{assertThat(ModelCalls.frozenInitialPrompt(proof,bytes)).isNotNull();return bytes;});
      ((ObjectNode)f.accepted.setup.consumer.request.path("messages").get(1)).put("content","另一份真实请求");f.rebuild();
      f.with((proof,bytes)->{
        var prompt=ModelCalls.frozenInitialPrompt(proof,bytes);
        String text=new String(bytes,StandardCharsets.UTF_8);assertThat(text).contains("0.00001");
        denied(()->ModelCalls.frozenInitialPrompt(proof,text.replace("0.00001","1.0E-5").getBytes(StandardCharsets.UTF_8)),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        denied(()->ModelCalls.frozenInitialPrompt(proof,other),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        bytes[1]^=1;assertThat(prompt.getInstructions().get(1).getText()).isEqualTo("另一份真实请求");
        denied(()->ModelCalls.frozenInitialPrompt(proof,bytes),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        // Prepared options remain ordinary mutable data; their later body is a different identity.
        ((OpenAiChatOptions)prompt.getOptions()).setModel("mutated-prepared-data");
        var wire=FrozenInitialPromptFixture.encode(prompt,true);
        assertThat(wire.body().path("model").asText()).isEqualTo("mutated-prepared-data");
        assertThat(Digest.sha256(new String(wire.bytes(),StandardCharsets.UTF_8))).isNotEqualTo(proof.requestSha256());
        return null;
      });
    }
  }
  @Test void boundsAndCurrentFrameThreadExpiryInterruptAndAmbientTransaction() throws Exception {
    denied(()->ModelCalls.frozenInitialPrompt(null,null),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
    try(var f=new FrozenInitialPromptFixture()) {
      var old=f.with((proof,bytes)->{
        for(byte[] value:Arrays.asList(null,new byte[0],new byte[2097153])) denied(()->ModelCalls.frozenInitialPrompt(proof,value),400,"MODEL_INPUT_INVALID");
        denied(()->ModelCalls.frozenInitialPrompt(proof,new byte[2097152]),403,"MATERIAL_SCOPE_MISSING_OR_CHANGED");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(ModelCalls.frozenInitialPrompt(proof,bytes)).isNotNull(); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
        Thread.currentThread().interrupt();
        try { denied(()->ModelCalls.frozenInitialPrompt(proof,bytes),503,"SYS-MODEL-503");assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        try(var executor=Executors.newSingleThreadExecutor()) {
          assertThatThrownBy(()->executor.submit(()->ModelCalls.frozenInitialPrompt(proof,bytes)).get()).hasCauseInstanceOf(PlatformException.class);
        }
        try(var different=AuthorityContext.bind(AuthorityContext.current())) { assertThatThrownBy(()->ModelCalls.frozenInitialPrompt(proof,bytes)).isInstanceOf(PlatformException.class); }
        AuthorityContext.requireInitialFor(AuthorityContext.current().actor(),AuthorityContext.current().call().resource());
        assertThatThrownBy(()->ModelCalls.frozenInitialPrompt(proof,bytes)).isInstanceOf(PlatformException.class);return Map.entry(proof,bytes);
      });
      assertThatThrownBy(()->ModelCalls.frozenInitialPrompt(old.getKey(),old.getValue())).isInstanceOf(PlatformException.class);
      f.with((proof,bytes)->{assertThat(ModelCalls.frozenInitialPrompt(proof,bytes)).isNotNull();return null;});
      f.with((proof,bytes)->{f.accepted.setup.consumer.iam.advanceClock(Duration.ofSeconds(61));denied(()->ModelCalls.frozenInitialPrompt(proof,bytes),403,"AUTHORITY_EXPIRED");return null;});
    }
  }
  @Test void acceptedUnsupportedAndBadNativeOptionsFailExplicitly() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      for(var option:List.of(Map.entry("seed",Json.tree(2147483648L)),Map.entry("topK",Json.tree(5)),Map.entry("sampling",Json.tree(Map.of("x",1))))) {
        f.params().set(option.getKey(),option.getValue());f.rebuild();
        f.with((proof,bytes)->{denied(()->ModelCalls.frozenInitialPrompt(proof,bytes),409,"MODEL_REQUEST_OPTION_UNSUPPORTED");return null;});f.params().remove(option.getKey());
      }
      for(var option:List.of(Map.entry("temperature",Json.tree(2.1)),Map.entry("topP",Json.tree(-.1)),Map.entry("frequencyPenalty",Json.tree(-2.1)),Map.entry("presencePenalty",Json.tree(2.1)))) {
        var previous=f.params().get(option.getKey());f.params().set(option.getKey(),option.getValue());f.rebuild();
        f.with((proof,bytes)->{denied(()->ModelCalls.frozenInitialPrompt(proof,bytes),400,"MODEL_INPUT_INVALID");return null;});
        if(previous==null)f.params().remove(option.getKey());else f.params().set(option.getKey(),previous);
      }
      for(var value:List.of(new DoubleNode(Double.NaN),new DoubleNode(Double.POSITIVE_INFINITY),new TextNode("x"))) {
        f.params().set("temperature",value);denied(()->ModelCalls.toPrompt(f.accepted.setup.consumer.request,"gpt-4o-mini",128,false),400,"MODEL_INPUT_INVALID");
      }
      f.params().put("temperature",.5);f.params().put("seed",1.5);
      denied(()->ModelCalls.toPrompt(f.accepted.setup.consumer.request,"gpt-4o-mini",128,false),400,"MODEL_INPUT_INVALID");
      f.params().remove("seed");f.params().putArray("stop").add(3);
      denied(()->ModelCalls.toPrompt(f.accepted.setup.consumer.request,"gpt-4o-mini",128,false),400,"MODEL_INPUT_INVALID");
    }
  }
  @Test void effectiveOutputPriorityAndTextStopJsonObject() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      f.params().put("maxOutputTokens",256);f.params().put("stop","结束");f.params().putObject("sampling");
      f.params().putObject("output").put("maxTokens",128).putObject("format").put("type","json");f.rebuild();
      f.with((proof,bytes)->{var wire=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(proof,bytes),true);
        assertThat(wire.body().path("max_tokens").intValue()).isEqualTo(128);assertThat(wire.body().path("stop")).isEqualTo(Json.tree(List.of("结束")));
        assertThat(wire.body().path("response_format").path("type").asText()).isEqualTo("json_object");return null;});
      ((ObjectNode)f.params().path("output")).remove("maxTokens");f.rebuild();
      f.with((proof,bytes)->{assertThat(((OpenAiChatOptions)ModelCalls.frozenInitialPrompt(proof,bytes).getOptions()).getMaxTokens()).isEqualTo(256);return null;});
    }
  }
  @Test void legacyToolsAndAssistantResultPairingRemainNativeAndToolExecutionDisabled() {
    var req=Json.read("{\"messages\":[{\"role\":\"system\",\"content\":\"policy\"},{\"role\":\"user\",\"content\":\"问题\"},{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"lookup\",\"arguments\":{\"q\":\"中文\"}}}]},{\"role\":\"tool\",\"tool_call_id\":\"call-1\",\"content\":\"结果\"}],\"toolDefinitions\":[{\"function\":{\"name\":\"lookup\",\"description\":\"查找\",\"parameters\":{\"type\":\"object\"}}}],\"params\":{}}");
    for(boolean stream:List.of(false,true)) {
      var prompt=ModelCalls.toPrompt(req,"legacy-frozen-name",42,stream);
      assertThat(((OpenAiChatOptions)prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
      var b=FrozenInitialPromptFixture.encode(prompt,stream).body();assertThat(b.path("tools").get(0).path("function").path("name").asText()).isEqualTo("lookup");
      assertThat(b.path("messages").get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call-1");
      assertThat(b.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("call-1");
      assertThat(b.path("messages").get(3).path("name").asText()).isEqualTo("lookup");FrozenInitialPromptFixture.noPrivateFields(b);
    }
    ((ObjectNode)req.path("messages").get(3)).put("tool_call_id","missing");
    assertThatThrownBy(()->ModelCalls.toPrompt(req,"legacy",42,false)).isInstanceOf(IllegalArgumentException.class).hasMessage("工具结果没有对应的模型调用");
  }
  @Test void activeControlsThenPurePreparationAndEncodingWindowHasNoSqlIamHttpOrBusinessChanges() throws Exception {
    try(var f=new FrozenInitialPromptFixture()) {
      var setup=f.accepted.setup;
      try(var http=java.net.http.HttpClient.newHttpClient()) {
        for(String path:List.of("/provider","/tokenizer")) assertThat(http.send(java.net.http.HttpRequest.newBuilder(setup.consumer.origin().resolve(path)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
      }
      assertThat(setup.providerHttp).hasValue(1);assertThat(setup.tokenizerHttp).hasValue(1);setup.providerHttp.set(0);setup.tokenizerHttp.set(0);
      f.with((proof,bytes)->{
        var before=FrozenInitialInputMeterTest.business(f.accepted);int iam=setup.consumer.iam.authorityCalls();setup.ds.reset();
        var wire=FrozenInitialPromptFixture.encode(ModelCalls.frozenInitialPrompt(proof,bytes),true);
        assertThat(setup.ds.sql).isEmpty();assertThat(setup.consumer.iam.authorityCalls()).isEqualTo(iam);
        assertThat(FrozenInitialInputMeterTest.business(f.accepted)).isEqualTo(before);assertThat(setup.providerHttp).hasValue(0);assertThat(setup.tokenizerHttp).hasValue(0);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("binding.fixture")).resolve("prompt-window.json"),Json.write(Map.of(
            "requestSha256",proof.requestSha256(),"bindingId",proof.bindingId(),"bindingSha256",proof.bindingSha256(),
            "providerControl",1,"tokenizerControl",1,"providerWindowHttp",0,"tokenizerWindowHttp",0,"iamDelta",0,"sqlDuringPreparation",List.of(),
            "businessContentSha256",Digest.sha256(Json.write(before))))); }
        catch(java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        FrozenInitialPromptFixture.record("window",wire,proof.requestSha256());return null;
      });
    }
  }
}
