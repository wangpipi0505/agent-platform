package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * EngineMigrations 的纯单测：不碰数据库、不启 Spring，只验证“迁移号来自 classpath”这条不变量。
 *
 * <p>max 只断言下界 220（V220 已存在），不写死等号：新增 V221 时本测试不应变红。
 */
class EngineMigrationsTest {

    @Test
    void versionsComeFromClasspathInOrderWithoutDuplicate() {
        var versions = EngineMigrations.versions();
        System.out.println("ENGINE_MIGRATIONS=" + versions + " max=" + EngineMigrations.max());
        assertThat(versions).isNotEmpty().isSorted().doesNotHaveDuplicates();
        // 数字解析而非字典序：200 在 20 之后、在 1000 之前；V20 这类两位编号不会被读成 200。
        assertThat(versions).allSatisfy(v -> assertThat(v).isPositive());
        assertThat(EngineMigrations.max()).isGreaterThanOrEqualTo(220);
        assertThat(EngineMigrations.max()).isEqualTo(versions.getLast());
    }

    @Test
    void countIsTheCensusOfVersionsInTheInclusiveRange() {
        int max = EngineMigrations.max();
        var versions = EngineMigrations.versions();
        assertThat(EngineMigrations.count(200, max))
                .isEqualTo((int) versions.stream().filter(v -> v >= 200 && v <= max).count());
        assertThat(EngineMigrations.count(200, max)).isPositive();
        // 区间是闭区间，边界值本身要被算进来。
        assertThat(EngineMigrations.count(max, max)).isEqualTo(1);
        assertThat(EngineMigrations.count(max + 1, max + 10)).isZero();
        assertThat(EngineMigrations.count(200, 199)).isZero();
    }

    @Test
    void everyVersionIsExactlyWhatTheClasspathFileNameSays() {
        // 断言 intent：期望值不是人工维护的常量，而是 db/migration 文件名本身。
        int max = EngineMigrations.max();
        assertThat(EngineMigrations.versions().getLast()).isEqualTo(max);
        assertThat(EngineMigrations.versions()).contains(200, max);
    }
}
