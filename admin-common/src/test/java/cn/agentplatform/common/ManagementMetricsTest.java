package cn.agentplatform.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementMetricsTest {
    @Test
    void convertsLinuxResidentMemoryToBytes(@TempDir Path directory) throws IOException {
        var status = directory.resolve("status");
        Files.writeString(status, "VmSize:\t82340 kB\nVmRSS:\t20360 kB\n");
        assertThat(ProcessResidentMemoryMetrics.residentBytes(status)).isEqualTo(20360 * 1024.0);
        assertThat(ProcessResidentMemoryMetrics.residentBytes(directory.resolve("missing"))).isNaN();
    }

    @Test
    void opensOnlyPrometheusOnItsDedicatedLoopbackListener() {
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setLocalPort(18180);
        request.setLocalAddr("127.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        assertThat(SecurityConfiguration.managementScrape(request, 18180, 18090)).isTrue();
        assertThat(SecurityConfiguration.managementScrape(request, 18180, 18180)).isFalse();
        assertThat(SecurityConfiguration.managementScrape(request, null, 18090)).isFalse();

        request.setRemoteAddr("192.0.2.1");
        assertThat(SecurityConfiguration.managementScrape(request, 18180, 18090)).isFalse();
        request.setRemoteAddr("127.0.0.1");
        request.setMethod("POST");
        assertThat(SecurityConfiguration.managementScrape(request, 18180, 18090)).isFalse();
        request.setMethod("GET");
        request.setRequestURI("/actuator/env");
        assertThat(SecurityConfiguration.managementScrape(request, 18180, 18090)).isFalse();
    }
}
