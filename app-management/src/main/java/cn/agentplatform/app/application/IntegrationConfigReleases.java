package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.AssistantExperienceRepository;
import cn.agentplatform.app.infra.ConfigReleaseRepository;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.ConnectionProfileRepository;
import cn.agentplatform.app.infra.IntegrationProfileRepository;
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
 * 配置发布：把"某个应用版本 + 某个体验修订 + 某个接入档案修订 + 一组连接引用"冻结成不可变快照。
 *
 * <p>发布链路是 DRAFT → VALIDATED → APPROVED → ACTIVE，每一步只允许前一步的唯一后继；激活是作用域内
 * 指针切换（CAS），同一作用域永远只有一个 ACTIVE。摘要只覆盖声明与版本引用，凭据永远不进来。
 */
@Service
public class IntegrationConfigReleases {

  static final Set<String> BODY_KEYS = Set.of("expectedRev", "requestId", "releaseId", "appCode",
      "appVersion", "experienceRev", "integrationProfileId", "integrationProfileRev",
      "connectionProfileRefs", "activationScope");
  private static final Set<String> REF_KEYS = Set.of("profileId", "profileRev");
  private static final int MAX_REFS = 16;

  private final ConfigReleaseRepository store;
  private final AssistantExperienceRepository experienceStore;
  private final AssistantExperiences experiences;
  private final IntegrationProfileRepository profileStore;
  private final IntegrationProfiles profiles;
  private final ConnectionProfileRepository connections;
  private final ConfigWriteLog writes;

  public IntegrationConfigReleases(ConfigReleaseRepository store,
      AssistantExperienceRepository experienceStore, AssistantExperiences experiences,
      IntegrationProfileRepository profileStore, IntegrationProfiles profiles,
      ConnectionProfileRepository connections, ConfigWriteLog writes) {
    this.store = store;
    this.experienceStore = experienceStore;
    this.experiences = experiences;
    this.profileStore = profileStore;
    this.profiles = profiles;
    this.connections = connections;
    this.writes = writes;
  }

