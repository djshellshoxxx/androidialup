package io.circuitdrift.androidialup.platform.relay;

import static io.circuitdrift.androidialup.protocol.Messages.*;

import io.circuitdrift.androidialup.protocol.AduFrame;
import io.circuitdrift.androidialup.protocol.FrameCodec;
import io.circuitdrift.androidialup.protocol.FrameKind;
import io.circuitdrift.androidialup.protocol.FrameStreamDecoder;
import io.circuitdrift.androidialup.protocol.PayloadCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Scripted plain-TCP ADUP relay on loopback for JVM tests: HELLO/AUTH handshake, dial accept +
 * CONNECTED progress, BYTE_RELAY echo with flow credit, HANGUP_ACK + CALL_TERMINATED, PONG.
 */
final class FakeRelayServer implements AutoCloseable {
    static final byte[] SESSION_ID = filled(0x5a);

    final ServerSocket server;
    final BlockingQueue<AduFrame> received = new LinkedBlockingQueue<>();
    final List<Socket> sockets = new CopyOnWriteArrayList<>();
    volatile boolean rejectAuth;
    volatile boolean answerPings = true;
    volatile long heartbeatSeconds = 30;
    volatile DialFailure dialFailure;
    private final Thread acceptThread;

    FakeRelayServer() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        acceptThread = new Thread(this::acceptLoop, "fake-relay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connector producing a plain loopback TCP socket (TLS is covered by RelayTlsTest). */
    RelayTlsTransport.Connector connector() {
        return () -> new Socket(InetAddress.getLoopbackAddress(), port());
    }

    /** Abruptly drops every accepted connection (relay death / network loss). */
    void dropConnections() {
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    AduFrame awaitKind(FrameKind kind, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            AduFrame frame = received.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (frame == null) return null;
            if (frame.kind() == kind) return frame;
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        dropConnections();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                Thread t = new Thread(() -> serve(socket), "fake-relay-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        FrameStreamDecoder decoder = new FrameStreamDecoder();
        long inboundSeq = 0;
        long outboundSeq = 0;
        byte[] callId = AduFrame.ZERO_ID;
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                for (AduFrame frame : decoder.feed(Arrays.copyOf(buffer, n))) {
                    received.add(frame);
                    switch (frame.kind()) {
                        case HELLO -> send(out, new HelloAck(1, "relay-test", FrameCodec.MAX_PAYLOAD,
                                heartbeatSeconds, List.of("BYTE_RELAY")), AduFrame.ZERO_ID, AduFrame.ZERO_ID, frame.requestId());
                        case AUTH_BEGIN -> send(out, new AuthChallenge(filled(0x11), "device-credential-hmac-sha256-v1"),
                                AduFrame.ZERO_ID, AduFrame.ZERO_ID, frame.requestId());
                        case AUTH_RESPONSE -> {
                            if (rejectAuth) {
                                send(out, new AuthFail("bad proof"), AduFrame.ZERO_ID, AduFrame.ZERO_ID, frame.requestId());
                            } else {
                                send(out, new AuthOk(new byte[32], List.of()), AduFrame.ZERO_ID, AduFrame.ZERO_ID, frame.requestId());
                            }
                        }
                        case DIAL_REQUEST -> {
                            callId = frame.callId();
                            if (dialFailure != null) {
                                send(out, new DialFailed(callId, dialFailure, false, "scripted"), callId,
                                        AduFrame.ZERO_ID, frame.requestId());
                                break;
                            }
                            send(out, new DialAccepted(callId, SESSION_ID, "gw-test", Mode.BYTE_RELAY),
                                    callId, SESSION_ID, frame.requestId());
                            send(out, new CallProgress(ProgressPhase.CONNECTED, "connected"), callId, SESSION_ID, 0);
                        }
                        case DATA_BYTES -> {
                            DataBytes data = (DataBytes) PayloadCodec.decode(frame.kind(), frame.payload());
                            if (data.streamSeq() != inboundSeq) throw new IOException("sequence gap");
                            inboundSeq += data.data().length;
                            send(out, new DataBytes(outboundSeq, data.data()), callId, SESSION_ID, 0);
                            outboundSeq += data.data().length;
                            send(out, new FlowStatus(256 * 1024, 0), callId, SESSION_ID, 0);
                        }
                        case HANGUP_REQUEST -> {
                            send(out, new HangupAck(), callId, SESSION_ID, frame.requestId());
                            send(out, new CallTerminated("LOCAL_HANGUP", TerminationSource.LOCAL, null),
                                    callId, SESSION_ID, 0);
                        }
                        case PING -> {
                            if (answerPings) {
                                Ping ping = (Ping) PayloadCodec.decode(frame.kind(), frame.payload());
                                send(out, new Pong(ping.nonce()), frame.callId(), frame.sessionId(), frame.requestId());
                            }
                        }
                        default -> { }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // connection ends
        }
    }

    private static synchronized void send(OutputStream out, Message message, byte[] call, byte[] session,
                                          long requestId) throws IOException {
        out.write(FrameCodec.encode(new AduFrame(PayloadCodec.kindFor(message), 0, call, session, requestId,
                PayloadCodec.encode(message))));
        out.flush();
    }

    static byte[] filled(int value) {
        byte[] id = new byte[16];
        Arrays.fill(id, (byte) value);
        return id;
    }
}
