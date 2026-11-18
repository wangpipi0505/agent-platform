package cn.agentplatform.engine.harness.context;

import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import cn.agentplatform.engine.harness.session.SessionEvents;
import cn.agentplatform.engine.harness.session.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * harness.context 提示装配（PromptSection 有序拼装，T2-10；机制规格=主方案 §5.4）。
 *
 * <p><b>五段有序拼装</b>：{@code PLATFORM_FIXED → TEMPLATE → RULES → SKILL_CATALOG →
 * RUNTIME_FACTS}。段来源由入参 Map 给出（section → 正文），拼装顺序固定于枚举声明序，
 * 与 Map 实现类型/遍历序无关；空段跳过，非空段按序以空行连接。段正文可含
 * {@code {{var}}} 插值变量，统一经 {@link #interpolate} 展开（未知变量结构化报错，
 * 与 ContextAssembler 模板渲染同口径）。
 *
 * <p><b>覆盖白名单</b>：manifest.contextPolicy（{@code contextPolicy.sections} 子对象，
 * 与 maxTurns 等扁平键不冲突）只能覆盖 TEMPLATE/RULES/SKILL_CATALOG 三段；
 * PLATFORM_FIXED（平台固定规则）与 RUNTIME_FACTS（运行时事实）<b>不可遮蔽</b>——覆盖
 * 尝试一律结构化拒绝（409 PROMPT_SECTION_OVERRIDE_DENIED，拒绝原因记录被拒段名单与
 * 白名单），非白名单的未知段名同样拒绝，保证平台固定规则不可被 manifest 静默替换。
 *
 * <p><b>渲染产物落日志</b>：渲染结果作为 {@code system/message} 会话事件落库（H1-5
 * "模型可见即已记录"）；同会话再次装配且渲染产物变更时原位 replace（surface_op=
 * {@code replace[startSeq..endSeq]} 摘除旧 prompt_render 消息后追加新消息，投影用法
 * 照 SessionStoreContractTest.deriveMessages 用例）；sha256 未变更时不重复落（同一产物
 * 不产生第二条 system 消息）。落库数据形状 {@code {content, kind:"prompt_render",
 * sha256}}——kind/sha256 标记用于定位上一渲染产物，content 为模型可见正文。
 *
 * <p><b>R-15 外溢引用回填（spill 接入工具环）</b>：带会话归属的
 * {@link #render(String, Map, Map, JsonNode)} 在 {{var}} 插值后，把段正文中的
 * {@code {{spill:&lt;spillId&gt;}}} 引用经 {@link SpillStore#read} 还原为外溢原文原位
 * 拼入（会话内归属核验+sha256 复核）——工具环超限外溢（AgentDriver→SpillStore）的读侧，
 * 后续轮次可取回大结果原文。
 */
public final class PromptAssembler {

  /** 提示段目录与唯一权威顺序（枚举声明序=拼装序）。 */
  public enum Section {
    /** 平台固定段：平台固定规则/安全约束（不可遮蔽）。 */
    PLATFORM_FIXED,
    /** 模板段：发布模板渲染产物。 */
    TEMPLATE,
    /** 规则段：工作区规则清单。 */
    RULES,
    /** 技能目录段：可用技能/工具目录。 */
    SKILL_CATALOG,
    /** 运行时事实段：运行时事实（不可遮蔽）。 */
    RUNTIME_FACTS
  }

  /** 覆盖白名单（主方案 §5.4：manifest 只能覆盖指定 section，平台固定规则不可遮蔽）。 */
  public static final Set<Section> OVERRIDABLE =
          Set.of(Section.TEMPLATE, Section.RULES, Section.SKILL_CATALOG);

  /** 覆盖拒绝原因码（结构化拒绝；拒绝语义不可静默降级为忽略）。 */
  public static final String OVERRIDE_DENIED = "PROMPT_SECTION_OVERRIDE_DENIED";

  /** 渲染产物落库标记（system/message data.kind）。 */
  public static final String RENDER_KIND = "prompt_render";

  /** {@code {{var}}} 插值模式（与 ContextAssembler.render 同一形状：花括号内允许空白）。 */
  private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

  /** 渲染产物：content=模型可见正文；sections=实际参与拼装的有序段；overridden=被 contextPolicy 覆盖的段。 */
  public record Render(String content, String sha256, List<Section> sections, Set<Section> overridden) {}

  /**
   * R-15（spill 接入工具环）：外溢引用回填 token——段正文中的 {@code {{ spill:&lt;spillId&gt; }}}
   * 在组装时还原为外溢原文（后续轮次取回）。与 {@link #VAR} 插值互不干扰（冒号不在
   * 变量名字符集内，插值器放过该 token）；回填产物不再二次插值。
   */
  public static final Pattern SPILL_REF =
          Pattern.compile("\\{\\{\\s*spill:(s_[a-f0-9]{32})\\s*}}");

  private final SessionStore sessions;
  /** 外溢读取协作端（可选装配）：未装配时按引用回填结构化拒绝，不伪造原文。 */
  private final SpillStore spills;
  private final SpillStore.StorageSource spillSource;

  /** sessions 为空时仅纯渲染（preview/无会话关联的独立运行），publish 为空操作。 */
  public PromptAssembler(SessionStore sessions) {
    this(sessions, null, null);
  }

  /**
   * R-15：带外溢读取协作端的装配器。spillSource 生产为 MinIO GET、测试为内存映射；
   * 归属核验与摘要复核在 SpillStore.read 内完成。
   */
  public PromptAssembler(SessionStore sessions, SpillStore spills, SpillStore.StorageSource spillSource) {
    this.sessions = sessions;
    this.spills = spills;
    this.spillSource = spillSource;
  }

  /**
   * 五段有序拼装 + {@code {{var}}} 插值 + 覆盖白名单。{@code contextPolicy.sections}
   * 只允许白名单段；越权覆盖（PLATFORM_FIXED/RUNTIME_FACTS/未知段名）结构化拒绝。
   *
   * @param sections      段来源（section → 段正文，可含 {{var}}）；缺段即跳过
   * @param vars          插值变量（缺变量报 PARAM_ERROR，与模板渲染同口径）
   * @param contextPolicy manifest.contextPolicy（null/无 sections 子对象=无覆盖）
   */
  public Render render(Map<Section, String> sections, Map<String, Object> vars, JsonNode contextPolicy) {
    return render(null, sections, vars, contextPolicy);
  }

  /**
   * R-15：带会话归属的组装——在既有插值/覆盖/拼装之外按引用回填外溢原文。段正文经
   * {{var}} 插值后，其中的 {@code {{spill:&lt;spillId&gt;}}} token 还原为外溢全文（会话
   * 内归属核验+摘要复核，见 {@link #backfill}）；回填产物不再二次插值。sessionId 为
   * null 时与三参 {@link #render} 完全同口径（零变化锚点）。
   */
  public Render render(
          String sessionId, Map<Section, String> sections, Map<String, Object> vars, JsonNode contextPolicy) {
    Objects.requireNonNull(sections, "段来源 Map 不可为空");
    var overrides = readOverrides(contextPolicy);
    var overridden = new LinkedHashSet<Section>();
    var applied = new ArrayList<Section>();
    var body = new StringBuilder();
    for (Section section : Section.values()) {
      String text = sections.get(section);
      if (overrides.containsKey(section)) {
        text = overrides.get(section);
        overridden.add(section);
      }
      if (text == null || text.isBlank()) continue;
      if (!body.isEmpty()) body.append("\n\n");
      String rendered = interpolate(text, vars);
      if (sessionId != null) rendered = backfill(sessionId, rendered);
      body.append(rendered);
      applied.add(section);
    }
    String content = body.toString();
    return new Render(content, Digest.sha256(content), List.copyOf(applied), Set.copyOf(overridden));
  }

  /**
   * R-15：按引用取回外溢原文（后续轮次取回入口）。委托 {@link SpillStore#read}（会话
   * 归属核验+sha256 复核）；读取协作端未装配时结构化拒绝（SPILL_RETRIEVAL_UNAVAILABLE），
   * 不静默返回空串或近似内容。
   */
  public String retrieveSpill(String sessionId, String spillId) {
    if (spills == null)
      throw new PlatformException(
              500, "SPILL_RETRIEVAL_UNAVAILABLE", "外溢读取协作端未装配，无法按引用取回原文");
    return spills.read(sessionId, spillId, spillSource);
  }

  /**
   * R-15：组装时按引用回填——文本中的 {@code {{spill:&lt;spillId&gt;}}} token 还原为外溢
   * 原文后原位拼入。引用无效/跨会话/摘要不符经 SpillStore.read 结构化拒绝（400/404/500）。
   */
  public String backfill(String sessionId, String text) {
    if (text == null || !text.contains("{{")) return text;
    Matcher matcher = SPILL_REF.matcher(text);
    var result = new StringBuffer();
    while (matcher.find())
      matcher.appendReplacement(result, Matcher.quoteReplacement(retrieveSpill(sessionId, matcher.group(1))));
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * 渲染产物作为 system/message 落日志；同会话上一渲染产物变更时原位 replace。返回本次
   * 落库（或既有）事件的 seq；无会话存储时返回 -1（纯渲染模式）。
   */
  public long publish(String sessionId, Render render) {
    if (sessions == null) return -1;
    long prior = -1;
    for (var event : sessions.read(sessionId, 0, 0)) {
      if (!SessionEvents.SYSTEM_MESSAGE.equals(event.type())) continue;
      if (!RENDER_KIND.equals(event.data().path("kind").asText())) continue;
      if (render.sha256().equals(event.data().path("sha256").asText()))
        return event.seq(); // 未变更：不重复落，返回既有产物 seq
      prior = event.seq(); // 变更：记最近一次 prompt_render（replace 它，同序保持唯一）
    }
    var data = Json.object();
    data.put("content", render.content());
    data.put("kind", RENDER_KIND);
    data.put("sha256", render.sha256());
    var surface =
            prior > 0
                    ? Json.tree(Map.of("op", "replace", "startSeq", prior, "endSeq", prior))
                    : null;
    return sessions.append(sessionId, null, SessionEvents.SYSTEM_MESSAGE, data, surface);
  }

  /** 渲染并落日志一步入口。 */
  public long publish(String sessionId, Map<Section, String> sections, Map<String, Object> vars, JsonNode contextPolicy) {
    return publish(sessionId, render(sections, vars, contextPolicy));
  }

  /** 解析并校验覆盖白名单：白名单外（含未知段名）一律结构化拒绝，不做静默忽略。 */
  private static Map<Section, String> readOverrides(JsonNode contextPolicy) {
    if (contextPolicy == null || contextPolicy.isNull() || contextPolicy.isMissingNode())
      return Map.of();
    var declared = contextPolicy.path("sections");
    if (declared.isMissingNode() || declared.isNull()) return Map.of();
    if (!declared.isObject())
      throw new PlatformException(409, OVERRIDE_DENIED, "contextPolicy.sections 必须为对象");
    var overrides = new LinkedHashMap<Section, String>();
    var denied = new LinkedHashSet<String>();
    var fields = declared.fieldNames();
    while (fields.hasNext()) {
      String name = fields.next();
      Section section = byName(name);
      if (section == null || !OVERRIDABLE.contains(section)) {
        denied.add(name);
        continue;
      }
      JsonNode value = declared.path(name);
      if (value.isTextual()) overrides.put(section, value.textValue());
      else if (value.isValueNode() || value.isObject()) overrides.put(section, value.toString());
      else denied.add(name);
    }
    if (!denied.isEmpty())
      throw new PlatformException(
              409,
              OVERRIDE_DENIED,
              "contextPolicy 试图覆盖白名单外 section "
                      + denied
                      + "（可覆盖="
                      + OVERRIDABLE
                      + "；平台固定段与运行时事实段不可遮蔽）");
    return overrides;
  }

  static Section byName(String name) {
    for (Section section : Section.values()) if (section.name().equals(name)) return section;
    return null;
  }

  /** {{var}} 插值：未知变量结构化报错（与 ContextAssembler.render 同口径）。 */
  static String interpolate(String text, Map<String, Object> vars) {
    Map<String, Object> source = vars == null ? Map.of() : vars;
    Matcher matcher = VAR.matcher(text == null ? "" : text);
    var result = new StringBuffer();
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!source.containsKey(name))
        throw new IllegalArgumentException("PARAM_ERROR:unknown prompt variable " + name);
      Object value = source.get(name);
      String replacement;
      if (value == null) replacement = "";
      else if (value instanceof JsonNode node)
        replacement = node.isValueNode() ? node.asText() : node.toString();
      else replacement = value.toString();
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
