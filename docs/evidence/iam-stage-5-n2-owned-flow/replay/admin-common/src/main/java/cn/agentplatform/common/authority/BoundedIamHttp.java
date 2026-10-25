package cn.agentplatform.common.authority;

import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Fixed deployment endpoints only. No discovery, redirects, retries, cookies or response cache. */
public final class BoundedIamHttp implements AutoCloseable {
    public static final int MAX_BYTES=64*1024;
    private final Set<URI> gets, posts;
    private final int deadlineMs;
    private final HttpClient client;
    public BoundedIamHttp(AuthorityProperties config, boolean testProfile) {
        config.validate(testProfile);
        var a=Objects.requireNonNull(config.authority(),"IAM_CONFIG_REQUIRED");
        var allowed=new HashSet<URI>();allowed.add(URI.create(a.jwksUri()));
        if(config.portal()!=null && config.portal().enabled())allowed.add(URI.create(config.portal().jwksUri()));
        gets=Set.copyOf(allowed);posts=Set.of(URI.create(a.tokenUri()),URI.create(a.decisionUri()));
        deadlineMs=a.readTimeoutMs();
        client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(a.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public byte[] get(URI uri){return request(uri,"GET",null,null,gets);}
    public byte[] postForm(URI uri,Map<String,String> fields){
        var encoded=new ArrayList<String>();
        fields.forEach((k,v)->encoded.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));
        return request(uri,"POST",String.join("&",encoded).getBytes(StandardCharsets.UTF_8),"application/x-www-form-urlencoded",posts);
    }
    public byte[] postJson(URI uri,Object value){return request(uri,"POST",Json.write(value).getBytes(StandardCharsets.UTF_8),"application/json",posts);}
    /** Only the already validated token/decision destinations may receive workload credentials. */
    public byte[] postJsonAuthenticated(URI uri,Object value,String authorization){
        if(authorization==null || !authorization.startsWith("Basic ") || authorization.indexOf('\r')>=0 || authorization.indexOf('\n')>=0)
            throw new IllegalArgumentException("IAM_CLIENT_AUTH_INVALID");
        return request(uri,"POST",Json.write(value).getBytes(StandardCharsets.UTF_8),"application/json",posts,authorization);
    }
    private byte[] request(URI uri,String method,byte[] body,String contentType,Set<URI> allowed){
        return request(uri,method,body,contentType,allowed,null);
    }
    private byte[] request(URI uri,String method,byte[] body,String contentType,Set<URI> allowed,String authorization){
        if(!allowed.contains(uri))throw new IllegalArgumentException("IAM_ENDPOINT_NOT_CONFIGURED");
        if(body!=null && body.length>MAX_BYTES)throw new IllegalArgumentException("IAM_REQUEST_TOO_LARGE");
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(deadlineMs)).header("Accept","application/json");
        if(contentType!=null)builder.header("Content-Type",contentType);
        if(authorization!=null)builder.header("Authorization",authorization);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var subscriber=new LimitedBody();
        var future=client.sendAsync(builder.build(),info->{
            if(info.statusCode()!=200 || info.headers().firstValueAsLong("Content-Length").orElse(0)>MAX_BYTES)subscriber.reject();
            return subscriber;
        });
        try {return future.get(deadlineMs,TimeUnit.MILLISECONDS).body();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|CancellationException e){throw unavailable();}
        finally{subscriber.cancel();future.cancel(true);}
    }
    static PlatformException unavailable(){return new PlatformException(503,"IAM_AUTHORITY_UNAVAILABLE","可信授权服务暂不可用");}
    /** Completion covers the full body; overflow/timeout cancels the active HTTP subscription. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> done=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return done;}
        public synchronized void onSubscribe(Flow.Subscription s){subscription=s;if(done.isDone())s.cancel();else s.request(1);}
        public synchronized void onNext(List<ByteBuffer> buffers){
            if(done.isDone())return;
            for(var buffer:buffers){
                if(buffer.remaining()>MAX_BYTES-bytes.size()){reject();return;}
                byte[] piece=new byte[buffer.remaining()];buffer.get(piece);bytes.writeBytes(piece);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){done.completeExceptionally(unavailable());}
        public synchronized void onComplete(){done.complete(bytes.toByteArray());}
        synchronized void reject(){done.completeExceptionally(unavailable());cancel();}
        synchronized void cancel(){if(subscription!=null)subscription.cancel();if(!done.isDone())done.completeExceptionally(unavailable());}
    }
    @Override public void close(){client.close();}
}
