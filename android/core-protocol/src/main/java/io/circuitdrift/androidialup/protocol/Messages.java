package io.circuitdrift.androidialup.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class Messages {
    private Messages() {}

    public static final int MAX_SHORT_STRING = 4096;
    public static final int MAX_TARGET_UTF8 = 256;
    public static final int MAX_CAPABILITIES = 128;
    public static final int MAX_MAP_ENTRIES = 128;
    public static final int MAX_BLOB = 65535;
    public static final int MAX_DATA_BYTES = 32768;

    public sealed interface Message permits Hello, HelloAck, HelloReject, AuthBegin,
            AuthChallenge, AuthResponse, AuthOk, AuthFail, DialRequest, DialAccepted,
            DialFailed, CallProgress, CallTerminated, DataBytes, FlowStatus, Ping, Pong,
            HangupRequest, HangupAck {}

    public interface ValuedEnum { int value(); }

    public enum Mode implements ValuedEnum {
        BYTE_RELAY(1), PCM_VBD_EXPERIMENTAL(2), V152_RTP_RESERVED(100), V1501_SPRT_RESERVED(101);
        private final int value;
        Mode(int value) { this.value = value; }
        public int value() { return value; }
        public static Mode fromValue(int value) { return enumValue(values(), value, "mode"); }
    }

    public enum NetworkTransport implements ValuedEnum {
        WIFI(1), CELLULAR(2), ETHERNET(3), OTHER(4);
        private final int value;
        NetworkTransport(int value) { this.value = value; }
        public int value() { return value; }
        public static NetworkTransport fromValue(int value) { return enumValue(values(), value, "network transport"); }
    }

    public enum DialFailure implements ValuedEnum {
        NO_ROUTE(1), GATEWAY_UNAVAILABLE(2), BUSY(3), NO_DIALTONE(4), NO_ANSWER(5),
        AUTHORIZATION_DENIED(6), UNSUPPORTED_MODE(7), TIMEOUT(8), INTERNAL_ERROR(9);
        private final int value;
        DialFailure(int value) { this.value = value; }
        public int value() { return value; }
        public static DialFailure fromValue(int value) { return enumValue(values(), value, "dial failure"); }
    }

    public enum ProgressPhase implements ValuedEnum {
        ROUTING(1), GATEWAY_CONNECTING(2), DIALING(3), RINGBACK(4), NEGOTIATING(5), CONNECTED(6);
        private final int value;
        ProgressPhase(int value) { this.value = value; }
        public int value() { return value; }
        public static ProgressPhase fromValue(int value) { return enumValue(values(), value, "progress phase"); }
    }

    public enum TerminationSource implements ValuedEnum {
        LOCAL(1), RELAY(2), GATEWAY(3), REMOTE(4);
        private final int value;
        TerminationSource(int value) { this.value = value; }
        public int value() { return value; }
        public static TerminationSource fromValue(int value) { return enumValue(values(), value, "termination source"); }
    }

    private static <E extends Enum<E> & ValuedEnum> E enumValue(E[] values, int raw, String label) {
        for (E value : values) if (value.value() == raw) return value;
        throw new ProtocolException("unknown " + label + " value " + raw);
    }

    public record KeyValue(String key, String value) {
        public KeyValue {
            validateString(key, "key");
            validateString(value, "value");
        }
    }

    public static final class Hello implements Message {
        private final String clientName, clientVersion;
        private final int protocolMin, protocolMax;
        private final byte[] endpointId;
        private final List<String> capabilities;
        public Hello(String clientName, String clientVersion, int protocolMin, int protocolMax,
                     byte[] endpointId, List<String> capabilities) {
            validateString(clientName, "clientName"); validateString(clientVersion, "clientVersion");
            validateU16(protocolMin, "protocolMin"); validateU16(protocolMax, "protocolMax");
            validateLength(endpointId, 32, "endpointId"); validateStrings(capabilities, "capabilities");
            this.clientName = clientName; this.clientVersion = clientVersion;
            this.protocolMin = protocolMin; this.protocolMax = protocolMax;
            this.endpointId = endpointId.clone(); this.capabilities = List.copyOf(capabilities);
        }
        public String clientName(){return clientName;} public String clientVersion(){return clientVersion;}
        public int protocolMin(){return protocolMin;} public int protocolMax(){return protocolMax;}
        public byte[] endpointId(){return endpointId.clone();} public List<String> capabilities(){return capabilities;}
        @Override public boolean equals(Object o){return o instanceof Hello x && protocolMin==x.protocolMin && protocolMax==x.protocolMax && clientName.equals(x.clientName) && clientVersion.equals(x.clientVersion) && Arrays.equals(endpointId,x.endpointId) && capabilities.equals(x.capabilities);}
        @Override public int hashCode(){return Objects.hash(clientName,clientVersion,protocolMin,protocolMax,Arrays.hashCode(endpointId),capabilities);}
    }

    public record HelloAck(int selectedVersion, String relayId, long maxFramePayload,
                           long heartbeatSeconds, List<String> capabilities) implements Message {
        public HelloAck {
            validateU16(selectedVersion,"selectedVersion"); validateString(relayId,"relayId");
            if(maxFramePayload<1 || maxFramePayload>FrameCodec.MAX_PAYLOAD) throw new IllegalArgumentException("maxFramePayload out of range");
            validateU32(heartbeatSeconds,"heartbeatSeconds"); validateStrings(capabilities,"capabilities"); capabilities=List.copyOf(capabilities);
        }
    }
    public record HelloReject(String reason) implements Message { public HelloReject { validateString(reason,"reason"); } }
    public record AuthBegin() implements Message {}

    public static final class AuthChallenge implements Message {
        private final byte[] nonce; private final String method;
        public AuthChallenge(byte[] nonce,String method){validateBlob(nonce,"nonce");validateString(method,"method");this.nonce=nonce.clone();this.method=method;}
        public byte[] nonce(){return nonce.clone();} public String method(){return method;}
        @Override public boolean equals(Object o){return o instanceof AuthChallenge x && Arrays.equals(nonce,x.nonce)&&method.equals(x.method);}
        @Override public int hashCode(){return 31*Arrays.hashCode(nonce)+method.hashCode();}
    }
    public static final class AuthResponse implements Message {
        private final byte[] proof;
        public AuthResponse(byte[] proof){validateBlob(proof,"proof");this.proof=proof.clone();}
        public byte[] proof(){return proof.clone();}
        @Override public boolean equals(Object o){return o instanceof AuthResponse x&&Arrays.equals(proof,x.proof);}
        @Override public int hashCode(){return Arrays.hashCode(proof);}
    }
    public static final class AuthOk implements Message {
        private final byte[] endpointId; private final List<KeyValue> policy;
        public AuthOk(byte[] endpointId,List<KeyValue> policy){validateLength(endpointId,32,"endpointId");validateMap(policy,"policy");this.endpointId=endpointId.clone();this.policy=List.copyOf(policy);}
        public byte[] endpointId(){return endpointId.clone();} public List<KeyValue> policy(){return policy;}
        @Override public boolean equals(Object o){return o instanceof AuthOk x&&Arrays.equals(endpointId,x.endpointId)&&policy.equals(x.policy);}
        @Override public int hashCode(){return 31*Arrays.hashCode(endpointId)+policy.hashCode();}
    }
    public record AuthFail(String reason) implements Message { public AuthFail { validateString(reason,"reason"); } }

    public record DialRequest(String target, Mode requestedMode, NetworkTransport networkTransport,
                              List<String> clientCapabilities, long dialTimeoutMs,
                              List<KeyValue> callerMetadata) implements Message {
        public DialRequest {
            Objects.requireNonNull(requestedMode); Objects.requireNonNull(networkTransport);
            int targetBytes=utf8Length(target); if(targetBytes<1||targetBytes>MAX_TARGET_UTF8) throw new IllegalArgumentException("target must be 1..256 UTF-8 bytes");
            validateStrings(clientCapabilities,"clientCapabilities"); validateU32(dialTimeoutMs,"dialTimeoutMs"); validateMap(callerMetadata,"callerMetadata");
            clientCapabilities=List.copyOf(clientCapabilities); callerMetadata=List.copyOf(callerMetadata);
        }
    }

    public static final class DialAccepted implements Message {
        private final byte[] callId, assignedSessionId; private final String selectedGatewayId; private final Mode selectedMode;
        public DialAccepted(byte[] callId,byte[] assignedSessionId,String selectedGatewayId,Mode selectedMode){validateLength(callId,16,"callId");validateLength(assignedSessionId,16,"assignedSessionId");validateString(selectedGatewayId,"selectedGatewayId");this.callId=callId.clone();this.assignedSessionId=assignedSessionId.clone();this.selectedGatewayId=selectedGatewayId;this.selectedMode=Objects.requireNonNull(selectedMode);}
        public byte[] callId(){return callId.clone();} public byte[] assignedSessionId(){return assignedSessionId.clone();} public String selectedGatewayId(){return selectedGatewayId;} public Mode selectedMode(){return selectedMode;}
        @Override public boolean equals(Object o){return o instanceof DialAccepted x&&Arrays.equals(callId,x.callId)&&Arrays.equals(assignedSessionId,x.assignedSessionId)&&selectedGatewayId.equals(x.selectedGatewayId)&&selectedMode==x.selectedMode;}
        @Override public int hashCode(){return Objects.hash(Arrays.hashCode(callId),Arrays.hashCode(assignedSessionId),selectedGatewayId,selectedMode);}
    }

    public static final class DialFailed implements Message {
        private final byte[] callId; private final DialFailure reason; private final boolean retryable; private final String humanDetail;
        public DialFailed(byte[] callId,DialFailure reason,boolean retryable,String humanDetail){validateLength(callId,16,"callId");if(humanDetail!=null)validateString(humanDetail,"humanDetail");this.callId=callId.clone();this.reason=Objects.requireNonNull(reason);this.retryable=retryable;this.humanDetail=humanDetail;}
        public byte[] callId(){return callId.clone();} public DialFailure reason(){return reason;} public boolean retryable(){return retryable;} public String humanDetail(){return humanDetail;}
        @Override public boolean equals(Object o){return o instanceof DialFailed x&&Arrays.equals(callId,x.callId)&&reason==x.reason&&retryable==x.retryable&&Objects.equals(humanDetail,x.humanDetail);}
        @Override public int hashCode(){return Objects.hash(Arrays.hashCode(callId),reason,retryable,humanDetail);}
    }

    public record CallProgress(ProgressPhase phase,String detail) implements Message { public CallProgress { Objects.requireNonNull(phase); if(detail!=null)validateString(detail,"detail"); } }
    public record CallTerminated(String reason,TerminationSource source,String diagnosticCode) implements Message { public CallTerminated { validateString(reason,"reason");Objects.requireNonNull(source);if(diagnosticCode!=null)validateString(diagnosticCode,"diagnosticCode"); } }

    public static final class DataBytes implements Message {
        private final long streamSeq; private final byte[] data;
        public DataBytes(long streamSeq,byte[] data){Objects.requireNonNull(data);if(data.length<1||data.length>MAX_DATA_BYTES)throw new IllegalArgumentException("DATA_BYTES data must be 1..32768 bytes");this.streamSeq=streamSeq;this.data=data.clone();}
        public long streamSeq(){return streamSeq;} public byte[] data(){return data.clone();}
        @Override public boolean equals(Object o){return o instanceof DataBytes x&&streamSeq==x.streamSeq&&Arrays.equals(data,x.data);}
        @Override public int hashCode(){return 31*Long.hashCode(streamSeq)+Arrays.hashCode(data);}
    }
    public record FlowStatus(long receiveWindowBytes,long queuedBytes) implements Message { public FlowStatus { validateU32(receiveWindowBytes,"receiveWindowBytes");validateU32(queuedBytes,"queuedBytes"); } }
    public record Ping(long nonce,long monotonicHint) implements Message {}
    public record Pong(long nonce) implements Message {}
    public record HangupRequest(String reason) implements Message { public HangupRequest { validateString(reason,"reason"); } }
    public record HangupAck() implements Message {}

    static void validateString(String value,String name){Objects.requireNonNull(value,name);if(utf8Length(value)>MAX_SHORT_STRING)throw new IllegalArgumentException(name+" exceeds 4096 UTF-8 bytes");}
    static int utf8Length(String value){return Objects.requireNonNull(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    static void validateStrings(List<String> values,String name){Objects.requireNonNull(values,name);if(values.size()>MAX_CAPABILITIES)throw new IllegalArgumentException(name+" has too many entries");for(String value:values)validateString(value,name);}
    static void validateMap(List<KeyValue> values,String name){Objects.requireNonNull(values,name);if(values.size()>MAX_MAP_ENTRIES)throw new IllegalArgumentException(name+" has too many entries");}
    static void validateBlob(byte[] value,String name){Objects.requireNonNull(value,name);if(value.length>MAX_BLOB)throw new IllegalArgumentException(name+" exceeds 65535 bytes");}
    static void validateLength(byte[] value,int len,String name){Objects.requireNonNull(value,name);if(value.length!=len)throw new IllegalArgumentException(name+" must be exactly "+len+" bytes");}
    static void validateU16(long value,String name){if(value<0||value>0xffffL)throw new IllegalArgumentException(name+" must fit u16");}
    static void validateU32(long value,String name){if(value<0||value>0xffff_ffffL)throw new IllegalArgumentException(name+" must fit u32");}
}
