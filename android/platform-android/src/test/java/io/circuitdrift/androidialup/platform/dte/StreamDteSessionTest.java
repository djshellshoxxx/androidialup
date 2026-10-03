package io.circuitdrift.androidialup.platform.dte;

import static org.junit.Assert.*;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.SessionPort;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamDteSessionTest {

    @Test
    public void atCommandRoundTripsAndEofClosesSession() throws Exception {
        PipePair pipes = new PipePair();
        FakeSessionPort port = new FakeSessionPort();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> closeReason = new AtomicReference<>();

        StreamDteSession session = new StreamDteSession(
                pipes.sessionInput, pipes.sessionOutput,
                (writer, executor) -> new ModemController(port, writer, "stream-test"),
                new StreamDteSession.Listener() {
                    @Override public void onClosed(String reason) {
                        closeReason.set(reason);
                        closed.countDown();
                    }
                },
                5, 64 * 1024, 32, System::nanoTime);

        session.start();
        pipes.hostOutput.write("AT\r".getBytes(StandardCharsets.US_ASCII));
        pipes.hostOutput.flush();

        String response = readUntil(pipes.hostInput, "OK\r\n", 3000);
        assertTrue(response.contains("OK\r\n"));

        pipes.hostOutput.close();
        assertTrue("EOF did not close the DTE session", closed.await(3, TimeUnit.SECONDS));
        assertEquals("DTE_EOF", closeReason.get());
        assertTrue(session.isClosed());

        session.close();
        session.close();
    }

    @Test
    public void dialAndOnlineBytesStayOrderedOnSingleModemOwner() throws Exception {
        PipePair pipes = new PipePair();
        FakeSessionPort port = new FakeSessionPort();
        AtomicReference<ModemController> controller = new AtomicReference<>();
        AtomicReference<Executor> modemExecutor = new AtomicReference<>();

        StreamDteSession session = new StreamDteSession(
                pipes.sessionInput, pipes.sessionOutput,
                (writer, executor) -> {
                    modemExecutor.set(executor);
                    ModemController value = new ModemController(port, writer, "stream-test");
                    controller.set(value);
                    return value;
                });
        session.start();

        pipes.hostOutput.write("ATDloopback\r".getBytes(StandardCharsets.US_ASCII));
        pipes.hostOutput.flush();
        assertTrue("dial was not delivered", port.dialed.await(3, TimeUnit.SECONDS));
        assertEquals("loopback", port.target.get());
        assertTrue(port.dialThread.get().getName().startsWith("stream-dte-modem-"));

        CountDownLatch connected = new CountDownLatch(1);
        modemExecutor.get().execute(() -> {
            controller.get().onCallConnected();
            connected.countDown();
        });
        assertTrue(connected.await(3, TimeUnit.SECONDS));
        readUntil(pipes.hostInput, "CONNECT", 3000);

        byte[] payload = new byte[] {0, 1, 2, 3, 4, 5, 31, 32, 64, 65, 126, 127, (byte) 0x80, (byte) 0xff};
        pipes.hostOutput.write(payload);
        pipes.hostOutput.flush();
        assertTrue("online data was not delivered", port.dataWritten.await(3, TimeUnit.SECONDS));
        assertArrayEquals(payload, port.data.get());
        assertTrue(port.dataThread.get().getName().startsWith("stream-dte-modem-"));
        assertSame(port.dialThread.get(), port.dataThread.get());

        session.close();
    }

    @Test
    public void boundedWriterOverflowDisconnectsInsteadOfDropping() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        BlockingOutputStream output = new BlockingOutputStream();
        FakeSessionPort port = new FakeSessionPort();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> closeReason = new AtomicReference<>();

        StreamDteSession session = new StreamDteSession(
                input, output,
                (writer, executor) -> new ModemController(port, writer, "stream-test"),
                new StreamDteSession.Listener() {
                    @Override public void onClosed(String reason) {
                        closeReason.set(reason);
                        closed.countDown();
                    }
                },
                10, 8, 2, System::nanoTime);
        session.start();

        session.writer().write(new byte[8]);
        assertTrue("writer did not enter blocking output", output.writeStarted.await(3, TimeUnit.SECONDS));
        session.writer().write(new byte[] {9});

        assertTrue("overflow did not close session", closed.await(3, TimeUnit.SECONDS));
        assertEquals("DTE_WRITE_QUEUE_OVERFLOW", closeReason.get());
        assertTrue(session.isClosed());
    }

    private static String readUntil(InputStream input, String needle, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (System.nanoTime() < deadline) {
            int available = input.available();
            if (available > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, available));
                if (count > 0) received.write(buffer, 0, count);
                String text = new String(received.toByteArray(), StandardCharsets.US_ASCII);
                if (text.contains(needle)) return text;
            } else {
                Thread.sleep(5);
            }
        }
        fail("timed out waiting for '" + needle + "', got: "
                + new String(received.toByteArray(), StandardCharsets.US_ASCII));
        return null;
    }

    private static final class PipePair {
        final PipedInputStream sessionInput = new PipedInputStream(64 * 1024);
        final PipedOutputStream hostOutput;
        final PipedOutputStream sessionOutput = new PipedOutputStream();
        final PipedInputStream hostInput;

        PipePair() throws IOException {
            hostOutput = new PipedOutputStream(sessionInput);
            hostInput = new PipedInputStream(sessionOutput, 64 * 1024);
        }
    }

    private static final class FakeSessionPort implements SessionPort {
        final CountDownLatch dialed = new CountDownLatch(1);
        final CountDownLatch dataWritten = new CountDownLatch(1);
        final AtomicReference<String> target = new AtomicReference<>();
        final AtomicReference<byte[]> data = new AtomicReference<>();
        final AtomicReference<Thread> dialThread = new AtomicReference<>();
        final AtomicReference<Thread> dataThread = new AtomicReference<>();

        @Override public void dial(String target) {
            this.target.set(target);
            dialThread.set(Thread.currentThread());
            dialed.countDown();
        }

        @Override public void writeData(byte[] data) {
            this.data.set(Arrays.copyOf(data, data.length));
            dataThread.set(Thread.currentThread());
            dataWritten.countDown();
        }

        @Override public void hangup(String reason) {}
        @Override public void answer() {}
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override public int read() throws IOException {
            try {
                closed.await();
                return -1;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            return read();
        }

        @Override public void close() {
            closed.countDown();
        }
    }

    private static final class BlockingOutputStream extends OutputStream {
        final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        @Override public void write(int b) throws IOException {
            write(new byte[] {(byte) b});
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            writeStarted.countDown();
            try {
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            throw new IOException("closed");
        }

        @Override public void close() {
            released.countDown();
        }
    }
}
