package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.CapabilityPackageQueryRepository;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import java.util.*;
import org.springframework.stereotype.Service;

/** 查看者可查目录，编辑者/管理员沿既有管理面角色规则可读；读取不授予绑定或发布权限。 */
@Service
public class CapabilityPackageQueryService {
    private final CapabilityPackageQueryRepository packages;
    public CapabilityPackageQueryService(CapabilityPackageQueryRepository packages) { this.packages = packages; }

    private Actor reader() {
        var actor = Actor.current();
        if (!actor.roles().contains("EDITOR")) actor.requireRole("VIEWER");
        return actor;
    }
    public Map<String, Object> list(Map<String, String> filters) {
        var actor = reader();
        if (!Set.of("limit", "beforeId", "q", "ontologySpace", "ontologyRelease").containsAll(filters.keySet()))
            throw bad("目录检索参数不支持");
        long limit = positive(filters.getOrDefault("limit", "30")), before = Long.MAX_VALUE;
        if (limit > 100) throw bad("每页最多 100 条");
        if (filters.containsKey("beforeId")) before = positive(filters.get("beforeId"));
        for (String key : List.of("q", "ontologySpace", "ontologyRelease"))
            if (filters.getOrDefault(key, "").length() > 128) throw bad("目录检索条件过长");
        return packages.list(actor.tenantId(), (int) limit, before, filters);
    }
    public Map<String, Object> detail(long id) {
        var actor = reader();
        if (id < 1) throw bad("能力包版本标识无效");
        return packages.detail(actor.tenantId(), id);
    }
    private long positive(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw bad("目录页码或标识无效");
            long number = Long.parseLong(value);
            if (number < 1) throw bad("目录页码或标识无效");
            return number;
        } catch (NumberFormatException ex) { throw bad("目录页码或标识无效"); }
    }
    private PlatformException bad(String message) { return new PlatformException(400, "INVALID_INPUT", message); }
}
