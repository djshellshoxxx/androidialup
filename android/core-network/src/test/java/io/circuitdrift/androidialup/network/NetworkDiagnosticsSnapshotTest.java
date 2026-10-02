package io.circuitdrift.androidialup.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class NetworkDiagnosticsSnapshotTest {
    @Test
    void selectedCandidateFieldsAndDecisionArePreserved() {
        var candidate = new NetworkCandidate(
                "42", NetworkTransport.WIFI, true, true, false, false, 23.5, 0.01);
        var decision = new NetworkSelectionEngine.SelectionDecision(
                "42", true, false, null, "HANDOVER_MARGIN_MET", Map.of("42", 966.5));

        var snapshot = NetworkDiagnosticsSnapshot.from(
                NetworkPolicy.PREFER_WIFI, candidate, decision, true, false);

        assertEquals(NetworkPolicy.PREFER_WIFI, snapshot.policy());
        assertEquals("42", snapshot.selectedId());
        assertEquals(NetworkTransport.WIFI, snapshot.selectedTransport());
        assertTrue(snapshot.validated());
        assertFalse(snapshot.metered());
        assertFalse(snapshot.roaming());
        assertEquals(23.5, snapshot.relayProbeRttMs(), 0.0001);
        assertEquals(0.01, snapshot.relayProbeLoss(), 0.0001);
        assertEquals("HANDOVER_MARGIN_MET", snapshot.selectionReason());
        assertTrue(snapshot.activeCall());
        assertFalse(snapshot.betterNetworkAvailable());
        assertEquals(966.5, snapshot.scores().get("42"), 0.0001);
    }

    @Test
    void noSelectionProducesSafeEmptySnapshot() {
        var decision = new NetworkSelectionEngine.SelectionDecision(
                null, true, false, null, "NO_ELIGIBLE_NETWORK", Map.of());
        var snapshot = NetworkDiagnosticsSnapshot.from(
                NetworkPolicy.CELLULAR_ONLY, null, decision, false, false);
        assertNull(snapshot.selectedId());
        assertNull(snapshot.selectedTransport());
        assertFalse(snapshot.validated());
        assertNull(snapshot.relayProbeRttMs());
        assertEquals("NO_ELIGIBLE_NETWORK", snapshot.selectionReason());
    }

    @Test
    void activeCallReportsSuppressedBetterNetworkIdentity() {
        var current = new NetworkCandidate(
                "cell", NetworkTransport.CELLULAR, true, true, true, false, 300.0, 0.0);
        var decision = new NetworkSelectionEngine.SelectionDecision(
                "cell", false, true, "wifi", "ACTIVE_CALL_NO_MIGRATION",
                Map.of("cell", 700.0, "wifi", 990.0));
        var snapshot = NetworkDiagnosticsSnapshot.from(
                NetworkPolicy.AUTOMATIC, current, decision, true, false);
        assertTrue(snapshot.betterNetworkAvailable());
        assertEquals("wifi", snapshot.betterNetworkId());
        assertEquals("ACTIVE_CALL_NO_MIGRATION", snapshot.selectionReason());
    }
}
