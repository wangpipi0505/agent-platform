package cn.agentplatform.engine.infra;

import static cn.agentplatform.engine.infra.RunAuthorityStore.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Metadata-first, one PostgreSQL snapshot. This component deliberately has no Spring stereotype. */
public final class MaterialScopeStore {
    static final List<String> KINDS=List.of("run-snapshot","run-inputs","run-version-refs");
    static final List<String> RUN_KINDS=List.of("run-state","run-budget","run-output","run-error");
    static final List<String> NODE_KINDS =
            List.of("node-definition", "node-state", "node-output", "node-error");
    static final List<String> ATTEMPT_KINDS =
            List.of("attempt-state", "attempt-request", "attempt-checkpoint", "attempt-error");
    // Jackson 2.19 JsonNodeDeserializer._fromFloat chooses getDecimalValue only with this flag.
    // Parse the persisted JSONB text, never round a decimal through binary double or global Json.
    private static final ObjectMapper EXACT=new ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final DataSource source;
    private final JdbcTemplate db;
    private final Clock clock;
    public MaterialScopeStore(DataSource source,Clock clock){this.source=Objects.requireNonNull(source);db=new JdbcTemplate(source);this.clock=Objects.requireNonNull(clock);}

    public AuthorizedMaterial read(MaterialRef ref, VerifiedAuthority current) {
        // Kept for source compatibility only. A public record is not cryptographic proof, even for
        // the original three immutable materials. Actual readers pass the retained private proof.
        throw denied();
    }

    public AuthorizedMaterial read(MaterialRef ref, RetainedAuthorization proof) {
        if (ref == null || proof == null || !"engine".equals(ref.producer()) || !known(ref.kind()))
            throw denied();
        proof.requireVerified();
        var result =
                snapshot(
                        source,
                        () -> {
                            var current = proof.current();
                            var ids =
                                    db.queryForList(
                                            "select run_id from engine.material_scope where"
                                                + " tenant_id=? and producer=? and kind=? and"
                                                + " material_id=? and material_version=?",
                                            Long.class,
                                            current.actor().tenantId(),
                                            ref.producer(),
                                            ref.kind(),
                                            ref.id(),
                                            ref.version());
                            if (ids.size() != 1) throw denied();
                            var b = RunAuthorityStore.load(db, ids.getFirst(), true);
                            requireReadProof(b, proof);
                            var s =
                                    KINDS.contains(ref.kind())
                                            ? b.scopes().stream()
                                                    .filter(v -> v.material().equals(ref))
                                                    .findFirst()
                                                    .orElseThrow(RunAuthorityStore::denied)
                                            : currentScope(b, ref.kind(), ref.id());
                            same(s.material(), ref);
                            MaterialAuthorization.require(s, current, clock);
                            var value = readCurrent(b, s, proof);
                            requireReadProof(b, proof);
                            return new AuthorizedMaterial(value, s);
                        });
        proof.requireVerified();
        return result;
    }

    /**
     * Package-only trusted primitive. Outer create owns both requireBound checks and transaction.
     */
    void write(MaterialScopeV1 scope) {
        requireWrite(source);
        if (scope == null || scope.material() == null || scope.owner() == null) throw denied();
        var ids =
                db.queryForList(
                        "select id from engine.run where public_id=? and tenant_id=?",
                        Long.class,
                        scope.material().id(),
                        scope.owner().tenantId());
        if (ids.size() != 1) throw denied();
        var b = RunAuthorityStore.load(db, ids.getFirst(), false);
        validate(scope, b);
        same(scope.material().payloadSha256(),payloadDigest(db,scope.material().kind(),b.runId()));
        if(scope.material().kind().equals("run-version-refs")){
            var parents =
                    db.queryForList(
                            "select scope_sha256 from engine.material_scope where run_id=? and"
                                + " kind='run-snapshot'",
                            String.class,
                            b.runId());
            if(parents.size()!=1||!scope.parentScopeSha256().equals(parents))throw denied();
            var parent =
                    Json.read(
                            db.queryForObject(
                                    "select scope::text from engine.material_scope where run_id=?"
                                        + " and kind='run-snapshot'",
                                    String.class,
                                    b.runId()),
                            MaterialScopeV1.class);
            if(!scope.requiredDataLabels().containsAll(parent.requiredDataLabels()))throw denied();
        }else if(!scope.parentScopeSha256().isEmpty())throw denied();
        var r=scope.material();
        db.update(
                "insert into"
                    + " engine.material_scope(run_id,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope)"
                    + " values(?,?,?,?,?,?,?,?,?::jsonb)",
                b.runId(),
                scope.owner().tenantId(),
                r.producer(),
                r.kind(),
                r.id(),
                r.version(),
                r.payloadSha256(),
                scope.scopeSha256(),
                Json.write(scope));
    }
    static List<MaterialScopeV1> scopes(JdbcTemplate db,RunAuthorityStore.Binding b){
        var rows =
                db.queryForList(
                        "select"
                            + " tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,"
                            + "scope::text"
                            + " as scope from engine.material_scope where run_id=? and kind in"
                            + " ('run-snapshot','run-inputs','run-version-refs')",
                        b.runId());
        if(rows.size()!=3)throw denied();var scopes=new ArrayList<MaterialScopeV1>();var kinds=new HashSet<String>();
        for(var row:rows){
            MaterialScopeV1 s;try{
                s = Json.read(str(row, "scope"), MaterialScopeV1.class);
            }catch(RuntimeException e){throw denied();}
            validate(s,b);if(!kinds.add(s.material().kind()))throw denied();
            same(s.owner().tenantId(), str(row, "tenant_id"));
            same(
                    s.material(),
                    new MaterialRef(
                            str(row, "producer"),
                            str(row, "kind"),
                            str(row, "material_id"),
                            str(row, "material_version"),
                            str(row, "payload_sha256")));
            same(s.scopeSha256(), str(row, "scope_sha256"));
            scopes.add(s);
        }
        var parent =
                scopes.stream()
                        .filter(s -> s.material().kind().equals("run-snapshot"))
                        .findFirst()
                        .orElseThrow(RunAuthorityStore::denied);
        for(var s:scopes){
            same(
                    s.parentScopeSha256(),
                    s.material().kind().equals("run-version-refs")
                            ? List.of(parent.scopeSha256())
                            : List.of());
            if (s.material().kind().equals("run-version-refs")
                    && !s.requiredDataLabels().containsAll(parent.requiredDataLabels()))
                throw denied();
        }
        scopes.sort(Comparator.comparing(s->s.material().kind()));return List.copyOf(scopes);
    }
    private static void validate(MaterialScopeV1 s,RunAuthorityStore.Binding b){
        if(s==null||s.schemaVersion()!=1||s.material()==null||s.owner()==null||s.requiredRoles()==null||s.requiredEntitlements()==null||s.requiredDataLabels()==null||s.parentScopeSha256()==null)throw denied();
        var r=s.material();if(!"engine".equals(r.producer())||!KINDS.contains(r.kind()))throw denied();
        same(b.owner(),s.owner());same(b.publicId(),r.id());same(b.snapshotSha(),r.version());hash(r.payloadSha256());hash(s.scopeSha256());
        same(b.admissionDecision(),s.sourceDecisionId());same(b.authorizationVersion(),s.sourceAuthorizationVersion());same(b.initialSha(),s.initialCeilingSha256());
        same(b.initial().roles(),s.requiredRoles());var entitlements=new TreeMap<>(b.initial().attributes());
        entitlements.remove("dataLabels");
        same(entitlements,s.requiredEntitlements());
        if (!s.requiredDataLabels()
                .containsAll(b.initial().attributes().getOrDefault("dataLabels", Set.of())))
            throw denied();
        s.requiredDataLabels().forEach(RunAuthorityStore::nonblank);
        same(MaterialAuthorization.scopeDigest(s),s.scopeSha256());
        if("run-snapshot".equals(r.kind()))same(b.snapshotSha(),r.payloadSha256());
    }
    static MaterialScopeV1 withDigest(MaterialScopeV1 s){return new MaterialScopeV1(s.schemaVersion(),s.material(),s.owner(),s.sourceDecisionId(),s.sourceAuthorizationVersion(),s.initialCeilingSha256(),s.requiredRoles(),s.requiredEntitlements(),s.requiredDataLabels(),s.parentScopeSha256(),MaterialAuthorization.scopeDigest(s));}

