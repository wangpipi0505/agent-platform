package cn.agentplatform.model.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.HandlerMapping;

/** Finite, signed test authority and ordinary HTTPS. Never used by production construction. */
public final class InitialModelInputConsumerFixture implements AutoCloseable {
  public static final ResourceBinding ROOT =
      new ResourceBinding("app-version", "app-a@1", Digest.sha256("candidate"));
  public static final String ID = "m_" + "a".repeat(32), RUN = "r_" + "b".repeat(32);
  public final LoopbackAuthority iam = new LoopbackAuthority();
  public final HttpCurrentAuthorityClient gateway, engine, model;
  public final AtomicInteger gets = new AtomicInteger();
  public final HttpsServer server;
  public final ExecutorService workers = Executors.newCachedThreadPool();
  public volatile byte[] response;
  public volatile int status = 200;
  public volatile String headerRef, headerSha, location, encoding;
  public volatile Runnable onGet = () -> {};
  public final JsonNode request;
  public ObjectNode manifest;

  public InitialModelInputConsumerFixture() throws Exception {
    this(null);
  }

  public InitialModelInputConsumerFixture(String numericParams) throws Exception {
    iam.portalActor(
        new Actor(
            "tenant-a",
            "alice",
            Set.of("EDITOR", "READER"),
            Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("initial", "restricted"))));
    gateway = iam.client("gateway");
    engine = iam.client("engine");
    model = iam.client("model");
    request =
        Json.read(
            "{\"modelEntryId\":7,\"messages\":[{\"role\":\"system\",\"content\":\"Hello"
                + " Alice\"},{\"role\":\"user\",\"content\":\"sensitive"
                + " input\"}],\"toolDefinitions\":[],\"params\":{\"maxOutputTokens\":2048},\"requiredLabels\":[\"initial\",\"parent\",\"restricted\"],\"manifest\":{\"stages\":[\"PREPARE\",\"RENDER\",\"MESSAGES\",\"ACCOUNT\",\"TRIM\",\"VALIDATE\",\"FREEZE\"],\"currentUserIndex\":1,\"estimatedTokens\":30,\"tokenCountExact\":false,\"admission\":\"MODEL_GATEWAY_CALIBRATED_TOKENIZER\",\"trimLog\":[],\"sources\":[\"frozenAppConfig\",\"inputs\",\"persistedHistory\"]},\"appCode\":\"app-a\",\"runId\":\""
                + RUN
                + "\",\"callKind\":\"FORMAL\",\"modelBindingRef\":{\"bindingId\":13,\"sha256\":\""
                + Digest.sha256("binding")
                + "\"},\"invocationRef\":\"material:"
                + ID
                + ":MAIN\"}");
    if (numericParams != null) ((ObjectNode) request).set("params", Json.read(numericParams));
    rebuildManifest();
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverSsl()));
    server.setExecutor(workers);
    server.createContext(
        "/",
        x -> {
          gets.incrementAndGet();
          try {
            onGet.run();
            if (location != null) x.getResponseHeaders().set("Location", location);
            if (encoding != null) x.getResponseHeaders().set("Content-Encoding", encoding);
            if (headerRef != null) x.getResponseHeaders().set("X-Agent-Material-Ref", headerRef);
            if (headerSha != null) x.getResponseHeaders().set("X-Agent-Material-Sha256", headerSha);
            x.sendResponseHeaders(status, response.length);
            x.getResponseBody().write(response);
          } catch (Exception ignored) {
          } finally {
            x.close();
          }
        });
    server.start();
  }

  public static SSLContext serverSsl() throws Exception {
    var ks = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(Path.of(System.getProperty("consumer.tls")))) {
      ks.load(in, "fixture-only".toCharArray());
    }
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(ks, "fixture-only".toCharArray());
    var ssl = SSLContext.getInstance("TLS");
    ssl.init(km.getKeyManagers(), null, null);
    return ssl;
  }

  public URI origin() {
    return URI.create("https://localhost:" + server.getAddress().getPort());
  }

  public byte[] bytes() {
    return Json.canonical(request).getBytes(StandardCharsets.UTF_8);
  }

  public String sha() {
    return Digest.sha256(Json.canonical(request));
  }

  public String ref() {
    return "/internal/material-scopes/model-input/" + ID + "?version=" + sha();
  }

  public void rebuildManifest() {
    var actor =
        new Actor(
            "tenant-a",
            "alice",
            Set.of("EDITOR", "READER"),
            Map.of("region", Set.of("A", "B"), "dataLabels", Set.of("initial", "restricted")));
    String snap = Digest.sha256("accepted snapshot");
    var p1 =
        scope(
            new MaterialRef("engine", "run-snapshot", RUN, snap, Digest.sha256("snapshot")),
            actor,
            List.of());
    var p2 =
        scope(
            new MaterialRef("engine", "run-inputs", RUN, snap, Digest.sha256("inputs")),
            actor,
            List.of());
    var p3 =
        scope(
            new MaterialRef("engine", "run-version-refs", RUN, snap, Digest.sha256("refs")),
            actor,
            List.of(p1.scopeSha256()));
    var parents =
        List.of(p1, p2, p3).stream()
            .sorted(Comparator.comparing(MaterialScopeV1::scopeSha256))
            .toList();
    var material = new MaterialRef("engine", "model-input", ID, sha(), sha());
    var child = scope(material, actor, parents.stream().map(MaterialScopeV1::scopeSha256).toList());
    manifest = Json.object();
    manifest
        .put("schemaVersion", 1)
        .put("producer", "engine")
        .put("profile", "initial-chat-main-v1");
    manifest.set("owner", Json.tree(new SubjectKey("tenant-a", "alice")));
    manifest.set("root", Json.tree(ROOT));
    manifest
        .put("runId", RUN)
        .put("acceptedSnapshotSha256", snap)
        .put("requestSha256", sha())
        .put("modelEntryId", 7);
    manifest.set("modelBindingRef", request.path("modelBindingRef"));
    manifest.set("material", Json.tree(material));
    manifest.set("scope", Json.tree(child));
    manifest.set("parents", Json.tree(parents));
    refreshResponse();
  }

  static MaterialScopeV1 scope(MaterialRef ref, Actor actor, List<String> parents) {
    var s =
        new MaterialScopeV1(
            1,
            ref,
            new SubjectKey(actor.tenantId(), actor.principalId()),
            "historic-decision",
            "historic-version",
            MaterialAuthorization.ceilingSha256(actor),
            actor.roles(),
            Map.of("region", actor.attributes().get("region")),
            Set.of("initial", "restricted", "parent"),
            parents,
            "");
    return new MaterialScopeV1(
        1,
        ref,
        s.owner(),
        s.sourceDecisionId(),
        s.sourceAuthorizationVersion(),
        s.initialCeilingSha256(),
        s.requiredRoles(),
        s.requiredEntitlements(),
        s.requiredDataLabels(),
        parents,
        MaterialAuthorization.scopeDigest(s));
  }

  public void refreshResponse() {
    manifest.remove("manifestSha256");
    manifest.put("manifestSha256", Digest.sha256(Json.canonical(manifest)));
    headerRef = ref();
    headerSha = manifest.path("scope").path("scopeSha256").asText();
    response = Json.write(ApiResponse.ok(manifest)).getBytes(StandardCharsets.UTF_8);
  }

  public <T> T bound(Function<RetainedAuthorization, T> work) throws Exception {
    return bound("model.chat-stream", work);
  }

  public <T> T bound(String action, Function<RetainedAuthorization, T> work) throws Exception {
    var initial =
        gateway.exchange(
            iam.portal(), new ServiceCall("engine", "proxy.engine.post.RunController.run", ROOT));
    var inbound =
        engine.authorizeRetained(
            initial.value(),
            new ServiceCall("engine", "proxy.engine.post.RunController.run", ROOT));
    var call = new ServiceCall("model", action, ROOT);
    var child = engine.resume(inbound.current().delegation(), call);
    return received(model, iam.config("model"), iam.clock(), child.value(), action, work);
  }

  public static <T> T received(
      HttpCurrentAuthorityClient model,
      AuthorityProperties config,
      java.time.Clock clock,
      String token,
      String action,
      Function<RetainedAuthorization, T> work)
      throws Exception {
    try (var decoder = new TrustedJwtDecoder(config, clock, true)) {
      var req = new MockHttpServletRequest("POST", "/internal/model/chat/stream");
      var res = new MockHttpServletResponse();
      req.setAttribute(
          HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
          "model.measure".equals(action)
              ? "/internal/model/measure"
              : "/internal/model/chat/stream");
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
      SecurityContextHolder.getContext()
          .setAuthentication(new JwtAuthenticationToken(decoder.decode(token), List.of()));
      var guard = new ServiceAuthorization("model", new ServiceRoutePolicy(), model);
      try {
        guard.preHandle(req, res, new Object());
        return work.apply(
            AuthorityContext.requireInitialFor(
                AuthorityContext.current().actor(), AuthorityContext.current().call().resource()));
      } finally {
        guard.afterCompletion(req, res, new Object(), null);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
      }
    }
  }

  public void close() {
    server.stop(0);
    workers.shutdownNow();
    model.close();
    engine.close();
    gateway.close();
    iam.close();
    try {
      workers.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
