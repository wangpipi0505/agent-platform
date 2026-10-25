package cn.agentplatform.gateway.infra;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PortalEventsPacketDecoderTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String SHA="0".repeat(64),RUN="r_"+"a".repeat(32);
    static final byte[] EMPTY=new byte[0];
    static byte[] utf8(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String quote(String s)throws Exception{return JSON.writeValueAsString(s);}
    static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static String header(byte[] m,byte[] p,boolean e)throws Exception{return "{\"childResponseId\":\"child\",\"runId\":\""+RUN+"\",\"resourceSha256\":\""+SHA+"\",\"initialCursor\":0,\"afterSeq\":0,\"eventSeq\":"+(e?"1":"null")+",\"eventType\":"+(e?quote("事件 类型 "+"界".repeat(32)):"null")+",\"payloadBytes\":"+p.length+",\"metadataSha256\":\""+sha(m)+"\",\"payloadSha256\":\""+sha(p)+"\"}";}
    // Mechanical protocol shapes only: no fixture here confers PG/issuer authority.
    static String window(boolean e,String state){return "{\"runDbId\":1,\"owner\":{},\"acceptedSnapshotSha\":\""+SHA+"\",\"initialCeiling\":{},\"initialCeilingSha256\":\""+SHA+"\",\"provenance\":{},\"rootScopes\":{},\"stateScope\":\"scope\",\"eventScope\":"+(e?"\"scope\"":"null")+",\"scopes\":[],\"state\":"+state+",\"eventIdentity\":"+(e?"{}":"null")+",\"window\":{},\"hasEvent\":"+e+"}";}
    static byte[] packet(int k,long n,String m,String p)throws Exception{return packet(k,n,utf8(m),utf8(p),null);}
    static byte[] packet(int k,long n,byte[] m,byte[] p,String h)throws Exception{
        byte[] hb=utf8(h==null?header(m,p,p.length>0):h);ByteBuffer b=ByteBuffer.allocate(24+hb.length+m.length+p.length+44);
        b.putInt(0x41504556).putShort((short)1).putShort((short)k).putLong(n).putInt(hb.length).putInt(m.length).put(hb).put(m).put(p);
        byte[] d=MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(b.array(),b.position()));b.putInt(0x4150434d).putLong(n).put(d);return b.array();
    }
    static byte[] hello()throws Exception{return packet(1,0,"{\"receipt\":\"aa.bb.cc\"}","");}
    static byte[] metadata(){return utf8("{\"receipt\":\"aa.bb.cc\"}");}
    static byte[] join(byte[]... a){int size=Arrays.stream(a).mapToInt(b->b.length).sum();byte[] out=new byte[size];int pos=0;for(byte[] b:a){System.arraycopy(b,0,out,pos,b.length);pos+=b.length;}return out;}
    static void rejects(byte[] b,String why)throws Exception{
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            assertThatThrownBy(()->d.readNext(new ByteArrayInputStream(b))).as(why).isInstanceOf(IOException.class);
            assertThatThrownBy(()->d.readNext(new ByteArrayInputStream(hello()))).isInstanceOf(IllegalStateException.class);
        }
    }
    static void rejectWindow(String m,String p,String why)throws Exception{
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            var in=new ByteArrayInputStream(join(hello(),packet(2,1,m,p)));d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(in)).as(why).isInstanceOf(IOException.class);
        }
    }
    static void acceptsWindow(String m,String p)throws Exception{
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            var in=new ByteArrayInputStream(join(hello(),packet(2,1,m,p)));d.readNext(in).orElseThrow().close();try(var packet=d.readNext(in).orElseThrow();var payload=packet.payload()){assertThat(payload.readAllBytes()).isEqualTo(utf8(p));}assertThat(d.readNext(in)).isEmpty();
        }
    }
    @Test void acceptedTwelveEncoderVectorsRemainUntrustedWithSmallReads()throws Exception{
        Path root=Files.exists(Path.of(".local/iam-o2b-f1-frozen-inputs.json"))?Path.of(""):Path.of("..");int packets=0,maxParents=0;Set<Integer> kinds=new HashSet<>();
        for(JsonNode ref:JSON.readTree(Files.readAllBytes(root.resolve(".local/iam-o2b-f1-frozen-inputs.json"))).get("vectors")){
            byte[] wire=Files.readAllBytes(root.resolve(ref.get("file").asText()));assertThat(wire.length).isEqualTo(ref.get("bytes").asInt());assertThat(sha(wire)).isEqualTo(ref.get("sha256").asText());
            try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
                InputStream in=new ByteArrayInputStream(wire){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(7,len));}};long ordinal=0;
                while(true){var next=d.readNext(in);if(next.isEmpty())break;try(var untrusted=next.orElseThrow()){
                    assertThat(untrusted.ordinal()).isEqualTo(ordinal++);kinds.add(untrusted.kind());packets++;
                    try(var m=untrusted.metadata();var p=untrusted.payload()){byte[] mb=m.readAllBytes(),pb=p.readAllBytes();assertThat(sha(mb)).isEqualTo(untrusted.header().metadataSha256());assertThat(sha(pb)).isEqualTo(untrusted.header().payloadSha256());maxParents=Math.max(maxParents,parentCount(JSON.readTree(mb)));}
                }}
            }
        }
        assertThat(packets).isEqualTo(25);assertThat(kinds).containsExactlyInAnyOrder(1,2,3);assertThat(maxParents).isGreaterThanOrEqualTo(65);
        // Receipt bytes are unverified: this test proves no association or authorization.
    }
    static int parentCount(JsonNode n){int max=n.isObject()&&n.has("parentScopeSha256")?n.get("parentScopeSha256").size():0;for(JsonNode c:n)max=Math.max(max,parentCount(c));return max;}
    @Test void magicFailureIsTerminal()throws Exception{byte[] b=hello();b[0]=0;rejects(b,"magic");}
    @Test void everyPartialSegmentFailsAndBoundaryEofIsEmpty()throws Exception{
        byte[] b=hello();int h=ByteBuffer.wrap(b).getInt(16),m=ByteBuffer.wrap(b).getInt(20);
        for(int n:new int[]{1,23,24,24+h-1,24+h,24+h+m-1,24+h+m,b.length-1})rejects(Arrays.copyOf(b,n),"cut "+n);
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){assertThat(d.readNext(new ByteArrayInputStream(EMPTY))).isEmpty();}
        byte[] w=packet(2,1,window(true,"{}"),"{\"x\":1}");int hp=ByteBuffer.wrap(w).getInt(16),mp=ByteBuffer.wrap(w).getInt(20);
        for(int n:new int[]{24+hp+mp,24+hp+mp+1,w.length-45})try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(b,Arrays.copyOf(w,n)));d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IOException.class);}
    }
    @Test void prefixOrderingAndAllDigestFieldsAreChecked()throws Exception{
        for(int at:new int[]{4,6,8,16,20}){byte[] b=hello();b[at]=(byte)0xff;rejects(b,"field "+at);}
        for(int at:new int[]{24,hello().length-44,hello().length-40,hello().length-1}){byte[] b=hello();b[at]^=1;rejects(b,"commit/header "+at);}
        for(String digest:new String[]{sha(metadata()),sha(EMPTY)})rejects(packet(1,0,metadata(),EMPTY,header(metadata(),EMPTY,false).replace(digest,SHA)),"segment SHA; COMMIT recalculated");
        for(byte[] second:new byte[][]{hello(),packet(2,2,window(false,"{}"),""),packet(2,0,window(false,"{}"),"")})try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),second));d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IOException.class);}
        rejects(packet(2,0,window(false,"{}"),""),"HELLO required");rejects(packet(1,Long.MAX_VALUE,"{\"receipt\":\"aa.bb.cc\"}",""),"ordinal initial/overflow");
        // Mechanical internal boundary setup avoids pretending to send 2^63 real packets.
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){
            var in=new ByteArrayInputStream(join(hello(),packet(2,Long.MAX_VALUE,window(false,"{}"),""),new byte[]{1}));d.readNext(in).orElseThrow().close();var expected=PortalEventsPacketDecoder.class.getDeclaredField("expectedOrdinal");expected.setAccessible(true);expected.setLong(d,Long.MAX_VALUE);d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IOException.class);
        }
    }
    @Test void strictJsonRejectsDuplicateUnknownTrailingAndEncodingsWithCorrectHashes()throws Exception{
        for(String m:new String[]{"{\"receipt\":\"aa.bb.cc\",\"receipt\":\"aa.bb.cc\"}","{\"receipt\":\"aa.bb.cc\",\"other\":1}","{\"receipt\":\"aa.bb.cc\"} {}","\ufeff{\"receipt\":\"aa.bb.cc\"}","{\"receipt\":\"\\uD800\"}","{\"receipt\":\"é.bb.cc\"}","{\"receipt\":\"a.b\"}","[]"})rejects(packet(1,0,m,""),m);
        for(byte[] m:new byte[][]{"{\"receipt\":\"aa.bb.cc\"}".getBytes(StandardCharsets.UTF_16BE),new byte[]{(byte)0xc0,(byte)0xaf},new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80}})rejects(packet(1,0,m,EMPTY,null),"UTF8 only");
        for(String p:new String[]{"{} {}","{\"x\":1,\"x\":2}","\"\\uDC00\"","\ufeffnull","[1,]"})rejectWindow(window(true,"{}"),p,p);
        rejectWindow(window(false,"{\"dynamic\":{\"k\":1,\"k\":2}}"),"","nested duplicate");
    }
    @Test void exactHeaderSchemaTypesAndScalarBoundaries()throws Exception{
        String h=header(metadata(),EMPTY,false);
        String[][] changes={{"\"child\"","\" \""},{"\""+RUN+"\"","\"r_X\""},{"\"resourceSha256\":\""+SHA+"\"","\"resourceSha256\":\"A"+"0".repeat(63)+"\""},{"\"afterSeq\":0","\"afterSeq\":-1"},{"\"initialCursor\":0","\"initialCursor\":9223372036854775808"},{"\"payloadBytes\":0","\"payloadBytes\":262145"},{"\"payloadBytes\":0","\"payloadBytes\":0.0"},{"\"eventSeq\":null","\"eventSeq\":0"},{"\"eventType\":null","\"eventType\":\" \""}};
        for(String[] c:changes)rejects(packet(1,0,metadata(),EMPTY,h.replace(c[0],c[1])),Arrays.toString(c));
        rejects(packet(1,0,metadata(),EMPTY,h.substring(0,h.length()-1)+",\"extra\":0}"),"unknown header");rejects(packet(1,0,metadata(),EMPTY,h.replace("\"child\"",quote("x".repeat(129)))),"child129");
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){d.readNext(new ByteArrayInputStream(packet(1,0,metadata(),EMPTY,h.replace("\"child\"",quote("x".repeat(128)))))).orElseThrow().close();}
        String receipt="a".repeat(4092)+".a.a";try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){d.readNext(new ByteArrayInputStream(packet(1,0,"{\"receipt\":"+quote(receipt)+"}",""))).orElseThrow().close();}rejects(packet(1,0,"{\"receipt\":"+quote(receipt+"a")+"}",""),"receipt4097");
        acceptsWindow(window(true,"{\"dynamic attributes\":{\"未知\":1}}"),"null");
    }
    @Test void windowRootShapeAndNullnessAreOnlyStructuralChecks()throws Exception{
        String m=window(false,"{}");
        for(String bad:new String[]{m.replace("\"owner\":{}","\"owner\":[]"),m.replace("\"hasEvent\":false","\"hasEvent\":0"),m.replace("\"scopes\":[]","\"scopes\":{}"),m.replace("\"eventIdentity\":null","\"eventIdentity\":{}"),m.replace("\"state\":{}","\"state\":null"),m.replace("\"runDbId\":1,",""),m.substring(0,m.length()-1)+",\"unknown\":null}"})rejectWindow(bad,"","root type/key");
        rejectWindow(window(true,"{}"),"","event needs header+bytes");rejectWindow(m,"null","noevent has no payload");acceptsWindow(window(true,"{}"),"null");acceptsWindow(m,"");
    }
    @Test void capacityHasExactCodeAndEndsResponse()throws Exception{
        for(String code:new String[]{"BUFFER_LIMIT","EVENT_LIMIT"})try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),packet(3,1,"{\"code\":\""+code+"\"}","")));d.readNext(in).orElseThrow().close();try(var p=d.readNext(in).orElseThrow()){assertThat(p.kind()).isEqualTo(3);}assertThat(d.readNext(in)).isEmpty();}
        for(String m:new String[]{"{\"code\":\"OTHER\"}","{\"code\":\"BUFFER_LIMIT\",\"x\":0}"})try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),packet(3,1,m,"")));d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IOException.class);}
    }
    static int tokens(String s)throws Exception{int n=0;try(var p=JSON.getFactory().createParser(s)){while(p.nextToken()!=null)n++;}return n;}
    @Test void exactStructureDepthTokenStringNameAndNumberLimits()throws Exception{
        acceptsWindow(window(false,"{\"x\":"+"[".repeat(78)+"0"+"]".repeat(78)+"}"),"");rejectWindow(window(false,"{\"x\":"+"[".repeat(79)+"0"+"]".repeat(79)+"}"),"","metadata depth81");
        acceptsWindow(window(true,"{}"),"[".repeat(64)+"0"+"]".repeat(64));rejectWindow(window(true,"{}"),"[".repeat(65)+"0"+"]".repeat(65),"payload depth65");
        String max="x".repeat(1048576);acceptsWindow(window(false,"{\"x\":"+quote(max)+"}"),"");rejectWindow(window(false,"{\"x\":"+quote(max+"x")+"}"),"","string+1");
        acceptsWindow(window(false,"{"+quote(max)+":0}"),"");rejectWindow(window(false,"{"+quote(max+"x")+":0}"),"","name+1");
        String number="1".repeat(1000);acceptsWindow(window(false,"{\"n\":"+number+"}"),"");rejectWindow(window(false,"{\"n\":"+number+"1}"),"","number1001");
        String skeleton=window(false,"{\"x\":[]}");int count=262144-tokens(skeleton);String full=skeleton.replace("\"x\":[]","\"x\":["+"0,".repeat(count-1)+"0]");assertThat(tokens(full)).isEqualTo(262144);acceptsWindow(full,"");rejectWindow(full.replace("\"x\":[","\"x\":[0,"),"","tokens+1 includes ends");
        acceptsWindow(window(true,"{}"),"["+"0,".repeat(131070)+"0]");
    }
    @Test void maximumWireAndUnsignedLengthLimits()throws Exception{
        String m=window(true,"{}");m+=" ".repeat(8388608-utf8(m).length);String p="\""+"x".repeat(262142)+"\"",h=header(utf8(m),utf8(p),true);h+=" ".repeat(8192-utf8(h).length);byte[] w=packet(2,1,utf8(m),utf8(p),h);assertThat(w.length).isEqualTo(8659012);
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),w));d.readNext(in).orElseThrow().close();d.readNext(in).orElseThrow().close();}
        for(int at:new int[]{16,20})for(int n:new int[]{0,at==16?8193:8388609,-1}){byte[] b=hello();ByteBuffer.wrap(b).putInt(at,n);rejects(b,"unsigned length "+at+":"+Integer.toUnsignedLong(n));}
    }
    @Test void eventTypeUtf16AndHeaderIntegerLimitsPreserveOriginalText()throws Exception{
        byte[] m=utf8(window(true,"{}")),p=utf8("null");String normal="事件 类型 "+"界".repeat(32),h=header(m,p,true);
        String unicode="界".repeat(32);assertThat(utf8(unicode)).hasSize(96);
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),packet(2,1,m,p,h.replace(quote(normal),quote(unicode)))));d.readNext(in).orElseThrow().close();try(var packet=d.readNext(in).orElseThrow()){assertThat(packet.header().eventType()).isEqualTo(unicode);}}
        String exact=h.replace(quote(normal),quote("x".repeat(4096)));byte[] wire=packet(2,1,m,p,exact);
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),wire));d.readNext(in).orElseThrow().close();try(var packet=d.readNext(in).orElseThrow()){assertThat(packet.header().eventType()).hasSize(4096);}}
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){var in=new ByteArrayInputStream(join(hello(),packet(2,1,m,p,h.replace(quote(normal),quote("x".repeat(4097))))));d.readNext(in).orElseThrow().close();assertThatThrownBy(()->d.readNext(in)).isInstanceOf(IOException.class);}
        String max=header(metadata(),EMPTY,false).replace("\"initialCursor\":0","\"initialCursor\":9223372036854775807").replace("\"afterSeq\":0","\"afterSeq\":9223372036854775807");
        try(var o=PortalEventsPacketBudget.SHARED.acquire();var d=new PortalEventsPacketDecoder(o)){try(var packet=d.readNext(new ByteArrayInputStream(packet(1,0,metadata(),EMPTY,max))).orElseThrow()){assertThat(packet.header().afterSeq()).isEqualTo(Long.MAX_VALUE);}}
        rejects(packet(1,0,metadata(),EMPTY,max.replace("9223372036854775807","19223372036854775807")),"20 digit integer");
    }
}
