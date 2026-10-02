package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;

import io.circuitdrift.androidialup.protocol.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * In-process relay that answers per S1_WIRE_PROTOCOL section 16 canonical ordering, modelled on
 * prototype/python/androidialup_relay/session.py with a loopback backend. It implements
 * {@link FrameSink}: every frame passes through {@link FrameCodec} bytes and
 * {@link FrameStreamDecoder} to prove wire compatibility. Replies are queued and delivered only
 * by {@link #pump(ModemRelayBridge)}, as a real reader thread would post them later.
 */
final class FakeRelay implements FrameSink {
    static final String RELAY_ID = "relay-prototype";

    enum DialOutcome { CONNECT, BUSY, NO_DIALTONE, NO_ANSWER, GATEWAY_UNAVAILABLE, SILENT, ACCEPT_ONLY }

    private final byte[] deviceSecret;
    private final FrameStreamDecoder decoder = new FrameStreamDecoder();
    private final Deque<AduFrame> toClient = new ArrayDeque<>();
    final List<FrameKind> received = new ArrayList<>();
    final List<FrameKind> sent = new ArrayList<>();
    /** Both directions in wire order: "C:KIND" client to relay, "R:KIND" relay to client. */
    final List<String> wire = new ArrayList<>();
    final ByteArrayOutputStream echoed = new ByteArrayOutputStream();
    final List<String> closes = new ArrayList<>();
    int connectRequests;

    DialOutcome dialOutcome = DialOutcome.CONNECT;
    boolean answerPings = true;
    boolean echoData = true;
    private byte[] nonce;
    private byte[] callId = AduFrame.ZERO_ID;
    private final byte[] sessionId;
    private long outboundSeq;
    private long inboundSeq;
    private int nonceCounter;

    FakeRelay(byte[] deviceSecret) {
        this.deviceSecret = deviceSecret.clone();
        this.sessionId = new byte[16];
        Arrays.fill(sessionId, (byte) 0x5e);
    }

    @Override
    public void send(AduFrame frame) {
        for (AduFrame decoded : decoder.feed(FrameCodec.encode(frame))) {
            received.add(decoded.kind());
            wire.add("C:" + decoded.kind());
            handle(decoded);
        }
    }

    @Override
    public void close(String reason) {
        closes.add(reason);
    }

    @Override
    public void requestConnect() {
        connectRequests++;
    }

    /** Delivers queued relay frames to the bridge until none remain; returns how many. */
    int pump(ModemRelayBridge bridge) {
        int count = 0;
        while (!toClient.isEmpty()) {
            AduFrame frame = toClient.poll();
            sent.add(frame.kind());
            wire.add("R:" + frame.kind());
            bridge.onFrame(frame);
            count++;
        }
        return count;
    }

    /** Relay-originated PING (S1 section 9: either peer may send). */
    void ping(long nonceValue) {
        reply(new Ping(nonceValue, 0), 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID);
    }

    void remoteHangup(String reason) {
        reply(new CallTerminated(reason, TerminationSource.GATEWAY, null), 0, callId, sessionId);
        resetCall();
    }

    private void handle(AduFrame frame) {
        Message message = PayloadCodec.decode(frame.kind(), frame.payload());
        switch (frame.kind()) {
            case HELLO -> reply(new HelloAck(1, RELAY_ID, FrameCodec.MAX_PAYLOAD, 10, List.of("BYTE_RELAY")),
                    frame.requestId(), AduFrame.ZERO_ID, AduFrame.ZERO_ID);
            case AUTH_BEGIN -> {
                nonce = new byte[32];
                Arrays.fill(nonce, (byte) (0xa0 + nonceCounter++));
                reply(new AuthChallenge(nonce, DeviceCredentialProof.METHOD), frame.requestId(), AduFrame.ZERO_ID, AduFrame.ZERO_ID);
            }
            case AUTH_RESPONSE -> {
                // The relay rebuilds the proof from its own view of HELLO (endpoint is all-0x11 in these tests).
                byte[] endpoint = new byte[32];
                Arrays.fill(endpoint, (byte) 0x11);
                byte[] expected = DeviceCredentialProof.compute(deviceSecret, nonce, endpoint, RELAY_ID);
                boolean ok = java.security.MessageDigest.isEqual(expected, ((AuthResponse) message).proof());
                nonce = null;
                if (ok) {
                    reply(new AuthOk(endpoint, List.of(new KeyValue("dial", "allowed"))), frame.requestId(), AduFrame.ZERO_ID, AduFrame.ZERO_ID);
                } else {
                    reply(new AuthFail("authentication failed"), frame.requestId(), AduFrame.ZERO_ID, AduFrame.ZERO_ID);
                }
            }
            case DIAL_REQUEST -> onDial(frame);
            case DATA_BYTES -> {
                DataBytes data = (DataBytes) message;
                if (data.streamSeq() != inboundSeq) throw new ProtocolException("relay saw BYTE_RELAY gap");
                inboundSeq += data.data().length;
                if (echoData) {
                    echoed.writeBytes(data.data());
                    reply(new DataBytes(outboundSeq, data.data()), 0, callId, sessionId);
                    outboundSeq += data.data().length;
                }
                reply(new FlowStatus(RelaySessionMachine.DEFAULT_RECEIVE_WINDOW, 0), 0, callId, sessionId);
            }
            case FLOW_STATUS, PONG -> { }
            case PING -> {
                if (answerPings) reply(new Pong(((Ping) message).nonce()), frame.requestId(), frame.callId(), frame.sessionId());
            }
            case HANGUP_REQUEST -> {
                reply(new HangupAck(), frame.requestId(), callId, sessionId);
                reply(new CallTerminated("LOCAL_HANGUP", TerminationSource.GATEWAY, null), 0, callId, sessionId);
                resetCall();
            }
            default -> throw new ProtocolException("fake relay does not handle " + frame.kind());
        }
    }

    private void onDial(AduFrame frame) {
        callId = frame.callId();
        long request = frame.requestId();
        switch (dialOutcome) {
            case SILENT -> { }
            case CONNECT, ACCEPT_ONLY -> {
                reply(new DialAccepted(callId, sessionId, "gw-loopback", Mode.BYTE_RELAY), request, callId, sessionId);
                reply(new CallProgress(ProgressPhase.DIALING, "loopback"), 0, callId, sessionId);
                if (dialOutcome == DialOutcome.CONNECT) {
                    reply(new CallProgress(ProgressPhase.CONNECTED, "loopback"), 0, callId, sessionId);
                    reply(new FlowStatus(RelaySessionMachine.DEFAULT_RECEIVE_WINDOW, 0), 0, callId, sessionId);
                }
            }
            default -> {
                reply(new DialFailed(callId, DialFailure.valueOf(dialOutcome.name()), false, dialOutcome.name()),
                        request, callId, AduFrame.ZERO_ID);
                resetCall();
            }
        }
    }

    private void resetCall() {
        callId = AduFrame.ZERO_ID;
        outboundSeq = 0;
        inboundSeq = 0;
    }

    private void reply(Message message, long requestId, byte[] call, byte[] session) {
        toClient.add(new AduFrame(PayloadCodec.kindFor(message), 0, call, session, requestId, PayloadCodec.encode(message)));
    }
}
