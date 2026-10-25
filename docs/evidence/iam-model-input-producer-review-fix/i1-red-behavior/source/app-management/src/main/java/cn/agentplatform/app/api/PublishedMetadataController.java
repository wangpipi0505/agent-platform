package cn.agentplatform.app.api;

import cn.agentplatform.app.application.PublishedMetadataService;
import cn.agentplatform.app.domain.PublishedMetadata;
import cn.agentplatform.common.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class PublishedMetadataController {
    private final PublishedMetadataService metadata;
    public PublishedMetadataController(PublishedMetadataService metadata){this.metadata=metadata;}
    @GetMapping("/internal/apps/{code}/published-metadata")
    public ApiResponse<PublishedMetadata> published(@PathVariable String code,@RequestParam Map<String,String> parameters){
        if(!parameters.isEmpty())throw new PlatformException(400,"INVALID_METADATA_QUERY","元数据查询不接受额外参数");
        return ApiResponse.ok(metadata.published(code));
    }
}
