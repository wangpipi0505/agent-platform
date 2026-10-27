package cn.agentplatform.app.application;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.schema.SchemaDialect;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAT-08：应用预检的 Schema 方言。技能只用联合数组、应用只用单字符串 type 的互斥写法都要通过；
 * 非法联合与远程引用必须被拒绝，并保留旧错误前缀 {@code Schema缺少有效type} 且追加 JSON Pointer。
 *
 * <p>不连数据库：只覆盖 AppService.validateSchema 这个纯预检规则，构造参数仅需要非空事务管理器。
 */
class AppSchemaDialectTest {

    private final AppService service = service();

    private static AppService service() {
        PlatformTransactionManager manager =
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(TransactionStatus status) {}

                    @Override
                    public void rollback(TransactionStatus status) {}
                };
        return new AppService(
                null,
                manager,
                "http://127.0.0.1:1",
                "http://127.0.0.1:1",
                "http://127.0.0.1:1",
                "http://127.0.0.1:1");
    }

    @Test
    void acceptsUnionTypeInPrecheck() {
        assertDoesNotThrow(
                () -> service.validateSchema(Map.of("type", List.of("string", "null"))));
    }

    @Test
    void acceptsNullableStyleInPrecheck() {
        assertDoesNotThrow(
                () ->
                        service.validateSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "errorMessage",
                                                Map.of("type", "string", "nullable", true)))));
    }

    @Test
    void rejectsUnionOfTwoConcreteTypesWithPointer() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.validateSchema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "errorMessage",
                                                        Map.of(
                                                                "type",
                                                                List.of("string", "object"))))));
        assertTrue(error.getMessage().startsWith("Schema缺少有效type"), error.getMessage());
        assertTrue(error.getMessage().contains("/properties/errorMessage/type"), error.getMessage());
    }

    @Test
    void rejectsRemoteRefWithPointer() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.validateSchema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of(
                                                        "body",
                                                        Map.of(
                                                                "$ref",
                                                                "https://example.com/schema.json")))));
        assertTrue(error.getMessage().contains("/properties/body/$ref"), error.getMessage());
    }

    @Test
    void acceptsNestedUnionInsideItems() {
        assertDoesNotThrow(
                () ->
                        service.validateSchema(
                                Map.of(
                                        "type",
                                        "array",
                                        "items",
                                        Map.of("type", List.of("string", "null")))));
    }

    @Test
    void keepsLegacySchemaAccepted() {
        assertDoesNotThrow(
                () ->
                        service.validateSchema(
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(
                                                "region", Map.of("type", "string"),
                                                "rows",
                                                        Map.of(
                                                                "type",
                                                                "array",
                                                                "items",
                                                                Map.of(
                                                                        "type",
                                                                        "object"))))));
    }

    @Test
    void legacySchemaNormalizesToTheSameTreeAsJsonTree() {
        Map<String, Object> legacy =
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties",
                        Map.of(
                                "region", Map.of("type", "string", "enum", List.of("A", "B")),
                                "rows",
                                        Map.of(
                                                "type", "array",
                                                "items",
                                                Map.of(
                                                        "type", "object",
                                                        "properties",
                                                        Map.of(
                                                                "id",
                                                                Map.of("type", "integer"))))));
        var tree = Json.tree(legacy);
        assertEquals(tree, SchemaDialect.normalize(tree), "旧 schema 归一后必须与 Json.tree 逐字段相等");
        assertTrue(SchemaDialect.validate(tree).isEmpty());
    }

    @Test
    void rejectsMissingRootTypeWithLegacyPrefixAndPointer() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.validateSchema(Map.of("properties", Map.of())));
        assertTrue(error.getMessage().startsWith("Schema缺少有效type"), error.getMessage());
        assertTrue(error.getMessage().contains("/type"), error.getMessage());
    }

    @Test
    void rejectsNonObjectRootWithPointer() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.validateSchema(List.of("string")));
        assertTrue(error.getMessage().startsWith("Schema缺少有效type"), error.getMessage());
        assertTrue(error.getMessage().contains("/: Schema必须为对象或布尔值"), error.getMessage());
    }

    @Test
    void ignoresNullSchema() {
        assertDoesNotThrow(() -> service.validateSchema(null));
    }
}
