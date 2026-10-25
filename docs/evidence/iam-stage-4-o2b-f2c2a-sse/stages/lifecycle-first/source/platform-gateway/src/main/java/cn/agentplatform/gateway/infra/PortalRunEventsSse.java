package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.PlatformException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;

/** Unassembled HTTP/1.1 NIO adapter. Only a real Session can supply an output window. */
public final class PortalRunEventsSse {
    static final int FRAME_BYTES=270374;
    private static final String SLOT=PortalRunEventsSse.class.getName()+".request";
    private final BiConsumer<String,Long> probe;
    public PortalRunEventsSse() { this(null); }
    PortalRunEventsSse(BiConsumer<String,Long> probe) { this.probe=probe; }

    /** Explicit context installation; this class has no bean, controller or scan annotations. */
    static Valve contextValve() { return new ContextValve(); }

    public Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp.Session session)throws IOException {
        Objects.requireNonNull(session);
        Object attribute=request.getAttribute(SLOT);
        if(!(attribute instanceof RequestSlot token))throw new IllegalStateException("Exact SSE context Valve required");
        session.lookupMetadata();
        var lease=session.beginSse();
        State state;
        try { state=new State(session,lease,token,probe); }
        catch(RuntimeException|Error failure) { lease.release();session.close();throw failure; }
        var handle=new Handle(state);
        try {
            state.async=request.startAsync();
            state.async.setTimeout(0); // Independent original absolute deadlines drive every wait.
            state.async.addListener(state);
            token.arm(state);
            state.worker=Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-sse-reader").unstarted(state::read);
            state.worker.start();
            if(!state.awaitWork())throw new IOException("First verified window unavailable");
            synchronized(state.io) {
                state.fresh();
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control","no-cache");
                state.output=response.getOutputStream();
                state.fresh();
                state.output.setWriteListener(state);
                state.installed=true;
                state.point("setter-return",0);
            }
            return handle;
        } catch(IOException|RuntimeException|Error failure) {
            state.stop();
            throw failure;
        }
    }
    public static final class Handle implements AutoCloseable {
        private final State state;
        private Handle(State state) { this.state=state; }
        @Override public void close() { state.stop(); }
        public boolean awaitClosed(Duration timeout) { return state.finish(timeout); }
        long after() { synchronized(state.cell) { return state.after; } }
        boolean terminal() { synchronized(state.cell) { return state.terminal; } }
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
        final RequestSlot token;
        final BiConsumer<String,Long> probe;
        PortalRunEventsHttp.Session session;
        PortalRunEventsHttp.Session.SseLease lease;
        AsyncContext async;
        ServletOutputStream output;
        Frame frame;
        Thread worker;
        PortalEventsMaterialVerifier.VerifiedWindow slot;
        final long deadline;
        long idleDeadline,after;
        boolean installed,stopping,eof,completed,completeRequested,finalizing,finished,terminal,statusSent,event,done,frameDone,attempted,flushed;
        int offset,heavy,actions;
        State(PortalRunEventsHttp.Session session,PortalRunEventsHttp.Session.SseLease lease,RequestSlot token,BiConsumer<String,Long> probe) {
            this.session=session;this.lease=lease;this.token=token;this.probe=probe;deadline=lease.deadline();
            frame=new Frame();idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
        }
        void point(String phase,long value) { if(probe!=null)probe.accept(phase,value); }
        void fresh() { synchronized(cell) { if(stopping||slot==null)throw new IllegalStateException("SSE output stopped");slot.requireFresh(); } }
        long left() { return Math.min(deadline,idleDeadline)-System.nanoTime(); }
        boolean awaitWork() {
            synchronized(cell) {
                for(;;) {
                    if(stopping)return false;
                    if(slot!=null)return true;
                    if(eof||left()<=0)return false;
                    point("ready-idle",0);
                    try { TimeUnit.NANOSECONDS.timedWait(cell,left()); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
                }
            }
        }
        void publish(PortalEventsMaterialVerifier.VerifiedWindow window,boolean isEvent)throws IOException {
            synchronized(cell) {
                if(stopping||slot!=null)throw new IOException("SSE publication stopped");
                window.requireFresh();slot=window;event=isEvent;offset=0;done=false;frameDone=false;attempted=false;flushed=false;cell.notifyAll();
                while(!done) {
                    // Stop never ends the borrow while a heavy helper is still on a callback stack.
                    if(stopping&&heavy==0) { slot=null;done=true;cell.notifyAll();break; }
                    long remaining=left();
                    if(remaining<=0&&heavy==0)throw new IOException("SSE original deadline");
                    try { TimeUnit.NANOSECONDS.timedWait(cell,Math.max(1,remaining)); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IOException(failure); }
                }
                if(stopping)throw new IOException("SSE stopped");
            }
        }
        void read() {
            try {
                session.consumeVerified(window->{
                    point("window",window.eventSeq()==null?0:window.eventSeq());
                    if(window.resetReason()!=null) {
                        frame.control(window,true);publish(window,false);synchronized(cell) { terminal=true; }throw new IOException("SSE reset complete");
                    }
                    if(!statusSent) { frame.control(window,false);publish(window,false);statusSent=true; }
                    if(window.eventSeq()!=null) { frame.event(window);publish(window,true); }
                    if(window.drained()) { synchronized(cell) { terminal=true; }throw new IOException("SSE terminal drained"); }
                });
            } catch(RuntimeException|IOException failure) { /* Bounded outcome only; no exception graph retained. */ }
            finally {
                synchronized(cell) { eof=true;cell.notifyAll(); }
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
                        fresh();point("before-ready",offset);
                        boolean ready=output.isReady();window.requireFresh();
                        if(!ready) { point("ready-false",offset);return false; }
                        if(offset<frame.count) {
                            int n=Math.min(8192,frame.count-offset);
                            attempted=true;point("before-write",offset);
                            output.write(frame.bytes,offset,n);window.requireFresh();offset+=n;point("after-write",offset);
                            idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
                        } else if(!flushed) {
                            point("before-flush",offset);output.flush();window.requireFresh();flushed=true;
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
                synchronized(cell) { if(stopping)return;stopping=true;actions++;active=session;cell.notifyAll(); }
            }
            try {
                if(active!=null)active.abort();
                synchronized(io) { token.abort(this); }
            } finally { synchronized(cell) { actions--;cell.notifyAll(); } }
        }
        @Override public void onError(Throwable failure) { synchronized(io) { synchronized(cell) { stopping=true;cell.notifyAll(); } }complete(); }
        @Override public void onTimeout(AsyncEvent event) { stop();complete(); }
        @Override public void onError(AsyncEvent event) { onError(event.getThrowable()); }
        @Override public void onStartAsync(AsyncEvent event) { stop();complete(); }
        @Override public void onComplete(AsyncEvent event) {
            token.disarm(this);
            synchronized(io) { synchronized(cell) { stopping=true;completed=true;cell.notifyAll(); } }
            finish(Duration.ofSeconds(2));
        }
        boolean finish(Duration timeout) {
            if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");
            long end=System.nanoTime()+timeout.toNanos();Thread thread;
            synchronized(cell) { if(finished)return true;if(finalizing||!completed||actions!=0||heavy!=0)return false;thread=worker; }
            if(thread==Thread.currentThread())return false;
            if(thread!=null)try { TimeUnit.NANOSECONDS.timedJoin(thread,Math.max(0,end-System.nanoTime())); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
            synchronized(cell) {
                if(finished)return true;
                if(finalizing||thread!=null&&thread.isAlive()||actions!=0||heavy!=0||slot!=null)return false;
                finalizing=true;
            }
            var closing=session;
            // All heavy references detach before the lease permits Http's old cleanup/fee return.
            synchronized(cell) { frame=null;output=null;slot=null;worker=null;async=null; }
            if(lease!=null) { lease.release();lease=null; }
            closing.close();boolean result=closing.awaitClosed(Duration.ofNanos(Math.max(0,end-System.nanoTime())));
            synchronized(cell) { if(result)session=null;finished=result;finalizing=false;cell.notifyAll(); }
            return result;
        }
    }
    private static final class RequestSlot {
        private org.apache.catalina.connector.Response response;
        private State state;
        private boolean armed,used;
        RequestSlot(org.apache.catalina.connector.Response response) { this.response=response; }
        synchronized void arm(State state) { if(armed||used||response==null)throw new IllegalStateException("SSE request token unavailable");this.state=state;armed=true; }
        synchronized void abort(State expected) {
            if(!armed||used||state!=expected||response==null)return;
            used=true;response.getCoyoteResponse().action(ActionCode.CLOSE_NOW,new IOException("SSE terminated"));
        }
        synchronized void disarm(State expected) { if(state==expected) { armed=false;state=null;response=null; } }
    }
    private static final class ContextValve extends ValveBase {
        ContextValve() { super(true); }
        @Override public void invoke(org.apache.catalina.connector.Request request,org.apache.catalina.connector.Response response)throws IOException,ServletException {
            if("GET".equals(request.getMethod())&&request.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events"))request.setAttribute(SLOT,new RequestSlot(response));
            getNext().invoke(request,response);
        }
    }
}
