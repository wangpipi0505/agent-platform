package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.auth.AuthScheme;
import cn.agentplatform.skill.domain.SkillDigest;
import cn.agentplatform.skill.infra.*;

import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 投影仅增加声明消费；真实PG与HTTP、模型/引擎协议替身分别验证，不冒称OR业务分页。 */
class OntologyProjectionImportTest {
    @TempDir Path secretDir;
    private String oldSecretDir;
    private String oldMasterKey;
    private String tenant;
    private String authRef;
    private SkillCatalog catalog;
    private OntologyManifestImports importer;

    @BeforeEach
    void prepare() {
        oldSecretDir = System.getProperty("agent.secret.dir");
        oldMasterKey = System.getProperty("agent.master.key");
        System.setProperty("agent.secret.dir", secretDir.toString());
        System.setProperty("agent.master.key", "projection-test-master-key-012345678901");
        tenant = "projection-" + UUID.randomUUID();
        var jwt =
                Jwt.withTokenValue("verified-projection-test")
                        .header("alg", "HS256")
                        .subject("projection-tester")
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("ADMIN", "EDITOR", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        authRef = SecretVault.put("ontopa_projection_test_only");
        catalog = spy(new SkillCatalog(null, null, null, "http://app.test", false));
        doAnswer(call -> call.getArgument(0)).when(catalog).create(anyMap());
        importer = new OntologyManifestImports(catalog);
    }

    @AfterEach
    void restore() {
        SecurityContextHolder.clearContext();
        if (oldSecretDir == null) System.clearProperty("agent.secret.dir");
        else System.setProperty("agent.secret.dir", oldSecretDir);
        if (oldMasterKey == null) System.clearProperty("agent.master.key");
        else System.setProperty("agent.master.key", oldMasterKey);
    }

    private Map<String, Object> manifest(String code) {
        var body = OntologyManifestImportsTest.formalManifest(code);
        body.put(
                "exposure",
                new LinkedHashMap<>(Map.of("kind", "QUERY", "pagination", sourcePage())));
        return body;
    }

    private Map<String, Object> sourcePage() {
        // maxRows deliberately below maxLimit: neither source nor consumer declares that ordering.
        return new LinkedHashMap<>(
                Map.of(
                        "offsetParam",
                        "startAt",
                        "limitParam",
                        "takeRows",
                        "maxLimit",
                        5,
                        "maxRows",
                        2));
    }

    private Map<String, Object> offset() {
        return new LinkedHashMap<>(
                Map.of(
                        "mode",
                        "OFFSET",
                        "request",
                        Map.of("offset", "startAt", "size", "takeRows"),
                        "maxPageSize",
                        5,
                        "maxRows",
                        2));
    }

    private Map<String, Object> canonicalBody() {
        var body =
                importer.toSkillBody(
                        OntologyManifestImportsTest.formalManifest("canonical_query"), authRef);
        body.put("exposure", Map.of("kind", "QUERY"));
        body.put("pagination", offset());
        return body;
    }

    @SafeVarargs
    private final Map<String, Object> request(Map<String, Object>... manifests) {
        return Map.of("manifests", List.of(manifests), "authRef", authRef);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> imported(Map<String, Object> manifest) {
        return ((List<Map<String, Object>>)
                        importer.importManifests(request(manifest)).get("drafts"))
                .getFirst();
    }

    private PlatformException rejected(Map<String, Object> body, String field) {
        var failure = assertThrows(PlatformException.class, () -> importer.importManifests(body));
        assertEquals(400, failure.status());
        assertTrue(failure.getMessage().contains(field), failure.getMessage());
        verify(catalog, never()).create(anyMap());
        return failure;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    @Test
    void formalProjectionAndExplicitRiskSurviveImport() {
        var body = imported(manifest("projected_query"));
        assertEquals(Map.of("kind", "QUERY"), body.get("exposure"));
        assertEquals(Json.tree(offset()), Json.tree(body.get("pagination")));
        assertEquals("SYNC", body.get("invocationKind"));
        assertEquals("READ_ONLY", body.get("riskLevel"));
        assertEquals("API_KEY", body.get(AuthScheme.FIELD));
        assertEquals(authRef, body.get("authRef"));
        assertFalse(object(body.get("exposure")).containsKey("pagination"));
        assertEquals("ONTOLOGY", object(body.get("importSource")).get("kind"));
    }

    @Test
    void catalogRetainsOffsetAndLegacyPageWithoutAddingMode() {
        var body = canonicalBody();
        var normalized = catalog.normalize(body);
        assertEquals(Json.tree(offset()), Json.tree(normalized.get("pagination")));
        assertEquals(Map.of("kind", "QUERY"), normalized.get("exposure"));
        var page =
                Map.of(
                        "request",
                        Map.of("page", "pageNo", "size", "pageSize"),
                        "response",
                        Map.of("itemsPath", "$.data.rows", "totalPath", "$.data.total"),
                        "maxPageSize",
                        10);
        body.put("pagination", page);
        assertEquals(page, catalog.normalize(body).get("pagination"));
        body.put(
                "pagination",
                Map.of(
                        "mode",
                        "PAGE",
                        "request",
                        Map.of("page", "p", "size", "s"),
                        "response",
                        Map.of()));
        assertEquals(body.get("pagination"), catalog.normalize(body).get("pagination"));
        var largest = offset();
        largest.put("maxPageSize", Integer.MAX_VALUE);
        largest.put("maxRows", Integer.MAX_VALUE);
        body.put("pagination", largest);
        assertEquals(largest, catalog.normalize(body).get("pagination"));
    }

    @Test
    void changingOnlyPaginationChangesSemanticDigest() {
        var body = canonicalBody();
        var before = catalog.normalize(body);
        var changed = offset();
        changed.put("maxPageSize", 6);
        body.put("pagination", changed);
        var after = catalog.normalize(body);
        assertNotEquals(SkillDigest.of(before), SkillDigest.of(after));
        assertEquals(before.get("execution"), after.get("execution"));
    }

    @Test
    void explicitRiskCanRiseWhileLegacyManualFloorRemainsCompatible() {
        var source = manifest("high_query");
        source.put("risk_level", "CONFIRM_REQUIRED");
        assertEquals("CONFIRM_REQUIRED", catalog.normalize(imported(source)).get("riskLevel"));
        var manual = canonicalBody();
        manual.remove("importSource");
        manual.remove("exposure");
        manual.remove("pagination");
        manual.put(
                "execution",
                Map.of(
                        "readonly",
                        false,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        manual.put("riskLevel", "READ_ONLY");
        assertEquals("CONFIRM_REQUIRED", catalog.normalize(manual).get("riskLevel"));
        manual.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        manual.put("riskLevel", "legacy-unrecognized-value");
        assertEquals("READ_ONLY", catalog.normalize(manual).get("riskLevel"));
    }

    @Test
    void absentProjectionAndRiskDoNotInventDeclarations() {
        var source = OntologyManifestImportsTest.formalManifest("old_query");
        source.remove("risk_level");
        var body = imported(source);
        assertFalse(body.containsKey("exposure"));
        assertFalse(body.containsKey("pagination"));
        assertFalse(body.containsKey("riskLevel"));
        var normalized = catalog.normalize(body);
        assertFalse(normalized.containsKey("exposure"));
        assertFalse(normalized.containsKey("pagination"));
        assertEquals("READ_ONLY", normalized.get("riskLevel"));
    }

    @Test
    void mutationPreservesBothSideEffectValuesWithoutInventingApproval() {
        for (boolean sideEffect : List.of(false, true)) {
            var source = manifest("mutation_" + sideEffect);
            source.put("exposure", Map.of("kind", "MUTATION"));
            object(source.get("exec_attrs")).put("readonly_flag", false);
            object(source.get("exec_attrs")).put("side_effect", sideEffect);
            source.put("risk_level", "CONFIRM_REQUIRED");
            var contract = catalog.normalize(imported(source));
            assertEquals(Map.of("kind", "MUTATION"), contract.get("exposure"));
            assertEquals(sideEffect, object(contract.get("execution")).get("sideEffect"));
            assertEquals("CONFIRM_REQUIRED", contract.get("riskLevel"));
            assertFalse(object(contract.get("execution")).containsKey("approval"));
            assertFalse(object(contract.get("execution")).containsKey("confirmRoles"));
        }
    }

    @Test
    void registeredNonHttpRequiresRealBindingWithoutInventingHttp() {
        for (var transport :
                List.of(
                        Map.of("adapter_type", "MCP", "invocation_kind", "SYNC"),
                        Map.of("adapter_type", "ALGORITHM", "invocation_kind", "SUBMIT_QUERY"))) {
            for (String reference : List.of("", "unregistered:projection")) {
                var source = manifest("non_http");
                source.putAll(transport);
                source.remove("endpoint");
                source.remove("http_method");
                if (!reference.isEmpty()) source.put("binding_ref", reference);
                rejected(request(source), "binding_ref");
            }
        }
    }

    @Test
    void contradictionsAndUnknownTransportCombinationsPointToTheirField() {
        var cases = new ArrayList<Map<String, Object>>();
        var unknown = manifest("unknown");
        unknown.put("adapter_type", "UNKNOWN");
        cases.add(unknown);
        var endpoint = manifest("endpoint");
        endpoint.put("adapter_type", "MCP");
        cases.add(endpoint);
        var method = manifest("method");
        method.put("adapter_type", "MCP");
        method.remove("endpoint");
        cases.add(method);
        var combo = manifest("combo");
        combo.put("adapter_type", "ALGORITHM");
        combo.remove("endpoint");
        combo.remove("http_method");
        cases.add(combo);
        var async = manifest("async");
        async.put("invocation_kind", "SUBMIT_QUERY");
        cases.add(async);
        var fields =
                List.of(
                        "adapter_type",
                        "endpoint",
                        "http_method",
                        "invocation_kind",
                        "invocation_kind");
        for (int i = 0; i < cases.size(); i++) rejected(request(cases.get(i)), fields.get(i));
    }

    private List<Map<String, Object>> invalidSourcePages() {
        var pages = new ArrayList<Map<String, Object>>();
        for (String key : sourcePage().keySet()) {
            var missing = sourcePage();
            missing.remove(key);
            pages.add(missing);
            var empty = sourcePage();
            empty.put(key, null);
            pages.add(empty);
        }
        for (Object number : List.of(0, -1, 1.5, 1.0, "5", 2147483648L, true))
            for (String field : List.of("maxLimit", "maxRows")) {
                var page = sourcePage();
                page.put(field, number);
                pages.add(page);
            }
        for (String field : List.of("offsetParam", "limitParam")) {
            var blank = sourcePage();
            blank.put(field, " ");
            pages.add(blank);
            var nonText = sourcePage();
            nonText.put(field, 1);
            pages.add(nonText);
        }
        var unknown = sourcePage();
        unknown.put("page", "renamedOffset");
        pages.add(unknown);
        return pages;
    }

    @Test
    void sourcePaginationIsStrictAndNeverSilentlyDropped() {
        for (var page : invalidSourcePages()) {
            var source = manifest("bad_page");
            source.put("exposure", Map.of("kind", "QUERY", "pagination", page));
            rejected(request(source), "pagination");
        }
        for (Object malformed : Arrays.asList(null, "offset", Map.of())) {
            var source = manifest("bad_shape");
            var exposure = new LinkedHashMap<String, Object>();
            exposure.put("kind", "QUERY");
            exposure.put("pagination", malformed);
            source.put("exposure", exposure);
            rejected(request(source), "pagination");
        }
    }

    @Test
    void canonicalPaginationRejectsNullUnknownFieldsAndMixedModes() {
        var invalid = new ArrayList<Object>();
        invalid.add(null);
        invalid.add(Map.of());
        invalid.add("offset");
        for (String field : offset().keySet()) {
            var page = offset();
            page.remove(field);
            invalid.add(page);
        }
        var unknown = offset();
        unknown.put("mode", "CURSOR");
        invalid.add(unknown);
        var mixed = offset();
        mixed.put("request", Map.of("page", "p", "size", "s"));
        invalid.add(mixed);
        var extra = offset();
        extra.put("response", Map.of("itemsPath", "$.data.rows"));
        invalid.add(extra);
        var decimal = offset();
        decimal.put("maxRows", 1.0);
        invalid.add(decimal);
        invalid.add(Map.of("request", Map.of("page", "p", "size", "s", "offset", "o")));
        invalid.add(
                Map.of(
                        "request",
                        Map.of("page", "p", "size", "s"),
                        "response",
                        Map.of("itemsPath", 1)));
        invalid.add(
                Map.of(
                        "request",
                        Map.of("page", "p", "size", "s"),
                        "response",
                        Map.of("nextOffset", "$.next")));
        for (Object page : invalid) {
            var body = canonicalBody();
            body.put("pagination", page);
            var failure = assertThrows(PlatformException.class, () -> catalog.normalize(body));
            assertEquals(400, failure.status());
            assertTrue(failure.getMessage().contains("pagination"));
        }
    }

    private List<Map<String, Object>> invalidLaterManifests() {
        var page = manifest("bad_page");
        object(object(page.get("exposure")).get("pagination")).put("maxLimit", 1.0);
        var query = manifest("bad_query");
        object(query.get("exec_attrs")).put("readonly_flag", false);
        query.put("risk_level", "CONFIRM_REQUIRED");
        var risk = manifest("bad_risk");
        risk.remove("exposure");
        object(risk.get("exec_attrs")).put("readonly_flag", false);
        var transport = manifest("bad_transport");
        transport.put("adapter_type", "ALGORITHM");
        transport.remove("endpoint");
        transport.remove("http_method");
        return List.of(page, query, risk, transport);
    }

    @Test
    void invalidLaterPaginationKindRiskAndTransportRejectBeforeFirstCreate() {
        var fields = List.of("pagination", "exposure.kind", "risk_level", "invocation_kind");
        var bad = invalidLaterManifests();
        for (int i = 0; i < bad.size(); i++) {
            clearInvocations(catalog);
            rejected(request(manifest("good_first"), bad.get(i)), fields.get(i));
        }
    }

    @Test
    void projectionConflictsAndUnknownRiskCannotChangeExecutionFlags() {
        var query = manifest("query_write");
        object(query.get("exec_attrs")).put("readonly_flag", false);
        query.put("risk_level", "CONFIRM_REQUIRED");
        rejected(request(query), "exposure.kind");
        var mutation = manifest("mutation_read");
        mutation.put("exposure", Map.of("kind", "MUTATION"));
        rejected(request(mutation), "exposure.kind");
        var risk = manifest("unknown_risk");
        risk.put("risk_level", "UNRECOGNIZED");
        rejected(request(risk), "risk_level");
        var exposed = manifest("unknown_projection");
        exposed.put("exposure", Map.of("kind", "EVENT"));
        rejected(request(exposed), "exposure.kind");
        for (Object raw :
                Arrays.asList(null, Map.of(), "QUERY", Map.of("kind", "QUERY", "unknown", true))) {
            var malformed = manifest("bad_exposure");
            malformed.put("exposure", raw);
            rejected(request(malformed), "exposure");
        }
    }

    /** 只复用已授权且带EX03准确归属标记的库，不自行建库或重置历史。 */
    private JdbcTemplate database() {
        String password = System.getenv("AGENT_DB_PASSWORD");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                password));
        var owner =
                admin.queryForList(
                        "select pg_get_userbyid(datdba) as"
                                + " owner,shobj_description(oid,'pg_database') as marker from"
                                + " pg_database where datname=?",
                        "agent_platform_ontology_import_test");
        assertEquals(1, owner.size(), "先核准既有EX03专库，不自动创建");
        assertEquals("agent_v12", owner.getFirst().get("owner"));
        assertEquals(
                "agent-platform EX-03 2026-10-28 ontology import isolated test database",
                owner.getFirst().get("marker"));
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_ontology_import_test",
                        "agent_v12",
                        password);
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(ds);
    }

    private SkillCatalog persistent(JdbcTemplate db, SkillClients clients) {
        return new SkillCatalog(
                db,
                new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())),
                clients,
                "http://app.test",
                false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
    void postgresInvalidLaterItemsLeaveAllDraftCountsUnchanged() {
        var db = database();
        var actual = persistent(db, mock(SkillClients.class));
        var service = new OntologyManifestImports(actual);
        for (var bad : invalidLaterManifests()) {
            int before =
                    db.queryForObject(
                            "select count(*) from skill.skill where tenant_id=?",
                            Integer.class,
                            tenant);
            PlatformException failure = null;
            try {
                service.importManifests(
                        request(
                                manifest("good_" + UUID.randomUUID().toString().replace("-", "")),
                                bad));
            } catch (PlatformException rejected) {
                failure = rejected;
            }
            int after =
                    db.queryForObject(
                            "select count(*) from skill.skill where tenant_id=?",
                            Integer.class,
                            tenant);
            int drafts =
                    db.queryForObject(
                            "select count(*) from skill.skill_version where tenant_id=? and"
                                + " status='DRAFT'",
                            Integer.class,
                            tenant);
            System.out.println(
                    "EX04_BATCH_COUNTS="
                            + Json.write(
                                    Map.of(
                                            "before",
                                            before,
                                            "after",
                                            after,
                                            "drafts",
                                            drafts,
                                            "rejected",
                                            failure != null)));
            assertEquals(before, after, "无效后项不得留下前项或自身草稿");
            assertEquals(0, drafts);
            assertNotNull(failure, "整批必须拒绝，不能静默丢弃无效声明");
            assertEquals(400, failure.status());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
    @SuppressWarnings("unchecked")
    void postgresHighRiskAndMutationDraftsKeepApprovalAndOmittedFields() {
        var db = database();
        var actual = persistent(db, mock(SkillClients.class));
        var service = new OntologyManifestImports(actual);
        for (String kind : List.of("QUERY", "MUTATION")) {
            String code = "guard_" + UUID.randomUUID().toString().replace("-", "");
            var source = manifest(code);
            source.put("exposure", Map.of("kind", kind));
            source.put("risk_level", "CONFIRM_REQUIRED");
            object(source.get("exec_attrs")).put("readonly_flag", kind.equals("QUERY"));
            var draft =
                    ((List<Map<String, Object>>)
                                    service.importManifests(request(source)).get("drafts"))
                            .getFirst();
            var original = actual.versionId(((Number) draft.get("skillVersionId")).longValue());
            var definition = object(original.get("definition"));
            assertEquals("CONFIRM_REQUIRED", definition.get("riskLevel"));
            assertEquals(Map.of("kind", kind), definition.get("exposure"));
            assertEquals(false, object(definition.get("execution")).get("sideEffect"));
            assertEquals(
                    "CONFIRM_ROLES_REQUIRED",
                    assertThrows(PlatformException.class, () -> actual.publish(code, 1)).code());
            var omitted = new LinkedHashMap<>(definition);
            omitted.remove("exposure");
            omitted.remove("riskLevel");
            var saved = actual.save(code, omitted);
            assertEquals("CONFIRM_REQUIRED", object(saved.get("definition")).get("riskLevel"));
            assertEquals(Map.of("kind", kind), object(saved.get("definition")).get("exposure"));
            assertEquals(original.get("semanticDigest"), saved.get("semanticDigest"));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
    @SuppressWarnings("unchecked")
    void postgresReadEditPublishKeepsOffsetAndExactApiKeyHeaders() throws Exception {
        var db = database();
        var clients = mock(SkillClients.class);
        var actual = persistent(db, clients);
        var headers = new CopyOnWriteArrayList<Map<String, List<String>>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String code = "page_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/onto/r/route3/releases/9f8e7d6c-1111-2222-3333-444455556666/ops/" + code;
        server.createContext(
                path,
                exchange -> {
                    headers.add(
                            Map.of(
                                    "X-API-Key",
                                    List.copyOf(
                                            exchange.getRequestHeaders()
                                                    .getOrDefault("X-API-Key", List.of())),
                                    "Authorization",
                                    List.copyOf(
                                            exchange.getRequestHeaders()
                                                    .getOrDefault("Authorization", List.of()))));
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes =
                            "{\"code\":\"OK\",\"data\":{\"found\":true},\"meta\":{}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(bytes);
                    }
                });
        server.start();
        try {
            var source = manifest(code);
            source.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + path);
            source.put("return_spec", Map.of("structuredPath", "$.data"));
            var draft =
                    ((List<Map<String, Object>>)
                                    new OntologyManifestImports(actual)
                                            .importManifests(request(source))
                                            .get("drafts"))
                            .getFirst();
            long id = ((Number) draft.get("skillVersionId")).longValue();
            var stored = actual.versionId(id);
            var definition = object(stored.get("definition"));
            assertEquals(Map.of("kind", "QUERY"), definition.get("exposure"));
            assertEquals(Json.tree(offset()), Json.tree(definition.get("pagination")));
            assertEquals("API_KEY", definition.get(AuthScheme.FIELD));
            assertEquals("ONTOLOGY", object(definition.get("importSource")).get("kind"));
            for (String field : List.of("exposure", "pagination")) {
                var invalid = new LinkedHashMap<>(definition);
                invalid.put(field, null);
                assertEquals(
                        400,
                        assertThrows(PlatformException.class, () -> actual.save(code, invalid))
                                .status());
                assertEquals(stored.get("definition"), actual.versionId(id).get("definition"));
                assertEquals(
                        stored.get("semanticDigest"), actual.versionId(id).get("semanticDigest"));
            }
            var omitted = new LinkedHashMap<>(definition);
            omitted.remove("exposure");
            omitted.remove("pagination");
            omitted.remove("riskLevel");
            var saved = actual.save(code, omitted);
            assertEquals(
                    definition.get("exposure"), object(saved.get("definition")).get("exposure"));
            assertEquals(
                    definition.get("pagination"),
                    object(saved.get("definition")).get("pagination"));
            assertEquals(stored.get("semanticDigest"), saved.get("semanticDigest"));
            var edited = new LinkedHashMap<>(object(saved.get("definition")));
            var changed = offset();
            changed.put("maxPageSize", 6);
            edited.put("pagination", changed);
            saved = actual.save(code, edited);
            assertNotEquals(stored.get("semanticDigest"), saved.get("semanticDigest"));
            var adapters =
                    new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
            var execution =
                    new SkillExecution(
                            db,
                            new DataSourceTransactionManager(
                                    Objects.requireNonNull(db.getDataSource())),
                            actual,
                            adapters,
                            clients,
                            "http://engine.test");
            OntologyManifestImportsTest.executeFixture(
                    execution, clients, code, id, saved.get("sha256").toString(), 1, true);
            var governance =
                    new SkillGovernance(
                            db,
                            actual,
                            adapters,
                            clients,
                            "http://model.test",
                            "http://engine.test");
            assertEquals(
                    true, governance.connection(code, 1, Map.of("controlledCallId", 1)).get("ok"));
            when(clients.post(eq("http://model.test/internal/model/chat"), anyMap()))
                    .thenReturn(
                            Json.tree(
                                    Map.of(
                                            "toolCalls",
                                            List.of(
                                                    Map.of(
                                                            "name",
                                                            code,
                                                            "arguments",
                                                            Map.of("region", "A"))))));
            assertEquals(
                    true,
                    governance
                            .acceptance(
                                    code,
                                    1,
                                    Map.of(
                                            "modelEntryId",
                                            1,
                                            "cases",
                                            List.of(
                                                    Map.of(
                                                            "question",
                                                            "查询A",
                                                            "expectSelected",
                                                            code,
                                                            "expectArgs",
                                                            Map.of("region", "A")))))
                            .get("pass"));
            assertEquals("PUBLISHED", actual.publish(code, 1).get("status"));
            var published = actual.versionId(id);
            var frozen = object(published.get("definition"));
            assertEquals(edited.get("pagination"), frozen.get("pagination"));
            assertEquals(definition.get("exposure"), frozen.get("exposure"));
            assertEquals(saved.get("semanticDigest"), published.get("semanticDigest"));
            assertEquals(definition.get("importSource"), frozen.get("importSource"));
            OntologyManifestImportsTest.executeFixture(
                    execution, clients, code, id, published.get("sha256").toString(), 2, false);
            assertEquals(2, headers.size());
            for (var value : headers) {
                assertEquals(List.of("ontopa_projection_test_only"), value.get("X-API-Key"));
                assertEquals(List.of(), value.get("Authorization"));
            }
            // 仅导出PG实际读回的两个投影字段；无凭据、随机租户或网络端点，供独立引擎测试精确衔接。
            System.out.println(
                    "EX04_PERSISTED_PROJECTION="
                            + Json.write(
                                    Map.of(
                                            "exposure",
                                            frozen.get("exposure"),
                                            "pagination",
                                            frozen.get("pagination"))));
        } finally {
            server.stop(0);
        }
    }
}
