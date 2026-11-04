package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.OnboardingTemplate;
import cn.agentplatform.app.infra.CapabilityPackageQueryRepository;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.OnboardingTemplateRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

/**
 * ONB-01：业务接入模板目录的编辑面。
 *
 * <p>模板只做“包装引用”：能力包精确版本 + 体验修订 + 接入档案三元组（业务系统/环境/等级）。
 * 发布前按 ConfigRelease 的口径复核全部引用仍然有效；不复制连接、Schema 或模板执行引擎，
 * 因此模板不会扩大底层能力。
 */
@Service
public class OnboardingTemplates {

    static final Set<String> BODY_KEYS = Set.of(
            "code", "name", "kind", "description", "sourcePackageVersionId", "sourcePackageSha256",
            "appCode", "experience", "integration", "expectedRev", "requestId");
    static final Set<String> PREFLIGHT_KEYS = Set.of("document", "connection", "requestId");
    private static final Set<String> TRANSITION_KEYS = Set.of("expectedRev", "requestId");
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern ENVIRONMENT = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Set<String> MODES = Set.of("L0", "L1", "L2", "L3");

    private final OnboardingTemplateRepository store;
    private final ConfigWriteLog writes;
    private final AssistantExperiences experiences;
    private final CapabilityPackageQueryRepository packages;
    private final OnboardingPreflightService preflight;

    public OnboardingTemplates(
            OnboardingTemplateRepository store,
            ConfigWriteLog writes,
            AssistantExperiences experiences,
            CapabilityPackageQueryRepository packages,
            OnboardingPreflightService preflight) {
        this.store = store;
        this.writes = writes;
        this.experiences = experiences;
        this.packages = packages;
        this.preflight = preflight;
    }

    public List<Map<String, Object>> list(String q, String kind) {
        if (kind != null && !kind.isBlank() && !OnboardingTemplate.KINDS.contains(kind)) {
            throw ConnectionProfiles.invalid("未知模板种类: " + kind);
        }
        if (q != null && q.length() > 128) {
            throw ConnectionProfiles.invalid("查询词过长");
        }
        return store.list(Actor.current(), q, kind);
    }

    public Map<String, Object> get(String code) {
        return get(Actor.current(), code);
    }

    Map<String, Object> get(Actor actor, String code) {
        return store.view(actor, code)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入模板不存在: " + code));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        return create(Actor.current(), body);
    }

