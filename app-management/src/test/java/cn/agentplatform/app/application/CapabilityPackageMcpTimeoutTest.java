package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅校验MCP精确发布元数据的超时绑定；复用PG/MVC与签名HTTP，不调用真实MCP工具。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackageMcpTimeoutTest {
    private final CapabilityPackageBindingTest fixture = new CapabilityPackageBindingTest();

    @BeforeAll static void setup() throws Exception { CapabilityPackageBindingTest.database(); }
    @AfterAll static void close() throws Exception { CapabilityPackageBindingTest.close(); }
    @BeforeEach void actor() { fixture.actor(); }
    @AfterEach void cleanup() { fixture.cleanup(); }

    @Test
    void declaredMcpTimeoutRejectsDifferentOrMissingPublishedTimeoutWithoutResolution() throws Exception {
        var pkg = fixture.pkg("MCP");
        AppService.objects(pkg.get("interfaces")).getFirst().put("timeoutMs", 1000);
        long id = fixture.imported(pkg);
        replyTimeout(30000L);
        assertEquals("CAPABILITY_BINDING_CONFLICT", fixture.resolve(id, fixture.plan(), 409).get("code"));
        assertUnresolved(id);
        replyTimeout(null);
        assertEquals("CAPABILITY_BINDING_CONFLICT", fixture.resolve(id, fixture.plan(), 409).get("code"));
        assertUnresolved(id);
        assertEquals(2, CapabilityPackageBindingTest.requests.get());
    }

    @Test
    void matchingMcpTimeoutAndLegacyOmittedDeclarationKeepExactVerifiedBinding() throws Exception {
        var exact = fixture.pkg("MCP");
        AppService.objects(exact.get("interfaces")).getFirst().put("timeoutMs", 1000);
        replyTimeout(1000L);
        assertResolved(fixture.imported(exact));

        var legacy = fixture.pkg("MCP");
        AppService.object(legacy.get("metadata")).put("version", "1.0.1");
        replyTimeout(30000L);
        assertResolved(fixture.imported(legacy));
        assertEquals(2, CapabilityPackageBindingTest.requests.get());
        assertEquals(0, CapabilityPackageBindingTest.db.queryForObject(
                "select count(*) from app.application where tenant_id=?", Integer.class, fixture.tenant));
    }

    private void replyTimeout(Long timeout) {
        var reply = new LinkedHashMap<>(CapabilityPackageBindingTest.metadata.get());
        var definition = new LinkedHashMap<>(AppService.object(reply.get("definition")));
        if (timeout == null) definition.remove("timeoutMs");
        else definition.put("timeoutMs", timeout);
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

    private void assertResolved(long id) throws Exception {
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
}
