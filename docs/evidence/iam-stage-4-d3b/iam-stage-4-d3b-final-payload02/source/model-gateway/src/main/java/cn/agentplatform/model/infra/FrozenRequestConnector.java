package cn.agentplatform.model.infra;

import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.client.reactive.*;

import reactor.core.publisher.*;

import java.net.URI;
import java.util.function.*;

/** Spring AI流式调用复用Spring WebClient连接器，网络发送前捕获真实序列化字节。 有界join仅作用于请求（上限2MiB）；响应仍逐帧背压处理，不整体缓存响应。 */
public final class FrozenRequestConnector implements ClientHttpConnector {
    private final ClientHttpConnector delegate;
    private final BiConsumer<URI, byte[]> beforeSend;

    public FrozenRequestConnector(BiConsumer<URI, byte[]> beforeSend) {
        this.delegate = new JdkClientHttpConnector();
        this.beforeSend = beforeSend;
    }

    @Override
    public Mono<ClientHttpResponse> connect(
            HttpMethod method, URI uri, Function<? super ClientHttpRequest, Mono<Void>> callback) {
        return delegate.connect(
                        method,
                        uri,
                        request ->
                                callback.apply(
                                        new ClientHttpRequestDecorator(request) {
                                            @Override
                                            public Mono<Void> writeWith(
                                                    Publisher<? extends DataBuffer> publisher) {
                                                return DataBufferUtils.join(
                                                                Flux.from(publisher),
                                                                2 * 1024 * 1024)
                                                        .flatMap(
                                                                buffer -> {
                                                                    byte[] bytes =
                                                                            new byte
                                                                                    [buffer
                                                                                            .readableByteCount()];
                                                                    buffer.read(bytes);
                                                                    DataBufferUtils.release(buffer);
                                                                    beforeSend.accept(
                                                                            uri,
                                                                            bytes); // PG提交成功之前，不调用底层writeWith。
                                                                    return super.writeWith(
                                                                            Mono.just(
                                                                                    bufferFactory()
                                                                                            .wrap(
                                                                                                    bytes)));
                                                                });
                                            }
                                        }))
                .flatMap(
                        response -> {
                            if (response.getStatusCode().isError())
                                return response.getBody()
                                        .take(1)
                                        .doOnNext(DataBufferUtils::release)
                                        .then(
                                                Mono.error(
                                                        cn.agentplatform.model.domain.ModelErrors
                                                                .status(
                                                                        response.getStatusCode()
                                                                                .value())));
                            return Mono.just(
                                    new ClientHttpResponseDecorator(response) {
                                        private final java.util.concurrent.atomic.AtomicLong
                                                received =
                                                        new java.util.concurrent.atomic
                                                                .AtomicLong();

                                        @Override
                                        public Flux<DataBuffer> getBody() {
                                            return super.getBody()
                                                    .<DataBuffer>handle(
                                                            (buffer, sink) -> {
                                                                if (received.addAndGet(
                                                                                buffer
                                                                                        .readableByteCount())
                                                                        > 2 * 1024 * 1024) {
                                                                    DataBufferUtils.release(buffer);
                                                                    sink.error(
                                                                            new IllegalArgumentException(
                                                                                    "模型流式响应超过安全大小"));
                                                                } else sink.next(buffer);
                                                            });
                                        }
                                    });
                        });
    }
}
