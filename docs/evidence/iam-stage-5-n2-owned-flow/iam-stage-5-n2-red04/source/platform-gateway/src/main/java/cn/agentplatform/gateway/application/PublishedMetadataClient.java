package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
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

/** Fixed app destination only. This component is not yet wired into the gateway proxy. */
public final class PublishedMetadataClient implements AutoCloseable {
    private static final int MAX_BYTES=4096, DEADLINE_MS=2000;
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI origin;
    private final CurrentAuthorityClient authority;
    private final HttpClient http;
    private volatile boolean closed;
    public record PublishedVersion(String appCode,long versionId,int versionNo,String versionSha256) {
        public ResourceBinding resource(){return new ResourceBinding("app-version",appCode+"@"+versionNo,versionSha256);}
    }
    public PublishedMetadataClient(URI origin,CurrentAuthorityClient authority){this(origin,authority,false);}
    /** Explicit isolated tests only; production always uses HTTPS hostname/certificate verification. */
    public PublishedMetadataClient(URI origin,CurrentAuthorityClient authority,boolean testLoopback){
        if(origin==null||origin.getHost()==null||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null
                ||!(origin.getRawPath().isEmpty()||origin.getRawPath().equals("/"))
                ||!("https".equals(origin.getScheme())||(testLoopback&&"http".equals(origin.getScheme())&&"127.0.0.1".equals(origin.getHost()))))
            throw new IllegalArgumentException("TRUSTED_APP_ORIGIN_REQUIRED");
        this.origin=origin;this.authority=Objects.requireNonNull(authority);
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public PublishedVersion lookup(String sourceToken,SubjectKey verifiedSubject,String code){
        if(closed)throw unavailable();
        Objects.requireNonNull(verifiedSubject);var resource=MetadataSelectors.published(verifiedSubject.tenantId(),code);
        // Original authentication goes only to C. The app receives a separately targeted service ticket.
        var ticket=authority.exchange(sourceToken,new ServiceCall("app",MetadataSelectors.ACTION,resource));
        var operation=ticket.operation();
        if(operation==null||!verifiedSubject.equals(operation.subject())||!resource.equals(operation.resource())
                ||!"PORTAL".equals(operation.sourceKind())||!"PORTAL".equals(operation.originKind()))throw denied();
        var request=HttpRequest.newBuilder(origin.resolve("/internal/apps/"+code+"/published-metadata"))
                .timeout(Duration.ofMillis(DEADLINE_MS)).header("Accept","application/json")
                .header("Authorization","Bearer "+ticket.value()).GET().build();
        var body=new LimitedBody();
        var future=http.sendAsync(request,info->{
            if(info.statusCode()!=200||info.headers().firstValueAsLong("Content-Length").orElse(0)>MAX_BYTES)body.reject();
            return body;
        });
        try{return parse(future.get(DEADLINE_MS,TimeUnit.MILLISECONDS).body(),code);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|CancellationException e){throw unavailable();}
        finally{body.cancel();future.cancel(true);}
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
    // Same full-body completion/cancellation pattern as BoundedIamHttp, without authority credentials.
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
    @Override public void close(){closed=true;http.shutdownNow();}
}
