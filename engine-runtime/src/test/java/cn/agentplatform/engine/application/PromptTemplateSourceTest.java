package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.AuthorizedMaterial;
import cn.agentplatform.engine.domain.InitialChatRules;
import cn.agentplatform.engine.infra.ModelInputMaterialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;

/** Existing assembly and finite source gates; no service, authority substitute or database fixture. */
class PromptTemplateSourceTest {
    static ObjectNode source() {
        var material = Map.of("content", "模板正文 {{topic}}", "variables",
                List.of(Map.of("name", "topic", "type", "string", "required", true)),
                "outputContract", Map.of("format", "text"));
        String sha = Digest.sha256(Json.canonical(material));
        var ref = Map.of("code", "selected", "version", 1, "sha256", sha);
        var prompt = new LinkedHashMap<String, Object>(material);
        prompt.putAll(ref); prompt.put("versionNo", 1); prompt.put("status", "PUBLISHED");
        var source = Json.object();
        source.put("appCode", "template_app"); source.put("type", "CHAT");
        source.put("name", "模板应用"); source.put("description", ""); source.put("domain", "");
        source.put("bizCategory", ""); source.set("tags", Json.tree(List.of()));
        source.set("definition", Json.object());
        source.set("config", Json.tree(Map.of("modelEntryId", 7, "systemPrompt", "${input.legacy}",
                "promptSource", "TEMPLATE", "promptRefs", List.of(ref))));
        hash(source);
        source.set("dependencyVersions", Json.tree(Map.of("modelRefs", List.of(Map.of("modelEntryId", 7)),
                "frozenPrompts", List.of(prompt))));
        return source;
    }

    static void hash(ObjectNode snapshot) {
        var content = new LinkedHashMap<String, Object>();
        for (String key : List.of("appCode", "type", "name", "description", "domain", "bizCategory", "tags", "config", "definition"))
            content.put(key, snapshot.path(key));
        snapshot.put("candidateSha256", Digest.sha256(Json.canonical(content)));
    }

    static JsonNode inputs() { return Json.tree(Map.of("topic", "实际问题", "legacy", "历史首段")); }

    @Test void explicitDirectPublishedPromptMustMatchItsCandidateDigest() {
        var snapshot = source();
        var config = (ObjectNode) snapshot.path("config");
        config.put("promptSource", "DIRECT").put("systemPrompt", "已发布的内联正文");
        config.remove("promptRefs");
        var dependencies = (ObjectNode) snapshot.path("dependencyVersions");
        dependencies.remove("frozenPrompts");
        dependencies.putArray("skillRefs");
        hash(snapshot);
        assertDoesNotThrow(() -> InitialChatRules.requireSources(snapshot, inputs()));

        config.put("systemPrompt", "被改写的内联正文");
        assertThrows(PlatformException.class, () -> InitialChatRules.requireSources(snapshot, inputs()));
    }

    @Test void explicitTemplateIsPrimaryWhileExistingInlineAndAppendOrderRemainUnchanged() {
        var snapshot = source();
        var config = (ObjectNode) snapshot.path("config").deepCopy();
        config.set("frozenPrompts", snapshot.path("dependencyVersions").path("frozenPrompts").deepCopy());
        var assembler = new ContextAssembler();
        var selected = Json.tree(assembler.assemble(config, inputs(), List.of(), List.of()));
        assertEquals("[selected]\n模板正文 实际问题", selected.path("messages").get(0).path("content").asText());
        assertEquals(inputs().toString(), selected.path("messages").get(1).path("content").asText());
        config.remove("promptSource");
        var legacy = Json.tree(assembler.assemble(config, inputs(), List.of(), List.of()));
        assertEquals("历史首段\n\n[selected]\n模板正文 实际问题", legacy.path("messages").get(0).path("content").asText());
        assertEquals(config.path("frozenPrompts"), snapshot.path("dependencyVersions").path("frozenPrompts"));
    }

    @Test void finiteSourceAcceptsOnlyBoundBodyAndRejectsChangedDigestOrSelfReportedMaterials() {
        var valid = source();
        assertDoesNotThrow(() -> InitialChatRules.requireSources(valid, inputs()));
        var badBody = valid.deepCopy();
        ((ObjectNode) badBody.path("dependencyVersions").path("frozenPrompts").get(0)).put("content", "伪造正文");
        var badSha = valid.deepCopy();
        ((ObjectNode) badSha.path("config").path("promptRefs").get(0)).put("sha256", "a".repeat(64));
        hash(badSha);
        var unknownBody = valid.deepCopy();
        ((ObjectNode) unknownBody.path("dependencyVersions").path("frozenPrompts").get(0)).put("untrustedMaterial", "未知正文来源");
        var selfReported = valid.deepCopy();
        ((ObjectNode) selfReported.path("config")).set("frozenPrompts", valid.path("dependencyVersions").path("frozenPrompts"));
        hash(selfReported);
        var missingBody = valid.deepCopy();
        ((ObjectNode) missingBody.path("dependencyVersions")).remove("frozenPrompts");
        for (var bad : List.of(badBody, badSha, unknownBody, selfReported, missingBody))
            assertThrows(PlatformException.class, () -> InitialChatRules.requireSources(bad, inputs()));
        var suppliedInputs = (ObjectNode) inputs();
        suppliedInputs.set("frozenPrompts", valid.path("dependencyVersions").path("frozenPrompts"));
        assertThrows(PlatformException.class, () -> InitialChatRules.requireSources(valid, suppliedInputs));
        // Public source records carry no authority. Invalid caller material is rejected before proof/binding access.
        var dataOnly = new ModelInputMaterialStore.InitialSource(null,
                new AuthorizedMaterial(selfReported, null), new AuthorizedMaterial(inputs(), null), null, "data-only");
        assertThrows(PlatformException.class, () -> ModelInputMaterials.assemble(dataOnly, null));
    }
}
