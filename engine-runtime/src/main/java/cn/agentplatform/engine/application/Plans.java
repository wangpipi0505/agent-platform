package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.harness.plan.PlanValidator;
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

  /**
   * 修订追加：baseRevision CAS——过期 409 PLAN_REVISION_CONFLICT；旧凭据不继承（新行无确认）。
   *
   * <p>R-1（返工卡）：固化前接 {@link PlanValidator#validate}（T2-4 校验器原无调用点——
   * 本卡接线）——校验顺序照 v1 §5.2：DAG（环/自依赖/未知依赖）→必填参数→类型→能力引用→权限。
   * 校验通过维持既有 VALID 行为不变（plan_step 步骤照列映射落库）；任一失败落
   * validation_status='INVALID'，逐条诊断（path/code/message）写入 plan_revision.diagnostics
   * （V230 既有列，NULL=未产生）并随响应返回（键形状不变：validationStatus/diagnostics）——
   * INVALID 修订 confirm 409 PLAN_NOT_VALID、确认中心 confirmable 投影只计 VALID 头修订。
   * plan_step 是 VALID 结构的投影：INVALID 修订不落步骤行（goal_spec 与 diagnostics 已在
   * 不可变修订行留痕，如实登记）。
   */
  public Map<String, Object> appendRevision(
      long taskId, JsonNode req, Actor actor) {
    return store.atomic(() -> appendLocked(taskId, req, actor));
  }

  private Map<String, Object> appendLocked(long taskId, JsonNode req, Actor actor) {
    var task = lockOwnedTask(taskId, actor);
    String planId = req.path("planId").asText("");
    if (planId.isBlank() || planId.length() > 64) throw new PlatformException(400, "PLAN_ID_REQUIRED", "计划标识必填");
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
    // R-1：结构化校验器（T2-4 交付完整 DAG/参数/能力/权限校验）——能力引用范围=任务
    // tool_policy.allowedToolRefs 的 skill: 引用（NULL/缺省=继承发布范围，校验器跳过能力检查）
    var validation = PlanValidator.validate(goalSpec, allowedSkills(task));
    String status = validation.valid() ? "VALID" : "INVALID";
    store.db()
            .update(
                    "insert into engine.plan_revision(tenant_id,plan_id,revision,owner_task_id,"
                        + "base_revision,schema_version,goal_spec,validation_status,diagnostics,digest,created_by)"
                        + " values(?,?,?,?,?,'1.0',?::jsonb,?,?::jsonb,?,?)",
                    actor.tenantId(),
                    planId,
                    newRevision,
                    taskId,
                    baseRevision,
                    Json.write(goalSpec),
                    status,
                    validation.diagnostics().isEmpty()
                            ? null
                            : Json.write(validation.diagnostics()),
                    digest,
                    actor.principalId());
    if (validation.valid()) {
      insertPlanSteps(newRevision, planId, actor.tenantId(), goalSpec);
      store.db().update("""
              update engine.task set state='PLAN_READY',state_version=state_version+1,updated_at=now()
              where id=? and purpose='PLAN' and state in ('ACCEPTED','NEEDS_INPUT')
              """, taskId);
    }
    return Map.of(
            "planId", planId,
            "revision", newRevision,
            "digest", digest,
            "validationStatus", status,
            "diagnostics", validation.diagnostics(),
            "pendingInputs", List.of());
  }

  /**
   * 计划校验能力范围（R-1）：任务 tool_policy.allowedToolRefs（V230——NULL 继承发布范围）中
   * {@code skill:code@v#sha…} 形状引用的技能码集合；NULL/空=校验器跳过能力引用检查
   * （{@link PlanValidator} 契约：allowedSkills 空集不做 CAPABILITY_NOT_ALLOWED 判定）。
   */
  private static Set<String> allowedSkills(Map<String, Object> task) {
    Object policy = task.get("tool_policy");
    if (policy == null) return Set.of();
    var codes = new HashSet<String>();
    for (JsonNode ref : Json.read(String.valueOf(policy), JsonNode.class).path("allowedToolRefs")) {
      String value = ref.asText("");
      if (value.startsWith("skill:")) codes.add(value.substring("skill:".length()).split("@")[0]);
    }
    return codes;
  }

  /** 计划版本级确认：同号同参重放原凭据；同号异参 409 REQUEST_REPLAY；digest 不符 409。 */
  public Map<String, Object> confirm(
      long taskId, String planId, int revision, JsonNode req, Actor actor) {
    return store.atomic(() -> confirmLocked(taskId, planId, revision, req, actor));
  }

  private Map<String, Object> confirmLocked(long taskId, String planId, int revision, JsonNode req, Actor actor) {
    lockOwnedTask(taskId, actor);
    var revisionRow = revisionRow(taskId, planId, revision, actor.tenantId());
    String digest = req.path("digest").asText("");
    if (!digest.equals(String.valueOf(revisionRow.get("digest"))))
      throw new PlatformException(409, "PLAN_DIGEST_CHANGED", "digest 与固化版本不符");
    String requestId = req.path("requestId").asText("");
    if (requestId.isBlank() || requestId.length() > 128)
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
    return execute(taskId, req, actor, null);
  }

  public Map<String, Object> execute(long taskId, JsonNode req, Actor actor, String operationReason) {
    return store.atomic(() -> executeLocked(taskId, req, actor, operationReason));
  }

  private Map<String, Object> executeLocked(long taskId, JsonNode req, Actor actor, String operationReason) {
    var task = lockOwnedTask(taskId, actor);
    String planId = req.path("planId").asText("");
    int revision = req.path("revision").asInt(0);
    String digest = req.path("digest").asText("");
    var revisionRow = revisionRow(taskId, planId, revision, actor.tenantId()); // ①任务归属
    if (!digest.equals(String.valueOf(revisionRow.get("digest")))) // ②digest 一致
      throw new PlatformException(409, "PLAN_DIGEST_CHANGED", "执行请求 digest 与固化版本不符");
    if (!"VALID".equals(String.valueOf(revisionRow.get("validation_status")))) // ③VALID
      throw new PlatformException(409, "PLAN_NOT_CONFIRMED", "所选版本未确认或凭据已过期，请重新确认");
    String requestId = req.path("requestId").asText("");
    if (!requestId.isBlank()) {
      var previous = store.db().queryForList(
              "select public_id,parent_task_id,selected_plan_revision_id from engine.task where tenant_id=? and principal_id=? and request_id=?",
              actor.tenantId(), actor.principalId(), requestId);
      if (!previous.isEmpty()) {
        var previousTask = previous.getFirst();
        if (Objects.equals(previousTask.get("parent_task_id"), task.get("id"))
                && Objects.equals(previousTask.get("selected_plan_revision_id"), revisionRow.get("id")))
          return Map.of("executionTaskId", previousTask.get("public_id"), "pinnedRevision", revision, "pinnedDigest", digest);
        throw new PlatformException(409, "REQUEST_REPLAY", "请求号已用于另一项操作");
      }
    }
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
    int changed = store.db().update(
            "update engine.task set state='RUNNING',state_version=state_version+1,updated_at=now() where id=? and state in ('PLAN_READY','NEEDS_INPUT')",
            taskId);
    if (changed != 1) throw new PlatformException(409, "TASK_STATE_CONFLICT", "当前计划状态不可执行");
    if (operationReason != null) {
      var audit = Json.write(Map.of("operationReason", operationReason, "principalId", actor.principalId(),
              "parentTaskId", task.get("public_id"), "revision", revision, "digest", digest));
      store.db().update("""
              insert into engine.task_event(task_id,seq,type,payload,state_version)
              select id,1,'TASK_ACCEPTED',?::jsonb,state_version from engine.task where public_id=?
              """, audit, executionTaskId);
    }
    return Map.of(
            "executionTaskId", executionTaskId,
            "pinnedRevision", revision,
            "pinnedDigest", digest);
  }

  /** 修订读（含历史修订确认状态与授权——评审十轮 P1-1：按该修订计算 canConfirm/canExecute）。 */
  public Map<String, Object> revisionDetail(
      long taskId, String planId, int revision, Actor actor) {
    var task = ownedTask(taskId, actor);
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
      // R-1：INVALID 修订不可确认（confirm 409 PLAN_NOT_VALID——投影与动作同口径）
      view.put("canConfirm", "VALID".equals(String.valueOf(row.get("validation_status"))));
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
      view.put("canExecute", !expired && Set.of("PLAN_READY", "NEEDS_INPUT").contains(task.get("state")));
    }
    return view;
  }

  // ------------------------------------------------------------------
  // B4-1（台账 §7.5）：plan-import 画布桥（接口稿 §6 plan-import 行）——受限映射+如实降级
  // ------------------------------------------------------------------

  /**
   * 计划导入草稿画布桥（骨架）：{planId,revision,digest,draftBaselineRev}→
   * {@code {validation{ok,issues},nodeMapping,draftRev}}。
   *
   * <p>受限映射（卡面口径）：plan_step 仅 kind=AGENT|SKILL 可表达为画布 CODE 节点
   * （一期节点族无 AGENT 类型，契约 graph-definition §1.5 AGENT/ONTOLOGY 预留），
   * target_ref 随节点标注；其余 kind 返回 stepId+KIND_NOT_EXPORTABLE（BLOCK）如实降级。
   * 不支持字段（inputBindings/failurePolicy）逐项 FIELD_NOT_EXPRESSIBLE（WARN）留痕，
   * 依赖边仅在有向无环且两端均可表达时生成（不可表达依赖 DEPENDENCY_DROPPED，WARN）。
   *
   * <p>骨架边界（如实说明）：本端点只做引擎侧映射预览，不写草稿图——draftRev 回显
   * draftBaselineRev（草稿推进由既有 PUT /admin/flows/{code} 独立完成）；工作区编辑权限
   * 归口 app-management，此处按引擎侧资源范围核验（tenant+提交人本人），角色门 BUILDER/
   * EDITOR（§6 矩阵：ADMIN 对 plan-import 只读，不放开导入）。
   */
  public Map<String, Object> importToCanvas(String appCode, JsonNode req, Actor actor) {
    if (!appCode.matches("[A-Za-z0-9_-]{1,64}"))
      throw new PlatformException(400, "INVALID_INPUT", "应用编码形状非法");
    if (!actor.roles().contains("BUILDER") && !actor.roles().contains("EDITOR"))
      throw new PlatformException(403, "PLAN_IMPORT_ROLE_REQUIRED", "计划导入需要 BUILDER 角色");
    String planId = req.path("planId").asText("");
    if (planId.isBlank() || planId.length() > 128)
      throw new PlatformException(400, "PLAN_ID_REQUIRED", "planId 必填（≤128 字）");
    if (!req.path("revision").isInt() || req.path("revision").asInt() < 1)
      throw new PlatformException(400, "PLAN_REVISION_INVALID", "revision 必须为正整数");
    if (!req.path("digest").isTextual() || req.path("digest").asText().isBlank())
      throw new PlatformException(400, "PLAN_DIGEST_REQUIRED", "digest 必填（固化版本摘要）");
    if (!req.path("draftBaselineRev").isInt() || req.path("draftBaselineRev").asInt() < 0)
      throw new PlatformException(400, "DRAFT_BASELINE_INVALID", "draftBaselineRev 必须为非负整数");
    int revision = req.path("revision").asInt();
    String digest = req.path("digest").asText();
    // 资源范围=提交人本人（tenant+principal 与 Plans 其他端点同一口径）
    var rows =
            store.db()
                    .queryForList(
                            "select pr.* from engine.plan_revision pr join engine.task t on t.id=pr.owner_task_id"
                                + " where t.tenant_id=? and t.principal_id=? and pr.plan_id=? and pr.revision=?",
                            actor.tenantId(),
                            actor.principalId(),
                            planId,
                            revision);
    if (rows.isEmpty())
      throw new PlatformException(404, "PLAN_REVISION_NOT_FOUND", "计划修订不存在或无权读取");
    var revisionRow = rows.getFirst();
    if (!digest.equals(String.valueOf(revisionRow.get("digest"))))
      throw new PlatformException(409, "PLAN_DIGEST_CHANGED", "digest 与固化版本不符");
    var steps =
            store.db()
                    .queryForList(
                            "select * from engine.plan_step where plan_revision_id=? order by step_id",
                            TraceStore.number(revisionRow, "id"));
    var issues = new ArrayList<Map<String, Object>>();
    if (!"VALID".equals(String.valueOf(revisionRow.get("validation_status"))))
      issues.add(
              Map.of(
                      "code", "PLAN_REVISION_INVALID",
                      "severity", "BLOCK",
                      "message", "修订校验状态 " + revisionRow.get("validation_status") + " 不可导入"));
    var nodes = new ArrayList<Map<String, Object>>();
    var edges = new ArrayList<Map<String, Object>>();
    var exported = new HashSet<String>(); // stepId→已表达为节点
    for (var step : steps) {
      String stepId = String.valueOf(step.get("step_id"));
      String kind = String.valueOf(step.get("kind"));
      if (!Set.of("AGENT", "SKILL").contains(kind)) {
        issues.add(
                Map.of(
                        "stepId", stepId,
                        "code", "KIND_NOT_EXPORTABLE",
                        "severity", "BLOCK",
                        "field", "kind",
                        "message", "步骤类型 " + kind + " 一期画布节点族不可表达，该步骤未导入"));
        continue;
      }
      exported.add(stepId);
      var node = new LinkedHashMap<String, Object>();
      node.put("nodeId", "n_" + stepId);
      node.put("stepId", stepId);
      node.put("type", "CODE");
      node.put("targetRef", step.get("target_ref"));
      node.put("required", Boolean.TRUE.equals(step.get("required")));
      node.put("dependsOn", TraceStore.json(step.get("depends_on")));
      nodes.add(node);
      // 不支持字段如实降级（节点已生成，字段留痕降级）
      var inputBindings = TraceStore.json(step.get("input_bindings"));
      if (inputBindings.isObject() && !inputBindings.isEmpty())
        issues.add(fieldIssue(stepId, "inputBindings", "步骤输入绑定不进画布节点属性，导入后需人工补齐"));
      var failurePolicy = TraceStore.json(step.get("failure_policy"));
      if (failurePolicy.isObject() && !failurePolicy.isEmpty())
        issues.add(fieldIssue(stepId, "failurePolicy", "步骤失败策略不进画布节点属性，导入后需人工补齐"));
    }
    for (var step : steps) {
      String stepId = String.valueOf(step.get("step_id"));
      if (!exported.contains(stepId)) continue;
      for (JsonNode dep : TraceStore.json(step.get("depends_on"))) {
        String depId = dep.asText();
        if (exported.contains(depId)) {
          edges.add(Map.of("from", "n_" + depId, "to", "n_" + stepId));
        } else {
          issues.add(
                  Map.of(
                          "stepId", stepId,
                          "code", "DEPENDENCY_DROPPED",
                          "severity", "WARN",
                          "field", "dependsOn",
                          "message", "依赖步骤 " + depId + " 未导入，连线丢弃"));
        }
      }
    }
    boolean ok = issues.stream().noneMatch(i -> "BLOCK".equals(i.get("severity")));
    var nodeMapping = new LinkedHashMap<String, Object>();
    nodeMapping.put("nodes", nodes);
    nodeMapping.put("edges", edges);
    var validation = new LinkedHashMap<String, Object>();
    validation.put("ok", ok);
    validation.put("issues", issues);
    var out = new LinkedHashMap<String, Object>();
    out.put("validation", validation);
    out.put("nodeMapping", nodeMapping);
    out.put("draftRev", req.path("draftBaselineRev").asInt());
    out.put("planId", planId);
    out.put("revision", revision);
    out.put("digest", digest);
    return out;
  }

  private static Map<String, Object> fieldIssue(String stepId, String field, String message) {
    return Map.of(
            "stepId", stepId,
            "code", "FIELD_NOT_EXPRESSIBLE",
            "severity", "WARN",
            "field", field,
            "message", message);
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

  private Map<String, Object> lockOwnedTask(long taskId, Actor actor) {
    var rows = store.db().queryForList(
            "select * from engine.task where id=? and tenant_id=? and principal_id=? for update",
            taskId, actor.tenantId(), actor.principalId());
    if (rows.isEmpty()) throw new PlatformException(404, "TASK_NOT_FOUND", "任务不存在或无权读取");
    return rows.getFirst();
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
