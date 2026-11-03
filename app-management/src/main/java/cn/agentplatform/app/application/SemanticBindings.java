package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.SemanticBinding;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.SemanticBindingRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用语义绑定目录（SEM-02.2 阶段1）。
 *
 * <p>只做三件事：把声明写成不可变修订、按 {@code expectedRev} 做 CAS、按 {@code requestId}
 * 做重放。这里不调用 OR、不校验 release 是否真实存在，也不与 ConfigRelease 组合——那些是
 * 阶段2的精确性校验；本阶段的职责是让 ADMIN 能先登记"应用要引用哪份扩展/技能版本"，并保证
 * 同一份声明不会因为重试被写两次、旧声明不会被原地改写。
 */
@Service
public class SemanticBindings {

    private final SemanticBindingRepository store;
    private final ConfigWriteLog writes;

    public SemanticBindings(SemanticBindingRepository store, ConfigWriteLog writes) {
        this.store = store;
        this.writes = writes;
    }

    public List<Map<String, Object>> list(String appCode) {
        Actor actor = Actor.current();
        if (appCode == null || appCode.isBlank()) {
            throw SemanticBinding.field("appCode", "必填");
        }
        return store.list(actor, appCode);
    }

    public Map<String, Object> get(String bindingId) {
        Actor actor = Actor.current();
        return store.view(actor, bindingId)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND",
                        "语义绑定不存在: " + bindingId));
    }

    public Map<String, Object> create(Map<String, Object> body) {
        Actor actor = Actor.current();
        SemanticBinding binding = SemanticBinding.parse(body, true, null);
        String appCode = SemanticBinding.appCode(body);
        if (!store.appExists(actor, appCode)) {
            throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
        }
        if (body.containsKey("expectedRev")) {
            throw SemanticBinding.field("expectedRev", "新建绑定不接受 expectedRev，首次写入的当前修订恒为 0");
        }
        String requestId = ConnectionProfiles.requestId(body);
        String sha = Digest.sha256(Json.canonical(List.of("semantic-binding.create", appCode,
                binding.sha256())));
        Map<String, Object> replayed = writes.replay(actor.tenantId(), "semantic-binding.create",
                binding.bindingId(), requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        store.ensure(actor, appCode, binding.bindingId());
        Map<String, Object> out = store.append(actor, appCode, binding, 0, requestId,
                () -> view(actor, binding.bindingId()));
        writes.remember(actor.tenantId(), "semantic-binding.create", binding.bindingId(), requestId, sha, out);
        return out;
    }

    public Map<String, Object> revise(String bindingId, Map<String, Object> body) {
        Actor actor = Actor.current();
        SemanticBinding binding = SemanticBinding.parse(body, false, bindingId);
        int expectedRev = ConnectionProfiles.expectedRev(body);
        String requestId = ConnectionProfiles.requestId(body);
        Map<String, Object> current = store.view(actor, bindingId)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND",
                        "语义绑定不存在: " + bindingId));
        String appCode = String.valueOf(current.get("appCode"));
        String sha = Digest.sha256(Json.canonical(List.of("semantic-binding.revise", appCode, bindingId,
                expectedRev, binding.sha256())));
        Map<String, Object> replayed = writes.replay(actor.tenantId(), "semantic-binding.revise",
                bindingId, requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        Map<String, Object> out = store.append(actor, appCode, binding, expectedRev, requestId,
                () -> view(actor, bindingId));
        writes.remember(actor.tenantId(), "semantic-binding.revise", bindingId, requestId, sha, out);
        return out;
    }

    public Map<String, Object> disable(String bindingId, Map<String, Object> body) {
        Actor actor = Actor.current();
        ConnectionProfiles.keys(body, java.util.Set.of("expectedRev", "requestId"), "语义绑定");
        int expectedRev = ConnectionProfiles.expectedRev(body);
        String requestId = ConnectionProfiles.requestId(body);
        String sha = Digest.sha256(Json.canonical(List.of("semantic-binding.disable", bindingId, expectedRev)));
        Map<String, Object> replayed = writes.replay(actor.tenantId(), "semantic-binding.disable",
                bindingId, requestId, sha);
        if (replayed != null) {
            replayed.put("replayed", true);
            return replayed;
        }
        Map<String, Object> out = store.disable(actor, bindingId, expectedRev, requestId,
                () -> view(actor, bindingId));
        writes.remember(actor.tenantId(), "semantic-binding.disable", bindingId, requestId, sha, out);
        return out;
    }

    private Map<String, Object> view(Actor actor, String bindingId) {
        return new LinkedHashMap<>(store.view(actor, bindingId)
                .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND",
                        "语义绑定不存在: " + bindingId)));
    }
}
