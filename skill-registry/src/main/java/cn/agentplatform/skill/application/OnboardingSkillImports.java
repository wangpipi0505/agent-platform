package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.auth.OnboardingServiceProof;
import cn.agentplatform.common.authority.AuthorityContext;
import cn.agentplatform.common.authority.VerifiedAuthority;
import cn.agentplatform.skill.infra.OnboardingImportReceipts;
import cn.agentplatform.skill.infra.OnboardingImportReceipts.ImportReceipt;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只接受 app 冻结的接入计划；复用原 OpenAPI 转换器，不另造 Schema 或绕过技能发布门禁。 */
@Service
public class OnboardingSkillImports {
    private static final Set<String> BODY_KEYS = Set.of(
            "source", "document", "includeOps", "defaults", "overrides", "bindingRefs");
    private static final Set<String> SOURCE_KEYS = Set.of(
            "sessionId", "planSha256", "templateCode", "templateRev", "packageVersionId",
            "packageSha256", "documentSha256");
    private static final Set<String> OVERRIDE_KEYS = Set.of(
            "name", "description", "execution", "guard", "returnSpec", "errorSemantics",
            "authRef", "timeoutMs", "constraints");
    private static final Set<String> REF_REQUIRED = Set.of(
            "profileId", "profileRev", "bindingId", "bindingRev", "operation", "environment");
    private static final Set<String> REF_KEYS = Set.of(
            "profileId", "profileRev", "bindingId", "bindingRev", "operation", "environment",
            "resolutionDigest");

    private record Access(Actor actor, Map<String, Object> audit) {}
    private record ImportPlan(
            Map<String, Object> source,
            List<String> operations,
            List<Map<String, Object>> bindingRefs,
            Map<String, Object> importBody,
            String sourceSha256,
            String requestSha256) {}

    private final OpenApiImports imports;
    private final SkillCatalog catalog;
    private final OnboardingImportReceipts receipts;
    private final TransactionTemplate tx;

    @Value("${agent.security.mode:}")
    private String securityMode = "";

    @Value("${agent.onboarding.dev-app-key-ref:}")
    private String devAppKeyRef = "";

    public OnboardingSkillImports(
            OpenApiImports imports,
            SkillCatalog catalog,
            OnboardingImportReceipts receipts,
            PlatformTransactionManager manager) {
        this.imports = imports;
        this.catalog = catalog;
        this.receipts = receipts;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(30);
    }

    public Map<String, Object> create(Map<String, Object> body) {
        // 在线当前授权复核在事务外完成；事务内只做本地导入与 receipt 原子提交。
        Access access = access("skill.onboarding-import.create", body);
        ImportPlan plan = plan(body);
        return tx.execute(status -> {
            String sessionId = plan.source().get("sessionId").toString();
            String planSha = plan.source().get("planSha256").toString();
            receipts.lockPlan(access.actor(), sessionId, planSha);
            var existing = receipts.findPlan(access.actor(), sessionId, planSha);
            if (!existing.isEmpty()) {
                assertReplay(access.actor(), plan, existing);
                return response(access.actor(), existing, true);
            }

            // OpenApiImports.create 的 REQUIRED 事务和 Catalog 的事务均加入当前 skill 事务。
            // 任一 operation 失败时，草稿与所有 receipt 一起回滚；不靠内存标记补偿丢响应。
            imports.create(plan.importBody());
            for (String operation : plan.operations()) {
                String code = codeFor(sessionId, planSha, operation);
                var actual = catalog.version(code, 1);
                assertImportedSource(actual, operation, plan.source().get("documentSha256"));
                receipts.insert(
                        access.actor(), plan.source(), plan.sourceSha256(), plan.requestSha256(),
                        plan.bindingRefs().stream()
                                .filter(ref -> operation.equals(ref.get("operation"))).toList(),
                        operation, actual, access.audit());
            }
            return response(access.actor(), receipts.findPlan(access.actor(), sessionId, planSha), false);
        });
    }

    public Map<String, Object> get(String sessionId, String planSha256) {
        Access access = access("skill.onboarding-import.read", null);
        sessionId(sessionId);
        sha(planSha256, "planSha256");
        return tx.execute(status -> {
            receipts.lockPlan(access.actor(), sessionId, planSha256);
            var stored = receipts.findPlan(access.actor(), sessionId, planSha256);
            if (stored.isEmpty())
                throw error(404, "ONBOARDING_IMPORT_NOT_FOUND", "尚无本计划的技能导入回执");
            assertOwner(access.actor(), stored);
            return response(access.actor(), stored, true);
        });
    }

    /** 编码只取冻结来源；客户端不能指定另一技能编码，也不会覆盖同码现有技能。 */
    public static String codeFor(String sessionId, String planSha256, String operationId) {
        return "onb_" + Digest.sha256(Json.canonical(List.of(sessionId, planSha256, operationId)))
                .substring(0, 48);
    }

