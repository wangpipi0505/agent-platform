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
 * 体验描述的持久化：父表记状态与当前修订号，修订表只追加。
 *
 * <p>事务边界：单次写入是一个本地事务，先锁父行（串行化同一应用的体验修订），再插修订、再 CAS 父行。
 * 修订一旦写入不原地更新，因此"改了哪个字段"永远可以由 experience_rev 之间的差异回答。
 */
@Repository
public class AssistantExperienceRepository {

  public record Revision(String title, String welcomeText,
      List<Map<String, Object>> capabilityDescriptions, List<Map<String, Object>> promptRefs,
      Map<String, Object> inputPresentation, Map<String, Object> resultPresentation,
      List<String> helpRefs, String sha256) {}

  public record State(int currentRev, String status, boolean revisionExists) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public AssistantExperienceRepository(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(5);
  }

  public Optional<Map<String, Object>> view(Actor actor, String appCode) {
    return tx.execute(s -> {
      Map<String, Object> head = head(actor, appCode);
      if (head == null) {
        return Optional.empty();
      }
      Map<String, Object> out = new LinkedHashMap<>(head);
      out.put("revisions", revisions(actor, appCode));
      return Optional.of(out);
    });
  }

  public List<Map<String, Object>> all(Actor actor) {
    return db.queryForList("""
        select app_code, current_rev, status, created_by, created_at, updated_at
          from app.assistant_experience where tenant_id = ? order by app_code
        """, actor.tenantId()).stream().map(AssistantExperienceRepository::headView).toList();
  }

  public State state(Actor actor, String appCode, int rev) {
    var rows = db.queryForList("""
        select e.current_rev, e.status,
               exists(select 1 from app.assistant_experience_revision r
                       where r.tenant_id = e.tenant_id and r.app_code = e.app_code and r.experience_rev = ?) as revision_exists
          from app.assistant_experience e where e.tenant_id = ? and e.app_code = ?
        """, rev, actor.tenantId(), appCode);
    if (rows.isEmpty()) {
      return null;
    }
    var row = rows.get(0);
    return new State(((Number) row.get("current_rev")).intValue(), String.valueOf(row.get("status")),
        Boolean.TRUE.equals(row.get("revision_exists")));
  }

