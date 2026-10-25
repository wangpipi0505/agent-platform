package cn.agentplatform.gateway.infra;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import cn.agentplatform.gateway.infra.PortalEventsPacketBudget.Owner;
import cn.agentplatform.gateway.infra.PortalEventsPacketBudget.Reservation;

/**
 * Private APEV v1 wire integrity only. Receipt, source, association, floor/current,
 * row digests and permission to publish remain unverified and belong to F2.
 * Owns no HTTP stream: closing this borrowed decoder never closes upstream.
 */
public final class PortalEventsPacketDecoder implements AutoCloseable {
    private static final int MAX_HEADER=8192,MAX_METADATA=8388608,MAX_PAYLOAD=262144,MAX_WIRE=8659012;
    private static final Set<String> HEADER_KEYS=Set.of("childResponseId","runId","resourceSha256","initialCursor","afterSeq","eventSeq","eventType","payloadBytes","metadataSha256","payloadSha256");
    private static final Set<String> WINDOW_KEYS=Set.of("runDbId","owner","acceptedSnapshotSha","initialCeiling","initialCeilingSha256","provenance","rootScopes","stateScope","eventScope","scopes","state","eventIdentity","window","hasEvent");
    private final Owner owner;
    private InputStream input;
    private boolean inputBound,reading,closed,failed,ended,helloSeen,capacitySeen,ordinalExhausted;
    private long expectedOrdinal;
    private UntrustedPacket packet;

    public PortalEventsPacketDecoder(Owner owner){this.owner=Objects.requireNonNull(owner);owner.claim(this);}

