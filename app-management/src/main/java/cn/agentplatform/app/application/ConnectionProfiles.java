package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 连接档案（ConnectionProfile）的 ADMIN 目录服务：创建、生成新修订、批准、停用。
 *
 * 字段与状态机来自 contracts/configuration-profile.md §2/§3；本类只做"请求归一 + 字段校验 + 摘要计算"，
 * 事务与幂等由 {@link ConnectionProfileRepository} 独占。修订是不可变的，所以本类没有任何"改上一版"的入口：
 * 任何内容变化都必须产生 profile_rev+1。
 *
 * 有意限制：这里不实现 resolve（运行期解析）、不校验远端可达性、不保存任何凭据明文；secret_ref 只是引用。
 */
@Service
public class ConnectionProfiles {

    static final Set<String> PROTOCOLS = Set.of("HTTP_SYNC", "HTTP_ASYNC", "MCP", "ALGORITHM", "PROCESS");
    static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{2,47}$");
    static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{7,63}$");
    static final Pattern OPERATION = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.\\-/:]{0,127}$");
    static final Pattern REF = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.:/\\-]{0,191}$");
    static final Pattern USE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.\\-]{0,63}$");
    static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    /** 声明中出现这些字段名即视为试图写入明文凭据：配置只允许引用（secretRef），不允许值。 */
    static final Pattern SECRET_KEY = Pattern.compile("(?i).*(secret|password|passwd|token|apikey|api_key|credential|private_key).*");
    static final Set<String> BODY_KEYS = Set.of("profileId", "expectedRev", "requestId", "protocol",
            "operationAllowlist", "endpointPolicy", "authScheme", "identityPolicy", "inputContractRefs",
            "outputContractRefs", "allowedUses");
    static final Set<String> DECLARATION_KEYS = Set.of("protocol", "operationAllowlist", "endpointPolicy",
            "authScheme", "identityPolicy", "inputContractRefs", "outputContractRefs", "allowedUses");
    private static final Set<String> ENDPOINT_KEYS = Set.of("hosts", "pathPrefixes", "httpMethods");
    private static final Set<String> AUTH_KEYS = Set.of("kind", "headerName");
    private static final Set<String> IDENTITY_KEYS = Set.of("mode", "subjectSource");
    private static final Set<String> AUTH_KINDS = Set.of("NONE", "API_KEY", "OAUTH2_CLIENT_CREDENTIALS", "MTLS",
            "SIGNED_REQUEST");
    private static final Set<String> IDENTITY_MODES = Set.of("PLATFORM_PRINCIPAL", "SERVICE_ACCOUNT");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final ConnectionProfileRepository repository;

    public ConnectionProfiles(ConnectionProfileRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        Actor actor = Actor.current();
        keys(body, BODY_KEYS, "连接档案");
        String profileId = id(body, "profileId");
        String requestId = requestId(body);
        if (body.containsKey("expectedRev"))
            throw invalid("创建连接档案不接受 expectedRev");
        var revision = declaration(body);
        String sha = Digest.sha256(Json.canonical(List.of("connection-profile.create", profileId, 0,
                revision.sha256())));
        return repository.createProfile(actor, profileId, requestId, sha, revision);
    }

    public Map<String, Object> revise(String profileId, Map<String, Object> body) {
        Actor actor = Actor.current();
        keys(body, BODY_KEYS, "连接档案修订");
        String id = id(Map.of("profileId", profileId), "profileId");
        int expectedRev = expectedRev(body);
        String requestId = requestId(body);
        var revision = declaration(body);
        String sha = Digest.sha256(Json.canonical(List.of("connection-profile.revise", id, expectedRev,
                revision.sha256())));
        return repository.reviseProfile(actor, id, expectedRev, requestId, sha, revision);
    }

    public Map<String, Object> approve(String profileId, Map<String, Object> body) {
        return transition(profileId, body, "APPROVED");
    }

    public Map<String, Object> disable(String profileId, Map<String, Object> body) {
        return transition(profileId, body, "DISABLED");
    }

    public Map<String, Object> get(String profileId) {
        Actor actor = Actor.current();
        return repository.profile(actor, id(Map.of("profileId", profileId), "profileId"))
                .orElseThrow(() -> new PlatformException(404, "CONFIG_VERSION_UNKNOWN", "连接档案不存在: " + profileId));
    }

    public List<Map<String, Object>> list() {
        return repository.profiles(Actor.current());
    }

    private Map<String, Object> transition(String profileId, Map<String, Object> body, String status) {
        Actor actor = Actor.current();
        keys(body, Set.of("expectedRev", "requestId"), "连接档案生命周期");
        String id = id(Map.of("profileId", profileId), "profileId");
        int expectedRev = expectedRev(body);
        String requestId = requestId(body);
        String operation = "APPROVED".equals(status) ? "connection-profile.approve" : "connection-profile.disable";
        String sha = Digest.sha256(Json.canonical(List.of(operation, id, expectedRev)));
        return repository.transitionProfile(actor, id, expectedRev, requestId, sha, status);
    }

    // ------------------------------------------------------------ 声明归一与校验（环境绑定复用这些规则）

    static ConnectionProfileRepository.ProfileRevision declaration(Map<String, Object> body) {
        // 先做凭据扫描：出现 secret/password/token 之类字段名就是配置事故，报错要具体到契约码，
        // 不能被后续的"未知字段"覆盖掉。
        rejectPlaintextSecrets(body, Set.of());
        String protocol = oneOf(text(body, "protocol", "协议", 24), PROTOCOLS, "protocol");
        List<String> operations = strings(body.get("operationAllowlist"), "operationAllowlist", 1, 64, OPERATION);
        Map<String, Object> endpoint = object(body.get("endpointPolicy"), "endpointPolicy", ENDPOINT_KEYS);
        Map<String, Object> auth = object(body.get("authScheme"), "authScheme", AUTH_KEYS);
        Map<String, Object> identity = object(body.get("identityPolicy"), "identityPolicy", IDENTITY_KEYS);
        List<String> inputs = strings(body.get("inputContractRefs"), "inputContractRefs", 0, 32, REF);
        List<String> outputs = strings(body.get("outputContractRefs"), "outputContractRefs", 0, 32, REF);
        List<String> uses = strings(body.get("allowedUses"), "allowedUses", 1, 16, USE);

        if (endpoint != null) {
            if (endpoint.get("hosts") != null)
                for (String host : strings(endpoint.get("hosts"), "endpointPolicy.hosts", 1, 32, null))
                    origin(host, "endpointPolicy.hosts");
            if (endpoint.get("pathPrefixes") != null)
                for (String prefix : strings(endpoint.get("pathPrefixes"), "endpointPolicy.pathPrefixes", 1, 32, null))
                    if (!prefix.startsWith("/") || prefix.length() > 256 || prefix.contains(".."))
                        throw invalid("endpointPolicy.pathPrefixes 必须以 / 开头且不得包含 ..: " + prefix);
            if (endpoint.get("httpMethods") != null)
                for (String method : strings(endpoint.get("httpMethods"), "endpointPolicy.httpMethods", 1, 8,
                        Pattern.compile("^[A-Z]{3,7}$")))
                    if (!HTTP_METHODS.contains(method))
                        throw invalid("endpointPolicy.httpMethods 含未允许的方法: " + method);
        }
        if (auth != null) {
            oneOf(text(auth, "kind", "authScheme.kind", 32), AUTH_KINDS, "authScheme.kind");
            if (auth.get("headerName") != null)
                text(auth, "headerName", "authScheme.headerName", 64, HEADER);
        }
        if (identity != null) {
            oneOf(text(identity, "mode", "identityPolicy.mode", 32), IDENTITY_MODES, "identityPolicy.mode");
            if (identity.get("subjectSource") != null)
                text(identity, "subjectSource", "identityPolicy.subjectSource", 128);
        }
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("protocol", protocol);
        declared.put("operationAllowlist", operations);
        declared.put("endpointPolicy", endpoint == null ? Map.of() : endpoint);
        declared.put("authScheme", auth == null ? Map.of() : auth);
        declared.put("identityPolicy", identity == null ? Map.of() : identity);
        declared.put("inputContractRefs", inputs);
        declared.put("outputContractRefs", outputs);
        declared.put("allowedUses", uses);
        return new ConnectionProfileRepository.ProfileRevision(protocol, operations,
                endpoint == null ? Map.of() : endpoint, auth == null ? Map.of() : auth,
                identity == null ? Map.of() : identity, inputs, outputs, uses,
                Digest.sha256(Json.canonical(declared)));
    }

    // ------------------------------------------------------------ 通用校验工具（同包内复用）

    static void keys(Map<String, Object> body, Set<String> allowed, String label) {
        if (body == null) throw invalid(label + "请求体不能为空");
        if (body.size() > 32) throw budget(label + "字段过多");
        for (String key : body.keySet())
            if (!allowed.contains(key))
                throw invalid(label + "包含未知字段: " + key);
        if (Json.write(body).length() > 65536)
            throw new PlatformException(400, "CONFIG_SCHEMA_BUDGET_EXCEEDED", label + "声明超过 64KB 预算");
    }

    static String text(Map<String, Object> body, String field, String label, int max) {
        return text(body, field, label, max, null);
    }

    static String text(Map<String, Object> body, String field, String label, int max, Pattern pattern) {
        Object raw = body == null ? null : body.get(field);
        if (!(raw instanceof String s) || s.isBlank() || s.length() > max)
            throw invalid("缺少或无效字段: " + (label == null ? field : label));
        if (pattern != null && !pattern.matcher(s).matches())
            throw invalid((label == null ? field : label) + " 格式不合法: " + s);
        return s;
    }

    static String id(Map<String, Object> body, String field) {
        return text(body, field, field, 64, ID);
    }

    static String requestId(Map<String, Object> body) {
        return text(body, "requestId", "requestId", 64, REQUEST_ID);
    }

    static int expectedRev(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("expectedRev");
        if (!(raw instanceof Number n) || n.intValue() < 1 || n.intValue() != n.doubleValue())
            throw invalid("缺少或无效字段: expectedRev（必须为不小于 1 的整数）");
        return n.intValue();
    }

    static String oneOf(String value, Set<String> allowed, String label) {
        if (!allowed.contains(value))
            throw invalid(label + " 只允许 " + new TreeSet<>(allowed) + ": " + value);
        return value;
    }

    static List<String> strings(Object raw, String field, int min, int max, Pattern pattern) {
        if (raw == null) {
            if (min == 0) return List.of();
            throw invalid("缺少或无效字段: " + field);
        }
        if (!(raw instanceof List<?> list) || list.size() < min || list.size() > max)
            throw invalid(field + " 必须是长度 " + min + "~" + max + " 的数组");
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank() || s.length() > 256)
                throw invalid(field + " 只能包含非空字符串");
            if (pattern != null && !pattern.matcher(s).matches())
                throw invalid(field + " 含格式不合法的条目: " + s);
            out.add(s);
        }
        if (new HashSet<>(out).size() != out.size()) throw invalid(field + " 不允许重复条目");
        return List.copyOf(out);
    }

    static Map<String, Object> object(Object raw, String field, Set<String> allowed) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) throw invalid(field + " 必须是 JSON 对象");
        Map<String, Object> out = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(field + " 的键必须是字符串");
            if (!allowed.contains(key)) throw invalid(field + " 包含未知键: " + key);
            out.put(key, entry.getValue());
        }
        return out;
    }

    /** 接受 https，或本机回环的 http（本地开发）；拒绝查询串、片段与空主机。 */
    static String endpoint(Object raw, String field) {
        if (!(raw instanceof String s) || s.isBlank() || s.length() > 2048)
            throw invalid(field + " 必须是合法的绝对地址");
        URI uri;
        try {
            uri = URI.create(s);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " 不是合法 URI: " + s);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean loopbackHttp = "http".equals(scheme) && loopback(uri.getHost());
        if (!https && !loopbackHttp)
            throw invalid(field + " 只接受 https（本机回环可用 http）: " + s);
        if (uri.getHost() == null || uri.getHost().isBlank())
            throw invalid(field + " 缺少主机: " + s);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw invalid(field + " 不接受查询串或片段: " + s);
        return s;
    }

    /** 来源只接受"方案 + 主机 + 可选端口"，带路径的写法一律拒绝（来源策略比较的是 origin）。 */
    static String origin(String raw, String field) {
        String value = endpoint(raw, field);
        URI uri = URI.create(value);
        if (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
            throw invalid(field + " 只接受来源（不得带路径）: " + raw);
        return value;
    }

    static boolean loopback(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host));
    }

    /** 递归拒绝明文凭据字段：配置只能引用 secretRef，不能出现 secret/password/token 之类的值。 */
    static void rejectPlaintextSecrets(Object value, Set<String> allowedKeys) {
        ArrayDeque<Object> stack = new ArrayDeque<>();
        stack.push(value);
        int visited = 0;
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (++visited > 1024)
                throw new PlatformException(400, "CONFIG_SCHEMA_BUDGET_EXCEEDED", "声明节点过多（超过 1024）");
            if (current instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (!allowedKeys.contains(key) && SECRET_KEY.matcher(key).matches())
                        throw new PlatformException(400, "CONFIG_SECRET_PLAINTEXT_REJECTED",
                                "配置中不允许出现明文凭据字段: " + key);
                    stack.push(entry.getValue());
                }
            } else if (current instanceof List<?> list) {
                list.forEach(stack::push);
            }
        }
    }

    static PlatformException invalid(String message) {
        return new PlatformException(400, "INVALID_INPUT", message);
    }

    static PlatformException budget(String message) {
        return new PlatformException(400, "CONFIG_SCHEMA_BUDGET_EXCEEDED", message);
    }
}
