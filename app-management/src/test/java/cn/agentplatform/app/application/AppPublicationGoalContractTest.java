package cn.agentplatform.app.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 检查实际发布预检使用的typeChecks与引用门，不连接DB，也不替代发布事务/落库验证。 */
class AppPublicationGoalContractTest {
    private final AppPublicationService.Port port = mock(AppPublicationService.Port.class);
    private final AppPublicationService service = new AppPublicationService(port);
    private Map<String,Object> goal() {
        return Json.read("{\"goalId\":\"g\",\"criterion\":{\"target\":{\"objectType\":\"Order\",\"objectId\":\"B\"},"
                + "\"readback\":{\"skillCode\":\"verify\",\"arguments\":{\"id\":\"B\"},\"objectIdPath\":\"$.id\",\"resultObjectIdPath\":\"$.id\"},"
                + "\"predicate\":{\"path\":\"$.rows[0].state\",\"operator\":\"equals\",\"value\":false}}}", Map.class);
    }
    @Test void precheckAcceptsCanonicalAndRejectsUnexecutableDialect() {
        var goal = goal();
        assertThatCode(() -> service.typeChecks(Map.of("goals", List.of(goal)), Map.of())).doesNotThrowAnyException();
        @SuppressWarnings("unchecked") var criterion = (Map<String,Object>) goal.get("criterion");
        criterion.put("predicate", Map.of("path", "rows[0].state", "equals", false));
        assertThatThrownBy(() -> service.typeChecks(Map.of("goals", List.of(goal)), Map.of()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("goal@1.0");
    }
    @Test void precheckRequiresEnabledReadonlySkill() {
        when(port.skillUrl()).thenReturn("http://not-called.invalid");
        when(port.remote(anyString(), eq("/internal/skills/verify"))).thenReturn(Map.of("status", "PUBLISHED", "versions",
                List.of(Map.of("versionNo", 1, "status", "PUBLISHED", "execution", Map.of("readonly", false, "sideEffect", true)))));
        var config = Map.<String,Object>of("goals", List.of(goal()), "skillRefs", List.of(Map.of("code", "verify", "version", 1)));
        assertThatThrownBy(() -> service.referenceChecks(config, Map.of(), false)).hasMessageContaining("只读");
        assertThatThrownBy(() -> service.referenceChecks(Map.of("goals", List.of(goal())), Map.of(), false)).hasMessageContaining("只读");
    }
}
