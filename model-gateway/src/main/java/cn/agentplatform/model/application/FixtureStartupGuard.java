package cn.agentplatform.model.application;

import cn.agentplatform.common.Json;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AP-S06 启动守卫：启动即盘点 fixture 演示数据并在日志中给出明确语义。
 * 生产/默认模式下检出 fixture 行是配置漂移信号——它们已被出口守卫隔离，
 * 不会参与真实调用，但应在日志与 /admin/model-providers/fixture 汇总中可辨识，
 * 绝不允许状态页“全绿但不可用”。
 */
@Component
public class FixtureStartupGuard implements ApplicationRunner {
    private static final String FIXTURE_MATCH =
            "(code ~ 'fixture' or code ~ '^smoke_model_')";

    private final JdbcTemplate db;
    private final FixturePolicy policy;

    public FixtureStartupGuard(JdbcTemplate db, FixturePolicy policy) {
        this.db = db;
        this.policy = policy;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long providers =
                db.queryForObject(
                        "select count(*) from model.provider where not deleted and " + FIXTURE_MATCH,
                        Long.class);
        Long entries =
                db.queryForObject(
                        "select count(*) from model.entry e join model.provider p on"
                            + " p.id=e.provider_id where not p.deleted and"
                            + " (p.code ~ 'fixture' or p.code ~ '^smoke_model_')",
                        Long.class);
        var summary = Map.of("demoMode", policy.demoMode(), "providers", providers, "entries", entries);
        if (!policy.demoMode() && providers > 0) {
            // WARN 级别要求值班必读：数据已隔离，但说明演示库数据混入或由 smoke 脚本残留。
            org.slf4j.LoggerFactory.getLogger(FixtureStartupGuard.class)
                    .warn(
                            "fixture 隔离守卫：检测到 {} 个演示供应商 / {} 个演示条目（demo-mode=false），"
                                    + "已禁止参与真实调用与新增注入；清理入口 POST /admin/model-providers/fixture/purge",
                            providers,
                            entries);
        } else {
            org.slf4j.LoggerFactory.getLogger(FixtureStartupGuard.class)
                    .info(
                            "fixture 隔离守卫：demoMode={}，演示供应商={}，演示条目={}",
                            policy.demoMode(),
                            providers,
                            entries);
        }
        // 供运维巡检对账；不影响启动成败（业务表统计失败也不阻断服务）。
        org.slf4j.LoggerFactory.getLogger(FixtureStartupGuard.class)
                .debug("fixture guard summary {}", Json.write(summary));
    }
}
