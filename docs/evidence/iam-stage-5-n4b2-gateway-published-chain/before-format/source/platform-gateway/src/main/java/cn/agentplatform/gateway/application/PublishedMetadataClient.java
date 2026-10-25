package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.gateway.infra.PublishedRunHttp;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Strict metadata query at the fixed app origin; its grant is never reused for the main run. */
public final class PublishedMetadataClient implements AutoCloseable {
    private static final int MAX_BYTES=4096, DEADLINE_MS=2000;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI origin;
    private final CurrentAuthorityClient authority;
    private final PublishedRunHttp http;
    private final boolean ownsHttp;
    private volatile boolean closed;
    public record PublishedVersion(String appCode,long versionId,int versionNo,String versionSha256) {
        public ResourceBinding resource(){return new ResourceBinding("app-version",appCode+"@"+versionNo,versionSha256);}
    }
    public PublishedMetadataClient(URI origin,CurrentAuthorityClient authority){this(origin,authority,false);}
    /** Explicit isolated tests only; production always uses HTTPS hostname/certificate verification. */
    public PublishedMetadataClient(URI origin,CurrentAuthorityClient authority,boolean testLoopback){
        this(origin,authority,ownedTransport(origin,testLoopback),testLoopback,true);
    }
    private static PublishedRunHttp ownedTransport(URI origin,boolean loopback){PublishedRunHttp.origin(origin,loopback);return new PublishedRunHttp();}
    public PublishedMetadataClient(URI origin,CurrentAuthorityClient authority,PublishedRunHttp http,boolean testLoopback){this(origin,authority,http,testLoopback,false);}
    private PublishedMetadataClient(URI origin,CurrentAuthorityClient authority,PublishedRunHttp http,boolean testLoopback,boolean ownsHttp){
        this.origin=PublishedRunHttp.origin(origin,testLoopback);this.authority=Objects.requireNonNull(authority);this.http=Objects.requireNonNull(http);this.ownsHttp=ownsHttp;
    }
    public PublishedVersion lookup(String sourceToken,SubjectKey verifiedSubject,String code){
        return lookup(sourceToken,verifiedSubject,code,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS),new PublishedRunHttp.Cancellation());
    }
    public PublishedVersion lookup(String sourceToken,SubjectKey verifiedSubject,String code,long deadline,PublishedRunHttp.Cancellation cancellation){
        if(closed)throw unavailable();
        Objects.requireNonNull(verifiedSubject);var resource=MetadataSelectors.published(verifiedSubject.tenantId(),code);
        // Original authentication goes only to C. The app receives a separately targeted service ticket.
        var ticket=authority.exchange(sourceToken,new ServiceCall("app",MetadataSelectors.ACTION,resource));
        var operation=ticket.operation();
        if(operation==null||!verifiedSubject.equals(operation.subject())||!resource.equals(operation.resource())
                ||!"PORTAL".equals(operation.sourceKind())||!"PORTAL".equals(operation.originKind()))throw denied();
        var response=http.get(origin.resolve("/internal/apps/"+code+"/published-metadata"),ticket.value(),MAX_BYTES,deadline,cancellation,()->{if(closed)throw unavailable();});
        return parse(response.body(),code);
    }
    private static PublishedVersion parse(byte[] bytes,String expectedCode){
        try{
            var root=JSON.readTree(bytes);
            if(root==null||!root.isObject()||root.size()!=4||!root.has("code")||!root.has("message")||!root.has("data")||!root.has("traceId")
                    ||!root.path("code").isTextual()||!"OK".equals(root.path("code").textValue())||!root.path("message").isTextual()
                    ||!(root.path("traceId").isNull()||root.path("traceId").isTextual()))throw unavailable();
            var data=root.path("data");
            if(!data.isObject()||data.size()!=4||!data.has("appCode")||!data.has("versionId")||!data.has("versionNo")||!data.has("versionSha256")
                    ||!data.path("appCode").isTextual()||!expectedCode.equals(data.path("appCode").textValue())
                    ||!data.path("versionId").isIntegralNumber()||!data.path("versionId").canConvertToLong()||data.path("versionId").longValue()<1
                    ||!data.path("versionNo").isIntegralNumber()||!data.path("versionNo").canConvertToInt()||data.path("versionNo").intValue()<1
                    ||!data.path("versionSha256").isTextual()||!data.path("versionSha256").textValue().matches("[a-f0-9]{64}"))throw unavailable();
            return new PublishedVersion(data.path("appCode").asText(),data.path("versionId").asLong(),data.path("versionNo").asInt(),data.path("versionSha256").asText());
        }catch(Exception error){throw unavailable();}
    }
    private static PlatformException unavailable(){return new PlatformException(503,"APP_METADATA_UNAVAILABLE","应用元数据暂不可用");}
    private static PlatformException denied(){return new PlatformException(403,"SYS-AUTH-403","当前授权不允许查询应用元数据");}
    // Retained bounded Flow subscriber contract for existing callers/tests. The actual HTTP path
    // above is Apache and is verified separately with its real cancellation handle and worker exit.
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> done=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return done;}
        public synchronized void onSubscribe(Flow.Subscription s){subscription=s;if(done.isDone())s.cancel();else s.request(1);}
        public synchronized void onNext(List<ByteBuffer> buffers){
            if(done.isDone())return;
            for(var b:buffers){if(b.remaining()>MAX_BYTES-bytes.size()){reject();return;}byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}
            subscription.request(1);
        }
        public void onError(Throwable error){done.completeExceptionally(unavailable());}
        public synchronized void onComplete(){done.complete(bytes.toByteArray());}
        synchronized void reject(){done.completeExceptionally(unavailable());cancel();}
        synchronized void cancel(){if(subscription!=null)subscription.cancel();if(!done.isDone())done.completeExceptionally(unavailable());}
    }
    @Override public void close(){closed=true;if(ownsHttp)http.close();}
}
