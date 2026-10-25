package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.application.ModelInputMaterials;
import cn.agentplatform.engine.domain.ModelInputManifest;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;

/** One initial profile, owned SQL and immutable facts; no egress or generic material framework. */
public final class ModelInputMaterialStore {
  public static final int SOURCE_BYTES = 1048576, REQUEST_BYTES = 2097152, MANIFEST_BYTES = 262144;
  private static final ObjectMapper EXACT =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .build())
                  .build())
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final DataSource source;
  private final JdbcTemplate db;
  private final Clock clock;

  public ModelInputMaterialStore(DataSource source, Clock clock) {
    this.source = Objects.requireNonNull(source);
    this.db = new JdbcTemplate(source);
    this.clock = Objects.requireNonNull(clock);
  }

  /** Scalar-only guard before the existing continuation loader materializes control JSON. */
  public void preflightInitialMetadata(long runId) {
    if (runId < 1) throw missing();
    var authority =
        db.queryForList(
            "select octet_length(row_to_json(a)::text) as bytes from engine.run_authority a where"
                + " run_id=?",
            runId);
    if (authority.size() != 1) throw missing();
    limit(num(authority.getFirst(), "bytes"), MANIFEST_BYTES);
    var scopes =
        db.queryForList(
            "select octet_length(scope::text) as bytes from engine.material_scope where run_id=?"
                + " and kind in ('run-snapshot','run-inputs','run-version-refs')",
            runId);
    if (scopes.size() != 3) throw missing();
    for (var s : scopes) limit(num(s, "bytes"), MANIFEST_BYTES);
  }

  /** Data only. insertInitial reloads everything rather than trusting this public record. */
  public record InitialSource(
      RunAuthorityStore.Binding binding,
      AuthorizedMaterial snapshot,
      AuthorizedMaterial inputs,
      AuthorizedMaterial versionRefs,
      String materialId) {}

  public InitialSource lockInitial(long runId, RetainedAuthorization proof) {
    return write(() -> lock(runId, proof));
  }

  private InitialSource lock(long runId, RetainedAuthorization proof) {
    local(proof);
    if (runId < 1) throw missing();
    if (db.queryForList("select id from engine.run where id=? for update", Long.class, runId).size()
        != 1) throw missing();
    if (db.queryForList(
                "select run_id from engine.run_authority where run_id=? for update",
                Long.class,
                runId)
            .size()
        != 1) throw missing();
    var b = binding(runId);
    RunAuthorityStore.strict(b, proof);
    localBinding(b, proof);
    var control =
        db.queryForMap(
            "select trigger_type,mode,budget->>'deadline' as deadline,(debug_snapshot is not null"
                + " or eval_candidate is not null or controlled_draft_skill_version_id is not null"
                + " or controlled_draft_skill_digest is not null or"
                + " controlled_skill_test_version_id is not null or controlled_skill_test_digest is"
                + " not null) as unsupported,exists(select 1 from engine.model_invocation where"
                + " run_id=r.id) as invoked,exists(select 1 from engine.chat_turn where"
                + " run_public_id=r.public_id) as history,exists(select 1 from engine.tool_call"
                + " where run_id=r.id) as tools from engine.run r where id=?",
            runId);
    eq("TASK", control.get("trigger_type"));
    eq("FULL", control.get("mode"));
    for (String k : List.of("unsupported", "invoked", "history", "tools"))
      eq(false, control.get(k));
    try {
      if (!Instant.parse((String) control.get("deadline")).isAfter(clock.instant()))
        throw missing();
    } catch (DateTimeException | NullPointerException e) {
      throw missing();
    }
    long total = 0;
    var sizes =
        db.queryForMap(
            "select octet_length(snapshot::text) as snapshot_bytes,octet_length(inputs::text) as"
                + " inputs_bytes,octet_length(version_refs::text) as refs_bytes from engine.run"
                + " where id=?",
            runId);
    for (String k : List.of("snapshot_bytes", "inputs_bytes", "refs_bytes")) {
      long n = num(sizes, k);
      limit(n, SOURCE_BYTES);
      total += n;
    }
    limit(total, 3L * SOURCE_BYTES);
    var values = new ArrayList<AuthorizedMaterial>();
    for (String kind : List.of("run-snapshot", "run-inputs", "run-version-refs")) {
      var scope =
          b.scopes().stream()
              .filter(s -> kind.equals(s.material().kind()))
              .findFirst()
              .orElseThrow(ModelInputMaterialStore::missing);
      MaterialAuthorization.require(scope, proof.current(), clock);
      String col =
          switch (kind) {
            case "run-snapshot" -> "snapshot";
            case "run-inputs" -> "inputs";
            default -> "version_refs";
          };
      var value =
          parse(
              db.queryForObject(
                  "select case when octet_length("
                      + col
                      + "::text)<=1048576 then "
                      + col
                      + "::text end from engine.run where id=?",
                  String.class,
                  runId),
              SOURCE_BYTES);
      if (!value.isObject()) throw missing();
      eq(scope.material().payloadSha256(), MaterialScopeStore.observedDigest(value));
      values.add(new AuthorizedMaterial(value, scope));
    }
    var snapshot = (JsonNode) values.get(0).payload();
    var refs = (JsonNode) values.get(2).payload();
    eq(b.root().id(), text(snapshot, "appCode") + "@" + positive(snapshot.path("versionNo")));
    eq(b.root().versionDigest(), text(snapshot, "candidateSha256"));
    eq(
        b.appVersionId(),
        positive(
            snapshot.has("appVersionId")
                ? snapshot.path("appVersionId")
                : snapshot.path("versionId")));
    if (snapshot.has("versionId")) eq(b.appVersionId(), positive(snapshot.path("versionId")));
    eq(b.appVersionNo(), positive(refs.path("appVer")));
    var ids =
        db.queryForList(
            "select material_id from engine.model_input_snapshot where run_id=?",
            String.class,
            runId);
    if (ids.size() > 1) throw missing();
    String id =
        ids.isEmpty() ? "m_" + UUID.randomUUID().toString().replace("-", "") : ids.getFirst();
    selector(id, "m_");
    localBinding(b, proof);
    return new InitialSource(b, values.get(0), values.get(1), values.get(2), id);
  }

  public ModelInputManifest insertInitial(
      InitialSource supplied, JsonNode request, RetainedAuthorization proof) {
    return write(
        () -> {
          if (supplied == null || supplied.binding() == null) throw missing();
          var actual = lock(supplied.binding().runId(), proof);
          // No fabricated data object or caller-selected scope can become a writer capability.
          eq(actual.binding(), supplied.binding());
          eq(actual.snapshot(), supplied.snapshot());
          eq(actual.inputs(), supplied.inputs());
          eq(actual.versionRefs(), supplied.versionRefs());
          if (!db.queryForList(
                  "select material_id from engine.model_input_snapshot where run_id=?",
                  String.class,
                  actual.binding().runId())
              .isEmpty()) eq(actual.materialId(), supplied.materialId());
          else {
            selector(supplied.materialId(), "m_");
            actual =
                new InitialSource(
                    actual.binding(),
                    actual.snapshot(),
                    actual.inputs(),
                    actual.versionRefs(),
                    supplied.materialId());
          }
          var expected = ModelInputMaterials.assemble(actual, proof);
          eq(Json.canonical(expected), Json.canonical(request));
          bounded(request, REQUEST_BYTES);
          String sha = Digest.sha256(Json.canonical(request));
          var b = actual.binding();
          var existing =
              db.queryForList(
                  "select request_sha256 from engine.model_input_snapshot where run_id=?",
                  String.class,
                  b.runId());
          if (!existing.isEmpty()) {
            eq(sha, existing.getFirst());
            var replay = readStored(actual.materialId(), sha, proof, b, false);
            localBinding(b, proof);
            return replay;
          }
          var parents = parents(b);
          var labels = new TreeSet<>(requiredLabels(b, proof));
          for (var n : request.path("requiredLabels")) labels.add(n.textValue());
          var roles = new TreeSet<>(b.initial().roles());
          var entitlements = new TreeMap<String, Set<String>>();
          b.initial()
              .attributes()
              .forEach(
                  (k, v) -> {
                    if (!k.equals("dataLabels")) entitlements.put(k, new TreeSet<>(v));
                  });
          for (var p : parents) {
            roles.addAll(p.requiredRoles());
            p.requiredEntitlements()
                .forEach((k, v) -> entitlements.computeIfAbsent(k, x -> new TreeSet<>()).addAll(v));
          }
          var ref = new MaterialRef("engine", "model-input", actual.materialId(), sha, sha);
          var scope =
              MaterialScopeStore.withDigest(
                  new MaterialScopeV1(
                      1,
                      ref,
                      b.owner(),
                      proof.current().decisionId(),
                      proof.current().authorizationVersion(),
                      b.initialSha(),
                      roles,
                      entitlements,
                      labels,
                      parents.stream().map(MaterialScopeV1::scopeSha256).toList(),
                      null));
          scopeBounds(scope);
          MaterialAuthorization.require(scope, proof.current(), clock);
          var raw =
              new ModelInputManifest(
                  1,
                  "engine",
                  "initial-chat-main-v1",
                  b.owner(),
                  b.root(),
                  b.publicId(),
                  b.snapshotSha(),
                  sha,
                  positive(request.path("modelEntryId")),
                  request.path("modelBindingRef"),
                  ref,
                  scope,
                  parents,
                  null);
          String manifestSha = Digest.sha256(Json.canonical(raw.unsigned()));
          var result =
              new ModelInputManifest(
                  raw.schemaVersion(),
                  raw.producer(),
                  raw.profile(),
                  raw.owner(),
                  raw.root(),
                  raw.runId(),
                  raw.acceptedSnapshotSha256(),
                  sha,
                  raw.modelEntryId(),
                  raw.modelBindingRef(),
                  ref,
                  scope,
                  parents,
                  manifestSha);
          bounded(Json.tree(result), MANIFEST_BYTES);
          db.update(
              "insert into"
                  + " engine.model_input_snapshot(material_id,run_id,tenant_id,principal_id,accepted_snapshot_sha256,model_entry_id,binding_id,binding_sha256,request,request_sha256,scope_sha256,manifest,manifest_sha256)"
                  + " values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?::jsonb,?)",
              ref.id(),
              b.runId(),
              b.owner().tenantId(),
              b.owner().principalId(),
              b.snapshotSha(),
              result.modelEntryId(),
              positive(result.modelBindingRef().path("bindingId")),
              text(result.modelBindingRef(), "sha256"),
              Json.write(request),
              sha,
              scope.scopeSha256(),
              Json.write(result),
              manifestSha);
          db.update(
              "insert into"
                  + " engine.material_scope(run_id,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope)"
                  + " values(?,?,'engine','model-input',?,?,?,?,?::jsonb)",
              b.runId(),
              b.owner().tenantId(),
              ref.id(),
              sha,
              sha,
              scope.scopeSha256(),
              Json.write(scope));
          localBinding(b, proof);
          return result;
        });
  }

  public ModelInputManifest readManifest(String id, String version, RetainedAuthorization proof) {
    selector(id, "m_");
    hash(version);
    exportPurpose(proof);
    // A caller transaction cannot silently weaken this snapshot or hold a write lock over HTTP.
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("MANIFEST_REQUIRES_NO_CALLER_TRANSACTION");
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setReadOnly(true);
    tx.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
    ModelInputManifest result;
    try {
      result =
          tx.execute(
              s -> {
                exportPurpose(proof);
                var a = proof.current();
                var runs =
                    db.queryForList(
                        "select run_id from engine.model_input_snapshot where material_id=? and"
                            + " request_sha256=? and tenant_id=? and principal_id=?",
                        Long.class,
                        id,
                        version,
                        a.actor().tenantId(),
                        a.actor().principalId());
                if (runs.size() != 1) throw missing();
                var b = binding(runs.getFirst());
                exportBinding(b, proof);
                return readStored(id, version, proof, b, true);
              });
    } catch (PlatformException e) {
      if (e.status() == 400) throw missing();
      throw e;
    } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
      throw missing();
    }
    exportPurpose(proof);
    return result;
  }

  private ModelInputManifest readStored(
      String id,
      String version,
      RetainedAuthorization proof,
      RunAuthorityStore.Binding b,
      boolean export) {
    // Probe both JSON documents in PG before obtaining any variable-length metadata text.
    var sizes =
        db.queryForList(
            "select octet_length(manifest::text) as bytes from engine.model_input_snapshot where"
                + " material_id=? and run_id=? and tenant_id=? and principal_id=? and"
                + " request_sha256=?",
            id,
            b.runId(),
            b.owner().tenantId(),
            b.owner().principalId(),
            version);
    if (sizes.size() != 1) throw missing();
    limit(num(sizes.getFirst(), "bytes"), MANIFEST_BYTES);
    var rows =
        db.queryForList(
            "select"
                + " material_id,run_id,tenant_id,principal_id,accepted_snapshot_sha256,model_entry_id,binding_id,binding_sha256,request_sha256,scope_sha256,manifest_sha256,case"
                + " when octet_length(manifest::text)<=262144 then manifest::text end as manifest"
                + " from engine.model_input_snapshot where material_id=? and run_id=? and"
                + " tenant_id=? and principal_id=? and request_sha256=?",
            id,
            b.runId(),
            b.owner().tenantId(),
            b.owner().principalId(),
            version);
    if (rows.size() != 1) throw missing();
    var row = rows.getFirst();
    var json = parse((String) row.get("manifest"), MANIFEST_BYTES);
    if (!json.isObject() || json.size() != 14) throw missing();
    var unsigned = json.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned).remove("manifestSha256");
    eq(Digest.sha256(Json.canonical(unsigned)), text(json, "manifestSha256"));
    eq(text(json, "manifestSha256"), row.get("manifest_sha256"));
    ModelInputManifest m;
    try {
      m = EXACT.treeToValue(json, ModelInputManifest.class);
    } catch (Exception e) {
      throw missing();
    }
    eq(1, m.schemaVersion());
    eq("engine", m.producer());
    eq("initial-chat-main-v1", m.profile());
    eq(b.owner(), m.owner());
    eq(b.root(), m.root());
    eq(b.publicId(), m.runId());
    selector(m.runId(), "r_");
    eq(b.snapshotSha(), m.acceptedSnapshotSha256());
    eq(version, m.requestSha256());
    eq(b.snapshotSha(), row.get("accepted_snapshot_sha256"));
    eq(m.modelEntryId(), num(row, "model_entry_id"));
    eq(positive(m.modelBindingRef().path("bindingId")), num(row, "binding_id"));
    hash(text(m.modelBindingRef(), "sha256"));
    eq(text(m.modelBindingRef(), "sha256"), row.get("binding_sha256"));
    eq(new MaterialRef("engine", "model-input", id, version, version), m.material());
    eq(m.material(), m.scope().material());
    eq(m.owner(), m.scope().owner());
    eq(b.initialSha(), m.scope().initialCeilingSha256());
    eq(m.scope().scopeSha256(), row.get("scope_sha256"));
    var p = parents(b);
    eq(p, m.parents());
    eq(p.stream().map(MaterialScopeV1::scopeSha256).toList(), m.scope().parentScopeSha256());
    scopeBounds(m.scope());
    MaterialAuthorization.require(m.scope(), proof.current(), clock);
    if (!m.scope().requiredRoles().containsAll(b.initial().roles())) throw missing();
    var original = new TreeMap<>(b.initial().attributes());
    original.remove("dataLabels");
    original.forEach(
        (k, v) -> {
          if (!m.scope().requiredEntitlements().getOrDefault(k, Set.of()).containsAll(v))
            throw missing();
        });
    for (var parent : p) {
      MaterialAuthorization.require(parent, proof.current(), clock);
      if (!m.scope().requiredRoles().containsAll(parent.requiredRoles())
          || !m.scope().requiredDataLabels().containsAll(parent.requiredDataLabels()))
        throw missing();
      parent
          .requiredEntitlements()
          .forEach(
              (k, v) -> {
                if (!m.scope().requiredEntitlements().getOrDefault(k, Set.of()).containsAll(v))
                  throw missing();
              });
    }
    if (!m.scope().requiredDataLabels().containsAll(requiredLabels(b, proof))) throw missing();
    var scopeSizes =
        db.queryForList(
            "select octet_length(scope::text) as bytes from engine.material_scope where run_id=?"
                + " and tenant_id=? and producer='engine' and kind='model-input' and material_id=?"
                + " and material_version=?",
            b.runId(),
            b.owner().tenantId(),
            id,
            version);
    if (scopeSizes.size() != 1) throw missing();
    limit(num(scopeSizes.getFirst(), "bytes"), MANIFEST_BYTES);
    var scopeRows =
        db.queryForList(
            "select payload_sha256,scope_sha256,case when octet_length(scope::text)<=262144 then"
                + " scope::text end as scope from engine.material_scope where run_id=? and"
                + " tenant_id=? and producer='engine' and kind='model-input' and material_id=? and"
                + " material_version=?",
            b.runId(),
            b.owner().tenantId(),
            id,
            version);
    if (scopeRows.size() != 1) throw missing();
    var s = scopeRows.getFirst();
    eq(version, s.get("payload_sha256"));
    eq(m.scope().scopeSha256(), s.get("scope_sha256"));
    try {
      eq(
          m.scope(),
          EXACT.treeToValue(parse((String) s.get("scope"), MANIFEST_BYTES), MaterialScopeV1.class));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw missing();
    }
    if (export) exportBinding(b, proof);
    else localBinding(b, proof);
    return m;
  }

  private RunAuthorityStore.Binding binding(long run) {
    var control =
        db.queryForList(
            "select octet_length(row_to_json(a)::text) as bytes from engine.run_authority a where"
                + " run_id=?",
            run);
    if (control.size() != 1) throw missing();
    limit(num(control.getFirst(), "bytes"), MANIFEST_BYTES);
    var initial =
        db.queryForList(
            "select octet_length(initial_ceiling::text) as bytes from engine.run_authority where"
                + " run_id=?",
            run);
    if (initial.size() != 1) throw missing();
    limit(num(initial.getFirst(), "bytes"), MANIFEST_BYTES);
    var scopes =
        db.queryForList(
            "select octet_length(scope::text) as bytes,case when octet_length(scope::text)<=262144"
                + " then scope::text end as scope from engine.material_scope where run_id=? and"
                + " kind in ('run-snapshot','run-inputs','run-version-refs')",
            run);
    if (scopes.size() != 3) throw missing();
    for (var s : scopes) {
      limit(num(s, "bytes"), MANIFEST_BYTES);
      scopeJsonBounds(parse((String) s.get("scope"), MANIFEST_BYTES));
    }
    try {
      var b = RunAuthorityStore.load(db, run, true);
      selector(b.publicId(), "r_");
      actorBounds(b.initial());
      for (var s : b.scopes()) scopeBounds(s);
      return b;
    } catch (PlatformException e) {
      if (e.status() == 503) throw e;
      throw missing();
    } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
      throw missing();
    }
  }

  public static Set<String> requiredLabels(
      RunAuthorityStore.Binding b, RetainedAuthorization proof) {
    var labels = new TreeSet<>(b.initial().attributes().getOrDefault("dataLabels", Set.of()));
    b.scopes().forEach(s -> labels.addAll(s.requiredDataLabels()));
    labels.addAll(proof.current().actor().attributes().getOrDefault("dataLabels", Set.of()));
    if (labels.size() > 256) throw missing();
    labels.forEach(ModelInputMaterialStore::identifier);
    return labels;
  }

  private List<MaterialScopeV1> parents(RunAuthorityStore.Binding b) {
    return b.scopes().stream().sorted(Comparator.comparing(MaterialScopeV1::scopeSha256)).toList();
  }

  private void localBinding(RunAuthorityStore.Binding b, RetainedAuthorization proof) {
    local(proof);
    RunAuthorityStore.strict(b, proof);
    // The run/authority locks are already held. Bind the retained writer to the latest
    // successful recordCheck CAS, just as MaterialScopeStore.requireWriter does; a newer
    // success must invalidate an earlier proof before payload access, write or replay.
    var check = db.queryForMap(
        "select last_decision_id,check_version from engine.run_authority where run_id=?",
        b.runId());
    eq(proof.current().decisionId(), check.get("last_decision_id"));
    eq(b.checkVersion(), num(check, "check_version"));
    if (b.blockedReason() != null
        || b.retryNotBefore() != null
        || !b.delegation().expiresAt().isAfter(clock.instant())) throw missing();
    for (var s : b.scopes()) MaterialAuthorization.require(s, proof.current(), clock);
  }

  private void local(RetainedAuthorization proof) {
    if (proof == null) throw missing();
    proof.requireVerified();
    var a = proof.current();
    if (!ServiceRoutePolicy.localRunAdvance(
        a.caller(), a.call(), "delegated", a.operation().sourceKind(), a.operation().originKind()))
      throw missing();
    actorBounds(proof.requireInitialCeiling());
    actorBounds(a.actor());
  }

  private void exportBinding(RunAuthorityStore.Binding b, RetainedAuthorization proof) {
    exportPurpose(proof);
    var a = proof.current();
    eq(b.owner(), new SubjectKey(a.actor().tenantId(), a.actor().principalId()));
    eq(b.root(), a.call().resource());
    eq(b.owner(), a.operation().subject());
    eq(b.initial(), proof.requireInitialCeiling());
    eq(b.originId(), proof.requireOriginAuthenticationId());
    for (var p : b.scopes()) MaterialAuthorization.require(p, a, clock);
  }

  public static void exportPurpose(RetainedAuthorization proof) {
    if (proof == null) throw missing();
    AuthorityContext.requireBound(proof);
    var a = proof.current();
    if (!"model".equals(a.caller())
        || !"engine".equals(a.call().target())
        || !"material.scope.read".equals(a.call().action())
        || !"app-version".equals(a.call().resource().kind())
        || !"DELEGATED".equals(a.operation().sourceKind())
        || !"PORTAL".equals(a.operation().originKind())) throw missing();
    eq(a.call().resource(), a.operation().resource());
    proof.requireInitialCeiling();
    proof.requireOriginAuthenticationId();
  }

  private <T> T write(Supplier<T> work) {
    RunAuthorityStore.requireWrite(source);
    try {
      return work.get();
    } catch (RuntimeException | Error e) {
      ((ConnectionHolder) TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
      throw e;
    }
  }

  private static JsonNode parse(String raw, int max) {
    if (raw == null) throw missing();
    limit(raw.getBytes(StandardCharsets.UTF_8).length, max);
    try {
      var n = EXACT.readTree(raw);
      bounded(n, max);
      return n;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw missing();
    }
  }

  public static void bounded(JsonNode n, int max) {
    if (n == null) throw missing();
    depth(n, 0);
    MaterialScopeStore.observedCanonicalCost(n);
    limit(Json.canonical(n).getBytes(StandardCharsets.UTF_8).length, max);
  }

  private static void depth(JsonNode n, int d) {
    if (d > 32) throw missing();
    if (n.isContainerNode()) for (var child : n) depth(child, d + 1);
  }

  private static void scopeJsonBounds(JsonNode n) {
    if (!n.isObject() || n.size() != 11) throw missing();
    text(n, "sourceDecisionId");
    text(n, "sourceAuthorizationVersion");
    var material = n.path("material");
    if (!material.isObject() || material.size() != 5) throw missing();
    for (String k : List.of("producer", "kind", "id", "version", "payloadSha256"))
      text(material, k);
    var owner = n.path("owner");
    if (!owner.isObject() || owner.size() != 2) throw missing();
    text(owner, "tenantId");
    text(owner, "principalId");
    array(n.path("requiredRoles"), 128);
    array(n.path("requiredDataLabels"), 256);
    array(n.path("parentScopeSha256"), 3);
    var e = n.path("requiredEntitlements");
    if (!e.isObject() || e.size() > 32) throw missing();
    e.fields()
        .forEachRemaining(
            x -> {
              identifier(x.getKey());
              array(x.getValue(), 256);
            });
  }

  private static void array(JsonNode n, int max) {
    if (!n.isArray() || n.size() > max) throw missing();
    for (var s : n) {
      if (!s.isTextual()) throw missing();
      identifier(s.textValue());
    }
  }

  private static void scopeBounds(MaterialScopeV1 s) {
    if (s == null) throw missing();
    scopeJsonBounds(Json.tree(s));
    hash(s.scopeSha256());
    eq(MaterialAuthorization.scopeDigest(s), s.scopeSha256());
  }

  private static void actorBounds(Actor a) {
    if (a == null || a.roles().size() > 128 || a.attributes().size() > 32) throw missing();
    identifier(a.tenantId());
    identifier(a.principalId());
    a.roles().forEach(ModelInputMaterialStore::identifier);
    a.attributes()
        .forEach(
            (k, v) -> {
              identifier(k);
              if (v.size() > (k.equals("dataLabels") ? 256 : 256)) throw missing();
              v.forEach(ModelInputMaterialStore::identifier);
            });
  }

  private static void identifier(String s) {
    if (s == null || s.isBlank() || s.length() > 256) throw missing();
  }

  public static void selector(String id, String prefix) {
    if (id == null || !id.matches(prefix + "[a-f0-9]{32}"))
      throw new PlatformException(400, "MATERIAL_SELECTOR_INVALID", "材料选择器无效");
  }

  public static void hash(String s) {
    if (s == null || !s.matches("[a-f0-9]{64}"))
      throw new PlatformException(400, "MATERIAL_SELECTOR_INVALID", "材料选择器无效");
  }

  public static long positive(JsonNode n) {
    if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1)
      throw missing();
    return n.longValue();
  }

  private static long num(Map<String, Object> m, String k) {
    if (!(m.get(k) instanceof Number n)) throw missing();
    return n.longValue();
  }

  private static String text(JsonNode n, String k) {
    var v = n.path(k);
    if (!v.isTextual()) throw missing();
    identifier(v.textValue());
    return v.textValue();
  }

  private static void limit(long n, long max) {
    if (n < 1 || n > max) throw missing();
  }

  private static void eq(Object a, Object b) {
    if (!Objects.equals(a, b)) throw missing();
  }

  public static PlatformException missing() {
    return new PlatformException(403, "MATERIAL_SCOPE_MISSING_OR_CHANGED", "材料范围缺失或已改变");
  }
}