    Map<String, Object> create(Actor actor, Map<String, Object> body) {
        ConnectionProfiles.keys(body, BODY_KEYS, "接入模板");
        if (body.containsKey("expectedRev")) {
            throw ConnectionProfiles.invalid("新建接入模板不接受 expectedRev，首次写入的当前修订恒为 1");
        }
        String code = ConnectionProfiles.text(body, "code", "模板 code", 64, CODE);
        OnboardingTemplate declaration = declaration(code, body);
        String requestId = ConnectionProfiles.requestId(body);
        String sha = Digest.sha256(Json.canonical(List.of(
                "onboarding-template.create", code, declaration.declarationSha256())));
        Map<String, Object> replayed = writes.replay(
                actor.tenantId(), "onboarding-template.create", code, requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        Map<String, Object> out = store.create(actor, code, declaration, requestId,
                () -> store.view(actor, code).orElseThrow());
        writes.remember(actor.tenantId(), "onboarding-template.create", code, requestId, sha, out);
        return out;
    }

    public Map<String, Object> revise(String code, Map<String, Object> body) {
        return revise(Actor.current(), code, body);
    }

    Map<String, Object> revise(Actor actor, String code, Map<String, Object> body) {
        ConnectionProfiles.keys(body, BODY_KEYS, "接入模板");
        Object rawCode = body.get("code");
        if (rawCode != null && !code.equals(String.valueOf(rawCode))) {
            throw ConnectionProfiles.invalid("路径与请求体的模板 code 不一致");
        }
        int expectedRev = ConnectionProfiles.expectedRev(body);
        OnboardingTemplate declaration = declaration(code, body);
        String requestId = ConnectionProfiles.requestId(body);
        String sha = Digest.sha256(Json.canonical(List.of(
                "onboarding-template.revise", code, expectedRev, declaration.declarationSha256())));
        Map<String, Object> replayed = writes.replay(
                actor.tenantId(), "onboarding-template.revise", code, requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        Map<String, Object> out = store.revise(actor, code, expectedRev, declaration, requestId,
                () -> store.view(actor, code).orElseThrow());
        writes.remember(actor.tenantId(), "onboarding-template.revise", code, requestId, sha, out);
        return out;
    }

    public Map<String, Object> publish(String code, Map<String, Object> body) {
        return publish(Actor.current(), code, body);
    }

    Map<String, Object> publish(Actor actor, String code, Map<String, Object> body) {
        ConnectionProfiles.keys(body, TRANSITION_KEYS, "接入模板");
        int expectedRev = ConnectionProfiles.expectedRev(body);
        OnboardingTemplateRepository.State state = requireRevision(actor, code, expectedRev);
        if (!"DRAFT".equals(state.status())) {
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "状态 " + state.status() + " 不能发布，只有 DRAFT 可以发布");
        }
        Map<String, Object> revision = store.revision(actor, code, expectedRev);
        requireReferences(actor, revision);
        String requestId = ConnectionProfiles.requestId(body);
        String sha = Digest.sha256(Json.canonical(List.of(
                "onboarding-template.publish", code, expectedRev)));
        Map<String, Object> replayed = writes.replay(
                actor.tenantId(), "onboarding-template.publish", code, requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        Map<String, Object> out = store.transition(actor, code, expectedRev, "DRAFT", "PUBLISHED", requestId,
                () -> store.view(actor, code).orElseThrow());
        writes.remember(actor.tenantId(), "onboarding-template.publish", code, requestId, sha, out);
        return out;
    }

    public Map<String, Object> disable(String code, Map<String, Object> body) {
        return disable(Actor.current(), code, body);
    }

    Map<String, Object> disable(Actor actor, String code, Map<String, Object> body) {
        ConnectionProfiles.keys(body, TRANSITION_KEYS, "接入模板");
        int expectedRev = ConnectionProfiles.expectedRev(body);
        OnboardingTemplateRepository.State state = requireRevision(actor, code, expectedRev);
        if (!Set.of("DRAFT", "PUBLISHED").contains(state.status())) {
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "状态 " + state.status() + " 不能停用");
        }
        String requestId = ConnectionProfiles.requestId(body);
        String sha = Digest.sha256(Json.canonical(List.of(
                "onboarding-template.disable", code, expectedRev)));
        Map<String, Object> replayed = writes.replay(
                actor.tenantId(), "onboarding-template.disable", code, requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        Map<String, Object> out = store.transition(actor, code, expectedRev, state.status(), "DISABLED",
                requestId, () -> store.view(actor, code).orElseThrow());
        writes.remember(actor.tenantId(), "onboarding-template.disable", code, requestId, sha, out);
        return out;
    }

    /**
     * 服务端预检：接受模板版本与原始规格文本，返回逐操作支持子集与规范化计划摘要。
     * 只读探测，不生成技能/应用；上游不可达时明确失败而不是伪造“全部不支持”。
     */
    public Map<String, Object> preflight(String code, int version, Map<String, Object> body) {
        return preflight(Actor.current(), code, version, body);
    }

    Map<String, Object> preflight(Actor actor, String code, int version, Map<String, Object> body) {
        ConnectionProfiles.keys(body, PREFLIGHT_KEYS, "接入模板预检");
        OnboardingTemplateRepository.State state = requireRevision(actor, code, version);
        if ("DISABLED".equals(state.status())) {
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "已停用的接入模板不允许预检");
        }
        Map<String, Object> revision = store.revision(actor, code, version);
        Object document = body.get("document");
        if (!(document instanceof String text) || text.isBlank()) {
            throw ConnectionProfiles.invalid("缺少 OpenAPI 规格文本");
        }
        Map<String, Object> connection = body.get("connection") == null
                ? Map.of()
                : map(body.get("connection"), "connection");
        Map<String, Object> preview = preflight.preview(actor, text);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) preview.get("operations");
        String planSha256 = Digest.sha256(Json.canonical(List.of(
                "onboarding-template.preflight",
                code,
                version,
                String.valueOf(revision.get("declarationSha256")),
                String.valueOf(preview.get("documentSha256")),
                OnboardingPreflightService.planMaterial(operations),
                connection)));
        preview.put("templateCode", code);
        preview.put("templateVersion", version);
        preview.put("declarationSha256", revision.get("declarationSha256"));
        preview.put("planSha256", planSha256);
        return preview;
    }

