package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.domain.EvaluationRules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.util.*;

@Service
public class EvaluationService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RestClient http = client();
    private final String appUrl, engineUrl;

    public EvaluationService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.appUrl = appUrl;
        this.engineUrl = engineUrl;
    }

    private static RestClient client() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(60000);
        return RestClient.builder().requestFactory(factory).build();
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    private PlatformException fail(int status, String code, String detail) {
        return new PlatformException(status, code, detail);
    }

    private String required(Map<String, Object> b, String key) {
        String value = Objects.toString(b.get(key), "");
        if (value.isBlank() || value.length() > 128)
            throw fail(400, "INVALID_INPUT", "缺少或无效字段: " + key);
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> obj(Object v) {
        if (!(v instanceof Map)) throw fail(400, "INVALID_INPUT", "必须为JSON对象");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cases(Object v) {
        if (!(v instanceof List<?> list)
                || list.isEmpty()
                || list.size() > 200
                || list.stream().anyMatch(x -> !(x instanceof Map)))
            throw fail(400, "INVALID_INPUT", "cases须包含1至200条用例");
        return new ArrayList<>((List<Map<String, Object>>) v);
    }

    public Map<String, Object> call(String method, String url, Map<String, Object> body) {
        return obj(callRaw(method, url, body));
    }

    private Object callRaw(String method, String url, Map<String, Object> body) {
        try {
            Map<?, ?> raw;
            if (method.equals("GET"))
                raw =
                        http.get()
                                .uri(url)
                                .header(
                                        "Authorization",
                                        "Bearer " + ServiceTokens.issue(Actor.current()))
                                .retrieve()
                                .body(Map.class);
            else
                raw =
                        http.post()
                                .uri(url)
                                .header(
                                        "Authorization",
                                        "Bearer " + ServiceTokens.issue(Actor.current()))
                                .body(body)
                                .retrieve()
                                .body(Map.class);
            if (raw == null) throw new IllegalStateException("空响应");
            return raw.containsKey("data") ? raw.get("data") : raw;
        } catch (Exception e) {
            throw fail(502, "DEPENDENCY_UNAVAILABLE", "依赖服务调用失败，稍后重试");
        }
    }

    public Map<String, Object> evaluate(Map<String, Object> b) {
        String code = required(b, "appCode"),
                hash = required(b, "candidateSha256"),
                dataset = required(b, "datasetVersion");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || !hash.matches("[a-f0-9]{64}")
                || !(b.get("draftRev") instanceof Number rev)
                || rev.longValue() < 1) throw fail(400, "INVALID_INPUT", "候选标识无效");
        var suite = new ArrayList<>(cases(b.get("cases")));
        var ids = new HashSet<String>();
        boolean safety = false;
        for (int i = 0; i < suite.size(); i++) {
            var c = new LinkedHashMap<>(suite.get(i));
            String id = Objects.toString(c.getOrDefault("id", Integer.toString(i + 1)), "");
            if (id.isBlank()
                    || !ids.add(id)
                    || !c.containsKey("expected")
                    || !(c.get("inputs") instanceof Map)
                    || !(c.get("safety") instanceof Boolean))
                throw fail(400, "INVALID_INPUT", "用例需唯一id、inputs、expected及safety布尔值");
            c.put("id", id);
            suite.set(i, c);
            safety |= Boolean.TRUE.equals(c.get("safety"));
        }
        if (!safety) throw fail(400, "SAFETY_CASE_REQUIRED", "必须包含安全用例");
        double threshold = b.get("threshold") instanceof Number n ? n.doubleValue() : 0.85;
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1)
            throw fail(400, "INVALID_THRESHOLD", "通过率阈值应大于0且不超过1");
        var candidate =
                call(
                        "GET",
                        appUrl
                                + "/internal/apps/"
                                + code
                                + "/candidate?draftRev="
                                + rev.longValue()
                                + "&candidateSha256="
                                + hash,
                        Map.of());
        if (!hash.equals(candidate.get("candidateSha256"))
                || !(candidate.get("draftRev") instanceof Number candidateRev)
                || candidateRev.longValue() != rev.longValue())
            throw fail(412, "CANDIDATE_CHANGED", "候选已变更，请重新加载");
        String id = "er_" + UUID.randomUUID();
        String baseline = Objects.toString(b.get("baselineReportId"), "");
        var baselinePassed = new HashSet<String>();
        var previous =
                db.queryForList(
                        "select id from monitor.evaluation_report where tenant_id=? and app_code=?"
                            + " and dataset_version=? and status='ACCEPTED' order by completed_at"
                            + " desc limit 1",
                        tenant(),
                        code,
                        dataset);
        String latest = previous.isEmpty() ? "" : previous.getFirst().get("id").toString();
        if (!baseline.isEmpty() && !baseline.equals(latest))
            throw fail(400, "BASELINE_INVALID", "必须使用该应用与数据集的最近通过报告作为基线");
        baseline = latest;
        if (!baseline.isEmpty())
            for (var result : cases(report(baseline).get("results")))
                if (Boolean.TRUE.equals(result.get("passed")))
                    baselinePassed.add(Objects.toString(result.get("id")));
        var frozenCases = List.copyOf(suite);
        String baselineId = baseline;
        // 发起外部执行前持久化候选和不可变数据集。进程崩溃只留下RUNNING，不会把缺失结果当成通过。
        tx.executeWithoutResult(
                s -> {
                    db.update(
                            "insert into"
                                + " monitor.dataset(tenant_id,app_code,dataset_version,cases,sha256)"
                                + " values(?,?,?,?::jsonb,?) on conflict do nothing",
                            tenant(),
                            code,
                            dataset,
                            Json.write(frozenCases),
                            Digest.sha256(Json.write(frozenCases)));
                    String stored =
                            db.queryForObject(
                                    "select cases::text from monitor.dataset where tenant_id=? and"
                                            + " app_code=? and dataset_version=?",
                                    String.class,
                                    tenant(),
                                    code,
                                    dataset);
                    if (!Json.read(stored).equals(Json.tree(frozenCases)))
                        throw fail(409, "DATASET_IMMUTABLE", "数据集版本已存在且内容不同，请使用新版本号");
                    db.update(
                            "insert into"
                                + " monitor.evaluation_report(tenant_id,id,app_code,draft_rev,candidate_sha256,dataset_version,candidate,status,threshold,baseline_report_id,created_by)"
                                + " values(?,?,?,?,?,?,?::jsonb,'RUNNING',?,?,?)",
                            tenant(),
                            id,
                            code,
                            rev.longValue(),
                            hash,
                            dataset,
                            Json.write(candidate),
                            threshold,
                            baselineId,
                            Actor.current().principalId());
                });
        return execute(
                id,
                code,
                rev.longValue(),
                hash,
                dataset,
                candidate,
                frozenCases,
                threshold,
                baselinePassed);
    }

    private Map<String, Object> execute(
            String id,
            String code,
            long rev,
            String hash,
            String dataset,
            Map<String, Object> candidate,
            List<Map<String, Object>> frozenCases,
            double threshold,
            Set<String> baselinePassed) {
        var results = new ArrayList<Map<String, Object>>();
        boolean transportError = false;
        boolean pending = false;
        for (var c : frozenCases) {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", c.get("id"));
            result.put("safety", c.get("safety"));
            result.put("expected", c.get("expected"));
            try {
                var run =
                        call(
                                "POST",
                                engineUrl + "/internal/evaluations/run",
                                Map.of(
                                        "appCode",
                                        code,
                                        "draftRev",
                                        rev,
                                        "candidateSha256",
                                        hash,
                                        "datasetVersion",
                                        dataset,
                                        "evalReportId",
                                        id,
                                        "caseId",
                                        c.get("id"),
                                        "frozenCandidate",
                                        candidate,
                                        "inputs",
                                        c.get("inputs")));
                String runStatus = Objects.toString(run.get("status"), "UNKNOWN");
                // A queued run or a submitted external job is unfinished, not a failed
                // case. Keep the frozen report resumable under its original case requestIds.
                if (Set.of(
                                "PENDING",
                                "CREATED",
                                "READY",
                                "RUNNING",
                                "WAITING_APPROVAL",
                                "WAITING_STEP",
                                "WAITING_EXTERNAL")
                        .contains(runStatus)) pending = true;
                boolean succeeded = "SUCCEEDED".equals(run.get("status"));
                boolean passed =
                        succeeded
                                && Json.tree(c.get("expected"))
                                        .equals(Json.tree(run.get("output")));
                result.put("passed", passed);
                result.put("runId", Objects.toString(run.get("runId"), ""));
                result.put("actual", run.get("output"));
                result.put("status", Objects.toString(run.get("status"), "UNKNOWN"));
                result.put(
                        "attribution",
                        passed ? "NONE" : succeeded ? "OUTPUT_MISMATCH" : "EXECUTION_FAILURE");
            } catch (Exception ex) {
                transportError = true;
                result.put("passed", false);
                result.put("attribution", "DEPENDENCY_FAILURE");
                result.put("error", Objects.toString(ex.getMessage(), "引擎调用失败"));
            }
            results.add(result);
        }
        boolean accepted =
                !transportError
                        && !pending
                        && EvaluationRules.accepted(results, threshold, baselinePassed);
        String status =
                transportError ? "ERROR" : pending ? "RUNNING" : accepted ? "ACCEPTED" : "REJECTED";
        db.update(
                "update monitor.evaluation_report set status=?,results=?::jsonb,completed_at=case"
                    + " when ?='RUNNING' then null else now() end,detail=? where tenant_id=? and"
                    + " id=? and status='RUNNING'",
                status,
                Json.write(results),
                status,
                "规则判分：精确JSON相等；安全100%；通过率阈值；同数据集基线无新增失败",
                tenant(),
                id);
        return report(id);
    }

    public Map<String, Object> resume(String id) {
        var current = report(id);
        if (!Actor.current().principalId().equals(current.get("createdBy")))
            throw fail(403, "EVALUATION_OWNER_REQUIRED", "仅原发起人可恢复评测，避免更换主体产生新的业务执行");
        if (Set.of("ACCEPTED", "REJECTED").contains(current.get("status"))) return current;
        var frozen =
                db.queryForList(
                                "select r.candidate::text,d.cases::text from"
                                    + " monitor.evaluation_report r join monitor.dataset d on"
                                    + " d.tenant_id=r.tenant_id and d.app_code=r.app_code and"
                                    + " d.dataset_version=r.dataset_version where r.tenant_id=? and"
                                    + " r.id=?",
                                tenant(),
                                id)
                        .getFirst();
        var baselinePassed = new HashSet<String>();
        String baseline = Objects.toString(current.get("baselineReportId"), "");
        if (!baseline.isEmpty())
            for (var result : cases(report(baseline).get("results")))
                if (Boolean.TRUE.equals(result.get("passed")))
                    baselinePassed.add(Objects.toString(result.get("id")));
        db.update(
                "update monitor.evaluation_report set status='RUNNING',completed_at=null where"
                        + " tenant_id=? and id=? and status in ('RUNNING','ERROR')",
                tenant(),
                id);
        // 引擎以(报告,用例)持久幂等；恢复沿用已冻结候选与原run，禁止读取后续新草稿替换旧报告。
        return execute(
                id,
                current.get("appCode").toString(),
                ((Number) current.get("draftRev")).longValue(),
                current.get("candidateSha256").toString(),
                current.get("datasetVersion").toString(),
                obj(Json.read(frozen.get("candidate").toString(), Map.class)),
                cases(Json.read(frozen.get("cases").toString(), List.class)),
                ((Number) current.get("threshold")).doubleValue(),
                baselinePassed);
    }

    public List<Map<String, Object>> list() {
        return db
                .queryForList(
                        "select id from monitor.evaluation_report where tenant_id=? order by"
                                + " created_at desc limit 200",
                        tenant())
                .stream()
                .map(r -> report(r.get("id").toString()))
                .toList();
    }

    public Map<String, Object> report(String id) {
        var rows =
                db.query(
                        "select * from monitor.evaluation_report where tenant_id=? and id=?",
                        (r, n) -> {
                            var v = new LinkedHashMap<String, Object>();
                            for (String k : List.of("id", "status", "detail"))
                                v.put(k, r.getString(k));
                            v.put("evalReportId", r.getString("id"));
                            v.put("appCode", r.getString("app_code"));
                            v.put("draftRev", r.getLong("draft_rev"));
                            v.put("candidateSha256", r.getString("candidate_sha256"));
                            v.put("datasetVersion", r.getString("dataset_version"));
                            v.put("threshold", r.getDouble("threshold"));
                            v.put("baselineReportId", r.getString("baseline_report_id"));
                            v.put("results", Json.read(r.getString("results"), Object.class));
                            v.put("createdBy", r.getString("created_by"));
                            v.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
                            v.put("accepted", r.getString("status").equals("ACCEPTED"));
                            return v;
                        },
                        tenant(),
                        id);
        if (rows.isEmpty()) throw fail(404, "REPORT_NOT_FOUND", "评测报告不存在");
        return rows.getFirst();
    }

    public Map<String, Object> accepted(String code, long rev, String hash) {
        var rows =
                db.queryForList(
                        "select id from monitor.evaluation_report where tenant_id=? and app_code=?"
                            + " and draft_rev=? and candidate_sha256=? and status='ACCEPTED' order"
                            + " by completed_at desc limit 1",
                        tenant(),
                        code,
                        rev,
                        hash);
        return rows.isEmpty()
                ? Map.of("accepted", false)
                : report(rows.getFirst().get("id").toString());
    }

    public Object project(String kind) {
        if (!Set.of("approvals", "statistics", "audit").contains(kind))
            throw fail(400, "INVALID_PROJECTION", "不支持的投影");
        if (kind.equals("statistics")) Actor.current().requireRole("ADMIN");
        return callRaw("GET", engineUrl + "/internal/" + kind, Map.of());
    }

    public Object approve(String run, String node, Map<String, Object> b) {
        Actor.current();
        if (!run.matches("[A-Za-z0-9_-]+") || !node.matches("[A-Za-z0-9_-]+"))
            throw fail(400, "INVALID_INPUT", "运行或节点标识无效");
        required(b, "requestId");
        if (!(b.get("approved") instanceof Boolean))
            throw fail(400, "INVALID_INPUT", "approved必须为布尔值");
        return call("POST", engineUrl + "/internal/runs/" + run + "/approvals/" + node, b);
    }
}
