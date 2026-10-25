package cn.agentplatform.engine.infra;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.api.RunController;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "ENGINE_PG_TEST", matches = "true")
class RunEventsPostgresTest {
    static TraceStore store;
    static JdbcTemplate db;
    ChunkBus bus;
    MockMvc mvc;
    String tenant;
    String publicId;
    long run;

    @BeforeAll
    static void database() {
        var ds =
                new DriverManagerDataSource(
                        EngineTestDatabase.url(),
                        EngineTestDatabase.user(),
                        EngineTestDatabase.password());
        Flyway.configure()
                .dataSource(ds)
                .schemas("engine")
                .defaultSchema("engine")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
        store = new TraceStore(db, new DataSourceTransactionManager(ds));
    }

    @BeforeEach
    void fixture() {
        tenant = "sse_" + UUID.randomUUID();
        publicId = "r_sse_" + UUID.randomUUID();
        run =
                db.queryForObject(
                        "insert into"
                            + " engine.run(public_id,app_code,debug_snapshot,trigger_type,status,principal_id,tenant_id,input_ref,version_refs,guard_ctx,budget,snapshot,inputs,mode)"
                            + " values(?,'test','{}','DEBUG','RUNNING','alice',?,'test','{}','{}','{}','{}','{}','FULL')"
                            + " returning id",
                        Long.class,
                        publicId,
                        tenant);
        bus = new ChunkBus();
        mvc =
                MockMvcBuilders.standaloneSetup(new RunController(null, store, bus, null))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
        actor(tenant, "alice");
    }

