package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.agentplatform.common.Json;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SSE + JSON-RPC HTTP peer; no SDK/result mocks. */
class McpAdapterTest {
    Peer peer;
    SkillAdapters adapters;
    long selectorBaseline;

    @BeforeEach
    void start() throws Exception {
        selectorBaseline = selectors();
        peer = new Peer();
        adapters = new SkillAdapters(mock(ArtifactStore.class), mock(AlgorithmAdapter.class));
    }

    @AfterEach
    void stop() throws Exception {
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((peer.streams.get() > 0 || selectors() > selectorBaseline)
                    && System.nanoTime() < end) Thread.sleep(20);
            assertEquals(0, peer.streams.get(), "SSE socket must close before returning");
            assertTrue(selectors() <= selectorBaseline, "per-session HttpClient selector leaked");
            assertEquals(2, McpAdapter.SESSIONS.availablePermits(), "session permit leaked");
        } finally {
            peer.close();
        }
    }

    static long selectors() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(
                        t ->
                                t.isAlive()
                                        && t.getName().matches("HttpClient-[0-9]+-SelectorManager"))
                .count();
    }

    Map<String, Object> contract(boolean write) {
        return new LinkedHashMap<>(
                Map.of(
                        "type",
                        "MCP",
                        "endpoint",
                        peer.url(),
                        "toolName",
                        "lookup",
                        "timeoutMs",
                        3000,
                        "inputSchema",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("region", Map.of("type", "string")),
                                "required",
                                List.of("region")),
                        "outputSchema",
                        Map.of("type", "object"),
                        "outputMaxBytes",
                        10 * 1024 * 1024,
                        "execution",
                        Map.of("sideEffect", write, "readonly", !write)));
    }

    SkillAdapters.Raw invoke(boolean write) {
        return adapters.invoke(
                contract(write), Map.of("region", "A"), "stable-key", 1, false, "run-1");
    }

    SkillAdapters.Raw invoke(boolean write, int timeout) {
        var c = contract(write);
        c.put("timeoutMs", timeout);
        return adapters.invoke(c, Map.of("region", "A"), "stable-key", 1, false, "run-1");
    }

    @Test
    void initializesListsThenCallsWithRealArguments() {
        var raw = invoke(false);
        assertEquals(7, raw.structured().path("value").asInt());
        assertEquals(
                List.of("initialize", "notifications/initialized", "tools/list", "tools/call"),
                peer.methods);
        assertEquals(Map.of("region", "A"), peer.arguments);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void crOnlySseLineEndingsStillUseTheSdkProtocol() {
        peer.lineEnding = "\r";
        assertEquals(7, assertDoesNotThrow(() -> invoke(false)).structured().path("value").asInt());
        assertEquals(1, peer.calls.get());
    }

    @Test
    void validSseExtensionFieldsAreIgnoredWithinWireLimits() {
        peer.extensionField = true;
        assertEquals(7, assertDoesNotThrow(() -> invoke(false)).structured().path("value").asInt());
        assertEquals(1, peer.calls.get());
    }

    @Test
    void initialUtf8BomIsAcceptedWithinWireByteCount() {
        peer.bom = true;
        assertEquals(7, assertDoesNotThrow(() -> invoke(false)).structured().path("value").asInt());
        assertEquals(1, peer.calls.get());
    }

    @Test
    void queryOnlyCannotIssueASecondMcpBusinessCall() {
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.invoke(
                                        contract(true),
                                        Map.of("region", "A"),
                                        "stable-key",
                                        1,
                                        true,
                                        "r_query"));
        assertEquals("RESULT_UNKNOWN", failure.semantic);
        assertTrue(failure.unknown);
        assertEquals(0, peer.calls.get());
        assertEquals(0, peer.opens.get());
    }

    @Test
    void oversizedArgumentsFailBeforeCallWithoutBecomingRetryable() {
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.invoke(
                                        contract(true),
                                        Map.of("region", "A".repeat(128 * 1024)),
                                        "key",
                                        1,
                                        false,
                                        "r_large_input"));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void missingToolFailsBeforeBusinessCall() {
        peer.toolName = "other";
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void remoteInputSchemaIsCheckedBeforeBusinessCall() {
        peer.inputSchema =
                Map.of(
                        "type",
                        "object",
                        "required",
                        List.of("region"),
                        "properties",
                        Map.of("region", Map.of("type", "integer")));
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void writeToolErrorNeverAuthorizesAutomaticRetry() {
        peer.isError = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("RESULT_UNKNOWN", failure.semantic);
        assertTrue(failure.unknown);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void forbiddenPostIsNotLostAsGenericTimeout() {
        peer.postStatus = 403;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("NO_PERMISSION", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void oversizedEventIsRejectedBeforeSdkMaterializesResult() {
        peer.large = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void absentToolsCapabilityRejectsBeforeListOrCall() {
        peer.hasTools = false;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertFalse(failure.unknown);
        assertFalse(peer.methods.contains("tools/list"));
        assertEquals(0, peer.calls.get());
    }

    @Test
    void unsupportedProtocolIsAConfigurationFailureBeforeBusinessCall() {
        peer.protocol = "2099-01-01";
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void followsBoundedToolPagesWithoutChangingArguments() {
        peer.paginated = true;
        invoke(false);
        assertEquals(2, Collections.frequency(peer.methods, "tools/list"));
        assertEquals(List.of("", "page-2"), peer.cursors);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void repeatedCursorCannotLoopUntilTimeout() {
        peer.paginated = true;
        peer.loopCursor = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void duplicateToolNamesRejectBeforeBusinessCall() {
        peer.duplicate = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void rawTopLevelSchemaKeywordsSurviveSdkConversion() {
        peer.inputSchema = new LinkedHashMap<>(peer.inputSchema);
        peer.inputSchema.put("minProperties", 2);
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void topLevelOneOfAndNestedPatternAreNotLostBySdkJsonSchema() {
        peer.inputSchema = new LinkedHashMap<>(peer.inputSchema);
        peer.inputSchema.put(
                "oneOf",
                List.of(
                        Map.of("properties", Map.of("region", Map.of("pattern", "^B$"))),
                        Map.of("properties", Map.of("region", Map.of("enum", List.of("C"))))));
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("PARAM_ERROR", failure.semantic);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void externalSchemaReferenceNeverTriggersNetworkLookup() {
        peer.inputSchema = new LinkedHashMap<>(peer.inputSchema);
        peer.inputSchema.put("$ref", peer.url() + "/forbidden");
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void schemaOver64KiBRejectsBeforeBusinessCall() {
        peer.inputSchema = new LinkedHashMap<>(peer.inputSchema);
        peer.inputSchema.put("description", "x".repeat(65537));
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void toolCountIsBoundedBeforeSdkConvertsAllTools() {
        peer.manyTools = 101;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void pagesCannotExtendBeyondSixteenRequests() {
        peer.endlessPages = true;
        var c = contract(false);
        c.put("timeoutMs", 4000);
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.invoke(
                                        c, Map.of("region", "A"), "key", 1, false, "r_pages"));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(16, peer.cursors.size());
        assertEquals(0, peer.calls.get());
    }

    @Test
    void quotaHasDistinctNonUnknownSemantic() {
        peer.postStatus = 429;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("QUOTA", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void writeDisconnectIsUnknownAndNeverResent() {
        peer.dropCall = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("RESULT_UNKNOWN", failure.semantic);
        assertTrue(failure.unknown);
        assertEquals(1, peer.calls.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429, 500})
    void businessPostRejectionPreservesDefiniteErrorsAndUncertainWrite(int status) {
        peer.callHttpStatus = status;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals(
                status == 403 ? "NO_PERMISSION" : status == 429 ? "QUOTA" : "RESULT_UNKNOWN",
                failure.semantic);
        assertEquals(status == 500, failure.unknown);
        assertEquals(1, Collections.frequency(peer.methods, "tools/call"));
    }

    @Test
    void droppedBusinessPostAckNeverReplaysPostBody() {
        peer.dropCallAck = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("RESULT_UNKNOWN", failure.semantic);
        assertTrue(failure.unknown);
        assertEquals(1, Collections.frequency(peer.methods, "tools/call"));
    }

    @Test
    void readDisconnectIsFailureWithoutAutomaticRetry() {
        peer.dropCall = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("RETRYABLE_FAILURE", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void endpointCannotForwardCredentialsAcrossOrigins() {
        peer.messageEndpoint = "http://127.0.0.1:1/capture";
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertFalse(failure.unknown);
        assertTrue(peer.methods.isEmpty());
    }

    @Test
    void connectionFailurePropagatesWithoutUnconsumedGlobalReactorError() {
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger("reactor.core.publisher.Operators");
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            peer.messageEndpoint = "http://127.0.0.1:1/capture";
            assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
            assertFalse(
                    appender.list.stream()
                            .anyMatch(
                                    event ->
                                            event.getFormattedMessage()
                                                    .contains("onErrorDropped")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void entireSessionUsesOneDeadlineIncludingBlockedReads() {
        peer.delayMs = 220;
        var c = contract(true);
        c.put("timeoutMs", 500);
        long start = System.nanoTime();
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () -> adapters.invoke(c, Map.of("region", "A"), "key", 1, false, "r_test"));
        assertTrue(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1400,
                "phases must not reset timeout");
        assertTrue(peer.calls.get() <= 1);
        assertTrue(Set.of("RESULT_UNKNOWN", "RETRYABLE_FAILURE").contains(failure.semantic));
    }

    @Test
    void idleSseWithoutEndpointClosesAtTotalDeadline() {
        peer.omitEndpoint = true;
        long start = System.nanoTime();
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true, 800));
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800);
    }

    @Test
    void tooManyEventsClosesStreamWithoutQueueingSdkNotifications() {
        peer.floodEvents = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(false));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void totalWireLimitAlsoCountsNonDataSseComments() {
        peer.floodComments = true;
        var c = contract(false);
        c.put("timeoutMs", 4000);
        var failure =
                assertThrows(
                        SkillAdapters.Failure.class,
                        () ->
                                adapters.invoke(
                                        c, Map.of("region", "A"), "key", 1, false, "r_limit"));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertEquals(1, peer.calls.get());
    }

    @Test
    void ackBodyHasIndependentReceiveLimit() {
        peer.oversizeAck = true;
        var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true));
        assertEquals("OUTPUT_INVALID", failure.semantic);
        assertFalse(failure.unknown);
        assertEquals(0, peer.calls.get());
    }

    @Test
    void blockedAckBodyCannotOutliveTheWholeSessionDeadline() {
        peer.blockAck = true;
        long start = System.nanoTime();
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger("reactor.core.publisher.Operators");
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = assertThrows(SkillAdapters.Failure.class, () -> invoke(true, 800));
            assertFalse(failure.unknown);
            assertEquals(0, peer.calls.get());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800);
            assertFalse(
                    appender.list.stream()
                            .anyMatch(
                                    event ->
                                            event.getFormattedMessage()
                                                    .contains("onErrorDropped")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void twoRealSessionsExhaustLocalDecodeAdmissionBeforeThirdSocket() throws Exception {
        try (var second = new Peer();
                var third = new Peer();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            peer.holdCall = new CountDownLatch(1);
            second.holdCall = new CountDownLatch(1);
            var firstContract = contract(false);
            firstContract.put("timeoutMs", 5000);
            var secondContract = new LinkedHashMap<>(firstContract);
            secondContract.put("endpoint", second.url());
            Future<?> one =
                    pool.submit(
                            () ->
                                    adapters.invoke(
                                            firstContract,
                                            Map.of("region", "A"),
                                            "one",
                                            1,
                                            false,
                                            "r_one"));
            Future<?> two =
                    pool.submit(
                            () ->
                                    adapters.invoke(
                                            secondContract,
                                            Map.of("region", "A"),
                                            "two",
                                            2,
                                            false,
                                            "r_two"));
            try {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((peer.calls.get() != 1 || second.calls.get() != 1)
                        && System.nanoTime() < end) Thread.sleep(10);
                assertEquals(1, peer.calls.get());
                assertEquals(1, second.calls.get());
                var denied = new LinkedHashMap<>(firstContract);
                denied.put("endpoint", third.url());
                var failure =
                        assertThrows(
                                SkillAdapters.Failure.class,
                                () ->
                                        adapters.invoke(
                                                denied,
                                                Map.of("region", "A"),
                                                "three",
                                                3,
                                                false,
                                                "r_three"));
                assertEquals("QUOTA", failure.semantic);
                assertEquals(0, third.opens.get());
            } finally {
                peer.holdCall.countDown();
                second.holdCall.countDown();
            }
            one.get(3, TimeUnit.SECONDS);
            two.get(3, TimeUnit.SECONDS);
        }
    }

    static final class Peer implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        final CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger(),
                streams = new AtomicInteger(),
                opens = new AtomicInteger();
        final CopyOnWriteArrayList<String> cursors = new CopyOnWriteArrayList<>();
        volatile OutputStream events;
        volatile boolean closed,
                isError,
                large,
                hasTools = true,
                paginated,
                loopCursor,
                duplicate,
                dropCall,
                omitEndpoint,
                floodEvents,
                floodComments,
                oversizeAck,
                blockAck,
                endlessPages,
                dropCallAck,
                extensionField,
                bom;
        volatile Runnable beforeCall;
        volatile int manyTools;
        volatile String expectedAuth;
        final AtomicInteger authRejects = new AtomicInteger();
        final AtomicInteger schemaReads = new AtomicInteger();
        volatile String protocol = "2024-11-05", messageEndpoint = "/messages?session=own";
        volatile String lineEnding = "\n";
        volatile long delayMs;
        volatile CountDownLatch holdCall;
        volatile String output = "{\"value\":7}";
        volatile String toolName = "lookup";
        volatile int postStatus = 202;
        volatile int callHttpStatus = 202;
        volatile Map<String, Object> inputSchema =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("region", Map.of("type", "string")),
                        "required",
                        List.of("region"));
        volatile Map<String, Object> arguments;

        Peer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/sse", this::sse);
            server.createContext("/messages", this::message);
            server.createContext(
                    "/schema-network",
                    exchange -> {
                        schemaReads.incrementAndGet();
                        byte[] bytes = "{\"type\":\"object\"}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders()
                                .set("Content-Type", "application/schema+json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
        }

        String schemaUrl() {
            return url().replace("/sse", "/schema-network");
        }

        void sse(HttpExchange exchange) throws IOException {
            opens.incrementAndGet();
            if (expectedAuth != null
                    && !expectedAuth.equals(
                            exchange.getRequestHeaders().getFirst("Authorization"))) {
                authRejects.incrementAndGet();
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            streams.incrementAndGet();
            events = exchange.getResponseBody();
            try {
                if (bom) events.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
                if (extensionField) {
                    events.write(
                            "x-provider-extension: safe-to-ignore\n\n"
                                    .getBytes(StandardCharsets.UTF_8));
                    events.flush();
                }
                if (!omitEndpoint) event("endpoint", messageEndpoint);
                while (!closed) {
                    Thread.sleep(40);
                    synchronized (this) {
                        events.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                        events.flush();
                    }
                }
            } catch (IOException ignored) {
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                streams.decrementAndGet();
                exchange.close();
            }
        }

        void message(HttpExchange exchange) throws IOException {
            if (expectedAuth != null
                    && !expectedAuth.equals(
                            exchange.getRequestHeaders().getFirst("Authorization"))) {
                authRejects.incrementAndGet();
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            var request =
                    Json.read(
                            new String(
                                    exchange.getRequestBody().readNBytes(128 * 1024),
                                    StandardCharsets.UTF_8));
            String method = request.path("method").asText();
            methods.add(method);
            if (oversizeAck) {
                byte[] excessive = new byte[8193];
                exchange.sendResponseHeaders(202, excessive.length);
                exchange.getResponseBody().write(excessive);
                exchange.close();
                return;
            }
            if (blockAck) {
                exchange.sendResponseHeaders(202, 0);
                try {
                    while (!closed) {
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                        Thread.sleep(40);
                    }
                } catch (IOException ignored) {
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            if (method.equals("tools/call") && dropCallAck) {
                exchange.close();
                return;
            }
            int status = method.equals("tools/call") ? callHttpStatus : postStatus;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            if (status >= 400 || !request.has("id")) return;
            if (delayMs > 0)
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            Object result;
            if (method.equals("initialize"))
                result =
                        Map.of(
                                "protocolVersion",
                                protocol,
                                "capabilities",
                                hasTools ? Map.of("tools", Map.of("listChanged", false)) : Map.of(),
                                "serverInfo",
                                Map.of("name", "isolated-peer", "version", "1"));
            else if (method.equals("tools/list")) {
                String cursor = request.path("params").path("cursor").asText("");
                cursors.add(cursor);
                var tool =
                        Map.of(
                                "name",
                                endlessPages
                                        ? "tool_" + cursors.size()
                                        : paginated && cursor.isEmpty() ? "other" : toolName,
                                "description",
                                "bounded test",
                                "inputSchema",
                                inputSchema);
                var list = new LinkedHashMap<String, Object>();
                list.put("tools", duplicate ? List.of(tool, tool) : List.of(tool));
                if (paginated && (cursor.isEmpty() || loopCursor)) list.put("nextCursor", "page-2");
                if (endlessPages) list.put("nextCursor", "page-" + cursors.size());
                if (manyTools > 0) {
                    var many = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < manyTools; i++)
                        many.add(
                                Map.of(
                                        "name",
                                        "tool_" + i,
                                        "description",
                                        "test",
                                        "inputSchema",
                                        inputSchema));
                    list.put("tools", many);
                }
                result = list;
            } else if (method.equals("tools/call")) {
                calls.incrementAndGet();
                arguments =
                        Json.mapper()
                                .convertValue(request.path("params").path("arguments"), Map.class);
                if (beforeCall != null) beforeCall.run();
                if (dropCall) {
                    events.close();
                    return;
                }
                if (floodEvents) {
                    for (int i = 0; i < 257; i++)
                        event(
                                "message",
                                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}");
                    return;
                }
                if (floodComments) {
                    byte[] comment =
                            (":" + "x".repeat(1024 * 1024) + "\n\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    synchronized (this) {
                        for (int i = 0; i < 17; i++) {
                            events.write(comment);
                            events.flush();
                        }
                    }
                    return;
                }
                if (holdCall != null)
                    try {
                        holdCall.await(6, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                String text =
                        large
                                ? Json.write(Map.of("value", "x".repeat(4 * 1024 * 1024 + 1)))
                                : output;
                result =
                        Map.of(
                                "content",
                                List.of(Map.of("type", "text", "text", text)),
                                "isError",
                                isError);
            } else result = Map.of();
            event(
                    "message",
                    Json.write(
                            Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result)));
        }

        synchronized void event(String type, String data) throws IOException {
            events.write(
                    ("event: " + type + lineEnding + "data: " + data + lineEnding + lineEnding)
                            .getBytes(StandardCharsets.UTF_8));
            events.flush();
        }

        @Override
        public void close() {
            closed = true;
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
