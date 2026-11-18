package cn.agentplatform.engine.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.engine.infra.RuntimeClients;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * R-18（A3-2 engine 侧 GUIDE 目录注册，台账 §7.6b）：engine 启动（或按需）经
 * RuntimeClients 从 skill 服务拉取 GUIDE 目录并本地缓存——V321 技能双轨的运行端注册面。
 *
 * <p>数据源：{@code GET /admin/skills?kind=GUIDE}（skill 服务既有 kind= 过滤目录，公共视图
 * 每条含 code/name/kind/status/currentVersionId/versions 与当前版本契约 definition——GUIDE
 * 契约=五字段白名单 name/description/whenToUse/modelInvocable/content，解释器在
 * skill-registry SkillCatalog.guideContract，engine 只读投影不重复校验）。拉取通道照既有
 * 跨服务预检口径（RuntimeClients 同一 ServiceTicket 面）。
 *
 * <p><b>不可达=N/A 留痕、不阻塞启动</b>：skill 服务不可达/拒绝/超时一律吞为 N/A 状态
 * （detail 留异常码），保留最近一次成功缓存（最后已知目录），启动事件监听不抛出。缓存
 * 消费路径：①{@link ResourceManifestService} guideRefs 存在性核验（缓存可用=OK/MISSING
 * 实核，N/A=留痕不阻塞装载判定）；②提示装配 SKILL_CATALOG 段正文来源（五字段摘要投影）。
 *
 * <p>engine 侧可查（等价口径，如实登记）：{@code GET /v1/skills?kind=GUIDE} 由本服务承载
 * （engine 直连只读缓存）；网关未登记 /v1/skills 前缀——经网关的技能目录面仍是 skill 服务
 * {@code /admin/skills?kind=GUIDE}（已可达、kind 过滤现成），engine 侧注册=本地缓存+上述
 * 两条消费路径。IAM（trusted）模式下 /admin/** 需门户域票据，服务票拉取会被拒——同样落
 * N/A 留痕，不影响运行（受实登记，目录缓存为尽力而为的注册面）。
 */
@Service
public class GuideDirectory {

    /** 拉取状态：OK=缓存就绪；N/A=skill 服务不可达/未拉取（留痕，缓存为最后已知目录）。 */
    public static final String STATUS_OK = "OK";
    public static final String STATUS_NA = "N/A";

    private final RuntimeClients clients;

    /** code → GUIDE 条目（公共视图只读投影）。volatile：读多写少，refresh 单写者。 */
    private volatile Map<String, JsonNode> cache = Map.of();
    private volatile String status = STATUS_NA;
    private volatile String detail = "尚未拉取（engine 启动或按需 refresh）";
    private volatile Instant fetchedAt;

    @Autowired
    public GuideDirectory(RuntimeClients clients) {
        this.clients = clients;
    }

    /** 启动挂载：ApplicationReadyEvent 时拉取一次；失败 N/A 留痕，绝不阻塞启动。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            refresh(directoryActor());
        } catch (RuntimeException neverBlocksStartup) {
            // refresh 自身已吞出站异常为 N/A；此处兜底保证启动事件链零异常外漏。
        }
    }

    /**
     * 拉取 GUIDE 目录并重建缓存。成功=OK（fetchedAt 刷新）；任何出站失败=N/A 留痕且保留
     * 旧缓存（不放大空目录、不清零最后已知注册面）。返回本次拉取后的目录视图。
     */
    public synchronized Map<String, Object> refresh(Actor actor) {
        try {
            JsonNode response = clients.get("skill", "/admin/skills?kind=GUIDE", actor);
            JsonNode list = response.path("data");
            if (!list.isArray()) throw new PlatformException(502, "GUIDE_DIRECTORY_SHAPE", "GUIDE 目录响应缺少 data 数组");
            Map<String, JsonNode> next = new LinkedHashMap<>();
            for (JsonNode item : list) {
                String code = item.path("code").asText("");
                if (!code.isBlank()) next.put(code, item);
            }
            cache = Collections.unmodifiableMap(new LinkedHashMap<>(next)); // 保序只读（条目序=服务目录序）
            status = STATUS_OK;
            detail = "GUIDE 目录缓存就绪（" + next.size() + " 条，来源 skill 服务 kind=GUIDE）";
            fetchedAt = Instant.now();
        } catch (RuntimeException unavailable) {
            // skill 服务不可达：N/A 留痕（异常码+类名入 detail），保留旧缓存，不阻塞。
            status = STATUS_NA;
            detail =
                    "GUIDE 目录拉取不可达（N/A 留痕）："
                            + unavailable.getClass().getSimpleName()
                            + (unavailable instanceof PlatformException pe
                                    ? ":" + pe.code()
                                    : "")
                            + "；缓存=最后已知目录（"
                            + cache.size()
                            + " 条）";
        }
        return view();
    }

    /** 目录视图：kind=GUIDE 过滤后的条目列表 + 拉取状态/时间/留痕详情。 */
    public Map<String, Object> view() {
        var data = new LinkedHashMap<String, Object>();
        data.put("kind", "GUIDE");
        data.put("skills", List.copyOf(cache.values()));
        data.put("total", cache.size());
        data.put("status", status);
        data.put("detail", detail);
        if (fetchedAt != null) data.put("fetchedAt", fetchedAt.toString());
        return data;
    }

    /** 缓存是否可用（消费路径据此决定实核或 N/A 留痕）。 */
    public boolean available() {
        return STATUS_OK.equals(status);
    }

    /** 按编码取缓存条目；guideRef 形如 code 或 code@version（@版本仅提示，目录按 code 定位）。 */
    public JsonNode entry(String ref) {
        String code = ref == null ? "" : ref.contains("@") ? ref.substring(0, ref.indexOf('@')) : ref;
        return cache.get(code);
    }

    /** 缓存条目数（启动清单/证据口径用）。 */
    public int size() {
        return cache.size();
    }

    /** 当前状态与留痕详情（启动清单 N/A 口径展示）。 */
    public Map<String, Object> health() {
        return Map.of("status", status, "detail", detail);
    }

    /**
     * 提示装配消费（SKILL_CATALOG 段正文来源）：GUIDE 目录五字段摘要投影——每条
     * {@code code name —— description（whenToUse: …; modelInvocable: …）}。缓存不可用返回
     * 空串（段级跳过，不伪造目录）。
     */
    public String catalogSection() {
        if (!available() || cache.isEmpty()) return "";
        var lines = new ArrayList<String>();
        for (JsonNode item : cache.values()) {
            var contract = item.path("definition");
            String line =
                    item.path("code").asText() + " " + item.path("name").asText("")
                            + " —— " + contract.path("description").asText("");
            var extras = new ArrayList<String>();
            if (!contract.path("whenToUse").asText("").isBlank())
                extras.add("whenToUse: " + contract.path("whenToUse").asText());
            if (contract.has("modelInvocable"))
                extras.add("modelInvocable: " + contract.path("modelInvocable").asBoolean());
            if (!extras.isEmpty()) line += "（" + String.join("; ", extras) + "）";
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    /** 拉取主体：运行端注册面用固定服务身份（ServiceTicket 面；不冒充门户用户）。 */
    static Actor directoryActor() {
        return new Actor("engine", "guide-directory", Set.of("SERVICE"), Map.of());
    }
}
