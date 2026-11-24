package cn.agentplatform.authority;

import cn.agentplatform.authority.config.IssuerResolver;
import cn.agentplatform.authority.identity.IdentityProperties;
import cn.agentplatform.authority.identity.SessionService;
import cn.agentplatform.authority.sign.KeyRing;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class SubjectLookupPolicyTest {

    @Test
    void missingReferencePreventsStartupOutsideTestAndDevelopment() {
        context("http://127.0.0.1", false).run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure())
                    .hasStackTraceContaining("subject-lookup-key-ref");
        });
        context("http://127.0.0.1", false, "test", "production").run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure())
                    .hasStackTraceContaining("subject-lookup-key-ref");
        });
    }

    @Test
    void missingReferenceWarnsAndDeniesPortalSubjectInTestProfile(CapturedOutput output) {
        context("http://127.0.0.1", true, "test").run(application ->
                assertThatExceptionOfType(AuthorityException.class)
                        .isThrownBy(() -> application.getBean(SessionService.class)
                                .requireCurrentIdentity("demo", "operator"))
                        .satisfies(error -> {
                            assertThat(error.status()).isEqualTo(503);
                            assertThat(error.code()).isEqualTo("IAM_IDENTITY_UNAVAILABLE");
                        }));
        assertThat(output).contains("已启用会话签发，但缺少 subject-lookup-key-ref");
    }

    @Test
    void directoryDecidesPortalAdmissionWithSessionIssuanceDisabled() throws IOException {
        var status = new AtomicReference<>("0");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/apikeys/identity/operator", request -> {
            byte[] body = ("{\"code\":200,\"data\":{\"userName\":\"operator\",\"status\":\""
                    + status.get() + "\"}}").getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length);
            try (var output = request.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            context("http://127.0.0.1:" + server.getAddress().getPort(), false, "production")
                    .withPropertyValues(
                            "agent.authority.identity.subject-lookup-key-ref=env:AGENT_R38_TEST_LOOKUP_KEY")
                    .run(application -> {
                        SessionService service = application.getBean(SessionService.class);
                        service.requireCurrentIdentity("demo", "operator");
                        status.set("1");
                        assertThatExceptionOfType(AuthorityException.class)
                                .isThrownBy(() -> service.requireCurrentIdentity("demo", "operator"))
                                .satisfies(error -> {
                                    assertThat(error.status()).isEqualTo(401);
                                    assertThat(error.code()).isEqualTo("SYS-AUTH-401");
                                });
                    });
        } finally {
            server.stop(0);
        }
    }

    private static ApplicationContextRunner context(String directoryUrl, boolean sessionEnabled, String... profiles) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=", "agent.authority.identity.subject-lookup-key-ref=")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withBean(KeyRing.class, () -> mock(KeyRing.class))
                .withBean(IssuerResolver.class, () -> mock(IssuerResolver.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(IdentityProperties.class, () -> new IdentityProperties(
                        sessionEnabled, directoryUrl, null, "/system/apikeys/identity/self", "demo",
                        AuthorityTestSupport.ISSUER, "agent-platform-portal", "gateway", "platform.access",
                        "agent-platform",
                        2_000, 2_000, 3_600, null, "USER_MGMT", null, "0", null))
                .withUserConfiguration(SessionService.class);
    }
}
