package cn.agentplatform.skill.infra;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.SkillRules;
import java.net.URI;
import java.util.*;

/** 只由服务端批准解析产生的执行描述；不改写历史技能合同及其语义摘要。 */
public record ApprovedConnectionExecution(String bindingId, int bindingRev, String baseEndpoint,
        String secretRef, String authKind, String protocol, Map<String, Object> endpointPolicy) {
    public static final String KEY = "_approvedConnectionExecution";
    public ApprovedConnectionExecution { endpointPolicy = Map.copyOf(endpointPolicy); }

    public Map<String, Object> apply(Map<String, Object> contract) {
        if (!Set.of("HTTP", "MCP").contains(Objects.toString(contract.get("type"), "")))
            throw denied("此适配器尚不支持受控环境连接");
        String expected = "MCP".equals(contract.get("type")) ? "MCP"
                : Set.of("SUBMIT_QUERY", "CALLBACK").contains(Objects.toString(contract.get("invocationKind"), ""))
                        ? "HTTP_ASYNC" : "HTTP_SYNC";
        if (!expected.equals(protocol) || "MCP".equals(expected) && "API_KEY".equals(authKind))
            throw denied("批准协议或鉴权形态与实际适配器不相容");
        String original = Objects.toString(contract.get("endpoint"), "");
        SkillRules.endpoint(baseEndpoint);
        if (!"NONE".equals(authKind) && secretRef.isBlank()) throw denied("批准绑定缺少凭据引用");
        // 校验在认领/提交前完成；缺失凭据不能进入旧异常兜底而被误记为已发出后的UNKNOWN。
        // 不保存此处解析出的值，适配器发出前仍按同一引用读取当前凭据。
        AuthScheme.headers(authKind, "NONE".equals(authKind) ? "" : secretRef);
        String origin = origin(original);
        var bound = rewrite(contract, origin, false);
        bound.put("authRef", "NONE".equals(authKind) ? "" : secretRef);
        bound.put("authScheme", authKind);
        bound.put(KEY, this);
        return Collections.unmodifiableMap(bound);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rewrite(Map<String, Object> value, String sourceOrigin, boolean artifacts) {
        var out = new LinkedHashMap<String, Object>();
        value.forEach((key, item) -> {
            if (artifacts && key.equals("authRef")) out.put(key, "NONE".equals(authKind) ? "" : secretRef);
            else if (artifacts && key.equals("authScheme")) out.put(key, authKind);
            else if ((key.equals("endpoint") || key.equals("lookupEndpoint") || key.equals("resultEndpoint")
                    || artifacts && key.equals("url"))
                    && item instanceof String endpoint && !endpoint.isBlank()) {
                if (!origin(endpoint).equals(sourceOrigin)) throw denied("附属入口必须与技能入口同源");
                String suffix = endpoint.substring(sourceOrigin.length());
                out.put(key, baseEndpoint.replaceAll("/+$", "") + (suffix.isEmpty() ? "/" : suffix));
            } else if ((artifacts || Set.of("execution", "approval", "prepare", "reconciliation", "returnSpec", "artifactSources").contains(key))
                    && item instanceof Map<?, ?> nested) {
                var child = rewrite((Map<String, Object>) nested, sourceOrigin, artifacts || key.equals("artifactSources"));
                if (artifacts && key.equals("retrieve")) {
                    child.put("authRef", "NONE".equals(authKind) ? "" : secretRef);
                    child.put("authScheme", authKind);
                }
                out.put(key, Collections.unmodifiableMap(child));
            }
            else out.put(key, item);
        });
        return out;
    }

    /** 实际出站点复核映射后的地址；返回值/状态URL不能把调用带回旧端点或另一个来源。 */
    public static void check(Map<String, Object> contract, String endpoint, String method) {
        if (!(contract.get(KEY) instanceof ApprovedConnectionExecution context)) return;
        URI uri = SkillRules.endpoint(endpoint);
        URI base = SkillRules.endpoint(context.baseEndpoint);
        if (uri.getPath().contains("\\") || Arrays.stream(uri.getPath().split("/"))
                .anyMatch(segment -> segment.equals(".") || segment.equals("..")))
            throw denied("批准根路径不允许跳转段");
        if (!origin(endpoint).equals(origin(context.baseEndpoint))
                || !within(uri.getPath(), Objects.toString(base.getPath(), "")))
            throw denied("实际出站地址超出批准绑定");
        Object hosts = context.endpointPolicy.get("hosts");
        if (hosts instanceof List<?> list && !list.contains(origin(endpoint))) throw denied("来源不在批准白名单");
        Object paths = context.endpointPolicy.get("pathPrefixes");
        if (paths instanceof List<?> list && list.stream().noneMatch(p -> within(uri.getPath(), p.toString())))
            throw denied("路径不在批准白名单");
        Object methods = context.endpointPolicy.get("httpMethods");
        if (methods instanceof List<?> list && !list.contains(method)) throw denied("方法不在批准白名单");
    }

    private static boolean within(String path, String prefix) {
        String root = prefix.replaceAll("/+$", "");
        return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
    }
    private static String origin(String endpoint) {
        var match = java.util.regex.Pattern.compile("^https?://[^/?#]+").matcher(endpoint);
        if (!match.find()) throw denied("受控入口必须是合法HTTP地址");
        return match.group();
    }
    private static PlatformException denied(String message) {
        return new PlatformException(409, "CONNECTION_EXECUTION_BINDING_INVALID", message);
    }
}
