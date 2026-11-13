package cn.agentplatform.engine.harness.plan;

import cn.agentplatform.common.Json;
import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * T2-4（台账 §7.3）：Plan 模式会话侧——plan:policy 提示分节注入与 exit_plan_mode 渐进披露。
 *
 * <p>主方案 §5.6：plan/mode{active} log-only 事件 durable、可重放、不入模型转录；激活时注入
 * {@code plan:policy} 提示分节（部署方文案，入 manifest）；exit_plan_mode 工具按渐进披露注册
 * ——首个计划态 step 加入请求工具声明并保持；计划模式外调用报错。
 *
 * <p>会话交互模式（本类）与任务模式（task.mode 受理即固定，T2-2）解耦——同一会话并发提交
 * Plan 任务与 Agent 任务互不影响（验证=A01 并发 Plan/Agent 互不串扰）。
 */
public final class PlanMode {

  private PlanMode() {}

  /** exit_plan_mode 工具声明（渐进披露形状：首计划态 step 加入请求工具声明并保持）。 */
  public static JsonNode exitPlanModeToolDeclaration() {
    return Json.read(
            "{\"type\":\"function\",\"function\":{\"name\":\"exit_plan_mode\","
                + "\"description\":\"计划已就绪，退出规划模式（提交计划供用户确认）\",\"parameters\":"
                + "{\"type\":\"object\",\"properties\":{\"plan\":{\"type\":\"object\","
                + "\"description\":\"结构化计划（steps/dependsOn/inputBindings 按 Schema）\"}},"
                + "\"required\":[\"plan\"]}}}");
  }

  /** plan:policy 提示分节（部署方文案入 manifest.planPolicy；缺省内置文案）。 */
  public static String policySection(JsonNode manifest) {
    String policy = manifest.path("planPolicy").asText("");
    return policy.isBlank()
            ? "当前处于规划模式：只读探索与计划制定。请按 Schema 产出结构化计划"
                + "（steps/dependsOn/targetRef/inputBindings），完成后调用 exit_plan_mode 提交。"
                + "规划阶段不执行任何写操作。"
            : policy;
  }

  /** plan/mode{active} 事件（log-only，不入模型转录——读取投影白名单 USER 可见 plan/mode）。 */
  public static long setActive(SessionStore sessions, String sessionId, boolean active) {
    return sessions.append(
            sessionId, null, SessionEvents.PLAN_MODE, Json.tree(java.util.Map.of("active", active)), null);
  }

  /** 重放取当前交互模式（最后一条 plan/mode 生效；无事件=非规划模式）。 */
  public static boolean isActive(SessionStore sessions, String sessionId) {
    List<SessionStore.SessionEvent> events = sessions.read(sessionId, 0, 0);
    boolean active = false;
    for (SessionStore.SessionEvent event : events)
      if (SessionEvents.PLAN_MODE.equals(event.type()))
        active = event.data().path("active").asBoolean(false);
    return active;
  }

  /**
   * 渐进披露：规划模式激活时该步工具声明附加 exit_plan_mode（首个计划态 step 加入并保持）；
   * 非规划模式出现 exit_plan_mode 调用→结构化拒绝（计划模式外调用报错）。
   *
   * @param declared 当前步模型可见工具声明数组（config.tools 的 definition 集合）
   * @return 附加 exit_plan_mode 后的声明集合（保持原有工具不裁剪——渐进披露只加不减）
   */
  public static List<JsonNode> discloseForPlanStep(List<JsonNode> declared, boolean planActive) {
    if (!planActive) return declared;
    var out = new java.util.ArrayList<>(declared);
    out.add(exitPlanModeToolDeclaration());
    return out;
  }

  /** exit_plan_mode 调用守卫：非规划模式调用→结构化拒绝（主方案 §5.6 渐进披露）。 */
  public static void requirePlanActive(boolean planActive, String sessionId) {
    if (!planActive)
      throw new cn.agentplatform.common.PlatformException(
              409,
              "EXIT_PLAN_MODE_INACTIVE",
              "exit_plan_mode 仅在规划模式内可用（会话 " + sessionId + " 未激活 plan/mode）");
  }
}