  /** 写一条不可变修订并把父行推进到该修订（调用方已校验 expectedRev 与状态）。 */
  public Map<String, Object> append(Actor actor, String appCode, Revision revision, int expectedRev,
      String requestId, Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> head = headForUpdate(actor, appCode);
      if (head == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
      }
      int current = ((Number) head.get("current_rev")).intValue();
      if (expectedRev != current) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT",
            "体验修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
      }
      String previousStatus = String.valueOf(head.get("status"));
      if (!java.util.Set.of("DRAFT", "APPROVED").contains(previousStatus)) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + head.get("status") + " 不允许新增体验修订");
      }
      int rev = current + 1;
      db.update("""
          insert into app.assistant_experience_revision(tenant_id, app_code, experience_rev, title, welcome_text,
            capability_descriptions, prompt_refs, input_presentation, result_presentation, help_refs,
            declaration_sha256, created_by)
          values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
          """, actor.tenantId(), appCode, rev, revision.title(), revision.welcomeText(),
          Json.write(revision.capabilityDescriptions()), Json.write(revision.promptRefs()),
          Json.write(revision.inputPresentation()), Json.write(revision.resultPresentation()),
          Json.write(revision.helpRefs()), revision.sha256(), actor.principalId());
      int updated = db.update("""
          update app.assistant_experience set current_rev = ?, status = 'DRAFT', updated_at = now()
           where tenant_id = ? and app_code = ? and current_rev = ? and status = ?
          """, rev, actor.tenantId(), appCode, current, previousStatus);
      if (updated != 1) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT", "体验修订已被并发修改，请重试");
      }
      Map<String, Object> body = new LinkedHashMap<>(response.get());
      body.put("requestId", requestId);
      body.put("replayed", false);
      return body;
    });
  }

  /** 状态迁移（批准/停用）：带 expectedRev 的 CAS，冲突即 409，不猜测调用方意图。 */
  public Map<String, Object> transition(Actor actor, String appCode, int expectedRev, String from, String to,
      String requestId, Supplier<Map<String, Object>> response) {
    return transaction(() -> {
      Map<String, Object> head = headForUpdate(actor, appCode);
      if (head == null) {
        throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
      }
      int current = ((Number) head.get("current_rev")).intValue();
      if (current != expectedRev) {
        throw new PlatformException(409, "CONFIG_REV_CONFLICT",
            "体验修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
      }
      int updated = db.update("""
          update app.assistant_experience set status = ?, updated_at = now()
           where tenant_id = ? and app_code = ? and current_rev = ? and status = ?
          """, to, actor.tenantId(), appCode, expectedRev, from);
      if (updated != 1) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "状态 " + head.get("status") + " 不能迁移到 " + to);
      }
      Map<String, Object> body = new LinkedHashMap<>(response.get());
      body.put("requestId", requestId);
      body.put("replayed", false);
      return body;
    });
  }

  public boolean appExists(Actor actor, String appCode) {
    var rows = db.queryForList("select 1 from app.application where tenant_id = ? and code = ?",
        actor.tenantId(), appCode);
    return !rows.isEmpty();
  }

  /** 父行不存在时补建（首次 create）；已存在时不动，避免并发 create 覆盖状态。 */
  public void ensure(Actor actor, String appCode) {
    db.update("""
        insert into app.assistant_experience(tenant_id, app_code, created_by)
        values (?, ?, ?) on conflict(tenant_id, app_code) do nothing
        """, actor.tenantId(), appCode, actor.principalId());
  }

  /** 提示词引用只能指向既有模板版本（配置只表达引用，不复制模板内容）。 */
  public boolean promptVersionExists(Actor actor, String templateCode, int version) {
    var rows = db.queryForList("""
        select 1 from app.prompt_template_version
         where tenant_id = ? and template_code = ? and version_no = ?
        """, actor.tenantId(), templateCode, version);
    return !rows.isEmpty();
  }

  private Map<String, Object> head(Actor actor, String appCode) {
    var rows = db.queryForList("""
        select app_code, current_rev, status, created_by, created_at, updated_at
          from app.assistant_experience where tenant_id = ? and app_code = ?
        """, actor.tenantId(), appCode);
    return rows.isEmpty() ? null : headView(rows.get(0));
  }

  /** 事务内锁定父行；这里保留库内列名（current_rev/status），CAS 判断直接读原始行。 */
  private Map<String, Object> headForUpdate(Actor actor, String appCode) {
    var rows = db.queryForList("""
        select app_code, current_rev, status, created_by, created_at, updated_at
          from app.assistant_experience where tenant_id = ? and app_code = ? for update
        """, actor.tenantId(), appCode);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private List<Map<String, Object>> revisions(Actor actor, String appCode) {
    return db.queryForList("""
        select experience_rev, title, welcome_text, capability_descriptions::text as capability_descriptions,
               prompt_refs::text as prompt_refs, input_presentation::text as input_presentation,
               result_presentation::text as result_presentation, help_refs::text as help_refs,
               declaration_sha256, created_by, created_at
          from app.assistant_experience_revision
         where tenant_id = ? and app_code = ? order by experience_rev
        """, actor.tenantId(), appCode).stream().map(AssistantExperienceRepository::revisionView).toList();
  }

  private static Map<String, Object> revisionView(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("experienceRev", row.get("experience_rev"));
    out.put("title", row.get("title"));
    out.put("welcomeText", row.get("welcome_text"));
    out.put("capabilityDescriptions",
        Json.read(String.valueOf(row.get("capability_descriptions")), List.class));
    out.put("promptRefs", Json.read(String.valueOf(row.get("prompt_refs")), List.class));
    out.put("inputPresentation",
        Json.read(String.valueOf(row.get("input_presentation")), Map.class));
    out.put("resultPresentation",
        Json.read(String.valueOf(row.get("result_presentation")), Map.class));
    out.put("helpRefs", Json.read(String.valueOf(row.get("help_refs")), List.class));
    out.put("declarationSha256", row.get("declaration_sha256"));
    out.put("createdBy", row.get("created_by"));
    out.put("createdAt", String.valueOf(row.get("created_at")));
    return out;
  }

  private static Map<String, Object> headView(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("appCode", row.get("app_code"));
    out.put("experienceRev", row.get("current_rev"));
    out.put("status", row.get("status"));
    out.put("createdBy", row.get("created_by"));
    out.put("createdAt", String.valueOf(row.get("created_at")));
    out.put("updatedAt", String.valueOf(row.get("updated_at")));
    return out;
  }

  private <T> T transaction(Supplier<T> work) {
    try {
      return tx.execute(s -> work.get());
    } catch (CannotAcquireLockException | QueryTimeoutException e) {
      throw new PlatformException(409, "CONFIG_REV_CONFLICT", "配置写入正被并发修改，请以同一 requestId 重试");
    } catch (DuplicateKeyException e) {
      throw new PlatformException(409, "CONFIG_REV_CONFLICT", "体验修订已存在，请重试");
    }
  }
}
