package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.AssistantExperienceRepository;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.IntegrationProfileRepository;
import cn.agentplatform.app.infra.IntegrationProfileRepository.Revision;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 接入档案（IntegrationProfile）的编辑面：某个业务软件以哪个接入等级、哪个环境、哪些来源暴露给哪个应用。
 *
 * <p>接入等级（L0~L3）在这里是声明而不是能力：声明之后仍需经过配置发布与运行时授权才会真的生效，
 * 因此本服务不启动任何外部连通性动作，也不接受来源白名单之外的模糊匹配。
 */
@Service
public class IntegrationProfiles {

  static final Set<String> MODES = Set.of("L0", "L1", "L2", "L3");
  static final Set<String> BODY_KEYS = Set.of("expectedRev", "requestId", "profileId", "appCode",
      "bizSystemCode", "environment", "mode", "allowedOrigins", "contextSchema", "credentialPolicyRef",
      "experienceRef");
  private static final Set<String> EXPERIENCE_REF_KEYS = Set.of("appCode", "experienceRev");
  private static final int MAX_ORIGINS = 8;
  private static final int MAX_CONTEXT_KEYS = 32;

  private final IntegrationProfileRepository store;
  private final AssistantExperienceRepository experiences;
  private final ConfigWriteLog writes;

  public IntegrationProfiles(IntegrationProfileRepository store,
      AssistantExperienceRepository experiences, ConfigWriteLog writes) {
    this.store = store;
    this.experiences = experiences;
    this.writes = writes;
  }

  public Map<String, Object> get(String profileId) {
    Actor actor = Actor.current();
    return store.view(actor, profileId)
        .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入档案不存在: " + profileId));
  }

  public List<Map<String, Object>> list(String appCode) {
    return store.all(Actor.current(), appCode);
  }

