package cn.agentplatform.skill.application;

import cn.agentplatform.common.Actor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

import java.util.*;

/** PG租约只用于投递既有事实。没有适配器依赖，任何重试都不可能再次发送外部业务请求。 */
@Component
@EnableScheduling
public class SkillOutbox {
    private final JdbcTemplate db;
    private final SkillExecution execution;
    private final String owner = "outbox-" + UUID.randomUUID();

    public SkillOutbox(JdbcTemplate db, SkillExecution execution) {
        this.db = db;
        this.execution = execution;
    }

    @Scheduled(fixedDelayString = "${agent.outbox.delay-ms:2000}")
    public void pulse() {
        // 系统调度器例外：只发现待投递租户键，不读取跨租户业务payload；后续认领与事实读取均显式限定租户。
        var tenants =
                db.queryForList(
                        "select distinct tenant_id from skill.execution_fact where"
                            + " callback_state='PENDING' and next_delivery_at<=now() and"
                            + " (delivery_lease_until is null or delivery_lease_until<now()) limit"
                            + " 8");
        int remaining = 8;
        for (var row : tenants) {
            if (remaining <= 0) break;
            remaining -= drainTenant(row.get("tenant_id").toString(), Math.min(2, remaining));
        }
    }

    public int drainTenant(String tenant, int maximum) {
        int processed = 0;
        long deadline = System.nanoTime() + 5_000_000_000L;
        for (int i = 0; i < Math.min(maximum, 8) && System.nanoTime() < deadline; i++) {
            // SKIP LOCKED与同一UPDATE原子认领，跨副本不会同时取得同代租约；90秒覆盖客户端60秒读取上限。
            var rows =
                    db.queryForList(
                            "with candidate as (select tenant_id,call_id from skill.execution_fact"
                                + " where tenant_id=? and callback_state='PENDING' and"
                                + " next_delivery_at<=now() and (delivery_lease_until is null or"
                                + " delivery_lease_until<now()) order by next_delivery_at for"
                                + " update skip locked limit 1) update skill.execution_fact f set"
                                + " delivery_owner=?,delivery_lease_until=now()+interval '90"
                                + " seconds',delivery_attempts=delivery_attempts+1 from candidate c"
                                + " where f.tenant_id=c.tenant_id and f.call_id=c.call_id returning"
                                + " f.tenant_id,f.call_id,f.principal_id,f.attempt_id,f.delivery_attempts",
                            tenant,
                            owner);
            if (rows.isEmpty()) break;
            var fact = rows.getFirst();
            long call = ((Number) fact.get("call_id")).longValue();
            try {
                // 主体取已验JWT产生的持久事实，投递票据仅SERVICE，不沿用ADMIN/APPROVER或扩大用户授权。
                var actor =
                        new Actor(
                                tenant,
                                fact.get("principal_id").toString(),
                                Set.of("SERVICE"),
                                Map.of());
                execution.retry(call, actor);
            } catch (Exception ignored) {
                /* 租约与下一次due重试承担恢复，不重新执行适配器。 */
            }
            int delay = 1 << Math.min(6, ((Number) fact.get("delivery_attempts")).intValue());
            db.update(
                    "update skill.execution_fact set"
                        + " delivery_owner=null,delivery_lease_until=null,next_delivery_at=now()+(?"
                        + " * interval '1 second') where tenant_id=? and call_id=? and attempt_id=?"
                        + " and delivery_owner=?",
                    delay,
                    tenant,
                    call,
                    fact.get("attempt_id"),
                    owner);
            processed++;
        }
        return processed;
    }
}
