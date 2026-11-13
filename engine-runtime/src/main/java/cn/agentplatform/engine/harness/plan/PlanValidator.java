package cn.agentplatform.engine.harness.plan;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * T2-4（台账 §7.3）：结构化优先计划校验器——Schema 产出→逐项校验→固化→渲染展示
 * （主方案 §5.6：渲染是展示投影，不是解析输入；执行绑定只认 PlanRevision）。
 *
 * <p>校验顺序照 v1 §5.2：DAG（环/自依赖/未知依赖）→ 必填参数 → 类型 → 能力引用 → 权限。
 * 校验通过返回 VALID（诊断空）；任一失败返回 INVALID（诊断逐条 path/code/message）。
 * 五 kind 枚举 SKILL/FLOW/AGENT/MODEL/CONDITION（contracts/task-states.md plan_step.kind）。
 */
public final class PlanValidator {

  private PlanValidator() {}

  public static final Set<String> KINDS = Set.of("SKILL", "FLOW", "AGENT", "MODEL", "CONDITION");
  /** 能力引用白名单形状：skill:code@v#sha256… / agent:code@v / model:entry / flow:id（形状校验，版本存在性由发布预检/运行装载核验）。 */
  private static final Map<String, String> TARGET_SHAPES =
          Map.of(
                  "SKILL", "^skill:[A-Za-z0-9_-]+@\\d+#[a-f0-9]{64}$",
                  "FLOW", "^flow:[A-Za-z0-9_-]+$",
                  "AGENT", "^agent:[A-Za-z0-9_-]+(@\\d+)?$",
                  "MODEL", "^model:[A-Za-z0-9_.-]+$",
                  "CONDITION", "^expr:.+$");

  /** 校验结果：valid + 逐条诊断（path/code/message）。 */
  public record ValidationResult(boolean valid, List<Map<String, String>> diagnostics) {}

  /** 步骤形状（校验输入）：stepId/kind/targetRef/dependsOn/inputBindings。 */
  public record StepSpec(String stepId, String kind, String targetRef, List<String> dependsOn, JsonNode inputBindings) {}

  public static ValidationResult validate(List<StepSpec> steps, Set<String> allowedSkills) {
    List<Map<String, String>> diags = new ArrayList<>();
    Map<String, StepSpec> byId = new LinkedHashMap<>();
    for (StepSpec step : steps) {
      String path = "/steps/" + step.stepId();
      if (step.stepId() == null || step.stepId().isBlank()) {
        diags.add(diag("/steps", "STEP_ID_REQUIRED", "步骤标识必填"));
        continue;
      }
      if (byId.containsKey(step.stepId())) {
        diags.add(diag(path, "STEP_ID_DUPLICATE", "步骤标识重复"));
        continue;
      }
      byId.put(step.stepId(), step);
      if (step.kind() == null || !KINDS.contains(step.kind())) {
        diags.add(diag(path + "/kind", "KIND_INVALID", "kind 必须为 SKILL|FLOW|AGENT|MODEL|CONDITION"));
      }
      if (step.targetRef() == null || step.targetRef().isBlank()) {
        diags.add(diag(path + "/targetRef", "TARGET_REF_REQUIRED", "targetRef 必填（精确版本引用）"));
      } else {
        String shape = TARGET_SHAPES.get(step.kind());
        if (shape != null && !step.targetRef().matches(shape))
          diags.add(diag(path + "/targetRef", "TARGET_REF_SHAPE", "targetRef 形状与 kind 不符: " + step.targetRef()));
        // 能力引用（skill:）必须在已授权集合内（主方案 §5.6 校验顺序第 4 项）
        if (step.targetRef().startsWith("skill:")) {
          String code = step.targetRef().substring("skill:".length()).split("@")[0];
          if (allowedSkills != null && !allowedSkills.isEmpty() && !allowedSkills.contains(code))
            diags.add(diag(path + "/targetRef", "CAPABILITY_NOT_ALLOWED", "技能未在发布工具范围: " + code));
        }
      }
      if (step.inputBindings() == null || !step.inputBindings().isObject())
        diags.add(diag(path + "/inputBindings", "INPUT_BINDINGS_REQUIRED", "inputBindings 必须为对象"));
    }
    // DAG：未知依赖/自依赖/环检测（DFS 三色）
    for (StepSpec step : byId.values()) {
      for (String dep : step.dependsOn() == null ? List.<String>of() : step.dependsOn()) {
        if (!byId.containsKey(dep))
          diags.add(diag("/steps/" + step.stepId() + "/dependsOn", "DEPENDENCY_UNKNOWN", "依赖的步骤不存在: " + dep));
        else if (dep.equals(step.stepId()))
          diags.add(diag("/steps/" + step.stepId() + "/dependsOn", "DEPENDENCY_SELF", "步骤自依赖"));
      }
    }
    Map<String, Integer> color = new HashMap<>();
    for (String id : byId.keySet())
      if (color.getOrDefault(id, 0) == 0) visit(id, byId, color, diags);
    return new ValidationResult(diags.isEmpty(), diags);
  }

  private static void visit(
      String id, Map<String, StepSpec> byId, Map<String, Integer> color, List<Map<String, String>> diags) {
    color.put(id, 1);
    for (String dep : byId.get(id).dependsOn() == null ? List.<String>of() : byId.get(id).dependsOn()) {
      int c = color.getOrDefault(dep, 0);
      if (c == 1)
        diags.add(diag("/steps/" + id + "/dependsOn", "DEPENDENCY_CYCLE", "依赖环: " + id + " → " + dep));
      else if (c == 0 && byId.containsKey(dep)) visit(dep, byId, color, diags);
    }
    color.put(id, 2);
  }

  /** JSON 入口：从 plan_revision.goal_spec.steps 数组校验（T2-3 appendRevision 固化前调用）。 */
  public static ValidationResult validate(JsonNode goalSpec, Set<String> allowedSkills) {
    List<StepSpec> steps = new ArrayList<>();
    for (JsonNode s : goalSpec.path("steps")) {
      List<String> deps = new ArrayList<>();
      s.path("dependsOn").forEach(d -> deps.add(d.asText()));
      steps.add(new StepSpec(s.path("stepId").asText(null), s.path("kind").asText(null),
              s.path("targetRef").asText(null), deps, s.path("inputBindings")));
    }
    return validate(steps, allowedSkills);
  }

  private static Map<String, String> diag(String path, String code, String message) {
    return Map.of("path", path, "code", code, "message", message);
  }
}
