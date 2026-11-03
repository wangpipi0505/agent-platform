package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 连接档案的授权（grant）存储：批准 ≠ 授权。
 *
 * 语义：
 * - 一使用方一有效行：库上有 `connection_profile_grant_active_uk` 部分唯一索引（revoked_at is null），
 *   撤销是"置 revoked_at"而不是删除，历史授权可审计。
 * - 外键钉到 connection_profile_version：不能给不存在的修订授权，也不能在有授权时把修订改掉。
 * - grant 行不保存凭据；凭据仍然只出现在环境绑定修订的 secret_ref 里。
 *
 * 这里不做事务编排：单条 insert/update 各自原子，调用方（{@code ConnectionGrants}）负责"先校验后写"的顺序，
 * 并发重复授予由唯一索引兜底并翻译成 409，不靠应用层先查后写。
 */
@Repository
public class ConnectionGrantRepository {

    private final JdbcTemplate db;

    public ConnectionGrantRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** 有效授权（revoked_at is null），按授予时间倒序；resolve 与目录读都只认这一份真相。 */
    public List<Map<String, Object>> activeGrants(Actor actor, String profileId, int profileRev) {
        return db.query("select id,grantee,allowed_uses::text,granted_by,granted_at from app.connection_profile_grant"
                        + " where tenant_id=? and profile_id=? and profile_rev=? and revoked_at is null"
                        + " order by granted_at desc, id desc",
                (rs, n) -> view(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        null, null),
                actor.tenantId(), profileId, profileRev);
    }

    public Optional<Map<String, Object>> activeGrant(Actor actor, String profileId, int profileRev, String grantee) {
        var rows = db.query("select id,grantee,allowed_uses::text,granted_by,granted_at"
                        + " from app.connection_profile_grant"
                        + " where tenant_id=? and profile_id=? and profile_rev=? and grantee=? and revoked_at is null",
                (rs, n) -> view(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        null, null),
                actor.tenantId(), profileId, profileRev, grantee);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<String, Object> grant(Actor actor, String profileId, int profileRev, String grantee,
            List<String> allowedUses) {
        try {
            var rows = db.query("insert into app.connection_profile_grant"
                            + " (tenant_id,profile_id,profile_rev,grantee,allowed_uses,granted_by)"
                            + " values (?,?,?,?,?::jsonb,?)"
                            + " returning id,grantee,allowed_uses::text,granted_by,granted_at",
                    (rs, n) -> view(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), null, null),
                    actor.tenantId(), profileId, profileRev, grantee, Json.write(allowedUses),
                    actor.principalId());
            return rows.get(0);
        } catch (DuplicateKeyException e) {
            // 并发授予同一使用方：唯一索引是最终裁决，交给调用方按"同载荷回放/异载荷冲突"处理。
            throw new PlatformException(409, "CONFIG_REV_CONFLICT",
                    "该使用方已持有此修订的有效授权，请先读取再决定是否修改: " + grantee);
        }
    }

    /** 撤销返回该授权行的最终形态；不存在或已撤销一律 404（不泄漏"曾经授权过谁"）。 */
    public Map<String, Object> revoke(Actor actor, String profileId, int profileRev, long grantId, String reason) {
        var rows = db.query("update app.connection_profile_grant set revoked_at=now(),revoke_reason=?"
                        + " where tenant_id=? and profile_id=? and profile_rev=? and id=? and revoked_at is null"
                        + " returning id,grantee,allowed_uses::text,granted_by,granted_at,revoked_at::text,revoke_reason",
                (rs, n) -> view(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)),
                reason, actor.tenantId(), profileId, profileRev, grantId);
        if (rows.isEmpty())
            throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "有效授权不存在: " + grantId);
        return rows.get(0);
    }

    private static Map<String, Object> view(long id, String grantee, String uses, String grantedBy, String grantedAt,
            String revokedAt, String revokeReason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grantId", id);
        m.put("grantee", grantee);
        m.put("allowedUses", Json.read(uses, Object.class));
        m.put("grantedBy", grantedBy);
        m.put("grantedAt", grantedAt);
        m.put("revokedAt", revokedAt);
        m.put("revokeReason", revokeReason);
        return m;
    }
}
