package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.*;

import io.minio.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@EnabledIfEnvironmentVariable(named = "AGENT_MINIO_ENDPOINT", matches = ".+")
class ArtifactPersistenceTest {
    static JdbcTemplate db;
    ArtifactStore store;
    String tenant;
    long call;

    @BeforeAll
    static void migrate() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:postgresql://127.0.0.1:25432/agent_platform_v12",
                        "agent_v12",
                        System.getenv("AGENT_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("skill")
                .defaultSchema("skill")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = new JdbcTemplate(ds);
    }

    @BeforeEach
    void prepare() {
        tenant = "artifact-test-" + UUID.randomUUID();
        call = System.nanoTime();
        identity(tenant, "owner");
        store = new ArtifactStore(db, System.getenv());
    }

    @AfterEach
    void cleanup() {
        db.update(
                "update skill.artifact set expires_at=now()-interval '1"
                        + " second',cleanup_after=now()-interval '1 second' where tenant_id=? and"
                        + " state<>'DELETED'",
                tenant);
        try {
            store.cleanTenant(tenant, 8);
        } finally {
            store.close();
            SecurityContextHolder.clearContext();
        }
    }

    void identity(String t, String p) {
        var jwt =
                Jwt.withTokenValue("verified-fixture")
                        .header("alg", "HS256")
                        .subject(p)
                        .claim("tenant_id", t)
                        .claim("roles", List.of("SERVICE"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    Map<String, Object> upload(int size) {
        byte[] json = Json.write(Map.of("data", "x".repeat(size))).getBytes(StandardCharsets.UTF_8);
        return store.offload(
                new byte[0],
                new ByteArrayInputStream(json),
                Map.of("type", "object"),
                call,
                "r_artifact");
    }

    @Test
    void fiveMegabyteArtifactIsOpaquePersistentAndDownloadableOnlyByOwnerAndRun() throws Exception {
        var ref = upload(5 * 1024 * 1024);
        String id = ref.get("artifactId").toString();
        assertFalse(Json.write(ref).contains("s3://"));
        assertFalse(ref.containsKey("uri"));
        assertTrue(ref.get("downloadUrl").toString().startsWith("/admin/artifacts/"));
        var row =
                db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and artifact_id=?",
                        tenant,
                        id);
        assertEquals("READY", row.get("state"));
        assertEquals("owner", row.get("principal_id"));
        assertEquals("r_artifact", row.get("run_id"));
        assertEquals(ref.get("sha256"), row.get("sha256"));
        try (var download = store.download(id, "r_artifact")) {
            byte[] actual = download.source().readAllBytes();
            assertEquals(((Number) ref.get("bytes")).longValue(), actual.length);
            assertEquals(
                    ref.get("sha256"),
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(actual)));
            assertEquals(
                    "x".repeat(5 * 1024 * 1024),
                    ArtifactStore.readJson(new ByteArrayInputStream(actual)).path("data").asText());
        }
        assertEquals(ref, store.controlledReference(Map.of("artifactId", id), "r_artifact"));
        var adapter = new SkillAdapters(store, null);
        var contract =
                Map.<String, Object>of(
                        "outputMaxBytes",
                        1024,
                        "outputSchema",
                        Map.of("type", "object"),
                        "returnSpec",
                        Map.of("fileRefPaths", List.of("$.file")));
        var mapped =
                adapter.read(
                        new ByteArrayInputStream(
                                Json.write(Map.of("file", Map.of("artifactId", id)))
                                        .getBytes(StandardCharsets.UTF_8)),
                        contract,
                        call,
                        "r_artifact");
        assertEquals(ref, mapped.fileRefs().getFirst());
        identity(tenant, "other");
        assertThrows(PlatformException.class, () -> store.download(id, "r_artifact"));
        identity("other-tenant", "owner");
        assertThrows(PlatformException.class, () -> store.download(id, "r_artifact"));
        identity(tenant, "owner");
        assertThrows(PlatformException.class, () -> store.download(id, "r_other"));
        assertThrows(
                PlatformException.class,
                () ->
                        store.controlledReference(
                                Map.of("artifactId", id, "uri", "s3://evil/key"), "r_artifact"));
    }

    @Test
    void schemaFailureDeletesExactObjectAndLeavesDurableCleanupRecord() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.offload(
                                new byte[0],
                                new ByteArrayInputStream(
                                        "{\"data\":1}".getBytes(StandardCharsets.UTF_8)),
                                Map.of("type", "array"),
                                call,
                                "r_artifact"));
        assertDeletedObject();
    }

    @Test
    void recordedAttachmentGetsNewRunOwnershipAndSurvivesSourceExpiry() throws Exception {
        var original = upload(5 * 1024 * 1024);
        String sourceId = original.get("artifactId").toString();
        var adapters = new SkillAdapters(store, null);
        var source =
                Json.tree(
                        Map.of(
                                "structured",
                                Map.of(),
                                "fileRefs",
                                List.of(original),
                                "modelSummary",
                                "已核实的大结果",
                                "cardPayloadJson",
                                "{\"title\":\"原卡片\"}"));
        var reused =
                adapters.recorded(
                        source,
                        Map.of("outputSchema", Map.of("type", "object")),
                        call + 1,
                        "r_reused",
                        "r_artifact");
        var copy = reused.fileRefs().getFirst();
        String copiedId = copy.get("artifactId").toString();
        assertNotEquals(sourceId, copiedId);
        assertEquals(original.get("sha256"), copy.get("sha256"));
        assertEquals(
                "/v1/runs/r_reused/artifacts/" + copiedId + "/download",
                copy.get("externalDownloadUrl"));
        assertEquals(
                "r_reused",
                db.queryForObject(
                        "select run_id from skill.artifact where artifact_id=?",
                        String.class,
                        copiedId));
        assertThrows(PlatformException.class, () -> store.download(sourceId, "r_reused"));
        identity(tenant, "other");
        assertThrows(
                PlatformException.class,
                () ->
                        adapters.recorded(
                                source,
                                Map.of("outputSchema", Map.of()),
                                call + 2,
                                "r_stolen",
                                "r_artifact"));
        identity(tenant, "owner");
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where"
                        + " artifact_id=?",
                sourceId);
        assertEquals(1, store.cleanTenant(tenant, 1));
        try (var downloaded = store.download(copiedId, "r_reused")) {
            assertEquals(
                    ((Number) copy.get("bytes")).longValue(),
                    downloaded.source().transferTo(OutputStream.nullOutputStream()));
        }
    }

    @Test
    void hardByteLimitFailureDeletesExactObject() {
        InputStream oversized =
                new InputStream() {
                    long left = ArtifactStore.MAX_ARTIFACT_BYTES + 1;

                    public int read() {
                        return left-- > 0 ? ' ' : -1;
                    }

                    public int read(byte[] b, int off, int len) {
                        if (left <= 0) return -1;
                        int n = (int) Math.min(left, len);
                        Arrays.fill(b, off, off + n, (byte) ' ');
                        left -= n;
                        return n;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> store.offload(new byte[0], oversized, Map.of(), call, "r_artifact"));
        assertDeletedObject();
    }

    void assertDeletedObject() {
        var row =
                db.queryForMap(
                        "select * from skill.artifact where tenant_id=? and call_id=?",
                        tenant,
                        call);
        assertEquals("DELETED", row.get("state"), Objects.toString(row.get("last_error_code")));
        assertNotNull(row.get("last_error_code"));
        var client =
                MinioClient.builder()
                        .endpoint(System.getenv("AGENT_MINIO_ENDPOINT"))
                        .credentials(
                                System.getenv("AGENT_MINIO_ACCESS_KEY"),
                                System.getenv("AGENT_MINIO_SECRET_KEY"))
                        .build();
        var error =
                assertThrows(
                        io.minio.errors.ErrorResponseException.class,
                        () ->
                                client.statObject(
                                        StatObjectArgs.builder()
                                                .bucket(row.get("bucket").toString())
                                                .object(row.get("object_key").toString())
                                                .build()));
        assertEquals("NoSuchKey", error.errorResponse().code());
    }

    @Test
    void retentionIsBoundedAndNeverDeletesAnotherTenantObject() throws Exception {
        var ref = upload(1024);
        String id = ref.get("artifactId").toString();
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where tenant_id=?",
                tenant);
        assertThrows(PlatformException.class, () -> store.download(id, "r_artifact"));
        assertEquals(0, store.cleanTenant("unrelated-tenant", 8));
        assertEquals(
                "READY",
                db.queryForObject(
                        "select state from skill.artifact where tenant_id=?",
                        String.class,
                        tenant));
        assertEquals(1, store.cleanTenant(tenant, 1));
        assertDeletedObject();
    }

    @Test
    void interruptedUploadIsRecoveredFromMetadataWithoutBucketListing() throws Exception {
        var ref = upload(128);
        db.update(
                "update skill.artifact set state='UPLOADING',cleanup_after=now()-interval '1"
                        + " second' where tenant_id=? and artifact_id=?",
                tenant,
                ref.get("artifactId"));
        var row =
                db.queryForMap(
                        "select bucket,object_key from skill.artifact where tenant_id=?", tenant);
        try (var client =
                MinioAsyncClient.builder()
                        .endpoint(System.getenv("AGENT_MINIO_ENDPOINT"))
                        .credentials(
                                System.getenv("AGENT_MINIO_ACCESS_KEY"),
                                System.getenv("AGENT_MINIO_SECRET_KEY"))
                        .build()) {
            String bucket = row.get("bucket").toString(), key = row.get("object_key").toString();
            String uploadId =
                    client.createMultipartUploadAsync(bucket, null, key, null, null)
                            .get()
                            .result()
                            .uploadId();
            assertEquals(1, store.cleanTenant(tenant, 1));
            assertDeletedObject();
            var error =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () ->
                                    client.listPartsAsync(
                                                    bucket, null, key, 1, 0, uploadId, null, null)
                                            .get());
            assertEquals(
                    "NoSuchUpload",
                    ((io.minio.errors.ErrorResponseException) error.getCause())
                            .errorResponse()
                            .code());
        }
    }

    @Test
    void responseStreamFailureCleansPartialUploadAndDoesNotIssueReference() {
        InputStream broken =
                new InputStream() {
                    long left = 6 * 1024 * 1024;

                    public int read() throws IOException {
                        if (left-- <= 0) throw new IOException("fixture interruption");
                        return ' ';
                    }

                    public int read(byte[] b, int off, int len) throws IOException {
                        if (left <= 0) throw new IOException("fixture interruption");
                        int n = (int) Math.min(left, len);
                        Arrays.fill(b, off, off + n, (byte) ' ');
                        left -= n;
                        return n;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.offload(
                                new byte[0], broken, Map.of("type", "object"), call, "r_artifact"));
        assertDeletedObject();
    }

    @Test
    void storageCleanupFailureIsRecordedAndLaterRetriedWithoutChangingOwnership() throws Exception {
        var ref = upload(1024);
        String id = ref.get("artifactId").toString();
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where tenant_id=?"
                        + " and artifact_id=?",
                tenant,
                id);
        var invalid = new HashMap<>(System.getenv());
        invalid.put("AGENT_MINIO_ACCESS_KEY", "artifact-test-invalid-key");
        var failed = new ArtifactStore(db, invalid);
        try {
            assertEquals(1, failed.cleanTenant(tenant, 1));
        } finally {
            failed.close();
        }
        var row =
                db.queryForMap(
                        "select state,last_error_code,cleanup_attempts,cleanup_after>now() as"
                            + " delayed from skill.artifact where tenant_id=? and artifact_id=?",
                        tenant,
                        id);
        assertEquals("CLEANUP_PENDING", row.get("state"));
        assertTrue(row.get("last_error_code").toString().startsWith("OBJECT_DELETE_FAILED_"));
        assertEquals(1, row.get("cleanup_attempts"));
        assertEquals(true, row.get("delayed"));
        db.update(
                "update skill.artifact set cleanup_after=now()-interval '1 second' where"
                        + " tenant_id=? and artifact_id=?",
                tenant,
                id);
        assertEquals(1, store.cleanTenant(tenant, 1));
        assertDeletedObject();
    }

    @Test
    void databaseOwnershipCannotBeRewrittenAndCleanupHonorsBatchMaximum() {
        var ref = upload(128);
        upload(128);
        upload(128);
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "update skill.artifact set principal_id='replacement' where"
                                        + " tenant_id=? and artifact_id=?",
                                tenant,
                                ref.get("artifactId")));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "update skill.artifact set object_key='different/key' where"
                                        + " tenant_id=? and artifact_id=?",
                                tenant,
                                ref.get("artifactId")));
        db.update(
                "update skill.artifact set expires_at=now()-interval '1 second' where tenant_id=?",
                tenant);
        assertEquals(1, store.cleanTenant(tenant, 1));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=? and state='DELETED'",
                        Integer.class,
                        tenant));
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from skill.artifact where tenant_id=? and state='READY'",
                        Integer.class,
                        tenant));
    }

    @Test
    void httpDownloadRoutesReturnBytesAndRejectSpoofedIdentityAndWrongRun() throws Exception {
        var ref = upload(1024);
        String id = ref.get("artifactId").toString();
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                new cn.agentplatform.skill.api.ArtifactController(store))
                        .setControllerAdvice(new PlatformErrors())
                        .build();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/admin/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Cache-Control", "private, no-store"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .contentType("application/json"));
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/runs/r_artifact/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isOk());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/v1/runs/r_wrong/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        identity(tenant, "other");
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                        "/admin/artifacts/" + id + "/download")
                                .header("X-User-Id", "owner")
                                .param("principalId", "owner"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isNotFound());
        SecurityContextHolder.clearContext();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/admin/artifacts/" + id + "/download"))
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                                .isUnauthorized());
    }
}
