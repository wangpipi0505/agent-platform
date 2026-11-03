package cn.agentplatform.app.infra;

import cn.agentplatform.app.domain.SemanticBinding;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 语义绑定（{@code app.semantic_binding} + 不可变修订）的持久化。
 *
 * <p>并发协议与既有配置目录一致：先锁父行，再用 {@code current_rev} 做 CAS；修订只追加，
 * 父行只在追加成功时推进 current_rev。读路径永远返回父行当前状态与全部历史修订，调用方
 * 不能通过覆盖旧修订来"修正"已交付的引用。
 */
@Repository
public class SemanticBindingRepository {

    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public SemanticBindingRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
    }

    public boolean appExists(Actor actor, String appCode) {
        var rows = db.queryForList("select 1 from app.application where tenant_id = ? and code = ?",
                actor.tenantId(), appCode);
        return !rows.isEmpty();
    }

    /** 父行不存在时补建；已存在时不动，避免并发 create 覆盖状态。 */
    public void ensure(Actor actor, String appCode, String bindingId) {
        db.update("""
                insert into app.semantic_binding(tenant_id, app_code, binding_id, created_by)
                values (?, ?, ?, ?) on conflict(tenant_id, binding_id) do nothing
                """, actor.tenantId(), appCode, bindingId, actor.principalId());
    }

    public Optional<Map<String, Object>> view(Actor actor, String bindingId) {
        Map<String, Object> head = head(actor, bindingId);
        if (head == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> revisions = db.queryForList("""
                select binding_rev, release_space_code, release_id, release_version,
                       release_content_digest, extension_id, extension_digest, op_code,
                       skill_code, skill_version, skill_sha256, profiles::text as profiles,
                       declaration_sha256, created_by, created_at
                  from app.semantic_binding_revision
                 where tenant_id = ? and binding_id = ? order by binding_rev
                """, actor.tenantId(), bindingId).stream().map(SemanticBindingRepository::revisionView).toList();
        Map<String, Object> out = new LinkedHashMap<>(head);
        out.put("revisions", revisions);
        return Optional.of(out);
    }

    public List<Map<String, Object>> list(Actor actor, String appCode) {
        return db.queryForList("""
                select binding_id, app_code, current_rev, status, created_by, created_at, updated_at
                  from app.semantic_binding where tenant_id = ? and app_code = ? order by binding_id
                """, actor.tenantId(), appCode).stream().map(SemanticBindingRepository::headView).toList();
    }

    /** 追加一版修订；create 时 expectedRev=0，revise 时必须等于当前修订。 */
    public Map<String, Object> append(Actor actor, String appCode, SemanticBinding binding, int expectedRev,
            String requestId, Supplier<Map<String, Object>> response) {
        return transaction(() -> {
            Map<String, Object> head = headForUpdate(actor, binding.bindingId());
            if (head == null) {
                throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "语义绑定不存在: " + binding.bindingId());
            }
            if (!appCode.equals(String.valueOf(head.get("app_code")))) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "语义绑定不属于该应用: " + appCode);
            }
            int current = ((Number) head.get("current_rev")).intValue();
            if (expectedRev != current) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT",
                        "语义绑定修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
            }
            if (!"ACTIVE".equals(String.valueOf(head.get("status")))) {
                throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
                        "状态 " + head.get("status") + " 不允许追加语义绑定修订");
            }
            int rev = current + 1;
            try {
                db.update("""
                        insert into app.semantic_binding_revision(tenant_id, binding_id, binding_rev,
                          release_space_code, release_id, release_version, release_content_digest,
                          extension_id, extension_digest, op_code, skill_code, skill_version, skill_sha256,
                          profiles, declaration_sha256, created_by)
                        values (?, ?, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """, actor.tenantId(), binding.bindingId(), rev,
                        binding.releaseRef().spaceCode(), binding.releaseRef().releaseId().toString(),
                        binding.releaseRef().version(), binding.releaseRef().contentDigest(),
                        binding.extensionId(), binding.extensionDigest(), binding.opCode(),
                        binding.skill().skillCode(), binding.skill().skillVersion(), binding.skill().skillSha256(),
                        Json.write(binding.profiles()), binding.sha256(), actor.principalId());
            } catch (DuplicateKeyException e) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "语义绑定修订已存在，请重试");
            }
            int updated = db.update("""
                    update app.semantic_binding set current_rev = ?, updated_at = now()
                     where tenant_id = ? and binding_id = ? and current_rev = ? and status = 'ACTIVE'
                    """, rev, actor.tenantId(), binding.bindingId(), current);
            if (updated != 1) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "语义绑定已被并发修改，请重试");
            }
            return finish(response.get(), requestId);
        });
    }

    /** 停用只改父行状态，不删除历史；重复停用按状态迁移错误处理。 */
    public Map<String, Object> disable(Actor actor, String bindingId, int expectedRev, String requestId,
            Supplier<Map<String, Object>> response) {
        return transaction(() -> {
            Map<String, Object> head = headForUpdate(actor, bindingId);
            if (head == null) {
                throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "语义绑定不存在: " + bindingId);
            }
            int current = ((Number) head.get("current_rev")).intValue();
            if (expectedRev != current) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT",
                        "语义绑定修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
            }
            if (!"ACTIVE".equals(String.valueOf(head.get("status")))) {
                throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID", "语义绑定已处于停用状态");
            }
            int updated = db.update("""
                    update app.semantic_binding set status = 'DISABLED', updated_at = now()
                     where tenant_id = ? and binding_id = ? and current_rev = ? and status = 'ACTIVE'
                    """, actor.tenantId(), bindingId, current);
            if (updated != 1) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "语义绑定已被并发修改，请重试");
            }
            return finish(response.get(), requestId);
        });
    }

    private Map<String, Object> head(Actor actor, String bindingId) {
        var rows = db.queryForList("""
                select binding_id, app_code, current_rev, status, created_by, created_at, updated_at
                  from app.semantic_binding where tenant_id = ? and binding_id = ?
                """, actor.tenantId(), bindingId);
        return rows.isEmpty() ? null : headView(rows.get(0));
    }

    private Map<String, Object> headForUpdate(Actor actor, String bindingId) {
        var rows = db.queryForList("""
                select binding_id, app_code, current_rev, status, created_by, created_at, updated_at
                  from app.semantic_binding where tenant_id = ? and binding_id = ? for update
                """, actor.tenantId(), bindingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> headView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingId", row.get("binding_id"));
        out.put("appCode", row.get("app_code"));
        out.put("currentRev", row.get("current_rev"));
        out.put("status", row.get("status"));
        out.put("createdBy", row.get("created_by"));
        out.put("createdAt", String.valueOf(row.get("created_at")));
        out.put("updatedAt", String.valueOf(row.get("updated_at")));
        return out;
    }

    private static Map<String, Object> revisionView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingRev", row.get("binding_rev"));
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("spaceCode", row.get("release_space_code"));
        release.put("releaseId", String.valueOf(row.get("release_id")));
        release.put("version", row.get("release_version"));
        release.put("contentDigest", row.get("release_content_digest"));
        out.put("releaseRef", release);
        out.put("extensionId", row.get("extension_id"));
        out.put("extensionDigest", row.get("extension_digest"));
        out.put("opCode", row.get("op_code"));
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillCode", row.get("skill_code"));
        skill.put("skillVersion", row.get("skill_version"));
        skill.put("skillSha256", row.get("skill_sha256"));
        out.put("skill", skill);
        out.put("profiles", Json.read(String.valueOf(row.get("profiles")), List.class));
        out.put("declarationSha256", row.get("declaration_sha256"));
        out.put("createdBy", row.get("created_by"));
        out.put("createdAt", String.valueOf(row.get("created_at")));
        return out;
    }

    private static Map<String, Object> finish(Map<String, Object> view, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>(view);
        body.put("requestId", requestId);
        body.put("replayed", false);
        return body;
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return tx.execute(s -> work.get());
        } catch (CannotAcquireLockException | QueryTimeoutException e) {
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "语义绑定正被并发修改，请以同一 requestId 重试");
        }
    }
}
