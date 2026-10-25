package cn.agentplatform.model.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.model.application.InitialModelInputConsumerFixture;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class EngineModelInputManifestClientHttpTest {
  @Test
  void ambientTransactionDeniesBeforeMetadataHttp() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MODEL_INPUT_REQUIRES_NO_TRANSACTION");
        assertThat(f.gets).hasValue(0);
      } finally {
        org.springframework.transaction.support.TransactionSynchronizationManager
            .setActualTransactionActive(false);
      }
    }
  }

  static final List<Map<String, Object>> outcomes = new ArrayList<>();

  @AfterAll
  static void evidence() throws Exception {
    String output = System.getProperty("consumer.unit.evidence");
    if (output != null) {
      Files.createDirectories(Path.of(output));
      Files.writeString(Path.of(output, "http-outcomes.json"), Json.write(outcomes));
    }
  }

  @Test
  void finiteHttpsOriginOnly() {
    for (String s :
        List.of(
            "http://localhost",
            "https://user@localhost",
            "https://localhost/x",
            "https://localhost?x=1",
            "https://localhost#x",
            "https://localhost:0",
            "https://localhost:65536"))
      assertThatThrownBy(() -> new EngineModelInputManifestClient(URI.create(s)))
          .as(s)
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundedNormalTlsTypedManifest() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      var r = c.read(InitialModelInputConsumerFixture.ID, f.sha(), "fixture-ticket");
      assertThat(r.envelope().data().requestSha256()).isEqualTo(f.sha());
      assertThat(Digest.sha256(Json.canonical(r.rawManifest())))
          .isEqualTo(Digest.sha256(Json.canonical(f.manifest)));
      assertThat(f.gets).hasValue(1);
      ((com.fasterxml.jackson.databind.node.ObjectNode) r.rawManifest()).removeAll();
      assertThat(r.rawManifest().size()).isEqualTo(14);
      outcomes.add(
          Map.of("case", "boundedNormalTls", "gets", f.gets.get(), "normalChainAndHostname", true));
    }
  }

  @Test
  void strictBytesJsonFieldsTypesArraysAndHashes() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      String good = new String(f.response, StandardCharsets.UTF_8);
      for (byte[] bad :
          List.of(
              new byte[] {(byte) 0xc3, 0x28},
              (good + "{}").getBytes(StandardCharsets.UTF_8),
              good.replace("\"code\":\"OK\"", "\"code\":\"OK\",\"code\":\"OK\"")
                  .getBytes(StandardCharsets.UTF_8),
              good.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")
                  .getBytes(StandardCharsets.UTF_8),
              good.replace("\"schemaVersion\":1", "\"schemaVersion\":null")
                  .getBytes(StandardCharsets.UTF_8),
              good.replace(
                      "\"profile\":\"initial-chat-main-v1\"",
                      "\"profile\":\"" + "x".repeat(257) + "\"")
                  .getBytes(StandardCharsets.UTF_8),
              good.replace("\"READER\"", "\"EDITOR\"").getBytes(StandardCharsets.UTF_8),
              good.replace("\"producer\":\"engine\"", "\"producer\":\"tampered\"")
                  .getBytes(StandardCharsets.UTF_8),
              "{\"x\":"
                  .concat("[".repeat(34))
                  .concat("0")
                  .concat("]".repeat(34))
                  .concat("}")
                  .getBytes(StandardCharsets.UTF_8))) {
        f.response = bad;
        assertThatThrownBy(
                () -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "private-test-ticket"))
            .isInstanceOfSatisfying(
                PlatformException.class,
                e -> {
                  assertThat(e.status()).isEqualTo(403);
                  assertThat(e.getMessage()).doesNotContain("private-test-ticket", "tampered");
                });
      }
      f.rebuildManifest();
      ((com.fasterxml.jackson.databind.node.ObjectNode) f.manifest).put("unknown", 1);
      f.refreshResponse();
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOf(PlatformException.class);
    }
  }

  @Test
  void assertedHeadersAndContentEncodingMustMatchExactly() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      f.headerRef = f.ref() + "&extra=1";
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOf(PlatformException.class);
      f.headerRef = f.ref();
      f.headerSha = "0".repeat(64);
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOf(PlatformException.class);
      f.headerSha = f.manifest.path("scope").path("scopeSha256").asText();
      f.encoding = "gzip";
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOf(PlatformException.class);
      f.server.removeContext("/");
      f.server.createContext(
          "/",
          x -> {
            x.getResponseHeaders().add("X-Agent-Material-Ref", f.ref());
            x.getResponseHeaders().add("X-Agent-Material-Ref", f.ref());
            x.getResponseHeaders().set("X-Agent-Material-Sha256", f.headerSha);
            x.sendResponseHeaders(200, f.response.length);
            x.getResponseBody().write(f.response);
            x.close();
          });
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOf(PlatformException.class);
    }
  }

  @Test
  void sameAndDifferentOriginRedirectsNeverForwardBearer() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var destination = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      f.status = 302;
      for (String target :
          List.of(f.origin() + "/redirect-target", destination.origin() + "/redirect-target")) {
        f.location = target;
        int before = f.gets.get();
        assertThatThrownBy(
                () -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "private-test-ticket"))
            .isInstanceOf(PlatformException.class);
        assertThat(f.gets.get() - before).isEqualTo(1);
        assertThat(destination.gets).hasValue(0);
        outcomes.add(
            Map.of(
                "case",
                target.startsWith(f.origin().toString())
                    ? "sameOriginRedirect"
                    : "otherOriginRedirect",
                "sourceGets",
                f.gets.get() - before,
                "destinationGets",
                destination.gets.get()));
      }
    }
  }

  @Test
  void wrongHostAndUntrustedCaFailOrdinaryTls() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var wrong =
            new EngineModelInputManifestClient(
                URI.create(f.origin().toString().replace("localhost", "127.0.0.1")))) {
      assertThatThrownBy(() -> wrong.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOfSatisfying(
              PlatformException.class, e -> assertThat(e.status()).isEqualTo(503));
      var old = SSLContext.getDefault();
      var untrusted = SSLContext.getInstance("TLS");
      var store = java.security.KeyStore.getInstance("PKCS12");
      store.load(null, null);
      var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tm.init(store);
      untrusted.init(null, tm.getTrustManagers(), null);
      SSLContext.setDefault(untrusted);
      try (var c = new EngineModelInputManifestClient(f.origin())) {
        assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
            .isInstanceOfSatisfying(
                PlatformException.class, e -> assertThat(e.status()).isEqualTo(503));
      } finally {
        SSLContext.setDefault(old);
      }
      assertThat(f.gets).hasValue(0);
      outcomes.add(Map.of("case", "wrongHostAndUntrustedCa", "gets", 0, "status", 503));
    }
  }

  @Test
  void contentLengthAndChunkOverflowCancelBoundedSubscriber() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      f.server.removeContext("/");
      f.server.createContext(
          "/",
          x -> {
            try {
              x.sendResponseHeaders(200, 262145);
              x.getResponseBody().write(new byte[262145]);
            } catch (Exception ignored) {
            } finally {
              x.close();
            }
          });
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOfSatisfying(
              PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
      var canceled = new AtomicInteger();
      f.server.removeContext("/");
      f.server.createContext(
          "/",
          x -> {
            try {
              x.sendResponseHeaders(200, 0);
              for (int i = 0; i < 256; i++) {
                x.getResponseBody().write(new byte[8192]);
                x.getResponseBody().flush();
                Thread.sleep(2);
              }
            } catch (Exception expected) {
              canceled.incrementAndGet();
            } finally {
              x.close();
            }
          });
      assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
          .isInstanceOfSatisfying(
              PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (canceled.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
      assertThat(canceled).hasValue(1);
      outcomes.add(
          Map.of(
              "case",
              "chunkOverflow",
              "status",
              403,
              "serverObservedCancellation",
              canceled.get()));
    }
  }

  @Test
  void oneTotalDeadlineIncludesStalledHeadersAndTricklingBody() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      for (boolean headers : List.of(true, false)) {
        var stopped = new AtomicInteger();
        f.server.removeContext("/");
        f.server.createContext(
            "/",
            x -> {
              try {
                if (headers) Thread.sleep(6000);
                else {
                  x.sendResponseHeaders(200, 0);
                  for (int i = 0; i < 60; i++) {
                    x.getResponseBody().write(' ');
                    x.getResponseBody().flush();
                    Thread.sleep(200);
                  }
                }
              } catch (Exception expected) {
                stopped.incrementAndGet();
              } finally {
                x.close();
              }
            });
        long start = System.nanoTime();
        assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
            .isInstanceOfSatisfying(
                PlatformException.class, e -> assertThat(e.status()).isEqualTo(503));
        long elapsed = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsed).isBetween(4500L, 5700L);
        outcomes.add(
            Map.of(
                "case",
                headers ? "stalledHeaders" : "tricklingBody",
                "status",
                503,
                "elapsedMs",
                elapsed));
      }
    }
  }

  @Test
  void interruptionCancelsAndPreservesInterruptStatus() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      Thread.currentThread().interrupt();
      try {
        assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
            .isInstanceOf(PlatformException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void roleEntitlementLabelAndParentCountsAreCheckedBeforeTypedSets() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var c = new EngineModelInputManifestClient(f.origin())) {
      for (String which :
          List.of("roles", "labels", "entitlements", "parents", "missing", "mandatoryNull")) {
        f.rebuildManifest();
        var scope = (com.fasterxml.jackson.databind.node.ObjectNode) f.manifest.get("scope");
        switch (which) {
          case "roles" -> {
            var a = scope.putArray("requiredRoles");
            for (int i = 0; i < 129; i++) a.add("role" + i);
          }
          case "labels" -> {
            var a = scope.putArray("requiredDataLabels");
            for (int i = 0; i < 257; i++) a.add("label" + i);
          }
          case "entitlements" -> {
            var e = scope.putObject("requiredEntitlements");
            for (int i = 0; i < 33; i++) e.putArray("key" + i).add("value");
          }
          case "parents" ->
              f.manifest
                  .withArray("parents")
                  .add(f.manifest.withArray("parents").get(0).deepCopy());
          case "missing" -> f.manifest.remove("profile");
          default -> f.manifest.putNull("owner");
        }
        if (!which.equals("parents")) {
          var s = Json.read(scope.toString(), MaterialScopeV1.class);
          scope.put("scopeSha256", MaterialAuthorization.scopeDigest(s));
        }
        f.refreshResponse();
        assertThatThrownBy(() -> c.read(InitialModelInputConsumerFixture.ID, f.sha(), "t"))
            .as(which)
            .isInstanceOfSatisfying(
                PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
      }
    }
  }
}