  public Map<String, Object> get(String releaseId) {
    Actor actor = Actor.current();
    Map<String, Object> row = store.find(actor, releaseId);
    if (row == null) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
    }
    return row;
  }

  public List<Map<String, Object>> list(String appCode) {
    return store.list(Actor.current(), appCode);
  }

  public Map<String, Object> create(Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, BODY_KEYS, "配置发布");
    if (body.containsKey("expectedRev")) {
      throw ConnectionProfiles.invalid("新建配置发布不接受 expectedRev");
    }
    String releaseId = ConnectionProfiles.id(body, "releaseId");
    String appCode = ConnectionProfiles.text(body, "appCode", "appCode", 64);
    int appVersion = positive(body.get("appVersion"), "appVersion");
    int experienceRev = positive(body.get("experienceRev"), "experienceRev");
    String profileId = ConnectionProfiles.text(body, "integrationProfileId", "integrationProfileId", 64,
        ConnectionProfiles.ID);
    int profileRev = positive(body.get("integrationProfileRev"), "integrationProfileRev");
    List<Map<String, Object>> refs = refs(body.get("connectionProfileRefs"));
    String scope = ConnectionProfiles.text(body, "activationScope", "activationScope", 32,
        EnvironmentBindings.ENVIRONMENT);
    String requestId = ConnectionProfiles.requestId(body);

    Map<String, Object> version = store.appVersion(actor, appCode, appVersion);
    if (version == null) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "应用版本不存在: " + appCode + "@" + appVersion);
    }
    requireExperience(actor, appCode, experienceRev);
    requireProfile(actor, profileId, profileRev);
    for (Map<String, Object> ref : refs) {
      requireConnection(actor, ref);
    }
    String candidate = String.valueOf(version.get("candidate_sha256"));
    String digest = digest(appCode, appVersion, candidate, experienceRev, profileId, profileRev, refs);
    String sha = Digest.sha256(Json.canonical(List.of("config-release.create", releaseId, digest)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "config-release.create", releaseId,
        requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> release = new LinkedHashMap<>();
    release.put("releaseId", releaseId);
    release.put("appCode", appCode);
    release.put("appVersion", appVersion);
    release.put("candidateSha256", candidate);
    release.put("experienceRev", experienceRev);
    release.put("integrationProfileId", profileId);
    release.put("integrationProfileRev", profileRev);
    release.put("connectionProfileRefs", refs);
    release.put("activationScope", scope);
    release.put("digest", digest);
    store.insert(actor, release);
    Map<String, Object> out = view(actor, releaseId, requestId);
    writes.remember(actor.tenantId(), "config-release.create", releaseId, requestId, sha, out);
    return out;
  }

  /** 校验：引用仍然存在且都处于 APPROVED，应用版本未变；通过后落到 VALIDATED。 */
  public Map<String, Object> validate(String releaseId, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("requestId"), "配置发布");
    String requestId = ConnectionProfiles.requestId(body);
    Map<String, Object> row = get(releaseId);
    if (!"DRAFT".equals(String.valueOf(row.get("status")))) {
      throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
          "状态 " + row.get("status") + " 不能校验，只有 DRAFT 可以校验");
    }
    String appCode = String.valueOf(row.get("appCode"));
    int appVersion = ((Number) row.get("appVersion")).intValue();
    Map<String, Object> version = store.appVersion(actor, appCode, appVersion);
    if (version == null || !String.valueOf(version.get("candidate_sha256"))
        .equals(String.valueOf(row.get("candidateSha256")))) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "应用版本已变化或不存在: " + appCode + "@" + appVersion);
    }
    experiences.requireApproved(actor, appCode, ((Number) row.get("experienceRev")).intValue());
    profiles.requireApproved(actor, String.valueOf(row.get("integrationProfileId")),
        ((Number) row.get("integrationProfileRev")).intValue());
    for (Object raw : (List<?>) row.get("connectionProfileRefs")) {
      requireConnection(actor, asRef(raw));
    }
    String sha = Digest.sha256(Json.canonical(List.of("config-release.validate", releaseId, requestId)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "config-release.validate", releaseId,
        requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.transition(actor, releaseId, "DRAFT", "VALIDATED", requestId,
        () -> view(actor, releaseId, requestId));
    writes.remember(actor.tenantId(), "config-release.validate", releaseId, requestId, sha, out);
    return out;
  }

  public Map<String, Object> approve(String releaseId, Map<String, Object> body) {
    return move(releaseId, body, "approve", "VALIDATED", "APPROVED", store::transition);
  }

  /** 激活：作用域指针切换，旧的 ACTIVE 自动退位为 SUPERSEDED。 */
  public Map<String, Object> activate(String releaseId, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("requestId"), "配置发布");
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("config-release.activate", releaseId, requestId)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "config-release.activate", releaseId,
        requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.activate(actor, releaseId, requestId,
        () -> view(actor, releaseId, requestId));
    writes.remember(actor.tenantId(), "config-release.activate", releaseId, requestId, sha, out);
    return out;
  }

  public Map<String, Object> disable(String releaseId, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("requestId"), "配置发布");
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("config-release.disable", releaseId, requestId)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "config-release.disable", releaseId,
        requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.disable(actor, releaseId, requestId,
        () -> view(actor, releaseId, requestId));
    writes.remember(actor.tenantId(), "config-release.disable", releaseId, requestId, sha, out);
    return out;
  }

  /**
   * 对外体验投影：只返回激活发布对应的体验声明，草稿与未激活版本一律不出现。
   * 无激活发布时按契约返回 CONFIG_RELEASE_NOT_ACTIVE（不降级为"空体验"）。
   */
  public Map<String, Object> experience(Actor actor, String appCode, String scope) {
    Map<String, Object> release = store.active(actor, appCode, scope);
    if (release == null) {
      throw new PlatformException(409, "CONFIG_RELEASE_NOT_ACTIVE",
          "应用 " + appCode + " 在作用域 " + scope + " 没有激活的配置发布");
    }
    int experienceRev = ((Number) release.get("experienceRev")).intValue();
    Map<String, Object> experience = experiences.get(appCode);
    Map<String, Object> revision = revision(experience, experienceRev);
    String profileId = String.valueOf(release.get("integrationProfileId"));
    int profileRev = ((Number) release.get("integrationProfileRev")).intValue();
    Map<String, Object> profileRevision = profiles.requireApproved(actor, profileId, profileRev);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schemaVersion", "v1");
    out.put("appCode", appCode);
    out.put("experienceRev", experienceRev);
    out.put("configReleaseRef", Map.of("configReleaseId", release.get("releaseId"),
        "digest", release.get("digest")));
    out.put("title", revision.get("title"));
    out.put("welcomeText", revision.get("welcomeText"));
    out.put("capabilities", capabilities(actor, appCode, ((Number) release.get("appVersion")).intValue(),
        revision));
    out.put("prompts", prompts(actor, revision));
    out.put("inputPresentation", revision.get("inputPresentation"));
    out.put("resultPresentation", revision.get("resultPresentation"));
    out.put("helpRefs", revision.get("helpRefs"));
    out.put("integrationProfileRef", Map.of("profileId", profileId, "profileRev", profileRev,
        "mode", profileRevision.get("mode"), "environment", profileRevision.get("environment")));
    return out;
  }

  /**
   * 能力清单按"该应用版本实际引用的技能"过滤：体验里声明了但版本没引用的能力不对外出现，
   * 避免文案承诺了引擎并不具备的能力。
   */
  private List<Map<String, Object>> capabilities(Actor actor, String appCode, int appVersion,
      Map<String, Object> revision) {
    Set<String> declared = store.skills(actor, appCode, appVersion);
    Object raw = revision.get("capabilityDescriptions");
    List<Map<String, Object>> out = new ArrayList<>();
    if (!(raw instanceof List<?> list)) {
      return out;
    }
    for (Object item : list) {
      Map<String, Object> entry = asMap(item);
      String code = String.valueOf(entry.get("code"));
      if (!declared.isEmpty() && !declared.contains(code)) {
        continue;
      }
      Map<String, Object> capability = new LinkedHashMap<>();
      capability.put("code", code);
      capability.put("label", entry.getOrDefault("label", code));
      capability.put("description", entry.getOrDefault("description", ""));
      capability.put("readOnly", Boolean.TRUE.equals(entry.get("readOnly")));
      capability.put("requiresApproval", Boolean.TRUE.equals(entry.get("requiresApproval")));
      capability.put("inputSchema", entry.getOrDefault("inputSchema", Map.of()));
      out.add(capability);
    }
    return out;
  }

  /** 提示词引用只回"存在且已发布"的模板版本；未发布的版本不回给调用方。 */
  private List<Map<String, Object>> prompts(Actor actor, Map<String, Object> revision) {
    Object raw = revision.get("promptRefs");
    List<Map<String, Object>> out = new ArrayList<>();
    if (!(raw instanceof List<?> list)) {
      return out;
    }
    for (Object item : list) {
      Map<String, Object> ref = asMap(item);
      String template = String.valueOf(ref.get("ref"));
      int version = ((Number) ref.getOrDefault("version", 1)).intValue();
      Map<String, Object> template_version = store.publishedPrompt(actor, template, version);
      if (template_version == null) {
        continue;
      }
      Map<String, Object> prompt = new LinkedHashMap<>();
      prompt.put("ref", template);
      prompt.put("version", version);
      prompt.put("title", template_version.get("title"));
      prompt.put("variables", template_version.get("variables"));
      prompt.put("readOnly", true);
      prompt.put("hasSideEffects", false);
      out.add(prompt);
    }
    return out;
  }

  private Map<String, Object> move(String releaseId, Map<String, Object> body, String operation,
      String from, String to, Transition transition) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("requestId"), "配置发布");
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("config-release." + operation, releaseId, requestId)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "config-release." + operation,
        releaseId, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = transition.apply(actor, releaseId, from, to, requestId,
        () -> view(actor, releaseId, requestId));
    writes.remember(actor.tenantId(), "config-release." + operation, releaseId, requestId, sha, out);
    return out;
  }

  @FunctionalInterface
  private interface Transition {
    Map<String, Object> apply(Actor actor, String releaseId, String from, String to, String requestId,
        java.util.function.Supplier<Map<String, Object>> response);
  }

  private Map<String, Object> view(Actor actor, String releaseId, String requestId) {
    Map<String, Object> row = store.find(actor, releaseId);
    if (row == null) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
    }
    row.put("requestId", requestId);
    row.put("replayed", false);
    return row;
  }

  private static Map<String, Object> revision(Map<String, Object> experience, int rev) {
    Object raw = experience.get("revisions");
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        Map<String, Object> entry = asMap(item);
        if (((Number) entry.getOrDefault("experienceRev", 0)).intValue() == rev) {
          return entry;
        }
      }
    }
    throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN", "体验修订不存在: " + rev);
  }

  private void requireExperience(Actor actor, String appCode, int rev) {
    var state = experienceStore.state(actor, appCode, rev);
    if (state == null || !state.revisionExists()) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "体验修订不存在: " + appCode + "@" + rev);
    }
  }

  private void requireProfile(Actor actor, String profileId, int rev) {
    var state = profileStore.state(actor, profileId, rev);
    if (state == null) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入档案不存在: " + profileId);
    }
    if (!state.revisionExists()) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "接入档案修订不存在: " + profileId + "@" + rev);
    }
  }

  private void requireConnection(Actor actor, Map<String, Object> ref) {
    String profileId = String.valueOf(ref.get("profileId"));
    int rev = ((Number) ref.get("profileRev")).intValue();
    var state = connections.profileState(actor, profileId, rev);
    if (state.isEmpty()) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "连接档案不存在: " + profileId);
    }
    if (!state.get().revisionExists()) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "连接档案修订不存在: " + profileId + "@" + rev);
    }
  }

  private static List<Map<String, Object>> refs(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw ConnectionProfiles.invalid("connectionProfileRefs 必须是数组");
    }
    if (list.size() > MAX_REFS) {
      throw ConnectionProfiles.budget("连接引用最多 " + MAX_REFS + " 条");
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> ref = ConnectionProfiles.object(item, "connectionProfileRefs", REF_KEYS);
      String profileId = ConnectionProfiles.text(ref, "profileId", "connectionProfileRefs", 64,
          ConnectionProfiles.ID);
      int rev = positive(ref.get("profileRev"), "connectionProfileRefs.profileRev");
      out.add(Map.of("profileId", profileId, "profileRev", rev));
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> asRef(Object raw) {
    Map<String, Object> ref = asMap(raw);
    return Map.of("profileId", String.valueOf(ref.get("profileId")),
        "profileRev", ((Number) ref.get("profileRev")).intValue());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object raw) {
    if (raw instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(String.valueOf(k), v));
      return out;
    }
    throw ConnectionProfiles.invalid("期望 JSON 对象，实际: " + raw);
  }

  private static int positive(Object raw, String label) {
    if (!(raw instanceof Number number) || number.intValue() < 1) {
      throw ConnectionProfiles.invalid(label + " 必须是正整数");
    }
    return number.intValue();
  }

  /** 摘要只覆盖声明与版本引用：应用版本候选摘要、体验修订、接入档案修订、连接引用集合。 */
  private static String digest(String appCode, int appVersion, String candidate, int experienceRev,
      String profileId, int profileRev, List<Map<String, Object>> refs) {
    List<Map<String, Object>> sorted = new ArrayList<>(refs);
    sorted.sort((a, b) -> String.valueOf(a.get("profileId")).compareTo(String.valueOf(b.get("profileId"))));
    return Digest.sha256(Json.canonical(List.of("config-release", appCode, appVersion, candidate,
        experienceRev, profileId, profileRev, sorted)));
  }
}
