package io.circuitdrift.androidialup.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ADUP v1 typed payload codec. Byte-for-byte compatible with
 * prototype/python/androidialup_protocol/messages.py: all integers are big-endian, strings are
 * {@code u16 length + UTF-8}, optional strings carry a {@code u8} presence marker (0/1), string
 * lists and maps are {@code u16 count} followed by their entries, blobs are {@code u16 length +
 * bytes}.
 *
 * <p>Encoding errors (limit violations) surface as {@link IllegalArgumentException}; decoding
 * errors surface as {@link ProtocolException}. The decoder never allocates from an untrusted
 * length before checking it against the corresponding limit.
 */
public final class PayloadCodec {
    private PayloadCodec() {}

    /** Returns the frame kind that carries {@code message}. */
    public static FrameKind kindFor(AduMessage message) {
        Objects.requireNonNull(message, "message");
        if (message instanceof Hello) return FrameKind.HELLO;
        if (message instanceof HelloAck) return FrameKind.HELLO_ACK;
        if (message instanceof HelloReject) return FrameKind.HELLO_REJECT;
        if (message instanceof AuthBegin) return FrameKind.AUTH_BEGIN;
        if (message instanceof AuthChallenge) return FrameKind.AUTH_CHALLENGE;
        if (message instanceof AuthResponse) return FrameKind.AUTH_RESPONSE;
        if (message instanceof AuthOk) return FrameKind.AUTH_OK;
        if (message instanceof AuthFail) return FrameKind.AUTH_FAIL;
        if (message instanceof DialRequest) return FrameKind.DIAL_REQUEST;
        if (message instanceof DialAccepted) return FrameKind.DIAL_ACCEPTED;
        if (message instanceof DialFailed) return FrameKind.DIAL_FAILED;
        if (message instanceof CallProgress) return FrameKind.CALL_PROGRESS;
        if (message instanceof CallTerminated) return FrameKind.CALL_TERMINATED;
        if (message instanceof DataBytes) return FrameKind.DATA_BYTES;
        if (message instanceof FlowStatus) return FrameKind.FLOW_STATUS;
        if (message instanceof Ping) return FrameKind.PING;
        if (message instanceof Pong) return FrameKind.PONG;
        if (message instanceof HangupRequest) return FrameKind.HANGUP_REQUEST;
        if (message instanceof HangupAck) return FrameKind.HANGUP_ACK;
        throw new IllegalArgumentException("unsupported message type " + message.getClass().getSimpleName());
    }

    /** Encodes {@code message} to its payload bytes. */
    public static byte[] encode(AduMessage message) {
        Objects.requireNonNull(message, "message");
        Writer w = new Writer();
        if (message instanceof Hello m) {
            w.text(m.clientName()); w.text(m.clientVersion()); w.u16(m.protocolMin()); w.u16(m.protocolMax());
            w.fixed(m.endpointId(), 32); w.strings(m.capabilities());
        } else if (message instanceof HelloAck m) {
            w.u16(m.selectedVersion()); w.text(m.relayId()); w.u32(m.maxFramePayload()); w.u32(m.heartbeatSeconds());
            w.strings(m.capabilities());
        } else if (message instanceof HelloReject m) {
            w.text(m.reason());
        } else if (message instanceof AuthBegin) {
            // empty payload
        } else if (message instanceof AuthChallenge m) {
            w.blob16(m.nonce()); w.text(m.method());
        } else if (message instanceof AuthResponse m) {
            w.blob16(m.proof());
        } else if (message instanceof AuthOk m) {
            w.fixed(m.endpointId(), 32); w.mapping(m.policy());
        } else if (message instanceof AuthFail m) {
            w.text(m.reason());
        } else if (message instanceof DialRequest m) {
            w.text(m.target()); w.u16(m.requestedMode().value()); w.u8(m.networkTransport().value());
            w.strings(m.clientCapabilities()); w.u32(m.dialTimeoutMs()); w.mapping(m.callerMetadata());
        } else if (message instanceof DialAccepted m) {
            w.fixed(m.callId(), 16); w.fixed(m.assignedSessionId(), 16); w.text(m.selectedGatewayId());
            w.u16(m.selectedMode().value());
        } else if (message instanceof DialFailed m) {
            w.fixed(m.callId(), 16); w.u16(m.reason().value()); w.u8(m.retryable() ? 1 : 0);
            w.optionalText(m.humanDetail());
        } else if (message instanceof CallProgress m) {
            w.u16(m.phase().value()); w.optionalText(m.detail());
        } else if (message instanceof CallTerminated m) {
            w.text(m.reason()); w.u8(m.source().value()); w.optionalText(m.diagnosticCode());
        } else if (message instanceof DataBytes m) {
            w.u64(m.streamSeq()); w.raw(m.data());
        } else if (message instanceof FlowStatus m) {
            w.u32(m.receiveWindowBytes()); w.u32(m.queuedBytes());
        } else if (message instanceof Ping m) {
            w.u64(m.nonce()); w.u64(m.monotonicHint());
        } else if (message instanceof Pong m) {
            w.u64(m.nonce());
        } else if (message instanceof HangupRequest m) {
            w.text(m.reason());
        } else if (message instanceof HangupAck) {
            // empty payload
        } else {
            throw new IllegalArgumentException("unsupported message type " + message.getClass().getSimpleName());
        }
        if (w.size() > FrameCodec.MAX_PAYLOAD) throw new IllegalArgumentException("encoded payload exceeds protocol maximum");
        return w.toByteArray();
    }

