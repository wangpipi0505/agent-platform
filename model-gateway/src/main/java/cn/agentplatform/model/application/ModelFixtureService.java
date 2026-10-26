package cn.agentplatform.model.application;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * AP-S06 fixture 汇总与清理。PG 是唯一业务权威源：清理一律落在业务表上
 * （provider 软删 + entry 禁用/删除），并写入 model.fixture_audit 审计。
 *
 * <p>清理边界（有意限制）：只命中 fixture 命名约定的行；entry 若被 call_log 或
 * binding_snapshot 引用则保留并停用（历史调用与冻结快照永不物理删除，可追溯）；
 * 托管数据（如 deepseek_cloud 这类经本地代理的真实供应商）不可被清理动作触碰。</p>
 */
@Service
public class ModelFixtureService {
    private static final String FIXTURE_MATCH =
            "(code ~ 'fixture' or code ~ '^smoke_model_')";

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FixturePolicy policy;

    public ModelFixtureService(JdbcTemplate db, FixturePolicy policy) {
        this.db = db;
        this.tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(db.getDataSource()));
        this.policy = policy;
    }

    public Map<String, Object> summary(Actor actor) {
        var providers =
                db.queryForList(
                        "select id,code,name,base_url,status from model.provider where tenant_id=?"
                                + " and not deleted and " + FIXTURE_MATCH + " order by id",
                        actor.tenantId());
        int entryCount = 0;
        var items = new ArrayList<Map<String, Object>>();
        for (var row : providers) {
            long id = ((Number) row.get("id")).longValue();
            int entries =
                    db.queryForObject(
                            "select count(*) from model.entry where tenant_id=? and provider_id=?",
                            Integer.class,
                            actor.tenantId(),
                            id);
            entryCount += entries;
            items.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "id", id,
                                    "code", row.get("code"),
                                    "name", row.get("name"),
                                    "baseUrl", row.get("base_url"),
                                    "status", row.get("status"),
                                    "entryCount", entries)));
        }
        var audits =
                db.queryForList(
                        "select actor,action,detail::text as detail,created_at from model.fixture_audit"
                                + " where tenant_id=? order by id desc limit 10",
                        actor.tenantId());
        // jsonb 以 PGobject 返回，归一成对象后随统一 JSON 序列化输出。
        audits.forEach(r -> r.put("detail", Json.read(Objects.toString(r.remove("detail"), "{}"))));
        return Map.of(
                "demoMode", policy.demoMode(),
                "providerCount", providers.size(),
                "entryCount", entryCount,
                "providers", items,
                "audits", audits);
    }

    public Map<String, Object> purge(Actor actor) {
        actor.requireRole("ADMIN");
        return tx.execute(
                status -> {
                    var providers =
                            db.queryForList(
                                    "select id,code from model.provider where tenant_id=?"
                                            + " and not deleted and " + FIXTURE_MATCH + " order by id",
                                    actor.tenantId());
                    int entriesRemoved = 0, entriesKept = 0;
                    var codes = new ArrayList<String>();
                    for (var row : providers) {
                        long id = ((Number) row.get("id")).longValue();
                        codes.add(row.get("code").toString());
                        for (var entry :
                                db.queryForList(
                                        "select id from model.entry where tenant_id=? and provider_id=?",
                                        actor.tenantId(),
                                        id)) {
                            long entryId = ((Number) entry.get("id")).longValue();
                            boolean referenced =
                                    db.queryForObject(
                                            "select exists(select 1 from model.call_log where"
                                                + " model_entry_id=?) or exists(select 1 from"
                                                + " model.binding_snapshot where model_entry_id=?)",
                                            Boolean.class,
                                            entryId,
                                            entryId);
                            if (referenced) {
                                db.update(
                                        "update model.entry set status='DISABLED' where id=?",
                                        entryId);
                                entriesKept++;
                            } else {
                                db.update("delete from model.entry where id=?", entryId);
                                entriesRemoved++;
                            }
                        }
                        db.update(
                                "update model.provider set deleted=true,status='DISABLED',"
                                        + "config_rev=config_rev+1,updated_at=now()"
                                        + " where tenant_id=? and id=?",
                                actor.tenantId(),
                                id);
                    }
                    db.update(
                            "insert into model.fixture_audit(tenant_id,actor,action,detail)"
                                    + " values(?,?,?::varchar,?::jsonb)",
                            actor.tenantId(),
                            actor.principalId(),
                            "PURGE_PROVIDERS",
                            Json.write(
                                    Map.of(
                                            "purged", providers.size(),
                                            "entriesRemoved", entriesRemoved,
                                            "entriesKept", entriesKept,
                                            "providers", codes)));
                    return Map.of(
                            "purged", providers.size(),
                            "entriesRemoved", entriesRemoved,
                            "entriesKept", entriesKept,
                            "providers", codes);
                });
    }
}
