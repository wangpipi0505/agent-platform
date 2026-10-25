package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.model.domain.EngineModelInputManifest;
import cn.agentplatform.model.infra.EngineModelInputManifestClient;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Clock;
import java.util.*;

/**
 * Private initial-profile consumer. No provider, credential, tokenizer or business-store boundary.
 */
public final class InitialModelInputVerifier implements AutoCloseable {
  private static final int MAX_REQUEST = 2 * 1024 * 1024;
  // Integer semantics stay strict; floating tokens retain the producer canonical spelling below.
  private static final ObjectMapper REQUEST =
      Json.mapper()
          .copy()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  private static final ObjectMapper CLAIMS = REQUEST.copy();

  static {
    REQUEST.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    REQUEST
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(MAX_REQUEST)
                .maxNameLength(256)
                .maxNumberLength(256)
                .build());
    CLAIMS.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    CLAIMS
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(65536)
                .maxNameLength(256)
                .maxNumberLength(256)
                .build());
  }

  private final HttpCurrentAuthorityClient authority;
  private final EngineModelInputManifestClient transport;
  private final Clock clock;

  public InitialModelInputVerifier(HttpCurrentAuthorityClient authority, URI engineOrigin) {
    this(authority, engineOrigin, Clock.systemUTC());
  }

  InitialModelInputVerifier(HttpCurrentAuthorityClient authority, URI engineOrigin, Clock clock) {
    this.authority = Objects.requireNonNull(authority, "AUTHORITY_REQUIRED");
    this.clock = Objects.requireNonNull(clock);
    transport = new EngineModelInputManifestClient(engineOrigin);
  }

  public VerifiedInitialModelInput verify(
      byte[] acceptedRequestUtf8, String relativeMaterialRef, RetainedAuthorization received) {
    purpose(received);
    AuthorityContext.requireBound(received);
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new IllegalStateException("MODEL_INPUT_REQUIRES_NO_TRANSACTION");
    }
    var fresh =
        AuthorityContext.requireInitialFor(
            received.current().actor(), received.current().call().resource());
    purpose(fresh);
    // This canonical catalog assertion is finite; configured URLs cannot create new authority.
    var route =
        new ServiceRoutePolicy()
            .routes().stream()
                .filter(
                    r ->
                        "GET".equals(r.method())
                            && "/internal/material-scopes/{kind}/{id}".equals(r.template())
                            && "model".equals(r.caller())
                            && "engine".equals(r.target()))
                .toList();
    if (route.size() != 1
        || !"material.scope.read".equals(route.getFirst().action())
        || !"delegated".equals(route.getFirst().mode())
        || route.getFirst().integrationAllowed()) throw changed();
    if (relativeMaterialRef == null
        || !relativeMaterialRef.matches(
            "/internal/material-scopes/model-input/m_[0-9a-f]{32}\\?version=[0-9a-f]{64}"))
      throw bad();
    String id =
        relativeMaterialRef.substring(
            "/internal/material-scopes/model-input/".length(), relativeMaterialRef.indexOf('?'));
    String sha = relativeMaterialRef.substring(relativeMaterialRef.indexOf("?version=") + 9);
    var request = parseRequest(acceptedRequestUtf8);
    String canonical = Json.canonical(request);
    byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_REQUEST || !sha.equals(Digest.sha256(canonical))) throw bad();
    requestProfile(request, id, fresh.current().call().resource());
    // Only this concrete final implementation's already verified return can reach originOf.
    var ticket =
        authority.forward(
            fresh.current(),
            new ServiceCall("engine", "material.scope.read", fresh.current().call().resource()));
    same(ticket.operation(), fresh.current().operation());
    same(originOf(ticket), fresh.requireOriginAuthenticationId());
    fresh.requireVerified();
    var reply = transport.read(id, sha, ticket.value());
    var manifest = reply.envelope().data();
    binding(manifest, request, id, sha, fresh);
    fresh.requireVerified();
    // No transaction spans either HTTP operation. Refresh replaces the mapping; never re-bind old
    // proof.
    var finalMapping =
        AuthorityContext.requireInitialFor(
            fresh.current().actor(), fresh.current().call().resource());
    purpose(finalMapping);
    closure(manifest, request, finalMapping);
    AuthorityContext.requireBound(finalMapping);
    finalMapping.requireVerified();
    return new VerifiedInitialModelInput(bytes, request, manifest, finalMapping);
  }

  private static void purpose(RetainedAuthorization proof) {
    if (proof == null) throw changed();
    proof.requireVerified();
    var a = proof.current();
    var root = a.call().resource();
    var owner = new SubjectKey(a.actor().tenantId(), a.actor().principalId());
    if (!"engine".equals(a.caller())
        || !"model".equals(a.call().target())
        || !"model.chat-stream".equals(a.call().action())
        || !"app-version".equals(root.kind())
        || !"DELEGATED".equals(a.operation().sourceKind())
        || !"PORTAL".equals(a.operation().originKind())) throw changed();
    same(root, a.operation().resource());
    same(owner, a.operation().subject());
    same(
        owner,
        new SubjectKey(
            proof.requireInitialCeiling().tenantId(), proof.requireInitialCeiling().principalId()));
    proof.requireOriginAuthenticationId();
  }

  /**
   * Field extraction only: private input is the immediate concrete forward return (292–296).
   * HttpCurrentAuthorityClient.verifiedTicket (420–440) verifies raw and returns those same bytes;
   * forward compares OperationBinding before return. No public token/ServiceTicket entry exists
   * here.
   */
  private static String originOf(ServiceTicket internallyVerifiedTicket) {
    try {
      String[] segments = internallyVerifiedTicket.value().split("\\.", -1);
      if (segments.length != 3
          || segments[0].isEmpty()
          || segments[2].isEmpty()
          || segments[1].length() > 87384
          || !segments[1].matches("[A-Za-z0-9_-]+")) throw changed();
      byte[] bytes = Base64.getUrlDecoder().decode(segments[1]);
      if (bytes.length > 65536) throw changed();
      var claims = CLAIMS.readTree(utf8(bytes));
      if (!claims.isObject()) throw changed();
      var origin = claims.get("origin_authentication_id");
      if (origin == null
          || !origin.isTextual()
          || origin.textValue().isBlank()
          || origin.textValue().length() > 128) throw changed();
      return origin.textValue();
    } catch (PlatformException p) {
      throw p;
    } catch (Exception invalid) {
      throw changed();
    }
  }

  private static JsonNode parseRequest(byte[] source) {
    if (source == null || source.length == 0 || source.length > MAX_REQUEST) throw bad();
    byte[] copy = source.clone();
    try {
      String json = utf8(copy);
      var request = REQUEST.readTree(json);
      // The accepted producer can emit decimal 0.00001 or common Double 1.0E-5. Preserve
      // that signed numeric token rather than changing representation before the single SHA.
      // Both passes use the same strict lexer, duplicate/depth/size bounds and UTF-8 input.
      try (var parser = REQUEST.getFactory().createParser(json)) {
        while (parser.nextToken() != null) {
          if (parser.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) continue;
          var pointer = parser.getParsingContext().pathAsPointer();
          var parent = request.at(pointer.head());
          var value = new CanonicalFloat(parser.getText(), parser.getDecimalValue());
          if (parent.isObject())
            ((com.fasterxml.jackson.databind.node.ObjectNode) parent)
                .set(pointer.last().getMatchingProperty(), value);
          else if (parent.isArray())
            ((com.fasterxml.jackson.databind.node.ArrayNode) parent)
                .set(pointer.last().getMatchingIndex(), value);
          else throw bad();
        }
      }
      if (request == null
          || !request.isObject()
          || Json.canonical(request).getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST)
        throw bad();
      return request;
    } catch (Exception invalid) {
      throw bad();
    }
  }

  /** Immutable numeric value: only strict lexer output may enter its raw serialization. */
  private static final class CanonicalFloat
      extends com.fasterxml.jackson.databind.node.NumericNode {
    private final String token;
    private final com.fasterxml.jackson.databind.node.DecimalNode value;

    private CanonicalFloat(String token, java.math.BigDecimal decimal) {
      if (token.length() > 256) throw bad();
      this.token = token;
      this.value = new com.fasterxml.jackson.databind.node.DecimalNode(decimal);
    }

    @Override
    public JsonParser.NumberType numberType() {
      return JsonParser.NumberType.BIG_DECIMAL;
    }

    @Override
    public JsonToken asToken() {
      return JsonToken.VALUE_NUMBER_FLOAT;
    }

    @Override
    public boolean isFloatingPointNumber() {
      return true;
    }

    @Override
    public boolean isBigDecimal() {
      return true;
    }

    @Override
    public Number numberValue() {
      return value.numberValue();
    }

    @Override
    public short shortValue() {
      return value.shortValue();
    }

    @Override
    public int intValue() {
      return value.intValue();
    }

    @Override
    public long longValue() {
      return value.longValue();
    }

    @Override
    public float floatValue() {
      return value.floatValue();
    }

    @Override
    public double doubleValue() {
      return value.doubleValue();
    }

    @Override
    public java.math.BigDecimal decimalValue() {
      return value.decimalValue();
    }

    @Override
    public java.math.BigInteger bigIntegerValue() {
      return value.bigIntegerValue();
    }

    @Override
    public boolean canConvertToInt() {
      return value.canConvertToInt();
    }

    @Override
    public boolean canConvertToLong() {
      return value.canConvertToLong();
    }

    @Override
    public boolean canConvertToExactIntegral() {
      return value.canConvertToExactIntegral();
    }

    @Override
    public String asText() {
      return token;
    }

    @Override
    public void serialize(JsonGenerator generator, SerializerProvider provider)
        throws java.io.IOException {
      // writeNumber(String) is reparsed by valueToTree and loses the numeric spelling.
      // RawValue keeps this already lexer-validated number intact through Json.canonical.
      generator.writeRawValue(token);
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CanonicalFloat that && token.equals(that.token);
    }

    @Override
    public int hashCode() {
      return token.hashCode();
    }
  }

  private static String utf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void requestProfile(JsonNode n, String id, ResourceBinding root) {
    fields(
        n,
        "modelEntryId",
        "messages",
        "toolDefinitions",
        "params",
        "requiredLabels",
        "manifest",
        "appCode",
        "runId",
        "callKind",
        "modelBindingRef",
        "invocationRef");
    positive(n.get("modelEntryId"));
    sameText(n.get("callKind"), "FORMAL");
    sameText(n.get("invocationRef"), "material:" + id + ":MAIN");
    if (!text(n.get("runId")).matches("r_[0-9a-f]{32}")
        || !root.id().startsWith(text(n.get("appCode")) + "@")) throw bad();
    fields(n.get("modelBindingRef"), "bindingId", "sha256");
    positive(n.get("modelBindingRef").get("bindingId"));
    hash(n.get("modelBindingRef").get("sha256"));
    var messages = n.get("messages");
    if (!messages.isArray() || messages.size() != 2) throw bad();
    for (int i = 0; i < 2; i++) {
      fields(messages.get(i), "role", "content");
      sameText(messages.get(i).get("role"), i == 0 ? "system" : "user");
      if (!messages.get(i).get("content").isTextual()) throw bad();
    }
    if (!n.get("toolDefinitions").isArray() || !n.get("toolDefinitions").isEmpty()) throw bad();
    strings(n.get("requiredLabels"), 256);
    var diagnostic = n.get("manifest");
    fields(
        diagnostic,
        "stages",
        "currentUserIndex",
        "estimatedTokens",
        "tokenCountExact",
        "admission",
        "trimLog",
        "sources");
    if (!Json.canonical(diagnostic.get("stages"))
            .equals(
                Json.canonical(
                    List.of(
                        "PREPARE", "RENDER", "MESSAGES", "ACCOUNT", "TRIM", "VALIDATE", "FREEZE")))
        || !diagnostic.get("currentUserIndex").isIntegralNumber()
        || diagnostic.get("currentUserIndex").intValue() != 1
        || !diagnostic.get("estimatedTokens").isIntegralNumber()
        || diagnostic.get("estimatedTokens").longValue() < 0
        || !diagnostic.get("tokenCountExact").isBoolean()
        || diagnostic.get("tokenCountExact").booleanValue()
        || !diagnostic.get("trimLog").isArray()
        || !diagnostic.get("trimLog").isEmpty()
        || !Json.canonical(diagnostic.get("sources"))
            .equals(Json.canonical(List.of("frozenAppConfig", "inputs", "persistedHistory"))))
      throw bad();
    sameText(diagnostic.get("admission"), "MODEL_GATEWAY_CALIBRATED_TOKENIZER");
    var params = n.get("params");
    if (!params.isObject()) throw bad();
    var keys =
        Set.of(
            "maxOutputTokens",
            "temperature",
            "topP",
            "frequencyPenalty",
            "presencePenalty",
            "topK",
            "seed",
            "stop",
            "sampling",
            "output",
            "context",
            "timeout",
            "budgets");
    params
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!keys.contains(k)) throw bad();
            });
    if (positive(params.get("maxOutputTokens")) > Integer.MAX_VALUE) throw bad();
    for (String k : List.of("temperature", "topP", "frequencyPenalty", "presencePenalty"))
      if (params.has(k) && !params.get(k).isNumber()) throw bad();
    for (String k : List.of("topK", "seed"))
      if (params.has(k) && !params.get(k).isIntegralNumber()) throw bad();
    if (params.has("stop") && !params.get("stop").isTextual()) {
      if (!params.get("stop").isArray()) throw bad();
      for (var stop : params.get("stop")) if (!stop.isTextual()) throw bad();
    }
    for (String k : List.of("sampling", "output", "context", "timeout", "budgets"))
      if (params.has(k) && !params.get(k).isObject()) throw bad();
    rejectExternal(params);
  }

  private static void rejectExternal(JsonNode n) {
    if (n.isObject()) {
      var fields = n.fields();
      while (fields.hasNext()) {
        var f = fields.next();
        if (Set.of(
                "tools",
                "toolDefinitions",
                "dataSources",
                "history",
                "sessionHistory",
                "historyRefs",
                "materialRefs",
                "frozenPrompts",
                "modelOverride",
                "overrides",
                "summary",
                "summarizer",
                "retry",
                "compressedMaterials")
            .contains(f.getKey())) throw bad();
        rejectExternal(f.getValue());
      }
    } else if (n.isArray()) n.forEach(InitialModelInputVerifier::rejectExternal);
  }

  private static void binding(
      EngineModelInputManifest m, JsonNode n, String id, String sha, RetainedAuthorization proof) {
    same(1, m.schemaVersion());
    same("engine", m.producer());
    same("initial-chat-main-v1", m.profile());
    same(
        new SubjectKey(
            proof.requireInitialCeiling().tenantId(), proof.requireInitialCeiling().principalId()),
        m.owner());
    same(proof.current().call().resource(), m.root());
    same(n.get("runId").textValue(), m.runId());
    same(n.get("modelEntryId").longValue(), m.modelEntryId());
    same(n.get("modelBindingRef").get("bindingId").longValue(), m.modelBindingRef().bindingId());
    same(n.get("modelBindingRef").get("sha256").textValue(), m.modelBindingRef().sha256());
    same(sha, m.requestSha256());
    if (m.acceptedSnapshotSha256() == null || !m.acceptedSnapshotSha256().matches("[0-9a-f]{64}"))
      throw changed();
    same(new MaterialRef("engine", "model-input", id, sha, sha), m.material());
    same(m.material(), m.scope().material());
  }

  private void closure(EngineModelInputManifest m, JsonNode request, RetainedAuthorization proof) {
    String initialSha = MaterialAuthorization.ceilingSha256(proof.requireInitialCeiling());
    var owner = m.owner();
    var parents = new HashMap<String, MaterialScopeV1>();
    var hashes = new TreeSet<String>();
    var roles = new HashSet<>(proof.requireInitialCeiling().roles());
    var entitlements = new HashMap<String, Set<String>>();
    proof
        .requireInitialCeiling()
        .attributes()
        .forEach(
            (k, v) -> {
              if (!k.equals("dataLabels")) entitlements.put(k, new HashSet<>(v));
            });
    var labels =
        new HashSet<>(
            proof.requireInitialCeiling().attributes().getOrDefault("dataLabels", Set.of()));
    labels.addAll(proof.current().actor().attributes().getOrDefault("dataLabels", Set.of()));
    if (m.parents().size() != 3) throw changed();
    for (var p : m.parents()) {
      same(owner, p.owner());
      same(initialSha, p.initialCeilingSha256());
      same("engine", p.material().producer());
      same(m.runId(), p.material().id());
      same(m.acceptedSnapshotSha256(), p.material().version());
      if (!Set.of("run-snapshot", "run-inputs", "run-version-refs").contains(p.material().kind())
          || parents.put(p.material().kind(), p) != null
          || !hashes.add(p.scopeSha256())) throw changed();
      proof.requireVerified();
      MaterialAuthorization.require(p, proof.current(), clock);
      roles.addAll(p.requiredRoles());
      labels.addAll(p.requiredDataLabels());
      p.requiredEntitlements()
          .forEach((k, v) -> entitlements.computeIfAbsent(k, ignored -> new HashSet<>()).addAll(v));
    }
    same(List.of(), parents.get("run-snapshot").parentScopeSha256());
    same(List.of(), parents.get("run-inputs").parentScopeSha256());
    same(
        List.of(parents.get("run-snapshot").scopeSha256()),
        parents.get("run-version-refs").parentScopeSha256());
    var child = m.scope();
    same(owner, child.owner());
    same(initialSha, child.initialCeilingSha256());
    same(List.copyOf(hashes), child.parentScopeSha256());
    if (!child.requiredRoles().containsAll(roles)
        || !child.requiredDataLabels().containsAll(labels)) throw changed();
    entitlements.forEach(
        (k, v) -> {
          if (!child.requiredEntitlements().getOrDefault(k, Set.of()).containsAll(v))
            throw changed();
        });
    if (!strings(request.get("requiredLabels"), 256).containsAll(child.requiredDataLabels()))
      throw changed();
    proof.requireVerified();
    MaterialAuthorization.require(child, proof.current(), clock);
  }

  private static void fields(JsonNode n, String... keys) {
    if (n == null || !n.isObject() || n.size() != keys.length) throw bad();
    for (String key : keys) if (!n.hasNonNull(key)) throw bad();
  }

  private static String text(JsonNode n) {
    if (n == null || !n.isTextual() || n.textValue().isBlank() || n.textValue().length() > 256)
      throw bad();
    return n.textValue();
  }

  private static long positive(JsonNode n) {
    if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1)
      throw bad();
    return n.longValue();
  }

  private static void hash(JsonNode n) {
    if (!text(n).matches("[0-9a-f]{64}")) throw bad();
  }

  private static void sameText(JsonNode n, String expected) {
    if (!expected.equals(text(n))) throw bad();
  }

  private static Set<String> strings(JsonNode n, int max) {
    if (n == null || !n.isArray() || n.size() > max) throw bad();
    var values = new HashSet<String>();
    for (var v : n) if (!values.add(text(v))) throw bad();
    return values;
  }

  private static void same(Object expected, Object actual) {
    if (!Objects.equals(expected, actual)) throw changed();
  }

  private static PlatformException changed() {
    return new PlatformException(403, "MATERIAL_SCOPE_MISSING_OR_CHANGED", "材料范围缺失或已改变");
  }

  private static PlatformException bad() {
    return new PlatformException(400, "MODEL_INPUT_INVALID", "模型输入或材料选择器无效");
  }

  public static final class VerifiedInitialModelInput {
    private final byte[] canonical;
    private final JsonNode request;
    private final EngineModelInputManifest manifest;
    private final RetainedAuthorization mapping;

    private VerifiedInitialModelInput(
        byte[] canonical,
        JsonNode request,
        EngineModelInputManifest manifest,
        RetainedAuthorization mapping) {
      this.canonical = canonical.clone();
      this.request = request.deepCopy();
      this.manifest = manifest;
      this.mapping = mapping;
    }

    public void requireVerified() {
      mapping.requireVerified();
      AuthorityContext.requireBound(mapping);
    }

    public byte[] acceptedCanonicalRequestUtf8() {
      requireVerified();
      return canonical.clone();
    }

    public JsonNode request() {
      requireVerified();
      return request.deepCopy();
    }

    public EngineModelInputManifest manifest() {
      requireVerified();
      return manifest;
    }

    RetainedAuthorization authorityForBinding() {
      requireVerified();
      return mapping;
    }

    @Override
    public String toString() {
      return "VerifiedInitialModelInput[request-bound]";
    }
  }

  @Override
  public void close() {
    transport.close();
  }
}
