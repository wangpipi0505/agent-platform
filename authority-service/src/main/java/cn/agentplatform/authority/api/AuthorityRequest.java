package cn.agentplatform.authority.api;

import cn.agentplatform.authority.AuthorityException;
import cn.agentplatform.authority.StrictJson;
import cn.agentplatform.common.authority.AuthorityProperties;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 决策/票据请求的形状白名单。
 * <p>字段集合刻意很窄：{@code operation} 决定端点语义，{@code target/action/resource} 必须与目录行
 * 逐字段相等（会原样进票据并成为平台侧的路由键），{@code nonce} 绑定断言与请求。
 * <p>{@code service_ticket} 出现在 {@code authorize_workload}（workload 票）与 {@code authorize}
 * （上游已委派的票）；{@code subject_token} 只在 {@code authorize} 出现（gateway 持有的 portal 用户令牌）；
 * {@code decision_id/grant_ref/operation_id} 只在 {@code forward} 出现（转发链的定位三件套）。
 * <p>{@code gateway_events_opening} 只在 {@code operation=exchange} 上合法（EX-44 变体 A，SSE 观测 opening）。
 * 它是判别位而不是能力开关：只有布尔 {@code true} 才按 opening 语义校验，拼成字符串等非法形状一律 400——
 * 静默忽略会把"opening 的前置校验"降级成一次普通换票。
 * <p>不接受额外字段是防"协议悄悄扩张"：新增字段必须先改这里的白名单和对应的平台消费者。
 */
public record AuthorityRequest(
        String operation,
        ServiceCall call,
        String nonce,
        String serviceTicket,
        String subjectToken,
        String decisionId,
        String grantRef,
        String operationId,
        boolean gatewayEventsOpening,
        JsonNode raw) {

    public static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> RESOURCE_KEYS = Set.of("kind", "id", "version_digest");
    private static final Pattern OPERATION = Pattern.compile("[a-z_]{1,64}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern ACTION = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
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
        String decisionId = optionalText(node, "decision_id", MAX_TEXT);
        String grantRef = optionalText(node, "grant_ref", MAX_TEXT);
        String operationId = optionalText(node, "operation_id", MAX_TEXT);
        boolean gatewayEventsOpening = optionalBoolean(node, "gateway_events_opening");
        return new AuthorityRequest(operation, new ServiceCall(target, action, binding), nonce, serviceTicket,
                subjectToken, decisionId, grantRef, operationId, gatewayEventsOpening, node);
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