    private OnboardingTemplateRepository.State requireRevision(Actor actor, String code, int version) {
        OnboardingTemplateRepository.State state = store.state(actor, code, version);
        if (state == null) {
            throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入模板不存在: " + code);
        }
        if (state.currentRev() != version) {
            throw new PlatformException(409, "CONFIG_REV_CONFLICT",
                    "接入模板修订已变化，请重新读取: 期望 " + version + "，当前 " + state.currentRev());
        }
        if (!state.revisionExists()) {
            throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
                    "接入模板修订不存在: " + code + "@" + version);
        }
        return state;
    }

    /** 发布前引用复核：能力包精确摘要、体验修订、接入档案三元组都必须仍然有效。 */
    private void requireReferences(Actor actor, Map<String, Object> revision) {
        long packageVersionId = ((Number) revision.get("sourcePackageVersionId")).longValue();
        String expectedSha = String.valueOf(revision.get("sourcePackageSha256"));
        Map<String, Object> packageDetail;
        try {
            packageDetail = packages.detail(actor.tenantId(), packageVersionId);
        } catch (PlatformException e) {
            if ("CAPABILITY_PACKAGE_NOT_FOUND".equals(e.code())) {
                throw new PlatformException(404, "CONFIG_REF_NOT_FOUND",
                        "能力包版本不存在: " + packageVersionId);
            }
            throw e;
        }
        if (!expectedSha.equals(String.valueOf(packageDetail.get("sha256")))) {
            throw new PlatformException(400, "INVALID_INPUT",
                    "能力包摘要与模板登记不一致: " + packageVersionId);
        }
        String appCode = String.valueOf(revision.get("appCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> experience = (Map<String, Object>) revision.get("experience");
        experiences.requireApproved(actor, appCode, ((Number) experience.get("experienceRev")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> integration = (Map<String, Object>) revision.get("integration");
        if (!store.approvedIntegrationRevisionExists(actor, appCode,
                String.valueOf(integration.get("bizSystemCode")),
                String.valueOf(integration.get("environment")),
                String.valueOf(integration.get("mode")))) {
            throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                    "模板引用的接入档案修订不存在或未批准: " + integration.get("bizSystemCode"));
        }
    }

    private OnboardingTemplate declaration(String code, Map<String, Object> body) {
        String name = ConnectionProfiles.text(body, "name", "模板名称", 128);
        String kind = ConnectionProfiles.text(body, "kind", "模板种类", 32);
        if (!OnboardingTemplate.KINDS.contains(kind)) {
            throw ConnectionProfiles.invalid("未知模板种类: " + kind);
        }
        if (!OnboardingTemplate.SELECTABLE_KINDS.contains(kind)) {
            throw ConnectionProfiles.invalid(OnboardingTemplate.OPEN_KIND_REASON);
        }
        String description = "";
        Object rawDescription = body.get("description");
        if (rawDescription != null) {
            if (!(rawDescription instanceof String text) || text.length() > 2048) {
                throw ConnectionProfiles.invalid("模板说明必须是不超过 2048 字符的字符串");
            }
            description = text;
        }
        Object rawPackage = body.get("sourcePackageVersionId");
        if (!(rawPackage instanceof Number number) || number.longValue() < 1
                || number.longValue() != number.doubleValue()) {
            throw ConnectionProfiles.invalid("缺少或无效字段: sourcePackageVersionId（必须为正整数）");
        }
        long packageVersionId = number.longValue();
        String packageSha = ConnectionProfiles.text(body, "sourcePackageSha256", "能力包摘要", 64, SHA);
        String appCode = ConnectionProfiles.text(body, "appCode", "appCode", 64, CODE);
        Map<String, Object> experience = map(body.get("experience"), "experience");
        Map<String, Object> integration = map(body.get("integration"), "integration");
        if (!experience.keySet().equals(Set.of("appCode", "experienceRev"))) {
            throw ConnectionProfiles.invalid("experience 只允许 appCode 与 experienceRev 两个字段");
        }
        String experienceAppCode = String.valueOf(experience.get("appCode"));
        if (!CODE.matcher(experienceAppCode).matches()) {
            throw ConnectionProfiles.invalid("experience.appCode 格式不合法");
        }
        if (!experienceAppCode.equals(appCode)) {
            throw ConnectionProfiles.invalid("体验描述必须属于同一应用: " + appCode);
        }
        Object rawExperienceRev = experience.get("experienceRev");
        if (!(rawExperienceRev instanceof Number revision) || revision.intValue() < 1
                || revision.intValue() != revision.doubleValue()) {
            throw ConnectionProfiles.invalid("experience.experienceRev 必须为正整数");
        }
        int experienceRev = revision.intValue();
        if (!integration.keySet().equals(Set.of("bizSystemCode", "environment", "mode"))) {
            throw ConnectionProfiles.invalid("integration 只允许 bizSystemCode/environment/mode 三个字段");
        }
        String bizSystemCode = ConnectionProfiles.text(integration, "bizSystemCode", "业务系统编码", 64);
        String environment = ConnectionProfiles.text(integration, "environment", "环境", 32, ENVIRONMENT);
        String mode = String.valueOf(integration.get("mode"));
        if (!MODES.contains(mode)) {
            throw ConnectionProfiles.invalid("未知接入等级: " + mode);
        }
        String sha = OnboardingTemplate.declarationSha(kind, name, description, packageVersionId,
                packageSha, appCode, experienceAppCode, experienceRev, bizSystemCode, environment, mode);
        return new OnboardingTemplate(code, 0, kind, name, description, packageVersionId, packageSha,
                appCode, experienceAppCode, experienceRev, bizSystemCode, environment, mode, sha);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw, String field) {
        if (raw == null) {
            throw ConnectionProfiles.invalid("缺少字段: " + field);
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw ConnectionProfiles.invalid(field + " 必须是对象");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        ((Map<Object, Object>) raw).forEach((key, item) -> value.put(String.valueOf(key), item));
        return value;
    }
}
