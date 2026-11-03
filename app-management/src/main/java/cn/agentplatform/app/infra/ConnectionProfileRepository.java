package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 连接档案（ConnectionProfile）与环境绑定（EnvironmentBinding）的唯一写入口。
 *
 * 事务所有权放在本类：对象行与修订行必须同生同死，"当前修订指针 + 新修订"任何一半落空都会让目录自相矛盾，
 * 因此调用方（application 层）只做字段校验与归一，不自己开事务。
 *
 * 三条不变量：
 * <ul>
 *   <li>修订不可变：*_version / *_revision 只追加，本类没有任何 UPDATE 修订行的语句；旧行永久保留。</li>
 *   <li>幂等以 (tenant_id, request_id) 为键：同号只落一次结果，重放返回首次响应；同号换内容直接拒绝。</li>
 *   <li>并发以 expectedRev 做 CAS：更新条件里带 current_rev，未命中即按"不存在 / 版本冲突 / 状态不允许"分开报错。</li>
 * </ul>
 *
 * 有意限制：本类不发起任何外部调用（健康探针与可用性由后续子步负责），也不读取或保存凭据明文，
 * secret_ref 只是引用字符串。
 */
@Repository
public class ConnectionProfileRepository {

    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public ConnectionProfileRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
    }

    /** 连接档案的不可变修订内容；sha256 由 application 层按归一后的声明算出。 */
    public record ProfileRevision(
            String protocol,
            List<String> operationAllowlist,
            Map<String, Object> endpointPolicy,
            Map<String, Object> authScheme,
            Map<String, Object> identityPolicy,
            List<String> inputContractRefs,
            List<String> outputContractRefs,
            List<String> allowedUses,
            String sha256) {}

    /** 环境绑定的不可变修订内容。 */
    public record BindingRevision(
            String environment,
            String connectionProfileId,
            int connectionProfileRev,
            String baseEndpoint,
            String secretRef,
            Map<String, Object> runtimeRef,
            Map<String, Object> originPolicy,
            Map<String, Object> healthPolicy,
            String sha256) {}

    /** 档案当前状态；revisionExists=false 表示档案存在但被引用的修订不存在。 */
    public record ProfileState(int currentRev, String status, boolean revisionExists) {}

    // ---------------------------------------------------------------- connection profile

    public Map<String, Object> createProfile(Actor actor, String profileId, String requestId, String requestSha,
                                             ProfileRevision rev) {
        String operation = "connection-profile.create";
        return locked(() -> {
            var prior = prior(actor, requestId, requestSha);
            if (prior != null) return prior;
            try {
                db.update("insert into app.connection_profile(tenant_id,profile_id,current_rev,status,created_by)"
                        + " values(?,?,1,'DRAFT',?)", actor.tenantId(), profileId, actor.principalId());
            } catch (DuplicateKeyException e) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "连接档案已存在: " + profileId);
            }
            insertProfileVersion(actor, profileId, 1, rev);
            return remember(actor, requestId, operation, profileId, requestSha, view(actor, profileId));
        });
    }

    public Map<String, Object> reviseProfile(Actor actor, String profileId, int expectedRev, String requestId,
                                             String requestSha, ProfileRevision rev) {
        String operation = "connection-profile.revise";
        return locked(() -> {
            var prior = prior(actor, requestId, requestSha);
            if (prior != null) return prior;
            int changed = db.update("update app.connection_profile set current_rev=?, updated_at=now()"
                    + " where tenant_id=? and profile_id=? and current_rev=? and status='DRAFT'",
                    expectedRev + 1, actor.tenantId(), profileId, expectedRev);
            if (changed != 1) throw explainProfile(actor, profileId, expectedRev, "生成新修订");
            insertProfileVersion(actor, profileId, expectedRev + 1, rev);
            return remember(actor, requestId, operation, profileId, requestSha, view(actor, profileId));
        });
    }

    /** 生命周期迁移：APPROVED 只接受 DRAFT 来源，DISABLED 接受 DRAFT/APPROVED；两者都不改写任何修订行。 */
    public Map<String, Object> transitionProfile(Actor actor, String profileId, int expectedRev, String requestId,
                                                 String requestSha, String targetStatus) {
        String operation = "connection-profile." + ("APPROVED".equals(targetStatus) ? "approve" : "disable");
        String guard = "APPROVED".equals(targetStatus) ? "status='DRAFT'" : "status in ('DRAFT','APPROVED')";
        return locked(() -> {
            var prior = prior(actor, requestId, requestSha);
            if (prior != null) return prior;
            int changed = db.update("update app.connection_profile set status=?, updated_at=now()"
                            + " where tenant_id=? and profile_id=? and current_rev=? and " + guard,
                    targetStatus, actor.tenantId(), profileId, expectedRev);
            if (changed != 1)
                throw explainProfile(actor, profileId, expectedRev,
                        "APPROVED".equals(targetStatus) ? "执行批准" : "执行停用");
            return remember(actor, requestId, operation, profileId, requestSha, view(actor, profileId));
        });
    }

    public Optional<Map<String, Object>> profile(Actor actor, String profileId) {
        return head(actor, profileId).map(head -> view(actor, profileId));
    }

    public List<Map<String, Object>> profiles(Actor actor) {
        return db.query("select profile_id from app.connection_profile where tenant_id=? order by profile_id",
                (rs, n) -> rs.getString(1), actor.tenantId()).stream()
                .map(id -> view(actor, id))
                .filter(Objects::nonNull)
                .toList();
    }

    public Optional<ProfileState> profileState(Actor actor, String profileId, int revision) {
        var rows = db.query("select current_rev,status from app.connection_profile where tenant_id=? and profile_id=?",
                (rs, n) -> new ProfileState(rs.getInt(1), rs.getString(2), false),
                actor.tenantId(), profileId);
        if (rows.isEmpty()) return Optional.empty();
        var head = rows.get(0);
        boolean exists = !db.query("select 1 from app.connection_profile_version"
                        + " where tenant_id=? and profile_id=? and profile_rev=?",
                (rs, n) -> 1, actor.tenantId(), profileId, revision).isEmpty();
        return Optional.of(new ProfileState(head.currentRev(), head.status(), exists));
    }

    // ---------------------------------------------------------------- environment binding

    public Map<String, Object> createBinding(Actor actor, String bindingId, String requestId, String requestSha,
                                             BindingRevision rev) {
        String operation = "environment-binding.create";
        return locked(() -> {
            var prior = prior(actor, requestId, requestSha);
            if (prior != null) return prior;
            requireProfileRevision(actor, rev.connectionProfileId(), rev.connectionProfileRev());
            try {
                db.update("insert into app.environment_binding(tenant_id,binding_id,current_rev,status,created_by)"
                        + " values(?,?,1,'DRAFT',?)", actor.tenantId(), bindingId, actor.principalId());
            } catch (DuplicateKeyException e) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "环境绑定已存在: " + bindingId);
            }
            insertBindingRevision(actor, bindingId, 1, rev);
            return remember(actor, requestId, operation, bindingId, requestSha, bindingView(actor, bindingId));
        });
    }

    public Map<String, Object> reviseBinding(Actor actor, String bindingId, int expectedRev, String requestId,
                                             String requestSha, BindingRevision rev) {
        String operation = "environment-binding.revise";
        return locked(() -> {
            var prior = prior(actor, requestId, requestSha);
            if (prior != null) return prior;
            requireProfileRevision(actor, rev.connectionProfileId(), rev.connectionProfileRev());
            int changed = db.update("update app.environment_binding set current_rev=?, updated_at=now()"
                    + " where tenant_id=? and binding_id=? and current_rev=? and status='DRAFT'",
                    expectedRev + 1, actor.tenantId(), bindingId, expectedRev);
            if (changed != 1) throw explainBinding(actor, bindingId, expectedRev, "生成新修订");
            insertBindingRevision(actor, bindingId, expectedRev + 1, rev);
            return remember(actor, requestId, operation, bindingId, requestSha, bindingView(actor, bindingId));
        });
    }

    public Map<String, Object> transitionBinding(Actor actor, String bindingId, int expectedRev, String requestId,
                                                 String requestSha, String targetStatus) {
        String operation = "environment-binding." + ("APPROVED".equals(targetStatus) ? "approve" : "disable");
        String guard = "APPROVED".equals(targetStatus) ? "status='DRAFT'" : "status in ('DRAFT','APPROVED')";
        return locked(() -> {
            var prior = prior(actor, requestId, requestSha);
            if (prior != null) return prior;
            int changed = db.update("update app.environment_binding set status=?, updated_at=now()"
                            + " where tenant_id=? and binding_id=? and current_rev=? and " + guard,
                    targetStatus, actor.tenantId(), bindingId, expectedRev);
            if (changed != 1)
                throw explainBinding(actor, bindingId, expectedRev,
                        "APPROVED".equals(targetStatus) ? "执行批准" : "执行停用");
            return remember(actor, requestId, operation, bindingId, requestSha, bindingView(actor, bindingId));
        });
    }

    public Optional<Map<String, Object>> binding(Actor actor, String bindingId) {
        return bindingHead(actor, bindingId).map(head -> bindingView(actor, bindingId));
    }

    public List<Map<String, Object>> bindings(Actor actor) {
        return db.query("select binding_id from app.environment_binding where tenant_id=? order by binding_id",
                (rs, n) -> rs.getString(1), actor.tenantId()).stream()
                .map(id -> bindingView(actor, id))
                .filter(Objects::nonNull)
                .toList();
    }

    // ---------------------------------------------------------------- internals

    /** 事务 + 锁超时降级：并发写只失败不排队，失败方按同一 requestId 重试即可拿到首次结果。 */
    private <T> T locked(java.util.function.Supplier<T> work) {
        try {
            return tx.execute(status -> {
                db.execute("set local lock_timeout='2s'");
                db.execute("set local statement_timeout='3s'");
                return work.get();
            });
        } catch (CannotAcquireLockException | QueryTimeoutException e) {
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "配置写入正被并发修改，请以同一 requestId 重试");
        }
    }

    /** 首次返回 null；已处理过则返回首次响应（replayed=true），内容不同视为同号复用而拒绝。 */
    private Map<String, Object> prior(Actor actor, String requestId, String requestSha) {
        var rows = db.query("select request_sha256,response::text from app.config_write_request"
                        + " where tenant_id=? and request_id=?",
                (rs, n) -> Map.of("sha", rs.getString(1), "body", rs.getString(2)),
                actor.tenantId(), requestId);
        if (rows.isEmpty()) return null;
        if (!requestSha.equals(rows.get(0).get("sha")))
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "同一 requestId 已用于不同的写入内容: " + requestId);
        Map<String, Object> body = Json.read((String) rows.get(0).get("body"), Map.class);
        body.put("replayed", true);
        return body;
    }

    private Map<String, Object> remember(Actor actor, String requestId, String operation, String target,
                                         String requestSha, Map<String, Object> response) {
        Map<String, Object> body = new LinkedHashMap<>(response);
        body.put("requestId", requestId);
        body.put("replayed", false);
        db.update("insert into app.config_write_request(tenant_id,request_id,operation,target,request_sha256,response)"
                + " values(?,?,?,?,?,?::jsonb)", actor.tenantId(), requestId, operation, target, requestSha,
                Json.write(body));
        return body;
    }

    private void insertProfileVersion(Actor actor, String profileId, int rev, ProfileRevision r) {
        db.update("insert into app.connection_profile_version(tenant_id,profile_id,profile_rev,protocol,"
                        + "operation_allowlist,endpoint_policy,auth_scheme,identity_policy,input_contract_refs,"
                        + "output_contract_refs,allowed_uses,declaration_sha256,created_by)"
                        + " values(?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?)",
                actor.tenantId(), profileId, rev, r.protocol(), Json.write(r.operationAllowlist()),
                Json.write(r.endpointPolicy()), Json.write(r.authScheme()), Json.write(r.identityPolicy()),
                Json.write(r.inputContractRefs()), Json.write(r.outputContractRefs()), Json.write(r.allowedUses()),
                r.sha256(), actor.principalId());
    }

    private void insertBindingRevision(Actor actor, String bindingId, int rev, BindingRevision r) {
        db.update("insert into app.environment_binding_revision(tenant_id,binding_id,binding_rev,environment,"
                        + "connection_profile_id,connection_profile_rev,base_endpoint,secret_ref,runtime_ref,"
                        + "origin_policy,health_policy,declaration_sha256,created_by)"
                        + " values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?)",
                actor.tenantId(), bindingId, rev, r.environment(), r.connectionProfileId(),
                r.connectionProfileRev(), r.baseEndpoint(), r.secretRef(), Json.write(r.runtimeRef()),
                Json.write(r.originPolicy()), Json.write(r.healthPolicy()), r.sha256(), actor.principalId());
    }

    /** 绑定修订必须落在已存在的连接修订上；外部外键只是兜底，这里给出可读错误码（不把约束异常抛给调用方）。 */
    private void requireProfileRevision(Actor actor, String profileId, int revision) {
        var state = profileState(actor, profileId, revision).orElseThrow(() ->
                new PlatformException(400, "CONFIG_VERSION_UNKNOWN", "连接档案不存在: " + profileId));
        if (!state.revisionExists())
            throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
                    "连接档案修订不存在: " + profileId + "@" + revision);
    }

    private Optional<Map<String, Object>> head(Actor actor, String profileId) {
        var rows = db.query("select current_rev,status,created_by,created_at,updated_at from app.connection_profile"
                        + " where tenant_id=? and profile_id=?",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("currentRev", rs.getInt(1));
                    m.put("status", rs.getString(2));
                    m.put("createdBy", rs.getString(3));
                    m.put("createdAt", rs.getString(4));
                    m.put("updatedAt", rs.getString(5));
                    return m;
                }, actor.tenantId(), profileId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Map<String, Object> view(Actor actor, String profileId) {
        var head = head(actor, profileId).orElse(null);
        if (head == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", profileId);
        out.putAll(head);
        out.put("revisions", db.query("select profile_rev,protocol,operation_allowlist::text,endpoint_policy::text,"
                        + "auth_scheme::text,identity_policy::text,input_contract_refs::text,output_contract_refs::text,"
                        + "allowed_uses::text,declaration_sha256,created_by,created_at"
                        + " from app.connection_profile_version where tenant_id=? and profile_id=? order by profile_rev",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("profileRev", rs.getInt(1));
                    m.put("protocol", rs.getString(2));
                    m.put("operationAllowlist", Json.read(rs.getString(3), Object.class));
                    m.put("endpointPolicy", Json.read(rs.getString(4), Object.class));
                    m.put("authScheme", Json.read(rs.getString(5), Object.class));
                    m.put("identityPolicy", Json.read(rs.getString(6), Object.class));
                    m.put("inputContractRefs", Json.read(rs.getString(7), Object.class));
                    m.put("outputContractRefs", Json.read(rs.getString(8), Object.class));
                    m.put("allowedUses", Json.read(rs.getString(9), Object.class));
                    m.put("declarationSha256", rs.getString(10));
                    m.put("createdBy", rs.getString(11));
                    m.put("createdAt", rs.getString(12));
                    return m;
                }, actor.tenantId(), profileId));
        return out;
    }

    private Optional<Map<String, Object>> bindingHead(Actor actor, String bindingId) {
        var rows = db.query("select current_rev,status,created_by,created_at,updated_at from app.environment_binding"
                        + " where tenant_id=? and binding_id=?",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("currentRev", rs.getInt(1));
                    m.put("status", rs.getString(2));
                    m.put("createdBy", rs.getString(3));
                    m.put("createdAt", rs.getString(4));
                    m.put("updatedAt", rs.getString(5));
                    return m;
                }, actor.tenantId(), bindingId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Map<String, Object> bindingView(Actor actor, String bindingId) {
        var head = bindingHead(actor, bindingId).orElse(null);
        if (head == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingId", bindingId);
        out.putAll(head);
        out.put("revisions", db.query("select binding_rev,environment,connection_profile_id,connection_profile_rev,"
                        + "base_endpoint,secret_ref,runtime_ref::text,origin_policy::text,health_policy::text,"
                        + "declaration_sha256,created_by,created_at"
                        + " from app.environment_binding_revision where tenant_id=? and binding_id=?"
                        + " order by binding_rev",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bindingRev", rs.getInt(1));
                    m.put("environment", rs.getString(2));
                    m.put("connectionProfileId", rs.getString(3));
                    m.put("connectionProfileRev", rs.getInt(4));
                    m.put("baseEndpoint", rs.getString(5));
                    m.put("secretRef", rs.getString(6));
                    m.put("runtimeRef", Json.read(rs.getString(7), Object.class));
                    m.put("originPolicy", Json.read(rs.getString(8), Object.class));
                    m.put("healthPolicy", Json.read(rs.getString(9), Object.class));
                    m.put("declarationSha256", rs.getString(10));
                    m.put("createdBy", rs.getString(11));
                    m.put("createdAt", rs.getString(12));
                    return m;
                }, actor.tenantId(), bindingId));
        return out;
    }

    /** 0 行受影响时区分"不存在 / 版本冲突 / 状态不允许"，避免把并发失败误报成参数错误。 */
    private PlatformException explainProfile(Actor actor, String profileId, int expectedRev, String action) {
        var head = head(actor, profileId);
        if (head.isEmpty())
            return new PlatformException(404, "CONFIG_VERSION_UNKNOWN", "连接档案不存在: " + profileId);
        int current = (Integer) head.get().get("currentRev");
        if (current != expectedRev)
            return new PlatformException(409, "CONFIG_REV_CONFLICT",
                    "profileRev 冲突：期望 " + expectedRev + "，当前 " + current);
        return new PlatformException(400, "INVALID_INPUT",
                "连接档案状态 " + head.get().get("status") + " 不允许" + action);
    }

    private PlatformException explainBinding(Actor actor, String bindingId, int expectedRev, String action) {
        var head = bindingHead(actor, bindingId);
        if (head.isEmpty())
            return new PlatformException(404, "CONFIG_VERSION_UNKNOWN", "环境绑定不存在: " + bindingId);
        int current = (Integer) head.get().get("currentRev");
        if (current != expectedRev)
            return new PlatformException(409, "CONFIG_REV_CONFLICT",
                    "bindingRev 冲突：期望 " + expectedRev + "，当前 " + current);
        return new PlatformException(400, "INVALID_INPUT",
                "环境绑定状态 " + head.get().get("status") + " 不允许" + action);
    }
}
