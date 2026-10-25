package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

public class ServiceTokensDeploymentModeTest {
    static final Actor ACTOR = new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of("region", Set.of("A")));

    @Test void unregisteredJvmTestCannotIssue() throws Exception { runScenario("unregistered"); }
    @Test void actualLegacySnapshotSurvivesJvmMutationsAndContextClose() throws Exception { runScenario("legacy-snapshot"); }
    @Test void actualIamOverridesJvmTestAndClosesAllLegacySignersBeforeListener() throws Exception { runScenario("iam-before-listener"); }
    @Test void legacyThenIamClosesSigningAcrossThreads() throws Exception { runScenario("legacy-then-iam"); }
    @Test void invalidDeploymentDoesNotRegisterPermission() throws Exception { runScenario("invalid-registration"); }
    @Test void isolatedReceiverExplicitlyRegistersIamWithoutOpeningProductionChain() throws Exception { runScenario("isolated-receiver"); }

    // Fixed finite child scenarios use this module's own classpath; no reset, reflection or JUnit order dependency.
    public static void runScenario(String scenario) throws Exception {
        if (!Set.of("unregistered", "legacy-snapshot", "iam-before-listener", "legacy-then-iam", "invalid-registration",
                "isolated-receiver", "common-boundary", "authority-complete-iam", "authority-dev-hmac", "authority-test",
                "authority-gateway", "authority-loopback-iam", "authority-portal").contains(scenario)) throw new AssertionError("unknown child");
        Path output = Files.createTempFile("n4a-" + scenario, ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-cp", System.getProperty("java.class.path"), ServiceTokensDeploymentModeTest.class.getName(), scenario)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            boolean finished = child.waitFor(45, TimeUnit.SECONDS);
            if (!finished) { child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
            String raw = Files.readString(output);
            System.out.print(raw);
            assertThat(finished).as("bounded child " + scenario).isTrue();
            assertThat(child.exitValue()).as(raw).isZero();
            assertThat(raw).contains("N4A_EXECUTED=" + scenario);
            System.out.println("N4A_CHILD_REAPED=" + scenario + ":" + child.pid());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            Files.deleteIfExists(output);
        }
    }

    static StandardEnvironment environment(Map<String, Object> properties, boolean test) {
        var env = new StandardEnvironment();
        var input = new LinkedHashMap<String, Object>();
        input.put("agent.security.service-id", "engine"); input.put("server.address", "127.0.0.1");
        input.putAll(properties);
        env.getPropertySources().addFirst(new MapPropertySource("n4a-actual-deployment", input));
        env.setActiveProfiles(test ? new String[]{"test"} : new String[0]);
        return env;
    }

    static void deniedAll() {
        assertThatThrownBy(() -> ServiceTokens.issue(ACTOR)).isInstanceOf(PlatformException.class)
                .extracting("code").isEqualTo("AUTHORITY_CONTEXT_MISSING");
        assertThatThrownBy(() -> ServiceTokens.userToken(ACTOR, 30)).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ServiceTokens.integrationToken(ACTOR,"app","biz",1,"digest",30))
                .isInstanceOf(PlatformException.class);
        assertThat(ServiceTokens.secretBytes()).hasSizeGreaterThanOrEqualTo(32);
    }

    static void claims(String token) throws Exception {
        var jwt = com.nimbusds.jwt.SignedJWT.parse(token);
        assertThat(jwt.verify(new com.nimbusds.jose.crypto.MACVerifier(ServiceTokens.secretBytes()))).isTrue();
        var c = jwt.getJWTClaimsSet();
        assertThat(c.getIssuer()).isEqualTo(ServiceTokens.ISSUER);
        assertThat(c.getSubject()).isEqualTo("alice");
        assertThat(c.getStringClaim("tenant_id")).isEqualTo("tenant-a");
        assertThat(c.getAudience()).containsExactly("agent-platform-internal");
        assertThat(c.getStringClaim("token_use")).isEqualTo("service");
        assertThat(c.getStringListClaim("roles")).containsExactlyInAnyOrder("EDITOR", "SERVICE");
        assertThat(c.getJSONObjectClaim("attributes")).containsKey("region");
        assertThat(c.getExpirationTime().getTime() - c.getIssueTime().getTime()).isEqualTo(120_000);
        assertThat(c.getJWTID()).isNotBlank();
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        switch (scenario) {
            case "unregistered" -> {
                System.setProperty("agent.security.mode", "test"); System.setProperty("spring.profiles.active", "test");
                assertThatThrownBy(() -> ServiceTokens.issue(ACTOR)).isInstanceOf(PlatformException.class);
                // Deliberate compatibility boundary: these two APIs can sign before any IAM registration.
                assertThat(com.nimbusds.jwt.SignedJWT.parse(ServiceTokens.userToken(ACTOR,30)).getJWTClaimsSet().getStringClaim("token_use")).isEqualTo("user");
                assertThat(com.nimbusds.jwt.SignedJWT.parse(ServiceTokens.integrationToken(ACTOR,"app","biz",1,"digest",30)).getJWTClaimsSet().getStringClaim("token_use")).isEqualTo("integration");
            }
            case "legacy-snapshot" -> {
                var factories = new AtomicInteger();
                try (var context = AuthorityConfigurationTest.application(Map.of("agent.security.mode","test", "stage1.test-profile",true), factories).run()) {
                    assertThat(context.isActive()).isTrue(); assertThat(factories).hasValue(1);
                    claims(ServiceTokens.issue(ACTOR));
                }
                for (String mode : List.of("iam", "dev-hmac", "nonsense")) {
                    System.setProperty("agent.security.mode",mode); System.setProperty("spring.profiles.active","production");
                    System.setProperty("agent.dev-auth","false"); claims(ServiceTokens.issue(ACTOR));
                }
                // Both validated legacy modes are one permission category; JVM values are irrelevant.
                SecurityConfiguration.bindDeployment(environment(Map.of("agent.security.mode","dev-hmac", "agent.dev-auth",true),false));
                claims(ServiceTokens.issue(ACTOR));
                SecurityConfiguration.bindDeployment(environment(Map.of("agent.security.mode","test"),true));
                claims(ServiceTokens.issue(ACTOR));
            }
            case "iam-before-listener" -> {
                System.setProperty("agent.security.mode", "test"); System.setProperty("spring.profiles.active", "test");
                var factories = new AtomicInteger(); var app = AuthorityConfigurationTest.application(Map.of(), factories);
                // Defaults lose to JVM properties; this actual source precedes systemProperties.
                app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("n4a-trusted-iam", AuthorityConfigurationTest.completeIam())));
                assertThatThrownBy(() -> { try (var ignored = app.run()) {} }).hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
                assertThat(factories).hasValue(0); deniedAll();
                assertThatThrownBy(() -> { try (var ignored = app.run()) {} }).hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
                assertThat(factories).hasValue(0); deniedAll();
                System.setProperty("agent.security.mode","dev-hmac"); System.setProperty("agent.dev-auth","true");
                deniedAll();
                var legacy = AuthorityConfigurationTest.application(Map.of("agent.security.mode","dev-hmac","agent.dev-auth",true), factories);
                assertThatThrownBy(() -> { try (var ignored = legacy.run()) {} }).isInstanceOf(PlatformException.class).extracting("code").isEqualTo("AUTHORITY_CONTEXT_MISSING");
                assertThat(factories).hasValue(0); deniedAll();
            }
            case "legacy-then-iam" -> {
                SecurityConfiguration.bindDeployment(environment(Map.of("agent.security.mode","test"),true));
                String alreadySigned = ServiceTokens.issue(ACTOR); claims(alreadySigned);
                var release = new CountDownLatch(1); var executor = Executors.newSingleThreadExecutor();
                try {
                    var afterRegistration = executor.submit(() -> { assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); deniedAll(); return true; });
                    SecurityConfiguration.bindDeployment(environment(AuthorityConfigurationTest.completeIam(),false));
                    deniedAll(); release.countDown(); assertThat(afterRegistration.get(5,TimeUnit.SECONDS)).isTrue();
                    claims(alreadySigned); // A string signed before registration is not retroactively removed.
                    assertThatThrownBy(() -> SecurityConfiguration.bindDeployment(environment(Map.of("agent.security.mode","test"),true)))
                            .isInstanceOf(PlatformException.class);
                    deniedAll();
                } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
            }
            case "invalid-registration" -> {
                System.setProperty("agent.security.mode","test"); System.setProperty("spring.profiles.active","test");
                for (var invalid : AuthorityConfigurationTest.invalidConfigurations().filter(i -> !i.name().equals("complete-iam-still-closed")).toList()) {
                    var input = new LinkedHashMap<>(invalid.properties());
                    // This scenario uses explicit Environment profiles; keep the original helper's meaning.
                    var env = environment(input, Boolean.TRUE.equals(input.remove("stage1.test-profile")));
                    env.getPropertySources().remove("systemProperties");
                    assertThatThrownBy(() -> SecurityConfiguration.bindDeployment(env)).as(invalid.name()).hasStackTraceContaining(invalid.reason());
                    assertThatThrownBy(() -> ServiceTokens.issue(ACTOR)).as(invalid.name()).isInstanceOf(PlatformException.class);
                }
            }
            case "common-boundary" -> {
                SecurityConfiguration.bindDeployment(environment(Map.of("agent.security.mode","test"),true));
                CommonBoundaryTest.legacyTicketAssertions();
            }
            case "isolated-receiver" -> isolatedReceiver();
            case "authority-complete-iam", "authority-dev-hmac", "authority-test", "authority-gateway", "authority-loopback-iam", "authority-portal" -> AuthorityConfigurationTest.main(args);
            default -> throw new AssertionError("unknown scenario");
        }
        System.out.println("N4A_EXECUTED=" + scenario);
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class Receiver {
        @Bean static BeanFactoryPostProcessor receiverDeployment(Environment environment) {
            return factory -> factory.registerSingleton("validatedReceiverDeployment", SecurityConfiguration.bindDeployment(environment));
        }
        @Bean SecurityFilterChain receiverChain(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(c -> c.anyRequest().denyAll()).build();
        }
    }

    static void isolatedReceiver() throws Exception {
        var input = AuthorityConfigurationTest.completeIam();
        input.put("server.address","127.0.0.1"); input.put("server.port",0);
        input.put("spring.config.location","optional:classpath:/n4a-no-config/");
        input.put("spring.main.banner-mode","off"); input.put("logging.level.root","ERROR");
        input.put("spring.jmx.enabled",false); input.put("springdoc.api-docs.enabled",false);
        var app = new SpringApplication(Receiver.class); app.setDefaultProperties(input);
        try (var context = app.run()) {
            assertThat(context.getBean("validatedReceiverDeployment", AuthorityProperties.class).mode()).isEqualTo("iam");
            int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/isolated"))
                        .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertThat(response.statusCode()).isEqualTo(403);
            }
            deniedAll();
            System.out.println("N4A_RECEIVER_PORT=" + port);
        }
        deniedAll();
        var factories = new AtomicInteger();
        assertThatThrownBy(() -> { try (var ignored = AuthorityConfigurationTest.application(AuthorityConfigurationTest.completeIam(),factories).run()) {} })
                .hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
        assertThat(factories).hasValue(0);
    }
}
