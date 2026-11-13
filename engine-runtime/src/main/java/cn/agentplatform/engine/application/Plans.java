package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * T2-3（台账 §7.3）：计划 API——修订追加（baseRevision CAS）/确认（去重+有效期）/
 * execute 五项受理校验/修订读。权威口径=主方案 §5.6（角色/去重/有效期三稿统一）。
 *
 * <p>确认端点=计划版本级（不依赖 run/node）；去重 UNIQUE(tenant, confirmed_by,
 * confirm_request_id)——同号同参（同版本同 digest）重放返回原凭据不追加 confirm_seq，
 * 同号异参 409 REQUEST_REPLAY；凭据绑定 plan_revision+confirmed_digest，旧版本确认
 * 天然不继承（凭据与版本行绑定）。execute 五项：任务归属、digest 一致、VALID、
 * 存在确认凭据（最新序）、凭据在有效期内——未确认/过期 409 PLAN_NOT_CONFIRMED。
 */
@Service
public class Plans {

  private final TraceStore store;

  public Plans(TraceStore store) {
    this.store = store;
  }

  /** 修订追加：baseRevision CAS——过期 409 PLAN_REVISION_CONFLICT；旧凭据不继承（新行无确认）。 */
  public Map<String, Object> appendRevision(
      long taskId, JsonNode req, Actor actor) {
    var task = ownedTask(taskId, actor);
    String planId = req.path("planId").asText("");
    if (planId.isBlank()) throw new PlatformException(400, "PLAN_ID_REQUIRED", "计划标识必填");
    if (!req.path("baseRevision").isNumber())
      throw new PlatformException(400, "PLAN_BASE_REVISION_REQUIRED", "baseRevision 必填（CAS 基线）");
    int baseRevision = req.path("baseRevision").asInt();
    int headRevision = headRevision(taskId, planId);
    if (headRevision != baseRevision)
      throw new PlatformException(
              409,
              "PLAN_REVISION_CONFLICT",
              "修订基线过期：当前头修订 " + headRevision + "，请求基线 " + baseRevision);
    int newRevision = headRevision + 1;
    JsonNode goalSpec =
            req.path("patch").isObject()
                    ? req.path("patch")
                    : req.path("supplement").isObject() ? req.path("supplement") : Json.object();
    String digest =
            cn.agentplatform.engine.application.Tasks.Digest.sha256(
                    Json.canonical(
                            Map.of(
                                    "v",
                                    "PLAN_REVISION_V1",
                                    "planId",
                                    planId,
                                    "revision",
                                    newRevision,
                                    "goalSpec",
                                    goalSpec)));
    store.atomic(
            () -> {
              store.db()
                      .update(
                              "insert into engine.plan_revision(tenant_id,plan_id,revision,owner_task_id,"
                                  + "base_revision,schema_version,goal_spec,validation_status,digest,created_by)"
                                  + " values(?,?,?,?,?,'1.0',?::jsonb,'VALID',?,?)",
                              actor.tenantId(),
                              planId,
                              newRevision,
                              taskId,
                              baseRevision,
                              Json.write(goalSpec),
                              digest,
                              actor.principalId());
              // 结构化校验器（T2-4 交付完整 DAG/参数/能力/权限校验）——本卡校验=goal_spec 形状
              insertPlanSteps(newRevision, planId, actor.tenantId(), goalSpec);
              return null;
            });
    return Map.of(
            "planId", planId,
            "revision", newRevision,
            "digest", digest,
            "validationStatus", "VALID",
            "diagnostics", List.of(),
            "pendingInputs", List.of());
  }

  /** 计划版本级确认：同号同参重放原凭据；同号异参 409 REQUEST_REPLAY；digest 不符 409。 */
  public Map<String, Object> confirm(
      long taskId, String planId, int revision, JsonNode req, Actor actor) {
    ownedTask(taskId, actor);
    var revisionRow = revisionRow(taskId, planId, revision, actor.tenantId());
    String digest = req.path("digest").asText("");
    if (!digest.equals(String.valueOf(revisionRow.get("digest"))))
      throw new PlatformException(409, "PLAN_DIGEST_CHANGED", "digest 与固化版本不符");
    String requestId = req.path("requestId").asText("");
    if (requestId.isBlank())
      throw new PlatformException(400, "CONFIRM_REQUEST_INVALID", "确认必须提供 requestId");
    // 去重：UNIQUE(tenant, confirmed_by, confirm_request_id)——同号同参重放原凭据
    var prior =
            store.db()
                    .queryForList(
                            "select * from engine.plan_confirmation where tenant_id=? and confirmed_by=?"
                                + " and confirm_request_id=?",
                            actor.tenantId(),
                            actor.principalId(),
                            requestId);
    if (!prior.isEmpty()) {
      var row = prior.getFirst();
      if (TraceStore.number(row, "plan_revision_id") == TraceStore.number(revisionRow, "id")
              && digest.equals(String.valueOf(row.get("confirmed_digest"))))
        return confirmationView(row, planId, revision);
      throw new PlatformException(409, "REQUEST_REPLAY", "同一确认请求号已用于另一版本或 digest");
    }
    if (!"VALID".equals(String.valueOf(revisionRow.get("validation_status"))))
      throw new PlatformException(409, "PLAN_NOT_VALID", "所选版本校验未通过（INVALID/PENDING），不可确认");
    int confirmSeq =
            store.db()
                    .queryForObject(
                            "select coalesce(max(confirm_seq),0)+1 from engine.plan_confirmation"
                                + " where plan_revision_id=?",
                            Integer.class,
                            TraceStore.number(revisionRow, "id"));
    OffsetDateTime expires =
            OffsetDateTime.now().plusHours(defaultExpiryHours());
    store.db()
            .update(
                    "insert into engine.plan_confirmation(plan_revision_id,confirm_seq,tenant_id,"
                        + "confirm_request_id,confirmed_by,confirmed_at,expires_at,confirmed_digest)"
                        + " values(?,?,?,?,?,now(),?,?)",
                    TraceStore.number(revisionRow, "id"),
                    confirmSeq,
                    actor.tenantId(),
                    requestId,
                    actor.principalId(),
                    expires,
                    digest);
    return Map.of(
            "planId", planId,
            "revision", revision,
            "confirmSeq", confirmSeq,
            "confirmedBy", actor.principalId(),
            "expiresAt", expires.toString(),
            "digest", digest);
  }

