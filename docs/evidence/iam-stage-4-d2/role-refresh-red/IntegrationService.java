package cn.agentplatform.app.application;

import static cn.agentplatform.app.application.AppService.*;

import cn.agentplatform.app.domain.IntegrationRules;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
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
        var rows =
                db.query(
                        "select"
                            + " id,tenant_id,app_code,biz_system_code,status,secret_ref,claim_limits,allowed_origins"
                            + " from app.integration_credential where id=?",
                        (r, n) ->
                                Map.<String, Object>of(
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
                                        "claimLimits",
                                        Json.read(r.getString("claim_limits"), Object.class),
                                        "allowedOrigins",
                                        Json.read(r.getString("allowed_origins"), Object.class)),
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
                    + " app.integration_credential(tenant_id,app_code,biz_system_code,secret_ref,secret_sha256,status,claim_limits,allowed_origins,issued_by)"
                    + " values(?,?,?,?,?,'ACTIVE',?::jsonb,?::jsonb,?)",
                tenant(),
                code,
                biz,
                ref,
                Digest.sha256(secret),
                Json.write(limits),
                Json.write(origins),
                Actor.current().principalId());
        audit(code, "INTEGRATION_ISSUE", biz);
        var result = new LinkedHashMap<>(get(code, biz, false));
        result.put("secretOnce", secret);
        result.put("apiKeyOnce", result.get("id") + ":" + secret);
        return result;
    }

    @Transactional
    public Map<String, Object> revoke(String code, String biz) {
        Actor.current().requireRole("ADMIN");
        requireCredential(code, biz);
        db.update(
                "update app.integration_credential set status='REVOKED',revoked_at=now(),policy_revision=policy_revision+1 where"
                    + " tenant_id=? and app_code=? and biz_system_code=?",
                tenant(),
                code,
                biz);
        audit(code, "INTEGRATION_REVOKE", biz);
        return get(code, biz, false);
    }

    @Transactional
    public Map<String, Object> policy(String code, String biz, Map<String, Object> body) {
        Actor.current().requireRole("ADMIN");
        requireCredential(code, biz);
        var limits = object(body.get("claimLimits"));
        Object origins = body.get("allowedOrigins");
        try {
            IntegrationRules.origins(origins);
            IntegrationRules.limits(limits);
        } catch (IllegalArgumentException ex) {
            throw error(400, "INVALID_INTEGRATION_POLICY", ex.getMessage());
        }
        db.update(
                "update app.integration_credential set"
                    + " claim_limits=?::jsonb,allowed_origins=?::jsonb,policy_revision=policy_revision+1 where tenant_id=? and"
                    + " app_code=? and biz_system_code=?",
                Json.write(limits),
                Json.write(origins),
                tenant(),
                code,
                biz);
        audit(code, "INTEGRATION_POLICY", biz);
        return get(code, biz, false);
    }

    /** Lock the real published pointer before authorization and retain the lock until issue commits.
     * The stored immutable published SHA is authoritative; no draft/payload is read to derive it. */
    private void requirePublishedVersion(String code) {
        var rows=db.queryForList("select a.current_version_no,v.candidate_sha256 from app.application a left join app.app_version v on v.tenant_id=a.tenant_id and v.app_code=a.code and v.version_no=a.current_version_no where a.tenant_id=? and a.code=? for update of a",tenant(),code);
        if(rows.isEmpty())throw error(404,"APP_NOT_FOUND","应用不存在");
        var row=rows.getFirst();if(row.get("current_version_no")==null||row.get("candidate_sha256")==null)throw error(409,"APP_UNPUBLISHED","请先发布应用");
        AuthorityContext.requireFor(Actor.current(),new ResourceBinding("app-version",code+"@"+row.get("current_version_no"),row.get("candidate_sha256").toString()));
    }
    /** Policy metadata is read under the row lock. The same transaction verifies the old exact
     * resource, mutates policy and increments revision; concurrent updates cannot lose increments. */
    private void requireCredential(String code,String biz) {
        var rows=db.queryForList("select id,policy_revision,secret_ref,claim_limits,allowed_origins from app.integration_credential where tenant_id=? and app_code=? and biz_system_code=? for update",tenant(),code,biz);
        if(rows.isEmpty())throw error(404,"INTEGRATION_NOT_FOUND","集成凭据不存在");
        var row=rows.getFirst();long id=((Number)row.get("id")).longValue();
        String digest=IntegrationClaims.digest(Json.tree(Map.of("secretRef",row.get("secret_ref"),"claimLimits",Json.read(row.get("claim_limits").toString()),"allowedOrigins",Json.read(row.get("allowed_origins").toString()))));
        String version=Digest.sha256(Json.canonical(Map.of("credentialId",id,"policyVersion",id+":"+row.get("policy_revision"),"policyDigest",digest)));
        AuthorityContext.requireFor(Actor.current(),new ResourceBinding("integration-credential",Long.toString(id),version));
    }

    private void audit(String code, String action, String biz) {
        db.update(
                "insert into"
                    + " app.lifecycle_log(tenant_id,app_code,actor,action,from_state,to_state,detail)"
                    + " values(?,?,?,?,'PUBLISHED','PUBLISHED',?::jsonb)",
                tenant(),
                code,
                Actor.current().principalId(),
                action,
                Json.write(Map.of("bizSystemCode", biz)));
    }
}