    private Access access(String action, Object body) {
        Actor actor = Actor.current();
        requireRoles(actor);
        if ("dev-hmac".equals(securityMode)) return developerAccess(actor, action, body);
        if (!"iam".equals(securityMode))
            throw error(503, "AUTHORITY_CONTEXT_MISSING", "接入导入必须显式配置正式或开发模式");
        VerifiedAuthority initial = AuthorityContext.current();
        requireApp(initial, action);
        // 沿既有 root resource 复核，绝不把 body 的 sessionId 或摘要升格成授权资源。
        VerifiedAuthority current = AuthorityContext.requireFor(actor, initial.call().resource());
        requireApp(current, action);
        requireRoles(current.actor());
        var audit = new LinkedHashMap<String, Object>();
        audit.put("mode", "IAM");
        audit.put("caller", current.caller());
        audit.put("target", current.call().target());
        audit.put("action", current.call().action());
        audit.put("resource", current.call().resource());
        audit.put("operation", current.operation());
        audit.put("authorizationVersion", current.authorizationVersion());
        audit.put("decisionId", current.decisionId());
        audit.put("actor", Map.of(
                "tenantId", current.actor().tenantId(),
                "principalId", current.actor().principalId(),
                "roles", current.actor().roles().stream().sorted().toList()));
        return new Access(current.actor(), audit);
    }

    private Access developerAccess(Actor actor, String action, Object body) {
        // 专用证明附加在已经验签的 service JWT 上；不从证明头重新解释或扩张调用主体。
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw error(403, "ONBOARDING_SERVICE_PROOF_INVALID", "开发接入缺少实际HTTP请求证明");
        var request = attributes.getRequest();
        String method = request.getMethod();
        String path = request.getRequestURI();
        boolean create = "skill.onboarding-import.create".equals(action)
                && "POST".equals(method) && "/internal/onboarding-skill-imports".equals(path);
        boolean read = "skill.onboarding-import.read".equals(action)
                && "GET".equals(method) && path.matches("/internal/onboarding-skill-imports/os_[a-f0-9]{32}");
        if (!create && !read)
            throw error(403, "ONBOARDING_SERVICE_PROOF_INVALID", "开发接入证明与实际登记路由不一致");
        String query = request.getQueryString();
        String pathAndQuery = path + (query == null ? "" : "?" + query);
        var headers = request.getHeaders(OnboardingServiceProof.HEADER);
        String proof = headers.hasMoreElements() ? headers.nextElement() : null;
        if (headers.hasMoreElements())
            throw error(403, "ONBOARDING_SERVICE_PROOF_INVALID", "开发接入证明必须唯一");
        var audit = new LinkedHashMap<>(OnboardingServiceProof.verify(
                devAppKeyRef, actor, method, pathAndQuery, body, proof));
        audit.put("mode", "DEV_ONBOARDING");
        audit.put("action", action);
        audit.put("actor", Map.of(
                "tenantId", actor.tenantId(), "principalId", actor.principalId(),
                "roles", actor.roles().stream().sorted().toList()));
        return new Access(actor, audit);
    }

    private static void requireRoles(Actor actor) {
        if (!actor.roles().contains("SERVICE"))
            throw error(403, "ONBOARDING_IMPORT_FORBIDDEN", "接入导入仅供当前获授权的应用服务调用");
        // ADMIN 保持平台既有的 EDITOR 上位语义；SERVICE 和 app caller 仍须分别成立。
        actor.requireRole("EDITOR");
    }

    private static void requireApp(VerifiedAuthority current, String action) {
        if (!"app".equals(current.caller())
                || !"skill".equals(current.call().target())
                || !action.equals(current.call().action())
                || current.call().resource() == null
                || current.operation() == null)
            throw error(403, "ONBOARDING_IMPORT_FORBIDDEN", "接入导入调用方或当前授权能力不匹配");
    }

