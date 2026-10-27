package cn.agentplatform.app.domain;

import cn.agentplatform.common.SubjectSnapshot;

import java.time.Instant;

/** 身份映射的领域行：tenant+principal 绑定一份当前 subject 快照；revision 随快照变化单调递增。 */
public record IdentityMapping(
        String tenantId,
        String principalId,
        String mappingKey,
        SubjectSnapshot subject,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
    public IdentityMapping {
        if (tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank())
            throw new IllegalArgumentException("IDENTITY_MAPPING_KEY_REQUIRED");
        // 映射键必须与快照自洽：键漂移或快照指向别的主体都属脏数据，装配期即拒绝
        if (mappingKey == null || !mappingKey.equals(subject.mappingKey()))
            throw new IllegalArgumentException("IDENTITY_MAPPING_KEY_MISMATCH");
        if (!tenantId.equals(subject.tenantId()) || !principalId.equals(subject.principalId()))
            throw new IllegalArgumentException("IDENTITY_MAPPING_SUBJECT_MISMATCH");
        if (revision < 1) throw new IllegalArgumentException("IDENTITY_MAPPING_REVISION_INVALID");
    }
}