    static void actor(String tenant, String owner) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("verified")
                                        .header("alg", "HS256")
                                        .subject(owner)
                                        .claim("tenant_id", tenant)
                                        .claim("roles", List.of("EDITOR"))
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build()));
    }

    @AfterEach
    void close() {
        bus.close();
        SecurityContextHolder.clearContext();
    }

    void event(String type, Object payload) {
        store.atomic(() -> store.event(run, type, payload));
    }

    void terminal() {
        store.atomic(
                () -> {
                    store.event(run, "RUN_FINISHED", Map.of("status", "SUCCEEDED"));
                    db.update("update engine.run set status='SUCCEEDED' where id=?", run);
                    return null;
                });
    }

    MvcResult open(String cursor) throws Exception {
        return mvc.perform(
                        get("/admin/runs/" + publicId + "/events").header("Last-Event-ID", cursor))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    static String completed(MvcResult result) throws Exception {
        result.getAsyncResult(5000);
        return result.getResponse().getContentAsString();
    }

    @Test
    void tenantAndOwnerAreRejectedBeforeAnyStreamAdmission() throws Exception {
        actor(tenant + "_other", "alice");
        mvc.perform(get("/admin/runs/" + publicId + "/events")).andExpect(status().isNotFound());
        actor(tenant, "bob");
        mvc.perform(get("/admin/runs/" + publicId + "/events")).andExpect(status().isNotFound());
    }

    @Test
    void persistentPagesReplayAfterCursorAndDrainTerminalWatermark() throws Exception {
        for (int i = 1; i <= 70; i++) event("NODE_STARTED", Map.of("number", i));
        terminal();
        String content = completed(open("65"));
        assertFalse(content.contains("id:65\n"));
        assertTrue(content.contains("id:66\n"));
        assertTrue(content.contains("id:71\n"));
        assertEquals(6, content.split("\nid:").length - 1);
        assertTrue(content.contains("event:RUN_FINISHED"));
        assertFalse(content.contains("event:chunk"));
        assertTrue(content.contains("\"chunkReplay\":false"));
    }

    @Test
    void chunksHaveNoFactIdsAndDisconnectDoesNotChangeRun() throws Exception {
        var result = open("0");
        bus.publish(run, "online delta");
        ChunkBusRedisTest.await(
                () ->
                        new String(
                                        result.getResponse().getContentAsByteArray(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .contains("online delta"));
        terminal();
        String content = completed(result);
        assertTrue(content.contains("event:chunk\ndata:"));
        assertTrue(content.contains("id:1\nevent:RUN_FINISHED"));
        assertEquals(
                "SUCCEEDED",
                db.queryForObject("select status from engine.run where id=?", String.class, run));
    }

    @Test
    void oversizedFactSignalsSnapshotRecoveryWithoutErasingPgFact() throws Exception {
        event("NODE_FINISHED", Map.of("large", "x".repeat(ChunkBus.MAX_EVENT_BYTES + 1)));
        terminal();
        String content = completed(open("0"));
        assertTrue(content.contains("EVENT_LIMIT"));
        assertTrue(content.contains("FINAL_SNAPSHOT"));
        assertFalse(content.contains("id:1\n"));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from engine.run_event where run_id=?",
                        Integer.class,
                        run));
    }

    @Test
    void overflowSignalsResetAndLeavesRunningStateUntouched() throws Exception {
        var result = open("0");
        for (int i = 0; i < 100; i++) bus.publish(run, "delta");
        String content = completed(result);
        assertTrue(content.contains("stream_reset"));
        assertEquals(
                "RUNNING",
                db.queryForObject("select status from engine.run where id=?", String.class, run));
    }

    @Test
    void expiredFactCursorSignalsStatusWatermarkInsteadOfClaimingReplay() throws Exception {
        event("NODE_STARTED", Map.of());
        event("NODE_FINISHED", Map.of());
        terminal();
        db.update("delete from engine.run_event where run_id=? and seq<=2", run);
        String content = completed(open("1"));
        assertTrue(content.contains("CURSOR_EXPIRED"));
        assertTrue(content.contains("\"eventsWatermark\":3"));
        assertTrue(content.contains("\"status\":\"SUCCEEDED\""));
        assertFalse(content.contains("id:3\n"));
    }

    @Test
    void fullyClearedHistoryResetsOldCursorsWithBoundedAuthoritativeQueries() throws Exception {
        event("NODE_STARTED", Map.of());
        event("NODE_FINISHED", Map.of());
        terminal();
        db.update("delete from engine.run_event where run_id=?", run);
        for (String status : List.of("RUNNING", "SUCCEEDED")) {
            db.update("update engine.run set status=? where id=?", status, run);
            for (String cursor : List.of("0", "1", "4")) {
                String content = captureWithBoundedQueries(cursor, null);
                assertTrue(
                        content.contains(cursor.equals("4") ? "CURSOR_AHEAD" : "CURSOR_EXPIRED"),
                        content);
                assertTrue(content.contains("\"eventsWatermark\":3"), content);
                assertTrue(content.contains("\"status\":\"" + status + "\""), content);
                assertFalse(content.contains("id:"), content);
                assertFalse(content.contains("lastEventId"), content);
            }
            String current =
                    captureWithBoundedQueries(
                            "3",
                            () ->
                                    db.update(
                                            "update engine.run set status='SUCCEEDED' where id=?",
                                            run));
            assertFalse(current.contains("stream_reset"), current);
        }
        // A caught-up cursor must not claim expiry even after all retained rows disappear.
        db.update("update engine.run set event_seq=0 where id=?", run);
        String empty = captureWithBoundedQueries("0", null);
        assertFalse(empty.contains("stream_reset"), empty);
        db.update("update engine.run set status='RUNNING' where id=?", run);
        empty =
                captureWithBoundedQueries(
                        "0",
                        () ->
                                db.update(
                                        "update engine.run set status='SUCCEEDED' where id=?",
                                        run));
        assertFalse(empty.contains("stream_reset"), empty);
    }

    @Test
    void retentionCleanupBetweenInitialSnapshotAndDrainResetsWithoutBusyPolling() throws Exception {
        event("NODE_STARTED", Map.of());
        terminal();
        String content =
                captureWithBoundedQueries(
                        "0", () -> db.update("delete from engine.run_event where run_id=?", run));
        assertTrue(content.contains("CURSOR_EXPIRED"), content);
        assertTrue(content.contains("\"eventsWatermark\":2"), content);
        assertTrue(content.contains("\"status\":\"SUCCEEDED\""), content);
        assertFalse(content.contains("id:"), content);
    }

    String captureWithBoundedQueries(String cursor, Runnable afterFirstFrame) throws Exception {
        var counting = org.mockito.Mockito.spy(new JdbcTemplate(db.getDataSource()));
        var traced = new TraceStore(counting, new DataSourceTransactionManager(db.getDataSource()));
        var body =
                new RunController(null, traced, bus, null).events(publicId, cursor, null).getBody();
        var output =
                new java.io.ByteArrayOutputStream() {
                    boolean first = true;

                    @Override
                    public void flush() {
                        if (first && afterFirstFrame != null) {
                            first = false;
                            afterFirstFrame.run();
                        }
                    }
                };
        org.mockito.Mockito.clearInvocations(counting);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> body.writeTo(output));
        org.mockito.Mockito.verify(counting, org.mockito.Mockito.atMost(2))
                .queryForMap(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class));
        org.mockito.Mockito.verify(counting, org.mockito.Mockito.atMost(1))
                .queryForList(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object[].class));
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void unavailableChunkTransportStillReplaysAuthoritativePgFacts() throws Exception {
        int unused;
        try (var socket =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            unused = socket.getLocalPort();
        }
        bus.close();
        bus = new ChunkBus("127.0.0.1", unused, "", 256, 16777216);
        mvc =
                MockMvcBuilders.standaloneSetup(new RunController(null, store, bus, null))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
        terminal();
        String content = completed(open("0"));
        assertTrue(content.contains("\"chunkAvailable\":false"));
        assertTrue(content.contains("id:1\nevent:RUN_FINISHED"));
        assertEquals(
                "SUCCEEDED",
                db.queryForObject("select status from engine.run where id=?", String.class, run));
    }
}
