package cn.agentplatform.monitor.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.EvaluationCandidateBinding;
import cn.agentplatform.common.authority.PlatformBuildRoutes;
import cn.agentplatform.common.authority.ResourceBinding;
import cn.agentplatform.common.authority.ServiceCall;
import cn.agentplatform.common.authority.ServiceRoutePolicy;
import cn.agentplatform.common.authority.SubjectKey;
import cn.agentplatform.common.authority.VerifiedAuthority;
import cn.agentplatform.monitor.domain.EvaluationAssertions;
import cn.agentplatform.monitor.domain.EvaluationRules;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class EvaluationService {
    private static final Pattern CANDIDATE_PATH =
            Pattern.compile("/internal/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/candidate");
    private static final Pattern ACCESS_PATH =
            Pattern.compile("/internal/evaluations/access/([A-Za-z][A-Za-z0-9_-]{0,63})");
    private static final Pattern CANDIDATE_QUERY =
            Pattern.compile("draftRev=([1-9][0-9]*)&candidateSha256=([a-f0-9]{64})");
    private static final Set<String> EVALUATION_ACTIONS = Set.of(
            "proxy.monitor.post.EvaluationController.evaluate", "proxy.monitor.post.EvaluationController.resume");

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final RestClient http = client();
    private final RestClient delegatedHttp = delegatedClient();
    private final ServiceRoutePolicy routes = new ServiceRoutePolicy();
    private final String appUrl, engineUrl;
    private final boolean assertionsEnabled;
    private ObjectProvider<CurrentAuthorityClient> authority;

    @Value("${agent.security.mode:dev-hmac}")
    private String securityMode = "dev-hmac";

    public boolean iamEnabled() {
        return "iam".equals(securityMode);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void authority(ObjectProvider<CurrentAuthorityClient> clients) {
        authority = clients;
    }

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

    private static RestClient delegatedClient() {
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(60));
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
        boolean iam = "iam".equals(securityMode);
        String delegated = iam ? evaluationTicket(method, URI.create(url), body) : null;
        return callRaw(method, url, body, delegated);
    }

    private Object callRaw(String method, String url, Map<String, Object> body, String delegated) {
        boolean iam = delegated != null;
        RestClient transport = iam ? delegatedHttp : http;
        try {
            String bearer = "Bearer " + (iam ? delegated : ServiceTokens.issue(Actor.current()));
            Map<?, ?> raw;
            if (method.equals("GET"))
                raw =
                        transport.get()
                                .uri(url)
                                .header(
                                        "Authorization",
                                        bearer)
                                .retrieve()
                                .body(Map.class);
            else
                raw =
                        transport.post()
                                .uri(url)
                                .header(
                                        "Authorization",
                                        bearer)
                                .body(body)
                                .retrieve()
                                .body(Map.class);
            if (raw == null) throw new IllegalStateException("空响应");
            return raw.containsKey("data") ? raw.get("data") : raw;
        } catch (RestClientResponseException e) {
            if (iam && e.getStatusCode().value() == 403) throw evaluationDenied();
            throw fail(502, "DEPENDENCY_UNAVAILABLE", "依赖服务调用失败，稍后重试");
        } catch (Exception e) {
            throw fail(502, "DEPENDENCY_UNAVAILABLE", "依赖服务调用失败，稍后重试");
        }
    }

    private String evaluationTicket(String method, URI uri, Map<String, Object> body) {
        var candidate = CANDIDATE_PATH.matcher(Objects.toString(uri.getRawPath(), ""));
        var access = ACCESS_PATH.matcher(Objects.toString(uri.getRawPath(), ""));
        String query = Objects.toString(uri.getRawQuery(), "");
        String target;
        String template;
        ResourceBinding expected;
        if ("GET".equals(method) && sameOrigin(uri, appUrl) && (candidate.matches() || access.matches())) {
            var parameters = CANDIDATE_QUERY.matcher(query);
            if (!parameters.matches()) throw evaluationDenied();
            target = "app";
            boolean readCandidate = candidate.matches();
            template = readCandidate ? "/internal/apps/{code}/candidate" : EvaluationCandidateBinding.ACCESS_TEMPLATE;
            expected = new ResourceBinding(EvaluationCandidateBinding.KIND,
                    (readCandidate ? candidate.group(1) : access.group(1)) + "@" + parameters.group(1), parameters.group(2));
        } else if ("POST".equals(method) && query.isEmpty() && sameOrigin(uri, engineUrl)
                && Set.of("/internal/evaluations/run", "/internal/evaluations/turn").contains(uri.getRawPath())) {
            if (body == null) throw evaluationDenied();
            String code = Objects.toString(body.get("appCode"), "");
            String revision = Objects.toString(body.get("draftRev"), "");
            String sha = Objects.toString(body.get("candidateSha256"), "");
            if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !revision.matches("[1-9][0-9]*")
                    || !sha.matches("[a-f0-9]{64}")) throw evaluationDenied();
            target = "engine";
            template = uri.getRawPath();
            expected = new ResourceBinding("evaluation-candidate", code + "@" + revision, sha);
        } else {
            throw evaluationDenied();
        }

        var current = AuthorityContext.current();
        if (!"monitor".equals(current.call().target()) || !EVALUATION_ACTIONS.contains(current.call().action())
                || current.operation() == null || !"PORTAL".equals(current.operation().originKind())
                || !expected.equals(current.call().resource()))
            throw evaluationDenied();
        var call = routes.require("monitor", target, method, template);
        return forward(current, new ServiceCall(target, call.action(), expected));
    }

    private String forward(VerifiedAuthority current, ServiceCall call) {
        var verified = AuthorityContext.requireFor(current.actor(), call.resource());
        var client = authority == null ? null : authority.getIfAvailable();
        if (client == null)
            throw fail(503, "DEPENDENCY_UNAVAILABLE", "评测服务授权暂不可用");
        var ticket = client.forward(verified, call);
        if (ticket == null || !verified.operation().equals(ticket.operation())
                || ticket.expiresAt() == null || !ticket.expiresAt().isAfter(Instant.now())
                || ticket.value() == null || ticket.value().isBlank()) throw evaluationDenied();
        return ticket.value();
    }

    private static boolean sameOrigin(URI request, String configured) {
        var origin = URI.create(configured);
        return request.getUserInfo() == null && request.getFragment() == null
                && Objects.equals(origin.getScheme(), request.getScheme())
                && Objects.equals(origin.getRawAuthority(), request.getRawAuthority());
    }

    private static PlatformException evaluationDenied() {
        return new PlatformException(403, "SYS-AUTH-403", "评测服务调用与当前候选授权不匹配");
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
        if ("iam".equals(securityMode)) {
            var expected = EvaluationCandidateBinding.from(Json.tree(b));
            var current = AuthorityContext.current();
            if (!EVALUATION_ACTIONS.contains(current.call().action()) || current.operation() == null
                    || !"PORTAL".equals(current.operation().originKind())) {
                throw evaluationDenied();
            }
            AuthorityContext.requireFor(current.actor(), expected);
        }
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
            if (EvaluationConversations.conversation(c)) {
                if (id.isBlank() || id.length()>128 || !ids.add(id))
                    throw fail(400,"EVALUATION_CONVERSATION_INVALID","多轮用例id须唯一且不超过128字符");
                c.put("id",id);
                c=new LinkedHashMap<>(EvaluationConversations.canonical(c,assertionsEnabled));
                suite.set(i,c); safety |= Boolean.TRUE.equals(c.get("safety"));
                assertionSnapshot.put(id,Map.of("mode","CONVERSATION","turns",c.get("turns")));
                continue;
            }
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
        promptTemplateRef(candidate);
        if (!hash.equals(candidate.get("candidateSha256"))
                || !(candidate.get("draftRev") instanceof Number candidateRev)
                || candidateRev.longValue() != rev.longValue())
            throw fail(412, "CANDIDATE_CHANGED", "候选已变更，请重新加载");
        if (suite.stream().anyMatch(EvaluationConversations::conversation))
            EvaluationConversations.limits(candidate,suite);
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
                                + " monitor.evaluation_report(tenant_id,id,app_code,draft_rev,candidate_sha256,candidate_semantic_digest,dataset_version,candidate,status,threshold,baseline_report_id,created_by,assertion_model,assertion_snapshot,results)"
                                + " values(?,?,?,?,?,?,?,?::jsonb,'RUNNING',?,?,?,?,?::jsonb,?::jsonb)",
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
                            assertionsEnabled ? Json.write(assertionSnapshot) : null,
                            Json.write(frozenCases.stream().map(c -> EvaluationConversations.initial(
                                    c,dataset,Digest.sha256(Json.write(frozenCases)))).toList()));
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
        if (frozenCases.stream().anyMatch(EvaluationConversations::conversation))
            return new EvaluationConversations(db,tx,(path,body)->call("POST",engineUrl+path,body),this::report,(runId,run)->validRunSource(Json.tree(run.get("sourceEvidence")),runId,run))
                    .execute(id,code,rev,hash,dataset,candidate,frozenCases,threshold,baselinePassed,assertionModel);
        boolean configurable = EvaluationAssertions.MODEL_CONFIGURABLE.equals(assertionModel);
        var priorResults = Json.read(db.queryForObject(
                "select results::text from monitor.evaluation_report where tenant_id=? and id=?", String.class, tenant(), id));
        String datasetSha = db.queryForObject("select sha256 from monitor.dataset where tenant_id=? and app_code=? and dataset_version=?",
                String.class, tenant(), code, dataset);
        var results = new ArrayList<Map<String, Object>>();
        boolean transportError = false;
        boolean pending = false;
        for (var c : frozenCases) {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", c.get("id"));
            result.put("safety", c.get("safety"));
            result.put("expected", c.get("expected"));
            var prior = java.util.stream.StreamSupport.stream(priorResults.spliterator(), false)
                    .filter(r -> Objects.equals(r.path("id").asText(), c.get("id"))).findFirst()
                    .orElse(Json.object()).path("sourceEvidence");
            // 协议标记在新报告首次外调前已落库；旧 RUNNING/ERROR 的 resume 不得追认新来源。
            boolean sourceProtocol = prior.path("schemaVersion").isIntegralNumber()
                    && prior.path("schemaVersion").asInt() == 2
                    && dataset.equals(prior.path("datasetVersion").asText())
                    && datasetSha.equals(prior.path("datasetSha256").asText());
            var source = new LinkedHashMap<String, Object>();
            if (sourceProtocol) source.put("schemaVersion", 2);
            source.put("environment", "UNKNOWN");
            source.put("datasetVersion", dataset);
            source.put("datasetSha256", datasetSha);
            result.put("sourceEvidence", source);
            try {
                var run =
                        evaluateRun(
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
                var runSource = Json.tree(run.get("sourceEvidence"));
                String environment = runSource.path("environment").asText("UNKNOWN");
                String runId = Objects.toString(run.get("runId"), "");
                boolean unfinished = Set.of("PENDING", "CREATED", "READY", "RUNNING", "WAITING_APPROVAL",
                        "WAITING_STEP", "WAITING_EXTERNAL").contains(runStatus);
                boolean consistent = sourceProtocol && !prior.path("invalid").asBoolean(false) && !runId.isBlank()
                        && dataset.equals(run.get("datasetVersion"));
                if (!unfinished) consistent &= validRunSource(runSource, runId, run)
                        && ("REAL".equals(run.get("environment")) && Boolean.FALSE.equals(run.get("demoMode"))
                            || "DEMO".equals(run.get("environment")) && Boolean.TRUE.equals(run.get("demoMode")));
                if (prior.hasNonNull("runId") && !runId.equals(prior.path("runId").asText())) consistent = false;
                if (!"UNKNOWN".equals(prior.path("environment").asText("UNKNOWN"))
                        && !environment.equals(prior.path("environment").asText())) consistent = false;
                source.put("environment", consistent && !unfinished ? environment : "UNKNOWN");
                if (consistent && !unfinished) source.put("runSource", runSource);
                if (!consistent && !runId.isBlank()) source.put("invalid", true);
                if (!runId.isBlank()) source.put("runId", runId);
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
        return resume(id, null);
    }

    public Map<String, Object> resume(String id, Map<String, Object> binding) {
        var current = report(id);
        if (!Actor.current().principalId().equals(current.get("createdBy")))
            throw fail(403, "EVALUATION_OWNER_REQUIRED", "仅原发起人可恢复评测，避免更换主体产生新的业务执行");
        if (iamEnabled()) {
            var supplied = EvaluationCandidateBinding.from(Json.tree(binding));
            var stored = EvaluationCandidateBinding.from(Json.tree(current));
            if (!stored.equals(supplied)) throw evaluationDenied();
            EvaluationCandidateBinding.requirePortal("gateway", "monitor",
                    "proxy.monitor.post.EvaluationController.resume", stored);
            String path = "/internal/evaluations/access/" + current.get("appCode")
                    + "?draftRev=" + current.get("draftRev") + "&candidateSha256=" + current.get("candidateSha256");
            if (!Boolean.TRUE.equals(call("GET", appUrl + path, Map.of()).get("allowed"))) throw evaluationDenied();
        }
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

    private Map<String, Object> evaluateRun(Map<String, Object> request) {
        String path = engineUrl + "/internal/evaluations/run";
        var result = call("POST", path, request);
        if (!iamEnabled()) return result;
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (Set.of("PENDING", "QUEUED", "RUNNING").contains(result.get("status"))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(Duration.ofMillis(100));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw fail(503, "EVALUATION_INTERRUPTED", "评测等待已中断");
            }
            // engine pins the request to the report and case so reads cannot create another run.
            result = call("POST", path, request);
        }
        return result;
    }

    public List<Map<String, Object>> list() {
        String query = "select id from monitor.evaluation_report where tenant_id=?";
        String limit = " order by created_at desc limit 200";
        String tenant = tenant();
        List<Map<String, Object>> rows;
        if (iamEnabled()) {
            var proof = EvaluationCandidateBinding.requirePortal("gateway", "monitor",
                    "proxy.monitor.get.EvaluationController.list", EvaluationCandidateBinding.reports());
            rows = db.queryForList(query + " and created_by=?" + limit, tenant, proof.actor().principalId());
        } else {
            rows = db.queryForList(query + limit, tenant);
        }
        return rows.stream()
                .map(r -> report(r.get("id").toString()))
                .toList();
    }

    public Map<String, Object> portalReport(String id) {
        if (!iamEnabled()) return report(id);
        var proof = EvaluationCandidateBinding.requirePortal("gateway", "monitor",
                "proxy.monitor.get.EvaluationController.report", EvaluationCandidateBinding.report(id));
        var current = report(id);
        if (!proof.actor().principalId().equals(current.get("createdBy"))) throw evaluationDenied();
        return current;
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
                            // 只读报告持久候选，不以当前模板反推历史引用。
                            v.put("promptTemplateRef", promptTemplateRef(obj(Json.read(r.getString("candidate"), Map.class))));
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
        var result = rows.getFirst();
        result.put("publicationEvidence", publicationEvidence(result));
        return result;
    }

    /** 服务固定的 engine 来源证据；签名/主体入口沿原 HTTP 链，本方法只核精确事实和材料摘要。 */
    private boolean validRunSource(JsonNode proof, String runId, Map<String, Object> run) {
        if (!proof.path("schemaVersion").isIntegralNumber() || proof.path("schemaVersion").asInt() != 2 || !"PLATFORM_PARTICIPANT_MODES".equals(proof.path("scope").asText())
                || !runId.equals(proof.path("runId").asText()) || !tenant().equals(proof.path("tenantId").asText())
                || !Actor.current().principalId().equals(proof.path("principalId").asText())
                || !Set.of("REAL", "DEMO", "UNKNOWN").contains(proof.path("environment").asText())
                || !proof.path("participants").isArray()
                || !Objects.equals(run.get("environment"), proof.path("engineEnvironment").asText())) return false;
        var unsigned = new LinkedHashMap<String,Object>(Json.mapper().convertValue(proof, Map.class));
        Object digest = unsigned.remove("evidenceSha256");
        return Digest.sha256(Json.canonical(unsigned)).equals(digest);
    }

    /** 证明平台各 producer 自身模式/实际路径及数据集身份；外部语义/业务效果不在范围。 */
    private Map<String, Object> publicationEvidence(Map<String, Object> report) {
        var data = db.queryForList("select cases::text,sha256 from monitor.dataset where tenant_id=? and app_code=? and dataset_version=?",
                tenant(), report.get("appCode"), report.get("datasetVersion"));
        String environment = "UNKNOWN";
        String datasetSha = data.isEmpty() ? "" : Objects.toString(data.getFirst().get("sha256"), "");
        var results = Json.tree(report.get("results"));
        if (!data.isEmpty()) {
            var suite = Json.read(data.getFirst().get("cases").toString());
            var ids = new HashSet<String>();
            suite.forEach(c -> ids.add(c.path("id").asText()));
            boolean valid = suite.isArray() && suite.size() > 0 && ids.size() == suite.size()
                    && results.isArray() && results.size() == suite.size();
            boolean demo = false; boolean unknown = false;
            for (var r : results) {
                var source = r.path("sourceEvidence");
                String mode = source.path("environment").asText();
                boolean rowValid = ids.remove(r.path("id").asText()) && source.path("schemaVersion").isIntegralNumber()
                        && source.path("schemaVersion").asInt() == 2
                        && report.get("datasetVersion").equals(source.path("datasetVersion").asText())
                        && datasetSha.equals(source.path("datasetSha256").asText())
                        && !source.path("runId").asText().isBlank()
                        && Set.of("REAL", "DEMO", "UNKNOWN").contains(mode)
                        && source.path("runSource").path("schemaVersion").asInt() == 2;
                valid &= rowValid; unknown |= !rowValid || "UNKNOWN".equals(mode);
                demo |= rowValid && "DEMO".equals(mode);
            }
            if (demo) environment = "DEMO";
            else if (valid && ids.isEmpty() && !unknown) environment = "REAL";
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("schemaVersion", 2);
        evidence.put("scope", "PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY");
        evidence.put("externalTruth", "NOT_ASSESSED");
        evidence.put("caseSourcesSha256", Digest.sha256(Json.canonical(results)));
        evidence.put("tenantId", tenant());
        for (String key : List.of("appCode", "draftRev", "evalReportId", "candidateSha256", "candidateSemanticDigest", "datasetVersion"))
            evidence.put(key, report.get(key));
        evidence.put("datasetSha256", datasetSha);
        evidence.put("environment", environment);
        evidence.put("qualityAccepted", Boolean.TRUE.equals(report.get("accepted")));
        evidence.put("status", report.get("status"));
        evidence.put("evidenceSha256", Digest.sha256(Json.canonical(evidence)));
        return evidence;
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
        if ("iam".equals(securityMode)) {
            EvaluationCandidateBinding.of(code, rev, hash);
            if (semanticDigest != null && !semanticDigest.matches("[a-f0-9]{64}")) {
                throw fail(400, "INVALID_CANDIDATE_DIGEST", "候选语义摘要格式无效");
            }
            EvaluationCandidateBinding.requirePublication(code, "app", "monitor",
                    EvaluationCandidateBinding.EVIDENCE_ACTION);
        }
        var rows =
                db.queryForList(
                        "select id,candidate_sha256,candidate_semantic_digest from"
                            + " monitor.evaluation_report where tenant_id=? and app_code=? and"
                            + " draft_rev=? and status in ('ACCEPTED','REJECTED') and (candidate_sha256=?"
                            + " or (candidate_semantic_digest is not null and"
                            + " candidate_semantic_digest=?)) order by completed_at desc",
                        tenant(),
                        code,
                        rev,
                        hash,
                        semanticDigest);
        if (rows.isEmpty()) return Map.of("accepted", false);
        var reports = rows.stream().map(r -> report(r.get("id").toString())).toList();
        var quality = reports.stream().filter(r -> Boolean.TRUE.equals(r.get("accepted"))).toList();
        var exact =
                quality.stream()
                        .filter(r -> Objects.equals(r.get("candidateSha256"), hash))
                        .findFirst();
        var result = quality.isEmpty() ? new LinkedHashMap<String, Object>(Map.of("accepted", false))
                : new LinkedHashMap<>(exact.orElse(quality.getFirst()));
        if (!quality.isEmpty()) result.put("matchedBy", exact.isPresent() ? "SHA256" : "SEMANTIC_DIGEST");
        var exactSources = reports.stream().filter(r -> Objects.equals(r.get("candidateSha256"), hash)).toList();
        var sources = exactSources.isEmpty() ? reports : exactSources;
        var sourceReport = sources.stream().min(Comparator.comparingInt(r -> {
            var evidence = Json.tree(r.get("publicationEvidence"));
            String mode = evidence.path("environment").asText();
            return ("REAL".equals(mode) ? 0 : "DEMO".equals(mode) ? 2 : 4)
                    + (Boolean.TRUE.equals(r.get("accepted")) ? 0 : 1);
        })).orElseThrow();
        result.put("publicationEvidence", sourceReport.get("publicationEvidence"));
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

    /** 正文不重复进入候选摘要，必须通过已入摘要的精确引用核验后使用。 */
    private Map<String, Object> promptTemplateRef(Map<String, Object> candidate) {
        var snapshot = Json.tree(candidate);
        if (!snapshot.path("config").has("promptSource")) return null;
        // R-33：DIRECT 候选无模板引用可核，但报告仍须保留"评的是哪份内联提示词"的事后证明——
        // 返回内联正文的 canonical SHA-256 而非 null；缺内联正文与 engine ContextAssembler 同判 409。
        if ("DIRECT".equals(snapshot.path("config").path("promptSource").asText())) {
            var inline = snapshot.path("config").path("systemPrompt");
            if (!inline.isTextual() || inline.asText().isBlank())
                throw fail(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "直连来源缺少内联正文");
            return Map.of("source", "DIRECT", "promptSha256", Digest.sha256(inline.asText()));
        }
        var refs = snapshot.path("config").path("promptRefs");
        var frozen = snapshot.path("dependencyVersions").path("frozenPrompts");
        if (!"TEMPLATE".equals(snapshot.path("config").path("promptSource").asText())
                || !refs.isArray() || refs.size() != 1 || !frozen.isArray() || frozen.size() != 1)
            throw promptMismatch();
        var ref = refs.get(0);
        var prompt = frozen.get(0);
        var promptFields = Set.of("code", "version", "versionNo", "status", "content", "variables", "outputContract", "sha256");
        prompt.fieldNames().forEachRemaining(key -> { if (!promptFields.contains(key)) throw promptMismatch(); });
        String sha = ref.path("sha256").asText();
        if (!ref.isObject() || ref.size() != 3 || !ref.path("code").asText().matches("[A-Za-z][A-Za-z0-9_-]{0,63}")
                || !ref.path("version").isIntegralNumber() || !ref.path("version").canConvertToInt()
                || ref.path("version").intValue() < 1 || !sha.matches("[0-9a-f]{64}")
                || !ref.path("code").equals(prompt.path("code"))
                || !ref.path("version").equals(prompt.path("version"))
                || prompt.has("versionNo") && !ref.path("version").equals(prompt.path("versionNo"))
                || !sha.equals(prompt.path("sha256").asText())
                || !"PUBLISHED".equals(prompt.path("status").asText())
                || !prompt.path("content").isTextual() || !prompt.path("variables").isArray()
                || !prompt.path("outputContract").isObject()) throw promptMismatch();
        var content = new LinkedHashMap<String, Object>();
        for (String key : List.of("appCode", "type", "name", "description", "domain", "bizCategory", "tags", "config", "definition"))
            content.put(key, snapshot.path(key));
        var material = Map.of("content", prompt.path("content"), "variables", prompt.path("variables"),
                "outputContract", prompt.path("outputContract"));
        if (!Digest.sha256(Json.canonical(content)).equals(snapshot.path("candidateSha256").asText())
                || !sha.equals(Digest.sha256(Json.canonical(material)))) throw promptMismatch();
        return Map.of("code", ref.path("code").textValue(), "version", ref.path("version").intValue(), "sha256", sha);
    }

    private PlatformException promptMismatch() {
        return fail(409, "PROMPT_TEMPLATE_SNAPSHOT_MISMATCH", "冻结模板正文与候选中的精确版本摘要不一致");
    }

    public Object project(String kind) {
        if (!Set.of("approvals", "statistics", "audit").contains(kind))
            throw fail(400, "INVALID_PROJECTION", "不支持的投影");
        if (kind.equals("statistics")) Actor.current().requireRole("ADMIN");
        if (iamEnabled() && kind.equals("audit")) {
            var current = AuthorityContext.current();
            var subject = new SubjectKey(current.actor().tenantId(), current.actor().principalId());
            var call = PlatformBuildRoutes.runAudit(routes, subject);
            return callRaw("GET", engineUrl + PlatformBuildRoutes.RUN_AUDIT, Map.of(), forward(current, call));
        }
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
