package cn.agentplatform.skill.application;

import static cn.agentplatform.skill.application.SkillCatalog.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.domain.SkillRules;
import cn.agentplatform.skill.infra.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** 连接与选型报告均绑定契约摘要；任何Schema/端点/守卫修改后，旧报告不能为新草稿放行。 */
@Service
public class SkillGovernance {
    private final JdbcTemplate db;
    private final SkillCatalog catalog;
    private final SkillAdapters adapters;
    private final SkillClients clients;
    private final String modelUrl;
    private final String engineUrl;
    private OnboardingExecutionBindings onboardingBindings;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void onboardingBindings(OnboardingExecutionBindings bindings){this.onboardingBindings=bindings;}

    public SkillGovernance(
            JdbcTemplate db,
            SkillCatalog catalog,
            SkillAdapters adapters,
            SkillClients clients,
            @Value("${agent.services.model:http://127.0.0.1:18084}") String modelUrl,
            @Value("${agent.services.engine:http://127.0.0.1:18082}") String engineUrl) {
        this.engineUrl = engineUrl;
        this.db = db;
        this.catalog = catalog;
        this.adapters = adapters;
        this.clients = clients;
        this.modelUrl = modelUrl;
    }

    public Map<String, Object> connection(String code, int version, Map<String, Object> body) {
        var candidate = catalog.version(code, version);
        if(onboardingBindings!=null)candidate=onboardingBindings.attach(Actor.current(),candidate);
        if (!Objects.toString(candidate.get("authRef"), "").isBlank())
            Actor.current().requireRole("ADMIN");
        long id = ((Number) candidate.get("skillVersionId")).longValue();
        var execution = object(candidate.get("execution"));
        boolean readonly =
                Boolean.TRUE.equals(execution.get("readonly"))
                        && !Boolean.TRUE.equals(execution.get("sideEffect"))
                        && !"CONFIRM_REQUIRED".equals(candidate.get("riskLevel"));
        var report = new LinkedHashMap<String, Object>();
        long start = System.nanoTime();
        boolean passed = false;
        try {
            {
                // 只读也登记真实DEBUG run：大结果有完整归属，错误/取消/恢复与上架证据走同一条执行链。
                long call;
                if (body.containsKey("controlledCallId")) call = number(body, "controlledCallId");
                else {
                    var testRequest=new LinkedHashMap<String,Object>();testRequest.put("skillVersionId",id);
                    testRequest.put("inputs",object(body.get("inputs")));testRequest.put("requestId",text(body,"requestId"));
                    // 仅传来源指纹作为核对提示；engine仍从自己读取的可信精确元数据取得执行引用。
                    if(candidate.containsKey("onboardingReceiptRef"))testRequest.put("onboardingReceiptRef",candidate.get("onboardingReceiptRef"));
                    var test=clients.post(engineUrl+"/internal/skill-draft-tests",testRequest);
                    report.put("runId", test.path("runId").asText());
                    report.put("status", test.path("status").asText());
                    report.put("action", readonly ? "WAIT_AND_RECHECK" : "APPROVE_AND_RECHECK");
                    call = test.path("controlledCallId").asLong(test.path("callId").asLong());
                    if (call > 0) report.put("controlledCallId", call);
                }
                String proofSql="select count(*) from skill.execution_fact where tenant_id=? and principal_id=? and call_id=?"
                        +" and skill_version_id=? and skill_contract_sha256=? and status='SUCCEEDED' and call_kind='DEBUG' and not is_mock";
                var proofArgs=new ArrayList<Object>(List.of(Actor.current().tenantId(),Actor.current().principalId(),call,id,candidate.get("sha256")));
                if(candidate.containsKey("executionBindingRefs")) {
                    proofSql+=" and request_snapshot->'bindingRefs'=?::jsonb";
                    proofArgs.add(Json.write(candidate.get("executionBindingRefs")));
                }
                Integer count=call<=0?0:db.queryForObject(proofSql,Integer.class,proofArgs.toArray());
                if (count == null || count == 0)
                    throw new IllegalArgumentException("需要同摘要成功的非Mock受控调试callId；确认并完成测试后重检");
                passed = true;
            }
        } catch (PlatformException ex) {
            throw ex;
        } catch (Exception ex) {
            report.put("reason", readonly ? "实际只读连接、权限或Schema检查未通过" : "有副作用技能须先完成引擎受控调试执行");
        }
        report.put("ok", passed);
        report.put("latencyMs", Math.max(0, (System.nanoTime() - start) / 1_000_000));
        report.put("candidateSha256", candidate.get("sha256"));
        // T10：证据同时记录“被测字节”（sha）与“被测语义”（digest）。发布门禁要求两者都与草稿
        // 当前值相等；只改 description 时 sha 变而 digest 不变，受控重绑定即可复用这份报告。
        report.put("semanticDigest", candidate.get("semanticDigest"));
        report.put("digestVersion", candidate.get("digestVersion"));
        db.update(
                "insert into"
                    + " skill.connection_test(tenant_id,skill_version_id,candidate_sha256,passed,report,semantic_digest)"
                    + " values(?,?,?,?,?::jsonb,?)",
                Actor.current().tenantId(),
                id,
                candidate.get("sha256"),
                passed,
                Json.write(report),
                candidate.get("semanticDigest"));
        return report;
    }