    private static boolean known(String kind) {
        return KINDS.contains(kind)
                || RUN_KINDS.contains(kind)
                || NODE_KINDS.contains(kind)
                || ATTEMPT_KINDS.contains(kind)
                || "run-event".equals(kind);
    }

    void requireReader(Binding b, VerifiedAuthority current) {
        boolean local =
                ServiceRoutePolicy.localRunAdvance(
                        current.caller(),
                        current.call(),
                        "delegated",
                        current.operation().sourceKind(),
                        current.operation().originKind());
        requirePurpose(current, local);
        same(b.owner(), new SubjectKey(current.actor().tenantId(), current.actor().principalId()));
        same(b.root(), current.call().resource());
    }

    private void requireReadProof(Binding b, RetainedAuthorization proof) {
        proof.requireVerified();
        requireReader(b, proof.current());
        if (ServiceRoutePolicy.localRunAdvance(
                proof.current().caller(),
                proof.current().call(),
                "delegated",
                proof.current().operation().sourceKind(),
                proof.current().operation().originKind())) requireWriter(b, proof, false);
        else AuthorityContext.requireBound(proof);
    }

    /**
     * Admission can initialize only rows owned by the concrete INSERT callers. Mutations require
     * the persisted successful current observation; a signed proof alone cannot clear a block.
     */
    void requireWriter(Binding b, RetainedAuthorization proof, boolean admission) {
        if (proof == null) throw denied();
        proof.requireVerified();
        if (admission) {
            AuthorityContext.requireBound(proof);
            requirePurpose(proof.current(), false);
            same(b.root(), proof.current().call().resource());
            same(b.delegation(), proof.current().delegation());
            same(b.initial(), proof.requireInitialCeiling());
            same(b.originId(), proof.requireOriginAuthenticationId());
        } else {
            RunAuthorityStore.strict(b, proof);
            var checks =
                    db.queryForMap(
                            "select blocked_reason,last_decision_id from engine.run_authority where"
                                + " run_id=?",
                            b.runId());
            if (checks.get("blocked_reason") != null) throw denied();
            same(proof.current().decisionId(), checks.get("last_decision_id"));
        }
    }

    List<MaterialScopeV1> currentScopes(Binding b) {
        var all = new ArrayList<>(b.scopes());
        var rows =
                db.queryForList(
                        "select distinct on (kind,material_id)"
                            + " tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,"
                            + "case when kind<>'model-input' or octet_length(scope::text)<=262144 then scope::text end"
                            + " as scope,material_revision from engine.material_scope where"
                            + " run_id=? and kind not in"
                            + " ('run-snapshot','run-inputs','run-version-refs') order by"
                            + " kind,material_id,material_revision desc",
                        b.runId());
        for (var row : rows) all.add(dynamic(row, b));
        return List.copyOf(all);
    }

    public List<MaterialRef> currentRefs(long run, RetainedAuthorization proof) {
        var result =
                snapshot(
                        source,
                        () -> {
                            proof.requireVerified();
                            var b = RunAuthorityStore.load(db, run, true);
                            requireReadProof(b, proof);
                            var scopes = currentScopes(b);
                            scopes.forEach(
                                    s -> MaterialAuthorization.require(s, proof.current(), clock));
                            requireReadProof(b, proof);
                            return scopes.stream().map(MaterialScopeV1::material).toList();
                        });
        proof.requireVerified();
        return result;
    }

    private MaterialScopeV1 currentScope(Binding b, String kind, String locator) {
        var rows =
                db.queryForList(
                        "select"
                            + " tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,"
                            + "case when kind<>'model-input' or octet_length(scope::text)<=262144 then scope::text end"
                            + " as scope,material_revision from engine.material_scope where"
                            + " run_id=? and kind=? and material_id=? order by material_revision"
                            + " desc limit 1",
                        b.runId(),
                        kind,
                        locator);
        if (rows.size() != 1) throw denied();
        return dynamic(rows.getFirst(), b);
    }

