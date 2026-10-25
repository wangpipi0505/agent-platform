package cn.agentplatform.skill.infra;

import cn.agentplatform.common.PlatformException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticates exact wire bytes before JSON parsing; disk and memory use have hard bounds. */
public final class SignedCallback implements AutoCloseable {
    private final Path file;
    public final String digest;
    public final long timestamp;

    private SignedCallback(Path file, String digest, long timestamp) {
        this.file = file;
        this.digest = digest;
        this.timestamp = timestamp;
    }

    public static SignedCallback read(
            InputStream source, String timestamp, String signature, String secret)
            throws IOException {
        long seconds;
        try {
            if (timestamp == null
                    || !timestamp.matches("[0-9]{1,12}")
                    || signature == null
                    || !signature.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException();
            seconds = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (seconds < now - 300 || seconds > now + 300) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new PlatformException(401, "CALLBACK_SIGNATURE_INVALID", "回调时间或签名无效");
        }
        Path path = Files.createTempFile("agent-callback-", ".json");
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "\n").getBytes(StandardCharsets.UTF_8));
            var hash = MessageDigest.getInstance("SHA-256");
            try (var output = Files.newOutputStream(path)) {
                byte[] buffer = new byte[8192];
                long count = 0;
                int n;
                while ((n = source.read(buffer)) != -1) {
                    count += n;
                    if (count > ArtifactStore.MAX_ARTIFACT_BYTES)
                        throw new PlatformException(413, "CALLBACK_TOO_LARGE", "回调超过64MiB硬上限");
                    mac.update(buffer, 0, n);
                    hash.update(buffer, 0, n);
                    output.write(buffer, 0, n);
                }
            }
            // OpenJDK21 MessageDigest.isEqual iterates over the expected digest; both lengths are
            // 32.
            if (!MessageDigest.isEqual(mac.doFinal(), HexFormat.of().parseHex(signature)))
                throw new PlatformException(401, "CALLBACK_SIGNATURE_INVALID", "回调签名无效");
            return new SignedCallback(path, HexFormat.of().formatHex(hash.digest()), seconds);
        } catch (Exception ex) {
            Files.deleteIfExists(path);
            if (ex instanceof PlatformException p) throw p;
            if (ex instanceof IOException io) throw io;
            throw new IOException("callback verification failed", ex);
        }
    }

    public InputStream open() throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(file);
    }
}
