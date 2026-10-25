package cn.agentplatform.gateway.infra;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PortalEventsTlsDebtTest {
    @Test void rawBytesMinusOnlyDeliveredApplicationBytesRemainDebt() throws Exception {
        var debt = new PortalEventsTlsDebt();
        var raw = debt.input(new ByteArrayInputStream(new byte[100]), () -> { });
        assertEquals(100, raw.read(new byte[100]));
        debt.credit(60);
        assertEquals(40, debt.snapshot().debt());
        assertEquals(0, debt.snapshot().reserved());
    }

    @Test void capacityClipsDelegateReadAndFailsWithoutFalseEof() throws Exception {
        var d = new PortalEventsTlsDebt(); var calls = new AtomicInteger(); var last = new AtomicInteger(); var abort = new AtomicInteger();
        InputStream source = new InputStream() {
            public int read() { throw new AssertionError("single path required"); }
            public int read(byte[] b, int off, int len) { calls.incrementAndGet(); last.set(len); return len; }
        };
        var in = d.input(source, abort::incrementAndGet);
        assertEquals(65535, in.read(new byte[65535]));
        assertEquals(1, in.read(new byte[2])); assertEquals(1, last.get());
        assertEquals(0, in.read(new byte[0]));
        assertThrows(IOException.class, in::read); assertEquals(2, calls.get()); assertEquals(1, abort.get());
        assertEquals(65536, d.snapshot().debt());
    }

    @Test void zeroAndEofRefundReservationButUnknownPartialExceptionKeepsIt() throws Exception {
        var d = new PortalEventsTlsDebt(); var step = new AtomicInteger(); var abort = new AtomicInteger();
        var in = d.input(new InputStream() {
            public int read() { throw new AssertionError(); }
            public int read(byte[] b, int off, int len) throws IOException {
                return switch (step.getAndIncrement()) { case 0 -> 0; case 1 -> -1; default -> { b[off]=7; throw new IOException("unknown consumed prefix"); } };
            }
        }, abort::incrementAndGet);
        assertEquals(0, in.read(new byte[17])); assertEquals(0, d.snapshot().reserved()); assertEquals(0, d.snapshot().debt());
        assertEquals(-1, in.read(new byte[23])); assertEquals(0, d.snapshot().reserved()); assertEquals(0, d.snapshot().debt());
        assertThrows(IOException.class, () -> in.read(new byte[41]));
        assertEquals(41, d.snapshot().debt()); assertEquals(0, d.snapshot().reserved()); assertEquals(1, abort.get());
    }

    @Test void reservationIsVisibleWhileIoBlockedAndSameDirectionCannotOverlap() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var d = new PortalEventsTlsDebt();
        var in = d.input(new InputStream() {
            public int read() { throw new AssertionError(); }
            public int read(byte[] b, int off, int len) throws IOException {
                entered.countDown(); try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test latch"); }
                catch (InterruptedException e) { throw new IOException(e); } return 11;
            }
        }, () -> { });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var read = pool.submit(() -> in.read(new byte[100]));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); assertEquals(100, d.snapshot().reserved());
            assertThrows(IOException.class, in::read); release.countDown(); assertEquals(11, read.get(2, TimeUnit.SECONDS));
            assertEquals(11, d.snapshot().debt()); assertEquals(0, d.snapshot().reserved());
        } finally { release.countDown(); }
    }

    @Test void skipAvailableAndBulkHelpersDoNotBypassAccounting() throws Exception {
        var d = new PortalEventsTlsDebt(); var in = d.input(new ByteArrayInputStream(new byte[65540]), () -> { });
        assertEquals(65536, in.available()); assertEquals(0, in.skip(0));
        assertThrows(IOException.class, () -> in.skip(1)); assertFalse(in.markSupported());
        assertThrows(IOException.class, in::reset); assertThrows(IOException.class, in::readAllBytes);
        assertThrows(IOException.class, () -> in.readNBytes(2));
        byte[] b = new byte[10]; assertEquals(10, in.readNBytes(b,0,10)); assertEquals(10,d.snapshot().debt());
        var other = new PortalEventsTlsDebt(); var small = other.input(new ByteArrayInputStream(new byte[123]), () -> { });
        assertEquals(123, small.transferTo(new ByteArrayOutputStream())); assertEquals(123,other.snapshot().debt());
        assertThrows(IOException.class, () -> d.credit(-1)); assertThrows(IOException.class, () -> d.credit(11));
        assertEquals(10,d.snapshot().debt());
    }

    @Test void outputHasOneCumulativeHardBoundaryAndExceptionNeverRefunds() throws Exception {
        var d = new PortalEventsTlsDebt(); var target = new ByteArrayOutputStream(); var abort = new AtomicInteger();
        var out = d.output(target, abort::incrementAndGet);
        out.write(new byte[131071]); out.write(5); assertEquals(131072,d.snapshot().output());
        assertThrows(IOException.class, () -> out.write(6)); assertEquals(131072,target.size()); assertEquals(1,abort.get());
        var failed = new PortalEventsTlsDebt(); var bad = failed.output(new OutputStream() {
            public void write(int b) { throw new AssertionError("unified array path"); }
            public void write(byte[] b,int off,int len) throws IOException { throw new IOException("unknown partial write"); }
        }, () -> { });
        assertThrows(IOException.class, () -> bad.write(new byte[19])); assertEquals(19,failed.snapshot().output());
    }
    @Test void checkedCounterOverflowAbortsWithoutGrantingCredit() throws Exception {
        var d=new PortalEventsTlsDebt();var abort=new AtomicInteger();
        // Reach an otherwise impractically distant counter boundary without adding a credit/reset API.
        for(String name:new String[]{"raw","delivered"}){
            var field=PortalEventsTlsDebt.class.getDeclaredField(name);field.setAccessible(true);field.setLong(d,Long.MAX_VALUE);
        }
        var in=d.input(new ByteArrayInputStream(new byte[1]),abort::incrementAndGet);
        assertThrows(ArithmeticException.class,in::read);
        assertEquals(1,abort.get());assertEquals(0,d.snapshot().debt());
        assertEquals(1,d.snapshot().reserved());assertEquals(Long.MAX_VALUE,d.snapshot().delivered());
    }
}
