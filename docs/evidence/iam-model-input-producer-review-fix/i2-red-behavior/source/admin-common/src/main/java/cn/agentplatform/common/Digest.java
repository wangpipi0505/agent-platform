package cn.agentplatform.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class Digest {
    private Digest() {}

    /** 候选/配置/调用摘要均使用UTF-8 SHA-256；逻辑调用身份与参数摘要不得混为一物。 */
    public static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
