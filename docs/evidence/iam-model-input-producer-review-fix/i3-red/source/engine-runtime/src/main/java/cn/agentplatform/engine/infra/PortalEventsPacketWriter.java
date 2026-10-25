package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.math.*;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.*;

/** E encoder only. No untrusted wire decoder, serializer reflection, or complete metadata buffer.
 * Fixed UTF8 scratch avoids Jackson's growing text/byte accumulators and recycler backing. */
public final class PortalEventsPacketWriter {
    public static final String MIME="application/vnd.agent-platform.portal-events.v1";
    static final int HEADER_MAX=8192,METADATA_MAX=8388608,PREFIX_BYTES=24,COMMIT_BYTES=44;
    private PortalEventsPacketWriter(){}
    static long wireBytes(long header,long metadata,long payload){
        if(header<1||header>HEADER_MAX||metadata<1||metadata>METADATA_MAX||payload<0||payload>262144)throw RunEventReader.finite("PACKET_BYTES");
        return Math.addExact(Math.addExact(PREFIX_BYTES+COMMIT_BYTES,header),Math.addExact(metadata,payload));
    }
    static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    static String hex(byte[] b){return HexFormat.of().formatHex(b);}
    @FunctionalInterface interface Encoding {void write(Json out)throws IOException;}
    static void write(RunEventReader.PacketMaterial m,PortalEventsResponse.Capture capture,RunEventReader.Ledger ledger,byte[] payload,Runnable guard,OutputStream output,long ordinal,boolean hello)throws IOException{
        Objects.requireNonNull(output);guard.run();if(ordinal<0||hello&&ordinal!=0||!hello&&ordinal==0)throw denied();
        var w=m.window;boolean event=!hello&&w.resetReason()==null&&m.event!=null;
        // Reservation precedes every allocation. Scratch64KiB covers 8192B streaming backing,
        // prefix24/commit44, SHA state/results, UTF8 scalars and per-depth Json state. Descriptor
        //64KiB covers <=80 active iterators/frames and bounded scalar strings. Sorting scans the
        // existing immutable maps/sets (O(n²), <=4096 scopes); no n-sized sorting arrays exist.
        // Header8192+24 is charged separately, and is also within C's original frame admission.
        try(var scratch=ledger.reserve(65536);var descriptors=ledger.reserve(65536);var headerBacking=ledger.reserve(HEADER_MAX+24L)){
            guard.run();Encoding metadata=hello?j->{j.object();j.field("receipt");j.string(m.receipt);j.endObject();}:j->metadata(j,m,event);
            var first=new DigestSink(METADATA_MAX,guard,null,ledger);metadata.write(new Json(first,80,262144,1048576,1000));first.finish();
            String metadataSha=hex(first.digest.digest());String payloadSha=hex(sha().digest(event?payload:EMPTY));int payloadBytes=event?payload.length:0;
            if(capture.responseId().isBlank()||capture.responseId().length()>128)throw denied();
            var header=new Fixed();var j=new Json(header,8,2048,4096,19);j.object();
            j.field("afterSeq");j.number(hello?capture.initialCursor():w.afterSeq());j.field("childResponseId");j.string(capture.responseId());
            j.field("eventSeq");if(event)j.number(m.event.path("seq").longValue());else j.nil();
            j.field("eventType");if(event)j.string(m.event.path("type").textValue());else j.nil();
            j.field("initialCursor");j.number(capture.initialCursor());j.field("metadataSha256");j.string(metadataSha);
            j.field("payloadBytes");j.number(payloadBytes);j.field("payloadSha256");j.string(payloadSha);
            j.field("resourceSha256");j.string(capture.resource().versionDigest());j.field("runId");j.string(w.runId());j.endObject();
            wireBytes(header.count,first.count,payloadBytes);guard.run();
            byte[] prefix=ByteBuffer.allocate(PREFIX_BYTES).putInt(0x41504556).putShort((short)1).putShort((short)(hello?1:2)).putLong(ordinal).putInt(header.count).putInt((int)first.count).array();
            var actual=new DigestSink(Long.MAX_VALUE,guard,output,ledger);
            // Prefix is the irrevocable boundary: a failure here can never switch generation.
            actual.raw(prefix,0,prefix.length);actual.raw(header.bytes,0,header.count);
            var second=new DigestSink(METADATA_MAX,guard,new OutputStream(){public void write(int v)throws IOException{actual.write(v);}public void write(byte[] b,int o,int n)throws IOException{actual.raw(b,o,n);}},ledger);
            metadata.write(new Json(second,80,262144,1048576,1000));second.finish();
            if(second.count!=first.count||!metadataSha.equals(hex(second.digest.digest())))throw denied();
            if(event){var digest=sha();for(int offset=0;offset<payload.length;){guard.run();int n=Math.min(8192,payload.length-offset);digest.update(payload,offset,n);actual.raw(payload,offset,n);offset+=n;}if(!payloadSha.equals(hex(digest.digest())))throw denied();}
            actual.finish();byte[] commit=ByteBuffer.allocate(COMMIT_BYTES).putInt(0x4150434d).putLong(ordinal).put(actual.digest.digest()).array();
            guard.run();copyWrite(output,commit,0,commit.length,ledger,guard);guard.run();output.flush();guard.run();
        }
    }
    private static final byte[] EMPTY=new byte[0];
    /** Capacity carries no unverified run facts and requires the production child export. */
    public static void capacity(OutputStream output,PortalEventsResponse response,HttpCurrentAuthorityClient authority,long ordinal,long after,String code)throws IOException{
        if(!Set.of("BUFFER_LIMIT","EVENT_LIMIT").contains(code)||ordinal<1||after<0)throw denied();
        authority.portalEventsChildReceipt(response);var c=response.currentCapture();Runnable guard=()->response.requireLive(c);var ledger=new RunEventReader.Ledger();
        try(var scratch=ledger.reserve(65536);var descriptor=ledger.reserve(65536);var backing=ledger.reserve(HEADER_MAX+24L)){
            var meta=new Fixed();var jm=new Json(meta,8,2048,4096,19);jm.object();jm.field("code");jm.string(code);jm.endObject();
            var header=new Fixed();var j=new Json(header,8,2048,4096,19);j.object();j.field("afterSeq");j.number(after);j.field("childResponseId");j.string(c.responseId());j.field("eventSeq");j.nil();j.field("eventType");j.nil();j.field("initialCursor");j.number(c.initialCursor());j.field("metadataSha256");j.string(hex(sha().digest(Arrays.copyOf(meta.bytes,meta.count))));j.field("payloadBytes");j.number(0);j.field("payloadSha256");j.string(hex(sha().digest(EMPTY)));j.field("resourceSha256");j.string(c.resource().versionDigest());j.field("runId");j.string(c.resource().id());j.endObject();
            byte[] p=ByteBuffer.allocate(24).putInt(0x41504556).putShort((short)1).putShort((short)3).putLong(ordinal).putInt(header.count).putInt(meta.count).array();
            var sink=new DigestSink(wireBytes(header.count,meta.count,0),guard,output,ledger);sink.raw(p,0,p.length);sink.raw(header.bytes,0,header.count);sink.raw(meta.bytes,0,meta.count);sink.finish();
            byte[] commit=ByteBuffer.allocate(44).putInt(0x4150434d).putLong(ordinal).put(sink.digest.digest()).array();guard.run();copyWrite(output,commit,0,commit.length,ledger,guard);guard.run();output.flush();guard.run();
        }
    }
    private static void metadata(Json j,RunEventReader.PacketMaterial m,boolean event)throws IOException{
        var b=m.binding;var w=m.window;
        j.object();j.field("acceptedSnapshotSha");j.string(b.snapshotSha());
        j.field("eventIdentity");if(event){j.object();for(String key:List.of("created_at","id","run_id","seq","type")){j.field(key);j.value(m.event.get(key));}j.endObject();}else j.nil();
        j.field("eventScope");if(event)j.string(m.eventScope.scopeSha256());else j.nil();j.field("hasEvent");j.bool(event);
        j.field("initialCeiling");j.actor(b.initial());j.field("initialCeilingSha256");j.string(b.initialSha());j.field("owner");j.subject(b.owner());
        j.field("provenance");j.object();j.field("admissionDecisionId");j.control(b.admissionDecision());j.field("appVersionId");j.number(b.appVersionId());j.field("appVersionNo");j.number(b.appVersionNo());j.field("authorizationVersion");j.control(b.authorizationVersion());j.field("executionRoot");j.resource(b.root());j.field("grantRef");j.control(b.delegation().value());j.field("operationId");j.control(b.operationId());j.field("originAuthenticationId");j.control(b.originId());j.endObject();
        j.field("rootScopes");j.object();for(String kind:List.of("run-inputs","run-snapshot","run-version-refs")){j.field(kind);MaterialScopeV1 found=null;for(var s:b.scopes())if(s.material().kind().equals(kind)){if(found!=null)throw denied();found=s;}if(found==null)throw denied();j.string(found.scopeSha256());}j.endObject();
        j.field("runDbId");j.number(b.runId());j.field("scopes");j.array();
        MaterialScopeStore.ObservedRow previous=null;
        int included=0;for(var row:m.rows.values())if(included(m,row,event))included++;
        for(int index=0;index<included;index++){
            MaterialScopeStore.ObservedRow next=null;for(var row:m.rows.values())if(included(m,row,event)&&(previous==null||compare(row,previous)>0)&&(next==null||compare(row,next)<0))next=row;
            if(next==null)throw denied();previous=next;j.element();j.object();j.field("revision");j.number(next.revision());j.field("scope");j.scope(m.scopes.get(next.sha()));j.endObject();
        }j.endArray();j.field("state");j.value(m.state);j.field("stateScope");j.string(m.stateScope.scopeSha256());
        j.field("window");j.object();j.field("afterSeq");j.number(w.afterSeq());j.field("oldest");if(w.oldest()==null)j.nil();else j.number(w.oldest());j.field("status");j.string(w.status());j.field("watermark");j.number(w.watermark());j.endObject();j.endObject();
    }
    private static boolean included(RunEventReader.PacketMaterial m,MaterialScopeStore.ObservedRow row,boolean event){
        String sha=row.sha();return MaterialScopeStore.KINDS.contains(row.kind())||sha.equals(m.stateScope.scopeSha256())||m.stateScope.parentScopeSha256().contains(sha)
                ||event&&(sha.equals(m.eventScope.scopeSha256())||m.eventScope.parentScopeSha256().contains(sha));
    }
    static int compare(MaterialScopeStore.ObservedRow a,MaterialScopeStore.ObservedRow b){int c=Long.compare(a.revision(),b.revision());return c==0?a.sha().compareTo(b.sha()):c;}
    private static PlatformException denied(){return new PlatformException(403,"RUN_AUTHORITY_DENIED","运行观察不可用");}
    private static void copyWrite(OutputStream output,byte[] bytes,int offset,int length,RunEventReader.Ledger ledger,Runnable guard)throws IOException{
        for(int p=offset;p<offset+length;){int n=Math.min(8192,offset+length-p);try(var reservation=ledger.reserve(n+24L)){byte[] isolated=Arrays.copyOfRange(bytes,p,p+n);guard.run();output.write(isolated,0,n);guard.run();}p+=n;}
    }
    static final class Fixed extends OutputStream {
        final byte[] bytes=new byte[HEADER_MAX];int count;
        public void write(int v){if(count==bytes.length)throw RunEventReader.finite("PACKET_HEADER");bytes[count++]=(byte)v;}
    }
    static final class DigestSink extends OutputStream {
        final long cap;final Runnable guard;final OutputStream output;final RunEventReader.Ledger ledger;final MessageDigest digest=sha();final byte[] bytes;int used;long count;
        DigestSink(long cap,Runnable guard,OutputStream output,RunEventReader.Ledger ledger){this.cap=cap;this.guard=guard;this.output=output;this.ledger=ledger;bytes=output==null?null:new byte[8192];}
        public void write(int v)throws IOException{guard.run();count=RunEventReader.Limits.add(count,1,cap,"PACKET_METADATA");digest.update((byte)v);if(output!=null){bytes[used++]=(byte)v;if(used==bytes.length)drain();}}
        public void write(byte[] b,int o,int n)throws IOException{raw(b,o,n);}
        void raw(byte[] b,int o,int n)throws IOException{guard.run();count=RunEventReader.Limits.add(count,n,cap,"PACKET_METADATA");digest.update(b,o,n);if(output!=null)for(int p=o;p<o+n;){int size=Math.min(bytes.length-used,o+n-p);System.arraycopy(b,p,bytes,used,size);used+=size;p+=size;if(used==bytes.length)drain();}}
        void drain()throws IOException{if(used>0){copyWrite(output,bytes,0,used,ledger,guard);used=0;}}
        void finish()throws IOException{guard.run();drain();guard.run();}
    }
    /** Allocation-free escaping/UTF8, sorted object keys and sets, original array/parent order.
     * Numeric normalization matches MaterialScopeStore; this is deliberately not RFC8785. */
    static final class Json {
        final OutputStream out;final int maxDepth,maxTokens,maxString,maxNumber;final boolean[] first;int depth,tokens;
        Json(OutputStream out,int depth,int tokens,int string,int number){this.out=out;maxDepth=depth;maxTokens=tokens;maxString=string;maxNumber=number;first=new boolean[depth+1];}
        void token(){if(++tokens>maxTokens)throw RunEventReader.finite("PACKET_TOKENS");}
        void object()throws IOException{token();if(++depth>maxDepth)throw RunEventReader.finite("PACKET_DEPTH");first[depth]=true;out.write('{');}
        void endObject()throws IOException{token();out.write('}');depth--;}
        void array()throws IOException{token();if(++depth>maxDepth)throw RunEventReader.finite("PACKET_DEPTH");first[depth]=true;out.write('[');}
        void endArray()throws IOException{token();out.write(']');depth--;}
        void element()throws IOException{if(!first[depth])out.write(',');first[depth]=false;}
        void field(String key)throws IOException{element();string(key);out.write(':');}
        void ascii(String s)throws IOException{for(int i=0;i<s.length();i++)out.write(s.charAt(i));}
        void nil()throws IOException{token();ascii("null");}
        void bool(boolean b)throws IOException{token();ascii(b?"true":"false");}
        void number(Number n)throws IOException{token();String s;if(n instanceof BigDecimal d){d=d.stripTrailingZeros();if(d.signum()!=0&&d.scale()<=0&&(long)d.precision()-d.scale()+(d.signum()<0?1:0)>maxNumber)throw RunEventReader.finite("PACKET_NUMBER");s=d.signum()==0?"0":d.scale()<=0?d.toBigIntegerExact().toString():d.toString();}else s=n.toString();if(s.length()>maxNumber||!s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw RunEventReader.finite("PACKET_NUMBER");ascii(s);}
        void control(String s)throws IOException{if(s==null||s.isBlank())throw denied();int bytes=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);bytes+=c<128?1:c<2048?2:3;if(Character.isHighSurrogate(c)){if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(++i)))throw denied();bytes+=1;}else if(Character.isLowSurrogate(c))throw denied();if(bytes>1024)throw RunEventReader.finite("PACKET_CONTROL");}string(s);}
        void string(String s)throws IOException{token();if(s==null)throw denied();if(s.length()>maxString)throw RunEventReader.finite("PACKET_STRING");out.write('"');
            for(int i=0;i<s.length();i++){int c=s.charAt(i);if(c=='"'||c=='\\'){out.write('\\');out.write(c);}else if(c<32){ascii("\\u00");out.write("0123456789abcdef".charAt(c>>>4));out.write("0123456789abcdef".charAt(c&15));}
                else if(c<128)out.write(c);else if(c<2048){out.write(192|(c>>>6));out.write(128|(c&63));}
                else if(Character.isHighSurrogate((char)c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw denied();int p=Character.toCodePoint((char)c,s.charAt(i));out.write(240|(p>>>18));out.write(128|((p>>>12)&63));out.write(128|((p>>>6)&63));out.write(128|(p&63));}
                else{if(Character.isLowSurrogate((char)c))throw denied();out.write(224|(c>>>12));out.write(128|((c>>>6)&63));out.write(128|(c&63));}}
            out.write('"');}
        void strings(Collection<String> values,boolean sorted)throws IOException{array();String previous=null;if(sorted){for(int i=0;i<values.size();i++){String next=null;for(String s:values)if((previous==null||s.compareTo(previous)>0)&&(next==null||s.compareTo(next)<0))next=s;if(next==null)throw denied();element();string(next);previous=next;}}else for(String s:values){element();string(s);}endArray();}
        void dictionary(Map<String,Set<String>> values)throws IOException{object();String previous=null;for(int i=0;i<values.size();i++){String next=null;for(String k:values.keySet())if((previous==null||k.compareTo(previous)>0)&&(next==null||k.compareTo(next)<0))next=k;if(next==null)throw denied();field(next);strings(values.get(next),true);previous=next;}endObject();}
        void subject(SubjectKey s)throws IOException{object();field("principalId");string(s.principalId());field("tenantId");string(s.tenantId());endObject();}
        void resource(ResourceBinding r)throws IOException{object();field("id");string(r.id());field("kind");string(r.kind());field("versionDigest");string(r.versionDigest());endObject();}
        void actor(Actor a)throws IOException{object();field("attributes");dictionary(a.attributes());field("principalId");string(a.principalId());field("roles");strings(a.roles(),true);field("tenantId");string(a.tenantId());endObject();}
        void scope(MaterialScopeV1 s)throws IOException{if(s==null)throw denied();object();field("initialCeilingSha256");string(s.initialCeilingSha256());field("material");var r=s.material();object();field("id");string(r.id());field("kind");string(r.kind());field("payloadSha256");string(r.payloadSha256());field("producer");string(r.producer());field("version");string(r.version());endObject();field("owner");subject(s.owner());field("parentScopeSha256");strings(s.parentScopeSha256(),false);field("requiredDataLabels");strings(s.requiredDataLabels(),true);field("requiredEntitlements");dictionary(s.requiredEntitlements());field("requiredRoles");strings(s.requiredRoles(),true);field("schemaVersion");number(s.schemaVersion());field("scopeSha256");string(s.scopeSha256());field("sourceAuthorizationVersion");string(s.sourceAuthorizationVersion());field("sourceDecisionId");string(s.sourceDecisionId());endObject();}
        void value(JsonNode v)throws IOException{if(v==null||v.isNull()){nil();return;}if(v.isObject()){object();String previous=null;for(int i=0;i<v.size();i++){String next=null;var it=v.fieldNames();while(it.hasNext()){String k=it.next();if((previous==null||k.compareTo(previous)>0)&&(next==null||k.compareTo(next)<0))next=k;}if(next==null)throw denied();field(next);value(v.get(next));previous=next;}endObject();}else if(v.isArray()){array();for(var n:v){element();value(n);}endArray();}else if(v.isTextual())string(v.textValue());else if(v.isBoolean())bool(v.booleanValue());else if(v.isNumber())number(v.numberValue());else throw denied();}
    }
}
