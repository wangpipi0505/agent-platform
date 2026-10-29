package cn.agentplatform.app.infra;

import cn.agentplatform.app.domain.CapabilityPackage;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class CapabilityPackageRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public CapabilityPackageRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
    }

    public Map<String, Object> register(CapabilityPackage pkg, Actor actor) {
        try {
            return tx.execute(status -> {
                // The unique key serializes same-version imports across JVMs. A losing
                // INSERT does not overwrite the winner; its next statement reads that commit.
                db.execute("set local lock_timeout='2s'");
                db.execute("set local statement_timeout='3s'");
                int created = db.update("insert into app.capability_package_version"
                                + "(tenant_id,package_id,package_version,package_sha256,declaration,software_id,software_version,created_by)"
                                + " values(?,?,?,?,?::jsonb,?,?,?) on conflict (tenant_id,package_id,package_version) do nothing",
                        actor.tenantId(), pkg.packageId(), pkg.version(), pkg.sha256(), pkg.declarationJson(),
                        pkg.softwareId(), pkg.softwareVersion(), actor.principalId());
                var rows = db.query("select id,package_id,package_version,package_sha256,software_id,software_version,binding_status"
                                + " from app.capability_package_version where tenant_id=? and package_id=? and package_version=?",
                        this::row, actor.tenantId(), pkg.packageId(), pkg.version());
                if (rows.size() != 1)
                    throw new PlatformException(409, "CAPABILITY_IMPORT_BUSY", "能力包登记冲突，请重试");
                var result = rows.getFirst();
                if (!pkg.sha256().equals(result.get("sha256")))
                    throw new PlatformException(409, "CAPABILITY_VERSION_CONFLICT", "同一能力包版本已有不同声明，请使用新版本");
                result.put("reused", created == 0);
                // Neither a matching declaration nor a user-supplied verification flag is
                // evidence of a registered skill, evaluated application or executable route.
                result.put("verificationStatus", "NOT_EVALUATED");
                result.put("appCreated", false);
                result.put("applications", List.of());
                return result;
            });
        } catch (CannotAcquireLockException | QueryTimeoutException ex) {
            throw new PlatformException(409, "CAPABILITY_IMPORT_BUSY", "能力包登记忙，请重试");
        }
    }

    public Map<String, Object> bindingSource(long id, String tenant) {
        var rows = db.query("select * from app.capability_package_version where tenant_id=? and id=?",
                (r, n) -> {
                    var result = row(r, n);
                    result.put("declaration", cn.agentplatform.common.Json.read(r.getString("declaration"), Map.class));
                    result.put("planSha256", r.getString("request_plan_sha256"));
                    String mapping = r.getString("resolved_mapping");
                    result.put("resolvedMapping", mapping == null ? Map.of() : cn.agentplatform.common.Json.read(mapping, Map.class));
                    return result;
                }, tenant, id);
        if (rows.isEmpty()) throw new PlatformException(404, "CAPABILITY_PACKAGE_NOT_FOUND", "能力包版本不存在");
        return rows.getFirst();
    }

    public Map<String, Object> bindingReceipt(Map<String, Object> stored, String planSha, boolean reused) {
        if (!"RESOLVED".equals(stored.get("bindingStatus")) || !planSha.equals(stored.get("planSha256")))
            throw new PlatformException(409, "CAPABILITY_IMPORT_PLAN_CONFLICT", "该包版本已按不同计划解析，请登记新版本");
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("packageVersionId", "packageId", "version", "sha256", "softwareId", "softwareVersion"))
            if (stored.containsKey(key)) result.put(key, stored.get(key));
        result.putAll((Map<String, Object>) stored.get("resolvedMapping"));
        result.put("bindingStatus", "RESOLVED"); result.put("verificationStatus", "NOT_EVALUATED");
        result.put("appCreated", false); result.put("applications", List.of()); result.put("reused", reused);
        return result;
    }

    public Map<String, Object> resolveBinding(long id, Actor actor, String packageSha, String planSha, Map<String, Object> mapping) {
        try {
            return tx.execute(status -> {
                db.execute("set local lock_timeout='2s'");
                db.execute("set local statement_timeout='3s'");
                // UPDATE 的 tenant+原包摘要条件与行锁共同裁决并发；失败者只读取原收据，绝不覆写。
                int changed = db.update("update app.capability_package_version set binding_status='RESOLVED',"
                                + "request_plan_sha256=?,resolved_mapping=?::jsonb,resolved_by=?,resolved_at=now()"
                                + " where tenant_id=? and id=? and package_sha256=? and binding_status='UNRESOLVED'",
                        planSha, cn.agentplatform.common.Json.write(mapping), actor.principalId(), actor.tenantId(), id, packageSha);
                var stored = bindingSource(id, actor.tenantId());
                if (!packageSha.equals(stored.get("sha256")))
                    throw new PlatformException(409, "CAPABILITY_VERSION_CONFLICT", "解析期间包声明已改变");
                return bindingReceipt(stored, planSha, changed == 0);
            });
        } catch (CannotAcquireLockException | QueryTimeoutException ex) {
            throw new PlatformException(409, "CAPABILITY_IMPORT_BUSY", "能力包解析忙，请重试");
        }
    }

    private Map<String, Object> row(ResultSet r, int ignored) throws SQLException {
        var value = new LinkedHashMap<String, Object>();
        value.put("packageVersionId", r.getLong("id"));
        value.put("packageId", r.getString("package_id"));
        value.put("version", r.getString("package_version"));
        value.put("sha256", r.getString("package_sha256"));
        value.put("softwareId", r.getString("software_id"));
        if (r.getString("software_version") != null) value.put("softwareVersion", r.getString("software_version"));
        value.put("bindingStatus", r.getString("binding_status"));
        return value;
    }
}
