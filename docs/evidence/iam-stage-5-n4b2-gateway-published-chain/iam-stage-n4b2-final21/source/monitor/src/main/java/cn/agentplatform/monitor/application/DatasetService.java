package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * M4 single-case promotion. Cases retain EvaluationService's exact id/inputs/expected/safety shape.
 */
@Service
public class DatasetService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public DatasetService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
    }

    private Actor actor(boolean internal) {
        var a = Actor.current();
        if (!a.roles().contains("EDITOR") || (internal && !a.roles().contains("SERVICE")))
            throw fail(403, "DATASET_EDITOR_REQUIRED", "数据集操作需要编排者身份和有效调用范围");
        return a;
    }

    private static PlatformException fail(int status, String code, String detail) {
        return new PlatformException(status, code, detail);
    }

    private static String text(JsonNode b, String key) {
        var v = b.path(key);
        if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > 128)
            throw fail(400, "INVALID_DATASET", "缺少或无效字段: " + key);
        return v.asText();
    }

    private static void fields(JsonNode b, Set<String> keys) {
        if (!b.isObject()) throw fail(400, "INVALID_DATASET", "必须为JSON对象");
        b.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!keys.contains(k))
                                throw fail(400, "INVALID_DATASET", "不支持字段: " + k);
                        });
    }

    private static void positive(JsonNode b, String key) {
        var v = b.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 1)
            throw fail(400, "INVALID_DATASET", "来源ID与修订号必须为正整数");
    }

    private static void bounds(JsonNode v, int depth) {
        if (depth > 20) throw fail(400, "DATASET_DEPTH_LIMIT", "数据集JSON层级超过上限");
        if (v.isContainerNode()) for (var child : v) bounds(child, depth + 1);
    }

    private static void route(String code, String version) {
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
            throw fail(400, "INVALID_DATASET", "应用编码或数据集版本无效");
    }

    public Map<String, Object> promote(JsonNode b) {
        var a = actor(true);
        fields(b, Set.of("appCode", "datasetVersion", "requestId", "source", "cases"));
        bounds(b, 0);
        if (Json.write(b).getBytes(StandardCharsets.UTF_8).length > 140000)
            throw fail(413, "DATASET_SIZE_LIMIT", "数据集内容超过上限");
        String code = text(b, "appCode"),
                version = text(b, "datasetVersion"),
                request = text(b, "requestId");
        route(code, version);
        JsonNode source = b.path("source"), cases = b.path("cases");
        fields(source, Set.of("inputId", "revision", "appDraftId", "inputsSha256"));
        for (String key : List.of("inputId", "revision", "appDraftId")) positive(source, key);
        if (!cases.isArray() || cases.size() != 1)
            throw fail(400, "INVALID_DATASET", "提升必须包含一个明确用例");
        var c = cases.get(0);
        fields(c, Set.of("id", "inputs", "expected", "safety"));
        text(c, "id");
        if (!c.path("inputs").isObject() || !c.has("expected") || !c.path("safety").isBoolean())
            throw fail(400, "INVALID_DATASET", "用例必须包含inputs对象、显式expected和safety布尔值");
        if (!Digest.sha256(Json.canonical(c.path("inputs"))).equals(text(source, "inputsSha256")))
            throw fail(400, "SOURCE_DIGEST_MISMATCH", "来源输入摘要不匹配");
        String digest = Digest.sha256(Json.canonical(b));
        // PostgreSQL ON CONFLICT serializes claims of either version or request ID. Re-read and
        // compare the full content inside this short local transaction, never replace a version.
        return tx.execute(
                s -> {
                    db.update(
                            "insert into"
                                + " monitor.dataset(tenant_id,app_code,dataset_version,cases,sha256,promoted_by,promotion_request_id,promotion_sha256,source)"
                                + " values(?,?,?,?::jsonb,?,?,?,?,?::jsonb) on conflict do nothing",
                            a.tenantId(),
                            code,
                            version,
                            Json.write(cases),
                            Digest.sha256(Json.canonical(cases)),
                            a.principalId(),
                            request,
                            digest,
                            Json.write(source));
                    var rows =
                            db.queryForList(
                                    "select promotion_sha256,promoted_by from monitor.dataset where"
                                        + " tenant_id=? and app_code=? and dataset_version=?",
                                    a.tenantId(),
                                    code,
                                    version);
                    if (rows.isEmpty()
                            || !digest.equals(rows.getFirst().get("promotion_sha256"))
                            || !a.principalId().equals(rows.getFirst().get("promoted_by")))
                        throw fail(409, "DATASET_IMMUTABLE", "数据集版本或requestId已用于其他内容，请使用新标识");
                    return get(code, version);
                });
    }

    private Map<String, Object> row(ResultSet r, int n) throws SQLException {
        var v = new LinkedHashMap<String, Object>();
        v.put("appCode", r.getString("app_code"));
        v.put("datasetVersion", r.getString("dataset_version"));
        v.put("cases", Json.read(r.getString("cases")));
        v.put("sha256", r.getString("sha256"));
        v.put("source", Json.read(r.getString("source")));
        v.put("requestId", r.getString("promotion_request_id"));
        v.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
        return v;
    }

    public Map<String, Object> get(String code, String version) {
        var a = actor(false);
        route(code, version);
        var rows =
                db.query(
                        "select * from monitor.dataset where tenant_id=? and promoted_by=? and"
                            + " app_code=? and dataset_version=?",
                        this::row,
                        a.tenantId(),
                        a.principalId(),
                        code,
                        version);
        if (rows.isEmpty()) throw fail(404, "DATASET_NOT_FOUND", "数据集不存在");
        return rows.getFirst();
    }

    public Map<String, Object> list(String code, String afterVersion, int limit) {
        var a = actor(false);
        route(code, "v");
        if (limit < 1 || limit > 100 || afterVersion.length() > 128)
            throw fail(400, "INVALID_PAGE", "分页参数无效");
        var rows =
                db.query(
                        "select * from monitor.dataset where tenant_id=? and promoted_by=? and"
                            + " app_code=? and dataset_version>? order by dataset_version limit ?",
                        this::row,
                        a.tenantId(),
                        a.principalId(),
                        code,
                        afterVersion,
                        limit + 1);
        boolean more = rows.size() > limit;
        var page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        page.forEach(v -> v.remove("cases"));
        return Map.of(
                "items",
                page,
                "hasMore",
                more,
                "nextAfterVersion",
                page.isEmpty() ? afterVersion : page.getLast().get("datasetVersion"));
    }
}
