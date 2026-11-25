package cn.agentplatform.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
class ProcessResidentMemoryMetrics {
    private static final Path PROCESS_STATUS = Path.of("/proc/self/status");

    @Bean
    MeterBinder processResidentMemory() {
        return registry -> {
            if (Files.isReadable(PROCESS_STATUS)) {
                Gauge.builder("process.resident.memory", PROCESS_STATUS, ProcessResidentMemoryMetrics::residentBytes)
                        .baseUnit("bytes")
                        .description("Resident memory of the Java process from Linux VmRSS")
                        .register(registry);
            }
        };
    }

    static double residentBytes(Path status) {
        try (var lines = Files.lines(status)) {
            return lines.filter(line -> line.startsWith("VmRSS:"))
                    .findFirst()
                    .map(line -> {
                        var fields = line.substring("VmRSS:".length()).trim().split("\\s+");
                        if (fields.length != 2 || !"kB".equals(fields[1])) return Double.NaN;
                        return (double) Math.multiplyExact(Long.parseLong(fields[0]), 1024L);
                    })
                    .orElse(Double.NaN);
        } catch (IOException | java.io.UncheckedIOException | NumberFormatException | ArithmeticException unavailable) {
            return Double.NaN;
        }
    }
}
