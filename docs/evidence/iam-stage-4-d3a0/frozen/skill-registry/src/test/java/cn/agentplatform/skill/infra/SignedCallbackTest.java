package cn.agentplatform.skill.infra;

import static org.junit.jupiter.api.Assertions.*;

import cn.agentplatform.common.PlatformException;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class SignedCallbackTest {
    private final String secret = "signed-callback-test-only-key";

    private String sign(String timestamp, byte[] body) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + "\n").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void exactUtf8BytesAreVerifiedAndPreservedIncludingWhitespace() throws Exception {
        byte[] body = "{\"value\":\"中文\"}\n".getBytes(StandardCharsets.UTF_8);
        String timestamp = "" + Instant.now().getEpochSecond();
        try (var verified =
                        SignedCallback.read(
                                new ByteArrayInputStream(body),
                                timestamp,
                                sign(timestamp, body),
                                secret);
                var input = verified.open()) {
            assertArrayEquals(body, input.readAllBytes());
            assertEquals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),
                    verified.digest);
        }
    }

    @Test
    void excessiveBodyStopsAtHardByteLimitWithoutReadingTheRest() {
        var source =
                new InputStream() {
                    long count;

                    public int read() {
                        count++;
                        return 'x';
                    }

                    public int read(byte[] b, int off, int len) {
                        java.util.Arrays.fill(b, off, off + len, (byte) 'x');
                        count += len;
                        return len;
                    }
                };
        var error =
                assertThrows(
                        PlatformException.class,
                        () ->
                                SignedCallback.read(
                                        source,
                                        "" + Instant.now().getEpochSecond(),
                                        "00".repeat(32),
                                        secret));
        assertEquals(413, error.status());
        assertTrue(source.count <= ArtifactStore.MAX_ARTIFACT_BYTES + 8192);
        assertTrue(source.count > ArtifactStore.MAX_ARTIFACT_BYTES);
    }

    @Test
    void invalidTimestampIsRejectedBeforeReadingAnyBody() {
        var unread =
                new InputStream() {
                    public int read() {
                        fail("invalid timestamp must not read body");
                        return -1;
                    }
                };
        for (String time : new String[] {"9223372036854775807", "-1", "NaN", ""})
            assertEquals(
                    401,
                    assertThrows(
                                    PlatformException.class,
                                    () ->
                                            SignedCallback.read(
                                                    unread, time, "00".repeat(32), secret))
                            .status());
    }
}
