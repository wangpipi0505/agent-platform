package cn.agentplatform.app.infra;

import cn.agentplatform.app.domain.IdentityMapping;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.SubjectSnapshot;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.*;

/** 身份映射表 V107 的唯一读写口：UPSERT 判据是 mapping_key，而非"有行就跳过"。 */
@Repository
public class IdentityMappingRepository {
    private final JdbcTemplate db;

    public IdentityMappingRepository(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * UPSERT（语义已在 V107 定死）：同 (tenant, principal) 只保留一行当前映射；
     * mapping_key 相同的幂等重放不推进 revision；不同快照整行替换且 revision 单调 +1。
     * 事务边界由调用方（application @Transactional）圈定：先改后插再回读，冲突行交给唯一约束。
     */
    public IdentityMapping upsert(SubjectSnapshot subject, String createdBy) {
        String key = subject.mappingKey();
        String tenantId = subject.tenantId();
        String principalId = subject.principalId();
        String snapshot = Json.write(snapshotMaterial(subject));
        int changed =
                db.update(
                        "update app.identity_mapping set mapping_key=?, subject_snapshot=?::jsonb,"
                                + " revision=revision+1, updated_at=now()"
                                + " where tenant_id=? and principal_id=? and mapping_key is distinct from ?",
                        key,
                        snapshot,
                        tenantId,
                        principalId,
                        key);
        if (changed == 0)
            db.update(
                    "insert into app.identity_mapping(tenant_id,principal_id,mapping_key,subject_snapshot,created_by)"
                            + " values(?,?,?,?::jsonb,?) on conflict (tenant_id,principal_id) do nothing",
                    tenantId,
                    principalId,
                    key,
                    snapshot,
                    createdBy);
        var existing = find(tenantId, principalId);
        // 回读在自己事务内进行：更新、插入或冲突行三者必有其一可见，缺失即存储层异常
        if (existing.isEmpty())
            throw new PlatformException(409, "IDENTITY_MAPPING_WRITE_CONFLICT", "身份映射写入未能生效");
        return existing.get();
    }

    public Optional<IdentityMapping> find(String tenantId, String principalId) {
        var rows =
                db.query(
                        "select mapping_key,subject_snapshot,revision,created_at,updated_at"
                                + " from app.identity_mapping where tenant_id=? and principal_id=?",
                        (r, n) -> toMapping(tenantId, principalId, r.getString("mapping_key"), r.getString("subject_snapshot"), r.getLong("revision"), r.getTimestamp("created_at"), r.getTimestamp("updated_at")),
                        tenantId,
                        principalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private IdentityMapping toMapping(
            String tenantId,
            String principalId,
            String mappingKey,
            String snapshot,
            long revision,
            Timestamp createdAt,
            Timestamp updatedAt) {
        JsonNode json = Json.read(snapshot);
        Map<String, Set<String>> attributes = new LinkedHashMap<>();
        json.path("attributes")
                .fields()
                .forEachRemaining(
                        e -> {
                            Set<String> values = new LinkedHashSet<>();
                            e.getValue().forEach(v -> values.add(v.asText()));
                            attributes.put(e.getKey(), values);
                        });
        Set<String> roles = new LinkedHashSet<>();
        json.path("roles").forEach(v -> roles.add(v.asText()));
        var subject =
                new SubjectSnapshot(
                        json.path("tenantId").asText(),
                        json.path("principalId").asText(),
                        roles,
                        attributes);
        return new IdentityMapping(
                tenantId,
                principalId,
                mappingKey,
                subject,
                revision,
                createdAt.toInstant(),
                updatedAt.toInstant());
    }

    /** 落库形状与 mappingKey() 摘要材料同构：键排序、集合元素排序，保证 JSON 往返不漂移映射键。 */
    private Map<String, Object> snapshotMaterial(SubjectSnapshot subject) {
        Map<String, Object> material = new TreeMap<>();
        material.put("tenantId", subject.tenantId());
        material.put("principalId", subject.principalId());
        material.put("roles", subject.roles().stream().sorted().toList());
        Map<String, Object> attrs = new TreeMap<>();
        subject.attributes().forEach((k, v) -> attrs.put(k, v.stream().sorted().toList()));
        material.put("attributes", attrs);
        return material;
    }
}
