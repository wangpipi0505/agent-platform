package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.TraceStore;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * A3-8（台账 §7.4）：轻量装载——两级资源清单与装载状态（主方案 §6.8，1109 §5.3）。
 *
 * <p>**GET /v1/deploy/resource-manifest?releaseDigest=&environment=**：按发布版本返回任务级
 * 资源引用（模型/技能/插件/连接/规则/模板）与**装载状态**（可用/连接失败/已停用/缺授权
 * +具体对象与阶段）。查询权限 BUILDER/OPERATOR/ADMIN（接口稿 §5.7）。
 *
 * <p>两级装载时点：**启动清单**（运行端启动：服务连通/凭据引用可解析/已启用插件与 MCP 目录/
 * 平台级配额——失败列具体依赖及原因，不进入受理）；**受理清单**（任务/会话受理：钉住发布
 * 引用的模型绑定与能力、技能精确版本、插件授权、规则版本、模板版本、上下文策略——逐项定位
 * 失败对象（模型/插件/技能/连接）与阶段（缺失/停用/缺授权/连接失败），任务置
 * RESOURCE_NOT_LOADABLE 不进入执行）。
 *
 * <p>发布快照取得：运行端启动后经 authority 出票、按 app_code+configReleaseId 从
 * app-management 只读接口取得钉住的发布快照（manifest+digest），本地不落盘、每次受理校验
 * digest；app-management 不可达时受理失败并返回具体依赖错误（A12 故障注入第四项）。
 * 本首版：快照经 RuntimeClients.get("app", "/internal/apps/{code}/published")（既有受理链
 * 同款）；跨服务核验（模型/技能/插件/MCP 存在性）为 N/A 留痕——状态=引用形状核验，
 * 存在性核验照 A3-1 预检口径（本地能核的核、跨服务标 N/A）。
 */
@Service
public class ResourceManifestService {

  private final TraceStore store;
  /** R-18：engine 侧 GUIDE 目录缓存（可选装配；手工构造/测试未注入时保持 N/A 留痕口径）。 */
  private GuideDirectory guides;

