package cn.agentplatform.gateway.infra;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.security.Principal;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import javax.net.ssl.*;

final class PortalEventsTlsConnection implements PortalEventsTls.Connection {
    private final PortalEventsTls owner;
    private final PortalEventsTlsDebt debt = new PortalEventsTlsDebt();
    private final long hardDeadline;
    private final long openingDeadline;
    private long lastDelivery;
    private Socket bottom;
    private RawSocket rawView;
    private SSLContext context;
    private SSLSocket ssl;
    private SafeSsl publicView;
    private InputStream applicationInput;
    private OutputStream applicationOutput;
    private boolean stopped;
    private boolean closeScheduled;
    private boolean terminated;
    private boolean knownClosed;
    private boolean knownInputShutdown;
    private boolean knownOutputShutdown;
    private boolean upgradeUsed;
    private boolean connectUsed;
    private boolean verified;
    private int active;
    private IOException closeFailure;
    private Thread closeThread;
    private final AtomicBoolean reading = new AtomicBoolean();
    private final AtomicBoolean writing = new AtomicBoolean();

    PortalEventsTlsConnection(PortalEventsTls owner, long now, long callerDeadline) {
        this.owner = owner;
        hardDeadline = now + Math.min(Duration.ofSeconds(55).toNanos(), callerDeadline - now);
        openingDeadline = now + Math.min(Duration.ofSeconds(2).toNanos(), hardDeadline - now);
    }
    boolean belongsTo(PortalEventsTls factory) { return owner == factory; }
    PortalEventsTlsDebt.Snapshot snapshot() { return debt.snapshot(); }
    synchronized long hardDeadlineNanos() {
        return hardDeadline;
    }
    synchronized long openingDeadlineNanos() {
        return openingDeadline;
    }
    private void checkLive() throws IOException {
        if (stopped) throw new IOException("TLS connection stopped", closeFailure);
    }
    private synchronized void begin() throws IOException {
        checkLive();
        active++;
    }
    private synchronized void end() {
        active--;
        notifyAll();
    }
    static int timeoutMillis(long deadline, int maximum) throws IOException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new IOException("TLS deadline elapsed");
        return (int) Math.min(maximum, (left + 999999) / 1000000);
    }
    void expire(long now) {
        boolean expired;
        synchronized (this) {
            expired = !stopped && (now - hardDeadline >= 0 || !verified && now - openingDeadline >= 0
                    || verified && now - lastDelivery >= Duration.ofSeconds(15).toNanos());
        }
        if (expired) abort();
    }
    synchronized Socket rawSocketForConnect() throws IOException {
        checkLive();
        if (bottom == null) {
            // No hostname resolver, default SOCKS selection, channel or caller-supplied socket.
            bottom = new Socket(Proxy.NO_PROXY);
            rawView = new RawSocket(bottom);
        }
        return rawView;
    }
    // Mechanical lifecycle seam only. It cannot create a verified TLS connection.
    synchronized void installRawForTest(Socket socket) throws IOException {
        checkLive();
        if (bottom != null || terminated) throw new IOException("TLS raw socket already installed or exited");
        bottom = socket;
        rawView = new RawSocket(socket);
    }
    SSLSocket upgradeConnectedSocket() throws IOException {
        begin();
        try {
            RawSocket raw;
            synchronized (this) {
                checkLive();
                if (upgradeUsed || bottom == null || !bottom.isConnected()) throw new IOException("TLS upgrade state invalid");
                upgradeUsed = true;
                raw = rawView;
            }
            var freshContext = owner.newContext();
            var fresh = (SSLSocket) freshContext.getSocketFactory().createSocket(raw, owner.hostname, owner.port, true);
            synchronized (this) {
                context = freshContext;
                ssl = fresh;
                checkLive();
            }
            fresh.setEnabledProtocols(new String[]{PortalEventsTls.PROTOCOL});
            fresh.setEnabledCipherSuites(new String[]{PortalEventsTls.CIPHER});
            var parameters = fresh.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.of(new SNIHostName(owner.hostname)));
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
            fresh.setSSLParameters(parameters);
            fresh.setSoTimeout(timeoutMillis(openingDeadline, 2000));
            fresh.startHandshake();
            var session = fresh.getSession();
            if (!PortalEventsTls.PROTOCOL.equals(session.getProtocol()) || !PortalEventsTls.CIPHER.equals(session.getCipherSuite())
                    || !(fresh.getApplicationProtocol().isEmpty() || "http/1.1".equals(fresh.getApplicationProtocol())))
                throw new IOException("TLS negotiated profile rejected");
            session.getPeerCertificates();
            fresh.setSoTimeout(15000);
            synchronized (this) {
                checkLive();
                if (System.nanoTime() - openingDeadline >= 0) throw new IOException("TLS opening deadline elapsed");
                verified = true;
                lastDelivery = System.nanoTime();
                publicView = new SafeSsl();
                applicationInput = new ApplicationInput();
                applicationOutput = new ApplicationOutput();
                return publicView;
            }
        } catch (IOException | RuntimeException e) {
            abort();
            throw e;
        } finally {
            end();
        }
    }
    public SSLSocket connectAndHandshake() throws IOException {
        Socket raw = rawSocketForConnect();
        raw.connect(new InetSocketAddress(owner.address, owner.port), timeoutMillis(openingDeadline, 1000));
        return upgradeConnectedSocket();
    }
    public void abort() {
        boolean schedule;
        synchronized (this) {
            stopped = true;
            schedule = !closeScheduled;
            closeScheduled = true;
        }
        if (schedule) PortalEventsTls.scheduleClose(this);
    }
    void closeResources() {
        boolean successful = false;
        boolean closesSucceeded = true;
        try {
            Socket raw;
            synchronized (this) {
                closeThread = Thread.currentThread();
                raw = bottom;
            }
            try {
                if (raw != null) raw.close();
            } catch (IOException | RuntimeException e) {
                closesSucceeded = false;
                rememberCloseFailure(e);
            }
            // A cancel flag, close exception or timeout is not evidence that any I/O has exited.
            synchronized (this) {
                while (active != 0) wait();
            }
            SSLSocket secure;
            synchronized (this) {
                secure = ssl;
            }
            try {
                if (secure != null) secure.close();
            } catch (IOException | RuntimeException e) {
                closesSucceeded = false;
                rememberCloseFailure(e);
            }
            successful = closesSucceeded;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            successful = false;
            rememberCloseFailure(e);
        } finally {
            synchronized (this) {
                // Physical state is independent of whether all close operations succeeded and released the slot.
                if (bottom != null) {
                    knownClosed |= bottom.isClosed();
                    knownInputShutdown |= bottom.isInputShutdown();
                    knownOutputShutdown |= bottom.isOutputShutdown();
                }
                if (ssl != null) {
                    knownClosed |= ssl.isClosed();
                    knownInputShutdown |= ssl.isInputShutdown();
                    knownOutputShutdown |= ssl.isOutputShutdown();
                }
                bottom = null;
                rawView = null;
                ssl = null;
                context = null;
                publicView = null;
                applicationInput = null;
                applicationOutput = null;
                terminated = successful && active == 0;
                if (terminated) PortalEventsTls.release(this);
                closeThread = null;
                notifyAll();
            }
        }
    }
    private synchronized void rememberCloseFailure(Exception e) {
        closeFailure = new IOException("TLS physical close failed; slot retained", e);
    }
    public synchronized boolean awaitClosed(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative close wait");
        long remaining = timeout.toNanos(), deadline = System.nanoTime() + remaining;
        while (!terminated && remaining > 0) {
            long millis = remaining / 1000000;
            int nanos = (int)(remaining % 1000000);
            wait(millis, nanos);
            remaining = deadline - System.nanoTime();
        }
        return terminated;
    }
    @Override public void close() { abort(); }
    private synchronized SSLSocket verifiedSocket() throws IOException {
        checkLive();
        if (!verified || ssl == null) throw new IOException("TLS identity not verified");
        return ssl;
    }
    private synchronized SSLSocket stateSocket() {
        if (ssl == null) throw new IllegalStateException("TLS connection exited");
        return ssl;
    }

    private final class ApplicationInput extends InputStream {
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : n == 0 ? 0 : one[0] & 255;
        }
        private void beginRead() throws IOException {
            if (!reading.compareAndSet(false, true)) throw new IOException("Concurrent TLS application read");
            try {
                begin();
            } catch (IOException | RuntimeException e) {
                reading.set(false);
                abort();
                throw e;
            }
        }
        private void endRead() {
            end();
            reading.set(false);
        }
        // Called only under whole-call ownership. This is the sole application delivery credit path.
        private int readOwned(byte[] b, int off, int len) throws IOException {
            SSLSocket socket = verifiedSocket();
            if (len == 0) return 0;
            int n = socket.getInputStream().read(b, off, len);
            synchronized (PortalEventsTlsConnection.this) {
                checkLive();
                if (n > 0) {
                    debt.credit(n);
                    lastDelivery = System.nanoTime();
                }
                return n;
            }
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            beginRead();
            try {
                return readOwned(b, off, len);
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            } finally {
                endRead();
            }
        }
        @Override public int readNBytes(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            beginRead();
            try {
                verifiedSocket();
                int total = 0;
                while (total < len) {
                    int n = readOwned(b, off + total, len - total);
                    if (n < 0) break;
                    total += n;
                }
                return total;
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            } finally {
                endRead();
            }
        }
        @Override public long transferTo(OutputStream target) throws IOException {
            Objects.requireNonNull(target);
            beginRead();
            try {
                byte[] buffer = new byte[8192];
                long total = 0;
                int n;
                while ((n = readOwned(buffer, 0, buffer.length)) >= 0) {
                    target.write(buffer, 0, n);
                    total = Math.addExact(total, n);
                }
                return total;
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            } finally {
                endRead();
            }
        }
        @Override public int available() throws IOException { return verifiedSocket().getInputStream().available(); }
        @Override public long skip(long n) throws IOException {
            if (n > 0) throw new IOException("TLS application skip is unsupported");
            return 0;
        }
        @Override public boolean markSupported() { return false; }
        @Override public void reset() throws IOException {
            throw new IOException("TLS replay is unsupported");
        }
        @Override public byte[] readAllBytes() throws IOException {
            throw new IOException("TLS unbounded aggregation is unsupported");
        }
        @Override public byte[] readNBytes(int n) throws IOException {
            throw new IOException("TLS allocating aggregation is unsupported");
        }
        @Override public void close() { abort(); }
    }
    private final class ApplicationOutput extends OutputStream {
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            perform(() -> verifiedSocket().getOutputStream().write(b, off, len));
        }
        @Override public void flush() throws IOException { perform(() -> verifiedSocket().getOutputStream().flush()); }
        private void perform(Io action) throws IOException {
            if (!writing.compareAndSet(false, true)) throw new IOException("Concurrent TLS application write");
            boolean entered = false;
            try {
                begin();
                entered = true;
                action.run();
                synchronized (PortalEventsTlsConnection.this) {
                    checkLive();
                }
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            } finally {
                if (entered) end();
                writing.set(false);
            }
        }
        @Override public void close() { abort(); }
    }
    @FunctionalInterface private interface Io { void run() throws IOException; }
    private synchronized void beginRaw() throws IOException {
        if (Thread.currentThread() != closeThread) checkLive();
        active++;
    }

    /** The JDK layered socket receives only this metered transport, never its physical delegate. */
    private final class RawSocket extends Socket {
        private final Socket delegate;
        private InputStream input;
        private OutputStream output;
        RawSocket(Socket delegate) { this.delegate = delegate; }
        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress target) || target.isUnresolved()
                    || !owner.address.equals(target.getAddress()) || target.getPort() != owner.port)
                throw new IOException("TLS raw destination rejected");
            begin();
            try {
                synchronized (PortalEventsTlsConnection.this) {
                    checkLive();
                    if (connectUsed) throw new IOException("TLS raw connect already used");
                    connectUsed = true;
                }
                if (timeout <= 0) throw new IOException("Finite TLS connect timeout required");
                delegate.connect(endpoint, Math.min(timeout, timeoutMillis(openingDeadline, 1000)));
                synchronized (PortalEventsTlsConnection.this) {
                    checkLive();
                }
            } catch (IOException | RuntimeException e) {
                abort();
                throw e;
            } finally {
                end();
            }
        }
        @Override public void connect(SocketAddress endpoint) throws IOException { connect(endpoint, 1000); }
        @Override public void bind(SocketAddress endpoint) throws IOException {
            throw new IOException("TLS local bind is unsupported");
        }
        @Override public synchronized InputStream getInputStream() throws IOException {
            if (input == null) {
                var counted = debt.input(delegate.getInputStream(), PortalEventsTlsConnection.this::abort);
                input = new InputStream() {
                    @Override public int read() throws IOException {
                        byte[] one = new byte[1];
                        int n = read(one, 0, 1);
                        return n<0?-1:n == 0?0:one[0]&255;
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        beginRaw();
                        try {
                            int n = counted.read(b, off, len);
                            synchronized (PortalEventsTlsConnection.this) {
                                checkLive();
                            }
                            return n;
                        } finally {
                            end();
                        }
                    }
                    @Override public int available() throws IOException { return counted.available(); }
                    @Override public long skip(long n) throws IOException { return counted.skip(n); }
                    @Override public void reset() throws IOException { counted.reset(); }
                    @Override public byte[] readAllBytes() throws IOException { return counted.readAllBytes(); }
                    @Override public byte[] readNBytes(int n) throws IOException { return counted.readNBytes(n); }
                    @Override public void close() { abort(); }
                };
            }
            return input;
        }
        @Override public synchronized OutputStream getOutputStream() throws IOException {
            if (output == null) {
                var counted = debt.output(delegate.getOutputStream(), PortalEventsTlsConnection.this::abort);
                output = new OutputStream() {
                    @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        beginRaw();
                        try {
                            counted.write(b, off, len);
                        } finally {
                            end();
                        }
                    }
                    @Override public void flush() throws IOException {
                        beginRaw();
                        try {
                            counted.flush();
                        } finally {
                            end();
                        }
                    }
                    @Override public void close() { abort(); }
                };
            }
            return output;
        }
        @Override public void close() { abort(); }
        @Override public void shutdownInput() { abort(); }
        @Override public void shutdownOutput() { abort(); }
        @Override public SocketChannel getChannel() { return null; }
        @Override public InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public int getLocalPort() { return delegate.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isBound() { return delegate.isBound(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
        @Override public void setTcpNoDelay(boolean b) throws SocketException { delegate.setTcpNoDelay(b); }
        @Override public boolean getTcpNoDelay() throws SocketException { return delegate.getTcpNoDelay(); }
        @Override public void setSoLinger(boolean b, int n) throws SocketException {
            if (b) throw new SocketException("TLS linger unsupported");
            delegate.setSoLinger(false, n);
        }
        @Override public int getSoLinger() throws SocketException { return delegate.getSoLinger(); }
        @Override public void sendUrgentData(int n) throws IOException {
            throw new IOException("TLS urgent data unsupported");
        }
        @Override public void setOOBInline(boolean b) throws SocketException {
            if (b) throw new SocketException("TLS OOB unsupported");
        }
        @Override public boolean getOOBInline() { return false; }
        @Override public void setSoTimeout(int n) throws SocketException {
            if (n <= 0 || n>15000) throw new SocketException("Finite TLS read timeout required");
            delegate.setSoTimeout(n);
        }
        @Override public int getSoTimeout() throws SocketException { return delegate.getSoTimeout(); }
        @Override public void setSendBufferSize(int n) throws SocketException { delegate.setSendBufferSize(n); }
        @Override public int getSendBufferSize() throws SocketException { return delegate.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int n) throws SocketException { delegate.setReceiveBufferSize(n); }
        @Override public int getReceiveBufferSize() throws SocketException { return delegate.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean b) throws SocketException { delegate.setKeepAlive(b); }
        @Override public boolean getKeepAlive() throws SocketException { return delegate.getKeepAlive(); }
        @Override public void setTrafficClass(int n) throws SocketException { delegate.setTrafficClass(n); }
        @Override public int getTrafficClass() throws SocketException { return delegate.getTrafficClass(); }
        @Override public void setReuseAddress(boolean b) throws SocketException { delegate.setReuseAddress(b); }
        @Override public boolean getReuseAddress() throws SocketException { return delegate.getReuseAddress(); }
        @Override public void setPerformancePreferences(int a, int b, int c) {
            throw new UnsupportedOperationException("TLS performance preferences unsupported");
        }
        @Override public <T> Socket setOption(SocketOption<T> name, T value) {
            throw new UnsupportedOperationException("TLS generic option mutation unsupported");
        }
        @Override public <T> T getOption(SocketOption<T> name) throws IOException { return delegate.getOption(name); }
        @Override public Set<SocketOption<?>> supportedOptions() { return delegate.supportedOptions(); }
        @Override public String toString() { return "Metered portal TLS raw socket"; }
    }

    /** Public view has no delegate field, mutable security settings or implicit-handshake entry point. */
    private final class SafeSsl extends SSLSocket {
        private UnsupportedOperationException frozen() { return new UnsupportedOperationException("Fixed portal TLS profile"); }
        @Override public InputStream getInputStream() throws IOException {
            synchronized (PortalEventsTlsConnection.this) {
                verifiedSocket();
                return applicationInput;
            }
        }
        @Override public OutputStream getOutputStream() throws IOException {
            synchronized (PortalEventsTlsConnection.this) {
                verifiedSocket();
                return applicationOutput;
            }
        }
        @Override public void startHandshake() throws IOException {
            throw new IOException("TLS application rehandshake prohibited");
        }
        @Override public SSLSession getSession() {
            synchronized (PortalEventsTlsConnection.this) {
                try {
                    verifiedSocket();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new ReadOnlySession();
            }
        }
        @Override public SSLSession getHandshakeSession() {
            throw frozen();
        }
        @Override public String[] getSupportedCipherSuites() { return new String[]{PortalEventsTls.CIPHER}; }
        @Override public String[] getEnabledCipherSuites() { return getSupportedCipherSuites(); }
        @Override public String[] getSupportedProtocols() { return new String[]{PortalEventsTls.PROTOCOL}; }
        @Override public String[] getEnabledProtocols() { return getSupportedProtocols(); }
        @Override public void setEnabledCipherSuites(String[] s) {
            throw frozen();
        }
        @Override public void setEnabledProtocols(String[] s) {
            throw frozen();
        }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener l) {
            throw frozen();
        }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener l) {
            throw frozen();
        }
        @Override public void setUseClientMode(boolean b) {
            throw frozen();
        }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean b) {
            throw frozen();
        }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean b) {
            throw frozen();
        }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean b) {
            throw frozen();
        }
        @Override public boolean getEnableSessionCreation() { return stateSocket().getEnableSessionCreation(); }
        @Override public SSLParameters getSSLParameters() { return stateSocket().getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters p) {
            throw frozen();
        }
        @Override public String getApplicationProtocol() { return stateSocket().getApplicationProtocol(); }
        @Override public String getHandshakeApplicationProtocol() {
            throw frozen();
        }
        @Override public void setHandshakeApplicationProtocolSelector(BiFunction<SSLSocket, List<String>, String> f) {
            throw frozen();
        }
        @Override public BiFunction<SSLSocket, List<String>, String> getHandshakeApplicationProtocolSelector() {
            throw frozen();
        }
        @Override public void connect(SocketAddress e) throws IOException {
            throw new IOException("TLS already connected");
        }
        @Override public void connect(SocketAddress e, int n) throws IOException {
            throw new IOException("TLS already connected");
        }
        @Override public void bind(SocketAddress e) throws IOException {
            throw new IOException("TLS already bound");
        }
        @Override public void close() { abort(); }
        @Override public void shutdownInput() { abort(); }
        @Override public void shutdownOutput() { abort(); }
        @Override public SocketChannel getChannel() { return null; }
        @Override public InetAddress getInetAddress() { return owner.address; }
        @Override public int getPort() { return owner.port; }
        @Override public InetAddress getLocalAddress() { return stateSocket().getLocalAddress(); }
        @Override public int getLocalPort() { return stateSocket().getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(owner.address, owner.port); }
        @Override public SocketAddress getLocalSocketAddress() { return stateSocket().getLocalSocketAddress(); }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isBound() { return true; }
        @Override public boolean isClosed() {
            synchronized (PortalEventsTlsConnection.this) {
                return ssl == null ? knownClosed : ssl.isClosed();
            }
        }
        @Override public boolean isInputShutdown() {
            synchronized (PortalEventsTlsConnection.this) {
                return ssl == null ? knownInputShutdown : ssl.isInputShutdown();
            }
        }
        @Override public boolean isOutputShutdown() {
            synchronized (PortalEventsTlsConnection.this) {
                return ssl == null ? knownOutputShutdown : ssl.isOutputShutdown();
            }
        }
        @Override public void setTcpNoDelay(boolean b) throws SocketException { stateSocket().setTcpNoDelay(b); }
        @Override public boolean getTcpNoDelay() throws SocketException { return stateSocket().getTcpNoDelay(); }
        @Override public void setSoLinger(boolean b, int n) throws SocketException {
            throw new SocketException("TLS linger mutation unsupported");
        }
        @Override public int getSoLinger() throws SocketException { return stateSocket().getSoLinger(); }
        @Override public void sendUrgentData(int n) throws IOException {
            throw new IOException("TLS urgent data unsupported");
        }
        @Override public void setOOBInline(boolean b) throws SocketException {
            throw new SocketException("TLS OOB mutation unsupported");
        }
        @Override public boolean getOOBInline() { return false; }
        @Override public void setSoTimeout(int n) throws SocketException {
            throw new SocketException("TLS read timeout is fixed");
        }
        @Override public int getSoTimeout() throws SocketException { return stateSocket().getSoTimeout(); }
        @Override public void setSendBufferSize(int n) throws SocketException { stateSocket().setSendBufferSize(n); }
        @Override public int getSendBufferSize() throws SocketException { return stateSocket().getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int n) throws SocketException { stateSocket().setReceiveBufferSize(n); }
        @Override public int getReceiveBufferSize() throws SocketException { return stateSocket().getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean b) throws SocketException { stateSocket().setKeepAlive(b); }
        @Override public boolean getKeepAlive() throws SocketException { return stateSocket().getKeepAlive(); }
        @Override public void setTrafficClass(int n) throws SocketException { stateSocket().setTrafficClass(n); }
        @Override public int getTrafficClass() throws SocketException { return stateSocket().getTrafficClass(); }
        @Override public void setReuseAddress(boolean b) throws SocketException { stateSocket().setReuseAddress(b); }
        @Override public boolean getReuseAddress() throws SocketException { return stateSocket().getReuseAddress(); }
        @Override public void setPerformancePreferences(int a, int b, int c) {
            throw frozen();
        }
        @Override public <T> Socket setOption(SocketOption<T> n, T v) {
            throw frozen();
        }
        @Override public <T> T getOption(SocketOption<T> n) throws IOException { return verifiedSocket().getOption(n); }
        @Override public Set<SocketOption<?>> supportedOptions() { return stateSocket().supportedOptions(); }
        @Override public String toString() { return "Verified fixed-origin portal TLS socket"; }
    }
    private final class ReadOnlySession implements SSLSession {
        private SSLSession session() { return stateSocket().getSession(); }
        @Override public byte[] getId() { return session().getId(); }
        @Override public SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return session().getCreationTime(); }
        @Override public long getLastAccessedTime() { return session().getLastAccessedTime(); }
        @Override public void invalidate() {
            throw new UnsupportedOperationException("TLS session is private");
        }
        @Override public boolean isValid() {
            synchronized (PortalEventsTlsConnection.this) {
                return !stopped && session().isValid();
            }
        }
        @Override public void putValue(String n, Object v) {
            throw new UnsupportedOperationException("TLS session is private");
        }
        @Override public Object getValue(String n) { return null; }
        @Override public void removeValue(String n) {
            throw new UnsupportedOperationException("TLS session is private");
        }
        @Override public String[] getValueNames() { return new String[0]; }
        @Override public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException { return session().getPeerCertificates(); }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @SuppressWarnings("removal") @Override public javax.security.cert.X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
            throw new SSLPeerUnverifiedException("Deprecated certificate API unsupported");
        }
        @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { return session().getPeerPrincipal(); }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return session().getCipherSuite(); }
        @Override public String getProtocol() { return session().getProtocol(); }
        @Override public String getPeerHost() { return owner.hostname; }
        @Override public int getPeerPort() { return owner.port; }
        @Override public int getPacketBufferSize() { return session().getPacketBufferSize(); }
        @Override public int getApplicationBufferSize() { return session().getApplicationBufferSize(); }
    }
}
