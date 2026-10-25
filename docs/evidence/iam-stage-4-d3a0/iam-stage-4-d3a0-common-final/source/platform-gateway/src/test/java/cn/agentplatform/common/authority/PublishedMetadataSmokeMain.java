package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import cn.agentplatform.gateway.application.PublishedMetadataClient;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real gateway client in its own JVM, connected to a separate app JVM only by HTTP. */
public final class PublishedMetadataSmokeMain {
    public static void main(String[] args)throws Exception{
        var paths=Json.read(Files.readString(Path.of(args[0])));String appClasspath=paths.path("app").textValue();
        var lines=new LinkedBlockingQueue<String>();var output=new StringBuilder();Process child=null;
        try(var fixture=new LoopbackAuthority();var gateway=fixture.client("gateway")){
            String tenant="d3a0-smoke-"+UUID.randomUUID();fixture.portalActor(new Actor(tenant,"alice",Set.of("EDITOR"),Map.of("region",Set.of("A"),"dataLabels",Set.of("restricted"))));
            child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Djdk.net.unixdomain.tmpdir=V:/.local/tmp","-cp",appClasspath,PublishedMetadataFixtureMainName.VALUE).directory(Path.of("..").toAbsolutePath().normalize().toFile()).redirectErrorStream(true).start();
            Process owned=child;Thread drain=Thread.ofPlatform().daemon(true).start(()->{try(var reader=owned.inputReader(StandardCharsets.UTF_8)){for(String line;(line=reader.readLine())!=null;){lines.add(line);synchronized(output){output.append(line).append('\n');}}}catch(IOException e){lines.add("CHILD_IO_FAILED");}});
            var stdin=new BufferedWriter(new OutputStreamWriter(child.getOutputStream(),StandardCharsets.UTF_8));
            stdin.write(Json.write(Map.of("trust",fixture.config("app"),"secret",fixture.testClientSecret("app"),"now",fixture.clock().instant().toString(),"tenant",tenant)));stdin.newLine();stdin.flush();
            int port=0;long deadline=System.nanoTime()+Duration.ofSeconds(25).toNanos();
            while(port==0&&System.nanoTime()<deadline){String line=lines.poll(1,TimeUnit.SECONDS);if(line!=null&&line.startsWith("D3A0_READY="))port=Json.read(line.substring("D3A0_READY=".length())).path("port").asInt();if(!child.isAlive()&&port==0)throw new AssertionError("app fixture exited before ready");}
            assertThat(port).isPositive();URI origin=URI.create("http://127.0.0.1:"+port);String source=fixture.portal();var subject=new SubjectKey(tenant,"alice");
            try(var client=new PublishedMetadataClient(origin,gateway,true);var appAuthority=fixture.client("app");var http=HttpClient.newHttpClient()){
                var version=client.lookup(source,subject,"Smoke");assertThat(version.resource()).isEqualTo(new ResourceBinding("app-version","Smoke@1",Digest.sha256("smoke-stored-candidate")));
                String queryRaw=fixture.requests.stream().filter(n->"authorize".equals(n.path("operation").asText())).findFirst().orElseThrow().path("service_ticket").asText();assertThat(queryRaw).isNotEqualTo(source);
                var query=new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published(tenant,"Smoke"));var proof=appAuthority.authorize(queryRaw,query);
                var main=new ServiceCall("app",new ServiceRoutePolicy().require("gateway","app","GET","/admin/apps/{code}").action(),version.resource());
                assertThatThrownBy(()->gateway.resume(proof.delegation(),main)).isInstanceOf(PlatformException.class);assertThatThrownBy(()->appAuthority.forward(proof,main)).isInstanceOf(PlatformException.class);
                var mainTicket=gateway.exchange(source,main);assertThat(mainTicket.operation().grantRef()).isNotEqualTo(proof.delegation().value());assertThat(mainTicket.operation().expiresAt()).isEqualTo(proof.operation().expiresAt());
                var misuse=gateway.exchange(source,new ServiceCall("app",main.action(),query.resource()));var denied=http.send(HttpRequest.newBuilder(origin.resolve("/admin/apps/Smoke")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+misuse.value()).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(denied.statusCode()).isEqualTo(403);
                fixture.currentRoles=Set.of();assertThatThrownBy(()->appAuthority.authorize(mainTicket.value(),main)).isInstanceOf(PlatformException.class);assertThatThrownBy(()->gateway.exchange(source,main)).isInstanceOf(PlatformException.class);
                fixture.currentRoles=Set.of("EDITOR");fixture.advanceClock(Duration.ofSeconds(41));assertThatThrownBy(()->gateway.exchange(source,main)).isInstanceOfAny(PlatformException.class,org.springframework.security.oauth2.jwt.JwtException.class);
            }
            stdin.write("STOP\n");stdin.flush();stdin.close();assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();drain.join(2000);assertThat(child.exitValue()).isZero();
            String captured; synchronized(output){captured=output.toString();}assertThat(captured).contains("D3A0_APP_RESULT=");
            System.out.print(captured);System.out.println("D3A0_GATEWAY_RESULT="+Json.write(Map.of("actualHttp",true,"distinctGrants",true,"queryRootChangeRejected",true,"payloadMisuse403",true,"sourceExpiryRejected",true,"currentNarrowingRejected",true,"appExit",child.exitValue())));
        }finally{if(child!=null&&child.isAlive()){child.destroy();if(!child.waitFor(3,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS);}}}
    }
    // A name string launches another JVM; no app business jar/classes appear in this JVM's classpath.
    private static final class PublishedMetadataFixtureMainName {static final String VALUE="cn.agentplatform.common.authority.PublishedMetadataFixtureMain";}
}
