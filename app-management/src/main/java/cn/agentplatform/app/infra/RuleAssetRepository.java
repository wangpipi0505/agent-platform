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
 * 规则资产（A3-3，app V123）的持久化：主档记状态与当前版本号，版本表组成不可变版本链。
 *
 * <p>事务边界：单次写入是一个本地事务，先锁主档行（串行化同一规则的版本推进），再写版本、再改主档。
 * 版本行一旦 PUBLISHED/ARCHIVED 由库层触发器禁改（immutable_rule_asset_version），因此"这条规则
 * 当时为什么拦人"永远可以由 (rule_code, version_no) 的差异回答。同一 scope 的 ENFORCED 互斥
 * 预检也在本仓库内完成：锁主档后查同 scope 的 ENABLED 强制规则，冲突在发布/启用前落地。
 */
@Repository
public class RuleAssetRepository {

  /** 主档行：管理面展示与状态机判断所需的最小字段。 */
  public record Head(String code, String name, String nature, String scopeJson, String status,
      int currentVersionNo) {}

  /** 版本行：content 保持 jsonb 文本，由调用方按需解析。 */
  public record Version(int versionNo, String contentJson, String sha256, String status,
      String publishedBy, String publishedAt) {}

  /** 互斥冲突条目：指明规则对（谁与谁在同一 scope 上冲突）。 */
  public record Conflict(String ruleCode, String conflictsWith, String conflictsWithName,
      String scopeJson, String detail) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public RuleAssetRepository(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(5);
  }

  // ---------- 查询 ----------

  public Optional<Head> head(Actor actor, String ruleCode) {
    var rows = db.queryForList("""
        select code, name, nature, scope::text as scope, status, current_version_no
          from app.rule_asset where tenant_id = ? and code = ?
        """, actor.tenantId(), ruleCode);
    return rows.isEmpty() ? Optional.empty() : Optional.of(head(rows.get(0)));
  }

  /** GET /admin/apps/{code}/rules：scope.app 等于该应用的规则（管理面按应用维护）。 */
  public List<Map<String, Object>> listByApp(Actor actor, String appCode) {
    return db.queryForList("""
        select code, name, nature, scope::text as scope, status, current_version_no, updated_at
          from app.rule_asset where tenant_id = ? and scope->>'app' = ? order by code
        """, actor.tenantId(), appCode).stream().map(RuleAssetRepository::headView).toList();
  }

  public List<Map<String, Object>> versions(Actor actor, String ruleCode) {
    return db.queryForList("""
        select version_no, content::text as content, sha256, status, published_by, published_at
          from app.rule_asset_version where tenant_id = ? and rule_code = ? order by version_no
        """, actor.tenantId(), ruleCode).stream().map(RuleAssetRepository::versionView).toList();
  }

  public Optional<Version> version(Actor actor, String ruleCode, int versionNo) {
    var rows = db.queryForList("""
        select version_no, content::text as content, sha256, status, published_by, published_at
          from app.rule_asset_version where tenant_id = ? and rule_code = ? and version_no = ?
        """, actor.tenantId(), ruleCode, versionNo);
    return rows.isEmpty() ? Optional.empty() : Optional.of(version(rows.get(0)));
  }

  public boolean appExists(Actor actor, String appCode) {
    var rows = db.queryForList("select 1 from app.application where tenant_id = ? and code = ?",
        actor.tenantId(), appCode);
    return !rows.isEmpty();
  }

  /**
   * 同一 scope 的 ENFORCED 互斥预检：返回与本规则（ENFORCED）同 scope 且 ENABLED 的其他强制规则。
   * scope 相等按 jsonb 语义比较（键序与空白不敏感）；GUIDANCE 不参与互斥。
   */
  public List<Conflict> enforcedConflicts(Actor actor, String ruleCode, String scopeJson) {
    var rows = db.queryForList("""
        select code, name, scope::text as scope from app.rule_asset
         where tenant_id = ? and nature = 'ENFORCED' and status = 'ENABLED'
           and code <> ? and scope = ?::jsonb
         order by code
        """, actor.tenantId(), ruleCode, scopeJson);
    return rows.stream()
        .map(r -> new Conflict(ruleCode, String.valueOf(r.get("code")), String.valueOf(r.get("name")),
            String.valueOf(r.get("scope")),
            "同一 scope 已存在启用的强制性规则，两者互斥"))
        .toList();
  }

