package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 环境绑定（EnvironmentBinding）的 ADMIN 目录服务：把"某个连接档案的某一修订"钉到"某个环境的实际地址与凭据引用"上。
 *
 * 字段来自 contracts/configuration-profile.md §2；与连接档案一样，修订不可变，变化必须产生 binding_rev+1。
 * 绑定修订通过外键钉住 (connectionProfileId, connectionProfileRev)：引用不存在的连接修订在写事务里就会被拒绝，
 * 不会出现"绑定指向一个从未发布过的档案版本"。
 *
 * 有意限制：baseEndpoint 只做形状校验，不做连通性探活；secretRef 只校验"像引用"，不解析、不读取凭据；
 * 与连接档案的关系校验只在写时进行，运行期解析（resolve）属后续子步。
 */
@Service
public class EnvironmentBindings {

    static final Pattern ID = ConnectionProfiles.ID;
    static final Pattern ENVIRONMENT = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    static final Pattern SECRET_REF = Pattern.compile("^[a-z][a-z0-9+.-]*:[A-Za-z0-9._/:-]{1,400}$");
    static final Set<String> BODY_KEYS = Set.of("bindingId", "expectedRev", "requestId", "environment",
            "connectionProfileId", "connectionProfileRev", "baseEndpoint", "secretRef", "runtimeRef",
            "originPolicy", "healthPolicy");
    private static final Set<String> RUNTIME_KEYS = Set.of("runner", "namespace", "engine");
    private static final Set<String> ORIGIN_KEYS = Set.of("origins");
    private static final Set<String> HEALTH_KEYS = Set.of("path", "intervalSeconds", "timeoutMs");

    private final ConnectionProfileRepository repository;

    public EnvironmentBindings(ConnectionProfileRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        Actor actor = Actor.current();
        ConnectionProfiles.keys(body, BODY_KEYS, "环境绑定");
        String bindingId = ConnectionProfiles.id(body, "bindingId");
        String requestId = ConnectionProfiles.requestId(body);
        if (body.containsKey("expectedRev"))
            throw ConnectionProfiles.invalid("创建环境绑定不接受 expectedRev");
        var revision = declaration(body);
        requireProfile(actor, revision);
        String sha = Digest.sha256(Json.canonical(List.of("environment-binding.create", bindingId, 0,
                revision.sha256())));
        return repository.createBinding(actor, bindingId, requestId, sha, revision);
    }

    public Map<String, Object> revise(String bindingId, Map<String, Object> body) {
        Actor actor = Actor.current();
        ConnectionProfiles.keys(body, BODY_KEYS, "环境绑定修订");
        String id = ConnectionProfiles.id(Map.of("bindingId", bindingId), "bindingId");
        int expectedRev = ConnectionProfiles.expectedRev(body);
        String requestId = ConnectionProfiles.requestId(body);
        var revision = declaration(body);
        requireProfile(actor, revision);
        String sha = Digest.sha256(Json.canonical(List.of("environment-binding.revise", id, expectedRev,
                revision.sha256())));
        return repository.reviseBinding(actor, id, expectedRev, requestId, sha, revision);
    }

    public Map<String, Object> approve(String bindingId, Map<String, Object> body) {
        return transition(bindingId, body, "APPROVED");
    }

    public Map<String, Object> disable(String bindingId, Map<String, Object> body) {
        return transition(bindingId, body, "DISABLED");
    }

    public Map<String, Object> get(String bindingId) {
        Actor actor = Actor.current();
        return repository.binding(actor, ConnectionProfiles.id(Map.of("bindingId", bindingId), "bindingId"))
                .orElseThrow(() -> new PlatformException(404, "CONFIG_VERSION_UNKNOWN", "环境绑定不存在: " + bindingId));
    }

    public List<Map<String, Object>> list() {
        return repository.bindings(Actor.current());
    }

    private Map<String, Object> transition(String bindingId, Map<String, Object> body, String status) {
        Actor actor = Actor.current();
        ConnectionProfiles.keys(body, Set.of("expectedRev", "requestId"), "环境绑定生命周期");
        String id = ConnectionProfiles.id(Map.of("bindingId", bindingId), "bindingId");
        int expectedRev = ConnectionProfiles.expectedRev(body);
        String requestId = ConnectionProfiles.requestId(body);
        String operation = "APPROVED".equals(status) ? "environment-binding.approve" : "environment-binding.disable";
        String sha = Digest.sha256(Json.canonical(List.of(operation, id, expectedRev)));
        return repository.transitionBinding(actor, id, expectedRev, requestId, sha, status);
    }