  /** execute 五项受理校验（主方案 §5.6）：未确认/过期 409 PLAN_NOT_CONFIRMED。 */
  public Map<String, Object> execute(long taskId, JsonNode req, Actor actor) {
    var task = ownedTask(taskId, actor);
    String planId = req.path("planId").asText("");
    int revision = req.path("revision").asInt(0);
    String digest = req.path("digest").asText("");
    var revisionRow = revisionRow(taskId, planId, revision, actor.tenantId()); // ①任务归属
    if (!digest.equals(String.valueOf(revisionRow.get("digest")))) // ②digest 一致
      throw new PlatformException(409, "PLAN_DIGEST_CHANGED", "执行请求 digest 与固化版本不符");
    if (!"VALID".equals(String.valueOf(revisionRow.get("validation_status")))) // ③VALID
      throw new PlatformException(409, "PLAN_NOT_CONFIRMED", "所选版本未确认或凭据已过期，请重新确认");
    var credentials =
            store.db()
                    .queryForList(
                            "select * from engine.plan_confirmation where plan_revision_id=?"
                                + " order by confirm_seq desc limit 1",
                            TraceStore.number(revisionRow, "id"));
    if (credentials.isEmpty()) // ④存在确认凭据（最新序）
      throw new PlatformException(409, "PLAN_NOT_CONFIRMED", "所选版本未确认，请先确认（或重新确认）");
    var credential = credentials.getFirst();
    if (!digest.equals(String.valueOf(credential.get("confirmed_digest"))))
      throw new PlatformException(409, "PLAN_DIGEST_CHANGED", "确认凭据与所选 digest 不一致");
    OffsetDateTime expiresAt =
            expiresOf(credential.get("expires_at"));
    if (expiresAt.isBefore(OffsetDateTime.now())) // ⑤凭据在有效期内
      throw new PlatformException(409, "PLAN_NOT_CONFIRMED", "确认凭据已过期，请重新确认所选版本");
    // 执行任务（purpose=EXECUTE）继承来源任务工具上界/会话关联/发布快照——T2-5 步骤映射消费
    String executionTaskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    store.atomic(
            () -> {
              store.db()
                      .update(
                              "insert into engine.task(public_id,tenant_id,principal_id,app_code,"
                                  + "config_release_id,config_digest,mode,purpose,goal,input_ref,input_digest,"
                                  + "session_id,state,request_id,request_digest,tool_policy,selected_plan_revision_id,role,parent_task_id)"
                                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                              executionTaskId,
                              actor.tenantId(),
                              actor.principalId(),
                              task.get("app_code"),
                              task.get("config_release_id"),
                              task.get("config_digest"),
                              task.get("mode"),
                              "EXECUTE",
                              task.get("goal"),
                              "task-input/" + executionTaskId,
                              task.get("input_digest"),
                              task.get("session_id"),
                              "ACCEPTED",
                              req.path("requestId").asText("exec-" + executionTaskId),
                              String.valueOf(task.get("request_digest")),
                              task.get("tool_policy"),
                              TraceStore.number(revisionRow, "id"),
                              "PLANNED_EXEC",
                              taskId);
              store.db()
                      .update(
                              "update engine.task set state='RUNNING',updated_at=now() where id=? and state in ('PLAN_READY','NEEDS_INPUT')",
                              taskId);
              return null;
            });
    return Map.of(
            "executionTaskId", executionTaskId,
            "pinnedRevision", revision,
            "pinnedDigest", digest);
  }

