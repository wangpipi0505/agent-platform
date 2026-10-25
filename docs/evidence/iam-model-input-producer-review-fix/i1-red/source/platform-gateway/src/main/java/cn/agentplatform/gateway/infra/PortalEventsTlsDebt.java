package cn.agentplatform.gateway.infra;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One physical socket, including handshake and close traffic; never reset on application progress. */
final class PortalEventsTlsDebt {
    static final int C = 65536;
    static final int W = 131072;
    private long debt;
    private long reserved;
    private long output;
    private long raw;
    private long delivered;
    record Snapshot(long debt, long reserved, long output, long raw, long delivered) { }
    synchronized Snapshot snapshot() { return new Snapshot(debt, reserved, output, raw, delivered); }
    private synchronized int reserve(int length) throws IOException {
        long remaining = C - debt - reserved;
        if (remaining <= 0) throw new IOException("TLS cumulative input capacity exhausted");
        int grant = (int) Math.min(length, remaining);
        reserved = Math.addExact(reserved, grant);
        return grant;
    }
    private synchronized void settle(int grant, int n) throws IOException {
        if (n < -1 || n > grant || reserved < grant) throw new IOException("TLS input accounting invariant");
        long used = Math.max(0, n);
        long nextRaw = Math.addExact(raw, used);
        long nextDebt = Math.addExact(debt, used);
        if (nextDebt > C) throw new IOException("TLS input accounting invariant");
        reserved -= grant; raw = nextRaw; debt = nextDebt;
    }
    synchronized void credit(int bytes) throws IOException {
        if (bytes < 0 || bytes > debt) throw new IOException("TLS application credit invariant");
        long next = Math.addExact(delivered, bytes);
        debt -= bytes; delivered = next;
    }
    private synchronized void chargeOutput(int length) throws IOException {
        long next = Math.addExact(output, length);
        if (next > W) throw new IOException("TLS cumulative output capacity exhausted");
        output = next;
    }
    InputStream input(InputStream delegate, Runnable abort) {
        return new InputStream() {
            private final AtomicBoolean reading = new AtomicBoolean();
            @Override public int read() throws IOException {
                byte[] one = new byte[1]; int n = read(one, 0, 1);
                return n < 0 ? -1 : n == 0 ? 0 : one[0] & 255;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                if (len == 0) return 0;
                if (!reading.compareAndSet(false, true)) throw new IOException("Concurrent TLS raw read");
                int grant = 0;
                try {
                    grant = reserve(len); int n;
                    try { n = delegate.read(b, off, grant); }
                    catch (IOException | RuntimeException e) {
                        // The exception carries no reliable consumed-byte count; keep the whole charge.
                        settle(grant, grant); grant = 0; throw e;
                    }
                    settle(grant, n); grant = 0; return n;
                } catch (IOException | RuntimeException e) {
                    try { if (grant != 0) settle(grant, grant); }
                    catch (IOException | RuntimeException accountingFailure) { e.addSuppressed(accountingFailure); }
                    finally { abort.run(); }
                    throw e;
                } finally { reading.set(false); }
            }
            @Override public long skip(long n) throws IOException {
                if (n > 0) throw new IOException("TLS raw skip/deplete is unsupported"); return 0;
            }
            @Override public int available() throws IOException {
                int available = Math.max(0, delegate.available());
                synchronized (PortalEventsTlsDebt.this) {
                    return (int) Math.min(available, C - debt - reserved);
                }
            }
            @Override public boolean markSupported() { return false; }
            @Override public void reset() throws IOException { throw new IOException("TLS replay is unsupported"); }
            @Override public byte[] readAllBytes() throws IOException { throw new IOException("TLS unbounded aggregation is unsupported"); }
            @Override public byte[] readNBytes(int n) throws IOException { throw new IOException("TLS allocating aggregation is unsupported"); }
            @Override public void close() { abort.run(); }
        };
    }
    OutputStream output(OutputStream delegate, Runnable abort) {
        return new OutputStream() {
            private final AtomicBoolean writing = new AtomicBoolean();
            @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, b.length);
                if (!writing.compareAndSet(false, true)) throw new IOException("Concurrent TLS raw write");
                try { chargeOutput(len); delegate.write(b, off, len); }
                catch (IOException | RuntimeException e) {
                    // Successful and uncertain partial writes both keep their entire charge.
                    abort.run(); throw e;
                } finally { writing.set(false); }
            }
            @Override public void flush() throws IOException {
                if (!writing.compareAndSet(false, true)) throw new IOException("Concurrent TLS raw write");
                try { delegate.flush(); }
                catch (IOException | RuntimeException e) { abort.run(); throw e; }
                finally { writing.set(false); }
            }
            @Override public void close() { abort.run(); }
        };
    }
}