    public Map<String, Object> acceptance(String code, int version, Map<String, Object> body) {
        var candidate = catalog.version(code, version);
        long model = number(body, "modelEntryId");
        if (!(body.get("cases") instanceof List<?> cases) || cases.isEmpty() || cases.size() > 100)
            throw error(400, "INVALID_ACCEPTANCE", "验收用例需1至100条");
        String id = "sa_" + UUID.randomUUID();
        var results = new ArrayList<Map<String, Object>>();
        int correct = 0;
        for (int i = 0; i < cases.size(); i++) {
            var item = object(cases.get(i));
            String question = text(item, "question");
            // R01/R07：显式存在且恰为空字符串的expectSelected表示“无工具调用”预期；
            // 缺失/null/非字符串/仅空白仍按原语义拒绝，不改全局text()避免其他必填字段接受空值。
            if (!(item.get("expectSelected") instanceof String expected))
                throw error(400, "INVALID_ACCEPTANCE", "缺少或无效字段: expectSelected");
            if (!expected.isEmpty() && expected.isBlank())
                throw error(400, "INVALID_ACCEPTANCE", "expectSelected仅空白无效；无工具预期请使用空字符串");
            boolean expectNoTool = expected.isEmpty();
            var definition =
                    Map.of(
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    code,
                                    "description",
                                    candidate.get("description"),
                                    "parameters",
                                    candidate.get("inputSchema")));
            var response =
                    clients.post(
                            modelUrl + "/internal/model/chat",
                            Map.of(
                                    "modelEntryId",
                                    model,
                                    "runId",
                                    id,
                                    "invocationRef",
                                    id + ":" + i,
                                    "messages",
                                    List.of(Map.of("role", "user", "content", question)),
                                    "toolDefinitions",
                                    List.of(definition),
                                    "params",
                                    Map.of("temperature", 0, "maxOutputTokens", 512),
                                    "requiredLabels",
                                    List.of()));
            var calls = response.path("toolCalls");
            String selected =
                    calls.isArray() && !calls.isEmpty() ? calls.get(0).path("name").asText() : "";
            var actual =
                    calls.isArray() && !calls.isEmpty()
                            ? calls.get(0).path("arguments")
                            : Json.tree(Map.of());
            boolean argsOk = true;
            try {
                if (!selected.isBlank())
                    SkillRules.validate(
                            candidate.get("inputSchema"),
                            Json.read(actual.toString(), Object.class));
                if (item.containsKey("expectArgs"))
                    argsOk = Json.tree(item.get("expectArgs")).equals(actual);
            } catch (Exception ex) {
                argsOk = false;
            }
            // R07：无工具预期核实际toolCalls为空——意外选任一工具即失败，不能只看首个名字。
            boolean noToolCalled = !calls.isArray() || calls.isEmpty();
            boolean pass = (expectNoTool ? noToolCalled : expected.equals(selected)) && argsOk;
            if (pass) correct++;
            results.add(
                    Map.of(
                            "question",
                            question,
                            "selected",
                            selected,
                            "argsOk",
                            argsOk,
                            "pass",
                            pass,
                            "actualArgs",
                            actual));
        }
        double rate = (double) correct / cases.size();
        var report =
                Map.<String, Object>of(
                        "id",
                        id,
                        "pass",
                        rate >= 0.8,
                        "hitRate",
                        rate,
                        "cases",
                        results,
                        "gate",
                        Map.of("minHitRate", 0.8),
                        "candidateSha256",
                        candidate.get("sha256"),
                        // T10：验收报告与列都带语义摘要，发布门禁按“版本+sha+digest+passed”匹配证据。
                        "semanticDigest",
                        candidate.get("semanticDigest"),
                        "digestVersion",
                        candidate.get("digestVersion"));
        db.update(
                "insert into"
                    + " skill.acceptance(id,tenant_id,skill_version_id,candidate_sha256,passed,hit_rate,report,semantic_digest)"
                    + " values(?,?,?,?,?,?,?::jsonb,?)",
                id,
                Actor.current().tenantId(),
                candidate.get("skillVersionId"),
                candidate.get("sha256"),
                rate >= 0.8,
                rate,
                Json.write(report),
                candidate.get("semanticDigest"));
        return report;
    }
}
