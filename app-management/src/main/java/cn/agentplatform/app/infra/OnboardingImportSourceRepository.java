package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 冻结原规格与一次性解析结果；用户修改当前计划不能改写已交付应用的来源。 */
@Repository
public class OnboardingImportSourceRepository {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public OnboardingImportSourceRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    public Map<String, Object> freeze(Map<String, Object> session, Map<String, Object> packageSource,
            Map<String, Object> prepared, Map<String, Object> request) {
        Actor actor = Actor.current();
        actor.requireRole("EDITOR");
        String id = session.get("sessionId").toString();
        String plan = session.get("planSha256").toString();
        String sourceId = Digest.sha256(Json.canonical(List.of(actor.tenantId(), id, plan)));
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("package", packageSource);
        snapshot.put("prepared", prepared);
        snapshot.put("importRequest", request);
        String sha = Digest.sha256(Json.canonical(snapshot));
        return tx.execute(status -> {
            var current = db.queryForList("select plan_sha256,status from app.onboarding_session"
                    + " where tenant_id=? and principal_id=? and session_id=? for update",
                    actor.tenantId(), actor.principalId(), id);
            if (current.isEmpty() || !plan.equals(current.getFirst().get("plan_sha256"))
                    || List.of("PUBLISHED", "CANCELLED").contains(current.getFirst().get("status")))
                throw conflict("计划或会话状态已经变化，未冻结新来源");
            var template = db.queryForList("select current_rev,status from app.onboarding_template"
                    + " where tenant_id=? and code=? for share", actor.tenantId(), session.get("templateCode"));
            var pkg = db.queryForList("select package_sha256 from app.capability_package_version"
                    + " where tenant_id=? and id=? for share", actor.tenantId(), packageSource.get("packageVersionId"));
            if (template.isEmpty() || !"PUBLISHED".equals(template.getFirst().get("status"))
                    || ((Number) template.getFirst().get("current_rev")).intValue()
                        != ((Number) session.get("templateRev")).intValue()
                    || pkg.isEmpty() || !packageSource.get("sha256").equals(pkg.getFirst().get("package_sha256")))
                throw conflict("规格预检期间模板或包来源已变化");
            var existing = get(actor, id, plan);
            if (existing != null) {
                // 当前grant摘要可能合法变化；恢复使用首次冻结的请求，不重解释旧规格/人工合同。
                if (!packageSource.get("sha256").equals(existing.get("packageSha256"))
                        || !prepared.get("documentSha256").equals(existing.get("documentSha256"))
                        || !Json.canonical(session.get("plan")).equals(Json.canonical(existing.get("frozenPlan"))))
                    throw conflict("同一计划不能替换原始规格或业务输入");
                return existing;
            }
            db.update("insert into app.onboarding_import_source"
                    + "(tenant_id,source_id,session_id,principal_id,plan_sha256,package_version_id,"
                    + "package_sha256,document_sha256,frozen_plan,snapshot,snapshot_sha256)"
                    + " values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?) on conflict do nothing",
                    actor.tenantId(), sourceId, id, actor.principalId(), plan,
                    packageSource.get("packageVersionId"), packageSource.get("sha256"),
                    prepared.get("documentSha256"), Json.write(session.get("plan")), Json.write(snapshot), sha);
            var out = get(actor, id, plan);
            if (out == null || !sha.equals(out.get("snapshotSha256")))
                throw conflict("同一计划已有不同导入依据，不能替换原规格或导入策略");
            return out;
        });
    }

    public Map<String, Object> get(Actor actor, String sessionId, String planSha256) {
        var rows = db.queryForList("select * from app.onboarding_import_source"
                + " where tenant_id=? and principal_id=? and session_id=? and plan_sha256=?",
                actor.tenantId(), actor.principalId(), sessionId, planSha256);
        return rows.isEmpty() ? null : view(rows.getFirst());
    }

    public Map<String, Object> require(Actor actor, String sourceId) {
        var rows = db.queryForList("select * from app.onboarding_import_source"
                + " where tenant_id=? and principal_id=? and source_id=?",
                actor.tenantId(), actor.principalId(), sourceId);
        if (rows.isEmpty()) throw conflict("当前主体没有该会话来源");
        return view(rows.getFirst());
    }

