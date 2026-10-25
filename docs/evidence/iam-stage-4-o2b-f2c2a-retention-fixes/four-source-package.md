# platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsSse.java

1: package cn.agentplatform.gateway.infra;
2: 
3: import cn.agentplatform.common.PlatformException;
4: import cn.agentplatform.common.authority.CurrentAuthorityClient;
5: import jakarta.servlet.*;
6: import jakarta.servlet.http.*;
7: import java.io.*;
8: import java.lang.ref.*;
9: import java.time.Duration;
10: import java.util.*;
11: import java.util.concurrent.TimeUnit;
12: import java.util.function.BiConsumer;
13: import org.apache.catalina.Valve;
14: import org.apache.catalina.valves.ValveBase;
15: import org.apache.coyote.ActionCode;
16: import org.apache.coyote.Processor;
17: import org.apache.coyote.UpgradeToken;
18: import org.apache.coyote.http11.Http11NioProtocol;
19: import org.apache.tomcat.util.net.*;
20: import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
21: import java.nio.ByteBuffer;
22: 
23: /** Unassembled HTTP/1.1 NIO adapter. Only a real Session can supply an output window. */
24: public final class PortalRunEventsSse {
25:     static final int FRAME_BYTES=270374;
26:     private static final String SLOT=PortalRunEventsSse.class.getName()+".request";
27:     private static Bridge bridge;
28:     private final BiConsumer<String,Long> probe;
29:     public PortalRunEventsSse() { this(null); }
30:     PortalRunEventsSse(BiConsumer<String,Long> probe) { this.probe=probe; }
31: 
32:     /** Explicit context installation; this class has no bean, controller or scan annotations. */
33:     static Valve contextValve() { return new ContextValve(); }
34: 
35:     public Handle start(HttpServletRequest request,HttpServletResponse response,PortalRunEventsHttp http,CurrentAuthorityClient authority,String source,String run,long cursor)throws IOException {
36:         Objects.requireNonNull(http);
37:         RequestSlot token=null;PortalRunEventsHttp.Session session=null;PortalRunEventsHttp.Session.SseLease lease=null;State state=null;Handle handle=null;
38:         try {
39:             Object attribute=request.getAttribute(SLOT);
40:             if(!(attribute instanceof RequestSlot exact))throw new IllegalStateException("Exact controlled SSE lifecycle required");
41:             token=exact;token.requireOriginal(request,response);
42:             // One shared credit is registered before the only Session/State construction attempt.
43:             handle=token.origin.protocol.createHandle();
44:             session=http.forSse(authority,source,run,cursor,handle);
45:             session.lookupMetadata();lease=session.beginSse();handle.claimState();state=new State(handle,session,lease,token,probe);
46:             handle.attach(state);
47:             lease.cancellation(state::cancelSignal);lease.exits(state::finish);
48:             token.arm(state);
49:             state.async=request.startAsync();
50:             state.async.setTimeout(0); // Independent original absolute deadlines drive every wait.
51:             state.async.addListener(state);
52:             state.worker=Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-sse-reader").unstarted(state::read);
53:             state.worker.setContextClassLoader(PortalRunEventsSse.class.getClassLoader());
54:             state.worker.start();
55:             if(!state.awaitWork())throw new IOException("First verified window unavailable");
56:             synchronized(state.io) {
57:                 state.point("before-getter",0);
58:                 state.fresh();
59:                 response.setContentType("text/event-stream");
60:                 response.setCharacterEncoding("UTF-8");
61:                 response.setHeader("Cache-Control","no-cache");
62:                 state.output=response.getOutputStream();
63:                 state.point("after-getter",0);
64:                 state.fresh();
65:                 state.output.setWriteListener(state);
66:                 state.installed=true;
67:                 state.point("setter-return",0);
68:             }
69:             return handle;
70:         } catch(IOException|RuntimeException|Error failure) {
71:             if(state==null||state.async==null) {
72:                 if(token!=null)token.reject();
73:                 if(state!=null)state.detachUnstarted(); // Returns before any fee/Owner can be released.
74:                 if(lease!=null)lease.release();
75:                 if(session!=null)session.close(); // Http joins its actually-started transport/timer before refund.
76:             } else {
77:                 state.stop();state.complete();session.close(); // Actual completion/worker/action gates still apply.
78:             }
79:             throw failure;
80:         } finally { Reference.reachabilityFence(handle); }
81:     }
82:     public static final class Handle implements AutoCloseable {
83:         private State state;
84:         private long after;
85:         private boolean terminal;
86:         private boolean sessionAttempted,stateAttempted;
87:         private Handle() {}
88:         synchronized void claimSession() { if(sessionAttempted)throw new IllegalStateException("Unique SSE Session already attempted");sessionAttempted=true; }
89:         private synchronized void claimState() { if(!sessionAttempted||stateAttempted)throw new IllegalStateException("Unique SSE State already attempted");stateAttempted=true; }
90:         private synchronized void attach(State exact) { if(state!=null||!stateAttempted||exact.handle!=this)throw new IllegalStateException("Unique SSE State identity");state=exact; }
91:         private synchronized State active() { return state; }
92:         private synchronized void detach(State expected,long cursor,boolean terminal) { if(state==expected) { after=cursor;this.terminal=terminal;state=null; } }
93:         @Override public void close() { var current=active();if(current!=null)current.stop(); }
94:         public boolean awaitClosed(Duration timeout) { if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");var current=active();return current==null||current.finish(timeout); }
95:         long after() { var current=active();if(current!=null)synchronized(current.cell) { return current.after; }synchronized(this) { return after; } }
96:         boolean terminal() { var current=active();if(current!=null)synchronized(current.cell) { return current.terminal; }synchronized(this) { return terminal; } }
97:         void wakeForTest() { var current=active();if(current!=null)synchronized(current.cell) { current.cell.notifyAll(); } }
98:         void interruptReaderForTest() { var current=active();if(current==null)return;Thread thread;synchronized(current.cell) { thread=current.worker; }if(thread!=null)thread.interrupt(); }
99:         boolean inertTokenForTest() { var current=active();if(current==null)return true;var token=current.token;if(token==null)return true;synchronized(token) { return token.origin==null&&token.response==null&&token.originalSocket==null&&token.state==null&&token.requestThread==null; } }
100:         synchronized boolean detachedForTest() { return state==null; }
101:     }
102:     /** One fixed backing, including control frames; no UTF-8 intermediate backing or growth. */
103:     static final class Frame extends OutputStream {
104:         final byte[] bytes=new byte[FRAME_BYTES];
105:         int count;
106:         @Override public void write(int b) { if(count==bytes.length)throw limit();bytes[count++]=(byte)b; }
107:         @Override public void write(byte[] b,int off,int n) { Objects.checkFromIndexSize(off,n,b.length);if(n>bytes.length-count)throw limit();System.arraycopy(b,off,bytes,count,n);count+=n; }
108:         void ascii(String value) { for(int i=0;i<value.length();i++) { int c=value.charAt(i);if(c>127)throw limit();write(c); } }
109:         void type(String value) {
110:             if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>32)throw limit();
111:             int start=count;
112:             for(int i=0;i<value.length();) {
113:                 int c=value.codePointAt(i);i+=Character.charCount(c);
114:                 if(c==0||c=='\r'||c=='\n'||c>=0xd800&&c<=0xdfff)throw limit();
115:                 if(c<128)write(c);
116:                 else if(c<2048) { write(0xc0|c>>6);write(0x80|c&63); }
117:                 else if(c<65536) { write(0xe0|c>>12);write(0x80|c>>6&63);write(0x80|c&63); }
118:                 else { write(0xf0|c>>18);write(0x80|c>>12&63);write(0x80|c>>6&63);write(0x80|c&63); }
119:             }
120:             if(count-start>128)throw limit();
121:         }
122:         void event(PortalEventsMaterialVerifier.VerifiedWindow window)throws IOException {
123:             count=0;ascii("id: ");ascii(Long.toString(window.eventSeq()));ascii("\nevent: ");type(window.eventType());ascii("\ndata: ");
124:             int start=count;window.writeRawPayload(this);if(count-start>262144)throw limit();ascii("\n\n");
125:         }
126:         void control(PortalEventsMaterialVerifier.VerifiedWindow window,boolean reset) {
127:             count=0;
128:             if(!reset)ascii("event: stream_status\ndata: {\"chunkAvailable\":false,\"chunkReplay\":false,\"recovery\":\"LAST_EVENT_ID_AND_FINAL_SNAPSHOT\"}\n\n");
129:             else {
130:                 String reason=window.resetReason();if("EXPIRED".equals(reason))reason="CURSOR_EXPIRED";
131:                 if(!Set.of("CURSOR_AHEAD","CURSOR_EXPIRED").contains(reason))throw limit();
132:                 String status=window.status();if(!status.matches("[A-Z_]{1,32}"))throw limit();
133:                 ascii("event: stream_reset\ndata: {\"reason\":\"");ascii(reason);ascii("\",\"chunkReplay\":false,\"recovery\":\"FINAL_SNAPSHOT\",\"lastEventId\":");
134:                 ascii(Long.toString(window.after()));ascii(",\"status\":\"");ascii(status);ascii("\",\"eventsWatermark\":");ascii(Long.toString(window.watermark()));ascii("}\n\n");
135:             }
136:         }
137:     }
138:     private static PlatformException limit() { return new PlatformException(413,"EVENT_LIMIT","事件响应过大"); }
139: 
140:     private static final class State implements WriteListener,AsyncListener {
141:         final Object cell=new Object(),io=new Object();
142:         RequestSlot token;
143:         final Handle handle;
144:         BiConsumer<String,Long> probe;
145:         PortalRunEventsHttp.Session session;
146:         PortalRunEventsHttp.Session.SseLease lease;
147:         AsyncContext async;
148:         ServletOutputStream output;
149:         Frame frame;
150:         Thread worker;
151:         PortalEventsMaterialVerifier.VerifiedWindow slot;
152:         final long deadline;
153:         long idleDeadline,after;
154:         boolean installed,stopping,abortIssued,eof,completed,completeRequested,finalizing,finished,terminal,statusSent,event,done,frameDone,attempted,flushed;
155:         int offset,heavy,actions;
156:         State(Handle handle,PortalRunEventsHttp.Session session,PortalRunEventsHttp.Session.SseLease lease,RequestSlot token,BiConsumer<String,Long> probe) {
157:             this.handle=handle;this.session=session;this.lease=lease;this.token=token;this.probe=probe;deadline=lease.deadline();
158:             idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));frame=new Frame();
159:         }
160:         private void detachUnstarted() {
161:             synchronized(cell) {
162:                 if(async!=null||worker!=null||actions!=0||heavy!=0||slot!=null)throw new IllegalStateException("Unstarted SSE cleanup identity");
163:                 frame=null;output=null;token=null;session=null;lease=null;finished=true;
164:                 handle.detach(this,after,terminal);
165:             }
166:             point("unstarted-detached",0);probe=null;
167:         }
168:         private void detachHeavy() {
169:             synchronized(cell) { frame=null;output=null;slot=null;worker=null;async=null;token=null; }
170:             // A test barrier may hold the following cell lock; its callback fully returns before refund.
171:             point("before-refund",0);probe=null;
172:         }
173:         void point(String phase,long value) { var currentProbe=probe;if(currentProbe!=null)currentProbe.accept(phase,value); }
174:         void fresh() { synchronized(cell) { if(stopping||slot==null)throw new IllegalStateException("SSE output stopped");slot.requireFresh(); } }
175:         long left() { return Math.min(deadline,idleDeadline)-System.nanoTime(); }
176:         boolean awaitWork() {
177:             for(;;) {
178:                 synchronized(cell) {
179:                     if(stopping)return false;
180:                     if(slot!=null)return true;
181:                     if(eof||left()<=0)return false;
182:                 }
183:                 // Only the inert shell reaches this point; no material local or application gate.
184:                 point(installed?"ready-idle":"bootstrap-wait",0);
185:                 synchronized(cell) {
186:                     if(stopping)return false;
187:                     if(slot!=null)return true;
188:                     if(eof||left()<=0)return false;
189:                     try { TimeUnit.NANOSECONDS.timedWait(cell,left()); }
190:                     catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
191:                 }
192:             }
193:         }
194:         void publish(PortalEventsMaterialVerifier.VerifiedWindow window,boolean isEvent)throws IOException {
195:             synchronized(cell) {
196:                 if(stopping||slot!=null)throw new IOException("SSE publication stopped");
197:                 window.requireFresh();slot=window;event=isEvent;offset=0;done=false;frameDone=false;attempted=false;flushed=false;cell.notifyAll();
198:                 boolean interrupted=false;
199:                 while(!done) {
200:                     // Stop never ends the borrow while a heavy helper is still on a callback stack.
201:                     if(stopping&&heavy==0) { slot=null;done=true;cell.notifyAll();break; }
202:                     long remaining=left();
203:                     if(remaining<=0&&heavy==0)throw new IOException("SSE original deadline");
204:                     try { TimeUnit.NANOSECONDS.timedWait(cell,remaining>0?remaining:TimeUnit.SECONDS.toNanos(1)); }
205:                     catch(InterruptedException failure) { interrupted=true;stopping=true;cell.notifyAll(); }
206:                 }
207:                 if(interrupted)Thread.currentThread().interrupt();
208:                 if(stopping)throw new IOException("SSE stopped");
209:             }
210:         }
211:         void read() {
212:             try {
213:                 session.consumeVerified(window->{
214:                     point("window",window.eventSeq()==null?0:window.eventSeq());
215:                     if(window.resetReason()!=null) {
216:                         frame.control(window,true);publish(window,false);throw new IOException("SSE reset complete");
217:                     }
218:                     if(!statusSent) { frame.control(window,false);publish(window,false);statusSent=true; }
219:                     if(window.eventSeq()!=null) { frame.event(window);publish(window,true); }
220:                     if(window.drained()) { synchronized(cell) { terminal=true; }throw new IOException("SSE terminal drained"); }
221:                 });
222:             } catch(RuntimeException failure) { /* Bounded outcome only; no exception graph retained. */ }
223:             finally {
224:                 boolean cancelled;
225:                 synchronized(cell) { eof=true;if(heavy==0) { slot=null;done=true; }cancelled=stopping;cell.notifyAll(); }
226:                 // The same sole worker is now OUTSIDE the synchronous material/Http operation.
227:                 // It can terminate a not-ready public request without needing a future callback.
228:                 if(cancelled)stop();
229:                 point("worker-body-exit",0);
230:             }
231:         }
232:         @Override public void onWritePossible()throws IOException {
233:             point("callback-entry",installed?1:0);
234:             try {
235:                 while(awaitWork()) {
236:                     boolean ready;
237:                     synchronized(cell) { heavy++; }
238:                     try { ready=writeSlot(); } // Heavy locals end before detaching the slot or ready-idle wait.
239:                     finally { synchronized(cell) {
240:                         heavy--;
241:                         if(frameDone||stopping) { slot=null;done=true;cell.notifyAll(); }
242:                     } }
243:                     if(!ready)return;
244:                 }
245:                 complete();
246:             } catch(IOException|RuntimeException failure) { stop();complete(); }
247:         }
248:         private boolean writeSlot()throws IOException {
249:             PortalEventsMaterialVerifier.VerifiedWindow window;
250:             synchronized(cell) { if(stopping||slot==null)return true;window=slot; }
251:                 while(true) {
252:                     synchronized(io) {
253:                         fresh();point("before-ready",offset);window.requireFresh();
254:                         boolean ready=output.isReady();window.requireFresh();
255:                         if(!ready) { point("ready-false",offset);return false; }
256:                         if(offset<frame.count) {
257:                             int n=Math.min(8192,frame.count-offset);
258:                             attempted=true;point("before-write",offset);window.requireFresh();
259:                             output.write(frame.bytes,offset,n);window.requireFresh();offset+=n;point("after-write",offset);
260:                             idleDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.SECONDS.toNanos(15));
261:                         } else if(!flushed) {
262:                             point("before-flush",offset);window.requireFresh();output.flush();window.requireFresh();flushed=true;
263:                             if(!output.isReady()) { point("flush-wait",offset);return false; }
264:                             window.requireFresh();
265:                         } else {
266:                             window.requireFresh();
267:                             if(event) { window.confirmEventFlushed();synchronized(cell) { after=window.eventSeq(); } }
268:                             frameDone=true;point("frame-complete",offset);return true;
269:                         }
270:                     }
271:                 }
272:         }
273:         void complete() {
274:             synchronized(io) { synchronized(cell) { if(completeRequested||completed)return;completeRequested=true; } }
275:             try { async.complete(); } catch(IllegalStateException ignored) { /* No completion evidence: retain Owner. */ }
276:         }
277:         void stop() {
278:             PortalRunEventsHttp.Session active;
279:             synchronized(io) {
280:                 synchronized(cell) { if(abortIssued||finished||finalizing)return;stopping=true;abortIssued=true;actions++;active=session;cell.notifyAll(); }
281:             }
282:             try {
283:                 if(active!=null)active.abort();
284:                 var currentToken=token;if(currentToken!=null)currentToken.abort(this);
285:             } finally { synchronized(cell) { actions--;cell.notifyAll(); } }
286:         }
287:         void cancelSignal() { synchronized(cell) { stopping=true;cell.notifyAll(); } }
288:         @Override public void onError(Throwable failure) {
289:             PortalRunEventsHttp.Session active;
290:             synchronized(io) { synchronized(cell) { stopping=true;active=session;cell.notifyAll(); } }
291:             if(active!=null)active.abort();
292:             complete();
293:         }
294:         @Override public void onTimeout(AsyncEvent event) { stop();complete(); }
295:         @Override public void onError(AsyncEvent event) { onError(event.getThrowable()); }
296:         @Override public void onStartAsync(AsyncEvent event) { stop();complete(); }
297:         @Override public void onComplete(AsyncEvent event) {
298:             point("completion-entry",0);
299:             var currentToken=token;if(currentToken!=null)currentToken.disarm(this);
300:             point("token-disarmed",0);
301:             synchronized(io) { synchronized(cell) { stopping=true;completed=true;cell.notifyAll(); } }
302:             finish(Duration.ofSeconds(2));
303:         }
304:         private boolean awaitWorker(long end) {
305:             Thread thread;synchronized(cell) { thread=worker; }
306:             if(thread==Thread.currentThread())return false;
307:             if(thread!=null)try { TimeUnit.NANOSECONDS.timedJoin(thread,Math.max(0,end-System.nanoTime())); }
308:             catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
309:             return thread==null||!thread.isAlive();
310:         }
311:         boolean finish(Duration timeout) {
312:             if(timeout.isNegative())throw new IllegalArgumentException("Negative close wait");
313:             RequestSlot currentToken=token;
314:             if(Thread.holdsLock(io)||currentToken!=null&&Thread.holdsLock(currentToken))return false;
315:             long end=System.nanoTime()+timeout.toNanos();
316:             synchronized(cell) {
317:                 if(worker==Thread.currentThread())return false;
318:                 while(!finished&&(finalizing||!completed||actions!=0||heavy!=0)) {
319:                     long remaining=end-System.nanoTime();if(remaining<=0)return false;
320:                     try { TimeUnit.NANOSECONDS.timedWait(cell,remaining); }
321:                     catch(InterruptedException failure) { Thread.currentThread().interrupt();return false; }
322:                 }
323:                 if(finished)return true;
324:                 // Elect BEFORE any Thread capture: another finisher may retain only this anchored State.
325:                 finalizing=true;
326:             }
327:             try {
328:                 // Actual onComplete already published stopping. Wake original private IO as well;
329:                 // this abort helper returns before join, and releases no lease/material/Owner fee.
330:                 session.abort();
331:                 if(!awaitWorker(end))return false;
332:                 synchronized(cell) {
333:                     if(worker!=null&&worker.isAlive()||actions!=0||heavy!=0||slot!=null)return false;
334:                 }
335:                 var closing=session;
336:                 detachHeavy(); // Thread/probe/heavy-bearing helpers have actually returned.
337:                 if(lease!=null) { lease.release();lease=null; }
338:                 closing.close();boolean result=closing.awaitClosed(Duration.ofNanos(Math.max(0,end-System.nanoTime())));
339:                 synchronized(cell) {
340:                     if(result) { session=null;token=null;probe=null;handle.detach(this,after,terminal); }
341:                     finished=result;
342:                 }
343:                 return result;
344:             } finally { synchronized(cell) { finalizing=false;cell.notifyAll(); } }
345:         }
346:     }
347:     private static final class RequestSlot {
348:         private org.apache.catalina.connector.Response response;
349:         private WrappedProcessor origin;
350:         private SocketWrapperBase<?> originalSocket;
351:         private State state;
352:         private Thread requestThread;
353:         private boolean armed,used,claimed;
354:         RequestSlot(org.apache.catalina.connector.Response response,WrappedProcessor origin,SocketWrapperBase<?> socket) { this.response=response;this.origin=origin;originalSocket=socket;requestThread=Thread.currentThread(); }
355:         synchronized void requireOriginal(HttpServletRequest request,HttpServletResponse supplied) {
356:             if(request.getDispatcherType()!=DispatcherType.REQUEST||requestThread!=Thread.currentThread()||bridge==null||bridge.current()!=origin||origin==null||origin.slot!=this||origin.socket!=originalSocket||originalSocket==null||response==null||response.getResponse()!=supplied||response.getRequest().getRequest()!=request||armed||used||claimed)
357:                 throw new IllegalStateException("Original controlled REQUEST scope required");
358:             claimed=true;
359:         }
360:         synchronized void reject() { if(origin!=null&&origin.slot==this)origin.slot=null;invalidate(); }
361:         synchronized void arm(State state) { if(armed||used||response==null)throw new IllegalStateException("SSE request token unavailable");this.state=state;armed=true; }
362:         void abort(State expected) {
363:             // Never wait for the real lifecycle exclusion while holding an application monitor.
364:             SocketWrapperBase<?> original;WrappedProcessor processor;
365:             synchronized(this) { original=originalSocket;processor=origin;if(original==null||processor==null)return; }
366:             var lock=original.getLock();lock.lock();
367:             try { synchronized(expected.io) { synchronized(this) {
368:                 if(!armed||used||state!=expected||response==null||origin!=processor||originalSocket!=original||processor.socket!=original||processor.slot!=this)return;
369:                 used=true;expected.point("before-close-now",0);
370:                 response.getCoyoteResponse().action(ActionCode.CLOSE_NOW,new IOException("SSE terminated"));
371:                 expected.point("close-now-return",0);
372:             } } } finally { lock.unlock(); }
373:         }
374:         synchronized void disarm(State expected) { if(state==expected) { if(origin!=null&&origin.slot==this)origin.slot=null;invalidate(); } }
375:         synchronized void invalidate() { armed=false;state=null;response=null;origin=null;originalSocket=null;requestThread=null; }
376:     }
377:     private static final class ContextValve extends ValveBase {
378:         ContextValve() { super(true); }
379:         @Override public void invoke(org.apache.catalina.connector.Request request,org.apache.catalina.connector.Response response)throws IOException,ServletException {
380:             WrappedProcessor current=bridge==null?null:bridge.current();
381:             if(request.getDispatcherType()==DispatcherType.REQUEST&&current!=null) {
382:                 current.invalidate();request.removeAttribute(SLOT); // Replace the one current attribute; never retain token history.
383:                 if("GET".equals(request.getMethod())&&request.getRequestURI().matches("/admin/runs/r_[0-9a-f]{32}/events")) {
384:                     var token=new RequestSlot(response,current,current.socket);current.slot=token;request.setAttribute(SLOT,token);
385:                 }
386:             }
387:             getNext().invoke(request,response);
388:         }
389:     }
390:     /** Fixed Tomcat10.1.41 HTTP/1.1 NIO profile; one instance for the whole JVM lifetime. */
391:     public static final class ControlledProtocol extends Http11NioProtocol {
392:         private static boolean constructed;
393:         private long creations,recycles,processes,lastProcessor,allocations,returned;
394:         private static final int BUNDLES=1536;
395:         private static final byte FREE=0,RESERVED=1,TRACKED=2,BURNED=3;
396:         private Reference<?>[] references;
397:         private byte[] slots;
398:         private ReferenceQueue<Object> queue;
399:         private boolean corrupt;
400:         private int failurePoint,handleFailurePoint;
401:         private long handleAllocations,handleReturned,processorReturned;
402:         public ControlledProtocol() {
403:             synchronized(ControlledProtocol.class) {
404:                 if(constructed)throw new IllegalStateException("Only one controlled connector per JVM lifetime");
405:                 PortalEventsPacketBudget.SHARED.reserveConnectorBaseline();
406:                 constructed=true;bridge=new Bridge();references=new Reference<?>[BUNDLES];slots=new byte[BUNDLES];queue=new ReferenceQueue<>();
407:             }
408:             setMaxConnections(1024);setMaxThreads(64);setProcessorCache(64);
409:         }
410:         private void verify(boolean started) {
411:             if(getMaxConnections()!=1024||getMaxThreads()!=64||getProcessorCache()!=64||getEndpoint().getUseVirtualThreads()||findUpgradeProtocols().length!=0)
412:                 throw new IllegalStateException("Controlled HTTP/1.1 NIO profile changed");
413:             var executor=getExecutor();
414:             if(executor!=null&&!(executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool&&pool.getMaximumPoolSize()==64)||started&&executor==null)
415:                 throw new IllegalStateException("Controlled internal executor required");
416:         }
417:         @Override public void setExecutor(java.util.concurrent.Executor executor) { if(executor!=null)throw new IllegalArgumentException("Custom executor prohibited");super.setExecutor(null); }
418:         @Override public void start()throws Exception { verify(false);super.start();verify(true); }
419:         // Both public paths use the ORIGINAL key. A historical CHM node must not own a wrapper.
420:         private Processor waitingKey(Processor processor) {
421:             if(processor instanceof WrappedProcessor owned) {
422:                 if(owned.protocol!=this)throw new IllegalArgumentException("Foreign controlled processor");
423:                 return owned.delegate;
424:             }
425:             return processor;
426:         }
427:         @Override public void addWaitingProcessor(Processor processor) { super.addWaitingProcessor(waitingKey(processor)); }
428:         @Override public void removeWaitingProcessor(Processor processor) { super.removeWaitingProcessor(waitingKey(processor)); }
429:         @Override protected synchronized Processor createProcessor() {
430:             verify(true);drainReferences(); // This reference-bearing frame fully RETURNS before credit is used.
431:             if(corrupt)throw new IllegalStateException("Controlled allocation identity failed");
432:             int index=0;while(index<BUNDLES&&slots[index]!=FREE)index++;
433:             if(index==BUNDLES)throw new IllegalStateException("Controlled processor allocation exhausted");
434:             slots[index]=RESERVED;int injectedFailure=failurePoint;failurePoint=0;
435:             try {
436:                 Processor original=super.createProcessor();
437:                 var wrapped=new WrappedProcessor(this,original,creations+1);allocations++;
438:                 if(injectedFailure==1)throw new IllegalStateException("Controlled construction failure probe");
439:                 var reference=new SlotReference(original,queue,index);references[index]=reference;
440:                 if(injectedFailure==2)throw new IllegalStateException("Controlled registration failure probe");
441:                 slots[index]=TRACKED;creations++;
442:                 Reference.reachabilityFence(original);Reference.reachabilityFence(wrapped);
443:                 return wrapped;
444:             } catch(RuntimeException|Error failure) { slots[index]=BURNED;throw failure; }
445:         }
446:         private synchronized Handle createHandle() {
447:             drainReferences();int index=freeIndex();slots[index]=RESERVED;
448:             int injectedFailure=handleFailurePoint;handleFailurePoint=0;
449:             Handle handle=null;
450:             try {
451:                 handle=new Handle();handleAllocations++;
452:                 if(injectedFailure==1)throw new IllegalStateException("Controlled Handle construction failure probe");
453:                 references[index]=new HandleReference(handle,queue,index);
454:                 if(injectedFailure==2)throw new IllegalStateException("Controlled Handle registration failure probe");
455:                 slots[index]=TRACKED;return handle;
456:             } catch(RuntimeException|Error failure) { slots[index]=BURNED;throw failure; }
457:             finally { Reference.reachabilityFence(handle); }
458:         }
459:         private int freeIndex() {
460:             if(corrupt)throw new IllegalStateException("Controlled allocation identity failed");
461:             int index=0;while(index<BUNDLES&&slots[index]!=FREE)index++;
462:             if(index==BUNDLES)throw new IllegalStateException("Controlled shared allocation exhausted");
463:             return index;
464:         }
465:         private void drainReferences() {
466:             for(Reference<?> reference;(reference=queue.poll())!=null;) {
467:                 int index;
468:                 if(reference instanceof SlotReference exact)index=exact.index;
469:                 else if(reference instanceof HandleReference exact)index=exact.index;
470:                 else { corrupt=true;return; }
471:                 if(index<0||index>=BUNDLES||references[index]!=reference) { corrupt=true;return; }
472:                 if(slots[index]==BURNED)continue;
473:                 if(slots[index]!=TRACKED) { corrupt=true;return; }
474:                 references[index]=null;slots[index]=FREE;returned++;
475:                 if(reference instanceof HandleReference)handleReturned++;else processorReturned++;
476:             }
477:         }
478:         synchronized void failHandleConstructionForTest(int point) { if(point<1||point>2)throw new IllegalArgumentException();handleFailurePoint=point; }
479:         synchronized long handleAllocationsForTest() { return handleAllocations; }
480:         synchronized long handleReturnedForTest() { drainReferences();return handleReturned; }
481:         synchronized long processorReturnedForTest() { drainReferences();return processorReturned; }
482:         synchronized void failConstructionForTest(int point) { if(point<1||point>2)throw new IllegalArgumentException();failurePoint=point; }
483:         synchronized long allocationsForTest() { return allocations; }
484:         synchronized int burnedForTest() { int n=0;for(byte state:slots)if(state==BURNED)n++;return n; }
485:         synchronized long returnedForTest() { drainReferences();return returned; }
486:         boolean waitingIdentityForTest(Processor processor) {
487:             Processor original=waitingKey(processor);int before=getWaitingProcessorCount();
488:             addWaitingProcessor(processor);super.removeWaitingProcessor(original);
489:             if(getWaitingProcessorCount()!=before)return false;
490:             super.addWaitingProcessor(original);removeWaitingProcessor(processor);
491:             return getWaitingProcessorCount()==before;
492:         }
493:         synchronized long creationsForTest() { return creations; }
494:         synchronized long recyclesForTest() { return recycles; }
495:         synchronized long processorForTest() { return lastProcessor; }
496:         SocketWrapperBase<?> currentSocketForTest() { var current=bridge.current();return current==null?null:current.socket; }
497:     }
498:     /** The only referent edge is PhantomReference's GC-managed field; no delegate/history copy. */
499:     private static final class SlotReference extends PhantomReference<Processor> {
500:         private final int index;
501:         SlotReference(Processor original,ReferenceQueue<Object> queue,int index) { super(original,queue);this.index=index; }
502:     }
503:     private static final class HandleReference extends PhantomReference<Handle> {
504:         private final int index;
505:         HandleReference(Handle handle,ReferenceQueue<Object> queue,int index) { super(handle,queue);this.index=index; }
506:     }
507:     /** Fixed64 short scopes; no per-event object, task, permission or connection registry. */
508:     private static final class Bridge {
509:         final Thread[] threads=new Thread[64];
510:         final WrappedProcessor[] processors=new WrappedProcessor[64];
511:         synchronized WrappedProcessor current() { Thread thread=Thread.currentThread();for(int i=0;i<64;i++)if(threads[i]==thread)return processors[i];return null; }
512:     }
513:     private static final class WrappedProcessor implements Processor {
514:         private final ControlledProtocol protocol;
515:         private final Processor delegate;
516:         private final long id;
517:         private volatile SocketWrapperBase<?> socket;
518:         private volatile RequestSlot slot;
519:         WrappedProcessor(ControlledProtocol protocol,Processor delegate,long id) { this.protocol=protocol;this.delegate=delegate;this.id=id; }
520:         void invalidate() { var previous=slot;if(previous!=null)previous.invalidate();slot=null; }
521:         @Override public SocketState process(SocketWrapperBase<?> actual,SocketEvent event)throws IOException {
522:             var lock=actual.getLock();lock.lock();int index=-1;WrappedProcessor previous=null;
523:             try {
524:                 protocol.verify(true);socket=actual;
525:                 synchronized(bridge) {
526:                     Thread thread=Thread.currentThread();
527:                     for(int i=0;i<64;i++)if(bridge.threads[i]==thread) { index=i;break; }
528:                     if(index<0)for(int i=0;i<64;i++)if(bridge.threads[i]==null) { index=i;break; }
529:                     if(index<0)throw new IOException("Controlled processing scopes exhausted");
530:                     previous=bridge.processors[index];bridge.threads[index]=thread;bridge.processors[index]=this;
531:                 }
532:                 synchronized(protocol) { protocol.processes++;protocol.lastProcessor=id; }
533:                 SocketState result=delegate.process(actual,event);
534:                 if(result!=SocketState.LONG&&result!=SocketState.ASYNC_END)invalidate();
535:                 return result;
536:             } catch(IOException|RuntimeException|Error failure) { invalidate();throw failure; }
537:             finally {
538:                 if(index>=0)synchronized(bridge) { bridge.processors[index]=previous;if(previous==null)bridge.threads[index]=null; }
539:                 lock.unlock();
540:             }
541:         }
542:         @Override public void recycle() {
543:             SocketWrapperBase<?> old=socket;
544:             if(old==null) { invalidate();protocol.removeWaitingProcessor(this);delegate.recycle();return; }
545:             var lock=old.getLock();lock.lock();
546:             try {
547:                 // Off-callback close/release cannot pool or reuse the delegate across an old action.
548:                 State retiring=slot==null?null:slot.state;
549:                 invalidate();if(retiring!=null)retiring.point("recycle-invalidated",0);
550:                 protocol.removeWaitingProcessor(this);delegate.recycle();socket=null;
551:                 synchronized(protocol) { protocol.recycles++; }
552:             } finally { lock.unlock(); }
553:         }
554:         @Override public UpgradeToken getUpgradeToken() { return delegate.getUpgradeToken(); }
555:         @Override public boolean isUpgrade() { return delegate.isUpgrade(); }
556:         @Override public boolean isAsync() { return delegate.isAsync(); }
557:         @Override public void timeoutAsync(long now) { delegate.timeoutAsync(now); }
558:         @Override public org.apache.coyote.Request getRequest() { return delegate.getRequest(); }
559:         @Override public void setSslSupport(SSLSupport ssl) { delegate.setSslSupport(ssl); }
560:         @Override public ByteBuffer getLeftoverInput() { return delegate.getLeftoverInput(); }
561:         @Override public void pause() { delegate.pause(); }
562:         @Override public boolean checkAsyncTimeoutGeneration() { return delegate.checkAsyncTimeoutGeneration(); }
563:     }
564: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java

