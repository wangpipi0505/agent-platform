package cn.agentplatform.app.infra;

import cn.agentplatform.app.domain.OnboardingTemplate;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 业务接入模板的持久化：父表记状态与当前修订号，修订表只追加。
 *
 * <p>与连接档案一致的是“父表 + 只追加修订”的形状，区别在于模板引用的是能力包与既有契约对象，
 * 因此修订内容全部是引用，不落任何连接凭据或脚本。
 */
@Repository
public class OnboardingTemplateRepository {

    public record State(int currentRev, String status, boolean revisionExists) {}

    private static final String HEAD_COLUMNS =
            "code, current_rev, status, created_by, created_at, updated_at";

    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public OnboardingTemplateRepository(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(5);
    }

    public Map<String, Object> create(
            Actor actor,
            String code,
            OnboardingTemplate template,
            String requestId,
            Supplier<Map<String, Object>> response) {
        return transaction(() -> {
            db.update(
                    """
                    insert into app.onboarding_template(tenant_id, code, created_by)
                    values (?, ?, ?)
                    """,
                    actor.tenantId(), code, actor.principalId());
            insertRevision(actor, template, 1);
            db.update(
                    """
                    update app.onboarding_template set current_rev = 1, updated_at = now()
                     where tenant_id = ? and code = ?
                    """,
                    actor.tenantId(), code);
            return finish(response, requestId);
        });
    }

