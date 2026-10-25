package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.*;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Genuine accepted fixture composition; offline encoders have no network delegate or response. */
final class FrozenInitialPromptFixture implements AutoCloseable {
  final FrozenInitialInputMeterFixture accepted = new FrozenInitialInputMeterFixture();
  FrozenInitialPromptFixture() throws Exception {}
  ObjectNode params() { return (ObjectNode) accepted.setup.consumer.request.path("params"); }
  void rebuild() { accepted.rebuild(); }
  <T> T with(java.util.function.BiFunction<FrozenModelBindingVerifier.VerifiedFrozenModelBinding,byte[],T> action) throws Exception {
    return accepted.accepted(action);
  }
  @Override public void close() { accepted.close(); }

  static final int MAX_BYTES = 2097152;
  static final class EncodedBeforeTransport extends RuntimeException {}
  record Wire(byte[] bytes, JsonNode body) {}

  static Wire encode(Prompt prompt, boolean streaming) {
    return encode(prompt, streaming, bytes -> {});
  }
  static Wire encode(Prompt prompt, boolean streaming, java.util.function.Consumer<byte[]> inspect) {
    var captured = new AtomicReference<byte[]>();
    var sentinel = new EncodedBeforeTransport();
    ClientHttpRequestFactory sync = (uri, method) -> new org.springframework.mock.http.client.MockClientHttpRequest(method, uri) {
      private int written;
      @Override public OutputStream getBody() throws IOException {
        return new FilterOutputStream(super.getBody()) {
          @Override public void write(int b) throws IOException {
            if (++written > MAX_BYTES) throw new IOException("OFFLINE_BODY_LIMIT"); out.write(b);
          }
          @Override public void write(byte[] b,int offset,int length) throws IOException {
            if (length > MAX_BYTES-written) throw new IOException("OFFLINE_BODY_LIMIT");
            written += length; out.write(b,offset,length);
          }
        };
      }
      @Override protected ClientHttpResponse executeInternal() {
        captured.set(getBodyAsBytes().clone()); inspect.accept(captured.get()); throw sentinel;
      }
    };
    ClientHttpConnector reactive = (method, uri, callback) -> {
      var request = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
      request.setWriteHandler(buffers -> DataBufferUtils.join(buffers, MAX_BYTES).flatMap(buffer -> {
        try { byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);captured.set(bytes); }
        finally { DataBufferUtils.release(buffer); }
        inspect.accept(captured.get());
        return Mono.error(sentinel);
      }));
      return callback.apply(request).then(Mono.error(sentinel));
    };
    var api = OpenAiApi.builder().baseUrl("https://offline.invalid").apiKey("offline-no-credential")
        .restClientBuilder(RestClient.builder().requestFactory(sync))
        .webClientBuilder(WebClient.builder().clientConnector(reactive)).build();
    var model = OpenAiChatModel.builder().openAiApi(api)
        .defaultOptions((OpenAiChatOptions)prompt.getOptions())
        .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    Throwable failure=catchThrowable(() -> {
      if (streaming) model.stream(prompt).collectList().block(Duration.ofSeconds(2)); else model.call(prompt);
    });
    assertThat(failure).as("the actual framework must reach the offline encoder sentinel").isNotNull();
    Throwable cause=failure;
    while(cause != sentinel && !(cause instanceof PlatformException) && cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
    if(cause instanceof PlatformException denied) throw denied;
    assertThat(cause).isSameAs(sentinel);
    byte[] bytes=captured.get(); assertThat(bytes).isNotNull().hasSizeLessThanOrEqualTo(MAX_BYTES);
    return new Wire(bytes,Json.read(new String(bytes,StandardCharsets.UTF_8)));
  }
  static void record(String name, Wire wire, String requestSha) {
    try {
      Path dir=Path.of(System.getProperty("binding.fixture"));
      String id="prompt-"+name+"-"+UUID.randomUUID();
      Files.write(dir.resolve(id+".wire.json"),wire.bytes());
      Files.writeString(dir.resolve(id+".identity.json"),Json.write(Map.of("requestSha256",requestSha,
          "wireSha256",Digest.sha256(new String(wire.bytes(),StandardCharsets.UTF_8)),"wireBytes",wire.bytes().length,
          "encodedBy","SpringAI1.0+Spring6.2.7","transportSent",false)));
    } catch(IOException e) { throw new UncheckedIOException(e); }
  }
  static void noPrivateFields(JsonNode body) {
    assertThat(body.fieldNames()).toIterable().doesNotContain("modelEntryId","modelBindingRef","manifest","requiredLabels",
        "appCode","runId","callKind","invocationRef","params","timeout","budgets","context","authority","traceId",
        "requestSha256","proof","rawBody","internalToolExecutionEnabled");
  }
  static void penalties(JsonNode body) {
    assertThat(body.has("frequency_penalty")).isTrue();
    assertThat(body.path("frequency_penalty").doubleValue()).isZero();
    assertThat(body.has("presence_penalty")).isTrue();
    assertThat(body.path("presence_penalty").doubleValue()).isEqualTo(0.00001);
  }
}