    private ImportPlan plan(Map<String, Object> body) {
        exactKeys(body, BODY_KEYS, "导入请求");
        var source = new LinkedHashMap<>(object(body.get("source"), "source"));
        exactKeys(source, SOURCE_KEYS, "source");
        String sessionId = text(source.get("sessionId"), "sessionId", 40);
        sessionId(sessionId);
        String planSha = sha(source.get("planSha256"), "planSha256");
        String templateCode = text(source.get("templateCode"), "templateCode", 64);
        if (!templateCode.matches("[a-z][a-z0-9_]{1,63}")) throw invalid("templateCode 格式无效");
        revision(source.get("templateRev"), "templateRev");
        positive(source.get("packageVersionId"), "packageVersionId");
        sha(source.get("packageSha256"), "packageSha256");
        String documentSha = sha(source.get("documentSha256"), "documentSha256");
        String document = text(body.get("document"), "document", 1_048_576);
        if (document.getBytes(StandardCharsets.UTF_8).length > 1_048_576)
            throw error(413, "OPENAPI_SIZE", "OpenAPI 文档最大1MiB");
        if (!documentSha.equals(Digest.sha256(document)))
            throw error(409, "ONBOARDING_DOCUMENT_CONFLICT", "文档与应用冻结的摘要不同");

        if (!(body.get("includeOps") instanceof List<?> selected)
                || selected.isEmpty() || selected.size() > 100)
            throw invalid("请选择1至100个不重复的 operationId");
        var operations = new ArrayList<String>();
        for (Object operation : selected) operations.add(text(operation, "operationId", 256));
        if (new LinkedHashSet<>(operations).size() != operations.size())
            throw invalid("operationId 不能重复");

        var defaults = object(body.get("defaults"), "defaults");
        exactKeys(defaults, Set.of("baseUrl"), "defaults");
        text(defaults.get("baseUrl"), "baseUrl", 4096);
        var supplied = object(body.get("overrides"), "overrides");
        exactKeys(supplied, Set.copyOf(operations), "overrides");
        var overrides = new LinkedHashMap<String, Object>();
        for (String operation : operations) {
            var manual = new LinkedHashMap<>(object(supplied.get(operation), "operation override"));
            if (!OVERRIDE_KEYS.containsAll(manual.keySet())
                    || !manual.keySet().containsAll(Set.of(
                            "name", "description", "execution", "guard", "returnSpec", "errorSemantics")))
                throw invalid("操作须显式提供名称、说明、执行、守卫、返回及失败语义；不能覆盖导入来源或编码");
            manual.put("code", codeFor(sessionId, planSha, operation));
            overrides.put(operation, manual);
        }

        List<Map<String, Object>> refs = bindingRefs(body.get("bindingRefs"), operations);
        var importBody = new LinkedHashMap<String, Object>();
        importBody.put("document", document);
        importBody.put("includeOps", operations);
        importBody.put("defaults", defaults);
        importBody.put("overrides", overrides);
        return new ImportPlan(
                source, List.copyOf(operations), refs, importBody,
                Digest.sha256(Json.canonical(source)), Digest.sha256(Json.canonical(body)));
    }

    private static List<Map<String, Object>> bindingRefs(Object raw, List<String> operations) {
        if (!(raw instanceof List<?> list) || list.size() != operations.size())
            throw invalid("每个操作必须固定一个批准连接引用");
        var refs = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();
        for (Object item : list) {
            var ref = new LinkedHashMap<>(object(item, "bindingRef"));
            if (!REF_KEYS.containsAll(ref.keySet()) || !ref.keySet().containsAll(REF_REQUIRED))
                throw invalid("连接引用仅接受精确档案、绑定修订、操作、环境和可选摘要");
            for (String field : List.of("profileId", "bindingId", "environment"))
                text(ref.get(field), field, 64);
            for (String field : List.of("profileId", "bindingId"))
                if (!ref.get(field).toString().matches("[a-z][a-z0-9-]{2,47}"))
                    throw invalid(field + " 格式无效");
            if (!ref.get("environment").toString().matches("[a-z][a-z0-9-]{0,31}"))
                throw invalid("environment 格式无效");
            revision(ref.get("profileRev"), "profileRev");
            revision(ref.get("bindingRev"), "bindingRev");
            String operation = text(ref.get("operation"), "operation", 256);
            if (!operations.contains(operation) || !seen.add(operation))
                throw invalid("连接引用须与选中操作逐项一致且不重复");
            if (ref.containsKey("resolutionDigest")) sha(ref.get("resolutionDigest"), "resolutionDigest");
            refs.add(ref);
        }
        return List.copyOf(refs);
    }

    private static void assertReplay(Actor actor, ImportPlan plan, List<ImportReceipt> stored) {
        assertOwner(actor, stored);
        if (stored.size() != plan.operations().size()
                || stored.stream().anyMatch(row -> !row.requestSha256().equals(plan.requestSha256())
                        || !row.sourceSha256().equals(plan.sourceSha256())
                        || !plan.operations().contains(row.operationId())))
            throw error(409, "ONBOARDING_IMPORT_CONFLICT", "该计划已有不同导入请求，请读取原回执或保存新计划");
    }

    private static void assertOwner(Actor actor, List<ImportReceipt> stored) {
        if (stored.stream().anyMatch(row -> !actor.principalId().equals(row.principalId())))
            throw error(403, "ONBOARDING_IMPORT_FORBIDDEN", "导入回执不属于当前操作者");
    }

