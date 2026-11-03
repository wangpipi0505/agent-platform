package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 接入档案（IntegrationProfile）的持久化：父表记状态与当前修订号，修订表只追加。
 *
 * <p>与连接档案的区别是"面向谁"：这里描述某个业务软件以哪个接入等级（L0~L3）暴露给哪个应用，
 * 因此环境、来源白名单与上下文声明都属于修订内容，改一次就是一个新 profileRev。
 */
@Repository
public class IntegrationProfileRepository {

  public record Revision(String bizSystemCode, String environment, String mode,
      List<String> allowedOrigins, Map<String, Object> contextSchema, String credentialPolicyRef,
      Map<String, Object> experienceRef, String sha256) {}

  public record State(int currentRev, String status, boolean revisionExists) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public IntegrationProfileRepository(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(5);
  }

  public Map<String, Object> create(Actor actor, String profileId, String appCode, Revision revision,
      String requestId, Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      db.update("""
          insert into app.integration_profile(tenant_id, profile_id, app_code, created_by)
          values (?, ?, ?, ?)
          """, actor.tenantId(), profileId, appCode, actor.principalId());
      insertRevision(actor, profileId, 1, revision);
      db.update("""
          update app.integration_profile set current_rev = 1, updated_at = now()
           where tenant_id = ? and profile_id = ?
          """, actor.tenantId(), profileId);
      return finish(response, requestId);
    });
  }

  public Map<String, Object> revise(Actor actor, String profileId, int expectedRev, Revision revision,
      String requestId, Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> head = headForUpdate(actor, profileId);
      if (head == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入档案不存在: " + profileId);
      }
      int current = ((Number) head.get("current_rev")).intValue();
      if (current != expectedRev) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT",
            "接入档案修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
      }
      if (!"DRAFT".equals(String.valueOf(head.get("status")))) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + head.get("status") + " 不允许新增接入档案修订");
      }
      int rev = current + 1;
      insertRevision(actor, profileId, rev, revision);
      int updated = db.update("""
          update app.integration_profile set current_rev = ?, updated_at = now()
           where tenant_id = ? and profile_id = ? and current_rev = ? and status = 'DRAFT'
          """, rev, actor.tenantId(), profileId, current);
      if (updated != 1) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT", "接入档案修订已被并发修改，请重试");
      }
      return finish(response, requestId);
    });
  }

  public Map<String, Object> transition(Actor actor, String profileId, int expectedRev, String from,
      String to, String requestId, Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> head = headForUpdate(actor, profileId);
      if (head == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入档案不存在: " + profileId);
      }
      int current = ((Number) head.get("current_rev")).intValue();
      if (current != expectedRev) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT",
            "接入档案修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
      }
      int updated = db.update("""
          update app.integration_profile set status = ?, updated_at = now()
           where tenant_id = ? and profile_id = ? and current_rev = ? and status = ?
          """, to, actor.tenantId(), profileId, expectedRev, from);
      if (updated != 1) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + head.get("status") + " 不能迁移到 " + to);
      }
      return finish(response, requestId);
    });
  }

  public Optional<Map<String, Object>> view(Actor actor, String profileId) {
    Map<String, Object> head = head(actor, profileId);
    if (head == null) {
      return Optional.empty();
    }
    Map<String, Object> out = new LinkedHashMap<>(head);
    out.put("revisions", db.queryForList("""
        select profile_rev, biz_system_code, environment, mode, allowed_origins::text as allowed_origins,
               context_schema::text as context_schema, credential_policy_ref, experience_ref::text as experience_ref,
               declaration_sha256, created_by, created_at
          from app.integration_profile_revision
         where tenant_id = ? and profile_id = ? order by profile_rev
        """, actor.tenantId(), profileId).stream().map(IntegrationProfileRepository::revisionView).toList());
    return Optional.of(out);
  }

  public List<Map<String, Object>> all(Actor actor, String appCode) {
    String sql = appCode == null || appCode.isBlank()
        ? "select profile_id, app_code, current_rev, status, created_by, created_at, updated_at"
            + " from app.integration_profile where tenant_id = ? order by profile_id"
        : "select profile_id, app_code, current_rev, status, created_by, created_at, updated_at"
            + " from app.integration_profile where tenant_id = ? and app_code = ? order by profile_id";
    Object[] args = appCode == null || appCode.isBlank()
        ? new Object[] {actor.tenantId()} : new Object[] {actor.tenantId(), appCode};
    return db.queryForList(sql, args).stream().map(IntegrationProfileRepository::headView).toList();
  }

  public State state(Actor actor, String profileId, int rev) {
    var rows = db.queryForList("""
        select p.current_rev, p.status,
               exists(select 1 from app.integration_profile_revision r
                       where r.tenant_id = p.tenant_id and r.profile_id = p.profile_id and r.profile_rev = ?) as revision_exists
          from app.integration_profile p where p.tenant_id = ? and p.profile_id = ?
        """, rev, actor.tenantId(), profileId);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.get(0);
    return new State(((Number) row.get("current_rev")).intValue(), String.valueOf(row.get("status")),
        Boolean.TRUE.equals(row.get("revision_exists")));
  }

  public Map<String, Object> revision(Actor actor, String profileId, int rev) {
    var rows = db.queryForList("""
        select profile_rev, biz_system_code, environment, mode, allowed_origins::text as allowed_origins,
               context_schema::text as context_schema, credential_policy_ref, experience_ref::text as experience_ref,
               declaration_sha256, created_by, created_at
          from app.integration_profile_revision
         where tenant_id = ? and profile_id = ? and profile_rev = ?
        """, actor.tenantId(), profileId, rev);
    return rows.isEmpty() ? null : revisionView(rows.get(0));
  }

  public boolean appExists(Actor actor, String appCode) {
    return !db.queryForList("select 1 from app.application where tenant_id = ? and code = ?",
        actor.tenantId(), appCode).isEmpty();
  }

  private void insertRevision(Actor actor, String profileId, int rev, Revision revision) {
    db.update("""
        insert into app.integration_profile_revision(tenant_id, profile_id, profile_rev, biz_system_code,
          environment, mode, allowed_origins, context_schema, credential_policy_ref, experience_ref,
          declaration_sha256, created_by)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?)
        """, actor.tenantId(), profileId, rev, revision.bizSystemCode(), revision.environment(),
        revision.mode(), Json.write(revision.allowedOrigins()), Json.write(revision.contextSchema()),
        revision.credentialPolicyRef(), Json.write(revision.experienceRef()), revision.sha256(),
        actor.principalId());
  }

  private Map<String, Object> finish(Supplier<Map<String, Object>> response, String requestId) {
    Map<String, Object> body = new LinkedHashMap<>(response.get());
    body.put("requestId", requestId);
    body.put("replayed", false);
    return body;
  }

  private Map<String, Object> head(Actor actor, String profileId) {
    var rows = db.queryForList("""
        select profile_id, app_code, current_rev, status, created_by, created_at, updated_at
          from app.integration_profile where tenant_id = ? and profile_id = ?
        """, actor.tenantId(), profileId);
    return rows.isEmpty() ? null : headView(rows.get(0));
  }

  private Map<String, Object> headForUpdate(Actor actor, String profileId) {
    var rows = db.queryForList("""
        select profile_id, app_code, current_rev, status, created_by, created_at, updated_at
          from app.integration_profile where tenant_id = ? and profile_id = ? for update
        """, actor.tenantId(), profileId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Map<String, Object> headView(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("profileId", row.get("profile_id"));
    out.put("appCode", row.get("app_code"));
    out.put("profileRev", row.get("current_rev"));
    out.put("status", row.get("status"));
    out.put("createdBy", row.get("created_by"));
    out.put("createdAt", String.valueOf(row.get("created_at")));
    out.put("updatedAt", String.valueOf(row.get("updated_at")));
    return out;
  }

  private static Map<String, Object> revisionView(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("profileRev", row.get("profile_rev"));
    out.put("bizSystemCode", row.get("biz_system_code"));
    out.put("environment", row.get("environment"));
    out.put("mode", row.get("mode"));
    out.put("allowedOrigins", Json.read(String.valueOf(row.get("allowed_origins")), List.class));
    out.put("contextSchema", Json.read(String.valueOf(row.get("context_schema")), Map.class));
    out.put("credentialPolicyRef", row.get("credential_policy_ref"));
    out.put("experienceRef", Json.read(String.valueOf(row.get("experience_ref")), Map.class));
    out.put("declarationSha256", row.get("declaration_sha256"));
    out.put("createdBy", row.get("created_by"));
    out.put("createdAt", String.valueOf(row.get("created_at")));
    return out;
  }

  private <T> T transaction(Supplier<T> work) {
    try {
      return tx.execute(s -> work.get());
    } catch (CannotAcquireLockException | QueryTimeoutException e) {
      throw new PlatformException(409, "CONFIG_REV_CONFLICT", "配置写入正被并发修改，请以同一 requestId 重试");
    } catch (DuplicateKeyException e) {
      throw new PlatformException(409, "CONFIG_REV_CONFLICT", "接入档案已存在: 请换一个 profileId");
    }
  }
}
