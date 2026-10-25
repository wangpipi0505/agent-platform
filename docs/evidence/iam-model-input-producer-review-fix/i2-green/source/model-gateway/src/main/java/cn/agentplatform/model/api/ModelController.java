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
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody JsonNode body) {
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
}
