package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.PlatformErrors;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.api.RunController;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class RunEventsHttpTest {
    @Configuration
    @EnableWebMvc
    static class HttpFixture implements WebMvcConfigurer {
        static final ChunkBus bus = new ChunkBus(1, 8192);

        @Bean
        TomcatServletWebServerFactory servletFactory() {
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getLoopbackAddress());
            factory.addInitializers(
                    servlet -> {
                        var filter =
                                servlet.addFilter(
                                        "fixture-identity",
                                        new OncePerRequestFilter() {
                                            @Override
                                            protected void doFilterInternal(
                                                    HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    FilterChain chain)
                                                    throws ServletException, java.io.IOException {
                                                RunEventsPostgresTest.actor("fixture", "owner");
                                                try {
                                                    chain.doFilter(request, response);
                                                } finally {
                                                    SecurityContextHolder.clearContext();
                                                }
                                            }
                                        });
                        filter.setAsyncSupported(true);
                        filter.addMappingForUrlPatterns(
                                EnumSet.of(DispatcherType.REQUEST), false, "/*");
                    });
            return factory;
        }

        @Bean
        org.springframework.boot.web.servlet.ServletRegistrationBean<
                        org.springframework.web.servlet.DispatcherServlet>
                dispatcher(org.springframework.web.context.WebApplicationContext context) {
            var registration =
                    new org.springframework.boot.web.servlet.ServletRegistrationBean<>(
                            new org.springframework.web.servlet.DispatcherServlet(context), "/");
            registration.setAsyncSupported(true);
            return registration;
        }

        @Bean
        TraceStore store() {
            var store = mock(TraceStore.class);
            var db = mock(JdbcTemplate.class);
            when(store.run(anyString(), any())).thenReturn(Map.of("id", 1L));
            when(store.db()).thenReturn(db);
            when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
            when(db.queryForMap(anyString(), any(Object[].class)))
                    .thenReturn(Map.of("status", "RUNNING", "event_seq", 0));
            return store;
        }

        @Bean
        RunController controller(TraceStore store) {
            return new RunController(null, store, bus, null);
        }

        @Bean
        PlatformErrors errors() {
            return new PlatformErrors();
        }

        @Bean(destroyMethod = "close")
        SimpleAsyncTaskExecutor writers() {
            var executor = new SimpleAsyncTaskExecutor("http-fixture-sse-");
            executor.setVirtualThreads(true);
            executor.setConcurrencyLimit(1);
            return executor;
        }

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.setTaskExecutor(writers()).setDefaultTimeout(60000);
        }
    }

    @Test
    void realHttpStartsOneInitializedWriterAndTcpDisconnectReleasesAdmission() throws Exception {
        try (var context =
                new AnnotationConfigServletWebServerApplicationContext(HttpFixture.class)) {
            int port = context.getWebServer().getPort();
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(3000);
                socket.setSoLinger(true, 0);
                socket.getOutputStream()
                        .write(
                                "GET /admin/runs/r_fixture/events HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII));
                var header = new java.io.ByteArrayOutputStream();
                while (header.size() < 8192) {
                    int value = socket.getInputStream().read();
                    if (value < 0) break;
                    header.write(value);
                    if (header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
                }
                String first = header.toString(StandardCharsets.US_ASCII);
                assertTrue(first.contains("200"), first);
                assertTrue(
                        first.toLowerCase(Locale.ROOT).contains("content-type: text/event-stream"),
                        first);
                assertThrows(PlatformException.class, () -> HttpFixture.bus.subscribe(2));
            }
            // A write after the peer's TCP reset observes the disconnect; only the observer is
            // stopped.
            for (int i = 0; i < 3; i++) {
                HttpFixture.bus.publish(1, "peer disconnected");
                Thread.sleep(30);
            }
            ChunkBusRedisTest.await(
                    () -> {
                        try (var replacement = HttpFixture.bus.subscribe(2)) {
                            return true;
                        } catch (PlatformException occupied) {
                            return false;
                        }
                    });
            assertTrue(HttpFixture.bus.chunkAvailable());
        } finally {
            HttpFixture.bus.close();
        }
    }
}
