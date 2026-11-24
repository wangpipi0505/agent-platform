package cn.agentplatform.app.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class PlatformGrantService {
    private static final Set<String> ROLES = Set.of("ADMIN", "BUILDER", "OPERATOR", "USER");
    private final JdbcTemplate db;
    private final PlatformAccessService access;

    public PlatformGrantService(JdbcTemplate db, PlatformAccessService access) {
        this.db = db;
        this.access = access;
    }

    public List<Grant> grants(Actor actor, String subject) {
        access.require(actor, PlatformAccessService.GRANT, null);
        return db.query("""
                select platform_role,app_scope::text,status,granted_by,expires_at
                from app.platform_user_role where tenant_id=? and subject=? order by platform_role
                """, (row, index) -> {
                    var expiry = row.getObject("expires_at", OffsetDateTime.class);
                    return new Grant(subject, row.getString("platform_role"),
                            List.of(Json.read(row.getString(2), String[].class)), row.getString("status"),
                            row.getString("granted_by"), expiry == null ? null : expiry.toInstant());
                }, actor.tenantId(), subject(subject));
    }

    public List<Map<String, Object>> audit(Actor actor, String subject) {
        access.require(actor, "audit.read", null);
        return db.queryForList("""
                select platform_role,event_type,actor_type,actor_subject,occurred_at
                from app.platform_role_audit where tenant_id=? and subject=? order by id desc limit 100
                """, actor.tenantId(), subject(subject));
    }

    public List<Map<String, Object>> configurationAudit(Actor actor) {
        access.require(actor, "audit.read", null);
        return db.query("""
                select target_table,operation,capability,database_actor,before_state::text,after_state::text,occurred_at
                from app.platform_capability_audit order by id desc limit 100
                """, (row, index) -> {
                    var item = new java.util.LinkedHashMap<String, Object>();
                    item.put("targetTable", row.getString(1));
                    item.put("operation", row.getString(2));
                    item.put("capability", row.getString(3));
                    item.put("databaseActor", row.getString(4));
                    String before = row.getString(5);
                    String after = row.getString(6);
                    item.put("before", before == null ? null : Json.read(before));
                    item.put("after", after == null ? null : Json.read(after));
                    item.put("occurredAt", row.getObject(7, OffsetDateTime.class).toInstant());
                    return item;
                });
    }

    @Transactional
    public void save(Actor actor, String subject, String role, Collection<String> applicationCodes, Instant expiresAt) {
        lockAndRequireGrant(actor);
        subject(subject);
        role(role);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now()))
            throw invalid("授权有效期必须晚于当前时间");
        var codes = requireApplications(actor.tenantId(), applicationCodes);
        var expiry = expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC);
        int changed = db.update("""
                insert into app.platform_user_role(tenant_id,subject,platform_role,app_scope,granted_by,expires_at)
                values(?,?,?,?::jsonb,?,?)
                on conflict(tenant_id,subject,platform_role) do update
                set app_scope=excluded.app_scope, granted_by=excluded.granted_by, granted_at=statement_timestamp(),
                    expires_at=excluded.expires_at, status='ACTIVE', revoked_at=null, updated_at=statement_timestamp()
                where app.platform_user_role.status<>'ACTIVE'
                   or app.platform_user_role.app_scope is distinct from excluded.app_scope
                   or app.platform_user_role.expires_at is distinct from excluded.expires_at
                """, actor.tenantId(), subject, role, Json.write(codes), actor.principalId(), expiry);
        if (changed == 1) auditChange(actor, subject, role, "PLATFORM_GRANT_SAVED");
    }

    @Transactional
    public void revoke(Actor actor, String subject, String role) {
        lockAndRequireGrant(actor);
        int changed = db.update("""
                update app.platform_user_role set status='REVOKED',revoked_at=statement_timestamp(),
                    updated_at=statement_timestamp()
                where tenant_id=? and subject=? and platform_role=? and status='ACTIVE'
                """, actor.tenantId(), subject(subject), role(role));
        if (changed == 1) auditChange(actor, subject, role, "PLATFORM_GRANT_REVOKED");
    }

    private void lockAndRequireGrant(Actor actor) {
        // Serialize revocation with concurrent writes by the same administrator and with first bootstrap.
        db.execute("lock table app.platform_user_role in share row exclusive mode");
        access.require(actor, PlatformAccessService.GRANT, null);
    }

    private void auditChange(Actor actor, String subject, String role, String action) {
        db.update("""
                insert into app.platform_role_audit(tenant_id,subject,platform_role,event_type,actor_type,actor_subject)
                values(?,?,?,?,'USER',?)
                """, actor.tenantId(), subject, role, action, actor.principalId());
    }

    private Set<String> requireApplications(String tenant, Collection<String> applicationCodes) {
        if (applicationCodes == null || applicationCodes.size() > 200
                || applicationCodes.stream().anyMatch(code -> code == null || !code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")))
            throw invalid("应用授权范围无效");
        var codes = new TreeSet<>(applicationCodes);
        if (codes.isEmpty()) return codes;
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(tenant);
        parameters.addAll(codes);
        var placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        long count = db.queryForObject("select count(*) from app.application where tenant_id=? and code in ("
                + placeholders + ")", Long.class, parameters.toArray());
        if (count != codes.size()) throw invalid("应用授权范围包含本租户之外或不存在的应用");
        return codes;
    }

    private static String subject(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("授权主体无效");
        return value;
    }

    private static String role(String value) {
        if (value == null || !ROLES.contains(value)) throw invalid("平台角色无效");
        return value;
    }

    private static PlatformException invalid(String message) {
        return new PlatformException(400, "INVALID_PLATFORM_GRANT", message);
    }

    public record Grant(String subject, String role, List<String> appScope,
                        String status, String grantedBy, Instant expiresAt) {}
}
