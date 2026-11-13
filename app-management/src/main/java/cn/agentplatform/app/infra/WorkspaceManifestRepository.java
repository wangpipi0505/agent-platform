package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 工作区 manifest（A3-1，app V122）的持久化：追加式不可变修订链。
 *
 * <p>语义（库稿 §4.6、主方案 §6.6）：每次保存追加 revision，digest=规范化 JSON 的内容寻址 sha256；
 * UNIQUE(tenant_id, app_code, digest) 在库层兜底"同内容不重复出版本"（并发双写时后到者撞唯一键，
 * 调用方按已存在修订回读）。行一旦写入由 V122 触发器 immutable_workspace_manifest 禁改禁删，
 * 因此"这次发布当时钉住的是哪份装配"永远可以按 (app_code, revision) 回答。
 */
@Repository
public class WorkspaceManifestRepository {

    /** 头修订行：GET /manifest 的主体与发布预检/固化引用的来源。 */
    public record Head(int revision, String manifestJson, String digest, String createdBy,
            Instant createdAt) {}

    private final JdbcTemplate db;

    public WorkspaceManifestRepository(JdbcTemplate db) {
        this.db = db;
    }

    // ---------- 查询 ----------

    /** 当前头修订（revision 最大者）；应用尚无 manifest 时为空。 */
    public Optional<Head> head(Actor actor, String appCode) {
        var rows = db.queryForList("""
            select revision, manifest::text as manifest, trim(trailing from digest) as digest,
                   created_by, created_at
              from app.workspace_manifest
             where tenant_id = ? and app_code = ?
             order by revision desc limit 1
            """, actor.tenantId(), appCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(head(rows.get(0)));
    }

    /** 按内容摘要定位既有修订（digest 去重的读取侧）；char(64) 右补空格需裁剪。 */
    public Optional<Head> byDigest(Actor actor, String appCode, String digest) {
        var rows = db.queryForList("""
            select revision, manifest::text as manifest, trim(trailing from digest) as digest,
                   created_by, created_at
              from app.workspace_manifest
             where tenant_id = ? and app_code = ? and digest = ?
            """, actor.tenantId(), appCode, digest);
        return rows.isEmpty() ? Optional.empty() : Optional.of(head(rows.get(0)));
    }

    /** GET /admin/apps/{code}/manifests：版本列表（修订升序，版本链完整可回看）。 */
    public List<Map<String, Object>> versions(Actor actor, String appCode) {
        return db.queryForList("""
            select revision, trim(trailing from digest) as digest, created_by, created_at
              from app.workspace_manifest
             where tenant_id = ? and app_code = ?
             order by revision
            """, actor.tenantId(), appCode).stream()
            .map(WorkspaceManifestRepository::versionView).toList();
    }

    public boolean appExists(Actor actor, String appCode) {
        return !db.queryForList("select 1 from app.application where tenant_id = ? and code = ?",
            actor.tenantId(), appCode).isEmpty();
    }

    // ---------- 写入 ----------

    /**
     * 追加修订。并发同一内容双写由 UNIQUE(tenant_id, app_code, digest) 兜底：撞唯一键抛
     * DuplicateKeyException，调用方（{@code WorkspaceManifests}）按既有修订回读而不是报错。
     * 并发推进 revision 由组合 PK 兜底，撞键同样交由调用方以重读后的头修订响应。
     */
    public void insert(Actor actor, String appCode, int revision, String manifestJson,
            String digest) {
        try {
            db.update("""
                insert into app.workspace_manifest(tenant_id, app_code, revision, manifest, digest, created_by)
                values (?, ?, ?, ?::jsonb, ?, ?)
                """, actor.tenantId(), appCode, revision, manifestJson, digest,
                actor.principalId());
        } catch (DuplicateKeyException e) {
            throw new cn.agentplatform.common.PlatformException(409, "MANIFEST_REVISION_CONFLICT",
                "manifest 修订或内容摘要已被并发写入，请以当前头修订为 baseRevision 重试: " + appCode);
        }
    }

    // ---------- 内部 ----------

    private static Head head(Map<String, Object> row) {
        return new Head(((Number) row.get("revision")).intValue(),
            String.valueOf(row.get("manifest")), String.valueOf(row.get("digest")),
            String.valueOf(row.get("created_by")),
            ((java.sql.Timestamp) row.get("created_at")).toInstant());
    }

    private static Map<String, Object> versionView(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", row.get("revision"));
        out.put("digest", row.get("digest"));
        out.put("createdBy", row.get("created_by"));
        out.put("createdAt", String.valueOf(row.get("created_at")));
        return out;
    }
}
