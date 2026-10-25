package cn.agentplatform.skill.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.agentplatform.common.*;
import cn.agentplatform.skill.application.OpenApiImports;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

class OpenApiImportControllerTest {
    OpenApiImports imports = mock(OpenApiImports.class);
    MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new OpenApiImportController(imports))
                    .setControllerAdvice(new PlatformErrors())
                    .build();

    @Test
    void previewsUsingCommonEnvelope() throws Exception {
        when(imports.preview(anyMap()))
                .thenReturn(Map.of("operations", List.of(), "documentSha256", "abc"));
        mvc.perform(
                        post("/admin/skills/import-openapi/preview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"document\":\"openapi text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.documentSha256").value("abc"));
    }

    @Test
    void createNeverReturnsCredentialReference() throws Exception {
        when(imports.create(anyMap()))
                .thenReturn(
                        Map.of(
                                "drafts",
                                List.of(
                                        Map.of(
                                                "status",
                                                "DRAFT",
                                                "authRef",
                                                "vault:test",
                                                "definition",
                                                Map.of("authRef", "vault:test")))));
        mvc.perform(
                        post("/admin/skills/import-openapi")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drafts[0].authRef").doesNotExist())
                .andExpect(jsonPath("$.data.drafts[0].definition.authRef").doesNotExist())
                .andExpect(jsonPath("$.data.drafts[0].authConfigured").value(true));
    }

    @Test
    void boundsEntireRequestBeforeBindingUntrustedDocument() throws Exception {
        mvc.perform(
                        post("/admin/skills/import-openapi/preview")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"document\":\"" + "x".repeat(2_097_152) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("OPENAPI_REQUEST_SIZE"));
        verifyNoInteractions(imports);
    }

    @Test
    void rejectsNullAndTrailingJsonBeforeCallingService() throws Exception {
        for (String input : List.of("null", "{} {}"))
            mvc.perform(
                            post("/admin/skills/import-openapi/preview")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(input))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(imports);
    }
}
