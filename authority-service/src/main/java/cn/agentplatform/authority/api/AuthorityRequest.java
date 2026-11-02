package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.StrictJson;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 决策/票据请求的形状白名单。
 * <p>字段集合刻意很窄：{@code operation} 决定端点语义，{@code target/action/resource} 必须与目录行
 * 逐字段相等（会原样进票据并成为平台侧的路由键），{@code nonce} 绑定断言与请求。
 * <p>{@code service_ticket} 出现在 {@code authorize_workload}（workload 票）与 {@code authorize}
 * （上游已委派的票）；{@code subject_token} 只在 {@code authorize}/`exchange` 出现（gateway 持有的 portal
 * 用户令牌）；{@code decision_id/grant_ref/operation_id} 只在 {@code forward} 出现（转发链的定位三件套）。
 * <p>{@code gateway_events_opening} 与 {@code query_ticket} 是 exchange 的**两个互斥变体**（EX-44 §2.3）：
 * 前者是变体 A（SSE 观测 opening，S0），后者是变体 B（拿 S0 票换 retained 观测票，S1）。
 * 两者都是判别位而不是能力开关：只有布尔 {@code true}/非空字符串才按对应语义校验，拼成字符串等非法形状一律
 * 400——静默忽略会把"opening 的前置校验"降级成一次普通换票，或让 {@code query_ticket} 变成没人校验的装饰字段。
 * <p>不接受额外字段是防"协议悄悄扩张"：新增字段必须先改这里的白名单和对应的平台消费者。
 */
