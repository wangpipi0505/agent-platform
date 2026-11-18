package cn.agentplatform.engine.api;

import cn.agentplatform.common.ApiResponse;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.application.GuideDirectory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * R-18（A3-2 engine 侧 GUIDE 目录注册）：engine 侧目录查询端点——GET /v1/skills?kind=GUIDE
 * 读{@link GuideDirectory}本地缓存（启动拉取/按需 refresh），使 engine 侧有数据可查。
 *
 * <p>等价口径（台账卡面允许、如实登记）：网关未登记 /v1/skills 前缀（本端点为 engine 直连
 * 只读面，等价于卡面"engine 侧 GET /v1/skills?kind=GUIDE 有数据"）；经网关的技能目录面
 * 仍是 skill 服务 {@code GET /admin/skills?kind=GUIDE}（已可达、kind 过滤现成）。kind 仅
 * 支持 GUIDE——TOOL 双轨的工具面走既有工具环（/internal/skills 精确版本链），不在本目录。
 */
@RestController
public class GuideDirectoryController {

    private final GuideDirectory directory;

    public GuideDirectoryController(GuideDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/v1/skills")
    public Object skills(@RequestParam(required = false) String kind) {
        // 目录面只读；登录主体即可查（缓存为公共视图投影，无密文），kind 白名单收口。
        Actor.current();
        if (kind == null || kind.isBlank()) kind = "GUIDE";
        if (!"GUIDE".equals(kind))
            throw new PlatformException(
                    400, "GUIDE_KIND_ONLY", "engine 侧目录仅承载 kind=GUIDE（TOOL 走工具环/技能链），收到: " + kind);
        return ApiResponse.ok(directory.view());
    }
}
