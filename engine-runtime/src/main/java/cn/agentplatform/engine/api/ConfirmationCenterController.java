package cn.agentplatform.engine.api;

import cn.agentplatform.common.*;
import cn.agentplatform.engine.application.ConfirmationCenterService;

import org.springframework.web.bind.annotation.*;

/**
 * B4-1（台账 §7.5）：确认中心待办角标端点（接口稿 §5.7）。网关/authority 登记照 S2-1
 * 目录行方式（admin-common agent-service-authority.json）；读取角色与四类口径见
 * {@link ConfirmationCenterService}。
 */
@RestController
public class ConfirmationCenterController {

    private final ConfirmationCenterService confirmationCenter;

    public ConfirmationCenterController(ConfirmationCenterService confirmationCenter) {
        this.confirmationCenter = confirmationCenter;
    }

    /** GET /admin/confirmation-center/counts?appCode=：四类未分页总数+total=Σ。 */
    @GetMapping("/admin/confirmation-center/counts")
    public Object counts(@RequestParam(value = "appCode", required = false) String appCode) {
        return ApiResponse.ok(confirmationCenter.counts(appCode, Actor.current()));
    }
}
