package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.PlatformUserDirectory;
import cn.agentplatform.common.PlatformException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "AGENT_R39_DIRECTORY_TEST_KEY", matches = ".+")
class PlatformUserDirectoryTest {
    @Test
    void realDirectorySearchAndNamedLookupStayInTheConfiguredTenant() {
        var directory = new PlatformUserDirectory(
                "http://127.0.0.1:18072", "tenant-a", "env:AGENT_R39_DIRECTORY_TEST_KEY");

        assertThat(directory.search("tenant-a", "operatorA"))
                .anySatisfy(user -> {
                    assertThat(user.subject()).isEqualTo("operatorA");
                    assertThat(user.active()).isTrue();
                });
        directory.requireActive("tenant-a", "operatorA");
        assertThatThrownBy(() -> directory.search("tenant-b", "operatorA"))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(503));
        assertThatThrownBy(() -> directory.requireActive("tenant-a", "r39-unknown-user"))
                .isInstanceOfSatisfying(PlatformException.class, error -> assertThat(error.status()).isEqualTo(409));
    }
}
