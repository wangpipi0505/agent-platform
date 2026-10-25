package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

public class ServiceTokensDeploymentModeTest {
    static final Actor ACTOR = new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of("region", Set.of("A")));

    @Test void actualIamOverridesJvmTestAndClosesAllLegacySignersBeforeListener() throws Exception {
        runScenario("iam-before-listener");
    }

    public static void runScenario(String scenario) throws Exception {
        Path output = Files.createTempFile("n4a-" + scenario, ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("java.class.path"), ServiceTokensDeploymentModeTest.class.getName(), scenario)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).as("bounded child " + scenario).isTrue();
            String raw = Files.readString(output);
            System.out.print(raw);
            assertThat(child.exitValue()).as(raw).isZero();
            assertThat(raw).contains("N4A_EXECUTED=" + scenario);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
            Files.deleteIfExists(output);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!"iam-before-listener".equals(args[0])) throw new AssertionError("unknown scenario");
        System.setProperty("agent.security.mode", "test");
        System.setProperty("spring.profiles.active", "test");
        var factories = new AtomicInteger();
        var app = AuthorityConfigurationTest.application(Map.of(), factories);
        // Defaults lose to JVM properties. This source is actually ahead of systemProperties.
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("n4a-trusted-iam", AuthorityConfigurationTest.completeIam())));
        assertThatThrownBy(() -> { try (var ignored = app.run()) {} })
                .hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
        assertThat(factories).hasValue(0);
        assertThatThrownBy(() -> ServiceTokens.issue(ACTOR)).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ServiceTokens.userToken(ACTOR, 30)).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ServiceTokens.integrationToken(ACTOR,"app","biz",1,"digest",30))
                .isInstanceOf(PlatformException.class);
        System.out.println("N4A_EXECUTED=" + args[0]);
    }
}
