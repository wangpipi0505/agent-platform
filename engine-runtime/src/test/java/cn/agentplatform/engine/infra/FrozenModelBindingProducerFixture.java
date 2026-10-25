package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.*;
import cn.agentplatform.engine.application.ModelInputMaterials;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Separate JVM only: genuine accepted producer, fresh registered PG, real receiver security. */
public final class FrozenModelBindingProducerFixture implements AutoCloseable {
  static final ResourceBinding ROOT = RunAuthorityFixture.ROOT;
  static final ServiceCall MAIN = RunAuthorityFixture.MAIN;
  static FrozenModelBindingProducerFixture f;
  final LoopbackAuthority iam = new LoopbackAuthority();
  final HttpCurrentAuthorityClient gateway, engine;
  final RunAuthorityFixture.CountingDataSource ds;
  final JdbcTemplate db;
  final com.zaxxer.hikari.HikariDataSource pool;
  final JsonNode descriptor;
  final TransactionTemplate tx;
  final RunAuthorityStore runs;
  final ModelInputMaterials materials;
  final Path dir;
  final String database;
  final List<String> sql = new java.util.concurrent.CopyOnWriteArrayList<>();
  final AtomicInteger gets = new AtomicInteger(), unexpectedBody = new AtomicInteger();
  final Actor actor =
      new Actor(
          "tenant-a",
          "alice",
          Set.of("EDITOR", "READER"),
          Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("initial", "restricted")));
  final String originalPortal;
  ServletWebServerApplicationContext context;
  final List<Integer> ownedPorts = new ArrayList<>();
  final Map<String, Object> negatives = new LinkedHashMap<>();

  FrozenModelBindingProducerFixture(Path dir) throws Exception {
    this.dir = dir;
    Files.createDirectories(dir);
    iam.portalActor(actor);
    gateway = iam.client("gateway");
    engine = iam.client("engine");
    originalPortal = iam.portal(300);
    var config=Json.read(Files.readString(dir.resolve("engine-db.json")));
    database=config.path("database").asText();
    descriptor=Json.read(Files.readString(dir.resolve("binding.json")));
    var hc=new com.zaxxer.hikari.HikariConfig();
    hc.setJdbcUrl(config.path("url").asText());hc.setUsername(config.path("username").asText());hc.setPassword(config.path("password").asText());
    hc.setConnectionTimeout(2000);hc.setValidationTimeout(1000);hc.setMaximumPoolSize(2);hc.setMinimumIdle(0);
    pool=new com.zaxxer.hikari.HikariDataSource(hc);
    ds=new RunAuthorityFixture.CountingDataSource(pool);
    db = new JdbcTemplate(ds);
    ds.beforeSql = sql::add;
    Flyway.configure()
        .dataSource(ds)
        .schemas("engine")
        .defaultSchema("engine")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try(var connection=pool.getConnection();var statement=connection.createStatement()){
      try{statement.executeQuery("select count(*) from model.provider");throw new AssertionError("MODEL_SCHEMA_MUST_BE_DENIED");}
      catch(java.sql.SQLException expected){if(!expected.getMessage().contains("permission denied for schema model"))throw expected;negatives.put("modelSchemaAccessDenied",true);}
    }
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    runs = new RunAuthorityStore(ds, iam.clock());
    materials = new ModelInputMaterials(ds, engine, iam.clock());
    ownedPorts.add(java.net.URI.create(iam.issuer()).getPort());
    ownedPorts.add(java.net.URI.create(iam.policyIssuer()).getPort());
  }

  JsonNode snapshot() {
    var entry = descriptor.path("entry");
    return Json.tree(
        Map.of(
            "appCode",
            "app-a",
            "type",
            "CHAT",
            "versionNo",
            1,
            "appVersionId",
            12,
            "candidateSha256",
            ROOT.versionDigest(),
            "config",
            Map.of(
                "modelEntryId",
                descriptor.path("modelEntryId").longValue(),
                "systemPrompt",
                "Hello ${input.name}",
                "requiredLabels",
                List.of("config"),
                "params",
                Json.read(
                    "{\"temperature\":1.0,\"topP\":0.25,\"frequencyPenalty\":0.0,\"presencePenalty\":1e-5}")),
            "dependencyVersions",
            Map.of(
                "modelRefs",
                List.of(
                    Map.of(
                        "modelEntryId",
                        descriptor.path("modelEntryId").longValue(),
                        "modelBindingRef",
                        Map.of("bindingId", descriptor.path("bindingId").longValue(), "sha256", descriptor.path("sha256").asText()),
                        "entry",
                        entry)))));
  }

  JsonNode budget() {
    return Json.tree(
        Map.of(
            "limits",
            Map.of(
                "maxSteps",
                25,
                "modelCalls",
                40,
                "totalTokens",
                200000,
                "totalDurationMs",
                600000,
                "maxDepth",
                2),
            "consumed",
            Map.of("steps", 0, "modelCalls", 0, "tokens", 0, "depth", 0),
            "deadline",
            iam.clock().instant().plusSeconds(599).toString()));
  }

  long create() throws Exception {
    var ticket = gateway.exchange(originalPortal, MAIN);
    try (var decoder = new TrustedJwtDecoder(iam.config("engine"), iam.clock(), true)) {
      var req = new MockHttpServletRequest("POST", "/admin/apps/app-a/runs");
      var res = new MockHttpServletResponse();
      req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/apps/{code}/runs");
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
      SecurityContextHolder.getContext()
          .setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()), List.of()));
      var handler =
          new HandlerMethod(
              new RunController(null, null, null, null),
              RunController.class.getMethod("run", String.class, JsonNode.class));
      var guard = new ServiceAuthorization("engine", new ServiceRoutePolicy(), engine);
      try {
        guard.preHandle(req, res, handler);
        var proof = AuthorityContext.requireInitialFor(Actor.current(), ROOT);
        return tx.execute(
            s ->
                runs.createPublished(
                    proof,
                    snapshot(),
                    Json.read("{\"name\":\"Alice\",\"message\":\"private fixture input\"}"),
                    Json.read("{\"appVer\":1}"),
                    budget(),
                    "FULL",
                    UUID.randomUUID().toString(),
                    Set.of("parent")));
      } finally {
        guard.afterCompletion(req, res, handler, null);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
      }
    }
  }

  String modelTicket(DelegationRef delegation, ResourceBinding root) {
    return engine.resume(delegation, new ServiceCall("model", "model.chat-stream", root)).value();
  }

  DelegationRef delegation(String portal, ResourceBinding root) {
    var call = new ServiceCall("engine", MAIN.action(), root);
    var ticket = gateway.exchange(portal, call);
    return engine.authorizeRetained(ticket.value(), call).current().delegation();
  }

  void start() throws Exception {
    long run = create();
    var m = materials.prepareInitial(run);
    var binding = runs.load(run);
    String request =
        db.queryForObject(
            "select request::text from engine.model_input_snapshot where run_id=?",
            String.class,
            run);
    Files.writeString(dir.resolve("persisted-request.json"), request);
    // Export the accepted producer canonical bytes, not the jsonb display representation.
    // The real retained continuation and locked frozen sources reproduce prepareInitial's assembly.
    var retained =
        new cn.agentplatform.engine.application.RunAuthority(
                runs, engine, iam.clock(), Duration.ofSeconds(1))
            .currentRetained(run);
    String canonical =
        tx.execute(
            s ->
                Json.canonical(
                    ModelInputMaterials.assemble(
                        new ModelInputMaterialStore(ds, iam.clock()).lockInitial(run, retained),
                        retained)));
    if (!Digest.sha256(canonical).equals(m.requestSha256()))
      throw new AssertionError("ACCEPTED_PRODUCER_CANONICAL_SHA_REQUIRED");
    Files.writeString(
        dir.resolve("numeric-export.json"),
        Json.write(
            Map.of(
                "source",
                    "real retained continuation plus locked accepted frozen sources and actual"
                        + " producer assemble",
                "canonicalRequestSha", Digest.sha256(canonical),
                "manifestRequestSha", m.requestSha256(),
                "persistedDisplayCanonicalSha",
                    Digest.sha256(Json.canonical(Json.read(request))))));
    Files.writeString(dir.resolve("request.json"), canonical);
    Files.writeString(dir.resolve("manifest.json"), Json.write(m));
    Files.writeString(dir.resolve("model-config.json"), Json.write(iam.config("model")));
    Files.writeString(dir.resolve("model-secret.txt"), iam.testClientSecret("model"));
    var tickets = new LinkedHashMap<String, String>();
    tickets.put("first", modelTicket(binding.delegation(), ROOT));
    tickets.put("repeat", modelTicket(binding.delegation(), ROOT));
    tickets.put("newGrant", modelTicket(delegation(originalPortal, ROOT), ROOT));
    iam.portalActor(new Actor("tenant-a", "mallory", actor.roles(), actor.attributes()));
    tickets.put("wrongOwner", modelTicket(delegation(iam.portal(300), ROOT), ROOT));
    iam.portalActor(actor);
    var otherRoot = new ResourceBinding(ROOT.kind(), ROOT.id(), Digest.sha256("wrong root"));
    tickets.put("wrongRoot", modelTicket(delegation(originalPortal, otherRoot), otherRoot));
    tickets.put("differentOrigin", modelTicket(delegation(iam.portal(300), ROOT), ROOT));
    for (var e : tickets.entrySet())
      Files.writeString(dir.resolve(e.getKey() + "-ticket.txt"), e.getValue());
    // Supported INSERT-only legacy state, before any publication; never disable immutable triggers.
    long legacy =
        db.queryForObject(
            "insert into"
                + " engine.run(public_id,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode,request_id)"
                + " select"
                + " ?,app_code,app_version_id,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode,?"
                + " from engine.run where id=? returning id",
            Long.class,
            "r_" + UUID.randomUUID().toString().replace("-", ""),
            UUID.randomUUID().toString(),
            run);
    db.update(
        "insert into"
            + " engine.run_authority(run_id,tenant_id,principal_id,grant_ref,grant_expires_at,initial_ceiling,resource_digest,source_kind,origin_authentication_id,app_version_id,app_version_no,root_kind,root_id,initial_ceiling_sha256,accepted_snapshot_sha256,admission_decision_id,authorization_version,operation_id)"
            + " select"
            + " ?,tenant_id,principal_id,grant_ref,grant_expires_at,initial_ceiling,resource_digest,source_kind,origin_authentication_id,app_version_id,app_version_no,root_kind,root_id,initial_ceiling_sha256,accepted_snapshot_sha256,admission_decision_id,authorization_version,operation_id"
            + " from engine.run_authority where run_id=?",
        legacy,
        run);
    String legacyId = "m_" + UUID.randomUUID().toString().replace("-", "");
    db.update(
        "insert into"
            + " engine.model_input_snapshot(material_id,run_id,tenant_id,principal_id,accepted_snapshot_sha256,model_entry_id,binding_id,binding_sha256,request,request_sha256,scope_sha256,manifest,manifest_sha256)"
            + " select"
            + " ?,?,tenant_id,principal_id,accepted_snapshot_sha256,model_entry_id,binding_id,binding_sha256,request,request_sha256,scope_sha256,manifest,manifest_sha256"
            + " from engine.model_input_snapshot where run_id=?",
        legacyId,
        legacy,
        run);
    negatives.put(
        "legacyScopes",
        db.queryForObject(
            "select count(*) from engine.material_scope where run_id=?", Long.class, legacy));
    var app = new SpringApplication(Receiver.class);
    app.setAdditionalProfiles("test");
    app.setDefaultProperties(
        Map.ofEntries(
            Map.entry("spring.config.location", "optional:classpath:/binding-no-config/"),
            Map.entry("server.address", "127.0.0.1"),
            Map.entry("server.port", 0),
            Map.entry("server.ssl.enabled", true),
            Map.entry(
                "server.ssl.key-store",
                Path.of(System.getProperty("consumer.tls")).toUri().toString()),
            Map.entry("server.ssl.key-store-password", "fixture-only"),
            Map.entry("server.ssl.key-store-type", "PKCS12"),
            Map.entry("server.ssl.key-alias", "engine"),
            Map.entry("spring.flyway.enabled", false),
            Map.entry("spring.main.banner-mode", "off"),
            Map.entry("springdoc.api-docs.enabled", false),
            Map.entry("management.endpoints.web.exposure.exclude", "*"),
            Map.entry("management.endpoints.web.discovery.enabled", false),
            Map.entry("logging.level.root", "ERROR")));
    context = (ServletWebServerApplicationContext) app.run();
    int port = context.getWebServer().getPort();
    ownedPorts.add(port);
    sql.clear();
    Files.writeString(
        dir.resolve("ready.json"),
        Json.write(
            Map.ofEntries(
                Map.entry("enginePid", ProcessHandle.current().pid()),
                Map.entry("engineOrigin", "https://localhost:" + port),
                Map.entry("clockInstant", iam.clock().instant().toString()),
                Map.entry(
                    "materialRef",
                    "/internal/material-scopes/model-input/"
                        + m.material().id()
                        + "?version="
                        + m.requestSha256()),
                Map.entry("legacyMaterialId", legacyId),
                Map.entry("requestSha256", m.requestSha256()),
                Map.entry("manifestSha256", m.manifestSha256()),
                Map.entry("initialCeilingSha256", binding.initialSha()),
                Map.entry("historicalOperationId", binding.operationId()),
                Map.entry("sourceManifestSha", System.getProperty("consumer.source.sha")),
                Map.entry("database", database),
                Map.entry("negativeSetup", negatives))));
  }

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableWebSecurity
  @Import(PlatformErrors.class)
  static class Receiver {
    @Bean
    DataSource dataSource() {
      return f.ds;
    }

    @Bean
    ModelInputMaterials materials() {
      return f.materials;
    }

    @Bean
    ModelInputMaterialController controller(ModelInputMaterials m) {
      return new ModelInputMaterialController(m);
    }

    @Bean(destroyMethod = "close")
    JwtDecoder decoder() {
      return new TrustedJwtDecoder(f.iam.config("engine"), f.iam.clock(), true);
    }

    @Bean
    ServiceAuthorization authorization() {
      return new ServiceAuthorization("engine", new ServiceRoutePolicy(), f.engine);
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
      return new RouteCoverageVerifier("engine", new ServiceRoutePolicy(), mappings);
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter> count() {
      var bean =
          new org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>(
              (request, response, chain) -> {
                if (((jakarta.servlet.http.HttpServletRequest) request)
                    .getRequestURI()
                    .startsWith("/internal/material-scopes/")) {
                  f.gets.incrementAndGet();
                  if (request.getInputStream().read() != -1) f.unexpectedBody.incrementAndGet();
                }
                chain.doFilter(request, response);
              });
      bean.setOrder(Integer.MIN_VALUE);
      return bean;
    }
  }

  @Override
  public void close() {
    if (context != null) context.close();
    engine.close();
    gateway.close();
    iam.close();
    try {
      var result = new LinkedHashMap<String, Object>();
      result.put("enginePid", ProcessHandle.current().pid());
      result.put("database", database);
      result.put(
          "identity",
          db.queryForMap(
              "select current_database() as database,current_user as username,inet_server_port() as"
                  + " port"));
      result.put(
          "migrations",
          db.queryForList(
              "select version,script,checksum,success from engine.flyway_schema_history order by"
                  + " installed_rank"));
      result.put("gets", gets.get());
      result.put("unexpectedGetBodies", unexpectedBody.get());
      result.put("authorityCalls", iam.authorityCalls());
      result.put("authorityOperations", iam.observedOperations());
      result.put("ownedPorts", ownedPorts);
      result.put("negativeSetup", negatives);
      result.put(
          "modelAttemptRows", db.queryForObject("select count(*) from engine.attempt", Long.class));
      result.put(
          "modelInvocationRows",
          db.queryForObject("select count(*) from engine.model_invocation", Long.class));
      result.put(
          "sdkAttempts",
          db.queryForObject(
              "select coalesce(sum(sdk_attempts),0) from engine.model_invocation", Long.class));
      result.put(
          "modelInputRows",
          db.queryForObject("select count(*) from engine.model_input_snapshot", Long.class));
      result.put(
          "sqlAfterReady",
          sql.stream().filter(s -> !s.startsWith("select request::text")).toList());
      result.put(
          "sessionsBeforePoolClose",
          db.queryForObject(
              "select count(*) from pg_stat_activity where datname=?", Long.class, database));
      result.put("resourcesClosed", true);
      pool.close();
      result.put("scopedPoolClosed",pool.isClosed());
      Files.writeString(dir.resolve("closed.json"), Json.write(result));
    } catch (Exception e) {
      throw new IllegalStateException("FIXTURE_CLOSURE_EVIDENCE_FAILED", e);
    }
  }

  public static void main(String[] args) throws Exception {
    var dir = Path.of(System.getProperty("consumer.fixture"));
    try (var owned = new FrozenModelBindingProducerFixture(dir)) {
      f = owned;
      owned.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(12);
      while (!Files.exists(dir.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(100);
      if (!Files.exists(dir.resolve("stop"))) throw new AssertionError("FIXTURE_STOP_DEADLINE");
    }
  }
}
