package cn.agentplatform.app.application;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "R45_APP_TEST_URL", matches = ".+")
class ModelRefreezePostgresTest {
    @Test void seedModelRepairAndRefreezeKeepPublishedVersionImmutableAndAuditReplays() {
        var source = new DriverManagerDataSource(System.getenv("R45_APP_TEST_URL"),
                System.getenv("AGENT_DB_USER"), System.getenv("AGENT_DB_PASSWORD"));
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("select current_database()", String.class)).startsWith("agent_platform_refreeze_");
        Flyway.configure().dataSource(source).schemas("app").defaultSchema("app").locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        var entry = Map.of("modelName", "test-model", "id", 17);
        var apps = new AppService(db, new DataSourceTransactionManager(source), "", "", "", "") {
            @Override public Map<String, Object> remotePost(String base, String path, Map<String, Object> body) {
                assertThat(path).isEqualTo("/internal/model-bindings/freeze");
                assertThat(body.get("modelEntryId")).isEqualTo(17L);
                return Map.of("bindingId", 53, "entry", entry, "sha256", Digest.sha256(Json.canonical(entry)));
            }
        };
        String code = "satellite-planning-assistant";
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("operator")
                .claim("tenant_id", "demo").claim("roles", List.of("ADMIN")).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            var draft = apps.get(code);
            long rev = ((Number) draft.get("draftRev")).longValue();
            assertThatThrownBy(() -> apps.refreeze(code, Map.of("requestId", "empty", "draftRev", rev)))
                    .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.code()).isEqualTo("BINDING_NOT_FROZEN"));
            String published = db.queryForObject("select snapshot::text from app.app_version where tenant_id='demo' and app_code=? and version_no=1", String.class, code);
            var cfg = new java.util.LinkedHashMap<>(AppService.object(draft.get("config")));
            cfg.put("modelBinding", Map.of("modelEntryId", 17));
            cfg.put("context", Map.of("maxTurns", cfg.remove("maxTurns")));
            var saved = apps.save(code, Map.of("draftRev", rev, "config", cfg), false);
            assertThat(((Number) saved.get("draftRev")).longValue()).isEqualTo(rev + 1);
            var input = Map.<String,Object>of("requestId", "repair", "draftRev", rev + 1);
            var receipt = apps.refreeze(code, input);
            assertThat(receipt.get("changed")).isEqualTo(true);
            assertThat(Json.canonical(apps.refreeze(code, input))).isEqualTo(Json.canonical(receipt));
            assertThat(apps.refreeze(code, Map.of("requestId", "recheck", "draftRev", rev + 2)).get("changed")).isEqualTo(false);
            assertThatThrownBy(() -> apps.refreeze(code, Map.of("requestId", "repair", "draftRev", rev + 2)))
                    .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.status()).isEqualTo(409));
            assertThat(db.queryForObject("select count(*) from app.lifecycle_log where tenant_id='demo' and app_code=? and action='MODEL_REFREEZE'", Integer.class, code)).isEqualTo(2);
            assertThat(db.queryForObject("select snapshot::text from app.app_version where tenant_id='demo' and app_code=? and version_no=1", String.class, code)).isEqualTo(published);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
