package cn.agentplatform.engine.application;

import java.time.Clock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import cn.agentplatform.common.authority.HttpCurrentAuthorityClient;
import cn.agentplatform.engine.infra.RunAuthorityConfiguration;
import cn.agentplatform.engine.infra.RunAuthorityStore;
import cn.agentplatform.engine.infra.RuntimeClients;
import cn.agentplatform.engine.infra.TraceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * EX-26 平台侧装配：{@code agent.security.mode=iam} 时 {@link RunService#admissionProof} 的两个依赖
 * 必须由容器补齐；其它模式下必须保持"没有授权依赖、一律 403 IAM_ADMISSION_UNAVAILABLE"的默认拒绝状态。
 *
 * <p>用 {@link ApplicationContextRunner} 而不是 {@code @SpringBootTest}：这里要断言的是装配关系
 * （哪个 bean 有没有、注入到了哪个字段），不需要真实 PG。{@link RunAuthorityStore} 的构造只持有
 * {@link DataSource} 并建一个 {@code JdbcTemplate}，不建连接，所以 mock 的 DataSource 足够；
 * 也因此本测试不需要 {@code AGENT_DB_PASSWORD} 门控（真实 SQL 路径由 RunAuthorityPersistencePostgresTest 覆盖）。
 *
 * <p>{@link RunService} 用既有的 5 参 public 构造实例化（和 10 处测试夹具同一入口），验证的是
 * setter 注入这条生产专用通道，而不是另一个只有生产才走得到的构造器。
 */
class RunAuthorityAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RunAuthorityConfiguration.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(HttpCurrentAuthorityClient.class, () -> mock(HttpCurrentAuthorityClient.class))
            .withBean(RunService.class, RunAuthorityAssemblyTest::productionRunService);

    @Test
    void iamModeWiresTheAdmissionDependenciesIntoTheProductionRunService() {
        runner.withPropertyValues("agent.security.mode=iam").run(context -> {
            assertThat(context).hasNotFailed();
            RunAuthority authority = context.getBean(RunAuthority.class);
            RunAuthorityStore store = context.getBean(RunAuthorityStore.class);
            RunService service = context.getBean(RunService.class);

            // 同一实例：生产路径上续期用的就是容器里这一个 store/authority，不是另 new 的一份。
            assertThat(ReflectionTestUtils.getField(service, "runAuthority")).isSameAs(authority);
            assertThat(ReflectionTestUtils.getField(service, "admissionStore")).isSameAs(store);
        });
    }

    @Test
    void legacyModeKeepsTheUnwiredDefaultDenyState() {
        runner.withPropertyValues("agent.security.mode=password").run(context -> {
            assertThat(context).hasNotFailed();
            // 旧部署（口令/API key）没有 authority-service，不能凭空造出这些 bean。
            assertThat(context.getBeansOfType(RunAuthority.class)).isEmpty();
            assertThat(context.getBeansOfType(RunAuthorityStore.class)).isEmpty();

            RunService service = context.getBean(RunService.class);
            assertThat(ReflectionTestUtils.getField(service, "runAuthority")).isNull();
            assertThat(ReflectionTestUtils.getField(service, "admissionStore")).isNull();
        });
    }

    @Test
    void backoffOutsideTheBoundedWindowFailsAtStartup() {
        runner.withPropertyValues("agent.security.mode=iam", "agent.engine.run-authority-backoff-seconds=61")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("RUN_AUTHORITY_BACKOFF_OUT_OF_RANGE");
                });
    }

    @Test
    void missingAuthorityClientFailsAtStartupInsteadOfWiringAnAlwaysDeniedProof() {
        new ApplicationContextRunner()
                .withUserConfiguration(RunAuthorityConfiguration.class)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("agent.security.mode=iam")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("IAM_AUTHORITY_CLIENT_REQUIRED");
                });
    }

    private static RunService productionRunService() {
        return new RunService(mock(TraceStore.class), mock(RuntimeClients.class), mock(FlowDriver.class),
                mock(AgentDriver.class), mock(RunScheduler.class));
    }
}
