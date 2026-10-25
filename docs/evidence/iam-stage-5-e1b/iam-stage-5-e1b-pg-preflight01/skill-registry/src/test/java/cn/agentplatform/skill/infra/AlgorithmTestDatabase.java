package cn.agentplatform.skill.infra;

import cn.agentplatform.common.*;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/** Only the two named algorithm databases in this project's dedicated PostgreSQL. */
public final class AlgorithmTestDatabase {
    public final JdbcTemplate db;
    public final DataSourceTransactionManager manager;

    public AlgorithmTestDatabase(String name, String target) {
        this(name, target, false);
    }

    AlgorithmTestDatabase(String name, String target, boolean resetMigrationSchema) {
        if (!Set.of(
                        "agent_platform_skill_algorithm_test",
                        "agent_platform_skill_algorithm_migration_test")
                .contains(name)) throw new IllegalArgumentException("Algorithm database whitelist");
        String password = System.getenv("AGENT_DB_PASSWORD");
        if (password == null || password.isBlank())
            throw new IllegalStateException("AGENT_DB_PASSWORD required; never skip PostgreSQL");
        var admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:25432/postgres",
                                "agent_v12",
                                password));
        if (admin.queryForObject(
                        "select count(*) from pg_database where datname=?", Integer.class, name)
                == 0) admin.execute("create database " + name);
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/" + name, "agent_v12", password);
        if (resetMigrationSchema) {
            if (!name.equals("agent_platform_skill_algorithm_migration_test"))
                throw new IllegalArgumentException("Only dedicated migration schema can reset");
            new JdbcTemplate(ds).execute("drop schema if exists skill cascade");
        }
        var configuration =
                Flyway.configure()
                        .dataSource(ds)
                        .schemas("skill")
                        .defaultSchema("skill")
                        .locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
        db = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
    }

    public static void identity(String tenant, String principal) {
        var jwt =
                Jwt.withTokenValue("algorithm-fixture-internal-context")
                        .header("alg", "HS256")
                        .subject(principal)
                        .claim("tenant_id", tenant)
                        .claim("roles", List.of("EDITOR", "ADMIN", "SERVICE"))
                        .claim("attributes", Map.of("region", List.of("A")))
                        .claim("token_use", "service")
                        .audience(List.of("agent-platform-internal"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(120))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    public static AlgorithmAdapter adapter(
            JdbcTemplate db, AlgorithmApiFixture api, int metadataLimit, long logLimit) {
        return new AlgorithmAdapter(
                db,
                Map.of(
                        "AGENT_K8S_API",
                        api.url(),
                        "AGENT_ALGORITHM_NAMESPACE",
                        "alg-test",
                        "AGENT_ALGORITHM_SERVICE_ACCOUNT",
                        "algorithm-runner",
                        "AGENT_ALGORITHM_ALLOWED_IMAGES",
                        "fixture/algorithm:fixed",
                        "AGENT_ALGORITHM_ALLOW_LOOPBACK_HTTP",
                        "true"),
                metadataLimit,
                logLimit);
    }
}
