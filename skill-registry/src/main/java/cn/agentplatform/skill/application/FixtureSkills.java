package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.skill.infra.SkillClients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AP-S06 技能侧 fixture 隔离。演示/冒烟技能由 smoke 脚本经管理 API 写入（非代码 seed），
 * 运行时按命名约定识别：code 含 "fixture" 或以 "smoke_" 开头。
 *
 * <p>设计不变量：agent.demo-mode（默认 false）是唯一开关。生产/默认模式禁止再注入 fixture
 * 技能；清理动作只命中 fixture 行——被应用引用的跳过（default refuse，引用探测失败也跳过）、
 * 有发布历史的停用保留（历史执行事实可追溯）、纯草稿删除；动作写入 skill.fixture_audit。</p>
 */
@Service
public class FixtureSkills {
    private static final String FIXTURE_MATCH = "(code ~ 'fixture' or code ~ '^smoke_')";

    private final JdbcTemplate db;
    private final SkillCatalog catalog;
    private final SkillClients clients;
    private final String appUrl;
    private final boolean demoMode;

    public FixtureSkills(
            JdbcTemplate db,
            SkillCatalog catalog,
            SkillClients clients,
            @Value("${agent.services.app:http://127.0.0.1:18081}") String appUrl,
            @Value("${agent.demo-mode:false}") boolean demoMode) {
        this.db = db;
        this.catalog = catalog;
        this.clients = clients;
        this.appUrl = appUrl;
        this.demoMode = demoMode;
    }

    public boolean demoMode() {
        return demoMode;
    }

    /** 技能命名约定：冒烟脚本全部使用 smoke_ 前缀；含 fixture 的编码同样视为演示数据。 */
    public static boolean isFixtureCode(String code) {
        return code != null && (code.contains("fixture") || code.startsWith("smoke_"));
    }

    /** 非演示模式禁止注入新 fixture 技能；SkillController.create 是唯一创建入口。 */
    public void assertCreatable(String code) {
        if (!demoMode && isFixtureCode(code))
            throw new PlatformException(
                    409,
                    "SKILL-FIXTURE",
                    "演示 fixture 技能只能在演示模式（AGENT_DEMO_MODE=true）下注册；"
                            + "请改用正式命名或启用演示模式");
    }

    public Map<String, Object> summary(Actor actor) {
        var rows =
                db.queryForList(
                        "select code,name,status from skill.skill where tenant_id=? and "
                                + FIXTURE_MATCH + " order by code",
                        actor.tenantId());
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            int versions =
                    db.queryForObject(
                            "select count(*) from skill.skill_version where tenant_id=? and skill_code=?",
                            Integer.class,
                            actor.tenantId(),
                            row.get("code"));
            items.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "code", row.get("code"),
                                    "name", row.get("name"),
                                    "status", row.get("status"),
                                    "versionCount", versions)));
        }
        var audits =
                db.queryForList(
                        "select actor,action,detail::text as detail,created_at from skill.fixture_audit"
                                + " where tenant_id=? order by id desc limit 10",
                        actor.tenantId());
        // jsonb 以 PGobject 返回，归一成对象后随统一 JSON 序列化输出。
        audits.forEach(r -> r.put("detail", Json.read(Objects.toString(r.remove("detail"), "{}"))));
        return Map.of(
                "demoMode", demoMode,
                "fixtureCount", rows.size(),
                "items", items,
                "audits", audits);
    }

    public Map<String, Object> purge(Actor actor) {
        actor.requireRole("ADMIN");
        var codes =
                db.queryForList(
                        "select code from skill.skill where tenant_id=? and " + FIXTURE_MATCH
                                + " order by code",
                        actor.tenantId());
        int removed = 0, disabled = 0, skipped = 0;
        var outcomes = new LinkedHashMap<String, String>();
        for (var row : codes) {
            String code = row.get("code").toString();
            if (!referencesEmpty(code)) {
                // 引用探测失败也按有引用处理：宁可保守跳过，不可误删仍被发布版本使用的技能。
                skipped++;
                outcomes.put(code, "SKIPPED_REFERENCED");
                continue;
            }
            var statuses =
                    db.queryForList(
                            "select distinct status from skill.skill_version where tenant_id=? and skill_code=?",
                            actor.tenantId(),
                            code);
            String main =
                    db.queryForObject(
                            "select status from skill.skill where tenant_id=? and code=?",
                            String.class,
                            actor.tenantId(),
                            code);
            boolean onlyDraft =
                    "DRAFT".equals(main)
                            && !statuses.isEmpty()
                            && statuses.stream().allMatch(r -> "DRAFT".equals(r.get("status")));
            if (onlyDraft) {
                catalog.delete(code);
                removed++;
                outcomes.put(code, "REMOVED");
            } else {
                // 有发布历史：停用而非删除，历史执行事实与版本契约保持可追溯。
                db.update(
                        "update skill.skill set status='DISABLED',updated_at=now()"
                                + " where tenant_id=? and code=?",
                        actor.tenantId(),
                        code);
                disabled++;
                outcomes.put(code, "DISABLED");
            }
        }
        db.update(
                "insert into skill.fixture_audit(tenant_id,actor,action,detail)"
                        + " values(?,?,?::varchar,?::jsonb)",
                actor.tenantId(),
                actor.principalId(),
                "PURGE_SKILLS",
                Json.write(
                        Map.of(
                                "removed", removed,
                                "disabled", disabled,
                                "skipped", skipped,
                                "skills", outcomes)));
        return Map.of(
                "removed", removed, "disabled", disabled, "skipped", skipped, "skills", outcomes);
    }

    private boolean referencesEmpty(String code) {
        try {
            var refs = clients.get(appUrl + "/internal/skills/" + code + "/references");
            return refs != null && refs.isArray() && refs.isEmpty();
        } catch (Exception unreachable) {
            return false;
        }
    }

    /** 管理面列表视图统一补 source 标记：带 code+name 的对象视为技能行；其余结构原样返回。 */
    public static Object withSource(Object view) {
        if (view instanceof List<?> list) return list.stream().map(FixtureSkills::withSource).toList();
        if (view instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> result.put(String.valueOf(k), withSource(v)));
            if (result.get("code") instanceof String code && result.containsKey("name"))
                result.put("source", isFixtureCode(code) ? "FIXTURE" : "MANAGED");
            return result;
        }
        return view;
    }
}