  // ---------- 写入 ----------

  /** 新建规则主档 + 首个 DRAFT 版本（version_no=1）。 */
  public Map<String, Object> create(Actor actor, String code, String name, String nature,
      String scopeJson, String contentJson, String sha256,
      Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      db.update("""
          insert into app.rule_asset(tenant_id, code, name, nature, scope, status)
          values (?, ?, ?, ?, ?::jsonb, 'DRAFT')
          """, actor.tenantId(), code, name, nature, scopeJson);
      db.update("""
          insert into app.rule_asset_version(tenant_id, rule_code, version_no, content, sha256, status)
          values (?, ?, 1, ?::jsonb, ?, 'DRAFT')
          """, actor.tenantId(), code, contentJson, sha256);
      return response.get();
    });
  }

  /**
   * 保存草稿：当前版本仍是 DRAFT 时原地改写（触发器放行）；已是 PUBLISHED/ARCHIVED 时
   * 追加新版本并把主档推进到新版本号。返回当前版本号与其状态。
   */
  public Map<String, Object> saveDraft(Actor actor, String ruleCode, String contentJson,
      String sha256) {
    return transaction(() -> {
      Head head = headForUpdate(actor, ruleCode);
      if (head == null) {
        throw notFound(ruleCode);
      }
      int current = head.currentVersionNo();
      var currentStatus = db.queryForList("""
          select status from app.rule_asset_version
           where tenant_id = ? and rule_code = ? and version_no = ? for update
          """, actor.tenantId(), ruleCode, current);
      if (currentStatus.isEmpty()) {
        throw new PlatformException(409, "RULE_VERSION_CONFLICT",
            "规则版本链缺失当前版本 " + current + ": " + ruleCode);
      }
      int versionNo = current;
      if ("DRAFT".equals(String.valueOf(currentStatus.get(0).get("status")))) {
        db.update("""
            update app.rule_asset_version set content = ?::jsonb, sha256 = ?
             where tenant_id = ? and rule_code = ? and version_no = ?
            """, contentJson, sha256, actor.tenantId(), ruleCode, versionNo);
      } else {
        versionNo = current + 1;
        db.update("""
            insert into app.rule_asset_version(tenant_id, rule_code, version_no, content, sha256, status)
            values (?, ?, ?, ?::jsonb, ?, 'DRAFT')
            """, actor.tenantId(), ruleCode, versionNo, contentJson, sha256);
        db.update("""
            update app.rule_asset set current_version_no = ?, updated_at = now()
             where tenant_id = ? and code = ?
            """, versionNo, actor.tenantId(), ruleCode);
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("code", ruleCode);
      out.put("versionNo", versionNo);
      out.put("status", "DRAFT");
      out.put("sha256", sha256);
      return out;
    });
  }

  /**
   * 发布版本：CAS 把 DRAFT 版本置 PUBLISHED 并记录发布人与时间；主档状态在 DRAFT 时随发布
   * 转为 ENABLED（1109 §5.1"已启用的规则提供给智能体选用"），已被显式 DISABLED/ARCHIVED 的
   * 主档保持原状态（发布不隐式重新启用）。冲突条目由调用方先经 {@link #enforcedConflicts} 取得并阻断。
   */
  public Map<String, Object> publish(Actor actor, String ruleCode, int versionNo) {
    return transaction(() -> {
      Head head = headForUpdate(actor, ruleCode);
      if (head == null) {
        throw notFound(ruleCode);
      }
      var target = db.queryForList("""
          select status from app.rule_asset_version
           where tenant_id = ? and rule_code = ? and version_no = ? for update
          """, actor.tenantId(), ruleCode, versionNo);
      if (target.isEmpty()) {
        throw new PlatformException(404, "RULE_VERSION_NOT_FOUND",
            "版本不存在: " + ruleCode + "@" + versionNo);
      }
      int updated = db.update("""
          update app.rule_asset_version set status = 'PUBLISHED', published_by = ?, published_at = now()
           where tenant_id = ? and rule_code = ? and version_no = ? and status = 'DRAFT'
          """, actor.principalId(), actor.tenantId(), ruleCode, versionNo);
      if (updated != 1) {
        throw new PlatformException(409, "RULE_VERSION_CONFLICT",
            "版本 " + versionNo + " 不是草稿或已被发布: " + ruleCode);
      }
      if ("DRAFT".equals(head.status())) {
        db.update("update app.rule_asset set status = 'ENABLED', updated_at = now() "
            + "where tenant_id = ? and code = ?", actor.tenantId(), ruleCode);
      } else {
        db.update("update app.rule_asset set updated_at = now() "
            + "where tenant_id = ? and code = ?", actor.tenantId(), ruleCode);
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("code", ruleCode);
      out.put("versionNo", versionNo);
      out.put("status", "PUBLISHED");
      return out;
    });
  }

  /** 启停（POST enable|disable）：启用前调用方须已做过互斥预检；这里做状态机 CAS。 */
  public Map<String, Object> setStatus(Actor actor, String ruleCode, String to) {
    return transaction(() -> {
      Head head = headForUpdate(actor, ruleCode);
      if (head == null) {
        throw notFound(ruleCode);
      }
      if ("ENABLED".equals(to)) {
        var published = db.queryForList("""
            select 1 from app.rule_asset_version
             where tenant_id = ? and rule_code = ? and status = 'PUBLISHED'
            """, actor.tenantId(), ruleCode);
        if (published.isEmpty()) {
          throw new PlatformException(400, "RULE_NOT_PUBLISHED",
              "规则没有已发布版本，不能启用: " + ruleCode);
        }
      }
      int updated = db.update("""
          update app.rule_asset set status = ?, updated_at = now()
           where tenant_id = ? and code = ? and status = ?
          """, to, actor.tenantId(), ruleCode, "ENABLED".equals(to) ? "DISABLED" : "ENABLED");
      if (updated != 1) {
        throw new PlatformException(400, "RULE_STATE_TRANSITION_INVALID",
            "状态 " + head.status() + " 不能迁移到 " + to + ": " + ruleCode);
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("code", ruleCode);
      out.put("status", to);
      return out;
    });
  }

  // ---------- 内部 ----------

  private static PlatformException notFound(String ruleCode) {
    return new PlatformException(404, "RULE_REF_NOT_FOUND", "规则不存在: " + ruleCode);
  }

  private Head headForUpdate(Actor actor, String ruleCode) {
    var rows = db.queryForList("""
        select code, name, nature, scope::text as scope, status, current_version_no
          from app.rule_asset where tenant_id = ? and code = ? for update
        """, actor.tenantId(), ruleCode);
    return rows.isEmpty() ? null : head(rows.get(0));
  }

  private static Head head(Map<String, Object> row) {
    return new Head(String.valueOf(row.get("code")), String.valueOf(row.get("name")),
        String.valueOf(row.get("nature")), String.valueOf(row.get("scope")),
        String.valueOf(row.get("status")),
        ((Number) row.get("current_version_no")).intValue());
  }

  private static Map<String, Object> headView(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", row.get("code"));
    out.put("name", row.get("name"));
    out.put("nature", row.get("nature"));
    out.put("scope", Json.read(String.valueOf(row.get("scope")), Map.class));
    out.put("status", row.get("status"));
    out.put("currentVersionNo", row.get("current_version_no"));
    out.put("updatedAt", String.valueOf(row.get("updated_at")));
    return out;
  }

  private static Version version(Map<String, Object> row) {
    return new Version(((Number) row.get("version_no")).intValue(),
        String.valueOf(row.get("content")), String.valueOf(row.get("sha256")),
        String.valueOf(row.get("status")),
        row.get("published_by") == null ? null : String.valueOf(row.get("published_by")),
        row.get("published_at") == null ? null : String.valueOf(row.get("published_at")));
  }

  private static Map<String, Object> versionView(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("versionNo", row.get("version_no"));
    out.put("content", Json.read(String.valueOf(row.get("content")), Map.class));
    out.put("sha256", row.get("sha256"));
    out.put("status", row.get("status"));
    out.put("publishedBy", row.get("published_by"));
    out.put("publishedAt", row.get("published_at") == null ? null : String.valueOf(row.get("published_at")));
    return out;
  }

  private <T> T transaction(Supplier<T> work) {
    try {
      return tx.execute(s -> work.get());
    } catch (CannotAcquireLockException | QueryTimeoutException e) {
      throw new PlatformException(409, "RULE_VERSION_CONFLICT", "规则写入正被并发修改，请以同一 requestId 重试");
    } catch (DuplicateKeyException e) {
      throw new PlatformException(409, "RULE_ALREADY_EXISTS", "规则已存在，请换一个 code");
    }
  }
}
