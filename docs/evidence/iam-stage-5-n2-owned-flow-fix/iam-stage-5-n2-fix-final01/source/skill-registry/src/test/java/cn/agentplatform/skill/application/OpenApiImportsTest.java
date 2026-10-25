package cn.agentplatform.skill.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.agentplatform.common.*;

import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.*;

class OpenApiImportsTest {
    SkillCatalog catalog = mock(SkillCatalog.class);
    OpenApiImports imports = new OpenApiImports(catalog);

    @BeforeEach
    void actor() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("editor")
                        .claim("tenant_id", "openapi-tests")
                        .claim("roles", List.of("EDITOR"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsExternalReferenceBeforeAnyResolverCanReadNetworkOrFile() {
        var failure =
                assertThrows(
                        PlatformException.class,
                        () ->
                                imports.preview(
                                        Map.of(
                                                "document",
                                                document()
                                                        .replace(
                                                                "type: string",
                                                                "$ref: 'file:///never-read'"))));
        assertEquals("OPENAPI_EXTERNAL_REF", failure.code());
        verifyNoInteractions(catalog);
    }

    @Test
    void previewsYamlPathQueryAndJsonSchemasWithoutTrustingDocumentExtensions() {
        var preview = imports.preview(Map.of("document", document()));
        assertEquals(Digest.sha256(document()), preview.get("documentSha256"));
        var operations = (List<Map<String, Object>>) preview.get("operations");
        assertEquals(1, operations.size());
        var operation = operations.getFirst();
        assertEquals("readItem", operation.get("operationId"));
        assertEquals(true, operation.get("supported"));
        assertEquals(List.of("id"), SkillCatalog.object(operation.get("httpMapping")).get("path"));
        assertFalse(Json.write(operation).contains("vault:from-document"));
    }

    @Test
    void unsupportedBodyMediaAndSecurityCombinationsAreDiagnosticAndCannotSave() {
        var doc =
                document()
                        .replace("get:", "post:")
                        .replace(
                                "      responses:",
                                "      security:\n"
                                        + "        - first: []\n"
                                        + "          second: []\n"
                                        + "      requestBody:\n"
                                        + "        content:\n"
                                        + "          text/plain:\n"
                                        + "            schema: {type: string}\n"
                                        + "      responses:");
        var op =
                ((List<Map<String, Object>>)
                                imports.preview(Map.of("document", doc)).get("operations"))
                        .getFirst();
        assertEquals(false, op.get("supported"));
        assertFalse(((List<?>) op.get("diagnostics")).isEmpty());
        assertThrows(PlatformException.class, () -> imports.create(request(doc)));
        verifyNoInteractions(catalog);
    }

    @Test
    void convertsSelectedOperationAndExplicitSixDimensionsIntoEditableDraft() {
        when(catalog.create(anyMap()))
                .thenAnswer(
                        invocation -> {
                            var result =
                                    new LinkedHashMap<String, Object>(invocation.getArgument(0));
                            result.put("status", "DRAFT");
                            return result;
                        });
        var response = imports.create(request(document()));
        var draft = ((List<Map<String, Object>>) response.get("drafts")).getFirst();
        assertEquals("DRAFT", draft.get("status"));
        assertEquals("https://example.test/api/items/{id}", draft.get("endpoint"));
        assertEquals("GET", draft.get("httpMethod"));
        assertEquals("HTTP", draft.get("type"));
        assertEquals("readItem", SkillCatalog.object(draft.get("importSource")).get("operationId"));
        assertEquals(
                Digest.sha256(document()),
                SkillCatalog.object(draft.get("importSource")).get("documentSha256"));
        assertEquals("", draft.get("authRef"));
        assertEquals(List.of("READER"), SkillCatalog.object(draft.get("guard")).get("roles"));
        verify(catalog).create(anyMap());
        verifyNoMoreInteractions(catalog);
    }

    @Test
    void requiresExplicitAccessAndFailureSemanticsRatherThanDocumentDefaults() {
        var request = request(document());
        var overrides = SkillCatalog.object(request.get("overrides"));
        var manual = SkillCatalog.object(overrides.get("readItem"));
        manual.remove("guard");
        assertThrows(PlatformException.class, () -> imports.create(request));
        verifyNoInteractions(catalog);
    }

    @Test
    void boundsDocumentAndRejectsDuplicateOperationIdsAndRecursiveSchemas() {
        assertThrows(
                PlatformException.class,
                () -> imports.preview(Map.of("document", " ".repeat(1_048_577))));
        var duplicated =
                document()
                        .replace(
                                "paths:",
                                "paths:\n"
                                        + "  /other:\n"
                                        + "    get:\n"
                                        + "      operationId: readItem\n"
                                        + "      responses: {'200': {description: ok, content:"
                                        + " {application/json: {schema: {type: object}}}}}");
        assertThrows(
                PlatformException.class, () -> imports.preview(Map.of("document", duplicated)));
        var recursive =
                document()
                        + "\n"
                        + "components:\n"
                        + "  schemas:\n"
                        + "    Recursive:\n"
                        + "      type: object\n"
                        + "      properties:\n"
                        + "        child: {$ref: '#/components/schemas/Recursive'}\n";
        recursive =
                recursive.replace(
                        "schema: {type: object}",
                        "schema: {$ref: '#/components/schemas/Recursive'}");
        var op =
                ((List<Map<String, Object>>)
                                imports.preview(Map.of("document", recursive)).get("operations"))
                        .getFirst();
        assertEquals(false, op.get("supported"));
    }

    @Test
    void rejectsNonEditorAndRejectsUnconfiguredAuthReferenceFromExplicitAdminInput() {
        var jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("viewer")
                        .claim("tenant_id", "openapi-tests")
                        .claim("roles", List.of("VIEWER"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertEquals(
                403,
                assertThrows(
                                PlatformException.class,
                                () -> imports.preview(Map.of("document", document())))
                        .status());
        jwt =
                Jwt.withTokenValue("verified-test")
                        .header("alg", "HS256")
                        .subject("admin")
                        .claim("tenant_id", "openapi-tests")
                        .claim("roles", List.of("ADMIN"))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var request = request(document());
        SkillCatalog.object(SkillCatalog.object(request.get("overrides")).get("readItem"))
                .put("authRef", "vault:not-a-valid-ref");
        assertEquals(
                "OPENAPI_AUTH_REF",
                assertThrows(PlatformException.class, () -> imports.create(request)).code());
        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsJsonDuplicateKeysYamlAliasesAndTrailingDocuments() {
        for (String doc :
                List.of(
                        "{\"openapi\":\"3.0.3\",\"openapi\":\"3.0.3\"}",
                        document() + "\n---\nfoo: bar\n",
                        document() + "\nmalicious: &a [1,2]\nexpanded: [*a,*a]\n"))
            assertThrows(PlatformException.class, () -> imports.preview(Map.of("document", doc)));
    }

    @Test
    void expandsLocalSchemaReferencesAndConvertsRequiredJsonBody() {
        var doc =
                document()
                                .replace("get:", "post:")
                                .replace(
                                        "      responses:",
                                        "      requestBody:\n"
                                                + "        required: true\n"
                                                + "        content:\n"
                                                + "          application/json:\n"
                                                + "            schema: {$ref:"
                                                + " '#/components/schemas/Body'}\n"
                                                + "      responses:")
                        + "\n"
                        + "components:\n"
                        + "  schemas:\n"
                        + "    Body:\n"
                        + "      type: object\n"
                        + "      required: [name]\n"
                        + "      properties:\n"
                        + "        name: {type: string}\n";
        var op =
                ((List<Map<String, Object>>)
                                imports.preview(Map.of("document", doc)).get("operations"))
                        .getFirst();
        assertEquals(true, op.get("supported"));
        assertEquals("body", SkillCatalog.object(op.get("httpMapping")).get("body"));
        var props =
                SkillCatalog.object(SkillCatalog.object(op.get("inputSchema")).get("properties"));
        assertEquals(List.of("name"), SkillCatalog.object(props.get("body")).get("required"));
    }

    @Test
    void givesDiagnosticsForUnsupportedSchemaAndSecurity() {
        for (String doc :
                List.of(
                        document()
                                .replace(
                                        "schema: {type: object}",
                                        "schema: {oneOf: [{type: string}, {type: number}]}"),
                        document()
                                        .replace(
                                                "      responses:",
                                                "      security:\n"
                                                        + "        - keyAuth: []\n"
                                                        + "      responses:")
                                + "\n"
                                + "components:\n"
                                + "  securitySchemes:\n"
                                + "    keyAuth: {type: apiKey, in: header, name: X-Key}\n")) {
            var op =
                    ((List<Map<String, Object>>)
                                    imports.preview(Map.of("document", doc)).get("operations"))
                            .getFirst();
            assertEquals(false, op.get("supported"));
            assertFalse(((List<?>) op.get("diagnostics")).isEmpty());
        }
    }

    static Map<String, Object> request(String doc) {
        var manual = new LinkedHashMap<String, Object>();
        manual.put("code", "read_item");
        manual.put("name", "读条目");
        manual.put("description", "按条目ID读取公开详情；不用于修改。示例：id=42。");
        manual.put(
                "execution",
                Map.of(
                        "readonly",
                        true,
                        "sideEffect",
                        false,
                        "idempotent",
                        true,
                        "cancellable",
                        false));
        manual.put("guard", Map.of("roles", List.of("READER"), "attributes", Map.of()));
        manual.put("errorSemantics", List.of(Map.of("code", "PARAM_ERROR", "retryable", false)));
        manual.put(
                "returnSpec",
                Map.of(
                        "summaryTemplate",
                        "已查询条目",
                        "structuredPath",
                        "$",
                        "fileRefPaths",
                        List.of()));
        return new LinkedHashMap<>(
                Map.of(
                        "document",
                        doc,
                        "includeOps",
                        List.of("readItem"),
                        "defaults",
                        Map.of("baseUrl", "https://example.test/api"),
                        "overrides",
                        new LinkedHashMap<>(Map.of("readItem", manual))));
    }

    static String document() {
        return """
        openapi: 3.0.3
        info: {title: Items, version: '1'}
        x-authRef: vault:from-document
        x-roles: [ADMIN]
        paths:
          /items/{id}:
            get:
              operationId: readItem
              parameters:
                - name: id
                  in: path
                  required: true
                  schema: {type: string}
                - name: verbose
                  in: query
                  schema: {type: boolean}
              responses:
                '200':
                  description: ok
                  content:
                    application/json:
                      schema: {type: object}
        """;
    }
}
