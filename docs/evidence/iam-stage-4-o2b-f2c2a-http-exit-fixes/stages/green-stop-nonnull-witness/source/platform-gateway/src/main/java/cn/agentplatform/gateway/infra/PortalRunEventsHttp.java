package cn.agentplatform.gateway.infra;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.PortalEventsChildAuthorization;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayEventsOpening;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.GatewayObservationQueryAuthorization;
import cn.agentplatform.gateway.application.RunObservationMetadataClient;
import cn.agentplatform.gateway.application.RunObservationMetadataClient.EventsMetadata;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.apache.hc.client5.http.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.*;
import org.apache.hc.client5.http.io.*;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.io.*;
import org.apache.hc.core5.http.io.*;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.*;
/** Unassembled original-Q and private APEV input transport. Resource admission conveys no authorization. */
public final class PortalRunEventsHttp {
    // Fixed finite object model: A393216+B65536+C524288+D262144+E1048576+F65536+G131072
    // =2490368, plus655360 margin=3145728; F1 separately charges the128B reservation token.
    static final long HTTP_BYTES = 3145728L;
    static final long PRIVATE_BYTES = 1048576L;
    private final PortalEventsTls tls;
    public PortalRunEventsHttp(PortalEventsTls tls) {
        this.tls = Objects.requireNonNull(tls);
    }
    public Session begin(CurrentAuthorityClient authority, String originalPortalSource, String run, long cursor) {
        return begin(authority,originalPortalSource,run,cursor,null);
    }
    Session forSse(CurrentAuthorityClient authority,String originalPortalSource,String run,long cursor,PortalRunEventsSse.Handle anchor) {
        Objects.requireNonNull(anchor).claimSession();
        return begin(authority,originalPortalSource,run,cursor,anchor);
    }
    private Session begin(CurrentAuthorityClient authority,String originalPortalSource,String run,long cursor,PortalRunEventsSse.Handle anchor) {
        Objects.requireNonNull(authority);
        var owner = PortalEventsPacketBudget.SHARED.acquire();
        PortalEventsPacketBudget.Reservation fee = null;
        GatewayEventsOpening opening = null;
        try {
            fee = owner.reserve(HTTP_BYTES);
            long t0 = System.nanoTime();
            opening = authority.beginGatewayPortalEvents(originalPortalSource, run, cursor);
            return new Session(tls, owner, fee, t0, opening, authority, originalPortalSource,anchor);
        } catch (RuntimeException | Error failure) {
            if (opening != null) opening.close();
            if (fee != null) fee.close();
            owner.close();
            throw failure;
        }
    }
    public static final class Session implements AutoCloseable {
        // Never cleared: closed Session/inner lease/ancestor stacks pin their unique shared credit.
        private final PortalRunEventsSse.Handle lifecycleAnchor;
        private final PortalEventsTls tls;
        private final PortalEventsPacketBudget.Owner owner;
        private PortalEventsPacketBudget.Reservation fee;
        private GatewayEventsOpening opening;
        private GatewayObservationQueryAuthorization query;
        private CurrentAuthorityClient authority;
        private String originalPortalSource;
        private PortalEventsPacketBudget.Reservation privateFee;
        private SavedFacts saved;
        private GatewayPortalEventsResponse gatewayResponse;
        private PortalEventsChildAuthorization child;
        private PortalEventsPacketDecoder decoder;
        private InputStream privateInput;
        private boolean qReady, privateUsed, privatePhase, bodyPhase;
        private long alarmEpoch;
        // Values are copied only from this Session's still-live Q facts before transfer.
        private record SavedFacts(String run, SubjectKey subject, ResourceBinding root, long versionId,
                long versionNo, String accepted, String initial, ResourceBinding snapshot, long cursor) {}
        private final long t0, openingDeadline, lifetimeDeadline;
        private long hopDeadline;
        private boolean used, active, stopped, closing, closed, transportClean, cleanupActive;
        private int transportStops;
        private Thread operationThread;
        private PortalEventsTlsConnection handle;
        private Socket raw;
        private SSLSocket ssl;
        private byte[] sslId;
        private HttpGet request;
        private HttpClientContext context;
        private PoolingHttpClientConnectionManager manager;
        private CloseableHttpClient client;
        private EventsMetadata facts;
        private ManagedHttpClientConnection connection;
        private ScheduledThreadPoolExecutor timer;
        private ScheduledFuture<?> alarm;
        private final IdentityHashMap<ClassicHttpRequest, Session> guards = new IdentityHashMap<>(2);
        private volatile Runnable beforeOutputForTest;
        private volatile java.util.function.Consumer<String> transportCheckpointForTest;
        void transportCheckpointForTest(java.util.function.Consumer<String> hook) { transportCheckpointForTest=hook; }
        private volatile boolean identityMatched;
        private Session(PortalEventsTls tls, PortalEventsPacketBudget.Owner owner, PortalEventsPacketBudget.Reservation fee, long t0, GatewayEventsOpening opening, CurrentAuthorityClient authority, String originalPortalSource,PortalRunEventsSse.Handle lifecycleAnchor) {
            this.lifecycleAnchor=lifecycleAnchor;
            this.tls = tls;
            this.owner = owner;
            this.fee = fee;
            this.t0 = t0;
            this.opening = opening;
            this.authority = authority;
            this.originalPortalSource = originalPortalSource;
            openingDeadline = t0 + TimeUnit.SECONDS.toNanos(5);
            lifetimeDeadline = t0 + TimeUnit.SECONDS.toNanos(55);
            query = opening.queryAuthorization();
        }
        private void live() {
            if (privatePhase) { livePrivate(!bodyPhase); return; }
            synchronized (this) {
                if (stopped || closed || System.nanoTime() - Math.min(openingDeadline, hopDeadline == 0 ? openingDeadline : hopDeadline) >= 0) throw unavailable();
            }
            if (opening.queryAuthorization() != query) throw unavailable();
            opening.publicRunId();
            query.requireVerified();
        }
        public EventsMetadata lookupMetadata() {
            synchronized (this) {
                if (used || closing || closed) throw unavailable();
                used = true;
                active = true;
                operationThread = Thread.currentThread();
                hopDeadline = Math.min(openingDeadline, Math.min(lifetimeDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2)));
            }
            try {
                live();
                build();
                live();
                String ticket = query.ticket().value();
                ascii(ticket, 16384);
                request = new HttpGet("https://" + tls.hostname + ":" + tls.port + "/internal/runs/" + opening.publicRunId() + "/observation-metadata");
                request.setVersion(HttpVersion.HTTP_1_1);
                request.setHeader("Authorization", "Bearer " + ticket);
                request.setHeader("Accept", "application/json");
                request.setHeader("Accept-Encoding", "identity");
                request.setHeader("Connection", "close");
                context = HttpClientContext.create();
                facts = client.execute(request, context, response -> {
                    try {
                        live();
                        checkResponse(response);
                        var entity = response.getEntity();
                        if (entity == null) throw unavailable();
                        byte[] body = new byte[8193];
                        int count = 0;
                        var in = entity.getContent();
                        for (;;) {
                            live();
                            int n = in.read(body, count, body.length - count);
                            if (n < 0) break;
                            if (n == 0) throw unavailable();
                            count += n;
                            if (count > 8192) throw unavailable();
                        }
                        live();
                        byte[] bounded = Arrays.copyOf(body, count);
                        if (response.getCode() != 200) throw RunObservationMetadataClient.parseEventsFailure(opening, response.getCode(), bounded);
                        var parsed = RunObservationMetadataClient.parseEvents(opening, bounded);
                        live();
                        return parsed;
                    } finally {
                        stopTransport();
                    }
                    // Abort before Apache closes a body: never drain an unknown remainder.
                });
                if (!finishTransport(Duration.ofSeconds(2))) throw unavailable();
                liveAfterHttp();
                synchronized (this) {
                    if (stopped || closing || closed) throw unavailable();
                    qReady = true;
                    return facts;
                }
            } catch (PlatformException failure) {
                abort();
                throw failure;
            } catch (Exception failure) {
                abort();
                throw unavailable();
            } finally {
                stopTransport();
                boolean finished;
                try {
                    finished = finishTransport(Duration.ofSeconds(2));
                } finally {
                    synchronized (this) {
                        active = false;
                        operationThread = null;
                        notifyAll();
                    }
                }
                if (!finished) throw unavailable();
            }
        }
        @FunctionalInterface interface WindowConsumer { void accept(PortalEventsMaterialVerifier.VerifiedWindow window) throws IOException; }
        private String materialChild;
        private volatile java.util.function.BiConsumer<String,Integer> materialCheckpointForTest;
        void materialCheckpointForTest(java.util.function.BiConsumer<String,Integer> hook) { materialCheckpointForTest = hook; }
        void consumeVerified(WindowConsumer consumer) {
            Objects.requireNonNull(consumer);
            consumePrivate(packet -> {
                // F1's scan fee has actually exited. Keep this fee through parser/borrow/consumer exit.
                try (var materialFee = owner.reserve(PortalEventsMaterialVerifier.MATERIAL_BYTES)) {
                    var context = new MaterialContext(packet);
                    try { new PortalEventsMaterialVerifier(context).consume(consumer); }
                    finally { context.valid = false; }
                }
            });
        }
        /** Only this Session can construct a binding. No caller may combine foreign facts/G/Owner. */
        final class MaterialContext {
            private final PortalEventsPacketDecoder.UntrustedPacket packet;
            private volatile boolean valid = true;
            private MaterialContext(PortalEventsPacketDecoder.UntrustedPacket packet) { this.packet = packet; }
            void live() { if (!valid) throw unavailable(); Session.this.live(); packet.header(); }
            PortalEventsPacketDecoder.UntrustedPacket packet() { live(); return packet; }
            GatewayPortalEventsResponse.Capture capture() { live(); return current(); }
            void requireLive(GatewayPortalEventsResponse.Capture capture) { live(); gatewayResponse.requireLive(capture); }
            boolean superseded(GatewayPortalEventsResponse.Capture capture) { live(); return gatewayResponse.isSuperseded(capture); }
            void require(GatewayPortalEventsResponse.Capture capture, MaterialScopeV1 scope) { requireLive(capture); capture.requireMaterial(scope); }
            void checkpoint(String phase, int index) {
                live(); var hook = materialCheckpointForTest; if (hook != null) hook.accept(phase, index); live();
            }
            void acceptReceipt(String signed) { live(); gatewayResponse.acceptChildReceipt(signed); live(); }
            void bindChild(String childId) { live(); if (materialChild != null) throw unavailable(); materialChild = childId; }
            String childId() { live(); if (materialChild == null) throw unavailable(); return materialChild; }
            String run() { live(); return saved.run(); }
            SubjectKey subject() { live(); return saved.subject(); }
            ResourceBinding root() { live(); return saved.root(); }
            ResourceBinding snapshot() { live(); return saved.snapshot(); }
            long versionId() { live(); return saved.versionId(); }
            long versionNo() { live(); return saved.versionNo(); }
            String accepted() { live(); return saved.accepted(); }
            String initial() { live(); return saved.initial(); }
            long initialCursor() { live(); return saved.cursor(); }
            long cursor() { live(); synchronized(Session.this) { return sseLease==null?saved.cursor():publicAfter; } }
            void confirm(GatewayPortalEventsResponse.Capture capture,long seq) {
                requireLive(capture);
                if(superseded(capture))throw unavailable();
                synchronized(Session.this) {
                    if(sseLease==null||sseLease.released||seq<=publicAfter)throw unavailable();
                    publicAfter=seq;
                }
            }
        }
        private SseLease sseLease;
        private long publicAfter;
        /** Session-minted resource lease. No G/Owner/proof escapes, and no caller supplied cursor. */
        synchronized SseLease beginSse() {
            if(sseLease!=null||privateUsed||stopped||closing||closed||!qReady)throw unavailable();
            long cursor=opening.effectiveCursor(); // Validate before publishing a lease that needs a State exit callback.
            var charge=owner.reserve(1909574L); // all C2 allowances incl one128B reservation token =1909702.
            try { sseLease=new SseLease(charge); publicAfter=cursor; return sseLease; }
            catch(RuntimeException|Error failure) { charge.close(); throw failure; }
        }
        final class SseLease {
            private PortalEventsPacketBudget.Reservation charge;
            private boolean released;
            private Runnable cancellation;
            private java.util.function.Function<Duration,Boolean> exit;
            private SseLease(PortalEventsPacketBudget.Reservation charge) { this.charge=charge; }
            long deadline() { return lifetimeDeadline; }
            synchronized void cancellation(Runnable signal) { if(cancellation!=null||released)throw unavailable();cancellation=Objects.requireNonNull(signal); }
            synchronized void exits(java.util.function.Function<Duration,Boolean> signal) { if(exit!=null||released)throw unavailable();exit=Objects.requireNonNull(signal); }
            void signal() { Runnable callback;synchronized(this) { callback=cancellation; }if(callback!=null)callback.run(); }
            void reap(Duration timeout) { java.util.function.Function<Duration,Boolean> callback;synchronized(this) { callback=exit; }if(callback!=null)callback.apply(timeout); }
            void release() {
                synchronized(this) { cancellation=null;exit=null; }
                synchronized(Session.this) { if(!released) { charge.close();charge=null;released=true;Session.this.notifyAll(); } }
            }
        }
        @FunctionalInterface interface PacketConsumer { void accept(PortalEventsPacketDecoder.UntrustedPacket packet) throws IOException; }
        void consumePrivate(PacketConsumer consumer) {
            Objects.requireNonNull(consumer);
            synchronized (this) {
                if (!qReady || active || privateUsed || stopped || closing || closed || !transportClean) throw unavailable();
                privateUsed = true;
                active = true;
                operationThread = Thread.currentThread();
            }
            try {
                // Lazy admission: Q-only owners retain the original 3211392B price.
                privateFee = owner.reserve(PRIVATE_BYTES);
                if (!(authority instanceof ResponseAuthorityClient responseAuthority)) throw unavailable();
                liveOpening();
                saved = new SavedFacts(facts.runId(), facts.owner(), facts.root(), facts.appVersionId(), facts.appVersionNo(),
                        facts.acceptedSnapshotSha256(), facts.initialCeilingSha256(), facts.snapshot(), opening.effectiveCursor());
                liveOpening();
                facts.close();
                facts = null;
                var g = responseAuthority.openGatewayPortalEvents(opening, saved.snapshot(), this::abort);
                synchronized (this) { gatewayResponse = g; }
                // Transfer invalidates Opening/Q getters; from here only the same G is consulted.
                if (System.nanoTime() - openingDeadline >= 0) throw unavailable();
                privatePhase = true;
                opening.close();
                opening = null;
                query = null;
                current();
                try {
                    var issued = authority.exchangePortalEventsChild(originalPortalSource, g);
                    synchronized (this) { child = issued; }
                } finally { originalPortalSource = null; }
                synchronized (this) {
                    if (stopped || closing || closed) throw unavailable();
                    hopDeadline = Math.min(lifetimeDeadline, Math.min(System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                            System.nanoTime() + current().remainingNanos()));
                    transportClean = false;
                }
                live();
                build();
                live();
                String ticket = child.ticket().value();
                ascii(ticket, 16384);
                request = new HttpGet("https://" + tls.hostname + ":" + tls.port + "/admin/runs/" + saved.run()
                        + "/events?Last-Event-ID=" + Long.toString(saved.cursor()));
                request.setVersion(HttpVersion.HTTP_1_1);
                request.setHeader("Authorization", "Bearer " + ticket);
                request.setHeader("Accept", "application/vnd.agent-platform.portal-events.v1");
                request.setHeader("Accept-Encoding", "identity");
                request.setHeader("Connection", "close");
                context = HttpClientContext.create();
                client.execute(request, context, response -> {
                    try {
                        live();
                        checkResponse(response, true);
                        var entity = response.getEntity();
                        if (entity == null) throw unavailable();
                        enterBody();
                        if (response.getCode() != 200) {
                            byte[] body = new byte[8193];
                            int count = 0;
                            var in = entity.getContent();
                            for (;;) {
                                live();
                                int n = in.read(body, count, body.length - count);
                                live();
                                if (n < 0) break;
                                if (n == 0 || (count += n) > 8192) throw unavailable();
                            }
                            live();
                            String code = RunObservationMetadataClient.parsePrivateEventsErrorCode(Arrays.copyOf(body, count));
                            live();
                            int status = response.getCode();
                            if (!(status == 400 && code.equals("EVENT_CURSOR") || status == 404 && code.equals("RUN_NOT_FOUND")
                                    || status == 429 && code.equals("STREAM_CAPACITY") || status == 413 && Set.of("EVENT_LIMIT", "BUFFER_LIMIT").contains(code)
                                    || status == 403 && Set.of("AUTHORITY_EXPIRED", "AUTHORITY_CONTEXT_MISSING", "RUN_AUTHORITY_DENIED", "MATERIAL_SCOPE_MISSING_OR_CHANGED", "SYS-AUTH-403", "AUTHORITY_DENIED").contains(code))) throw unavailable();
                            throw new PlatformException(status, code, "Private events request rejected");
                        }
                        privateInput = new FilterInputStream(entity.getContent()) {
                            @Override public int read() throws IOException { live(); int n = in.read(); live(); return n; }
                            @Override public int read(byte[] b, int off, int len) throws IOException { live(); int n = in.read(b, off, len); live(); return n; }
                            @Override public void close() { stopTransport(); }
                        };
                        decoder = new PortalEventsPacketDecoder(owner);
                        for (;;) {
                            live();
                            var next = decoder.readNext(privateInput);
                            if (next.isEmpty()) { live(); break; }
                            try (var packet = next.orElseThrow()) {
                                live();
                                consumer.accept(packet);
                                live();
                            }
                        }
                        return null;
                    } finally { stopTransport(); }
                });
            } catch (PlatformException failure) {
                abort(); throw failure;
            } catch (Exception failure) {
                abort(); throw unavailable();
            } finally {
                originalPortalSource = null;
                stopTransport();
                try { finishTransport(Duration.ofSeconds(2)); }
                finally {
                    boolean requestedClose;
                    synchronized (this) { active = false; operationThread = null; requestedClose = closing; notifyAll(); }
                    // A cancelled transfer must close a late G even when its caller already exhausted the close wait.
                    if (requestedClose && sseLease==null) awaitClosed(Duration.ofSeconds(2));
                }
            }
        }
        private void liveOpening() {
            synchronized (this) { if (stopped || closing || closed || System.nanoTime() - openingDeadline >= 0) throw unavailable(); }
            if (opening.queryAuthorization() != query) throw unavailable();
            opening.publicRunId(); query.requireVerified();
        }
        private GatewayPortalEventsResponse.Capture current() {
            synchronized (this) { if (stopped || closing || closed || System.nanoTime() - lifetimeDeadline >= 0 || gatewayResponse == null) throw unavailable(); }
            var capture = gatewayResponse.currentCapture();
            if (!saved.subject().equals(capture.subject()) || !saved.snapshot().equals(capture.resource())
                    || saved.cursor() != capture.initialCursor()) throw unavailable();
            gatewayResponse.requireLive(capture);
            if (gatewayResponse.isSuperseded(capture)) throw unavailable();
            return capture;
        }
        private void livePrivate(boolean head) {
            current();
            if (head) {
                if (hopDeadline != 0 && System.nanoTime() - hopDeadline >= 0 || child == null) throw unavailable();
                child.requireVerified();
            }
        }
        private void enterBody() {
            livePrivate(true);
            synchronized (this) {
                if (stopped || closing || closed || bodyPhase) throw unavailable();
                bodyPhase = true;
                arm(lifetimeDeadline);
            }
            live();
        }
        private void arm(long deadline) {
            long epoch = ++alarmEpoch;
            if (alarm != null) alarm.cancel(false);
            alarm = timer.schedule(() -> {
                synchronized (Session.this) {
                    if (epoch != alarmEpoch || stopped || closed) return;
                }
                abort();
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        private void liveAfterHttp() {
            if (System.nanoTime() - hopDeadline >= 0) throw unavailable();
            // Successful Q close deliberately stops only the physical hop, not its retained Opening.
            if (opening.queryAuthorization() != query) throw unavailable();
            opening.publicRunId();
            query.requireVerified();
        }
        private void build() {
            // One timer task for one absolute hop; no per-read scheduling or application worker queue.
            timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().inheritInheritableThreadLocals(false).name("portal-events-http-deadline").factory());
            timer.setRemoveOnCancelPolicy(true);
            timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            arm(hopDeadline);
            var builder = new PoolingHttpClientConnectionManagerBuilder() {
                @Override protected HttpClientConnectionOperator createConnectionOperator(SchemePortResolver ignored, DnsResolver ignoredDns, TlsSocketStrategy ignoredTls) {
                    var dns = new DnsResolver() {
                        public InetAddress[] resolve(String h) throws UnknownHostException {
                            if (!tls.hostname.equals(h)) throw new UnknownHostException("Fixed TLS host required");
                            return new InetAddress[] {
                                tls.address
                            };
                        }
                        public String resolveCanonicalHostname(String h) throws UnknownHostException {
                            resolve(h);
                            return tls.hostname;
                        }
                    };
                    return new DefaultHttpClientConnectionOperator(proxy -> {
                        if (proxy != null) throw new IOException("Proxy prohibited");
                        live();
                        // E's TLS hard anchor is the original lifetime. The phase timer owns the shorter head
                        // bound; freezing checked_until/head into this handle would kill healthy renewed G bodies.
                        var acquired = tls.acquire(privatePhase ? lifetimeDeadline : Math.min(lifetimeDeadline, hopDeadline));
                        if (!(acquired instanceof PortalEventsTlsConnection c) || !c.belongsTo(tls)) {
                            acquired.abort();
                            throw new IOException("Foreign TLS handle");
                        }
                        synchronized (Session.this) {
                            if (stopped || handle != null) {
                                c.abort();
                                throw new IOException("TLS installation stopped");
                            }
                            handle = c;
                        }
                        try {
                            var socket = c.rawSocketForConnect();
                            synchronized (Session.this) {
                                if (stopped) {
                                    c.abort();
                                    throw new IOException("TLS installation stopped");
                                }
                                raw = socket;
                            }
                            return socket;
                        } catch (RuntimeException | IOException e) {
                            c.abort();
                            throw e;
                        }
                    }, endpoint -> {
                        if (!"https".equals(endpoint.getSchemeName()) || !tls.hostname.equals(endpoint.getHostName()) || endpoint.getPort() != tls.port) throw new IllegalArgumentException("Fixed HTTPS route required");
                        return tls.port;
                    }, dns, scheme -> "https".equals(scheme) ?(socket, host, port, attachment, ctx) -> {
                        if (socket != raw || !tls.hostname.equals(host) || port != tls.port || ctx != context) throw new IOException("TLS upgrade identity rejected");
                        live();
                        var verified = handle.upgradeConnectedSocket();
                        live();
                        synchronized (Session.this) {
                            if (stopped) {
                                handle.abort();
                                throw new IOException("TLS upgrade stopped");
                            }
                            ssl = verified;
                            sslId = verified.getSession().getId();
                        }
                        return verified;
                    }
                    : null);
                }
            };
            manager = builder.setConnectionFactory(attachment -> new QuietConnection(this)).setMaxConnTotal(1).setMaxConnPerRoute(1).setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.ofMilliseconds(2000)).setSoLinger(TimeValue.NEG_ONE_MILLISECOND).build()).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(1000)).setSocketTimeout(Timeout.ofMilliseconds(2000)).setValidateAfterInactivity(TimeValue.NEG_ONE_MILLISECOND).build()).build();
            client = HttpClients.custom().setConnectionManager(manager).setConnectionReuseStrategy((r, s, c) -> false).setRoutePlanner((host, ctx) -> {
                if (ctx != context || !"https".equals(host.getSchemeName()) || !tls.hostname.equals(host.getHostName()) || host.getPort() != tls.port || host.getAddress() != null) throw new HttpException("Fixed route required");
                return new HttpRoute(host, null, true);
            }).disableAutomaticRetries().disableRedirectHandling().disableContentCompression().disableCookieManagement().disableAuthCaching().disableConnectionState().disableDefaultUserAgent().setDefaultCookieSpecRegistry(name -> null).setDefaultAuthSchemeRegistry(name -> null).setDefaultCredentialsProvider((scope, ctx) -> null).setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false).setProtocolUpgradeEnabled(false).setHardCancellationEnabled(true).setConnectionRequestTimeout(Timeout.ofMilliseconds(1)).setResponseTimeout(Timeout.ofMilliseconds(2000)).build()).setRequestExecutor(new HttpRequestExecutor() {
                @Override public ClassicHttpResponse execute(ClassicHttpRequest actual, HttpClientConnection conn, HttpResponseInformationCallback callback, HttpContext ctx) throws IOException, HttpException {
                    check(actual, conn, ctx);
                    synchronized (guards) {
                        if (guards.containsKey(actual) || guards.size() >= 2) throw new IOException("Request identity capacity");
                        guards.put(actual, Session.this);
                    }
                    try {
                        return super.execute(actual, conn, callback, ctx);
                    } finally {
                        synchronized (guards) {
                            guards.remove(actual);
                        }
                    }
                }
            }).build();
        }
        private void check(ClassicHttpRequest actual, HttpClientConnection conn, HttpContext ctx) throws IOException {
            live();
            if (ctx != context || !(conn instanceof QuietConnection connection) || connection.session != this || connection.getSocket() != ssl || ssl == null) throw new IOException("Request connection identity rejected");
            var session = ssl.getSession();
            if (!session.isValid() || !PortalEventsTls.PROTOCOL.equals(session.getProtocol()) || !PortalEventsTls.CIPHER.equals(session.getCipherSuite()) || !tls.hostname.equals(session.getPeerHost()) || session.getPeerPort() != tls.port || !Arrays.equals(sslId, session.getId())) throw new IOException("TLS session identity rejected");
            if (!"GET".equals(actual.getMethod()) || actual.getEntity() != null || !HttpVersion.HTTP_1_1.equals(actual.getVersion()) || !actual.getRequestUri().equals(privatePhase ? "/admin/runs/" + saved.run() + "/events?Last-Event-ID=" + Long.toString(saved.cursor()) : "/internal/runs/" + opening.publicRunId() + "/observation-metadata")) throw new IOException("Fixed request required");
            var headers = actual.getHeaders();
            if (headers.length > 16) throw new IOException("Request header count");
            var names = new HashSet<String>();
            for (var h : headers) {
                String name = h.getName().toLowerCase(Locale.ROOT);
                if (!names.add(name)) throw new IOException("Duplicate request header");
                String expected = switch (name) {
                    case "authorization" -> "Bearer " + (privatePhase ? child.ticket().value() : query.ticket().value());
                    case "accept" -> privatePhase ? "application/vnd.agent-platform.portal-events.v1" : "application/json";
                    case "accept-encoding" -> "identity";
                    case "host" -> tls.hostname + ":" + tls.port;
                    case "connection" -> "close";
                    default -> throw new IOException("Unexpected request header");
                };
                if (!expected.equals(h.getValue())) throw new IOException("Fixed request header required");
            }
            if (!names.equals(Set.of("authorization", "accept", "accept-encoding", "host", "connection"))) throw new IOException("Missing request header");
            requestOctets(actual);
            live();
        }
        private void writerCheck(ClassicHttpRequest actual, QuietConnection conn) throws IOException {
            synchronized (guards) {
                if (guards.get(actual) != this) throw new IOException("Missing original request identity");
            }
            check(actual, conn, context);
            identityMatched = true;
        }
        private void stopTransport() {
            synchronized (this) {
                // An elected cleanup already owns the abort wakeup. Other admitted stops
                // remain concurrent so an earlier stalled canceller cannot strand real IO.
                if (transportClean || cleanupActive || transportStops == Integer.MAX_VALUE) return;
                transportStops++;
            }
            stopTransportReferences();
            synchronized (this) { transportStops--; notifyAll(); }
        }
        private boolean stopTransportReferences() {
            try {
                PortalEventsTlsConnection c;
                HttpGet r;
                synchronized (this) { c=handle; r=request; }
                var hook=transportCheckpointForTest;
                if(hook!=null&&c!=null&&r!=null)hook.accept("stop-snapshot");
                if(c!=null)c.abort();
                if(r!=null)r.cancel();
                return true;
            } catch (RuntimeException | Error failure) {
                // This narrow boundary fails closed: no Throwable crosses the actual
                // heavy-helper return. Original transport fields remain for cleanup retry.
                return false;
            }
        }
        public void abort() {
            SseLease owned;
            synchronized (this) {
                stopped = true;
                owned=sseLease;
            }
            if(owned!=null)owned.signal();
            stopTransport();
        }
        private boolean finishTransport(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (this) {
                while (cleanupActive || transportStops != 0) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) return false;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (active && operationThread != Thread.currentThread() || transportClean) return transportClean;
                // Admission and election share one lock; no new stop snapshot can enter.
                cleanupActive = true;
            }
            boolean cleaned=finishTransportReferences(deadline);
            // Only Session/primitive locals survive this actual inner return. Publishing
            // clean in the heavy helper's finally would still expose its reference frame.
            synchronized (this) {
                if(cleaned)transportClean=true;
                cleanupActive=false;
                notifyAll();
                return cleaned;
            }
        }
        private boolean finishTransportReferences(long deadline) {
            try {
                PortalEventsTlsConnection closingHandle;
                HttpGet closingRequest;
                ScheduledFuture<?> closingAlarm;
                ScheduledThreadPoolExecutor closingTimer;
                CloseableHttpClient closingClient;
                PoolingHttpClientConnectionManager closingManager;
                java.util.function.Consumer<String> hook;
                synchronized (this) {
                    closingHandle=handle; closingRequest=request; closingAlarm=alarm;
                    closingTimer=timer; closingClient=client; closingManager=manager;
                    hook=transportCheckpointForTest;
                }
                if(hook!=null)hook.accept("cleanup-snapshot");
                if (closingHandle != null) closingHandle.abort();
                if (closingRequest != null) closingRequest.cancel();
                if (closingAlarm != null) closingAlarm.cancel(false);
                if (closingTimer != null) closingTimer.shutdown();
                // Physical waits and library close must leave both Session and identity locks available to abort.
                if (closingHandle != null && !closingHandle.awaitClosed(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) return false;
                if (closingTimer != null && !closingTimer.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return false;
                if (closingClient != null) closingClient.close(CloseMode.IMMEDIATE);
                else if (closingManager != null) closingManager.close(CloseMode.IMMEDIATE);
                if(hook!=null)hook.accept("cleanup-tail");
                synchronized (this) {
                    client = null;
                    manager = null;
                    connection = null;
                    request = null;
                    context = null;
                    ssl = null;
                    sslId = null;
                    raw = null;
                    handle = null;
                    alarm = null;
                    timer = null;
                    beforeOutputForTest = null;
                    transportCheckpointForTest = null;
                    synchronized (guards) {
                        guards.clear();
                    }
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException | Error failure) {
                // Includes test probes and close failures: retain original fields, keep
                // transport dirty, and allow a later bounded retry without a heavy Throwable.
                return false;
            }
        }
        public boolean awaitClosed(Duration timeout) {
            if (timeout.isNegative()) throw new IllegalArgumentException("Negative close wait");
            long deadline = System.nanoTime() + timeout.toNanos();
            SseLease owned;
            synchronized(this) { if(closed)return true;if(!closing)return false;owned=sseLease; }
            // A failed finite onComplete wait may be retried by the original Session's owner.
            // This call holds no Session monitor and cannot bypass the heavy/worker/completion gate.
            if(owned!=null&&!owned.released)owned.reap(Duration.ofNanos(Math.max(0,deadline-System.nanoTime())));
            synchronized (this) {
                if (closed) return true;
                if (!closing) return false;
                while (active || sseLease!=null&&!sseLease.released) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) return false;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            if (!finishTransport(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())))) return false;
            synchronized (this) {
                if (closed) return true;
                // Refuse release if a callback retained a segment or decoder work remains live.
                try { if (decoder != null) decoder.close(); } catch (RuntimeException stillBorrowed) { return false; }
                decoder = null;
                privateInput = null;
                if (gatewayResponse != null) gatewayResponse.close();
                gatewayResponse = null;
                child = null;
                saved = null;
                materialChild = null;
                materialCheckpointForTest = null;
                authority = null;
                originalPortalSource = null;
                if (opening != null) opening.close();
                opening = null;
                query = null;
                if (facts != null) facts.close();
                facts = null;
                if (privateFee != null) privateFee.close();
                privateFee = null;
                fee.close();
                fee = null;
                owner.close();
                closed = true;
                notifyAll();
                return true;
            }
        }
        @Override public void close() {
            synchronized (this) {
                closing = true;
            }
            abort();
            awaitClosed(Duration.ofSeconds(2));
        }
        // Mechanical blocking only; cannot install a different ticket, socket, URL, or proof.
        void beforeOutputForTest(Runnable hook) {
            beforeOutputForTest = hook;
        }
    }
    static int requestOctets(ClassicHttpRequest r) throws IOException {
        int bytes = ascii(r.getMethod(), 16) + 1 + ascii(r.getRequestUri(), 512) + 1 + ascii(r.getVersion().toString(), 16) + 2;
        for (var h : r.getHeaders()) bytes = Math.addExact(bytes, ascii(h.getName(), 32768) + 2 + ascii(h.getValue(), 32768) + 2);
        bytes = Math.addExact(bytes, 2);
        if (bytes > 32768) throw new IOException("Request head limit");
        return bytes;
    }
    private static int ascii(String value, int limit) throws IOException {
        if (value == null || value.length() > limit) throw new IOException("ASCII field limit");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126) throw new IOException("ASCII field rejected");
        }
        return value.length();
    }
    private static void checkResponse(ClassicHttpResponse response) throws IOException { checkResponse(response, false); }
    private static void checkResponse(ClassicHttpResponse response, boolean events) throws IOException {
        if (!HttpVersion.HTTP_1_1.equals(response.getVersion()) || !(events ? Set.of(200, 400, 403, 404, 413, 429) : Set.of(200, 403, 404)).contains(response.getCode())) throw new IOException("Response status rejected");
        var counts = new HashMap<String, Integer>();
        String cl = null, te = null, type = null;
        for (var h : response.getHeaders()) {
            String name = h.getName().toLowerCase(Locale.ROOT);
            int n = counts.merge(name, 1, Integer::sum);
            if (Set.of("content-encoding", "set-cookie", "location", "trailer", "www-authenticate", "proxy-authenticate").contains(name)) throw new IOException("Response header rejected");
            if (Set.of("content-length", "transfer-encoding", "content-type").contains(name) && n != 1) throw new IOException("Duplicate response framing");
            if (name.equals("content-length")) cl = h.getValue();
            if (name.equals("transfer-encoding")) te = h.getValue();
            if (name.equals("content-type")) type = h.getValue();
        }
        if (type == null || !(events && response.getCode() == 200 ? type.equals("application/vnd.agent-platform.portal-events.v1") : type.matches("(?i)application/json(?:;[ ]*charset=UTF-8)?")) || cl != null && te != null || cl == null && te == null) throw new IOException("Response framing rejected");
        if (cl != null) {
            if (!cl.matches("0|[1-9][0-9]{0,18}")) throw new IOException("Content length rejected");
            try {
                if (Long.parseLong(cl) > 8192 && !(events && response.getCode() == 200)) throw new IOException("Body limit");
            } catch (NumberFormatException e) {
                throw new IOException("Content length rejected");
            }
        }
        if (te != null && !te.equalsIgnoreCase("chunked")) throw new IOException("Transfer coding rejected");
    }
    /** Core5.3.4 standard parser/writer/holder, without DefaultManaged's header or wire hooks. */
    private static final class QuietConnection extends DefaultBHttpClientConnection implements ManagedHttpClientConnection {
        final Session session;
        private boolean terminated;
        QuietConnection(Session session) {
            super(Http1Config.custom().setBufferSize(8192).setMaxLineLength(8192).setMaxHeaderCount(1).setMaxEmptyLineCount(2).build(), null, null, null, null, () -> {
                var delegate = DefaultHttpRequestWriterFactory.INSTANCE.create();
                return (actual, buffer, out) -> {
                    session.writerCheck(actual, (QuietConnection) sessionConnection(session));
                    delegate.write(actual, buffer, out);
                };
            }, config -> {
                var delegate = new org.apache.hc.core5.http.impl.io.DefaultHttpResponseParser(Http1Config.custom().setMaxLineLength(8192).setMaxHeaderCount(64).setMaxEmptyLineCount(2).build());
                return (buffer, in) -> {
                    try {
                        var response = delegate.parse(new HeadBuffer(buffer), in);
                        checkResponse(response, session.privatePhase);
                        return response;
                    } catch (IOException | HttpException | RuntimeException e) {
                        session.stopTransport();
                        throw e;
                    }
                };
            });
            this.session = session;
        }
        // The unique connection is set by bind before any executor/writer invocation.
        private static ManagedHttpClientConnection sessionConnection(Session session) {
            return session.connection;
        }
        @Override public void bind(Socket socket) throws IOException {
            if (terminated) throw new IOException("Connection exited");
            super.bind(socket);
            session.connection = this;
        }
        @Override public void bind(SSLSocket ssl, Socket raw) throws IOException {
            if (terminated) throw new IOException("Connection exited");
            super.bind(new SocketHolder(ssl, raw) {
                @Override protected OutputStream getOutputStream(Socket socket) throws IOException {
                    Runnable hook = session.beforeOutputForTest;
                    if (hook != null) hook.run();
                    return super.getOutputStream(socket);
                }
            });
            session.connection = this;
        }
        @Override public Socket getSocket() {
            var holder = getSocketHolder();
            return holder == null ? null : holder.getSocket();
        }
        @Override public void setSocketTimeout(Timeout timeout) {
            if (!(getSocket() instanceof SSLSocket)) super.setSocketTimeout(timeout);
            /* SafeSsl retains F2a15000ms; absolute hop timer aborts earlier. */
        }
        @Override public void activate() {
            if (terminated) throw new IllegalStateException("Connection exited");
        }
        @Override public void passivate() {
            session.stopTransport();
        }
        @Override public void close() {
            close(CloseMode.IMMEDIATE);
        }
        @Override public void close(CloseMode mode) {
            terminated = true;
            session.stopTransport();
            super.close(CloseMode.IMMEDIATE);
        }
    }
    /** Charge only parsed lines, including rejected line, before standard parser retains them. */
    private static final class HeadBuffer implements SessionInputBuffer {
        final SessionInputBuffer input;
        int total;
        HeadBuffer(SessionInputBuffer input) {
            this.input = input;
        }
        public int readLine(CharArrayBuffer line, InputStream stream) throws IOException {
            int start = line.length();
            int n = input.readLine(line, stream);
            if (n >= 0) {
                int length = line.length() - start;
                total = Math.addExact(total, length + 2);
                if (total > 8192) throw new IOException("Response head limit");
                if (length > 0 && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) throw new IOException("Folded response header");
                for (int i = start; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c < 32 || c == 127) throw new IOException("Response head control");
                }
            }
            return n;
        }
        public int length() {
            return input.length();
        }
        public int capacity() {
            return input.capacity();
        }
        public int available() {
            return input.available();
        }
        public int read(byte[] b, int off, int len, InputStream in) throws IOException {
            return input.read(b, off, len, in);
        }
        public int read(byte[] b, InputStream in) throws IOException {
            return input.read(b, in);
        }
        public int read(InputStream in) throws IOException {
            return input.read(in);
        }
        public HttpTransportMetrics getMetrics() {
            return input.getMetrics();
        }
    }
    private static PlatformException unavailable() {
        return new PlatformException(503, "RUN_METADATA_UNAVAILABLE", "运行观察元数据暂不可用");
    }
}
