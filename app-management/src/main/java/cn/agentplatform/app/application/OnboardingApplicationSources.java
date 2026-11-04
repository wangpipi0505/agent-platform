package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.OnboardingImportSourceRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 会话专属来源的唯一写入/保存校验边界，数据库关系而非客户端标签选择该分支。 */
@Service
public class OnboardingApplicationSources {
    private final OnboardingImportSourceRepository repository;

    public OnboardingApplicationSources(OnboardingImportSourceRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> prepare(Map<String, Object> session, Map<String, Object> packageSource,
            Map<String, Object> prepared, Map<String, Object> request) {
        var source = new LinkedHashMap<String, Object>();
        source.put("sessionId", session.get("sessionId"));
        source.put("planSha256", session.get("planSha256"));
        source.put("templateCode", session.get("templateCode"));
        source.put("templateRev", session.get("templateRev"));
        source.put("packageVersionId", packageSource.get("packageVersionId"));
        source.put("packageSha256", packageSource.get("sha256"));
        source.put("documentSha256", prepared.get("documentSha256"));
        var payload = new LinkedHashMap<>(request);
        payload.put("source", source);
        if (!Objects.equals(prepared.get("documentSha256"), Digest.sha256(payload.get("document").toString())))
            throw conflict("原始规格摘要与预检不同");
        var fixedPackage = new LinkedHashMap<String, Object>();
        for (String key : List.of("packageVersionId", "packageId", "version", "sha256", "softwareId", "softwareVersion", "declaration"))
            if (packageSource.containsKey(key)) fixedPackage.put(key, packageSource.get(key));
        return repository.freeze(session, fixedPackage, prepared, payload);
    }

    /** 只承认从技能服务读回的真实发布版本；原包全局 mapping/receipt 不改。 */
    public Map<String, Object> resolve(Map<String, Object> session, List<Map<String, Object>> actualPublished) {
        var row = sourceRow(session);
        var snapshot = object(row.get("snapshot"));
        var pkg = object(snapshot.get("package"));
        var prepared = object(snapshot.get("prepared"));
        var route = object(prepared.get("routePlan"));
        var bindings = new ArrayList<Map<String, Object>>();
        var operations = AppService.objects(prepared.get("operations"));
        if (operations.isEmpty() || operations.size() != actualPublished.size())
            throw conflict("实际发布技能集合与固定操作集合不同");
        for (var operation : operations) {
            String operationId = operation.get("operationId").toString();
            String expectedCode = "onb_" + Digest.sha256(Json.canonical(List.of(
                    row.get("sessionId"), row.get("planSha256"), operationId))).substring(0, 48);
            var matches = actualPublished.stream().filter(actual -> expectedCode.equals(actual.get("code"))).toList();
            if (matches.size() != 1) throw conflict("实际技能编码与服务端固定导入操作不同");
            var actual = matches.getFirst();
            if (!"PUBLISHED".equals(actual.get("status")))
                throw new PlatformException(409, "SKILL_NOT_PUBLISHED", "先在原技能工作台完成真实验证、选型验收与发布");
            bindings.add(OnboardingImportRules.mapping(object(pkg.get("declaration")), route, operation, actual));
        }
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("environment", route.get("environment"));
        mapping.put("environmentDeclaration", cn.agentplatform.app.domain.CapabilityBinding
                .select(object(pkg.get("declaration")), route).environmentDeclaration());
        mapping.put("routeProfileId", route.get("routeProfileId"));
        mapping.put("bindings", bindings);
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("packageVersionId", "packageId", "version", "sha256", "softwareId", "softwareVersion"))
            if (pkg.containsKey(key)) result.put(key, pkg.get(key));
        result.putAll(mapping);
        result.put("mappingSha256", Digest.sha256(Json.canonical(mapping)));
        result.put("sessionSourceId", row.get("sourceId"));
        for (String key : List.of("sessionId", "planSha256", "documentSha256")) result.put(key, row.get(key));
        result.put("templateCode", session.get("templateCode"));
        result.put("templateRev", session.get("templateRev"));
        return repository.resolve(Actor.current(), row.get("sourceId").toString(), result);
    }

    public Map<String, Object> resolved(Map<String, Object> session) {
        var row = repository.get(Actor.current(), session.get("sessionId").toString(), session.get("planSha256").toString());
        return row == null || !"RESOLVED".equals(row.get("status")) ? null : object(row.get("resolvedSource"));
    }

    public Map<String, Object> requireSource(String id) {
        var row = repository.require(Actor.current(), id);
        if (!"RESOLVED".equals(row.get("status"))) throw conflict("来源尚未固化");
        return object(row.get("resolvedSource"));
    }

    public void bind(Map<String, Object> app, String sourceId) { repository.bind(Actor.current(), app, sourceId); }

    /** 返回false才由原全局包分支继续；新分支不能通过删除配置字段逃离来源校验。 */
    public boolean assertIfPresent(Map<String, Object> app) {
        var rows = repository.applicationBindings(Actor.current(), app);
        if (rows.stream().noneMatch(row -> row.get("session_source_id") != null)) return false;
        var expected = new ArrayList<Map<String, Object>>();
        var executionRefs = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var binding = object(row.get("binding_source"));
            if (row.get("session_source_id") == null) {
                if (!"RESOLVED".equals(row.get("binding_status"))
                        || !Objects.equals(binding.get("sha256"), row.get("package_sha256"))
                        || !Objects.equals(binding.get("mappingSha256"), Digest.sha256(Json.canonical(object(row.get("resolved_mapping"))))))
                    throw conflict("既有能力包解析来源不一致");
            } else {
                var source = object(row.get("resolved_source"));
                if (!"RESOLVED".equals(row.get("source_status"))
                        || !Objects.equals(row.get("source_package_id"), row.get("package_version_id"))
                        || !Objects.equals(row.get("source_package_sha"), row.get("package_sha256"))
                        || !Objects.equals(source.get("sessionSourceId"), row.get("session_source_id"))
                        || !Objects.equals(source.get("planSha256"), row.get("plan_sha256"))
                        || !Objects.equals(source.get("documentSha256"), row.get("document_sha256"))
                        || !Json.canonical(binding).equals(Json.canonical(source)))
                    throw conflict("应用与不可变会话来源关系不一致");
                var request = object(object(row.get("snapshot")).get("importRequest"));
                executionRefs.addAll(AppService.objects(request.get("bindingRefs")));
            }
            expected.add(binding);
        }
        var config = object(app.get("config"));
        if (!Json.canonical(expected).equals(Json.canonical(config.get("capabilityPackages")))
                || !Json.canonical(executionRefs).equals(Json.canonical(config.get("bindingRefs"))))
            throw conflict("不能删除、扩充或替换本应用的固定来源与执行连接");
        AppService.checkCapabilityReferences(config, object(app.get("definition")));
        return true;
    }

    private Map<String, Object> sourceRow(Map<String, Object> session) {
        var row = repository.get(Actor.current(), session.get("sessionId").toString(), session.get("planSha256").toString());
        if (row == null) throw conflict("当前计划尚无固定导入来源");
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (value instanceof String text) return Json.read(text, Map.class);
        throw conflict("来源结构不完整");
    }
    private static PlatformException conflict(String message) {
        return new PlatformException(409, "ONBOARDING_SOURCE_CONFLICT", message);
    }
}
