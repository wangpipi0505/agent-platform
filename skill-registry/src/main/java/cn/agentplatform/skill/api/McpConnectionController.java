package cn.agentplatform.skill.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.skill.application.McpConnectionRegistry;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MCP 连接档案 API（接口稿 §5.6 MCP 表；主方案 §6.3）。ADMIN 面：连接登记/配置变更
 * （revision 纪律）、按次建连测试（具体失败类别）、发现（完整批次串行发布目录）、
 * 目录与差异查询、差异审核生成技能草稿。发现为同步按次语义（首期），无 202 异步受理。
 */
@RestController
public class McpConnectionController {
    private final McpConnectionRegistry registry;

    public McpConnectionController(McpConnectionRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/admin/mcp-connections")
    public Object create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(registry.register(body));
    }

    /** 配置变更：端点/凭据/传输任一变更 revision+1（主方案 §6.3 修订绑定纪律）。 */
    @PutMapping("/admin/mcp-connections/{id}")
    public Object update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(registry.updateConfig(id, body));
    }

    @GetMapping("/admin/mcp-connections/{id}")
    public Object get(@PathVariable String id) {
        return ApiResponse.ok(registry.connection(id));
    }

    @PostMapping("/admin/mcp-connections/{id}/test")
    public Object test(@PathVariable String id) {
        return ApiResponse.ok(registry.test(id));
    }

    @PostMapping("/admin/mcp-connections/{id}/discover")
    public Object discover(@PathVariable String id) {
        return ApiResponse.ok(registry.discover(id));
    }

    @GetMapping("/admin/mcp-connections/{id}/tools")
    public Object tools(@PathVariable String id, @RequestParam(defaultValue = "false") boolean diff) {
        return ApiResponse.ok(registry.tools(id, diff));
    }

    @PostMapping("/admin/mcp-connections/{id}/tools/review")
    public Object review(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(registry.review(id, body));
    }
}
