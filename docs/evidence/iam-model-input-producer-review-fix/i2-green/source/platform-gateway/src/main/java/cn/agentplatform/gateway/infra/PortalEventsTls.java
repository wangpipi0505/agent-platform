package cn.agentplatform.gateway.infra;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Unassembled fixed-origin TLS transport. Two slots count physical, not logical, connections. */
public final class PortalEventsTls implements AutoCloseable {
    static final String PROTOCOL = "TLSv1.3";
    static final String CIPHER = "TLS_AES_128_GCM_SHA256";
    private static final Object SLOTS_LOCK = new Object();
    private static final PortalEventsTlsConnection[] SLOTS = new PortalEventsTlsConnection[2];
    private static final ThreadFactory DAEMON = r -> Thread.ofPlatform().daemon().name("portal-events-tls").unstarted(r);
    private static final ThreadPoolExecutor CLOSERS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), DAEMON, new ThreadPoolExecutor.AbortPolicy());
    static {
        // A fixed scan of two slots; no per-read/per-factory timers or unbounded cancel queue.
        Executors.newSingleThreadScheduledExecutor(DAEMON).scheduleAtFixedRate(() -> {
            PortalEventsTlsConnection[] current;
            synchronized (SLOTS_LOCK) { current = SLOTS.clone(); }
            for (var handle : current) if (handle != null) handle.expire(System.nanoTime());
        }, 50, 50, TimeUnit.MILLISECONDS);
    }
    final String hostname;
    final int port;
    final InetAddress address;
    private final TrustManager[] trust;
    private boolean closed;

    public PortalEventsTls(URI origin, byte[] literalAddress, Path caFile) throws IOException {
        Objects.requireNonNull(origin);
        Objects.requireNonNull(literalAddress);
        Objects.requireNonNull(caFile);
        String host = origin.getHost();
        if (!"https".equals(origin.getScheme()) || host == null || origin.getPort() < 1 || origin.getPort() > 65535
                || origin.getRawUserInfo() != null || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null
                || origin.getRawFragment() != null || host.length() > 253 || !host.matches("[A-Za-z0-9.-]+")
                || host.matches("[0-9.]+") || host.indexOf('.') < 0) throw new IOException("Invalid fixed TLS origin");
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                throw new IOException("Invalid fixed TLS DNS hostname");
        }
        if (literalAddress.length != 4 && literalAddress.length != 16) throw new IOException("Invalid literal TLS address");
        if (!"32768".equals(System.getProperty("jdk.tls.maxHandshakeMessageSize"))
                || !"4".equals(System.getProperty("jdk.tls.maxCertificateChainLength")))
            throw new IOException("TLS JVM startup profile declaration is required");
        // This rejects an explicitly incompatible declaration; only a fresh launcher can establish static initialization order.
        if (Boolean.parseBoolean(System.getProperty("com.sun.security.enableAIAcaIssuers")))
            throw new IOException("TLS profile prohibits enabled AIA issuer fetching");
        hostname = host;
        port = origin.getPort();
        address = InetAddress.getByAddress(literalAddress.clone());
        trust = loadTrust(caFile);
    }
    private static TrustManager[] loadTrust(Path file) throws IOException {
        if (Files.size(file) > 65536) throw new IOException("TLS CA file exceeds limit");
        byte[] bytes;
        try (var in = Files.newInputStream(file)) { bytes = in.readNBytes(65537); }
        if (bytes.length > 65536) throw new IOException("TLS CA file grew beyond limit");
        for (byte b : bytes) if (b < 0) throw new IOException("TLS CA PEM must be ASCII");
        String pem = new String(bytes, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN CERTIFICATE-----", end = "-----END CERTIFICATE-----";
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            var parser = CertificateFactory.getInstance("X.509");
            int offset = 0, count = 0;
            while (offset < pem.length()) {
                while (offset < pem.length() && Character.isWhitespace(pem.charAt(offset))) offset++;
                if (offset == pem.length()) break;
                if (!pem.startsWith(begin, offset) || ++count > 4) throw new IOException("TLS CA block/count invalid");
                int stop = pem.indexOf(end, offset + begin.length());
                if (stop < 0) throw new IOException("Incomplete TLS CA PEM");
                String payload = pem.substring(offset + begin.length(), stop);
                if (!payload.matches("[A-Za-z0-9+/=\\s]+")) throw new IOException("Invalid TLS CA base64");
                byte[] der = Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
                var input = new ByteArrayInputStream(der);
                var cert = (X509Certificate) parser.generateCertificate(input);
                if (input.available() != 0) throw new IOException("TLS CA DER trailing data");
                cert.checkValidity();
                boolean[] usage = cert.getKeyUsage();
                if (cert.getBasicConstraints() < 0 || usage != null && (usage.length <= 5 || !usage[5]))
                    throw new IOException("TLS trust material is not a signing CA");
                store.setCertificateEntry("ca-" + count, cert);
                offset = stop + end.length();
            }
            if (count == 0) throw new IOException("TLS CA file is empty");
            var tmf = TrustManagerFactory.getInstance("PKIX");
            var parameters = new PKIXBuilderParameters(store, new X509CertSelector());
            parameters.setRevocationEnabled(false);
            tmf.init(new CertPathTrustManagerParameters(parameters));
            return tmf.getTrustManagers();
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Invalid private TLS CA material", e);
        }
    }
    SSLContext newContext() throws IOException {
        try {
            var context = SSLContext.getInstance(PROTOCOL, "SunJSSE");
            context.init(null, trust, null);
            context.getClientSessionContext().setSessionCacheSize(2);
            context.getClientSessionContext().setSessionTimeout(55);
            return context;
        } catch (GeneralSecurityException e) { throw new IOException("Cannot initialize private TLS context", e); }
    }
    public Connection acquire(long callerDeadlineNanos) throws IOException {
        synchronized (SLOTS_LOCK) {
            if (closed) throw new IOException("TLS factory closed");
            long now = System.nanoTime();
            if (callerDeadlineNanos - now <= 0) throw new IOException("TLS caller deadline elapsed");
            for (int i = 0; i < SLOTS.length; i++) {
                if (SLOTS[i] == null) {
                    var connection = new PortalEventsTlsConnection(this, now, callerDeadlineNanos);
                    SLOTS[i] = connection;
                    return connection;
                }
            }
            throw new IOException("TLS physical slots exhausted");
        }
    }
    static void scheduleClose(PortalEventsTlsConnection connection) { CLOSERS.execute(connection::closeResources); }
    static void release(PortalEventsTlsConnection connection) {
        synchronized (SLOTS_LOCK) {
            for (int i = 0; i < SLOTS.length; i++) {
                if (SLOTS[i] == connection) SLOTS[i] = null;
            }
        }
    }
    public interface Connection extends AutoCloseable {
        SSLSocket connectAndHandshake() throws IOException;
        void abort();
        boolean awaitClosed(Duration timeout) throws InterruptedException;
        void close();
    }
    public void close() {
        PortalEventsTlsConnection[] current;
        synchronized (SLOTS_LOCK) { closed = true; current = SLOTS.clone(); }
        for (var connection : current) if (connection != null && connection.belongsTo(this)) connection.abort();
    }
}
