package cn.agentplatform.skill.infra;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Json;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ONB-07：OR 正式本体来源的只读消费入口。
 *
 * <p>只在接入模板显式声明需要本体语义时调用（声明缺省不入网，旧模板零变化）。OR 返回的
 * 四元组、manifest 摘要、正式操作与 ACTIVE 语义扩展必须自洽：releaseId/spaceCode 与请求
 * 一致、摘要为 64 位小写十六进制、版本形如 vN；任一不符按依赖不可用拒绝，缺本体或非 READY
 * 由 OR 的 SEMANTIC_REF_INVALID 原样阻断，不把候选预览、停用扩展或旧截图补成正式来源。
 */
@Component
public class OntologyManifestClient {
    /** 与平台其他 OR 侧调用一致：地址从配置注入，不在此硬编码业务空间。 */
    private static final String PATH = "/onto/releases/%s/formal-source";

    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern VERSION = Pattern.compile("^v[1-9][0-9]*$");
    private static final Pattern OP_CODE = Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");
    private static final Pattern PROFILE =
            Pattern.compile("^[a-z][a-z0-9-]{0,31}@[0-9]+\\.[0-9]+$");
    private static final Pattern SCHEMA_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+$");
    private static final Set<String> ENDPOINT_STATUS = Set.of("ACTIVE", "DISABLED");

    private final java.util.function.Function<String, JsonNode> request;
    private final String baseUrl;

