package cn.agentplatform.app.infra;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只读取租户内不可变包版本及真实来源关系，不从声明推断运行或验证结论。 */
@Repository
public class CapabilityPackageQueryRepository {
    private final JdbcTemplate db;
    private static final String SUMMARY = "select p.id,p.package_id,p.package_version,p.package_sha256,"
        + "p.software_id,p.software_version,p.binding_status,p.created_at,p.resolved_at,"
        + "(select count(*) from app.capability_app_binding b where b.tenant_id=p.tenant_id and b.package_version_id=p.id) as application_count";
    public CapabilityPackageQueryRepository(JdbcTemplate db) { this.db = db; }

    public Map<String, Object> list(String tenant, int limit, long before, Map<String, String> filters) {
        var sql = new StringBuilder(SUMMARY + " from app.capability_package_version p where p.tenant_id=? and p.id<?");
        var args = new ArrayList<Object>(List.of(tenant, before));
        String query = filters.getOrDefault("q", "").trim();
        if (!query.isEmpty()) {
            sql.append(" and (position(lower(?) in lower(p.package_id))>0 or position(lower(?) in lower(p.software_id))>0)");
            args.add(query); args.add(query);
        }
        // 这里筛选的是原声明引用；不把用户提供的空间/release 当作本体授权或已解析的事实。
        for (String key : List.of("ontologySpace", "ontologyRelease")) {
            String value = filters.getOrDefault(key, "");
            if (!value.isEmpty()) {
                sql.append(key.equals("ontologySpace") ? " and p.declaration#>>'{ontology,space}'=?" : " and p.declaration#>>'{ontology,release}'=?");
                args.add(value);
            }
        }
        sql.append(" order by p.id desc limit ?"); args.add(limit + 1);
        var rows = db.query(sql.toString(), this::summary, args.toArray());
        var result = new LinkedHashMap<String, Object>();
        boolean more = rows.size() > limit;
        result.put("items", new ArrayList<>(rows.subList(0, Math.min(limit, rows.size()))));
        if (more) result.put("nextBeforeId", rows.get(limit - 1).get("packageVersionId"));
        return result;
    }
    public Map<String, Object> detail(String tenant, long id) {
        var rows = db.query(SUMMARY + ",p.declaration,p.resolved_mapping from app.capability_package_version p where p.tenant_id=? and p.id=?",
            (r, n) -> {
                var value = summary(r, n);
                value.put("declaration", Json.read(r.getString("declaration"), Map.class));
                String mapping = r.getString("resolved_mapping");
                value.put("resolvedMapping", mapping == null ? Map.of() : Json.read(mapping, Map.class));
                return value;
            }, tenant, id);
        if (rows.isEmpty()) throw new PlatformException(404, "CAPABILITY_PACKAGE_NOT_FOUND", "能力包版本不存在");
        var value = rows.getFirst();
        var applications = db.query("select a.id,a.code,a.name,a.type,a.status,a.current_version_no,d.draft_rev"
            + " from app.capability_app_binding b join app.application a on a.tenant_id=b.tenant_id and a.id=b.app_id and a.code=b.app_code"
            + " left join app.app_draft d on d.tenant_id=a.tenant_id and d.app_id=a.id and d.app_code=a.code"
            + " where b.tenant_id=? and b.package_version_id=? order by a.id limit 101", (r, n) -> {
                var app = new LinkedHashMap<String, Object>();
                app.put("appId", r.getLong("id")); app.put("appCode", r.getString("code"));
                app.put("name", r.getString("name")); app.put("type", r.getString("type"));
                app.put("status", r.getString("status")); app.put("draftRev", r.getObject("draft_rev"));
                app.put("currentVersionNo", r.getObject("current_version_no")); return app;
            }, tenant, id);
        value.put("applications", new ArrayList<>(applications.subList(0, Math.min(100, applications.size()))));
        value.put("applicationsTruncated", applications.size() > 100);
        return value;
    }
    private Map<String, Object> summary(ResultSet r, int ignored) throws SQLException {
        var value = new LinkedHashMap<String, Object>();
        value.put("packageVersionId", r.getLong("id")); value.put("packageId", r.getString("package_id"));
        value.put("version", r.getString("package_version")); value.put("sha256", r.getString("package_sha256"));
        value.put("softwareId", r.getString("software_id")); value.put("softwareVersion", r.getString("software_version"));
        value.put("bindingStatus", r.getString("binding_status")); value.put("verificationStatus", "NOT_EVALUATED");
        value.put("applicationCount", r.getLong("application_count"));
        value.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
        var resolved = r.getTimestamp("resolved_at");
        if (resolved != null) value.put("resolvedAt", resolved.toInstant().toString());
        return value;
    }
}