public record AuthorityRequest(
        String operation,
        ServiceCall call,
        String nonce,
        String serviceTicket,
        String subjectToken,
        String queryTicket,
        String decisionId,
        String grantRef,
        String operationId,
        boolean gatewayEventsOpening,
        JsonNode raw) {

    public static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> RESOURCE_KEYS = Set.of("kind", "id", "version_digest");
    /** EX-44 S3 的 binding：与客户端 {@code PortalEventsBinding} 记录逐字段同名（camelCase）。 */
    private static final Set<String> BINDING_KEYS = Set.of("responseId", "resource", "effectiveCursor",
            "observationRequestSha256", "hardDeadline");
    private static final Set<String> BINDING_RESOURCE_KEYS = Set.of("kind", "id", "versionDigest");
    private static final Pattern OPERATION = Pattern.compile("[a-z_]{1,64}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern ACTION = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final Pattern RESPONSE_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROFILE = Pattern.compile("[a-z0-9.-]{1,64}");
    private static final int MAX_TEXT = 256;
    private static final int MAX_TICKET = 16384;

    public static AuthorityRequest parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw AuthorityException.invalidRequest("请求体为空", "empty body");
        }
        if (body.length > MAX_BODY_BYTES) {
            throw AuthorityException.invalidRequest("请求体过大", "body over " + MAX_BODY_BYTES + " bytes");
        }
        JsonNode node;
        try {
            node = StrictJson.read(body);
        } catch (IOException e) {
            throw AuthorityException.invalidRequest("请求体不是合法 JSON", "malformed json: " + e.getClass().getSimpleName());
        }
        if (node == null || !node.isObject()) {
            throw AuthorityException.invalidRequest("请求体必须是 JSON 对象", "body is not an object");
        }
        String operation = requiredText(node, "operation", 64);
        if (!OPERATION.matcher(operation).matches()) {
            throw AuthorityException.invalidRequest("operation 非法", "operation=" + operation);
        }
        String target = requiredText(node, "target", 64);
        if (!AuthorityProperties.SERVICES.contains(target)) {
            throw AuthorityException.invalidRequest("target 不是平台服务", "target=" + target);
        }
        String action = requiredText(node, "action", 128);
        if (!ACTION.matcher(action).matches()) {
            throw AuthorityException.invalidRequest("action 非法", "action=" + action);
        }
        JsonNode resource = node.get("resource");
        if (resource == null || !resource.isObject()) {
            throw AuthorityException.invalidRequest("resource 必须是对象", "resource missing");
        }
        Set<String> keys = new LinkedHashSet<>();
        resource.fieldNames().forEachRemaining(keys::add);
        if (resource.size() != RESOURCE_KEYS.size() || !RESOURCE_KEYS.equals(keys)) {
            throw AuthorityException.invalidRequest("resource 必须且只能包含 kind/id/version_digest",
                    "resource keys=" + keys);
        }
        ResourceBinding binding;
        try {
            binding = new ResourceBinding(
                    requiredText(resource, "kind", MAX_TEXT),
                    requiredText(resource, "id", MAX_TEXT),
                    requiredText(resource, "version_digest", MAX_TEXT));
        } catch (IllegalArgumentException e) {
            throw AuthorityException.invalidRequest("resource 字段非法", "resource value blank: " + e.getMessage());
        }
        String nonce = requiredText(node, "nonce", 128);
        if (!NONCE.matcher(nonce).matches()) {
            throw AuthorityException.invalidRequest("nonce 非法", "nonce=" + nonce);
        }
        String serviceTicket = optionalText(node, "service_ticket", MAX_TICKET);
        String subjectToken = optionalText(node, "subject_token", MAX_TICKET);
        // 变体 B 的判别位：字段存在且非空才是 query_ticket 交换；空串/非法形状直接 400，绝不悄悄降级。
        String queryTicket = optionalText(node, "query_ticket", MAX_TICKET);
        String decisionId = optionalText(node, "decision_id", MAX_TEXT);
        String grantRef = optionalText(node, "grant_ref", MAX_TEXT);
        String operationId = optionalText(node, "operation_id", MAX_TEXT);
        boolean gatewayEventsOpening = optionalBoolean(node, "gateway_events_opening");
        return new AuthorityRequest(operation, new ServiceCall(target, action, binding), nonce, serviceTicket,
                subjectToken, queryTicket, decisionId, grantRef, operationId, gatewayEventsOpening, node);
    }

    /**
     * EX-44 S3 的 binding。字段集/类型在这里一次钉死：多一个少一个都 400，不把"看起来像"的节点带进判定。
     *
     * <p>响应 id 由客户端生成（重放保护键），因此只做字符集与长度约束；{@code hardDeadline} 是 ISO-8601
     * 字符串（与 {@code immutable.hard_deadline} 的 epoch 秒是两种编码，不能混用）；{@code resource}
     * 用记录组件名（camelCase {@code versionDigest}），与决策存储里的 snake_case 资源对象刻意不同。
     */
    public Binding binding() {
        JsonNode node = raw.get("binding");
        if (node == null || !node.isObject()) {
            throw AuthorityException.invalidRequest("binding 必须是对象", "binding missing");
        }
        if (!BINDING_KEYS.equals(fieldNames(node))) {
            throw AuthorityException.invalidRequest("binding 必须且只能包含五个字段", "binding keys=" + fieldNames(node));
        }
        String responseId = requiredText(node, "responseId", 128);
        if (!RESPONSE_ID.matcher(responseId).matches()) {
            throw AuthorityException.invalidRequest("binding.responseId 非法", "responseId=" + responseId);
        }
        JsonNode resource = node.get("resource");
        if (resource == null || !resource.isObject() || !BINDING_RESOURCE_KEYS.equals(fieldNames(resource))) {
            throw AuthorityException.invalidRequest("binding.resource 必须且只能包含 kind/id/versionDigest",
                    "binding.resource invalid");
        }
        ResourceBinding resourceBinding;
        try {
            resourceBinding = new ResourceBinding(
                    requiredText(resource, "kind", MAX_TEXT),
                    requiredText(resource, "id", MAX_TEXT),
                    requiredText(resource, "versionDigest", MAX_TEXT));
        } catch (IllegalArgumentException e) {
            throw AuthorityException.invalidRequest("binding.resource 字段非法", "binding.resource blank");
        }
        JsonNode cursorNode = node.get("effectiveCursor");
        if (cursorNode == null || !cursorNode.isIntegralNumber() || !cursorNode.canConvertToLong()) {
            throw AuthorityException.invalidRequest("binding.effectiveCursor 必须是整数", "effectiveCursor invalid");
        }
        long effectiveCursor = cursorNode.longValue();
        if (effectiveCursor < 0) {
            throw AuthorityException.invalidRequest("binding.effectiveCursor 不能为负", "effectiveCursor=" + effectiveCursor);
        }
        String sha = requiredText(node, "observationRequestSha256", 64);
        if (!SHA256.matcher(sha).matches()) {
            throw AuthorityException.invalidRequest("binding.observationRequestSha256 非法", "sha=" + sha);
        }
        String deadline = requiredText(node, "hardDeadline", 64);
        Instant hardDeadline;
        try {
            hardDeadline = Instant.parse(deadline);
        } catch (DateTimeParseException e) {
            throw AuthorityException.invalidRequest("binding.hardDeadline 必须是 ISO-8601 时刻", "hardDeadline=" + deadline);
        }
        return new Binding(responseId, resourceBinding, effectiveCursor, sha, hardDeadline);
    }

    /** S3 的 profile 是固定枚举值：先做形状校验，具体取值由 S3 判定（这里不耦合 gateway/engine 两套）。 */
    public String profile() {
        String value = requiredText(raw, "profile", 64);
        if (!PROFILE.matcher(value).matches()) {
            throw AuthorityException.invalidRequest("profile 非法", "profile=" + value);
        }
        return value;
    }

    /** S3 的 immutable：必须整体作为对象回显给客户端，字段语义由 S3 与 D2/D1 的权威值逐字段比对。 */
    public JsonNode immutable() {
        JsonNode node = raw.get("immutable");
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw AuthorityException.invalidRequest("immutable 必须是对象", "immutable missing");
        }
        return node;
    }

    /**
     * EX-45 S4 的入口凭据：S3 开窗后回显给客户端的 continuation
     * （{@code agent-portal-gateway-events-continuation+jwt}）。
     *
     * <p>它和 {@code query_ticket}/{@code service_ticket} 属于同一类"票面字符串"，长度上限也一致；
     * 形状之外的校验（typ/use/aud/exp、与 D4 的绑定）全部留给 S4 判定——请求解析层不解释票据语义。
     */
    public String parentContinuation() {
        return optionalText(raw, "parent_continuation", MAX_TICKET);
    }

    /**
     * EX-45 S4 的父 immutable：客户端把 S3 收到的 immutable 原样带回。
     *
     * <p>这里只保证"是一个非空对象"；真正的校验是 S4 用 D4/D3/D1 重建后做<b>整节点</b>比较。
     * 放到解析层会把"哪个字段不等"这类业务语义漏进请求层，也会让 S3/S4 的编码约束分叉。
     */
    public JsonNode parentImmutable() {
        JsonNode node = raw.get("parent_immutable");
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw AuthorityException.invalidRequest("parent_immutable 必须是对象", "parent_immutable missing");
        }
        return node;
    }

    /**
     * EX-46 S5 的入口凭据：S4 回显的 {@code child_open}
     * （{@code agent-portal-events-child-open+jwt}），engine 打开子观测时必须原样带回。
     *
     * <p>与 {@code parent_continuation} 同属"票面字符串"：解析层只管形状与长度，typ/use/aud/exp、
     * 与 association 的三个绑定值（摘要、父子 responseId）全部留给 S5 判定。
     */
    public String childOpen() {
        return optionalText(raw, "child_open", MAX_TICKET);
    }

    /**
     * EX-46 S6 的续期凭据：S3/S5 断言里的 {@code continuation_ref} 原样带回
     * （gateway 窗口用 gateway continuation，child 窗口用 child continuation）。
     *
     * <p>续期只刷新 checked 窗口，不重新发窗：请求层因此只读这一个字段，窗口上界由判决层按
     * 决策里的 immutable 与 55s 硬窗复核。
     */
    public String continuationRef() {
        return optionalText(raw, "continuation_ref", MAX_TICKET);
    }

    /** S3 binding 的类型化视图：构造前已在 {@link #binding()} 里完成形状/长度/编码校验。 */
    public record Binding(String responseId, ResourceBinding resource, long effectiveCursor,
                          String observationRequestSha256, Instant hardDeadline) {
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String requiredText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) {
            throw AuthorityException.invalidRequest(field + " 缺失或非法", field + " invalid");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) {
            throw AuthorityException.invalidRequest(field + " 非法", field + " invalid");
        }
        return value.textValue();
    }

    /**
     * EX-44 变体 A 的判别位：缺省即 false；出现时必须是真正的布尔值。
     *
     * <p>为什么拒绝字符串 {@code "true"}：客户端只会发布尔 {@code true}，字符串只可能是手写/拼错的请求。
     * 若静默按 false 处理，一个本意是 opening 的调用会被降级成普通 exchange——服务端不再做目的校验、
     * 也不再要求 origin，等于让拼写错误绕过本切片新增的两道前置检查。
     */
    private static boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw AuthorityException.invalidRequest(field + " 必须是布尔值", field + " not boolean");
        }
        return value.booleanValue();
    }
}