    private MaterialScopeV1 dynamic(Map<String, Object> row, Binding b) {
        if ("model-input".equals(row.get("kind"))) return initialScope(row, b);
        MaterialScopeV1 s;
        try {
            s = Json.read(str(row, "scope"), MaterialScopeV1.class);
        } catch (RuntimeException e) {
            throw denied();
        }
        var r = s.material();
        if (r == null || !known(r.kind()) || KINDS.contains(r.kind())) throw denied();
        same(
                new MaterialRef(
                        str(row, "producer"),
                        str(row, "kind"),
                        str(row, "material_id"),
                        str(row, "material_version"),
                        str(row, "payload_sha256")),
                r);
        same(Long.toString(((Number) row.get("material_revision")).longValue()), r.version());
        same(s.scopeSha256(), str(row, "scope_sha256"));
        same(s.owner(), b.owner());
        same(s.owner().tenantId(), str(row, "tenant_id"));
        same(s.initialCeilingSha256(), b.initialSha());
        nonblank(s.sourceDecisionId());
        nonblank(s.sourceAuthorizationVersion());
        same(MaterialAuthorization.scopeDigest(s), s.scopeSha256());
        requireLocator(b.runId(), r.kind(), r.id());
        var parents = new ArrayList<MaterialScopeV1>();
        if (s.parentScopeSha256() == null
                || s.parentScopeSha256().isEmpty()
                || new HashSet<>(s.parentScopeSha256()).size() != s.parentScopeSha256().size())
            throw denied();
        for (String hash : s.parentScopeSha256()) {
            var found =
                    db.queryForList(
                            "select scope::text from engine.material_scope where run_id=? and"
                                + " scope_sha256=? and material_revision<?",
                            String.class,
                            b.runId(),
                            hash,
                            ((Number) row.get("material_revision")).longValue());
            if (found.size() != 1) throw denied();
            var parent = Json.read(found.getFirst(), MaterialScopeV1.class);
            same(parent.scopeSha256(), hash);
            same(MaterialAuthorization.scopeDigest(parent), hash);
            same(parent.owner(), b.owner());
            same(parent.initialCeilingSha256(), b.initialSha());
            parents.add(parent);
        }
        requireFloor(s, b, parents);
        return s;
    }

    /** One immutable SHA-versioned kind; generic observer and revision parsing stay closed. */
    private MaterialScopeV1 initialScope(Map<String, Object> row, Binding b) {
        if (row.get("scope") == null) throw denied();
        var rows = db.queryForList("select material_id,request_sha256,scope_sha256,manifest_sha256,"
                + "case when octet_length(manifest::text)<=262144 then manifest::text end as manifest"
                + " from engine.model_input_snapshot where run_id=? and tenant_id=? and principal_id=?",
                b.runId(), b.owner().tenantId(), b.owner().principalId());
        if (rows.size() != 1 || rows.getFirst().get("manifest") == null) throw denied();
        var stored = rows.getFirst();
        var m = Json.read(str(stored, "manifest"), cn.agentplatform.engine.domain.ModelInputManifest.class);
        var s = m.scope();
        same("initial-chat-main-v1", m.profile()); same("engine", m.producer()); same(1, m.schemaVersion());
        same(b.owner(), m.owner()); same(b.root(), m.root()); same(b.publicId(), m.runId());
        same(b.snapshotSha(), m.acceptedSnapshotSha256());
        same(Digest.sha256(Json.canonical(m.unsigned())), m.manifestSha256());
        same(m.manifestSha256(), stored.get("manifest_sha256"));
        same(new MaterialRef("engine", "model-input", str(stored, "material_id"),
                str(stored, "request_sha256"), str(stored, "request_sha256")), m.material());
        ModelInputMaterialStore.selector(m.material().id(), "m_");
        same(m.requestSha256(), m.material().version());
        same(m.material(), s.material()); same(b.owner(), s.owner());
        same(b.initialSha(), s.initialCeilingSha256());
        same(MaterialAuthorization.scopeDigest(s), s.scopeSha256());
        same(s.scopeSha256(), stored.get("scope_sha256"));
        var parents = b.scopes().stream().sorted(Comparator.comparing(MaterialScopeV1::scopeSha256)).toList();
        same(parents, m.parents());
        same(parents.stream().map(MaterialScopeV1::scopeSha256).toList(), s.parentScopeSha256());
        requireFloor(s, b, parents);
        same(new MaterialRef(str(row, "producer"), str(row, "kind"), str(row, "material_id"),
                str(row, "material_version"), str(row, "payload_sha256")), m.material());
        same(b.owner().tenantId(), row.get("tenant_id"));
        same(s.scopeSha256(), row.get("scope_sha256"));
        same(s, Json.read(str(row, "scope"), MaterialScopeV1.class));
        return s;
    }

    private static void requireFloor(MaterialScopeV1 s, Binding b, List<MaterialScopeV1> parents) {
        var roles = new TreeSet<>(b.initial().roles());
        var attrs = new TreeMap<String, Set<String>>();
        b.initial()
                .attributes()
                .forEach(
                        (k, v) -> {
                            if (!k.equals("dataLabels")) attrs.put(k, new TreeSet<>(v));
                        });
        var labels = new TreeSet<>(b.initial().attributes().getOrDefault("dataLabels", Set.of()));
        for (var p : b.scopes()) labels.addAll(p.requiredDataLabels());
        for (var p : parents) {
            roles.addAll(p.requiredRoles());
            p.requiredEntitlements()
                    .forEach((k, v) -> attrs.computeIfAbsent(k, key -> new TreeSet<>()).addAll(v));
            labels.addAll(p.requiredDataLabels());
        }
        if (s.requiredRoles() == null
                || !s.requiredRoles().containsAll(roles)
                || s.requiredEntitlements() == null
                || s.requiredDataLabels() == null
                || !s.requiredDataLabels().containsAll(labels)) throw denied();
        attrs.forEach(
                (k, v) -> {
                    if (!s.requiredEntitlements().getOrDefault(k, Set.of()).containsAll(v))
                        throw denied();
                });
    }

