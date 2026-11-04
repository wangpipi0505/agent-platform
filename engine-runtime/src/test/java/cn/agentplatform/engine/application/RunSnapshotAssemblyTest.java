package cn.agentplatform.engine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import cn.agentplatform.engine.infra.*;

/**
 * MNT-05 装配证据：容器走 {@link RunService} 的 {@code @Autowired} 构造器时，注入的
 * {@link RunSnapshotService} 与 {@link RunAdmissionService} 必须是同一批 bean，而不是门面里另
 * new 的一份。
 *
 * <p>用真实 {@link AnnotationConfigApplicationContext}（不是手工 new）：这里要证明的正是"经实际
 * Spring 装配到达入口"。依赖全部用 mock，不触网、不连库；快照行为本身由
 * {@code RunServiceContextPackTest}/{@code CapabilityRunSnapshotTest} 覆盖。
 */
class RunSnapshotAssemblyTest {

    @Test
    void containerInjectsTheSnapshotCollaboratorIntoTheFacade() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TraceStore.class, () -> {
                TraceStore trace = mock(TraceStore.class);
                // 运行期 @Scheduled 清理会用到 db()；返回 mock 让周期任务空转，避免噪声 NPE。
                when(trace.db()).thenReturn(mock(JdbcTemplate.class));
                return trace;
            });
            context.registerBean(RuntimeClients.class, () -> mock(RuntimeClients.class));
            context.registerBean(FlowDriver.class, () -> mock(FlowDriver.class));
            context.registerBean(AgentDriver.class, () -> mock(AgentDriver.class));
            context.registerBean(RunScheduler.class, () -> mock(RunScheduler.class));
            context.register(RunSnapshotService.class, RunAdmissionService.class, RunService.class);
            context.refresh();

            RunSnapshotService snapshots = context.getBean(RunSnapshotService.class);
            RunAdmissionService admissions = context.getBean(RunAdmissionService.class);
            RunService service = context.getBean(RunService.class);

            assertThat(ReflectionTestUtils.getField(service, "snapshots")).isSameAs(snapshots);
            assertThat(service.snapshots()).isSameAs(snapshots);
            assertThat(ReflectionTestUtils.getField(service, "admissions")).isSameAs(admissions);
            assertThat(service.admissions()).isSameAs(admissions);
        }
    }
}