    public Optional<UntrustedPacket> readNext(InputStream upstream)throws IOException {
        Objects.requireNonNull(upstream);
        synchronized(owner){
            owner.requireOpen();if(closed||failed)throw new IllegalStateException("packet decoder terminal");
            if(reading||packet!=null)throw new IllegalStateException("previous packet/read remains active");
            if(inputBound&&input!=upstream)throw new IllegalStateException("decoder input cannot be replaced");
            if(!inputBound){inputBound=true;input=upstream;}
            if(ended)return Optional.empty();reading=true;
        }
        Reservation work=null,prefixCharge=null,headerCharge=null,wireCharge=null;
        byte[] prefix=null,headerBytes=null,wire=null;
        try{
            // Reserve the entire verified provider model BEFORE parser, UTF8 scratch or structure scan.
            work=owner.reserve(PortalEventsPacketBudget.SCAN_BYTES);
            prefixCharge=owner.reserve(24+32);prefix=new byte[24];
            int first=upstream.read();
            if(first<0){synchronized(owner){ended=true;}return Optional.empty();}
            if(capacitySeen||ordinalExhausted)throw invalid("bytes after CAPACITY/ordinal exhaustion");
            prefix[0]=(byte)first;readExactly(upstream,prefix,1,23);
            ByteBuffer p=ByteBuffer.wrap(prefix);
            if(p.getInt()!=0x41504556||Short.toUnsignedInt(p.getShort())!=1)throw invalid("prefix magic/version");
            int kind=Short.toUnsignedInt(p.getShort());long ordinal=p.getLong();
            long hl=Integer.toUnsignedLong(p.getInt()),ml=Integer.toUnsignedLong(p.getInt());
            if(kind<1||kind>3||ordinal<0||ordinal!=expectedOrdinal||(!helloSeen&&kind!=1)||(helloSeen&&kind==1))throw invalid("kind/ordinal/HELLO order");
            if(hl<1||hl>MAX_HEADER||ml<1||ml>MAX_METADATA)throw invalid("unsigned segment length");
            // Checked long arithmetic and all intersections precede casts and backing allocation.
            long minimum=Math.addExact(Math.addExact(24L,hl),Math.addExact(ml,44L));
            if(minimum>MAX_WIRE)throw invalid("wire length");
            headerCharge=owner.reserve(hl+32);headerBytes=new byte[(int)hl];readExactly(upstream,headerBytes,0,headerBytes.length);
            Header facts=header(scan(headerBytes,0,headerBytes.length,0));
            long total=Math.addExact(minimum,facts.payloadBytes());if(total>MAX_WIRE)throw invalid("wire length");
            wireCharge=owner.reserve(total+32);wire=new byte[(int)total];
            System.arraycopy(prefix,0,wire,0,24);System.arraycopy(headerBytes,0,wire,24,headerBytes.length);
            int metadataAt=24+(int)hl,payloadAt=metadataAt+(int)ml,commitAt=payloadAt+facts.payloadBytes();
            p=null;headerBytes=null;headerCharge.close();headerCharge=null;prefix=null;prefixCharge.close();prefixCharge=null;
            readExactly(upstream,wire,metadataAt,(int)ml);readExactly(upstream,wire,payloadAt,facts.payloadBytes());readExactly(upstream,wire,commitAt,44);
            ByteBuffer commit=ByteBuffer.wrap(wire,commitAt,44);
            if(commit.getInt()!=0x4150434d||commit.getLong()!=ordinal)throw invalid("COMMIT magic/ordinal");
            commit=null;
            byte[] computed=digest(wire,0,commitAt);
            for(int i=0;i<computed.length;i++)if(computed[i]!=wire[commitAt+12+i])throw invalid("COMMIT SHA256");
            if(!HexFormat.of().formatHex(digest(wire,metadataAt,(int)ml)).equals(facts.metadataSha256())||!HexFormat.of().formatHex(digest(wire,payloadAt,facts.payloadBytes())).equals(facts.payloadSha256()))throw invalid("segment SHA256");
            Map<String,Object> metadata=scan(wire,metadataAt,(int)ml,kind);
            validateMetadata(kind,facts,metadata);
            if(facts.payloadBytes()>0)scan(wire,payloadAt,facts.payloadBytes(),4);
            UntrustedPacket result=new UntrustedPacket(this,wire,wireCharge,kind,ordinal,facts,metadataAt,(int)ml,payloadAt);
            synchronized(owner){
                packet=result;helloSeen=true;if(kind==3)capacitySeen=true;
                if(ordinal==Long.MAX_VALUE)ordinalExhausted=true;else expectedOrdinal=ordinal+1;
            }
            wire=null;wireCharge=null;return Optional.of(result);
        }catch(IOException|RuntimeException|Error e){synchronized(owner){failed=true;}throw e;}
        finally{
            // No phase token is returned until actual synchronous parser/read work has exited.
            wire=null;headerBytes=null;prefix=null;
            if(wireCharge!=null)wireCharge.close();if(headerCharge!=null)headerCharge.close();if(prefixCharge!=null)prefixCharge.close();if(work!=null)work.close();
            synchronized(owner){reading=false;}
        }
    }
    private static void readExactly(InputStream in,byte[] bytes,int at,int count)throws IOException {
        int end=at+count;
        while(at<end){int n=in.read(bytes,at,Math.min(8192,end-at));if(n<0)throw invalid("partial packet EOF");if(n==0){int b=in.read();if(b<0)throw invalid("partial packet EOF");bytes[at++]=(byte)b;}else at+=n;}
    }
    private static byte[] digest(byte[] b,int at,int length){
        try{MessageDigest d=MessageDigest.getInstance("SHA-256");for(int n=0;n<length;n+=8192)d.update(b,at+n,Math.min(8192,length-n));return d.digest();}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static IOException invalid(String message){return new IOException("invalid private packet: "+message);}

    // Exactly this Reader factory is used for each sequential segment. No byte bootstrap,
    // canonicalized/interned names or shared recycler can outlive the prepaid phase.
    private static JsonFactory factory(int depth,int tokens,int string,int number){
        return JsonFactory.builder().disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES).disable(JsonFactory.Feature.INTERN_FIELD_NAMES).disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(depth).maxTokenCount(tokens).maxStringLength(string).maxNameLength(string).maxNumberLength(number).maxDocumentLength(MAX_METADATA).build()).build();
    }
    private static Map<String,Object> scan(byte[] bytes,int start,int length,int schema)throws IOException {
        int maxDepth=schema==0?8:schema==4?64:80,maxTokens=schema==0?2048:262144,maxString=schema==0?4096:1048576,maxNumber=schema==0?19:1000;
        Set<String> keys=schema==0?HEADER_KEYS:schema==1?Set.of("receipt"):schema==2?WINDOW_KEYS:schema==3?Set.of("code"):Set.of();
        Map<String,Object> root=new HashMap<>();
        CharsetDecoder utf8=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(Reader reader=new InputStreamReader(new ByteArrayInputStream(bytes,start,length),utf8);JsonParser parser=factory(maxDepth,maxTokens,maxString,maxNumber).createParser(reader)){
            int depth=0,count=0;boolean seen=false,done=false;String key=null;
            for(JsonToken t;(t=parser.nextToken())!=null;){
                if(done)throw invalid("trailing JSON value");if(++count>maxTokens)throw invalid("JSON token limit");
                if(t==JsonToken.FIELD_NAME){
                    String name=parser.currentName();if(name.length()>maxString)throw invalid("JSON name limit");unicode(name);
                    if(depth==1&&schema!=4){if(!keys.contains(name))throw invalid("unknown root field");key=name;}
                    continue;
                }
                if(!seen){seen=true;if(schema!=4&&t!=JsonToken.START_OBJECT)throw invalid("JSON root object required");}
                if(t==JsonToken.VALUE_STRING){parser.finishToken();int size=parser.getTextLength();if(size>maxString)throw invalid("JSON string limit");unicode(parser.getTextCharacters(),parser.getTextOffset(),size);}
                if(t.isNumeric()&&parser.getTextLength()>maxNumber)throw invalid("JSON number limit");
                if(depth==1&&key!=null){
                    Object value=t;
                    if(schema==0){
                        if(t==JsonToken.VALUE_STRING)value=parser.getText();else if(t==JsonToken.VALUE_NUMBER_INT)value=parser.getLongValue();else if(t!=JsonToken.VALUE_NULL)throw invalid("header scalar type");
                    }else if(schema==1||schema==3){if(t!=JsonToken.VALUE_STRING)throw invalid("metadata scalar type");value=parser.getText();}
                    else if(key.equals("hasEvent")){if(t!=JsonToken.VALUE_TRUE&&t!=JsonToken.VALUE_FALSE)throw invalid("hasEvent boolean");value=t==JsonToken.VALUE_TRUE;}
                    else if(key.equals("runDbId")){if(t!=JsonToken.VALUE_NUMBER_INT||parser.getLongValue()<1)throw invalid("runDbId positive integer");}
                    root.put(key,value);key=null;
                }
                if(t==JsonToken.START_OBJECT||t==JsonToken.START_ARRAY){if(++depth>maxDepth)throw invalid("JSON depth limit");}
                else if(t==JsonToken.END_OBJECT||t==JsonToken.END_ARRAY){if(--depth==0)done=true;}
                else if(depth==0)done=true;
            }
            if(!seen||!done||(schema!=4&&!root.keySet().equals(keys)))throw invalid("JSON missing fields/value");
            return root;
        }catch(IOException|RuntimeException e){
            // Jackson exceptions may retain their parser and raw ContentReference. Never
            // let that graph escape after the scan reservation has been returned.
            throw invalid("JSON structure/UTF8/limits");
        }
    }
    private static void unicode(String text)throws IOException {
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i==text.length()||!Character.isLowSurrogate(text.charAt(i)))throw invalid("unpaired surrogate");}else if(Character.isLowSurrogate(c))throw invalid("unpaired surrogate");}
    }
    private static void unicode(char[] text,int start,int size)throws IOException {
        int end=start+size;for(int i=start;i<end;i++){char c=text[i];if(Character.isHighSurrogate(c)){if(++i==end||!Character.isLowSurrogate(text[i]))throw invalid("unpaired surrogate");}else if(Character.isLowSurrogate(c))throw invalid("unpaired surrogate");}
    }
    private static String string(Map<String,Object> m,String key)throws IOException {Object v=m.get(key);if(!(v instanceof String s))throw invalid("header string type");return s;}
    private static long integer(Map<String,Object> m,String key)throws IOException {Object v=m.get(key);if(!(v instanceof Long n))throw invalid("header integer type");return n;}
    private static String sha(Map<String,Object> m,String key)throws IOException {String s=string(m,key);if(s.length()!=64)throw invalid("SHA256 shape");for(int i=0;i<64;i++){char c=s.charAt(i);if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))throw invalid("SHA256 shape");}return s;}
    private static Header header(Map<String,Object> m)throws IOException {
        String child=string(m,"childResponseId"),run=string(m,"runId");
        if(child.isBlank()||child.length()>128||!run.matches("r_[0-9a-f]{32}"))throw invalid("child/run shape");
        long initial=integer(m,"initialCursor"),after=integer(m,"afterSeq"),payload=integer(m,"payloadBytes");
        if(initial<0||after<initial||payload<0||payload>MAX_PAYLOAD)throw invalid("header number range");
        Long seq=m.get("eventSeq")==JsonToken.VALUE_NULL?null:integer(m,"eventSeq");
        String type=m.get("eventType")==JsonToken.VALUE_NULL?null:string(m,"eventType");
        // Event text is preserved verbatim; a nonempty whitespace type is not an empty type.
        if(seq!=null&&seq<1||type!=null&&type.isEmpty())throw invalid("event scalar range");
        return new Header(child,run,sha(m,"resourceSha256"),initial,after,seq,type,(int)payload,sha(m,"metadataSha256"),sha(m,"payloadSha256"));
    }
    private static void validateMetadata(int kind,Header h,Map<String,Object> m)throws IOException {
        if(kind==1||kind==3){
            if(h.eventSeq()!=null||h.eventType()!=null||h.payloadBytes()!=0)throw invalid("non-window event");
            if(kind==1){String receipt=(String)m.get("receipt");if(h.afterSeq()!=h.initialCursor()||receipt.length()>4096||!receipt.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw invalid("HELLO receipt shape");}
            else if(!Set.of("BUFFER_LIMIT","EVENT_LIMIT").contains(m.get("code")))throw invalid("CAPACITY code");
            return;
        }
        for(String key:List.of("owner","initialCeiling","provenance","rootScopes","state","window"))if(m.get(key)!=JsonToken.START_OBJECT)throw invalid("WINDOW object type");
        for(String key:List.of("acceptedSnapshotSha","initialCeilingSha256","stateScope"))if(m.get(key)!=JsonToken.VALUE_STRING)throw invalid("WINDOW string type");
        if(m.get("scopes")!=JsonToken.START_ARRAY)throw invalid("WINDOW scopes array");
        boolean event=(Boolean)m.get("hasEvent");
        if(event){if(m.get("eventIdentity")!=JsonToken.START_OBJECT||m.get("eventScope")!=JsonToken.VALUE_STRING||h.eventSeq()==null||h.eventType()==null||h.payloadBytes()==0)throw invalid("event presence mismatch");}
        else if(m.get("eventIdentity")!=JsonToken.VALUE_NULL||m.get("eventScope")!=JsonToken.VALUE_NULL||h.eventSeq()!=null||h.eventType()!=null||h.payloadBytes()!=0)throw invalid("noevent mismatch");
    }

    @Override public void close(){synchronized(owner){
        if(closed)return;if(reading)throw new IllegalStateException("packet read/parse remains active");
        if(packet!=null)packet.close();input=null;closed=true;
    }}
    /** Small immutable wire facts, never authorization or business proof. */
    public record Header(String childResponseId,String runId,String resourceSha256,long initialCursor,long afterSeq,Long eventSeq,String eventType,int payloadBytes,String metadataSha256,String payloadSha256){}

    /** Structurally complete bytes only; no receipt signature, run membership or publication rights. */
    public static final class UntrustedPacket implements AutoCloseable {
        private final PortalEventsPacketDecoder decoder;
        private byte[] wire;
        private Reservation reservation;
        private final int kind,metadataAt,metadataLength,payloadAt;
        private final long ordinal;
        private Header header;
        private int borrows;
        private UntrustedPacket(PortalEventsPacketDecoder decoder,byte[] wire,Reservation reservation,int kind,long ordinal,Header header,int metadataAt,int metadataLength,int payloadAt){this.decoder=decoder;this.wire=wire;this.reservation=reservation;this.kind=kind;this.ordinal=ordinal;this.header=header;this.metadataAt=metadataAt;this.metadataLength=metadataLength;this.payloadAt=payloadAt;}
        private void live(){decoder.owner.requireOpen();if(wire==null||decoder.closed)throw new IllegalStateException("untrusted packet closed");}
        public int kind(){synchronized(decoder.owner){live();return kind;}}
        public long ordinal(){synchronized(decoder.owner){live();return ordinal;}}
        public Header header(){synchronized(decoder.owner){live();return header;}}
        public InputStream metadata(){return borrow(metadataAt,metadataLength);}
        public InputStream payload(){synchronized(decoder.owner){live();return borrow(payloadAt,header.payloadBytes());}}
        private InputStream borrow(int start,int count){synchronized(decoder.owner){
            live();Reservation charge=decoder.owner.reserve(256);
            try{InputStream stream=new Segment(this,start,count,charge);borrows++;return stream;}catch(RuntimeException|Error e){charge.close();throw e;}
        }}
        @Override public void close(){synchronized(decoder.owner){
            if(wire==null)return;if(borrows!=0)throw new IllegalStateException("packet segment borrow remains active");
            wire=null;header=null;reservation.close();reservation=null;decoder.packet=null;
        }}
    }
    private static final class Segment extends InputStream {
        private UntrustedPacket packet;
        private final Owner owner;
        private Reservation reservation;
        private int position;
        private final int end;
        private Segment(UntrustedPacket packet,int start,int count,Reservation reservation){this.packet=packet;owner=packet.decoder.owner;position=start;end=start+count;this.reservation=reservation;}
        private void live()throws IOException {if(packet==null)throw new IOException("packet segment closed");packet.live();}
        @Override public int read()throws IOException {synchronized(owner){live();return position==end?-1:Byte.toUnsignedInt(packet.wire[position++]);}}
        @Override public int read(byte[] out,int off,int length)throws IOException {Objects.checkFromIndexSize(off,length,out.length);synchronized(owner){live();if(length==0)return 0;if(position==end)return -1;int n=Math.min(length,end-position);System.arraycopy(packet.wire,position,out,off,n);position+=n;return n;}}
        @Override public long skip(long count)throws IOException {synchronized(owner){live();long n=Math.min(Math.max(0,count),end-position);position+=(int)n;return n;}}
        @Override public int available()throws IOException {synchronized(owner){live();return end-position;}}
        @Override public void close(){synchronized(owner){if(packet!=null){packet.borrows--;packet=null;reservation.close();reservation=null;}}}
    }
}
