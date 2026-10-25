package cn.agentplatform.app.api;

import cn.agentplatform.app.application.DraftMetadataService;
import cn.agentplatform.common.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
public class DraftMetadataController {
    private final DraftMetadataService drafts;

    public DraftMetadataController(DraftMetadataService drafts) {
        this.drafts = drafts;
    }

    @GetMapping("/internal/app-drafts/{id}")
    public Object get(@PathVariable long id) {
        return ApiResponse.ok(drafts.get(id));
    }
}
