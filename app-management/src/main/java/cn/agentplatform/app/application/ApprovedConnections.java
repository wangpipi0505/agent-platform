package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConnectionGrantRepository;
import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 运行期只读解析（resolve）：给定"档案 + 修订 + 环境"，返回可以直接用于调用的一次性事实快照。
 *
 * 这是 INT-02 里唯一允许运行期读配置的入口，因此它的判定顺序就是权限边界本身：
 * 1) 档案/修订必须存在（404 CONFIG_REF_NOT_FOUND，不区分"不存在"和"不属于本租户"）；
 * 2) 必须已批准（DISABLED → CONFIG_RELEASE_NOT_ACTIVE；DRAFT → CONFIG_STATE_TRANSITION_INVALID）；
 * 3) 请求的修订必须就是当前已批准修订——旧修订不再解析，避免"回滚到某一版"被当成可用配置；
 * 4) 鉴权形态必须是运行时已开放的那三种（§10.1），留白值显式拒绝；
 * 5) 环境绑定只取"钉住该修订且自身已批准"的那些，未批准绑定不得进入运行期；
 * 6) 授权按使用方投影成 grantedUses，但解析本身不判断"这次调用是否被授权"——那是调用方（skill/engine）
 *    用自己的身份去比对 grant 的事，这里只提供事实，不做跨仓授权决策。
 *
 * 回执带 resolutionDigest：调用方可以把它写进调用记录，事后证明"当时用的是哪一份声明"。
 * 摘要不含 resolvedAt（时间不参与身份），所以同一份事实每次解析得到同一个摘要。
 */
@Service
public class ApprovedConnections {

    static final Pattern ENVIRONMENT = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final ConnectionProfileRepository profiles;
    private final ConnectionGrantRepository grants;

    public ApprovedConnections(ConnectionProfileRepository profiles, ConnectionGrantRepository grants) {
        this.profiles = profiles;
        this.grants = grants;
    }

    public Map<String, Object> resolve(String profileId, int profileRev, String environment) {
        return resolve(profileId, profileRev, environment, null, null, null);
    }

    public Map<String, Object> resolve(String profileId, int profileRev, String environment,
            String bindingId, Integer bindingRev, String grantee) {
        Actor actor = Actor.current();
        if ((bindingId == null) != (bindingRev == null) || bindingRev != null && bindingRev < 1)
            throw new PlatformException(400, "INVALID_INPUT", "绑定引用必须同时给出 bindingId/bindingRev");
        if (environment != null && !environment.isBlank() && !ENVIRONMENT.matcher(environment).matches())
            throw new PlatformException(400, "INVALID_INPUT", "environment 格式不合法: " + environment);
        String env = environment == null || environment.isBlank() ? null : environment;

        Map<String, Object> head = profiles.profile(actor, profileId)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "连接档案不存在: " + profileId));
        String status = String.valueOf(head.get("status"));
        if ("DISABLED".equals(status))
            throw new PlatformException(400, "CONFIG_RELEASE_NOT_ACTIVE", "连接档案已停用: " + profileId);
        if (!"APPROVED".equals(status))
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "只有已批准的连接档案才能被运行时解析，当前状态: " + status);
        int current = (Integer) head.get("currentRev");
        if (profileRev != current)
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "只能解析当前已批准修订：当前 " + current + "，请求 " + profileRev);

        Map<String, Object> revision = revisionOf(head, profileId, profileRev);
        String authKind = ConnectionProfiles.runtimeAuthKind(asMap(revision.get("authScheme")));

        List<Map<String, Object>> bindings = profiles.pinnedBindingRevisions(actor, profileId, profileRev).stream()
                .filter(b -> env == null || env.equals(b.get("environment")))
                .filter(b -> bindingId == null || bindingId.equals(b.get("bindingId"))
                        && bindingRev.equals(b.get("bindingRev")))
                .toList();
        if ((env != null || bindingId != null) && bindings.isEmpty())
            throw new PlatformException(404, "CONFIG_REF_NOT_FOUND",
                    "该环境下没有已批准的环境绑定: " + profileId + "@" + profileRev + " / " + env);

        List<Map<String, Object>> activeGrants = grants.activeGrants(actor, profileId, profileRev).stream()
                .filter(g -> grantee == null || grantee.equals(g.get("grantee"))).toList();
        // 被授予用途是各有效授权 allowedUses 的并集；保持首次出现顺序，便于摘要稳定。
        List<String> grantedUses = activeGrants.stream()
                .flatMap(g -> asList(g.get("allowedUses")).stream())
                .map(String::valueOf)
                .distinct()
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", profileId);
        out.put("profileRev", profileRev);
        out.put("status", status);
        out.put("protocol", revision.get("protocol"));
        out.put("operationAllowlist", revision.get("operationAllowlist"));
        out.put("endpointPolicy", revision.get("endpointPolicy"));
        out.put("authScheme", revision.get("authScheme"));
        out.put("authKind", authKind);
        out.put("identityPolicy", revision.get("identityPolicy"));
        out.put("inputContractRefs", revision.get("inputContractRefs"));
        out.put("outputContractRefs", revision.get("outputContractRefs"));
        out.put("allowedUses", revision.get("allowedUses"));
        out.put("grantedUses", grantedUses);
        out.put("bindings", bindings);
        out.put("grants", activeGrants);
        out.put("resolutionDigest", digest(profileId, profileRev, revision, authKind, bindings, activeGrants));
        out.put("resolvedAt", Instant.now().toString());
        return out;
    }

    /**
     * 摘要只覆盖"声明 + 钉住的绑定 + 有效授权"，不含时间与读取顺序：同一份事实必须稳定得到同一摘要，
     * 否则调用方无法用它证明"当时解析到的是哪一版"。
     */
    private static String digest(String profileId, int profileRev, Map<String, Object> revision, String authKind,
            List<Map<String, Object>> bindings, List<Map<String, Object>> activeGrants) {
        List<Object> material = new ArrayList<>();
        material.add("connection-profile.resolve");
        material.add(profileId);
        material.add(profileRev);
        material.add(revision.get("protocol"));
        material.add(authKind);
        material.add(revision.get("operationAllowlist"));
        material.add(revision.get("allowedUses"));
        material.add(revision.get("declarationSha256"));
        material.add(bindings.stream()
                .map(b -> List.of(b.get("bindingId"), b.get("bindingRev"), b.get("environment"),
                        b.get("baseEndpoint"), b.get("secretRef"), b.get("declarationSha256")))
                .toList());
        material.add(activeGrants.stream()
                .map(g -> List.of(g.get("grantId"), g.get("grantee"), g.get("allowedUses")))
                .sorted((a, b) -> Long.compare(((Number) a.get(0)).longValue(), ((Number) b.get(0)).longValue()))
                .toList());
        return Digest.sha256(Json.canonical(material));
    }

    private static Map<String, Object> revisionOf(Map<String, Object> head, String profileId, int profileRev) {
        if (head.get("revisions") instanceof List<?> revisions) {
            for (Object raw : revisions)
                if (raw instanceof Map<?, ?> m && Integer.valueOf(profileRev).equals(m.get("profileRev")))
                    return asMap(m);
        }
        throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
                "连接档案修订不存在: " + profileId + "@" + profileRev);
    }

    private static Map<String, Object> asMap(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static List<?> asList(Object raw) {
        return raw instanceof List<?> l ? l : List.of();
    }
}
