package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;

import io.circuitdrift.androidialup.protocol.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class RelaySessionMachine {
    public static final int DEFAULT_RECEIVE_WINDOW = 256 * 1024;
    public static final int MAX_LOCAL_PENDING = 256 * 1024;

    public enum State {
        NEW, HELLO_SENT, AUTH_BEGIN_SENT, AUTH_RESPONSE_SENT,
        IDLE, DIALING, CONNECTED, HANGING_UP, FAILED
    }

    public interface AuthProofProvider { byte[] proofFor(AuthChallenge challenge); }

    public sealed interface Action permits Outbound, CallConnected, InboundData, CallFailed, CallTerminatedAction {}
    public record Outbound(AduFrame frame) implements Action {}
    public record CallConnected(String detail) implements Action {}
    public static final class InboundData implements Action {
        private final byte[] data;
        public InboundData(byte[] data){this.data=data.clone();}
        public byte[] data(){return data.clone();}
    }
    public record CallFailed(DialFailure reason, String detail) implements Action {}
    public record CallTerminatedAction(String reason) implements Action {}

    private final byte[] endpointId;
    private final AuthProofProvider proofProvider;
    private final Supplier<byte[]> callIdFactory;

    private State state = State.NEW;
    private byte[] callId = AduFrame.ZERO_ID.clone();
    private byte[] sessionId = AduFrame.ZERO_ID.clone();
    private long nextRequestId = 1;
    private long expectedRequestId;
    private long outboundSeq;
    private long inboundSeq;
    private long peerWindow = DEFAULT_RECEIVE_WINDOW;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public RelaySessionMachine(byte[] endpointId, AuthProofProvider proofProvider, Supplier<byte[]> callIdFactory) {
        if (endpointId == null || endpointId.length != 32) throw new IllegalArgumentException("endpointId must be 32 bytes");
        this.endpointId = endpointId.clone();
        this.proofProvider = Objects.requireNonNull(proofProvider, "proofProvider");
        this.callIdFactory = Objects.requireNonNull(callIdFactory, "callIdFactory");
    }

    public State state(){return state;}
    public byte[] callId(){return callId.clone();}
    public byte[] sessionId(){return sessionId.clone();}
    public int pendingOutboundBytes(){return pending.size();}
    public long outboundSeq(){return outboundSeq;}
    public long inboundSeq(){return inboundSeq;}
    public long peerWindow(){return peerWindow;}

    public List<Action> onTlsConnected() {
        requireState(State.NEW, "TLS connected");
        long request = takeRequestId();
        expectedRequestId = request;
        state = State.HELLO_SENT;
        return List.of(outbound(new Hello("AndroidDialup", "0.1", 1, 1, endpointId, List.of("BYTE_RELAY")),
                request, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    public List<Action> dial(String target, NetworkTransport transport) {
        requireState(State.IDLE, "dial");
        byte[] generated = callIdFactory.get();
        if (generated == null || generated.length != 16 || isZero(generated)) throw new IllegalStateException("callIdFactory must return nonzero 16-byte ID");
        callId = generated.clone(); sessionId = AduFrame.ZERO_ID.clone(); resetByteRelay();
        long request = takeRequestId(); expectedRequestId = request; state = State.DIALING;
        DialRequest message = new DialRequest(target, Mode.BYTE_RELAY, transport, List.of("BYTE_RELAY"), 60000, List.of());
        return List.of(outbound(message, request, callId, AduFrame.ZERO_ID));
    }

    public List<Action> writeData(byte[] data) {
        requireState(State.CONNECTED, "writeData");
        if (data == null || data.length == 0) return List.of();
        if ((long) pending.size() + data.length > MAX_LOCAL_PENDING) throw new IllegalStateException("BYTE_RELAY local pending limit exceeded");
        pending.writeBytes(data);
        return drainPending();
    }

    public List<Action> drainPending() {
        if (state != State.CONNECTED && state != State.HANGING_UP) return List.of();
        List<Action> actions = new ArrayList<>();
        while (pending.size() > 0 && peerWindow > 0) {
            byte[] all = pending.toByteArray();
            int take = (int)Math.min(Math.min((long)MAX_DATA_BYTES, peerWindow), all.length);
            byte[] chunk = Arrays.copyOfRange(all, 0, take);
            pending.reset();
            if (take < all.length) pending.writeBytes(Arrays.copyOfRange(all, take, all.length));
            actions.add(outbound(new DataBytes(outboundSeq, chunk), 0, callId, sessionId));
            outboundSeq += take;
            peerWindow -= take;
        }
        return List.copyOf(actions);
    }

    public List<Action> hangup(String reason) {
        if (state != State.CONNECTED && state != State.DIALING) throw new IllegalStateException("hangup not allowed in " + state);
        if (isZero(sessionId)) throw new IllegalStateException("hangup requires assigned session ID");
        long request = takeRequestId(); state = State.HANGING_UP;
        return List.of(outbound(new HangupRequest(reason), request, callId, sessionId));
    }

    public List<Action> onFrame(AduFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.kind() == FrameKind.PING && state != State.NEW) {
            Ping ping = (Ping)PayloadCodec.decode(frame.kind(), frame.payload());
            return List.of(outbound(new Pong(ping.nonce()), frame.requestId(), frame.callId(), frame.sessionId()));
        }

        return switch (state) {
            case NEW -> throw new ProtocolException("TLS connection/HELLO required before inbound frames");
            case HELLO_SENT -> onHelloAck(frame);
            case AUTH_BEGIN_SENT -> onAuthChallenge(frame);
            case AUTH_RESPONSE_SENT -> onAuthResult(frame);
            case IDLE -> throw new ProtocolException("unexpected frame while authenticated and idle: " + frame.kind());
            case DIALING, CONNECTED, HANGING_UP -> onActiveCallFrame(frame);
            case FAILED -> throw new ProtocolException("session is failed");
        };
    }

    private List<Action> onHelloAck(AduFrame frame) {
        requireKind(frame, FrameKind.HELLO_ACK); requireZeroIds(frame); requireExpectedRequest(frame);
        HelloAck ack = (HelloAck)PayloadCodec.decode(frame.kind(), frame.payload());
        if (ack.selectedVersion() != 1) throw new ProtocolException("relay selected unsupported protocol version");
        long request = takeRequestId(); expectedRequestId = request; state = State.AUTH_BEGIN_SENT;
        return List.of(outbound(new AuthBegin(), request, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    private List<Action> onAuthChallenge(AduFrame frame) {
        requireKind(frame, FrameKind.AUTH_CHALLENGE); requireZeroIds(frame); requireExpectedRequest(frame);
        AuthChallenge challenge = (AuthChallenge)PayloadCodec.decode(frame.kind(), frame.payload());
        byte[] proof = proofProvider.proofFor(challenge);
        if (proof == null) throw new IllegalStateException("proofProvider returned null");
        long request = takeRequestId(); expectedRequestId = request; state = State.AUTH_RESPONSE_SENT;
        return List.of(outbound(new AuthResponse(proof), request, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    private List<Action> onAuthResult(AduFrame frame) {
        requireZeroIds(frame); requireExpectedRequest(frame);
        if (frame.kind() == FrameKind.AUTH_FAIL) {
            PayloadCodec.decode(frame.kind(), frame.payload()); state = State.FAILED; return List.of();
        }
        requireKind(frame, FrameKind.AUTH_OK);
        PayloadCodec.decode(frame.kind(), frame.payload()); state = State.IDLE; expectedRequestId = 0; return List.of();
    }

    private List<Action> onActiveCallFrame(AduFrame frame) {
        if (frame.kind() == FrameKind.DIAL_ACCEPTED) {
            if (state != State.DIALING) throw new ProtocolException("DIAL_ACCEPTED outside DIALING");
            requireCall(frame, callId); requireExpectedRequest(frame);
            DialAccepted accepted = (DialAccepted)PayloadCodec.decode(frame.kind(), frame.payload());
            if (!Arrays.equals(accepted.callId(), callId)) throw new ProtocolException("DIAL_ACCEPTED payload call_id mismatch");
            if (!Arrays.equals(accepted.assignedSessionId(), frame.sessionId())) throw new ProtocolException("DIAL_ACCEPTED session_id mismatch");
            if (isZero(frame.sessionId())) throw new ProtocolException("DIAL_ACCEPTED requires nonzero session_id");
            sessionId = frame.sessionId(); expectedRequestId = 0; return List.of();
        }

        if (frame.kind() == FrameKind.DIAL_FAILED) {
            if (state != State.DIALING) throw new ProtocolException("DIAL_FAILED outside DIALING");
            requireCall(frame, callId);
            if (!isZero(sessionId) && !Arrays.equals(frame.sessionId(), sessionId)) throw new ProtocolException("DIAL_FAILED session_id mismatch");
            DialFailed failed = (DialFailed)PayloadCodec.decode(frame.kind(), frame.payload());
            if (!Arrays.equals(failed.callId(), callId)) throw new ProtocolException("DIAL_FAILED payload call_id mismatch");
            resetCall(); return List.of(new CallFailed(failed.reason(), failed.humanDetail()));
        }

        requireActiveIds(frame);
        if (frame.kind() == FrameKind.CALL_PROGRESS) {
            CallProgress progress=(CallProgress)PayloadCodec.decode(frame.kind(), frame.payload());
            if(progress.phase()==ProgressPhase.CONNECTED){state=State.CONNECTED;return List.of(new CallConnected(progress.detail()));}
            return List.of();
        }
        if (frame.kind() == FrameKind.FLOW_STATUS) {
            FlowStatus flow=(FlowStatus)PayloadCodec.decode(frame.kind(), frame.payload()); peerWindow=flow.receiveWindowBytes(); return drainPending();
        }
        if (frame.kind() == FrameKind.DATA_BYTES) {
            if(state!=State.CONNECTED)throw new ProtocolException("DATA_BYTES not allowed before CONNECTED");
            DataBytes data=(DataBytes)PayloadCodec.decode(frame.kind(), frame.payload());
            if(data.streamSeq()!=inboundSeq)throw new ProtocolException("BYTE_RELAY sequence mismatch: expected "+Long.toUnsignedString(inboundSeq)+", got "+Long.toUnsignedString(data.streamSeq()));
            inboundSeq += data.data().length;
            return List.of(new InboundData(data.data()), outbound(new FlowStatus(DEFAULT_RECEIVE_WINDOW,0),0,callId,sessionId));
        }
        if (frame.kind() == FrameKind.HANGUP_ACK) { PayloadCodec.decode(frame.kind(),frame.payload()); return List.of(); }
        if (frame.kind() == FrameKind.CALL_TERMINATED) {
            CallTerminated terminated=(CallTerminated)PayloadCodec.decode(frame.kind(),frame.payload()); resetCall(); return List.of(new CallTerminatedAction(terminated.reason()));
        }
        throw new ProtocolException("frame kind not allowed in active call: "+frame.kind());
    }

    private Outbound outbound(Message message,long request,byte[] call,byte[] session){return new Outbound(new AduFrame(PayloadCodec.kindFor(message),0,call,session,request,PayloadCodec.encode(message)));}
    private long takeRequestId(){long value=nextRequestId;nextRequestId=(nextRequestId+1)&0xffff_ffffL;if(nextRequestId==0)nextRequestId=1;return value;}
    private void requireExpectedRequest(AduFrame frame){if(frame.requestId()!=expectedRequestId)throw new ProtocolException("response request_id mismatch");}
    private void requireKind(AduFrame frame,FrameKind kind){if(frame.kind()!=kind)throw new ProtocolException(kind+" required, got "+frame.kind());}
    private void requireZeroIds(AduFrame frame){if(!isZero(frame.callId())||!isZero(frame.sessionId()))throw new ProtocolException("handshake frame IDs must be zero");}
    private void requireCall(AduFrame frame,byte[] expected){if(!Arrays.equals(frame.callId(),expected))throw new ProtocolException("call_id mismatch");}
    private void requireActiveIds(AduFrame frame){requireCall(frame,callId);if(isZero(sessionId)||!Arrays.equals(frame.sessionId(),sessionId))throw new ProtocolException("session_id mismatch");}
    private void requireState(State expected,String action){if(state!=expected)throw new IllegalStateException(action+" not allowed in "+state);}
    private static boolean isZero(byte[] value){for(byte b:value)if(b!=0)return false;return true;}
    private void resetByteRelay(){outboundSeq=0;inboundSeq=0;peerWindow=DEFAULT_RECEIVE_WINDOW;pending.reset();}
    private void resetCall(){state=State.IDLE;callId=AduFrame.ZERO_ID.clone();sessionId=AduFrame.ZERO_ID.clone();expectedRequestId=0;resetByteRelay();}
}
