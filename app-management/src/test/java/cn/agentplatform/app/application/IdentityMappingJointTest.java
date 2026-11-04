package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.IdentityMappingRepository;
import cn.agentplatform.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfEnvironmentVariable(named = "AGENT_DB_PASSWORD", matches = ".+")
@SpringBootTest(classes = IdentityMappingJointTest.MappingApp.class, properties = {
        "agent.security.mode=test", "agent.security.service-id=app", "server.address=127.0.0.1"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class IdentityMappingJointTest {
    static final String OLD_KEY = System.getProperty("agent.jwt.secret");
    @Autowired MockMvc mvc;
    @Autowired IdentityMappingRepository repository;

    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @Import({IdentityMappingService.class, IdentityMappingRepository.class, SecurityConfiguration.class, PlatformErrors.class, TraceContextFilter.class})
    @ComponentScan(basePackages = "cn.agentplatform.app.api", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "cn\\.agentplatform\\.app\\.api\\.IdentityMappingController"))
    static class MappingApp {}

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        String key = System.getenv().getOrDefault("AGENT_JWT_SECRET", "ex23-independent-current-subject-0123456789abcdef");
        System.setProperty("agent.jwt.secret", key); p.add("agent.security.jwt-secret", () -> key);
        p.add("spring.datasource.url", TestDatabase::url);
        p.add("spring.datasource.username", TestDatabase::user);
        p.add("spring.datasource.password", () -> TestDatabase.password());
        // 测试实例 max_connections=20：小池避免缓存的 Spring 上下文占满连接槽。
        p.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        p.add("spring.flyway.default-schema", () -> "app"); p.add("spring.flyway.schemas", () -> "app");
    }
    @AfterAll static void restore() { if (OLD_KEY == null) System.clearProperty("agent.jwt.secret"); else System.setProperty("agent.jwt.secret", OLD_KEY); }

    @Test void verifiedCurrentSubjectIsStableTenantIsolatedAndChangedPermissionsCannotReuseTheOldRow() throws Exception {
        var actor = new Actor("ex23-" + UUID.randomUUID(), "alice", Set.of("EDITOR", "SERVICE"), Map.of("region", Set.of("A")));
        String token = ServiceTokens.issue(actor);
        var register = mvc.perform(put("/admin/system/identity-mapping/current").header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "mallory").header("X-User-Tenant", "forged-tenant")
                        .contentType("application/json").content("{\"tenantId\":\"forged\",\"principalId\":\"mallory\"}"))
                .andReturn().getResponse();
        assertThat(register.getStatus()).isEqualTo(200);
        var first = Json.read(register.getContentAsString()).path("data");
        assertThat(first.path("tenantId").asText()).isEqualTo(actor.tenantId());
        assertThat(first.path("principalId").asText()).isEqualTo("alice");
        assertThat(first.path("mappingKey").asText()).isEqualTo(actor.subject().mappingKey());
        var replay = mvc.perform(put("/admin/system/identity-mapping/current").header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(Json.read(replay.getContentAsString()).path("data").path("revision")).isEqualTo(first.path("revision"));
        var other = new Actor(actor.tenantId() + "-other", "alice", actor.roles(), actor.attributes());
        var absent = mvc.perform(get("/internal/identity-mappings/current").header("Authorization", "Bearer " + ServiceTokens.issue(other))
                .header("X-User-Tenant", actor.tenantId())).andReturn().getResponse();
        assertThat(absent.getStatus()).isEqualTo(404);
        assertThat(Json.read(absent.getContentAsString()).path("code").asText()).isEqualTo("IDENTITY_MAPPING_NOT_FOUND");
        var isolated = mvc.perform(put("/admin/system/identity-mapping/current").header("Authorization", "Bearer " + ServiceTokens.issue(other))).andReturn().getResponse();
        assertThat(isolated.getStatus()).isEqualTo(200);
        assertThat(Json.read(isolated.getContentAsString()).path("data").path("mappingKey").asText()).isNotEqualTo(first.path("mappingKey").asText());
        var narrowed = new Actor(actor.tenantId(), "alice", Set.of("VIEWER", "SERVICE"), actor.attributes());
        var changed = repository.upsert(narrowed.subject(), "trusted-fixture-permission-update");
        assertThat(changed.revision()).isEqualTo(first.path("revision").asLong() + 1);
        var stale = mvc.perform(get("/internal/identity-mappings/current").header("Authorization", "Bearer " + token)
                .header("X-User-Roles", "ADMIN")).andReturn().getResponse();
        assertThat(stale.getStatus()).isEqualTo(403);
        assertThat(Json.read(stale.getContentAsString()).path("code").asText()).isEqualTo("IDENTITY_MAPPING_CHANGED");
        assertThat(repository.find(actor.tenantId(), "alice").orElseThrow().mappingKey()).isEqualTo(narrowed.subject().mappingKey());
    }
}
