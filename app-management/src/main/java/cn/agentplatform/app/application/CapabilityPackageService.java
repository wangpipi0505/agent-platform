package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.CapabilityPackage;
import cn.agentplatform.app.infra.CapabilityPackageRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;

@Service
public class CapabilityPackageService {
    private final CapabilityPackageRepository packages;

    public CapabilityPackageService(CapabilityPackageRepository packages) {
        this.packages = packages;
    }

    public Map<String, Object> importPackage(Map<String, Object> body) {
        var actor = Actor.current();
        actor.requireRole("EDITOR");
        // Intake has no execution authority. A caller cannot turn declaration storage into
        // app creation or silently supply an unverified binding plan.
        if (body.containsKey("applications") || body.containsKey("bindings") || body.containsKey("environment"))
            throw new PlatformException(409, "CAPABILITY_BINDING_REQUIRED", "技能绑定尚未完成，本入口仅登记能力包声明");
        if (!body.keySet().equals(Set.of("package")))
            throw new PlatformException(400, "INVALID_INPUT", "请求仅接受 package 字段");
        return packages.register(CapabilityPackage.parse(body.get("package")), actor);
    }
}