    private void requireLocator(long run, String kind, String locator) {
        if (RUN_KINDS.contains(kind)) {
            same(
                    db.queryForObject(
                            "select public_id from engine.run where id=?", String.class, run),
                    locator);
            return;
        }
        long id;
        try {
            id = Long.parseLong(locator);
            if (id < 1 || !Long.toString(id).equals(locator)) throw denied();
        } catch (NumberFormatException e) {
            throw denied();
        }
        String sql =
                NODE_KINDS.contains(kind)
                        ? "select run_id from engine.node_instance where id=?"
                        : ATTEMPT_KINDS.contains(kind)
                                ? "select n.run_id from engine.attempt a join engine.node_instance"
                                      + " n on n.id=a.node_instance_id where a.id=?"
                                : "select run_id from engine.run_event where id=?";
        var ids = db.queryForList(sql, Long.class, id);
        if (ids.size() != 1 || ids.getFirst() != run) throw denied();
    }

    /** All parents are reread from append-only metadata, never accepted from a public record. */
    MaterialScopeV1 append(
            Binding b,
            String kind,
            String locator,
            RetainedAuthorization proof,
            List<MaterialScopeV1> consumed) {
        requireWrite(source);
        if (!known(kind) || KINDS.contains(kind)) throw denied();
        requireLocator(b.runId(), kind, locator);
        proof.requireVerified();
        var parents = new TreeMap<String, MaterialScopeV1>();
        for (var p : b.scopes()) parents.put(p.scopeSha256(), p);
        for (var p : consumed) {
            var rows =
                    db.queryForList(
                            "select scope::text from engine.material_scope where run_id=? and"
                                + " scope_sha256=?",
                            String.class,
                            b.runId(),
                            p.scopeSha256());
            if (rows.size() != 1) throw denied();
            same(Json.read(rows.getFirst(), MaterialScopeV1.class), p);
            parents.put(p.scopeSha256(), p);
        }
        var roles = new TreeSet<>(b.initial().roles());
        var attrs = new TreeMap<String, Set<String>>();
        b.initial()
                .attributes()
                .forEach(
                        (k, v) -> {
                            if (!k.equals("dataLabels")) attrs.put(k, new TreeSet<>(v));
                        });
        var labels =
                new TreeSet<>(
                        proof.current().actor().attributes().getOrDefault("dataLabels", Set.of()));
        for (var p : parents.values()) {
            roles.addAll(p.requiredRoles());
            p.requiredEntitlements()
                    .forEach((k, v) -> attrs.computeIfAbsent(k, key -> new TreeSet<>()).addAll(v));
            labels.addAll(p.requiredDataLabels());
            MaterialAuthorization.require(p, proof.current(), clock);
        }
        long revision =
                db.queryForObject(
                        "select"
                            + " nextval(pg_get_serial_sequence('engine.material_scope','material_revision'))",
                        Long.class);
        var ref =
                new MaterialRef(
                        "engine",
                        kind,
                        locator,
                        Long.toString(revision),
                        digest(payload(db, kind, locator, b.runId())));
        var scope =
                withDigest(
                        new MaterialScopeV1(
                                1,
                                ref,
                                b.owner(),
                                proof.current().decisionId(),
                                proof.current().authorizationVersion(),
                                b.initialSha(),
                                roles,
                                attrs,
                                labels,
                                List.copyOf(parents.keySet()),
                                null));
        requireFloor(scope, b, List.copyOf(parents.values()));
        MaterialAuthorization.require(scope, proof.current(), clock);
        db.update(
                "insert into"
                    + " engine.material_scope(run_id,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,scope,material_revision)"
                    + " values(?,?,?,?,?,?,?,?,?::jsonb,?)",
                b.runId(),
                b.owner().tenantId(),
                "engine",
                kind,
                locator,
                ref.version(),
                ref.payloadSha256(),
                scope.scopeSha256(),
                Json.write(scope),
                revision);
        proof.requireVerified();
        return scope;
    }

    JsonNode readCurrent(Binding b, MaterialScopeV1 s, RetainedAuthorization proof) {
        return readCurrentValue(b, s, proof).value();
    }

    record Payload(JsonNode value, boolean sqlNull) {}

    Payload readCurrentValue(Binding b, MaterialScopeV1 s, RetainedAuthorization proof) {
        proof.requireVerified();
        requireReader(b, proof.current());
        MaterialAuthorization.require(s, proof.current(), clock);
        var r = s.material();
        if ("model-input".equals(r.kind())) {
            // Admission and execution are different purposes. Both already proved this exact
            // read scope above; reading immutable bytes does not mint a local writer proof.
            var value=readInitialPayload(b,s);proof.requireVerified();return value;
        }
        var payload =
                KINDS.contains(r.kind())
                        ? new Payload(payload(db, r.kind(), b.runId()), false)
                        : payloadValue(db, r.kind(), r.id(), b.runId());
        same(r.payloadSha256(), digest(payload.value()));
        proof.requireVerified();
        return payload;
    }

    /** Fixed observer read only: this proof cannot enter the retained execution writer overload. */
    Payload readObservedValue(Binding binding,MaterialScopeV1 scope,TraceStore.StatusReadProof proof){
        proof.requireMaterial(binding,scope,clock);var ref=scope.material();
        if("model-input".equals(ref.kind())){var value=readInitialPayload(binding,scope);proof.requireMaterial(binding,scope,clock);return value;}
        var value=KINDS.contains(ref.kind())?new Payload(payload(db,ref.kind(),binding.runId()),false):payloadValue(db,ref.kind(),ref.id(),binding.runId());
        same(ref.payloadSha256(),digest(value.value()));proof.requireMaterial(binding,scope,clock);return value;
    }

