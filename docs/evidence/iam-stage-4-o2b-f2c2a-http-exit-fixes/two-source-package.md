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
88:         private int transportStops;
89:         private Thread operationThread;
90:         private PortalEventsTlsConnection handle;
91:         private Socket raw;
92:         private SSLSocket ssl;
93:         private byte[] sslId;
94:         private HttpGet request;
95:         private HttpClientContext context;
96:         private PoolingHttpClientConnectionManager manager;
97:         private CloseableHttpClient client;
98:         private EventsMetadata facts;
99:         private ManagedHttpClientConnection connection;
100:         private ScheduledThreadPoolExecutor timer;
101:         private ScheduledFuture<?> alarm;
102:         private final IdentityHashMap<ClassicHttpRequest, Session> guards = new IdentityHashMap<>(2);
103:         private volatile Runnable beforeOutputForTest;
104:         private volatile java.util.function.Consumer<String> transportCheckpointForTest;
105:         void transportCheckpointForTest(java.util.function.Consumer<String> hook) { transportCheckpointForTest=hook; }
106:         private volatile boolean identityMatched;
107:         private Session(PortalEventsTls tls, PortalEventsPacketBudget.Owner owner, PortalEventsPacketBudget.Reservation fee, long t0, GatewayEventsOpening opening, CurrentAuthorityClient authority, String originalPortalSource,PortalRunEventsSse.Handle lifecycleAnchor) {
108:             this.lifecycleAnchor=lifecycleAnchor;
109:             this.tls = tls;
110:             this.owner = owner;
111:             this.fee = fee;
112:             this.t0 = t0;
113:             this.opening = opening;
114:             this.authority = authority;
115:             this.originalPortalSource = originalPortalSource;
116:             openingDeadline = t0 + TimeUnit.SECONDS.toNanos(5);
117:             lifetimeDeadline = t0 + TimeUnit.SECONDS.toNanos(55);
118:             query = opening.queryAuthorization();
119:         }
120:         private void live() {
121:             if (privatePhase) { livePrivate(!bodyPhase); return; }
122:             synchronized (this) {
123:                 if (stopped || closed || System.nanoTime() - Math.min(openingDeadline, hopDeadline == 0 ? openingDeadline : hopDeadline) >= 0) throw unavailable();
124:             }
125:             if (opening.queryAuthorization() != query) throw unavailable();
126:             opening.publicRunId();
127:             query.requireVerified();
128:         }
129:         public EventsMetadata lookupMetadata() {
130:             synchronized (this) {
131:                 if (used || closing || closed) throw unavailable();
132:                 used = true;
133:                 active = true;
134:                 operationThread = Thread.currentThread();
135:                 hopDeadline = Math.min(openingDeadline, Math.min(lifetimeDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
136:             }
137:             try {
138:                 live();
139:                 build();
140:                 live();
141:                 String ticket = query.ticket().value();
142:                 ascii(ticket, 16384);
143:                 request = new HttpGet("https://" + tls.hostname + ":" + tls.port + "/internal/runs/" + opening.publicRunId() + "/observation-metadata");
144:                 request.setVersion(HttpVersion.HTTP_1_1);
145:                 request.setHeader("Authorization", "Bearer " + ticket);
146:                 request.setHeader("Accept", "application/json");
147:                 request.setHeader("Accept-Encoding", "identity");
148:                 request.setHeader("Connection", "close");
149:                 context = HttpClientContext.create();
150:                 facts = client.execute(request, context, response -> {
151:                     try {
152:                         live();
153:                         checkResponse(response);
154:                         var entity = response.getEntity();
155:                         if (entity == null) throw unavailable();
156:                         byte[] body = new byte[8193];
157:                         int count = 0;
158:                         var in = entity.getContent();
159:                         for (;;) {
160:                             live();
161:                             int n = in.read(body, count, body.length - count);
162:                             if (n < 0) break;
163:                             if (n == 0) throw unavailable();
164:                             count += n;
165:                             if (count > 8192) throw unavailable();
166:                         }
167:                         live();
168:                         byte[] bounded = Arrays.copyOf(body, count);
169:                         if (response.getCode() != 200) throw RunObservationMetadataClient.parseEventsFailure(opening, response.getCode(), bounded);
170:                         var parsed = RunObservationMetadataClient.parseEvents(opening, bounded);
171:                         live();
172:                         return parsed;
173:                     } finally {
174:                         stopTransport();
175:                     }
176:                     // Abort before Apache closes a body: never drain an unknown remainder.
177:                 });
178:                 if (!finishTransport(Duration.ofSeconds(2))) throw unavailable();
179:                 liveAfterHttp();
180:                 synchronized (this) {
181:                     if (stopped || closing || closed) throw unavailable();
182:                     qReady = true;
183:                     return facts;
184:                 }
185:             } catch (PlatformException failure) {
186:                 abort();
187:                 throw failure;
188:             } catch (Exception failure) {
189:                 abort();
190:                 throw unavailable();
191:             } finally {
192:                 stopTransport();
193:                 boolean finished;
194:                 try {
195:                     finished = finishTransport(Duration.ofSeconds(2));
196:                 } finally {
197:                     synchronized (this) {
198:                         active = false;
199:                         operationThread = null;
200:                         notifyAll();
201:                     }
202:                 }
203:                 if (!finished) throw unavailable();
204:             }
205:         }
206:         @FunctionalInterface interface WindowConsumer { void accept(PortalEventsMaterialVerifier.VerifiedWindow window) throws IOException; }
207:         private String materialChild;
208:         private volatile java.util.function.BiConsumer<String,Integer> materialCheckpointForTest;
209:         void materialCheckpointForTest(java.util.function.BiConsumer<String,Integer> hook) { materialCheckpointForTest = hook; }
210:         void consumeVerified(WindowConsumer consumer) {
211:             Objects.requireNonNull(consumer);
212:             consumePrivate(packet -> {
213:                 // F1's scan fee has actually exited. Keep this fee through parser/borrow/consumer exit.
214:                 try (var materialFee = owner.reserve(PortalEventsMaterialVerifier.MATERIAL_BYTES)) {
215:                     var context = new MaterialContext(packet);
216:                     try { new PortalEventsMaterialVerifier(context).consume(consumer); }
217:                     finally { context.valid = false; }
218:                 }
219:             });
220:         }
221:         /** Only this Session can construct a binding. No caller may combine foreign facts/G/Owner. */
222:         final class MaterialContext {
223:             private final PortalEventsPacketDecoder.UntrustedPacket packet;
224:             private volatile boolean valid = true;
225:             private MaterialContext(PortalEventsPacketDecoder.UntrustedPacket packet) { this.packet = packet; }
226:             void live() { if (!valid) throw unavailable(); Session.this.live(); packet.header(); }
227:             PortalEventsPacketDecoder.UntrustedPacket packet() { live(); return packet; }
228:             GatewayPortalEventsResponse.Capture capture() { live(); return current(); }
229:             void requireLive(GatewayPortalEventsResponse.Capture capture) { live(); gatewayResponse.requireLive(capture); }
230:             boolean superseded(GatewayPortalEventsResponse.Capture capture) { live(); return gatewayResponse.isSuperseded(capture); }
231:             void require(GatewayPortalEventsResponse.Capture capture, MaterialScopeV1 scope) { requireLive(capture); capture.requireMaterial(scope); }
232:             void checkpoint(String phase, int index) {
233:                 live(); var hook = materialCheckpointForTest; if (hook != null) hook.accept(phase, index); live();
234:             }
235:             void acceptReceipt(String signed) { live(); gatewayResponse.acceptChildReceipt(signed); live(); }
236:             void bindChild(String childId) { live(); if (materialChild != null) throw unavailable(); materialChild = childId; }
237:             String childId() { live(); if (materialChild == null) throw unavailable(); return materialChild; }
238:             String run() { live(); return saved.run(); }
239:             SubjectKey subject() { live(); return saved.subject(); }
240:             ResourceBinding root() { live(); return saved.root(); }
241:             ResourceBinding snapshot() { live(); return saved.snapshot(); }
242:             long versionId() { live(); return saved.versionId(); }
243:             long versionNo() { live(); return saved.versionNo(); }
244:             String accepted() { live(); return saved.accepted(); }
245:             String initial() { live(); return saved.initial(); }
246:             long initialCursor() { live(); return saved.cursor(); }
247:             long cursor() { live(); synchronized(Session.this) { return sseLease==null?saved.cursor():publicAfter; } }
248:             void confirm(GatewayPortalEventsResponse.Capture capture,long seq) {
249:                 requireLive(capture);
250:                 if(superseded(capture))throw unavailable();
251:                 synchronized(Session.this) {
252:                     if(sseLease==null||sseLease.released||seq<=publicAfter)throw unavailable();
253:                     publicAfter=seq;
254:                 }
255:             }
256:         }
257:         private SseLease sseLease;
258:         private long publicAfter;
259:         /** Session-minted resource lease. No G/Owner/proof escapes, and no caller supplied cursor. */
260:         synchronized SseLease beginSse() {
261:             if(sseLease!=null||privateUsed||stopped||closing||closed||!qReady)throw unavailable();
262:             long cursor=opening.effectiveCursor(); // Validate before publishing a lease that needs a State exit callback.
263:             var charge=owner.reserve(1909574L); // all C2 allowances incl one128B reservation token =1909702.
264:             try { sseLease=new SseLease(charge); publicAfter=cursor; return sseLease; }
265:             catch(RuntimeException|Error failure) { charge.close(); throw failure; }
266:         }
267:         final class SseLease {
268:             private PortalEventsPacketBudget.Reservation charge;
269:             private boolean released;
270:             private Runnable cancellation;
271:             private java.util.function.Function<Duration,Boolean> exit;
272:             private SseLease(PortalEventsPacketBudget.Reservation charge) { this.charge=charge; }
273:             long deadline() { return lifetimeDeadline; }
274:             synchronized void cancellation(Runnable signal) { if(cancellation!=null||released)throw unavailable();cancellation=Objects.requireNonNull(signal); }
275:             synchronized void exits(java.util.function.Function<Duration,Boolean> signal) { if(exit!=null||released)throw unavailable();exit=Objects.requireNonNull(signal); }
276:             void signal() { Runnable callback;synchronized(this) { callback=cancellation; }if(callback!=null)callback.run(); }
277:             void reap(Duration timeout) { java.util.function.Function<Duration,Boolean> callback;synchronized(this) { callback=exit; }if(callback!=null)callback.apply(timeout); }
278:             void release() {
279:                 synchronized(this) { cancellation=null;exit=null; }
280:                 synchronized(Session.this) { if(!released) { charge.close();charge=null;released=true;Session.this.notifyAll(); } }
281:             }
282:         }
283:         @FunctionalInterface interface PacketConsumer { void accept(PortalEventsPacketDecoder.UntrustedPacket packet) throws IOException; }
284:         void consumePrivate(PacketConsumer consumer) {
285:             Objects.requireNonNull(consumer);
286:             synchronized (this) {
287:                 if (!qReady || active || privateUsed || stopped || closing || closed || !transportClean) throw unavailable();
288:                 privateUsed = true;
289:                 active = true;
290:                 operationThread = Thread.currentThread();
291:             }
292:             try {
293:                 // Lazy admission: Q-only owners retain the original 3211392B price.
294:                 privateFee = owner.reserve(PRIVATE_BYTES);
295:                 if (!(authority instanceof ResponseAuthorityClient responseAuthority)) throw unavailable();
296:                 liveOpening();
297:                 saved = new SavedFacts(facts.runId(), facts.owner(), facts.root(), facts.appVersionId(), facts.appVersionNo(),
298:                         facts.acceptedSnapshotSha256(), facts.initialCeilingSha256(), facts.snapshot(), opening.effectiveCursor());
299:                 liveOpening();
300:                 facts.close();
301:                 facts = null;
302:                 var g = responseAuthority.openGatewayPortalEvents(opening, saved.snapshot(), this::abort);
303:                 synchronized (this) { gatewayResponse = g; }
304:                 // Transfer invalidates Opening/Q getters; from here only the same G is consulted.
305:                 if (System.nanoTime() - openingDeadline >= 0) throw unavailable();
306:                 privatePhase = true;
307:                 opening.close();
308:                 opening = null;
309:                 query = null;
310:                 current();
311:                 try {
312:                     var issued = authority.exchangePortalEventsChild(originalPortalSource, g);
313:                     synchronized (this) { child = issued; }
314:                 } finally { originalPortalSource = null; }
315:                 synchronized (this) {
316:                     if (stopped || closing || closed) throw unavailable();
317:                     hopDeadline = Math.min(lifetimeDeadline, Math.min(System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
318:                             System.nanoTime() + current().remainingNanos()));
319:                     transportClean = false;
320:                 }
321:                 live();
322:                 build();
323:                 live();
324:                 String ticket = child.ticket().value();
325:                 ascii(ticket, 16384);
326:                 request = new HttpGet("https://" + tls.hostname + ":" + tls.port + "/admin/runs/" + saved.run()
327:                         + "/events?Last-Event-ID=" + Long.toString(saved.cursor()));
328:                 request.setVersion(HttpVersion.HTTP_1_1);
329:                 request.setHeader("Authorization", "Bearer " + ticket);
330:                 request.setHeader("Accept", "application/vnd.agent-platform.portal-events.v1");
331:                 request.setHeader("Accept-Encoding", "identity");
332:                 request.setHeader("Connection", "close");
333:                 context = HttpClientContext.create();
334:                 client.execute(request, context, response -> {
335:                     try {
336:                         live();
337:                         checkResponse(response, true);
338:                         var entity = response.getEntity();
339:                         if (entity == null) throw unavailable();
340:                         enterBody();
341:                         if (response.getCode() != 200) {
342:                             byte[] body = new byte[8193];
343:                             int count = 0;
344:                             var in = entity.getContent();
345:                             for (;;) {
346:                                 live();
347:                                 int n = in.read(body, count, body.length - count);
348:                                 live();
349:                                 if (n < 0) break;
350:                                 if (n == 0 || (count += n) > 8192) throw unavailable();
351:                             }
352:                             live();
353:                             String code = RunObservationMetadataClient.parsePrivateEventsErrorCode(Arrays.copyOf(body, count));
354:                             live();
355:                             int status = response.getCode();
356:                             if (!(status == 400 && code.equals("EVENT_CURSOR") || status == 404 && code.equals("RUN_NOT_FOUND")
357:                                     || status == 429 && code.equals("STREAM_CAPACITY") || status == 413 && Set.of("EVENT_LIMIT", "BUFFER_LIMIT").contains(code)
358:                                     || status == 403 && Set.of("AUTHORITY_EXPIRED", "AUTHORITY_CONTEXT_MISSING", "RUN_AUTHORITY_DENIED", "MATERIAL_SCOPE_MISSING_OR_CHANGED", "SYS-AUTH-403", "AUTHORITY_DENIED").contains(code))) throw unavailable();
359:                             throw new PlatformException(status, code, "Private events request rejected");
360:                         }
361:                         privateInput = new FilterInputStream(entity.getContent()) {
362:                             @Override public int read() throws IOException { live(); int n = in.read(); live(); return n; }
363:                             @Override public int read(byte[] b, int off, int len) throws IOException { live(); int n = in.read(b, off, len); live(); return n; }
364:                             @Override public void close() { stopTransport(); }
365:                         };
366:                         decoder = new PortalEventsPacketDecoder(owner);
367:                         for (;;) {
368:                             live();
369:                             var next = decoder.readNext(privateInput);
370:                             if (next.isEmpty()) { live(); break; }
371:                             try (var packet = next.orElseThrow()) {
372:                                 live();
373:                                 consumer.accept(packet);
374:                                 live();
375:                             }
376:                         }
377:                         return null;
378:                     } finally { stopTransport(); }
379:                 });
380:             } catch (PlatformException failure) {
381:                 abort(); throw failure;
382:             } catch (Exception failure) {
383:                 abort(); throw unavailable();
384:             } finally {
385:                 originalPortalSource = null;
386:                 stopTransport();
387:                 try { finishTransport(Duration.ofSeconds(2)); }
388:                 finally {
389:                     boolean requestedClose;
390:                     synchronized (this) { active = false; operationThread = null; requestedClose = closing; notifyAll(); }
391:                     // A cancelled transfer must close a late G even when its caller already exhausted the close wait.
392:                     if (requestedClose && sseLease==null) awaitClosed(Duration.ofSeconds(2));
393:                 }
394:             }
395:         }
396:         private void liveOpening() {
397:             synchronized (this) { if (stopped || closing || closed || System.nanoTime() - openingDeadline >= 0) throw unavailable(); }
398:             if (opening.queryAuthorization() != query) throw unavailable();
399:             opening.publicRunId(); query.requireVerified();
400:         }
401:         private GatewayPortalEventsResponse.Capture current() {
402:             synchronized (this) { if (stopped || closing || closed || System.nanoTime() - lifetimeDeadline >= 0 || gatewayResponse == null) throw unavailable(); }
403:             var capture = gatewayResponse.currentCapture();
404:             if (!saved.subject().equals(capture.subject()) || !saved.snapshot().equals(capture.resource())
405:                     || saved.cursor() != capture.initialCursor()) throw unavailable();
406:             gatewayResponse.requireLive(capture);
407:             if (gatewayResponse.isSuperseded(capture)) throw unavailable();
408:             return capture;
409:         }
410:         private void livePrivate(boolean head) {
411:             current();
412:             if (head) {
413:                 if (hopDeadline != 0 && System.nanoTime() - hopDeadline >= 0 || child == null) throw unavailable();
414:                 child.requireVerified();
415:             }
416:         }
417:         private void enterBody() {
418:             livePrivate(true);
419:             synchronized (this) {
420:                 if (stopped || closing || closed || bodyPhase) throw unavailable();
421:                 bodyPhase = true;
422:                 arm(lifetimeDeadline);
423:             }
424:             live();
425:         }
426:         private void arm(long deadline) {
427:             long epoch = ++alarmEpoch;
428:             if (alarm != null) alarm.cancel(false);
429:             alarm = timer.schedule(() -> {
430:                 synchronized (Session.this) {
431:                     if (epoch != alarmEpoch || stopped || closed) return;
432:                 }
433:                 abort();
434:             }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
435:         }
436:         private void liveAfterHttp() {
437:             if (System.nanoTime() - hopDeadline >= 0) throw unavailable();
438:             // Successful Q close deliberately stops only the physical hop, not its retained Opening.
439:             if (opening.queryAuthorization() != query) throw unavailable();
440:             opening.publicRunId();
441:             query.requireVerified();
442:         }
443:         private void build() {
444:             // One timer task for one absolute hop; no per-read scheduling or application worker queue.
445:             timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-http-deadline").factory());
446:             timer.setRemoveOnCancelPolicy(true);
447:             timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
448:             timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
449:             arm(hopDeadline);
450:             var builder = new PoolingHttpClientConnectionManagerBuilder() {
451:                 @Override protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ignored, DnsResolver ignoredDns, TlsSocketStrategy ignoredTls) {
452:                     var dns = new DnsResolver() {
453:                         public InetAddress[] resolve(String h) throws UnknownHostException {
454:                             if (!tls.hostname.equals(h)) throw new UnknownHostException("Fixed TLS host required");
455:                             return new InetAddress[] {
456:                                 tls.address
457:                             };
458:                         }
459:                         public String resolveCanonicalHostname(String h) throws UnknownHostException {
460:                             resolve(h);
461:                             return tls.hostname;
462:                         }
463:                     };
464:                     return new DefaultHttpClientConnectionOperator(proxy -> {
465:                         if (proxy != null) throw new IOException("Proxy prohibited");
466:                         live();
467:                         // E's TLS hard anchor is the original lifetime. The phase timer owns the shorter head
468:                         // bound; freezing checked_until/head into this handle would kill healthy renewed G bodies.
469:                         var acquired = tls.acquire(privatePhase ? lifetimeDeadline : Math.min(lifetimeDeadline, hopDeadline));
470:                         if (!(acquired instanceof PortalEventsTlsConnection c) || !c.belongsTo(tls)) {
471:                             acquired.abort();
472:                             throw new IOException("Foreign TLS handle");
473:                         }
474:                         synchronized (Session.this) {
475:                             if (stopped || handle != null) {
476:                                 c.abort();
477:                                 throw new IOException("TLS installation stopped");
478:                             }
479:                             handle = c;
480:                         }
481:                         try {
482:                             var socket = c.rawSocketForConnect();
483:                             synchronized (Session.this) {
484:                                 if (stopped) {
485:                                     c.abort();
486:                                     throw new IOException("TLS installation stopped");
487:                                 }
488:                                 raw = socket;
489:                             }
490:                             return socket;
491:                         } catch (RuntimeException | IOException e) {
492:                             c.abort();
493:                             throw e;
494:                         }
495:                     }, endpoint -> {
496:                         if (!"https".equals(endpoint.getSchemeName()) || !tls.hostname.equals(endpoint.getHostName()) || endpoint.getPort() != tls.port) throw new IllegalArgumentException("Fixed HTTPS route required");
497:                         return tls.port;
498:                     }, dns, scheme -> "https".equals(scheme) ?(socket, host, port, attachment, ctx) -> {
499:                         if (socket != raw || !tls.hostname.equals(host) || port != tls.port || ctx != context) throw new IOException("TLS upgrade identity rejected");
500:                         live();
501:                         var verified = handle.upgradeConnectedSocket();
502:                         live();
503:                         synchronized (Session.this) {
504:                             if (stopped) {
505:                                 handle.abort();
506:                                 throw new IOException("TLS upgrade stopped");
507:                             }
508:                             ssl = verified;
509:                             sslId = verified.getSession().getId();
510:                         }
511:                         return verified;
512:                     }
513:                     : null);
514:                 }
515:             };
516:             manager = builder.setConnectionFactory(attachment -> new QuietConnection(this)).setMaxConnTotal(1).setMaxConnPerRoute(1).setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(2000)).setSoLinger(TimeValue.NEG_ONE_MILLISECOND).build()).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(1000)).setSocketTimeout(Timeout.ofMilliseconds(2000)).setValidateAfterInactivity(TimeValue.NEG_ONE_MILLISECOND).build()).build();
517:             client = HttpClients.custom().setConnectionManager(manager).setConnectionReuseStrategy((r, s, c) -> false).setRoutePlanner((host, ctx) -> {
518:                 if (ctx != context || !"https".equals(host.getSchemeName()) || !tls.hostname.equals(host.getHostName()) || host.getPort() != tls.port || host.getAddress() != null) throw new HttpException("Fixed route required");
519:                 return new HttpRoute(host, null, true);
520:             }).disableAutomaticRetries().disableRedirectHandling().disableContentCompression().disableCookieManagement().disableAuthCaching().disableConnectionState().disableDefaultUserAgent().setDefaultCookieSpecRegistry(name -> null).setDefaultAuthSchemeRegistry(name -> null).setDefaultCredentialsProvider((scope, ctx) -> null).setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false).setProtocolUpgradeEnabled(false).setHardCancellationEnabled(true).setConnectionRequestTimeout(Timeout.ofMilliseconds(1)).setResponseTimeout(Timeout.ofMilliseconds(2000)).build()).setRequestExecutor(new HttpRequestExecutor() {
521:                 @Override public ClassicHttpResponse execute(ClassicHttpRequest actual, HttpClientConnection conn, HttpResponseInformationCallback callback, HttpContext ctx) throws IOException, HttpException {
522:                     check(actual, conn, ctx);
523:                     synchronized (guards) {
524:                         if (guards.containsKey(actual) || guards.size() >= 2) throw new IOException("Request identity capacity");
525:                         guards.put(actual, Session.this);
526:                     }
527:                     try {
528:                         return super.execute(actual, conn, callback, ctx);
529:                     } finally {
530:                         synchronized (guards) {
531:                             guards.remove(actual);
532:                         }
533:                     }
534:                 }
535:             }).build();
536:         }
537:         private void check(ClassicHttpRequest actual, HttpClientConnection conn, HttpContext ctx) throws IOException {
538:             live();
539:             if (ctx != context || !(conn instanceof QuietConnection connection) || connection.session != this || connection.getSocket() != ssl || ssl == null) throw new IOException("Request connection identity rejected");
540:             var session = ssl.getSession();
541:             if (!session.isValid() || !PortalEventsTls.PROTOCOL.equals(session.getProtocol()) || !PortalEventsTls.CIPHER.equals(session.getCipherSuite()) || !tls.hostname.equals(session.getPeerHost()) || session.getPeerPort() != tls.port || !Arrays.equals(sslId, session.getId())) throw new IOException("TLS session identity rejected");
542:             if (!"GET".equals(actual.getMethod()) || actual.getEntity() != null || !HttpVersion.HTTP_1_1.equals(actual.getVersion()) || !actual.getRequestUri().equals(privatePhase ? "/admin/runs/" + saved.run() + "/events?Last-Event-ID=" + Long.toString(saved.cursor()) : "/internal/runs/" + opening.publicRunId() + "/observation-metadata")) throw new IOException("Fixed request required");
543:             var headers = actual.getHeaders();
544:             if (headers.length > 16) throw new IOException("Request header count");
545:             var names = new HashSet<String>();
546:             for (var h : headers) {
547:                 String name = h.getName().toLowerCase(Locale.ROOT);
548:                 if (!names.add(name)) throw new IOException("Duplicate request header");
549:                 String expected = switch (name) {
550:                     case "authorization" -> "Bearer " + (privatePhase ? child.ticket().value() : query.ticket().value());
551:                     case "accept" -> privatePhase ? "application/vnd.agent-platform.portal-events.v1" : "application/json";
552:                     case "accept-encoding" -> "identity";
553:                     case "host" -> tls.hostname + ":" + tls.port;
554:                     case "connection" -> "close";
555:                     default -> throw new IOException("Unexpected request header");
556:                 };
557:                 if (!expected.equals(h.getValue())) throw new IOException("Fixed request header required");
558:             }
559:             if (!names.equals(Set.of("authorization", "accept", "accept-encoding", "host", "connection"))) throw new IOException("Missing request header");
560:             requestOctets(actual);
561:             live();
562:         }
563:         private void writerCheck(ClassicHttpRequest actual, QuietConnection conn) throws IOException {
564:             synchronized (guards) {
565:                 if (guards.get(actual) != this) throw new IOException("Missing original request identity");
566:             }
567:             check(actual, conn, context);
568:             identityMatched = true;
569:         }
570:         private void stopTransport() {
571:             synchronized (this) {
572:                 // An elected cleanup already owns the abort wakeup. Other admitted stops
573:                 // remain concurrent so an earlier stalled canceller cannot strand real IO.
574:                 if (transportClean || cleanupActive || transportStops == Integer.MAX_VALUE) return;
575:                 transportStops++;
576:             }
577:             stopTransportReferences();
578:             synchronized (this) { transportStops--; notifyAll(); }
579:         }
580:         private boolean stopTransportReferences() {
581:             try {
582:                 PortalEventsTlsConnection c;
583:                 HttpGet r;
584:                 synchronized (this) { c=handle; r=request; }
585:                 var hook=transportCheckpointForTest;
586:                 if(hook!=null&&c!=null&&r!=null)hook.accept("stop-snapshot");
587:                 if(c!=null)c.abort();
588:                 if(r!=null)r.cancel();
589:                 return true;
590:             } catch (RuntimeException | Error failure) {
591:                 // This narrow boundary fails closed: no Throwable crosses the actual
592:                 // heavy-helper return. Original transport fields remain for cleanup retry.
593:                 return false;
594:             }
595:         }
596:         public void abort() {
597:             SseLease owned;
598:             synchronized (this) {
599:                 stopped = true;
600:                 owned=sseLease;
601:             }
602:             if(owned!=null)owned.signal();
603:             stopTransport();
604:         }
605:         private boolean finishTransport(Duration timeout) {
606:             long deadline = System.nanoTime() + timeout.toNanos();
607:             synchronized (this) {
608:                 while (cleanupActive || transportStops != 0) {
609:                     long left = deadline - System.nanoTime();
610:                     if (left <= 0) return false;
611:                     try {
612:                         TimeUnit.NANOSECONDS.timedWait(this, left);
613:                     } catch (InterruptedException e) {
614:                         Thread.currentThread().interrupt();
615:                         return false;
616:                     }
617:                 }
618:                 if (active && operationThread != Thread.currentThread() || transportClean) return transportClean;
619:                 // Admission and election share one lock; no new stop snapshot can enter.
620:                 cleanupActive = true;
621:             }
622:             boolean cleaned=finishTransportReferences(deadline);
623:             // Only Session/primitive locals survive this actual inner return. Publishing
624:             // clean in the heavy helper's finally would still expose its reference frame.
625:             synchronized (this) {
626:                 if(cleaned)transportClean=true;
627:                 cleanupActive=false;
628:                 notifyAll();
629:                 return cleaned;
630:             }
631:         }
632:         private boolean finishTransportReferences(long deadline) {
633:             try {
634:                 PortalEventsTlsConnection closingHandle;
635:                 HttpGet closingRequest;
636:                 ScheduledFuture<?> closingAlarm;
637:                 ScheduledThreadPoolExecutor closingTimer;
638:                 CloseableHttpClient closingClient;
639:                 PoolingHttpClientConnectionManager closingManager;
640:                 java.util.function.Consumer<String> hook;
641:                 synchronized (this) {
642:                     closingHandle=handle; closingRequest=request; closingAlarm=alarm;
643:                     closingTimer=timer; closingClient=client; closingManager=manager;
644:                     hook=transportCheckpointForTest;
645:                 }
646:                 if(hook!=null)hook.accept("cleanup-snapshot");
647:                 if (closingHandle != null) closingHandle.abort();
648:                 if (closingRequest != null) closingRequest.cancel();
649:                 if (closingAlarm != null) closingAlarm.cancel(false);
650:                 if (closingTimer != null) closingTimer.shutdown();
651:                 // Physical waits and library close must leave both Session and identity locks available to abort.
652:                 if (closingHandle != null && !closingHandle.awaitClosed(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) return false;
653:                 if (closingTimer != null && !closingTimer.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
654:                 if (closingClient != null) closingClient.close(CloseMode.IMMEDIATE);
655:                 else if (closingManager != null) closingManager.close(CloseMode.IMMEDIATE);
656:                 if(hook!=null)hook.accept("cleanup-tail");
657:                 synchronized (this) {
658:                     client = null;
659:                     manager = null;
660:                     connection = null;
661:                     request = null;
662:                     context = null;
663:                     ssl = null;
664:                     sslId = null;
665:                     raw = null;
666:                     handle = null;
667:                     alarm = null;
668:                     timer = null;
669:                     beforeOutputForTest = null;
670:                     transportCheckpointForTest = null;
671:                     synchronized (guards) {
672:                         guards.clear();
673:                     }
674:                     return true;
675:                 }
676:             } catch (InterruptedException e) {
677:                 Thread.currentThread().interrupt();
678:                 return false;
679:             } catch (RuntimeException | Error failure) {
680:                 // Includes test probes and close failures: retain original fields, keep
681:                 // transport dirty, and allow a later bounded retry without a heavy Throwable.
682:                 return false;
683:             }
684:         }
685:         public boolean awaitClosed(Duration timeout) {
686:             if (timeout.isNegative()) throw new IllegalArgumentException("Negative close wait");
687:             long deadline = System.nanoTime() + timeout.toNanos();
688:             SseLease owned;
689:             synchronized(this) { if(closed)return true;if(!closing)return false;owned=sseLease; }
690:             // A failed finite onComplete wait may be retried by the original Session's owner.
691:             // This call holds no Session monitor and cannot bypass the heavy/worker/completion gate.
692:             if(owned!=null&&!owned.released)owned.reap(Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
693:             synchronized (this) {
694:                 if (closed) return true;
695:                 if (!closing) return false;
696:                 while (active || sseLease!=null&&!sseLease.released) {
697:                     long left = deadline - System.nanoTime();
698:                     if (left <= 0) return false;
699:                     try {
700:                         TimeUnit.NANOSECONDS.timedWait(this, left);
701:                     } catch (InterruptedException e) {
702:                         Thread.currentThread().interrupt();
703:                         return false;
704:                     }
705:                 }
706:             }
707:             if (!finishTransport(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) return false;
708:             synchronized (this) {
709:                 if (closed) return true;
710:                 // Refuse release if a callback retained a segment or decoder work remains live.
711:                 try { if (decoder != null) decoder.close(); } catch (RuntimeException stillBorrowed) { return false; }
712:                 decoder = null;
713:                 privateInput = null;
714:                 if (gatewayResponse != null) gatewayResponse.close();
715:                 gatewayResponse = null;
716:                 child = null;
717:                 saved = null;
718:                 materialChild = null;
719:                 materialCheckpointForTest = null;
720:                 authority = null;
721:                 originalPortalSource = null;
722:                 if (opening != null) opening.close();
723:                 opening = null;
724:                 query = null;
725:                 if (facts != null) facts.close();
726:                 facts = null;
727:                 if (privateFee != null) privateFee.close();
728:                 privateFee = null;
729:                 fee.close();
730:                 fee = null;
731:                 owner.close();
732:                 closed = true;
733:                 notifyAll();
734:                 return true;
735:             }
736:         }
737:         @Override public void close() {
738:             synchronized (this) {
739:                 closing = true;
740:             }
741:             abort();
742:             awaitClosed(Duration.ofSeconds(2));
743:         }
744:         // Mechanical blocking only; cannot install a different ticket, socket, URL, or proof.
745:         void beforeOutputForTest(Runnable hook) {
746:             beforeOutputForTest = hook;
747:         }
748:     }
749:     static int requestOctets(ClassicHttpRequest r) throws IOException {
750:         int bytes = ascii(r.getMethod(), 16) + 1 + ascii(r.getRequestUri(), 512) + 1 + ascii(r.getVersion().toString(), 16) + 2;
751:         for (var h : r.getHeaders()) bytes = Math.addExact(bytes, ascii(h.getName(), 32768) + 2 + ascii(h.getValue(), 32768) + 2);
752:         bytes = Math.addExact(bytes, 2);
753:         if (bytes > 32768) throw new IOException("Request head limit");
754:         return bytes;
755:     }
756:     private static int ascii(String value, int limit) throws IOException {
757:         if (value == null || value.length() > limit) throw new IOException("ASCII field limit");
758:         for (int i = 0; i < value.length(); i++) {
759:             char c = value.charAt(i);
760:             if (c < 32 || c > 126) throw new IOException("ASCII field rejected");
761:         }
762:         return value.length();
763:     }
764:     private static void checkResponse(ClassicHttpResponse response) throws IOException { checkResponse(response, false); }
765:     private static void checkResponse(ClassicHttpResponse response, boolean events) throws IOException {
766:         if (!HttpVersion.HTTP_1_1.equals(response.getVersion()) || !(events ? Set.of(200, 400, 403, 404, 413, 429) : Set.of(200, 403, 404)).contains(response.getCode())) throw new IOException("Response status rejected");
767:         var counts = new HashMap<String, Integer>();
768:         String cl = null, te = null, type = null;
769:         for (var h : response.getHeaders()) {
770:             String name = h.getName().toLowerCase(Locale.ROOT);
771:             int n = counts.merge(name, 1, Integer::sum);
772:             if (Set.of("content-encoding", "set-cookie", "location", "trailer", "www-authenticate", "proxy-authenticate").contains(name)) throw new IOException("Response header rejected");
773:             if (Set.of("content-length", "transfer-encoding", "content-type").contains(name) && n != 1) throw new IOException("Duplicate response framing");
774:             if (name.equals("content-length")) cl = h.getValue();
775:             if (name.equals("transfer-encoding")) te = h.getValue();
776:             if (name.equals("content-type")) type = h.getValue();
777:         }
778:         if (type == null || !(events && response.getCode() == 200 ? type.equals("application/vnd.agent-platform.portal-events.v1") : type.matches("(?i)application/json(?:;[ ]*charset=UTF-8)?")) || cl != null && te != null || cl == null && te == null) throw new IOException("Response framing rejected");
779:         if (cl != null) {
780:             if (!cl.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Content length rejected");
781:             try {
782:                 if (Long.parseLong(cl) > 8192 && !(events && response.getCode() == 200)) throw new IOException("Body limit");
783:             } catch (NumberFormatException e) {
784:                 throw new IOException("Content length rejected");
785:             }
786:         }
787:         if (te != null && !te.equalsIgnoreCase("chunked")) throw new IOException("Transfer coding rejected");
788:     }
789:     /** Core5.3.4 standard parser/writer/holder, without DefaultManaged's header or wire hooks. */
790:     private static final class QuietConnection extends DefaultBHttpClientConnection implements ManagedHttpClientConnection {
791:         final Session session;
792:         private boolean terminated;
793:         QuietConnection(Session session) {
794:             super(Http1Config.custom().setBufferSize(8192).setMaxLineLength(8192).setMaxHeaderCount(1).setMaxEmptyLineCount(2).build(), null, null, null, null, () -> {
795:                 var delegate = DefaultHttpRequestWriterFactory.INSTANCE.create();
796:                 return (actual, buffer, out) -> {
797:                     session.writerCheck(actual, (QuietConnection) sessionConnection(session));
798:                     delegate.write(actual, buffer, out);
799:                 };
800:             }, config -> {
801:                 var delegate = new org.apache.hc.core5.http.impl.io.DefaultHttpResponseParser(Http1Config.custom().setMaxLineLength(8192).setMaxHeaderCount(64).setMaxEmptyLineCount(2).build());
802:                 return (buffer, in) -> {
803:                     try {
804:                         var response = delegate.parse(new HeadBuffer(buffer), in);
805:                         checkResponse(response, session.privatePhase);
806:                         return response;
807:                     } catch (IOException | HttpException | RuntimeException e) {
808:                         session.stopTransport();
809:                         throw e;
810:                     }
811:                 };
812:             });
813:             this.session = session;
814:         }
815:         // The unique connection is set by bind before any executor/writer invocation.
816:         private static ManagedHttpClientConnection sessionConnection(Session session) {
817:             return session.connection;
818:         }
819:         @Override public void bind(Socket socket) throws IOException {
820:             if (terminated) throw new IOException("Connection exited");
821:             super.bind(socket);
822:             session.connection = this;
823:         }
824:         @Override public void bind(SSLSocket ssl, Socket raw) throws IOException {
825:             if (terminated) throw new IOException("Connection exited");
826:             super.bind(new SocketHolder(ssl, raw) {
827:                 @Override protected OutputStream getOutputStream(Socket socket) throws IOException {
828:                     Runnable hook = session.beforeOutputForTest;
829:                     if (hook != null) hook.run();
830:                     return super.getOutputStream(socket);
831:                 }
832:             });
833:             session.connection = this;
834:         }
835:         @Override public Socket getSocket() {
836:             var holder = getSocketHolder();
837:             return holder == null ? null : holder.getSocket();
838:         }
839:         @Override public void setSocketTimeout(Timeout timeout) {
840:             if (!(getSocket() instanceof SSLSocket)) super.setSocketTimeout(timeout);
841:             /* SafeSsl retains F2a15000ms; absolute hop timer aborts earlier. */
842:         }
843:         @Override public void activate() {
844:             if (terminated) throw new IllegalStateException("Connection exited");
845:         }
846:         @Override public void passivate() {
847:             session.stopTransport();
848:         }
849:         @Override public void close() {
850:             close(CloseMode.IMMEDIATE);
851:         }
852:         @Override public void close(CloseMode mode) {
853:             terminated = true;
854:             session.stopTransport();
855:             super.close(CloseMode.IMMEDIATE);
856:         }
857:     }
858:     /** Charge only parsed lines, including rejected line, before standard parser retains them. */
859:     private static final class HeadBuffer implements SessionInputBuffer {
860:         final SessionInputBuffer input;
861:         int total;
862:         HeadBuffer(SessionInputBuffer input) {
863:             this.input = input;
864:         }
865:         public int readLine(CharArrayBuffer line, InputStream stream) throws IOException {
866:             int start = line.length();
867:             int n = input.readLine(line, stream);
868:             if (n >= 0) {
869:                 int length = line.length() - start;
870:                 total = Math.addExact(total, length + 2);
871:                 if (total > 8192) throw new IOException("Response head limit");
872:                 if (length > 0 && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) throw new IOException("Folded response header");
873:                 for (int i = start; i < line.length(); i++) {
874:                     char c = line.charAt(i);
875:                     if (c < 32 || c == 127) throw new IOException("Response head control");
876:                 }
877:             }
878:             return n;
879:         }
880:         public int length() {
881:             return input.length();
882:         }
883:         public int capacity() {
884:             return input.capacity();
885:         }
886:         public int available() {
887:             return input.available();
888:         }
889:         public int read(byte[] b, int off, int len, InputStream in) throws IOException {
890:             return input.read(b, off, len, in);
891:         }
892:         public int read(byte[] b, InputStream in) throws IOException {
893:             return input.read(b, in);
894:         }
895:         public int read(InputStream in) throws IOException {
896:             return input.read(in);
897:         }
898:         public HttpTransportMetrics getMetrics() {
899:             return input.getMetrics();
900:         }
901:     }
902:     private static PlatformException unavailable() {
903:         return new PlatformException(503, "RUN_METADATA_UNAVAILABLE", "运行观察元数据暂不可用");
904:     }
905: }

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
51:     static Path runs() throws IOException { return Files.createDirectories(root().resolve(".local/iam-o2b-f2c2a-http-exit-fixes/peers")); }
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
100:     @Test void actualAbortSnapshotMustReturnBeforeHttpRefund()throws Exception { child("http-stop-exit"); }
101:     @Test void actualCleanupSnapshotMustReturnBeforeHttpRefund()throws Exception { child("http-cleanup-exit"); }
102:     @Test void retainedFinishedHandlesConsumeSharedFactoryCapacity()throws Exception { child("retained-cap"); }
103:     @Test void q403FailureClosesTransferredSessionBeforeReturningCapacity()throws Exception { child("fix-q403"); }
104:     @Test void qTransportFailureClosesTransferredSessionBeforeReturningCapacity()throws Exception { child("fix-qtransport"); }
105:     @Test void transferredServletCannotClaimTheOriginalRequestFromWorker()throws Exception { child("fix-offthread"); }
106:     @Test void originalRequestScopeAndDispatcherAreRequiredBeforeQuery()throws Exception { child("fix-dispatcher");child("fix-missing-token"); }
107:     @Test void uncertainProcessorConstructionBurnsItsReservedBundle()throws Exception { child("fix-burn"); }
108:     @Test void automaticPhantomNotificationCanReturnActualFactoryCapacity()throws Exception { child("fix-reuse"); }
109:     @Test void actualProcessorFactoryRefusesBeyondFixedStrongPopulation()throws Exception { child("fix-cap"); }
110:     public static void main(String[] args)throws Exception { scenario(args[0],Path.of(args[1])); }
111:     static byte[] wire(SequencePeer peer,byte[] hello,String child,ObjectNode original,byte[] payload,String mode)throws Exception {
112:         int count=mode.equals("backpressure")||mode.equals("pending-flush")?20:mode.equals("sequence")?2:1;
113:         var parts=new ArrayList<byte[]>();parts.add(hello);
114:         for(int i=1;i<=count;i++) {
115:             var m=original.deepCopy();
116:             ((ObjectNode)m.get("window")).put("afterSeq",i-1).put("watermark",count);
117:             ((ObjectNode)m.get("state")).put("event_seq",count);
118:             ((ObjectNode)m.get("eventIdentity")).put("seq",i);
119:             if(mode.equals("long-id")) { ((ObjectNode)m.get("eventIdentity")).put("seq",Long.MAX_VALUE);((ObjectNode)m.get("window")).put("watermark",Long.MAX_VALUE);((ObjectNode)m.get("state")).put("event_seq",Long.MAX_VALUE); }
120:             if(mode.equals("sequence")) {
121:                 ((ObjectNode)m.get("window")).put("status","SUCCEEDED");((ObjectNode)m.get("state")).put("status","SUCCEEDED");
122:             }
123:             if(mode.equals("reset")) { ((ObjectNode)m.get("window")).put("oldest",3).put("watermark",3);((ObjectNode)m.get("state")).put("event_seq",3); }
124:             ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));
125:             PortalRunEventsSseTest.payload(m,payload,mode.equals("sequence")?" "+"界".repeat(31):mode.equals("spaces")?" ".repeat(32):"EVENT");
126:             parts.add(peer.packet(2,JSON.writeValueAsBytes(m),payload,child,m));
127:         }
128:         if(mode.equals("sequence")) {
129:             var m=original.deepCopy();
130:             ((ObjectNode)m.get("window")).put("afterSeq",count).put("watermark",count).put("status","SUCCEEDED");
131:             ((ObjectNode)m.get("state")).put("event_seq",count).put("status","SUCCEEDED");
132:             m.put("hasEvent",false);m.putNull("eventScope");m.putNull("eventIdentity");
133:             ((ObjectNode)selected(m,"stateScope").get("material")).put("payloadSha256",PortalEventsMaterialVerifier.canonicalSha(JSON.writeValueAsBytes(m.get("state"))));rehash(m);
134:             parts.add(peer.packet(2,JSON.writeValueAsBytes(m),EMPTY,child,m));
135:         }
136:         return PortalEventsPacketDecoderTest.join(parts.toArray(byte[][]::new));
137:     }
138:     static final class Exchange {
139:         final AtomicReference<PortalRunEventsSse.Handle> handle=new AtomicReference<>();
140:         final AtomicReference<PortalRunEventsHttp.Session> session=new AtomicReference<>();
141:         final AtomicReference<Throwable> failure=new AtomicReference<>();
142:         final List<String> trace=Collections.synchronizedList(new ArrayList<>());
143:         PortalRunEventsSse.Handle handle()throws Exception {
144:             long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
145:             while(handle.get()==null&&System.nanoTime()<end) { if(failure.get()!=null)throw new AssertionError(failure.get());Thread.sleep(10); }
146:             assertNotNull(handle.get());return handle.get();
147:         }
148:     }
149:     static final class Server implements AutoCloseable {
150:         final Tomcat tomcat=new Tomcat();
151:         org.apache.catalina.Context context;
152:         final PortalRunEventsSse.ControlledProtocol protocol=new PortalRunEventsSse.ControlledProtocol();
153:         final List<Exchange> exchanges=new ArrayList<>();
154:         final List<PortalEventsTls> endpoints;
155:         final AtomicInteger next=new AtomicInteger();
156:         final Exchange first=new Exchange();
157:         final AtomicReference<PortalRunEventsSse.Handle> handle=first.handle;
158:         final AtomicReference<PortalRunEventsHttp.Session> session=first.session;
159:         final AtomicReference<Throwable> failure=first.failure;
160:         final List<String> trace=first.trace;
161:         final Path run;
162:         String invocation="normal";
163:         Server(Path run,Fixture f,PortalEventsTls tls,BiConsumer<String,Long> hook)throws Exception {
164:             this(run,f,List.of(tls),Collections.singletonList(hook));
165:         }
166:         Server(Path run,Fixture f,List<PortalEventsTls> endpoints,List<BiConsumer<String,Long>> hooks)throws Exception {
167:             this.run=run;this.endpoints=endpoints;exchanges.add(first);for(int i=1;i<endpoints.size();i++)exchanges.add(new Exchange());
168:             Path base=Files.createDirectories(run.resolve("tomcat"));tomcat.setBaseDir(base.toString());
169:             var connector=new org.apache.catalina.connector.Connector(protocol);connector.setPort(0);tomcat.setConnector(connector);
170:             connector.setProperty("address","127.0.0.1");connector.setProperty("socket.txBufSize","1024");connector.setProperty("socket.appWriteBufSize","8192");
171:             connector.setProperty("minSpareThreads","2");
172:             context=tomcat.addContext("",base.toString());context.getPipeline().addValve(PortalRunEventsSse.contextValve());
173:             var servlet=Tomcat.addServlet(context,"events",new HttpServlet() {
174:                 @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException {
175:                     int index=next.getAndIncrement();var exchange=exchanges.get(index);var hook=hooks.get(index);
176:                     final Object requestToken=request.getAttribute(PortalRunEventsSse.class.getName()+".request");
177:                     var adapter=new PortalRunEventsSse((phase,value)->{
178:                         exchange.trace.add(phase+"="+value);
179:                         try { Object token=requestToken;if(token!=null) { Object state=field(token,"state");if(state!=null)exchange.session.compareAndSet(null,(PortalRunEventsHttp.Session)field(state,"session")); } }
180:                         catch(Exception failure) { throw new AssertionError(failure); }
181:                         if(hook!=null)hook.accept(phase,value);
182:                     });
183:                     try {
184:                         if(invocation.equals("handle-burn-1"))protocol.failHandleConstructionForTest(1);
185:                         if(invocation.equals("handle-burn-2"))protocol.failHandleConstructionForTest(2);
186:                         Runnable call=()->{ try {
187:                             var h=adapter.start(request,response,new PortalRunEventsHttp(endpoints.get(index)),f.observed,f.source,f.run,0);
188:                             exchange.handle.set(h);Object state=field(h,"state");if(state!=null)exchange.session.set((PortalRunEventsHttp.Session)field(state,"session"));
189:                         } catch(Throwable t) { exchange.failure.set(t); } };
190:                         if(invocation.equals("offthread")) {
191:                             Thread other=Thread.ofPlatform().start(call);other.join(6000);if(other.isAlive()) { other.interrupt();other.join(6000); }assertFalse(other.isAlive(),"bounded off-thread probe actually exited");
192:                         } else if(invocation.equals("dispatcher"))request.getRequestDispatcher("/reentry").include(request,response);
193:                         else call.run(); }
194:                     catch(Throwable t) { exchange.failure.set(t); }
195:                 }
196:             });servlet.setAsyncSupported(true);context.addServletMappingDecoded("/admin/runs/*","events");
197:             var reentry=Tomcat.addServlet(context,"reentry",new HttpServlet() { @Override protected void doGet(HttpServletRequest request,HttpServletResponse response)throws IOException { var exchange=exchanges.get(next.get()-1);try { exchange.handle.set(new PortalRunEventsSse().start(request,response,new PortalRunEventsHttp(endpoints.get(next.get()-1)),f.observed,f.source,f.run,0)); }catch(Throwable t) { exchange.failure.set(t); } } });reentry.setAsyncSupported(true);context.addServletMappingDecoded("/reentry","reentry");
198:             Tomcat.addServlet(context,"ping",new HttpServlet() { @Override protected void doGet(HttpServletRequest r,HttpServletResponse out)throws IOException { out.setContentLength(4);out.getOutputStream().write(utf("pong")); } });context.addServletMappingDecoded("/ping","ping");
199:             tomcat.start();assertEquals(1024,protocol.getMaxConnections());assertEquals(64,protocol.getMaxThreads());assertEquals(64,protocol.getProcessorCache());
200:         }
201:         Socket request(String run)throws Exception { return request(run,false); }
202:         Socket request(String run,boolean keepAlive)throws Exception {
203:             var socket=new Socket();socket.setReceiveBufferSize(1024);socket.connect(new InetSocketAddress("127.0.0.1",tomcat.getConnector().getLocalPort()));socket.setSoTimeout(12000);
204:             socket.getOutputStream().write(utf("GET /admin/runs/"+run+"/events HTTP/1.1\r\nHost: localhost\r\nConnection: "+(keepAlive?"keep-alive":"close")+"\r\n\r\n"));socket.getOutputStream().flush();return socket;
205:         }
206:         boolean waitTrace(String prefix,long millis)throws Exception {
207:             long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
208:             while(System.nanoTime()<end) { synchronized(trace) { if(trace.stream().anyMatch(x->x.startsWith(prefix)))return true; }Thread.sleep(10); }return false;
209:         }
210:         PortalRunEventsSse.Handle handle()throws Exception { return first.handle(); }
211:         public void close()throws Exception {
212:             for(int i=0;i<exchanges.size();i++) {
213:                 var exchange=exchanges.get(i);Files.write(run.resolve("sse-trace-finally-"+i+".txt"),exchange.trace,StandardCharsets.UTF_8);
214:                 var h=exchange.handle.get();if(h!=null) { h.close();assertTrue(h.awaitClosed(Duration.ofSeconds(5))); }
215:             }
216:             for(var tls:endpoints)tls.close();tomcat.stop();tomcat.destroy();
217:             Files.writeString(run.resolve("protocol.properties"),"implementation="+protocol.getClass().getName()+"\ncreated="+protocol.creationsForTest()+"\nrecycled="+protocol.recyclesForTest()+"\nwaiting="+protocol.getWaitingProcessorCount()+"\nbaseline="+PortalEventsPacketBudget.SHARED.connectorBaselineBytes()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\nowners="+PortalEventsPacketBudget.SHARED.owners()+"\n");
218:         }
219:     }
220:     static void scenario(String mode,Path run)throws Exception {
221:         if(mode.equals("invalid-cursor")) { invalidCursor(run);return; }
222:         if(mode.equals("preasync")) { preasync(run);return; }
223:         if(mode.equals("handle-burn")) { handleBurn(run);return; }
224:         if(mode.equals("mixed-reference")) { mixedReference(run);return; }
225:         if(mode.equals("ancestor-tail")) { ancestorTail(run);return; }
226:         if(mode.startsWith("http-")) { httpHelperExit(mode,run);return; }
227:         if(mode.equals("retained-cap")) { retainedCap(run);return; }
228:         if(mode.startsWith("fix-q")) { startupFailure(mode,run);return; }
229:         if(mode.equals("fix-offthread")||mode.equals("fix-dispatcher")||mode.equals("fix-missing-token")) { startupScope(mode.substring(4),run);return; }
230:         if(mode.equals("fix-cap")||mode.equals("fix-burn")||mode.equals("fix-reuse")) { allocationCap(mode,run);return; }
231:         if(mode.startsWith("generation-")||mode.startsWith("freshness-")) { freshness(mode,run);return; }
232:         if(mode.startsWith("bootstrap-")) { bootstrap(mode.substring(10),run);return; }
233:         if(mode.startsWith("pending-flush-")) { pendingFlush(mode.substring(14),run);return; }
234:         switch(mode) {
235:             case "action-retention" -> { actionRetention(run);return; }
236:             case "processor-reuse" -> { processorReuse(run);return; }
237:             case "bad-profile" -> {
238:                 var protocol=new PortalRunEventsSse.ControlledProtocol();protocol.setMaxThreads(65);
239:                 assertThrows(IllegalStateException.class,protocol::start);assertThrows(IllegalStateException.class,PortalRunEventsSse.ControlledProtocol::new);
240:                 assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());return;
241:             }
242:             case "not-ready-idle" -> { notReadyIdle(run);return; }
243:             case "forced-release" -> { forcedRelease(run);return; }
244:             case "idle" -> { idle(run);return; }
245:             case "keepalive" -> { keepalive(run);return; }
246:             case "light-shell" -> { lightShell(run);return; }
247:             case "heavy-retention","completion-retention","abort-order" -> { retentionScenario(mode,run);return; }
248:         }
249:         byte[] raw=mode.startsWith("backpressure")?utf("\""+"x".repeat(262142)+"\""):mode.equals("precision")?utf(" { \"n\" : -0.00, \"value\": 1.23456789012345678901234567890e+20 } "):utf("null");
250:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},mode,raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
251:             if(mode.startsWith("backpressure")) {
252:                 assertTrue(server.waitTrace("ready-false",8000)||server.waitTrace("flush-wait",1000),"real stopped-reader readiness");
253:                 var handle=server.handle();long prior=handle.after();long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>83886080L);
254:                 if(mode.equals("backpressure-resume")) {
255:                     socket.setReceiveBufferSize(262144);byte[] body=socket.getInputStream().readAllBytes();assertTrue(body.length>262144);
256:                     assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(1,handle.after());assertTrue(traceCount(server.trace,"callback-entry")>1);
257:                 } else { handle.close();assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(prior,handle.after()); }
258:             } else {
259:                 byte[] body=socket.getInputStream().readAllBytes();var handle=server.handle();assertTrue(handle.awaitClosed(Duration.ofSeconds(4)));assertTrue(handle.detachedForTest());assertTrue(handle.inertTokenForTest());
260:                 String text=new String(body,StandardCharsets.UTF_8);
261:                 if(mode.equals("reset")) { assertTrue(text.contains("CURSOR_EXPIRED"));assertFalse(text.contains("stream_status"));assertFalse(text.contains("id: 1"));assertEquals(0,handle.after()); }
262:                 else { assertTrue(text.contains("event: stream_status"));
263:                 assertTrue(text.contains(mode.equals("precision")?"data: {\"n\":-0.00,\"value\":1.23456789012345678901234567890e+20}":"data: null"));
264:                 assertEquals(mode.equals("long-id")?Long.MAX_VALUE:mode.equals("sequence")?2:1,handle.after());assertEquals(mode.equals("sequence"),handle.terminal());
265:                 }
266:                 assertTrue(server.trace.contains("setter-return=0"));assertTrue(server.trace.contains("callback-entry=1"));
267:                 assertTrue(server.trace.indexOf("setter-return=0")<server.trace.indexOf("callback-entry=1"));
268:                 if(mode.equals("sequence")) { assertTrue(text.contains("id: 2"));assertTrue(text.contains("event:  界")); }
269:                 if(mode.equals("spaces"))assertTrue(text.contains("event: "+" ".repeat(32)+"\n"));
270:             }
271:             assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertNull(server.failure.get());
272:             Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
273:             Files.writeString(run.resolve("sse.properties"),"after="+server.handle().after()+"\nterminal="+server.handle().terminal()+"\nactualWorkerExit=true\nownerBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
274:         }
275:     }
276:     static Object field(Object value,String name)throws Exception {
277:         var f=(value instanceof Class<?> c?c:value.getClass()).getDeclaredField(name);f.setAccessible(true);return f.get(value instanceof Class<?>?null:value);
278:     }
279:     static void invalidCursor(Path run)throws Exception {
280:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"q-only",utf("null"));var tls=peer.tls()) {
281:             var opening=new AtomicReference<HttpCurrentAuthorityClient.GatewayEventsOpening>();
282:             var observed=(CurrentAuthorityClient)java.lang.reflect.Proxy.newProxyInstance(CurrentAuthorityClient.class.getClassLoader(),new Class<?>[]{CurrentAuthorityClient.class},(proxy,method,args)->{
283:                 try { Object value=method.invoke(f.observed,args);if(method.getName().equals("beginGatewayPortalEvents"))opening.set((HttpCurrentAuthorityClient.GatewayEventsOpening)value);return value; }
284:                 catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
285:             });
286:             var session=new PortalRunEventsHttp(tls).begin(observed,f.source,f.run,0);session.lookupMetadata();opening.get().close();
287:             assertThrows(RuntimeException.class,session::beginSse);session.close();boolean closed=session.awaitClosed(Duration.ofMillis(100));
288:             Files.writeString(run.resolve("invalid-cursor.properties"),"realQ=true\noriginalOpeningClosed=true\nbeginSseDenied=true\nactualClosed="+closed+"\nowners="+PortalEventsPacketBudget.SHARED.owners()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
289:             assertTrue(closed,"rejected original cursor must not publish an unreapable SSE lease");assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(0,PortalEventsPacketBudget.SHARED.reservedBytes());
290:         }
291:     }
292:     static void preasync(Path run)throws Exception {
293:         var serverRef=new AtomicReference<Server>();var detached=new AtomicBoolean();
294:         BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("unstarted-detached")) {
295:             assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>3145728);assertEquals(1,PortalEventsPacketBudget.SHARED.owners());detached.set(true);
296:         }};
297:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"q-only",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,hook)) {
298:             serverRef.set(server);((org.apache.catalina.Wrapper)server.context.findChild("events")).setAsyncSupported(false);
299:             try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
300:             assertInstanceOf(IllegalStateException.class,server.failure.get());assertTrue(detached.get());assertNull(server.handle.get());
301:             assertEquals(0,traceCount(server.trace,"window"));assertEquals(0,traceCount(server.trace,"setter-return"));assertEquals(0,traceCount(server.trace,"completion-entry"));
302:             assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(1,peer.connections);
303:             Files.writeString(run.resolve("preasync.properties"),"actualAsyncSupported=false\ndetachBarrierWhileOwnerAndLeaseCharged=true\nworker=0\nsetter=0\ncompletion=0\nfinalBaseline=3145728\n");
304:         }
305:     }
306:     static void handleBurn(Path run)throws Exception {
307:         try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"scope",utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"scope",utf("null"));var ta=a.tls();var tb=b.tls();var server=new Server(run,f,List.of(ta,tb),Arrays.asList(null,null))) {
308:             var held=new org.apache.coyote.Processor[1533];for(int i=0;i<1532;i++)held[i]=server.protocol.createProcessor();
309:             for(int i=0;i<2;i++) { server.invocation="handle-burn-"+(i+1);try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }assertInstanceOf(IllegalStateException.class,server.exchanges.get(i).failure.get()); }
310:             assertEquals(2,server.protocol.burnedForTest());assertEquals(2,server.protocol.handleAllocationsForTest());held[1532]=server.protocol.createProcessor();assertThrows(IllegalStateException.class,server.protocol::createProcessor);
311:             assertEquals(0,a.connections+b.connections);assertEquals(0,PortalEventsPacketBudget.SHARED.owners());java.lang.ref.Reference.reachabilityFence(held);
312:             Files.writeString(run.resolve("handle-burn.properties"),"constructorBurn=1\nregistrationBurn=1\nprocessors=1534\nsharedTotal=1536\nnewSession=0\n");
313:         }
314:     }
315:     static void releaseGatewayObserver(PortalRunEventsHttp.Session expected)throws Exception {
316:         assertNotNull(expected);Object gateway=currentGateway.get();assertNotNull(gateway);
317:         Object slot=field(field(field(gateway,"holder"),"evidence"),"slot");Object cancellation=field(slot,"cancel");
318:         int matches=0;for(var capture:cancellation.getClass().getDeclaredFields()) { capture.setAccessible(true);if(capture.get(cancellation)==expected)matches++; }
319:         assertEquals(1,matches,"the exact original bound Session.abort is the static observer root");
320:         currentGateway.set(null);
321:     }
322:     static void mixedReference(Path run)throws Exception {
323:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null)) {
324:             var held=new org.apache.coyote.Processor[1534];for(int i=0;i<held.length;i++)held[i]=server.protocol.createProcessor();
325:             try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
326:             assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));assertThrows(IllegalStateException.class,server.protocol::createProcessor);
327:             releaseGatewayObserver(server.session.get()); // This reference-bearing observer helper returns before roots are dropped.
328:             server.handle.set(null);server.session.set(null);held[0]=null;
329:             long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
330:             while(System.nanoTime()<end&&(server.protocol.handleReturnedForTest()==0||server.protocol.processorReturnedForTest()==0))Thread.sleep(10);
331:             boolean explicit=server.protocol.handleReturnedForTest()==0||server.protocol.processorReturnedForTest()==0;
332:             if(explicit)System.gc(); // The sole preauthorized test-only GC in this fresh mixed child.
333:             end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
334:             while(System.nanoTime()<end&&(server.protocol.handleReturnedForTest()==0||server.protocol.processorReturnedForTest()==0))Thread.sleep(10);
335:             long handles=server.protocol.handleReturnedForTest(),processors=server.protocol.processorReturnedForTest();int reused=0;
336:             if(handles>0&&processors>0) { held[0]=server.protocol.createProcessor();var extra=server.protocol.createProcessor();assertThrows(IllegalStateException.class,server.protocol::createProcessor);java.lang.ref.Reference.reachabilityFence(extra);reused=2; }
337:             Files.writeString(run.resolve("mixed-reference.properties"),"explicitGcCalls="+(explicit?1:0)+"\nhandleNotifications="+handles+"\nprocessorNotifications="+processors+"\nactualSharedFactoryReuse="+reused+"\nobservation="+(reused==2?"OBSERVED":"INCONCLUSIVE")+"\n");
338:             java.lang.ref.Reference.reachabilityFence(held);
339:         }
340:     }
341:     static void ancestorTail(Path run)throws Exception {
342:         var atRefund=new CountDownLatch(1);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var completionEntered=new CountDownLatch(1);var completionRelease=new CountDownLatch(1);
343:         var serverRef=new AtomicReference<Server>();var stateRef=new AtomicReference<Object>();
344:         BiConsumer<String,Long> hook=(phase,value)->{
345:             if(phase.equals("completion-entry")) { completionEntered.countDown();pause(completionRelease); }
346:             if(phase.equals("before-refund")) { atRefund.countDown();pause(locked); }
347:         };
348:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,hook);var socket=server.request(f.run)) {
349:             serverRef.set(server);assertTrue(completionEntered.await(5,TimeUnit.SECONDS));
350:             var handle=server.handle();Object state=field(handle,"state");var session=server.session.get();assertNotNull(session);Object cell=field(state,"cell");
351:             // Only the actual onComplete publishes completed; the external reaper uses its original gate.
352:             var locker=Thread.ofPlatform().start(()->{pause(atRefund);synchronized(cell) { locked.countDown();pause(release); }});
353:             session.close(); // Establish closing before the reaper, while real completion is still paused.
354:             var reaper=Thread.ofPlatform().start(()->assertTrue(session.awaitClosed(Duration.ofSeconds(10))));
355:             long reapEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
356:             while(Arrays.stream(reaper.getStackTrace()).noneMatch(x->x.getMethodName().equals("reap"))&&System.nanoTime()<reapEnd)Thread.sleep(5);
357:             assertTrue(Arrays.stream(reaper.getStackTrace()).anyMatch(x->x.getMethodName().equals("reap")));
358:             completionRelease.countDown();
359:             try {
360:                 assertTrue(locked.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
361:                 while(PortalEventsPacketBudget.SHARED.owners()!=0&&System.nanoTime()<end)Thread.sleep(5);
362:                 assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
363:                 assertSame(handle,field(session,"lifecycleAnchor"));assertSame(handle,field(state,"handle"));
364:                 for(String name:List.of("frame","probe","output","slot","worker","async","token","lease"))assertNull(field(state,name),name);
365:                 for(String name:List.of("client","manager","connection","request","context","ssl","sslId","raw","handle","alarm","timer","beforeOutputForTest","decoder","privateInput","gatewayResponse","child","saved","authority","originalPortalSource","opening","query","facts","privateFee","fee","materialChild","materialCheckpointForTest","operationThread"))assertNull(field(session,name),name);
366:                 assertTrue(((Map<?,?>)field(session,"guards")).isEmpty());Object lease=field(session,"sseLease");assertEquals(true,field(lease,"released"));for(String name:List.of("charge","cancellation","exit"))assertNull(field(lease,name));assertNull(field(field(session,"owner"),"decoder"));
367:                 assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(tls).forSse(f.observed,f.source,f.run,0,handle));assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
368:                 boolean ancestor=Arrays.stream(reaper.getStackTrace()).anyMatch(x->x.getMethodName().equals("awaitClosed")||x.getMethodName().equals("reap"));
369:                 Files.writeString(run.resolve("ancestor-tail.properties"),"ownerRefunded=true\ncellHeldAfterDetachHelperReturned=true\nfinalSessionAnchorExact=true\nfinalStateHandleExact=true\nreaperAncestorObserved="+ancestor+"\n");
370:                 assertTrue(ancestor,"actual awaitClosed/reap ancestor remains live after refund");
371:             } finally { release.countDown();completionRelease.countDown();locker.join(5000);reaper.join(5000);assertFalse(locker.isAlive());assertFalse(reaper.isAlive()); }
372:             assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));
373:         } finally { release.countDown();completionRelease.countDown(); }
374:     }
375:     static void httpHelperExit(String mode,Path run)throws Exception {
376:         var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondDone=new CountDownLatch(1);
377:         var failures=new ConcurrentLinkedQueue<Throwable>();var firstResult=new AtomicBoolean();var secondResult=new AtomicBoolean();
378:         boolean stop=mode.equals("http-stop-exit");
379:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
380:             var handle=server.handle();var session=server.session.get();assertNotNull(session);
381:             assertTrue(server.waitTrace("frame-complete",5000));
382:             long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
383:             while(field(session,"request")==null&&System.nanoTime()<end)Thread.sleep(5);
384:             assertNotNull(field(session,"request"));assertNotNull(field(session,"handle"));
385:             session.transportCheckpointForTest(phase->{
386:                 // An actual failed cleanup leaves its transport fields intact. The operation and
387:                 // real onComplete then exit before the named closer retries those same resources.
388:                 if(!stop&&phase.equals("cleanup-snapshot")&&!Thread.currentThread().getName().equals("owned-http-first"))throw new IllegalStateException("owned cleanup retry vector");
389:                 if(Thread.currentThread().getName().equals("owned-http-first")&&phase.equals(stop?"stop-snapshot":"cleanup-tail")) { entered.countDown();pause(release); }
390:             });
391:             var first=Thread.ofPlatform().name("owned-http-first").start(()->{try { if(stop)session.abort();else { session.close();firstResult.set(session.awaitClosed(Duration.ofSeconds(5))); } }catch(Throwable failure) { failures.add(failure); }});
392:             Thread second=null;
393:             try {
394:                 assertTrue(entered.await(6,TimeUnit.SECONDS),"actual Http helper reached its reference-bearing window");
395:                 second=Thread.ofPlatform().name("owned-http-second").start(()->{try { session.close();secondResult.set(session.awaitClosed(Duration.ofMillis(100))); }catch(Throwable failure) { failures.add(failure); }finally { secondDone.countDown(); }});
396:                 assertTrue(secondDone.await(5,TimeUnit.SECONDS),"other real closer has bounded wait");
397:                 long held=PortalEventsPacketBudget.SHARED.reservedBytes();int owners=PortalEventsPacketBudget.SHARED.owners();
398:                 Files.writeString(run.resolve("http-helper-window.properties"),"mode="+mode+"\nactualHelperPaused=true\nsecondCloserReturned="+secondResult.get()+"\nowners="+owners+"\nreserved="+held+"\n");
399:                 assertFalse(secondResult.get(),"another closer cannot refund before the actual heavy helper returns");
400:                 assertEquals(1,owners,"original Owner remains admitted");
401:                 assertTrue(held>=PortalEventsPacketBudget.SHARED.connectorBaselineBytes()+PortalRunEventsHttp.HTTP_BYTES,"original Http graph remains charged");
402:             } finally { release.countDown();first.join(8000);if(second!=null)second.join(8000);assertFalse(first.isAlive());if(second!=null)assertFalse(second.isAlive()); }
403:             assertTrue(failures.isEmpty(),failures.toString());assertTrue(session.awaitClosed(Duration.ofSeconds(5)));assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));
404:             assertEquals(PortalEventsPacketBudget.SHARED.connectorBaselineBytes(),PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
405:             Files.writeString(run.resolve("http-helper-exit.properties"),"mode="+mode+"\nactualFirstThreadExited=true\nactualSecondThreadExited=true\nfinalOwners=0\nfinalBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
406:         } finally { release.countDown(); }
407:     }
408:     static void retainedCap(Path run)throws Exception {
409:         try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",utf("null"));var c=new SequencePeer(f,Files.createDirectories(run.resolve("c")),m->{},"scope",utf("null"));var ta=a.tls();var tb=b.tls();var tc=c.tls();var server=new Server(run,f,List.of(ta,tb,tc),Arrays.asList(null,null,null))) {
410:             var held=new org.apache.coyote.Processor[1533];
411:             for(int i=0;i<held.length;i++)held[i]=server.protocol.createProcessor();
412:             for(int i=0;i<2;i++) {
413:                 try(var socket=server.request(f.run)) { assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("data: null")); }
414:                 assertTrue(server.exchanges.get(i).handle().awaitClosed(Duration.ofSeconds(5)));
415:             }
416:             assertEquals(1534,server.protocol.creationsForTest(),"two real requests reuse one original processor");
417:             try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
418:             assertInstanceOf(IllegalStateException.class,server.exchanges.get(2).failure.get(),"retained finished Handles must exhaust the common pool before third Session creation");
419:             assertNull(server.exchanges.get(2).handle.get());assertEquals(0,c.connections,"refusal before Session/Q");
420:             Files.writeString(run.resolve("retained-cap.properties"),"manualProcessors=1533\nactualRequestProcessors=1\nretainedFinishedHandles=2\nthirdDeniedBeforeSession=true\n");
421:             java.lang.ref.Reference.reachabilityFence(held);java.lang.ref.Reference.reachabilityFence(server.exchanges);
422:         }
423:     }
424:     static void startupFailure(String mode,Path run)throws Exception {
425:         String selected=mode.substring(4);
426:         try(var f=new Fixture();var a=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},selected,utf("null"));var b=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},selected,utf("null"));var c=new SequencePeer(f,Files.createDirectories(run.resolve("c")),m->{},"eof",utf("null"));var ta=a.tls();var tb=b.tls();var tc=c.tls();var server=new Server(run,f,List.of(ta,tb,tc),Arrays.asList(null,null,null))) {
427:             try {
428:                 for(int i=0;i<2;i++) {
429:                     try(var socket=server.request(f.run)) { socket.getInputStream().readAllBytes(); }
430:                     var x=server.exchanges.get(i);assertNotNull(x.failure.get());if(selected.equals("q403")) { var failure=assertInstanceOf(PlatformException.class,x.failure.get());assertEquals(403,failure.status());assertEquals("RUN_AUTHORITY_DENIED",failure.code()); }assertNull(x.handle.get());assertFalse(traceCount(x.trace,"setter-return")>0);assertFalse(traceCount(x.trace,"window")>0);
431:                     Files.writeString(run.resolve("before-cleanup-"+i+".properties"),"owners="+PortalEventsPacketBudget.SHARED.owners()+"\nreserved="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
432:                     assertEquals(0,PortalEventsPacketBudget.SHARED.owners());assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
433:                 }
434:                 try(var socket=server.request(f.run)) { assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("data: null")); }
435:                 assertTrue(server.exchanges.get(2).handle().awaitClosed(Duration.ofSeconds(5)));
436:                 Files.writeString(run.resolve("startup.properties"),"repeatFailures=2\nsubsequentNormalStart=true\n");
437:             } finally { for(var x:server.exchanges)if(x.session.get()!=null)x.session.get().close(); }
438:         }
439:     }
440:     static void startupScope(String mode,Path run)throws Exception {
441:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"scope",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null)) {
442:             server.invocation=mode;
443:             if(mode.equals("missing-token"))for(var valve:server.context.getPipeline().getValves())if(valve.getClass().getName().contains("PortalRunEventsSse$ContextValve"))server.context.getPipeline().removeValve(valve);
444:             try {
445:                 try(var socket=server.request(f.run)) { try { socket.getInputStream().readAllBytes(); }catch(SocketTimeoutException boundedOldProbe) { Files.writeString(run.resolve("old-probe-timeout.txt"),"real connector probe reached bounded read deadline\n"); } }
446:                 assertEquals(0,peer.connections,"reject before Q");assertNotNull(server.failure.get());assertNull(server.handle.get(),"original REQUEST only");
447:                 assertFalse(traceCount(server.trace,"bootstrap-wait")>0);assertFalse(traceCount(server.trace,"setter-return")>0);assertEquals(0,peer.connections,"reject before Q");
448:                 assertEquals(3145728,PortalEventsPacketBudget.SHARED.reservedBytes());
449:                 Files.writeString(run.resolve("scope.properties"),"mode="+mode+"\nqConnections="+peer.connections+"\nsetter=0\n");
450:             } finally { if(server.session.get()!=null)server.session.get().close(); }
451:         }
452:     }
453:     static void allocationCap(String mode,Path run)throws Exception {
454:         Tomcat tomcat=new Tomcat();var protocol=new PortalRunEventsSse.ControlledProtocol();var connector=new org.apache.catalina.connector.Connector(protocol);connector.setPort(0);tomcat.setConnector(connector);tomcat.setBaseDir(Files.createDirectories(run.resolve("tomcat")).toString());tomcat.addContext("",run.toString());
455:         org.apache.coyote.Processor[] held=new org.apache.coyote.Processor[1537];
456:         try {
457:             tomcat.start();
458:             if(mode.equals("fix-burn")) {
459:                 protocol.failConstructionForTest(1);assertThrows(IllegalStateException.class,protocol::createProcessor);
460:                 protocol.failConstructionForTest(2);assertThrows(IllegalStateException.class,protocol::createProcessor);assertEquals(2,protocol.burnedForTest());
461:                 for(int i=0;i<1534;i++)held[i]=protocol.createProcessor();assertThrows(IllegalStateException.class,protocol::createProcessor);
462:                 assertEquals(1536,protocol.allocationsForTest());assertEquals(2,protocol.burnedForTest());
463:                 Files.writeString(run.resolve("burn.properties"),"burned=2\ntracked=1534\nownedAllocations=1536\nrefusalBeforeExtraAllocation=true\n");return;
464:             }
465:             for(int i=0;i<1535;i++)held[i]=protocol.createProcessor();
466:             assertTrue(protocol.waitingIdentityForTest(held[0]));held[0].recycle();assertTrue(protocol.waitingIdentityForTest(held[0]));
467:             var start=new CountDownLatch(1);var success=new AtomicInteger();var denied=new AtomicInteger();var unexpected=new AtomicReference<Throwable>();
468:             Thread[] threads=new Thread[2];for(int j=0;j<2;j++) { final int index=1535+j;threads[j]=Thread.ofPlatform().start(()->{try { start.await();held[index]=protocol.createProcessor();success.incrementAndGet(); }catch(IllegalStateException expected) { denied.incrementAndGet(); }catch(Throwable t) { unexpected.set(t); }}); }
469:             start.countDown();for(var thread:threads) { thread.join(5000);assertFalse(thread.isAlive()); }
470:             Files.writeString(run.resolve("cap.properties"),"strongInitial=1535\nraceSuccess="+success+"\nraceDenied="+denied+"\ncreated="+protocol.creationsForTest()+"\n");
471:             assertNull(unexpected.get());assertEquals(1,success.get(),"only last allocation slot admitted");assertEquals(1,denied.get());assertThrows(IllegalStateException.class,protocol::createProcessor);assertEquals(1536,protocol.creationsForTest());assertEquals(1536,protocol.allocationsForTest());
472:             if(mode.equals("fix-reuse")) {
473:                 java.lang.ref.Reference.reachabilityFence(held);Arrays.fill(held,null);
474:                 long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(protocol.returnedForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
475:                 boolean explicit=protocol.returnedForTest()==0;if(explicit)System.gc(); // Authorized once, isolated witness only.
476:                 end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(protocol.returnedForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
477:                 long returned=protocol.returnedForTest();
478:                 if(returned>0)held[0]=protocol.createProcessor();
479:                 Files.writeString(run.resolve("reference-witness.properties"),"explicitGcCalls="+(explicit?1:0)+"\nautomaticNotifications="+returned+"\nactualFactoryReuse="+(held[0]!=null)+"\nobservation="+(returned>0?"OBSERVED":"INCONCLUSIVE")+"\n");
480:             }
481:         } finally { java.lang.ref.Reference.reachabilityFence(held);tomcat.stop();tomcat.destroy(); }
482:     }
483:     static String line(InputStream in)throws IOException {
484:         var out=new ByteArrayOutputStream();int previous=-1,b;
485:         while((b=in.read())!=-1) { if(previous=='\r'&&b=='\n')return out.toString(StandardCharsets.US_ASCII).substring(0,out.size()-1);out.write(b);previous=b;if(out.size()>8192)throw new IOException("bounded response line"); }
486:         throw new EOFException();
487:     }
488:     static byte[] chunked(Socket socket)throws IOException {
489:         var in=socket.getInputStream();assertTrue(line(in).startsWith("HTTP/1.1 200"));
490:         String h;boolean chunked=false;while(!(h=line(in)).isEmpty())if(h.equalsIgnoreCase("Transfer-Encoding: chunked"))chunked=true;
491:         assertTrue(chunked);var body=new ByteArrayOutputStream();
492:         while(true) { int n=Integer.parseInt(line(in),16);if(n==0) { assertEquals("",line(in));return body.toByteArray(); }if(n>1048576||body.size()+n>2097152)throw new IOException("bounded SSE response");body.write(in.readNBytes(n));assertEquals("",line(in)); }
493:     }
494:     static void keepalive(Path run)throws Exception {
495:         var atExit=new CountDownLatch(1);var release=new CountDownLatch(1);
496:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{ if(phase.equals("worker-body-exit")) { atExit.countDown();pause(release); } });var socket=server.request(f.run,true)) {
497:             try {
498:                 assertTrue(atExit.await(5,TimeUnit.SECONDS));assertTrue(new String(chunked(socket),StandardCharsets.UTF_8).contains("data: null"));
499:                 assertTrue(server.waitTrace("token-disarmed",5000));assertTrue(server.handle().inertTokenForTest());var h=server.handle();assertFalse(h.awaitClosed(Duration.ZERO));assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>0);
500:                 socket.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));socket.getOutputStream().flush();h.close();
501:                 String next=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(next.startsWith("HTTP/1.1 200"));assertTrue(next.endsWith("pong"));
502:                 release.countDown();assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());
503:                 Files.writeString(run.resolve("keepalive.properties"),"sameSocket=true\nlateAbortAfterRealDisarm=true\nnextRequestPong=true\nworkerHeldFeesUntilActualExit=true\n");
504:             } finally { release.countDown(); }
505:         }
506:     }
507:     static void pause(CountDownLatch release) {
508:         try { if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("owned pause deadline"); }
509:         catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
510:     }
511:     static void freshness(String mode,Path run)throws Exception {
512:         var points=List.of(mode.substring(mode.indexOf('-')+1));
513:         for(String point:points) {
514:             Path vector=Files.createDirectory(run.resolve(point));var changed=new AtomicBoolean();var frames=new AtomicInteger();
515:             byte[] raw=utf("\""+"x".repeat(240000)+"\"");
516:             try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},"eof",raw);var tls=peer.tls()) {
517:                 BiConsumer<String,Long> hook=(phase,value)->{
518:                     if(phase.equals("frame-complete"))frames.incrementAndGet();
519:                     boolean trigger=point.contains("getter")?phase.equals(point):frames.get()==1&&(point.equals("revoke")||point.equals("partial")?phase.equals("after-write"):phase.equals(point));
520:                     if(trigger&&changed.compareAndSet(false,true)) {
521:                         if(point.equals("partial"))throw new UncheckedIOException(new IOException("partial-unknown sentinel"));
522:                         var g=currentGateway.get();
523:                         if(point.equals("revoke")) { f.iam.revoked(true);assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(g)); }
524:                         else { var previous=g.currentCapture();f.gateway.checkGatewayPortalEvents(g);assertTrue(g.isSuperseded(previous)); }
525:                     }
526:                 };
527:                 try(var server=new Server(vector,f,tls,hook);var socket=server.request(f.run)) {
528:                     try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
529:                     assertTrue(changed.get(),point);
530:                     var handle=server.handle.get();
531:                     if(handle!=null) { assertTrue(handle.awaitClosed(Duration.ofSeconds(5)));assertEquals(0,handle.after()); }
532:                     else { assertNotNull(server.failure.get());assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5))); }
533:                     assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());
534:                     Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
535:                 }
536:             }
537:         }
538:     }
539:     static void bootstrap(String selected,Path run)throws Exception {
540:         for(String mode:List.of(selected)) {
541:             Path vector=Files.createDirectory(run.resolve(mode));var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
542:             try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},mode,utf("null"));var tls=peer.tls();var server=new Server(vector,f,tls,(phase,value)->{if(mode.equals("late")&&phase.equals("window")) { entered.countDown();pause(release); }});var socket=server.request(f.run)) {
543:                 try {
544:                     if(mode.equals("late")) { assertTrue(entered.await(5,TimeUnit.SECONDS));server.session.get().close();assertTrue(PortalEventsPacketBudget.SHARED.reservedBytes()>83886080L);release.countDown(); }
545:                     try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
546:                     assertNull(server.handle.get());assertNotNull(server.failure.get());
547:                     assertTrue(server.session.get().awaitClosed(Duration.ofSeconds(5)));assertFalse((traceCount(server.trace,"setter-return")>0));
548:                     assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
549:                 } finally { release.countDown(); }
550:             }
551:         }
552:     }
553:     static void idle(Path run)throws Exception {
554:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
555:             var h=server.handle();assertTrue(server.waitTrace("ready-idle",5000));long start=System.nanoTime();
556:             for(int i=0;i<20;i++) { h.wakeForTest();Thread.sleep(30); }
557:             assertTrue(h.awaitClosed(Duration.ofSeconds(20)));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
558:             assertTrue(elapsed>=13000&&elapsed<19000,"actual original 15s idle, no peer timeout or reset by wake");assertFalse(h.terminal());
559:             Files.write(run.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);Files.writeString(run.resolve("idle.properties"),"spuriousWakes=20\nelapsedMs="+elapsed+"\ncontainerAsyncTimeout=0\n");
560:         }
561:     }
562:     static long traceCount(List<String> trace,String prefix) { synchronized(trace) { return trace.stream().filter(x->x.startsWith(prefix)).count(); } }
563:     static void lightShell(Path run)throws Exception {
564:         var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var frames=new AtomicInteger();var hit=new AtomicBoolean();
565:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"idle",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{
566:             if(phase.equals("frame-complete"))frames.incrementAndGet();
567:             if(phase.equals("ready-idle")&&frames.get()==2&&hit.compareAndSet(false,true)) { entered.countDown();pause(release); }
568:         });var socket=server.request(f.run)) {
569:             try {
570:                 assertTrue(entered.await(5,TimeUnit.SECONDS));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
571:                 while(System.nanoTime()<end) { long observed=PortalEventsPacketBudget.SHARED.reservedBytes();if(observed>=67108864L&&observed<83886080L)break;Thread.sleep(10); }
572:                 long scan=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(scan>=67108864L&&scan<83886080L,"old C1 gone, next actual F1 read charged");
573:                 server.session.get().close();assertFalse(server.session.get().awaitClosed(Duration.ZERO));
574:                 long retained=PortalEventsPacketBudget.SHARED.reservedBytes();
575:                 assertEquals(PortalEventsPacketBudget.SHARED.connectorBaselineBytes()+6169798L,retained,"original HTTP/private/SSE/Owner retained before completion");
576:                 release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());
577:                 Files.writeString(run.resolve("light-shell.properties"),"nextF1Bytes="+scan+"\noldMaterialExited=true\nouterCallbackStillPaused=true\nretainedBeforeCompletion="+retained+"\nfinalBytes="+PortalEventsPacketBudget.SHARED.reservedBytes()+"\n");
578:             } finally { release.countDown(); }
579:         }
580:     }
581:     static void pendingFlush(String selected,Path run)throws Exception {
582:         for(String outcome:List.of(selected)) {
583:             Path vector=Files.createDirectory(run.resolve(outcome));byte[] raw=utf("\""+"x".repeat(8000)+"\"");
584:             try(var f=new Fixture();var peer=new SequencePeer(f,vector,m->{},"pending-flush",raw);var tls=peer.tls();var server=new Server(vector,f,tls,null);var socket=server.request(f.run)) {
585:                 assertTrue(server.waitTrace("flush-wait",8000),"actual nonblocking flush left bytes pending");
586:                 var h=server.handle();long before=h.after();assertTrue(before<20);
587:                 if(outcome.equals("generation")) { var g=currentGateway.get();var old=g.currentCapture();f.gateway.checkGatewayPortalEvents(g);assertTrue(g.isSuperseded(old)); }
588:                 if(outcome.equals("revoke")) { f.iam.revoked(true);assertThrows(PlatformException.class,()->f.gateway.checkGatewayPortalEvents(currentGateway.get())); }
589:                 assertEquals(before,h.after());socket.setReceiveBufferSize(262144);
590:                 try { socket.getInputStream().readAllBytes(); }catch(SocketException expected) { }
591:                 assertTrue(h.awaitClosed(Duration.ofSeconds(5)));assertEquals(outcome.equals("drain")?20:before,h.after());
592:                 Files.writeString(vector.resolve("pending-flush.properties"),"outcome="+outcome+"\nactualFlushWait=true\npublicAfterWhilePending="+before+"\npublicAfterExit="+h.after()+"\n");
593:                 Files.write(vector.resolve("sse-trace.txt"),server.trace,StandardCharsets.UTF_8);
594:             }
595:         }
596:     }
597:     static void retentionScenario(String mode,Path run)throws Exception {
598:         var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var hitA=new AtomicBoolean();var hitB=new AtomicBoolean();
599:         String trigger=mode.equals("heavy-retention")?"frame-complete":mode.equals("completion-retention")?"completion-entry":"before-write";
600:         BiConsumer<String,Long> a=(phase,value)->{if(phase.equals(trigger)&&hitA.compareAndSet(false,true)) { entered.countDown();pause(release); }};
601:         BiConsumer<String,Long> b=(phase,value)->{if(phase.equals(trigger)&&hitB.compareAndSet(false,true)) { entered.countDown();pause(release); }};
602:         try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",utf("null"));var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",utf("null"));var ta=pa.tls();var tb=pb.tls();var server=new Server(run,f,List.of(ta,tb),List.of(a,b));var ca=server.request(f.run);var cb=server.request(f.run)) {
603:             var sa=server.exchanges.get(0);var sb=server.exchanges.get(1);
604:             try {
605:                 assertTrue(entered.await(7,TimeUnit.SECONDS));
606:                 long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertEquals(2,PortalEventsPacketBudget.SHARED.owners());
607:                 assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
608:                 if(mode.equals("heavy-retention")) { sa.session.get().close();sb.session.get().close();sa.handle().interruptReaderForTest();sb.handle().interruptReaderForTest();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.session.get().awaitClosed(Duration.ZERO)); }
609:                 if(mode.equals("completion-retention")) { sa.session.get().close();sb.session.get().close();assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());assertFalse(sa.handle().awaitClosed(Duration.ofMillis(40))); }
610:                 if(mode.equals("abort-order")) {
611:                     var abortA=Thread.ofPlatform().start(sa.handle()::close);var abortB=Thread.ofPlatform().start(sb.handle()::close);
612:                     Thread.sleep(100);assertTrue(abortA.isAlive());assertTrue(abortB.isAlive());assertEquals(held,PortalEventsPacketBudget.SHARED.reservedBytes());
613:                     release.countDown();abortA.join(5000);abortB.join(5000);assertFalse(abortA.isAlive());assertFalse(abortB.isAlive());
614:                 }
615:                 release.countDown();assertTrue(sa.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(sb.handle().awaitClosed(Duration.ofSeconds(6)));
616:                 assertEquals(3145728L,PortalEventsPacketBudget.SHARED.reservedBytes());assertEquals(0,PortalEventsPacketBudget.SHARED.owners());
617:                 Files.writeString(run.resolve("retention.properties"),"mode="+mode+"\nheldBytes="+held+"\nthirdDenied=true\nactualWorkersExited=true\nfinalOwnedBytes=0\nconnectorBaseline=3145728\n");
618:                 Files.write(run.resolve("a-trace.txt"),sa.trace,StandardCharsets.UTF_8);Files.write(run.resolve("b-trace.txt"),sb.trace,StandardCharsets.UTF_8);
619:             } finally { release.countDown(); }
620:         }
621:     }
622:     static void awaitPublicBackpressure(Exchange exchange)throws Exception {
623:         long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);int previous=-1;
624:         while(System.nanoTime()<end) {
625:             int count;boolean idle;synchronized(exchange.trace) { count=exchange.trace.size();idle=count>0&&exchange.trace.get(count-1).startsWith("ready-false="); }
626:             if(idle&&count==previous)return;previous=count;Thread.sleep(30);
627:         }
628:         fail("actual public ready-false must settle before launching this external action");
629:     }
630:     static void actionRetention(Path run)throws Exception {
631:         var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
632:         BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("before-close-now")) { entered.countDown();pause(release); }};
633:         byte[] raw=utf("\""+"x".repeat(262142)+"\"");
634:         try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",raw);var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",raw);var ta=pa.tls();var tb=pb.tls();var server=new Server(run,f,List.of(ta,tb),List.of(hook,hook));var ca=server.request(f.run);var cb=server.request(f.run)) {
635:             var a=server.exchanges.get(0);var b=server.exchanges.get(1);
636:             Thread aa=null,bb=null;
637:             try {
638:                 // Hold the first actual action before waiting for the second public output; the first
639:                 // response must not expire/complete while the fixture waits for both backpressure states.
640:                 awaitPublicBackpressure(a);aa=Thread.ofPlatform().start(a.handle()::close);
641:                 awaitPublicBackpressure(b);bb=Thread.ofPlatform().start(b.handle()::close);
642:                 assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(aa.isAlive());assertTrue(bb.isAlive());
643:                 assertFalse(a.handle().awaitClosed(Duration.ofMillis(40)));assertFalse(b.handle().awaitClosed(Duration.ofMillis(40)));
644:                 assertEquals(2,PortalEventsPacketBudget.SHARED.owners());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
645:                 long held=PortalEventsPacketBudget.SHARED.reservedBytes();assertTrue(held>=13388172L);
646:                 release.countDown();aa.join(5000);bb.join(5000);assertFalse(aa.isAlive());assertFalse(bb.isAlive());
647:                 assertTrue(a.handle().awaitClosed(Duration.ofSeconds(6)));assertTrue(b.handle().awaitClosed(Duration.ofSeconds(6)));
648:                 assertEquals(1,traceCount(a.trace,"close-now-return"));assertEquals(1,traceCount(b.trace,"close-now-return"));
649:                 Files.writeString(run.resolve("action-retention.properties"),"heldBytes="+held+"\nthirdDenied=true\nactualCloseNowEach=1\nactualActionThreadsExited=true\n");
650:             } finally { release.countDown();if(aa!=null)aa.join(5000);if(bb!=null)bb.join(5000); }
651:         }
652:     }
653:     static void processorReuse(Path run)throws Exception {
654:         var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
655:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",utf("null"));var tls=peer.tls();var server=new Server(run,f,tls,(phase,value)->{if(phase.equals("worker-body-exit")) { entered.countDown();pause(release); }});var first=server.request(f.run)) {
656:             try {
657:                 assertTrue(entered.await(5,TimeUnit.SECONDS));first.getInputStream().readAllBytes();
658:                 long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(server.protocol.recyclesForTest()==0&&System.nanoTime()<end)Thread.sleep(10);
659:                 assertTrue(server.protocol.recyclesForTest()>0);long old=server.protocol.processorForTest();Thread.sleep(100);
660:                 try(var next=new Socket("127.0.0.1",server.tomcat.getConnector().getLocalPort())) {
661:                     next.setSoTimeout(5000);next.getOutputStream().write(utf("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\nGET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));next.getOutputStream().flush();
662:                     server.handle().close();String body=new String(next.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(2,body.split("pong",-1).length-1);
663:                     assertEquals(old,server.protocol.processorForTest());assertEquals(1,server.protocol.creationsForTest());
664:                     assertEquals(0,traceCount(server.trace,"before-close-now"));
665:                 }
666:                 release.countDown();assertTrue(server.handle().awaitClosed(Duration.ofSeconds(5)));
667:                 Files.writeString(run.resolve("reuse.properties"),"sameProcessorId="+old+"\ndifferentSocket=true\npipelinedPingResponses=2\nlateActionCalls=0\n");
668:             } finally { release.countDown(); }
669:         }
670:     }
671:     static void notReadyIdle(Path run)throws Exception {
672:         byte[] raw=utf("\""+"x".repeat(262142)+"\"");
673:         try(var f=new Fixture();var peer=new SequencePeer(f,run,m->{},"eof",raw);var tls=peer.tls();var server=new Server(run,f,tls,null);var socket=server.request(f.run)) {
674:             assertTrue(server.waitTrace("ready-false",5000));var h=server.handle();long before=h.after(),start=System.nanoTime();
675:             assertTrue(h.awaitClosed(Duration.ofSeconds(22)));long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
676:             assertTrue(elapsed>=12000&&elapsed<21000);assertEquals(before,h.after());assertEquals(1,traceCount(server.trace,"close-now-return"));
677:             Files.writeString(run.resolve("not-ready-idle.properties"),"noClientReadAfterReadyFalse=true\nelapsedMs="+elapsed+"\ncloseNowCalls=1\nafter="+h.after()+"\n");
678:         }
679:     }
680:     static void forcedRelease(Path run)throws Exception {
681:         byte[] raw=utf("\""+"x".repeat(262142)+"\"");
682:         var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
683:         var owner=new AtomicReference<Server>();
684:         var sockets=new ConcurrentHashMap<String,org.apache.tomcat.util.net.SocketWrapperBase<?>>();
685:         BiConsumer<String,Long> hook=(phase,value)->{if(phase.equals("before-write")) { var actual=owner.get().protocol.currentSocketForTest();if(actual!=null)sockets.putIfAbsent(Thread.currentThread().getName()+"-"+System.identityHashCode(actual),actual); }if(phase.equals("recycle-invalidated")) { entered.countDown();pause(release); }};
686:         try(var f=new Fixture();var pa=new SequencePeer(f,Files.createDirectories(run.resolve("a")),m->{},"eof",raw);var pb=new SequencePeer(f,Files.createDirectories(run.resolve("b")),m->{},"eof",raw);var ta=pa.tls();var tb=pb.tls() ) {
687:             var server=new Server(run,f,List.of(ta,tb),List.of(hook,hook));
688:             owner.set(server);
689:             try(var ca=server.request(f.run);var cb=server.request(f.run)) {
690:                 var a=server.exchanges.get(0);var b=server.exchanges.get(1);a.handle();b.handle();
691:                 long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<end&&!((traceCount(a.trace,"ready-false")>0)&&(traceCount(b.trace,"ready-false")>0)))Thread.sleep(10);
692:                 var actual=new HashSet<>(sockets.values());assertEquals(2,actual.size());
693:                 var closers=actual.stream().map(socket->Thread.ofPlatform().start(socket::close)).toList();
694:                 try {
695:                     assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(0,server.protocol.recyclesForTest());
696:                     a.session.get().close();b.session.get().close();a.handle().close();b.handle().close();
697:                     assertFalse(a.handle().awaitClosed(Duration.ofMillis(50)));assertFalse(b.handle().awaitClosed(Duration.ofMillis(50)));
698:                     assertEquals(2,PortalEventsPacketBudget.SHARED.owners());assertThrows(IllegalStateException.class,()->new PortalRunEventsHttp(ta).begin(f.gateway,f.source,f.run,0));
699:                     assertFalse((traceCount(a.trace,"before-close-now")>0));assertFalse((traceCount(b.trace,"before-close-now")>0));
700:                     release.countDown();for(var closer:closers) { closer.join(5000);assertFalse(closer.isAlive()); }
701:                     assertEquals(0,server.protocol.getWaitingProcessorCount());assertTrue(server.protocol.recyclesForTest()>=2);assertTrue(a.handle().inertTokenForTest());assertTrue(b.handle().inertTokenForTest());
702:                     a.handle().close();b.handle().close();assertFalse((traceCount(a.trace,"before-close-now")>0));assertFalse((traceCount(b.trace,"before-close-now")>0));
703:                     assertFalse((traceCount(a.trace,"completion-entry")>0));assertFalse((traceCount(b.trace,"completion-entry")>0));
704:                     Files.writeString(run.resolve("forced-release.properties"),"actualSocketCloseEach=true\nbarrierBeforeDelegateRecycle=true\nrecycled="+server.protocol.recyclesForTest()+"\nwaiting=0\ncompletionNotInvented=true\nownersRetained=2\nlateActionCalls=0\n");
705:                 } finally { release.countDown();for(var closer:closers)closer.join(5000); }
706:             } finally { for(int i=0;i<server.exchanges.size();i++)Files.write(run.resolve("forced-trace-"+i+".txt"),server.exchanges.get(i).trace,StandardCharsets.UTF_8);server.tomcat.stop();server.tomcat.destroy(); }
707:         }
708:     }
709:     static final class SequencePeer implements AutoCloseable {
710:         final Fixture f;
711:         final SSLServerSocket listener;
712:         final Thread worker;
713:         final Path run;
714:         volatile SSLSocket socket;
715:         volatile Throwable failure;
716:         int connections; long ordinal;
717:         String receipt;
718:         PortalEventsResponse engineResponse;
719:         SequencePeer(Fixture f, Path run, Consumer<ObjectNode> mutation, String mode, byte[] payload) throws Exception {
720:             this.f = f;
721:             this.run = run;
722:             var c = SSLContext.getInstance("TLSv1.3", "SunJSSE");
723:             var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
724:             km.init(PortalRunEventsHttpTest.fixtures(), "f2a-test-only".toCharArray());
725:             var key = Arrays.stream(km.getKeyManagers()).filter(X509ExtendedKeyManager.class::isInstance).map(X509ExtendedKeyManager.class::cast).findFirst().orElseThrow();
726:             c.init(new KeyManager[] {
727:                 new PortalEventsTlsTest.FixedKey(key, "normal", new Properties())
728:             }, null, null);
729:             listener = (SSLServerSocket)c.getServerSocketFactory().createServerSocket(0, 2, InetAddress.getByAddress(new byte[] {
730:                 127, 0, 0, 1
731:             }));
732:             listener.setEnabledProtocols(new String[] {
733:                 "TLSv1.3"
734:             });
735:             listener.setEnabledCipherSuites(new String[] {
736:                 PortalEventsTls.CIPHER
737:             });
738:             listener.setSoTimeout(5000);
739:             worker = Thread.ofPlatform().daemon().start(() -> {
740:                 try {
741:                     try (var q = (SSLSocket)listener.accept()) {
742:                         socket = q;
743:                         connections++;
744:                         q.setSoTimeout(5000);
745:                         q.startHandshake();
746:                         head(q);
747:                         if(mode.equals("q403")) { byte[] body=utf("{\"code\":\"RUN_AUTHORITY_DENIED\",\"message\":\"denied\",\"data\":null,\"traceId\":null}");q.getOutputStream().write(PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 403 Denied\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n"),body));q.getOutputStream().flush();return; }
748:                         if(mode.equals("qtransport"))return;
749:                         q.getOutputStream().write(f.q());
750:                         q.getOutputStream().flush();
751:                     }
752:                     if(mode.equals("q-only"))return;
753:                     try (var e = (SSLSocket)listener.accept()) {
754:                         socket = e;
755:                         connections++;
756:                         e.setSoTimeout(25000);
757:                         e.startHandshake();
758:                         String h = head(e);
759:                         String bearer = h.substring(h.indexOf("Authorization: Bearer ") + 22).split("\r\n", 2)[0];
760:                         // Real mapped receiver frame. Public bind(currentOnly) cannot supply this initial provenance.
761:                         var previousSecurity = SecurityContextHolder.getContext();
762:                         var previousRequest = RequestContextHolder.getRequestAttributes();
763:                         var request = new MockHttpServletRequest("GET", "/admin/runs/" + f.run + "/events");
764:                         var response = new MockHttpServletResponse();
765:                         request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/admin/runs/{id}/events");
766:                         var handler = new org.springframework.web.method.HandlerMethod(new PortalEventsMaterialAuthorityTest(), PortalEventsMaterialAuthorityTest.class.getMethod("handler"));
767:                         var gate = new ServiceAuthorization("engine", new ServiceRoutePolicy(), f.engine);
768:                         try (var decoder = new TrustedJwtDecoder(f.iam.config("engine"), f.iam.clock(), true)) {
769:                             var context = SecurityContextHolder.createEmptyContext();
770:                             context.setAuthentication(new JwtAuthenticationToken(decoder.decode(bearer), List.of()));
771:                             SecurityContextHolder.setContext(context);
772:                             RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
773:                             assertTrue(gate.preHandle(request, response, handler));
774:                             var binding = RunObservationSelectors.events(f.run, f.accepted);
775:                             var retained = AuthorityContext.requireInitialFor(AuthorityContext.current().actor(), binding);
776:                             engineResponse = f.engine.openPortalEventsChild(retained, 0, () -> {
777:                             });
778:                             receipt = f.engine.portalEventsChildReceipt(engineResponse);
779:                             var capture = engineResponse.currentCapture();
780:                             String child = capture.responseId();
781:                             var model = f.original.deepCopy();
782:                             mutation.accept(model);
783:                             byte[] m = JSON.writeValueAsBytes(model), p = payload == null ? asset().payload() : payload;
784:                             String signed = mode.equals("bad-receipt") ? receipt.substring(0, receipt.lastIndexOf('.')+1) + (receipt.charAt(receipt.lastIndexOf('.')+1)=='a'?"b":"a") + receipt.substring(receipt.lastIndexOf('.')+2) : receipt;
785:                             byte[] hello = JSON.writeValueAsBytes(Map.of("receipt", signed));
786:                             String helloChild = mode.equals("wrong-child") ? "foreign-child" : child;
787:                             byte[] first=packet(1, hello, EMPTY, helloChild, model);
788:                             byte[] wire=wire(this,first,child,model,p,mode);
789:                             e.getOutputStream().write(mode.equals("idle")?PortalEventsPacketDecoderTest.join(utf("HTTP/1.1 200 Finite\r\nContent-Type: "+MIME+"\r\nContent-Length: "+(wire.length+1)+"\r\n\r\n"),wire):response(wire, MIME));
790:                             e.getOutputStream().flush();
791:                             try {
792:                                 e.getInputStream().read();
793:                             }
794:                             catch (SocketException expected) {
795:                             }
796:                         }
797:                         finally {
798:                             gate.afterCompletion(request, response, handler, null);
799:                             RequestContextHolder.setRequestAttributes(previousRequest);
800:                             SecurityContextHolder.setContext(previousSecurity);
801:                         }
802:                     }
803:                 }
804:                 catch (SocketException expected) {
805:                     if (engineResponse == null&&!mode.equals("scope")) failure = expected;
806:                 }
807:                 catch (Throwable t) {
808:                     failure = t;
809:                 }
810:             });
811:         }
812:         byte[] packet(int kind, byte[] m, byte[] p, String child, JsonNode model) throws Exception {
813:             var h = JSON.createObjectNode();
814:             h.put("childResponseId", child);
815:             h.put("runId", f.run);
816:             h.put("resourceSha256", f.accepted);
817:             h.put("initialCursor", 0);
818:             h.put("afterSeq", model.has("window")?model.get("window").get("afterSeq").asLong():0);
819:             h.put("payloadBytes", p.length);
820:             h.put("metadataSha256", sha(m));
821:             h.put("payloadSha256", sha(p));
822:             if (kind == 2 && model.get("hasEvent").asBoolean()) {
823:                 h.set("eventSeq", model.get("eventIdentity").get("seq"));
824:                 h.set("eventType", model.get("eventIdentity").get("type"));
825:             }
826:             else {
827:                 h.putNull("eventSeq");
828:                 h.putNull("eventType");
829:             }
830:             return PortalEventsPacketDecoderTest.packet(kind, kind == 1 ? 0 : ++ordinal, m, p, JSON.writeValueAsString(h));
831:         }
832:         PortalEventsTls tls() throws Exception {
833:             return new PortalEventsTls(URI.create("https://"+HOST+":"+listener.getLocalPort()), new byte[] {
834:                 127, 0, 0, 1
835:             }, PortalRunEventsHttpTest.ca(run, "root"));
836:         }
837:         public void close() throws Exception {
838:             listener.close();
839:             if (socket != null) socket.close();
840:             worker.join(5000);
841:             assertFalse(worker.isAlive());
842:             if (engineResponse != null) engineResponse.close();
843:             if (failure != null) throw new AssertionError("receiver peer", failure);
844:         }
845:     }
846: 
847: }