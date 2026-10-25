package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.domain.*;
import cn.agentplatform.engine.infra.TestInputClient;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * §30.1.7/4.2/5.4: named complete input values, owner isolation, revision CAS and durable
 * promotion.
 */
@Service
public class TestInputService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final TestInputClient client;

    public TestInputService(
            JdbcTemplate db, PlatformTransactionManager manager, TestInputClient client) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.client = client;
    }

    private Actor actor() {
        var a = Actor.current();
        if (!a.roles().contains("EDITOR"))
            throw fail(403, "TEST_INPUT_EDITOR_REQUIRED", "测试输入仅限编排者使用");
        return a;
    }

    private static PlatformException fail(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }

    private static void fields(JsonNode b, Set<String> keys) {
        if (!b.isObject()) throw fail(400, "INVALID_INPUT", "请求必须为对象");
        b.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!keys.contains(k)) throw fail(400, "INVALID_INPUT", "不支持字段: " + k);
                        });
    }

    private static String text(JsonNode b, String key) {
        var v = b.path(key);
        if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > 128)
            throw fail(400, "INVALID_INPUT", "缺少或无效字段: " + key);
        return v.asText();
    }

    private static long positive(JsonNode b, String key) {
        var v = b.path(key);
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 1)
            throw fail(400, "INVALID_INPUT", "无效修订号: " + key);
        return v.asLong();
    }

    private static void bounds(JsonNode v, int depth) {
        if (depth > 16) throw fail(400, "INPUT_DEPTH_LIMIT", "JSON层级超过16层");
        if (v.isContainerNode()) for (var child : v) bounds(child, depth + 1);
    }

    private static void size(JsonNode v) {
        bounds(v, 0);
        if (Json.write(v).getBytes(StandardCharsets.UTF_8).length > 65536)
            throw fail(413, "INPUT_SIZE_LIMIT", "JSON超过64KiB");
    }

    private JsonNode draft(long id, String code, Actor actor) {
        if (id < 1) throw fail(400, "INVALID_INPUT", "草稿ID无效");
        var d = client.draft(id, actor);
        if (d.path("draftId").asLong() != id
                || (code != null && !code.equals(d.path("appCode").asText())))
            throw fail(409, "DRAFT_CHANGED", "草稿归属已变更");
        if (!Set.of("DRAFT", "PUBLISHED").contains(d.path("status").asText()))
            throw fail(409, "DRAFT_UNAVAILABLE", "当前草稿不可使用");
        return d;
    }

    private void validate(JsonNode d, JsonNode inputs) {
        if (!inputs.isObject()) throw fail(400, "INVALID_INPUT", "inputs必须为完整变量对象");
        size(inputs);
        InputDeclarations.validate(d, inputs);
    }

    private Map<String, Object> row(ResultSet r, int n) throws SQLException {
        var v = new LinkedHashMap<String, Object>();
        v.put("id", r.getLong("id"));
        v.put("appDraftId", r.getLong("app_draft_id"));
        v.put("appCode", r.getString("app_code"));
        v.put("name", r.getString("name"));
        v.put("inputs", Json.read(r.getString("inputs")));
        v.put("revision", r.getLong("revision"));
        v.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
        v.put("updatedAt", r.getTimestamp("updated_at").toInstant().toString());
        return v;
    }

    private Map<String, Object> stored(long id, Actor a) {
        var rows =
                db.query(
                        "select * from engine.debug_input where tenant_id=? and created_by=? and"
                                + " id=?",
                        this::row,
                        a.tenantId(),
                        a.principalId(),
                        id);
        if (rows.isEmpty()) throw fail(404, "TEST_INPUT_NOT_FOUND", "测试输入不存在");
        return rows.getFirst();
    }

    public Map<String, Object> create(long draftId, JsonNode body) {
        var a = actor();
        fields(body, Set.of("name", "inputs"));
        String name = text(body, "name");
        var d = draft(draftId, null, a);
        validate(d, body.path("inputs"));
        return db.query(
                        "insert into"
                            + " engine.debug_input(tenant_id,app_draft_id,app_code,name,inputs,created_by,updated_by)"
                            + " values(?,?,?,?,?::jsonb,?,?) returning *",
                        this::row,
                        a.tenantId(),
                        draftId,
                        d.path("appCode").asText(),
                        name,
                        Json.write(body.path("inputs")),
                        a.principalId(),
                        a.principalId())
                .getFirst();
    }

    public Map<String, Object> get(long id) {
        var a = actor();
        var v = stored(id, a);
        validate(
                draft(((Number) v.get("appDraftId")).longValue(), v.get("appCode").toString(), a),
                Json.tree(v.get("inputs")));
        return v;
    }

    public Map<String, Object> list(long draftId, long afterId, int limit) {
        var a = actor();
        if (afterId < 0 || limit < 1 || limit > 100)
            throw fail(400, "INVALID_PAGE", "分页limit须1至100，afterId不得为负");
        draft(draftId, null, a);
        // List metadata only: stale values remain discoverable for correction; detail/reuse
        // revalidates them.
        var rows =
                db.query(
                        "select * from engine.debug_input where tenant_id=? and created_by=? and"
                                + " app_draft_id=? and id>? order by id limit ?",
                        this::row,
                        a.tenantId(),
                        a.principalId(),
                        draftId,
                        afterId,
                        limit + 1);
        boolean more = rows.size() > limit;
        var page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        page.forEach(r -> r.remove("inputs"));
        return Map.of(
                "items",
                page,
                "hasMore",
                more,
                "nextAfterId",
                page.isEmpty() ? afterId : page.getLast().get("id"));
    }

    public Map<String, Object> update(long id, JsonNode body) {
        var a = actor();
        fields(body, Set.of("revision", "name", "inputs"));
        long rev = positive(body, "revision");
        String name = text(body, "name");
        var old = stored(id, a);
        validate(
                draft(
                        ((Number) old.get("appDraftId")).longValue(),
                        old.get("appCode").toString(),
                        a),
                body.path("inputs"));
        var rows =
                db.query(
                        "update engine.debug_input set"
                            + " name=?,inputs=?::jsonb,revision=revision+1,updated_by=?,updated_at=now()"
                            + " where tenant_id=? and created_by=? and id=? and revision=?"
                            + " returning *",
                        this::row,
                        name,
                        Json.write(body.path("inputs")),
                        a.principalId(),
                        a.tenantId(),
                        a.principalId(),
                        id,
                        rev);
        if (rows.isEmpty()) throw fail(409, "TEST_INPUT_REVISION_CONFLICT", "输入已变更，请刷新后重试");
        return rows.getFirst();
    }

    public void delete(long id, long revision) {
        var a = actor();
        if (revision < 1) throw fail(400, "INVALID_INPUT", "修订号无效");
        var old = stored(id, a);
        draft(((Number) old.get("appDraftId")).longValue(), old.get("appCode").toString(), a);
        if (db.update(
                        "delete from engine.debug_input where tenant_id=? and created_by=? and id=?"
                                + " and revision=?",
                        a.tenantId(),
                        a.principalId(),
                        id,
                        revision)
                != 1) throw fail(409, "TEST_INPUT_REVISION_CONFLICT", "输入已变更，请刷新后重试");
    }

    public JsonNode promote(long id, JsonNode body) {
        var a = actor();
        fields(
                body,
                Set.of("requestId", "revision", "datasetVersion", "caseId", "expected", "safety"));
        String request = text(body, "requestId"),
                version = text(body, "datasetVersion"),
                caseId = text(body, "caseId");
        long rev = positive(body, "revision");
        if (!body.has("expected") || !body.path("safety").isBoolean())
            throw fail(400, "EXPECTED_REQUIRED", "请显式填写expected及safety布尔值");
        size(body);
        String digest = Digest.sha256(Json.canonical(Map.of("inputId", id, "request", body)));
        var previous = promotion(a, request);
        if (previous != null) {
            check(previous, digest);
            var payload = Json.read(previous.get("payload").toString());
            draft(
                    payload.path("source").path("appDraftId").asLong(),
                    payload.path("appCode").asText(),
                    a);
            return client.promote(payload, a);
        }
        var input = stored(id, a);
        long draftId = ((Number) input.get("appDraftId")).longValue();
        String code = input.get("appCode").toString();
        var d = draft(draftId, code, a);
        if (((Number) input.get("revision")).longValue() != rev)
            throw fail(409, "TEST_INPUT_REVISION_CONFLICT", "待提升的输入修订号已变更");
        validate(d, Json.tree(input.get("inputs")));
        var payload =
                Json.tree(
                        Map.of(
                                "requestId",
                                request,
                                "appCode",
                                code,
                                "datasetVersion",
                                version,
                                "source",
                                Map.of(
                                        "inputId",
                                        id,
                                        "revision",
                                        rev,
                                        "appDraftId",
                                        draftId,
                                        "inputsSha256",
                                        Digest.sha256(Json.canonical(input.get("inputs")))),
                                "cases",
                                List.of(
                                        Map.of(
                                                "id",
                                                caseId,
                                                "inputs",
                                                input.get("inputs"),
                                                "expected",
                                                body.path("expected"),
                                                "safety",
                                                body.path("safety").asBoolean()))));
        // Optimistic PG snapshot: INSERT ... SELECT verifies revision at the same statement
        // snapshot.
        // PostgreSQL ON CONFLICT arbitrates parallel retries; neither local transaction spans HTTP.
        var frozen =
                tx.execute(
                        s -> {
                            db.update(
                                    "insert into"
                                        + " engine.debug_input_promotion(tenant_id,created_by,request_id,input_id,app_draft_id,app_code,request_sha256,payload)"
                                        + " select"
                                        + " tenant_id,created_by,?,id,app_draft_id,app_code,?,?::jsonb"
                                        + " from engine.debug_input where tenant_id=? and"
                                        + " created_by=? and id=? and revision=? on conflict do"
                                        + " nothing",
                                    request,
                                    digest,
                                    Json.write(payload),
                                    a.tenantId(),
                                    a.principalId(),
                                    id,
                                    rev);
                            var stored = promotion(a, request);
                            if (stored == null)
                                throw fail(409, "TEST_INPUT_REVISION_CONFLICT", "输入在提升期间已变更");
                            check(stored, digest);
                            return Json.read(stored.get("payload").toString());
                        });
        return client.promote(frozen, a);
    }

    private Map<String, Object> promotion(Actor a, String request) {
        var rows =
                db.queryForList(
                        "select request_sha256,payload::text from engine.debug_input_promotion"
                                + " where tenant_id=? and created_by=? and request_id=?",
                        a.tenantId(),
                        a.principalId(),
                        request);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void check(Map<String, Object> prior, String digest) {
        if (!digest.equals(prior.get("request_sha256")))
            throw fail(409, "PROMOTION_REQUEST_CONFLICT", "相同requestId不能用于不同提升内容");
    }
}
