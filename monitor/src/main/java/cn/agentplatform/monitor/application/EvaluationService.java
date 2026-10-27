package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import cn.agentplatform.monitor.domain.EvaluationAssertions;
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
    private final boolean assertionsEnabled;

    public EvaluationService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            String appUrl,
            String engineUrl) {
        this(db, manager, appUrl, engineUrl, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationService(
            JdbcTemplate db,
            PlatformTransactionManager manager,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl,
            @Value("${agent.monitor.evaluation.assertions.enabled:true}") boolean assertionsEnabled) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.appUrl = appUrl;
        this.engineUrl = engineUrl;
        this.assertionsEnabled = assertionsEnabled;
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

    private EvaluationAssertions.Spec assertionSpec(
            Object assertion, Object expected, boolean safety) {
        try {
            return EvaluationAssertions.requireValid(assertion, expected, safety);
        } catch (EvaluationAssertions.InvalidAssertion e) {
            // 保存期拒绝：错误码稳定、信息可读；调用方尚未冻结数据集、尚未发起执行。
            throw fail(400, e.code(), e.userMessage());
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
        // 判分模型与断言快照随报告一起冻结：恢复执行只认快照，不受后续数据集/默认值变化影响。
        String assertionModel =
                assertionsEnabled
                        ? EvaluationAssertions.MODEL_CONFIGURABLE
                        : EvaluationAssertions.MODEL_EXACT;
        var assertionSnapshot = new LinkedHashMap<String, Object>();
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
            boolean caseSafety = Boolean.TRUE.equals(c.get("safety"));
            // 保存期校验断言配置（未知模式/非法参数/削弱安全语义），失败时尚未写库、尚未调用上游。
            var spec = assertionSpec(c.get("assertion"), c.get("expected"), caseSafety);
            // 仅规范化显式配置；历史用例保持无断言字段，旧数据集内容字节不变。
            if (assertionsEnabled && c.get("assertion") != null) c.put("assertion", spec.canonical());
            assertionSnapshot.put(id, spec.canonical());
            suite.set(i, c);
            safety |= caseSafety;
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
        String semanticDigest = candidateDigest(candidate);
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
                                + " monitor.evaluation_report(tenant_id,id,app_code,draft_rev,candidate_sha256,candidate_semantic_digest,dataset_version,candidate,status,threshold,baseline_report_id,created_by,assertion_model,assertion_snapshot)"
                                + " values(?,?,?,?,?,?,?,?::jsonb,'RUNNING',?,?,?,?,?::jsonb)",
                            tenant(),
                            id,
                            code,
                            rev.longValue(),
                            hash,
                            semanticDigest,
                            dataset,
                            Json.write(candidate),
                            threshold,
                            baselineId,
                            Actor.current().principalId(),
                            assertionModel,
                            assertionsEnabled ? Json.write(assertionSnapshot) : null);
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
                baselinePassed,
                assertionModel);
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
            Set<String> baselinePassed,
            String assertionModel) {
        // EXACT（旧报告/开关关闭）忽略 assertion 字段；CONFIGURABLE 按冻结的用例配置判分。
        boolean configurable = EvaluationAssertions.MODEL_CONFIGURABLE.equals(assertionModel);
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
                boolean succeeded = "SUCCEEDED".equals(runStatus);
                var comparison =
                        succeeded
                                ? EvaluationAssertions.compare(
                                        configurable ? c.get("assertion") : null,
                                        c.get("expected"),
                                        run.get("output"))
                                : new EvaluationAssertions.CompareResult(
                                        false,
                                        "ASSERTION_EXECUTION_NOT_SUCCEEDED",
                                        "运行状态 " + runStatus + " 未产生可判分的成功输出");
                boolean passed = succeeded && comparison.pass();
                result.put("passed", passed);
                result.put(
                        "assertion",
                        EvaluationAssertions.modeOf(configurable ? c.get("assertion") : null));
                result.put(
                        "baselinePassed",
                        baselinePassed.contains(Objects.toString(c.get("id"), "")));
                result.put(
                        "regression",
                        baselinePassed.contains(Objects.toString(c.get("id"), "")) && !passed);
                result.put("runId", Objects.toString(run.get("runId"), ""));
                result.put("actual", run.get("output"));
                result.put("status", Objects.toString(run.get("status"), "UNKNOWN"));
                result.put(
                        "attribution",
                        passed ? "NONE" : succeeded ? "OUTPUT_MISMATCH" : "EXECUTION_FAILURE");
                if (!passed) {
                    // 可读、有界的原因（模式名+差异位置），不回贴整份输出。
                    result.put("reasonCode", comparison.code());
                    result.put("reason", comparison.detail());
                }
            } catch (Exception ex) {
                transportError = true;
                result.put("passed", false);
                result.put(
                        "baselinePassed",
                        baselinePassed.contains(Objects.toString(c.get("id"), "")));
                result.put(
                        "regression",
                        baselinePassed.contains(Objects.toString(c.get("id"), "")));
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
                "规则判分：按用例断言（历史用例缺省精确JSON相等）；安全100%；通过率阈值；同数据集基线无新增失败",
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
                baselinePassed,
                Objects.toString(current.get("assertionModel"), EvaluationAssertions.MODEL_EXACT));
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
                            v.put(
                                    "candidateSemanticDigest",
                                    r.getString("candidate_semantic_digest"));
                            v.put("datasetVersion", r.getString("dataset_version"));
                            v.put("threshold", r.getDouble("threshold"));
                            v.put("baselineReportId", r.getString("baseline_report_id"));
                            v.put("assertionModel", r.getString("assertion_model"));
                            String assertionSnapshot = r.getString("assertion_snapshot");
                            v.put(
                                    "assertionSnapshot",
                                    assertionSnapshot == null
                                            ? null
                                            : Json.read(assertionSnapshot, Map.class));
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> results =
                                    (List<Map<String, Object>>)
                                            (List<?>) Json.read(r.getString("results"), List.class);
                            if (results == null) results = List.of();
                            boolean accepted = r.getString("status").equals("ACCEPTED");
                            v.put("results", results);
                            v.put(
                                    "summary",
                                    EvaluationRules.summary(
                                            results, r.getDouble("threshold"), accepted));
                            v.put("createdBy", r.getString("created_by"));
                            v.put("createdAt", r.getTimestamp("created_at").toInstant().toString());
                            v.put("accepted", accepted);
                            return v;
                        },
                        tenant(),
                        id);
        if (rows.isEmpty()) throw fail(404, "REPORT_NOT_FOUND", "评测报告不存在");
        return rows.getFirst();
    }

    /**
     * 候选是否已有可用证据。
     *
     * <p>两条路：sha 精确匹配（快照身份一致）与语义摘要匹配（只有描述类字段变化）。**sha 优先**——同一候选
     * 既可能有多份报告，也可能新旧报告都命中；顺序固定，调用方才能稳定判断"这次是不是复用"。
     * 两者都不命中一律 {@code accepted=false}，绝不回落到"最近一次通过的报告"。
     *
     * @param semanticDigest 调用方当前候选的语义摘要；不知道就传 null，此时只走 sha 精确匹配
     */
    public Map<String, Object> accepted(String code, long rev, String hash, String semanticDigest) {
        var rows =
                db.queryForList(
                        "select id,candidate_sha256,candidate_semantic_digest from"
                            + " monitor.evaluation_report where tenant_id=? and app_code=? and"
                            + " draft_rev=? and status='ACCEPTED' and (candidate_sha256=?"
                            + " or (candidate_semantic_digest is not null and"
                            + " candidate_semantic_digest=?)) order by completed_at desc",
                        tenant(),
                        code,
                        rev,
                        hash,
                        semanticDigest);
        if (rows.isEmpty()) return Map.of("accepted", false);
        var exact =
                rows.stream()
                        .filter(r -> Objects.equals(r.get("candidate_sha256"), hash))
                        .findFirst();
        var chosen = exact.orElse(rows.getFirst());
        var result = new LinkedHashMap<>(report(chosen.get("id").toString()));
        result.put("matchedBy", exact.isPresent() ? "SHA256" : "SEMANTIC_DIGEST");
        return result;
    }

    /** 历史调用方（只给 sha）保持原行为。 */
    public Map<String, Object> accepted(String code, long rev, String hash) {
        return accepted(code, rev, hash, null);
    }

    /**
     * 从候选里读语义摘要：缺省返回 null（历史候选、只读技能都不带），存在则必须是 64 位小写十六进制。
     *
     * <p>不在这里重算摘要：候选由应用侧构造，监控侧重算等于把两套规则耦合在一起；传上来的是声明，
     * 与 sha 一样属于调用方必须保持一致的输入。
     */
    static String candidateDigest(Map<String, Object> candidate) {
        Object value = candidate.get("semanticDigest");
        if (value == null) return null;
        if (!(value instanceof String digest) || !digest.matches("[0-9a-f]{64}"))
            throw new PlatformException(400, "INVALID_CANDIDATE_DIGEST", "候选语义摘要必须是64位小写十六进制");
        return digest;
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
