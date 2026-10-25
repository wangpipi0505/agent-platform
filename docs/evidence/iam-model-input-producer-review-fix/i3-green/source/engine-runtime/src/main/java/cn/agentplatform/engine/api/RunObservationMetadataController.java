package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.RunObservationMetadataService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerMapping;

@RestController
public final class RunObservationMetadataController {
    private final RunObservationMetadataService service;
    public RunObservationMetadataController(RunObservationMetadataService service){this.service=service;}
    @GetMapping("/internal/runs/{id}/observation-metadata")
    public Object metadata(@PathVariable String id,HttpServletRequest request)throws IOException {
        // getQueryString preserves an empty raw query; parameter maps lose a bare question mark.
        if(request.getDispatcherType()!=DispatcherType.REQUEST||!"GET".equals(request.getMethod())
                ||!"/internal/runs/{id}/observation-metadata".equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                ||!request.getRequestURI().equals(request.getContextPath()+"/internal/runs/"+id+"/observation-metadata")
                ||request.getQueryString()!=null||request.getContentLengthLong()>0
                ||request.getInputStream().read()!=-1)
            throw new PlatformException(400,"INVALID_RUN_OBSERVATION_REQUEST","运行观察查询请求无效");
        return ApiResponse.ok(service.metadata(id));
    }
}
