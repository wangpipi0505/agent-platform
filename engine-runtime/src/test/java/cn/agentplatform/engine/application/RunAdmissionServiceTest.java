package cn.agentplatform.engine.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.domain.RunAdmissionRejected;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受理协调的单元契约：幂等读取、容量拒绝、请求形状、事务边界都不依赖 HTTP 或 PG。
 * 真实 PG 的并发/锁顺序证据由受理子步的装配测试另行覆盖。
 */
class RunAdmissionServiceTest {
    private final Actor actor =
            new Actor("tenant-a", "alice", Set.of("operator"), Map.of("region", Set.of("A")));
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TraceStore store = mock(TraceStore.class);
    private final RunScheduler scheduler = mock(RunScheduler.class);

    private RunAdmissionService service() {
        when(store.db()).thenReturn(jdbc);
        return new RunAdmissionService(store, scheduler);
    }

    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void protectedAdmissionCannotJoinCallerTransaction() {
        var service = service();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var rejected =
                assertThrows(
                        PlatformException.class, () -> service.requireNoAmbientTransaction(true));
        assertEquals(403, rejected.status());
        assertEquals("AUTHORITY_TRANSACTION_FORBIDDEN", rejected.code());
        service.requireNoAmbientTransaction(false);
    }

    @Test
    void publishedRequestShapeRejectsDraftsTrustedSnapshotsAndTickets() {
        ObjectNode valid = Json.object();
        valid.put("mode", "FULL");
        valid.put("requestId", "req-1");
        RunAdmissionService.requirePublishedRequest(
                valid, "TASK", null, null, false, null, false);

        ObjectNode versioned = valid.deepCopy();
        versioned.put("versionNo", 3);
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () ->
                                RunAdmissionService.requirePublishedRequest(
                                        versioned, "TASK", null, null, false, null, false));
        assertEquals(403, rejected.status());

        ObjectNode retried = valid.deepCopy();
        retried.put("requestId", " ");
        assertThrows(
                PlatformException.class,
                () ->
                        RunAdmissionService.requirePublishedRequest(
                                retried, "TASK", null, null, false, null, false));
    }

    @Test
    void existingIsSkippedWithoutRequestId() {
        assertNull(service().existing(actor, Json.object(), false));
        verifyNoInteractions(jdbc);
    }

    @Test
    void existingReadsTenantPrincipalAndRequestId() {
        ObjectNode request = Json.object();
        request.put("requestId", "req-1");
        Map<String, Object> row = Map.of("id", 7L, "public_id", "r_x");
        doReturn(List.of(row)).when(jdbc).queryForList(anyString(), any(Object[].class));

        assertEquals(row, service().existing(actor, request, false));
    }

    @Test
    void lockAndReuseReturnsTheAlreadyAcceptedRunId() {
        ObjectNode request = Json.object();
        request.put("requestId", "req-1");
        request.set("inputs", Json.tree(Map.of("message", "hi")));
        Map<String, Object> row =
                Map.of(
                        "id", 7L,
                        "public_id", "r_x",
                        "app_code", "demo",
                        "trigger_type", "TASK",
                        "inputs", Json.write(Map.of("message", "hi")));
        doReturn(List.of())
                .when(jdbc)
                .queryForList(contains("pg_advisory_xact_lock"), any(Object[].class));
        doReturn(List.of(row))
                .when(jdbc)
                .queryForList(contains("from engine.run"), any(Object[].class));

        assertEquals(7L, service().lockAndReuse(actor, request, "demo", "TASK", false, null));
    }

    @Test
    void verifyReplayRejectsDifferentInputsButAcceptsTheSameRequestShape() {
        var service = service();
        Map<String, Object> row =
                Map.of(
                        "app_code", "demo",
                        "trigger_type", "TASK",
                        "inputs", Json.write(Map.of("message", "hi")));
        service.verifyReplay(row, "demo", request("hi"), "TASK");

        var conflict =
                assertThrows(
                        PlatformException.class,
                        () -> service.verifyReplay(row, "demo", request("other"), "TASK"));
        assertEquals(409, conflict.status());
        assertEquals("REQUEST_CONFLICT", conflict.code());
    }

    @Test
    void reserveSurfacesCapacityRejectionAndHonoursProvidedTickets() {
        var service = service();
        when(scheduler.reserveInteractive()).thenThrow(new RunAdmissionRejected(false));

        JsonNode request = Json.object();
        var rejected =
                assertThrows(
                        PlatformException.class,
                        () ->
                                service.reserve(
                                        actor, request, "demo", "TASK", false, null, null, false));
        assertEquals(429, rejected.status());
        assertEquals("ENGINE_BUSY", rejected.code());

        var accepted =
                service.reserve(actor, request, "demo", "TASK", false, null, null, true);
        assertNull(accepted.slot());
        assertNull(accepted.replay());
        verify(scheduler, times(1)).reserveInteractive();
    }

    @Test
    void openingWithoutAssemblyIsAnExplainableBlock() {
        var service = service();
        var rejected =
                assertThrows(
                        PlatformException.class, () -> service.opening("demo", actor, true));
        assertEquals(403, rejected.status());
        assertEquals("IAM_ADMISSION_UNAVAILABLE", rejected.code());
        assertNull(service.opening("demo", actor, false));
        assertFalse(service.wired());
    }

    private JsonNode request(String message) {
        ObjectNode request = Json.object();
        request.set("inputs", Json.tree(Map.of("message", message)));
        return request;
    }
}
