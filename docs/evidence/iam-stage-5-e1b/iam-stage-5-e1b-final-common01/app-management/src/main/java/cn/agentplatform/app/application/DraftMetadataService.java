package cn.agentplatform.app.application;

import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** §30.1.7: draft ownership is resolved inside the app domain, never by cross-schema reads. */
@Service
public class DraftMetadataService {
    private final JdbcTemplate db;

    public DraftMetadataService(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> get(long id) {
        var actor = Actor.current();
        if (!actor.roles().containsAll(Set.of("SERVICE", "EDITOR")))
            throw new PlatformException(403, "DRAFT_EDITOR_REQUIRED", "内部草稿读取需要编排者身份");
        var rows =
                db.query(
                        "select"
                            + " d.id,d.app_code,d.draft_rev,d.config::text,d.definition::text,a.type,a.status"
                            + " from app.app_draft d join app.application a on"
                            + " a.tenant_id=d.tenant_id and a.code=d.app_code where d.tenant_id=?"
                            + " and d.id=?",
                        (r, n) -> {
                            var value = new LinkedHashMap<String, Object>();
                            value.put("draftId", r.getLong("id"));
                            value.put("appCode", r.getString("app_code"));
                            value.put("draftRev", r.getLong("draft_rev"));
                            value.put("type", r.getString("type"));
                            value.put("status", r.getString("status"));
                            value.put("config", Json.read(r.getString("config")));
                            value.put("definition", Json.read(r.getString("definition")));
                            return value;
                        },
                        actor.tenantId(),
                        id);
        if (rows.isEmpty()) throw new PlatformException(404, "DRAFT_NOT_FOUND", "草稿不存在");
        var result = rows.getFirst();
        if (Set.of("ARCHIVED", "DISABLED").contains(result.get("status")))
            throw new PlatformException(409, "DRAFT_UNAVAILABLE", "应用已停用或归档");
        return result;
    }
}
