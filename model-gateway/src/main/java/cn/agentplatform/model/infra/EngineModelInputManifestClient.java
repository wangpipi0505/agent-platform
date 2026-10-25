package cn.agentplatform.model.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.model.domain.EngineModelInputManifest;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One configured TLS origin. No redirect, discovery, proxy, payload upload or authority minting.
 */
public final class EngineModelInputManifestClient implements AutoCloseable {
  private static final int MAX = 256 * 1024;
  private static final ObjectMapper EXACT =
      Json.mapper()
          .copy()
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

  static {
    EXACT.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    EXACT
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(256)
                .maxNameLength(256)
                .maxNumberLength(32)
                .build());
  }

  private final URI origin;
  private final HttpClient http;
  private final Set<CompletableFuture<?>> active = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  public EngineModelInputManifestClient(URI origin) {
    if (origin == null
        || !"https".equals(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
        || origin.getPort() == 0
        || origin.getPort() > 65535
        || origin.getRawAuthority().endsWith(":"))
      throw new IllegalArgumentException("ENGINE_ORIGIN_INVALID");
    try {
      this.origin = new URI("https", null, origin.getHost(), origin.getPort(), null, null, null);
    } catch (URISyntaxException invalid) {
      throw new IllegalArgumentException("ENGINE_ORIGIN_INVALID");
    }
    http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.of(null))
            .build();
  }

  public ManifestReply read(String id, String sha, String ticket) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new IllegalStateException("MODEL_INPUT_REQUIRES_NO_TRANSACTION");
    }
    if (id == null || !id.matches("m_[0-9a-f]{32}") || sha == null || !sha.matches("[0-9a-f]{64}"))
      throw badSelector();
    if (closed || ticket == null || ticket.isBlank() || ticket.length() > 128 * 1024)
      throw unavailable();
    String ref = "/internal/material-scopes/model-input/" + id + "?version=" + sha;
    long started = System.nanoTime();
    var body = new AtomicReference<BoundedBody>();
    CompletableFuture<HttpResponse<byte[]>> future = null;
    try {
      var request =
          HttpRequest.newBuilder(URI.create(origin.toASCIIString() + ref))
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + ticket)
              .header("Accept", "application/json")
              .GET()
              .build();
      future =
          http.sendAsync(
              request,
              info -> {
                if (info.statusCode() != 200) {
                  if (info.statusCode() >= 500) throw unavailable();
                  throw changed();
                }
                var enc = info.headers().allValues("Content-Encoding");
                if (!enc.isEmpty()) throw changed();
                var lengths = info.headers().allValues("Content-Length");
                if (lengths.size() > 1) throw changed();
                if (!lengths.isEmpty())
                  try {
                    long size = Long.parseLong(lengths.getFirst());
                    if (size < 0 || size > MAX) throw changed();
                  } catch (NumberFormatException invalid) {
                    throw changed();
                  }
                var bounded = new BoundedBody();
                body.set(bounded);
                return bounded;
              });
      active.add(future);
      if (closed) throw unavailable();
      long remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - started);
      if (remaining <= 0) throw new TimeoutException();
      var response = future.get(remaining, TimeUnit.NANOSECONDS);
      if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(5)) throw new TimeoutException();
      var headers = response.headers();
      String material = one(headers, "X-Agent-Material-Ref"),
          scopeSha = one(headers, "X-Agent-Material-Sha256");
      var reply = parse(response.body(), material, scopeSha);
      if (!ref.equals(material) || !reply.envelope().data().scope().scopeSha256().equals(scopeSha))
        throw changed();
      return reply;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      while (cause != null) {
        if (cause instanceof PlatformException p) throw p;
        cause = cause.getCause();
      }
      throw unavailable();
    } catch (TimeoutException | CancellationException invalid) {
      throw unavailable();
    } catch (IllegalArgumentException invalid) {
      throw unavailable();
    } finally {
      if (future != null) {
        if (!future.isDone()) future.cancel(true);
        active.remove(future);
      }
      var subscriber = body.get();
      if (subscriber != null) subscriber.cancel();
    }
  }

  private static String one(HttpHeaders headers, String name) {
    var values = headers.allValues(name);
    if (values.size() != 1) throw changed();
    return values.getFirst();
  }

  private static ManifestReply parse(byte[] bytes, String ref, String scopeSha) {
    try {
      if (bytes.length > MAX) throw changed();
      var decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      JsonNode envelope = EXACT.readTree(decoder.decode(ByteBuffer.wrap(bytes)).toString());
      fields(envelope, "code", "message", "data", "traceId");
      text(envelope.get("code"));
      if (!"OK".equals(envelope.get("code").textValue())
          || !envelope.get("message").isTextual()
          || !(envelope.get("traceId").isNull() || envelope.get("traceId").isTextual()))
        throw changed();
      JsonNode raw = envelope.get("data");
      fields(
          raw,
          "schemaVersion",
          "producer",
          "profile",
          "owner",
          "root",
          "runId",
          "acceptedSnapshotSha256",
          "requestSha256",
          "modelEntryId",
          "modelBindingRef",
          "material",
          "scope",
          "parents",
          "manifestSha256");
      number(raw.get("schemaVersion"));
      number(raw.get("modelEntryId"));
      for (String key :
          List.of(
              "producer",
              "profile",
              "runId",
              "acceptedSnapshotSha256",
              "requestSha256",
              "manifestSha256")) text(raw.get(key));
      owner(raw.get("owner"));
      fields(raw.get("root"), "kind", "id", "versionDigest");
      raw.get("root").forEach(EngineModelInputManifestClient::text);
      fields(raw.get("modelBindingRef"), "bindingId", "sha256");
      number(raw.get("modelBindingRef").get("bindingId"));
      hash(raw.get("modelBindingRef").get("sha256"));
      material(raw.get("material"));
      scope(raw.get("scope"));
      var parents = raw.get("parents");
      if (!parents.isArray() || parents.size() != 3) throw changed();
      parents.forEach(EngineModelInputManifestClient::scope);
      hash(raw.get("manifestSha256"));
      var unsigned = (ObjectNode) raw.deepCopy();
      unsigned.remove("manifestSha256");
      if (!Digest.sha256(Json.canonical(unsigned)).equals(raw.get("manifestSha256").textValue()))
        throw changed();
      if (Json.canonical(envelope).getBytes(StandardCharsets.UTF_8).length > MAX) throw changed();
      var dto = EXACT.treeToValue(raw, EngineModelInputManifest.class);
      return new ManifestReply(
          new ApiResponse<>("OK", "", dto, envelope.get("traceId").textValue()),
          raw,
          ref,
          scopeSha);
    } catch (PlatformException p) {
      throw p;
    } catch (Exception invalid) {
      throw changed();
    }
  }

  private static void fields(JsonNode n, String... keys) {
    if (n == null || !n.isObject() || n.size() != keys.length) throw changed();
    for (String key : keys) if (!n.has(key)) throw changed();
  }

  private static void text(JsonNode n) {
    if (n == null || !n.isTextual() || n.textValue().isBlank() || n.textValue().length() > 256)
      throw changed();
  }

  private static void number(JsonNode n) {
    if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.longValue() < 1)
      throw changed();
  }

  private static void hash(JsonNode n) {
    text(n);
    if (!n.textValue().matches("[0-9a-f]{64}")) throw changed();
  }

  private static void owner(JsonNode n) {
    fields(n, "tenantId", "principalId");
    n.forEach(EngineModelInputManifestClient::text);
  }

  private static void material(JsonNode n) {
    fields(n, "producer", "kind", "id", "version", "payloadSha256");
    n.forEach(EngineModelInputManifestClient::text);
    hash(n.get("payloadSha256"));
  }

  private static void unique(JsonNode n, int max) {
    if (n == null || !n.isArray() || n.size() > max) throw changed();
    var seen = new HashSet<String>();
    for (var x : n) {
      text(x);
      if (!seen.add(x.textValue())) throw changed();
    }
  }

  private static void scope(JsonNode n) {
    fields(
        n,
        "schemaVersion",
        "material",
        "owner",
        "sourceDecisionId",
        "sourceAuthorizationVersion",
        "initialCeilingSha256",
        "requiredRoles",
        "requiredEntitlements",
        "requiredDataLabels",
        "parentScopeSha256",
        "scopeSha256");
    number(n.get("schemaVersion"));
    material(n.get("material"));
    owner(n.get("owner"));
    text(n.get("sourceDecisionId"));
    text(n.get("sourceAuthorizationVersion"));
    hash(n.get("initialCeilingSha256"));
    hash(n.get("scopeSha256"));
    unique(n.get("requiredRoles"), 128);
    unique(n.get("requiredDataLabels"), 256);
    unique(n.get("parentScopeSha256"), 3);
    n.get("parentScopeSha256").forEach(EngineModelInputManifestClient::hash);
    var entitlements = n.get("requiredEntitlements");
    if (!entitlements.isObject() || entitlements.size() > 32) throw changed();
    entitlements
        .fields()
        .forEachRemaining(
            e -> {
              if (e.getKey().isBlank() || e.getKey().length() > 256) throw changed();
              unique(e.getValue(), 256);
            });
    try {
      var s = EXACT.treeToValue(n, MaterialScopeV1.class);
      if (!MaterialAuthorization.scopeDigest(s).equals(s.scopeSha256())) throw changed();
    } catch (PlatformException p) {
      throw p;
    } catch (Exception invalid) {
      throw changed();
    }
  }

  public record ManifestReply(
      ApiResponse<EngineModelInputManifest> envelope,
      JsonNode rawManifest,
      String materialRefHeader,
      String scopeShaHeader) {
    public ManifestReply {
      rawManifest = rawManifest == null ? null : rawManifest.deepCopy();
    }

    @Override
    public JsonNode rawManifest() {
      return rawManifest == null ? null : rawManifest.deepCopy();
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
    private Flow.Subscription subscription;

    public CompletionStage<byte[]> getBody() {
      return result;
    }

    public synchronized void onSubscribe(Flow.Subscription s) {
      if (subscription != null || result.isDone()) {
        s.cancel();
        return;
      }
      subscription = s;
      s.request(1);
    }

    public synchronized void onNext(List<ByteBuffer> chunks) {
      if (result.isDone()) return;
      long incoming = 0;
      for (var chunk : chunks) {
        incoming += chunk.remaining();
        if (incoming > MAX - bytes.size()) {
          result.completeExceptionally(changed());
          cancel();
          return;
        }
      }
      for (var chunk : chunks) {
        int count = chunk.remaining();
        if (chunk.hasArray()) {
          bytes.write(chunk.array(), chunk.arrayOffset() + chunk.position(), count);
          chunk.position(chunk.limit());
        } else {
          byte[] copy = new byte[count];
          chunk.get(copy);
          bytes.writeBytes(copy);
        }
      }
      subscription.request(1);
    }

    public synchronized void onError(Throwable error) {
      result.completeExceptionally(error);
      cancel();
    }

    public synchronized void onComplete() {
      result.complete(bytes.toByteArray());
    }

    synchronized void cancel() {
      if (subscription != null) subscription.cancel();
      if (!result.isDone()) result.completeExceptionally(unavailable());
    }
  }

  private static PlatformException changed() {
    return new PlatformException(403, "MATERIAL_SCOPE_MISSING_OR_CHANGED", "材料范围缺失或已改变");
  }

  private static PlatformException unavailable() {
    return new PlatformException(503, "MATERIAL_SOURCE_UNAVAILABLE", "材料来源暂不可用");
  }

  private static PlatformException badSelector() {
    return new PlatformException(400, "MATERIAL_SELECTOR_INVALID", "材料选择器无效");
  }

  @Override
  public void close() {
    closed = true;
    active.forEach(f -> f.cancel(true));
    http.shutdownNow();
    try {
      http.awaitTermination(Duration.ofSeconds(2));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
