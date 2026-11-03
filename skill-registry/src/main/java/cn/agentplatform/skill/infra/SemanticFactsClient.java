package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * OR 只读语义事实入口的对象引用解析客户端（SEM-03.1）。
 *
 * <p>只经 {@link SkillClients} 的既有签票/信封语义访问 OR，不直连 OR 数据库，也不把
 * 调用方给的外部 ID 当已解析对象。响应必须逐字段核对：releaseId/spaceCode 必须与本次
 * 请求一致，缺字段或标识不是 UUID 一律按依赖不可用拒绝，避免把错版本地址带回执行链。
 *
 * <p>本子步只解析引用，不读取业务事实；方法命名与返回类型不承诺事实值。
 */
@Component
public class SemanticFactsClient {
    /** 与平台其他 OR 侧调用一致：地址从配置注入，不在此硬编码业务空间。 */
    private static final String PATH = "/onto/releases/%s/semantic/facts/resolve";

    private final SkillClients clients;
    private final String baseUrl;

    public SemanticFactsClient(
            SkillClients clients,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String baseUrl) {
        this.clients = Objects.requireNonNull(clients);
        this.baseUrl = Objects.requireNonNull(baseUrl).replaceAll("/+$", "");
    }

    /** 解析一个 space-object 引用；外部引用由 OR 侧拒绝，不在此处补解析器。 */
    public ResolvedObject resolve(UUID releaseId, Map<String, Object> reference) {
        if (releaseId == null || reference == null)
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "对象引用不能为空");
        JsonNode data = clients.post(baseUrl + PATH.formatted(releaseId), reference);
        if (!data.isObject()) throw malformed();
        String refKind = text(data, "refKind");
        String spaceCode = text(data, "spaceCode");
        String objectType = text(data, "objectType");
        String stableId = text(data, "stableId");
        if (!releaseId.toString().equals(text(data, "releaseId")))
            throw malformed();
        if (reference.get("spaceCode") != null
                && !reference.get("spaceCode").toString().equals(spaceCode)) throw malformed();
        if (reference.get("objectType") != null
                && !reference.get("objectType").toString().equals(objectType)) throw malformed();
        if (reference.get("stableId") != null
                && !reference.get("stableId").toString().equals(stableId)) throw malformed();
        return new ResolvedObject(
                refKind,
                spaceCode,
                UUID.fromString(text(data, "releaseId")),
                text(data, "version"),
                objectType,
                stableId,
                uuid(data, "classId"),
                uuid(data, "mappingId"),
                text(data, "keyField"),
                text(data, "sourceKey"));
    }

    /** 服务端地址的可核对投影；不含源表/数据源等实现细节。 */
    public record ResolvedObject(
            String refKind,
            String spaceCode,
            UUID releaseId,
            String version,
            String objectType,
            String stableId,
            UUID classId,
            UUID mappingId,
            String keyField,
            String sourceKey) {

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("refKind", refKind);
            map.put("spaceCode", spaceCode);
            map.put("releaseId", releaseId.toString());
            map.put("version", version);
            map.put("objectType", objectType);
            map.put("stableId", stableId);
            map.put("classId", classId.toString());
            map.put("mappingId", mappingId.toString());
            map.put("keyField", keyField);
            map.put("sourceKey", sourceKey);
            return map;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw malformed();
        return value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException ex) {
            throw malformed();
        }
    }

    private static PlatformException malformed() {
        return new PlatformException(502, "DEPENDENCY_UNAVAILABLE", "本体服务返回的对象引用解析结果不完整");
    }
}
