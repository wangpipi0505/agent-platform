package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.model.infra.EngineModelInputManifestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Real engine JVM/PG positive; absence of explicit ready inputs is an error, never a skip. */
class InitialModelInputConsumerHttpTest {
  static Path dir;
  static JsonNode ready;
  static HttpCurrentAuthorityClient authority;
  static AuthorityProperties config;
  static Clock clock;
  static InitialModelInputVerifier verifier;
  static byte[] request;
  static final List<Map<String, Object>> observations = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    String configured = System.getProperty("consumer.fixture");
    assertThat(configured).as("explicit ready fixture required").isNotBlank();
    dir = Path.of(configured);
    ready = Json.read(Files.readString(dir.resolve("ready.json")));
    assertThat(ready.path("enginePid").longValue()).isNotEqualTo(ProcessHandle.current().pid());
    config =
        Json.read(Files.readString(dir.resolve("model-config.json")), AuthorityProperties.class);
    clock = Clock.fixed(Instant.parse(ready.path("clockInstant").asText()), ZoneOffset.UTC);
    assertThat(Digest.sha256(System.getenv("AGENT_SECRET_IAM_MODEL_CLIENT")))
        .isEqualTo(Digest.sha256(Files.readString(dir.resolve("model-secret.txt"))));
    // The public resolver reads only the fixed model environment reference. Its endpoint fields
    // are constructor validation data, never used for HTTP; the actual signed client uses config.
    var a = config.authority();
    var credentialConfig =
        new AuthorityProperties(
            "iam",
            "model",
            null,
            new AuthorityProperties.Authority(
                "https://fixture.invalid",
                "https://fixture.invalid/jwks",
                "https://fixture.invalid/token",
                "https://fixture.invalid/decision",
                a.clientId(),
                a.clientSecretRef(),
                a.allowedAlgorithm(),
                a.audience(),
                a.maxTicketSeconds(),
                a.maxDecisionSeconds(),
                a.connectTimeoutMs(),
                a.readTimeoutMs()),
            null,
            null,
            null,
            false,
            null);
    authority =
        new HttpCurrentAuthorityClient(
            config, clock, new WorkloadCredentialResolver(credentialConfig), true);
    verifier =
        new InitialModelInputVerifier(
            authority, URI.create(ready.path("engineOrigin").asText()), clock);
    request = Files.readAllBytes(dir.resolve("request.json"));
  }

  @AfterAll
  static void close() throws Exception {
    if (verifier != null) verifier.close();
    if (authority != null) authority.close();
    if (dir != null)
      Files.writeString(
          dir.resolve("model-results.json"),
          Json.write(
              Map.of(
                  "modelPid",
                  ProcessHandle.current().pid(),
                  "enginePid",
                  ready.path("enginePid").longValue(),
                  "observations",
                  observations,
                  "modelDatasourceConfigured",
                  false,
                  "structuralBoundaryEvidence",
                  "Verifier collaborators are authority and metadata HTTP only; no dynamic"
                      + " provider, tokenizer, measure, SecretVault or response-lease"
                      + " instrumentation",
                  "resourcesClosed",
                  true)));
  }

  static <T> T received(
      String ticket,
      java.util.function.Function<HttpCurrentAuthorityClient.RetainedAuthorization, T> work)
      throws Exception {
    return InitialModelInputConsumerFixture.received(
        authority,
        config,
        clock,
        Files.readString(dir.resolve(ticket + "-ticket.txt")),
        "model.chat-stream",
        work);
  }

  @Test
  void actualProducerClosureRepeatedAuthorizationAndNewGrantOperationSucceed() throws Exception {
    for (String ticket : List.of("first", "repeat", "newGrant"))
      received(
          ticket,
          p -> {
            var proof = verifier.verify(request, ready.path("materialRef").asText(), p);
            assertThat(Digest.sha256(Json.canonical(proof.request())))
                .isEqualTo(ready.path("requestSha256").asText());
            assertThat(
                    Digest.sha256(
                        new String(
                            proof.acceptedCanonicalRequestUtf8(),
                            java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(ready.path("requestSha256").asText());
            var params = proof.request().path("params");
            assertThat(params.path("temperature").doubleValue()).isEqualTo(1.0);
            assertThat(params.path("topP").doubleValue()).isEqualTo(0.25);
            assertThat(params.path("frequencyPenalty").doubleValue()).isEqualTo(0.0);
            assertThat(params.path("presencePenalty").doubleValue()).isEqualTo(1e-5);
            var manifest = proof.manifest();
            assertThat(manifest.requestSha256()).isEqualTo(ready.path("requestSha256").asText());
            assertThat(manifest.manifestSha256()).isEqualTo(ready.path("manifestSha256").asText());
            assertThat(manifest.scope().initialCeilingSha256())
                .isEqualTo(ready.path("initialCeilingSha256").asText());
            assertThat(manifest.parents()).hasSize(3);
            assertThat(p.current().operation().operationId())
                .isNotEqualTo(ready.path("historicalOperationId").asText());
            assertThat(manifest.scope().sourceDecisionId()).isNotEqualTo(p.current().decisionId());
            observations.add(
                Map.of(
                    "case",
                    ticket,
                    "result",
                    "verified",
                    "currentOperationDiffersFromHistorical",
                    true,
                    "owner",
                    manifest.owner(),
                    "requestSha",
                    manifest.requestSha256(),
                    "scopeSha",
                    manifest.scope().scopeSha256()));
            return null;
          });
  }

  @Test
  void realProducerNumericMutationDeniesBeforeMetadataGet() throws Exception {
    String original = new String(request, java.nio.charset.StandardCharsets.UTF_8);
    var token = java.util.regex.Pattern.compile("\"temperature\":1(?=[,}])").matcher(original);
    assertThat(token.find()).as("one actual producer temperature token").isTrue();
    int start = token.start(), end = token.end();
    assertThat(token.find()).as("unique temperature token").isFalse();
    String changed =
        original.substring(0, start) + "\"temperature\":0.75" + original.substring(end);
    assertThat(changed.substring(0, start).equals(original.substring(0, start))).isTrue();
    assertThat(
            changed
                .substring(start + "\"temperature\":0.75".length())
                .equals(original.substring(end)))
        .isTrue();
    received(
        "first",
        p -> {
          assertThatThrownBy(
                  () ->
                      verifier.verify(
                          changed.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          ready.path("materialRef").asText(),
                          p))
              .isInstanceOfSatisfying(
                  PlatformException.class, e -> assertThat(e.status()).isEqualTo(400));
          observations.add(
              Map.of("case", "numericOneFieldMutation", "result", "400 request SHA before GET"));
          return null;
        });
  }

  @Test
  void realPgMissingManifestLegacyScopeWrongOwnerRootAndOtherOriginDenyAtProducer()
      throws Exception {
    for (String ticket : List.of("wrongOwner", "wrongRoot", "differentOrigin"))
      received(
          ticket,
          p -> {
            assertThatThrownBy(
                    () -> verifier.verify(request, ready.path("materialRef").asText(), p))
                .isInstanceOfSatisfying(
                    PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
            observations.add(
                Map.of("case", ticket, "result", "403 producer metadata authorization"));
            return null;
          });
    try (var transport =
        new EngineModelInputManifestClient(URI.create(ready.path("engineOrigin").asText()))) {
      for (String id : List.of("m_" + "f".repeat(32), ready.path("legacyMaterialId").asText()))
        received(
            "first",
            p -> {
              var ticket =
                  authority.forward(
                      p.current(),
                      new ServiceCall(
                          "engine", "material.scope.read", p.current().call().resource()));
              assertThatThrownBy(
                      () ->
                          transport.read(id, ready.path("requestSha256").asText(), ticket.value()))
                  .isInstanceOfSatisfying(
                      PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
              observations.add(
                  Map.of(
                      "case",
                      id.equals(ready.path("legacyMaterialId").asText())
                          ? "legacyScopes"
                          : "missingManifest",
                      "result",
                      "403 producer PG metadata"));
              return null;
            });
    }
  }
}
