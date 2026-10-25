package cn.agentplatform.model.api;

import cn.agentplatform.common.*;
import cn.agentplatform.model.application.ModelCalls;
import cn.agentplatform.model.infra.ModelStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
public class ModelController {
    private final ModelStore store;
    private final ModelCalls calls;

    public ModelController(ModelStore store, ModelCalls calls) {
        this.store = store;
        this.calls = calls;
    }

    @GetMapping("/admin/model-providers")
    public Object providers() {
        return ApiResponse.ok(store.providers(Actor.current()));
    }

    @PostMapping("/admin/model-providers")
    public Object provider(@RequestBody JsonNode body) {
        return ApiResponse.ok(store.createProvider(body, Actor.current()));
    }

    @PostMapping("/admin/model-providers/{id}/test")
    public Object test(@PathVariable long id) {
        return ApiResponse.ok(calls.testProvider(id, Actor.current()));
    }

    @PutMapping("/admin/model-providers/{id}")
    public Object updateProvider(@PathVariable long id, @RequestBody JsonNode body) {
        return ApiResponse.ok(store.updateProvider(id, body, Actor.current()));
    }

    @PostMapping("/admin/model-providers/{id}/disable")
    public Object disableProvider(@PathVariable long id) {
        return ApiResponse.ok(store.disableProvider(id, Actor.current()));
    }

    @PostMapping("/admin/model-providers/{id}/credentials/rotate")
    public Object rotate(@PathVariable long id, @RequestBody JsonNode body) {
        return ApiResponse.ok(store.rotate(id, body, Actor.current()));
    }

    @PutMapping("/admin/model-providers/{id}/quota")
    public Object quota(@PathVariable long id, @RequestBody JsonNode body) {
        return ApiResponse.ok(store.configureQuota(id, body, Actor.current()));
    }

    @GetMapping("/admin/model-entries")
    public Object entries() {
        return ApiResponse.ok(store.entries(Actor.current()));
    }

    @PostMapping("/admin/model-entries")
    public Object entry(@RequestBody JsonNode body) {
        return ApiResponse.ok(store.createEntry(body, Actor.current()));
    }

    @PutMapping("/admin/model-entries/{id}")
    public Object updateEntry(@PathVariable long id, @RequestBody JsonNode body) {
        return ApiResponse.ok(store.updateEntry(id, body, Actor.current()));
    }

    @GetMapping("/admin/model-entries/{id}/metering")
    public Object metering(@PathVariable long id) {
        return ApiResponse.ok(store.calibrations(id, Actor.current()));
    }

    @GetMapping("/internal/model-entries/{id}")
    public Object internal(@PathVariable long id) {
        return ApiResponse.ok(store.entry(id, Actor.current(), true));
    }

    @PostMapping("/internal/model-bindings/freeze")
    public Object freeze(@RequestBody JsonNode body) {
        return ApiResponse.ok(store.freeze(body.path("modelEntryId").asLong(), Actor.current()));
    }

    @PostMapping("/internal/model-bindings/validate")
    public Object validate(@RequestBody JsonNode body) {
        store.validateBinding(body, Actor.current());
        return ApiResponse.ok(Map.of("valid", true));
    }

    @GetMapping("/internal/statistics")
    public Object stats() {
        return ApiResponse.ok(store.statistics(Actor.current()));
    }

    @PostMapping("/internal/statistics/query")
    public Object statistics(@RequestBody Map<String, Object> query) {
        return ApiResponse.ok(store.statistics(query.get("runIds"), Actor.current()));
    }

    @PostMapping("/internal/model/measure")
    public Object measure(@RequestBody JsonNode body) {
        return ApiResponse.ok(calls.measure(body, Actor.current()));
    }

    @PostMapping("/internal/model/chat")
    public Object chat(@RequestBody JsonNode body) {
        return ApiResponse.ok(calls.call(body, Actor.current(), null));
    }

    @PostMapping(
            value = "/internal/model/chat/stream",
            params = "!profile",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody JsonNode body) {
        calls.requireLegacyStreamAllowed();
        Actor actor = Actor.current();
        SseEmitter emitter = new SseEmitter(610000L);
        Thread.ofVirtual()
                .name("model-stream")
                .start(
                        () -> {
                            try {
                                var answer =
                                        calls.call(
                                                body,
                                                actor,
                                                text -> {
                                                    try {
                                                        emitter.send(
                                                                SseEmitter.event()
                                                                        .name("chunk")
                                                                        .data(
                                                                                Map.of(
                                                                                        "text",
                                                                                        text)));
                                                    } catch (Exception e) {
                                                        throw new IllegalStateException(
                                                                "流式接收方已断开", e);
                                                    }
                                                });
                                emitter.send(SseEmitter.event().name("final").data(answer));
                                emitter.complete();
                            } catch (Exception e) {
                                var error = cn.agentplatform.model.domain.ModelErrors.normalize(e);
                                try {
                                    emitter.send(
                                            SseEmitter.event()
                                                    .name("error")
                                                    .data(
                                                            Map.of(
                                                                    "code",
                                                                    error.code(),
                                                                    "message",
                                                                    error.getMessage(),
                                                                    "status",
                                                                    error.status())));
                                } catch (Exception ignored) {
                                }
                                emitter.complete();
                            }
                        });
        return emitter;
    }

    @PostMapping(value = "/internal/model/chat/stream", params = "profile",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void initialStream(jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        // The retained servlet frame is checked before protected body consumption or SSE setup.
        calls.requireInitialStreamEntry();
        var profiles = request.getParameterValues("profile");
        var refs = request.getParameterValues("materialRef");
        if (profiles == null || profiles.length != 1 || !"initial-chat-main-v1".equals(profiles[0])
                || refs == null || refs.length != 1 || refs[0].length() > 256
                || request.getParameterMap().size() != 2)
            throw new PlatformException(400, "MODEL_INPUT_INVALID", "模型输入选择器无效");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        var bytes = new java.io.ByteArrayOutputStream();
        var input = request.getInputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            checkReadDeadline(deadline);
            int read = input.read(buffer, 0, Math.min(buffer.length, 2097153 - bytes.size()));
            checkReadDeadline(deadline);
            if (read < 0) break;
            bytes.write(buffer, 0, read);
            if (bytes.size() > 2097152) throw new PlatformException(413, "MODEL_INPUT_INVALID", "模型输入超出大小限制");
        }
        try {
            var result = calls.initialStream(bytes.toByteArray(), refs[0], text -> {
                try { event(response, "chunk", Map.of("text", text)); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            event(response, "final", result);
        } catch (Exception e) {
            var normalized = cn.agentplatform.model.domain.ModelErrors.normalize(e);
            if (!response.isCommitted()) throw normalized;
            try { event(response, "error", Map.of("code", normalized.code(), "message", normalized.getMessage(), "status", normalized.status())); }
            catch (java.io.IOException disconnected) { /* The response stream is cancelled by initialStream's close. */ }
        }
    }

    private static void checkReadDeadline(long deadline) {
        // A blocked read also needs the deployment container's socket/upload timeout.
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new PlatformException(408, "MODEL_INPUT_TIMEOUT", "模型输入读取超时");
    }

    private static void event(jakarta.servlet.http.HttpServletResponse response, String name, Object value)
            throws java.io.IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("event:" + name + "\ndata:" + Json.write(value) + "\n\n");
        response.flushBuffer();
    }
}
