package cn.agentplatform.app.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.agentplatform.app.application.AppService.error;

/**
 * PLAT-12 实现项 4：审计三元组 runId↔principalId↔credentialId。
 * 落在既有 app.lifecycle_log 上（V107 补 run_id/credential_id 可空列），不新建重复表。
 * run 引用是跨服务事实（engine.run.public_id），本服务只做形状与归属校验，不反查引擎可用性。
 */
@Service
public class AuditLinkService {
    private final JdbcTemplate db;

    public AuditLinkService(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 记录运行关联审计行。主体取自已验 Actor（principal=actor.principalId），不收 body 身份；
     * appCode/credentialId 引用不实即显式失败且不留半关联行；credentialId 可空（无凭据参与的运行）。
     * 事务边界：本方法整体原子，任一校验失败即整体回滚。
     */
    @Transactional
    public void recordRunLink(Actor actor, String appCode, String runId, String credentialId) {
        String credential = credentialId == null ? null : requireOwnCredential(actor.tenantId(), credentialId);
        if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,64}"))
            throw error(400, "INVALID_INPUT", "运行标识无效");
        boolean appExists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "select exists(select 1 from app.application where tenant_id=? and code=?)",
                                Boolean.class,
                                actor.tenantId(),
                                appCode));
        if (!appExists) throw error(404, "APP_NOT_FOUND", "应用不存在");
        db.update(
                "insert into app.lifecycle_log(tenant_id,app_code,actor,action,from_state,to_state,detail,run_id,credential_id)"
                        + " values(?,?,?,'RUN_ASSOCIATION','RUNNING','RUNNING',?::jsonb,?,?)",
                actor.tenantId(),
                appCode,
                actor.principalId(),
                Json.write(Map.of("runId", runId, "credentialId", String.valueOf(credential))),
                runId,
                credential);
    }

    /** 业务用户反查自己的运行；查他人属跨用户面，仅 ADMIN（默认拒绝）。 */
    public List<Map<String, Object>> runsByPrincipal(Actor caller, String principalId) {
        if (!caller.principalId().equals(principalId) && !caller.roles().contains("ADMIN"))
            throw error(403, "SYS-APP-4031", "只能查询本人的运行关联");
        return queryLinks(caller.tenantId(), "actor=?", principalId);
    }

    /** 凭据面覆盖多个业务用户，仅 ADMIN 可从凭据反查运行。 */
    public List<Map<String, Object>> runsByCredential(Actor caller, String credentialId) {
        if (!caller.roles().contains("ADMIN")) throw error(403, "SYS-APP-4031", "凭据关联查询仅限管理员");
        return queryLinks(caller.tenantId(), "credential_id=?", requireOwnCredential(caller.tenantId(), credentialId));
    }

    private List<Map<String, Object>> queryLinks(String tenantId, String predicate, Object value) {
        return db.query(
                "select run_id,actor,credential_id,app_code,action,created_at from app.lifecycle_log"
                        + " where tenant_id=? and "
                        + predicate
                        + " and run_id is not null order by id desc",
                (r, n) -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("runId", r.getString("run_id"));
                    row.put("principalId", r.getString("actor"));
                    row.put("credentialId", r.getString("credential_id"));
                    row.put("appCode", r.getString("app_code"));
                    row.put("action", r.getString("action"));
                    row.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
                    return row;
                },
                tenantId,
                value);
    }

    /** 关联校验：凭据必须真实存在于调用方租户；非数字或不存在都显式报错，绝不久账。 */
    private String requireOwnCredential(String tenantId, String credentialId) {
        long id;
        try {
            id = Long.parseLong(credentialId);
        } catch (NumberFormatException ex) {
            throw error(400, "INVALID_INPUT", "凭据标识无效");
        }
        boolean exists =
                Boolean.TRUE.equals(
                        db.queryForObject(
                                "select exists(select 1 from app.integration_credential where id=? and tenant_id=?)",
                                Boolean.class,
                                id,
                                tenantId));
        if (!exists) throw error(404, "INTEGRATION_NOT_FOUND", "集成凭据不存在");
        return Long.toString(id);
    }
}
