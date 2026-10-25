package cn.agentplatform.common.authority;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.api.GatewayController;
import cn.agentplatform.gateway.application.*;
import cn.agentplatform.gateway.infra.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Gateway parent contains no app/engine business classes; each receiver owns its actual classpath
 * and PG.
 */
class GatewayPublishedChainPostgresTest {
    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final String CODE = "admission-flow",
            SHA = Digest.sha256("n3-candidate-one"),
            SHA2 = Digest.sha256("n3-candidate-two");
    static LoopbackAuthority iam;
    static Child app, engine;
    static int appPort, enginePort;
    static ServletWebServerApplicationContext context;
    static String tenant, source;
    static long versionId;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude =
                    org.springframework.boot.autoconfigure.security.servlet
                            .SecurityFilterAutoConfiguration.class)
    @EnableWebSecurity(debug = false)
    @Import({GatewayController.class, GatewayIamAssembly.class, PlatformErrors.class})
    static class Receiver {
        @Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor deployment(
                org.springframework.core.env.Environment e) {
            return f ->
                    f.registerSingleton(
                            "authorityProperties", SecurityConfiguration.bindDeployment(e));
        }

        @Bean(destroyMethod = "close")
        TrustedJwtDecoder decoder(AuthorityProperties p) {
            return new TrustedJwtDecoder(p, iam.clock(), true);
        }

        @Bean
        Clock clock() {
            return iam.clock();
        }

        @Bean
        IntegrationAccess integration(TrustedJwtDecoder d) {
            return new IntegrationAccess(
                    "http://127.0.0.1:" + appPort, "http://127.0.0.1:" + enginePort, d);
        }

        @Bean(destroyMethod = "close")
        HttpCurrentAuthorityClient authority() {
            return iam.client("gateway");
        }

        @Bean(destroyMethod = "close")
        PublishedRunHttp transport() {
            return new PublishedRunHttp();
        }

        @Bean(destroyMethod = "close")
        PublishedMetadataClient metadata(PublishedRunHttp h, HttpCurrentAuthorityClient a) {
            return new PublishedMetadataClient(
                    URI.create("http://127.0.0.1:" + appPort), a, h, true);
        }

        @Bean(destroyMethod = "close")
        PublishedRunProxy proxy(
                PublishedRunHttp h, HttpCurrentAuthorityClient a, PublishedMetadataClient m) {
            return new PublishedRunProxy(
                    URI.create("http://127.0.0.1:" + enginePort), m, a, h, true);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        assertThat(System.getProperty("java.class.path"))
                .doesNotContain("app-management", "engine-runtime");
        iam = new LoopbackAuthority();
        iam.portalActor(actor("n3-initial"));
        try {
            app =
                    new Child(
                            "app",
                            "cn.agentplatform.common.authority.PublishedAdmissionFixtureMain",
                            "N3_APP_");
            app.init(
                    Map.of(
                            "trust",
                            iam.config("app"),
                            "secret",
                            iam.testClientSecret("app"),
                            "now",
                            iam.clock().instant().toString()));
            var appReady = app.ready();
            appPort = appReady.path("port").asInt();
            System.out.println("N4B2_APP_READY=" + appReady);
            engine =
                    new Child(
                            "engine",
                            "cn.agentplatform.common.authority.GatewayPublishedEngineFixtureMain",
                            "N4B2_ENGINE_");
            // Existing package-private fixture seam, never reflected or promoted to production API.
            engine.init(
                    Map.of(
                            "trust",
                            iam.config("engine"),
                            "secret",
                            iam.secrets.get("engine"),
                            "now",
                            iam.clock().instant().toString(),
                            "appOrigin",
                            "http://127.0.0.1:" + appPort));
            enginePort = engine.ready().path("port").asInt();
            var boot = new SpringApplication(Receiver.class);
            boot.setAdditionalProfiles("test");
            boot.setDefaultProperties(
                    Map.ofEntries(
                            Map.entry("server.address", "127.0.0.1"),
                            Map.entry("server.port", "0"),
                            Map.entry(
                                    "spring.config.location",
                                    "optional:classpath:/n4b2-chain-no-config/"),
                            Map.entry("spring.main.banner-mode", "off"),
                            Map.entry("springdoc.api-docs.enabled", false),
                            Map.entry("spring.flyway.enabled", false),
                            Map.entry("management.endpoints.web.exposure.exclude", "*"),
                            Map.entry("management.endpoints.web.discovery.enabled", false),
                            Map.entry("logging.level.root", "ERROR")));
            boot.addInitializers(
                    c -> {
                        var p = new LinkedHashMap<String, Object>();
                        flatten("agent.security", Json.tree(iam.config("gateway")), p);
                        p.put("agent.dev-auth", false);
                        c.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new org.springframework.core.env.MapPropertySource(
                                                "n4b2-chain-trust", p));
                    });
            context = (ServletWebServerApplicationContext) boot.run();
            System.out.println(
                    "N4B2_CHAIN_PIDS="
                            + Json.write(
                                    Map.of(
                                            "gateway",
                                            ProcessHandle.current().pid(),
                                            "app",
                                            app.process.pid(),
                                            "engine",
                                            engine.process.pid(),
                                            "port",
                                            context.getWebServer().getPort())));
        } catch (Throwable e) {
            try {
                stop();
            } catch (Throwable cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    static Actor actor(String t) {
        return new Actor(
                t,
                "alice",
                Set.of("EDITOR"),
                Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("initial")));
    }

    @BeforeEach
    void seed() throws Exception {
        iam.clearFiniteGrants();
        iam.revoked(false);
        iam.unavailable(false);
        iam.ticketMutation(c -> {});
        iam.decisionMutation(c -> {});
        tenant = "n3-n4b2-" + UUID.randomUUID();
        iam.portalActor(actor(tenant));
        source = iam.portal();
        app.command(Map.of("action", "clock", "now", iam.clock().instant().toString()));
        engine.command(Map.of("action", "clock", "now", iam.clock().instant().toString()));
        versionId =
                app.command(
                                Map.of(
                                        "action",
                                        "seed",
                                        "tenant",
                                        tenant,
                                        "snapshot",
                                        snapshot(),
                                        "sha",
                                        SHA,
                                        "sha2",
                                        SHA2))
                        .path("versionId")
                        .asLong();
        assertThat(stats().path("runs")).isEmpty();
    }

    @AfterEach
    void record(TestInfo info) throws Exception {
        iam.decisionMutation(c -> {});
        iam.ticketMutation(c -> {});
        System.out.println(
                "N4B2_CHAIN_CASE="
                        + Json.write(
                                Map.of(
                                        "case",
                                        info.getDisplayName(),
                                        "tenant",
                                        tenant,
                                        "engine",
                                        stats(),
                                        "app",
                                        app.command(Map.of("action", "stats")),
                                        "authorityCalls",
                                        iam.authorityCalls())));
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (context != null) {
                context.close();
                context = null;
            }
        } finally {
            try {
                if (engine != null) {
                    var child = engine;
                    engine = null;
                    child.close();
                }
            } finally {
                try {
                    if (app != null) {
                        var child = app;
                        app = null;
                        child.close();
                    }
                } finally {
                    if (iam != null) {
                        iam.close();
                        iam = null;
                    }
                }
            }
        }
    }

    static JsonNode stats() throws Exception {
        return engine.command(Map.of("action", "stats", "tenant", tenant));
    }

    static JsonNode snapshot() {
        return Json.tree(
                Map.of(
                        "appCode",
                        CODE,
                        "appVersionId",
                        CODE + "@1",
                        "versionNo",
                        1,
                        "candidateSha256",
                        SHA,
                        "type",
                        "FLOW",
                        "config",
                        Map.of("guard", Map.of("roles", List.of("EDITOR"))),
                        "definition",
                        Map.of(
                                "entry",
                                "start",
                                "nodes",
                                List.of(
                                        Map.of("id", "start", "type", "START"),
                                        Map.of(
                                                "id",
                                                "end",
                                                "type",
                                                "END",
                                                "args",
                                                "${start.output}")),
                                "edges",
                                List.of(Map.of("from", "start", "to", "end")))));
    }

    static HttpResponse<String> post(String id) throws Exception {
        try (var h = HttpClient.newHttpClient()) {
            return h.send(
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + context.getWebServer().getPort()
                                                    + "/admin/apps/"
                                                    + CODE
                                                    + "/runs"))
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", "Bearer " + source)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            Json.write(
                                                    Map.of(
                                                            "requestId",
                                                            id,
                                                            "inputs",
                                                            Map.of("a", 7)))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    static JsonNode ok(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.read(r.body()).path("data");
    }

    static void denied(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isIn(401, 403, 409, 503);
        assertThat(r.body()).doesNotContain("\"nodes\"", "\"events\"");
    }

    @Test
    void portalThroughRealMetadataMainRunAppPgAndOwnedFlowReturnsFullStatusAndReplay()
            throws Exception {
        var first = ok(post("same-request"));
        assertThat(first.path("runId").asText()).startsWith("r_");
        for (String f :
                List.of(
                        "appCode",
                        "status",
                        "eventsWatermark",
                        "inputs",
                        "output",
                        "fileRefs",
                        "error",
                        "versionRefs",
                        "nodes",
                        "events",
                        "paused")) assertThat(first.has(f)).as(f).isTrue();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < until
                && !"SUCCEEDED".equals(stats().path("runs").path(0).path("status").asText()))
            Thread.sleep(10);
        var complete = ok(post("same-request"));
        assertThat(complete.path("runId")).isEqualTo(first.path("runId"));
        assertThat(complete.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(complete.path("output").path("a").asInt()).isEqualTo(7);
        assertThat(complete.path("nodes")).hasSize(2);
        assertThat(complete.path("events").findValuesAsText("type"))
                .contains("RUN_STARTED", "NODE_STARTED", "RUN_FINISHED");
        var counts = stats();
        assertThat(counts.path("runs")).hasSize(1);
        assertThat(counts.path("attempts").asInt()).isEqualTo(2);
        assertThat(counts.path("materials").asInt()).isGreaterThan(15);
        assertThat(counts.path("runs").path(0).path("app_version_id").asLong())
                .isEqualTo(versionId);
        assertThat(counts.path("activeWorkers").asInt()).isZero();
        assertThat(counts.path("legacy").asInt()).isZero();
        var exchanges =
                iam.requests.stream()
                        .filter(r -> r.path("operation").asText().equals("exchange"))
                        .toList();
        assertThat(exchanges).hasSize(4);
        assertThat(exchanges.stream().map(r -> r.path("nonce").asText()).distinct().count())
                .isEqualTo(4);
        assertThat(
                        exchanges.stream()
                                .filter(
                                        r ->
                                                r.path("action")
                                                        .asText()
                                                        .equals(MetadataSelectors.ACTION))
                                .count())
                .isEqualTo(2);
        assertThat(
                        exchanges.stream()
                                .filter(
                                        r ->
                                                r.path("action")
                                                        .asText()
                                                        .equals(
                                                                "proxy.engine.post.RunController.run"))
                                .count())
                .isEqualTo(2);
        assertThat(
                        iam.grants.values().stream()
                                .map(LoopbackAuthority.Grant::originAuthenticationId)
                                .distinct()
                                .count())
                .isEqualTo(1);
        assertThat(iam.grants.values().stream().map(LoopbackAuthority.Grant::id).distinct().count())
                .isEqualTo(4);
    }

    @Test
    void pointerSwitchAfterLookupIsRejectedBeforeRunAdmission() throws Exception {
        switchAfterLookup(2, "PUBLISHED");
        denied(post("pointer"));
        assertThat(stats().path("runs")).isEmpty();
        assertThat(stats().path("attempts").asInt()).isZero();
    }

    @Test
    void disabledApplicationAfterLookupIsRejectedBeforeRunAdmission() throws Exception {
        switchAfterLookup(1, "DISABLED");
        denied(post("disabled"));
        assertThat(stats().path("runs")).isEmpty();
    }

    static void switchAfterLookup(int version, String status) {
        iam.decisionMutation(
                c -> {
                    if ("gateway".equals(c.get("client_id"))
                            && "proxy.engine.post.RunController.run".equals(c.get("action")))
                        try {
                            app.command(
                                    Map.of(
                                            "action", "pointer", "tenant", tenant, "version",
                                            version, "status", status));
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                });
    }

    @Test
    void expiredOriginalPortalCannotOpenMainAfterMetadata() throws Exception {
        iam.decisionMutation(
                c -> {
                    if (MetadataSelectors.ACTION.equals(c.get("action"))
                            && "gateway".equals(c.get("client_id")))
                        iam.advanceClock(Duration.ofSeconds(41));
                });
        denied(post("source-expired"));
        assertThat(stats().path("runs")).isEmpty();
    }

    @Test
    void tamperedMainTicketAndCurrentNarrowingCannotAdmitRun() throws Exception {
        iam.ticketMutation(
                c -> {
                    if ("engine".equals(c.get("target")))
                        c.put(
                                "resource",
                                Map.of(
                                        "kind",
                                        "app-version",
                                        "id",
                                        CODE + "@1",
                                        "version_digest",
                                        SHA2));
                });
        denied(post("ticket"));
        assertThat(stats().path("runs")).isEmpty();
        iam.ticketMutation(c -> {});
        iam.decisionMutation(
                c -> {
                    if ("engine".equals(c.get("client_id"))
                            && "proxy.engine.post.RunController.run".equals(c.get("action")))
                        c.put("roles", Set.of());
                });
        denied(post("narrow"));
        assertThat(stats().path("runs")).isEmpty();
        assertThat(stats().path("attempts").asInt()).isZero();
    }

    @Test
    void actualMaterialScopeOutsideCurrentCeilingLeaksNoStatus() throws Exception {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode) snapshot();
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.path("config"))
                .set(
                        "guard",
                        Json.tree(
                                Map.of(
                                        "roles",
                                        List.of("EDITOR"),
                                        "attributes",
                                        Map.of("region", List.of("C")))));
        tenant = "n3-n4b2-material-" + UUID.randomUUID();
        iam.portalActor(actor(tenant));
        source = iam.portal();
        app.command(
                Map.of(
                        "action",
                        "seed",
                        "tenant",
                        tenant,
                        "snapshot",
                        value,
                        "sha",
                        SHA,
                        "sha2",
                        SHA2));
        denied(post("scope-mismatch"));
        assertThat(stats().path("runs")).isEmpty();
        assertThat(stats().path("attempts").asInt()).isZero();
    }

    @Test
    void verifiedPortalWithoutPlatformScopeAndServiceTicketCannotEnterGateway() throws Exception {
        var claims = iam.base("user", "portal-gateway", 40);
        claims.put("iss", iam.issuer() + "/portal");
        claims.put("sub", "alice");
        claims.put("tenant_id", tenant);
        claims.put("client_id", "portal-web");
        claims.put("roles", List.of("EDITOR"));
        claims.put("attributes", actor(tenant).attributes());
        claims.put("scope", "");
        String noScope = iam.sign(claims, iam.portalKey, "at+jwt");
        try (var h = HttpClient.newHttpClient()) {
            var r =
                    h.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + context.getWebServer().getPort()
                                                            + "/admin/apps/"
                                                            + CODE
                                                            + "/runs"))
                                    .header("Authorization", "Bearer " + noScope)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).isEqualTo(403);
        }
        var service = iam.base("service", "urn:agent-platform:service:gateway", 30);
        service.put("sub", "engine");
        service.put("client_id", "engine");
        service.put("caller", "engine");
        service.put("target", "gateway");
        service.put("action", "unregistered.gateway.action");
        service.put("authority_mode", "workload");
        service.put("scope", "unregistered.gateway.action");
        service.put(
                "resource",
                Map.of("kind", "app-version", "id", CODE + "@1", "version_digest", SHA));
        for (String bad :
                List.of(
                        iam.sign(service, iam.authoritySigningKey(), "at+jwt"),
                        iam.integration(iam.integrationClaims()))) {
            try (var h = HttpClient.newHttpClient()) {
                var r =
                        h.send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + context.getWebServer().getPort()
                                                                + "/admin/apps/"
                                                                + CODE
                                                                + "/runs"))
                                        .header("Authorization", "Bearer " + bad)
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).isIn(401, 403);
            }
        }
        assertThat(iam.authorityCalls()).isZero();
        assertThat(stats().path("runs")).isEmpty();
    }

    static void flatten(String prefix, JsonNode v, Map<String, Object> p) {
        if (v.isObject())
            v.fields()
                    .forEachRemaining(
                            e -> {
                                String k =
                                        prefix.endsWith("attribute-value-allowlist")
                                                ? "[" + e.getKey() + "]"
                                                : "."
                                                        + e.getKey()
                                                                .replaceAll(
                                                                        "([a-z])([A-Z])", "$1-$2")
                                                                .toLowerCase(Locale.ROOT);
                                flatten(prefix + k, e.getValue(), p);
                            });
        else if (v.isArray()) {
            for (int i = 0; i < v.size(); i++) flatten(prefix + "[" + i + "]", v.get(i), p);
        } else if (!v.isNull())
            p.put(
                    prefix,
                    v.isBoolean() ? v.booleanValue() : v.isNumber() ? v.numberValue() : v.asText());
    }

    static final class Child implements AutoCloseable {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final String prefix;
        final StringBuilder output = new StringBuilder();
        final Thread drain;

        Child(String module, String main, String prefix) throws Exception {
            this.prefix = prefix;
            String cp =
                    Files.readString(
                                    ROOT.resolve(
                                            ".local/iam-n4b2-gateway-published-chain/"
                                                    + module
                                                    + "-classpath.txt"))
                            .strip();
            for (String entry : cp.split(";")) {
                String rel =
                        ROOT.relativize(Path.of(entry).toRealPath()).toString().replace('\\', '/');
                assertThat(
                                rel.startsWith(".tools/m2/")
                                        || rel.startsWith(
                                                (module.equals("app")
                                                                ? "app-management"
                                                                : "engine-runtime")
                                                        + "/target/")
                                        || rel.startsWith("admin-common/target/"))
                        .as(rel)
                        .isTrue();
            }
            process =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin/java.exe")
                                            .toString(),
                                    "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp",
                                    "-cp",
                                    cp,
                                    main)
                            .directory(ROOT.toFile())
                            .redirectErrorStream(true)
                            .start();
            stdin = process.outputWriter(StandardCharsets.UTF_8);
            drain =
                    Thread.ofPlatform()
                            .daemon(true)
                            .start(
                                    () -> {
                                        try (var r = process.inputReader(StandardCharsets.UTF_8)) {
                                            for (String l; (l = r.readLine()) != null; ) {
                                                lines.add(l);
                                                synchronized (output) {
                                                    output.append(l).append('\n');
                                                }
                                            }
                                        } catch (IOException e) {
                                            lines.add("CHILD_IO_FAILURE");
                                        }
                                    });
        }

        void init(Object value) throws Exception {
            stdin.write(Json.write(value));
            stdin.newLine();
            stdin.flush();
        }

        JsonNode ready() throws Exception {
            return Json.read(await(prefix + "READY=", 30));
        }

        JsonNode command(Object value) throws Exception {
            init(value);
            return Json.read(await(prefix + "REPLY=", 8));
        }

        String await(String key, int seconds) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < until) {
                String l = lines.poll(100, TimeUnit.MILLISECONDS);
                if (l != null && l.startsWith(key)) return l.substring(key.length());
                if (!process.isAlive()) break;
            }
            throw new AssertionError("Finite child missing " + key + "\n" + output);
        }

        public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    init(Map.of("action", "STOP"));
                    stdin.close();
                    if (!process.waitFor(15, TimeUnit.SECONDS))
                        throw new AssertionError("FINITE_CHILD_STOP_TIMEOUT");
                }
                assertThat(process.exitValue()).isZero();
            } finally {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(3, TimeUnit.SECONDS);
                    }
                }
                drain.join(2000);
                System.out.println(output);
                System.out.println(
                        "N4B2_CHILD_EXIT="
                                + Json.write(
                                        Map.of(
                                                "prefix",
                                                prefix,
                                                "pid",
                                                process.pid(),
                                                "alive",
                                                process.isAlive(),
                                                "exit",
                                                process.exitValue())));
            }
        }
    }
}
