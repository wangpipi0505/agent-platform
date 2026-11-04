package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.AssistantExperienceRepository;
import cn.agentplatform.app.infra.ConfigReleaseRepository;
import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.domain.PublishedMetadata;
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
import org.springframework.beans.factory.annotation.Autowired;
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
      "connectionProfileRefs", "semanticBindings", "activationScope");
  private static final Set<String> REF_KEYS = Set.of("profileId", "profileRev", "bindingId", "bindingRev",
      "operation", "environment", "resolutionDigest");
  private static final Set<String> SEMANTIC_REF_KEYS = Set.of("bindingId", "bindingRev");
  private static final int MAX_REFS = 16;
  private static final int MAX_SEMANTIC_REFS = 8;

  private final ConfigReleaseRepository store;
  private final AssistantExperienceRepository experienceStore;
  private final AssistantExperiences experiences;
  private final IntegrationProfileRepository profileStore;
  private final IntegrationProfiles profiles;
  private final ConnectionProfileRepository connections;
  private final ConfigWriteLog writes;
  private final PublishedMetadataService publishedMetadata;
  private final SemanticBindings bindings;

  /** 兼容旧装配（阶段1测试直接 new）：不注入语义绑定目录时，发布不允许声明 semanticBindings。 */
  public IntegrationConfigReleases(ConfigReleaseRepository store,
      AssistantExperienceRepository experienceStore, AssistantExperiences experiences,
      IntegrationProfileRepository profileStore, IntegrationProfiles profiles,
      ConnectionProfileRepository connections, ConfigWriteLog writes,
      PublishedMetadataService publishedMetadata) {
    this(store, experienceStore, experiences, profileStore, profiles, connections, writes,
        publishedMetadata, null);
  }

  @Autowired
  public IntegrationConfigReleases(ConfigReleaseRepository store,
      AssistantExperienceRepository experienceStore, AssistantExperiences experiences,
      IntegrationProfileRepository profileStore, IntegrationProfiles profiles,
      ConnectionProfileRepository connections, ConfigWriteLog writes,
      PublishedMetadataService publishedMetadata, SemanticBindings bindings) {
    this.store = store;
    this.experienceStore = experienceStore;
    this.experiences = experiences;
    this.profileStore = profileStore;
    this.profiles = profiles;
    this.connections = connections;
    this.writes = writes;
    this.publishedMetadata = publishedMetadata;
    this.bindings = bindings;
  }

  public Map<String, Object> get(String releaseId) {
    Actor actor = Actor.current();
    Map<String, Object> row = store.find(actor, releaseId);
    if (row == null) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
    }
    return withSemanticDetails(actor, row);
  }

  public List<Map<String, Object>> list(String appCode) {
    Actor actor = Actor.current();
    return store.list(actor, appCode).stream().map(row -> withSemanticDetails(actor, row)).toList();
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
    List<Map<String, Object>> semantic = semanticRefs(body.get("semanticBindings"));
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
    // 绑定修订在创建时钉住：只核对存在与所属应用；撤权状态留到 validate/activate 再拒绝，
    // 这样配置者能看到"先建后校验失败"的明确错误，而不是创建时被含糊地挡住。
    requireSemanticRevisions(actor, appCode, semantic);
    String candidate = String.valueOf(version.get("candidate_sha256"));
    String digest = digest(appCode, appVersion, candidate, experienceRev, profileId, profileRev, refs,
        semantic);
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
    release.put("semanticBindings", semantic);
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
    requireSemanticStillValid(actor, row);
    requireCapabilitiesBacked(actor, row);
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
    // 激活前重新核对引用：批准之后仍可能有人停用连接档案、改回体验草稿或重发应用版本。
    // 只在 validate 时看一次是不够的——那之后到真正切换指针之间隔着任意长的时间。
    requireReferencesStillValid(actor, releaseId);
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
    int appVersion = ((Number) release.get("appVersion")).intValue();
    // 对外投影必须站在"当前真实发布版本"上：配置发布钉住的是某一次发布，应用重新发布之后
    // 旧配置就不该继续代表这个应用的能力面（文案与引擎实际能力会分叉）。
    PublishedMetadata published = publishedMetadata.composition(appCode);
    if (published.versionNo() != appVersion) {
      throw new PlatformException(409, "CONFIG_RELEASE_NOT_ACTIVE",
          "激活配置固定在应用版本 " + appVersion + "，当前发布版本是 " + published.versionNo()
              + "，请针对新版本重新激活配置");
    }
    if (!String.valueOf(published.versionSha256())
        .equals(String.valueOf(release.get("candidateSha256")))) {
      throw new PlatformException(409, "CONFIG_RELEASE_NOT_ACTIVE",
          "激活配置的候选摘要与当前发布版本不一致，请重新创建并激活配置发布");
    }
    int experienceRev = ((Number) release.get("experienceRev")).intValue();
    Map<String, Object> experience = experiences.get(appCode);
    Map<String, Object> revision = revision(experience, experienceRev);
    String profileId = String.valueOf(release.get("integrationProfileId"));
    int profileRev = ((Number) release.get("integrationProfileRev")).intValue();
    Map<String, Object> profileRevision = profiles.requireApproved(actor, profileId, profileRev);
    List<Map<String, Object>> semantic = semanticDetails(actor, release);
    for (Map<String, Object> binding : semantic) {
      if (!"ACTIVE".equals(String.valueOf(binding.get("status")))) {
        // 撤权优先：旧发布可以回查自己钉住的修订，但运行投影不能再把已撤权的绑定当能力面。
        throw new PlatformException(409, "SEMANTIC_REF_INVALID",
            "配置发布引用的语义绑定已停用: " + binding.get("bindingId"));
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("schemaVersion", "v1");
    out.put("appCode", appCode);
    out.put("experienceRev", experienceRev);
    out.put("configReleaseRef", Map.of("configReleaseId", release.get("releaseId"),
        "digest", release.get("digest")));
    out.put("title", revision.get("title"));
    out.put("welcomeText", revision.get("welcomeText"));
    out.put("capabilities", capabilities(appCode, published.versionNo(), revision));
    out.put("prompts", prompts(actor, revision));
    out.put("inputPresentation", revision.get("inputPresentation"));
    out.put("resultPresentation", revision.get("resultPresentation"));
    out.put("helpRefs", revision.get("helpRefs"));
    out.put("integrationProfileRef", Map.of("profileId", profileId, "profileRev", profileRev,
        "mode", profileRevision.get("mode"), "environment", profileRevision.get("environment")));
    out.put("semanticBindings", semantic);
    // 仅版本引用可向运行期投影：地址/secret_ref仍由内部批准解析按当前授权读取。
    var runtimeRefs = new ArrayList<Map<String, Object>>();
    for (var ref : refs(release.get("connectionProfileRefs"))) {
      if (!ref.containsKey("bindingId")) continue; // 历史目录引用不被猜成一个可执行连接。
      var fixed = new LinkedHashMap<>(ref);
      fixed.putIfAbsent("environment", profileRevision.get("environment"));
      runtimeRefs.add(fixed);
    }
    out.put("bindingRefs", runtimeRefs);
    return out;
  }

  /**
   * 能力清单按"当前发布版本精确引用的技能版本"过滤：体验里声明了但版本没引用的能力不对外出现，
   * 避免文案承诺了引擎并不具备的能力。引用来源是发布元数据（不可变版本 + skill_reference），
   * 不是客户端传来的 appVersion。
   */
  private List<Map<String, Object>> capabilities(String appCode, int publishedVersion,
      Map<String, Object> revision) {
    Set<String> declared = new java.util.LinkedHashSet<>();
    for (Map<String, Object> ref : publishedMetadata.skillReferences(appCode, publishedVersion)) {
      declared.add(String.valueOf(ref.get("code")));
    }
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
    // 发布钉住的是不可变应用版本，所以"精确技能引用"可以从版本号确定性读回；
    // 对外回显它们，让配置者看到这份发布究竟绑定了哪些技能版本，而不是只看到一句版本号。
    row.put("skillRefs", publishedMetadata.skillReferences(String.valueOf(row.get("appCode")),
        ((Number) row.get("appVersion")).intValue()));
    row.put("requestId", requestId);
    row.put("replayed", false);
    return row;
  }

  /**
   * 激活前复核：这份发布当初校验过的引用，现在是否还有效。
   *
   * <p>只在 validate 时看一次不够——批准到真正切换指针之间可能过了很久，期间应用可能重发、
   * 体验可能被改回草稿、接入档案可能被停用、连接档案可能被撤权。任一项不再成立就不切指针，
   * 让配置者重新走校验，而不是让过期组合悄悄上线。
   */
  private void requireReferencesStillValid(Actor actor, String releaseId) {
    Map<String, Object> row = store.find(actor, releaseId);
    if (row == null) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "配置发布不存在: " + releaseId);
    }
    String status = String.valueOf(row.get("status"));
    if (!"APPROVED".equals(status)) {
      throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
          "状态 " + status + " 不能激活，只有 APPROVED 可以激活");
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
      requireActiveConnection(actor, asRef(raw));
    }
    requireSemanticStillValid(actor, row);
    requireCapabilitiesBacked(actor, row);
  }

  /**
   * 体验声明的每一项能力，必须由钉住的应用版本真实引用（{@code app.skill_reference}）。
   * 投影阶段已经按这个口径过滤，但过滤是"少给"；校验阶段要直接拦下来，否则配置者会以为
   * 自己发布了十项能力，实际对外只出现三项。
   *
   * <p>版本完全没有技能引用时不设限（纯对话应用），与投影口径保持一致。
   */
  private void requireCapabilitiesBacked(Actor actor, Map<String, Object> row) {
    String appCode = String.valueOf(row.get("appCode"));
    int appVersion = ((Number) row.get("appVersion")).intValue();
    Set<String> declared = new java.util.LinkedHashSet<>();
    for (Map<String, Object> ref : publishedMetadata.skillReferences(appCode, appVersion)) {
      declared.add(String.valueOf(ref.get("code")));
    }
    if (declared.isEmpty()) {
      return;
    }
    Map<String, Object> revision = revision(experiences.get(appCode),
        ((Number) row.get("experienceRev")).intValue());
    Object raw = revision.get("capabilityDescriptions");
    if (!(raw instanceof List<?> list)) {
      return;
    }
    for (Object item : list) {
      String code = String.valueOf(asMap(item).get("code"));
      if (!declared.contains(code)) {
        throw new PlatformException(400, "CONFIG_STATE_TRANSITION_INVALID",
            "体验声明的能力在该应用版本没有对应技能引用: " + code + "（" + appCode + "@" + appVersion + "）");
      }
    }
  }

  /** 激活阶段的连接引用还要求档案处于 APPROVED：停用或未批准都不该带着上线。 */
  private void requireActiveConnection(Actor actor, Map<String, Object> ref) {
    String profileId = String.valueOf(ref.get("profileId"));
    requireConnection(actor, ref);
    var state = connections.profileState(actor, profileId,
        ((Number) ref.get("profileRev")).intValue());
    if (state.isEmpty()) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "连接档案不存在: " + profileId);
    }
    if (!"APPROVED".equals(state.get().status())) {
      throw new PlatformException(409, "CONFIG_RELEASE_NOT_ACTIVE",
          "被引用的连接档案已停用或尚未批准: " + profileId + "（当前状态 " + state.get().status() + "）");
    }
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
    if (ref.containsKey("bindingId") && connections.pinnedBindingRevisions(actor, profileId, rev).stream()
        .noneMatch(binding -> ref.get("bindingId").equals(binding.get("bindingId"))
            && ref.get("bindingRev").equals(binding.get("bindingRev"))
            && (!ref.containsKey("environment") || ref.get("environment").equals(binding.get("environment"))))) {
      throw new PlatformException(409, "CONNECTION_BINDING_UNAVAILABLE", "配置引用的精确批准绑定不可用");
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
      var fixed = new LinkedHashMap<String, Object>();
      fixed.put("profileId", profileId); fixed.put("profileRev", rev);
      if (ref.keySet().stream().anyMatch(key -> !Set.of("profileId", "profileRev").contains(key))) {
        fixed.put("bindingId", ConnectionProfiles.text(ref, "bindingId", "connectionProfileRefs.bindingId", 48,
            ConnectionProfiles.ID));
        fixed.put("bindingRev", positive(ref.get("bindingRev"), "connectionProfileRefs.bindingRev"));
        fixed.put("operation", ConnectionProfiles.text(ref, "operation", "connectionProfileRefs.operation", 128,
            ConnectionProfiles.OPERATION));
        if (ref.containsKey("environment")) fixed.put("environment", ConnectionProfiles.text(ref, "environment", "environment", 32,
            java.util.regex.Pattern.compile("^[a-z][a-z0-9-]{0,31}$")));
        if (ref.containsKey("resolutionDigest")) fixed.put("resolutionDigest", ConnectionProfiles.text(ref, "resolutionDigest", "resolutionDigest", 64,
            java.util.regex.Pattern.compile("^[0-9a-f]{64}$")));
      }
      out.add(Map.copyOf(fixed));
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> asRef(Object raw) {
    return refs(List.of(raw)).getFirst();
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

  /** 对外/管理回看：把发布钉住的 bindingId+bindingRev 展开为不可变修订的可读投影。 */
  private Map<String, Object> withSemanticDetails(Actor actor, Map<String, Object> release) {
    release.put("semanticBindings", semanticDetails(actor, release));
    return release;
  }

  private List<Map<String, Object>> semanticDetails(Actor actor, Map<String, Object> release) {
    List<Map<String, Object>> refs = semanticRefs(release.get("semanticBindings"));
    if (refs.isEmpty()) {
      return List.of();
    }
    requireCatalog();
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> ref : refs) {
      out.add(bindings.pinned(actor, String.valueOf(release.get("appCode")),
          String.valueOf(ref.get("bindingId")), ((Number) ref.get("bindingRev")).intValue()));
    }
    return out;
  }

  private void requireSemanticRevisions(Actor actor, String appCode,
      List<Map<String, Object>> refs) {
    if (refs.isEmpty()) {
      return;
    }
    requireCatalog();
    for (Map<String, Object> ref : refs) {
      bindings.pinned(actor, appCode, String.valueOf(ref.get("bindingId")),
          ((Number) ref.get("bindingRev")).intValue());
    }
  }

  /** 兼容阶段1的 8 参构造：未装配目录时，任何语义引用都必须显式失败而不是被静默忽略。 */
  private void requireCatalog() {
    if (bindings == null) {
      throw new PlatformException(409, "CONFIG_RELEASE_NOT_ACTIVE",
          "语义绑定目录未装配，不能使用 semanticBindings");
    }
  }

  /** 校验/激活共用：绑定必须仍为 ACTIVE，撤权优先于历史快照。 */
  private void requireSemanticStillValid(Actor actor, Map<String, Object> row) {
    String appCode = String.valueOf(row.get("appCode"));
    List<Map<String, Object>> refs = rowRefs(row.get("semanticBindings"));
    if (refs.isEmpty()) {
      return;
    }
    requireCatalog();
    for (Map<String, Object> ref : refs) {
      bindings.live(actor, appCode, String.valueOf(ref.get("bindingId")),
          ((Number) ref.get("bindingRev")).intValue());
    }
  }

  /**
   * 内部行上的绑定引用可能已被 {@link #withSemanticDetails} 展开为可读投影（含 appCode/status 等），
   * 这里只取引用键；外部请求仍走 {@link #semanticRefs} 的严格白名单校验。
   */
  private static List<Map<String, Object>> rowRefs(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map) || !(map.get("bindingRev") instanceof Number rev)) {
        continue;
      }
      Object id = map.get("bindingId");
      if (id != null) {
        out.add(Map.of("bindingId", String.valueOf(id), "bindingRev", rev.intValue()));
      }
    }
    return out;
  }

  private static List<Map<String, Object>> semanticRefs(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw ConnectionProfiles.invalid("semanticBindings 必须是数组");
    }
    if (list.size() > MAX_SEMANTIC_REFS) {
      throw ConnectionProfiles.budget("语义绑定引用最多 " + MAX_SEMANTIC_REFS + " 条");
    }
    List<Map<String, Object>> out = new ArrayList<>();
    Set<String> seen = new java.util.LinkedHashSet<>();
    for (Object item : list) {
      Map<String, Object> ref = ConnectionProfiles.object(item, "semanticBindings", SEMANTIC_REF_KEYS);
      String bindingId = ConnectionProfiles.text(ref, "bindingId", "semanticBindings", 64,
          ConnectionProfiles.ID);
      int rev = positive(ref.get("bindingRev"), "semanticBindings.bindingRev");
      if (!seen.add(bindingId)) {
        throw ConnectionProfiles.invalid("同一语义绑定只能引用一次: " + bindingId);
      }
      out.add(Map.of("bindingId", bindingId, "bindingRev", rev));
    }
    return List.copyOf(out);
  }

  /** 摘要只覆盖声明与版本引用：应用版本、体验/接入修订、连接引用与语义绑定引用。 */
  private static String digest(String appCode, int appVersion, String candidate, int experienceRev,
      String profileId, int profileRev, List<Map<String, Object>> refs,
      List<Map<String, Object>> semantic) {
    List<Map<String, Object>> sorted = new ArrayList<>(refs);
    sorted.sort((a, b) -> String.valueOf(a.get("profileId")).compareTo(String.valueOf(b.get("profileId"))));
    List<Map<String, Object>> bindings = new ArrayList<>(semantic);
    bindings.sort((a, b) -> String.valueOf(a.get("bindingId")).compareTo(String.valueOf(b.get("bindingId"))));
    return Digest.sha256(Json.canonical(List.of("config-release", appCode, appVersion, candidate,
        experienceRev, profileId, profileRev, sorted, bindings)));
  }
}
