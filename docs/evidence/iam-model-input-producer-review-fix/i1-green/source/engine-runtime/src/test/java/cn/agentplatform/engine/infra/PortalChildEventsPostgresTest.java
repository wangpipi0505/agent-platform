package cn.agentplatform.engine.infra;

import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/** E checks reuse the real admitted C DAG writer, never manufactured scope fixtures. */
public class PortalChildEventsPostgresTest {
    public static ChunkBus chunks(){return new ChunkBus(2,8192);}
    public static int frames(ChunkBus chunks)throws Exception{return PortalRunEventsLifecycleTest.availableFrames(chunks);}
    public record Packet(int kind,long ordinal,JsonNode header,JsonNode metadata,byte[] payload){}
    /** Only checks our encoder's own output. Malicious input decoder matrix belongs to F. */
    public static List<Packet> packets(byte[] wire)throws Exception{
        var result=new ArrayList<Packet>();var in=ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);long ordinal=0;
        while(in.hasRemaining()){
            int start=in.position();assertThat(in.getInt()).isEqualTo(0x41504556);assertThat(in.getShort()).isEqualTo((short)1);int kind=in.getShort();long sequence=in.getLong();assertThat(sequence).isEqualTo(ordinal++);int headerBytes=in.getInt(),metadataBytes=in.getInt();
            assertThat(headerBytes).isBetween(1,8192);assertThat(metadataBytes).isBetween(1,8388608);
            byte[] header=new byte[headerBytes];in.get(header);var h=Json.read(new String(header,StandardCharsets.UTF_8));assertThat(h.size()).isEqualTo(10);
            byte[] metadata=new byte[metadataBytes];in.get(metadata);var m=Json.read(new String(metadata,StandardCharsets.UTF_8));
            byte[] payload=new byte[h.path("payloadBytes").intValue()];in.get(payload);int end=in.position();
            assertThat(PortalEventsPacketWriter.hex(PortalEventsPacketWriter.sha().digest(metadata))).isEqualTo(h.path("metadataSha256").asText());assertThat(PortalEventsPacketWriter.hex(PortalEventsPacketWriter.sha().digest(payload))).isEqualTo(h.path("payloadSha256").asText());
            assertThat(in.getInt()).isEqualTo(0x4150434d);assertThat(in.getLong()).isEqualTo(sequence);byte[] digest=new byte[32];in.get(digest);assertThat(digest).isEqualTo(PortalEventsPacketWriter.sha().digest(Arrays.copyOfRange(wire,start,end)));
            result.add(new Packet(kind,sequence,h,m,payload));
        }return result;
    }
    static byte[] encode(Fixture f,PortalEventsResponse child,long after,String vector)throws Exception{
        var bytes=new ByteArrayOutputStream();try(var e=f.reader.readChildWindow(child,after,f.auth.engine)){e.consume(v->{try{long hello=v.writePrivatePacket(bytes,0,true);long peak=v.writePrivatePacket(bytes,1,false);assertThat(hello).isLessThanOrEqualTo(192L*1024*1024);assertThat(peak).isLessThanOrEqualTo(192L*1024*1024);}catch(IOException failure){throw new UncheckedIOException(failure);}});}
        byte[] result=bytes.toByteArray();saveVector(vector,result);return result;
    }
    public static void saveVector(String vector,byte[] bytes)throws IOException{var path=Path.of("..","docs","evidence","iam-stage-4-o2b-e-engine-packet","vectors",vector+"-"+UUID.randomUUID()+".bin");Files.createDirectories(path.getParent());Files.write(path,bytes);}
    @Test void capacityVectorsUseActualChildAndContainNoMaterialFacts()throws Exception{
        try(var f=new Fixture()){var b=f.seed();try(var s=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=s.open()){
            // Encoder vectors inject the reason, not an authorization. Actual EVENT_LIMIT
            // production selection is independently checked by the HTTP/writer test.
            for(String code:List.of("BUFFER_LIMIT","EVENT_LIMIT")){var out=new ByteArrayOutputStream();try(var e=f.reader.readChildWindow(child,0,f.auth.engine)){e.consume(v->{try{v.writePrivatePacket(out,0,true);PortalEventsPacketWriter.capacity(out,child,f.auth.engine,1,0,code);}catch(IOException x){throw new UncheckedIOException(x);}});}saveVector("capacity-"+code,out.toByteArray());var packets=packets(out.toByteArray());assertThat(packets).hasSize(2);var cap=packets.getLast();assertThat(cap.kind()).isEqualTo(3);assertThat(cap.metadata().size()).isEqualTo(1);assertThat(cap.metadata().path("code").asText()).isEqualTo(code);assertThat(cap.header().path("eventSeq").isNull()).isTrue();assertThat(cap.header().path("eventType").isNull()).isTrue();assertThat(cap.payload()).isEmpty();}
        }}
    }
    @Test void helloAfterIsInitialCursorEvenWhenBorrowDescribesALaterWindow()throws Exception{
        try(var f=new Fixture()){var b=f.seed();try(var s=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=s.open()){
            var p=packets(encode(f,child,1,"hello-initial-cursor"));assertThat(p.getFirst().header().path("afterSeq").longValue()).isEqualTo(0);assertThat(p.getFirst().header().path("initialCursor").longValue()).isEqualTo(0);assertThat(p.get(1).header().path("afterSeq").longValue()).isEqualTo(1);
        }}
    }
    @Test void completeSameRrUnionHasAtLeast65ParentsFullProvenanceAndBusinessDigests()throws Exception{
        try(var f=new Fixture()){var b=f.seed();try(var session=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=session.open()){
            f.observe();byte[] wire=encode(f,child,0,"event");int sql=f.delegate.source.sql.size();f.delegate.source.observe=false;var packets=packets(wire);assertThat(packets).hasSize(2);assertThat(packets.getFirst().kind()).isEqualTo(1);session.parent.acceptChildReceipt(packets.getFirst().metadata().path("receipt").asText());
            var p=packets.get(1);var m=p.metadata();assertThat(m.size()).isEqualTo(14);assertThat(m.path("provenance").size()).isEqualTo(8);assertThat(m.path("provenance").path("grantRef").asText()).isEqualTo(b.delegation().value());assertThat(m.path("provenance").path("operationId").asText()).isEqualTo(b.operationId());assertThat(m.path("provenance").path("originAuthenticationId").asText()).isEqualTo(b.originId());
            assertThat(m.path("state").size()).isEqualTo(11);assertThat(m.path("eventIdentity").size()).isEqualTo(5);assertThat(m.path("state").path("id").longValue()).isEqualTo(b.runId());assertThat(m.path("initialCeilingSha256").asText()).isEqualTo(MaterialAuthorization.ceilingSha256(Json.read(m.path("initialCeiling").toString(),Actor.class)));
            var bySha=new HashMap<String,JsonNode>();var revisions=new HashSet<Long>();long previous=0;
            for(var entry:m.path("scopes")){assertThat(entry.size()).isEqualTo(2);long revision=entry.path("revision").longValue();assertThat(revision).isGreaterThan(previous);previous=revision;assertThat(revisions.add(revision)).isTrue();var scope=entry.path("scope");var s=RunEventReader.scope(scope);assertThat(s.scopeSha256()).isEqualTo(MaterialAuthorization.scopeDigest(s));assertThat(bySha.put(s.scopeSha256(),scope)).isNull();var stored=Json.read(f.db.queryForObject("select scope::text from engine.material_scope where run_id=? and scope_sha256=?",String.class,b.runId(),s.scopeSha256()));assertThat(s).isEqualTo(RunEventReader.scope(stored));assertThat(scope.path("parentScopeSha256")).isEqualTo(stored.path("parentScopeSha256"));}
            var stateScope=bySha.get(m.path("stateScope").asText());assertThat(stateScope.path("parentScopeSha256").size()).isGreaterThanOrEqualTo(65);
            var expected=new HashSet<String>();m.path("rootScopes").properties().forEach(e->expected.add(e.getValue().asText()));expected.add(m.path("stateScope").asText());stateScope.path("parentScopeSha256").forEach(n->expected.add(n.asText()));expected.add(m.path("eventScope").asText());bySha.get(m.path("eventScope").asText()).path("parentScopeSha256").forEach(n->expected.add(n.asText()));assertThat(bySha.keySet()).isEqualTo(expected);
            assertThat(stateScope.path("material").path("payloadSha256").asText()).isEqualTo(MaterialScopeStore.observedDigestMeasured(m.path("state")).sha());var row=m.path("eventIdentity").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)row).set("payload",Json.read(new String(p.payload(),StandardCharsets.UTF_8)));assertThat(bySha.get(m.path("eventScope").asText()).path("material").path("payloadSha256").asText()).isEqualTo(MaterialScopeStore.observedDigestMeasured(row).sha());
            assertThat(f.delegate.source.sql).hasSize(sql);assertThat(f.delegate.source.observations.stream().map(s->s.substring(0,s.indexOf(':'))).distinct().count()).isEqualTo(1);assertThat(f.delegate.source.observations).allMatch(s->s.endsWith(":4:true:false"));
        }}
    }
    @Test void emptyNullTerminalAndCursorResetVectorsRemainDistinct()throws Exception{
        try(var f=new Fixture()){var b=f.seed();
            try(var session=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),1);var child=session.open()){var p=packets(encode(f,child,1,"initial-empty")).get(1);assertThat(p.metadata().path("hasEvent").booleanValue()).isFalse();assertThat(p.payload()).isEmpty();assertThat(p.header().path("eventSeq").isNull()).isTrue();}
            f.append(b.runId(),"type:允许\n原值",null);
            try(var session=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),1);var child=session.open()){var p=packets(encode(f,child,1,"json-null")).get(1);assertThat(p.metadata().path("hasEvent").booleanValue()).isTrue();assertThat(p.payload()).isEqualTo("null".getBytes(StandardCharsets.UTF_8));assertThat(p.header().path("eventType").asText()).isEqualTo("type:允许\n原值");}
            f.finish(b.runId());
            for(long after:new long[]{3,4})try(var session=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),after);var child=session.open()){var p=packets(encode(f,child,after,after==3?"terminal-empty":"cursor-ahead")).get(1);assertThat(p.metadata().path("hasEvent").booleanValue()).isFalse();assertThat(p.payload()).isEmpty();assertThat(p.metadata().path("state").path("status").asText()).isEqualTo("FAILED");}
            f.db.update("delete from engine.run_event where run_id=?",b.runId());
            try(var session=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=session.open()){var p=packets(encode(f,child,0,"cursor-expired")).get(1);assertThat(p.payload()).isEmpty();assertThat(p.metadata().path("window").path("oldest").isNull()).isTrue();}
        }
    }
    @Test void realConcurrentWriterIsOnlyVisibleInNextSnapshotAndNoEncodingSqlExists()throws Exception{
        try(var f=new Fixture()){var b=f.seed();try(var session=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=session.open()){
            var once=new AtomicBoolean();f.observe();f.delegate.source.onQuery=q->{if(q.contains("engine.material_scope")&&once.compareAndSet(false,true))PortalRunEventWindowPostgresTest.outside(()->f.finish(b.runId()));};
            var first=packets(encode(f,child,0,"same-rr-before-terminal")).get(1);assertThat(first.metadata().path("state").path("status").asText()).isEqualTo("PENDING");assertThat(first.metadata().path("window").path("watermark").longValue()).isEqualTo(1);f.delegate.source.onQuery=q->{};
            var next=packets(encode(f,child,1,"same-rr-after-terminal")).get(1);assertThat(next.metadata().path("state").path("status").asText()).isEqualTo("FAILED");assertThat(next.header().path("eventSeq").longValue()).isEqualTo(2);
        }}
    }
    @Test void narrowedCurrentTamperedStateAndWrongParentsFailBeforeHello()throws Exception{
        try(var f=new Fixture()){var b=f.seed();
            f.auth.narrow();try(var s=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=s.open()){var out=new ByteArrayOutputStream();assertThatThrownBy(()->{try(var e=f.reader.readChildWindow(child,0,f.auth.engine)){e.consume(v->{try{v.writePrivatePacket(out,0,true);}catch(IOException x){throw new UncheckedIOException(x);}});}}).isInstanceOf(PlatformException.class);assertThat(out.size()).isZero();}finally{f.auth.resetCoverage();}
            for(String kind:List.of("state","parent"))try(var s=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=s.open()){
                f.observe();f.delegate.source.change=(k,v)->kind.equals("state")&&k.equals("state_text")?v.toString().replace("\"output_ref\":null","\"output_ref\":\"tampered\""):kind.equals("parent")&&k.equals("scope_text")?v.toString().replace("\"parentScopeSha256\": [","\"parentScopeSha256\": [\""+"0".repeat(64)+"\","):v;
                assertThatThrownBy(()->f.reader.readChildWindow(child,0,f.auth.engine)).isInstanceOf(PlatformException.class);
            }finally{f.delegate.source.change=(k,v)->v;}
        }
    }
    @Test void outputGuardReentrySinkMutationAndFailureCannotCommitOrReleaseEarly()throws Exception{
        try(var f=new Fixture()){var b=f.seed();try(var s=new PortalChildEventsAuthorityFixture(f.auth,b.owner(),b.publicId(),b.snapshotSha(),0);var child=s.open()){
            try(var e=f.reader.readChildWindow(child,0,f.auth.engine)){e.consume(v->{try{
                var actual=new ByteArrayOutputStream();var sink=new OutputStream(){boolean attempted;public void write(int b){throw new AssertionError();}public void write(byte[] bytes,int offset,int length)throws IOException{if(!attempted){attempted=true;assertThatThrownBy(()->v.writePayload(OutputStream.nullOutputStream())).isInstanceOf(PlatformException.class);assertThatThrownBy(()->v.writePrivatePacket(OutputStream.nullOutputStream(),0,true)).isInstanceOf(PlatformException.class);}actual.write(bytes,offset,length);Arrays.fill(bytes,(byte)0);}};
                v.writePrivatePacket(sink,0,true);v.writePrivatePacket(sink,1,false);assertThat(packets(actual.toByteArray())).hasSize(2);
            }catch(Exception x){throw new RuntimeException(x);}});}
            var partial=new ByteArrayOutputStream();try(var e=f.reader.readChildWindow(child,0,f.auth.engine)){assertThatThrownBy(()->e.consume(v->{try{v.writePrivatePacket(new OutputStream(){public void write(int b){}public void write(byte[] bytes,int o,int n)throws IOException{partial.write(bytes,o,n);throw new IOException("blocked sink failed");}},0,true);}catch(IOException x){throw new UncheckedIOException(x);}})).isInstanceOf(UncheckedIOException.class);}assertThat(new String(partial.toByteArray(),StandardCharsets.ISO_8859_1)).doesNotContain("APCM");
        }}
    }
    public static final class Fixture implements AutoCloseable {
        final PortalRunEventWindowPostgresTest.Fixture delegate;
        public final RunEventReaderAuthorityFixture auth;
        public final DataSource source;
        public final JdbcTemplate db;
        public final TraceStore trace;
        public final RunEventReader reader;
        public Fixture()throws Exception{delegate=new PortalRunEventWindowPostgresTest.Fixture();auth=delegate.auth;source=delegate.source;db=delegate.db;trace=delegate.trace;reader=delegate.reader;}
        public RunAuthorityStore.Binding seed(){return delegate.seed(0);}
        public void finish(long run){delegate.finish(run);}
        public void append(long run,String type,Object value){delegate.append(run,type,value);}
        public void observe(){delegate.source.start();}
        public java.util.List<String> getters(){return delegate.source.getters;}
        public void close(){delegate.close();}
    }
}
