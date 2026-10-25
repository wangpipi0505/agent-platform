package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.node.*;
import com.knuddels.jtokkit.Encodings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

@Timeout(30)
class FrozenInitialInputMeterTest {
  static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(PlatformException.class, e -> {
      assertThat(e.status()).isEqualTo(status); assertThat(e.code()).isEqualTo(code);
    });
  }
  @Test void extractedHelperKeepsPinnedLocalCountsAndOriginalUnknownError() {
    for (String text : List.of("Hello world", "中文与嵌套{\"a\":[1,2]}", "{\"temperature\":0.00001}"))
      assertThat(ModelCalls.countLocalTokens("gpt-4o-mini", text)).isEqualTo(
          Encodings.newDefaultEncodingRegistry().getEncodingForModel("gpt-4o-mini").orElseThrow().countTokens(text) + 64);
    assertThatThrownBy(() -> ModelCalls.countLocalTokens("unsupported-fixture-model", "x"))
        .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage("该模型尚未配置专用tokenizer，请先完成模型计量校准");
  }
  @Test void originalBytesPositiveThenOneAsciiByteChangeMustDeny() throws Exception {
    try (var f = new FrozenInitialInputMeterFixture()) {
      f.accepted((proof, bytes) -> {
        var result = FrozenInitialInputMeter.measure(proof, bytes);
        assertThat(result.requestSha256()).isEqualTo(proof.requestSha256());
        byte[] changed = bytes.clone(); changed[1] = changed[1] == 'x' ? (byte)'y' : (byte)'x';
        denied(() -> FrozenInitialInputMeter.measure(proof, changed), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED");
        return null;
      });
    }
  }
  @Test void explicitRemoteTokenizerMustDenySupportedLocalModel() throws Exception {
    try (var local = new FrozenInitialInputMeterFixture(); var remote = new FrozenInitialInputMeterFixture("gpt-4o-mini", true)) {
      local.accepted((proof, bytes) -> FrozenInitialInputMeter.measure(proof, bytes));
      remote.accepted((proof, bytes) -> {
        denied(() -> FrozenInitialInputMeter.measure(proof, bytes), 409, "MODEL_TOKENIZER_REQUIRES_EGRESS"); return null;
      });
    }
  }
  @Test void originalScientificCjkNestedFormatAndCallerMutation() throws Exception {
    try (var f = new FrozenInitialInputMeterFixture()) {
      ((ObjectNode)f.setup.consumer.request.path("messages").get(1)).put("content", "中文，原始输入");
      var params = (ObjectNode)f.setup.consumer.request.path("params");
      params.set("temperature", new DecimalNode(new java.math.BigDecimal("0.00001")));
      params.putObject("output").put("maxTokens", 128).putObject("format").put("type", "json").putObject("schema").put("type", "object");
      f.rebuild();
      f.accepted((proof, bytes) -> {
        String text = new String(bytes, StandardCharsets.UTF_8); assertThat(text).contains("0.00001", "中文", "schema");
        var result = FrozenInitialInputMeter.measure(proof, bytes);
        assertThat(result.inputTokens()).isEqualTo(ModelCalls.countLocalTokens("gpt-4o-mini", text));
        assertThat(result.bindingId()).isEqualTo(f.selected.path("bindingId").longValue());
        assertThat(result.modelEntryId()).isEqualTo(proof.modelEntryId());
        assertThat(result.bindingSha256()).isEqualTo(proof.bindingSha256());
        assertThat(result.method()).isEqualTo("JTOKKIT_WITH_CONSERVATIVE_STRUCTURE");
        assertThat(result.basis()).isEqualTo("ACCEPTED_CANONICAL_INPUT"); assertThat(result.exact()).isFalse();
        denied(() -> FrozenInitialInputMeter.measure(proof, text.replace("0.00001", "1.0E-5").getBytes(StandardCharsets.UTF_8)), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED");
        denied(() -> FrozenInitialInputMeter.measure(proof, Json.canonical(Json.read(text)).getBytes(StandardCharsets.UTF_8)), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED");
        bytes[1] ^= 1;
        assertThat(result.inputTokens()).isEqualTo(ModelCalls.countLocalTokens("gpt-4o-mini", text));
        denied(() -> FrozenInitialInputMeter.measure(proof, bytes), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED"); return null;
      });
    }
  }
  @Test void boundsAndUnknownFailClosed() throws Exception {
    denied(() -> FrozenInitialInputMeter.measure(null, null), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED");
    try (var f = new FrozenInitialInputMeterFixture()) {
      f.accepted((proof, bytes) -> {
        for (byte[] value : Arrays.asList(null, new byte[0], new byte[2097153]))
          denied(() -> FrozenInitialInputMeter.measure(proof, value), 400, "MODEL_INPUT_INVALID");
        denied(() -> FrozenInitialInputMeter.measure(proof, new byte[2097152]), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED");
        return null;
      });
    }
    try (var f = new FrozenInitialInputMeterFixture("unsupported-fixture-model", false)) {
      f.accepted((proof, bytes) -> { denied(() -> FrozenInitialInputMeter.measure(proof, bytes), 409, "MODEL_TOKENIZER_UNSUPPORTED"); return null; });
    }
  }
  @Test void bytesFromAnotherGenuinelyAcceptedRequestCannotUseThisProof() throws Exception {
    try (var first = new FrozenInitialInputMeterFixture(); var second = new FrozenInitialInputMeterFixture()) {
      ((ObjectNode)second.setup.consumer.request.path("messages").get(1)).put("content", "another accepted request");
      second.rebuild();
      byte[] other = second.accepted((proof, bytes) -> { assertThat(FrozenInitialInputMeter.measure(proof, bytes)).isNotNull(); return bytes; });
      first.accepted((proof, bytes) -> { assertThat(FrozenInitialInputMeter.measure(proof, bytes)).isNotNull();
        denied(() -> FrozenInitialInputMeter.measure(proof, other), 403, "MATERIAL_SCOPE_MISSING_OR_CHANGED"); return null; });
    }
  }
  @Test void mappingFrameThreadExpiryInterruptAndAmbientLocalTransaction() throws Exception {
    try (var f = new FrozenInitialInputMeterFixture()) {
      var retained = f.accepted((proof, bytes) -> {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThat(FrozenInitialInputMeter.measure(proof, bytes)).isNotNull(); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
        Thread.currentThread().interrupt();
        try { denied(() -> FrozenInitialInputMeter.measure(proof, bytes), 503, "SYS-MODEL-503"); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        try (var workers = Executors.newSingleThreadExecutor()) {
          assertThatThrownBy(() -> workers.submit(() -> FrozenInitialInputMeter.measure(proof, bytes)).get()).hasCauseInstanceOf(PlatformException.class);
        }
        try (var different = AuthorityContext.bind(AuthorityContext.current())) {
          assertThatThrownBy(() -> FrozenInitialInputMeter.measure(proof, bytes)).isInstanceOf(PlatformException.class);
        }
        AuthorityContext.requireInitialFor(AuthorityContext.current().actor(), AuthorityContext.current().call().resource());
        assertThatThrownBy(() -> FrozenInitialInputMeter.measure(proof, bytes)).isInstanceOf(PlatformException.class);
        return Map.entry(proof, bytes);
      });
      assertThatThrownBy(() -> FrozenInitialInputMeter.measure(retained.getKey(), retained.getValue())).isInstanceOf(PlatformException.class);
      f.accepted((proof, bytes) -> { assertThat(FrozenInitialInputMeter.measure(proof, bytes)).isNotNull(); return null; });
      f.accepted((proof, bytes) -> { f.setup.consumer.iam.advanceClock(Duration.ofSeconds(61));
        denied(() -> FrozenInitialInputMeter.measure(proof, bytes), 403, "AUTHORITY_EXPIRED"); return null; });
    }
  }
  @Test void activeEndpointControlsThenMeterWindowHasNoHttpIamSqlOrBusinessChanges() throws Exception {
    try (var f = new FrozenInitialInputMeterFixture()) {
      try (var http = java.net.http.HttpClient.newHttpClient()) {
        for (String path : List.of("/provider", "/tokenizer"))
          assertThat(http.send(java.net.http.HttpRequest.newBuilder(f.setup.consumer.origin().resolve(path)).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
      }
      assertThat(f.setup.providerHttp).hasValue(1); assertThat(f.setup.tokenizerHttp).hasValue(1);
      f.setup.providerHttp.set(0); f.setup.tokenizerHttp.set(0);
      f.accepted((proof, bytes) -> {
        var before = business(f); int iam = f.setup.consumer.iam.authorityCalls(); f.setup.ds.reset();
        assertThat(FrozenInitialInputMeter.measure(proof, bytes)).isNotNull();
        assertThat(f.setup.ds.sql).isEmpty(); assertThat(f.setup.consumer.iam.authorityCalls()).isEqualTo(iam);
        assertThat(business(f)).isEqualTo(before); assertThat(f.setup.providerHttp).hasValue(0); assertThat(f.setup.tokenizerHttp).hasValue(0);
        try { java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("binding.fixture")).resolve("local-meter-window.json"),
            Json.write(Map.of("requestSha256", proof.requestSha256(), "bindingId", proof.bindingId(), "modelEntryId", proof.modelEntryId(),
                "bindingSha256", proof.bindingSha256(), "providerProbe", 1, "tokenizerProbe", 1, "providerMeterHttp", 0, "tokenizerMeterHttp", 0,
                "businessBeforeAfterEqual", true, "businessContentSha256", Digest.sha256(Json.write(before))))); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        return null;
      });
    }
  }
  static List<Object> business(FrozenInitialInputMeterFixture f) {
    return List.of("provider", "entry", "binding_snapshot", "call_log", "quota_reservation", "metering_calibration").stream()
        .map(t -> (Object) f.setup.db.queryForObject("select coalesce(jsonb_agg(to_jsonb(x) order by to_jsonb(x)::text),'[]'::jsonb)::text from model." + t + " x", String.class)).toList();
  }
}
