package cn.agentplatform.app.application;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只验证分页绑定：复用真实 app PG/MVC 与签名 HTTP 元数据夹具，不调用业务工具。 */
@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
class CapabilityPackagePaginationBindingTest {
    private final CapabilityPackageBindingTest fixture = new CapabilityPackageBindingTest();
    private static final Map<String, Object> PAGE = Map.of("request", Map.of("page", "page", "size", "pageSize"));

    @BeforeAll static void setup() throws Exception { CapabilityPackageBindingTest.database(); }
    @AfterAll static void close() throws Exception { CapabilityPackageBindingTest.close(); }
    @BeforeEach void actor() { fixture.actor(); }
    @AfterEach void cleanup() { fixture.cleanup(); }

    @Test
    void omittedPublishedModeBindsAsPageAndExplicitPageOrOffsetKeepTheirMeaning() throws Exception {
        var policies = List.of(PAGE,
                Map.of("mode", "PAGE", "request", Map.of("page", "page", "size", "pageSize")),
                Map.of("mode", "OFFSET", "request", Map.of("offset", "offset", "size", "limit"),
                        "maxPageSize", 20, "maxRows", 40));
        for (int index = 0; index < policies.size(); index++) {
            var pkg = pkg(index, index == 2 ? "offset" : "page");
            replyPagination(policies.get(index));
            long id = fixture.imported(pkg);
            assertResolved(id);
        }
        assertEquals(3, CapabilityPackageBindingTest.requests.get());
    }

    @Test
    void implicitPageCannotBindAsNoneAndAbsentDeclarationsStayCompatible() throws Exception {
        var pkg = pkg(0, "none");
        replyPagination(PAGE);
        long id = fixture.imported(pkg);
        assertEquals("CAPABILITY_BINDING_CONFLICT", fixture.resolve(id, fixture.plan(), 409).get("code"));
        var stored = CapabilityPackageBindingTest.db.queryForMap(
                "select binding_status,request_plan_sha256,resolved_mapping from app.capability_package_version where tenant_id=? and id=?",
                fixture.tenant, id);
        assertEquals("UNRESOLVED", stored.get("binding_status"));
        assertNull(stored.get("request_plan_sha256"));
        assertNull(stored.get("resolved_mapping"));

        replyPagination(null);
        assertResolved(id); // 真正没有分页配置时，原 none 声明仍有效。
        var legacy = pkg(1, null);
        replyPagination(PAGE);
        assertResolved(fixture.imported(legacy)); // 工具未声明分页时不新增限制。
        assertEquals(3, CapabilityPackageBindingTest.requests.get());
    }

    private Map<String, Object> pkg(int suffix, String mode) {
        var pkg = fixture.pkg("HTTP_SYNC");
        AppService.object(pkg.get("metadata")).put("version", "1.0." + suffix);
        if (mode != null) AppService.objects(pkg.get("tools")).getFirst().put("pagination", mode);
        return pkg;
    }

    private void replyPagination(Object pagination) {
        var reply = new LinkedHashMap<>(CapabilityPackageBindingTest.metadata.get());
        var definition = new LinkedHashMap<>(AppService.object(reply.get("definition")));
        if (pagination == null) definition.remove("pagination");
        else definition.put("pagination", pagination);
        reply.put("definition", definition);
        String sha = Digest.sha256(Json.canonical(definition));
        reply.put("sha256", sha);
        reply.put("digest", sha);
        CapabilityPackageBindingTest.metadata.set(reply);
    }

    private void assertResolved(long id) throws Exception {
        var data = AppService.object(fixture.resolve(id, fixture.plan(), 200).get("data"));
        assertEquals("RESOLVED", data.get("bindingStatus"));
        assertEquals("NOT_EVALUATED", data.get("verificationStatus"));
        assertEquals(false, data.get("appCreated"));
        assertEquals(CapabilityPackageBindingTest.metadata.get().get("sha256"),
                AppService.objects(data.get("bindings")).getFirst().get("sha256"));
        assertEquals("RESOLVED", CapabilityPackageBindingTest.db.queryForObject(
                "select binding_status from app.capability_package_version where tenant_id=? and id=?",
                String.class, fixture.tenant, id));
        assertEquals(0, CapabilityPackageBindingTest.db.queryForObject(
                "select count(*) from app.application where tenant_id=?", Integer.class, fixture.tenant));
    }
}
