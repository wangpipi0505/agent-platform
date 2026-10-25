package cn.agentplatform.common.authority;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.*;
import java.nio.file.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class AuthorityPolicyTest {
    @Test void routeCannotBorrowCallerOrMethod() {
        var policy = new ServiceRoutePolicy();
        assertThat(policy.require("engine", "skill", "GET", "/internal/skills/{code}").action())
                .isEqualTo("skill.metadata.read");
        assertThatThrownBy(() -> policy.require("monitor", "skill", "GET", "/internal/skills/{code}"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> policy.require("engine", "skill", "POST", "/internal/skills/{code}"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> policy.require("engine", "unknown", "GET", "/internal/skills/{code}"))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> policy.require("engine", "skill", "GET", "/internal/skills/payroll"))
                .isInstanceOf(PlatformException.class);
    }

    @Test void adminCannotGrantServiceCapability() {
        var jwt = Jwt.withTokenValue("test").header("alg", "test").subject("admin")
                .claim("tenant_id", "t").claim("roles", List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            Actor.current().requireRole("EDITOR");
            assertThatThrownBy(() -> new ServiceRoutePolicy().require("monitor", "engine", "POST", "/internal/tool-calls/{id}/submitted"))
                    .isInstanceOf(PlatformException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void cannotRestoreRemovedRegion() {
        assertThat(ClaimCeiling.intersect(Set.of("A", "B"), Set.of("A"))).containsExactly("A");
        assertThat(ClaimCeiling.intersect(Set.of("A"), Set.of())).isEmpty();
        assertThat(ClaimCeiling.intersect(null, Set.of("A"))).isEmpty();
        assertThatThrownBy(() -> ClaimCeiling.intersect(Set.of("A"), Set.of("A")).add("B"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void labelsRemainObligationsAndMissingPermissionDoesNotAddAuthority() {
        var result = ClaimCeiling.attributes(
                Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("SECRET"), "userId", Set.of("u"), "bizSystemCode", Set.of("b"), "unknown", Set.of("x")),
                Map.of("dataLabels", Set.of("INTERNAL"), "userId", Set.of("u"), "bizSystemCode", Set.of("b")));
        assertThat(result.get("region")).isEmpty();
        assertThat(result.get("dataLabels")).containsExactlyInAnyOrder("SECRET", "INTERNAL");
        assertThat(result).doesNotContainKey("unknown");
        assertThatThrownBy(() -> result.put("region", Set.of("B"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.get("dataLabels").clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ClaimCeiling.attributes(Map.of("dataLabels", Set.of("SECRET")), Map.of()).get("dataLabels"))
                .containsExactly("SECRET");
    }

    @Test void ownershipMustMatchAndCannotBeCompletedFromOneSide() {
        for (String key : List.of("bizSystemCode", "userId")) {
            assertThat(ClaimCeiling.attributes(Map.of(key, Set.of("a")), Map.of(key, Set.of("a"))).get(key))
                    .containsExactly("a");
            assertThat(ClaimCeiling.attributes(Map.of(key, Set.of(" a ")), Map.of(key, Set.of(" a "))).get(key))
                    .containsExactly(" a ");
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(key, Set.of(" a ")), Map.of(key, Set.of("a"))))
                    .isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(key, Set.of("a")), Map.of(key, Set.of("b"))))
                    .isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(), Map.of(key, Set.of("a"))))
                    .isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> ClaimCeiling.attributes(Map.of(key, Set.of("a", "b")), Map.of(key, Set.of("a", "b"))))
                    .isInstanceOf(PlatformException.class);
        }
        var key = new SubjectKey("tenant", "principal");
        assertThat(ClaimCeiling.subject(key, key)).isEqualTo(key);
        assertThatThrownBy(() -> ClaimCeiling.subject(key, new SubjectKey("other", "principal")))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> ClaimCeiling.subject(key, null)).isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> new SubjectKey("", "p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceBinding("run", "1", "")).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidOwnershipSingletons() {
        return Stream.of("bizSystemCode", "userId").flatMap(key ->
                Stream.of("", " ", (String) null).map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest(name = "{0}: invalid singleton [{1}]")
    @MethodSource("invalidOwnershipSingletons")
    void blankOrNullOwnershipCannotPassEquality(String key, String value) {
        // Collections.singleton deliberately permits null, unlike Set.of: reject at the authority boundary.
        var attributes = Map.of(key, Collections.singleton(value));
        assertThatThrownBy(() -> ClaimCeiling.attributes(attributes, attributes))
                .isInstanceOfSatisfying(PlatformException.class, error -> {
                    assertThat(error.status()).isEqualTo(403);
                    assertThat(error.code()).isEqualTo("SYS-AUTH-403");
                });
    }

    @Test void catalogSeparatesActionsAndModesAndKeepsUnusedEntrypointsDisabled() {
        var policy = new ServiceRoutePolicy();
        assertThat(policy.require("skill", "engine", "POST", "/internal/tool-calls/{id}/fact").action())
                .isEqualTo("tool.fact.record").isNotEqualTo(policy.require("skill", "engine", "POST", "/internal/tool-calls/{id}/submitted").action());
        assertThat(policy.routes().stream().filter(r -> r.mode().equals("workload")))
                .extracting(ServiceRoutePolicy.Route::action).containsExactlyInAnyOrder("integration.credential.read", "integration.origin.check");
        for (var row : policy.routes()) {
            if (row.mode().equals("disabled")) {
                for (String caller : AuthorityProperties.SERVICES)
                    assertThatThrownBy(() -> policy.require(caller, row.target(), row.method(), row.template()))
                            .isInstanceOf(PlatformException.class);
            } else {
                assertThat(policy.require(row.caller(), row.target(), row.method(), row.template()).resource()).isNull();
                assertThat(row.template()).doesNotContain("*");
            }
        }
        assertThatThrownBy(() -> policy.require("engine", "model", "POST", "/internal/model/chat"))
                .isInstanceOf(PlatformException.class);
        assertThat(policy.require("skill", "model", "POST", "/internal/model/chat").action()).isEqualTo("model.chat");
        assertThatThrownBy(() -> policy.require("engine", "model", "GET", "/internal/material-scopes/{kind}/{id}"))
                .isInstanceOf(PlatformException.class);
    }

    @Test void duplicateAndUnknownCatalogEntriesFailClosed() {
        var row = new ServiceRoutePolicy.Route("GET", "/internal/example/{id}", "engine", "skill",
                "example.read", "delegated", false, "test", "test", "");
        assertThatThrownBy(() -> new ServiceRoutePolicy(List.of(row, row))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceRoutePolicy(List.of(new ServiceRoutePolicy.Route(
                "GET", "/internal/example/{id}", "unknown", "skill", "example.read", "delegated", false, "test", "test", ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceRoutePolicy(List.of(new ServiceRoutePolicy.Route(
                "GET", "/internal/**", "engine", "skill", "example.read", "delegated", false, "test", "test", ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceRoutePolicy(List.of(new ServiceRoutePolicy.Route(
                "GET", "/internal/example/{id}", "engine", "skill", "example.read", "unknown", false, "test", "test", ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceRoutePolicy(List.of(new ServiceRoutePolicy.Route(
                "GET", "/internal/example/{id}", null, "skill", "example.read", "disabled", false, "test", "test", "unused"), row)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void catalogCoversEveryActualControllerTemplateWithoutGrantingGatewayWildcards() throws Exception {
        var root = Path.of("..").toAbsolutePath().normalize();
        var actual = new HashSet<String>();
        var modules = Map.of("platform-gateway", "gateway", "app-management", "app", "engine-runtime", "engine",
                "skill-registry", "skill", "model-gateway", "model", "monitor", "monitor");
        var mapping = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\\((.*?)\\))?", Pattern.DOTALL);
        var path = Pattern.compile("\"(/[^\"\\n]*)\"");
        for (var module : modules.entrySet()) {
            try (var files = Files.walk(root.resolve(module.getKey()).resolve("src/main/java"))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    if (!source.contains("@RestController")) continue;
                    int classAt = source.indexOf("public class ");
                    assertThat(classAt).as(file.toString()).isGreaterThan(0);
                    var baseMapping = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)").matcher(source.substring(0, classAt));
                    String base = baseMapping.find() ? baseMapping.group(1) : "";
                    var methods = mapping.matcher(source.substring(classAt));
                    while (methods.find()) {
                        String verb = methods.group(1).equals("Request") ? "ANY" : methods.group(1).toUpperCase(Locale.ROOT);
                        var paths = path.matcher(Objects.toString(methods.group(2), ""));
                        boolean found = false;
                        while (paths.find()) { found = true; actual.add(module.getValue() + "|" + verb + "|" + base + paths.group(1)); }
                        if (!found) actual.add(module.getValue() + "|" + verb + "|" + base);
                    }
                }
            }
        }
        var policy = new ServiceRoutePolicy();
        var catalog = new HashSet<String>();
        for (var row : policy.routes()) {
            if (row.source().startsWith("springdoc-")) continue;
            catalog.add(row.target() + "|" + row.method() + "|" + row.template());
        }
        assertThat(actual).hasSize(158).containsExactlyInAnyOrderElementsOf(catalog);
        assertThat(policy.routes().stream().filter(r -> r.source().startsWith("springdoc-"))).hasSize(5);
        assertThat(policy.routes().stream().filter(ServiceRoutePolicy.Route::integrationAllowed))
                .allMatch(r -> r.caller().equals("gateway") && r.mode().equals("delegated") && r.template().startsWith("/v1/"))
                .hasSize(11);
        assertThatThrownBy(() -> policy.routes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
