package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ModelInputMaterialPostgresTest {
  @Test
  void malformedParameterShapesNeverConsumeImmutableSlot() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      var invalid = List.of(
          Map.entry("temperature", Json.tree("bad")), Map.entry("topP", Json.object()),
          Map.entry("stop", Json.object()), Map.entry("stop", Json.tree(List.of("ok", 3))),
          Map.entry("topK", Json.tree(1.5)), Map.entry("seed", Json.tree("12")),
          Map.entry("frequencyPenalty", Json.tree(List.of(0.1))),
          Map.entry("presencePenalty", Json.tree("0.1")),
          Map.entry("maxOutputTokens", Json.tree(1.5)),
          Map.entry("temperature", com.fasterxml.jackson.databind.node.NullNode.instance));
      for (var bad : invalid) {
        var snapshot = (ObjectNode) f.snapshot();
        ((ObjectNode) snapshot.path("config")).set("params", Json.tree(Map.of(bad.getKey(), bad.getValue())));
        long run = f.create(snapshot, Json.tree(Map.of("name", "Alice")));
        assertThatThrownBy(() -> f.materials.prepareInitial(run))
            .as("parameter %s: %s", bad.getKey(), bad.getValue().getNodeType())
            .isInstanceOf(PlatformException.class);
        assertThat(f.count("model_input_snapshot")).isZero();
        assertThat(f.db.queryForObject("select count(*) from engine.material_scope where kind='model-input'", Long.class)).isZero();
      }
    }
  }

  @Test
  void validTypedParametersSurvivePersistedCanonicalRequest() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      for (var stop : List.of(Json.tree("END"), Json.tree(List.of("END", "停止")))) {
        var expected = Json.tree(Map.of("temperature", 0.7, "topP", 0.9, "topK", 40,
            "seed", -12, "frequencyPenalty", -0.2, "presencePenalty", 0.4,
            "stop", stop, "maxOutputTokens", 1024));
        var snapshot = (ObjectNode) f.snapshot();
        var config = (ObjectNode) snapshot.path("config");
        config.set("params", expected);
        config.put("maxOutputTokens", 1024);
        config.set("sampling", Json.tree(Map.of("temperature", 0.7, "topP", 0.9)));
        config.set("output", Json.tree(Map.of("maxTokens", 1024)));
        long run = f.create(snapshot, Json.tree(Map.of("name", "Alice")));
        var manifest = f.materials.prepareInitial(run);
        var request = Json.read(f.db.queryForObject("select request::text from engine.model_input_snapshot where run_id=?", String.class, run));
        expected.fields().forEachRemaining(e -> assertThat(Json.canonical(request.path("params").path(e.getKey())))
            .as(e.getKey()).isEqualTo(Json.canonical(e.getValue())));
        assertThat(request.path("params").path("sampling")).isEqualTo(config.path("sampling"));
        assertThat(request.path("params").path("output")).isEqualTo(config.path("output"));
        assertThat(Digest.sha256(Json.canonical(request))).isEqualTo(manifest.requestSha256());
      }
    }
  }

  @Test
  void oversizedExpansionRejectsBeforeLargeAllocationWithMeasuredControl() throws Exception {
    var meter = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    assertThat(meter.isThreadAllocatedMemorySupported()).isTrue();
    meter.setThreadAllocatedMemoryEnabled(true);
    long thread = Thread.currentThread().threadId();
    long start = meter.getThreadAllocatedBytes(thread);
    var control = new byte[12 * 1024 * 1024];
    assertThat(System.identityHashCode(control)).isNotZero();
    long positiveBytes = meter.getThreadAllocatedBytes(thread) - start;
    assertThat(positiveBytes).isGreaterThanOrEqualTo(control.length);
    try (var f = new ModelInputMaterialFixture()) {
      var authority = new RunAuthority(f.runs, f.engine, f.iam.clock(), Duration.ofSeconds(1));
      var store = new ModelInputMaterialStore(f.ds, f.iam.clock());
      long warm = f.create();
      var warmProof = authority.currentRetained(warm);
      f.tx.execute(st -> ModelInputMaterials.assemble(store.lockInitial(warm, warmProof), warmProof));
      var snapshot = (ObjectNode) f.snapshot();
      ((ObjectNode) snapshot.path("config")).put("systemPrompt", "prefix " + "${input.name}".repeat(128));
      var inputs = Json.tree(Map.of("name", "x".repeat(65536), "message", "small"));
      assertThat(Json.canonical(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(1048576);
      assertThat(Json.canonical(inputs).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(1048576);
      long run = f.create(snapshot, inputs);
      var proof = authority.currentRetained(run);
      long[] allocated = {0};
      assertThatThrownBy(() -> f.tx.execute(st -> {
        var source = store.lockInitial(run, proof);
        long before = meter.getThreadAllocatedBytes(thread);
        try {
          var request = ModelInputMaterials.assemble(source, proof);
          return store.insertInitial(source, request, proof);
        } finally {
          allocated[0] = meter.getThreadAllocatedBytes(thread) - before;
        }
      })).isInstanceOf(PlatformException.class);
      java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("model.input.evidence"), "expansion-allocation.json"),
          Json.write(Map.of("counter", "HotSpot per-thread allocated bytes", "positiveBytes", positiveBytes,
              "assemblyBytes", allocated[0], "allocationCeiling", 8 * 1024 * 1024,
              "hypotheticalExpandedAsciiBytes", 128 * 65536, "jvmPid", ProcessHandle.current().pid())));
      assertThat(f.count("model_input_snapshot")).isZero();
      assertThat(f.db.queryForObject("select count(*) from engine.material_scope where kind='model-input'", Long.class)).isZero();
      assertThat(allocated[0]).isLessThan(8 * 1024 * 1024);
    }
  }

  @Test
  void templateSemanticsAndNearLimitUnicodeRemainSupported() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      var cases = List.of(
          Map.entry("a ${input.name} ${input.name}", "a 界🙂 界🙂"),
          Map.entry("a ${input.missing}", "a null"),
          Map.entry("a ${input.number} ${input.nested.value}", "a 12 nested"),
          Map.entry("${input.nested}", ""),
          Map.entry("a ${input.cascade} ${input.name}", "a 界🙂 界🙂"));
      for (var c : cases) {
        var snapshot = (ObjectNode) f.snapshot();
        ((ObjectNode) snapshot.path("config")).put("systemPrompt", c.getKey());
        var inputs = Json.tree(Map.of("name", "界🙂", "number", 12, "nested", Map.of("value", "nested"),
            "cascade", "${input.name}", "message", "small"));
        long run = f.create(snapshot, inputs);
        f.materials.prepareInitial(run);
        var request = Json.read(f.db.queryForObject("select request::text from engine.model_input_snapshot where run_id=?", String.class, run));
        assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo(c.getValue());
      }
      var snapshot = (ObjectNode) f.snapshot();
      ((ObjectNode) snapshot.path("config")).put("systemPrompt", "P ${input.name}${input.name}");
      String value = "界🙂\"\n\\".repeat(79000);
      long run = f.create(snapshot, Json.tree(Map.of("name", value, "message", "small")));
      f.materials.prepareInitial(run);
      var request = Json.read(f.db.queryForObject("select request::text from engine.model_input_snapshot where run_id=?", String.class, run));
      assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo("P " + value + value);
      int bytes = Json.canonical(request).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      assertThat(bytes).isBetween(2000000, ModelInputMaterialStore.REQUEST_BYTES);
    }
  }

  @Test
  void latestSuccessfulDecisionRejectsOlderWriterBeforePayload() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      var authority = new RunAuthority(f.runs, f.engine, f.iam.clock(), Duration.ofSeconds(1));
      var p1 = authority.currentRetained(run);
      long firstVersion = f.runs.load(run).checkVersion();
      f.iam.decisionMutation(c -> {
        if (c.containsKey("attributes"))
          c.put("attributes", Map.of("region", Set.of("A", "B"),
              "dataLabels", Set.of("initial", "new-policy")));
      });
      var p2 = authority.currentRetained(run);
      assertThat(f.runs.load(run).checkVersion()).isGreaterThan(firstVersion);
      assertThat(p2.current().decisionId()).isNotEqualTo(p1.current().decisionId());
      assertThat(f.db.queryForObject("select last_decision_id from engine.run_authority where run_id=?",
          String.class, run)).isEqualTo(p2.current().decisionId());
      var store = new ModelInputMaterialStore(f.ds, f.iam.clock());
      f.sql.clear();
      assertThatThrownBy(() -> f.tx.execute(st -> store.lockInitial(run, p1)))
          .isInstanceOf(PlatformException.class);
      assertThat(f.sql).noneMatch(q -> q.startsWith("select case when octet_length(snapshot"));
      assertThat(f.count("model_input_snapshot")).isZero();
      assertThat(f.db.queryForObject("select count(*) from engine.material_scope where kind='model-input'",
          Long.class)).isZero();
      var manifest = f.tx.execute(st -> {
        var source = store.lockInitial(run, p2);
        return store.insertInitial(source, ModelInputMaterials.assemble(source, p2), p2);
      });
      assertThat(manifest.scope().sourceDecisionId()).isEqualTo(p2.current().decisionId());
      assertThat(manifest.scope().requiredDataLabels()).contains("initial", "new-policy", "parent", "config");
      assertThat(f.count("model_input_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void preflight217AndSourceContinuity() throws Exception {
    try (var f = new ModelInputMaterialFixture(true)) {
      assertThat(
              f.db.queryForList(
                  "select version from engine.flyway_schema_history where success and version is"
                      + " not null order by installed_rank",
                  String.class))
          .containsExactly(
              "200", "201", "202", "203", "204", "205", "206", "207", "208", "209", "210", "211",
              "212", "213", "214", "215", "216", "217");
    }
  }

  @Test
  void persistsRequestAndScopeAtomicallyWithFullParentUnion() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      var m = f.materials.prepareInitial(run);
      assertThat(m).isNotNull();
      assertThat(f.count("model_input_snapshot")).isEqualTo(1);
      assertThat(m.parents()).hasSize(3);
      assertThat(m.scope().requiredDataLabels()).contains("initial", "config", "parent");
    }
  }

  @Test
  void missingRootScopeMustDeny() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");
      f.db.update("delete from engine.material_scope where run_id=? and kind='run-inputs'", run);
      assertThatThrownBy(() -> f.materials.prepareInitial(run))
          .isInstanceOf(PlatformException.class);
    }
  }

  @Test
  void replayRecomputesFullRequestAndNeverCreatesDispatchFacts() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      var first = f.materials.prepareInitial(run);
      var again = f.materials.prepareInitial(run);
      assertThat(again).isEqualTo(first);
      var request =
          Json.read(
              f.db.queryForObject(
                  "select request::text from engine.model_input_snapshot where run_id=?",
                  String.class,
                  run));
      assertThat(Digest.sha256(Json.canonical(request))).isEqualTo(first.requestSha256());
      assertThat(request.path("invocationRef").asText())
          .isEqualTo("material:" + first.material().id() + ":MAIN");
      assertThat(request.path("callKind").asText()).isEqualTo("FORMAL");
      assertThat(request.path("toolDefinitions")).isEmpty();
      assertThat(request.path("messages")).hasSize(2);
      assertThat(first.scope().parentScopeSha256())
          .containsExactlyElementsOf(
              first.parents().stream().map(MaterialScopeV1::scopeSha256).sorted().toList());
      first
          .parents()
          .forEach(
              s -> assertThat(MaterialAuthorization.scopeDigest(s)).isEqualTo(s.scopeSha256()));
      assertThat(f.count("model_invocation")).isZero();
      assertThat(f.count("chat_turn")).isZero();
      assertThat(
              f.db.queryForObject(
                  "select budget->'consumed'->>'modelCalls' from engine.run where id=?",
                  String.class,
                  run))
          .isEqualTo("0");
      for (String table : List.of("engine.model_input_snapshot", "engine.material_scope")) {
        assertThatThrownBy(() -> f.db.update("delete from " + table + " where run_id=?", run))
            .hasMessageContaining("IMMUTABLE");
        assertThatThrownBy(
                () ->
                    f.db.update("update " + table + " set tenant_id=tenant_id where run_id=?", run))
            .hasMessageContaining("IMMUTABLE");
      }
    }
  }

  @Test
  void narrowedCurrentDeniesBeforeAnySourceAndCannotRemoveOldObligations() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      f.iam.decisionMutation(
          c -> {
            if (c.containsKey("attributes"))
              c.put("attributes", Map.of("region", Set.of("A"), "dataLabels", Set.of("initial")));
          });
      f.sql.clear();
      assertThatThrownBy(() -> f.materials.prepareInitial(run))
          .isInstanceOf(PlatformException.class);
      assertThat(f.count("model_input_snapshot")).isZero();
      assertThat(f.sql).noneMatch(s -> s.contains("select case when octet_length(snapshot"));
    }
  }

  @Test
  void rejectsUnsupportedAndMismatchedFrozenConfiguration() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      var variants = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      var pure = (ObjectNode) f.snapshot();
      pure.put("type", "FLOW");
      variants.add(pure);
      for (String key : List.of("tools", "history", "frozenPrompts", "modelOverride")) {
        var s = (ObjectNode) f.snapshot();
        ((ObjectNode) s.path("config")).set(key, Json.tree(List.of()));
        variants.add(s);
      }
      var mismatch = (ObjectNode) f.snapshot();
      ((ObjectNode) mismatch.path("config"))
          .set("modelBinding", Json.tree(Map.of("modelEntryId", 8)));
      variants.add(mismatch);
      var dup = (ObjectNode) f.snapshot();
      ((ArrayNode) dup.path("dependencyVersions").path("modelRefs"))
          .add(dup.path("dependencyVersions").path("modelRefs").get(0).deepCopy());
      variants.add(dup);
      var drift = (ObjectNode) f.snapshot();
      ((ObjectNode) drift.path("dependencyVersions").path("modelRefs").get(0).path("entry"))
          .put("modelName", "changed");
      variants.add(drift);
      var extra = (ObjectNode) f.snapshot();
      ((ObjectNode)
              extra.path("dependencyVersions").path("modelRefs").get(0).path("modelBindingRef"))
          .put("extra", "unsupported");
      variants.add(extra);
      var stringId = (ObjectNode) f.snapshot();
      ((ObjectNode) stringId.path("dependencyVersions").path("modelRefs").get(0))
          .put("modelEntryId", "7");
      variants.add(stringId);
      for (var snapshot : variants) {
        long run = f.create(snapshot, Json.read("{\"name\":\"Alice\"}"));
        assertThatThrownBy(() -> f.materials.prepareInitial(run))
            .isInstanceOf(PlatformException.class)
            .satisfies(
                e ->
                    assertThat(((PlatformException) e).code())
                        .isEqualTo("MATERIAL_SCOPE_MISSING_OR_CHANGED"));
      }
      assertThat(f.count("model_input_snapshot")).isZero();
    }
  }

  @Test
  void oversizedSignedDecisionIdentifierRejectsThisBoundedProfile() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      f.iam.decisionMutation(
          c -> {
            if (c.containsKey("roles")) c.put("jti", "x".repeat(257));
          });
      long run = f.create();
      assertThatThrownBy(() -> f.materials.prepareInitial(run))
          .isInstanceOf(PlatformException.class);
      assertThat(f.count("model_input_snapshot")).isZero();
    }
  }

  @Test
  void caughtScopeInsertFailureStillRollsBackRequest() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      var proof =
          new RunAuthority(f.runs, f.engine, f.iam.clock(), Duration.ofSeconds(1))
              .currentRetained(run);
      var store = new ModelInputMaterialStore(f.ds, f.iam.clock());
      f.ds.beforeSql =
          s -> {
            f.sql.add(s);
            if (s.startsWith("insert into engine.material_scope"))
              throw new PlatformException(403, "INJECTED_SCOPE_FAILURE", "scope insert fault");
          };
      assertThatThrownBy(
              () ->
                  f.tx.execute(
                      status -> {
                        var source = store.lockInitial(run, proof);
                        try {
                          store.insertInitial(
                              source, ModelInputMaterials.assemble(source, proof), proof);
                        } catch (PlatformException e) {
                          assertThat(e.code()).isEqualTo("INJECTED_SCOPE_FAILURE");
                        }
                        return null;
                      }))
          .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
      f.ds.beforeSql = f.sql::add;
      assertThat(f.count("model_input_snapshot")).isZero();
      assertThat(
              f.db.queryForObject(
                  "select count(*) from engine.material_scope where run_id=? and"
                      + " kind='model-input'",
                  Long.class,
                  run))
          .isZero();
    }
  }

  @Test
  void publicCurrentOnlyAndFabricatedSourceCannotWriteAndCatchCannotCommit() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      var proof =
          new RunAuthority(f.runs, f.engine, f.iam.clock(), Duration.ofSeconds(1))
              .currentRetained(run);
      var store = new ModelInputMaterialStore(f.ds, f.iam.clock());
      assertThatThrownBy(
              () ->
                  f.tx.execute(
                      status -> {
                        assertThatThrownBy(
                                () ->
                                    store.lockInitial(
                                        run,
                                        HttpCurrentAuthorityClient.RetainedAuthorization
                                            .currentOnly(proof.current())))
                            .isInstanceOf(PlatformException.class);
                        return null;
                      }))
          .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
      assertThatThrownBy(
              () ->
                  f.tx.execute(
                      status -> {
                        var s = store.lockInitial(run, proof);
                        var req = (ObjectNode) ModelInputMaterials.assemble(s, proof);
                        req.put("modelEntryId", 8);
                        assertThatThrownBy(() -> store.insertInitial(s, req, proof))
                            .isInstanceOf(PlatformException.class);
                        return null;
                      }))
          .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
      assertThat(f.count("model_input_snapshot")).isZero();
    }
  }

  @Test
  void realLockWaitExpiryRejectsAndPrecommitExpiryRollsBack() throws Exception {
    try (var f = new ModelInputMaterialFixture();
        var workers = Executors.newSingleThreadExecutor()) {
      long run = f.create();
      var proof =
          new RunAuthority(f.runs, f.engine, f.iam.clock(), Duration.ofSeconds(1))
              .currentRetained(run);
      var store = new ModelInputMaterialStore(f.ds, f.iam.clock());
      var arrived = new CountDownLatch(1);
      try (var connection = f.ds.getConnection()) {
        connection.setAutoCommit(false);
        try (var q =
            connection.prepareStatement("select id from engine.run where id=? for update")) {
          q.setLong(1, run);
          q.executeQuery();
        }
        arrived = new CountDownLatch(1);
        var waitForLock = arrived;
        f.ds.beforeRunLock = pid -> waitForLock.countDown();
        var future =
            workers.submit(
                () ->
                    assertThatThrownBy(() -> f.tx.execute(status -> store.lockInitial(run, proof)))
                        .isInstanceOf(PlatformException.class));
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
        f.iam.advanceClock(Duration.ofSeconds(10));
        connection.commit();
        future.get(5, TimeUnit.SECONDS);
      }
      assertThat(f.count("model_input_snapshot")).isZero();
    }
  }

  @Test
  void finalLocalCheckRollsBackAndPostcommitCheckReportsNonAtomicBoundary() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      f.ds.beforeSql =
          s -> {
            f.sql.add(s);
            if (s.startsWith("insert into engine.material_scope"))
              f.iam.advanceClock(Duration.ofSeconds(10));
          };
      assertThatThrownBy(() -> f.materials.prepareInitial(run))
          .isInstanceOf(PlatformException.class);
      f.ds.beforeSql = f.sql::add;
      assertThat(f.count("model_input_snapshot")).isZero();
      long second = f.create();
      f.ds.afterMaterialCommit = () -> f.iam.advanceClock(Duration.ofSeconds(10));
      assertThatThrownBy(() -> f.materials.prepareInitial(second))
          .isInstanceOf(PlatformException.class);
      assertThat(f.count("model_input_snapshot")).isEqualTo(1);
    }
  }

  @Test
  void boundsRejectBeforeSourceMaterializationAndDepthRejects() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run =
          f.create(f.snapshot(), Json.tree(Map.of("name", "A", "message", "x".repeat(1048577))));
      f.sql.clear();
      assertThatThrownBy(() -> f.materials.prepareInitial(run))
          .isInstanceOf(PlatformException.class);
      assertThat(f.sql).noneMatch(s -> s.startsWith("select case when octet_length(snapshot"));
      var deep = Json.object();
      var cur = deep;
      for (int i = 0; i < 34; i++) {
        var next = Json.object();
        cur.set("x", next);
        cur = next;
      }
      long another = f.create(f.snapshot(), deep);
      assertThatThrownBy(() -> f.materials.prepareInitial(another))
          .isInstanceOf(PlatformException.class);
      assertThat(f.count("model_input_snapshot")).isZero();
    }
  }

  @Test
  void metadataSerializerStabilizesAllSetOrders() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      var m = f.materials.prepareInitial(f.create());
      var original = Json.canonical(m);
      var reread =
          Json.read(Json.write(m), cn.agentplatform.engine.domain.ModelInputManifest.class);
      assertThat(Json.canonical(reread)).isEqualTo(original);
      var unsigned = (ObjectNode) Json.tree(reread);
      unsigned.remove("manifestSha256");
      assertThat(Digest.sha256(Json.canonical(unsigned))).isEqualTo(m.manifestSha256());
      var out = java.nio.file.Path.of(System.getProperty("model.input.evidence"));
      var input = out.resolve("digest-input.json");
      var request =
          Json.read(
              f.db.queryForObject(
                  "select request::text from engine.model_input_snapshot where material_id=?",
                  String.class,
                  m.material().id()));
      java.nio.file.Files.writeString(input, Json.write(Map.of("request", request, "manifest", m)));
      var witnesses = new ArrayList<Object>();
      for (int i = 0; i < 2; i++) {
        var process =
            new ProcessBuilder(
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin/java.exe")
                        .toString(),
                    "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp",
                    "-cp",
                    System.getProperty("java.class.path"),
                    ModelInputMaterialFixture.class.getName(),
                    input.toString())
                .directory(java.nio.file.Path.of("G:/207/项目/算法重组工具/代码/agent-platform").toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String result =
            new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(result).isZero();
        var witness = Json.read(result);
        assertThat(witness.path("requestSha").asText()).isEqualTo(m.requestSha256());
        assertThat(witness.path("manifestSha").asText()).isEqualTo(m.manifestSha256());
        assertThat(witness.path("manifestWireSha").asText()).isEqualTo(Digest.sha256(original));
        witnesses.add(witness);
      }
      java.nio.file.Files.writeString(out.resolve("cross-jvm-digests.json"), Json.write(witnesses));
    }
  }

  @Test
  void modelTokenizerProviderAndCredentialCanariesObserveZeroComponentCalls() throws Exception {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var canary =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    canary.createContext(
        "/",
        x -> {
          calls.incrementAndGet();
          byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          x.sendResponseHeaders(200, body.length);
          x.getResponseBody().write(body);
          x.close();
        });
    canary.start();
    try (var client = java.net.http.HttpClient.newHttpClient();
        var f = new ModelInputMaterialFixture()) {
      String origin = "http://127.0.0.1:" + canary.getAddress().getPort();
      for (String path :
          List.of("/internal/model/measure", "/internal/model/chat/stream", "/v1/chat/completions"))
        client.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + path)).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.discarding());
      assertThat(calls.get()).isEqualTo(3);
      calls.set(0);
      var actor = RunAuthorityFixture.actor(Set.of("A", "B"));
      String ref = SecretVault.put("fixture-canary-value", actor);
      var out = java.nio.file.Path.of(System.getProperty("model.input.evidence"));
      java.nio.file.Path positive = out.resolve("secret-control.jfr"),
          actual = out.resolve("component-zero-egress.jfr");
      try (var recording = new jdk.jfr.Recording()) {
        recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO).withStackTrace();
        recording.start();
        assertThat(SecretVault.resolve(ref, actor)).isEqualTo("fixture-canary-value");
        recording.stop();
        recording.dump(positive);
      }
      assertThat(credentialReads(positive)).isPositive();
      var snapshot = (ObjectNode) f.snapshot();
      var item = (ObjectNode) snapshot.path("dependencyVersions").path("modelRefs").get(0);
      var entry = (ObjectNode) item.path("entry");
      entry.put("baseUrl", origin);
      entry.put("secretRef", ref);
      ((ObjectNode) item.path("modelBindingRef"))
          .put("sha256", Digest.sha256(Json.canonical(entry)));
      long run = f.create(snapshot, Json.read("{\"name\":\"Alice\"}"));
      try (var existing = new RuntimeClients()) {
        existing.model = origin;
        existing.app = origin;
        existing.skill = origin;
        try (var recording = new jdk.jfr.Recording()) {
          recording.enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO).withStackTrace();
          recording.start();
          assertThat(f.materials.prepareInitial(run)).isNotNull();
          recording.stop();
          recording.dump(actual);
        }
      }
      assertThat(credentialReads(actual)).isZero();
      assertThat(calls.get()).isZero();
      java.nio.file.Files.writeString(
          out.resolve("egress.json"),
          Json.write(
              Map.of(
                  "canaryPort",
                  canary.getAddress().getPort(),
                  "positiveEndpointControls",
                  3,
                  "componentModelTokenizerProviderCalls",
                  calls.get(),
                  "positiveCredentialReadEvents",
                  credentialReads(positive),
                  "componentCredentialReadEvents",
                  credentialReads(actual),
                  "jvmPid",
                  ProcessHandle.current().pid())));
    } finally {
      canary.stop(0);
    }
  }

  static long credentialReads(java.nio.file.Path recording) throws Exception {
    return jdk.jfr.consumer.RecordingFile.readAllEvents(recording).stream()
        .filter(
            e ->
                e.getEventType().getName().equals("jdk.FileRead")
                    && e.getStackTrace() != null
                    && e.getStackTrace().getFrames().stream()
                        .anyMatch(
                            s ->
                                s.getMethod()
                                        .getType()
                                        .getName()
                                        .equals(SecretVault.class.getName())
                                    && s.getMethod().getName().equals("resolve")))
        .count();
  }

  @Test
  void legacyInvocationAndChatTurnAssociationAreRejected() throws Exception {
    try (var f = new ModelInputMaterialFixture()) {
      long run = f.create();
      long node =
          f.db.queryForObject(
              "insert into engine.node_instance(run_id,step_seq,node_type,status,definition)"
                  + " values(?,1,'TASK','READY','{}') returning id",
              Long.class,
              run);
      f.db.update(
          "insert into"
              + " engine.model_invocation(run_id,node_instance_id,round_no,kind,source_request)"
              + " values(?,?,1,'MAIN','{}')",
          run,
          node);
      assertThatThrownBy(() -> f.materials.prepareInitial(run))
          .isInstanceOf(PlatformException.class);
      long other = f.create();
      String publicId = f.runs.load(other).publicId();
      f.db.update(
          "insert into engine.chat_session(public_id,tenant_id,principal_id,app_code,snapshot)"
              + " values('session-fixture','tenant-a','alice','app-a','{}')");
      f.db.update(
          "insert into engine.chat_turn(session_id,turn_no,request_id,inputs,run_public_id)"
              + " values('session-fixture',1,'req-fixture','{}',?)",
          publicId);
      assertThatThrownBy(() -> f.materials.prepareInitial(other))
          .isInstanceOf(PlatformException.class);
      assertThat(f.count("model_input_snapshot")).isZero();
    }
  }

  @Test
  void concurrentPreparationsWithOneRetainedProofPersistOneReference() throws Exception {
    try (var f = new ModelInputMaterialFixture();
        var pool = Executors.newFixedThreadPool(2)) {
      long run = f.create();
      var proof =
          new RunAuthority(f.runs, f.engine, f.iam.clock(), Duration.ofSeconds(1))
              .currentRetained(run);
      var start = new CountDownLatch(1);
      var store = new ModelInputMaterialStore(f.ds, f.iam.clock());
      var task =
          (java.util.concurrent.Callable<String>)
              () -> {
                start.await();
                return f.tx.execute(
                    status -> {
                      var s = store.lockInitial(run, proof);
                      return store
                          .insertInitial(s, ModelInputMaterials.assemble(s, proof), proof)
                          .material()
                          .id();
                    });
              };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(b.get(5, TimeUnit.SECONDS));
      assertThat(f.count("model_input_snapshot")).isEqualTo(1);
    }
  }
}
