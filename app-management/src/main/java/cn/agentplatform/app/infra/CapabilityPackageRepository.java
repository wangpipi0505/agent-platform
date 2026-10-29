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
