package cn.agentplatform.app.application;

import static cn.agentplatform.app.application.AppService.*;

import cn.agentplatform.app.domain.IntegrationRules;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class IntegrationService {
    private final JdbcTemplate db;
    private final AppService apps;

    public IntegrationService(JdbcTemplate db, AppService apps) {
        this.db = db;
        this.apps = apps;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    public List<Map<String, Object>> list(String code) {
        apps.get(code);
        return db
                .queryForList(
                        "select biz_system_code from app.integration_credential where tenant_id=?"
                            + " and app_code=? order by biz_system_code",
                        tenant(),
                        code)
                .stream()
                .map(r -> get(code, r.get("biz_system_code").toString(), false))
                .toList();
    }

    public Map<String, Object> get(String code, String biz, boolean internal) {
        var rows =
                db.query(
                        "select * from app.integration_credential where tenant_id=? and app_code=?"
                            + " and biz_system_code=?",
                        (r, n) -> {
                            var result = new LinkedHashMap<String, Object>();
                            result.put("id", r.getLong("id"));
                            result.put("appCode", code);
                            result.put("bizSystemCode", biz);
                            result.put("status", r.getString("status"));
                            result.put(
                                    "claimLimits",
                                    Json.read(r.getString("claim_limits"), Object.class));
                            result.put(
                                    "allowedOrigins",
                                    Json.read(r.getString("allowed_origins"), Object.class));
                            result.put("roleMapping", Json.read(r.getString("role_mapping"), Object.class));
                            result.put(
                                    "issuedAt", r.getTimestamp("issued_at").toInstant().toString());
                            if (internal) result.put("secretRef", r.getString("secret_ref"));
                            return result;
                        },
                        tenant(),
                        code,
                        biz);
        if (rows.isEmpty()) throw error(404, "INTEGRATION_NOT_FOUND", "集成凭据不存在");
        return rows.getFirst();
    }

    /** 认证专用例外：首次opaque凭据尚无用户租户，只能由网关专用身份用全局id解析权威归属，绝不采信body租户。 */
    public Map<String, Object> authenticate(long id) {
        gatewayIdentity();
        var credential = credential(id);
        credential.remove("secretSha256");
        return credential;
    }

    /** Called after the gateway workload and its concrete credential binding have been verified. */
    public Map<String, Object> verifyApiKey(long id, String secret) {
        if (secret == null || secret.isEmpty() || secret.length() > 256) throw invalidApiKey();
        Map<String, Object> credential;
        try {
            credential = credential(id);
        } catch (PlatformException error) {
            if (error.status() == 404) throw invalidApiKey();
            throw error;
        }
        String stored = (String) credential.remove("secretSha256");
        if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                Digest.sha256(secret).getBytes(StandardCharsets.US_ASCII))
                || !"ACTIVE".equals(credential.get("status"))) throw invalidApiKey();
        credential.put("policyDigest", IntegrationClaims.digest(Json.tree(credential)));
        credential.remove("secretRef");
        return credential;
    }

    public Map<String, Object> sourceClaims(long id, String secret, JsonNode requested,
            String origin) {
        if (requested == null || !requested.isObject())
            throw error(400, "INVALID_CLAIMS", "集成身份声明须为JSON对象");
        var credential = Json.tree(verifyApiKey(id, secret));
        IntegrationClaimPolicy.origin(credential, origin);
        String app = credential.path("appCode").asText();
        if (requested.has("appCode") && !app.equals(requested.path("appCode").asText()))
            throw error(403, "APP_BINDING_DENIED", "凭据不属于此应用");
        var actor = IntegrationClaimPolicy.clip(credential, requested);
        return IntegrationSource.claims(id, app, credential.path("bizSystemCode").asText(),
                actor.attributes().get("userId").iterator().next(), credential.path("policyDigest").asText(), actor);
    }

    private static PlatformException invalidApiKey() {
        return error(401, "INVALID_CREDENTIAL", "集成凭据无效");
    }

    private Map<String, Object> credential(long id) {
        var rows =
                db.query(
                        "select"
                            + " id,tenant_id,app_code,biz_system_code,status,secret_ref,secret_sha256,claim_limits,allowed_origins,role_mapping"
                            + " from app.integration_credential where id=?",
                        (r, n) ->
                                new LinkedHashMap<>(Map.<String, Object>of(
                                        "id",
                                        r.getLong("id"),
                                        "tenantId",
                                        r.getString("tenant_id"),
                                        "appCode",
                                        r.getString("app_code"),
                                        "bizSystemCode",
                                        r.getString("biz_system_code"),
                                        "status",
                                        r.getString("status"),
                                        "secretRef",
                                        r.getString("secret_ref"),
                                        "secretSha256",
                                        r.getString("secret_sha256"),
                                        "claimLimits",
                                        Json.read(r.getString("claim_limits"), Object.class),
                                        "allowedOrigins",
                                        Json.read(r.getString("allowed_origins"), Object.class),
                                        "roleMapping",
                                        Json.read(r.getString("role_mapping"), Object.class))),
                        id);
        if (rows.isEmpty()) throw error(404, "INTEGRATION_NOT_FOUND", "集成凭据不存在");
        return rows.getFirst();
    }

    private void gatewayIdentity() {
        Actor actor = Actor.current();
        if (!actor.principalId().equals("_gateway")
                || !actor.roles().contains("SERVICE")
                || !actor.roles().contains("GATEWAY_AUTH"))
            throw error(403, "GATEWAY_AUTH_REQUIRED", "仅网关专用认证身份可解析凭据归属");
    }

    /** CORS首次预检没有用户租户；专用网关身份只能查询全局ACTIVE来源是否存在，不能读取租户记录。 */
    public Map<String, Object> origins(String origin) {
        gatewayIdentity();
        return checkOrigin(origin);
    }

    public Map<String, Object> checkOrigin(String origin) {
        try {
            IntegrationRules.origins(List.of(origin));
        } catch (Exception ex) {
            return Map.of("allowed", false);
        }
        Boolean allowed =
                db.queryForObject(
                        "select exists(select 1 from app.integration_credential where"
                            + " status='ACTIVE' and allowed_origins @> ?::jsonb)",
                        Boolean.class,
                        Json.write(List.of(origin)));
        return Map.of("allowed", Boolean.TRUE.equals(allowed));
    }

    @Transactional
    public Map<String, Object> issue(String code, Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        requirePublishedVersion(code);
        String biz = text(body, "bizSystemCode");
        if (!biz.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw error(400, "INVALID_INPUT", "业务系统编码无效");
        var limits = object(body.get("claimLimits"));
        Object origins = body.get("allowedOrigins");
        var roleMapping = roleMapping(body.getOrDefault("roleMapping", Map.of()), limits);
        try {
            IntegrationRules.origins(origins);
            IntegrationRules.limits(limits);
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_INTEGRATION_POLICY", ex.getMessage());
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String ref = SecretVault.put(secret);
        // 业务表仅存加密密钥库引用与不可逆摘要；明文只在本次签发响应出现一次。
        // 密钥库与PG不能跨资源原子提交；数据库失败最多留下不可引用的密文孤儿，不能产生可用凭据。
        db.update(
                "insert into"
                    + " app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,allowed_origins,role_mapping,issued_by)"
                    + " values(?,?,?,?,?,'ACTIVE',?::jsonb,?::jsonb,?::jsonb,?)",
                tenant(),
                code,
                biz,
                ref,
                Digest.sha256(secret),
                Json.write(limits),
                Json.write(origins),
                Json.write(roleMapping),
                Actor.current().principalId());
        var result = new LinkedHashMap<>(get(code, biz, false));
        // PLAT-12 审计三元组：凭据生命周期审计带上 credential_id，可从凭据反查
        audit(Actor.current(), code, "INTEGRATION_ISSUE", biz, result.get("id").toString());
        result.put("secretOnce", secret);
        result.put("apiKeyOnce", result.get("id") + ":" + secret);
        return result;
    }

    @Transactional
    public Map<String, Object> revoke(String code, String biz) {
        Actor.current().requireRole("ADMIN");
        long credentialId = requireCredential(code, biz);
        db.update(
                "update app.integration_credential set status='REVOKED',revoked_at=now(),policy_revision=policy_revision+1 where"
                    + " tenant_id=? and app_code=? and biz_system_code=?",
                tenant(),
                code,
                biz);
        audit(Actor.current(), code, "INTEGRATION_REVOKE", biz, Long.toString(credentialId));
        return get(code, biz, false);
    }

    @Transactional
    public Map<String, Object> policy(String code, String biz, Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        long credentialId = requireCredential(code, biz);
        var limits = object(body.get("claimLimits"));
        Object origins = body.get("allowedOrigins");
        String mapping = body.containsKey("roleMapping") ? Json.write(roleMapping(body.get("roleMapping"), limits)) : null;
        try {
            IntegrationRules.origins(origins);
            IntegrationRules.limits(limits);
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_INTEGRATION_POLICY", ex.getMessage());
        }
        db.update(
                "update app.integration_credential set"
                    + " claim_limits=?::jsonb,allowed_origins=?::jsonb,role_mapping=coalesce(?::jsonb,role_mapping),"
                    + " policy_revision=policy_revision+1 where tenant_id=? and"
                    + " app_code=? and biz_system_code=?",
                Json.write(limits),
                Json.write(origins),
                mapping,
                tenant(),
                code,
                biz);
        audit(Actor.current(), code, "INTEGRATION_POLICY", biz, Long.toString(credentialId));
        return get(code, biz, false);
    }

    private static Map<String, String> roleMapping(Object supplied, Map<String, Object> limits) {
        if (!(supplied instanceof Map<?, ?> mapping) || mapping.size() > 128) {
            throw error(400, "INVALID_INTEGRATION_POLICY", "角色映射须为最多 128 个已登记角色的对象");
        }
        var result = new TreeMap<String, String>();
        var allowed = Json.tree(limits.getOrDefault("roles", List.of()));
        var admitted = new HashSet<String>();
        allowed.forEach(role -> { if (role.isTextual()) admitted.add(role.textValue()); });
        for (var entry : mapping.entrySet()) {
            if (!(entry.getKey() instanceof String role) || !admitted.contains(role)
                    || !(entry.getValue() instanceof String value) || !IntegrationClaims.RUNTIME_ROLES.contains(value)
                    || "AGENT_BUILDER".equalsIgnoreCase(role) && !"AGENT_VIEWER".equals(value)) {
                throw error(400, "INVALID_INTEGRATION_POLICY", "角色映射超出接入档案上限");
            }
            result.put(role, value);
        }
        // JSONB's textual form inserts a space after each colon and comma.
        if (Json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + 2 * result.size() > 16384) {
            throw error(400, "INVALID_INTEGRATION_POLICY", "角色映射超过 16 KiB");
        }
        return result;
    }

    /** Lock the real published pointer before authorization and retain the lock until issue commits.
     * The stored immutable published SHA is authoritative; no draft/payload is read to derive it. */
    private void requirePublishedVersion(String code) {
        var rows=db.queryForList("select a.current_version_no,v.candidate_sha256 from app.application a left join app.app_version v on v.tenant_id=a.tenant_id and v.app_code=a.code and v.version_no=a.current_version_no where a.tenant_id=? and a.code=? for update of a",tenant(),code);
        if(rows.isEmpty())throw error(404,"APP_NOT_FOUND","应用不存在");
        var row=rows.getFirst();if(row.get("current_version_no")==null||row.get("candidate_sha256")==null)throw error(409,"APP_UNPUBLISHED","请先发布应用");
        AuthorityContext.requireFor(Actor.current(),new ResourceBinding("app-version",code+"@"+row.get("current_version_no"),row.get("candidate_sha256").toString())).actor().requireRole("ADMIN");
    }
    /** Policy metadata is read under the row lock. The same transaction verifies the old exact
     * resource, mutates policy and increments revision; concurrent updates cannot lose increments.
     * 返回凭据 id 供审计三元组（runId↔principalId↔credentialId）落关联列。 */
    private long requireCredential(String code,String biz) {
        var rows=db.queryForList("select id,policy_revision,secret_ref,claim_limits,allowed_origins,role_mapping from app.integration_credential where tenant_id=? and app_code=? and biz_system_code=? for update",tenant(),code,biz);
        if(rows.isEmpty())throw error(404,"INTEGRATION_NOT_FOUND","集成凭据不存在");
        var row=rows.getFirst();long id=((Number)row.get("id")).longValue();
        String digest=IntegrationClaims.digest(Json.tree(Map.of("secretRef",row.get("secret_ref"),"claimLimits",Json.read(row.get("claim_limits").toString()),"allowedOrigins",Json.read(row.get("allowed_origins").toString()),"roleMapping",Json.read(row.get("role_mapping").toString()))));
        String version=Digest.sha256(Json.canonical(Map.of("credentialId",id,"policyVersion",id+":"+row.get("policy_revision"),"policyDigest",digest)));
        AuthorityContext.requireFor(Actor.current(),new ResourceBinding("integration-credential",Long.toString(id),version)).actor().requireRole("ADMIN");
        return id;
    }

    /** PLAT-12 审计写入：credential_id 可空，给出即落关联列（V107），支持从凭据反查；包内可见以便直验写入形状。 */
    void audit(Actor actor, String code, String action, String biz, String credentialId) {
        db.update(
                "insert into"
                    + " app.lifecycle_log(tenant_id,app_code,actor,action,from_state,to_state,detail,credential_id)"
                    + " values(?,?,?,?,'PUBLISHED','PUBLISHED',?::jsonb,?)",
                actor.tenantId(),
                code,
                actor.principalId(),
                action,
                Json.write(Map.of("bizSystemCode", biz)),
                credentialId);
    }
}
