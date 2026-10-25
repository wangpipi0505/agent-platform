package cn.agentplatform.engine.infra;

import static cn.agentplatform.engine.infra.RunAuthorityStore.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.domain.GraphRules;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One unregistered finite PG owner. C must share this owner; old status/writer loaders are not finite event plans. */
public final class RunEventReader {
    static final long JSON_BYTES=1048576,METADATA_BYTES=8388608,ROOT_BYTES=8388608,CONTROL_BYTES=262144;
    static final int MAX_RECORDS=4096,MAX_PARENTS=4096,EVENT_BYTES=262144;
    // Root's budget-decision: these are additional intersecting representation limits. Model
    // 64MiB includes 512/token for DOM+DTO+normalization/sort/collection backing slack and 8/raw
    // byte for decoded strings/full parent arrays. Before fetching each text reserve 6/raw byte
    // for JDBC UTF-8 row, decoded UTF-16, raw length-check copy and driver slack; max48MiB.
    // Streaming preflight+DOM parser scratch reserves64MiB (duplicate detection/name/text buffers
    // and segment/consolidation slack). Canonical<=8MiB reserves5x for UTF-16 segments/result and
    // UTF-8 digest copy, max40MiB. Fixed scalar plan/maps reserve8MiB. Parser and canonical phases
    // never overlap: parsed DOM returns after both parsers close, before canonicalScratch begins.
    // Their worst phases are64+48+64+8=184MiB and64+48+40+8=160MiB. The total ledger still caps
    // all reservations at192MiB before allocation. Two permits
    // therefore own <=384MiB accounted representations; this is not an exact object-heap claim.
    static final long MODEL_UNITS=64L*1024*1024,REPRESENTATION_UPPER_PER_WORKSPACE=192L*1024*1024;
    static final class Ledger {
        long current,peak;
        final class Reservation implements AutoCloseable {
            final long bytes;boolean closed;Reservation(long bytes){this.bytes=bytes;}
            public void close(){if(!closed){closed=true;current-=bytes;}}
        }
        Reservation reserve(long bytes){current=Limits.add(current,bytes,REPRESENTATION_UPPER_PER_WORKSPACE,"REPRESENTATION_TOTAL");peak=Math.max(peak,current);return new Reservation(bytes);}
    }
    private final DataSource source;private final JdbcTemplate db;
    private final Semaphore workspaces=new Semaphore(2);
    public RunEventReader(DataSource source){this.source=Objects.requireNonNull(source);db=new JdbcTemplate(source);}
    public Optional<EventEmission> readNext(PortalEventsResponse response,long afterSeq){
        long entered=System.nanoTime();
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("EVENT_READER_REQUIRES_NO_CALLER_TRANSACTION");
        var capture=Objects.requireNonNull(response).currentCapture();
        if(afterSeq<0||afterSeq<capture.initialCursor())throw denied();
        var work=new Work(db,response,capture,entered);work.millis();
        if(!workspaces.tryAcquire())throw finite("WORKSPACE");
        var lease=new Lease(workspaces);boolean handed=false;Throwable primary=null;
        try{
            clearCurrentThreadBuffers();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            // Spring6.2.7 doBegin/doCommit owns this connection. Seconds timeouts cannot express
            // the signed deadline; actual SQL uses floor(ms) of the same conservative anchor.
            var data=tx.execute(status->read(work,afterSeq,false).event());work.live();
            if(data==null)return Optional.empty();
            // Cleanup is before mint/transfer, so a cleanup failure cannot strand a pending permit.
            clearCurrentThreadBuffers();work.live();
            var emission=new EventEmission(response,capture,lease,work.ledger,data.info(),data.payload(),data.labels());
            var result=Optional.of(emission);handed=true;return result;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{if(!handed)try{cleanup(primary);}finally{lease.close();}}
    }
    private record EventData(Info info,byte[] payload,Set<String> labels){}
    private record ReadData(EventData event,WindowInfo window,PacketMaterial packet){ReadData(EventData event,WindowInfo window){this(event,window,null);}}
    /** Only the actual child export enables this mint; ordinary B/C callers retain their API. */
    public WindowEmission readChildWindow(PortalEventsResponse response,long afterSeq,HttpCurrentAuthorityClient authority){
        String receipt=Objects.requireNonNull(authority).portalEventsChildReceipt(response);
        return readWindow(response,afterSeq,receipt);
    }
    /** Same finite owner and top-level RR as readNext; empty still carries verified state here. */
    public WindowEmission readWindow(PortalEventsResponse response,long afterSeq){
        return readWindow(response,afterSeq,null);
    }
    private WindowEmission readWindow(PortalEventsResponse response,long afterSeq,String receipt){
        long entered=System.nanoTime();
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("EVENT_READER_REQUIRES_NO_CALLER_TRANSACTION");
        var capture=Objects.requireNonNull(response).currentCapture();
        if(afterSeq<0||afterSeq<capture.initialCursor())throw denied();
        var work=new Work(db,response,capture,entered,true);work.privateReceipt=receipt;work.millis();
        if(!workspaces.tryAcquire())throw finite("WORKSPACE");
        var lease=new Lease(workspaces);boolean handed=false;Throwable primary=null;
        try{
            clearCurrentThreadBuffers();
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var data=tx.execute(status->read(work,afterSeq,true));work.live();
            clearCurrentThreadBuffers();work.live();
            var event=data.event();
            var owner=new EventEmission(response,capture,lease,work.ledger,event==null?null:event.info(),event==null?null:event.payload(),event==null?Set.of():event.labels());
            owner.window=true;owner.packet=data.packet();var result=new WindowEmission(owner,data.window());handed=true;return result;
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{if(!handed)try{cleanup(primary);}finally{lease.close();}}
    }
    public record WindowInfo(String runId,String status,long watermark,Long oldest,long afterSeq){
        public String resetReason(){return afterSeq>watermark?"CURSOR_AHEAD":(oldest==null?afterSeq<watermark:afterSeq<oldest-1)?"CURSOR_EXPIRED":null;}
        public boolean drained(){return Set.of("SUCCEEDED","FAILED","CANCELLED").contains(status)&&afterSeq>=watermark;}
    }
    /** A value is descriptive only; only this private owner can authorize a synchronous borrow. */
    public static final class WindowEmission implements AutoCloseable{
        private final EventEmission owner;private final WindowInfo window;
        private WindowEmission(EventEmission owner,WindowInfo window){this.owner=owner;this.window=window;}
        public void consume(Consumer<WindowBorrow> action){owner.consume(borrow->action.accept(new WindowBorrow(borrow,window)));}
        /** C's actual exit runs after either guard/action failure, before the original lease
         * finally. Ordinary B borrows retain their existing consume/close contract. */
        public void consume(Consumer<WindowBorrow> action,Consumer<Throwable> beforeRelease){
            Objects.requireNonNull(action);owner.consumeWindow(borrow->action.accept(new WindowBorrow(borrow,window)),beforeRelease);
        }
        public void close(){owner.close();}
    }
    public static final class WindowBorrow{
        private final Borrow event;private final WindowInfo window;
        private WindowBorrow(Borrow event,WindowInfo window){this.event=event;this.window=window;}
        public WindowInfo window(){event.requireLive();return window;}
        public boolean hasEvent(){event.requireLive();return event.emission.info!=null;}
        public Info info(){if(!hasEvent())throw denied();return event.info();}
        public void requireLive(){event.requireLive();}
        public void writePayload(OutputStream output)throws IOException{if(!hasEvent())throw denied();event.writePayload(output);}
        /** No model/array leaves this synchronous guarded exit, shared with payload output. */
        public long writePrivatePacket(OutputStream output,long ordinal,boolean hello)throws IOException{
            event.beginOutput();try{if(event.emission.packet==null)throw denied();PortalEventsPacketWriter.write(event.emission.packet,event.emission.capture,event.emission.ledger,event.emission.payload,this::requireLive,output,ordinal,hello);return event.emission.ledger.peak;}
            finally{event.outputActive=false;}
        }
    }
    /** Raised only by an exact local capture liveness guard, never by material/digest rejection. */
    public static final class Superseded extends RuntimeException{private Superseded(){super("EVENT_CAPTURE_SUPERSEDED");}}
    private static void live(PortalEventsResponse response,PortalEventsResponse.Capture capture,boolean window){
        try{response.requireLive(capture);}catch(PlatformException failure){
            if(window&&response.isSuperseded(capture))throw new Superseded();throw failure;
        }
    }
    private ReadData read(Work w,long afterSeq,boolean window){
        var b=RunAuthorityStore.loadObservationBindingBounded(w,w.capture.subject(),w.capture.resource().id());
        same(w.capture.resource(),RunObservationSelectors.status(b.publicId(),b.snapshotSha()));
        var roots=MaterialScopeStore.probeObserved(w,b.runId(),"kind in ('run-snapshot','run-inputs','run-version-refs')",4);
        if(roots.size()!=3)throw denied();
        var events=w.query("select id,seq,octet_length(type) as type_bytes,case when octet_length(type)<=262144 then type end as event_type from engine.run_event where run_id=? and seq>? order by seq limit 1",1,b.runId(),afterSeq);
        MaterialScopeStore.ObservedRow selected=null;MaterialScopeV1 selectedScope=null;
        var plan=new LinkedHashMap<String,MaterialScopeStore.ObservedRow>();
        for(var root:roots)MaterialScopeStore.addObserved(plan,root,w.limits);
        Map<String,Object> event=events.isEmpty()?null:events.getFirst();
        if(event!=null){
            if(num(event,"seq")<=afterSeq)throw denied();Limits.single(num(event,"type_bytes"),CONTROL_BYTES,"CONTROL_BYTES");nonblank((String)event.get("event_type"));
            var rows=MaterialScopeStore.probeObserved(w,b.runId(),"kind='run-event' and material_id=?",2,Long.toString(num(event,"id")));
            if(rows.size()!=1)throw denied();selected=rows.getFirst();MaterialScopeStore.addObserved(plan,selected,w.limits);
            selectedScope=MaterialScopeStore.readObservedScope(w,b,selected);var parents=selectedScope.parentScopeSha256();if(parents.isEmpty())throw denied();
            var args=new ArrayList<Object>();args.add(selected.revision());args.addAll(parents);
            var found=MaterialScopeStore.probeObserved(w,b.runId(),"material_revision<? and scope_sha256 in ("+String.join(",",Collections.nCopies(parents.size(),"?"))+")",MAX_RECORDS+1,args.toArray());
            var hashes=new HashSet<String>();for(var parent:found){if(!hashes.add(parent.sha())||!parents.contains(parent.sha()))throw denied();MaterialScopeStore.addObserved(plan,parent,w.limits);}same(hashes,new HashSet<>(parents));
        }
        MaterialScopeStore.ObservedRow stateRow=null;MaterialScopeV1 stateScope=null;
        if(window){
            var rows=MaterialScopeStore.probeObserved(w,b.runId(),"kind='run-state' and material_revision=(select max(material_revision) from engine.material_scope where run_id="+b.runId()+" and kind='run-state')",2);
            if(rows.size()!=1)throw denied();stateRow=rows.getFirst();MaterialScopeStore.addObserved(plan,stateRow,w.limits);
            stateScope=MaterialScopeStore.readObservedScope(w,b,stateRow);
            var parents=stateScope.parentScopeSha256();if(parents.isEmpty())throw denied();
            var args=new ArrayList<Object>();args.add(stateRow.revision());args.addAll(parents);
            var found=MaterialScopeStore.probeObserved(w,b.runId(),"material_revision<? and scope_sha256 in ("+String.join(",",Collections.nCopies(parents.size(),"?"))+")",MAX_RECORDS+1,args.toArray());
            var hashes=new HashSet<String>();for(var parent:found){if(!hashes.add(parent.sha())||!parents.contains(parent.sha()))throw denied();MaterialScopeStore.addObserved(plan,parent,w.limits);}same(hashes,new HashSet<>(parents));
        }
        // Probe and sum complete metadata, including every repeated parent array, before payload.
        var scopes=new LinkedHashMap<String,MaterialScopeV1>();
        for(var row:plan.values()){var scope=row==selected?selectedScope:row==stateRow?stateScope:MaterialScopeStore.readObservedScope(w,b,row);scopes.put(row.sha(),scope);w.material(scope,row.bytes());w.live();}
        var rootScopes=roots.stream().map(r->scopes.get(r.sha())).toList();
        try(var canonical=w.canonicalScratch(6*roots.stream().mapToLong(MaterialScopeStore.ObservedRow::bytes).max().orElseThrow())){b=MaterialScopeStore.requireObservedRoots(b,rootScopes);}
        boolean missingHistoricalEvent=false;
        for(var row:plan.values()){
            // Only a real direct-parent role can reach this helper. A selected event always
            // wins consumption priority, even when it is also a run-state historical parent.
            if(window&&row!=selected&&row.kind().equals("run-event")&&(stateScope.parentScopeSha256().contains(row.sha())||selectedScope!=null&&selectedScope.parentScopeSha256().contains(row.sha())))
                missingHistoricalEvent|=MaterialScopeStore.observedHistoricalEventMissing(w,b,row);
            else MaterialScopeStore.requireObservedLocator(w,b,row);
        }
        if(window)MaterialScopeStore.requireObservedFloor(stateScope,b,stateScope.parentScopeSha256().stream().map(scopes::get).toList());
        if(selected!=null)MaterialScopeStore.requireObservedFloor(selectedScope,b,selectedScope.parentScopeSha256().stream().map(scopes::get).toList());
        w.live();
        // The inputs root is proved but its payload is never consumed by this reader.
        var probe=w.query("select octet_length(snapshot::text) as snapshot_bytes,jsonb_typeof(snapshot) as snapshot_type,octet_length(version_refs::text) as refs_bytes,jsonb_typeof(version_refs) as refs_type from engine.run where id=?",1,b.runId()).getFirst();
        same("object",probe.get("snapshot_type"));same("object",probe.get("refs_type"));w.limits.root(num(probe,"snapshot_bytes"));w.limits.root(num(probe,"refs_bytes"));
        long stateBytes=window?MaterialScopeStore.probeObservedState(w,b):0;
        if(window)w.limits.root(stateBytes); // State intersects the original root aggregate, not a new allowance.
        var snapshot=MaterialScopeStore.readObservedRoot(w,b.runId(),"snapshot",num(probe,"snapshot_bytes"));
        same(b.snapshotSha(),w.digest(snapshot));w.live();
        var refs=MaterialScopeStore.readObservedRoot(w,b.runId(),"version_refs",num(probe,"refs_bytes"));
        for(var scope:rootScopes){String kind=scope.material().kind();if(!kind.equals("run-inputs"))same(scope.material().payloadSha256(),w.digest(kind.equals("run-snapshot")?snapshot:refs));}
        TraceStore.requireSnapshotRelations(b,snapshot,refs);GraphRules.requirePublishedFlow(snapshot);
        if(!"FLOW".equals(text(snapshot,"type"))||snapshot.hasNonNull("debugSnapshot")||snapshot.hasNonNull("evalCandidate")||snapshot.hasNonNull("controlledDraftId")||snapshot.path("config").hasNonNull("dataSources")||refs.has("ontology")&&(!refs.path("ontology").isArray()||!refs.path("ontology").isEmpty()))throw denied();
        WindowInfo facts=null;JsonNode state=null;
        if(window){
            state=MaterialScopeStore.readObservedState(w,b,stateScope,stateBytes);
            if(!state.path("event_seq").isIntegralNumber()||!state.path("event_seq").canConvertToLong()||state.path("event_seq").longValue()<0)throw denied();
            String status=text(state,"status");long watermark=state.path("event_seq").longValue();
            if(missingHistoricalEvent&&!Set.of("SUCCEEDED","FAILED","CANCELLED").contains(status))throw denied();
            var minimum=w.query("select min(seq) as oldest_seq from engine.run_event where run_id=?",1,b.runId()).getFirst().get("oldest_seq");
            Long oldest=minimum==null?null:minimum instanceof Number n?n.longValue():null;
            if(minimum!=null&&(oldest==null||oldest<1||oldest>watermark)||event!=null&&num(event,"seq")>watermark)throw denied();
            facts=new WindowInfo(b.publicId(),status,watermark,oldest,afterSeq);
        }
        w.live();if(event==null)return new ReadData(null,facts,packet(w,b,plan,scopes,stateScope,null,state,null,facts)); // B empty never claims terminal/window.
        var value=MaterialScopeStore.readObservedEvent(w,b,num(event,"id"),num(event,"seq"),selectedScope);
        same(value.path("run_id").longValue(),b.runId());same(value.path("id").longValue(),num(event,"id"));same(value.path("seq").longValue(),num(event,"seq"));same(text(value,"type"),event.get("event_type"));
        byte[] payload;
        try(var encoding=w.canonicalScratch(6L*EVENT_BYTES)){payload=encode(value.get("payload"));Limits.single(payload.length,EVENT_BYTES,"EVENT_PAYLOAD");}
        var labels=new TreeSet<String>();scopes.values().forEach(s->labels.addAll(s.requiredDataLabels()));
        // Admission before borrow encoding: at most six UTF-8 bytes per UTF-16 code unit,
        // plus quotes/commas/brackets. The set and sort backing are in the existing model
        // reservation; the borrowed generator has fixed small buffers in fixed8MiB scratch.
        long labelBytes=2;for(var label:labels)labelBytes=Limits.add(labelBytes,6L*label.length()+3,MaterialScopeStore.OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");w.live();
        return new ReadData(new EventData(new Info(b.publicId(),w.capture.resource(),w.capture.responseId(),afterSeq,num(event,"seq"),(String)event.get("event_type"),selectedScope.material().payloadSha256(),w.measurements(payload.length)),payload,labels),facts,packet(w,b,plan,scopes,stateScope,selectedScope,state,value,facts));
    }
    /** All references are private products of this one RR. The DOMs are never mutated or exported.
     * Existing model accounting covers their backing; charge retained descriptors before mint. */
    static final class PacketMaterial {
        final Binding binding;final Map<String,MaterialScopeStore.ObservedRow> rows;final Map<String,MaterialScopeV1> scopes;
        final MaterialScopeV1 stateScope,eventScope;final JsonNode state,event;final WindowInfo window;final String receipt;
        PacketMaterial(Binding b,Map<String,MaterialScopeStore.ObservedRow> r,Map<String,MaterialScopeV1> s,MaterialScopeV1 ss,MaterialScopeV1 es,JsonNode state,JsonNode event,WindowInfo w,String receipt){binding=b;rows=r;scopes=s;stateScope=ss;eventScope=es;this.state=state;this.event=event;window=w;this.receipt=receipt;}
    }
    private static PacketMaterial packet(Work w,Binding b,Map<String,MaterialScopeStore.ObservedRow> rows,Map<String,MaterialScopeV1> scopes,MaterialScopeV1 stateScope,MaterialScopeV1 eventScope,JsonNode state,JsonNode event,WindowInfo facts){
        if(w.privateReceipt==null)return null;w.ledger.reserve(512L+2L*w.privateReceipt.length());
        return new PacketMaterial(b,rows,scopes,stateScope,eventScope,state,event,facts,w.privateReceipt);
    }
    public record Measurements(long modelReserved,long totalReservedPeak,long rawReservedPeak,long canonicalReservedPeak,long canonicalActualUtf8Peak,long tokens,int parserBackingChars,int eventBackingBytes){}
    public record Info(String runId,ResourceBinding resource,String responseId,long afterSeq,long seq,String type,String rowSha256,Measurements measurements){}
    /** Pending, active callback and its actual finally all own the original reader permit. */
    public static final class EventEmission implements AutoCloseable{
        private final PortalEventsResponse response;private final PortalEventsResponse.Capture capture;private final Lease lease;private final Ledger ledger;private final Info info;
        private byte[] payload;private Set<String> labels;private PacketMaterial packet;private boolean active,closing,finished,window;private Thread consumer;
        private EventEmission(PortalEventsResponse r,PortalEventsResponse.Capture c,Lease l,Ledger ledger,Info i,byte[] p,Set<String> labels){response=r;capture=c;lease=l;this.ledger=ledger;info=i;payload=p;this.labels=labels;}
        private void usable(){if(closing||finished)throw denied();}
        public synchronized Info info(){usable();response.requireLive(capture);return info;}
        public void consume(Consumer<Borrow> consume){
            Objects.requireNonNull(consume);
            synchronized(this){usable();if(active)throw denied();try{live(response,capture,window);}catch(RuntimeException failure){finish();throw failure;}active=true;consumer=Thread.currentThread();}
            Throwable primary=null;
            try{consume.accept(new Borrow(this));guard();}catch(RuntimeException|Error failure){primary=failure;throw failure;}
            finally{try{cleanup(primary);}finally{synchronized(this){active=false;consumer=null;finish();}}}
        }
        private void consumeWindow(Consumer<Borrow> consume,Consumer<Throwable> beforeRelease){
            Objects.requireNonNull(beforeRelease);
            synchronized(this){usable();if(active)throw denied();active=true;consumer=Thread.currentThread();}
            Throwable primary=null;
            // Claim actual ownership before the entry guard: stop/close cannot recycle a
            // C window while its terminal Servlet complete is still physically executing.
            try{guard();consume.accept(new Borrow(this));guard();}
            catch(RuntimeException|Error failure){primary=failure;throw failure;}
            finally{
                // No holder or emission monitor is held over application write/complete.
                try{beforeRelease.accept(primary);}
                finally{try{cleanup(primary);}finally{synchronized(this){active=false;consumer=null;finish();}}}
            }
        }
        private synchronized void guard(){usable();if(!active||consumer!=Thread.currentThread())throw denied();live(response,capture,window);}
        private void finish(){payload=null;labels=null;packet=null;finished=true;lease.close();}
        public synchronized void close(){if(finished)return;closing=true;if(!active)finish();}
    }
    /** C must keep encoding, actual stream acquisition and write/flush inside synchronous consume. */
    public static final class Borrow{
        private final EventEmission emission;private boolean outputActive;private Borrow(EventEmission e){emission=e;}
        // One synchronous output at a time bounds both payload copies and label generators,
        // including cross-method sink callbacks. Sequential outputs remain legal.
        private void beginOutput(){emission.guard();if(outputActive)throw denied();outputActive=true;}
        public Info info(){emission.guard();return emission.info;}
        public void requireLive(){emission.guard();}
        public void writeRequiredDataLabels(OutputStream output)throws IOException{
            Objects.requireNonNull(output);beginOutput();try{
            var finiteOutput=new OutputStream(){
                long bytes;
                private void admit(int n){emission.guard();bytes=Limits.add(bytes,n,MaterialScopeStore.OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");}
                @Override public void write(int value)throws IOException{admit(1);output.write(value);emission.guard();}
                @Override public void write(byte[] value,int offset,int length)throws IOException{admit(length);output.write(value,offset,length);emission.guard();}
            };
            // No set/string/byte[] escapes. Do not close or flush the caller's stream: C owns
            // complete framing and the actual flush inside this same synchronous borrow.
            try(var generator=ROOT.createGenerator(finiteOutput)){
                generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET).disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
                ROOT.writeValue(generator,emission.labels);
            }
            emission.guard();
            }finally{outputActive=false;}
        }
        public void writePayload(OutputStream output)throws IOException{
            Objects.requireNonNull(output);beginOutput();
            // Transfer the original ledger with the permit: its retained model reservation
            // includes the original encoded payload. Charge a separate array (+24B for
            // header and alignment slack under the deployment's standard object alignment)
            // before clone, throughout actual write, within the same 192MiB total. No reuse
            // of fixed8MiB scratch; the sink receives only this disposable copy. The output
            // guard also excludes overlapping label generators/copies, even during callbacks.
            try(var copy=emission.ledger.reserve(emission.payload.length+24L)){
                byte[] payload=emission.payload.clone();emission.guard();output.write(payload);emission.guard();
            }finally{outputActive=false;}
        }
    }
    private static final class Lease implements AutoCloseable{
        private final Semaphore permits;private final AtomicBoolean released=new AtomicBoolean();Lease(Semaphore p){permits=p;}public void close(){if(released.compareAndSet(false,true))permits.release();}
    }
    public static final class CapacityFailure extends PlatformException{
        private final String dimension;
        private CapacityFailure(String d){super(d.equals("WORKSPACE")?429:413,d.equals("WORKSPACE")?"STREAM_CAPACITY":d.equals("EVENT_PAYLOAD")?"EVENT_LIMIT":"BUFFER_LIMIT","有限事件材料容量不足");dimension=d;}
        public String dimension(){return dimension;}
    }
    static CapacityFailure finite(String d){return new CapacityFailure(d);}
    static long num(Map<String,Object> r,String k){var v=r.get(k);if(!(v instanceof Number n))throw denied();return n.longValue();}
    static final class Limits{
        long metadata,roots,records;
        static long add(long current,long amount,long cap,String d){if(amount<0||current<0||current>cap||amount>cap-current)throw finite(d);return current+amount;}
        static void single(long bytes,long cap,String d){add(0,bytes,cap,d);}
        void metadata(long bytes){metadata=add(metadata,bytes,METADATA_BYTES,"METADATA_BYTES");}
        void root(long bytes){roots=add(roots,bytes,ROOT_BYTES,"ROOT_BYTES");}
        void record(long bytes){single(bytes,JSON_BYTES,"SCOPE_BYTES");records=add(records,1,MAX_RECORDS,"RECORDS");metadata(bytes);}
        static void parents(long count){single(count,MAX_PARENTS,"PARENTS");}
    }
    /** No wall Clock: one conservative monotonic anchor survives all statements and parsing. */
    static final class Work{
        final JdbcTemplate db;final PortalEventsResponse response;final PortalEventsResponse.Capture capture;final long entered,allowance;final Limits limits=new Limits();final boolean window;
        long representationUnits;int maxParserBackingChars;String privateReceipt;
        final Ledger ledger=new Ledger();long rawPeak,canonicalPeak,canonicalActualPeak,tokens;
        Work(JdbcTemplate d,PortalEventsResponse r,PortalEventsResponse.Capture c,long e){this(d,r,c,e,false);}
        Work(JdbcTemplate d,PortalEventsResponse r,PortalEventsResponse.Capture c,long e,boolean window){this.window=window;db=d;response=r;capture=c;entered=e;allowance=c.remainingNanos();ledger.reserve(8L*1024*1024);}
        long remaining(){return Math.min(capture.remainingNanos(),allowance-Math.max(0,System.nanoTime()-entered));}
        void live(){RunEventReader.live(response,capture,window);if(Thread.currentThread().isInterrupted()||remaining()<=0)throw denied();}
        long millis(){live();long ms=remaining()/1000000L;if(ms<1)throw denied();return ms;}
        List<Map<String,Object>> query(String sql,int rows,Object...args){
            millis();return db.execute((ConnectionCallback<List<Map<String,Object>>>)c->{
                var result=new ArrayList<Map<String,Object>>(rows);
                try(var statement=c.prepareStatement(sql)){
                for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
                long timeout=Math.max(1,millis()/2);boolean installed=false;
                // Reserve half the remaining budget for the LOCAL SET round trip; PG zero
                // disables timeout. Retry from the remaining budget, never restarting its anchor.
                for(int i=0;i<3;i++){
                    try(var setting=c.prepareStatement("select set_config('statement_timeout', ?, true)")){setting.setString(1,Long.toString(timeout));millis();setting.executeQuery().close();}
                    long now=millis();if(now>=timeout){installed=true;break;}timeout=Math.max(1,now/2);
                }
                // The successful loop's millis() is the final execution check, after closing
                // SET and after all parameter preparation. There is inevitably a small gap to
                // executeQuery; do not turn a second millisecond-boundary race into false denial.
                if(!installed)throw denied();
                    try(var rs=statement.executeQuery()){var meta=rs.getMetaData();while(rs.next()){if(result.size()>=rows)throw finite("RECORDS");var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getObject(i));result.add(row);}}
                }live();return result;
            });
        }
        JsonNode json(String raw,boolean root){
            live();try(var parser=ledger.reserve(64L*1024*1024)){
                var parsed=parseMeasured(raw,root,this::live,(count,bytes)->{
                    long cost=512L*count+8L*bytes;long next=Limits.add(representationUnits,cost,MODEL_UNITS,"REPRESENTATION_MODEL");ledger.reserve(cost);representationUnits=next;tokens+=count;
                });maxParserBackingChars=Math.max(maxParserBackingChars,parsed.maxBackingChars());live();return parsed.value();
            }
        }
        JsonNode jsonText(String sql,long bytes,boolean root,Object...args){
            long cost=6*bytes;try(var text=ledger.reserve(cost)){
                rawPeak=Math.max(rawPeak,cost);var rows=query(sql,2,args);if(rows.size()!=1||rows.getFirst().size()!=1)throw denied();
                var raw=(String)rows.getFirst().values().iterator().next();if(raw==null)throw denied();same((long)raw.getBytes(StandardCharsets.UTF_8).length,bytes);return json(raw,root);
            }
        }
        Ledger.Reservation canonicalScratch(long bytes){Limits.single(bytes,MaterialScopeStore.OBSERVED_NORMALIZED_BYTES,"NORMALIZED_BYTES");long cost=5*bytes;var reserved=ledger.reserve(cost);canonicalPeak=Math.max(canonicalPeak,cost);return reserved;}
        String digest(JsonNode value){
            long bytes=MaterialScopeStore.observedCanonicalCost(value);try(var canonical=canonicalScratch(bytes)){
                var measured=MaterialScopeStore.observedDigestMeasured(value);canonicalActualPeak=Math.max(canonicalActualPeak,measured.utf8Bytes());live();return measured.sha();
            }
        }
        String ceiling(Actor actor,long bytes){try(var canonical=canonicalScratch(6*bytes)){return MaterialAuthorization.ceilingSha256(actor);}}
        void material(MaterialScopeV1 scope,long bytes){try(var canonical=canonicalScratch(6*bytes)){capture.requireMaterial(scope);}}
        Measurements measurements(int backing){return new Measurements(representationUnits,ledger.peak,rawPeak,canonicalPeak,canonicalActualPeak,tokens,maxParserBackingChars,backing);}
    }
    private static ObjectMapper mapper(boolean root){
        var constraints=StreamReadConstraints.builder().maxNestingDepth(root?64:32).maxNumberLength(1000).maxStringLength(root?1048576:65536).maxNameLength(root?1048576:65536).build();
        return new ObjectMapper(JsonFactory.builder().streamReadConstraints(constraints).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING).disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES).disable(JsonFactory.Feature.INTERN_FIELD_NAMES).build())
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private static final ObjectMapper META=mapper(false),ROOT=mapper(true);
    /** Jackson2.19 JsonRecyclerPools.ThreadLocalPool.acquirePooled calls this same public
     * BufferRecyclers accessor; Json.mapper().copy() retains that pool. Only mandatory common
     * material/ceiling validation uses it. BufferRecycler has four byte+four char slots; alloc
     * atomically takes a slot and clears it. Empty defaults total<=36,800B (charged to fixed8MiB
     * scratch). Drop, never return, those arrays; do not globally release or touch another thread.
     * Entry drain also prevents borrowing a large buffer left by unrelated earlier work.
     */
    @SuppressWarnings("deprecation")
    private static void clearCurrentThreadBuffers(){
        var recycler=com.fasterxml.jackson.core.util.BufferRecyclers.getBufferRecycler();
        for(int i=0;i<4;i++){recycler.allocByteBuffer(i);recycler.allocCharBuffer(i);}
    }
    private static void cleanup(Throwable primary){
        try{clearCurrentThreadBuffers();}catch(RuntimeException|Error failure){if(primary==null)throw failure;primary.addSuppressed(failure);}
    }
    static String encodeText(JsonNode value){try{return ROOT.writeValueAsString(value);}catch(IOException failure){throw denied();}}
    private static byte[] encode(JsonNode value){try{return ROOT.writeValueAsBytes(value);}catch(IOException failure){throw denied();}}
    record Parsed(JsonNode value,int tokens,int utf8Bytes,int maxBackingChars){}
    @FunctionalInterface private interface BeforeDom{void admit(int tokens,int bytes);}
    static JsonNode parse(String raw,boolean root,Runnable live){
        return parseMeasured(raw,root,live,(tokens,bytes)->{}).value();
    }
    static Parsed parseMeasured(String raw,boolean root,Runnable live){return parseMeasured(raw,root,live,(tokens,bytes)->{});}
    private static Parsed parseMeasured(String raw,boolean root,Runnable live,BeforeDom admission){
        if(raw==null)throw denied();var mapper=root?ROOT:META;int count=0,depth=0,maxBacking=0;boolean complete=false;
        // Jackson2.19 ReaderBasedJsonParser lazily finishes strings: force text length during
        // the streaming pass, before DOM, with field-name tokens and duplicate detection too.
        try(var parser=mapper.createParser(raw)){JsonToken token;while((token=parser.nextToken())!=null){
            if(complete)throw denied();if(++count>(root?262144:65536))throw finite("PARSER_TOKENS");if((count&255)==0)live.run();
            if(token==JsonToken.FIELD_NAME||token==JsonToken.VALUE_STRING){if(parser.getTextLength()>(root?1048576:65536))throw finite("PARSER_STRING");maxBacking=Math.max(maxBacking,parser.getTextCharacters().length);}
            if(token.isNumeric()&&parser.getTextLength()>1000)throw finite("PARSER_NUMBER");
            if(token.isStructStart())depth++;if(token.isStructEnd())depth--;if(depth==0&&token!=JsonToken.FIELD_NAME)complete=true;
        }if(!complete)throw denied();live.run();int bytes=raw.getBytes(StandardCharsets.UTF_8).length;admission.admit(count,bytes);var result=mapper.readTree(raw);live.run();return new Parsed(result,count,bytes,maxBacking);
        }catch(StreamConstraintsException capacity){throw finite("PARSER_CONSTRAINT");}catch(IOException invalid){throw denied();}
    }
    static MaterialScopeV1 scope(JsonNode n){
        object(n);if(n.size()!=11||!n.path("schemaVersion").isIntegralNumber()||!n.path("schemaVersion").canConvertToInt()||n.path("schemaVersion").intValue()!=1)throw denied();
        var material=n.get("material");object(material);if(material.size()!=5)throw denied();for(String k:List.of("producer","kind","id","version","payloadSha256"))text(material,k);
        var owner=n.get("owner");object(owner);if(owner.size()!=2)throw denied();text(owner,"tenantId");text(owner,"principalId");
        for(String k:List.of("sourceDecisionId","sourceAuthorizationVersion","initialCeilingSha256","scopeSha256"))text(n,k);
        for(String k:List.of("requiredRoles","requiredDataLabels","parentScopeSha256"))strings(n.get(k),k.equals("parentScopeSha256"));
        var attrs=n.get("requiredEntitlements");object(attrs);attrs.fields().forEachRemaining(e->strings(e.getValue(),false));
        try{return META.treeToValue(n,MaterialScopeV1.class);}catch(IOException|IllegalArgumentException invalid){throw denied();}
    }
    private static void strings(JsonNode n,boolean parents){if(n==null||!n.isArray())throw denied();if(parents)Limits.parents(n.size());var unique=new HashSet<String>();for(var v:n){if(!v.isTextual()||v.textValue().isBlank()||!unique.add(v.textValue()))throw denied();if(parents)hash(v.textValue());}}
}
