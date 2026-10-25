package cn.agentplatform.monitor.application;

import cn.agentplatform.common.Json;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Blocking local HTTP peer; all report facts are explicit test data. */
final class StatisticsHttpFixture implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    volatile String engineTarget = "";
    volatile Map<String, Object> modelRequest = Map.of();
    volatile int engineRequests, modelRequests;
    volatile boolean failModel;

    StatisticsHttpFixture() throws IOException {
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

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    void serve(Socket socket) {
        try (socket) {
            var input = new BufferedInputStream(socket.getInputStream());
            String target = line(input).split(" ")[1];
            var headers = new HashMap<String, String>();
            for (String h = line(input); !h.isEmpty(); h = line(input)) {
                int colon = h.indexOf(':');
                headers.put(
                        h.substring(0, colon).toLowerCase(Locale.ROOT),
                        h.substring(colon + 1).trim());
            }
            if (!headers.getOrDefault("authorization", "").startsWith("Bearer "))
                throw new IOException("Missing internal ticket");
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
            Map<String, Object> data;
            int status = 200;
            if (target.startsWith("/internal/statistics/query")) {
                modelRequests++;
                modelRequest = Json.read(body.toString(StandardCharsets.UTF_8), Map.class);
                var usage = new LinkedHashMap<String, Object>();
                usage.put("runId", "r_one");
                usage.put("calls", 2);
                usage.put("knownTokens", 21);
                usage.put("totalTokens", null);
                usage.put("usageMissingCalls", 1);
                data =
                        Map.of(
                                "byRun",
                                List.of(usage),
                                "snapshotAt",
                                "model-snapshot",
                                "grain",
                                "MODEL_SEND");
                if (failModel) status = 503;
            } else {
                engineRequests++;
                engineTarget = target;
                data =
                        Map.of(
                                "groups",
                                List.of(
                                        Map.of(
                                                "groupKey",
                                                "key",
                                                "appCode",
                                                "app-a",
                                                "bizSystemCode",
                                                "biz-a",
                                                "callKind",
                                                "FORMAL",
                                                "date",
                                                "2026-10-01",
                                                "runs",
                                                1,
                                                "confirmationCount",
                                                2)),
                                "runRefs",
                                List.of(Map.of("runId", "r_one", "groupKey", "key")),
                                "skillGroups",
                                List.of(),
                                "failures",
                                List.of(),
                                "snapshotAt",
                                "engine-snapshot",
                                "from",
                                "2026-10-01T00:00:00Z",
                                "to",
                                "2026-10-02T00:00:00Z",
                                "windowBasis",
                                "RUN_STARTED_AT");
            }
            byte[] bytes =
                    Json.write(Map.of("code", status == 200 ? "OK" : "UNAVAILABLE", "data", data))
                            .getBytes(StandardCharsets.UTF_8);
            var output = socket.getOutputStream();
            output.write(
                    ("HTTP/1.1 "
                                    + status
                                    + " OK\r\n"
                                    + "Content-Type: application/json\r\n"
                                    + "Connection: close\r\n"
                                    + "Content-Length: "
                                    + bytes.length
                                    + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static String line(InputStream input) throws IOException {
        var out = new ByteArrayOutputStream();
        for (int c = input.read(); c != -1 && c != '\n'; c = input.read())
            if (c != '\r') out.write(c);
        return out.toString(StandardCharsets.US_ASCII);
    }

    public void close() throws Exception {
        server.close();
        workers.close();
    }
}
