package io.circuitdrift.androidialup.platform.dte;

import static org.junit.Assert.*;

import io.circuitdrift.androidialup.modem.ModemController;
import io.circuitdrift.androidialup.modem.SessionPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import org.junit.Test;

public final class PrivilegedCdcAcmDteTransportTest {

    @Test
    public void opensProviderEndpointAndRunsSharedStreamSession() throws Exception {
        FakeProvider provider = new FakeProvider();
        PrivilegedCdcAcmDteTransport transport = new PrivilegedCdcAcmDteTransport(
                provider,
                (writer, executor) -> new ModemController(new NoopSession(), writer, "test"));

        transport.start();
        provider.hostInput.write("AT\r".getBytes("US-ASCII"));
        provider.hostInput.flush();
        long deadline = System.currentTimeMillis() + 2000;
        while (provider.output.size() < 6 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals("AT\rOK\r\n", provider.output.toString("US-ASCII"));
        assertTrue(transport.isRunning());
        assertEquals(SerialGadgetProvider.Capabilities.NONE, transport.capabilities());

        transport.close();
        assertTrue(provider.endpointClosed);
    }

    @Test
    public void startFailureLeavesTransportStopped() {
        SerialGadgetProvider provider = new SerialGadgetProvider() {
            @Override public Endpoint open() throws IOException {
                throw new IOException("tty unavailable");
            }
        };
        PrivilegedCdcAcmDteTransport transport = new PrivilegedCdcAcmDteTransport(
                provider,
                (writer, executor) -> new ModemController(new NoopSession(), writer, "test"));

        try {
            transport.start();
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("tty unavailable", expected.getMessage());
        }
        assertFalse(transport.isRunning());
    }

    private static final class FakeProvider implements SerialGadgetProvider {
        final PipedInputStream deviceInput = new PipedInputStream();
        final PipedOutputStream hostInput;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile boolean endpointClosed;

        FakeProvider() throws IOException {
            hostInput = new PipedOutputStream(deviceInput);
        }

        @Override public Endpoint open() {
            return new Endpoint() {
                @Override public InputStream input() { return deviceInput; }
                @Override public OutputStream output() { return output; }
                @Override public Capabilities capabilities() { return Capabilities.NONE; }
                @Override public void close() throws IOException {
                    endpointClosed = true;
                    hostInput.close();
                    deviceInput.close();
                }
            };
        }
    }

    private static final class NoopSession implements SessionPort {
        @Override public void dial(String target) {}
        @Override public void writeData(byte[] data) {}
        @Override public void hangup(String reason) {}
        @Override public void answer() {}
    }
}
