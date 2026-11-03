package cn.agentplatform.app.domain;

import cn.agentplatform.common.PlatformException;
import java.util.*;

/**
 * 能力包声明里的通用 JSON 取值规则：只做类型收窄与输入校验，不读存储、不依赖应用服务。
 *
 * <p>本类由 MNT-08 从 {@code AppService} 的同名静态工具抽出：领域规则（{@link CapabilityBinding}）此前反向
 * import 应用层，导致纯规则无法脱离应用服务单独测试。抽取只搬位置，错误码、状态码、提示文案和缺省值逐字保持；
 * {@code AppService} 保留同名公开方法委托到这里，既有调用方与 Spring 消费者不受影响。
 */
public final class CapabilityValues {
    private CapabilityValues() {}

    /** 必填短文本字段：缺省按空串参与校验，空白或超长一律拒绝。 */
    public static String text(Map<String, Object> body, String field) {
        String v = Objects.toString(body.get(field), "");
        if (v.isBlank() || v.length() > 128) throw invalid("缺少或无效字段: " + field);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object v) {
        if (!(v instanceof Map<?, ?>)) throw invalid("必须提供JSON对象");
        return (Map<String, Object>) v;
    }

    /** 可选对象数组：缺失等价空数组；null 元素也视为非对象成员。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Object v) {
        if (v == null) return List.of();
        if (!(v instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof Map)))
            throw invalid("必须提供对象数组");
        return (List<Map<String, Object>>) v;
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "INVALID_INPUT", message);
    }
}
