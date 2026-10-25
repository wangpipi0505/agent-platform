package cn.agentplatform.app.application;

import static cn.agentplatform.app.application.AppService.*;

import cn.agentplatform.app.domain.AppRules;
import cn.agentplatform.common.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TemplateService {
    private final JdbcTemplate db;

    public TemplateService(JdbcTemplate db) {
        this.db = db;
    }

    private String tenant() {
        return Actor.current().tenantId();
    }

    public List<Map<String, Object>> list() {
        return db.query(
                "select * from app.prompt_template where tenant_id=? order by code",
                (r, n) ->
                        Map.<String, Object>of(
                                "id",
                                r.getString("code"),
                                "code",
                                r.getString("code"),
                                "name",
                                r.getString("name"),
                                "description",
                                r.getString("description"),
                                "currentVersion",
                                r.getInt("current_version")),
                tenant());
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        String code = text(b, "code");
        if (!code.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw error(400, "INVALID_INPUT", "模板编码无效");
        db.update(
                "insert into app.prompt_template(tenant_id,code,name,description) values(?,?,?,?)",
                tenant(),
                code,
                text(b, "name"),
                Objects.toString(b.get("description"), ""));
        insert(code, 1, b);
        return version(code, 1);
    }

    @Transactional
    public Map<String, Object> revise(String code, Map<String, Object> b) {
        var rows =
                db.queryForList(
                        "select current_version from app.prompt_template where tenant_id=? and"
                            + " code=? for update",
                        tenant(),
                        code);
        if (rows.isEmpty()) throw error(404, "TEMPLATE_NOT_FOUND", "模板不存在");
        int next = ((Number) rows.getFirst().get("current_version")).intValue() + 1;
        insert(code, next, b);
        db.update(
                "update app.prompt_template set current_version=? where tenant_id=? and code=?",
                next,
                tenant(),
                code);
        return version(code, next);
    }

    private void insert(String code, int version, Map<String, Object> b) {
        String content = Objects.toString(b.get("content"), "");
        if (content.isBlank()) throw error(400, "INVALID_INPUT", "模板内容不能为空");
        var vars = objects(b.get("variables"));
        var names = new HashSet<String>();
        for (var v : vars) {
            String name = text(v, "name");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")
                    || !names.add(name)
                    || !Set.of("string", "integer", "number", "boolean", "object", "array")
                            .contains(Objects.toString(v.get("type"), "")))
                throw error(400, "INVALID_INPUT", "变量定义无效");
        }
        var matcher =
                java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}")
                        .matcher(content);
        while (matcher.find())
            if (!names.contains(matcher.group(1))) throw error(400, "INVALID_INPUT", "模板包含未声明变量");
        db.update(
                "insert into"
                    + " app.prompt_template_version(tenant_id,template_code,version_no,content,variables,output_contract,sha256)"
                    + " values(?,?,?,?,?::jsonb,?::jsonb,?)",
                tenant(),
                code,
                version,
                content,
                Json.write(vars),
                Json.write(b.getOrDefault("outputContract", Map.of())),
                Digest.sha256(
                        AppRules.canonical(
                                Map.of(
                                        "content",
                                        content,
                                        "variables",
                                        vars,
                                        "outputContract",
                                        b.getOrDefault("outputContract", Map.of())))));
    }

    public List<Map<String, Object>> versions(String code) {
        return db
                .query(
                        "select version_no from app.prompt_template_version where tenant_id=? and"
                            + " template_code=? order by version_no desc",
                        (r, n) -> r.getInt(1),
                        tenant(),
                        code)
                .stream()
                .map(v -> version(code, v))
                .toList();
    }

    public Map<String, Object> version(String code, int version) {
        var rows =
                db.query(
                        "select * from app.prompt_template_version where tenant_id=? and"
                            + " template_code=? and version_no=?",
                        (r, n) ->
                                Map.<String, Object>of(
                                        "code",
                                        code,
                                        "versionNo",
                                        version,
                                        "status",
                                        r.getString("status"),
                                        "content",
                                        r.getString("content"),
                                        "variables",
                                        Json.read(r.getString("variables"), Object.class),
                                        "outputContract",
                                        Json.read(r.getString("output_contract"), Object.class),
                                        "sha256",
                                        r.getString("sha256")),
                        tenant(),
                        code,
                        version);
        if (rows.isEmpty()) throw error(404, "TEMPLATE_VERSION_NOT_FOUND", "模板版本不存在");
        return rows.getFirst();
    }

    @Transactional
    public Map<String, Object> publish(String code, int version) {
        var v = version(code, version);
        if (v.get("status").equals("PUBLISHED")) return v;
        if (!v.get("status").equals("DRAFT")) throw error(409, "TEMPLATE_STATE", "仅草稿可发布");
        db.update(
                "update app.prompt_template_version set"
                    + " status='PUBLISHED',published_by=?,published_at=now() where tenant_id=? and"
                    + " template_code=? and version_no=? and status='DRAFT'",
                Actor.current().principalId(),
                tenant(),
                code,
                version);
        return version(code, version);
    }

    public Map<String, Object> render(String code, int version, Map<String, Object> body) {
        var v = version(code, version);
        try {
            String result =
                    AppRules.render(
                            v.get("content").toString(),
                            objects(v.get("variables")),
                            object(body.getOrDefault("variables", body)));
            return Map.of(
                    "text",
                    result,
                    "rendered",
                    result,
                    "tokenCountEstimate",
                    (result.codePointCount(0, result.length()) + 3) / 4,
                    "tokenCountExact",
                    false);
        } catch (IllegalArgumentException e) {
            throw error(400, "TEMPLATE_VARIABLE_INVALID", e.getMessage());
        }
    }

    public List<Map<String, Object>> classifications() {
        return db.queryForList(
                "select kind,code,name from app.classification where tenant_id=? order by"
                    + " kind,code",
                tenant());
    }

    public Map<String, Object> classification(Map<String, Object> body) {
        String kind = text(body, "kind");
        if (!Set.of("DOMAIN", "BIZ_CATEGORY", "TAG").contains(kind))
            throw error(400, "INVALID_INPUT", "分类类型无效");
        String code = text(body, "code"), name = text(body, "name");
        db.update(
                "insert into app.classification(tenant_id,kind,code,name) values(?,?,?,?) on"
                    + " conflict(tenant_id,kind,code) do update set name=excluded.name",
                tenant(),
                kind,
                code,
                name);
        return Map.of("kind", kind, "code", code, "name", name);
    }
}