  /** 修订读（含历史修订确认状态与授权——评审十轮 P1-1：按该修订计算 canConfirm/canExecute）。 */
  public Map<String, Object> revisionDetail(
      long taskId, String planId, int revision, Actor actor) {
    ownedTask(taskId, actor);
    var row = revisionRow(taskId, planId, revision, actor.tenantId());
    var view = new LinkedHashMap<String, Object>();
    view.put("planId", planId);
    view.put("revision", revision);
    view.put("digest", row.get("digest"));
    view.put("validationStatus", row.get("validation_status"));
    var credentials =
            store.db()
                    .queryForList(
                            "select * from engine.plan_confirmation where plan_revision_id=?"
                                + " order by confirm_seq desc limit 1",
                            TraceStore.number(row, "id"));
    if (credentials.isEmpty()) {
      view.put("confirmation", Map.of("state", "UNCONFIRMED"));
      view.put("canConfirm", true);
      view.put("canExecute", false);
    } else {
      var c = credentials.getFirst();
      OffsetDateTime expiresAt =
              expiresOf(c.get("expires_at"));
      boolean expired = expiresAt.isBefore(OffsetDateTime.now());
      view.put(
              "confirmation",
              Map.of(
                      "state", expired ? "CONFIRMED_EXPIRED" : "CONFIRMED_VALID",
                      "confirmedBy", c.get("confirmed_by"),
                      "expiresAt", c.get("expires_at"),
                      "seq", c.get("confirm_seq")));
      view.put("canConfirm", expired);
      view.put("canExecute", !expired);
    }
    return view;
  }

  private int headRevision(long taskId, String planId) {
    Integer head =
            store.db()
                    .queryForObject(
                            "select coalesce(max(revision),0) from engine.plan_revision"
                                + " where owner_task_id=? and plan_id=? and tenant_id=(select tenant_id from engine.task where id=?)",
                            Integer.class,
                            taskId,
                            planId,
                            taskId);
    return head == null ? 0 : head;
  }

  private Map<String, Object> revisionRow(long taskId, String planId, int revision, String tenant) {
    var rows =
            store.db()
                    .queryForList(
                            "select * from engine.plan_revision where owner_task_id=? and plan_id=?"
                                + " and revision=? and tenant_id=?",
                            taskId,
                            planId,
                            revision,
                            tenant);
    if (rows.isEmpty())
      throw new PlatformException(404, "PLAN_REVISION_NOT_FOUND", "计划修订不存在");
    return rows.getFirst();
  }

  /** 结构化步骤落库（T2-4 交付完整校验器）：goal_spec.steps 数组照 plan_step 列映射。 */
  private void insertPlanSteps(int revision, String planId, String tenant, JsonNode goalSpec) {
    var revisionId =
            store.db()
                    .queryForObject(
                            "select id from engine.plan_revision where tenant_id=? and plan_id=? and revision=?",
                            Long.class,
                            tenant,
                            planId,
                            revision);
    for (JsonNode step : goalSpec.path("steps")) {
      store.db()
              .update(
                      "insert into engine.plan_step(plan_revision_id,step_id,depends_on,kind,target_ref,input_bindings,required,failure_policy)"
                          + " values(?,?,?::jsonb,?,?,?::jsonb,?,?::jsonb)",
                      revisionId,
                      step.path("stepId").asText(),
                      Json.write(step.path("dependsOn").isArray() ? step.path("dependsOn") : Json.tree(List.of())),
                      step.path("kind").asText("AGENT"),
                      step.path("targetRef").asText("agent:default@1"),
                      Json.write(step.path("inputBindings").isObject() ? step.path("inputBindings") : Json.object()),
                      !step.path("required").isBoolean() || step.path("required").asBoolean(),
                      Json.write(step.path("failurePolicy").isObject() ? step.path("failurePolicy") : Json.object()));
    }
  }

  private Map<String, Object> confirmationView(
      Map<String, Object> row, String planId, int revision) {
    return Map.of(
            "planId", planId,
            "revision", revision,
            "confirmSeq", row.get("confirm_seq"),
            "confirmedBy", row.get("confirmed_by"),
            "expiresAt", String.valueOf(row.get("expires_at")),
            "digest", String.valueOf(row.get("confirmed_digest")));
  }

  private int defaultExpiryHours() {
    // 凭据有效期沿用 InlineApproval 语义（默认与 ActionPlan 一致，入部署配置）——首版默认 4h
    return 4;
  }

  private Map<String, Object> ownedTask(long taskId, Actor actor) {
    var rows =
            store.db()
                    .queryForList(
                            "select * from engine.task where id=? and tenant_id=? and principal_id=?",
                            taskId,
                            actor.tenantId(),
                            actor.principalId());
    if (rows.isEmpty())
      throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在或无权读取");
    return rows.getFirst();
  }

  /** PG timestamptz 读取（JDBC Timestamp/String 双形态容错）。 */
  private static java.time.OffsetDateTime expiresOf(Object value) {
    if (value instanceof java.time.OffsetDateTime odt) return odt;
    if (value instanceof java.sql.Timestamp ts)
      return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
    String text = String.valueOf(value).replace(" ", "T");
    return java.time.OffsetDateTime.parse(text.length() <= 10 ? text + "T00:00:00Z" : text
                    + (text.endsWith("Z") || text.contains("+") ? "" : "Z"));
  }
}
