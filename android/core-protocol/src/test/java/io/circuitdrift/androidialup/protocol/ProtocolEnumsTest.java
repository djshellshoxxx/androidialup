package io.circuitdrift.androidialup.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProtocolEnumsTest {
    @Test
    void modeValuesMatchSpec() {
        assertEquals(1, Mode.BYTE_RELAY.value());
        assertEquals(2, Mode.PCM_VBD_EXPERIMENTAL.value());
        assertEquals(100, Mode.V152_RTP_RESERVED.value());
        assertEquals(101, Mode.V1501_SPRT_RESERVED.value());
        for (Mode m : Mode.values()) assertSame(m, Mode.fromValue(m.value()));
        assertThrows(ProtocolException.class, () -> Mode.fromValue(3));
    }

    @Test
    void transportValuesMatchSpec() {
        assertEquals(1, ProtocolTransport.WIFI.value());
        assertEquals(2, ProtocolTransport.CELLULAR.value());
        assertEquals(3, ProtocolTransport.ETHERNET.value());
        assertEquals(4, ProtocolTransport.OTHER.value());
        for (ProtocolTransport t : ProtocolTransport.values()) assertSame(t, ProtocolTransport.fromValue(t.value()));
        assertThrows(ProtocolException.class, () -> ProtocolTransport.fromValue(0));
        assertThrows(ProtocolException.class, () -> ProtocolTransport.fromValue(5));
    }

    @Test
    void dialFailureValuesMatchSpec() {
        DialFailure[] expected = {
            DialFailure.NO_ROUTE, DialFailure.GATEWAY_UNAVAILABLE, DialFailure.BUSY, DialFailure.NO_DIALTONE,
            DialFailure.NO_ANSWER, DialFailure.AUTHORIZATION_DENIED, DialFailure.UNSUPPORTED_MODE,
            DialFailure.TIMEOUT, DialFailure.INTERNAL_ERROR,
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(i + 1, expected[i].value());
            assertSame(expected[i], DialFailure.fromValue(i + 1));
        }
        assertThrows(ProtocolException.class, () -> DialFailure.fromValue(10));
    }

    @Test
    void progressPhaseValuesMatchSpec() {
        ProgressPhase[] expected = {
            ProgressPhase.ROUTING, ProgressPhase.GATEWAY_CONNECTING, ProgressPhase.DIALING,
            ProgressPhase.RINGBACK, ProgressPhase.NEGOTIATING, ProgressPhase.CONNECTED,
        };
        for (int i = 0; i < expected.length; i++) {
            assertEquals(i + 1, expected[i].value());
            assertSame(expected[i], ProgressPhase.fromValue(i + 1));
        }
        assertThrows(ProtocolException.class, () -> ProgressPhase.fromValue(7));
    }

    @Test
    void terminationSourceValuesMatchSpec() {
        assertEquals(1, TerminationSource.LOCAL.value());
        assertEquals(2, TerminationSource.RELAY.value());
        assertEquals(3, TerminationSource.GATEWAY.value());
        assertEquals(4, TerminationSource.REMOTE.value());
        for (TerminationSource s : TerminationSource.values()) assertSame(s, TerminationSource.fromValue(s.value()));
        assertThrows(ProtocolException.class, () -> TerminationSource.fromValue(-1));
    }
}
