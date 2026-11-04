package cn.agentplatform.app.api;
import cn.agentplatform.app.application.OnboardingSessions;
import cn.agentplatform.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/admin/onboarding-sessions")
public class OnboardingSessionsController {
    private final OnboardingSessions sessions;
    public OnboardingSessionsController(OnboardingSessions sessions){this.sessions=sessions;}
    @GetMapping public Object list(){return ApiResponse.ok(sessions.list());}
    @GetMapping("/templates") public Object templates(){return ApiResponse.ok(sessions.templates());}
    @GetMapping("/{sessionId}") public Object get(@PathVariable String sessionId){return ApiResponse.ok(sessions.get(sessionId));}
    @PostMapping public Object create(@RequestBody Map<String,Object> body){return ApiResponse.ok(sessions.create(body));}
    @PutMapping("/{sessionId}") public Object save(@PathVariable String sessionId,@RequestBody Map<String,Object> body){return ApiResponse.ok(sessions.save(sessionId,body));}
    @PostMapping("/{sessionId}/preview") public Object preview(@PathVariable String sessionId,@RequestBody Map<String,Object> body){return ApiResponse.ok(sessions.preview(sessionId,body));}
    @PostMapping("/{sessionId}/steps/{step}/execute") public Object execute(@PathVariable String sessionId,@PathVariable String step,@RequestBody Map<String,Object> body){return ApiResponse.ok(sessions.execute(sessionId,step,body));}
    @PostMapping("/{sessionId}/cancel") public Object cancel(@PathVariable String sessionId,@RequestBody Map<String,Object> body){return ApiResponse.ok(sessions.cancel(sessionId,body));}
}