    private static final String INITIAL_COLUMNS="material_id,run_id,tenant_id,principal_id,accepted_snapshot_sha256,model_entry_id,binding_id,binding_sha256,request_sha256,scope_sha256,manifest_sha256";
    private Payload readInitialPayload(Binding b,MaterialScopeV1 scope){
        var rows=db.queryForList("select "+INITIAL_COLUMNS+",octet_length(manifest::text) as manifest_bytes,octet_length(canonical_request_utf8) as raw_bytes,octet_length(request::text) as semantic_bytes from engine.model_input_snapshot where run_id=?",b.runId());
        if(rows.size()!=1)throw denied();var row=rows.getFirst();
        for(String k:List.of("manifest_bytes","raw_bytes","semantic_bytes"))ModelInputMaterialStore.limit(RunEventReader.num(row,k),k.equals("manifest_bytes")?262144:2097152);
        var text=db.queryForMap("select case when octet_length(manifest::text)<=262144 then manifest::text end as manifest,case when octet_length(canonical_request_utf8)<=2097152 then canonical_request_utf8 end as raw,case when octet_length(request::text)<=2097152 then request::text end as semantic from engine.model_input_snapshot where run_id=?",b.runId());
        var manifest=initialManifest(b,scope,row,ModelInputMaterialStore.parse(str(text,"manifest"),262144));
        String raw=str(text,"raw");same(scope.material().version(),Digest.sha256(raw));
        var value=ModelInputMaterialStore.parse(raw,2097152);var semantic=ModelInputMaterialStore.parse(str(text,"semantic"),2097152);
        same(observedDigest(value),observedDigest(semantic));requireInitialRequest(b,manifest,value);
        var snapshot=payload(db,"run-snapshot",b.runId());
        same(b.snapshotSha(),observedDigest(snapshot));requireInitialBinding(manifest,snapshot);
        return new Payload(value,false);
    }
    private static cn.agentplatform.engine.domain.ModelInputManifest initialManifest(Binding b,MaterialScopeV1 scope,Map<String,Object> row,JsonNode json){
        if(!json.isObject()||json.size()!=14)throw denied();
        cn.agentplatform.engine.domain.ModelInputManifest m;
        try{m=EXACT.treeToValue(json,cn.agentplatform.engine.domain.ModelInputManifest.class);}catch(Exception invalid){throw denied();}
        same(1,m.schemaVersion());same("engine",m.producer());same("initial-chat-main-v1",m.profile());
        same(b.owner(),m.owner());same(b.root(),m.root());same(b.publicId(),m.runId());same(b.snapshotSha(),m.acceptedSnapshotSha256());
        same(b.runId(),RunEventReader.num(row,"run_id"));same(b.owner().tenantId(),row.get("tenant_id"));same(b.owner().principalId(),row.get("principal_id"));same(b.snapshotSha(),row.get("accepted_snapshot_sha256"));
        same(Digest.sha256(Json.canonical(m.unsigned())),m.manifestSha256());same(m.manifestSha256(),row.get("manifest_sha256"));
        same(new MaterialRef("engine","model-input",str(row,"material_id"),str(row,"request_sha256"),str(row,"request_sha256")),m.material());
        ModelInputMaterialStore.selector(m.material().id(),"m_");hash(m.requestSha256());same(m.requestSha256(),m.material().version());same(m.material(),scope.material());same(scope,m.scope());same(b.owner(),scope.owner());same(b.initialSha(),scope.initialCeilingSha256());
        same(MaterialAuthorization.scopeDigest(scope),scope.scopeSha256());same(scope.scopeSha256(),row.get("scope_sha256"));
        same(m.modelEntryId(),RunEventReader.num(row,"model_entry_id"));if(m.modelEntryId()<1||!m.modelBindingRef().isObject()||m.modelBindingRef().size()!=2)throw denied();
        same(positive(m.modelBindingRef().path("bindingId")),RunEventReader.num(row,"binding_id"));hash(text(m.modelBindingRef(),"sha256"));same(text(m.modelBindingRef(),"sha256"),row.get("binding_sha256"));
        var parents=b.scopes().stream().sorted(Comparator.comparing(MaterialScopeV1::scopeSha256)).toList();
        same(parents,m.parents());same(parents.stream().map(MaterialScopeV1::scopeSha256).toList(),scope.parentScopeSha256());requireFloor(scope,b,parents);
        return m;
    }
    private static void requireInitialRequest(Binding b,cn.agentplatform.engine.domain.ModelInputManifest m,JsonNode request){
        same("material:"+m.material().id()+":MAIN",request.path("invocationRef").asText());same(b.publicId(),request.path("runId").asText());same("FORMAL",request.path("callKind").asText());
        same(m.modelEntryId(),positive(request.path("modelEntryId")));same(m.modelBindingRef(),request.path("modelBindingRef"));
    }
    private static void requireInitialBinding(cn.agentplatform.engine.domain.ModelInputManifest m,JsonNode snapshot){
        same(m.modelEntryId(),cn.agentplatform.engine.domain.InitialChatRules.entryId(snapshot));
        same(m.modelBindingRef(),cn.agentplatform.engine.application.FrozenModels.reference(snapshot,m.modelEntryId()));
    }

    static JsonNode payload(JdbcTemplate db, String kind, String locator, long run) {
        return payloadValue(db, kind, locator, run).value();
    }

