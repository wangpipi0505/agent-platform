package cn.agentplatform.engine.domain;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.Digest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Pure finite source shape, never an authority token or a substitute for locked relational checks. */
public final class InitialChatRules {
    private InitialChatRules() {}
    private static final Set<String> CONFIG = Set.of("modelEntryId","modelBinding","prompt","systemPrompt",
            "params","sampling","output","context","timeout","budgets","requiredLabels","maxOutputTokens",
            "promptSource","promptRefs");
    private static final Set<String> EXTERNAL = Set.of("sessionId","chatSession","chatTurn","sessionHistory",
            "history","historyRefs","materialRefs","tools","toolDefinitions","dataSources","frozenPrompts",
            "modelOverride","overrides","summary","summarizer","retry","compressedMaterials");
    public static void requireSources(JsonNode snapshot,JsonNode inputs) {
        requireSources(snapshot, inputs, false);
    }

    public static void requireEvaluationSources(JsonNode snapshot, JsonNode inputs) {
        requireSources(snapshot, inputs, true);
        evaluationMessages(snapshot);
    }

    private static void requireSources(JsonNode snapshot, JsonNode inputs, boolean evaluation) {
        if(!snapshot.isObject()||!"CHAT".equals(snapshot.path("type").asText())||!inputs.isObject())throw denied();
        for(String key:List.of("debugSnapshot","evalCandidate","controlledDraftId","controlledReplay","reexecution",
                "debugControls","controlledSkillVersion","guard","sessionId","chatSession","chatTurn","sessionHistory","history","historyRefs","materialRefs"))
            if ((snapshot.has(key) && !(evaluation && key.equals("chatSession"))) || inputs.has(key)) throw denied();
        for (String key : List.of("evaluationConversation", "evaluationHistory"))
            if ((!evaluation && snapshot.has(key)) || inputs.has(key)) throw denied();
        if(snapshot.has("definition")&&!snapshot.path("definition").isNull()&&!snapshot.path("definition").isEmpty())throw denied();
        var config=snapshot.path("config");if(!config.isObject())throw denied();
        promptConfig(snapshot);
        if(config.has("promptRefs")&&!config.has("promptSource"))throw denied();
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
        boolean template="TEMPLATE".equals(config.path("promptSource").asText());
        if (config.has("promptSource") && !template && !evaluation) throw promptMismatch();
        dependencies.fieldNames().forEachRemaining(k->{
            if(k.equals("modelRefs"))return;
            if(template&&k.equals("frozenPrompts"))return;
            if((template||evaluation)&&k.equals("skillRefs")&&dependencies.path(k).isArray()&&dependencies.path(k).isEmpty())return;
            throw denied();
        });
        if(positive(dependencies.path("modelRefs").get(0).path("modelEntryId"))!=entry)throw denied();
        var output=new OutputPolicy(config.path("output"));
        if(output.onInvalid().equals("RETRY")||(config.path("output").has("maxRetries")&&output.maxRetries()!=0))throw denied();
    }

    /** Only the candidate admission path can write this protected snapshot material. */
    public static List<Map<String, Object>> evaluationMessages(JsonNode snapshot) {
        boolean session = snapshot.has("chatSession");
        if (session != snapshot.has("evaluationConversation") || session != snapshot.has("evaluationHistory"))
            throw denied();
        if (!session) return List.of();
        var pointer = snapshot.path("chatSession");
        long turn = positive(pointer.path("turnNo"));
        var history = snapshot.path("evaluationHistory").path("priorTurns");
        if (turn > 200 || !pointer.path("sessionId").asText().matches("es_[a-f0-9]{48}")
                || !history.isArray() || history.size() != turn - 1) throw denied();
        var messages = new ArrayList<Map<String, Object>>(2 * history.size());
        for (var item : history) {
            if (!item.path("runId").asText().matches("r_[0-9a-f]{32}")
                    || !item.path("inputScopeSha256").asText().matches("[a-f0-9]{64}")
                    || !item.path("outputScopeSha256").asText().matches("[a-f0-9]{64}")
                    || !item.path("user").isTextual() || !item.path("assistant").isTextual()) throw denied();
            messages.add(Map.of("role", "user", "content", item.path("user").textValue()));
            messages.add(Map.of("role", "assistant", "content", item.path("assistant").textValue()));
        }
        return List.copyOf(messages);
    }

    public static Set<String> evaluationLabels(JsonNode snapshot) {
        if (!snapshot.has("evaluationHistory")) return Set.of();
        var labels = snapshot.path("evaluationHistory").path("requiredLabels");
        if (!labels.isArray() || labels.size() > 256) throw denied();
        var result = new TreeSet<String>();
        for (var label : labels)
            if (!label.isTextual() || label.textValue().isBlank() || label.textValue().length() > 256
                    || !result.add(label.textValue())) throw denied();
        return Set.copyOf(result);
    }
    public static long entryId(JsonNode snapshot){var c=snapshot.path("config");return positive(c.has("modelEntryId")?c.path("modelEntryId"):c.path("modelBinding").path("modelEntryId"));}

    /** 模型输入仅从已绑定摘要的引用及其核验后的持久正文派生。 */
    public static JsonNode promptConfig(JsonNode snapshot) {
        var config=snapshot.path("config");
        if(!config.has("promptSource"))return config;
        if ("DIRECT".equals(config.path("promptSource").asText()) && !config.has("promptRefs")) return config;
        var refs=config.path("promptRefs");
        var frozen=snapshot.path("dependencyVersions").path("frozenPrompts");
        if(!"TEMPLATE".equals(config.path("promptSource").asText())||config.has("frozenPrompts")
                ||!refs.isArray()||refs.size()!=1||!frozen.isArray()||frozen.size()!=1)throw promptMismatch();
        var ref=refs.get(0);var prompt=frozen.get(0);
        var promptFields=Set.of("code","version","versionNo","status","content","variables","outputContract","sha256");
        prompt.fieldNames().forEachRemaining(key->{if(!promptFields.contains(key))throw promptMismatch();});
        String sha=ref.path("sha256").asText();
        if(!ref.isObject()||ref.size()!=3||!ref.path("code").asText().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                ||!ref.path("version").isIntegralNumber()||!ref.path("version").canConvertToInt()
                ||ref.path("version").intValue()<1||!sha.matches("[0-9a-f]{64}")
                ||!ref.path("code").equals(prompt.path("code"))||!ref.path("version").equals(prompt.path("version"))
                ||prompt.has("versionNo")&&!ref.path("version").equals(prompt.path("versionNo"))
                ||!sha.equals(prompt.path("sha256").asText())||!"PUBLISHED".equals(prompt.path("status").asText())
                ||!prompt.path("content").isTextual()||!prompt.path("variables").isArray()
                ||!prompt.path("outputContract").isObject())throw promptMismatch();
        var candidate=new LinkedHashMap<String,Object>();
        for(String key:List.of("appCode","type","name","description","domain","bizCategory","tags","config","definition"))
            candidate.put(key,snapshot.path(key));
        if(!Digest.sha256(Json.canonical(candidate)).equals(snapshot.path("candidateSha256").asText())
                ||!sha.equals(Digest.sha256(Json.canonical(Map.of("content",prompt.path("content"),
                        "variables",prompt.path("variables"),"outputContract",prompt.path("outputContract"))))))throw promptMismatch();
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)config.deepCopy();
        result.set("frozenPrompts",frozen.deepCopy());
        return result;
    }
    private static PlatformException promptMismatch(){return new PlatformException(409,"PROMPT_TEMPLATE_SNAPSHOT_MISMATCH","冻结模板正文与候选中的精确版本摘要不一致");}
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
