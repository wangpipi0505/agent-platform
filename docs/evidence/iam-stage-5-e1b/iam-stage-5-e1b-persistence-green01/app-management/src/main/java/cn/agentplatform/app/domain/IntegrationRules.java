package cn.agentplatform.app.domain;

import java.net.URI;
import java.util.*;

public final class IntegrationRules {
    private IntegrationRules() {}

    public static void origins(Object raw) {
        if (!(raw instanceof List<?> origins) || origins.isEmpty() || origins.size() > 50)
            throw new IllegalArgumentException("必须配置1至50个明确来源");
        for (Object value : origins) {
            if (!(value instanceof String origin)) throw new IllegalArgumentException("来源必须为字符串");
            URI uri = URI.create(origin);
            String host = uri.getHost();
            boolean loopback =
                    Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(host, ""));
            if (host == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    || !("https".equals(uri.getScheme())
                            || loopback && "http".equals(uri.getScheme())))
                throw new IllegalArgumentException("来源需为无路径HTTPS origin，本机开发可HTTP");
        }
    }

    public static void limits(Map<String, Object> limits) {
        if (!Set.of("principalIds", "roles", "attributes").containsAll(limits.keySet()))
            throw new IllegalArgumentException("声明上限包含未知字段");
        strings(limits.get("principalIds"), true);
        strings(limits.get("roles"), false);
        if (!(limits.get("attributes") instanceof Map<?, ?> attributes))
            throw new IllegalArgumentException("attributes必须为对象");
        for (var entry : attributes.entrySet()) {
            if (!entry.getKey().toString().matches("[A-Za-z_][A-Za-z0-9_-]{0,63}"))
                throw new IllegalArgumentException("属性名无效");
            strings(entry.getValue(), true);
        }
    }

    private static void strings(Object raw, boolean required) {
        if (!(raw instanceof List<?> values)
                || values.size() > 1000
                || required && values.isEmpty()
                || values.stream()
                        .anyMatch(
                                v ->
                                        !(v instanceof String s)
                                                || s.isBlank()
                                                || s.length() > 128
                                                || s.equals("*")))
            throw new IllegalArgumentException("声明上限必须为明确字符串数组，禁止通配符");
    }
}
