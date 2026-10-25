package cn.agentplatform.common.authority;

import cn.agentplatform.app.api.*;
import cn.agentplatform.app.application.*;
import cn.agentplatform.common.*;

import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import javax.sql.DataSource;

/**
 * Own-module child fixture: production controller/service/guards, real new PG; control only STDIN.
 */
public final class PublishedAdmissionFixtureMain {
    static final String DATABASE = "agent_platform_iam_admission_app_v12", CODE = "admission-flow";
    static AuthorityProperties trust;
    static String secret;
    static DataSource ds;
    static JdbcTemplate db;
    static final AtomicReference<Instant> now = new AtomicReference<>();
    static final AtomicInteger publishedRequests = new AtomicInteger(),
            payloadReads = new AtomicInteger();
    static final Clock clock =
            new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId z) {
                    return this;
                }

                public Instant instant() {
                    return now.get();
                }
            };

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSecurity
    @Import({
        PublishedMetadataController.class,
        PublishedMetadataService.class,
        AppController.class,
        TemplateService.class,
        PlatformErrors.class
    })
    static class Receiver {
        @Bean
        AuthorityProperties authorityProperties() {
            return trust;
        }

        @Bean
        DataSource dataSource() {
            return ds;
        }

        @Bean
        JdbcTemplate jdbc() {
            return db;
        }

        @Bean
        AppService apps() {
            return new AppService(
                    db,
                    new DataSourceTransactionManager(ds),
                    "http://127.0.0.1:1",
                    "http://127.0.0.1:1",
                    "http://127.0.0.1:1",
                    "http://127.0.0.1:1");
        }

        @Bean(destroyMethod = "close")
        JwtDecoder decoder() {
            return new TrustedJwtDecoder(trust, clock, true);
        }

        @Bean(destroyMethod = "close")
        HttpCurrentAuthorityClient current() {
            return new HttpCurrentAuthorityClient(
                    trust, clock, new WorkloadCredentialResolver(trust, n -> secret, true), true);
        }

        @Bean
        ServiceAuthorization authorization(CurrentAuthorityClient c) {
            return new ServiceAuthorization("app", new ServiceRoutePolicy(), c);
        }

        @Bean
        SecurityFilterChain security(HttpSecurity h, JwtDecoder d, ServiceAuthorization a)
                throws Exception {
            return SecurityConfiguration.serviceSecurity(h, d, a);
        }

        @Bean
        org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a) {
            return a.mappedInterceptor();
        }

        @Bean
        RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> mappings) {
            return new RouteCoverageVerifier("app", new ServiceRoutePolicy(), mappings);
        }

        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
                observer() {
            var registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<
                            jakarta.servlet.Filter>(
                            (request, response, chain) -> {
                                if (((jakarta.servlet.http.HttpServletRequest) request)
                                        .getRequestURI()
                                        .endsWith("/published"))
                                    publishedRequests.incrementAndGet();
                                chain.doFilter(request, response);
                            });
            registration.setOrder(-200);
            return registration;
        }
    }

    public static void main(String[] args) throws Exception {
        try (var input =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var init = Json.read(Objects.requireNonNull(input.readLine()));
            trust = Json.read(init.path("trust").toString(), AuthorityProperties.class);
            trust.validate(true);
            secret = init.path("secret").textValue();
            now.set(Instant.parse(init.path("now").textValue()));
            String password = System.getenv("AGENT_DB_PASSWORD");
            if (password == null || password.isBlank())
                throw new IllegalStateException("ISOLATED_DB_CREDENTIAL_REQUIRED");
            var admin =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    "jdbc:postgresql://127.0.0.1:25432/postgres",
                                    "agent_v12",
                                    password));
            boolean existed =
                    Boolean.TRUE.equals(
                            admin.queryForObject(
                                    "select exists(select 1 from pg_database where datname=?)",
                                    Boolean.class,
                                    DATABASE));
            System.out.println("N3_APP_DB_EXISTED=" + existed);
            if (!existed) admin.execute("create database " + DATABASE);
            ds =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://127.0.0.1:25432/" + DATABASE, "agent_v12", password);
            db =
                    new JdbcTemplate(ds) {
                        @Override
                        public <T> List<T> query(String sql, RowMapper<T> row, Object... args) {
                            if (sql.startsWith("select snapshot::text"))
                                payloadReads.incrementAndGet();
                            return super.query(sql, row, args);
                        }
                    };
            if (!DATABASE.equals(db.queryForObject("select current_database()", String.class)))
                throw new AssertionError();
            var files = Files.list(Path.of("app-management/src/main/resources/db/migration"));
            try (files) {
                int max =
                        files.map(p -> p.getFileName().toString())
                                .filter(n -> n.matches("V\\d+__.*\\.sql"))
                                .mapToInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__"))))
                                .max()
                                .orElseThrow();
                if (max != 104) throw new AssertionError("APP_SOURCE_MAX");
                System.out.println("N3_APP_SOURCE_MAX=" + max);
            }
            boolean history =
                    db.queryForObject(
                            "select to_regclass('app.flyway_schema_history') is not null",
                            Boolean.class);
            System.out.println(
                    "N3_APP_HISTORY_BEFORE="
                            + Json.write(
                                    history
                                            ? db.queryForList(
                                                    "select version,description,checksum,success"
                                                        + " from app.flyway_schema_history order by"
                                                        + " installed_rank")
                                            : List.of()));
            Flyway.configure()
                    .dataSource(ds)
                    .schemas("app")
                    .defaultSchema("app")
                    .locations("classpath:db/migration")
                    .target("104")
                    .load()
                    .migrate();
            var rows =
                    db.queryForList(
                            "select version,description,checksum,success from"
                                + " app.flyway_schema_history order by installed_rank");
            System.out.println("N3_APP_HISTORY_AFTER=" + Json.write(rows));
            if (db.queryForObject(
                            "select count(*) from app.flyway_schema_history where success and"
                                + " version in ('100','101','102','103','104')",
                            Integer.class)
                    != 5) throw new AssertionError("APP_HISTORY");
            var application = new SpringApplication(Receiver.class);
            application.setAdditionalProfiles("test");
            application.setDefaultProperties(
                    Map.ofEntries(
                            Map.entry("server.address", "127.0.0.1"),
                            Map.entry("server.port", "0"),
                            Map.entry(
                                    "spring.config.location",
                                    "optional:classpath:/n3-child-no-config/"),
                            Map.entry("spring.main.banner-mode", "off"),
                            Map.entry("springdoc.api-docs.enabled", false),
                            Map.entry("spring.flyway.enabled", false),
                            Map.entry("management.endpoints.web.exposure.exclude", "*"),
                            Map.entry("management.endpoints.web.discovery.enabled", false),
                            Map.entry("logging.level.root", "ERROR")));
            try (var context = (ServletWebServerApplicationContext) application.run()) {
                System.out.println(
                        "N3_APP_READY="
                                + Json.write(
                                        Map.of(
                                                "port",
                                                context.getWebServer().getPort(),
                                                "pid",
                                                ProcessHandle.current().pid(),
                                                "database",
                                                DATABASE)));
                System.out.flush();
                for (String line; (line = input.readLine()) != null; ) {
                    var command = Json.read(line);
                    String action = command.path("action").asText();
                    if (action.equals("STOP")) break;
                    if (action.equals("clock")) {
                        now.set(Instant.parse(command.path("now").asText()));
                        reply(Map.of("clock", now.get().toString()));
                        continue;
                    }
                    if (action.equals("stats")) {
                        reply(
                                Map.of(
                                        "publishedRequests",
                                        publishedRequests.get(),
                                        "payloadReads",
                                        payloadReads.get()));
                        continue;
                    }
                    String tenant = command.path("tenant").asText();
                    if (!tenant.startsWith("n3-"))
                        throw new IllegalArgumentException("FINITE_TENANT_REQUIRED");
                    if (action.equals("seed")) {
                        db.update(
                                "insert into"
                                    + " app.application(tenant_id,code,name,type,status,current_version_no,created_by)"
                                    + " values(?,?,'N3 fixture','FLOW','PUBLISHED',1,'alice')",
                                tenant,
                                CODE);
                        db.update(
                                "insert into app.app_draft(tenant_id,app_id,app_code) select"
                                    + " tenant_id,id,code from app.application where tenant_id=?"
                                    + " and code=?",
                                tenant,
                                CODE);
                        var snapshot = command.path("snapshot");
                        for (int version = 1; version <= 2; version++) {
                            var stored =
                                    (com.fasterxml.jackson.databind.node.ObjectNode)
                                            snapshot.deepCopy();
                            stored.put("versionNo", version);
                            stored.put(
                                    "candidateSha256",
                                    command.path(version == 1 ? "sha" : "sha2").asText());
                            stored.put("appVersionId", CODE + "@" + version);
                            db.update(
                                    "insert into"
                                        + " app.app_version(tenant_id,app_code,version_no,draft_rev,candidate_sha256,snapshot,eval_report_id,published_by,app_id,app_draft_id)"
                                        + " select"
                                        + " a.tenant_id,a.code,?,?,?,?::jsonb,'finite','alice',a.id,d.id"
                                        + " from app.application a join app.app_draft d on"
                                        + " d.tenant_id=a.tenant_id and d.app_code=a.code where"
                                        + " a.tenant_id=? and a.code=?",
                                    version,
                                    version,
                                    command.path(version == 1 ? "sha" : "sha2").asText(),
                                    Json.write(stored),
                                    tenant,
                                    CODE);
                        }
                        reply(
                                Map.of(
                                        "versionId",
                                        db.queryForObject(
                                                "select id from app.app_version where tenant_id=?"
                                                    + " and app_code=? and version_no=1",
                                                Long.class,
                                                tenant,
                                                CODE)));
                    } else if (action.equals("pointer")) {
                        db.update(
                                "update app.application set current_version_no=?,status=? where"
                                    + " tenant_id=? and code=?",
                                command.path("version").asInt(),
                                command.path("status").asText(),
                                tenant,
                                CODE);
                        reply(Map.of("updated", true));
                    } else throw new IllegalArgumentException("FINITE_COMMAND_REQUIRED");
                }
            }
            System.out.println("N3_APP_STOPPED=" + ProcessHandle.current().pid());
            System.out.flush();
        } finally {
            secret = null;
        }
    }

    static void reply(Object value) {
        System.out.println("N3_APP_REPLY=" + Json.write(value));
        System.out.flush();
    }
}