    public Map<String, Object> revise(
            Actor actor,
            String code,
            int expectedRev,
            OnboardingTemplate template,
            String requestId,
            Supplier<Map<String, Object>> response) {
        return transaction(() -> {
            Map<String, Object> head = headForUpdate(actor, code);
            if (head == null) {
                throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入模板不存在: " + code);
            }
            int current = ((Number) head.get("current_rev")).intValue();
            if (current != expectedRev) {
                throw new PlatformException(
                        409,
                        "CONFIG_REV_CONFLICT",
                        "接入模板修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
            }
            if ("DISABLED".equals(String.valueOf(head.get("status")))) {
                throw new PlatformException(
                        400, "CONFIG_STATE_TRANSITION_INVALID", "已停用的接入模板不允许新增修订");
            }
            int rev = current + 1;
            insertRevision(actor, template, rev);
            int updated = db.update(
                    """
                    update app.onboarding_template set current_rev = ?, status = 'DRAFT', updated_at = now()
                     where tenant_id = ? and code = ? and current_rev = ?
                    """,
                    rev, actor.tenantId(), code, current);
            if (updated != 1) {
                throw new PlatformException(409, "CONFIG_REV_CONFLICT", "接入模板修订已被并发修改，请重试");
            }
            return finish(response, requestId);
        });
    }

    public Map<String, Object> transition(
            Actor actor,
            String code,
            int expectedRev,
            String from,
            String to,
            String requestId,
            Supplier<Map<String, Object>> response) {
        return transaction(() -> {
            Map<String, Object> head = headForUpdate(actor, code);
            if (head == null) {
                throw new PlatformException(404, "CONFIG_REF_NOT_FOUND", "接入模板不存在: " + code);
            }
            int current = ((Number) head.get("current_rev")).intValue();
            if (current != expectedRev) {
                throw new PlatformException(
                        409,
                        "CONFIG_REV_CONFLICT",
                        "接入模板修订已变化，请重新读取: 期望 " + expectedRev + "，当前 " + current);
            }
            int updated = db.update(
                    """
                    update app.onboarding_template set status = ?, updated_at = now()
                     where tenant_id = ? and code = ? and current_rev = ? and status = ?
                    """,
                    to, actor.tenantId(), code, expectedRev, from);
            if (updated != 1) {
                throw new PlatformException(
                        400,
                        "CONFIG_STATE_TRANSITION_INVALID",
                        "状态 " + head.get("status") + " 不能迁移到 " + to);
            }
            return finish(response, requestId);
        });
    }

    public Optional<Map<String, Object>> view(Actor actor, String code) {
        Map<String, Object> head = head(actor, code);
        if (head == null) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>(headView(head));
        var latest = revision(actor, code, ((Number) head.get("current_rev")).intValue());
        if (latest != null) {
            out.put("latest", latest);
        }
        var versions = db.queryForList(
                """
                select template_rev, kind, name, declaration_sha256, created_by, created_at
                  from app.onboarding_template_version
                 where tenant_id = ? and code = ? order by template_rev
                """,
                actor.tenantId(), code);
        out.put(
                "versions",
                versions.stream()
                        .map(
                                row -> {
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("templateRev", row.get("template_rev"));
                                    item.put("kind", row.get("kind"));
                                    item.put("name", row.get("name"));
                                    item.put("declarationSha256", row.get("declaration_sha256"));
                                    item.put("createdBy", row.get("created_by"));
                                    item.put("createdAt", String.valueOf(row.get("created_at")));
                                    return item;
                                })
                        .toList());
        return Optional.of(out);
    }

    public List<Map<String, Object>> list(Actor actor, String q, String kind) {
        var sql = new StringBuilder(
                """
                select code, current_rev, status, created_by, created_at, updated_at,
                       (select v.kind from app.onboarding_template_version v
                         where v.tenant_id = t.tenant_id and v.code = t.code
                           and v.template_rev = t.current_rev) as kind,
                       (select v.name from app.onboarding_template_version v
                         where v.tenant_id = t.tenant_id and v.code = t.code
                           and v.template_rev = t.current_rev) as name,
                       (select v.app_code from app.onboarding_template_version v
                         where v.tenant_id = t.tenant_id and v.code = t.code
                           and v.template_rev = t.current_rev) as app_code,
                       (select v.declaration_sha256 from app.onboarding_template_version v
                         where v.tenant_id = t.tenant_id and v.code = t.code
                           and v.template_rev = t.current_rev) as declaration_sha256
                  from app.onboarding_template t
                 where t.tenant_id = ?
                """);
        var args = new java.util.ArrayList<Object>();
        args.add(actor.tenantId());
        String query = q == null ? "" : q.trim();
        if (!query.isEmpty()) {
            sql.append(
                    " and (position(lower(?) in lower(t.code)) > 0"
                            + " or exists (select 1 from app.onboarding_template_version v"
                            + " where v.tenant_id = t.tenant_id and v.code = t.code"
                            + " and v.template_rev = t.current_rev and position(lower(?) in lower(v.name)) > 0))");
            args.add(query);
            args.add(query);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(
                    " and exists (select 1 from app.onboarding_template_version v"
                            + " where v.tenant_id = t.tenant_id and v.code = t.code"
                            + " and v.template_rev = t.current_rev and v.kind = ?)");
            args.add(kind);
        }
        sql.append(" order by t.code");
        return db.query(
                sql.toString(),
                (r, n) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", r.getString("code"));
                    item.put("templateVersion", r.getObject("current_rev"));
                    item.put("status", r.getString("status"));
                    item.put("kind", r.getString("kind"));
                    item.put("name", r.getString("name"));
                    item.put("appCode", r.getString("app_code"));
                    item.put("declarationSha256", r.getString("declaration_sha256"));
                    item.put("createdBy", r.getString("created_by"));
                    item.put("createdAt", String.valueOf(r.getObject("created_at")));
                    item.put("updatedAt", String.valueOf(r.getObject("updated_at")));
                    return item;
                },
                args.toArray());
    }

    public State state(Actor actor, String code, int rev) {
        var rows = db.queryForList(
                """
                select t.current_rev, t.status,
                       exists(select 1 from app.onboarding_template_version v
                               where v.tenant_id = t.tenant_id and v.code = t.code and v.template_rev = ?) as revision_exists
                  from app.onboarding_template t where t.tenant_id = ? and t.code = ?
                """,
                rev, actor.tenantId(), code);
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.get(0);
        return new State(
                ((Number) row.get("current_rev")).intValue(),
                String.valueOf(row.get("status")),
                Boolean.TRUE.equals(row.get("revision_exists")));
    }

