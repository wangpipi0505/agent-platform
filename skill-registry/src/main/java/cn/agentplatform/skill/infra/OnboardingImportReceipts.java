package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PG 中的不可变导入事实；不以进程缓存承担恢复或执行授权。 */
@Repository
public class OnboardingImportReceipts {
    public record ImportReceipt(
            long receiptId,
            String principalId,
            Map<String, Object> source,
            String sourceSha256,
            String requestSha256,
            List<Map<String, Object>> bindingRefs,
            String operationId,
            String code,
            int version,
            long skillVersionId,
            String sha256,
            Map<String, Object> authority) {}

    private final JdbcTemplate db;

    public OnboardingImportReceipts(JdbcTemplate db) {
        this.db = db;
    }

    /** 与导入共用事务的计划锁；相同计划的首次并发导入只能有一个创建者。 */
    public void lockPlan(Actor actor, String sessionId, String planSha256) {
        String key = Digest.sha256(Json.canonical(List.of(actor.tenantId(), sessionId, planSha256)));
        long lockId = Long.parseUnsignedLong(key.substring(0, 16), 16);
        db.queryForList("select pg_advisory_xact_lock(?)", lockId);
    }

    /** 不按 principal 过滤，以便将同租户冒用他人 session 的请求明确拒绝。 */
    public List<ImportReceipt> findPlan(Actor actor, String sessionId, String planSha256) {
        return db.query(
                "select * from skill.onboarding_skill_import_receipt"
                        + " where tenant_id=? and session_id=? and plan_sha256=? order by id",
                this::row,
                actor.tenantId(), sessionId, planSha256);
    }

    /** 仅供同服务治理逻辑读取；执行方仍须核对当前合同摘要并重新解析当前连接授权。 */
    public Optional<ImportReceipt> findBySkillVersion(Actor actor, long skillVersionId) {
        var rows = db.query(
                "select * from skill.onboarding_skill_import_receipt"
                        + " where tenant_id=? and principal_id=? and skill_version_id=?",
                this::row,
                actor.tenantId(), actor.principalId(), skillVersionId);
        return rows.stream().findFirst();
    }

    /** 只返回存在性；不能把他人导入来源误判成普通技能，从而落回无固定引用的旧路径。 */
    public boolean hasSkillVersion(Actor actor, long skillVersionId) {
        return Boolean.TRUE.equals(db.queryForObject(
                "select exists(select 1 from skill.onboarding_skill_import_receipt"
                        + " where tenant_id=? and skill_version_id=?)",
                Boolean.class,
                actor.tenantId(), skillVersionId));
    }

    /** 锁顺序与 SkillCatalog 一致：先 skill，再精确 version，避免回放与人工编辑互相穿透。 */
    public void lockSkill(Actor actor, ImportReceipt receipt) {
        db.queryForList(
                "select id from skill.skill where tenant_id=? and code=? for update",
                actor.tenantId(), receipt.code());
        db.queryForList(
                "select id from skill.skill_version where tenant_id=? and id=? for update",
                actor.tenantId(), receipt.skillVersionId());
    }

    public void insert(
            Actor actor,
            Map<String, Object> source,
            String sourceSha256,
            String requestSha256,
            List<Map<String, Object>> bindingRefs,
            String operationId,
            Map<String, Object> version,
            Map<String, Object> authority) {
        db.update(
                "insert into skill.onboarding_skill_import_receipt"
                        + "(tenant_id,principal_id,session_id,plan_sha256,operation_id,source,"
                        + "source_sha256,request_sha256,binding_refs,skill_code,skill_version_no,"
                        + "skill_version_id,skill_sha256,authority)"
                        + " values(?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?,?,?::jsonb)",
                actor.tenantId(), actor.principalId(), source.get("sessionId"), source.get("planSha256"),
                operationId, Json.write(source), sourceSha256, requestSha256, Json.write(bindingRefs),
                version.get("code"), version.get("version"), version.get("skillVersionId"),
                version.get("sha256"), Json.write(authority));
    }

    @SuppressWarnings("unchecked")
    private ImportReceipt row(ResultSet row, int ignored) throws SQLException {
        return new ImportReceipt(
                row.getLong("id"),
                row.getString("principal_id"),
                Json.read(row.getString("source"), Map.class),
                row.getString("source_sha256"),
                row.getString("request_sha256"),
                (List<Map<String, Object>>) (List<?>) Json.read(row.getString("binding_refs"), List.class),
                row.getString("operation_id"),
                row.getString("skill_code"),
                row.getInt("skill_version_no"),
                row.getLong("skill_version_id"),
                row.getString("skill_sha256"),
                Json.read(row.getString("authority"), Map.class));
    }
}
