package cn.agentplatform.app.infra;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 发布绑定的持久化协作对象。
 *
 * <p>只承载 {@code app.publish_pin} 的写入与读取，不参与发布编排、不持有事务、不发起远端调用。
 * 由 {@code AppService.commit} 在本地发布事务之外调用：pin 是"远端确认结果"的观测记录，
 * 写失败不得回滚已提交的版本，因此这里的异常处理仍由调用方决定。
 *
 * <p>有意保留的限制：一行一应用（唯一键 tenant_id + app_code），不保留 pin 历史；
 * 历史值仍由 last_pinned_* 三列承载，与 V106 迁移的既有语义一致。
 */
public class AppPublicationStore {

    private static final String PIN_UPSERT =
            "insert into app.publish_pin(tenant_id,app_code,app_version_no,state,release_id,"
                    + "binding_id,reason,environment,last_pinned_release_id,last_pinned_binding_id,last_pinned_at)"
                    + " values(?,?,?,?,?,?,?,?,?,?,?)"
                    + " on conflict(tenant_id,app_code) do update set"
                    + " app_version_no=excluded.app_version_no,state=excluded.state,"
                    + " release_id=excluded.release_id,binding_id=excluded.binding_id,"
                    + " reason=excluded.reason,environment=excluded.environment,updated_at=now(),"
                    + " last_pinned_release_id=coalesce(case when excluded.state='PINNED' then"
                    + " excluded.release_id end, app.publish_pin.last_pinned_release_id),"
                    + " last_pinned_binding_id=coalesce(case when excluded.state='PINNED' then"
                    + " excluded.binding_id end, app.publish_pin.last_pinned_binding_id),"
                    + " last_pinned_at=coalesce(case when excluded.state='PINNED' then now() end,"
                    + " app.publish_pin.last_pinned_at)";

    private final JdbcTemplate db;

    public AppPublicationStore(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 落一条 pin 观测。{@code state=null} 视为空串（沿用旧实现 {@code Objects.toString} 的语义）。
     * 调用方负责"是否启用记录"的判定与失败兜底。
     */
    public void recordPin(
            String tenantId,
            String code,
            int versionNo,
            String state,
            String releaseId,
            String bindingId,
            String reason,
            String environment) {
        String normalized = state == null ? "" : state;
        boolean pinned = "PINNED".equals(normalized);
        db.update(
                PIN_UPSERT,
                tenantId,
                code,
                versionNo,
                normalized,
                releaseId,
                bindingId,
                reason,
                environment,
                pinned ? releaseId : null,
                pinned ? bindingId : null,
                pinned ? new Timestamp(System.currentTimeMillis()) : null);
    }

    /** 原样返回 pin 行（0 或 1 行）；V106 之前的旧发布没有行，调用方据此返回空 Map。 */
    public List<Map<String, Object>> pinRows(String tenantId, String code) {
        return db.queryForList(
                "select * from app.publish_pin where tenant_id=? and app_code=?", tenantId, code);
    }
}
