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
    /** EVENTS facts remain tied to this exact Opening/Q; they are never an authorization. */
    public static final class EventsMetadata implements AutoCloseable {
        private GatewayEventsOpening opening;
        private GatewayObservationQueryAuthorization query;
        private final String runId, tenantId, principalId, rootId, rootDigest, accepted, initial;
        private final long versionId, versionNo;
        private final ResourceBinding snapshot;
        private EventsMetadata(GatewayEventsOpening o, GatewayObservationQueryAuthorization q, Map<String, Object> d) {
            opening = o;
            query = q;
            runId = (String) d.get("runId");
            var owner = objectValue(d, "owner");
            tenantId = (String) owner.get("tenantId");
            principalId = (String) owner.get("principalId");
            var root = objectValue(d, "root");
            rootId = (String) root.get("id");
            rootDigest = (String) root.get("versionDigest");
            versionId = (Long) d.get("appVersionId");
            versionNo = (Long) d.get("appVersionNo");
            accepted = (String) d.get("acceptedSnapshotSha256");
            initial = (String) d.get("initialCeilingSha256");
            snapshot = RunObservationSelectors.events(runId, accepted);
        }
        private void live() {
            if (opening == null || query == null || opening.queryAuthorization() != query || !opening.publicRunId().equals(runId)) throw unavailable();
            query.requireVerified();
        }
        public synchronized String runId() {
            live();
            return runId;
        }
        public synchronized SubjectKey owner() {
            live();
            return query.ticket().operation().subject();
        }
        public synchronized ResourceBinding root() {
            live();
            return new ResourceBinding("app-version", rootId, rootDigest);
        }
        public synchronized long appVersionId() {
            live();
            return versionId;
        }
        public synchronized long appVersionNo() {
            live();
            return versionNo;
        }
        public synchronized String acceptedSnapshotSha256() {
            live();
            return accepted;
        }
        public synchronized String initialCeilingSha256() {
            live();
            return initial;
        }
        public synchronized ResourceBinding snapshot() {
            live();
            return snapshot;
        }
        @Override public synchronized void close() {
            opening = null;
            query = null;
        }
        @Override public String toString() {
            return "EventsMetadata[validated-facts]";
        }
    }
    public static EventsMetadata parseEvents(GatewayEventsOpening opening, byte[] boundedBody) {
        Objects.requireNonNull(opening);
        var q = opening.queryAuthorization();
        q.requireVerified();
        String run = opening.publicRunId();
        EventsMetadata facts;
        try {
            var envelope = eventsEnvelope(boundedBody, false);
            if (!"OK".equals(envelope.get("code"))) throw unavailable();
            var d = objectValue(envelope, "data");
            var owner = objectValue(d, "owner");
            var app = objectValue(d, "root");
            var subject = q.ticket().operation().subject();
            if (!run.equals(d.get("runId")) || !subject.tenantId().equals(owner.get("tenantId")) || !subject.principalId().equals(owner.get("principalId")) || !q.ticket().operation().resource().equals(RunObservationSelectors.metadata(subject, run))) throw unavailable();
            long no = (Long) d.get("appVersionNo");
            if (!"app-version".equals(app.get("kind")) || !((String) app.get("id")).matches("[A-Za-z][A-Za-z0-9_-]{0,63}@" + no)) throw unavailable();
            hash((String) app.get("versionDigest"));
            hash((String) d.get("acceptedSnapshotSha256"));
            hash((String) d.get("initialCeilingSha256"));
            // No DOM/ObjectMapper/shared recycler: fixed seven-field streaming model only.
            var sink = new java.io.OutputStream() {
                int bytes;
                public void write(int b) throws java.io.IOException {
                    if (++ bytes > 4096) throw new java.io.IOException("data limit");
                }
                public void write(byte[] b, int off, int len) throws java.io.IOException {
                    if (len > 4096 - bytes) throw new java.io.IOException("data limit");
                    bytes += len;
                }
            };
            try (var generator = eventsFactory().createGenerator(sink)) {
                writeEventsData(generator, d);
            }
            facts = new EventsMetadata(opening, q, d);
        } catch (Exception invalid) {
            throw unavailable();
        }
        if (opening.queryAuthorization() != q || !opening.publicRunId().equals(run)) throw unavailable();
        q.requireVerified();
        return facts;
    }
    /** Only finite strict ApiResponse errors; internal 401 can never become Portal 401. */
    public static PlatformException parseEventsFailure(GatewayEventsOpening opening, int status, byte[] body) {
        var q = opening.queryAuthorization();
        q.requireVerified();
        String code;
        try {
            var e = eventsEnvelope(body, true);
            code = (String) e.get("code");
            if (!(status == 404 && "RUN_NOT_FOUND".equals(code) || status == 403 && Set.of("AUTHORITY_EXPIRED", "AUTHORITY_CONTEXT_MISSING", "RUN_AUTHORITY_DENIED", "MATERIAL_SCOPE_MISSING_OR_CHANGED", "SYS-AUTH-403", "AUTHORITY_DENIED").contains(code))) throw unavailable();
        } catch (Exception invalid) {
            throw unavailable();
        }
        if (opening.queryAuthorization() != q) throw unavailable();
        q.requireVerified();
        return new PlatformException(status, code, "运行观察请求未获准");
    }
    private static JsonFactory eventsFactory() {
        return JsonFactory.builder().disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES).disable(JsonFactory.Feature.INTERN_FIELD_NAMES).disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING).disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxNameLength(32).maxStringLength(512).maxNumberLength(20).maxDocumentLength(8192).build()).build();
    }
    private static Map<String, Object> eventsEnvelope(byte[] b, boolean error) throws java.io.IOException {
        if (b == null || b.length > 8192) throw unavailable();
        char[] chars = new char[8192];
        var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        var target = java.nio.CharBuffer.wrap(chars);
        var result = decoder.decode(java.nio.ByteBuffer.wrap(b), target, true);
        if (result.isError()) result.throwException();
        if (result.isOverflow()) throw unavailable();
        result = decoder.flush(target);
        if (result.isError()) result.throwException();
        if (result.isOverflow() || target.position() > 0 && chars[0] == '\ufeff') throw unavailable();
        try (var p = eventsFactory().createParser(chars, 0, target.position())) {
            var reader = new EventsReader(p, error);
            var root = reader.object(0);
            if (reader.next() != null) throw unavailable();
            return root;
        }
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectValue(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Map)) throw unavailable();
        return (Map<String, Object>) v;
    }
    private static final class EventsReader {
        private final JsonParser parser;
        private final boolean error;
        private int tokens;
        EventsReader(JsonParser p, boolean error) {
            parser = p;
            this.error = error;
        }
        JsonToken next() throws java.io.IOException {
            if (++ tokens > 64) throw unavailable();
            return parser.nextToken();
        }
        String scalar(int max) throws java.io.IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) throw unavailable();
            parser.finishToken();
            String s = parser.getText();
            if (s.length() > max) throw unavailable();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++ i == s.length() || !Character.isLowSurrogate(s.charAt(i))) throw unavailable();
                } else if (Character.isLowSurrogate(c)) throw unavailable();
            }
            return s;
        }
        Map<String, Object> object(int schema) throws java.io.IOException {
            if ((schema == 0 ? next() : parser.currentToken()) != JsonToken.START_OBJECT) throw unavailable();
            Set<String> names = switch (schema) {
                case 0 -> Set.of("code", "message", "data", "traceId");
                case 1 -> Set.of("runId", "owner", "root", "appVersionId", "appVersionNo", "acceptedSnapshotSha256", "initialCeilingSha256");
                case 2 -> Set.of("tenantId", "principalId");
                case 3 -> Set.of("kind", "id", "versionDigest");
                default -> throw unavailable();
            };
            Map<String, Object> values = new HashMap<>();
            while (next() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw unavailable();
                String name = parser.currentName();
                if (!names.contains(name) || values.containsKey(name)) throw unavailable();
                JsonToken token = next();
                Object value;
                if (schema == 0 && name.equals("data")) {
                    if (error) {
                        if (token != JsonToken.VALUE_NULL) throw unavailable();
                        value = null;
                    } else value = object(1);
                } else if (schema == 1 && (name.equals("owner") || name.equals("root"))) value = object(name.equals("owner") ? 2 : 3);
                else if (schema == 1 && (name.equals("appVersionId") || name.equals("appVersionNo"))) {
                    if (token != JsonToken.VALUE_NUMBER_INT) throw unavailable();
                    long n = parser.getLongValue();
                    if (n <= 0) throw unavailable();
                    value = n;
                } else if (schema == 0 && name.equals("traceId") && token == JsonToken.VALUE_NULL) value = null;
                else value = scalar(schema == 2 || name.equals("traceId") ? 128 : 512);
                values.put(name, value);
            }
            if (!values.keySet().equals(names)) throw unavailable();
            return values;
        }
    }
    private static void writeEventsData(JsonGenerator g, Map<String, Object> d) throws java.io.IOException {
        g.writeStartObject();
        g.writeStringField("runId", (String) d.get("runId"));
        g.writeObjectFieldStart("owner");
        var o = objectValue(d, "owner");
        g.writeStringField("tenantId", (String) o.get("tenantId"));
        g.writeStringField("principalId", (String) o.get("principalId"));
        g.writeEndObject();
        g.writeObjectFieldStart("root");
        var r = objectValue(d, "root");
        g.writeStringField("kind", (String) r.get("kind"));
        g.writeStringField("id", (String) r.get("id"));
        g.writeStringField("versionDigest", (String) r.get("versionDigest"));
        g.writeEndObject();
        g.writeNumberField("appVersionId", (Long) d.get("appVersionId"));
        g.writeNumberField("appVersionNo", (Long) d.get("appVersionNo"));
        g.writeStringField("acceptedSnapshotSha256", (String) d.get("acceptedSnapshotSha256"));
        g.writeStringField("initialCeilingSha256", (String) d.get("initialCeilingSha256"));
        g.writeEndObject();
    }
    /** Pure finite error data only; the owning private Session must check its own G around parsing. */
    public static String parsePrivateEventsErrorCode(byte[] body) {
        try {
            String code = (String) eventsEnvelope(body, true).get("code");
            if (!code.matches("[A-Z][A-Z0-9_-]{0,63}")) throw unavailable();
            return code;
        } catch (Exception invalid) { throw unavailable(); }
    }
}
