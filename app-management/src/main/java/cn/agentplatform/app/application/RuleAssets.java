package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.ConfigWriteLog;
import cn.agentplatform.app.infra.RuleAssetRepository;
import cn.agentplatform.app.infra.RuleAssetRepository.Conflict;
import cn.agentplatform.app.infra.RuleAssetRepository.Head;
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
 * 规则资产（A3-3，app V123）的编辑面：列表/新建草稿/保存草稿/发布版本/查版本/启停。
 *
 * <p>语义（主方案 §6.4、1109 §5.1）：GUIDANCE 规则发布后进提示装配"规则段"，ENFORCED 规则发布后
 * 进 Guard 结构化校验；运行命中记录规则版本进审计属运行端挂接（H1-6），本服务不执行规则本体。
 * 发布预检（接口稿 §5.5）：同一 scope 的 ENFORCED 规则互斥——发布或启用强制规则时，scope 精确相等
 * （jsonb 语义比较）且已 ENABLED 的其他强制规则构成冲突，输出规则对并阻断；预检结构照既有
 * 发布预检的 checks 条目（group/result/detail），因 app.publish_report 绑定应用草稿，规则侧
 * 结果随发布响应返回而不落 publish_report。规则字段（名称/适用范围）随主档创建时定格，
 * 六条契约路径不含范围改写；范围变化以禁用后重建表达。
 */
@Service
public class RuleAssets {

  static final Set<String> CREATE_KEYS =
      Set.of("requestId", "code", "name", "nature", "scope", "content");
  static final Set<String> DRAFT_KEYS = Set.of("requestId", "content");
  static final Set<String> ACTION_KEYS = Set.of("requestId");
  private static final Set<String> NATURES = Set.of("GUIDANCE", "ENFORCED");
  private static final Set<String> SCOPE_KEYS = Set.of("app", "stage", "toolClass");

  private final RuleAssetRepository store;
  private final ConfigWriteLog writes;

  public RuleAssets(RuleAssetRepository store, ConfigWriteLog writes) {
    this.store = store;
    this.writes = writes;
  }

  // ---------- 查询 ----------

  public List<Map<String, Object>> list(Actor actor, String appCode) {
    requireApp(actor, appCode);
    return store.listByApp(actor, appCode);
  }

  public List<Map<String, Object>> versions(Actor actor, String ruleCode) {
    head(actor, ruleCode);
    return store.versions(actor, ruleCode);
  }

  // ---------- 写入 ----------

