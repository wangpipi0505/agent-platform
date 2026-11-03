package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.AssistantExperienceRepository;
import cn.agentplatform.app.infra.AssistantExperienceRepository.Revision;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 体验描述（AssistantExperience）的编辑面：标题、欢迎语、能力说明、提示词引用与展示形态。
 *
 * <p>这里是"配置"而不是"发布"：本服务只产出可校验、可审计的不可变修订，真正对外可见依赖
 * 配置发布（ConfigRelease）的 ACTIVE 指针。语义字段里出现明文凭据会被直接拒绝，凭据只能以引用出现。
 */
@Service
public class AssistantExperiences {

  static final Set<String> BODY_KEYS = Set.of("expectedRev", "requestId", "title", "welcomeText",
      "capabilityDescriptions", "promptRefs", "inputPresentation", "resultPresentation", "helpRefs");
  private static final Set<String> CAPABILITY_KEYS = Set.of("code", "label", "description", "readOnly",
      "requiresApproval", "inputSchema");
  private static final Set<String> PROMPT_KEYS = Set.of("ref", "version");
  private static final int MAX_CAPABILITIES = 32;
  private static final int MAX_PROMPTS = 16;
  private static final int MAX_HELP = 8;
  private static final int MAX_PRESENTATION_KEYS = 16;

  private final AssistantExperienceRepository store;
  private final ConfigWriteLog writes;

  public AssistantExperiences(AssistantExperienceRepository store, ConfigWriteLog writes) {
    this.store = store;
    this.writes = writes;
  }

