package cn.agentplatform.model.application;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Pure, stream-only correspondence at this instant; neither SDK provenance nor send/replay authority. */
final class FrozenInitialWireGuard {
  private FrozenInitialWireGuard() {}
  private static final int MAX_BYTES=2097152;
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
          .maxNumberLength(128).maxStringLength(MAX_BYTES).build()).build())
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static void require(FrozenModelBindingVerifier.VerifiedFrozenModelBinding binding,
      byte[] acceptedCanonicalUtf8,byte[] encodedStreamBody) {
    long started=System.nanoTime();checkTime(started);
    if(binding==null)throw changed();binding.requireVerified();
    byte[] accepted=copy(acceptedCanonicalUtf8),encoded=copy(encodedStreamBody);
    // The shared converter checks the original accepted RAW hash, live mapping and option support.
    var expected=ModelCalls.frozenInitialPrompt(binding,accepted);
    checkTime(started);binding.requireVerified();
    JsonNode body=parse(encoded);object(body);
    keys(body,Set.of("messages","model","max_tokens","temperature","top_p","frequency_penalty",
        "presence_penalty","seed","stop","response_format","stream","stream_options","tools"));
    var options=(OpenAiChatOptions)expected.getOptions();
    text(body,"model",options.getModel());number(body,"max_tokens",options.getMaxTokens());
    number(body,"temperature",options.getTemperature());number(body,"top_p",options.getTopP());
    number(body,"frequency_penalty",options.getFrequencyPenalty());number(body,"presence_penalty",options.getPresencePenalty());
    number(body,"seed",options.getSeed());
    JsonNode stop=body.get("stop");
    if(options.getStop()==null){absent(stop);}else{
      required(stop);if(!stop.isArray())throw invalid();
      if(stop.size()!=options.getStop().size())throw changed();
      for(int i=0;i<stop.size();i++)textValue(stop.get(i),options.getStop().get(i));
    }
    JsonNode messages=body.get("messages");required(messages);if(!messages.isArray())throw invalid();
    if(messages.size()!=2 || expected.getInstructions().size()!=2)throw changed();
    for(int i=0;i<2;i++){
      JsonNode message=messages.get(i);object(message);keys(message,Set.of("role","content"));
      text(message,"role",i==0?"system":"user");text(message,"content",expected.getInstructions().get(i).getText());
    }
    var format=options.getResponseFormat();JsonNode actualFormat=body.get("response_format");
    if(format==null){absent(actualFormat);}else{
      required(actualFormat);object(actualFormat);keys(actualFormat,Set.of("type","json_schema"));
      text(actualFormat,"type",format.getType().name().toLowerCase(Locale.ROOT));
      var schema=format.getJsonSchema();JsonNode actualSchema=actualFormat.get("json_schema");
      if(schema==null){absent(actualSchema);}else{
        required(actualSchema);object(actualSchema);keys(actualSchema,Set.of("name","strict","schema"));
        text(actualSchema,"name",schema.getName());bool(actualSchema,"strict",schema.getStrict());
        // Only the public schema DATA subtree is converted; an expected request body is never encoded.
        JsonNode schemaData=JSON.valueToTree(schema.getSchema());
        if(!schemaData.equals(actualSchema.get("schema")))throw changed();
      }
    }
    bool(body,"stream",true);
    JsonNode streamOptions=body.get("stream_options");required(streamOptions);object(streamOptions);
    keys(streamOptions,Set.of("include_usage"));bool(streamOptions,"include_usage",true);
    // Pinned 1.0 emits precisely this empty tools array even for the two-text-message profile.
    JsonNode tools=body.get("tools");required(tools);if(!tools.isArray())throw invalid();if(!tools.isEmpty())throw changed();
    checkTime(started);binding.requireVerified();
  }
  private static byte[] copy(byte[] bytes){if(bytes==null||bytes.length<1||bytes.length>MAX_BYTES)throw invalid();return bytes.clone();}
  private static JsonNode parse(byte[] bytes){
    try{
      String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      try(var parser=JSON.createParser(text)){
        JsonNode node=JSON.readTree(parser);if(node==null||parser.nextToken()!=null)throw invalid();return node;
      }
    }catch(java.io.IOException|IllegalArgumentException e){throw invalid();}
  }
  private static void object(JsonNode node){if(node==null||!node.isObject())throw invalid();}
  private static void keys(JsonNode node,Set<String> allowed){node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw changed();});}
  private static void required(JsonNode node){if(node==null)throw changed();}
  private static void absent(JsonNode node){if(node!=null)throw changed();}
  private static void text(JsonNode body,String key,String expected){textValue(body.get(key),expected);}
  private static void textValue(JsonNode node,String expected){required(node);if(!node.isTextual())throw invalid();if(!Objects.equals(node.textValue(),expected))throw changed();}
  private static void bool(JsonNode body,String key,Boolean expected){
    JsonNode node=body.get(key);if(expected==null){absent(node);return;}required(node);if(!node.isBoolean())throw invalid();if(node.booleanValue()!=expected)throw changed();
  }
  private static void number(JsonNode body,String key,Number expected){
    JsonNode node=body.get(key);if(expected==null){absent(node);return;}required(node);if(!node.isNumber())throw invalid();
    if(expected instanceof Integer && (!node.isIntegralNumber()||!node.canConvertToInt()))throw invalid();
    if(node.decimalValue().compareTo(new BigDecimal(expected.toString()))!=0)throw changed();
  }
  private static void checkTime(long started){if(Thread.currentThread().isInterrupted()||System.nanoTime()-started>=2_000_000_000L)throw new PlatformException(503,"SYS-MODEL-503","模型请求校验暂不可用");}
  private static PlatformException changed(){return new PlatformException(403,"MATERIAL_SCOPE_MISSING_OR_CHANGED","材料范围缺失或已改变");}
  private static PlatformException invalid(){return new PlatformException(400,"MODEL_INPUT_INVALID","模型输入无效");}
}
