package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Monitor owns report snapshots only. All run/call totals are recomputed from authority
 * projections.
 */
@Service
public class StatisticsService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final String engineUrl, modelUrl;
    private final RestClient http;

    public StatisticsService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String modelUrl) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.engineUrl = engineUrl;
        this.modelUrl = modelUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        http = RestClient.builder().requestFactory(factory).build();
    }

    public Map<String, String> filters(Map<String, ?> request, boolean explicitWindow) {
        Actor.current().requireRole("ADMIN");
        var result = new TreeMap<String, String>();
        for (String key : List.of("from", "to", "appCode", "bizSystemCode", "callKind", "groupBy"))
            if (request.get(key) != null) {
                String value = request.get(key).toString();
                if (value.isBlank() || value.length() > 128) throw invalid();
                result.put(key, value);
            }
        if (explicitWindow && (!result.containsKey("from") || !result.containsKey("to")))
            throw invalid();
        try {
            Instant end =
                    result.containsKey("to") ? Instant.parse(result.get("to")) : Instant.now();
            Instant start =
                    result.containsKey("from")
                            ? Instant.parse(result.get("from"))
                            : end.minus(Duration.ofDays(30));
            if (!start.isBefore(end)
                    || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0)
                throw invalid();
            result.put("from", start.toString());
            result.put("to", end.toString());
        } catch (java.time.format.DateTimeParseException ex) {
            throw invalid();
        }
        result.putIfAbsent("groupBy", "day");
        if (!result.get("groupBy").equals("day")
                || result.containsKey("callKind")
                        && !Set.of("FORMAL", "DEBUG", "EVALUATION")
                                .contains(result.get("callKind"))) throw invalid();
        return result;
    }

    public Map<String, Object> query(Map<String, String> filters) {
        Actor.current().requireRole("ADMIN");
        var uri = UriComponentsBuilder.fromUriString(engineUrl + "/internal/statistics");
        filters.forEach(uri::queryParam);
        var engine = source("GET", uri.build().encode().toUriString(), null);
        var groups = rows(engine, "groups", 2000);
        var refs = rows(engine, "runRefs", 10000);
        var skillGroups = rows(engine, "skillGroups", 2000);
        var groupIndex = new LinkedHashMap<String, Map<String, Object>>();
        for (var group : groups) {
            String key = string(group, "groupKey");
            if (groupIndex.putIfAbsent(key, group) != null) throw sourceInvalid();
            group.put("model", emptyModel());
        }
        var refIndex = new LinkedHashMap<String, String>();
        for (var ref : refs) {
            String id = string(ref, "runId"), key = string(ref, "groupKey");
            if (!groupIndex.containsKey(key) || refIndex.putIfAbsent(id, key) != null)
                throw sourceInvalid();
        }
        var model =
                source(
                        "POST",
                        modelUrl + "/internal/statistics/query",
                        Map.of("runIds", new ArrayList<>(refIndex.keySet())));
        var seen = new HashSet<String>();
        for (var usage : rows(model, "byRun", 10000)) {
            String id = string(usage, "runId");
            if (!refIndex.containsKey(id) || !seen.add(id)) throw sourceInvalid();
            @SuppressWarnings("unchecked")
            var target = (Map<String, Object>) groupIndex.get(refIndex.get(id)).get("model");
            add(target, "calls", number(usage, "calls"));
            add(target, "usageMissingCalls", number(usage, "usageMissingCalls"));
            if (usage.get("knownTokens") != null) {
                add(target, "knownTokens", number(usage, "knownTokens"));
                target.put("hasKnownUsage", true);
            }
        }
        for (var group : groups) finishModel(cast(group.get("model")));
        var result = new LinkedHashMap<String, Object>();
        result.put("groups", groups);
        result.put("skillGroups", skillGroups);
        result.put("failures", rows(engine, "failures", 50));
        result.put("filters", filters);
        result.put("queryHash", Digest.sha256(Json.canonical(filters)));
        result.put(
                "sourceSnapshots",
                Map.of(
                        "engine",
                        string(engine, "snapshotAt"),
                        "model",
                        string(model, "snapshotAt")));
        result.put("consistency", "PER_SOURCE_SNAPSHOT");
        result.put("windowBasis", "RUN_STARTED_AT");
        result.put("timezone", "UTC");
        result.put("runGrain", "RUN");
        result.put("skillGrain", "LOGICAL_SKILL_CALL");
        result.put("modelGrain", "MODEL_SEND");
        result.put("confirmationGrain", "APPROVAL_DONE_EVENT");
        return result;
    }

    public Map<String, Object> rebuild(Map<String, Object> request) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        if (!Set.of("requestId", "from", "to", "appCode", "bizSystemCode", "callKind", "groupBy")
                .containsAll(request.keySet())) throw invalid();
        String requestId = Objects.toString(request.get("requestId"), "");
        if (!requestId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        var filter = filters(request, true);
        String hash = Digest.sha256(Json.canonical(filter));
        var existing =
                db.queryForList(
                        "select id,query_hash from monitor.statistics_snapshot where tenant_id=?"
                            + " and request_id=?",
                        actor.tenantId(),
                        requestId);
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.getFirst().get("query_hash"))) throw conflict();
            return snapshot(existing.getFirst().get("id").toString());
        }
        // Never hold a DB transaction while waiting for another service. Failure leaves active
        // unchanged.
        var report = query(filter);
        String id = UUID.randomUUID().toString();
        report.put("snapshotId", id);
        return tx.execute(
                status -> {
                    int inserted =
                            db.update(
                                    "insert into"
                                        + " monitor.statistics_snapshot(tenant_id,id,request_id,query_hash,filters,report,created_by)"
                                        + " values(?,?,?,?,?::jsonb,?::jsonb,?) on"
                                        + " conflict(tenant_id,request_id) do nothing",
                                    actor.tenantId(),
                                    id,
                                    requestId,
                                    hash,
                                    Json.write(filter),
                                    Json.write(report),
                                    actor.principalId());
                    if (inserted == 0) {
                        var winner =
                                db.queryForMap(
                                        "select id,query_hash from monitor.statistics_snapshot"
                                            + " where tenant_id=? and request_id=?",
                                        actor.tenantId(),
                                        requestId);
                        if (!hash.equals(winner.get("query_hash"))) throw conflict();
                        return snapshot(winner.get("id").toString());
                    }
                    db.update(
                            "insert into"
                                + " monitor.statistics_active(tenant_id,query_hash,snapshot_id)"
                                + " values(?,?,?) on conflict(tenant_id,query_hash) do update set"
                                + " snapshot_id=excluded.snapshot_id,updated_at=now()",
                            actor.tenantId(),
                            hash,
                            id);
                    return report;
                });
    }

    public Map<String, Object> snapshot(String id) {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        var result =
                db.queryForList(
                        "select report::text from monitor.statistics_snapshot where tenant_id=? and"
                            + " id=?",
                        String.class,
                        actor.tenantId(),
                        id);
        if (result.isEmpty())
            throw new PlatformException(404, "STATISTICS_NOT_FOUND", "统计快照不存在或不在当前租户");
        return Json.read(result.getFirst(), Map.class);
    }

    public List<Map<String, Object>> snapshots() {
        Actor actor = Actor.current();
        actor.requireRole("ADMIN");
        var result =
                db.queryForList(
                        "select s.id as \"snapshotId\",s.query_hash as"
                            + " \"queryHash\",s.filters,s.created_at as"
                            + " \"createdAt\",(a.snapshot_id=s.id) as active from"
                            + " monitor.statistics_snapshot s left join monitor.statistics_active a"
                            + " on a.tenant_id=s.tenant_id and a.query_hash=s.query_hash where"
                            + " s.tenant_id=? order by s.created_at desc,s.id limit 100",
                        actor.tenantId());
        result.forEach(r -> r.put("filters", Json.read(r.get("filters").toString())));
        return result;
    }

    public Map<String, Object> drilldown(String id) {
        Actor.current().requireRole("ADMIN");
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw invalid();
        return source("GET", engineUrl + "/internal/statistics/runs/" + id, null);
    }

    public static void validatePage(int page, int pageSize) {
        if (page < 1 || page > 2000 || pageSize < 1 || pageSize > 100) throw invalid();
    }

    public static Map<String, Object> page(Map<String, Object> report, int page, int pageSize) {
        validatePage(page, pageSize);
        var result = new LinkedHashMap<>(report);
        for (String key : List.of("groups", "skillGroups")) {
            var rows = (List<?>) report.get(key);
            int start = Math.min(rows.size(), (page - 1) * pageSize);
            result.put(
                    key,
                    new ArrayList<>(rows.subList(start, Math.min(rows.size(), start + pageSize))));
            result.put(key + "Total", rows.size());
        }
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    private Map<String, Object> source(String method, String url, Map<String, Object> body) {
        try {
            RestClient.RequestHeadersSpec<?> request;
            if (method.equals("GET"))
                request =
                        http.get()
                                .uri(url)
                                .header(
                                        "Authorization",
                                        "Bearer " + ServiceTokens.issue(Actor.current()));
            else
                request =
                        http.post()
                                .uri(url)
                                .header(
                                        "Authorization",
                                        "Bearer " + ServiceTokens.issue(Actor.current()))
                                .body(body);
            return request.exchange(
                    (req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status >= 400) {
                            if (status == 422)
                                throw new PlatformException(
                                        422, "STATISTICS_LIMIT", "统计范围过大，请缩短时间或选择应用、业务系统、调用模式");
                            if (status == 404)
                                throw new PlatformException(
                                        404, "STATISTICS_NOT_FOUND", "运行不存在或不在当前租户");
                            throw new PlatformException(
                                    502, "STATISTICS_SOURCE_UNAVAILABLE", "统计来源暂不可用，请稍后重试");
                        }
                        byte[] bytes = res.getBody().readNBytes(8 * 1024 * 1024 + 1);
                        if (bytes.length > 8 * 1024 * 1024)
                            throw new PlatformException(422, "STATISTICS_LIMIT", "统计结果过大，请缩小查询范围");
                        var envelope = Json.read(new String(bytes, StandardCharsets.UTF_8));
                        if (!"OK".equals(envelope.path("code").asText())
                                || !envelope.path("data").isObject()) throw sourceInvalid();
                        return Json.read(envelope.path("data").toString(), Map.class);
                    });
        } catch (PlatformException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PlatformException(502, "STATISTICS_SOURCE_UNAVAILABLE", "统计来源暂不可用，请稍后重试");
        }
    }

    private static Map<String, Object> emptyModel() {
        var m = new LinkedHashMap<String, Object>();
        m.put("calls", 0L);
        m.put("usageMissingCalls", 0L);
        m.put("knownTokens", 0L);
        m.put("hasKnownUsage", false);
        return m;
    }

    private static void finishModel(Map<String, Object> m) {
        boolean known = Boolean.TRUE.equals(m.remove("hasKnownUsage"));
        if (!known && number(m, "calls") > 0) m.put("knownTokens", null);
        m.put("totalTokens", number(m, "usageMissingCalls") > 0 ? null : m.get("knownTokens"));
    }

    private static void add(Map<String, Object> m, String key, long n) {
        try {
            m.put(key, Math.addExact(number(m, key), n));
        } catch (ArithmeticException ex) {
            throw sourceInvalid();
        }
    }

    private static long number(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Number n) || n.longValue() < 0) throw sourceInvalid();
        return n.longValue();
    }

    private static String string(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank() || s.length() > 1024) throw sourceInvalid();
        return s;
    }

    private static Map<String, Object> cast(Object value) {
        if (!(value instanceof Map<?, ?>)) throw sourceInvalid();
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> rows(
            Map<String, Object> source, String key, int limit) {
        if (!(source.get(key) instanceof List<?> list)
                || list.size() > limit
                || list.stream().anyMatch(v -> !(v instanceof Map))) throw sourceInvalid();
        return list.stream()
                .map(v -> new LinkedHashMap<>(cast(v)))
                .map(v -> (Map<String, Object>) v)
                .toList();
    }

    private static PlatformException invalid() {
        return new PlatformException(
                400,
                "INVALID_STATISTICS_QUERY",
                "统计需要有效时间（最长366天）、groupBy=day；分页每页1至100条，重建还需requestId和明确from/to");
    }

    private static PlatformException sourceInvalid() {
        return new PlatformException(502, "STATISTICS_SOURCE_INVALID", "统计来源数据不完整，请稍后重试");
    }

    private static PlatformException conflict() {
        return new PlatformException(409, "STATISTICS_REQUEST_REUSED", "重建请求号已用于其他筛选条件，请重新生成请求号");
    }
}
