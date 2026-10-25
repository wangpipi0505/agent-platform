package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.application.ModelInputMaterials;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Signed test authority and owned PG database, below the public model-admission gate. */
final class ModelInputMaterialFixture implements AutoCloseable {
  static final ResourceBinding ROOT = RunAuthorityFixture.ROOT;
  static final ServiceCall MAIN = RunAuthorityFixture.MAIN;
  final LoopbackAuthority iam = new LoopbackAuthority();
  final HttpCurrentAuthorityClient gateway, engine, model;
  final RunAuthorityFixture.CountingDataSource ds;
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final RunAuthorityStore runs;
  final ModelInputMaterials materials;
  final String database = "iam_model_input_fix_" + UUID.randomUUID().toString().replace("-", "");
  final List<String> sql = new java.util.concurrent.CopyOnWriteArrayList<>();

  ModelInputMaterialFixture() throws Exception {
    this(false);
  }

  ModelInputMaterialFixture(boolean preflight) throws Exception {
    iam.portalActor(RunAuthorityFixture.actor(Set.of("A", "B")));
    gateway = iam.client("gateway");
    engine = iam.client("engine");
    model = iam.client("model");
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                "agent_v12",
                System.getenv("AGENT_DB_PASSWORD")));
    if (admin.queryForObject(
            "select count(*) from pg_database where datname=?", Integer.class, database)
        != 0) throw new AssertionError("fresh database required");
    // Absence preflight precedes this repair's explicit ownership registry and CREATE.
    var registry = System.getProperty("model.input.registry");
    if (registry == null) throw new AssertionError("owned database registry required");
    Files.writeString(Path.of(registry), Json.write(Map.of("database", database,
        "absentBeforeCreate", true, "jvmPid", ProcessHandle.current().pid())) + "\n",
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    admin.execute("create database " + database);
    ds =
        new RunAuthorityFixture.CountingDataSource(
            new DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:25432/" + database,
                "agent_v12",
                System.getenv("AGENT_DB_PASSWORD")));
    db = new JdbcTemplate(ds);
    ds.beforeSql = sql::add;
    var flyway =
        Flyway.configure()
            .dataSource(ds)
            .schemas("engine")
            .defaultSchema("engine")
            .locations("classpath:db/migration");
    if (preflight) flyway.target("217");
    flyway.load().migrate();
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    runs = new RunAuthorityStore(ds, iam.clock());
    materials = new ModelInputMaterials(ds, engine, iam.clock());
    var evidence = System.getProperty("model.input.evidence");
    if (evidence != null) {
      var dir = Path.of(evidence);
      Files.createDirectories(dir);
      Files.writeString(
          dir.resolve(database + ".json"),
          Json.write(
              Map.of(
                  "database",
                  database,
                  "identity",
                  db.queryForMap(
                      "select current_database() as database,current_user as"
                          + " username,inet_server_port() as port"),
                  "migrations",
                  db.queryForList(
                      "select version,script,checksum,success from engine.flyway_schema_history"
                          + " order by installed_rank"),
                  "jvmPid",
                  ProcessHandle.current().pid())));
    }
  }

  JsonNode snapshot() {
    var s =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            Json.read(
                "{\"appCode\":\"app-a\",\"type\":\"CHAT\",\"versionNo\":1,\"appVersionId\":12,\"candidateSha256\":\""
                    + ROOT.versionDigest()
                    + "\",\"config\":{\"modelEntryId\":7,\"systemPrompt\":\"Hello"
                    + " ${input.name}\",\"requiredLabels\":[\"config\"]}}");
    var entry = Json.read("{\"modelName\":\"fixture-model\",\"contextWindow\":8192}");
    s.set(
        "dependencyVersions",
        Json.tree(
            Map.of(
                "modelRefs",
                List.of(
                    Map.of(
                        "modelEntryId",
                        7,
                        "modelBindingRef",
                        Map.of("bindingId", 13, "sha256", Digest.sha256(Json.canonical(entry))),
                        "entry",
                        entry)))));
    return s;
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
    return create(snapshot(), Json.read("{\"name\":\"Alice\",\"message\":\"sensitive input\"}"));
  }

  long create(JsonNode snapshot, JsonNode inputs) throws Exception {
    return admitted(
        p ->
            tx.execute(
                s ->
                    runs.createPublished(
                        p,
                        snapshot,
                        inputs,
                        Json.read("{\"appVer\":1}"),
                        budget(),
                        "FULL",
                        UUID.randomUUID().toString(),
                        Set.of("parent"))));
  }

  <T> T admitted(Function<RetainedAuthorization, T> work) throws Exception {
    var ticket = gateway.exchange(iam.portal(), MAIN);
    try (var decoder = new TrustedJwtDecoder(iam.config("engine"), iam.clock(), true)) {
      var request = new MockHttpServletRequest("POST", "/admin/apps/app-a/runs");
      var response = new MockHttpServletResponse();
      request.setAttribute(
          HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/apps/{code}/runs");
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
      SecurityContextHolder.getContext()
          .setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()), List.of()));
      var handler =
          new HandlerMethod(
              new RunController(null, null, null, null),
              RunController.class.getMethod("run", String.class, JsonNode.class));
      var guard = new ServiceAuthorization("engine", new ServiceRoutePolicy(), engine);
      try {
        guard.preHandle(request, response, handler);
        return work.apply(AuthorityContext.requireInitialFor(Actor.current(), ROOT));
      } finally {
        guard.afterCompletion(request, response, handler, null);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
      }
    }
  }

  long count(String table) {
    return db.queryForObject("select count(*) from engine." + table, Long.class);
  }

  @Override
  public void close() {
    model.close();
    engine.close();
    gateway.close();
    iam.close();
    var evidence = System.getProperty("model.input.evidence");
    if (evidence != null)
      try {
        var result = new LinkedHashMap<String, Object>();
        result.put("database", database);
        result.put("jvmPid", ProcessHandle.current().pid());
        result.put("authorityPort", java.net.URI.create(iam.issuer()).getPort());
        result.put("policyPort", java.net.URI.create(iam.policyIssuer()).getPort());
        result.put("authorityCalls", iam.authorityCalls());
        result.put("resourcesClosed", true);
        result.put("runs", count("run"));
        result.put(
            "hasModelInputTable",
            db.queryForObject(
                "select to_regclass('engine.model_input_snapshot') is not null", Boolean.class));
        if (Boolean.TRUE.equals(result.get("hasModelInputTable"))) {
          result.put("requests", count("model_input_snapshot"));
          result.put(
              "modelInputScopes",
              db.queryForObject(
                  "select count(*) from engine.material_scope where kind='model-input'",
                  Long.class));
        }
        Files.writeString(Path.of(evidence, database + "-close.json"), Json.write(result));
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
  }

  /** Digest-only independent JVM; no fixture construction, DB, IAM or retained credentials. */
  public static void main(String[] args) throws Exception {
    var exact =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var value = exact.readTree(Files.readString(Path.of(args[0])));
    var manifest =
        Json.read(
            value.path("manifest").toString(),
            cn.agentplatform.engine.domain.ModelInputManifest.class);
    var labels = value.path("request").path("requiredLabels");
    var sorted = new TreeSet<String>();
    for (var label : labels) sorted.add(label.textValue());
    if (!Json.canonical(labels).equals(Json.canonical(sorted)))
      throw new AssertionError("REQUEST_LABEL_ORDER_CHANGED");
    System.out.println(
        Json.write(
            Map.of(
                "requestSha",
                Digest.sha256(Json.canonical(value.path("request"))),
                "manifestSha",
                Digest.sha256(Json.canonical(manifest.unsigned())),
                "manifestWireSha",
                Digest.sha256(Json.canonical(manifest)),
                "pid",
                ProcessHandle.current().pid())));
  }
}
