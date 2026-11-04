package cn.agentplatform.app.application;

import cn.agentplatform.common.PlatformException;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** 接入计划纯规则：只转换已声明的字段、直接引用与JSON常量，不访问资源或执行表达式。 */
public final class OnboardingPlan {
    private static final Set<String> TOP = Set.of("fieldsBundle", "values", "selectedOperations", "modelEntryId", "connectionRef",
            "sourceDocument", "routePlan", "importOverrides");
    private static final Set<String> KINDS = Set.of("text", "number", "boolean", "enum", "file");
    private static final Set<String> FORBIDDEN = Set.of("tenant", "tenantid", "principal", "principalid", "authref", "endpoint",
            "script", "expression", "eval", "function", "transform", "__proto__", "prototype", "constructor");
    private static final Pattern PARAM = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,63}");
    private static final Pattern PATH = Pattern.compile("(?:\\$\\.)?[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*|\\[[0-9]{1,6}])*" );
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.\\[\\]-]*)\\s*}}");
    private OnboardingPlan() {}

    /** DRAFT可保留未填完的值；未知配置、危险键或已提供值的类型错误不能落成有效计划。 */
    public static Map<String,Object> normalize(Map<String,Object> plan) {
        if (plan == null) throw invalid("plan", "不能为空");
        checkKeys(plan, TOP, "plan");
        rejectDanger(plan, 0);
        var bundle = normalizeBundle(object(plan.get("fieldsBundle"), "fieldsBundle"));
        var values = object(plan.get("values"), "values");
        var fields = fieldIndex(bundle);
        var copiedValues = new LinkedHashMap<String,Object>();
        for (var entry : values.entrySet()) {
            var field = fields.get(entry.getKey());
            if (field == null) throw invalid("values." + entry.getKey(), "字段未声明");
            copiedValues.put(entry.getKey(), typed(field, entry.getValue(), "values." + entry.getKey()));
        }
        Object selectedRaw = plan.get("selectedOperations");
        if (!(selectedRaw instanceof List<?> selected) || selected.size() > 32)
            throw invalid("selectedOperations", "必须为最多32项的数组");
        var operations = new ArrayList<String>();
        for (Object item : selected) {
            String operation = text(item, "selectedOperations", 256, false);
            if (operations.contains(operation)) throw invalid("selectedOperations", "不能重复");
            operations.add(operation);
        }
        var result = new LinkedHashMap<String,Object>();
        result.put("fieldsBundle", bundle); result.put("values", copiedValues); result.put("selectedOperations", operations);
        if (plan.containsKey("modelEntryId")) result.put("modelEntryId", positive(plan.get("modelEntryId"), "modelEntryId"));
        if (plan.containsKey("connectionRef")) {
            var connection = object(plan.get("connectionRef"), "connectionRef");
            checkKeys(connection, Set.of("profileId", "profileRev", "bindingId", "bindingRev", "operation", "environment"), "connectionRef");
            var ref = new LinkedHashMap<String,Object>();
            for (String key : List.of("profileId", "bindingId")) {
                String id = text(connection.get(key), "connectionRef." + key, 48, false);
                if (!id.matches("[a-z][a-z0-9-]{2,47}")) throw invalid("connectionRef." + key, "标识符无效");
                ref.put(key, id);
            }
            ref.put("profileRev", positive(connection.get("profileRev"), "connectionRef.profileRev"));
            ref.put("bindingRev", positive(connection.get("bindingRev"), "connectionRef.bindingRev"));
            String operation = text(connection.get("operation"), "connectionRef.operation", 128, false);
            if (!operation.matches("[A-Za-z][A-Za-z0-9_.\\-/:]{0,127}")) throw invalid("connectionRef.operation", "操作标识符无效");
            ref.put("operation", operation);
            if (connection.containsKey("environment")) ref.put("environment", text(connection.get("environment"), "connectionRef.environment", 64, false));
            result.put("connectionRef", ref);
        }
        if(plan.containsKey("sourceDocument")) {
            if(!(plan.get("sourceDocument") instanceof String document)||document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_048_576)
                throw invalid("sourceDocument","必须为不超过1MiB的原始规格文本");
            result.put("sourceDocument",document);
        }
        if(plan.containsKey("routePlan")) {
            var route=object(plan.get("routePlan"),"routePlan");checkKeys(route,Set.of("environment","routeProfileId"),"routePlan");
            String environment=text(route.get("environment"),"routePlan.environment",32,false);
            if(!environment.matches("[a-z][a-z0-9-]{0,31}"))throw invalid("routePlan.environment","环境格式无效");
            result.put("routePlan",Map.of("environment",environment,"routeProfileId",text(route.get("routeProfileId"),"routePlan.routeProfileId",64,false)));
        }
        if(plan.containsKey("importOverrides")) {
            var overrides=object(plan.get("importOverrides"),"importOverrides");if(overrides.size()>32)throw invalid("importOverrides","操作不得超过32项");
            var copied=new LinkedHashMap<String,Object>();
            for(var entry:overrides.entrySet()) {
                text(entry.getKey(),"importOverrides.operationId",256,false);var manual=object(entry.getValue(),"importOverrides.operation");
                checkKeys(manual,Set.of("name","description","execution","guard","returnSpec","errorSemantics","timeoutMs","constraints"),"importOverrides.operation");
                copied.put(entry.getKey(),new LinkedHashMap<>(manual));
            }
            result.put("importOverrides",copied);
        }
        return result;
    }

    /** 直接引用只读原始values一次；缺失与显式null不同，不能以真假值判断必填或用默认值覆盖。 */
    public static Map<String,Object> arguments(Map<String,Object> plan) {
        var normalized = normalize(plan);
        var bundle = object(normalized.get("fieldsBundle"), "fieldsBundle");
        var values = object(normalized.get("values"), "values");
        var mappings = object(bundle.get("mappings"), "mappings");
        var result = new LinkedHashMap<String,Object>();
        for (var entry : fieldIndex(bundle).entrySet()) {
            String param = entry.getKey(); var field = entry.getValue();
            if (!mappings.containsKey(param)) {
                if (Boolean.TRUE.equals(field.get("required"))) throw invalid("mappings." + param, "必填参数缺少映射");
                continue;
            }
            var mapping = object(mappings.get(param), "mappings." + param);
            Object value;
            if ("constant".equals(mapping.get("kind"))) value = mapping.get("value");
            else {
                String from = mapping.get("from").toString();
                if (!values.containsKey(from)) {
                    if (Boolean.TRUE.equals(field.get("required"))) throw invalid("values." + from, "必填参数缺少来源值");
                    continue;
                }
                value = values.get(from);
            }
            result.put(param, typed(field, value, "arguments." + param));
        }
        return result;
    }

    /** 只生成原运行器可解释的受限呈现声明；路径存在性与nullable由实际输出Schema/真实验证裁决。 */
    public static Map<String,Object> returnSpec(Map<String,Object> plan) {
        var bundle = object(normalize(plan).get("fieldsBundle"), "fieldsBundle");
        var presentation = object(bundle.get("returns"), "returns");
        String summary = presentation.get("summaryTemplate").toString();
        var spec = new LinkedHashMap<String,Object>();
        spec.put("structuredPath", "$");
        spec.put("modelSummary", Map.of("mode", "TEMPLATE", "tpl", summary.isBlank() ? "查询已完成" : summary));
        var paths = new ArrayList<String>();
        for (Object raw : list(presentation.get("fileRefPaths"), "fileRefPaths", 16)) {
            String path = raw.toString(); paths.add(path.startsWith("$.") ? path : "$." + path);
        }
        spec.put("fileRefPaths", paths);
        if (Boolean.TRUE.equals(presentation.get("cardTemplateEnabled"))) {
            var card = new LinkedHashMap<String,Object>();
            for (Object raw : list(presentation.get("cardFields"), "cardFields", 32)) {
                var item = object(raw, "cardFields"); String from = item.get("from").toString();
                card.put(item.get("label").toString(), "{{" + (from.startsWith("$.") ? from.substring(2) : from) + "}}");
            }
            spec.put("cardTemplate", card);
        }
        return spec;
    }

    /** 从已经过技能Schema门禁的对象输入推导基础表单；不另建引用解析器或伪装复杂Schema可编辑。 */
    public static Map<String,Object> fields(Map<String,Object> inputSchema) {
        if (inputSchema == null || inputSchema.containsKey("$ref") || !"object".equals(inputSchema.get("type")))
            throw invalid("inputSchema", "首版字段表单只支持已解析的object根");
        var properties = inputSchema.containsKey("properties") ? object(inputSchema.get("properties"), "inputSchema.properties") : Map.<String,Object>of();
        if (properties.size() > 64) throw invalid("inputSchema.properties", "字段最多64项");
        var required = new LinkedHashSet<String>();
        for (Object raw : list(inputSchema.getOrDefault("required", List.of()), "inputSchema.required", 64)) {
            String name = param(raw, "inputSchema.required");
            if (!properties.containsKey(name) || !required.add(name)) throw invalid("inputSchema.required", "必填字段未知或重复");
        }
        var definitions = new ArrayList<Map<String,Object>>();
        var mappings = new LinkedHashMap<String,Object>();
        for (var entry : properties.entrySet()) {
            String name = param(entry.getKey(), "inputSchema.properties");
            var property = object(entry.getValue(), "inputSchema.properties." + name);
            if (property.containsKey("$ref") || property.containsKey("oneOf") || property.containsKey("anyOf") || property.containsKey("allOf"))
                throw invalid(name, "复杂Schema须通过原专业配置入口");
            String type = schemaType(property.get("type"), name);
            String kind = switch(type) { case "string" -> "text"; case "number", "integer" -> "number"; case "boolean" -> "boolean";
                case "array" -> { var items = object(property.get("items"), name + ".items");
                    if (!Set.of("string", "number", "integer", "boolean").contains(schemaType(items.get("type"), name + ".items")))
                        throw invalid(name, "数组只支持标量项"); yield "text"; }
                default -> throw invalid(name, "嵌套对象不能伪装为基础文本字段"); };
            var definition = new LinkedHashMap<String,Object>();
            definition.put("param", name); definition.put("label", property.get("title") instanceof String title && !title.isBlank() ? title : name);
            definition.put("kind", kind); definition.put("required", required.contains(name));
            if (property.get("description") instanceof String description) definition.put("description", description);
            if (property.get("enum") instanceof List<?> choices && !choices.isEmpty() && choices.stream().allMatch(value -> value instanceof String)) {
                definition.put("kind", "enum"); definition.put("enumValues", new ArrayList<>(choices));
            }
            definitions.add(definition); mappings.put(name, Map.of("kind", "direct", "from", name));
        }
        return normalizeBundle(Map.of("fields", definitions, "mappings", mappings, "returns", Map.of()));
    }

    /** 诊断集合是服务端实际预检结果；选择不能扩大当前supported范围，也不能借重复ID消除歧义。 */
    public static List<Map<String,Object>> selectOperations(Map<String,Object> plan,List<Map<String,Object>> actualPreview) {
        var normalized = normalize(plan);
        var selected = list(normalized.get("selectedOperations"), "selectedOperations", 32);
        if (selected.isEmpty()) throw invalid("selectedOperations", "至少选择一项当前支持的操作");
        if (actualPreview == null) throw invalid("operations", "缺少实际诊断结果");
        var available = new LinkedHashMap<String,Map<String,Object>>();
        for (var operation : actualPreview) {
            String id = text(operation.get("operationId"), "operations.operationId", 256, false);
            if (available.putIfAbsent(id, operation) != null) throw invalid("operations", "诊断操作ID存在歧义");
        }
        var result = new ArrayList<Map<String,Object>>();
        for (Object id : selected) {
            var operation = available.get(id.toString());
            if (operation == null || !Boolean.TRUE.equals(operation.get("supported"))) throw invalid("selectedOperations", "所选操作不在当前支持集合: " + id);
            result.add(new LinkedHashMap<>(operation));
        }
        return result;
    }

    private static Map<String,Object> normalizeBundle(Map<String,Object> raw) {
        checkKeys(raw, Set.of("fields", "mappings", "returns"), "fieldsBundle");
        var definitions = new ArrayList<Map<String,Object>>();
        var indexed = new LinkedHashMap<String,Map<String,Object>>();
        for (Object item : list(raw.get("fields"), "fieldsBundle.fields", 64)) {
            var field = object(item, "field");
            checkKeys(field, Set.of("param", "label", "kind", "description", "unit", "enumValues", "required"), "field");
            String name = param(field.get("param"), "field.param"), kind = text(field.get("kind"), "field.kind", 16, false);
            if (!KINDS.contains(kind)) throw invalid("field.kind", "不支持的字段类型");
            var copy = new LinkedHashMap<String,Object>(); copy.put("param", name); copy.put("kind", kind);
            copy.put("label", text(field.get("label"), name + ".label", 256, false));
            Object required = field.getOrDefault("required", false);
            if (!(required instanceof Boolean)) throw invalid(name + ".required", "必须为布尔值");
            copy.put("required", required);
            for (String key : List.of("description", "unit")) if (field.containsKey(key)) copy.put(key, text(field.get(key), name + "." + key, 2048, true));
            if (field.containsKey("enumValues") || kind.equals("enum")) {
                var choices = new ArrayList<String>();
                for (Object choice : list(field.getOrDefault("enumValues", List.of()), name + ".enumValues", 100)) {
                    String value = text(choice, name + ".enumValues", 256, true);
                    if (choices.contains(value)) throw invalid(name + ".enumValues", "枚举值重复"); choices.add(value);
                }
                if (kind.equals("enum") && choices.isEmpty()) throw invalid(name + ".enumValues", "枚举字段必须声明允许值");
                copy.put("enumValues", choices);
            }
            if (indexed.putIfAbsent(name, copy) != null) throw invalid(name, "字段参数重复"); definitions.add(copy);
        }
        var mappings = object(raw.get("mappings"), "fieldsBundle.mappings");
        var normalizedMappings = new LinkedHashMap<String,Object>();
        for (var entry : mappings.entrySet()) {
            if (!indexed.containsKey(entry.getKey())) throw invalid("mappings." + entry.getKey(), "目标字段未声明");
            var mapping = object(entry.getValue(), "mappings." + entry.getKey());
            String kind = text(mapping.get("kind"), "mapping.kind", 16, false);
            var copy = new LinkedHashMap<String,Object>(); copy.put("kind", kind);
            if (kind.equals("direct")) {
                if (!mapping.keySet().equals(Set.of("kind", "from"))) throw invalid("mapping", "直接引用只接受kind/from");
                String from = param(mapping.get("from"), "mapping.from");
                if (!indexed.containsKey(from)) throw invalid("mapping.from", "来源字段未声明"); copy.put("from", from);
            } else if (kind.equals("constant")) {
                if (!mapping.keySet().equals(Set.of("kind", "value"))) throw invalid("mapping", "常量只接受kind/value且必须显式给值");
                Object value = mapping.get("value");
                if (value instanceof Map<?,?> || value instanceof Collection<?>) throw invalid("mapping.value", "常量只允许JSON标量");
                copy.put("value", typed(indexed.get(entry.getKey()), value, "mapping.value"));
            } else throw invalid("mapping.kind", "只支持direct或constant");
            normalizedMappings.put(entry.getKey(), copy);
        }
        var presentation = raw.containsKey("returns") ? object(raw.get("returns"), "returns") : Map.<String,Object>of();
        checkKeys(presentation, Set.of("summaryTemplate", "cardTemplateEnabled", "cardFields", "fileRefPaths"), "returns");
        String summary = text(presentation.getOrDefault("summaryTemplate", ""), "returns.summaryTemplate", 4096, true);
        validateTemplate(summary);
        Object enabled = presentation.getOrDefault("cardTemplateEnabled", false);
        if (!(enabled instanceof Boolean)) throw invalid("returns.cardTemplateEnabled", "必须为布尔值");
        var cards = new ArrayList<Map<String,Object>>(); var labels = new HashSet<String>();
        for (Object item : list(presentation.getOrDefault("cardFields", List.of()), "returns.cardFields", 32)) {
            var card = object(item, "cardFields"); if (!card.keySet().equals(Set.of("label", "from"))) throw invalid("cardFields", "仅接受label/from");
            String label = text(card.get("label"), "cardFields.label", 128, false), from = path(card.get("from"), "cardFields.from");
            if (!labels.add(label)) throw invalid("cardFields.label", "展示名重复"); cards.add(Map.of("label", label, "from", from));
        }
        if (Boolean.TRUE.equals(enabled) && cards.isEmpty()) throw invalid("returns.cardFields", "启用卡片须声明字段");
        var paths = new ArrayList<String>();
        for (Object value : list(presentation.getOrDefault("fileRefPaths", List.of()), "returns.fileRefPaths", 16)) {
            String selected = path(value, "returns.fileRefPaths"); if (paths.contains(selected)) throw invalid("fileRefPaths", "引用路径重复"); paths.add(selected);
        }
        var normalizedReturns = new LinkedHashMap<String,Object>(); normalizedReturns.put("summaryTemplate", summary);
        normalizedReturns.put("cardTemplateEnabled", enabled); normalizedReturns.put("cardFields", cards); normalizedReturns.put("fileRefPaths", paths);
        return new LinkedHashMap<>(Map.of("fields", definitions, "mappings", normalizedMappings, "returns", normalizedReturns));
    }

    private static Object typed(Map<String,Object> field,Object value,String location) {
        if (value == null) return null;
        String kind = field.get("kind").toString();
        boolean valid = switch(kind) {
            case "boolean" -> value instanceof Boolean;
            case "number" -> value instanceof Number number && finite(number);
            case "enum" -> value instanceof String && list(field.get("enumValues"), "enumValues", 100).contains(value);
            case "file" -> value instanceof String text && text.matches("[A-Za-z0-9_.:-]{1,192}");
            default -> value instanceof String || value instanceof List<?>;
        };
        if (!valid) throw invalid(location, "提供值与声明字段类型不一致");
        return copyValue(value, 0);
    }
    private static Object copyValue(Object value,int depth) {
        if (depth > 16) throw invalid("values", "嵌套超过16层");
        if (value == null || value instanceof Boolean || value instanceof Number number && finite(number)) return value;
        if (value instanceof String text) return text(text, "values", 16384, true);
        if (value instanceof List<?> list) {
            if (list.size() > 256) throw invalid("values", "数组最多256项");
            var copy = new ArrayList<Object>(); for (Object item : list) copy.add(copyValue(item, depth + 1)); return copy;
        }
        throw invalid("values", "只支持JSON标量和标量数组");
    }
    private static boolean finite(Number value) { return !(value instanceof Double d) || Double.isFinite(d) ? !(value instanceof Float f) || Float.isFinite(f) : false; }
    private static void validateTemplate(String template) {
        if (template.contains("${") || template.toLowerCase(Locale.ROOT).contains("<script")) throw invalid("summaryTemplate", "不接受可执行表达式");
        var matcher = PLACEHOLDER.matcher(template); var remainder = new StringBuffer();
        while (matcher.find()) { path(matcher.group(1), "summaryTemplate"); matcher.appendReplacement(remainder, ""); }
        matcher.appendTail(remainder);
        if (remainder.indexOf("{{") >= 0 || remainder.indexOf("}}") >= 0) throw invalid("summaryTemplate", "仅支持受限字段占位符");
    }
    private static String path(Object value,String location) {
        String selected = text(value, location, 256, false);
        if (!PATH.matcher(selected).matches()) throw invalid(location, "仅支持字段/数字下标路径"); return selected;
    }
    private static String schemaType(Object raw,String location) {
        if (raw instanceof String text) return text;
        if (raw instanceof List<?> union) {
            var types = union.stream().filter(type -> !"null".equals(type)).toList();
            if (types.size() == 1 && types.getFirst() instanceof String text) return text;
        }
        throw invalid(location, "类型未明确或包含不支持的联合");
    }
    private static long positive(Object value,String location) {
        if (!(value instanceof Number number)) throw invalid(location, "必须为精确正整数");
        try { long result = new BigDecimal(number.toString()).longValueExact(); if (result < 1) throw new ArithmeticException(); return result; }
        catch (ArithmeticException | NumberFormatException error) { throw invalid(location, "必须为精确正整数"); }
    }
    private static String param(Object value,String location) {
        String name = text(value, location, 64, false);
        if (!PARAM.matcher(name).matches() || FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) throw invalid(location, "参数名无效或保留"); return name;
    }
    private static String text(Object value,String location,int max,boolean empty) {
        if (!(value instanceof String text) || text.length() > max || !empty && text.isBlank() || text.chars().anyMatch(c -> c < 32 && c != 9 && c != 10 && c != 13))
            throw invalid(location, "字符串为空、过长或含控制字符"); return text;
    }
    private static void checkKeys(Map<String,Object> value,Set<String> allowed,String location) {
        if (!allowed.containsAll(value.keySet())) throw invalid(location, "包含未登记字段");
    }
    private static void rejectDanger(Object value,int depth) {
        if (depth > 20) throw invalid("plan", "结构嵌套过深");
        if (value instanceof Map<?,?> map) for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || FORBIDDEN.contains(key.toLowerCase(Locale.ROOT))) throw invalid("plan", "禁止客户端身份、端点或执行表达式字段");
            rejectDanger(entry.getValue(), depth + 1);
        } else if (value instanceof List<?> list) for (Object item : list) rejectDanger(item, depth + 1);
        else if (value instanceof String text && (text.contains("${") || text.toLowerCase(Locale.ROOT).contains("<script") || text.toLowerCase(Locale.ROOT).startsWith("javascript:")))
            throw invalid("plan", "不接受可执行表达式");
    }
    private static LinkedHashMap<String,Map<String,Object>> fieldIndex(Map<String,Object> bundle) {
        var result = new LinkedHashMap<String,Map<String,Object>>();
        for (Object raw : list(bundle.get("fields"), "fields", 64)) { var field = object(raw, "field"); result.put(field.get("param").toString(), field); } return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw,String location) {
        if (!(raw instanceof Map<?,?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw invalid(location, "必须为对象"); return (Map<String,Object>)raw;
    }
    private static List<?> list(Object raw,String location,int max) {
        if (!(raw instanceof List<?> list) || list.size() > max) throw invalid(location, "必须为有界数组"); return list;
    }
    private static PlatformException invalid(String location,String message) { return new PlatformException(400, "INVALID_INPUT", "接入计划 " + location + "：" + message); }
}
