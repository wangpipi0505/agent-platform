package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.infra.PublishedRunHttp;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** One synchronous Q/M operation; full validation precedes any successful browser business byte. */
public final class RunStatusProxy implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final URI origin;private final RunObservationMetadataClient metadata;private final CurrentAuthorityClient authority;private final PublishedRunHttp http;
    private final Set<PublishedRunHttp.Cancellation> active=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    public RunStatusProxy(URI origin,RunObservationMetadataClient metadata,CurrentAuthorityClient authority,PublishedRunHttp http){this(origin,metadata,authority,http,false);}
    public RunStatusProxy(URI origin,RunObservationMetadataClient metadata,CurrentAuthorityClient authority,PublishedRunHttp http,boolean testLoopback){this.origin=PublishedRunHttp.origin(origin,testLoopback);this.metadata=Objects.requireNonNull(metadata);this.authority=Objects.requireNonNull(authority);this.http=Objects.requireNonNull(http);}
    public void forward(HttpServletRequest request,HttpServletResponse response,GatewayIngressBoundary.PortalStatusRequest capture)throws IOException {
        final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);var cancellation=new PublishedRunHttp.Cancellation();active.add(cancellation);
        try{
            Runnable live=()->{if(closed||cancellation.cancelled()||deadline-System.nanoTime()<=0)throw unavailable();capture.requireLive(request);};live.run();
            var facts=metadata.lookup(capture,request,deadline,cancellation);live.run();
            var main=authority.exchangeGatewayRunStatus(capture.sourceToken(),facts.snapshot());live.run();facts.requireMain(main);
            Runnable send=()->{live.run();main.requireVerified();};
            byte[] bytes=http.getRunStatus(origin,capture.publicRunId(),main.ticket().value(),deadline,cancellation,send).body();
            validate(bytes,capture.publicRunId());send.run();
            response.setStatus(200);response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
            // The actual MVC stream acquisition is part of the window. Never unwrap its delegate.
            var output=response.getOutputStream();send.run();
            try{output.write(bytes);}catch(IOException failure){
                try{output.close();}catch(IOException closeFailure){failure.addSuppressed(closeFailure);}
                org.slf4j.LoggerFactory.getLogger(RunStatusProxy.class).warn("Run status output interrupted: committed={}, attemptedBytes={}, actualBytes=unknown",response.isCommitted(),bytes.length,failure);
                if(!response.isCommitted())throw failure;
            }
        }catch(PlatformException failure){
            // Return only owned finite messages; a valid engine size denial is not Portal expiry.
            if(failure.status()==401)throw new PlatformException(401,"PORTAL_TOKEN_INVALID","门户身份已失效");
            if(failure.status()==404)throw new PlatformException(404,"RUN_NOT_FOUND","运行不存在");
            if(failure.status()==403)throw new PlatformException(403,failure.code(),"RUN_STATUS_TOO_LARGE".equals(failure.code())?"运行状态超出范围":"当前授权不允许读取运行状态");
            throw unavailable();
        }finally{cancellation.cancel();active.remove(cancellation);}
    }
    private static void validate(byte[] bytes,String run){
        try{var root=RunObservationMetadataClient.readUtf8(JSON,bytes);RunObservationMetadataClient.requireEnvelope(root);var d=root.path("data");if(!d.isObject()||!d.path("runId").isTextual()||!run.equals(d.path("runId").textValue()))throw unavailable();}
        catch(Exception invalid){throw unavailable();}
    }
    private static PlatformException unavailable(){return new PlatformException(503,"RUN_STATUS_UNAVAILABLE","运行状态暂不可用");}
    @Override public void close(){closed=true;active.forEach(PublishedRunHttp.Cancellation::cancel);}
}
