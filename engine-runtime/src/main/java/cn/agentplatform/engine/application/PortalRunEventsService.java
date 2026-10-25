package cn.agentplatform.engine.application;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.RunController;
import cn.agentplatform.engine.infra.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** Explicit receiver assembly only. One connection owns one finite Servlet task, never an MVC
 * streaming callback: Spring 6.2.7 otherwise obtains the stream before and flushes after it. */
public final class PortalRunEventsService implements AutoCloseable {
    private final RunEventReader reader;private final HttpCurrentAuthorityClient authority;
    private final ChunkBus chunks;private final Clock clock;private final ThreadPoolExecutor writers;
    private final Set<Owner> owners=ConcurrentHashMap.newKeySet();private volatile boolean closed;
    public PortalRunEventsService(RunEventReader reader,HttpCurrentAuthorityClient authority,ChunkBus chunks,Clock clock){
        this(reader,authority,chunks,clock,new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),r->{var t=new Thread(r,"portal-events-writer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy()));
    }
    /** Transfers the finite executor to this service, for controlled receiver assembly. */
    public PortalRunEventsService(RunEventReader reader,HttpCurrentAuthorityClient authority,ChunkBus chunks,Clock clock,ThreadPoolExecutor writers){
        this.reader=Objects.requireNonNull(reader);this.authority=Objects.requireNonNull(authority);this.chunks=Objects.requireNonNull(chunks);this.clock=Objects.requireNonNull(clock);this.writers=Objects.requireNonNull(writers);
        if(writers.getMaximumPoolSize()>2||writers.getCorePoolSize()<1||writers.getQueue().remainingCapacity()+writers.getQueue().size()>2)throw new IllegalArgumentException("FINITE_EVENTS_EXECUTOR_REQUIRED");
    }
    public void open(String id,HttpServletRequest request,HttpServletResponse output)throws IOException{
        long cursor=validate(id,request);
        boolean privatePackets=privateRepresentation(request);
        if(closed)throw capacity();
        var current=AuthorityContext.current();var call=current.call();
        RunObservationSelectors.requirePurpose(call,current.caller(),"delegated",current.operation().sourceKind(),current.operation().originKind());
        if(!RunObservationSelectors.EVENTS_ACTION.equals(call.action())||!call.resource().equals(RunObservationSelectors.events(id,call.resource().versionDigest())))throw denied();
        var owner=new Owner(output,cursor,privatePackets);owners.add(owner);
        try{
            owner.slot=chunks.reservePortalSlot(owner::stop);owner.check();
            owner.frameLease=chunks.reservePortalFrame();if(owner.frameLease==null)throw capacity();
            var initial=AuthorityContext.requireInitialFor(current.actor(),call.resource());
            var binding=new PortalEventsBinding("events-"+UUID.randomUUID(),call.resource(),cursor,
                    PortalEventsBinding.observationRequestSha256(new SubjectKey(current.actor().tenantId(),current.actor().principalId()),initial.requireOriginAuthenticationId(),call.resource(),cursor),clock.instant().plusSeconds(55));
            owner.response=privatePackets?authority.openPortalEventsChild(initial,cursor,owner::stop):authority.openPortalEvents(initial,binding,owner::stop);owner.check();
            owner.pending=owner.read();owner.check();
            // Queue only a small connection owner. Keeping preflight results in the bounded
            // task queue could consume both reader permits and starve the two active pollers.
            // The actual writer obtains a fresh frame and a fresh complete RR before output.
            close(owner.pending);owner.pending=null;close(owner.frameLease);owner.frameLease=null;
            owner.superseded=0;
            // First complete protected window precedes SSE success and async handoff.
            owner.async=request.startAsync(request,output);
            owner.async.setTimeout(55000);owner.async.addListener(owner);
            output.setContentType(privatePackets?PortalEventsPacketWriter.MIME:"text/event-stream");if(!privatePackets)output.setCharacterEncoding("UTF-8");
            if(!owner.state.compareAndSet(0,1))throw denied();
            owner.check();writers.execute(owner);
        }catch(RuntimeException|Error failure){
            if(owner.async!=null){
                // Once Servlet async has taken ownership MVC cannot safely map a thrown
                // exception: error redispatch would become a different protected route.
                owner.abortOpening(failure instanceof PlatformException p?p:failure instanceof RejectedExecutionException?capacity():new PlatformException(500,"STREAM_UNAVAILABLE","事件观察不可用"));return;
            }
            owner.abortOpening(null);
            if(failure instanceof RejectedExecutionException)throw capacity();throw failure;
        }
    }
    /** A bounded parser recognizes the private media type, then requires its one exact spelling.
     * Ordinary Accept rules remain with MVC; a private request never falls back to public SSE. */
    public static boolean privateRepresentation(HttpServletRequest request){
        var headers=Collections.list(request.getHeaders("Accept"));boolean selected=false;int bytes=0;
        for(String header:headers){if(header==null||(bytes+=header.length())>8192)throw invalid();
            try{for(var media:org.springframework.http.MediaType.parseMediaTypes(header))if(media.getType().equalsIgnoreCase("application")&&media.getSubtype().equalsIgnoreCase("vnd.agent-platform.portal-events.v1"))selected=true;}
            catch(org.springframework.http.InvalidMediaTypeException failure){throw invalid();}}
        if(selected&&(headers.size()!=1||!headers.getFirst().equals(PortalEventsPacketWriter.MIME)))throw invalid();return selected;
    }
    private static long validate(String id,HttpServletRequest r)throws IOException{
        Object actual=r.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if(!(actual instanceof HandlerMethod h)||h.getBeanType()!=RunController.class||h.getMethod().getDeclaringClass()!=RunController.class||!h.getMethod().getName().equals("events")
                ||r.getDispatcherType()!=DispatcherType.REQUEST||!"GET".equals(r.getMethod())
                ||!"/admin/runs/{id}/events".equals(r.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
                ||!r.getRequestURI().equals(r.getContextPath()+"/admin/runs/"+id+"/events")||r.getContentLengthLong()>0||r.getInputStream().read()!=-1||!r.isAsyncSupported())throw invalid();
        var parameters=r.getParameterMap();if(parameters.keySet().stream().anyMatch(k->!k.equals("Last-Event-ID")))throw invalid();
        String selected;
        if(parameters.containsKey("Last-Event-ID")){var values=parameters.get("Last-Event-ID");if(values==null||values.length!=1)throw invalid();selected=values[0];}
        else{var headers=Collections.list(r.getHeaders("Last-Event-ID"));if(headers.size()>1)throw invalid();selected=headers.isEmpty()?"0":headers.getFirst();}
        if(selected==null||!selected.matches("[0-9]{1,19}"))throw invalid();
        try{return Long.parseLong(selected);}catch(NumberFormatException overflow){throw invalid();}
    }
    private static PlatformException invalid(){return new PlatformException(400,"EVENT_CURSOR","事件观察请求无效");}
    private static PlatformException denied(){return new PlatformException(403,"RUN_AUTHORITY_DENIED","运行观察不可用");}
    private static PlatformException capacity(){return new PlatformException(429,"STREAM_CAPACITY","实时观察已满，请稍后重连");}
    private static void close(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}

    private final class Owner implements Runnable,AsyncListener {
        // OPENING=0/PENDING=1/RUNNING=2/FINISHED=3. Notifications publish stop. Only
        // actual running finally or the never-run CAS can become the release owner.
        final AtomicInteger state=new AtomicInteger();final AtomicBoolean stopped=new AtomicBoolean(),completed=new AtomicBoolean();
        final HttpServletResponse output;final boolean privatePackets;long after,ordinal;boolean statusSent,packetStarted,capacitySent;int superseded;
        volatile PortalEventsResponse response;ChunkBus.PortalSlot slot;AutoCloseable frameLease;
        RunEventReader.WindowEmission pending;AsyncContext async;ServletOutputStream stream;
        Owner(HttpServletResponse output,long after,boolean privatePackets){this.output=output;this.after=after;this.privatePackets=privatePackets;}
        void check(){if(stopped.get()||closed)throw denied();}
        RunEventReader.WindowEmission read(){
            while(true)try{check();return privatePackets?reader.readChildWindow(response,after,authority):reader.readWindow(response,after);}
            catch(RunEventReader.Superseded old){if(++superseded>2)throw old;}
        }
        void stop(){
            stopped.set(true);var current=response;if(current!=null)current.close();
            if(state.compareAndSet(1,3)){try{complete();}finally{release();}}
        }
        void abortOpening(PlatformException failure){
            if(state.compareAndSet(0,3)||state.compareAndSet(1,3)){
                try{
                    if(failure!=null&&!stopped.get()&&!output.isCommitted()){
                        output.setStatus(failure.status());output.setContentType("application/json");
                        frameLease=chunks.reservePortalFrame();
                        if(frameLease!=null){
                            var capture=response.currentCapture();
                            // Fixed generic envelope (<1KiB), charged to the reserved 8KiB
                            // scratch. No exception detail or unverified run fact is exposed.
                            byte[] error=Json.write(new ApiResponse<>(failure.code(),"事件观察不可用",null,null)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            response.requireLive(capture);var stream=output.getOutputStream();response.requireLive(capture);
                            stream.write(error);response.requireLive(capture);stream.flush();response.requireLive(capture);
                        }
                    }
                }catch(IOException|RuntimeException alreadyStopped){
                    // A retired holder/Servlet response can only close at this boundary.
                }finally{stopped.set(true);try{complete();}finally{release();}}
            }
        }
        void complete(){if(async!=null&&completed.compareAndSet(false,true))try{async.complete();}catch(IllegalStateException alreadyCompleted){
            // The container may already have completed after a real timeout/onComplete.
            // The call has physically returned; it still precedes our resource finally.
        }}
        void release(){
            close(pending);pending=null;close(frameLease);frameLease=null;close(response);close(slot);owners.remove(this);
        }
        @Override public void run(){
            if(!state.compareAndSet(1,2))return;
            try{
                while(true){
                    check();
                    if(pending==null){
                        frameLease=chunks.reservePortalFrame();
                        if(frameLease==null){pause();continue;}
                        try{pending=read();}catch(RunEventReader.CapacityFailure limit){
                            if(limit.status()==429){close(frameLease);frameLease=null;pause();continue;}
                            capacityReset(limit);return;
                        }
                    }
                    var done=new AtomicBoolean();
                    try{
                        pending.consume(view->{
                            check();var window=view.window();String reset=window.resetReason();
                            if(privatePackets){
                                if(!statusSent){packet(view,true);statusSent=true;}
                                // Only active idle after the first WINDOW may be omitted. A new
                                // terminal observation must reach the peer as complete material.
                                if(ordinal==1||reset!=null||view.hasEvent()||window.drained()){
                                    packet(view,false);if(reset!=null||window.drained())done.set(true);
                                    else if(view.hasEvent()){after=view.info().seq();if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(window.status())&&after>=window.watermark())done.set(true);}
                                }
                                return;
                            }
                            var frame=new Frame();
                            if(reset!=null){frame.controlInto("stream_reset",reset,window.status(),window.watermark(),after);send(view,frame);done.set(true);return;}
                            if(!statusSent){frame.controlInto("stream_status",null,null,0,after);send(view,frame);statusSent=true;}
                            if(view.hasEvent()){
                                frame.count=0;frame.event(view);send(view,frame);after=view.info().seq();
                                if(Set.of("SUCCEEDED","FAILED","CANCELLED").contains(window.status())&&after>=window.watermark())done.set(true);
                            }else if(window.drained())done.set(true);
                        },failure->{
                            if(privatePackets&&failure instanceof RunEventReader.CapacityFailure limit){capacityReset(limit);return;}
                            // This seam includes entry and final guard failures. A healthy
                            // bounded retry alone may release the old window before reread;
                            // termination must complete while B and this frame are still held.
                            if(failure instanceof RunEventReader.Superseded&&!done.get()&&superseded<2&&(!privatePackets||!packetStarted)){
                                try{check();response.currentCapture();return;}catch(RuntimeException retired){/* Complete below while still owning this window. */}
                            }
                            if(failure!=null||done.get())complete();
                        });
                        superseded=0; // A successful complete window starts the next bounded read round.
                    }catch(RunEventReader.Superseded old){if(privatePackets&&packetStarted||++superseded>2)throw old;}
                    catch(RunEventReader.CapacityFailure limit){if(!privatePackets)throw limit;return;}
                    finally{close(pending);pending=null;close(frameLease);frameLease=null;}
                    if(done.get())return;
                    pause();
                }
            }catch(RuntimeException failure){
                org.slf4j.LoggerFactory.getLogger(PortalRunEventsService.class).debug("Portal events owner stopped at cursor {}",after,failure);
                try{if(!stopped.get()&&!completed.get()&&!output.isCommitted()&&failure instanceof PlatformException p)output.setStatus(p.status());}
                catch(IllegalStateException alreadyCompleted){/* Container retired its response during a blocked call. */}
            }finally{
                // Getter/write/flush and final complete must physically return before any
                // still-held reader/frame/slot can be returned to a different connection.
                try{complete();}finally{state.set(3);release();}
            }
        }
        void packet(RunEventReader.WindowBorrow view,boolean hello){
            packetStarted=false;
            try{
                check();view.requireLive();if(stream==null){stream=output.getOutputStream();check();view.requireLive();}
                var guarded=new OutputStream(){
                    public void write(int value)throws IOException{check();view.requireLive();packetStarted=true;stream.write(value);check();view.requireLive();}
                    public void write(byte[] bytes,int offset,int count)throws IOException{check();view.requireLive();packetStarted=true;stream.write(bytes,offset,count);check();view.requireLive();}
                    public void flush()throws IOException{check();view.requireLive();stream.flush();check();view.requireLive();}
                };
                view.writePrivatePacket(guarded,ordinal,hello);
                ordinal=Math.incrementExact(ordinal);packetStarted=false;
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }
        void send(RunEventReader.WindowBorrow view,Frame frame){
            try{
                check();view.requireLive();
                if(stream==null){stream=output.getOutputStream();check();view.requireLive();}
                check();view.requireLive();stream.write(frame.bytes,0,frame.count);check();view.requireLive();
                stream.flush();check();view.requireLive();
            }catch(IOException io){throw new UncheckedIOException(io);}
        }
        void capacityReset(RunEventReader.CapacityFailure failure){
            try{
                if(privatePackets){
                    if(packetStarted||capacitySent)return;
                    if(statusSent){
                        capacitySent=true;
                        try{check();var c=response.currentCapture();if(stream==null){stream=output.getOutputStream();check();response.requireLive(c);}
                            PortalEventsPacketWriter.capacity(stream,response,authority,ordinal,after,failure.code());return;
                        }catch(IOException io){throw new UncheckedIOException(io);}
                    }
                    if(output.isCommitted())return;
                    // No private packet has begun: reuse the charged fixed JSON envelope below.
                }
                // No unvalidated status/watermark is included. The fresh local capture guards
                // this fixed capacity control; a failed holder never authorizes a final reset.
                var capture=response.currentCapture();check();byte[] bytes;int count;
                // Opening preflight is not an output proof. Only the actual Servlet commit
                // decides whether fresh-window capacity is an HTTP error or a bounded reset.
                if(!output.isCommitted()){
                    output.setStatus(failure.status());output.setContentType("application/json");
                    bytes=Json.write(new ApiResponse<>(failure.code(),"事件观察不可用",null,null)).getBytes(java.nio.charset.StandardCharsets.UTF_8);count=bytes.length;
                }else if(privatePackets)return;
                else{var frame=Frame.control("stream_reset",failure.code(),null,0,after);bytes=frame.bytes;count=frame.count;}
                try{
                    response.requireLive(capture);if(stream==null){stream=output.getOutputStream();check();response.requireLive(capture);}
                    check();response.requireLive(capture);stream.write(bytes,0,count);check();response.requireLive(capture);
                    stream.flush();check();response.requireLive(capture);complete();
                }catch(IOException io){throw new UncheckedIOException(io);}
            }finally{
                // Capacity can fail at export/current/get/write/flush. Complete must physically
                // return here, inside beforeRelease while this RR's B permit and frame are held.
                if(privatePackets)complete();
            }
        }
        void pause(){check();response.currentCapture();try{Thread.sleep(75);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw denied();}}
        @Override public void onTimeout(AsyncEvent event){stop();}
        @Override public void onError(AsyncEvent event){stop();}
        @Override public void onComplete(AsyncEvent event){stop();}
        @Override public void onStartAsync(AsyncEvent event){stop();}
    }
    /** Single exact backing; never BAOS growth/toByteArray. All copies sent by the B borrow
     * remain charged to B; this independent complete frame is charged to ChunkBus first. */
    static final class Frame extends OutputStream {
        final byte[] bytes=new byte[ChunkBus.MAX_FRAME_BYTES];int count;boolean payload;
        private void admit(int n){if(n<0||n>bytes.length-count)throw new PlatformException(413,"EVENT_LIMIT","事件响应过大");}
        @Override public void write(int value){admit(1);if(payload&&(value=='\r'||value=='\n'))throw denied();bytes[count++]=(byte)value;}
        @Override public void write(byte[] value,int offset,int length){admit(length);if(payload)for(int i=offset;i<offset+length;i++)if(value[i]=='\r'||value[i]=='\n')throw denied();System.arraycopy(value,offset,bytes,count,length);count+=length;}
        void ascii(String text){for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c>127)throw denied();write(c);}}
        void event(RunEventReader.WindowBorrow view){
            var info=view.info();if(info.seq()<0||info.type()==null||!info.type().matches("[A-Za-z_]{1,64}"))throw denied();
            ascii("id:");ascii(Long.toString(info.seq()));ascii("\nevent:");ascii(info.type());ascii("\ndata:");
            int start=count;payload=true;try{view.writePayload(this);}catch(IOException failure){throw new UncheckedIOException(failure);}finally{payload=false;}
            if(count-start>ChunkBus.MAX_EVENT_BYTES)throw new PlatformException(413,"EVENT_LIMIT","事件响应过大");ascii("\n\n");
        }
        static Frame control(String type,String reason,String status,long watermark,long after){
            var f=new Frame();f.controlInto(type,reason,status,watermark,after);return f;
        }
        void controlInto(String type,String reason,String status,long watermark,long after){
            var f=this;f.count=0;f.ascii("event:");f.ascii(type);f.ascii("\ndata:{");
            if(reason==null)f.ascii("\"chunkAvailable\":false,\"chunkReplay\":false,\"recovery\":\"LAST_EVENT_ID_AND_FINAL_SNAPSHOT\"");
            else{
                if(!Set.of("CURSOR_AHEAD","CURSOR_EXPIRED","BUFFER_LIMIT","EVENT_LIMIT").contains(reason))throw denied();
                f.ascii("\"reason\":\"");f.ascii(reason);f.ascii("\",\"chunkReplay\":false,\"recovery\":\"FINAL_SNAPSHOT\",\"lastEventId\":");f.ascii(Long.toString(after));
                if(status!=null){if(!status.matches("[A-Z_]{1,32}"))throw denied();f.ascii(",\"status\":\"");f.ascii(status);f.ascii("\",\"eventsWatermark\":");f.ascii(Long.toString(watermark));}
            }
            f.ascii("}\n\n");
        }
    }
    @Override public void close(){closed=true;for(var owner:owners)owner.stop();for(var task:writers.shutdownNow())if(task instanceof Owner owner)owner.stop();}
}
