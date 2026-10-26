package cn.agentplatform.skill.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * AP-S06 启动守卫：启动即盘点技能侧 fixture 演示数据并写明语义日志。
 * 生产/默认模式下检出的 fixture 技能会被管理面列表标注 source=FIXTURE；其中已发布并被
 * 引用的仍可运行（历史契约有效），纯草稿建议经 POST /admin/skills/fixture/purge 清理。
 */
@Component
public class FixtureStartupGuard implements ApplicationRunner {
    private final JdbcTemplate db;
    private final FixtureSkills fixtures;

    public FixtureStartupGuard(JdbcTemplate db, FixtureSkills fixtures) {
        this.db = db;
        this.fixtures = fixtures;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long skills =
                db.queryForObject(
                        "select count(*) from skill.skill where code ~ 'fixture' or code ~ '^smoke_'",
                        Long.class);
        var log = org.slf4j.LoggerFactory.getLogger(FixtureStartupGuard.class);
        if (!fixtures.demoMode() && skills > 0) {
            log.warn(
                    "fixture 隔离守卫：检测到 {} 个演示技能（demo-mode=false）；管理面列表将标注"
                            + " source=FIXTURE，非演示模式禁止再注册；清理入口 POST /admin/skills/fixture/purge",
                    skills);
        } else {
            log.info("fixture 隔离守卫：demoMode={}，演示技能={}", fixtures.demoMode(), skills);
        }
    }
}
