package cn.agentplatform.common.authority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import cn.agentplatform.common.SecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EX-43 §3.2: the iam startup gate is a validating pass, not an unconditional rejection. Every
 * failure is local (no socket, no SecurityContext) and happens before Boot constructs a listener.
 */
class IamInboundGateTest {

    @Test
    void missingAuthorityConfigurationFailsWithIamConfigIncomplete() {
        var properties = new LinkedHashMap<>(AuthorityConfigurationTest.completeIam());
        properties.keySet().removeIf(key -> key.startsWith("agent.security.authority"));
        var factories = new AtomicInteger();
        assertThatThrownBy(() -> {
            try (var ignored = AuthorityConfigurationTest.application(properties, factories).run()) {
            }
        }).hasStackTraceContaining("IAM_CONFIG_INCOMPLETE");
        assertThat(factories).as("no listener on incomplete iam configuration").hasValue(0);
    }

    @Test
    void unresolvableClientSecretFailsWithIamClientSecretMissing() {
        var factories = new AtomicInteger();
        assertThatThrownBy(() -> {
            try (var ignored = AuthorityConfigurationTest.application(AuthorityConfigurationTest.completeIam(), factories).run()) {
            }
        }).hasStackTraceContaining("IAM_CLIENT_SECRET_MISSING");
        assertThat(factories).as("no listener before the local secret reference resolves").hasValue(0);
    }

    @Test
    void completeIamDeploymentAndCoverageCrossTheLocalGate() {
        MockEnvironment environment = IamTestEnvironments.completeIam();
        var properties = SecurityConfiguration.bindValidatedDeployment(environment);
        assertThat(properties.mode()).isEqualTo("iam");
        assertThatCode(() -> IamStartupGate.requireLocalClientSecret(environment, properties))
                .doesNotThrowAnyException();
        assertThatCode(() -> CoverageGate.verify("iam", "engine", IamTestEnvironments.routes(),
                IamTestEnvironments.mappings(Map.of())))
                .doesNotThrowAnyException();
    }

    @Test
    void missingInternalRouteBlocksIamButOnlyWarnsInDev() {
        var mappings = IamTestEnvironments.mappings(Map.of("/internal/unregistered", "GET"));
        assertThatThrownBy(() -> CoverageGate.verify("iam", "engine", IamTestEnvironments.routes(), mappings))
                .hasMessageStartingWith("ROUTE_COVERAGE_MISSING")
                .hasMessageContaining("/internal/unregistered");
        assertThatCode(() -> CoverageGate.verify("dev-hmac", "engine", IamTestEnvironments.routes(), mappings))
                .as("dev-hmac keeps the historical warn-only behaviour").doesNotThrowAnyException();
    }

    @Test
    void disabledCatalogRowAlsoBlocksIamStartup() {
        var mappings = IamTestEnvironments.mappings(Map.of("/internal/disabled", "GET"));
        var routes = IamTestEnvironments.routes(new ServiceRoutePolicy.Route("GET", "/internal/disabled",
                null, "engine", "internal.disabled", "disabled", false, "TEST", null, "test-only"));
        assertThatThrownBy(() -> CoverageGate.verify("iam", "engine", routes, mappings))
                .hasMessageStartingWith("ROUTE_COVERAGE_MISSING")
                .hasMessageContaining("disabled");
        assertThatCode(() -> CoverageGate.verify("test", "engine", routes, mappings)).doesNotThrowAnyException();
    }
}
