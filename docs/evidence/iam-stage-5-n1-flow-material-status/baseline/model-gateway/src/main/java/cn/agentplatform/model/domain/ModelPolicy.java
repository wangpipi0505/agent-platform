package cn.agentplatform.model.domain;

import java.net.URI;
import java.util.Set;

/** 详设第二部分：本地端点不豁免出域标签；缺分类按本地保守策略处理。 */
public final class ModelPolicy {
    private ModelPolicy() {}

    public static void checkLabels(Set<String> required, Set<String> trusted) {
        if (required.isEmpty()) {
            if (!trusted.contains("LOCAL"))
                throw new IllegalArgumentException("数据未分类时仅允许具备LOCAL信任标签的端点");
        } else if (!trusted.containsAll(required))
            throw new IllegalArgumentException("端点不满足数据所需信任标签");
    }

    public static void checkBudget(int window, int tokens, int output, int safety) {
        if (tokens < 0 || output <= 0 || tokens + (long) output + safety > window)
            throw new IllegalArgumentException("上下文超限，请缩减输入或调整模型窗口");
    }

    public static URI endpoint(String value) {
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("模型地址必须是不含凭据的HTTP(S)地址");
        return uri;
    }
}
