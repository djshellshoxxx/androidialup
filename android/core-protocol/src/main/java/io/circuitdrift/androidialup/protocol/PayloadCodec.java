package io.circuitdrift.androidialup.protocol;

import static io.circuitdrift.androidialup.protocol.Messages.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PayloadCodec {
    private PayloadCodec() {}

    public static FrameKind kindFor(Message message) {
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
        throw new IllegalArgumentException("unsupported message type " + message.getClass().getName());
    }

    public static byte[] encode(Message message) {
        Writer w = new Writer();
        if (message instanceof Hello m) {
            w.text(m.clientName()); w.text(m.clientVersion()); w.u16(m.protocolMin()); w.u16(m.protocolMax()); w.fixed(m.endpointId()); w.strings(m.capabilities());
        } else if (message instanceof HelloAck m) {
            w.u16(m.selectedVersion()); w.text(m.relayId()); w.u32(m.maxFramePayload()); w.u32(m.heartbeatSeconds()); w.strings(m.capabilities());
        } else if (message instanceof HelloReject m) {
            w.text(m.reason());
        } else if (message instanceof AuthBegin) {
            // empty by protocol definition
        } else if (message instanceof AuthChallenge m) {
            w.blob16(m.nonce()); w.text(m.method());
        } else if (message instanceof AuthResponse m) {
            w.blob16(m.proof());
        } else if (message instanceof AuthOk m) {
            w.fixed(m.endpointId()); w.mapping(m.policy());
        } else if (message instanceof AuthFail m) {
            w.text(m.reason());
        } else if (message instanceof DialRequest m) {
            w.text(m.target()); w.u16(m.requestedMode().value()); w.u8(m.networkTransport().value());
            w.strings(m.clientCapabilities()); w.u32(m.dialTimeoutMs()); w.mapping(m.callerMetadata());
        } else if (message instanceof DialAccepted m) {
            w.fixed(m.callId()); w.fixed(m.assignedSessionId()); w.text(m.selectedGatewayId()); w.u16(m.selectedMode().value());
        } else if (message instanceof DialFailed m) {
            w.fixed(m.callId()); w.u16(m.reason().value()); w.u8(m.retryable() ? 1 : 0); w.optionalText(m.humanDetail());
        } else if (message instanceof CallProgress m) {
            w.u16(m.phase().value()); w.optionalText(m.detail());
        } else if (message instanceof CallTerminated m) {
            w.text(m.reason()); w.u8(m.source().value()); w.optionalText(m.diagnosticCode());
        } else if (message instanceof DataBytes m) {
            w.u64(m.streamSeq()); w.fixed(m.data());
        } else if (message instanceof FlowStatus m) {
            w.u32(m.receiveWindowBytes()); w.u32(m.queuedBytes());
        } else if (message instanceof Ping m) {
            w.u64(m.nonce()); w.u64(m.monotonicHint());
        } else if (message instanceof Pong m) {
            w.u64(m.nonce());
        } else if (message instanceof HangupRequest m) {
            w.text(m.reason());
        } else if (message instanceof HangupAck) {
            // empty by protocol definition
        } else {
            throw new IllegalArgumentException("unsupported message type " + message.getClass().getName());
        }
        byte[] result = w.bytes();
        if (result.length > FrameCodec.MAX_PAYLOAD) throw new IllegalArgumentException("encoded payload exceeds protocol maximum");
        return result;
    }

    public static Message decode(FrameKind kind, byte[] payload) {
        Reader r = new Reader(payload);
        Message result = switch (kind) {
            case HELLO -> new Hello(r.text(), r.text(), r.u16(), r.u16(), r.fixed(32), r.strings());
            case HELLO_ACK -> new HelloAck(r.u16(), r.text(), r.u32(), r.u32(), r.strings());
            case HELLO_REJECT -> new HelloReject(r.text());
            case AUTH_BEGIN -> new AuthBegin();
            case AUTH_CHALLENGE -> new AuthChallenge(r.blob16(), r.text());
            case AUTH_RESPONSE -> new AuthResponse(r.blob16());
            case AUTH_OK -> new AuthOk(r.fixed(32), r.mapping());
            case AUTH_FAIL -> new AuthFail(r.text());
            case DIAL_REQUEST -> new DialRequest(r.text(), Mode.fromValue(r.u16()), NetworkTransport.fromValue(r.u8()), r.strings(), r.u32(), r.mapping());
            case DIAL_ACCEPTED -> new DialAccepted(r.fixed(16), r.fixed(16), r.text(), Mode.fromValue(r.u16()));
            case DIAL_FAILED -> new DialFailed(r.fixed(16), DialFailure.fromValue(r.u16()), r.boolByte(), r.optionalText());
            case CALL_PROGRESS -> new CallProgress(ProgressPhase.fromValue(r.u16()), r.optionalText());
            case CALL_TERMINATED -> new CallTerminated(r.text(), TerminationSource.fromValue(r.u8()), r.optionalText());
            case DATA_BYTES -> new DataBytes(r.u64(), r.remaining());
            case FLOW_STATUS -> new FlowStatus(r.u32(), r.u32());
            case PING -> new Ping(r.u64(), r.u64());
            case PONG -> new Pong(r.u64());
            case HANGUP_REQUEST -> new HangupRequest(r.text());
            case HANGUP_ACK -> new HangupAck();
            default -> throw new ProtocolException("frame kind has no I1 payload codec: " + kind);
        };
        r.finish();
        return result;
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void u8(long v){out.write((int)(v&0xff));}
        void u16(long v){out.write((int)((v>>>8)&0xff));out.write((int)(v&0xff));}
        void u32(long v){for(int shift=24;shift>=0;shift-=8)out.write((int)((v>>>shift)&0xff));}
        void u64(long v){for(int shift=56;shift>=0;shift-=8)out.write((int)((v>>>shift)&0xff));}
        void fixed(byte[] b){out.write(b, 0, b.length);}
        void blob16(byte[] b){u16(b.length);fixed(b);}
        void text(String s){byte[] b=s.getBytes(StandardCharsets.UTF_8);u16(b.length);fixed(b);}
        void optionalText(String s){u8(s==null?0:1);if(s!=null)text(s);}
        void strings(List<String> values){u16(values.size());for(String value:values)text(value);}
        void mapping(List<KeyValue> values){u16(values.size());for(KeyValue kv:values){text(kv.key());text(kv.value());}}
        byte[] bytes(){return out.toByteArray();}
    }

    private static final class Reader {
        private final byte[] payload; private int pos;
        Reader(byte[] payload){this.payload=payload.clone();}
        byte[] take(int n){if(n<0||pos+n>payload.length)throw new ProtocolException("truncated payload");byte[] b=java.util.Arrays.copyOfRange(payload,pos,pos+n);pos+=n;return b;}
        int u8(){return Byte.toUnsignedInt(take(1)[0]);}
        int u16(){byte[] b=take(2);return (Byte.toUnsignedInt(b[0])<<8)|Byte.toUnsignedInt(b[1]);}
        long u32(){byte[] b=take(4);return Integer.toUnsignedLong(ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt());}
        long u64(){return ByteBuffer.wrap(take(8)).order(ByteOrder.BIG_ENDIAN).getLong();}
        byte[] fixed(int n){return take(n);}
        byte[] blob16(){int n=u16();if(n>MAX_BLOB)throw new ProtocolException("blob length exceeds maximum");return take(n);}
        String text(){int n=u16();if(n>MAX_SHORT_STRING)throw new ProtocolException("string length exceeds maximum");byte[] raw=take(n);try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();}catch(CharacterCodingException e){throw new ProtocolException("invalid UTF-8");}}
        String optionalText(){int flag=u8();if(flag==0)return null;if(flag!=1)throw new ProtocolException("invalid optional field marker");return text();}
        boolean boolByte(){int value=u8();if(value==0)return false;if(value==1)return true;throw new ProtocolException("invalid boolean marker");}
        List<String> strings(){int n=u16();if(n>MAX_CAPABILITIES)throw new ProtocolException("too many string-list entries");List<String> out=new ArrayList<>(n);for(int i=0;i<n;i++)out.add(text());return List.copyOf(out);}
        List<KeyValue> mapping(){int n=u16();if(n>MAX_MAP_ENTRIES)throw new ProtocolException("too many map entries");List<KeyValue> out=new ArrayList<>(n);for(int i=0;i<n;i++)out.add(new KeyValue(text(),text()));return List.copyOf(out);}
        byte[] remaining(){return take(payload.length-pos);}
        void finish(){if(pos!=payload.length)throw new ProtocolException("trailing payload bytes");}
    }
}