    /**
     * Decodes {@code payload} as the message carried by {@code kind}.
     *
     * @throws ProtocolException on any malformed payload (truncation, trailing bytes, limit
     *     violations, invalid UTF-8, invalid markers, unknown enum values) or for a kind that has
     *     no Beta 0.1 payload codec.
     */
    public static AduMessage decode(FrameKind kind, byte[] payload) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
        Reader r = new Reader(payload);
        AduMessage msg;
        try {
            switch (kind) {
                case HELLO -> {
                    String name = r.text(); String version = r.text(); int min = r.u16(); int max = r.u16();
                    byte[] endpoint = r.fixed(32); List<String> caps = r.strings();
                    msg = new Hello(name, version, min, max, endpoint, caps);
                }
                case HELLO_ACK -> {
                    int selected = r.u16(); String relay = r.text(); long maxPayload = r.u32(); long heartbeat = r.u32();
                    List<String> caps = r.strings();
                    if (maxPayload < 1 || maxPayload > FrameCodec.MAX_PAYLOAD) throw new ProtocolException("max_frame_payload out of range");
                    msg = new HelloAck(selected, relay, (int) maxPayload, heartbeat, caps);
                }
                case HELLO_REJECT -> msg = new HelloReject(r.text());
                case AUTH_BEGIN -> msg = new AuthBegin();
                case AUTH_CHALLENGE -> {
                    byte[] nonce = r.blob16(); String method = r.text();
                    msg = new AuthChallenge(nonce, method);
                }
                case AUTH_RESPONSE -> msg = new AuthResponse(r.blob16());
                case AUTH_OK -> {
                    byte[] endpoint = r.fixed(32); List<Map.Entry<String, String>> policy = r.mapping();
                    msg = new AuthOk(endpoint, policy);
                }
                case AUTH_FAIL -> msg = new AuthFail(r.text());
                case DIAL_REQUEST -> {
                    String target = r.text(); Mode mode = Mode.fromValue(r.u16());
                    ProtocolTransport transport = ProtocolTransport.fromValue(r.u8());
                    List<String> caps = r.strings(); long timeout = r.u32();
                    List<Map.Entry<String, String>> metadata = r.mapping();
                    msg = new DialRequest(target, mode, transport, caps, timeout, metadata);
                }
                case DIAL_ACCEPTED -> {
                    byte[] callId = r.fixed(16); byte[] sessionId = r.fixed(16); String gateway = r.text();
                    Mode mode = Mode.fromValue(r.u16());
                    msg = new DialAccepted(callId, sessionId, gateway, mode);
                }
                case DIAL_FAILED -> {
                    byte[] callId = r.fixed(16); DialFailure reason = DialFailure.fromValue(r.u16()); int retry = r.u8();
                    if (retry != 0 && retry != 1) throw new ProtocolException("invalid retryable flag");
                    String detail = r.optionalText();
                    msg = new DialFailed(callId, reason, retry == 1, detail);
                }
                case CALL_PROGRESS -> {
                    ProgressPhase phase = ProgressPhase.fromValue(r.u16()); String detail = r.optionalText();
                    msg = new CallProgress(phase, detail);
                }
                case CALL_TERMINATED -> {
                    String reason = r.text(); TerminationSource source = TerminationSource.fromValue(r.u8());
                    String code = r.optionalText();
                    msg = new CallTerminated(reason, source, code);
                }
                case DATA_BYTES -> {
                    long seq = r.u64();
                    int remaining = r.remaining();
                    if (remaining < 1 || remaining > MessageLimits.MAX_DATA_BYTES) {
                        throw new ProtocolException("DATA_BYTES data must be 1..32768 bytes");
                    }
                    msg = new DataBytes(seq, r.fixed(remaining));
                }
                case FLOW_STATUS -> {
                    long window = r.u32(); long queued = r.u32();
                    msg = new FlowStatus(window, queued);
                }
                case PING -> {
                    long nonce = r.u64(); long hint = r.u64();
                    msg = new Ping(nonce, hint);
                }
                case PONG -> msg = new Pong(r.u64());
                case HANGUP_REQUEST -> msg = new HangupRequest(r.text());
                case HANGUP_ACK -> msg = new HangupAck();
                default -> throw new ProtocolException("no Beta 0.1 payload codec for frame kind " + kind.value());
            }
        } catch (IllegalArgumentException e) {
            // A wire value that passed the reader's limits but failed message validation.
            throw new ProtocolException(e.getMessage());
        }
        r.finish();
        return msg;
    }

    private static final class Writer {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        int size() { return buf.size(); }
        byte[] toByteArray() { return buf.toByteArray(); }

        void u8(int value) { buf.write(value & 0xFF); }
        void u16(int value) { buf.write((value >>> 8) & 0xFF); buf.write(value & 0xFF); }
        void u32(long value) { for (int shift = 24; shift >= 0; shift -= 8) buf.write((int) ((value >>> shift) & 0xFF)); }
        void u64(long value) { for (int shift = 56; shift >= 0; shift -= 8) buf.write((int) ((value >>> shift) & 0xFF)); }
        void raw(byte[] value) { buf.write(value, 0, value.length); }

        void fixed(byte[] value, int length) {
            if (value.length != length) throw new IllegalArgumentException("expected " + length + " bytes");
            raw(value);
        }

        void blob16(byte[] value) {
            MessageLimits.blob(value, "blob");
            u16(value.length);
            raw(value);
        }

        void text(String value) {
            MessageLimits.string(value, "string");
            byte[] raw = value.getBytes(StandardCharsets.UTF_8);
            u16(raw.length);
            raw(raw);
        }

        void optionalText(String value) {
            u8(value == null ? 0 : 1);
            if (value != null) text(value);
        }

        void strings(List<String> values) {
            MessageLimits.stringList(values, "list");
            u16(values.size());
            for (String value : values) text(value);
        }

        void mapping(List<Map.Entry<String, String>> values) {
            MessageLimits.stringMap(values, "map");
            u16(values.size());
            for (Map.Entry<String, String> entry : values) { text(entry.getKey()); text(entry.getValue()); }
        }
    }

    private static final class Reader {
        private final byte[] payload;
        private int pos;

        Reader(byte[] payload) { this.payload = payload; }

        int remaining() { return payload.length - pos; }

        private int take(int length) {
            if (length < 0 || length > payload.length - pos) throw new ProtocolException("truncated payload");
            int start = pos;
            pos += length;
            return start;
        }

        int u8() { return payload[take(1)] & 0xFF; }

        int u16() {
            int p = take(2);
            return ((payload[p] & 0xFF) << 8) | (payload[p + 1] & 0xFF);
        }

        long u32() {
            int p = take(4);
            long v = 0;
            for (int i = 0; i < 4; i++) v = (v << 8) | (payload[p + i] & 0xFF);
            return v;
        }

        long u64() {
            int p = take(8);
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (payload[p + i] & 0xFF);
            return v;
        }

        byte[] fixed(int length) {
            int p = take(length);
            return Arrays.copyOfRange(payload, p, p + length);
        }

        byte[] blob16() {
            int length = u16();
            if (length > MessageLimits.MAX_BLOB) throw new ProtocolException("blob length exceeds maximum");
            return fixed(length);
        }

        String text() {
            int length = u16();
            if (length > MessageLimits.MAX_SHORT_STRING) throw new ProtocolException("string length exceeds maximum");
            int p = take(length);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                CharBuffer chars = decoder.decode(ByteBuffer.wrap(payload, p, length));
                return chars.toString();
            } catch (CharacterCodingException e) {
                throw new ProtocolException("invalid UTF-8");
            }
        }

        String optionalText() {
            int flag = u8();
            if (flag == 0) return null;
            if (flag != 1) throw new ProtocolException("invalid optional field marker");
            return text();
        }

        List<String> strings() {
            int count = u16();
            if (count > MessageLimits.MAX_CAPABILITIES) throw new ProtocolException("too many string-list entries");
            List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) out.add(text());
            return out;
        }

        List<Map.Entry<String, String>> mapping() {
            int count = u16();
            if (count > MessageLimits.MAX_MAP_ENTRIES) throw new ProtocolException("too many map entries");
            List<Map.Entry<String, String>> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String key = text();
                String value = text();
                out.add(Map.entry(key, value));
            }
            return out;
        }

        void finish() {
            if (pos != payload.length) throw new ProtocolException("trailing payload bytes");
        }
    }
}
