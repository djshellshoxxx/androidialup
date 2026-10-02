package io.circuitdrift.androidialup.platform;

import static org.junit.Assert.*;

import io.circuitdrift.androidialup.network.NetworkTransport;
import org.junit.Test;

public class CandidateNormalizerTest {
    @Test
    public void mapsWifiCapabilitiesIntoCoreCandidate() {
        var candidate = CandidateNormalizer.normalize(
                "42", NetworkTransport.WIFI,
                true, true, true, true,
                25.0, 0.01);
        assertEquals("42", candidate.id());
        assertEquals(NetworkTransport.WIFI, candidate.transport());
        assertTrue(candidate.internet());
        assertTrue(candidate.validated());
        assertFalse(candidate.metered());
        assertFalse(candidate.roaming());
        assertEquals(25.0, candidate.relayProbeRttMs(), 0.0001);
        assertEquals(0.01, candidate.relayProbeLoss(), 0.0001);
    }

    @Test
    public void notMeteredAndNotRoamingFlagsInvertCleanly() {
        var candidate = CandidateNormalizer.normalize(
                "7", NetworkTransport.CELLULAR,
                true, true, false, false,
                null, null);
        assertTrue(candidate.metered());
        assertTrue(candidate.roaming());
    }

    @Test
    public void preservesCoreMeasurementNormalization() {
        var candidate = CandidateNormalizer.normalize(
                "9", NetworkTransport.WIFI,
                true, true, true, true,
                Double.NaN, 2.0);
        assertNull(candidate.relayProbeRttMs());
        assertEquals(1.0, candidate.relayProbeLoss(), 0.0001);
    }
}
