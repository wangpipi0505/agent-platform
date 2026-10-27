package cn.agentplatform.skill.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.SkillExecution;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

/** PLAT-05 T05.2：/tasks/{id}/result 的门户读取面（controller 只校验参数并转调应用层）。 */
class TaskResultControllerTest {
    SkillExecution execution = mock(SkillExecution.class);
    MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new TaskResultController(execution))
                    .setControllerAdvice(new PlatformErrors())
                    .build();

    @Test
    void failedTaskReturnsCompleteErrorObject() throws Exception {
        var error =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "semantic",
                                "REMOTE_FAILED",
                                "code",
                                "TLE_CHECKSUM_INVALID",
                                "category",
                                "BUSINESS",
                                "retryable",
                                false,
                                "userMessage",
                                "外部任务失败：wrong checksum",
                                "detail",
                                Map.of("raw", "wrong checksum"),
                                "traceId",
                                "trace-1"));
        when(execution.taskResult(42L))
                .thenReturn(
                        Map.of(
                                "callId",
                                42L,
                                "status",
                                "FAILED",
                                "result",
                                Map.of("status", "FAILED", "error", error)));
        mvc.perform(get("/admin/skills/tasks/42/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.result.error.semantic").value("REMOTE_FAILED"))
                .andExpect(jsonPath("$.data.result.error.code").value("TLE_CHECKSUM_INVALID"))
                .andExpect(jsonPath("$.data.result.error.category").value("BUSINESS"))
                .andExpect(jsonPath("$.data.result.error.retryable").value(false))
                .andExpect(
                        jsonPath("$.data.result.error.userMessage").value("外部任务失败：wrong checksum"));
    }

    @Test
    void unknownTaskIsReadable404() throws Exception {
        when(execution.taskResult(7L))
                .thenThrow(new PlatformException(404, "FACT_NOT_FOUND", "任务不存在或不可见"));
        mvc.perform(get("/admin/skills/tasks/7/result"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FACT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("任务不存在或不可见"));
    }

    @Test
    void unfinishedTaskIsReadable409() throws Exception {
        when(execution.taskResult(9L))
                .thenThrow(new PlatformException(409, "TASK_NOT_FINAL", "任务尚未到达终态，请稍后查询"));
        mvc.perform(get("/admin/skills/tasks/9/result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FINAL"));
    }

    @Test
    void nonNumericTaskIdIsRejectedAsBadRequest() throws Exception {
        // 路径参数类型不匹配由框架参数校验拒绝，controller 本体不被调用。
        mvc.perform(get("/admin/skills/tasks/abc/result")).andExpect(status().isBadRequest());
    }
}
