package cn.agentplatform.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

/**
 * secret_ref的开发环境实现：新工程专用目录+独立主密钥+AES-GCM认证加密。
 * 生产部署可使用env:AGENT_SECRET_*接入外部Secret；禁止把明文密钥写业务表/日志/prompt。
 */
public final class SecretVault {
    private SecretVault() {}

    private static String setting(String env, String property) {
        String override = System.getProperty(property, "");
        if (!override.isBlank()) return override;
        return Objects.toString(System.getenv(env), "");
    }

    private static Path directory() {
        String configured = setting("AGENT_SECRET_DIR", "agent.secret.dir");
        if (configured.isBlank()) throw new IllegalStateException("必须显式配置本工程独立密钥目录");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private static byte[] key() throws Exception {
        String key = setting("AGENT_MASTER_KEY", "agent.master.key");
        if (key.length() < 32) throw new IllegalStateException("必须配置独立的至少32字符主密钥");
        return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    }

    public static String put(String secret) {
        return put(secret, Actor.current());
    }

    public static String put(String secret, Actor actor) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("密钥不能为空");
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] encrypted =
                    cipher.doFinal(
                            Json.write(Map.of("tenantId", actor.tenantId(), "value", secret))
                                    .getBytes(StandardCharsets.UTF_8));
            String id = UUID.randomUUID().toString();
            Path dir = directory();
            Files.createDirectories(dir);
            // 每条密钥独立文件且不覆盖，GCM校验可检测篡改；目录必须由部署方赋予服务账户最小权限。
            byte[] record = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, record, 0, nonce.length);
            System.arraycopy(encrypted, 0, record, nonce.length, encrypted.length);
            Files.write(
                    dir.resolve(id + ".secret"),
                    record,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return "vault:" + id;
        } catch (Exception e) {
            throw new IllegalStateException("密钥保存失败", e);
        }
    }

    public static String resolve(String reference) {
        return resolve(reference, Actor.current());
    }

    public static String resolve(String reference, Actor actor) {
        if (reference == null) throw new IllegalArgumentException("secret_ref不能为空");
        if (reference.startsWith("env:AGENT_SECRET_")) {
            String name = reference.substring(4);
            if (!name.matches("AGENT_SECRET_[A-Z0-9_]+"))
                throw new IllegalArgumentException("密钥引用不合法");
            // env引用也需要权威租户绑定；知道环境变量名不等于有权使用该凭据。
            String bindings = Objects.toString(System.getenv("AGENT_SECRET_BINDINGS"), "{}");
            if (!actor.tenantId().equals(Json.read(bindings).path(name).asText()))
                throw new PlatformException(403, "SECRET_SCOPE_DENIED", "凭据引用不属于当前租户");
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("密钥引用未配置");
            return value;
        }
        if (!reference.startsWith("vault:")) throw new IllegalArgumentException("不支持的密钥引用");
        String id = UUID.fromString(reference.substring(6)).toString();
        try {
            byte[] record = Files.readAllBytes(directory().resolve(id + ".secret"));
            if (record.length < 29) throw new IllegalStateException("密钥记录损坏");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(record, 0, 12)));
            var payload =
                    Json.read(
                            new String(
                                    cipher.doFinal(Arrays.copyOfRange(record, 12, record.length)),
                                    StandardCharsets.UTF_8));
            if (!actor.tenantId().equals(payload.path("tenantId").asText()))
                throw new PlatformException(403, "SECRET_SCOPE_DENIED", "凭据引用不属于当前租户");
            return payload.path("value").asText();
        } catch (PlatformException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("密钥读取或认证失败", e);
        }
    }
}
