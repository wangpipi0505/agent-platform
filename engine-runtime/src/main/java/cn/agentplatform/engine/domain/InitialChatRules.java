package cn.agentplatform.engine.domain;

import cn.agentplatform.common.PlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Pure finite source shape, never an authority token or a substitute for locked relational checks. */
public final class InitialChatRules {
    private InitialChatRules() {}
    private static final Set<String> CONFIG = Set.of("modelEntryId","modelBinding","prompt","systemPrompt",
            "params","sampling","output","context","timeout","budgets","requiredLabels","maxOutputTokens");
    private static final Set<String> EXTERNAL = Set.of("sessionId","chatSession","chatTurn","sessionHistory",
            "history","historyRefs","materialRefs","tools","toolDefinitions","dataSources","frozenPrompts",
            "modelOverride","overrides","summary","summarizer","retry","compressedMaterials");
    public static void requireSources(JsonNode snapshot,JsonNode inputs) {
        if(!snapshot.isObject()||!"CHAT".equals(snapshot.path("type").asText())||!inputs.isObject())throw denied();
        for(String key:List.of("debugSnapshot","evalCandidate","controlledDraftId","controlledReplay","reexecution",
                "debugControls","controlledSkillVersion","guard","sessionId","chatSession","chatTurn","sessionHistory","history","historyRefs","materialRefs"))
            if(snapshot.has(key)||inputs.has(key))throw denied();
        if(snapshot.has("definition")&&!snapshot.path("definition").isNull()&&!snapshot.path("definition").isEmpty())throw denied();
        var config=snapshot.path("config");if(!config.isObject())throw denied();
        config.fieldNames().forEachRemaining(k->{if(!CONFIG.contains(k))throw denied();});
        rejectExternal(config,0);rejectExternal(inputs,0);
        for(String k:List.of("prompt","systemPrompt"))if(config.has(k)&&!config.path(k).isTextual())throw denied();
        for(String k:List.of("params","sampling","output","context","timeout","budgets"))if(config.has(k)&&!config.path(k).isObject())throw denied();
        long entry=entryId(snapshot);
        if(config.has("modelBinding")&&(!config.path("modelBinding").isObject()||config.path("modelBinding").size()!=1
                ||positive(config.path("modelBinding").path("modelEntryId"))!=entry))throw denied();
        for(String k:List.of("temperature","topP"))if(config.path("sampling").has(k)&&!config.path("sampling").path(k).isNumber())throw denied();
        if(config.has("maxOutputTokens"))limit(config.path("maxOutputTokens"));
        if(config.path("output").has("maxTokens"))limit(config.path("output").path("maxTokens"));
        var fields=config.path("params").fields();while(fields.hasNext()) {
            var field=fields.next();var value=field.getValue();
            switch(field.getKey()) {
                case "maxOutputTokens" -> limit(value);
                case "temperature","topP","frequencyPenalty","presencePenalty" -> {if(!value.isNumber())throw denied();}
                case "topK","seed" -> {if(!value.isIntegralNumber())throw denied();}
                case "stop" -> {if(!value.isTextual()&&!value.isArray())throw denied();if(value.isArray())for(var item:value)if(!item.isTextual())throw denied();}
                default -> throw denied();
            }
        }
        if(config.has("requiredLabels")){var labels=config.path("requiredLabels");if(!labels.isArray()||labels.size()>256)throw denied();for(var label:labels)if(!label.isTextual()||label.textValue().isBlank()||label.textValue().length()>256)throw denied();}
        var dependencies=snapshot.path("dependencyVersions");if(!dependencies.isObject()||!dependencies.path("modelRefs").isArray()||dependencies.path("modelRefs").size()!=1)throw denied();
        dependencies.fieldNames().forEachRemaining(k->{if(!k.equals("modelRefs"))throw denied();});
        if(positive(dependencies.path("modelRefs").get(0).path("modelEntryId"))!=entry)throw denied();
        var output=new OutputPolicy(config.path("output"));
        if(output.onInvalid().equals("RETRY")||(config.path("output").has("maxRetries")&&output.maxRetries()!=0))throw denied();
    }
    public static long entryId(JsonNode snapshot){var c=snapshot.path("config");return positive(c.has("modelEntryId")?c.path("modelEntryId"):c.path("modelBinding").path("modelEntryId"));}
    public static void requireAgent(JsonNode definition,Object nodeDef,Object nodeType,long step){
        if(nodeDef!=null||!"AGENT".equals(nodeType)||step!=1||!definition.isObject()||definition.size()!=1||!"AGENT".equals(definition.path("type").asText()))throw denied();
    }
    private static void rejectExternal(JsonNode value,int depth){
        if(depth>64)throw denied();
        if(value.isObject()){var fields=value.fields();while(fields.hasNext()){var f=fields.next();if(EXTERNAL.contains(f.getKey()))throw denied();rejectExternal(f.getValue(),depth+1);}}
        else if(value.isArray())for(var item:value)rejectExternal(item,depth+1);
    }
    private static long positive(JsonNode value){if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1)throw denied();return value.longValue();}
    private static void limit(JsonNode value){if(positive(value)>Integer.MAX_VALUE)throw denied();}
    private static PlatformException denied(){return new PlatformException(403,"RUN_AUTHORITY_DENIED","运行授权或材料范围不匹配");}
}