  public ResourceManifestService(TraceStore store) {
    this.store = store;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void guideDirectory(GuideDirectory directory) {
    this.guides = directory;
  }

  /** 单项资源装载状态：ref/name/kind/status(OK|MISSING|DISABLED|NO_AUTH|CONNECTION_FAILED|N/A)/detail。 */
  public record ResourceEntry(String kind, String ref, String status, String stage, String detail) {}

  /**
   * 资源清单：按 appCode 取当前发布快照 manifest，逐分区产出资源条目与装载状态。
   * releaseDigest 非空时与快照 digest 校验（不符 409 RELEASE_DIGEST_MISMATCH——定位对象与阶段）。
   */
  public Map<String, Object> manifest(String appCode, String releaseDigest, String environment, Actor actor) {
    requireRole(actor);
    var snapshotRows =
            store.db()
                    .queryForList(
                            "select public_id,config_release_id,config_digest,snapshot from engine.chat_session"
                                + " where app_code=? and config_release_id is not null"
                                + " order by created_at desc limit 1",
                            appCode);
    JsonNode manifest;
    String pinnedDigest;
    if (snapshotRows.isEmpty()) {
      // 无钉住快照的演示应用：空 manifest 骨架（SKIPPED 口径——无资源可装载）
      manifest = Json.object();
      pinnedDigest = "none";
    } else {
      var row = snapshotRows.getFirst();
      pinnedDigest = String.valueOf(row.get("config_digest"));
      manifest = Json.read(String.valueOf(row.get("snapshot")), JsonNode.class).path("config");
    }
    if (releaseDigest != null && !releaseDigest.isBlank() && !releaseDigest.equals(pinnedDigest))
      throw new PlatformException(
              409,
              "RELEASE_DIGEST_MISMATCH",
              "releaseDigest 与当前钉住发布不符（期望 " + pinnedDigest + "）——对象=发布快照，阶段=清单解析");
    var entries = new ArrayList<ResourceEntry>();
    collectModel(entries, manifest);
    collectSkills(entries, manifest);
    collectPlugins(entries, manifest);
    collectRules(entries, manifest, appCode);
    collectMemory(entries, manifest);
    collectContext(entries, manifest);
    // 装载判定：任一 OK=false 的硬资源（MISSING/DISABLED/NO_AUTH）→ RESOURCE_NOT_LOADABLE 形状
    var blocking =
            entries.stream()
                    .filter(e -> Set.of("MISSING", "DISABLED", "NO_AUTH", "CONNECTION_FAILED").contains(e.status()))
                    .toList();
    var view = new LinkedHashMap<String, Object>();
    view.put("appCode", appCode);
    view.put("releaseDigest", pinnedDigest);
    view.put("environment", environment == null || environment.isBlank() ? "production" : environment);

    view.put("levels", Map.of("startup", startupChecklist(), "admission", "按引用逐项（见 resources）"));
    view.put("resources", entries);
    view.put("loadable", blocking.isEmpty());
    if (!blocking.isEmpty())
      view.put(
              "resourceNotLoadable",
              Map.of(
                      "code",
                      "RESOURCE_NOT_LOADABLE",
                      "objects",
                      blocking.stream().map(e -> e.kind() + ":" + e.ref()).toList(),
                      "stages",
                      blocking.stream().map(ResourceEntry::stage).distinct().toList()));
    return view;
  }

  /** 启动清单（主方案 §6.8 第一级）：服务连通/凭据引用/已启用插件与 MCP 目录/平台配额。 */
  private List<Object> startupChecklist() {
    List<Object> items = new ArrayList<>();
    items.add(Map.of("item", "app-management 发布快照只读接口", "status", "N/A", "stage", "启动", "detail", "跨服务核验照 A3-1 预检口径"));
    items.add(Map.of("item", "已启用插件目录", "status", "N/A", "stage", "启动", "detail", "plugin_version ENABLED 计数——A3-5 落地后接通"));
    items.add(Map.of("item", "MCP 连接目录", "status", "N/A", "stage", "启动", "detail", "mcp_connection ACTIVE 计数——A3-6 落地后接通"));
    items.add(Map.of("item", "平台级配额", "status", "OK", "stage", "启动", "detail", "model-gateway 计量配额沿用现状"));
    return items;
  }

  private void collectModel(List<ResourceEntry> entries, JsonNode manifest) {
    var binding = manifest.path("model").path("defaultBinding");
    if (binding.isObject() && !binding.isEmpty()) {
      String ref = binding.path("providerCode").asText("") + "/" + binding.path("modelName").asText("");
      entries.add(new ResourceEntry("MODEL", ref, "OK", "受理", "模型绑定随冻结快照（存在性核验经 model-gateway——N/A 留痕）"));
    }
  }

  private void collectSkills(List<ResourceEntry> entries, JsonNode manifest) {
    // R-18：GUIDE 引用存在性核验升级——engine 侧 GUIDE 目录缓存可用时实核（命中=OK、
    // 未命中=MISSING 且阻断装载）；缓存 N/A（skill 服务不可达/未注入）保持既有 N/A 留痕
    // 口径，不放大装载判定。
    for (JsonNode g : manifest.path("skills").path("guideRefs")) {
      String ref = g.asText();
      if (guides != null && guides.available()) {
        boolean known = guides.entry(ref) != null;
        entries.add(
                new ResourceEntry(
                        "SKILL",
                        ref,
                        known ? "OK" : "MISSING",
                        "受理",
                        known
                                ? "GUIDE 引用（engine 侧目录注册缓存命中）"
                                : "GUIDE 引用在 engine 侧目录注册中不存在（MISSING）"));
      } else
        entries.add(
                new ResourceEntry(
                        "SKILL", ref, "OK", "受理", "GUIDE 引用（存在性核验经 skill-registry——N/A 留痕）"));
    }
    for (JsonNode t : manifest.path("skills").path("toolGrants"))
      entries.add(
              new ResourceEntry(
                      "SKILL",
                      t.path("skillCode").asText() + "@" + t.path("version").asInt(),
                      t.path("skillCode").asText("").isBlank() ? "MISSING" : "OK",
                      "受理",
                      "TOOL 授权（skill_version 存在性核验经 skill-registry——N/A 留痕）"));
  }

  private void collectPlugins(List<ResourceEntry> entries, JsonNode manifest) {
    for (JsonNode p : manifest.path("plugins"))
      entries.add(
              new ResourceEntry(
                      "PLUGIN",
                      p.path("pluginCode").asText() + "@" + p.path("version").asText(),
                      "N/A",
                      "受理",
                      "插件启用/授权核验经 skill-registry（A3-5 落地后接通——N/A 留痕）"));
  }

  private void collectRules(List<ResourceEntry> entries, JsonNode manifest, String appCode) {
    // 规则引用本地核验（A3-3 已交付 rule_asset/rule_asset_version——engine 无 app schema 直查，
    // 经 app-management 预检口径承接；此处按引用形状核验）
    for (JsonNode r : manifest.path("rules")) {
      String ref = r.asText();
      boolean shaped = ref.matches("^[A-Za-z0-9_-]+@\\d+$");
      entries.add(
              new ResourceEntry(
                      "RULE",
                      ref,
                      shaped ? "N/A" : "MISSING",
                      "受理",
                      shaped ? "规则版本核验经 app-management 预检（N/A 留痕）" : "引用形状非法（code@version）"));
    }
  }

  private void collectMemory(List<ResourceEntry> entries, JsonNode manifest) {
    var mp = manifest.path("memoryPolicy");
    if (mp.isObject() && !mp.isEmpty())
      entries.add(new ResourceEntry("MEMORY_POLICY", "memoryPolicy", "OK", "受理", "readScopes/writeScopes/topK 随快照装载"));
  }

  private void collectContext(List<ResourceEntry> entries, JsonNode manifest) {
    var cp = manifest.path("contextPolicy");
    if (cp.isObject() && !cp.isEmpty())
      entries.add(new ResourceEntry("CONTEXT_POLICY", "contextPolicy", "OK", "受理", "maxTurns/contextBudget/compactionThreshold/historyWindow 随快照装载"));
  }

  private void requireRole(Actor actor) {
    if (!actor.roles().contains("BUILDER")
            && !actor.roles().contains("OPERATOR")
            && !actor.roles().contains("ADMIN"))
      throw new PlatformException(403, "RESOURCE_MANIFEST_FORBIDDEN", "资源清单查询权限 BUILDER/OPERATOR/ADMIN");
  }
}
