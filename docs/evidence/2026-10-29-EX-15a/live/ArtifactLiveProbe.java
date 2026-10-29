import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.minio.MinioAsyncClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** EX-15a external probe: no application context, direct store call, or tenant-wide cleanup. */
public class ArtifactLiveProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SIZE = 64L * 1024 * 1024;
    private static final String TENANT = "local-v12", PRINCIPAL = "developer-v12";
    private static final URI BASE = URI.create("http://127.0.0.1:18080");
    private static Path directory;

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("upload", "restart", "cleanup").contains(args[0]))
            throw new IllegalArgumentException("EX15_ARGUMENTS");
        directory = Path.of(args[1]).toRealPath();
        require(directory.equals(Path.of(required("EX15_PRIVATE")).toRealPath()), "PRIVATE_PATH");
        String database = required("EX15_DATABASE");
        require(database.matches("agent_platform_ex15a_live_[0-9]{14}_[0-9a-f]{8}"), "DATABASE_NAME");
        require(required("AGENT_DB_URL").equals("jdbc:postgresql://127.0.0.1:25432/" + database), "DATABASE_URL");
        require(required("AGENT_DB_USER").equals("agent_v12"), "DATABASE_USER");
        try (Connection db = DriverManager.getConnection(required("AGENT_DB_URL"),
                required("AGENT_DB_USER"), required("AGENT_DB_PASSWORD"));
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                     .followRedirects(HttpClient.Redirect.NEVER).build()) {
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                    "select current_database(),current_user,pg_get_userbyid(datdba) from pg_database where datname=current_database()")) {
                require(r.next() && database.equals(r.getString(1)) && "agent_v12".equals(r.getString(2))
                        && "agent_v12".equals(r.getString(3)) && !r.next(), "DATABASE_OWNER");
            }
            ObjectNode result = switch (args[0]) {
                case "upload" -> upload(http, db);
                case "restart" -> restart(http, db);
                default -> cleanup(db);
            };
            result.put("phase", args[0]).put("passed", true);
            JSON.writeValue(directory.resolve(args[0] + ".safe.json").toFile(), result);
            System.out.println("EX15_PROBE_PASS " + args[0]);
        }
    }

    private static ObjectNode upload(HttpClient http, Connection db) throws Exception {
        require(rowCount(db) == 0, "UPLOAD_REQUIRES_EMPTY_ARTIFACT_TABLE");
        Path file = directory.resolve("payload.bin");
        require(!Files.exists(file), "PAYLOAD_ALREADY_EXISTS");
        byte[] block = new byte[8192];
        new SecureRandom().nextBytes(block);
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            for (long written = 0; written < SIZE; written += block.length) {
                out.write(block);
                expected.update(block);
            }
        }
        String sha = HexFormat.of().formatHex(expected.digest());
        String boundary = "ex15_" + UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"payload.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        String token = token(http);
        String[] segments = token.split("\\.", -1);
        require(segments.length == 3 && !segments[2].isEmpty(), "TOKEN_JWT_FORMAT");
        String invalidSignature = segments[0] + "." + segments[1] + "."
                + (segments[2].charAt(0) == 'A' ? 'B' : 'A') + segments[2].substring(1);
        rejectedUpload(http, db, boundary, prefix, suffix, null);
        rejectedUpload(http, db, boundary, prefix, suffix, invalidSignature);
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                HttpRequest.BodyPublishers.ofFile(file), HttpRequest.BodyPublishers.ofByteArray(suffix));
        require(body.contentLength() == SIZE + prefix.length + suffix.length, "MULTIPART_LENGTH");
        var response = http.send(HttpRequest.newBuilder(BASE.resolve("/admin/artifacts"))
                .timeout(Duration.ofSeconds(180)).header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", "ex15-forged-tenant").header("X-Principal-Id", "ex15-forged-principal")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(body).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        JsonNode raw = readJson(response, 200, "UPLOAD");
        // ArtifactController returns the raw Map. Only dev-token uses ApiResponse.data.
        require(!raw.has("data") && raw.path("artifactId").asText().matches("art_[0-9a-f]{32}"), "RAW_UPLOAD_MAP");
        String id = raw.path("artifactId").asText();
        require(raw.path("bytes").asLong(-1) == SIZE && sha.equals(raw.path("sha256").asText()), "UPLOAD_LENGTH_SHA");
        require(raw.path("downloadUrl").asText().equals("/admin/artifacts/" + id + "/download"), "DOWNLOAD_URL");
        require(raw.path("contentType").asText().equals("application/octet-stream"), "CONTENT_TYPE");
        ObjectNode record = row(db, id);
        require(rowCount(db) == 1, "ONLY_OWN_ARTIFACT_ROW");
        validateReady(record, id, sha);
        record.put("probeRun", required("EX15_RUN")).put("expectedSha256", sha);
        JSON.writeValue(directory.resolve("artifact.private.json").toFile(), record);
        JSON.writeValue(directory.resolve("upload-response.private.json").toFile(), raw);
        download(http, token, id, sha);
        return summary(id, sha).put("rawMap", true).put("pgReady", true).put("downloadMatched", true)
                .put("fixtureBufferBytes", block.length).put("multipartBytes", body.contentLength())
                .put("missingBearerHttp", 401).put("invalidSignatureHttp", 401).put("rejectedUploadsCreatedZeroRows", true)
                .put("forgedIdentityHeadersIgnored", true);
    }

    private static void rejectedUpload(HttpClient http, Connection db, String boundary, byte[] prefix,
            byte[] suffix, String badToken) throws Exception {
        var request = HttpRequest.newBuilder(BASE.resolve("/admin/artifacts"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(new byte[]{42}), HttpRequest.BodyPublishers.ofByteArray(suffix)));
        if (badToken != null) request.header("Authorization", "Bearer " + badToken);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            require(response.statusCode() == 401, badToken == null ? "MISSING_BEARER_401" : "INVALID_SIGNATURE_401");
            require(input.readNBytes(65537).length <= 65536, "AUTH_ERROR_BODY_BOUND");
        }
        require(rowCount(db) == 0, "UNAUTHENTICATED_UPLOAD_CREATED_ROW");
    }

    private static ObjectNode restart(HttpClient http, Connection db) throws Exception {
        ObjectNode saved = saved();
        String id = saved.path("artifact_id").asText(), sha = saved.path("expectedSha256").asText();
        ObjectNode current = row(db, id);
        sameIdentity(saved, current);
        validateReady(current, id, sha);
        require(rowCount(db) == 1, "RESTART_ONLY_OWN_ARTIFACT_ROW");
        download(http, token(http), id, sha);
        JSON.writeValue(directory.resolve("restart-row.private.json").toFile(), current);
        return summary(id, sha).put("sameArtifactId", true).put("pgIdentityUnchanged", true).put("downloadMatched", true);
    }

    private static ObjectNode cleanup(Connection db) throws Exception {
        ObjectNode saved = saved();
        String id = saved.path("artifact_id").asText(), sha = saved.path("expectedSha256").asText();
        ObjectNode current = row(db, id);
        sameIdentity(saved, current);
        validateReady(current, id, sha);
        require(rowCount(db) == 1, "CLEANUP_ONLY_OWN_ARTIFACT_ROW");
        // No cleanTenant, DELETE, table scan cleanup, or direct object removal. The real scheduler owns deletion.
        try (PreparedStatement s = db.prepareStatement("update skill.artifact set expires_at=now()-interval '1 second',"
                + "cleanup_after=now()-interval '1 second' where artifact_id=? and tenant_id=? and principal_id=?"
                + " and run_id=? and call_id=? and bucket=? and object_key=? and sha256=? and size_bytes=? and state='READY'")) {
            s.setString(1, id); s.setString(2, TENANT); s.setString(3, PRINCIPAL);
            s.setString(4, saved.path("run_id").asText()); s.setLong(5, 1L);
            s.setString(6, saved.path("bucket").asText()); s.setString(7, saved.path("object_key").asText());
            s.setString(8, sha); s.setLong(9, SIZE);
            require(s.executeUpdate() == 1, "EXPIRE_EXACT_ONE_ROW");
        }
        JSON.writeValue(directory.resolve("expired-row.private.json").toFile(), row(db, id));
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        do {
            current = row(db, id);
            sameIdentity(saved, current);
            if ("DELETED".equals(current.path("state").asText())) break;
            require(Set.of("READY", "CLEANUP_PENDING").contains(current.path("state").asText()), "CLEANUP_STATE");
            Thread.sleep(1000);
        } while (System.nanoTime() < deadline);
        require("DELETED".equals(current.path("state").asText()) && rowCount(db) == 1, "SCHEDULER_DELETED");
        require(current.path("deleted_at").isTextual() && current.path("cleanup_attempts").asInt() >= 1, "SCHEDULER_FACTS");
        URI endpoint = URI.create(required("AGENT_MINIO_ENDPOINT"));
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
                && endpoint.getPort() == 29000 && endpoint.getUserInfo() == null, "MINIO_ENDPOINT");
        try (MinioAsyncClient minio = MinioAsyncClient.builder().endpoint(endpoint.toString())
                .credentials(required("AGENT_MINIO_ACCESS_KEY"), required("AGENT_MINIO_SECRET_KEY")).build()) {
            try {
                minio.statObject(StatObjectArgs.builder().bucket(saved.path("bucket").asText())
                        .object(saved.path("object_key").asText()).build()).get(20, TimeUnit.SECONDS);
                throw new IllegalStateException("EX15_OBJECT_STILL_EXISTS");
            } catch (ExecutionException e) {
                // This precise 8.5.17 chain is proven by EX15a upload-red-v3 and upload-green.
                require(e.getCause() instanceof CompletionException, "MINIO_COMPLETION_WRAPPER");
                require(e.getCause().getCause() instanceof ErrorResponseException, "MINIO_ERROR_RESPONSE");
                ErrorResponseException missing = (ErrorResponseException)e.getCause().getCause();
                require("NoSuchKey".equals(missing.errorResponse().code()), "MINIO_EXACT_NO_SUCH_KEY");
            }
        }
        JSON.writeValue(directory.resolve("deleted-row.private.json").toFile(), current);
        return summary(id, sha).put("expiredExactlyOne", true).put("schedulerDeleted", true).put("exactNoSuchKey", true);
    }

    private static String token(HttpClient http) throws Exception {
        var response = http.send(HttpRequest.newBuilder(BASE.resolve("/auth/dev-token"))
                .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        JsonNode data = readJson(response, 200, "TOKEN").path("data");
        require(TENANT.equals(data.path("user").path("tenantId").asText())
                && PRINCIPAL.equals(data.path("user").path("principalId").asText()), "TOKEN_IDENTITY");
        String token = data.path("accessToken").asText();
        require(!token.isBlank(), "TOKEN_ABSENT");
        return token; // Never write the response or token to disk or stdout.
    }

    private static void download(HttpClient http, String token, String id, String sha) throws Exception {
        var response = http.send(HttpRequest.newBuilder(BASE.resolve("/admin/artifacts/" + id + "/download"))
                .timeout(Duration.ofSeconds(180)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream input = response.body()) {
            require(response.statusCode() == 200, "DOWNLOAD_HTTP");
            require(response.headers().firstValue("Content-Type").orElse("").equals("application/octet-stream"), "DOWNLOAD_CONTENT_TYPE");
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) {
                size += n; require(size <= SIZE, "DOWNLOAD_OVERSIZE"); digest.update(buffer, 0, n);
            }
        }
        require(size == SIZE && sha.equals(HexFormat.of().formatHex(digest.digest())), "DOWNLOAD_LENGTH_SHA");
    }

    private static JsonNode readJson(HttpResponse<InputStream> response, int status, String phase) throws Exception {
        try (InputStream input = response.body()) {
            require(response.statusCode() == status, phase + "_HTTP");
            byte[] bytes = input.readNBytes(65537);
            require(bytes.length <= 65536, phase + "_JSON_SIZE");
            return JSON.readTree(bytes);
        }
    }
    private static long rowCount(Connection db) throws Exception {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("select count(*) from skill.artifact")) { r.next(); return r.getLong(1); }
    }
    private static ObjectNode row(Connection db, String id) throws Exception {
        require(id.matches("art_[0-9a-f]{32}"), "ARTIFACT_ID");
        try (PreparedStatement s = db.prepareStatement("select artifact_id,tenant_id,principal_id,run_id,call_id,bucket,object_key,"
                + "state,sha256,size_bytes,content_type,expires_at,cleanup_after,cleanup_attempts,deleted_at from skill.artifact where artifact_id=?")) {
            s.setString(1, id);
            try (ResultSet r = s.executeQuery()) {
                require(r.next(), "ROW_ABSENT");
                ObjectNode row = JSON.createObjectNode();
                for (int n = 1; n <= r.getMetaData().getColumnCount(); n++) row.put(r.getMetaData().getColumnLabel(n), r.getString(n));
                require(!r.next(), "ROW_DUPLICATE"); return row;
            }
        }
    }
    private static void validateReady(ObjectNode r, String id, String sha) throws Exception {
        require(id.equals(r.path("artifact_id").asText()) && TENANT.equals(r.path("tenant_id").asText())
                && PRINCIPAL.equals(r.path("principal_id").asText()) && r.path("run_id").asText().matches("r_upload_[0-9a-f]{32}")
                && r.path("call_id").asLong() == 1 && r.path("size_bytes").asLong() == SIZE && sha.equals(r.path("sha256").asText())
                && "READY".equals(r.path("state").asText()) && "application/octet-stream".equals(r.path("content_type").asText())
                && required("AGENT_MINIO_BUCKET").equals(r.path("bucket").asText()), "READY_ROW");
        String prefix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TENANT.getBytes(StandardCharsets.UTF_8)));
        require((prefix + "/artifacts/" + id + ".bin").equals(r.path("object_key").asText()), "OWN_OBJECT_KEY");
    }
    private static void sameIdentity(ObjectNode a, ObjectNode b) {
        for (String key : List.of("artifact_id", "tenant_id", "principal_id", "run_id", "call_id", "bucket", "object_key", "sha256", "size_bytes", "content_type"))
            require(a.path(key).equals(b.path(key)), "IDENTITY_CHANGED_" + key);
    }
    private static ObjectNode saved() throws Exception {
        ObjectNode r = (ObjectNode)JSON.readTree(directory.resolve("artifact.private.json").toFile());
        require(required("EX15_RUN").equals(r.path("probeRun").asText()), "PROBE_RUN_MISMATCH"); return r;
    }
    private static ObjectNode summary(String id, String sha) throws Exception {
        return JSON.createObjectNode().put("artifactIdSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(id.getBytes(StandardCharsets.US_ASCII)))).put("bytes", SIZE).put("sha256", sha);
    }
    private static String required(String key) { String v = System.getenv(key); require(v != null && !v.isBlank(), "ENV_" + key); return v; }
    private static void require(boolean pass, String code) { if (!pass) throw new IllegalStateException("EX15_" + code); }
}