    public Map<String, Object> revision(Actor actor, String code, int rev) {
        var rows = db.queryForList(
                """
                select template_rev, kind, name, description, source_package_version_id,
                       source_package_sha256, app_code, experience_ref::text as experience_ref,
                       integration_ref::text as integration_ref, declaration_sha256,
                       created_by, created_at
                  from app.onboarding_template_version
                 where tenant_id = ? and code = ? and template_rev = ?
                """,
                actor.tenantId(), code, rev);
        return rows.isEmpty() ? null : revisionView(rows.get(0));
    }

    /** 发布前复核：模板引用的接入档案三元组必须存在且已批准（照 ConfigRelease 的引用复核口径）。 */
    public boolean approvedIntegrationRevisionExists(
            Actor actor, String appCode, String bizSystemCode, String environment, String mode) {
        return !db.queryForList(
                        """
                        select 1
                          from app.integration_profile p
                          join app.integration_profile_revision r
                            on r.tenant_id = p.tenant_id and r.profile_id = p.profile_id
                         where p.tenant_id = ? and p.app_code = ? and p.status = 'APPROVED'
                           and r.biz_system_code = ? and r.environment = ? and r.mode = ?
                         limit 1
                        """,
                        actor.tenantId(), appCode, bizSystemCode, environment, mode)
                .isEmpty();
    }

    private void insertRevision(Actor actor, OnboardingTemplate template, int rev) {
        db.update(
                """
                insert into app.onboarding_template_version(tenant_id, code, template_rev, kind, name,
                  description, source_package_version_id, source_package_sha256, app_code,
                  experience_ref, integration_ref, declaration_sha256, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """,
                actor.tenantId(),
                template.code(),
                rev,
                template.kind(),
                template.name(),
                template.description(),
                template.sourcePackageVersionId(),
                template.sourcePackageSha256(),
                template.appCode(),
                Json.write(template.experienceRef()),
                Json.write(template.integrationRef()),
                template.declarationSha256(),
                actor.principalId());
    }

    private Map<String, Object> finish(Supplier<Map<String, Object>> response, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>(response.get());
        body.put("requestId", requestId);
        body.put("replayed", false);
        return body;
    }

    private Map<String, Object> head(Actor actor, String code) {
        var rows = db.queryForList(
                "select " + HEAD_COLUMNS + " from app.onboarding_template where tenant_id = ? and code = ?",
                actor.tenantId(), code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> headForUpdate(Actor actor, String code) {
        var rows = db.queryForList(
                "select " + HEAD_COLUMNS
                        + " from app.onboarding_template where tenant_id = ? and code = ? for update",
                actor.tenantId(), code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> headView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", row.get("code"));
        out.put("templateVersion", row.get("current_rev"));
        out.put("status", row.get("status"));
        out.put("createdBy", row.get("created_by"));
        out.put("createdAt", String.valueOf(row.get("created_at")));
        out.put("updatedAt", String.valueOf(row.get("updated_at")));
        return out;
    }

    private static Map<String, Object> revisionView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateVersion", row.get("template_rev"));
        out.put("kind", row.get("kind"));
        out.put("name", row.get("name"));
        out.put("description", row.get("description"));
        out.put("sourcePackageVersionId", row.get("source_package_version_id"));
        out.put("sourcePackageSha256", row.get("source_package_sha256"));
        out.put("appCode", row.get("app_code"));
        out.put("experience", Json.read(String.valueOf(row.get("experience_ref")), Map.class));
        out.put("integration", Json.read(String.valueOf(row.get("integration_ref")), Map.class));
        out.put("declarationSha256", row.get("declaration_sha256"));
        out.put("createdBy", row.get("created_by"));
        out.put("createdAt", String.valueOf(row.get("created_at")));
        return out;
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return tx.execute(s -> work.get());
        } catch (CannotAcquireLockException | QueryTimeoutException e) {
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "配置写入正被并发修改，请以同一 requestId 重试");
        } catch (DuplicateKeyException e) {
            throw new PlatformException(409, "CONFIG_REV_CONFLICT", "接入模板已存在: 请换一个 code");
        }
    }
}