    private Map<String, Object> response(Actor actor, List<ImportReceipt> stored, boolean replayed) {
        assertOwner(actor, stored);
        ImportReceipt first = stored.getFirst();
        stored.stream().sorted(Comparator.comparing(ImportReceipt::code))
                .forEach(row -> receipts.lockSkill(actor, row));
        var drafts = new ArrayList<Map<String, Object>>();
        var facts = new ArrayList<Map<String, Object>>();
        var bindingRefs = new ArrayList<Map<String, Object>>();
        for (ImportReceipt receipt : stored) {
            if (!receipt.sourceSha256().equals(Digest.sha256(Json.canonical(receipt.source())))
                    || !first.requestSha256().equals(receipt.requestSha256())
                    || !first.sourceSha256().equals(receipt.sourceSha256())
                    || receipt.bindingRefs().size() != 1
                    || !receipt.operationId().equals(receipt.bindingRefs().getFirst().get("operation")))
                throw error(409, "ONBOARDING_IMPORT_CONFLICT", "导入来源回执不一致");
            bindingRefs.addAll(receipt.bindingRefs());
            var actual = catalog.version(receipt.code(), receipt.version());
            if (((Number) actual.get("skillVersionId")).longValue() != receipt.skillVersionId()
                    || !receipt.sha256().equals(actual.get("sha256"))
                    || !receipt.sha256().equals(Digest.sha256(Json.canonical(actual.get("definition"))))
                    || !Set.of("DRAFT", "PUBLISHED").contains(actual.get("status"))
                    || "DISABLED".equals(actual.get("skillStatus")))
                throw error(409, "ONBOARDING_IMPORTED_SKILL_CHANGED", "原导入技能已被修改或停用，未创建替代草稿");
            assertImportedSource(actual, receipt.operationId(), receipt.source().get("documentSha256"));
            var draft = new LinkedHashMap<String, Object>();
            draft.put("operationId", receipt.operationId());
            draft.put("code", receipt.code());
            draft.put("version", receipt.version());
            draft.put("skillVersionId", receipt.skillVersionId());
            draft.put("sha256", receipt.sha256());
            var fact = new LinkedHashMap<>(draft);
            fact.put("receiptId", receipt.receiptId());
            facts.add(fact);
            draft.put("status", actual.get("status"));
            draft.put("definition", actual.get("definition"));
            drafts.add(draft);
        }
        var sourceReceipt = new LinkedHashMap<String, Object>();
        sourceReceipt.put("source", first.source());
        sourceReceipt.put("sourceSha256", first.sourceSha256());
        sourceReceipt.put("requestSha256", first.requestSha256());
        sourceReceipt.put("bindingRefs", bindingRefs);
        sourceReceipt.put("authority", first.authority());
        sourceReceipt.put("imports", facts);
        return Map.of("drafts", drafts, "sourceReceipt", sourceReceipt, "replayed", replayed);
    }

    private static void assertImportedSource(Map<String, Object> actual, String operation, Object documentSha) {
        var definition = object(actual.get("definition"), "stored definition");
        var source = object(definition.get("importSource"), "stored importSource");
        if (!"OPENAPI".equals(source.get("kind")) || !operation.equals(source.get("operationId"))
                || !documentSha.equals(source.get("documentSha256")))
            throw error(409, "ONBOARDING_IMPORTED_SKILL_CHANGED", "实际技能来源与冻结操作不同");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String)))
            throw invalid(label + " 必须是JSON对象");
        return (Map<String, Object>) map;
    }

    private static void exactKeys(Map<String, Object> map, Set<String> keys, String label) {
        if (!map.keySet().equals(keys)) throw invalid(label + " 字段与接口契约不一致");
    }

    private static String text(Object raw, String label, int max) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > max)
            throw invalid(label + " 缺失或无效");
        return value;
    }

    private static String sha(Object raw, String label) {
        String value = text(raw, label, 64);
        if (!value.matches("[a-f0-9]{64}")) throw invalid(label + " 须为小写SHA-256");
        return value;
    }

    private static void sessionId(String value) {
        if (!value.matches("os_[a-f0-9]{32}")) throw invalid("sessionId 格式无效");
    }

    private static void positive(Object raw, String label) {
        var number = Json.tree(raw);
        if (!number.isIntegralNumber() || !number.canConvertToLong() || number.longValue() < 1)
            throw invalid(label + " 须为精确正整数");
    }

    private static void revision(Object raw, String label) {
        positive(raw, label);
        if (!Json.tree(raw).canConvertToInt()) throw invalid(label + " 超出修订范围");
    }

    private static PlatformException invalid(String message) {
        return error(400, "ONBOARDING_IMPORT_INPUT", message);
    }

    private static PlatformException error(int status, String code, String message) {
        return new PlatformException(status, code, message);
    }
}
