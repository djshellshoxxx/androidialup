package io.circuitdrift.androidialup.session;

import static io.circuitdrift.androidialup.protocol.Messages.*;

import io.circuitdrift.androidialup.protocol.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pure, single-threaded ADUP v1 client session reducer. It never opens sockets, never sleeps and
 * never reads a clock: the host feeds inbound frames, local commands and monotonic time
 * ({@code nowMs}) and executes the returned {@link Action}s in order.
 *
 * <p>Protocol deadlines (S1_SPEC_FREEZE section 7) and the control heartbeat are serviced by
 * {@link #onTimer(long)}. Every call ends with exactly one terminal action
 * ({@link CallFailed}, {@link CallTerminatedAction} or {@link TransportFailed}) unless the host
 * itself ended it with {@link #hangup(String)}.
 */
public final class RelaySessionMachine {
    public static final int DEFAULT_RECEIVE_WINDOW = 256 * 1024;
    public static final int MAX_LOCAL_PENDING = 256 * 1024;
    /** S1_SPEC_FREEZE control heartbeat failure interval: no PONG within this window fails the transport. */
    public static final long HEARTBEAT_FAILURE_MS = 30_000;
    /** S1_SPEC_FREEZE section 7: relay authentication, TLS connect to AUTH_OK. */
    public static final long AUTH_DEADLINE_MS = 8_000;
    /** S1_SPEC_FREEZE section 7: DIAL_REQUEST acknowledgement (DIAL_ACCEPTED or DIAL_FAILED). */
    public static final long DIAL_ACK_DEADLINE_MS = 5_000;
    /** S1_SPEC_FREEZE section 7: backend dial setup, DIAL_ACCEPTED to CALL_PROGRESS(CONNECTED). */
    public static final long DIAL_SETUP_DEADLINE_MS = 60_000;

    // S1_SPEC_FREEZE section 12 error taxonomy names used by this machine.
    public static final String AUTH_FAILURE = "AUTH_FAILURE";
    public static final String RELAY_UNAVAILABLE = "RELAY_UNAVAILABLE";
    public static final String BACKEND_NO_ANSWER = "BACKEND_NO_ANSWER";
    public static final String PROTOCOL_VERSION_MISMATCH = "PROTOCOL_VERSION_MISMATCH";
    public static final String PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION";
    public static final String QUEUE_OVERFLOW = "QUEUE_OVERFLOW";
    public static final String NETWORK_LOST = "NETWORK_LOST";
    /** Existing heartbeat failure reason (not a section 12 name; see I1_ANDROID_RELAY_SESSION_STATUS). */
    public static final String HEARTBEAT_TIMEOUT = "HEARTBEAT_TIMEOUT";

    public enum State {
        NEW, HELLO_SENT, AUTH_BEGIN_SENT, AUTH_RESPONSE_SENT,
        IDLE, DIALING, CONNECTED, HANGING_UP, FAILED
    }

    /** The single protocol deadline currently armed, if any. */
    public enum Deadline { NONE, AUTHENTICATION, DIAL_ACK, DIAL_SETUP }

    /**
     * Computes {@code AUTH_RESPONSE.proof}. {@code relayId} is the value from HELLO_ACK and
     * {@code endpointId} the 32 bytes sent in HELLO. Throwing {@link ProtocolException} rejects
     * the challenge and no AUTH_RESPONSE is sent.
     */
    @FunctionalInterface
    public interface AuthProofProvider { byte[] proofFor(String relayId, byte[] endpointId, AuthChallenge challenge); }

    /** Configurable protocol timing (S1 section 7 says values are configurable). */
    public record Options(long authDeadlineMs, long dialAckDeadlineMs, long dialSetupDeadlineMs) {
        public static final Options DEFAULTS = new Options(AUTH_DEADLINE_MS, DIAL_ACK_DEADLINE_MS, DIAL_SETUP_DEADLINE_MS);
        public Options {
            if (authDeadlineMs <= 0 || dialAckDeadlineMs <= 0 || dialSetupDeadlineMs <= 0) {
                throw new IllegalArgumentException("deadlines must be positive");
            }
            if (dialSetupDeadlineMs > 0xffff_ffffL) throw new IllegalArgumentException("dialSetupDeadlineMs must fit u32");
        }
        public Options withDeadlines(long authMs, long dialAckMs, long dialSetupMs) {
            return new Options(authMs, dialAckMs, dialSetupMs);
        }
    }

    public sealed interface Action permits Outbound, CallConnected, InboundData, CallFailed, CallTerminatedAction, TransportFailed {}
    public record Outbound(AduFrame frame) implements Action {}
    public record CallConnected(String detail) implements Action {}
    public static final class InboundData implements Action {
        private final byte[] data;
        public InboundData(byte[] data){this.data=data.clone();}
        public byte[] data(){return data.clone();}
        @Override public String toString(){return "InboundData[" + data.length + " bytes]";}
    }
    /** The dial failed before CONNECTED. {@link #terminalReason()} is the S1 section 12 name. */
    public record CallFailed(DialFailure reason, String detail) implements Action {
        public String terminalReason() { return taxonomyFor(reason); }
    }
    public record CallTerminatedAction(String reason) implements Action {}
    /**
     * Emitted once when the transport must be closed; an active call or dial maps to NO CARRIER.
     * {@code detail} is optional structured context and never contains secrets.
     */
    public record TransportFailed(String reason, String detail) implements Action {
        public TransportFailed(String reason) { this(reason, null); }
    }

    private final byte[] endpointId;
    private final AuthProofProvider proofProvider;
    private final Supplier<byte[]> callIdFactory;
    private final Options options;

    private State state = State.NEW;
    private String relayId;
    private byte[] callId = AduFrame.ZERO_ID.clone();
    private byte[] sessionId = AduFrame.ZERO_ID.clone();
    private long nextRequestId = 1;
    private long expectedRequestId;
    private long hangupRequestId;
    private long outboundSeq;
    private long inboundSeq;
    private long peerWindow = DEFAULT_RECEIVE_WINDOW;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private long lastNowMs;
    private Deadline deadline = Deadline.NONE;
    private long deadlineAtMs;
    private boolean callTerminalReported;
    private boolean cancelOnAccept;
    private String cancelReason;
    private String terminalReason;

    private long heartbeatIntervalMs;
    private long lastPeerActivityMs;
    private boolean pingOutstanding;
    private long outstandingPingNonce;
    private long pingSentAtMs;
    private long nextPingNonce = 1;

    public RelaySessionMachine(byte[] endpointId, AuthProofProvider proofProvider, Supplier<byte[]> callIdFactory) {
        this(endpointId, proofProvider, callIdFactory, Options.DEFAULTS);
    }

    public RelaySessionMachine(byte[] endpointId, AuthProofProvider proofProvider, Supplier<byte[]> callIdFactory, Options options) {
        if (endpointId == null || endpointId.length != 32) throw new IllegalArgumentException("endpointId must be 32 bytes");
        this.endpointId = endpointId.clone();
        this.proofProvider = Objects.requireNonNull(proofProvider, "proofProvider");
        this.callIdFactory = Objects.requireNonNull(callIdFactory, "callIdFactory");
        this.options = Objects.requireNonNull(options, "options");
    }

    public State state(){return state;}
    /** relay_id from HELLO_ACK, or null before it arrived. */
    public String relayId(){return relayId;}
    public byte[] callId(){return callId.clone();}
    public byte[] sessionId(){return sessionId.clone();}
    public int pendingOutboundBytes(){return pending.size();}
    public long outboundSeq(){return outboundSeq;}
    public long inboundSeq(){return inboundSeq;}
    public long peerWindow(){return peerWindow;}
    public boolean heartbeatOutstanding(){return pingOutstanding;}
    public long lastPeerActivityMs(){return lastPeerActivityMs;}
    public long heartbeatIntervalMs(){return heartbeatIntervalMs;}
    public Options options(){return options;}
    /** The armed protocol deadline, {@link Deadline#NONE} if none. */
    public Deadline activeDeadline(){return deadline;}
    /** Monotonic expiry of {@link #activeDeadline()}; meaningless when NONE. */
    public long deadlineAtMs(){return deadlineAtMs;}
    /** S1 section 12 reason of the most recent call or transport termination, or null. */
    public String terminalReason(){return terminalReason;}
    /** True once the host has requested a call (dialing or connected) and the relay has not ended it. */
    public boolean callActive(){return state == State.DIALING || state == State.CONNECTED || state == State.HANGING_UP;}

    /** Convenience overload using the latest time seen by the machine. */
    public List<Action> onTlsConnected() { return onTlsConnected(lastNowMs); }

    public List<Action> onTlsConnected(long nowMs) {
        requireState(State.NEW, "TLS connected");
        advance(nowMs);
        lastPeerActivityMs = nowMs;
        arm(Deadline.AUTHENTICATION, nowMs + options.authDeadlineMs());
        long request = takeRequestId();
        expectedRequestId = request;
        state = State.HELLO_SENT;
        return List.of(outbound(new Hello("AndroidDialup", "0.1", 1, 1, endpointId, List.of("BYTE_RELAY")),
                request, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    /** Convenience overload using the latest time seen by the machine. */
    public List<Action> dial(String target, NetworkTransport transport) { return dial(target, transport, lastNowMs); }

    public List<Action> dial(String target, NetworkTransport transport, long nowMs) {
        requireState(State.IDLE, "dial");
        advance(nowMs);
        byte[] generated = callIdFactory.get();
        if (generated == null || generated.length != 16 || isZero(generated)) throw new IllegalStateException("callIdFactory must return nonzero 16-byte ID");
        DialRequest message = new DialRequest(target, Mode.BYTE_RELAY, transport, List.of("BYTE_RELAY"), options.dialSetupDeadlineMs(), List.of());
        callId = generated.clone(); sessionId = AduFrame.ZERO_ID.clone(); resetByteRelay();
        callTerminalReported = false; cancelOnAccept = false; cancelReason = null; terminalReason = null;
        long request = takeRequestId(); expectedRequestId = request; state = State.DIALING;
        arm(Deadline.DIAL_ACK, nowMs + options.dialAckDeadlineMs());
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

    /**
     * Local hangup or dial cancel. The host initiated it, so no terminal action follows for this
     * call; {@code reason} becomes the terminal reason. Before DIAL_ACCEPTED (no session ID yet)
     * the HANGUP_REQUEST is deferred until the relay assigns one. Idempotent while hanging up.
     */
    public List<Action> hangup(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (state == State.HANGING_UP || (state == State.DIALING && cancelOnAccept)) return List.of();
        if (state != State.CONNECTED && state != State.DIALING) throw new IllegalStateException("hangup not allowed in " + state);
        callTerminalReported = true;
        terminalReason = reason;
        if (isZero(sessionId)) {
            cancelOnAccept = true;
            cancelReason = reason;
            return List.of();
        }
        return List.of(sendHangup(reason));
    }

    /**
     * Host-reported fatal transport condition (socket closed, decode error, local protocol
     * violation). Emits one {@link TransportFailed}; later calls are no-ops.
     */
    public List<Action> fail(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (state == State.FAILED) return List.of();
        return List.of(transportFailed(reason, null));
    }

    /** Convenience overload using the latest time seen by the machine. */
    public List<Action> onFrame(AduFrame frame) { return onFrame(frame, lastNowMs); }

    /**
     * Handles one inbound frame received at monotonic time {@code nowMs}. Every accepted
     * frame refreshes peer activity. Heartbeat frames are handled in any post-HELLO state.
     */
    public List<Action> onFrame(AduFrame frame, long nowMs) {
        Objects.requireNonNull(frame, "frame");
        if (state == State.FAILED) throw new ProtocolException("session is failed");
        advance(nowMs);
        if (frame.kind() == FrameKind.PING && state != State.NEW) {
            Ping ping = (Ping)PayloadCodec.decode(frame.kind(), frame.payload());
            lastPeerActivityMs = nowMs;
            return List.of(outbound(new Pong(ping.nonce()), frame.requestId(), frame.callId(), frame.sessionId()));
        }
        if (frame.kind() == FrameKind.PONG && state != State.NEW) {
            Pong pong = (Pong)PayloadCodec.decode(frame.kind(), frame.payload());
            if (!pingOutstanding) throw new ProtocolException("unsolicited PONG");
            if (pong.nonce() != outstandingPingNonce) throw new ProtocolException("PONG nonce does not match outstanding PING");
            pingOutstanding = false;
            lastPeerActivityMs = nowMs;
            return List.of();
        }
        List<Action> actions = dispatch(frame, nowMs);
        lastPeerActivityMs = nowMs;
        return actions;
    }

    /**
     * Services protocol deadlines and heartbeat timing at monotonic time {@code nowMs}. Never
     * sleeps. Each deadline fires at most once. Sends a PING once the negotiated interval elapses
     * without peer activity, and emits exactly one {@link TransportFailed} when that PING goes
     * unanswered for {@link #HEARTBEAT_FAILURE_MS}.
     */
    public List<Action> onTimer(long nowMs) {
        if (state == State.FAILED) return List.of();
        advance(nowMs);
        if (deadline != Deadline.NONE && nowMs - deadlineAtMs >= 0) {
            List<Action> expired = expireDeadline();
            if (!expired.isEmpty()) return expired;
        }
        if (!authenticated() || heartbeatIntervalMs <= 0) return List.of();
        if (pingOutstanding) {
            if (nowMs - pingSentAtMs < HEARTBEAT_FAILURE_MS) return List.of();
            pingOutstanding = false;
            return List.of(transportFailed(HEARTBEAT_TIMEOUT, "no PONG within " + HEARTBEAT_FAILURE_MS + " ms"));
        }
        if (nowMs - lastPeerActivityMs < heartbeatIntervalMs) return List.of();
        long nonce = nextPingNonce++;
        pingOutstanding = true;
        outstandingPingNonce = nonce;
        pingSentAtMs = nowMs;
        return List.of(outbound(new Ping(nonce, nowMs), 0, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    private List<Action> expireDeadline() {
        Deadline expired = deadline;
        disarm();
        switch (expired) {
            case AUTHENTICATION:
                return List.of(transportFailed(AUTH_FAILURE, "relay authentication deadline " + options.authDeadlineMs() + " ms expired"));
            case DIAL_ACK:
                return List.of(transportFailed(RELAY_UNAVAILABLE, "DIAL_REQUEST acknowledgement deadline " + options.dialAckDeadlineMs() + " ms expired"));
            case DIAL_SETUP: {
                List<Action> actions = new ArrayList<>();
                boolean report = !callTerminalReported;
                actions.add(sendHangup(BACKEND_NO_ANSWER));
                if (report) {
                    callTerminalReported = true;
                    terminalReason = BACKEND_NO_ANSWER;
                    actions.add(new CallFailed(DialFailure.TIMEOUT, "backend dial setup deadline " + options.dialSetupDeadlineMs() + " ms expired"));
                }
                return List.copyOf(actions);
            }
            default:
                return List.of();
        }
    }

    private boolean authenticated() {
        return state == State.IDLE || state == State.DIALING || state == State.CONNECTED || state == State.HANGING_UP;
    }

    private List<Action> dispatch(AduFrame frame, long nowMs) {
        return switch (state) {
            case NEW -> throw new ProtocolException("TLS connection/HELLO required before inbound frames");
            case HELLO_SENT -> onHelloAck(frame);
            case AUTH_BEGIN_SENT -> onAuthChallenge(frame);
            case AUTH_RESPONSE_SENT -> onAuthResult(frame);
            case IDLE -> throw new ProtocolException("unexpected frame while authenticated and idle: " + frame.kind());
            case DIALING, CONNECTED, HANGING_UP -> onActiveCallFrame(frame, nowMs);
            case FAILED -> throw new ProtocolException("session is failed");
        };
    }

    private List<Action> onHelloAck(AduFrame frame) {
        requireZeroIds(frame); requireExpectedRequest(frame);
        if (frame.kind() == FrameKind.HELLO_REJECT) {
            HelloReject reject = (HelloReject)PayloadCodec.decode(frame.kind(), frame.payload());
            return List.of(transportFailed(PROTOCOL_VERSION_MISMATCH, reject.reason()));
        }
        requireKind(frame, FrameKind.HELLO_ACK);
        HelloAck ack = (HelloAck)PayloadCodec.decode(frame.kind(), frame.payload());
        if (ack.selectedVersion() != 1) throw new ProtocolException("relay selected unsupported protocol version");
        heartbeatIntervalMs = ack.heartbeatSeconds() * 1000L;
        relayId = ack.relayId();
        long request = takeRequestId(); expectedRequestId = request; state = State.AUTH_BEGIN_SENT;
        return List.of(outbound(new AuthBegin(), request, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    private List<Action> onAuthChallenge(AduFrame frame) {
        requireKind(frame, FrameKind.AUTH_CHALLENGE); requireZeroIds(frame); requireExpectedRequest(frame);
        AuthChallenge challenge = (AuthChallenge)PayloadCodec.decode(frame.kind(), frame.payload());
        byte[] proof = proofProvider.proofFor(relayId, endpointId.clone(), challenge);
        if (proof == null) throw new IllegalStateException("proofProvider returned null");
        long request = takeRequestId(); expectedRequestId = request; state = State.AUTH_RESPONSE_SENT;
        return List.of(outbound(new AuthResponse(proof), request, AduFrame.ZERO_ID, AduFrame.ZERO_ID));
    }

    private List<Action> onAuthResult(AduFrame frame) {
        requireZeroIds(frame); requireExpectedRequest(frame);
        if (frame.kind() == FrameKind.AUTH_FAIL) {
            AuthFail fail = (AuthFail)PayloadCodec.decode(frame.kind(), frame.payload());
            return List.of(transportFailed(AUTH_FAILURE, fail.reason()));
        }
        requireKind(frame, FrameKind.AUTH_OK);
        PayloadCodec.decode(frame.kind(), frame.payload());
        state = State.IDLE; expectedRequestId = 0; disarm();
        return List.of();
    }

    private List<Action> onActiveCallFrame(AduFrame frame, long nowMs) {
        if (frame.kind() == FrameKind.DIAL_ACCEPTED) {
            if (state != State.DIALING) throw new ProtocolException("DIAL_ACCEPTED outside DIALING");
            requireCall(frame, callId); requireExpectedRequest(frame);
            DialAccepted accepted = (DialAccepted)PayloadCodec.decode(frame.kind(), frame.payload());
            if (!Arrays.equals(accepted.callId(), callId)) throw new ProtocolException("DIAL_ACCEPTED payload call_id mismatch");
            if (!Arrays.equals(accepted.assignedSessionId(), frame.sessionId())) throw new ProtocolException("DIAL_ACCEPTED session_id mismatch");
            if (isZero(frame.sessionId())) throw new ProtocolException("DIAL_ACCEPTED requires nonzero session_id");
            sessionId = frame.sessionId(); expectedRequestId = 0;
            if (cancelOnAccept) {
                cancelOnAccept = false;
                return List.of(sendHangup(cancelReason));
            }
            arm(Deadline.DIAL_SETUP, nowMs + options.dialSetupDeadlineMs());
            return List.of();
        }

        if (frame.kind() == FrameKind.DIAL_FAILED) {
            if (state != State.DIALING) throw new ProtocolException("DIAL_FAILED outside DIALING");
            requireCall(frame, callId);
            if (!isZero(sessionId) && !Arrays.equals(frame.sessionId(), sessionId)) throw new ProtocolException("DIAL_FAILED session_id mismatch");
            DialFailed failed = (DialFailed)PayloadCodec.decode(frame.kind(), frame.payload());
            if (!Arrays.equals(failed.callId(), callId)) throw new ProtocolException("DIAL_FAILED payload call_id mismatch");
            boolean report = !callTerminalReported;
            if (report) terminalReason = taxonomyFor(failed.reason());
            resetCall();
            return report ? List.of(new CallFailed(failed.reason(), failed.humanDetail())) : List.of();
        }

        requireActiveIds(frame);
        if (frame.kind() == FrameKind.CALL_PROGRESS) {
            CallProgress progress=(CallProgress)PayloadCodec.decode(frame.kind(), frame.payload());
            if(state==State.DIALING && progress.phase()==ProgressPhase.CONNECTED){
                state=State.CONNECTED; disarm();
                return List.of(new CallConnected(progress.detail()));
            }
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
        if (frame.kind() == FrameKind.HANGUP_ACK) {
            if (state != State.HANGING_UP) throw new ProtocolException("HANGUP_ACK without HANGUP_REQUEST");
            if (frame.requestId() != hangupRequestId) throw new ProtocolException("HANGUP_ACK request_id mismatch");
            PayloadCodec.decode(frame.kind(),frame.payload());
            return List.of();
        }
        if (frame.kind() == FrameKind.CALL_TERMINATED) {
            CallTerminated terminated=(CallTerminated)PayloadCodec.decode(frame.kind(),frame.payload());
            boolean report = !callTerminalReported;
            if (report) terminalReason = terminated.reason();
            resetCall();
            return report ? List.of(new CallTerminatedAction(terminated.reason())) : List.of();
        }
        throw new ProtocolException("frame kind not allowed in active call: "+frame.kind());
    }

    /** S1 section 12 name for a wire dial failure. */
    static String taxonomyFor(DialFailure failure) {
        return switch (failure) {
            case NO_ROUTE -> "NO_ROUTE";
            case GATEWAY_UNAVAILABLE -> "BACKEND_UNAVAILABLE";
            case BUSY -> "BACKEND_BUSY";
            case NO_DIALTONE -> "BACKEND_NO_DIALTONE";
            case NO_ANSWER, TIMEOUT -> BACKEND_NO_ANSWER;
            case AUTHORIZATION_DENIED -> AUTH_FAILURE;
            case UNSUPPORTED_MODE -> "LOCAL_CONFIG";
            case INTERNAL_ERROR -> "INTERNAL_ERROR";
        };
    }

    private Outbound sendHangup(String reason) {
        long request = takeRequestId();
        hangupRequestId = request;
        state = State.HANGING_UP;
        disarm();
        return outbound(new HangupRequest(reason), request, callId, sessionId);
    }

    private TransportFailed transportFailed(String reason, String detail) {
        state = State.FAILED;
        disarm();
        pingOutstanding = false;
        terminalReason = reason;
        return new TransportFailed(reason, detail);
    }

    private void arm(Deadline kind, long atMs) { deadline = kind; deadlineAtMs = atMs; }
    private void disarm() { deadline = Deadline.NONE; deadlineAtMs = 0; }
    private void advance(long nowMs) { if (nowMs - lastNowMs > 0) lastNowMs = nowMs; }

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
    private void resetCall(){state=State.IDLE;callId=AduFrame.ZERO_ID.clone();sessionId=AduFrame.ZERO_ID.clone();expectedRequestId=0;hangupRequestId=0;cancelOnAccept=false;cancelReason=null;disarm();resetByteRelay();}
}
