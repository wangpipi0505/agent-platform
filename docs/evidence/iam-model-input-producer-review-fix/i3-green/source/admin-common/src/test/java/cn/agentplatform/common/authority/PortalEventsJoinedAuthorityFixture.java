package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Finite network issuer bridge. Secrets cross private stdin only; this is not a production IAM. */
public final class PortalEventsJoinedAuthorityFixture implements AutoCloseable {
    public static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("o2a-b-candidate"));
    public final LoopbackAuthority iam=new LoopbackAuthority();
    public PortalEventsJoinedAuthorityFixture()throws Exception { actor("tenant-a","alice"); }
    public void actor(String tenant,String principal){iam.portalActor(new Actor(tenant,principal,Set.of("EDITOR"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial"))));}
    public Map<String,Object> privateConfig(String service){
        if(!Set.of("gateway","engine").contains(service))throw new IllegalArgumentException("FINITE_SERVICE");
        return Map.of("authority",iam.config(service),"secret",iam.secrets.get(service),"epoch",iam.clock().instant().getEpochSecond());
    }
    public static HttpCurrentAuthorityClient client(AuthorityProperties p,String secret,Clock clock){return new HttpCurrentAuthorityClient(p,clock,new WorkloadCredentialResolver(p,k->secret,true),true);}
    public String admission(){try(var c=iam.client("gateway")){return c.exchange(iam.portal(120),new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT)).value();}}
    public static <T>T admitted(HttpCurrentAuthorityClient engine,String ticket,java.util.function.Function<HttpCurrentAuthorityClient.RetainedAuthorization,T> work){
        var call=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);var proof=engine.authorizeRetained(ticket,call);
        try(var frame=AuthorityContext.bindRetained(proof,()->engine.authorizeRetained(ticket,call))){return work.apply(proof);}
    }
    public String token(){return iam.portal(120);}
    public void register(String run,String sha){iam.registerObservation(new SubjectKey("tenant-a","alice"),run,sha);}
    public void narrow(boolean on){iam.eventsCurrentAttributes=on?Map.of("region",Set.of("A"),"dataLabels",Set.of("initial")):null;}
    public void revoke(boolean on){iam.gatewayEventsRevoked=on;}
    public Map<String,Object> observations(){return Map.of("calls",iam.authorityCalls(),"attempts",iam.requestAttempts(),"operations",iam.observedOperations());}
    /** Current thread metadata only: finite stacks, no locals, exception graphs, tokens or payloads. */
    public static List<Map<String,Object>> boundedThreads(){
        var result=new ArrayList<Map<String,Object>>();var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        for(var thread:bean.getThreadInfo(bean.getAllThreadIds(),24)){
            if(thread==null||result.size()>=32)continue;String name=thread.getThreadName();
            if(!(name.contains("portal-events")||name.startsWith("http-nio")||name.startsWith("https-jsse")))continue;
            var row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("id",thread.getThreadId());row.put("state",thread.getThreadState().name());row.put("lock",Objects.toString(thread.getLockName(),""));row.put("lockOwner",Objects.toString(thread.getLockOwnerName(),""));row.put("stack",Arrays.stream(thread.getStackTrace()).map(StackTraceElement::toString).toList());result.add(row);
        }return result;
    }
    public static final class TestClock extends Clock {
        private final AtomicLong epoch;
        public TestClock(long initial){epoch=new AtomicLong(initial);}
        public void set(long value){if(value<epoch.get())throw new IllegalArgumentException("CLOCK_BACKWARDS");epoch.set(value);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){if(!ZoneOffset.UTC.equals(z))throw new IllegalArgumentException();return this;}
        public Instant instant(){return Instant.ofEpochSecond(epoch.get());}
    }
    public void close(){iam.close();}
}
