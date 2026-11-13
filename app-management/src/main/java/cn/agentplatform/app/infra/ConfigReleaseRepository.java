package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 配置发布（ConfigRelease）的持久化：不可变组合快照 + 单作用域激活指针。
 *
 * <p>激活是这里唯一会改状态的操作，也是最需要并发正确的一步：先锁激活指针行，再让旧发布退位（SUPERSEDED）、
 * 新发布上位（ACTIVE），最后推进指针的 activation_rev。指针与发布状态在同一事务里，不会出现"指针指向
 * 一个还是 APPROVED 的发布"。
 */
@Repository
public class ConfigReleaseRepository {

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public ConfigReleaseRepository(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(5);
  }

  /** 应用版本的 candidate_sha256 只从发布记录取，客户端自报的摘要一律不采信。 */
  public Map<String, Object> appVersion(Actor actor, String appCode, int version) {
    var rows = db.queryForList("""
        select version_no, candidate_sha256, published_at from app.app_version
         where tenant_id = ? and app_code = ? and version_no = ?
        """, actor.tenantId(), appCode, version);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void insert(Actor actor, Map<String, Object> release) {
    // 发布行与语义引用行必须同事务落库：只写一半会让发布钉住一个并不存在的引用，
    // 之后的校验/激活会把错误推迟到更难解释的时点。
    transaction(() -> {
      try {
        db.update("""
            insert into app.integration_config_release(tenant_id, release_id, app_code, app_version,
              candidate_sha256, experience_rev, integration_profile_id, integration_profile_rev,
              connection_profile_refs, activation_scope, digest, created_by,
              manifest_revision, manifest_digest)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """, actor.tenantId(), release.get("releaseId"), release.get("appCode"),
            release.get("appVersion"), release.get("candidateSha256"), release.get("experienceRev"),
            release.get("integrationProfileId"), release.get("integrationProfileRev"),
            Json.write(release.get("connectionProfileRefs")), release.get("activationScope"),
            release.get("digest"), actor.principalId(),
            release.get("manifestRevision"), release.get("manifestDigest"));
      } catch (DuplicateKeyException e) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT",
            "配置发布已存在: " + release.get("releaseId"));
      }
      List<Map<String, Object>> refs = semanticRefs(release.get("semanticBindings"));
      for (int i = 0; i < refs.size(); i++) {
        db.update("""
            insert into app.integration_config_release_semantic_ref(tenant_id, release_id, ref_index,
              binding_id, binding_rev)
            values (?, ?, ?, ?, ?)
            """, actor.tenantId(), release.get("releaseId"), i,
            refs.get(i).get("bindingId"), refs.get(i).get("bindingRev"));
      }
      return null;
    });
  }

  /** 发布时钉住的 manifest 头修订（A3-1，库稿 §4.6 扩列）：应用没有 manifest 时为 null（两列同空）。 */
  public record ManifestPin(int revision, String digest) {}

  /**
   * 服务端取当前 manifest 头修订供发布固化（不信任客户端自报，同 candidate_sha256 口径）。
   * 修订链不可变，钉住后该 (app_code, revision) 的装配内容永远可回查；char(64) 右补空格需裁剪。
   */
  public ManifestPin manifestPin(Actor actor, String appCode) {
    var rows = db.queryForList("""
        select revision, trim(trailing from digest) as digest from app.workspace_manifest
         where tenant_id = ? and app_code = ? order by revision desc limit 1
        """, actor.tenantId(), appCode);
    return rows.isEmpty() ? null
        : new ManifestPin(((Number) rows.get(0).get("revision")).intValue(),
            String.valueOf(rows.get(0).get("digest")));
  }

  public Map<String, Object> find(Actor actor, String releaseId) {
    var rows = db.queryForList("""
        select release_id, app_code, app_version, candidate_sha256, experience_rev,
               integration_profile_id, integration_profile_rev, connection_profile_refs::text as connection_profile_refs,
               activation_scope, status, digest, created_by, created_at, updated_at,
               manifest_revision, trim(trailing from manifest_digest) as manifest_digest
          from app.integration_config_release where tenant_id = ? and release_id = ?
        """, actor.tenantId(), releaseId);
    return rows.isEmpty() ? null : withSemantic(actor, view(rows.get(0)));
  }

  public List<Map<String, Object>> list(Actor actor, String appCode) {
    return db.queryForList("""
        select release_id, app_code, app_version, candidate_sha256, experience_rev,
               integration_profile_id, integration_profile_rev, connection_profile_refs::text as connection_profile_refs,
               activation_scope, status, digest, created_by, created_at, updated_at,
               manifest_revision, trim(trailing from manifest_digest) as manifest_digest
          from app.integration_config_release where tenant_id = ? and app_code = ?
         order by created_at desc
        """, actor.tenantId(), appCode).stream()
        .map(ConfigReleaseRepository::view).map(row -> withSemantic(actor, row)).toList();
  }

  /** 状态迁移（校验/批准/停用）：CAS 到 from 状态，冲突返回 409 而不是覆盖。 */
  public Map<String, Object> transition(Actor actor, String releaseId, String from, String to,
      String requestId, Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> row = lockRelease(actor, releaseId);
      if (row == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
      }
      if (!from.equals(String.valueOf(row.get("status")))) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + row.get("status") + " 不能迁移到 " + to);
      }
      db.update("""
          update app.integration_config_release set status = ?, updated_at = now()
           where tenant_id = ? and release_id = ? and status = ?
          """, to, actor.tenantId(), releaseId, from);
      return finish(response, requestId);
    });
  }

  /**
   * 激活：指针行先 upsert 再 for update 上锁；同一作用域旧 ACTIVE 退位。作用域内只有一个 ACTIVE 由
   * 指针主键保证，发布行的状态只是它的投影。
   */
  public Map<String, Object> activate(Actor actor, String releaseId, String requestId,
      Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> row = lockRelease(actor, releaseId);
      if (row == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
      }
      if (!"APPROVED".equals(String.valueOf(row.get("status")))) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + row.get("status") + " 不能激活，只有 APPROVED 可以激活");
      }
      String appCode = String.valueOf(row.get("app_code"));
      String scope = String.valueOf(row.get("activation_scope"));
      db.update("""
          insert into app.integration_config_release_activation(tenant_id, app_code, scope, release_id, activated_by)
          values (?, ?, ?, ?, ?)
          on conflict(tenant_id, app_code, scope) do nothing
          """, actor.tenantId(), appCode, scope, releaseId, actor.principalId());
      var pointer = db.queryForList("""
          select release_id, activation_rev from app.integration_config_release_activation
           where tenant_id = ? and app_code = ? and scope = ? for update
          """, actor.tenantId(), appCode, scope).get(0);
      String previous = String.valueOf(pointer.get("release_id"));
      if (!previous.equals(releaseId)) {
        db.update("""
            update app.integration_config_release set status = 'SUPERSEDED', updated_at = now()
             where tenant_id = ? and release_id = ? and status = 'ACTIVE'
            """, actor.tenantId(), previous);
      }
      db.update("""
          update app.integration_config_release set status = 'ACTIVE', updated_at = now()
           where tenant_id = ? and release_id = ? and status = 'APPROVED'
          """, actor.tenantId(), releaseId);
      db.update("""
          update app.integration_config_release_activation
             set release_id = ?, activation_rev = activation_rev + 1, activated_by = ?, activated_at = now()
           where tenant_id = ? and app_code = ? and scope = ?
          """, releaseId, actor.principalId(), actor.tenantId(), appCode, scope);
      return finish(response, requestId);
    });
  }

  /** 停用：ACTIVE → DISABLED，并清空指针（不留"指向已停用发布"的指针）。 */
  public Map<String, Object> disable(Actor actor, String releaseId, String requestId,
      Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> row = lockRelease(actor, releaseId);
      if (row == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
      }
      String status = String.valueOf(row.get("status"));
      if (!"ACTIVE".equals(status) && !"APPROVED".equals(status)) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + status + " 不能停用");
      }
      db.update("""
          update app.integration_config_release set status = 'DISABLED', updated_at = now()
           where tenant_id = ? and release_id = ?
          """, actor.tenantId(), releaseId);
      db.update("""
          delete from app.integration_config_release_activation
           where tenant_id = ? and app_code = ? and scope = ? and release_id = ?
          """, actor.tenantId(), row.get("app_code"), row.get("activation_scope"), releaseId);
      return finish(response, requestId);
    });
  }

  /** 对外体验接口用：作用域当前激活的发布（不含草稿）。 */
  public Map<String, Object> active(Actor actor, String appCode, String scope) {
    var rows = db.queryForList("""
        select r.release_id, r.app_code, r.app_version, r.candidate_sha256, r.experience_rev,
               r.integration_profile_id, r.integration_profile_rev,
               r.connection_profile_refs::text as connection_profile_refs,
               r.activation_scope, r.status, r.digest, r.created_by, r.created_at, r.updated_at
          from app.integration_config_release_activation a
          join app.integration_config_release r
            on r.tenant_id = a.tenant_id and r.release_id = a.release_id
         where a.tenant_id = ? and a.app_code = ? and a.scope = ? and r.status = 'ACTIVE'
        """, actor.tenantId(), appCode, scope);
    return rows.isEmpty() ? null : withSemantic(actor, view(rows.get(0)));
  }

  /** 对外能力清单以"应用版本实际引用的技能"为准，文案不能承诺引擎没有的能力。 */
  public Set<String> skills(Actor actor, String appCode, int appVersion) {
    return new java.util.LinkedHashSet<>(db.queryForList("""
        select skill_code from app.skill_reference
         where tenant_id = ? and app_code = ? and app_version_no = ? order by skill_code
        """, actor.tenantId(), appCode, appVersion).stream()
        .map(row -> String.valueOf(row.get("skill_code"))).toList());
  }

  /** 只回已发布模板版本：草稿模板不进入对外体验。 */
  public Map<String, Object> publishedPrompt(Actor actor, String templateCode, int version) {
    var rows = db.queryForList("""
        select t.name as title, v.variables::text as variables
          from app.prompt_template_version v
          join app.prompt_template t on t.tenant_id = v.tenant_id and t.code = v.template_code
         where v.tenant_id = ? and v.template_code = ? and v.version_no = ? and v.status = 'PUBLISHED'
        """, actor.tenantId(), templateCode, version);
    if (rows.isEmpty()) {
      return null;
    }
    Map<String, Object> row = rows.get(0);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("title", row.get("title"));
    out.put("variables", Json.read(String.valueOf(row.get("variables")), List.class));
    return out;
  }

  private Map<String, Object> lockRelease(Actor actor, String releaseId) {
    var rows = db.queryForList("""
        select release_id, app_code, app_version, candidate_sha256, experience_rev,
               integration_profile_id, integration_profile_rev, connection_profile_refs::text as connection_profile_refs,
               activation_scope, status, digest, created_by, created_at, updated_at
          from app.integration_config_release where tenant_id = ? and release_id = ? for update
        """, actor.tenantId(), releaseId);
    // 锁行后直接用库内列名做状态/CAS 判断，投影（view）只用于对外响应。
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("releaseId", row.get("release_id"));
    out.put("appCode", row.get("app_code"));
    out.put("appVersion", row.get("app_version"));
    out.put("candidateSha256", row.get("candidate_sha256"));
    out.put("experienceRev", row.get("experience_rev"));
    out.put("integrationProfileId", row.get("integration_profile_id"));
    out.put("integrationProfileRev", row.get("integration_profile_rev"));
    out.put("connectionProfileRefs",
        Json.read(String.valueOf(row.get("connection_profile_refs")), List.class));
    out.put("activationScope", row.get("activation_scope"));
    out.put("status", row.get("status"));
    out.put("digest", row.get("digest"));
    // A3-1：发布钉住的 manifest 头修订（成对列；无 manifest 的存量发布同空）。
    out.put("manifestRevision", row.get("manifest_revision"));
    out.put("manifestDigest", row.get("manifest_digest"));
    out.put("createdBy", row.get("created_by"));
    out.put("createdAt", String.valueOf(row.get("created_at")));
    out.put("updatedAt", String.valueOf(row.get("updated_at")));
    return out;
  }

  /** 语义引用只回 bindingId/bindingRev；扩展、技能与本体四元组由绑定修订表回查。 */
  private Map<String, Object> withSemantic(Actor actor, Map<String, Object> row) {
    row.put("semanticBindings", refs(actor, String.valueOf(row.get("releaseId"))));
    return row;
  }

  private List<Map<String, Object>> refs(Actor actor, String releaseId) {
    return db.queryForList("""
        select binding_id, binding_rev from app.integration_config_release_semantic_ref
         where tenant_id = ? and release_id = ? order by ref_index
        """, actor.tenantId(), releaseId).stream().map(row -> {
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("bindingId", row.get("binding_id"));
          out.put("bindingRev", row.get("binding_rev"));
          return out;
        }).toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> semanticRefs(Object raw) {
    return raw instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  private Map<String, Object> finish(Supplier<Map<String, Object>> response, String requestId) {
    Map<String, Object> body = new LinkedHashMap<>(response.get());
    body.put("requestId", requestId);
    body.put("replayed", false);
    return body;
  }

  private <T> T transaction(Supplier<T> work) {
    try {
      return tx.execute(s -> work.get());
    } catch (CannotAcquireLockException | QueryTimeoutException e) {
      throw new PlatformException(409, "CONFIG_REV_CONFLICT", "配置写入正被并发修改，请以同一 requestId 重试");
    }
  }
}
