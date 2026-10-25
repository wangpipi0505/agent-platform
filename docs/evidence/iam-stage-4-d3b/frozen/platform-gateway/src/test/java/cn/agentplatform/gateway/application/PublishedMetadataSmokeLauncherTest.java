package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Execution shell only; its single JUnit result is reported separately from component assertions. */
class PublishedMetadataSmokeLauncherTest {
    @Test void launchTwoIsolatedJvmFixturesWithFiniteDeadline()throws Exception{
        Path root=Path.of("..").toAbsolutePath().normalize(),evidence=root.resolve("docs/evidence/iam-stage-4-d3a0"),cpFile=evidence.resolve("smoke-classpaths.json");
        assertThat(cpFile).exists();String gateway=Json.read(Files.readString(cpFile)).path("gateway").textValue();assertThat(gateway).isNotBlank();
        String runId=UUID.randomUUID().toString();Path raw=evidence.resolve("smoke-process-"+runId+".log");assertThat(raw).doesNotExist();
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-cp",gateway,"cn.agentplatform.common.authority.PublishedMetadataSmokeMain",cpFile.toString()).directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).redirectOutput(raw.toFile()).start();
        try{
            assertThat(child.waitFor(50,TimeUnit.SECONDS)).as("finite smoke process deadline").isTrue();assertThat(child.exitValue()).as(Files.readString(raw)).isZero();
            var lines=Files.readAllLines(raw);var app=lines.stream().filter(x->x.startsWith("D3A0_APP_RESULT=")).findFirst().orElseThrow();var result=lines.stream().filter(x->x.startsWith("D3A0_GATEWAY_RESULT=")).findFirst().orElseThrow();
            Files.writeString(evidence.resolve("smoke-result-"+runId+".json"),Json.write(Map.of("app",Json.read(app.substring(app.indexOf('=')+1)),"gateway",Json.read(result.substring(result.indexOf('=')+1)),"launcherExit",child.exitValue(),"rawLog",raw.getFileName().toString(),"ownedGatewayPid",child.pid(),"ownedGatewayExited",!child.isAlive()))+"\n");
        }finally{if(child.isAlive()){child.destroy();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS);}}}
    }
}
