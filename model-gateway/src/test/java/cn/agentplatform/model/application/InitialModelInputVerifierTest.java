package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class InitialModelInputVerifierTest {
  @Test
  void ambientTransactionDeniesBeforeAuthorityRefreshOrMaterialHttp() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      f.bound(
          p -> {
            int calls = f.iam.authorityCalls();
            org.springframework.transaction.support.TransactionSynchronizationManager
                .setActualTransactionActive(true);
            try {
              assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessage("MODEL_INPUT_REQUIRES_NO_TRANSACTION");
              assertThat(f.iam.authorityCalls()).isEqualTo(calls);
              assertThat(f.gets).hasValue(0);
            } finally {
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .setActualTransactionActive(false);
            }
            return null;
          });
    }
  }

  @Test
  void realSignedBoundRequestProducesPrivateResultAndRetiresWithFrame() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      var proof =
          f.bound(
              p -> {
                var result = v.verify(f.bytes(), f.ref(), p);
                assertThat(Digest.sha256(Json.canonical(result.request()))).isEqualTo(f.sha());
                ((com.fasterxml.jackson.databind.node.ObjectNode) result.request()).removeAll();
                assertThat(Digest.sha256(Json.canonical(result.request()))).isEqualTo(f.sha());
                assertThat(
                        Digest.sha256(
                            new String(
                                result.acceptedCanonicalRequestUtf8(), StandardCharsets.UTF_8)))
                    .isEqualTo(f.sha());
                return result;
              });
      assertThat(f.gets).hasValue(1);
      assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);
    }
  }

  @Test
  void producerNumericCanonicalShaSurvivesStrictParsingAndMutationDeniesBeforeGet()
      throws Exception {
    for (String value : List.of("1.0", "0.0", "0.25", "1e-5")) {
      try (var f =
              new InitialModelInputConsumerFixture(
                  "{\"maxOutputTokens\":2048,\"temperature\":" + value + "}");
          var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
        f.bound(
            p -> {
              assertThatCode(
                      () -> {
                        var proof = v.verify(f.bytes(), f.ref(), p);
                        var number = proof.request().path("params").path("temperature");
                        var expected = new java.math.BigDecimal(value);
                        assertThat(number.isNumber()).isTrue();
                        assertThat(number.isFloatingPointNumber()).isTrue();
                        assertThat(number.isIntegralNumber()).isFalse();
                        assertThat(number.doubleValue()).isEqualTo(Double.parseDouble(value));
                        assertThat(number.asDouble()).isEqualTo(Double.parseDouble(value));
                        assertThat(number.floatValue()).isEqualTo(Float.parseFloat(value));
                        assertThat(number.decimalValue()).isEqualByComparingTo(expected);
                        assertThat(number.bigIntegerValue()).isEqualTo(expected.toBigInteger());
                        assertThat(number.shortValue()).isEqualTo(expected.shortValue());
                        assertThat(number.canConvertToExactIntegral())
                            .isEqualTo(expected.stripTrailingZeros().scale() <= 0);
                        assertThat(Digest.sha256(Json.canonical(proof.request())))
                            .as(value)
                            .isEqualTo(f.sha());
                        assertThat(
                                Digest.sha256(
                                    new String(
                                        proof.acceptedCanonicalRequestUtf8(),
                                        StandardCharsets.UTF_8)))
                            .as(value)
                            .isEqualTo(f.sha());
                      })
                  .as("accepted producer numeric value %s", value)
                  .doesNotThrowAnyException();
              return null;
            });
        assertThat(f.gets).hasValue(1);
        var changed = (ObjectNode) f.request.deepCopy();
        ((ObjectNode) changed.get("params")).put("temperature", 0.75);
        f.bound(
            p -> {
              assertThatThrownBy(
                      () ->
                          v.verify(
                              Json.write(changed).getBytes(StandardCharsets.UTF_8), f.ref(), p))
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(400));
              return null;
            });
        assertThat(f.gets).hasValue(1);
      }
    }
  }

  @Test
  void producerDecimalCanonicalAndCommonExponentRemainIndividuallyBound() throws Exception {
    for (String value : List.of("0.00001", "1E-7", "1.234567890123456789E-7")) {
      try (var f = new InitialModelInputConsumerFixture();
          var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
        ((ObjectNode) f.request.get("params"))
            .set(
                "temperature",
                new com.fasterxml.jackson.databind.node.DecimalNode(
                    new java.math.BigDecimal(value)));
        f.rebuildManifest();
        f.bound(
            p -> {
              assertThatCode(
                      () -> {
                        var proof = v.verify(f.bytes(), f.ref(), p);
                        assertThat(Digest.sha256(Json.canonical(proof.request())))
                            .isEqualTo(f.sha());
                        assertThat(proof.acceptedCanonicalRequestUtf8()).containsExactly(f.bytes());
                      })
                  .as("real producer decimal canonical %s", value)
                  .doesNotThrowAnyException();
              return null;
            });
        assertThat(f.gets).hasValue(1);
        var changed = (ObjectNode) f.request.deepCopy();
        ((ObjectNode) changed.get("params")).put("temperature", 0.75);
        f.bound(
            p -> {
              assertThatThrownBy(
                      () ->
                          v.verify(
                              Json.write(changed).getBytes(StandardCharsets.UTF_8), f.ref(), p))
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(400));
              return null;
            });
        assertThat(f.gets).hasValue(1);
      }
    }
  }

  @Test
  void wideFiniteDecimalRetainsFrozenParserSupport() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      var expected = new java.math.BigDecimal("1E+400");
      ((ObjectNode) f.request.get("params"))
          .set("temperature", new com.fasterxml.jackson.databind.node.DecimalNode(expected));
      f.rebuildManifest();
      f.bound(
          p -> {
            assertThatCode(
                    () -> {
                      var proof = v.verify(f.bytes(), f.ref(), p);
                      var n = proof.request().path("params").path("temperature");
                      assertThat(n.isNumber()).isTrue();
                      assertThat(n.isFloatingPointNumber()).isTrue();
                      assertThat(n.isIntegralNumber()).isFalse();
                      assertThat(n.decimalValue()).isEqualByComparingTo(expected);
                      assertThat(n.bigIntegerValue()).isEqualTo(expected.toBigInteger());
                      assertThat(n.canConvertToInt()).isFalse();
                      assertThat(n.canConvertToLong()).isFalse();
                      assertThat(proof.acceptedCanonicalRequestUtf8()).containsExactly(f.bytes());
                      assertThat(Digest.sha256(Json.canonical(proof.request()))).isEqualTo(f.sha());
                    })
                .doesNotThrowAnyException();
            return null;
          });
      assertThat(f.gets).hasValue(1);
    }
  }

  @Test
  void strictLexerRejectsNonJsonNumberInjectionAndOversizedTokenBeforeGet() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      for (String token :
          List.of(
              "NaN",
              "Infinity",
              "-Infinity",
              "1".repeat(257),
              "1.0,\"temperature\":0.0",
              "1.0} trailing")) {
        ((ObjectNode) f.request.get("params"))
            .set(
                "temperature",
                new com.fasterxml.jackson.databind.node.POJONode(
                    new com.fasterxml.jackson.databind.util.RawValue(token)));
        f.rebuildManifest();
        f.bound(
            p -> {
              assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(400));
              return null;
            });
      }
      assertThat(f.gets).hasValue(0);
    }
  }

  @Test
  void privateConstructorsAndNoPublicProofInputs() {
    assertThat(InitialModelInputVerifier.VerifiedInitialModelInput.class.getConstructors())
        .isEmpty();
    assertThat(InitialModelInputVerifier.class.getMethods())
        .filteredOn(m -> m.getName().equals("verify"))
        .allSatisfy(
            m ->
                assertThat(m.getParameterTypes())
                    .containsExactly(
                        byte[].class,
                        String.class,
                        HttpCurrentAuthorityClient.RetainedAuthorization.class));
  }

  @Test
  void noContextAndCurrentOnlyDenyBeforeGet() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      f.bound(
          p -> {
            assertThatThrownBy(
                    () ->
                        v.verify(
                            f.bytes(),
                            f.ref(),
                            HttpCurrentAuthorityClient.RetainedAuthorization.currentOnly(
                                p.current())))
                .isInstanceOf(PlatformException.class);
            return null;
          });
      assertThat(f.gets).hasValue(0);
    }
  }

  @Test
  void signedForwardWithChangedOrMissingOriginDeniesBeforeDataGet() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      for (boolean missing : List.of(false, true))
        f.bound(
            p -> {
              f.iam.ticketMutation(
                  c -> {
                    if (missing) c.remove("origin_authentication_id");
                    else c.put("origin_authentication_id", "changed-authentication");
                  });
              // Establish the concrete generic forward baseline: the independently signed ticket
              // passes.
              var generic =
                  f.model.forward(
                      p.current(),
                      new ServiceCall(
                          "engine", "material.scope.read", InitialModelInputConsumerFixture.ROOT));
              assertThat(generic.operation()).isEqualTo(p.current().operation());
              assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
              f.iam.ticketMutation(c -> {});
              return null;
            });
      assertThat(f.gets).hasValue(0);
    }
  }

  @Test
  void wrongMeasurePurposeDeniesBeforeDataGet() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      f.bound(
          "model.measure",
          p -> {
            assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                .isInstanceOf(PlatformException.class);
            return null;
          });
      assertThat(f.gets).hasValue(0);
    }
  }

  @Test
  void strictSelectorsAndFullCandidateHashDenyBeforeGet() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      for (String ref :
          List.of(
              "https://localhost" + f.ref(),
              "//localhost" + f.ref(),
              f.ref() + "&version=" + f.sha(),
              f.ref() + "&x=1",
              f.ref() + "#x",
              f.ref().replace("model-input", "%6dodel-input"),
              f.ref().replace("?", "/?"),
              f.ref().replace(f.sha(), "c".repeat(64))))
        f.bound(
            p -> {
              assertThatThrownBy(() -> v.verify(f.bytes(), ref, p))
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(400));
              return null;
            });
      for (String key :
          List.of(
              "manifest",
              "requiredLabels",
              "messages",
              "modelBindingRef",
              "runId",
              "invocationRef")) {
        var changed = (ObjectNode) f.request.deepCopy();
        if (key.equals("messages")) {
          var a = changed.withArray(key);
          var first = a.remove(0);
          a.add(first);
        } else if (key.equals("requiredLabels")) changed.withArray(key).add("new");
        else if (key.equals("manifest"))
          ((ObjectNode) changed.get(key)).put("estimatedTokens", 999);
        else if (key.equals("modelBindingRef"))
          ((ObjectNode) changed.get(key)).put("bindingId", 14);
        else changed.put(key, "changed");
        f.bound(
            p -> {
              assertThatThrownBy(
                      () ->
                          v.verify(
                              Json.write(changed).getBytes(StandardCharsets.UTF_8), f.ref(), p))
                  .isInstanceOf(PlatformException.class);
              return null;
            });
      }
      assertThat(f.gets).hasValue(0);
    }
  }

  @Test
  void reordersOnlyObjectKeysAndUsesExactLiveFinalMapping() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      var reversed = Json.object();
      var keys = new ArrayList<String>();
      f.request.fieldNames().forEachRemaining(keys::add);
      Collections.reverse(keys);
      keys.forEach(k -> reversed.set(k, f.request.get(k)));
      f.bound(
          p -> {
            var proof = v.verify(Json.write(reversed).getBytes(StandardCharsets.UTF_8), f.ref(), p);
            assertThat(Digest.sha256(Json.canonical(proof.request()))).isEqualTo(f.sha());
            try (var executor = Executors.newSingleThreadExecutor()) {
              assertThatThrownBy(() -> executor.submit(proof::requireVerified).get())
                  .hasCauseInstanceOf(PlatformException.class);
            }
            AuthorityContext.requireInitialFor(
                AuthorityContext.current().actor(), InitialModelInputConsumerFixture.ROOT);
            assertThatThrownBy(proof::requireVerified).isInstanceOf(PlatformException.class);
            return null;
          });
    }
  }

  @Test
  void closureOwnerRootInitialSnapshotAndUnionCannotBeChangedWithOnlyManifestRehash()
      throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      for (String mutation :
          List.of(
              "owner",
              "root",
              "acceptedSnapshotSha256",
              "requestSha256",
              "modelEntryId",
              "modelBindingRef",
              "parentDuplicate",
              "foreignParent",
              "parentVersion",
              "childUnderUnion",
              "legacyScope")) {
        f.rebuildManifest();
        switch (mutation) {
          case "owner" -> ((ObjectNode) f.manifest.get("owner")).put("principalId", "mallory");
          case "root" ->
              ((ObjectNode) f.manifest.get("root")).put("versionDigest", Digest.sha256("foreign"));
          case "modelEntryId" -> f.manifest.put("modelEntryId", 8);
          case "modelBindingRef" ->
              ((ObjectNode) f.manifest.get("modelBindingRef")).put("bindingId", 14);
          case "parentDuplicate" ->
              f.manifest
                  .withArray("parents")
                  .set(1, f.manifest.withArray("parents").get(0).deepCopy());
          case "foreignParent" -> {
            ((ObjectNode) f.manifest.withArray("parents").get(0).get("material"))
                .put("id", "r_" + "c".repeat(32));
            rehashScope((ObjectNode) f.manifest.withArray("parents").get(0));
          }
          case "parentVersion" -> {
            ((ObjectNode) f.manifest.withArray("parents").get(0).get("material"))
                .put("version", Digest.sha256("foreign"));
            rehashScope((ObjectNode) f.manifest.withArray("parents").get(0));
          }
          case "childUnderUnion" -> {
            ((ObjectNode) f.manifest.get("scope")).putArray("requiredRoles").add("EDITOR");
            rehashScope((ObjectNode) f.manifest.get("scope"));
          }
          case "legacyScope" -> f.manifest.remove("scope");
          default -> f.manifest.put(mutation, Digest.sha256("foreign"));
        }
        f.refreshResponse();
        f.bound(
            p -> {
              assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                  .as(mutation)
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
              return null;
            });
      }
    }
  }

  static void rehashScope(ObjectNode n) {
    var s = Json.read(n.toString(), MaterialScopeV1.class);
    n.put("scopeSha256", MaterialAuthorization.scopeDigest(s));
  }

  @Test
  void currentNarrowingRevocationOutageAndExpiryDuringTransportReturnNoResult() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      for (String mode : List.of("narrow", "revoke", "unavailable")) {
        f.onGet =
            () -> {
              switch (mode) {
                case "narrow" ->
                    f.iam.decisionMutation(
                        c -> {
                          if (c.containsKey("attributes"))
                            c.put(
                                "attributes",
                                Map.of(
                                    "region",
                                    Set.of("A"),
                                    "dataLabels",
                                    Set.of("initial", "restricted")));
                        });
                case "revoke" -> f.iam.revoked(true);
                default -> f.iam.unavailable(true);
              }
            };
        f.bound(
            p -> {
              assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                  .isInstanceOfSatisfying(
                      PlatformException.class,
                      e ->
                          assertThat(e.status()).isEqualTo(mode.equals("unavailable") ? 503 : 403));
              return null;
            });
        f.iam.decisionMutation(c -> {});
        f.iam.revoked(false);
        f.iam.unavailable(false);
      }
      f.onGet = () -> {};
      f.bound(
          p -> {
            assertThat(v.verify(f.bytes(), f.ref(), p)).isNotNull();
            return null;
          });
      f.onGet = () -> f.iam.advanceClock(java.time.Duration.ofSeconds(61));
      f.bound(
          p -> {
            assertThatThrownBy(() -> v.verify(f.bytes(), f.ref(), p))
                .isInstanceOf(PlatformException.class);
            return null;
          });
      assertThat(f.iam.observedOperations())
          .doesNotContain(
              "response_open", "response_check", "portal_events_open", "portal_events_check");
    }
  }

  @Test
  void permissionArrayOrderMayDifferFromRawManifestHashAndRetainScopeDigest() throws Exception {
    try (var f = new InitialModelInputConsumerFixture();
        var v = new InitialModelInputVerifier(f.model, f.origin(), f.iam.clock())) {
      var nodes = new ArrayList<JsonNode>();
      nodes.add(f.manifest.get("scope"));
      f.manifest.get("parents").forEach(nodes::add);
      for (var node : nodes) {
        var a = (com.fasterxml.jackson.databind.node.ArrayNode) node.get("requiredRoles");
        var first = a.remove(0);
        a.add(first);
      }
      f.refreshResponse();
      f.bound(
          p -> {
            assertThat(v.verify(f.bytes(), f.ref(), p)).isNotNull();
            return null;
          });
    }
  }

  @Test
  void missingConfigurationAndDefaultIamGuardRemainClosed() {
    assertThatThrownBy(() -> new InitialModelInputVerifier(null, URI.create("https://localhost")))
        .isInstanceOf(NullPointerException.class);
    var props = new LinkedHashMap<String, Object>();
    props.put("agent.security.mode", "iam");
    props.put("agent.security.service-id", "model");
    props.put("server.address", "127.0.0.1");
    props.put("agent.security.authority.client-id", "model");
    props.put("agent.security.authority.issuer", "https://authority.invalid");
    props.put("agent.security.authority.jwks-uri", "https://authority.invalid/jwks");
    props.put("agent.security.authority.token-uri", "https://authority.invalid/token");
    props.put("agent.security.authority.decision-uri", "https://authority.invalid/decision");
    props.put("agent.security.authority.client-secret-ref", "env:AGENT_SECRET_IAM_MODEL_CLIENT");
    props.put("agent.security.authority.allowed-algorithm", "RS256");
    props.put("agent.security.authority.audience", "urn:agent-platform:service:model");
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new org.springframework.core.env.MapPropertySource("closed-model-iam", props));
      context.register(cn.agentplatform.common.SecurityConfiguration.class);
      assertThatThrownBy(context::refresh).hasStackTraceContaining("IAM_TRUST_CHAIN_UNAVAILABLE");
    }
  }
}