    /** 写时引用校验：档案必须存在、修订必须存在、档案不得处于停用态（停用档案不能被新绑定引用）。 */
    private void requireProfile(Actor actor, ConnectionProfileRepository.BindingRevision revision) {
        var profile = repository.profile(actor, revision.connectionProfileId())
                .orElseThrow(() -> new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
                        "连接档案不存在: " + revision.connectionProfileId()));
        Map<?, ?> pinned = null;
        if (profile.get("revisions") instanceof List<?> revisions)
            for (Object raw : revisions)
                if (raw instanceof Map<?, ?> item
                        && Integer.valueOf(revision.connectionProfileRev()).equals(item.get("profileRev"))) {
                    pinned = item;
                    break;
                }
        if (pinned == null)
            throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
                    "连接档案修订不存在: " + revision.connectionProfileId() + "@" + revision.connectionProfileRev());
        if ("DISABLED".equals(profile.get("status")))
            throw new PlatformException(400, "CONFIG_RELEASE_NOT_ACTIVE",
                    "连接档案已停用，不能被环境绑定引用: " + revision.connectionProfileId());
        // 契约 §10.3 裁决：绑定必须引用 APPROVED 及以上；引用 DRAFT 视为状态迁移非法，不在运行期"先绑后批"。
        if (!"APPROVED".equals(profile.get("status")))
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "环境绑定只能引用已批准的连接档案修订: " + revision.connectionProfileId() + "@"
                            + revision.connectionProfileRev() + " 当前状态 " + profile.get("status"));
        // 与 resolve 相同，未声明鉴权等同 NONE；判定只来自本租户钉住的不可变档案修订。
        Object kind = pinned.get("authScheme") instanceof Map<?, ?> auth ? auth.get("kind") : null;
        boolean noAuth = kind == null || "NONE".equals(kind);
        if (noAuth && !revision.secretRef().isEmpty())
            throw ConnectionProfiles.invalid("NONE 鉴权的环境绑定必须将 secretRef 设为空字符串");
        if (!noAuth && revision.secretRef().isEmpty())
            throw ConnectionProfiles.invalid("该连接档案要求 secretRef 为非空 scheme:ref 引用");
    }

    /** 包内可见：规则用例直接验证字段契约，不必启动 Spring 或连接数据库。 */
    static ConnectionProfileRepository.BindingRevision declaration(Map<String, Object> body) {
        String environment = ConnectionProfiles.text(body, "environment", "environment", 64, ENVIRONMENT);
        String profileId = ConnectionProfiles.id(body, "connectionProfileId");
        int profileRev = number(body, "connectionProfileRev", 1, Integer.MAX_VALUE, "connectionProfileRev");
        String baseEndpoint = ConnectionProfiles.endpoint(body.get("baseEndpoint"), "baseEndpoint");
        Object rawSecret = body.get("secretRef");
        if (!(rawSecret instanceof String secretRef) || secretRef.length() > 512
                || !secretRef.isEmpty() && !SECRET_REF.matcher(secretRef).matches())
            throw ConnectionProfiles.invalid("secretRef 必须是 scheme:ref 引用，NONE 鉴权须显式传空字符串");
        Map<String, Object> runtime = ConnectionProfiles.object(body.get("runtimeRef"), "runtimeRef", RUNTIME_KEYS);
        Map<String, Object> origin = ConnectionProfiles.object(body.get("originPolicy"), "originPolicy", ORIGIN_KEYS);
        Map<String, Object> health = ConnectionProfiles.object(body.get("healthPolicy"), "healthPolicy", HEALTH_KEYS);
        if (runtime != null)
            for (var entry : runtime.entrySet())
                if (!(entry.getValue() instanceof String s) || s.isBlank() || s.length() > 256)
                    throw ConnectionProfiles.invalid("runtimeRef." + entry.getKey() + " 必须是非空字符串（不超过 256）");
        if (origin != null)
            for (String originUrl : ConnectionProfiles.strings(origin.get("origins"), "originPolicy.origins", 1, 16,
                    null))
                ConnectionProfiles.origin(originUrl, "originPolicy.origins");
        if (health != null) {
            if (health.get("path") != null) {
                String path = ConnectionProfiles.text(health, "path", "healthPolicy.path", 256);
                if (!path.startsWith("/") || path.contains(".."))
                    throw ConnectionProfiles.invalid("healthPolicy.path 必须以 / 开头且不得包含 ..");
            }
            if (health.get("intervalSeconds") != null)
                number(health, "intervalSeconds", 5, 3600, "healthPolicy.intervalSeconds");
            if (health.get("timeoutMs") != null)
                number(health, "timeoutMs", 100, 60000, "healthPolicy.timeoutMs");
        }
        ConnectionProfiles.rejectPlaintextSecrets(body, Set.of("secretRef"));

        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("environment", environment);
        declared.put("connectionProfileId", profileId);
        declared.put("connectionProfileRev", profileRev);
        declared.put("baseEndpoint", baseEndpoint);
        declared.put("secretRef", secretRef);
        declared.put("runtimeRef", runtime == null ? Map.of() : runtime);
        declared.put("originPolicy", origin == null ? Map.of() : origin);
        declared.put("healthPolicy", health == null ? Map.of() : health);
        return new ConnectionProfileRepository.BindingRevision(environment, profileId, profileRev, baseEndpoint,
                secretRef, runtime == null ? Map.of() : runtime, origin == null ? Map.of() : origin,
                health == null ? Map.of() : health, Digest.sha256(Json.canonical(declared)));
    }

    private static int number(Map<String, Object> body, String field, int min, int max, String label) {
        Object raw = body == null ? null : body.get(field);
        if (!(raw instanceof Number n) || n.intValue() != n.doubleValue() || n.intValue() < min
                || n.intValue() > max)
            throw ConnectionProfiles.invalid("缺少或无效字段: " + label + "（必须为 " + min + "~" + max + " 的整数）");
        return n.intValue();
    }
}
