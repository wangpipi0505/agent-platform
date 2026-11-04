package cn.agentplatform.skill.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.function.BiFunction;

/** 当前已验主体只能使用部署登记的逐主体、逐版本secret_ref；正文不能选择凭据或代替身份。 */
@Component
public class OntologyConsumeIdentity {
    private final JsonNode bindings;
    private final BiFunction<String, Actor, String> secrets;

    @org.springframework.beans.factory.annotation.Autowired
    public OntologyConsumeIdentity(@Value("${agent.ontology.consume-identities:[]}") String bindings) {
        this(bindings, SecretVault::resolve);
    }

    OntologyConsumeIdentity(String bindings, BiFunction<String, Actor, String> secrets) {
        this.bindings = Json.read(bindings);
        if (!this.bindings.isArray()) throw new IllegalArgumentException("consume-identities必须为数组");
        this.secrets = secrets;
    }

    /** 只列当前已验主体登记的版本候选；不解析、不返回secret_ref或凭据。 */
    public java.util.Set<UUID> releaseIds(Actor actor) {
        var result = new java.util.LinkedHashSet<UUID>();
        for (JsonNode binding : bindings) {
            if (!actor.tenantId().equals(binding.path("tenantId").asText())
                    || !actor.principalId().equals(binding.path("principalId").asText())) continue;
            try { result.add(UUID.fromString(binding.path("releaseId").asText())); }
            catch (IllegalArgumentException invalid) { throw denied(); }
        }
        return java.util.Collections.unmodifiableSet(result);
    }

    public String key(Actor actor, UUID release) {
        JsonNode found = null;
        for (JsonNode binding : bindings) {
            if (!actor.tenantId().equals(binding.path("tenantId").asText())
                    || !actor.principalId().equals(binding.path("principalId").asText())
                    || !release.toString().equals(binding.path("releaseId").asText())) continue;
            if (found != null) throw denied();
            found = binding;
        }
        if (found == null) throw denied();
        String ref = found.path("secretRef").asText();
        if (!(ref.matches("vault:[0-9a-fA-F-]{36}") || ref.matches("env:AGENT_SECRET_[A-Z0-9_]+")))
            throw denied();
        String key = secrets.apply(ref, actor);
        if (key == null || !key.matches("ontopa_[A-Za-z0-9_-]{43}")) throw denied();
        return key;
    }

    /** OR回查结果必须证明该凭据确实映射当前主体；错用户、错版本和畸形投影均默认拒绝。 */
    public void verify(Actor actor, UUID release, JsonNode identity) {
        if (identity == null || !actor.tenantId().equals(identity.path("platformTenant").asText())
                || !actor.principalId().equals(identity.path("platformActor").asText())
                || !release.toString().equals(identity.path("releaseId").asText())) throw denied();
        try {
            for (String field : new String[]{"spaceId", "principalId", "credentialId"})
                UUID.fromString(identity.path(field).asText());
        } catch (IllegalArgumentException invalid) { throw denied(); }
    }

    private static PlatformException denied() {
        return new PlatformException(403, "ONTOLOGY_IDENTITY_DENIED", "当前主体没有匹配的本体消费身份或绑定已变化");
    }
}
