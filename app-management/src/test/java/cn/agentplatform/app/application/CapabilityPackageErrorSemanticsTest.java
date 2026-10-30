package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 复用真实 app PG/MVC 与签名 HTTP 夹具，技能元数据仍为本地协议标本。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackageErrorSemanticsTest {
    private final CapabilityPackageBindingTest fixture = new CapabilityPackageBindingTest();
    private static final Map<String, Object> BUSY = Map.of("match", "BUSY", "semantic", "RETRYABLE_FAILURE");
    private static final Map<String, Object> DENIED = Map.of("match", "DENIED", "semantic", "NO_PERMISSION");

    @BeforeAll static void setup() throws Exception { CapabilityPackageBindingTest.database(); }
    @AfterAll static void close() throws Exception { CapabilityPackageBindingTest.close(); }
    @BeforeEach void actor() { fixture.actor(); }
    @AfterEach void cleanup() { fixture.cleanup(); }

    @Test
    void arrayDifferenceMissingRulesAndChangedPriorityCannotPersistResolution() throws Exception {
        var pkg = fixture.pkg("HTTP_SYNC");
        AppService.objects(pkg.get("tools")).getFirst().put("errorSemantics", List.of(BUSY, DENIED));
        long id = fixture.imported(pkg);

        for (Object actual : List.of(
                List.of(Map.of("match", "BUSY", "semantic", "OUTPUT_INVALID"), DENIED),
                List.of(DENIED, BUSY))) {
            replyPolicy(actual);
            assertEquals("CAPABILITY_BINDING_CONFLICT", fixture.resolve(id, fixture.plan(), 409).get("code"));
            assertUnresolved(id);
        }
        replyPolicy(null);
        assertEquals("CAPABILITY_BINDING_CONFLICT", fixture.resolve(id, fixture.plan(), 409).get("code"));
        assertUnresolved(id);
        assertEquals(3, CapabilityPackageBindingTest.requests.get());
    }

    @Test
    void exactArrayObjectAndLegacyEmptyDeclarationKeepTheirPublishedDigest() throws Exception {
        var policies = List.of(List.of(BUSY, DENIED),
                Map.of("codePath", "error.code", "messagePath", "error.message", "retryableCodes", List.of("BUSY")),
                List.of());
        for (int index = 0; index < policies.size(); index++) {
            var pkg = fixture.pkg("HTTP_SYNC");
            AppService.object(pkg.get("metadata")).put("version", "1.0." + index);
            AppService.objects(pkg.get("tools")).getFirst().put("errorSemantics", policies.get(index));
            // 旧空数组表示未声明规则，不要求旧技能补造 errorSemantics 字段。
            replyPolicy(index == 2 ? null : policies.get(index));
            long id = fixture.imported(pkg);
            var data = AppService.object(fixture.resolve(id, fixture.plan(), 200).get("data"));
            var binding = AppService.objects(data.get("bindings")).getFirst();
            assertEquals("RESOLVED", data.get("bindingStatus"));
            assertEquals("NOT_EVALUATED", data.get("verificationStatus"));
            assertEquals(false, data.get("appCreated"));
            assertEquals(CapabilityPackageBindingTest.metadata.get().get("sha256"), binding.get("sha256"));
            var stored = Json.read(CapabilityPackageBindingTest.db.queryForObject(
                    "select resolved_mapping::text from app.capability_package_version where tenant_id=? and id=?",
                    String.class, fixture.tenant, id));
            assertEquals(Json.tree(data.get("bindings")), stored.path("bindings"));
        }
        assertEquals(3, CapabilityPackageBindingTest.requests.get());
        assertEquals(0, CapabilityPackageBindingTest.db.queryForObject(
                "select count(*) from app.application where tenant_id=?", Integer.class, fixture.tenant));
    }

    private void replyPolicy(Object policy) {
        var reply = new LinkedHashMap<>(CapabilityPackageBindingTest.metadata.get());
        var definition = new LinkedHashMap<>(AppService.object(reply.get("definition")));
        if (policy == null) definition.remove("errorSemantics");
        else definition.put("errorSemantics", policy);
        reply.put("definition", definition);
        String sha = Digest.sha256(Json.canonical(definition));
        reply.put("sha256", sha);
        reply.put("digest", sha);
        CapabilityPackageBindingTest.metadata.set(reply);
    }

    private void assertUnresolved(long id) {
        var stored = CapabilityPackageBindingTest.db.queryForMap(
                "select binding_status,request_plan_sha256,resolved_mapping from app.capability_package_version where tenant_id=? and id=?",
                fixture.tenant, id);
        assertEquals("UNRESOLVED", stored.get("binding_status"));
        assertNull(stored.get("request_plan_sha256"));
        assertNull(stored.get("resolved_mapping"));
    }
}
