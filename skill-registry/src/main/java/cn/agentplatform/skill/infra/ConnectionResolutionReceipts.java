package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受控连接解析回执：每次向 app 解析连接档案都留一行。
 *
 * 只记事实（谁、为哪个技能、解析了哪一版、摘要、授权用途、时间），不记凭据，也不缓存解析结果——
 * 回执用于事后回答"当时用的是哪份配置"，不用于加速下一次调用；缓存一旦跨请求复用，
 * 撤权就无法在下一次调用生效。
 */
@Repository
public class ConnectionResolutionReceipts {

    private final JdbcTemplate db;

    public ConnectionResolutionReceipts(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> record(Actor actor, String skillCode, Map<String, Object> facts, String environment) {
        return record(actor, skillCode, facts, environment, null);
    }

    /**
     * INT-02 子步3：带调用范围的解析回执。
     *
     * scope 可空（子步2 路径不变）；非空时给出 runPublicId / callId / attemptId / operation，
     * 让回执能与引擎的 engine.tool_call 对齐——这是事后回答"这次调用连的是哪个端点"的唯一凭据。
     */
    public Map<String, Object> record(Actor actor, String skillCode, Map<String, Object> facts,
            String environment, Map<String, Object> scope) {
        String digest = String.valueOf(facts.get("resolutionDigest"));
        String authKind = String.valueOf(facts.get("authKind"));
        Object uses = facts.getOrDefault("grantedUses", java.util.List.of());
        Map<String, Object> call = scope == null ? Map.of() : scope;
        var rows = db.query("insert into skill.connection_resolution"
                        + " (tenant_id,skill_code,profile_id,profile_rev,environment,resolution_digest,auth_kind,"
                        + " granted_uses,requested_by,run_public_id,call_id,attempt_id,operation,execution_binding)"
                        + " values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?::jsonb) returning id,resolved_at::text",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("receiptId", rs.getLong(1));
                    m.put("resolvedAt", rs.getString(2));
                    return m;
                },
                actor.tenantId(), skillCode, String.valueOf(facts.get("profileId")),
                ((Number) facts.get("profileRev")).intValue(), environment, digest, authKind,
                Json.write(uses), actor.principalId(),
                text(call, "runPublicId"), number(call, "callId"), number(call, "attemptId"),
                text(call, "operation"), call.get("executionBinding") == null ? null : Json.write(call.get("executionBinding")));
        Map<String, Object> out = new LinkedHashMap<>(facts);
        out.putAll(rows.get(0));
        return out;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static Long number(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }
}
