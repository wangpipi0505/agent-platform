package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationRuntimeRoutesTest {
    private static final String TASK = "task-0123456789abcdef0123";
    private final ServiceRoutePolicy routes = new ServiceRoutePolicy();

    @Test
    void readActionsBindTheCredentialApplicationAndExactPublicTask() {
        for (String suffix : new String[]{"", "/events", "/result"}) {
            String path = "/v1/tasks/" + TASK + suffix;
            assertThat(IntegrationRuntimeRoutes.taskRead("GET", path)).isTrue();
            var call = IntegrationRuntimeRoutes.taskCall(routes, "GET", path, "app-a");
            assertThat(call.target()).isEqualTo("engine");
            assertThat(IntegrationRuntimeRoutes.permits(call, "app-a")).isTrue();
            assertThat(IntegrationRuntimeRoutes.permits(call, "app-b")).isFalse();
            assertThat(IntegrationRuntimeRoutes.permits(
                    new ServiceCall("engine", call.action(), new ResourceBinding(
                            call.resource().kind(), call.resource().id(), "0".repeat(64))), "app-a")).isFalse();
        }
    }

    @Test
    void numericIdsControlActionsAndStreamsDoNotMatchTaskRead() {
        for (String suffix : new String[]{"/cancel", "/resume", "/events/stream", "/result/"}) {
            assertThat(IntegrationRuntimeRoutes.taskRead("GET", "/v1/tasks/" + TASK + suffix)).isFalse();
        }
        assertThat(IntegrationRuntimeRoutes.taskRead("GET", "/v1/tasks/123")).isFalse();
        assertThat(IntegrationRuntimeRoutes.taskRead("POST", "/v1/tasks/" + TASK)).isFalse();
        assertThatThrownBy(() -> IntegrationRuntimeRoutes.taskCall(routes, "GET", "/v1/tasks/123", "app-a"))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void onlyEventReadsAcceptTheCursorAndLimit() {
        String path = "/v1/tasks/" + TASK;
        IntegrationRuntimeRoutes.requireTaskQuery(path + "/events", "limit=4&afterSeq=1");
        for (String query : new String[]{"afterSeq=1&afterSeq=2", "limit=-1", "tenant=x", "limit=1&"}) {
            assertThatThrownBy(() -> IntegrationRuntimeRoutes.requireTaskQuery(path + "/events", query))
                    .isInstanceOf(PlatformException.class);
        }
        assertThatThrownBy(() -> IntegrationRuntimeRoutes.requireTaskQuery(path + "/result", "limit=1"))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void cancelIsBoundToOneApplicationAndRequiresAnIntegrationAdministrator() {
        String path = "/v1/tasks/" + TASK + "/cancel";
        assertThat(IntegrationRuntimeRoutes.taskCancel("POST", path)).isTrue();
        assertThat(IntegrationRuntimeRoutes.taskCancel("GET", path)).isFalse();
        assertThat(IntegrationRuntimeRoutes.taskCancel("POST", "/v1/tasks/123/cancel")).isFalse();
        var call = IntegrationRuntimeRoutes.taskCall(routes, "POST", path, "app-a");
        assertThat(IntegrationRuntimeRoutes.permits(call, "app-a")).isTrue();
        assertThat(IntegrationRuntimeRoutes.permits(call, "app-b")).isFalse();
        assertThat(IntegrationRuntimeRoutes.permitsRoles(call, java.util.Set.of("AGENT_ADMIN"))).isTrue();
        assertThat(IntegrationRuntimeRoutes.permitsRoles(call, java.util.Set.of("AGENT_VIEWER", "ADMIN"))).isFalse();
    }

    @Test
    void taskAdmissionPinsTheApplicationAndCannotSendAnIntegrationGrantToSkills() {
        var root = new ResourceBinding("app-version", "app-a@7", "f".repeat(64));
        var call = IntegrationTaskRoutes.startCall("app-a", root);
        assertThat(IntegrationRuntimeRoutes.permits(call, "app-a")).isTrue();
        assertThat(IntegrationRuntimeRoutes.permits(call, "app-b")).isFalse();
        assertThat(IntegrationRuntimeRoutes.permitsRoles(call, java.util.Set.of("AGENT_OPERATOR"))).isTrue();
        assertThat(IntegrationRuntimeRoutes.permitsRoles(call, java.util.Set.of("AGENT_VIEWER", "ADMIN"))).isFalse();
        assertThat(IntegrationTaskRoutes.downstream("engine", new ServiceCall("model", "model.chat-stream", root))).isTrue();
        assertThat(IntegrationTaskRoutes.downstream("engine", new ServiceCall("skill", "skill.invoke", root))).isFalse();
        assertThat(IntegrationTaskRoutes.downstream("model", new ServiceCall("model", "model.chat-stream", root))).isFalse();
    }

    @Test
    void resultFileMatchesOnlyItsOwnRunAndBindsTheCredentialApplication() {
        String run = "r_" + "a".repeat(32);
        String path = "/v1/runs/" + run + "/artifacts/task_result_" + "a".repeat(32) + "/download";
        assertThat(IntegrationRuntimeRoutes.supported("GET", path)).isTrue();
        assertThat(IntegrationRuntimeRoutes.supported("HEAD", path)).isFalse();
        assertThat(IntegrationRuntimeRoutes.supported("GET", path.replace("result_a", "result_b"))).isFalse();
        assertThat(IntegrationRuntimeRoutes.supported("GET", path.replace("task_result_", "art_"))).isFalse();
        assertThat(IntegrationRuntimeRoutes.supported("GET", path + "/")).isFalse();
        var call = IntegrationRuntimeRoutes.taskCall(routes, "GET", path, "app-a");
        assertThat(call.target()).isEqualTo("engine");
        assertThat(IntegrationRuntimeRoutes.permits(call, "app-a")).isTrue();
        assertThat(IntegrationRuntimeRoutes.permits(call, "app-b")).isFalse();
        assertThatThrownBy(() -> IntegrationRuntimeRoutes.requireTaskQuery(path, "ignored=1"))
                .isInstanceOf(PlatformException.class);
    }
}