    /** published 来源比较在应用层完成；这里只允许固定计划的一次性原子固化。 */
    public Map<String, Object> resolve(Actor actor, String sourceId, Map<String, Object> source) {
        return tx.execute(status -> {
            var rows = db.queryForList("select * from app.onboarding_import_source"
                    + " where tenant_id=? and principal_id=? and source_id=? for update",
                    actor.tenantId(), actor.principalId(), sourceId);
            if (rows.isEmpty()) throw conflict("会话来源不存在");
            var row = view(rows.getFirst());
            if (!sourceId.equals(source.get("sessionSourceId"))
                    || !row.get("planSha256").equals(source.get("planSha256"))
                    || !row.get("packageSha256").equals(source.get("sha256"))
                    || !row.get("documentSha256").equals(source.get("documentSha256")))
                throw conflict("解析结果与冻结来源坐标不同");
            if ("RESOLVED".equals(row.get("status"))) {
                if (!Json.canonical(row.get("resolvedSource")).equals(Json.canonical(source)))
                    throw conflict("已固化来源不可重绑为另一组技能");
            } else {
                db.update("update app.onboarding_import_source set status='RESOLVED',"
                        + "resolved_source=?::jsonb,resolved_at=now() where tenant_id=? and source_id=?",
                        Json.write(source), actor.tenantId(), sourceId);
            }
            return source;
        });
    }

    public void bind(Actor actor, Map<String, Object> app, String sourceId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Session source binding requires the application transaction");
        var row = require(actor, sourceId);
        if (!"RESOLVED".equals(row.get("status"))) throw conflict("新技能来源尚未固化");
        int changed = db.update("update app.capability_app_binding b set session_source_id=?,source=?::jsonb"
                + " where tenant_id=? and app_id=? and app_code=? and package_version_id=?"
                + " and (session_source_id is null or session_source_id=? or exists"
                + " (select 1 from app.onboarding_import_source prior where prior.tenant_id=b.tenant_id"
                + " and prior.source_id=b.session_source_id and prior.session_id=? and prior.principal_id=?))",
                sourceId, Json.write(row.get("resolvedSource")), actor.tenantId(), app.get("id"),
                app.get("code"), row.get("packageVersionId"), sourceId, row.get("sessionId"), actor.principalId());
        if (changed != 1) throw conflict("应用必须先建立属于本会话的真实包关系");
    }

    public List<Map<String, Object>> applicationBindings(Actor actor, Map<String, Object> app) {
        return db.queryForList("select b.source::text as binding_source,b.session_source_id,"
                + "p.package_sha256,p.binding_status,p.resolved_mapping::text,"
                + "s.status as source_status,s.package_version_id as source_package_id,"
                + "s.package_sha256 as source_package_sha,s.plan_sha256,s.document_sha256,"
                + "s.resolved_source::text,s.snapshot::text,b.package_version_id"
                + " from app.capability_app_binding b join app.capability_package_version p"
                + " on p.tenant_id=b.tenant_id and p.id=b.package_version_id"
                + " left join app.onboarding_import_source s on s.tenant_id=b.tenant_id"
                + " and s.source_id=b.session_source_id"
                + " where b.tenant_id=? and b.app_id=? and b.app_code=? order by b.package_version_id",
                actor.tenantId(), app.get("id"), app.get("code"));
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        var out = new LinkedHashMap<String, Object>();
        for (var field : Map.ofEntries(Map.entry("sourceId", "source_id"),
                Map.entry("sessionId", "session_id"), Map.entry("planSha256", "plan_sha256"),
                Map.entry("packageVersionId", "package_version_id"), Map.entry("packageSha256", "package_sha256"),
                Map.entry("documentSha256", "document_sha256"), Map.entry("snapshotSha256", "snapshot_sha256"),
                Map.entry("status", "status")).entrySet()) out.put(field.getKey(), row.get(field.getValue()));
        for (var field : Map.of("frozenPlan", "frozen_plan", "snapshot", "snapshot", "resolvedSource", "resolved_source").entrySet())
            if (row.get(field.getValue()) != null) out.put(field.getKey(), object(row.get(field.getValue())));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return Json.read(value.toString(), Map.class); }
    private static PlatformException conflict(String message) {
        return new PlatformException(409, "ONBOARDING_SOURCE_CONFLICT", message);
    }
}