1: package cn.agentplatform.gateway.infra;
2: import cn.agentplatform.common.PlatformException;
3: import cn.agentplatform.common.authority.*;
4: import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.PortalEventsChildAuthorization;
5: import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayEventsOpening;
6: import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayObservationQueryAuthorization;
7: import cn.agentplatform.gateway.application.RunObservationMetadataClient;
8: import cn.agentplatform.gateway.application.RunObservationMetadataClient.EventsMetadata;
9: import java.io.*;
10: import java.net.*;
11: import java.time.Duration;
12: import java.util.*;
13: import java.util.concurrent.*;
14: import javax.net.ssl.*;
15: import org.apache.hc.client5.http.*;
16: import org.apache.hc.client5.http.classic.methods.HttpGet;
17: import org.apache.hc.client5.http.config.*;
18: import org.apache.hc.client5.http.impl.classic.*;
19: import org.apache.hc.client5.http.impl.io.*;
20: import org.apache.hc.client5.http.io.*;
21: import org.apache.hc.client5.http.protocol.HttpClientContext;
22: import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
23: import org.apache.hc.core5.http.*;
24: import org.apache.hc.core5.http.config.Http1Config;
25: import org.apache.hc.core5.http.impl.io.*;
26: import org.apache.hc.core5.http.io.*;
27: import org.apache.hc.core5.http.protocol.HttpContext;
28: import org.apache.hc.core5.io.CloseMode;
29: import org.apache.hc.core5.util.*;
30: /** Unassembled original-Q and private APEV input transport. Resource admission conveys no authorization. */
31: public final class PortalRunEventsHttp {
32:     // Fixed finite object model: A393216+B65536+C524288+D262144+E1048576+F65536+G131072
33:     // =2490368, plus655360 margin=3145728; F1 separately charges the128B reservation token.
34:     static final long HTTP_BYTES = 3145728L;
35:     static final long PRIVATE_BYTES = 1048576L;
36:     private final PortalEventsTls tls;
37:     public PortalRunEventsHttp(PortalEventsTls tls) {
38:         this.tls = Objects.requireNonNull(tls);
39:     }
40:     public Session begin(CurrentAuthorityClient authority, String originalPortalSource, String run, long cursor) {
41:         return begin(authority,originalPortalSource,run,cursor,null);
42:     }
43:     Session forSse(CurrentAuthorityClient authority,String originalPortalSource,String run,long cursor,PortalRunEventsSse.Handle anchor) {
44:         Objects.requireNonNull(anchor).claimSession();
45:         return begin(authority,originalPortalSource,run,cursor,anchor);
46:     }
47:     private Session begin(CurrentAuthorityClient authority,String originalPortalSource,String run,long cursor,PortalRunEventsSse.Handle anchor) {
48:         Objects.requireNonNull(authority);
49:         var owner = PortalEventsPacketBudget.SHARED.acquire();
50:         PortalEventsPacketBudget.Reservation fee = null;
51:         GatewayEventsOpening opening = null;
52:         try {
53:             fee = owner.reserve(HTTP_BYTES);
54:             long t0 = System.nanoTime();
55:             opening = authority.beginGatewayPortalEvents(originalPortalSource, run, cursor);
56:             return new Session(tls, owner, fee, t0, opening, authority, originalPortalSource,anchor);
57:         } catch (RuntimeException | Error failure) {
58:             if (opening != null) opening.close();
59:             if (fee != null) fee.close();
60:             owner.close();
61:             throw failure;
62:         }
63:     }
64:     public static final class Session implements AutoCloseable {
65:         // Never cleared: closed Session/inner lease/ancestor stacks pin their unique shared credit.
66:         private final PortalRunEventsSse.Handle lifecycleAnchor;
67:         private final PortalEventsTls tls;
68:         private final PortalEventsPacketBudget.Owner owner;
69:         private PortalEventsPacketBudget.Reservation fee;
70:         private GatewayEventsOpening opening;
71:         private GatewayObservationQueryAuthorization query;
72:         private CurrentAuthorityClient authority;
73:         private String originalPortalSource;
74:         private PortalEventsPacketBudget.Reservation privateFee;
75:         private SavedFacts saved;
76:         private GatewayPortalEventsResponse gatewayResponse;
77:         private PortalEventsChildAuthorization child;
78:         private PortalEventsPacketDecoder decoder;
79:         private InputStream privateInput;
80:         private boolean qReady, privateUsed, privatePhase, bodyPhase;
81:         private long alarmEpoch;
82:         // Values are copied only from this Session's still-live Q facts before transfer.
83:         private record SavedFacts(String run, SubjectKey subject, ResourceBinding root, long versionId,
84:                 long versionNo, String accepted, String initial, ResourceBinding snapshot, long cursor) {}
85:         private final long t0, openingDeadline, lifetimeDeadline;
86:         private long hopDeadline;
87:         private boolean used, active, stopped, closing, closed, transportClean, cleanupActive;
88:         private Thread operationThread;
89:         private PortalEventsTlsConnection handle;
90:         private Socket raw;
91:         private SSLSocket ssl;
92:         private byte[] sslId;
93:         private HttpGet request;
94:         private HttpClientContext context;
95:         private PoolingHttpClientConnectionManager manager;
96:         private CloseableHttpClient client;
97:         private EventsMetadata facts;
98:         private ManagedHttpClientConnection connection;
99:         private ScheduledThreadPoolExecutor timer;
100:         private ScheduledFuture<?> alarm;
101:         private final IdentityHashMap<ClassicHttpRequest, Session> guards = new IdentityHashMap<>(2);
102:         private volatile Runnable beforeOutputForTest;
103:         private volatile boolean identityMatched;
104:         private Session(PortalEventsTls tls, PortalEventsPacketBudget.Owner owner, PortalEventsPacketBudget.Reservation fee, long t0, GatewayEventsOpening opening, CurrentAuthorityClient authority, String originalPortalSource,PortalRunEventsSse.Handle lifecycleAnchor) {
105:             this.lifecycleAnchor=lifecycleAnchor;
106:             this.tls = tls;
107:             this.owner = owner;
108:             this.fee = fee;
109:             this.t0 = t0;
110:             this.opening = opening;
111:             this.authority = authority;
112:             this.originalPortalSource = originalPortalSource;
113:             openingDeadline = t0 + TimeUnit.SECONDS.toNanos(5);
114:             lifetimeDeadline = t0 + TimeUnit.SECONDS.toNanos(55);
115:             query = opening.queryAuthorization();
116:         }
117:         private void live() {
118:             if (privatePhase) { livePrivate(!bodyPhase); return; }
119:             synchronized (this) {
120:                 if (stopped || closed || System.nanoTime() - Math.min(openingDeadline, hopDeadline == 0 ? openingDeadline : hopDeadline) >= 0) throw unavailable();
121:             }
122:             if (opening.queryAuthorization() != query) throw unavailable();
123:             opening.publicRunId();
124:             query.requireVerified();
125:         }
126:         public EventsMetadata lookupMetadata() {
127:             synchronized (this) {
128:                 if (used || closing || closed) throw unavailable();
129:                 used = true;
130:                 active = true;
131:                 operationThread = Thread.currentThread();
132:                 hopDeadline = Math.min(openingDeadline, Math.min(lifetimeDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
133:             }
134:             try {
135:                 live();
136:                 build();
137:                 live();
138:                 String ticket = query.ticket().value();
139:                 ascii(ticket, 16384);
140:                 request = new HttpGet("https://" + tls.hostname + ":" + tls.port + "/internal/runs/" + opening.publicRunId() + "/observation-metadata");
141:                 request.setVersion(HttpVersion.HTTP_1_1);
142:                 request.setHeader("Authorization", "Bearer " + ticket);
143:                 request.setHeader("Accept", "application/json");
144:                 request.setHeader("Accept-Encoding", "identity");
145:                 request.setHeader("Connection", "close");
146:                 context = HttpClientContext.create();
147:                 facts = client.execute(request, context, response -> {
148:                     try {
149:                         live();
150:                         checkResponse(response);
151:                         var entity = response.getEntity();
152:                         if (entity == null) throw unavailable();
153:                         byte[] body = new byte[8193];
154:                         int count = 0;
155:                         var in = entity.getContent();
156:                         for (;;) {
157:                             live();
158:                             int n = in.read(body, count, body.length - count);
159:                             if (n < 0) break;
160:                             if (n == 0) throw unavailable();
161:                             count += n;
162:                             if (count > 8192) throw unavailable();
163:                         }
164:                         live();
165:                         byte[] bounded = Arrays.copyOf(body, count);
166:                         if (response.getCode() != 200) throw RunObservationMetadataClient.parseEventsFailure(opening, response.getCode(), bounded);
167:                         var parsed = RunObservationMetadataClient.parseEvents(opening, bounded);
168:                         live();
169:                         return parsed;
170:                     } finally {
171:                         stopTransport();
172:                     }
173:                     // Abort before Apache closes a body: never drain an unknown remainder.
174:                 });
175:                 if (!finishTransport(Duration.ofSeconds(2))) throw unavailable();
176:                 liveAfterHttp();
177:                 synchronized (this) {
178:                     if (stopped || closing || closed) throw unavailable();
179:                     qReady = true;
180:                     return facts;
181:                 }
182:             } catch (PlatformException failure) {
183:                 abort();
184:                 throw failure;
185:             } catch (Exception failure) {
186:                 abort();
187:                 throw unavailable();
188:             } finally {
189:                 stopTransport();
190:                 boolean finished;
191:                 try {
192:                     finished = finishTransport(Duration.ofSeconds(2));
193:                 } finally {
194:                     synchronized (this) {
195:                         active = false;
196:                         operationThread = null;
197:                         notifyAll();
198:                     }
199:                 }
200:                 if (!finished) throw unavailable();
201:             }
202:         }
203:         @FunctionalInterface interface WindowConsumer { void accept(PortalEventsMaterialVerifier.VerifiedWindow window) throws IOException; }
204:         private String materialChild;
205:         private volatile java.util.function.BiConsumer<String,Integer> materialCheckpointForTest;
206:         void materialCheckpointForTest(java.util.function.BiConsumer<String,Integer> hook) { materialCheckpointForTest = hook; }
207:         void consumeVerified(WindowConsumer consumer) {
208:             Objects.requireNonNull(consumer);
209:             consumePrivate(packet -> {
210:                 // F1's scan fee has actually exited. Keep this fee through parser/borrow/consumer exit.
211:                 try (var materialFee = owner.reserve(PortalEventsMaterialVerifier.MATERIAL_BYTES)) {
212:                     var context = new MaterialContext(packet);
213:                     try { new PortalEventsMaterialVerifier(context).consume(consumer); }
214:                     finally { context.valid = false; }
215:                 }
216:             });
217:         }
218:         /** Only this Session can construct a binding. No caller may combine foreign facts/G/Owner. */
219:         final class MaterialContext {
220:             private final PortalEventsPacketDecoder.UntrustedPacket packet;
221:             private volatile boolean valid = true;
222:             private MaterialContext(PortalEventsPacketDecoder.UntrustedPacket packet) { this.packet = packet; }
223:             void live() { if (!valid) throw unavailable(); Session.this.live(); packet.header(); }
224:             PortalEventsPacketDecoder.UntrustedPacket packet() { live(); return packet; }
225:             GatewayPortalEventsResponse.Capture capture() { live(); return current(); }
226:             void requireLive(GatewayPortalEventsResponse.Capture capture) { live(); gatewayResponse.requireLive(capture); }
227:             boolean superseded(GatewayPortalEventsResponse.Capture capture) { live(); return gatewayResponse.isSuperseded(capture); }
228:             void require(GatewayPortalEventsResponse.Capture capture, MaterialScopeV1 scope) { requireLive(capture); capture.requireMaterial(scope); }
229:             void checkpoint(String phase, int index) {
230:                 live(); var hook = materialCheckpointForTest; if (hook != null) hook.accept(phase, index); live();
231:             }
232:             void acceptReceipt(String signed) { live(); gatewayResponse.acceptChildReceipt(signed); live(); }
233:             void bindChild(String childId) { live(); if (materialChild != null) throw unavailable(); materialChild = childId; }
234:             String childId() { live(); if (materialChild == null) throw unavailable(); return materialChild; }
235:             String run() { live(); return saved.run(); }
236:             SubjectKey subject() { live(); return saved.subject(); }
237:             ResourceBinding root() { live(); return saved.root(); }
238:             ResourceBinding snapshot() { live(); return saved.snapshot(); }
239:             long versionId() { live(); return saved.versionId(); }
240:             long versionNo() { live(); return saved.versionNo(); }
241:             String accepted() { live(); return saved.accepted(); }
242:             String initial() { live(); return saved.initial(); }
243:             long initialCursor() { live(); return saved.cursor(); }
244:             long cursor() { live(); synchronized(Session.this) { return sseLease==null?saved.cursor():publicAfter; } }
245:             void confirm(GatewayPortalEventsResponse.Capture capture,long seq) {
246:                 requireLive(capture);
247:                 if(superseded(capture))throw unavailable();
248:                 synchronized(Session.this) {
249:                     if(sseLease==null||sseLease.released||seq<=publicAfter)throw unavailable();
250:                     publicAfter=seq;
251:                 }
252:             }
253:         }
254:         private SseLease sseLease;
255:         private long publicAfter;
256:         /** Session-minted resource lease. No G/Owner/proof escapes, and no caller supplied cursor. */
257:         synchronized SseLease beginSse() {
258:             if(sseLease!=null||privateUsed||stopped||closing||closed||!qReady)throw unavailable();
259:             long cursor=opening.effectiveCursor(); // Validate before publishing a lease that needs a State exit callback.
260:             var charge=owner.reserve(1909574L); // all C2 allowances incl one128B reservation token =1909702.
261:             try { sseLease=new SseLease(charge); publicAfter=cursor; return sseLease; }
262:             catch(RuntimeException|Error failure) { charge.close(); throw failure; }
263:         }
264:         final class SseLease {
265:             private PortalEventsPacketBudget.Reservation charge;
266:             private boolean released;
267:             private Runnable cancellation;
268:             private java.util.function.Function<Duration,Boolean> exit;
269:             private SseLease(PortalEventsPacketBudget.Reservation charge) { this.charge=charge; }
270:             long deadline() { return lifetimeDeadline; }
271:             synchronized void cancellation(Runnable signal) { if(cancellation!=null||released)throw unavailable();cancellation=Objects.requireNonNull(signal); }
272:             synchronized void exits(java.util.function.Function<Duration,Boolean> signal) { if(exit!=null||released)throw unavailable();exit=Objects.requireNonNull(signal); }
273:             void signal() { Runnable callback;synchronized(this) { callback=cancellation; }if(callback!=null)callback.run(); }
274:             void reap(Duration timeout) { java.util.function.Function<Duration,Boolean> callback;synchronized(this) { callback=exit; }if(callback!=null)callback.apply(timeout); }
275:             void release() {
276:                 synchronized(this) { cancellation=null;exit=null; }
277:                 synchronized(Session.this) { if(!released) { charge.close();charge=null;released=true;Session.this.notifyAll(); } }
278:             }
279:         }
280:         @FunctionalInterface interface PacketConsumer { void accept(PortalEventsPacketDecoder.UntrustedPacket packet) throws IOException; }
281:         void consumePrivate(PacketConsumer consumer) {
282:             Objects.requireNonNull(consumer);
283:             synchronized (this) {
284:                 if (!qReady || active || privateUsed || stopped || closing || closed || !transportClean) throw unavailable();
285:                 privateUsed = true;
286:                 active = true;
287:                 operationThread = Thread.currentThread();
288:             }
289:             try {
290:                 // Lazy admission: Q-only owners retain the original 3211392B price.
291:                 privateFee = owner.reserve(PRIVATE_BYTES);
292:                 if (!(authority instanceof ResponseAuthorityClient responseAuthority)) throw unavailable();
293:                 liveOpening();
294:                 saved = new SavedFacts(facts.runId(), facts.owner(), facts.root(), facts.appVersionId(), facts.appVersionNo(),
295:                         facts.acceptedSnapshotSha256(), facts.initialCeilingSha256(), facts.snapshot(), opening.effectiveCursor());
296:                 liveOpening();
297:                 facts.close();
298:                 facts = null;
299:                 var g = responseAuthority.openGatewayPortalEvents(opening, saved.snapshot(), this::abort);
300:                 synchronized (this) { gatewayResponse = g; }
301:                 // Transfer invalidates Opening/Q getters; from here only the same G is consulted.
302:                 if (System.nanoTime() - openingDeadline >= 0) throw unavailable();
303:                 privatePhase = true;
304:                 opening.close();
305:                 opening = null;
306:                 query = null;
307:                 current();
308:                 try {
309:                     var issued = authority.exchangePortalEventsChild(originalPortalSource, g);
310:                     synchronized (this) { child = issued; }
311:                 } finally { originalPortalSource = null; }
312:                 synchronized (this) {
313:                     if (stopped || closing || closed) throw unavailable();
314:                     hopDeadline = Math.min(lifetimeDeadline, Math.min(System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
315:                             System.nanoTime() + current().remainingNanos()));
316:                     transportClean = false;
317:                 }
318:                 live();
319:                 build();
320:                 live();
321:                 String ticket = child.ticket().value();
322:                 ascii(ticket, 16384);
323:                 request = new HttpGet("https://" + tls.hostname + ":" + tls.port + "/admin/runs/" + saved.run()
324:                         + "/events?Last-Event-ID=" + Long.toString(saved.cursor()));
325:                 request.setVersion(HttpVersion.HTTP_1_1);
326:                 request.setHeader("Authorization", "Bearer " + ticket);
327:                 request.setHeader("Accept", "application/vnd.agent-platform.portal-events.v1");
328:                 request.setHeader("Accept-Encoding", "identity");
329:                 request.setHeader("Connection", "close");
330:                 context = HttpClientContext.create();
331:                 client.execute(request, context, response -> {
332:                     try {
333:                         live();
334:                         checkResponse(response, true);
335:                         var entity = response.getEntity();
336:                         if (entity == null) throw unavailable();
337:                         enterBody();
338:                         if (response.getCode() != 200) {
339:                             byte[] body = new byte[8193];
340:                             int count = 0;
341:                             var in = entity.getContent();
342:                             for (;;) {
343:                                 live();
344:                                 int n = in.read(body, count, body.length - count);
345:                                 live();
346:                                 if (n < 0) break;
347:                                 if (n == 0 || (count += n) > 8192) throw unavailable();
348:                             }
349:                             live();
350:                             String code = RunObservationMetadataClient.parsePrivateEventsErrorCode(Arrays.copyOf(body, count));
351:                             live();
352:                             int status = response.getCode();
353:                             if (!(status == 400 && code.equals("EVENT_CURSOR") || status == 404 && code.equals("RUN_NOT_FOUND")
354:                                     || status == 429 && code.equals("STREAM_CAPACITY") || status == 413 && Set.of("EVENT_LIMIT", "BUFFER_LIMIT").contains(code)
355:                                     || status == 403 && Set.of("AUTHORITY_EXPIRED", "AUTHORITY_CONTEXT_MISSING", "RUN_AUTHORITY_DENIED", "MATERIAL_SCOPE_MISSING_OR_CHANGED", "SYS-AUTH-403", "AUTHORITY_DENIED").contains(code))) throw unavailable();
356:                             throw new PlatformException(status, code, "Private events request rejected");
357:                         }
358:                         privateInput = new FilterInputStream(entity.getContent()) {
359:                             @Override public int read() throws IOException { live(); int n = in.read(); live(); return n; }
360:                             @Override public int read(byte[] b, int off, int len) throws IOException { live(); int n = in.read(b, off, len); live(); return n; }
361:                             @Override public void close() { stopTransport(); }
362:                         };
363:                         decoder = new PortalEventsPacketDecoder(owner);
364:                         for (;;) {
365:                             live();
366:                             var next = decoder.readNext(privateInput);
367:                             if (next.isEmpty()) { live(); break; }
368:                             try (var packet = next.orElseThrow()) {
369:                                 live();
370:                                 consumer.accept(packet);
371:                                 live();
372:                             }
373:                         }
374:                         return null;
375:                     } finally { stopTransport(); }
376:                 });
377:             } catch (PlatformException failure) {
378:                 abort(); throw failure;
379:             } catch (Exception failure) {
380:                 abort(); throw unavailable();
381:             } finally {
382:                 originalPortalSource = null;
383:                 stopTransport();
384:                 try { finishTransport(Duration.ofSeconds(2)); }
385:                 finally {
386:                     boolean requestedClose;
387:                     synchronized (this) { active = false; operationThread = null; requestedClose = closing; notifyAll(); }
388:                     // A cancelled transfer must close a late G even when its caller already exhausted the close wait.
389:                     if (requestedClose && sseLease==null) awaitClosed(Duration.ofSeconds(2));
390:                 }
391:             }
392:         }
393:         private void liveOpening() {
394:             synchronized (this) { if (stopped || closing || closed || System.nanoTime() - openingDeadline >= 0) throw unavailable(); }
395:             if (opening.queryAuthorization() != query) throw unavailable();
396:             opening.publicRunId(); query.requireVerified();
397:         }
398:         private GatewayPortalEventsResponse.Capture current() {
399:             synchronized (this) { if (stopped || closing || closed || System.nanoTime() - lifetimeDeadline >= 0 || gatewayResponse == null) throw unavailable(); }
400:             var capture = gatewayResponse.currentCapture();
401:             if (!saved.subject().equals(capture.subject()) || !saved.snapshot().equals(capture.resource())
402:                     || saved.cursor() != capture.initialCursor()) throw unavailable();
403:             gatewayResponse.requireLive(capture);
404:             if (gatewayResponse.isSuperseded(capture)) throw unavailable();
405:             return capture;
406:         }
407:         private void livePrivate(boolean head) {
408:             current();
409:             if (head) {
410:                 if (hopDeadline != 0 && System.nanoTime() - hopDeadline >= 0 || child == null) throw unavailable();
411:                 child.requireVerified();
412:             }
413:         }
414:         private void enterBody() {
415:             livePrivate(true);
416:             synchronized (this) {
417:                 if (stopped || closing || closed || bodyPhase) throw unavailable();
418:                 bodyPhase = true;
419:                 arm(lifetimeDeadline);
420:             }
421:             live();
422:         }
423:         private void arm(long deadline) {
424:             long epoch = ++alarmEpoch;
425:             if (alarm != null) alarm.cancel(false);
426:             alarm = timer.schedule(() -> {
427:                 synchronized (Session.this) {
428:                     if (epoch != alarmEpoch || stopped || closed) return;
429:                 }
430:                 abort();
431:             }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
432:         }
433:         private void liveAfterHttp() {
434:             if (System.nanoTime() - hopDeadline >= 0) throw unavailable();
435:             // Successful Q close deliberately stops only the physical hop, not its retained Opening.
436:             if (opening.queryAuthorization() != query) throw unavailable();
437:             opening.publicRunId();
438:             query.requireVerified();
439:         }
440:         private void build() {
441:             // One timer task for one absolute hop; no per-read scheduling or application worker queue.
442:             timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-http-deadline").factory());
443:             timer.setRemoveOnCancelPolicy(true);
444:             timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
445:             timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
446:             arm(hopDeadline);
447:             var builder = new PoolingHttpClientConnectionManagerBuilder() {
448:                 @Override protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ignored, DnsResolver ignoredDns, TlsSocketStrategy ignoredTls) {
449:                     var dns = new DnsResolver() {
450:                         public InetAddress[] resolve(String h) throws UnknownHostException {
451:                             if (!tls.hostname.equals(h)) throw new UnknownHostException("Fixed TLS host required");
452:                             return new InetAddress[] {
453:                                 tls.address
454:                             };
455:                         }
456:                         public String resolveCanonicalHostname(String h) throws UnknownHostException {
457:                             resolve(h);
458:                             return tls.hostname;
459:                         }
460:                     };
461:                     return new DefaultHttpClientConnectionOperator(proxy -> {
462:                         if (proxy != null) throw new IOException("Proxy prohibited");
463:                         live();
464:                         // E's TLS hard anchor is the original lifetime. The phase timer owns the shorter head
465:                         // bound; freezing checked_until/head into this handle would kill healthy renewed G bodies.
466:                         var acquired = tls.acquire(privatePhase ? lifetimeDeadline : Math.min(lifetimeDeadline, hopDeadline));
467:                         if (!(acquired instanceof PortalEventsTlsConnection c) || !c.belongsTo(tls)) {
468:                             acquired.abort();
469:                             throw new IOException("Foreign TLS handle");
470:                         }
471:                         synchronized (Session.this) {
472:                             if (stopped || handle != null) {
473:                                 c.abort();
474:                                 throw new IOException("TLS installation stopped");
475:                             }
476:                             handle = c;
477:                         }
478:                         try {
479:                             var socket = c.rawSocketForConnect();
480:                             synchronized (Session.this) {
481:                                 if (stopped) {
482:                                     c.abort();
483:                                     throw new IOException("TLS installation stopped");
484:                                 }
485:                                 raw = socket;
486:                             }
487:                             return socket;
488:                         } catch (RuntimeException | IOException e) {
489:                             c.abort();
490:                             throw e;
491:                         }
492:                     }, endpoint -> {
493:                         if (!"https".equals(endpoint.getSchemeName()) || !tls.hostname.equals(endpoint.getHostName()) || endpoint.getPort() != tls.port) throw new IllegalArgumentException("Fixed HTTPS route required");
494:                         return tls.port;
495:                     }, dns, scheme -> "https".equals(scheme) ?(socket, host, port, attachment, ctx) -> {
496:                         if (socket != raw || !tls.hostname.equals(host) || port != tls.port || ctx != context) throw new IOException("TLS upgrade identity rejected");
497:                         live();
498:                         var verified = handle.upgradeConnectedSocket();
499:                         live();
500:                         synchronized (Session.this) {
501:                             if (stopped) {
502:                                 handle.abort();
503:                                 throw new IOException("TLS upgrade stopped");
504:                             }
505:                             ssl = verified;
506:                             sslId = verified.getSession().getId();
507:                         }
508:                         return verified;
509:                     }
510:                     : null);
511:                 }
512:             };
513:             manager = builder.setConnectionFactory(attachment -> new QuietConnection(this)).setMaxConnTotal(1).setMaxConnPerRoute(1).setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(2000)).setSoLinger(TimeValue.NEG_ONE_MILLISECOND).build()).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(1000)).setSocketTimeout(Timeout.ofMilliseconds(2000)).setValidateAfterInactivity(TimeValue.NEG_ONE_MILLISECOND).build()).build();
514:             client = HttpClients.custom().setConnectionManager(manager).setConnectionReuseStrategy((r, s, c) -> false).setRoutePlanner((host, ctx) -> {
515:                 if (ctx != context || !"https".equals(host.getSchemeName()) || !tls.hostname.equals(host.getHostName()) || host.getPort() != tls.port || host.getAddress() != null) throw new HttpException("Fixed route required");
516:                 return new HttpRoute(host, null, true);
517:             }).disableAutomaticRetries().disableRedirectHandling().disableContentCompression().disableCookieManagement().disableAuthCaching().disableConnectionState().disableDefaultUserAgent().setDefaultCookieSpecRegistry(name -> null).setDefaultAuthSchemeRegistry(name -> null).setDefaultCredentialsProvider((scope, ctx) -> null).setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false).setProtocolUpgradeEnabled(false).setHardCancellationEnabled(true).setConnectionRequestTimeout(Timeout.ofMilliseconds(1)).setResponseTimeout(Timeout.ofMilliseconds(2000)).build()).setRequestExecutor(new HttpRequestExecutor() {
518:                 @Override public ClassicHttpResponse execute(ClassicHttpRequest actual, HttpClientConnection conn, HttpResponseInformationCallback callback, HttpContext ctx) throws IOException, HttpException {
519:                     check(actual, conn, ctx);
520:                     synchronized (guards) {
521:                         if (guards.containsKey(actual) || guards.size() >= 2) throw new IOException("Request identity capacity");
522:                         guards.put(actual, Session.this);
523:                     }
524:                     try {
525:                         return super.execute(actual, conn, callback, ctx);
526:                     } finally {
527:                         synchronized (guards) {
528:                             guards.remove(actual);
529:                         }
530:                     }
531:                 }
532:             }).build();
533:         }
534:         private void check(ClassicHttpRequest actual, HttpClientConnection conn, HttpContext ctx) throws IOException {
535:             live();
536:             if (ctx != context || !(conn instanceof QuietConnection connection) || connection.session != this || connection.getSocket() != ssl || ssl == null) throw new IOException("Request connection identity rejected");
537:             var session = ssl.getSession();
538:             if (!session.isValid() || !PortalEventsTls.PROTOCOL.equals(session.getProtocol()) || !PortalEventsTls.CIPHER.equals(session.getCipherSuite()) || !tls.hostname.equals(session.getPeerHost()) || session.getPeerPort() != tls.port || !Arrays.equals(sslId, session.getId())) throw new IOException("TLS session identity rejected");
539:             if (!"GET".equals(actual.getMethod()) || actual.getEntity() != null || !HttpVersion.HTTP_1_1.equals(actual.getVersion()) || !actual.getRequestUri().equals(privatePhase ? "/admin/runs/" + saved.run() + "/events?Last-Event-ID=" + Long.toString(saved.cursor()) : "/internal/runs/" + opening.publicRunId() + "/observation-metadata")) throw new IOException("Fixed request required");
540:             var headers = actual.getHeaders();
541:             if (headers.length > 16) throw new IOException("Request header count");
542:             var names = new HashSet<String>();
543:             for (var h : headers) {
544:                 String name = h.getName().toLowerCase(Locale.ROOT);
545:                 if (!names.add(name)) throw new IOException("Duplicate request header");
546:                 String expected = switch (name) {
547:                     case "authorization" -> "Bearer " + (privatePhase ? child.ticket().value() : query.ticket().value());
548:                     case "accept" -> privatePhase ? "application/vnd.agent-platform.portal-events.v1" : "application/json";
549:                     case "accept-encoding" -> "identity";
550:                     case "host" -> tls.hostname + ":" + tls.port;
551:                     case "connection" -> "close";
552:                     default -> throw new IOException("Unexpected request header");
553:                 };
554:                 if (!expected.equals(h.getValue())) throw new IOException("Fixed request header required");
555:             }
556:             if (!names.equals(Set.of("authorization", "accept", "accept-encoding", "host", "connection"))) throw new IOException("Missing request header");
557:             requestOctets(actual);
558:             live();
559:         }
560:         private void writerCheck(ClassicHttpRequest actual, QuietConnection conn) throws IOException {
561:             synchronized (guards) {
562:                 if (guards.get(actual) != this) throw new IOException("Missing original request identity");
563:             }
564:             check(actual, conn, context);
565:             identityMatched = true;
566:         }
567:         private void stopTransport() {
568:             PortalEventsTlsConnection c;
569:             HttpGet r;
570:             synchronized (this) {
571:                 c = handle;
572:                 r = request;
573:             }
574:             if (c != null) c.abort();
575:             if (r != null) r.cancel();
576:         }
577:         public void abort() {
578:             SseLease owned;
579:             synchronized (this) {
580:                 stopped = true;
581:                 owned=sseLease;
582:             }
583:             if(owned!=null)owned.signal();
584:             stopTransport();
585:         }
586:         private boolean finishTransport(Duration timeout) {
587:             long deadline = System.nanoTime() + timeout.toNanos();
588:             PortalEventsTlsConnection closingHandle;
589:             HttpGet closingRequest;
590:             ScheduledFuture<?> closingAlarm;
591:             ScheduledThreadPoolExecutor closingTimer;
592:             CloseableHttpClient closingClient;
593:             PoolingHttpClientConnectionManager closingManager;
594:             synchronized (this) {
595:                 while (cleanupActive) {
596:                     long left = deadline - System.nanoTime();
597:                     if (left <= 0) return false;
598:                     try {
599:                         TimeUnit.NANOSECONDS.timedWait(this, left);
600:                     } catch (InterruptedException e) {
601:                         Thread.currentThread().interrupt();
602:                         return false;
603:                     }
604:                 }
605:                 if (active && operationThread != Thread.currentThread() || transportClean) return transportClean;
606:                 // Election and snapshots share one lock. Failed cleanup retains the original references for retry.
607:                 cleanupActive = true;
608:                 closingHandle = handle;
609:                 closingRequest = request;
610:                 closingAlarm = alarm;
611:                 closingTimer = timer;
612:                 closingClient = client;
613:                 closingManager = manager;
614:             }
615:             try {
616:                 if (closingHandle != null) closingHandle.abort();
617:                 if (closingRequest != null) closingRequest.cancel();
618:                 if (closingAlarm != null) closingAlarm.cancel(false);
619:                 if (closingTimer != null) closingTimer.shutdown();
620:                 // Physical waits and library close must leave both Session and identity locks available to abort.
621:                 if (closingHandle != null && !closingHandle.awaitClosed(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) return false;
622:                 if (closingTimer != null && !closingTimer.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
623:                 if (closingClient != null) closingClient.close(CloseMode.IMMEDIATE);
624:                 else if (closingManager != null) closingManager.close(CloseMode.IMMEDIATE);
625:                 synchronized (this) {
626:                     client = null;
627:                     manager = null;
628:                     connection = null;
629:                     request = null;
630:                     context = null;
631:                     ssl = null;
632:                     sslId = null;
633:                     raw = null;
634:                     handle = null;
635:                     alarm = null;
636:                     timer = null;
637:                     beforeOutputForTest = null;
638:                     synchronized (guards) {
639:                         guards.clear();
640:                     }
641:                     transportClean = true;
642:                     return true;
643:                 }
644:             } catch (InterruptedException e) {
645:                 Thread.currentThread().interrupt();
646:                 return false;
647:             } catch (RuntimeException e) {
648:                 return false;
649:             } finally {
650:                 synchronized (this) {
651:                     cleanupActive = false;
652:                     notifyAll();
653:                 }
654:             }
655:         }
656:         public boolean awaitClosed(Duration timeout) {
657:             if (timeout.isNegative()) throw new IllegalArgumentException("Negative close wait");
658:             long deadline = System.nanoTime() + timeout.toNanos();
659:             SseLease owned;
660:             synchronized(this) { if(closed)return true;if(!closing)return false;owned=sseLease; }
661:             // A failed finite onComplete wait may be retried by the original Session's owner.
662:             // This call holds no Session monitor and cannot bypass the heavy/worker/completion gate.
663:             if(owned!=null&&!owned.released)owned.reap(Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
664:             synchronized (this) {
665:                 if (closed) return true;
666:                 if (!closing) return false;
667:                 while (active || sseLease!=null&&!sseLease.released) {
668:                     long left = deadline - System.nanoTime();
669:                     if (left <= 0) return false;
670:                     try {
671:                         TimeUnit.NANOSECONDS.timedWait(this, left);
672:                     } catch (InterruptedException e) {
673:                         Thread.currentThread().interrupt();
674:                         return false;
675:                     }
676:                 }
677:             }
678:             if (!finishTransport(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) return false;
679:             synchronized (this) {
680:                 if (closed) return true;
681:                 // Refuse release if a callback retained a segment or decoder work remains live.
682:                 try { if (decoder != null) decoder.close(); } catch (RuntimeException stillBorrowed) { return false; }
683:                 decoder = null;
684:                 privateInput = null;
685:                 if (gatewayResponse != null) gatewayResponse.close();
686:                 gatewayResponse = null;
687:                 child = null;
688:                 saved = null;
689:                 materialChild = null;
690:                 materialCheckpointForTest = null;
691:                 authority = null;
692:                 originalPortalSource = null;
693:                 if (opening != null) opening.close();
694:                 opening = null;
695:                 query = null;
696:                 if (facts != null) facts.close();
697:                 facts = null;
698:                 if (privateFee != null) privateFee.close();
699:                 privateFee = null;
700:                 fee.close();
701:                 fee = null;
702:                 owner.close();
703:                 closed = true;
704:                 notifyAll();
705:                 return true;
706:             }
707:         }
708:         @Override public void close() {
709:             synchronized (this) {
710:                 closing = true;
711:             }
712:             abort();
713:             awaitClosed(Duration.ofSeconds(2));
714:         }
715:         // Mechanical blocking only; cannot install a different ticket, socket, URL, or proof.
716:         void beforeOutputForTest(Runnable hook) {
717:             beforeOutputForTest = hook;
718:         }
719:     }
720:     static int requestOctets(ClassicHttpRequest r) throws IOException {
721:         int bytes = ascii(r.getMethod(), 16) + 1 + ascii(r.getRequestUri(), 512) + 1 + ascii(r.getVersion().toString(), 16) + 2;
722:         for (var h : r.getHeaders()) bytes = Math.addExact(bytes, ascii(h.getName(), 32768) + 2 + ascii(h.getValue(), 32768) + 2);
723:         bytes = Math.addExact(bytes, 2);
724:         if (bytes > 32768) throw new IOException("Request head limit");
725:         return bytes;
726:     }
727:     private static int ascii(String value, int limit) throws IOException {
728:         if (value == null || value.length() > limit) throw new IOException("ASCII field limit");
729:         for (int i = 0; i < value.length(); i++) {
730:             char c = value.charAt(i);
731:             if (c < 32 || c > 126) throw new IOException("ASCII field rejected");
732:         }
733:         return value.length();
734:     }
735:     private static void checkResponse(ClassicHttpResponse response) throws IOException { checkResponse(response, false); }
736:     private static void checkResponse(ClassicHttpResponse response, boolean events) throws IOException {
737:         if (!HttpVersion.HTTP_1_1.equals(response.getVersion()) || !(events ? Set.of(200, 400, 403, 404, 413, 429) : Set.of(200, 403, 404)).contains(response.getCode())) throw new IOException("Response status rejected");
738:         var counts = new HashMap<String, Integer>();
739:         String cl = null, te = null, type = null;
740:         for (var h : response.getHeaders()) {
741:             String name = h.getName().toLowerCase(Locale.ROOT);
742:             int n = counts.merge(name, 1, Integer::sum);
743:             if (Set.of("content-encoding", "set-cookie", "location", "trailer", "www-authenticate", "proxy-authenticate").contains(name)) throw new IOException("Response header rejected");
744:             if (Set.of("content-length", "transfer-encoding", "content-type").contains(name) && n != 1) throw new IOException("Duplicate response framing");
745:             if (name.equals("content-length")) cl = h.getValue();
746:             if (name.equals("transfer-encoding")) te = h.getValue();
747:             if (name.equals("content-type")) type = h.getValue();
748:         }
749:         if (type == null || !(events && response.getCode() == 200 ? type.equals("application/vnd.agent-platform.portal-events.v1") : type.matches("(?i)application/json(?:;[ ]*charset=UTF-8)?")) || cl != null && te != null || cl == null && te == null) throw new IOException("Response framing rejected");
750:         if (cl != null) {
751:             if (!cl.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Content length rejected");
752:             try {
753:                 if (Long.parseLong(cl) > 8192 && !(events && response.getCode() == 200)) throw new IOException("Body limit");
754:             } catch (NumberFormatException e) {
755:                 throw new IOException("Content length rejected");
756:             }
757:         }
758:         if (te != null && !te.equalsIgnoreCase("chunked")) throw new IOException("Transfer coding rejected");
759:     }
760:     /** Core5.3.4 standard parser/writer/holder, without DefaultManaged's header or wire hooks. */
761:     private static final class QuietConnection extends DefaultBHttpClientConnection implements ManagedHttpClientConnection {
762:         final Session session;
763:         private boolean terminated;
764:         QuietConnection(Session session) {
765:             super(Http1Config.custom().setBufferSize(8192).setMaxLineLength(8192).setMaxHeaderCount(1).setMaxEmptyLineCount(2).build(), null, null, null, null, () -> {
766:                 var delegate = DefaultHttpRequestWriterFactory.INSTANCE.create();
767:                 return (actual, buffer, out) -> {
768:                     session.writerCheck(actual, (QuietConnection) sessionConnection(session));
769:                     delegate.write(actual, buffer, out);
770:                 };
771:             }, config -> {
772:                 var delegate = new org.apache.hc.core5.http.impl.io.DefaultHttpResponseParser(Http1Config.custom().setMaxLineLength(8192).setMaxHeaderCount(64).setMaxEmptyLineCount(2).build());
773:                 return (buffer, in) -> {
774:                     try {
775:                         var response = delegate.parse(new HeadBuffer(buffer), in);
776:                         checkResponse(response, session.privatePhase);
777:                         return response;
778:                     } catch (IOException | HttpException | RuntimeException e) {
779:                         session.stopTransport();
780:                         throw e;
781:                     }
782:                 };
783:             });
784:             this.session = session;
785:         }
786:         // The unique connection is set by bind before any executor/writer invocation.
787:         private static ManagedHttpClientConnection sessionConnection(Session session) {
788:             return session.connection;
789:         }
790:         @Override public void bind(Socket socket) throws IOException {
791:             if (terminated) throw new IOException("Connection exited");
792:             super.bind(socket);
793:             session.connection = this;
794:         }
795:         @Override public void bind(SSLSocket ssl, Socket raw) throws IOException {
796:             if (terminated) throw new IOException("Connection exited");
797:             super.bind(new SocketHolder(ssl, raw) {
798:                 @Override protected OutputStream getOutputStream(Socket socket) throws IOException {
799:                     Runnable hook = session.beforeOutputForTest;
800:                     if (hook != null) hook.run();
801:                     return super.getOutputStream(socket);
802:                 }
803:             });
804:             session.connection = this;
805:         }
806:         @Override public Socket getSocket() {
807:             var holder = getSocketHolder();
808:             return holder == null ? null : holder.getSocket();
809:         }
810:         @Override public void setSocketTimeout(Timeout timeout) {
811:             if (!(getSocket() instanceof SSLSocket)) super.setSocketTimeout(timeout);
812:             /* SafeSsl retains F2a15000ms; absolute hop timer aborts earlier. */
813:         }
814:         @Override public void activate() {
815:             if (terminated) throw new IllegalStateException("Connection exited");
816:         }
817:         @Override public void passivate() {
818:             session.stopTransport();
819:         }
820:         @Override public void close() {
821:             close(CloseMode.IMMEDIATE);
822:         }
823:         @Override public void close(CloseMode mode) {
824:             terminated = true;
825:             session.stopTransport();
826:             super.close(CloseMode.IMMEDIATE);
827:         }
828:     }
829:     /** Charge only parsed lines, including rejected line, before standard parser retains them. */
830:     private static final class HeadBuffer implements SessionInputBuffer {
831:         final SessionInputBuffer input;
832:         int total;
833:         HeadBuffer(SessionInputBuffer input) {
834:             this.input = input;
835:         }
836:         public int readLine(CharArrayBuffer line, InputStream stream) throws IOException {
837:             int start = line.length();
838:             int n = input.readLine(line, stream);
839:             if (n >= 0) {
840:                 int length = line.length() - start;
841:                 total = Math.addExact(total, length + 2);
842:                 if (total > 8192) throw new IOException("Response head limit");
843:                 if (length > 0 && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) throw new IOException("Folded response header");
844:                 for (int i = start; i < line.length(); i++) {
845:                     char c = line.charAt(i);
846:                     if (c < 32 || c == 127) throw new IOException("Response head control");
847:                 }
848:             }
849:             return n;
850:         }
851:         public int length() {
852:             return input.length();
853:         }
854:         public int capacity() {
855:             return input.capacity();
856:         }
857:         public int available() {
858:             return input.available();
859:         }
860:         public int read(byte[] b, int off, int len, InputStream in) throws IOException {
861:             return input.read(b, off, len, in);
862:         }
863:         public int read(byte[] b, InputStream in) throws IOException {
864:             return input.read(b, in);
865:         }
866:         public int read(InputStream in) throws IOException {
867:             return input.read(in);
868:         }
869:         public HttpTransportMetrics getMetrics() {
870:             return input.getMetrics();
871:         }
872:     }
873:     private static PlatformException unavailable() {
874:         return new PlatformException(503, "RUN_METADATA_UNAVAILABLE", "运行观察元数据暂不可用");
875:     }
876: }

# platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalEventsPacketBudget.java

1: package cn.agentplatform.gateway.infra;
2: 
3: /** Resource admission only; Owner conveys no IAM authority. Conservative charges, not heap telemetry. */
4: public final class PortalEventsPacketBudget {
5:     public static final long GLOBAL_BYTES=201326592L,OWNER_BYTES=100663296L,SCAN_BYTES=67108864L;
6:     public static final PortalEventsPacketBudget SHARED=new PortalEventsPacketBudget();
7:     private static final long OWNER_BASE=65536L,TOKEN_BYTES=128L;
8:     private long reserved;
9:     private int owners;
10:     private boolean connectorPrepaid;
11:     private PortalEventsPacketBudget(){}
12:     /** One fixed adapter graph per JVM; never an Owner or an IAM capability. */
13:     synchronized void reserveConnectorBaseline(){
14:         if(connectorPrepaid)return;
15:         long next=Math.addExact(reserved,3145728L);
16:         if(next>GLOBAL_BYTES)throw new IllegalStateException("connector baseline exhausted");
17:         reserved=next;connectorPrepaid=true;
18:     }
19:     synchronized long connectorBaselineBytes(){return connectorPrepaid?3145728L:0L;}
20:     public synchronized Owner acquire(){
21:         if(owners==2)throw new IllegalStateException("packet owner slots exhausted");
22:         long next=Math.addExact(reserved,OWNER_BASE);
23:         if(next>GLOBAL_BYTES)throw new IllegalStateException("packet global budget exhausted");
24:         reserved=next;owners++;
25:         try{return new Owner(this);}catch(RuntimeException|Error e){reserved-=OWNER_BASE;owners--;throw e;}
26:     }
27:     public synchronized long reservedBytes(){return reserved;}
28:     public synchronized int owners(){return owners;}
29:     public static final class Owner implements AutoCloseable {
30:         private final PortalEventsPacketBudget budget;
31:         private long reserved=OWNER_BASE;
32:         private boolean closed,claimed;
33:         private PortalEventsPacketDecoder decoder;
34:         private Owner(PortalEventsPacketBudget budget){this.budget=budget;}
35:         synchronized void claim(PortalEventsPacketDecoder candidate){
36:             requireOpen();if(claimed)throw new IllegalStateException("owner already claimed a decoder");claimed=true;decoder=candidate;
37:         }
38:         synchronized void requireOpen(){if(closed)throw new IllegalStateException("packet owner closed");}
39:         public synchronized long reservedBytes(){return reserved;}
40:         /** F2 must reserve its additional business model/frame before allocating it. */
41:         public synchronized Reservation reserve(long bytes){
42:             requireOpen();if(bytes<0)throw new IllegalArgumentException("negative reservation");
43:             long charged=Math.addExact(bytes,TOKEN_BYTES),next=Math.addExact(reserved,charged);
44:             synchronized(budget){long global=Math.addExact(budget.reserved,charged);if(next>OWNER_BYTES||global>GLOBAL_BYTES)throw new IllegalStateException("packet allocation budget exhausted");reserved=next;budget.reserved=global;}
45:             try{return new Reservation(this,charged);}catch(RuntimeException|Error e){release(charged);throw e;}
46:         }
47:         private synchronized void release(long bytes){
48:             if(bytes>reserved)throw new IllegalStateException("packet ledger underflow");synchronized(budget){reserved-=bytes;budget.reserved-=bytes;}
49:         }
50:         @Override public synchronized void close(){
51:             if(closed)return;
52:             if(decoder!=null)decoder.close(); // Refuse while physical read/borrow is active; caller retries after actual exit.
53:             if(reserved!=OWNER_BASE)throw new IllegalStateException("owner has live reservations");
54:             decoder=null;closed=true;synchronized(budget){budget.reserved-=OWNER_BASE;reserved=0;budget.owners--;}
55:         }
56:     }
57:     /** Resource tokens cannot be minted by callers. */
58:     public static final class Reservation implements AutoCloseable {
59:         private Owner owner;
60:         private final long bytes;
61:         private Reservation(Owner owner,long bytes){this.owner=owner;this.bytes=bytes;}
62:         @Override public synchronized void close(){if(owner!=null){owner.release(bytes);owner=null;}}
63:     }
64: }

# platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java

1: package cn.agentplatform.gateway.infra;
2: import cn.agentplatform.common.*;
3: import cn.agentplatform.common.authority.*;
4: import com.fasterxml.jackson.databind.*;
5: import com.fasterxml.jackson.databind.node.*;
6: import java.io.*;
7: import java.net.*;
8: import java.nio.charset.StandardCharsets;
9: import java.nio.file.*;
10: import java.security.*;
11: import java.time.Duration;
12: import java.util.*;
13: import java.util.concurrent.*;
14: import java.util.concurrent.atomic.*;
15: import java.util.function.*;
16: import javax.net.ssl.*;
17: import jakarta.servlet.http.*;
18: import org.apache.catalina.startup.Tomcat;
19: import org.junit.jupiter.api.Test;
20: import org.springframework.mock.web.*;
21: import org.springframework.security.core.context.SecurityContextHolder;
22: import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
23: import org.springframework.web.context.request.*;
24: import org.springframework.web.servlet.HandlerMapping;
25: import static org.junit.jupiter.api.Assertions.*;
26: import static cn.agentplatform.gateway.infra.PortalEventsMaterialAuthorityTest.*;
27: /** Real embedded Tomcat HTTP/1.1 NIO and same issuer/receiver/private TLS chain. */
28: public class PortalRunEventsSseLifecycleTest {
29:     @Test void originalRequestBootstrapAndContinuousCursorTerminal() throws Exception { child("sequence"); }
30:     @Test void privateEofDoesNotInventTerminal() throws Exception { child("eof"); }
31:     @Test void realBackpressureAbortRetainsCursorAndActuallyExits() throws Exception { child("backpressure"); }
32:     @Test void resetPrecedesStatusAndDoesNotAdvanceCursor() throws Exception { child("reset"); }
33:     @Test void allSpacePgTypeSurvivesActualServletOutput() throws Exception { child("spaces"); }
34:     @Test void generationAtGetterAndEachWriteBoundaryNeverPublishesOldTail() throws Exception { for(String point:List.of("before-getter","after-getter","before-ready","before-write","after-write","before-flush"))child("generation-"+point); }
35:     @Test void revokeAndPartialUnknownNeverAdvanceCursor() throws Exception { for(String point:List.of("revoke","partial"))child("freshness-"+point); }
36:     @Test void firstErrorAndLateFirstWindowCannotReviveRegistration() throws Exception { for(String point:List.of("bad-receipt","late"))child("bootstrap-"+point); }
37:     @Test void originalIdleDeadlineSurvivesSpuriousWakeupsAndStoppedReader() throws Exception { child("idle"); }
38:     @Test void twoHeavyHelpersKeepWholeOwnersAndThirdAdmissionClosed() throws Exception { child("heavy-retention"); }
39:     @Test void stalledCompletionKeepsOwnersBeforeOldHttpCleanup() throws Exception { child("completion-retention"); }
40:     @Test void abortIsOrderedAfterActualReadyWriteAndRetainsFeesWhileStalled() throws Exception { child("abort-order"); }
41:     @Test void lateAbortCannotTouchReusedKeepaliveRequest() throws Exception { child("keepalive"); }
42:     @Test void realNotReadyNotificationResumesTheSameFrame() throws Exception { child("backpressure-resume"); }
43:     @Test void exactLongMaxCursorAndHighPrecisionJsonReachRealServletBytes() throws Exception { child("long-id");child("precision"); }
44:     @Test void inertReadyIdleShellAllowsMaterialFeeExitButRetainsFinalOwner() throws Exception { child("light-shell"); }
45:     @Test void actualPendingFlushRequiresDrainAndFreshGenerationBeforeCursor() throws Exception { for(String point:List.of("drain","generation","revoke"))child("pending-flush-"+point); }
46:     @Test void stalledActualActionRetainsTwoOwnersUntilActionAndCompletionExit() throws Exception { child("action-retention"); }
47:     @Test void sameProcessorOnAnotherSocketCannotReceiveOldAbort() throws Exception { child("processor-reuse"); }
48:     @Test void noFutureWritableCallbackIsNeededForTheOwnedIdleDeadline() throws Exception { child("not-ready-idle"); }
49:     @Test void controlledProfileAndSingleLifetimeInstanceFailClosed() throws Exception { child("bad-profile"); }
50:     @Test void forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion() throws Exception { child("forced-release"); }
51:     static Path runs() throws IOException { return Files.createDirectories(root().resolve(".local/iam-o2b-f2c2a-retention-fixes/peers")); }
52:     static void child(String group) throws Exception {
53:         Path run = Files.createDirectory(runs().resolve(group + "-" + UUID.randomUUID()));
54:         var args = List.of("-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-Djdk.tls.maxHandshakeMessageSize=32768", "-Djdk.tls.maxCertificateChainLength=4",
55:         "-Dcom.sun.security.enableAIAcaIssuers=false", "-Djdk.tls.server.newSessionTicketCount=0", "-Djdk.tls.server.enableStatusRequestExtension=false",
56:         "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), PortalRunEventsSseLifecycleTest.class.getName(),
57:         group, run.toString());
58:         Files.writeString(run.resolve("client.args"), String.join("\n", args));
59:         var command = new ArrayList<String>();
60:         command.add(Path.of(System.getProperty("java.home"), "bin/java.exe").toString());
61:         command.addAll(args);
62:         Process p = new ProcessBuilder(command).directory(root().toFile()).redirectErrorStream(true).start();
63:         Files.writeString(run.resolve("launch.properties"), "pid=" + p.pid() + "\ncwd=" + root() + "\n");
64:         var failure = new AtomicReference<Throwable>();
65:         Thread collector = Thread.ofPlatform().daemon().start(() -> {
66:             try (var out = Files.newOutputStream(run.resolve("client.out")); var in = p.getInputStream()) {
67:                 byte[] b = new byte[4096];
68:                 int n, total = 0;
69:                 while ((n = in.read(b)) != -1) {
70:                     if ((total += n) > 131072) throw new IOException("bounded child output");
71:                     out.write(b, 0, n);
72:                 }
73:             }
74:             catch (Throwable t) {
75:                 failure.set(t);
76:                 p.destroyForcibly();
77:             }
78:         });
79:         try {
80:             assertTrue(p.waitFor(95, TimeUnit.SECONDS), "owned child deadline");
81:             collector.join(3000);
82:             assertFalse(collector.isAlive());
83:             assertNull(failure.get());
84:             assertEquals(0, p.exitValue(), new String(Files.readAllBytes(run.resolve("client.out")), StandardCharsets.UTF_8));
85:         }
86:         finally {
87:             if (p.isAlive()) p.destroyForcibly();
88:             assertTrue(p.waitFor(3, TimeUnit.SECONDS));
89:             collector.join(3000);
90:             assertFalse(collector.isAlive());
91:             Files.writeString(run.resolve("exit.properties"), "pid=" + p.pid() + "\nexit=" + p.exitValue() + "\nalive=" + p.isAlive() + "\ncollectorAlive=" + collector.isAlive() + "\n");
92:         }
93:     }
94: 
95:     @Test void invalidCursorCannotPublishAnUnreapableLease()throws Exception { child("invalid-cursor"); }
96:     @Test void preasyncUnsupportedDetachesHeavyStateBeforeRefund()throws Exception { child("preasync"); }
97:     @Test void uncertainHandleConstructionAndRegistrationBurnSharedCredits()throws Exception { child("handle-burn"); }
98:     @Test void mixedAutomaticReferencesReuseOneSharedPopulation()throws Exception { child("mixed-reference"); }
99:     @Test void actualAwaitClosedAncestorPinsRefundedShellCredit()throws Exception { child("ancestor-tail"); }
100:     @Test void retainedFinishedHandlesConsumeSharedFactoryCapacity()throws Exception { child("retained-cap"); }
101:     @Test void q403FailureClosesTransferredSessionBeforeReturningCapacity()throws Exception { child("fix-q403"); }
102:     @Test void qTransportFailureClosesTransferredSessionBeforeReturningCapacity()throws Exception { child("fix-qtransport"); }
103:     @Test void transferredServletCannotClaimTheOriginalRequestFromWorker()throws Exception { child("fix-offthread"); }
104:     @Test void originalRequestScopeAndDispatcherAreRequiredBeforeQuery()throws Exception { child("fix-dispatcher");child("fix-missing-token"); }
105:     @Test void uncertainProcessorConstructionBurnsItsReservedBundle()throws Exception { child("fix-burn"); }
106:     @Test void automaticPhantomNotificationCanReturnActualFactoryCapacity()throws Exception { child("fix-reuse"); }
107:     @Test void actualProcessorFactoryRefusesBeyondFixedStrongPopulation()throws Exception { child("fix-cap"); }
108:     public static void main(String[] args)throws Exception { scenario(args[0],Path.of(args[1])); }
109:     static byte[] wire(SequencePeer peer,byte[] hello,String child,ObjectNode original,byte[] payload,String mode)throws Exception {
110:         int count=mode.equals("backpressure")||mode.equals("pending-flush")?20:mode.equals("sequence")?2:1;
111:         var parts=new ArrayList<byte[]>();parts.add(hello);
112:         for(int i=1;i<=count;i++) {
113:             var m=original.deepCopy();
114:             ((ObjectNode)m.get("window")).put("afterSeq",i-1).put("watermark",count);
115:             ((ObjectNode)m.get("state")).put("event_seq",count);
116:             ((ObjectNode)m.get("eventIdentity")).put("seq",i);
117:             if(mode.equals("long-id")) { ((ObjectNode)m.get("eventIdentity")).put("seq",Long.MAX_VALUE);((ObjectNode)m.get("window")).put("watermark",Long.MAX_VALUE);((ObjectNode)m.get("state")).put("event_seq",Long.MAX_VALUE); }
118:             if(mode.equals("sequence")) {
119:                 ((ObjectNode)m.get("window")).put("status","SUCCEEDED");((ObjectNode)m.get("state")).put("status","SUCCEEDED");
120:             }
121:             if(mode.equals("reset")) { ((ObjectNode)m.get("window")).put("oldest",3).put("watermark",3);((ObjectNode)m.get("state")).put("event_seq",3); }
122:             ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));
123:             PortalRunEventsSseTest.payload(m,payload,mode.equals("sequence")?" "+"界".repeat(31):mode.equals("spaces")?" ".repeat(32):"EVENT");
124:             parts.add(peer.packet(2,JSON.writeValueAsBytes(m),payload,child,m));
125:         }
126:         if(mode.equals("sequence")) {
127:             var m=original.deepCopy();
128:             ((ObjectNode)m.get("window")).put("afterSeq",count).put("watermark",count).put("status","SUCCEEDED");
129:             ((ObjectNode)m.get("state")).put("event_seq",count).put("status","SUCCEEDED");
130:             m.put("hasEvent",false);m.putNull("eventScope");m.putNull("eventIdentity");
131:             ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));rehash(m);
132:             parts.add(peer.packet(2,JSON.writeValueAsBytes(m),EMPTY,child,m));
133:         }
134:         return PortalEventsPacketDecoderTest.join(parts.toArray(byte[][]::new));
135:     }
136:     static final class Exchange {
137:         final AtomicReference<PortalRunEventsSse.Handle> handle=new AtomicReference<>();
138:         final AtomicReference<PortalRunEventsHttp.Session> session=new AtomicReference<>();
139:         final AtomicReference<Throwable> failure=new AtomicReference<>();
140:         final List<String> trace=Collections.synchronizedList(new ArrayList<>());
141:         PortalRunEventsSse.Handle handle()throws Exception {
142:             long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
143:             while(handle.get()==null&&System.nanoTime()<end) { if(failure.get()!=null)throw new AssertionError(failure.get());Thread.sleep(10); }
144:             assertNotNull(handle.get());return handle.get();
145:         }
146:     }
147:     static final class Server implements AutoCloseable {
148:         final Tomcat tomcat=new Tomcat();
149:         org.apache.catalina.Context context;
150:         final PortalRunEventsSse.ControlledProtocol protocol=new PortalRunEventsSse.ControlledProtocol();
151:         final List<Exchange> exchanges=new ArrayList<>();
152:         final List<PortalEventsTls> endpoints;
153:         final AtomicInteger next=new AtomicInteger();
154:         final Exchange first=new Exchange();
155:         final AtomicReference<PortalRunEventsSse.Handle> handle=first.handle;
156:         final AtomicReference<PortalRunEventsHttp.Session> session=first.session;
157:         final AtomicReference<Throwable> failure=first.failure;
158:         final List<String> trace=first.trace;
159:         final Path run;
160:         String invocation="normal";
161:         Server(Path run,Fixture f,PortalEventsTls tls,BiConsumer<String,Long> hook)throws Exception {
162:             this(run,f,List.of(tls),Collections.singletonList(hook));
163:         }
164:         Server(Path run,Fixture f,List<PortalEventsTls> endpoints,List<BiConsumer<String,Long>> hooks)throws Exception {
165:             this.run=run;this.endpoints=endpoints;exchanges.add(first);for(int i=1;i<endpoints.size();i++)exchanges.add(new Exchange());
166:             Path base=Files.createDirectories(run.resolve("tomcat"));tomcat.setBaseDir(base.toString());
167:             var connector=new org.apache.catalina.connector.Connector(protocol);connector.setPort(0);tomcat.setConnector(connector);
168:             connector.setProperty("address","127.0.0.1");connector.setProperty("socket.txBufSize","1024");connector.setProperty("socket.appWriteBufSize","8192");
169:             connector.setProperty("minSpareThreads","2");
170:             context=tomcat.addContext("",base.toString());context.getPipeline().addValve(PortalRunEventsSse.contextValve());
171:             var servlet=Tomcat.addServlet(context,"events",new HttpServlet() {
172:                 @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
173:                     int index=next.getAndIncrement();var exchange=exchanges.get(index);var hook=hooks.get(index);
174:                     final Object requestToken=request.getAttribute(PortalRunEventsSse.class.getName()+".request");
175:                     var adapter=new PortalRunEventsSse((phase,value)->{
176:                         exchange.trace.add(phase+"="+value);
177:                         try { Object token=requestToken;if(token!=null) { Object state=field(token,"state");if(state!=null)exchange.session.compareAndSet(null,(PortalRunEventsHttp.Session)field(state,"session")); } }
178:                         catch(Exception failure) { throw new AssertionError(failure); }
179:                         if(hook!=null)hook.accept(phase,value);
180:                     });
181:                     try {
182:                         if(invocation.equals("handle-burn-1"))protocol.failHandleConstructionForTest(1);
183:                         if(invocation.equals("handle-burn-2"))protocol.failHandleConstructionForTest(2);
184:                         Runnable call=()->{ try {
185:                             var h=adapter.start(request,response,new PortalRunEventsHttp(endpoints.get(index)),f.observed,f.source,f.run,0);
186:                             exchange.handle.set(h);Object state=field(h,"state");if(state!=null)exchange.session.set((PortalRunEventsHttp.Session)field(state,"session"));
187:                         } catch(Throwable t) { exchange.failure.set(t); } };
188:                         if(invocation.equals("offthread")) {
189:                             Thread other=Thread.ofPlatform().start(call);other.join(6000);if(other.isAlive()) { other.interrupt();other.join(6000); }assertFalse(other.isAlive(),"bounded off-thread probe actually exited");
190:                         } else if(invocation.equals("dispatcher"))request.getRequestDispatcher("/reentry").include(request,response);
191:                         else call.run(); }
192:                     catch(Throwable t) { exchange.failure.set(t); }
193:                 }
194:             });servlet.setAsyncSupported(true);context.addServletMappingDecoded("/admin/runs/*","events");
195:             var reentry=Tomcat.addServlet(context,"reentry",new HttpServlet() { @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException { var exchange=exchanges.get(next.get()-1);try { exchange.handle.set(new PortalRunEventsSse().start(request,response,new PortalRunEventsHttp(endpoints.get(next.get()-1)),f.observed,f.source,f.run,0)); }catch(Throwable t) { exchange.failure.set(t); } } });reentry.setAsyncSupported(true);context.addServletMappingDecoded("/reentry","reentry");
196:             Tomcat.addServlet(context,"ping",new HttpServlet() { @Override protected void doGet(HttpServletRequest r,HttpServletResponse out)throws IOException { out.setContentLength(4);out.getOutputStream().write(utf("pong")); } });context.addServletMappingDecoded("/ping","ping");
197:             tomcat.start();assertEquals(1024,protocol.getMaxConnections());assertEquals(64,protocol.getMaxThreads());assertEquals(64,protocol.getProcessorCache());
198:         }
199:         Socket request(String run)throws Exception { return request(run,false); }
200:         Socket request(String run,boolean keepAlive)throws Exception {
201:             var socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",tomcat.getConnector().getLocalPort()));socket.setSoTimeout(12000);
202:             socket.getOutputStream().write(utf("GET /admin/runs/"+run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: "+(keepAlive?"keep-alive":"close")+"\r\n\r\n"));socket.getOutputStream().flush();return socket;
203:         }
204:         boolean waitTrace(String prefix,long millis)throws Exception {
205:             long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
206:             while(System.nanoTime()<end) { synchronized(trace) { if(trace.stream().anyMatch(x->x.startsWith(prefix)))return true; }Thread.sleep(10); }return false;
207:         }
208:         PortalRunEventsSse.Handle handle()throws Exception { return first.handle(); }
209:         public void close()throws Exception {
210:             for(int i=0;i<exchanges.size();i++) {
211:                 var exchange=exchanges.get(i);Files.write(run.resolve("sse-trace-finally-"+i+".txt"),exchange.trace,StandardCharsets.UTF_8);
212:                 var h=exchange.handle.get();if(h!=null) { h.close();assertTrue(h.awaitClosed(Duration.ofSeconds(5))); }
213:             }
214:             for(var tls:endpoints)tls.close();tomcat.stop();tomcat.destroy();
215:             Files.writeString(run.resolve("protocol.properties"),"implementation="+protocol.getClass().getName()+"\ncreated="+protocol.creationsForTest()+"\nrecycled="+protocol.recyclesForTest()+"\nwaiting="+protocol.getWaitingProcessorCount()+"\nbaseline="+PortalEventsPacketBudget.SHARED.connectorBaselineBytes()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\nowners="+PortalEventsPacketBudget.SHARED.owners()+"\n");
216:         }
217:     }
218:     static void scenario(String mode,Path run)throws Exception {
219:         if(mode.equals("invalid-cursor")) { invalidCursor(run);return; }
220:         if(mode.equals("preasync")) { preasync(run);return; }
221:         if(mode.equals("handle-burn")) { handleBurn(run);return; }
222:         if(mode.equals("mixed-reference")) { mixedReference(run);return; }
223:         if(mode.equals("ancestor-tail")) { ancestorTail(run);return; }
224:         if(mode.equals("retained-cap")) { retainedCap(run);return; }
225:         if(mode.startsWith("fix-q")) { startupFailure(mode,run);return; }
226:         if(mode.equals("fix-offthread")||mode.equals("fix-dispatcher")||mode.equals("fix-missing-token")) { startupScope(mode.substring(4),run);return; }
227:         if(mode.equals("fix-cap")||mode.equals("fix-burn")||mode.equals("fix-reuse")) { allocationCap(mode,run);return; }
228:         if(mode.startsWith("generation-")||mode.startsWith("freshness-")) { freshness(mode,run);return; }
229:         if(mode.startsWith("bootstrap-")) { bootstrap(mode.substring(10),run);return; }
230:         if(mode.startsWith("pending-flush-")) { pendingFlush(mode.substring(14),run);return; }
231:         switch(mode) {
232:             case "action-retention" -> { actionRetention(run);return; }
233:             case "processor-reuse" -> { processorReuse(run);return; }
234:             case "bad-profile" -> {
235:                 var protocol=new PortalRunEventsSse.ControlledProtocol();protocol.setMaxThreads(65);
236:                 assertThrows(IllegalStateException.class,protocol::start);assertThrows(IllegalStateException.class,PortalRunEventsSse.ControlledProtocol::new);
237:                 assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());return;
238:             }
239:             case "not-ready-idle" -> { notReadyIdle(run);return; }
240:             case "forced-release" -> { forcedRelease(run);return; }
241:             case "idle" -> { idle(run);return; }
242:             case "keepalive" -> { keepalive(run);return; }
243:             case "light-shell" -> { lightShell(run);return; }
244:             case "heavy-retention","completion-retention","abort-order" -> { retentionScenario(mode,run);return; }
245:         }
246:         byte[] raw=mode.startsWith("backpressure")?utf("\""+"x".repeat(262142)+"\""):mode.equals("precision")?utf(" { \"n\" : -0.00, \"value\": 1.23456789012345678901234567890e+20 } "):utf("null");
247:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},mode,raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
248:             if(mode.startsWith("backpressure")) {
249:                 assertTrue(server.waitTrace("ready-false",8000)||server.waitTrace("flush-wait",1000),"real stopped-reader readiness");
250:                 var handle=server.handle();long prior=handle.after();long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>83886080L);
251:                 if(mode.equals("backpressure-resume")) {
252:                     socket.setReceiveBufferSize(262144);byte[] body=socket.getInputStream().readAllBytes();assertTrue(body.length>262144);
253:                     assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(1,handle.after());assertTrue(traceCount(server.trace,"callback-entry")>1);
254:                 } else { handle.close();assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(prior,handle.after()); }
255:             } else {
256:                 byte[] body=socket.getInputStream().readAllBytes();var handle=server.handle();assertTrue(handle.awaitClosed(Duration.ofSeconds(4)));assertTrue(handle.detachedForTest());assertTrue(handle.inertTokenForTest());
257:                 String text=new String(body,StandardCharsets.UTF_8);
258:                 if(mode.equals("reset")) { assertTrue(text.contains("CURSOR_EXPIRED"));assertFalse(text.contains("stream_status"));assertFalse(text.contains("id: 1"));assertEquals(0,handle.after()); }
259:                 else { assertTrue(text.contains("event: stream_status"));
260:                 assertTrue(text.contains(mode.equals("precision")?"data: {\"n\":-0.00,\"value\":1.23456789012345678901234567890e+20}":"data: null"));
261:                 assertEquals(mode.equals("long-id")?Long.MAX_VALUE:mode.equals("sequence")?2:1,handle.after());assertEquals(mode.equals("sequence"),handle.terminal());
262:                 }
263:                 assertTrue(server.trace.contains("setter-return=0"));assertTrue(server.trace.contains("callback-entry=1"));
264:                 assertTrue(server.trace.indexOf("setter-return=0")<server.trace.indexOf("callback-entry=1"));
265:                 if(mode.equals("sequence")) { assertTrue(text.contains("id: 2"));assertTrue(text.contains("event:  界")); }
266:                 if(mode.equals("spaces"))assertTrue(text.contains("event: "+" ".repeat(32)+"\n"));
267:             }
268:             assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertNull(server.failure.get());
269:             Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
270:             Files.writeString(run.resolve("sse.properties"),"after="+server.handle().after()+"\nterminal="+server.handle().terminal()+"\nactualWorkerExit=true\nownerBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
271:         }
272:     }
273:     static Object field(Object value,String name)throws Exception {
274:         var f=(value instanceof Class<?> c?c:value.getClass()).getDeclaredField(name);f.setAccessible(true);return f.get(value instanceof Class<?>?null:value);
275:     }
276:     static void invalidCursor(Path run)throws Exception {
277:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"q-only",utf("null"));var tls=peer.tls()) {
278:             var opening=new AtomicReference<HttpCurrentAuthorityClient.GatewayEventsOpening>();
279:             var observed=(CurrentAuthorityClient)java.lang.reflect.Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),new Class<?>[]{CurrentAuthorityClient.class},(proxy,method,args)->{
280:                 try { Object value=method.invoke(f.observed,args);if(method.getName().equals("beginGatewayPortalEvents"))opening.set((HttpCurrentAuthorityClient.GatewayEventsOpening)value);return value; }
281:                 catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
282:             });
283:             var session=new PortalRunEventsHttp(tls).begin(observed,f.source,f.run,0);session.lookupMetadata();opening.get().close();
284:             assertThrows(RuntimeException.class,session::beginSse);session.close();boolean closed=session.awaitClosed(Duration.ofMillis(100));
285:             Files.writeString(run.resolve("invalid-cursor.properties"),"realQ=true\noriginalOpeningClosed=true\nbeginSseDenied=true\nactualClosed="+closed+"\nowners="+PortalEventsPacketBudget.SHARED.owners()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
286:             assertTrue(closed,"rejected original cursor must not publish an unreapable SSE lease");assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
287:         }
288:     }
289:     static void preasync(Path run)throws Exception {
290:         var serverRef=new AtomicReference<Server>();var detached=new AtomicBoolean();
291:         BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("unstarted-detached")) {
292:             assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>3145728);assertEquals(1,PortalEventsPacketBudget.SHARED.owners());detached.set(true);
293:         }};
294:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"q-only",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,hook)) {
295:             serverRef.set(server);((org.apache.catalina.Wrapper)server.context.findChild("events")).setAsyncSupported(false);
296:             try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
297:             assertInstanceOf(IllegalStateException.class,server.failure.get());assertTrue(detached.get());assertNull(server.handle.get());
298:             assertEquals(0,traceCount(server.trace,"window"));assertEquals(0,traceCount(server.trace,"setter-return"));assertEquals(0,traceCount(server.trace,"completion-entry"));
299:             assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(1,peer.connections);
300:             Files.writeString(run.resolve("preasync.properties"),"actualAsyncSupported=false\ndetachBarrierWhileOwnerAndLeaseCharged=true\nworker=0\nsetter=0\ncompletion=0\nfinalBaseline=3145728\n");
301:         }
302:     }
303:     static void handleBurn(Path run)throws Exception {
304:         try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"scope",utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"scope",utf("null"));var ta=a.tls();var tb=b.tls();var server=new Server(run,f,List.of(ta,tb),Arrays.asList(null,null))) {
305:             var held=new org.apache.coyote.Processor[1533];for(int i=0;i<1532;i++)held[i]=server.protocol.createProcessor();
306:             for(int i=0;i<2;i++) { server.invocation="handle-burn-"+(i+1);try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }assertInstanceOf(IllegalStateException.class,server.exchanges.get(i).failure.get()); }
307:             assertEquals(2,server.protocol.burnedForTest());assertEquals(2,server.protocol.handleAllocationsForTest());held[1532]=server.protocol.createProcessor();assertThrows(IllegalStateException.class,server.protocol::createProcessor);
308:             assertEquals(0,a.connections+b.connections);assertEquals(0,PortalEventsPacketBudget.SHARED.owners());java.lang.ref.Reference.reachabilityFence(held);
309:             Files.writeString(run.resolve("handle-burn.properties"),"constructorBurn=1\nregistrationBurn=1\nprocessors=1534\nsharedTotal=1536\nnewSession=0\n");
310:         }
311:     }
312:     static void releaseGatewayObserver(PortalRunEventsHttp.Session expected)throws Exception {
313:         assertNotNull(expected);Object gateway=currentGateway.get();assertNotNull(gateway);
314:         Object slot=field(field(field(gateway,"holder"),"evidence"),"slot");Object cancellation=field(slot,"cancel");
315:         int matches=0;for(var capture:cancellation.getClass().getDeclaredFields()) { capture.setAccessible(true);if(capture.get(cancellation)==expected)matches++; }
316:         assertEquals(1,matches,"the exact original bound Session.abort is the static observer root");
317:         currentGateway.set(null);
318:     }
319:     static void mixedReference(Path run)throws Exception {
320:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null)) {
321:             var held=new org.apache.coyote.Processor[1534];for(int i=0;i<held.length;i++)held[i]=server.protocol.createProcessor();
322:             try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
323:             assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));assertThrows(IllegalStateException.class,server.protocol::createProcessor);
324:             releaseGatewayObserver(server.session.get()); // This reference-bearing observer helper returns before roots are dropped.
325:             server.handle.set(null);server.session.set(null);held[0]=null;
326:             long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
327:             while(System.nanoTime()<end&&(server.protocol.handleReturnedForTest()==0||server.protocol.processorReturnedForTest()==0))Thread.sleep(10);
328:             boolean explicit=server.protocol.handleReturnedForTest()==0||server.protocol.processorReturnedForTest()==0;
329:             if(explicit)System.gc(); // The sole preauthorized test-only GC in this fresh mixed child.
330:             end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
331:             while(System.nanoTime()<end&&(server.protocol.handleReturnedForTest()==0||server.protocol.processorReturnedForTest()==0))Thread.sleep(10);
332:             long handles=server.protocol.handleReturnedForTest(),processors=server.protocol.processorReturnedForTest();int reused=0;
333:             if(handles>0&&processors>0) { held[0]=server.protocol.createProcessor();var extra=server.protocol.createProcessor();assertThrows(IllegalStateException.class,server.protocol::createProcessor);java.lang.ref.Reference.reachabilityFence(extra);reused=2; }
334:             Files.writeString(run.resolve("mixed-reference.properties"),"explicitGcCalls="+(explicit?1:0)+"\nhandleNotifications="+handles+"\nprocessorNotifications="+processors+"\nactualSharedFactoryReuse="+reused+"\nobservation="+(reused==2?"OBSERVED":"INCONCLUSIVE")+"\n");
335:             java.lang.ref.Reference.reachabilityFence(held);
336:         }
337:     }
338:     static void ancestorTail(Path run)throws Exception {
339:         var atRefund=new CountDownLatch(1);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var completionEntered=new CountDownLatch(1);var completionRelease=new CountDownLatch(1);
340:         var serverRef=new AtomicReference<Server>();var stateRef=new AtomicReference<Object>();
341:         BiConsumer<String,Long> hook=(phase,value)->{
342:             if(phase.equals("completion-entry")) { completionEntered.countDown();pause(completionRelease); }
343:             if(phase.equals("before-refund")) { atRefund.countDown();pause(locked); }
344:         };
345:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,hook);var socket=server.request(f.run)) {
346:             serverRef.set(server);assertTrue(completionEntered.await(5,TimeUnit.SECONDS));
347:             var handle=server.handle();Object state=field(handle,"state");var session=server.session.get();assertNotNull(session);Object cell=field(state,"cell");
348:             // Only the actual onComplete publishes completed; the external reaper uses its original gate.
349:             var locker=Thread.ofPlatform().start(()->{pause(atRefund);synchronized(cell) { locked.countDown();pause(release); }});
350:             session.close(); // Establish closing before the reaper, while real completion is still paused.
351:             var reaper=Thread.ofPlatform().start(()->assertTrue(session.awaitClosed(Duration.ofSeconds(10))));
352:             long reapEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
353:             while(Arrays.stream(reaper.getStackTrace()).noneMatch(x->x.getMethodName().equals("reap"))&&System.nanoTime()<reapEnd)Thread.sleep(5);
354:             assertTrue(Arrays.stream(reaper.getStackTrace()).anyMatch(x->x.getMethodName().equals("reap")));
355:             completionRelease.countDown();
356:             try {
357:                 assertTrue(locked.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
358:                 while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<end)Thread.sleep(5);
359:                 assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
360:                 assertSame(handle,field(session,"lifecycleAnchor"));assertSame(handle,field(state,"handle"));
361:                 for(String name:List.of("frame","probe","output","slot","worker","async","token","lease"))assertNull(field(state,name),name);
362:                 for(String name:List.of("client","manager","connection","request","context","ssl","sslId","raw","handle","alarm","timer","beforeOutputForTest","decoder","privateInput","gatewayResponse","child","saved","authority","originalPortalSource","opening","query","facts","privateFee","fee","materialChild","materialCheckpointForTest","operationThread"))assertNull(field(session,name),name);
363:                 assertTrue(((Map<?,?>)field(session,"guards")).isEmpty());Object lease=field(session,"sseLease");assertEquals(true,field(lease,"released"));for(String name:List.of("charge","cancellation","exit"))assertNull(field(lease,name));assertNull(field(field(session,"owner"),"decoder"));
364:                 assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(tls).forSse(f.observed,f.source,f.run,0,handle));assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
365:                 boolean ancestor=Arrays.stream(reaper.getStackTrace()).anyMatch(x->x.getMethodName().equals("awaitClosed")||x.getMethodName().equals("reap"));
366:                 Files.writeString(run.resolve("ancestor-tail.properties"),"ownerRefunded=true\ncellHeldAfterDetachHelperReturned=true\nfinalSessionAnchorExact=true\nfinalStateHandleExact=true\nreaperAncestorObserved="+ancestor+"\n");
367:                 assertTrue(ancestor,"actual awaitClosed/reap ancestor remains live after refund");
368:             } finally { release.countDown();completionRelease.countDown();locker.join(5000);reaper.join(5000);assertFalse(locker.isAlive());assertFalse(reaper.isAlive()); }
369:             assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));
370:         } finally { release.countDown();completionRelease.countDown(); }
371:     }
372:     static void retainedCap(Path run)throws Exception {
373:         try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",utf("null"));var c=new SequencePeer(f,Files.createDirectories(run.resolve("c")),m->{},"scope",utf("null"));var ta=a.tls();var tb=b.tls();var tc=c.tls();var server=new Server(run,f,List.of(ta,tb,tc),Arrays.asList(null,null,null))) {
374:             var held=new org.apache.coyote.Processor[1533];
375:             for(int i=0;i<held.length;i++)held[i]=server.protocol.createProcessor();
376:             for(int i=0;i<2;i++) {
377:                 try(var socket=server.request(f.run)) { assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("data: null")); }
378:                 assertTrue(server.exchanges.get(i).handle().awaitClosed(Duration.ofSeconds(5)));
379:             }
380:             assertEquals(1534,server.protocol.creationsForTest(),"two real requests reuse one original processor");
381:             try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
382:             assertInstanceOf(IllegalStateException.class,server.exchanges.get(2).failure.get(),"retained finished Handles must exhaust the common pool before third Session creation");
383:             assertNull(server.exchanges.get(2).handle.get());assertEquals(0,c.connections,"refusal before Session/Q");
384:             Files.writeString(run.resolve("retained-cap.properties"),"manualProcessors=1533\nactualRequestProcessors=1\nretainedFinishedHandles=2\nthirdDeniedBeforeSession=true\n");
385:             java.lang.ref.Reference.reachabilityFence(held);java.lang.ref.Reference.reachabilityFence(server.exchanges);
386:         }
387:     }
388:     static void startupFailure(String mode,Path run)throws Exception {
389:         String selected=mode.substring(4);
390:         try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},selected,utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},selected,utf("null"));var c=new SequencePeer(f,Files.createDirectories(run.resolve("c")),m->{},"eof",utf("null"));var ta=a.tls();var tb=b.tls();var tc=c.tls();var server=new Server(run,f,List.of(ta,tb,tc),Arrays.asList(null,null,null))) {
391:             try {
392:                 for(int i=0;i<2;i++) {
393:                     try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
394:                     var x=server.exchanges.get(i);assertNotNull(x.failure.get());if(selected.equals("q403")) { var failure=assertInstanceOf(PlatformException.class,x.failure.get());assertEquals(403,failure.status());assertEquals("RUN_AUTHORITY_DENIED",failure.code()); }assertNull(x.handle.get());assertFalse(traceCount(x.trace,"setter-return")>0);assertFalse(traceCount(x.trace,"window")>0);
395:                     Files.writeString(run.resolve("before-cleanup-"+i+".properties"),"owners="+PortalEventsPacketBudget.SHARED.owners()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
396:                     assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
397:                 }
398:                 try(var socket=server.request(f.run)) { assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("data: null")); }
399:                 assertTrue(server.exchanges.get(2).handle().awaitClosed(Duration.ofSeconds(5)));
400:                 Files.writeString(run.resolve("startup.properties"),"repeatFailures=2\nsubsequentNormalStart=true\n");
401:             } finally { for(var x:server.exchanges)if(x.session.get()!=null)x.session.get().close(); }
402:         }
403:     }
404:     static void startupScope(String mode,Path run)throws Exception {
405:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"scope",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null)) {
406:             server.invocation=mode;
407:             if(mode.equals("missing-token"))for(var valve:server.context.getPipeline().getValves())if(valve.getClass().getName().contains("PortalRunEventsSse$ContextValve"))server.context.getPipeline().removeValve(valve);
408:             try {
409:                 try(var socket=server.request(f.run)) { try { socket.getInputStream().readAllBytes(); }catch(SocketTimeoutException boundedOldProbe) { Files.writeString(run.resolve("old-probe-timeout.txt"),"real connector probe reached bounded read deadline\n"); } }
410:                 assertEquals(0,peer.connections,"reject before Q");assertNotNull(server.failure.get());assertNull(server.handle.get(),"original REQUEST only");
411:                 assertFalse(traceCount(server.trace,"bootstrap-wait")>0);assertFalse(traceCount(server.trace,"setter-return")>0);assertEquals(0,peer.connections,"reject before Q");
412:                 assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
413:                 Files.writeString(run.resolve("scope.properties"),"mode="+mode+"\nqConnections="+peer.connections+"\nsetter=0\n");
414:             } finally { if(server.session.get()!=null)server.session.get().close(); }
415:         }
416:     }
417:     static void allocationCap(String mode,Path run)throws Exception {
418:         Tomcat tomcat=new Tomcat();var protocol=new PortalRunEventsSse.ControlledProtocol();var connector=new org.apache.catalina.connector.Connector(protocol);connector.setPort(0);tomcat.setConnector(connector);tomcat.setBaseDir(Files.createDirectories(run.resolve("tomcat")).toString());tomcat.addContext("",run.toString());
419:         org.apache.coyote.Processor[] held=new org.apache.coyote.Processor[1537];
420:         try {
421:             tomcat.start();
422:             if(mode.equals("fix-burn")) {
423:                 protocol.failConstructionForTest(1);assertThrows(IllegalStateException.class,protocol::createProcessor);
424:                 protocol.failConstructionForTest(2);assertThrows(IllegalStateException.class,protocol::createProcessor);assertEquals(2,protocol.burnedForTest());
425:                 for(int i=0;i<1534;i++)held[i]=protocol.createProcessor();assertThrows(IllegalStateException.class,protocol::createProcessor);
426:                 assertEquals(1536,protocol.allocationsForTest());assertEquals(2,protocol.burnedForTest());
427:                 Files.writeString(run.resolve("burn.properties"),"burned=2\ntracked=1534\nownedAllocations=1536\nrefusalBeforeExtraAllocation=true\n");return;
428:             }
429:             for(int i=0;i<1535;i++)held[i]=protocol.createProcessor();
430:             assertTrue(protocol.waitingIdentityForTest(held[0]));held[0].recycle();assertTrue(protocol.waitingIdentityForTest(held[0]));
431:             var start=new CountDownLatch(1);var success=new AtomicInteger();var denied=new AtomicInteger();var unexpected=new AtomicReference<Throwable>();
432:             Thread[] threads=new Thread[2];for(int j=0;j<2;j++) { final int index=1535+j;threads[j]=Thread.ofPlatform().start(()->{try { start.await();held[index]=protocol.createProcessor();success.incrementAndGet(); }catch(IllegalStateException expected) { denied.incrementAndGet(); }catch(Throwable t) { unexpected.set(t); }}); }
433:             start.countDown();for(var thread:threads) { thread.join(5000);assertFalse(thread.isAlive()); }
434:             Files.writeString(run.resolve("cap.properties"),"strongInitial=1535\nraceSuccess="+success+"\nraceDenied="+denied+"\ncreated="+protocol.creationsForTest()+"\n");
435:             assertNull(unexpected.get());assertEquals(1,success.get(),"only last allocation slot admitted");assertEquals(1,denied.get());assertThrows(IllegalStateException.class,protocol::createProcessor);assertEquals(1536,protocol.creationsForTest());assertEquals(1536,protocol.allocationsForTest());
436:             if(mode.equals("fix-reuse")) {
437:                 java.lang.ref.Reference.reachabilityFence(held);Arrays.fill(held,null);
438:                 long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(protocol.returnedForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
439:                 boolean explicit=protocol.returnedForTest()==0;if(explicit)System.gc(); // Authorized once, isolated witness only.
440:                 end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(protocol.returnedForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
441:                 long returned=protocol.returnedForTest();
442:                 if(returned>0)held[0]=protocol.createProcessor();
443:                 Files.writeString(run.resolve("reference-witness.properties"),"explicitGcCalls="+(explicit?1:0)+"\nautomaticNotifications="+returned+"\nactualFactoryReuse="+(held[0]!=null)+"\nobservation="+(returned>0?"OBSERVED":"INCONCLUSIVE")+"\n");
444:             }
445:         } finally { java.lang.ref.Reference.reachabilityFence(held);tomcat.stop();tomcat.destroy(); }
446:     }
447:     static String line(InputStream in)throws IOException {
448:         var out=new ByteArrayOutputStream();int previous=-1,b;
449:         while((b=in.read())!=-1) { if(previous=='\r'&&b=='\n')return out.toString(StandardCharsets.US_ASCII).substring(0,out.size()-1);out.write(b);previous=b;if(out.size()>8192)throw new IOException("bounded response line"); }
450:         throw new EOFException();
451:     }
452:     static byte[] chunked(Socket socket)throws IOException {
453:         var in=socket.getInputStream();assertTrue(line(in).startsWith("HTTP/1.1 200"));
454:         String h;boolean chunked=false;while(!(h=line(in)).isEmpty())if(h.equalsIgnoreCase("Transfer-Encoding: chunked"))chunked=true;
455:         assertTrue(chunked);var body=new ByteArrayOutputStream();
456:         while(true) { int n=Integer.parseInt(line(in),16);if(n==0) { assertEquals("",line(in));return body.toByteArray(); }if(n>1048576||body.size()+n>2097152)throw new IOException("bounded SSE response");body.write(in.readNBytes(n));assertEquals("",line(in)); }
457:     }
458:     static void keepalive(Path run)throws Exception {
459:         var atExit=new CountDownLatch(1);var release=new CountDownLatch(1);
460:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{ if(phase.equals("worker-body-exit")) { atExit.countDown();pause(release); } });var socket=server.request(f.run,true)) {
461:             try {
462:                 assertTrue(atExit.await(5,TimeUnit.SECONDS));assertTrue(new String(chunked(socket),StandardCharsets.UTF_8).contains("data: null"));
463:                 assertTrue(server.waitTrace("token-disarmed",5000));assertTrue(server.handle().inertTokenForTest());var h=server.handle();assertFalse(h.awaitClosed(Duration.ZERO));assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>0);
464:                 socket.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));socket.getOutputStream().flush();h.close();
465:                 String next=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(next.startsWith("HTTP/1.1 200"));assertTrue(next.endsWith("pong"));
466:                 release.countDown();assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());
467:                 Files.writeString(run.resolve("keepalive.properties"),"sameSocket=true\nlateAbortAfterRealDisarm=true\nnextRequestPong=true\nworkerHeldFeesUntilActualExit=true\n");
468:             } finally { release.countDown(); }
469:         }
470:     }
471:     static void pause(CountDownLatch release) {
472:         try { if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("owned pause deadline"); }
473:         catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
474:     }
475:     static void freshness(String mode,Path run)throws Exception {
476:         var points=List.of(mode.substring(mode.indexOf('-')+1));
477:         for(String point:points) {
478:             Path vector=Files.createDirectory(run.resolve(point));var changed=new AtomicBoolean();var frames=new AtomicInteger();
479:             byte[] raw=utf("\""+"x".repeat(240000)+"\"");
480:             try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},"eof",raw);var tls=peer.tls()) {
481:                 BiConsumer<String,Long> hook=(phase,value)->{
482:                     if(phase.equals("frame-complete"))frames.incrementAndGet();
483:                     boolean trigger=point.contains("getter")?phase.equals(point):frames.get()==1&&(point.equals("revoke")||point.equals("partial")?phase.equals("after-write"):phase.equals(point));
484:                     if(trigger&&changed.compareAndSet(false,true)) {
485:                         if(point.equals("partial"))throw new UncheckedIOException(new IOException("partial-unknown sentinel"));
486:                         var g=currentGateway.get();
487:                         if(point.equals("revoke")) { f.iam.revoked(true);assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(g)); }
488:                         else { var previous=g.currentCapture();f.gateway.checkGatewayPortalEvents(g);assertTrue(g.isSuperseded(previous)); }
489:                     }
490:                 };
491:                 try(var server=new Server(vector,f,tls,hook);var socket=server.request(f.run)) {
492:                     try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
493:                     assertTrue(changed.get(),point);
494:                     var handle=server.handle.get();
495:                     if(handle!=null) { assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(0,handle.after()); }
496:                     else { assertNotNull(server.failure.get());assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5))); }
497:                     assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());
498:                     Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
499:                 }
500:             }
501:         }
502:     }
503:     static void bootstrap(String selected,Path run)throws Exception {
504:         for(String mode:List.of(selected)) {
505:             Path vector=Files.createDirectory(run.resolve(mode));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
506:             try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},mode,utf("null"));var tls=peer.tls();var server=new Server(vector,f,tls,(phase,value)->{if(mode.equals("late")&&phase.equals("window")) { entered.countDown();pause(release); }});var socket=server.request(f.run)) {
507:                 try {
508:                     if(mode.equals("late")) { assertTrue(entered.await(5,TimeUnit.SECONDS));server.session.get().close();assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>83886080L);release.countDown(); }
509:                     try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
510:                     assertNull(server.handle.get());assertNotNull(server.failure.get());
511:                     assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5)));assertFalse((traceCount(server.trace,"setter-return")>0));
512:                     assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
513:                 } finally { release.countDown(); }
514:             }
515:         }
516:     }
517:     static void idle(Path run)throws Exception {
518:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
519:             var h=server.handle();assertTrue(server.waitTrace("ready-idle",5000));long start=System.nanoTime();
520:             for(int i=0;i<20;i++) { h.wakeForTest();Thread.sleep(30); }
521:             assertTrue(h.awaitClosed(Duration.ofSeconds(20)));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
522:             assertTrue(elapsed>=13000&&elapsed<19000,"actual original 15s idle, no peer timeout or reset by wake");assertFalse(h.terminal());
523:             Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);Files.writeString(run.resolve("idle.properties"),"spuriousWakes=20\nelapsedMs="+elapsed+"\ncontainerAsyncTimeout=0\n");
524:         }
525:     }
526:     static long traceCount(List<String> trace,String prefix) { synchronized(trace) { return trace.stream().filter(x->x.startsWith(prefix)).count(); } }
527:     static void lightShell(Path run)throws Exception {
528:         var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var frames=new AtomicInteger();var hit=new AtomicBoolean();
529:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{
530:             if(phase.equals("frame-complete"))frames.incrementAndGet();
531:             if(phase.equals("ready-idle")&&frames.get()==2&&hit.compareAndSet(false,true)) { entered.countDown();pause(release); }
532:         });var socket=server.request(f.run)) {
533:             try {
534:                 assertTrue(entered.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
535:                 while(System.nanoTime()<end) { long observed=PortalEventsPacketBudget.SHARED.reservedBytes();if(observed>=67108864L&&observed<83886080L)break;Thread.sleep(10); }
536:                 long scan=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(scan>=67108864L&&scan<83886080L,"old C1 gone, next actual F1 read charged");
537:                 server.session.get().close();assertFalse(server.session.get().awaitClosed(Duration.ZERO));
538:                 assertEquals(7218374L,PortalEventsPacketBudget.SHARED.reservedBytes(),"original HTTP/private/SSE/Owner retained before completion");
539:                 release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());
540:                 Files.writeString(run.resolve("light-shell.properties"),"nextF1Bytes="+scan+"\noldMaterialExited=true\nouterCallbackStillPaused=true\nretainedBeforeCompletion=7218374\nfinalBytes=3145728\n");
541:             } finally { release.countDown(); }
542:         }
543:     }
544:     static void pendingFlush(String selected,Path run)throws Exception {
545:         for(String outcome:List.of(selected)) {
546:             Path vector=Files.createDirectory(run.resolve(outcome));byte[] raw=utf("\""+"x".repeat(8000)+"\"");
547:             try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},"pending-flush",raw);var tls=peer.tls();var server=new Server(vector,f,tls,null);var socket=server.request(f.run)) {
548:                 assertTrue(server.waitTrace("flush-wait",8000),"actual nonblocking flush left bytes pending");
549:                 var h=server.handle();long before=h.after();assertTrue(before<20);
550:                 if(outcome.equals("generation")) { var g=currentGateway.get();var old=g.currentCapture();f.gateway.checkGatewayPortalEvents(g);assertTrue(g.isSuperseded(old)); }
551:                 if(outcome.equals("revoke")) { f.iam.revoked(true);assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(currentGateway.get())); }
552:                 assertEquals(before,h.after());socket.setReceiveBufferSize(262144);
553:                 try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
554:                 assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(outcome.equals("drain")?20:before,h.after());
555:                 Files.writeString(vector.resolve("pending-flush.properties"),"outcome="+outcome+"\nactualFlushWait=true\npublicAfterWhilePending="+before+"\npublicAfterExit="+h.after()+"\n");
556:                 Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
557:             }
558:         }
559:     }
560:     static void retentionScenario(String mode,Path run)throws Exception {
561:         var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var hitA=new AtomicBoolean();var hitB=new AtomicBoolean();
562:         String trigger=mode.equals("heavy-retention")?"frame-complete":mode.equals("completion-retention")?"completion-entry":"before-write";
563:         BiConsumer<String,Long> a=(phase,value)->{if(phase.equals(trigger)&&hitA.compareAndSet(false,true)) { entered.countDown();pause(release); }};
564:         BiConsumer<String,Long> b=(phase,value)->{if(phase.equals(trigger)&&hitB.compareAndSet(false,true)) { entered.countDown();pause(release); }};
565:         try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",utf("null"));var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",utf("null"));var ta=pa.tls();var tb=pb.tls();var server=new Server(run,f,List.of(ta,tb),List.of(a,b));var ca=server.request(f.run);var cb=server.request(f.run)) {
566:             var sa=server.exchanges.get(0);var sb=server.exchanges.get(1);
567:             try {
568:                 assertTrue(entered.await(7,TimeUnit.SECONDS));
569:                 long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertEquals(2,PortalEventsPacketBudget.SHARED.owners());
570:                 assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
571:                 if(mode.equals("heavy-retention")) { sa.session.get().close();sb.session.get().close();sa.handle().interruptReaderForTest();sb.handle().interruptReaderForTest();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.session.get().awaitClosed(Duration.ZERO)); }
572:                 if(mode.equals("completion-retention")) { sa.session.get().close();sb.session.get().close();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.handle().awaitClosed(Duration.ofMillis(40))); }
573:                 if(mode.equals("abort-order")) {
574:                     var abortA=Thread.ofPlatform().start(sa.handle()::close);var abortB=Thread.ofPlatform().start(sb.handle()::close);
575:                     Thread.sleep(100);assertTrue(abortA.isAlive());assertTrue(abortB.isAlive());assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());
576:                     release.countDown();abortA.join(5000);abortB.join(5000);assertFalse(abortA.isAlive());assertFalse(abortB.isAlive());
577:                 }
578:                 release.countDown();assertTrue(sa.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(sb.handle().awaitClosed(Duration.ofSeconds(6)));
579:                 assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
580:                 Files.writeString(run.resolve("retention.properties"),"mode="+mode+"\nheldBytes="+held+"\nthirdDenied=true\nactualWorkersExited=true\nfinalOwnedBytes=0\nconnectorBaseline=3145728\n");
581:                 Files.write(run.resolve("a-trace.txt"),sa.trace,StandardCharsets.UTF_8);Files.write(run.resolve("b-trace.txt"),sb.trace,StandardCharsets.UTF_8);
582:             } finally { release.countDown(); }
583:         }
584:     }
585:     static void awaitPublicBackpressure(Exchange exchange)throws Exception {
586:         long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int previous=-1;
587:         while(System.nanoTime()<end) {
588:             int count;boolean idle;synchronized(exchange.trace) { count=exchange.trace.size();idle=count>0&&exchange.trace.get(count-1).startsWith("ready-false="); }
589:             if(idle&&count==previous)return;previous=count;Thread.sleep(30);
590:         }
591:         fail("actual public ready-false must settle before launching this external action");
592:     }
593:     static void actionRetention(Path run)throws Exception {
594:         var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
595:         BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("before-close-now")) { entered.countDown();pause(release); }};
596:         byte[] raw=utf("\""+"x".repeat(262142)+"\"");
597:         try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",raw);var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",raw);var ta=pa.tls();var tb=pb.tls();var server=new Server(run,f,List.of(ta,tb),List.of(hook,hook));var ca=server.request(f.run);var cb=server.request(f.run)) {
598:             var a=server.exchanges.get(0);var b=server.exchanges.get(1);
599:             Thread aa=null,bb=null;
600:             try {
601:                 // Hold the first actual action before waiting for the second public output; the first
602:                 // response must not expire/complete while the fixture waits for both backpressure states.
603:                 awaitPublicBackpressure(a);aa=Thread.ofPlatform().start(a.handle()::close);
604:                 awaitPublicBackpressure(b);bb=Thread.ofPlatform().start(b.handle()::close);
605:                 assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(aa.isAlive());assertTrue(bb.isAlive());
606:                 assertFalse(a.handle().awaitClosed(Duration.ofMillis(40)));assertFalse(b.handle().awaitClosed(Duration.ofMillis(40)));
607:                 assertEquals(2,PortalEventsPacketBudget.SHARED.owners());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
608:                 long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>=13388172L);
609:                 release.countDown();aa.join(5000);bb.join(5000);assertFalse(aa.isAlive());assertFalse(bb.isAlive());
610:                 assertTrue(a.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(b.handle().awaitClosed(Duration.ofSeconds(6)));
611:                 assertEquals(1,traceCount(a.trace,"close-now-return"));assertEquals(1,traceCount(b.trace,"close-now-return"));
612:                 Files.writeString(run.resolve("action-retention.properties"),"heldBytes="+held+"\nthirdDenied=true\nactualCloseNowEach=1\nactualActionThreadsExited=true\n");
613:             } finally { release.countDown();if(aa!=null)aa.join(5000);if(bb!=null)bb.join(5000); }
614:         }
615:     }
616:     static void processorReuse(Path run)throws Exception {
617:         var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
618:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{if(phase.equals("worker-body-exit")) { entered.countDown();pause(release); }});var first=server.request(f.run)) {
619:             try {
620:                 assertTrue(entered.await(5,TimeUnit.SECONDS));first.getInputStream().readAllBytes();
621:                 long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(server.protocol.recyclesForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
622:                 assertTrue(server.protocol.recyclesForTest()>0);long old=server.protocol.processorForTest();Thread.sleep(100);
623:                 try(var next=new Socket("127.0.0.1",server.tomcat.getConnector().getLocalPort())) {
624:                     next.setSoTimeout(5000);next.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\nGET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));next.getOutputStream().flush();
625:                     server.handle().close();String body=new String(next.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(2,body.split("pong",-1).length-1);
626:                     assertEquals(old,server.protocol.processorForTest());assertEquals(1,server.protocol.creationsForTest());
627:                     assertEquals(0,traceCount(server.trace,"before-close-now"));
628:                 }
629:                 release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));
630:                 Files.writeString(run.resolve("reuse.properties"),"sameProcessorId="+old+"\ndifferentSocket=true\npipelinedPingResponses=2\nlateActionCalls=0\n");
631:             } finally { release.countDown(); }
632:         }
633:     }
634:     static void notReadyIdle(Path run)throws Exception {
635:         byte[] raw=utf("\""+"x".repeat(262142)+"\"");
636:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
637:             assertTrue(server.waitTrace("ready-false",5000));var h=server.handle();long before=h.after(),start=System.nanoTime();
638:             assertTrue(h.awaitClosed(Duration.ofSeconds(22)));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
639:             assertTrue(elapsed>=12000&&elapsed<21000);assertEquals(before,h.after());assertEquals(1,traceCount(server.trace,"close-now-return"));
640:             Files.writeString(run.resolve("not-ready-idle.properties"),"noClientReadAfterReadyFalse=true\nelapsedMs="+elapsed+"\ncloseNowCalls=1\nafter="+h.after()+"\n");
641:         }
642:     }
643:     static void forcedRelease(Path run)throws Exception {
644:         byte[] raw=utf("\""+"x".repeat(262142)+"\"");
645:         var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
646:         var owner=new AtomicReference<Server>();
647:         var sockets=new ConcurrentHashMap<String,org.apache.tomcat.util.net.SocketWrapperBase<?>>();
648:         BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("before-write")) { var actual=owner.get().protocol.currentSocketForTest();if(actual!=null)sockets.putIfAbsent(Thread.currentThread().getName()+"-"+System.identityHashCode(actual),actual); }if(phase.equals("recycle-invalidated")) { entered.countDown();pause(release); }};
649:         try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",raw);var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",raw);var ta=pa.tls();var tb=pb.tls() ) {
650:             var server=new Server(run,f,List.of(ta,tb),List.of(hook,hook));
651:             owner.set(server);
652:             try(var ca=server.request(f.run);var cb=server.request(f.run)) {
653:                 var a=server.exchanges.get(0);var b=server.exchanges.get(1);a.handle();b.handle();
654:                 long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<end&&!((traceCount(a.trace,"ready-false")>0)&&(traceCount(b.trace,"ready-false")>0)))Thread.sleep(10);
655:                 var actual=new HashSet<>(sockets.values());assertEquals(2,actual.size());
656:                 var closers=actual.stream().map(socket->Thread.ofPlatform().start(socket::close)).toList();
657:                 try {
658:                     assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(0,server.protocol.recyclesForTest());
659:                     a.session.get().close();b.session.get().close();a.handle().close();b.handle().close();
660:                     assertFalse(a.handle().awaitClosed(Duration.ofMillis(50)));assertFalse(b.handle().awaitClosed(Duration.ofMillis(50)));
661:                     assertEquals(2,PortalEventsPacketBudget.SHARED.owners());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
662:                     assertFalse((traceCount(a.trace,"before-close-now")>0));assertFalse((traceCount(b.trace,"before-close-now")>0));
663:                     release.countDown();for(var closer:closers) { closer.join(5000);assertFalse(closer.isAlive()); }
664:                     assertEquals(0,server.protocol.getWaitingProcessorCount());assertTrue(server.protocol.recyclesForTest()>=2);assertTrue(a.handle().inertTokenForTest());assertTrue(b.handle().inertTokenForTest());
665:                     a.handle().close();b.handle().close();assertFalse((traceCount(a.trace,"before-close-now")>0));assertFalse((traceCount(b.trace,"before-close-now")>0));
666:                     assertFalse((traceCount(a.trace,"completion-entry")>0));assertFalse((traceCount(b.trace,"completion-entry")>0));
667:                     Files.writeString(run.resolve("forced-release.properties"),"actualSocketCloseEach=true\nbarrierBeforeDelegateRecycle=true\nrecycled="+server.protocol.recyclesForTest()+"\nwaiting=0\ncompletionNotInvented=true\nownersRetained=2\nlateActionCalls=0\n");
668:                 } finally { release.countDown();for(var closer:closers)closer.join(5000); }
669:             } finally { for(int i=0;i<server.exchanges.size();i++)Files.write(run.resolve("forced-trace-"+i+".txt"),server.exchanges.get(i).trace,StandardCharsets.UTF_8);server.tomcat.stop();server.tomcat.destroy(); }
670:         }
671:     }
672:     static final class SequencePeer implements AutoCloseable {
673:         final Fixture f;
674:         final SSLServerSocket listener;
675:         final Thread worker;
676:         final Path run;
677:         volatile SSLSocket socket;
678:         volatile Throwable failure;
679:         int connections; long ordinal;
680:         String receipt;
681:         PortalEventsResponse engineResponse;
682:         SequencePeer(Fixture f, Path run, Consumer<ObjectNode> mutation, String mode, byte[] payload) throws Exception {
683:             this.f = f;
684:             this.run = run;
685:             var c = SSLContext.getInstance("TLSv1.3", "SunJSSE");
686:             var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
687:             km.init(PortalRunEventsHttpTest.fixtures(), "f2a-test-only".toCharArray());
688:             var key = Arrays.stream(km.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
689:             c.init(new KeyManager[] {
690:                 new PortalEventsTlsTest.FixedKey(key, "normal", new Properties())
691:             }, null, null);
692:             listener = (SSLServerSocket)c.getServerSocketFactory().createServerSocket(0, 2, InetAddress.getByAddress(new byte[] {
693:                 127, 0, 0, 1
694:             }));
695:             listener.setEnabledProtocols(new String[] {
696:                 "TLSv1.3"
697:             });
698:             listener.setEnabledCipherSuites(new String[] {
699:                 PortalEventsTls.CIPHER
700:             });
701:             listener.setSoTimeout(5000);
702:             worker = Thread.ofPlatform().daemon().start(() -> {
703:                 try {
704:                     try (var q = (SSLSocket)listener.accept()) {
705:                         socket = q;
706:                         connections++;
707:                         q.setSoTimeout(5000);
708:                         q.startHandshake();
709:                         head(q);
710:                         if(mode.equals("q403")) { byte[] body=utf("{\"code\":\"RUN_AUTHORITY_DENIED\",\"message\":\"denied\",\"data\":null,\"traceId\":null}");q.getOutputStream().write(PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 403 Denied\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n"),body));q.getOutputStream().flush();return; }
711:                         if(mode.equals("qtransport"))return;
712:                         q.getOutputStream().write(f.q());
713:                         q.getOutputStream().flush();
714:                     }
715:                     if(mode.equals("q-only"))return;
716:                     try (var e = (SSLSocket)listener.accept()) {
717:                         socket = e;
718:                         connections++;
719:                         e.setSoTimeout(25000);
720:                         e.startHandshake();
721:                         String h = head(e);
722:                         String bearer = h.substring(h.indexOf("Authorization: Bearer ") + 22).split("\r\n", 2)[0];
723:                         // Real mapped receiver frame. Public bind(currentOnly) cannot supply this initial provenance.
724:                         var previousSecurity = SecurityContextHolder.getContext();
725:                         var previousRequest = RequestContextHolder.getRequestAttributes();
726:                         var request = new MockHttpServletRequest("GET", "/admin/runs/" + f.run + "/events");
727:                         var response = new MockHttpServletResponse();
728:                         request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/runs/{id}/events");
729:                         var handler = new org.springframework.web.method.HandlerMethod(new PortalEventsMaterialAuthorityTest(), PortalEventsMaterialAuthorityTest.class.getMethod("handler"));
730:                         var gate = new ServiceAuthorization("engine", new ServiceRoutePolicy(), f.engine);
731:                         try (var decoder = new TrustedJwtDecoder(f.iam.config("engine"), f.iam.clock(), true)) {
732:                             var context = SecurityContextHolder.createEmptyContext();
733:                             context.setAuthentication(new JwtAuthenticationToken(decoder.decode(bearer), List.of()));
734:                             SecurityContextHolder.setContext(context);
735:                             RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
736:                             assertTrue(gate.preHandle(request, response, handler));
737:                             var binding = RunObservationSelectors.events(f.run, f.accepted);
738:                             var retained = AuthorityContext.requireInitialFor(AuthorityContext.current().actor(), binding);
739:                             engineResponse = f.engine.openPortalEventsChild(retained, 0, () -> {
740:                             });
741:                             receipt = f.engine.portalEventsChildReceipt(engineResponse);
742:                             var capture = engineResponse.currentCapture();
743:                             String child = capture.responseId();
744:                             var model = f.original.deepCopy();
745:                             mutation.accept(model);
746:                             byte[] m = JSON.writeValueAsBytes(model), p = payload == null ? asset().payload() : payload;
747:                             String signed = mode.equals("bad-receipt") ? receipt.substring(0, receipt.lastIndexOf('.')+1) + (receipt.charAt(receipt.lastIndexOf('.')+1)=='a'?"b":"a") + receipt.substring(receipt.lastIndexOf('.')+2) : receipt;
748:                             byte[] hello = JSON.writeValueAsBytes(Map.of("receipt", signed));
749:                             String helloChild = mode.equals("wrong-child") ? "foreign-child" : child;
750:                             byte[] first=packet(1, hello, EMPTY, helloChild, model);
751:                             byte[] wire=wire(this,first,child,model,p,mode);
752:                             e.getOutputStream().write(mode.equals("idle")?PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: "+(wire.length+1)+"\r\n\r\n"),wire):response(wire, MIME));
753:                             e.getOutputStream().flush();
754:                             try {
755:                                 e.getInputStream().read();
756:                             }
757:                             catch (SocketException expected) {
758:                             }
759:                         }
760:                         finally {
761:                             gate.afterCompletion(request, response, handler, null);
762:                             RequestContextHolder.setRequestAttributes(previousRequest);
763:                             SecurityContextHolder.setContext(previousSecurity);
764:                         }
765:                     }
766:                 }
767:                 catch (SocketException expected) {
768:                     if (engineResponse == null&&!mode.equals("scope")) failure = expected;
769:                 }
770:                 catch (Throwable t) {
771:                     failure = t;
772:                 }
773:             });
774:         }
775:         byte[] packet(int kind, byte[] m, byte[] p, String child, JsonNode model) throws Exception {
776:             var h = JSON.createObjectNode();
777:             h.put("childResponseId", child);
778:             h.put("runId", f.run);
779:             h.put("resourceSha256", f.accepted);
780:             h.put("initialCursor", 0);
781:             h.put("afterSeq", model.has("window")?model.get("window").get("afterSeq").asLong():0);
782:             h.put("payloadBytes", p.length);
783:             h.put("metadataSha256", sha(m));
784:             h.put("payloadSha256", sha(p));
785:             if (kind == 2 && model.get("hasEvent").asBoolean()) {
786:                 h.set("eventSeq", model.get("eventIdentity").get("seq"));
787:                 h.set("eventType", model.get("eventIdentity").get("type"));
788:             }
789:             else {
790:                 h.putNull("eventSeq");
791:                 h.putNull("eventType");
792:             }
793:             return PortalEventsPacketDecoderTest.packet(kind, kind == 1 ? 0 : ++ordinal, m, p, JSON.writeValueAsString(h));
794:         }
795:         PortalEventsTls tls() throws Exception {
796:             return new PortalEventsTls(URI.create("https://"+HOST+":"+listener.getLocalPort()), new byte[] {
797:                 127, 0, 0, 1
798:             }, PortalRunEventsHttpTest.ca(run, "root"));
799:         }
800:         public void close() throws Exception {
801:             listener.close();
802:             if (socket != null) socket.close();
803:             worker.join(5000);
804:             assertFalse(worker.isAlive());
805:             if (engineResponse != null) engineResponse.close();
806:             if (failure != null) throw new AssertionError("receiver peer", failure);
807:         }
808:     }
809: 
810: }
