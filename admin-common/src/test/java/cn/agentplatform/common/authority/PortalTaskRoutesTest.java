package cn.agentplatform.common.authority;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PortalTaskRoutesTest {
    private static final String TASK = "/v1/tasks/task-1234567890abcdef1234";

    @Test void bindsOwnerOperationsToTheExactPublicRoute() {
        var policy = new ServiceRoutePolicy();
        var read = PortalTaskRoutes.match("GET", TASK);
        var confirm = PortalTaskRoutes.match("POST", TASK + "/plans/plan-1/revisions/2/confirm");
        assertThat(read.permission()).isEqualTo("agent.view");
        assertThat(confirm.permission()).isEqualTo("agent.plan.confirm");
        assertThat(confirm.call(policy).resource()).isNotEqualTo(
                PortalTaskRoutes.match("POST", TASK + "/plans/plan-1/revisions/3/confirm").call(policy).resource());
        assertThat(PortalTaskRoutes.match("POST", TASK + "/plans").permission()).isEqualTo("agent.plan.revise");
        assertThat(PortalTaskRoutes.match("POST", TASK + "/execute").permission()).isEqualTo("agent.plan.execute");
        assertThat(PortalTaskRoutes.match("POST", TASK + "/result")).isNull();
        assertThat(PortalTaskRoutes.match("POST", TASK + "/cancel")).isNull();
        assertThat(PortalTaskRoutes.match("GET", TASK + "/plans/%2F/revisions/1")).isNull();
        assertThat(PortalTaskRoutes.match("GET", "/v1/tasks/42")).isNull();
    }
}
