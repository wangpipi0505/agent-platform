package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import cn.agentplatform.common.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeClientsPublishedHttpTest {
    @Test void unknownServiceNeverSignsOrSends() throws Exception {
        var sends = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            sends.incrementAndGet();
            byte[] data = "{\"code\":\"OK\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, data.length);
            try (var out=x.getResponseBody()) {out.write(data);}
        });
        server.start();
        try {
            var clients = new RuntimeClients();
            clients.model = "http://127.0.0.1:" + server.getAddress().getPort();
            var actor = new Actor("tenant-a", "alice", Set.of("EDITOR"), Map.of());
            for (String target : Arrays.asList(null, "", "modle", "APP")) {
                assertThatThrownBy(() -> clients.get(target, "/must-not-send", actor))
                    .isInstanceOfSatisfying(PlatformException.class, e -> assertThat(e.status()).isEqualTo(403));
            }
            assertThat(sends).hasValue(0);
        } finally {server.stop(0);}
    }
}
