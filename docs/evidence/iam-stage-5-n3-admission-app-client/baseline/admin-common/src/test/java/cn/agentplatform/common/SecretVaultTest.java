package cn.agentplatform.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.UUID;

class SecretVaultTest {
    @Test
    void encryptsAtRestAndRejectsEscapedReferences() throws Exception {
        Path dir = Path.of(".local", "vault-test-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(dir);
        System.setProperty("agent.secret.dir", dir.toString());
        System.setProperty("agent.master.key", "TEST-ONLY-independent-encryption-master-key-12345");
        Actor owner = new Actor("vault-a", "alice", java.util.Set.of("ADMIN"), java.util.Map.of());
        String ref = SecretVault.put("sensitive-value", owner);
        assertThat(SecretVault.resolve(ref, owner)).isEqualTo("sensitive-value");
        Actor stranger =
                new Actor("vault-b", "admin", java.util.Set.of("ADMIN"), java.util.Map.of());
        assertThatThrownBy(() -> SecretVault.resolve(ref, stranger))
                .isInstanceOf(PlatformException.class);
        try (var files = Files.list(dir)) {
            assertThat(new String(Files.readAllBytes(files.findFirst().orElseThrow())))
                    .doesNotContain("sensitive-value");
        }
        assertThatThrownBy(() -> SecretVault.resolve("vault:../../escape", owner))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
