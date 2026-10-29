package cn.agentplatform.app.application;

import cn.agentplatform.app.domain.CapabilityBinding;
import cn.agentplatform.app.infra.CapabilityPackageRepository;
import cn.agentplatform.app.infra.CapabilitySkillClient;
import cn.agentplatform.common.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CapabilityPackageBindingService {
    private final CapabilityPackageRepository packages;
    private final CapabilitySkillClient skills;
    public CapabilityPackageBindingService(CapabilityPackageRepository packages, CapabilitySkillClient skills) {
        this.packages = packages; this.skills = skills;
    }

    public Map<String, Object> resolve(long id, Map<String, Object> body) {
        var actor = Actor.current(); actor.requireRole("EDITOR");
        var plan = CapabilityBinding.plan(body);
        String planSha = Digest.sha256(Json.canonical(plan));
        var stored = packages.bindingSource(id, actor.tenantId());
        String packageSha = stored.get("sha256").toString();
        if ("RESOLVED".equals(stored.get("bindingStatus")))
            return packages.bindingReceipt(stored, planSha, true);
        var selected = CapabilityBinding.select(AppService.object(stored.get("declaration")), plan);
        var bindings = new ArrayList<Map<String, Object>>();
        var versions = new HashMap<String, Integer>();
        long deadline = System.nanoTime() + 5_000_000_000L;
        // 远端读取全部结束后才进入 PG 写事务；不得拿本地行锁等待网络或产生部分绑定。
        for (var tool : selected.tools()) {
            var ref = selected.reference(tool);
            String code = AppService.text(ref, "code");
            Object number = ref.get("version");
            if (!(number instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1)
                throw new PlatformException(400, "INVALID_INPUT", "技能版本必须是精确正整数");
            int version = ((Number) number).intValue();
            Integer previous = versions.putIfAbsent(code, version);
            if (previous != null && previous != version)
                throw new PlatformException(409, "SKILL_VERSION_CONFLICT", "同一技能不能选择不同版本");
            var actual = skills.version(code, version, actor, deadline);
            bindings.add(selected.mapping(tool, ref, actual));
        }
        var mapping = new LinkedHashMap<String, Object>();
        mapping.put("environment", plan.get("environment"));
        mapping.put("environmentDeclaration", selected.environmentDeclaration());
        mapping.put("routeProfileId", plan.get("routeProfileId"));
        mapping.put("bindings", bindings);
        return packages.resolveBinding(id, actor, packageSha, planSha, mapping);
    }
}
