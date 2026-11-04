package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.ConnectionResolutionReceipts;
import cn.agentplatform.skill.infra.ApprovedConnectionExecution;
import cn.agentplatform.skill.infra.SkillClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 受控连接解析（skill 侧）：向 app 取"某技能可用的连接事实"，并留下回执。
 *
 * 三条边界：
 * 1) 不解析就是不缓存的代理——每次都向 app 要，撤权、停用、换版在下一次调用立即生效；
 * 2) 回执与解析同一次请求完成，回执写失败就不把事实交出去（否则审计链会缺一段）；
 * 3) app 提供当前有效 grant 事实；分发门禁按服务端精确技能身份与不可变执行用途裁决，
 *    授权通过后才写可交给适配器的回执，批准档案本身不是使用授权。
 */
@Component
public class ApprovedConnectionResolver {

    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{2,47}$");
    static final Pattern SKILL_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    static final Pattern ENVIRONMENT = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    static final Set<String> RUNTIME_AUTH_KINDS = Set.of("NONE", "BEARER", "API_KEY");
    static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    /** 单次调用可引用的受控连接上限：超出即拒绝，避免一次调用被拼成任意多端点。 */
    static final int MAX_REFS = 8;
    /** 操作名与 app 侧 operationAllowlist 同一词汇表（见 ConnectionProfiles.OPERATION）。 */
    static final Pattern OPERATION = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.\\-/:]{0,127}$");

    private final SkillClients clients;
    private final ConnectionResolutionReceipts receipts;
    private final String appUrl;

    public ApprovedConnectionResolver(SkillClients clients, ConnectionResolutionReceipts receipts,
            @Value("${agent.services.app}") String appUrl) {
        this.clients = Objects.requireNonNull(clients);
        this.receipts = Objects.requireNonNull(receipts);
        this.appUrl = Objects.requireNonNull(appUrl).replaceAll("/+$", "");
    }

    public Map<String, Object> resolve(String skillCode, String profileId, int profileRev, String environment) {
        return resolve(skillCode, profileId, profileRev, environment, null);
    }

    /**
     * 带调用范围的解析：回执额外记下 run/call/attempt/operation（V318），
     * 用于把"这次解析"与引擎的具体调用逐条对齐。
     */
    public Map<String, Object> resolve(String skillCode, String profileId, int profileRev, String environment,
            Map<String, Object> scope) {
        Actor actor = Actor.current();
        Map<String, Object> facts = facts(skillCode, profileId, profileRev, environment);
        return receipts.record(actor, skillCode, facts, environment, scope);
    }

    private Map<String, Object> facts(String skillCode, String profileId, int profileRev, String environment) {
        return facts(skillCode, profileId, profileRev, environment, null, 0, null);
    }

    private Map<String, Object> facts(String skillCode, String profileId, int profileRev, String environment,
            String bindingId, int bindingRev, String grantee) {
        if (!SKILL_CODE.matcher(skillCode).matches())
            throw new PlatformException(400, "INVALID_INPUT", "技能编码格式不合法: " + skillCode);
        if (!ID.matcher(profileId).matches())
            throw new PlatformException(400, "INVALID_INPUT", "连接档案编码格式不合法: " + profileId);
        if (profileRev < 1)
            throw new PlatformException(400, "INVALID_INPUT", "profileRev 必须不小于 1");
        String env = environment == null || environment.isBlank() ? null : environment;
        if (env != null && !ENVIRONMENT.matcher(env).matches())
            throw new PlatformException(400, "INVALID_INPUT", "environment 格式不合法: " + env);

        String url = appUrl + "/internal/connection-profiles/" + encode(profileId) + "/versions/" + profileRev
                + "/resolve";
        var query = new java.util.StringJoiner("&");
        if (env != null) query.add("environment=" + encode(env));
        if (bindingId != null) query.add("bindingId=" + encode(bindingId)).add("bindingRev=" + bindingRev)
                .add("grantee=" + encode(grantee));
        if (query.length() > 0) url += "?" + query;
        return shape(clients.get(url), profileId, profileRev);
    }

    /**
     * 受控调用的分发前复核（INT-02 子步3，契约 §5.2）。
     *
     * 语义：
     * 1) 引用来源只有两处——本次请求里引擎下发的 bindingRefs，或技能契约自带的 bindingRefs；
     *    两处都空 = 该技能没有引用受控连接，走原有 legacy 路径，本方法不产生任何解析与回执。
     * 2) 每条引用都要重新向 app 解析一次（不做本地缓存）：撤权、停用、换版在下一次调用立即生效。
     * 3) 操作名必须在该档案的操作白名单内；引用声明了摘要时，摘要必须与当前解析一致，
     *    否则说明"配置在批准后被换过"，按 409 拒绝而不是默认沿用旧端点。
     * 4) 调用方不能自带 tenantId：租户只来自已验票据，引用里出现租户字段即视为伪造。
     */
    public Map<String, Object> gate(Map<String, Object> contract, Map<String, Object> request, String skillCode) {
        List<Map<String, Object>> refs = bindingRefs(request.get("bindingRefs"),
                contract == null ? null : contract.get("bindingRefs"));
        if (refs.isEmpty()) return Map.of("refs", List.of());
        Actor actor = Actor.current();
        // code 已由 SkillExecution 与精确 skill_version 核对；请求/ref 自报身份与用途从不参与授权。
        String grantee = "skill." + skillCode;
        Map<?, ?> execution = contract != null && contract.get("execution") instanceof Map<?, ?> e ? e : Map.of();
        String use = Boolean.TRUE.equals(execution.get("sideEffect")) ? "write"
                : Boolean.TRUE.equals(execution.get("readonly")) ? "read" : "execute";
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("runPublicId", text(request.get("runId")));
        scope.put("callId", request.get("callId"));
        scope.put("attemptId", request.get("attemptId"));
        List<Map<String, Object>> checked = new java.util.ArrayList<>();
        for (Map<String, Object> ref : refs) {
            if (ref.containsKey("tenantId"))
                throw new PlatformException(400, "INVALID_INPUT", "连接引用不允许携带 tenantId，租户只来自已验票据");
            String profileId = text(ref.get("profileId"));
            String operation = text(ref.get("operation"));
            Object rawRev = ref.get("profileRev");
            if (profileId == null || operation == null || !(rawRev instanceof Number rev)
                    || rev.intValue() < 1)
                throw new PlatformException(400, "INVALID_INPUT", "连接引用必须给出 profileId/profileRev/operation");
            if (!OPERATION.matcher(operation).matches())
                throw new PlatformException(400, "INVALID_INPUT", "连接引用操作名格式不合法: " + operation);
            String declared = text(ref.get("resolutionDigest"));
            if (declared != null && !DIGEST.matcher(declared).matches())
                throw new PlatformException(400, "INVALID_INPUT", "连接引用摘要格式不合法: " + declared);

            Map<String, Object> callScope = new LinkedHashMap<>(scope);
            callScope.put("operation", operation);
            String environment = text(ref.get("environment"));
            String bindingId = text(ref.get("bindingId"));
            Object bindingRev = ref.get("bindingRev");
            if (bindingId == null || !ID.matcher(bindingId).matches()
                    || !(bindingRev instanceof Number bindingRevision) || bindingRevision.intValue() < 1
                    || bindingRevision.doubleValue() != bindingRevision.intValue())
                throw new PlatformException(400, "CONNECTION_BINDING_REQUIRED", "受控执行必须固定 bindingId/bindingRev");
            Map<String, Object> facts = facts(skillCode, profileId, rev.intValue(), environment,
                    bindingId, bindingRevision.intValue(), grantee);
            List<?> allow = facts.get("operationAllowlist") instanceof List<?> list ? list : List.of();
            if (!allow.contains(operation))
                throw new PlatformException(400, "CONNECTION_OPERATION_NOT_ALLOWED",
                        "该连接档案未批准本次操作: " + profileId + " / " + operation);
            List<?> declaredUses = facts.get("allowedUses") instanceof List<?> list ? list : List.of();
            List<?> grants = facts.get("grants") instanceof List<?> list ? list : List.of();
            boolean granted = declaredUses.contains(use) && grants.stream().anyMatch(value ->
                    value instanceof Map<?, ?> grant && grantee.equals(grant.get("grantee"))
                            && grant.get("revokedAt") == null && grant.get("revoked_at") == null
                            && grant.get("allowedUses") instanceof List<?> uses && uses.contains(use));
            if (!granted)
                throw new PlatformException(403, "CONNECTION_USE_NOT_GRANTED",
                        "连接未授予当前技能所需用途: " + profileId + " / " + grantee + " / " + use);
            Map<?, ?> binding = ((List<?>) facts.get("bindings")).stream()
                    .filter(b -> b instanceof Map<?, ?> m && bindingId.equals(m.get("bindingId"))
                            && m.get("bindingRev") instanceof Number n && n.intValue() == bindingRevision.intValue()
                            && (environment == null || environment.equals(m.get("environment"))))
                    .map(b -> (Map<?, ?>) b).findFirst()
                    .orElseThrow(() -> new PlatformException(409, "CONNECTION_BINDING_UNAVAILABLE",
                            "原绑定修订不存在、未批准或已停用: " + bindingId + "@" + bindingRevision.intValue()));
            Object resolvedDigest = facts.get("resolutionDigest");
            if (declared != null && !declared.equals(String.valueOf(resolvedDigest)))
                throw new PlatformException(409, "CONNECTION_BINDING_CHANGED",
                        "引用的连接在批准后已变更，请按当前批准重新取用: " + profileId);
            // 回执只记录本次使用方真正获授的用途，不把其他 grant 的用途并集写成执行授权。
            facts.put("grantedUses", List.of(use));
            var executionBinding = new LinkedHashMap<String, Object>();
            for (String key : List.of("bindingId", "bindingRev", "baseEndpoint", "environment"))
                executionBinding.put(key, binding.get(key));
            executionBinding.put("resolutionDigest", resolvedDigest);
            callScope.put("executionBinding", executionBinding);
            facts = receipts.record(actor, skillCode, facts, environment, callScope);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("profileId", profileId);
            entry.put("profileRev", rev.intValue());
            entry.put("operation", operation);
            entry.put("authKind", facts.get("authKind"));
            entry.put("resolutionDigest", resolvedDigest);
            entry.put("receiptId", facts.get("receiptId"));
            entry.put("executionBinding", executionBinding);
            entry.put("executionContext", new ApprovedConnectionExecution(bindingId, bindingRevision.intValue(),
                    Objects.toString(binding.get("baseEndpoint"), ""), Objects.toString(binding.get("secretRef"), ""),
                    Objects.toString(facts.get("authKind")), Objects.toString(facts.get("protocol")),
                    (Map<String, Object>) facts.get("endpointPolicy")));
            checked.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refs", checked);
        return out;
    }

    public Map<String, Object> executionContract(Map<String, Object> contract, Map<String, Object> request, String code) {
        List<?> checked = (List<?>) gate(contract, request, code).get("refs");
        if (checked.isEmpty()) return contract;
        if (checked.size() != 1)
            throw new PlatformException(400, "CONNECTION_BINDING_AMBIGUOUS", "单次技能执行必须精确固定一个连接");
        var entry = (Map<?, ?>) checked.getFirst();
        return ((ApprovedConnectionExecution) entry.get("executionContext")).apply(contract);
    }

    /** 多重来源合并：请求引用优先（引擎按调用冻结下发），契约声明只作缺省。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> bindingRefs(Object first, Object second) {
        // Arrays.asList 而不是 List.of：两处来源都可能为 null（旧技能契约没有 bindingRefs）。
        for (Object candidate : java.util.Arrays.asList(first, second)) {
            if (candidate instanceof List<?> list && !list.isEmpty()) {
                var out = new java.util.ArrayList<Map<String, Object>>();
                for (Object item : list)
                    if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
                    else throw new PlatformException(400, "INVALID_INPUT", "连接引用必须是对象数组");
                if (out.size() > MAX_REFS)
                    throw new PlatformException(400, "INVALID_INPUT",
                            "单次调用最多引用 " + MAX_REFS + " 个受控连接");
                return out;
            }
        }
        return List.of();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String out = String.valueOf(value);
        return out.isBlank() ? null : out;
    }

    /**
     * 形状校验：远端少字段、摘要不合法、鉴权形态不在开放集合，都按协议破坏处理（502），
     * 不猜默认值——把"看不懂"降级成"没鉴权"是最危险的失败方式。
     */
    static Map<String, Object> shape(JsonNode data, String profileId, int profileRev) {
        if (data == null || !data.isObject()) throw malformed("空响应或非对象");
        if (!profileId.equals(data.path("profileId").asText())) throw malformed("profileId 不一致");
        if (!data.path("profileRev").isIntegralNumber() || data.path("profileRev").asInt() != profileRev)
            throw malformed("profileRev 不一致");
        String digest = data.path("resolutionDigest").asText("");
        if (!DIGEST.matcher(digest).matches()) throw malformed("resolutionDigest 不是 64 位小写十六进制");
        String authKind = data.path("authKind").asText("");
        if (!RUNTIME_AUTH_KINDS.contains(authKind))
            throw new PlatformException(400, "CONFIG_AUTH_SCHEME_UNSUPPORTED",
                    "该鉴权形态尚未开放给运行时: " + authKind);
        for (String array : List.of("operationAllowlist", "allowedUses", "grantedUses", "bindings", "grants"))
            if (!data.path(array).isArray()) throw malformed(array + " 必须是数组");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", profileId);
        out.put("profileRev", profileRev);
        out.put("status", data.path("status").asText(""));
        out.put("protocol", data.path("protocol").asText(""));
        out.put("operationAllowlist", Json.read(data.path("operationAllowlist").toString(), List.class));
        out.put("endpointPolicy", Json.read(data.path("endpointPolicy").toString(), Map.class));
        out.put("authScheme", Json.read(data.path("authScheme").toString(), Map.class));
        out.put("authKind", authKind);
        out.put("identityPolicy", Json.read(data.path("identityPolicy").toString(), Map.class));
        out.put("inputContractRefs", Json.read(data.path("inputContractRefs").toString(), List.class));
        out.put("outputContractRefs", Json.read(data.path("outputContractRefs").toString(), List.class));
        out.put("allowedUses", Json.read(data.path("allowedUses").toString(), List.class));
        out.put("grantedUses", Json.read(data.path("grantedUses").toString(), List.class));
        out.put("bindings", Json.read(data.path("bindings").toString(), List.class));
        out.put("grants", Json.read(data.path("grants").toString(), List.class));
        out.put("resolutionDigest", digest);
        out.put("resolvedAt", data.path("resolvedAt").asText(""));
        return out;
    }

    private static PlatformException malformed(String detail) {
        return new PlatformException(502, "CONNECTION_RESOLUTION_MALFORMED", "连接解析回执不符合契约: " + detail);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
