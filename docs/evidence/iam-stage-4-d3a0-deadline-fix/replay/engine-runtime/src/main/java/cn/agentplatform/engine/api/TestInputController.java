package cn.agentplatform.engine.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.engine.application.TestInputService;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/debug-inputs")
public class TestInputController {
    private final TestInputService inputs;

    public TestInputController(TestInputService inputs) {
        this.inputs = inputs;
    }

    @PostMapping
    public Object create(@RequestParam long appDraftId, @RequestBody JsonNode body) {
        return ApiResponse.ok(inputs.create(appDraftId, body));
    }

    @GetMapping
    public Object list(
            @RequestParam long appDraftId,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(inputs.list(appDraftId, afterId, limit));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) {
        return ApiResponse.ok(inputs.get(id));
    }

    @PutMapping("/{id}")
    public Object update(@PathVariable long id, @RequestBody JsonNode body) {
        return ApiResponse.ok(inputs.update(id, body));
    }

    @DeleteMapping("/{id}")
    public Object delete(@PathVariable long id, @RequestParam long revision) {
        inputs.delete(id, revision);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @PostMapping("/{id}/promote")
    public Object promote(@PathVariable long id, @RequestBody JsonNode body) {
        return ApiResponse.ok(inputs.promote(id, body));
    }
}
