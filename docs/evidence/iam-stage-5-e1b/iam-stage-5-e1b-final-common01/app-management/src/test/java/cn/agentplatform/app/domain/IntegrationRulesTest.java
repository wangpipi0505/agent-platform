package cn.agentplatform.app.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class IntegrationRulesTest {
    @Test
    void rejectsWildcardOriginsAndInsecurePublicHttp() {
        assertThrows(IllegalArgumentException.class, () -> IntegrationRules.origins(List.of("*")));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegrationRules.origins(List.of("http://example.com")));
        assertThrows(
                IllegalArgumentException.class,
                () -> IntegrationRules.origins(List.of("https://example.com/path")));
        IntegrationRules.origins(List.of("https://example.com", "http://127.0.0.1:8080"));
    }

    @Test
    void rejectsUnboundedClaims() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationRules.limits(
                                Map.of(
                                        "principalIds",
                                        List.of("*"),
                                        "roles",
                                        List.of(),
                                        "attributes",
                                        Map.of())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        IntegrationRules.limits(
                                Map.of(
                                        "principalIds",
                                        List.of(),
                                        "roles",
                                        List.of(),
                                        "attributes",
                                        Map.of())));
        IntegrationRules.limits(
                Map.of(
                        "principalIds",
                        List.of("alice"),
                        "roles",
                        List.of("operator"),
                        "attributes",
                        Map.of("region", List.of("A"))));
    }
}
