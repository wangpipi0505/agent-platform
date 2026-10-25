package cn.agentplatform.gateway.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.*;
import cn.agentplatform.gateway.infra.PublishedRunHttp;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed engine Q projection. Parsed facts cannot mint a permission; Q retains signed evidence. */
public final class RunObservationMetadataClient implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final URI origin;private final CurrentAuthorityClient authority;private final PublishedRunHttp http;
    private final Set<PublishedRunHttp.Cancellation> active=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    public RunObservationMetadataClient(URI origin,CurrentAuthorityClient authority,PublishedRunHttp http){this(origin,authority,http,false);}
    /** Explicit isolated receiver only; default constructor requires HTTPS. Transport is borrowed. */
    public RunObservationMetadataClient(URI origin,CurrentAuthorityClient authority,PublishedRunHttp http,boolean testLoopback){this.origin=PublishedRunHttp.origin(origin,testLoopback);this.authority=Objects.requireNonNull(authority);this.http=Objects.requireNonNull(http);}
    public static final class Result {
        private final ResourceBinding snapshot;private final GatewayObservationQueryAuthorization query;
        private Result(ResourceBinding snapshot,GatewayObservationQueryAuthorization query){this.snapshot=snapshot;this.query=query;}
        public ResourceBinding snapshot(){return snapshot;}
        public void requireMain(GatewayStatusAuthorization main){main.requireSameQuery(query);if(!snapshot.equals(main.ticket().operation().resource()))throw unavailable();}
        @Override public String toString(){return "RunObservationMetadata[validated-facts]";}
    }
    public Result lookup(GatewayIngressBoundary.PortalStatusRequest capture,HttpServletRequest request,long deadline,PublishedRunHttp.Cancellation cancellation){
        active.add(cancellation);
        try{
            Runnable check=()->{if(closed||deadline-System.nanoTime()<=0)throw unavailable();capture.requireLive(request);};check.run();
            var q=authority.exchangeGatewayRunObservationQuery(capture.sourceToken(),capture.subject(),capture.publicRunId());check.run();
            byte[] bytes=http.getRunObservationMetadata(origin,capture.publicRunId(),q.ticket().value(),deadline,cancellation,()->{check.run();q.requireVerified();}).body();
            var resource=parse(bytes,capture.subject(),capture.publicRunId());check.run();q.requireVerified();return new Result(resource,q);
        }finally{active.remove(cancellation);}
    }
    private static ResourceBinding parse(byte[] bytes,SubjectKey expected,String run){
        try{
            var root=readUtf8(JSON,bytes);requireEnvelope(root);
            var d=root.path("data");
            if(!fields(d,Set.of("runId","owner","root","appVersionId","appVersionNo","acceptedSnapshotSha256","initialCeilingSha256"))
                    ||Json.mapper().writeValueAsBytes(d).length>4096||!text(d,"runId").equals(run))throw unavailable();
            var owner=d.path("owner");if(!fields(owner,Set.of("tenantId","principalId"))||!text(owner,"tenantId").equals(expected.tenantId())||!text(owner,"principalId").equals(expected.principalId())
                    ||expected.tenantId().length()>128||expected.principalId().length()>128)throw unavailable();
            long no=positive(d.path("appVersionNo"));positive(d.path("appVersionId"));
            var app=d.path("root");if(!fields(app,Set.of("kind","id","versionDigest"))||!text(app,"kind").equals("app-version")
                    ||!text(app,"id").matches("[A-Za-z][A-Za-z0-9_-]{0,63}@"+no))throw unavailable();
            hash(text(app,"versionDigest"));hash(text(d,"initialCeilingSha256"));String sha=text(d,"acceptedSnapshotSha256");hash(sha);
            return RunObservationSelectors.status(run,sha);
        }catch(Exception invalid){throw unavailable();}
    }
    static void requireEnvelope(JsonNode r){
        if(!fields(r,Set.of("code","message","data","traceId"))||!text(r,"code").equals("OK")||!r.path("message").isTextual()||r.path("message").textValue().length()>512
                ||!(r.path("traceId").isNull()||r.path("traceId").isTextual()&&r.path("traceId").textValue().length()<=128))throw unavailable();
    }
    static JsonNode readUtf8(ObjectMapper mapper,byte[] bytes)throws java.io.IOException {
        // Byte-based Jackson autodetect accepts UTF-16/32. The observation wire profile is UTF-8.
        var text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
        return mapper.readTree(text.toString());
    }
    private static boolean fields(JsonNode d,Set<String> names){if(d==null||!d.isObject()||d.size()!=names.size())return false;for(String n:names)if(!d.has(n))return false;return true;}
    private static String text(JsonNode d,String key){var n=d.path(key);if(!n.isTextual())throw unavailable();return n.textValue();}
    private static long positive(JsonNode n){if(!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<1)throw unavailable();return n.longValue();}
    private static void hash(String value){if(!value.matches("[a-f0-9]{64}"))throw unavailable();}
    private static PlatformException unavailable(){return new PlatformException(503,"RUN_METADATA_UNAVAILABLE","运行观察元数据暂不可用");}
    @Override public void close(){closed=true;active.forEach(PublishedRunHttp.Cancellation::cancel);}
}
