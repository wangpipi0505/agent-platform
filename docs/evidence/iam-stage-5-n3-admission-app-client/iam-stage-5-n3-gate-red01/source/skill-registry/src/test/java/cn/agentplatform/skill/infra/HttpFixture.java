package cn.agentplatform.skill.infra;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅测试使用的真实阻塞HTTP端点；不复用外部进程或现有服务。 */
public class HttpFixture implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    public final AtomicInteger calls = new AtomicInteger();
    public volatile int delayMs;
    public volatile byte[] response = "{\"value\":7}".getBytes(StandardCharsets.UTF_8);
    public volatile String lastBody;

    public HttpFixture() throws IOException {
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        workers.submit(
                () -> {
                    while (!server.isClosed())
                        try {
                            Socket socket = server.accept();
                            workers.submit(() -> serve(socket));
                        } catch (IOException ex) {
                            if (!server.isClosed()) throw new UncheckedIOException(ex);
                        }
                });
    }

    public String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/tool";
    }

    private void serve(Socket socket) {
        try (socket) {
            var input = new BufferedInputStream(socket.getInputStream());
            line(input);
            var headers = new HashMap<String, String>();
            for (String h = line(input); !h.isEmpty(); h = line(input)) {
                int colon = h.indexOf(':');
                headers.put(
                        h.substring(0, colon).toLowerCase(Locale.ROOT),
                        h.substring(colon + 1).trim());
            }
            var body = new ByteArrayOutputStream();
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                for (int n = Integer.parseInt(line(input), 16);
                        n > 0;
                        n = Integer.parseInt(line(input), 16)) {
                    body.write(input.readNBytes(n));
                    line(input);
                }
                line(input);
            } else
                body.write(
                        input.readNBytes(
                                Integer.parseInt(headers.getOrDefault("content-length", "0"))));
            lastBody = body.toString(StandardCharsets.UTF_8);
            calls.incrementAndGet();
            if (delayMs > 0) Thread.sleep(delayMs);
            var output = socket.getOutputStream();
            output.write(
                    ("HTTP/1.1 200 OK\r\n"
                         + "Content-Type: application/json\r\n"
                         + "Connection: close\r\n"
                         + "Content-Length: "
                                    + response.length
                                    + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            output.write(response);
            output.flush();
        } catch (IOException ignored) {
            /* timeout tests deliberately close the client before this response */
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String line(InputStream input) throws IOException {
        var text = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') text.write(b);
        }
        return text.toString(StandardCharsets.US_ASCII);
    }

    public void close() throws Exception {
        server.close();
        workers.close();
    }
}
