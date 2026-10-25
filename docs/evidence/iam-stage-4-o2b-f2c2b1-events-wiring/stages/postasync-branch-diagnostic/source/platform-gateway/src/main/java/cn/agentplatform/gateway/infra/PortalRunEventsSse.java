package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.CurrentAuthorityClient;
import cn.agentplatform.common.authority.GatewayIngressBoundary.PortalEventsRequest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.lang.ref.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;
import org.apache.coyote.Processor;
import org.apache.coyote.UpgradeToken;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.*;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import java.nio.ByteBuffer;

/** Explicitly assembled HTTP/1.1 NIO adapter. Only a real Session can supply an output window. */
public final class PortalRunEventsSse {
    static final int FRAME_BYTES=270374;
    private static final String SLOT=PortalRunEventsSse.class.getName()+".request";
    private static Bridge bridge;
    private final BiConsumer<String,Long> probe;
    public PortalRunEventsSse() { this(null); }
    PortalRunEventsSse(BiConsumer<String,Long> probe) { this.probe=probe; }

    /** Explicit context installation; this class has no bean, controller or scan annotations. */
    static Valve contextValve() { return new ContextValve(); }

    Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,String source,String run,long cursor)throws IOException {
        return start(request,response,http,authority,source,run,cursor,null);
    }
    public Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,PortalEventsRequest capture)throws IOException {
        Objects.requireNonNull(capture).requireLive(request,response);
        return start(request,response,http,authority,capture.sourceToken(),capture.publicRunId(),capture.cursor(),capture);
    }
    private Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,String source,String run,long cursor,PortalEventsRequest capture)throws IOException {
        Objects.requireNonNull(http);
        RequestSlot token=null;PortalRunEventsHttp.Session session=null;PortalRunEventsHttp.Session.SseLease lease=null;State state=null;Handle handle=null;
        boolean asyncOwned=false;
        try {
            Object attribute=request.getAttribute(SLOT);
            if(!(attribute instanceof RequestSlot exact))throw new IllegalStateException("Exact controlled SSE lifecycle required");
            token=exact;
            if(capture==null)token.requireOriginal(request,response);else token.requireCaptured(request,response,capture);
            // One shared credit is registered before the only Session/State construction attempt.
            handle=token.origin.protocol.createHandle();
            session=http.forSse(authority,source,run,cursor,handle);
            session.lookupMetadata();lease=session.beginSse();handle.claimState();state=new State(handle,session,lease,token,probe);
            handle.attach(state);
            lease.cancellation(state::cancelSignal);lease.exits(state::finish);
            token.arm(state);
            state.async=request.startAsync();
            asyncOwned=true; // Completion may clear State.async before bootstrap's catch executes.
            state.async.setTimeout(0); // Independent original absolute deadlines drive every wait.
            state.async.addListener(state);
            state.worker=Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-sse-reader").unstarted(state::read);
            state.worker.setContextClassLoader(PortalRunEventsSse.class.getClassLoader());
            state.worker.start();
            if(!state.awaitWork())throw new IOException("First verified window unavailable");
            synchronized(state.io) {
                state.point("before-getter",0);
                state.fresh();
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control","no-cache");
                state.output=response.getOutputStream();
                state.point("after-getter",0);
                state.fresh();
                state.output.setWriteListener(state);
                state.installed=true;
                state.point("setter-return",0);
            }
            return handle;
        } catch(IOException|RuntimeException|Error failure) {
            System.err.println("C2B1_DIAG_SSE_CATCH typed="+(capture!=null)+" asyncOwned="+asyncOwned);
            if(capture!=null&&asyncOwned){
                // Once async owns the response, even synchronous completion can make isAsyncStarted
                // false. Never route this failure (or a cleanup failure) to MVC's JSON writer.
                // Returning the existing Handle means response ownership, not successful business IO.
                try{state.stop();}catch(RuntimeException|Error ignored){}
                finally{
                    try{state.complete();}catch(RuntimeException|Error ignored){}
                    finally{try{session.close();}catch(RuntimeException|Error ignored){}}
                }
                try{org.slf4j.LoggerFactory.getLogger(PortalRunEventsSse.class).warn("Portal events async bootstrap stopped");}catch(RuntimeException|Error ignored){}
                System.err.println("C2B1_DIAG_SSE_OWNED_RETURN");
                return handle;
            }
            if(state==null||state.async==null) {
                if(token!=null)token.reject();
                if(state!=null)state.detachUnstarted(); // Returns before any fee/Owner can be released.
                if(lease!=null)lease.release();
                if(session!=null)session.close(); // Http joins its actually-started transport/timer before refund.
            } else {
                state.stop();state.complete();session.close(); // Actual completion/worker/action gates still apply.
            }
            throw failure;
        } finally { Reference.reachabilityFence(handle); }
    }
    public static final class Handle implements AutoCloseable {
        private State state;
        private long after;
        private boolean terminal;
        private boolean sessionAttempted,stateAttempted;
        private Handle() {}
        synchronized void claimSession() { if(sessionAttempted)throw new IllegalStateException("Unique SSE Session already attempted");sessionAttempted=true; }
        private synchronized void claimState() { if(!sessionAttempted||stateAttempted)throw new IllegalStateException("Unique SSE State already attempted");stateAttempted=true; }
        private synchronized void attach(State exact) { if(state!=null||!stateAttempted||exact.handle!=this)throw new IllegalStateException("Unique SSE State identity");state=exact; }
        private synchronized State active() { return state; }
        private synchronized void detach(State expected,long cursor,boolean terminal) { if(state==expected) { after=cursor;this.terminal=terminal;state=null; } }
        @Override public void close() { var current=active();if(current!=null)current.stop(); }
        public boolean awaitClosed(Duration timeout) { if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");var current=active();return current==null||current.finish(timeout); }
        long after() { var current=active();if(current!=null)synchronized(current.cell) { return current.after; }synchronized(this) { return after; } }
        boolean terminal() { var current=active();if(current!=null)synchronized(current.cell) { return current.terminal; }synchronized(this) { return terminal; } }
        void wakeForTest() { var current=active();if(current!=null)synchronized(current.cell) { current.cell.notifyAll(); } }
        void interruptReaderForTest() { var current=active();if(current==null)return;Thread thread;synchronized(current.cell) { thread=current.worker; }if(thread!=null)thread.interrupt(); }
        boolean inertTokenForTest() { var current=active();if(current==null)return true;var token=current.token;if(token==null)return true;synchronized(token) { return token.origin==null&&token.response==null&&token.originalSocket==null&&token.state==null&&token.requestThread==null; } }
        synchronized boolean detachedForTest() { return state==null; }
    }
    /** One fixed backing, including control frames; no UTF-8 intermediate backing or growth. */
    static final class Frame extends OutputStream {
        final byte[] bytes=new byte[FRAME_BYTES];
        int count;
        @Override public void write(int b) { if(count==bytes.length)throw limit();bytes[count++]=(byte)b; }
        @Override public void write(byte[] b,int off,int n) { Objects.checkFromIndexSize(off,n,b.length);if(n>bytes.length-count)throw limit();System.arraycopy(b,off,bytes,count,n);count+=n; }
        void ascii(String value) { for(int i=0;i<value.length();i++) { int c=value.charAt(i);if(c>127)throw limit();write(c); } }
        void type(String value) {
            if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>32)throw limit();
            int start=count;
            for(int i=0;i<value.length();) {
                int c=value.codePointAt(i);i+=Character.charCount(c);
                if(c==0||c=='\r'||c=='\n'||c>=0xd800&&c<=0xdfff)throw limit();
                if(c<128)write(c);
                else if(c<2048) { write(0xc0|c>>6);write(0x80|c&63); }
                else if(c<65536) { write(0xe0|c>>12);write(0x80|c>>6&63);write(0x80|c&63); }
                else { write(0xf0|c>>18);write(0x80|c>>12&63);write(0x80|c>>6&63);write(0x80|c&63); }
            }
            if(count-start>128)throw limit();
        }
        void event(PortalEventsMaterialVerifier.VerifiedWindow window)throws IOException {
            count=0;ascii("id: ");ascii(Long.toString(window.eventSeq()));ascii("\nevent: ");type(window.eventType());ascii("\ndata: ");
            int start=count;window.writeRawPayload(this);if(count-start>262144)throw limit();ascii("\n\n");
        }
        void control(PortalEventsMaterialVerifier.VerifiedWindow window,boolean reset) {
            count=0;
            if(!reset)ascii("event: stream_status\ndata: {\"chunkAvailable\":false,\"chunkReplay\":false,\"recovery\":\"LAST_EVENT_ID_AND_FINAL_SNAPSHOT\"}\n\n");
            else {
                String reason=window.resetReason();if("EXPIRED".equals(reason))reason="CURSOR_EXPIRED";
                if(!Set.of("CURSOR_AHEAD","CURSOR_EXPIRED").contains(reason))throw limit();
                String status=window.status();if(!status.matches("[A-Z_]{1,32}"))throw limit();
                ascii("event: stream_reset\ndata: {\"reason\":\"");ascii(reason);ascii("\",\"chunkReplay\":false,\"recovery\":\"FINAL_SNAPSHOT\",\"lastEventId\":");
                ascii(Long.toString(window.after()));ascii(",\"status\":\"");ascii(status);ascii("\",\"eventsWatermark\":");ascii(Long.toString(window.watermark()));ascii("}\n\n");
            }
        }
    }
    private static PlatformException limit() { return new PlatformException(413,"EVENT_LIMIT","事件响应过大"); }

    private static final class State implements WriteListener,AsyncListener {
        final Object cell=new Object(),io=new Object();
        RequestSlot token;
        final Handle handle;
        BiConsumer<String,Long> probe;
        PortalRunEventsHttp.Session session;
        PortalRunEventsHttp.Session.SseLease lease;
        AsyncContext async;
        ServletOutputStream output;
        Frame frame;
        Thread worker;
        PortalEventsMaterialVerifier.VerifiedWindow slot;
        final long deadline;
        long idleDeadline,after;
        boolean installed,stopping,abortIssued,eof,completed,completeRequested,finalizing,finished,terminal,statusSent,event,done,frameDone,attempted,flushed;
        int offset,heavy,actions;
        State(Handle handle,PortalRunEventsHttp.Session session,PortalRunEventsHttp.Session.SseLease lease,RequestSlot token,BiConsumer<String,Long> probe) {
            this.handle=handle;this.session=session;this.lease=lease;this.token=token;this.probe=probe;deadline=lease.deadline();
            idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));frame=new Frame();
        }
        private void detachUnstarted() {
            synchronized(cell) {
                if(async!=null||worker!=null||actions!=0||heavy!=0||slot!=null)throw new IllegalStateException("Unstarted SSE cleanup identity");
                frame=null;output=null;token=null;session=null;lease=null;finished=true;
                handle.detach(this,after,terminal);
            }
            point("unstarted-detached",0);probe=null;
        }
        private void detachHeavy() {
            synchronized(cell) { frame=null;output=null;slot=null;worker=null;async=null;token=null; }
            // A test barrier may hold the following cell lock; its callback fully returns before refund.
            point("before-refund",0);probe=null;
        }
        void point(String phase,long value) { var currentProbe=probe;if(currentProbe!=null)currentProbe.accept(phase,value); }
        void fresh() { synchronized(cell) { if(stopping||slot==null)throw new IllegalStateException("SSE output stopped");slot.requireFresh(); } }
        long left() { return Math.min(deadline,idleDeadline)-System.nanoTime(); }
        boolean awaitWork() {
            for(;;) {
                synchronized(cell) {
                    if(stopping)return false;
                    if(slot!=null)return true;
                    if(eof||left()<=0)return false;
                }
                // Only the inert shell reaches this point; no material local or application gate.
                point(installed?"ready-idle":"bootstrap-wait",0);
                synchronized(cell) {
                    if(stopping)return false;
                    if(slot!=null)return true;
                    if(eof||left()<=0)return false;
                    try { TimeUnit.NANOSECONDS.timedWait(cell,left()); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
                }
            }
        }
        void publish(PortalEventsMaterialVerifier.VerifiedWindow window,boolean isEvent)throws IOException {
            synchronized(cell) {
                if(stopping||slot!=null)throw new IOException("SSE publication stopped");
                window.requireFresh();slot=window;event=isEvent;offset=0;done=false;frameDone=false;attempted=false;flushed=false;cell.notifyAll();
                boolean interrupted=false;
                while(!done) {
                    // Stop never ends the borrow while a heavy helper is still on a callback stack.
                    if(stopping&&heavy==0) { slot=null;done=true;cell.notifyAll();break; }
                    long remaining=left();
                    if(remaining<=0&&heavy==0)throw new IOException("SSE original deadline");
                    try { TimeUnit.NANOSECONDS.timedWait(cell,remaining>0?remaining:TimeUnit.SECONDS.toNanos(1)); }
                    catch(InterruptedException failure) { interrupted=true;stopping=true;cell.notifyAll(); }
                }
                if(interrupted)Thread.currentThread().interrupt();
                if(stopping)throw new IOException("SSE stopped");
            }
        }
        void read() {
            try {
                session.consumeVerified(window->{
                    point("window",window.eventSeq()==null?0:window.eventSeq());
                    if(window.resetReason()!=null) {
                        frame.control(window,true);publish(window,false);throw new IOException("SSE reset complete");
                    }
                    if(!statusSent) { frame.control(window,false);publish(window,false);statusSent=true; }
                    if(window.eventSeq()!=null) { frame.event(window);publish(window,true); }
                    if(window.drained()) { synchronized(cell) { terminal=true; }throw new IOException("SSE terminal drained"); }
                });
            } catch(RuntimeException failure) { /* Bounded outcome only; no exception graph retained. */ }
            finally {
                boolean cancelled;
                synchronized(cell) { eof=true;if(heavy==0) { slot=null;done=true; }cancelled=stopping;cell.notifyAll(); }
                // The same sole worker is now OUTSIDE the synchronous material/Http operation.
                // It can terminate a not-ready public request without needing a future callback.
                if(cancelled)stop();
                point("worker-body-exit",0);
            }
        }
        @Override public void onWritePossible()throws IOException {
            point("callback-entry",installed?1:0);
            try {
                while(awaitWork()) {
                    boolean ready;
                    synchronized(cell) { heavy++; }
                    try { ready=writeSlot(); } // Heavy locals end before detaching the slot or ready-idle wait.
                    finally { synchronized(cell) {
                        heavy--;
                        if(frameDone||stopping) { slot=null;done=true;cell.notifyAll(); }
                    } }
                    if(!ready)return;
                }
                complete();
            } catch(IOException|RuntimeException failure) { stop();complete(); }
        }
        private boolean writeSlot()throws IOException {
            PortalEventsMaterialVerifier.VerifiedWindow window;
            synchronized(cell) { if(stopping||slot==null)return true;window=slot; }
                while(true) {
                    synchronized(io) {
                        fresh();point("before-ready",offset);window.requireFresh();
                        boolean ready=output.isReady();window.requireFresh();
                        if(!ready) { point("ready-false",offset);return false; }
                        if(offset<frame.count) {
                            int n=Math.min(8192,frame.count-offset);
                            attempted=true;point("before-write",offset);window.requireFresh();
                            output.write(frame.bytes,offset,n);window.requireFresh();offset+=n;point("after-write",offset);
                            idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
                        } else if(!flushed) {
                            point("before-flush",offset);window.requireFresh();output.flush();window.requireFresh();flushed=true;
                            if(!output.isReady()) { point("flush-wait",offset);return false; }
                            window.requireFresh();
                        } else {
                            window.requireFresh();
                            if(event) { window.confirmEventFlushed();synchronized(cell) { after=window.eventSeq(); } }
                            frameDone=true;point("frame-complete",offset);return true;
                        }
                    }
                }
        }
        void complete() {
            synchronized(io) { synchronized(cell) { if(completeRequested||completed)return;completeRequested=true; } }
            try { async.complete(); } catch(IllegalStateException ignored) { /* No completion evidence: retain Owner. */ }
        }
        void stop() {
            PortalRunEventsHttp.Session active;
            synchronized(io) {
                synchronized(cell) { if(abortIssued||finished||finalizing)return;stopping=true;abortIssued=true;actions++;active=session;cell.notifyAll(); }
            }
            try {
                if(active!=null)active.abort();
                var currentToken=token;if(currentToken!=null)currentToken.abort(this);
            } finally { synchronized(cell) { actions--;cell.notifyAll(); } }
        }
        void cancelSignal() { synchronized(cell) { stopping=true;cell.notifyAll(); } }
        @Override public void onError(Throwable failure) {
            PortalRunEventsHttp.Session active;
            synchronized(io) { synchronized(cell) { stopping=true;active=session;cell.notifyAll(); } }
            if(active!=null)active.abort();
            complete();
        }
        @Override public void onTimeout(AsyncEvent event) { stop();complete(); }
        @Override public void onError(AsyncEvent event) { onError(event.getThrowable()); }
        @Override public void onStartAsync(AsyncEvent event) { stop();complete(); }
        @Override public void onComplete(AsyncEvent event) {
            point("completion-entry",0);
            var currentToken=token;if(currentToken!=null)currentToken.disarm(this);
            point("token-disarmed",0);
            synchronized(io) { synchronized(cell) { stopping=true;completed=true;cell.notifyAll(); } }
            finish(Duration.ofSeconds(2));
        }
        private boolean awaitWorker(long end) {
            Thread thread;synchronized(cell) { thread=worker; }
            if(thread==Thread.currentThread())return false;
            if(thread!=null)try { TimeUnit.NANOSECONDS.timedJoin(thread,Math.max(0,end-System.nanoTime())); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
            return thread==null||!thread.isAlive();
        }
        boolean finish(Duration timeout) {
            if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");
            RequestSlot currentToken=token;
            if(Thread.holdsLock(io)||currentToken!=null&&Thread.holdsLock(currentToken))return false;
            long end=System.nanoTime()+timeout.toNanos();
            synchronized(cell) {
                if(worker==Thread.currentThread())return false;
                while(!finished&&(finalizing||!completed||actions!=0||heavy!=0)) {
                    long remaining=end-System.nanoTime();if(remaining<=0)return false;
                    try { TimeUnit.NANOSECONDS.timedWait(cell,remaining); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
                }
                if(finished)return true;
                // Elect BEFORE any Thread capture: another finisher may retain only this anchored State.
                finalizing=true;
            }
            try {
                // Actual onComplete already published stopping. Wake original private IO as well;
                // this abort helper returns before join, and releases no lease/material/Owner fee.
                session.abort();
                if(!awaitWorker(end))return false;
                synchronized(cell) {
                    if(worker!=null&&worker.isAlive()||actions!=0||heavy!=0||slot!=null)return false;
                }
                var closing=session;
                detachHeavy(); // Thread/probe/heavy-bearing helpers have actually returned.
                if(lease!=null) { lease.release();lease=null; }
                closing.close();boolean result=closing.awaitClosed(Duration.ofNanos(Math.max(0,end-System.nanoTime())));
                synchronized(cell) {
                    if(result) { session=null;token=null;probe=null;handle.detach(this,after,terminal); }
                    finished=result;
                }
                return result;
            } finally { synchronized(cell) { finalizing=false;cell.notifyAll(); } }
        }
    }
    private static final class RequestSlot {
        private org.apache.catalina.connector.Response response;
        private WrappedProcessor origin;
        private SocketWrapperBase<?> originalSocket;
        private State state;
        private Thread requestThread;
        private boolean armed,used,claimed;
        RequestSlot(org.apache.catalina.connector.Response response,WrappedProcessor origin,SocketWrapperBase<?> socket) { this.response=response;this.origin=origin;originalSocket=socket;requestThread=Thread.currentThread(); }
        synchronized void requireOriginal(HttpServletRequest request,HttpServletResponse supplied) {
            requireScope(request);
            if(response.getResponse()!=supplied||response.getRequest().getRequest()!=request)
                throw new IllegalStateException("Original controlled REQUEST scope required");
            claimed=true;
        }
        synchronized void requireCaptured(HttpServletRequest request,HttpServletResponse supplied,PortalEventsRequest capture){
            capture.requireLive(request,supplied);requireScope(request);
            // Security/MVC supplies wrappers. Only the captured exact argument pair may use this
            // public Servlet-wrapper adaptation; stream acquisition always uses supplied above.
            if(!contains(request,response.getRequest().getRequest())||!contains(supplied,response.getResponse()))
                throw new IllegalStateException("Original controlled MVC facades required");
            claimed=true;
        }
        private void requireScope(HttpServletRequest request){
            if(request.getDispatcherType()!=DispatcherType.REQUEST||requestThread!=Thread.currentThread()||bridge==null||bridge.current()!=origin||origin==null||origin.slot!=this||origin.socket!=originalSocket||originalSocket==null||response==null||armed||used||claimed)
                throw new IllegalStateException("Original controlled REQUEST scope required");
        }
        private static boolean contains(ServletRequest supplied,ServletRequest exact){
            for(int i=0;i<32&&supplied!=null;i++){
                if(supplied==exact)return true;
                if(!(supplied instanceof ServletRequestWrapper wrapper))return false;
                supplied=wrapper.getRequest();
            }return false;
        }
        private static boolean contains(ServletResponse supplied,ServletResponse exact){
            for(int i=0;i<32&&supplied!=null;i++){
                if(supplied==exact)return true;
                if(!(supplied instanceof ServletResponseWrapper wrapper))return false;
                supplied=wrapper.getResponse();
            }return false;
        }
        synchronized void reject() { if(origin!=null&&origin.slot==this)origin.slot=null;invalidate(); }
        synchronized void arm(State state) { if(armed||used||response==null)throw new IllegalStateException("SSE request token unavailable");this.state=state;armed=true; }
        void abort(State expected) {
            // Never wait for the real lifecycle exclusion while holding an application monitor.
            SocketWrapperBase<?> original;WrappedProcessor processor;
            synchronized(this) { original=originalSocket;processor=origin;if(original==null||processor==null)return; }
            var lock=original.getLock();lock.lock();
            try { synchronized(expected.io) { synchronized(this) {
                if(!armed||used||state!=expected||response==null||origin!=processor||originalSocket!=original||processor.socket!=original||processor.slot!=this)return;
                used=true;expected.point("before-close-now",0);
                response.getCoyoteResponse().action(ActionCode.CLOSE_NOW,new IOException("SSE terminated"));
                expected.point("close-now-return",0);
            } } } finally { lock.unlock(); }
        }
        synchronized void disarm(State expected) { if(state==expected) { if(origin!=null&&origin.slot==this)origin.slot=null;invalidate(); } }
        synchronized void invalidate() { armed=false;state=null;response=null;origin=null;originalSocket=null;requestThread=null; }
    }
    private static final class ContextValve extends ValveBase {
        ContextValve() { super(true); }
        @Override public void invoke(org.apache.catalina.connector.Request request,org.apache.catalina.connector.Response response)throws IOException,ServletException {
            WrappedProcessor current=bridge==null?null:bridge.current();
            if(request.getDispatcherType()==DispatcherType.REQUEST&&current!=null) {
                current.invalidate();request.removeAttribute(SLOT); // Replace the one current attribute; never retain token history.
                if("GET".equals(request.getMethod())&&request.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")) {
                    var token=new RequestSlot(response,current,current.socket);current.slot=token;request.setAttribute(SLOT,token);
                }
            }
            getNext().invoke(request,response);
        }
    }
    /** Fixed Tomcat10.1.41 HTTP/1.1 NIO profile; one instance for the whole JVM lifetime. */
    public static final class ControlledProtocol extends Http11NioProtocol {
        private static boolean constructed;
        private long creations,recycles,processes,lastProcessor,allocations,returned;
        private static final int BUNDLES=1536;
        private static final byte FREE=0,RESERVED=1,TRACKED=2,BURNED=3;
        private Reference<?>[] references;
        private byte[] slots;
        private ReferenceQueue<Object> queue;
        private boolean corrupt;
        private int failurePoint,handleFailurePoint;
        private long handleAllocations,handleReturned,processorReturned;
        public ControlledProtocol() {
            synchronized(ControlledProtocol.class) {
                if(constructed)throw new IllegalStateException("Only one controlled connector per JVM lifetime");
                PortalEventsPacketBudget.SHARED.reserveConnectorBaseline();
                constructed=true;bridge=new Bridge();references=new Reference<?>[BUNDLES];slots=new byte[BUNDLES];queue=new ReferenceQueue<>();
            }
            setMaxConnections(1024);setMaxThreads(64);setProcessorCache(64);
        }
        private void verify(boolean started) {
            if(getMaxConnections()!=1024||getMaxThreads()!=64||getProcessorCache()!=64||getEndpoint().getUseVirtualThreads()||findUpgradeProtocols().length!=0)
                throw new IllegalStateException("Controlled HTTP/1.1 NIO profile changed");
            var executor=getExecutor();
            if(executor!=null&&!(executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool&&pool.getMaximumPoolSize()==64)||started&&executor==null)
                throw new IllegalStateException("Controlled internal executor required");
        }
        @Override public void setExecutor(java.util.concurrent.Executor executor) { if(executor!=null)throw new IllegalArgumentException("Custom executor prohibited");super.setExecutor(null); }
        @Override public void start()throws Exception { verify(false);super.start();verify(true); }
        // Both public paths use the ORIGINAL key. A historical CHM node must not own a wrapper.
        private Processor waitingKey(Processor processor) {
            if(processor instanceof WrappedProcessor owned) {
                if(owned.protocol!=this)throw new IllegalArgumentException("Foreign controlled processor");
                return owned.delegate;
            }
            return processor;
        }
        @Override public void addWaitingProcessor(Processor processor) { super.addWaitingProcessor(waitingKey(processor)); }
        @Override public void removeWaitingProcessor(Processor processor) { super.removeWaitingProcessor(waitingKey(processor)); }
        @Override protected synchronized Processor createProcessor() {
            verify(true);drainReferences(); // This reference-bearing frame fully RETURNS before credit is used.
            if(corrupt)throw new IllegalStateException("Controlled allocation identity failed");
            int index=0;while(index<BUNDLES&&slots[index]!=FREE)index++;
            if(index==BUNDLES)throw new IllegalStateException("Controlled processor allocation exhausted");
            slots[index]=RESERVED;int injectedFailure=failurePoint;failurePoint=0;
            try {
                Processor original=super.createProcessor();
                var wrapped=new WrappedProcessor(this,original,creations+1);allocations++;
                if(injectedFailure==1)throw new IllegalStateException("Controlled construction failure probe");
                var reference=new SlotReference(original,queue,index);references[index]=reference;
                if(injectedFailure==2)throw new IllegalStateException("Controlled registration failure probe");
                slots[index]=TRACKED;creations++;
                Reference.reachabilityFence(original);Reference.reachabilityFence(wrapped);
                return wrapped;
            } catch(RuntimeException|Error failure) { slots[index]=BURNED;throw failure; }
        }
        private synchronized Handle createHandle() {
            drainReferences();int index=freeIndex();slots[index]=RESERVED;
            int injectedFailure=handleFailurePoint;handleFailurePoint=0;
            Handle handle=null;
            try {
                handle=new Handle();handleAllocations++;
                if(injectedFailure==1)throw new IllegalStateException("Controlled Handle construction failure probe");
                references[index]=new HandleReference(handle,queue,index);
                if(injectedFailure==2)throw new IllegalStateException("Controlled Handle registration failure probe");
                slots[index]=TRACKED;return handle;
            } catch(RuntimeException|Error failure) { slots[index]=BURNED;throw failure; }
            finally { Reference.reachabilityFence(handle); }
        }
        private int freeIndex() {
            if(corrupt)throw new IllegalStateException("Controlled allocation identity failed");
            int index=0;while(index<BUNDLES&&slots[index]!=FREE)index++;
            if(index==BUNDLES)throw new IllegalStateException("Controlled shared allocation exhausted");
            return index;
        }
        private void drainReferences() {
            for(Reference<?> reference;(reference=queue.poll())!=null;) {
                int index;
                if(reference instanceof SlotReference exact)index=exact.index;
                else if(reference instanceof HandleReference exact)index=exact.index;
                else { corrupt=true;return; }
                if(index<0||index>=BUNDLES||references[index]!=reference) { corrupt=true;return; }
                if(slots[index]==BURNED)continue;
                if(slots[index]!=TRACKED) { corrupt=true;return; }
                references[index]=null;slots[index]=FREE;returned++;
                if(reference instanceof HandleReference)handleReturned++;else processorReturned++;
            }
        }
        synchronized void failHandleConstructionForTest(int point) { if(point<1||point>2)throw new IllegalArgumentException();handleFailurePoint=point; }
        synchronized long handleAllocationsForTest() { return handleAllocations; }
        synchronized long handleReturnedForTest() { drainReferences();return handleReturned; }
        synchronized long processorReturnedForTest() { drainReferences();return processorReturned; }
        synchronized void failConstructionForTest(int point) { if(point<1||point>2)throw new IllegalArgumentException();failurePoint=point; }
        synchronized long allocationsForTest() { return allocations; }
        synchronized int burnedForTest() { int n=0;for(byte state:slots)if(state==BURNED)n++;return n; }
        synchronized long returnedForTest() { drainReferences();return returned; }
        boolean waitingIdentityForTest(Processor processor) {
            Processor original=waitingKey(processor);int before=getWaitingProcessorCount();
            addWaitingProcessor(processor);super.removeWaitingProcessor(original);
            if(getWaitingProcessorCount()!=before)return false;
            super.addWaitingProcessor(original);removeWaitingProcessor(processor);
            return getWaitingProcessorCount()==before;
        }
        synchronized long creationsForTest() { return creations; }
        synchronized long recyclesForTest() { return recycles; }
        synchronized long processorForTest() { return lastProcessor; }
        SocketWrapperBase<?> currentSocketForTest() { var current=bridge.current();return current==null?null:current.socket; }
    }
    /** The only referent edge is PhantomReference's GC-managed field; no delegate/history copy. */
    private static final class SlotReference extends PhantomReference<Processor> {
        private final int index;
        SlotReference(Processor original,ReferenceQueue<Object> queue,int index) { super(original,queue);this.index=index; }
    }
    private static final class HandleReference extends PhantomReference<Handle> {
        private final int index;
        HandleReference(Handle handle,ReferenceQueue<Object> queue,int index) { super(handle,queue);this.index=index; }
    }
    /** Fixed64 short scopes; no per-event object, task, permission or connection registry. */
    private static final class Bridge {
        final Thread[] threads=new Thread[64];
        final WrappedProcessor[] processors=new WrappedProcessor[64];
        synchronized WrappedProcessor current() { Thread thread=Thread.currentThread();for(int i=0;i<64;i++)if(threads[i]==thread)return processors[i];return null; }
    }
    private static final class WrappedProcessor implements Processor {
        private final ControlledProtocol protocol;
        private final Processor delegate;
        private final long id;
        private volatile SocketWrapperBase<?> socket;
        private volatile RequestSlot slot;
        WrappedProcessor(ControlledProtocol protocol,Processor delegate,long id) { this.protocol=protocol;this.delegate=delegate;this.id=id; }
        void invalidate() { var previous=slot;if(previous!=null)previous.invalidate();slot=null; }
        @Override public SocketState process(SocketWrapperBase<?> actual,SocketEvent event)throws IOException {
            var lock=actual.getLock();lock.lock();int index=-1;WrappedProcessor previous=null;
            try {
                protocol.verify(true);socket=actual;
                synchronized(bridge) {
                    Thread thread=Thread.currentThread();
                    for(int i=0;i<64;i++)if(bridge.threads[i]==thread) { index=i;break; }
                    if(index<0)for(int i=0;i<64;i++)if(bridge.threads[i]==null) { index=i;break; }
                    if(index<0)throw new IOException("Controlled processing scopes exhausted");
                    previous=bridge.processors[index];bridge.threads[index]=thread;bridge.processors[index]=this;
                }
                synchronized(protocol) { protocol.processes++;protocol.lastProcessor=id; }
                SocketState result=delegate.process(actual,event);
                if(result!=SocketState.LONG&&result!=SocketState.ASYNC_END)invalidate();
                return result;
            } catch(IOException|RuntimeException|Error failure) { invalidate();throw failure; }
            finally {
                if(index>=0)synchronized(bridge) { bridge.processors[index]=previous;if(previous==null)bridge.threads[index]=null; }
                lock.unlock();
            }
        }
        @Override public void recycle() {
            SocketWrapperBase<?> old=socket;
            if(old==null) { invalidate();protocol.removeWaitingProcessor(this);delegate.recycle();return; }
            var lock=old.getLock();lock.lock();
            try {
                // Off-callback close/release cannot pool or reuse the delegate across an old action.
                State retiring=slot==null?null:slot.state;
                invalidate();if(retiring!=null)retiring.point("recycle-invalidated",0);
                protocol.removeWaitingProcessor(this);delegate.recycle();socket=null;
                synchronized(protocol) { protocol.recycles++; }
            } finally { lock.unlock(); }
        }
        @Override public UpgradeToken getUpgradeToken() { return delegate.getUpgradeToken(); }
        @Override public boolean isUpgrade() { return delegate.isUpgrade(); }
        @Override public boolean isAsync() { return delegate.isAsync(); }
        @Override public void timeoutAsync(long now) { delegate.timeoutAsync(now); }
        @Override public org.apache.coyote.Request getRequest() { return delegate.getRequest(); }
        @Override public void setSslSupport(SSLSupport ssl) { delegate.setSslSupport(ssl); }
        @Override public ByteBuffer getLeftoverInput() { return delegate.getLeftoverInput(); }
        @Override public void pause() { delegate.pause(); }
        @Override public boolean checkAsyncTimeoutGeneration() { return delegate.checkAsyncTimeoutGeneration(); }
    }
}
