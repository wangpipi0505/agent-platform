package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConnectionGrantRepository;
import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 连接档案的授权目录：把"某修订可以被某使用方以哪些用途使用"落成独立事实。
 *
 * 契约 §10.3 裁决的三条边界在这里落地：
 * 1) 批准 ≠ 授权——只有已批准修订（且该修订就是当前修订）才谈得上授权；
 * 2) 授权不悬空——引用必须指向已存在的修订，撤销只置 revoked_at；
 * 3) 用途是子集——授予的 allowedUses 必须是档案修订 allowedUses 的子集，运行期绝不越权放大。
 *
 * 幂等：同一使用方重复授予**同一载荷**视为回放（返回已存在的行，replayed=true）；载荷不同则 409，
 * 要求先撤销再授予，避免"静默扩大授权"。这里不需要 requestId：天然键就是 (档案, 修订, 使用方)。
 */
@Service
public class ConnectionGrants {

    static final Pattern GRANTEE = Pattern.compile("^(?:[a-z][a-z0-9._-]{2,63}|skill\\.[A-Za-z][A-Za-z0-9_-]{0,63})$");
    private static final int MAX_USES = 16;
    private static final int MAX_REASON = 256;

    private final ConnectionProfileRepository profiles;
    private final ConnectionGrantRepository grants;

    public ConnectionGrants(ConnectionProfileRepository profiles, ConnectionGrantRepository grants) {
        this.profiles = profiles;
        this.grants = grants;
    }

    public Map<String, Object> list(String profileId, int profileRev) {
        Actor actor = Actor.current();
        revision(actor, profileId, profileRev);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", profileId);
        out.put("profileRev", profileRev);
        out.put("grants", grants.activeGrants(actor, profileId, profileRev));
        return out;
    }

    public Map<String, Object> grant(String profileId, int profileRev, Map<String, Object> body) {
        Actor actor = Actor.current();
        Map<String, Object> revision = revision(actor, profileId, profileRev);
        if (body == null) throw new PlatformException(400, "INVALID_INPUT", "授权请求体不能为空");
        for (String key : body.keySet())
            if (!"grantee".equals(key) && !"allowedUses".equals(key))
                throw new PlatformException(400, "INVALID_INPUT", "授权请求包含未知字段: " + key);

        String grantee = ConnectionProfiles.text(body, "grantee", "授权使用方", 70, GRANTEE);
        List<String> uses = ConnectionProfiles.strings(body.get("allowedUses"), "allowedUses", 1, MAX_USES,
                ConnectionProfiles.USE);
        List<?> declared = revision.get("allowedUses") instanceof List<?> l ? l : List.of();
        for (String use : uses)
            if (!declared.contains(use))
                throw new PlatformException(400, "INVALID_INPUT",
                        "授权用途超出档案已批准用途: " + use + "（已批准: " + declared + "）");

        var existing = grants.activeGrant(actor, profileId, profileRev, grantee);
        if (existing.isPresent()) {
            Object previous = existing.get().get("allowedUses");
            if (previous instanceof List<?> l && l.size() == uses.size() && l.containsAll(uses)) {
                Map<String, Object> replayed = new LinkedHashMap<>(existing.get());
                replayed.put("replayed", true);
                return replayed;
            }
            throw new PlatformException(409, "CONFIG_REV_CONFLICT",
                    "该使用方已有不同用途的授权，请先撤销再授予: " + grantee);
        }
        Map<String, Object> created = new LinkedHashMap<>(grants.grant(actor, profileId, profileRev, grantee, uses));
        created.put("replayed", false);
        return created;
    }

    public Map<String, Object> revoke(String profileId, int profileRev, long grantId, Map<String, Object> body) {
        Actor actor = Actor.current();
        revision(actor, profileId, profileRev);
        String reason = "";
        if (body != null && !body.isEmpty()) {
            for (String key : body.keySet())
                if (!"reason".equals(key))
                    throw new PlatformException(400, "INVALID_INPUT", "撤销请求包含未知字段: " + key);
            Object raw = body.get("reason");
            if (raw != null) reason = ConnectionProfiles.text(body, "reason", "撤销原因", MAX_REASON);
        }
        return grants.revoke(actor, profileId, profileRev, grantId, reason);
    }

    /**
     * 授权只能发生在"已批准且是当前修订"的档案上。
     *
     * 返回该修订的完整声明（admin 读面形状），供用途子集校验与后续 resolve 复用。
     */
    private Map<String, Object> revision(Actor actor, String profileId, int profileRev) {
        Map<String, Object> head = profiles.profile(actor, profileId)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "连接档案不存在: " + profileId));
        String status = String.valueOf(head.get("status"));
        if ("DISABLED".equals(status))
            throw new PlatformException(400, "CONFIG_RELEASE_NOT_ACTIVE", "连接档案已停用: " + profileId);
        if (!"APPROVED".equals(status))
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "只有已批准的连接档案才能授权，当前状态: " + status);
        int current = (Integer) head.get("currentRev");
        if (profileRev != current)
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "只能对当前已批准修订授权：当前 " + current + "，请求 " + profileRev);
        if (head.get("revisions") instanceof List<?> revisions) {
            for (Object raw : revisions)
                if (raw instanceof Map<?, ?> m && Integer.valueOf(profileRev).equals(m.get("profileRev")))
                    return cast(m);
        }
        throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
                "连接档案修订不存在: " + profileId + "@" + profileRev);
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