  public Map<String, Object> get(String appCode) {
    Actor actor = Actor.current();
    return store.view(actor, appCode)
        .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode));
  }

  public List<Map<String, Object>> list() {
    return store.all(Actor.current());
  }

  public Map<String, Object> create(String appCode, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, BODY_KEYS, "体验描述");
    if (body.containsKey("expectedRev")) {
      throw ConnectionProfiles.invalid("新建体验描述不接受 expectedRev，首次写入的当前修订恒为 0");
    }
    requireApp(actor, appCode);
    Revision revision = declaration(actor, body);
    requirePrompts(actor, revision);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("assistant-experience.create", appCode,
        revision.sha256())));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "assistant-experience.create",
        appCode, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    store.ensure(actor, appCode);
    Map<String, Object> out = store.append(actor, appCode, revision, 0, requestId,
        () -> view(actor, appCode));
    writes.remember(actor.tenantId(), "assistant-experience.create", appCode, requestId, sha, out);
    return out;
  }

  public Map<String, Object> revise(String appCode, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, BODY_KEYS, "体验描述");
    int expectedRev = ConnectionProfiles.expectedRev(body);
    Revision revision = declaration(actor, body);
    requirePrompts(actor, revision);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("assistant-experience.revise", appCode,
        expectedRev, revision.sha256())));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "assistant-experience.revise",
        appCode, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.append(actor, appCode, revision, expectedRev, requestId,
        () -> view(actor, appCode));
    writes.remember(actor.tenantId(), "assistant-experience.revise", appCode, requestId, sha, out);
    return out;
  }

  public Map<String, Object> approve(String appCode, Map<String, Object> body) {
    return transition(appCode, body, "approve", "DRAFT", "APPROVED");
  }

  public Map<String, Object> disable(String appCode, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("expectedRev", "requestId"), "体验描述");
    int expectedRev = ConnectionProfiles.expectedRev(body);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("assistant-experience.disable", appCode, expectedRev)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "assistant-experience.disable",
        appCode, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.transition(actor, appCode, expectedRev, "APPROVED", "DISABLED",
        requestId, () -> view(actor, appCode));
    writes.remember(actor.tenantId(), "assistant-experience.disable", appCode, requestId, sha, out);
    return out;
  }

  public AssistantExperienceRepository.State state(Actor actor, String appCode, int rev) {
    return store.state(actor, appCode, rev);
  }

  /** 发布校验会复用：给定应用的体验修订必须存在且处于 APPROVED。 */
  public void requireApproved(Actor actor, String appCode, int experienceRev) {
    var state = store.state(actor, appCode, experienceRev);
    if (state == null || !state.revisionExists()) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "体验修订不存在: " + appCode + "@" + experienceRev);
    }
    if (!"APPROVED".equals(state.status())) {
      throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
          "体验描述状态 " + state.status() + " 不能用于配置发布");
    }
  }

  private Map<String, Object> transition(String appCode, Map<String, Object> body, String operation,
      String from, String to) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("expectedRev", "requestId"), "体验描述");
    int expectedRev = ConnectionProfiles.expectedRev(body);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("assistant-experience." + operation, appCode, expectedRev)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "assistant-experience." + operation,
        appCode, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.transition(actor, appCode, expectedRev, from, to, requestId,
        () -> view(actor, appCode));
    writes.remember(actor.tenantId(), "assistant-experience." + operation, appCode, requestId, sha, out);
    return out;
  }

  private Map<String, Object> view(Actor actor, String appCode) {
    return store.view(actor, appCode).orElseThrow(
        () -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode));
  }

  /** 提示词引用只允许指向既有模板版本：配置表达的是引用，不是模板内容的副本。 */
  private void requirePrompts(Actor actor, Revision revision) {
    for (Map<String, Object> ref : revision.promptRefs()) {
      String code = String.valueOf(ref.get("ref"));
      int version = ((Number) ref.get("version")).intValue();
      if (!store.promptVersionExists(actor, code, version)) {
        throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
            "提示词模板版本不存在: " + code + "@" + version);
      }
    }
  }

  private void requireApp(Actor actor, String appCode) {
    if (!store.appExists(actor, appCode)) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
    }
  }

  static Revision declaration(Actor actor, Map<String, Object> body) {
    ConnectionProfiles.rejectPlaintextSecrets(body, Set.of());
    String title = ConnectionProfiles.text(body, "title", "体验描述", 128);
    String welcome = body.containsKey("welcomeText")
        ? ConnectionProfiles.text(body, "welcomeText", "体验描述", 2048) : "";
    List<Map<String, Object>> capabilities = capabilities(body.get("capabilityDescriptions"));
    List<Map<String, Object>> prompts = prompts(body.get("promptRefs"));
    Map<String, Object> input = presentation(body.get("inputPresentation"), "inputPresentation");
    Map<String, Object> result = presentation(body.get("resultPresentation"), "resultPresentation");
    List<String> help = ConnectionProfiles.strings(body.get("helpRefs"), "helpRefs", 0, MAX_HELP,
        ConnectionProfiles.REF);
    Map<String, Object> declared = new LinkedHashMap<>();
    declared.put("title", title);
    declared.put("welcomeText", welcome);
    declared.put("capabilityDescriptions", capabilities);
    declared.put("promptRefs", prompts);
    declared.put("inputPresentation", input);
    declared.put("resultPresentation", result);
    declared.put("helpRefs", help);
    return new Revision(title, welcome, capabilities, prompts, input, result, help,
        Digest.sha256(Json.canonical(declared)));
  }

  private static List<Map<String, Object>> capabilities(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw ConnectionProfiles.invalid("capabilityDescriptions 必须是数组");
    }
    if (list.size() > MAX_CAPABILITIES) {
      throw ConnectionProfiles.budget("能力说明最多 " + MAX_CAPABILITIES + " 条");
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> entry = ConnectionProfiles.object(item, "capabilityDescriptions", CAPABILITY_KEYS);
      String code = ConnectionProfiles.text(entry, "code", "能力说明", 64, ConnectionProfiles.OPERATION);
      ConnectionProfiles.text(entry, "label", "能力说明", 64);
      if (entry.containsKey("description")) {
        ConnectionProfiles.text(entry, "description", "能力说明", 512);
      }
      Map<String, Object> normalized = new LinkedHashMap<>();
      normalized.put("code", code);
      normalized.put("label", entry.get("label"));
      normalized.put("description", entry.getOrDefault("description", ""));
      normalized.put("readOnly", bool(entry.get("readOnly")));
      normalized.put("requiresApproval", bool(entry.get("requiresApproval")));
      normalized.put("inputSchema", entry.getOrDefault("inputSchema", Map.of()));
      out.add(normalized);
    }
    return out;
  }

  private static List<Map<String, Object>> prompts(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw ConnectionProfiles.invalid("promptRefs 必须是数组");
    }
    if (list.size() > MAX_PROMPTS) {
      throw ConnectionProfiles.budget("提示词引用最多 " + MAX_PROMPTS + " 条");
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> entry = ConnectionProfiles.object(item, "promptRefs", PROMPT_KEYS);
      String ref = ConnectionProfiles.text(entry, "ref", "提示词引用", 128, ConnectionProfiles.REF);
      int version = integer(entry.get("version"), "提示词版本");
      out.add(Map.of("ref", ref, "version", version));
    }
    return out;
  }

  /**
   * 展示形态是"读起来怎么显示"的声明，键集合由产品形态决定、不在这里枚举；但必须是小而扁的 JSON 对象，
   * 且同样禁止明文凭据（展示层不应该成为 secret 的旁路）。
   */
  private static Map<String, Object> presentation(Object raw, String field) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw ConnectionProfiles.invalid(field + " 必须是 JSON 对象");
    }
    if (map.size() > MAX_PRESENTATION_KEYS) {
      throw ConnectionProfiles.budget(field + " 最多声明 " + MAX_PRESENTATION_KEYS + " 个键");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw ConnectionProfiles.invalid(field + " 的键必须是字符串");
      }
      if (Json.write(entry.getValue()).length() > 4096) {
        throw ConnectionProfiles.budget(field + " 单个条目超过 4KB: " + key);
      }
      out.put(key, entry.getValue());
    }
    ConnectionProfiles.rejectPlaintextSecrets(out, Set.of());
    return out;
  }

  private static int integer(Object raw, String label) {    if (!(raw instanceof Number number) || number.intValue() < 1 || number.intValue() > 1_000_000) {
      throw ConnectionProfiles.invalid(label + " 必须是正整数");
    }
    return number.intValue();
  }

  private static boolean bool(Object raw) {
    return raw instanceof Boolean value && value;
  }
}