  public Map<String, Object> create(Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, BODY_KEYS, "接入档案");
    if (body.containsKey("expectedRev")) {
      throw ConnectionProfiles.invalid("新建接入档案不接受 expectedRev，首次写入的当前修订恒为 1");
    }
    String profileId = ConnectionProfiles.id(body, "profileId");
    String appCode = ConnectionProfiles.text(body, "appCode", "appCode", 64);
    requireApp(actor, appCode);
    Revision revision = declaration(actor, body);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("integration-profile.create", profileId,
        appCode, revision.sha256())));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "integration-profile.create",
        profileId, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.create(actor, profileId, appCode, revision, requestId,
        () -> view(actor, profileId));
    writes.remember(actor.tenantId(), "integration-profile.create", profileId, requestId, sha, out);
    return out;
  }

  public Map<String, Object> revise(String profileId, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, BODY_KEYS, "接入档案");
    int expectedRev = ConnectionProfiles.expectedRev(body);
    Revision revision = declaration(actor, body);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("integration-profile.revise", profileId,
        expectedRev, revision.sha256())));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "integration-profile.revise",
        profileId, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.revise(actor, profileId, expectedRev, revision, requestId,
        () -> view(actor, profileId));
    writes.remember(actor.tenantId(), "integration-profile.revise", profileId, requestId, sha, out);
    return out;
  }

  public Map<String, Object> approve(String profileId, Map<String, Object> body) {
    return transition(profileId, body, "approve", "DRAFT", "APPROVED");
  }

  public Map<String, Object> disable(String profileId, Map<String, Object> body) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("expectedRev", "requestId"), "接入档案");
    int expectedRev = ConnectionProfiles.expectedRev(body);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("integration-profile.disable", profileId, expectedRev)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "integration-profile.disable",
        profileId, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.transition(actor, profileId, expectedRev, "APPROVED", "DISABLED",
        requestId, () -> view(actor, profileId));
    writes.remember(actor.tenantId(), "integration-profile.disable", profileId, requestId, sha, out);
    return out;
  }

  public IntegrationProfileRepository.State state(Actor actor, String profileId, int profileRev) {
    return store.state(actor, profileId, profileRev);
  }

  /** 配置发布校验会复用：档案修订必须存在且处于 APPROVED。 */
  public Map<String, Object> requireApproved(Actor actor, String profileId, int profileRev) {
    var state = store.state(actor, profileId, profileRev);
    if (state == null) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入档案不存在: " + profileId);
    }
    if (!state.revisionExists()) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "接入档案修订不存在: " + profileId + "@" + profileRev);
    }
    if (!"APPROVED".equals(state.status())) {
      throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
          "接入档案状态 " + state.status() + " 不能用于配置发布");
    }
    return store.revision(actor, profileId, profileRev);
  }

  private Map<String, Object> transition(String profileId, Map<String, Object> body, String operation,
      String from, String to) {
    Actor actor = Actor.current();
    ConnectionProfiles.keys(body, Set.of("expectedRev", "requestId"), "接入档案");
    int expectedRev = ConnectionProfiles.expectedRev(body);
    String requestId = ConnectionProfiles.requestId(body);
    String sha = Digest.sha256(Json.canonical(List.of("integration-profile." + operation, profileId, expectedRev)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "integration-profile." + operation,
        profileId, requestId, sha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.transition(actor, profileId, expectedRev, from, to, requestId,
        () -> view(actor, profileId));
    writes.remember(actor.tenantId(), "integration-profile." + operation, profileId, requestId, sha, out);
    return out;
  }

  private Map<String, Object> view(Actor actor, String profileId) {
    return store.view(actor, profileId)
        .orElseThrow(() -> new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入档案不存在: " + profileId));
  }

  private void requireApp(Actor actor, String appCode) {
    if (!store.appExists(actor, appCode)) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
    }
  }

  private Revision declaration(Actor actor, Map<String, Object> body) {
    ConnectionProfiles.rejectPlaintextSecrets(body, Set.of());
    String bizSystemCode = ConnectionProfiles.text(body, "bizSystemCode", "接入档案", 64);
    String environment = ConnectionProfiles.text(body, "environment", "environment", 32,
        EnvironmentBindings.ENVIRONMENT);
    String mode = ConnectionProfiles.oneOf(ConnectionProfiles.text(body, "mode", "mode", 2), MODES, "接入等级");
    List<String> origins = origins(body.get("allowedOrigins"));
    Map<String, Object> context = contextSchema(body.get("contextSchema"));
    String credentialPolicyRef = body.containsKey("credentialPolicyRef")
        ? ConnectionProfiles.text(body, "credentialPolicyRef", "credentialPolicyRef", 512, ConnectionProfiles.REF)
        : "";
    Map<String, Object> experienceRef = experienceRef(actor, body.get("experienceRef"));
    Map<String, Object> declared = new LinkedHashMap<>();
    declared.put("bizSystemCode", bizSystemCode);
    declared.put("environment", environment);
    declared.put("mode", mode);
    declared.put("allowedOrigins", origins);
    declared.put("contextSchema", context);
    declared.put("credentialPolicyRef", credentialPolicyRef);
    declared.put("experienceRef", experienceRef);
    return new Revision(bizSystemCode, environment, mode, origins, context, credentialPolicyRef,
        experienceRef, Digest.sha256(Json.canonical(declared)));
  }

  private static List<String> origins(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw ConnectionProfiles.invalid("allowedOrigins 必须是数组");
    }
    if (list.size() > MAX_ORIGINS) {
      throw ConnectionProfiles.budget("来源白名单最多 " + MAX_ORIGINS + " 条");
    }
    var out = new java.util.ArrayList<String>();
    for (Object item : list) {
      if (!(item instanceof String s)) {
        throw ConnectionProfiles.invalid("allowedOrigins 只能包含字符串");
      }
      out.add(ConnectionProfiles.origin(s, "allowedOrigins"));
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> contextSchema(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw ConnectionProfiles.invalid("contextSchema 必须是 JSON 对象");
    }
    if (map.size() > MAX_CONTEXT_KEYS) {
      throw ConnectionProfiles.budget("上下文声明最多 " + MAX_CONTEXT_KEYS + " 个字段");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw ConnectionProfiles.invalid("contextSchema 的键必须是字符串");
      }
      if (Json.write(entry.getValue()).length() > 4096) {
        throw ConnectionProfiles.budget("contextSchema 单个字段超过 4KB: " + key);
      }
      out.put(key, entry.getValue());
    }
    ConnectionProfiles.rejectPlaintextSecrets(out, Set.of());
    return out;
  }

  private Map<String, Object> experienceRef(Actor actor, Object raw) {
    Map<String, Object> ref = ConnectionProfiles.object(raw, "experienceRef", EXPERIENCE_REF_KEYS);
    if (ref == null) {
      throw ConnectionProfiles.invalid("缺少或无效字段: experienceRef（必须声明 appCode 与 experienceRev）");
    }
    String appCode = ConnectionProfiles.text(ref, "appCode", "experienceRef.appCode", 64);
    Object revRaw = ref.get("experienceRev");
    if (!(revRaw instanceof Number number) || number.intValue() < 1) {
      throw ConnectionProfiles.invalid("experienceRef.experienceRev 必须是正整数");
    }
    int rev = number.intValue();
    var state = experiences.state(actor, appCode, rev);
    if (state == null || !state.revisionExists()) {
      throw new PlatformException(400, "CONFIG_VERSION_UNKNOWN",
          "体验修订不存在: " + appCode + "@" + rev);
    }
    return Map.of("appCode", appCode, "experienceRev", rev);
  }
}