    public OntologyManifestClient(SkillClients clients, String baseUrl) {
        this(clients::get, baseUrl);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OntologyManifestClient(OntologySemanticTransport transport,
            @Value("${agent.services.ontology:http://127.0.0.1:18182}") String baseUrl) {
        this(transport::get, baseUrl);
    }

    private OntologyManifestClient(java.util.function.Function<String, JsonNode> request, String baseUrl) {
        this.request = Objects.requireNonNull(request);
        this.baseUrl = Objects.requireNonNull(baseUrl).replaceAll("/+$", "");
    }

    /** 模板没有本体声明时返回空且不触网；有声明才回读正式来源。 */
    public Optional<FormalSource> resolveIfDeclared(Map<String, Object> declaration) {
        if (!declared(declaration)) return Optional.empty();
        Object releaseId = declaration.get("releaseId");
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(releaseId));
        } catch (IllegalArgumentException invalid) {
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "模板声明的本体版本不是合法UUID");
        }
        Object spaceCode = declaration.get("spaceCode");
        return Optional.of(resolve(id, spaceCode == null ? null : String.valueOf(spaceCode)));
    }

    /** 声明需同时具备 releaseId；仅 spaceCode 不足以定位正式版本。 */
    public static boolean declared(Map<String, Object> declaration) {
        if (declaration == null) return false;
        Object releaseId = declaration.get("releaseId");
        return releaseId instanceof String value && !value.isBlank();
    }

    public FormalSource resolve(UUID releaseId, String spaceCode) {
        if (releaseId == null)
            throw new PlatformException(400, "SEMANTIC_REF_INVALID", "本体版本不能为空");
        String url = baseUrl + PATH.formatted(releaseId);
        if (spaceCode != null && !spaceCode.isBlank()) {
            if (!spaceCode.matches("^[A-Za-z0-9._-]{1,64}$"))
                throw new PlatformException(400, "SEMANTIC_REF_INVALID", "空间标识格式无效");
            url += "?spaceCode=" + spaceCode;
        }
        JsonNode data;
        try {
            data = request.apply(url);
        } catch (PlatformException failure) {
            if (failure.status() == 502 && "DEPENDENCY_UNAVAILABLE".equals(failure.code()))
                throw new PlatformException(
                        503, "SEMANTIC_SOURCE_UNAVAILABLE", "本体正式来源暂不可达，请稍后重试");
            throw failure;
        }
        if (data == null || !data.isObject()) throw malformed();
        if (!"1.0".equals(text(data, "schemaVersion"))) throw malformed();
        if (!data.path("readOnly").asBoolean(false)) throw malformed();

        JsonNode ref = data.path("releaseRef");
        if (!ref.isObject()) throw malformed();
        String refSpace = text(ref, "spaceCode");
        if (!refSpace.matches("^[A-Za-z0-9._-]{1,64}$")) throw malformed();
        if (spaceCode != null && !spaceCode.isBlank() && !spaceCode.equals(refSpace))
            throw malformed();
        if (!releaseId.toString().equals(text(ref, "releaseId"))) throw malformed();
        String version = text(ref, "version");
        if (!VERSION.matcher(version).matches()) throw malformed();
        String contentDigest = text(ref, "contentDigest");
        if (!DIGEST.matcher(contentDigest).matches()) throw malformed();
        if (!contentDigest.equals(text(data, "bundleSha256"))) throw malformed();

        String manifestSha256 = text(data, "manifestSha256");
        if (!DIGEST.matcher(manifestSha256).matches()) throw malformed();

        List<Operation> operations = operations(data.path("operations"));
        List<Extension> extensions = extensions(data.path("semanticExtensions"));
        Map<String, Object> counts = counts(data.path("counts"));
        return new FormalSource(
                refSpace, releaseId, version, contentDigest, manifestSha256, operations, extensions, counts);
    }

    private List<Operation> operations(JsonNode node) {
        if (!node.isArray() || node.size() > 64) throw malformed();
        var result = new ArrayList<Operation>(node.size());
        for (JsonNode item : node) {
            if (!item.isObject()) throw malformed();
            String opCode = text(item, "opCode");
            if (!OP_CODE.matcher(opCode).matches()) throw malformed();
            String route = text(item, "route");
            if (route.isBlank() || route.length() > 512) throw malformed();
            String status = item.path("status").asText("");
            if (!ENDPOINT_STATUS.contains(status)) throw malformed();
            result.add(new Operation(opCode, route, status));
        }
        return List.copyOf(result);
    }

    private List<Extension> extensions(JsonNode node) {
        if (!node.isArray() || node.size() > 64) throw malformed();
        var result = new ArrayList<Extension>(node.size());
        for (JsonNode item : node) {
            if (!item.isObject()) throw malformed();
            UUID extensionId;
            try {
                extensionId = UUID.fromString(text(item, "extensionId"));
            } catch (IllegalArgumentException invalid) {
                throw malformed();
            }
            String opCode = text(item, "opCode");
            if (!OP_CODE.matcher(opCode).matches()) throw malformed();
            String profile = text(item, "profile");
            if (!PROFILE.matcher(profile).matches()) throw malformed();
            String schemaVersion = text(item, "schemaVersion");
            if (!SCHEMA_VERSION.matcher(schemaVersion).matches()) throw malformed();
            String digest = text(item, "extensionSha256");
            if (!DIGEST.matcher(digest).matches()) throw malformed();
            if (!"ACTIVE".equals(item.path("status").asText(""))) throw malformed();
            Map<String, Object> descriptor = Map.of();
            if (item.hasNonNull("descriptor")) {
                if (!item.path("descriptor").isObject()) throw malformed();
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = Json.read(item.path("descriptor").toString(), Map.class);
                descriptor = Map.copyOf(parsed);
            }
            result.add(new Extension(extensionId, opCode, profile, schemaVersion, digest, descriptor));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> counts(JsonNode node) {
        if (!node.isObject()) throw malformed();
        var result = new LinkedHashMap<String, Object>();
        for (String kind : List.of("class", "property", "relation", "rule", "operation")) {
            JsonNode value = node.path(kind);
            if (!value.isIntegralNumber() || value.asLong() < 0 || value.asLong() > 1_000_000)
                throw malformed();
            result.put(kind, value.asLong());
        }
        return Map.copyOf(result);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw malformed();
        return value.asText();
    }

    private static PlatformException malformed() {
        return new PlatformException(
                502, "DEPENDENCY_UNAVAILABLE", "本体正式来源响应不符合约定");
    }

    public record Operation(String opCode, String route, String status) {
        public Map<String, Object> toMap() {
            return Map.of("opCode", opCode, "route", route, "status", status);
        }
    }

    public record Extension(
            UUID extensionId, String opCode, String profile, String schemaVersion, String sha256,
            Map<String, Object> descriptor) {
        public Extension(UUID extensionId, String opCode, String profile, String schemaVersion, String sha256) {
            this(extensionId, opCode, profile, schemaVersion, sha256, Map.of());
        }
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("extensionId", extensionId.toString());
            map.put("opCode", opCode);
            map.put("profile", profile);
            map.put("schemaVersion", schemaVersion);
            map.put("extensionSha256", sha256);
            if (!descriptor.isEmpty()) map.put("descriptor", descriptor);
            return map;
        }
    }

    /** AP 只保存引用与摘要；不缓存正文，不把候选/停用扩展当正式来源。 */
    public record FormalSource(
            String spaceCode,
            UUID releaseId,
            String version,
            String contentDigest,
            String manifestSha256,
            List<Operation> operations,
            List<Extension> extensions,
            Map<String, Object> counts) {

        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            var ref = new LinkedHashMap<String, Object>();
            ref.put("spaceCode", spaceCode);
            ref.put("releaseId", releaseId.toString());
            ref.put("version", version);
            ref.put("contentDigest", contentDigest);
            map.put("releaseRef", ref);
            map.put("manifestSha256", manifestSha256);
            map.put("operations", operations.stream().map(Operation::toMap).toList());
            map.put("semanticExtensions", extensions.stream().map(Extension::toMap).toList());
            map.put("counts", counts);
            return map;
        }
    }
}