  public Map<String, Object> create(Actor actor, String appCode, Map<String, Object> body) {
    ConnectionProfiles.keys(body, CREATE_KEYS, "规则");
    requireApp(actor, appCode);
    String code = ConnectionProfiles.text(body, "code", "规则编码", 64);
    if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
      throw ConnectionProfiles.invalid("规则编码必须以字母开头，只含字母数字下划线连字符: " + code);
    }
    String name = ConnectionProfiles.text(body, "name", "规则名称", 128);
    String nature = ConnectionProfiles.oneOf(
        ConnectionProfiles.text(body, "nature", "规则性质", 16), NATURES, "规则性质");
    String scopeJson = scope(appCode, body.get("scope"));
    Map<String, Object> content = content(body.get("content"));
    String sha = Digest.sha256(Json.canonical(content));
    String requestId = ConnectionProfiles.requestId(body);
    String requestSha = Digest.sha256(Json.canonical(
        List.of("rule.create", appCode, code, name, nature, scopeJson, content)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "rule.create", code,
        requestId, requestSha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.create(actor, code, name, nature, scopeJson,
        Json.write(content), sha, () -> view(actor, code));
    writes.remember(actor.tenantId(), "rule.create", code, requestId, requestSha, out);
    return out;
  }

  public Map<String, Object> saveDraft(Actor actor, String ruleCode, Map<String, Object> body) {
    ConnectionProfiles.keys(body, DRAFT_KEYS, "规则草稿");
    head(actor, ruleCode);
    Map<String, Object> content = content(body.get("content"));
    String sha = Digest.sha256(Json.canonical(content));
    String requestId = ConnectionProfiles.requestId(body);
    String requestSha = Digest.sha256(Json.canonical(List.of("rule.draft", ruleCode, content)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "rule.draft", ruleCode,
        requestId, requestSha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Map<String, Object> out = store.saveDraft(actor, ruleCode, Json.write(content), sha);
    writes.remember(actor.tenantId(), "rule.draft", ruleCode, requestId, requestSha, out);
    return out;
  }

  public Map<String, Object> publish(Actor actor, String ruleCode, int versionNo, Map<String, Object> body) {
    ConnectionProfiles.keys(body, ACTION_KEYS, "规则发布");
    String requestId = ConnectionProfiles.requestId(body);
    String requestSha = Digest.sha256(Json.canonical(List.of("rule.publish", ruleCode, versionNo)));
    // 回放在预检之前：已成功的发布按上次响应回放，不因事后环境变化（他人先发布了冲突规则）重算预检。
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "rule.publish", ruleCode,
        requestId, requestSha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Head head = head(actor, ruleCode);
    var checks = new ArrayList<Map<String, Object>>();
    checks.add(check("版本可发布", () -> {
      var version = store.version(actor, ruleCode, versionNo)
          .orElseThrow(() -> new PlatformException(404, "RULE_VERSION_NOT_FOUND",
              "版本不存在: " + ruleCode + "@" + versionNo));
      if (!"DRAFT".equals(version.status())) {
        throw new PlatformException(409, "RULE_VERSION_CONFLICT",
            "版本 " + versionNo + " 不是草稿或已被发布: " + ruleCode);
      }
    }));
    if ("ENFORCED".equals(head.nature())) {
      var conflicts = store.enforcedConflicts(actor, ruleCode, head.scopeJson());
      checks.add(mutexCheck(head, conflicts));
      blockOnConflict(conflicts);
    } else {
      var pass = new LinkedHashMap<String, Object>();
      pass.put("group", "规则互斥");
      pass.put("result", "SKIPPED");
      pass.put("detail", "指导性规则不参与同 scope 互斥");
      checks.add(pass);
    }
    Map<String, Object> published = store.publish(actor, ruleCode, versionNo);
    Map<String, Object> out = new LinkedHashMap<>(published);
    out.put("precheck", Map.of("passed", true, "checks", checks));
    writes.remember(actor.tenantId(), "rule.publish", ruleCode, requestId, requestSha, out);
    return out;
  }

  public Map<String, Object> change(Actor actor, String ruleCode, String action, Map<String, Object> body) {
    ConnectionProfiles.keys(body, ACTION_KEYS, "规则启停");
    if (!Set.of("enable", "disable").contains(action)) {
      throw ConnectionProfiles.invalid("未知启停动作: " + action);
    }
    String requestId = ConnectionProfiles.requestId(body);
    String requestSha = Digest.sha256(Json.canonical(List.of("rule." + action, ruleCode)));
    Map<String, Object> replayed = writes.replay(actor.tenantId(), "rule." + action, ruleCode,
        requestId, requestSha);
    if (replayed != null) {
      replayed.put("replayed", true);
      return replayed;
    }
    Head head = head(actor, ruleCode);
    String to = "enable".equals(action) ? "ENABLED" : "DISABLED";
    if ("ENABLED".equals(to)) {
      // 启用与发布同门槛：没有已发布版本不得启用；强制规则启用前重做同 scope 互斥预检，
      // 否则停用期间的竞争发布会让互斥只停留在发布时刻。
      var conflicts = store.enforcedConflicts(actor, ruleCode, head.scopeJson());
      blockOnConflict(conflicts);
    }
    Map<String, Object> out = store.setStatus(actor, ruleCode, to);
    writes.remember(actor.tenantId(), "rule." + action, ruleCode, requestId, requestSha, out);
    return out;
  }

  // ---------- 内部 ----------

  private Map<String, Object> view(Actor actor, String ruleCode) {
    Head head = store.head(actor, ruleCode)
        .orElseThrow(() -> new PlatformException(404, "RULE_REF_NOT_FOUND", "规则不存在: " + ruleCode));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", head.code());
    out.put("name", head.name());
    out.put("nature", head.nature());
    out.put("scope", Json.read(head.scopeJson(), Map.class));
    out.put("status", head.status());
    out.put("currentVersionNo", head.currentVersionNo());
    out.put("versions", store.versions(actor, ruleCode));
    return out;
  }

  private Head head(Actor actor, String ruleCode) {
    return store.head(actor, ruleCode)
        .orElseThrow(() -> new PlatformException(404, "RULE_REF_NOT_FOUND", "规则不存在: " + ruleCode));
  }

  private void requireApp(Actor actor, String appCode) {
    if (!store.appExists(actor, appCode)) {
      throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "应用不存在: " + appCode);
    }
  }

  /** scope 只含 app/stage/toolClass 三个可选键且值为非空字符串；app 键以路径为准。 */
  private String scope(String appCode, Object raw) {
    if (!(raw instanceof Map<?, ?> scope)) {
      throw ConnectionProfiles.invalid("缺少或无效字段: scope（必须是对象）");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : scope.entrySet()) {
      String key = String.valueOf(e.getKey());
      if (!SCOPE_KEYS.contains(key)) {
        throw ConnectionProfiles.invalid("scope 包含未知键: " + key + "（只允许 app/stage/toolClass）");
      }
      if (!(e.getValue() instanceof String value) || value.isBlank() || value.length() > 128) {
        throw ConnectionProfiles.invalid("scope." + key + " 必须是不超过 128 字的非空字符串");
      }
      out.put(key, value);
    }
    Object declared = out.get("app");
    if (declared != null && !declared.equals(appCode)) {
      throw ConnectionProfiles.invalid("scope.app 与路径不一致: " + declared + " ≠ " + appCode);
    }
    out.put("app", appCode);
    return Json.write(out);
  }

  /** content 形状照 1109 §5.1：恰好 {trigger, action, requirement} 三个非空字符串。 */
  private Map<String, Object> content(Object raw) {
    if (!(raw instanceof Map<?, ?> content)) {
      throw ConnectionProfiles.invalid("缺少或无效字段: content（必须是对象）");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : new String[] {"trigger", "action", "requirement"}) {
      Object value = content.get(key);
      if (!(value instanceof String text) || text.isBlank() || text.length() > 2048) {
        throw ConnectionProfiles.invalid(
            "content." + key + " 必须是不超过 2048 字的非空字符串（1109 §5.1 触发条件/处理动作/处理要求）");
      }
      out.put(key, text);
    }
    for (Object key : content.keySet()) {
      if (!Set.of("trigger", "action", "requirement").contains(String.valueOf(key))) {
        throw ConnectionProfiles.invalid("content 包含未知键: " + key);
      }
    }
    return out;
  }

  /** 互斥检查条目：照既有发布预检的 checks 形状（group/result/detail + conflicts 规则对）。 */
  private Map<String, Object> mutexCheck(Head head, List<Conflict> conflicts) {
    var entry = new LinkedHashMap<String, Object>();
    entry.put("group", "规则互斥");
    entry.put("result", conflicts.isEmpty() ? "PASS" : "BLOCKED");
    entry.put("scope", Json.read(head.scopeJson(), Map.class));
    if (conflicts.isEmpty()) {
      entry.put("detail", "同 scope 下没有其他已启用的强制性规则");
    } else {
      entry.put("detail", "同一 scope 的强制性规则互斥，存在 " + conflicts.size() + " 条冲突");
      entry.put("conflicts", conflicts.stream().map(c -> {
        var pair = new LinkedHashMap<String, Object>();
        pair.put("ruleCode", c.ruleCode());
        pair.put("conflictsWith", c.conflictsWith());
        pair.put("conflictsWithName", c.conflictsWithName());
        pair.put("scope", Json.read(c.scopeJson(), Map.class));
        pair.put("detail", c.detail());
        return pair;
      }).toList());
    }
    return entry;
  }

  /** 冲突阻断：409 + 消息指明规则对（与既有 EVAL_GATE_BLOCKED 同层的发布前硬阻断）。 */
  private void blockOnConflict(List<Conflict> conflicts) {
    if (conflicts.isEmpty()) {
      return;
    }
    String pairs = conflicts.stream()
        .map(c -> c.ruleCode() + " ⇄ " + c.conflictsWith())
        .reduce((a, b) -> a + "; " + b)
        .orElse("");
    throw new PlatformException(409, "RULE_MUTEX_BLOCKED",
        "发布预检发现同 scope 的强制性规则互斥冲突，已阻断: " + pairs);
  }

  private static Map<String, Object> check(String group, Runnable runnable) {
    var entry = new LinkedHashMap<String, Object>();
    entry.put("group", group);
    try {
      runnable.run();
      entry.put("result", "PASS");
    } catch (PlatformException e) {
      entry.put("result", "BLOCKED");
      entry.put("detail", e.getMessage());
      throw e;
    }
    return entry;
  }
}
