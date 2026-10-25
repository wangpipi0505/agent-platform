import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 本机验收fixture：可计数的5MiB只读JSON，只用于验证MinIO转存与受控下载。 */
public class LargeResultFixture {
    public static void main(String[] args) throws Exception {
        byte[] payload =
                ("{\"data\":\""
                                + "x".repeat(5 * 1024 * 1024)
                                + "\",\"message\":\"large-artifact-fixture\"}")
                        .getBytes(StandardCharsets.UTF_8);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 28412), 0);
        server.createContext(
                "/result",
                exchange -> {
                    exchange.getRequestBody().readNBytes(8192);
                    calls.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, payload.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(payload);
                    } finally {
                        exchange.close();
                    }
                });
        server.createContext(
                "/stats",
                exchange -> {
                    byte[] bytes =
                            ("{\"calls\":" + calls.get() + ",\"bytes\":" + payload.length + "}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(bytes);
                    } finally {
                        exchange.close();
                    }
                });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("TEST ONLY: readonly large JSON=127.0.0.1:28412/result");
    }
}
