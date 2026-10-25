package cn.agentplatform.engine.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;
import cn.agentplatform.common.schema.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.SchemaValidatorsConfig;

import org.junit.jupiter.api.Test;

import java.util.Map;

/** Small real admissions under lowered limits; no expensive graph reaches networknt. */
class SharedSchemaBudgetTest {
    private static JsonNode declaration() {
        return Json.read("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
    }

    private static JsonNode draft(JsonNode schema) {
        return Json.tree(Map.of("config", Map.of("inputSchema", schema)));
    }

    private static JsonNode output(JsonNode schema) {
        return Json.tree(Map.of("format", Map.of("type", "json", "schema", schema)));
    }

    @Test
    void inputLowGraphBudgetRejectsBeforeNativeConfiguration() {
        try (var budget = mockStatic(SchemaWorkBudget.class, CALLS_REAL_METHODS);
                var nativeConfig = mockConstruction(SchemaValidatorsConfig.class)) {
            budget.when(() -> SchemaWorkBudget.inspect(any(JsonNode.class)))
                    .thenAnswer(call -> SchemaWorkBudget.inspect(call.getArgument(0), 1));
            var error =
                    assertThrows(
                            PlatformException.class,
                            () -> InputDeclarations.validate(draft(declaration()), Json.object()));
            assertEquals("INVALID_INPUT_DECLARATION", error.code());
            assertTrue(nativeConfig.constructed().isEmpty());
        }
    }

    @Test
    void outputLowGraphBudgetRejectsBeforeNativeConfiguration() {
        try (var budget = mockStatic(SchemaWorkBudget.class, CALLS_REAL_METHODS);
                var nativeConfig = mockConstruction(SchemaValidatorsConfig.class)) {
            budget.when(() -> SchemaWorkBudget.inspect(any(JsonNode.class)))
                    .thenAnswer(call -> SchemaWorkBudget.inspect(call.getArgument(0), 1));
            var error =
                    assertThrows(RuntimeFault.class, () -> new OutputPolicy(output(declaration())));
            assertEquals("OUTPUT_POLICY_INVALID", error.semantic);
            assertTrue(nativeConfig.constructed().isEmpty());
        }
    }

    @Test
    void inputExpiredDeadlineRejectsBeforeDeclarationCopy() {
        var raw = spy(Json.object().put("type", "object"));
        try (var ignored = SchemaDeadline.until(System.nanoTime() - 1)) {
            var error = assertThrows(PlatformException.class, () -> InputDeclarations.schema(raw));
            assertEquals("INVALID_INPUT_DECLARATION", error.code());
            verify(raw, never()).deepCopy();
        }
    }

    @Test
    void outputExpiredDeadlineRejectsConstruction() {
        try (var ignored = SchemaDeadline.until(System.nanoTime() - 1)) {
            assertThrows(RuntimeFault.class, () -> new OutputPolicy(output(declaration())));
        }
    }

    @Test
    void inputTinyInstanceBudgetProducesBadRequest() {
        var plan = SchemaWorkBudget.inspect(declaration());
        var limited = spy(plan);
        doAnswer(
                        call -> {
                            plan.admitInstance(call.getArgument(0), call.getArgument(1), 1);
                            return null;
                        })
                .when(limited)
                .admitInstance(any(JsonNode.class), anyLong());
        try (var budget = mockStatic(SchemaWorkBudget.class, CALLS_REAL_METHODS)) {
            budget.when(() -> SchemaWorkBudget.inspect(any(JsonNode.class))).thenReturn(limited);
            var error =
                    assertThrows(
                            PlatformException.class,
                            () ->
                                    InputDeclarations.validate(
                                            draft(declaration()), Json.read("{\"q\":\"a\"}")));
            assertEquals(400, error.status());
            assertTrue(error.getMessage().contains("工作量"));
        }
    }

    @Test
    void outputTinyInstanceBudgetBecomesBoundedVerdict() {
        var plan = SchemaWorkBudget.inspect(declaration());
        var limited = spy(plan);
        doAnswer(
                        call -> {
                            plan.admitInstance(call.getArgument(0), call.getArgument(1), 1);
                            return null;
                        })
                .when(limited)
                .admitInstance(any(JsonNode.class), anyLong());
        try (var budget = mockStatic(SchemaWorkBudget.class, CALLS_REAL_METHODS)) {
            budget.when(() -> SchemaWorkBudget.inspect(any(JsonNode.class))).thenReturn(limited);
            var verdict =
                    new OutputPolicy(output(declaration()))
                            .validate(Json.read("{\"text\":\"{}\"}"));
            assertFalse(verdict.valid());
            assertTrue(verdict.reason().contains("工作量"));
            assertTrue(verdict.reason().length() <= 1024);
        }
    }

    @Test
    void unsupportedShortRegexIsRejectedAtBothEngineBoundaries() {
        var schema = Json.read("{\"properties\":{\"q\":{\"pattern\":\"(?=a)a\"}}}");
        assertThrows(
                PlatformException.class,
                () -> InputDeclarations.validate(draft(schema), Json.object()));
        assertThrows(RuntimeFault.class, () -> new OutputPolicy(output(schema)));
    }

    @Test
    void outputExpiredValidationBecomesVerdictAndScopeRestores() {
        var policy = new OutputPolicy(output(declaration()));
        try (var ignored = SchemaDeadline.until(System.nanoTime() - 1)) {
            assertFalse(policy.validate(Json.read("{\"text\":\"{}\"}")).valid());
        }
        assertTrue(policy.validate(Json.read("{\"text\":\"{}\"}")).valid());
    }
}
