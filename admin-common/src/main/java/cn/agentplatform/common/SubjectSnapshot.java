package cn.agentplatform.common;

import java.util.*;

/**
 * PLAT-12：验签后主体快照（方案 §4.12 主体模型中随身份固定的部分）。
 * guard、审计与出站身份注入共用同一份不可变快照；快照一经持有，后续 claims 变化不会漏入。
 * appCode/runId/credentialId 属每次调用的运行期上下文，随审计三元组记录，不进身份快照。
 */
public record SubjectSnapshot(
        String tenantId,
        String principalId,
        Set<String> roles,
        Map<String, Set<String>> attributes) {
    public SubjectSnapshot {
        if (tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank())
            throw new PlatformException(401, "SYS-AUTH-401", "身份缺少租户或用户");
        roles = Set.copyOf(roles == null ? Set.of() : roles);
        Map<String, Set<String>> safe = new HashMap<>();
        if (attributes != null) attributes.forEach((k, v) -> safe.put(k, Set.copyOf(v)));
        attributes = Map.copyOf(safe);
    }

    /**
     * 映射键：快照材料的不可逆 SHA-256。键与集合元素先排序再进 canonical JSON，
     * 保证同一主体无论集合迭代顺序如何都得到同一键（X-5 映射表的幂等判据）。
     */
    public String mappingKey() {
        Map<String, Object> material = new TreeMap<>();
        material.put("tenantId", tenantId);
        material.put("principalId", principalId);
        material.put("roles", roles.stream().sorted().toList());
        Map<String, Object> attrs = new TreeMap<>();
        attributes.forEach((k, v) -> attrs.put(k, v.stream().sorted().toList()));
        material.put("attributes", attrs);
        return Digest.sha256(Json.canonical(material));
    }
}