    /** Finite event plan: identity scalars are schema-bounded; full scope text is a later query. */
    record ObservedRow(long run,long revision,String tenant,String producer,String kind,String locator,String version,String payload,String sha,long bytes,long parents){}
    static List<ObservedRow> probeObserved(RunEventReader.Work w,long run,String predicate,int limit,Object...args){
        if(limit<1||limit>RunEventReader.MAX_RECORDS+1)throw denied();
        var parameters=new ArrayList<Object>();parameters.add(run);parameters.addAll(Arrays.asList(args));
        var rows=w.query("select material_revision,tenant_id,producer,kind,material_id,material_version,payload_sha256,scope_sha256,octet_length(scope::text) as scope_bytes,jsonb_typeof(scope) as scope_type,jsonb_typeof(scope->'parentScopeSha256') as parent_type,case when jsonb_typeof(scope->'parentScopeSha256')='array' then jsonb_array_length(scope->'parentScopeSha256') end as parent_count from engine.material_scope where run_id=? and ("+predicate+") order by material_revision limit "+limit,limit,parameters.toArray());
        var result=new ArrayList<ObservedRow>();
        for(var row:rows){
            same("object",row.get("scope_type"));same("array",row.get("parent_type"));
            long bytes=RunEventReader.num(row,"scope_bytes"),parents=RunEventReader.num(row,"parent_count"),revision=RunEventReader.num(row,"material_revision");
            RunEventReader.Limits.single(bytes,RunEventReader.JSON_BYTES,"SCOPE_BYTES");RunEventReader.Limits.parents(parents);if(revision<1)throw denied();
            hash(str(row,"payload_sha256"));hash(str(row,"scope_sha256"));String kind=str(row,"kind");if(!known(kind)&&!"model-input".equals(kind))throw denied();
            // V216/217 constrain these scalar columns (tenant64, locator80, fixed kind/producer,
            // hash64 or positive bigint revision). They never carry arbitrary payload JSON.
            result.add(new ObservedRow(run,revision,str(row,"tenant_id"),str(row,"producer"),kind,str(row,"material_id"),str(row,"material_version"),str(row,"payload_sha256"),str(row,"scope_sha256"),bytes,parents));
        }w.live();return result;
    }
    static void addObserved(Map<String,ObservedRow> plan,ObservedRow row,RunEventReader.Limits limits){
        var previous=plan.putIfAbsent(row.sha(),row);if(previous!=null){same(previous,row);return;}limits.record(row.bytes());
    }
    static MaterialScopeV1 readObservedScope(RunEventReader.Work w,Binding b,ObservedRow row){
        w.live();var node=w.jsonText("select case when octet_length(scope::text)<=1048576 and jsonb_typeof(scope)='object' then case when jsonb_typeof(scope->'parentScopeSha256')='array' then case when jsonb_array_length(scope->'parentScopeSha256')<=4096 then scope::text end end end as scope_text from engine.material_scope where run_id=? and scope_sha256=? and material_revision=?",row.bytes(),false,b.runId(),row.sha(),row.revision());
        var s=RunEventReader.scope(node);same((long)s.parentScopeSha256().size(),row.parents());
        try(var canonical=w.canonicalScratch(6*row.bytes())){
        var r=s.material();same(new MaterialRef(row.producer(),row.kind(),row.locator(),row.version(),row.payload()),r);same(row.sha(),s.scopeSha256());same(row.tenant(),b.owner().tenantId());same(b.owner(),s.owner());same(b.initialSha(),s.initialCeilingSha256());same(MaterialAuthorization.scopeDigest(s),s.scopeSha256());
        if(KINDS.contains(r.kind()))validate(s,b);
        else{if((!known(r.kind())&&!"model-input".equals(r.kind()))||s.parentScopeSha256().isEmpty())throw denied();same("engine",r.producer());
            if("model-input".equals(r.kind())){ModelInputMaterialStore.selector(r.id(),"m_");hash(r.version());same(r.version(),r.payloadSha256());if(s.parentScopeSha256().size()!=3)throw denied();}
            else same(Long.toString(row.revision()),r.version());nonblank(s.sourceDecisionId());nonblank(s.sourceAuthorizationVersion());}
        w.live();return s;}
    }
    static Binding requireObservedRoots(Binding b,List<MaterialScopeV1> scopes){
        if(scopes.size()!=3||scopes.stream().map(s->s.material().kind()).distinct().count()!=3)throw denied();
        var snapshot=scopes.stream().filter(s->s.material().kind().equals("run-snapshot")).findFirst().orElseThrow(RunAuthorityStore::denied);
        for(var s:scopes){validate(s,b);same(s.parentScopeSha256(),s.material().kind().equals("run-version-refs")?List.of(snapshot.scopeSha256()):List.of());if(s.material().kind().equals("run-version-refs")&&!s.requiredDataLabels().containsAll(snapshot.requiredDataLabels()))throw denied();}
        return new Binding(b.runId(),b.publicId(),b.owner(),b.delegation(),b.initial(),b.root(),b.appVersionId(),b.appVersionNo(),b.initialSha(),b.snapshotSha(),b.originId(),b.admissionDecision(),b.authorizationVersion(),b.operationId(),b.checkVersion(),b.blockedReason(),b.retryNotBefore(),scopes);
    }
    static void requireObservedFloor(MaterialScopeV1 s,Binding b,List<MaterialScopeV1> parents){requireFloor(s,b,parents);}
    static void requireObservedLocator(RunEventReader.Work w,Binding b,ObservedRow row){
        if(KINDS.contains(row.kind())||RUN_KINDS.contains(row.kind())){same(b.publicId(),row.locator());return;}
        if("model-input".equals(row.kind())){
            ModelInputMaterialStore.selector(row.locator(),"m_");
            var rows=w.query("select run_id from engine.model_input_snapshot where material_id=? and request_sha256=?",2,row.locator(),row.version());
            if(rows.size()!=1||RunEventReader.num(rows.getFirst(),"run_id")!=b.runId())throw denied();return;
        }
        long id;try{id=Long.parseLong(row.locator());if(id<1||!Long.toString(id).equals(row.locator()))throw denied();}catch(NumberFormatException invalid){throw denied();}
        String sql=NODE_KINDS.contains(row.kind())?"select run_id from engine.node_instance where id=?":ATTEMPT_KINDS.contains(row.kind())?"select n.run_id from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where a.id=?":"select run_id from engine.run_event where id=?";
        var rows=w.query(sql,2,id);if(rows.size()!=1||RunEventReader.num(rows.getFirst(),"run_id")!=b.runId())throw denied();
    }
    static JsonNode readObservedRoot(RunEventReader.Work w,long run,String column,long bytes){
        if(!Set.of("snapshot","version_refs","inputs").contains(column))throw denied();w.live();
        var value=w.jsonText("select case when octet_length("+column+"::text)<=8388608 and jsonb_typeof("+column+")='object' then "+column+"::text end as payload_text from engine.run where id=?",bytes,true,run);object(value);w.live();return value;
    }
    /** Observer-only immutable lineage; every query/DOM/raw buffer spends the existing Work budget. */
    static void requireObservedInitial(RunEventReader.Work w,Binding b,MaterialScopeV1 scope,JsonNode snapshot){
        w.live();
        var rows=w.query("select "+INITIAL_COLUMNS+",octet_length(manifest::text) as manifest_bytes,octet_length(canonical_request_utf8) as raw_bytes,octet_length(request::text) as semantic_bytes from engine.model_input_snapshot where run_id=?",2,b.runId());
        if(rows.size()!=1)throw denied();var row=rows.getFirst();
        long metadata=RunEventReader.num(row,"manifest_bytes"),raw=RunEventReader.num(row,"raw_bytes"),semantic=RunEventReader.num(row,"semantic_bytes");
        RunEventReader.Limits.single(metadata,262144,"METADATA_BYTES");RunEventReader.Limits.single(raw,2097152,"ROOT_BYTES");RunEventReader.Limits.single(semantic,2097152,"ROOT_BYTES");
        w.limits.metadata(metadata);w.limits.root(raw);w.limits.root(semantic);
        var document=w.jsonText("select case when octet_length(manifest::text)<=262144 then manifest::text end as value from engine.model_input_snapshot where run_id=?",metadata,false,b.runId());
        try(var representation=w.ledger.reserve(8*metadata);var canonical=w.canonicalScratch(6*metadata)){
            var manifest=initialManifest(b,scope,row,document);w.live();
            var request=w.jsonTextHash("select case when octet_length(canonical_request_utf8)<=2097152 then canonical_request_utf8 end as value from engine.model_input_snapshot where run_id=?",raw,true,scope.material().version(),b.runId());
            var normalized=w.jsonText("select case when octet_length(request::text)<=2097152 then request::text end as value from engine.model_input_snapshot where run_id=?",semantic,true,b.runId());
            same(w.digest(request),w.digest(normalized));requireInitialRequest(b,manifest,request);requireInitialBinding(manifest,snapshot);w.live();
        }
    }
    /** Missing physical rows are candidates only. The caller must prove the complete terminal
     * state digest before accepting a historical, non-consumed event direct parent. */
    static boolean observedHistoricalEventMissing(RunEventReader.Work w,Binding b,ObservedRow row){
        same("run-event",row.kind());same(b.runId(),row.run());same("engine",row.producer());
        long id;try{id=Long.parseLong(row.locator());}catch(NumberFormatException invalid){throw denied();}
        if(id<1||!Long.toString(id).equals(row.locator()))throw denied();
        var rows=w.query("select run_id from engine.run_event where id=?",2,id);
        if(rows.isEmpty())return true;
        if(rows.size()!=1||RunEventReader.num(rows.getFirst(),"run_id")!=b.runId())throw denied();
        return false;
    }
    private static final String OBSERVED_STATE=" from (select id,public_id,app_code,status,event_seq,stop_reason,to_char(started_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as started_at,to_char(ended_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as ended_at,mode,trigger_type,output_ref from engine.run where id=?) material";
    static long probeObservedState(RunEventReader.Work w,Binding b){
        var rows=w.query("select octet_length(row_to_json(material)::text) as state_bytes"+OBSERVED_STATE,2,b.runId());
        if(rows.size()!=1)throw denied();long bytes=RunEventReader.num(rows.getFirst(),"state_bytes");
        RunEventReader.Limits.single(bytes,RunEventReader.JSON_BYTES,"STATE_ROW");return bytes;
    }
    static JsonNode readObservedState(RunEventReader.Work w,Binding b,MaterialScopeV1 scope,long bytes){
        var state=w.jsonText("select case when octet_length(row_to_json(material)::text)<=1048576 then row_to_json(material)::text end as state_text"+OBSERVED_STATE,bytes,true,b.runId());
        object(state);if(state.size()!=11)throw denied();same(b.runId(),state.path("id").longValue());same(b.publicId(),text(state,"public_id"));
        same(scope.material().payloadSha256(),w.digest(state));w.live();return state;
    }
    static JsonNode readObservedEvent(RunEventReader.Work w,Binding b,long id,long seq,MaterialScopeV1 scope){
        String material=" from (select row_to_json(e)::text as row_text,octet_length(e.payload::text) as public_bytes from (select id,run_id,seq,type,payload,to_char(created_at at time zone 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') as created_at from engine.run_event where id=? and run_id=? and seq=?) e) material";
        var probe=w.query("select octet_length(row_text) as row_bytes,public_bytes"+material,2,id,b.runId(),seq);if(probe.size()!=1)throw denied();
        long bytes=RunEventReader.num(probe.getFirst(),"row_bytes");RunEventReader.Limits.single(bytes,RunEventReader.JSON_BYTES,"EVENT_ROW");RunEventReader.Limits.single(RunEventReader.num(probe.getFirst(),"public_bytes"),RunEventReader.EVENT_BYTES,"EVENT_PAYLOAD");w.live();
        var value=w.jsonText("select case when octet_length(row_text)<=1048576 and public_bytes<=262144 then row_text end as event_text"+material,bytes,true,id,b.runId(),seq);object(value);if(value.size()!=6)throw denied();same(scope.material().payloadSha256(),w.digest(value));w.live();return value;
    }
    static final long OBSERVED_NORMALIZED_BYTES=8L*1024*1024;
    record ObservedDigest(String sha,int utf8Bytes){}
    static long observedCanonicalCost(JsonNode value){return normalizedCost(value,0);}
    static ObservedDigest observedDigestMeasured(JsonNode value){
        observedCanonicalCost(value);String normalized=RunEventReader.encodeText(normalize(value));
        // Count UTF-8 without a second encoded array. PG/parser provide valid Unicode; UTF-8
        // replacement for any unpaired surrogate follows String.getBytes (one replacement byte).
        int bytes=0;for(int i=0;i<normalized.length();i++){char c=normalized.charAt(i);if(c<128)bytes++;else if(c<2048)bytes+=2;else if(Character.isHighSurrogate(c)&&i+1<normalized.length()&&Character.isLowSurrogate(normalized.charAt(i+1))){bytes+=4;i++;}else bytes+=Character.isSurrogate(c)?1:3;}
        return new ObservedDigest(Digest.sha256(normalized),bytes);
    }
    static String observedDigest(JsonNode value){
        return observedDigestMeasured(value).sha();
    }
    /** Short exponents can expand during normalize/toBigIntegerExact. Admit that representation
     * before allocating it; this bound is separate from raw PG bytes and does not alter writers. */
    private static long normalizedCost(JsonNode value,long used){
        if(value.isObject()){
            used=RunEventReader.Limits.add(used,2,OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");
            var fields=value.fields();while(fields.hasNext()){var entry=fields.next();used=RunEventReader.Limits.add(used,6L*entry.getKey().length()+4,OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");used=normalizedCost(entry.getValue(),used);}return used;
        }
        if(value.isArray()){used=RunEventReader.Limits.add(used,2,OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");for(var child:value){used=RunEventReader.Limits.add(used,1,OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");used=normalizedCost(child,used);}return used;}
        long bytes=value.isTextual()?6L*value.textValue().length()+2:5;
        if(value.isNumber()){var decimal=value.decimalValue();bytes=decimal.scale()<=0?(long)decimal.precision()-decimal.scale()+2:(long)decimal.precision()+24;}
        return RunEventReader.Limits.add(used,bytes,OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");
    }

    private static Payload payloadValue(JdbcTemplate db, String kind, String locator, long run) {
        String sql =
                switch (kind) {
                    case "run-budget" -> "select budget::text from engine.run where id=?";
                    case "run-output" -> "select output::text from engine.run where id=?";
                    case "run-error" -> "select error::text from engine.run where id=?";
                    case "run-state" ->
                            projection(
                                    "run",
                                    "id,public_id,app_code,status,event_seq,stop_reason,started_at,ended_at,mode,trigger_type,output_ref");
                    case "node-definition" ->
                            "select definition::text from engine.node_instance where id=?";
                    case "node-output" ->
                            "select output::text from engine.node_instance where id=?";
                    case "node-error" -> "select error::text from engine.node_instance where id=?";
                    // Lease generation/extension is control metadata. Normal renewLeases changes
                    // lease_until
                    // without changing user facts; the concrete writer checks the actual lease in
                    // its CAS.
                    case "node-state" ->
                            projection(
                                    "node_instance",
                                    "id,run_id,node_def_id,step_seq,node_type,join_semantics,status,committed_attempt_id,committed_output_ref,input_digest,output_digest,started_at,ended_at,pause_seq,released,winner_node_id");
                    case "attempt-request" ->
                            "select request_snapshot::text from engine.attempt where id=?";
                    case "attempt-checkpoint" ->
                            "select checkpoint::text from engine.attempt where id=?";
                    case "attempt-error" -> "select error::text from engine.attempt where id=?";
                    case "attempt-state" ->
                            projection(
                                    "attempt",
                                    "id,node_instance_id,attempt_no,status,idempotency_key,request_snapshot_ref,response_ref,started_at,ended_at");
                    case "run-event" ->
                            projection("run_event", "id,run_id,seq,type,payload,created_at");
                    default -> throw denied();
                };
        String raw =
                db.queryForObject(
                        sql,
                        String.class,
                        RUN_KINDS.contains(kind) ? run : Long.parseLong(locator));
        try {
            // Both persist the JSON-null material digest, while original response presentation
            // still distinguishes SQL NULL (TraceStore.json(null) -> {}) from explicit JSON null.
            return new Payload(EXACT.readTree(raw == null ? "null" : raw), raw == null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw denied();
        }
    }

    // Only the fixed projections above call this method. UTC text is independent of JDBC/session
    // TZ,
    // with microseconds matching PostgreSQL precision; status restores original Timestamp fields.
    private static String projection(String table, String columns) {
        var fields =
                Arrays.stream(columns.split(","))
                        .map(
                                c ->
                                        Set.of(
                                                                "started_at",
                                                                "ended_at",
                                                                "lease_until",
                                                                "created_at")
                                                        .contains(c)
                                                ? "to_char("
                                                        + c
                                                        + " at time zone"
                                                        + " 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"
                                                        + " as "
                                                        + c
                                                : c)
                        .toList();
        return "select row_to_json(material)::text from (select "
                + String.join(",", fields)
                + " from engine."
                + table
                + " where id=?) material";
    }

    static String payloadDigest(JdbcTemplate db,String kind,long id){return digest(payload(db,kind,id));}
    private static JsonNode payload(JdbcTemplate db,String kind,long id){
        String column =
                switch (kind) {
                    case "run-snapshot" -> "snapshot";
                    case "run-inputs" -> "inputs";
                    case "run-version-refs" -> "version_refs";
                    default -> throw denied();
                };
        String raw =
                db.queryForObject(
                        "select " + column + "::text from engine.run where id=?", String.class, id);
        try{if(raw==null)throw denied();var node=EXACT.readTree(raw);object(node);return node;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw denied();}
    }
    private static String digest(JsonNode node){return Digest.sha256(normalize(node).toString());}
    private static JsonNode normalize(JsonNode node){
        if(node.isObject()){var result=JsonNodeFactory.instance.objectNode();var keys=new TreeSet<String>();node.fieldNames().forEachRemaining(keys::add);keys.forEach(k->result.set(k,normalize(node.get(k))));return result;}
        if(node.isArray()){var result=JsonNodeFactory.instance.arrayNode();node.forEach(n->result.add(normalize(n)));return result;}
        if(node.isNumber()){var value=node.decimalValue().stripTrailingZeros();if(value.signum()==0)return BigIntegerNode.valueOf(java.math.BigInteger.ZERO);return value.scale()<=0?BigIntegerNode.valueOf(value.toBigIntegerExact()):DecimalNode.valueOf(value);}
        return node;
    }
    static <T>T snapshot(DataSource source,Supplier<T> action){
        // Spring 6.2.7 normally silently joins REQUIRED. Inspect the bound connection explicitly:
        // accept RR or SERIALIZABLE, reject READ_COMMITTED, and never suspend an outer transaction.
        if(TransactionSynchronizationManager.isActualTransactionActive()){
            if (!TransactionSynchronizationManager.hasResource(source))
                throw new IllegalStateException("SAME_DATASOURCE_TRANSACTION_REQUIRED");
            var c=DataSourceUtils.getConnection(source);try{
                if (c.getAutoCommit()
                        || c.getTransactionIsolation() < Connection.TRANSACTION_REPEATABLE_READ)
                    throw new IllegalStateException("REPEATABLE_READ_REQUIRED");
            }
            catch(SQLException e){throw new IllegalStateException(e);}finally{DataSourceUtils.releaseConnection(c,source);}return action.get();
        }
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(status->action.get());
    }
}

